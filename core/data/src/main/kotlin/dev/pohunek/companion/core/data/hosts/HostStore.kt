package dev.pohunek.companion.core.data.hosts

import android.util.AtomicFile
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProtocolClientException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

/** A daemon the owner added by address; [label] is an optional display name. */
data class ConfiguredHost(val route: HostRoute, val label: String?)

/** Result of reading the hosts file. An [Unreadable] file is reported and never overwritten. */
sealed interface HostsLoad {
    data class Loaded(val host: ConfiguredHost?) : HostsLoad

    data class Unreadable(val reason: String) : HostsLoad
}

/** The hosts file could not be written, or it is unreadable and therefore protected from being replaced. */
class HostStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
private data class HostsFile(val version: Int, val hosts: List<StoredHost>)

@Serializable
private data class StoredHost(val route: String, val label: String? = null)

/**
 * The configured host as JSON in the app's files directory, written atomically. The file holds a list so that further hosts need no
 * format change; only one entry is accepted for now.
 */
class HostStore(private val file: AtomicFile, private val ioDispatcher: CoroutineDispatcher) {
    suspend fun load(): HostsLoad = withContext(ioDispatcher) { read() }

    /** Replaces the stored host, or clears it when [host] is null. */
    suspend fun save(host: ConfiguredHost?) {
        withContext(ioDispatcher) {
            (read() as? HostsLoad.Unreadable)?.let { throw HostStoreException("the hosts file is unreadable and is kept as it is: ${it.reason}") }
            write(HostsFile(FORMAT_VERSION, listOfNotNull(host?.let { StoredHost(it.route.toString(), it.label) })))
        }
    }

    private fun read(): HostsLoad {
        if (!file.baseFile.exists()) return HostsLoad.Loaded(null)
        return try {
            decode(JSON.decodeFromString(HostsFile.serializer(), file.readFully().decodeToString()))
        } catch (e: IOException) {
            HostsLoad.Unreadable(e.message ?: e.javaClass.simpleName)
        } catch (e: SerializationException) {
            HostsLoad.Unreadable("invalid content: ${e.message}")
        } catch (e: ProtocolClientException.InvalidRoute) {
            HostsLoad.Unreadable(e.message.orEmpty())
        }
    }

    private fun decode(stored: HostsFile): HostsLoad = when {
        stored.version != FORMAT_VERSION -> HostsLoad.Unreadable("unsupported format version ${stored.version}")
        stored.hosts.size > 1 -> HostsLoad.Unreadable("more than one host is stored")
        else -> HostsLoad.Loaded(stored.hosts.singleOrNull()?.let { ConfiguredHost(HostRoute.parse(it.route), it.label) })
    }

    private fun write(content: HostsFile) {
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            throw HostStoreException("cannot write the hosts file: ${e.message}", e)
        }
        try {
            out.write(JSON.encodeToString(HostsFile.serializer(), content).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw HostStoreException("cannot write the hosts file: ${e.message}", e)
        }
    }

    private companion object {
        /** Format of the hosts file, not a tunable. */
        const val FORMAT_VERSION = 1

        val JSON = Json { explicitNulls = false }
    }
}
