// Refusal rules ported from the desktop companion's attach.rs (AttachRefusal::for_session), see THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.sessions

import dev.pohunek.companion.protocol.SessionState

/** Why a session cannot be attached; the list shows the reason instead of opening a terminal. */
enum class AttachRefusal {
    External,
    Starting,
    Ended,
    ;

    companion object {
        /** Null when a live, daemon-owned session can be attached; an absent [external] flag means a daemon-owned session. */
        fun forSession(external: Boolean?, state: SessionState): AttachRefusal? = when {
            external == true -> External
            else -> when (state) {
                SessionState.Running -> null
                SessionState.Starting -> Starting
                SessionState.Stopped, SessionState.Done, SessionState.Failed -> Ended
            }
        }
    }
}
