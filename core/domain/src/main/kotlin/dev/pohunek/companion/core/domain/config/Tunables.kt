package dev.pohunek.companion.core.domain.config

/** What a tunable's number counts; the Settings screen shows it next to the value. */
enum class TunableUnit { Milliseconds, Seconds, Bytes, Characters, Count }

/**
 * Every tunable of `res/values/config.xml`, in the file's order. The entry's lowercase name is the key in that file and in the saved
 * overrides; the default value is the file's. A value is a whole number from [minimum] to [maximum], which is [MAX_TUNABLE_VALUE] unless
 * the key is narrower.
 */
enum class Tunable(val minimum: Long, val unit: TunableUnit, val maximum: Long = MAX_TUNABLE_VALUE) {
    CONNECT_TIMEOUT_MS(1, TunableUnit.Milliseconds),
    REQUEST_TIMEOUT_MS(1, TunableUnit.Milliseconds),
    SESSION_ACTION_TIMEOUT_MS(1, TunableUnit.Milliseconds),
    CREATE_TIMEOUT_MS(1, TunableUnit.Milliseconds),
    ATTACH_ATTEMPTS(1, TunableUnit.Count),
    ATTACH_RETRY_DELAY_MS(1, TunableUnit.Milliseconds),
    RESEED_INTERVAL_SECS(1, TunableUnit.Seconds),
    DISCOVER_INTERVAL_SECS(1, TunableUnit.Seconds),
    DISCOVER_TIMEOUT_SECS(1, TunableUnit.Seconds),
    RECONNECT_INITIAL_MS(1, TunableUnit.Milliseconds),
    RECONNECT_MAX_MS(1, TunableUnit.Milliseconds),
    BACKOFF_RESET_MS(1, TunableUnit.Milliseconds),
    UNREACHABLE_GRACE_MS(1, TunableUnit.Milliseconds),
    LIVENESS_INTERVAL_MS(1, TunableUnit.Milliseconds),
    LIVENESS_TIMEOUT_MS(1, TunableUnit.Milliseconds),
    LIVENESS_FAILURES(1, TunableUnit.Count),
    LIVENESS_RETRY_MS(1, TunableUnit.Milliseconds),
    ATTACH_LIVENESS_INTERVAL_MS(1, TunableUnit.Milliseconds),
    ATTACH_LIVENESS_TIMEOUT_MS(1, TunableUnit.Milliseconds),
    REATTACH_INITIAL_MS(1, TunableUnit.Milliseconds),
    REATTACH_MAX_MS(1, TunableUnit.Milliseconds),
    REATTACH_GIVE_UP_MS(1, TunableUnit.Milliseconds),
    IDLE_THRESHOLD_SECS(1, TunableUnit.Seconds),
    ROW_BADGE_MAX_CHARS(1, TunableUnit.Characters),
    SECTION_TITLE_MAX_CHARS(1, TunableUnit.Characters),
    DETAIL_VALUE_MAX_CHARS(1, TunableUnit.Characters),
    NOTIFICATION_PAGE_SIZE(1, TunableUnit.Count),
    NEW_SESSION_COLS(1, TunableUnit.Count, MAX_TERMINAL_SIDE),
    NEW_SESSION_ROWS(1, TunableUnit.Count, MAX_TERMINAL_SIDE),
    WHEEL_MIN_INTERVAL_MS(1, TunableUnit.Milliseconds),
    WHEEL_BACKLOG_ROWS(1, TunableUnit.Count),
    SYNC_OUTPUT_TIMEOUT_MS(1, TunableUnit.Milliseconds),
    GROUP_NAME_MAX_BYTES(1, TunableUnit.Bytes, MAX_GROUP_NAME_BYTES),
    LOG_MAX_BYTES(MIN_LOG_MAX_BYTES, TunableUnit.Bytes),
    ;

    val key: String get() = name.lowercase()

    /** True when a running live loop captured the value at its start, so a change needs the loops restarted to take effect. */
    val readByLiveLoops: Boolean
        get() = when (this) {
            CONNECT_TIMEOUT_MS, REQUEST_TIMEOUT_MS, RESEED_INTERVAL_SECS, RECONNECT_INITIAL_MS, RECONNECT_MAX_MS, BACKOFF_RESET_MS,
            LIVENESS_INTERVAL_MS, LIVENESS_TIMEOUT_MS, LIVENESS_FAILURES, LIVENESS_RETRY_MS, IDLE_THRESHOLD_SECS, NOTIFICATION_PAGE_SIZE,
            -> true
            else -> false
        }

    /** The tunables this one may not fall below: a longest wait is never shorter than the first, nor than the hold that resets it. */
    val lowerBounds: List<Tunable>
        get() = when (this) {
            RECONNECT_MAX_MS -> listOf(RECONNECT_INITIAL_MS, BACKOFF_RESET_MS)
            REATTACH_MAX_MS -> listOf(REATTACH_INITIAL_MS)
            else -> emptyList()
        }
}

/** The configured values are integers in `config.xml`, so an override cannot exceed an Int either. */
const val MAX_TUNABLE_VALUE: Long = Int.MAX_VALUE.toLong()

/** The wire carries a terminal's columns and rows as 16-bit numbers. */
private const val MAX_TERMINAL_SIDE = 65_535L

private const val MIN_LOG_MAX_BYTES = 4096L

/** The daemon refuses a metadata value over 4096 bytes. */
private const val MAX_GROUP_NAME_BYTES = 4096L

/** Why the value of [tunable] is refused. */
data class TunableProblem(val tunable: Tunable, val message: String)

/** The result of checking what the owner entered. */
sealed interface OverrideCheck {
    /** [overrides] holds only the values that differ from the default. */
    data class Valid(val overrides: Map<Tunable, Long>) : OverrideCheck

    data class Invalid(val problems: List<TunableProblem>) : OverrideCheck
}

/** Every rule a complete set of [values] breaks, none when it is valid; a pair of ordered tunables reports both members. */
fun tunableProblems(values: Map<Tunable, Long>): List<TunableProblem> = Tunable.entries.flatMap { tunable ->
    val value = values.getValue(tunable)
    val own = when {
        value < tunable.minimum -> rangeProblem(tunable, value)
        value > tunable.maximum -> TunableProblem(tunable, "must be at most ${tunable.maximum}, got $value")
        else -> null
    }
    val ordering = tunable.lowerBounds.flatMap { floor ->
        val floorValue = values.getValue(floor)
        if (value < floorValue) {
            listOf(
                TunableProblem(tunable, "must be at least ${floor.key} ($floorValue), got $value"),
                TunableProblem(floor, "must not exceed ${tunable.key} ($value), got $floorValue"),
            )
        } else {
            emptyList()
        }
    }
    listOfNotNull(own) + ordering
}.distinct()

private fun rangeProblem(tunable: Tunable, value: Long): TunableProblem =
    TunableProblem(tunable, if (tunable.minimum == 1L) "must be greater than 0, got $value" else "must be at least ${tunable.minimum}, got $value")

/**
 * Checks what the owner entered for every tunable against the rules of [tunableProblems], on top of the [defaults]. A text that is
 * not a whole number is refused for its tunable alone; the other rules are then checked with that tunable at its default.
 */
fun checkOverrides(defaults: Map<Tunable, Long>, entered: Map<Tunable, String>): OverrideCheck {
    val parsed = entered.mapValues { (_, text) -> text.trim().toLongOrNull() }
    val notNumbers = parsed.filterValues { it == null }.keys.map { TunableProblem(it, "must be a whole number up to ${it.maximum}") }
    val values = defaults + parsed.mapNotNull { (tunable, value) -> value?.let { tunable to it } }
    val problems = (notNumbers + tunableProblems(values)).distinctBy { it.tunable to it.message }
    return if (problems.isEmpty()) OverrideCheck.Valid(
        values.filter { (tunable, value) ->
            value != defaults.getValue(tunable)
        },
    ) else OverrideCheck.Invalid(problems)
}

/** Checks saved [overrides] on top of the [defaults]: the effective values must obey [tunableProblems]. */
fun checkOverrideValues(defaults: Map<Tunable, Long>, overrides: Map<Tunable, Long>): List<TunableProblem> = tunableProblems(defaults + overrides)
