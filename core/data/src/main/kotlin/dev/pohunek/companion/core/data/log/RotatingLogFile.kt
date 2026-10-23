package dev.pohunek.companion.core.data.log

import java.io.File
import java.io.IOException

/**
 * Append-only line file bounded by size: when a line would push the active file past [maxBytes], the active file replaces the one
 * previous file (`NAME.1`) and a new active file starts. The two files together never exceed twice [maxBytes]. Not thread-safe;
 * [JsonLineLog] serialises access.
 */
class RotatingLogFile(private val directory: File, private val name: String, private val maxBytes: Long) {
    init {
        require(maxBytes > 0) { "maxBytes must be greater than 0, got $maxBytes" }
    }

    private val active = File(directory, name)
    private val previous = File(directory, "$name.1")

    /** [line] is one complete line including its terminator; a line longer than [maxBytes] is refused. */
    fun append(line: ByteArray) {
        if (line.size > maxBytes) throw IOException("log line of ${line.size} bytes exceeds the limit of $maxBytes bytes")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create the log directory")
        if (active.length() + line.size > maxBytes) rotate()
        active.appendBytes(line)
    }

    private fun rotate() {
        if (previous.exists() && !previous.delete()) throw IOException("cannot remove the previous log file")
        if (active.exists() && !active.renameTo(previous)) throw IOException("cannot rotate the log file")
    }
}
