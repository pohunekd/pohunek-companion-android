package dev.pohunek.companion.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.style.TextOverflow
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.layout.HostPickerRow
import dev.pohunek.companion.core.domain.layout.HostReach
import dev.pohunek.companion.core.domain.layout.hostPickerRows
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** The `Host ▾` chip, which opens the [HostSheet]; it is offered while the host selector is. */
@Composable
internal fun HostFilter(state: SessionListState, onEvent: (SessionListEvent) -> Unit) {
    if (state.hostOptions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val selected = state.hostOptions.firstOrNull { it.selected && it.key != null }
    val value = selected?.label
    val description = when {
        selected == null -> stringResource(R.string.host_filter_description)
        selected.reach == HostReach.Unreachable -> stringResource(R.string.host_filter_selected_unreachable_description, value.orEmpty())
        else -> stringResource(R.string.host_filter_selected_description, value.orEmpty())
    }
    BarChip(
        selected = selected != null,
        onClick = { open = true },
        label = value ?: stringResource(R.string.host_filter),
        labelMaxWidth = if (value == null) null else PohunekTheme.filterBarHostMaxWidth,
        modifier = Modifier.semantics { contentDescription = description }.testTag("SessionList.filter.host"),
    )
    if (open) HostSheet(state, onPick = { onEvent(SessionListEvent.TapHost(it)) }, onDismiss = { open = false })
}

/** All hosts and each followed host with its reach and session count. A window of its own, so it exposes its test tags itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostSheet(state: SessionListState, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val rows = remember(state.hostOptions, state.hostPool) { hostPickerRows(state.hostOptions, state.hostPool) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("HostSheet")) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.host_filter),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = PohunekTheme.spacing.gutter, vertical = PohunekTheme.spacing.sm),
            )
            HorizontalDivider()
            for (row in rows) {
                HostRow(row) {
                    onPick(row.key)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun HostRow(row: HostPickerRow, onClick: () -> Unit) {
    val name = if (row.key == null) stringResource(R.string.host_all) else row.label.orEmpty()
    val reach = when (row.reach) {
        null -> null
        HostReach.Connected -> stringResource(R.string.host_reach_connected)
        HostReach.Unreachable -> stringResource(R.string.host_reach_unreachable)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = PohunekTheme.stateRowHeight)
            .selectable(selected = row.selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = PohunekTheme.spacing.gutter)
            .testTag(if (row.key == null) "HostSheet.all" else "HostSheet.host"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PohunekTheme.spacing.md),
    ) {
        RadioButton(selected = row.selected, onClick = null)
        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (reach != null) Text(reach, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(row.count.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
