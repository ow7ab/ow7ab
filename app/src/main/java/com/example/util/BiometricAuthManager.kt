package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricAuthManager {

    private const val TAG = "BiometricAuthManager"
    private const val PREFS_NAME = "pmp_security_prefs"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    private const val KEY_LAST_AUTH_TIME = "last_biometric_auth_time"
    private const val KEY_LAST_AUTH_USER_CODE = "last_biometric_auth_user_code"
    private const val AUTH_GRACE_PERIOD_MS = 60_000L // 1 minute grace period scoped strictly to the same userCode

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Checks if the device has biometric hardware and enrolled credentials (fingerprint or face).
     */
    fun isBiometricAvailable(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        return when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Checks if biometric lock is activated by the user.
     */
    fun isBiometricLockEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BIOMETRIC_ENABLED, false)
    }

    /**
     * Toggles biometric lock setting.
     */
    fun setBiometricLockEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
        if (!enabled) {
            clearRecentAuthentication(context)
        }
    }

    /**
     * Checks if the CURRENT active user was recently authenticated within the 60s grace period.
     * Never shares the grace period across different employee accounts.
     */
    fun isRecentlyAuthenticated(context: Context): Boolean {
        val prefs = getPrefs(context)
        val lastTime = prefs.getLong(KEY_LAST_AUTH_TIME, 0L)
        val lastUserCode = prefs.getString(KEY_LAST_AUTH_USER_CODE, "").orEmpty()
        val activeUserCode = DeviceSessionStore.getActiveUserCode(context)
        if (activeUserCode.isBlank() || !activeUserCode.equals(lastUserCode, ignoreCase = true)) {
            return false
        }
        return (System.currentTimeMillis() - lastTime) in 0 until AUTH_GRACE_PERIOD_MS
    }

    private fun recordAuthenticationSuccess(context: Context) {
        val activeUserCode = DeviceSessionStore.getActiveUserCode(context)
        getPrefs(context).edit()
            .putLong(KEY_LAST_AUTH_TIME, System.currentTimeMillis())
            .putString(KEY_LAST_AUTH_USER_CODE, activeUserCode)
            .apply()
        DeviceSessionStore.touchSessionActivity(context)
    }

    /**
     * Clears recent authentication timestamp so next sensitive screen access requires fresh biometric validation.
     */
    fun clearRecentAuthentication(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_LAST_AUTH_TIME)
            .remove(KEY_LAST_AUTH_USER_CODE)
            .apply()
    }

    /**
     * Launches the native Android BiometricPrompt for fingerprint or face unlock.
     * SECURITY FIX: Never silently invokes onSuccess() when biometric hardware is unavailable.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "التحقق البيومتري (بصمة الإصبع أو الوجه)",
        subtitle: String = "يرجى تأكيد الهوية للوصول إلى البيانات المالية والمخازن الحساسة",
        negativeButtonText: String = "إلغاء",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isBiometricAvailable(activity)) {
            onError("مستشعر البصمة أو قفل الجهاز غير مفعل في إعدادات الهاتف. يرجى تفعيله أو تعطيل القفل البيومتري من الإعدادات.")
            return
        }

        // Verify that if a biometric account binding exists, it matches the currently logged-in session user
        val activeCode = DeviceSessionStore.getActiveUserCode(activity)
        val boundUser = BiometricPreferenceManager.getBoundUser(activity)
        if (boundUser != null && activeCode.isNotBlank() && !boundUser.userCode.equals(activeCode, ignoreCase = true)) {
            onError("البصمة المسجلة على هذا الجهاز مرتبطة بحساب آخر (${boundUser.fullName}). لا يمكن استخدامها لتأكيد جلسة هذا الحساب.")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    recordAuthenticationSuccess(activity)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.w(TAG, "Biometric error $errorCode: $errString")
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        onError("تم إلغاء التحقق البيومتري")
                    } else {
                        onError("فشل التحقق البيومتري: $errString")
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.w(TAG, "Biometric authentication failed (mismatch)")
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setConfirmationRequired(false)
            .build()

        try {
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start biometric prompt: ${e.message}")
            onError(e.message ?: "تعذر فتح قارئ البصمة")
        }
    }
}
