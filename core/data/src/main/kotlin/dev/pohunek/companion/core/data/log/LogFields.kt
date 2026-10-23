package dev.pohunek.companion.core.data.log

import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProtocolClientException
import java.security.MessageDigest

private const val HOST_KEY_BYTES = 4

/** The log field that identifies a host without disclosing its address: the leading bytes of the route's SHA-256 as hex. */
fun hostKeyField(route: HostRoute): LogField {
    val digest = MessageDigest.getInstance("SHA-256").digest(route.toString().encodeToByteArray())
    return LogField.hostKey(digest.take(HOST_KEY_BYTES).joinToString("") { "%02x".format(it) })
}

/**
 * The error code of a failed request or connection. Messages are not used: they can quote routes, and a daemon's `msg` can quote
 * session names. The code of a daemon error is the daemon's own `class/code` pair.
 */
fun ProtocolClientException.logCode(): String = when (this) {
    is ProtocolClientException.Daemon -> "daemon/${error.errorClass.name.lowercase()}/${error.code}"
    is ProtocolClientException.VersionMismatch -> "version_mismatch"
    is ProtocolClientException.InvalidRoute -> "invalid_route"
    is ProtocolClientException.ConnectionFailed -> "connection_failed"
    is ProtocolClientException.ConnectTimeout -> "connect_timeout"
    is ProtocolClientException.RequestTimeout -> "request_timeout"
    is ProtocolClientException.LivenessTimeout -> "liveness_timeout"
    is ProtocolClientException.ConnectionLost -> "connection_lost"
    is ProtocolClientException.Framing -> "framing"
    is ProtocolClientException.MalformedResponse -> "malformed_response"
    is ProtocolClientException.MalformedEvent -> "malformed_event"
    is ProtocolClientException.ResponseIdMismatch -> "response_id_mismatch"
    is ProtocolClientException.ConnectionUnusable -> "connection_unusable"
}
