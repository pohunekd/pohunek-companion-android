package dev.pohunek.companion.newsession

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.newsession.CreateBlocker
import dev.pohunek.companion.core.domain.newsession.LaunchBlocker
import dev.pohunek.companion.core.domain.sessions.sanitizeText

@Composable
internal fun noticeText(notice: CreateNotice): String = when (notice) {
    is CreateNotice.Refused -> stringResource(R.string.new_session_refused, notice.message)
    is CreateNotice.RefusedMaybeCreated -> stringResource(R.string.new_session_refused_maybe_created, notice.message)
    is CreateNotice.NotSent -> stringResource(R.string.new_session_not_sent, notice.message)
    CreateNotice.Unconfirmed -> stringResource(R.string.new_session_unconfirmed)
    CreateNotice.PromptUnconfirmed -> stringResource(R.string.new_session_prompt_unconfirmed)
}

/** The sentence for [blocker]; none for the ones another element already explains (a load failure, an unconfirmed create). */
@Composable
internal fun blockerText(blocker: CreateBlocker?): String? = when (blocker) {
    null, is CreateBlocker.LoadFailed, CreateBlocker.Unconfirmed -> null
    CreateBlocker.NoHost -> stringResource(R.string.new_session_blocker_no_host)
    CreateBlocker.Loading -> stringResource(R.string.new_session_blocker_loading)
    CreateBlocker.InFlight -> stringResource(R.string.new_session_blocker_in_flight)
    is CreateBlocker.RequestedProjectMissing -> stringResource(R.string.new_session_blocker_requested_missing, sanitizeText(blocker.project))
    CreateBlocker.NoProjectsOnDaemon -> stringResource(R.string.new_session_blocker_no_projects)
    CreateBlocker.NoProject -> stringResource(R.string.new_session_blocker_no_project)
    CreateBlocker.NoLaunchableAgent -> stringResource(R.string.new_session_blocker_no_launchable)
    CreateBlocker.NoAgent -> stringResource(R.string.new_session_blocker_no_agent)
    is CreateBlocker.AgentNotLaunchable -> stringResource(R.string.new_session_blocker_not_launchable, sanitizeText(blocker.agent))
    is LaunchBlocker -> launchBlockerText(blocker)
}

@Composable
internal fun launchBlockerText(blocker: LaunchBlocker): String = when (blocker) {
    LaunchBlocker.TemplateLoading -> stringResource(R.string.new_session_blocker_template_loading)
    LaunchBlocker.BaseBranchWithoutBranch -> stringResource(R.string.new_session_blocker_base_branch_without_branch)
    LaunchBlocker.GitUnavailable -> stringResource(R.string.new_session_blocker_git_unavailable)
    LaunchBlocker.WorktreeUnsupported -> stringResource(R.string.new_session_blocker_worktree_unsupported)
}
