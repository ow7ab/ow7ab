package com.example.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * سجل تشخيص داخلي مخصص للمطور فقط (Developer-Only Diagnostic Logger)
 * يحتفظ بالتفاصيل التقنية (رموز HTTP، الروابط، بصمات SHA-256، ومسارات Firebase) بعيداً عن واجهة المستخدم العادي،
 * ولا يسجل أبداً أي كلمات مرور أو مفاتيح سرية.
 */
object DeveloperUpdateLogger {
    private const val TAG = "PmpUpdateDevLog"
    private const val MAX_ENTRIES = 60

    data class LogEntry(
        val timestamp: String,
        val stage: String,
        val technicalDetail: String,
        val isError: Boolean
    )

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun info(stage: String, detail: String) {
        runCatching { Log.i(TAG, "[$stage] $detail") }
        appendEntry(stage, detail, isError = false)
    }

    fun warn(stage: String, detail: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) Log.w(TAG, "[$stage] $detail", throwable)
            else Log.w(TAG, "[$stage] $detail")
        }
        val combined = if (throwable != null) "$detail (${throwable.javaClass.simpleName}: ${throwable.message})" else detail
        appendEntry(stage, combined, isError = true)
    }

    fun error(stage: String, detail: String, throwable: Throwable? = null) {
        runCatching { Log.e(TAG, "[$stage] $detail", throwable) }
        val combined = if (throwable != null) "$detail (${throwable.javaClass.simpleName}: ${throwable.message})" else detail
        appendEntry(stage, combined, isError = true)
    }

    fun logError(stage: String, detail: String, throwable: Throwable? = null) {
        error(stage, detail, throwable)
    }

    fun getRecentLogs(): List<String> {
        return _entries.value.map { "[${it.timestamp}] [${it.stage}] ${it.technicalDetail}" }
    }

    private fun appendEntry(stage: String, detail: String, isError: Boolean) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        val entry = LogEntry(
            timestamp = sdf.format(Date()),
            stage = stage,
            technicalDetail = detail,
            isError = isError
        )
        val updated = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        _entries.value = updated
    }

    fun clear() {
        _entries.value = emptyList()
    }
}

/**
 * مساعد إشعارات التحديث الفورية (FCM + Android System Notifications)
 * يضمن:
 * - عدم إرسال إشعارات مكررة لنفس رقم الإصدار (Deduplication by versionCode).
 * - احترام صلاحيات إشعارات أندرويد (POST_NOTIFICATIONS في Android 13+).
 * - توجيه المستخدم مباشرة إلى صفحة «تحديثات التطبيق» عند الضغط على الإشعار.
 * - عدم تعطيل التطبيق في حال فشل خدمة الإشعارات.
 */
object UpdateNotificationHelper {
    const val CHANNEL_ID = "pmp_updates_channel"
    const val NOTIFICATION_ID = 7042
    const val EXTRA_OPEN_UPDATES_SCREEN = "extra_open_updates_screen"
    const val EXTRA_TARGET_VERSION_CODE = "extra_target_version_code"
    const val FCM_UPDATES_TOPIC = "pmp_app_updates"

    private const val PREFS_NAME = "pmp_update_notification_prefs"
    private const val KEY_LAST_NOTIFIED_VERSION_CODE = "last_notified_update_version_code"

    private val _fcmUpdateSignal = MutableStateFlow<Int>(0)
    val fcmUpdateSignal: StateFlow<Int> = _fcmUpdateSignal.asStateFlow()

    fun ensureChannelAndSubscribe(context: Context) {
        ensureNotificationChannel(context)
        subscribeToUpdatesTopicSafely()
    }

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "تحديثات التطبيق",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "إشعارات توفر إصدارات جديدة من تطبيق PMP PAINTS"
                }
                manager.createNotificationChannel(channel)
            }.onFailure { err ->
                DeveloperUpdateLogger.warn("NotificationChannel", "Failed to create notification channel: ${err.message}")
            }
        }
    }

    fun subscribeToUpdatesTopicSafely() {
        runCatching {
            FirebaseMessaging.getInstance().subscribeToTopic(FCM_UPDATES_TOPIC)
                .addOnSuccessListener {
                    DeveloperUpdateLogger.info("FCM", "Subscribed to topic: $FCM_UPDATES_TOPIC")
                }
                .addOnFailureListener { err ->
                    DeveloperUpdateLogger.warn("FCM", "Topic subscription notice: ${err.message}")
                }
        }.onFailure { err ->
            DeveloperUpdateLogger.warn("FCM", "FirebaseMessaging unavailable: ${err.message}")
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun wasVersionAlreadyNotified(context: Context, versionCode: Int): Boolean {
        if (versionCode <= 0) return true
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastNotified = prefs.getInt(KEY_LAST_NOTIFIED_VERSION_CODE, -1)
        return lastNotified >= versionCode
    }

    fun markVersionNotified(context: Context, versionCode: Int) {
        if (versionCode <= 0) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_LAST_NOTIFIED_VERSION_CODE, versionCode).apply()
    }

    fun notifyNewUpdateAvailableIfNeeded(
        context: Context,
        updateInfo: UpdateInfo,
        installedVersionCode: Int
    ) {
        if (updateInfo.versionCode <= installedVersionCode) return
        notifyNewUpdateAvailableIfNeeded(
            context = context,
            versionCode = updateInfo.versionCode,
            versionName = updateInfo.versionName,
            releaseNotes = updateInfo.releaseNotes
        )
    }

    fun notifyNewUpdateAvailableIfNeeded(
        context: Context,
        versionCode: Int,
        versionName: String,
        releaseNotes: String
    ) {
        runCatching {
            val appContext = context.applicationContext
            val installedCode = UpdateManager.getInstance(appContext).getInstalledVersionCode()
            if (versionCode <= installedCode) return
            if (wasVersionAlreadyNotified(appContext, versionCode)) return

            markVersionNotified(appContext, versionCode)
            _fcmUpdateSignal.value = versionCode

            if (!hasNotificationPermission(appContext)) {
                DeveloperUpdateLogger.info(
                    "Notification",
                    "Skipped system notification for v$versionName (code=$versionCode): POST_NOTIFICATIONS permission not granted."
                )
                return
            }

            ensureNotificationChannel(appContext)

            val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_UPDATES_SCREEN, true)
                putExtra(EXTRA_TARGET_VERSION_CODE, versionCode)
            }

            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getActivity(
                appContext,
                NOTIFICATION_ID,
                launchIntent,
                pendingFlags
            )

            val cleanSummary = sanitizeUserFacingReleaseNotes(releaseNotes)
            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_pmp_emblem)
                .setContentTitle("تحديث جديد متاح (${versionName.removePrefix("v")})")
                .setContentText(cleanSummary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(cleanSummary))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
            DeveloperUpdateLogger.info("Notification", "Dispatched update notification for v$versionName (code=$versionCode)")
        }.onFailure { err ->
            DeveloperUpdateLogger.warn("Notification", "Failed to show update notification: ${err.message}")
        }
    }

    /**
     * تنظيف وصف التحديث من أي مسارات أو روابط أو بصمات تقنية قبل عرضه للمستخدم العادي
     */
    fun sanitizeUserFacingReleaseNotes(rawNotes: String): String {
        val cleanedLines = rawNotes
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    !line.contains("http://", ignoreCase = true) &&
                    !line.contains("https://", ignoreCase = true) &&
                    !line.contains("SHA-256", ignoreCase = true) &&
                    !line.contains("sha256", ignoreCase = true) &&
                    !line.contains("Build ID", ignoreCase = true) &&
                    !line.contains("Commit", ignoreCase = true) &&
                    !line.contains("com.pmp", ignoreCase = true) &&
                    !line.contains("ow7ab", ignoreCase = true) &&
                    !line.contains(".apk", ignoreCase = true)
            }
            .toList()

        return if (cleanedLines.isEmpty()) {
            "يتوفر إصدار جديد يتضمن تحسينات في الأداء والسرعة واستقرار النظام."
        } else {
            cleanedLines.joinToString("\n")
        }
    }
}

/**
 * خدمة Firebase Cloud Messaging لاستقبال إشعارات التحديث السحابية الفورية
 * وتوجيه المستخدم مباشرة إلى شاشة التحديثات عند الضغط على الإشعار.
 */
class PmpFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        DeveloperUpdateLogger.info("FCM", "FCM registration token refreshed")
        UpdateNotificationHelper.subscribeToUpdatesTopicSafely()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        runCatching {
            val data = remoteMessage.data
            val versionCode = data["versionCode"]?.toIntOrNull()
                ?: data["version_code"]?.toIntOrNull()
                ?: 0
            val versionName = data["versionName"]
                ?: data["version_name"]
                ?: remoteMessage.notification?.title
                ?: ""
            val releaseNotes = data["releaseNotes"]
                ?: data["release_notes"]
                ?: remoteMessage.notification?.body
                ?: "يتوفر إصدار جديد يتضمن تحسينات وإصلاحات."

            DeveloperUpdateLogger.info("FCM", "Received FCM message (versionCode=$versionCode, versionName=$versionName)")

            if (versionCode > 0) {
                UpdateNotificationHelper.notifyNewUpdateAvailableIfNeeded(
                    context = applicationContext,
                    versionCode = versionCode,
                    versionName = versionName.ifBlank { "3.$versionCode-PRO" },
                    releaseNotes = releaseNotes
                )
            }
        }.onFailure { err ->
            DeveloperUpdateLogger.warn("FCM", "Error handling FCM message: ${err.message}")
        }
    }
}
