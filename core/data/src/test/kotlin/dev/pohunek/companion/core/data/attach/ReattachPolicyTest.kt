package dev.pohunek.companion.core.data.attach

import dev.pohunek.companion.protocol.ErrorClass
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.ProtocolError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReattachPolicyTest {
    private val policy = ReattachPolicy(initialDelay = 500.milliseconds, maxDelay = 4.seconds, giveUpAfter = 60.seconds)

    @Test
    fun delayDoublesPerFailureUpToTheCap() {
        val delays = (1..6).map { policy.delayFor(it) }
        assertEquals(listOf(500.milliseconds, 1.seconds, 2.seconds, 4.seconds, 4.seconds, 4.seconds), delays)
    }

    @Test
    fun hugeFailureCountDoesNotOverflow() {
        assertEquals(4.seconds, policy.delayFor(Int.MAX_VALUE))
    }

    @Test
    fun endedAndRemovedSessionsAreFinal() {
        for (code in listOf("session_not_found", "session_not_running", "session_external_read_only", "session_runtime_lost")) {
            assertEquals(FailureKind.SessionGone, daemonError(code).finalKind())
        }
    }

    @Test
    fun transportAndOtherDaemonErrorsAreRetried() {
        assertNull(daemonError("worker_attach_stream_failed").finalKind())
        assertNull(ProtocolClientException.ConnectionLost("gone").finalKind())
        assertNull(ProtocolClientException.ConnectionFailed(HostRoute.parse("127.0.0.1:1"), IOException("refused")).finalKind())
    }

    private fun daemonError(code: String) = ProtocolClientException.Daemon(ProtocolError(ErrorClass.Runtime, code, "msg"))
}
