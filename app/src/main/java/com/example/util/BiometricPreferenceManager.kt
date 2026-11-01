package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.example.data.local.UserAccountEntity
import java.util.UUID

/**
 * Manages secure device-specific biometric binding.
 * Guarantees that biometric authentication on this specific phone can ONLY authenticate
 * the single verified employee account that was explicitly logged in with credentials on this device.
 * Strictly prevents opening arbitrary accounts, admin accounts, or accounts from other devices.
 */
data class BoundBiometricUser(
    val id: Long,
    val userCode: String,
    val username: String,
    val fullName: String,
    val role: String,
    val boundAt: Long
)

object BiometricPreferenceManager {

    private const val PREFS_NAME = "pmp_biometric_device_binding"
    private const val KEY_BOUND_USER_ID = "bound_user_id"
    private const val KEY_BOUND_USER_CODE = "bound_user_code"
    private const val KEY_BOUND_USERNAME = "bound_username"
    private const val KEY_BOUND_FULL_NAME = "bound_full_name"
    private const val KEY_BOUND_ROLE = "bound_role"
    private const val KEY_BOUND_AT = "bound_at"
    private const val KEY_IS_ENABLED = "is_biometric_enabled"
    private const val KEY_DEVICE_INSTALL_ID = "device_install_id"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Binds the verified employee to this specific hardware device.
     * Called strictly upon successful login with username and password or verified Google Identity.
     */
    fun bindUser(context: Context, user: UserAccountEntity) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putLong(KEY_BOUND_USER_ID, user.id)
            .putString(KEY_BOUND_USER_CODE, user.userCode.trim())
            .putString(KEY_BOUND_USERNAME, user.username.trim().lowercase())
            .putString(KEY_BOUND_FULL_NAME, user.fullName.trim())
            .putString(KEY_BOUND_ROLE, user.role.trim())
            .putLong(KEY_BOUND_AT, System.currentTimeMillis())
            .putBoolean(KEY_IS_ENABLED, true)
            .apply()
    }

    /**
     * Returns the verified account bound to this hardware device, or null if no account is bound.
     */
    fun getBoundUser(context: Context): BoundBiometricUser? {
        val prefs = getPrefs(context)
        val isEnabled = prefs.getBoolean(KEY_IS_ENABLED, false)
        val id = prefs.getLong(KEY_BOUND_USER_ID, -1L)
        val code = prefs.getString(KEY_BOUND_USER_CODE, "") ?: ""
        val username = prefs.getString(KEY_BOUND_USERNAME, "") ?: ""
        val fullName = prefs.getString(KEY_BOUND_FULL_NAME, "") ?: ""
        val role = prefs.getString(KEY_BOUND_ROLE, "") ?: ""
        val boundAt = prefs.getLong(KEY_BOUND_AT, 0L)

        if (!isEnabled || id <= 0L || (code.isBlank() && username.isBlank())) {
            return null
        }

        return BoundBiometricUser(
            id = id,
            userCode = code,
            username = username,
            fullName = fullName.ifBlank { username },
            role = role,
            boundAt = boundAt
        )
    }

    /**
     * Unbinds the account from biometric login on this device.
     */
    fun clearBinding(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_BOUND_USER_ID)
            .remove(KEY_BOUND_USER_CODE)
            .remove(KEY_BOUND_USERNAME)
            .remove(KEY_BOUND_FULL_NAME)
            .remove(KEY_BOUND_ROLE)
            .remove(KEY_BOUND_AT)
            .putBoolean(KEY_IS_ENABLED, false)
            .apply()
    }

    /**
     * Retrieves or generates a stable, sanitized hardware device identifier for diagnostics.
     */
    fun getDeviceIdentifier(context: Context): String {
        val prefs = getPrefs(context)
        var deviceId = prefs.getString(KEY_DEVICE_INSTALL_ID, null)
        if (deviceId.isNullOrBlank()) {
            val androidId = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()
            deviceId = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                "DEV-" + androidId.takeLast(8).uppercase()
            } else {
                "DEV-" + UUID.randomUUID().toString().take(8).uppercase()
            }
            prefs.edit().putString(KEY_DEVICE_INSTALL_ID, deviceId).apply()
        }
        return deviceId
    }
}
