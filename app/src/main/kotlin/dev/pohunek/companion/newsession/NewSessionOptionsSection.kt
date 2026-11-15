package dev.pohunek.companion.newsession

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.newsession.OptionChoice
import dev.pohunek.companion.core.domain.newsession.ProfileOption
import dev.pohunek.companion.core.domain.newsession.effectiveChoice
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** One picker per option of the selected profile, with its default preselected; none when the host does not accept `agent_options`. */
internal fun LazyListScope.agentOptionSections(state: NewSessionState, onEvent: (NewSessionEvent) -> Unit) {
    val options = state.choices?.optionsOf(state.form.agent).orEmpty()
    options.forEach { option ->
        item(key = "agentOption:${option.id}") { AgentOptionPicker(option, state, onEvent) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentOptionPicker(option: ProfileOption, state: NewSessionState, onEvent: (NewSessionEvent) -> Unit) {
    val spacing = PohunekTheme.spacing
    val chosen = effectiveChoice(state.form, option)?.id
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        SectionTitle(option.label)
        Column(Modifier.padding(horizontal = spacing.gutter), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                option.choices.forEach { choice -> ChoiceChip(option, choice, chosen == choice.id, !state.activity.inFlight, onEvent) }
            }
            option.choices.filterNot(OptionChoice::selectable).forEach { choice ->
                Text(
                    stringResource(R.string.new_session_option_unavailable, choice.label, choice.unavailable.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("NewSession.optionNote"),
                )
            }
        }
    }
}

@Composable
private fun ChoiceChip(option: ProfileOption, choice: OptionChoice, selected: Boolean, editable: Boolean, onEvent: (NewSessionEvent) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onEvent(NewSessionEvent.SelectAgentOption(option.id, choice.id)) },
        enabled = choice.selectable && editable,
        label = { Text(choice.label) },
        modifier = Modifier
            .semantics { choice.unavailable?.let { stateDescription = it } }
            .testTag("NewSession.optionChoice"),
    )
}
