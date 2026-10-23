package dev.pohunek.companion.newsession

import dev.pohunek.companion.core.data.newsession.NewSessions
import dev.pohunek.companion.core.domain.newsession.Recipe
import dev.pohunek.companion.core.domain.newsession.TemplateStep
import dev.pohunek.companion.core.domain.newsession.applyRecipe
import dev.pohunek.companion.core.domain.newsession.withoutTemplate
import dev.pohunek.companion.protocol.HostRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Reads the static templates of the selected host's project and the recipe of the chosen one into the form's state. Every host,
 * project or template change advances a generation, and an answer that no longer matches it is dropped.
 */
class TemplateReader(
    private val scope: CoroutineScope,
    private val newSessions: NewSessions,
    private val read: () -> NewSessionState,
    private val update: ((NewSessionState) -> NewSessionState) -> Unit,
) {
    private var job: Job? = null
    private var generation = 0

    /** The host and project whose templates are loaded or loading; null while none is selected. */
    private var loadedFor: Pair<HostRoute, String>? = null

    /** The host changed: whatever is loading belongs to the old host, and the project's templates are read again once it is known. */
    fun hostChanged() {
        job?.cancel()
        generation++
        loadedFor = null
        update { it.copy(form = it.form.withoutTemplate(), templates = null, templateError = null) }
    }

    /** Reads the templates again when the selected host and project are not the ones they were read for. */
    fun sync() {
        val form = read().form
        val host = form.host
        val project = form.project
        val key = if (host != null && project != null) host to project else null
        if (key == loadedFor) return
        loadedFor = key
        job?.cancel()
        val token = ++generation
        update { it.copy(form = it.form.withoutTemplate(), templates = null, templateError = null) }
        if (key == null) return
        job = scope.launch {
            val step = newSessions.templates(key.first, key.second)
            if (token != generation) return@launch
            when (step) {
                is TemplateStep.Applied -> update { it.copy(templates = step.value) }
                is TemplateStep.Failed -> update { it.copy(templateError = step.error) }
            }
        }
    }

    /** Blank clears the template and keeps the agent and prompt; a template is read, then fills the agent and the prompt. */
    fun select(name: String?) {
        job?.cancel()
        val token = ++generation
        val target = target(read(), name)
        if (target == null) {
            update { it.copy(form = it.form.withoutTemplate(), templateError = null) }
            return
        }
        update { it.copy(form = it.form.copy(template = target.name, recipe = null, templateLoading = true), templateError = null) }
        job = scope.launch {
            val step = newSessions.recipe(target.route, target.project, target.name)
            if (token == generation) arrived(target.name, step)
        }
    }

    private class Target(val route: HostRoute, val project: String, val name: String)

    /** The template [name] of the selected project, or null for Blank or a name the project does not list. */
    private fun target(state: NewSessionState, name: String?): Target? {
        val route = state.form.host
        val project = state.form.project
        val listed = name != null && name in state.templates.orEmpty()
        return if (listed && route != null && project != null) Target(route, project, name) else null
    }

    private fun arrived(name: String, step: TemplateStep<Recipe>) {
        val state = read()
        val choices = requireNotNull(state.choices) { "a template is chosen only after the host's choices are loaded" }
        val applied = when (step) {
            is TemplateStep.Applied -> applyRecipe(state.form, choices, name, step.value)
            is TemplateStep.Failed -> step
        }
        when (applied) {
            is TemplateStep.Applied -> update { it.copy(form = applied.value) }
            is TemplateStep.Failed -> update { it.copy(form = it.form.withoutTemplate(), templateError = applied.error) }
        }
    }
}
