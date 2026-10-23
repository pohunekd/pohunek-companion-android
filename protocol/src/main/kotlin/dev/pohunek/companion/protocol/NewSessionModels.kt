package dev.pohunek.companion.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One entry of `project.list`; `id` is the handle `session.new` takes and `label` the display name. Only the fields the app uses are decoded. */
@Serializable
data class ProjectInfo(val id: String, val label: String)

/**
 * One agent of `host.inspect`'s `runtimes`. A launch is possible when [available] holds and [supported] is not false; an absent
 * [supported] means the runtime has no version policy.
 */
@Serializable
data class AgentRuntime(
    val agent: String,
    val available: Boolean,
    val version: String? = null,
    val supported: Boolean? = null,
)

/** The part of `host.inspect`'s `HostCapabilities` the app reads. */
@Serializable
data class HostCapabilities(val runtimes: List<AgentRuntime>)

/** The parameters of a `session.new` that runs [agent] in [project] (a project id of `project.list`). */
data class SessionNewParams(
    val agent: String,
    val cols: Int,
    val rows: Int,
    val project: String,
    /** Owner name; the daemon trims and validates it. Omitted when null. */
    val name: String? = null,
    /** Initial prompt injected into the new terminal; omitted when null. */
    val input: String? = null,
)

/**
 * Result of `session.new`: the created session, flattened, plus `applied_input`. [appliedInput] is true when the daemon injected the
 * initial input and null when none was sent or the daemon does not report it.
 */
data class SessionNewResult(val session: SessionInfo, val appliedInput: Boolean?)

/** Decodes a `session.new` result; a mistyped `applied_input` is a typed error, never a default. */
fun JsonElement.decodeSessionNewResult(): SessionNewResult {
    val session = decodeResult(SessionInfo.serializer())
    val applied = (this as? JsonObject)?.get("applied_input")
    return when {
        applied == null || applied is JsonNull -> SessionNewResult(session, null)
        applied is JsonPrimitive && applied.booleanOrNull != null -> SessionNewResult(session, applied.booleanOrNull)
        else -> throw ProtocolClientException.MalformedResponse("result does not match the documented shape: applied_input is not a boolean")
    }
}
