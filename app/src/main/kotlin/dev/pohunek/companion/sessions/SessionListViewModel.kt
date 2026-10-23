@file:Suppress("TooManyFunctions") // The state holder is followed by the pure derivations of the rows, the details and the filters.

package dev.pohunek.companion.sessions

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dev.pohunek.companion.config.DisplayLimits
import dev.pohunek.companion.core.data.actions.SessionActionRunner
import dev.pohunek.companion.core.data.commands.CommandResult
import dev.pohunek.companion.core.data.commands.ResumeResult
import dev.pohunek.companion.core.data.commands.SessionCommands
import dev.pohunek.companion.core.data.hosts.HostsState
import dev.pohunek.companion.core.data.hosts.KnownHosts
import dev.pohunek.companion.core.data.layout.LayoutRepository
import dev.pohunek.companion.core.data.layout.LayoutState
import dev.pohunek.companion.core.data.live.HostConnection
import dev.pohunek.companion.core.data.live.HostFeed
import dev.pohunek.companion.core.data.live.HostRegistry
import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.domain.hosts.ConfiguredHost
import dev.pohunek.companion.core.domain.hosts.hostDisplayLabel
import dev.pohunek.companion.core.domain.layout.Edit
import dev.pohunek.companion.core.domain.layout.GROUP_METADATA_KEY
import dev.pohunek.companion.core.domain.layout.GroupError
import dev.pohunek.companion.core.domain.layout.GroupFact
import dev.pohunek.companion.core.domain.layout.GroupMember
import dev.pohunek.companion.core.domain.layout.GroupNameCheck
import dev.pohunek.companion.core.domain.layout.GroupValue
import dev.pohunek.companion.core.domain.layout.HostEntry
import dev.pohunek.companion.core.domain.layout.HostReach
import dev.pohunek.companion.core.domain.layout.INBOX_ID
import dev.pohunek.companion.core.domain.layout.INBOX_NAME
import dev.pohunek.companion.core.domain.layout.LayoutView
import dev.pohunek.companion.core.domain.layout.ProjectSelection
import dev.pohunek.companion.core.domain.layout.SessionFilter
import dev.pohunek.companion.core.domain.layout.StateSelection
import dev.pohunek.companion.core.domain.layout.WorkItem
import dev.pohunek.companion.core.domain.layout.checkGroupName
import dev.pohunek.companion.core.domain.layout.groupFact
import dev.pohunek.companion.core.domain.layout.hostOptions
import dev.pohunek.companion.core.domain.layout.projectChips
import dev.pohunek.companion.core.domain.layout.stateChips
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.error
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.core.domain.log.warn
import dev.pohunek.companion.core.domain.sessions.HostLink
import dev.pohunek.companion.core.domain.sessions.HostSessions
import dev.pohunek.companion.core.domain.sessions.HostedSession
import dev.pohunek.companion.core.domain.sessions.OwnerSession
import dev.pohunek.companion.core.domain.sessions.ResumeOffer
import dev.pohunek.companion.core.domain.sessions.SessionAction
import dev.pohunek.companion.core.domain.sessions.SessionActionOutcome
import dev.pohunek.companion.core.domain.sessions.SessionKey
import dev.pohunek.companion.core.domain.sessions.UNCONFIRMED_PROCESSES_CODE
import dev.pohunek.companion.core.domain.sessions.actionFailureText
import dev.pohunek.companion.core.domain.sessions.actionSubject
import dev.pohunek.companion.core.domain.sessions.clipDisplay
import dev.pohunek.companion.core.domain.sessions.hasConflictingRuntime
import dev.pohunek.companion.core.domain.sessions.offersRemove
import dev.pohunek.companion.core.domain.sessions.offersStop
import dev.pohunek.companion.core.domain.sessions.openableWorkLink
import dev.pohunek.companion.core.domain.sessions.partitionNeedsYou
import dev.pohunek.companion.core.domain.sessions.removalNotice
import dev.pohunek.companion.core.domain.sessions.resumeOffer
import dev.pohunek.companion.core.domain.sessions.runtimeDetail
import dev.pohunek.companion.core.domain.sessions.sanitizeText
import dev.pohunek.companion.core.domain.sessions.sessionDetails
import dev.pohunek.companion.core.domain.sessions.sessionDisplayName
import dev.pohunek.companion.core.domain.sessions.sessionProjectLabel
import dev.pohunek.companion.core.domain.sessions.workBadge
import dev.pohunek.companion.core.domain.sessions.workFacts
import dev.pohunek.companion.core.mvi.BaseViewModel
import dev.pohunek.companion.protocol.HostRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The union list of every known host, configured or discovered. The live loops run in [registry] while [processLifecycle] is started,
 * that is while the app is visible; each return to the foreground reconnects every host and re-seeds at once.
 */
@Suppress("TooManyFunctions") // One state holder for the events of the list screen; the row and list derivations live below it.
class SessionListViewModel(
    private val hosts: KnownHosts,
    private val layouts: LayoutRepository,
    private val registry: HostRegistry,
    private val commands: SessionCommands,
    private val actions: SessionActionRunner,
    private val processLifecycle: Lifecycle,
    private val log: AppLog,
    limits: Flow<DisplayLimits>,
) : BaseViewModel<SessionListState, SessionListEvent, SessionListEffect>(SessionListState()) {
    private var firstHostRequested = false

    /** What each session of a host said, as of the last list queued for it, so that only a list that changed is sent again. */
    private val reconciled = HashMap<HostRoute, List<GroupFact>>()

    /** True once every configured host has sent a list; learned groups are dropped only then. */
    private var allHostsListed = false

    private val editor = GroupEditor(layouts, GroupWrites(commands, log), registry, log) { note -> updateState { it.copy(groupNote = note) } }

    /** Hosts whose migration was started for the current connection and list; a changed list or a new connection arms it again. */
    private val migrationArmed = HashSet<HostRoute>()

    /** Hosts whose armed seed arrived while their migration ran. */
    private val migrationRearm = HashSet<HostRoute>()

    /** Hosts with a migration running, so that two never write the same sessions. */
    private val migrating = HashSet<HostRoute>()

    /** Lists to reconcile in the order the hosts sent them; one consumer applies them, so an older list never lands after a newer one. */
    private val seeds = Channel<Seed>(Channel.UNLIMITED)

    private sealed interface Seed {
        class HostList(val route: HostRoute, val facts: List<GroupFact>) : Seed

        /** Every host has now listed, which may leave learned groups without a member. */
        data object Sweep : Seed

        /** [route] is connected with [facts] as its list and has not been migrated; its manual placements are written once the list is applied. */
        class Migrate(val route: HostRoute, val facts: List<GroupFact>) : Seed
    }

    private data class ListInputs(
        val configured: HostsState,
        val feeds: Map<HostRoute, HostFeed>,
        val layout: LayoutState,
        val limits: DisplayLimits,
        val notShared: Set<SessionKey>,
    )

    init {
        viewModelScope.launch { hosts.load() }
        viewModelScope.launch {
            layouts.load()
            for (seed in seeds) applySeed(seed)
        }
        viewModelScope.launch {
            combine(hosts.state, registry.state, layouts.state, limits, layouts.notShared, ::ListInputs).collect { input ->
                val (configured, feeds, layout) = input
                if (configured is HostsState.Loaded && configured.hosts.isEmpty() && !firstHostRequested) {
                    firstHostRequested = true
                    sendEffect(SessionListEffect.OpenHosts)
                }
                editor.configure(input.limits.groupNameMaxBytes, (configured as? HostsState.Loaded)?.hosts.orEmpty())
                queueSeeds(configured, feeds, layout, input.limits.groupNameMaxBytes)
                updateState { listed(it, configured, feeds, layout, input.notShared, input.limits.rowBadgeMaxChars, input.limits.sectionTitleMaxChars) }
                pruneCollapsed(configured, feeds, input.limits.sectionTitleMaxChars)
            }
        }
        viewModelScope.launch { processLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { hosts.run() } }
    }

    private suspend fun applySeed(seed: Seed) {
        when (seed) {
            is Seed.HostList -> {
                layouts.reconcile(seed.route, seed.facts.map { it.key.sessionId })
                layouts.applyGroupValues(seed.route, seed.facts, allHostsListed)
            }
            Seed.Sweep -> layouts.applyGroupValues(null, emptyList(), allHostsListed)
            is Seed.Migrate -> startMigration(seed.route, seed.facts)
        }
    }

    /** Runs beside the seed consumer, so that slow writes never hold back the lists that follow. */
    private fun startMigration(route: HostRoute, facts: List<GroupFact>) {
        if (!migrating.add(route)) {
            // The list that armed this seed is newer than the running migration's; arming again once it ends lets that list retry a failure.
            migrationRearm.add(route)
            return
        }
        viewModelScope.launch {
            try {
                editor.migrate(route, facts)
            } finally {
                migrating.remove(route)
                if (migrationRearm.remove(route)) migrationArmed.remove(route)
            }
        }
    }

    override fun onEvent(event: SessionListEvent) {
        when (event) {
            SessionListEvent.Refresh -> refresh()
            is SessionListEvent.OpenSession -> openSession(event.ref)
            is SessionListEvent.OpenActions -> updateState { it.copy(actionSheet = event.ref) }
            is SessionListEvent.OpenDetails -> openDetails(event.ref)
            // At most one of the sheet and the details is open, so both events close whichever is.
            SessionListEvent.DismissActions, SessionListEvent.CloseDetails -> updateState { it.copy(actionSheet = null, details = null) }
            is SessionListEvent.RenameEvent -> onRenameEvent(event)
            is SessionListEvent.ResumeEvent -> onResumeEvent(event)
            is SessionListEvent.LeaveEvent -> onLeaveEvent(event)
            is SessionListEvent.ActionEvent -> onActionEvent(event)
            SessionListEvent.LinkNotHandled -> log.error("session.open_link_failed", listOf(LogField.errorCode("no_activity")))
            SessionListEvent.DismissRefusal -> updateState { it.copy(refusal = null) }
            is SessionListEvent.SearchEvent -> onSearchEvent(event)
            is SessionListEvent.LayoutEvent -> onLayoutEvent(event)
        }
    }

    private fun onLeaveEvent(event: SessionListEvent.LeaveEvent) {
        when (event) {
            is SessionListEvent.OpenLink -> openLink(event.ref)
            is SessionListEvent.NewSessionHere -> newSessionHere(event.ref)
        }
    }

    private fun onSearchEvent(event: SessionListEvent.SearchEvent) {
        when (event) {
            SessionListEvent.OpenSearch -> updateState { it.copy(search = it.search ?: "") }
            is SessionListEvent.EditSearch -> updateState { it.copy(search = event.text).filtered() }
            SessionListEvent.CloseSearch -> updateState { it.copy(search = null).filtered() }
        }
    }

    private fun onActionEvent(event: SessionListEvent.ActionEvent) {
        when (event) {
            is SessionListEvent.RequestAction -> requestAction(event.ref, event.action)
            SessionListEvent.ConfirmAction -> confirmAction()
            SessionListEvent.DismissAction -> dismissAction()
        }
    }

    private fun onLayoutEvent(event: SessionListEvent.LayoutEvent) {
        when (event) {
            is SessionListEvent.SetSectionCollapsed -> viewModelScope.launch { layouts.setCollapsed(event.id, event.collapsed) }
            is SessionListEvent.ToggleState -> viewModelScope.launch { layouts.toggleState(event.state) }
            is SessionListEvent.TapProject -> viewModelScope.launch { layouts.tapProject(event.chip) }
            is SessionListEvent.TapHost -> viewModelScope.launch { layouts.tapHost(event.key) }
            is SessionListEvent.SelectView -> viewModelScope.launch { layouts.selectView(event.view) }
            is SessionListEvent.GroupEvent -> onGroupEvent(event)
            is SessionListEvent.OpenMoveToGroup -> updateState { it.copy(actionSheet = null, movePicker = event.ref) }
            SessionListEvent.DismissMoveToGroup -> updateState { it.copy(movePicker = null) }
            is SessionListEvent.MoveToGroup -> moveToGroup(event.ref, event.groupId)
        }
    }

    private fun onGroupEvent(event: SessionListEvent.GroupEvent) {
        when (event) {
            is SessionListEvent.OpenGroupMenu -> updateState { it.copy(groupMenu = event.id) }
            SessionListEvent.DismissGroupMenu -> updateState { it.copy(groupMenu = null) }
            SessionListEvent.OpenNewGroup -> openGroupDraft(GroupDraftPurpose.New, "")
            is SessionListEvent.OpenRenameGroup -> currentState.groups.firstOrNull { it.id == event.groupId }
                ?.let { openGroupDraft(GroupDraftPurpose.Rename(it.id), it.name) }
            is SessionListEvent.OpenNewGroupFor -> openGroupDraft(GroupDraftPurpose.NewAndMove(event.ref), "")
            is SessionListEvent.EditGroupName -> updateState { state ->
                state.copy(groupDraft = state.groupDraft?.let { if (it.busy) it else it.copy(text = event.text, error = null) })
            }
            SessionListEvent.SubmitGroupName -> submitGroupName()
            SessionListEvent.DismissGroupName -> updateState { state -> state.copy(groupDraft = state.groupDraft?.takeIf { it.busy }) }
            SessionListEvent.DismissGroupNote -> updateState { it.copy(groupNote = null) }
            is SessionListEvent.DeleteGroup -> deleteGroup(event.id)
        }
    }

    private fun openGroupDraft(purpose: GroupDraftPurpose, text: String) {
        updateState { it.copy(groupMenu = null, actionSheet = null, movePicker = null, groupDraft = GroupDraft(purpose, text)) }
    }

    /** The dialog stays open on a refusal, with the reason in place; it cannot be dismissed while the edit runs. */
    private fun submitGroupName() {
        val draft = currentState.groupDraft?.takeUnless { it.busy } ?: return
        updateState { it.copy(groupDraft = draft.copy(busy = true, error = null)) }
        viewModelScope.launch {
            val error = submitDraft(draft)
            updateState { state ->
                state.copy(groupDraft = if (error == null) null else state.groupDraft?.copy(busy = false, error = error))
            }
        }
    }

    /** The refusal to show in the dialog, or null when the edit was applied. */
    private suspend fun submitDraft(draft: GroupDraft): GroupNameError? = when (val purpose = draft.purpose) {
        GroupDraftPurpose.New -> editor.createGroup(draft.text)
        is GroupDraftPurpose.Rename -> editor.rename(purpose.groupId, draft.text)
        is GroupDraftPurpose.NewAndMove -> editor.createAndPlace(purpose.ref, draft.text)
    }

    private fun deleteGroup(id: String) {
        updateState { it.copy(groupMenu = null) }
        viewModelScope.launch {
            if (!editor.delete(id)) sendEffect(SessionListEffect.LayoutEditRefused)
        }
    }

    private fun moveToGroup(ref: RowRef, groupId: String) {
        updateState { it.copy(movePicker = null) }
        viewModelScope.launch {
            if (!editor.place(ref, groupId)) sendEffect(SessionListEffect.LayoutEditRefused)
        }
    }

    /**
     * Drops the collapsed state of derived-view sections that no session has any more. It waits until every configured host has sent a
     * list, so a host that has not been heard from yet cannot make its sections look stale.
     */
    private fun pruneCollapsed(configured: HostsState, feeds: Map<HostRoute, HostFeed>, titleMaxChars: Int) {
        val hostList = (configured as? HostsState.Loaded)?.hosts.orEmpty()
        val rows = currentState.rows ?: return
        if (hostList.any { feeds[it.route]?.sessions == null }) return
        viewModelScope.launch { layouts.pruneCollapsedSections(rows, SessionRow::layoutKey, titleMaxChars, SessionRow::facts) }
    }

    /**
     * Queues the list of each connected host that differs from the one last queued for it, in ids, group values or the external flag.
     * A host that is connecting or down keeps its last list, which must not prune anything, so it is skipped; a list that arrives
     * while the host is connected is the seed.
     */
    private fun queueSeeds(configured: HostsState, feeds: Map<HostRoute, HostFeed>, layout: LayoutState, groupNameMaxBytes: Int) {
        reconciled.keys.retainAll(feeds.keys)
        migrationArmed.retainAll(feeds.keys)
        val hostList = (configured as? HostsState.Loaded)?.hosts
        val listed = hostList != null && hostList.none { feeds[it.route]?.sessions == null }
        val sweep = listed && !allHostsListed
        allHostsListed = listed
        var queued = false
        for ((route, feed) in feeds) {
            val sessions = feed.takeIf { it.connection == HostConnection.Connected }?.sessions
            if (sessions == null) migrationArmed.remove(route) else queued = queueList(route, sessions, groupNameMaxBytes) || queued
        }
        if (sweep && !queued) seeds.trySend(Seed.Sweep)
        armMigrations(layout, feeds)
    }

    /** Queues the list of [route] when it differs from the one last queued, which also arms its migration again. The value is whether it was queued. */
    private fun queueList(route: HostRoute, sessions: List<OwnerSession>, groupNameMaxBytes: Int): Boolean {
        val facts = sessions.map { it.info.groupFact(route.toString(), groupNameMaxBytes) }
        val previous = reconciled.put(route, facts)
        if (previous == facts) return false
        logRejectedGroupValues(route, facts, previous.orEmpty().associateBy { it.key })
        seeds.trySend(Seed.HostList(route, facts))
        migrationArmed.remove(route)
        return true
    }

    /**
     * Queues the migration of each connected host that is not migrated yet, once per connection and list. It is queued behind the list
     * it was armed with, so the placements it reads are the ones that list left.
     */
    private fun armMigrations(layout: LayoutState, feeds: Map<HostRoute, HostFeed>) {
        val migrated = (layout as? LayoutState.Available)?.layout?.migratedHosts ?: return
        for ((route, facts) in reconciled) {
            val connected = feeds[route]?.let { it.connection == HostConnection.Connected && it.sessions != null } == true
            if (connected && route.toString() !in migrated && migrationArmed.add(route)) seeds.trySend(Seed.Migrate(route, facts))
        }
    }

    /** One warn line per session whose unusable group value is new; the value itself is never logged. */
    private fun logRejectedGroupValues(route: HostRoute, facts: List<GroupFact>, previous: Map<SessionKey, GroupFact>) {
        facts.filter { it.value is GroupValue.Rejected && previous[it.key]?.value != it.value }.forEach { fact ->
            val problem = (fact.value as GroupValue.Rejected).problem
            log.warn(
                "layout.group_value_ignored",
                listOf(hostKeyField(route), LogField.sessionId(fact.key.sessionId), LogField.errorCode(problem.code)),
            )
        }
    }

    private fun onResumeEvent(event: SessionListEvent.ResumeEvent) {
        when (event) {
            is SessionListEvent.Resume -> currentRow(event.ref)?.let(::resume)
            SessionListEvent.DismissResumeNote -> updateState { it.copy(resumeNote = null) }
        }
    }

    private fun currentRow(ref: RowRef): SessionRow? = (currentState.needsYou + currentState.rows.orEmpty()).firstOrNull { it.ref == ref }

    private fun openSession(ref: RowRef) {
        val row = currentRow(ref) ?: return
        // An ended session is resumed rather than attached; one the daemon cannot resume says why.
        if (row.reachable) {
            when (val offer = row.resume) {
                ResumeOffer.Available -> return resume(row)
                is ResumeOffer.Refused -> return updateState { it.copy(resumeNote = ResumeNote.Refused(offer.sentence), refusal = null, actionSheet = null) }
                ResumeOffer.NotApplicable -> Unit
            }
        }
        // A row of a host that is not connected opens nothing; only a host that is down says why.
        val refusal = if (row.reachable) row.attachRefusal else row.attachRefusal.takeIf { row.link == HostLink.Down }
        updateState { it.copy(refusal = refusal, refusalHost = row.route, actionSheet = null) }
        if (row.reachable && refusal == null) sendEffect(SessionListEffect.OpenTerminal(row.route, row.sessionId))
    }

    /**
     * No optimistic update: the row changes when the daemon's session event arrives. The terminal opens when the reply says the new
     * generation runs; otherwise the owner is told to attach once it does.
     */
    private fun resume(row: SessionRow) {
        if (!row.reachable || row.resume != ResumeOffer.Available || row.ref in currentState.resuming) return
        updateState { it.copy(resuming = it.resuming + row.ref, resumeNote = null, refusal = null, actionSheet = null) }
        val hostLabel = if (currentState.multipleHosts) row.hostLabel else null
        viewModelScope.launch {
            val result = commands.resume(row.route, row.sessionId)
            val note = when (result) {
                is ResumeResult.Failed -> ResumeNote.Failed(row.sessionId, hostLabel, result.detail)
                is ResumeResult.Resumed -> ResumeNote.Started(row.title).takeUnless { result.running }
            }
            updateState { it.copy(resuming = it.resuming - row.ref, resumeNote = note) }
            if (result is ResumeResult.Resumed && result.running) sendEffect(SessionListEffect.OpenTerminal(row.route, row.sessionId))
        }
    }

    private fun openDetails(ref: RowRef) {
        updateState { it.copy(actionSheet = null) }
        val session = registry.state.value[ref.route]?.sessions?.firstOrNull { it.info.id == ref.sessionId } ?: return
        val row = (currentState.needsYou + currentState.rows.orEmpty()).firstOrNull { it.ref == ref } ?: return
        log.info("session.open_details", listOf(LogField.sessionId(ref.sessionId)))
        updateState { it.copy(details = DetailsView(ref, row.title, sessionDetails(session, row.hostLabel, ref.route, row.notShared))) }
    }

    /** The form opens on the row's host and project; the new session lands in the owner group the layout holds the row in, whatever view is shown. */
    private fun newSessionHere(ref: RowRef) {
        updateState { it.copy(actionSheet = null) }
        val group = (layouts.state.value as? LayoutState.Available)?.layout?.items?.firstOrNull { it.key == ref.layoutKey }?.group
        val project = registry.state.value[ref.route]?.sessions?.firstOrNull { it.info.id == ref.sessionId }?.info?.projectId
        sendEffect(SessionListEffect.OpenNewSession(ref.route.toString(), project, group))
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

    private fun onRenameEvent(event: SessionListEvent.RenameEvent) {
        when (event) {
            is SessionListEvent.OpenRename -> openRename(event.ref)
            is SessionListEvent.EditRename -> updateState { state ->
                state.copy(rename = state.rename?.let { if (it.busy) it else it.copy(text = event.text, error = null) })
            }
            SessionListEvent.SubmitRename -> submitRename()
            SessionListEvent.DismissRename -> updateState { state -> state.copy(rename = state.rename?.takeIf { it.busy }) }
        }
    }

    /** The dialog starts from the name as the daemon holds it now, empty for a session that shows its id. */
    private fun openRename(ref: RowRef) {
        val state = currentState
        val row = (state.needsYou + state.rows.orEmpty()).firstOrNull { it.ref == ref }
        val name = registry.state.value[ref.route]?.sessions?.firstOrNull { it.info.id == ref.sessionId }?.info?.name
        if (row == null || !row.canRename) {
            updateState { it.copy(actionSheet = null) }
            return
        }
        val prefill = name?.let(::sanitizeText).orEmpty()
        updateState { it.copy(actionSheet = null, rename = RenameDraft(ref, prefill, if (state.multipleHosts) row.hostLabel else null)) }
    }

    /**
     * The row changes when the daemon's session event arrives; a blank text clears the name. The dialog cannot be dismissed while the
     * request runs, so the reply always belongs to the dialog that sent it.
     */
    private fun submitRename() {
        val draft = currentState.rename?.takeUnless { it.busy } ?: return
        updateState { it.copy(rename = draft.copy(busy = true, error = null)) }
        viewModelScope.launch {
            val result = commands.rename(draft.ref.route, draft.ref.sessionId, draft.text.trim().ifEmpty { null })
            updateState { state ->
                val open = state.rename?.takeIf { it.ref == draft.ref }
                when {
                    open == null -> state
                    result is CommandResult.Failed -> state.copy(rename = open.copy(busy = false, error = result.detail))
                    else -> state.copy(rename = null)
                }
            }
        }
    }

    /** Opens the confirmation from the session as the daemon last reported it; nothing is sent before the owner confirms. */
    private fun requestAction(ref: RowRef, action: SessionAction) {
        updateState { it.copy(actionSheet = null) }
        if (currentState.actionDialog != null) return
        val dialog = actionDialogFor(ref, action) ?: return
        log.info("session.action_requested", listOf(hostKeyField(ref.route), LogField.sessionId(ref.sessionId), LogField.method(action.method)))
        updateState { it.copy(actionDialog = dialog) }
    }

    /** The dialog for [action] on [ref], or null when the host is not connected, the session or its row is gone, or the action is not offered. */
    private fun actionDialogFor(ref: RowRef, action: SessionAction): ActionDialog? {
        val feed = registry.state.value[ref.route]?.takeIf { it.connection is HostConnection.Connected }
        val info = feed?.sessions?.firstOrNull { it.info.id == ref.sessionId }?.info
        val row = (currentState.needsYou + currentState.rows.orEmpty()).firstOrNull { it.ref == ref }
        val offered = when (action) {
            SessionAction.Stop -> info != null && offersStop(info)
            SessionAction.Remove -> info != null && offersRemove(info)
            SessionAction.RemoveAnyway -> false
        }
        return if (info != null && row != null && offered) ActionDialog(ref, row.title, row.hostLabel, action, hasConflictingRuntime(info)) else null
    }

    private fun confirmAction() {
        val dialog = currentState.actionDialog?.takeIf { !it.busy && it.notice == null } ?: return
        updateState { it.copy(actionDialog = dialog.copy(busy = true, error = null)) }
        viewModelScope.launch {
            val outcome = actions.run(dialog.ref.route, dialog.ref.sessionId, dialog.action)
            updateState { it.copy(actionDialog = afterAction(dialog, outcome)) }
        }
    }

    private fun dismissAction() {
        val dialog = currentState.actionDialog?.takeIf { !it.busy } ?: return
        val fields = listOf(hostKeyField(dialog.ref.route), LogField.sessionId(dialog.ref.sessionId), LogField.method(dialog.action.method))
        log.info("session.action_dismissed", fields)
        updateState { it.copy(actionDialog = null) }
    }

    private fun refresh() {
        if (currentState.refreshing) return
        updateState { it.copy(refusal = null) }
        registry.requestReseed()
    }
}

/**
 * The dialog once the call ended: closed on success unless the removal left something to report, turned into the consent step when the
 * daemon could not confirm the runtime's processes ended, and otherwise showing the failure in place.
 */
internal fun afterAction(dialog: ActionDialog, outcome: SessionActionOutcome): ActionDialog? {
    val subject = actionSubject(dialog.ref.sessionId, dialog.hostLabel)
    if (outcome is SessionActionOutcome.Done) return removalNotice(subject, outcome)?.let { dialog.copy(busy = false, error = null, notice = it) }
    val failure = actionFailureText(dialog.action, subject, outcome)
    val needsConsent = dialog.action == SessionAction.Remove && outcome is SessionActionOutcome.Refused && outcome.code == UNCONFIRMED_PROCESSES_CODE
    return dialog.copy(busy = false, error = failure, action = if (needsConsent) SessionAction.RemoveAnyway else dialog.action)
}

private fun listed(
    state: SessionListState,
    configured: HostsState,
    feeds: Map<HostRoute, HostFeed>,
    layout: LayoutState,
    notShared: Set<SessionKey>,
    badgeMaxChars: Int,
    sectionTitleMaxChars: Int,
): SessionListState {
    val hostList = (configured as? HostsState.Loaded)?.hosts.orEmpty()
    val entries = hostList.map { host -> HostSessions(host, feeds[host.route].link(), feeds[host.route]?.sessions.orEmpty()) }
    val listing = partitionNeedsYou(entries)
    val statuses = hostList.mapNotNull { host ->
        (feeds[host.route]?.connection as? HostConnection.Reconnecting)?.let { HostStatus(hostDisplayLabel(host), it) }
    }
    val awaiting = awaitsList(hostList, feeds, empty = listing.needsYou.isEmpty() && listing.others.isEmpty())
    val others = listing.others.map { it.toRow(badgeMaxChars, notShared) }
    val available = layout as? LayoutState.Available
    // Row actions need the daemon: the sheet closes with the session or with its host's connection.
    val sheetOpen = state.actionSheet?.let { ref ->
        (listing.needsYou + listing.others).any { it.reachable && RowRef(it.route, it.session.info.id) == ref }
    } == true
    val view = available?.layout?.view ?: LayoutView.MY_GROUPS
    val project = available?.layout?.project(view) ?: ProjectSelection.All
    val host = available?.layout?.host(view)
    return state.copy(
        actionSheet = state.actionSheet.takeIf { sheetOpen },
        movePicker = state.movePicker.takeIf { picker ->
            (listing.needsYou + listing.others).any { it.reachable && RowRef(it.route, it.session.info.id) == picker }
        },
        groupMenu = state.groupMenu.takeIf { menu -> available?.layout?.groups?.any { it.id == menu } == true },
        rename = state.rename.stillListed(listing.needsYou + listing.others),
        multipleHosts = hostList.size > 1,
        hostsRead = configured !is HostsState.Loading,
        storageError = (configured as? HostsState.Unreadable)?.reason,
        hasHosts = hostList.isNotEmpty(),
        needsYou = listing.needsYou.map { it.toRow(badgeMaxChars, notShared) },
        // The list waits for the layout, so that it never shows once flat and then arranged.
        rows = if (awaiting || available == null) null else others,
        view = view,
        states = available?.layout?.states(view) ?: StateSelection.DEFAULT,
        stateChips = stateChips((listing.needsYou + listing.others).mapTo(HashSet()) { it.session.ownerState }),
        project = project,
        projectChips = projectChips((listing.needsYou + listing.others).map { sessionProjectLabel(it.session.info) }, project),
        host = host,
        hostOptions = hostOptions(hostList.map { it.toEntry(feeds[it.route].link()) }, host),
        groups = available?.layout?.presentView(others, SessionRow::layoutKey, sectionTitleMaxChars, SessionRow::facts).orEmpty(),
        ownerGroups = available?.layout?.orderedGroups().orEmpty(),
        layoutIssue = available?.issue,
        statuses = statuses,
        refreshing = feeds.values.any { it.refreshing },
        details = state.details?.let { refreshedDetails(it, listing.needsYou + listing.others, feeds, hostList, notShared) },
        // The unreachable-host reason ends with the outage of the host it explained.
        refusal = state.refusal.takeUnless {
            it == AttachRefusal.HostUnreachable && entries.none { e -> e.host.route == state.refusalHost && e.link == HostLink.Down }
        },
    ).filtered()
}

/** The lists the screen shows: [SessionListState.needsYou] and [SessionListState.groups] narrowed by the state selection and the search. */
private fun SessionListState.filtered(): SessionListState {
    val filter = SessionFilter(search.orEmpty(), states, project, host)
    val needs = filter.filterRows(needsYou, SessionRow::filterFacts)
    val sections = filter.filterSections(groups, SessionRow::filterFacts)
    val listed = needsYou.isNotEmpty() || rows.orEmpty().isNotEmpty()
    return copy(
        shownNeedsYou = needs,
        shownGroups = sections,
        noMatch = filter.isActive && listed && needs.isEmpty() && sections.all { it.rows.isEmpty() },
    )
}

/** True until a list can be shown: no host has sent one, or the lists sent are empty while a host's first one is still expected. */
private fun awaitsList(hostList: List<ConfiguredHost>, feeds: Map<HostRoute, HostFeed>, empty: Boolean): Boolean {
    val received = hostList.any { feeds[it.route]?.sessions != null }
    // A host that has neither sent a list nor failed is still expected to send one.
    val expecting = hostList.any { feeds[it.route]?.let { feed -> feed.sessions == null && feed.connection.expectsList() } ?: true }
    return !received || (empty && expecting)
}

/**
 * The open details rebuilt from the latest lists. A session that is absent from a list its host sent, or whose host is no longer
 * configured, keeps its last name and loses its fields; a host that has sent no list yet leaves the details as they are.
 */
private fun refreshedDetails(
    open: DetailsView,
    shown: List<HostedSession>,
    feeds: Map<HostRoute, HostFeed>,
    hosts: List<ConfiguredHost>,
    notShared: Set<SessionKey>,
): DetailsView {
    val hosted = shown.firstOrNull { it.route == open.ref.route && it.session.info.id == open.ref.sessionId }
    return when {
        hosted != null -> DetailsView(
            open.ref,
            sessionDisplayName(hosted.session.info),
            sessionDetails(hosted.session, hosted.hostLabel, hosted.route, hosted.groupNotShared(notShared)),
        )
        hosts.none { it.route == open.ref.route } || feeds[open.ref.route]?.sessions != null -> open.copy(details = null)
        else -> open
    }
}

/** The dialog closes with the session that left the list; a host that went down keeps it, so the owner's text survives. */
private fun RenameDraft?.stillListed(sessions: List<HostedSession>): RenameDraft? =
    takeIf { draft -> draft != null && sessions.any { RowRef(it.route, it.session.info.id) == draft.ref } }

private fun ConfiguredHost.toEntry(link: HostLink) =
    HostEntry(route.toString(), hostDisplayLabel(this), if (link == HostLink.Connected) HostReach.Connected else HostReach.Unreachable)

private fun HostFeed?.link(): HostLink = when (this?.connection) {
    HostConnection.Connected -> HostLink.Connected
    is HostConnection.Reconnecting, HostConnection.NotReported -> HostLink.Down
    HostConnection.Connecting, null -> HostLink.Connecting
}

/** A host that is down or no longer reported by discovery will not send a list on its own. */
private fun HostConnection.expectsList(): Boolean = this !is HostConnection.Reconnecting && this != HostConnection.NotReported

/** External sessions never carry the key; any other session is marked when the layout recorded that the daemon cannot hold it. */
private fun HostedSession.groupNotShared(notShared: Set<SessionKey>): Boolean =
    session.info.external == true || SessionKey(route.toString(), session.info.id) in notShared

private fun HostedSession.toRow(badgeMaxChars: Int, notShared: Set<SessionKey>): SessionRow {
    val info = session.info
    val facts = workFacts(info.metadata.orEmpty())
    val badge = facts?.let(::workBadge)
    val workItem = facts?.id?.let { WorkItem(facts.provider, facts.kind, it, badge?.link.orEmpty()) }
    return SessionRow(
        route = route,
        sessionId = info.id,
        title = sessionDisplayName(info),
        agent = sanitizeText(info.agent),
        ownerState = session.ownerState,
        project = sessionProjectLabel(info),
        workItem = workItem,
        badge = badge?.let { clipDisplay(it.link, badgeMaxChars) },
        badgeRole = badge?.role?.let { clipDisplay(it, badgeMaxChars) },
        hasLink = reachable && openableWorkLink(info.metadata.orEmpty()) != null,
        runtimeDetail = runtimeDetail(info),
        hostLabel = hostLabel,
        link = link,
        attachRefusal = if (link == HostLink.Down) AttachRefusal.HostUnreachable else AttachRefusal.forSession(info.external, info.state),
        canRename = reachable && info.external != true,
        canStop = reachable && offersStop(info),
        canRemove = reachable && offersRemove(info),
        resume = resumeOffer(info),
        notShared = groupNotShared(notShared),
    )
}
