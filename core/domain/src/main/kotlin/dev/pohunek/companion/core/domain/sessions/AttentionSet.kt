// Ported to Kotlin from pohunek-companion `crates/core/src/model.rs` (track_attention, waiting_since).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.NotificationRecord
import java.time.OffsetDateTime

/** The open attention notifications of one daemon: `agent_blocked` and `approval_required` records that are unread or read. */
internal class AttentionSet {
    private val records = LinkedHashMap<String, NotificationRecord>()

    fun all(): List<NotificationRecord> = records.values.toList()

    /** Keeps [record] while it is an open attention notification and drops it otherwise. */
    fun track(record: NotificationRecord) {
        if (record.kind.isAttention && record.status.isOpen) records[record.id] = record else records.remove(record.id)
    }

    fun remove(notificationId: String) {
        records.remove(notificationId)
    }

    /** Replaces the set with [seed] and returns how many records were added, removed or differ from the set it replaced. */
    fun replaceWith(seed: List<NotificationRecord>): Int {
        val previous = LinkedHashMap(records)
        records.clear()
        seed.forEach(::track)
        return records.count { (id, record) -> previous[id] != record } + previous.keys.count { it !in records }
    }

    /** Per session id with an open record, the earliest parseable `created_at` among its records; null when none parses. */
    fun waitingBySession(): Map<String, OffsetDateTime?> {
        val waiting = HashMap<String, OffsetDateTime?>()
        for (record in records.values) {
            val sessionId = record.sessionId ?: continue
            waiting[sessionId] = listOfNotNull(waiting[sessionId], parseTimestamp(record.createdAt)).minOrNull()
        }
        return waiting
    }
}
