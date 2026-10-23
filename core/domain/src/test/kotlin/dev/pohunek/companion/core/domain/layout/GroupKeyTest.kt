package dev.pohunek.companion.core.domain.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupKeyTest {
    private fun parse(value: String?, max: Int = 64) =
        parseGroupValue(value?.let { mapOf(GROUP_METADATA_KEY to it) }, max)

    @Test
    fun `a missing key is absent`() {
        assertEquals(GroupValue.Absent, parseGroupValue(null, 64))
        assertEquals(GroupValue.Absent, parseGroupValue(mapOf("other" to "x"), 64))
    }

    @Test
    fun `a value is trimmed and keeps its spelling`() {
        assertEquals(GroupValue.Present("Night shift"), parse("  Night shift \n"))
    }

    @Test
    fun `an empty or blank value is rejected`() {
        assertEquals(GroupValue.Rejected(GroupNameProblem.Empty), parse(""))
        assertEquals(GroupValue.Rejected(GroupNameProblem.Empty), parse(" \t "))
    }

    @Test
    fun `the limit counts utf-8 bytes not characters`() {
        assertEquals(GroupValue.Present("abcd"), parse("abcd", max = 4))
        assertEquals(GroupValue.Rejected(GroupNameProblem.TooLong), parse("abcde", max = 4))
        assertEquals(GroupValue.Present("ěš"), parse("ěš", max = 4))
        assertEquals(GroupValue.Rejected(GroupNameProblem.TooLong), parse("ěšč", max = 4))
    }

    @Test
    fun `control and bidirectional format characters are rejected`() {
        assertEquals(GroupValue.Rejected(GroupNameProblem.ControlCharacter), parse("a\u0000b"))
        assertEquals(GroupValue.Rejected(GroupNameProblem.ControlCharacter), parse("a\tb"))
        assertEquals(GroupValue.Rejected(GroupNameProblem.ControlCharacter), parse("a\u007fb"))
        assertEquals(GroupValue.Rejected(GroupNameProblem.ControlCharacter), parse("a‮b"))
        assertEquals(GroupValue.Rejected(GroupNameProblem.ControlCharacter), parse("a⁦b"))
    }
}
