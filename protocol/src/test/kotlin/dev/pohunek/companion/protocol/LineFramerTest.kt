package dev.pohunek.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LineFramerTest {
    private val payload = "{\"a\":1}\n{\"b\":\"žluťoučký 🐴\"}\n{\"c\":3}\n".toByteArray(Charsets.UTF_8)
    private val expected = listOf("{\"a\":1}", "{\"b\":\"žluťoučký 🐴\"}", "{\"c\":3}")

    private fun framed(chunks: List<ByteArray>): List<String> {
        val framer = LineFramer()
        return chunks.flatMap { framer.feed(it) }
    }

    @Test
    fun `joined reads yield every line`() {
        assertEquals(expected, framed(listOf(payload)))
    }

    @Test
    fun `every single split point yields the same lines, including inside a multi-byte character`() {
        for (cut in 1 until payload.size) {
            assertEquals("split at $cut", expected, framed(listOf(payload.copyOfRange(0, cut), payload.copyOfRange(cut, payload.size))))
        }
    }

    @Test
    fun `one byte per read yields the same lines`() {
        assertEquals(expected, framed(payload.map { byteArrayOf(it) }))
    }

    @Test
    fun `carriage return before the newline is stripped`() {
        assertEquals(listOf("x"), framed(listOf("x\r\n".toByteArray())))
    }

    @Test
    fun `a line of exactly the cap is accepted and one byte more is rejected`() {
        val atCap = ByteArray(Wire.MAX_CONTROL_LINE_BYTES) { 'a'.code.toByte() }
        assertEquals(Wire.MAX_CONTROL_LINE_BYTES, framed(listOf(atCap, byteArrayOf(0x0a))).single().length)
        assertThrows(ProtocolClientException.Framing::class.java) { LineFramer().feed(ByteArray(Wire.MAX_CONTROL_LINE_BYTES + 1) { 'a'.code.toByte() }) }
    }

    @Test
    fun `the cap counts a line across reads and not the whole stream`() {
        val framer = LineFramer()
        val half = ByteArray(Wire.MAX_CONTROL_LINE_BYTES / 2 + 1) { 'a'.code.toByte() }
        framer.feed(half + byteArrayOf(0x0a))
        framer.feed(half)
        assertThrows(ProtocolClientException.Framing::class.java) { framer.feed(half) }
    }

    @Test
    fun `invalid utf-8 is a framing error and not a replacement character`() {
        assertThrows(ProtocolClientException.Framing::class.java) { LineFramer().feed(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x0a)) }
    }

    @Test
    fun `an unterminated last line is returned at end of stream`() {
        val framer = LineFramer()
        assertEquals(emptyList<String>(), framer.feed("{\"a\"".toByteArray()))
        assertEquals("{\"a\"", framer.finish())
        assertNull(framer.finish())
    }

    @Test
    fun `encode appends a newline and rejects an oversized line`() {
        assertEquals(listOf<Byte>(0x78, 0x0a), LineFramer.encode("x").toList())
        assertThrows(ProtocolClientException.Framing::class.java) { LineFramer.encode("a".repeat(Wire.MAX_CONTROL_LINE_BYTES + 1)) }
    }
}
