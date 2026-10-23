package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.OwnerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFilterTest {
    private val facts = HashMap<String, FilterFacts>()

    private fun row(
        id: String,
        name: String,
        state: OwnerState = OwnerState.Working,
        project: String? = null,
        agent: String = "shell",
        badge: String? = null,
        work: String? = null,
        host: String = "h1",
    ): String {
        facts[id] = FilterFacts(state, listOfNotNull(name, project, agent, badge), listOfNotNull(id, work), project, host)
        return id
    }

    private fun filter(
        query: String = "",
        states: StateSelection = StateSelection.DEFAULT,
        project: ProjectSelection = ProjectSelection.All,
        host: String? = null,
    ) = SessionFilter(query, states, project, host)

    private fun SessionFilter.hits(vararg ids: String) = ids.filter { matches(facts.getValue(it)) }

    private fun section(id: String, vararg rows: String, collapsed: Boolean = false) = GroupSection(id, id, collapsed, rows.toList())

    private fun SessionFilter.sections(vararg sections: GroupSection<String>) = filterSections(sections.toList()) { facts.getValue(it) }

    @Test
    fun `text fields match as subsequences and identifiers only as substrings`() {
        val review = row("s-1", "review-pr-1412", agent = "claude", badge = "GH PR #1412", work = "1412")
        row("s-2", "docs", project = "proj-gamma")
        assertEquals(listOf("s-1"), filter("rvw").hits("s-1", "s-2"))
        assertEquals(listOf("s-1"), filter("1412").hits("s-1", "s-2"))
        assertEquals(listOf("s-2"), filter("gam").hits("s-1", "s-2"))
        assertEquals(listOf("s-1"), filter("RVW CLA").hits(review, "s-2"))
        // An id is not matched by a scattered subsequence, and the characters of one token never span two fields.
        assertTrue(filter("s2").hits("s-1", "s-2").isEmpty())
        assertTrue(filter("docs gamma9").hits("s-2").isEmpty())
        assertEquals(listOf("s-2"), filter("  docs   proj ").hits("s-2"))
    }

    @Test
    fun `matching folds case including non ascii letters`() {
        row("a", "železná")
        assertEquals(listOf("a"), filter("ŽEL").hits("a"))
        row("b", "ŽELEZNÁ")
        assertEquals(listOf("b"), filter("žel").hits("b"))
    }

    @Test
    fun `an unselected state hides the row whatever the query says`() {
        row("a", "alpha", state = OwnerState.Ended)
        row("b", "alpha")
        assertEquals(listOf("b"), filter("alpha").hits("a", "b"))
        val withEnded = StateSelection.DEFAULT.toggled(OwnerState.Ended)
        assertEquals(listOf("a", "b"), filter("alpha", withEnded).hits("a", "b"))
    }

    @Test
    fun `the default selection is every state but Ended and is stored as no entry`() {
        assertFalse(OwnerState.Ended in StateSelection.DEFAULT)
        assertTrue(OwnerState.entries.filter { it != OwnerState.Ended }.all { it in StateSelection.DEFAULT })
        assertNull(StateSelection.DEFAULT.storedKeys())
        assertNull(StateSelection.DEFAULT.toggled(OwnerState.Ended).toggled(OwnerState.Ended).storedKeys())
        assertEquals(
            listOf("needs-you", "working", "idle", "runtime-problem", "ended", "no-agent-state"),
            StateSelection.DEFAULT.toggled(OwnerState.Ended).storedKeys(),
        )
    }

    @Test
    fun `stored keys are read leniently`() {
        assertEquals(StateSelection.DEFAULT, StateSelection.fromStored(null))
        assertEquals(StateSelection(setOf(OwnerState.Idle)), StateSelection.fromStored(listOf("idle", "future-state")))
        assertEquals(StateSelection.DEFAULT, StateSelection.fromStored(listOf("future-state")))
        assertEquals(StateSelection(emptySet()), StateSelection.fromStored(emptyList()))
    }

    @Test
    fun `problem and no agent chips show only while a session has the state`() {
        assertEquals(
            listOf(OwnerState.NeedsOwner, OwnerState.Working, OwnerState.Idle, OwnerState.Ended),
            stateChips(setOf(OwnerState.Working)),
        )
        assertEquals(
            listOf(
                OwnerState.NeedsOwner,
                OwnerState.Working,
                OwnerState.Idle,
                OwnerState.RuntimeProblem,
                OwnerState.Ended,
                OwnerState.NoAgentState,
            ),
            stateChips(setOf(OwnerState.RuntimeProblem, OwnerState.NoAgentState)),
        )
    }

    @Test
    fun `a query drops empty sections and expands the rest`() {
        val sections = filter("alp").sections(
            section("a", row("1", "alpha"), row("2", "beta"), collapsed = true),
            section("b", row("3", "gamma")),
            section("c"),
        )
        assertEquals(listOf("a"), sections.map { it.id })
        assertEquals(listOf("1"), sections.single().rows)
        assertFalse(sections.single().collapsed)
    }

    @Test
    fun `a state selection drops sections it emptied but keeps empty ones and collapsing`() {
        val sections = filter().sections(
            section("a", row("1", "alpha", OwnerState.Ended), collapsed = true),
            section("b", row("2", "beta"), row("3", "gamma", OwnerState.Ended), collapsed = true),
            section("c"),
        )
        assertEquals(listOf("b", "c"), sections.map { it.id })
        assertEquals(listOf("2"), sections.first().rows)
        assertTrue(sections.first().collapsed)
    }

    @Test
    fun `an inactive filter returns the sections as they are`() {
        val all = StateSelection(OwnerState.entries.toSet())
        val input = section("a", row("1", "alpha", OwnerState.Ended), collapsed = true)
        assertEquals(listOf(input), filter(states = all).sections(input))
        assertFalse(filter(states = all).isActive)
        assertTrue(filter("x", all).isActive)
    }

    @Test
    fun `a project or host narrows further and ends collapsing like a query`() {
        row("a", "alpha", project = "ripgrep", host = "h1")
        row("b", "beta", project = "Ripgrep", host = "h2")
        row("c", "gamma", host = "h2")
        val ids = arrayOf("a", "b", "c")
        assertEquals(listOf("a"), filter(project = ProjectSelection.Project("ripgrep")).hits(*ids))
        assertEquals(listOf("c"), filter(project = ProjectSelection.NoProject).hits(*ids))
        assertEquals(listOf("b", "c"), filter(host = "h2").hits(*ids))
        assertEquals(listOf("b"), filter("beta", project = ProjectSelection.Project("Ripgrep"), host = "h2").hits(*ids))
        // A project that no longer exists and a host that is not followed keep filtering as empty.
        assertTrue(filter(project = ProjectSelection.Project("gone")).hits(*ids).isEmpty())
        assertTrue(filter(host = "h9").hits(*ids).isEmpty())

        val sections = filter(host = "h1").sections(section("g1", "a", collapsed = true), section("g2", "b", "c"))
        assertEquals(listOf("g1"), sections.map { it.id })
        assertFalse(sections.single().collapsed)
    }

    @Test
    fun `project chips are sorted ignoring case and keep the selected project`() {
        val rows = listOf("beta", "Alpha", null, "alpha2", "beta")
        assertEquals(
            listOf(null, "Alpha", "alpha2", "beta", null),
            projectChips(rows, ProjectSelection.All).map { it.label },
        )
        assertEquals(
            listOf(ProjectSelection.All, ProjectSelection.Project("Alpha"), ProjectSelection.Project("alpha2")),
            projectChips(rows, ProjectSelection.All).map { it.selection }.take(3),
        )
        assertEquals(ProjectSelection.NoProject, projectChips(rows, ProjectSelection.All).last().selection)
        val withoutNone = projectChips(listOf("beta"), ProjectSelection.Project("gone"))
        assertEquals(listOf(null, "beta", "gone"), withoutNone.map { it.label })
        assertEquals(listOf("gone"), withoutNone.filter { it.selected }.map { it.label })
        assertEquals(listOf(ProjectSelection.All, ProjectSelection.NoProject), projectChips(emptyList(), ProjectSelection.NoProject).map { it.selection })
        assertEquals(ProjectSelection.All, ProjectSelection.Project("a").tapped(ProjectSelection.Project("a")))
        assertEquals(ProjectSelection.NoProject, ProjectSelection.Project("a").tapped(ProjectSelection.NoProject))
    }

    @Test
    fun `host options are hidden below two hosts and keep a selected host that is gone`() {
        val one = listOf(HostEntry("h1", "one", HostReach.Connected))
        val two = one + HostEntry("h2", "two", HostReach.Unreachable)
        assertTrue(hostOptions(one, null).isEmpty())
        assertEquals(listOf(null, "h1", "h2"), hostOptions(two, null).map { it.key })
        assertEquals(listOf(true, false, false), hostOptions(two, null).map { it.selected })
        assertEquals(listOf(HostReach.Connected, HostReach.Unreachable), hostOptions(two, null).mapNotNull { it.reach })
        val stale = hostOptions(one, "h9")
        assertEquals(listOf(null, "h1", "h9"), stale.map { it.key })
        assertEquals(HostReach.Unreachable, stale.last().reach)
        assertTrue(stale.last().selected)
        assertNull(tappedHost("h1", "h1"))
        assertEquals("h2", tappedHost("h1", "h2"))
        assertNull(tappedHost("h1", null))
    }
}
