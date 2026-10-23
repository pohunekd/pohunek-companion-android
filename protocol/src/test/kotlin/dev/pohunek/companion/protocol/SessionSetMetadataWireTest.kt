package dev.pohunek.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSetMetadataWireTest {
    private fun capturedLine(name: String): String =
        (Fixtures::class.java.getResourceAsStream("/captured/$name.jsonl") ?: error("missing captured line $name")).use {
            it.readBytes().toString(Charsets.UTF_8).trim()
        }

    @Test
    fun `setting a key is the line the daemon accepted`() {
        val line = Requests.sessionSetMetadata("req-1", "s-1", mapOf("companion.group" to "Reviews")).toLine()
        assertEquals(capturedLine("session-set-metadata-request"), line)
    }

    @Test
    fun `removing a key sends JSON null as in the line the daemon accepted`() {
        val line = Requests.sessionSetMetadata("req-2", "s-1", mapOf("companion.group" to null)).toLine()
        assertEquals(capturedLine("session-set-metadata-clear-request"), line)
    }

    @Test
    fun `captured response carries the session with the key`() {
        val ok = ResponseDecoder().decode(Fixtures.captured("session-set-metadata-response").trim(), "req-1")
        val session = ok.decodeResult(SessionSetMetadataResult.serializer()).session
        assertEquals("Reviews", session.metadata?.get("companion.group"))
    }

    @Test
    fun `captured response after removing the key has no metadata`() {
        val ok = ResponseDecoder().decode(Fixtures.captured("session-set-metadata-clear-response").trim(), "req-2")
        assertNull(ok.decodeResult(SessionSetMetadataResult.serializer()).session.metadata)
    }
}
