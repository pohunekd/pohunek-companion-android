package dev.pohunek.companion.sessions

import dev.pohunek.companion.core.data.layout.LayoutEdit
import dev.pohunek.companion.core.domain.layout.GroupError

/** The refusal of an edit as the dialog shows it, or null when the edit was applied. */
internal fun <V, E> LayoutEdit<V, E>.refusal(describe: (E) -> GroupNameError): GroupNameError? = when (this) {
    is LayoutEdit.Applied -> null
    is LayoutEdit.Refused -> describe(error)
    LayoutEdit.Unavailable -> GroupNameError.Gone
}

internal fun GroupError.toNameError(): GroupNameError = when (this) {
    GroupError.EmptyName -> GroupNameError.Empty
    GroupError.NameTooLong -> GroupNameError.TooLong
    GroupError.NameHasControlCharacter -> GroupNameError.ControlCharacter
    is GroupError.DuplicateName -> GroupNameError.Duplicate(name)
    else -> GroupNameError.Gone
}
