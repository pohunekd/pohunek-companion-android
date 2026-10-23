package dev.pohunek.companion.core.data.sessions

import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.PohunekConnection
import dev.pohunek.companion.protocol.SessionInfo
import kotlinx.coroutines.CoroutineDispatcher

/** Reads the sessions of one daemon over a connection that lives for a single request. */
class HostSessions(private val config: ClientConfig, private val ioDispatcher: CoroutineDispatcher) {
    /** Throws [dev.pohunek.companion.protocol.ProtocolClientException] when the daemon cannot be reached or answers with an error. */
    suspend fun list(route: HostRoute): List<SessionInfo> = PohunekConnection.open(route, config, ioDispatcher).use { it.listSessions() }
}
