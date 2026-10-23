package dev.pohunek.companion.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** What a `host.discover` record says about the peer's daemon, as classified for the protocol range of the asking request. */
sealed interface HostClass {
    /** A compatible daemon answered a health probe. */
    data class ReachableDaemon(val daemonVersion: String) : HostClass

    /** A daemon answered but speaks a protocol version outside the asking range. */
    data class VersionMismatch(val daemonProtocolVersion: Int) : HostClass

    /** The peer advertises an overlay address, but its daemon port gave no usable health response. */
    data object Unreachable : HostClass

    /** The peer had no dialable address, so it was not probed. */
    data object Candidate : HostClass

    /** A classification this client does not model; such a record is ignored, so a daemon that adds one never blinds discovery. */
    data class Unknown(val name: String) : HostClass
}

/** One peer of `host.discover`; [port] is the effective daemon port of the overlay route and [address] an IP without a port. */
@Serializable(with = HostRecordSerializer::class)
data class HostRecord(
    val name: String?,
    val fqdn: String?,
    val address: String?,
    val port: Int,
    val overlay: String,
    val peerId: String?,
    val hostClass: HostClass,
)

/** The flat wire object: the classification is an internal tag whose fields sit beside the record's own. */
@Serializable
private data class HostRecordWire(
    val name: String? = null,
    val fqdn: String? = null,
    val address: String? = null,
    val port: Int,
    val overlay: String,
    @SerialName("peer_id") val peerId: String? = null,
    val classification: String,
    @SerialName("daemon_version") val daemonVersion: String? = null,
    @SerialName("daemon_protocol_version") val daemonProtocolVersion: Int? = null,
)

/** Decodes a record; a port outside 1 to 65535 or a missing field of a known classification is a decode error, never a default. */
object HostRecordSerializer : KSerializer<HostRecord> {
    override val descriptor: SerialDescriptor = SerialDescriptor("dev.pohunek.companion.protocol.HostRecord", HostRecordWire.serializer().descriptor)

    override fun deserialize(decoder: Decoder): HostRecord {
        val wire = decoder.decodeSerializableValue(HostRecordWire.serializer())
        if (wire.port !in 1..MAX_PORT) throw SerializationException("port ${wire.port} is outside 1 to 65535")
        val hostClass = when (wire.classification) {
            "reachable_daemon" -> HostClass.ReachableDaemon(wire.daemonVersion ?: throw SerializationException("reachable_daemon without daemon_version"))
            "version_mismatch" -> HostClass.VersionMismatch(
                wire.daemonProtocolVersion ?: throw SerializationException("version_mismatch without daemon_protocol_version"),
            )
            "unreachable" -> HostClass.Unreachable
            "candidate" -> HostClass.Candidate
            else -> HostClass.Unknown(wire.classification)
        }
        return HostRecord(wire.name, wire.fqdn, wire.address, wire.port, wire.overlay, wire.peerId, hostClass)
    }

    override fun serialize(encoder: Encoder, value: HostRecord): Unit = throw SerializationException("a host record is only decoded")
}

private const val MAX_PORT = 65_535
