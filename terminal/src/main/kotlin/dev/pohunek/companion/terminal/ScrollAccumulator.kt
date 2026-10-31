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

    private var lastReportNanos = NO_REPORT

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
     * One report step (-1, 0 or 1) for a program at [nowNanos], never closer than [minIntervalNanos] to the previous
     * step. Travel that waits behind that spacing stays pending, except what exceeds [maxBacklogRows]: a backlog is
     * dropped rather than replayed after the finger stopped.
     */
    fun takeReportStep(cellHeight: Int, nowNanos: Long, minIntervalNanos: Long, maxBacklogRows: Int): Int {
        val backlogPixels = maxBacklogRows.toFloat() * cellHeight
        pending = pending.coerceIn(-backlogPixels, backlogPixels)
        val whole = (pending / cellHeight).toInt()
        if (whole == 0) return 0
        if (lastReportNanos != NO_REPORT && nowNanos - lastReportNanos < minIntervalNanos) return 0
        val step = Integer.signum(whole)
        pending -= step * cellHeight
        lastReportNanos = nowNanos
        return step
    }

    /** Whether at least one whole row of travel still waits to be reported. */
    fun hasReportRow(cellHeight: Int): Boolean = abs(pending) >= cellHeight

    private companion object {
        const val NO_REPORT = Long.MIN_VALUE
    }
}
