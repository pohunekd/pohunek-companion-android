package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.RuntimeState
import dev.pohunek.companion.protocol.SessionCapabilities
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionRuntime
import dev.pohunek.companion.protocol.SessionState
import dev.pohunek.companion.protocol.StateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionActionsTest {
    private fun info(state: SessionState = SessionState.Running, runtime: RuntimeState? = RuntimeState.Live, external: Boolean? = null) = SessionInfo(
        id = "s-1",
        external = external,
        capabilities = SessionCapabilities(resume = false, fork = false),
        agent = "claude",
        agentBase = "claude",
        cwd = "/work",
        pid = 1,
        runtime = runtime?.let { SessionRuntime(it, "1") },
        cols = 80,
        rows = 24,
        state = state,
        stateSource = StateSource.Screen,
        createdAt = "2026-01-01T09:00:00Z",
        updatedAt = "2026-01-01T10:00:00Z",
    )

    @Test
    fun `a live session offers stop and remove, an ended one only remove`() {
        assertTrue(offersStop(info()))
        assertTrue(offersRemove(info()))
        assertFalse(offersStop(info(SessionState.Stopped, RuntimeState.Terminal)))
        assertTrue(offersRemove(info(SessionState.Done, RuntimeState.Terminal)))
        assertTrue(offersStop(info(runtime = null)))
    }

    @Test
    fun `an external session offers nothing in any state`() {
        assertFalse(offersStop(info(external = true)))
        assertFalse(offersRemove(info(external = true)))
        assertFalse(offersRemove(info(SessionState.Stopped, RuntimeState.Conflict, external = true)))
    }

    @Test
    fun `a conflict is offered stop and remove though the daemon rule refuses, an incompatible runtime only remove`() {
        assertTrue(offersStop(info(runtime = RuntimeState.Conflict)))
        assertTrue(offersRemove(info(runtime = RuntimeState.Conflict)))
        assertFalse(offersStop(info(SessionState.Stopped, RuntimeState.Conflict)))
        assertTrue(offersRemove(info(SessionState.Stopped, RuntimeState.Conflict)))
        assertFalse(offersStop(info(runtime = RuntimeState.Incompatible)))
        assertTrue(offersRemove(info(runtime = RuntimeState.Incompatible)))
        assertTrue(hasConflictingRuntime(info(runtime = RuntimeState.Incompatible)))
        assertFalse(hasConflictingRuntime(info()))
    }

    @Test
    fun `a lost runtime can be removed but not stopped`() {
        assertFalse(offersStop(info(runtime = RuntimeState.Lost)))
        assertTrue(offersRemove(info(runtime = RuntimeState.Lost)))
    }

    @Test
    fun `a refusal reads with the daemon message and recover hint, sanitized`() {
        val refused = SessionActionOutcome.Refused("session_runtime_lost", "runtime\nlost", "run  resume")
        assertEquals("Cannot stop s-1 on box: runtime lost (run resume)", actionFailureText(SessionAction.Stop, actionSubject("s-1", "box"), refused))
        val bare = SessionActionOutcome.Refused("x", "nope", null)
        assertEquals("Cannot remove s-1 on box: nope", actionFailureText(SessionAction.RemoveAnyway, actionSubject("s-1", "box"), bare))
    }

    @Test
    fun `an unknown outcome says the action may still complete and a success has no failure text`() {
        val text = actionFailureText(SessionAction.Remove, "s-1 on box", SessionActionOutcome.Unknown)
        assertTrue(text.orEmpty().contains("outcome is unknown") && text.orEmpty().contains("may still complete"))
        assertNull(actionFailureText(SessionAction.Remove, "s-1 on box", SessionActionOutcome.Done()))
    }

    @Test
    fun `a removal notice lists failed worktrees and accepted processes, and is absent when there is nothing to report`() {
        assertNull(removalNotice("s-1 on box", SessionActionOutcome.Done()))
        val notice = removalNotice("s-1 on box", SessionActionOutcome.Done(2, listOf("42 (node)", "43")))
        assertEquals(
            "Removed s-1 on box, but 2 worktree checkout(s) could not be deleted and need manual cleanup; " +
                "these processes could not be confirmed ended and were not signalled; they may still be running: 42 (node), 43",
            notice,
        )
    }
}
