package dev.pohunek.companion.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.layout.TreePosition
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/**
 * The count of a row's direct sub-sessions. While the view folds them it is also the control that shows or hides them under the row;
 * a row in Needs you, or any row while the fold is off, only states the count.
 */
@Composable
internal fun SubSessionsControl(tree: TreePosition, onToggle: () -> Unit) {
    val spacing = PohunekTheme.spacing
    val label = pluralStringResource(R.plurals.sub_sessions_count, tree.subSessions, tree.subSessions)
    val text = @Composable {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
    if (!tree.folding) {
        Row(Modifier.fillMaxWidth().padding(horizontal = spacing.gutter, vertical = spacing.xs).testTag("SessionList.subSessionsCount")) { text() }
        return
    }
    val state = stringResource(if (tree.expanded) R.string.sub_sessions_expanded else R.string.sub_sessions_collapsed)
    val action = stringResource(if (tree.expanded) R.string.sub_sessions_collapse else R.string.sub_sessions_expand)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = PohunekTheme.minTouchTarget)
            .clickable(onClickLabel = action, onClick = onToggle)
            .padding(horizontal = spacing.gutter)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = state
            }
            .testTag("SessionList.subSessions"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(collapsed = !tree.expanded)
        Row(Modifier.padding(start = spacing.md)) { text() }
    }
}
