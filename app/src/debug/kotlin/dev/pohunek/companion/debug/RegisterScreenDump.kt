package dev.pohunek.companion.debug

import android.content.Context
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import dev.pohunek.companion.terminal.TerminalController

/** Answers `scripts/ui-agent screen` with the text of [controller]'s terminal while this composable is in the composition. */
@Composable
fun RegisterScreenDump(controller: TerminalController) {
    val context = LocalContext.current
    DisposableEffect(controller, context) {
        val receiver = ScreenDumpReceiver { controller.takeIf { it.isAttached }?.screenText() }
        context.registerReceiver(receiver, IntentFilter(ScreenDumpReceiver.ACTION), ScreenDumpReceiver.SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
}
