package dev.pohunek.companion.sessions

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * A list follows its first visible keyed item, so a Needs you section that appears above it would sit out of view. When the owner left
 * the list at the top, it is kept there. That is read when a scroll ends: a layout shift caused by inserted items is not a scroll.
 */
@Composable
internal fun RevealNeedsYou(listState: LazyListState, needsYouShown: Boolean) {
    var atTop by remember(listState) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (!scrolling) atTop = !listState.canScrollBackward }
    }
    // The first composition is no change: a list restored at a scrolled position stays there.
    var wasShown by remember(listState) { mutableStateOf(needsYouShown) }
    LaunchedEffect(needsYouShown) {
        if (needsYouShown && !wasShown && atTop) listState.requestScrollToItem(0)
        wasShown = needsYouShown
    }
}
