package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.SessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubSessionsTest {
    private fun key(id: String, host: String = "h1") = SessionKey(host, id)

    /** A row is its key; the fold output keeps the position beside it. */
    private data class Placed(val key: SessionKey, val tree: TreePosition)

    private fun tree(vararg links: Pair<String, String?>) = SubSessionTree.build(links.map { (id, spawner) -> key(id) to spawner })

    private fun fold(
        sections: List<List<String>>,
        tree: SubSessionTree,
        expanded: Set<String> = emptySet(),
        fold: Boolean = true,
    ): List<List<Pair<String, TreePosition>>> {
        val input = sections.mapIndexed { index, ids -> GroupSection("s$index", "s$index", false, ids.map { Placed(key(it), TreePosition()) }) }
        return foldSections(input, tree, expanded.mapTo(HashSet()) { key(it) }, fold, Placed::key) { row, position -> row.copy(tree = position) }
            .map { section -> section.rows.map { it.key.sessionId to it.tree } }
    }

    private fun List<List<Pair<String, TreePosition>>>.ids() = map { section -> section.map { it.first } }

    @Test
    fun `an orphan whose spawner is gone is a root`() {
        val tree = tree("a" to "gone", "b" to "a")
        assertNull(tree.parent(key("a")))
        assertEquals(key("a"), tree.parent(key("b")))
    }

    @Test
    fun `a spawner on another host is not a parent`() {
        val tree = SubSessionTree.build(listOf(key("a", "h1") to null, key("b", "h2") to "a"))
        assertNull(tree.parent(key("b", "h2")))
    }

    @Test
    fun `a spawned_by cycle makes each of its sessions a root and a session spawned by itself a root`() {
        val tree = tree("a" to "b", "b" to "c", "c" to "a", "d" to "c", "e" to "e")
        listOf("a", "b", "c", "e").forEach { assertNull(it, tree.parent(key(it))) }
        assertEquals(key("c"), tree.parent(key("d")))
        assertEquals(listOf("a", "b", "c", "d", "e"), fold(listOf(listOf("a", "b", "c", "d", "e")), tree, fold = false).ids().single())
    }

    @Test
    fun `sub-sessions are hidden until the parent is expanded and then indented by depth`() {
        val tree = tree("p" to null, "a" to "p", "b" to "p", "g" to "a")
        val sections = listOf(listOf("p", "a", "b", "g"))

        val folded = fold(sections, tree)
        assertEquals(listOf(listOf("p")), folded.ids())
        assertEquals(TreePosition(depth = 0, subSessions = 2, expanded = false, folding = true), folded.single().single().second)

        val expanded = fold(sections, tree, expanded = setOf("p", "a"))
        assertEquals(listOf(listOf("p", "a", "g", "b")), expanded.ids())
        assertEquals(listOf(0, 1, 2, 1), expanded.single().map { it.second.depth })
    }

    @Test
    fun `an expanded child under a collapsed parent stays hidden`() {
        val tree = tree("p" to null, "a" to "p", "g" to "a")
        assertEquals(listOf(listOf("p")), fold(listOf(listOf("p", "a", "g")), tree, expanded = setOf("a")).ids())
    }

    @Test
    fun `with the fold off every row stays in its place and only carries its count`() {
        val tree = tree("p" to null, "a" to "p")
        val result = fold(listOf(listOf("p", "a")), tree, fold = false)
        assertEquals(listOf(listOf("p", "a")), result.ids())
        assertEquals(TreePosition(subSessions = 1), result.single().first().second)
    }

    @Test
    fun `a sub-session whose parent is not among the shown rows stays in its own place`() {
        // The parent is filtered out or lifted into Needs you, so it is in no section.
        val tree = tree("p" to null, "a" to "p")
        assertEquals(listOf(listOf("a")), fold(listOf(listOf("a")), tree).ids())
    }

    @Test
    fun `a child shows under its parent in another section and a section the fold emptied is dropped`() {
        val tree = tree("p" to null, "a" to "p", "x" to null)
        val sections = listOf(listOf("p"), listOf("a"), listOf("x"))
        assertEquals(listOf(listOf("p", "a"), listOf("x")), fold(sections, tree, expanded = setOf("p")).ids().filter { it.isNotEmpty() })
        assertEquals(listOf(listOf("p"), listOf("x")), fold(sections, tree).ids())
    }

    @Test
    fun `counting the Needs you rows gives counts and no fold control`() {
        val tree = tree("p" to null, "a" to "p")
        val rows = countSubSessions(listOf(Placed(key("p"), TreePosition())), tree, Placed::key) { row, position -> row.copy(tree = position) }
        assertEquals(TreePosition(subSessions = 1, folding = false), rows.single().tree)
    }

    @Test
    fun `a sub-session whose parent sits in a collapsed section stays in its own open section`() {
        val tree = tree("p" to null, "a" to "p")
        val sections = listOf(
            GroupSection("s0", "s0", true, listOf(Placed(key("p"), TreePosition()))),
            GroupSection("s1", "s1", false, listOf(Placed(key("a"), TreePosition()))),
        )
        val folded = foldSections(sections, tree, setOf(key("p")), true, Placed::key) { row, position -> row.copy(tree = position) }
        assertEquals(listOf(listOf("p"), listOf("a")), folded.map { s -> s.rows.map { it.key.sessionId } })
    }
}
