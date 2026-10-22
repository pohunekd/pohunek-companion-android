// Token structure adapted from opencode-mobile's OcTheme (MIT), see THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 4dp based spacing scale. */
@Immutable
object PohunekSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    /** Horizontal gutter for screen content. */
    val gutter: Dp = 16.dp
}

/** Entry point for design tokens beyond Material's scheme. */
object PohunekTheme {
    val spacing: PohunekSpacing get() = PohunekSpacing
}

/** Material 3 theme that follows the system light/dark setting unless [darkTheme] is given. */
@Composable
fun PohunekTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
