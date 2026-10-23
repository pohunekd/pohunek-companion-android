package dev.pohunek.companion.core.data.attach

import dev.pohunek.companion.protocol.AttachPolicy
import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.LivenessPolicy
import dev.pohunek.companion.protocol.ProtocolClientException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Tunables of the re-attach loop of one terminal; every value is positive and the delays are ordered. */
class ReattachPolicy(
    /** Wait before the first re-attach; doubles per consecutive failure. */
    val initialDelay: Duration,
    /** Longest wait between re-attaches; also how long an attach must hold before the failure count resets. */
    val maxDelay: Duration,
    /** How long the terminal may stay without an attachment before it stops retrying and shows a final error. */
    val giveUpAfter: Duration,
) {
    init {
        require(initialDelay.isPositive()) { "initialDelay must be positive" }
        require(maxDelay >= initialDelay) { "maxDelay must be at least initialDelay" }
        require(giveUpAfter.isPositive()) { "giveUpAfter must be positive" }
    }

    /** Wait before re-attach number [failures] (1-based): the initial wait doubled per earlier failure, capped at the maximum. */
    fun delayFor(failures: Int): Duration {
        val shift = (failures - 1).coerceIn(0, MAX_SHIFT)
        val initial = initialDelay.inWholeMilliseconds
        val scaled = if (initial > Long.MAX_VALUE shr shift) Long.MAX_VALUE else initial shl shift
        return minOf(scaled, maxDelay.inWholeMilliseconds).milliseconds
    }

    private companion object {
        /** Larger shifts only overflow; the cap is reached long before. */
        const val MAX_SHIFT = 32
    }
}

/** Why a terminal stopped trying to attach. */
enum class FailureKind {
    /** The daemon says the session ended, was removed, has no terminal, or lost its runtime (only `session.resume` recovers it); retrying cannot help. */
    SessionGone,

    /** The route or the protocol version is unusable; retrying cannot help. */
    Unusable,

    /** The host stayed unreachable for [ReattachPolicy.giveUpAfter]. */
    GaveUp,
}

private val SESSION_GONE_CODES = setOf("session_not_found", "session_not_running", "session_external_read_only", "session_runtime_lost")

/** The final reason this failure ends the retries, or null when another attach may succeed. */
fun ProtocolClientException.finalKind(): FailureKind? = when {
    this is ProtocolClientException.Daemon && error.code in SESSION_GONE_CODES -> FailureKind.SessionGone
    this is ProtocolClientException.InvalidRoute || this is ProtocolClientException.VersionMismatch -> FailureKind.Unusable
    else -> null
}

/** Everything a [TerminalAttachment] reads from configuration. */
class AttachSettings(
    val client: ClientConfig,
    val attach: AttachPolicy,
    val liveness: LivenessPolicy,
    val reattach: ReattachPolicy,
)
