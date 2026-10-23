package dev.pohunek.companion.sessions

import dev.pohunek.companion.core.data.layout.LayoutEdit
import dev.pohunek.companion.core.data.layout.LayoutRepository
import dev.pohunek.companion.core.data.layout.LayoutState
import dev.pohunek.companion.core.data.live.HostConnection
import dev.pohunek.companion.core.data.live.HostRegistry
import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.domain.hosts.ConfiguredHost
import dev.pohunek.companion.core.domain.hosts.hostDisplayLabel
import dev.pohunek.companion.core.domain.layout.Edit
import dev.pohunek.companion.core.domain.layout.GROUP_METADATA_KEY
import dev.pohunek.companion.core.domain.layout.GroupError
import dev.pohunek.companion.core.domain.layout.GroupFact
import dev.pohunek.companion.core.domain.layout.GroupNameCheck
import dev.pohunek.companion.core.domain.layout.INBOX_ID
import dev.pohunek.companion.core.domain.layout.INBOX_NAME
import dev.pohunek.companion.core.domain.layout.checkGroupName
import dev.pohunek.companion.core.domain.layout.groupFact
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.info
import dev.pohunek.companion.core.domain.log.warn
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.SessionInfo

/**
 * The owner's group edits and the migration, which write `companion.group` to the sessions and change the layout only once the daemon
 * holds the value. Everything runs on the caller's coroutine; [report] tells the owner about sessions a write left as they were.
 */
@Suppress("TooManyFunctions") // One cohesive set of group edits; they share the name limit, the hosts and the in-flight guard.
internal class GroupEditor(
    private val layouts: LayoutRepository,
    private val writes: GroupWrites,
    private val registry: HostRegistry,
    private val log: AppLog,
    private val report: (GroupWriteNote) -> Unit,
) {
    private var maxBytes: Int? = null
    private var hosts: List<ConfiguredHost> = emptyList()

    /** The groups a rename or delete is writing members of, so that a second edit of the same group is refused until the first ends. */
    private val groupsInFlight = HashSet<String>()

    /** The limit group names are checked against and the hosts a failure is named by; both come from the latest list inputs. */
    fun configure(groupNameMaxBytes: Int, configured: List<ConfiguredHost>) {
        maxBytes = groupNameMaxBytes
        hosts = configured
    }

    /**
     * Writes the group's name to the session and moves the row once the daemon holds it: the key is then the record and the local
     * flag stays clear. A session that cannot carry the key keeps a local placement and is marked not shared; a failed write moves
     * nothing and is reported. The value is false when the group or the session is gone.
     */
    suspend fun place(ref: RowRef, groupId: String): Boolean {
        val limit = maxBytes
        val group = layout()?.groups?.firstOrNull { it.id == groupId }
        val target = writeTarget(ref.route, ref.sessionId)
        if (limit == null || group == null || target == null) return false
        val outcome = writes.write(target, group.name, limit)
        if (outcome is WriteOutcome.Failed) {
            report(GroupWriteNote(1, 1, target.hostLabel, outcome.reason))
            return true
        }
        val local = outcome == WriteOutcome.Unwritable
        if (local) layouts.markNotShared(listOf(ref.layoutKey))
        return layouts.moveSession(ref.layoutKey, groupId, manual = local) is LayoutEdit.Applied
    }

    /** Appends an empty group; nothing is written, since membership is written when a session joins. */
    suspend fun createGroup(text: String): GroupNameError? {
        val limit = maxBytes
        return if (limit == null) GroupNameError.Gone else layouts.createGroup(text, limit).refusal(GroupError::toNameError)
    }

    /** The group is made here first, so that the session's own event, which names it, finds it; then the session moves into it. */
    suspend fun createAndPlace(ref: RowRef, text: String): GroupNameError? {
        val limit = maxBytes
        if (limit == null || writeTarget(ref.route, ref.sessionId) == null) return GroupNameError.Gone
        return when (val created = layouts.createGroup(text, limit)) {
            is LayoutEdit.Applied -> if (place(ref, created.value)) null else GroupNameError.Gone
            else -> created.refusal(GroupError::toNameError)
        }
    }

    /**
     * Writes the new name to every member session, then renames the group here. The group keeps its name when a write failed, so that
     * the members still show under it and repeating the rename writes only to the ones that still carry the old name; a member that
     * did get the new name shows under a learned group of that name until the rename merges it. The value is the refusal to show.
     */
    suspend fun rename(groupId: String, text: String): GroupNameError? {
        val limit = maxBytes
        val layout = layout()
        if (limit == null || layout == null) return GroupNameError.Gone
        val check = layout.renameGroup(groupId, text, limit)
        val name = (checkGroupName(text, limit) as? GroupNameCheck.Valid)?.name
        return when {
            check is Edit.Refused -> check.error.toNameError()
            check is Edit.Done && !check.value -> null
            name == null || !groupsInFlight.add(groupId) -> GroupNameError.Gone
            else -> renameWritten(groupId, text, name, limit)
        }
    }

    private suspend fun renameWritten(groupId: String, text: String, name: String, limit: Int): GroupNameError? = try {
        if (writeMembers(groupId, name, limit)) layouts.renameGroup(groupId, text, limit).refusal(GroupError::toNameError) else null
    } finally {
        groupsInFlight.remove(groupId)
    }

    /**
     * Writes Inbox to every member session, then deletes the group here; a failed write leaves the group and its remaining members as
     * they are. The value is false when the group is gone or cannot be deleted.
     */
    suspend fun delete(groupId: String): Boolean {
        val limit = maxBytes
        val deletable = groupId != INBOX_ID && layout()?.groups?.any { it.id == groupId } == true
        if (limit == null || !deletable || !groupsInFlight.add(groupId)) return false
        return try {
            !writeMembers(groupId, INBOX_NAME, limit) || layouts.deleteGroup(groupId) is LayoutEdit.Applied
        } finally {
            groupsInFlight.remove(groupId)
        }
    }

    /**
     * Writes [name] to the members of group [groupId], marks the ones that cannot carry it as not shared and reports the ones that
     * were left as they were. The value is true when every member that could be written was.
     */
    private suspend fun writeMembers(groupId: String, name: String, limit: Int): Boolean {
        val feeds = registry.state.value
        val facts = feeds.flatMap { (route, feed) -> feed.sessions.orEmpty().map { it.info.groupFact(route.toString(), limit) } }
        val targets = layouts.groupMembers(groupId, facts).mapNotNull { member ->
            feeds.keys.firstOrNull { it.toString() == member.key.host }?.let { writeTarget(it, member.key.sessionId) }
        }
        val summary = writes.writeAll(targets, name, limit)
        layouts.markNotShared(summary.unwritable)
        val note = summary.note()
        if (note != null) report(note)
        return note == null
    }

    /**
     * Writes the manual placements of [route] into session metadata. The host counts as migrated only when no write failed; a refused
     * session keeps its local placement and is marked not shared. [facts] may be older than the daemon's state, so a key that arrived
     * since is never overwritten.
     */
    suspend fun migrate(route: HostRoute, facts: List<GroupFact>) {
        val limit = maxBytes
        val plan = limit?.let { layouts.migrationPlan(route, facts, it) }
        if (limit == null || plan == null) return
        layouts.markNotShared(plan.unshared)
        val outcomes = plan.writes.filter { !currentInfo(route, it.key.sessionId).hasGroupKey() }.mapNotNull { write ->
            writeTarget(route, write.key.sessionId)?.let { target -> Triple(write.key, target, writes.write(target, write.name, limit)) }
        }
        layouts.markNotShared(outcomes.filter { it.third == WriteOutcome.Unwritable }.map { it.first })
        val failures = outcomes.filter { it.third is WriteOutcome.Failed }
        val failed = failures.size
        if (plan.writes.isNotEmpty()) {
            val counts = listOf(
                LogField.number("written", outcomes.count { it.third == WriteOutcome.Written }.toLong()),
                LogField.number("unwritable", outcomes.count { it.third == WriteOutcome.Unwritable }.toLong()),
                LogField.number("failed", failed.toLong()),
            )
            val fields = listOf(hostKeyField(route)) + counts
            if (failed == 0) log.info("layout.migration_run", fields) else log.warn("layout.migration_run", fields)
        }
        failures.firstOrNull()?.let { (_, target, outcome) ->
            report(GroupWriteNote(failed, outcomes.count { it.third != WriteOutcome.Unwritable }, target.hostLabel, (outcome as? WriteOutcome.Failed)?.reason))
        }
        if (failed == 0) layouts.markMigrated(route)
    }

    private fun layout() = (layouts.state.value as? LayoutState.Available)?.layout

    private fun currentInfo(route: HostRoute, sessionId: String): SessionInfo? =
        registry.state.value[route]?.sessions?.firstOrNull { it.info.id == sessionId }?.info

    /** The session as a write sees it, or null when its host or the session is gone from the lists. */
    private fun writeTarget(route: HostRoute, sessionId: String): WriteTarget? {
        val feed = registry.state.value[route]
        val info = currentInfo(route, sessionId)
        val label = hosts.firstOrNull { it.route == route }?.let(::hostDisplayLabel) ?: route.toString()
        return info?.let { WriteTarget(route, sessionId, label, feed?.connection == HostConnection.Connected, it.external == true) }
    }
}

private fun SessionInfo?.hasGroupKey(): Boolean = this?.metadata?.containsKey(GROUP_METADATA_KEY) == true
