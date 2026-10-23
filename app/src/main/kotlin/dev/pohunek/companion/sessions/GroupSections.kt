package dev.pohunek.companion.sessions

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import dev.pohunek.companion.R
import dev.pohunek.companion.core.data.layout.LayoutIssue
import dev.pohunek.companion.core.domain.layout.GroupSection
import dev.pohunek.companion.core.domain.layout.INBOX_ID
import dev.pohunek.companion.core.domain.layout.LayoutView
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** The chip row that switches the list between its views; it scrolls sideways when the labels do not fit. */
@Composable
internal fun ViewChips(selected: LayoutView, onEvent: (SessionListEvent) -> Unit) {
    val spacing = PohunekTheme.spacing
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = spacing.gutter, vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        for (view in LayoutView.entries) {
            FilterChip(
                selected = view == selected,
                onClick = { onEvent(SessionListEvent.SelectView(view)) },
                label = { Text(stringResource(view.labelRes())) },
                modifier = Modifier.testTag("SessionList.view.${view.id}"),
            )
        }
    }
}

@StringRes
private fun LayoutView.labelRes(): Int = when (this) {
    LayoutView.MY_GROUPS -> R.string.view_my_groups
    LayoutView.BY_STATE -> R.string.view_by_state
    LayoutView.BY_PROJECT -> R.string.view_by_project
    LayoutView.BY_WORK_ITEM -> R.string.view_by_work_item
}

/** The header of a section: its name, how many sessions it holds and a tap target that collapses or expands it. */
@Composable
internal fun GroupHeader(section: GroupSection<SessionRow>, onEvent: (SessionListEvent) -> Unit) {
    val spacing = PohunekTheme.spacing
    val state = stringResource(if (section.collapsed) R.string.group_collapsed else R.string.group_expanded)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(if (section.collapsed) R.string.group_expand else R.string.group_collapse)) {
                onEvent(SessionListEvent.SetSectionCollapsed(section.id, !section.collapsed))
            }
            .padding(horizontal = spacing.gutter, vertical = spacing.md)
            .semantics(mergeDescendants = true) { stateDescription = state }
            .testTag("SessionList.groupHeader"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(section.collapsed)
        Text(
            section.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = spacing.md),
        )
        Text(section.rows.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (section.id != INBOX_ID) {
            IconButton(onClick = { onEvent(SessionListEvent.OpenGroupMenu(section.id)) }, modifier = Modifier.testTag("GroupHeader.more")) {
                MoreIcon(stringResource(R.string.group_more))
            }
        }
    }
}

/** The chevron's tips sit this fraction of its width in from the edge it points away from. */
private const val CHEVRON_INSET_DIVISOR = 4f

/** Points right when the group is collapsed and down when it is expanded; drawn here because the Material icon set is not a dependency. */
@Composable
private fun Chevron(collapsed: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(PohunekTheme.chevronSize)) {
        val inset = size.width / CHEVRON_INSET_DIVISOR
        val points = if (collapsed) {
            listOf(Offset(inset, 0f), Offset(size.width - inset, size.height / 2), Offset(inset, size.height))
        } else {
            listOf(Offset(0f, inset), Offset(size.width / 2, size.height - inset), Offset(size.width, inset))
        }
        val width = PohunekTheme.chevronStroke.toPx()
        drawLine(color, points[0], points[1], width, StrokeCap.Round)
        drawLine(color, points[1], points[2], width, StrokeCap.Round)
    }
}

/** What is wrong with the layout file; shown above the list, which stays usable. */
@Composable
internal fun LayoutIssueBanner(issue: LayoutIssue) {
    val text = when (issue) {
        is LayoutIssue.Unreadable -> stringResource(R.string.layout_unreadable, issue.reason)
        is LayoutIssue.SaveFailed -> stringResource(R.string.layout_save_failed, issue.reason)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(PohunekTheme.spacing.gutter).testTag("Layout.error"),
    )
}
