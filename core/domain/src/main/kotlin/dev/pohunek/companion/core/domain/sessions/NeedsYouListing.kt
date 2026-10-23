// Ported to Kotlin from pohunek-companion `crates/core/src/needs_you.rs` (with_needs_you) and `tray_summary.rs` (needs_owner_order, wait_order),
// and from `session.rs` / `badge.rs` (runtime_detail, sanitize). Repository: https://github.com/pohunekd/pohunek-companion, MIT,
// Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.RuntimeState
import dev.pohunek.companion.protocol.SessionInfo
import java.time.OffsetDateTime

/** The sessions of one host split for display: those that need the owner in wait order, and all the others, in the order given. */
data class NeedsYouListing(val needsYou: List<OwnerSession>, val others: List<OwnerSession>)

/**
 * Lifts the sessions that need the owner out of [sessions]. Display only: nothing about the sessions changes. While the host is not
 * [hostReachable] its sessions may be out of date, so none of them counts as needing the owner.
 */
fun partitionNeedsYou(sessions: List<OwnerSession>, hostReachable: Boolean): NeedsYouListing {
    if (!hostReachable) return NeedsYouListing(emptyList(), sessions)
    val (needing, rest) = sessions.partition { it.ownerState == OwnerState.NeedsOwner }
    return NeedsYouListing(needing.sortedWith(needsOwnerOrder), rest)
}

/**
 * Longest waiting first, then display name and id; a session without a known waiting time comes after those with one. The list is of
 * one host, so the host never separates two sessions.
 */
internal val needsOwnerOrder: Comparator<OwnerSession> =
    compareBy<OwnerSession, OffsetDateTime?>(nullsLast(naturalOrder())) { it.waitingSince }
        .thenBy { sessionDisplayName(it.info) }
        .thenBy { it.info.id }

/** The session's sanitized name, or its id when it has none. */
fun sessionDisplayName(info: SessionInfo): String = info.name?.let(::sanitizeText)?.ifEmpty { null } ?: info.id

/**
 * `<state>: <loss_reason>` for a `conflict` or `incompatible` runtime, just the state without a reason, and null for any other runtime.
 * The reason is daemon-supplied text, so control and bidi-format characters are removed and whitespace is collapsed.
 */
fun runtimeDetail(info: SessionInfo): String? {
    val runtime = info.runtime ?: return null
    val state = when (runtime.state) {
        RuntimeState.Conflict -> "conflict"
        RuntimeState.Incompatible -> "incompatible"
        else -> return null
    }
    val reason = runtime.lossReason?.let(::sanitizeText).orEmpty()
    return if (reason.isEmpty()) state else "$state: $reason"
}

/** Collapses whitespace runs to one space and drops control and format characters, so metadata cannot reshape the row it is shown in. */
fun sanitizeText(value: String): String {
    val out = StringBuilder(value.length)
    value.forEach { c ->
        when {
            c.isWhitespace() || c == NEXT_LINE -> if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
            !c.isISOControl() && !isFormatChar(c) -> out.append(c)
        }
    }
    return out.trimEnd().toString()
}

/** Rust's `char::is_whitespace`, which the desktop sanitizer uses, counts it; Kotlin's does not. */
private const val NEXT_LINE = '\u0085'

private fun isFormatChar(c: Char): Boolean = c == '؜' || c in '​'..'‏' || c in '‪'..'‮' || c in '⁠'..'⁩' || c == '﻿'
