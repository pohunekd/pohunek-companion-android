package dev.pohunek.companion.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationEventsTest {
    private fun decode(name: String) = EventDecoder.decode(Fixtures.captured(name))

    private fun page(name: String, id: String) = ResponseDecoder().decode(Fixtures.captured(name), id).decodeResult(NotificationListResult.serializer())

    @Test
    fun `agent_state decodes with its runtime, epoch and revision`() {
        val update = (decode("agent_state") as DaemonEvent.AgentState).update
        assertEquals("s-01M5MBCMAVJ27MX7S3W8P0PGV1", update.sessionId)
        assertEquals(AgentActivity.Working, update.activity)
        assertEquals(StateSource.Screen, update.source)
        assertEquals("d-303-1792758140128403886-0", update.activityEpoch)
        assertEquals(DecimalWire(50UL), update.revision)
        assertEquals(DecimalWire(1UL), update.runtime?.runtimeGeneration)
    }

    @Test
    fun `agent_state without the additive fields still decodes`() {
        val line = """{"v":4,"event":"agent_state","session_id":"s","activity":"idle","source":"osc_title"}"""
        val update = (EventDecoder.decode(line) as DaemonEvent.AgentState).update
        assertNull(update.runtime)
        assertNull(update.activityEpoch)
        assertNull(update.revision)
    }

    @Test
    fun `subagent_state and background_state decode the fields owner state reads`() {
        val subagent = (decode("subagent_state") as DaemonEvent.SubagentState).update
        assertEquals(SubagentInfo("sub-1", "claude", SubagentLifecycle.Running, DecimalWire(7UL)), subagent.subagent)
        assertEquals(DecimalWire(2UL), subagent.runtime?.runtimeGeneration)

        val background = (decode("background_state") as DaemonEvent.BackgroundState).update.background
        assertEquals(DecimalWire(9UL), background?.revision)
        assertEquals(listOf(BackgroundItem("b-1", BackgroundStatus.Running), BackgroundItem("b-2", BackgroundStatus.Other)), background?.items)

        val cleared = EventDecoder.decode("""{"v":4,"event":"background_state","session_id":"s","background":null}""") as DaemonEvent.BackgroundState
        assertNull(cleared.update.background)
    }

    @Test
    fun `notification events decode the record or the deleted id`() {
        val created = (decode("notification_created") as DaemonEvent.NotificationCreated).record
        assertEquals(NotificationKind.AgentBlocked, created.kind)
        assertEquals(NotificationStatus.Unread, created.status)
        assertEquals("s-01M5MBCMAP8JVX9AXXHRBDFMMB", created.sessionId)
        val updated = (decode("notification_updated") as DaemonEvent.NotificationUpdated).record
        assertEquals(created.id, updated.id)
        assertEquals(NotificationStatus.Read, updated.status)
        assertTrue(updated.status.isOpen)
        assertEquals(created.id, (decode("notification_deleted") as DaemonEvent.NotificationDeleted).notificationId)
    }

    @Test
    fun `revisions and generations must be canonical decimal strings`() {
        assertEquals(DecimalWire(18446744073709551615UL), DecimalWire.parse("18446744073709551615"))
        for (bad in listOf("", "01", "-1", "+1", " 1", "1.0", "18446744073709551616", "x")) assertNull(bad, DecimalWire.parse(bad))
        assertTrue(DecimalWire(10UL) > DecimalWire(9UL))

        val runtime = """"runtime":{"worker_instance_id":"w","runtime_generation":"1"}"""
        for (revision in listOf("\"01\"", "5", "\"\"")) {
            val line = """{"v":4,"event":"agent_state","session_id":"s","activity":"idle","source":"screen",$runtime,"revision":$revision}"""
            assertTrue(revision, EventDecoder.decode(line) is DaemonEvent.Malformed)
        }
    }

    @Test
    fun `a payload of the wrong shape decodes to Malformed`() {
        for (line in listOf(
            """{"v":4,"event":"agent_state","session_id":"s","activity":"sleeping","source":"screen"}""",
            """{"v":4,"event":"subagent_state","session_id":"s"}""",
            """{"v":4,"event":"notification_created"}""",
            """{"v":4,"event":"notification_created","record":{"id":"n","kind":"agent_blocked","status":"vanished","created_at":"t"}}""",
            """{"v":4,"event":"notification_deleted","notification_id":7}""",
        )) {
            assertTrue(line, EventDecoder.decode(line) is DaemonEvent.Malformed)
        }
    }

    @Test
    fun `the notification list request carries only the set parameters`() {
        val line = Requests.notificationList("r", NotificationListParams(NotificationKind.ApprovalRequired, limit = 200)).toLine()
        assertEquals("""{"v":{"minimum":4,"maximum":4},"id":"r","method":"notification.list","params":{"kind":"approval_required","limit":200}}""", line)
        assertTrue(Requests.notificationList("r", NotificationListParams()).toLine().endsWith(""""params":{}}"""))
    }

    @Test
    fun `captured pages decode and are followed by their cursor`() = runBlocking {
        val first = page("notification-list-page-1", "req-1")
        assertEquals(2, first.notifications.size)
        assertEquals("2026-10-23T12:23:16.987665134Z|n-303-1792758196987674174-3", first.nextCursor)
        val second = page("notification-list-page-2", "req-2")
        assertNull(second.nextCursor)

        val requests = ArrayList<NotificationListParams>()
        val records = listAllNotifications(NotificationKind.AgentBlocked, pageSize = 2) { params ->
            requests += params
            if (params.cursor == null) first else second
        }
        assertEquals(first.notifications + second.notifications, records)
        assertEquals(listOf<String?>(null, first.nextCursor), requests.map { it.cursor })
        assertTrue(requests.all { it.kind == NotificationKind.AgentBlocked && it.limit == 2 })
    }

    @Test
    fun `a page that is too large is retried at half the size with the same cursor`() = runBlocking {
        val tooLarge = ProtocolClientException.Daemon(decodeError("notification-list-too-large-response"))
        val first = page("notification-list-page-1", "req-1")
        val second = page("notification-list-page-2", "req-2")
        val requests = ArrayList<NotificationListParams>()
        val records = listAllNotifications(NotificationKind.AgentBlocked, pageSize = 200) { params ->
            requests += params
            when {
                (params.limit ?: 0) > 2 -> throw tooLarge
                params.cursor == null -> first
                else -> second
            }
        }
        assertEquals(4, records.size)
        // 200 halves to 100, 50, 25, 12, 6, 3 and 1 until a page fits; the second page keeps that size and follows the cursor.
        assertEquals(listOf<Int?>(200, 100, 50, 25, 12, 6, 3, 1, 1), requests.map { it.limit })
        assertEquals(List<String?>(8) { null } + first.nextCursor, requests.map { it.cursor })
    }

    @Test
    fun `a page of one that is still too large fails with the daemon error`() = runBlocking {
        val tooLarge = ProtocolClientException.Daemon(decodeError("notification-list-too-large-response"))
        val limits = ArrayList<Int?>()
        val failure = runCatching {
            listAllNotifications(NotificationKind.ApprovalRequired, pageSize = 3) { params ->
                limits += params.limit
                throw tooLarge
            }
        }.exceptionOrNull()
        assertTrue(failure === tooLarge)
        assertEquals(listOf<Int?>(3, 1), limits)
    }

    @Test
    fun `other daemon errors and a cursor that does not advance fail the listing`() = runBlocking {
        val other = ProtocolClientException.Daemon(ProtocolError(ErrorClass.Daemon, "internal", "boom"))
        val daemonFailure = runCatching { listAllNotifications(NotificationKind.AgentBlocked, 10) { throw other } }.exceptionOrNull()
        assertTrue(daemonFailure === other)
        val stuck = NotificationListResult(page("notification-list-page-2", "req-2").notifications, nextCursor = "c")
        val cursorFailure = runCatching { listAllNotifications(NotificationKind.AgentBlocked, 10) { stuck } }.exceptionOrNull()
        assertTrue(cursorFailure is ProtocolClientException.MalformedResponse)
    }

    private fun decodeError(name: String): ProtocolError =
        runCatching { ResponseDecoder().decode(Fixtures.captured(name), "req-1") }.exceptionOrNull().let { (it as ProtocolClientException.Daemon).error }
}
