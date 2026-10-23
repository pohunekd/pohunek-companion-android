// Ported to Kotlin from pohunek-companion `crates/core/src/sort_key.rs`.
// Repository: https://github.com/pohunekd/pohunek-companion, MIT, Copyright (c) 2026 pohunekd. See THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.domain.layout

/**
 * Fractional sort keys: short strings over `a..z` that order lexicographically and always leave room between two distinct
 * neighbours, so a move rewrites exactly one key and precision never runs out.
 */
object SortKey {
    private const val MIN = 'a'
    private const val BASE = 26

    /** Whether [key] is well formed: non-empty, lowercase ASCII, and not ending in `a` (a trailing `a` would leave no room before the key). */
    fun isValid(key: String): Boolean = key.isNotEmpty() && key.all { it in MIN..'z' } && !key.endsWith(MIN)

    /**
     * A key strictly between [lower] and [upper]; null is the open end. Both bounds must be valid keys with `lower < upper`;
     * an [upper] that does not exceed [lower] is treated as open.
     */
    fun between(lower: String?, upper: String?): String {
        require(lower == null || lower.all { it in MIN..'z' }) { "lower bound is not a sort key" }
        require(upper == null || upper.all { it in MIN..'z' }) { "upper bound is not a sort key" }
        val low = lower.orEmpty()
        val high = upper?.takeIf { it > low }
        if (low.isEmpty() && high == null) return (MIN + BASE / 2).toString()
        return midpoint(low, high)
    }

    private fun digit(key: String, index: Int): Int = if (index < key.length) key[index] - MIN else 0

    private fun midpoint(lower: String, upper: String?): String {
        val common = if (upper == null) 0 else (0 until upper.length).takeWhile { digit(lower, it) == upper[it] - MIN }.size
        if (upper != null && common > 0) return upper.substring(0, common) + midpoint(lower.drop(common), upper.substring(common))
        val low = digit(lower, 0)
        val high = if (upper == null) BASE else digit(upper, 0)
        return when {
            // Appending steps by one letter instead of halving the remaining range, so a run of appends lengthens the key by a letter
            // only every 25 keys.
            upper == null && low < BASE - 1 -> (MIN + low + 1).toString()
            high - low > 1 -> (MIN + (low + high) / 2).toString()
            upper != null && upper.length > 1 -> upper[0].toString()
            else -> (MIN + low).toString() + midpoint(lower.drop(1), null)
        }
    }
}
