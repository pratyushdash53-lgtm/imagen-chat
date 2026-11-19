package app.imagen.chat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF6C4DF6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECE8FF),
    onPrimaryContainer = Color(0xFF1E1250),
    secondary = Color(0xFF5B5575),
    onSecondary = Color.White,
    background = Color(0xFFFAF9FC),
    onBackground = Color(0xFF1B1A22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1A22),
    surfaceVariant = Color(0xFFF0EEF6),
    onSurfaceVariant = Color(0xFF5E5B6B),
    outline = Color(0xFFB9B5C7),
    outlineVariant = Color(0xFFE4E1EC),
    error = Color(0xFFD33A4F),
    onError = Color.White,
    errorContainer = Color(0xFFFFE3E6),
    onErrorContainer = Color(0xFF5C0A18),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7A6FF),
    onPrimary = Color(0xFF1C1040),
    primaryContainer = Color(0xFF2B2450),
    onPrimaryContainer = Color(0xFFE6E0FF),
    secondary = Color(0xFFC9C3DD),
    onSecondary = Color(0xFF312E41),
    background = Color(0xFF0E0E12),
    onBackground = Color(0xFFE8E6F0),
    surface = Color(0xFF17171D),
    onSurface = Color(0xFFE8E6F0),
    surfaceVariant = Color(0xFF22222B),
    onSurfaceVariant = Color(0xFFB2AFC0),
    outline = Color(0xFF6F6C7E),
    outlineVariant = Color(0xFF2E2D3A),
    error = Color(0xFFFF8A98),
    onError = Color(0xFF4A0612),
    errorContainer = Color(0xFF4A1B24),
    onErrorContainer = Color(0xFFFFDADF),
)

@Composable
fun ImagenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
