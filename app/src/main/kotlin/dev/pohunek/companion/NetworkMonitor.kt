package dev.pohunek.companion

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.pohunek.companion.core.data.live.HostRegistry
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.info
import kotlinx.coroutines.awaitCancellation

/**
 * Tells the live loops when Android reports a network that carries traffic, so a host waiting out a reconnect backoff tries at once.
 * The request has no transport and no NOT_VPN requirement: a physical network coming back and a VPN network such as NetBird's coming
 * up both match, where the default-network callback would report only the one the system routes through.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val registry: HostRegistry,
    private val log: AppLog,
) {
    /** Watches the networks until cancelled; the callback is unregistered then. */
    suspend fun run(): Nothing {
        val validated = HashSet<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                // Capability changes of a network that was already usable are not a new network.
                val becameUsable = synchronized(validated) {
                    if (usable) {
                        validated.add(network)
                    } else {
                        validated.remove(network)
                        false
                    }
                }
                if (becameUsable) {
                    log.info("network.available", listOf(LogField.transport(capabilities.transportName())))
                    registry.networkAvailable()
                }
            }

            override fun onLost(network: Network) {
                synchronized(validated) { validated.remove(network) }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        try {
            awaitCancellation()
        } finally {
            connectivity.unregisterNetworkCallback(callback)
        }
    }
}

private fun NetworkCapabilities.transportName(): String = when {
    hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
    hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
    hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
    else -> "other"
}
