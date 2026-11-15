// Ported to Kotlin from pohunek-companion `crates/core/src/sub_sessions.rs` (SubSessionTree, fold_sections) at b743aa2, without its
// `work.role=manager` / `manager` metadata fallback: a parent is only the session `spawned_by` names.
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.SessionKey

/** Where a displayed row sits in the sub-session fold. */
data class TreePosition(
    /** Levels below the row's root that the row is shown at; 0 for a row in its own place. */
    val depth: Int = 0,
    /** Number of direct sub-sessions. */
    val subSessions: Int = 0,
    /** The row's sub-sessions are shown under it. */
    val expanded: Boolean = false,
    /** Sub-sessions are folded, so the row's control expands and collapses them. */
    val folding: Boolean = false,
)

/** The parent of every session that has one, over the sessions of all hosts. */
class SubSessionTree private constructor(
    private val parents: Map<SessionKey, SessionKey>,
    private val children: Map<SessionKey, List<SessionKey>>,
) {
    /** The session [key] folds under; null for a root. */
    fun parent(key: SessionKey): SessionKey? = parents[key]

    /** The direct sub-sessions of [key], in the order of the sessions [build] saw. */
    fun children(key: SessionKey): List<SessionKey> = children[key].orEmpty()

    /** True when at least one session folds under another. */
    val isEmpty: Boolean get() = parents.isEmpty()

    companion object {
        /**
         * Resolves each session's parent from its `spawned_by` session id. [sessions] pairs a session with the id its `spawned_by` names (null
         * when it has none). A parent is looked up on the session's own host only; a spawner that is not among the sessions makes an orphan
         * a root, and a `spawned_by` cycle makes each of its sessions a root.
         */
        fun build(sessions: List<Pair<SessionKey, String?>>): SubSessionTree {
            val known = sessions.mapTo(HashSet()) { it.first }
            val parents = LinkedHashMap<SessionKey, SessionKey>()
            for ((key, spawner) in sessions) {
                val parent = spawner?.let { SessionKey(key.host, it) }
                if (parent != null && parent != key && parent in known) parents[key] = parent
            }
            breakCycles(parents)
            val children = LinkedHashMap<SessionKey, MutableList<SessionKey>>()
            for ((key, _) in sessions) parents[key]?.let { children.getOrPut(it) { mutableListOf() } += key }
            return SubSessionTree(parents, children)
        }

        /** Removes the parent link of every session on a cycle, so each becomes a root and no walk up the tree can loop. */
        private fun breakCycles(parents: MutableMap<SessionKey, SessionKey>) {
            val settled = HashSet<SessionKey>()
            val onCycle = mutableListOf<SessionKey>()
            for (start in parents.keys.toList()) {
                val path = mutableListOf<SessionKey>()
                var current: SessionKey? = start
                while (current != null && current !in settled) {
                    val at = path.indexOf(current)
                    if (at >= 0) {
                        onCycle += path.subList(at, path.size)
                        break
                    }
                    path += current
                    current = parents[current]
                }
                settled += path
            }
            onCycle.forEach(parents::remove)
        }
    }
}

/**
 * Sets each row's sub-session count and, when [fold] is set, shows every sub-session only under its parent: an expanded parent (one of
 * [expanded]) is followed by its sub-sessions, indented by depth. It runs on the sections as they are displayed (after the Needs-you
 * lift and the filter), so a sub-session whose parent is not among [sections] (lifted into Needs you, or filtered out) stays in its own
 * place and no session hides behind a parent that is not there to expand. The Needs you list is not passed in, so a sub-session that
 * needs the owner stays lifted there and is not shown a second time under its parent; [countSubSessions] gives those rows their counts
 * but no control. A section that only the fold emptied is dropped.
 */
fun <R> foldSections(
    sections: List<GroupSection<R>>,
    tree: SubSessionTree,
    expanded: Set<SessionKey>,
    fold: Boolean,
    keyOf: (R) -> SessionKey,
    withPosition: (R, TreePosition) -> R,
): List<GroupSection<R>> {
    val placed = LinkedHashMap<SessionKey, R>()
    // Rows of a collapsed section are not drawn, so they can neither host nor receive a folded sub-session.
    for (row in sections.filterNot { it.collapsed }.flatMap { it.rows }) placed.putIfAbsent(keyOf(row), row)
    val order = placed.keys.withIndex().associate { it.value to it.index }
    fun plain(row: R) = withPosition(row, TreePosition(subSessions = tree.children(keyOf(row)).size))

    fun push(row: R, depth: Int, out: MutableList<R>, visited: MutableSet<SessionKey>) {
        val key = keyOf(row)
        if (!visited.add(key)) return
        val open = key in expanded
        out += withPosition(row, TreePosition(depth, tree.children(key).size, open, folding = true))
        if (!open) return
        tree.children(key).filter { it in placed }.sortedBy { order.getValue(it) }.forEach { child ->
            push(placed.getValue(child), depth + 1, out, visited)
        }
    }
    return sections.mapNotNull { section ->
        if (!fold || section.collapsed) return@mapNotNull section.copy(rows = section.rows.map(::plain))
        val rows = mutableListOf<R>()
        val visited = HashSet<SessionKey>()
        for (row in section.rows) {
            val parent = tree.parent(keyOf(row))
            if (parent == null || parent !in placed) push(row, 0, rows, visited)
        }
        if (section.rows.isNotEmpty() && rows.isEmpty()) null else section.copy(rows = rows)
    }
}

/** The Needs you rows with their sub-session counts and no fold or expand control. */
fun <R> countSubSessions(needsYou: List<R>, tree: SubSessionTree, keyOf: (R) -> SessionKey, withPosition: (R, TreePosition) -> R): List<R> =
    needsYou.map { withPosition(it, TreePosition(subSessions = tree.children(keyOf(it)).size)) }
