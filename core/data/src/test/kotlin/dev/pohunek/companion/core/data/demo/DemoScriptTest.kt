package dev.pohunek.companion.core.data.demo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoScriptTest {
    private val csi = Regex("\u001b\\[[0-9;]*[A-Za-z]")
    private val standingByTail = Regex("showcase demo: standing by(?:${csi.pattern}|\\r\\n)*\\z")
    private val cells = 42
    private val narrowestWidestLine = 30

    @Test
    fun everyLineOfTheRedrawFitsTheFortyTwoCellTerminal() {
        val lines = String(DemoScript.redraw(), Charsets.UTF_8).replace("\r\n", "\n").split("\n")
        // A checklist tick or a progress frame redraws in place before the line ends, so only what
        // follows the last remaining \r is visible; a line without one is visible as a whole.
        val visible = lines.map { line -> csi.replace(line.substringAfterLast('\r'), "") }
        for (line in visible) {
            val width = line.codePointCount(0, line.length)
            assertTrue("$width cells: $line", width <= cells)
        }
        val widest = visible.maxOf { it.codePointCount(0, it.length) }
        assertTrue("the widest line was only $widest cells", widest >= narrowestWidestLine)
    }

    @Test
    fun stepsAreTheSameEveryRunAndEndWithTheStandingByLine() {
        val first = DemoScript.steps().fold(ByteArray(0)) { bytes, step -> bytes + step.bytes }
        val second = DemoScript.steps().fold(ByteArray(0)) { bytes, step -> bytes + step.bytes }
        assertArrayEquals(first, second)
        val text = String(first, Charsets.UTF_8)
        assertTrue("tail was ${text.takeLast(80)}", standingByTail.containsMatchIn(text))
    }
}
