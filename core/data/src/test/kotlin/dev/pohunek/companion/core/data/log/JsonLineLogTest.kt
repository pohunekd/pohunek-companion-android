package dev.pohunek.companion.core.data.log

import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.core.domain.log.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class JsonLineLogTest {
    private val clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC)

    @Test
    fun `a line is one object with the envelope first and fields escaped`() {
        val line = JsonLineLog.format(
            clock,
            LogLevel.Warn,
            "attach.failed",
            listOf(LogField.sessionId("a\"b\nc"), LogField.number("ts", 1), LogField.number("attempt", 2)),
        )
        assertEquals("""{"ts":"2026-01-02T03:04:05Z","level":"warn","event":"attach.failed","session":"a\"b\nc","attempt":2}""", line)
    }
}
