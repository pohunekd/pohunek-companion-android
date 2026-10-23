package dev.pohunek.companion.core.domain.hosts

import dev.pohunek.companion.protocol.HostClass
import dev.pohunek.companion.protocol.HostRecord
import dev.pohunek.companion.protocol.HostRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {
    private val reachable = HostClass.ReachableDaemon("0.35.2")

    private fun record(name: String, hostClass: HostClass = reachable, address: String? = "100.92.30.40", port: Int = 18722) =
        HostRecord(name, "$name.example.net", address, port, "netbird", "key-$name", hostClass)

    private fun manual(route: String, label: String? = null) = ConfiguredHost(HostRoute.parse(route), label)

    @Test
    fun `a reachable peer is keyed by peer id and followed at its address and port`() {
        val plan = planDiscovery(listOf(record("air", port = 28722)), emptyList())
        assertEquals(listOf(DiscoveredHost("netbird:key-air", HostRoute("100.92.30.40", 28722), "air")), plan.connect)
    }

    @Test
    fun `the fqdn is the identity without a peer id`() {
        for (peerId in listOf(null, "")) {
            val plan = planDiscovery(listOf(record("air").copy(peerId = peerId)), emptyList())
            assertEquals("netbird:air.example.net", plan.connect.single().key)
        }
    }

    @Test
    fun `a peer without an overlay, an identity or a usable address is not followed`() {
        val noIdentity = record("a").copy(peerId = null, fqdn = null)
        val noOverlay = record("b").copy(overlay = "")
        val addresses = listOf(null, "", "air.example.net", "100.92.30", "100.92.30.256", "100.92.030.4", "fe80::1%eth0", "not:ip", "1.2.3.4:80", ".:", ".1:2")
        val records = listOf(noIdentity, noOverlay) + addresses.map { record("c").copy(address = it) } + record("z", port = 0) + record("d", port = 18722)
        assertEquals(listOf("netbird:key-d"), planDiscovery(records, emptyList()).connect.map { it.key })
    }

    @Test
    fun `IPv4 and IPv6 literals are followed`() {
        val plan = planDiscovery(listOf(record("a", address = "fd00::1"), record("b", address = "100.64.0.9")), emptyList())
        assertEquals(listOf("[fd00::1]:18722", "100.64.0.9:18722"), plan.connect.map { it.route.toString() })
    }

    @Test
    fun `only a version mismatch is listed as incompatible and unreachable or candidate peers are ignored`() {
        val records = listOf(
            record("old", HostClass.VersionMismatch(3)),
            record("down", HostClass.Unreachable),
            record("idle", HostClass.Candidate, address = null),
            record("odd", HostClass.Unknown("quarantined")),
        )
        val plan = planDiscovery(records, emptyList())
        assertTrue(plan.connect.isEmpty())
        assertEquals(listOf(IncompatibleHost("netbird:key-old", "old", 3)), plan.incompatible)
    }

    @Test
    fun `records of one peer collapse to the first, in record order`() {
        val records = listOf(
            record("air"),
            record("box", address = "100.92.30.41"),
            record("air", port = 1),
            record("old", HostClass.VersionMismatch(3)),
            record("old", HostClass.VersionMismatch(2)),
        )
        val plan = planDiscovery(records, emptyList())
        assertEquals(listOf("air", "box"), plan.connect.map { it.label })
        assertEquals(18722, plan.connect.first().route.port)
        assertEquals(listOf(3), plan.incompatible.map { it.protocolVersion })
    }

    @Test
    fun `two peers at one address are followed once`() {
        val plan = planDiscovery(listOf(record("a"), record("b")), emptyList())
        assertEquals(listOf("a"), plan.connect.map { it.label })
    }

    @Test
    fun `a configured host naming the peer keeps its entry and the peer is not followed twice`() {
        for (target in listOf("100.92.30.40:1", "key-air:1", "AIR.example.net:1", "Air:1", "[netbird:key-air]:1")) {
            val plan = planDiscovery(listOf(record("air"), record("old", HostClass.VersionMismatch(3))), listOf(manual(target)))
            assertTrue(target, plan.connect.isEmpty())
            assertEquals(target, setOf("netbird:key-air"), plan.claimed.filter { it.endsWith("air") }.toSet())
        }
        val other = planDiscovery(listOf(record("air")), listOf(manual("other:1")))
        assertEquals(1, other.connect.size)
        assertTrue(other.claimed.isEmpty())
    }

    @Test
    fun `a configured host also claims a peer that is incompatible`() {
        val plan = planDiscovery(listOf(record("old", HostClass.VersionMismatch(3))), listOf(manual("old:18722")))
        assertTrue(plan.incompatible.isEmpty())
        assertEquals(setOf("netbird:key-old"), plan.claimed)
    }

    @Test
    fun `a route a configured host already has is not followed again`() {
        val plan = planDiscovery(listOf(record("a", address = "100.92.30.40")), listOf(manual("100.92.30.40:18722")))
        assertTrue(plan.connect.isEmpty())
    }

    @Test
    fun `the label falls back from name to fqdn to key and is sanitized`() {
        val named = record("air").copy(name = "Air\n‮ one")
        assertEquals("Air one", planDiscovery(listOf(named), emptyList()).connect.single().label)
        val unnamed = record("air").copy(name = "​")
        assertEquals("air.example.net", planDiscovery(listOf(unnamed), emptyList()).connect.single().label)
        val bare = record("air").copy(name = null, fqdn = null)
        assertEquals("netbird:key-air", planDiscovery(listOf(bare), emptyList()).connect.single().label)
    }

    @Test
    fun `a peer that leaves the answer stays marked not present and returns present`() {
        val first =
            trackPeers(emptyList(), planDiscovery(listOf(record("air", address = "100.0.0.1"), record("box", address = "100.0.0.2")), emptyList()), emptyList())
        assertEquals(listOf(true, true), first.map { it.present })
        val second = trackPeers(first, planDiscovery(listOf(record("box", address = "100.0.0.2")), emptyList()), emptyList())
        assertEquals(listOf("box" to true, "air" to false), second.map { it.host.label to it.present })
        assertEquals(HostRoute("100.0.0.1", 18722), second.last().host.route)
        val third =
            trackPeers(second, planDiscovery(listOf(record("air", address = "100.0.0.1"), record("box", address = "100.0.0.2")), emptyList()), emptyList())
        assertEquals(listOf("air" to true, "box" to true), third.map { it.host.label to it.present })
    }

    @Test
    fun `a peer that changed address is followed at the new one only`() {
        val before = trackPeers(emptyList(), planDiscovery(listOf(record("air", address = "100.0.0.1")), emptyList()), emptyList())
        val after = trackPeers(before, planDiscovery(listOf(record("air", address = "100.0.0.5")), emptyList()), emptyList())
        assertEquals(listOf(HostRoute("100.0.0.5", 18722)), after.map { it.host.route })
    }

    @Test
    fun `a departed peer is dropped once a configured host covers it or sits at its address`() {
        val before =
            trackPeers(emptyList(), planDiscovery(listOf(record("air", address = "100.0.0.1"), record("box", address = "100.0.0.2")), emptyList()), emptyList())
        val covered = listOf(manual("air.example.net:1"), manual("100.0.0.2:5"))
        val plan = planDiscovery(listOf(record("air", address = "100.0.0.1")), covered)
        assertEquals(emptyList<DiscoveredPeer>(), trackPeers(before, plan, covered))
        // A peer at the address of a configured host is that host, whatever its name.
        val sameAddress = listOf(manual("100.0.0.2:18722"))
        assertEquals(
            listOf("air"),
            trackPeers(before, planDiscovery(listOf(record("air", address = "100.0.0.1")), sameAddress), sameAddress).map {
                it.host.label
            },
        )
    }
}
