package dev.pohunek.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Terminal geometry in cells; both sides are positive and fit the daemon's 16-bit fields. */
@Serializable
data class TerminalDimensions(val cols: Int, val rows: Int) {
    init {
        require(cols in DIMENSION_RANGE && rows in DIMENSION_RANGE) { "terminal dimensions must be 1 to $MAX_DIMENSION cells, got ${cols}x$rows" }
    }

    companion object {
        private const val MAX_DIMENSION = 65_535
        private val DIMENSION_RANGE = 1..MAX_DIMENSION
    }
}

/** Result of `session.attach`: the one-shot id the attach connection redeems. */
@Serializable
data class SessionAttachResult(@SerialName("stream_id") val streamId: String)

/** Result of `session.detach`; [error] is the stream failure the worker recorded before the raw connection closed. */
@Serializable
data class SessionDetachResult(val detached: Boolean, val error: ProtocolError? = null)

/** Result of `session.resize`: the session summary after the resize. */
@Serializable
data class SessionResizeResult(val session: SessionInfo)
