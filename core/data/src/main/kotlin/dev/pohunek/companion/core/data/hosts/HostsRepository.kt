package dev.pohunek.companion.core.data.hosts

import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.domain.hosts.ConfiguredHost
import dev.pohunek.companion.core.domain.hosts.HostProblem
import dev.pohunek.companion.core.domain.hosts.hostProblem
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.protocol.HostRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The hosts as the app knows them. */
sealed interface HostsState {
    /** The hosts file has not been read yet. */
    data object Loading : HostsState

    data class Loaded(val hosts: List<ConfiguredHost>) : HostsState

    /** The hosts file cannot be read; nothing is changed or saved while it stays so. */
    data class Unreadable(val reason: String) : HostsState
}

/** Result of adding, replacing or removing a host. */
sealed interface HostEdit {
    data object Saved : HostEdit

    /** The edit would break a host rule; nothing was written. */
    data class Refused(val problem: HostProblem) : HostEdit

    /** The hosts file is unreadable or the host to change is not listed; nothing was written. */
    data class Unavailable(val reason: String) : HostEdit
}

/** The one owner of the host list: edits are validated, written, and then published in [state]. */
class HostsRepository(private val store: HostStore, private val log: AppLog) {
    private val mutex = Mutex()
    private val current = MutableStateFlow<HostsState>(HostsState.Loading)
    val state: StateFlow<HostsState> get() = current

    /** Reads the hosts file once; later calls return at once. */
    suspend fun load() {
        mutex.withLock {
            if (current.value != HostsState.Loading) return
            current.value = when (val load = store.load()) {
                is HostsLoad.Loaded -> HostsState.Loaded(load.hosts)
                is HostsLoad.Unreadable -> HostsState.Unreadable(load.reason)
            }
        }
    }

    suspend fun add(host: ConfiguredHost): HostEdit = edit("host.added", host.route) { it + host }

    /** Replaces the host at [route] with [host], in place. */
    suspend fun replace(route: HostRoute, host: ConfiguredHost): HostEdit = edit("host.edited", host.route) { hosts ->
        if (hosts.none { it.route == route }) null else hosts.map { if (it.route == route) host else it }
    }

    suspend fun remove(route: HostRoute): HostEdit = edit("host.removed", route) { hosts ->
        if (hosts.none { it.route == route }) null else hosts.filterNot { it.route == route }
    }

    /** Throws [HostStoreException] when the file cannot be written; the published state is then unchanged. */
    private suspend fun edit(event: String, route: HostRoute, change: (List<ConfiguredHost>) -> List<ConfiguredHost>?): HostEdit = mutex.withLock {
        val loaded = current.value as? HostsState.Loaded ?: return HostEdit.Unavailable("the hosts are not available")
        val next = change(loaded.hosts) ?: return HostEdit.Unavailable("the host is not listed")
        hostProblem(next)?.let { return HostEdit.Refused(it) }
        store.save(next)
        current.value = HostsState.Loaded(next)
        log.info(event, listOf(hostKeyField(route)))
        HostEdit.Saved
    }
}
