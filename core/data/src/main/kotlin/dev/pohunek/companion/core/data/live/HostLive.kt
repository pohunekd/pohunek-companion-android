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

/**
 * Keeps one daemon's sessions current: subscribe first so no event is lost, seed on a second connection, apply the events that arrived
 * meanwhile, then stream. Every [LiveConfig.reseedInterval] the seed is taken again the same way. A failed re-seed or a failed health
 * probe ends the connection like a broken stream does, and the loop reconnects with a growing wait. Collecting [updates] runs the loop
 * until the collector is cancelled; each collection starts with a fresh store and a fresh failure count.
 */
class HostLive(
    route: HostRoute,
    private val transport: HostTransport,
    private val config: LiveConfig,
    private val timeSource: TimeSource.WithComparableMarks,
    private val log: AppLog,
) {
    private val hostName = route.toString()
    private val hostKey = hostKeyField(route)
    private val refresh = Channel<Unit>(Channel.CONFLATED)

    /** Asks the running loop to re-seed now, or to stop waiting for the next reconnect. */
    fun requestReseed() {
        refresh.trySend(Unit)
    }

    fun updates(): Flow<LiveUpdate> = flow {
        var failures = 0
        while (true) {
            val connection = Connection(SessionStore(hostName, timeSource, config.idleThreshold), this)
            log.info("live.connecting", listOf(hostKey, LogField.number("attempt", failures + 1L)))
            val error = try {
                connect(connection)
            } catch (e: ProtocolClientException) {
                e
            }
            val held = connection.establishedAt
            if (held != null && held.elapsedNow() >= config.reconnectMax) failures = 0
            failures++
            val delay = backoffDelay(config, failures)
            logEnded(connection.store, held != null, error, failures, delay)
            emit(LiveUpdate.Reconnecting(failures, error.message.orEmpty(), error.protocolCode()))
            withTimeoutOrNull(delay) { refresh.receive() }
        }
    }

    /** State of one connection attempt; only the loop coroutine touches it. */
    private inner class Connection(val store: SessionStore, val out: FlowCollector<LiveUpdate>) {
        val inbox = Channel<StreamMessage>(Channel.UNLIMITED)
        var establishedAt: ComparableTimeMark? = null
        var shown: List<OwnerSession>? = null
        var reseedDue: ComparableTimeMark = timeSource.markNow()
        var probeDue: ComparableTimeMark = timeSource.markNow()

        /** Replaces the store with a fresh seed, then applies the events that arrived while it was fetched. */
        suspend fun seed(initial: Boolean) {
            val seed = transport.fetchSeed()
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
                else -> log.warn("live.reseed_corrected", counts)
            }
            val now = timeSource.markNow()
            reseedDue = now + config.reseedInterval
            probeDue = now + config.livenessInterval
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
            is StreamMessage.Event -> store.applyEvent(message.event) == EventOutcome.Applied
        }

        suspend fun probe() {
            withTimeoutOrNull(config.livenessTimeout) { transport.probe() }
                ?: throw ProtocolClientException.RequestTimeout(config.livenessTimeout.inWholeMilliseconds)
            probeDue = timeSource.markNow() + config.livenessInterval
        }

        /** Emits the sessions when they, their owner state or their waiting time differ from the last list emitted. */
        suspend fun publish() {
            val current = store.ownerSessions().sortedBy { it.info.id }
            val previous = shown
            if (current == previous) return
            shown = current
            if (previous != null) logOwnerStateChanges(previous, current)
            out.emit(LiveUpdate.Sessions(current))
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
            onTimeout(minOf(until(connection.reseedDue), until(connection.probeDue), connection.untilIdleDeadline())) { Wake.Timer }
        }
        when {
            wake is Wake.Message -> if (connection.apply(wake.message)) connection.publish()
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
        connection.seed(initial = false)
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
