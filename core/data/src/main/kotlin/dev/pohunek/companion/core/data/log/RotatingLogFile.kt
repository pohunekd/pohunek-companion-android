package dev.pohunek.companion.core.data.log

import java.io.File
import java.io.IOException

/**
 * Append-only line file bounded by size: when a line would push the active file past the current maximum, the active file replaces the one
 * previous file (`NAME.1`) and a new active file starts. The two files together never exceed twice the maximum, which is read at every append. Not thread-safe;
 * [JsonLineLog] serialises access.
 */
class RotatingLogFile(private val directory: File, private val name: String, private val maxBytesSource: () -> Long) {
    constructor(directory: File, name: String, maxBytes: Long) : this(directory, name, { maxBytes })

    private val active = File(directory, name)
    private val previous = File(directory, "$name.1")

    /** [line] is one complete line including its terminator; a line longer than the maximum is refused. */
    fun append(line: ByteArray) {
        val maxBytes = maxBytesSource()
        require(maxBytes > 0) { "maxBytes must be greater than 0, got $maxBytes" }
        if (line.size > maxBytes) throw IOException("log line of ${line.size} bytes exceeds the limit of $maxBytes bytes")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create the log directory")
        if (active.length() + line.size > maxBytes) rotate()
        active.appendBytes(line)
    }

    /** Replaces [target] with the previous file followed by the active file, oldest line first; returns the bytes written. A missing file adds nothing. */
    fun copyAllTo(target: File): Long = target.outputStream().use { out ->
        listOf(previous, active).filter { it.exists() }.sumOf { source -> source.inputStream().use { it.copyTo(out) } }
    }

    private fun rotate() {
        if (previous.exists() && !previous.delete()) throw IOException("cannot remove the previous log file")
        if (active.exists() && !active.renameTo(previous)) throw IOException("cannot rotate the log file")
    }
}
