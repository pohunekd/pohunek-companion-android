// Ported to Kotlin from pohunek-companion `crates/core/src/pending_placement.rs` (Pending::add, Pending::settle).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.newsession

import dev.pohunek.companion.core.domain.sessions.SessionKey
import kotlin.time.Duration

/** A session just created that waits to appear in the layout so that it can be moved to [group]. [recordedAtMillis] is epoch milliseconds. */
data class PendingPlacement(val key: SessionKey, val group: String, val recordedAtMillis: Long)

/** What [PendingPlacements.settle] decided. */
data class Settlement(
    val remaining: PendingPlacements,
    /** Placements whose session is present now, with the group to move it to. */
    val apply: List<PendingPlacement>,
    /** Sessions whose placement outlived the maximum age without the session appearing. */
    val expired: List<SessionKey>,
)

/** Placements recorded by the new-session form for sessions the layout does not hold yet. Immutable; every edit returns the new value. */
data class PendingPlacements(val entries: List<PendingPlacement> = emptyList()) {
    /** Records [group] for [key], replacing an earlier entry of the session. */
    fun add(key: SessionKey, group: String, nowMillis: Long): PendingPlacements =
        PendingPlacements(entries.filter { it.key != key } + PendingPlacement(key, group, nowMillis))

    /**
     * Takes the entries of sessions in [present] to be applied and drops the ones older than [maxAge]. An entry that is present and too
     * old is still applied: the session did appear.
     */
    fun settle(present: Set<SessionKey>, nowMillis: Long, maxAge: Duration): Settlement {
        val apply = entries.filter { it.key in present }
        val rest = entries.filter { it.key !in present }
        val (expired, kept) = rest.partition { nowMillis - it.recordedAtMillis > maxAge.inWholeMilliseconds }
        return Settlement(PendingPlacements(kept), apply, expired.map { it.key })
    }
}
