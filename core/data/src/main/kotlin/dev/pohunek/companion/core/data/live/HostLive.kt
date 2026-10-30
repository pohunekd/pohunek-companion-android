// Ported to Kotlin from pohunek-companion `crates/core/src/live.rs` (run_live, cycle_inner, reseed, backoff) and `connect.rs` (fetch_seed);
// the liveness probe and the failing re-seed are additions. Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd.
// See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.data.log.logCode
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.core.domain.log.warn
import dev.pohunek.companion.core.domain.sessions.EventOutcome
import dev.pohunek.companion.core.domain.sessions.OwnerSession
import dev.pohunek.companion.core.domain.sessions.SessionStore
import dev.pohunek.companion.protocol.DaemonEvent
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.ProtocolError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Wait before reconnect number [failures] (1-based): the initial wait doubled per earlier failure, capped at the maximum. */
internal fun backoffDelay(config: LiveConfig, failures: Int): Duration {
    val shift = (failures - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
    val initial = config.reconnectInitial.inWholeMilliseconds
    val scaled = if (initial > Long.MAX_VALUE shr shift) Long.MAX_VALUE else initial shl shift
    return minOf(scaled, config.reconnectMax.inWholeMilliseconds).milliseconds
}

/** Larger shifts only overflow; the cap is reached long before. */
private const val MAX_BACKOFF_SHIFT = 32

private sealed interface StreamMessage {
    data class Event(val event: DaemonEvent) : StreamMessage

    data class Failed(val error: ProtocolClientException) : StreamMessage
}

private sealed interface Wake {
    data class Message(val message: StreamMessage) : Wake

    data object Refresh : Wake

    data object Timer : Wake
}

/** What ended a wait between two connection attempts. */
private enum class WaitEnd { Elapsed, Refresh, Network }

/**
 * What one host's loop keeps across its connections and restarts: the session store and the last list handed to the collector. A seed
 * on a new connection reconciles into this store, so idle clocks survive and an unchanged list is not published again.
 */
class LiveState(val store: SessionStore) {
    internal var shown: List<OwnerSession>? = null

    constructor(route: HostRoute, timeSource: TimeSource.WithComparableMarks, idleThreshold: Duration) :
        this(SessionStore(route.toString(), timeSource, idleThreshold))
}

/**
 * Keeps one daemon's sessions current: subscribe first so no event is lost, seed on a second connection, apply the events that arrived
 * meanwhile, then stream. Every [LiveConfig.reseedInterval] the seed is taken again the same way. A broken stream ends the connection at
 * once; so do [Liveness.failures] failed re-seeds or health probes in a row, and a re-seed that finds a change no buffered event
 * explains when no event follows within [Liveness.retry] (a dead subscription the probes cannot see). The loop then reconnects with a growing wait. Collecting
 * [updates] runs the loop until the collector is cancelled; each collection starts with a fresh failure count and reconciles into [state].
 */
@Suppress("TooManyFunctions") // The loop and its helpers share the wake channels and the connection state.
class HostLive(
    route: HostRoute,
    private val transport: HostTransport,
    private val config: LiveConfig,
    private val timeSource: TimeSource.WithComparableMarks,
    private val log: AppLog,
    private val state: LiveState = LiveState(route, timeSource, config.idleThreshold),
) {
    private val hostKey = hostKeyField(route)
    private val refresh = Channel<Unit>(Channel.CONFLATED)

    private val network = Channel<Unit>(Channel.CONFLATED)

    /** True only while the loop waits between two attempts; a network event at any other time has nothing to cut short. */
    @Volatile
    private var waiting = false

    /** Asks the running loop to re-seed now, or to stop waiting for the next reconnect. */
    fun requestReseed() {
        refresh.trySend(Unit)
    }

    /** Ends the wait before the next reconnect, once per call; a loop that is connected or mid-attempt ignores it, and the failure count stays. */
    fun networkAvailable() {
        if (waiting) network.trySend(Unit)
    }

    fun updates(): Flow<LiveUpdate> = flow {
        var failures = 0
        var early = false
        state.store.idleThreshold = config.idleThreshold
        while (true) {
            val connection = Connection(state.store, this)
            val attemptFields = listOf(hostKey, LogField.number("attempt", failures + 1L))
            log.info("live.connecting", if (early) attemptFields + LogField.number("early", 1) else attemptFields)
            val error = try {
                connect(connection)
            } catch (e: ProtocolClientException) {
                e
            }
            val held = connection.establishedAt
            if (held != null && held.elapsedNow() >= config.backoffReset) failures = 0
            failures++
            val delay = backoffDelay(config, failures)
            logEnded(connection.store, held != null, error, failures, delay)
            emit(LiveUpdate.Reconnecting(failures, error.message.orEmpty(), error.protocolCode()))
            early = waitForNextAttempt(delay) == WaitEnd.Network
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun waitForNextAttempt(delay: Duration): WaitEnd {
        network.tryReceive()
        waiting = true
        try {
            return select {
                refresh.onReceive { WaitEnd.Refresh }
                network.onReceive { WaitEnd.Network }
                onTimeout(delay) { WaitEnd.Elapsed }
            }
        } finally {
            waiting = false
        }
    }

    /** State of one connection attempt; only the loop coroutine touches it. */
    private inner class Connection(val store: SessionStore, val out: FlowCollector<LiveUpdate>) {
        val inbox = Channel<StreamMessage>(Channel.UNLIMITED)
        var establishedAt: ComparableTimeMark? = null
        var reseedDue: ComparableTimeMark = timeSource.markNow()
        var probeDue: ComparableTimeMark = timeSource.markNow()

        /** Probes and re-seeds that failed in a row since the last one that succeeded. */
        var failedChecks = 0

        /**
         * Set by a re-seed that changed the store although no event was waiting to explain it: the time by which an event must arrive
         * (one still in flight on a healthy stream), else the subscription is taken as dead.
         */
        var suspectDue: ComparableTimeMark? = null

        /**
         * Records a failed probe or re-seed and returns after logging it as [event], or throws [error] once [Liveness.failures]
         * checks failed in a row.
         */
        fun checkFailed(event: String, error: ProtocolClientException) {
            failedChecks++
            if (failedChecks >= config.liveness.failures) throw error
            log.warn(event, listOf(hostKey, LogField.errorCode(error.logCode()), LogField.number("failures", failedChecks.toLong())))
        }

        /**
         * Reconciles the store with a fresh seed, then applies the events that arrived while it was fetched. A re-seed that cannot be
         * fetched counts as a failed check and is retried after [Liveness.retry]; the initial seed fails the connection.
         */
        suspend fun seed(initial: Boolean): Boolean {
            val seed = if (initial) {
                transport.fetchSeed()
            } else {
                try {
                    transport.fetchSeed()
                } catch (e: ProtocolClientException) {
                    checkFailed("live.reseed_failed", e)
                    reseedDue = timeSource.markNow() + config.liveness.retry
                    return false
                }
            }
            failedChecks = 0
            val change = store.applySeed(seed.sessions, seed.notifications)
            val buffered = drain()
            val counts = listOf(
                hostKey,
                LogField.number("sessions", seed.sessions.size.toLong()),
                LogField.number("attention", seed.notifications.size.toLong()),
                LogField.number("added", change.added.toLong()),
                LogField.number("removed", change.removed.toLong()),
                LogField.number("changed", change.changed.toLong()),
                LogField.number("attention_changed", change.attention.toLong()),
                LogField.number("buffered", buffered.toLong()),
            )
            // Events that arrived while the seed was fetched explain a difference; only an unexplained one is a missed event.
            when {
                initial -> log.info("live.seeded", counts)
                change.isEmpty || buffered > 0 -> log.info("live.reseeded", counts)
                else -> {
                    log.warn("live.reseed_corrected", counts)
                    suspectDue = timeSource.markNow() + config.liveness.retry
                }
            }
            val now = timeSource.markNow()
            reseedDue = now + config.reseedInterval
            probeDue = now + config.liveness.interval
            return true
        }

        /** Time until the next session turns Idle; unbounded while none is waiting on the idle threshold. */
        fun untilIdleDeadline(): Duration = store.nextIdleDeadline()?.let { until(it) } ?: Duration.INFINITE

        /** Applies the buffered messages and returns how many there were. */
        fun drain(): Int {
            var count = 0
            while (true) {
                apply(inbox.tryReceive().getOrNull() ?: return count)
                count++
            }
        }

        /** Returns whether the store changed. */
        fun apply(message: StreamMessage): Boolean = when (message) {
            is StreamMessage.Failed -> throw message.error
            is StreamMessage.Event -> {
                suspectDue = null
                store.applyEvent(message.event) == EventOutcome.Applied
            }
        }

        /** Time until a suspected dead subscription is given up on; unbounded while none is suspected. */
        fun untilSuspectDue(): Duration = suspectDue?.let { until(it) } ?: Duration.INFINITE

        /** The subscription is the only way events reach the store, and the seed and probe connections cannot tell that it is dead. */
        fun failIfSuspectExpired() {
            if (suspectDue?.hasPassedNow() == true) {
                throw ProtocolClientException.ConnectionLost("the re-seed found a change the event stream never delivered")
            }
        }

        suspend fun probe() {
            val error = try {
                withTimeoutOrNull(config.liveness.timeout) { transport.probe() }
                    ?: ProtocolClientException.RequestTimeout(config.liveness.timeout.inWholeMilliseconds)
            } catch (e: ProtocolClientException) {
                e
            }
            if (error is ProtocolClientException) {
                checkFailed("live.probe_failed", error)
                probeDue = timeSource.markNow() + config.liveness.retry
            } else {
                failedChecks = 0
                probeDue = timeSource.markNow() + config.liveness.interval
            }
        }

        /** Emits the sessions when they, their owner state or their waiting time differ from the last list emitted. */
        suspend fun publish() {
            val current = store.ownerSessions().sortedBy { it.info.id }
            val previous = state.shown
            if (current == previous) return
            if (previous != null) logOwnerStateChanges(previous, current)
            out.emit(LiveUpdate.Sessions(current))
            // Recorded only after the collector took it, so a cancelled emit is published again by the next loop.
            state.shown = current
        }
    }

    private suspend fun connect(connection: Connection): Nothing {
        val stream = transport.subscribe()
        try {
            coroutineScope<Nothing> {
                launch { readEvents(stream, connection.inbox) }
                connection.seed(initial = true)
                connection.establishedAt = timeSource.markNow()
                refresh.tryReceive()
                connection.out.emit(LiveUpdate.Connected)
                connection.publish()
                follow(connection)
            }
        } finally {
            stream.close()
        }
    }

    private suspend fun follow(connection: Connection): Nothing {
        while (true) step(connection)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun step(connection: Connection) {
        val wake = select {
            connection.inbox.onReceive { Wake.Message(it) }
            refresh.onReceive { Wake.Refresh }
            val timeout = minOf(until(connection.reseedDue), until(connection.probeDue), connection.untilIdleDeadline(), connection.untilSuspectDue())
            onTimeout(timeout) { Wake.Timer }
        }
        when {
            wake is Wake.Message -> if (connection.apply(wake.message)) connection.publish()
            wake == Wake.Timer && connection.suspectDue?.hasPassedNow() == true -> connection.failIfSuspectExpired()
            wake == Wake.Refresh || connection.reseedDue.hasPassedNow() -> reseed(connection)
            connection.probeDue.hasPassedNow() -> {
                connection.probe()
                // The probe can outlast an idle deadline, which then no longer wakes the loop.
                connection.publish()
            }
            else -> connection.publish()
        }
    }

    private suspend fun reseed(connection: Connection) {
        if (!connection.seed(initial = false)) return
        connection.publish()
        connection.out.emit(LiveUpdate.Reseeded)
    }

    private fun logOwnerStateChanges(previous: List<OwnerSession>, current: List<OwnerSession>) {
        val before = previous.associate { it.info.id to it.ownerState }
        for (session in current) {
            val was = before[session.info.id] ?: continue
            if (was != session.ownerState) {
                val fields = listOf(hostKey, LogField.sessionId(session.info.id), LogField.previousState(was.name), LogField.state(session.ownerState.name))
                log.info("live.owner_state", fields)
            }
        }
    }

    private fun until(due: ComparableTimeMark): Duration = (-due.elapsedNow()).coerceAtLeast(Duration.ZERO)

    private suspend fun readEvents(stream: EventStream, inbox: SendChannel<StreamMessage>) {
        try {
            stream.events.collect { inbox.send(StreamMessage.Event(it)) }
            inbox.send(StreamMessage.Failed(ProtocolClientException.ConnectionLost("the event stream ended")))
        } catch (e: ProtocolClientException) {
            inbox.send(StreamMessage.Failed(e))
        }
    }

    private fun logEnded(store: SessionStore, established: Boolean, error: ProtocolClientException, failures: Int, delay: Duration) {
        val report = store.takeReport()
        val fields = listOf(
            hostKey,
            LogField.errorCode(error.logCode()),
            LogField.number("attempt", failures.toLong()),
            LogField.number("delay_ms", delay.inWholeMilliseconds),
            LogField.number("events_applied", report.applied.values.sum().toLong()),
            LogField.number("events_ignored", report.ignored.values.sum().toLong()),
        )
        log.warn(if (established) "live.disconnected" else "live.connect_failed", fields)
    }
}

/** The daemon's protocol error code when it refused the connection with one. */
private fun ProtocolClientException.protocolCode(): String? = when (this) {
    is ProtocolClientException.Daemon -> error.code
    is ProtocolClientException.VersionMismatch -> ProtocolError.VERSION_MISMATCH_CODE
    else -> null
}
