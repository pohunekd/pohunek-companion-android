package dev.pohunek.companion.core.data.settings

import android.util.AtomicFile
import dev.pohunek.companion.core.domain.config.Tunable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** Result of reading the settings file. */
sealed interface SettingsLoad {
    /** [overrides] holds the saved values; none when the file does not exist. */
    data class Loaded(val overrides: Map<Tunable, Long>) : SettingsLoad

    data class Unreadable(val reason: String) : SettingsLoad
}

/** The settings file could not be written. */
class SettingsStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
private data class SettingsFile(val version: Int, val overrides: Map<String, Long>)

/**
 * The tunables the owner changed, as JSON in the app's files directory, written atomically. Only values that differ from the default
 * in `config.xml` are stored, so a changed default still reaches every tunable that was not overridden. A key no tunable has is
 * ignored when reading and dropped by the next write.
 */
class SettingsStore(private val file: AtomicFile, private val ioDispatcher: CoroutineDispatcher) {
    /** Blocking: the file holds a few lines and is read once, at start-up, before the first screen. */
    fun load(): SettingsLoad {
        // AtomicFile.readFully falls back to the backup left by an interrupted write.
        if (!file.baseFile.exists() && !File(file.baseFile.path + BACKUP_SUFFIX).exists()) return SettingsLoad.Loaded(emptyMap())
        return try {
            decode(JSON.decodeFromString(SettingsFile.serializer(), file.readFully().decodeToString()))
        } catch (e: IOException) {
            SettingsLoad.Unreadable(e.message ?: e.javaClass.simpleName)
        } catch (e: SerializationException) {
            SettingsLoad.Unreadable("invalid content: ${e.message}")
        }
    }

    /** Replaces the stored overrides, also when the file is unreadable: that is how the owner discards it. */
    suspend fun save(overrides: Map<Tunable, Long>) {
        withContext(ioDispatcher) { write(SettingsFile(FORMAT_VERSION, overrides.mapKeys { it.key.key })) }
    }

    private fun decode(stored: SettingsFile): SettingsLoad {
        if (stored.version != FORMAT_VERSION) return SettingsLoad.Unreadable("unsupported format version ${stored.version}")
        val byKey = Tunable.entries.associateBy { it.key }
        return SettingsLoad.Loaded(stored.overrides.mapNotNull { (key, value) -> byKey[key]?.let { it to value } }.toMap())
    }

    private fun write(content: SettingsFile) {
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            throw SettingsStoreException("cannot write the settings file: ${e.message}", e)
        }
        try {
            out.write(JSON.encodeToString(SettingsFile.serializer(), content).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw SettingsStoreException("cannot write the settings file: ${e.message}", e)
        }
    }

    private companion object {
        /** Format of the settings file, not a tunable. */
        const val FORMAT_VERSION = 1

        /** Suffix of the backup file android.util.AtomicFile keeps while writing. */
        const val BACKUP_SUFFIX = ".bak"

        val JSON = Json
    }
}
