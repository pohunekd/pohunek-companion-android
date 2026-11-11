package dev.pohunek.companion.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** A daemon enum with a fixed wire spelling per value and one [WireEnum.isUnknown] member for every value this client does not model. */
interface WireEnum {
    val wire: String

    /** True for the member that stands for a wire value the client does not know; it is never sent. */
    val isUnknown: Boolean get() = false
}

/**
 * Decodes a string to the entry with that [WireEnum.wire], or to [unknown] for any other string, so a value a newer daemon adds cannot
 * fail the message that carries it. Encoding [unknown] throws: a placeholder must never reach the wire.
 */
abstract class WireEnumSerializer<E>(
    serialName: String,
    private val entries: List<E>,
    private val unknown: E,
) : KSerializer<E> where E : Enum<E>, E : WireEnum {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): E {
        val value = decoder.decodeString()
        return entries.firstOrNull { it !== unknown && it.wire == value } ?: unknown
    }

    override fun serialize(encoder: Encoder, value: E) {
        if (value === unknown) throw SerializationException("${descriptor.serialName}.${value.name} stands for an unmodelled value and cannot be sent")
        encoder.encodeString(value.wire)
    }
}

/** The wire name of a value that is sent in a request; refuses the placeholder for an unmodelled value. */
fun <E> E.requireWire(): String where E : Enum<E>, E : WireEnum {
    require(!isUnknown) { "${javaClass.simpleName}.$name stands for an unmodelled value and cannot be sent" }
    return wire
}
