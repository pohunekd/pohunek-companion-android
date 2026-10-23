package dev.pohunek.companion.core.data.live

import kotlin.time.Duration

/** Tunables of one host's live loop; every value is positive and the reconnect delays are ordered. */
class LiveConfig(
    /** Interval of the periodic re-seed that corrects what the event stream missed. */
    val reseedInterval: Duration,
    /** Wait before the first reconnect; doubles per consecutive failure. */
    val reconnectInitial: Duration,
    /** Longest wait between reconnects; also how long a connection must hold before the failure count resets. */
    val reconnectMax: Duration,
    /** Interval of the `daemon.health` probe on a connection that is otherwise quiet. */
    val livenessInterval: Duration,
    /** Time one probe, connecting included, may take before the connection is declared dead. */
    val livenessTimeout: Duration,
    /** How long a session must stay idle, with no subagent or background work, before it is shown as Idle. */
    val idleThreshold: Duration,
    /** Records requested per `notification.list` page; the page is halved while the daemon reports it as too large. */
    val notificationPageSize: Int,
) {
    init {
        require(reseedInterval.isPositive()) { "reseedInterval must be positive" }
        require(reconnectInitial.isPositive()) { "reconnectInitial must be positive" }
        require(reconnectMax >= reconnectInitial) { "reconnectMax must be at least reconnectInitial" }
        require(livenessInterval.isPositive()) { "livenessInterval must be positive" }
        require(livenessTimeout.isPositive()) { "livenessTimeout must be positive" }
        require(idleThreshold.isPositive()) { "idleThreshold must be positive" }
        require(notificationPageSize >= 1) { "notificationPageSize must be at least 1" }
    }
}
