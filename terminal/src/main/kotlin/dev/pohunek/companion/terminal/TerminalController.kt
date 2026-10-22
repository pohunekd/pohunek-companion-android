package dev.pohunek.companion.terminal

import android.os.Looper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** Character grid of the terminal, in cells. */
data class TerminalSize(val cols: Int, val rows: Int)

/**
 * Transport-facing side of an embedded terminal: bytes to display go in through [write], what the user
 * typed (and the terminal's own query responses) comes out of [input], and the grid size out of [size].
 *
 * A controller is bound to the view that [TerminalView] creates for it; [write] is valid only while a view
 * is attached and only on the main thread, because the emulator state lives in that view.
 */
class TerminalController {
    private val inputChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val sizeState = MutableStateFlow<TerminalSize?>(null)
    private var terminal: GhosttyTerminalView? = null

    /** Bytes the user produced, in order, to send to the program behind the terminal. Single collector. */
    val input: Flow<ByteArray> = inputChannel.receiveAsFlow()

    /** Current grid size, null until the first layout of an attached view; changes on rotation, IME, and pinch zoom. */
    val size: StateFlow<TerminalSize?> = sizeState.asStateFlow()

    /** Whether a view is attached and [write] may be called. */
    val isAttached: Boolean get() = terminal != null

    /** Feeds [bytes] of program output to the emulator; any byte sequence is accepted. */
    fun write(bytes: ByteArray) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "TerminalController.write must run on the main thread" }
        val target = checkNotNull(terminal) { "TerminalController.write called with no attached view" }
        target.write(bytes)
    }

    internal fun attach(view: GhosttyTerminalView) {
        // A recomposition that swaps views attaches the new one before the old one is released.
        terminal?.let { releaseCallbacks(it) }
        terminal = view
        view.onInputBytes = { bytes -> inputChannel.trySend(bytes) }
        view.onGridResize = { cols, rows -> sizeState.value = TerminalSize(cols, rows) }
    }

    internal fun detach(view: GhosttyTerminalView) {
        if (terminal !== view) return
        releaseCallbacks(view)
        terminal = null
        sizeState.value = null
    }

    private fun releaseCallbacks(view: GhosttyTerminalView) {
        view.onInputBytes = null
        view.onGridResize = null
    }
}
