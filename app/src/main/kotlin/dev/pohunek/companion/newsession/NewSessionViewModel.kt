package dev.pohunek.companion.newsession

import androidx.lifecycle.viewModelScope
import dev.pohunek.companion.config.AppConfig
import dev.pohunek.companion.core.data.hosts.HostOffers
import dev.pohunek.companion.core.data.hosts.KnownHosts
import dev.pohunek.companion.core.data.newsession.ChoicesUpdate
import dev.pohunek.companion.core.data.newsession.NewSessions
import dev.pohunek.companion.core.data.newsession.PendingPlacementBook
import dev.pohunek.companion.core.domain.hosts.hostDisplayLabel
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.warn
import dev.pohunek.companion.core.domain.newsession.CreateOutcome
import dev.pohunek.companion.core.domain.newsession.NewSessionForm
import dev.pohunek.companion.core.domain.newsession.sessionNewParams
import dev.pohunek.companion.core.domain.newsession.settleSelection
import dev.pohunek.companion.core.domain.sessions.SessionKey
import dev.pohunek.companion.core.mvi.BaseViewModel
import dev.pohunek.companion.navigation.NewSessionDestination
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.SessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The form that starts a session on a followed host. Changing the host reads that host's projects and agents anew; an answer that
 * belongs to an earlier host is dropped. Exactly one `session.new` leaves per submit and a form whose outcome is unknown never sends
 * again.
 */
class NewSessionViewModel(
    private val hosts: KnownHosts,
    private val newSessions: NewSessions,
    private val placements: PendingPlacementBook,
    private val log: AppLog,
    private val config: () -> AppConfig,
    private val requested: NewSessionDestination,
) : BaseViewModel<NewSessionState, NewSessionEvent, NewSessionEffect>(
    NewSessionState(form = NewSessionForm(project = requested.project), attachAfterCreate = config().newSession.attachAfterCreate),
) {
    private var loadJob: Job? = null

    /** Counts host selections; an answer carries the count it was asked under and is applied only while it is current. */
    private var generation = 0

    init {
        viewModelScope.launch {
            hosts.load()
            updateState { it.copy(hostsRead = true) }
            hosts.offers.collect(::offered)
        }
    }

    override fun onEvent(event: NewSessionEvent) {
        val busy = currentState.activity.inFlight
        when (event) {
            is NewSessionEvent.SelectHost -> if (!busy && event.route != currentState.form.host) selectHost(event.route)
            NewSessionEvent.Reload -> currentState.form.host?.takeIf { !busy }?.let(::selectHost)
            is NewSessionEvent.SelectProject -> edit { it.copy(project = event.id) }
            is NewSessionEvent.SelectAgent -> edit { it.copy(agent = event.name) }
            is NewSessionEvent.EditName -> edit { it.copy(name = event.text) }
            is NewSessionEvent.EditPrompt -> edit { it.copy(prompt = event.text) }
            is NewSessionEvent.EditProjectQuery -> updateState { it.copy(projectQuery = event.text) }
            is NewSessionEvent.SetAttachAfterCreate -> updateState { it.copy(attachAfterCreate = event.attach) }
            NewSessionEvent.Create -> create()
        }
    }

    /** Edits are ignored while the request runs, so the form that was sent is the one the outcome belongs to. */
    private fun edit(change: (NewSessionForm) -> NewSessionForm) {
        updateState { state -> if (state.activity.inFlight) state else state.copy(form = change(state.form)) }
    }

    private fun offered(offers: HostOffers) {
        val options = offers.followed.map { HostOption(it.route, hostDisplayLabel(it)) }
        updateState { it.copy(hosts = options, incompatibleHosts = offers.incompatible.map { host -> host.label }) }
        if (currentState.form.host == null && options.isNotEmpty()) {
            val preselected = requested.host?.let(::parseRoute)?.takeIf { route -> options.any { it.route == route } }
            selectHost(preselected ?: options.first().route)
        }
    }

    private fun parseRoute(text: String): HostRoute? = try {
        HostRoute.parse(text)
    } catch (_: ProtocolClientException.InvalidRoute) {
        null
    }

    private fun selectHost(route: HostRoute) {
        loadJob?.cancel()
        val token = ++generation
        updateState {
            it.copy(
                form = it.form.copy(host = route),
                projects = null,
                choices = null,
                projectQuery = "",
                activity = it.activity.copy(loading = true, loadError = null),
            )
        }
        loadJob = viewModelScope.launch { newSessions.choices(route).collect { update -> answered(token, update) } }
    }

    private fun answered(token: Int, update: ChoicesUpdate) {
        updateState { state ->
            if (token != generation) return@updateState state
            when (update) {
                is ChoicesUpdate.Projects -> state.copy(
                    projects = update.projects,
                    form = state.form.copy(project = state.form.project?.takeIf { id -> update.projects.any { it.id == id } }),
                )
                is ChoicesUpdate.Loaded -> state.copy(
                    projects = update.choices.projects,
                    choices = update.choices,
                    form = settleSelection(state.form, update.choices),
                    activity = state.activity.copy(loading = false),
                )
                is ChoicesUpdate.Failed -> state.copy(activity = state.activity.copy(loading = false, loadError = update.detail))
            }
        }
    }

    private fun create() {
        val state = currentState
        val route = state.form.host
        val settings = config()
        val params = sessionNewParams(state.form, settings.newSession.cols, settings.newSession.rows)
        if (state.blocker != null || route == null || params == null) return
        updateState { it.copy(activity = it.activity.copy(inFlight = true), notice = null) }
        viewModelScope.launch { finish(route, newSessions.create(route, params)) }
    }

    private fun finish(route: HostRoute, outcome: CreateOutcome) {
        when (outcome) {
            is CreateOutcome.Created -> created(route, outcome)
            is CreateOutcome.Refused -> settle(CreateNotice.Refused(outcome.message), unconfirmed = false)
            is CreateOutcome.NotSent -> settle(CreateNotice.NotSent(outcome.message), unconfirmed = false)
            CreateOutcome.Unconfirmed -> settle(CreateNotice.Unconfirmed, unconfirmed = true)
        }
    }

    private fun created(route: HostRoute, outcome: CreateOutcome.Created) {
        val session = outcome.result.session
        requested.group?.let { placements.record(SessionKey(route.toString(), session.id), it) }
        if (!outcome.promptDelivered) return settle(CreateNotice.PromptUnconfirmed, unconfirmed = true)
        updateState { it.copy(activity = it.activity.copy(inFlight = false)) }
        // A session that is not running yet cannot be attached; the list shows it as it starts.
        val open = currentState.attachAfterCreate && session.state == SessionState.Running
        if (currentState.attachAfterCreate && !open) {
            log.warn("new_session.attach_skipped", listOf(LogField.sessionId(session.id), LogField.state(session.state.name)))
        }
        sendEffect(if (open) NewSessionEffect.OpenTerminal(route, session.id) else NewSessionEffect.Close)
    }

    private fun settle(notice: CreateNotice, unconfirmed: Boolean) {
        updateState { it.copy(activity = it.activity.copy(inFlight = false, unconfirmed = it.activity.unconfirmed || unconfirmed), notice = notice) }
    }
}
