package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
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
    private const val AUTH_GRACE_PERIOD_MS = 60_000L // 1 minute grace period between sensitive screens

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
    }

    /**
     * Checks if the user was recently authenticated within the grace period.
     */
    fun isRecentlyAuthenticated(context: Context): Boolean {
        val lastTime = getPrefs(context).getLong(KEY_LAST_AUTH_TIME, 0L)
        return System.currentTimeMillis() - lastTime < AUTH_GRACE_PERIOD_MS
    }

    private fun recordAuthenticationSuccess(context: Context) {
        getPrefs(context).edit().putLong(KEY_LAST_AUTH_TIME, System.currentTimeMillis()).apply()
    }

    /**
     * Clears recent authentication timestamp so next sensitive screen access requires fresh biometric validation.
     */
    fun clearRecentAuthentication(context: Context) {
        getPrefs(context).edit().remove(KEY_LAST_AUTH_TIME).apply()
    }

    /**
     * Launches the native Android BiometricPrompt for fingerprint or face unlock.
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
            // If biometrics not enrolled on hardware, treat as authorized so user is never locked out
            onSuccess()
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
                    // If user cancelled, report cancellation
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
