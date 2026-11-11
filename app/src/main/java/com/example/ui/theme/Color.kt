package com.example.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.core.architecture.PmpThemeArchitectureContract

/**
 * أوضاع المظهر الاحترافي لتطبيق PMP PAINTS ERP:
 * - LIGHT: الوضع النهاري الأبيض المؤسسي الفاتح
 * - DARK: الوضع المظلم الأسود الفائق (True Pitch Black Dark Mode - AMOLED #000000)
 * - SYSTEM: تلقائي يتبع إعدادات نظام أندرويد في الهاتف
 */
enum class PmpThemeMode(val storageKey: String, val labelAr: String) {
    LIGHT("LIGHT", PmpThemeArchitectureContract.LIGHT_MODE_NAME_AR),
    DARK("DARK", "${PmpThemeArchitectureContract.TRUE_BLACK_MODE_NAME_AR} (True Black)"),
    SYSTEM("SYSTEM", PmpThemeArchitectureContract.SYSTEM_MODE_NAME_AR);

    val arabicLabel: String get() = labelAr

    companion object {
        fun fromKey(key: String?): PmpThemeMode {
            return entries.firstOrNull { it.storageKey.equals(key, ignoreCase = true) } ?: LIGHT
        }
    }
}

/**
 * محرك التحكم التفاعلي الفوري في الوضع المظلم الأسود (True Black Dark Mode Controller)
 * يتيح التبديل اللحظي بين الوضع النهاري والوضع المظلم الأسود دون الحاجة لإعادة تشغيل التطبيق.
 */
object PmpThemeController {
    private const val PREFS_NAME = "pmp_theme_preferences"
    private const val KEY_THEME_MODE = "selected_theme_mode"

    var selectedThemeMode by mutableStateOf(PmpThemeMode.LIGHT)
    var isDarkModeActive by mutableStateOf(false)

    var isDarkMode: Boolean
        get() = isDarkModeActive
        set(value) {
            updateDarkModeState(value)
        }

    fun updateDarkModeState(
        enabled: Boolean,
        mode: PmpThemeMode = if (enabled) PmpThemeMode.DARK else PmpThemeMode.LIGHT
    ) {
        selectedThemeMode = mode
        isDarkModeActive = enabled
    }

    fun arabicLabel(): String {
        return if (isDarkModeActive) {
            PmpThemeArchitectureContract.TRUE_BLACK_MODE_NAME_AR
        } else {
            PmpThemeArchitectureContract.LIGHT_MODE_NAME_AR
        }
    }

    fun initialize(context: Context, isSystemInDarkTheme: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_THEME_MODE, PmpThemeMode.LIGHT.storageKey)
        val mode = PmpThemeMode.fromKey(savedKey)
        selectedThemeMode = mode
        isDarkModeActive = when (mode) {
            PmpThemeMode.LIGHT -> false
            PmpThemeMode.DARK -> true
            PmpThemeMode.SYSTEM -> isSystemInDarkTheme
        }
    }

    fun setThemeMode(context: Context, mode: PmpThemeMode, isSystemInDarkTheme: Boolean = false) {
        selectedThemeMode = mode
        isDarkModeActive = when (mode) {
            PmpThemeMode.LIGHT -> false
            PmpThemeMode.DARK -> true
            PmpThemeMode.SYSTEM -> isSystemInDarkTheme
        }
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME_MODE, mode.storageKey)
                .apply()
        }
    }

    fun toggleDarkMode(context: Context) {
        val nextMode = if (isDarkModeActive) PmpThemeMode.LIGHT else PmpThemeMode.DARK
        setThemeMode(context, nextMode, isSystemInDarkTheme = nextMode == PmpThemeMode.DARK)
    }
}

typealias PmpThemeModeController = PmpThemeController

// ============================================================================
// PMP PAINTS — True Pitch Black Dark Mode ("الوضع المظلم الأسود") & Daylight Adaptive Palette
// ============================================================================

val PmpWhiteBg: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF000000) else Color(0xFFF0F4F8)

val PmpWhiteSurface: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF0A0A0D) else Color(0xFFFFFFFF)

val PmpWhiteCard: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF121216) else Color(0xFFF8FAFC)

val PmpWhiteBorder: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF27272A) else Color(0xFFDCE4EE)

/**
 * سطح فرعي متكيف للتبويبات غير النشطة، الفلاتر، وشرائح العملات والصلاحيات
 * يضمن عدم ظهور أي تبويب باللون الأبيض عند تفعيل الوضع المظلم الأسود
 */
val PmpSubtleSurface: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF18181D) else Color(0xFFF1F5F9)

val PmpContainerBarSurface: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF0E0E12) else Color(0xFFF8FAFC)

val PmpDangerSoftSurface: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF281115) else Color(0xFFFEF2F2)

val PmpSuccessSoftSurface: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF0B241A) else Color(0xFFF0FDF4)

val PmpWarningSoftSurface: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF271D0B) else Color(0xFFFFFBEB)

val PmpInfoSoftSurface: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF0F1D33) else Color(0xFFEFF6FF)

// Primary Brand Colors — PMP Red, True Black / Navy, Gold, Blue, Green
val PmpBrandRed = Color(0xFFDC2626)
val PmpBrandRedLight = Color(0xFFEF4444)
val PmpBrandRedSoft: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF2A1215) else Color(0xFFFEE2E2)

val PmpBrandNavy: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFFF8FAFC) else Color(0xFF0F294A)

val PmpBrandBlue = Color(0xFF1D4ED8)
val PmpBrandBlueLight = Color(0xFF3B82F6)
val PmpBrandBlueSoft: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF101F38) else Color(0xFFDBEAFE)

val PmpBrandGold = Color(0xFFD97706)
val PmpBrandGoldLight = Color(0xFFF59E0B)
val PmpBrandGoldSoft: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF271E0B) else Color(0xFFFEF3C7)

val PmpBrandGreen = Color(0xFF059669)
val PmpBrandOrange = Color(0xFFEA580C)

val PmpCyanGlow = Color(0xFF0891B2)
val PmpBlueElectric = Color(0xFF2563EB)
val PmpIndigoDeep = Color(0xFF4F46E5)

// Status Colors
val StatusSuccess = Color(0xFF16A34A)
val StatusSuccessSoft: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF0B261B) else Color(0xFFDCFCE7)

val StatusWarning = Color(0xFFD97706)
val StatusWarningSoft: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF291D0A) else Color(0xFFFEF3C7)

val StatusDanger = Color(0xFFDC2626)
val StatusDangerSoft: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF2B1115) else Color(0xFFFEE2E2)

val StatusInfo = Color(0xFF0284C7)
val StatusInfoSoft: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF0C2236) else Color(0xFFE0F2FE)

// Compatibility Aliases Across Existing Screens
val PmpNavyDeep: Color get() = PmpWhiteBg
val PmpNavySurface: Color get() = PmpWhiteSurface
val PmpNavyCard: Color get() = PmpWhiteCard
val PmpNavyBorder: Color get() = PmpWhiteBorder

val PmpBlue = Color(0xFF1D4ED8)
val PmpBlueLight = Color(0xFF2563EB)
val PmpCyan = Color(0xFF0284C7)
val PmpGold = Color(0xFFD97706)
val PmpGreen = Color(0xFF059669)
val PmpGreenLight = Color(0xFF10B981)
val PmpRed = PmpBrandRed
val PmpRedLight = Color(0xFFEF4444)
val PmpPurple = Color(0xFF6D28D9)

// Adaptive Glass & Surface Tokens (True Black in Dark Mode)
val GlassSurface: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF121216).copy(alpha = 0.96f) else Color(0xFFFFFFFF).copy(alpha = 0.94f)

val GlassSurfaceElevated: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF18181E) else Color(0xFFFFFFFF)

val GlassBorder: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF27272A) else Color(0xFFD5E0EC)

val GlassBorderHighlight: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF3F3F46) else Color(0xFFCBD5E1)

val GlassShadowColor: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF000000).copy(alpha = 0.75f) else Color(0xFF0F172A).copy(alpha = 0.06f)

val GlassInput: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF0D0D11) else Color(0xFFF3F7FB)

// Adaptive Text Hierarchy
val TextPrimary: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFFF8FAFC) else Color(0xFF0F172A)

val TextSecondary: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFFA1A1AA) else Color(0xFF334155)

val TextWhite: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFFF8FAFC) else Color(0xFF0F172A)

val TextMuted: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFFA1A1AA) else Color(0xFF334155)

val TextSubtle: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFF71717A) else Color(0xFF64748B)

val TextDark: Color
    get() = if (PmpThemeController.isDarkModeActive) Color(0xFFF8FAFC) else Color(0xFF0F172A)

val TextPureWhite = Color(0xFFFFFFFF)

// Gradients — True Pitch Black Dark Mode / Daylight Light Mode
val GlassBackgroundBrush: Brush
    get() = if (PmpThemeController.isDarkModeActive) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF000000),
                Color(0xFF050507),
                Color(0xFF000000)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFF4F7FB),
                Color(0xFFEAF0F7),
                Color(0xFFE2EAF4)
            )
        )
    }

val FrostedGlassGradient: Brush
    get() = if (PmpThemeController.isDarkModeActive) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF141418).copy(alpha = 0.98f),
                Color(0xFF0E0E12).copy(alpha = 0.96f),
                Color(0xFF0A0A0D).copy(alpha = 0.96f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.96f),
                Color(0xFFF8FBFF).copy(alpha = 0.90f),
                Color(0xFFF0F5FC).copy(alpha = 0.86f)
            )
        )
    }

val FrostedGlassBorderBrush: Brush
    get() = if (PmpThemeController.isDarkModeActive) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF27272A).copy(alpha = 0.85f),
                Color(0xFF18181B).copy(alpha = 0.70f),
                Color(0xFF27272A).copy(alpha = 0.80f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White,
                Color(0xFFD0DCEB).copy(alpha = 0.75f),
                Color(0xFFBFD0E4).copy(alpha = 0.55f)
            )
        )
    }

val GlassCardGradient: Brush
    get() = if (PmpThemeController.isDarkModeActive) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF18181D),
                Color(0xFF121216)
            )
        )
    } else {
        FrostedGlassGradient
    }

val GlassAccentBlueGradient: Brush
    get() = if (PmpThemeController.isDarkModeActive) {
        Brush.linearGradient(
            colors = listOf(Color(0xFF2563EB), Color(0xFF1D4ED8))
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color(0xFF0F294A), Color(0xFF1E3A8A))
        )
    }

val GlassAccentGreenGradient = Brush.linearGradient(
    colors = listOf(Color(0xFF059669), Color(0xFF047857))
)

val GlassAccentGoldGradient = Brush.linearGradient(
    colors = listOf(Color(0xFFD97706), Color(0xFFB45309))
)

val PmpBrandRedGradient = Brush.linearGradient(
    colors = listOf(Color(0xFFDC2626), Color(0xFF991B1B))
)

val PmpRedGradient = PmpBrandRedGradient

val PmpSuccessGradient = GlassAccentGreenGradient

val PmpLogoSwooshGradient = Brush.horizontalGradient(
    colors = listOf(Color(0xFFDC2626), Color(0xFFEA580C), Color(0xFFD97706))
)
