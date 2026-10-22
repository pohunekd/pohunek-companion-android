package dev.pohunek.companion.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import java.io.IOException

/**
 * Debug hook behind `scripts/ui-agent screen`: on [ACTION] it writes the text visible in the attached terminal to
 * `files/debug/screen.txt` of the app, where the host reads it through `run-as`. The ordered-broadcast result code tells the sender
 * whether a screen was written: [RESULT_WRITTEN] or [RESULT_UNAVAILABLE] with the reason as result data.
 */
internal class ScreenDumpReceiver(private val screenText: () -> String?) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = screenText()
        if (text == null) {
            setResult(RESULT_UNAVAILABLE, NO_TERMINAL, null)
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
        const val ACTION = "dev.pohunek.companion.debug.DUMP_SCREEN"
        const val RESULT_WRITTEN = 1
        const val RESULT_UNAVAILABLE = 2
        private const val NO_TERMINAL = "no terminal is attached"
        private const val DIRECTORY = "debug"
        private const val FILE = "screen.txt"
    }
}
