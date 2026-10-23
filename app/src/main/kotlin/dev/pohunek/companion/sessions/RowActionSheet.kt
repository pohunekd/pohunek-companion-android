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
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** The actions of one row; an action the row cannot offer is absent. The sheet is a window of its own, so it exposes its test tags itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RowActionSheet(row: SessionRow, onEvent: (SessionListEvent) -> Unit) {
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
            SheetAction(stringResource(R.string.row_action_details), "RowSheet.details") { onEvent(SessionListEvent.OpenDetails(row.ref)) }
            if (row.hasLink) {
                SheetAction(stringResource(R.string.row_action_open_link), "RowSheet.openLink") { onEvent(SessionListEvent.OpenLink(row.ref)) }
            }
        }
    }
}

@Composable
private fun SheetAction(text: String, tag: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(text) }, modifier = Modifier.clickable(onClick = onClick).testTag(tag))
}
