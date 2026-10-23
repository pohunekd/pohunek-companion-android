package dev.pohunek.companion.config

import android.content.res.Resources
import androidx.annotation.IntegerRes
import dev.pohunek.companion.R
import dev.pohunek.companion.protocol.AttachPolicy
import dev.pohunek.companion.protocol.ClientConfig
import kotlin.time.Duration.Companion.milliseconds

/** Validated tunables from `res/values/config.xml`. */
class AppConfig private constructor(val client: ClientConfig, val attachPolicy: AttachPolicy, val logMaxBytes: Long) {
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
                    atLeast(resources, "log_max_bytes", R.integer.log_max_bytes, MIN_LOG_MAX_BYTES),
                ),
            )
        } catch (e: InvalidKey) {
            AppConfigResult.Invalid(e.key, e.detail)
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
