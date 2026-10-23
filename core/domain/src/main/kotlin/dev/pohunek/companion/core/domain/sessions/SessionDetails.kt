// Ported to Kotlin from pohunek-companion `crates/core/src/detail.rs` (DetailSource, SessionDetails::build, copy_all).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionState
import java.time.format.DateTimeFormatter

/** One labelled, copyable value; [key] is a stable identifier of the field and [value] is the complete text that is copied. */
data class DetailField(val key: String, val label: String, val value: String)

/** A titled group of fields; never empty. */
data class DetailSection(val title: String, val fields: List<DetailField>)

/** The grouped fields of one session in display order. Labels and titles are English because they are also the text of "Copy all". */
data class SessionDetails(val sections: List<DetailSection>) {
    /** The session's [name] followed by one `Label: value` line per field, for pasting into an issue or a chat. */
    fun copyAll(name: String): String = buildString {
        append(sanitizeText(name))
        sections.flatMap { it.fields }.forEach { append('\n').append(it.label).append(": ").append(it.value) }
    }
}

/**
 * The details of [session] on the host shown as [hostLabel] at [hostRoute]. A field without a value is left out, and every value is
 * plain text without control or bidi-format characters. [groupNotShared] adds the line that says the session's group exists only on
 * this phone.
 */
fun sessionDetails(session: OwnerSession, hostLabel: String, hostRoute: HostRoute, groupNotShared: Boolean = false): SessionDetails {
    val info = session.info
    val sections = listOf(
        DetailSection("Identity", identityFields(info, hostLabel, hostRoute, groupNotShared)),
        DetailSection("Agent", agentFields(session)),
        DetailSection("Location", locationFields(info)),
        DetailSection("Work", workFields(info)),
    )
    return SessionDetails(sections.filter { it.fields.isNotEmpty() })
}

private class FieldList {
    val fields = mutableListOf<DetailField>()

    fun add(key: String, label: String, value: String?) {
        if (value != null && value.isNotBlank()) fields += DetailField(key, label, value)
    }
}

private fun fields(build: FieldList.() -> Unit): List<DetailField> = FieldList().apply(build).fields

private fun identityFields(info: SessionInfo, hostLabel: String, hostRoute: HostRoute, groupNotShared: Boolean) = fields {
    val route = sanitizeText(hostRoute.toString())
    add("session_id", "Session id", sanitizeText(info.id))
    add("host", "Host", hostLabel)
    add("host_route", "Host route", route.takeIf { it != hostLabel })
    add("group", "Group", if (groupNotShared) GROUP_NOT_SHARED_TEXT else null)
}

private fun agentFields(session: OwnerSession) = fields {
    val info = session.info
    val state = SessionState.serializer().descriptor.getElementName(info.state.ordinal)
    val detail = runtimeDetail(info)
    add("agent", "Agent", sanitizeText(info.agent))
    add("state", "State", if (detail == null) state else "$state ($detail)")
    add("waiting_since", "Waiting since", session.waitingSince?.let(DateTimeFormatter.ISO_OFFSET_DATE_TIME::format))
    add("resume", "Resume", resumeText(resumeOffer(info)))
    add("native_session_id", "Native session id", info.nativeSessionId?.let(::sanitizeText))
    add("native_session_path", "Native session path", info.nativeSessionPath?.let(::plainText))
}

private fun resumeText(offer: ResumeOffer): String? = when (offer) {
    ResumeOffer.NotApplicable -> null
    ResumeOffer.Available -> "Available"
    is ResumeOffer.Refused -> offer.sentence
}

private fun locationFields(info: SessionInfo) = fields {
    val project = sessionProjectLabel(info)
    add("cwd", "Working directory", plainText(info.cwd))
    add("worktree", "Worktree", info.worktreePath?.takeIf { it != info.cwd }?.let(::plainText))
    add("repo", "Repository", info.repo?.let(::plainText))
    add("branch", "Branch", info.branch?.let(::sanitizeText))
    add("project", "Project", project)
    add("project_id", "Project id", info.projectId?.let(::sanitizeText)?.takeIf { it != project })
}

private fun workFields(info: SessionInfo) = fields {
    val metadata = info.metadata.orEmpty()
    val facts = workFacts(metadata)
    add("work_provider", "Provider", facts?.provider)
    add("work_kind", "Kind", facts?.kind)
    add("work_id", "Id", facts?.id)
    add("work_role", "Role", facts?.role)
    add("work_link", "Link", openableWorkLink(metadata))
}

/** The value of the details line for a session whose group is kept only on this phone. */
const val GROUP_NOT_SHARED_TEXT = "only on this phone"

/** Drops control and bidi-format characters but keeps every space, which is significant in paths. */
fun plainText(value: String): String = value.filterNot { it.isISOControl() || isFormatChar(it) }
