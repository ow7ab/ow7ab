package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.local.UserAccountEntity
import java.util.Base64
import java.util.Locale
import java.util.UUID

data class LoginLockoutState(
    val isLockedOut: Boolean,
    val failedAttempts: Int,
    val remainingLockoutSeconds: Long,
    val lockedUntilMillis: Long,
    val messageAr: String? = null
) {
    val isLocked: Boolean get() = isLockedOut
    val remainingSeconds: Long get() = remainingLockoutSeconds
    val message: String get() = messageAr ?: "تم إغلاق تسجيل الدخول مؤقتاً بسبب محاولات خاطئة متكررة"
    val remainingAttemptsBeforeLock: Int get() = (AuthSecurityManager.LOCKOUT_THRESHOLD_TIER_1 - failedAttempts).coerceAtLeast(0)
}

data class PasswordResetChallengeState(
    val userCode: String,
    val username: String,
    val otpHash: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val attemptsUsed: Int = 0,
    val maxAttempts: Int = 3,
    val consumed: Boolean = false,
    val channel: String = "EMAIL"
)

data class TwoFactorConfig(
    val userCode: String,
    val username: String,
    val enabled: Boolean = false,
    val secretBase32: String = "",
    val backupCodesHashed: List<String> = emptyList(),
    val requireOnTrustedDevices: Boolean = false,
    val enabledAt: Long = 0L
) {
    val hashedRecoveryCodes: List<String> get() = backupCodesHashed
}

data class TrustedDeviceRecord(
    val deviceId: String,
    val deviceName: String,
    val osVersion: String,
    val userCode: String,
    val username: String,
    val fullName: String,
    val firstSeenAt: Long,
    val lastLoginAt: Long,
    val isTrusted: Boolean = true,
    val isRevoked: Boolean = false,
    val biometricBound: Boolean = false,
    val activeSessionId: String = ""
) {
    val trusted: Boolean get() = isTrusted
    val revoked: Boolean get() = isRevoked
    val lastActiveAt: Long get() = lastLoginAt
}

data class LoginActivityRecord(
    val eventId: String = "LOG-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(4).uppercase(Locale.ROOT)}",
    val timestamp: Long = System.currentTimeMillis(),
    val userCode: String,
    val username: String,
    val fullName: String,
    val deviceId: String,
    val deviceName: String,
    val authMethod: String, // PASSWORD, BIOMETRIC, GOOGLE, 2FA, RECOVERY, LOGOUT, SESSION
    val status: String, // SUCCESS, FAILED, LOCKED_OUT, REVOKED, EXPIRED
    val isSuspicious: Boolean = false,
    val detail: String
) {
    val success: Boolean
        get() = status.equals("SUCCESS", ignoreCase = true) ||
            status.equals("ISSUED", ignoreCase = true) ||
            status.equals("LOGOUT", ignoreCase = true)
    val eventType: String get() = authMethod
}

data class SuspiciousLoginAlert(
    val alertId: String = "ALT-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(4).uppercase(Locale.ROOT)}",
    val timestamp: Long = System.currentTimeMillis(),
    val severity: String = "HIGH", // HIGH, CRITICAL, MEDIUM
    val userCode: String,
    val username: String,
    val deviceId: String,
    val deviceName: String,
    val title: String,
    val description: String,
    val acknowledged: Boolean = false
) {
    val id: String get() = alertId
    val detail: String get() = description
}

/**
 * Central Enterprise Authentication Security Manager for PMP PAINTS ERP (Phase 02).
 * Enforces:
 * 1. Brute-force protection & progressive lockout after repeated failed login attempts.
 * 2. Time-bounded, attempt-limited cryptographic OTP password reset & account recovery.
 * 3. Optional Two-Factor Authentication (RFC 6238 TOTP + single-use 8-char backup recovery codes).
 * 4. Trusted device management, per-device session tracking, and remote/local session revocation.
 * 5. Comprehensive login activity history & real-time suspicious login notifications.
 */
object AuthSecurityManager {

    private const val PREFS_NAME = "pmp_auth_security_shield_v2"
    private const val KEY_LOCKOUT_MAP = "lockout_records_v2"
    private const val KEY_RESET_CHALLENGES = "reset_challenges_v2"
    private const val KEY_2FA_CONFIGS = "two_factor_configs_v2"
    private const val KEY_TRUSTED_DEVICES = "trusted_devices_v2"
    private const val KEY_LOGIN_ACTIVITIES = "login_activities_v2"
    private const val KEY_SUSPICIOUS_ALERTS = "suspicious_alerts_v2"
    private const val KEY_REVOKED_SESSIONS = "revoked_sessions_v2"
    private const val KEY_USER_TOKEN_EPOCHS = "user_token_epochs_v2"

    private const val SECURITY_NOTIFICATION_CHANNEL_ID = "pmp_security_alerts_channel"
    private const val MAX_ACTIVITY_HISTORY = 200
    private const val MAX_SUSPICIOUS_ALERTS = 100

    // Progressive Lockout Thresholds
    const val LOCKOUT_THRESHOLD_TIER_1 = 5
    const val LOCKOUT_DURATION_TIER_1_MS = 5 * 60 * 1000L // 5 minutes
    const val LOCKOUT_THRESHOLD_TIER_2 = 8
    const val LOCKOUT_DURATION_TIER_2_MS = 15 * 60 * 1000L // 15 minutes
    const val LOCKOUT_THRESHOLD_TIER_3 = 10
    const val LOCKOUT_DURATION_TIER_3_MS = 30 * 60 * 1000L // 30 minutes

    // Password Reset OTP Constraints
    const val RESET_OTP_TTL_MS = 10 * 60 * 1000L // 10 minutes
    const val RESET_OTP_MAX_ATTEMPTS = 3

    private data class LockoutEntry(
        val failedCount: Int,
        val lastFailedAt: Long,
        val lockedUntil: Long
    )

    private val inMemoryPrefsFallback = mutableMapOf<String, String>()

    private fun getPrefs(context: Context?): SharedPreferences? {
        if (context == null) return null
        return runCatching {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
    }

    private fun readString(context: Context?, key: String, default: String = ""): String {
        val prefs = getPrefs(context)
        return if (prefs != null) {
            prefs.getString(key, default) ?: default
        } else {
            synchronized(inMemoryPrefsFallback) {
                inMemoryPrefsFallback[key] ?: default
            }
        }
    }

    private fun writeString(context: Context?, key: String, value: String) {
        val prefs = getPrefs(context)
        if (prefs != null) {
            prefs.edit().putString(key, value).apply()
        } else {
            synchronized(inMemoryPrefsFallback) {
                inMemoryPrefsFallback[key] = value
            }
        }
    }

    private fun enc(value: String): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    }

    private fun dec(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return runCatching {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun getHardwareDeviceName(): String {
        val manufacturer = runCatching { Build.MANUFACTURER.orEmpty().trim() }.getOrDefault("")
        val model = runCatching { Build.MODEL.orEmpty().trim() }.getOrDefault("")
        val combined = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }
        return combined.ifBlank { "PMP Android Terminal" }
    }

    fun getAndroidOsLabel(): String {
        val release = runCatching { Build.VERSION.RELEASE.orEmpty() }.getOrDefault("Android")
        val sdk = runCatching { Build.VERSION.SDK_INT }.getOrDefault(34)
        return "Android $release (API $sdk)"
    }

    private fun normalizeIdentityKey(identity: String): String {
        return identity.trim().lowercase(Locale.ROOT)
    }

    // =========================================================================
    // 1. FAILED LOGIN PROTECTION & PROGRESSIVE LOCKOUT
    // =========================================================================

    private fun loadLockoutMap(context: Context?): MutableMap<String, LockoutEntry> {
        val raw = readString(context, KEY_LOCKOUT_MAP, "")
        val map = mutableMapOf<String, LockoutEntry>()
        if (raw.isBlank()) return map
        raw.split("\n").forEach { line ->
            val parts = line.split("|")
            if (parts.size >= 4) {
                val key = dec(parts[0])
                val count = parts[1].toIntOrNull() ?: 0
                val lastAt = parts[2].toLongOrNull() ?: 0L
                val until = parts[3].toLongOrNull() ?: 0L
                if (key.isNotEmpty()) {
                    map[key] = LockoutEntry(count, lastAt, until)
                }
            }
        }
        return map
    }

    private fun saveLockoutMap(context: Context?, map: Map<String, LockoutEntry>) {
        val serialized = map.entries.joinToString("\n") { (k, v) ->
            "${enc(k)}|${v.failedCount}|${v.lastFailedAt}|${v.lockedUntil}"
        }
        writeString(context, KEY_LOCKOUT_MAP, serialized)
    }

    fun checkLoginAllowed(
        context: Context?,
        identity: String,
        nowMillis: Long = System.currentTimeMillis()
    ): LoginLockoutState {
        val key = normalizeIdentityKey(identity)
        if (key.isEmpty()) {
            return LoginLockoutState(false, 0, 0L, 0L)
        }
        val entry = loadLockoutMap(context)[key] ?: return LoginLockoutState(false, 0, 0L, 0L)
        val failedCount = entry.failedCount
        val lockedUntil = entry.lockedUntil

        if (lockedUntil > nowMillis) {
            val remainingSec = ((lockedUntil - nowMillis + 999L) / 1000L).coerceAtLeast(1L)
            val minutes = remainingSec / 60L
            val seconds = remainingSec % 60L
            val timeFormatted = if (minutes > 0) "${minutes} دقيقة و ${seconds} ثانية" else "${seconds} ثانية"
            return LoginLockoutState(
                isLockedOut = true,
                failedAttempts = failedCount,
                remainingLockoutSeconds = remainingSec,
                lockedUntilMillis = lockedUntil,
                messageAr = "تم قفل تسجيل الدخول مؤقتاً لهذا الحساب بسبب تكرار المحاولات الخاطئة ($failedCount محاولات). يرجى الانتظار $timeFormatted أو استخدام استعادة الحساب."
            )
        }

        return LoginLockoutState(
            isLockedOut = false,
            failedAttempts = failedCount,
            remainingLockoutSeconds = 0L,
            lockedUntilMillis = 0L
        )
    }

    fun recordFailedLoginAttempt(
        context: Context?,
        identity: String,
        matchedUserCode: String = "",
        authMethod: String = "PASSWORD",
        reason: String = "كلمة المرور غير صحيحة",
        nowMs: Long = System.currentTimeMillis(),
        nowMillis: Long = nowMs
    ): LoginLockoutState {
        val st = recordSingleFailedIdentityAttempt(
            context = context,
            identity = identity,
            authMethod = authMethod,
            reason = reason,
            nowMillis = nowMillis,
            emitAlerts = true
        )
        if (matchedUserCode.isNotBlank() && !matchedUserCode.equals(identity, ignoreCase = true)) {
            recordSingleFailedIdentityAttempt(
                context = context,
                identity = matchedUserCode,
                authMethod = authMethod,
                reason = reason,
                nowMillis = nowMillis,
                emitAlerts = false
            )
        }
        return st
    }

    private fun recordSingleFailedIdentityAttempt(
        context: Context?,
        identity: String,
        authMethod: String,
        reason: String,
        nowMillis: Long,
        emitAlerts: Boolean
    ): LoginLockoutState {
        val key = normalizeIdentityKey(identity)
        if (key.isEmpty()) {
            return LoginLockoutState(false, 1, 0L, 0L)
        }
        val map = loadLockoutMap(context)
        val prevCount = map[key]?.failedCount ?: 0
        val newCount = prevCount + 1

        val lockoutDurationMs = when {
            newCount >= LOCKOUT_THRESHOLD_TIER_3 -> LOCKOUT_DURATION_TIER_3_MS
            newCount >= LOCKOUT_THRESHOLD_TIER_2 -> LOCKOUT_DURATION_TIER_2_MS
            newCount >= LOCKOUT_THRESHOLD_TIER_1 -> LOCKOUT_DURATION_TIER_1_MS
            else -> 0L
        }
        val lockedUntil = if (lockoutDurationMs > 0L) nowMillis + lockoutDurationMs else 0L

        map[key] = LockoutEntry(newCount, nowMillis, lockedUntil)
        saveLockoutMap(context, map)

        if (emitAlerts) {
            val devId = if (context != null) DeviceSessionStore.getOrCreateDeviceId(context) else "DEV-LOCAL"
            val devName = getHardwareDeviceName()
            val isSuspicious = newCount >= 3

            recordLoginActivity(
                context = context,
                record = LoginActivityRecord(
                    timestamp = nowMillis,
                    userCode = key.uppercase(Locale.ROOT),
                    username = key,
                    fullName = key,
                    deviceId = devId,
                    deviceName = devName,
                    authMethod = authMethod,
                    status = if (lockedUntil > nowMillis) "LOCKED_OUT" else "FAILED",
                    isSuspicious = isSuspicious,
                    detail = "$reason (المحاولة الفاشلة رقم $newCount)"
                )
            )

            if (newCount == 3 || newCount == LOCKOUT_THRESHOLD_TIER_1 || newCount >= LOCKOUT_THRESHOLD_TIER_2) {
                val severity = if (newCount >= LOCKOUT_THRESHOLD_TIER_1) "CRITICAL" else "HIGH"
                recordSuspiciousAlert(
                    context = context,
                    alert = SuspiciousLoginAlert(
                        timestamp = nowMillis,
                        severity = severity,
                        userCode = key.uppercase(Locale.ROOT),
                        username = key,
                        deviceId = devId,
                        deviceName = devName,
                        title = if (lockedUntil > nowMillis) {
                            "تنبيه أمني حرج: قفل الحساب بعد $newCount محاولات دخول فاشلة"
                        } else {
                            "تنبيه أمني: محاولات دخول متكررة غير ناجحة ($newCount)"
                        },
                        description = "تم رصد $newCount محاولات تسجيل دخول فاشلة للحساب ($identity) عبر ($authMethod) على الجهاز ($devName • $devId). السبب: $reason"
                    )
                )
            }
        }

        return checkLoginAllowed(context, identity, nowMillis)
    }

    fun clearFailedLoginAttempts(context: Context?, vararg identities: String) {
        val map = loadLockoutMap(context)
        var modified = false
        for (id in identities) {
            val key = normalizeIdentityKey(id)
            if (key.isNotEmpty() && map.remove(key) != null) {
                modified = true
            }
        }
        if (modified) {
            saveLockoutMap(context, map)
        }
    }

    // =========================================================================
    // 2. SECURE PASSWORD RESET CHALLENGE (TIME-BOUNDED + ATTEMPT-LIMITED)
    // =========================================================================

    private fun loadResetChallenges(context: Context?): MutableMap<String, PasswordResetChallengeState> {
        val raw = readString(context, KEY_RESET_CHALLENGES, "")
        val map = mutableMapOf<String, PasswordResetChallengeState>()
        if (raw.isBlank()) return map
        raw.split("\n").forEach { line ->
            val p = line.split("|")
            if (p.size >= 9) {
                val key = dec(p[0])
                val state = PasswordResetChallengeState(
                    userCode = dec(p[1]),
                    username = dec(p[2]),
                    otpHash = dec(p[3]),
                    issuedAt = p[4].toLongOrNull() ?: 0L,
                    expiresAt = p[5].toLongOrNull() ?: 0L,
                    attemptsUsed = p[6].toIntOrNull() ?: 0,
                    maxAttempts = p[7].toIntOrNull() ?: RESET_OTP_MAX_ATTEMPTS,
                    consumed = p[8].toBoolean(),
                    channel = if (p.size > 9) dec(p[9]) else "EMAIL"
                )
                if (key.isNotEmpty()) map[key] = state
            }
        }
        return map
    }

    private fun saveResetChallenge(context: Context?, state: PasswordResetChallengeState) {
        val map = loadResetChallenges(context)
        val keyCode = normalizeIdentityKey(state.userCode)
        val keyUser = normalizeIdentityKey(state.username)
        if (keyCode.isNotEmpty()) map[keyCode] = state
        if (keyUser.isNotEmpty()) map[keyUser] = state
        val serialized = map.entries.joinToString("\n") { (k, s) ->
            listOf(
                enc(k),
                enc(s.userCode),
                enc(s.username),
                enc(s.otpHash),
                s.issuedAt.toString(),
                s.expiresAt.toString(),
                s.attemptsUsed.toString(),
                s.maxAttempts.toString(),
                s.consumed.toString(),
                enc(s.channel)
            ).joinToString("|")
        }
        writeString(context, KEY_RESET_CHALLENGES, serialized)
    }

    private fun getResetChallenge(context: Context?, normalizedKey: String): PasswordResetChallengeState? {
        return loadResetChallenges(context)[normalizedKey]
    }

    fun issuePasswordResetChallenge(
        context: Context?,
        user: UserAccountEntity,
        channel: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<String, PasswordResetChallengeState> {
        val otpCode = PasswordSecurityUtil.generateSecureSixDigitOtp()
        val otpHash = PasswordSecurityUtil.hashPassword(otpCode)
        val challenge = PasswordResetChallengeState(
            userCode = user.userCode.trim(),
            username = user.username.trim().lowercase(Locale.ROOT),
            otpHash = otpHash,
            issuedAt = nowMillis,
            expiresAt = nowMillis + RESET_OTP_TTL_MS,
            attemptsUsed = 0,
            maxAttempts = RESET_OTP_MAX_ATTEMPTS,
            consumed = false,
            channel = channel
        )
        saveResetChallenge(context, challenge)

        val devId = if (context != null) DeviceSessionStore.getOrCreateDeviceId(context) else "DEV-LOCAL"
        recordLoginActivity(
            context = context,
            record = LoginActivityRecord(
                timestamp = nowMillis,
                userCode = user.userCode,
                username = user.username,
                fullName = user.fullName,
                deviceId = devId,
                deviceName = getHardwareDeviceName(),
                authMethod = "PASSWORD_RESET_OTP",
                status = "ISSUED",
                isSuspicious = false,
                detail = "إصدار رمز استعادة كلمة المرور (صالح لمدة 10 دقائق) عبر قناة ($channel)"
            )
        )
        return Pair(otpCode, challenge)
    }

    fun issuePasswordResetChallenge(
        context: Context?,
        userCode: String,
        username: String,
        channel: String = "EMAIL",
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val otpCode = PasswordSecurityUtil.generateSecureSixDigitOtp()
        val otpHash = PasswordSecurityUtil.hashPassword(otpCode)
        val challenge = PasswordResetChallengeState(
            userCode = userCode.trim(),
            username = username.trim().lowercase(Locale.ROOT),
            otpHash = otpHash,
            issuedAt = nowMs,
            expiresAt = nowMs + RESET_OTP_TTL_MS,
            attemptsUsed = 0,
            maxAttempts = RESET_OTP_MAX_ATTEMPTS,
            consumed = false,
            channel = channel
        )
        saveResetChallenge(context, challenge)
        val devId = if (context != null) DeviceSessionStore.getOrCreateDeviceId(context) else "DEV-LOCAL"
        recordLoginActivity(
            context = context,
            record = LoginActivityRecord(
                timestamp = nowMs,
                userCode = userCode,
                username = username,
                fullName = username.ifBlank { userCode },
                deviceId = devId,
                deviceName = getHardwareDeviceName(),
                authMethod = "PASSWORD_RESET_OTP",
                status = "ISSUED",
                isSuspicious = false,
                detail = "إصدار رمز استعادة كلمة المرور (صالح لمدة 10 دقائق) عبر قناة ($channel)"
            )
        )
        return otpCode
    }

    fun verifyAndConsumePasswordResetOtp(
        context: Context?,
        userCode: String = "",
        userCodeOrUsername: String = userCode,
        otpInput: String = "",
        enteredOtp: String = otpInput,
        nowMs: Long = System.currentTimeMillis(),
        nowMillis: Long = nowMs
    ): Result<Unit> {
        val key = normalizeIdentityKey(userCodeOrUsername.ifBlank { userCode })
        val effectiveOtp = enteredOtp.ifBlank { otpInput }
        val challenge = getResetChallenge(context, key)
            ?: return Result.failure(Exception("لا يوجد طلب استعادة نشط لهذا الحساب. يرجى طلب رمز تحقق جديد."))

        if (challenge.consumed) {
            return Result.failure(Exception("تم استخدام رمز التحقق هذا مسبقاً. يرجى طلب رمز جديد."))
        }
        if (nowMillis > challenge.expiresAt) {
            return Result.failure(Exception("انتهت صلاحية رمز التحقق (10 دقائق). يرجى طلب رمز جديد."))
        }
        if (challenge.attemptsUsed >= challenge.maxAttempts) {
            return Result.failure(Exception("تم تجاوز الحد الأقصى لمحاولات إدخال الرمز (${challenge.maxAttempts} محاولات). يرجى طلب رمز جديد."))
        }

        val isMatch = PasswordSecurityUtil.verifyPassword(effectiveOtp.trim(), challenge.otpHash)
        if (!isMatch) {
            val nextAttempts = challenge.attemptsUsed + 1
            val updated = challenge.copy(
                attemptsUsed = nextAttempts,
                consumed = nextAttempts >= challenge.maxAttempts
            )
            saveResetChallenge(context, updated)
            val remaining = (challenge.maxAttempts - nextAttempts).coerceAtLeast(0)
            return if (remaining > 0) {
                Result.failure(Exception("كود التحقق غير صحيح. المحاولات المتبقية: $remaining"))
            } else {
                Result.failure(Exception("تم إلغاء الرمز بسبب استنفاد المحاولات المسموحة. يرجى طلب رمز جديد."))
            }
        }

        // Validated -> mark consumed immediately
        saveResetChallenge(context, challenge.copy(attemptsUsed = challenge.attemptsUsed + 1, consumed = true))
        return Result.success(Unit)
    }

    // =========================================================================
    // 3. OPTIONAL TWO-FACTOR AUTHENTICATION (2FA) & BACKUP RECOVERY CODES
    // =========================================================================

    private fun loadTwoFactorMap(context: Context?): MutableMap<String, TwoFactorConfig> {
        val raw = readString(context, KEY_2FA_CONFIGS, "")
        val map = mutableMapOf<String, TwoFactorConfig>()
        if (raw.isBlank()) return map
        raw.split("\n").forEach { line ->
            val p = line.split("|")
            if (p.size >= 8) {
                val key = dec(p[0])
                val codesRaw = dec(p[5])
                val codes = if (codesRaw.isBlank()) emptyList() else codesRaw.split(",").filter { it.isNotBlank() }
                val cfg = TwoFactorConfig(
                    userCode = dec(p[1]),
                    username = dec(p[2]),
                    enabled = p[3].toBoolean(),
                    secretBase32 = dec(p[4]),
                    backupCodesHashed = codes,
                    requireOnTrustedDevices = p[6].toBoolean(),
                    enabledAt = p[7].toLongOrNull() ?: 0L
                )
                if (key.isNotEmpty()) map[key] = cfg
            }
        }
        return map
    }

    private fun saveTwoFactorConfig(context: Context?, config: TwoFactorConfig) {
        val map = loadTwoFactorMap(context)
        val keyCode = normalizeIdentityKey(config.userCode)
        val keyUser = normalizeIdentityKey(config.username)
        if (keyCode.isNotEmpty()) map[keyCode] = config
        if (keyUser.isNotEmpty()) map[keyUser] = config
        val serialized = map.entries.joinToString("\n") { (k, c) ->
            listOf(
                enc(k),
                enc(c.userCode),
                enc(c.username),
                c.enabled.toString(),
                enc(c.secretBase32),
                enc(c.backupCodesHashed.joinToString(",")),
                c.requireOnTrustedDevices.toString(),
                c.enabledAt.toString()
            ).joinToString("|")
        }
        writeString(context, KEY_2FA_CONFIGS, serialized)
    }

    fun getTwoFactorConfig(context: Context?, userCode: String, username: String = ""): TwoFactorConfig {
        val map = loadTwoFactorMap(context)
        val keyCode = normalizeIdentityKey(userCode)
        val keyUser = normalizeIdentityKey(username)
        return map[keyCode] ?: (if (keyUser.isNotEmpty()) map[keyUser] else null)
            ?: TwoFactorConfig(userCode = userCode, username = username, enabled = false)
    }

    fun enableTwoFactorAuth(
        context: Context?,
        user: UserAccountEntity,
        requireOnTrustedDevices: Boolean = false
    ): Pair<TwoFactorConfig, List<String>> {
        val existing = getTwoFactorConfig(context, user.userCode, user.username)
        val secret = existing.secretBase32.ifBlank { PasswordSecurityUtil.generateTotpSecretBase32() }
        val plainRecoveryCodes = PasswordSecurityUtil.generateBackupRecoveryCodes(8)
        val hashedRecoveryCodes = plainRecoveryCodes.map {
            PasswordSecurityUtil.hashPassword(PasswordSecurityUtil.normalizeRecoveryCode(it))
        }
        val config = TwoFactorConfig(
            userCode = user.userCode,
            username = user.username,
            enabled = true,
            secretBase32 = secret,
            backupCodesHashed = hashedRecoveryCodes,
            requireOnTrustedDevices = requireOnTrustedDevices,
            enabledAt = System.currentTimeMillis()
        )
        saveTwoFactorConfig(context, config)
        return Pair(config, plainRecoveryCodes)
    }

    fun enableTwoFactorAuth(
        context: Context?,
        userCode: String,
        username: String,
        requireOnEveryLogin: Boolean = true
    ): Pair<String, List<String>> {
        val existing = getTwoFactorConfig(context, userCode, username)
        val secret = existing.secretBase32.ifBlank { PasswordSecurityUtil.generateTotpSecretBase32() }
        val plainRecoveryCodes = PasswordSecurityUtil.generateBackupRecoveryCodes(8)
        val hashedRecoveryCodes = plainRecoveryCodes.map {
            PasswordSecurityUtil.hashPassword(PasswordSecurityUtil.normalizeRecoveryCode(it))
        }
        val config = TwoFactorConfig(
            userCode = userCode,
            username = username,
            enabled = true,
            secretBase32 = secret,
            backupCodesHashed = hashedRecoveryCodes,
            requireOnTrustedDevices = requireOnEveryLogin,
            enabledAt = System.currentTimeMillis()
        )
        saveTwoFactorConfig(context, config)
        return Pair(secret, plainRecoveryCodes)
    }

    fun disableTwoFactorAuth(context: Context?, userCode: String, username: String = "") {
        val existing = getTwoFactorConfig(context, userCode, username)
        saveTwoFactorConfig(context, existing.copy(enabled = false))
    }

    fun regenerateRecoveryCodesForUser(context: Context?, user: UserAccountEntity): List<String> {
        return regenerateBackupRecoveryCodes(context, user.userCode, user.username)
    }

    fun regenerateBackupRecoveryCodes(
        context: Context?,
        userCode: String,
        username: String = ""
    ): List<String> {
        val existing = getTwoFactorConfig(context, userCode, username)
        val secret = existing.secretBase32.ifBlank { PasswordSecurityUtil.generateTotpSecretBase32() }
        val plainRecoveryCodes = PasswordSecurityUtil.generateBackupRecoveryCodes(8)
        val hashedRecoveryCodes = plainRecoveryCodes.map {
            PasswordSecurityUtil.hashPassword(PasswordSecurityUtil.normalizeRecoveryCode(it))
        }
        val updated = existing.copy(
            userCode = userCode,
            username = username,
            secretBase32 = secret,
            backupCodesHashed = hashedRecoveryCodes
        )
        saveTwoFactorConfig(context, updated)
        return plainRecoveryCodes
    }

    fun verifyTwoFactorCodeOrRecovery(
        context: Context?,
        userCode: String,
        username: String,
        enteredCode: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val config = getTwoFactorConfig(context, userCode, username)
        if (!config.enabled) return true

        val clean = enteredCode.trim()
        if (clean.length == 6 && clean.all { it.isDigit() }) {
            if (PasswordSecurityUtil.verifyTotpCode(config.secretBase32, clean, nowMillis)) {
                return true
            }
        }
        return consumeBackupRecoveryCode(context, userCode, username, clean)
    }

    fun verifyTwoFactorCodeOrRecovery(
        context: Context?,
        userCode: String,
        inputCode: String,
        nowMs: Long = System.currentTimeMillis()
    ): Result<Boolean> {
        val ok = verifyTwoFactorCodeOrRecovery(
            context = context,
            userCode = userCode,
            username = "",
            enteredCode = inputCode,
            nowMillis = nowMs
        )
        return if (ok) {
            Result.success(true)
        } else {
            Result.failure(Exception("رمز المصادقة الثنائية (2FA) أو كود الاسترداد غير صحيح"))
        }
    }

    fun consumeBackupRecoveryCode(
        context: Context?,
        userCode: String,
        username: String,
        enteredRecoveryCode: String
    ): Boolean {
        val normalized = PasswordSecurityUtil.normalizeRecoveryCode(enteredRecoveryCode)
        if (normalized.length < 6) return false
        val config = getTwoFactorConfig(context, userCode, username)
        if (config.backupCodesHashed.isEmpty()) return false

        val matchIndex = config.backupCodesHashed.indexOfFirst { storedHash ->
            PasswordSecurityUtil.verifyPassword(normalized, storedHash)
        }
        if (matchIndex < 0) return false

        val remainingHashes = config.backupCodesHashed.toMutableList().apply { removeAt(matchIndex) }
        saveTwoFactorConfig(context, config.copy(backupCodesHashed = remainingHashes))
        return true
    }

    fun consumeBackupRecoveryCode(
        context: Context?,
        userCode: String,
        rawRecoveryCode: String
    ): Boolean {
        return consumeBackupRecoveryCode(
            context = context,
            userCode = userCode,
            username = "",
            enteredRecoveryCode = rawRecoveryCode
        )
    }

    fun isTwoFactorRequiredForLogin(
        context: Context?,
        user: UserAccountEntity,
        deviceId: String
    ): Boolean {
        return isTwoFactorRequiredForLogin(context, user.userCode, deviceId)
    }

    fun isTwoFactorRequiredForLogin(
        context: Context?,
        userCode: String,
        deviceId: String
    ): Boolean {
        val config = getTwoFactorConfig(context, userCode, "")
        if (!config.enabled) return false
        if (config.requireOnTrustedDevices) return true
        val trusted = isDeviceTrustedForUser(context, userCode, deviceId)
        return !trusted
    }

    // =========================================================================
    // 4. TRUSTED DEVICE MANAGEMENT & DEVICE REVOCATION
    // =========================================================================

    fun registerAuthenticatedDevice(
        context: Context?,
        user: UserAccountEntity,
        deviceId: String,
        sessionId: String,
        biometricBound: Boolean = false,
        markTrusted: Boolean = true,
        nowMillis: Long = System.currentTimeMillis()
    ): TrustedDeviceRecord {
        val all = getAllTrustedDevices(context).toMutableList()
        val existingIdx = all.indexOfFirst {
            it.deviceId.equals(deviceId, ignoreCase = true) &&
                (it.userCode.equals(user.userCode, ignoreCase = true) ||
                    it.username.equals(user.username, ignoreCase = true))
        }

        val userHasOtherDevices = all.any {
            (it.userCode.equals(user.userCode, ignoreCase = true) || it.username.equals(user.username, ignoreCase = true)) &&
                !it.deviceId.equals(deviceId, ignoreCase = true)
        }
        if (existingIdx < 0 && userHasOtherDevices) {
            recordSuspiciousAlert(
                context = context,
                alert = SuspiciousLoginAlert(
                    timestamp = nowMillis,
                    severity = "MEDIUM",
                    userCode = user.userCode,
                    username = user.username,
                    deviceId = deviceId,
                    deviceName = getHardwareDeviceName(),
                    title = "تسجيل دخول من جهاز جديد لحساب (${user.fullName})",
                    description = "تم تسجيل الدخول إلى الحساب (${user.username}) من جهاز جديد (${getHardwareDeviceName()} • $deviceId). إذا لم تكن أنت، يرجى إلغاء تفويض الجهاز وتغيير كلمة المرور فوراً."
                )
            )
        }

        val record = if (existingIdx >= 0) {
            val prev = all[existingIdx]
            prev.copy(
                userCode = user.userCode,
                username = user.username,
                fullName = user.fullName,
                lastLoginAt = nowMillis,
                isTrusted = if (prev.isRevoked) false else (prev.isTrusted || markTrusted),
                biometricBound = biometricBound || prev.biometricBound,
                activeSessionId = sessionId
            ).also { all[existingIdx] = it }
        } else {
            TrustedDeviceRecord(
                deviceId = deviceId,
                deviceName = getHardwareDeviceName(),
                osVersion = getAndroidOsLabel(),
                userCode = user.userCode,
                username = user.username,
                fullName = user.fullName,
                firstSeenAt = nowMillis,
                lastLoginAt = nowMillis,
                isTrusted = markTrusted,
                isRevoked = false,
                biometricBound = biometricBound,
                activeSessionId = sessionId
            ).also { all.add(0, it) }
        }

        saveAllTrustedDevices(context, all)
        return record
    }

    fun registerAuthenticatedDevice(
        context: Context?,
        userCode: String,
        username: String,
        autoTrustFirstDevice: Boolean = true
    ): TrustedDeviceRecord {
        val devId = DeviceSessionStore.getOrCreateDeviceId(context)
        val sessId = DeviceSessionStore.getActiveSessionId(context)
        val stubUser = UserAccountEntity(
            id = 1L,
            userCode = userCode,
            fullName = username.ifBlank { userCode },
            username = username,
            phoneOrEmail = "",
            pinOrPassword = "",
            role = "موظف",
            branch = "المقر الرئيسي",
            permissions = "",
            serverSyncToken = "",
            remoteServerId = "",
            syncStatus = "APPROVED_BY_MANAGER",
            serverEndpoint = "",
            isCurrentSession = true,
            active = true
        )
        return registerAuthenticatedDevice(
            context = context,
            user = stubUser,
            deviceId = devId,
            sessionId = sessId,
            biometricBound = false,
            markTrusted = autoTrustFirstDevice
        )
    }

    fun getTrustedDevicesForUser(context: Context?, userCode: String, username: String = ""): List<TrustedDeviceRecord> {
        return getAllTrustedDevices(context).filter {
            it.userCode.equals(userCode, ignoreCase = true) ||
                (username.isNotBlank() && it.username.equals(username, ignoreCase = true))
        }
    }

    fun getAllTrustedDevices(context: Context?): List<TrustedDeviceRecord> {
        val raw = readString(context, KEY_TRUSTED_DEVICES, "")
        if (raw.isBlank()) return emptyList()
        val list = mutableListOf<TrustedDeviceRecord>()
        raw.split("\n").forEach { line ->
            val p = line.split("|")
            if (p.size >= 12) {
                list.add(
                    TrustedDeviceRecord(
                        deviceId = dec(p[0]),
                        deviceName = dec(p[1]).ifBlank { "Android Device" },
                        osVersion = dec(p[2]).ifBlank { "Android" },
                        userCode = dec(p[3]),
                        username = dec(p[4]),
                        fullName = dec(p[5]),
                        firstSeenAt = p[6].toLongOrNull() ?: 0L,
                        lastLoginAt = p[7].toLongOrNull() ?: 0L,
                        isTrusted = p[8].toBoolean(),
                        isRevoked = p[9].toBoolean(),
                        biometricBound = p[10].toBoolean(),
                        activeSessionId = dec(p[11])
                    )
                )
            }
        }
        return list
    }

    private fun saveAllTrustedDevices(context: Context?, devices: List<TrustedDeviceRecord>) {
        val serialized = devices.take(100).joinToString("\n") { dev ->
            listOf(
                enc(dev.deviceId),
                enc(dev.deviceName),
                enc(dev.osVersion),
                enc(dev.userCode),
                enc(dev.username),
                enc(dev.fullName),
                dev.firstSeenAt.toString(),
                dev.lastLoginAt.toString(),
                dev.isTrusted.toString(),
                dev.isRevoked.toString(),
                dev.biometricBound.toString(),
                enc(dev.activeSessionId)
            ).joinToString("|")
        }
        writeString(context, KEY_TRUSTED_DEVICES, serialized)
    }

    fun setDeviceTrustStatus(
        context: Context?,
        userCode: String,
        deviceId: String,
        trusted: Boolean
    ) {
        val all = getAllTrustedDevices(context).map { dev ->
            if (dev.deviceId.equals(deviceId, ignoreCase = true) && dev.userCode.equals(userCode, ignoreCase = true)) {
                dev.copy(isTrusted = trusted, isRevoked = if (trusted) false else dev.isRevoked)
            } else {
                dev
            }
        }
        saveAllTrustedDevices(context, all)
    }

    fun revokeTrustedDevice(
        context: Context?,
        userCode: String,
        deviceId: String = "",
        targetDeviceId: String = deviceId,
        actor: String = "",
        reason: String = if (actor.isNotBlank()) "إلغاء تفويض الجهاز بواسطة ($actor)" else "إلغاء تفويض الجهاز من قبل المستخدم أو الإدارة"
    ) {
        val effectiveDeviceId = targetDeviceId.ifBlank { deviceId }
        val all = getAllTrustedDevices(context).map { dev ->
            if (dev.deviceId.equals(effectiveDeviceId, ignoreCase = true) && dev.userCode.equals(userCode, ignoreCase = true)) {
                if (dev.activeSessionId.isNotBlank()) {
                    revokeSessionById(context, dev.activeSessionId)
                }
                dev.copy(isTrusted = false, isRevoked = true, biometricBound = false, activeSessionId = "")
            } else {
                dev
            }
        }
        saveAllTrustedDevices(context, all)
        recordLoginActivity(
            context = context,
            record = LoginActivityRecord(
                userCode = userCode,
                username = userCode,
                fullName = userCode,
                deviceId = effectiveDeviceId,
                deviceName = getHardwareDeviceName(),
                authMethod = "DEVICE_MANAGEMENT",
                status = "REVOKED",
                isSuspicious = false,
                detail = "$reason ($effectiveDeviceId)"
            )
        )
    }

    fun restoreRevokedDevice(
        context: Context?,
        userCode: String,
        deviceId: String
    ) {
        val all = getAllTrustedDevices(context).map { dev ->
            if (dev.deviceId.equals(deviceId, ignoreCase = true) && dev.userCode.equals(userCode, ignoreCase = true)) {
                dev.copy(isRevoked = false, isTrusted = true)
            } else {
                dev
            }
        }
        saveAllTrustedDevices(context, all)
    }

    fun isDeviceRevokedForUser(context: Context?, userCode: String, deviceId: String): Boolean {
        return getAllTrustedDevices(context).any {
            it.deviceId.equals(deviceId, ignoreCase = true) &&
                it.userCode.equals(userCode, ignoreCase = true) &&
                it.isRevoked
        }
    }

    fun isDeviceTrustedForUser(context: Context?, userCode: String, deviceId: String): Boolean {
        val match = getAllTrustedDevices(context).firstOrNull {
            it.deviceId.equals(deviceId, ignoreCase = true) &&
                it.userCode.equals(userCode, ignoreCase = true)
        } ?: return false
        return match.isTrusted && !match.isRevoked
    }

    // =========================================================================
    // 5. SESSION REVOCATION & TOKEN EPOCH VALIDATION
    // =========================================================================

    fun revokeSessionById(context: Context?, sessionId: String) {
        if (sessionId.isBlank()) return
        val raw = readString(context, KEY_REVOKED_SESSIONS, "")
        val set = LinkedHashSet<String>()
        set.add(sessionId.trim())
        if (raw.isNotBlank()) {
            raw.split("\n").filter { it.isNotBlank() }.forEach { set.add(it.trim()) }
        }
        writeString(context, KEY_REVOKED_SESSIONS, set.take(200).joinToString("\n"))
    }

    fun revokeSessionById(
        context: Context?,
        sessionId: String,
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        revokeSessionById(context, sessionId)
    }

    fun isSessionRevoked(context: Context?, sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        val raw = readString(context, KEY_REVOKED_SESSIONS, "")
        if (raw.isBlank()) return false
        return raw.split("\n").any { it.trim().equals(sessionId.trim(), ignoreCase = true) }
    }

    fun revokeAllSessionsForUser(
        context: Context?,
        userCode: String,
        keepSessionId: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val epochs = loadUserTokenEpochs(context)
        epochs[normalizeIdentityKey(userCode)] = nowMillis
        saveUserTokenEpochs(context, epochs)

        val devices = getAllTrustedDevices(context).map { dev ->
            if (dev.userCode.equals(userCode, ignoreCase = true)) {
                if (dev.activeSessionId.isNotBlank() && dev.activeSessionId != keepSessionId) {
                    revokeSessionById(context, dev.activeSessionId)
                    dev.copy(activeSessionId = "")
                } else {
                    dev
                }
            } else {
                dev
            }
        }
        saveAllTrustedDevices(context, devices)
    }

    fun getUserSessionRevocationEpoch(context: Context?, userCode: String): Long {
        return loadUserTokenEpochs(context)[normalizeIdentityKey(userCode)] ?: 0L
    }

    private fun loadUserTokenEpochs(context: Context?): MutableMap<String, Long> {
        val raw = readString(context, KEY_USER_TOKEN_EPOCHS, "")
        val map = mutableMapOf<String, Long>()
        if (raw.isBlank()) return map
        raw.split("\n").forEach { line ->
            val p = line.split("|")
            if (p.size >= 2) {
                val k = dec(p[0])
                val v = p[1].toLongOrNull() ?: 0L
                if (k.isNotEmpty()) map[k] = v
            }
        }
        return map
    }

    private fun saveUserTokenEpochs(context: Context?, map: Map<String, Long>) {
        val serialized = map.entries.joinToString("\n") { (k, v) -> "${enc(k)}|$v" }
        writeString(context, KEY_USER_TOKEN_EPOCHS, serialized)
    }

    // =========================================================================
    // 6. LOGIN ACTIVITY HISTORY & SUSPICIOUS LOGIN NOTIFICATIONS
    // =========================================================================

    fun recordLoginActivity(context: Context?, record: LoginActivityRecord) {
        val existing = getLoginActivities(context).toMutableList()
        existing.add(0, record)
        val serialized = existing.take(MAX_ACTIVITY_HISTORY).joinToString("\n") { item ->
            listOf(
                enc(item.eventId),
                item.timestamp.toString(),
                enc(item.userCode),
                enc(item.username),
                enc(item.fullName),
                enc(item.deviceId),
                enc(item.deviceName),
                enc(item.authMethod),
                enc(item.status),
                item.isSuspicious.toString(),
                enc(item.detail)
            ).joinToString("|")
        }
        writeString(context, KEY_LOGIN_ACTIVITIES, serialized)
    }

    fun recordLoginActivity(
        context: Context?,
        userCode: String,
        username: String,
        fullName: String,
        eventType: String,
        method: String,
        success: Boolean,
        detail: String
    ) {
        val devId = DeviceSessionStore.getOrCreateDeviceId(context)
        recordLoginActivity(
            context = context,
            record = LoginActivityRecord(
                userCode = userCode,
                username = username,
                fullName = fullName.ifBlank { username.ifBlank { userCode } },
                deviceId = devId,
                deviceName = getHardwareDeviceName(),
                authMethod = eventType.ifBlank { method },
                status = if (success) "SUCCESS" else "FAILED",
                isSuspicious = !success,
                detail = detail
            )
        )
    }

    fun getLoginActivities(context: Context?, userCodeFilter: String? = null): List<LoginActivityRecord> {
        val raw = readString(context, KEY_LOGIN_ACTIVITIES, "")
        if (raw.isBlank()) return emptyList()
        val list = mutableListOf<LoginActivityRecord>()
        raw.split("\n").forEach { line ->
            val p = line.split("|")
            if (p.size >= 11) {
                val item = LoginActivityRecord(
                    eventId = dec(p[0]),
                    timestamp = p[1].toLongOrNull() ?: 0L,
                    userCode = dec(p[2]),
                    username = dec(p[3]),
                    fullName = dec(p[4]),
                    deviceId = dec(p[5]),
                    deviceName = dec(p[6]),
                    authMethod = dec(p[7]),
                    status = dec(p[8]),
                    isSuspicious = p[9].toBoolean(),
                    detail = dec(p[10])
                )
                if (userCodeFilter.isNullOrBlank() ||
                    item.userCode.equals(userCodeFilter, ignoreCase = true) ||
                    item.username.equals(userCodeFilter, ignoreCase = true)
                ) {
                    list.add(item)
                }
            }
        }
        return list
    }

    fun recordSuspiciousAlert(context: Context?, alert: SuspiciousLoginAlert) {
        val existing = getSuspiciousAlerts(context).toMutableList()
        existing.add(0, alert)
        saveSuspiciousAlerts(context, existing.take(MAX_SUSPICIOUS_ALERTS))
        if (context != null) {
            dispatchSystemSecurityNotification(context, alert)
        }
    }

    fun getSuspiciousAlerts(context: Context?, onlyUnacknowledged: Boolean = false): List<SuspiciousLoginAlert> {
        val raw = readString(context, KEY_SUSPICIOUS_ALERTS, "")
        if (raw.isBlank()) return emptyList()
        val list = mutableListOf<SuspiciousLoginAlert>()
        raw.split("\n").forEach { line ->
            val p = line.split("|")
            if (p.size >= 10) {
                val item = SuspiciousLoginAlert(
                    alertId = dec(p[0]),
                    timestamp = p[1].toLongOrNull() ?: 0L,
                    severity = dec(p[2]).ifBlank { "HIGH" },
                    userCode = dec(p[3]),
                    username = dec(p[4]),
                    deviceId = dec(p[5]),
                    deviceName = dec(p[6]),
                    title = dec(p[7]),
                    description = dec(p[8]),
                    acknowledged = p[9].toBoolean()
                )
                if (!onlyUnacknowledged || !item.acknowledged) {
                    list.add(item)
                }
            }
        }
        return list
    }

    fun acknowledgeSuspiciousAlert(context: Context?, alertId: String) {
        val all = getSuspiciousAlerts(context, onlyUnacknowledged = false).map { item ->
            if (item.alertId == alertId) item.copy(acknowledged = true) else item
        }
        saveSuspiciousAlerts(context, all)
    }

    private fun saveSuspiciousAlerts(context: Context?, alerts: List<SuspiciousLoginAlert>) {
        val serialized = alerts.joinToString("\n") { item ->
            listOf(
                enc(item.alertId),
                item.timestamp.toString(),
                enc(item.severity),
                enc(item.userCode),
                enc(item.username),
                enc(item.deviceId),
                enc(item.deviceName),
                enc(item.title),
                enc(item.description),
                item.acknowledged.toString()
            ).joinToString("|")
        }
        writeString(context, KEY_SUSPICIOUS_ALERTS, serialized)
    }

    private fun dispatchSystemSecurityNotification(context: Context, alert: SuspiciousLoginAlert) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    SECURITY_NOTIFICATION_CHANNEL_ID,
                    "تنبيهات أمان الحسابات (PMP Security)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "إشعارات محاولات الدخول المشبوهة وقفل الحسابات والأجهزة الجديدة"
                }
                nm.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(context, SECURITY_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_pmp_emblem)
                .setContentTitle(alert.title)
                .setContentText(alert.description)
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.description))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify((alert.timestamp % 100000).toInt(), notification)
        }
    }

    fun resetAllInMemoryStateForTests() {
        synchronized(inMemoryPrefsFallback) {
            inMemoryPrefsFallback.clear()
        }
    }

    fun resetInMemoryStoreForTests() = resetAllInMemoryStateForTests()
}
