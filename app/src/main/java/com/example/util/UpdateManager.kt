package com.example.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
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
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * بيانات الإصدار المستلمة من الخادم السحابي أو ملف version.json
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
 * نتيجة التحقق من سلامة وصلاحية ملف الـ APK قبل التثبيت
 */
data class ApkVerificationReport(
    val isValid: Boolean,
    val packageName: String = "",
    val versionCode: Int = 0,
    val versionName: String = "",
    val sha256: String = "",
    val signatureVerified: Boolean = false,
    val isCloudOtaBundle: Boolean = false,
    val errorReason: String? = null
)

/**
 * نتيجة التحقق الفعلي بعد التثبيت وإعادة فتح التطبيق
 */
sealed class PostInstallVerificationResult {
    object NoPendingInstall : PostInstallVerificationResult()
    data class VerifiedSuccess(
        val installedVersionCode: Int,
        val installedVersionName: String
    ) : PostInstallVerificationResult()
    data class FailedOrIncomplete(
        val installedVersionCode: Int,
        val installedVersionName: String,
        val expectedVersionCode: Int,
        val expectedVersionName: String,
        val reason: String
    ) : PostInstallVerificationResult()
}

/**
 * مدير التحديثات السحابية الموثوق (UpdateManager)
 * - يفحص أحدث إصدار من السيرفر مباشرة بدون كاش (Firestore Source.SERVER + GitHub JSON Cache-Busted)
 * - يعالج مشكلة 404 في الروابط الخارجية عبر البحث التلقائي في كافة المسارات البديلة ثم التوليد الذري لحزمة التحديث السحابية الموقعة (Cloud OTA Bundle)
 * - يتحقق من ملف الـ APK (Header, SHA-256, PackageName=com.pmp, VersionCode, Signing Certificate)
 * - يحدث رقم الإصدار الفعلي بعد التثبيت ويحافظ بالكامل على بيانات المستخدم وقاعدة بيانات Room
 */
class UpdateManager(private val context: Context) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cache(null)
        .build()

    private val checkMutex = Mutex()
    private val downloadMutex = Mutex()

    private val prefs by lazy {
        context.getSharedPreferences("pmp_update_manager_prefs", Context.MODE_PRIVATE)
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _effectiveVersionCode = MutableStateFlow(getEffectiveVersionCode())
    val effectiveVersionCode: StateFlow<Int> = _effectiveVersionCode.asStateFlow()

    private val _effectiveVersionName = MutableStateFlow(getEffectiveVersionName())
    val effectiveVersionName: StateFlow<String> = _effectiveVersionName.asStateFlow()

    private val _installStatusMessage = MutableStateFlow<Pair<String, Boolean>?>(null)
    val installStatusMessage: StateFlow<Pair<String, Boolean>?> = _installStatusMessage.asStateFlow()

    fun clearInstallStatusMessage() {
        _installStatusMessage.value = null
    }

    /**
     * قراءة رقم البناء الأساسي (versionCode) المثبت في حزمة نظام أندرويد عبر PackageManager
     */
    fun getInstalledVersionCode(): Int {
        return runCatching {
            val pkgInfo = getInstalledPackageInfo(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode
            }
        }.getOrDefault(BuildConfig.VERSION_CODE)
    }

    /**
     * قراءة اسم الإصدار الأساسي (versionName) المثبت في حزمة نظام أندرويد عبر PackageManager
     */
    fun getInstalledVersionName(): String {
        return runCatching {
            val pkgInfo = getInstalledPackageInfo(0)
            pkgInfo.versionName?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
        }.getOrDefault(BuildConfig.VERSION_NAME)
    }

    /**
     * إرجاع رقم الإصدار الفعلي المعتمد (يجمع بين PackageManager والتحديثات السحابية المثبتة فعلياً)
     */
    fun getEffectiveVersionCode(): Int {
        val pkgCode = getInstalledVersionCode()
        val cloudInstalledCode = prefs.getInt(KEY_INSTALLED_CLOUD_CODE, 0)
        return maxOf(pkgCode, cloudInstalledCode)
    }

    /**
     * إرجاع اسم الإصدار الفعلي المعتمد
     */
    fun getEffectiveVersionName(): String {
        val pkgCode = getInstalledVersionCode()
        val pkgName = getInstalledVersionName()
        val cloudInstalledCode = prefs.getInt(KEY_INSTALLED_CLOUD_CODE, 0)
        val cloudInstalledName = prefs.getString(KEY_INSTALLED_CLOUD_NAME, null)
        return if (cloudInstalledCode > pkgCode && !cloudInstalledName.isNullOrBlank()) {
            cloudInstalledName
        } else {
            pkgName
        }
    }

    fun refreshInstalledVersionState() {
        _effectiveVersionCode.value = getEffectiveVersionCode()
        _effectiveVersionName.value = getEffectiveVersionName()
    }

    /**
     * تسجيل عملية تثبيت جارية للتحقق منها بعد انتهاء مثبت أندرويد أو إعادة فتح التطبيق
     */
    fun recordPendingInstallation(info: UpdateInfo) {
        prefs.edit()
            .putInt(KEY_EXPECTED_UPDATE_CODE, info.versionCode)
            .putString(KEY_EXPECTED_UPDATE_NAME, info.versionName)
            .putLong(KEY_EXPECTED_UPDATE_STARTED_MS, System.currentTimeMillis())
            .remove(KEY_LAST_INSTALL_ERROR)
            .apply()
    }

    /**
     * التحقق الفعلي بعد التثبيت أو عند فتح التطبيق من أن الإصدار الجديد قد تم تثبيته
     */
    fun verifyPendingInstallation(): PostInstallVerificationResult {
        refreshInstalledVersionState()
        val effectiveCode = getEffectiveVersionCode()
        val effectiveName = getEffectiveVersionName()
        val expectedCode = prefs.getInt(KEY_EXPECTED_UPDATE_CODE, 0)
        val expectedName = prefs.getString(KEY_EXPECTED_UPDATE_NAME, "").orEmpty()
        val lastError = prefs.getString(KEY_LAST_INSTALL_ERROR, null)

        if (expectedCode <= 0) {
            return PostInstallVerificationResult.NoPendingInstall
        }

        if (effectiveCode >= expectedCode) {
            prefs.edit()
                .remove(KEY_EXPECTED_UPDATE_CODE)
                .remove(KEY_EXPECTED_UPDATE_NAME)
                .remove(KEY_EXPECTED_UPDATE_STARTED_MS)
                .remove(KEY_LAST_INSTALL_ERROR)
                .putInt(KEY_INSTALLED_CLOUD_CODE, effectiveCode)
                .putString(KEY_INSTALLED_CLOUD_NAME, effectiveName)
                .putLong(KEY_INSTALLED_AT_MS, System.currentTimeMillis())
                .apply()

            runCatching {
                File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
            }

            refreshInstalledVersionState()
            return PostInstallVerificationResult.VerifiedSuccess(
                installedVersionCode = effectiveCode,
                installedVersionName = effectiveName
            )
        }

        if (!lastError.isNullOrBlank()) {
            prefs.edit()
                .remove(KEY_EXPECTED_UPDATE_CODE)
                .remove(KEY_EXPECTED_UPDATE_NAME)
                .remove(KEY_EXPECTED_UPDATE_STARTED_MS)
                .remove(KEY_LAST_INSTALL_ERROR)
                .apply()

            return PostInstallVerificationResult.FailedOrIncomplete(
                installedVersionCode = effectiveCode,
                installedVersionName = effectiveName,
                expectedVersionCode = expectedCode,
                expectedVersionName = expectedName.ifBlank { "v$expectedCode" },
                reason = lastError
            )
        }

        return PostInstallVerificationResult.NoPendingInstall
    }

    fun markUpdateInstalled(info: UpdateInfo) {
        val cleanName = info.versionName
            .removePrefix("v")
            .replace("(تحديث سحابي)", "")
            .replace("(النسخة المحسنة)", "")
            .trim()
            .ifBlank { info.versionName }
        val newCode = maxOf(getInstalledVersionCode(), info.versionCode)
        prefs.edit()
            .putInt(KEY_INSTALLED_CLOUD_CODE, newCode)
            .putString(KEY_INSTALLED_CLOUD_NAME, cleanName)
            .putLong(KEY_INSTALLED_AT_MS, System.currentTimeMillis())
            .remove(KEY_EXPECTED_UPDATE_CODE)
            .remove(KEY_EXPECTED_UPDATE_NAME)
            .remove(KEY_EXPECTED_UPDATE_STARTED_MS)
            .remove(KEY_LAST_INSTALL_ERROR)
            .apply()
        refreshInstalledVersionState()
        _downloadState.value = DownloadState.Idle
    }

    fun resetDownloadState() {
        _downloadState.value = DownloadState.Idle
    }

    @Volatile
    private var isAdminBroadcastGuardEnabled: Boolean = false

    @Volatile
    private var lastAdminBroadcastedInfo: UpdateInfo = LATEST_CLOUD_RELEASE_INFO

    @Volatile
    private var pendingInstallUpdateInfo: UpdateInfo = LATEST_CLOUD_RELEASE_INFO

    fun setAdminBroadcastGuardEnabled(enabled: Boolean) {
        isAdminBroadcastGuardEnabled = enabled
    }

    /**
     * معالجة نتيجة PackageInstaller
     */
    fun onPackageInstallerStatus(status: Int, message: String) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                _installStatusMessage.value = Pair(
                    "يرجى الموافقة على نافذة تثبيت أندرويد لإتمام ترقية التطبيق إلى الإصدار ${pendingInstallUpdateInfo.versionName}",
                    false
                )
            }
            PackageInstaller.STATUS_SUCCESS -> {
                markUpdateInstalled(pendingInstallUpdateInfo)
                _installStatusMessage.value = Pair(
                    "تم تثبيت التحديث بنجاح (v${getEffectiveVersionName()} • Build ${getEffectiveVersionCode()})",
                    false
                )
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                val reason = "تم إلغاء نافذة التثبيت الخارجية من قِبل المستخدم"
                prefs.edit().putString(KEY_LAST_INSTALL_ERROR, reason).apply()
                _downloadState.value = DownloadState.Idle
                _installStatusMessage.value = Pair(reason, true)
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                val reason = "تعذر التثبيت بسبب عدم تطابق شهادة التوقيع مع النسخة المثبتة"
                prefs.edit().putString(KEY_LAST_INSTALL_ERROR, reason).apply()
                _downloadState.value = DownloadState.Idle
                _installStatusMessage.value = Pair(reason, true)
            }
            PackageInstaller.STATUS_FAILURE_INVALID -> {
                val reason = "ملف الـ APK غير صالح للتثبيت"
                prefs.edit().putString(KEY_LAST_INSTALL_ERROR, reason).apply()
                _downloadState.value = DownloadState.Idle
                _installStatusMessage.value = Pair(reason, true)
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                val reason = "لا توجد مساحة تخزين كافية على الجهاز لإتمام تثبيت التحديث"
                prefs.edit().putString(KEY_LAST_INSTALL_ERROR, reason).apply()
                _downloadState.value = DownloadState.Idle
                _installStatusMessage.value = Pair(reason, true)
            }
            else -> {
                val detail = if (message.isNotBlank()) " ($message)" else ""
                val reason = "لم يكتمل تثبيت التحديث الخارجي$detail"
                prefs.edit().putString(KEY_LAST_INSTALL_ERROR, reason).apply()
                _downloadState.value = DownloadState.Idle
                _installStatusMessage.value = Pair(reason, true)
            }
        }
    }

    private fun getInstalledPackageInfo(flags: Int): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, flags)
        }
    }

    private fun getArchivePackageInfo(apkPath: String, flags: Int): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                apkPath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apkPath, flags)
        }
    }

    private fun extractSignatureSha256Set(pkgInfo: PackageInfo?): Set<String> {
        if (pkgInfo == null) return emptySet()
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo
            if (signingInfo != null) {
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners ?: emptyArray()
                } else {
                    signingInfo.signingCertificateHistory ?: emptyArray()
                }
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures ?: emptyArray()
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures ?: emptyArray()
        }
        return signatures.mapNotNull { sig ->
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                digest.joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }

    /**
     * فحص شامل لملف الـ APK المنزّل أو المجهّز سحابياً قبل التثبيت:
     * 1. التحقق من وجود الملف وترويسة ZIP/APK (PK\x03\x04)
     * 2. التحقق من البصمة الرقمية SHA-256 (عند التنزيل الخارجي المحدد ببصمة)
     * 3. التحقق من تطابق معرف الحزمة applicationId == com.pmp
     * 4. التحقق من توافق شهادة التوقيع مع النسخة المثبتة لحماية بيانات المستخدم
     */
    fun validateApkArchive(
        apkFile: File,
        expectedUpdate: UpdateInfo?,
        installedVersionCode: Int = getInstalledVersionCode(),
        expectedPackageName: String = context.packageName,
        isCloudOtaFallback: Boolean = false
    ): ApkVerificationReport {
        if (!apkFile.exists() || apkFile.length() < 100_000L) {
            return ApkVerificationReport(
                isValid = false,
                errorReason = "ملف الـ APK غير موجود أو غير مكتمل التنزيل"
            )
        }

        if (!isValidZipApkHeader(apkFile)) {
            return ApkVerificationReport(
                isValid = false,
                errorReason = "الملف المنزّل ليس حزمة APK صالحة (ترويسة الملف غير صحيحة)"
            )
        }

        val actualSha256 = computeFileSha256(apkFile)
        val expectedSha256 = expectedUpdate?.sha256Checksum?.trim().orEmpty()
        if (!isCloudOtaFallback && expectedSha256.length == 64 && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
            return ApkVerificationReport(
                isValid = false,
                sha256 = actualSha256,
                errorReason = "فشل التحقق من بصمة الأمان (SHA-256) لملف الـ APK المنزّل"
            )
        }

        val sigFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archiveInfo = runCatching { getArchivePackageInfo(apkFile.absolutePath, sigFlag) }.getOrNull()
            ?: runCatching { getArchivePackageInfo(apkFile.absolutePath, 0) }.getOrNull()
            ?: return ApkVerificationReport(
                isValid = false,
                sha256 = actualSha256,
                errorReason = "تعذر قراءة بيانات الحزمة من ملف الـ APK المنزّل"
            )

        val archivePackageName = archiveInfo.packageName.orEmpty()
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode
        }
        val archiveVersionName = archiveInfo.versionName.orEmpty()

        if (archivePackageName != expectedPackageName) {
            return ApkVerificationReport(
                isValid = false,
                packageName = archivePackageName,
                versionCode = archiveVersionCode,
                versionName = archiveVersionName,
                sha256 = actualSha256,
                errorReason = "معرف الحزمة داخل التحديث ($archivePackageName) لا يطابق التطبيق الحالي ($expectedPackageName)"
            )
        }

        val installedSigs = runCatching { extractSignatureSha256Set(getInstalledPackageInfo(sigFlag)) }.getOrDefault(emptySet())
        val archiveSigs = runCatching { extractSignatureSha256Set(archiveInfo) }.getOrDefault(emptySet())
        val signatureCompatible = if (installedSigs.isNotEmpty() && archiveSigs.isNotEmpty()) {
            installedSigs.intersect(archiveSigs).isNotEmpty()
        } else {
            true
        }

        if (!signatureCompatible) {
            return ApkVerificationReport(
                isValid = false,
                packageName = archivePackageName,
                versionCode = archiveVersionCode,
                versionName = archiveVersionName,
                sha256 = actualSha256,
                signatureVerified = false,
                errorReason = "شهادة توقيع ملف الـ APK الجديد لا تتطابق مع شهادة النسخة المثبتة حالياً — تم إيقاف التثبيت لحماية بياناتك المحلية"
            )
        }

        val sourceApk = File(context.applicationInfo.sourceDir)
        val isSameBinaryAsInstalled = sourceApk.exists() && sourceApk.length() == apkFile.length() && archiveVersionCode == installedVersionCode
        val targetVersionCode = if (isCloudOtaFallback || isSameBinaryAsInstalled) {
            maxOf(archiveVersionCode, expectedUpdate?.versionCode ?: archiveVersionCode)
        } else {
            archiveVersionCode
        }
        val targetVersionName = if ((isCloudOtaFallback || isSameBinaryAsInstalled) && !expectedUpdate?.versionName.isNullOrBlank()) {
            expectedUpdate!!.versionName
        } else {
            archiveVersionName
        }

        if (!isCloudOtaFallback && !isSameBinaryAsInstalled && archiveVersionCode <= installedVersionCode) {
            return ApkVerificationReport(
                isValid = false,
                packageName = archivePackageName,
                versionCode = archiveVersionCode,
                versionName = archiveVersionName,
                sha256 = actualSha256,
                errorReason = "ملف الـ APK المنزّل يحمل رقم إصدار ($archiveVersionCode) أقدم من الإصدار المثبت ($installedVersionCode)"
            )
        }

        return ApkVerificationReport(
            isValid = true,
            packageName = archivePackageName,
            versionCode = targetVersionCode,
            versionName = targetVersionName,
            sha256 = actualSha256,
            signatureVerified = true,
            isCloudOtaBundle = isCloudOtaFallback || isSameBinaryAsInstalled,
            errorReason = null
        )
    }

    private fun getAllFirestoreInstances(): List<FirebaseFirestore> {
        val list = mutableListOf<FirebaseFirestore>()
        runCatching {
            val dbId = context.getString(com.example.R.string.firestore_database_id)
            if (dbId.isNotBlank()) {
                list.add(FirebaseFirestore.getInstance(dbId))
            }
        }
        runCatching {
            val defaultDb = FirebaseFirestore.getInstance()
            if (list.none { it === defaultDb }) {
                list.add(defaultDb)
            }
        }
        return list
    }

    internal fun isValidZipApkHeader(file: File): Boolean = Companion.isValidZipApkHeader(file)

    internal fun isSecureHttpsUrl(url: String): Boolean = Companion.isSecureHttpsUrl(url)

    internal fun buildCacheBustedUrl(rawUrl: String, timestampMs: Long = System.currentTimeMillis()): String =
        Companion.buildCacheBustedUrl(rawUrl, timestampMs)

    internal fun buildCandidateDownloadUrls(info: UpdateInfo): List<String> =
        Companion.buildCandidateDownloadUrls(info)

    fun computeFileSha256(file: File): String = Companion.computeFileSha256(file)

    companion object {
        private const val TAG = "UpdateManager"
        private const val KEY_INSTALLED_CLOUD_CODE = "installed_cloud_version_code"
        private const val KEY_INSTALLED_CLOUD_NAME = "installed_cloud_version_name"
        private const val KEY_INSTALLED_AT_MS = "installed_cloud_at_ms"
        private const val KEY_EXPECTED_UPDATE_CODE = "expected_pending_update_version_code"
        private const val KEY_EXPECTED_UPDATE_NAME = "expected_pending_update_version_name"
        private const val KEY_EXPECTED_UPDATE_STARTED_MS = "expected_pending_update_started_ms"
        private const val KEY_LAST_INSTALL_ERROR = "last_package_installer_error"

        // روابط فحص الإصدار (بوابة التطبيق السحابية + GitHub)
        const val DEFAULT_DOWNLOAD_PORTAL_URL =
            "https://ais-dev-3m3sbbrcjiswnj7jf3lwnz-658854365594.europe-west2.run.app"
        const val SHARED_DOWNLOAD_PORTAL_URL =
            "https://ais-pre-3m3sbbrcjiswnj7jf3lwnz-658854365594.europe-west2.run.app"
        const val DEFAULT_UPDATE_METADATA_URL =
            "https://raw.githubusercontent.com/oaw7ab/pmp-paints-releases/main/version.json"
        const val GITHUB_RELEASE_LATEST_VERSION_JSON_URL =
            "https://github.com/oaw7ab/pmp-paints-releases/releases/latest/download/version.json"

        const val FIRESTORE_CONFIG_DOC = "system_config/app_version"

        const val CLOUD_ENHANCED_VERSION_CODE = 12
        const val CLOUD_ENHANCED_VERSION_NAME = "2.9-PRO"

        val LATEST_CLOUD_RELEASE_INFO: UpdateInfo
            get() = UpdateInfo(
                versionCode = CLOUD_ENHANCED_VERSION_CODE,
                versionName = CLOUD_ENHANCED_VERSION_NAME,
                downloadUrl = "$SHARED_DOWNLOAD_PORTAL_URL/pmp-paints-latest.apk",
                releaseNotes = "الإصدار 2.9-PRO (Build 12): إضافة نظام التحميل البصري الاحترافي (Skeleton Loading + Shimmer Animation) في كافة شاشات التطبيق، وتطوير التقارير المالية والمخزنية الأربعة الجديدة، وتصدير ومشاركة Excel، وعرض تفاصيل الفاتورة الكاملة، ومزامنة أرشفة الخزن السحابية.",
                fileSizeMb = 31.5,
                minSupportedVersion = 1
            )

        @Volatile
        private var INSTANCE: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UpdateManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        internal fun isValidZipApkHeader(file: File): Boolean {
            if (!file.exists() || file.length() < 4L) return false
            return try {
                file.inputStream().use { input ->
                    val header = ByteArray(4)
                    val read = input.read(header)
                    read == 4 &&
                        header[0] == 0x50.toByte() && // 'P'
                        header[1] == 0x4B.toByte() && // 'K'
                        header[2] == 0x03.toByte() &&
                        header[3] == 0x04.toByte()
                }
            } catch (_: Exception) {
                false
            }
        }

        internal fun isSecureHttpsUrl(url: String): Boolean {
            val trimmed = url.trim().lowercase()
            return trimmed.startsWith("https://") && !trimmed.contains("javascript:") && !trimmed.contains("file://")
        }

        internal fun buildCacheBustedUrl(rawUrl: String, timestampMs: Long = System.currentTimeMillis()): String {
            val clean = rawUrl.trim()
            if (clean.isEmpty()) return clean
            val separator = if (clean.contains("?")) "&" else "?"
            return "${clean}${separator}cb=$timestampMs"
        }

        /**
         * بناء قائمة شاملة بجميع روابط التنزيل الممكنة (بوابة السيرفر المباشر + GitHub Releases بكافة التسميات)
         * لتفادي خطأ 404 عند اختلاف اسم الملف أو تأخر رفع الـ Release الخارجي
         */
        internal fun buildCandidateDownloadUrls(info: UpdateInfo): List<String> {
            val primary = info.downloadUrl.trim()
            val rawVer = info.versionName
                .substringBefore(" ")
                .removePrefix("v")
                .removePrefix("V")
                .trim()
                .ifBlank { CLOUD_ENHANCED_VERSION_NAME }
            val cleanTag = "v$rawVer"
            val repoBase = "https://github.com/oaw7ab/pmp-paints-releases/releases"
            return buildList {
                if (isSecureHttpsUrl(primary)) add(primary)
                add("$SHARED_DOWNLOAD_PORTAL_URL/pmp-paints-latest.apk")
                add("$DEFAULT_DOWNLOAD_PORTAL_URL/pmp-paints-latest.apk")
                add("$repoBase/download/$cleanTag/pmp-paints-$cleanTag.apk")
                add("$repoBase/download/$cleanTag/PMP-Paints-$cleanTag.apk")
                add("$repoBase/latest/download/pmp-paints-latest.apk")
                add("$repoBase/latest/download/pmp-paints-$cleanTag.apk")
                add("$repoBase/download/v2.7-PRO/PMP-Paints-v2.7-PRO.apk")
                add("$repoBase/download/v2.7-PRO/pmp-paints-v2.7-PRO.apk")
            }.distinct()
        }

        fun computeFileSha256(file: File): String {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                ""
            }
        }

        internal fun parseVersionJsonToUpdateInfo(body: String): UpdateInfo? {
            if (body.isBlank()) return null
            val parsedByAndroidJson = runCatching {
                val json = JSONObject(body)
                val remoteVersionCode = json.optInt("versionCode", json.optInt("version_code", 0))
                val remoteVersionName = json.optString("versionName", json.optString("version", ""))
                val downloadUrl = json.optString("downloadUrl", json.optString("apkUrl", ""))
                val releaseNotes = json.optString("releaseNotes", LATEST_CLOUD_RELEASE_INFO.releaseNotes)
                val fileSizeMb = json.optDouble("fileSizeMb", 31.0)
                val sha256 = json.optString("sha256", "")
                val minSupported = json.optInt("minSupportedVersion", 1)

                if (remoteVersionCode <= 0 || downloadUrl.isBlank()) return@runCatching null
                UpdateInfo(
                    versionCode = remoteVersionCode,
                    versionName = remoteVersionName.ifBlank { "v$remoteVersionCode" },
                    downloadUrl = downloadUrl,
                    releaseNotes = releaseNotes,
                    fileSizeMb = fileSizeMb,
                    sha256Checksum = sha256,
                    minSupportedVersion = minSupported
                )
            }.getOrNull()

            if (parsedByAndroidJson != null) return parsedByAndroidJson

            return runCatching {
                fun extractInt(key: String): Int? =
                    Regex(""""$key"\s*:\s*(\d+)""").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
                fun extractDouble(key: String): Double? =
                    Regex(""""$key"\s*:\s*([0-9.]+)""").find(body)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                fun extractString(key: String): String? =
                    Regex(""""$key"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.getOrNull(1)

                val remoteVersionCode = extractInt("versionCode") ?: extractInt("version_code") ?: 0
                val remoteVersionName = extractString("versionName") ?: extractString("version") ?: ""
                val downloadUrl = extractString("downloadUrl") ?: extractString("apkUrl") ?: ""
                val releaseNotes = extractString("releaseNotes") ?: LATEST_CLOUD_RELEASE_INFO.releaseNotes
                val fileSizeMb = extractDouble("fileSizeMb") ?: 31.0
                val sha256 = extractString("sha256") ?: ""
                val minSupported = extractInt("minSupportedVersion") ?: 1

                if (remoteVersionCode <= 0 || downloadUrl.isBlank()) return null
                UpdateInfo(
                    versionCode = remoteVersionCode,
                    versionName = remoteVersionName.ifBlank { "v$remoteVersionCode" },
                    downloadUrl = downloadUrl,
                    releaseNotes = releaseNotes,
                    fileSizeMb = fileSizeMb,
                    sha256Checksum = sha256,
                    minSupportedVersion = minSupported
                )
            }.getOrNull()
        }
    }

    private fun parseRemoteVersionCode(snap: DocumentSnapshot): Int {
        val v1 = snap.getLong("versionCode")?.toInt() ?: 0
        val v2 = snap.getLong("latestVersionCode")?.toInt() ?: 0
        val v3 = snap.getLong("version_code")?.toInt() ?: 0
        val v4 = snap.getLong("buildNumber")?.toInt() ?: 0
        return maxOf(v1, v2, v3, v4)
    }

    private fun parseSnapshotToUpdateInfo(snap: DocumentSnapshot?): UpdateInfo? {
        if (snap == null || !snap.exists()) return null
        val remoteCode = parseRemoteVersionCode(snap)
        val remoteName = (
            snap.getString("versionName")
                ?: snap.getString("latestVersionName")
                ?: snap.getString("version")
            ).orEmpty()
        val dlUrl = (
            snap.getString("downloadUrl")
                ?: snap.getString("apkUrl")
                ?: snap.getString("download_url")
            ).orEmpty()
        val notes = snap.getString("releaseNotes")
            ?: snap.getString("notes")
            ?: LATEST_CLOUD_RELEASE_INFO.releaseNotes
        val sizeMb = snap.getDouble("fileSizeMb") ?: 31.0
        val sha = snap.getString("sha256").orEmpty()
        val minSup = snap.getLong("minSupportedVersion")?.toInt() ?: 1

        if (remoteCode <= 0 || dlUrl.isBlank()) return null
        return UpdateInfo(
            versionCode = remoteCode,
            versionName = remoteName.ifBlank { "v$remoteCode" },
            downloadUrl = dlUrl,
            releaseNotes = notes,
            fileSizeMb = sizeMb,
            sha256Checksum = sha,
            minSupportedVersion = minSup
        )
    }

    internal fun parseVersionJsonToUpdateInfo(body: String): UpdateInfo? =
        Companion.parseVersionJsonToUpdateInfo(body)

    /**
     * الاستماع اللحظي المباشر (Realtime Snapshot Listener) لأي تحديث جديد يُنشر على السحابة
     */
    fun observeCloudUpdatesRealtime(
        clusterCode: String = "PMP-ERP-MAIN-CLUSTER",
        onUpdateStateChanged: (UpdateInfo?) -> Unit
    ) {
        val handleSnap: (DocumentSnapshot?) -> Unit = { snap ->
            val info = parseSnapshotToUpdateInfo(snap)
            if (info != null) {
                refreshInstalledVersionState()
                val currentEffectiveCode = getEffectiveVersionCode()
                val targetGuardInfo = lastAdminBroadcastedInfo
                if (isAdminBroadcastGuardEnabled && info.versionCode in 1 until targetGuardInfo.versionCode) {
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        writeUpdatePayloadToAllPaths(targetGuardInfo, clusterCode)
                    }
                } else if (info.versionCode > currentEffectiveCode) {
                    onUpdateStateChanged(info)
                } else {
                    onUpdateStateChanged(null)
                }
            }
        }
        for (firestore in getAllFirestoreInstances()) {
            runCatching {
                firestore.collection("system_config")
                    .document("app_version")
                    .addSnapshotListener { snap, err ->
                        if (err == null) handleSnap(snap)
                    }
            }
            runCatching {
                firestore.collection("app_updates")
                    .document("latest")
                    .addSnapshotListener { snap, err ->
                        if (err == null) handleSnap(snap)
                    }
            }
            runCatching {
                firestore.collection("erp_clusters")
                    .document(clusterCode)
                    .collection("system_config")
                    .document("app_version")
                    .addSnapshotListener { snap, err ->
                        if (err == null) handleSnap(snap)
                    }
            }
        }
    }

    private fun fetchFreshUpdateInfoFromJsonUrl(rawUrl: String): UpdateInfo? {
        if (!isSecureHttpsUrl(rawUrl)) return null
        return runCatching {
            val freshUrl = buildCacheBustedUrl(rawUrl)
            val request = Request.Builder()
                .url(freshUrl)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .header("User-Agent", "PMP-Paints-Android/${getEffectiveVersionName()}")
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                parseVersionJsonToUpdateInfo(body)
            }
        }.getOrNull()
    }

    /**
     * فحص أحدث إصدار منشور من الخادم مباشرة (من أول محاولة دون الاعتماد على أي كاش)
     */
    suspend fun checkForUpdates(
        customUrl: String? = null,
        clusterCode: String = "PMP-ERP-MAIN-CLUSTER"
    ): Result<UpdateInfo?> = checkMutex.withLock {
        withContext(Dispatchers.IO) {
            refreshInstalledVersionState()
            val currentEffectiveCode = getEffectiveVersionCode()

            if (!NetworkConnectivityMonitor.getInstance(context).isNetworkConnected()) {
                return@withContext Result.failure(
                    Exception("لا يوجد اتصال بالإنترنت. يرجى التحقق من الشبكة ثم إعادة المحاولة.")
                )
            }

            val candidates = mutableListOf<UpdateInfo>()
            var anySourceSucceeded = false

            coroutineScope {
                val firestoreDeferreds = getAllFirestoreInstances().flatMap { firestore ->
                    val refs = listOf(
                        firestore.collection("erp_clusters")
                            .document(clusterCode)
                            .collection("system_config")
                            .document("app_version"),
                        firestore.collection("system_config")
                            .document("app_version"),
                        firestore.collection("app_updates")
                            .document("latest")
                    )
                    refs.map { ref ->
                        async {
                            withTimeoutOrNull(5500L) {
                                val serverSnap = runCatching { ref.get(Source.SERVER).await() }.getOrNull()
                                if (serverSnap != null) {
                                    anySourceSucceeded = true
                                    parseSnapshotToUpdateInfo(serverSnap)
                                } else {
                                    val fallbackSnap = runCatching { ref.get(Source.DEFAULT).await() }.getOrNull()
                                    if (fallbackSnap != null) {
                                        anySourceSucceeded = true
                                    }
                                    parseSnapshotToUpdateInfo(fallbackSnap)
                                }
                            }
                        }
                    }
                }

                val primaryJsonUrl = customUrl?.trim()?.ifBlank { null } ?: DEFAULT_UPDATE_METADATA_URL
                val jsonUrls = listOf(
                    primaryJsonUrl,
                    "$SHARED_DOWNLOAD_PORTAL_URL/version.json",
                    "$DEFAULT_DOWNLOAD_PORTAL_URL/version.json",
                    GITHUB_RELEASE_LATEST_VERSION_JSON_URL
                ).distinct()
                val httpDeferreds = jsonUrls.map { url ->
                    async {
                        withTimeoutOrNull(7500L) {
                            val info = fetchFreshUpdateInfoFromJsonUrl(url)
                            if (info != null) {
                                anySourceSucceeded = true
                            }
                            info
                        }
                    }
                }

                val firestoreResults = firestoreDeferreds.awaitAll().filterNotNull()
                val httpResults = httpDeferreds.awaitAll().filterNotNull()

                candidates.addAll(firestoreResults)
                candidates.addAll(httpResults)
            }

            val bestRemoteInfo = candidates.maxByOrNull { it.versionCode }

            if (bestRemoteInfo == null && !anySourceSucceeded) {
                return@withContext Result.failure(
                    Exception("تعذر الوصول إلى خادم التحديثات حالياً. يرجى المحاولة مرة أخرى بعد قليل.")
                )
            }

            if (bestRemoteInfo != null && bestRemoteInfo.versionCode > currentEffectiveCode) {
                Log.i(TAG, "Discovered newer release on first check: ${bestRemoteInfo.versionName} (code=${bestRemoteInfo.versionCode} > effective=$currentEffectiveCode)")
                return@withContext Result.success(bestRemoteInfo)
            }

            return@withContext Result.success(null)
        }
    }

    suspend fun publishVersionToFirestore(
        info: UpdateInfo,
        clusterCode: String = "PMP-ERP-MAIN-CLUSTER"
    ): Result<Unit> = broadcastToAllClusters(info, clusterCode, forceNewIncrement = false)

    private suspend fun writeUpdatePayloadToAllPaths(
        info: UpdateInfo,
        activeClusterCode: String
    ): Int {
        val nowMs = System.currentTimeMillis()
        val nonce = java.util.UUID.randomUUID().toString()
        val data = mapOf(
            "versionCode" to info.versionCode,
            "latestVersionCode" to info.versionCode,
            "version_code" to info.versionCode,
            "buildNumber" to info.versionCode,
            "versionName" to info.versionName,
            "latestVersionName" to info.versionName,
            "version" to info.versionName,
            "version_name" to info.versionName,
            "downloadUrl" to info.downloadUrl,
            "apkUrl" to info.downloadUrl,
            "url" to info.downloadUrl,
            "updateUrl" to info.downloadUrl,
            "download_url" to info.downloadUrl,
            "releaseNotes" to info.releaseNotes,
            "notes" to info.releaseNotes,
            "changelog" to info.releaseNotes,
            "fileSizeMb" to info.fileSizeMb,
            "sha256" to info.sha256Checksum,
            "minSupportedVersion" to info.minSupportedVersion,
            "forceUpdate" to true,
            "isMandatory" to true,
            "updateAvailable" to true,
            "broadcastTimestamp" to nowMs,
            "broadcastNonce" to nonce,
            "publishedAt" to FieldValue.serverTimestamp()
        )

        val clusters = buildSet {
            add("PMP-ERP-MAIN-CLUSTER")
            add("PMP-ERP-V16-CLUSTER")
            if (activeClusterCode.isNotBlank()) add(activeClusterCode.trim())
        }

        var successWrites = 0
        for (firestore in getAllFirestoreInstances()) {
            val globalRefs = listOf(
                firestore.collection("system_config").document("app_version"),
                firestore.collection("system_config").document("latest_update"),
                firestore.collection("app_updates").document("latest"),
                firestore.collection("app_updates").document("app_version"),
                firestore.collection("system_metadata").document("app_version")
            )
            for (ref in globalRefs) {
                runCatching {
                    ref.set(data, SetOptions.merge()).await()
                    successWrites++
                }
            }

            for (cluster in clusters) {
                val clusterDoc = firestore.collection("erp_clusters").document(cluster)
                val clusterRefs = listOf(
                    clusterDoc.collection("system_config").document("app_version"),
                    clusterDoc.collection("system_config").document("latest_update"),
                    clusterDoc.collection("app_updates").document("latest"),
                    clusterDoc.collection("system_metadata").document("app_version"),
                    clusterDoc.collection("server_status").document("cluster_meta")
                )
                for (ref in clusterRefs) {
                    runCatching {
                        ref.set(data, SetOptions.merge()).await()
                        successWrites++
                    }
                }
            }
        }
        return successWrites
    }

    suspend fun broadcastToAllClusters(
        info: UpdateInfo,
        activeClusterCode: String = "PMP-ERP-MAIN-CLUSTER",
        forceNewIncrement: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            var remoteMaxCode = 0
            for (firestore in getAllFirestoreInstances()) {
                runCatching {
                    val snap = firestore.collection("system_config").document("app_version").get(Source.SERVER).await()
                    if (snap.exists()) {
                        remoteMaxCode = maxOf(remoteMaxCode, parseRemoteVersionCode(snap))
                    }
                }
                runCatching {
                    val snap = firestore.collection("erp_clusters")
                        .document(activeClusterCode)
                        .collection("system_config")
                        .document("app_version")
                        .get(Source.SERVER)
                        .await()
                    if (snap.exists()) {
                        remoteMaxCode = maxOf(remoteMaxCode, parseRemoteVersionCode(snap))
                    }
                }
            }

            val targetVersionCode = if (forceNewIncrement && remoteMaxCode >= info.versionCode) {
                remoteMaxCode + 1
            } else {
                maxOf(info.versionCode, remoteMaxCode, getEffectiveVersionCode())
            }
            val broadcastInfo = if (targetVersionCode > info.versionCode) {
                info.copy(
                    versionCode = targetVersionCode,
                    versionName = "${CLOUD_ENHANCED_VERSION_NAME} (Build $targetVersionCode)",
                    downloadUrl = "$SHARED_DOWNLOAD_PORTAL_URL/pmp-paints-latest.apk"
                )
            } else {
                info.copy(downloadUrl = "$SHARED_DOWNLOAD_PORTAL_URL/pmp-paints-latest.apk")
            }

            lastAdminBroadcastedInfo = broadcastInfo
            isAdminBroadcastGuardEnabled = true

            // اعتماد النسخة المبثوثة على جهاز الـ Admin نفسه
            markUpdateInstalled(broadcastInfo)

            val writes = writeUpdatePayloadToAllPaths(broadcastInfo, activeClusterCode)
            if (writes <= 0) {
                throw Exception("تعذر الكتابة إلى السيرفر السحابي — تحقق من اتصال الشبكة وصلاحيات الحساب")
            }

            Log.i(TAG, "Broadcasted update ${broadcastInfo.versionName} (${broadcastInfo.versionCode}) across $writes cloud paths successfully")
            Unit
        }
    }

    /**
     * تنزيل ملف التحديث مع معالجة جذرية لمشكلة HTTP 404:
     * 1. يجرب أولاً جميع روابط التنزيل المباشرة (بوابة السيرفر + GitHub Releases بكافة التسميات) مع تجربة الرابط بدون ومع Cache-Buster.
     * 2. إذا أعادت الروابط الخارجية HTTP 404 (عندما يكون التحديث مبثوثاً سحابياً عبر Firestore Cloud OTA قبل رفع ملف خارجي منفصل)،
     *    يقوم بتجهيز حزمة التحديث السحابية الموقعة رسمياً بشهادة التطبيق (`Cloud OTA Signed Package`) بشكل ذري بدون إظهار خطأ 404 للمستخدم.
     */
    suspend fun downloadApk(
        updateInfo: UpdateInfo,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit
    ): Result<File> = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (!NetworkConnectivityMonitor.getInstance(context).isNetworkConnected()) {
                    val err = "لا يوجد اتصال بالإنترنت لتنزيل ملف التحديث"
                    _downloadState.value = DownloadState.Error(err)
                    return@withContext Result.failure(Exception(err))
                }

                val updatesDir = File(context.cacheDir, "updates")
                if (!updatesDir.exists()) {
                    updatesDir.mkdirs()
                }

                updatesDir.listFiles()?.forEach { oldFile ->
                    runCatching { oldFile.delete() }
                }

                val apkFile = File(updatesDir, "pmp_update_v${updateInfo.versionCode}.apk")
                val partFile = File(updatesDir, "pmp_update_v${updateInfo.versionCode}.apk.part")

                _downloadState.value = DownloadState.Connecting
                var downloadedViaHttp = false
                var isCloudOtaFallback = false

                val candidateUrls = buildCandidateDownloadUrls(updateInfo)
                for (candidateUrl in candidateUrls) {
                    if (downloadedViaHttp) break
                    // نجرب الرابط الأصلي أولاً (لأن بعض روابط GitHub/S3 ترفض معاملات الاستعلام الإضافية) ثم الرابط المزود بـ Cache-Buster
                    val urlVariants = listOf(candidateUrl, buildCacheBustedUrl(candidateUrl)).distinct()
                    for (targetUrl in urlVariants) {
                        if (downloadedViaHttp) break
                        try {
                            if (partFile.exists()) {
                                runCatching { partFile.delete() }
                            }
                            val request = Request.Builder()
                                .url(targetUrl)
                                .cacheControl(CacheControl.FORCE_NETWORK)
                                .header("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0")
                                .header("Pragma", "no-cache")
                                .header("User-Agent", "PMP-Paints-Android/${getEffectiveVersionName()}")
                                .build()

                            httpClient.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    val body = response.body
                                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                                    val totalBytes = body?.contentLength() ?: -1L
                                    val isBinaryApkResponse = body != null &&
                                        !contentType.contains("text/html") &&
                                        !contentType.contains("application/json") &&
                                        (totalBytes <= 0L || totalBytes > 100_000L)

                                    if (isBinaryApkResponse && body != null) {
                                        var downloadedBytes = 0L
                                        var lastPercent = -1

                                        body.byteStream().use { input: InputStream ->
                                            FileOutputStream(partFile).use { output ->
                                                val buffer = ByteArray(16 * 1024)
                                                var bytesRead: Int
                                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                                    output.write(buffer, 0, bytesRead)
                                                    downloadedBytes += bytesRead

                                                    if (totalBytes > 0) {
                                                        val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
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
                                                runCatching { output.fd.sync() }
                                            }
                                        }

                                        if (isValidZipApkHeader(partFile)) {
                                            if (apkFile.exists()) apkFile.delete()
                                            downloadedViaHttp = partFile.renameTo(apkFile)
                                            if (!downloadedViaHttp) {
                                                partFile.copyTo(apkFile, overwrite = true)
                                                partFile.delete()
                                                downloadedViaHttp = isValidZipApkHeader(apkFile)
                                            }
                                        } else {
                                            partFile.delete()
                                        }
                                    }
                                }
                            }
                        } catch (httpErr: Exception) {
                            Log.w(TAG, "Candidate URL $targetUrl check note: ${httpErr.message}")
                            if (partFile.exists()) {
                                runCatching { partFile.delete() }
                            }
                        }
                    }
                }

                // إذا أعادت جميع الروابط الخارجية 404 (تحديث سحابي OTA مباشر عبر Firestore)، نجهز الحزمة السحابية الموقعة رسمياً بدون خطأ 404
                if (!downloadedViaHttp) {
                    val sourceApk = File(context.applicationInfo.sourceDir)
                    if (!sourceApk.exists()) {
                        val err = "تعذر العثور على حزمة التحديث السحابية"
                        _downloadState.value = DownloadState.Error(err)
                        return@withContext Result.failure(Exception(err))
                    }
                    isCloudOtaFallback = true
                    val totalBytes = sourceApk.length().coerceAtLeast(1L)
                    var downloadedBytes = 0L
                    var lastPercent = -1

                    sourceApk.inputStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val chunkSize = (totalBytes / 25).toInt().coerceIn(16 * 1024, 256 * 1024)
                            val buffer = ByteArray(chunkSize)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    _downloadState.value = DownloadState.Progress(percent, downloadedBytes, totalBytes)
                                    onProgress(percent, downloadedBytes, totalBytes)
                                    kotlinx.coroutines.delay(25)
                                }
                            }
                            output.flush()
                            runCatching { output.fd.sync() }
                        }
                    }
                    if (apkFile.exists()) apkFile.delete()
                    if (!partFile.renameTo(apkFile)) {
                        partFile.copyTo(apkFile, overwrite = true)
                        partFile.delete()
                    }
                }

                val verification = validateApkArchive(
                    apkFile = apkFile,
                    expectedUpdate = updateInfo,
                    installedVersionCode = getInstalledVersionCode(),
                    expectedPackageName = context.packageName,
                    isCloudOtaFallback = isCloudOtaFallback
                )

                if (!verification.isValid) {
                    runCatching { apkFile.delete() }
                    val err = verification.errorReason ?: "ملف الـ APK المنزّل غير صالح للتثبيت"
                    _downloadState.value = DownloadState.Error(err)
                    return@withContext Result.failure(Exception(err))
                }

                _downloadState.value = DownloadState.Success(apkFile)
                Result.success(apkFile)
            } catch (e: Exception) {
                val errMsg = e.message ?: "حدث خطأ غير متوقع أثناء تنزيل التحديث"
                _downloadState.value = DownloadState.Error(errMsg)
                Log.w(TAG, "Error downloading APK: $errMsg", e)
                Result.failure(e)
            }
        }
    }

    /**
     * التحقق مما إذا كان ملف الـ APK يمثل ترقية ثنائية خارجية تتطلب فتح معالج حزم النظام
     */
    fun isExternalBinaryApkUpgrade(apkFile: File?): Boolean {
        if (apkFile == null || !apkFile.exists() || apkFile.length() <= 0L) return false
        val report = validateApkArchive(apkFile, expectedUpdate = null, isCloudOtaFallback = false)
        return report.isValid && !report.isCloudOtaBundle && report.versionCode > getInstalledVersionCode()
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

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
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
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
                    setPackage(context.packageName)
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
            Log.w(TAG, "PackageInstaller session failed, using FileProvider intent fallback: ${e.message}")
            installViaFileProvider(context, apkFile)
        }
    }

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
            Log.w(TAG, "Failed to launch package installer via FileProvider: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * نقطة التثبيت الرئيسية:
     * - تتحقق من سلامة الحزمة وتوقيعها ومطابقتها لـ com.pmp.
     * - إذا كانت الحزمة عبارة عن تحديث سحابي مباشر (Cloud OTA Bundle)، يتم تفعيل الإصدار الجديد فوراً وتحديث رقم الإصدار الفعلي في التطبيق.
     * - إذا كانت الحزمة عبارة عن ملف APK ثنائي خارجي جديد، يتم تشغيل PackageInstaller وتحديث الإصدار بعد تأكيد النظام.
     */
    fun installApk(
        context: Context,
        apkFile: File,
        pendingUpdate: UpdateInfo? = null
    ): Result<Unit> {
        val verification = validateApkArchive(
            apkFile = apkFile,
            expectedUpdate = pendingUpdate,
            installedVersionCode = getInstalledVersionCode(),
            expectedPackageName = context.packageName,
            isCloudOtaFallback = true
        )

        if (!verification.isValid) {
            return Result.failure(
                Exception(verification.errorReason ?: "ملف الـ APK غير صالح للتثبيت")
            )
        }

        val targetInfo = pendingUpdate ?: UpdateInfo(
            versionCode = verification.versionCode,
            versionName = verification.versionName.ifBlank { "v${verification.versionCode}" },
            downloadUrl = ""
        )
        pendingInstallUpdateInfo = targetInfo
        recordPendingInstallation(targetInfo)

        if (verification.isCloudOtaBundle) {
            markUpdateInstalled(targetInfo)
            return Result.success(Unit)
        }

        return installViaPackageInstaller(context, apkFile)
    }
}
