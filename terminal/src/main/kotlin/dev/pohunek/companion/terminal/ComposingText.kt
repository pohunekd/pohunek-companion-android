package dev.pohunek.companion.terminal

/** What the PTY must still receive to follow a text change: [backspaces] erasures, then [text]. */
internal data class TextEdit(val backspaces: Int, val text: String) {
    companion object {
        val NONE = TextEdit(0, "")
    }
}

/**
 * Tracks the text an IME is composing and turns every update into the [TextEdit] the PTY still needs, so each
 * character is sent once while it is typed and a replaced or deleted tail is erased with one backspace per code point.
 * The IME's own view of the editor stays empty (the terminal has no editable text), so nothing else is kept.
 */
internal class ComposingText {
    private class Entry(val codePoint: Int, var erasable: Boolean)

    private val entries = ArrayList<Entry>()

    /** The composition becomes [text]: only the part that differs from the previous composition is sent. */
    fun update(text: CharSequence): TextEdit {
        val target = text.toString().codePoints().toArray()
        var common = 0
        while (common < entries.size && common < target.size && entries[common].codePoint == target[common]) common++
        var backspaces = 0
        for (i in common until entries.size) if (entries[i].erasable) backspaces++
        while (entries.size > common) entries.removeAt(entries.lastIndex)
        val appended = StringBuilder()
        for (i in common until target.size) {
            entries.add(Entry(target[i], erasable = true))
            appended.appendCodePoint(target[i])
        }
        return TextEdit(backspaces, appended.toString())
    }

    /** The IME commits [text] in place of the composition: the same diff as [update], then nothing is composing. */
    fun commit(text: CharSequence): TextEdit = update(text).also { clear() }

    /** The IME deletes [length] UTF-16 units before the cursor: composing text goes first, the rest is erased blind. */
    fun deleteBefore(length: Int): TextEdit {
        var remaining = length
        var backspaces = 0
        while (remaining > 0 && entries.isNotEmpty()) {
            val last = entries.removeAt(entries.lastIndex)
            remaining -= Character.charCount(last.codePoint)
            if (last.erasable) backspaces++
        }
        return TextEdit(backspaces + maxOf(remaining, 0), "")
    }

    /** The last [count] code points were sent as a key chord, which a backspace cannot take back. */
    fun markNotErasable(count: Int) {
        for (i in maxOf(entries.size - count, 0) until entries.size) entries[i].erasable = false
    }

    /** The composition ended without a change to the PTY (finish, or the IME restarted its own state). */
    fun clear() = entries.clear()
}
