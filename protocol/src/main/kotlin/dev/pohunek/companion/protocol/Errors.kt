package dev.pohunek.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ErrorClass {
    @SerialName("configuration")
    Configuration,

    @SerialName("daemon")
    Daemon,

    @SerialName("transport")
    Transport,

    @SerialName("runtime")
    Runtime,

    @SerialName("discovery")
    Discovery,
}

/** Error body of a response; the set of `code` values is open. */
@Serializable
data class ProtocolError(
    @SerialName("class") val errorClass: ErrorClass,
    val code: String,
    val msg: String,
    val recover: String? = null,
) {
    val isVersionMismatch: Boolean get() = errorClass == ErrorClass.Daemon && code == VERSION_MISMATCH_CODE

    companion object {
        const val VERSION_MISMATCH_CODE: String = "version_mismatch"
    }
}

/** Every failure of a request or of opening a connection. */
sealed class ProtocolClientException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The daemon answered with an `err` body. */
    class Daemon(val error: ProtocolError) : ProtocolClientException("daemon error ${error.errorClass.name.lowercase()}/${error.code}: ${error.msg}")

    /**
     * No common protocol version: the daemon answered `daemon/version_mismatch` ([error] is set), or its response selected a version
     * outside [offered] or different from the one the connection already fixed ([daemonVersion]).
     */
    class VersionMismatch(val offered: ProtocolVersionRange, val daemonVersion: Int?, val error: ProtocolError?) :
        ProtocolClientException(
            "client protocol $offered is incompatible with the daemon" +
                (daemonVersion?.let { " (selected protocol $it)" } ?: "") + (error?.let { ": ${it.msg}" } ?: ""),
        )

    class InvalidRoute(val route: String, detail: String) : ProtocolClientException("invalid host route '$route': $detail")

    /** The TCP connection could not be opened. */
    class ConnectionFailed(val route: HostRoute, cause: Throwable) : ProtocolClientException("cannot connect to $route: ${cause.message}", cause)

    class ConnectTimeout(val route: HostRoute, val timeoutMillis: Long) : ProtocolClientException("connecting to $route timed out after $timeoutMillis ms")

    /** No response within the request timeout; the request may have been applied. */
    class RequestTimeout(val timeoutMillis: Long) : ProtocolClientException("no response within $timeoutMillis ms; the request may have been applied")

    /** The connection broke or ended before a complete response arrived. */
    class ConnectionLost(detail: String, cause: Throwable? = null) : ProtocolClientException("connection lost: $detail", cause)

    /** A control line exceeded the cap or was not valid UTF-8. */
    class Framing(detail: String, cause: Throwable? = null) : ProtocolClientException("protocol framing error: $detail", cause)

    /** The line is not a valid response envelope, or the result does not match the documented shape. */
    class MalformedResponse(detail: String, cause: Throwable? = null) : ProtocolClientException("malformed response: $detail", cause)

    /** A subscription line is not a valid event envelope, or a modelled event's payload does not match the documented shape. */
    class MalformedEvent(detail: String, cause: Throwable? = null) : ProtocolClientException("malformed event: $detail", cause)

    class ResponseIdMismatch(val expected: String, val actual: String) : ProtocolClientException("response id '$actual' does not answer request '$expected'")

    /** An earlier failure left the connection in an undefined state; open a new one. */
    class ConnectionUnusable(detail: String) : ProtocolClientException("connection is unusable: $detail")
}
