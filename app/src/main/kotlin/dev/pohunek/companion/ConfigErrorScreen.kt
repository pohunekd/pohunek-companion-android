package dev.pohunek.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.pohunek.companion.config.ConfigState
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import dev.pohunek.companion.sessions.Centered
import dev.pohunek.companion.sessions.Message

/**
 * Shown instead of the app when a value is invalid; names the key. A saved override or settings file that is invalid can be discarded
 * with [onDiscardSaved], which reports a [discardFailure]; a default in `config.xml` cannot be changed from here.
 */
@Composable
fun ConfigErrorScreen(error: ConfigState.Invalid, discardFailure: String?, onDiscardSaved: () -> Unit, modifier: Modifier = Modifier) {
    Centered(modifier.fillMaxSize()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PohunekTheme.spacing.md)) {
            val message = if (error.fromOverrides) R.string.config_error_saved else R.string.config_error
            Message(stringResource(message, error.key, error.detail), "ConfigError.message", error = true)
            if (error.fromOverrides) {
                Button(onClick = onDiscardSaved, modifier = Modifier.testTag("ConfigError.discard")) { Text(stringResource(R.string.config_error_reset)) }
                discardFailure?.let { Message(stringResource(R.string.settings_storage_failed, it), "ConfigError.discardFailed", error = true) }
            }
        }
    }
}
