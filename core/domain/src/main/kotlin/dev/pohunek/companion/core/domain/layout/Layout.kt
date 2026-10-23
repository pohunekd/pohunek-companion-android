// Ported to Kotlin from pohunek-companion `crates/core/src/layout.rs`.
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.SessionKey
import dev.pohunek.companion.core.domain.sessions.sanitizeText

/**
 * The owner's client-side layout: groups of sessions in a manual order. Immutable; every edit returns the new layout, so a refused
 * edit leaves the receiver as it was. Only group membership is shared, through the `companion.group` session metadata key; writing it
 * and persistence are the caller's.
 */
@ConsistentCopyVisibility
@Suppress("TooManyFunctions") // One cohesive set of edits; splitting it would scatter the invariants they share.
data class Layout private constructor(
    /** Id of the selected view as stored; an unknown value survives until the owner picks a view. */
    val storedView: String?,
    /** Filter of each view by view id; a view without an entry uses the default filter. */
    val filters: Map<String, StoredFilter>,
    /** Ids of the collapsed sections of each derived view by view id. My groups keeps its state on the groups themselves. */
    val collapsedSections: Map<String, Set<String>>,
    val groups: List<Group>,
    val items: List<Item>,
    /** Host keys (routes) whose manual placements have been written into session metadata, once each; see [migrationPlan]. */
    val migratedHosts: List<String>,
) {
    /** The selected view; My groups when none is stored or the stored id is unknown. */
    val view: LayoutView get() = storedView?.let(LayoutView::parse) ?: LayoutView.MY_GROUPS

    /** The stored filter of [view]; the default filter when none is stored. */
    fun filter(view: LayoutView): StoredFilter = filters[view.id] ?: StoredFilter()

    /** Selects the view. */
    fun withView(view: LayoutView): Layout = copy(storedView = view.id)

    /** Stores the filter of [view]; the default filter is stored as no entry. */
    fun withFilter(view: LayoutView, filter: StoredFilter): Layout =
        copy(filters = if (filter == StoredFilter()) filters - view.id else filters + (view.id to filter))

    /** The owner states [view] shows. */
    fun states(view: LayoutView): StateSelection = StateSelection.fromStored(filter(view).states)

    /** Stores the state selection of [view] and keeps the rest of its filter. */
    fun withStates(view: LayoutView, selection: StateSelection): Layout = withFilter(view, filter(view).copy(states = selection.storedKeys()))

    /** The project [view] is narrowed to; a project label wins over the No project flag. */
    fun project(view: LayoutView): ProjectSelection = filter(view).let { stored ->
        when {
            stored.projectLabel != null -> ProjectSelection.Project(stored.projectLabel)
            stored.noProject -> ProjectSelection.NoProject
            else -> ProjectSelection.All
        }
    }

    /** Stores the project selection of [view] and keeps the rest of its filter. */
    fun withProject(view: LayoutView, selection: ProjectSelection): Layout = withFilter(
        view,
        filter(view).copy(
            projectLabel = (selection as? ProjectSelection.Project)?.label,
            noProject = selection == ProjectSelection.NoProject,
        ),
    )

    /** The key of the host [view] is narrowed to, or null for every host. */
    fun host(view: LayoutView): String? = filter(view).host?.ifEmpty { null }

    /** Stores the host of [view] (null: every host) and keeps the rest of its filter. */
    fun withHost(view: LayoutView, host: String?): Layout = withFilter(view, filter(view).copy(host = host))

    /** Groups in display order. */
    fun orderedGroups(): List<Group> = groups.sortedWith(compareBy<Group>({ it.order }, { it.id }))

    /** Items of [group] in display order. */
    fun groupItems(group: String): List<Item> =
        items.filter { it.group == group }.sortedWith(compareBy<Item>({ it.order }, { it.key.host }, { it.key.sessionId }))

    /**
     * Appends sessions the layout does not know to the end of Inbox, in list order, and drops placements of [host]'s sessions missing
     * from [current], the full seeded list of that host. Items of other hosts are never pruned, so a host that is disconnected or not
     * yet seeded keeps its placements.
     */
    fun reconcile(host: String, current: List<SessionKey>): Reconciliation {
        val listed = current.toSet()
        val (kept, dropped) = items.partition { it.key.host != host || it.key in listed }
        val known = kept.mapTo(HashSet()) { it.key }
        var last = kept.filter { it.group == INBOX_ID }.maxOfOrNull { it.order }
        val added = mutableListOf<Item>()
        for (key in current) {
            if (!known.add(key)) continue
            val order = SortKey.between(last, null)
            last = order
            added += Item(key, INBOX_ID, order)
        }
        return Reconciliation(copy(items = kept + added), added.map { it.key }, dropped.map { it.key })
    }

    /**
     * Moves [key] into [group] at [placement]. Only that session's item changes: its group, one new sort key and its manual flag, which
     * is set to [manual]: true while the owner's local move is what places the session, false once its `companion.group` key is. The
     * value is whether anything changed. A drop where the session already is, or on itself, keeps its position and changes the flag only.
     */
    fun moveSession(key: SessionKey, group: String, placement: Placement, manual: Boolean): Edit<Boolean, MoveError> {
        val index = items.indexOfFirst { it.key == key }
        return when {
            groups.none { it.id == group } -> Edit.Refused(MoveError.UnknownGroup(group))
            index < 0 -> Edit.Refused(MoveError.UnknownSession(key.sessionId))
            else -> placeItem(index, group, placement, manual)
        }
    }

    private fun placeItem(index: Int, group: String, placement: Placement, manual: Boolean): Edit<Boolean, MoveError> {
        val item = items[index]
        val anchor = placement.anchorOrNull()
        if (anchor == item.key) return flag(index, manual)
        val neighbours = groupItems(group).filter { it.key != item.key }
        val slot = if (anchor == null) {
            neighbours.size
        } else {
            neighbours.indexOfFirst { it.key == anchor }.takeIf { it >= 0 }?.plus(if (placement is Placement.After) 1 else 0)
                ?: return Edit.Refused(MoveError.AnchorNotInGroup(anchor.sessionId))
        }
        val lower = neighbours.getOrNull(slot - 1)?.order
        val upper = neighbours.getOrNull(slot)?.order
        val stays = item.group == group && (lower == null || lower < item.order) && (upper == null || item.order < upper)
        return when {
            stays -> flag(index, manual)
            lower != null && upper != null && lower >= upper -> Edit.Refused(MoveError.AmbiguousOrder(group))
            else -> Edit.Done(
                copy(items = items.replaced(index, item.copy(group = group, order = SortKey.between(lower, upper), manual = manual))),
                true,
            )
        }
    }

    /** Sets the item's manual flag; the value is whether it changed. */
    private fun flag(index: Int, manual: Boolean): Edit<Boolean, MoveError> {
        val item = items[index]
        return if (item.manual == manual) Edit.Done(this, false) else Edit.Done(copy(items = items.replaced(index, item.copy(manual = manual))), true)
    }

    /** Appends a group after the last one; the value is its id (`g<n>`). The name must be fit to be written as a `companion.group` value. */
    fun createGroup(name: String, maxBytes: Int): Edit<String, GroupError> {
        val clean = when (val check = checkGroupName(name, maxBytes)) {
            is GroupNameCheck.Invalid -> return Edit.Refused(check.problem.toError())
            is GroupNameCheck.Valid -> check.name
        }
        if (nameTaken(clean, except = null)) return Edit.Refused(GroupError.DuplicateName(clean))
        val group = groups.appended(clean, GroupOrigin.Created)
        return Edit.Done(copy(groups = groups + group), group.id)
    }

    /**
     * Renames a group; Inbox keeps its name. A created group of that name refuses the rename; a learned one, which only mirrors what
     * other clients wrote, is merged: its sessions join the end of the renamed group and it goes. The value is whether anything changed.
     */
    fun renameGroup(id: String, name: String, maxBytes: Int): Edit<Boolean, GroupError> {
        val index = groups.indexOfFirst { it.id == id }
        val check = checkGroupName(name, maxBytes)
        val clean = (check as? GroupNameCheck.Valid)?.name
        return when {
            index < 0 -> Edit.Refused(GroupError.UnknownGroup(id))
            id == INBOX_ID -> Edit.Refused(GroupError.InboxFixed)
            check is GroupNameCheck.Invalid -> Edit.Refused(check.problem.toError())
            clean == null -> Edit.Refused(GroupError.EmptyName)
            nameTaken(clean, except = id, ignoreLearned = true) -> Edit.Refused(GroupError.DuplicateName(clean))
            else -> renamed(index, clean).let { next -> Edit.Done(next, next != this) }
        }
    }

    private fun renamed(index: Int, name: String): Layout {
        val group = groups[index]
        val learned = groups.firstOrNull { it.id != group.id && it.origin == GroupOrigin.Learned && it.name.matchKey() == name.matchKey() }
        val renamed = copy(groups = groups.replaced(index, group.copy(name = name)))
        return if (learned == null) renamed else renamed.absorbed(learned.id, group.id)
    }

    /** Moves the sessions of group [from] to the end of group [into], in their order, and drops [from]. */
    private fun absorbed(from: String, into: String): Layout {
        var last = items.filter { it.group == into }.maxOfOrNull { it.order }
        val relocated = groupItems(from).associate { item ->
            val order = SortKey.between(last, null)
            last = order
            item.key to item.copy(group = into, order = order)
        }
        return copy(groups = groups.filter { it.id != from }, items = items.map { relocated[it.key] ?: it })
    }

    /**
     * Collapses or expands a section of the selected view: an owner group in My groups, a section id in a derived view. The value is
     * whether the collapsed state changed.
     */
    fun setCollapsed(id: String, collapsed: Boolean): Edit<Boolean, GroupError> {
        val current = view
        if (!current.isEditable) {
            val ids = collapsedSections[current.id].orEmpty()
            val next = if (collapsed) ids + id else ids - id
            val sections = if (next.isEmpty()) collapsedSections - current.id else collapsedSections + (current.id to next)
            return Edit.Done(copy(collapsedSections = sections), next != ids)
        }
        return setGroupCollapsed(id, collapsed)
    }

    /** Collapses or expands an owner group whatever the selected view is. The value is whether the collapsed state changed. */
    fun setGroupCollapsed(id: String, collapsed: Boolean): Edit<Boolean, GroupError> {
        val index = groups.indexOfFirst { it.id == id }
        if (index < 0) return Edit.Refused(GroupError.UnknownGroup(id))
        val group = groups[index]
        return Edit.Done(copy(groups = groups.replaced(index, group.copy(collapsed = collapsed))), group.collapsed != collapsed)
    }

    /** Moves a group before or after another; only that group's sort key changes. The value is whether anything changed. */
    fun moveGroup(id: String, placement: GroupPlacement): Edit<Boolean, GroupError> {
        val index = groups.indexOfFirst { it.id == id }
        val anchor = placement.anchorId
        return when {
            index < 0 -> Edit.Refused(GroupError.UnknownGroup(id))
            groups.none { it.id == anchor } -> Edit.Refused(GroupError.UnknownGroup(anchor))
            anchor == id -> Edit.Done(this, false)
            else -> placeGroup(index, placement)
        }
    }

    private fun placeGroup(index: Int, placement: GroupPlacement): Edit<Boolean, GroupError> {
        val group = groups[index]
        val others = orderedGroups().filter { it.id != group.id }
        val slot = others.indexOfFirst { it.id == placement.anchorId } + if (placement is GroupPlacement.After) 1 else 0
        val lower = others.getOrNull(slot - 1)?.order
        val upper = others.getOrNull(slot)?.order
        return when {
            (lower == null || lower < group.order) && (upper == null || group.order < upper) -> Edit.Done(this, false)
            lower != null && upper != null && lower >= upper -> Edit.Refused(GroupError.AmbiguousOrder)
            else -> Edit.Done(copy(groups = groups.replaced(index, group.copy(order = SortKey.between(lower, upper)))), true)
        }
    }

    /** Deletes a group and appends its sessions to the end of Inbox in their display order, keeping their manual flag. The value is the moved sessions. */
    fun deleteGroup(id: String): Edit<List<SessionKey>, GroupError> {
        if (groups.none { it.id == id }) return Edit.Refused(GroupError.UnknownGroup(id))
        if (id == INBOX_ID) return Edit.Refused(GroupError.InboxFixed)
        val moving = groupItems(id)
        var last = items.filter { it.group == INBOX_ID }.maxOfOrNull { it.order }
        val relocated = moving.associate { item ->
            val order = SortKey.between(last, null)
            last = order
            item.key to item.copy(group = INBOX_ID, order = order)
        }
        val next = items.map { relocated[it.key] ?: it }
        return Edit.Done(copy(groups = groups.filter { it.id != id }, items = next), moving.map { it.key })
    }

    /** Groups in order, each with the rows of its items in order. A row without a placement is left out until the next reconcile. */
    fun <R> arrange(rows: List<R>, keyOf: (R) -> SessionKey): List<GroupSection<R>> {
        val byKey = rows.associateBy(keyOf)
        return orderedGroups().map { group ->
            GroupSection(group.id, group.name, group.collapsed, groupItems(group.id).mapNotNull { byKey[it.key] })
        }
    }

    /**
     * [arrange], with the rows that have no placement yet at the end of Inbox in list order, which is where the next reconcile puts them,
     * so a list that arrives before its reconcile is shown whole.
     */
    fun <R> present(rows: List<R>, keyOf: (R) -> SessionKey): List<GroupSection<R>> {
        val placed = items.mapTo(HashSet()) { it.key }
        val unplaced = rows.filter { keyOf(it) !in placed }
        val sections = arrange(rows, keyOf)
        return if (unplaced.isEmpty()) sections else sections.map { if (it.id == INBOX_ID) it.copy(rows = it.rows + unplaced) else it }
    }

    /**
     * The sections of the selected view for [rows], which exclude the sessions that need the owner on a connected host (the list lifts those
     * out for every view). A derived view's sections are collapsed per its stored ids; titles are clipped to [titleMaxChars].
     */
    fun <R> presentView(rows: List<R>, keyOf: (R) -> SessionKey, titleMaxChars: Int, factsOf: (R) -> RowFacts): List<GroupSection<R>> {
        val current = view
        val sections = deriveSections(current, present(rows, keyOf), titleMaxChars, factsOf)
        if (current.isEditable) return sections
        val collapsed = collapsedSections[current.id].orEmpty()
        return sections.map { it.copy(collapsed = it.id in collapsed) }
    }

    /**
     * Drops the stored collapsed ids of the derived views that no section of [rows] has, so the file stays bounded by the live
     * sections. Without rows nothing is dropped, so a host that is not connected keeps the state.
     */
    fun <R> pruneCollapsedSections(rows: List<R>, keyOf: (R) -> SessionKey, titleMaxChars: Int, factsOf: (R) -> RowFacts): Layout {
        if (rows.isEmpty() || collapsedSections.isEmpty()) return this
        val arranged = present(rows, keyOf)
        val pruned = collapsedSections.mapValues { (viewId, ids) ->
            val derived = LayoutView.parse(viewId)?.takeUnless { it.isEditable } ?: return@mapValues ids
            val live = deriveSections(derived, arranged, titleMaxChars, factsOf).mapTo(HashSet()) { it.id }
            ids.filterTo(HashSet()) { it in live }
        }.filterValues { it.isNotEmpty() }
        return if (pruned == collapsedSections) this else copy(collapsedSections = pruned)
    }

    /**
     * Places the sessions of [facts] by their `companion.group` value. A present value puts the session in the group of that name,
     * compared without case (Inbox for `Inbox`), appends a learned group when the phone has none, and clears the item's manual flag, since
     * the key is now the record. A session without a usable value that sits in a group because of an earlier key goes back to the end of
     * Inbox; one the owner moved locally stays. A session arriving in a group goes to that group's end. Learned groups left without a
     * member are dropped only when [allHostsListed], so a host that has not sent its list cannot make its groups look empty. Facts of
     * sessions the layout does not hold are ignored.
     */
    fun applyGroupValues(facts: List<GroupFact>, allHostsListed: Boolean): GroupSync {
        val sync = GroupValueSync(groups, items)
        facts.forEach(sync::place)
        val dropped = if (allHostsListed) sync.dropEmptyLearnedGroups() else 0
        val next = if (sync.groups == groups && sync.items == items) this else copy(groups = sync.groups, items = sync.items)
        return GroupSync(next, sync.moved, sync.added, dropped)
    }

    private fun nameTaken(name: String, except: String?, ignoreLearned: Boolean = false): Boolean {
        val folded = name.lowercase()
        return groups.any { it.id != except && (!ignoreLearned || it.origin != GroupOrigin.Learned) && it.name.lowercase() == folded }
    }

    /**
     * The `companion.group` writes that move the manual placements of [host] into session metadata, none once the host is migrated. A
     * candidate is a manual item outside Inbox whose session [facts] list, is not external and has no key at all (a value other clients
     * would ignore still counts as a key, and is never overwritten). A group name that cannot be written as a value leaves its sessions
     * local, listed in [GroupMigration.unshared] together with the manual placements of external sessions.
     */
    fun migrationPlan(host: String, facts: List<GroupFact>, maxBytes: Int): GroupMigration {
        if (host in migratedHosts) return GroupMigration(emptyList(), emptyList())
        val writable = facts.filter { !it.external && it.value == GroupValue.Absent }.mapTo(HashSet()) { it.key }
        val names = groups.associate { it.id to it.name }
        val checked = items.filter { it.key.host == host && it.manual && it.group != INBOX_ID && it.key in writable }.mapNotNull { item ->
            names[item.group]?.let { item.key to checkGroupName(it, maxBytes) }
        }
        val external = facts.filter { it.external }.mapTo(HashSet()) { it.key }
        val localOnly = items.filter { it.key.host == host && it.manual && it.group != INBOX_ID && it.key in external }.map { it.key }
        return GroupMigration(
            writes = checked.mapNotNull { (key, check) -> (check as? GroupNameCheck.Valid)?.let { GroupWrite(key, it.name) } },
            unshared = checked.filter { it.second is GroupNameCheck.Invalid }.map { it.first } + localOnly,
        )
    }

    /** Records [host] as migrated, so [migrationPlan] gives nothing for it from now on. */
    fun withMigrated(host: String): Layout = if (host in migratedHosts) this else copy(migratedHosts = migratedHosts + host)

    /**
     * The sessions a rename or delete of group [groupId] has to write: those whose key names the group (compared as the list does) and
     * the ones the group holds locally without a usable key, in the group's display order and then the order of [facts]. Inbox and unknown groups have none.
     */
    fun groupMembers(groupId: String, facts: List<GroupFact>): List<GroupMember> {
        val group = groups.firstOrNull { it.id == groupId }?.takeIf { it.id != INBOX_ID } ?: return emptyList()
        val folded = group.name.matchKey()
        val position = groupItems(groupId).withIndex().associate { it.value.key to it.index }
        return facts.filter { fact ->
            when (val value = fact.value) {
                is GroupValue.Present -> value.name.matchKey() == folded
                else -> fact.key in position
            }
        }.sortedBy { position[it.key] ?: Int.MAX_VALUE }.map { GroupMember(it.key, it.external) }
    }

    companion object {
        /** The stored format version this build reads. */
        const val FORMAT_VERSION = 2

        /** The oldest stored version this build still reads; it has no [migratedHosts], so every host reads as unmigrated. */
        private const val OLDEST_VERSION = 1

        /** Inbox only. */
        fun default(): Layout = Layout(null, emptyMap(), emptyMap(), listOf(inboxGroup(SortKey.between(null, null))), emptyList(), emptyList())

        /**
         * Builds a layout from stored data. Rejects a wrong version, an empty or repeated group id, an invalid sort key, an item in an
         * unknown group and a session listed twice; re-adds a missing Inbox before the first group and the Problem state to a stored
         * state selection. An unknown view id is kept and selects My groups.
         */
        fun load(
            version: Int,
            view: String?,
            filters: Map<String, StoredFilter>,
            collapsedSections: Map<String, Set<String>>,
            groups: List<Group>,
            items: List<Item>,
            migratedHosts: List<String>,
        ): LayoutLoad {
            val problem = validate(version, groups, items)
            if (problem != null) return LayoutLoad.Invalid(problem)
            val repaired = groups.none { it.id == INBOX_ID }
            val all = if (repaired) listOf(inboxGroup(SortKey.between(null, groups.minOfOrNull { it.order }))) + groups else groups
            val layout = Layout(
                storedView = view,
                filters = filters.mapValues { it.value.withProblemState() },
                collapsedSections = collapsedSections.mapValues { it.value.toSet() },
                groups = all.toList(),
                items = items.toList(),
                migratedHosts = migratedHosts.distinct(),
            )
            return LayoutLoad.Loaded(layout, repaired)
        }

        private fun validate(version: Int, groups: List<Group>, items: List<Item>): String? {
            if (version !in OLDEST_VERSION..FORMAT_VERSION) {
                return "unsupported format version $version (this build reads versions $OLDEST_VERSION to $FORMAT_VERSION)"
            }
            val ids = HashSet<String>()
            val sessions = HashSet<SessionKey>()
            return groups.firstNotNullOfOrNull { groupProblem(it, ids) } ?: items.firstNotNullOfOrNull { itemProblem(it, ids, sessions) }
        }

        private fun groupProblem(group: Group, ids: MutableSet<String>): String? = when {
            group.id.isEmpty() || !ids.add(group.id) -> "group id \"${group.id}\" is empty or repeated"
            !SortKey.isValid(group.order) -> "group \"${group.id}\" has an invalid order key \"${group.order}\""
            else -> null
        }

        private fun itemProblem(item: Item, groupIds: Set<String>, sessions: MutableSet<SessionKey>): String? {
            val id = item.key.sessionId
            return when {
                item.group !in groupIds -> "session \"$id\" is in unknown group \"${item.group}\""
                !SortKey.isValid(item.order) -> "session \"$id\" has an invalid order key \"${item.order}\""
                !sessions.add(item.key) -> "session \"$id\" is listed twice"
                else -> null
            }
        }

        private fun StoredFilter.withProblemState() =
            copy(states = states?.let { StateSelection.fromStored(it).withRuntimeProblem().storedKeys() })

        private fun inboxGroup(order: String) = Group(INBOX_ID, INBOX_NAME, order)
    }
}

private fun Placement.anchorOrNull(): SessionKey? = when (this) {
    is Placement.Before -> anchor
    is Placement.After -> anchor
    Placement.End -> null
}

private fun GroupNameProblem.toError(): GroupError = when (this) {
    GroupNameProblem.Empty -> GroupError.EmptyName
    GroupNameProblem.TooLong -> GroupError.NameTooLong
    GroupNameProblem.ControlCharacter -> GroupError.NameHasControlCharacter
}

/** The working copy [Layout.applyGroupValues] edits; [moved] and [added] count what changed. */
private class GroupValueSync(var groups: List<Group>, var items: List<Item>) {
    var moved = 0
    var added = 0

    fun place(fact: GroupFact) {
        val index = items.indexOfFirst { it.key == fact.key }
        if (index < 0) return
        val value = fact.value
        if (value is GroupValue.Present) {
            val name = sanitizeText(value.name)
            val target = groups.firstOrNull { it.name.matchKey() == name.matchKey() } ?: groups.appended(name, GroupOrigin.Learned).also {
                groups = groups + it
                added++
            }
            relocate(index, target.id)
        } else if (items[index].group != INBOX_ID && !items[index].manual) {
            relocate(index, INBOX_ID)
        }
    }

    /** Moves the item to the end of [target] unless it is there; either way the key is now the record, so the manual flag goes. */
    private fun relocate(index: Int, target: String) {
        val item = items[index]
        if (item.group == target) {
            if (item.manual) items = items.replaced(index, item.copy(manual = false))
            return
        }
        val last = items.filter { it.group == target }.maxOfOrNull { it.order }
        items = items.replaced(index, item.copy(group = target, order = SortKey.between(last, null), manual = false))
        moved++
    }

    /** Drops the learned groups no item is in and returns how many went. */
    fun dropEmptyLearnedGroups(): Int {
        val inUse = items.mapTo(HashSet()) { it.group }
        val kept = groups.filter { it.origin != GroupOrigin.Learned || it.id in inUse }
        return (groups.size - kept.size).also { groups = kept }
    }
}

/** What group names are compared by: as shown (whitespace collapsed), ignoring case. */
private fun String.matchKey(): String = sanitizeText(this).lowercase()

/** A group named [name] after the last of the receiver, with the first free id `g<n>`. */
private fun List<Group>.appended(name: String, origin: GroupOrigin): Group {
    val id = generateSequence(size) { it + 1 }.map { "g$it" }.first { candidate -> none { it.id == candidate } }
    val last = maxWithOrNull(compareBy<Group>({ it.order }, { it.id }))
    return Group(id, name, SortKey.between(last?.order, null), origin = origin)
}

private fun <T> List<T>.replaced(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }
