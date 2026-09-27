package id.kaloriku.phone.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A restrained palette: warm neutral base, one green accent, one terracotta alert.
 * 70% neutral surfaces, 20% muted text/containers, 10% accent.
 */
object KaloriColors {
    // Light
    val LightBackground = Color(0xFFFAF9F7)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceMuted = Color(0xFFF2F0EC)
    val LightOnSurface = Color(0xFF1B1B19)
    val LightMuted = Color(0xFF6E6B64)
    val LightHairline = Color(0xFFE6E2DB)
    val LightAccent = Color(0xFF2E6B4F)
    val LightAccentSoft = Color(0xFFE3EFE8)
    val LightAlert = Color(0xFFB8482A)
    val LightAlertSoft = Color(0xFFF7E4DC)

    // Dark
    val DarkBackground = Color(0xFF121211)
    val DarkSurface = Color(0xFF1C1C1A)
    val DarkSurfaceMuted = Color(0xFF262622)
    val DarkOnSurface = Color(0xFFF0EEE9)
    val DarkMuted = Color(0xFF9B978E)
    val DarkHairline = Color(0xFF32322D)
    val DarkAccent = Color(0xFF7FC79E)
    val DarkAccentSoft = Color(0xFF1F3A2C)
    val DarkAlert = Color(0xFFE08A6E)
    val DarkAlertSoft = Color(0xFF3A231B)

    // Meal colours — used only in the statistics chart, not as decoration.
    val MealSarapan = Color(0xFF3E7CB1)
    val MealSiang = Color(0xFF2E6B4F)
    val MealMalam = Color(0xFF8A6D3B)
    val MealCamilan = Color(0xFF9A5B7A)
}
