package dev.pohunek.companion.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.pohunek.companion.config.AppConfig
import dev.pohunek.companion.core.data.demo.DemoScreen
import dev.pohunek.companion.core.data.demo.DemoScript
import dev.pohunek.companion.core.data.demo.DemoSessions
import dev.pohunek.companion.debug.RegisterScreenDump
import dev.pohunek.companion.protocol.AgentActivity
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionState
import dev.pohunek.companion.terminal.TerminalController
import dev.pohunek.companion.terminal.TerminalView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The screen a demo session's terminal shows, decided by the state the session list gives it. */
internal fun demoScreenOf(info: SessionInfo?): DemoScreen = when {
    info == null || info.state == SessionState.Stopped -> DemoScreen.Ended
    info.activity == AgentActivity.Blocked -> DemoScreen.Blocked
    info.activity == AgentActivity.Working -> DemoScreen.Working
    info.activity == AgentActivity.Idle -> DemoScreen.Idle
    else -> DemoScreen.Shell
}

/**
 * The terminal of a demo session: no attachment is ever built, so nothing leaves the app. The scripted session plays the
 * showcase run; every other session shows its static screen. Typing is read so it does not pile up, and then dropped.
 */
@Composable
internal fun DemoTerminal(config: AppConfig, route: HostRoute, sessionId: String, modifier: Modifier = Modifier) {
    val controller = remember(route, sessionId) { TerminalController(config.terminal) }
    RegisterScreenDump(controller)
    TerminalView(controller, "Terminal.view", modifier.fillMaxSize())
    LaunchedEffect(controller) { play(controller, route, sessionId) }
}

/** Drives [controller] on the main thread: the first size arrives with the view, later ones are redraws. */
private suspend fun play(controller: TerminalController, route: HostRoute, sessionId: String) = coroutineScope {
    launch { controller.input.collect { } }
    controller.size.filterNotNull().first()
    val redraws = controller.size.filterNotNull().drop(1).distinctUntilChanged()
    if (sessionId == DemoSessions.SCRIPTED_SESSION_ID) {
        for (step in DemoScript.steps()) {
            controller.write(step.bytes)
            delay(step.delayAfter)
        }
        redraws.collect { controller.write(DemoScript.redraw()) }
    } else {
        val screen = DemoScript.screen(demoScreenOf(DemoSessions.find(route, sessionId)))
        controller.write(screen)
        val clear = "\u001b[H\u001b[2J".toByteArray()
        redraws.collect { controller.write(clear + screen) }
    }
}
