package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.LogLevel
import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.protocol.AgentActivity
import dev.pohunek.companion.protocol.DaemonEvent
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.NotificationKind
import dev.pohunek.companion.protocol.NotificationRecord
import dev.pohunek.companion.protocol.NotificationStatus
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.SessionCapabilities
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionState
import dev.pohunek.companion.protocol.StateSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HostLiveTest {
    private val route = HostRoute.parse("127.0.0.1:1")
    private val config = LiveConfig(
        reseedInterval = 60.seconds,
        reconnectInitial = 500.milliseconds,
        reconnectMax = 30.seconds,
        backoffReset = 5.seconds,
        liveness = Liveness(interval = 10.seconds, timeout = 5.seconds, failures = 2, retry = 2.seconds),
        idleThreshold = 300.seconds,
        notificationPageSize = 200,
    )

    private val detectionBound =
        config.liveness.interval + config.liveness.timeout * config.liveness.failures + config.liveness.retry * (config.liveness.failures - 1)

    private class FakeStream : EventStream {
        val inbox = Channel<Any>(Channel.UNLIMITED)

        override val events = flow {
            for (message in inbox) {
                when (message) {
                    is DaemonEvent -> emit(message)
                    is ProtocolClientException -> throw message
                }
            }
        }

        override fun close() {
            inbox.close()
        }
    }

    private class FakeTransport : HostTransport {
        val streams = mutableListOf<FakeStream>()
        var subscribe: suspend () -> EventStream = { FakeStream().also { streams += it } }
        var seed: suspend () -> List<SessionInfo> = { emptyList() }
        var notifications: List<NotificationRecord> = emptyList()
        var probe: suspend () -> Unit = {}

        override suspend fun subscribe(): EventStream = subscribe.invoke()

        override suspend fun fetchSeed(): Seed = Seed(seed.invoke(), notifications)

        override suspend fun probe() = probe.invoke()
    }

    private object NoLog : AppLog {
        override fun log(level: LogLevel, event: String, fields: List<LogField>) = Unit
    }

    private fun session(
        id: String,
        updatedAt: String = "2026-01-01T10:00:00Z",
        name: String? = null,
        activity: AgentActivity? = null,
    ) = SessionInfo(
        id = id,
        capabilities = SessionCapabilities(resume = false, fork = false),
        name = name,
        agent = "shell",
        agentBase = "shell",
        cwd = "/work",
        pid = 1,
        cols = 80,
        rows = 24,
        state = SessionState.Running,
        stateSource = StateSource.Process,
        activity = activity,
        createdAt = "2026-01-01T09:00:00Z",
        updatedAt = updatedAt,
    )

    private val lost = ProtocolClientException.ConnectionLost("test")

    private fun TestScope.collect(transport: HostTransport, into: MutableList<Pair<Long, LiveUpdate>>) =
        launch { HostLive(route, transport, config, testScheduler.timeSource, NoLog).updates().collect { into += currentTime to it } }

    private fun List<Pair<Long, LiveUpdate>>.lastSessions() = map { it.second }.filterIsInstance<LiveUpdate.Sessions>().last().sessions

    @Test
    fun `an event that arrives while the seed is fetched is applied after the seed`() = runTest {
        val transport = FakeTransport()
        val seed = CompletableDeferred<List<SessionInfo>>()
        transport.seed = { seed.await() }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = collect(transport, updates)
        runCurrent()

        transport.streams.single().inbox.trySend(DaemonEvent.SessionCreated(session("s-2")))
        runCurrent()
        seed.complete(listOf(session("s-1")))
        runCurrent()

        assertEquals(listOf("s-1", "s-2"), updates.lastSessions().map { it.info.id })
        assertEquals(1, updates.count { it.second is LiveUpdate.Sessions })
        job.cancel()
    }

    @Test
    fun `a seed older than an event that arrived during the fetch does not overwrite it`() = runTest {
        val transport = FakeTransport()
        val seed = CompletableDeferred<List<SessionInfo>>()
        transport.seed = { seed.await() }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = collect(transport, updates)
        runCurrent()

        transport.streams.single().inbox.trySend(DaemonEvent.SessionUpdated(session("s-1", "2026-01-01T10:00:09Z", name = "new")))
        runCurrent()
        seed.complete(listOf(session("s-1", "2026-01-01T10:00:00Z", name = "old")))
        runCurrent()

        assertEquals(listOf<String?>("new"), updates.lastSessions().map { it.info.name })
        job.cancel()
    }

    @Test
    fun `a re-seed buffers events the same way`() = runTest {
        val transport = FakeTransport()
        transport.seed = { listOf(session("s-1", "2026-01-01T10:00:00Z", name = "old")) }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = collect(transport, updates)
        runCurrent()

        val reseed = CompletableDeferred<List<SessionInfo>>()
        transport.seed = { reseed.await() }
        advanceTimeBy(config.reseedInterval)
        runCurrent()
        transport.streams.single().inbox.trySend(DaemonEvent.SessionUpdated(session("s-1", "2026-01-01T10:00:09Z", name = "new")))
        runCurrent()
        reseed.complete(listOf(session("s-1", "2026-01-01T10:00:00Z", name = "old")))
        runCurrent()

        assertEquals(listOf<String?>("new"), updates.lastSessions().map { it.info.name })
        assertTrue(updates.any { it.second == LiveUpdate.Reseeded })
        job.cancel()
    }

    @Test
    fun `a re-seed that finds a change no event explained ends the connection`() = runTest {
        val transport = FakeTransport()
        transport.seed = { listOf(session("s-1", name = "old")) }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = collect(transport, updates)
        runCurrent()

        transport.seed = { listOf(session("s-1", "2026-01-01T10:00:09Z", name = "new")) }
        advanceTimeBy(config.reseedInterval)
        runCurrent()
        assertEquals(listOf<String?>("new"), updates.lastSessions().map { it.info.name })
        assertTrue(updates.last().second is LiveUpdate.Reconnecting)

        advanceTimeBy(config.reconnectInitial + 1.milliseconds)
        assertEquals(2, transport.streams.size)
        job.cancel()
    }

    @Test
    fun `reconnect waits double up to the maximum`() = runTest {
        val transport = FakeTransport()
        transport.subscribe = { throw ProtocolClientException.ConnectionFailed(route, IOException("refused")) }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = collect(transport, updates)

        advanceTimeBy(200.seconds)
        runCurrent()

        val reconnects = updates.filter { it.second is LiveUpdate.Reconnecting }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), reconnects.take(8).map { (it.second as LiveUpdate.Reconnecting).attempt })
        assertEquals(
            listOf(500L, 1000, 2000, 4000, 8000, 16000, 30000),
            reconnects.map { it.first }.zipWithNext { a, b -> b - a }.take(7),
        )
        job.cancel()
    }

    @Test
    fun `a network event ends the backoff wait once and keeps the failure count`() = runTest {
        val transport = FakeTransport()
        var subscribes = 0
        transport.subscribe = {
            subscribes++
            throw ProtocolClientException.ConnectionFailed(route, IOException("refused"))
        }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val live = HostLive(route, transport, config, testScheduler.timeSource, NoLog)
        val job = launch { live.updates().collect { updates += currentTime to it } }
        runCurrent()
        assertEquals(1, subscribes)

        live.networkAvailable()
        live.networkAvailable()
        runCurrent()
        assertEquals(2, subscribes)
        assertEquals(0L, currentTime)

        // The early attempt failed as attempt 2: the next wait is the doubled one, not a fresh initial wait.
        advanceTimeBy(999)
        runCurrent()
        assertEquals(2, subscribes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, subscribes)
        job.cancel()
    }

    @Test
    fun `a network event while connected leaves the next backoff wait alone`() = runTest {
        val transport = FakeTransport()
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val live = HostLive(route, transport, config, testScheduler.timeSource, NoLog)
        val job = launch { live.updates().collect { updates += currentTime to it } }
        runCurrent()
        assertTrue(updates.any { it.second == LiveUpdate.Connected })

        live.networkAvailable()
        runCurrent()
        transport.subscribe = { throw ProtocolClientException.ConnectionFailed(route, IOException("refused")) }
        transport.streams.single().inbox.trySend(lost)
        runCurrent()
        val before = transport.streams.size
        advanceTimeBy(499)
        runCurrent()
        assertEquals(1, updates.count { it.second is LiveUpdate.Reconnecting })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, updates.count { it.second is LiveUpdate.Reconnecting })
        assertEquals(before, transport.streams.size)
        job.cancel()
    }

    private fun TestScope.attemptAfterConnectionHeld(heldMillis: Long): Int {
        val transport = FakeTransport()
        var subscribes = 0
        transport.subscribe = {
            if (++subscribes <= 2) throw ProtocolClientException.ConnectionFailed(route, IOException("refused"))
            FakeStream().also { transport.streams += it }
        }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = collect(transport, updates)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(updates.any { it.second == LiveUpdate.Connected })
        advanceTimeBy(heldMillis)
        transport.streams.single().inbox.trySend(lost)
        runCurrent()
        val attempt = updates.map { it.second }.filterIsInstance<LiveUpdate.Reconnecting>().last().attempt
        job.cancel()
        return attempt
    }

    @Test
    fun `a connection that drops before the reset hold keeps the failure count growing`() = runTest {
        assertEquals(3, attemptAfterConnectionHeld(4_000))
    }

    @Test
    fun `a connection that held for the reset hold resets the failure count`() = runTest {
        assertEquals(1, attemptAfterConnectionHeld(config.backoffReset.inWholeMilliseconds))
    }

    @Test
    fun `one failed probe followed by a good one keeps the connection`() = runTest {
        val transport = FakeTransport()
        var probes = 0
        transport.probe = { if (++probes == 1) throw ProtocolClientException.ConnectionFailed(route, IOException("unreachable")) }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = collect(transport, updates)

        advanceTimeBy(config.liveness.interval + config.liveness.retry + config.liveness.interval)
        runCurrent()

        assertEquals(3, probes)
        assertTrue(updates.none { it.second is LiveUpdate.Reconnecting })
        job.cancel()
    }

    @Test
    fun `failed probes in a row end the connection within the documented bound`() = runTest {
        val transport = FakeTransport()
        transport.probe = { awaitCancellation() }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = collect(transport, updates)

        advanceTimeBy(detectionBound - 1.milliseconds)
        runCurrent()
        assertTrue(updates.none { it.second is LiveUpdate.Reconnecting })
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(detectionBound.inWholeMilliseconds, updates.single { it.second is LiveUpdate.Reconnecting }.first)
        job.cancel()
    }

    @Test
    fun `a failed re-seed followed by a good probe keeps the connection and is retried`() = runTest {
        val transport = FakeTransport()
        var seeds = 0
        transport.seed = {
            if (++seeds == 2) throw ProtocolClientException.ConnectionLost("seed") else listOf(session("s-1"))
        }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = collect(transport, updates)

        advanceTimeBy(config.reseedInterval + config.liveness.retry)
        runCurrent()

        assertEquals(3, seeds)
        assertTrue(updates.none { it.second is LiveUpdate.Reconnecting })
        assertTrue(updates.any { it.second == LiveUpdate.Reseeded })
        job.cancel()
    }

    @Test
    fun `failed re-seeds in a row end the connection after the retry wait`() = runTest {
        val transport = FakeTransport()
        var seeds = 0
        transport.seed = { if (++seeds >= 2) throw ProtocolClientException.ConnectionLost("seed") else emptyList() }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        // A good probe would reset the count, so none falls inside the test.
        val noProbes = LiveConfig(
            reseedInterval = config.reseedInterval,
            reconnectInitial = config.reconnectInitial,
            reconnectMax = config.reconnectMax,
            backoffReset = config.backoffReset,
            liveness = Liveness(interval = 1.hours, timeout = config.liveness.timeout, failures = 2, retry = config.liveness.retry),
            idleThreshold = config.idleThreshold,
            notificationPageSize = config.notificationPageSize,
        )
        val job = launch {
            HostLive(route, transport, noProbes, testScheduler.timeSource, NoLog).updates().collect { updates += currentTime to it }
        }

        advanceTimeBy(config.reseedInterval + config.liveness.retry)
        runCurrent()

        val expected = (config.reseedInterval + config.liveness.retry).inWholeMilliseconds
        assertEquals(expected, updates.single { it.second is LiveUpdate.Reconnecting }.first)
        job.cancel()
    }

    @Test
    fun `an idle session is published as Idle after the threshold without any event`() = runTest {
        val transport = FakeTransport()
        transport.seed = { listOf(session("s-1", activity = AgentActivity.Idle)) }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = collect(transport, updates)
        runCurrent()
        assertEquals(listOf(OwnerState.Working), updates.lastSessions().map { it.ownerState })

        advanceTimeBy(config.idleThreshold - 1.milliseconds)
        runCurrent()
        assertEquals(1, updates.count { it.second is LiveUpdate.Sessions })
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(listOf(OwnerState.Idle), updates.lastSessions().map { it.ownerState })
        assertEquals(config.idleThreshold.inWholeMilliseconds, updates.last { it.second is LiveUpdate.Sessions }.first)
        job.cancel()
    }

    @Test
    fun `an attention notification event lifts a session and its deletion clears it without a re-seed`() = runTest {
        val transport = FakeTransport()
        transport.seed = { listOf(session("s-1", activity = AgentActivity.Idle)) }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = collect(transport, updates)
        runCurrent()

        val record = NotificationRecord("n-1", NotificationKind.AgentBlocked, NotificationStatus.Unread, "2026-01-01T10:00:00Z", "s-1")
        val inbox = transport.streams.single().inbox
        inbox.trySend(DaemonEvent.NotificationCreated(record))
        runCurrent()
        assertEquals(listOf(OwnerState.NeedsOwner), updates.lastSessions().map { it.ownerState })

        inbox.trySend(DaemonEvent.NotificationDeleted("n-1"))
        runCurrent()
        assertEquals(listOf(OwnerState.Working), updates.lastSessions().map { it.ownerState })
        assertEquals(0, updates.count { it.second == LiveUpdate.Reseeded })
        job.cancel()
    }

    @Test
    fun `a reconnect keeps the idle clock and publishes nothing for an identical seed`() = runTest {
        val transport = FakeTransport()
        transport.seed = { listOf(session("s-1", activity = AgentActivity.Idle)) }
        val state = LiveState(route, testScheduler.timeSource, config.idleThreshold)
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = launch { HostLive(route, transport, config, testScheduler.timeSource, NoLog, state).updates().collect { updates += currentTime to it } }
        advanceTimeBy(200.seconds)
        transport.streams.single().inbox.trySend(lost)
        advanceTimeBy(config.reconnectInitial + 1.milliseconds)
        assertEquals(2, transport.streams.size)
        assertEquals(1, updates.count { it.second is LiveUpdate.Sessions })

        advanceTimeBy(100.seconds)
        runCurrent()
        assertEquals(listOf(OwnerState.Idle), updates.lastSessions().map { it.ownerState })
        job.cancel()

        // A later collection on the same state, as after a foreground return, finds the list unchanged.
        val again = mutableListOf<Pair<Long, LiveUpdate>>()
        val second = launch { HostLive(route, transport, config, testScheduler.timeSource, NoLog, state).updates().collect { again += currentTime to it } }
        runCurrent()
        assertTrue(again.any { it.second == LiveUpdate.Connected })
        assertEquals(0, again.count { it.second is LiveUpdate.Sessions })
        second.cancel()
    }
}
