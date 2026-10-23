package dev.pohunek.companion.newsession

import androidx.lifecycle.viewModelScope
import dev.pohunek.companion.config.AppConfig
import dev.pohunek.companion.core.data.hosts.HostOffers
import dev.pohunek.companion.core.data.hosts.KnownHosts
import dev.pohunek.companion.core.data.layout.LayoutRepository
import dev.pohunek.companion.core.data.layout.LayoutState
import dev.pohunek.companion.core.data.newsession.ChoicesUpdate
import dev.pohunek.companion.core.data.newsession.NewSessions
import dev.pohunek.companion.core.domain.hosts.hostDisplayLabel
import dev.pohunek.companion.core.domain.layout.GroupNameCheck
import dev.pohunek.companion.core.domain.layout.INBOX_ID
import dev.pohunek.companion.core.domain.layout.checkGroupName
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.warn
import dev.pohunek.companion.core.domain.newsession.CreateOutcome
import dev.pohunek.companion.core.domain.newsession.NewSessionForm
import dev.pohunek.companion.core.domain.newsession.sessionNewParams
import dev.pohunek.companion.core.domain.newsession.settleSelection
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
    private val layouts: LayoutRepository,
    private val log: AppLog,
    private val config: () -> AppConfig,
    private val requested: NewSessionDestination,
) : BaseViewModel<NewSessionState, NewSessionEvent, NewSessionEffect>(
    NewSessionState(
        form = NewSessionForm(project = requested.project),
        requestedProject = requested.project,
        attachAfterCreate = config().newSession.attachAfterCreate,
    ),
) {
    private var loadJob: Job? = null

    /** Counts host selections; an answer carries the count it was asked under and is applied only while it is current. */
    private var generation = 0

    private val templates = TemplateReader(viewModelScope, newSessions, { currentState }, ::updateState)

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
            is NewSessionEvent.SelectProject -> {
                edit { it.copy(project = event.id) }
                templates.sync()
            }
            is NewSessionEvent.SelectTemplate -> if (!busy) templates.select(event.name)
            is NewSessionEvent.FormEdit -> edit(event::applyTo)
            NewSessionEvent.ToggleAdvanced -> updateState { it.copy(advancedOpen = !it.advancedOpen) }
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
        templates.hostChanged()
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
        if (token == generation) templates.sync()
    }

    private fun create() {
        val state = currentState
        val route = state.form.host
        val settings = config()
        val cols = settings.newSession.cols
        val rows = settings.newSession.rows
        if (state.blocker != null || route == null || sessionNewParams(state.form, cols, rows) == null) return
        updateState { it.copy(activity = it.activity.copy(inFlight = true), notice = null) }
        viewModelScope.launch {
            val group = groupNameFor(layouts, log, requested.group, settings.display.groupNameMaxBytes)
            val params = checkNotNull(sessionNewParams(state.form, cols, rows, group)) { "the same form passed the guard in create()" }
            finish(route, newSessions.create(route, params))
        }
    }

    private fun finish(route: HostRoute, outcome: CreateOutcome) {
        when (outcome) {
            is CreateOutcome.Created -> created(route, outcome)
            is CreateOutcome.Refused -> settle(CreateNotice.Refused(outcome.message), unconfirmed = false)
            is CreateOutcome.RefusedMaybeCreated -> settle(CreateNotice.RefusedMaybeCreated(outcome.message), unconfirmed = true)
            is CreateOutcome.NotSent -> settle(CreateNotice.NotSent(outcome.message), unconfirmed = false)
            CreateOutcome.Unconfirmed -> settle(CreateNotice.Unconfirmed, unconfirmed = true)
        }
    }

    private fun created(route: HostRoute, outcome: CreateOutcome.Created) {
        val session = outcome.result.session
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

/**
 * The name of the owner group the form was opened from, which the session is created in. Inbox, a group deleted since, and a name
 * that is not a valid `companion.group` value send none, so the session starts unplaced.
 */
private suspend fun groupNameFor(layouts: LayoutRepository, log: AppLog, groupId: String?, maxBytes: Int): String? {
    val id = groupId?.takeIf { it != INBOX_ID } ?: return null
    layouts.load()
    val name = (layouts.state.value as? LayoutState.Available)?.layout?.groups?.firstOrNull { it.id == id }?.name ?: return null
    return when (val check = checkGroupName(name, maxBytes)) {
        is GroupNameCheck.Valid -> check.name
        is GroupNameCheck.Invalid -> {
            log.warn("new_session.group_skipped", listOf(LogField.errorCode(check.problem.code)))
            null
        }
    }
}
