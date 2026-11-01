package com.example.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// PMP Brand Core Colors (Taken from Official Logo & Poster)
val PmpBrandRed = Color(0xFFC4121A)      // Signature Paint Can Crimson Red
val PmpBrandRedDark = Color(0xFF991B1E)
val PmpBrandGreen = Color(0xFF16A34A)    // From the "P" in the PMP logo
val PmpBrandOrange = Color(0xFFEA580C)   // From the "M" in the PMP logo
val PmpBrandMagenta = Color(0xFFC026D3)  // From the second "P" & swoosh
val PmpBrandNavy = Color(0xFF0A1D56)     // From "Paints" in the logo
val PmpBrandGold = Color(0xFFEAB308)     // Paint wheel yellow/gold accent

// Compatibility mappings for ERP modules
val PmpBlue = Color(0xFF0A1D56)          // Brand Navy
val PmpBlueLight = Color(0xFF1E40AF)
val PmpCyan = Color(0xFF0284C7)
val PmpGold = Color(0xFFEA580C)          // Brand Orange
val PmpGoldLight = Color(0xFFF97316)
val PmpGoldDark = Color(0xFFC2410C)
val PmpGreen = Color(0xFF16A34A)         // Brand Green
val PmpGreenLight = Color(0xFF22C55E)
val PmpRed = Color(0xFFC4121A)           // Brand Crimson
val PmpRedLight = Color(0xFFEF4444)
val PmpPurple = Color(0xFFC026D3)        // Brand Magenta

// Modern White / Light Glass Surfaces
val PmpWhiteBg = Color(0xFFF8FAFC)
val PmpWhiteSurface = Color(0xFFFFFFFF)
val PmpWhiteCard = Color(0xFFFFFFFF)
val PmpWhiteBorder = Color(0xFFE2E8F0)

val PmpNavyDeep = Color(0xFFF8FAFC)
val PmpNavySurface = Color(0xFFFFFFFF)
val PmpNavyCard = Color(0xFFF1F5F9)
val PmpNavyBorder = Color(0xFFCBD5E1)

// Frosted Glass Tokens
val GlassSurface = Color(0xFFFFFFFF).copy(alpha = 0.90f)
val GlassSurfaceElevated = Color(0xFFFFFFFF).copy(alpha = 0.98f)
val GlassBorder = Color(0xFFE2E8F0)
val GlassHighlight = Color(0xFFFFFFFF)
val GlassInput = Color(0xFFF8FAFC)

// Text Colors (High Contrast Slate Ink)
val TextDark = Color(0xFF0F172A)
val TextWhite = Color(0xFF0F172A)
val TextPureWhite = Color(0xFFFFFFFF)
val TextMuted = Color(0xFF475569)
val TextSubtle = Color(0xFF64748B)

// Brand Gradients
val GlassBackgroundBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFFFFFFFF),
        Color(0xFFF8FAFC),
        Color(0xFFF1F5F9)
    )
)

val FrostedGlassGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFFFFFFFF).copy(alpha = 0.94f),
        Color(0xFFF8FAFC).copy(alpha = 0.88f),
        Color(0xFFF1F5F9).copy(alpha = 0.92f)
    ),
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)

val FrostedGlassBorderBrush = Brush.linearGradient(
    colors = listOf(
        Color.White,
        Color(0xFFE2E8F0),
        Color(0xFFCBD5E1).copy(alpha = 0.8f)
    ),
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)

val GlassCardGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFFF1F5F9),
        Color(0xFFE2E8F0)
    )
)

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
