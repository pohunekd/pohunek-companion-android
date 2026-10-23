package dev.pohunek.companion.debug

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.io.File
import java.io.IOException

/**
 * Debug hook behind `scripts/ui-agent clipboard`: on [ACTION] it writes the primary clip's text to
 * `files/debug/clipboard.txt` of the app, where the host reads it through `run-as`. Android lets only the focused app
 * read the clipboard, so the app must be in the foreground. The ordered-broadcast result code tells the sender whether a
 * text was written: [RESULT_WRITTEN] or [RESULT_UNAVAILABLE] with the reason as result data.
 */
class ClipboardDumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text == null) {
            setResult(RESULT_UNAVAILABLE, EMPTY, null)
            return
        }
        try {
            val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
            val target = File(directory, FILE)
            val pending = File(directory, "$FILE.tmp")
            pending.writeText(text)
            if (!pending.renameTo(target)) throw IOException("cannot replace $target")
            setResult(RESULT_WRITTEN, null, null)
        } catch (e: IOException) {
            setResult(RESULT_UNAVAILABLE, e.message ?: e.javaClass.simpleName, null)
        }
    }

    companion object {
        const val ACTION = "dev.pohunek.companion.debug.DUMP_CLIPBOARD"
        const val RESULT_WRITTEN = 1
        const val RESULT_UNAVAILABLE = 2
        private const val EMPTY = "the clipboard has no text, or the app is not focused"
        private const val DIRECTORY = "debug"
        private const val FILE = "clipboard.txt"
    }
}
