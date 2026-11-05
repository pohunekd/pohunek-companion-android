package dev.pohunek.companion.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.layout.ProjectPickerRow
import dev.pohunek.companion.core.domain.layout.ProjectSelection
import dev.pohunek.companion.core.domain.layout.projectPickerRows
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** The `Project ▾` chip, which opens the [ProjectSheet]; it is offered when the sessions have more than one choice. */
@Composable
internal fun ProjectFilter(state: SessionListState, onEvent: (SessionListEvent) -> Unit) {
    if (state.projectChips.size <= 1) return
    var open by remember { mutableStateOf(false) }
    val selected = state.projectChips.firstOrNull { it.selected }?.selection ?: ProjectSelection.All
    val value = when (selected) {
        ProjectSelection.All -> null
        ProjectSelection.NoProject -> stringResource(R.string.project_none)
        is ProjectSelection.Project -> selected.label
    }
    val description = if (value == null) {
        stringResource(R.string.project_filter_description)
    } else {
        stringResource(R.string.project_filter_selected_description, value)
    }
    BarChip(
        selected = value != null,
        onClick = { open = true },
        label = value ?: stringResource(R.string.project_filter),
        labelMaxWidth = if (value == null) null else PohunekTheme.filterBarProjectMaxWidth,
        modifier = Modifier.semantics { contentDescription = description }.testTag("SessionList.filter.project"),
    )
    if (open) {
        ProjectSheet(state, onPick = { onEvent(SessionListEvent.TapProject(it)) }, onDismiss = { open = false })
    }
}

/** The searchable list of projects with their session counts. A window of its own, so it exposes its test tags itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectSheet(state: SessionListState, onPick: (ProjectSelection) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val rows = remember(state.projectChips, state.projectPool, query) { projectPickerRows(state.projectChips, state.projectPool, query) }
    val projects = state.projectChips.count { it.label != null }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("ProjectSheet"),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.project_sheet_search, projects)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = PohunekTheme.spacing.gutter).testTag("ProjectSheet.search"),
            )
            HorizontalDivider(Modifier.padding(top = PohunekTheme.spacing.sm))
            LazyColumn {
                items(rows, key = { it.selection.toString() }) { row ->
                    ProjectRow(row) {
                        onPick(row.selection)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(row: ProjectPickerRow, onClick: () -> Unit) {
    val tag = when (row.selection) {
        ProjectSelection.All -> "ProjectSheet.all"
        ProjectSelection.NoProject -> "ProjectSheet.none"
        is ProjectSelection.Project -> "ProjectSheet.project"
    }
    val text = when (row.selection) {
        ProjectSelection.All -> stringResource(R.string.project_all)
        ProjectSelection.NoProject -> stringResource(R.string.project_none)
        is ProjectSelection.Project -> row.label.orEmpty()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = PohunekTheme.stateRowHeight)
            .selectable(selected = row.selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = PohunekTheme.spacing.gutter)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PohunekTheme.spacing.md),
    ) {
        RadioButton(selected = row.selected, onClick = null)
        Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (row.needsYou) NeedsYouMark()
        Text(row.count.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NeedsYouMark() {
    val description = stringResource(R.string.project_needs_you)
    Box(
        Modifier
            .size(PohunekTheme.stateDotSize)
            .background(PohunekTheme.statusColors.needsOwner, CircleShape)
            .semantics { contentDescription = description },
    )
}
