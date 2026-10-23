package dev.pohunek.companion.terminal

import android.view.KeyCharacterMap
import java.text.Normalizer

/**
 * Combines a dead key (a key event whose character carries [KeyCharacterMap.COMBINING_ACCENT]) with the key pressed
 * after it, so `ˇ` then `c` reaches the PTY as `č`.
 */
internal class DeadKeyComposer {
    private var accent = 0

    /**
     * Feeds the character of one key press (`KeyEvent.getUnicodeChar`). Returns null when the key is typed as usual,
     * an empty string when the key is consumed as a dead key, otherwise the text to send instead of the key's own.
     */
    fun feed(unicode: Int): String? = when {
        unicode == 0 -> null
        unicode and KeyCharacterMap.COMBINING_ACCENT != 0 -> {
            accent = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
            ""
        }
        accent == 0 -> null
        else -> combine(unicode, accent).also { accent = 0 }
    }

    private fun combine(unicode: Int, accent: Int): String? {
        if (Character.isISOControl(unicode)) return null
        val base = String(Character.toChars(unicode))
        val composed = Normalizer.normalize(base + String(Character.toChars(accent)), Normalizer.Form.NFC)
        return if (composed.codePointCount(0, composed.length) == 1 || unicode == ' '.code) composed else base
    }
}
