// Ported to Kotlin from pohunek-companion `crates/core/src/session.rs` (offer_stop, offer_remove) and `crates/core/src/lifecycle.rs`;
// the eligibility rules mirror `SessionInfo::can_stop` and `can_remove` of Pohunek v0.35.2. Repository:
// https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.RuntimeState
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionState

/** A daemon call that ends or deletes a session; [verb] is the word the messages use and [method] the daemon method it calls. */
enum class SessionAction(val verb: String, val method: String) {
    /** `session.stop`: the agent ends and the session stays listed as ended. */
    Stop("stop", "session.stop"),

    /** `session.remove`: the session is deleted, stopped first when live, together with the worktree checkout Pohunek made for it. */
    Remove("remove", "session.remove"),

    /** `session.remove_accepting_unconfirmed`: [Remove] that also proceeds past processes the daemon cannot prove ended. */
    RemoveAnyway("remove", "session.remove_accepting_unconfirmed"),
}

/** Error code of a removal the daemon refused because it cannot prove the runtime's processes ended. */
const val UNCONFIRMED_PROCESSES_CODE: String = "runtime_supervision_ambiguous"

private val problemRuntimes = setOf(RuntimeState.Conflict, RuntimeState.Incompatible)

private val terminalStates = setOf(SessionState.Stopped, SessionState.Done, SessionState.Failed)

private fun SessionInfo.runtimeIn(states: Set<RuntimeState>): Boolean = runtime?.state in states

private fun SessionInfo.isExternal(): Boolean = external == true

private fun SessionInfo.daemonCanStop(): Boolean {
    if (isExternal() || state in terminalStates) return false
    return runtime?.state?.let { it == RuntimeState.Live || it == RuntimeState.Starting || it == RuntimeState.Reconnecting } ?: true
}

/**
 * Whether Stop is offered: when the daemon's rule allows it, and also for a live-looking session in `conflict`, which the daemon stops
 * when the recorded identity checks out and otherwise refuses with a reason the dialog shows.
 */
fun offersStop(info: SessionInfo): Boolean =
    info.daemonCanStop() || (!info.isExternal() && info.state !in terminalStates && info.runtimeIn(setOf(RuntimeState.Conflict)))

/** Whether Remove is offered: the daemon's rule, plus `conflict` and `incompatible` runtimes, which it retires or refuses with a reason. */
fun offersRemove(info: SessionInfo): Boolean {
    if (info.isExternal()) return false
    if (info.runtimeIn(problemRuntimes)) return true
    return info.state in terminalStates || info.daemonCanStop() || info.runtimeIn(setOf(RuntimeState.Lost))
}

/** Whether the runtime is `conflict` or `incompatible`, so that the daemon may refuse and the dialog says so. */
fun hasConflictingRuntime(info: SessionInfo): Boolean = info.runtimeIn(problemRuntimes)

/** How an attempt to stop or remove ended. */
sealed interface SessionActionOutcome {
    /** The daemon did it; [worktreesFailed] checkouts could not be deleted and [unconfirmedProcesses] (`pid (command)`) were left unsignalled. */
    data class Done(val worktreesFailed: Long = 0, val unconfirmedProcesses: List<String> = emptyList()) : SessionActionOutcome

    /** The daemon answered with an error. */
    data class Refused(val code: String, val message: String, val recover: String?) : SessionActionOutcome

    /** The request may or may not have been applied: no answer arrived in time or the connection broke after it was sent. */
    data object Unknown : SessionActionOutcome

    /** The request did not reach the daemon or its answer was unusable; [reason] is the client's own description. */
    data class Failed(val reason: String) : SessionActionOutcome
}

/** The subject of a message: the session id, followed by its host. The id is the daemon's, so unlike a name it is safe to quote. */
fun actionSubject(sessionId: String, hostLabel: String): String = "$sessionId on $hostLabel"

/** The line shown in the dialog when [action] did not succeed. Daemon text is sanitized because it can quote session names. */
fun actionFailureText(action: SessionAction, subject: String, outcome: SessionActionOutcome): String? = when (outcome) {
    is SessionActionOutcome.Done -> null
    is SessionActionOutcome.Refused -> {
        val hint = outcome.recover?.let(::sanitizeText)?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
        "Cannot ${action.verb} $subject: ${sanitizeText(outcome.message)}$hint"
    }
    SessionActionOutcome.Unknown ->
        "Cannot ${action.verb} $subject: the outcome is unknown and the action may still complete; the list shows the result."
    is SessionActionOutcome.Failed -> "Cannot ${action.verb} $subject: ${sanitizeText(outcome.reason)}"
}

/** What a successful removal left behind for the owner to handle, or null when nothing. */
fun removalNotice(subject: String, done: SessionActionOutcome.Done): String? {
    val parts = buildList {
        if (done.worktreesFailed > 0) add("${done.worktreesFailed} worktree checkout(s) could not be deleted and need manual cleanup")
        if (done.unconfirmedProcesses.isNotEmpty()) {
            val processes = done.unconfirmedProcesses.joinToString(", ")
            add("these processes could not be confirmed ended and were not signalled; they may still be running: $processes")
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.let { "Removed $subject, but ${it.joinToString("; ")}" }
}
