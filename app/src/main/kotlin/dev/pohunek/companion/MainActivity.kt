// Edge-to-edge activity shell adapted from opencode-mobile's MainActivity (MIT), see THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pohunek.companion.config.ApplyResult
import dev.pohunek.companion.config.ConfigState
import dev.pohunek.companion.config.RuntimeConfig
import dev.pohunek.companion.core.ui.theme.PohunekTheme
import dev.pohunek.companion.navigation.AppNavHost
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PohunekTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .semantics { testTagsAsResourceId = true },
                ) {
                    val runtime = remember { KoinPlatform.getKoin().get<RuntimeConfig>() }
                    val scope = rememberCoroutineScope()
                    var discardFailure by remember { mutableStateOf<String?>(null) }
                    when (val state = runtime.state.collectAsStateWithLifecycle().value) {
                        is ConfigState.Valid -> AppNavHost(runtime)
                        is ConfigState.Invalid -> ConfigErrorScreen(
                            state,
                            discardFailure,
                            onDiscardSaved = { scope.launch { discardFailure = (runtime.apply(emptyMap()) as? ApplyResult.StorageFailed)?.reason } },
                        )
                    }
                }
            }
        }
    }
}
