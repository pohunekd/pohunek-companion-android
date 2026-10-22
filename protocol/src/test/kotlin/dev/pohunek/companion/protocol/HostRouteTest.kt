package dev.pohunek.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HostRouteTest {
    @Test
    fun `host names, IPv4 and bracketed IPv6 literals parse and print back`() {
        assertEquals(HostRoute("build-2.netbird.cloud", 18722), HostRoute.parse("build-2.netbird.cloud:18722"))
        assertEquals(HostRoute("100.64.1.2", 18722), HostRoute.parse("100.64.1.2:18722"))
        assertEquals(HostRoute("fd00::1", 18722), HostRoute.parse("[fd00::1]:18722"))
        assertEquals("[fd00::1]:18722", HostRoute.parse("[fd00::1]:18722").toString())
    }

    @Test
    fun `malformed routes are rejected`() {
        for (route in listOf("", "host", ":18722", "host:", "host:0", "host:65536", "host:abc", "fd00::1:18722", "[]:18722")) {
            assertThrows(route, ProtocolClientException.InvalidRoute::class.java) { HostRoute.parse(route) }
        }
    }
}
