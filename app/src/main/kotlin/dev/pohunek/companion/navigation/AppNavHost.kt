// Navigation host adapted from opencode-mobile's AppNavHost (MIT), see THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.pohunek.companion.config.AppConfig
import dev.pohunek.companion.sessions.SessionListScreen
import dev.pohunek.companion.sessions.SessionListViewModel
import dev.pohunek.companion.sessions.TerminalScreen
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

@Composable
fun AppNavHost(
    config: AppConfig,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = StartDestination, modifier = modifier) {
        composable<StartDestination> {
            val koin = remember { KoinPlatform.getKoin() }
            val viewModel: SessionListViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { SessionListViewModel(koin.get(), koin.get { parametersOf(config.client) }) }
                },
            )
            SessionListScreen(viewModel, onOpenTerminal = { route, sessionId ->
                navController.navigate(TerminalDestination(route.toString(), sessionId))
            })
        }
        composable<TerminalDestination> { entry ->
            val destination = entry.toRoute<TerminalDestination>()
            TerminalScreen(config, destination.route, destination.sessionId, onBack = { navController.popBackStack() })
        }
    }
}
