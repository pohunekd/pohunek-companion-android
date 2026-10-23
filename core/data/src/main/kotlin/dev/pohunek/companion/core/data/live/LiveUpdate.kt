package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.protocol.SessionInfo

/** What a host's live loop tells its collector. */
sealed interface LiveUpdate {
    /** The subscription is acknowledged and the store is seeded. */
    data object Connected : LiveUpdate

    /** The current sessions ordered by id; sent only when they differ from the last list sent. */
    data class Sessions(val sessions: List<SessionInfo>) : LiveUpdate

    /** A re-seed finished and the store holds its result. */
    data object Reseeded : LiveUpdate

    /** The connection is down; [attempt] counts consecutive failures and [code] is the daemon's protocol error code when it refused. */
    data class Reconnecting(val attempt: Int, val reason: String, val code: String?) : LiveUpdate
}
