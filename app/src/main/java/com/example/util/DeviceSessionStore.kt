package com.example.util

import android.content.Context
import android.content.SharedPreferences
import com.example.data.local.UserAccountEntity
import java.util.Locale
import java.util.UUID

data class SessionValidationResult(
    val isValid: Boolean,
    val reasonCode: String = "VALID", // VALID, NO_SESSION, IDENTITY_MISMATCH, INACTIVE_OR_PENDING, EXPIRED_MAX_AGE, EXPIRED_IDLE, REVOKED_SESSION, REVOKED_DEVICE, TOKEN_ROTATED
    val messageAr: String? = null
) {
    val reasonMessage: String get() = messageAr.orEmpty()
}

/**
 * Manages device-local session authentication state, session expiration (absolute TTL + idle timeout),
 * cryptographic account-to-device binding, and session revocation checks.
 *
 * CRITICAL SECURITY INVARIANT:
 * 1. Sessions are strictly bound to this physical device installation AND to the exact (userCode + username).
 * 2. Local Room auto-increment `userId` alone is NEVER trusted to restore a session without matching `userCode` and `username`.
 * 3. Expired or revoked sessions are immediately terminated and cleared.
 */
object DeviceSessionStore {

    private const val PREFS_NAME = "pmp_device_session_prefs"
    private const val KEY_ACTIVE_USER_ID = "device_active_user_id"
    private const val KEY_ACTIVE_USER_CODE = "device_active_user_code"
    private const val KEY_ACTIVE_USERNAME = "device_active_username"
    private const val KEY_ACTIVE_USER_ROLE = "device_active_user_role"
    private const val KEY_DEVICE_ID = "device_unique_hardware_id"
    private const val KEY_SESSION_ID = "device_active_session_id"
    private const val KEY_LOGIN_TIMESTAMP = "device_login_timestamp"
    private const val KEY_LAST_ACTIVITY_TIMESTAMP = "device_last_activity_timestamp"
    private const val KEY_BOUND_SYNC_TOKEN = "device_bound_sync_token"
    private const val KEY_CREDENTIAL_FINGERPRINT = "device_credential_fingerprint"
    private const val KEY_SESSION_MAX_AGE_MS = "device_session_max_age_ms"
    private const val KEY_SESSION_IDLE_TIMEOUT_MS = "device_session_idle_timeout_ms"

    const val DEFAULT_SESSION_MAX_AGE_MS = 12 * 60 * 60 * 1000L // 12 hours
    const val DEFAULT_SESSION_IDLE_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes

    private val inMemorySessionFallback = mutableMapOf<String, Any>()

    private fun getPrefs(context: Context?): SharedPreferences? {
        if (context == null) return null
        return runCatching {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
    }

    /**
     * Saves the authenticated user session for THIS physical device with full identity and expiration metadata.
     */
    fun saveActiveSession(
        context: Context?,
        userId: Long,
        userCode: String,
        role: String,
        username: String = "",
        serverSyncToken: String = "",
        storedPasswordSecret: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val deviceId = getOrCreateDeviceId(context)
        val cleanCode = userCode.trim().ifBlank { "USR" }
        val sessionId = "SESS-$cleanCode-${deviceId.removePrefix("DEV-")}-${nowMs % 1000000}-${UUID.randomUUID().toString().take(4).uppercase(Locale.ROOT)}"
        val fingerprint = if (username.isNotBlank() && storedPasswordSecret.isNotBlank()) {
            PasswordSecurityUtil.computeAccountDeviceFingerprint(
                userCode = userCode,
                username = username,
                storedPasswordSecret = storedPasswordSecret,
                serverSyncToken = serverSyncToken,
                deviceId = deviceId
            )
        } else {
            ""
        }

        val prefs = getPrefs(context)
        if (prefs != null) {
            prefs.edit()
                .putLong(KEY_ACTIVE_USER_ID, userId)
                .putString(KEY_ACTIVE_USER_CODE, userCode.trim())
                .putString(KEY_ACTIVE_USERNAME, username.trim().lowercase(Locale.ROOT))
                .putString(KEY_ACTIVE_USER_ROLE, role.trim())
                .putString(KEY_SESSION_ID, sessionId)
                .putLong(KEY_LOGIN_TIMESTAMP, nowMs)
                .putLong(KEY_LAST_ACTIVITY_TIMESTAMP, nowMs)
                .putString(KEY_BOUND_SYNC_TOKEN, serverSyncToken.trim())
                .putString(KEY_CREDENTIAL_FINGERPRINT, fingerprint)
                .apply()
        } else {
            synchronized(inMemorySessionFallback) {
                inMemorySessionFallback[KEY_ACTIVE_USER_ID] = userId
                inMemorySessionFallback[KEY_ACTIVE_USER_CODE] = userCode.trim()
                inMemorySessionFallback[KEY_ACTIVE_USERNAME] = username.trim().lowercase(Locale.ROOT)
                inMemorySessionFallback[KEY_ACTIVE_USER_ROLE] = role.trim()
                inMemorySessionFallback[KEY_SESSION_ID] = sessionId
                inMemorySessionFallback[KEY_LOGIN_TIMESTAMP] = nowMs
                inMemorySessionFallback[KEY_LAST_ACTIVITY_TIMESTAMP] = nowMs
                inMemorySessionFallback[KEY_BOUND_SYNC_TOKEN] = serverSyncToken.trim()
                inMemorySessionFallback[KEY_CREDENTIAL_FINGERPRINT] = fingerprint
            }
        }
        return sessionId
    }

    /**
     * Convenience overload binding the full UserAccountEntity and registering the device in AuthSecurityManager.
     */
    fun saveActiveUserSession(
        context: Context?,
        user: UserAccountEntity,
        authMethod: String = "PASSWORD",
        biometricBound: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val sessionId = saveActiveSession(
            context = context,
            userId = user.id,
            userCode = user.userCode,
            role = user.role,
            username = user.username,
            serverSyncToken = user.serverSyncToken,
            storedPasswordSecret = user.pinOrPassword,
            nowMs = nowMs
        )
        val devId = getOrCreateDeviceId(context)
        AuthSecurityManager.registerAuthenticatedDevice(
            context = context,
            user = user,
            deviceId = devId,
            sessionId = sessionId,
            biometricBound = biometricBound,
            markTrusted = true,
            nowMillis = nowMs
        )
        AuthSecurityManager.recordLoginActivity(
            context = context,
            record = LoginActivityRecord(
                timestamp = nowMs,
                userCode = user.userCode,
                username = user.username,
                fullName = user.fullName,
                deviceId = devId,
                deviceName = AuthSecurityManager.getHardwareDeviceName(),
                authMethod = authMethod,
                status = "SUCCESS",
                isSuspicious = false,
                detail = "تسجيل دخول ناجح وتفعيل الجلسة ($sessionId) للحساب ${user.fullName} (${user.role})"
            )
        )
        return sessionId
    }

    fun getActiveUserId(context: Context?): Long {
        val prefs = getPrefs(context)
        return prefs?.getLong(KEY_ACTIVE_USER_ID, -1L)
            ?: synchronized(inMemorySessionFallback) { (inMemorySessionFallback[KEY_ACTIVE_USER_ID] as? Long) ?: -1L }
    }

    fun getActiveUserCode(context: Context?): String {
        val prefs = getPrefs(context)
        return prefs?.getString(KEY_ACTIVE_USER_CODE, "").orEmpty()
            .ifEmpty { synchronized(inMemorySessionFallback) { (inMemorySessionFallback[KEY_ACTIVE_USER_CODE] as? String).orEmpty() } }
    }

    fun getActiveUsername(context: Context?): String {
        val prefs = getPrefs(context)
        return prefs?.getString(KEY_ACTIVE_USERNAME, "").orEmpty()
            .ifEmpty { synchronized(inMemorySessionFallback) { (inMemorySessionFallback[KEY_ACTIVE_USERNAME] as? String).orEmpty() } }
    }

    fun getActiveSessionId(context: Context?): String {
        val prefs = getPrefs(context)
        return prefs?.getString(KEY_SESSION_ID, "").orEmpty()
            .ifEmpty { synchronized(inMemorySessionFallback) { (inMemorySessionFallback[KEY_SESSION_ID] as? String).orEmpty() } }
    }

    fun getLoginTimestamp(context: Context?): Long {
        val prefs = getPrefs(context)
        return prefs?.getLong(KEY_LOGIN_TIMESTAMP, 0L)
            ?: synchronized(inMemorySessionFallback) { (inMemorySessionFallback[KEY_LOGIN_TIMESTAMP] as? Long) ?: 0L }
    }

    fun getLastActivityTimestamp(context: Context?): Long {
        val prefs = getPrefs(context)
        return prefs?.getLong(KEY_LAST_ACTIVITY_TIMESTAMP, 0L)
            ?: synchronized(inMemorySessionFallback) { (inMemorySessionFallback[KEY_LAST_ACTIVITY_TIMESTAMP] as? Long) ?: 0L }
    }

    fun touchSessionActivity(context: Context?, nowMillis: Long = System.currentTimeMillis()) {
        if (!hasActiveSession(context)) return
        val prefs = getPrefs(context)
        if (prefs != null) {
            prefs.edit().putLong(KEY_LAST_ACTIVITY_TIMESTAMP, nowMillis).apply()
        } else {
            synchronized(inMemorySessionFallback) {
                inMemorySessionFallback[KEY_LAST_ACTIVITY_TIMESTAMP] = nowMillis
            }
        }
    }

    fun getSessionMaxAgeMs(context: Context?): Long {
        val prefs = getPrefs(context)
        val raw = prefs?.getLong(KEY_SESSION_MAX_AGE_MS, DEFAULT_SESSION_MAX_AGE_MS)
            ?: synchronized(inMemorySessionFallback) { (inMemorySessionFallback[KEY_SESSION_MAX_AGE_MS] as? Long) ?: DEFAULT_SESSION_MAX_AGE_MS }
        return raw.coerceIn(15 * 60 * 1000L, 7 * 24 * 60 * 60 * 1000L)
    }

    fun setSessionMaxAgeMs(context: Context?, maxAgeMs: Long) {
        val clamped = maxAgeMs.coerceIn(15 * 60 * 1000L, 7 * 24 * 60 * 60 * 1000L)
        val prefs = getPrefs(context)
        if (prefs != null) {
            prefs.edit().putLong(KEY_SESSION_MAX_AGE_MS, clamped).apply()
        } else {
            synchronized(inMemorySessionFallback) {
                inMemorySessionFallback[KEY_SESSION_MAX_AGE_MS] = clamped
            }
        }
    }

    fun getSessionIdleTimeoutMs(context: Context?): Long {
        val prefs = getPrefs(context)
        val raw = prefs?.getLong(KEY_SESSION_IDLE_TIMEOUT_MS, DEFAULT_SESSION_IDLE_TIMEOUT_MS)
            ?: synchronized(inMemorySessionFallback) { (inMemorySessionFallback[KEY_SESSION_IDLE_TIMEOUT_MS] as? Long) ?: DEFAULT_SESSION_IDLE_TIMEOUT_MS }
        return raw.coerceIn(5 * 60 * 1000L, 24 * 60 * 60 * 1000L)
    }

    fun setSessionIdleTimeoutMs(context: Context?, idleTimeoutMs: Long) {
        val clamped = idleTimeoutMs.coerceIn(5 * 60 * 1000L, 24 * 60 * 60 * 1000L)
        val prefs = getPrefs(context)
        if (prefs != null) {
            prefs.edit().putLong(KEY_SESSION_IDLE_TIMEOUT_MS, clamped).apply()
        } else {
            synchronized(inMemorySessionFallback) {
                inMemorySessionFallback[KEY_SESSION_IDLE_TIMEOUT_MS] = clamped
            }
        }
    }

    fun hasActiveSession(context: Context?): Boolean {
        return getActiveUserId(context) > 0L && getActiveUserCode(context).isNotBlank()
    }

    /**
     * Validates that the stored device session belongs to the target user, has not expired
     * (either by absolute lifetime or idle timeout), and has not been revoked.
     */
    fun validateSessionForUser(
        context: Context?,
        user: UserAccountEntity?,
        nowMs: Long = System.currentTimeMillis()
    ): SessionValidationResult {
        if (user == null || !hasActiveSession(context)) {
            return SessionValidationResult(false, "NO_SESSION", "لا توجد جلسة نشطة حالياً")
        }

        val savedCode = getActiveUserCode(context).trim()
        val savedUsername = getActiveUsername(context).trim()
        val sessionId = getActiveSessionId(context).trim()
        val deviceId = getOrCreateDeviceId(context)

        // 1. Strict identity match: prevent Room ID reuse from opening another employee's account
        if (savedCode.isNotBlank() && !user.userCode.trim().equals(savedCode, ignoreCase = true)) {
            return SessionValidationResult(
                isValid = false,
                reasonCode = "IDENTITY_MISMATCH",
                messageAr = "تعارض في هوية الجلسة المحفوظة. يرجى إعادة تسجيل الدخول لتأكيد هويتك."
            )
        }
        if (savedUsername.isNotBlank() && !user.username.trim().equals(savedUsername, ignoreCase = true)) {
            return SessionValidationResult(
                isValid = false,
                reasonCode = "IDENTITY_MISMATCH",
                messageAr = "تعارض في اسم المستخدم المرتبط بالجلسة. يرجى إعادة تسجيل الدخول."
            )
        }

        // 2. Account active & approved status
        if (!user.active || user.syncStatus == "PENDING_MANAGER_APPROVAL") {
            return SessionValidationResult(
                isValid = false,
                reasonCode = "INACTIVE_OR_PENDING",
                messageAr = "تم إيقاف هذا الحساب أو تعليق صلاحياته من قبل الإدارة."
            )
        }

        // 3. Device or Session revocation check
        if (AuthSecurityManager.isDeviceRevokedForUser(context, user.userCode, deviceId)) {
            return SessionValidationResult(
                isValid = false,
                reasonCode = "REVOKED_DEVICE",
                messageAr = "تم إلغاء تفويض هذا الجهاز من قائمة الأجهزة الموثوقة. يرجى تسجيل الدخول مجدداً."
            )
        }
        if (sessionId.isNotBlank() && AuthSecurityManager.isSessionRevoked(context, sessionId)) {
            return SessionValidationResult(
                isValid = false,
                reasonCode = "REVOKED_SESSION",
                messageAr = "تم إنهاء هذه الجلسة عن بُعد لأسباب أمنية. يرجى إعادة تسجيل الدخول."
            )
        }

        val loginAt = getLoginTimestamp(context)
        val revokeEpoch = AuthSecurityManager.getUserSessionRevocationEpoch(context, user.userCode)
        if (loginAt > 0L && revokeEpoch > loginAt) {
            return SessionValidationResult(
                isValid = false,
                reasonCode = "REVOKED_SESSION",
                messageAr = "تم إبطال الجلسات السابقة لهذا الحساب بعد تحديث أمني. يرجى تسجيل الدخول مجدداً."
            )
        }

        // 4. Server sync token rotation check (e.g. password reset or remote session revocation)
        val prefs = getPrefs(context)
        val boundSyncToken = prefs?.getString(KEY_BOUND_SYNC_TOKEN, "").orEmpty()
            .ifEmpty { synchronized(inMemorySessionFallback) { (inMemorySessionFallback[KEY_BOUND_SYNC_TOKEN] as? String).orEmpty() } }
        if (boundSyncToken.isNotBlank() &&
            user.serverSyncToken.isNotBlank() &&
            user.serverSyncToken.startsWith("SYNC-REVOKED-", ignoreCase = true) &&
            boundSyncToken != user.serverSyncToken
        ) {
            return SessionValidationResult(
                isValid = false,
                reasonCode = "TOKEN_ROTATED",
                messageAr = "تم تغيير بيانات أمان الحساب أو إبطال الجلسة من جهاز آخر. يرجى تسجيل الدخول مجدداً."
            )
        }

        // 5. Absolute Session Expiration check
        val maxAgeMs = getSessionMaxAgeMs(context)
        if (loginAt > 0L && (nowMs - loginAt) > maxAgeMs) {
            return SessionValidationResult(
                isValid = false,
                reasonCode = "EXPIRED_MAX_AGE",
                messageAr = "انتهت الصلاحية الزمنية للجلسة. يرجى إعادة تسجيل الدخول للمتابعة بأمان."
            )
        }

        // 6. Session Idle Timeout check
        val lastActivityAt = getLastActivityTimestamp(context).takeIf { it > 0L } ?: loginAt
        val idleTimeoutMs = getSessionIdleTimeoutMs(context)
        if (lastActivityAt > 0L && (nowMs - lastActivityAt) > idleTimeoutMs) {
            return SessionValidationResult(
                isValid = false,
                reasonCode = "EXPIRED_IDLE",
                messageAr = "انتهت الجلسة تلقائياً بسبب عدم النشاط لحماية بياناتك المالية."
            )
        }

        return SessionValidationResult(isValid = true, reasonCode = "VALID")
    }

    /**
     * Clears the active user session on THIS device, forcing the login screen to appear.
     */
    fun clearActiveSession(context: Context?) {
        val prefs = getPrefs(context)
        if (prefs != null) {
            prefs.edit()
                .remove(KEY_ACTIVE_USER_ID)
                .remove(KEY_ACTIVE_USER_CODE)
                .remove(KEY_ACTIVE_USERNAME)
                .remove(KEY_ACTIVE_USER_ROLE)
                .remove(KEY_SESSION_ID)
                .remove(KEY_LOGIN_TIMESTAMP)
                .remove(KEY_LAST_ACTIVITY_TIMESTAMP)
                .remove(KEY_BOUND_SYNC_TOKEN)
                .remove(KEY_CREDENTIAL_FINGERPRINT)
                .apply()
        }
        synchronized(inMemorySessionFallback) {
            inMemorySessionFallback.remove(KEY_ACTIVE_USER_ID)
            inMemorySessionFallback.remove(KEY_ACTIVE_USER_CODE)
            inMemorySessionFallback.remove(KEY_ACTIVE_USERNAME)
            inMemorySessionFallback.remove(KEY_ACTIVE_USER_ROLE)
            inMemorySessionFallback.remove(KEY_SESSION_ID)
            inMemorySessionFallback.remove(KEY_LOGIN_TIMESTAMP)
            inMemorySessionFallback.remove(KEY_LAST_ACTIVITY_TIMESTAMP)
            inMemorySessionFallback.remove(KEY_BOUND_SYNC_TOKEN)
            inMemorySessionFallback.remove(KEY_CREDENTIAL_FINGERPRINT)
        }
    }

    /**
     * Returns a stable, persistent device identifier unique to this device installation.
     */
    fun getOrCreateDeviceId(context: Context?): String {
        val prefs = getPrefs(context)
        if (prefs != null) {
            var devId = prefs.getString(KEY_DEVICE_ID, null)
            if (devId.isNullOrBlank()) {
                devId = "DEV-" + UUID.randomUUID().toString().take(12).uppercase(Locale.ROOT)
                prefs.edit().putString(KEY_DEVICE_ID, devId).apply()
            }
            return devId
        }
        return synchronized(inMemorySessionFallback) {
            val existing = inMemorySessionFallback[KEY_DEVICE_ID] as? String
            if (!existing.isNullOrBlank()) {
                existing
            } else {
                val created = "DEV-" + UUID.randomUUID().toString().take(12).uppercase(Locale.ROOT)
                inMemorySessionFallback[KEY_DEVICE_ID] = created
                created
            }
        }
    }

    fun resetInMemoryStoreForTests() {
        synchronized(inMemorySessionFallback) {
            inMemorySessionFallback.clear()
        }
    }
}
