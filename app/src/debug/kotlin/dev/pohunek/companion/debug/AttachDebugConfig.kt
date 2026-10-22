package dev.pohunek.companion.debug

import android.content.res.Resources
import dev.pohunek.companion.R
import dev.pohunek.companion.protocol.AttachPolicy
import dev.pohunek.companion.protocol.ClientConfig
import kotlin.time.Duration.Companion.milliseconds

/** Timeouts and retry policy of the attach debug screen, read from the resources next to their documentation. */
internal class AttachDebugConfig(val client: ClientConfig, val policy: AttachPolicy) {
    companion object {
        /** Throws [IllegalArgumentException] naming the invalid value; the screen shows that message instead of connecting. */
        fun from(resources: Resources): AttachDebugConfig = AttachDebugConfig(
            ClientConfig(
                connectTimeout = resources.getInteger(R.integer.attach_debug_connect_timeout_ms).toLong().milliseconds,
                requestTimeout = resources.getInteger(R.integer.attach_debug_request_timeout_ms).toLong().milliseconds,
            ),
            AttachPolicy(
                maxAttempts = resources.getInteger(R.integer.attach_debug_attempts),
                retryDelay = resources.getInteger(R.integer.attach_debug_retry_delay_ms).toLong().milliseconds,
            ),
        )
    }
}
