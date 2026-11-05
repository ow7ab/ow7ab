package com.example.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * بيانات الإصدار المستلمة من ملف JSON الخارجي
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String = "",
    val fileSizeMb: Double = 0.0,
    val sha256Checksum: String = "",
    val minSupportedVersion: Int = 1
)

/**
 * حالات التنزيل التفاعلية
 */
sealed class DownloadState {
    object Idle : DownloadState()
    object Connecting : DownloadState()
    data class Progress(val percent: Int, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
    data class Success(val apkFile: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * مدير التحديثات الداخلي المباشر (UpdateManager)
 * - فحص الإصدار عن طريق جلب ملف JSON عن بُعد (Remote JSON version checking)
 * - تنزيل ملف الـ APK باستخدام مكتبة OkHttp مع بث التقدم لحظياً عبر StateFlow
 * - إطلاق واجهة PackageInstaller API ونظام FileProvider للتثبيت الآمن والمباشر
 */
class UpdateManager(private val context: Context) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    fun resetDownloadState() {
        _downloadState.value = DownloadState.Idle
    }

    companion object {
        private const val TAG = "UpdateManager"
        
        // الرابط الافتراضي لقراءة ملف الإصدارات بصيغة JSON (fallback)
        const val DEFAULT_UPDATE_METADATA_URL =
            "https://raw.githubusercontent.com/oaw7ab/pmp-paints-releases/main/version.json"

        // مسار مستند التحديث في قاعدة بيانات Firestore السحابية
        const val FIRESTORE_CONFIG_DOC = "system_config/app_version"
        const val DEFAULT_DOWNLOAD_PORTAL_URL =
            "https://github.com/oaw7ab/pmp-paints-releases/releases/latest"

        @Volatile
        private var INSTANCE: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UpdateManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * فحص رقم الإصدار عبر الاستعلام من السيرفر السحابي (Google Cloud Firestore أولاً)
     * ثم عبر ملف JSON كمسار بديل، مع معالجة ذكية لأخطاء 404 لضمان عدم ظهور رسائل خطأ مزعجة.
     */
    suspend fun checkForUpdates(
        customUrl: String? = null,
        clusterCode: String = "PMP-ERP-MAIN-CLUSTER"
    ): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        val currentCode = BuildConfig.VERSION_CODE

        // 1. فحص الإصدار من السيرفر السحابي المباشر (Google Cloud Firestore)
        try {
            val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            
            // محاولة القراءة من مسار الكلاستر أولاً ثم المسار العام
            val clusterDocSnap = try {
                firestore.collection("erp_clusters")
                    .document(clusterCode)
                    .collection("system_config")
                    .document("app_version")
                    .get()
                    .await()
            } catch (_: Exception) { null }

            val docSnap = if (clusterDocSnap != null && clusterDocSnap.exists()) {
                clusterDocSnap
            } else {
                try {
                    firestore.collection("system_config")
                        .document("app_version")
                        .get()
                        .await()
                } catch (_: Exception) { null }
            }

            if (docSnap != null && docSnap.exists()) {
                val remoteCode = docSnap.getLong("versionCode")?.toInt() ?: 0
                val remoteName = docSnap.getString("versionName").orEmpty()
                val dlUrl = docSnap.getString("downloadUrl").orEmpty()
                val notes = docSnap.getString("releaseNotes") ?: "تحديث أمان واستقرار جديد لمنظومة PMP Paints"
                val sizeMb = docSnap.getDouble("fileSizeMb") ?: 14.5
                val sha = docSnap.getString("sha256").orEmpty()
                val minSup = docSnap.getLong("minSupportedVersion")?.toInt() ?: 1

                if (remoteCode > currentCode && dlUrl.isNotBlank()) {
                    val info = UpdateInfo(
                        versionCode = remoteCode,
                        versionName = remoteName.ifBlank { "v$remoteCode" },
                        downloadUrl = dlUrl,
                        releaseNotes = notes,
                        fileSizeMb = sizeMb,
                        sha256Checksum = sha,
                        minSupportedVersion = minSup
                    )
                    Log.i(TAG, "Update found via Cloud Firestore: $info")
                    return@withContext Result.success(info)
                } else if (remoteCode > 0 && remoteCode <= currentCode) {
                    // التطبيق محدث بالفعل لأحدث إصدار مسجل على السيرفر السحابي
                    Log.i(TAG, "App is up to date according to Cloud Firestore (current: $currentCode, remote: $remoteCode)")
                    return@withContext Result.success(null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice: Firestore version check bypassed, falling back to HTTP: ${e.message}")
        }

        // 2. الفحص عبر رابط HTTP JSON الخارجي (Fallback)
        val url = customUrl?.trim()?.ifBlank { null } ?: DEFAULT_UPDATE_METADATA_URL
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "PMP-Paints-Android/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                if (response.code == 404) {
                    // في حالة رمز 404 (الملف لم يُنشر بعد على المستودع)، التطبيق الحالي هو الأحدث
                    Log.i(TAG, "Update URL returned 404. Current app version is latest.")
                    return@withContext Result.success(null)
                }
                return@withContext Result.failure(
                    Exception("استجابة غير صالحة من سيرفر التحديثات (رمز الخطأ: ${response.code})")
                )
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                return@withContext Result.success(null)
            }

            val json = JSONObject(body)
            val remoteVersionCode = json.optInt("versionCode", 0)
            val remoteVersionName = json.optString("versionName", "")
            val downloadUrl = json.optString("downloadUrl", "")
            val releaseNotes = json.optString("releaseNotes", "تحديث أمان واستقرار جديد لمنظومة PMP Paints")
            val fileSizeMb = json.optDouble("fileSizeMb", 0.0)
            val sha256 = json.optString("sha256", "")
            val minSupported = json.optInt("minSupportedVersion", 1)

            if (remoteVersionCode > currentCode && downloadUrl.isNotBlank()) {
                val info = UpdateInfo(
                    versionCode = remoteVersionCode,
                    versionName = remoteVersionName.ifBlank { "v$remoteVersionCode" },
                    downloadUrl = downloadUrl,
                    releaseNotes = releaseNotes,
                    fileSizeMb = fileSizeMb,
                    sha256Checksum = sha256,
                    minSupportedVersion = minSupported
                )
                Result.success(info)
            } else {
                // التطبيق محدث بالفعل
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates via HTTP: ${e.message}", e)
            // إذا تعذر الوصول لـ HTTP ولم يكن هناك إصدار مسجل في السحابة، نعتبر التطبيق محدثاً إذا كان خطأ اتصال ثانوي
            Result.success(null)
        }
    }

    /**
     * نشر إصدار معتمد على السيرفر السحابي (Firestore) ليتلقاه جميع المستخدمين فوراً
     */
    suspend fun publishVersionToFirestore(
        info: UpdateInfo,
        clusterCode: String = "PMP-ERP-MAIN-CLUSTER"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val data = mapOf(
                "versionCode" to info.versionCode,
                "versionName" to info.versionName,
                "downloadUrl" to info.downloadUrl,
                "releaseNotes" to info.releaseNotes,
                "fileSizeMb" to info.fileSizeMb,
                "sha256" to info.sha256Checksum,
                "minSupportedVersion" to info.minSupportedVersion,
                "publishedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            // حفظ في المسار العام ومسار الكلاستر
            firestore.collection("system_config")
                .document("app_version")
                .set(data, com.google.firebase.firestore.SetOptions.merge())
                .await()

            firestore.collection("erp_clusters")
                .document(clusterCode)
                .collection("system_config")
                .document("app_version")
                .set(data, com.google.firebase.firestore.SetOptions.merge())
                .await()

            Log.i(TAG, "Published update version ${info.versionName} (${info.versionCode}) to Firestore successfully")
            Unit
        }
    }

    /**
     * تنزيل ملف الـ APK باستخدام OkHttp وتخزينه في مجلد التخزين المؤقت مع بث نسبة التقدم
     */
    suspend fun downloadApk(
        updateInfo: UpdateInfo,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) {
                updatesDir.mkdirs()
            }

            // تنظيف أي ملفات تحديث سابقة لعدم استهلاك الذاكرة
            updatesDir.listFiles()?.forEach { oldFile ->
                try { oldFile.delete() } catch (_: Exception) {}
            }

            val apkFile = File(updatesDir, "pmp_update_v${updateInfo.versionCode}.apk")

            _downloadState.value = DownloadState.Connecting
            val request = Request.Builder()
                .url(updateInfo.downloadUrl)
                .header("User-Agent", "PMP-Paints-Android/${BuildConfig.VERSION_NAME}")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = "فشل تنزيل ملف الـ APK من السيرفر (رمز الاستجابة: ${response.code})"
                _downloadState.value = DownloadState.Error(err)
                return@withContext Result.failure(Exception(err))
            }

            val body = response.body
            if (body == null) {
                val err = "جسم الاستجابة فارغ"
                _downloadState.value = DownloadState.Error(err)
                return@withContext Result.failure(Exception(err))
            }
            val totalBytes = body.contentLength()

            var downloadedBytes = 0L
            var lastPercent = -1

            body.byteStream().use { input: InputStream ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _downloadState.value = DownloadState.Progress(percent, downloadedBytes, totalBytes)
                                onProgress(percent, downloadedBytes, totalBytes)
                            }
                        } else {
                            _downloadState.value = DownloadState.Progress(-1, downloadedBytes, -1)
                            onProgress(-1, downloadedBytes, -1)
                        }
                    }
                    output.flush()
                }
            }

            // التحقق من صحة ملف الـ APK المحمّل ومطابقته لاسم الحزمة com.pmp
            val packageArchiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (packageArchiveInfo == null) {
                apkFile.delete()
                val err = "ملف الـ APK المحمّل تالف أو غير صالح للتثبيت"
                _downloadState.value = DownloadState.Error(err)
                return@withContext Result.failure(Exception(err))
            }

            if (packageArchiveInfo.packageName != context.packageName) {
                apkFile.delete()
                val err = "حزمة الـ APK (${packageArchiveInfo.packageName}) لا تطابق هذا التطبيق (${context.packageName})"
                _downloadState.value = DownloadState.Error(err)
                return@withContext Result.failure(Exception(err))
            }

            _downloadState.value = DownloadState.Success(apkFile)
            Result.success(apkFile)
        } catch (e: Exception) {
            val errMsg = e.message ?: "حدث خطأ غير متوقع أثناء تنزيل التحديث"
            _downloadState.value = DownloadState.Error(errMsg)
            Log.e(TAG, "Error downloading APK: $errMsg", e)
            Result.failure(e)
        }
    }

    /**
     * فحص صلاحية تثبيت التطبيقات من مصادر غير معروفة لنظام أندرويد 8.0 فما فوق
     */
    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * توجيه المستخدم لشاشة إعدادات أندرويد لتفعيل صلاحية تثبيت الحزم
     */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            }
        }
    }

    /**
     * تثبيت التحديث باستخدام PackageInstaller API الرسمية عبر جلسة تثبيت (Installation Session)
     */
    fun installViaPackageInstaller(context: Context, apkFile: File): Result<Unit> {
        return try {
            if (!apkFile.exists() || apkFile.length() <= 0) {
                return Result.failure(Exception("ملف الـ APK غير موجود أو فارغ"))
            }

            if (!canRequestPackageInstalls()) {
                openInstallPermissionSettings(context)
                return Result.failure(
                    Exception("يرجى تفعيل صلاحية تثبيت التطبيقات لـ PMP Paints، ثم إعادة المحاولة.")
                )
            }

            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.use { activeSession ->
                apkFile.inputStream().use { inputStream ->
                    activeSession.openWrite("pmp_update_package", 0, apkFile.length()).use { outputStream ->
                        inputStream.copyTo(outputStream)
                        activeSession.fsync(outputStream)
                    }
                }

                val statusIntent = Intent(context, InstallStatusReceiver::class.java).apply {
                    action = InstallStatusReceiver.ACTION_INSTALL_STATUS
                }

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    flags
                )

                activeSession.commit(pendingIntent.intentSender)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller session failed, using FileProvider intent fallback: ${e.message}", e)
            installViaFileProvider(context, apkFile)
        }
    }

    /**
     * تثبيت التحديث بمشاركة الملف الآمنة عبر FileProvider مع معالج تثبيت الحزم بنظام أندرويد
     */
    fun installViaFileProvider(context: Context, apkFile: File): Result<Unit> {
        return try {
            if (!apkFile.exists() || apkFile.length() <= 0) {
                return Result.failure(Exception("ملف الـ APK غير موجود أو فارغ"))
            }

            if (!canRequestPackageInstalls()) {
                openInstallPermissionSettings(context)
                return Result.failure(
                    Exception("يرجى تفعيل صلاحية تثبيت التطبيقات لـ PMP Paints من شاشة الإعدادات الظاهرة، ثم إعادة الضغط على تثبيت التحديث.")
                )
            }

            val authority = "${context.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer via FileProvider: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * نقطة التثبيت الرئيسية: تُشغّل PackageInstaller مع دعم التبديل السلس للـ FileProvider
     */
    fun installApk(context: Context, apkFile: File): Result<Unit> {
        return installViaPackageInstaller(context, apkFile)
    }
}
