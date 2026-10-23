package dev.pohunek.companion.sessions

import dev.pohunek.companion.core.data.hosts.ConfiguredHost
import dev.pohunek.companion.core.domain.sessions.OwnerSession
import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.protocol.HostRoute

/** One session as the list shows it; every text is untrusted metadata and is rendered as plain text only. */
data class SessionRow(
    val id: String,
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
    /** Null when a terminal can be attached to the session. */
    val attachRefusal: AttachRefusal?,
)

/** Where the live connection to the host stands. */
sealed interface ListConnection {
    /** Connecting for the first time or after the app came back to the foreground. */
    data object Connecting : ListConnection

    data object Connected : ListConnection

    /** The connection is down; [code] is the daemon's protocol error code when it refused, such as `version_mismatch`. */
    data class Reconnecting(val attempt: Int, val reason: String, val code: String?) : ListConnection
}

/** The host form; [error] is the reason the last save was refused. */
data class HostForm(val route: String = "", val label: String = "", val error: String? = null, val saving: Boolean = false)

data class SessionListState(
    /** False until the hosts file has been read. */
    val hostsRead: Boolean = false,
    /** Set when the hosts file is unreadable; nothing is saved then. */
    val storageError: String? = null,
    val host: ConfiguredHost? = null,
    /** The last list the host sent; null until the first arrives, kept, though out of date, while the connection is down. */
    val sessions: List<OwnerSession>? = null,
    /** Sessions that need the owner, longest waiting first; empty while the connection is down. */
    val needsYou: List<SessionRow> = emptyList(),
    /** The other sessions, or null until the first list arrives. */
    val rows: List<SessionRow>? = null,
    val connection: ListConnection = ListConnection.Connecting,
    /** True from a requested re-seed until it finishes or the connection fails. */
    val refreshing: Boolean = false,
    /** Non-null while the host form is shown. */
    val form: HostForm? = null,
    /** Why the last tapped session was not opened; shown until dismissed, refreshed, or another session opens. */
    val refusal: AttachRefusal? = null,
    /** Id of the session whose action sheet is open; the sheet closes when the session leaves the list. */
    val actionSheet: String? = null,
)

sealed interface SessionListEvent {
    data object Refresh : SessionListEvent

    data class OpenSession(val id: String) : SessionListEvent

    data class OpenActions(val id: String) : SessionListEvent

    data object DismissActions : SessionListEvent

    data class OpenLink(val id: String) : SessionListEvent

    data object DismissRefusal : SessionListEvent

    data object EditHost : SessionListEvent

    data object CancelEdit : SessionListEvent

    data object ClearHost : SessionListEvent

    data object SaveHost : SessionListEvent

    data class RouteChanged(val route: String) : SessionListEvent

    data class LabelChanged(val label: String) : SessionListEvent
}

sealed interface SessionListEffect {
    data class OpenTerminal(val route: HostRoute, val sessionId: String) : SessionListEffect

    /** Hand [url], already checked, to the system; it opens in the browser. */
    data class OpenLink(val url: String) : SessionListEffect

    /** The session has no link that may be opened any more. */
    data object LinkUnavailable : SessionListEffect
}
