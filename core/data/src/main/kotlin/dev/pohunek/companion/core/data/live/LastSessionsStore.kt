package dev.pohunek.companion.core.data.live

import android.util.AtomicFile
import dev.pohunek.companion.core.data.log.hostKeyField
import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.warn
import dev.pohunek.companion.core.domain.sessions.OwnerSession
import dev.pohunek.companion.protocol.HostRoute
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException

/**
 * The last session list each host published, kept in the app's private files so that a cold start can show it, dimmed, until the host's
 * first seed. The file is a cache: one that cannot be read or written is logged by event and error code, never by content, and the
 * next [save] replaces it.
 */
class LastSessionsStore(private val file: AtomicFile, private val ioDispatcher: CoroutineDispatcher, private val log: AppLog) {
    private val mutex = Mutex()
    private var lists: MutableMap<String, List<OwnerSession>>? = null

    /** The list stored for [route], or null when none is. */
    suspend fun restore(route: HostRoute): List<OwnerSession>? = mutex.withLock { loaded()[route.toString()] }

    /** Stores [sessions] as [route]'s list; a failed write is logged, and the next changed list writes the file again. */
    suspend fun save(route: HostRoute, sessions: List<OwnerSession>) {
        mutex.withLock {
            val current = loaded()
            if (current[route.toString()] == sessions) return
            current[route.toString()] = sessions
            write(current, route)
        }
    }

    /** Drops the lists of every host that is not in [routes]. */
    suspend fun retain(routes: Set<HostRoute>) {
        mutex.withLock {
            val current = loaded()
            val keep = routes.mapTo(HashSet()) { it.toString() }
            if (current.keys.retainAll(keep)) write(current, null)
        }
    }

    private suspend fun loaded(): MutableMap<String, List<OwnerSession>> = lists ?: readFile().also { lists = it }

    private suspend fun readFile(): MutableMap<String, List<OwnerSession>> = withContext(ioDispatcher) {
        // AtomicFile.readFully falls back to the backup left by an interrupted write.
        if (!file.baseFile.exists() && !File(file.baseFile.path + BACKUP_SUFFIX).exists()) return@withContext LinkedHashMap()
        val decoded = try {
            LastSessionsCodec.decode(file.readFully().decodeToString(throwOnInvalidSequence = true))
        } catch (_: IOException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
        if (decoded == null) {
            log.warn("last_sessions.unreadable", listOf(LogField.errorCode("unreadable")))
            LinkedHashMap()
        } else {
            LinkedHashMap(decoded)
        }
    }

    private suspend fun write(hosts: Map<String, List<OwnerSession>>, route: HostRoute?) {
        val bytes = LastSessionsCodec.encode(hosts).encodeToByteArray()
        withContext(ioDispatcher) {
            val out = try {
                file.startWrite()
            } catch (_: IOException) {
                logWriteFailed(route)
                return@withContext
            }
            try {
                out.write(bytes)
                file.finishWrite(out)
            } catch (_: IOException) {
                file.failWrite(out)
                logWriteFailed(route)
            }
        }
    }

    private fun logWriteFailed(route: HostRoute?) {
        log.warn("last_sessions.write_failed", listOfNotNull(route?.let(::hostKeyField), LogField.errorCode("io")))
    }

    private companion object {
        /** Suffix of the backup file android.util.AtomicFile keeps while writing. */
        const val BACKUP_SUFFIX = ".bak"
    }
}
