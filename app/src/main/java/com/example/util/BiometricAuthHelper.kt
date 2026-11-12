package com.example.util

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricAuthHelper {

    fun isBiometricAvailable(context: Context): Pair<Boolean, String> {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK

        return when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                Pair(true, "البصمة متوفرة وجاهزة للاستخدام")
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                Pair(false, "هذا الجهاز لا يدعم مستشعر البصمة أو الوجه")
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                Pair(false, "مستشعر البصمة غير متاح حالياً")
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                Pair(false, "لم يتم تسجيل أي بصمة أو وجه في إعدادات الهاتف بعد")
            }
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> {
                Pair(false, "يلزم تحديث أمني لاستخدام البصمة")
            }
            else -> {
                Pair(false, "ميزة البصمة غير متاحة حالياً")
            }
        }
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String = "تسجيل الدخول بالبصمة أو الوجه",
        subtitle: String = "منظومة PMP Paints ERP",
        description: String = "ضع إصبعك على المستشعر أو انظر إلى الكاميرا للتحقق من هويتك",
        negativeButtonText: String = "إلغاء واستخدام كلمة المرور",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val (available, reason) = isBiometricAvailable(activity)
        if (!available) {
            onError(reason)
            return
        }

        val boundUser = BiometricPreferenceManager.getBoundUser(activity)
        if (boundUser == null) {
            onError("لم يتم ربط أي حساب موثق بالبصمة على هذا الجهاز بعد. يرجى تسجيل الدخول بكلمة المرور أولاً.")
            return
        }

        val lockout = AuthSecurityManager.checkLoginAllowed(activity, boundUser.username.ifBlank { boundUser.userCode })
        if (lockout.isLockedOut) {
            onError(lockout.messageAr ?: "الحساب مقفل مؤقتاً بسبب محاولات دخول متكررة غير ناجحة.")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDescription(description)
            .setConfirmationRequired(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptInfoBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            promptInfoBuilder.setNegativeButtonText(negativeButtonText)
        } else {
            promptInfoBuilder.setNegativeButtonText(negativeButtonText)
        }

        val promptInfo = promptInfoBuilder.build()

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == BiometricPrompt.ERROR_LOCKOUT || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                        AuthSecurityManager.recordFailedLoginAttempt(
                            context = activity,
                            identity = boundUser.username.ifBlank { boundUser.userCode },
                            authMethod = "BIOMETRIC",
                            reason = "قفل مستشعر البصمة بسبب محاولات خاطئة متكررة: $errString"
                        )
                    }
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    AuthSecurityManager.recordFailedLoginAttempt(
                        context = activity,
                        identity = boundUser.username.ifBlank { boundUser.userCode },
                        authMethod = "BIOMETRIC",
                        reason = "عدم تطابق البصمة الحيوية مع المستخدم المسجل"
                    )
                    onError("لم يتم التعرف على البصمة، يرجى المحاولة مرة أخرى")
                }
            }
        )

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            onError(e.message ?: "تعذر بدء التحقق بالبصمة")
        }
    }
}
