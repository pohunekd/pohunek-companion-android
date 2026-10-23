package dev.pohunek.companion.sessions

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dev.pohunek.companion.core.data.hosts.ConfiguredHost
import dev.pohunek.companion.core.data.hosts.HostStore
import dev.pohunek.companion.core.data.hosts.HostStoreException
import dev.pohunek.companion.core.data.hosts.HostsLoad
import dev.pohunek.companion.core.data.live.HostLive
import dev.pohunek.companion.core.data.live.HostLiveFactory
import dev.pohunek.companion.core.data.live.LiveUpdate
import dev.pohunek.companion.core.mvi.BaseViewModel
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.SessionInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The live loop of the saved host runs while [processLifecycle] is started, that is while the app is visible. Each return to the
 * foreground reconnects and re-seeds at once.
 */
class SessionListViewModel(
    private val hosts: HostStore,
    private val liveFactory: HostLiveFactory,
    private val processLifecycle: Lifecycle,
) : BaseViewModel<SessionListState, SessionListEvent, SessionListEffect>(SessionListState()) {
    private var live: HostLive? = null
    private var liveJob: Job? = null

    init {
        viewModelScope.launch { applyLoaded(hosts.load()) }
    }

    override fun onEvent(event: SessionListEvent) {
        when (event) {
            SessionListEvent.Refresh -> refresh()
            is SessionListEvent.OpenSession -> openSession(event.id)
            SessionListEvent.DismissRefusal -> updateState { it.copy(refusal = null) }
            SessionListEvent.EditHost -> openForm()
            SessionListEvent.CancelEdit -> updateState { it.copy(form = null) }
            SessionListEvent.ClearHost -> clearHost()
            SessionListEvent.SaveHost -> saveHost()
            is SessionListEvent.RouteChanged -> updateForm { it.copy(route = event.route, error = null) }
            is SessionListEvent.LabelChanged -> updateForm { it.copy(label = event.label) }
        }
    }

    private fun applyLoaded(load: HostsLoad) {
        when (load) {
            is HostsLoad.Unreadable -> updateState { it.copy(hostsRead = true, storageError = load.reason) }
            is HostsLoad.Loaded -> {
                updateState { it.copy(hostsRead = true, host = load.host, form = if (load.host == null) HostForm() else null) }
                load.host?.let { startLive(it.route) }
            }
        }
    }

    private fun openSession(id: String) {
        val state = currentState
        val host = state.host ?: return
        val row = state.rows?.firstOrNull { it.id == id } ?: return
        val refusal = row.attachRefusal
        updateState { it.copy(refusal = refusal) }
        if (refusal == null) sendEffect(SessionListEffect.OpenTerminal(host.route, row.id))
    }

    private fun refresh() {
        val live = live ?: return
        if (currentState.refreshing) return
        updateState { it.copy(refreshing = true, refusal = null) }
        live.requestReseed()
    }

    private fun startLive(route: HostRoute) {
        stopLive()
        val session = liveFactory.create(route)
        live = session
        liveJob = viewModelScope.launch {
            processLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateState { it.copy(connection = ListConnection.Connecting, refreshing = false) }
                session.updates().collect { update -> updateState { it.apply(update) } }
            }
        }
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        live = null
    }

    private fun openForm() {
        val state = currentState
        if (state.storageError != null) return
        val host = state.host
        updateState { it.copy(form = HostForm(route = host?.route?.toString().orEmpty(), label = host?.label.orEmpty())) }
    }

    private fun saveHost() {
        val form = currentState.form ?: return
        if (form.saving) return
        val route = try {
            HostRoute.parse(form.route.trim())
        } catch (e: ProtocolClientException.InvalidRoute) {
            updateForm { it.copy(error = e.message) }
            return
        }
        val host = ConfiguredHost(route, form.label.trim().ifEmpty { null })
        updateForm { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                hosts.save(host)
            } catch (e: HostStoreException) {
                updateForm { it.copy(saving = false, error = e.message) }
                return@launch
            }
            stopLive()
            updateState { it.copy(host = host, form = null, rows = null, connection = ListConnection.Connecting, refreshing = false) }
            startLive(route)
        }
    }

    private fun clearHost() {
        val form = currentState.form ?: return
        if (form.saving) return
        updateForm { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                hosts.save(null)
            } catch (e: HostStoreException) {
                updateForm { it.copy(saving = false, error = e.message) }
                return@launch
            }
            stopLive()
            updateState { SessionListState(hostsRead = true, form = HostForm()) }
        }
    }

    private fun updateForm(transform: (HostForm) -> HostForm) = updateState { state -> state.copy(form = state.form?.let(transform)) }
}

private fun SessionInfo.toRow() = SessionRow(
    id = id,
    title = name?.trim()?.ifEmpty { null } ?: id,
    agent = agent,
    state = state,
    activity = activity,
    attachRefusal = AttachRefusal.forSession(external, state),
)

private fun SessionListState.apply(update: LiveUpdate): SessionListState = when (update) {
    LiveUpdate.Connected -> copy(connection = ListConnection.Connected, refreshing = false)
    is LiveUpdate.Sessions -> copy(rows = update.sessions.map { it.info.toRow() })
    LiveUpdate.Reseeded -> copy(refreshing = false)
    is LiveUpdate.Reconnecting -> copy(connection = ListConnection.Reconnecting(update.attempt, update.reason, update.code), refreshing = false)
}
