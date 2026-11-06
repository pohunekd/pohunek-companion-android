package dev.pohunek.companion.sessions

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.pohunek.companion.core.domain.layout.hiddenAfterScroll
import dev.pohunek.companion.core.domain.layout.settledHidden
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * How far the bottom filter bar is slid down off the screen. The bar overlays the list and moves by translation only, so the list keeps
 * its size; [overlapPx] is the part of the list the shown bar covers. The bar stays shown while the search is focused, the keyboard is
 * open or touch exploration is on, and it is frozen where it is during a drag of a row.
 */
@Stable
internal class FilterBarVisibility(private val scope: CoroutineScope) {
    var hiddenPx by mutableFloatStateOf(0f)
        private set
    var barHeightPx by mutableFloatStateOf(0f)
    var navInsetPx by mutableFloatStateOf(0f)
    var dragging by mutableStateOf(false)
    var searchFocused by mutableStateOf(false)
    var keyboardOpen by mutableStateOf(false)
    var touchExploration by mutableStateOf(false)

    val pinned: Boolean get() = searchFocused || keyboardOpen || touchExploration

    private val mayHide: Boolean get() = !pinned && !dragging

    /** The height of the bar above the navigation bar, which is what it covers of the list. */
    val overlapPx: Float get() = (barHeightPx - navInsetPx).coerceAtLeast(0f)

    /** What the bar covers of the list right now. */
    val visibleOverlapPx: Float get() = (overlapPx - hiddenPx).coerceAtLeast(0f)

    private var settling: Job? = null

    fun connection(listState: LazyListState): NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f && mayHide) {
                settling?.cancel()
                hiddenPx = hiddenAfterScroll(hiddenPx, available.y, barHeightPx, listState.canScrollForward)
            }
            return Offset.Zero
        }
    }

    fun show() = slideTo(0f)

    fun settle() = slideTo(settledHidden(hiddenPx, barHeightPx))

    private fun slideTo(target: Float) {
        settling?.cancel()
        if (hiddenPx == target) return
        settling = scope.launch { animate(hiddenPx, target) { value, _ -> hiddenPx = value } }
    }
}

/**
 * The state of the bar over [listState]. The bar is shown at the top of the list, while it has to stay, and when [resetKey] changes.
 * A bar left half way when the scroll ends slides to the nearer end.
 */
@Composable
internal fun rememberFilterBarVisibility(listState: LazyListState, resetKey: Any?): FilterBarVisibility {
    val scope = rememberCoroutineScope()
    val bar = remember(scope) { FilterBarVisibility(scope) }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val touchExploration = rememberTouchExploration()
    SideEffect {
        bar.keyboardOpen = keyboardOpen
        bar.touchExploration = touchExploration
    }
    LaunchedEffect(bar, listState) {
        snapshotFlow { bar.pinned || !listState.canScrollBackward }.collectLatest { if (it) bar.show() }
    }
    LaunchedEffect(bar, listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling -> if (!scrolling) bar.settle() }
    }
    LaunchedEffect(bar, resetKey) { bar.show() }
    return bar
}

/** The nested scroll of the list that moves the bar, and the freeze of the bar while a row of [drag] is dragged. */
@Composable
internal fun FilterBarVisibility.followScrollOf(listState: LazyListState, drag: ListDrag): Modifier {
    LaunchedEffect(drag, this) { snapshotFlow { drag.source != null }.collect { dragging = it } }
    return Modifier.nestedScroll(remember(this, listState) { connection(listState) })
}

/** The space at the end of the list that keeps its last row clear of the bar and the floating action button. */
@Composable
internal fun FilterBarVisibility.listBottomPadding(): Dp = with(LocalDensity.current) { overlapPx.toDp() } + PohunekTheme.fabClearance

@Composable
private fun rememberTouchExploration(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}
