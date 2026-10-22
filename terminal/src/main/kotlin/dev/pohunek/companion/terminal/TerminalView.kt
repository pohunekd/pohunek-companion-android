package dev.pohunek.companion.terminal

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Embeds the terminal and binds it to [controller]. The key accessory bar (Esc, Tab, sticky Ctrl and Alt,
 * arrows, shell symbols, paste) is shown above the soft keyboard while it is visible, and the view resizes
 * to stay clear of it. [testTag] is the terminal's id for `scripts/ui-agent` and UI automation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalView(controller: TerminalController, testTag: String, modifier: Modifier = Modifier) {
    val imeVisible = WindowInsets.isImeVisible
    AndroidView(
        factory = { context -> TerminalHostView(context).also { controller.attach(it.terminal) } },
        modifier = modifier.imePadding(),
        update = {
            it.setAccessoryBarVisible(imeVisible)
            it.setTestTag(testTag)
        },
        onRelease = {
            controller.detach(it.terminal)
            it.destroy()
        },
    )
}
