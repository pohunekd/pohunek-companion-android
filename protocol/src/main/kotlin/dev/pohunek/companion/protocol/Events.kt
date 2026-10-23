package dev.pohunek.companion.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Event names of the pinned tag's "Events" table that this client models (docs/public-api.md). */
object EventNames {
    const val SESSION_CREATED: String = "session_created"
    const val SESSION_UPDATED: String = "session_updated"
    const val SESSION_STOPPED: String = "session_stopped"
    const val SESSION_REMOVED: String = "session_removed"
    const val SESSION_RUNTIME_RECONNECTED: String = "session_runtime_reconnected"
    const val SESSION_RUNTIME_LOST: String = "session_runtime_lost"
    const val SESSION_RUNTIME_CONFLICT: String = "session_runtime_conflict"
    const val SESSION_NATIVE_RECOVERED: String = "session_native_recovered"
}

/** One line of the `subscribe` stream. */
sealed interface DaemonEvent {
    val name: String

    /** An event that carries a full session snapshot. */
    sealed interface Snapshot : DaemonEvent {
        val session: SessionInfo
    }

    data class SessionCreated(override val session: SessionInfo) : Snapshot {
        override val name: String get() = EventNames.SESSION_CREATED
    }

    data class SessionUpdated(override val session: SessionInfo) : Snapshot {
        override val name: String get() = EventNames.SESSION_UPDATED
    }

    data class SessionStopped(override val session: SessionInfo) : Snapshot {
        override val name: String get() = EventNames.SESSION_STOPPED
    }

    data class SessionRemoved(override val session: SessionInfo) : Snapshot {
        override val name: String get() = EventNames.SESSION_REMOVED
    }

    data class SessionRuntimeReconnected(override val session: SessionInfo) : Snapshot {
        override val name: String get() = EventNames.SESSION_RUNTIME_RECONNECTED
    }

    data class SessionRuntimeLost(override val session: SessionInfo) : Snapshot {
        override val name: String get() = EventNames.SESSION_RUNTIME_LOST
    }

    data class SessionRuntimeConflict(override val session: SessionInfo) : Snapshot {
        override val name: String get() = EventNames.SESSION_RUNTIME_CONFLICT
    }

    data class SessionNativeRecovered(
        override val session: SessionInfo,
        val previousWorkerInstanceId: String?,
        val workerInstanceId: String?,
    ) : Snapshot {
        override val name: String get() = EventNames.SESSION_NATIVE_RECOVERED
    }

    /** A modelled event whose payload does not match the documented shape; the stream stays usable, so the caller counts it and goes on. */
    data class Malformed(override val name: String, val detail: String) : DaemonEvent

    /** An event this client does not model, so a daemon that adds events never breaks decoding. */
    data class Unknown(override val name: String) : DaemonEvent
}

/**
 * Decodes event lines of one subscription. An invalid envelope throws [ProtocolClientException.MalformedEvent]; a modelled event whose payload has
 * the wrong shape decodes to [DaemonEvent.Malformed], never to a default.
 */
object EventDecoder {
    fun decode(line: String): DaemonEvent {
        val obj = parseObject(line)
        checkVersion(obj)
        val name = (obj["event"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw malformed("missing string event")
        if (name.isEmpty() || name.any { it.isISOControl() }) throw malformed("invalid event name")
        return try {
            payload(name, obj)
        } catch (e: PayloadException) {
            DaemonEvent.Malformed(name, e.message.orEmpty())
        }
    }

    private fun payload(name: String, obj: JsonObject): DaemonEvent = when (name) {
        EventNames.SESSION_CREATED -> DaemonEvent.SessionCreated(session(obj))
        EventNames.SESSION_UPDATED -> DaemonEvent.SessionUpdated(session(obj))
        EventNames.SESSION_STOPPED -> DaemonEvent.SessionStopped(session(obj))
        EventNames.SESSION_REMOVED -> DaemonEvent.SessionRemoved(session(obj))
        EventNames.SESSION_RUNTIME_RECONNECTED -> DaemonEvent.SessionRuntimeReconnected(session(obj))
        EventNames.SESSION_RUNTIME_LOST -> DaemonEvent.SessionRuntimeLost(session(obj))
        EventNames.SESSION_RUNTIME_CONFLICT -> DaemonEvent.SessionRuntimeConflict(session(obj))
        EventNames.SESSION_NATIVE_RECOVERED -> DaemonEvent.SessionNativeRecovered(
            session = session(obj),
            previousWorkerInstanceId = optionalString(obj, "previous_worker_instance_id"),
            workerInstanceId = optionalString(obj, "worker_instance_id"),
        )
        else -> DaemonEvent.Unknown(name)
    }

    private fun checkVersion(obj: JsonObject) {
        val version = (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw malformed("missing or invalid v")
        if (version !in Wire.CLIENT_RANGE) throw ProtocolClientException.VersionMismatch(Wire.CLIENT_RANGE, version, null)
    }

    private fun session(obj: JsonObject): SessionInfo = try {
        Wire.json.decodeFromJsonElement(SessionInfo.serializer(), requireNotNull(obj["session"]) { "no session payload" })
    } catch (e: SerializationException) {
        throw PayloadException("session does not match the documented shape: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw PayloadException("session does not match the documented shape: ${e.message}", e)
    }

    private fun optionalString(obj: JsonObject, key: String): String? {
        val value: JsonElement = obj[key] ?: return null
        val primitive = value as? JsonPrimitive ?: throw PayloadException("$key is not a string")
        if (primitive.isString) return primitive.content
        if (primitive.contentOrNull == null) return null
        throw PayloadException("$key is not a string")
    }

    private fun parseObject(line: String): JsonObject = try {
        Wire.json.parseToJsonElement(line) as? JsonObject ?: throw malformed("not a JSON object")
    } catch (e: SerializationException) {
        throw malformed("invalid JSON", e)
    }

    /** Internal: a payload failure, turned into [DaemonEvent.Malformed] by [decode]. */
    private class PayloadException(detail: String, cause: Throwable? = null) : Exception(detail, cause)

    private fun malformed(detail: String, cause: Throwable? = null) = ProtocolClientException.MalformedEvent(detail, cause)
}
