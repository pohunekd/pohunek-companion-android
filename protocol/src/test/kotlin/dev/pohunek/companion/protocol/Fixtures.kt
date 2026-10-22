package dev.pohunek.companion.protocol

import kotlinx.serialization.json.JsonElement

/** Wire fixtures copied from Pohunek `sdk/ts/protocol/fixtures` at v0.35.2. */
object Fixtures {
    fun text(name: String): String =
        (Fixtures::class.java.getResourceAsStream("/fixtures/$name.json") ?: error("missing fixture $name")).use { it.readBytes().toString(Charsets.UTF_8) }

    fun json(name: String): JsonElement = Wire.json.parseToJsonElement(text(name))
}

val sessionListSerializer = kotlinx.serialization.builtins.ListSerializer(SessionInfo.serializer())
