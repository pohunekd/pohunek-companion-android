package dev.pohunek.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionLifecycleWireTest {
    private fun line(name: String) = Fixtures.captured(name).trim()

    @Test
    fun `stop remove and consent requests carry the bare session id`() {
        assertEquals("""{"v":{"minimum":4,"maximum":4},"id":"r1","method":"session.stop","params":"s-1"}""", Requests.sessionStop("r1", "s-1").toLine())
        assertEquals("""{"v":{"minimum":4,"maximum":4},"id":"r2","method":"session.remove","params":"s-1"}""", Requests.sessionRemove("r2", "s-1").toLine())
        assertEquals(
            """{"v":{"minimum":4,"maximum":4},"id":"r3","method":"session.remove_accepting_unconfirmed","params":"s-1"}""",
            Requests.sessionRemoveAcceptingUnconfirmed("r3", "s-1").toLine(),
        )
    }

    @Test
    fun `captured stop results decode, a stop of an ended session reports false`() {
        val stopped = ResponseDecoder().decode(line("session-stop-response"), "cap-1").decodeResult(SessionStopResult.serializer())
        assertTrue(stopped.stopped)
        val ended = ResponseDecoder().decode(line("session-stop-ended-response"), "cap-2").decodeResult(SessionStopResult.serializer())
        assertFalse(ended.stopped)
    }

    @Test
    fun `captured remove results decode with the process list absent`() {
        val live = ResponseDecoder().decode(line("session-remove-response"), "cap-5").decodeResult(SessionRemoveResult.serializer())
        assertEquals(SessionRemoveResult(removed = true, stopped = true, worktreesRemoved = 0, worktreesFailed = 0), live)
        val ended = ResponseDecoder().decode(line("session-remove-ended-response"), "cap-3").decodeResult(SessionRemoveResult.serializer())
        assertEquals(false, ended.stopped)
        assertEquals(emptyList<UnconfirmedProcess>(), ended.acceptedUnconfirmedProcesses)
    }

    @Test
    fun `accepted unconfirmed processes decode and read as pid with command`() {
        val result = ResponseDecoder().decode(line("session-remove-accepted-processes-response"), "cap-7").decodeResult(SessionRemoveResult.serializer())
        assertEquals(1, result.worktreesFailed)
        assertEquals(listOf(UnconfirmedProcess(4242, "node"), UnconfirmedProcess(4243, null)), result.acceptedUnconfirmedProcesses)
        assertEquals(listOf("4242 (node)", "4243"), result.acceptedUnconfirmedProcesses.map { it.describe() })
    }

    @Test
    fun `refusals surface the daemon code, message and recover hint`() {
        try {
            ResponseDecoder().decode(line("session-remove-ambiguous-response"), "cap-8")
            fail("the refusal must throw")
        } catch (e: ProtocolClientException.Daemon) {
            assertEquals("runtime_supervision_ambiguous", e.error.code)
            assertEquals("inspect and end the processes that belong to the session, then retry", e.error.recover)
        }
        try {
            ResponseDecoder().decode(line("session-remove-not-found-response"), "cap-4")
            fail("the refusal must throw")
        } catch (e: ProtocolClientException.Daemon) {
            assertEquals("session_not_found", e.error.code)
            assertEquals(null, e.error.recover)
        }
    }
}
