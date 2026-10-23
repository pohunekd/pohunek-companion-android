package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.core.domain.sessions.SessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewSectionsTest {
    /** A row is identified by its id; its facts come from [facts]. */
    private val facts = HashMap<String, RowFacts>()

    private fun row(id: String, state: OwnerState = OwnerState.Working, project: String? = null, work: WorkItem? = null): String {
        facts[id] = RowFacts(state, project, work)
        return id
    }

    private fun group(id: String, vararg rows: String) = GroupSection(id, id, false, rows.toList())

    private fun derive(view: LayoutView, vararg groups: GroupSection<String>, max: Int = 40) = deriveSections(view, groups.toList(), max) { facts.getValue(it) }

    private fun named(sections: List<GroupSection<String>>) = sections.map { it.name to it.rows }

    private fun item(provider: String?, kind: String?, id: String, title: String) = WorkItem(provider, kind, id, title)

    @Test
    fun `by state places every row once in state order keeping the group order`() {
        val out = derive(
            LayoutView.BY_STATE,
            group("a", row("1", OwnerState.Idle), row("2", OwnerState.NoAgentState), row("3", OwnerState.Working)),
            group("b", row("5", OwnerState.Ended), row("6", OwnerState.Idle), row("8", OwnerState.RuntimeProblem)),
            group("c", row("7", OwnerState.Working)),
        )
        assertEquals(
            listOf(
                "Working" to listOf("3", "7"),
                "Idle" to listOf("1", "6"),
                "Runtime problem" to listOf("8"),
                "Ended" to listOf("5"),
                "No agent state" to listOf("2"),
            ),
            named(out),
        )
    }

    @Test
    fun `by state omits empty sections and keeps a waiting row of a host that is not connected`() {
        assertTrue(derive(LayoutView.BY_STATE).isEmpty())
        val out = derive(LayoutView.BY_STATE, group("a", row("1", OwnerState.Idle), row("2", OwnerState.NeedsOwner)))
        assertEquals(listOf("Idle" to listOf("1"), "Waiting (host not connected)" to listOf("2")), named(out))
    }

    @Test
    fun `by project joins hosts by label orders by title ignoring case and puts no project last`() {
        val out = derive(
            LayoutView.BY_PROJECT,
            group("g1", row("1", project = "beta"), row("2"), row("3", project = "Alpha")),
            group("g2", row("4", project = "beta"), row("5")),
        )
        assertEquals(listOf("Alpha" to listOf("3"), "beta" to listOf("1", "4"), "No project" to listOf("2", "5")), named(out))
    }

    @Test
    fun `labels that differ only in case stay separate sections ordered by title then id`() {
        val out = derive(LayoutView.BY_PROJECT, group("g", row("1", project = "beta"), row("2", project = "Beta"), row("3", project = "alpha")))
        assertEquals(listOf("alpha", "Beta", "beta"), out.map { it.name })
    }

    @Test
    fun `a project title is clipped for display but sorted whole`() {
        val out = derive(LayoutView.BY_PROJECT, group("g", row("1", project = "abcdefghij-2"), row("2", project = "abcdefghij-1")), max = 6)
        assertEquals(listOf("abcde…", "abcde…"), out.map { it.name })
        assertEquals(listOf(listOf("2"), listOf("1")), out.map { it.rows })
        assertEquals(2, out.map { it.id }.toSet().size)
    }

    @Test
    fun `by work item keys on provider kind and id and ignores the role`() {
        val pr12 = item("github", "pr", "12", "GH PR #12")
        val issue12 = item("github", "issue", "12", "GH #12")
        val out = derive(
            LayoutView.BY_WORK_ITEM,
            group("g1", row("1", work = pr12), row("2"), row("3", work = issue12)),
            group("g2", row("4", work = pr12), row("5")),
        )
        assertEquals(
            listOf("GH #12" to listOf("3"), "GH PR #12" to listOf("1", "4"), "No Work item" to listOf("2", "5")),
            named(out),
        )
    }

    @Test
    fun `my groups is the arranged groups unchanged`() {
        val groups = arrayOf(group("a", row("1")), group("b"))
        assertEquals(groups.toList(), derive(LayoutView.MY_GROUPS, *groups))
    }

    private fun key(id: String) = SessionKey("local", id)

    private fun layoutWith(view: LayoutView, vararg ids: String): Layout = Layout.default().reconcile("local", ids.map(::key)).layout.withView(view)

    private fun Layout.sections(max: Int = 40) = presentView(facts.keys.sorted(), ::key, max) { facts.getValue(it) }

    @Test
    fun `collapsed derived sections are kept per view and stale ids are dropped`() {
        row("1", project = "alpha")
        row("2", project = "beta")
        var layout = layoutWith(LayoutView.BY_PROJECT, "1", "2")
        val alpha = layout.sections().first().id
        layout = (layout.setCollapsed(alpha, true) as Edit.Done).layout
        layout = (layout.setCollapsed("gone", true) as Edit.Done).layout
        assertEquals(listOf(true, false), layout.sections().map { it.collapsed })

        layout = layout.pruneCollapsedSections(facts.keys.sorted(), ::key, 40) { facts.getValue(it) }
        assertEquals(mapOf("by-project" to setOf(alpha)), layout.collapsedSections)

        val byState = layout.withView(LayoutView.BY_STATE)
        assertTrue(byState.sections().none { it.collapsed })
        assertEquals(listOf(true, false), byState.withView(LayoutView.BY_PROJECT).sections().map { it.collapsed })
    }

    @Test
    fun `pruning without rows keeps the collapsed state`() {
        row("1", project = "alpha")
        val layout = layoutWith(LayoutView.BY_PROJECT, "1")
        val collapsed = (layout.setCollapsed("project\u001falpha", true) as Edit.Done).layout
        assertEquals(collapsed, collapsed.pruneCollapsedSections(emptyList<String>(), ::key, 40) { facts.getValue(it) })
    }
}
