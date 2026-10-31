package dev.pohunek.companion.terminal

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout

/** Terminal grid with the key accessory bar below it. */
internal class TerminalHostView(context: Context, settings: TerminalSettings) : LinearLayout(context) {
    val terminal = GhosttyTerminalView(context, settings)
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

    /**
     * Exposes [tag] as the accessibility resource id of the terminal grid. A Compose `testTag` on the
     * `AndroidView` wrapper does not reach the accessibility tree: the embedded view reports its own node.
     */
    fun setTestTag(tag: String) {
        terminal.accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.viewIdResourceName = tag
            }
        }
    }

    fun destroy() = terminal.destroy()
}
