package dev.pohunek.companion.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.sessions.SessionAction
import dev.pohunek.companion.core.domain.sessions.SessionActionOutcome
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import dev.pohunek.companion.protocol.HostRoute
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.ResponseDecoder
import dev.pohunek.companion.sessions.ActionDialog
import dev.pohunek.companion.sessions.RowRef
import dev.pohunek.companion.sessions.SessionListEvent
import dev.pohunek.companion.sessions.afterAction

/**
 * Debug-only preview of the confirmation steps the isolated daemon cannot produce: a removal refused with
 * `runtime_supervision_ambiguous` turns into "Remove anyway", whose confirmation then reports the accepted processes and a failed
 * worktree. The outcomes come from the same response lines as the protocol fixtures, decoded by the real decoder, and the dialog
 * changes through the state holder's own transition. Nothing is sent to a daemon. Opened with
 * `adb shell am start -n dev.pohunek.companion/.debug.ActionDialogDemoActivity`.
 */
class ActionDialogDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PohunekTheme {
                ActionDialogDemo(Modifier.semantics { testTagsAsResourceId = true })
            }
        }
    }
}

private val ref = RowRef(HostRoute.parse("127.0.0.1:1"), "s-demo")

private fun demoDialog(action: SessionAction, runtimeProblem: Boolean, busy: Boolean = false) =
    ActionDialog(ref, "docs-install-guide", "demo-host", action, runtimeProblem, busy)

/** `captured/session-remove-ambiguous-response.json` of the protocol tests. */
private const val AMBIGUOUS_LINE =
    """{"v":4,"id":"cap-8","err":{"class":"runtime","code":"runtime_supervision_ambiguous","msg":"cannot confirm that every process of the """ +
        """runtime ended; unreadable: 4242 (node)","recover":"inspect and end the processes that belong to the session, then retry"}}"""

/** `captured/session-remove-accepted-processes-response.json` of the protocol tests, reduced to the fields the dialog reads. */
private val acceptedOutcome = SessionActionOutcome.Done(worktreesFailed = 1, unconfirmedProcesses = listOf("4242 (node)", "4243"))

private fun refusal(): SessionActionOutcome = try {
    ResponseDecoder().decode(AMBIGUOUS_LINE, "cap-8")
    error("the demo line is an error response")
} catch (e: ProtocolClientException.Daemon) {
    SessionActionOutcome.Refused(e.error.code, e.error.msg, e.error.recover)
}

@Composable
private fun ActionDialogDemo(modifier: Modifier = Modifier) {
    var dialog by remember { mutableStateOf<ActionDialog?>(null) }
    var confirmations by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp)) {
        OutlinedButton(
            onClick = {
                confirmations = 0
                dialog = demoDialog(SessionAction.Remove, runtimeProblem = true)
            },
            modifier = Modifier.testTag("ActionDemo.remove"),
        ) { Text(stringResource(R.string.action_dialog_demo_remove)) }
        OutlinedButton(onClick = {
            dialog = demoDialog(SessionAction.Stop, runtimeProblem = false, busy = true)
        }, modifier = Modifier.testTag("ActionDemo.busy")) {
            Text(stringResource(R.string.action_dialog_demo_busy))
        }
    }
    dialog?.let { open ->
        ActionDialog(open) { event ->
            when (event) {
                SessionListEvent.ConfirmAction -> {
                    confirmations++
                    dialog = afterAction(open, if (confirmations == 1) refusal() else acceptedOutcome)
                }
                SessionListEvent.DismissAction -> dialog = null
                else -> Unit
            }
        }
    }
}
