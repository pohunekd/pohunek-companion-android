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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HostLiveTest {
    private val route = HostRoute.parse("127.0.0.1:1")
    private val config = LiveConfig(
        reseedInterval = 60.seconds,
        reconnectInitial = 500.milliseconds,
        reconnectMax = 30.seconds,
        livenessInterval = 10.seconds,
        livenessTimeout = 5.seconds,
        idleThreshold = 300.seconds,
        notificationPageSize = 200,
    )

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
    fun `a connection that drops soon keeps the failure count growing`() = runTest {
        assertEquals(3, attemptAfterConnectionHeld(5_000))
    }

    @Test
    fun `a connection that held for the maximum wait resets the failure count`() = runTest {
        assertEquals(1, attemptAfterConnectionHeld(config.reconnectMax.inWholeMilliseconds))
    }

    @Test
    fun `a failed health probe fails the connection at the probe interval`() = runTest {
        val transport = FakeTransport()
        transport.probe = { throw ProtocolClientException.ConnectionFailed(route, IOException("unreachable")) }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = collect(transport, updates)

        advanceTimeBy(config.livenessInterval)
        runCurrent()

        assertEquals(config.livenessInterval.inWholeMilliseconds, updates.single { it.second is LiveUpdate.Reconnecting }.first)
        job.cancel()
    }

    @Test
    fun `a probe that never answers fails the connection after the liveness timeout`() = runTest {
        val transport = FakeTransport()
        transport.probe = { awaitCancellation() }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = collect(transport, updates)

        advanceTimeBy(config.livenessInterval + config.livenessTimeout)
        runCurrent()

        val failed = updates.single { it.second is LiveUpdate.Reconnecting }
        assertEquals((config.livenessInterval + config.livenessTimeout).inWholeMilliseconds, failed.first)
        job.cancel()
    }

    @Test
    fun `a failed re-seed fails the connection and the next connection seeds again`() = runTest {
        val transport = FakeTransport()
        var seeds = 0
        transport.seed = {
            if (++seeds == 2) throw ProtocolClientException.ConnectionLost("seed") else listOf(session("s-$seeds"))
        }
        val updates = mutableListOf<Pair<Long, LiveUpdate>>()
        val job = collect(transport, updates)

        advanceTimeBy(config.reseedInterval + config.reconnectInitial)
        runCurrent()

        assertEquals(config.reseedInterval.inWholeMilliseconds, updates.single { it.second is LiveUpdate.Reconnecting }.first)
        assertEquals(listOf("s-3"), updates.lastSessions().map { it.info.id })
        assertEquals(2, updates.count { it.second == LiveUpdate.Connected })
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
