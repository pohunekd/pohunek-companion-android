// Ported to Kotlin from pohunek-companion `crates/core/src/layout.rs`.
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.layout

/** Why a move was refused; the layout is left unchanged. */
sealed interface MoveError {
    data class UnknownSession(val sessionId: String) : MoveError

    data class UnknownGroup(val groupId: String) : MoveError

    /** The anchor of a before/after placement is not in the target group. */
    data class AnchorNotInGroup(val sessionId: String) : MoveError

    /** The neighbours at the drop point share an order key, so no key fits between them. */
    data class AmbiguousOrder(val groupId: String) : MoveError
}

/** Why a group operation was refused; the layout is left unchanged. */
sealed interface GroupError {
    /** A label for logs that names the refusal without the group name it may carry. */
    val kind: String

    data object EmptyName : GroupError {
        override val kind = "empty name"
    }

    data class DuplicateName(val name: String) : GroupError {
        override val kind = "duplicate name"
    }

    data class UnknownGroup(val groupId: String) : GroupError {
        override val kind = "unknown group"
    }

    data object InboxFixed : GroupError {
        override val kind = "inbox is fixed"
    }

    data object AmbiguousOrder : GroupError {
        override val kind = "ambiguous order"
    }
}

/** Why creating a group and moving a session into it was refused; the layout is left unchanged. */
sealed interface CreateAndMoveError {
    /** A label for logs that names the refusal without the group name. */
    val kind: String

    data class Group(val error: GroupError) : CreateAndMoveError {
        override val kind get() = error.kind
    }

    data class Move(val error: MoveError) : CreateAndMoveError {
        override val kind = "move refused"
    }
}

/** The outcome of an edit of an immutable [Layout]: the new layout and a value, or the refusal with nothing changed. */
sealed interface Edit<out V, out E> {
    data class Done<out V>(val layout: Layout, val value: V) : Edit<V, Nothing>

    data class Refused<out E>(val error: E) : Edit<Nothing, E>
}
