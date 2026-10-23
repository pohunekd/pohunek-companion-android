// Ported to Kotlin from pohunek-companion `crates/core/src/resume.rs` (resume_offer, refusal_sentence, is_ended).
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.sessions

import dev.pohunek.companion.protocol.SessionInfo

/** What a session snapshot says about resuming it. */
sealed interface ResumeOffer {
    /** The session is live or external: there is nothing to resume and nothing to explain. */
    data object NotApplicable : ResumeOffer

    /** An ended session the daemon can resume. */
    data object Available : ResumeOffer

    /** An ended session the daemon cannot resume; [sentence] says why. */
    data class Refused(val sentence: String) : ResumeOffer
}

/** Error code of `session.resume` when the agent profile changed since launch; only the host's local socket may approve it. */
const val PROFILE_CHANGED_CODE: String = "agent_profile_changed"

/** Error code a remote caller gets when it asks to approve a profile change. */
const val PROFILE_CHANGE_LOCAL_ONLY_CODE: String = "agent_profile_change_local_only"

private const val NOT_RESUMABLE_CODE = "not_resumable"

/**
 * Ended means a terminal session state or a lost runtime. The refusal code comes from `capability_refusals.resume`; a cleared
 * capability without a code is `not_resumable`.
 */
fun resumeOffer(info: SessionInfo): ResumeOffer = when {
    info.external == true || !isEnded(info) -> ResumeOffer.NotApplicable
    info.capabilities.resume -> ResumeOffer.Available
    else -> ResumeOffer.Refused(resumeRefusalSentence(info.capabilityRefusals?.resume ?: NOT_RESUMABLE_CODE))
}

/** A short sentence for a `session.resume` refusal [code]; an unknown code is cleaned and quoted. */
fun resumeRefusalSentence(code: String): String = when (code) {
    NOT_RESUMABLE_CODE, "agent_not_resumable" -> "This session has no saved agent conversation to resume."
    "agent_binary_missing" -> "The agent program can no longer be found."
    "agent_native_reference_missing" -> "The agent's saved conversation can no longer be found."
    PROFILE_CHANGED_CODE, PROFILE_CHANGE_LOCAL_ONLY_CODE ->
        "The agent profile changed since launch, and only the host itself can approve that. " +
            "Run `pohunek session resume --accept-profile-change SESSION` on the host."
    "runtime_supervision_ambiguous" -> "The previous run could not be confirmed ended; try again later."
    "runtime_served_by_package", "runtime_not_installed", "runtime_builtin_removed" ->
        "The agent runtime of this session is no longer installed on the host."
    "runtime_incompatible" -> "The agent runtime of this session is incompatible with this host."
    "task_session_ended" -> "The session belongs to an ended task; continue it from the task."
    else -> "The daemon refuses to resume it (${sanitizeText(code)})."
}
