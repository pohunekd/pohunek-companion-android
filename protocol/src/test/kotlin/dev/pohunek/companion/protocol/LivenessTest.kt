package dev.pohunek.companion.protocol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class LivenessTest {
    private val policy = LivenessPolicy(interval = 1_000.milliseconds, timeout = 300.milliseconds)

    @Test
    fun aProbeThatNeverAnswersFailsAtIntervalPlusTimeout() = runTest {
        val outcome = async { runCatching { watchLiveness(policy) { awaitCancellation() } } }
        val failure = outcome.await().exceptionOrNull()
        assertTrue(failure is ProtocolClientException.LivenessTimeout)
        assertEquals(300L, (failure as ProtocolClientException.LivenessTimeout).timeoutMillis)
        assertEquals(1_300L, currentTime)
    }

    @Test
    fun answeredProbesKeepTheWatchRunningAtTheInterval() = runTest {
        var probes = 0
        val outcome = async { runCatching { watchLiveness(policy) { if (++probes <= 3) delay(299) else awaitCancellation() } } }
        val failure = outcome.await().exceptionOrNull()
        assertTrue(failure is ProtocolClientException.LivenessTimeout)
        assertEquals(4, probes)
        assertEquals(3 * (1_000L + 299L) + 1_000L + 300L, currentTime)
    }

    @Test
    fun aProbeFailureIsPassedOnUnchanged() = runTest {
        val lost = ProtocolClientException.ConnectionLost("reset")
        val outcome = async { runCatching { watchLiveness(policy) { throw lost } } }
        assertEquals(lost, outcome.await().exceptionOrNull())
    }
}
