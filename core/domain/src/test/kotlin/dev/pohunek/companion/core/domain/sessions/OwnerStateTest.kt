package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.AgentActivity
import dev.pohunek.companion.protocol.AgentStateUpdate
import dev.pohunek.companion.protocol.BackgroundItem
import dev.pohunek.companion.protocol.BackgroundStateUpdate
import dev.pohunek.companion.protocol.BackgroundStatus
import dev.pohunek.companion.protocol.DaemonEvent
import dev.pohunek.companion.protocol.DecimalWire
import dev.pohunek.companion.protocol.NotificationKind
import dev.pohunek.companion.protocol.NotificationRecord
import dev.pohunek.companion.protocol.NotificationStatus
import dev.pohunek.companion.protocol.RuntimeState
import dev.pohunek.companion.protocol.SessionBackground
import dev.pohunek.companion.protocol.SessionCapabilities
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionRuntime
import dev.pohunek.companion.protocol.SessionRuntimeIdentity
import dev.pohunek.companion.protocol.SessionState
import dev.pohunek.companion.protocol.StateSource
import dev.pohunek.companion.protocol.SubagentInfo
import dev.pohunek.companion.protocol.SubagentLifecycle
import dev.pohunek.companion.protocol.SubagentStateUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class OwnerStateTest {
    private val clock = TestTimeSource()
    private val threshold = 300.seconds
    private val store = SessionStore("host", clock, threshold)

    private fun session(
        id: String = "s-1",
        activity: AgentActivity? = AgentActivity.Idle,
        state: SessionState = SessionState.Running,
        runtimeState: RuntimeState = RuntimeState.Live,
        generation: String = "1",
        workerInstance: String = "w-1",
        subagents: List<SubagentInfo> = emptyList(),
        background: SessionBackground? = null,
    ) = SessionInfo(
        id = id,
        capabilities = SessionCapabilities(resume = false, fork = false),
        agent = "claude",
        agentBase = "claude",
        cwd = "/work",
        pid = 1,
        runtime = SessionRuntime(runtimeState, generation, workerInstanceId = workerInstance),
        cols = 80,
        rows = 24,
        state = state,
        stateSource = StateSource.Screen,
        activity = activity,
        subagents = subagents,
        background = background,
        createdAt = "2026-01-01T09:00:00Z",
        updatedAt = "2026-01-01T10:00:00Z",
    )

    private fun runtime(generation: Int = 1, worker: String = "w-1") = SessionRuntimeIdentity(worker, DecimalWire(generation.toULong()))

    private fun agentState(
        activity: AgentActivity,
        revision: Int?,
        epoch: String? = "e-1",
        runtime: SessionRuntimeIdentity? = runtime(),
        id: String = "s-1",
    ) = DaemonEvent.AgentState(
        AgentStateUpdate(id, activity, StateSource.Screen, runtime, epoch, revision?.let { DecimalWire(it.toULong()) }),
    )

    private fun subagent(revision: Int, lifecycle: SubagentLifecycle) = SubagentInfo("sub-1", "claude", lifecycle, DecimalWire(revision.toULong()))

    private fun subagentState(revision: Int, lifecycle: SubagentLifecycle, runtime: SessionRuntimeIdentity? = runtime()) =
        DaemonEvent.SubagentState(SubagentStateUpdate("s-1", subagent(revision, lifecycle), runtime))

    private fun backgroundState(revision: Int, status: BackgroundStatus?) = DaemonEvent.BackgroundState(
        BackgroundStateUpdate(
            "s-1",
            status?.let { SessionBackground(DecimalWire(revision.toULong()), listOf(BackgroundItem("b-1", it))) },
            runtime(),
        ),
    )

    private fun notification(
        id: String = "n-1",
        kind: NotificationKind = NotificationKind.AgentBlocked,
        status: NotificationStatus = NotificationStatus.Unread,
        sessionId: String? = "s-1",
        createdAt: String = "2026-01-01T10:00:00Z",
    ) = NotificationRecord(id, kind, status, createdAt, sessionId)

    private fun owner(id: String = "s-1") = store.ownerState(id)

    @Test
    fun `a stale or repeated agent_state revision is ignored and a newer or new-epoch one applied`() {
        store.applySeed(listOf(session(activity = AgentActivity.Working)), emptyList())

        assertEquals(EventOutcome.Applied, store.applyEvent(agentState(AgentActivity.Idle, revision = 5)))
        assertEquals(AgentActivity.Idle, store.session("s-1")?.activity)
        for (revision in listOf(5, 4)) {
            assertEquals(
                EventOutcome.Ignored(IgnoreReason.StaleRevision),
                store.applyEvent(agentState(AgentActivity.Working, revision)),
            )
        }
        assertEquals(AgentActivity.Idle, store.session("s-1")?.activity)

        assertEquals(EventOutcome.Applied, store.applyEvent(agentState(AgentActivity.Working, revision = 6)))
        // A new epoch starts a new revision sequence, so a smaller revision is current again.
        assertEquals(EventOutcome.Applied, store.applyEvent(agentState(AgentActivity.Blocked, revision = 1, epoch = "e-2")))
        assertEquals(AgentActivity.Blocked, store.session("s-1")?.activity)
    }

    @Test
    fun `revisions are compared as numbers, not as text`() {
        store.applySeed(listOf(session()), emptyList())
        store.applyEvent(agentState(AgentActivity.Working, revision = 9))
        assertEquals(EventOutcome.Applied, store.applyEvent(agentState(AgentActivity.Idle, revision = 10)))
    }

    @Test
    fun `an observation for another runtime or without one is ignored`() {
        store.applySeed(listOf(session(activity = AgentActivity.Working, generation = "2")), emptyList())

        assertEquals(EventOutcome.Ignored(IgnoreReason.RuntimeMismatch), store.applyEvent(agentState(AgentActivity.Idle, 1, runtime = runtime(generation = 1))))
        assertEquals(EventOutcome.Ignored(IgnoreReason.RuntimeMismatch), store.applyEvent(agentState(AgentActivity.Idle, 1, runtime = runtime(2, "w-2"))))
        assertEquals(EventOutcome.Ignored(IgnoreReason.MissingRuntime), store.applyEvent(agentState(AgentActivity.Idle, 1, runtime = null)))
        assertEquals(EventOutcome.Ignored(IgnoreReason.MissingRevision), store.applyEvent(agentState(AgentActivity.Idle, null, runtime = runtime(2))))
        assertEquals(EventOutcome.Ignored(IgnoreReason.UnknownSession), store.applyEvent(agentState(AgentActivity.Idle, 1, id = "s-9")))
        assertEquals(EventOutcome.Ignored(IgnoreReason.RuntimeMismatch), store.applyEvent(subagentState(1, SubagentLifecycle.Running, runtime(1))))
        assertEquals(AgentActivity.Working, store.session("s-1")?.activity)
        assertEquals(emptyList<SubagentInfo>(), store.session("s-1")?.subagents)
    }

    @Test
    fun `an open attention notification makes the session Needs owner and deleting it clears that`() {
        store.applySeed(listOf(session(activity = AgentActivity.Working)), emptyList())
        assertEquals(OwnerState.Working, owner())

        store.applyEvent(DaemonEvent.NotificationCreated(notification()))
        assertEquals(OwnerState.NeedsOwner, owner())
        assertEquals(OffsetDateTime.parse("2026-01-01T10:00:00Z"), store.waitingSince("s-1"))

        store.applyEvent(DaemonEvent.NotificationDeleted("n-1"))
        assertEquals(OwnerState.Working, owner())
        assertNull(store.waitingSince("s-1"))
    }

    @Test
    fun `only unread or read attention records count and waiting since is the earliest`() {
        store.applySeed(
            listOf(session(activity = AgentActivity.Working)),
            listOf(
                notification("n-1", createdAt = "2026-01-01T10:05:00Z"),
                notification("n-2", NotificationKind.ApprovalRequired, NotificationStatus.Read, createdAt = "2026-01-01T10:01:00Z"),
                notification("n-3", NotificationKind.TurnCompleted, createdAt = "2025-01-01T00:00:00Z"),
                notification("n-4", status = NotificationStatus.Acknowledged, createdAt = "2025-01-01T00:00:00Z"),
                notification("n-5", sessionId = null),
            ),
        )
        assertEquals(listOf("n-1", "n-2", "n-5"), store.attention().map { it.id })
        assertEquals(OffsetDateTime.parse("2026-01-01T10:01:00Z"), store.waitingSince("s-1"))

        store.applyEvent(DaemonEvent.NotificationUpdated(notification("n-2", NotificationKind.ApprovalRequired, NotificationStatus.Acknowledged)))
        assertEquals(OffsetDateTime.parse("2026-01-01T10:05:00Z"), store.waitingSince("s-1"))
        store.applyEvent(DaemonEvent.NotificationUpdated(notification("n-1", status = NotificationStatus.Archived)))
        assertEquals(OwnerState.Working, owner())
    }

    @Test
    fun `attention does not lift an ended session or one with a runtime problem`() {
        store.applySeed(
            listOf(
                session("ended", state = SessionState.Stopped),
                session("lost", runtimeState = RuntimeState.Lost),
                session("conflict", runtimeState = RuntimeState.Conflict),
                session("blocked", activity = AgentActivity.Blocked),
                session("shell", activity = null),
            ),
            listOf("ended", "lost", "conflict").map { notification("n-$it", sessionId = it) },
        )
        assertEquals(
            listOf(OwnerState.Ended, OwnerState.Ended, OwnerState.RuntimeProblem, OwnerState.NeedsOwner, OwnerState.NoAgentState),
            store.ownerSessions().map { it.ownerState },
        )
    }

    @Test
    fun `an idle session turns Idle only after the threshold on virtual time`() {
        store.applySeed(listOf(session(activity = AgentActivity.Working)), emptyList())
        store.applyEvent(agentState(AgentActivity.Idle, revision = 1))
        assertEquals(OwnerState.Working, owner())
        val deadline = store.nextIdleDeadline()
        assertNotNull(deadline)

        clock += threshold - 1.seconds
        assertEquals(OwnerState.Working, owner())
        assertEquals(deadline, store.nextIdleDeadline())

        clock += 1.seconds
        assertEquals(OwnerState.Idle, owner())
        assertNull(store.nextIdleDeadline())

        store.applyEvent(agentState(AgentActivity.Working, revision = 2))
        assertEquals(OwnerState.Working, owner())
        store.applyEvent(agentState(AgentActivity.Idle, revision = 3))
        clock += threshold - 1.seconds
        assertEquals(OwnerState.Working, owner())
    }

    @Test
    fun `a seed from a new runtime does not carry the idle clock over`() {
        store.applySeed(listOf(session()), emptyList())
        clock += 200.seconds
        store.applySeed(listOf(session(generation = "2")), emptyList())
        clock += 100.seconds
        assertEquals(OwnerState.Working, owner())

        store.applySeed(listOf(session(generation = "2", workerInstance = "w-2")), emptyList())
        clock += 200.seconds
        assertEquals(OwnerState.Working, owner())
    }

    @Test
    fun `a re-seed does not restart the idle clock of a session that stays idle`() {
        store.applySeed(listOf(session()), emptyList())
        clock += 200.seconds
        store.applySeed(listOf(session()), emptyList())
        clock += 100.seconds
        assertEquals(OwnerState.Idle, owner())

        // A session that stopped being idle and became idle again starts over.
        store.applySeed(listOf(session(activity = AgentActivity.Working)), emptyList())
        store.applySeed(listOf(session()), emptyList())
        clock += 299.seconds
        assertEquals(OwnerState.Working, owner())
    }

    @Test
    fun `a running subagent or a background task keeps an idle session Working`() {
        store.applySeed(listOf(session(subagents = listOf(subagent(1, SubagentLifecycle.Running)))), emptyList())
        clock += threshold * 2
        assertEquals(OwnerState.Working, owner())
        assertNull(store.nextIdleDeadline())

        assertEquals(EventOutcome.Ignored(IgnoreReason.StaleRevision), store.applyEvent(subagentState(1, SubagentLifecycle.Completed)))
        assertEquals(OwnerState.Working, owner())
        // The idle clock starts when the last work ends.
        store.applyEvent(subagentState(2, SubagentLifecycle.Completed))
        assertEquals(OwnerState.Working, owner())
        clock += threshold
        assertEquals(OwnerState.Idle, owner())

        store.applyEvent(backgroundState(1, BackgroundStatus.Pending))
        assertEquals(OwnerState.Working, owner())
        val staleClear = DaemonEvent.BackgroundState(BackgroundStateUpdate("s-1", SessionBackground(DecimalWire(1UL), emptyList()), runtime()))
        assertEquals(EventOutcome.Ignored(IgnoreReason.StaleRevision), store.applyEvent(staleClear))
        store.applyEvent(backgroundState(2, null))
        assertEquals(OwnerState.Working, owner())
        clock += threshold
        assertEquals(OwnerState.Idle, owner())
    }

    @Test
    fun `a re-seed keeps the activity baseline while the runtime is unchanged and drops it when the runtime changed`() {
        store.applySeed(listOf(session(activity = AgentActivity.Working)), emptyList())
        store.applyEvent(agentState(AgentActivity.Idle, revision = 5))

        store.applySeed(listOf(session(activity = AgentActivity.Idle)), emptyList())
        assertEquals(EventOutcome.Ignored(IgnoreReason.StaleRevision), store.applyEvent(agentState(AgentActivity.Working, revision = 4)))

        store.applySeed(listOf(session(activity = AgentActivity.Idle, generation = "2")), emptyList())
        assertEquals(EventOutcome.Applied, store.applyEvent(agentState(AgentActivity.Working, revision = 1, runtime = runtime(2))))
    }

    @Test
    fun `a re-seed reports changed attention`() {
        store.applySeed(listOf(session()), listOf(notification("n-1")))
        assertEquals(SeedChange(attention = 0), store.applySeed(listOf(session()), listOf(notification("n-1"))))
        assertEquals(SeedChange(attention = 2), store.applySeed(listOf(session()), listOf(notification("n-2"))))
    }
}
