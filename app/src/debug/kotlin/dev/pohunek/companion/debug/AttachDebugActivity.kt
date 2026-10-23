package dev.pohunek.companion.debug

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.pohunek.companion.R
import dev.pohunek.companion.core.data.attach.AttachState
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.terminal.TerminalView
import kotlinx.coroutines.Dispatchers
import org.koin.mp.KoinPlatform

/**
 * Debug-only screen: enter a host route, list its sessions, tap one to attach the terminal. Rotation is handled in place
 * (see the manifest), so a rotation resizes the attached PTY instead of attaching again.
 */
class AttachDebugActivity : ComponentActivity() {
    private var model: AttachDebugModel? = null
    private val screenDump = ScreenDumpReceiver { model?.active?.controller?.takeIf { it.isAttached }?.screenText() }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val config = try {
            AttachDebugConfig.from(resources)
        } catch (e: IllegalArgumentException) {
            setContent { PohunekTheme { ConfigError(e.message.orEmpty(), Modifier.semantics { testTagsAsResourceId = true }) } }
            return
        }
        val created = AttachDebugModel(config, lifecycleScope, Dispatchers.IO, Dispatchers.Main, KoinPlatform.getKoin().get())
        model = created
        registerReceiver(screenDump, IntentFilter(ScreenDumpReceiver.ACTION), ScreenDumpReceiver.SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED)
        setContent {
            PohunekTheme {
                AttachDebugScreen(created, ::openDemo, Modifier.semantics { testTagsAsResourceId = true })
            }
        }
    }

    override fun onDestroy() {
        if (model != null) unregisterReceiver(screenDump)
        // The model is not retained, so any destruction leaves the screen: a recreated activity starts without the attachment.
        model?.detach()
        super.onDestroy()
    }

    private fun openDemo() = startActivity(Intent(this, TerminalDemoActivity::class.java))
}

@Composable
private fun ConfigError(message: String, modifier: Modifier) {
    Text(
        stringResource(R.string.attach_debug_config_error, message),
        modifier
            .safeDrawingPadding()
            .padding(16.dp)
            .testTag("AttachDebug.configError"),
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun AttachDebugScreen(model: AttachDebugModel, openDemo: () -> Unit, modifier: Modifier = Modifier) {
    val active = model.active
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        if (active == null) {
            SessionPicker(model, openDemo, Modifier.weight(1f))
        } else {
            BackHandler(onBack = model::detach)
            // Keyed on the attach, so a new entry into a session never reuses the previous emulator.
            key(active) { AttachedTerminal(active, model::detach) }
        }
    }
}

@Composable
private fun SessionPicker(model: AttachDebugModel, openDemo: () -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Listing releases the route field so the soft keyboard no longer covers the list.
    val list = {
        focus.clearFocus()
        keyboard?.hide()
        model.list()
    }
    Column(modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = model.route,
            onValueChange = { model.route = it },
            label = { Text(stringResource(R.string.attach_debug_route_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { list() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("AttachDebug.route"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(list, Modifier.testTag("AttachDebug.list")) { Text(stringResource(R.string.attach_debug_list)) }
            OutlinedButton(openDemo, Modifier.testTag("AttachDebug.demo")) { Text(stringResource(R.string.attach_debug_demo)) }
        }
        if (model.listing) Text(stringResource(R.string.attach_debug_listing), Modifier.testTag("AttachDebug.listing"))
        model.listError?.let { Text(it, Modifier.testTag("AttachDebug.error"), color = MaterialTheme.colorScheme.error) }
        val sessions = model.sessions
        if (sessions != null && sessions.isEmpty()) Text(stringResource(R.string.attach_debug_no_sessions))
        LazyColumn(Modifier.weight(1f)) { items(sessions.orEmpty(), key = { it.id }) { SessionRow(it) { model.attach(it) } } }
    }
}

@Composable
private fun SessionRow(session: SessionInfo, onClick: () -> Unit) {
    Text(
        stringResource(R.string.attach_debug_session_row, session.id, session.agent, session.state.name, session.cols, session.rows),
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .testTag("AttachDebug.session"),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ColumnScope.AttachedTerminal(active: ActiveAttach, onDetach: () -> Unit) {
    val state by active.attachment.state.collectAsState()
    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onDetach, Modifier.testTag("AttachDebug.detach")) { Text(stringResource(R.string.attach_debug_detach)) }
        Text(statusText(state), Modifier.testTag("AttachDebug.status"))
    }
    (state as? AttachState.Failed)?.let {
        Text(it.cause.message.orEmpty(), Modifier.padding(horizontal = 8.dp).testTag("AttachDebug.error"), color = MaterialTheme.colorScheme.error)
    }
    TerminalView(active.controller, "AttachDebug.terminal", Modifier.weight(1f))
}

@Composable
private fun statusText(state: AttachState): String = when (state) {
    AttachState.Connecting -> stringResource(R.string.attach_debug_status_connecting)
    is AttachState.Attached -> state.reportedSize?.let {
        stringResource(R.string.attach_debug_status_attached_sized, state.streamId, it.cols, it.rows)
    } ?: stringResource(R.string.attach_debug_status_attached, state.streamId)
    is AttachState.Failed -> stringResource(R.string.attach_debug_status_failed)
    is AttachState.Detached -> stringResource(
        if (state.detachFailure == null) R.string.attach_debug_status_detached else R.string.attach_debug_status_detach_unconfirmed,
    )
}
