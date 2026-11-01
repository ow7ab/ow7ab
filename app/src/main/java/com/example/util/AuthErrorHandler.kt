package com.example.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthActionCodeException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthMultiFactorException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWebException
import com.google.firebase.firestore.FirebaseFirestoreException

/**
 * Centralized ErrorHandler for Firebase and application authentication exceptions.
 * Converts technical and network exceptions into user-friendly Arabic messages
 * and dispatches them via Toasts, Snackbars, or ViewModel messages to avoid app freezing.
 */
object AuthErrorHandler {

    private const val TAG = "AuthErrorHandler"

    /**
     * Translates Firebase and authentication exceptions into clear, friendly Arabic error descriptions.
     */
    fun getReadableErrorMessage(throwable: Throwable?): String {
        if (throwable == null) return "حدث خطأ غير متوقع، يرجى المحاولة مرة أخرى."

        Log.e(TAG, "Auth error caught: ${throwable::class.java.simpleName}: ${throwable.message}", throwable)

        return when (throwable) {
            is FirebaseAuthInvalidUserException -> {
                when (throwable.errorCode) {
                    "ERROR_USER_NOT_FOUND", "user-not-found" ->
                        "الحساب غير مسجل في النظام. يرجى التأكد من البريد الإلكتروني أو طلب تفعيل حساب جديد."
                    "ERROR_USER_DISABLED", "user-disabled" ->
                        "تم إيقاف هذا الحساب من قبل الإدارة. يرجى التواصل مع مدير النظام لتفعيله."
                    else -> "بيانات المستخدم غير صالحة أو تم إيقاف الحساب."
                }
            }
            is FirebaseAuthInvalidCredentialsException -> {
                when (throwable.errorCode) {
                    "ERROR_WRONG_PASSWORD", "wrong-password", "invalid-credential" ->
                        "بيانات تسجيل الدخول أو الرمز غير صحيحة. يرجى التحقق وإعادة المحاولة."
                    "ERROR_INVALID_EMAIL", "invalid-email" ->
                        "صيغة البريد الإلكتروني المدخلة غير صحيحة."
                    else -> "بيانات الاعتماد غير مقبولة من خادم المصادقة."
                }
            }
            is FirebaseAuthUserCollisionException -> {
                "البريد الإلكتروني أو رقم الهاتف مستخدم بالفعل في حساب موظف آخر."
            }
            is FirebaseAuthRecentLoginRequiredException -> {
                "تتطلب هذه العملية الحساسة إعادة تسجيل الدخول حديثاً لأسباب أمنية."
            }
            is FirebaseNetworkException -> {
                "تعذر الاتصال بخوادم المصادقة السحابية. يرجى التحقق من اتصال الإنترنت ثم إعادة المحاولة."
            }
            is FirebaseAuthWebException -> {
                "تعذرت المصادقة السحابية عبر Google أو المتصفح. يرجى التحقق من الاتصال وإعادة المحاولة."
            }
            is FirebaseAuthActionCodeException -> {
                "رمز التحقق أو الرابط منتهي الصلاحية أو تم استخدامه مسبقاً."
            }
            is FirebaseAuthMultiFactorException -> {
                "يتطلب هذا الحساب تأكيد الهوية بخطوتين (MFA)."
            }
            is FirebaseFirestoreException -> {
                when (throwable.code) {
                    FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                        "تم رفض الوصول: لا تملك الصلاحيات الكافية لتنفيذ هذا الإجراء."
                    FirebaseFirestoreException.Code.UNAVAILABLE ->
                        "خادم المزامنة السحابية غير متاح مؤقتاً، جاري المتابعة في الوضع المحلي."
                    else -> "تعذر مزامنة بيانات الحساب مع السيرفر: ${throwable.localizedMessage ?: ""}"
                }
            }
            is androidx.credentials.exceptions.GetCredentialCancellationException -> {
                "تم إلغاء عملية تسجيل الدخول عبر Google من قبل المستخدم."
            }
            is androidx.credentials.exceptions.GetCredentialException -> {
                "تعذر استرداد بيانات الاعتماد من Google: ${throwable.localizedMessage ?: "حاول ثانية"}"
            }
            else -> {
                val msg = throwable.localizedMessage ?: ""
                when {
                    msg.contains("network", ignoreCase = true) || msg.contains("timeout", ignoreCase = true) ->
                        "انقطع الاتصال بالإنترنت أثناء معالجة المصادقة. يرجى التحقق من الشبكة."
                    msg.contains("canceled", ignoreCase = true) || msg.contains("cancelled", ignoreCase = true) ->
                        "تم إلغاء عملية المصادقة."
                    msg.isNotBlank() && !msg.startsWith("java.") -> msg
                    else -> "حدث خطأ أثناء معالجة الجلسة. يرجى المحاولة مرة أخرى."
                }
            }
        }
    }

    /**
     * Safely shows a Toast with the localized error message on the UI/Main thread.
     */
    fun showToast(context: Context, throwable: Throwable?, duration: Int = Toast.LENGTH_LONG) {
        val message = getReadableErrorMessage(throwable)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(context.applicationContext, message, duration).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, message, duration).show()
            }
        }
    }

    /**
     * Unified handler: formats readable message, optionally shows Toast, and invokes callback (for ViewModel/Snackbar).
     */
    fun handleAuthError(
        context: Context,
        throwable: Throwable?,
        showToastNotification: Boolean = true,
        onShowMessage: (String) -> Unit = {}
    ): String {
        val message = getReadableErrorMessage(throwable)
        if (showToastNotification) {
            showToast(context, throwable)
        }
        onShowMessage(message)
        return message
    }
}
