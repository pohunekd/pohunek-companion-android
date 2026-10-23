package dev.pohunek.companion.hosts

import androidx.lifecycle.viewModelScope
import dev.pohunek.companion.core.data.hosts.HostEdit
import dev.pohunek.companion.core.data.hosts.HostStoreException
import dev.pohunek.companion.core.data.hosts.HostsRepository
import dev.pohunek.companion.core.data.hosts.HostsState
import dev.pohunek.companion.core.data.live.HostRegistry
import dev.pohunek.companion.core.domain.hosts.ConfiguredHost
import dev.pohunek.companion.core.domain.hosts.hostDisplayLabel
import dev.pohunek.companion.core.mvi.BaseViewModel
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProtocolClientException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Lists, adds, edits and removes the configured hosts; the connection of each comes from the shared [registry]. */
class HostsViewModel(
    private val hosts: HostsRepository,
    registry: HostRegistry,
) : BaseViewModel<HostsScreenState, HostsEvent, HostsEffect>(HostsScreenState()) {
    init {
        viewModelScope.launch { hosts.load() }
        viewModelScope.launch {
            combine(hosts.state, registry.state) { configured, feeds -> configured to feeds }.collect { (configured, feeds) ->
                val items = (configured as? HostsState.Loaded)?.hosts.orEmpty().map { HostItem(it, hostDisplayLabel(it), feeds[it.route]?.connection) }
                updateState {
                    val needsForm = configured is HostsState.Loaded && items.isEmpty()
                    it.copy(
                        hostsRead = configured !is HostsState.Loading,
                        storageError = (configured as? HostsState.Unreadable)?.reason,
                        hosts = items,
                        form = it.form ?: if (needsForm) HostForm() else null,
                    )
                }
            }
        }
    }

    override fun onEvent(event: HostsEvent) {
        when (event) {
            HostsEvent.AddHost -> openForm(HostForm())
            is HostsEvent.EditHost -> currentState.hosts.firstOrNull { it.host.route == event.route }?.let {
                openForm(HostForm(route = it.host.route.toString(), label = it.host.label.orEmpty(), editing = it.host.route))
            }
            HostsEvent.CancelEdit -> if (currentState.hosts.isNotEmpty() && currentState.form?.saving != true) updateState { it.copy(form = null) }
            HostsEvent.SaveHost -> saveHost()
            HostsEvent.RemoveHost -> removeHost()
            is HostsEvent.RouteChanged -> updateForm { it.copy(route = event.route, error = null) }
            is HostsEvent.LabelChanged -> updateForm { it.copy(label = event.label) }
        }
    }

    private fun openForm(form: HostForm) {
        if (currentState.storageError == null) updateState { it.copy(form = form) }
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
        val first = currentState.hosts.isEmpty()
        submit(closeOnSaved = first) { form.editing?.let { hosts.replace(it, host) } ?: hosts.add(host) }
    }

    private fun removeHost() {
        val form = currentState.form ?: return
        val route = form.editing ?: return
        if (form.saving) return
        submit(closeOnSaved = false) { hosts.remove(route) }
    }

    private fun submit(closeOnSaved: Boolean, edit: suspend () -> HostEdit) {
        updateForm { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val failure = try {
                when (val result = edit()) {
                    HostEdit.Saved -> null
                    is HostEdit.Refused -> result.problem.message
                    is HostEdit.Unavailable -> result.reason
                }
            } catch (e: HostStoreException) {
                e.message
            }
            if (failure != null) {
                updateForm { it.copy(saving = false, error = failure) }
            } else {
                // The list this edit left decides what follows: with no host left the form stays open.
                val remaining = (hosts.state.value as? HostsState.Loaded)?.hosts.orEmpty()
                updateState { it.copy(form = if (remaining.isEmpty()) HostForm() else null) }
                if (closeOnSaved) sendEffect(HostsEffect.Close)
            }
        }
    }

    private fun updateForm(transform: (HostForm) -> HostForm) = updateState { state -> state.copy(form = state.form?.let(transform)) }
}
