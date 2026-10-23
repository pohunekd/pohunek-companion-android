package dev.pohunek.companion.core.data.layout

import android.util.AtomicFile
import dev.pohunek.companion.core.domain.layout.Layout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The layout file could not be written, or it is unreadable and therefore protected from being replaced. */
class LayoutStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The owner's layout as JSON in the app's files directory, written atomically. A file that cannot be read or breaks the layout rules
 * is reported as unreadable and is never replaced, not even when it became unreadable after it was loaded.
 */
class LayoutStore(private val file: AtomicFile, private val ioDispatcher: CoroutineDispatcher) {
    suspend fun load(): LayoutRead = withContext(ioDispatcher) { read() }

    /** Replaces the stored layout; throws [LayoutStoreException] and leaves the file as it is when it cannot be read or written. */
    suspend fun save(layout: Layout) {
        withContext(ioDispatcher) {
            (read() as? LayoutRead.Unreadable)?.let { throw LayoutStoreException("the layout file is unreadable and is kept as it is: ${it.reason}") }
            write(LayoutCodec.encode(layout))
        }
    }

    private fun read(): LayoutRead {
        // AtomicFile.readFully falls back to the backup left by an interrupted write.
        if (!file.baseFile.exists() && !File(file.baseFile.path + BACKUP_SUFFIX).exists()) return LayoutRead.Loaded(Layout.default(), repaired = false)
        return try {
            LayoutCodec.decode(file.readFully().decodeToString(throwOnInvalidSequence = true))
        } catch (e: IOException) {
            LayoutRead.Unreadable(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun write(text: String) {
        val bytes = text.encodeToByteArray()
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            throw LayoutStoreException("cannot write the layout file: ${e.message}", e)
        }
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw LayoutStoreException("cannot write the layout file: ${e.message}", e)
        }
        verifyWritten(bytes)
    }

    /** AtomicFile.finishWrite reports a failed sync or rename only to logcat, so the result is read back. */
    private fun verifyWritten(bytes: ByteArray) {
        val written = try {
            file.readFully()
        } catch (e: IOException) {
            throw LayoutStoreException("cannot read back the layout file: ${e.message}", e)
        }
        if (!written.contentEquals(bytes)) throw LayoutStoreException("the layout file does not hold what was written")
    }

    private companion object {
        /** Suffix of the backup file android.util.AtomicFile keeps while writing. */
        const val BACKUP_SUFFIX = ".bak"
    }
}
