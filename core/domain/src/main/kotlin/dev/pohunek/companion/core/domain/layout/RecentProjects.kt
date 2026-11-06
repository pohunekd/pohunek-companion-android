package dev.pohunek.companion.core.domain.layout

/** The project labels each view's owner picked last, newest first. Phone-only state: it is never shared with the desktop. */
data class RecentProjects(val views: Map<String, List<String>> = emptyMap()) {
    /**
     * The lists after picking [label] in view [viewId]: it moves to the front of that view's list, any later copy of the exact label goes,
     * and every list is cut to [size], so a lowered size shrinks the stored lists too.
     */
    fun picked(viewId: String, label: String, size: Int): RecentProjects {
        val updated = views + (viewId to (listOf(label) + views[viewId].orEmpty().filter { it != label }))
        return RecentProjects(updated.mapValues { it.value.take(size) }.filterValues { it.isNotEmpty() })
    }

    /** The labels of view [viewId] to list as recent: the first [size] stored that some project row in [chips] still has. */
    fun shown(viewId: String, size: Int, chips: List<ProjectChip>): List<String> {
        val available = chips.mapNotNullTo(HashSet()) { it.label }
        return views[viewId].orEmpty().take(size).filter { it in available }
    }
}

/** The project picker rows of the recent [labels], in that order; a label without a row in [rows] is skipped. */
fun recentPickerRows(rows: List<ProjectPickerRow>, labels: List<String>): List<ProjectPickerRow> =
    labels.mapNotNull { label -> rows.firstOrNull { it.label == label && it.selection is ProjectSelection.Project } }
