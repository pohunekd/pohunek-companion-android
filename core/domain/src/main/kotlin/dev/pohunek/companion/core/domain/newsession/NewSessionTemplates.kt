// Template rules ported to Kotlin from pohunek-companion `crates/core/src/new_session.rs` (template_names, recipe_from_action).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.newsession

import dev.pohunek.companion.protocol.ActionSummary
import dev.pohunek.companion.protocol.ProjectActionResult

/** The launch recipe of a chosen template; [prompt] is static text. */
data class Recipe(val agent: String, val branch: String?, val baseBranch: String?, val prompt: String)

/** Why a template could not be applied; the texts are composed in the view. */
sealed interface TemplateError {
    /** The template's prompt has `${...}` variables, which this app does not fill. */
    data class Variables(val template: String) : TemplateError

    /** The template names an agent this host cannot launch now. */
    data class AgentNotLaunchable(val agent: String) : TemplateError

    /** The daemon's or the transport's failure while reading the templates or one recipe; [detail] is cleaned for display. */
    data class Daemon(val detail: String) : TemplateError
}

/** The names of the templates among a project's actions: the ones with no provider. */
fun templateNames(actions: List<ActionSummary>): List<String> = actions.filter { it.provider == ActionSummary.PROVIDER_NONE }.map { it.name }

/** A template step that either produced [Applied.value] or failed with a [TemplateError]. */
sealed interface TemplateStep<out T> {
    data class Applied<T>(val value: T) : TemplateStep<T>

    data class Failed(val error: TemplateError) : TemplateStep<Nothing>
}

/** The recipe of [result], or [TemplateError.Variables] when its prompt has a `${` placeholder; a blank branch counts as none. */
fun recipeFromAction(name: String, result: ProjectActionResult): TemplateStep<Recipe> =
    if (VARIABLE_MARKER in result.promptContent) {
        TemplateStep.Failed(TemplateError.Variables(name))
    } else {
        TemplateStep.Applied(Recipe(result.agent, result.branch.trimmedOrNull(), result.baseBranch.trimmedOrNull(), result.promptContent))
    }

private const val VARIABLE_MARKER = "\${"

internal fun String?.trimmedOrNull(): String? = this?.trim()?.ifEmpty { null }

/**
 * The form after [recipe] was chosen on a host with [choices]: its agent and prompt fill the form, or [TemplateError.AgentNotLaunchable]
 * when the host cannot launch that agent.
 */
fun applyRecipe(form: NewSessionForm, choices: NewSessionChoices, template: String, recipe: Recipe): TemplateStep<NewSessionForm> =
    if (choices.launchable(recipe.agent)) {
        TemplateStep.Applied(form.copy(template = template, recipe = recipe, templateLoading = false, agent = recipe.agent, prompt = recipe.prompt))
    } else {
        TemplateStep.Failed(TemplateError.AgentNotLaunchable(recipe.agent))
    }

/** The form without a template: no template chosen or being read; the agent, prompt and branch texts stay as the owner left them. */
fun NewSessionForm.withoutTemplate(): NewSessionForm = copy(template = null, recipe = null, templateLoading = false)
