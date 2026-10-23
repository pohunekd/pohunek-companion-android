package dev.pohunek.companion.core.domain.hosts

import dev.pohunek.companion.protocol.HostRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfiguredHostTest {
    private fun host(route: String, label: String? = null) = ConfiguredHost(HostRoute.parse(route), label)

    @Test
    fun `routes must be unique and a set label must not be blank`() {
        assertNull(hostProblem(listOf(host("a.example:1", "one"), host("a.example:2"), host("b.example:1", "x"))))
        assertEquals(
            HostProblem.DuplicateRoute(HostRoute("a.example", 1)),
            hostProblem(listOf(host("a.example:1"), host("b.example:1"), host("a.example:1", "again"))),
        )
        assertEquals(HostProblem.BlankLabel(HostRoute("a.example", 1)), hostProblem(listOf(host("a.example:1", "  "))))
    }

    @Test
    fun `the display label is the sanitized label, else the route`() {
        assertEquals("build box", hostDisplayLabel(host("a.example:1", "build\n‮ box")))
        assertEquals("a.example:1", hostDisplayLabel(host("a.example:1")))
        assertEquals("[fd00::1]:7", hostDisplayLabel(host("[fd00::1]:7", "​")))
    }
}
