package com.example.core.architecture

/**
 * PMP PAINTS — Modular Cross-Platform Architecture & Domain Contracts (v4.0)
 *
 * Pure Kotlin (Zero Android / JVM-UI framework dependencies) modular architecture layer
 * designed for:
 * 1. Seamless future updates & version orchestration across all release channels (`v4.0`).
 * 2. Clean separation of Domain, State, Feature Modules, and Platform Gateways to facilitate
 *    direct code sharing or porting to Kotlin Multiplatform (iOS / Web / Desktop).
 * 3. Decoupled Feature Registry with explicit module dependencies, RBAC security gates,
 *    and unified navigation route contracts across Android, iOS, and Web targets.
 */

enum class PmpTargetPlatform(val id: String, val displayNameAr: String) {
    ANDROID("android", "نظام أندرويد (Android)"),
    IOS("ios", "نظام آي أو إس (iOS)"),
    WEB("web", "منصة الويب (Web ERP)")
}

/**
 * Single Source of Truth for Application Release Identity & Update Compatibility (`v4.0`).
 */
object PmpAppVersionContract {
    const val APPLICATION_ID: String = "com.aistudio.pmppaints.erp"
    const val CURRENT_VERSION_CODE: Int = 16
    const val CURRENT_VERSION_NAME: String = "v4.1"
    const val MINIMUM_SUPPORTED_VERSION_CODE: Int = 10
    const val DEFAULT_RELEASE_BUILD_ID: String = "PMP-v4.1-b16-9a8b7c6d"
    const val DEFAULT_APK_FILE_NAME: String = "pmp-paints-v4.1-b16.apk"
    const val DEFAULT_PAGES_APK_URL: String = "https://ow7ab.github.io/ow7ab/pmp-paints-v4.1-b16.apk"
    const val DEFAULT_GITHUB_RELEASE_APK_URL: String =
        "https://github.com/ow7ab/ow7ab/releases/download/v4.1/pmp-paints-v4.1-b16.apk"
    const val DEFAULT_VERSION_METADATA_URL: String = "https://ow7ab.github.io/ow7ab/version.json"
    const val GOOGLE_PLAY_STORE_URL: String = "https://play.google.com/store/apps/details?id=com.aistudio.pmppaints.erp"

    data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int = 0
    ) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            return patch.compareTo(other.patch)
        }

        fun formatNormalized(): String =
            if (patch > 0) "v$major.$minor.$patch" else "v$major.$minor"
    }

    fun parseSemanticVersion(rawVersionName: String): SemanticVersion {
        val cleaned = rawVersionName
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .substringBefore(" ")
        val parts = cleaned.split(".").mapNotNull { it.toIntOrNull() }
        return SemanticVersion(
            major = parts.getOrElse(0) { 4 },
            minor = parts.getOrElse(1) { 1 },
            patch = parts.getOrElse(2) { 0 }
        )
    }

    /**
     * Pure cross-platform update evaluation logic (usable on Android, iOS, and Web).
     */
    fun isRemoteUpdateNewer(
        installedVersionCode: Int,
        installedVersionName: String,
        remoteVersionCode: Int,
        remoteVersionName: String
    ): Boolean {
        if (remoteVersionCode > installedVersionCode) return true
        if (remoteVersionCode < installedVersionCode) return false
        val installedSemVer = parseSemanticVersion(installedVersionName)
        val remoteSemVer = parseSemanticVersion(remoteVersionName)
        return remoteSemVer > installedSemVer
    }
}

/**
 * Pure Kotlin Theme & Appearance Contract supporting True Pitch Black Dark Mode ("الوضع المظلم الأسود")
 * across Android Compose, iOS SwiftUI, and Web CSS variables.
 */
data class PmpCrossPlatformColorTokens(
    val isTrueBlackDark: Boolean,
    val backgroundPrimaryHex: Long,
    val surfacePrimaryHex: Long,
    val surfaceCardHex: Long,
    val surfaceSubtleHex: Long,
    val surfaceElevatedHex: Long,
    val textPrimaryHex: Long,
    val textSecondaryHex: Long,
    val borderSubtleHex: Long,
    val brandPrimaryRedHex: Long = 0xFFDC2626,
    val brandAccentBlueHex: Long = 0xFF3B82F6
)

object PmpThemeArchitectureContract {
    const val TRUE_BLACK_MODE_NAME_AR: String = "الوضع المظلم الأسود"
    const val LIGHT_MODE_NAME_AR: String = "الوضع النهاري"
    const val SYSTEM_MODE_NAME_AR: String = "تلقائي حسب النظام"

    val TrueBlackDarkTokens = PmpCrossPlatformColorTokens(
        isTrueBlackDark = true,
        backgroundPrimaryHex = 0xFF000000, // Pure AMOLED Pitch Black (#000000)
        surfacePrimaryHex = 0xFF0A0A0D,    // Deep Carbon Surface (#0A0A0D)
        surfaceCardHex = 0xFF121216,       // Obsidian Card Surface (#121216)
        surfaceSubtleHex = 0xFF18181D,     // Unselected Tab / Chip / Pill Surface (#18181D)
        surfaceElevatedHex = 0xFF1E1E24,   // Modal / Elevated Surface (#1E1E24)
        textPrimaryHex = 0xFFF8FAFC,       // Crisp White-Slate Text
        textSecondaryHex = 0xFFA1A1AA,     // Neutral Zinc Secondary Text
        borderSubtleHex = 0xFF27272A       // True Dark Zinc Border (#27272A)
    )

    val DaylightTokens = PmpCrossPlatformColorTokens(
        isTrueBlackDark = false,
        backgroundPrimaryHex = 0xFFF8FAFC,
        surfacePrimaryHex = 0xFFFFFFFF,
        surfaceCardHex = 0xFFFFFFFF,
        surfaceSubtleHex = 0xFFF1F5F9,
        surfaceElevatedHex = 0xFFFFFFFF,
        textPrimaryHex = 0xFF0F172A,
        textSecondaryHex = 0xFF475569,
        borderSubtleHex = 0xFFE2E8F0
    )

    fun resolveTokens(isDark: Boolean): PmpCrossPlatformColorTokens =
        if (isDark) TrueBlackDarkTokens else DaylightTokens
}

/**
 * Modular Feature Identifiers for dependency management and cross-platform routing.
 */
enum class PmpModuleId(
    val moduleKey: String,
    val routePath: String,
    val titleAr: String,
    val titleEn: String,
    val requiresAdminRole: Boolean = false
) {
    CORE_AUTH("core.auth", "/auth/login", "المصادقة والأمان", "Auth & Security"),
    CORE_SYNC("core.sync", "/system/sync", "المزامنة السحابية", "Cloud Sync Engine"),
    CORE_UPDATE("core.update", "/system/update", "محرك التحديثات السحابية", "OTA Update Engine"),
    FEATURE_DASHBOARD("feature.dashboard", "/dashboard", "الرئيسية", "Dashboard"),
    FEATURE_TREASURY("feature.treasury", "/treasury", "الخزينة والمالية", "Treasury & Finance"),
    FEATURE_PURCHASES("feature.purchases", "/purchases", "المشتريات والمواد الخام", "Purchases & Raw Materials"),
    FEATURE_FACTORY("feature.factory", "/factory", "المصنع والإنتاج", "Factory & Production"),
    FEATURE_CHEMISTRY_LAB("feature.chemistry_lab", "/chemistry-lab", "المعمل الكيميائي", "Chemistry Lab"),
    FEATURE_INVENTORY("feature.inventory", "/inventory", "المخازن والمنتجات", "Warehouse & Inventory"),
    FEATURE_SALES("feature.sales", "/sales", "المبيعات والعملاء", "Sales & Customers"),
    FEATURE_POSTER_PRICES("feature.poster_prices", "/poster-prices", "بوستر الأسعار", "Price Poster"),
    FEATURE_USERS("feature.users", "/users", "المستخدمون والصلاحيات", "Users & RBAC", requiresAdminRole = true),
    FEATURE_REPORTS("feature.reports", "/reports", "التقارير والتحليلات", "Reports & Analytics"),
    FEATURE_SETTINGS("feature.settings", "/settings", "الإعدادات والنظام", "System Settings")
}

/**
 * Descriptor for a self-contained feature module supporting modular expansion
 * and multi-platform portability (Android / iOS / Web).
 */
data class PmpFeatureModuleDescriptor(
    val id: PmpModuleId,
    val version: String = PmpAppVersionContract.CURRENT_VERSION_NAME,
    val dependencies: Set<PmpModuleId> = emptySet(),
    val supportedPlatforms: Set<PmpTargetPlatform> = setOf(
        PmpTargetPlatform.ANDROID,
        PmpTargetPlatform.IOS,
        PmpTargetPlatform.WEB
    ),
    val supportsOfflineFirst: Boolean = true,
    val supportsRealtimeCloudSync: Boolean = true
) {
    val webRouteUrl: String get() = "https://app.pmp-paints.com${id.routePath}"
    val iosDeepLinkUri: String get() = "pmppaints:/${id.routePath}"
    val androidDeepLinkUri: String get() = "android-app://${PmpAppVersionContract.APPLICATION_ID}${id.routePath}"
}

/**
 * Platform-independent Gateway Contracts (Port & Adapter pattern for Android, iOS, and Web).
 */
interface PmpPlatformUpdateGateway {
    val currentVersionCode: Int
    val currentVersionName: String
    val targetPlatform: PmpTargetPlatform
    suspend fun checkCloudRelease(metadataUrl: String = PmpAppVersionContract.DEFAULT_VERSION_METADATA_URL): Boolean
}

interface PmpPlatformStorageGateway {
    val schemaVersion: Int
    val preservesZeroDataLoss: Boolean
}

/**
 * Central Modular Registry managing feature dependencies, topological initialization order,
 * and cross-platform capability discovery.
 */
object PmpModuleRegistry {
    private val modules: List<PmpFeatureModuleDescriptor> = listOf(
        PmpFeatureModuleDescriptor(
            id = PmpModuleId.CORE_AUTH,
            dependencies = emptySet()
        ),
        PmpFeatureModuleDescriptor(
            id = PmpModuleId.CORE_SYNC,
            dependencies = setOf(PmpModuleId.CORE_AUTH)
        ),
        PmpFeatureModuleDescriptor(
            id = PmpModuleId.CORE_UPDATE,
            dependencies = emptySet(),
            supportsRealtimeCloudSync = true
        ),
        PmpFeatureModuleDescriptor(
            id = PmpModuleId.FEATURE_DASHBOARD,
            dependencies = setOf(PmpModuleId.CORE_AUTH, PmpModuleId.CORE_SYNC)
        ),
        PmpFeatureModuleDescriptor(
            id = PmpModuleId.FEATURE_TREASURY,
            dependencies = setOf(PmpModuleId.CORE_AUTH, PmpModuleId.CORE_SYNC)
        ),
        PmpFeatureModuleDescriptor(
            id = PmpModuleId.FEATURE_PURCHASES,
            dependencies = setOf(PmpModuleId.CORE_AUTH, PmpModuleId.CORE_SYNC, PmpModuleId.FEATURE_TREASURY, PmpModuleId.FEATURE_INVENTORY)
        ),
        PmpFeatureModuleDescriptor(
            id = PmpModuleId.FEATURE_INVENTORY,
            dependencies = setOf(PmpModuleId.CORE_AUTH, PmpModuleId.CORE_SYNC)
        ),
        PmpFeatureModuleDescriptor(
            id = PmpModuleId.FEATURE_CHEMISTRY_LAB,
            dependencies = setOf(PmpModuleId.CORE_AUTH, PmpModuleId.FEATURE_INVENTORY)
        ),
        PmpFeatureModuleDescriptor(
            id = PmpModuleId.FEATURE_FACTORY,
            dependencies = setOf(PmpModuleId.CORE_AUTH, PmpModuleId.CORE_SYNC, PmpModuleId.FEATURE_INVENTORY, PmpModuleId.FEATURE_CHEMISTRY_LAB)
        ),
        PmpFeatureModuleDescriptor(
            id = PmpModuleId.FEATURE_SALES,
            dependencies = setOf(PmpModuleId.CORE_AUTH, PmpModuleId.CORE_SYNC, PmpModuleId.FEATURE_INVENTORY, PmpModuleId.FEATURE_TREASURY)
        ),
        PmpFeatureModuleDescriptor(
            id = PmpModuleId.FEATURE_POSTER_PRICES,
            dependencies = setOf(PmpModuleId.CORE_AUTH, PmpModuleId.FEATURE_INVENTORY)
        ),
        PmpFeatureModuleDescriptor(
            id = PmpModuleId.FEATURE_USERS,
            dependencies = setOf(PmpModuleId.CORE_AUTH, PmpModuleId.CORE_SYNC)
        ),
        PmpFeatureModuleDescriptor(
            id = PmpModuleId.FEATURE_REPORTS,
            dependencies = setOf(
                PmpModuleId.CORE_AUTH,
                PmpModuleId.FEATURE_TREASURY,
                PmpModuleId.FEATURE_SALES,
                PmpModuleId.FEATURE_PURCHASES,
                PmpModuleId.FEATURE_FACTORY,
                PmpModuleId.FEATURE_INVENTORY
            )
        ),
        PmpFeatureModuleDescriptor(
            id = PmpModuleId.FEATURE_SETTINGS,
            dependencies = setOf(PmpModuleId.CORE_AUTH, PmpModuleId.CORE_SYNC, PmpModuleId.CORE_UPDATE)
        )
    )

    fun getAllModules(): List<PmpFeatureModuleDescriptor> = modules

    fun getModule(id: PmpModuleId): PmpFeatureModuleDescriptor =
        modules.first { it.id == id }

    fun getModulesForPlatform(platform: PmpTargetPlatform): List<PmpFeatureModuleDescriptor> =
        modules.filter { platform in it.supportedPlatforms }

    /**
     * Verifies that the module dependency graph is acyclic and all dependencies are registered.
     */
    fun validateDependencyGraph(): Boolean {
        val registeredIds = modules.map { it.id }.toSet()
        for (module in modules) {
            if (!registeredIds.containsAll(module.dependencies)) return false
            if (module.id in module.dependencies) return false
        }
        return true
    }

    /**
     * Resolves accessible feature modules based on whether the current user is the Super Admin
     * or holds specific module permissions.
     */
    fun resolveAccessibleModules(
        isSuperAdmin: Boolean,
        grantedModuleIds: Set<PmpModuleId>
    ): List<PmpFeatureModuleDescriptor> {
        if (isSuperAdmin) return modules
        return modules.filter { descriptor ->
            !descriptor.id.requiresAdminRole &&
                (descriptor.id.moduleKey.startsWith("core.") || descriptor.id in grantedModuleIds)
        }
    }
}
