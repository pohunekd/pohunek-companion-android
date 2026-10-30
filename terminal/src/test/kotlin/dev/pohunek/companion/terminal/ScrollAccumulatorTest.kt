package dev.pohunek.companion.terminal

import org.junit.Assert.assertEquals
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

    @Test
    fun reportRowsKeepTheRemainderAndNetOutReversals() {
        val acc = ScrollAccumulator()
        acc.add(100f)
        assertEquals(2, acc.takeReportRows(cell, maxRows = 5, maxBacklogRows = 10))
        acc.add(-30f)
        assertEquals(0, acc.takeReportRows(cell, maxRows = 5, maxBacklogRows = 10))
        assertEquals(-10f, acc.pending, 0f)
    }

    @Test
    fun reportRowsAreRateLimitedAndTheBacklogIsBounded() {
        val acc = ScrollAccumulator()
        acc.add(-4000f)
        assertEquals(-3, acc.takeReportRows(cell, maxRows = 3, maxBacklogRows = 10))
        assertEquals(-280f, acc.pending, 0f)
    }

    @Test
    fun settleRoundsToTheNearestRow() {
        val acc = ScrollAccumulator().apply { add(25f) }
        assertEquals(1, acc.settle(cell))
        assertEquals(0f, acc.pending, 0f)
    }
}
