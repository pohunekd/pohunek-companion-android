// Discovery plan ported to Kotlin from pohunek-companion `crates/core/src/discovery.rs` (plan, host_key, label, configured_entry).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.hosts

import dev.pohunek.companion.core.domain.sessions.sanitizeText
import dev.pohunek.companion.protocol.HostClass
import dev.pohunek.companion.protocol.HostRecord
import dev.pohunek.companion.protocol.HostRoute
import java.net.InetAddress
import java.net.UnknownHostException

/** A reachable peer the app follows at [route] (the record's address and port); [key] never carries either. */
data class DiscoveredHost(val key: String, val route: HostRoute, val label: String)

/** A peer whose daemon speaks protocol [protocolVersion], which this client cannot use; it is listed and never connected. */
data class IncompatibleHost(val key: String, val label: String, val protocolVersion: Int)

/**
 * What the records ask the app to do. [claimed] holds the keys of peers a configured host names; they are neither followed nor
 * listed, because the configured entry already covers them.
 */
data class DiscoveryPlan(val connect: List<DiscoveredHost>, val incompatible: List<IncompatibleHost>, val claimed: Set<String>)

/**
 * Derives the peers to follow from [records], which are ordered by the host that reported them. Only a `reachable_daemon` record with
 * an IP address literal is followed; `version_mismatch` records are reported as incompatible; every other classification is ignored.
 * Records of one peer collapse to the first, and a peer a configured host in [manual] names keeps that entry.
 */
fun planDiscovery(records: List<HostRecord>, manual: List<ConfiguredHost>): DiscoveryPlan {
    val plan = PlanBuilder(manual)
    records.forEach(plan::add)
    return plan.build()
}

private class PlanBuilder(private val manual: List<ConfiguredHost>) {
    private val connect = LinkedHashMap<String, DiscoveredHost>()
    private val incompatible = LinkedHashMap<String, IncompatibleHost>()
    private val claimed = LinkedHashSet<String>()
    private val manualRoutes = manual.map { it.route }.toSet()

    fun add(record: HostRecord) {
        val key = hostKey(record) ?: return
        if (manual.any { namesRecord(it, record, key) }) {
            claimed += key
            return
        }
        when (val hostClass = record.hostClass) {
            is HostClass.ReachableDaemon -> followableRoute(record)?.let { route ->
                val taken = route in manualRoutes || connect.values.any { it.route == route }
                if (key !in connect && !taken) connect[key] = DiscoveredHost(key, route, label(record, key))
            }
            is HostClass.VersionMismatch ->
                incompatible.putIfAbsent(key, IncompatibleHost(key, label(record, key), hostClass.daemonProtocolVersion))
            HostClass.Unreachable, HostClass.Candidate, is HostClass.Unknown -> Unit
        }
    }

    fun build() = DiscoveryPlan(connect.values.toList(), incompatible.values.toList(), claimed)
}

/** A followed peer; [present] is false once the discovery answer no longer lists it as a reachable daemon. */
data class DiscoveredPeer(val host: DiscoveredHost, val present: Boolean)

/**
 * The peers after a new [plan]: the ones it lists as reachable, then the earlier ones it no longer lists, marked not present so
 * their rows stay while their loop stops. A peer a configured host now covers (by key or by address) or whose route a listed peer
 * took is dropped.
 */
fun trackPeers(previous: List<DiscoveredPeer>, plan: DiscoveryPlan, manual: List<ConfiguredHost>): List<DiscoveredPeer> {
    val current = plan.connect.map { DiscoveredPeer(it, present = true) }
    val takenRoutes = current.map { it.host.route }.toSet()
    val manualAddresses = manual.map { it.route.host.lowercase() }.toSet()
    val left = previous.filter {
        it.host.key !in plan.claimed && current.none { now -> now.host.key == it.host.key } &&
            it.host.route !in takenRoutes && it.host.route.host.lowercase() !in manualAddresses
    }
    return current + left.map { it.copy(present = false) }
}

/** `<overlay>:<peer id>`, else `<overlay>:<fqdn>`; null when the record carries no overlay or no identity. */
private fun hostKey(record: HostRecord): String? {
    if (record.overlay.isEmpty()) return null
    val identity = record.peerId?.ifEmpty { null } ?: record.fqdn?.ifEmpty { null } ?: return null
    return "${record.overlay}:$identity"
}

/** The record's name, else its fqdn, else its key, sanitized; the first one that is not empty after sanitizing. */
private fun label(record: HostRecord, key: String): String =
    listOf(record.name, record.fqdn, key).firstNotNullOfOrNull { value -> value?.let(::sanitizeText)?.ifEmpty { null } } ?: sanitizeText(key)

/**
 * True when [host] names [record]: its host part equals the record's key, address (the form a phone owner types), peer id, fqdn or
 * name. Its port is not compared, so a daemon on another port still counts as the configured one.
 */
private fun namesRecord(host: ConfiguredHost, record: HostRecord, key: String): Boolean {
    val target = host.route.host
    return target == key || listOfNotNull(record.address, record.peerId, record.fqdn, record.name).any {
        it.isNotEmpty() && target.equals(it, ignoreCase = true)
    }
}

private fun followableRoute(record: HostRecord): HostRoute? {
    val address = record.address?.takeIf(::isIpLiteral) ?: return null
    return HostRoute(address, record.port)
}

private const val IPV4_PARTS = 4
private const val IPV4_MAX_OCTET = 255
private const val IPV4_MAX_DIGITS = 3

/** True for an IPv4 or IPv6 literal. Nothing is resolved: a peer controls this text, and a host name must never reach DNS. */
internal fun isIpLiteral(text: String): Boolean = when {
    ':' in text -> text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' } && parsesAsIpv6(text)
    else -> isIpv4(text)
}

private fun isIpv4(text: String): Boolean {
    val parts = text.split('.')
    return parts.size == IPV4_PARTS && parts.all { part ->
        part.length in 1..IPV4_MAX_DIGITS && part.all { it in '0'..'9' } && (part.length == 1 || part[0] != '0') && part.toInt() <= IPV4_MAX_OCTET
    }
}

/** A string with a colon is parsed as an IPv6 literal and never looked up, so an invalid one fails without a network call. */
private fun parsesAsIpv6(text: String): Boolean = try {
    InetAddress.getByName(text)
    true
} catch (_: UnknownHostException) {
    // The text is not an IPv6 literal.
    false
}
