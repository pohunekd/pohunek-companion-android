package dev.pohunek.companion.core.data.layout

import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.domain.layout.CreateAndMoveError
import dev.pohunek.companion.core.domain.layout.Edit
import dev.pohunek.companion.core.domain.layout.GroupError
import dev.pohunek.companion.core.domain.layout.GroupFact
import dev.pohunek.companion.core.domain.layout.Layout
import dev.pohunek.companion.core.domain.layout.LayoutView
import dev.pohunek.companion.core.domain.layout.MoveError
import dev.pohunek.companion.core.domain.layout.Placement
import dev.pohunek.companion.core.domain.layout.ProjectSelection
import dev.pohunek.companion.core.domain.layout.RowFacts
import dev.pohunek.companion.core.domain.layout.tappedHost
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.error
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.core.domain.sessions.SessionKey
import dev.pohunek.companion.protocol.HostRoute
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What is wrong with the layout file; the layout in use is then not, or not fully, saved. */
sealed interface LayoutIssue {
    /** The file cannot be read. It is kept as it is, and the app runs with an unsaved Inbox-only layout. */
    data class Unreadable(val reason: String) : LayoutIssue

    /** The last change could not be written; the layout in use holds it and the next change tries again. */
    data class SaveFailed(val reason: String) : LayoutIssue
}

/** The layout as the app holds it. */
sealed interface LayoutState {
    /** The layout file has not been read yet. */
    data object Loading : LayoutState

    data class Available(val layout: Layout, val issue: LayoutIssue?) : LayoutState
}

/** What an owner edit of the layout came to. */
sealed interface LayoutEdit<out V, out E> {
    /** The layout in use holds the change; [LayoutState.Available.issue] says whether it could be saved. */
    data class Applied<out V>(val value: V) : LayoutEdit<V, Nothing>

    /** The layout was left as it was. */
    data class Refused<out E>(val error: E) : LayoutEdit<Nothing, E>

    /** The layout file has not been read yet, so there is nothing to edit. */
    data object Unavailable : LayoutEdit<Nothing, Nothing>
}

/** The one owner of the layout: edits are applied to the layout in use, saved when the content changed, and then published in [state]. */
@Suppress("TooManyFunctions") // One owner of the layout: each owner edit is one entry point that shares the lock and the save.
class LayoutRepository(private val store: LayoutStore, private val log: AppLog) {
    private val mutex = Mutex()
    private val current = MutableStateFlow<LayoutState>(LayoutState.Loading)
    private var saved: Layout? = null
    private var writable = true
    val state: StateFlow<LayoutState> get() = current

    /** Reads the layout file once; later calls return at once. */
    suspend fun load() {
        mutex.withLock {
            if (current.value != LayoutState.Loading) return
            when (val read = store.load()) {
                is LayoutRead.Loaded -> {
                    log.info("layout.loaded", sizeFields(read.layout))
                    saved = read.layout.takeUnless { read.repaired }
                    current.value = LayoutState.Available(read.layout, null)
                    persist(read.layout)
                }
                is LayoutRead.Unreadable -> {
                    log.error("layout.unreadable", listOf(LogField.errorCode("unreadable")))
                    writable = false
                    current.value = LayoutState.Available(Layout.default(), LayoutIssue.Unreadable(read.reason))
                }
            }
        }
    }

    /**
     * Applies the full list [sessionIds] that [route] sent: sessions the layout does not know join Inbox, placements of that host's
     * missing sessions go. Other hosts' placements are untouched, so call it only for a list the host sent while connected.
     */
    suspend fun reconcile(route: HostRoute, sessionIds: List<String>) {
        val host = route.toString()
        change { layout ->
            val result = layout.reconcile(host, sessionIds.map { SessionKey(host, it) })
            if (result.added.isNotEmpty() || result.pruned.isNotEmpty()) {
                val counts = listOf(LogField.number("added", result.added.size.toLong()), LogField.number("pruned", result.pruned.size.toLong()))
                log.info("layout.reconciled", listOf(hostKeyField(route)) + counts)
            }
            result.layout
        }
    }

    /**
     * Places the sessions of [facts] by their group value; see [Layout.applyGroupValues]. Call it after [reconcile] of the same list;
     * [route] is null for a sweep that has no list.
     * Saves only when something changed.
     */
    suspend fun applyGroupValues(route: HostRoute?, facts: List<GroupFact>, allHostsListed: Boolean) {
        change { layout ->
            val result = layout.applyGroupValues(facts, allHostsListed)
            if (result.moved > 0 || result.learnedAdded > 0 || result.learnedDropped > 0) {
                val counts = listOf(
                    LogField.number("moved", result.moved.toLong()),
                    LogField.number("learned_added", result.learnedAdded.toLong()),
                    LogField.number("learned_dropped", result.learnedDropped.toLong()),
                )
                log.info("layout.groups_applied", listOfNotNull(route?.let(::hostKeyField)) + counts)
            }
            result.layout
        }
    }

    /** Selects the view the list shows; the choice is saved with the layout. */
    suspend fun selectView(view: LayoutView) {
        change { layout ->
            if (layout.storedView == view.id) {
                layout
            } else {
                log.info("layout.view_selected", listOf(LogField.state(view.id)))
                layout.withView(view)
            }
        }
    }

    /** Toggles [state] in the selection of the selected view; the choice is saved with the layout. */
    suspend fun toggleState(state: OwnerState) {
        change { layout ->
            val view = layout.view
            val next = layout.states(view).toggled(state)
            log.info("layout.states_changed", listOf(LogField.state(view.id), LogField.number("selected", next.states.size.toLong())))
            layout.withStates(view, next)
        }
    }

    /** Applies a tap on the project chip [chip] to the selected view: selects it, or clears the project when it is already selected. */
    suspend fun tapProject(chip: ProjectSelection) {
        change { layout ->
            val view = layout.view
            val next = layout.project(view).tapped(chip)
            log.info("layout.project_changed", listOf(LogField.state(view.id), LogField.number("selected", if (next == ProjectSelection.All) 0 else 1)))
            layout.withProject(view, next)
        }
    }

    /** Applies a tap on the host [key] (null: All hosts) to the selected view: selects it, or shows every host when it is already selected. */
    suspend fun tapHost(key: String?) {
        change { layout ->
            val view = layout.view
            val next = tappedHost(layout.host(view), key)
            log.info("layout.host_changed", listOf(LogField.state(view.id), LogField.number("selected", if (next == null) 0 else 1)))
            layout.withHost(view, next)
        }
    }

    /**
     * Collapses or expands a section of the selected view: an owner group in My groups, a section of a derived view. A derived section
     * id carries session metadata, so only an owner group's id is logged. An unknown group changes nothing.
     */
    suspend fun setCollapsed(id: String, collapsed: Boolean) {
        change { layout ->
            when (val edit = layout.setCollapsed(id, collapsed)) {
                is Edit.Refused -> layout
                is Edit.Done -> {
                    if (edit.value) {
                        val section = if (layout.view.isEditable) listOf(LogField.groupId(id)) else listOf(LogField.state(layout.view.id))
                        log.info("layout.section_collapsed", section + LogField.number("collapsed", if (collapsed) 1 else 0))
                    }
                    edit.layout
                }
            }
        }
    }

    /** Appends a group named [name] to My groups; the value is its id. */
    suspend fun createGroup(name: String): LayoutEdit<String, GroupError> = edit(
        { it.createGroup(name) },
        { id -> log.info("layout.group_created", listOf(LogField.groupId(id))) },
    )

    /** Appends a group and moves [key] to its end as one change, so a refusal of either leaves the layout as it was. The value is the group's id. */
    suspend fun createGroupAndMove(name: String, key: SessionKey): LayoutEdit<String, CreateAndMoveError> = edit(
        { it.createGroupAndMove(name, key) },
        { id ->
            log.info("layout.group_created", listOf(LogField.groupId(id)))
            log.info("layout.session_moved", listOf(LogField.groupId(id), LogField.sessionId(key.sessionId)))
        },
    )

    /** Renames a group; the value is whether the name changed. */
    suspend fun renameGroup(id: String, name: String): LayoutEdit<Boolean, GroupError> = edit(
        { it.renameGroup(id, name) },
        { changed -> if (changed) log.info("layout.group_renamed", listOf(LogField.groupId(id))) },
    )

    /** Deletes a group; its sessions go to the end of Inbox in their order. The value is the sessions that moved. */
    suspend fun deleteGroup(id: String): LayoutEdit<List<SessionKey>, GroupError> = edit(
        { it.deleteGroup(id) },
        { moved -> log.info("layout.group_deleted", listOf(LogField.groupId(id), LogField.number("sessions", moved.size.toLong()))) },
    )

    /** Moves [key] to the end of [group]; the value is whether anything changed. */
    suspend fun moveSession(key: SessionKey, group: String): LayoutEdit<Boolean, MoveError> = edit(
        { it.moveSession(key, group, Placement.End) },
        { changed -> if (changed) log.info("layout.session_moved", listOf(LogField.groupId(group), LogField.sessionId(key.sessionId))) },
    )

    /** Applies one owner edit under the lock; [logged] runs once the layout in use holds a change. */
    private suspend fun <V, E> edit(apply: (Layout) -> Edit<V, E>, logged: (V) -> Unit): LayoutEdit<V, E> = mutex.withLock {
        val available = current.value as? LayoutState.Available ?: return LayoutEdit.Unavailable
        when (val result = apply(available.layout)) {
            is Edit.Refused -> LayoutEdit.Refused(result.error)
            is Edit.Done -> {
                if (result.layout != available.layout) {
                    current.value = available.copy(layout = result.layout)
                    persist(result.layout)
                }
                logged(result.value)
                LayoutEdit.Applied(result.value)
            }
        }
    }

    /** Drops the collapsed ids of derived-view sections that none of [rows] has; see [Layout.pruneCollapsedSections]. */
    suspend fun <R> pruneCollapsedSections(rows: List<R>, keyOf: (R) -> SessionKey, titleMaxChars: Int, factsOf: (R) -> RowFacts) {
        change { it.pruneCollapsedSections(rows, keyOf, titleMaxChars, factsOf) }
    }

    private suspend fun change(edit: (Layout) -> Layout) {
        mutex.withLock {
            val available = current.value as? LayoutState.Available ?: return
            val next = edit(available.layout)
            if (next == available.layout) return
            current.value = available.copy(layout = next)
            persist(next)
        }
    }

    /**
     * Writes [layout] when it differs from the file and the file may be replaced; a failure is published, never thrown. The write
     * is not cancelled with the caller, so memory and file never end up apart without an issue saying so.
     */
    private suspend fun persist(layout: Layout) {
        if (!writable) return
        val issue = if (layout == saved) null else withContext(NonCancellable) { save(layout) }
        val now = current.value as? LayoutState.Available ?: return
        if (now.issue != issue) current.value = now.copy(issue = issue)
    }

    private suspend fun save(layout: Layout): LayoutIssue? = try {
        store.save(layout)
        saved = layout
        log.info("layout.saved", sizeFields(layout))
        null
    } catch (e: LayoutStoreException) {
        log.error("layout.save_failed", listOf(LogField.errorCode("write")))
        LayoutIssue.SaveFailed(e.message.orEmpty())
    }

    private fun sizeFields(layout: Layout) =
        listOf(LogField.number("groups", layout.groups.size.toLong()), LogField.number("sessions", layout.items.size.toLong()))
}
