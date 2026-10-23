package dev.pohunek.companion.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import dev.pohunek.companion.R
import dev.pohunek.companion.core.ui.theme.PohunekTheme

/**
 * Debug-only scratch screen for the `scripts/ui-agent` gestures: a list whose rows are reordered by
 * long-press and drag, and a button that puts the current order on the clipboard. Opened with
 * `adb shell am start -n dev.pohunek.companion/.debug.GestureDemoActivity`.
 */
class GestureDemoActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val count = resources.getInteger(R.integer.gesture_demo_items)
        setContent {
            PohunekTheme {
                GestureDemoScreen(count, Modifier.semantics { testTagsAsResourceId = true })
            }
        }
    }
}

@Composable
private fun GestureDemoScreen(count: Int, modifier: Modifier = Modifier) {
    val order = remember { mutableStateListOf<Int>().apply { addAll(1..count) } }
    val context = LocalContext.current
    val orderText = order.joinToString(",")
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        Text(orderText, Modifier.testTag("GestureDemo.order"))
        OutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("order", orderText))
            },
            modifier = Modifier.testTag("GestureDemo.copy"),
        ) { Text(stringResource(R.string.gesture_demo_copy)) }
        Column(Modifier.testTag("GestureDemo.list")) {
            // Keyed by item so a row keeps its drag gesture while it moves.
            order.toList().forEach { item -> key(item) { DemoRow(item, order) } }
        }
    }
}

@Composable
private fun DemoRow(item: Int, order: MutableList<Int>) {
    val rowHeight = 56.dp
    Text(
        stringResource(R.string.gesture_demo_item, item),
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 4.dp)
            .testTag("GestureDemo.row")
            .pointerInput(item) {
                var travelled = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { travelled = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        travelled += amount.y
                        val rowPx = rowHeight.toPx()
                        val from = order.indexOf(item)
                        val target = (from + (travelled / rowPx).toInt()).coerceIn(0, order.lastIndex)
                        if (target != from) {
                            order.add(target, order.removeAt(from))
                            travelled -= (target - from) * rowPx
                        }
                    },
                )
            },
    )
}
