package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.DaemonEvent
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.PohunekConnection
import dev.pohunek.companion.protocol.PohunekSubscription
import dev.pohunek.companion.protocol.SessionInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

/** An open event subscription; [events] ends with a [dev.pohunek.companion.protocol.ProtocolClientException] and is collected once. */
interface EventStream : AutoCloseable {
    val events: Flow<DaemonEvent>
}

/** What the live loop needs from one daemon. Every failure is a [dev.pohunek.companion.protocol.ProtocolClientException]. */
interface HostTransport {
    /** Returns once the daemon acknowledged the subscription. */
    suspend fun subscribe(): EventStream

    /** Reads `session.list` over a connection of its own. */
    suspend fun fetchSeed(): List<SessionInfo>

    /** Sends `daemon.health` over a connection of its own. */
    suspend fun probe()
}

/** [HostTransport] over TCP; each call opens the connection it needs and the subscription's stays open until it is closed. */
class TcpHostTransport(
    private val route: HostRoute,
    private val config: ClientConfig,
    private val ioDispatcher: CoroutineDispatcher,
) : HostTransport {
    override suspend fun subscribe(): EventStream {
        val subscription = PohunekSubscription.open(route, config, ioDispatcher)
        return object : EventStream {
            override val events: Flow<DaemonEvent> get() = subscription.events

            override fun close() = subscription.close()
        }
    }

    override suspend fun fetchSeed(): List<SessionInfo> = PohunekConnection.open(route, config, ioDispatcher).use { it.listSessions() }

    override suspend fun probe() {
        PohunekConnection.open(route, config, ioDispatcher).use { it.health() }
    }
}
