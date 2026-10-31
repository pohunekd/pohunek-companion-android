package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.core.domain.sessions.OwnerSession
import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.protocol.SessionInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

@Serializable
private data class LastSessionsFile(val version: Int, val hosts: List<HostEntry> = emptyList())

@Serializable
private data class HostEntry(val host: String, val sessions: List<SessionEntry> = emptyList())

@Serializable
private data class SessionEntry(
    val info: SessionInfo,
    @SerialName("owner_state") val ownerState: String,
    @SerialName("waiting_since") val waitingSince: String? = null,
)

/** The JSON form of every host's last published session list, keyed by the route's text. */
internal object LastSessionsCodec {
    const val FORMAT_VERSION = 1

    private val json = Json { explicitNulls = false; ignoreUnknownKeys = true }

    fun encode(hosts: Map<String, List<OwnerSession>>): String = json.encodeToString(
        LastSessionsFile.serializer(),
        LastSessionsFile(
            FORMAT_VERSION,
            hosts.map { (host, sessions) ->
                HostEntry(host, sessions.map { SessionEntry(it.info, it.ownerState.name, it.waitingSince?.toString()) })
            },
        ),
    )

    /** The stored lists, or null when the text is not a file of this version; the cause is not reported because it can quote session data. */
    fun decode(text: String): Map<String, List<OwnerSession>>? {
        val file = parse(text)
        if (file == null || file.version != FORMAT_VERSION) return null
        val hosts = LinkedHashMap<String, List<OwnerSession>>()
        for (entry in file.hosts) {
            hosts[entry.host] = entry.sessions.map { it.toOwnerSession() ?: return null }
        }
        return hosts
    }

    // SerializationException is an IllegalArgumentException; a value the model rejects (such as a missing field) throws it too.
    private fun parse(text: String): LastSessionsFile? = try {
        json.decodeFromString(LastSessionsFile.serializer(), text)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun SessionEntry.toOwnerSession(): OwnerSession? {
        val state = OwnerState.entries.firstOrNull { it.name == ownerState } ?: return null
        val waiting = try {
            waitingSince?.let(OffsetDateTime::parse)
        } catch (_: DateTimeParseException) {
            return null
        }
        return OwnerSession(info, state, waiting)
    }
}
