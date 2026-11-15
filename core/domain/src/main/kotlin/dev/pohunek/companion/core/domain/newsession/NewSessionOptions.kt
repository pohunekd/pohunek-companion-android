package dev.pohunek.companion.core.domain.newsession

import dev.pohunek.companion.core.domain.sessions.sanitizeText
import dev.pohunek.companion.protocol.AgentOption
import dev.pohunek.companion.protocol.AgentOptionChoice
import dev.pohunek.companion.protocol.AgentOptionUnavailable

/** One choice of a [ProfileOption]; [unavailable] is the reason it cannot be selected now, or null when it can. [id] is what `session.new` takes. */
data class OptionChoice(val id: String, val label: String, val unavailable: String?) {
    val selectable: Boolean get() = unavailable == null
}

/** An option a profile declares, as the form shows it; [default] is the id of the choice a launch takes when none is named. */
data class ProfileOption(val id: String, val label: String, val default: String, val choices: List<OptionChoice>) {
    fun choice(choiceId: String): OptionChoice? = choices.firstOrNull { it.id == choiceId }
}

internal fun profileOption(option: AgentOption) = ProfileOption(
    id = option.id,
    label = sanitizeText(option.label).ifEmpty { sanitizeText(option.id) },
    default = option.default,
    choices = option.choices.map { OptionChoice(it.id, sanitizeText(it.label).ifEmpty { sanitizeText(it.id) }, unavailableReason(it)) },
)

/** The sentence for a choice that cannot be selected; the floor is the profile's text, cleaned. */
private fun unavailableReason(choice: AgentOptionChoice): String? {
    if (choice.available) return null
    val floor = choice.minRuntime?.let(::sanitizeText)?.takeIf { it.isNotEmpty() }
    return when (choice.reason) {
        AgentOptionUnavailable.RuntimeTooOld -> floor?.let { "needs runtime $it or newer" } ?: "runtime is too old"
        AgentOptionUnavailable.RuntimeVersionUnknown -> floor?.let { "runtime version unknown, needs $it or newer" } ?: "runtime version unknown"
        else -> "not available on this host"
    }
}

/** The choice [option] launches with under [form]: the picked one when the profile still declares it, else the default. */
fun effectiveChoice(form: NewSessionForm, option: ProfileOption): OptionChoice? =
    (form.agentOptions[option.id]?.let(option::choice) ?: option.choice(option.default))

internal fun unavailableOption(form: NewSessionForm, choices: NewSessionChoices): CreateBlocker.OptionChoiceUnavailable? =
    choices.optionsOf(form.agent).firstNotNullOfOrNull { option ->
        effectiveChoice(form, option)?.takeUnless { it.selectable }?.let { CreateBlocker.OptionChoiceUnavailable(option.label, it.unavailable.orEmpty()) }
    }

/** [form] with [choiceId] picked for [optionId] of its agent; a pick the agent's options do not declare or the host cannot run is ignored. */
fun selectAgentOption(form: NewSessionForm, choices: NewSessionChoices, optionId: String, choiceId: String): NewSessionForm {
    val choice = choices.optionsOf(form.agent).firstOrNull { it.id == optionId }?.choice(choiceId)
    return if (choice != null && choice.selectable) form.copy(agentOptions = form.agentOptions + (optionId to choiceId)) else form
}

/** The picks that differ from the defaults, for options and choices the agent still declares; null when there are none. */
internal fun agentOptionDiff(form: NewSessionForm, choices: NewSessionChoices?): Map<String, String>? {
    val declared = choices?.optionsOf(form.agent).orEmpty()
    val diff = declared.mapNotNull { option ->
        form.agentOptions[option.id]?.takeIf { it != option.default && option.choice(it) != null }?.let { option.id to it }
    }.toMap()
    return diff.ifEmpty { null }
}
