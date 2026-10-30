package dev.pohunek.companion.config

import dev.pohunek.companion.core.data.attach.AttachSettings
import dev.pohunek.companion.core.data.attach.ReattachPolicy
import dev.pohunek.companion.core.data.discovery.DiscoveryConfig
import dev.pohunek.companion.core.data.live.LiveConfig
import dev.pohunek.companion.core.domain.config.Tunable
import dev.pohunek.companion.protocol.AttachPolicy
import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.LivenessPolicy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The longest texts the screens show before they cut them, the longest group name read from metadata (UTF-8 bytes), and the grace before a host shows down. */
data class DisplayLimits(
    val rowBadgeMaxChars: Int,
    val sectionTitleMaxChars: Int,
    val detailValueMaxChars: Int,
    val groupNameMaxBytes: Int,
    /** How long a host without a connection keeps looking connected in the session list. */
    val unreachableGrace: Duration,
)

/** What starting a session needs. */
class NewSessionSettings(
    /** [AppConfig.client] with the timeout of a `session.new`, which waits for the agent to start. */
    val client: ClientConfig,
    val cols: Int,
    val rows: Int,
    /** Initial state of the form's "Open after creating" switch. */
    val attachAfterCreate: Boolean,
)

/** The tunables as typed settings, built from a complete set of values that passed `tunableProblems`. */
class AppConfig private constructor(
    val client: ClientConfig,
    /** [client] with the longer request timeout of a session stop or remove. */
    val actionClient: ClientConfig,
    val newSession: NewSessionSettings,
    val attachSettings: AttachSettings,
    val live: LiveConfig,
    val discovery: DiscoveryConfig,
    val logMaxBytes: Long,
    val display: DisplayLimits,
) {
    companion object {
        /** [values] holds a value for every [Tunable] and obeys their rules. */
        fun build(values: Map<Tunable, Long>, attachAfterCreate: Boolean): AppConfig {
            fun value(tunable: Tunable): Long = values.getValue(tunable)
            val client = ClientConfig(
                connectTimeout = value(Tunable.CONNECT_TIMEOUT_MS).milliseconds,
                requestTimeout = value(Tunable.REQUEST_TIMEOUT_MS).milliseconds,
            )
            return AppConfig(
                client,
                ClientConfig(connectTimeout = client.connectTimeout, requestTimeout = value(Tunable.SESSION_ACTION_TIMEOUT_MS).milliseconds),
                NewSessionSettings(
                    ClientConfig(connectTimeout = client.connectTimeout, requestTimeout = value(Tunable.CREATE_TIMEOUT_MS).milliseconds),
                    value(Tunable.NEW_SESSION_COLS).toInt(),
                    value(Tunable.NEW_SESSION_ROWS).toInt(),
                    attachAfterCreate,
                ),
                AttachSettings(
                    client,
                    AttachPolicy(
                        maxAttempts = value(Tunable.ATTACH_ATTEMPTS).toInt(),
                        retryDelay = value(Tunable.ATTACH_RETRY_DELAY_MS).milliseconds,
                    ),
                    LivenessPolicy(
                        interval = value(Tunable.ATTACH_LIVENESS_INTERVAL_MS).milliseconds,
                        timeout = value(Tunable.ATTACH_LIVENESS_TIMEOUT_MS).milliseconds,
                    ),
                    ReattachPolicy(
                        initialDelay = value(Tunable.REATTACH_INITIAL_MS).milliseconds,
                        maxDelay = value(Tunable.REATTACH_MAX_MS).milliseconds,
                        giveUpAfter = value(Tunable.REATTACH_GIVE_UP_MS).milliseconds,
                    ),
                ),
                LiveConfig(
                    reseedInterval = value(Tunable.RESEED_INTERVAL_SECS).seconds,
                    reconnectInitial = value(Tunable.RECONNECT_INITIAL_MS).milliseconds,
                    reconnectMax = value(Tunable.RECONNECT_MAX_MS).milliseconds,
                    backoffReset = value(Tunable.BACKOFF_RESET_MS).milliseconds,
                    livenessInterval = value(Tunable.LIVENESS_INTERVAL_MS).milliseconds,
                    livenessTimeout = value(Tunable.LIVENESS_TIMEOUT_MS).milliseconds,
                    idleThreshold = value(Tunable.IDLE_THRESHOLD_SECS).seconds,
                    notificationPageSize = value(Tunable.NOTIFICATION_PAGE_SIZE).toInt(),
                ),
                DiscoveryConfig(
                    interval = value(Tunable.DISCOVER_INTERVAL_SECS).seconds,
                    timeout = value(Tunable.DISCOVER_TIMEOUT_SECS).seconds,
                ),
                value(Tunable.LOG_MAX_BYTES),
                DisplayLimits(
                    value(Tunable.ROW_BADGE_MAX_CHARS).toInt(),
                    value(Tunable.SECTION_TITLE_MAX_CHARS).toInt(),
                    value(Tunable.DETAIL_VALUE_MAX_CHARS).toInt(),
                    value(Tunable.GROUP_NAME_MAX_BYTES).toInt(),
                    value(Tunable.UNREACHABLE_GRACE_MS).milliseconds,
                ),
            )
        }
    }
}
