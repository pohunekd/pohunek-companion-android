// Ported to Kotlin from pohunek-companion `crates/core/src/model.rs` (apply_seed, apply_event, differs_materially, is_older).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.DaemonEvent
import dev.pohunek.companion.protocol.SessionInfo
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** A session on one host; ids are only unique per daemon. */
data class SessionKey(val host: String, val sessionId: String)

/** Why an event left the store unchanged. */
enum class IgnoreReason {
    /** The event names a session the store does not hold. */
    UnknownSession,

    /** A session snapshot older than the stored one. */
    StaleSnapshot,

    /** An event the store does not model. */
    NotModelled,
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

/**
 * Sessions of one daemon, seeded by `session.list` and kept current by `session_*` events. Holds only what the daemon sent and does no I/O.
 * Not thread-safe: the owner confines it to one coroutine or serialises access.
 */
class SessionStore(val host: String) {
    private val sessions = LinkedHashMap<SessionKey, SessionInfo>()
    private val projectLabels = HashMap<String, String>()
    private var seeds = 0
    private val applied = HashMap<String, Int>()
    private val ignored = HashMap<Pair<String, IgnoreReason>, Int>()
    private var unorderedSnapshots = 0

    /** Current sessions in seed-then-arrival order. */
    fun sessions(): Map<SessionKey, SessionInfo> = LinkedHashMap(sessions)

    fun session(sessionId: String): SessionInfo? = sessions[SessionKey(host, sessionId)]

    /** The project label last seen in a seed for [projectId]; events omit it. */
    fun projectLabel(projectId: String): String? = projectLabels[projectId]

    fun report(): ReconcileReport = ReconcileReport(seeds, applied.toMap(), ignored.toMap(), unorderedSnapshots)

    /** Returns the counts accumulated so far and starts a new tally. */
    fun takeReport(): ReconcileReport = report().also {
        seeds = 0
        applied.clear()
        ignored.clear()
        unorderedSnapshots = 0
    }

    /** Replaces all state with the `session.list` result and returns what differs from the state that was replaced. */
    fun applySeed(seed: List<SessionInfo>): SeedChange {
        for (info in seed) {
            val projectId = info.projectId
            val label = info.projectLabel
            if (projectId != null && label != null) projectLabels[projectId] = label
        }
        val previous = LinkedHashMap(sessions)
        sessions.clear()
        for (info in seed) sessions[key(info.id)] = info
        seeds++

        var added = 0
        var changed = 0
        for ((key, info) in sessions) {
            val old = previous[key]
            when {
                old == null -> added++
                differsMaterially(old, info) -> changed++
            }
        }
        return SeedChange(added = added, removed = previous.keys.count { it !in sessions }, changed = changed)
    }

    /** Applies one subscription event and records the outcome in the report. */
    fun applyEvent(event: DaemonEvent): EventOutcome {
        val outcome = dispatch(event)
        when (outcome) {
            EventOutcome.Applied -> applied.merge(event.name, 1, Int::plus)
            is EventOutcome.Ignored -> ignored.merge(event.name to outcome.reason, 1, Int::plus)
        }
        return outcome
    }

    private fun dispatch(event: DaemonEvent): EventOutcome = when (event) {
        is DaemonEvent.SessionCreated -> replaceSnapshot(event.session, insertIfAbsent = true)
        is DaemonEvent.SessionRemoved ->
            if (sessions.remove(key(event.session.id)) != null) EventOutcome.Applied else EventOutcome.Ignored(IgnoreReason.UnknownSession)
        is DaemonEvent.Snapshot -> replaceSnapshot(event.session, insertIfAbsent = false)
        is DaemonEvent.Unknown -> EventOutcome.Ignored(IgnoreReason.NotModelled)
    }

    private fun key(sessionId: String) = SessionKey(host, sessionId)

    /**
     * Replaces a known session's snapshot unless it is older than the stored one. An unknown session is added when [insertIfAbsent]
     * (`session_created`) and otherwise ignored.
     */
    private fun replaceSnapshot(info: SessionInfo, insertIfAbsent: Boolean): EventOutcome {
        val stored = sessions[key(info.id)]
        val order = if (stored == null) false else isOlder(info.updatedAt, stored.updatedAt)
        return when {
            stored == null && !insertIfAbsent -> EventOutcome.Ignored(IgnoreReason.UnknownSession)
            order == true -> EventOutcome.Ignored(IgnoreReason.StaleSnapshot)
            else -> {
                if (order == null) unorderedSnapshots++
                sessions[key(info.id)] = info
                EventOutcome.Applied
            }
        }
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
    val candidateAt = parse(candidate) ?: return null
    val storedAt = parse(stored) ?: return null
    return candidateAt.isBefore(storedAt)
}

private fun parse(timestamp: String): OffsetDateTime? = try {
    OffsetDateTime.parse(timestamp)
} catch (_: DateTimeParseException) {
    null
}
