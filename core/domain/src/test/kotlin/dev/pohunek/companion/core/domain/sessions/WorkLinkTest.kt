package dev.pohunek.companion.core.domain.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkLinkTest {
    private fun badge(vararg pairs: Pair<String, String>) = workFacts(mapOf(*pairs))?.let(::workBadge)

    @Test
    fun `work keys win over legacy keys and abbreviate provider and kind`() {
        val badge = badge(
            "work.link.provider" to "github",
            "work.link.kind" to "pull_request",
            "work.link.id" to "12",
            "work.role" to "implementer",
            "link.provider" to "linear",
            "link.id" to "ABC-1",
        )
        assertEquals(WorkBadge("GH PR #12", "implementer"), badge)
    }

    @Test
    fun `legacy keys apply only without any work key and a plain issue omits its kind`() {
        assertEquals(WorkBadge("Linear ABC-123", null), badge("link.provider" to "Linear", "link.kind" to "issue", "link.id" to "ABC-123"))
        assertNull(badge("work.link.kind" to "pr", "link.id" to "9"))
    }

    @Test
    fun `no link id means no badge and no field means no facts`() {
        assertNull(badge("work.role" to "reviewer"))
        assertEquals("reviewer", workFacts(mapOf("work.role" to "reviewer"))?.role)
        assertNull(workFacts(mapOf("other" to "x")))
        assertNull(workFacts(mapOf("work.link.id" to " ‮\u0007 ")))
    }

    @Test
    fun `untrusted values lose control and bidi characters and collapse whitespace`() {
        val badge = badge("work.link.provider" to "weird", "work.link.id" to "A\nB‮\u0007C", "work.role" to "  re​viewer \t two ")
        assertEquals(WorkBadge("weird A BC", "reviewer two"), badge)
    }

    @Test
    fun `clipping counts characters and never splits a surrogate pair`() {
        assertEquals("abc…", clipDisplay("abcdef", 4))
        assertEquals("abcd", clipDisplay("abcd", 4))
        assertEquals("a😀…", clipDisplay("a😀😀😀", 3))
        assertEquals("ab…ef", clipMiddle("abcdef", 5))
        assertEquals("abcdef", clipMiddle("abcdef", 6))
    }

    @Test
    fun `the first link key present decides and an unsafe value gives no fallback`() {
        val safe = "https://example.invalid/pull/1"
        assertEquals(safe, openableWorkLink(mapOf("work.link.url" to safe, "link.url" to "https://other.invalid")))
        assertEquals(safe, openableWorkLink(mapOf("link.url" to safe)))
        assertNull(openableWorkLink(mapOf("work.link.url" to "javascript:alert(1)", "link.url" to safe)))
        assertNull(openableWorkLink(emptyMap()))
    }

    @Test
    fun `only printable ascii http and https urls with a host are openable`() {
        listOf("http://host", "HTTPS://Host:8080/a?b#c", "https://user@host/", "https://[::1]/x").forEach { assertTrue(it, isOpenableUrl(it)) }
        listOf(
            "javascript:alert(1)",
            "data:text/html,x",
            "file:///etc/passwd",
            "intent://host#Intent;end",
            "ftp://host/",
            "https://",
            "https:///path",
            "https://:80/",
            "https://user@/",
            "https://host\\evil",
            "https://host/ space",
            "https://host/\u0007",
            "https://host/‮",
            "https://höst/",
            "//host/path",
            "",
        ).forEach { assertFalse(it, isOpenableUrl(it)) }
    }

    @Test
    fun `a url of exactly the maximum length is openable and one more is not`() {
        val prefix = "https://h/"
        assertTrue(isOpenableUrl(prefix + "a".repeat(OPENABLE_URL_MAX_BYTES - prefix.length)))
        assertFalse(isOpenableUrl(prefix + "a".repeat(OPENABLE_URL_MAX_BYTES - prefix.length + 1)))
    }
}
