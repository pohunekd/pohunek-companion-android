package dev.pohunek.companion.core.domain.newsession

import dev.pohunek.companion.core.domain.sessions.SessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class PendingPlacementsTest {
    private fun key(id: String) = SessionKey("127.0.0.1:7001", id)

    private val maxAge = 100.seconds

    @Test
    fun `a present session is applied once and its entry removed`() {
        val pending = PendingPlacements().add(key("a"), "g1", 1_000).add(key("b"), "g2", 1_000)
        val first = pending.settle(setOf(key("a")), 2_000, maxAge)
        assertEquals(listOf(PendingPlacement(key("a"), "g1", 1_000)), first.apply)
        assertTrue(first.expired.isEmpty())
        assertEquals(listOf(key("b")), first.remaining.entries.map { it.key })
        assertTrue(first.remaining.settle(setOf(key("a")), 3_000, maxAge).apply.isEmpty())
    }

    @Test
    fun `an entry whose session never appears expires after the maximum age`() {
        val pending = PendingPlacements().add(key("a"), "g1", 1_000)
        val early = pending.settle(emptySet(), 1_000 + maxAge.inWholeMilliseconds, maxAge)
        assertTrue(early.expired.isEmpty())
        val late = pending.settle(emptySet(), 1_001 + maxAge.inWholeMilliseconds, maxAge)
        assertEquals(listOf(key("a")), late.expired)
        assertTrue(late.remaining.entries.isEmpty())
    }

    @Test
    fun `a session that appears after the maximum age is still applied`() {
        val pending = PendingPlacements().add(key("a"), "g1", 1_000)
        val settled = pending.settle(setOf(key("a")), 1_000_000, maxAge)
        assertEquals(listOf(key("a")), settled.apply.map { it.key })
        assertTrue(settled.expired.isEmpty())
    }

    @Test
    fun `adding a session again replaces its entry`() {
        val pending = PendingPlacements().add(key("a"), "g1", 1_000).add(key("a"), "g2", 2_000)
        assertEquals(listOf(PendingPlacement(key("a"), "g2", 2_000)), pending.entries)
    }
}
