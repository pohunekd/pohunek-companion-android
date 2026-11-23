package dev.pohunek.companion.core.data.demo

import dev.pohunek.companion.core.data.live.EventStream
import dev.pohunek.companion.core.data.live.HostTransport
import dev.pohunek.companion.core.data.live.Seed
import dev.pohunek.companion.protocol.DaemonEvent
import dev.pohunek.companion.protocol.HostRoute
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [HostTransport] of the demo mode: it serves the synthetic showcase sessions of [DemoSessions] in-process and opens no socket. Its
 * event stream never emits and stays open, so the live loop keeps the host Connected.
 */
class DemoHostTransport(private val route: HostRoute) : HostTransport {
    override suspend fun subscribe(): EventStream = object : EventStream {
        override val events: Flow<DaemonEvent> get() = flow { awaitCancellation() }

        override fun close() = Unit
    }

    override suspend fun fetchSeed(): Seed =
        Seed(DemoSessions.forHost(route), emptyList(), attentionIsolationSupported = false)

    override suspend fun probe() = Unit
}
