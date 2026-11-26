package dev.pohunek.companion.core.domain.newsession

import dev.pohunek.companion.protocol.SessionCapabilities
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionInitialInput
import dev.pohunek.companion.protocol.SessionInitialInputState
import dev.pohunek.companion.protocol.SessionNewResult
import dev.pohunek.companion.protocol.SessionState
import dev.pohunek.companion.protocol.StateSource
import org.junit.Assert.assertEquals
import org.junit.Test

class CreateOutcomeTest {
    private fun result(applied: Boolean?, state: SessionInitialInputState? = null) = SessionNewResult(
        SessionInfo(
            id = "s-1",
            capabilities = SessionCapabilities(resume = false, fork = false),
            agent = "claude",
            agentBase = "claude",
            cwd = "/work",
            pid = 1,
            cols = 80,
            rows = 24,
            state = SessionState.Running,
            stateSource = StateSource.Screen,
            createdAt = "2026-01-01T09:00:00Z",
            updatedAt = "2026-01-01T10:00:00Z",
            initialInput = state?.let { SessionInitialInput(it) },
        ),
        applied,
    )

    private fun prompt(applied: Boolean?, state: SessionInitialInputState? = null, sent: Boolean = true) =
        createdOutcome(result(applied, state), sentPrompt = sent).prompt

    @Test
    fun `a create without a prompt is delivered whatever the daemon reports`() {
        assertEquals(PromptDelivery.Delivered, prompt(applied = null, sent = false))
        assertEquals(PromptDelivery.Delivered, prompt(applied = true, state = SessionInitialInputState.Pending, sent = false))
    }

    @Test
    fun `a host without initial_input confirms by applied_input alone`() {
        assertEquals(PromptDelivery.Delivered, prompt(applied = true))
    }

    @Test
    fun `a delivered initial_input is delivered`() {
        assertEquals(PromptDelivery.Delivered, prompt(applied = true, state = SessionInitialInputState.Delivered))
    }

    @Test
    fun `a pending initial_input is held`() {
        assertEquals(PromptDelivery.Held, prompt(applied = true, state = SessionInitialInputState.Pending))
    }

    @Test
    fun `a dropped or unmodelled initial_input is unconfirmed`() {
        assertEquals(PromptDelivery.Unconfirmed, prompt(applied = true, state = SessionInitialInputState.Dropped))
        assertEquals(PromptDelivery.Unconfirmed, prompt(applied = true, state = SessionInitialInputState.Unknown))
    }

    @Test
    fun `an absent applied_input is unconfirmed`() {
        assertEquals(PromptDelivery.Unconfirmed, prompt(applied = null))
        assertEquals(PromptDelivery.Unconfirmed, prompt(applied = false, state = SessionInitialInputState.Delivered))
    }
}
