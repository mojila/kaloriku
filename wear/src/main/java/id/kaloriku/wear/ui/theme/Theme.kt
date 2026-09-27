package id.kaloriku.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography

/** Watch palette: near-black base with a single green accent. */
private val WearScheme = ColorScheme(
    primary = Color(0xFF7FC79E),
    primaryDim = Color(0xFF4E8C68),
    primaryContainer = Color(0xFF1F3A2C),
    onPrimary = Color(0xFF0B1A12),
    onPrimaryContainer = Color(0xFFBFE6CF),
    secondary = Color(0xFFB9C7BE),
    secondaryDim = Color(0xFF8A968E),
    secondaryContainer = Color(0xFF2A2F2B),
    onSecondary = Color(0xFF14140F),
    onSecondaryContainer = Color(0xFFDCE6DF),
    tertiary = Color(0xFFD8C08A),
    tertiaryDim = Color(0xFF9C8B63),
    tertiaryContainer = Color(0xFF3A3320),
    onTertiary = Color(0xFF1A160A),
    onTertiaryContainer = Color(0xFFF0E2BE),
    surfaceContainerLow = Color(0xFF181816),
    surfaceContainer = Color(0xFF1C1C1A),
    surfaceContainerHigh = Color(0xFF262622),
    onSurface = Color(0xFFF0EEE9),
    onSurfaceVariant = Color(0xFF9B978E),
    outline = Color(0xFF4A4A44),
    outlineVariant = Color(0xFF32322D),
    background = Color(0xFF101010),
    onBackground = Color(0xFFF0EEE9),
    error = Color(0xFFE08A6E),
    errorDim = Color(0xFFB4654C),
    errorContainer = Color(0xFF3A231B),
    onError = Color(0xFF1A0E0A),
    onErrorContainer = Color(0xFFF3C7B8),
)

@Composable
fun KaloriWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WearScheme,
        typography = Typography(),
        content = content,
    )
}
