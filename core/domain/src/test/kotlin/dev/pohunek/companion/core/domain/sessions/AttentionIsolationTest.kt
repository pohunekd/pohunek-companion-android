package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.AgentActivity
import dev.pohunek.companion.protocol.DaemonEvent
import dev.pohunek.companion.protocol.SessionCapabilities
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionState
import dev.pohunek.companion.protocol.SpawnedBy
import dev.pohunek.companion.protocol.StateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class AttentionIsolationTest {
    private fun session(id: String, parent: String? = null, flagged: Boolean = false, daemonId: String = "d-1") = SessionInfo(
        id = id,
        capabilities = SessionCapabilities(resume = false, fork = false),
        agent = "claude",
        agentBase = "claude",
        cwd = "/work",
        pid = 1,
        cols = 80,
        rows = 24,
        state = SessionState.Running,
        stateSource = StateSource.Screen,
        activity = AgentActivity.Blocked,
        metadata = if (flagged) mapOf(ATTENTION_CHILDREN_KEY to ATTENTION_CHILDREN_ISOLATE) else null,
        spawnedBy = parent?.let { SpawnedBy(it, daemonId) },
        createdAt = "2026-01-01T09:00:00Z",
        updatedAt = "2026-01-01T10:00:00Z",
    )

    private fun isolatedBy(target: String, vararg all: SessionInfo): String? {
        val byId = all.associateBy { it.id }
        return isolatingAncestor(byId.getValue(target)) { byId[it] }
    }

    @Test
    fun `a child of a flagged parent is isolated by it`() {
        assertEquals("p", isolatedBy("c", session("p", flagged = true), session("c", "p")))
    }

    @Test
    fun `a grandchild is isolated by the nearest flagged ancestor`() {
        val tree = arrayOf(session("root", flagged = true), session("mid", "root", flagged = true), session("leaf", "mid"))
        assertEquals("mid", isolatedBy("leaf", *tree))
        assertEquals("root", isolatedBy("leaf", session("root", flagged = true), session("mid", "root"), session("leaf", "mid")))
    }

    @Test
    fun `the flagged parent is not isolated by its own flag and the daemon id is ignored`() {
        assertNull(isolatedBy("p", session("p", flagged = true)))
        assertEquals("p", isolatedBy("c", session("p", flagged = true), session("c", "p", daemonId = "another-daemon")))
    }

    @Test
    fun `a child of an unflagged parent is not isolated`() {
        assertNull(isolatedBy("c", session("p"), session("c", "p")))
    }

    @Test
    fun `a broken chain is not isolated`() {
        assertNull(isolatedBy("c", session("c", "missing")))
    }

    @Test
    fun `a spawned_by cycle ends and is not isolated`() {
        assertNull(isolatedBy("a", session("a", "b"), session("b", "a")))
        assertNull(isolatedBy("a", session("a", "a")))
    }

    private val store = SessionStore("host-a", TestTimeSource(), 300.seconds)
    private val tree = listOf(session("p", flagged = true), session("c", "p"))

    @Test
    fun `a host without the capability isolates nothing`() {
        store.applySeed(tree, emptyList(), attentionIsolationSupported = false)
        assertTrue(store.ownerSessions().all { it.needsOwner })
    }

    @Test
    fun `an isolated session keeps its owner state but does not need the owner or count as waiting`() {
        store.applySeed(tree, emptyList(), attentionIsolationSupported = true)
        val child = store.ownerSessions().single { it.info.id == "c" }
        assertEquals(OwnerState.NeedsOwner, child.ownerState)
        assertTrue(child.attentionIsolated)
        assertFalse(child.needsOwner)
        assertEquals(OwnerState.Working, child.listState)
        assertTrue(store.ownerSessions().single { it.info.id == "p" }.needsOwner)
    }

    @Test
    fun `clearing the parent flag by a session update lifts the child again`() {
        store.applySeed(tree, emptyList(), attentionIsolationSupported = true)
        store.applyEvent(DaemonEvent.SessionUpdated(session("p").copy(updatedAt = "2026-01-01T11:00:00Z")))
        assertTrue(store.ownerSessions().single { it.info.id == "c" }.needsOwner)
    }
}
