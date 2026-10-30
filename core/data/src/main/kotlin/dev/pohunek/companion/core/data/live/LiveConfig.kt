package dev.pohunek.companion.core.data.live

import kotlin.time.Duration

/** Tunables of the checks that decide a connection is dead; every value is positive. */
class Liveness(
    /** Interval of the `daemon.health` probe on a connection that is otherwise quiet. */
    val interval: Duration,
    /** Time one probe, connecting included, may take before it counts as failed. */
    val timeout: Duration,
    /** Consecutive failed probes or re-seeds that declare the connection dead. */
    val failures: Int,
    /** Wait before the next probe or re-seed after a failed one. */
    val retry: Duration,
) {
    init {
        require(interval.isPositive()) { "interval must be positive" }
        require(timeout.isPositive()) { "timeout must be positive" }
        require(failures >= 1) { "failures must be at least 1" }
        require(retry.isPositive()) { "retry must be positive" }
    }
}

/** Tunables of one host's live loop; every value is positive and the reconnect delays are ordered. */
class LiveConfig(
    /** Interval of the periodic re-seed that corrects what the event stream missed. */
    val reseedInterval: Duration,
    /** Wait before the first reconnect; doubles per consecutive failure. */
    val reconnectInitial: Duration,
    /** Longest wait between reconnects. */
    val reconnectMax: Duration,
    /** How long a connection that was seeded must hold before the failure count resets, so the next drop starts at [reconnectInitial]. */
    val backoffReset: Duration,
    /** The health probe and the failures that end a connection. */
    val liveness: Liveness,
    /** How long a session must stay idle, with no subagent or background work, before it is shown as Idle. */
    val idleThreshold: Duration,
    /** Records requested per `notification.list` page; the page is halved while the daemon reports it as too large. */
    val notificationPageSize: Int,
) {
    init {
        require(reseedInterval.isPositive()) { "reseedInterval must be positive" }
        require(reconnectInitial.isPositive()) { "reconnectInitial must be positive" }
        require(reconnectMax >= reconnectInitial) { "reconnectMax must be at least reconnectInitial" }
        require(backoffReset.isPositive()) { "backoffReset must be positive" }
        require(idleThreshold.isPositive()) { "idleThreshold must be positive" }
        require(notificationPageSize >= 1) { "notificationPageSize must be at least 1" }
    }
}
