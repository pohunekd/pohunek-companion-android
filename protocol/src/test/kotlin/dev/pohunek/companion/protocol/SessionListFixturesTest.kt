package dev.pohunek.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionListFixturesTest {
    @Test
    fun `session list response fixture decodes through the decoder`() {
        val sessions = ResponseDecoder().decode(Fixtures.text("response-ok-session-list"), "req-fixture-1").decodeResult(sessionListSerializer)
        val session = sessions.single()
        assertEquals("s-fixture-1", session.id)
        assertEquals("TypeScript SDK phase 1", session.name)
        assertEquals(SessionState.Done, session.state)
        assertEquals(StateSource.Report, session.stateSource)
        assertEquals(AgentActivity.Idle, session.activity)
        assertEquals(CwdSource.Osc7, session.cwdSource)
        assertEquals(SessionCapabilities(resume = true, fork = true), session.capabilities)
        assertEquals(mapOf("ticket" to "POH-12", "track" to "S.3"), session.metadata)
        assertEquals(42425L, session.activeAgentPid)
        assertEquals(true, session.isLinkedWorktree)
        assertEquals(0, session.exitCode)
    }

    @Test
    fun `full and minimal session info fixtures decode`() {
        val full = Wire.json.decodeFromJsonElement(SessionInfo.serializer(), Fixtures.json("session-info-full"))
        assertEquals("zajca/12-typescript-sdk", full.branch)
        assertEquals("/workspace/pohunek-sdk", full.worktreePath)
        assertEquals(true, full.outputForceClosed)
        val minimal = Wire.json.decodeFromJsonElement(SessionInfo.serializer(), Fixtures.json("session-info-minimal"))
        assertEquals(SessionState.Running, minimal.state)
        assertEquals(StateSource.Process, minimal.stateSource)
        assertEquals(120, minimal.cols)
        assertNull(minimal.name)
        assertNull(minimal.activity)
        assertNull(minimal.metadata)
        assertNull(minimal.exitCode)
    }
}
