package dev.pohunek.companion.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire contract of the pinned Pohunek tag v0.35.2 (docs/public-api.md, "Compatibility Model" and "Transports"). */
object Wire {
    /** The only protocol version this client speaks; there are no adapters for protocol 3. */
    const val PROTOCOL_VERSION: Int = 4

    /** Longest control line in bytes, excluding the newline; the daemon and the SDK use the same cap. */
    const val MAX_CONTROL_LINE_BYTES: Int = 1_048_576

    /** The range every request of this client advertises. */
    val CLIENT_RANGE: ProtocolVersionRange = ProtocolVersionRange(PROTOCOL_VERSION, PROTOCOL_VERSION)

    /** Results ignore unknown fields; enum values stay closed, so an unknown one is a decode error. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}

/** Inclusive `{minimum, maximum}` range of the request envelope. */
@Serializable
data class ProtocolVersionRange(val minimum: Int, val maximum: Int) {
    init {
        require(minimum in 1..maximum) { "invalid protocol version range $minimum..$maximum" }
    }

    operator fun contains(version: Int): Boolean = version in minimum..maximum

    override fun toString(): String = "$minimum..=$maximum"
}
