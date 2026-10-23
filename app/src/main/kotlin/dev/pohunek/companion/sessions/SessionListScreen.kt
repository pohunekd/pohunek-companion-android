package dev.pohunek.companion.sessions

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pohunek.companion.R
import dev.pohunek.companion.core.mvi.CollectEffect
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import dev.pohunek.companion.protocol.HostRoute

@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    detailValueMaxChars: Int,
    onOpenTerminal: (HostRoute, String) -> Unit,
    onOpenHosts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNewSession: (host: String?, project: String?, group: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val linkUnavailable = stringResource(R.string.link_unavailable)
    val noLinkApp = stringResource(R.string.link_no_app)
    val editRefused = stringResource(R.string.group_edit_refused)
    CollectEffect(viewModel.effect) { effect ->
        when (effect) {
            is SessionListEffect.OpenTerminal -> onOpenTerminal(effect.route, effect.sessionId)
            SessionListEffect.OpenHosts -> onOpenHosts()
            is SessionListEffect.OpenNewSession -> onOpenNewSession(effect.host, effect.project, effect.group)
            is SessionListEffect.OpenLink -> try {
                context.startActivity(Intent(Intent.ACTION_VIEW, effect.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) {
                viewModel.onEvent(SessionListEvent.LinkNotHandled)
                snackbar.showSnackbar(noLinkApp)
            }
            SessionListEffect.LinkUnavailable -> snackbar.showSnackbar(linkUnavailable)
            SessionListEffect.LayoutEditRefused -> snackbar.showSnackbar(editRefused)
        }
    }
    // Hoisted so the list keeps its scroll position while the details cover it.
    val listState = rememberLazyListState()
    val details = state.details
    if (details != null) {
        DetailsScreen(details, detailValueMaxChars, viewModel::onEvent, modifier)
    } else {
        SessionListContent(state, viewModel::onEvent, onOpenHosts, onOpenSettings, { onOpenNewSession(null, null, null) }, snackbar, listState, modifier)
    }
    state.actionDialog?.let { ActionDialog(it, viewModel::onEvent) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListContent(
    state: SessionListState,
    onEvent: (SessionListEvent) -> Unit,
    onOpenHosts: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewSession: () -> Unit,
    snackbar: SnackbarHostState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val listShown = state.hostsRead && state.hasHosts && state.storageError == null
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { NewSessionButton(listShown, onNewSession) },
        topBar = { SessionListTopBar(state, listShown, onEvent, onOpenHosts, onOpenSettings) },
    ) { padding ->
        val body = Modifier.padding(padding).fillMaxSize()
        when {
            !state.hostsRead -> Centered(body) { CircularProgressIndicator() }
            state.storageError != null -> Centered(body) {
                Message(stringResource(R.string.host_storage_unreadable, state.storageError), "HostStorage.error", error = true)
            }
            !state.hasHosts -> Centered(body) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PohunekTheme.spacing.md)) {
                    Message(stringResource(R.string.session_list_no_hosts), "SessionList.noHosts")
                    Button(onClick = onOpenHosts, modifier = Modifier.testTag("SessionList.addHost")) {
                        Text(stringResource(R.string.session_list_add_host))
                    }
                }
            }
            else -> Column(body) {
                state.layoutIssue?.let { LayoutIssueBanner(it) }
                state.refusal?.let { RefusalBanner(it) { onEvent(SessionListEvent.DismissRefusal) } }
                state.resumeNote?.let { ResumeNoteBanner(it) { onEvent(SessionListEvent.DismissResumeNote) } }
                state.groupNote?.let { GroupNoteBanner(it) { onEvent(SessionListEvent.DismissGroupNote) } }
                state.statuses.forEach { HostStatusLine(it) }
                ViewChips(state.view, onEvent)
                StateChips(state.stateChips, state.states, onEvent)
                if (state.projectChips.size > 1) ProjectChips(state.projectChips, onEvent)
                if (state.hostOptions.isNotEmpty()) HostChips(state.hostOptions, onEvent)
                SessionRows(state, onEvent, listState, Modifier.weight(1f).fillMaxWidth())
                RowWindows(state, onEvent)
            }
        }
    }
}

/** The windows opened from a row or a group header: the action sheet, the rename dialog, the move list, the group menu and the group name dialog. */
@Composable
private fun RowWindows(state: SessionListState, onEvent: (SessionListEvent) -> Unit) {
    val sheetRow = state.actionSheet?.let { ref -> (state.needsYou + state.rows.orEmpty()).firstOrNull { it.ref == ref } }
    if (sheetRow != null) RowActionSheet(sheetRow, sheetRow.ref in state.resuming, state.stepOffer(sheetRow), onEvent)
    state.rename?.let { RenameDialog(it, onEvent) }
    val moveRow = state.movePicker?.let { ref -> (state.needsYou + state.rows.orEmpty()).firstOrNull { it.ref == ref } }
    if (moveRow != null) MoveToGroupSheet(moveRow, state.ownerGroups, onEvent)
    state.groups.firstOrNull { it.id == state.groupMenu }?.let { GroupMenuSheet(it, state.groupStepOffer(it.id), onEvent) }
    state.groupDraft?.let { GroupNameDialog(it, onEvent) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionRows(state: SessionListState, onEvent: (SessionListEvent) -> Unit, listState: LazyListState, modifier: Modifier) {
    val drag = remember(listState) { ListDrag(listState) }
    val editable = state.view.isEditable
    val items = remember(state.shownGroups, editable) { if (editable) dragItems(state.shownGroups) else emptyMap() }
    val haptics = LocalHapticFeedback.current
    SideEffect {
        drag.items = items
        drag.haptics = haptics
        drag.onDrop = { target -> onEvent(target.event()) }
    }
    LaunchedEffect(items) { drag.dropIfGone() }
    DragAutoScroll(drag, listState)
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { onEvent(SessionListEvent.Refresh) }, modifier = modifier) {
        val rows = state.rows
        LazyColumn(
            with(drag) { Modifier.fillMaxSize().testTag("SessionList.list").reorderGesture() },
            listState,
            contentPadding = PaddingValues(bottom = PohunekTheme.fabClearance),
        ) {
            when {
                rows == null && state.statuses.isNotEmpty() -> item { Spacer(Modifier.fillParentMaxSize()) }
                rows == null -> item {
                    Centered(Modifier.fillParentMaxSize()) { Message(stringResource(R.string.session_list_loading), "SessionList.loading") }
                }
                rows.isEmpty() && state.needsYou.isEmpty() ->
                    item { Centered(Modifier.fillParentMaxSize()) { Message(stringResource(R.string.session_list_empty), "SessionList.empty") } }
                state.noMatch ->
                    item { Centered(Modifier.fillParentMaxSize()) { Message(stringResource(R.string.session_list_no_match), "SessionList.noMatch") } }
                else -> {
                    if (state.shownNeedsYou.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.session_list_needs_you), "SessionList.needsYou") }
                        items(state.shownNeedsYou, key = { it.listKey }) { row -> RowWithDivider(row, onEvent) }
                    }
                    for (section in state.shownGroups) {
                        item(key = groupItemKey(section.id)) {
                            GroupHeader(section, editable, lifted = drag.source == groupItemKey(section.id), onEvent)
                        }
                        if (!section.collapsed) {
                            items(section.rows, key = { it.listKey }) { row ->
                                RowWithDivider(row, onEvent, draggable = editable, lifted = drag.source == row.listKey)
                            }
                        }
                    }
                    if (editable) item(key = "newGroup") { NewGroupButton(onEvent) }
                }
            }
        }
        DragOverlay(drag, Modifier.fillMaxSize())
    }
}

@Composable
private fun NewGroupButton(onEvent: (SessionListEvent) -> Unit) {
    TextButton(
        onClick = { onEvent(SessionListEvent.OpenNewGroup) },
        modifier = Modifier.padding(horizontal = PohunekTheme.spacing.sm).testTag("SessionList.newGroup"),
    ) {
        Text(stringResource(R.string.group_new))
    }
}

@Composable
private fun RowWithDivider(row: SessionRow, onEvent: (SessionListEvent) -> Unit, draggable: Boolean = false, lifted: Boolean = false) {
    SessionRowItem(
        row,
        onClick = { onEvent(SessionListEvent.OpenSession(row.ref)) },
        onMore = { onEvent(SessionListEvent.OpenActions(row.ref)) },
        draggable = draggable,
        lifted = lifted,
    )
    HorizontalDivider()
}

@Composable
private fun SectionHeader(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PohunekTheme.spacing.gutter, vertical = PohunekTheme.spacing.sm).testTag(tag),
    )
}

@Composable
private fun HostStatusLine(status: HostStatus) {
    val connection = status.connection
    val code = connection.code
    val text = if (code != null) {
        stringResource(R.string.host_status_refused, status.hostLabel, code, connection.reason)
    } else {
        stringResource(R.string.host_status_reconnecting, status.hostLabel, connection.attempt, connection.reason)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(PohunekTheme.spacing.gutter).testTag("SessionList.status"),
    )
}

@Composable
internal fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.padding(horizontal = PohunekTheme.spacing.gutter), contentAlignment = Alignment.Center) { content() }
}

@Composable
internal fun Message(text: String, tag: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.testTag(tag),
    )
}
