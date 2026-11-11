package com.nebi.promediadownloader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7C7FF5),
    onPrimary = Color.White,
    secondary = Color(0xFF8B5CF6),
    background = Color(0xFF070B16),
    onBackground = Color(0xFFE9EFFB),
    surface = Color(0xFF101A2D),
    onSurface = Color(0xFFE9EFFB),
    surfaceVariant = Color(0xFF18253F),
    onSurfaceVariant = Color(0xFF93A5C8),
    outline = Color(0xFF263654),
    outlineVariant = Color(0xFF1D2B46),
    error = Color(0xFFF87171),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B5BF5),
    onPrimary = Color.White,
    secondary = Color(0xFF7C5CF5),
    background = Color(0xFFEEF1F8),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEDF1FA),
    onSurfaceVariant = Color(0xFF55657F),
    outline = Color(0xFFD5DDEC),
    outlineVariant = Color(0xFFE3E9F5),
    error = Color(0xFFDC2626),
)

// Her stilde satır yüksekliği yazı boyutuna uygun; küçük yazılarda
// satırların birbirinden kopuk görünmesini engeller.
private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
    ),
)

@Composable
fun ProMediaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
