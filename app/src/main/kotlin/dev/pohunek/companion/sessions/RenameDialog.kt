package dev.pohunek.companion.sessions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.pohunek.companion.R
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** Edits the name of one session; an empty text clears it. The dialog is a window of its own, so it exposes its test tags itself. */
@Composable
internal fun RenameDialog(draft: RenameDraft, onEvent: (SessionListEvent) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(draft.text, TextRange(draft.text.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = { onEvent(SessionListEvent.DismissRename) },
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("RenameDialog"),
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        onEvent(SessionListEvent.EditRename(it.text))
                    },
                    placeholder = { Text(stringResource(R.string.rename_placeholder)) },
                    singleLine = true,
                    enabled = !draft.busy,
                    isError = draft.error != null,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("RenameDialog.name"),
                )
                draft.error?.let { RenameError(draft, it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(SessionListEvent.SubmitRename) }, enabled = !draft.busy, modifier = Modifier.testTag("RenameDialog.save")) {
                Text(stringResource(R.string.rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(SessionListEvent.DismissRename) }, modifier = Modifier.testTag("RenameDialog.cancel")) {
                Text(stringResource(R.string.rename_cancel))
            }
        },
    )
}

@Composable
private fun RenameError(draft: RenameDraft, detail: String) {
    val id = draft.ref.sessionId
    val text = draft.hostLabel?.let { stringResource(R.string.rename_failed_on_host, id, it, detail) } ?: stringResource(R.string.rename_failed, id, detail)
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = PohunekTheme.spacing.sm).testTag("RenameDialog.error"),
    )
}
