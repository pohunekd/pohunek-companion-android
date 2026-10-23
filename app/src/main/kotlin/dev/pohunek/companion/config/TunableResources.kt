package dev.pohunek.companion.config

import androidx.annotation.IntegerRes
import androidx.annotation.StringRes
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.config.Tunable

/** The default value and the description of a tunable, both declared in `res/values/config.xml`. */
class TunableResources(@IntegerRes val default: Int, @StringRes val help: Int)

private val RESOURCES: Map<Tunable, TunableResources> = mapOf(
    Tunable.CONNECT_TIMEOUT_MS to TunableResources(R.integer.connect_timeout_ms, R.string.connect_timeout_ms_help),
    Tunable.REQUEST_TIMEOUT_MS to TunableResources(R.integer.request_timeout_ms, R.string.request_timeout_ms_help),
    Tunable.SESSION_ACTION_TIMEOUT_MS to TunableResources(R.integer.session_action_timeout_ms, R.string.session_action_timeout_ms_help),
    Tunable.CREATE_TIMEOUT_MS to TunableResources(R.integer.create_timeout_ms, R.string.create_timeout_ms_help),
    Tunable.ATTACH_ATTEMPTS to TunableResources(R.integer.attach_attempts, R.string.attach_attempts_help),
    Tunable.ATTACH_RETRY_DELAY_MS to TunableResources(R.integer.attach_retry_delay_ms, R.string.attach_retry_delay_ms_help),
    Tunable.RESEED_INTERVAL_SECS to TunableResources(R.integer.reseed_interval_secs, R.string.reseed_interval_secs_help),
    Tunable.DISCOVER_INTERVAL_SECS to TunableResources(R.integer.discover_interval_secs, R.string.discover_interval_secs_help),
    Tunable.DISCOVER_TIMEOUT_SECS to TunableResources(R.integer.discover_timeout_secs, R.string.discover_timeout_secs_help),
    Tunable.RECONNECT_INITIAL_MS to TunableResources(R.integer.reconnect_initial_ms, R.string.reconnect_initial_ms_help),
    Tunable.RECONNECT_MAX_MS to TunableResources(R.integer.reconnect_max_ms, R.string.reconnect_max_ms_help),
    Tunable.LIVENESS_INTERVAL_MS to TunableResources(R.integer.liveness_interval_ms, R.string.liveness_interval_ms_help),
    Tunable.LIVENESS_TIMEOUT_MS to TunableResources(R.integer.liveness_timeout_ms, R.string.liveness_timeout_ms_help),
    Tunable.ATTACH_LIVENESS_INTERVAL_MS to TunableResources(R.integer.attach_liveness_interval_ms, R.string.attach_liveness_interval_ms_help),
    Tunable.ATTACH_LIVENESS_TIMEOUT_MS to TunableResources(R.integer.attach_liveness_timeout_ms, R.string.attach_liveness_timeout_ms_help),
    Tunable.REATTACH_INITIAL_MS to TunableResources(R.integer.reattach_initial_ms, R.string.reattach_initial_ms_help),
    Tunable.REATTACH_MAX_MS to TunableResources(R.integer.reattach_max_ms, R.string.reattach_max_ms_help),
    Tunable.REATTACH_GIVE_UP_MS to TunableResources(R.integer.reattach_give_up_ms, R.string.reattach_give_up_ms_help),
    Tunable.IDLE_THRESHOLD_SECS to TunableResources(R.integer.idle_threshold_secs, R.string.idle_threshold_secs_help),
    Tunable.ROW_BADGE_MAX_CHARS to TunableResources(R.integer.row_badge_max_chars, R.string.row_badge_max_chars_help),
    Tunable.SECTION_TITLE_MAX_CHARS to TunableResources(R.integer.section_title_max_chars, R.string.section_title_max_chars_help),
    Tunable.DETAIL_VALUE_MAX_CHARS to TunableResources(R.integer.detail_value_max_chars, R.string.detail_value_max_chars_help),
    Tunable.NOTIFICATION_PAGE_SIZE to TunableResources(R.integer.notification_page_size, R.string.notification_page_size_help),
    Tunable.NEW_SESSION_COLS to TunableResources(R.integer.new_session_cols, R.string.new_session_cols_help),
    Tunable.NEW_SESSION_ROWS to TunableResources(R.integer.new_session_rows, R.string.new_session_rows_help),
    Tunable.PENDING_PLACEMENT_MAX_AGE_SECS to TunableResources(R.integer.pending_placement_max_age_secs, R.string.pending_placement_max_age_secs_help),
    Tunable.LOG_MAX_BYTES to TunableResources(R.integer.log_max_bytes, R.string.log_max_bytes_help),
)

/** The resources of a tunable; every [Tunable] has them, which the first lookup checks. */
fun Tunable.resources(): TunableResources {
    check(RESOURCES.keys == Tunable.entries.toSet()) { "config.xml lacks resources for ${Tunable.entries - RESOURCES.keys}" }
    return RESOURCES.getValue(this)
}
