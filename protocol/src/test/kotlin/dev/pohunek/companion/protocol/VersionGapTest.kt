package dev.pohunek.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VersionGapTest {
    @Test
    fun `the captured protocol 4 refusal is an older Pohunek`() {
        val refusal = assertThrows(ProtocolClientException.VersionMismatch::class.java) {
            ResponseDecoder().decode(Fixtures.captured("version-mismatch-protocol-4-response"), "req-1")
        }
        assertEquals(VersionGap.Older(4), refusal.gap)
    }

    @Test
    fun `a discovery record of a protocol 4 peer is an older Pohunek`() {
        assertEquals(VersionGap.Older(4), HostClass.VersionMismatch(4).gap)
    }

    @Test
    fun `a version above the client range is a newer Pohunek`() {
        assertEquals(VersionGap.Newer(6), HostClass.VersionMismatch(6).gap)
        assertEquals(VersionGap.Newer(7), ProtocolClientException.VersionMismatch(Wire.CLIENT_RANGE, 7, null).gap)
    }

    @Test
    fun `a refusal without a version does not say which side is behind`() {
        assertEquals(VersionGap.Unknown, ProtocolClientException.VersionMismatch(Wire.CLIENT_RANGE, null, null).gap)
    }
}
