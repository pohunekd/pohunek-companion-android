package dev.pohunek.companion.core.domain.layout

/**
 * How far a bar that slides off the bottom edge is hidden after a user scroll of [scrollY] pixels (negative when the content moves up),
 * between 0 (shown) and [travel] (fully hidden). Content that cannot scroll forward never hides it; any upward scroll shows it.
 */
fun hiddenAfterScroll(hidden: Float, scrollY: Float, travel: Float, canScrollForward: Boolean): Float {
    val hides = scrollY < 0f && canScrollForward
    val shows = scrollY > 0f
    return if (hides || shows) (hidden - scrollY).coerceIn(0f, travel) else hidden.coerceIn(0f, travel)
}

/** Where a bar left [hidden] pixels out of [travel] comes to rest: the nearer end. */
fun settledHidden(hidden: Float, travel: Float): Float = if (hidden * 2f > travel) travel else 0f
