package dev.pohunek.companion.debug

import java.io.ByteArrayOutputStream

private const val BYTE_MASK = 0xff
private const val DEL_BYTE = 0x7f
private const val CR_BYTE = '\r'.code
private const val LF_BYTE = '\n'.code
private const val BS_BYTE = '\b'.code
private const val SPACE_BYTE = ' '.code
private const val CARET_BASE = '@'.code
private const val FIRST_PRINTABLE = 0x20
private const val SGR_INVERSE_ON = "\u001b[7m"
private const val SGR_INVERSE_OFF = "\u001b[27m"

/**
 * Turns keyboard input into what a terminal line discipline would echo: Enter starts a new line,
 * Backspace erases the previous cell, and other control bytes (Esc, Tab, Ctrl chords, the lead bytes of
 * arrow keys) show up in caret notation, highlighted. Every other byte, including UTF-8 sequences, is fed back unchanged.
 */
internal fun echoBytes(input: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(input.size)
    for (byte in input) {
        val value = byte.toInt() and BYTE_MASK
        when {
            value == CR_BYTE -> out.write(byteArrayOf(CR_BYTE.toByte(), LF_BYTE.toByte()))
            value == DEL_BYTE || value == BS_BYTE -> out.write(byteArrayOf(BS_BYTE.toByte(), SPACE_BYTE.toByte(), BS_BYTE.toByte()))
            value < FIRST_PRINTABLE -> {
                out.write(SGR_INVERSE_ON.toByteArray())
                out.write(byteArrayOf('^'.code.toByte(), (value + CARET_BASE).toByte()))
                out.write(SGR_INVERSE_OFF.toByteArray())
            }
            else -> out.write(value)
        }
    }
    return out.toByteArray()
}
