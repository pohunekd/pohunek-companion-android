// Ported to Kotlin from pohunek-companion `crates/core/src/model.rs` (Tracked, ActivityBaseline, runtime_scoped, apply_agent_state,
// apply_subagent_state, apply_background_state). Repository: https://github.com/pohunekd/pohunek-companion, MIT,
// Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.AgentStateUpdate
import dev.pohunek.companion.protocol.BackgroundStateUpdate
import dev.pohunek.companion.protocol.DecimalWire
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionRuntimeIdentity
import dev.pohunek.companion.protocol.SubagentStateUpdate
import kotlin.time.ComparableTimeMark

/** Last applied `agent_state` evidence for one session: `(epoch, runtime)` scopes the revision sequence. */
internal data class ActivityBaseline(val epoch: String?, val workerInstanceId: String, val generation: DecimalWire, val revision: DecimalWire) {
    /** Whether [other] belongs to the revision sequence of this baseline; a different epoch or runtime starts a new one. */
    fun sameSequence(other: ActivityBaseline): Boolean =
        epoch == other.epoch && workerInstanceId == other.workerInstanceId && generation == other.generation
}

/** A session snapshot with the observations that the snapshot itself does not carry: the activity baseline and the idle clock. */
internal class TrackedSession(var info: SessionInfo, var baseline: ActivityBaseline?, var idleSince: ComparableTimeMark?) {
    /** Starts the idle clock when the condition becomes true, keeps it while it stays true, and clears it when it ends. */
    fun refreshIdle(now: ComparableTimeMark) {
        idleSince = if (isIdleWithoutWork(info)) idleSince ?: now else null
    }

    /** Whether [next] names the same worker instance and runtime generation as the stored snapshot; two snapshots without a runtime match. */
    fun sameRuntimeAs(next: SessionInfo): Boolean {
        val stored = info.runtime
        val incoming = next.runtime
        if (stored == null || incoming == null) return stored == incoming
        return stored.workerInstanceId == incoming.workerInstanceId &&
            DecimalWire.parse(stored.runtimeGeneration) == DecimalWire.parse(incoming.runtimeGeneration)
    }

    fun runsOn(workerInstanceId: String, generation: DecimalWire): Boolean {
        val runtime = info.runtime ?: return false
        return runtime.workerInstanceId == workerInstanceId && DecimalWire.parse(runtime.runtimeGeneration) == generation
    }

    fun replaceSnapshot(snapshot: SessionInfo, now: ComparableTimeMark): EventOutcome {
        info = snapshot
        refreshIdle(now)
        return EventOutcome.Applied
    }

    fun applyAgentState(update: AgentStateUpdate, now: ComparableTimeMark): EventOutcome = scoped(update.runtime) { runtime ->
        val next = update.revision?.let { ActivityBaseline(update.activityEpoch, runtime.workerInstanceId, runtime.runtimeGeneration, it) }
        val last = baseline
        when {
            next == null -> EventOutcome.Ignored(IgnoreReason.MissingRevision)
            last != null && last.sameSequence(next) && last.revision >= next.revision -> EventOutcome.Ignored(IgnoreReason.StaleRevision)
            else -> {
                baseline = next
                replaceSnapshot(info.copy(activity = update.activity), now)
            }
        }
    }

    fun applySubagentState(update: SubagentStateUpdate, now: ComparableTimeMark): EventOutcome = scoped(update.runtime) {
        val incoming = update.subagent
        val index = info.subagents.indexOfFirst { it.provider == incoming.provider && it.id == incoming.id }
        val stored = info.subagents.getOrNull(index)
        if (stored != null && stored.revision >= incoming.revision) {
            EventOutcome.Ignored(IgnoreReason.StaleRevision)
        } else {
            val subagents = if (stored == null) info.subagents + incoming else info.subagents.toMutableList().also { it[index] = incoming }
            replaceSnapshot(info.copy(subagents = subagents), now)
        }
    }

    fun applyBackgroundState(update: BackgroundStateUpdate, now: ComparableTimeMark): EventOutcome = scoped(update.runtime) {
        val stored = info.background
        val incoming = update.background
        if (stored != null && incoming != null && stored.revision >= incoming.revision) {
            EventOutcome.Ignored(IgnoreReason.StaleRevision)
        } else {
            replaceSnapshot(info.copy(background = incoming), now)
        }
    }

    /** Runs [apply] once the event's runtime is the one the stored snapshot names. */
    private inline fun scoped(runtime: SessionRuntimeIdentity?, apply: (SessionRuntimeIdentity) -> EventOutcome): EventOutcome = when {
        runtime == null -> EventOutcome.Ignored(IgnoreReason.MissingRuntime)
        !runsOn(runtime.workerInstanceId, runtime.runtimeGeneration) -> EventOutcome.Ignored(IgnoreReason.RuntimeMismatch)
        else -> apply(runtime)
    }
}
