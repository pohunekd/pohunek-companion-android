package dev.pohunek.companion.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import dev.pohunek.companion.core.domain.layout.DropEntry
import dev.pohunek.companion.core.domain.layout.DropIndicator
import dev.pohunek.companion.core.domain.layout.GroupDrop
import dev.pohunek.companion.core.domain.layout.GroupSection
import dev.pohunek.companion.core.domain.layout.SessionDrop
import dev.pohunek.companion.core.domain.layout.groupDropAt
import dev.pohunek.companion.core.domain.layout.groupStepDown
import dev.pohunek.companion.core.domain.layout.groupStepUp
import dev.pohunek.companion.core.domain.layout.sessionDropAt
import dev.pohunek.companion.core.domain.layout.stepDown
import dev.pohunek.companion.core.domain.layout.stepUp
import dev.pohunek.companion.core.domain.sessions.SessionKey
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** What an item of the My groups list is, as far as dragging goes. */
internal sealed interface DragItem {
    val title: String

    data class Header(val groupId: String, override val title: String) : DragItem

    data class Row(val ref: RowRef, val groupId: String, override val title: String) : DragItem
}

/** Where the dragged item would land. */
internal sealed interface DragTarget {
    val indicator: DropIndicator

    data class Session(val ref: RowRef, val drop: SessionDrop) : DragTarget {
        override val indicator: DropIndicator get() = drop.indicator
    }

    data class Group(val id: String, val drop: GroupDrop) : DragTarget {
        override val indicator: DropIndicator get() = drop.indicator
    }
}

/** The list key of a group header. */
internal fun groupItemKey(groupId: String): String = "group:$groupId"

/**
 * The drag and drop of My groups: one long press on a session row or a group header lifts it, the finger moves a card over the list that
 * stays where it is, and releasing drops it where the indicator is. The dragged item is held by its list key and the target is resolved
 * from the list as drawn when it is asked for, so a list update during the drag moves neither the item nor the drop.
 */
internal class ListDrag(private val listState: LazyListState) {
    /** The draggable items of the list by list key; empty where nothing is draggable. */
    var items: Map<String, DragItem> by mutableStateOf(emptyMap())

    /** The list key of the item being dragged, or null. */
    var source: String? by mutableStateOf(null)
        private set

    /** The finger's position in the list's coordinates. */
    var pointerY: Float by mutableFloatStateOf(0f)
        private set

    var onDrop: (DragTarget) -> Unit = {}
    var haptics: HapticFeedback? = null

    val sourceItem: DragItem? get() = source?.let { items[it] }

    /** The item at [y], by its place in the drawn list. */
    private fun itemAt(y: Float): String? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key?.toString()?.takeIf { it in items }

    private fun entries(): List<DropEntry> = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
        val top = info.offset.toFloat()
        val bottom = (info.offset + info.size).toFloat()
        when (val item = items[info.key.toString()]) {
            is DragItem.Header -> DropEntry.Header(item.groupId, top, bottom)
            is DragItem.Row -> DropEntry.Row(item.ref.layoutKey, item.groupId, top, bottom)
            null -> null
        }
    }

    /** Where the dragged item lands with the finger where it is; null when nothing is dragged or the finger is not over a target. */
    val target: DragTarget?
        get() = when (val item = sourceItem) {
            is DragItem.Row -> sessionDropAt(entries(), item.ref.layoutKey, pointerY)?.let { DragTarget.Session(item.ref, it) }
            is DragItem.Header -> groupDropAt(entries(), item.groupId, pointerY)?.let { DragTarget.Group(item.groupId, it) }
            null -> null
        }

    private fun cancel() {
        source = null
    }

    /** Ends the drag when the item has left the list (its session is gone or the view changed). */
    fun dropIfGone() {
        if (source != null && sourceItem == null) cancel()
    }

    /** Detects a long press on a draggable item and follows the finger until it lifts. */
    fun Modifier.reorderGesture(): Modifier = pointerInput(this@ListDrag) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val key = itemAt(down.position.y) ?: return@awaitEachGesture
            val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { holdUntilLifted(down) }
            if (released != null) return@awaitEachGesture
            source = key
            pointerY = down.position.y
            haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
            try {
                follow(down.id)
            } finally {
                cancel()
            }
        }
    }

    /**
     * Returns once the finger lifts or leaves the touch slop, which is a tap or the start of a scroll. It watches the events before the
     * children do, because a row's own click handling consumes the press, which would end a long-press wait that looks at them after it.
     */
    private suspend fun AwaitPointerEventScope.holdUntilLifted(down: PointerInputChange) {
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return
        }
    }

    /** Consumes the pointer's events before the list's scroll sees them; a lift drops, anything else cancels. */
    private suspend fun AwaitPointerEventScope.follow(pointerId: PointerId) {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            val change = event.changes.firstOrNull { it.id == pointerId } ?: return
            pointerY = change.position.y
            if (change.changedToUpIgnoreConsumed()) {
                target?.let {
                    haptics?.performHapticFeedback(HapticFeedbackType.Confirm)
                    onDrop(it)
                }
                return
            }
            if (!change.pressed) return
        }
    }
}

/** Scrolls the list while the finger of a drag is within the edge zone, faster the nearer the edge. */
@Composable
internal fun DragAutoScroll(drag: ListDrag, listState: LazyListState) {
    val density = LocalDensity.current
    LaunchedEffect(drag, listState) {
        val edge = with(density) { PohunekTheme.dragEdgeZone.toPx() }
        val top = with(density) { PohunekTheme.dragScrollSpeed.toPx() }
        snapshotFlow { drag.source != null }.collectLatest { dragging ->
            if (!dragging) return@collectLatest
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val seconds = (now - last) / NANOS_PER_SECOND
                last = now
                val height = listState.layoutInfo.viewportSize.height
                val depth = when {
                    drag.pointerY < edge -> drag.pointerY / edge - 1f
                    drag.pointerY > height - edge -> 1f - (height - drag.pointerY) / edge
                    else -> 0f
                }.coerceIn(-1f, 1f)
                if (depth != 0f) listState.scrollBy(depth * top * seconds)
            }
        }
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000f

/** The card that follows the finger while an item is dragged, and the mark of where it would land. */
@Composable
internal fun DragOverlay(drag: ListDrag, modifier: Modifier = Modifier) {
    val item = drag.sourceItem ?: return
    val ghostHeight = with(LocalDensity.current) { PohunekTheme.dragGhostHeight.roundToPx() }
    Box(modifier) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            shadowElevation = PohunekTheme.spacing.sm,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PohunekTheme.spacing.gutter)
                .height(PohunekTheme.dragGhostHeight)
                .offset { IntOffset(0, drag.pointerY.roundToInt() - ghostHeight) }
                .testTag("SessionList.dragGhost"),
        ) {
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = PohunekTheme.spacing.md)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            }
        }
        drag.target?.indicator?.let { DropMark(it) }
    }
}

@Composable
private fun DropMark(indicator: DropIndicator) {
    val color = MaterialTheme.colorScheme.primary
    when (indicator) {
        is DropIndicator.Line -> {
            val thickness = with(LocalDensity.current) { PohunekTheme.dropLineThickness.roundToPx() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(PohunekTheme.dropLineThickness)
                    .offset { IntOffset(0, indicator.y.roundToInt() - thickness / 2) }
                    .background(color)
                    .testTag("SessionList.dropIndicator"),
            )
        }
        is DropIndicator.Span -> Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, indicator.top.roundToInt()) }
                .height(with(LocalDensity.current) { (indicator.bottom - indicator.top).toDp() })
                .background(color.copy(alpha = PohunekTheme.DROP_HEADER_ALPHA))
                .testTag("SessionList.dropIndicator"),
        )
    }
}

/** The draggable items of the shown groups: each group's header and its rows (a collapsed group shows none). */
internal fun dragItems(sections: List<GroupSection<SessionRow>>): Map<String, DragItem> = buildMap {
    for (section in sections) {
        put(groupItemKey(section.id), DragItem.Header(section.id, section.name))
        if (!section.collapsed) section.rows.forEach { put(it.listKey, DragItem.Row(it.ref, section.id, it.title)) }
    }
}

/** The dimming of the item being dragged. */
internal fun Modifier.draggedLook(lifted: Boolean): Modifier = if (lifted) alpha(PohunekTheme.DRAG_SOURCE_ALPHA) else this

/** Which of "Move up" and "Move down" the sheet of a session or a group offers. */
internal data class StepOffer(val up: Boolean, val down: Boolean)

/** The steps of [row] over its neighbours in the shown groups; null where the list is not draggable or the row is not in a group (Needs you). */
internal fun SessionListState.stepOffer(row: SessionRow): StepOffer? {
    if (!view.isEditable) return null
    val shown = shownGroups.keyed()
    if (shown.none { row.layoutKey in it.rows }) return null
    return StepOffer(stepUp(shown, row.layoutKey) != null, stepDown(shown, row.layoutKey) != null)
}

/** The steps of group [id] over its neighbours among the groups shown. */
internal fun SessionListState.groupStepOffer(id: String): StepOffer {
    val ids = shownGroups.map { it.id }
    return StepOffer(groupStepUp(ids, id) != null, groupStepDown(ids, id) != null)
}

/** The sections with their rows as layout keys, which is what the step functions work on. */
internal fun List<GroupSection<SessionRow>>.keyed(): List<GroupSection<SessionKey>> =
    map { GroupSection(it.id, it.name, it.collapsed, it.rows.map(SessionRow::layoutKey)) }

/** The event that applies a drop. */
internal fun DragTarget.event(): SessionListEvent = when (this) {
    is DragTarget.Session -> SessionListEvent.DropSession(ref, drop.groupId, drop.placement)
    is DragTarget.Group -> SessionListEvent.DropGroup(id, drop.placement)
}
