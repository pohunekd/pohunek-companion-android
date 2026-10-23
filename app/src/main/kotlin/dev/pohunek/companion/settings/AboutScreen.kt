package dev.pohunek.companion.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import dev.pohunek.companion.R
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import dev.pohunek.companion.ioDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import java.io.IOException

/** Asset name of the notices; Gradle copies `THIRD_PARTY_NOTICES.md` of the repository root into the assets under it. */
private const val NOTICES_ASSET = "THIRD_PARTY_NOTICES.md"

private sealed interface Notices {
    data object Loading : Notices

    data class Loaded(val text: String) : Notices

    data class Failed(val reason: String) : Notices
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val spacing = PohunekTheme.spacing
    val version = remember { appVersion(context) }
    val notices by produceState<Notices>(Notices.Loading) { value = readNotices(context) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack, modifier = Modifier.testTag("About.back")) { Text(stringResource(R.string.settings_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.gutter).testTag("About.list"),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(stringResource(R.string.about_version, version), modifier = Modifier.testTag("About.version"))
            Text(stringResource(R.string.about_protocol, stringResource(R.string.pohunek_protocol_tag)), modifier = Modifier.testTag("About.protocolTag"))
            Text(stringResource(R.string.about_notices), style = MaterialTheme.typography.titleMedium)
            when (val current = notices) {
                Notices.Loading -> Unit
                is Notices.Loaded -> Text(
                    current.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("About.notices"),
                )
                is Notices.Failed -> Text(
                    stringResource(R.string.about_notices_failed, current.reason),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("About.noticesError"),
                )
            }
        }
    }
}

// The package of the running app always exists; the fallback only keeps the screen readable.
private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
} catch (e: PackageManager.NameNotFoundException) {
    "unknown (${e.message})"
}

private suspend fun readNotices(context: Context): Notices = withContext(KoinPlatform.getKoin().get<CoroutineDispatcher>(ioDispatcher)) {
    try {
        Notices.Loaded(context.assets.open(NOTICES_ASSET).use { it.readBytes().decodeToString() })
    } catch (e: IOException) {
        Notices.Failed(e.message ?: e.javaClass.simpleName)
    }
}
