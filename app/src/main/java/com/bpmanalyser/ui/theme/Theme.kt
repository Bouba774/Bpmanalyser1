package com.bpmanalyzer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Brand colors
val Indigo400 = Color(0xFF818CF8)
val Indigo500 = Color(0xFF6366F1)
val Indigo600 = Color(0xFF4F46E5)
val Cyan400 = Color(0xFF22D3EE)
val Emerald400 = Color(0xFF34D399)
val Rose400 = Color(0xFFFB7185)
val Amber400 = Color(0xFFFBBF24)
val Violet400 = Color(0xFFA78BFA)

val Surface900 = Color(0xFF0F172A)
val Surface800 = Color(0xFF1E293B)
val Surface700 = Color(0xFF334155)
val Surface200 = Color(0xFFE2E8F0)
val Surface100 = Color(0xFFF1F5F9)
val Surface50 = Color(0xFFF8FAFC)

private val DarkColorScheme = darkColorScheme(
    primary = Indigo400,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Indigo400,
    secondary = Cyan400,
    tertiary = Emerald400,
    background = Surface900,
    surface = Surface800,
    surfaceVariant = Surface700,
    onBackground = Color(0xFFF1F5F9),
    onSurface = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Rose400,
    outline = Color(0xFF475569)
)

private val LightColorScheme = lightColorScheme(
    primary = Indigo600,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF2FF),
    onPrimaryContainer = Indigo600,
    secondary = Color(0xFF0891B2),
    tertiary = Color(0xFF059669),
    background = Surface50,
    surface = Color.White,
    surfaceVariant = Surface100,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF64748B),
    error = Color(0xFFDC2626),
    outline = Color(0xFFCBD5E1)
)

@Composable
fun BPMAnalyzerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

fun bpmRangeColor(bpm: Double): Color {
    return when {
        bpm < 90 -> Cyan400
        bpm < 110 -> Emerald400
        bpm < 125 -> Amber400
        bpm < 140 -> Indigo400
        else -> Rose400
    }
}
