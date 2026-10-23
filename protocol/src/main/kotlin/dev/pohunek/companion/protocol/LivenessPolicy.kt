package dev.pohunek.companion.protocol

import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * How an idle attach is checked for a connection that died without a FIN or RST: a probe every [interval], each given [timeout] to
 * answer. A dead connection is reported at most [interval] + [timeout] after it died. Both values are required and positive.
 */
class LivenessPolicy(val interval: Duration, val timeout: Duration) {
    init {
        require(interval.isPositive()) { "interval must be positive" }
        require(timeout.isPositive()) { "timeout must be positive" }
    }
}

/** Calls [probe] with [LivenessPolicy.timeout] every [LivenessPolicy.interval] until it throws or the caller cancels. */
suspend fun watchLiveness(policy: LivenessPolicy, probe: suspend (timeout: Duration) -> Unit): Nothing {
    while (true) {
        delay(policy.interval)
        probe(policy.timeout)
    }
}
