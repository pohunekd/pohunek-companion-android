// Ported to Kotlin from Pohunek `crates/daemon/src/session/attention_isolation.rs` (isolating_ancestor) at v0.43.0.
// Repository: https://github.com/zajca/pohunek, MIT. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.SessionInfo

/** The reserved metadata key a parent session sets to withhold the attention of its descendants. */
const val ATTENTION_CHILDREN_KEY = "pohunek.attention.children"

/** The only value of [ATTENTION_CHILDREN_KEY] the daemon accepts. */
const val ATTENTION_CHILDREN_ISOLATE = "isolate"

/**
 * The id of the nearest ancestor of [session] that flags [ATTENTION_CHILDREN_KEY] as [ATTENTION_CHILDREN_ISOLATE], walking
 * `spawned_by.session_id` links, or null when none does. Only the session id is matched, as the daemon does. [lookup] resolves an id
 * among the same host's current sessions; a broken chain or a cycle ends the walk with null. The session's own flag never isolates
 * itself.
 */
fun isolatingAncestor(session: SessionInfo, lookup: (String) -> SessionInfo?): String? {
    val visited = hashSetOf(session.id)
    var current = session.spawnedBy?.sessionId
    var found: String? = null
    while (current != null && found == null && visited.add(current)) {
        val ancestor = lookup(current)
        if (ancestor?.metadata?.get(ATTENTION_CHILDREN_KEY) == ATTENTION_CHILDREN_ISOLATE) found = ancestor.id
        current = ancestor?.spawnedBy?.sessionId
    }
    return found
}
