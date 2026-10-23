package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.core.domain.sessions.SessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private companion object {
        const val MAX_NAME = 64
    }

    private fun key(id: String, host: String = "local") = SessionKey(host, id)

    private fun keys(vararg ids: String, host: String = "local") = ids.map { key(it, host) }

    private fun ids(items: List<Item>) = items.map { it.key.sessionId }

    private fun order(layout: Layout, group: String) = ids(layout.groupItems(group))

    private fun groupIds(layout: Layout) = layout.orderedGroups().map { it.id }

    private fun <V, E> Edit<V, E>.done(): Edit.Done<V> = this as? Edit.Done ?: error("refused: $this")

    private fun <V, E> Edit<V, E>.refusal(): E = (this as? Edit.Refused)?.error ?: error("not refused: $this")

    private fun Layout.reconciled(host: String, vararg ids: String) = reconcile(host, keys(*ids, host = host)).layout

    /** Inbox "g", Work "n", Empty "t", with the given sessions reconciled into Inbox. */
    private fun arranged(vararg ids: String): Layout {
        val groups = listOf(
            Group(INBOX_ID, INBOX_NAME, "g"),
            Group("work", "Work", "n"),
            Group("empty", "Empty", "t"),
        )
        val loaded = Layout.load(Layout.FORMAT_VERSION, null, emptyMap(), emptyMap(), groups, emptyList(), emptyList()) as LayoutLoad.Loaded
        return loaded.layout.reconciled("local", *ids)
    }

    private fun Layout.moved(id: String, group: String, placement: Placement): Layout {
        val done = moveSession(key(id), group, placement, manual = true).done()
        assertTrue(done.value)
        return done.layout
    }

    @Test
    fun `reconcile appends unknown sessions in list order and prunes missing ones`() {
        val first = Layout.default().reconcile("local", keys("s1", "s2", "s3"))
        assertEquals(keys("s1", "s2", "s3"), first.added)
        assertEquals(listOf("s1", "s2", "s3"), order(first.layout, INBOX_ID))

        val second = first.layout.reconcile("local", keys("s3", "s1", "s4", "s5"))
        assertEquals(keys("s2"), second.pruned)
        assertEquals(keys("s4", "s5"), second.added)
        assertEquals(listOf("s1", "s3", "s4", "s5"), order(second.layout, INBOX_ID))
    }

    @Test
    fun `a host seed prunes only that hosts items`() {
        var layout = Layout.default().reconciled("host-a", "a1", "a2").reconciled("host-b", "b1", "b2")
        assertEquals(listOf("a1", "a2", "b1", "b2"), order(layout, INBOX_ID))

        val seeded = layout.reconcile("host-a", keys("a1", "a3", host = "host-a"))
        assertEquals(keys("a2", host = "host-a"), seeded.pruned)
        assertEquals(keys("a3", host = "host-a"), seeded.added)
        layout = seeded.layout
        assertEquals(listOf("a1", "b1", "b2", "a3"), order(layout, INBOX_ID))

        layout = layout.reconcile("host-b", emptyList()).layout
        assertEquals(listOf("a1", "a3"), order(layout, INBOX_ID))

        val unseeded = layout.reconcile("host-c", emptyList())
        assertTrue(unseeded.pruned.isEmpty() && unseeded.added.isEmpty())
        assertEquals(layout, unseeded.layout)
    }

    @Test
    fun `a session listed twice is added once`() {
        val result = Layout.default().reconcile("local", keys("s1", "s1"))
        assertEquals(keys("s1"), result.added)
    }

    @Test
    fun `a move before after or to the end orders within and across groups`() {
        var layout = arranged("a", "b", "c", "d")
        layout = layout.moved("d", INBOX_ID, Placement.Before(key("a")))
        assertEquals(listOf("d", "a", "b", "c"), order(layout, INBOX_ID))
        layout = layout.moved("d", INBOX_ID, Placement.After(key("b")))
        assertEquals(listOf("a", "b", "d", "c"), order(layout, INBOX_ID))
        layout = layout.moved("a", INBOX_ID, Placement.End)
        assertEquals(listOf("b", "d", "c", "a"), order(layout, INBOX_ID))

        layout = layout.moved("b", "empty", Placement.End)
        assertEquals(listOf("b"), order(layout, "empty"))
        layout = layout.moved("c", "work", Placement.End)
        layout = layout.moved("a", "work", Placement.Before(key("c")))
        layout = layout.moved("d", "work", Placement.After(key("a")))
        assertEquals(listOf("a", "d", "c"), order(layout, "work"))
        assertTrue(order(layout, INBOX_ID).isEmpty())
    }

    @Test
    fun `a move changes one item and marks it manual`() {
        val before = arranged("a", "b", "c")
        val after = before.moved("c", "work", Placement.End)
        for ((old, new) in before.items.zip(after.items)) {
            if (old.key.sessionId == "c") {
                assertEquals("work" to true, new.group to new.manual)
            } else {
                assertEquals(old, new)
            }
        }
        assertEquals(before.groups, after.groups)
    }

    @Test
    fun `dropping a moved session where it is or on itself only pins it`() {
        val plain = arranged("a", "b", "c")
        val onItself = plain.moveSession(key("b"), INBOX_ID, Placement.Before(key("b")), manual = true).done()
        assertTrue(onItself.value)
        assertEquals(plain.items.map { it.copy(manual = it.key.sessionId == "b") }, onItself.layout.items)

        val settled = plain.moved("b", INBOX_ID, Placement.After(key("a")))
        for (placement in listOf(Placement.After(key("a")), Placement.Before(key("c")), Placement.Before(key("b")))) {
            val done = settled.moveSession(key("b"), INBOX_ID, placement, manual = true).done()
            assertFalse(done.value)
            assertEquals(settled, done.layout)
        }
    }

    @Test
    fun `a move between neighbours with equal keys is refused`() {
        val layout = arranged("a", "b", "c")
        val tied = layout.withItems(layout.items.mapIndexed { i, item -> if (i == 1) item.copy(order = layout.items[0].order) else item })
        assertEquals(MoveError.AmbiguousOrder(INBOX_ID), tied.moveSession(key("c"), INBOX_ID, Placement.Before(key("b")), manual = true).refusal())
    }

    @Test
    fun `a refused move leaves the layout unchanged`() {
        val layout = arranged("a", "b")
        assertEquals(MoveError.UnknownSession("zz"), layout.moveSession(key("zz"), INBOX_ID, Placement.End, manual = true).refusal())
        assertEquals(MoveError.UnknownGroup("nope"), layout.moveSession(key("a"), "nope", Placement.End, manual = true).refusal())
        assertEquals(MoveError.AnchorNotInGroup("b"), layout.moveSession(key("a"), "work", Placement.Before(key("b")), manual = true).refusal())
        assertEquals(arranged("a", "b"), layout)
    }

    @Test
    fun `deleting a group appends its sessions to Inbox in order keeping the manual flag`() {
        var layout = arranged("a", "b", "c", "d")
        for (id in listOf("c", "a", "d")) layout = layout.moved(id, "work", Placement.End)
        assertEquals(listOf("c", "a", "d"), order(layout, "work"))

        val deleted = layout.deleteGroup("work").done()
        assertEquals(keys("c", "a", "d"), deleted.value)
        assertEquals(listOf("b", "c", "a", "d"), order(deleted.layout, INBOX_ID))
        assertEquals(listOf(INBOX_ID, "empty"), groupIds(deleted.layout))
        assertEquals(3, deleted.layout.items.count { it.manual })
    }

    @Test
    fun `Inbox cannot be renamed or deleted and an unknown group is refused`() {
        val layout = arranged("a")
        assertEquals(GroupError.InboxFixed, layout.deleteGroup(INBOX_ID).refusal())
        assertEquals(GroupError.InboxFixed, layout.renameGroup(INBOX_ID, "Main", MAX_NAME).refusal())
        assertEquals(GroupError.UnknownGroup("nope"), layout.deleteGroup("nope").refusal())
        assertEquals(GroupError.UnknownGroup("nope"), layout.renameGroup("nope", "X", MAX_NAME).refusal())
    }

    @Test
    fun `group names are trimmed non-empty and unique ignoring case`() {
        val layout = arranged()
        assertEquals(GroupError.EmptyName, layout.createGroup("  \t ", MAX_NAME).refusal())
        assertEquals(GroupError.DuplicateName("WORK"), layout.createGroup("WORK", MAX_NAME).refusal())
        assertEquals(GroupError.DuplicateName("inbox"), layout.createGroup(" inbox ", MAX_NAME).refusal())
        assertEquals(GroupError.DuplicateName("work"), layout.renameGroup("empty", "work", MAX_NAME).refusal())
        assertEquals(GroupError.EmptyName, layout.renameGroup("empty", " ", MAX_NAME).refusal())

        val created = layout.createGroup("  Review ", MAX_NAME).done()
        assertEquals(listOf(INBOX_ID, "work", "empty", created.value), groupIds(created.layout))
        assertEquals("Review", created.layout.groups.last().name)
        val upper = created.layout.renameGroup(created.value, "REVIEW", MAX_NAME).done()
        assertTrue(upper.value)
        assertFalse(upper.layout.renameGroup(created.value, "REVIEW", MAX_NAME).done().value)
    }

    @Test
    fun `new group ids are g followed by a number and skip ids in use`() {
        val one = Layout.default().createGroup("One", MAX_NAME).done()
        assertEquals("g1", one.value)
        val two = one.layout.createGroup("Two", MAX_NAME).done()
        assertEquals("g2", two.value)
        val afterDelete = two.layout.deleteGroup("g1").done().layout
        assertEquals("g3", afterDelete.createGroup("Three", MAX_NAME).done().value)
    }

    @Test
    fun `moving a group changes only its key and a no-op move changes nothing`() {
        val layout = arranged()
        for (placement in listOf(GroupPlacement.After(INBOX_ID), GroupPlacement.Before("empty"), GroupPlacement.Before("work"))) {
            val done = layout.moveGroup("work", placement).done()
            assertFalse(done.value)
            assertEquals(layout, done.layout)
        }
        val moved = layout.moveGroup("empty", GroupPlacement.Before(INBOX_ID)).done()
        assertTrue(moved.value)
        assertEquals(listOf("empty", INBOX_ID, "work"), groupIds(moved.layout))
        assertEquals(layout.groups.filter { it.id != "empty" }, moved.layout.groups.filter { it.id != "empty" })
        assertEquals(GroupError.UnknownGroup("nope"), layout.moveGroup("work", GroupPlacement.Before("nope")).refusal())
        assertEquals(GroupError.UnknownGroup("nope"), layout.moveGroup("nope", GroupPlacement.Before("work")).refusal())

        val tied = (
            Layout.load(
                1, null, emptyMap(), emptyMap(),
                layout.groups.map {
                    it.copy(order = if (it.id == "work") "g" else it.order)
                },
                emptyList(),
                emptyList(),
            ) as LayoutLoad.Loaded
            ).layout
        assertEquals(GroupError.AmbiguousOrder, tied.moveGroup("empty", GroupPlacement.After(INBOX_ID)).refusal())
    }

    @Test
    fun `collapsing applies to groups in My groups and to sections in a derived view`() {
        val layout = arranged()
        val collapsed = layout.setCollapsed("work", true).done()
        assertTrue(collapsed.value)
        assertTrue(collapsed.layout.groups.single { it.id == "work" }.collapsed)
        assertFalse(collapsed.layout.setCollapsed("work", true).done().value)
        assertEquals(GroupError.UnknownGroup("nope"), layout.setCollapsed("nope", true).refusal())

        val byState = layout.withView(LayoutView.BY_STATE)
        val section = byState.setCollapsed("working", true).done()
        assertTrue(section.value)
        assertEquals(mapOf("by-state" to setOf("working")), section.layout.collapsedSections)
        assertEquals(layout.groups, section.layout.groups)
        val expanded = section.layout.setCollapsed("working", false).done()
        assertTrue(expanded.value)
        assertTrue(expanded.layout.collapsedSections.isEmpty())
    }

    @Test
    fun `arrange lists groups in order with their rows and leaves out unplaced rows`() {
        val layout = arranged("a", "b", "c").moved("c", "work", Placement.End)
        val sections = layout.arrange(listOf("x", "c", "b", "a")) { key(it) }
        assertEquals(listOf(INBOX_ID, "work", "empty"), sections.map { it.id })
        assertEquals(listOf(listOf("a", "b"), listOf("c"), emptyList()), sections.map { it.rows })
    }

    @Test
    fun `present shows rows without a placement at the end of Inbox in list order`() {
        val layout = arranged("a", "b", "c").moved("c", "work", Placement.End)
        val sections = layout.present(listOf("x", "c", "b", "y", "a")) { key(it) }
        assertEquals(listOf(listOf("a", "b", "x", "y"), listOf("c"), emptyList()), sections.map { it.rows })
        assertEquals(layout.arrange(listOf("c", "b", "a")) { key(it) }, layout.present(listOf("c", "b", "a")) { key(it) })
    }

    @Test
    fun `a group collapses in every view and leaves the derived sections alone`() {
        val derived = arranged().withView(LayoutView.BY_PROJECT)
        val collapsed = derived.setGroupCollapsed("work", true).done()
        assertTrue(collapsed.value)
        assertTrue(collapsed.layout.groups.single { it.id == "work" }.collapsed)
        assertTrue(collapsed.layout.collapsedSections.isEmpty())
        assertEquals(LayoutView.BY_PROJECT, collapsed.layout.view)
        assertEquals(GroupError.UnknownGroup("nope"), derived.setGroupCollapsed("nope", true).refusal())
    }

    @Test
    fun `view and filters fall back and persist per view`() {
        val layout = Layout.default()
        assertEquals(LayoutView.MY_GROUPS, layout.view)
        assertEquals(LayoutView.BY_PROJECT, layout.withView(LayoutView.BY_PROJECT).view)

        val unknown = (Layout.load(1, "by-herd", emptyMap(), emptyMap(), layout.groups, emptyList(), emptyList()) as LayoutLoad.Loaded).layout
        assertEquals(LayoutView.MY_GROUPS, unknown.view)
        assertEquals("by-herd", unknown.storedView)

        val filter = StoredFilter(states = listOf("working"), host = "h")
        val filtered = layout.withFilter(LayoutView.BY_STATE, filter)
        assertEquals(filter, filtered.filter(LayoutView.BY_STATE))
        assertEquals(StoredFilter(), filtered.filter(LayoutView.BY_PROJECT))
        assertTrue(filtered.withFilter(LayoutView.BY_STATE, StoredFilter()).filters.isEmpty())
    }

    @Test
    fun `a state selection is stored per view keeping the rest of the filter and Problem is re-added on load`() {
        val withEnded = StateSelection.DEFAULT.toggled(OwnerState.Ended)
        val stored = Layout.default().withFilter(LayoutView.BY_STATE, StoredFilter(host = "h")).withStates(LayoutView.BY_STATE, withEnded)
        assertEquals("h", stored.filter(LayoutView.BY_STATE).host)
        assertEquals(withEnded, stored.states(LayoutView.BY_STATE))
        assertEquals(StateSelection.DEFAULT, stored.states(LayoutView.BY_PROJECT))
        assertEquals(StoredFilter(host = "h"), stored.withStates(LayoutView.BY_STATE, StateSelection.DEFAULT).filter(LayoutView.BY_STATE))

        val file = mapOf("by-state" to StoredFilter(states = listOf("working", "ended")), "by-project" to StoredFilter(states = listOf("future")))
        val loaded = (Layout.load(1, null, file, emptyMap(), listOf(inbox), emptyList(), emptyList()) as LayoutLoad.Loaded).layout
        assertEquals(listOf("working", "runtime-problem", "ended"), loaded.filter(LayoutView.BY_STATE).states)
        assertEquals(StoredFilter(), loaded.filter(LayoutView.BY_PROJECT))
    }

    @Test
    fun `project and host are stored per view keeping the rest of the filter`() {
        val layout = Layout.default().withStates(LayoutView.BY_STATE, StateSelection.DEFAULT.toggled(OwnerState.Ended))
        val project = layout.withProject(LayoutView.BY_STATE, ProjectSelection.Project("ripgrep")).withHost(LayoutView.BY_STATE, "h")
        assertEquals(ProjectSelection.Project("ripgrep"), project.project(LayoutView.BY_STATE))
        assertEquals("h", project.host(LayoutView.BY_STATE))
        assertEquals(ProjectSelection.All, project.project(LayoutView.BY_PROJECT))
        assertNull(project.host(LayoutView.BY_PROJECT))
        assertEquals(StateSelection.DEFAULT.toggled(OwnerState.Ended), project.states(LayoutView.BY_STATE))

        val none = project.withProject(LayoutView.BY_STATE, ProjectSelection.NoProject)
        assertTrue(none.filter(LayoutView.BY_STATE).noProject)
        assertNull(none.filter(LayoutView.BY_STATE).projectLabel)
        // A project label wins over the flag, and the default filter is stored as no entry.
        val both = Layout.default().withFilter(LayoutView.BY_STATE, StoredFilter(projectLabel = "x", noProject = true))
        assertEquals(ProjectSelection.Project("x"), both.project(LayoutView.BY_STATE))
        val cleared = none.withHost(LayoutView.BY_STATE, null).withStates(LayoutView.BY_STATE, StateSelection.DEFAULT)
            .withProject(LayoutView.BY_STATE, ProjectSelection.All)
        assertTrue(cleared.filters.isEmpty())
        assertNull(Layout.default().withFilter(LayoutView.BY_STATE, StoredFilter(host = "")).host(LayoutView.BY_STATE))
    }

    private fun loading(groups: List<Group>, items: List<Item> = emptyList(), version: Int = 1) =
        Layout.load(version, null, emptyMap(), emptyMap(), groups, items, emptyList())

    private val inbox = Group(INBOX_ID, INBOX_NAME, "n")

    private fun Layout.withItems(items: List<Item>): Layout =
        (Layout.load(Layout.FORMAT_VERSION, storedView, filters, collapsedSections, groups, items, migratedHosts) as LayoutLoad.Loaded).layout

    private fun invalid(load: LayoutLoad) = (load as LayoutLoad.Invalid).message

    @Test
    fun `loading rejects inconsistent data with a message`() {
        assertTrue(invalid(loading(listOf(inbox), version = 3)).contains("unsupported format version 3"))
        assertTrue(invalid(loading(listOf(inbox), version = 0)).contains("unsupported format version 0"))
        assertTrue(invalid(loading(listOf(inbox, inbox.copy(order = "p")))).contains("empty or repeated"))
        assertTrue(invalid(loading(listOf(inbox.copy(id = "")))).contains("empty or repeated"))
        assertTrue(invalid(loading(listOf(inbox.copy(order = "na")))).contains("invalid order key"))
        assertTrue(invalid(loading(listOf(inbox.copy(order = "")))).contains("invalid order key"))
        val item = Item(key("s1"), INBOX_ID, "n")
        assertTrue(invalid(loading(listOf(inbox), listOf(item.copy(group = "nope")))).contains("unknown group"))
        assertTrue(invalid(loading(listOf(inbox), listOf(item.copy(order = "A")))).contains("invalid order key"))
        assertTrue(invalid(loading(listOf(inbox), listOf(item, item.copy(order = "p")))).contains("listed twice"))
    }

    @Test
    fun `a loaded layout does not follow later changes to the lists it was built from`() {
        val items = mutableListOf<Item>()
        val loaded = (Layout.load(1, null, emptyMap(), emptyMap(), listOf(inbox), items, emptyList()) as LayoutLoad.Loaded).layout
        items += Item(key("s1"), "gone", "n")
        assertTrue(loaded.items.isEmpty())
    }

    @Test
    fun `a session on another host with the same id is not a duplicate`() {
        val items = listOf(Item(key("s1", "a"), INBOX_ID, "n"), Item(key("s1", "b"), INBOX_ID, "p"))
        assertFalse((loading(listOf(inbox), items) as LayoutLoad.Loaded).repaired)
    }

    @Test
    fun `a missing Inbox is re-added before the first group`() {
        val loaded = loading(listOf(Group("work", "Work", "g"))) as LayoutLoad.Loaded
        assertTrue(loaded.repaired)
        assertEquals(listOf(INBOX_ID, "work"), groupIds(loaded.layout))
        assertEquals(INBOX_NAME, loaded.layout.groups.first().name)
        assertTrue(loaded.layout.groups.first().order < "g")
    }
}
