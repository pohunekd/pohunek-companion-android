package dev.pohunek.companion.core.domain.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunablesTest {
    private val defaults = Tunable.entries.associateWith { 1000L }.toMutableMap().also {
        it[Tunable.RECONNECT_INITIAL_MS] = 500
        it[Tunable.RECONNECT_MAX_MS] = 30_000
        it[Tunable.REATTACH_INITIAL_MS] = 500
        it[Tunable.REATTACH_MAX_MS] = 10_000
        it[Tunable.LOG_MAX_BYTES] = 1_048_576
    }

    private fun entered(vararg changes: Pair<Tunable, String>): Map<Tunable, String> = defaults.mapValues { it.value.toString() } + changes

    private fun invalid(check: OverrideCheck): List<TunableProblem> = (check as OverrideCheck.Invalid).problems

    @Test
    fun `defaults are valid and unchanged entries are not overrides`() {
        assertTrue(tunableProblems(defaults).isEmpty())
        assertEquals(OverrideCheck.Valid(emptyMap()), checkOverrides(defaults, entered()))
    }

    @Test
    fun `only values that differ from the default become overrides`() {
        val check = checkOverrides(defaults, entered(Tunable.IDLE_THRESHOLD_SECS to " 60 ", Tunable.RESEED_INTERVAL_SECS to "1000"))
        assertEquals(OverrideCheck.Valid(mapOf(Tunable.IDLE_THRESHOLD_SECS to 60L)), check)
    }

    @Test
    fun `zero, negative and non-numeric values are refused with the reason`() {
        assertEquals("must be greater than 0, got 0", invalid(checkOverrides(defaults, entered(Tunable.IDLE_THRESHOLD_SECS to "0"))).single().message)
        assertEquals("must be greater than 0, got -5", invalid(checkOverrides(defaults, entered(Tunable.CONNECT_TIMEOUT_MS to "-5"))).single().message)
        for (text in listOf("", "abc", "1.5", "99999999999999999999")) {
            val problem = invalid(checkOverrides(defaults, entered(Tunable.IDLE_THRESHOLD_SECS to text))).single()
            assertEquals(Tunable.IDLE_THRESHOLD_SECS, problem.tunable)
            assertTrue(problem.message.startsWith("must be a whole number"))
        }
    }

    @Test
    fun `a value beyond an Int and a log size below its minimum are refused`() {
        val tooBig = invalid(checkOverrides(defaults, entered(Tunable.IDLE_THRESHOLD_SECS to "2147483648")))
        assertEquals("must be at most 2147483647, got 2147483648", tooBig.single().message)
        val tooSmall = invalid(checkOverrides(defaults, entered(Tunable.LOG_MAX_BYTES to "4095")))
        assertEquals("must be at least 4096, got 4095", tooSmall.single().message)
        assertTrue(checkOverrides(defaults, entered(Tunable.LOG_MAX_BYTES to "4096")) is OverrideCheck.Valid)
    }

    @Test
    fun `an initial backoff above the maximum is refused on both members`() {
        val problems = invalid(checkOverrides(defaults, entered(Tunable.RECONNECT_INITIAL_MS to "40000")))
        assertEquals(
            listOf(
                TunableProblem(Tunable.RECONNECT_MAX_MS, "must be at least reconnect_initial_ms (40000), got 30000"),
                TunableProblem(Tunable.RECONNECT_INITIAL_MS, "must not exceed reconnect_max_ms (30000), got 40000"),
            ),
            problems,
        )
    }

    @Test
    fun `a maximum below the default initial backoff is refused`() {
        val problems = invalid(checkOverrides(defaults, entered(Tunable.REATTACH_MAX_MS to "100")))
        assertEquals(Tunable.REATTACH_MAX_MS, problems.first().tunable)
        assertEquals("must be at least reattach_initial_ms (500), got 100", problems.first().message)
    }

    @Test
    fun `raising both ends together is valid`() {
        val check = checkOverrides(defaults, entered(Tunable.RECONNECT_INITIAL_MS to "40000", Tunable.RECONNECT_MAX_MS to "60000"))
        assertEquals(OverrideCheck.Valid(mapOf(Tunable.RECONNECT_INITIAL_MS to 40_000L, Tunable.RECONNECT_MAX_MS to 60_000L)), check)
    }

    @Test
    fun `resetting one end of an overridden pair can leave an invalid pair`() {
        val resetInitial = entered(Tunable.RECONNECT_MAX_MS to "200")
        val problems = invalid(checkOverrides(defaults, resetInitial))
        assertEquals("must be at least reconnect_initial_ms (500), got 200", problems.first().message)
    }

    @Test
    fun `saved overrides that break the ordering against the defaults are reported`() {
        val saved = mapOf(Tunable.RECONNECT_INITIAL_MS to 40_000L)
        assertEquals(Tunable.RECONNECT_MAX_MS, checkOverrideValues(defaults, saved).first().tunable)
        assertTrue(checkOverrideValues(defaults, mapOf(Tunable.IDLE_THRESHOLD_SECS to 5L)).isEmpty())
    }

    @Test
    fun `a non-numeric entry does not hide the other problems`() {
        val problems = invalid(checkOverrides(defaults, entered(Tunable.CONNECT_TIMEOUT_MS to "x", Tunable.IDLE_THRESHOLD_SECS to "0")))
        assertEquals(setOf(Tunable.CONNECT_TIMEOUT_MS, Tunable.IDLE_THRESHOLD_SECS), problems.map { it.tunable }.toSet())
    }

    @Test
    fun `every key is lowercase of its name and unique`() {
        assertEquals(Tunable.entries.size, Tunable.entries.map { it.key }.toSet().size)
        assertEquals("idle_threshold_secs", Tunable.IDLE_THRESHOLD_SECS.key)
    }
}
