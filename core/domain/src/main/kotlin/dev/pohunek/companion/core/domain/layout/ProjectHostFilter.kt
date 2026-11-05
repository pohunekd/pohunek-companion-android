// Ported to Kotlin from pohunek-companion `crates/core/src/filter.rs` (ProjectSelection, project_chips, HostEntry, host_options).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.OwnerState

/** Which project a view is narrowed to. */
sealed interface ProjectSelection {
    /** Every session. */
    data object All : ProjectSelection

    /** Sessions of the projects with this label on every host, matched case-sensitively as displayed. */
    data class Project(val label: String) : ProjectSelection

    /** Sessions that belong to no project. */
    data object NoProject : ProjectSelection

    /** Whether a session of [project] (null: none) passes. */
    fun admits(project: String?): Boolean = when (this) {
        All -> true
        is Project -> project == label
        NoProject -> project == null
    }

    /** The selection after a tap on [chip]: the chip's selection, or All when the chip is already selected. */
    fun tapped(chip: ProjectSelection): ProjectSelection = if (chip == this) All else chip
}

/** One chip of the project row; [label] is null for the All chip and the No project chip, whose texts the view owns. */
data class ProjectChip(val selection: ProjectSelection, val label: String?, val selected: Boolean)

/**
 * The project chips for sessions whose projects are [projects] (null: no project): All, one chip per label ordered by label ignoring
 * case (the same label on several hosts is one chip), then No project when some session has none. The selected project keeps its chip
 * after its sessions are gone.
 */
fun projectChips(projects: Collection<String?>, selected: ProjectSelection): List<ProjectChip> {
    val labels = projects.filterNotNull().toMutableSet()
    if (selected is ProjectSelection.Project) labels += selected.label
    val chips = mutableListOf(ProjectChip(ProjectSelection.All, null, selected == ProjectSelection.All))
    labels.sortedWith(compareBy<String>({ it.lowercase() }, { it })).mapTo(chips) {
        ProjectChip(ProjectSelection.Project(it), it, selected == ProjectSelection.Project(it))
    }
    if (projects.any { it == null } || selected == ProjectSelection.NoProject) {
        chips += ProjectChip(ProjectSelection.NoProject, null, selected == ProjectSelection.NoProject)
    }
    return chips
}

/** One row of the project picker; [label] is null for All projects and No project, whose texts the view owns. */
data class ProjectPickerRow(
    val selection: ProjectSelection,
    val label: String?,
    /** Sessions of the project after the state, host and search filters. */
    val count: Int,
    /** Whether one of those sessions needs the owner. */
    val needsYou: Boolean,
    val selected: Boolean,
)

/**
 * The picker rows: All projects, then the labels of [chips] in their order, then No project when [chips] has it. [narrowed] are the
 * sessions that pass the state, host and search filters. [pickerQuery] narrows the label rows by the session search's match; the
 * selected row, All projects and No project stay whatever it says.
 */
fun projectPickerRows(chips: List<ProjectChip>, narrowed: List<FilterFacts>, pickerQuery: String): List<ProjectPickerRow> {
    val query = SessionFilter(pickerQuery, StateSelection(OwnerState.entries.toSet()))
    return chips.mapNotNull { chip ->
        val inProject = narrowed.filter { chip.selection.admits(it.project) }
        val row = ProjectPickerRow(chip.selection, chip.label, inProject.size, inProject.any { it.ownerState == OwnerState.NeedsOwner }, chip.selected)
        row.takeIf { chip.label == null || chip.selected || query.matches(FilterFacts(OwnerState.Working, listOf(chip.label), emptyList(), null, "")) }
    }
}

/** Whether a followed host answers. */
enum class HostReach { Connected, Unreachable }

/** A followed host: [key] is the host part of its sessions' keys. */
data class HostEntry(val key: String, val label: String, val reach: HostReach)

/** One entry of the host selector; [key] and [label] are null for All hosts, whose text the view owns. */
data class HostOption(val key: String?, val label: String?, val reach: HostReach?, val selected: Boolean)

/**
 * The host selector entries: All hosts, then each followed host with its reach. Empty while fewer than two hosts are followed, so the
 * selector stays hidden. The selected host keeps its entry, marked unreachable, after it is no longer followed.
 */
fun hostOptions(hosts: List<HostEntry>, selected: String?): List<HostOption> {
    val gone = selected?.takeIf { key -> hosts.none { it.key == key } }
    if (hosts.size < 2 && gone == null) return emptyList()
    val options = mutableListOf(HostOption(null, null, null, selected == null))
    hosts.mapTo(options) { HostOption(it.key, it.label, it.reach, selected == it.key) }
    if (gone != null) options += HostOption(gone, gone, HostReach.Unreachable, true)
    return options
}

/** One row of the host sheet; [key] and [label] are null for All hosts, whose text the view owns, and [reach] is null for it too. */
data class HostPickerRow(val key: String?, val label: String?, val reach: HostReach?, val count: Int, val selected: Boolean)

/** The host sheet rows for [options]; [narrowed] are the sessions that pass the state, project and search filters, whatever their host. */
fun hostPickerRows(options: List<HostOption>, narrowed: List<FilterFacts>): List<HostPickerRow> =
    options.map { option ->
        HostPickerRow(option.key, option.label, option.reach, narrowed.count { option.key == null || it.host == option.key }, option.selected)
    }

/** The host selected after a tap on [key] (null: All hosts): that host, or every host when it is already selected. */
fun tappedHost(selected: String?, key: String?): String? = if (key == selected) null else key
