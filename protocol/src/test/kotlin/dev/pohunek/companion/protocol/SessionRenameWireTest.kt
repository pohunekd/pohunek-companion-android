package dev.pohunek.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionRenameWireTest {
    private fun capturedLine(name: String): String =
        (Fixtures::class.java.getResourceAsStream("/captured/$name.jsonl") ?: error("missing captured line $name")).use {
            it.readBytes().toString(Charsets.UTF_8).trim()
        }

    @Test
    fun `rename request is the line the daemon accepted`() {
        assertEquals(capturedLine("session-rename-request"), Requests.sessionRename("req-1", "s-1", "renamed-agent").toLine())
    }

    @Test
    fun `clearing the name omits it as in the line the daemon accepted`() {
        assertEquals(capturedLine("session-rename-clear-request"), Requests.sessionRename("req-2", "s-1", null).toLine())
    }

    @Test
    fun `captured rename response decodes to the renamed session`() {
        val ok = ResponseDecoder().decode(Fixtures.captured("session-rename-response").trim(), "req-1")
        val session = ok.decodeResult(SessionRenameResult.serializer()).session
        assertEquals("renamed-agent", session.name)
    }

    @Test
    fun `captured clear response has no name`() {
        val ok = ResponseDecoder().decode(Fixtures.captured("session-rename-clear-response").trim(), "req-2")
        assertNull(ok.decodeResult(SessionRenameResult.serializer()).session.name)
    }

    @Test
    fun `captured refusal of a control character is a daemon error with the daemon text`() {
        val failure = assertThrows(ProtocolClientException.Daemon::class.java) {
            ResponseDecoder().decode(Fixtures.captured("session-rename-control-char-error").trim(), "req-3")
        }
        assertEquals("bad_request", failure.error.code)
        assertEquals("session name must not contain control characters", failure.error.msg)
    }
}
