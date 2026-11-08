package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0A1D56),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F0FC),
    onPrimaryContainer = Color(0xFF0A1D56),
    secondary = PmpGoldLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = PmpGoldDark,
    tertiary = Color(0xFF0284C7),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5FA),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFE2E8F0),
    error = PmpRed,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = PmpGoldLight,
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF7C2D12),
    onSecondaryContainer = Color(0xFFFFEDD5),
    tertiary = Color(0xFF38BDF8),
    background = Color(0xFF0B1120),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    error = PmpRedLight,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = PmpThemeController.isDarkModeActive,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = when (PmpThemeController.selectedThemeMode) {
        PmpThemeMode.DARK -> true
        PmpThemeMode.LIGHT -> false
        PmpThemeMode.SYSTEM -> systemDark
    }
    if (PmpThemeController.isDarkModeActive != effectiveDark) {
        PmpThemeController.updateDarkModeState(effectiveDark, PmpThemeController.selectedThemeMode)
    }

    val colorScheme = if (effectiveDark) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val statusBg = if (effectiveDark) Color(0xFF0B1120) else Color(0xFFF8FAFC)
            val navBg = if (effectiveDark) Color(0xFF0F172A) else Color.White
            @Suppress("DEPRECATION")
            window.statusBarColor = statusBg.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = navBg.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !effectiveDark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !effectiveDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

