package dev.pohunek.companion.terminal

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/** Terminal grid with the key accessory bar below it. */
internal class TerminalHostView(context: Context) : LinearLayout(context) {
    val terminal = GhosttyTerminalView(context)
    private val accessoryBar = TerminalAccessoryBar(context)

    init {
        orientation = VERTICAL
        accessoryBar.onKey = { keyCode -> terminal.sendAccessoryKey(keyCode) }
        accessoryBar.onText = { text -> terminal.sendAccessoryText(text) }
        accessoryBar.onPaste = { terminal.requestPaste() }
        accessoryBar.visibility = GONE
        terminal.accessoryBar = accessoryBar
        addView(terminal, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(accessoryBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setAccessoryBarVisible(visible: Boolean) {
        accessoryBar.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun destroy() = terminal.destroy()
}
