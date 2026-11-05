package dev.pohunek.companion.core.domain.hosts

/** A followed host whose connection is down; [code] is the daemon's protocol error code when it refused the connection. */
data class HostOutage(val hostLabel: String, val attempt: Int, val reason: String, val code: String?)

/**
 * The host status lines above the session list. A refusal needs the owner to act, so each keeps its own line in [refused];
 * hosts that are only reconnecting share one line: [reconnecting] counted against the [followed] hosts, naming [firstReconnecting].
 */
data class HostStatusSummary(
    val refused: List<HostOutage> = emptyList(),
    val reconnecting: List<HostOutage> = emptyList(),
    val followed: Int = 0,
) {
    /** The reconnecting host named in the line: the first in host order. */
    val firstReconnecting: HostOutage? get() = reconnecting.firstOrNull()

    val isEmpty: Boolean get() = refused.isEmpty() && reconnecting.isEmpty()
}

/** Splits [outages], in host order, into refusals and reconnecting hosts; the reason of each is cut to one line. */
fun summarizeHostStatus(outages: List<HostOutage>, followed: Int): HostStatusSummary {
    val oneLine = outages.map { it.copy(reason = it.reason.replace(LINE_BREAKS, " ").trim()) }
    val (refused, reconnecting) = oneLine.partition { it.code != null }
    return HostStatusSummary(refused, reconnecting, followed)
}

private val LINE_BREAKS = Regex("\\s*[\\r\\n\\u2028\\u2029]+\\s*")
