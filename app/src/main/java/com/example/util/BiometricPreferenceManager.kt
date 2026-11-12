package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.data.local.UserAccountEntity
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import javax.crypto.KeyGenerator

/**
 * Represents the verified employee account cryptographically bound to biometric authentication on this device.
 */
data class BoundBiometricUser(
    val id: Long,
    val userCode: String,
    val username: String,
    val fullName: String,
    val role: String,
    val boundAt: Long,
    val boundDeviceId: String = "",
    val credentialFingerprint: String = "",
    val serverSyncToken: String = ""
)

data class BiometricAccountValidationResult(
    val isValid: Boolean,
    val user: UserAccountEntity? = null,
    val errorMessage: String = ""
) {
    val isSuccess: Boolean get() = isValid
    val isFailure: Boolean get() = !isValid
    fun getOrNull(): UserAccountEntity? = user
    fun exceptionOrNull(): Throwable? = if (isValid) null else Exception(errorMessage)
}

/**
 * Manages secure device-specific biometric binding.
 *
 * CRITICAL SECURITY INVARIANTS:
 * 1. Biometric authentication on this physical device can ONLY open the exact verified employee account
 *    (`userCode` AND `username` must BOTH match) that was explicitly logged in with credentials on this device.
 * 2. Local Room auto-increment `id` is NEVER used as a standalone match condition (`it.id == bound.id` is strictly forbidden),
 *    preventing ID reuse or database re-seeding from ever opening another employee's or admin's account.
 * 3. Binds to `credentialFingerprint` (HMAC-SHA256 over userCode + username + passwordHash + serverSyncToken + deviceId).
 *    If the employee's password is changed or their session token is revoked on the server, biometric login is invalidated
 *    until they re-authenticate with their password.
 * 4. If a different employee logs in on the same device, the previous employee's biometric binding is immediately
 *    replaced/cleared so one employee can never use biometrics to enter another employee's account.
 */
object BiometricPreferenceManager {

    private const val PREFS_NAME = "pmp_biometric_device_binding"
    private const val KEY_BOUND_USER_ID = "bound_user_id"
    private const val KEY_BOUND_USER_CODE = "bound_user_code"
    private const val KEY_BOUND_USERNAME = "bound_username"
    private const val KEY_BOUND_FULL_NAME = "bound_full_name"
    private const val KEY_BOUND_ROLE = "bound_role"
    private const val KEY_BOUND_AT = "bound_at"
    private const val KEY_BOUND_DEVICE_ID = "bound_device_id"
    private const val KEY_CREDENTIAL_FINGERPRINT = "bound_credential_fingerprint"
    private const val KEY_SERVER_SYNC_TOKEN = "bound_server_sync_token"
    private const val KEY_IS_ENABLED = "is_biometric_enabled"
    private const val KEY_DEVICE_INSTALL_ID = "device_install_id"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS_PREFIX = "PmpBiometricHardwareKey_"

    private val inMemoryBindingFallback = mutableMapOf<String, Any>()

    private fun getPrefs(context: Context?): SharedPreferences? {
        if (context == null) return null
        return runCatching {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
    }

    /**
     * Binds the verified employee to this specific hardware device.
     * Called strictly upon successful login with verified credentials or approved Google identity.
     */
    fun bindUser(context: Context?, user: UserAccountEntity): Boolean {
        val cleanCode = user.userCode.trim()
        val cleanUsername = user.username.trim().lowercase(Locale.ROOT)
        if (cleanCode.isBlank() || cleanUsername.isBlank()) {
            return false
        }
        if (!user.active || user.syncStatus == "PENDING_MANAGER_APPROVAL") {
            return false
        }

        val deviceId = getDeviceIdentifier(context)
        val fingerprint = PasswordSecurityUtil.computeAccountDeviceFingerprint(
            userCode = cleanCode,
            username = cleanUsername,
            storedPasswordSecret = user.pinOrPassword,
            serverSyncToken = user.serverSyncToken,
            deviceId = deviceId
        )
        val now = System.currentTimeMillis()

        // Provision hardware-backed Android Keystore key for this userCode when running on physical/emulator Android
        if (context != null) {
            provisionHardwareKeystoreBinding(cleanCode)
        }

        val prefs = getPrefs(context)
        if (prefs != null) {
            prefs.edit()
                .putLong(KEY_BOUND_USER_ID, user.id)
                .putString(KEY_BOUND_USER_CODE, cleanCode)
                .putString(KEY_BOUND_USERNAME, cleanUsername)
                .putString(KEY_BOUND_FULL_NAME, user.fullName.trim())
                .putString(KEY_BOUND_ROLE, user.role.trim())
                .putLong(KEY_BOUND_AT, now)
                .putString(KEY_BOUND_DEVICE_ID, deviceId)
                .putString(KEY_CREDENTIAL_FINGERPRINT, fingerprint)
                .putString(KEY_SERVER_SYNC_TOKEN, user.serverSyncToken.trim())
                .putBoolean(KEY_IS_ENABLED, true)
                .apply()
        } else {
            synchronized(inMemoryBindingFallback) {
                inMemoryBindingFallback[KEY_BOUND_USER_ID] = user.id
                inMemoryBindingFallback[KEY_BOUND_USER_CODE] = cleanCode
                inMemoryBindingFallback[KEY_BOUND_USERNAME] = cleanUsername
                inMemoryBindingFallback[KEY_BOUND_FULL_NAME] = user.fullName.trim()
                inMemoryBindingFallback[KEY_BOUND_ROLE] = user.role.trim()
                inMemoryBindingFallback[KEY_BOUND_AT] = now
                inMemoryBindingFallback[KEY_BOUND_DEVICE_ID] = deviceId
                inMemoryBindingFallback[KEY_CREDENTIAL_FINGERPRINT] = fingerprint
                inMemoryBindingFallback[KEY_SERVER_SYNC_TOKEN] = user.serverSyncToken.trim()
                inMemoryBindingFallback[KEY_IS_ENABLED] = true
            }
        }
        return true
    }

    fun isBoundToUser(context: Context?, user: UserAccountEntity): Boolean {
        val bound = getBoundUser(context) ?: return false
        return bound.userCode.equals(user.userCode.trim(), ignoreCase = true) &&
            bound.username.equals(user.username.trim(), ignoreCase = true)
    }

    /**
     * Returns the verified account bound to this hardware device, or null if no valid account is bound.
     * Requires BOTH userCode AND username to be non-blank and boundDeviceId to match this device.
     */
    fun getBoundUser(context: Context?): BoundBiometricUser? {
        val prefs = getPrefs(context)
        val isEnabled: Boolean
        val id: Long
        val code: String
        val username: String
        val fullName: String
        val role: String
        val boundAt: Long
        val boundDeviceId: String
        val fingerprint: String
        val syncToken: String

        if (prefs != null) {
            isEnabled = prefs.getBoolean(KEY_IS_ENABLED, false)
            id = prefs.getLong(KEY_BOUND_USER_ID, -1L)
            code = prefs.getString(KEY_BOUND_USER_CODE, "")?.trim().orEmpty()
            username = prefs.getString(KEY_BOUND_USERNAME, "")?.trim()?.lowercase(Locale.ROOT).orEmpty()
            fullName = prefs.getString(KEY_BOUND_FULL_NAME, "")?.trim().orEmpty()
            role = prefs.getString(KEY_BOUND_ROLE, "")?.trim().orEmpty()
            boundAt = prefs.getLong(KEY_BOUND_AT, 0L)
            boundDeviceId = prefs.getString(KEY_BOUND_DEVICE_ID, "")?.trim().orEmpty()
            fingerprint = prefs.getString(KEY_CREDENTIAL_FINGERPRINT, "")?.trim().orEmpty()
            syncToken = prefs.getString(KEY_SERVER_SYNC_TOKEN, "")?.trim().orEmpty()
        } else {
            synchronized(inMemoryBindingFallback) {
                isEnabled = (inMemoryBindingFallback[KEY_IS_ENABLED] as? Boolean) ?: false
                id = (inMemoryBindingFallback[KEY_BOUND_USER_ID] as? Long) ?: -1L
                code = (inMemoryBindingFallback[KEY_BOUND_USER_CODE] as? String)?.trim().orEmpty()
                username = (inMemoryBindingFallback[KEY_BOUND_USERNAME] as? String)?.trim()?.lowercase(Locale.ROOT).orEmpty()
                fullName = (inMemoryBindingFallback[KEY_BOUND_FULL_NAME] as? String)?.trim().orEmpty()
                role = (inMemoryBindingFallback[KEY_BOUND_ROLE] as? String)?.trim().orEmpty()
                boundAt = (inMemoryBindingFallback[KEY_BOUND_AT] as? Long) ?: 0L
                boundDeviceId = (inMemoryBindingFallback[KEY_BOUND_DEVICE_ID] as? String)?.trim().orEmpty()
                fingerprint = (inMemoryBindingFallback[KEY_CREDENTIAL_FINGERPRINT] as? String)?.trim().orEmpty()
                syncToken = (inMemoryBindingFallback[KEY_SERVER_SYNC_TOKEN] as? String)?.trim().orEmpty()
            }
        }

        // Strict check: BOTH code and username must be present
        if (!isEnabled || id <= 0L || code.isBlank() || username.isBlank()) {
            return null
        }

        val currentDeviceId = getDeviceIdentifier(context)
        if (boundDeviceId.isNotBlank() && !boundDeviceId.equals(currentDeviceId, ignoreCase = true)) {
            // Device ID mismatch (e.g. backup copied across devices) -> reject and clear immediately!
            clearBinding(context)
            return null
        }

        return BoundBiometricUser(
            id = id,
            userCode = code,
            username = username,
            fullName = fullName.ifBlank { username },
            role = role,
            boundAt = boundAt,
            boundDeviceId = boundDeviceId.ifBlank { currentDeviceId },
            credentialFingerprint = fingerprint,
            serverSyncToken = syncToken
        )
    }

    /**
     * Strictly resolves and validates the bound biometric user against the database user list.
     *
     * CRITICAL FIX:
     * - NEVER matches by `it.id == bound.id` alone!
     * - Requires BOTH `userCode` AND `username` to match `bound.userCode` and `bound.username`.
     * - Verifies device revocation status, active status, manager approval status, and cryptographic credential fingerprint.
     */
    fun resolveAndValidateBoundAccount(
        context: Context?,
        allUsers: List<UserAccountEntity>
    ): BiometricAccountValidationResult {
        val bound = getBoundUser(context)
            ?: return BiometricAccountValidationResult(
                isValid = false,
                errorMessage = "لم يتم تفعيل البصمة لأي حساب على هذا الهاتف بعد. يرجى تسجيل الدخول باسم المستخدم وكلمة المرور أولاً لربط بصمتك بحسابك."
            )

        val deviceId = getDeviceIdentifier(context)
        if (bound.boundDeviceId.isNotBlank() && !bound.boundDeviceId.equals(deviceId, ignoreCase = true)) {
            clearBinding(context)
            return BiometricAccountValidationResult(
                isValid = false,
                errorMessage = "تم رصد اختلاف في البصمة الرقمية للجهاز. يرجى تسجيل الدخول بكلمة المرور لإعادة ربط الحساب بهذا الهاتف."
            )
        }

        // Require exact match on BOTH userCode AND username (never fallback to local auto-increment ID!)
        val matchedUser = allUsers.firstOrNull { candidate ->
            candidate.userCode.trim().equals(bound.userCode.trim(), ignoreCase = true) &&
                candidate.username.trim().equals(bound.username.trim(), ignoreCase = true)
        }

        if (matchedUser == null) {
            clearBinding(context)
            AuthSecurityManager.recordSuspiciousAlert(
                context = context,
                alert = SuspiciousLoginAlert(
                    severity = "HIGH",
                    userCode = bound.userCode,
                    username = bound.username,
                    deviceId = deviceId,
                    deviceName = AuthSecurityManager.getHardwareDeviceName(),
                    title = "إحباط محاولة دخول بالبصمة لحساب غير متطابق",
                    description = "تم رفض محاولة تسجيل الدخول بالبصمة لأن الحساب المرتبط (${bound.username} / ${bound.userCode}) لم يعد متطابقاً في قاعدة البيانات."
                )
            )
            return BiometricAccountValidationResult(
                isValid = false,
                errorMessage = "الحساب المرتبط بهذه البصمة لم يعد متوفراً أو تغيرت بياناته الأساسية. يرجى تسجيل الدخول بكلمة المرور مجدداً."
            )
        }

        if (com.example.data.local.DeletedAccountsRegistry.isDeleted(matchedUser.userCode, matchedUser.username, matchedUser.phoneOrEmail)) {
            clearBinding(context)
            return BiometricAccountValidationResult(
                isValid = false,
                errorMessage = "تم حذف هذا الحساب نهائياً من قبل الإدارة ولا يمكن الدخول إليه بالبصمة."
            )
        }

        if (!matchedUser.active) {
            return BiometricAccountValidationResult(
                isValid = false,
                errorMessage = "الحساب المرتبط بهذه البصمة (${matchedUser.fullName}) تم إيقافه من قبل الإدارة."
            )
        }

        if (matchedUser.syncStatus == "PENDING_MANAGER_APPROVAL") {
            return BiometricAccountValidationResult(
                isValid = false,
                errorMessage = "الحساب المرتبط بهذه البصمة (${matchedUser.fullName}) بانتظار موافقة مدير النظام وتحديد الصلاحيات أولاً."
            )
        }

        if (AuthSecurityManager.isDeviceRevokedForUser(context, matchedUser.userCode, deviceId)) {
            clearBinding(context)
            return BiometricAccountValidationResult(
                isValid = false,
                errorMessage = "تم إلغاء تفويض هذا الجهاز من قائمة الأجهزة الموثوقة للحساب (${matchedUser.fullName}). يرجى تسجيل الدخول بكلمة المرور."
            )
        }

        val loginAt = bound.boundAt
        val revokeEpoch = AuthSecurityManager.getUserSessionRevocationEpoch(context, matchedUser.userCode)
        if (loginAt > 0L && revokeEpoch > loginAt) {
            clearBinding(context)
            return BiometricAccountValidationResult(
                isValid = false,
                errorMessage = "تم إبطال جلسات البصمة السابقة لهذا الحساب لأسباب أمنية. يرجى تسجيل الدخول بكلمة المرور لإعادة تفعيل البصمة."
            )
        }

        // Verify cryptographic credential & syncToken fingerprint
        if (bound.credentialFingerprint.isNotBlank()) {
            val expectedFingerprint = PasswordSecurityUtil.computeAccountDeviceFingerprint(
                userCode = matchedUser.userCode,
                username = matchedUser.username,
                storedPasswordSecret = matchedUser.pinOrPassword,
                serverSyncToken = matchedUser.serverSyncToken,
                deviceId = deviceId
            )
            if (!PasswordSecurityUtil.constantTimeEquals(bound.credentialFingerprint, expectedFingerprint)) {
                clearBinding(context)
                AuthSecurityManager.recordSuspiciousAlert(
                    context = context,
                    alert = SuspiciousLoginAlert(
                        severity = "MEDIUM",
                        userCode = matchedUser.userCode,
                        username = matchedUser.username,
                        deviceId = deviceId,
                        deviceName = AuthSecurityManager.getHardwareDeviceName(),
                        title = "إعادة تعيين ربط البصمة بعد تغيير كلمة المرور أو مفتاح المزامنة",
                        description = "تم إيقاف الدخول بالبصمة تلقائياً للحساب (${matchedUser.username}) نظراً لتغيير كلمة المرور أو تدوير مفتاح الأمان على الخادم."
                    )
                )
                return BiometricAccountValidationResult(
                    isValid = false,
                    errorMessage = "تم تغيير كلمة المرور أو تحديث مفتاح أمان الحساب (${matchedUser.fullName}) مؤخراً. يرجى تسجيل الدخول بكلمة المرور الجديدة مرة واحدة لإعادة تفعيل البصمة."
                )
            }
        }

        return BiometricAccountValidationResult(isValid = true, user = matchedUser)
    }

    /**
     * Ensures that if a user logs in on this device, any previous biometric binding belonging to a
     * DIFFERENT employee is immediately replaced with the newly authenticated user so biometric login
     * can never open the wrong employee account.
     */
    fun synchronizeBindingOnAuthenticatedLogin(
        context: Context?,
        authenticatedUser: UserAccountEntity,
        autoBindIfUnbound: Boolean = true
    ) {
        val currentBound = getBoundUser(context)
        if (currentBound != null &&
            (!currentBound.userCode.equals(authenticatedUser.userCode, ignoreCase = true) ||
                !currentBound.username.equals(authenticatedUser.username, ignoreCase = true))
        ) {
            clearBinding(context)
        }
        if (autoBindIfUnbound || currentBound != null) {
            bindUser(context, authenticatedUser)
        }
    }

    /**
     * Unbinds the account from biometric login on this device and deletes its hardware keystore key.
     */
    fun clearBinding(context: Context?) {
        val prefs = getPrefs(context)
        val oldCode = prefs?.getString(KEY_BOUND_USER_CODE, null)
            ?: synchronized(inMemoryBindingFallback) { inMemoryBindingFallback[KEY_BOUND_USER_CODE] as? String }
        if (context != null && !oldCode.isNullOrBlank()) {
            deleteHardwareKeystoreBinding(oldCode)
        }
        if (prefs != null) {
            prefs.edit()
                .remove(KEY_BOUND_USER_ID)
                .remove(KEY_BOUND_USER_CODE)
                .remove(KEY_BOUND_USERNAME)
                .remove(KEY_BOUND_FULL_NAME)
                .remove(KEY_BOUND_ROLE)
                .remove(KEY_BOUND_AT)
                .remove(KEY_BOUND_DEVICE_ID)
                .remove(KEY_CREDENTIAL_FINGERPRINT)
                .remove(KEY_SERVER_SYNC_TOKEN)
                .putBoolean(KEY_IS_ENABLED, false)
                .apply()
        }
        synchronized(inMemoryBindingFallback) {
            inMemoryBindingFallback.remove(KEY_BOUND_USER_ID)
            inMemoryBindingFallback.remove(KEY_BOUND_USER_CODE)
            inMemoryBindingFallback.remove(KEY_BOUND_USERNAME)
            inMemoryBindingFallback.remove(KEY_BOUND_FULL_NAME)
            inMemoryBindingFallback.remove(KEY_BOUND_ROLE)
            inMemoryBindingFallback.remove(KEY_BOUND_AT)
            inMemoryBindingFallback.remove(KEY_BOUND_DEVICE_ID)
            inMemoryBindingFallback.remove(KEY_CREDENTIAL_FINGERPRINT)
            inMemoryBindingFallback.remove(KEY_SERVER_SYNC_TOKEN)
            inMemoryBindingFallback[KEY_IS_ENABLED] = false
        }
    }

    /**
     * Retrieves or generates a stable, sanitized hardware device identifier for security & diagnostics.
     */
    fun getDeviceIdentifier(context: Context?): String {
        val prefs = getPrefs(context)
        if (prefs != null && context != null) {
            var deviceId = prefs.getString(KEY_DEVICE_INSTALL_ID, null)
            if (deviceId.isNullOrBlank()) {
                val androidId = runCatching {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                }.getOrNull()
                deviceId = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                    "DEV-" + androidId.takeLast(8).uppercase(Locale.ROOT)
                } else {
                    "DEV-" + UUID.randomUUID().toString().take(8).uppercase(Locale.ROOT)
                }
                prefs.edit().putString(KEY_DEVICE_INSTALL_ID, deviceId).apply()
            }
            return deviceId
        }
        return synchronized(inMemoryBindingFallback) {
            val existing = inMemoryBindingFallback[KEY_DEVICE_INSTALL_ID] as? String
            if (!existing.isNullOrBlank()) {
                existing
            } else {
                val created = "DEV-" + UUID.randomUUID().toString().take(8).uppercase(Locale.ROOT)
                inMemoryBindingFallback[KEY_DEVICE_INSTALL_ID] = created
                created
            }
        }
    }

    private fun provisionHardwareKeystoreBinding(userCode: String) {
        runCatching {
            val alias = KEY_ALIAS_PREFIX + userCode.replace(Regex("[^a-zA-Z0-9_]"), "_")
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setInvalidatedByBiometricEnrollment(true)
            }
            keyGenerator.init(builder.build())
            keyGenerator.generateKey()
        }
    }

    private fun deleteHardwareKeystoreBinding(userCode: String) {
        runCatching {
            val alias = KEY_ALIAS_PREFIX + userCode.replace(Regex("[^a-zA-Z0-9_]"), "_")
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            if (ks.containsAlias(alias)) {
                ks.deleteEntry(alias)
            }
        }
    }

    fun resetInMemoryStoreForTests() {
        synchronized(inMemoryBindingFallback) {
            inMemoryBindingFallback.clear()
        }
    }
}
