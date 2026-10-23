package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.SessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DropTargetsTest {
    private fun key(id: String) = SessionKey("host", id)

    /** Inbox (header 0..10, rows a 10..20 and b 20..30), then Work (header 30..40, no rows shown), then Done (header 40..50, row c 50..60). */
    private val entries = listOf(
        DropEntry.Header(INBOX_ID, 0f, 10f),
        DropEntry.Row(key("a"), INBOX_ID, 10f, 20f),
        DropEntry.Row(key("b"), INBOX_ID, 20f, 30f),
        DropEntry.Header("g1", 30f, 40f),
        DropEntry.Header("g2", 40f, 50f),
        DropEntry.Row(key("c"), "g2", 50f, 60f),
    )

    private fun session(y: Float, dragged: String = "c") = sessionDropAt(entries, key(dragged), y)

    @Test
    fun `a session over the top half of a row lands before it and over the bottom half after it`() {
        assertEquals(SessionDrop(INBOX_ID, Placement.Before(key("a")), DropIndicator.Line(10f)), session(14f))
        assertEquals(SessionDrop(INBOX_ID, Placement.After(key("a")), DropIndicator.Line(20f)), session(15f))
        assertEquals(SessionDrop(INBOX_ID, Placement.After(key("b")), DropIndicator.Line(30f)), session(29f))
    }

    @Test
    fun `a session over a header lands at the end of that group, also when it is empty`() {
        assertEquals(SessionDrop("g1", Placement.End, DropIndicator.Span(30f, 40f)), session(35f))
        assertEquals(SessionDrop(INBOX_ID, Placement.End, DropIndicator.Span(0f, 10f)), session(1f))
    }

    @Test
    fun `a session has no target over itself or outside the entries`() {
        assertNull(session(55f, dragged = "c"))
        assertNull(session(-1f))
        assertNull(session(60f))
    }

    @Test
    fun `a group over the top half of a header lands before that group`() {
        assertEquals(GroupDrop(GroupPlacement.Before("g1"), DropIndicator.Line(30f)), groupDropAt(entries, INBOX_ID, 32f))
    }

    @Test
    fun `a group over the bottom half of a header or over rows lands after that group, the line below its last entry`() {
        assertEquals(GroupDrop(GroupPlacement.After("g2"), DropIndicator.Line(60f)), groupDropAt(entries, INBOX_ID, 48f))
        assertEquals(GroupDrop(GroupPlacement.After("g2"), DropIndicator.Line(60f)), groupDropAt(entries, INBOX_ID, 52f))
        assertEquals(GroupDrop(GroupPlacement.After("g1"), DropIndicator.Line(40f)), groupDropAt(entries, INBOX_ID, 38f))
    }

    @Test
    fun `a group has no target over itself or outside the entries`() {
        assertNull(groupDropAt(entries, INBOX_ID, 15f))
        assertNull(groupDropAt(entries, "g2", 55f))
        assertNull(groupDropAt(entries, "g2", 70f))
    }

    private val shown = listOf(
        GroupSection(INBOX_ID, "Inbox", false, listOf(key("a"), key("b"))),
        GroupSection("g1", "Work", false, emptyList()),
        GroupSection("g2", "Done", false, listOf(key("c"))),
    )

    @Test
    fun `moving a session up goes before the row above, else to the end of the group above`() {
        assertEquals(SessionStep(INBOX_ID, Placement.Before(key("a"))), stepUp(shown, key("b")))
        assertEquals(SessionStep("g1", Placement.End), stepUp(shown, key("c")))
        assertNull(stepUp(shown, key("a")))
        assertNull(stepUp(shown, key("missing")))
    }

    @Test
    fun `moving a session down goes after the row below, else into the group below at its start or end`() {
        assertEquals(SessionStep(INBOX_ID, Placement.After(key("b"))), stepDown(shown, key("a")))
        assertEquals(SessionStep("g1", Placement.End), stepDown(shown, key("b")))
        assertEquals(SessionStep("g2", Placement.Before(key("c"))), stepDown(shown.filterNot { it.id == "g1" }, key("b")))
        assertNull(stepDown(shown, key("c")))
    }

    @Test
    fun `moving a group steps over its neighbour and stops at the ends`() {
        val ids = listOf(INBOX_ID, "g1", "g2")
        assertEquals(GroupPlacement.Before(INBOX_ID), groupStepUp(ids, "g1"))
        assertNull(groupStepUp(ids, INBOX_ID))
        assertEquals(GroupPlacement.After("g2"), groupStepDown(ids, "g1"))
        assertNull(groupStepDown(ids, "g2"))
    }
}
