package dev.pohunek.companion.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollAccumulatorTest {
    private val cell = 40

    @Test
    fun floorRowsKeepAPositiveSubRowOffsetInBothDirections() {
        val up = ScrollAccumulator().apply { add(-10f) }
        assertEquals(-1, up.takeFloorRows(cell))
        assertEquals(30f, up.pending, 0f)

        val down = ScrollAccumulator().apply { add(95f) }
        assertEquals(2, down.takeFloorRows(cell))
        assertEquals(15f, down.pending, 0f)
    }

    private val interval = 16_000_000L

    @Test
    fun reportStepsKeepTheRemainderAndNetOutReversals() {
        val acc = ScrollAccumulator()
        acc.add(100f)
        assertEquals(1, acc.takeReportStep(cell, 0, interval, maxBacklogRows = 10))
        acc.add(-30f)
        assertEquals(0, acc.takeReportStep(cell, interval, interval, maxBacklogRows = 10))
        assertEquals(30f, acc.pending, 0f)
    }

    @Test
    fun reportStepsAreNeverCloserThanTheIntervalAndLoseNoTravel() {
        val acc = ScrollAccumulator().apply { add(-4 * cell.toFloat()) }
        val sent = mutableListOf<Long>()
        var now = 0L
        while (acc.hasReportRow(cell)) {
            if (acc.takeReportStep(cell, now, interval, maxBacklogRows = 10) != 0) sent += now
            now += 4_000_000L
        }
        assertEquals(4, sent.size)
        assertTrue(sent.zipWithNext().all { (a, b) -> b - a >= interval })
        assertEquals(0f, acc.pending, 0f)
    }

    @Test
    fun theBacklogIsBoundedAndTheExcessDropped() {
        val acc = ScrollAccumulator().apply { add(4000f) }
        assertEquals(1, acc.takeReportStep(cell, 0, interval, maxBacklogRows = 3))
        assertEquals(2 * cell.toFloat(), acc.pending, 0f)
    }

    @Test
    fun settleRoundsToTheNearestRow() {
        val acc = ScrollAccumulator().apply { add(25f) }
        assertEquals(1, acc.settle(cell))
        assertEquals(0f, acc.pending, 0f)
    }
}
