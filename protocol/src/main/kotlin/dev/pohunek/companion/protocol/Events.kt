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

    /** An event this client does not model, so a daemon that adds events never breaks decoding. */
    data class Unknown(override val name: String) : DaemonEvent
}

/** Decodes event lines of one subscription; a modelled event whose payload has the wrong shape is a typed failure, never a default. */
object EventDecoder {
    fun decode(line: String): DaemonEvent {
        val obj = parseObject(line)
        checkVersion(obj)
        val name = (obj["event"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw malformed("missing string event")
        return when (name) {
            EventNames.SESSION_CREATED -> DaemonEvent.SessionCreated(session(name, obj))
            EventNames.SESSION_UPDATED -> DaemonEvent.SessionUpdated(session(name, obj))
            EventNames.SESSION_STOPPED -> DaemonEvent.SessionStopped(session(name, obj))
            EventNames.SESSION_REMOVED -> DaemonEvent.SessionRemoved(session(name, obj))
            EventNames.SESSION_RUNTIME_RECONNECTED -> DaemonEvent.SessionRuntimeReconnected(session(name, obj))
            EventNames.SESSION_RUNTIME_LOST -> DaemonEvent.SessionRuntimeLost(session(name, obj))
            EventNames.SESSION_RUNTIME_CONFLICT -> DaemonEvent.SessionRuntimeConflict(session(name, obj))
            EventNames.SESSION_NATIVE_RECOVERED -> DaemonEvent.SessionNativeRecovered(
                session = session(name, obj),
                previousWorkerInstanceId = optionalString(name, obj, "previous_worker_instance_id"),
                workerInstanceId = optionalString(name, obj, "worker_instance_id"),
            )
            else -> DaemonEvent.Unknown(name)
        }
    }

    private fun checkVersion(obj: JsonObject) {
        val version = (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw malformed("missing or invalid v")
        if (version !in Wire.CLIENT_RANGE) throw ProtocolClientException.VersionMismatch(Wire.CLIENT_RANGE, version, null)
    }

    private fun session(name: String, obj: JsonObject): SessionInfo = try {
        Wire.json.decodeFromJsonElement(SessionInfo.serializer(), requireNotNull(obj["session"]) { "no session payload" })
    } catch (e: SerializationException) {
        throw malformed("$name session does not match the documented shape: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw malformed("$name session does not match the documented shape: ${e.message}", e)
    }

    private fun optionalString(name: String, obj: JsonObject, key: String): String? {
        val value: JsonElement = obj[key] ?: return null
        val primitive = value as? JsonPrimitive ?: throw malformed("$name $key is not a string")
        if (primitive.isString) return primitive.content
        if (primitive.contentOrNull == null) return null
        throw malformed("$name $key is not a string")
    }

    private fun parseObject(line: String): JsonObject = try {
        Wire.json.parseToJsonElement(line) as? JsonObject ?: throw malformed("not a JSON object")
    } catch (e: SerializationException) {
        throw malformed("invalid JSON", e)
    }

    private fun malformed(detail: String, cause: Throwable? = null) = ProtocolClientException.MalformedEvent(detail, cause)
}
