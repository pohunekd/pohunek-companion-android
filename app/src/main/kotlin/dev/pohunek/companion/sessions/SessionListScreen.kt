package dev.pohunek.companion.sessions

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pohunek.companion.R
import dev.pohunek.companion.core.data.hosts.ConfiguredHost
import dev.pohunek.companion.core.mvi.CollectEffect
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProtocolError

@Composable
fun SessionListScreen(viewModel: SessionListViewModel, onOpenTerminal: (HostRoute, String) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val linkUnavailable = stringResource(R.string.link_unavailable)
    val noLinkApp = stringResource(R.string.link_no_app)
    CollectEffect(viewModel.effect) { effect ->
        when (effect) {
            is SessionListEffect.OpenTerminal -> onOpenTerminal(effect.route, effect.sessionId)
            is SessionListEffect.OpenLink -> try {
                context.startActivity(Intent(Intent.ACTION_VIEW, effect.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) {
                snackbar.showSnackbar(noLinkApp)
            }
            SessionListEffect.LinkUnavailable -> snackbar.showSnackbar(linkUnavailable)
        }
    }
    SessionListContent(state, viewModel::onEvent, snackbar, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListContent(
    state: SessionListState,
    onEvent: (SessionListEvent) -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val listShown = state.host != null && state.form == null && state.storageError == null
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(hostName(state.host) ?: stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    if (listShown) {
                        TextButton(onClick = { onEvent(SessionListEvent.Refresh) }, modifier = Modifier.testTag("SessionList.refresh")) {
                            Text(stringResource(R.string.session_list_refresh))
                        }
                        TextButton(onClick = { onEvent(SessionListEvent.EditHost) }, modifier = Modifier.testTag("SessionList.editHost")) {
                            Text(stringResource(R.string.session_list_edit_host))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val body = Modifier.padding(padding).fillMaxSize()
        when {
            !state.hostsRead -> Centered(body) { CircularProgressIndicator() }
            state.storageError != null -> Centered(body) {
                Message(stringResource(R.string.host_storage_unreadable, state.storageError), "HostStorage.error", error = true)
            }
            state.form != null -> HostFormContent(state.form, state.host != null, onEvent, body)
            else -> Column(body) {
                state.refusal?.let { RefusalBanner(it) { onEvent(SessionListEvent.DismissRefusal) } }
                (state.connection as? ListConnection.Reconnecting)?.let {
                    ReconnectingStatus(hostName(state.host).orEmpty(), it)
                }
                SessionRows(state, onEvent, Modifier.weight(1f).fillMaxWidth())
                val sheetRow = state.actionSheet?.let { id -> (state.needsYou + state.rows.orEmpty()).firstOrNull { it.id == id } }
                if (sheetRow != null) RowActionSheet(sheetRow, onEvent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionRows(state: SessionListState, onEvent: (SessionListEvent) -> Unit, modifier: Modifier) {
    val connection = state.connection
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { onEvent(SessionListEvent.Refresh) }, modifier = modifier) {
        val rows = state.rows
        val dimmed = if (connection is ListConnection.Connected) Modifier else Modifier.alpha(PohunekTheme.DIMMED_ALPHA)
        LazyColumn(Modifier.fillMaxSize().then(dimmed).testTag("SessionList.list")) {
            when {
                rows == null && connection is ListConnection.Reconnecting -> item { Spacer(Modifier.fillParentMaxSize()) }
                rows == null -> item {
                    Centered(Modifier.fillParentMaxSize()) { Message(stringResource(R.string.session_list_loading), "SessionList.loading") }
                }
                rows.isEmpty() && state.needsYou.isEmpty() ->
                    item { Centered(Modifier.fillParentMaxSize()) { Message(stringResource(R.string.session_list_empty), "SessionList.empty") } }
                else -> {
                    if (state.needsYou.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.session_list_needs_you), "SessionList.needsYou") }
                        items(state.needsYou, key = { it.id }) { row -> RowWithDivider(row, onEvent) }
                        if (rows.isNotEmpty()) item { SectionHeader(stringResource(R.string.session_list_other_sessions), "SessionList.others") }
                    }
                    items(rows, key = { it.id }) { row -> RowWithDivider(row, onEvent) }
                }
            }
        }
    }
}

@Composable
private fun RowWithDivider(row: SessionRow, onEvent: (SessionListEvent) -> Unit) {
    SessionRowItem(row, onClick = { onEvent(SessionListEvent.OpenSession(row.id)) }, onMore = { onEvent(SessionListEvent.OpenActions(row.id)) })
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

private fun hostName(host: ConfiguredHost?): String? = host?.let { it.label ?: it.route.toString() }

@Composable
private fun ReconnectingStatus(host: String, connection: ListConnection.Reconnecting) {
    val incompatible = connection.code == ProtocolError.VERSION_MISMATCH_CODE
    val text = if (incompatible) {
        stringResource(R.string.session_list_incompatible, host, connection.attempt, connection.reason)
    } else {
        stringResource(R.string.session_list_reconnecting, host, connection.attempt, connection.reason)
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
