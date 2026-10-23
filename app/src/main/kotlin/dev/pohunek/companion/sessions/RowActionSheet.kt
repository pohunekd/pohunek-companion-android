package dev.pohunek.companion.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.sessions.ResumeOffer
import dev.pohunek.companion.core.domain.sessions.SessionAction
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/**
 * The actions of one row; an action the row cannot offer is absent, and [canMove] is false in a derived view, which is read-only.
 * The sheet is a window of its own, so it exposes its test tags itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RowActionSheet(row: SessionRow, resuming: Boolean, canMove: Boolean, onEvent: (SessionListEvent) -> Unit) {
    ModalBottomSheet(onDismissRequest = {
        onEvent(SessionListEvent.DismissActions)
    }, modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("RowSheet")) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PohunekTheme.spacing.gutter, vertical = PohunekTheme.spacing.sm).testTag("RowSheet.title"),
            )
            HorizontalDivider()
            if (row.attachRefusal == null) {
                SheetAction(stringResource(R.string.row_action_open_terminal), "RowSheet.openTerminal") { onEvent(SessionListEvent.OpenSession(row.ref)) }
            }
            ResumeAction(row.resume, resuming, onEvent, row.ref)
            SheetAction(stringResource(R.string.row_action_details), "RowSheet.details") { onEvent(SessionListEvent.OpenDetails(row.ref)) }
            if (row.canRename) {
                SheetAction(stringResource(R.string.row_action_rename), "RowSheet.rename") { onEvent(SessionListEvent.OpenRename(row.ref)) }
            }
            if (canMove) {
                SheetAction(stringResource(R.string.row_action_move), "RowSheet.move") { onEvent(SessionListEvent.OpenMoveToGroup(row.ref)) }
            }
            if (row.hasLink) {
                SheetAction(stringResource(R.string.row_action_open_link), "RowSheet.openLink") { onEvent(SessionListEvent.OpenLink(row.ref)) }
            }
            if (row.canStop) {
                SheetAction(stringResource(R.string.row_action_stop), "RowSheet.stop") { onEvent(SessionListEvent.RequestAction(row.ref, SessionAction.Stop)) }
            }
            if (row.canRemove) {
                SheetAction(stringResource(R.string.row_action_remove), "RowSheet.remove") {
                    onEvent(SessionListEvent.RequestAction(row.ref, SessionAction.Remove))
                }
            }
        }
    }
}

/** Resume when the daemon offers it; for an ended session it cannot resume, the reason instead. */
@Composable
private fun ResumeAction(offer: ResumeOffer, resuming: Boolean, onEvent: (SessionListEvent) -> Unit, ref: RowRef) {
    when {
        offer == ResumeOffer.Available && resuming -> ListItem(
            headlineContent = { Text(stringResource(R.string.row_action_resuming)) },
            modifier = Modifier.testTag("RowSheet.resuming"),
        )
        offer == ResumeOffer.Available -> SheetAction(stringResource(R.string.row_action_resume), "RowSheet.resume") { onEvent(SessionListEvent.Resume(ref)) }
        offer is ResumeOffer.Refused -> ListItem(
            headlineContent = { Text(offer.sentence, color = MaterialTheme.colorScheme.error) },
            overlineContent = { Text(stringResource(R.string.row_action_resume_refused)) },
            modifier = Modifier.testTag("RowSheet.resumeRefusal"),
        )
    }
}

@Composable
private fun SheetAction(text: String, tag: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(text) }, modifier = Modifier.clickable(onClick = onClick).testTag(tag))
}
