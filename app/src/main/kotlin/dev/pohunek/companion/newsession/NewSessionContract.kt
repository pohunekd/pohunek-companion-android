package dev.pohunek.companion.newsession

import dev.pohunek.companion.core.domain.newsession.CreateBlocker
import dev.pohunek.companion.core.domain.newsession.NewSessionActivity
import dev.pohunek.companion.core.domain.newsession.NewSessionChoices
import dev.pohunek.companion.core.domain.newsession.NewSessionForm
import dev.pohunek.companion.core.domain.newsession.ProjectChoice
import dev.pohunek.companion.core.domain.newsession.TemplateError
import dev.pohunek.companion.core.domain.newsession.createBlocker
import dev.pohunek.companion.core.domain.newsession.selectAgentOption
import dev.pohunek.companion.protocol.HostRoute

/** A host the form can start a session on. */
data class HostOption(val route: HostRoute, val label: String)

/** What the last create left to tell the owner; the texts are composed in the view. */
sealed interface CreateNotice {
    /** The daemon refused; [message] is its text and recovery hint. The form is editable. */
    data class Refused(val message: String) : CreateNotice

    /** The daemon answered with an error that does not prove nothing was created; Create stays disabled so that it is not repeated. */
    data class RefusedMaybeCreated(val message: String) : CreateNotice

    /** The request never left; [message] says why. The form is editable. */
    data class NotSent(val message: String) : CreateNotice

    /** The request left and no verdict arrived; Create stays disabled so that it is not repeated. */
    data object Unconfirmed : CreateNotice

    /** The session was created but the daemon did not confirm delivering the prompt; Create stays disabled so that it is not repeated. */
    data object PromptUnconfirmed : CreateNotice

    /** The session was created and the daemon holds the prompt until the agent's dialog is answered; Create stays disabled so that it is not repeated. */
    data object PromptHeld : CreateNotice
}

data class NewSessionState(
    /** False until the hosts file has been read. */
    val hostsRead: Boolean = false,
    /** Every followed host, configured ones first. */
    val hosts: List<HostOption> = emptyList(),
    /** Labels of peers listed but not pickable because they speak another protocol. */
    val incompatibleHosts: List<String> = emptyList(),
    val form: NewSessionForm = NewSessionForm(),
    /** The selected host's projects, or null until its `project.list` answered. */
    val projects: List<ProjectChoice>? = null,
    /** The selected host's projects and agents, or null until both answered. */
    val choices: NewSessionChoices? = null,
    val projectQuery: String = "",
    /** The selected project's static template names, or null until they are read (or when they could not be). */
    val templates: List<String>? = null,
    /** Why the templates or the chosen template could not be used; Blank stays usable. */
    val templateError: TemplateError? = null,
    /** The Advanced section (branch and base branch of a Blank session) is open. */
    val advancedOpen: Boolean = false,
    /** The agent picker lists every profile, not only the owner's. */
    val showAllAgents: Boolean = false,
    val activity: NewSessionActivity = NewSessionActivity(),
    /** The "Open after creating" switch. */
    val attachAfterCreate: Boolean = false,
    val notice: CreateNotice? = null,
    /** The project id the form was opened on, if any. */
    val requestedProject: String? = null,
) {
    /** Why Create is disabled, or null when it is allowed. */
    val blocker: CreateBlocker? get() = createBlocker(form, choices, activity, requestedProject)

    /** The projects whose label contains the query, case-insensitively; all of them for a blank query. */
    val visibleProjects: List<ProjectChoice>
        get() {
            val query = projectQuery.trim()
            return projects.orEmpty().filter { query.isEmpty() || it.label.contains(query, ignoreCase = true) }
        }
}

sealed interface NewSessionEvent {
    /** An edit of the form's own fields, ignored while a create is in flight. */
    sealed interface FormEdit : NewSessionEvent {
        fun applyTo(form: NewSessionForm, choices: NewSessionChoices?): NewSessionForm
    }

    data class SelectHost(val route: HostRoute) : NewSessionEvent

    /** Reads the selected host's choices again, after a failure. */
    data object Reload : NewSessionEvent

    data class SelectProject(val id: String) : NewSessionEvent

    data class SelectAgent(val name: String) : FormEdit {
        override fun applyTo(
            form: NewSessionForm,
            choices: NewSessionChoices?,
        ) = if (name == form.agent) form else form.copy(agent = name, agentOptions = emptyMap())
    }

    /** Picks [choiceId] for the option [optionId] of the selected agent; a choice the host cannot run is ignored. */
    data class SelectAgentOption(val optionId: String, val choiceId: String) : FormEdit {
        override fun applyTo(form: NewSessionForm, choices: NewSessionChoices?) =
            if (choices == null) form else selectAgentOption(form, choices, optionId, choiceId)
    }

    data class EditProjectQuery(val text: String) : NewSessionEvent

    /** A template of [NewSessionState.templates], or null for Blank. */
    data class SelectTemplate(val name: String?) : NewSessionEvent

    data class EditName(val text: String) : FormEdit {
        override fun applyTo(form: NewSessionForm, choices: NewSessionChoices?) = form.copy(name = text)
    }

    data class EditBranch(val text: String) : FormEdit {
        override fun applyTo(form: NewSessionForm, choices: NewSessionChoices?) = form.copy(branch = text)
    }

    data class EditBaseBranch(val text: String) : FormEdit {
        override fun applyTo(form: NewSessionForm, choices: NewSessionChoices?) = form.copy(baseBranch = text)
    }

    data object ToggleAdvanced : NewSessionEvent

    /** Lists the profiles meant for agents or hidden from every picker, or goes back to the owner's. */
    data object ToggleShowAllAgents : NewSessionEvent

    data class EditPrompt(val text: String) : FormEdit {
        override fun applyTo(form: NewSessionForm, choices: NewSessionChoices?) = form.copy(prompt = text)
    }

    data class SetAttachAfterCreate(val attach: Boolean) : NewSessionEvent

    data object Create : NewSessionEvent
}

sealed interface NewSessionEffect {
    /** The session exists and runs; the terminal opens on it in place of the form. */
    data class OpenTerminal(val route: HostRoute, val sessionId: String) : NewSessionEffect

    /** The session exists; the form closes. */
    data object Close : NewSessionEffect
}
