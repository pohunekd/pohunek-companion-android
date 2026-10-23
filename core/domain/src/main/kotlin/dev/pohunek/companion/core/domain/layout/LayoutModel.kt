// Ported to Kotlin from pohunek-companion `crates/core/src/layout.rs`.
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.SessionKey

/** Id of the group that receives sessions without a place. */
const val INBOX_ID = "inbox"

/** Display name of the Inbox group. */
const val INBOX_NAME = "Inbox"

/** A group of sessions. */
data class Group(
    /** Stable identity; the name can change without touching items. */
    val id: String,
    val name: String,
    /** Fractional sort key among groups. */
    val order: String,
    /** The header shows with the session count and the rows are hidden. */
    val collapsed: Boolean = false,
)

/** A session's place: its group and its sort key within that group. */
data class Item(
    val key: SessionKey,
    val group: String,
    val order: String,
    /** Set once the owner has moved the session, so placement rules leave it where the owner put it. */
    val manual: Boolean = false,
)

/** The views the layout can select; the stored id of an unknown view falls back to [MY_GROUPS]. */
enum class LayoutView(val id: String) {
    MY_GROUPS("my-groups"),
    BY_STATE("by-state"),
    BY_PROJECT("by-project"),
    BY_WORK_ITEM("by-work-item"),
    ;

    /** Whether the owner can rearrange sessions in this view. */
    val isEditable: Boolean get() = this == MY_GROUPS

    companion object {
        fun parse(id: String): LayoutView? = entries.firstOrNull { it.id == id }
    }
}

/** The filter stored for one view; the all-null default is stored as no entry. */
data class StoredFilter(
    /** State keys; null selects the default states. */
    val states: List<String>? = null,
    /** Label of the selected project, matched on every host. */
    val projectLabel: String? = null,
    /** Sessions without a project; ignored when [projectLabel] is set. */
    val noProject: Boolean = false,
    /** Key of the selected host; null selects every host. */
    val host: String? = null,
)

/** One group with its rows in display order. */
data class GroupSection<R>(val id: String, val name: String, val collapsed: Boolean, val rows: List<R>)

/** Where a moved session lands in its target group. */
sealed interface Placement {
    data class Before(val anchor: SessionKey) : Placement

    data class After(val anchor: SessionKey) : Placement

    /** After the last session; the only placement into an empty group. */
    data object End : Placement
}

/** Where a moved group lands among the others. */
sealed interface GroupPlacement {
    val anchorId: String

    data class Before(override val anchorId: String) : GroupPlacement

    data class After(override val anchorId: String) : GroupPlacement
}

/** What a reconcile changed. */
data class Reconciliation(val layout: Layout, val added: List<SessionKey>, val pruned: List<SessionKey>)

/** The result of building a layout from stored data. */
sealed interface LayoutLoad {
    /** [repaired] is true when the stored data differs from [layout] (a missing Inbox was re-added), so it needs saving. */
    data class Loaded(val layout: Layout, val repaired: Boolean) : LayoutLoad

    data class Invalid(val message: String) : LayoutLoad
}
