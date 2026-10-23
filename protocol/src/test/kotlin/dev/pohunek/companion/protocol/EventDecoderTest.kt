package dev.pohunek.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDecoderTest {
    private fun decode(name: String) = EventDecoder.decode(Fixtures.captured(name))

    @Test
    fun `each modelled session event decodes to its type with the snapshot`() {
        val created = decode("session_created") as DaemonEvent.SessionCreated
        assertEquals("capture-a", created.session.name)
        assertEquals(SessionState.Running, created.session.state)
        val runtime = created.session.runtime
        assertEquals(RuntimeState.Live, runtime?.state)
        assertEquals("1", runtime?.runtimeGeneration)
        assertNull(runtime?.lossReason)

        assertEquals("capture-b", (decode("session_updated") as DaemonEvent.SessionUpdated).session.name)
        val stopped = decode("session_stopped") as DaemonEvent.SessionStopped
        assertEquals(SessionState.Stopped, stopped.session.state)
        assertEquals(RuntimeState.Terminal, stopped.session.runtime?.state)
        assertTrue(decode("session_removed") is DaemonEvent.SessionRemoved)
        assertTrue(decode("session_runtime_reconnected") is DaemonEvent.SessionRuntimeReconnected)
        val lost = decode("session_runtime_lost") as DaemonEvent.SessionRuntimeLost
        assertEquals(RuntimeState.Lost, lost.session.runtime?.state)
        assertEquals("runtime_lost", lost.session.runtime?.lossReason)
        val conflict = decode("session_runtime_conflict") as DaemonEvent.SessionRuntimeConflict
        assertEquals(RuntimeState.Conflict, conflict.session.runtime?.state)
        val recovered = decode("session_native_recovered") as DaemonEvent.SessionNativeRecovered
        assertEquals("2", recovered.session.runtime?.runtimeGeneration)
        assertEquals("runtime-recovered-0001", recovered.workerInstanceId)
        assertEquals(recovered.previousWorkerInstanceId, (decode("session_created") as DaemonEvent.SessionCreated).session.runtime?.workerInstanceId)
    }

    @Test
    fun `an unknown event name decodes to Unknown`() {
        assertEquals(DaemonEvent.Unknown("session_future_thing"), decode("unknown_event"))
        assertEquals(DaemonEvent.Unknown("agent_state"), EventDecoder.decode("""{"v":4,"event":"agent_state","session_id":"s-1"}"""))
    }

    @Test
    fun `the captured session list decodes with its runtime`() {
        val sessions = ResponseDecoder().decode(Fixtures.captured("session-list-response"), "req-1").decodeResult(sessionListSerializer)
        assertEquals(RuntimeState.Lost, sessions.single { it.name == "alpha-agent" }.runtime?.state)
        assertTrue(sessions.all { it.runtime != null })
    }

    @Test
    fun `malformed event lines are typed failures`() {
        assertThrows(ProtocolClientException.MalformedEvent::class.java) { EventDecoder.decode("not json") }
        assertThrows(ProtocolClientException.MalformedEvent::class.java) { EventDecoder.decode("""{"v":4}""") }
        assertThrows(ProtocolClientException.MalformedEvent::class.java) { EventDecoder.decode("""{"v":4,"event":"session_updated"}""") }
        assertThrows(ProtocolClientException.MalformedEvent::class.java) { EventDecoder.decode("""{"v":4,"event":"session_updated","session":{"id":"s"}}""") }
        assertThrows(ProtocolClientException.VersionMismatch::class.java) { EventDecoder.decode("""{"v":3,"event":"session_updated"}""") }
    }
}
