package dev.pohunek.companion.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import dev.pohunek.companion.R
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import dev.pohunek.companion.terminal.TerminalController
import dev.pohunek.companion.terminal.TerminalSettings
import dev.pohunek.companion.terminal.TerminalView
import kotlin.time.Duration.Companion.milliseconds

/** Debug-only screen that exercises the terminal view without a daemon: scripted ANSI output and a local echo. */
class TerminalDemoActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val config = TerminalDemoConfig(
            redrawFrames = resources.getInteger(R.integer.terminal_demo_redraw_frames),
            frameDelayMs = resources.getInteger(R.integer.terminal_demo_frame_delay_ms).toLong(),
            terminal = TerminalSettings(
                wheelMinInterval = resources.getInteger(R.integer.wheel_min_interval_ms).milliseconds,
                wheelBacklogRows = resources.getInteger(R.integer.wheel_backlog_rows),
                syncOutputTimeout = resources.getInteger(R.integer.sync_output_timeout_ms).milliseconds,
            ),
        )
        setContent {
            PohunekTheme {
                TerminalDemoScreen(config, Modifier.semantics { testTagsAsResourceId = true })
            }
        }
    }
}

@Composable
private fun TerminalDemoScreen(config: TerminalDemoConfig, modifier: Modifier = Modifier) {
    val controller = remember { TerminalController(config.terminal) }
    val scope = rememberCoroutineScope()
    val session = remember { TerminalDemoSession(controller, config, scope) }
    LaunchedEffect(session) { session.start() }
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Row(Modifier.padding(horizontal = 8.dp)) {
            DemoButton(R.string.terminal_demo_colours, "TerminalDemo.colours", session::showColours)
            DemoButton(R.string.terminal_demo_alt_screen, "TerminalDemo.altScreen", session::showAltScreen)
            DemoButton(R.string.terminal_demo_clear, "TerminalDemo.clear", session::clear)
        }
        TerminalView(controller, "TerminalDemo.terminal", Modifier.weight(1f))
    }
}

@Composable
private fun DemoButton(label: Int, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.padding(end = 8.dp).testTag(tag)) { Text(stringResource(label)) }
}
