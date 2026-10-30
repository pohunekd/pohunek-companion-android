package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.core.domain.sessions.OwnerSession
import dev.pohunek.companion.protocol.HostRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ComparableTimeMark

/** Where one host's live connection stands. */
sealed interface HostConnection {
    /** The first connection of this foreground period is not established yet. */
    data object Connecting : HostConnection

    data object Connected : HostConnection

    /** The connection is down; [code] is the daemon's protocol error code when it refused, such as `version_mismatch`. */
    data class Reconnecting(val attempt: Int, val reason: String, val code: String?) : HostConnection

    /** Discovery no longer lists the host: its loop is stopped and its last rows are kept. */
    data object NotReported : HostConnection
}

/** The routes the registry follows and the routes whose loop is stopped but whose last feed stays, marked [HostConnection.NotReported]. */
data class FollowedHosts(val followed: List<HostRoute>, val retained: List<HostRoute> = emptyList())

/** What the registry knows about one host. */
data class HostFeed(
    val connection: HostConnection = HostConnection.Connecting,
    /** The last list the host sent; null until the first arrives, kept, though out of date, while the connection is down. */
    val sessions: List<OwnerSession>? = null,
    /** True from a requested re-seed until it finishes or the connection fails. */
    val refreshing: Boolean = false,
    /** When the host last stopped being [HostConnection.Connected], or started its loop; null while connected. See [link]. */
    val downSince: ComparableTimeMark? = null,
)

/**
 * One live loop per host, each independent of the others: a host that fails or is slow never touches another host's loop or rows.
 * [state] holds the latest [HostFeed] of every host while [run] is active and is kept, out of date, after it stops.
 */
class HostRegistry(private val factory: HostLiveFactory) {
    private val feeds = MutableStateFlow<Map<HostRoute, HostFeed>>(emptyMap())
    private val lives = ConcurrentHashMap<HostRoute, HostLive>()

    /** One state per followed route; it outlives loop restarts and the gaps between [run] calls, and is dropped once the route is not followed. */
    private val states = ConcurrentHashMap<HostRoute, LiveState>()
    private val generation = MutableStateFlow(0)

    val state: StateFlow<Map<HostRoute, HostFeed>> get() = feeds

    /**
     * Runs the loop of every followed route [hosts] names until cancelled; a route that appears starts its loop and one that disappears
     * stops it and drops its feed, unless it is retained: then the feed stays, not reported. A start begins with every followed feed
     * connecting again. A [restart] stops every loop and starts it again with the configuration current by then.
     */
    suspend fun run(hosts: Flow<FollowedHosts>): Nothing = supervisorScope {
        val jobs = mutableMapOf<HostRoute, Job>()
        var started = generation.value
        try {
            hosts.combine(generation) { followed, current -> followed to current }.collect { (followed, current) ->
                val (wanted, retained) = followed
                if (current != started) {
                    started = current
                    jobs.values.forEach { it.cancel() }
                    jobs.clear()
                    lives.clear()
                }
                val wantedSet = wanted.toSet()
                val retainedSet = retained.toSet() - wantedSet
                states.keys.retainAll(wantedSet)
                for (gone in jobs.keys - wantedSet) {
                    jobs.remove(gone)?.cancel()
                    lives.remove(gone)
                }
                feeds.update { map ->
                    map.filterKeys { it in wantedSet || it in retainedSet }
                        .mapValues { (route, feed) ->
                            if (route in retainedSet) feed.copy(connection = HostConnection.NotReported, refreshing = false) else feed
                        }
                }
                for (route in wanted) {
                    if (route !in jobs) jobs[route] = launch { follow(route) }
                }
            }
            error("the host list flow never completes")
        } finally {
            lives.clear()
        }
    }

    /** Restarts every loop of the running [run] so that it reads the current configuration; the rows stay until the new loop reports. */
    fun restart() {
        generation.update { it + 1 }
    }

    /** Asks every running loop to re-seed now, or to stop waiting for its next reconnect. */
    fun requestReseed() {
        val routes = lives.keys.toList()
        feeds.update { map -> map.mapValues { (route, feed) -> if (route in routes) feed.copy(refreshing = true) else feed } }
        routes.forEach { lives[it]?.requestReseed() }
    }

    /** Tells every running loop that a usable network appeared: those waiting out a backoff try now, the others are not touched. */
    fun networkAvailable() {
        lives.values.forEach { it.networkAvailable() }
    }

    private suspend fun follow(route: HostRoute) {
        val live = factory.create(route, states.getOrPut(route) { factory.createState(route) })
        lives[route] = live
        feeds.update { map ->
            val feed = map[route] ?: HostFeed()
            map + (route to feed.copy(connection = HostConnection.Connecting, refreshing = false, downSince = feed.downSince ?: factory.now()))
        }
        live.updates().collect { update ->
            feeds.update { map -> map[route]?.let { map + (route to it.apply(update, factory.now())) } ?: map }
        }
    }
}

private fun HostFeed.apply(update: LiveUpdate, now: ComparableTimeMark): HostFeed = when (update) {
    LiveUpdate.Connected -> copy(connection = HostConnection.Connected, refreshing = false, downSince = null)
    is LiveUpdate.Sessions -> copy(sessions = update.sessions)
    LiveUpdate.Reseeded -> copy(refreshing = false)
    is LiveUpdate.Reconnecting ->
        copy(connection = HostConnection.Reconnecting(update.attempt, update.reason, update.code), refreshing = false, downSince = downSince ?: now)
}
