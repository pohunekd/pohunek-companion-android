package dev.pohunek.companion.sessions

import dev.pohunek.companion.core.data.commands.SessionCommands
import dev.pohunek.companion.core.data.commands.SetGroupResult
import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.domain.layout.GroupNameCheck
import dev.pohunek.companion.core.domain.layout.checkGroupName
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.warn
import dev.pohunek.companion.core.domain.sessions.SessionKey
import dev.pohunek.companion.protocol.HostRoute

/** One session a write of `companion.group` is meant for, with what the call needs to know about its host. */
internal data class WriteTarget(
    val route: HostRoute,
    val sessionId: String,
    val hostLabel: String,
    val connected: Boolean,
    val external: Boolean,
) {
    val key: SessionKey get() = SessionKey(route.toString(), sessionId)
}

/** How the write of one session ended. */
internal sealed interface WriteOutcome {
    /** The daemon holds the key; the session event carries it back. */
    data object Written : WriteOutcome

    /** The session cannot carry the key (external, refused by the daemon, or a name that cannot be a value): its placement stays local. */
    data object Unwritable : WriteOutcome

    /** The session was left as it was. [reason] is the daemon's message, or null when the host is not connected. */
    data class Failed(val reason: String?) : WriteOutcome
}

/** What a batch of writes came to. [attempted] counts the sessions written or failed; unwritable ones are neither. */
internal data class WriteSummary(
    val attempted: Int,
    val failures: List<Pair<WriteTarget, String?>>,
    val unwritable: List<SessionKey>,
) {
    /** The message for the owner, or null when every session that could be written was. */
    fun note(): GroupWriteNote? =
        failures.firstOrNull()?.let { (target, reason) -> GroupWriteNote(failures.size, attempted, target.hostLabel, reason) }
}

/** Writes `companion.group` to sessions, one call per session; nothing here changes the layout. */
internal class GroupWrites(private val commands: SessionCommands, private val log: AppLog) {
    /** Sets [name] on [target], or removes the key when it is null. An external session is never sent a request. */
    suspend fun write(target: WriteTarget, name: String?, maxBytes: Int): WriteOutcome {
        val check = name?.let { checkGroupName(it, maxBytes) }
        val fields = listOf(hostKeyField(target.route), LogField.sessionId(target.sessionId))
        return when {
            target.external -> WriteOutcome.Unwritable
            check is GroupNameCheck.Invalid -> {
                log.warn("layout.group_write_skipped", fields + LogField.errorCode(check.problem.code))
                WriteOutcome.Unwritable
            }
            !target.connected -> {
                log.warn("layout.group_write_skipped", fields + LogField.errorCode(NOT_CONNECTED_CODE))
                WriteOutcome.Failed(null)
            }
            else -> send(target, (check as? GroupNameCheck.Valid)?.name)
        }
    }

    private suspend fun send(target: WriteTarget, value: String?): WriteOutcome = when (val result = commands.setGroup(target.route, target.sessionId, value)) {
        SetGroupResult.Done -> WriteOutcome.Written
        is SetGroupResult.Unwritable -> WriteOutcome.Unwritable
        is SetGroupResult.Failed -> WriteOutcome.Failed(result.detail)
    }

    /** Writes [name] to every target in turn; a failure does not stop the others. */
    suspend fun writeAll(targets: List<WriteTarget>, name: String?, maxBytes: Int): WriteSummary {
        var attempted = 0
        val failures = mutableListOf<Pair<WriteTarget, String?>>()
        val unwritable = mutableListOf<SessionKey>()
        for (target in targets) {
            when (val outcome = write(target, name, maxBytes)) {
                WriteOutcome.Written -> attempted++
                WriteOutcome.Unwritable -> unwritable += target.key
                is WriteOutcome.Failed -> {
                    attempted++
                    failures += target to outcome.reason
                }
            }
        }
        return WriteSummary(attempted, failures, unwritable)
    }

    private companion object {
        const val NOT_CONNECTED_CODE = "not_connected"
    }
}
