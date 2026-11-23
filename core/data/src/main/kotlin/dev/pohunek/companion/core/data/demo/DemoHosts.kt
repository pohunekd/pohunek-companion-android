package dev.pohunek.companion.core.data.demo

import dev.pohunek.companion.core.domain.hosts.ConfiguredHost
import dev.pohunek.companion.protocol.HostRoute

/**
 * The synthetic hosts of the demo mode. Their routes use the reserved `.invalid` top-level name (RFC 6761), so they can never be a real
 * daemon.
 */
object DemoHosts {
    val primary = ConfiguredHost(HostRoute("showcase.demo.invalid", 1), "Showcase")

    val second = ConfiguredHost(HostRoute("second-host.demo.invalid", 2), "Second host")

    val all: List<ConfiguredHost> = listOf(primary, second)

    fun isDemo(route: HostRoute): Boolean = all.any { it.route == route }
}
