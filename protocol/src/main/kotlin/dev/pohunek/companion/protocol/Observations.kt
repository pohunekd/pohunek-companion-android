package dev.pohunek.companion.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A canonical unsigned decimal string of the wire (`revision`, `runtime_generation`) held as its exact value. Strings are never compared
 * as text: `"10"` sorts before `"9"`.
 */
@JvmInline
@Serializable(with = DecimalWireSerializer::class)
value class DecimalWire(val value: ULong) : Comparable<DecimalWire> {
    override fun compareTo(other: DecimalWire): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()

    companion object {
        /** Null for anything but digits without leading zeros that fits 64 bits, which the daemon never sends. */
        fun parse(text: String): DecimalWire? = text.toULongOrNull()?.takeIf { it.toString() == text }?.let(::DecimalWire)
    }
}

object DecimalWireSerializer : KSerializer<DecimalWire> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DecimalWire", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): DecimalWire {
        val text = decoder.decodeString()
        return DecimalWire.parse(text) ?: throw SerializationException("not a canonical unsigned decimal string")
    }

    override fun serialize(encoder: Encoder, value: DecimalWire) = encoder.encodeString(value.toString())
}

/** Worker instance and generation an observation event was produced for. */
@Serializable
data class SessionRuntimeIdentity(
    @SerialName("worker_instance_id") val workerInstanceId: String,
    @SerialName("runtime_generation") val runtimeGeneration: DecimalWire,
)

@Serializable
enum class SubagentLifecycle {
    @SerialName("running")
    Running,

    @SerialName("completed")
    Completed,

    @SerialName("failed")
    Failed,

    @SerialName("cancelled")
    Cancelled,

    @SerialName("lost")
    Lost,
}

/** The fields of a provider-managed subagent that owner state reads; `(provider, id)` identifies it and a newer [revision] replaces it. */
@Serializable
data class SubagentInfo(
    val id: String,
    val provider: String,
    val lifecycle: SubagentLifecycle,
    val revision: DecimalWire,
)

@Serializable
enum class BackgroundStatus {
    @SerialName("running")
    Running,

    @SerialName("pending")
    Pending,

    @SerialName("other")
    Other,
}

@Serializable
data class BackgroundItem(val id: String, val status: BackgroundStatus)

/** The background-task snapshot of a session runtime, replaced wholesale on every [revision]. */
@Serializable
data class SessionBackground(val revision: DecimalWire, val items: List<BackgroundItem>)

/** Payload of `agent_state`; `runtime`, `activity_epoch` and `revision` are additive on the wire, so each may be absent. */
@Serializable
data class AgentStateUpdate(
    @SerialName("session_id") val sessionId: String,
    val activity: AgentActivity,
    val source: StateSource,
    val runtime: SessionRuntimeIdentity? = null,
    @SerialName("activity_epoch") val activityEpoch: String? = null,
    val revision: DecimalWire? = null,
)

@Serializable
data class SubagentStateUpdate(
    @SerialName("session_id") val sessionId: String,
    val subagent: SubagentInfo,
    val runtime: SessionRuntimeIdentity? = null,
)

/** Payload of `background_state`; a null [background] clears the snapshot. */
@Serializable
data class BackgroundStateUpdate(
    @SerialName("session_id") val sessionId: String,
    val background: SessionBackground? = null,
    val runtime: SessionRuntimeIdentity? = null,
)
