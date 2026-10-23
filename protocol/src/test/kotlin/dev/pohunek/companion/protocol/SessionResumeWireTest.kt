package dev.pohunek.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionResumeWireTest {
    private fun capturedLine(name: String): String =
        (Fixtures::class.java.getResourceAsStream("/captured/$name.jsonl") ?: error("missing captured line $name")).use {
            it.readBytes().toString(Charsets.UTF_8).trim()
        }

    @Test
    fun `resume request is the bare session id the daemon parsed`() {
        assertEquals(capturedLine("session-resume-request"), Requests.sessionResume("req-1", "s-1").toLine())
    }

    @Test
    fun `captured refusal is a runtime error with the daemon code`() {
        val failure = assertThrows(ProtocolClientException.Daemon::class.java) {
            ResponseDecoder().decode(Fixtures.captured("session-resume-not-resumable-error").trim(), "req-1")
        }
        assertEquals("agent_not_resumable", failure.error.code)
        assertEquals(ErrorClass.Runtime, failure.error.errorClass)
    }
}
