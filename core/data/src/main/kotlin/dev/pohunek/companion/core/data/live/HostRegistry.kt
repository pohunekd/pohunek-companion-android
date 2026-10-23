package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.core.domain.sessions.OwnerSession
import dev.pohunek.companion.protocol.HostRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentHashMap

/** Where one host's live connection stands. */
sealed interface HostConnection {
    /** The first connection of this foreground period is not established yet. */
    data object Connecting : HostConnection

    data object Connected : HostConnection

    /** The connection is down; [code] is the daemon's protocol error code when it refused, such as `version_mismatch`. */
    data class Reconnecting(val attempt: Int, val reason: String, val code: String?) : HostConnection
}

/** What the registry knows about one host. */
data class HostFeed(
    val connection: HostConnection = HostConnection.Connecting,
    /** The last list the host sent; null until the first arrives, kept, though out of date, while the connection is down. */
    val sessions: List<OwnerSession>? = null,
    /** True from a requested re-seed until it finishes or the connection fails. */
    val refreshing: Boolean = false,
)

/**
 * One live loop per host, each independent of the others: a host that fails or is slow never touches another host's loop or rows.
 * [state] holds the latest [HostFeed] of every host while [run] is active and is kept, out of date, after it stops.
 */
class HostRegistry(private val factory: HostLiveFactory) {
    private val feeds = MutableStateFlow<Map<HostRoute, HostFeed>>(emptyMap())
    private val lives = ConcurrentHashMap<HostRoute, HostLive>()

    val state: StateFlow<Map<HostRoute, HostFeed>> get() = feeds

    /**
     * Runs the loop of every route [hosts] names until cancelled; a route that appears starts its loop and one that disappears stops
     * it and drops its feed. A start begins with every kept feed connecting again.
     */
    suspend fun run(hosts: Flow<List<HostRoute>>): Nothing = supervisorScope {
        val jobs = mutableMapOf<HostRoute, Job>()
        try {
            hosts.collect { wanted ->
                val wantedSet = wanted.toSet()
                for (gone in jobs.keys - wantedSet) {
                    jobs.remove(gone)?.cancel()
                    lives.remove(gone)
                }
                feeds.update { map -> map.filterKeys { it in wantedSet } }
                for (route in wanted) {
                    if (route !in jobs) jobs[route] = launch { follow(route) }
                }
            }
            error("the host list flow never completes")
        } finally {
            lives.clear()
        }
    }

    /** Asks every running loop to re-seed now, or to stop waiting for its next reconnect. */
    fun requestReseed() {
        val routes = lives.keys.toList()
        feeds.update { map -> map.mapValues { (route, feed) -> if (route in routes) feed.copy(refreshing = true) else feed } }
        routes.forEach { lives[it]?.requestReseed() }
    }

    private suspend fun follow(route: HostRoute) {
        val live = factory.create(route)
        lives[route] = live
        feeds.update { it + (route to (it[route]?.copy(connection = HostConnection.Connecting, refreshing = false) ?: HostFeed())) }
        live.updates().collect { update ->
            feeds.update { map -> map[route]?.let { map + (route to it.apply(update)) } ?: map }
        }
    }
}

private fun HostFeed.apply(update: LiveUpdate): HostFeed = when (update) {
    LiveUpdate.Connected -> copy(connection = HostConnection.Connected, refreshing = false)
    is LiveUpdate.Sessions -> copy(sessions = update.sessions)
    LiveUpdate.Reseeded -> copy(refreshing = false)
    is LiveUpdate.Reconnecting -> copy(connection = HostConnection.Reconnecting(update.attempt, update.reason, update.code), refreshing = false)
}
