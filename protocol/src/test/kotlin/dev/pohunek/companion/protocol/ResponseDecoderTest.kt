package dev.pohunek.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseDecoderTest {
    private fun ok(
        v: Int,
        id: String = "r1",
        result: String = """{"status":"ok","daemon_version":"0.35.2","protocol_version":4}""",
    ) = """{"v":$v,"id":"$id","ok":$result}"""

    private inline fun <reified T : ProtocolClientException> failure(line: String, decoder: ResponseDecoder = ResponseDecoder(), id: String = "r1"): T =
        assertThrows(T::class.java) { decoder.decode(line, id) }

    @Test
    fun `first response fixes the version and the result decodes`() {
        val decoder = ResponseDecoder()
        val health = decoder.decode(ok(4), "r1").decodeResult(DaemonHealth.serializer())
        assertEquals(DaemonHealth("ok", "0.35.2", 4), health)
        assertEquals(4, decoder.selectedVersion)
    }

    @Test
    fun `unknown fields in a result are ignored`() {
        val result = """{"status":"ok","daemon_version":"0.35.2","protocol_version":4,"future":{"x":1}}"""
        assertEquals("ok", ResponseDecoder().decode(ok(4, result = result), "r1").decodeResult(DaemonHealth.serializer()).status)
    }

    @Test
    fun `a selected version outside the offered range is a version mismatch`() {
        val low = failure<ProtocolClientException.VersionMismatch>(ok(3))
        assertEquals(3, low.daemonVersion)
        assertNull(low.error)
        assertEquals(5, failure<ProtocolClientException.VersionMismatch>(ok(5)).daemonVersion)
    }

    @Test
    fun `a version that changes after the first response is a version mismatch`() {
        val decoder = ResponseDecoder(ProtocolVersionRange(3, 4))
        decoder.decode(ok(4), "r1")
        assertEquals(3, failure<ProtocolClientException.VersionMismatch>(ok(3, "r2"), decoder, "r2").daemonVersion)
    }

    @Test
    fun `daemon version_mismatch error is a typed mismatch even when its v is outside the offer`() {
        val line = """{"v":5,"id":"r1","err":{"class":"daemon","code":"version_mismatch","msg":"no overlap","recover":"upgrade"}}"""
        val failure = failure<ProtocolClientException.VersionMismatch>(line)
        assertEquals(ProtocolError(ErrorClass.Daemon, "version_mismatch", "no overlap", "upgrade"), failure.error)
        assertEquals(Wire.CLIENT_RANGE, failure.offered)
    }

    @Test
    fun `other daemon errors keep class code message and recover hint`() {
        val decoder = ResponseDecoder()
        val failure = failure<ProtocolClientException.Daemon>(Fixtures.text("response-err-with-recover"), decoder, "req-fixture-1")
        assertEquals(ErrorClass.Runtime, failure.error.errorClass)
        assertEquals("agent_binary_missing", failure.error.code)
        assertEquals("agent binary not found on PATH: codex", failure.error.msg)
        assertTrue(failure.error.recover!!.startsWith("install the codex CLI"))
        assertEquals(4, decoder.selectedVersion)
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
        val mismatch = failure<ProtocolClientException.ResponseIdMismatch>(ok(4, id = "other"))
        assertEquals("r1", mismatch.expected)
        assertEquals("other", mismatch.actual)
    }

    @Test
    fun `envelopes that are not exactly one of ok and err are malformed`() {
        val lines = listOf(
            "not json",
            "[1]",
            """{"v":4,"id":"r1"}""",
            """{"v":4,"id":"r1","ok":{},"err":{"class":"daemon","code":"x","msg":"y"}}""",
            """{"v":0,"id":"r1","ok":{}}""",
            """{"v":"4","id":"r1","ok":{}}""",
            """{"v":4,"ok":{}}""",
            """{"v":4,"id":"r1","err":{"class":"unheard-of","code":"x","msg":"y"}}""",
        )
        for (line in lines) failure<ProtocolClientException.MalformedResponse>(line)
    }

    @Test
    fun `a result of the wrong shape or with an unknown enum value is malformed`() {
        val decoder = ResponseDecoder()
        assertThrows(ProtocolClientException.MalformedResponse::class.java) {
            decoder.decode(ok(4, result = """{"status":"ok"}"""), "r1").decodeResult(DaemonHealth.serializer())
        }
        val badState = Fixtures.text("session-info-minimal").replace("\"running\"", "\"levitating\"")
        assertThrows(ProtocolClientException.MalformedResponse::class.java) {
            decoder.decode(ok(4, "r2", "[$badState]"), "r2").decodeResult(sessionListSerializer)
        }
    }
}
