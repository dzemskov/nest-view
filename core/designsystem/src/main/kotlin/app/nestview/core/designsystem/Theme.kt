package app.nestview.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Status and sound colours, readable on black video. */
object MonitorColors {
    val Live = Color(0xFF4CD964)
    val Delayed = Color(0xFFFFB020)
    val Problem = Color(0xFFFF4D4D)
    val Offline = Color(0xFF9AA0A6)
    val Scrim = Color(0x99000000)
    val ScrimStrong = Color(0xCC000000)
    val SoundSoft = Color(0xFF4CD964)
    val SoundNoticeable = Color(0xFFFFB020)
    val SoundLoud = Color(0xFFFF4D4D)
}

private val NestViewColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF1F3A5F),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFB8C7DA),
    background = Color.Black,
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF111316),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF1E2227),
    onSurfaceVariant = Color(0xFFB9BEC6),
    surfaceContainer = Color(0xFF171A1E),
    surfaceContainerHigh = Color(0xFF1E2227),
    surfaceContainerHighest = Color(0xFF262A30),
    error = Color(0xFFFF8A80),
)

/** The app is dark-only: it's used in dark rooms at night and must never flash white. */
@Composable
fun NestViewTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NestViewColors, content = content)
}
