// Ported to Kotlin from pohunek-companion `crates/core/src/view.rs` (by_state, by_project, by_work_item, by_key).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.core.domain.sessions.clipDisplay

/** Id of the trailing section of sessions without a project. */
const val NO_PROJECT_ID = "no-project"
private const val NO_PROJECT_NAME = "No project"

/** Id of the trailing section of sessions without a Work link. */
const val NO_WORK_ID = "no-work-item"
private const val NO_WORK_NAME = "No Work item"

/** Id of the trailing By state section of sessions that wait for the owner on a host that is not connected. */
const val WAITING_UNCONFIRMED_ID = "waiting-unconfirmed"
private const val WAITING_UNCONFIRMED_NAME = "Waiting (host not connected)"

/** Joins the parts of a section id; sanitized metadata holds no control characters, so no part can contain it. */
private const val ID_SEPARATOR = '\u001f'

/** The Pohunek Work item a session is linked to; the role is not part of its identity. */
data class WorkItem(
    val provider: String?,
    val kind: String?,
    val id: String,
    /** The badge text such as `GH PR #12`, not yet clipped. */
    val title: String,
)

/** What the derived views read of a row. */
data class RowFacts(val ownerState: OwnerState, val project: String?, val workItem: WorkItem?)

/** The By state sections after the ones that need the owner, in display order. */
private val STATE_SECTIONS = listOf(
    Triple(OwnerState.Working, "working", "Working"),
    Triple(OwnerState.Idle, "idle", "Idle"),
    Triple(OwnerState.RuntimeProblem, "runtime-problem", "Runtime problem"),
    Triple(OwnerState.Ended, "ended", "Ended"),
    Triple(OwnerState.NoAgentState, "no-agent-state", "No agent state"),
)

/**
 * The sections [view] shows for the owner's [arranged] groups. My groups shows them as they are; the other views regroup the same
 * rows and keep their My groups order inside a section. The caller lifts the sessions that need the owner out of [arranged] first,
 * for every view alike. Every row lands in exactly one section.
 */
fun <R> deriveSections(
    view: LayoutView,
    arranged: List<GroupSection<R>>,
    titleMaxChars: Int,
    factsOf: (R) -> RowFacts,
): List<GroupSection<R>> = when (view) {
    LayoutView.MY_GROUPS -> arranged
    LayoutView.BY_STATE -> byState(arranged.flatMap { it.rows }, factsOf)
    LayoutView.BY_PROJECT -> byKey(arranged.flatMap { it.rows }, titleMaxChars, NO_PROJECT_ID to NO_PROJECT_NAME) { row ->
        factsOf(row).project?.let { "project$ID_SEPARATOR$it" to it }
    }
    LayoutView.BY_WORK_ITEM -> byKey(arranged.flatMap { it.rows }, titleMaxChars, NO_WORK_ID to NO_WORK_NAME) { row ->
        factsOf(row).workItem?.let { work ->
            "work$ID_SEPARATOR${work.provider.orEmpty()}$ID_SEPARATOR${work.kind.orEmpty()}$ID_SEPARATOR${work.id}" to work.title
        }
    }
}

private fun <R> byState(rows: List<R>, factsOf: (R) -> RowFacts): List<GroupSection<R>> {
    val named = STATE_SECTIONS.map { (state, id, name) -> Triple(id, name, rows.filter { factsOf(it).ownerState == state }) }
    // A session that needs the owner is lifted only on a connected host; one left behind is shown rather than dropped.
    val unconfirmed = Triple(WAITING_UNCONFIRMED_ID, WAITING_UNCONFIRMED_NAME, rows.filter { factsOf(it).ownerState == OwnerState.NeedsOwner })
    return (named + unconfirmed).filter { it.third.isNotEmpty() }.map { (id, name, sectionRows) -> GroupSection(id, name, false, sectionRows) }
}

/**
 * Buckets [rows] by the `(section id, title)` that [key] gives; rows it gives null for go to the trailing [none] section. Sections
 * are ordered by lowercased title, then title, then id; the title shown is clipped to [titleMaxChars].
 */
private fun <R> byKey(rows: List<R>, titleMaxChars: Int, none: Pair<String, String>, key: (R) -> Pair<String, String>?): List<GroupSection<R>> {
    val keyed = LinkedHashMap<String, Pair<String, MutableList<R>>>()
    val unkeyed = mutableListOf<R>()
    for (row in rows) {
        val entry = key(row)
        if (entry == null) unkeyed += row else keyed.getOrPut(entry.first) { entry.second to mutableListOf() }.second += row
    }
    val sections = keyed.entries
        .sortedWith(compareBy<Map.Entry<String, Pair<String, List<R>>>>({ it.value.first.lowercase() }, { it.value.first }, { it.key }))
        .map { (id, titled) -> GroupSection(id, clipDisplay(titled.first, titleMaxChars), false, titled.second) }
    return if (unkeyed.isEmpty()) sections else sections + GroupSection(none.first, none.second, false, unkeyed)
}
