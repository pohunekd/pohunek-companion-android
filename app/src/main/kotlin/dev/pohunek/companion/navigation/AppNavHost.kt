// Navigation host adapted from opencode-mobile's AppNavHost (MIT), see THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.pohunek.companion.StartScreen

@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = StartDestination, modifier = modifier) {
        composable<StartDestination> { StartScreen() }
    }
}
