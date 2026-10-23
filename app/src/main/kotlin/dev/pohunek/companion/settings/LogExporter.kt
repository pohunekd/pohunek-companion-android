package dev.pohunek.companion.settings

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.pohunek.companion.core.data.log.JsonLineLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The one directory and file of the cache directory the log export provider serves; `res/xml/log_export_paths.xml` and the manifest name the same. */
private const val EXPORT_DIRECTORY = "log-export"
private const val EXPORT_FILE = "pohunek-companion-logs.jsonl"

/** Suffix of the provider's authority, appended to the application id (`${applicationId}.logexport` in the manifest). */
private const val AUTHORITY_SUFFIX = ".logexport"

/** The media type of the shared file: newline-delimited JSON. */
const val LOG_EXPORT_MIME_TYPE = "application/x-ndjson"

sealed interface LogExport {
    /** [uri] names the exported file, [bytes] long, readable by the app that receives the share. */
    data class Ready(val uri: Uri, val bytes: Long) : LogExport

    /** The log has no line yet; nothing is shared. */
    data object Empty : LogExport

    data class Failed(val reason: String) : LogExport
}

/** Copies the app's log (rotated file, then active file) into the export file and names it through the log export provider. */
class LogExporter(private val context: Context, private val log: JsonLineLog, private val io: CoroutineDispatcher) {
    suspend fun export(): LogExport = withContext(io) {
        val directory = File(context.cacheDir, EXPORT_DIRECTORY)
        val target = File(directory, EXPORT_FILE)
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create the export directory")
            val bytes = log.exportTo(target)
            if (bytes == 0L) {
                target.delete()
                LogExport.Empty
            } else {
                LogExport.Ready(FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, target), bytes)
            }
        } catch (e: IOException) {
            target.delete()
            LogExport.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
