package dev.pohunek.companion.sessions

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dev.pohunek.companion.core.data.hosts.HostsRepository
import dev.pohunek.companion.core.data.hosts.HostsState
import dev.pohunek.companion.core.data.live.HostConnection
import dev.pohunek.companion.core.data.live.HostFeed
import dev.pohunek.companion.core.data.live.HostRegistry
import dev.pohunek.companion.core.domain.hosts.hostDisplayLabel
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.error
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.core.domain.sessions.HostLink
import dev.pohunek.companion.core.domain.sessions.HostSessions
import dev.pohunek.companion.core.domain.sessions.HostedSession
import dev.pohunek.companion.core.domain.sessions.clipDisplay
import dev.pohunek.companion.core.domain.sessions.openableWorkLink
import dev.pohunek.companion.core.domain.sessions.partitionNeedsYou
import dev.pohunek.companion.core.domain.sessions.runtimeDetail
import dev.pohunek.companion.core.domain.sessions.sanitizeText
import dev.pohunek.companion.core.domain.sessions.sessionDisplayName
import dev.pohunek.companion.core.domain.sessions.sessionProjectLabel
import dev.pohunek.companion.core.domain.sessions.workBadge
import dev.pohunek.companion.core.domain.sessions.workFacts
import dev.pohunek.companion.core.mvi.BaseViewModel
import dev.pohunek.companion.protocol.HostRoute
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The union list of every configured host. The live loops run in [registry] while [processLifecycle] is started, that is while the app
 * is visible; each return to the foreground reconnects every host and re-seeds at once.
 */
@Suppress("TooManyFunctions") // One state holder for the events of the list screen; the row and list derivations live below it.
class SessionListViewModel(
    private val hosts: HostsRepository,
    private val registry: HostRegistry,
    private val processLifecycle: Lifecycle,
    private val log: AppLog,
    private val badgeMaxChars: Int,
) : BaseViewModel<SessionListState, SessionListEvent, SessionListEffect>(SessionListState()) {
    private var firstHostRequested = false

    init {
        viewModelScope.launch { hosts.load() }
        viewModelScope.launch {
            combine(hosts.state, registry.state) { configured, feeds -> configured to feeds }.collect { (configured, feeds) ->
                if (configured is HostsState.Loaded && configured.hosts.isEmpty() && !firstHostRequested) {
                    firstHostRequested = true
                    sendEffect(SessionListEffect.OpenHosts)
                }
                updateState { listed(it, configured, feeds, badgeMaxChars) }
            }
        }
        viewModelScope.launch {
            val routes = hosts.state.filterIsInstance<HostsState.Loaded>().map { loaded -> loaded.hosts.map { it.route } }.distinctUntilChanged()
            processLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { registry.run(routes) }
        }
    }

    override fun onEvent(event: SessionListEvent) {
        when (event) {
            SessionListEvent.Refresh -> refresh()
            is SessionListEvent.OpenSession -> openSession(event.ref)
            is SessionListEvent.OpenActions -> updateState { it.copy(actionSheet = event.ref) }
            SessionListEvent.DismissActions -> updateState { it.copy(actionSheet = null) }
            is SessionListEvent.OpenLink -> openLink(event.ref)
            SessionListEvent.LinkNotHandled -> log.error("session.open_link_failed", listOf(LogField.errorCode("no_activity")))
            SessionListEvent.DismissRefusal -> updateState { it.copy(refusal = null) }
        }
    }

    private fun openSession(ref: RowRef) {
        val state = currentState
        val row = (state.needsYou + state.rows.orEmpty()).firstOrNull { it.ref == ref } ?: return
        // A row of a host that is not connected opens nothing; only a host that is down says why.
        val refusal = if (row.reachable) row.attachRefusal else row.attachRefusal.takeIf { row.link == HostLink.Down }
        updateState { it.copy(refusal = refusal, refusalHost = row.route, actionSheet = null) }
        if (row.reachable && refusal == null) sendEffect(SessionListEffect.OpenTerminal(row.route, row.sessionId))
    }

    /** The link is read from the session as it is now and checked again, so a value that changed since the row was drawn is not opened. */
    private fun openLink(ref: RowRef) {
        updateState { it.copy(actionSheet = null) }
        val feed = registry.state.value[ref.route]
        val url = feed?.sessions?.firstOrNull { it.info.id == ref.sessionId }?.let { openableWorkLink(it.info.metadata.orEmpty()) }
        if (url == null || feed.connection !is HostConnection.Connected) {
            sendEffect(SessionListEffect.LinkUnavailable)
            return
        }
        log.info("session.open_link", listOf(LogField.sessionId(ref.sessionId)))
        sendEffect(SessionListEffect.OpenLink(url))
    }

    private fun refresh() {
        if (currentState.refreshing) return
        updateState { it.copy(refusal = null) }
        registry.requestReseed()
    }
}

private fun listed(state: SessionListState, configured: HostsState, feeds: Map<HostRoute, HostFeed>, badgeMaxChars: Int): SessionListState {
    val hostList = (configured as? HostsState.Loaded)?.hosts.orEmpty()
    val entries = hostList.map { host -> HostSessions(host, feeds[host.route].link(), feeds[host.route]?.sessions.orEmpty()) }
    val listing = partitionNeedsYou(entries)
    val statuses = hostList.mapNotNull { host ->
        (feeds[host.route]?.connection as? HostConnection.Reconnecting)?.let { HostStatus(hostDisplayLabel(host), it) }
    }
    val received = hostList.any { feeds[it.route]?.sessions != null }
    // A host that has neither sent a list nor failed is still expected to send one.
    val expecting = hostList.any { feeds[it.route]?.let { feed -> feed.sessions == null && feed.connection !is HostConnection.Reconnecting } ?: true }
    val empty = listing.needsYou.isEmpty() && listing.others.isEmpty()
    // Row actions need the daemon: the sheet closes with the session or with its host's connection.
    val sheetOpen = state.actionSheet?.let { ref ->
        (listing.needsYou + listing.others).any { it.reachable && RowRef(it.route, it.session.info.id) == ref }
    } == true
    return state.copy(
        actionSheet = state.actionSheet.takeIf { sheetOpen },
        hostsRead = configured !is HostsState.Loading,
        storageError = (configured as? HostsState.Unreadable)?.reason,
        hasHosts = hostList.isNotEmpty(),
        needsYou = listing.needsYou.map { it.toRow(badgeMaxChars) },
        rows = if (!received || (empty && expecting)) null else listing.others.map { it.toRow(badgeMaxChars) },
        statuses = statuses,
        refreshing = feeds.values.any { it.refreshing },
        // The unreachable-host reason ends with the outage of the host it explained.
        refusal = state.refusal.takeUnless {
            it == AttachRefusal.HostUnreachable && entries.none { e -> e.host.route == state.refusalHost && e.link == HostLink.Down }
        },
    )
}

private fun HostFeed?.link(): HostLink = when (this?.connection) {
    HostConnection.Connected -> HostLink.Connected
    is HostConnection.Reconnecting -> HostLink.Down
    HostConnection.Connecting, null -> HostLink.Connecting
}

private fun HostedSession.toRow(badgeMaxChars: Int): SessionRow {
    val info = session.info
    val badge = workFacts(info.metadata.orEmpty())?.let(::workBadge)
    return SessionRow(
        route = route,
        sessionId = info.id,
        title = sessionDisplayName(info),
        agent = sanitizeText(info.agent),
        ownerState = session.ownerState,
        project = sessionProjectLabel(info),
        badge = badge?.let { clipDisplay(it.link, badgeMaxChars) },
        badgeRole = badge?.role?.let { clipDisplay(it, badgeMaxChars) },
        hasLink = reachable && openableWorkLink(info.metadata.orEmpty()) != null,
        runtimeDetail = runtimeDetail(info),
        hostLabel = hostLabel,
        link = link,
        attachRefusal = if (link == HostLink.Down) AttachRefusal.HostUnreachable else AttachRefusal.forSession(info.external, info.state),
    )
}
