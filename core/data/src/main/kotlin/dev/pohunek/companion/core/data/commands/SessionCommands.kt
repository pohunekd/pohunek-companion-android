package dev.pohunek.companion.core.data.commands

import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.data.log.logCode
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.error
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.core.domain.sessions.PROFILE_CHANGED_CODE
import dev.pohunek.companion.core.domain.sessions.PROFILE_CHANGE_LOCAL_ONLY_CODE
import dev.pohunek.companion.core.domain.sessions.resumeRefusalSentence
import dev.pohunek.companion.core.domain.sessions.sanitizeText
import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.PohunekConnection
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.SessionState
import kotlinx.coroutines.CoroutineDispatcher

/** How a command to a daemon ended. */
sealed interface CommandResult {
    data object Done : CommandResult

    /** [detail] is the daemon's `msg` with its `recover` hint in parentheses, or the transport failure; cleaned for display. */
    data class Failed(val detail: String) : CommandResult
}

/** How a `session.resume` ended. */
sealed interface ResumeResult {
    /** The daemon started a new generation; [running] is true when it already runs, so a terminal can be attached. */
    data class Resumed(val running: Boolean) : ResumeResult

    /** [detail] is the daemon's `msg` with its `recover` hint, the host-side hint for a changed profile, or the transport failure. */
    data class Failed(val detail: String) : ResumeResult
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
        when (val attempt = run(route, sessionId, "session.rename") { it.rename(sessionId, name) }) {
            is Attempt.Ok -> CommandResult.Done
            is Attempt.Err -> CommandResult.Failed(attempt.detail)
        }

    /**
     * Resumes the ended [sessionId] on [route] with the bare id, which never approves a changed agent profile: that is answered with
     * the hint to run `pohunek session resume` on the host.
     */
    suspend fun resume(route: HostRoute, sessionId: String): ResumeResult =
        when (val attempt = run(route, sessionId, "session.resume") { it.resume(sessionId) }) {
            is Attempt.Ok -> ResumeResult.Resumed(attempt.value.state == SessionState.Running)
            is Attempt.Err -> ResumeResult.Failed(attempt.detail)
        }

    private sealed interface Attempt<out T> {
        data class Ok<T>(val value: T) : Attempt<T>

        data class Err(val detail: String) : Attempt<Nothing>
    }

    private suspend fun <T> run(route: HostRoute, sessionId: String, method: String, command: suspend (PohunekConnection) -> T): Attempt<T> {
        val fields = listOf(hostKeyField(route), LogField.sessionId(sessionId), LogField.method(method))
        return try {
            val value = PohunekConnection.open(route, client, ioDispatcher).use { command(it) }
            log.info("session.command", fields)
            Attempt.Ok(value)
        } catch (e: ProtocolClientException) {
            log.error("session.command_failed", fields + LogField.errorCode(e.logCode()))
            Attempt.Err(e.display())
        }
    }
}

/**
 * The text shown for a failed command; daemon text is untrusted, so control characters are removed. A changed agent profile is
 * answered with the fixed sentence naming the command to run on the host.
 */
internal fun ProtocolClientException.display(): String = when {
    this is ProtocolClientException.Daemon && error.code in PROFILE_APPROVAL_CODES -> resumeRefusalSentence(error.code)
    this is ProtocolClientException.Daemon -> {
        val recover = error.recover?.let(::sanitizeText)?.takeIf { it.isNotEmpty() }
        sanitizeText(error.msg) + (recover?.let { " ($it)" }.orEmpty())
    }
    else -> sanitizeText(message.orEmpty())
}

private val PROFILE_APPROVAL_CODES = setOf(PROFILE_CHANGED_CODE, PROFILE_CHANGE_LOCAL_ONLY_CODE)
