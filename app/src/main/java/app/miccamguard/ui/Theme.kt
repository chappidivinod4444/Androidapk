package app.miccamguard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object GuardColors {
    val Safe = Color(0xFF22C55E)
    val Danger = Color(0xFFFF5A5F)
    val Warn = Color(0xFFF59E0B)
    val HeroOnA = Color(0xFF0D9488)
    val HeroOnB = Color(0xFF0E7490)
    val HeroOffA = Color(0xFF475569)
    val HeroOffB = Color(0xFF334155)
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0F3B37),
    onPrimaryContainer = Color(0xFFA7F3E8),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF00212E),
    secondaryContainer = Color(0xFF16324A),
    onSecondaryContainer = Color(0xFFCFEAFB),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF1A2438),
    onSurfaceVariant = Color(0xFF9FB0C8),
    surfaceContainerLowest = Color(0xFF080E19),
    surfaceContainerLow = Color(0xFF0F1727),
    surfaceContainer = Color(0xFF121B2D),
    surfaceContainerHigh = Color(0xFF172135),
    surfaceContainerHighest = Color(0xFF1D283E),
    outline = Color(0xFF3A4A66),
    outlineVariant = Color(0xFF223049),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0A0F),
    errorContainer = Color(0xFF4A1620),
    onErrorContainer = Color(0xFFFFD9DC),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF053B36),
    secondary = Color(0xFF0369A1),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDF1FD),
    onSecondaryContainer = Color(0xFF07304A),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF4F7FB),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE5EBF3),
    onSurfaceVariant = Color(0xFF52627A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF2F8),
    surfaceContainerHighest = Color(0xFFE5EBF3),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFD6DEE9),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

@Composable
fun GuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
