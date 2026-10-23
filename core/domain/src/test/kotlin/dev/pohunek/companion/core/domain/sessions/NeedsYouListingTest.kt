package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.core.domain.hosts.ConfiguredHost
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.RuntimeState
import dev.pohunek.companion.protocol.SessionCapabilities
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionRuntime
import dev.pohunek.companion.protocol.SessionState
import dev.pohunek.companion.protocol.StateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class NeedsYouListingTest {
    private fun info(id: String, name: String? = null, runtime: SessionRuntime? = null) = SessionInfo(
        id = id,
        name = name,
        capabilities = SessionCapabilities(resume = false, fork = false),
        agent = "claude",
        agentBase = "claude",
        cwd = "/work",
        pid = 1,
        runtime = runtime,
        cols = 80,
        rows = 24,
        state = SessionState.Running,
        stateSource = StateSource.Screen,
        createdAt = "2026-01-01T09:00:00Z",
        updatedAt = "2026-01-01T10:00:00Z",
    )

    private val hostA = ConfiguredHost(HostRoute("a.example", 1), "alpha")
    private val hostB = ConfiguredHost(HostRoute("b.example", 1), null)

    private fun session(id: String, state: OwnerState, name: String? = null, waiting: String? = null) =
        OwnerSession(info(id, name), state, waiting?.let(OffsetDateTime::parse))

    private fun ids(sessions: List<HostedSession>) = sessions.map { it.key.sessionId }

    private fun partition(vararg sessions: OwnerSession) =
        partitionNeedsYou(listOf(HostSessions(hostA, HostLink.Connected, sessions.toList())))

    @Test
    fun `sessions that need the owner are lifted in wait then name then id order and the rest keeps its order`() {
        val listing = partition(
            session("1", OwnerState.Working),
            session("2", OwnerState.NeedsOwner, name = "zed"),
            session("3", OwnerState.Idle),
            session("4", OwnerState.NeedsOwner, name = "beta", waiting = "2026-01-01T01:00:00Z"),
            session("5", OwnerState.NeedsOwner, name = "alpha", waiting = "2026-01-01T02:00:00Z"),
            session("6", OwnerState.NeedsOwner, name = "alpha", waiting = "2026-01-01T02:00:00Z"),
            session("7", OwnerState.NeedsOwner, name = "alpha"),
            session("8", OwnerState.Ended),
        )
        assertEquals(listOf("4", "5", "6", "7", "2"), ids(listing.needsYou))
        assertEquals(listOf("1", "3", "8"), ids(listing.others))
    }

    @Test
    fun `an offset timestamp orders by instant, not by text`() {
        val listing = partition(
            session("a", OwnerState.NeedsOwner, waiting = "2026-01-01T10:00:00+02:00"),
            session("b", OwnerState.NeedsOwner, waiting = "2026-01-01T09:00:00Z"),
        )
        assertEquals(listOf("a", "b"), ids(listing.needsYou))
    }

    @Test
    fun `nothing counts as needing the owner while the host is not connected`() {
        for (link in listOf(HostLink.Down, HostLink.Connecting)) {
            val listing = partitionNeedsYou(
                listOf(HostSessions(hostA, link, listOf(session("1", OwnerState.NeedsOwner), session("2", OwnerState.Working)))),
            )
            assertEquals(emptyList<HostedSession>(), listing.needsYou)
            assertEquals(listOf("1", "2"), ids(listing.others))
            assertFalse(listing.others.any { it.reachable })
        }
    }

    @Test
    fun `the same session id on two hosts stays two sessions and a down host does not affect the other`() {
        val listing = partitionNeedsYou(
            listOf(
                HostSessions(hostA, HostLink.Down, listOf(session("s", OwnerState.NeedsOwner))),
                HostSessions(hostB, HostLink.Connected, listOf(session("s", OwnerState.NeedsOwner, name = "same"))),
            ),
        )
        assertEquals(listOf(SessionKey(hostB.route.toString(), "s")), listing.needsYou.map { it.key })
        assertEquals(listOf(SessionKey(hostA.route.toString(), "s")), listing.others.map { it.key })
        assertEquals(listOf("b.example:1"), listing.needsYou.map { it.hostLabel })
        assertEquals(listOf("alpha"), listing.others.map { it.hostLabel })
    }

    @Test
    fun `equal wait and name across hosts are ordered by host, then id`() {
        val listing = partitionNeedsYou(
            listOf(
                HostSessions(hostB, HostLink.Connected, listOf(session("1", OwnerState.NeedsOwner, name = "x"))),
                HostSessions(
                    hostA,
                    HostLink.Connected,
                    listOf(session("2", OwnerState.NeedsOwner, name = "x"), session("1", OwnerState.NeedsOwner, name = "x")),
                ),
            ),
        )
        assertEquals(
            listOf(SessionKey(hostA.route.toString(), "1"), SessionKey(hostA.route.toString(), "2"), SessionKey(hostB.route.toString(), "1")),
            listing.needsYou.map { it.key },
        )
    }

    @Test
    fun `runtime detail names the state and a sanitized loss reason for conflict and incompatible runtimes only`() {
        fun detail(state: RuntimeState, reason: String?) = runtimeDetail(info("s", runtime = SessionRuntime(state, "1", lossReason = reason)))
        assertEquals("conflict: resume binding mismatch", detail(RuntimeState.Conflict, "  resume\n binding‮ mismatch\u0007 "))
        assertEquals("incompatible", detail(RuntimeState.Incompatible, " \n "))
        assertEquals("incompatible", detail(RuntimeState.Incompatible, null))
        assertNull(detail(RuntimeState.Lost, "gone"))
        assertNull(runtimeDetail(info("s")))
    }
}
