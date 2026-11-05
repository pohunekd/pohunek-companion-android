package dev.pohunek.companion.sessions

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.layout.filterKey
import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** The bar's filter chips and its Clear button; a Clear that has nothing to reset is absent. */
@Composable
internal fun FilterBarChips(state: SessionListState, onEvent: (SessionListEvent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PohunekTheme.spacing.sm)) {
        StateFilter(state, onEvent)
        if (state.filtersChanged) {
            IconButton(onClick = { onEvent(SessionListEvent.ClearFilters) }, modifier = Modifier.testTag("SessionList.clearFilters")) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.filters_clear))
            }
        }
    }
}

/** The `State ▾` chip, which opens the [StateSheet]. */
@Composable
private fun StateFilter(state: SessionListState, onEvent: (SessionListEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val count = state.states.selectedCountOrNull(state.stateChips)
    val description = stringResource(R.string.state_filter_description, state.stateChips.count { it in state.states })
    FilterChip(
        selected = count != null,
        onClick = { open = true },
        label = { Text(if (count == null) stringResource(R.string.state_filter) else stringResource(R.string.state_filter_count, count)) },
        trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null) },
        modifier = Modifier.semantics { contentDescription = description }.testTag("SessionList.filter.state"),
    )
    if (open) StateSheet(state, onToggle = { onEvent(SessionListEvent.ToggleState(it)) }, onDismiss = { open = false })
}

/** One check row per offered state with the number of sessions in it; a tap toggles the state at once. A window of its own, so it exposes its tags itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StateSheet(state: SessionListState, onToggle: (OwnerState) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("StateSheet")) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.state_filter),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = PohunekTheme.spacing.gutter, vertical = PohunekTheme.spacing.sm),
            )
            HorizontalDivider()
            for (offered in state.stateChips) {
                StateRow(offered, offered in state.states, state.stateCounts[offered] ?: 0) { onToggle(offered) }
            }
        }
    }
}

@Composable
private fun StateRow(state: OwnerState, selected: Boolean, count: Int, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = PohunekTheme.stateRowHeight)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = PohunekTheme.spacing.gutter)
            .testTag("StateSheet.state.${state.filterKey}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PohunekTheme.spacing.md),
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Text(stringResource(state.chipLabelRes()), Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@StringRes
private fun OwnerState.chipLabelRes(): Int = when (this) {
    OwnerState.NeedsOwner -> R.string.state_chip_needs_you
    OwnerState.Working -> R.string.state_chip_working
    OwnerState.Idle -> R.string.state_chip_idle
    OwnerState.RuntimeProblem -> R.string.state_chip_problem
    OwnerState.Ended -> R.string.state_chip_ended
    OwnerState.NoAgentState -> R.string.state_chip_no_agent
}
