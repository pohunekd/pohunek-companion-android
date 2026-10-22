package dev.pohunek.companion.protocol.manual

import dev.pohunek.companion.protocol.AttachPolicy
import dev.pohunek.companion.protocol.AttachedSession
import dev.pohunek.companion.protocol.ClientConfig
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.TerminalDimensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

private const val ARGUMENT_COUNT = 4
private const val CONNECT_ARGUMENT = 2
private const val REQUEST_ARGUMENT = 3
private const val FIRST_PRINTABLE = 0x20
private const val LAST_PRINTABLE = 0x7e
private const val CHECK_COLS = 100
private const val CHECK_ROWS = 30
private const val RESIZED_COLS = 60
private const val RESIZED_ROWS = 20
private const val RETRY_DELAY_MS = 200L
private const val READ_WINDOW_MS = 1_500L

/**
 * Hand-run check: attaches to a session with a 100x30 view, types `echo hi`, resizes to 60x20, and detaches, printing what the daemon
 * reported. Usage: `AttachCheckKt HOST:PORT SESSION_ID CONNECT_TIMEOUT_MS REQUEST_TIMEOUT_MS`.
 * Prints sizes, byte counts and the printable text of the output; run it against synthetic sessions only.
 */
fun main(args: Array<String>) {
    if (args.size != ARGUMENT_COUNT) {
        System.err.println("usage: AttachCheck HOST:PORT SESSION_ID CONNECT_TIMEOUT_MS REQUEST_TIMEOUT_MS")
        exitProcess(2)
    }
    val connectMs = args[CONNECT_ARGUMENT].toLongOrNull()
    val requestMs = args[REQUEST_ARGUMENT].toLongOrNull()
    if (connectMs == null || requestMs == null) {
        System.err.println("timeouts must be integers in milliseconds")
        exitProcess(2)
    }
    try {
        runBlocking {
            val config = ClientConfig(connectMs.milliseconds, requestMs.milliseconds)
            val session = AttachedSession.open(
                HostRoute.parse(args[0]),
                args[1],
                TerminalDimensions(CHECK_COLS, CHECK_ROWS),
                config,
                AttachPolicy(1, RETRY_DELAY_MS.milliseconds),
                Dispatchers.IO,
            )
            session.use {
                println("attached stream=${it.streamId}")
                val output = Collector()
                val reader = launch(Dispatchers.IO) { while (true) output.add(it.read() ?: break) }
                delay(READ_WINDOW_MS)
                println("repaint: ${output.take()}")
                it.write("echo hi\n".toByteArray())
                delay(READ_WINDOW_MS)
                println("after echo: ${output.take()}")
                val resized = it.resize(TerminalDimensions(RESIZED_COLS, RESIZED_ROWS))
                println("resize reported ${resized.cols}x${resized.rows}")
                delay(READ_WINDOW_MS)
                println("after resize: ${output.take()}")
                println("detach: ${it.detach()}")
                delay(READ_WINDOW_MS)
                println("stream ended after detach: ${reader.isCompleted}")
                it.close()
                reader.join()
            }
        }
    } catch (e: ProtocolClientException) {
        println("typed error: ${e.javaClass.simpleName}: ${e.message}")
        exitProcess(1)
    }
}

/** Output bytes the reader coroutine received since the last [take]. */
private class Collector {
    private val bytes = java.io.ByteArrayOutputStream()

    @Synchronized
    fun add(chunk: ByteArray) = bytes.write(chunk)

    @Synchronized
    fun take(): String {
        val text = bytes.toString(Charsets.ISO_8859_1).map { if (it.code in FIRST_PRINTABLE..LAST_PRINTABLE) it else '.' }.joinToString("")
        val summary = "${bytes.size()} bytes: $text"
        bytes.reset()
        return summary
    }
}
