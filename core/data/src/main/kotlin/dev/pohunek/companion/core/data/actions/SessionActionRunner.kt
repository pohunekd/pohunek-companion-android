package dev.pohunek.companion.core.data.actions

import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.data.log.logCode
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.error
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.core.domain.sessions.SessionAction
import dev.pohunek.companion.core.domain.sessions.SessionActionOutcome
import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.PohunekConnection
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.SessionRemoveResult
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Sends one stop or remove to a daemon over a connection of its own, whose request timeout is [config]'s: a removal waits for the
 * daemon to end processes and delete a checkout, which takes far longer than a list request. Every failure is returned as an
 * outcome; only cancellation is rethrown.
 */
class SessionActionRunner(
    private val config: ClientConfig,
    private val ioDispatcher: CoroutineDispatcher,
    private val log: AppLog,
) {
    suspend fun run(route: HostRoute, sessionId: String, action: SessionAction): SessionActionOutcome {
        val fields = listOf(hostKeyField(route), LogField.sessionId(sessionId), LogField.method(action.method))
        log.info("session.action", fields)
        return try {
            PohunekConnection.open(route, config, ioDispatcher).use { connection ->
                when (action) {
                    SessionAction.Stop -> {
                        connection.stop(sessionId)
                        SessionActionOutcome.Done()
                    }
                    SessionAction.Remove -> connection.remove(sessionId).toOutcome()
                    SessionAction.RemoveAnyway -> connection.removeAcceptingUnconfirmed(sessionId).toOutcome()
                }
            }.also { log.info("session.action_done", fields) }
        } catch (e: ProtocolClientException) {
            log.error("session.action_failed", fields + LogField.errorCode(e.logCode()))
            e.toOutcome()
        }
    }
}

private fun SessionRemoveResult.toOutcome() = SessionActionOutcome.Done(worktreesFailed, acceptedUnconfirmedProcesses.map { it.describe() })

/** A timeout or a break after the request left may have applied it; a failure to connect or a malformed answer is reported as is. */
private fun ProtocolClientException.toOutcome(): SessionActionOutcome = when (this) {
    is ProtocolClientException.Daemon -> SessionActionOutcome.Refused(error.code, error.msg, error.recover)
    is ProtocolClientException.RequestTimeout, is ProtocolClientException.ConnectionLost -> SessionActionOutcome.Unknown
    else -> SessionActionOutcome.Failed(message.orEmpty())
}
