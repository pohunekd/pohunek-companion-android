package dev.pohunek.companion.sessions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import dev.pohunek.companion.core.domain.sessions.HostLink
import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** Tap opens the terminal; the trailing icon and a long press open the row's action sheet, which a row of a host that is not connected lacks. */
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
                if (row.notShared) NotSharedLabel()
                row.runtimeDetail?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("SessionList.runtimeDetail")) }
                if (row.link == HostLink.Down) {
                    Text(
                        stringResource(R.string.session_list_host_unreachable),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("SessionList.hostUnreachable"),
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HostChip(row.hostLabel)
                if (row.hasActions) {
                    IconButton(onClick = onMore, modifier = Modifier.testTag("SessionRow.more")) { MoreIcon(stringResource(R.string.row_more_actions)) }
                }
            }
        },
        modifier = Modifier
            .alpha(if (row.reachable) 1f else PohunekTheme.DIMMED_ALPHA)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (row.hasActions) onMore else null,
                onLongClickLabel = stringResource(R.string.row_more_actions),
            )
            .semantics(mergeDescendants = true) {}
            .testTag("SessionList.row"),
    )
}

/** Says that the session's group is kept only on this phone; TalkBack reads the longer description instead of the label. */
@Composable
private fun NotSharedLabel() {
    val description = stringResource(R.string.session_row_not_shared_description)
    Text(
        stringResource(R.string.session_row_not_shared),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.semantics { contentDescription = description }.testTag("SessionList.notShared"),
    )
}

/** The host's name on a row; it is the only thing that tells two hosts' rows apart. */
@Composable
private fun HostChip(label: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = PohunekTheme.hostChipMaxWidth)
                .padding(horizontal = PohunekTheme.spacing.sm, vertical = PohunekTheme.spacing.xs)
                .testTag("SessionList.host"),
        )
    }
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
internal fun MoreIcon(description: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val radius = PohunekTheme.moreDotRadius
    val spacing = PohunekTheme.moreDotSpacing
    Canvas(Modifier.size(radius * 2, spacing * 2 + radius * 2).semantics { contentDescription = description }) {
        for (index in -1..1) drawCircle(color, radius.toPx(), center.copy(y = center.y + index * spacing.toPx()))
    }
}
