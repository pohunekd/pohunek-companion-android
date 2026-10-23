package dev.pohunek.companion.sessions

import androidx.lifecycle.viewModelScope
import dev.pohunek.companion.core.data.hosts.ConfiguredHost
import dev.pohunek.companion.core.data.hosts.HostStore
import dev.pohunek.companion.core.data.hosts.HostStoreException
import dev.pohunek.companion.core.data.hosts.HostsLoad
import dev.pohunek.companion.core.data.sessions.HostSessions
import dev.pohunek.companion.core.mvi.BaseViewModel
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.SessionInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SessionListViewModel(
    private val hosts: HostStore,
    private val sessions: HostSessions,
) : BaseViewModel<SessionListState, SessionListEvent, SessionListEffect>(SessionListState()) {
    private var loadJob: Job? = null

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
                load.host?.let { loadSessions(it.route) }
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
        val host = currentState.host ?: return
        if (currentState.loading) return
        loadSessions(host.route)
    }

    private fun loadSessions(route: HostRoute) {
        loadJob?.cancel()
        updateState { it.copy(loading = true, refusal = null) }
        loadJob = viewModelScope.launch {
            try {
                val rows = sessions.list(route).map(SessionInfo::toRow)
                updateState { it.copy(loading = false, rows = rows, error = null) }
            } catch (e: ProtocolClientException.VersionMismatch) {
                fail(ListError(incompatible = true, message = e.message.orEmpty()))
            } catch (e: ProtocolClientException) {
                fail(ListError(incompatible = false, message = e.message.orEmpty()))
            }
        }
    }

    private fun fail(error: ListError) = updateState { it.copy(loading = false, rows = null, error = error) }

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
            loadJob?.cancel()
            updateState { it.copy(host = host, form = null, rows = null, error = null) }
            loadSessions(route)
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
            loadJob?.cancel()
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
