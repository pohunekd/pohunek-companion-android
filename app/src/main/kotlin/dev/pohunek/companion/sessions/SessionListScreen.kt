package dev.pohunek.companion.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pohunek.companion.R
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import dev.pohunek.companion.protocol.AgentActivity
import dev.pohunek.companion.protocol.SessionState

@Composable
fun SessionListScreen(viewModel: SessionListViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SessionListContent(state, viewModel::onEvent, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListContent(state: SessionListState, onEvent: (SessionListEvent) -> Unit, modifier: Modifier = Modifier) {
    val listShown = state.host != null && state.form == null && state.storageError == null
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.host?.let {
                            it.label ?: it.route.toString()
                        } ?: stringResource(R.string.app_name),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
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
            else -> SessionRows(state, { onEvent(SessionListEvent.Refresh) }, body)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionRows(state: SessionListState, onRefresh: () -> Unit, modifier: Modifier) {
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh, modifier = modifier) {
        LazyColumn(Modifier.fillMaxSize().testTag("SessionList.list")) {
            val rows = state.rows
            val error = state.error
            when {
                error != null -> item { Centered(Modifier.fillParentMaxSize()) { ListFailure(error) } }
                rows == null -> item {
                    Centered(Modifier.fillParentMaxSize()) { Message(stringResource(R.string.session_list_loading), "SessionList.loading") }
                }
                rows.isEmpty() -> item { Centered(Modifier.fillParentMaxSize()) { Message(stringResource(R.string.session_list_empty), "SessionList.empty") } }
                else -> items(rows, key = { it.id }) { row ->
                    SessionRowItem(row)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ListFailure(error: ListError) {
    val text = if (error.incompatible) {
        stringResource(R.string.session_list_incompatible, error.message)
    } else {
        stringResource(R.string.session_list_error, error.message)
    }
    Message(text, "SessionList.error", error = true)
}

@Composable
private fun SessionRowItem(row: SessionRow) {
    val summary = listOfNotNull(row.agent, stateLabel(row.state), row.activity?.let { activityLabel(it) }).joinToString(" · ")
    ListItem(
        headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("SessionList.row"),
    )
}

@Composable
private fun stateLabel(state: SessionState): String = stringResource(
    when (state) {
        SessionState.Starting -> R.string.session_state_starting
        SessionState.Running -> R.string.session_state_running
        SessionState.Stopped -> R.string.session_state_stopped
        SessionState.Done -> R.string.session_state_done
        SessionState.Failed -> R.string.session_state_failed
    },
)

@Composable
private fun activityLabel(activity: AgentActivity): String = stringResource(
    when (activity) {
        AgentActivity.Working -> R.string.session_activity_working
        AgentActivity.Blocked -> R.string.session_activity_blocked
        AgentActivity.Idle -> R.string.session_activity_idle
    },
)

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
