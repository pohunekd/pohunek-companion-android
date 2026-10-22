package dev.pohunek.companion.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachWireTest {
    private val streamId = "a-1"

    private fun errorLine(id: String = streamId) =
        """{"v":4,"id":"$id","err":{"class":"runtime","code":"attach_expired","msg":"the attach stream id expired"}}"""

    @Test
    fun `attach request carries the view geometry as initial dimensions`() {
        assertEquals(
            """{"v":{"minimum":4,"maximum":4},"id":"r1","method":"session.attach","params":{"session_id":"s-1","initial_dimensions":{"cols":80,"rows":24}}}""",
            Requests.sessionAttach("r1", "s-1", TerminalDimensions(80, 24)).toLine(),
        )
        assertEquals(
            """{"v":{"minimum":4,"maximum":4},"id":"r2","method":"session.attach","params":{"session_id":"s-1"}}""",
            Requests.sessionAttach("r2", "s-1", null).toLine(),
        )
    }

    @Test
    fun `resize and detach requests are emitted as documented`() {
        assertEquals(
            """{"v":{"minimum":4,"maximum":4},"id":"r3","method":"session.resize","params":{"session_id":"s-1","cols":100,"rows":30}}""",
            Requests.sessionResize("r3", "s-1", TerminalDimensions(100, 30)).toLine(),
        )
        assertEquals(
            """{"v":{"minimum":4,"maximum":4},"id":"r4","method":"session.detach","params":{"stream_id":"a-1"}}""",
            Requests.sessionDetach("r4", "a-1").toLine(),
        )
    }

    @Test
    fun `terminal dimensions outside the 16-bit cell range are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TerminalDimensions(0, 24) }
        assertThrows(IllegalArgumentException::class.java) { TerminalDimensions(80, 65_536) }
    }

    @Test
    fun `attach and detach results decode`() {
        assertEquals(SessionAttachResult("a-1"), Wire.json.decodeFromString(SessionAttachResult.serializer(), """{"stream_id":"a-1"}"""))
        assertEquals(SessionDetachResult(true), Wire.json.decodeFromString(SessionDetachResult.serializer(), """{"detached":true}"""))
        val failed = Wire.json.decodeFromString(
            SessionDetachResult.serializer(),
            """{"detached":false,"error":{"class":"runtime","code":"worker_attach_stream_failed","msg":"stream failed"}}""",
        )
        assertEquals("worker_attach_stream_failed", failed.error?.code)
    }

    @Test
    fun `prelude is exactly one attach field and a newline`() {
        assertEquals("""{"attach":"a-1"}""" + "\n", AttachRedemption.prelude("a-1").toString(Charsets.UTF_8))
        assertEquals("""{"attach":"a\"b"}""" + "\n", AttachRedemption.prelude("a\"b").toString(Charsets.UTF_8))
    }

    @Test
    fun `a complete error line is the redemption failure`() {
        val failure = AttachRedemption.failureIn((errorLine() + "\n").toByteArray(), streamId)
        assertTrue(failure is ProtocolClientException.Daemon)
        assertEquals("attach_expired", (failure as ProtocolClientException.Daemon).error.code)
    }

    @Test
    fun `an error line with CRLF is the redemption failure`() {
        assertTrue(AttachRedemption.failureIn((errorLine() + "\r\n").toByteArray(), streamId) is ProtocolClientException.Daemon)
    }

    @Test
    fun `an error answering another stream id is not accepted as a redemption answer`() {
        assertTrue(AttachRedemption.failureIn((errorLine("a-2") + "\n").toByteArray(), streamId) is ProtocolClientException.ResponseIdMismatch)
    }

    @Test
    fun `terminal bytes are never taken for a failure`() {
        val repaint = "\u001b[H\u001b[2Jprompt$ \r\n".toByteArray()
        assertNull(AttachRedemption.failureIn(repaint, streamId))
        assertNull(AttachRedemption.failureIn(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x0a), streamId))
        assertNull(AttachRedemption.failureIn(errorLine().toByteArray(), streamId))
        assertNull(AttachRedemption.failureIn(ByteArray(0), streamId))
        assertNull(AttachRedemption.failureIn("""{"note":"not a response"}""".toByteArray() + 0x0a, streamId))
    }

    @Test
    fun `only a chunk that opens an object without a newline waits for more bytes`() {
        assertTrue(AttachRedemption.mayBeResponse("""{"v":4""".toByteArray()))
        assertTrue(!AttachRedemption.mayBeResponse("""{"v":4}""".toByteArray() + 0x0a))
        assertTrue(!AttachRedemption.mayBeResponse("\u001b[H".toByteArray()))
        assertArrayEquals(buildJsonObject { put("attach", "x") }.toString().toByteArray() + 0x0a, AttachRedemption.prelude("x"))
    }
}
