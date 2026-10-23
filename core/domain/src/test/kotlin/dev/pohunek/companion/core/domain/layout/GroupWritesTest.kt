package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.SessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupWritesTest {
    private val a = "10.0.0.1:1"
    private val b = "10.0.0.2:1"
    private val maxBytes = 64

    private fun key(id: String, host: String = a) = SessionKey(host, id)

    private fun fact(id: String, value: GroupValue = GroupValue.Absent, host: String = a, external: Boolean = false) =
        GroupFact(key(id, host), value, external)

    private fun present(name: String) = GroupValue.Present(name)

    private fun <V, E> Edit<V, E>.done(): Edit.Done<V> = this as? Edit.Done ?: error("refused: $this")

    private fun Layout.withSessions(host: String, vararg ids: String) = reconcile(host, ids.map { key(it, host) }).layout

    /** Creates [name] and moves the sessions into it as manual placements; the group id is `g<n>` in creation order. */
    private fun Layout.placed(name: String, vararg sessions: SessionKey): Layout {
        val created = createGroup(name, maxBytes).done()
        return sessions.fold(created.layout) { layout, session ->
            layout.moveSession(session, created.value, Placement.End, manual = true).done().layout
        }
    }

    @Test
    fun `migration writes the manual placements outside Inbox of a host`() {
        val layout = Layout.default().withSessions(a, "s1", "s2", "s3").withSessions(b, "t1")
            .placed("Later", key("s1"), key("s2"))
            .placed("Mine", key("t1", b))
        val plan = layout.migrationPlan(a, listOf(fact("s1"), fact("s2"), fact("s3")), maxBytes)
        assertEquals(listOf(GroupWrite(key("s1"), "Later"), GroupWrite(key("s2"), "Later")), plan.writes)
        assertTrue(plan.unshared.isEmpty())
    }

    @Test
    fun `migration skips manual Inbox items, items that are not manual, external sessions and sessions missing from the list`() {
        val base = Layout.default().withSessions(a, "inbox-manual", "plain", "external", "missing", "moved")
        val inInbox = base.moveSession(key("inbox-manual"), INBOX_ID, Placement.End, manual = true).done().layout
        val placed = inInbox.placed("Later", key("external"), key("missing"), key("moved"))
        val unmarked = placed.moveSession(key("moved"), "g1", Placement.End, manual = false).done().layout
        val plan = unmarked.migrationPlan(
            a,
            listOf(fact("inbox-manual"), fact("plain"), fact("external", external = true), fact("moved")),
            maxBytes,
        )
        assertTrue(plan.writes.isEmpty())
    }

    @Test
    fun `migration never overwrites a key that is present, usable or not`() {
        val layout = Layout.default().withSessions(a, "s1", "s2", "s3").placed("Later", key("s1"), key("s2"), key("s3"))
        val plan = layout.migrationPlan(
            a,
            listOf(fact("s1", present("Other")), fact("s2", GroupValue.Rejected(GroupNameProblem.TooLong)), fact("s3")),
            maxBytes,
        )
        assertEquals(listOf(GroupWrite(key("s3"), "Later")), plan.writes)
    }

    @Test
    fun `a migrated host gives nothing and recording it twice changes nothing`() {
        val layout = Layout.default().withSessions(a, "s1").placed("Later", key("s1"))
        val migrated = layout.withMigrated(a)
        assertEquals(listOf(a), migrated.migratedHosts)
        assertEquals(migrated, migrated.withMigrated(a))
        assertTrue(migrated.migrationPlan(a, listOf(fact("s1")), maxBytes).writes.isEmpty())
        assertEquals(1, layout.migrationPlan(a, listOf(fact("s1")), maxBytes).writes.size)
    }

    @Test
    fun `a group name that cannot be a value keeps its sessions local`() {
        val layout = Layout.default().withSessions(a, "s1").placed("Later", key("s1"))
        val tooLong = layout.copyGroupName("g1", "x".repeat(65))
        val plan = tooLong.migrationPlan(a, listOf(fact("s1")), maxBytes)
        assertTrue(plan.writes.isEmpty())
        assertEquals(listOf(key("s1")), plan.unshared)
    }

    private fun Layout.copyGroupName(id: String, name: String): Layout = Layout.load(
        Layout.FORMAT_VERSION,
        storedView,
        filters,
        collapsedSections,
        groups.map { if (it.id == id) it.copy(name = name) else it },
        items,
        migratedHosts,
    ).let { (it as LayoutLoad.Loaded).layout }

    @Test
    fun `members of a group are the sessions that name it ignoring case and its local placements without a usable key`() {
        val layout = Layout.default().withSessions(a, "keyed", "local", "other", "away", "garbled").withSessions(b, "remote")
            .placed("Later", key("local"), key("garbled"), key("away"))
        val facts = listOf(
            fact("keyed", present("later")),
            fact("local"),
            fact("other", present("Elsewhere")),
            fact("away", present("Elsewhere")),
            fact("garbled", GroupValue.Rejected(GroupNameProblem.ControlCharacter)),
            fact("remote", present("LATER"), host = b),
        )
        val members = layout.groupMembers("g1", facts)
        // Placed sessions first, in the group's display order; sessions that only name the group follow in list order.
        assertEquals(listOf(key("local"), key("garbled"), key("keyed"), key("remote", b)), members.map { it.key })
    }

    @Test
    fun `members carry the external flag and Inbox and unknown groups have none`() {
        val layout = Layout.default().withSessions(a, "ext").placed("Later", key("ext"))
        val members = layout.groupMembers("g1", listOf(fact("ext", external = true)))
        assertEquals(listOf(GroupMember(key("ext"), external = true)), members)
        assertTrue(layout.groupMembers(INBOX_ID, listOf(fact("ext", present("Inbox")))).isEmpty())
        assertTrue(layout.groupMembers("nope", listOf(fact("ext"))).isEmpty())
    }

    @Test
    fun `renaming into the name of a learned group merges it, a created group refuses`() {
        val layout = Layout.default().withSessions(a, "s1", "s2", "s3").placed("Later", key("s1")).placed("Mine")
        val learned = layout.applyGroupValues(listOf(fact("s2", present("Someday")), fact("s3", present("someday"))), allHostsListed = false).layout
        val learnedId = learned.groups.first { it.origin == GroupOrigin.Learned }.id

        val merged = learned.renameGroup("g1", "Someday", maxBytes).done()
        assertTrue(merged.value)
        assertEquals(listOf("g1"), merged.layout.groups.filter { it.name == "Someday" }.map { it.id })
        assertTrue(merged.layout.groups.none { it.id == learnedId })
        assertEquals(listOf(key("s1"), key("s2"), key("s3")), merged.layout.groupItems("g1").map { it.key })

        val refused = learned.renameGroup("g1", "mine", maxBytes)
        assertEquals(GroupError.DuplicateName("mine"), (refused as Edit.Refused).error)
    }

    @Test
    fun `a name that cannot be written as a value is refused with its reason`() {
        val layout = Layout.default().withSessions(a, "s1").placed("Later", key("s1"))
        fun refusal(edit: Edit<*, GroupError>) = (edit as Edit.Refused).error
        assertEquals(GroupError.NameTooLong, refusal(layout.createGroup("x".repeat(65), maxBytes)))
        assertEquals(GroupError.NameTooLong, refusal(layout.renameGroup("g1", "é".repeat(33), maxBytes)))
        assertEquals(GroupError.NameHasControlCharacter, refusal(layout.createGroup("a\u0007b", maxBytes)))
        assertEquals(GroupError.NameHasControlCharacter, refusal(layout.renameGroup("g1", "a‮b", maxBytes)))
        assertEquals(GroupError.EmptyName, refusal(layout.createGroup("  ", maxBytes)))
        assertTrue(layout.createGroup("é".repeat(32), maxBytes) is Edit.Done)
    }

    @Test
    fun `a move can clear the manual flag and then changes only the flag when the session is already there`() {
        val layout = Layout.default().withSessions(a, "s1").placed("Later", key("s1"))
        assertTrue(layout.items.single().manual)
        val cleared = layout.moveSession(key("s1"), "g1", Placement.End, manual = false).done()
        assertTrue(cleared.value)
        assertFalse(cleared.layout.items.single().manual)
        assertFalse(cleared.layout.moveSession(key("s1"), "g1", Placement.End, manual = false).done().value)
    }
}
