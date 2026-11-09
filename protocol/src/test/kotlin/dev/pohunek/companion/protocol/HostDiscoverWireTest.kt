package dev.pohunek.companion.protocol

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HostDiscoverWireTest {
    private val records = ListSerializer(HostRecord.serializer())

    private fun decode(name: String) = ResponseDecoder().decode(Fixtures.captured(name).trim(), "req-1").decodeResult(records)

    @Test
    fun `request asks for the cached snapshot with the client range`() {
        assertEquals(
            """{"v":{"minimum":5,"maximum":5},"id":"req-1","method":"host.discover","params":{"force":false}}""",
            Requests.hostDiscover("req-1").toLine(),
        )
    }

    @Test
    fun `every classification decodes beside the record's own fields`() {
        val peers = decode("host-discover-response")
        assertEquals(
            HostRecord("air", "air.example.net", "100.92.30.40", 18722, "netbird", "key-air", HostClass.ReachableDaemon("0.41.0")),
            peers[0],
        )
        assertEquals(HostClass.VersionMismatch(3), peers[1].hostClass)
        assertEquals(HostClass.Unreachable, peers[2].hostClass)
        assertEquals(HostClass.Candidate, peers[3].hostClass)
        assertNull(peers[3].address)
        assertNull(peers[3].peerId)
    }

    @Test
    fun `the record of the asked machine is marked local and a peer record is not`() {
        val peers = decode("host-discover-response")
        assertEquals(listOf(false, false, false, false, false, true), peers.map { it.local })
    }

    @Test
    fun `a classification this client does not model is kept as unknown`() {
        assertEquals(HostClass.Unknown("quarantined"), decode("host-discover-response")[4].hostClass)
    }

    @Test
    fun `a classification without its detail or a port out of range is malformed`() {
        val body = """{"name":"a","port":%d,"overlay":"netbird","classification":"%s"}"""
        for ((port, classification) in listOf(18722 to "reachable_daemon", 18722 to "version_mismatch", -1 to "candidate", 65536 to "candidate")) {
            val line = """{"v":5,"id":"req-1","ok":[${body.format(port, classification)}]}"""
            assertThrows(ProtocolClientException.MalformedResponse::class.java) { ResponseDecoder().decode(line, "req-1").decodeResult(records) }
        }
    }

    @Test
    fun `a host without an overlay CLI answers a discovery error with the daemon text`() {
        val failure = assertThrows(ProtocolClientException.Daemon::class.java) {
            ResponseDecoder().decode(Fixtures.captured("host-discover-netbird-cli-missing-response").trim(), "req-1")
        }
        assertEquals(ErrorClass.Discovery, failure.error.errorClass)
        assertEquals("netbird_cli_missing", failure.error.code)
    }
}
