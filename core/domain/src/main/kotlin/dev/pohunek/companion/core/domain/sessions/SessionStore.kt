// Ported to Kotlin from pohunek-companion `crates/core/src/model.rs` (apply_seed, apply_event, differs_materially, is_older).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.DaemonEvent
import dev.pohunek.companion.protocol.NotificationRecord
import dev.pohunek.companion.protocol.SessionInfo
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/** A session on one host; ids are only unique per daemon. */
data class SessionKey(val host: String, val sessionId: String)

/** Why an event left the store unchanged. */
enum class IgnoreReason {
    /** The event names a session the store does not hold. */
    UnknownSession,

    /** A session snapshot older than the stored one. */
    StaleSnapshot,

    /** The event needs a runtime identity and carries none. */
    MissingRuntime,

    /** The event's worker instance or runtime generation differs from the stored session's. */
    RuntimeMismatch,

    /** An `agent_state` event carries no revision to order it by. */
    MissingRevision,

    /** The revision is not newer than the last applied one of the same sequence. */
    StaleRevision,

    /** An event the store does not model. */
    NotModelled,

    /** The payload does not decode as the event it names. */
    MalformedPayload,
}

/** What [SessionStore.applyEvent] did with one event. */
sealed interface EventOutcome {
    data object Applied : EventOutcome

    data class Ignored(val reason: IgnoreReason) : EventOutcome
}

/** What a seed changed compared with the state it replaced. */
data class SeedChange(
    /** Sessions in the seed that the store did not hold. */
    val added: Int = 0,
    /** Sessions the store held that the seed lacks. */
    val removed: Int = 0,
    /** Sessions in both whose snapshot differs, ignoring `updated_at`, `native_last_activity_at` and `state_source`. */
    val changed: Int = 0,
    /** Attention notifications added, removed, or differing. */
    val attention: Int = 0,
) {
    val isEmpty: Boolean get() = this == SeedChange()
}

/** Counts of seeds and events since the store was created or the report was last taken, for the caller to log. */
data class ReconcileReport(
    val seeds: Int = 0,
    /** Applied events per event name. */
    val applied: Map<String, Int> = emptyMap(),
    /** Ignored events per event name and reason. */
    val ignored: Map<Pair<String, IgnoreReason>, Int> = emptyMap(),
    /** Snapshot events applied although the stored or carried `updated_at` could not be parsed, so their order was not checked. */
    val unorderedSnapshots: Int = 0,
)

/** A session with the state the owner sees; [waitingSince] is when its longest-open attention notification was created. */
data class OwnerSession(val info: SessionInfo, val ownerState: OwnerState, val waitingSince: OffsetDateTime?)

/**
 * Sessions and open attention notifications of one daemon, seeded by `session.list` and `notification.list` and kept current by the
 * event stream. Holds only what the daemon sent and does no I/O; [timeSource] is the only clock and decides when an idle session
 * turns [OwnerState.Idle]. Not thread-safe: the owner confines it to one coroutine or serialises access.
 */
@Suppress("TooManyFunctions") // One aggregate of seed, events and derived views; the per-event rules live in TrackedSession and AttentionSet.
class SessionStore(val host: String, private val timeSource: TimeSource.WithComparableMarks, private val idleThreshold: Duration) {
    private val sessions = LinkedHashMap<SessionKey, TrackedSession>()
    private val attention = AttentionSet()
    private val projectLabels = HashMap<String, String>()
    private var seeds = 0
    private val applied = HashMap<String, Int>()
    private val ignored = HashMap<Pair<String, IgnoreReason>, Int>()
    private var unorderedSnapshots = 0

    /** Current sessions in seed-then-arrival order. */
    fun sessions(): Map<SessionKey, SessionInfo> = sessions.mapValuesTo(LinkedHashMap()) { it.value.info }

    fun session(sessionId: String): SessionInfo? = sessions[key(sessionId)]?.info

    /** The project label last seen in a seed for [projectId]; events omit it. */
    fun projectLabel(projectId: String): String? = projectLabels[projectId]

    /** Open attention notifications (`agent_blocked`, `approval_required` that are unread or read). */
    fun attention(): List<NotificationRecord> = attention.all()

    /** Every session with its owner state now, in seed-then-arrival order. */
    fun ownerSessions(): List<OwnerSession> {
        val waiting = attention.waitingBySession()
        return sessions.values.map { ownerSession(it, waiting) }
    }

    /** Owner state of one session now; null for an unknown session. */
    fun ownerState(sessionId: String): OwnerState? = sessions[key(sessionId)]?.let { ownerSession(it, attention.waitingBySession()).ownerState }

    /** When the longest-open attention notification of [sessionId] was created; null without one or when no timestamp parses. */
    fun waitingSince(sessionId: String): OffsetDateTime? = attention.waitingBySession()[sessionId]

    /**
     * The earliest future mark at which a session still inside its idle threshold turns [OwnerState.Idle]; null when none is waiting
     * on the threshold, so no timer is needed.
     */
    fun nextIdleDeadline(): ComparableTimeMark? =
        sessions.values.mapNotNull { it.idleSince }.map { it + idleThreshold }.filter { it.hasNotPassedNow() }.minOrNull()

    fun report(): ReconcileReport = ReconcileReport(seeds, applied.toMap(), ignored.toMap(), unorderedSnapshots)

    /** Returns the counts accumulated so far and starts a new tally. */
    fun takeReport(): ReconcileReport = report().also {
        seeds = 0
        applied.clear()
        ignored.clear()
        unorderedSnapshots = 0
    }

    /**
     * Replaces all state with the `session.list` and attention `notification.list` results and returns what differs from the state that
     * was replaced. A session keeps its `agent_state` baseline while its runtime is unchanged, because the seed carries no activity
     * revision and a buffered older event must not replace newer activity, and keeps its idle clock while it stays idle.
     */
    fun applySeed(seed: List<SessionInfo>, notifications: List<NotificationRecord>): SeedChange {
        for (info in seed) {
            val projectId = info.projectId
            val label = info.projectLabel
            if (projectId != null && label != null) projectLabels[projectId] = label
        }
        val now = timeSource.markNow()
        val previous = LinkedHashMap(sessions)
        sessions.clear()
        for (info in seed) {
            val old = previous[key(info.id)]
            val tracked = TrackedSession(info, baseline = null, idleSince = null)
            tracked.baseline = old?.baseline?.takeIf { tracked.runsOn(it.workerInstanceId, it.generation) }
            tracked.idleSince = if (isIdleWithoutWork(info)) old?.idleSince ?: now else null
            sessions[key(info.id)] = tracked
        }
        val attentionChanged = attention.replaceWith(notifications)
        seeds++

        var added = 0
        var changed = 0
        for ((key, tracked) in sessions) {
            val old = previous[key]
            when {
                old == null -> added++
                differsMaterially(old.info, tracked.info) -> changed++
            }
        }
        return SeedChange(added = added, removed = previous.keys.count { it !in sessions }, changed = changed, attention = attentionChanged)
    }

    /** Applies one subscription event and records the outcome in the report. */
    fun applyEvent(event: DaemonEvent): EventOutcome {
        val outcome = dispatch(event, timeSource.markNow())
        when (outcome) {
            EventOutcome.Applied -> applied.merge(event.name, 1, Int::plus)
            is EventOutcome.Ignored -> ignored.merge(event.name to outcome.reason, 1, Int::plus)
        }
        return outcome
    }

    private fun dispatch(event: DaemonEvent, now: ComparableTimeMark): EventOutcome = when (event) {
        is DaemonEvent.SessionCreated -> replaceSnapshot(event.session, insertIfAbsent = true, now)
        is DaemonEvent.SessionRemoved ->
            if (sessions.remove(key(event.session.id)) != null) EventOutcome.Applied else EventOutcome.Ignored(IgnoreReason.UnknownSession)
        is DaemonEvent.Snapshot -> replaceSnapshot(event.session, insertIfAbsent = false, now)
        is DaemonEvent.AgentState -> observe(event.update.sessionId) { it.applyAgentState(event.update, now) }
        is DaemonEvent.SubagentState -> observe(event.update.sessionId) { it.applySubagentState(event.update, now) }
        is DaemonEvent.BackgroundState -> observe(event.update.sessionId) { it.applyBackgroundState(event.update, now) }
        is DaemonEvent.NotificationCreated -> applying { attention.track(event.record) }
        is DaemonEvent.NotificationUpdated -> applying { attention.track(event.record) }
        is DaemonEvent.NotificationDeleted -> applying { attention.remove(event.notificationId) }
        is DaemonEvent.Unknown -> EventOutcome.Ignored(IgnoreReason.NotModelled)
        is DaemonEvent.Malformed -> EventOutcome.Ignored(IgnoreReason.MalformedPayload)
    }

    private fun key(sessionId: String) = SessionKey(host, sessionId)

    private inline fun applying(change: () -> Unit): EventOutcome {
        change()
        return EventOutcome.Applied
    }

    private inline fun observe(sessionId: String, apply: (TrackedSession) -> EventOutcome): EventOutcome =
        sessions[key(sessionId)]?.let(apply) ?: EventOutcome.Ignored(IgnoreReason.UnknownSession)

    /**
     * Replaces a known session's snapshot unless it is older than the stored one. An unknown session is added when [insertIfAbsent]
     * (`session_created`) and otherwise ignored.
     */
    private fun replaceSnapshot(info: SessionInfo, insertIfAbsent: Boolean, now: ComparableTimeMark): EventOutcome {
        val stored = sessions[key(info.id)]
        return when {
            stored == null && !insertIfAbsent -> EventOutcome.Ignored(IgnoreReason.UnknownSession)
            stored == null -> applying { sessions[key(info.id)] = TrackedSession(info, baseline = null, idleSince = null).also { it.refreshIdle(now) } }
            else -> when (isOlder(info.updatedAt, stored.info.updatedAt)) {
                true -> EventOutcome.Ignored(IgnoreReason.StaleSnapshot)
                false -> stored.replaceSnapshot(info, now)
                null -> {
                    unorderedSnapshots++
                    stored.replaceSnapshot(info, now)
                }
            }
        }
    }

    private fun ownerSession(tracked: TrackedSession, waiting: Map<String, OffsetDateTime?>): OwnerSession {
        val info = tracked.info
        val state = deriveOwnerState(info, openAttention = info.id in waiting, idleFor = tracked.idleSince?.elapsedNow(), idleThreshold = idleThreshold)
        return OwnerSession(info, state, waiting[info.id])
    }
}

/**
 * Whether two snapshots of one session differ beyond the activity timestamps and the state source. The daemon advances the timestamps
 * while a session works and switches the source between detectors without an event for each step.
 */
private fun differsMaterially(old: SessionInfo, new: SessionInfo): Boolean {
    val aligned = new.copy(
        updatedAt = old.updatedAt,
        nativeLastActivityAt = old.nativeLastActivityAt,
        stateSource = old.stateSource,
        // Events omit the label that a seed carries.
        projectLabel = if (old.projectLabel == null) null else new.projectLabel,
    )
    return old != aligned
}

/** `null` when either timestamp is not RFC 3339, so the order is unknown. */
private fun isOlder(candidate: String, stored: String): Boolean? {
    val candidateAt = parseTimestamp(candidate) ?: return null
    val storedAt = parseTimestamp(stored) ?: return null
    return candidateAt.isBefore(storedAt)
}

internal fun parseTimestamp(timestamp: String): OffsetDateTime? = try {
    OffsetDateTime.parse(timestamp)
} catch (_: DateTimeParseException) {
    null
}
