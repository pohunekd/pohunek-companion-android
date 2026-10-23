package dev.pohunek.companion.core.data.layout

import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.domain.layout.Edit
import dev.pohunek.companion.core.domain.layout.Layout
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.error
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.core.domain.sessions.SessionKey
import dev.pohunek.companion.protocol.HostRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

/** The one owner of the layout: edits are applied to the layout in use, saved when the content changed, and then published in [state]. */
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

    /** Collapses or expands a group; an unknown group changes nothing. */
    suspend fun setGroupCollapsed(id: String, collapsed: Boolean) {
        change { layout ->
            when (val edit = layout.setGroupCollapsed(id, collapsed)) {
                is Edit.Refused -> layout
                is Edit.Done -> {
                    if (edit.value) log.info("layout.group_collapsed", listOf(LogField.groupId(id), LogField.number("collapsed", if (collapsed) 1 else 0)))
                    edit.layout
                }
            }
        }
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

    /** Writes [layout] when it differs from the file and the file may be replaced; a failure is published, never thrown. */
    private suspend fun persist(layout: Layout) {
        if (!writable || layout == saved) return
        val issue = try {
            store.save(layout)
            saved = layout
            log.info("layout.saved", sizeFields(layout))
            null
        } catch (e: LayoutStoreException) {
            log.error("layout.save_failed", listOf(LogField.errorCode("write")))
            LayoutIssue.SaveFailed(e.message.orEmpty())
        }
        val now = current.value as? LayoutState.Available ?: return
        if (now.issue != issue) current.value = now.copy(issue = issue)
    }

    private fun sizeFields(layout: Layout) =
        listOf(LogField.number("groups", layout.groups.size.toLong()), LogField.number("sessions", layout.items.size.toLong()))
}
