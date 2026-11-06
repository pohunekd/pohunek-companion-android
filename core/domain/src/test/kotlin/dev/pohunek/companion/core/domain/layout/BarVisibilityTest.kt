package dev.pohunek.companion.core.domain.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class BarVisibilityTest {
    @Test
    fun `scrolling down hides the bar by the scrolled distance up to its height`() {
        assertEquals(30f, hiddenAfterScroll(hidden = 0f, scrollY = -30f, travel = 100f, canScrollForward = true), 0f)
        assertEquals(100f, hiddenAfterScroll(hidden = 90f, scrollY = -30f, travel = 100f, canScrollForward = true), 0f)
    }

    @Test
    fun `scrolling up shows the bar even where the list cannot scroll forward`() {
        assertEquals(60f, hiddenAfterScroll(hidden = 100f, scrollY = 40f, travel = 100f, canScrollForward = false), 0f)
        assertEquals(0f, hiddenAfterScroll(hidden = 10f, scrollY = 40f, travel = 100f, canScrollForward = true), 0f)
    }

    @Test
    fun `a downward scroll on a list at its end leaves the bar where it is`() {
        assertEquals(0f, hiddenAfterScroll(hidden = 0f, scrollY = -30f, travel = 100f, canScrollForward = false), 0f)
        assertEquals(40f, hiddenAfterScroll(hidden = 40f, scrollY = -30f, travel = 100f, canScrollForward = false), 0f)
    }

    @Test
    fun `a bar left half hidden comes to rest at the nearer end`() {
        assertEquals(0f, settledHidden(hidden = 50f, travel = 100f), 0f)
        assertEquals(100f, settledHidden(hidden = 51f, travel = 100f), 0f)
        assertEquals(0f, settledHidden(hidden = 0f, travel = 100f), 0f)
    }
}
