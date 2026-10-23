// Ported to Kotlin from pohunek-companion `crates/core/src/owner_state.rs` (derive, is_idle_without_work, has_runtime_problem).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.AgentActivity
import dev.pohunek.companion.protocol.BackgroundStatus
import dev.pohunek.companion.protocol.RuntimeState
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionState
import dev.pohunek.companion.protocol.SubagentLifecycle
import kotlin.time.Duration

/** What a session means to the owner, derived from the daemon's facts. */
enum class OwnerState {
    /** An agent waits for the owner: an open attention notification or `blocked` activity. */
    NeedsOwner,

    /** The agent works, or is idle while subagents or background tasks run or the idle threshold has not passed. */
    Working,

    /** Idle with no work for longer than the idle threshold. */
    Idle,

    /** The runtime is neither healthy nor lost (`conflict`, `incompatible`), so the session is not known to be running. */
    RuntimeProblem,

    /** The session stopped, finished, failed, or lost its runtime. */
    Ended,

    /** No agent activity is reported, for example a plain shell. */
    NoAgentState,
}

/** The condition whose duration the idle clock measures. */
internal fun isIdleWithoutWork(info: SessionInfo): Boolean =
    info.activity == AgentActivity.Idle && !hasRunningWork(info) && !isEnded(info) && !hasRuntimeProblem(info)

/** The runtime is in a state that is neither healthy nor lost, so the reported activity cannot be trusted. */
private fun hasRuntimeProblem(info: SessionInfo): Boolean = info.runtime?.state.let { it == RuntimeState.Conflict || it == RuntimeState.Incompatible }

private fun hasRunningWork(info: SessionInfo): Boolean {
    val subagent = info.subagents.any { it.lifecycle == SubagentLifecycle.Running }
    val background = info.background?.items?.any { it.status == BackgroundStatus.Running || it.status == BackgroundStatus.Pending } == true
    return subagent || background
}

/** A terminal session state or a lost runtime. */
internal fun isEnded(info: SessionInfo): Boolean =
    info.state == SessionState.Stopped || info.state == SessionState.Done || info.state == SessionState.Failed || info.runtime?.state == RuntimeState.Lost

/**
 * Derives the owner state. [idleFor] is how long the idle-without-work condition has held, or null while it does not; an idle session
 * counts as working until it has been idle for a full [idleThreshold].
 */
fun deriveOwnerState(info: SessionInfo, openAttention: Boolean, idleFor: Duration?, idleThreshold: Duration): OwnerState = when {
    isEnded(info) -> OwnerState.Ended
    hasRuntimeProblem(info) -> OwnerState.RuntimeProblem
    openAttention || info.activity == AgentActivity.Blocked -> OwnerState.NeedsOwner
    info.activity == AgentActivity.Working -> OwnerState.Working
    info.activity == AgentActivity.Idle ->
        if (!hasRunningWork(info) && idleFor != null && idleFor >= idleThreshold) OwnerState.Idle else OwnerState.Working
    else -> OwnerState.NoAgentState
}
