package dev.pohunek.companion.core.data.layout

import dev.pohunek.companion.core.domain.layout.Edit
import dev.pohunek.companion.core.domain.layout.INBOX_ID
import dev.pohunek.companion.core.domain.layout.Layout
import dev.pohunek.companion.core.domain.layout.LayoutView
import dev.pohunek.companion.core.domain.layout.Placement
import dev.pohunek.companion.core.domain.layout.StoredFilter
import dev.pohunek.companion.core.domain.sessions.SessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutCodecTest {
    private fun loaded(text: String): LayoutRead.Loaded = decoded(text) as? LayoutRead.Loaded ?: error("unreadable: ${decoded(text)}")

    private fun decoded(text: String) = LayoutCodec.decode(text)

    private fun <V, E> Edit<V, E>.layout(): Layout = (this as? Edit.Done)?.layout ?: error("refused: $this")

    private fun sample(): Layout {
        val seeded = Layout.default().reconcile("127.0.0.1:1", listOf("a", "b", "c").map { SessionKey("127.0.0.1:1", it) }).layout
        val grouped = seeded.createGroupAndMove("Builds", SessionKey("127.0.0.1:1", "b")).layout()
        return grouped
            .setGroupCollapsed(INBOX_ID, true).layout()
            .moveSession(SessionKey("127.0.0.1:1", "c"), "g1", Placement.End).layout()
            .withView(LayoutView.BY_STATE)
            .withFilter(LayoutView.BY_STATE, StoredFilter(states = listOf("working"), projectLabel = "p", host = "127.0.0.1:1"))
    }

    @Test
    fun `a layout survives a round trip with groups order collapse and manual flags`() {
        val layout = sample()
        val read = loaded(LayoutCodec.encode(layout))
        assertEquals(layout, read.layout)
        assertFalse(read.repaired)
    }

    @Test
    fun `the file uses the schema field names and leaves out defaults`() {
        val text = LayoutCodec.encode(sample())
        for (name in listOf("\"version\":1", "\"session_id\":\"a\"", "\"project_label\"", "\"collapsed\":true", "\"manual\":true")) {
            assertTrue(name, name in text)
        }
        assertFalse("\"no_project\"" in text)
        assertFalse("\"manual\":false" in text)
    }

    @Test
    fun `a file that is not valid is unreadable with a reason`() {
        val inbox = """{"id":"inbox","name":"Inbox","order":"n"}"""
        val invalid = listOf(
            "",
            "not json",
            """{"version":1,"unknown":true}""",
            """{"version":2}""",
            """{"version":1,"groups":[$inbox,$inbox]}""",
            """{"version":1,"groups":[$inbox],"items":[{"host":"h","session_id":"s","group":"nope","order":"n"}]}""",
        )
        for (text in invalid) assertTrue(text, decoded(text) is LayoutRead.Unreadable)
    }

    @Test
    fun `a file without Inbox is repaired and then needs saving`() {
        val read = loaded("""{"version":1,"groups":[{"id":"g1","name":"Work","order":"n"}]}""")
        assertTrue(read.repaired)
        assertEquals(listOf(INBOX_ID, "g1"), read.layout.orderedGroups().map { it.id })
    }
}
