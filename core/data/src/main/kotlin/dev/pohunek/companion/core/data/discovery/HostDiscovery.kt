package dev.pohunek.companion.core.data.discovery

import dev.pohunek.companion.core.data.commands.display
import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.data.log.logCode
import dev.pohunek.companion.core.domain.hosts.ConfiguredHost
import dev.pohunek.companion.core.domain.hosts.DiscoveredPeer
import dev.pohunek.companion.core.domain.hosts.IncompatibleHost
import dev.pohunek.companion.core.domain.hosts.planDiscovery
import dev.pohunek.companion.core.domain.hosts.trackPeers
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.core.domain.log.warn
import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.HostRecord
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.PohunekConnection
import dev.pohunek.companion.protocol.ProtocolClientException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** Tunables of host discovery; both are positive. */
class DiscoveryConfig(
    /** Interval between two rounds of `host.discover` on every connected configured host. */
    val interval: Duration,
    /** Time the exchange of one `host.discover` may take once connected; the daemon probes its peers before it answers. */
    val timeout: Duration,
) {
    init {
        require(interval.isPositive()) { "interval must be positive" }
        require(timeout.isPositive()) { "timeout must be positive" }
    }
}

/** Why asking [host] for its peers failed; [detail] is the daemon's text or the transport failure, cleaned for display. */
data class DiscoveryFailure(val host: HostRoute, val detail: String)

/** What discovery knows now. */
data class DiscoveryState(
    /** Peers to follow, then the ones that left the answer. */
    val peers: List<DiscoveredPeer> = emptyList(),
    val incompatible: List<IncompatibleHost> = emptyList(),
    /** One entry per configured host whose latest question failed. */
    val failures: List<DiscoveryFailure> = emptyList(),
)

/** What one configured host last told: [records] is its last successful answer and survives a later failure. */
private data class Answer(val records: List<HostRecord>?, val failure: String?)

/**
 * Finds the daemons on the overlay through the configured hosts: every connected one is asked for `host.discover` when it connects and
 * then every [DiscoveryConfig.interval]. A failed question never removes what an earlier answer listed; only a successful answer that
 * omits a peer marks it as left. Each question has a connection of its own, so discovery never delays a live loop.
 */
class HostDiscovery(
    private val client: ClientConfig,
    private val config: DiscoveryConfig,
    private val ioDispatcher: CoroutineDispatcher,
    private val log: AppLog,
) {
    private val answers = MutableStateFlow<Map<HostRoute, Answer>>(emptyMap())
    private val current = MutableStateFlow(DiscoveryState())

    /** The cause last logged per asked host, so that a failure that repeats is logged once. */
    private val loggedCauses = ConcurrentHashMap<HostRoute, String>()

    val state: StateFlow<DiscoveryState> get() = current

    /** Runs until cancelled. [connected] holds the configured hosts that are connected now; each is asked when it joins it. */
    suspend fun run(configured: Flow<List<ConfiguredHost>>, connected: Flow<Set<HostRoute>>): Nothing = coroutineScope {
        launch { configured.combine(answers) { hosts, found -> hosts to found }.collect { (hosts, found) -> publish(hosts, found) } }
        connected.distinctUntilChanged().collectLatest { routes ->
            while (true) {
                ask(routes)
                delay(config.interval)
            }
        }
        error("the connected hosts flow never completes")
    }

    private suspend fun ask(routes: Set<HostRoute>) {
        val results = coroutineScope { routes.map { route -> async { route to askOne(route) } }.awaitAll() }
        answers.update { known -> known + results.associate { (route, result) -> route to result.toAnswer(known[route]) } }
    }

    private fun Result.toAnswer(previous: Answer?): Answer = when (this) {
        is Result.Listed -> Answer(records, failure = null)
        is Result.Failed -> Answer(previous?.records, detail)
    }

    private sealed interface Result {
        data class Listed(val records: List<HostRecord>) : Result

        data class Failed(val detail: String) : Result
    }

    private suspend fun askOne(route: HostRoute): Result = try {
        val records = PohunekConnection.open(route, client, ioDispatcher).use { it.discoverHosts(config.timeout) }
        loggedCauses.remove(route)
        Result.Listed(records)
    } catch (e: ProtocolClientException) {
        val cause = e.logCode()
        if (loggedCauses.put(route, cause) != cause) log.warn("discovery.failed", listOf(hostKeyField(route), LogField.errorCode(cause)))
        Result.Failed(e.display())
    }

    /** Recomputes the state from the configured hosts and their latest answers; the answer of a host that is no longer configured is ignored. */
    private fun publish(hosts: List<ConfiguredHost>, found: Map<HostRoute, Answer>) {
        val asked = hosts.map { it.route }.filter { it in found }
        val records = asked.flatMap { found.getValue(it).records.orEmpty() }
        val plan = planDiscovery(records, hosts)
        val failures = asked.mapNotNull { route -> found.getValue(route).failure?.let { DiscoveryFailure(route, it) } }
        val previous = current.value
        // Until one question has been answered the earlier peers stay as they are; with no configured host nothing is followed.
        val answered = asked.any { found.getValue(it).records != null }
        val peers = when {
            hosts.isEmpty() -> emptyList()
            answered -> trackPeers(previous.peers, plan, hosts)
            else -> previous.peers
        }
        val incompatible = if (hosts.isEmpty()) emptyList() else if (answered) plan.incompatible else previous.incompatible
        val next = DiscoveryState(peers, incompatible, failures)
        current.value = next
        if (next.peers.map { it.host.key to it.present } != previous.peers.map { it.host.key to it.present }) {
            log.info(
                "discovery.peers",
                listOf(
                    LogField.number("followed", next.peers.count { it.present }.toLong()),
                    LogField.number("left", next.peers.count { !it.present }.toLong()),
                    LogField.number("incompatible", next.incompatible.size.toLong()),
                ),
            )
        }
    }
}
