package dev.pohunek.companion.core.data.layout

import dev.pohunek.companion.core.domain.layout.Group
import dev.pohunek.companion.core.domain.layout.GroupOrigin
import dev.pohunek.companion.core.domain.layout.Item
import dev.pohunek.companion.core.domain.layout.Layout
import dev.pohunek.companion.core.domain.layout.LayoutLoad
import dev.pohunek.companion.core.domain.layout.StoredFilter
import dev.pohunek.companion.core.domain.sessions.SessionKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Result of reading the layout file. An [Unreadable] file is reported and never overwritten. */
sealed interface LayoutRead {
    /** [repaired] is true when the file lacks something the layout has (a missing Inbox), so the layout needs saving. */
    data class Loaded(val layout: Layout, val repaired: Boolean) : LayoutRead

    data class Unreadable(val reason: String) : LayoutRead
}

@Serializable
private data class LayoutFile(
    val version: Int,
    val view: String? = null,
    val filters: Map<String, FilterEntry> = emptyMap(),
    @SerialName("collapsed_sections") val collapsedSections: Map<String, List<String>> = emptyMap(),
    val groups: List<GroupEntry> = emptyList(),
    val items: List<ItemEntry> = emptyList(),
)

@Serializable
private data class FilterEntry(
    val states: List<String>? = null,
    @SerialName("project_label") val projectLabel: String? = null,
    @SerialName("no_project") val noProject: Boolean = false,
    val host: String? = null,
)

@Serializable
private data class GroupEntry(
    val id: String,
    val name: String,
    val order: String,
    val collapsed: Boolean = false,
    /** Present only as `learned`; a file without it reads as created. */
    val origin: String? = null,
)

@Serializable
private data class ItemEntry(
    val host: String,
    @SerialName("session_id") val sessionId: String,
    val group: String,
    val order: String,
    val manual: Boolean = false,
)

/** The JSON form of a [Layout]; the field names are those of the desktop companion's layout schema (version 1). */
internal object LayoutCodec {
    private val json = Json { explicitNulls = false }

    fun encode(layout: Layout): String = json.encodeToString(
        LayoutFile.serializer(),
        LayoutFile(
            version = Layout.FORMAT_VERSION,
            view = layout.storedView,
            filters = layout.filters.mapValues { (_, f) -> FilterEntry(f.states, f.projectLabel, f.noProject, f.host) },
            collapsedSections = layout.collapsedSections.mapValues { (_, ids) -> ids.sorted() },
            groups = layout.groups.map { GroupEntry(it.id, it.name, it.order, it.collapsed, it.origin.stored()) },
            items = layout.items.map { ItemEntry(it.key.host, it.key.sessionId, it.group, it.order, it.manual) },
        ),
    )

    private const val LEARNED = "learned"

    private fun GroupOrigin.stored(): String? = if (this == GroupOrigin.Learned) LEARNED else null

    private fun originOf(stored: String?): GroupOrigin? = when (stored) {
        null -> GroupOrigin.Created
        LEARNED -> GroupOrigin.Learned
        else -> null
    }

    fun decode(text: String): LayoutRead {
        val file = try {
            json.decodeFromString(LayoutFile.serializer(), text)
        } catch (e: SerializationException) {
            // The serializer appends the whole input to its message; only the first line says what is wrong.
            return LayoutRead.Unreadable("invalid content: ${e.message.orEmpty().lineSequence().first()}")
        }
        val groups = file.groups.map { entry ->
            val origin = originOf(entry.origin) ?: return LayoutRead.Unreadable("group \"${entry.id}\" has an unknown origin")
            Group(entry.id, entry.name, entry.order, entry.collapsed, origin)
        }
        val load = Layout.load(
            version = file.version,
            view = file.view,
            filters = file.filters.mapValues { (_, f) -> StoredFilter(f.states, f.projectLabel, f.noProject, f.host) },
            collapsedSections = file.collapsedSections.mapValues { (_, ids) -> ids.toSet() },
            groups = groups,
            items = file.items.map { Item(SessionKey(it.host, it.sessionId), it.group, it.order, it.manual) },
        )
        return when (load) {
            is LayoutLoad.Loaded -> LayoutRead.Loaded(load.layout, load.repaired)
            is LayoutLoad.Invalid -> LayoutRead.Unreadable(load.message)
        }
    }
}
