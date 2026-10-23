package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.CapabilityRefusals
import dev.pohunek.companion.protocol.RuntimeState
import dev.pohunek.companion.protocol.SessionCapabilities
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionRuntime
import dev.pohunek.companion.protocol.SessionState
import dev.pohunek.companion.protocol.StateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeOfferTest {
    private fun info(
        state: SessionState = SessionState.Stopped,
        resume: Boolean = true,
        refusal: String? = null,
        external: Boolean? = null,
        runtime: SessionRuntime? = null,
    ) = SessionInfo(
        id = "s-1",
        external = external,
        capabilities = SessionCapabilities(resume = resume, fork = false),
        capabilityRefusals = refusal?.let { CapabilityRefusals(resume = it) },
        agent = "claude",
        agentBase = "claude",
        cwd = "/work",
        pid = 1,
        runtime = runtime,
        cols = 80,
        rows = 24,
        state = state,
        stateSource = StateSource.Screen,
        createdAt = "2026-01-01T09:00:00Z",
        updatedAt = "2026-01-01T10:00:00Z",
    )

    @Test
    fun `an ended session with the capability is available`() {
        assertEquals(ResumeOffer.Available, resumeOffer(info()))
        assertEquals(ResumeOffer.Available, resumeOffer(info(state = SessionState.Failed)))
    }

    @Test
    fun `a running session with a lost runtime counts as ended`() {
        val lost = SessionRuntime(RuntimeState.Lost, runtimeGeneration = "1", workerInstanceId = "w-1")
        assertEquals(ResumeOffer.Available, resumeOffer(info(state = SessionState.Running, runtime = lost)))
    }

    @Test
    fun `live and external sessions have nothing to resume`() {
        assertEquals(ResumeOffer.NotApplicable, resumeOffer(info(state = SessionState.Running)))
        assertEquals(ResumeOffer.NotApplicable, resumeOffer(info(state = SessionState.Starting)))
        assertEquals(ResumeOffer.NotApplicable, resumeOffer(info(external = true)))
    }

    @Test
    fun `a refusal code of the snapshot names the reason`() {
        val offer = resumeOffer(info(resume = false, refusal = "agent_binary_missing"))
        assertEquals(ResumeOffer.Refused("The agent program can no longer be found."), offer)
    }

    @Test
    fun `a cleared capability without a code is not resumable`() {
        assertEquals(ResumeOffer.Refused(resumeRefusalSentence("not_resumable")), resumeOffer(info(resume = false)))
        assertEquals(resumeRefusalSentence("not_resumable"), resumeRefusalSentence("agent_not_resumable"))
    }

    @Test
    fun `a changed profile names the command to run on the host`() {
        val sentence = resumeRefusalSentence(PROFILE_CHANGED_CODE)
        assertTrue(sentence.contains("pohunek session resume"))
        assertEquals(sentence, resumeRefusalSentence(PROFILE_CHANGE_LOCAL_ONLY_CODE))
    }

    @Test
    fun `an unknown code is cleaned into the sentence`() {
        val sentence = resumeRefusalSentence("new_code\u001b[0m")
        assertTrue(sentence.contains("new_code"))
        assertFalse(sentence.contains('\u001b'))
    }
}
