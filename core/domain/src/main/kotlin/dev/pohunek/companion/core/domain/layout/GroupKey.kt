package dev.pohunek.companion.core.domain.layout

import dev.pohunek.companion.core.domain.sessions.SessionKey
import dev.pohunek.companion.core.domain.sessions.isFormatChar
import dev.pohunek.companion.protocol.SessionInfo

/** The session metadata key that holds a session's group name, shared with the desktop companion. */
const val GROUP_METADATA_KEY = "companion.group"

/** Why a group name is not usable as the value of [GROUP_METADATA_KEY]. */
enum class GroupNameProblem(val code: String) {
    Empty("empty"),
    TooLong("too_long"),
    ControlCharacter("control_character"),
}

/** The outcome of checking a group name against the rules every client applies when it reads or writes the key. */
sealed interface GroupNameCheck {
    /** [name] is trimmed and fit to store or to show. */
    data class Valid(val name: String) : GroupNameCheck

    data class Invalid(val problem: GroupNameProblem) : GroupNameCheck
}

/**
 * Trims [raw] and refuses it when it is empty, longer than [maxBytes] UTF-8 bytes, or holds a control or bidirectional-format character.
 * Reading a value and writing a name use the same rules.
 */
fun checkGroupName(raw: String, maxBytes: Int): GroupNameCheck {
    val name = raw.trim()
    return when {
        name.isEmpty() -> GroupNameCheck.Invalid(GroupNameProblem.Empty)
        name.any { it.isISOControl() || isFormatChar(it) } -> GroupNameCheck.Invalid(GroupNameProblem.ControlCharacter)
        name.toByteArray(Charsets.UTF_8).size > maxBytes -> GroupNameCheck.Invalid(GroupNameProblem.TooLong)
        else -> GroupNameCheck.Valid(name)
    }
}

/** What a session's metadata says about its group. */
sealed interface GroupValue {
    /** The key is missing. */
    data object Absent : GroupValue

    /** The key holds a usable [name]. */
    data class Present(val name: String) : GroupValue

    /** The key holds a value that is treated as absent; the value itself is never kept, so it cannot reach a log. */
    data class Rejected(val problem: GroupNameProblem) : GroupValue
}

/** Reads [GROUP_METADATA_KEY] from a session's [metadata]. */
fun parseGroupValue(metadata: Map<String, String>?, maxBytes: Int): GroupValue {
    val raw = metadata?.get(GROUP_METADATA_KEY) ?: return GroupValue.Absent
    return when (val check = checkGroupName(raw, maxBytes)) {
        is GroupNameCheck.Valid -> GroupValue.Present(check.name)
        is GroupNameCheck.Invalid -> GroupValue.Rejected(check.problem)
    }
}

/** What [this] session of the host with key [host] says about its group. */
fun SessionInfo.groupFact(host: String, maxBytes: Int): GroupFact =
    GroupFact(SessionKey(host, id), parseGroupValue(metadata, maxBytes), external == true)
