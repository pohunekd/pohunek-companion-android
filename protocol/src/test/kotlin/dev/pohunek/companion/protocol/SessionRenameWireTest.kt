package dev.pohunek.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionRenameWireTest {
    @Test
    fun `rename request carries the name`() {
        assertEquals(
            """{"v":{"minimum":4,"maximum":4},"id":"android-1","method":"session.rename","params":{"session_id":"s-1","name":"alpha"}}""",
            Requests.sessionRename("android-1", "s-1", "alpha").toLine(),
        )
    }

    @Test
    fun `clearing the name omits it`() {
        assertEquals(
            """{"v":{"minimum":4,"maximum":4},"id":"android-2","method":"session.rename","params":{"session_id":"s-1"}}""",
            Requests.sessionRename("android-2", "s-1", null).toLine(),
        )
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
