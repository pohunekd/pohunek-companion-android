package dev.pohunek.companion.terminal

import kotlin.math.abs
import kotlin.math.floor

/**
 * Finger travel in pixels, signed (positive scrolls toward newer content), turned into whole rows.
 * Whatever is not yet a whole row stays in [pending], so slow and fast gestures lose nothing.
 */
internal class ScrollAccumulator {
    var pending = 0f
        private set

    fun add(pixels: Float) {
        pending += pixels
    }

    fun clear() {
        pending = 0f
    }

    /**
     * Rows for the local scrollback, rounded down: [pending] stays in `[0, cellHeight)` and is the
     * sub-row offset the view draws the content shifted by.
     */
    fun takeFloorRows(cellHeight: Int): Int {
        val rows = floor(pending / cellHeight).toInt()
        pending -= rows * cellHeight
        return rows
    }

    /** Rounds [pending] to the nearest row and clears it; returns the rows to scroll for that. */
    fun settle(cellHeight: Int): Int {
        val rows = Math.round(pending / cellHeight)
        pending = 0f
        return rows
    }

    /**
     * Rows to report to a program this frame, rounded toward zero, at most [maxRows] in magnitude.
     * What the cap holds back stays pending for the next frame, except what exceeds
     * [maxBacklogRows]: a backlog is dropped rather than replayed after the finger stopped.
     */
    fun takeReportRows(cellHeight: Int, maxRows: Int, maxBacklogRows: Int): Int {
        val backlogPixels = maxBacklogRows.toFloat() * cellHeight
        pending = pending.coerceIn(-backlogPixels, backlogPixels)
        val whole = (pending / cellHeight).toInt()
        val rows = if (abs(whole) > maxRows) maxRows * Integer.signum(whole) else whole
        pending -= rows * cellHeight
        return rows
    }
}
