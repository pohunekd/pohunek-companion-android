package dev.pohunek.companion.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

/** Request envelope; `params` is a JSON value and `null` for parameterless methods. */
@Serializable
data class Request(val v: ProtocolVersionRange, val id: String, val method: String, val params: JsonElement) {
    fun toLine(): String = Wire.json.encodeToString(serializer(), this)
}

object Requests {
    fun health(id: String): Request = Request(Wire.CLIENT_RANGE, id, "daemon.health", JsonNull)

    /** `params` is `{}` without filters, as in the public API's example, else `{"filters":[...]}`. */
    fun sessionList(id: String, filters: List<SessionFilter>): Request {
        val params = if (filters.isEmpty()) {
            buildJsonObject { }
        } else {
            buildJsonObject { put("filters", Wire.json.encodeToJsonElement(ListSerializer(SessionFilter.serializer()), filters)) }
        }
        return Request(Wire.CLIENT_RANGE, id, "session.list", params)
    }
}

/**
 * Validates the responses of one connection. The first valid response fixes the protocol version; every later one must carry the same.
 * A failure other than [ProtocolClientException.Daemon] leaves the connection unusable.
 */
class ResponseDecoder(private val offered: ProtocolVersionRange = Wire.CLIENT_RANGE) {
    var selectedVersion: Int? = null
        private set

    /** Returns the `ok` payload of [line], which must answer [expectedId], or throws the typed failure. */
    fun decode(line: String, expectedId: String): JsonElement {
        val response = parseObject(line)
        val error = response["err"]?.let { decodeError(it) }
        // A refused range may come back with a `v` of its own, so this check precedes the range and id checks.
        if (error != null && error.isVersionMismatch) throw ProtocolClientException.VersionMismatch(offered, version(response["v"]), error)
        requireId(response, expectedId)
        selectVersion(version(response["v"]) ?: throw malformed("missing or invalid v"))
        return payload(response["ok"], error)
    }

    private fun requireId(response: JsonObject, expectedId: String) {
        val actualId = (response["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw malformed("missing string id")
        if (actualId != expectedId) throw ProtocolClientException.ResponseIdMismatch(expectedId, actualId)
    }

    private fun payload(ok: JsonElement?, error: ProtocolError?): JsonElement = when {
        error != null && ok == null -> throw ProtocolClientException.Daemon(error)
        ok != null && error == null -> ok
        else -> throw malformed("exactly one of ok and err is required")
    }

    private fun selectVersion(received: Int) {
        val fixed = selectedVersion
        if (received !in offered || (fixed != null && received != fixed)) throw ProtocolClientException.VersionMismatch(offered, received, null)
        selectedVersion = received
    }

    private fun parseObject(line: String): JsonObject = try {
        Wire.json.parseToJsonElement(line) as? JsonObject ?: throw malformed("not a JSON object")
    } catch (e: SerializationException) {
        throw ProtocolClientException.MalformedResponse("invalid JSON", e)
    }

    private fun decodeError(element: JsonElement): ProtocolError = try {
        Wire.json.decodeFromJsonElement(ProtocolError.serializer(), element)
    } catch (e: SerializationException) {
        throw ProtocolClientException.MalformedResponse("invalid err body", e)
    }

    private fun version(element: JsonElement?): Int? = (element as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }

    private fun malformed(detail: String) = ProtocolClientException.MalformedResponse(detail)
}

/** Decodes an `ok` payload into [serializer]'s type; a shape mismatch is a typed error, never a default. */
fun <T> JsonElement.decodeResult(serializer: KSerializer<T>): T = try {
    Wire.json.decodeFromJsonElement(serializer, this)
} catch (e: SerializationException) {
    throw ProtocolClientException.MalformedResponse("result does not match the documented shape: ${e.message}", e)
} catch (e: IllegalArgumentException) {
    throw ProtocolClientException.MalformedResponse("result does not match the documented shape: ${e.message}", e)
}
