package dev.pohunek.companion.protocol

import kotlinx.serialization.KSerializer
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
    const val AGENT_STATE: String = "agent_state"
    const val SUBAGENT_STATE: String = "subagent_state"
    const val BACKGROUND_STATE: String = "background_state"
    const val NOTIFICATION_CREATED: String = "notification_created"
    const val NOTIFICATION_UPDATED: String = "notification_updated"
    const val NOTIFICATION_DELETED: String = "notification_deleted"
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

    data class AgentState(val update: AgentStateUpdate) : DaemonEvent {
        override val name: String get() = EventNames.AGENT_STATE
    }

    data class SubagentState(val update: SubagentStateUpdate) : DaemonEvent {
        override val name: String get() = EventNames.SUBAGENT_STATE
    }

    data class BackgroundState(val update: BackgroundStateUpdate) : DaemonEvent {
        override val name: String get() = EventNames.BACKGROUND_STATE
    }

    data class NotificationCreated(val record: NotificationRecord) : DaemonEvent {
        override val name: String get() = EventNames.NOTIFICATION_CREATED
    }

    data class NotificationUpdated(val record: NotificationRecord) : DaemonEvent {
        override val name: String get() = EventNames.NOTIFICATION_UPDATED
    }

    data class NotificationDeleted(val notificationId: String) : DaemonEvent {
        override val name: String get() = EventNames.NOTIFICATION_DELETED
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

    private fun payload(name: String, obj: JsonObject): DaemonEvent = sessionEvent(name, obj) ?: observationEvent(name, obj) ?: DaemonEvent.Unknown(name)

    private fun sessionEvent(name: String, obj: JsonObject): DaemonEvent? = when (name) {
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
        else -> null
    }

    private fun observationEvent(name: String, obj: JsonObject): DaemonEvent? = when (name) {
        EventNames.AGENT_STATE -> DaemonEvent.AgentState(decode(AgentStateUpdate.serializer(), obj, "agent_state"))
        EventNames.SUBAGENT_STATE -> DaemonEvent.SubagentState(decode(SubagentStateUpdate.serializer(), obj, "subagent_state"))
        EventNames.BACKGROUND_STATE -> DaemonEvent.BackgroundState(decode(BackgroundStateUpdate.serializer(), obj, "background_state"))
        EventNames.NOTIFICATION_CREATED -> DaemonEvent.NotificationCreated(decode(NotificationRecord.serializer(), obj["record"], "record"))
        EventNames.NOTIFICATION_UPDATED -> DaemonEvent.NotificationUpdated(decode(NotificationRecord.serializer(), obj["record"], "record"))
        EventNames.NOTIFICATION_DELETED -> DaemonEvent.NotificationDeleted(
            optionalString(obj, "notification_id") ?: throw PayloadException("notification_id is missing"),
        )
        else -> null
    }

    private fun checkVersion(obj: JsonObject) {
        val version = (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw malformed("missing or invalid v")
        if (version !in Wire.CLIENT_RANGE) throw ProtocolClientException.VersionMismatch(Wire.CLIENT_RANGE, version, null)
    }

    private fun session(obj: JsonObject): SessionInfo = decode(SessionInfo.serializer(), obj["session"], "session")

    /** Decodes [element], which is the payload's `what` field or, for flat events, the whole event object. */
    private fun <T> decode(serializer: KSerializer<T>, element: JsonElement?, what: String): T = try {
        Wire.json.decodeFromJsonElement(serializer, requireNotNull(element) { "no $what payload" })
    } catch (e: SerializationException) {
        throw PayloadException("$what does not match the documented shape: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw PayloadException("$what does not match the documented shape: ${e.message}", e)
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
