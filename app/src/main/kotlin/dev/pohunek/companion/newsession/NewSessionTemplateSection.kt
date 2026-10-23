package dev.pohunek.companion.newsession

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.newsession.TemplateError
import dev.pohunek.companion.core.domain.sessions.sanitizeText
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/** The Blank chip, one chip per static template of the selected project, and what went wrong with the last template. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TemplateSection(state: NewSessionState, onEvent: (NewSessionEvent) -> Unit) {
    val choices = state.choices
    if (choices == null || state.form.project == null) return
    val spacing = PohunekTheme.spacing
    Column(Modifier.padding(horizontal = spacing.gutter), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(stringResource(R.string.new_session_template), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            FilterChip(
                selected = state.form.template == null,
                onClick = { onEvent(NewSessionEvent.SelectTemplate(null)) },
                enabled = !state.activity.inFlight,
                label = { Text(stringResource(R.string.new_session_template_blank)) },
                modifier = Modifier.testTag("NewSession.templateBlank"),
            )
            state.templates.orEmpty().forEach { name ->
                FilterChip(
                    selected = name == state.form.template,
                    onClick = { onEvent(NewSessionEvent.SelectTemplate(name)) },
                    enabled = !state.activity.inFlight,
                    label = { Text(sanitizeText(name)) },
                    modifier = Modifier.testTag("NewSession.template"),
                )
            }
        }
        if (state.form.templateLoading) {
            Text(
                stringResource(R.string.new_session_template_loading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("NewSession.templateLoading"),
            )
        }
        state.templateError?.let {
            Text(templateErrorText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("NewSession.templateError"))
        }
    }
}

/** Branch and base branch of a Blank session; the host's missing git or worktree support is stated here before anything is typed. */
@Composable
internal fun AdvancedSection(state: NewSessionState, onEvent: (NewSessionEvent) -> Unit) {
    val spacing = PohunekTheme.spacing
    val locked = state.activity.inFlight
    Column(Modifier.padding(horizontal = spacing.gutter), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        TextButton(onClick = { onEvent(NewSessionEvent.ToggleAdvanced) }, modifier = Modifier.testTag("NewSession.advanced")) {
            Text(stringResource(R.string.new_session_advanced) + if (state.advancedOpen) " -" else " +")
        }
        if (state.advancedOpen) {
            val unsupported = when {
                state.choices?.gitAvailable == false -> R.string.new_session_worktree_note_git
                state.choices?.worktreeSupported == false -> R.string.new_session_worktree_note_worktree
                else -> null
            }
            unsupported?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("NewSession.worktreeNote"),
                )
            }
            OutlinedTextField(
                value = state.form.branch,
                onValueChange = { onEvent(NewSessionEvent.EditBranch(it)) },
                label = { Text(stringResource(R.string.new_session_branch)) },
                singleLine = true,
                enabled = !locked,
                modifier = Modifier.fillMaxWidth().testTag("NewSession.branch"),
            )
            OutlinedTextField(
                value = state.form.baseBranch,
                onValueChange = { onEvent(NewSessionEvent.EditBaseBranch(it)) },
                label = { Text(stringResource(R.string.new_session_base_branch)) },
                singleLine = true,
                enabled = !locked,
                modifier = Modifier.fillMaxWidth().testTag("NewSession.baseBranch"),
            )
        }
    }
}

@Composable
private fun templateErrorText(error: TemplateError): String = when (error) {
    is TemplateError.Variables -> stringResource(R.string.new_session_template_variables, sanitizeText(error.template))
    is TemplateError.AgentNotLaunchable -> stringResource(R.string.new_session_template_agent_not_launchable, sanitizeText(error.agent))
    is TemplateError.Daemon -> stringResource(R.string.new_session_template_failed, error.detail)
}
