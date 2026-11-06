package dev.pohunek.companion.core.data.layout

import android.util.AtomicFile
import dev.pohunek.companion.core.domain.layout.RecentProjects
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.warn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException

@Serializable
private data class RecentProjectsFile(val version: Int, val views: Map<String, List<String>> = emptyMap())

/**
 * The projects the owner picked last in each view's project picker, in `recent_projects.json` of the app's private files. The file is
 * the phone's own and is never sent anywhere. One that cannot be read is logged once and kept as it is: picks are then not recorded.
 */
class RecentProjectsStore(private val file: AtomicFile, private val ioDispatcher: CoroutineDispatcher, private val log: AppLog) {
    private val mutex = Mutex()
    private val current = MutableStateFlow(RecentProjects())
    private var loaded = false
    private var readable = true

    /** The recent projects; empty until [load] has read the file, and while the file is unreadable. */
    val state: StateFlow<RecentProjects> get() = current

    /** Reads the file once. */
    suspend fun load() {
        mutex.withLock { ensureLoaded() }
    }

    /** Records that [label] was picked in view [viewId], keeping at most [size] labels per view. */
    suspend fun pick(viewId: String, label: String, size: Int) {
        mutex.withLock {
            ensureLoaded()
            if (!readable) return
            val next = current.value.picked(viewId, label, size)
            if (next == current.value) return
            current.value = next
            write(next)
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        withContext(ioDispatcher) {
            // AtomicFile.readFully falls back to the backup left by an interrupted write.
            if (!file.baseFile.exists() && !File(file.baseFile.path + BACKUP_SUFFIX).exists()) return@withContext
            val decoded = try {
                val parsed = json.decodeFromString(RecentProjectsFile.serializer(), file.readFully().decodeToString(throwOnInvalidSequence = true))
                parsed.takeIf { it.version == FORMAT_VERSION }?.let { RecentProjects(it.views) }
            } catch (_: IOException) {
                null
            } catch (_: CharacterCodingException) {
                null
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (decoded == null) {
                readable = false
                log.warn("recent_projects.unreadable", listOf(LogField.errorCode("unreadable")))
            } else {
                current.value = decoded
            }
        }
    }

    private suspend fun write(recent: RecentProjects) {
        val bytes = json.encodeToString(RecentProjectsFile.serializer(), RecentProjectsFile(FORMAT_VERSION, recent.views)).encodeToByteArray()
        withContext(ioDispatcher) {
            val out = try {
                file.startWrite()
            } catch (_: IOException) {
                logWriteFailed()
                return@withContext
            }
            try {
                out.write(bytes)
                file.finishWrite(out)
            } catch (_: IOException) {
                file.failWrite(out)
                logWriteFailed()
            }
        }
    }

    private fun logWriteFailed() {
        log.warn("recent_projects.write_failed", listOf(LogField.errorCode("io")))
    }

    private companion object {
        const val FORMAT_VERSION = 1

        /** Suffix of the backup file android.util.AtomicFile keeps while writing. */
        const val BACKUP_SUFFIX = ".bak"

        val json = Json { ignoreUnknownKeys = true }
    }
}
