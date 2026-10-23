package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.DaemonEvent
import dev.pohunek.companion.protocol.SessionCapabilities
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionState
import dev.pohunek.companion.protocol.StateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStoreTest {
    private val store = SessionStore("host-a")

    private fun session(
        id: String,
        updatedAt: String = "2026-01-01T10:00:00Z",
        name: String? = null,
        projectId: String? = null,
        projectLabel: String? = null,
    ) = SessionInfo(
        id = id,
        capabilities = SessionCapabilities(resume = false, fork = false),
        name = name,
        agent = "shell",
        agentBase = "shell",
        cwd = "/work",
        pid = 1,
        cols = 80,
        rows = 24,
        state = SessionState.Running,
        stateSource = StateSource.Process,
        projectId = projectId,
        projectLabel = projectLabel,
        createdAt = "2026-01-01T09:00:00Z",
        updatedAt = updatedAt,
    )

    private fun names() = store.sessions().values.map { it.name }

    @Test
    fun `seed then create update and remove`() {
        val change = store.applySeed(listOf(session("s-1", name = "one")))
        assertEquals(SeedChange(added = 1), change)

        assertEquals(EventOutcome.Applied, store.applyEvent(DaemonEvent.SessionCreated(session("s-2", name = "two"))))
        assertEquals(listOf<String?>("one", "two"), names())

        store.applyEvent(DaemonEvent.SessionUpdated(session("s-1", "2026-01-01T10:00:05Z", name = "renamed")))
        assertEquals("renamed", store.session("s-1")?.name)

        assertEquals(EventOutcome.Applied, store.applyEvent(DaemonEvent.SessionRemoved(session("s-2"))))
        assertEquals(listOf<String?>("renamed"), names())
        assertEquals(
            EventOutcome.Ignored(IgnoreReason.UnknownSession),
            store.applyEvent(DaemonEvent.SessionRemoved(session("s-2"))),
        )

        val report = store.takeReport()
        assertEquals(1, report.seeds)
        assertEquals(mapOf("session_created" to 1, "session_updated" to 1, "session_removed" to 1), report.applied)
        assertEquals(mapOf(("session_removed" to IgnoreReason.UnknownSession) to 1), report.ignored)
        assertEquals(ReconcileReport(), store.report())
    }

    @Test
    fun `an older snapshot is ignored and an equal or newer one applied`() {
        store.applySeed(listOf(session("s-1", "2026-01-01T10:00:10Z", name = "current")))

        val stale = store.applyEvent(DaemonEvent.SessionUpdated(session("s-1", "2026-01-01T10:00:09.999999999Z", name = "stale")))
        assertEquals(EventOutcome.Ignored(IgnoreReason.StaleSnapshot), stale)
        assertEquals("current", store.session("s-1")?.name)

        store.applyEvent(DaemonEvent.SessionStopped(session("s-1", "2026-01-01T10:00:10Z", name = "same-time")))
        assertEquals("same-time", store.session("s-1")?.name)
        // The offset is part of the instant: 12:00:20+02:00 is 10:00:20Z.
        store.applyEvent(DaemonEvent.SessionUpdated(session("s-1", "2026-01-01T12:00:20+02:00", name = "offset")))
        assertEquals("offset", store.session("s-1")?.name)
    }

    @Test
    fun `an unparseable timestamp is applied and counted`() {
        store.applySeed(listOf(session("s-1", "2026-01-01T10:00:10Z", name = "current")))
        store.applyEvent(DaemonEvent.SessionUpdated(session("s-1", "garbage", name = "unordered")))
        assertEquals("unordered", store.session("s-1")?.name)
        assertEquals(1, store.report().unorderedSnapshots)
    }

    @Test
    fun `an update for a session the store does not hold is ignored`() {
        store.applySeed(emptyList())
        assertEquals(
            EventOutcome.Ignored(IgnoreReason.UnknownSession),
            store.applyEvent(DaemonEvent.SessionRuntimeLost(session("s-9"))),
        )
        assertEquals(EventOutcome.Ignored(IgnoreReason.NotModelled), store.applyEvent(DaemonEvent.Unknown("agent_state")))
        assertEquals(
            EventOutcome.Ignored(IgnoreReason.MalformedPayload),
            store.applyEvent(DaemonEvent.Malformed("session_updated", "no session")),
        )
        assertEquals(emptyMap<SessionKey, SessionInfo>(), store.sessions())
    }

    @Test
    fun `a re-seed that drops a session reports it removed`() {
        store.applySeed(listOf(session("s-1"), session("s-2"), session("s-3", name = "old")))
        val change = store.applySeed(listOf(session("s-1"), session("s-3", name = "new"), session("s-4")))
        assertEquals(SeedChange(added = 1, removed = 1, changed = 1), change)
        assertNull(store.session("s-2"))
        assertEquals(setOf("s-1", "s-3", "s-4"), store.sessions().keys.map { it.sessionId }.toSet())
    }

    @Test
    fun `a re-seed that differs only in activity timestamps and state source reports nothing`() {
        store.applySeed(listOf(session("s-1")))
        val noisy = session("s-1", "2026-01-01T10:05:00Z").copy(nativeLastActivityAt = "2026-01-01T10:04:00Z", stateSource = StateSource.Screen)
        assertEquals(SeedChange(), store.applySeed(listOf(noisy)))
        assertEquals("2026-01-01T10:05:00Z", store.session("s-1")?.updatedAt)
    }

    @Test
    fun `the project label survives an update without one`() {
        store.applySeed(listOf(session("s-1", projectId = "p-1", projectLabel = "proj")))
        store.applyEvent(DaemonEvent.SessionUpdated(session("s-1", "2026-01-01T10:00:05Z", projectId = "p-1")))
        assertNull(store.session("s-1")?.projectLabel)
        assertEquals("proj", store.projectLabel("p-1"))

        // The seed carries the label while events omit it, so that difference alone is not a change.
        assertEquals(SeedChange(), store.applySeed(listOf(session("s-1", "2026-01-01T10:00:05Z", projectId = "p-1", projectLabel = "proj"))))
        assertEquals(SeedChange(changed = 1), store.applySeed(listOf(session("s-1", "2026-01-01T10:00:05Z", projectId = "p-1", projectLabel = "other"))))
    }

    @Test
    fun `sessions of different hosts have different keys`() {
        val other = SessionStore("host-b")
        store.applySeed(listOf(session("s-1")))
        other.applySeed(listOf(session("s-1")))
        assertEquals(setOf(SessionKey("host-a", "s-1")), store.sessions().keys)
        assertEquals(setOf(SessionKey("host-b", "s-1")), other.sessions().keys)
    }
}
