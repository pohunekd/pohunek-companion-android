// Token structure adapted from opencode-mobile's OcTheme (MIT), see THIRD_PARTY_NOTICES.md.
package dev.pohunek.companion.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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

/** Colours of the owner states, chosen for contrast against the surface of the scheme they belong to. */
@Immutable
class StatusColors(
    val needsOwner: Color,
    val working: Color,
    val idle: Color,
    val problem: Color,
    val ended: Color,
)

@Suppress("MagicNumber") // Colour literals are the token definitions.
private val LightStatusColors = StatusColors(
    needsOwner = Color(0xFFB45309),
    working = Color(0xFF1B6E3A),
    idle = Color(0xFF455A8A),
    problem = Color(0xFFB3261E),
    ended = Color(0xFF6B6672),
)

@Suppress("MagicNumber") // Colour literals are the token definitions.
private val DarkStatusColors = StatusColors(
    needsOwner = Color(0xFFFFB74D),
    working = Color(0xFF81C995),
    idle = Color(0xFF9DB4E8),
    problem = Color(0xFFF2B8B5),
    ended = Color(0xFFA9A4AD),
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** Entry point for design tokens beyond Material's scheme. */
object PohunekTheme {
    val spacing: PohunekSpacing get() = PohunekSpacing

    val statusColors: StatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStatusColors.current

    /** Diameter of the owner state dot on a row. */
    val stateDotSize: Dp = 10.dp

    /** Radius of one dot of the vertical "more" icon, and the distance between the centres of two dots. */
    val moreDotRadius: Dp = 2.dp
    val moreDotSpacing: Dp = 6.dp

    /** Opacity of content that may be out of date, such as session rows while the host is unreachable. */
    const val DIMMED_ALPHA: Float = 0.5f
}

/** Material 3 theme that follows the system light/dark setting unless [darkTheme] is given. */
@Composable
fun PohunekTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors, content = content)
    }
}
