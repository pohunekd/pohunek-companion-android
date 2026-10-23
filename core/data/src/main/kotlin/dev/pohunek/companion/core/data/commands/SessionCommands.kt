package dev.pohunek.companion.core.data.commands

import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.data.log.logCode
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.error
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.core.domain.sessions.sanitizeText
import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.PohunekConnection
import dev.pohunek.companion.protocol.ProtocolClientException
import kotlinx.coroutines.CoroutineDispatcher

/** How a command to a daemon ended. */
sealed interface CommandResult {
    data object Done : CommandResult

    /** [detail] is the daemon's `msg` with its `recover` hint in parentheses, or the transport failure; cleaned for display. */
    data class Failed(val detail: String) : CommandResult
}

/**
 * Commands that change a session on its daemon. Each call opens a connection of its own, so a command never waits on the live loop
 * of the host. Nothing is applied locally: the change reaches the app through the daemon's session event.
 */
class SessionCommands(
    private val client: ClientConfig,
    private val ioDispatcher: CoroutineDispatcher,
    private val log: AppLog,
) {
    /** Sets the owner name of [sessionId] on [route]; a null [name] clears it so the session shows its id. */
    suspend fun rename(route: HostRoute, sessionId: String, name: String?): CommandResult =
        run(route, sessionId, "session.rename") { it.rename(sessionId, name) }

    private suspend fun run(route: HostRoute, sessionId: String, method: String, command: suspend (PohunekConnection) -> Unit): CommandResult {
        val fields = listOf(hostKeyField(route), LogField.sessionId(sessionId), LogField.method(method))
        return try {
            PohunekConnection.open(route, client, ioDispatcher).use { command(it) }
            log.info("session.command", fields)
            CommandResult.Done
        } catch (e: ProtocolClientException) {
            log.error("session.command_failed", fields + LogField.errorCode(e.logCode()))
            CommandResult.Failed(e.display())
        }
    }
}

/** The text shown for a failed command; daemon text is untrusted, so control characters are removed. */
internal fun ProtocolClientException.display(): String = when (this) {
    is ProtocolClientException.Daemon -> sanitizeText(error.msg) + (error.recover?.let(::sanitizeText)?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty())
    else -> sanitizeText(message.orEmpty())
}
