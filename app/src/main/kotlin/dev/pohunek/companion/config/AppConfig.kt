package dev.pohunek.companion.config

import android.content.res.Resources
import androidx.annotation.IntegerRes
import dev.pohunek.companion.R
import dev.pohunek.companion.core.data.live.LiveConfig
import dev.pohunek.companion.protocol.AttachPolicy
import dev.pohunek.companion.protocol.ClientConfig
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Validated tunables from `res/values/config.xml`. */
class AppConfig private constructor(val client: ClientConfig, val attachPolicy: AttachPolicy, val live: LiveConfig, val logMaxBytes: Long) {
    companion object {
        fun load(resources: Resources): AppConfigResult = try {
            AppConfigResult.Valid(
                AppConfig(
                    ClientConfig(
                        connectTimeout = positive(resources, "connect_timeout_ms", R.integer.connect_timeout_ms).milliseconds,
                        requestTimeout = positive(resources, "request_timeout_ms", R.integer.request_timeout_ms).milliseconds,
                    ),
                    AttachPolicy(
                        maxAttempts = positive(resources, "attach_attempts", R.integer.attach_attempts).toInt(),
                        retryDelay = positive(resources, "attach_retry_delay_ms", R.integer.attach_retry_delay_ms).milliseconds,
                    ),
                    liveConfig(resources),
                    atLeast(resources, "log_max_bytes", R.integer.log_max_bytes, MIN_LOG_MAX_BYTES),
                ),
            )
        } catch (e: InvalidKey) {
            AppConfigResult.Invalid(e.key, e.detail)
        }

        private fun liveConfig(resources: Resources): LiveConfig {
            val initial = positive(resources, "reconnect_initial_ms", R.integer.reconnect_initial_ms)
            return LiveConfig(
                reseedInterval = positive(resources, "reseed_interval_secs", R.integer.reseed_interval_secs).seconds,
                reconnectInitial = initial.milliseconds,
                reconnectMax = atLeast(resources, "reconnect_max_ms", R.integer.reconnect_max_ms, initial).milliseconds,
                livenessInterval = positive(resources, "liveness_interval_ms", R.integer.liveness_interval_ms).milliseconds,
                livenessTimeout = positive(resources, "liveness_timeout_ms", R.integer.liveness_timeout_ms).milliseconds,
            )
        }

        private const val MIN_LOG_MAX_BYTES = 4096L

        private fun atLeast(resources: Resources, key: String, @IntegerRes id: Int, minimum: Long): Long {
            val value = resources.getInteger(id)
            if (value < minimum) throw InvalidKey(key, "must be at least $minimum, got $value")
            return value.toLong()
        }

        private fun positive(resources: Resources, key: String, @IntegerRes id: Int): Long {
            val value = resources.getInteger(id)
            if (value <= 0) throw InvalidKey(key, "must be greater than 0, got $value")
            return value.toLong()
        }
    }

    private class InvalidKey(val key: String, val detail: String) : Exception("$key $detail")
}

sealed interface AppConfigResult {
    data class Valid(val config: AppConfig) : AppConfigResult

    /** [key] names the entry of `config.xml` that failed validation. */
    data class Invalid(val key: String, val detail: String) : AppConfigResult
}
