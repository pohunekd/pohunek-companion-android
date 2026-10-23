package dev.pohunek.companion.sessions

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.pohunek.companion.R

/** The button that opens the new session form; it is shown while the list is. */
@Composable
internal fun NewSessionButton(shown: Boolean, onClick: () -> Unit) {
    if (shown) {
        ExtendedFloatingActionButton(onClick = onClick, modifier = Modifier.testTag("SessionList.newSession")) {
            Text(stringResource(R.string.new_session_fab))
        }
    }
}
