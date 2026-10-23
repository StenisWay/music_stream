package dev.musicstream.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Blue-black palette: near-black navy surfaces with an electric-blue accent.
private val NightBlack = Color(0xFF0A0E17)
private val Electric = Color(0xFF4D8DFF)

private val BlueBlackColors = darkColorScheme(
    primary = Electric,
    onPrimary = Color(0xFF001A40),
    primaryContainer = Color(0xFF173A70),
    onPrimaryContainer = Color(0xFFD6E3FF),
    inversePrimary = Color(0xFF2F5FC4),

    secondary = Color(0xFF8FB4FF),
    onSecondary = Color(0xFF0D2350),
    secondaryContainer = Color(0xFF1E2A44),
    onSecondaryContainer = Color(0xFFD8E2FF),

    tertiary = Color(0xFF5ED4F5),
    onTertiary = Color(0xFF00313D),
    tertiaryContainer = Color(0xFF0E4A5A),
    onTertiaryContainer = Color(0xFFB8EEFF),

    background = NightBlack,
    onBackground = Color(0xFFE3E8F2),
    surface = NightBlack,
    onSurface = Color(0xFFE3E8F2),
    surfaceVariant = Color(0xFF1C2433),
    onSurfaceVariant = Color(0xFF9AA6BC),
    surfaceTint = Electric,
    inverseSurface = Color(0xFFE3E8F2),
    inverseOnSurface = Color(0xFF1A1F2A),

    surfaceDim = NightBlack,
    surfaceBright = Color(0xFF2A3346),
    surfaceContainerLowest = Color(0xFF06090F),
    surfaceContainerLow = Color(0xFF0E131E),
    surfaceContainer = Color(0xFF121826),
    surfaceContainerHigh = Color(0xFF182030),
    surfaceContainerHighest = Color(0xFF1F283A),

    outline = Color(0xFF3A4558),
    outlineVariant = Color(0xFF263042),
    scrim = Color.Black,
)

/** Material 3 theme; always the blue-black palette (no light mode, no wallpaper colours). */
@Composable
fun MusicStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BlueBlackColors, content = content)
}
