// Ported to Kotlin from pohunek-companion `crates/core/src/filter.rs` (state selection, state chips, query matching, filter_sections).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.OwnerState

/** Every owner state in chip order. */
private val CHIP_ORDER = listOf(
    OwnerState.NeedsOwner,
    OwnerState.Working,
    OwnerState.Idle,
    OwnerState.RuntimeProblem,
    OwnerState.Ended,
    OwnerState.NoAgentState,
)

/** The value stored in the layout file for a state chip. */
val OwnerState.filterKey: String
    get() = when (this) {
        OwnerState.NeedsOwner -> "needs-you"
        OwnerState.Working -> "working"
        OwnerState.Idle -> "idle"
        OwnerState.RuntimeProblem -> "runtime-problem"
        OwnerState.Ended -> "ended"
        OwnerState.NoAgentState -> "no-agent-state"
    }

/** The state chips to show: all of them, except that Problem and No agent appear only while [present], the states some session has. */
fun stateChips(present: Set<OwnerState>): List<OwnerState> =
    CHIP_ORDER.filter { it in present || (it != OwnerState.RuntimeProblem && it != OwnerState.NoAgentState) }

/** The owner states a view shows. The default is every state except Ended. */
data class StateSelection(val states: Set<OwnerState>) {
    operator fun contains(state: OwnerState): Boolean = state in states

    /** Adds the state when absent, removes it when present. */
    fun toggled(state: OwnerState): StateSelection = StateSelection(if (state in states) states - state else states + state)

    /** True when every state passes, so the selection narrows nothing. */
    val isAll: Boolean get() = states.containsAll(CHIP_ORDER)

    /** The selection with Problem on, which a loaded file never leaves off. */
    fun withRuntimeProblem(): StateSelection = StateSelection(states + OwnerState.RuntimeProblem)

    /** The keys to store in chip order, or null for the default selection, which is stored as no entry. */
    fun storedKeys(): List<String>? = if (this == DEFAULT) null else CHIP_ORDER.filter { it in states }.map { it.filterKey }

    companion object {
        val DEFAULT = StateSelection(CHIP_ORDER.toSet() - OwnerState.Ended)

        /**
         * Reads stored [keys]; null selects the default. Unknown keys are ignored, and a list of only unknown keys is a file from
         * another version, not a choice to hide everything, so it selects the default.
         */
        fun fromStored(keys: List<String>?): StateSelection {
            if (keys == null) return DEFAULT
            val parsed = keys.mapNotNull { key -> CHIP_ORDER.firstOrNull { it.filterKey == key } }
            return if (parsed.isEmpty() && keys.isNotEmpty()) DEFAULT else StateSelection(parsed.toSet())
        }
    }
}

/**
 * What a query matches in a row. [texts] (name, project label, agent, Work badge) match as subsequences; [identifiers] (session id,
 * Work id) match as substrings, because a random-looking id contains almost any short subsequence.
 */
data class FilterFacts(val ownerState: OwnerState, val texts: List<String>, val identifiers: List<String>)

/**
 * The state selection and the query narrowing a list. The query is split on whitespace; a row matches when its state is selected and
 * every token matches at least one of its fields, case-insensitively. An empty query matches every row.
 */
class SessionFilter(query: String, val states: StateSelection) {
    private val tokens: List<IntArray> = tokenize(query).map(::codePoints)

    /** Whether a query narrows the list; section expansion and hiding of empty sections follow this, not the state selection. */
    val isSearching: Boolean get() = tokens.isNotEmpty()

    /** Whether any part of the filter hides rows. */
    val isActive: Boolean get() = isSearching || !states.isAll

    fun matches(facts: FilterFacts): Boolean {
        if (facts.ownerState !in states) return false
        if (tokens.isEmpty()) return true
        val texts = facts.texts.map(::codePoints)
        val identifiers = facts.identifiers.map(::codePoints)
        return tokens.all { token -> texts.any { isSubsequence(token, it) } || identifiers.any { isSubstring(token, it) } }
    }

    /** The rows that match, in their order. An inactive filter keeps all of them. */
    fun <R> filterRows(rows: List<R>, factsOf: (R) -> FilterFacts): List<R> = if (isActive) rows.filter { matches(factsOf(it)) } else rows

    /**
     * Keeps only the matching rows of each section, in their order. While a query is set, sections left without a row are dropped and
     * the rest are shown expanded so their matches are visible. With only a state selection, sections the filter emptied are dropped,
     * sections that were already empty stay, and collapsing is kept.
     */
    fun <R> filterSections(sections: List<GroupSection<R>>, factsOf: (R) -> FilterFacts): List<GroupSection<R>> {
        if (!isActive) return sections
        return sections.mapNotNull { section ->
            val rows = section.rows.filter { matches(factsOf(it)) }
            val keep = rows.isNotEmpty() || (!isSearching && section.rows.isEmpty())
            if (keep) section.copy(rows = rows, collapsed = section.collapsed && !isSearching) else null
        }
    }
}

private fun tokenize(query: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    for (c in query) {
        if (c.isWhitespace()) {
            if (current.isNotEmpty()) tokens += current.toString().also { current.clear() }
        } else {
            current.append(c)
        }
    }
    if (current.isNotEmpty()) tokens += current.toString()
    return tokens
}

private fun codePoints(text: String): IntArray = text.lowercase().codePoints().toArray()

private fun isSubsequence(needle: IntArray, haystack: IntArray): Boolean {
    var next = 0
    for (c in haystack) if (next < needle.size && c == needle[next]) next++
    return next == needle.size
}

private fun isSubstring(needle: IntArray, haystack: IntArray): Boolean =
    (0..haystack.size - needle.size).any { start -> needle.indices.all { haystack[start + it] == needle[it] } }
