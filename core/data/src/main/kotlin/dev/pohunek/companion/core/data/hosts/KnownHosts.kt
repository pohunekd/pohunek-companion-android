package dev.pohunek.companion.core.data.hosts

import dev.pohunek.companion.core.data.discovery.HostDiscovery
import dev.pohunek.companion.core.data.live.FollowedHosts
import dev.pohunek.companion.core.data.live.HostConnection
import dev.pohunek.companion.core.data.live.HostRegistry
import dev.pohunek.companion.core.domain.hosts.ConfiguredHost
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Every host the app lists: the configured hosts first, then the peers discovery found through them. A discovered host is not
 * stored; it lives as long as the process.
 */
class KnownHosts(
    private val configured: HostsRepository,
    private val discovery: HostDiscovery,
    private val registry: HostRegistry,
) {
    /** The configured hosts followed by the discovered ones; any state other than loaded is the repository's own. */
    val state: Flow<HostsState> = combine(configured.state, discovery.state) { hosts, found ->
        if (hosts is HostsState.Loaded) HostsState.Loaded(hosts.hosts + found.peers.map { ConfiguredHost(it.host.route, it.host.label) }) else hosts
    }

    suspend fun load() = configured.load()

    /** Follows every host until cancelled: the configured ones and the present discovered ones are connected, the left ones kept. */
    suspend fun run(): Nothing = coroutineScope {
        val manual = configured.state.filterIsInstance<HostsState.Loaded>().map { it.hosts }
        val followed = combine(manual, discovery.state) { hosts, found ->
            FollowedHosts(
                followed = hosts.map { it.route } + found.peers.filter { it.present }.map { it.host.route },
                retained = found.peers.filter { !it.present }.map { it.host.route },
            )
        }.distinctUntilChanged()
        val connected =
            combine(manual, registry.state) { hosts, feeds -> hosts.map { it.route }.filter { feeds[it]?.connection == HostConnection.Connected }.toSet() }
        launch { discovery.run(manual, connected) }
        registry.run(followed)
    }
}
