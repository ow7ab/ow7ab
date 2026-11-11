package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val PmpLightColorScheme = lightColorScheme(
    primary = PmpBrandRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEE2E2),
    onPrimaryContainer = PmpBrandRed,
    secondary = PmpBrandBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = PmpBrandBlue,
    tertiary = PmpBrandGold,
    onTertiary = Color.White,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFE2E8F0),
    error = StatusDanger,
    onError = Color.White
)

/**
 * مخطط ألوان الوضع المظلم الأسود الفائق (True Pitch Black Dark Mode — AMOLED #000000)
 * يستبدل اللون الكحلي السابق باللون الأسود الصريح المريح للعين مع تباين واضح للبطاقات والتبويبات.
 */
private val PmpTrueBlackDarkColorScheme = darkColorScheme(
    primary = PmpBrandRedLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B1115),
    onPrimaryContainer = Color(0xFFFCA5A5),
    secondary = PmpBrandBlueLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF101F38),
    onSecondaryContainer = Color(0xFF93C5FD),
    tertiary = PmpBrandGoldLight,
    onTertiary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF0A0A0D),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF121216),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF27272A),
    error = Color(0xFFEF4444),
    onError = Color.White
)

@Composable
fun GlassDropTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        PmpThemeController.initialize(context, darkTheme)
    }
    LaunchedEffect(darkTheme, PmpThemeController.selectedThemeMode) {
        if (PmpThemeController.selectedThemeMode == PmpThemeMode.SYSTEM) {
            PmpThemeController.isDarkModeActive = darkTheme
        }
    }

    val useDark = PmpThemeController.isDarkModeActive
    val colorScheme = if (useDark) PmpTrueBlackDarkColorScheme else PmpLightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val barColor = if (useDark) Color(0xFF000000).toArgb() else Color(0xFFFFFFFF).toArgb()
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !useDark
            insetsController.isAppearanceLightNavigationBars = !useDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    GlassDropTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        content = content
    )
}

