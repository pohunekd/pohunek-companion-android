package dev.pohunek.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Result of `session.stop`; false when no live session was stopped, for example one that had ended already. */
@Serializable
data class SessionStopResult(val stopped: Boolean)

/**
 * A process a consenting removal accepted without proving it gone. [command] is the kernel command name, which the daemon escapes
 * so that it is printable; the start identity the daemon also sends is not used.
 */
@Serializable
data class UnconfirmedProcess(val pid: Long, val command: String? = null) {
    /** `pid (command)`, or the pid alone when the process table no longer listed it. */
    fun describe(): String = if (command == null) pid.toString() else "$pid ($command)"
}

/** Result of `session.remove` and `session.remove_accepting_unconfirmed`; the daemon omits the process list when it is empty. */
@Serializable
data class SessionRemoveResult(
    val removed: Boolean,
    val stopped: Boolean,
    @SerialName("worktrees_removed") val worktreesRemoved: Long,
    @SerialName("worktrees_failed") val worktreesFailed: Long,
    @SerialName("accepted_unconfirmed_processes") val acceptedUnconfirmedProcesses: List<UnconfirmedProcess> = emptyList(),
)
