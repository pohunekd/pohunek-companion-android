package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.SessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupValuesTest {
    private val a = "10.0.0.1:1"
    private val b = "10.0.0.2:1"

    private fun key(id: String, host: String = a) = SessionKey(host, id)

    private fun fact(id: String, group: String?, host: String = a) =
        GroupFact(key(id, host), group?.let { GroupValue.Present(it) } ?: GroupValue.Absent, external = false)

    private fun Layout.withSessions(host: String, vararg ids: String) = reconcile(host, ids.map { key(it, host) }).layout

    private fun Layout.withGroup(name: String) = (createGroup(name, 64) as Edit.Done).layout

    private fun Layout.sessionsIn(group: String) = groupItems(group).map { it.key }

    private fun Layout.groupNamed(name: String) = groups.first { it.name == name }

    @Test
    fun `names merge ignoring case and keep the phone's spelling`() {
        val layout = Layout.default().withGroup("Later").withSessions(a, "s1").withSessions(b, "s2")
        val first = layout.applyGroupValues(listOf(fact("s1", "later")), allHostsListed = false)
        val second = first.layout.applyGroupValues(listOf(fact("s2", "LATER", b)), allHostsListed = false)
        val later = second.layout.groupNamed("Later")
        assertEquals(listOf(key("s1"), key("s2", b)), second.layout.sessionsIn(later.id))
        assertEquals(2, second.layout.groups.size)
        assertEquals(GroupOrigin.Created, later.origin)
    }

    @Test
    fun `learned groups across two routes with different case form one group`() {
        val layout = Layout.default().withSessions(a, "s1").withSessions(b, "s2")
        val sync = layout.applyGroupValues(listOf(fact("s1", "Reviews"), fact("s2", "reviews", b)), allHostsListed = false)
        assertEquals(1, sync.learnedAdded)
        val reviews = sync.layout.groupNamed("Reviews")
        assertEquals(GroupOrigin.Learned, reviews.origin)
        assertEquals(listOf(key("s1"), key("s2", b)), sync.layout.sessionsIn(reviews.id))
    }

    @Test
    fun `Inbox in any case places the session in Inbox`() {
        val layout = Layout.default().withGroup("Work").withSessions(a, "s1")
        val inWork = layout.moveSession(key("s1"), "g1", Placement.End, manual = true).let { (it as Edit.Done).layout }
        val sync = inWork.applyGroupValues(listOf(fact("s1", "iNbOx")), allHostsListed = true)
        assertEquals(listOf(key("s1")), sync.layout.sessionsIn(INBOX_ID))
        assertEquals(1, sync.layout.groups.count { it.name.equals("inbox", ignoreCase = true) })
        assertEquals(0, sync.learnedAdded)
    }

    @Test
    fun `a learned group is added after the last group and dropped only when every host has listed`() {
        val layout = Layout.default().withGroup("Mine").withSessions(a, "s1")
        val added = layout.applyGroupValues(listOf(fact("s1", "Night shift")), allHostsListed = false)
        assertEquals(listOf(INBOX_ID, "g1", "g2"), added.layout.orderedGroups().map { it.id })
        assertEquals("Night shift", added.layout.orderedGroups().last().name)

        val cleared = added.layout.applyGroupValues(listOf(fact("s1", null)), allHostsListed = false)
        assertEquals(0, cleared.learnedDropped)
        assertEquals(listOf(key("s1")), cleared.layout.sessionsIn(INBOX_ID))
        assertTrue(cleared.layout.groups.any { it.name == "Night shift" })

        val swept = cleared.layout.applyGroupValues(emptyList(), allHostsListed = true)
        assertEquals(1, swept.learnedDropped)
        assertFalse(swept.layout.groups.any { it.name == "Night shift" })
    }

    @Test
    fun `a learned group holding a session of a silent host is kept`() {
        val layout = Layout.default().withSessions(a, "s1").withSessions(b, "s2")
        val added = layout.applyGroupValues(listOf(fact("s1", "Q"), fact("s2", "Q", b)), allHostsListed = false)
        val cleared = added.layout.applyGroupValues(listOf(fact("s1", null)), allHostsListed = true)
        assertTrue(cleared.layout.groups.any { it.name == "Q" })
    }

    @Test
    fun `a created group stays when empty`() {
        val layout = Layout.default().withGroup("Mine").withSessions(a, "s1")
        val sync = layout.applyGroupValues(listOf(fact("s1", null)), allHostsListed = true)
        assertSame(layout, sync.layout)
        assertEquals(0, sync.learnedDropped)
    }

    @Test
    fun `the key clears the manual flag and a cleared key returns the session to Inbox`() {
        val layout = Layout.default().withGroup("A").withGroup("B").withSessions(a, "s1")
        val local = (layout.moveSession(key("s1"), "g1", Placement.End, manual = true) as Edit.Done).layout
        assertTrue(local.items.single().manual)

        val keyed = local.applyGroupValues(listOf(fact("s1", "B")), allHostsListed = true)
        val item = keyed.layout.items.single()
        assertEquals("g2", item.group)
        assertFalse(item.manual)

        val cleared = keyed.layout.applyGroupValues(listOf(fact("s1", null)), allHostsListed = true)
        assertEquals(INBOX_ID, cleared.layout.items.single().group)
    }

    @Test
    fun `a local move without a key stays put`() {
        val layout = Layout.default().withGroup("A").withSessions(a, "s1")
        val local = (layout.moveSession(key("s1"), "g1", Placement.End, manual = true) as Edit.Done).layout
        val sync = local.applyGroupValues(listOf(fact("s1", null)), allHostsListed = true)
        assertSame(local, sync.layout)
    }

    @Test
    fun `a session arriving in a group goes to its end and one already there keeps its position`() {
        val layout = Layout.default().withGroup("A").withSessions(a, "s1", "s2", "s3")
        val first = layout.applyGroupValues(listOf(fact("s3", "A"), fact("s1", "A")), allHostsListed = true).layout
        assertEquals(listOf(key("s3"), key("s1")), first.sessionsIn("g1"))
        val again = first.applyGroupValues(listOf(fact("s1", "a"), fact("s3", "a")), allHostsListed = true)
        assertSame(first, again.layout)
        assertEquals(0, again.moved)
    }

    @Test
    fun `facts of sessions the layout does not hold are ignored`() {
        val layout = Layout.default().withSessions(a, "s1")
        val sync = layout.applyGroupValues(listOf(fact("ghost", "X")), allHostsListed = true)
        assertSame(layout, sync.layout)
    }

    @Test
    fun `rejected values place the session as if it had no key`() {
        val layout = Layout.default().withGroup("A").withSessions(a, "s1")
        val keyed = layout.applyGroupValues(listOf(fact("s1", "A")), allHostsListed = true).layout
        val rejected = GroupFact(key("s1"), GroupValue.Rejected(GroupNameProblem.TooLong), external = false)
        val sync = keyed.applyGroupValues(listOf(rejected), allHostsListed = true)
        assertEquals(INBOX_ID, sync.layout.items.single().group)
    }

    @Test
    fun `names match as they are shown, with whitespace collapsed`() {
        val layout = Layout.default().withGroup("Night  shift").withSessions(a, "s1", "s2")
        val sync = layout.applyGroupValues(listOf(fact("s1", "night shift"), fact("s2", "NIGHT\u00a0 SHIFT")), allHostsListed = true)
        assertEquals(0, sync.learnedAdded)
        assertEquals(listOf(key("s1"), key("s2")), sync.layout.sessionsIn("g1"))
    }
}
