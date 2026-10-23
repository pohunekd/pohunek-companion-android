package dev.pohunek.companion.hosts

import dev.pohunek.companion.core.data.live.HostConnection
import dev.pohunek.companion.core.domain.hosts.ConfiguredHost
import dev.pohunek.companion.protocol.HostRoute

/** One configured host with its connection; [connection] is null while no loop has reported for it. */
data class HostItem(val host: ConfiguredHost, val label: String, val connection: HostConnection?)

/** A host found by discovery: listed, never edited or removed. [connection] is null while no loop has reported for it. */
data class DiscoveredItem(val label: String, val route: String, val present: Boolean, val connection: HostConnection?)

/** A peer whose daemon speaks a protocol version this app cannot use. */
data class IncompatibleItem(val key: String, val label: String, val protocolVersion: Int)

/** Asking the configured host [hostLabel] for its peers failed with [detail]. */
data class DiscoveryErrorItem(val route: String, val hostLabel: String, val detail: String)

/** The host form; [editing] is the route of the host being changed, null when adding. [error] is why the last save was refused. */
data class HostForm(
    val route: String = "",
    val label: String = "",
    val editing: HostRoute? = null,
    val error: String? = null,
    val saving: Boolean = false,
)

data class HostsScreenState(
    /** False until the hosts file has been read. */
    val hostsRead: Boolean = false,
    /** Set when the hosts file is unreadable; nothing is saved then. */
    val storageError: String? = null,
    val hosts: List<HostItem> = emptyList(),
    val discovered: List<DiscoveredItem> = emptyList(),
    val incompatible: List<IncompatibleItem> = emptyList(),
    val discoveryErrors: List<DiscoveryErrorItem> = emptyList(),
    /** Non-null while the form is shown; an empty host list always shows it. */
    val form: HostForm? = null,
)

sealed interface HostsEvent {
    data object AddHost : HostsEvent

    data class EditHost(val route: HostRoute) : HostsEvent

    data object CancelEdit : HostsEvent

    data object SaveHost : HostsEvent

    data object RemoveHost : HostsEvent

    data class RouteChanged(val route: String) : HostsEvent

    data class LabelChanged(val label: String) : HostsEvent
}

sealed interface HostsEffect {
    /** The first host was saved: the owner goes back to the list. */
    data object Close : HostsEffect
}
