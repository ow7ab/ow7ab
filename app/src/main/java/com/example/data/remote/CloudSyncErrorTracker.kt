package com.example.data.remote

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 8-category root-cause classification for Cloud / Firestore / Auth / Sync operations.
 * Mirrors web `CloudErrorLogger.ts`.
 */
enum class CloudErrorCategory(
    val arabicTitle: String,
    val isTransientRetryable: Boolean,
    val remediationHint: String
) {
    PERMISSION_DENIED(
        arabicTitle = "رفض صلاحيات الخادم (PERMISSION_DENIED)",
        isTransientRetryable = false,
        remediationHint = "تحقق من تطابق عضوية المستخدم في cluster_members ودوره المعتمد وقواعد Firestore المنشورة."
    ),
    UNAUTHENTICATED(
        arabicTitle = "جلسة المصادقة غير نشطة أو منتهية (UNAUTHENTICATED)",
        isTransientRetryable = false,
        remediationHint = "سجّل الدخول بحساب مصادق عبر Firebase Auth / Google SSO لتجديد رمز الجلسة."
    ),
    UNAVAILABLE(
        arabicTitle = "الخدمة السحابية غير متاحة مؤقتاً (UNAVAILABLE)",
        isTransientRetryable = true,
        remediationHint = "تحقق من اتصال الإنترنت أو أعد المحاولة بعد بضع ثوانٍ."
    ),
    DEADLINE_EXCEEDED(
        arabicTitle = "انتهاء مهلة استجابة الخادم (DEADLINE_EXCEEDED)",
        isTransientRetryable = true,
        remediationHint = "بطء الشبكة أو الاستجابة — خفّف حجم الدفعة أو أعد المحاولة على اتصال مستقر."
    ),
    RESOURCE_EXHAUSTED(
        arabicTitle = "تجاوز حصة الطلبات أو الحدود (RESOURCE_EXHAUSTED)",
        isTransientRetryable = true,
        remediationHint = "تم استهلاك الحصة المؤقتة لـ Firebase أو تم إرسال طلبات كثيفة متتالية."
    ),
    FAILED_PRECONDITION(
        arabicTitle = "شرط خادمي أو فهرس غير مكتمل (FAILED_PRECONDITION)",
        isTransientRetryable = false,
        remediationHint = "تحقق من حالة القيد أو فهارس Firestore المطلوبة قبل إعادة العملية."
    ),
    NETWORK_ERROR(
        arabicTitle = "انقطاع أو ضعف اتصال الشبكة (NETWORK_ERROR)",
        isTransientRetryable = true,
        remediationHint = "تأكد من اتصال الجهاز بالإنترنت وعدم حجب نطاقات *.googleapis.com."
    ),
    DATA_CONFLICT(
        arabicTitle = "تعارض أو رفض تحقق البيانات (DATA_CONFLICT)",
        isTransientRetryable = false,
        remediationHint = "تحقق من توازن القيود المحاسبية أو القواعد الثابتة للسجلات غير القابلة للتعديل."
    ),
    SECURITY_TAMPER(
        arabicTitle = "تنبيه أمني: محاولة تلاعب في معاملة مالية (SECURITY_TAMPER)",
        isTransientRetryable = false,
        remediationHint = "تم إيقاف العملية تلقائياً وتوثيقها في سجل التدقيق والأخطاء. راجع تفاصيل المستخدم والمرجع فوراً."
    ),
    DUPLICATE_TRANSACTION(
        arabicTitle = "منع تكرار معاملة مالية في Firestore (DUPLICATE_TRANSACTION)",
        isTransientRetryable = false,
        remediationHint = "المعاملة مسجلة مسبقاً بنفس المرجع أو البصمة المالية؛ تم حظر الإدخال المكرر لحماية الأرصدة."
    )
}

enum class ServiceProbeId(val arabicLabel: String) {
    NETWORK("اتصال الشبكة والإنترنت"),
    FIREBASE_AUTH("جلسة Firebase Auth"),
    FIRESTORE("اتصال وقواعد Firestore"),
    RBAC_POLICY("سياسات الصلاحيات RBAC"),
    APP_UPDATES("تحديثات التطبيق الرسمية")
}

data class ServiceProbeResult(
    val probeId: ServiceProbeId,
    val ok: Boolean,
    val measured: Boolean = true,
    val latencyMs: Long = 0L,
    val detail: String,
    val remediationHint: String = "",
    val checkedAtMillis: Long = System.currentTimeMillis()
)

data class PreflightCheckResult(
    val passed: Boolean,
    val clusterCode: String,
    val firebaseAuthPresent: Boolean,
    val networkOnline: Boolean,
    val roleSummary: String,
    val message: String,
    val checkedAtMillis: Long = System.currentTimeMillis()
)

data class SyncStepSummary(
    val stepName: String,
    val collectionPath: String,
    val syncedCount: Int,
    val success: Boolean,
    val message: String
)

data class ComprehensiveDiagnosticReport(
    val clusterCode: String,
    val overallOk: Boolean,
    val summaryMessage: String,
    val probes: List<ServiceProbeResult>,
    val preflight: PreflightCheckResult? = null,
    val readOnly: Boolean = true,
    val totalLatencyMs: Long = 0L,
    val generatedAtMillis: Long = System.currentTimeMillis()
)

data class CloudErrorLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val formattedTime: String,
    val deviceId: String,
    val operation: String,
    val collectionPath: String,
    val category: CloudErrorCategory,
    val code: String,
    val sanitizedMessage: String,
    val remediationHint: String,
    val retryable: Boolean
)

data class ClassifiedCloudError(
    val category: CloudErrorCategory,
    val code: String,
    val sanitizedMessage: String,
    val remediationHint: String,
    val retryable: Boolean
)

object CloudSyncErrorTracker {
    private val bearerRegex = Regex("Bearer\\s+[A-Za-z0-9\\-_.~+/]+=*", RegexOption.IGNORE_CASE)
    private val apiKeyRegex = Regex("AIza[0-9A-Za-z_-]{12,}")
    private val jwtRegex = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*")
    private val secretParamRegex = Regex(
        "\\b(pinOrPassword|password|secret|token|apiKey|idToken|accessToken|private_key)\\b\\s*[:=]\\s*[^\\s,;}\"'&]+",
        RegexOption.IGNORE_CASE
    )

    fun sanitizeCloudMessage(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return "خطأ غير محدد أثناء الاتصال بالسيرفر"
        return trimmed
            .replace(bearerRegex, "Bearer [REDACTED]")
            .replace(apiKeyRegex, "[REDACTED_API_KEY]")
            .replace(jwtRegex, "[REDACTED_JWT]")
            .replace(secretParamRegex) { match ->
                val key = match.groupValues.getOrElse(1) { "secret" }
                "$key=[REDACTED]"
            }
            .take(600)
    }

    fun classifyThrowable(throwable: Throwable?): ClassifiedCloudError {
        if (throwable == null) {
            val cat = CloudErrorCategory.UNAVAILABLE
            return ClassifiedCloudError(
                category = cat,
                code = cat.name,
                sanitizedMessage = "تعذر تحديد سبب الخطأ السحابي",
                remediationHint = cat.remediationHint,
                retryable = cat.isTransientRetryable
            )
        }

        val sanitized = sanitizeCloudMessage(throwable.message ?: throwable.toString())

        // Direct FirebaseFirestoreException check
        val firestoreEx = findCause<FirebaseFirestoreException>(throwable)
        if (firestoreEx != null) {
            val category = when (firestoreEx.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> CloudErrorCategory.PERMISSION_DENIED
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> CloudErrorCategory.UNAUTHENTICATED
                FirebaseFirestoreException.Code.UNAVAILABLE -> CloudErrorCategory.UNAVAILABLE
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> CloudErrorCategory.DEADLINE_EXCEEDED
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> CloudErrorCategory.RESOURCE_EXHAUSTED
                FirebaseFirestoreException.Code.FAILED_PRECONDITION -> CloudErrorCategory.FAILED_PRECONDITION
                FirebaseFirestoreException.Code.ABORTED,
                FirebaseFirestoreException.Code.ALREADY_EXISTS,
                FirebaseFirestoreException.Code.NOT_FOUND,
                FirebaseFirestoreException.Code.INVALID_ARGUMENT,
                FirebaseFirestoreException.Code.OUT_OF_RANGE,
                FirebaseFirestoreException.Code.DATA_LOSS -> CloudErrorCategory.DATA_CONFLICT
                else -> classifyFromMessage(sanitized)
            }
            return ClassifiedCloudError(
                category = category,
                code = firestoreEx.code.name,
                sanitizedMessage = sanitized,
                remediationHint = category.remediationHint,
                retryable = category.isTransientRetryable
            )
        }

        // Network exceptions check
        if (findCause<FirebaseNetworkException>(throwable) != null ||
            findCause<UnknownHostException>(throwable) != null ||
            findCause<SocketTimeoutException>(throwable) != null ||
            findCause<ConnectException>(throwable) != null ||
            findCause<IOException>(throwable) != null
        ) {
            val cat = CloudErrorCategory.NETWORK_ERROR
            return ClassifiedCloudError(
                category = cat,
                code = throwable::class.java.simpleName.ifBlank { cat.name },
                sanitizedMessage = sanitized,
                remediationHint = cat.remediationHint,
                retryable = cat.isTransientRetryable
            )
        }

        val category = classifyFromMessage(sanitized)
        return ClassifiedCloudError(
            category = category,
            code = category.name,
            sanitizedMessage = sanitized,
            remediationHint = category.remediationHint,
            retryable = category.isTransientRetryable
        )
    }

    fun buildLogEntry(
        operation: String,
        collectionPath: String,
        throwable: Throwable?,
        deviceId: String
    ): CloudErrorLogEntry {
        val now = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
        val classified = classifyThrowable(throwable)
        return CloudErrorLogEntry(
            timestamp = now,
            formattedTime = timeStr,
            deviceId = deviceId.ifBlank { "ANDROID-CLIENT" },
            operation = operation.ifBlank { "CLOUD_SYNC" },
            collectionPath = collectionPath.ifBlank { "/" },
            category = classified.category,
            code = classified.code,
            sanitizedMessage = classified.sanitizedMessage,
            remediationHint = classified.remediationHint,
            retryable = classified.retryable
        )
    }

    fun buildTamperLogEntry(
        operation: String,
        collectionPath: String,
        reason: String,
        actor: String = "",
        deviceId: String = "ANDROID-CLIENT"
    ): CloudErrorLogEntry {
        val now = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
        val actorPrefix = if (actor.isNotBlank()) "($actor) " else ""
        val cat = CloudErrorCategory.SECURITY_TAMPER
        return CloudErrorLogEntry(
            timestamp = now,
            formattedTime = timeStr,
            deviceId = deviceId.ifBlank { "ANDROID-CLIENT" },
            operation = operation.ifBlank { "HANDLE_TRANSACTION_COMMIT" },
            collectionPath = collectionPath.ifBlank { "/" },
            category = cat,
            code = cat.name,
            sanitizedMessage = sanitizeCloudMessage("[محاولة تلاعب] $actorPrefix$reason"),
            remediationHint = cat.remediationHint,
            retryable = false
        )
    }

    fun buildDuplicateLogEntry(
        operation: String,
        collectionPath: String,
        reason: String,
        actor: String = "",
        deviceId: String = "ANDROID-CLIENT"
    ): CloudErrorLogEntry {
        val now = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
        val actorPrefix = if (actor.isNotBlank()) "($actor) " else ""
        val cat = CloudErrorCategory.DUPLICATE_TRANSACTION
        return CloudErrorLogEntry(
            timestamp = now,
            formattedTime = timeStr,
            deviceId = deviceId.ifBlank { "ANDROID-CLIENT" },
            operation = operation.ifBlank { "HANDLE_TRANSACTION_COMMIT" },
            collectionPath = collectionPath.ifBlank { "/" },
            category = cat,
            code = cat.name,
            sanitizedMessage = sanitizeCloudMessage("[معاملة مالية مكررة] $actorPrefix$reason"),
            remediationHint = cat.remediationHint,
            retryable = false
        )
    }

    private fun classifyFromMessage(sanitizedLowerSource: String): CloudErrorCategory {
        val h = sanitizedLowerSource.lowercase(Locale.US)
        return when {
            h.contains("security_tamper") || h.contains("tamper") ||
                h.contains("تلاعب") || h.contains("tombstone_replay") ->
                CloudErrorCategory.SECURITY_TAMPER
            h.contains("duplicate_transaction") || h.contains("معاملة مالية مكررة") ||
                h.contains("مسجلة مسبقاً") || h.contains("تم منع التكرار") ->
                CloudErrorCategory.DUPLICATE_TRANSACTION
            h.contains("unauthenticated") || h.contains("auth/") ||
                h.contains("invalid-credential") || h.contains("id token") || h.contains("unauthorized") ||
                h.contains("جلسة المصادقة") || h.contains("google sign-in") ||
                h.contains("تسجيل الدخول بحساب google") || h.contains("جلسة google") ->
                CloudErrorCategory.UNAUTHENTICATED
            h.contains("permission_denied") || h.contains("permission-denied") ||
                h.contains("missing or insufficient permissions") || h.contains("صلاحية") ||
                h.contains("cluster_members") || h.contains("غير مربوط بدليل عضوية") ->
                CloudErrorCategory.PERMISSION_DENIED
            h.contains("unavailable") || h.contains("503") ->
                CloudErrorCategory.UNAVAILABLE
            h.contains("deadline_exceeded") || h.contains("deadline-exceeded") || h.contains("timeout") ->
                CloudErrorCategory.DEADLINE_EXCEEDED
            h.contains("resource_exhausted") || h.contains("resource-exhausted") ||
                h.contains("quota") || h.contains("429") ->
                CloudErrorCategory.RESOURCE_EXHAUSTED
            h.contains("failed_precondition") || h.contains("failed-precondition") || h.contains("index") ->
                CloudErrorCategory.FAILED_PRECONDITION
            h.contains("network") || h.contains("offline") || h.contains("unknownhost") ||
                h.contains("socket") || h.contains("connect") ->
                CloudErrorCategory.NETWORK_ERROR
            h.contains("aborted") || h.contains("already_exists") || h.contains("already-exists") ||
                h.contains("conflict") || h.contains("محصن") ->
                CloudErrorCategory.DATA_CONFLICT
            else -> CloudErrorCategory.UNAVAILABLE
        }
    }

    private inline fun <reified T : Throwable> findCause(throwable: Throwable): T? {
        var cur: Throwable? = throwable
        var depth = 0
        while (cur != null && depth < 12) {
            if (cur is T) return cur
            cur = cur.cause
            depth++
        }
        return null
    }
}
