package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.HostRoute
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.TimeSource

/** Creates the live loop of a host over TCP; the configuration is read when a loop is created, so a new loop uses the current one. */
class HostLiveFactory(
    private val client: () -> ClientConfig,
    private val live: () -> LiveConfig,
    private val ioDispatcher: CoroutineDispatcher,
    private val timeSource: TimeSource.WithComparableMarks,
    private val log: AppLog,
) {
    fun create(route: HostRoute): HostLive {
        val config = live()
        return HostLive(route, TcpHostTransport(route, client(), ioDispatcher, config.notificationPageSize), config, timeSource, log)
    }
}
