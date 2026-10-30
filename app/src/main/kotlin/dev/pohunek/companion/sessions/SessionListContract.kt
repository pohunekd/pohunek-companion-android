package dev.pohunek.companion.sessions

import dev.pohunek.companion.core.data.layout.LayoutIssue
import dev.pohunek.companion.core.data.live.HostConnection
import dev.pohunek.companion.core.domain.layout.FilterFacts
import dev.pohunek.companion.core.domain.layout.Group
import dev.pohunek.companion.core.domain.layout.GroupPlacement
import dev.pohunek.companion.core.domain.layout.GroupSection
import dev.pohunek.companion.core.domain.layout.HostOption
import dev.pohunek.companion.core.domain.layout.LayoutView
import dev.pohunek.companion.core.domain.layout.Placement
import dev.pohunek.companion.core.domain.layout.ProjectChip
import dev.pohunek.companion.core.domain.layout.ProjectSelection
import dev.pohunek.companion.core.domain.layout.RowFacts
import dev.pohunek.companion.core.domain.layout.StateSelection
import dev.pohunek.companion.core.domain.layout.WorkItem
import dev.pohunek.companion.core.domain.sessions.HostLink
import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.core.domain.sessions.ResumeOffer
import dev.pohunek.companion.core.domain.sessions.SessionAction
import dev.pohunek.companion.core.domain.sessions.SessionDetails
import dev.pohunek.companion.core.domain.sessions.SessionKey
import dev.pohunek.companion.protocol.HostRoute

/** One session as the list shows it; every text is untrusted metadata and is rendered as plain text only. */
data class SessionRow(
    val route: HostRoute,
    val sessionId: String,
    val title: String,
    val agent: String,
    val ownerState: OwnerState,
    /** The project label, or null for a session outside a project. */
    val project: String?,
    /** The Pohunek Work item the session is linked to, for the By Work item view; the badge above is its clipped text. */
    val workItem: WorkItem?,
    /** The Work link badge such as `GH PR #12`, cut to the configured length, and the session's Work role. */
    val badge: String?,
    val badgeRole: String?,
    /** True when the session has a Work link that may be opened and the host is connected. */
    val hasLink: Boolean,
    /** `<state>: <loss_reason>` of a conflict or incompatible runtime. */
    val runtimeDetail: String?,
    /** The host's label, else its route. */
    val hostLabel: String,
    /** Where the row's host stands; the facts of a host that is not connected are the last known ones. */
    val link: HostLink,
    /** Null when a terminal can be attached to the session. */
    val attachRefusal: AttachRefusal?,
    /** True when the daemon accepts a new name: the host is connected and the session is not external. */
    val canRename: Boolean,
    /** Stop and Remove are offered per the desktop's rules and only while the host is connected; an external session has neither. */
    val canStop: Boolean,
    val canRemove: Boolean,
    /** What the daemon's snapshot says about resuming the session; acting on it needs a connected host. */
    val resume: ResumeOffer,
    /** True when the group of this session is kept only on this phone: it is external, or the daemon cannot hold the key for it. */
    val notShared: Boolean,
) {
    val reachable: Boolean get() = link.reachable

    /** What the derived views group by. */
    val facts: RowFacts get() = RowFacts(ownerState, project, workItem)

    /** What the search matches: name, project, agent and the full Work badge as text, session id and Work id as identifiers. */
    val filterFacts: FilterFacts
        get() = FilterFacts(
            ownerState,
            listOfNotNull(title, project, agent, workItem?.title),
            listOfNotNull(sessionId, workItem?.id),
            project,
            layoutKey.host,
        )

    val ref: RowRef get() = RowRef(route, sessionId)

    val layoutKey: SessionKey get() = ref.layoutKey

    /** Row actions need the daemon, so a row of a host that is not connected offers none; Details and Move to group are always offered. */
    val hasActions: Boolean get() = reachable

    /** Unique across hosts and a plain string, so that the list can save its state by it. */
    val listKey: String get() = "$route|$sessionId"
}

/** A session on its host; two hosts can hold the same session id. */
data class RowRef(val route: HostRoute, val sessionId: String) {
    /** The session's identity in the layout. */
    val layoutKey: SessionKey get() = SessionKey(route.toString(), sessionId)
}

/** The details screen of one session; [details] is null once the session has left its host's list, [name] is then its last known name. */
data class DetailsView(val ref: RowRef, val name: String, val details: SessionDetails?)

/** A host whose connection is down, for the status lines above the list. */
data class HostStatus(val hostLabel: String, val connection: HostConnection.Reconnecting)

/**
 * The confirmation of a stop or remove, kept apart from the row so that it survives the session leaving the list. [action] turns from
 * [SessionAction.Remove] into [SessionAction.RemoveAnyway] when the daemon cannot confirm the runtime's processes ended; that consent
 * lives only in this dialog. [error] is shown in place; [notice] reports what a removal left behind and replaces the buttons by Close.
 */
data class ActionDialog(
    val ref: RowRef,
    val name: String,
    val hostLabel: String,
    val action: SessionAction,
    /** The runtime is in conflict or incompatible, so the daemon may refuse. */
    val runtimeProblem: Boolean,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

data class SessionListState(
    /** False until the hosts file has been read. */
    val hostsRead: Boolean = false,
    /** Set when the hosts file is unreadable; nothing is saved then. */
    val storageError: String? = null,
    /** True when at least one host is configured. */
    val hasHosts: Boolean = false,
    /** True with two or more hosts, when a message names the host its session lives on. */
    val multipleHosts: Boolean = false,
    /** Sessions that need the owner on a connected host, longest waiting first. */
    val needsYou: List<SessionRow> = emptyList(),
    /** The other sessions in list order, or null until a list is shown: no host has sent one, or the first one is still expected. */
    val rows: List<SessionRow>? = null,
    /** The view the list shows; the choice is kept with the layout. */
    val view: LayoutView = LayoutView.MY_GROUPS,
    /** [rows] sectioned by [view]: the owner's groups in My groups, else the derived sections; the rows of a collapsed section are listed but not shown. */
    val groups: List<GroupSection<SessionRow>> = emptyList(),
    /** The owner's groups in order, whatever [view] is: the targets of "Move to group". */
    val ownerGroups: List<Group> = emptyList(),
    /** The open search field's text, or null while the field is closed. The text is never stored. */
    val search: String? = null,
    /** The state chips shown, and the states of the shown view that pass. */
    val stateChips: List<OwnerState> = emptyList(),
    val states: StateSelection = StateSelection.DEFAULT,
    /** The project chips of every session and the project the shown view is narrowed to. */
    val projectChips: List<ProjectChip> = emptyList(),
    val project: ProjectSelection = ProjectSelection.All,
    /** The host selector entries, empty while it is hidden, and the key of the host the shown view is narrowed to. */
    val hostOptions: List<HostOption> = emptyList(),
    val host: String? = null,
    /** [needsYou] and [groups] after the state selection, project, host and search; the unfiltered lists stay the source of every row action. */
    val shownNeedsYou: List<SessionRow> = emptyList(),
    val shownGroups: List<GroupSection<SessionRow>> = emptyList(),
    /** True when the filter hides every session of a list that has some. */
    val noMatch: Boolean = false,
    /** Why the layout file is not, or not fully, saved; the list is shown regardless. */
    val layoutIssue: LayoutIssue? = null,
    /** One entry per host that is not connected, in host order. */
    val statuses: List<HostStatus> = emptyList(),
    /** True from a requested re-seed until every host finished it or failed. */
    val refreshing: Boolean = false,
    /** Why the last tapped session was not opened; shown until dismissed, refreshed, or another session opens. */
    val refusal: AttachRefusal? = null,
    /** The host a [AttachRefusal.HostUnreachable] refusal was about. */
    val refusalHost: HostRoute? = null,
    /** The session whose action sheet is open; the sheet closes when the session leaves the list. */
    val actionSheet: RowRef? = null,
    /** The open details screen, which follows the session live; null while the list is shown. */
    val details: DetailsView? = null,
    /** The open rename dialog; it closes with the session that leaves the list. */
    val rename: RenameDraft? = null,
    /** The open stop or remove confirmation. */
    val actionDialog: ActionDialog? = null,
    /** The sessions with a `session.resume` in flight; a second resume of one of them is ignored. */
    val resuming: Set<RowRef> = emptySet(),
    /** The last resume outcome, shown above the list until dismissed or replaced. */
    val resumeNote: ResumeNote? = null,
    /** The id of the owner group whose menu is open; the menu closes when the group is deleted. */
    val groupMenu: String? = null,
    /** The session whose "Move to group" list is open; it closes when the session leaves the list. */
    val movePicker: RowRef? = null,
    /** The open group name dialog. */
    val groupDraft: GroupDraft? = null,
    /** What the last group write left undone, shown above the list until dismissed or replaced. */
    val groupNote: GroupWriteNote? = null,
)

/**
 * Sessions that a group write did not reach: [failed] of [total], and the first failure's host and reason. [reason] is the daemon's
 * message and recovery hint, or null when that host was not connected. The text is composed in the view.
 */
data class GroupWriteNote(val failed: Int, val total: Int, val hostLabel: String, val reason: String?)

/** What the group name dialog will do with the name. */
sealed interface GroupDraftPurpose {
    /** Appends an empty group to My groups. */
    data object New : GroupDraftPurpose

    data class Rename(val groupId: String) : GroupDraftPurpose

    /** Appends a group and moves the session into it. */
    data class NewAndMove(val ref: RowRef) : GroupDraftPurpose
}

/** Why the group name dialog was refused; the texts are composed in the view. */
sealed interface GroupNameError {
    data object Empty : GroupNameError

    data object TooLong : GroupNameError

    data object ControlCharacter : GroupNameError

    data class Duplicate(val name: String) : GroupNameError

    /** The group or the session left the layout while the dialog was open. */
    data object Gone : GroupNameError
}

/** The group name dialog: the text being edited, the edit in flight and why the last one was refused. */
data class GroupDraft(
    val purpose: GroupDraftPurpose,
    val text: String,
    val busy: Boolean = false,
    val error: GroupNameError? = null,
)

/** What the last resume left to tell the owner; the texts are composed in the view. */
sealed interface ResumeNote {
    /** Resumed, but the new generation is not running yet, so the terminal is left to the owner. */
    data class Started(val name: String) : ResumeNote

    /** The request failed; [detail] is the daemon's message and recovery hint, or the transport failure. [hostLabel] is set with several hosts. */
    data class Failed(val sessionId: String, val hostLabel: String?, val detail: String) : ResumeNote

    /** A tapped ended session that the daemon cannot resume. */
    data class Refused(val sentence: String) : ResumeNote
}

/** The rename dialog: the text being edited, the request in flight and why the last one failed. */
data class RenameDraft(
    val ref: RowRef,
    val text: String,
    /** The host's label when the message must name it. */
    val hostLabel: String?,
    val busy: Boolean = false,
    /** The daemon's message and recovery hint, or the transport failure. */
    val error: String? = null,
)

sealed interface SessionListEvent {
    data object Refresh : SessionListEvent

    data class OpenSession(val ref: RowRef) : SessionListEvent

    data class OpenActions(val ref: RowRef) : SessionListEvent

    data object DismissActions : SessionListEvent

    data class OpenDetails(val ref: RowRef) : SessionListEvent

    data object CloseDetails : SessionListEvent

    /** The events of the rename dialog. */
    sealed interface RenameEvent : SessionListEvent

    data class OpenRename(val ref: RowRef) : RenameEvent

    data class EditRename(val text: String) : RenameEvent

    data object SubmitRename : RenameEvent

    data object DismissRename : RenameEvent

    /** The events of resuming an ended session. */
    sealed interface ResumeEvent : SessionListEvent

    /** Resumes the ended session; ignored while a resume of it is running. */
    data class Resume(val ref: RowRef) : ResumeEvent

    data object DismissResumeNote : ResumeEvent

    /** The events that take the owner from a row to something else: its link, or a new session beside it. */
    sealed interface LeaveEvent : SessionListEvent

    data class OpenLink(val ref: RowRef) : LeaveEvent

    /** Opens the new session form on the host, project and group of the row. */
    data class NewSessionHere(val ref: RowRef) : LeaveEvent

    /** The events of the stop and remove confirmation. */
    sealed interface ActionEvent : SessionListEvent

    /** Opens the confirmation of [action], which is [SessionAction.Stop] or [SessionAction.Remove]; nothing is sent yet. */
    data class RequestAction(val ref: RowRef, val action: SessionAction) : ActionEvent

    data object ConfirmAction : ActionEvent

    /** Closes the confirmation, whether it asked, failed or reported; ignored while the call runs. */
    data object DismissAction : ActionEvent

    /** No app handled the link intent. */
    data object LinkNotHandled : SessionListEvent

    data object DismissRefusal : SessionListEvent

    /** The events of the search field. */
    sealed interface SearchEvent : SessionListEvent

    data object OpenSearch : SearchEvent

    data class EditSearch(val text: String) : SearchEvent

    /** Clears the query and closes the search field. */
    data object CloseSearch : SearchEvent

    /** The events that change the owner's layout. */
    sealed interface LayoutEvent : SessionListEvent

    /** Collapses or expands a section of the shown view; the state is saved with the layout. */
    data class SetSectionCollapsed(val id: String, val collapsed: Boolean) : LayoutEvent

    /** Toggles [state] in the selection of the shown view; the choice is saved with the layout. */
    data class ToggleState(val state: OwnerState) : LayoutEvent

    /** Taps a project chip of the shown view: selects the project, or clears it when it is already selected. */
    data class TapProject(val chip: ProjectSelection) : LayoutEvent

    /** Taps a host of the shown view (null: All hosts): selects it, or shows every host when it is already selected. */
    data class TapHost(val key: String?) : LayoutEvent

    /** Shows [view]; the choice is saved with the layout. */
    data class SelectView(val view: LayoutView) : LayoutEvent

    /** The events of the group menu and the group name dialog. */
    sealed interface GroupEvent : LayoutEvent

    data class OpenGroupMenu(val id: String) : GroupEvent

    data object DismissGroupMenu : GroupEvent

    /** Opens the name dialog for a new group. */
    data object OpenNewGroup : GroupEvent

    data class OpenRenameGroup(val groupId: String) : GroupEvent

    /** Opens the name dialog for a new group that takes the session [ref]. */
    data class OpenNewGroupFor(val ref: RowRef) : GroupEvent

    data class EditGroupName(val text: String) : GroupEvent

    data object SubmitGroupName : GroupEvent

    /** Closes the name dialog; ignored while its edit runs. */
    data object DismissGroupName : GroupEvent

    /** Clears the message about sessions a group write did not reach. */
    data object DismissGroupNote : GroupEvent

    /** Deletes the group without a confirmation; its sessions go to the end of Inbox. */
    data class DeleteGroup(val id: String) : GroupEvent

    data class OpenMoveToGroup(val ref: RowRef) : LayoutEvent

    data object DismissMoveToGroup : LayoutEvent

    data class MoveToGroup(val ref: RowRef, val groupId: String) : LayoutEvent

    /** A session dropped into group [groupId] at [placement] by a drag. */
    data class DropSession(val ref: RowRef, val groupId: String, val placement: Placement) : LayoutEvent

    /** A group dropped at [placement] among the others by a drag. */
    data class DropGroup(val id: String, val placement: GroupPlacement) : LayoutEvent

    /** "Move up" or "Move down" of a session over its neighbour in the shown groups. */
    data class StepSession(val ref: RowRef, val direction: StepDirection) : LayoutEvent

    /** "Move up" or "Move down" of a group over its neighbour. */
    data class StepGroup(val id: String, val direction: StepDirection) : LayoutEvent
}

enum class StepDirection { Up, Down }

sealed interface SessionListEffect {
    data class OpenTerminal(val route: HostRoute, val sessionId: String) : SessionListEffect

    /** Hand [url], already checked, to the system; it opens in the browser. */
    data class OpenLink(val url: String) : SessionListEffect

    /** The session has no link that may be opened any more. */
    data object LinkUnavailable : SessionListEffect

    /** The new session form opens; each argument is null when nothing is preselected. [host] is the route as `HostRoute.toString` writes it. */
    data class OpenNewSession(val host: String, val project: String?, val group: String?) : SessionListEffect

    /** No host is configured: the owner adds the first one. */
    data object OpenHosts : SessionListEffect

    /** A move or delete found its group or session gone. */
    data object LayoutEditRefused : SessionListEffect
}
