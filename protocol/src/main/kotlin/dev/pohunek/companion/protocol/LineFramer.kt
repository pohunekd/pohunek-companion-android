package dev.pohunek.companion.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Newline-delimited UTF-8 framing of the control stream, independent of how the TCP reads split or join the bytes. */
class LineFramer {
    private val pending = ByteArrayOutputStream()

    /** Appends [length] bytes of [chunk] and returns the lines that are complete now, without their newline. */
    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): List<String> {
        val lines = ArrayList<String>()
        var position = offset
        val end = offset + length
        while (position < end) {
            var newline = position
            while (newline < end && chunk[newline] != NEWLINE) newline++
            appendBounded(chunk, position, newline - position)
            if (newline == end) break
            lines += decode(pending.toByteArray())
            pending.reset()
            position = newline + 1
        }
        return lines
    }

    /** At end of stream: the unterminated last line, if one is pending. */
    fun finish(): String? {
        if (pending.size() == 0) return null
        val line = decode(pending.toByteArray())
        pending.reset()
        return line
    }

    private fun appendBounded(chunk: ByteArray, offset: Int, length: Int) {
        if (pending.size().toLong() + length > Wire.MAX_CONTROL_LINE_BYTES) throw ProtocolClientException.Framing("control line exceeded maximum length")
        pending.write(chunk, offset, length)
    }

    private fun decode(bytes: ByteArray): String {
        val content = if (bytes.isNotEmpty() && bytes.last() == CARRIAGE_RETURN) bytes.copyOf(bytes.size - 1) else bytes
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString()
        } catch (e: CharacterCodingException) {
            throw ProtocolClientException.Framing("invalid utf-8 control line: ${e.message}", e)
        }
    }

    companion object {
        private const val NEWLINE: Byte = 0x0a
        private const val CARRIAGE_RETURN: Byte = 0x0d

        /** The UTF-8 bytes of [line] followed by the newline delimiter. */
        fun encode(line: String): ByteArray {
            val bytes = line.toByteArray(Charsets.UTF_8)
            if (bytes.size > Wire.MAX_CONTROL_LINE_BYTES) throw ProtocolClientException.Framing("control line exceeded maximum length")
            return bytes + NEWLINE
        }
    }
}
