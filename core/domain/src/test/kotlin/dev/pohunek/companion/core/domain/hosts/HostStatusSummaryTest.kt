package dev.pohunek.companion.core.domain.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStatusSummaryTest {
    private fun outage(label: String, attempt: Int = 1, reason: String = "timeout", code: String? = null) = HostOutage(label, attempt, reason, code)

    @Test
    fun `reconnecting hosts share one line that names the first in host order`() {
        val summary = summarizeHostStatus(listOf(outage("a", 4), outage("b", 2), outage("c", 9)), followed = 5)
        assertEquals(3, summary.reconnecting.size)
        assertEquals(5, summary.followed)
        assertEquals("a", summary.firstReconnecting?.hostLabel)
        assertTrue(summary.refused.isEmpty())
    }

    @Test
    fun `a refusal keeps its own line apart from the reconnecting hosts`() {
        val summary = summarizeHostStatus(listOf(outage("a"), outage("b", code = "version_mismatch"), outage("c")), followed = 3)
        assertEquals(listOf("b"), summary.refused.map { it.hostLabel })
        assertEquals(listOf("a", "c"), summary.reconnecting.map { it.hostLabel })
    }

    @Test
    fun `a reason is cut to one line`() {
        val summary = summarizeHostStatus(listOf(outage("a", reason = "connect failed\r\n  caused by: timeout\n")), followed = 1)
        assertEquals("connect failed caused by: timeout", summary.firstReconnecting?.reason)
    }

    @Test
    fun `no outage is an empty summary`() {
        assertTrue(summarizeHostStatus(emptyList(), followed = 3).isEmpty)
    }
}
