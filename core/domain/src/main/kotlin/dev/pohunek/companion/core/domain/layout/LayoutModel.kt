// Ported to Kotlin from pohunek-companion `crates/core/src/layout.rs`.
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.SessionKey

/** Id of the group that receives sessions without a place. */
const val INBOX_ID = "inbox"

/** Display name of the Inbox group. */
const val INBOX_NAME = "Inbox"

/** Who made a group: the owner on this phone, or a session's `companion.group` value that named a group the phone did not have. */
enum class GroupOrigin { Created, Learned }

/** A group of sessions. */
data class Group(
    /** Stable identity; the name can change without touching items. */
    val id: String,
    val name: String,
    /** Fractional sort key among groups. */
    val order: String,
    /** The header shows with the session count and the rows are hidden. */
    val collapsed: Boolean = false,
    /** A learned group goes away once no session is in it; a created group stays until the owner deletes it. */
    val origin: GroupOrigin = GroupOrigin.Created,
)

/** A session's place: its group and its sort key within that group. */
data class Item(
    val key: SessionKey,
    val group: String,
    val order: String,
    /** Set while the owner's local move, not the session's `companion.group` key, is what places the session. */
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

/** What one session says about its group, as read from its metadata. [external] sessions cannot carry the key. */
data class GroupFact(val key: SessionKey, val value: GroupValue, val external: Boolean)

/** One `companion.group` value to write to a session. */
data class GroupWrite(val key: SessionKey, val name: String)

/** What moving a host's manual placements into metadata comes to: the [writes] to make and the sessions whose group name cannot be written. */
data class GroupMigration(val writes: List<GroupWrite>, val unshared: List<SessionKey>)

/** A session a group rename or delete has to write; an [external] one is never sent a write. */
data class GroupMember(val key: SessionKey, val external: Boolean)

/** What applying group values changed: sessions moved to another group, and learned groups added and dropped. */
data class GroupSync(val layout: Layout, val moved: Int, val learnedAdded: Int, val learnedDropped: Int)

/** What a reconcile changed. */
data class Reconciliation(val layout: Layout, val added: List<SessionKey>, val pruned: List<SessionKey>)

/** The result of building a layout from stored data. */
sealed interface LayoutLoad {
    /** [repaired] is true when the stored data differs from [layout] (a missing Inbox was re-added), so it needs saving. */
    data class Loaded(val layout: Layout, val repaired: Boolean) : LayoutLoad

    data class Invalid(val message: String) : LayoutLoad
}
