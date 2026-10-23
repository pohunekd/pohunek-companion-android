package dev.pohunek.companion

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.pohunek.companion.config.AppConfigResult
import dev.pohunek.companion.sessions.Centered
import dev.pohunek.companion.sessions.Message

/** Shown instead of the app when a value in `config.xml` is invalid; names the key. */
@Composable
fun ConfigErrorScreen(error: AppConfigResult.Invalid, modifier: Modifier = Modifier) {
    Centered(modifier.fillMaxSize()) {
        Message(stringResource(R.string.config_error, error.key, error.detail), "ConfigError.message", error = true)
    }
}
