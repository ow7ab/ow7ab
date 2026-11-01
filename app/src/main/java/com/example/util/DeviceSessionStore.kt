package com.example.util

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Manages device-local session authentication state.
 *
 * CRITICAL SECURITY INVARIANT:
 * Sessions are strictly bound to this physical device installation.
 * When a database backup is exported, shared, or synced to another device,
 * the active session on that second device will remain UNAUTHENTICATED until a user
 * explicitly logs in on that specific device.
 */
object DeviceSessionStore {

    private const val PREFS_NAME = "pmp_device_session_prefs"
    private const val KEY_ACTIVE_USER_ID = "device_active_user_id"
    private const val KEY_ACTIVE_USER_CODE = "device_active_user_code"
    private const val KEY_ACTIVE_USER_ROLE = "device_active_user_role"
    private const val KEY_DEVICE_ID = "device_unique_hardware_id"
    private const val KEY_LOGIN_TIMESTAMP = "device_login_timestamp"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Saves the authenticated user session for THIS physical device.
     */
    fun saveActiveSession(context: Context, userId: Long, userCode: String, role: String) {
        getPrefs(context).edit()
            .putLong(KEY_ACTIVE_USER_ID, userId)
            .putString(KEY_ACTIVE_USER_CODE, userCode)
            .putString(KEY_ACTIVE_USER_ROLE, role)
            .putLong(KEY_LOGIN_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    /**
     * Gets the active user ID authenticated on THIS physical device.
     * Returns -1L if no user is currently authenticated on this device.
     */
    fun getActiveUserId(context: Context): Long {
        return getPrefs(context).getLong(KEY_ACTIVE_USER_ID, -1L)
    }

    /**
     * Gets the active userCode authenticated on THIS physical device.
     */
    fun getActiveUserCode(context: Context): String {
        return getPrefs(context).getString(KEY_ACTIVE_USER_CODE, "").orEmpty()
    }

    /**
     * Returns true only if a valid user session is authenticated on THIS device.
     */
    fun hasActiveSession(context: Context): Boolean {
        return getActiveUserId(context) > 0L
    }

    /**
     * Clears the active user session on THIS device, forcing the login screen to appear.
     */
    fun clearActiveSession(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_ACTIVE_USER_ID)
            .remove(KEY_ACTIVE_USER_CODE)
            .remove(KEY_ACTIVE_USER_ROLE)
            .remove(KEY_LOGIN_TIMESTAMP)
            .apply()
    }

    /**
     * Returns a stable, persistent device identifier unique to this device installation.
     */
    fun getOrCreateDeviceId(context: Context): String {
        val prefs = getPrefs(context)
        var devId = prefs.getString(KEY_DEVICE_ID, null)
        if (devId.isNullOrBlank()) {
            devId = "DEV-" + UUID.randomUUID().toString().take(12).uppercase()
            prefs.edit().putString(KEY_DEVICE_ID, devId).apply()
        }
        return devId
    }
}
