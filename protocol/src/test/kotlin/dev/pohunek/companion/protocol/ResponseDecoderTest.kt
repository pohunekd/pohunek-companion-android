package dev.pohunek.companion.protocol

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseDecoderTest {
    private fun ok(
        v: Int,
        id: String = "r1",
        result: String = """{"status":"ok","daemon_version":"0.41.0","protocol_version":5}""",
    ) = """{"v":$v,"id":"$id","ok":$result}"""

    private inline fun <reified T : ProtocolClientException> failure(line: String, decoder: ResponseDecoder = ResponseDecoder(), id: String = "r1"): T =
        assertThrows(T::class.java) { decoder.decode(line, id) }

    @Test
    fun `first response fixes the version and the result decodes`() {
        val decoder = ResponseDecoder()
        val health = decoder.decode(ok(5), "r1").decodeResult(DaemonHealth.serializer())
        assertEquals(DaemonHealth("ok", "0.41.0", 5), health)
        assertEquals(5, decoder.selectedVersion)
    }

    @Test
    fun `unknown fields in a result are ignored`() {
        val result = """{"status":"ok","daemon_version":"0.41.0","protocol_version":5,"future":{"x":1}}"""
        assertEquals("ok", ResponseDecoder().decode(ok(5, result = result), "r1").decodeResult(DaemonHealth.serializer()).status)
    }

    @Test
    fun `a selected version outside the offered range is a version mismatch`() {
        val low = failure<ProtocolClientException.VersionMismatch>(ok(4))
        assertEquals(4, low.daemonVersion)
        assertNull(low.error)
        assertEquals(6, failure<ProtocolClientException.VersionMismatch>(ok(6)).daemonVersion)
    }

    @Test
    fun `a version inside a wider offered range is accepted and then fixed`() {
        val decoder = ResponseDecoder(ProtocolVersionRange(5, 6))
        decoder.decode(ok(5), "r1")
        assertEquals(5, decoder.selectedVersion)
        decoder.decode(ok(5, "r2"), "r2")
    }

    @Test
    fun `a version that changes after the first response is a version mismatch`() {
        val decoder = ResponseDecoder(ProtocolVersionRange(5, 6))
        decoder.decode(ok(6), "r1")
        assertEquals(5, failure<ProtocolClientException.VersionMismatch>(ok(5, "r2"), decoder, "r2").daemonVersion)
    }

    @Test
    fun `daemon version_mismatch error is a typed mismatch even when its v is outside the offer`() {
        val line = """{"v":4,"id":"r1","err":{"class":"daemon","code":"version_mismatch","msg":"no overlap","recover":"upgrade"}}"""
        val failure = failure<ProtocolClientException.VersionMismatch>(line)
        assertEquals(ProtocolError(ErrorClass.Daemon, "version_mismatch", "no overlap", "upgrade"), failure.error)
        assertEquals(Wire.CLIENT_RANGE, failure.offered)
    }

    @Test
    fun `the refusal of a protocol 4 daemon to a protocol 5 request is a typed mismatch`() {
        val line = Fixtures.captured("version-mismatch-protocol-4-response").trim()
        val failure = failure<ProtocolClientException.VersionMismatch>(line, id = "req-1")
        assertEquals("version_mismatch", failure.error?.code)
        assertEquals(Wire.CLIENT_RANGE, failure.offered)
    }

    @Test
    fun `other daemon errors keep class code message and recover hint`() {
        val decoder = ResponseDecoder()
        val failure = failure<ProtocolClientException.Daemon>(Fixtures.text("response-err-with-recover"), decoder, "req-fixture-1")
        assertEquals(ErrorClass.Runtime, failure.error.errorClass)
        assertEquals("agent_binary_missing", failure.error.code)
        assertEquals("agent binary not found on PATH: codex", failure.error.msg)
        assertTrue(failure.error.recover.orEmpty().startsWith("install the codex CLI"))
        assertEquals(5, decoder.selectedVersion)
    }

    @Test
    fun `error fixtures round-trip`() {
        for (name in listOf("protocol-error-with-recover", "protocol-error-without-recover")) {
            val fixture = Fixtures.json(name)
            val error = Wire.json.decodeFromJsonElement(ProtocolError.serializer(), fixture)
            assertEquals(name, fixture, Wire.json.encodeToJsonElement(ProtocolError.serializer(), error))
        }
    }

    @Test
    fun `a response for another request poisons with an id mismatch`() {
        val mismatch = failure<ProtocolClientException.ResponseIdMismatch>(ok(5, id = "other"))
        assertEquals("r1", mismatch.expected)
        assertEquals("other", mismatch.actual)
    }

    @Test
    fun `envelopes that are not exactly one of ok and err are malformed`() {
        val lines = listOf(
            "not json",
            "[1]",
            """{"v":5,"id":"r1"}""",
            """{"v":5,"id":"r1","ok":{},"err":{"class":"daemon","code":"x","msg":"y"}}""",
            """{"v":0,"id":"r1","ok":{}}""",
            """{"v":"4","id":"r1","ok":{}}""",
            """{"v":5,"ok":{}}""",
        )
        for (line in lines) failure<ProtocolClientException.MalformedResponse>(line)
    }

    @Test
    fun `a result of the wrong shape is malformed`() {
        assertThrows(ProtocolClientException.MalformedResponse::class.java) {
            ResponseDecoder().decode(ok(5, result = """{"status":"ok"}"""), "r1").decodeResult(DaemonHealth.serializer())
        }
    }

    @Test
    fun `a session with an unmodelled enum value does not fail the list that carries it`() {
        val running = Fixtures.text("session-info-minimal")
        val levitating = running.replace("\"running\"", "\"levitating\"")
        val sessions = ResponseDecoder().decode(ok(5, "r2", "[$running,$levitating]"), "r2").decodeResult(sessionListSerializer)
        assertEquals(listOf(SessionState.Running, SessionState.Unknown), sessions.map { it.state })
    }

    @Test
    fun `an error class this client does not model still surfaces as the daemon error`() {
        val error = failure<ProtocolClientException.Daemon>("""{"v":5,"id":"r1","err":{"class":"unheard-of","code":"x","msg":"y"}}""").error
        assertEquals(ErrorClass.Unknown, error.errorClass)
        assertEquals("x", error.code)
    }

    @Test
    fun `a placeholder for an unmodelled value is never sent`() {
        assertThrows(IllegalArgumentException::class.java) { SessionFilter.state(SessionState.Unknown) }
        assertThrows(SerializationException::class.java) {
            Wire.json.encodeToString(NotificationListParams.serializer(), NotificationListParams(kind = NotificationKind.Unknown))
        }
    }
}
