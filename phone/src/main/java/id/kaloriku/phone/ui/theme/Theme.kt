package id.kaloriku.phone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightScheme = lightColorScheme(
    primary = KaloriColors.LightAccent,
    onPrimary = Color.White,
    primaryContainer = KaloriColors.LightAccentSoft,
    onPrimaryContainer = KaloriColors.LightAccent,
    secondary = KaloriColors.LightMuted,
    onSecondary = Color.White,
    error = KaloriColors.LightAlert,
    onError = Color.White,
    errorContainer = KaloriColors.LightAlertSoft,
    onErrorContainer = KaloriColors.LightAlert,
    background = KaloriColors.LightBackground,
    onBackground = KaloriColors.LightOnSurface,
    surface = KaloriColors.LightSurface,
    onSurface = KaloriColors.LightOnSurface,
    surfaceVariant = KaloriColors.LightSurfaceMuted,
    onSurfaceVariant = KaloriColors.LightMuted,
    outline = KaloriColors.LightHairline,
    outlineVariant = KaloriColors.LightHairline,
)

private val DarkScheme = darkColorScheme(
    primary = KaloriColors.DarkAccent,
    onPrimary = Color(0xFF0B1A12),
    primaryContainer = KaloriColors.DarkAccentSoft,
    onPrimaryContainer = KaloriColors.DarkAccent,
    secondary = KaloriColors.DarkMuted,
    onSecondary = Color(0xFF101010),
    error = KaloriColors.DarkAlert,
    onError = Color(0xFF1A0E0A),
    errorContainer = KaloriColors.DarkAlertSoft,
    onErrorContainer = KaloriColors.DarkAlert,
    background = KaloriColors.DarkBackground,
    onBackground = KaloriColors.DarkOnSurface,
    surface = KaloriColors.DarkSurface,
    onSurface = KaloriColors.DarkOnSurface,
    surfaceVariant = KaloriColors.DarkSurfaceMuted,
    onSurfaceVariant = KaloriColors.DarkMuted,
    outline = KaloriColors.DarkHairline,
    outlineVariant = KaloriColors.DarkHairline,
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun KaloriKuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}
