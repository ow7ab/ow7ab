package com.example.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * بيانات إصدار التحديث السحابي الموثّق (تشمل بصمة البناء Build ID وGit Commit وSHA-256)
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val isMandatory: Boolean = false,
    val sha256Checksum: String = "",
    val minSupportedVersionCode: Int = 1,
    val fileSizeMb: Double = 18.5,
    val fileSize: Long = 0L,
    val buildId: String = "",
    val gitCommit: String = "",
    val publishedAt: String = "",
    val signingCertSha256: String = ""
)

/**
 * تقرير تشخيص هوية البناء الفعلي المثبت على الجهاز (Build Identity Diagnostics)
 */
data class BuildIdentityReport(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val buildId: String,
    val gitCommitSha: String,
    val buildTimestampUtc: String,
    val installedApkSha256: String,
    val signingCertSha256: String,
    val updateSourceUrl: String,
    val expectedTargetVersionCode: Int,
    val expectedTargetBuildId: String,
    val isIdentityVerified: Boolean,
    val statusSummary: String
)

/**
 * نتيجة التحقق من انتقال هوية البناء بين إصدارين (Version A -> Version B)
 */
data class BuildTransitionVerification(
    val isVerified: Boolean,
    val versionCodeAdvanced: Boolean,
    val buildIdChanged: Boolean,
    val matchesExpectedBuildId: Boolean,
    val diagnosticMessage: String
)

/**
 * نتيجة التحقق من صلاحية بيانات التحديث قبل نشره أو تنزيله
 */
data class UpdateValidationDecision(
    val isValid: Boolean,
    val reason: String
)

/**
 * حالات تحميل وتجهيز التحديث لعرض شريط التقدم الحقيقي للمستخدم
 */
sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(
        val progressPercent: Int,
        val downloadedMb: Double,
        val totalMb: Double,
        val attempt: Int = 1
    ) : DownloadState()
    object VerifyingChecksum : DownloadState()
    data class ReadyToInstall(val apkFile: File, val updateInfo: UpdateInfo) : DownloadState()
    data class Failed(val errorMessage: String, val canRetry: Boolean = true) : DownloadState()
}

/**
 * نتيجة التحقق من اكتمال تثبيت الحزمة الفعلي عبر Android PackageManager
 */
sealed class PendingInstallVerification {
    object None : PendingInstallVerification()
    data class VerifiedInstalled(
        val versionCode: Int,
        val versionName: String,
        val buildId: String
    ) : PendingInstallVerification()
    data class NotYetInstalled(
        val expectedCode: Int,
        val currentInstalledCode: Int,
        val diagnosticError: String = ""
    ) : PendingInstallVerification()
}

class UpdateManager(private val context: Context) {

    private val appContext = context.applicationContext

    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        // إزالة أي قيم وهمية سابقة في SharedPreferences كانت تزيّف رقم الإصدار دون تثبيت APK حقيقي
        purgeLegacySpoofedVersionPrefs()
    }

    private val _effectiveVersionCode = MutableStateFlow(getInstalledVersionCode())
    val effectiveVersionCode: StateFlow<Int> = _effectiveVersionCode.asStateFlow()

    private val _effectiveVersionName = MutableStateFlow(getInstalledVersionName())
    val effectiveVersionName: StateFlow<String> = _effectiveVersionName.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _installStatusMessage = MutableStateFlow<Pair<String, Boolean>?>(null)
    val installStatusMessage: StateFlow<Pair<String, Boolean>?> = _installStatusMessage.asStateFlow()

    private var pendingInstallUpdateInfo: UpdateInfo? = null
    private var realtimeUpdateRegistrations: MutableList<ListenerRegistration> = mutableListOf()
    @Volatile
    private var adminBroadcastGuardEnabled: Boolean = false

    fun setAdminBroadcastGuardEnabled(enabled: Boolean) {
        adminBroadcastGuardEnabled = enabled
    }

    private fun purgeLegacySpoofedVersionPrefs() {
        runCatching {
            if (prefs.contains(LEGACY_KEY_INSTALLED_CLOUD_CODE) || prefs.contains(LEGACY_KEY_INSTALLED_CLOUD_NAME)) {
                prefs.edit()
                    .remove(LEGACY_KEY_INSTALLED_CLOUD_CODE)
                    .remove(LEGACY_KEY_INSTALLED_CLOUD_NAME)
                    .apply()
            }
        }
    }

    /**
     * قراءة رقم الإصدار الحقيقي المثبت فعلياً في النظام عبر PackageManager فقط
     * (لا يتم أبداً قراءة رقم إصدار مزيف من SharedPreferences)
     */
    fun getInstalledVersionCode(): Int {
        return try {
            val pInfo = getInstalledPackageInfo(0)
            val pkgCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
            if (pkgCode > 0) pkgCode else BuildConfig.VERSION_CODE
        } catch (e: Exception) {
            BuildConfig.VERSION_CODE
        }
    }

    /**
     * قراءة اسم الإصدار الحقيقي المثبت فعلياً في النظام عبر PackageManager فقط
     */
    fun getInstalledVersionName(): String {
        return try {
            val pInfo = getInstalledPackageInfo(0)
            pInfo.versionName?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            BuildConfig.VERSION_NAME
        }
    }

    /**
     * يعيد دائماً رقم الإصدار الفعلي المثبت من PackageManager دون أي تزييف محلي
     */
    fun getEffectiveVersionCode(): Int = getInstalledVersionCode()

    /**
     * يعيد دائماً اسم الإصدار الفعلي المثبت من PackageManager دون أي تزييف محلي
     */
    fun getEffectiveVersionName(): String = getInstalledVersionName()

    /**
     * قراءة بصمة البناء المدمجة في الكود المترجم (BuildConfig.BUILD_ID)
     */
    fun getInstalledBuildId(): String = BuildConfig.BUILD_ID

    /**
     * قراءة بصمة Git Commit المدمجة في الكود المترجم (BuildConfig.GIT_COMMIT_SHA)
     */
    fun getInstalledGitCommitSha(): String = BuildConfig.GIT_COMMIT_SHA

    /**
     * قراءة تاريخ بناء الحزمة المدمج في الكود المترجم (BuildConfig.BUILD_TIMESTAMP_UTC)
     */
    fun getInstalledBuildTimestampUtc(): String = BuildConfig.BUILD_TIMESTAMP_UTC

    /**
     * تحديث التدفقات الحية لرقم واسم الإصدار الفعلي المثبت من PackageManager
     */
    fun refreshInstalledVersionState() {
        purgeLegacySpoofedVersionPrefs()
        _effectiveVersionCode.value = getInstalledVersionCode()
        _effectiveVersionName.value = getInstalledVersionName()
    }

    /**
     * استخراج بصمة شهادة التوقيع (SHA-256) للحزمة المثبتة حالياً
     */
    fun getInstalledSigningCertSha256(): String {
        return runCatching {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val pInfo = getInstalledPackageInfo(flags)
            extractSigningCertSha256FromPackageInfo(pInfo)
        }.getOrDefault("")
    }

    /**
     * حساب SHA-256 لملف APK المثبت حالياً على الجهاز
     */
    fun getInstalledBaseApkSha256(): String {
        return runCatching {
            val sourceApk = File(appContext.applicationInfo.sourceDir)
            if (sourceApk.exists() && sourceApk.isFile) {
                computeFileSha256(sourceApk)
            } else {
                ""
            }
        }.getOrDefault("")
    }

    /**
     * إنشاء تقرير تشخيصي شامل لهوية البناء الفعلي المثبت على الجهاز (لوحة تشخيص المدير)
     */
    fun getInstalledBuildIdentityReport(
        updateSourceUrl: String = DEFAULT_UPDATE_METADATA_URL,
        latestRemoteUpdate: UpdateInfo? = null
    ): BuildIdentityReport {
        val installedCode = getInstalledVersionCode()
        val installedName = getInstalledVersionName()
        val installedBuildId = getInstalledBuildId()
        val installedGitCommit = getInstalledGitCommitSha()
        val installedTimestamp = getInstalledBuildTimestampUtc()
        val installedApkHash = getInstalledBaseApkSha256()
        val certHash = getInstalledSigningCertSha256()

        val expectedCode = prefs.getInt(KEY_EXPECTED_UPDATE_CODE, -1)
        val expectedBuildId = prefs.getString(KEY_EXPECTED_BUILD_ID, "")?.trim().orEmpty()

        val remoteMatchesInstalled = latestRemoteUpdate != null &&
            latestRemoteUpdate.versionCode == installedCode &&
            latestRemoteUpdate.buildId.isNotBlank() &&
            latestRemoteUpdate.buildId != installedBuildId

        val pendingMismatch = expectedCode > 0 &&
            installedCode >= expectedCode &&
            expectedBuildId.isNotBlank() &&
            expectedBuildId != installedBuildId

        val isVerified = !remoteMatchesInstalled && !pendingMismatch && installedBuildId.isNotBlank()
        val statusSummary = when {
            pendingMismatch ->
                "تحذير أمني: رقم الإصدار ($installedCode) تغيّر لكن معرّف البناء الفعلي ($installedBuildId) لا يطابق الحزمة المستهدفة ($expectedBuildId)!"
            remoteMatchesInstalled ->
                "تحذير: السحابة تشير لنفس رقم الإصدار ($installedCode) لكن بمعرّف بناء مختلف (${latestRemoteUpdate?.buildId}) عن المثبت ($installedBuildId)."
            expectedCode > installedCode ->
                "يوجد تحديث معلّق (Build $expectedCode) لم يكتمل تثبيته من نظام أندرويد بعد (المثبت حالياً: Build $installedCode)."
            latestRemoteUpdate != null && latestRemoteUpdate.versionCode > installedCode ->
                "تتوفر نسخة أحدث على السحابة: v${latestRemoteUpdate.versionName} (Build ${latestRemoteUpdate.versionCode} • ${latestRemoteUpdate.buildId.ifBlank { "CI" }})"
            else ->
                "تم التحقق بنجاح: الحزمة المثبتة أصلية وموقّعة وتطابق بصمة البناء ($installedBuildId)."
        }

        return BuildIdentityReport(
            packageName = appContext.packageName,
            versionCode = installedCode,
            versionName = installedName,
            buildId = installedBuildId,
            gitCommitSha = installedGitCommit,
            buildTimestampUtc = installedTimestamp,
            installedApkSha256 = installedApkHash,
            signingCertSha256 = certHash,
            updateSourceUrl = updateSourceUrl,
            expectedTargetVersionCode = if (expectedCode > 0) expectedCode else (latestRemoteUpdate?.versionCode ?: installedCode),
            expectedTargetBuildId = expectedBuildId.ifBlank { latestRemoteUpdate?.buildId.orEmpty() },
            isIdentityVerified = isVerified,
            statusSummary = statusSummary
        )
    }

    /**
     * التحقق بعد العودة للتطبيق: هل قام نظام Android فعلياً بتثبيت الحزمة الجديدة وتحديث Build ID؟
     */
    fun verifyPendingInstallation(): PendingInstallVerification {
        refreshInstalledVersionState()
        val expectedCode = prefs.getInt(KEY_EXPECTED_UPDATE_CODE, -1)
        val expectedName = prefs.getString(KEY_EXPECTED_UPDATE_NAME, null)
        val expectedBuildId = prefs.getString(KEY_EXPECTED_BUILD_ID, null)?.trim().orEmpty()
        if (expectedCode <= 0) return PendingInstallVerification.None

        val actualInstalledCode = getInstalledVersionCode()
        val actualInstalledName = getInstalledVersionName()
        val actualBuildId = getInstalledBuildId()

        return if (actualInstalledCode >= expectedCode) {
            if (expectedBuildId.isNotBlank() && actualBuildId != expectedBuildId) {
                val warnMsg = "تحذير: رقم الإصدار ($actualInstalledCode) يطابق التحديث لكن معرّف البناء المثبت ($actualBuildId) يختلف عن المتوقع ($expectedBuildId)."
                Log.w(TAG, warnMsg)
                clearPendingExpectedUpdate()
                PendingInstallVerification.NotYetInstalled(
                    expectedCode = expectedCode,
                    currentInstalledCode = actualInstalledCode,
                    diagnosticError = warnMsg
                )
            } else {
                clearPendingExpectedUpdate()
                cleanOldUpdateArchives(keepVersionCode = -1)
                PendingInstallVerification.VerifiedInstalled(
                    versionCode = actualInstalledCode,
                    versionName = expectedName ?: actualInstalledName,
                    buildId = actualBuildId
                )
            }
        } else {
            PendingInstallVerification.NotYetInstalled(
                expectedCode = expectedCode,
                currentInstalledCode = actualInstalledCode,
                diagnosticError = "لم يكتمل تثبيت الحزمة الجديدة بعد؛ التطبيق ما زال يعمل بالإصدار القديم (Build $actualInstalledCode بدلاً من Build $expectedCode)."
            )
        }
    }

    private fun recordPendingExpectedUpdate(info: UpdateInfo) {
        prefs.edit()
            .putInt(KEY_EXPECTED_UPDATE_CODE, info.versionCode)
            .putString(KEY_EXPECTED_UPDATE_NAME, info.versionName)
            .putString(KEY_EXPECTED_BUILD_ID, info.buildId)
            .putString(KEY_EXPECTED_GIT_COMMIT, info.gitCommit)
            .putLong(KEY_EXPECTED_UPDATE_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun clearPendingExpectedUpdate() {
        prefs.edit()
            .remove(KEY_EXPECTED_UPDATE_CODE)
            .remove(KEY_EXPECTED_UPDATE_NAME)
            .remove(KEY_EXPECTED_BUILD_ID)
            .remove(KEY_EXPECTED_GIT_COMMIT)
            .remove(KEY_EXPECTED_UPDATE_TIMESTAMP)
            .apply()
    }

    fun clearInstallStatusMessage() {
        _installStatusMessage.value = null
    }

    internal fun onPackageInstallerStatus(status: Int, rawMessage: String) {
        val info = pendingInstallUpdateInfo
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                _installStatusMessage.value =
                    "يرجى الموافقة على نافذة تثبيت أندرويد لإتمام ترقية الحزمة الفعلية..." to true
            }
            PackageInstaller.STATUS_SUCCESS -> {
                refreshInstalledVersionState()
                val actualCode = getInstalledVersionCode()
                val actualName = getInstalledVersionName()
                val actualBuildId = getInstalledBuildId()
                val expectedCode = info?.versionCode ?: prefs.getInt(KEY_EXPECTED_UPDATE_CODE, actualCode)
                if (actualCode >= expectedCode) {
                    clearPendingExpectedUpdate()
                    _downloadState.value = DownloadState.Idle
                    _installStatusMessage.value =
                        "تم تثبيت التحديث الفعلي بنجاح! الإصدار الحالي: v$actualName (Build $actualCode • $actualBuildId)" to true
                } else {
                    _installStatusMessage.value =
                        "اكتملت جلسة المثبت؛ أعد فتح التطبيق للتحقق من تفعيل الحزمة الجديدة (Build $expectedCode)." to true
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // لا يتم أبداً تزييف التثبيت عند إلغاء المستخدم!
                _installStatusMessage.value =
                    "تم إلغاء عملية تثبيت التحديث من قِبل المستخدم. التطبيق ما زال على الإصدار v${getInstalledVersionName()} (Build ${getInstalledVersionCode()})." to false
            }
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                _installStatusMessage.value =
                    "تعذر التثبيت (INSTALL_FAILED_UPDATE_INCOMPATIBLE): شهادة توقيع ملف APK الجديد لا تطابق النسخة المثبتة حالياً." to false
            }
            PackageInstaller.STATUS_FAILURE_INVALID -> {
                _installStatusMessage.value =
                    "تعذر التثبيت (INSTALL_FAILED_INVALID_APK): ملف APK تالف أو غير صالح." to false
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                _installStatusMessage.value =
                    "تعذر التثبيت: المساحة التخزينية في الجهاز غير كافية." to false
            }
            else -> {
                val detail = rawMessage.ifBlank { "رمز الحالة: $status" }
                _installStatusMessage.value =
                    "فشل تثبيت الحزمة الجديدة ($detail). لم يتم تغيير إصدار التطبيق." to false
            }
        }
    }

    /**
     * مراقبة فورية (Real-time SnapshotListener) لمسارات التحديث السحابية
     */
    fun observeCloudUpdatesRealtime(
        clusterCode: String = "PMP-MAIN-01",
        onUpdateDetected: (UpdateInfo) -> Unit
    ) {
        stopRealtimeUpdateObserver()
        runCatching {
            val firestore = FirebaseFirestore.getInstance()
            val normalizedCluster = clusterCode.trim().uppercase().ifBlank { "PMP-MAIN-01" }

            val handleSnapshot: (com.google.firebase.firestore.DocumentSnapshot?) -> Unit = { snapshot ->
                if (snapshot != null && snapshot.exists() && !snapshot.metadata.isFromCache) {
                    val info = parseFirestoreSnapshotToUpdateInfo(snapshot)
                    val installedCode = getInstalledVersionCode()
                    if (info != null && info.versionCode > installedCode) {
                        Log.i(
                            TAG,
                            "Realtime update detected: v${info.versionName} (code=${info.versionCode}, buildId=${info.buildId}), installed=$installedCode"
                        )
                        onUpdateDetected(info)
                    }
                }
            }

            val globalReg = firestore.collection("system_config")
                .document("app_version")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    handleSnapshot(snapshot)
                }
            realtimeUpdateRegistrations.add(globalReg)

            val clusterReg = firestore.collection("erp_clusters")
                .document(normalizedCluster)
                .collection("config")
                .document("app_version")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    handleSnapshot(snapshot)
                }
            realtimeUpdateRegistrations.add(clusterReg)

            val broadcastReg = firestore.collection("app_updates")
                .document("latest")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    handleSnapshot(snapshot)
                }
            realtimeUpdateRegistrations.add(broadcastReg)
        }.onFailure { err ->
            Log.w(TAG, "Unable to attach realtime update listener: ${err.message}")
        }
    }

    fun stopRealtimeUpdateObserver() {
        realtimeUpdateRegistrations.forEach { reg ->
            runCatching { reg.remove() }
        }
        realtimeUpdateRegistrations.clear()
    }

    /**
     * التحقق الفوري الصارم من وجود تحديث جديد (Server-First + Cache-Busted HTTP JSON)
     */
    suspend fun checkForUpdates(
        metadataUrl: String = DEFAULT_UPDATE_METADATA_URL,
        clusterCode: String = "PMP-MAIN-01"
    ): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            refreshInstalledVersionState()
            val currentVersionCode = getInstalledVersionCode()
            val discoveredUpdates = mutableListOf<UpdateInfo>()

            // 1. فحص ملفات version.json المباشرة عبر HTTPS مع كسر الكاش (Cache-Busting)
            // نعطي الأولوية لملف version.json المنشور من GitHub Actions لأنه يحتوي على بصمة SHA-256 وBuild ID الحقيقية
            val candidateJsonUrls = buildList {
                if (metadataUrl.isNotBlank()) add(metadataUrl.trim())
                add(DEFAULT_UPDATE_METADATA_URL)
                add(GITHUB_PAGES_METADATA_URL)
                add(GITHUB_RAW_METADATA_URL)
                add(SECONDARY_UPDATE_METADATA_URL)
            }.distinct()

            for (url in candidateJsonUrls) {
                runCatching {
                    val info = fetchFreshUpdateInfoFromJsonUrl(url)
                    if (info != null) {
                        discoveredUpdates.add(info)
                    }
                }
            }

            // 2. فحص GitHub Releases API للمستودع الرسمي ow7ab/ow7ab والمستودع المساند
            for (apiUrl in listOf(GITHUB_RELEASES_API_URL, SECONDARY_GITHUB_RELEASES_API_URL)) {
                runCatching {
                    val releaseInfo = fetchLatestGitHubReleaseUpdateInfo(apiUrl)
                    if (releaseInfo != null) {
                        discoveredUpdates.add(releaseInfo)
                    }
                }
            }

            // 3. فحص مستندات Firestore السحابية مباشرة من السيرفر (Source.SERVER)
            runCatching {
                val firestore = FirebaseFirestore.getInstance()
                val normalizedCluster = clusterCode.trim().uppercase().ifBlank { "PMP-MAIN-01" }

                val globalServerDoc = withTimeoutOrNull(7_500L) {
                    firestore.collection("system_config")
                        .document("app_version")
                        .get(Source.SERVER)
                        .await()
                }
                parseFirestoreSnapshotToUpdateInfo(globalServerDoc)?.let { discoveredUpdates.add(it) }

                val latestServerDoc = withTimeoutOrNull(7_500L) {
                    firestore.collection("app_updates")
                        .document("latest")
                        .get(Source.SERVER)
                        .await()
                }
                parseFirestoreSnapshotToUpdateInfo(latestServerDoc)?.let { discoveredUpdates.add(it) }

                val clusterServerDoc = withTimeoutOrNull(7_500L) {
                    firestore.collection("erp_clusters")
                        .document(normalizedCluster)
                        .collection("config")
                        .document("app_version")
                        .get(Source.SERVER)
                        .await()
                }
                parseFirestoreSnapshotToUpdateInfo(clusterServerDoc)?.let { discoveredUpdates.add(it) }
            }.onFailure { e ->
                Log.w(TAG, "Direct Source.SERVER Firestore check failed: ${e.message}")
            }

            if (discoveredUpdates.isEmpty()) {
                return@withContext Result.failure(
                    Exception("تعذر الوصول إلى خادم التحديثات السحابي. يرجى التحقق من اتصال الإنترنت والمحاولة مرة أخرى.")
                )
            }

            // اختيار النسخة الأحدث مع تفضيل النسخة التي تملك buildId وsha256 موثقة عند تساوي versionCode
            val highestUpdate = discoveredUpdates.maxWithOrNull(
                compareBy<UpdateInfo> { it.versionCode }
                    .thenBy { it.sha256Checksum.isNotBlank() }
                    .thenBy { it.buildId.isNotBlank() }
            )!!

            Log.i(
                TAG,
                "Update check complete: currentInstalled=$currentVersionCode (${getInstalledBuildId()}), highestRemote=${highestUpdate.versionCode} (${highestUpdate.versionName}, buildId=${highestUpdate.buildId})"
            )

            return@withContext if (highestUpdate.versionCode > currentVersionCode) {
                Result.success(highestUpdate)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}", e)
            Result.failure(Exception("تعذر التحقق من التحديثات: ${e.localizedMessage ?: "خطأ في الشبكة"}"))
        }
    }

    /**
     * التحقق الصارم قبل النشر: يتأكد أن ملف APK الحقيقي موجود فعلياً على خادم الإصدارات (HTTP 200/206 + ترويسة ZIP)
     * قبل السماح بنشر رقم الإصدار إلى Firestore لمنع نشر أرقام إصدارات وهمية بدون ملف APK مبني!
     */
    suspend fun verifyRemoteReleaseBeforePublish(
        candidateInfo: UpdateInfo,
        metadataUrl: String = DEFAULT_UPDATE_METADATA_URL
    ): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            // 1. محاولة جلب أحدث version.json منشور فعلياً من GitHub Actions / GitHub Pages
            val remoteJsonCandidates = buildList {
                if (metadataUrl.isNotBlank()) add(metadataUrl.trim())
                add(DEFAULT_UPDATE_METADATA_URL)
                add(GITHUB_PAGES_METADATA_URL)
                add(GITHUB_RAW_METADATA_URL)
                add(SECONDARY_UPDATE_METADATA_URL)
            }.distinct()

            var verifiedRemoteJsonInfo: UpdateInfo? = null
            for (url in remoteJsonCandidates) {
                val fetched = runCatching { fetchFreshUpdateInfoFromJsonUrl(url) }.getOrNull()
                if (fetched != null && fetched.versionCode >= candidateInfo.versionCode) {
                    verifiedRemoteJsonInfo = fetched
                    break
                }
            }

            // 2. فحص GitHub Releases API أيضاً
            if (verifiedRemoteJsonInfo == null) {
                for (apiUrl in listOf(GITHUB_RELEASES_API_URL, SECONDARY_GITHUB_RELEASES_API_URL)) {
                    val releaseInfo = runCatching { fetchLatestGitHubReleaseUpdateInfo(apiUrl) }.getOrNull()
                    if (releaseInfo != null && releaseInfo.versionCode >= candidateInfo.versionCode) {
                        verifiedRemoteJsonInfo = releaseInfo
                        break
                    }
                }
            }

            val resolvedCandidate = verifiedRemoteJsonInfo ?: candidateInfo
            val installedCode = getInstalledVersionCode()

            if (resolvedCandidate.versionCode <= 0 || resolvedCandidate.versionName.isBlank()) {
                return@withContext Result.failure(
                    Exception("بيانات الإصدار غير صالحة: يجب تحديد versionCode أكبر من الصفر وversionName واضح.")
                )
            }

            // 3. التحقق الفعلي عبر الشبكة من أن رابط APK يرجع حزمة ثنائية حقيقية (ZIP header PK\x03\x04) وليس 404
            val candidateUrls = buildCandidateDownloadUrls(resolvedCandidate)
            var verifiedWorkingUrl: String? = null
            var verifiedContentLength: Long = resolvedCandidate.fileSize
            val probeStatuses = mutableListOf<String>()

            for (apkUrl in candidateUrls) {
                val probe = probeRemoteApkBinaryUrl(apkUrl)
                if (probe.isReachableValidApk) {
                    verifiedWorkingUrl = apkUrl
                    if (probe.contentLengthBytes > 0L) {
                        verifiedContentLength = probe.contentLengthBytes
                    }
                    break
                } else {
                    probeStatuses.add("${apkUrl.substringAfterLast('/')} (${probe.reason})")
                }
            }

            if (verifiedWorkingUrl == null) {
                val details = probeStatuses.take(3).joinToString(" | ")
                return@withContext Result.failure(
                    Exception(
                        "تم إيقاف النشر لحماية المستخدمين: ملف APK للإصدار v${resolvedCandidate.versionName} (Build ${resolvedCandidate.versionCode}) غير موجود فعلياً على خادم الإصدارات ($details). " +
                            "يرجى أولاً تشغيل GitHub Actions لبناء ورفع الحزمة الجديدة، ثم الضغط على «نشر التحديث»."
                    )
                )
            }

            val finalBuildId = resolvedCandidate.buildId.ifBlank {
                "PMP-v${resolvedCandidate.versionName}-b${resolvedCandidate.versionCode}-${BuildConfig.GIT_COMMIT_SHA}"
            }
            val finalGitCommit = resolvedCandidate.gitCommit.ifBlank { BuildConfig.GIT_COMMIT_SHA }
            val finalPublishedAt = resolvedCandidate.publishedAt.ifBlank { formatUtcTimestamp(System.currentTimeMillis()) }
            val finalSizeMb = if (verifiedContentLength > 0L) {
                Math.round((verifiedContentLength.toDouble() / (1024.0 * 1024.0)) * 100.0) / 100.0
            } else {
                resolvedCandidate.fileSizeMb
            }

            val verifiedInfo = resolvedCandidate.copy(
                downloadUrl = verifiedWorkingUrl,
                fileSize = verifiedContentLength,
                fileSizeMb = finalSizeMb,
                buildId = finalBuildId,
                gitCommit = finalGitCommit,
                publishedAt = finalPublishedAt,
                signingCertSha256 = resolvedCandidate.signingCertSha256.ifBlank { getInstalledSigningCertSha256() }
            )

            Log.i(
                TAG,
                "Remote release verified before publish: v${verifiedInfo.versionName} (code=${verifiedInfo.versionCode}, buildId=${verifiedInfo.buildId}, url=$verifiedWorkingUrl, installed=$installedCode)"
            )
            Result.success(verifiedInfo)
        } catch (e: Exception) {
            Result.failure(Exception("فشل التحقق من حزمة الإصدار قبل النشر: ${e.localizedMessage}"))
        }
    }

    private data class RemoteApkProbeResult(
        val isReachableValidApk: Boolean,
        val contentLengthBytes: Long,
        val reason: String
    )

    private fun probeRemoteApkBinaryUrl(apkUrl: String): RemoteApkProbeResult {
        if (!apkUrl.startsWith("https://", ignoreCase = true)) {
            return RemoteApkProbeResult(false, 0L, "الرابط ليس HTTPS")
        }
        var conn: HttpURLConnection? = null
        return try {
            val bustedUrl = buildCacheBustedUrl(apkUrl)
            conn = (URL(bustedUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("Range", "bytes=0-3")
                setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                setRequestProperty("User-Agent", "PMP-Paints-ReleaseVerifier/${BuildConfig.VERSION_NAME}")
            }
            conn.connect()
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                return RemoteApkProbeResult(false, 0L, "HTTP $code")
            }
            val contentType = (conn.contentType ?: "").lowercase()
            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                return RemoteApkProbeResult(false, 0L, "Content-Type=$contentType")
            }
            val header = ByteArray(4)
            val read = conn.inputStream.use { input -> input.read(header) }
            val isZip = read == 4 &&
                header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
            if (!isZip) {
                return RemoteApkProbeResult(false, 0L, "ترويسة الملف ليست APK/ZIP صالحة")
            }
            val totalLength = parseTotalLengthFromHeaders(conn)
            RemoteApkProbeResult(true, totalLength, "OK")
        } catch (e: Exception) {
            RemoteApkProbeResult(false, 0L, e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseTotalLengthFromHeaders(conn: HttpURLConnection): Long {
        val contentRange = conn.getHeaderField("Content-Range")
        if (!contentRange.isNullOrBlank() && contentRange.contains("/")) {
            val totalPart = contentRange.substringAfterLast("/").trim()
            totalPart.toLongOrNull()?.let { if (it > 0L) return it }
        }
        val len = conn.contentLengthLong
        return if (len > 4L) len else 0L
    }

    private fun fetchFreshUpdateInfoFromJsonUrl(baseUrl: String): UpdateInfo? {
        val bustedUrl = buildCacheBustedUrl(baseUrl)
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(bustedUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                useCaches = false
                defaultUseCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("Expires", "0")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "PMP-Paints-Updater/${BuildConfig.VERSION_NAME} (Android)")
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parseVersionJsonToUpdateInfo(response)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchLatestGitHubReleaseUpdateInfo(apiUrl: String): UpdateInfo? {
        val bustedUrl = buildCacheBustedUrl(apiUrl)
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(bustedUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                useCaches = false
                defaultUseCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "PMP-Paints-Updater/${BuildConfig.VERSION_NAME} (Android)")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(raw)

            val assets = json.optJSONArray("assets")
            var apkDownloadUrl = ""
            var apkSizeBytes = 0L
            var apkSizeMb = 18.5
            var versionJsonAssetUrl = ""

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    val browserUrl = asset.optString("browser_download_url", "")
                    val sizeBytes = asset.optLong("size", 0L)
                    if (name.equals("version.json", ignoreCase = true) && browserUrl.isNotBlank()) {
                        versionJsonAssetUrl = browserUrl
                    } else if (name.endsWith(".apk", ignoreCase = true) && browserUrl.isNotBlank()) {
                        if (apkDownloadUrl.isBlank() || !name.contains("latest", ignoreCase = true)) {
                            apkDownloadUrl = browserUrl
                            if (sizeBytes > 0L) {
                                apkSizeBytes = sizeBytes
                                apkSizeMb = Math.round((sizeBytes.toDouble() / (1024.0 * 1024.0)) * 100.0) / 100.0
                            }
                        }
                    }
                }
            }

            if (versionJsonAssetUrl.isNotBlank()) {
                fetchFreshUpdateInfoFromJsonUrl(versionJsonAssetUrl)?.let { infoFromAsset ->
                    return if (apkDownloadUrl.isNotBlank() && infoFromAsset.downloadUrl.isBlank()) {
                        infoFromAsset.copy(
                            downloadUrl = apkDownloadUrl,
                            fileSize = if (infoFromAsset.fileSize > 0L) infoFromAsset.fileSize else apkSizeBytes,
                            fileSizeMb = if (infoFromAsset.fileSizeMb > 0.0) infoFromAsset.fileSizeMb else apkSizeMb
                        )
                    } else {
                        infoFromAsset
                    }
                }
            }

            val tagName = json.optString("tag_name", "").removePrefix("v").removePrefix("V").trim()
            val releaseName = json.optString("name", "")
            val body = json.optString("body", "").ifBlank { "تحديث سحابي معتمد لتطبيق PMP PAINTS" }
            val publishedAt = json.optString("published_at", "")
            if (apkDownloadUrl.isBlank()) return null

            val buildNumberRegex = Regex("""(?:Build|b|#)\s*(\d+)""", RegexOption.IGNORE_CASE)
            val parsedCode = buildNumberRegex.find(releaseName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: buildNumberRegex.find(apkDownloadUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return null

            val buildIdRegex = Regex("""(PMP-v[^\s`)]+)""")
            val parsedBuildId = buildIdRegex.find(body)?.groupValues?.getOrNull(1).orEmpty()
            val sha256Regex = Regex("""\b([a-fA-F0-9]{64})\b""")
            val parsedSha256 = sha256Regex.find(body)?.groupValues?.getOrNull(1).orEmpty()

            UpdateInfo(
                versionCode = parsedCode,
                versionName = tagName.ifBlank { "3.$parsedCode-PRO" },
                downloadUrl = apkDownloadUrl,
                releaseNotes = body,
                isMandatory = false,
                sha256Checksum = parsedSha256,
                minSupportedVersionCode = 1,
                fileSizeMb = apkSizeMb,
                fileSize = apkSizeBytes,
                buildId = parsedBuildId,
                publishedAt = publishedAt
            )
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseFirestoreSnapshotToUpdateInfo(
        doc: com.google.firebase.firestore.DocumentSnapshot?
    ): UpdateInfo? {
        if (doc == null || !doc.exists()) return null
        val remoteCode = (doc.getLong("versionCode") ?: doc.getLong("version_code") ?: 0L).toInt()
        if (remoteCode <= 0) return null
        val remoteName = doc.getString("versionName")
            ?: doc.getString("version_name")
            ?: "$remoteCode.0"
        val rawDownloadUrl = (doc.getString("downloadUrl")
            ?: doc.getString("apkUrl")
            ?: doc.getString("download_url")
            ?: "").trim()
        val downloadUrl = normalizeDownloadUrl(rawDownloadUrl, remoteName, remoteCode)
        val notes = doc.getString("releaseNotes")
            ?: doc.getString("release_notes")
            ?: "تحديث سحابي يتضمن تحسينات الأداء والأمان والمزامنة."
        val mandatory = doc.getBoolean("isMandatory")
            ?: doc.getBoolean("mandatory")
            ?: false
        val sha256 = (doc.getString("sha256")
            ?: doc.getString("sha256Checksum")
            ?: "").trim()
        val minSupported = (doc.getLong("minSupportedVersion")
            ?: doc.getLong("minSupportedVersionCode")
            ?: 1L).toInt()
        val sizeBytes = doc.getLong("fileSize") ?: 0L
        val sizeMb = doc.getDouble("fileSizeMb")
            ?: if (sizeBytes > 0L) Math.round((sizeBytes.toDouble() / (1024.0 * 1024.0)) * 100.0) / 100.0 else 18.5
        val buildId = (doc.getString("buildId") ?: doc.getString("build_id") ?: "").trim()
        val gitCommit = (doc.getString("gitCommit") ?: doc.getString("git_commit") ?: "").trim()
        val publishedAt = (doc.getString("publishedAt") ?: doc.getString("published_at") ?: "").trim()
        val signingCertSha256 = (doc.getString("signingCertSha256") ?: "").trim()

        return UpdateInfo(
            versionCode = remoteCode,
            versionName = remoteName,
            downloadUrl = downloadUrl,
            releaseNotes = notes,
            isMandatory = mandatory,
            sha256Checksum = sha256,
            minSupportedVersionCode = minSupported,
            fileSizeMb = sizeMb,
            fileSize = sizeBytes,
            buildId = buildId,
            gitCommit = gitCommit,
            publishedAt = publishedAt,
            signingCertSha256 = signingCertSha256
        )
    }

    /**
     * نشر بيانات إصدار تم التحقق من وجود حزمته الثنائية (APK) فعلياً إلى مسارات Firestore
     */
    suspend fun publishVersionToFirestore(
        info: UpdateInfo,
        clusterCode: String = "PMP-MAIN-01",
        requireRemoteBinaryVerification: Boolean = true
    ): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        if (!adminBroadcastGuardEnabled) {
            return@withContext Result.failure(
                SecurityException("رفض أمني: نشر التحديثات السحابية مسموح لحساب المدير العام (admin) فقط.")
            )
        }
        try {
            val verifiedInfo = if (requireRemoteBinaryVerification) {
                val verifyRes = verifyRemoteReleaseBeforePublish(info)
                if (verifyRes.isFailure) {
                    return@withContext Result.failure(
                        verifyRes.exceptionOrNull() ?: Exception("فشل التحقق من وجود ملف APK على الخادم.")
                    )
                }
                verifyRes.getOrThrow()
            } else {
                info
            }

            val firestore = FirebaseFirestore.getInstance()
            val normalizedCluster = clusterCode.trim().uppercase().ifBlank { "PMP-MAIN-01" }
            val resolvedUrl = normalizeDownloadUrl(
                verifiedInfo.downloadUrl,
                verifiedInfo.versionName,
                verifiedInfo.versionCode
            )
            val publishedAtUtc = verifiedInfo.publishedAt.ifBlank { formatUtcTimestamp(System.currentTimeMillis()) }
            val buildId = verifiedInfo.buildId.ifBlank {
                "PMP-v${verifiedInfo.versionName}-b${verifiedInfo.versionCode}-${BuildConfig.GIT_COMMIT_SHA}"
            }
            val gitCommit = verifiedInfo.gitCommit.ifBlank { BuildConfig.GIT_COMMIT_SHA }

            val payload = hashMapOf(
                "versionCode" to verifiedInfo.versionCode,
                "version_code" to verifiedInfo.versionCode,
                "versionName" to verifiedInfo.versionName,
                "version_name" to verifiedInfo.versionName,
                "downloadUrl" to resolvedUrl,
                "apkUrl" to resolvedUrl,
                "download_url" to resolvedUrl,
                "releaseNotes" to verifiedInfo.releaseNotes,
                "release_notes" to verifiedInfo.releaseNotes,
                "isMandatory" to verifiedInfo.isMandatory,
                "mandatory" to verifiedInfo.isMandatory,
                "sha256" to verifiedInfo.sha256Checksum,
                "sha256Checksum" to verifiedInfo.sha256Checksum,
                "fileSize" to verifiedInfo.fileSize,
                "fileSizeMb" to verifiedInfo.fileSizeMb,
                "buildId" to buildId,
                "gitCommit" to gitCommit,
                "publishedAt" to publishedAtUtc,
                "signingCertSha256" to verifiedInfo.signingCertSha256.ifBlank { getInstalledSigningCertSha256() },
                "minSupportedVersion" to verifiedInfo.minSupportedVersionCode,
                "minSupportedVersionCode" to verifiedInfo.minSupportedVersionCode,
                "updatedAt" to System.currentTimeMillis(),
                "publishedBy" to "admin_verified_release"
            )

            firestore.collection("system_config")
                .document("app_version")
                .set(payload, SetOptions.merge())
                .await()

            firestore.collection("app_updates")
                .document("latest")
                .set(payload, SetOptions.merge())
                .await()

            firestore.collection("erp_clusters")
                .document(normalizedCluster)
                .collection("config")
                .document("app_version")
                .set(payload, SetOptions.merge())
                .await()

            Result.success(
                verifiedInfo.copy(
                    downloadUrl = resolvedUrl,
                    buildId = buildId,
                    gitCommit = gitCommit,
                    publishedAt = publishedAtUtc
                )
            )
        } catch (e: Exception) {
            Result.failure(Exception("تعذر نشر التحديث في السحابة: ${e.localizedMessage}"))
        }
    }

    /**
     * بث التحديث الموثّق لجميع الكلاسترات والمستخدمين بعد التحقق الإلزامي من توفر ملف APK الفعلي على السيرفر.
     * لن يقوم هذا التابع أبداً بزيادة versionCode عشوائياً في الذاكرة دون وجود حزمة APK حقيقية مبنية!
     */
    suspend fun broadcastToAllClusters(
        info: UpdateInfo,
        primaryClusterCode: String = "PMP-MAIN-01",
        forceNewIncrement: Boolean = false
    ): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        if (!adminBroadcastGuardEnabled) {
            return@withContext Result.failure(
                SecurityException("رفض أمني: بث التحديثات السحابية مسموح لحساب المدير العام (admin) فقط.")
            )
        }
        try {
            // فحص الحزمة المبنية على خادم الإصدارات أولاً (لا يُسمح ببث رقم إصدار وهمي غير مبني)
            val verificationResult = verifyRemoteReleaseBeforePublish(info)
            if (verificationResult.isFailure) {
                return@withContext Result.failure(
                    verificationResult.exceptionOrNull()
                        ?: Exception("تعذر التحقق من وجود ملف APK المعتمد على خادم الإصدارات.")
                )
            }
            val verifiedRelease = verificationResult.getOrThrow()

            val firestore = FirebaseFirestore.getInstance()
            val resolvedUrl = normalizeDownloadUrl(
                verifiedRelease.downloadUrl,
                verifiedRelease.versionName,
                verifiedRelease.versionCode
            )

            val payload = hashMapOf(
                "versionCode" to verifiedRelease.versionCode,
                "version_code" to verifiedRelease.versionCode,
                "versionName" to verifiedRelease.versionName,
                "version_name" to verifiedRelease.versionName,
                "downloadUrl" to resolvedUrl,
                "apkUrl" to resolvedUrl,
                "download_url" to resolvedUrl,
                "releaseNotes" to verifiedRelease.releaseNotes,
                "release_notes" to verifiedRelease.releaseNotes,
                "isMandatory" to verifiedRelease.isMandatory,
                "mandatory" to verifiedRelease.isMandatory,
                "sha256" to verifiedRelease.sha256Checksum,
                "sha256Checksum" to verifiedRelease.sha256Checksum,
                "fileSize" to verifiedRelease.fileSize,
                "fileSizeMb" to verifiedRelease.fileSizeMb,
                "buildId" to verifiedRelease.buildId,
                "gitCommit" to verifiedRelease.gitCommit,
                "publishedAt" to verifiedRelease.publishedAt,
                "signingCertSha256" to verifiedRelease.signingCertSha256.ifBlank { getInstalledSigningCertSha256() },
                "minSupportedVersion" to verifiedRelease.minSupportedVersionCode,
                "minSupportedVersionCode" to verifiedRelease.minSupportedVersionCode,
                "updatedAt" to System.currentTimeMillis(),
                "broadcastMode" to "verified_apk_all_previous_versions"
            )

            // 1. المسار العام الموحد
            firestore.collection("system_config")
                .document("app_version")
                .set(payload, SetOptions.merge())
                .await()

            // 2. مسار البث الفوري
            firestore.collection("app_updates")
                .document("latest")
                .set(payload, SetOptions.merge())
                .await()

            // 3. جميع الكلاسترات المسجلة لضمان وصول الإشعار لكل الفروع والنسخ السابقة
            val targetClusters = mutableSetOf("PMP-MAIN-01", primaryClusterCode.trim().uppercase())
            runCatching {
                val clustersSnap = firestore.collection("erp_clusters").get().await()
                clustersSnap.documents.forEach { doc ->
                    if (doc.id.isNotBlank()) targetClusters.add(doc.id)
                }
            }

            targetClusters.filter { it.isNotBlank() }.forEach { clusterId ->
                runCatching {
                    firestore.collection("erp_clusters")
                        .document(clusterId)
                        .collection("config")
                        .document("app_version")
                        .set(payload, SetOptions.merge())
                        .await()
                }
            }

            Result.success(verifiedRelease.copy(downloadUrl = resolvedUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast verified update to all clusters", e)
            Result.failure(Exception("تعذر بث التحديث لجميع النسخ: ${e.localizedMessage}"))
        }
    }

    /**
     * تنزيل ملف APK الحقيقي من الخادم مع التحقق الصارم من:
     * 1. تنزيل حزمة ثنائية حقيقية عبر HTTPS (تم إزالة نسخ الحزمة المحلية sourceDir نهائياً).
     * 2. اسم ملف فريد لكل إصدار وبناء (pmp_update_v{versionCode}_{buildId}.apk) وحذف أي ملفات قديمة.
     * 3. التحقق من ترويسة ZIP وحجم الملف وبصمة SHA-256.
     * 4. التحقق عبر PackageManager.getPackageArchiveInfo من packageName == com.pmp وversionCode > المثبت وتطابق التوقيع.
     */
    suspend fun downloadApk(
        updateInfo: UpdateInfo,
        onProgress: (percent: Int, downloadedMb: Double, totalMb: Double) -> Unit = { _, _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        val installedCode = getInstalledVersionCode()
        if (updateInfo.versionCode <= installedCode) {
            val msg = "الإصدار المطلوب تنزيله (Build ${updateInfo.versionCode}) ليس أحدث من الإصدار المثبت حالياً (Build $installedCode)."
            _downloadState.value = DownloadState.Failed(msg, canRetry = false)
            return@withContext Result.failure(Exception(msg))
        }

        val updatesDir = File(appContext.cacheDir, "updates").apply {
            if (!exists()) mkdirs()
        }

        // تنظيف جميع ملفات التحديثات السابقة لضمان عدم تثبيت ملف APK قديم مخزن في الكاش
        cleanOldUpdateArchives(keepVersionCode = updateInfo.versionCode)

        val safeBuildSuffix = updateInfo.buildId
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(32)
            .ifBlank { "b${updateInfo.versionCode}" }
        val finalFileName = "pmp_update_v${updateInfo.versionCode}_${safeBuildSuffix}.apk"
        val targetFile = File(updatesDir, finalFileName)
        val tempFile = File(updatesDir, "$finalFileName.part")

        // إذا كان الملف النهائي موجوداً مسبقاً، نتحقق منه بصرامة قبل إعادة استخدامه
        if (targetFile.exists() && targetFile.length() >= MIN_VALID_APK_SIZE_BYTES) {
            val cachedValidation = validateApkArchive(targetFile, updateInfo)
            if (cachedValidation.isValid) {
                val sizeMb = targetFile.length().toDouble() / (1024.0 * 1024.0)
                onProgress(100, sizeMb, sizeMb)
                _downloadState.value = DownloadState.ReadyToInstall(targetFile, updateInfo)
                return@withContext Result.success(targetFile)
            } else {
                targetFile.delete()
            }
        }

        // حذف أي ملف مؤقت قديم غير موثوق لضمان تنزيل نظيف 100%
        if (tempFile.exists()) {
            tempFile.delete()
        }

        val candidateUrls = buildCandidateDownloadUrls(updateInfo)
        var lastError: Exception? = null
        var downloadedSuccessfully = false
        val failedUrlsSummary = mutableListOf<String>()

        for ((urlIndex, candidateUrl) in candidateUrls.withIndex()) {
            for (attempt in 1..MAX_DOWNLOAD_RETRIES_PER_URL) {
                var connection: HttpURLConnection? = null
                try {
                    val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
                    _downloadState.value = DownloadState.Downloading(
                        progressPercent = 0,
                        downloadedMb = existingBytes.toDouble() / (1024.0 * 1024.0),
                        totalMb = updateInfo.fileSizeMb,
                        attempt = attempt
                    )

                    val downloadUrlWithCacheBuster = buildCacheBustedUrl(candidateUrl)
                    val url = URL(downloadUrlWithCacheBuster)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15_000
                        readTimeout = 25_000
                        useCaches = false
                        defaultUseCaches = false
                        instanceFollowRedirects = true
                        setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                        setRequestProperty("Pragma", "no-cache")
                        setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                        setRequestProperty("User-Agent", "PMP-Paints-Updater/${BuildConfig.VERSION_NAME}")
                        if (existingBytes > 0L) {
                            setRequestProperty("Range", "bytes=$existingBytes-")
                        }
                    }
                    connection.connect()

                    val code = connection.responseCode
                    if (code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_FORBIDDEN) {
                        failedUrlsSummary.add("HTTP $code (${candidateUrl.substringAfterLast('/')})")
                        lastError = Exception("ملف APK غير متوفر على الرابط (HTTP $code)")
                        break
                    }
                    if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                        throw Exception("استجابة غير متوقعة من الخادم (HTTP $code)")
                    }

                    val contentType = (connection.contentType ?: "").lowercase()
                    if (contentType.contains("text/html") || contentType.contains("application/json")) {
                        failedUrlsSummary.add("استجابة غير ثنائية ($contentType)")
                        lastError = Exception("الرابط أعاد صفحة ويب بدلاً من ملف APK ثنائي.")
                        break
                    }

                    val isResumed = (code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L)
                    val contentLength = connection.contentLengthLong
                    val totalBytes = when {
                        isResumed && contentLength > 0L -> existingBytes + contentLength
                        contentLength > 0L -> contentLength
                        updateInfo.fileSize > 0L -> updateInfo.fileSize
                        else -> (updateInfo.fileSizeMb * 1024 * 1024).toLong()
                    }
                    val totalMb = (totalBytes.toDouble() / (1024.0 * 1024.0)).coerceAtLeast(1.0)

                    var downloadedBytes = if (isResumed) existingBytes else 0L
                    connection.inputStream.use { input ->
                        FileOutputStream(tempFile, isResumed).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val downloadedMb = downloadedBytes.toDouble() / (1024.0 * 1024.0)
                                val percent = if (totalBytes > 0L) {
                                    ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
                                } else {
                                    50
                                }
                                onProgress(percent, downloadedMb, totalMb)
                                _downloadState.value = DownloadState.Downloading(
                                    progressPercent = percent,
                                    downloadedMb = downloadedMb,
                                    totalMb = totalMb,
                                    attempt = urlIndex + 1
                                )
                            }
                            output.flush()
                        }
                    }

                    if (!isValidZipApkHeader(tempFile)) {
                        tempFile.delete()
                        throw Exception("الملف المنزّل ليس حزمة أندرويد صالحة (ترويسة ZIP مفقودة).")
                    }

                    downloadedSuccessfully = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Download attempt $attempt for URL #$urlIndex ($candidateUrl) failed: ${e.message}")
                    if (attempt < MAX_DOWNLOAD_RETRIES_PER_URL) {
                        delay(1000L * attempt)
                    }
                } finally {
                    connection?.disconnect()
                }
            }
            if (downloadedSuccessfully) break
        }

        // منع قاطع لأي محاكاة أو نسخ للحزمة المحلية المثبتة (No Fake Self-Copy Fallback!)
        if (!downloadedSuccessfully) {
            tempFile.delete()
            val urlDiag = failedUrlsSummary.distinct().take(2).joinToString(" ، ")
            val errorMsg = buildString {
                append("تعذر تنزيل ملف التحديث الفعلي (APK) للإصدار v${updateInfo.versionName} (Build ${updateInfo.versionCode}). ")
                if (urlDiag.isNotBlank()) {
                    append("تفاصيل الخادم: [$urlDiag]. ")
                } else if (lastError != null) {
                    append("${lastError?.localizedMessage}. ")
                }
                append("يرجى التأكد من اكتمال بناء ورفع ملف APK عبر GitHub Actions في المستودع (ow7ab/ow7ab).")
            }
            _downloadState.value = DownloadState.Failed(errorMsg, canRetry = true)
            return@withContext Result.failure(Exception(errorMsg))
        }

        _downloadState.value = DownloadState.VerifyingChecksum
        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }

        val validation = validateApkArchive(targetFile, updateInfo)
        if (!validation.isValid) {
            targetFile.delete()
            _downloadState.value = DownloadState.Failed(validation.errorMessage, canRetry = true)
            return@withContext Result.failure(Exception(validation.errorMessage))
        }

        val finalMb = targetFile.length().toDouble() / (1024.0 * 1024.0)
        onProgress(100, finalMb, finalMb)
        _downloadState.value = DownloadState.ReadyToInstall(targetFile, updateInfo)
        Result.success(targetFile)
    }

    private data class ApkValidationResult(
        val isValid: Boolean,
        val errorMessage: String = ""
    )

    /**
     * فحص جنائي وأمني شامل لملف APK المنزّل قبل السماح بتشغيل مثبّت أندرويد:
     * 1. وجود الملف وحجمه الفعلي وترويسة ZIP (PK\x03\x04).
     * 2. مطابقة حجم الملف (fileSize) إن كان محدداً بدقة في version.json.
     * 3. مطابقة البصمة الرقمية SHA-256.
     * 4. فحص PackageArchiveInfo عبر PackageManager للتأكد من:
     *    - packageName == com.pmp
     *    - archiveVersionCode == expectedUpdate.versionCode
     *    - archiveVersionCode > getInstalledVersionCode() (أحدث فعلياً من النسخة المثبتة)
     * 5. مطابقة شهادة التوقيع الرقمي (Signing Certificate SHA-256) لضمان عدم فشل التثبيت أو ضياع البيانات.
     */
    private fun validateApkArchive(apkFile: File, expectedUpdate: UpdateInfo): ApkValidationResult {
        if (!apkFile.exists() || apkFile.length() < MIN_VALID_APK_SIZE_BYTES) {
            return ApkValidationResult(
                isValid = false,
                errorMessage = "حجم ملف التحديث المنزّل صغير جداً أو غير مكتمل (${apkFile.length()} بايت)."
            )
        }

        if (!isValidZipApkHeader(apkFile)) {
            return ApkValidationResult(
                isValid = false,
                errorMessage = "ملف التحديث المنزّل تالف (ترويسة ZIP/APK غير صالحة)."
            )
        }

        if (expectedUpdate.fileSize > MIN_VALID_APK_SIZE_BYTES && apkFile.length() != expectedUpdate.fileSize) {
            return ApkValidationResult(
                isValid = false,
                errorMessage = "حجم ملف APK المنزّل (${apkFile.length()} بايت) لا يطابق الحجم المعتمد في البناء (${expectedUpdate.fileSize} بايت)."
            )
        }

        val expectedSha = expectedUpdate.sha256Checksum.trim().lowercase()
        if (expectedSha.length == 64) {
            val actualSha = computeFileSha256(apkFile).lowercase()
            if (actualSha != expectedSha) {
                return ApkValidationResult(
                    isValid = false,
                    errorMessage = "فشل التحقق الأمني (SHA-256): بصمة الملف المنزّل (${actualSha.take(12)}...) لا تطابق بصمة الإصدار المعتمد (${expectedSha.take(12)}...)."
                )
            }
        }

        return try {
            val pm = appContext.packageManager
            val archiveFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val archiveInfo: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(archiveFlags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, archiveFlags)
            }

            if (archiveInfo == null) {
                return ApkValidationResult(
                    isValid = false,
                    errorMessage = "تعذر قراءة بيانات الحزمة (AndroidManifest) داخل ملف APK المنزّل."
                )
            }

            val archivePackage = archiveInfo.packageName.orEmpty()
            if (archivePackage != appContext.packageName) {
                return ApkValidationResult(
                    isValid = false,
                    errorMessage = "رفض أمني: معرّف الحزمة داخل التحديث ($archivePackage) لا يطابق تطبيق PMP PAINTS (${appContext.packageName})!"
                )
            }

            val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.versionCode
            }

            val installedVersionCode = getInstalledVersionCode()

            // منع تثبيت نفس النسخة أو نسخة أقدم
            if (archiveVersionCode <= installedVersionCode) {
                return ApkValidationResult(
                    isValid = false,
                    errorMessage = "رفض التثبيت: ملف APK المنزّل يحمل رقم بناء ($archiveVersionCode) مساوٍ أو أقدم من النسخة المثبتة حالياً ($installedVersionCode)!"
                )
            }

            // التأكد من أن رقم البناء داخل ملف APK يطابق رقم البناء المعلن في التحديث
            if (archiveVersionCode != expectedUpdate.versionCode) {
                return ApkValidationResult(
                    isValid = false,
                    errorMessage = "تعارض في رقم البناء: ملف APK المنزّل يحمل البناء ($archiveVersionCode) بينما الإشعار يشير للبناء (${expectedUpdate.versionCode})."
                )
            }

            // التحقق من تطابق شهادة التوقيع مع التطبيق المثبت حالياً لمنع INSTALL_FAILED_UPDATE_INCOMPATIBLE
            val installedCertSha = getInstalledSigningCertSha256()
            val archiveCertSha = extractSigningCertSha256FromPackageInfo(archiveInfo)
            if (installedCertSha.isNotBlank() && archiveCertSha.isNotBlank() &&
                !installedCertSha.equals(archiveCertSha, ignoreCase = true)
            ) {
                return ApkValidationResult(
                    isValid = false,
                    errorMessage = "رفض التثبيت لحماية بياناتك: شهادة توقيع التحديث (${archiveCertSha.take(16)}...) لا تطابق شهادة النسخة المثبتة (${installedCertSha.take(16)}...)."
                )
            }

            ApkValidationResult(isValid = true)
        } catch (e: Exception) {
            Log.e(TAG, "Archive validation error: ${e.message}", e)
            ApkValidationResult(
                isValid = false,
                errorMessage = "فشل فحص صلاحية ملف APK قبل التثبيت: ${e.localizedMessage}"
            )
        }
    }

    /**
     * تنظيف ملفات التحديثات القديمة من الكاش لمنع تراكم المساحة أو الخلط بين الإصدارات
     */
    private fun cleanOldUpdateArchives(keepVersionCode: Int) {
        runCatching {
            val updatesDir = File(appContext.cacheDir, "updates")
            if (!updatesDir.exists()) return
            updatesDir.listFiles()?.forEach { file ->
                val keepPrefix = "pmp_update_v${keepVersionCode}_"
                if (keepVersionCode <= 0 || !file.name.startsWith(keepPrefix)) {
                    file.delete()
                }
            }
        }
    }

    fun resetDownloadState() {
        _downloadState.value = DownloadState.Idle
    }

    /**
     * هل يملك التطبيق صلاحية تثبيت الحزم من مصادر غير معروفة (Android 8.0+)
     */
    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * فتح شاشة إعدادات السماح بتثبيت التطبيقات غير المعروفة الخاصة بالتطبيق
     */
    fun openInstallPermissionSettings(activityContext: Context = appContext) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                activityContext.startActivity(intent)
            }
        }
    }

    /**
     * يتحقق مما إذا كان الملف يمثل حزمة APK خارجية جديدة تتطلب ترقية النظام الفعلية
     */
    fun isExternalBinaryApkUpgrade(apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        return try {
            val pm = appContext.packageManager
            val archiveInfo: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
            val archiveCode = if (archiveInfo != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    archiveInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    archiveInfo.versionCode
                }
            } else {
                0
            }
            archiveCode > getInstalledVersionCode()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * تثبيت ملف APK الموثّق عبر مثبّت حزم أندرويد الرسمي (ACTION_VIEW + FileProvider / PackageInstaller Session).
     * لا يتم أبداً الإعلان عن نجاح التثبيت بمجرد فتح المثبّت؛ بل يتم تسجيل الإصدار المتوقع والتحقق منه عبر PackageManager.
     */
    fun installApk(
        activityContext: Context = appContext,
        apkFile: File,
        pendingUpdate: UpdateInfo? = null
    ): Result<Unit> {
        val targetInfo = pendingUpdate ?: UpdateInfo(
            versionCode = getInstalledVersionCode() + 1,
            versionName = getInstalledVersionName(),
            downloadUrl = DEFAULT_APK_DOWNLOAD_URL,
            releaseNotes = ""
        )

        val validation = validateApkArchive(apkFile, targetInfo)
        if (!validation.isValid) {
            return Result.failure(IllegalStateException(validation.errorMessage))
        }

        if (!canRequestPackageInstalls()) {
            openInstallPermissionSettings(activityContext)
            return Result.failure(
                SecurityException("يرجى تفعيل خيار «السماح بتثبيت التطبيقات من هذا المصدر» أولاً ثم الضغط على تثبيت التحديث.")
            )
        }

        pendingInstallUpdateInfo = targetInfo
        recordPendingExpectedUpdate(targetInfo)

        // فتح شاشة تثبيت أندرويد الرسمية المباشرة عبر FileProvider URI (مع دعم PackageInstaller كخيار مساند)
        return runCatching {
            installViaStandardIntent(activityContext, apkFile)
        }.recoverCatching {
            installViaPackageInstaller(apkFile).getOrThrow()
        }
    }

    private fun installViaPackageInstaller(apkFile: File): Result<Unit> {
        return try {
            val packageInstaller = appContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(appContext.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.use { activeSession ->
                FileInputStream(apkFile).use { input ->
                    activeSession.openWrite("pmp_update.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output, bufferSize = 16 * 1024)
                        activeSession.fsync(output)
                    }
                }

                val statusIntent = Intent(appContext, InstallStatusReceiver::class.java).apply {
                    action = InstallStatusReceiver.ACTION_INSTALL_STATUS
                    setPackage(appContext.packageName)
                }

                val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    statusIntent,
                    pendingFlags
                )

                activeSession.commit(pendingIntent.intentSender)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller session failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun installViaStandardIntent(activityContext: Context, apkFile: File) {
        val apkUri: Uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )

        @Suppress("DEPRECATION")
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val resolved = installIntent.resolveActivity(appContext.packageManager)
        if (resolved != null) {
            activityContext.startActivity(installIntent)
        } else {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityContext.startActivity(viewIntent)
        }
    }

    private fun getInstalledPackageInfo(flags: Int): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, flags)
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val PREFS_NAME = "pmp_update_manager_prefs"
        private const val LEGACY_KEY_INSTALLED_CLOUD_CODE = "installed_cloud_version_code"
        private const val LEGACY_KEY_INSTALLED_CLOUD_NAME = "installed_cloud_version_name"
        private const val KEY_EXPECTED_UPDATE_CODE = "expected_pending_update_code"
        private const val KEY_EXPECTED_UPDATE_NAME = "expected_pending_update_name"
        private const val KEY_EXPECTED_BUILD_ID = "expected_pending_build_id"
        private const val KEY_EXPECTED_GIT_COMMIT = "expected_pending_git_commit"
        private const val KEY_EXPECTED_UPDATE_TIMESTAMP = "expected_pending_update_ts"
        private const val MAX_DOWNLOAD_RETRIES_PER_URL = 2
        internal const val MIN_VALID_APK_SIZE_BYTES = 100 * 1024L // 100 KB minimum for real APK

        /**
         * روابط المستودع الرسمي المعتمد (ow7ab/ow7ab) مع دعم GitHub Pages لتفادي خطأ 404 للمستودعات الخاصة
         */
        const val GITHUB_PAGES_BASE_URL = "https://ow7ab.github.io/ow7ab"
        const val GITHUB_PAGES_METADATA_URL = "https://ow7ab.github.io/ow7ab/version.json"
        const val DEFAULT_UPDATE_METADATA_URL = "https://ow7ab.github.io/ow7ab/version.json"
        const val GITHUB_RAW_METADATA_URL = "https://raw.githubusercontent.com/ow7ab/ow7ab/main/version.json"
        const val SECONDARY_UPDATE_METADATA_URL = "https://raw.githubusercontent.com/oaw7ab/pmp-paints-releases/main/version.json"

        const val GITHUB_RELEASES_API_URL = "https://api.github.com/repos/ow7ab/ow7ab/releases/latest"
        const val SECONDARY_GITHUB_RELEASES_API_URL = "https://api.github.com/repos/oaw7ab/pmp-paints-releases/releases/latest"

        const val DEFAULT_DOWNLOAD_PORTAL_URL = "https://github.com/ow7ab/ow7ab/releases/latest/download"
        const val SHARED_DOWNLOAD_PORTAL_URL = "https://github.com/oaw7ab/pmp-paints-releases/releases/latest/download"
        const val DEFAULT_APK_DOWNLOAD_URL = "https://ow7ab.github.io/ow7ab/pmp-paints-v3.0-PRO-b13.apk"

        /**
         * بيانات الإصدار المرجعي الحالي (Version 3.0-PRO / Build 13) المطابق لبصمة البناء المدمجة في BuildConfig
         */
        val LATEST_CLOUD_RELEASE_INFO = UpdateInfo(
            versionCode = 13,
            versionName = "3.0-PRO",
            downloadUrl = DEFAULT_APK_DOWNLOAD_URL,
            releaseNotes = "إصدار 3.0-PRO المعتمد:\n" +
                "1. إصلاح جذري لنظام التحديثات السحابية ومنع التثبيت الوهمي أو نسخ الحزمة المحلية.\n" +
                "2. إضافة بصمة البناء الحقيقية (Build ID + Git Commit SHA + SHA-256) للتحقق الصارم من أن الحزمة المثبتة تحتوي على أحدث كود فعلي.\n" +
                "3. فحص توقيع الحزمة (Signing Certificate) وتطابق versionCode قبل تشغيل مثبّت أندرويد الرسمي.\n" +
                "4. إضافة لوحة تشخيص البناء والتحديثات للمدير (Admin Build & Update Diagnostic Panel).",
            isMandatory = false,
            minSupportedVersionCode = 1,
            fileSizeMb = 18.5,
            fileSize = 0L,
            buildId = BuildConfig.BUILD_ID,
            gitCommit = BuildConfig.GIT_COMMIT_SHA,
            publishedAt = BuildConfig.BUILD_TIMESTAMP_UTC
        )

        @Volatile
        private var instance: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return instance ?: synchronized(this) {
                instance ?: UpdateManager(context.applicationContext).also { instance = it }
            }
        }

        internal fun formatUtcTimestamp(epochMillis: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date(epochMillis))
        }

        /**
         * استخراج بصمة شهادة التوقيع SHA-256 من PackageInfo
         */
        internal fun extractSigningCertSha256FromPackageInfo(packageInfo: PackageInfo): String {
            return runCatching {
                val certBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val signingInfo = packageInfo.signingInfo
                    val signatures = if (signingInfo != null) {
                        if (signingInfo.hasMultipleSigners()) {
                            signingInfo.apkContentsSigners
                        } else {
                            signingInfo.signingCertificateHistory
                        }
                    } else {
                        null
                    }
                    signatures?.firstOrNull()?.toByteArray()
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.signatures?.firstOrNull()?.toByteArray()
                }
                if (certBytes != null && certBytes.isNotEmpty()) {
                    val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
                    digest.joinToString(":") { "%02X".format(it) }
                } else {
                    ""
                }
            }.getOrDefault("")
        }

        /**
         * بناء رابط مع معرّف زمني لمنع التخزين المؤقت في الشبكة وCDN
         */
        internal fun buildCacheBustedUrl(url: String, timestampMillis: Long = System.currentTimeMillis()): String {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return trimmed
            val separator = if (trimmed.contains("?")) "&" else "?"
            return "${trimmed}${separator}_cb=$timestampMillis"
        }

        /**
         * تطبيع رابط التحميل ليضمن دائماً الإشارة لملف `.apk` مباشر
         */
        internal fun normalizeDownloadUrl(
            rawUrl: String,
            versionName: String,
            versionCode: Int
        ): String {
            val clean = rawUrl.trim()
            if (clean.isBlank()) {
                return "$GITHUB_PAGES_BASE_URL/pmp-paints-v${versionName}-b${versionCode}.apk"
            }
            if (clean.endsWith("/releases/latest/download", ignoreCase = true) ||
                clean.endsWith("/releases/latest/download/", ignoreCase = true)
            ) {
                return "${clean.trimEnd('/')}/pmp-paints-v${versionName}-b${versionCode}.apk"
            }
            if (clean.endsWith("/releases/latest", ignoreCase = true) ||
                clean.endsWith("/releases", ignoreCase = true)
            ) {
                val repoBase = clean
                    .removeSuffix("/releases/latest")
                    .removeSuffix("/releases")
                    .trimEnd('/')
                return "$repoBase/releases/download/v$versionName/pmp-paints-v${versionName}-b${versionCode}.apk"
            }
            return clean
        }

        /**
         * توليد قائمة الروابط المرشحة لتحميل ملف APK (GitHub Pages + GitHub Releases لمستودع ow7ab/ow7ab)
         */
        internal fun buildCandidateDownloadUrls(updateInfo: UpdateInfo): List<String> {
            val vName = updateInfo.versionName.removePrefix("v").removePrefix("V").trim()
            val vCode = updateInfo.versionCode
            val primaryNormalized = normalizeDownloadUrl(updateInfo.downloadUrl, vName, vCode)

            return buildList {
                if (primaryNormalized.startsWith("https://", ignoreCase = true)) {
                    add(primaryNormalized)
                }
                // 1. GitHub Pages Public Hosting (يعمل حتى لو كان المستودع ow7ab/ow7ab خاصاً Private)
                add("$GITHUB_PAGES_BASE_URL/pmp-paints-v${vName}-b${vCode}.apk")
                add("$GITHUB_PAGES_BASE_URL/pmp-paints-latest.apk")

                // 2. GitHub Releases لمستودع ow7ab/ow7ab
                add("https://github.com/ow7ab/ow7ab/releases/download/v${vName}/pmp-paints-v${vName}-b${vCode}.apk")
                add("https://github.com/ow7ab/ow7ab/releases/download/v${vName}/pmp-paints-latest.apk")
                add("$DEFAULT_DOWNLOAD_PORTAL_URL/pmp-paints-v${vName}-b${vCode}.apk")
                add("$DEFAULT_DOWNLOAD_PORTAL_URL/pmp-paints-latest.apk")

                // 3. المستودع المساند oaw7ab/pmp-paints-releases
                add("https://github.com/oaw7ab/pmp-paints-releases/releases/download/v${vName}/pmp-paints-v${vName}-b${vCode}.apk")
                add("$SHARED_DOWNLOAD_PORTAL_URL/pmp-paints-v${vName}-b${vCode}.apk")
                add("$SHARED_DOWNLOAD_PORTAL_URL/pmp-paints-latest.apk")
            }.distinct()
        }

        /**
         * تحليل محتوى version.json إلى كائن UpdateInfo شامل لحقول Build Identity
         * يدعم org.json.JSONObject في بيئة أندرويد والمحلل المباشر في اختبارات JVM
         */
        internal fun parseVersionJsonToUpdateInfo(rawJson: String): UpdateInfo? {
            if (rawJson.isBlank()) return null
            return try {
                val json = JSONObject(rawJson)
                val remoteCode = json.optInt("versionCode", json.optInt("version_code", 0))
                if (remoteCode <= 0) return parseVersionJsonPureKotlin(rawJson)
                val remoteName = json.optString("versionName", json.optString("version_name", "$remoteCode.0"))
                val rawUrl = json.optString(
                    "downloadUrl",
                    json.optString("apkUrl", json.optString("download_url", ""))
                )
                val downloadUrl = normalizeDownloadUrl(rawUrl, remoteName, remoteCode)
                val notes = json.optString(
                    "releaseNotes",
                    json.optString("release_notes", "تحديث سحابي معتمد لتطبيق PMP PAINTS.")
                )
                val mandatory = json.optBoolean("isMandatory", json.optBoolean("mandatory", false))
                val sha256 = json.optString("sha256", json.optString("sha256Checksum", "")).trim()
                val minSupported = json.optInt(
                    "minSupportedVersion",
                    json.optInt("minSupportedVersionCode", 1)
                )
                val sizeBytes = json.optLong("fileSize", json.optLong("file_size", 0L))
                val sizeMb = json.optDouble(
                    "fileSizeMb",
                    if (sizeBytes > 0L) Math.round((sizeBytes.toDouble() / (1024.0 * 1024.0)) * 100.0) / 100.0 else 18.5
                )
                val buildId = json.optString("buildId", json.optString("build_id", "")).trim()
                val gitCommit = json.optString("gitCommit", json.optString("git_commit", "")).trim()
                val publishedAt = json.optString("publishedAt", json.optString("published_at", "")).trim()
                val signingCertSha256 = json.optString("signingCertSha256", "").trim()

                UpdateInfo(
                    versionCode = remoteCode,
                    versionName = remoteName,
                    downloadUrl = downloadUrl,
                    releaseNotes = notes,
                    isMandatory = mandatory,
                    sha256Checksum = sha256,
                    minSupportedVersionCode = minSupported,
                    fileSizeMb = sizeMb,
                    fileSize = sizeBytes,
                    buildId = buildId,
                    gitCommit = gitCommit,
                    publishedAt = publishedAt,
                    signingCertSha256 = signingCertSha256
                )
            } catch (e: Throwable) {
                parseVersionJsonPureKotlin(rawJson)
            }
        }

        private fun parseVersionJsonPureKotlin(rawJson: String): UpdateInfo? {
            fun extractInt(vararg keys: String): Int? {
                for (key in keys) {
                    val match = Regex(""""$key"\s*:\s*(\d+)""").find(rawJson)
                    if (match != null) return match.groupValues[1].toIntOrNull()
                }
                return null
            }
            fun extractLong(vararg keys: String): Long? {
                for (key in keys) {
                    val match = Regex(""""$key"\s*:\s*(\d+)""").find(rawJson)
                    if (match != null) return match.groupValues[1].toLongOrNull()
                }
                return null
            }
            fun extractDouble(vararg keys: String): Double? {
                for (key in keys) {
                    val match = Regex(""""$key"\s*:\s*([0-9]+(?:\.[0-9]+)?)""").find(rawJson)
                    if (match != null) return match.groupValues[1].toDoubleOrNull()
                }
                return null
            }
            fun extractBoolean(vararg keys: String): Boolean? {
                for (key in keys) {
                    val match = Regex(""""$key"\s*:\s*(true|false)""", RegexOption.IGNORE_CASE).find(rawJson)
                    if (match != null) return match.groupValues[1].equals("true", ignoreCase = true)
                }
                return null
            }
            fun extractString(vararg keys: String): String? {
                for (key in keys) {
                    val match = Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""").find(rawJson)
                    if (match != null) {
                        return match.groupValues[1]
                            .replace("\\n", "\n")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                    }
                }
                return null
            }

            val remoteCode = extractInt("versionCode", "version_code") ?: return null
            if (remoteCode <= 0) return null
            val remoteName = extractString("versionName", "version_name") ?: "$remoteCode.0"
            val rawUrl = extractString("downloadUrl", "apkUrl", "download_url").orEmpty()
            val downloadUrl = normalizeDownloadUrl(rawUrl, remoteName, remoteCode)
            val notes = extractString("releaseNotes", "release_notes")
                ?: "تحديث سحابي معتمد لتطبيق PMP PAINTS."
            val mandatory = extractBoolean("isMandatory", "mandatory") ?: false
            val sha256 = extractString("sha256", "sha256Checksum").orEmpty().trim()
            val minSupported = extractInt("minSupportedVersion", "minSupportedVersionCode") ?: 1
            val sizeBytes = extractLong("fileSize", "file_size") ?: 0L
            val sizeMb = extractDouble("fileSizeMb")
                ?: if (sizeBytes > 0L) Math.round((sizeBytes.toDouble() / (1024.0 * 1024.0)) * 100.0) / 100.0 else 18.5
            val buildId = extractString("buildId", "build_id").orEmpty().trim()
            val gitCommit = extractString("gitCommit", "git_commit").orEmpty().trim()
            val publishedAt = extractString("publishedAt", "published_at").orEmpty().trim()
            val signingCertSha256 = extractString("signingCertSha256").orEmpty().trim()

            return UpdateInfo(
                versionCode = remoteCode,
                versionName = remoteName,
                downloadUrl = downloadUrl,
                releaseNotes = notes,
                isMandatory = mandatory,
                sha256Checksum = sha256,
                minSupportedVersionCode = minSupported,
                fileSizeMb = sizeMb,
                fileSize = sizeBytes,
                buildId = buildId,
                gitCommit = gitCommit,
                publishedAt = publishedAt,
                signingCertSha256 = signingCertSha256
            )
        }

        /**
         * التحقق من سلامة بيانات التحديث ومقارنتها مع النسخة المثبتة حالياً
         */
        internal fun validateUpdateMetadataIntegrity(
            installedVersionCode: Int,
            installedBuildId: String,
            candidate: UpdateInfo
        ): UpdateValidationDecision {
            if (candidate.versionCode <= 0 || candidate.versionName.isBlank()) {
                return UpdateValidationDecision(false, "بيانات الإصدار غير مكتملة (versionCode/versionName).")
            }
            if (candidate.versionCode <= installedVersionCode) {
                return UpdateValidationDecision(
                    false,
                    "الإصدار المرشح (${candidate.versionCode}) ليس أحدث من الإصدار المثبت ($installedVersionCode)."
                )
            }
            if (!candidate.downloadUrl.startsWith("https://", ignoreCase = true)) {
                return UpdateValidationDecision(false, "رابط التحميل يجب أن يكون بصيغة HTTPS آمنة.")
            }
            if (candidate.buildId.isNotBlank() && candidate.buildId == installedBuildId) {
                return UpdateValidationDecision(
                    false,
                    "تعارض: معرّف البناء (${candidate.buildId}) مطابق تماماً للبناء المثبت حالياً رغم اختلاف رقم الإصدار!"
                )
            }
            if (candidate.sha256Checksum.isNotBlank() && candidate.sha256Checksum.length != 64) {
                return UpdateValidationDecision(false, "بصمة SHA-256 غير صالحة (يجب أن تكون 64 محرفاً سداسياً عشرياً).")
            }
            return UpdateValidationDecision(true, "بيانات الإصدار صالحة وموثّقة.")
        }

        /**
         * التحقق من انتقال هوية البناء الفعلية من الإصدار أ (Version A) إلى الإصدار ب (Version B)
         */
        internal fun verifyBuildIdentityTransition(
            previousVersionCode: Int,
            previousBuildId: String,
            currentVersionCode: Int,
            currentBuildId: String,
            expectedUpdate: UpdateInfo
        ): BuildTransitionVerification {
            val codeAdvanced = currentVersionCode > previousVersionCode && currentVersionCode == expectedUpdate.versionCode
            val buildChanged = currentBuildId.isNotBlank() && currentBuildId != previousBuildId
            val matchesExpected = expectedUpdate.buildId.isBlank() || currentBuildId == expectedUpdate.buildId

            val isVerified = codeAdvanced && buildChanged && matchesExpected
            val msg = when {
                !codeAdvanced ->
                    "فشل التحقق: رقم البناء المثبت ($currentVersionCode) لم يرتقِ إلى الإصدار المستهدف (${expectedUpdate.versionCode})."
                !buildChanged ->
                    "فشل التحقق: رقم الإصدار تغيّر إلى ($currentVersionCode) لكن بصمة البناء داخل الكود ($currentBuildId) لم تتغير عن النسخة السابقة!"
                !matchesExpected ->
                    "فشل التحقق: بصمة البناء المثبتة ($currentBuildId) لا تطابق بصمة التحديث المنشور (${expectedUpdate.buildId})."
                else ->
                    "نجح التحقق الجذري: تم الانتقال فعلياً من ($previousBuildId) إلى ($currentBuildId) وتحديث الكود البرمجي."
            }

            return BuildTransitionVerification(
                isVerified = isVerified,
                versionCodeAdvanced = codeAdvanced,
                buildIdChanged = buildChanged,
                matchesExpectedBuildId = matchesExpected,
                diagnosticMessage = msg
            )
        }

        /**
         * فحص ترويسة الملف للتأكد من أنه أرشيف ZIP/APK حقيقي يبدأ بـ PK\x03\x04
         */
        internal fun isValidZipApkHeader(file: File): Boolean {
            if (!file.exists() || file.length() < 4L) return false
            return try {
                FileInputStream(file).use { fis ->
                    val magic = ByteArray(4)
                    if (fis.read(magic) != 4) return false
                    magic[0] == 0x50.toByte() &&
                        magic[1] == 0x4B.toByte() &&
                        magic[2] == 0x03.toByte() &&
                        magic[3] == 0x04.toByte()
                }
            } catch (e: Exception) {
                false
            }
        }

        /**
         * حساب البصمة الرقمية SHA-256 للملف
         */
        internal fun computeFileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(16 * 1024)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
