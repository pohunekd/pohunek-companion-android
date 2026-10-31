package dev.pohunek.companion.terminal

import kotlin.time.Duration

/** The tunables of the terminal view, given by the host app from its configuration. */
data class TerminalSettings(
    /** The shortest time between two wheel reports (or arrow keys) sent to a program while scrolling. */
    val wheelMinInterval: Duration,
    /** Finger travel in rows that may wait behind that spacing before the excess is dropped. */
    val wheelBacklogRows: Int,
)
