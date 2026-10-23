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
    ): String {
        facts[id] = FilterFacts(state, listOfNotNull(name, project, agent, badge), listOfNotNull(id, work))
        return id
    }

    private fun filter(query: String = "", states: StateSelection = StateSelection.DEFAULT) = SessionFilter(query, states)

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
}
