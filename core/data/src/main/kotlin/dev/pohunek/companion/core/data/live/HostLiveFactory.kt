package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.HostRoute
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.TimeSource

/** Creates the live loop of a host over TCP. */
class HostLiveFactory(
    private val client: ClientConfig,
    private val live: LiveConfig,
    private val ioDispatcher: CoroutineDispatcher,
    private val timeSource: TimeSource.WithComparableMarks,
    private val log: AppLog,
) {
    fun create(route: HostRoute): HostLive = HostLive(route, TcpHostTransport(route, client, ioDispatcher), live, timeSource, log)
}
