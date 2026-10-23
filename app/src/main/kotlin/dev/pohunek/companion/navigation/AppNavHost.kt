// Navigation host adapted from opencode-mobile's AppNavHost (MIT), see THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.pohunek.companion.config.RuntimeConfig
import dev.pohunek.companion.hosts.HostsScreen
import dev.pohunek.companion.hosts.HostsViewModel
import dev.pohunek.companion.sessions.SessionListScreen
import dev.pohunek.companion.sessions.SessionListViewModel
import dev.pohunek.companion.sessions.TerminalScreen
import dev.pohunek.companion.settings.AboutScreen
import dev.pohunek.companion.settings.SettingsScreen
import dev.pohunek.companion.settings.SettingsViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.mp.KoinPlatform

@Composable
fun AppNavHost(
    runtime: RuntimeConfig,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = StartDestination, modifier = modifier) {
        composable<StartDestination> {
            val koin = remember { KoinPlatform.getKoin() }
            val viewModel: SessionListViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SessionListViewModel(
                            koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get(),
                            runtime.configs.map { it.rowBadgeMaxChars }.distinctUntilChanged(),
                        )
                    }
                },
            )
            val detailValueMaxChars by remember { runtime.configs.map { it.detailValueMaxChars }.distinctUntilChanged() }
                .collectAsStateWithLifecycle(runtime.current().detailValueMaxChars)
            SessionListScreen(
                viewModel,
                detailValueMaxChars,
                onOpenTerminal = { route, sessionId ->
                    // Single-top: a double tap on a row must not stack two terminals.
                    navController.navigate(TerminalDestination(route.toString(), sessionId)) { launchSingleTop = true }
                },
                onOpenHosts = { navController.navigate(HostsDestination) { launchSingleTop = true } },
                onOpenSettings = { navController.navigate(SettingsDestination) { launchSingleTop = true } },
            )
        }
        composable<HostsDestination> {
            val koin = remember { KoinPlatform.getKoin() }
            val viewModel: HostsViewModel = viewModel(factory = viewModelFactory { initializer { HostsViewModel(koin.get(), koin.get(), koin.get()) } })
            HostsScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable<SettingsDestination> {
            val koin = remember { KoinPlatform.getKoin() }
            val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(runtime, koin.get(), koin.get()) } })
            SettingsScreen(
                viewModel,
                onOpenAbout = { navController.navigate(AboutDestination) { launchSingleTop = true } },
                onBack = { navController.popBackStack() },
            )
        }
        composable<AboutDestination> { AboutScreen(onBack = { navController.popBackStack() }) }
        composable<TerminalDestination> { entry ->
            val destination = entry.toRoute<TerminalDestination>()
            // The attach settings are read when a terminal is opened.
            TerminalScreen(runtime.current(), destination.route, destination.sessionId, onBack = { navController.popBackStack() })
        }
    }
}
