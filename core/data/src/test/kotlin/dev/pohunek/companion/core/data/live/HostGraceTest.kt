package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.core.domain.sessions.HostLink
import dev.pohunek.companion.core.domain.sessions.OwnerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class HostGraceTest {
    private val clock = TestTimeSource()
    private val grace = 10.seconds
    private val list = emptyList<OwnerSession>()

    private fun reconnecting(code: String? = null) = HostConnection.Reconnecting(attempt = 1, reason = "lost", code = code)

    private fun feed(connection: HostConnection, sessions: List<OwnerSession>? = list) =
        HostFeed(connection, sessions, downSince = if (connection == HostConnection.Connected) null else clock.markNow())

    @Test
    fun `a lost connection looks connected until the grace ends`() {
        val lost = feed(reconnecting())
        assertEquals(HostLink.Grace, lost.link(grace))
        clock += 9.seconds
        assertEquals(HostLink.Grace, lost.link(grace))
        assertEquals(1.seconds, lost.graceRemaining(grace))
        clock += 1.seconds
        assertEquals(HostLink.Down, lost.link(grace))
        assertNull(lost.graceRemaining(grace))
    }

    @Test
    fun `a connection back inside the grace never shows as down`() {
        val lost = feed(reconnecting())
        clock += 4.seconds
        assertEquals(HostLink.Grace, lost.link(grace))
        assertEquals(HostLink.Connected, lost.copy(connection = HostConnection.Connected, downSince = null).link(grace))
    }

    @Test
    fun `a loop start with a retained list is in grace, then connecting`() {
        val restarted = feed(HostConnection.Connecting)
        assertEquals(HostLink.Grace, restarted.link(grace))
        clock += 10.seconds
        assertEquals(HostLink.Connecting, restarted.link(grace))
    }

    @Test
    fun `a host without a list keeps the connecting state`() {
        assertEquals(HostLink.Connecting, feed(HostConnection.Connecting, sessions = null).link(grace))
        assertEquals(HostLink.Connecting, null.link(grace))
    }

    @Test
    fun `a refusal with a code and an unreported host are down at once`() {
        assertEquals(HostLink.Down, feed(reconnecting(code = "version_mismatch")).link(grace))
        assertEquals(HostLink.Down, feed(HostConnection.NotReported).link(grace))
    }

    @Test
    fun `a list read from disk is never in grace and is confirmed only by a list from the host`() {
        val onDisk = HostFeed(HostConnection.Connecting, lastKnown = list, downSince = clock.markNow())
        assertEquals(HostLink.Connecting, onDisk.link(grace))
        assertEquals(HostLink.Connecting, onDisk.copy(connection = HostConnection.Connected, downSince = null).link(grace))
        assertEquals(HostLink.Down, onDisk.copy(connection = reconnecting()).link(grace))
        assertEquals(HostLink.Connected, onDisk.copy(connection = HostConnection.Connected, sessions = list, lastKnown = null).link(grace))
        assertNull(onDisk.graceRemaining(grace))
    }

    @Test
    fun `a host that never connected shows its failure only after the grace`() {
        val unreachable = feed(reconnecting(), sessions = null)
        assertEquals(HostLink.Grace, unreachable.link(grace))
        clock += grace
        assertEquals(HostLink.Down, unreachable.link(grace))
    }
}
