package dev.pohunek.companion.sessions

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex

// Screen reader order: top bar, list, new session button, filter bar.
internal const val TOP_BAR_ORDER = 0f
internal const val LIST_ORDER = 1f
internal const val FAB_ORDER = 2f
internal const val FILTER_BAR_ORDER = 3f

internal fun Modifier.traversalGroup(index: Float): Modifier = semantics {
    isTraversalGroup = true
    traversalIndex = index
}
