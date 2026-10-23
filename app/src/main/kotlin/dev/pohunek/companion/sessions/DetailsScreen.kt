package dev.pohunek.companion.sessions

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.pohunek.companion.R
import dev.pohunek.companion.core.domain.sessions.DetailField
import dev.pohunek.companion.core.domain.sessions.clipMiddle
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import kotlinx.coroutines.launch

/**
 * The identity, agent, location and Work facts of one session, grouped, with a copy button per value. Values are clipped in the middle on
 * screen at [valueMaxChars]; copying always takes the full text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailsScreen(view: DetailsView, valueMaxChars: Int, onEvent: (SessionListEvent) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copyFailed = stringResource(R.string.details_copy_failed)
    val copy: (String) -> Unit = { text ->
        if (!copyToClipboard(context, text)) scope.launch { snackbar.showSnackbar(copyFailed) }
    }
    BackHandler { onEvent(SessionListEvent.CloseDetails) }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(view.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("SessionDetails.title")) },
                navigationIcon = {
                    TextButton(onClick = { onEvent(SessionListEvent.CloseDetails) }, modifier = Modifier.testTag("SessionDetails.back")) {
                        Text(stringResource(R.string.details_back))
                    }
                },
                actions = {
                    view.details?.let { details ->
                        TextButton(onClick = { copy(details.copyAll(view.name)) }, modifier = Modifier.testTag("SessionDetails.copyAll")) {
                            Text(stringResource(R.string.details_copy_all))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val body = Modifier.padding(padding).fillMaxSize()
        val details = view.details
        if (details == null) {
            Centered(body) { Message(stringResource(R.string.details_removed), "SessionDetails.removed") }
        } else {
            LazyColumn(body.testTag("SessionDetails.list")) {
                details.sections.forEach { section ->
                    item(key = "section:${section.title}") {
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = PohunekTheme.spacing.gutter, vertical = PohunekTheme.spacing.sm),
                        )
                    }
                    items(section.fields, key = { it.key }) { field -> DetailRow(field, valueMaxChars) { copy(field.value) } }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(field: DetailField, valueMaxChars: Int, onCopy: () -> Unit) {
    ListItem(
        overlineContent = { Text(field.label) },
        headlineContent = { Text(clipMiddle(field.value, valueMaxChars), modifier = Modifier.testTag("SessionDetails.value.${field.key}")) },
        trailingContent = {
            TextButton(onClick = onCopy, modifier = Modifier.testTag("SessionDetails.copy.${field.key}")) { Text(stringResource(R.string.details_copy)) }
        },
    )
    HorizontalDivider()
}

/**
 * Puts [text] on the clipboard marked sensitive, so the system's copy preview does not show it. False when the system refuses the
 * clip, as it does for an app that is not in the foreground.
 */
private fun copyToClipboard(context: Context, text: String): Boolean {
    val clip = ClipData.newPlainText("", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    return try {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        true
    } catch (_: SecurityException) {
        false
    }
}
