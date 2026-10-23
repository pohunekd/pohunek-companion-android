package dev.pohunek.companion.sessions

import dev.pohunek.companion.core.data.hosts.ConfiguredHost
import dev.pohunek.companion.protocol.AgentActivity
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.SessionState

/** One session as the list shows it; every text is untrusted metadata and is rendered as plain text only. */
data class SessionRow(
    val id: String,
    val title: String,
    val agent: String,
    val state: SessionState,
    val activity: AgentActivity?,
    /** Null when a terminal can be attached to the session. */
    val attachRefusal: AttachRefusal?,
)

/** Why the list could not be loaded; [incompatible] marks a host that speaks no protocol version this app does. */
data class ListError(val incompatible: Boolean, val message: String)

/** The host form; [error] is the reason the last save was refused. */
data class HostForm(val route: String = "", val label: String = "", val error: String? = null, val saving: Boolean = false)

data class SessionListState(
    /** False until the hosts file has been read. */
    val hostsRead: Boolean = false,
    /** Set when the hosts file is unreadable; nothing is saved then. */
    val storageError: String? = null,
    val host: ConfiguredHost? = null,
    val loading: Boolean = false,
    val rows: List<SessionRow>? = null,
    val error: ListError? = null,
    /** Non-null while the host form is shown. */
    val form: HostForm? = null,
    /** Why the last tapped session was not opened; shown until dismissed, refreshed, or another session opens. */
    val refusal: AttachRefusal? = null,
)

sealed interface SessionListEvent {
    data object Refresh : SessionListEvent

    data class OpenSession(val id: String) : SessionListEvent

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
}
