package dev.pohunek.companion.core.data.log

import dev.pohunek.companion.core.domain.log.AppLog
import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.LogLevel
import dev.pohunek.companion.core.domain.log.LogValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.time.Clock

/** Receives every line that was formatted, for example to mirror it to logcat. */
fun interface LogMirror {
    fun write(level: LogLevel, event: String, line: String)
}

/**
 * Writes each event as one JSON object per line: `ts` (ISO-8601 UTC from [clock]), `level`, `event`, then the fields. A field named like
 * one of those keys is dropped, so a line always keeps its envelope. Write failures go to [onWriteFailure]; they never reach the caller.
 */
class JsonLineLog(
    private val file: RotatingLogFile,
    private val clock: Clock,
    private val mirror: LogMirror?,
    private val onWriteFailure: (IOException) -> Unit,
) : AppLog {
    override fun log(level: LogLevel, event: String, fields: List<LogField>) {
        val line = format(clock, level, event, fields)
        mirror?.write(level, event, line)
        synchronized(file) {
            try {
                file.append((line + "\n").encodeToByteArray())
            } catch (e: IOException) {
                onWriteFailure(e)
            }
        }
    }

    /** Replaces [target] with every kept line, oldest first, as of one instant: appends wait while the copy runs. Returns the bytes written. */
    @Throws(IOException::class)
    fun exportTo(target: File): Long = synchronized(file) { file.copyAllTo(target) }

    companion object {
        private val ENVELOPE = setOf("ts", "level", "event")

        /** The JSON line for one event, without its terminator. */
        fun format(clock: Clock, level: LogLevel, event: String, fields: List<LogField>): String {
            val content = LinkedHashMap<String, JsonElement>()
            content["ts"] = JsonPrimitive(clock.instant().toString())
            content["level"] = JsonPrimitive(level.name.lowercase())
            content["event"] = JsonPrimitive(event)
            for (field in fields) {
                if (field.name in ENVELOPE) continue
                content[field.name] = when (val value = field.value) {
                    is LogValue.Text -> JsonPrimitive(value.value)
                    is LogValue.Number -> JsonPrimitive(value.value)
                }
            }
            return JsonObject(content).toString()
        }
    }
}
