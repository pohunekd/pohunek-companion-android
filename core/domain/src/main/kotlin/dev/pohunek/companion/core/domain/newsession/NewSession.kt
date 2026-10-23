// Form rules ported to Kotlin from pohunek-companion `crates/core/src/new_session.rs` (agent_choices, submit_blocker, build_params, classify).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.newsession

import dev.pohunek.companion.core.domain.sessions.sanitizeText
import dev.pohunek.companion.protocol.AgentRuntime
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProjectInfo
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.SessionNewParams
import dev.pohunek.companion.protocol.SessionNewResult

/** A project of the chosen host; [label] is cleaned for display and search, [id] is what `session.new` takes. */
data class ProjectChoice(val id: String, val label: String)

/** An agent the host reports; [unlaunchable] is the reason it cannot start now, or null when it can. */
data class AgentChoice(val name: String, val unlaunchable: String?) {
    val launchable: Boolean get() = unlaunchable == null
}

/** What the host offers: every project, and every agent with launchable ones first. */
data class NewSessionChoices(val projects: List<ProjectChoice>, val agents: List<AgentChoice>) {
    val hasLaunchableAgent: Boolean get() = agents.any { it.launchable }

    fun launchable(name: String): Boolean = agents.any { it.name == name && it.launchable }
}

fun projectChoices(projects: List<ProjectInfo>): List<ProjectChoice> = projects.map { ProjectChoice(it.id, sanitizeText(it.label)) }

/** Every reported agent, launchable ones first; each group keeps the daemon's order. */
fun agentChoices(runtimes: List<AgentRuntime>): List<AgentChoice> {
    val (launchable, blocked) = runtimes.map { AgentChoice(it.agent, unlaunchableReason(it)) }.partition { it.launchable }
    return launchable + blocked
}

/** Launchable means present on the host and not reported as an unsupported version; an absent `supported` means no version policy. */
private fun unlaunchableReason(runtime: AgentRuntime): String? = when {
    !runtime.available -> "not available: binary not found on the daemon's PATH"
    runtime.supported == false -> runtime.version?.let(::sanitizeText)?.takeIf { it.isNotEmpty() }?.let { "unsupported version ($it)" } ?: "unsupported version"
    else -> null
}

/** The form as the owner filled it in; [name] and [prompt] are the raw texts. */
data class NewSessionForm(
    val host: HostRoute? = null,
    /** A project id of [NewSessionChoices.projects]. */
    val project: String? = null,
    val agent: String? = null,
    val name: String = "",
    val prompt: String = "",
)

/** What the form is doing besides collecting input. */
data class NewSessionActivity(
    /** The choices of the selected host are still being read. */
    val loading: Boolean = false,
    /** Why the selected host's choices could not be read; its text is the daemon's or the transport's, cleaned. */
    val loadError: String? = null,
    /** A `session.new` is in flight. */
    val inFlight: Boolean = false,
    /** A `session.new` ended without a verdict; the request is never repeated from this form. */
    val unconfirmed: Boolean = false,
)

/** Why Create is disabled, listed in the order the owner should fix them. */
sealed interface CreateBlocker {
    data object NoHost : CreateBlocker

    data object Loading : CreateBlocker

    data class LoadFailed(val detail: String) : CreateBlocker

    data object InFlight : CreateBlocker

    data object Unconfirmed : CreateBlocker

    /** The project the form was opened on is not on the selected host, and no other one was picked. */
    data class RequestedProjectMissing(val project: String) : CreateBlocker

    data object NoProjectsOnDaemon : CreateBlocker

    data object NoProject : CreateBlocker

    data object NoLaunchableAgent : CreateBlocker

    data object NoAgent : CreateBlocker

    data class AgentNotLaunchable(val agent: String) : CreateBlocker
}

/**
 * The first reason [form] cannot be submitted, or null when Create is allowed. [choices] is null until the host's answer arrived;
 * [requestedProject] is the project id the form was opened on, which picking another project supersedes.
 */
fun createBlocker(form: NewSessionForm, choices: NewSessionChoices?, activity: NewSessionActivity, requestedProject: String? = null): CreateBlocker? {
    val waiting = when {
        form.host == null -> CreateBlocker.NoHost
        activity.loading -> CreateBlocker.Loading
        activity.loadError != null -> CreateBlocker.LoadFailed(activity.loadError)
        activity.inFlight -> CreateBlocker.InFlight
        activity.unconfirmed -> CreateBlocker.Unconfirmed
        else -> null
    }
    if (waiting != null) return waiting
    return if (choices == null) CreateBlocker.Loading else selectionBlocker(form, choices, requestedProject)
}

/** The first missing or unusable choice of a form whose host has answered. */
private fun selectionBlocker(form: NewSessionForm, choices: NewSessionChoices, requestedProject: String?): CreateBlocker? = when {
    requestedProject != null && form.project.isNullOrEmpty() && choices.projects.none { it.id == requestedProject } ->
        CreateBlocker.RequestedProjectMissing(requestedProject)
    choices.projects.isEmpty() -> CreateBlocker.NoProjectsOnDaemon
    choices.projects.none { it.id == form.project } -> CreateBlocker.NoProject
    !choices.hasLaunchableAgent -> CreateBlocker.NoLaunchableAgent
    form.agent.isNullOrEmpty() -> CreateBlocker.NoAgent
    !choices.launchable(form.agent) -> CreateBlocker.AgentNotLaunchable(form.agent)
    else -> null
}

/**
 * The form after the choices of its host arrived: the project stays when the host has it, else none is selected; the agent stays
 * when launchable, else the first launchable one is selected (none when the host has none).
 */
fun settleSelection(form: NewSessionForm, choices: NewSessionChoices): NewSessionForm = form.copy(
    project = form.project?.takeIf { id -> choices.projects.any { it.id == id } },
    agent = form.agent?.takeIf(choices::launchable) ?: choices.agents.firstOrNull { it.launchable }?.name,
)

/** The request for a form that passed [createBlocker]; name and prompt are trimmed and sent only when not blank. */
fun sessionNewParams(form: NewSessionForm, cols: Int, rows: Int): SessionNewParams? {
    val project = form.project ?: return null
    val agent = form.agent ?: return null
    return SessionNewParams(agent, cols, rows, project, form.name.trim().ifEmpty { null }, form.prompt.trim().ifEmpty { null })
}

/** How a `session.new` ended. */
sealed interface CreateOutcome {
    /** The session exists. [promptDelivered] is false when a prompt was sent and the daemon did not confirm injecting it. */
    data class Created(val result: SessionNewResult, val promptDelivered: Boolean) : CreateOutcome

    /** The daemon answered with an error. [message] is its text with the recovery hint; the form stays editable. */
    data class Refused(val code: String, val message: String) : CreateOutcome

    /** Proven to precede the request: the connection never opened. The form stays editable. */
    data class NotSent(val message: String) : CreateOutcome

    /** The request left and no verdict arrived; it may have created a session and must not be repeated. */
    data object Unconfirmed : CreateOutcome
}

/** The outcome of a `session.new` that answered [result]; [sentPrompt] is true when the request carried an initial prompt. */
fun createdOutcome(result: SessionNewResult, sentPrompt: Boolean): CreateOutcome.Created =
    CreateOutcome.Created(result, promptDelivered = !sentPrompt || result.appliedInput == true)

/**
 * Splits a failed `session.new` into a daemon refusal, a failure proven to precede the request and an unknown outcome. Only a failure to
 * open the connection proves nothing was sent; everything else after the request left, a lost connection, a timeout or a malformed
 * answer, is unknown.
 */
fun classifyCreateFailure(failure: ProtocolClientException): CreateOutcome = when (failure) {
    is ProtocolClientException.Daemon -> CreateOutcome.Refused(failure.error.code, refusalText(failure.error.msg, failure.error.recover))
    is ProtocolClientException.VersionMismatch ->
        failure.error?.let { CreateOutcome.Refused(it.code, refusalText(it.msg, it.recover)) } ?: CreateOutcome.Unconfirmed
    is ProtocolClientException.InvalidRoute, is ProtocolClientException.ConnectionFailed, is ProtocolClientException.ConnectTimeout ->
        CreateOutcome.NotSent(sanitizeText(failure.message.orEmpty()))
    else -> CreateOutcome.Unconfirmed
}

/** The daemon's message with its recovery hint in parentheses; both are untrusted text. */
private fun refusalText(msg: String, recover: String?): String {
    val hint = recover?.let(::sanitizeText)?.takeIf { it.isNotEmpty() }
    return sanitizeText(msg) + (hint?.let { " ($it)" }.orEmpty())
}
