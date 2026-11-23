package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.protocol.HostRoute
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

/** Creates the live loop of a host; the configuration is read when a loop is created, so a new loop uses the current one. */
class HostLiveFactory(
    private val live: () -> LiveConfig,
    private val timeSource: TimeSource.WithComparableMarks,
    private val log: AppLog,
    /** Builds the transport of one route's loop. */
    private val transport: (HostRoute, LiveConfig) -> HostTransport,
) {
    /** The current time on the clock the loops use. */
    fun now(): ComparableTimeMark = timeSource.markNow()

    /** The state a route's loops share; the registry keeps it for as long as the route is followed. */
    fun createState(route: HostRoute): LiveState = LiveState(route, timeSource, live().idleThreshold)

    fun create(route: HostRoute, state: LiveState): HostLive {
        val config = live()
        return HostLive(route, transport(route, config), config, timeSource, log, state)
    }
}
