package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.SessionKey

/** One drawn entry of My groups with its vertical extent in the coordinates of the list; entries are given top to bottom. */
sealed interface DropEntry {
    val groupId: String
    val top: Float
    val bottom: Float

    data class Header(override val groupId: String, override val top: Float, override val bottom: Float) : DropEntry

    data class Row(val key: SessionKey, override val groupId: String, override val top: Float, override val bottom: Float) : DropEntry
}

/** What a drop indicator marks: a line between two entries, or the extent of a group header (the session joins the end of that group). */
sealed interface DropIndicator {
    data class Line(val y: Float) : DropIndicator

    data class Span(val top: Float, val bottom: Float) : DropIndicator
}

/** Where a dragged session would land, and what to draw there. */
data class SessionDrop(val groupId: String, val placement: Placement, val indicator: DropIndicator)

/** Where a dragged group would land, and what to draw there. */
data class GroupDrop(val placement: GroupPlacement, val indicator: DropIndicator)

/**
 * The drop of the session [dragged] with the finger at [y]: the top half of a row puts it before that row, the bottom half after it, and a
 * group header puts it at the end of that group, which is the only way into a collapsed or empty group. Null over the dragged row itself
 * and over anything that is not an entry (Needs you, the New group button, the gaps).
 */
fun sessionDropAt(entries: List<DropEntry>, dragged: SessionKey, y: Float): SessionDrop? {
    val entry = entries.firstOrNull { y >= it.top && y < it.bottom } ?: return null
    return when {
        entry is DropEntry.Header -> SessionDrop(entry.groupId, Placement.End, DropIndicator.Span(entry.top, entry.bottom))
        entry is DropEntry.Row && entry.key == dragged -> null
        entry is DropEntry.Row && y < (entry.top + entry.bottom) / 2 -> SessionDrop(entry.groupId, Placement.Before(entry.key), DropIndicator.Line(entry.top))
        entry is DropEntry.Row -> SessionDrop(entry.groupId, Placement.After(entry.key), DropIndicator.Line(entry.bottom))
        else -> null
    }
}

/**
 * The drop of the group [dragged] with the finger at [y]: the top half of a header puts it before that group, the bottom half of a header
 * and the rows of a group after it. The line of an "after" drop sits below the last entry of that group. Null over the dragged group
 * itself and over anything that is not an entry.
 */
fun groupDropAt(entries: List<DropEntry>, dragged: String, y: Float): GroupDrop? {
    val entry = entries.firstOrNull { y >= it.top && y < it.bottom } ?: return null
    val group = entry.groupId
    if (group == dragged) return null
    val before = entry is DropEntry.Header && y < (entry.top + entry.bottom) / 2
    return if (before) {
        GroupDrop(GroupPlacement.Before(group), DropIndicator.Line(entry.top))
    } else {
        GroupDrop(GroupPlacement.After(group), DropIndicator.Line(entries.last { it.groupId == group }.bottom))
    }
}

/** Where "Move up" or "Move down" puts a session. */
data class SessionStep(val groupId: String, val placement: Placement)

/**
 * The step of [key] one place up in [sections], the groups as shown: before the row above it, or at the end of the group above when it
 * is the first of its group. Null when it is the first of the first group, or is not shown.
 */
fun stepUp(sections: List<GroupSection<SessionKey>>, key: SessionKey): SessionStep? {
    val at = sections.indexOfFirst { section -> section.rows.any { it == key } }
    if (at < 0) return null
    val rows = sections[at].rows
    val index = rows.indexOf(key)
    return when {
        index > 0 -> SessionStep(sections[at].id, Placement.Before(rows[index - 1]))
        at > 0 -> SessionStep(sections[at - 1].id, Placement.End)
        else -> null
    }
}

/**
 * The step of [key] one place down in [sections]: after the row below it, or before the first row of the group below (at its end when
 * that is empty) when it is the last of its group. Null when it is the last of the last group, or is not shown.
 */
fun stepDown(sections: List<GroupSection<SessionKey>>, key: SessionKey): SessionStep? {
    val at = sections.indexOfFirst { section -> section.rows.any { it == key } }
    if (at < 0) return null
    val rows = sections[at].rows
    val index = rows.indexOf(key)
    val next = sections.getOrNull(at + 1)
    return when {
        index < rows.lastIndex -> SessionStep(sections[at].id, Placement.After(rows[index + 1]))
        next == null -> null
        else -> SessionStep(next.id, next.rows.firstOrNull()?.let { Placement.Before(it) } ?: Placement.End)
    }
}

/** The placement of group [id] one place up among [ids], the groups in order: before the group above it. Null for the first. */
fun groupStepUp(ids: List<String>, id: String): GroupPlacement? =
    ids.indexOf(id).takeIf { it > 0 }?.let { GroupPlacement.Before(ids[it - 1]) }

/** The placement of group [id] one place down among [ids]: after the group below it. Null for the last. */
fun groupStepDown(ids: List<String>, id: String): GroupPlacement? =
    ids.indexOf(id).takeIf { it in 0 until ids.lastIndex }?.let { GroupPlacement.After(ids[it + 1]) }
