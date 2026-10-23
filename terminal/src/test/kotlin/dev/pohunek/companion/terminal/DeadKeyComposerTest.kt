package dev.pohunek.companion.terminal

import android.view.KeyCharacterMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeadKeyComposerTest {
    private val caron = KeyCharacterMap.COMBINING_ACCENT or 0x030C
    private val acute = KeyCharacterMap.COMBINING_ACCENT or 0x0301

    @Test
    fun `a dead key combines with the next character`() {
        val keys = DeadKeyComposer()
        assertEquals("", keys.feed(caron))
        assertEquals("č", keys.feed('c'.code))
        assertNull(keys.feed('c'.code))
    }

    @Test
    fun `a modifier press keeps the accent pending`() {
        val keys = DeadKeyComposer()
        keys.feed(acute)
        assertNull(keys.feed(0))
        assertEquals("Á", keys.feed('A'.code))
    }

    @Test
    fun `a character that does not combine is typed alone and a space types the accent`() {
        val keys = DeadKeyComposer()
        keys.feed(acute)
        assertEquals("x", keys.feed('x'.code))
        keys.feed(acute)
        assertEquals(" ́", keys.feed(' '.code))
    }

    @Test
    fun `a control key drops the pending accent`() {
        val keys = DeadKeyComposer()
        keys.feed(acute)
        assertNull(keys.feed('\n'.code))
        assertNull(keys.feed('a'.code))
    }
}
