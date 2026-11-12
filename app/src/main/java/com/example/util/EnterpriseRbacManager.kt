package com.example.util

import android.content.Context
import com.example.data.local.UserAccountEntity
import com.example.isSuperAdminAccount
import com.example.isSystemManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PHASE 03: ENTERPRISE RBAC & EMPLOYEE MANAGEMENT
 *
 * منظومة التحكم الدقيق في الصلاحيات المبنية على الأدوار (Granular Role-Based Access Control):
 * 1. دعم الصلاحيات التفصيلية الـ 11:
 *    - View (عرض)
 *    - Create (إنشاء)
 *    - Edit (تعديل)
 *    - Delete (حذف)
 *    - Approve (اعتماد)
 *    - Export (تصدير)
 *    - Print (طباعة)
 *    - Financial operations (العمليات المالية)
 *    - Inventory operations (عمليات المخزون)
 *    - User management (إدارة المستخدمين)
 *    - Administrative settings (الإعدادات الإدارية)
 * 2. الحفاظ الكامل على هيكل الوحدات والأقسام الحالية:
 *    - Factory (المصنع)
 *    - Warehouses (المخازن)
 *    - Production (الإنتاج)
 *    - Raw materials (مواد الإنتاج / المواد الخام)
 *    - Packaging (التعبئة والتغليف)
 *    - Accounting (المحاسبة / الخزن / المبيعات / التقارير)
 *    - Customers (العملاء)
 *    - Suppliers (الموردين / المشتريات)
 * 3. دعم الصلاحيات المؤقتة الاختيارية بتاريخ انتهاء (Temporary Permissions with Expiration Dates).
 * 4. سجل تاريخ تعديلات الصلاحيات (Permission Change History Audit Trail).
 * 5. عدم منح أي مدير أو مسؤول صلاحيات مطلقة تلقائياً (Scoped Administrator Isolation).
 * 6. رفض طلبات الواجهة الخلفية غير المصرح بها (Backend API / Repository Request Rejection).
 */
object EnterpriseRbacManager {

    private const val PREFS_NAME = "pmp_enterprise_rbac_v3_prefs"
    private const val KEY_AUDIT_HISTORY = "rbac_permission_change_history_v3"
    private const val KEY_TEMP_GRANTS = "rbac_temporary_grants_v3"
    private const val KEY_REJECTED_REQUESTS = "rbac_rejected_backend_requests_v3"

    /**
     * الوحدات الأساسية المعتمدة في نظام PMP PAINTS
     */
    enum class ErpModule(
        val canonicalArabicName: String,
        val englishName: String,
        val aliases: Set<String>
    ) {
        FACTORY(
            canonicalArabicName = "المصنع",
            englishName = "Factory",
            aliases = setOf("المصنع", "Factory", "FACTORY")
        ),
        WAREHOUSES(
            canonicalArabicName = "المخازن",
            englishName = "Warehouses",
            aliases = setOf("المخازن", "Warehouses", "WAREHOUSES", "INVENTORY")
        ),
        PRODUCTION(
            canonicalArabicName = "الإنتاج",
            englishName = "Production",
            aliases = setOf("الإنتاج", "Production", "PRODUCTION", "أوامر الإنتاج")
        ),
        RAW_MATERIALS(
            canonicalArabicName = "مواد الإنتاج",
            englishName = "Raw materials",
            aliases = setOf("مواد الإنتاج", "المواد الخام", "Raw materials", "RAW_MATERIALS", "مخزن الخام")
        ),
        PACKAGING(
            canonicalArabicName = "التعبئة والتغليف",
            englishName = "Packaging",
            aliases = setOf("التعبئة والتغليف", "Packaging", "PACKAGING")
        ),
        ACCOUNTING(
            canonicalArabicName = "المحاسبة",
            englishName = "Accounting",
            aliases = setOf("المحاسبة", "Accounting", "ACCOUNTING")
        ),
        CUSTOMERS(
            canonicalArabicName = "العملاء",
            englishName = "Customers",
            aliases = setOf("العملاء", "Customers", "CUSTOMERS", "مديونيات العملاء")
        ),
        SUPPLIERS(
            canonicalArabicName = "الموردين",
            englishName = "Suppliers",
            aliases = setOf("الموردين", "المشتريات", "Suppliers", "SUPPLIERS", "PURCHASES")
        ),
        CHEMISTRY_LAB(
            canonicalArabicName = "المختبر الكيميائي",
            englishName = "Chemistry Lab",
            aliases = setOf("المختبر الكيميائي", "Chemistry Lab", "CHEMISTRY_LAB")
        ),
        POSTER_PRICES(
            canonicalArabicName = "الأسعار",
            englishName = "Prices",
            aliases = setOf("الأسعار", "Prices", "POSTER")
        ),
        USERS(
            canonicalArabicName = "المستخدمين",
            englishName = "Users",
            aliases = setOf("المستخدمين", "إدارة المستخدمين", "Users", "USERS")
        ),
        SETTINGS(
            canonicalArabicName = "الإعدادات",
            englishName = "Settings",
            aliases = setOf("الإعدادات", "الإعدادات الإدارية", "Settings", "SETTINGS")
        );

        companion object {
            fun fromNameOrAlias(name: String): ErpModule? {
                val clean = name.trim()
                if (clean.isEmpty()) return null
                return entries.firstOrNull { module ->
                    module.canonicalArabicName.equals(clean, ignoreCase = true) ||
                        module.englishName.equals(clean, ignoreCase = true) ||
                        module.aliases.any { it.equals(clean, ignoreCase = true) }
                }
            }
        }
    }

    /**
     * رموز الصلاحيات التفصيلية القابلة للتخصيص لكل موظف على حدة
     */
    object PermissionTokens {
        // Module tokens
        const val MODULE_FACTORY = "المصنع"
        const val MODULE_WAREHOUSES = "المخازن"
        const val MODULE_PRODUCTION = "الإنتاج"
        const val MODULE_RAW_MATERIALS = "مواد الإنتاج"
        const val MODULE_PACKAGING = "التعبئة والتغليف"
        const val MODULE_ACCOUNTING = "المحاسبة"
        const val MODULE_TREASURY = "الخزن"
        const val MODULE_SALES = "المبيعات"
        const val MODULE_TRANSFERS = "التحويلات"
        const val MODULE_CUSTOMERS = "العملاء"
        const val MODULE_SUPPLIERS = "الموردين"
        const val MODULE_PURCHASES = "المشتريات"
        const val MODULE_CHEMISTRY_LAB = "المختبر الكيميائي"
        const val MODULE_PRICES = "الأسعار"
        const val MODULE_REPORTS = "التقارير"
        const val MODULE_REPORTS_FULL = "التقارير (كاملة)"
        const val MODULE_USERS = "المستخدمين"
        const val MODULE_SETTINGS = "الإعدادات"

        // Granular Action tokens (11 core capabilities)
        const val ACTION_VIEW = "صلاحية العرض"
        const val ACTION_CREATE = "صلاحية الإنشاء"
        const val ACTION_EDIT = "صلاحية التعديل"
        const val ACTION_DELETE = "صلاحية الحذف والتصفير"
        const val ACTION_APPROVE = "اعتماد المستندات"
        const val ACTION_EXPORT = "تصدير PDF"
        const val ACTION_PRINT = "طباعة المستندات"
        const val ACTION_FINANCIAL_OPS = "العمليات المالية"
        const val ACTION_INVENTORY_OPS = "عمليات المخزون"
        const val ACTION_USER_MANAGEMENT = "إدارة المستخدمين"
        const val ACTION_ADMIN_SETTINGS = "الإعدادات الإدارية"

        // Scoped admin marker (prevents automatic blanket manager access)
        const val SCOPED_ADMIN_MARKER = "SCOPED_ADMIN"

        val ALL_CORE_MODULES: List<String> = listOf(
            MODULE_FACTORY,
            MODULE_WAREHOUSES,
            MODULE_PRODUCTION,
            MODULE_RAW_MATERIALS,
            MODULE_PACKAGING,
            MODULE_ACCOUNTING,
            MODULE_TREASURY,
            MODULE_SALES,
            MODULE_TRANSFERS,
            MODULE_CUSTOMERS,
            MODULE_SUPPLIERS,
            MODULE_PURCHASES,
            MODULE_CHEMISTRY_LAB,
            MODULE_PRICES,
            MODULE_USERS,
            MODULE_SETTINGS
        )

        val ALL_GRANULAR_ACTION_TOKENS: List<String> = listOf(
            ACTION_VIEW,
            ACTION_CREATE,
            ACTION_EDIT,
            ACTION_DELETE,
            ACTION_APPROVE,
            ACTION_EXPORT,
            ACTION_PRINT,
            ACTION_FINANCIAL_OPS,
            ACTION_INVENTORY_OPS,
            ACTION_USER_MANAGEMENT,
            ACTION_ADMIN_SETTINGS
        )

        fun tokenForAction(action: ErpActionPermission): String = when (action) {
            ErpActionPermission.VIEW -> ACTION_VIEW
            ErpActionPermission.CREATE, ErpActionPermission.SUBMIT -> ACTION_CREATE
            ErpActionPermission.EDIT -> ACTION_EDIT
            ErpActionPermission.DELETE, ErpActionPermission.REVERSE -> ACTION_DELETE
            ErpActionPermission.APPROVE, ErpActionPermission.REJECT -> ACTION_APPROVE
            ErpActionPermission.EXPORT -> ACTION_EXPORT
            ErpActionPermission.PRINT -> ACTION_PRINT
            ErpActionPermission.FINANCIAL_OPERATIONS -> ACTION_FINANCIAL_OPS
            ErpActionPermission.INVENTORY_OPERATIONS,
            ErpActionPermission.RECEIVE,
            ErpActionPermission.ISSUE,
            ErpActionPermission.TRANSFER -> ACTION_INVENTORY_OPS
            ErpActionPermission.USER_MANAGEMENT -> ACTION_USER_MANAGEMENT
            ErpActionPermission.ADMINISTRATIVE_SETTINGS,
            ErpActionPermission.MANAGE_SETTINGS -> ACTION_ADMIN_SETTINGS
        }
    }

    /**
     * منحة صلاحية مؤقتة بتاريخ انتهاء محدد
     */
    data class TemporaryPermissionGrant(
        val grantId: String = "TMP-${UUID.randomUUID().toString().take(8).uppercase()}",
        val targetUserCode: String,
        val permissionToken: String,
        val grantedBy: String,
        val grantedAtMillis: Long = System.currentTimeMillis(),
        val expiresAtMillis: Long,
        val reason: String = "صلاحية مؤقتة معتمدة"
    ) {
        fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
            return nowMillis > expiresAtMillis
        }

        fun isActive(nowMillis: Long = System.currentTimeMillis()): Boolean {
            return !isExpired(nowMillis)
        }

        fun toInlineToken(): String {
            val safeToken = permissionToken.replace("|", "/").replace(",", ";").replace("[", "(").replace("]", ")")
            val safeBy = grantedBy.replace("|", "/").replace(",", ";").replace("[", "(").replace("]", ")")
            val safeReason = reason.replace("|", "/").replace(",", ";").replace("[", "(").replace("]", ")")
            return "TEMP[$safeToken|$expiresAtMillis|$safeBy|$safeReason|$grantId]"
        }

        companion object {
            fun fromInlineToken(rawToken: String, fallbackUserCode: String = ""): TemporaryPermissionGrant? {
                val trimmed = rawToken.trim()
                if (!trimmed.startsWith("TEMP[") || !trimmed.endsWith("]")) return null
                val inner = trimmed.removePrefix("TEMP[").removeSuffix("]")
                val parts = inner.split("|")
                if (parts.size < 2) return null
                val perm = parts[0].trim()
                val expiresAt = parts[1].trim().toLongOrNull() ?: return null
                val grantedBy = parts.getOrNull(2)?.trim()?.ifBlank { "Admin" } ?: "Admin"
                val reason = parts.getOrNull(3)?.trim()?.ifBlank { "صلاحية مؤقتة" } ?: "صلاحية مؤقتة"
                val grantId = parts.getOrNull(4)?.trim()?.ifBlank { "TMP-$expiresAt" } ?: "TMP-$expiresAt"
                if (perm.isEmpty()) return null
                return TemporaryPermissionGrant(
                    grantId = grantId,
                    targetUserCode = fallbackUserCode,
                    permissionToken = perm,
                    grantedBy = grantedBy,
                    grantedAtMillis = (expiresAt - 86_400_000L).coerceAtLeast(0L),
                    expiresAtMillis = expiresAt,
                    reason = reason
                )
            }
        }
    }

    /**
     * سجل تدقيق وتاريخ تغييرات الصلاحيات (Permission Change History Entry)
     */
    data class PermissionChangeAuditEntry(
        val entryId: String = "RBAC-LOG-${UUID.randomUUID().toString().take(8).uppercase()}",
        val targetUserCode: String,
        val targetUsername: String,
        val targetFullName: String,
        val actorUserCode: String,
        val actorUsername: String,
        val actorRole: String,
        val previousRole: String,
        val newRole: String,
        val previousPermissions: String,
        val newPermissions: String,
        val addedPermissions: Set<String>,
        val removedPermissions: Set<String>,
        val temporaryGrantsSummary: String = "",
        val reason: String = "تحديث صلاحيات الموظف",
        val changedAtMillis: Long = System.currentTimeMillis()
    ) {
        fun formattedTimestamp(): String {
            return runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(changedAtMillis))
            }.getOrDefault(changedAtMillis.toString())
        }
    }

    /**
     * قرار التحقق من طلب الواجهة الخلفية / المستودع / السحابة
     */
    data class BackendAccessDecision(
        val allowed: Boolean,
        val statusCode: Int,
        val errorCode: String,
        val message: String,
        val actorUsername: String,
        val module: String,
        val action: ErpActionPermission,
        val timestampMillis: Long = System.currentTimeMillis()
    )

    private val auditHistoryMemory = CopyOnWriteArrayList<PermissionChangeAuditEntry>()
    private val temporaryGrantsMemory = CopyOnWriteArrayList<TemporaryPermissionGrant>()
    private val rejectedRequestsMemory = CopyOnWriteArrayList<BackendAccessDecision>()

    /**
     * التحقق مما إذا كان الحساب هو مسؤول مخصص النطاق (Scoped Administrator)
     * لا يحصل المسؤول المخصص على صلاحيات شاملة تلقائياً بل يقتصر على ما مُنح له صراحةً.
     */
    fun isScopedAdministrator(user: UserAccountEntity?): Boolean {
        if (user == null) return false
        if (user.isSuperAdminAccount()) return false
        val role = user.role.trim()
        val rawPerms = user.permissions
        return rawPerms.contains(PermissionTokens.SCOPED_ADMIN_MARKER) ||
            role.contains("مخصص") ||
            role.contains("مقيد") ||
            role.contains("فرع") ||
            role.contains("قطاع") ||
            role.contains("مسؤول نظام") ||
            role.contains("مسؤول صلاحيات") ||
            role.equals("Scoped Admin", ignoreCase = true)
    }

    /**
     * التحقق مما إذا كان المستخدم مخولاً بإدارة وتكوين صلاحيات الموظفين:
     * 1. حساب SuperAdmin الأساسي (`admin`) مخول دائماً.
     * 2. المسؤول المفوض صراحةً بصلاحية `إدارة المستخدمين` (`ACTION_USER_MANAGEMENT`) مخول بتكوين صلاحيات الموظفين
     *    دون المساس بحساب `admin` ودون منح صلاحيات لا يملكها هو نفسه.
     * 3. المدير العام الافتراضي (`مدير النظام` بدون تفويض `إدارة المستخدمين`) لا يملك تعديل الصلاحيات تلقائياً.
     */
    fun canConfigureEmployeePermissions(
        actor: UserAccountEntity?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (actor == null || !actor.active) return false
        if (actor.syncStatus == "PENDING_MANAGER_APPROVAL") return false
        if (actor.isSuperAdminAccount()) return true
        val explicitTokens = extractActiveRawTokens(actor, nowMillis)
        val hasExplicitUserManagement = PermissionTokens.ACTION_USER_MANAGEMENT in explicitTokens ||
            "ACTION:USER_MANAGEMENT" in explicitTokens ||
            "GRANT:المستخدمين:USER_MANAGEMENT" in explicitTokens
        val isDeniedUserManagement = "DENY:${PermissionTokens.ACTION_USER_MANAGEMENT}" in explicitTokens ||
            "DENY:USER_MANAGEMENT" in explicitTokens ||
            "DENY:المستخدمين:USER_MANAGEMENT" in explicitTokens
        return hasExplicitUserManagement && !isDeniedUserManagement
    }

    /**
     * استخراج الرموز النصية الفعالة فقط (مع استبعاد الصلاحيات المؤقتة المنتهية الصلاحية تلقائياً)
     */
    fun extractActiveRawTokens(
        user: UserAccountEntity?,
        nowMillis: Long = System.currentTimeMillis()
    ): Set<String> {
        if (user == null || !user.active) return emptySet()
        val result = LinkedHashSet<String>()

        user.permissions.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { token ->
                if (token.startsWith("TEMP[")) {
                    val tempGrant = TemporaryPermissionGrant.fromInlineToken(token, user.userCode)
                    if (tempGrant != null && tempGrant.isActive(nowMillis)) {
                        result.add(tempGrant.permissionToken)
                    }
                } else {
                    result.add(token)
                }
            }

        return result
    }

    /**
     * الحصول على قائمة المنح المؤقتة النشطة للموظف
     */
    fun getActiveTemporaryGrants(
        user: UserAccountEntity?,
        nowMillis: Long = System.currentTimeMillis()
    ): List<TemporaryPermissionGrant> {
        if (user == null) return emptyList()
        return user.permissions.split(",")
            .mapNotNull { TemporaryPermissionGrant.fromInlineToken(it.trim(), user.userCode) }
            .filter { it.isActive(nowMillis) }
            .distinctBy { "${it.permissionToken}_${it.expiresAtMillis}" }
    }

    /**
     * الحصول على قائمة المنح المؤقتة المنتهية للموظف
     */
    fun getExpiredTemporaryGrants(
        user: UserAccountEntity?,
        nowMillis: Long = System.currentTimeMillis()
    ): List<TemporaryPermissionGrant> {
        if (user == null) return emptyList()
        return user.permissions.split(",")
            .mapNotNull { TemporaryPermissionGrant.fromInlineToken(it.trim(), user.userCode) }
            .filter { it.isExpired(nowMillis) }
            .distinctBy { "${it.permissionToken}_${it.expiresAtMillis}" }
    }

    /**
     * إضافة صلاحية مؤقتة بتاريخ انتهاء إلى نص صلاحيات الموظف وتسجيلها في السجل
     */
    fun grantTemporaryPermission(
        actor: UserAccountEntity?,
        targetUser: UserAccountEntity,
        permissionToken: String,
        expiresAtMillis: Long,
        reason: String = "تفويض مؤقت للعمل",
        nowMillis: Long = System.currentTimeMillis(),
        context: Context? = null
    ): Result<UserAccountEntity> {
        if (!canConfigureEmployeePermissions(actor, nowMillis)) {
            return Result.failure(SecurityException("غير مصرح لك بمنح صلاحيات مؤقتة للموظفين"))
        }
        if (targetUser.isSuperAdminAccount() && !actor.isSuperAdminAccount()) {
            return Result.failure(SecurityException("لا يمكن تعديل صلاحيات الحساب الرئيسي Admin"))
        }
        if (expiresAtMillis <= nowMillis) {
            return Result.failure(IllegalArgumentException("تاريخ انتهاء الصلاحية المؤقتة يجب أن يكون في المستقبل"))
        }
        val cleanToken = permissionToken.trim()
        if (cleanToken.isEmpty()) {
            return Result.failure(IllegalArgumentException("يرجى تحديد الصلاحية المراد منحها مؤقتاً"))
        }

        val grant = TemporaryPermissionGrant(
            targetUserCode = targetUser.userCode.ifBlank { targetUser.username },
            permissionToken = cleanToken,
            grantedBy = actor?.username ?: "admin",
            grantedAtMillis = nowMillis,
            expiresAtMillis = expiresAtMillis,
            reason = reason.trim().ifBlank { "تفويض مؤقت" }
        )
        temporaryGrantsMemory.add(grant)

        val existingTokens = targetUser.permissions.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        existingTokens.add(grant.toInlineToken())
        val updatedPermissionsString = existingTokens.distinct().joinToString(",")
        val updatedUser = targetUser.copy(permissions = updatedPermissionsString)

        recordPermissionChange(
            actor = actor,
            targetUser = targetUser,
            newRole = targetUser.role,
            newPermissions = updatedPermissionsString,
            reason = "منح صلاحية مؤقتة ($cleanToken): ${grant.reason}",
            changedAtMillis = nowMillis,
            context = context
        )
        if (context != null) {
            persistToPreferences(context)
        }
        return Result.success(updatedUser)
    }

    /**
     * تنظيف وإزالة الصلاحيات المؤقتة المنتهية من نص الصلاحيات عند الحاجة
     */
    fun pruneExpiredInlineTokens(
        permissionsString: String,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        return permissionsString.split(",")
            .map { it.trim() }
            .filter { token ->
                if (token.isEmpty()) return@filter false
                if (token.startsWith("TEMP[")) {
                    val parsed = TemporaryPermissionGrant.fromInlineToken(token)
                    parsed != null && parsed.isActive(nowMillis)
                } else {
                    true
                }
            }
            .joinToString(",")
    }

    /**
     * تنسيق نص الصلاحيات للعرض البشري الواضح في بطاقة الموظف (مع توضيح الصلاحيات المؤقتة النشطة)
     */
    fun formatCleanPermissionsForDisplay(
        permissionsString: String,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val permanent = mutableListOf<String>()
        val activeTemp = mutableListOf<String>()
        val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.US)

        permissionsString.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { token ->
                if (token.startsWith("TEMP[")) {
                    val grant = TemporaryPermissionGrant.fromInlineToken(token)
                    if (grant != null && grant.isActive(nowMillis)) {
                        val expText = runCatching { dateFormat.format(Date(grant.expiresAtMillis)) }.getOrDefault("")
                        activeTemp.add("${grant.permissionToken} (مؤقت حتى $expText)")
                    }
                } else if (!token.startsWith("DENY:") && token != PermissionTokens.SCOPED_ADMIN_MARKER) {
                    permanent.add(token)
                }
            }

        return (permanent + activeTemp).joinToString(" • ")
    }

    /**
     * التحقق مما إذا كان الموظف يملك صلاحية الوصول إلى وحدة (Module) معينة (لإظهار أو إخفاء التبويبات)
     */
    fun hasModuleAccess(
        user: UserAccountEntity?,
        moduleTitle: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (moduleTitle == "الرئيسية") return true
        if (user == null || !user.active) return false
        if (user.syncStatus == "PENDING_MANAGER_APPROVAL") return false
        if (user.isSuperAdminAccount()) return true

        val activeTokens = extractActiveRawTokens(user, nowMillis)

        // التحقق من وجود حظر صريح للوحدة
        if ("DENY:$moduleTitle" in activeTokens || "DENY:$moduleTitle:VIEW" in activeTokens) {
            return false
        }

        // المدير العام غير المقيد (System Manager) يرى الوحدات التشغيلية المعتمدة
        if (user.isSystemManager() && !isScopedAdministrator(user)) {
            return true
        }

        if (moduleTitle == "التقارير") {
            return "التقارير" in activeTokens ||
                "التقارير (كاملة)" in activeTokens ||
                "المحاسبة" in activeTokens ||
                activeTokens.any { it.startsWith("تقارير ") }
        }
        if (moduleTitle == "الخزن") {
            return "الخزن" in activeTokens || "المحاسبة" in activeTokens || "العمليات المالية" in activeTokens
        }
        if (moduleTitle == "المبيعات") {
            return "المبيعات" in activeTokens || "المحاسبة" in activeTokens
        }
        if (moduleTitle == "التحويلات") {
            return "التحويلات" in activeTokens || "المحاسبة" in activeTokens
        }

        val canonical = ErpModule.fromNameOrAlias(moduleTitle)
        if (canonical != null) {
            // مطابقة مباشرة أو عبر المرادفات المعتمدة للوحدة
            if (activeTokens.any { token -> canonical.aliases.any { alias -> alias.equals(token, ignoreCase = true) } }) {
                return true
            }
            // علاقات الوحدات الفرعية المحفوظة من النظام الحالي:
            return when (canonical) {
                ErpModule.FACTORY -> "المصنع" in activeTokens || "الإنتاج" in activeTokens || "مواد الإنتاج" in activeTokens || "التعبئة والتغليف" in activeTokens
                ErpModule.PRODUCTION -> "الإنتاج" in activeTokens || "المصنع" in activeTokens
                ErpModule.RAW_MATERIALS -> "مواد الإنتاج" in activeTokens || "المواد الخام" in activeTokens || "المصنع" in activeTokens
                ErpModule.PACKAGING -> "التعبئة والتغليف" in activeTokens || "المصنع" in activeTokens
                ErpModule.WAREHOUSES -> "المخازن" in activeTokens || "عمليات المخزون" in activeTokens
                ErpModule.ACCOUNTING -> "المحاسبة" in activeTokens || "الخزن" in activeTokens || "المبيعات" in activeTokens || "العمليات المالية" in activeTokens
                ErpModule.CUSTOMERS -> "العملاء" in activeTokens || "المبيعات" in activeTokens || "المحاسبة" in activeTokens || "تقارير المديونيات" in activeTokens
                ErpModule.SUPPLIERS -> "الموردين" in activeTokens || "المشتريات" in activeTokens || (moduleTitle == "المشتريات" && "مواد الإنتاج" in activeTokens)
                ErpModule.USERS -> "المستخدمين" in activeTokens || PermissionTokens.ACTION_USER_MANAGEMENT in activeTokens
                ErpModule.SETTINGS -> "الإعدادات" in activeTokens || PermissionTokens.ACTION_ADMIN_SETTINGS in activeTokens
                else -> moduleTitle in activeTokens
            }
        }

        return moduleTitle in activeTokens || "GRANT:$moduleTitle:VIEW" in activeTokens
    }

    /**
     * التحقق الدقيق من الصلاحيات (Granular Action + Module Permission Check)
     * يدعم كافة الصلاحيات الـ 11 وجميع الوحدات الـ 8 مع مراعاة الصلاحيات المؤقتة وعزل المسؤولين المخصصين.
     */
    fun hasGranularPermission(
        user: UserAccountEntity?,
        module: String,
        action: ErpActionPermission,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (user == null || !user.active) return false
        if (user.syncStatus == "PENDING_MANAGER_APPROVAL") return false
        if (user.isSuperAdminAccount()) return true

        val activeTokens = extractActiveRawTokens(user, nowMillis)
        val actionToken = PermissionTokens.tokenForAction(action)
        val canonicalModule = ErpModule.fromNameOrAlias(module)
        val canonicalModuleName = canonicalModule?.canonicalArabicName ?: module.trim()

        // 1. فحص الحظر الصريح (Explicit Deny) على مستوى العملية أو الوحدة+العملية
        if ("DENY:${action.name}" in activeTokens ||
            "DENY:$actionToken" in activeTokens ||
            "DENY:$module:${action.name}" in activeTokens ||
            "DENY:$canonicalModuleName:${action.name}" in activeTokens ||
            "DENY:$module:$actionToken" in activeTokens ||
            "DENY:$canonicalModuleName:$actionToken" in activeTokens
        ) {
            return false
        }

        // 2. فحص المنح الصريح لوحدة+عملية محددة (Explicit Module-Action Grant)
        if ("GRANT:$module:${action.name}" in activeTokens ||
            "GRANT:$canonicalModuleName:${action.name}" in activeTokens ||
            "GRANT:$module:$actionToken" in activeTokens ||
            "GRANT:$canonicalModuleName:$actionToken" in activeTokens
        ) {
            return true
        }

        // 3. حماية صارمة لإدارة المستخدمين والإعدادات الإدارية الحساسة
        if (action == ErpActionPermission.USER_MANAGEMENT ||
            (canonicalModule == ErpModule.USERS && action in listOf(
                ErpActionPermission.CREATE,
                ErpActionPermission.EDIT,
                ErpActionPermission.DELETE,
                ErpActionPermission.APPROVE,
                ErpActionPermission.MANAGE_SETTINGS,
                ErpActionPermission.ADMINISTRATIVE_SETTINGS,
                ErpActionPermission.USER_MANAGEMENT
            ))
        ) {
            return canConfigureEmployeePermissions(user, nowMillis)
        }

        if (action == ErpActionPermission.MANAGE_SETTINGS || action == ErpActionPermission.ADMINISTRATIVE_SETTINGS) {
            return PermissionTokens.ACTION_ADMIN_SETTINGS in activeTokens ||
                "ACTION:ADMINISTRATIVE_SETTINGS" in activeTokens ||
                "ACTION:MANAGE_SETTINGS" in activeTokens
        }

        // 4. المدير العام للنظام (غير المقيد بـ SCOPED_ADMIN) يملك الإشراف التشغيلي الكامل ما عدا إدارة المستخدمين والإعدادات الحساسة
        if (user.isSystemManager() && !isScopedAdministrator(user)) {
            return true
        }

        // 5. للمسؤولين المخصصين (Scoped Admins) والمحاسبين وأمناء المخازن والموظفين:
        // يجب أولاً التحقق من صلاحية الوصول للوحدة المطلوبة (أو كون العملية عامة مثل الاعتماد/التصدير/الطباعة)
        val hasModule = hasModuleAccess(user, module, nowMillis) ||
            (module == "الاعتماد" && (PermissionTokens.ACTION_APPROVE in activeTokens || user.role.contains("مدير") || user.role.contains("جودة")))

        val role = user.role

        return when (action) {
            ErpActionPermission.VIEW -> hasModule

            ErpActionPermission.CREATE, ErpActionPermission.SUBMIT -> {
                if (!hasModule) false
                else if ("VIEW_ONLY" in activeTokens || "عرض فقط" in activeTokens || "DENY:CREATE" in activeTokens) false
                else true
            }

            ErpActionPermission.EDIT -> {
                if (!hasModule) false
                else if ("VIEW_ONLY" in activeTokens || "عرض فقط" in activeTokens || "DENY:EDIT" in activeTokens) false
                else if (module == "نوع الصنف" || module == "تعديل نوع الصنف") {
                    "تعديل نوع الصنف" in activeTokens || PermissionTokens.ACTION_EDIT in activeTokens
                } else {
                    true
                }
            }

            ErpActionPermission.DELETE, ErpActionPermission.REVERSE -> {
                hasModule && (
                    PermissionTokens.ACTION_DELETE in activeTokens ||
                        "صلاحية الحذف" in activeTokens ||
                        "ACTION:DELETE" in activeTokens
                    )
            }

            ErpActionPermission.APPROVE, ErpActionPermission.REJECT -> {
                val canApprove = PermissionTokens.ACTION_APPROVE in activeTokens ||
                    "صلاحية الاعتماد" in activeTokens ||
                    "ACTION:APPROVE" in activeTokens ||
                    (!isScopedAdministrator(user) && (role.contains("مدير") || role.contains("جودة")))
                if (module == "الاعتماد") canApprove else (hasModule && canApprove)
            }

            ErpActionPermission.EXPORT -> {
                PermissionTokens.ACTION_EXPORT in activeTokens ||
                    "صلاحية التصدير" in activeTokens ||
                    "ACTION:EXPORT" in activeTokens ||
                    PermissionTokens.MODULE_REPORTS_FULL in activeTokens
            }

            ErpActionPermission.PRINT -> {
                PermissionTokens.ACTION_PRINT in activeTokens ||
                    "صلاحية الطباعة" in activeTokens ||
                    "ACTION:PRINT" in activeTokens ||
                    PermissionTokens.ACTION_EXPORT in activeTokens ||
                    ("المبيعات" in activeTokens && canonicalModule in setOf(ErpModule.ACCOUNTING, ErpModule.CUSTOMERS, null))
            }

            ErpActionPermission.FINANCIAL_OPERATIONS -> {
                val hasFinancialToken = PermissionTokens.ACTION_FINANCIAL_OPS in activeTokens ||
                    "ACTION:FINANCIAL_OPERATIONS" in activeTokens ||
                    "الخزن" in activeTokens ||
                    "المحاسبة" in activeTokens ||
                    "المبيعات" in activeTokens ||
                    "التحويلات" in activeTokens
                hasFinancialToken && (
                    hasModule ||
                        canonicalModule in setOf(ErpModule.ACCOUNTING, ErpModule.CUSTOMERS, ErpModule.SUPPLIERS)
                    )
            }

            ErpActionPermission.INVENTORY_OPERATIONS,
            ErpActionPermission.RECEIVE,
            ErpActionPermission.ISSUE -> {
                val hasInventoryToken = PermissionTokens.ACTION_INVENTORY_OPS in activeTokens ||
                    "ACTION:INVENTORY_OPERATIONS" in activeTokens ||
                    "المخازن" in activeTokens ||
                    "مواد الإنتاج" in activeTokens ||
                    "المصنع" in activeTokens ||
                    "التعبئة والتغليف" in activeTokens ||
                    (!isScopedAdministrator(user) && role.contains("أمين مخزن"))
                hasInventoryToken && (
                    hasModule ||
                        canonicalModule in setOf(
                            ErpModule.WAREHOUSES,
                            ErpModule.RAW_MATERIALS,
                            ErpModule.FACTORY,
                            ErpModule.PRODUCTION,
                            ErpModule.PACKAGING,
                            ErpModule.SUPPLIERS
                        )
                    )
            }

            ErpActionPermission.TRANSFER -> {
                "التحويلات" in activeTokens ||
                    "المخازن" in activeTokens ||
                    PermissionTokens.ACTION_INVENTORY_OPS in activeTokens
            }

            ErpActionPermission.USER_MANAGEMENT -> canConfigureEmployeePermissions(user, nowMillis)

            ErpActionPermission.ADMINISTRATIVE_SETTINGS,
            ErpActionPermission.MANAGE_SETTINGS -> {
                PermissionTokens.ACTION_ADMIN_SETTINGS in activeTokens ||
                    "ACTION:ADMINISTRATIVE_SETTINGS" in activeTokens
            }
        }
    }

    /**
     * التحقق من طلبات الواجهة الخلفية / المستودع / السحابة ورفض أي عملية غير مصرح بها
     */
    fun validateBackendRequest(
        user: UserAccountEntity?,
        module: String,
        action: ErpActionPermission,
        nowMillis: Long = System.currentTimeMillis(),
        context: Context? = null
    ): BackendAccessDecision {
        if (user == null) {
            val decision = BackendAccessDecision(
                allowed = false,
                statusCode = 401,
                errorCode = "ERR_RBAC_UNAUTHENTICATED",
                message = "تم رفض الطلب من الخادم: يجب تسجيل الدخول أولاً لتنفيذ (${action.arabicLabel}) في قسم ($module).",
                actorUsername = "anonymous",
                module = module,
                action = action,
                timestampMillis = nowMillis
            )
            recordRejectedRequest(decision, context)
            return decision
        }

        if (!user.active || user.syncStatus == "PENDING_MANAGER_APPROVAL") {
            val decision = BackendAccessDecision(
                allowed = false,
                statusCode = 403,
                errorCode = "ERR_RBAC_ACCOUNT_INACTIVE",
                message = "تم رفض الطلب من الخادم: الحساب (${user.username}) غير مفعل أو بانتظار اعتماد الإدارة.",
                actorUsername = user.username,
                module = module,
                action = action,
                timestampMillis = nowMillis
            )
            recordRejectedRequest(decision, context)
            return decision
        }

        val permitted = hasGranularPermission(user, module, action, nowMillis)
        if (!permitted) {
            val decision = BackendAccessDecision(
                allowed = false,
                statusCode = 403,
                errorCode = "ERR_RBAC_FORBIDDEN",
                message = "تم رفض الطلب الأمني (403 Forbidden): الحساب (${user.username} - ${user.role}) لا يملك صلاحية (${action.arabicLabel}) على وحدة ($module).",
                actorUsername = user.username,
                module = module,
                action = action,
                timestampMillis = nowMillis
            )
            recordRejectedRequest(decision, context)
            return decision
        }

        return BackendAccessDecision(
            allowed = true,
            statusCode = 200,
            errorCode = "OK",
            message = "مصرح بتمرير العملية",
            actorUsername = user.username,
            module = module,
            action = action,
            timestampMillis = nowMillis
        )
    }

    /**
     * فرض التحقق الأمني للواجهة الخلفية وإلقاء SecurityException عند الرفض
     */
    fun enforceBackendPermissionOrThrow(
        user: UserAccountEntity?,
        module: String,
        action: ErpActionPermission,
        nowMillis: Long = System.currentTimeMillis(),
        context: Context? = null
    ): BackendAccessDecision {
        val decision = validateBackendRequest(user, module, action, nowMillis, context)
        if (!decision.allowed) {
            throw SecurityException(decision.message)
        }
        return decision
    }

    /**
     * تكوين وتحديث صلاحيات موظف فردي بواسطة مسؤول مخول مع منع تصعيد الصلاحيات وتسجيل التاريخ
     */
    fun configureEmployeePermissions(
        actor: UserAccountEntity?,
        targetUser: UserAccountEntity,
        newRole: String,
        newPermissions: String,
        reason: String = "تحديث صلاحيات الموظف من لوحة الصلاحيات",
        nowMillis: Long = System.currentTimeMillis(),
        context: Context? = null
    ): Result<UserAccountEntity> {
        if (!canConfigureEmployeePermissions(actor, nowMillis)) {
            val denied = BackendAccessDecision(
                allowed = false,
                statusCode = 403,
                errorCode = "ERR_RBAC_USER_MGMT_FORBIDDEN",
                message = "تم رفض الطلب: الحساب (${actor?.username ?: "unknown"}) غير مخول بتعديل صلاحيات الموظفين.",
                actorUsername = actor?.username ?: "unknown",
                module = "المستخدمين",
                action = ErpActionPermission.USER_MANAGEMENT,
                timestampMillis = nowMillis
            )
            recordRejectedRequest(denied, context)
            return Result.failure(SecurityException(denied.message))
        }

        // حماية الحساب الرئيسي Admin من أي تعديل بواسطة مسؤول مفوض غير Admin
        if (targetUser.isSuperAdminAccount() && !actor.isSuperAdminAccount()) {
            val denied = BackendAccessDecision(
                allowed = false,
                statusCode = 403,
                errorCode = "ERR_RBAC_SUPERADMIN_PROTECTED",
                message = "تم رفض الطلب: لا يمكن للمسؤول المفوض تعديل الحساب الرئيسي Admin.",
                actorUsername = actor?.username ?: "unknown",
                module = "المستخدمين",
                action = ErpActionPermission.USER_MANAGEMENT,
                timestampMillis = nowMillis
            )
            recordRejectedRequest(denied, context)
            return Result.failure(SecurityException(denied.message))
        }

        // منع تصعيد الصلاحيات: المسؤول المفوض (غير SuperAdmin) لا يمكنه منح صلاحية الإعدادات الإدارية إذا لم يكن يملكها
        if (!actor.isSuperAdminAccount()) {
            val actorTokens = extractActiveRawTokens(actor, nowMillis)
            val targetTokens = newPermissions.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (PermissionTokens.ACTION_ADMIN_SETTINGS in targetTokens &&
                PermissionTokens.ACTION_ADMIN_SETTINGS !in actorTokens
            ) {
                return Result.failure(
                    SecurityException("تم رفض الطلب: لا يمكن للمسؤول المفوض منح صلاحية (الإعدادات الإدارية) لأنه لا يملكها.")
                )
            }
        }

        val cleanRole = newRole.trim().ifBlank { targetUser.role }
        val cleanPermissions = newPermissions.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",")

        val updatedUser = targetUser.copy(
            role = cleanRole,
            permissions = cleanPermissions
        )

        recordPermissionChange(
            actor = actor,
            targetUser = targetUser,
            newRole = cleanRole,
            newPermissions = cleanPermissions,
            reason = reason,
            changedAtMillis = nowMillis,
            context = context
        )

        return Result.success(updatedUser)
    }

    /**
     * تسجيل عملية تغيير الصلاحيات في سجل التدقيق التاريخي (Permission Change History)
     */
    fun recordPermissionChange(
        actor: UserAccountEntity?,
        targetUser: UserAccountEntity,
        newRole: String,
        newPermissions: String,
        reason: String = "تحديث صلاحيات الموظف",
        changedAtMillis: Long = System.currentTimeMillis(),
        context: Context? = null
    ): PermissionChangeAuditEntry {
        val oldSet = targetUser.permissions.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val newSet = newPermissions.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val added = newSet - oldSet
        val removed = oldSet - newSet
        val tempSummary = newSet.mapNotNull { TemporaryPermissionGrant.fromInlineToken(it, targetUser.userCode) }
            .joinToString("; ") { "${it.permissionToken}@${it.expiresAtMillis}" }

        val entry = PermissionChangeAuditEntry(
            targetUserCode = targetUser.userCode.ifBlank { "USR-${targetUser.id}" },
            targetUsername = targetUser.username,
            targetFullName = targetUser.fullName,
            actorUserCode = actor?.userCode?.ifBlank { "USR-ADMIN" } ?: "USR-ADMIN",
            actorUsername = actor?.username?.ifBlank { "admin" } ?: "admin",
            actorRole = actor?.role?.ifBlank { "مدير النظام" } ?: "مدير النظام",
            previousRole = targetUser.role,
            newRole = newRole.trim().ifBlank { targetUser.role },
            previousPermissions = targetUser.permissions,
            newPermissions = newPermissions,
            addedPermissions = added,
            removedPermissions = removed,
            temporaryGrantsSummary = tempSummary,
            reason = reason.trim().ifBlank { "تحديث صلاحيات الموظف" },
            changedAtMillis = changedAtMillis
        )
        auditHistoryMemory.add(0, entry)
        while (auditHistoryMemory.size > 250) {
            auditHistoryMemory.removeAt(auditHistoryMemory.lastIndex)
        }
        if (context != null) {
            persistToPreferences(context)
        }
        return entry
    }

    private fun recordRejectedRequest(decision: BackendAccessDecision, context: Context? = null) {
        rejectedRequestsMemory.add(0, decision)
        while (rejectedRequestsMemory.size > 200) {
            rejectedRequestsMemory.removeAt(rejectedRequestsMemory.lastIndex)
        }
        if (context != null) {
            persistToPreferences(context)
        }
    }

    /**
     * استرجاع سجل تغييرات الصلاحيات بالكامل أو لموظف محدد
     */
    fun getPermissionChangeHistory(
        targetUserCodeOrUsername: String? = null,
        context: Context? = null
    ): List<PermissionChangeAuditEntry> {
        if (context != null && auditHistoryMemory.isEmpty()) {
            loadFromPreferences(context)
        }
        val all = auditHistoryMemory.toList()
        if (targetUserCodeOrUsername.isNullOrBlank()) return all
        val key = targetUserCodeOrUsername.trim()
        return all.filter {
            it.targetUserCode.equals(key, ignoreCase = true) ||
                it.targetUsername.equals(key, ignoreCase = true)
        }
    }

    /**
     * استرجاع سجل الطلبات الخلفية المرفوضة أمنياً
     */
    fun getRejectedBackendRequests(): List<BackendAccessDecision> {
        return rejectedRequestsMemory.toList()
    }

    /**
     * مسح السجلات المؤقتة في الذاكرة (مخصص لتهيئة اختبارات الوحدة)
     */
    fun resetMemoryStateForTests() {
        auditHistoryMemory.clear()
        temporaryGrantsMemory.clear()
        rejectedRequestsMemory.clear()
    }

    /**
     * حفظ واسترجاع السجل في SharedPreferences بصيغة نصية آمنة (بدون الاعتماد على org.json في اختبارات JVM)
     */
    fun persistToPreferences(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val serializedHistory = auditHistoryMemory.take(150).joinToString("\n") { e ->
                listOf(
                    e.entryId.escapeField(),
                    e.targetUserCode.escapeField(),
                    e.targetUsername.escapeField(),
                    e.targetFullName.escapeField(),
                    e.actorUserCode.escapeField(),
                    e.actorUsername.escapeField(),
                    e.actorRole.escapeField(),
                    e.previousRole.escapeField(),
                    e.newRole.escapeField(),
                    e.previousPermissions.escapeField(),
                    e.newPermissions.escapeField(),
                    e.addedPermissions.joinToString(";").escapeField(),
                    e.removedPermissions.joinToString(";").escapeField(),
                    e.temporaryGrantsSummary.escapeField(),
                    e.reason.escapeField(),
                    e.changedAtMillis.toString()
                ).joinToString("\t")
            }
            prefs.edit().putString(KEY_AUDIT_HISTORY, serializedHistory).apply()
        }
    }

    fun loadFromPreferences(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_AUDIT_HISTORY, "").orEmpty()
            if (raw.isBlank()) return
            val loaded = raw.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val p = line.split("\t")
                    if (p.size < 16) return@mapNotNull null
                    PermissionChangeAuditEntry(
                        entryId = p[0].unescapeField(),
                        targetUserCode = p[1].unescapeField(),
                        targetUsername = p[2].unescapeField(),
                        targetFullName = p[3].unescapeField(),
                        actorUserCode = p[4].unescapeField(),
                        actorUsername = p[5].unescapeField(),
                        actorRole = p[6].unescapeField(),
                        previousRole = p[7].unescapeField(),
                        newRole = p[8].unescapeField(),
                        previousPermissions = p[9].unescapeField(),
                        newPermissions = p[10].unescapeField(),
                        addedPermissions = p[11].unescapeField().split(";").filter { it.isNotBlank() }.toSet(),
                        removedPermissions = p[12].unescapeField().split(";").filter { it.isNotBlank() }.toSet(),
                        temporaryGrantsSummary = p[13].unescapeField(),
                        reason = p[14].unescapeField(),
                        changedAtMillis = p[15].toLongOrNull() ?: System.currentTimeMillis()
                    )
                }
                .toList()
            if (loaded.isNotEmpty()) {
                val existingIds = auditHistoryMemory.map { it.entryId }.toSet()
                loaded.forEach { item ->
                    if (item.entryId !in existingIds) {
                        auditHistoryMemory.add(item)
                    }
                }
            }
        }
    }

    private fun String.escapeField(): String =
        this.replace("\\", "\\\\").replace("\t", " ").replace("\n", " ")

    private fun String.unescapeField(): String =
        this.replace("\\\\", "\\")
}
