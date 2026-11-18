package com.example.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.data.local.AuditLogEntity
import com.example.data.local.FormulationEntity
import com.example.data.local.FormulationIngredient
import com.example.data.local.PmpDao
import com.example.data.local.UserAccountEntity
import com.example.isSuperAdminAccount
import com.example.isSystemManager
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * PMP Chemical Lab — Intellectual Property Protection, Granular RBAC & Sealing Engine.
 * منظومة أمن المختبر الكيميائي وحماية الملكية الفكرية وتشفير التركيبات السرية.
 *
 * Security Invariants:
 * - ZERO hardcoded AES/HMAC master keys in source code.
 * - Strict deny-by-default & explicit DENY precedence for secret formula percentages.
 * - Confidential ingredient ratios never leak into AuditLog, error reports, notifications, or unauthorized exports.
 */
object LabSecurityManager {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "pmp_lab_ip_aes_gcm_v1"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val PBKDF2_ALGO = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 100_000
    private const val PREFIX_DEVICE_SEAL = "PMPENC1:DEV:"
    private const val PREFIX_PASSPHRASE_SEAL = "PMPENC1:PWD:"

    // Ephemeral session-scoped AES key ONLY used when AndroidKeyStore provider is unavailable (e.g., Robolectric JVM tests)
    private val ephemeralJvmKeyRef = AtomicReference<SecretKey?>(null)

    enum class LabActorRole(val arabicLabel: String) {
        ADMIN("مدير النظام / الإدارة العليا"),
        LAB_OFFICER("مسؤول المختبر الكيميائي"),
        QC_OFFICER("مسؤول الجودة (QC)"),
        PRODUCTION_OFFICER("مهندس / مسؤول الإنتاج"),
        ACCOUNTANT("المحاسب المالي"),
        WAREHOUSE_OFFICER("أمين مخزن الخامات"),
        SALES_OFFICER("مسؤول المبيعات"),
        OTHER("موظف عام")
    }

    object LabTokens {
        const val VIEW_SECRET_FORMULAS = "VIEW_SECRET_FORMULAS"
        const val APPROVE_FORMULAS = "APPROVE_FORMULAS"
        const val EDIT_FORMULAS = "EDIT_FORMULAS"
        const val EXPORT_FORMULAS = "EXPORT_FORMULAS"
        const val IMPORT_EXCEL = "IMPORT_EXCEL"
        const val MODULE_LAB = "المختبر الكيميائي"
    }

    private val PERCENT_PATTERN = Regex("""\d+(\.\d+)?\s*%""")
    private val JSON_ARRAY_PATTERN = Regex("""\[(\s*\{.*?\}\s*,?)*]""", RegexOption.DOT_MATCHES_ALL)

    fun resolveActorRole(user: UserAccountEntity?): LabActorRole {
        if (user == null || !user.active) return LabActorRole.OTHER
        if (user.isSuperAdminAccount() || (user.isSystemManager() && !EnterpriseRbacManager.isScopedAdministrator(user))) {
            return LabActorRole.ADMIN
        }
        val tokens = EnterpriseRbacManager.extractActiveRawTokens(user)
        val roleLower = user.role.trim().lowercase(Locale.ROOT)
        return when {
            LabTokens.MODULE_LAB in tokens ||
                roleLower.contains("مختبر") ||
                roleLower.contains("كيمي") ||
                roleLower.contains("lab") -> LabActorRole.LAB_OFFICER

            roleLower.contains("جودة") ||
                roleLower.contains("qc") ||
                roleLower.contains("quality") -> LabActorRole.QC_OFFICER

            roleLower.contains("محاسب") ||
                roleLower.contains("account") ||
                ("المحاسبة" in tokens && "المصنع" !in tokens) -> LabActorRole.ACCOUNTANT

            roleLower.contains("أمين مخزن") ||
                roleLower.contains("مخازن") ||
                roleLower.contains("warehouse") -> LabActorRole.WAREHOUSE_OFFICER

            roleLower.contains("مبيعات") ||
                roleLower.contains("sales") ||
                ("المبيعات" in tokens && "المصنع" !in tokens && "الإنتاج" !in tokens) -> LabActorRole.SALES_OFFICER

            roleLower.contains("إنتاج") ||
                roleLower.contains("تصنيع") ||
                roleLower.contains("production") ||
                "الإنتاج" in tokens ||
                "المصنع" in tokens -> LabActorRole.PRODUCTION_OFFICER

            else -> LabActorRole.OTHER
        }
    }

    /**
     * Controls whether a user can see the confidential ingredient list & percentages.
     * STRICT DENY-BY-DEFAULT: Production, QC, Accountant, Warehouse, and Sales users NEVER see secret ratios
     * unless explicitly granted VIEW_SECRET_FORMULAS / المختبر الكيميائي (and not explicitly denied).
     */
    fun canViewSecretFormulas(
        user: UserAccountEntity?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (user == null || !user.active || user.syncStatus == "PENDING_MANAGER_APPROVAL") return false
        if (user.isSuperAdminAccount()) return true

        val tokens = EnterpriseRbacManager.extractActiveRawTokens(user, nowMillis)
        if ("DENY:${LabTokens.VIEW_SECRET_FORMULAS}" in tokens ||
            "DENY:${LabTokens.MODULE_LAB}" in tokens ||
            "DENY:${LabTokens.MODULE_LAB}:VIEW" in tokens
        ) {
            return false
        }

        if (user.isSystemManager() && !EnterpriseRbacManager.isScopedAdministrator(user)) return true

        if (LabTokens.VIEW_SECRET_FORMULAS in tokens ||
            "GRANT:${LabTokens.MODULE_LAB}:VIEW" in tokens ||
            LabTokens.MODULE_LAB in tokens ||
            "Chemistry Lab" in tokens ||
            "CHEMISTRY_LAB" in tokens
        ) {
            return true
        }

        val roleLower = user.role.trim().lowercase(Locale.ROOT)
        return roleLower.contains("مختبر") || roleLower.contains("كيمي") || roleLower.contains("chemist")
    }

    fun canCreateOrEditFormulas(
        user: UserAccountEntity?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!canViewSecretFormulas(user, nowMillis)) return false
        if (user!!.isSuperAdminAccount()) return true
        val tokens = EnterpriseRbacManager.extractActiveRawTokens(user, nowMillis)
        if ("VIEW_ONLY" in tokens ||
            "عرض فقط" in tokens ||
            "DENY:EDIT" in tokens ||
            "DENY:CREATE" in tokens ||
            "DENY:${LabTokens.EDIT_FORMULAS}" in tokens ||
            "DENY:${LabTokens.MODULE_LAB}:EDIT" in tokens ||
            "DENY:${LabTokens.MODULE_LAB}:CREATE" in tokens
        ) {
            return false
        }
        return true
    }

    fun canApproveFormulas(
        user: UserAccountEntity?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (user == null || !user.active || user.syncStatus == "PENDING_MANAGER_APPROVAL") return false
        if (user.isSuperAdminAccount()) return true
        val tokens = EnterpriseRbacManager.extractActiveRawTokens(user, nowMillis)
        if ("DENY:${LabTokens.APPROVE_FORMULAS}" in tokens ||
            "DENY:APPROVE" in tokens ||
            "DENY:${LabTokens.MODULE_LAB}:APPROVE" in tokens
        ) {
            return false
        }
        if (user.isSystemManager() && !EnterpriseRbacManager.isScopedAdministrator(user)) return true
        return LabTokens.APPROVE_FORMULAS in tokens ||
            "اعتماد التركيبات" in tokens ||
            "GRANT:${LabTokens.MODULE_LAB}:APPROVE" in tokens
    }

    fun canLogLabExperiments(
        user: UserAccountEntity?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (user == null || !user.active || user.syncStatus == "PENDING_MANAGER_APPROVAL") return false
        if (user.isSuperAdminAccount()) return true
        val tokens = EnterpriseRbacManager.extractActiveRawTokens(user, nowMillis)
        if ("DENY:EXPERIMENTS" in tokens || "VIEW_ONLY" in tokens || "عرض فقط" in tokens) return false
        val role = resolveActorRole(user)
        return role in setOf(LabActorRole.ADMIN, LabActorRole.LAB_OFFICER, LabActorRole.QC_OFFICER) ||
            canViewSecretFormulas(user, nowMillis)
    }

    fun canDecideQcGate(
        user: UserAccountEntity?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (user == null || !user.active || user.syncStatus == "PENDING_MANAGER_APPROVAL") return false
        if (user.isSuperAdminAccount()) return true
        val tokens = EnterpriseRbacManager.extractActiveRawTokens(user, nowMillis)
        if ("DENY:QC_DECISION" in tokens || "DENY:APPROVE" in tokens) return false
        val role = resolveActorRole(user)
        return role in setOf(LabActorRole.ADMIN, LabActorRole.QC_OFFICER) ||
            "اعتماد الجودة" in tokens ||
            EnterpriseRbacManager.hasGranularPermission(user, LabTokens.MODULE_LAB, ErpActionPermission.APPROVE, nowMillis)
    }

    fun canViewFormulaCosts(
        user: UserAccountEntity?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (user == null || !user.active || user.syncStatus == "PENDING_MANAGER_APPROVAL") return false
        val role = resolveActorRole(user)
        if (role in setOf(LabActorRole.ADMIN, LabActorRole.LAB_OFFICER, LabActorRole.ACCOUNTANT)) return true
        val tokens = EnterpriseRbacManager.extractActiveRawTokens(user, nowMillis)
        return "المحاسبة" in tokens || "COST_VIEW" in tokens || canViewSecretFormulas(user, nowMillis)
    }

    fun canViewRawMaterialRegistry(
        user: UserAccountEntity?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (user == null || !user.active || user.syncStatus == "PENDING_MANAGER_APPROVAL") return false
        val role = resolveActorRole(user)
        return role != LabActorRole.SALES_OFFICER
    }

    fun canExportSecretFormulas(
        user: UserAccountEntity?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!canViewSecretFormulas(user, nowMillis)) return false
        if (user!!.isSuperAdminAccount()) return true
        val tokens = EnterpriseRbacManager.extractActiveRawTokens(user, nowMillis)
        if ("DENY:${LabTokens.EXPORT_FORMULAS}" in tokens ||
            "DENY:EXPORT" in tokens ||
            "DENY:${LabTokens.MODULE_LAB}:EXPORT" in tokens
        ) {
            return false
        }
        if (user.isSystemManager() && !EnterpriseRbacManager.isScopedAdministrator(user)) return true
        return LabTokens.EXPORT_FORMULAS in tokens ||
            EnterpriseRbacManager.PermissionTokens.ACTION_EXPORT in tokens ||
            EnterpriseRbacManager.PermissionTokens.ACTION_PRINT in tokens ||
            "GRANT:${LabTokens.MODULE_LAB}:EXPORT" in tokens
    }

    fun redactFormulationForUser(
        formulation: FormulationEntity,
        user: UserAccountEntity?
    ): FormulationEntity {
        val showSecrets = canViewSecretFormulas(user)
        val showCosts = canViewFormulaCosts(user)
        if (showSecrets && showCosts) return formulation
        return formulation.copy(
            ingredientsJson = if (showSecrets) formulation.ingredientsJson else "[]",
            confidentialCiphertext = if (showSecrets) formulation.confidentialCiphertext else "",
            technicalAnalysis = if (showSecrets) formulation.technicalAnalysis else "مواصفات فنية محمية الصلاحية",
            totalCost = if (showCosts) formulation.totalCost else 0.0,
            costPerKg = if (showCosts) formulation.costPerKg else 0.0,
            historicalTotalCost = if (showCosts) formulation.historicalTotalCost else 0.0,
            historicalCostPerKg = if (showCosts) formulation.historicalCostPerKg else 0.0
        )
    }

    fun redactIngredientsForUser(
        ingredients: List<FormulationIngredient>,
        user: UserAccountEntity?
    ): List<FormulationIngredient> {
        return if (canViewSecretFormulas(user)) ingredients else emptyList()
    }

    /**
     * Strips confidential ingredient payloads & percentage ratios from log entries, error banners, and sync notifications.
     */
    fun sanitizeLogText(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(JSON_ARRAY_PATTERN, "[REDACTED_FORMULA_PAYLOAD]")
            .replace(PERCENT_PATTERN, "[REDACTED_%]")
            .take(240)
    }

    suspend fun logLabAccessOrDenial(
        dao: PmpDao,
        user: UserAccountEntity?,
        action: String,
        formulaCode: String,
        allowed: Boolean,
        note: String = "",
        context: Context? = null
    ) {
        val actor = user?.username?.ifBlank { user.fullName } ?: "anonymous"
        val cleanAction = if (allowed) action else "LAB_SECURITY_DENIED_$action"
        val cleanNote = sanitizeLogText(note)
        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = cleanAction,
                detail = "[$formulaCode] ${if (allowed) "مصرح" else "مرفوض أمنياً"} | $cleanNote"
            )
        )
        if (!allowed && context != null) {
            EnterpriseRbacManager.validateBackendRequest(
                user = user,
                module = LabTokens.MODULE_LAB,
                action = ErpActionPermission.VIEW,
                context = context
            )
        }
    }

    // ==================== Hardware / Keystore & PBKDF2 AES-GCM Sealing ====================

    private fun getOrCreateDeviceKey(): SecretKey {
        try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
            ks.load(null)
            (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            return keyGen.generateKey()
        } catch (_: Throwable) {
            // Fallback for headless JVM / Robolectric unit tests: dynamic random runtime key, never hardcoded.
            return ephemeralJvmKeyRef.updateAndGet { existing ->
                existing ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }!!
        }
    }

    /**
     * Seals confidential ingredient JSON on device using Keystore AES-256-GCM (or runtime-generated key in JVM).
     */
    fun sealWithDeviceKey(plainIngredientsJson: String): Result<String> = runCatching {
        val key = getOrCreateDeviceKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainIngredientsJson.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        "$PREFIX_DEVICE_SEAL$ivB64:$dataB64"
    }

    fun unsealWithDeviceKey(sealedPayload: String): Result<String> = runCatching {
        require(sealedPayload.startsWith(PREFIX_DEVICE_SEAL)) { "تنسيق التشفير المحلي غير صالح." }
        val parts = sealedPayload.removePrefix(PREFIX_DEVICE_SEAL).split(":")
        require(parts.size == 2) { "تلف في حزمة التشفير المحلية." }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
        val key = getOrCreateDeviceKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    /**
     * Cross-platform AES-256-GCM envelope using an operator-supplied Lab passphrase (PBKDF2-HMAC-SHA256).
     * Never stores or embeds the passphrase in source code.
     */
    fun sealWithPassphrase(plainIngredientsJson: String, passphrase: CharArray): Result<String> = runCatching {
        require(passphrase.size >= 8) { "عبارة مرور المختبر يجب أن لا تقل عن 8 أحرف." }
        val rng = SecureRandom()
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val iv = ByteArray(GCM_IV_BYTES).also { rng.nextBytes(it) }
        val key = derivePassphraseKey(passphrase, salt)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plainIngredientsJson.toByteArray(Charsets.UTF_8))
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        "$PREFIX_PASSPHRASE_SEAL$saltB64:$ivB64:$dataB64"
    }

    fun unsealWithPassphrase(sealedPayload: String, passphrase: CharArray): Result<String> = runCatching {
        require(sealedPayload.startsWith(PREFIX_PASSPHRASE_SEAL)) { "تنسيق التشفير المشترك غير صالح." }
        val parts = sealedPayload.removePrefix(PREFIX_PASSPHRASE_SEAL).split(":")
        require(parts.size == 3) { "حزمة التشفير تالفة." }
        val salt = Base64.decode(parts[0], Base64.NO_WRAP)
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipherBytes = Base64.decode(parts[2], Base64.NO_WRAP)
        val key = derivePassphraseKey(passphrase, salt)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private fun derivePassphraseKey(passphrase: CharArray, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGO)
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, 256)
        val encoded = factory.generateSecret(spec).encoded
        return SecretKeySpec(encoded, "AES")
    }
}
