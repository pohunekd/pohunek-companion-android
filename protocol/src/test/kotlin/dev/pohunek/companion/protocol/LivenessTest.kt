package dev.pohunek.companion.protocol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class LivenessTest {
    private val policy = LivenessPolicy(interval = 1_000.milliseconds, timeout = 300.milliseconds)

    @Test
    fun probesRunAtTheIntervalWithTheConfiguredTimeoutUntilOneFails() = runTest {
        val starts = mutableListOf<Long>()
        val timeouts = mutableListOf<Long>()
        val dead = ProtocolClientException.LivenessTimeout(300)
        val outcome = async {
            runCatching {
                watchLiveness(policy) {
                    starts += currentTime
                    timeouts += it.inWholeMilliseconds
                    if (starts.size == 3) throw dead
                }
            }
        }
        assertEquals(dead, outcome.await().exceptionOrNull())
        assertEquals(listOf(1_000L, 2_000L, 3_000L), starts)
        assertEquals(listOf(300L, 300L, 300L), timeouts)
    }
}
