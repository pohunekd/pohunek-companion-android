package dev.pohunek.companion.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import dev.pohunek.companion.R
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** The form that adds or edits the host; [hostConfigured] enables cancel and remove. */
@Composable
internal fun HostFormContent(form: HostForm, hostConfigured: Boolean, onEvent: (SessionListEvent) -> Unit, modifier: Modifier = Modifier) {
    val spacing = PohunekTheme.spacing
    Column(
        modifier.padding(spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(stringResource(R.string.host_form_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = form.route,
            onValueChange = { onEvent(SessionListEvent.RouteChanged(it)) },
            label = { Text(stringResource(R.string.host_form_route)) },
            singleLine = true,
            isError = form.error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag("HostForm.route"),
        )
        OutlinedTextField(
            value = form.label,
            onValueChange = { onEvent(SessionListEvent.LabelChanged(it)) },
            label = { Text(stringResource(R.string.host_form_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("HostForm.label"),
        )
        form.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("HostForm.error"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Button(onClick = { onEvent(SessionListEvent.SaveHost) }, enabled = !form.saving, modifier = Modifier.testTag("HostForm.save")) {
                Text(stringResource(R.string.host_form_save))
            }
            if (hostConfigured) {
                OutlinedButton(onClick = { onEvent(SessionListEvent.CancelEdit) }, enabled = !form.saving, modifier = Modifier.testTag("HostForm.cancel")) {
                    Text(stringResource(R.string.host_form_cancel))
                }
                TextButton(onClick = { onEvent(SessionListEvent.ClearHost) }, enabled = !form.saving, modifier = Modifier.testTag("HostForm.clear")) {
                    Text(stringResource(R.string.host_form_clear))
                }
            }
        }
    }
}
