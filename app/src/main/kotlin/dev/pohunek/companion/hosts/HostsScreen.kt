package dev.pohunek.companion.hosts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pohunek.companion.R
import dev.pohunek.companion.core.data.live.HostConnection
import dev.pohunek.companion.core.mvi.CollectEffect
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import dev.pohunek.companion.sessions.Centered
import dev.pohunek.companion.sessions.Message

@Composable
fun HostsScreen(viewModel: HostsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEffect(viewModel.effect) { effect ->
        when (effect) {
            HostsEffect.Close -> onBack()
        }
    }
    // The form of a list that has hosts closes first; with no host the form is the screen and back leaves it.
    BackHandler(enabled = state.form?.saving == false && state.hosts.isNotEmpty()) { viewModel.onEvent(HostsEvent.CancelEdit) }
    HostsContent(state, viewModel::onEvent, onBack, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostsContent(state: HostsScreenState, onEvent: (HostsEvent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hosts_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("Hosts.back")) { Text(stringResource(R.string.terminal_back)) }
                },
            )
        },
    ) { padding ->
        val body = Modifier.padding(padding).fillMaxSize()
        val form = state.form
        when {
            !state.hostsRead -> Centered(body) { CircularProgressIndicator() }
            state.storageError != null -> Centered(body) {
                Message(stringResource(R.string.host_storage_unreadable, state.storageError), "HostStorage.error", error = true)
            }
            form != null -> HostFormContent(form, state.hosts.isNotEmpty(), onEvent, body)
            else -> HostList(state, onEvent, body)
        }
    }
}

@Composable
private fun HostList(state: HostsScreenState, onEvent: (HostsEvent) -> Unit, modifier: Modifier) {
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("Hosts.list")) {
            items(state.hosts, key = { it.host.route.toString() }) { item ->
                ListItem(
                    headlineContent = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            if (item.host.label != null) Text(item.host.route.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ConnectionText(item.connection)
                        }
                    },
                    modifier = Modifier
                        .clickable { onEvent(HostsEvent.EditHost(item.host.route)) }
                        .semantics(mergeDescendants = true) {}
                        .testTag("Hosts.row"),
                )
                HorizontalDivider()
            }
        }
        Button(
            onClick = { onEvent(HostsEvent.AddHost) },
            modifier = Modifier.padding(PohunekTheme.spacing.gutter).testTag("Hosts.add"),
        ) {
            Text(stringResource(R.string.hosts_add))
        }
    }
}

@Composable
private fun ConnectionText(connection: HostConnection?) {
    val text = when (connection) {
        HostConnection.Connected -> stringResource(R.string.host_state_connected)
        HostConnection.Connecting, null -> stringResource(R.string.host_state_connecting)
        is HostConnection.Reconnecting -> {
            val code = connection.code
            if (code != null) {
                stringResource(R.string.host_state_refused, code, connection.reason)
            } else {
                stringResource(R.string.host_state_reconnecting, connection.attempt, connection.reason)
            }
        }
    }
    val color = if (connection is HostConnection.Reconnecting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Text(text, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("Hosts.status"))
}
