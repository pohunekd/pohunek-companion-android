package dev.pohunek.companion.debug

import dev.pohunek.companion.terminal.TerminalController
import dev.pohunek.companion.terminal.TerminalSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Tunables of the demo, read from resources by the activity. */
internal data class TerminalDemoConfig(val redrawFrames: Int, val frameDelayMs: Long)

/**
 * Daemon-free driver of a [TerminalController]: scripted ANSI output plus a local echo of the user's input.
 * [scope] must run on the main thread.
 */
internal class TerminalDemoSession(
    private val controller: TerminalController,
    private val config: TerminalDemoConfig,
    private val scope: CoroutineScope,
) {
    private var scripted: Job? = null
    private var altScreenActive = false

    /** Starts echoing input and prints the colour sample as soon as the grid has a size. */
    fun start() {
        scope.launch { controller.input.collect(::onInput) }
        showColours()
    }

    fun showColours() = runScripted {
        write(AnsiSample.CLEAR_ALL)
        write(AnsiSample.colours(awaitSize().cols))
    }

    fun clear() = runScripted { write(AnsiSample.CLEAR_ALL + AnsiSample.PROMPT) }

    fun showAltScreen() = runScripted {
        write(AnsiSample.ENTER_ALT_SCREEN)
        altScreenActive = true
        write(AnsiSample.CLEAR_ALL)
        for (frame in 0 until config.redrawFrames) {
            val size = awaitSize()
            write(AnsiSample.altScreenFrame(frame, config.redrawFrames, size.cols, size.rows))
            delay(config.frameDelayMs)
        }
        write(AnsiSample.altScreenDone())
    }

    private fun runScripted(block: suspend () -> Unit) {
        leaveAltScreen()
        scripted = scope.launch { block() }
    }

    private suspend fun awaitSize(): TerminalSize = controller.size.filterNotNull().first()

    private fun write(text: String) = controller.write(text.toByteArray())

    private fun leaveAltScreen() {
        scripted?.cancel()
        scripted = null
        if (altScreenActive) {
            altScreenActive = false
            write(AnsiSample.LEAVE_ALT_SCREEN)
        }
    }

    private fun onInput(bytes: ByteArray) {
        if (altScreenActive) {
            if (bytes.any { it == QUIT_BYTE }) {
                leaveAltScreen()
                write(AnsiSample.PROMPT)
            }
            return
        }
        controller.write(echoBytes(bytes))
    }

    private companion object {
        val QUIT_BYTE = 'q'.code.toByte()
    }
}
