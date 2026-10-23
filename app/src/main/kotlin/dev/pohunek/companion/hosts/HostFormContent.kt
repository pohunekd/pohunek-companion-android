package dev.pohunek.companion.hosts

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

/** The form that adds or edits a host; [canCancel] is false while no host exists, and removing is offered for a host being edited. */
@Composable
internal fun HostFormContent(form: HostForm, canCancel: Boolean, onEvent: (HostsEvent) -> Unit, modifier: Modifier = Modifier) {
    val spacing = PohunekTheme.spacing
    Column(
        modifier.padding(spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(stringResource(R.string.host_form_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = form.route,
            onValueChange = { onEvent(HostsEvent.RouteChanged(it)) },
            label = { Text(stringResource(R.string.host_form_route)) },
            singleLine = true,
            isError = form.error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag("HostForm.route"),
        )
        OutlinedTextField(
            value = form.label,
            onValueChange = { onEvent(HostsEvent.LabelChanged(it)) },
            label = { Text(stringResource(R.string.host_form_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("HostForm.label"),
        )
        form.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("HostForm.error"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Button(onClick = { onEvent(HostsEvent.SaveHost) }, enabled = !form.saving, modifier = Modifier.testTag("HostForm.save")) {
                Text(stringResource(R.string.host_form_save))
            }
            if (canCancel) {
                OutlinedButton(onClick = { onEvent(HostsEvent.CancelEdit) }, enabled = !form.saving, modifier = Modifier.testTag("HostForm.cancel")) {
                    Text(stringResource(R.string.host_form_cancel))
                }
            }
            if (form.editing != null) {
                TextButton(onClick = { onEvent(HostsEvent.RemoveHost) }, enabled = !form.saving, modifier = Modifier.testTag("HostForm.clear")) {
                    Text(stringResource(R.string.host_form_clear))
                }
            }
        }
    }
}
