package dev.pohunek.companion.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposingTextTest {
    private val composing = ComposingText()

    @Test
    fun `typing a word sends each character once and a commit sends only the new tail`() {
        assertEquals(TextEdit(0, "a"), composing.update("a"))
        assertEquals(TextEdit(0, "h"), composing.update("ah"))
        assertEquals(TextEdit(0, "oj"), composing.update("ahoj"))
        assertEquals(TextEdit(0, " "), composing.commit("ahoj "))
        assertEquals(TextEdit(0, "x"), composing.update("x"))
    }

    @Test
    fun `a committed character without a composition is sent once`() {
        assertEquals(TextEdit(0, "ě"), composing.commit("ě"))
        assertEquals(TextEdit(0, "ě"), composing.commit("ě"))
    }

    @Test
    fun `a replaced tail is erased by one backspace per code point`() {
        composing.update("ahoe")
        assertEquals(TextEdit(1, "ě"), composing.update("ahoě"))
        assertEquals(TextEdit(1, ""), composing.update("aho"))
        assertEquals(TextEdit(3, ""), composing.update(""))
    }

    @Test
    fun `a surrogate pair is one code point`() {
        composing.update("a😀")
        assertEquals(TextEdit(1, "b"), composing.update("ab"))
    }

    @Test
    fun `deleting before the cursor takes the composing text first and erases the rest`() {
        composing.update("ab")
        assertEquals(TextEdit(3, ""), composing.deleteBefore(3))
        assertEquals(TextEdit(0, "c"), composing.update("c"))
    }

    @Test
    fun `finishing a composition keeps what was sent and starts a new one`() {
        composing.update("ahoj")
        composing.clear()
        assertEquals(TextEdit(0, "x"), composing.update("x"))
    }

    @Test
    fun `a chord that took the place of a character is not erased`() {
        composing.update("c")
        composing.markNotErasable(1)
        assertEquals(TextEdit(0, "d"), composing.update("cd"))
        assertEquals(TextEdit(1, ""), composing.update(""))
    }
}
