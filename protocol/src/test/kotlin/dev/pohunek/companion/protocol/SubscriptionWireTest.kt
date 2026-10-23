package dev.pohunek.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionWireTest {
    @Test
    fun `subscribe request has null params`() {
        assertEquals(
            """{"v":{"minimum":4,"maximum":4},"id":"android-subscribe","method":"subscribe","params":null}""",
            Requests.subscribe("android-subscribe").toLine(),
        )
    }

    @Test
    fun `lines that share a read with the acknowledgement stay queued in order`() {
        val queue = LineQueue()
        val chunk = (
            """{"v":4,"id":"android-subscribe","ok":{"subscribed":true}}""" + "\n" +
                """{"v":4,"event":"session_removed","session":"""
            ).toByteArray()
        queue.feed(chunk, chunk.size)
        assertEquals("""{"v":4,"id":"android-subscribe","ok":{"subscribed":true}}""", queue.poll())
        assertNull(queue.poll())
        val rest = "{}}\n".toByteArray()
        queue.feed(rest, rest.size)
        assertEquals("""{"v":4,"event":"session_removed","session":{}}""", queue.poll())
    }
}
