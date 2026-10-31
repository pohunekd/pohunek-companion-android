package dev.pohunek.companion.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pohunek.companion.R
import dev.pohunek.companion.config.AppConfig
import dev.pohunek.companion.core.data.attach.AttachState
import dev.pohunek.companion.core.data.attach.FailureKind
import dev.pohunek.companion.core.data.attach.TerminalAttachment
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import dev.pohunek.companion.debug.RegisterScreenDump
import dev.pohunek.companion.ioDispatcher
import dev.pohunek.companion.mainDispatcher
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.terminal.TerminalController
import dev.pohunek.companion.terminal.TerminalView
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.mp.KoinPlatform
import kotlin.time.TimeSource

/** The terminal of one session, full screen. Leaving the destination detaches; every entry attaches anew. */
@Composable
fun TerminalScreen(config: AppConfig, route: String, sessionId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val parsed = remember(route) { parseRoute(route) }
    Box(modifier.fillMaxSize().safeDrawingPadding()) {
        when (parsed) {
            is ParsedRoute.Valid -> AttachedTerminal(config, parsed.route, sessionId, onBack)
            is ParsedRoute.Invalid -> StatusPanel(Modifier.align(Alignment.TopCenter), onBack, showBack = true) {
                Text(
                    stringResource(R.string.terminal_failed, parsed.message),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("Terminal.error"),
                )
            }
        }
    }
}

private sealed interface ParsedRoute {
    data class Valid(val route: HostRoute) : ParsedRoute

    data class Invalid(val message: String) : ParsedRoute
}

private fun parseRoute(route: String): ParsedRoute = try {
    ParsedRoute.Valid(HostRoute.parse(route))
} catch (e: ProtocolClientException.InvalidRoute) {
    ParsedRoute.Invalid(e.message.orEmpty())
}

/** The controller of the terminal view and the single-use attachment that drives it. */
private class TerminalSession(val controller: TerminalController, val attachment: TerminalAttachment)

@Composable
private fun BoxScope.AttachedTerminal(config: AppConfig, route: HostRoute, sessionId: String, onBack: () -> Unit) {
    val session = remember(route, sessionId) {
        val controller = TerminalController(config.terminal)
        val koin = KoinPlatform.getKoin()
        val attachment = TerminalAttachment(
            route, sessionId, controller, config.attachSettings, TimeSource.Monotonic,
            koin.get<CoroutineDispatcher>(ioDispatcher), koin.get<CoroutineDispatcher>(mainDispatcher), koin.get(),
        )
        TerminalSession(controller, attachment)
    }
    DisposableEffect(session) {
        session.attachment.start()
        onDispose { session.attachment.stop() }
    }
    RegisterScreenDump(session.controller)
    val state by session.attachment.state.collectAsStateWithLifecycle()
    TerminalView(session.controller, "Terminal.view", Modifier.fillMaxSize())
    when (val current = state) {
        is AttachState.Attached -> Unit
        AttachState.Connecting -> StatusPanel(Modifier.align(Alignment.TopCenter), onBack, showBack = false) {
            Text(stringResource(R.string.terminal_connecting), modifier = Modifier.testTag("Terminal.status"))
        }
        is AttachState.Reconnecting -> StatusPanel(Modifier.align(Alignment.TopCenter), onBack, showBack = true) {
            Text(stringResource(R.string.terminal_reconnecting, current.attempt), modifier = Modifier.testTag("Terminal.status"))
        }
        is AttachState.Failed -> StatusPanel(Modifier.align(Alignment.TopCenter), onBack, showBack = true) {
            Text(
                when (current.kind) {
                    FailureKind.SessionGone -> stringResource(R.string.terminal_session_gone)
                    FailureKind.GaveUp -> stringResource(R.string.terminal_gave_up, current.cause.message.orEmpty())
                    FailureKind.Unusable -> stringResource(R.string.terminal_failed, current.cause.message.orEmpty())
                },
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("Terminal.error"),
            )
        }
        is AttachState.Detached -> StatusPanel(Modifier.align(Alignment.TopCenter), onBack, showBack = false) {
            val failure = current.detachFailure
            Text(
                if (failure == null) {
                    stringResource(R.string.terminal_detached)
                } else {
                    stringResource(R.string.terminal_detach_unconfirmed, failure.message.orEmpty())
                },
                modifier = Modifier.testTag("Terminal.status"),
            )
        }
    }
}

/** A banner over the terminal; it floats so a state change never resizes the terminal and with it the PTY. */
@Composable
private fun StatusPanel(modifier: Modifier, onBack: () -> Unit, showBack: Boolean, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(PohunekTheme.spacing.gutter)) {
            content()
            if (showBack) {
                TextButton(onClick = onBack, modifier = Modifier.testTag("Terminal.back")) { Text(stringResource(R.string.terminal_back)) }
            }
        }
    }
}
