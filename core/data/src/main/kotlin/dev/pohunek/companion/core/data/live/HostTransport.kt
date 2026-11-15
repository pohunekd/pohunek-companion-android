package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.DaemonEvent
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.NotificationKind
import dev.pohunek.companion.protocol.NotificationRecord
import dev.pohunek.companion.protocol.PohunekConnection
import dev.pohunek.companion.protocol.PohunekSubscription
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.listAllNotifications
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

/** An open event subscription; [events] ends with a [dev.pohunek.companion.protocol.ProtocolClientException] and is collected once. */
interface EventStream : AutoCloseable {
    val events: Flow<DaemonEvent>
}

/**
 * What one seed fetch read: `session.list`, the attention notifications that `notification.list` holds, and whether `host.inspect`
 * reported `session_attention_isolation_supported`.
 */
data class Seed(val sessions: List<SessionInfo>, val notifications: List<NotificationRecord>, val attentionIsolationSupported: Boolean = false)

/** What the live loop needs from one daemon. Every failure is a [dev.pohunek.companion.protocol.ProtocolClientException]. */
interface HostTransport {
    /** Returns once the daemon acknowledged the subscription. */
    suspend fun subscribe(): EventStream

    /** Reads `session.list`, the attention notifications and the isolation capability over a connection of its own. */
    suspend fun fetchSeed(): Seed

    /** Sends `daemon.health` over a connection of its own. */
    suspend fun probe()
}

/** [HostTransport] over TCP; each call opens the connection it needs and the subscription's stays open until it is closed. */
class TcpHostTransport(
    private val route: HostRoute,
    private val config: ClientConfig,
    private val ioDispatcher: CoroutineDispatcher,
    private val notificationPageSize: Int,
) : HostTransport {
    override suspend fun subscribe(): EventStream {
        val subscription = PohunekSubscription.open(route, config, ioDispatcher)
        return object : EventStream {
            override val events: Flow<DaemonEvent> get() = subscription.events

            override fun close() = subscription.close()
        }
    }

    override suspend fun fetchSeed(): Seed = PohunekConnection.open(route, config, ioDispatcher).use { connection ->
        val sessions = connection.listSessions()
        val notifications = NotificationKind.ATTENTION.flatMap { kind ->
            listAllNotifications(kind, notificationPageSize) { connection.listNotifications(it) }
        }
        Seed(sessions, notifications, connection.inspectHostFlags().sessionAttentionIsolationSupported)
    }

    override suspend fun probe() {
        PohunekConnection.open(route, config, ioDispatcher).use { it.health() }
    }
}
