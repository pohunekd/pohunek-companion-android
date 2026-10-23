package dev.pohunek.companion.core.data.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RotatingLogFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun line(n: Int) = "line-%04d-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\n".format(n).encodeToByteArray()

    @Test
    fun `rotation keeps one previous file and stays under twice the limit`() {
        val dir = folder.newFolder("logs")
        val max = 500L
        val log = RotatingLogFile(dir, "app.log", max)
        for (n in 1..100) {
            log.append(line(n))
            val total = dir.listFiles().orEmpty().sumOf { it.length() }
            assertTrue("total $total", total < 2 * max)
        }
        assertEquals(setOf("app.log", "app.log.1"), dir.list().orEmpty().toSet())
        assertTrue(File(dir, "app.log").length() <= max)
        assertTrue(File(dir, "app.log.1").length() <= max)
        assertTrue(File(dir, "app.log").readText().trimEnd().endsWith("line-0100-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
        val kept = (File(dir, "app.log.1").readLines() + File(dir, "app.log").readLines())
        assertEquals(kept.sorted(), kept)
        assertEquals(kept.size, kept.toSet().size)
    }

    @Test
    fun `a new instance continues the existing active file`() {
        val dir = folder.newFolder("logs")
        RotatingLogFile(dir, "app.log", 500).append(line(1))
        RotatingLogFile(dir, "app.log", 500).append(line(2))
        assertEquals(2, File(dir, "app.log").readLines().size)
        assertFalse(File(dir, "app.log.1").exists())
    }

    @Test
    fun `a line longer than the limit is refused`() {
        val log = RotatingLogFile(folder.newFolder("logs"), "app.log", 10)
        try {
            log.append(line(1))
            error("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("exceeds"))
        }
    }
}
