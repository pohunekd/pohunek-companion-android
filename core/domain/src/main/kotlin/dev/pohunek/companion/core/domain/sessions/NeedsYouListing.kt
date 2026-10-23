// Ported to Kotlin from pohunek-companion `crates/core/src/needs_you.rs` (with_needs_you) and `tray_summary.rs` (needs_owner_order, wait_order),
// and from `session.rs` / `badge.rs` (runtime_detail, sanitize), and from `crates/app/src/main.rs` (HostsView::union). Repository:
// https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.core.domain.hosts.ConfiguredHost
import dev.pohunek.companion.core.domain.hosts.hostDisplayLabel
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.RuntimeState
import dev.pohunek.companion.protocol.SessionInfo
import java.time.OffsetDateTime

/** Where a host's connection stands, as far as its rows are concerned. */
enum class HostLink {
    Connected,

    /** The first connection of this foreground period is not established yet. */
    Connecting,

    /** The connection failed and is being retried, or the daemon refused it. */
    Down,
}

/** One host with the last sessions it sent; they may be out of date unless [link] is [HostLink.Connected]. */
data class HostSessions(val host: ConfiguredHost, val link: HostLink, val sessions: List<OwnerSession>)

/** A session of the union list with the host it came from. */
data class HostedSession(val route: HostRoute, val hostLabel: String, val link: HostLink, val session: OwnerSession) {
    /** Two daemons can hold the same session id, so a session is identified by its host as well. */
    val key: SessionKey get() = SessionKey(route.toString(), session.info.id)

    /** Facts of a host that is not connected are the last known ones: the row offers nothing that needs the daemon. */
    val reachable: Boolean get() = link == HostLink.Connected
}

/** The union of every host's sessions split for display: those that need the owner in wait order, and all the others. */
data class NeedsYouListing(val needsYou: List<HostedSession>, val others: List<HostedSession>)

/**
 * The union of [hosts] keyed by `(route, id)`, with the sessions that need the owner lifted out. Display only: nothing about the sessions
 * changes. A host that is not connected keeps its rows in [NeedsYouListing.others], because its facts may be out of date, so none of its
 * sessions counts as needing the owner.
 */
fun partitionNeedsYou(hosts: List<HostSessions>): NeedsYouListing {
    val all = hosts.flatMap { entry ->
        val label = hostDisplayLabel(entry.host)
        entry.sessions.map { HostedSession(entry.host.route, label, entry.link, it) }
    }
    val (needing, rest) = all.partition { it.reachable && it.session.ownerState == OwnerState.NeedsOwner }
    return NeedsYouListing(needing.sortedWith(needsOwnerOrder), rest)
}

/** Longest waiting first, then display name, host and id; a session without a known waiting time comes after those with one. */
internal val needsOwnerOrder: Comparator<HostedSession> =
    compareBy<HostedSession, OffsetDateTime?>(nullsLast(naturalOrder())) { it.session.waitingSince }
        .thenBy { sessionDisplayName(it.session.info) }
        .thenBy { it.key.host }
        .thenBy { it.key.sessionId }

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

internal fun isFormatChar(c: Char): Boolean = c == '؜' || c in '​'..'‏' || c in '‪'..'‮' || c in '⁠'..'⁩' || c == '﻿'
