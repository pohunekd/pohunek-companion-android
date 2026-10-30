package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.core.domain.sessions.HostLink
import kotlin.time.Duration

/**
 * How the host's rows are treated. A host without a connection looks connected ([HostLink.Grace]) until it has been without one for
 * [grace]: a short loss, or the start of a loop that keeps its last list, is not shown. A daemon refusal with a code, and a host that
 * discovery no longer reports, are [HostLink.Down] at once. A host that has not sent a list yet is [HostLink.Connecting].
 */
fun HostFeed?.link(grace: Duration): HostLink {
    val feed = this ?: return HostLink.Connecting
    return when (val connection = feed.connection) {
        HostConnection.Connected -> HostLink.Connected
        HostConnection.NotReported -> HostLink.Down
        is HostConnection.Reconnecting -> if (connection.code == null && feed.inGrace(grace)) HostLink.Grace else HostLink.Down
        HostConnection.Connecting -> if (feed.sessions != null && feed.inGrace(grace)) HostLink.Grace else HostLink.Connecting
    }
}

/** The time left until [link] stops being [HostLink.Grace], or null when it is not in grace. */
fun HostFeed.graceRemaining(grace: Duration): Duration? =
    if (link(grace) == HostLink.Grace) grace - checkNotNull(downSince) { "a feed in grace has a down mark" }.elapsedNow() else null

private fun HostFeed.inGrace(grace: Duration): Boolean = downSince?.let { it.elapsedNow() < grace } ?: false
