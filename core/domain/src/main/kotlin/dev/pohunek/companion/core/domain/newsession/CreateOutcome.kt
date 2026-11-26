// Outcome classification ported to Kotlin from pohunek-companion `crates/core/src/new_session.rs` (classify, CreateError).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.newsession

import dev.pohunek.companion.core.domain.sessions.sanitizeText
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.ProtocolError
import dev.pohunek.companion.protocol.SessionInitialInputState
import dev.pohunek.companion.protocol.SessionNewResult

/** Where the initial prompt of a created session stands. */
enum class PromptDelivery {
    /** No prompt was sent, or the daemon confirmed injecting it. */
    Delivered,

    /** The daemon holds the prompt until the agent's startup dialog is answered; a daemon restart before that drops it. */
    Held,

    /** A prompt was sent and the daemon did not confirm injecting it, or reported it dropped or in a state this client does not model. */
    Unconfirmed,
}

/** How a `session.new` ended. */
sealed interface CreateOutcome {
    /** The session exists. [prompt] says whether the initial prompt reached it. */
    data class Created(val result: SessionNewResult, val prompt: PromptDelivery) : CreateOutcome

    /** The daemon refused before it recorded a session. [message] is its text with the recovery hint; the form stays editable. */
    data class Refused(val code: String, val message: String) : CreateOutcome

    /**
     * The daemon answered with an error whose code does not prove that nothing was recorded: it can fail after it recorded the
     * session. [message] is its text; the request must not be repeated.
     */
    data class RefusedMaybeCreated(val code: String, val message: String) : CreateOutcome

    /** Proven to precede the request: the connection never opened. The form stays editable. */
    data class NotSent(val message: String) : CreateOutcome

    /** The request left and no verdict arrived; it may have created a session and must not be repeated. */
    data object Unconfirmed : CreateOutcome
}

/** The outcome of a `session.new` that answered [result]; [sentPrompt] is true when the request carried an initial prompt. */
fun createdOutcome(result: SessionNewResult, sentPrompt: Boolean): CreateOutcome.Created =
    CreateOutcome.Created(result, promptDelivery(result, sentPrompt))

/** `applied_input` is true on every successful create, so a daemon that reports `initial_input` (v0.44.0 and newer) has the last word. */
private fun promptDelivery(result: SessionNewResult, sentPrompt: Boolean): PromptDelivery {
    if (!sentPrompt) return PromptDelivery.Delivered
    if (result.appliedInput != true) return PromptDelivery.Unconfirmed
    return when (result.session.initialInput?.state) {
        null, SessionInitialInputState.Delivered -> PromptDelivery.Delivered
        SessionInitialInputState.Pending -> PromptDelivery.Held
        SessionInitialInputState.Dropped, SessionInitialInputState.Unknown -> PromptDelivery.Unconfirmed
    }
}

/**
 * The `session.new` errors the daemon documents as raised before anything is written or as rolled back with a definite verdict
 * (`docs/public-api.md`, `session.new` and "Creation outcome"), plus the validation codes it answers for a bad request. Any other
 * code may come after the session was recorded, for example `session_runtime_reconnecting`.
 */
private val DEFINITE_REFUSALS = setOf(
    "bad_request",
    "project_not_found",
    "agent_profile_not_found",
    "invalid_profile",
    "agent_binary_missing",
    "agent_runtime_unsupported",
    "agent_profile_revision_unavailable",
    "worker_socket_path_invalid",
    "session_input_not_ready",
    "version_mismatch",
    "agent_option_unknown",
    "agent_option_choice_unknown",
    "agent_option_choice_unavailable",
    "agent_options_not_declared",
    "agent_options_locked",
)

/**
 * Splits a failed `session.new` into a refusal, a failure proven to precede the request and an unknown outcome. Only a failure to open
 * the connection proves nothing was sent. A daemon error proves nothing was created only for the codes in [DEFINITE_REFUSALS];
 * everything else after the request left, a lost connection, a timeout or a malformed answer, is unknown.
 */
fun classifyCreateFailure(failure: ProtocolClientException): CreateOutcome = when (failure) {
    is ProtocolClientException.Daemon -> daemonOutcome(failure.error)
    is ProtocolClientException.VersionMismatch -> failure.error?.let(::daemonOutcome) ?: CreateOutcome.Unconfirmed
    is ProtocolClientException.InvalidRoute, is ProtocolClientException.ConnectionFailed, is ProtocolClientException.ConnectTimeout ->
        CreateOutcome.NotSent(sanitizeText(failure.message.orEmpty()))
    else -> CreateOutcome.Unconfirmed
}

private fun daemonOutcome(error: ProtocolError): CreateOutcome {
    val message = refusalText(error.msg, error.recover, error.code)
    return if (error.code in DEFINITE_REFUSALS) CreateOutcome.Refused(error.code, message) else CreateOutcome.RefusedMaybeCreated(error.code, message)
}

/** What the owner can do about a refused option selection, appended when the daemon sends no recovery hint of its own. */
private val OPTION_REFUSAL_HINTS = mapOf(
    "agent_option_unknown" to "the profile changed on the host; reopen the form to read it again",
    "agent_option_choice_unknown" to "the profile changed on the host; reopen the form to read it again",
    "agent_option_choice_unavailable" to "the host's runtime cannot run that choice; pick another one",
    "agent_options_not_declared" to "the profile changed on the host; reopen the form to read it again",
    "agent_options_locked" to "the profile fixes its options; start it with its defaults",
)

/** The daemon's message with its recovery hint in parentheses; both are untrusted text. A refused option selection without a hint gets ours. */
private fun refusalText(msg: String, recover: String?, code: String): String {
    val hint = recover?.let(::sanitizeText)?.takeIf { it.isNotEmpty() } ?: OPTION_REFUSAL_HINTS[code]
    return sanitizeText(msg) + (hint?.let { " ($it)" }.orEmpty())
}
