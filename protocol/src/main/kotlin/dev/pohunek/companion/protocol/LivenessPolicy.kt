package dev.pohunek.companion.protocol

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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

/** Runs [probe] every [LivenessPolicy.interval] until it fails or the caller cancels; never returns normally. */
suspend fun watchLiveness(policy: LivenessPolicy, probe: suspend () -> Unit): Nothing {
    while (true) {
        delay(policy.interval)
        withTimeoutOrNull(policy.timeout) { probe() }
            ?: throw ProtocolClientException.LivenessTimeout(policy.timeout.inWholeMilliseconds)
    }
}
