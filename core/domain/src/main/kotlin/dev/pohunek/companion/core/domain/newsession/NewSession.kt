// Form rules ported to Kotlin from pohunek-companion `crates/core/src/new_session.rs` (agent_choices, submit_blocker, build_params, classify).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.newsession

import dev.pohunek.companion.core.domain.sessions.sanitizeText
import dev.pohunek.companion.protocol.AgentRuntime
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProjectInfo
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.ProtocolError
import dev.pohunek.companion.protocol.SessionNewParams
import dev.pohunek.companion.protocol.SessionNewResult

/** A project of the chosen host; [label] is cleaned for display and search, [id] is what `session.new` takes. */
data class ProjectChoice(val id: String, val label: String)

/** An agent the host reports; [unlaunchable] is the reason it cannot start now, or null when it can. */
data class AgentChoice(val name: String, val unlaunchable: String?) {
    val launchable: Boolean get() = unlaunchable == null
}

/**
 * What the host offers: every project, every agent with launchable ones first, and whether it can run a session in a worktree
 * ([gitAvailable] and [worktreeSupported] come from `host.inspect`).
 */
data class NewSessionChoices(
    val projects: List<ProjectChoice>,
    val agents: List<AgentChoice>,
    val gitAvailable: Boolean,
    val worktreeSupported: Boolean,
) {
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

/** The form as the owner filled it in; [name], [prompt], [branch] and [baseBranch] are the raw texts. */
data class NewSessionForm(
    val host: HostRoute? = null,
    /** A project id of [NewSessionChoices.projects]. */
    val project: String? = null,
    val agent: String? = null,
    val name: String = "",
    val prompt: String = "",
    /** The chosen template's name; null means Blank. */
    val template: String? = null,
    /** The recipe of [template]; null for Blank and while it is still being read. */
    val recipe: Recipe? = null,
    /** A template was chosen and its recipe has not arrived yet. */
    val templateLoading: Boolean = false,
    /** Branch of a Blank session's worktree; unused while a template is chosen. */
    val branch: String = "",
    /** Base branch of a Blank session's worktree; unused while a template is chosen. */
    val baseBranch: String = "",
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

/** Why the launch recipe, a template or a branch, cannot be used yet or on this host. */
sealed interface LaunchBlocker : CreateBlocker {
    data object TemplateLoading : LaunchBlocker

    /** A base branch will be sent without a branch; the daemon refuses that (`session.new base_branch requires branch`). */
    data object BaseBranchWithoutBranch : LaunchBlocker

    /** A branch or base branch will be sent, and the host has no `git`. */
    data object GitUnavailable : LaunchBlocker

    /** A branch or base branch will be sent, and the host cannot run a session in a worktree. */
    data object WorktreeUnsupported : LaunchBlocker
}

/** The branch and base branch the request will carry: the template's, or the Blank texts, trimmed, with an empty one counting as none. */
private fun branchOverrides(form: NewSessionForm): Pair<String?, String?> =
    form.recipe?.let { it.branch to it.baseBranch } ?: (form.branch.trimmedOrNull() to form.baseBranch.trimmedOrNull())

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
    form.templateLoading -> LaunchBlocker.TemplateLoading
    else -> worktreeBlocker(form, choices)
}

/** The host's missing worktree support, when the request will carry a branch or a base branch. */
private fun worktreeBlocker(form: NewSessionForm, choices: NewSessionChoices): CreateBlocker? {
    val (branch, baseBranch) = branchOverrides(form)
    return when {
        branch == null && baseBranch == null -> null
        branch == null -> LaunchBlocker.BaseBranchWithoutBranch
        !choices.gitAvailable -> LaunchBlocker.GitUnavailable
        !choices.worktreeSupported -> LaunchBlocker.WorktreeUnsupported
        else -> null
    }
}

/**
 * The form after the choices of its host arrived: the project stays when the host has it, else none is selected; the agent stays
 * when launchable, else the first launchable one is selected (none when the host has none).
 */
fun settleSelection(form: NewSessionForm, choices: NewSessionChoices): NewSessionForm = form.copy(
    project = form.project?.takeIf { id -> choices.projects.any { it.id == id } },
    agent = form.agent?.takeIf(choices::launchable) ?: choices.agents.firstOrNull { it.launchable }?.name,
)

/** The request for a form that passed [createBlocker]; name, prompt, branch and base branch are trimmed and sent only when not blank. */
fun sessionNewParams(form: NewSessionForm, cols: Int, rows: Int): SessionNewParams? {
    val project = form.project ?: return null
    val agent = form.agent ?: return null
    val (branch, baseBranch) = branchOverrides(form)
    return SessionNewParams(agent, cols, rows, project, form.name.trimmedOrNull(), form.prompt.trimmedOrNull(), branch, baseBranch)
}
