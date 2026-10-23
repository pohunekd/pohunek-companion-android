package dev.pohunek.companion.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.window.DialogProperties
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.sessions.SessionAction
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/**
 * The confirmation of a stop or remove. Cancel has the focus when it opens, so that a stray Enter from a keyboard cancels. While the call
 * runs the dialog cannot be dismissed and the confirm button shows progress. It is a window of its own and exposes its test tags itself.
 */
@Composable
internal fun ActionDialog(dialog: ActionDialog, onEvent: (SessionListEvent) -> Unit) {
    val dismissFocus = remember { FocusRequester() }
    LaunchedEffect(dialog.busy, dialog.action) { if (!dialog.busy && dialog.notice == null) dismissFocus.requestFocus() }
    AlertDialog(
        onDismissRequest = { onEvent(SessionListEvent.DismissAction) },
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("ActionDialog"),
        properties = DialogProperties(dismissOnBackPress = !dialog.busy, dismissOnClickOutside = !dialog.busy),
        title = { Text(stringResource(headingOf(dialog.action)), modifier = Modifier.testTag("ActionDialog.title")) },
        text = { ActionDialogBody(dialog) },
        confirmButton = {
            if (dialog.notice != null) {
                TextButton(onClick = { onEvent(SessionListEvent.DismissAction) }, modifier = Modifier.testTag("ActionDialog.close")) {
                    Text(stringResource(R.string.action_close))
                }
            } else {
                TextButton(onClick = { onEvent(SessionListEvent.ConfirmAction) }, enabled = !dialog.busy, modifier = Modifier.testTag("ActionDialog.confirm")) {
                    if (dialog.busy) {
                        val working = stringResource(R.string.action_working)
                        CircularProgressIndicator(
                            Modifier.size(PohunekTheme.progressSize).semantics { contentDescription = working }.testTag("ActionDialog.progress"),
                            strokeWidth = PohunekTheme.progressStroke,
                        )
                    } else {
                        Text(stringResource(headingOf(dialog.action)))
                    }
                }
            }
        },
        dismissButton = if (dialog.notice != null) {
            null
        } else {
            {
                TextButton(
                    onClick = { onEvent(SessionListEvent.DismissAction) },
                    enabled = !dialog.busy,
                    modifier = Modifier.focusRequester(dismissFocus).testTag("ActionDialog.dismiss"),
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun ActionDialogBody(dialog: ActionDialog) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PohunekTheme.spacing.sm)) {
        Text(
            stringResource(R.string.action_subject, dialog.name, dialog.hostLabel),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("ActionDialog.subject"),
        )
        if (dialog.notice == null) Text(consequence(dialog), modifier = Modifier.testTag("ActionDialog.consequence"))
        dialog.notice?.let { Text(it, modifier = Modifier.testTag("ActionDialog.notice")) }
        dialog.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ActionDialog.error")) }
    }
}

private fun headingOf(action: SessionAction): Int = when (action) {
    SessionAction.Stop -> R.string.action_heading_stop
    SessionAction.Remove -> R.string.action_heading_remove
    SessionAction.RemoveAnyway -> R.string.action_heading_remove_anyway
}

/** What the action does, then, for a removal, where the checkout goes and, for a runtime problem, that the daemon may refuse. */
@Composable
private fun consequence(dialog: ActionDialog): String {
    val base = stringResource(
        when (dialog.action) {
            SessionAction.Stop -> R.string.action_consequence_stop
            SessionAction.Remove -> R.string.action_consequence_remove
            SessionAction.RemoveAnyway -> R.string.action_consequence_remove_anyway
        },
    )
    val notes = buildList {
        if (dialog.action != SessionAction.Stop) add(stringResource(R.string.action_note_worktree_on_host))
        if (dialog.runtimeProblem && dialog.action != SessionAction.RemoveAnyway) add(stringResource(R.string.action_note_runtime_problem))
    }
    return (listOf(base) + notes).joinToString(" ")
}
