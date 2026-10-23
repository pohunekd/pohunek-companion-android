package dev.pohunek.companion.sessions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** Tap opens the terminal; the trailing icon and a long press open the row's action sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionRowItem(row: SessionRow, onClick: () -> Unit, onMore: () -> Unit) {
    val color = ownerStateColor(row.ownerState)
    val label = ownerStateLabel(row.ownerState)
    ListItem(
        leadingContent = { Box(Modifier.size(PohunekTheme.stateDotSize).background(color, CircleShape)) },
        headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                val summary = buildAnnotatedString {
                    withStyle(SpanStyle(color = color)) { append(label) }
                    append(" · ${row.agent}")
                }
                Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val work = listOfNotNull(row.project, row.badge, row.badgeRole)
                if (work.isNotEmpty()) {
                    Text(work.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("SessionList.rowWork"))
                }
                row.runtimeDetail?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("SessionList.runtimeDetail")) }
            }
        },
        trailingContent = {
            IconButton(onClick = onMore, modifier = Modifier.testTag("SessionRow.more")) {
                MoreIcon(stringResource(R.string.row_more_actions))
            }
        },
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onMore, onLongClickLabel = stringResource(R.string.row_more_actions))
            .semantics(mergeDescendants = true) {}
            .testTag("SessionList.row"),
    )
}

@Composable
private fun ownerStateColor(state: OwnerState): Color {
    val colors = PohunekTheme.statusColors
    return when (state) {
        OwnerState.NeedsOwner -> colors.needsOwner
        OwnerState.Working -> colors.working
        OwnerState.Idle -> colors.idle
        OwnerState.RuntimeProblem -> colors.problem
        OwnerState.Ended, OwnerState.NoAgentState -> colors.ended
    }
}

@Composable
private fun ownerStateLabel(state: OwnerState): String = stringResource(
    when (state) {
        OwnerState.NeedsOwner -> R.string.owner_state_needs_owner
        OwnerState.Working -> R.string.owner_state_working
        OwnerState.Idle -> R.string.owner_state_idle
        OwnerState.RuntimeProblem -> R.string.owner_state_runtime_problem
        OwnerState.Ended -> R.string.owner_state_ended
        OwnerState.NoAgentState -> R.string.owner_state_no_agent_state
    },
)

/** Three vertical dots; drawn here because the Material icon set is not a dependency of the app. */
@Composable
private fun MoreIcon(description: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val radius = PohunekTheme.moreDotRadius
    val spacing = PohunekTheme.moreDotSpacing
    Canvas(Modifier.size(radius * 2, spacing * 2 + radius * 2).semantics { contentDescription = description }) {
        for (index in -1..1) drawCircle(color, radius.toPx(), center.copy(y = center.y + index * spacing.toPx()))
    }
}
