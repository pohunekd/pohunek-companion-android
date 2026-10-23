package dev.pohunek.companion.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextOverflow
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.layout.GroupSection
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** The menu of an owner group: Rename and Delete. The sheet is a window of its own, so it exposes its test tags itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupMenuSheet(section: GroupSection<SessionRow>, onEvent: (SessionListEvent) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { onEvent(SessionListEvent.DismissGroupMenu) },
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("GroupMenu"),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            SheetTitle(section.name, "GroupMenu.title")
            HorizontalDivider()
            SheetItem(stringResource(R.string.group_action_rename), "GroupMenu.rename") { onEvent(SessionListEvent.OpenRenameGroup(section.id)) }
            SheetItem(stringResource(R.string.group_action_delete), "GroupMenu.delete") { onEvent(SessionListEvent.DeleteGroup(section.id)) }
        }
    }
}

/** The groups a session can move to, then New group... which creates one and moves the session into it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoveToGroupSheet(row: SessionRow, groups: List<GroupSection<SessionRow>>, onEvent: (SessionListEvent) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { onEvent(SessionListEvent.DismissMoveToGroup) },
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("MoveSheet"),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            SheetTitle(stringResource(R.string.move_title, row.title), "MoveSheet.title")
            HorizontalDivider()
            LazyColumn {
                items(groups, key = { it.id }) { group ->
                    SheetItem(group.name, "MoveSheet.group") { onEvent(SessionListEvent.MoveToGroup(row.ref, group.id)) }
                }
                item { SheetItem(stringResource(R.string.move_new_group), "MoveSheet.newGroup") { onEvent(SessionListEvent.OpenNewGroupFor(row.ref)) } }
            }
        }
    }
}

/** Names a new group or renames one; the refusal is shown in place and the dialog stays open. */
@Composable
internal fun GroupNameDialog(draft: GroupDraft, onEvent: (SessionListEvent) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(draft.text, TextRange(draft.text.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val rename = draft.purpose is GroupDraftPurpose.Rename
    AlertDialog(
        onDismissRequest = { onEvent(SessionListEvent.DismissGroupName) },
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("GroupName"),
        title = { Text(stringResource(if (rename) R.string.group_name_title_rename else R.string.group_name_title_new)) },
        text = {
            Column {
                OutlinedTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        onEvent(SessionListEvent.EditGroupName(it.text))
                    },
                    placeholder = { Text(stringResource(R.string.group_name_placeholder)) },
                    singleLine = true,
                    enabled = !draft.busy,
                    isError = draft.error != null,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("GroupName.name"),
                )
                draft.error?.let { GroupNameErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(SessionListEvent.SubmitGroupName) }, enabled = !draft.busy, modifier = Modifier.testTag("GroupName.save")) {
                Text(stringResource(if (rename) R.string.group_name_save else R.string.group_name_create))
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(SessionListEvent.DismissGroupName) }, enabled = !draft.busy, modifier = Modifier.testTag("GroupName.cancel")) {
                Text(stringResource(R.string.group_name_cancel))
            }
        },
    )
}

@Composable
private fun GroupNameErrorText(error: GroupNameError) {
    val text = when (error) {
        GroupNameError.Empty -> stringResource(R.string.group_error_empty)
        is GroupNameError.Duplicate -> stringResource(R.string.group_error_duplicate, error.name)
        GroupNameError.Gone -> stringResource(R.string.group_error_gone)
    }
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = PohunekTheme.spacing.sm).testTag("GroupName.error"),
    )
}

@Composable
private fun SheetTitle(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = PohunekTheme.spacing.gutter, vertical = PohunekTheme.spacing.sm).testTag(tag),
    )
}

@Composable
private fun SheetItem(text: String, tag: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(text) }, modifier = Modifier.clickable(onClick = onClick).testTag(tag))
}
