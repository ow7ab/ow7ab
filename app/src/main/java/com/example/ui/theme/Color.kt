package com.example.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class PmpThemeMode(val storageKey: String, val arabicLabel: String) {
    LIGHT("LIGHT", "فاتح (Light)"),
    DARK("DARK", "مظلم احترافي (Dark Pro)"),
    SYSTEM("SYSTEM", "تلقائي حسب النظام")
}

/**
 * متحكم عالمي تفاعلي بالوضع المظلم الاحترافي (Professional Dark Mode) في تطبيق PMP PAINTS
 */
object PmpThemeController {
    var isDarkModeActive by mutableStateOf(false)
        internal set

    var selectedThemeMode by mutableStateOf(PmpThemeMode.LIGHT)
        internal set

    fun updateDarkModeState(isDark: Boolean, mode: PmpThemeMode = selectedThemeMode) {
        isDarkModeActive = isDark
        selectedThemeMode = mode
    }
}

// PMP Brand Core Colors (Taken from Official Logo & Poster)
val PmpBrandRed = Color(0xFFC4121A)      // Signature Paint Can Crimson Red
val PmpBrandRedDark = Color(0xFF991B1E)
val PmpBrandGreen = Color(0xFF16A34A)    // From the "P" in the PMP logo
val PmpBrandOrange = Color(0xFFEA580C)   // From the "M" in the PMP logo
val PmpBrandMagenta = Color(0xFFC026D3)  // From the second "P" & swoosh
val PmpBrandNavy: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF93C5FD) else Color(0xFF0A1D56)
val PmpBrandGold = Color(0xFFEAB308)     // Paint wheel yellow/gold accent

// Compatibility mappings for ERP modules
val PmpBlue: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF60A5FA) else Color(0xFF0A1D56)
val PmpBlueLight = Color(0xFF3B82F6)
val PmpCyan: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF38BDF8) else Color(0xFF0284C7)
val PmpGold: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFFFB923C) else Color(0xFFEA580C)
val PmpGoldLight = Color(0xFFF97316)
val PmpGoldDark = Color(0xFFC2410C)
val PmpGreen: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF22C55E) else Color(0xFF16A34A)
val PmpGreenLight = Color(0xFF4ADE80)
val PmpRed = Color(0xFFC4121A)           // Brand Crimson
val PmpRedLight = Color(0xFFEF4444)
val PmpPurple = Color(0xFFC026D3)        // Brand Magenta

// Modern Adaptive Surfaces (Light vs Professional Obsidian Dark)
val PmpWhiteBg: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF0B1120) else Color(0xFFF8FAFC)
val PmpWhiteSurface: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF111827) else Color(0xFFFFFFFF)
val PmpWhiteCard: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF1E293B) else Color(0xFFFFFFFF)
val PmpWhiteBorder: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF334155) else Color(0xFFE2E8F0)

val PmpNavyDeep: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF090E1A) else Color(0xFFF8FAFC)
val PmpNavySurface: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF1E293B) else Color(0xFFFFFFFF)
val PmpNavyCard: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF0F172A) else Color(0xFFF1F5F9)
val PmpNavyBorder: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF334155) else Color(0xFFCBD5E1)

// Frosted Glass Tokens
val GlassSurface: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF1E293B).copy(alpha = 0.92f) else Color(0xFFFFFFFF).copy(alpha = 0.90f)
val GlassSurfaceElevated: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF1E293B).copy(alpha = 0.98f) else Color(0xFFFFFFFF).copy(alpha = 0.98f)
val GlassBorder: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF334155) else Color(0xFFE2E8F0)
val GlassHighlight: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF475569) else Color(0xFFFFFFFF)
val GlassInput: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF0F172A) else Color(0xFFF8FAFC)

// Text Colors (High Contrast Adaptive Slate / Crisp White)
val TextDark: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFFF8FAFC) else Color(0xFF0F172A)
val TextWhite: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFFF8FAFC) else Color(0xFF0F172A)
val TextPureWhite = Color(0xFFFFFFFF)
val TextMuted: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF94A3B8) else Color(0xFF475569)
val TextSubtle: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFFCBD5E1) else Color(0xFF64748B)

// Brand Gradients (Adaptive for Light & Dark Pro)
val GlassBackgroundBrush: Brush
    get() = if (PmpThemeController.isDarkModeActive) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF090E1A),
                Color(0xFF0F172A),
                Color(0xFF111C2D)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFFFFFF),
                Color(0xFFF8FAFC),
                Color(0xFFF1F5F9)
            )
        )
    }

val FrostedGlassGradient: Brush
    get() = if (PmpThemeController.isDarkModeActive) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF1E293B).copy(alpha = 0.95f),
                Color(0xFF162032).copy(alpha = 0.92f),
                Color(0xFF0F172A).copy(alpha = 0.95f)
            ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFFFFFFF).copy(alpha = 0.94f),
                Color(0xFFF8FAFC).copy(alpha = 0.88f),
                Color(0xFFF1F5F9).copy(alpha = 0.92f)
            ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    }

val FrostedGlassBorderBrush: Brush
    get() = if (PmpThemeController.isDarkModeActive) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF475569).copy(alpha = 0.75f),
                Color(0xFF334155).copy(alpha = 0.60f),
                Color(0xFF1E293B).copy(alpha = 0.80f)
            ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White,
                Color(0xFFE2E8F0),
                Color(0xFFCBD5E1).copy(alpha = 0.8f)
            ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    }

val GlassCardGradient: Brush
    get() = if (PmpThemeController.isDarkModeActive) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF1E293B),
                Color(0xFF0F172A)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFF1F5F9),
                Color(0xFFE2E8F0)
            )
        )
    }

// PMP Multi-Color Gradient matching the orbital swoosh & paint wheel
val PmpLogoSwooshGradient = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFFC026D3), // Magenta
        Color(0xFFEA580C), // Orange
        Color(0xFF16A34A), // Green
        Color(0xFF0284C7)  // Blue
    )
)

// PMP Red Can Gradient matching the brand poster
val PmpBrandRedGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFFD3151E),
        Color(0xFFC4121A),
        Color(0xFF8B0D13)
    )
)

val GlassAccentBlueGradient = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFF0A1D56),
        Color(0xFF1E40AF)
    )
)

val GlassAccentGoldGradient = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFFEA580C),
        Color(0xFFF97316)
    )
)

val GlassAccentGreenGradient = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFF16A34A),
        Color(0xFF22C55E)
    )
)

