package dev.pohunek.companion.protocol

import kotlinx.serialization.json.JsonElement

/** Wire fixtures copied from Pohunek `sdk/ts/protocol/fixtures` at main commit 52fdbbab80ae581a1e611c1bce6b3bacbdb19c67 (v0.42.0 candidate). */
object Fixtures {
    fun text(name: String): String =
        (Fixtures::class.java.getResourceAsStream("/fixtures/$name.json") ?: error("missing fixture $name")).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Fixtures captured from the isolated daemon; see `captured/README.md`. */
    fun captured(name: String): String =
        (Fixtures::class.java.getResourceAsStream("/captured/$name.json") ?: error("missing captured fixture $name")).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    fun json(name: String): JsonElement = Wire.json.parseToJsonElement(text(name))
}

val sessionListSerializer = kotlinx.serialization.builtins.ListSerializer(SessionInfo.serializer())
