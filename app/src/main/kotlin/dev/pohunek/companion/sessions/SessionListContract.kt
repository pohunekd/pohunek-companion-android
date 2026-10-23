package dev.pohunek.companion.sessions

import dev.pohunek.companion.core.data.live.HostConnection
import dev.pohunek.companion.core.domain.sessions.HostLink
import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.core.domain.sessions.SessionDetails
import dev.pohunek.companion.protocol.HostRoute

/** One session as the list shows it; every text is untrusted metadata and is rendered as plain text only. */
data class SessionRow(
    val route: HostRoute,
    val sessionId: String,
    val title: String,
    val agent: String,
    val ownerState: OwnerState,
    /** The project label, or null for a session outside a project. */
    val project: String?,
    /** The Work link badge such as `GH PR #12`, cut to the configured length, and the session's Work role. */
    val badge: String?,
    val badgeRole: String?,
    /** True when the session has a Work link that may be opened and the host is connected. */
    val hasLink: Boolean,
    /** `<state>: <loss_reason>` of a conflict or incompatible runtime. */
    val runtimeDetail: String?,
    /** The host's label, else its route. */
    val hostLabel: String,
    /** Where the row's host stands; the facts of a host that is not connected are the last known ones. */
    val link: HostLink,
    /** Null when a terminal can be attached to the session. */
    val attachRefusal: AttachRefusal?,
) {
    val reachable: Boolean get() = link == HostLink.Connected

    val ref: RowRef get() = RowRef(route, sessionId)

    /** Row actions need the daemon, so a row of a host that is not connected offers none. */
    val hasActions: Boolean get() = reachable && (attachRefusal == null || hasLink)

    /** Unique across hosts and a plain string, so that the list can save its state by it. */
    val listKey: String get() = "$route|$sessionId"
}

/** A session on its host; two hosts can hold the same session id. */
data class RowRef(val route: HostRoute, val sessionId: String)

/** The details screen of one session; [details] is null once the session has left its host's list, [name] is then its last known name. */
data class DetailsView(val ref: RowRef, val name: String, val details: SessionDetails?)

/** A host whose connection is down, for the status lines above the list. */
data class HostStatus(val hostLabel: String, val connection: HostConnection.Reconnecting)

data class SessionListState(
    /** False until the hosts file has been read. */
    val hostsRead: Boolean = false,
    /** Set when the hosts file is unreadable; nothing is saved then. */
    val storageError: String? = null,
    /** True when at least one host is configured. */
    val hasHosts: Boolean = false,
    /** Sessions that need the owner on a connected host, longest waiting first. */
    val needsYou: List<SessionRow> = emptyList(),
    /** The other sessions, or null until a list is shown: no host has sent one, or the first one is still expected. */
    val rows: List<SessionRow>? = null,
    /** One entry per host that is not connected, in host order. */
    val statuses: List<HostStatus> = emptyList(),
    /** True from a requested re-seed until every host finished it or failed. */
    val refreshing: Boolean = false,
    /** Why the last tapped session was not opened; shown until dismissed, refreshed, or another session opens. */
    val refusal: AttachRefusal? = null,
    /** The host a [AttachRefusal.HostUnreachable] refusal was about. */
    val refusalHost: HostRoute? = null,
    /** The session whose action sheet is open; the sheet closes when the session leaves the list. */
    val actionSheet: RowRef? = null,
    /** The open details screen, which follows the session live; null while the list is shown. */
    val details: DetailsView? = null,
)

sealed interface SessionListEvent {
    data object Refresh : SessionListEvent

    data class OpenSession(val ref: RowRef) : SessionListEvent

    data class OpenActions(val ref: RowRef) : SessionListEvent

    data object DismissActions : SessionListEvent

    data class OpenDetails(val ref: RowRef) : SessionListEvent

    data object CloseDetails : SessionListEvent

    data class OpenLink(val ref: RowRef) : SessionListEvent

    /** No app handled the link intent. */
    data object LinkNotHandled : SessionListEvent

    data object DismissRefusal : SessionListEvent
}

sealed interface SessionListEffect {
    data class OpenTerminal(val route: HostRoute, val sessionId: String) : SessionListEffect

    /** Hand [url], already checked, to the system; it opens in the browser. */
    data class OpenLink(val url: String) : SessionListEffect

    /** The session has no link that may be opened any more. */
    data object LinkUnavailable : SessionListEffect

    /** No host is configured: the owner adds the first one. */
    data object OpenHosts : SessionListEffect
}
