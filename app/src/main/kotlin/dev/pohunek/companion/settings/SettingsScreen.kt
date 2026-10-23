package dev.pohunek.companion.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pohunek.companion.R
import dev.pohunek.companion.config.resources
import dev.pohunek.companion.core.domain.config.TunableUnit
import dev.pohunek.companion.core.ui.theme.PohunekTheme

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onOpenAbout: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(state, viewModel::onEvent, onOpenAbout, onBack, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    state: SettingsState,
    onEvent: (SettingsEvent) -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PohunekTheme.spacing
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("Settings.back")) { Text(stringResource(R.string.settings_back)) }
                },
                actions = {
                    TextButton(onClick = {
                        onEvent(SettingsEvent.Save)
                    }, enabled = state.dirty && !state.saving, modifier = Modifier.testTag("Settings.save")) {
                        Text(stringResource(R.string.settings_save))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxWidth().testTag("Settings.list"), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            item { Outcome(state.outcome) }
            item {
                Row(Modifier.padding(horizontal = spacing.gutter), horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OutlinedButton(onClick = {
                        onEvent(SettingsEvent.ResetAll)
                    }, enabled = state.anyOverride, modifier = Modifier.testTag("Settings.resetAll")) {
                        Text(stringResource(R.string.settings_reset_all))
                    }
                }
            }
            items(state.items, key = { it.tunable.key }) { item ->
                TunableRow(item, onEvent)
                HorizontalDivider()
            }
            item {
                Text(
                    stringResource(R.string.settings_about),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAbout).padding(spacing.gutter).testTag("Settings.about"),
                )
            }
        }
    }
}

@Composable
private fun Outcome(outcome: SaveOutcome?) {
    val spacing = PohunekTheme.spacing
    when (outcome) {
        null -> Unit
        SaveOutcome.Saved -> Text(
            stringResource(R.string.settings_saved),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = spacing.gutter, vertical = spacing.sm).testTag("Settings.saved"),
        )
        is SaveOutcome.Refused -> Column(Modifier.padding(horizontal = spacing.gutter, vertical = spacing.sm).testTag("Settings.refusal")) {
            Text(stringResource(R.string.settings_refused), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
            outcome.lines.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
        is SaveOutcome.StorageFailed -> Text(
            stringResource(R.string.settings_storage_failed, outcome.reason),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = spacing.gutter, vertical = spacing.sm).testTag("Settings.storageError"),
        )
    }
}

@Composable
private fun TunableRow(item: TunableItem, onEvent: (SettingsEvent) -> Unit) {
    val spacing = PohunekTheme.spacing
    val key = item.tunable.key
    val unit = unitLabel(item.tunable.unit)
    Column(Modifier.fillMaxWidth().padding(horizontal = spacing.gutter), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(key, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("Settings.key.$key"))
        Text(stringResource(item.tunable.resources().help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = item.text,
            onValueChange = { onEvent(SettingsEvent.Edit(item.tunable, it)) },
            singleLine = true,
            isError = item.error != null,
            suffix = unit?.let { { Text(it) } },
            supportingText = {
                Text(
                    item.error ?: stringResource(R.string.settings_default, item.default.toString(), unit.orEmpty()).trim(),
                    modifier = Modifier.testTag(if (item.error != null) "Settings.error.$key" else "Settings.default.$key"),
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("Settings.value.$key"),
        )
        if (item.text.trim() != item.default.toString()) {
            TextButton(onClick = { onEvent(SettingsEvent.Reset(item.tunable)) }, modifier = Modifier.testTag("Settings.reset.$key")) {
                Text(stringResource(R.string.settings_reset))
            }
        }
    }
}

@Composable
private fun unitLabel(unit: TunableUnit): String? = when (unit) {
    TunableUnit.Milliseconds -> stringResource(R.string.unit_ms)
    TunableUnit.Seconds -> stringResource(R.string.unit_secs)
    TunableUnit.Bytes -> stringResource(R.string.unit_bytes)
    TunableUnit.Characters -> stringResource(R.string.unit_chars)
    TunableUnit.Count -> null
}
