package dev.pohunek.companion.newsession

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.newsession.AgentChoice
import dev.pohunek.companion.core.domain.newsession.CreateBlocker
import dev.pohunek.companion.core.domain.sessions.sanitizeText
import dev.pohunek.companion.core.mvi.CollectEffect
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import dev.pohunek.companion.protocol.HostRoute

/** The form that starts a session. [onOpenTerminal] replaces the form with the terminal of the new session; [onClose] leaves it. */
@Composable
fun NewSessionScreen(
    viewModel: NewSessionViewModel,
    onOpenTerminal: (HostRoute, String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEffect(viewModel.effect) { effect ->
        when (effect) {
            is NewSessionEffect.OpenTerminal -> onOpenTerminal(effect.route, effect.sessionId)
            NewSessionEffect.Close -> onClose()
        }
    }
    // Leaving while the request runs would drop its outcome, and with it the session's id.
    BackHandler(enabled = state.activity.inFlight) {}
    NewSessionContent(state, viewModel::onEvent, onClose, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSessionContent(state: NewSessionState, onEvent: (NewSessionEvent) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_session_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    TextButton(onClick = onClose, enabled = !state.activity.inFlight, modifier = Modifier.testTag("NewSession.back")) {
                        Text(stringResource(R.string.terminal_back))
                    }
                },
            )
        },
        bottomBar = { CreateBar(state, onEvent) },
    ) { padding ->
        val body = Modifier.padding(padding).fillMaxSize()
        when {
            !state.hostsRead -> Column(body, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            state.hosts.isEmpty() -> Column(body.padding(PohunekTheme.spacing.gutter)) {
                Text(stringResource(R.string.new_session_no_hosts), modifier = Modifier.testTag("NewSession.noHosts"))
            }
            else -> FormBody(state, onEvent, body)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormBody(state: NewSessionState, onEvent: (NewSessionEvent) -> Unit, modifier: Modifier) {
    val spacing = PohunekTheme.spacing
    val locked = state.activity.inFlight
    LazyColumn(modifier.testTag("NewSession.form"), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        item { SectionTitle(stringResource(R.string.new_session_host)) }
        item {
            FlowRow(Modifier.padding(horizontal = spacing.gutter), horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                state.hosts.forEach { host ->
                    FilterChip(
                        selected = host.route == state.form.host,
                        onClick = { onEvent(NewSessionEvent.SelectHost(host.route)) },
                        enabled = !locked,
                        label = { Text(host.label) },
                        modifier = Modifier.testTag("NewSession.host"),
                    )
                }
                state.incompatibleHosts.forEach { label ->
                    FilterChip(
                        selected = false,
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.new_session_host_incompatible, label)) },
                        modifier = Modifier.testTag("NewSession.hostIncompatible"),
                    )
                }
            }
        }
        item { LoadStatus(state, onEvent) }
        item { SectionTitle(stringResource(R.string.new_session_project)) }
        projectSection(state, onEvent)
        item { SectionTitle(stringResource(R.string.new_session_agent)) }
        item { AgentChips(state, onEvent) }
        item {
            OutlinedTextField(
                value = state.form.name,
                onValueChange = { onEvent(NewSessionEvent.EditName(it)) },
                label = { Text(stringResource(R.string.new_session_name)) },
                singleLine = true,
                enabled = !locked,
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.gutter).testTag("NewSession.name"),
            )
        }
        item {
            OutlinedTextField(
                value = state.form.prompt,
                onValueChange = { onEvent(NewSessionEvent.EditPrompt(it)) },
                label = { Text(stringResource(R.string.new_session_prompt)) },
                minLines = 3,
                enabled = !locked,
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.gutter).testTag("NewSession.prompt"),
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = spacing.gutter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.new_session_attach))
                Switch(
                    checked = state.attachAfterCreate,
                    onCheckedChange = { onEvent(NewSessionEvent.SetAttachAfterCreate(it)) },
                    enabled = !locked,
                    modifier = Modifier.testTag("NewSession.attach"),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PohunekTheme.spacing.gutter, vertical = PohunekTheme.spacing.xs),
    )
}

@Composable
private fun LoadStatus(state: NewSessionState, onEvent: (NewSessionEvent) -> Unit) {
    val error = state.activity.loadError
    when {
        error != null -> Column(Modifier.padding(horizontal = PohunekTheme.spacing.gutter)) {
            Text(
                stringResource(R.string.new_session_load_failed, error),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("NewSession.loadError"),
            )
            TextButton(onClick = { onEvent(NewSessionEvent.Reload) }, modifier = Modifier.testTag("NewSession.retry")) {
                Text(stringResource(R.string.new_session_retry))
            }
        }
        state.activity.loading -> Text(
            stringResource(R.string.new_session_loading),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = PohunekTheme.spacing.gutter).testTag("NewSession.loading"),
        )
    }
}

private fun LazyListScope.projectSection(state: NewSessionState, onEvent: (NewSessionEvent) -> Unit) {
    val projects = state.projects
    if (projects == null) return
    item {
        OutlinedTextField(
            value = state.projectQuery,
            onValueChange = { onEvent(NewSessionEvent.EditProjectQuery(it)) },
            label = { Text(stringResource(R.string.new_session_project_search)) },
            singleLine = true,
            enabled = !state.activity.inFlight,
            modifier = Modifier.fillMaxWidth().padding(horizontal = PohunekTheme.spacing.gutter).testTag("NewSession.projectSearch"),
        )
    }
    val visible = state.visibleProjects
    if (visible.isEmpty()) {
        item {
            Text(
                stringResource(if (projects.isEmpty()) R.string.new_session_no_projects else R.string.new_session_no_match),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = PohunekTheme.spacing.gutter).testTag("NewSession.noProjects"),
            )
        }
    }
    items(visible, key = { "project:${it.id}" }) { project ->
        val selected = project.id == state.form.project
        ListItem(
            headlineContent = { Text(project.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = if (selected) ({ Text(stringResource(R.string.new_session_selected)) }) else null,
            colors = ListItemDefaults.colors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier
                .clickable(enabled = !state.activity.inFlight) { onEvent(NewSessionEvent.SelectProject(project.id)) }
                .testTag("NewSession.project"),
        )
        HorizontalDivider()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentChips(state: NewSessionState, onEvent: (NewSessionEvent) -> Unit) {
    val agents = state.choices?.agents ?: return
    Column(Modifier.padding(horizontal = PohunekTheme.spacing.gutter), verticalArrangement = Arrangement.spacedBy(PohunekTheme.spacing.xs)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(PohunekTheme.spacing.sm)) {
            agents.forEach { agent ->
                FilterChip(
                    selected = agent.name == state.form.agent,
                    onClick = { onEvent(NewSessionEvent.SelectAgent(agent.name)) },
                    enabled = agent.launchable && !state.activity.inFlight,
                    label = { Text(sanitizeText(agent.name)) },
                    modifier = Modifier.testTag("NewSession.agent"),
                )
            }
        }
        agents.filterNot(AgentChoice::launchable).forEach { agent ->
            Text(
                stringResource(R.string.new_session_agent_unavailable, sanitizeText(agent.name), agent.unlaunchable.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("NewSession.agentNote"),
            )
        }
    }
}

/** The outcome of the last create, why Create is disabled, and the Create button; it stays above the keyboard. */
@Composable
private fun CreateBar(state: NewSessionState, onEvent: (NewSessionEvent) -> Unit) {
    val spacing = PohunekTheme.spacing
    val blocker = state.blocker
    Surface(tonalElevation = spacing.xs) {
        Column(Modifier.fillMaxWidth().padding(spacing.gutter), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            state.notice?.let { Text(noticeText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("NewSession.notice")) }
            blockerText(blocker)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("NewSession.blocker"),
                )
            }
            Button(
                onClick = { onEvent(NewSessionEvent.Create) },
                enabled = blocker == null,
                modifier = Modifier.fillMaxWidth().testTag("NewSession.create"),
            ) {
                Text(stringResource(if (state.activity.inFlight) R.string.new_session_creating else R.string.new_session_create))
            }
        }
    }
}

@Composable
private fun noticeText(notice: CreateNotice): String = when (notice) {
    is CreateNotice.Refused -> stringResource(R.string.new_session_refused, notice.message)
    is CreateNotice.NotSent -> stringResource(R.string.new_session_not_sent, notice.message)
    CreateNotice.Unconfirmed -> stringResource(R.string.new_session_unconfirmed)
    CreateNotice.PromptUnconfirmed -> stringResource(R.string.new_session_prompt_unconfirmed)
}

/** The sentence for [blocker]; none for the ones another element already explains (a load failure, an unconfirmed create). */
@Composable
private fun blockerText(blocker: CreateBlocker?): String? = when (blocker) {
    null, is CreateBlocker.LoadFailed, CreateBlocker.Unconfirmed -> null
    CreateBlocker.NoHost -> stringResource(R.string.new_session_blocker_no_host)
    CreateBlocker.Loading -> stringResource(R.string.new_session_blocker_loading)
    CreateBlocker.InFlight -> stringResource(R.string.new_session_blocker_in_flight)
    CreateBlocker.NoProjectsOnDaemon -> stringResource(R.string.new_session_blocker_no_projects)
    CreateBlocker.NoProject -> stringResource(R.string.new_session_blocker_no_project)
    CreateBlocker.NoLaunchableAgent -> stringResource(R.string.new_session_blocker_no_launchable)
    CreateBlocker.NoAgent -> stringResource(R.string.new_session_blocker_no_agent)
    is CreateBlocker.AgentNotLaunchable -> stringResource(R.string.new_session_blocker_not_launchable, sanitizeText(blocker.agent))
}
