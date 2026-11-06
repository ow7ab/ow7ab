package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import com.example.util.BiometricAuthManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.isSuperAdminAccount
import com.example.isSystemManager

@Composable
fun SettingsScreen(
    viewModel: PmpViewModel,
    onNavigateToUsers: () -> Unit = {},
    onSignOut: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val auditLogs by viewModel.auditLogs.collectAsState()
    val cashboxes by viewModel.cashboxes.collectAsState()
    val movements by viewModel.movements.collectAsState()
    val invoices by viewModel.invoices.collectAsState()
    val products by viewModel.products.collectAsState()
    val customers by viewModel.customers.collectAsState()
    val userAccounts by viewModel.userAccounts.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()

    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
    var isBiometricEnabled by remember { mutableStateOf(BiometricAuthManager.isBiometricLockEnabled(context)) }
    val isBiometricAvailable = remember { BiometricAuthManager.isBiometricAvailable(context) }

    val isSuperAdmin = currentUser.isSuperAdminAccount()
    var healthStatus by remember { mutableStateOf("جميع السجلات والبيانات متطابقة وسليمة") }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showAllAuditLogs by remember { mutableStateOf(false) }
    var showDiagnosticDashboard by remember { mutableStateOf(false) }
    var showTransactionDiagnosticsDialog by remember { mutableStateOf(false) }
    var showServerWipeDangerDialog by remember { mutableStateOf(false) }
    var activeSettingsTab by remember { mutableIntStateOf(0) }
    val settingsTabs = remember(isSuperAdmin) {
        if (isSuperAdmin) listOf("العام والحساب", "المزامنة وفحص النظام") else listOf("العام والحساب")
    }
    LaunchedEffect(isSuperAdmin) {
        if (!isSuperAdmin && activeSettingsTab != 0) {
            activeSettingsTab = 0
        }
    }

    val transactionTraces by viewModel.transactionTraces.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val cloudSyncState by viewModel.cloudSyncState.collectAsState()
    val serverHealthReport by viewModel.serverHealthReport.collectAsState()
    val clusterCode by viewModel.serverClusterCode.collectAsState()
    val lastSyncTime by viewModel.lastSuccessfulSyncTime.collectAsState()

    // User Profile state
    var profileFullName by remember(currentUser) { mutableStateOf(currentUser?.fullName.orEmpty()) }
    var profileEmail by remember(currentUser) { mutableStateOf(currentUser?.phoneOrEmail.orEmpty()) }
    var profileNewPassword by remember { mutableStateOf("") }
    var profileConfirmPassword by remember { mutableStateOf("") }
    var isProfileSaving by remember { mutableStateOf(false) }
    var showUpdateInstallDialog by remember { mutableStateOf(false) }

    val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsState()
    val isDownloadingUpdate by viewModel.isDownloadingUpdate.collectAsState()
    val updateProgress by viewModel.updateProgress.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val availableUpdate by viewModel.availableUpdate.collectAsState()
    val updateAvailable by viewModel.updateAvailable.collectAsState()
    val downloadedApkFile by viewModel.downloadedApkFile.collectAsState()
    val effectiveVersionCode by viewModel.effectiveVersionCode.collectAsState()
    val effectiveVersionName by viewModel.effectiveVersionName.collectAsState()
    val lastUpdateCheckStatus by viewModel.lastUpdateCheckStatus.collectAsState()

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "الإعدادات",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )

                    if (settingsTabs.size > 1) {
                        Spacer(modifier = Modifier.height(12.dp))
                        GlassTabRow(
                            tabs = settingsTabs,
                            selectedIndex = activeSettingsTab,
                            onTabSelected = { activeSettingsTab = it }
                        )
                    }
                }
            }
        }

        if (activeSettingsTab == 0 || !isSuperAdmin) {
        // Section 1: User Profile Card (الملف الشخصي للمستخدم)
        item {
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(24.dp))
                            Text(
                                text = "الملف الشخصي",
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        GlassBadge(text = currentUser?.role ?: "مستخدم", color = PmpGold)
                    }

                    if (isSuperAdmin) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "اسم الدخول:",
                                color = TextMuted,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            OutlinedTextField(
                                value = currentUser?.username ?: "admin",
                                onValueChange = {},
                                readOnly = true,
                                enabled = false,
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = TextMuted) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledContainerColor = PmpNavySurface.copy(alpha = 0.6f),
                                    disabledTextColor = TextWhite.copy(alpha = 0.7f),
                                    disabledBorderColor = GlassBorder
                                )
                            )
                        }
                    }

                    // Editable Name
                    GlassTextField(
                        value = profileFullName,
                        onValueChange = { profileFullName = it },
                        label = "الاسم بالكامل *",
                        placeholder = "اسم الموظف أو المدير"
                    )

                    // Editable Email
                    GlassTextField(
                        value = profileEmail,
                        onValueChange = { profileEmail = it },
                        label = "البريد الإلكتروني أو الهاتف",
                        placeholder = "user@pmp-paints.com"
                    )

                    // Editable Password (Stacked cleanly to prevent label/placeholder overlap on narrow screens)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassTextField(
                            value = profileNewPassword,
                            onValueChange = { profileNewPassword = it },
                            label = "كلمة المرور الجديدة (اختياري)",
                            placeholder = "4 خانات على الأقل",
                            modifier = Modifier.fillMaxWidth()
                        )
                        GlassTextField(
                            value = profileConfirmPassword,
                            onValueChange = { profileConfirmPassword = it },
                            label = "تأكيد كلمة المرور الجديدة",
                            placeholder = "إعادة كتابة كلمة المرور",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    GlassButton(
                        text = if (isProfileSaving) "جاري حفظ التعديلات..." else "تأكيد",
                        icon = Icons.Default.Save,
                        gradient = PmpBrandRedGradient,
                        enabled = !isProfileSaving,
                        onClick = {
                            if (profileFullName.trim().isBlank()) {
                                viewModel.postMessage("الاسم لا يمكن أن يكون فارغاً", isError = true)
                                return@GlassButton
                            }
                            if (profileNewPassword.isNotBlank()) {
                                if (profileNewPassword.length < 4) {
                                    viewModel.postMessage("كلمة المرور يجب أن تتكون من 4 خانات على الأقل", isError = true)
                                    return@GlassButton
                                }
                                if (profileNewPassword != profileConfirmPassword) {
                                    viewModel.postMessage("كلمة المرور وتأكيدها غير متطابقين", isError = true)
                                    return@GlassButton
                                }
                            }
                            isProfileSaving = true
                            viewModel.updateUserProfile(
                                fullName = profileFullName,
                                phoneOrEmail = profileEmail,
                                newPassword = profileNewPassword.ifBlank { null }
                            )
                            isProfileSaving = false
                            profileNewPassword = ""
                            profileConfirmPassword = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Section 2: In-App Updates Card (تحديث التطبيق المباشر)
        item {
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = PmpGreen, modifier = Modifier.size(24.dp))
                            Column {
                                Text(
                                    text = "تحديث التطبيق",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = if (isSuperAdmin) {
                                        "الإصدار الحالي المثبت: v$effectiveVersionName (Build $effectiveVersionCode)"
                                    } else {
                                        "الإصدار الحالي المثبت: v$effectiveVersionName"
                                    },
                                    color = TextMuted,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        GlassBadge(
                            text = if (isSuperAdmin) "v$effectiveVersionName ($effectiveVersionCode)" else "v$effectiveVersionName",
                            color = PmpGreen
                        )
                    }

                    // Available Update Banner or Shimmer Skeleton while checking
                    val activeUpdate = availableUpdate
                    if (isCheckingUpdate && activeUpdate == null) {
                        UpdateVersionCardSkeleton()
                    } else if (activeUpdate != null && updateAvailable) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(PmpBrandNavy.copy(alpha = 0.7f))
                                .border(1.dp, PmpCyan.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp)
                                    ) {
                                        Icon(Icons.Default.NewReleases, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = if (isSuperAdmin) {
                                                "تحديث جديد متوفر (${activeUpdate.versionName} • Build ${activeUpdate.versionCode})"
                                            } else {
                                                "تحديث جديد متوفر (${activeUpdate.versionName})"
                                            },
                                            color = TextWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp
                                        )
                                    }
                                    GlassBadge(
                                        text = if (isSuperAdmin) "Build ${activeUpdate.versionCode}" else "إصدار جديد",
                                        color = PmpGold
                                    )
                                }

                                Text(
                                    text = if (isSuperAdmin && activeUpdate.releaseNotes.isNotBlank()) {
                                        activeUpdate.releaseNotes
                                    } else {
                                        "يتوفر تحديث جديد للتطبيق يتضمن تحسينات في الأداء والسرعة مع الحفاظ الكامل على كافة البيانات."
                                    },
                                    color = TextWhite.copy(alpha = 0.9f),
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    } else if (!isDownloadingUpdate) {
                        val isErrorStatus = lastUpdateCheckStatus.startsWith("تعذر") ||
                            lastUpdateCheckStatus.startsWith("فشل") ||
                            lastUpdateCheckStatus.startsWith("لم يتم")
                        val statusColor = if (isErrorStatus) PmpRedLight else PmpGreen
                        val userFacingStatus = if (isSuperAdmin) {
                            lastUpdateCheckStatus
                        } else {
                            lastUpdateCheckStatus.replace(Regex("\\s*[•(]?Build\\s*\\d+\\)?", RegexOption.IGNORE_CASE), "").trim()
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(statusColor.copy(alpha = 0.12f))
                                .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isErrorStatus) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = userFacingStatus,
                                    color = statusColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    if (isDownloadingUpdate) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(PmpBrandNavy.copy(alpha = 0.5f))
                                .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(72.dp)
                                ) {
                                    if (updateProgress > 0) {
                                        CircularProgressIndicator(
                                            progress = { updateProgress / 100f },
                                            modifier = Modifier.size(72.dp),
                                            color = PmpCyan,
                                            trackColor = PmpNavySurface,
                                            strokeWidth = 6.dp
                                        )
                                        Text(
                                            text = "$updateProgress%",
                                            color = TextWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(72.dp),
                                            color = PmpCyan,
                                            strokeWidth = 6.dp
                                        )
                                        Icon(
                                            imageVector = Icons.Default.CloudDownload,
                                            contentDescription = null,
                                            tint = PmpCyan,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = when (val state = downloadState) {
                                        is com.example.util.DownloadState.Connecting -> "جاري الاتصال وتجهيز التحديث..."
                                        is com.example.util.DownloadState.Progress -> "جاري تنزيل التحديث ($updateProgress%)"
                                        is com.example.util.DownloadState.Success -> "اكتمل التنزيل بنجاح"
                                        is com.example.util.DownloadState.Error -> "تعذر التنزيل: ${state.message}"
                                        else -> "جاري تنزيل التحديث..."
                                    },
                                    color = PmpCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassButton(
                            text = if (isCheckingUpdate) "جاري التحقق من التحديثات..." else "فحص التحديثات الآن",
                            icon = Icons.Default.Refresh,
                            gradient = GlassAccentBlueGradient,
                            enabled = !isCheckingUpdate && !isDownloadingUpdate,
                            onClick = { viewModel.checkForAppUpdates(manual = true) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("check_for_updates_button")
                        )

                        val buttonText = when {
                            isDownloadingUpdate -> "جاري تنزيل التحديث..."
                            downloadedApkFile != null -> "تثبيت التحديث الآن"
                            availableUpdate != null -> "تنزيل وتثبيت التحديث الجديد"
                            else -> "تنزيل التحديث"
                        }

                        GlassButton(
                            text = buttonText,
                            icon = if (downloadedApkFile != null) Icons.Default.InstallMobile else Icons.Default.CloudDownload,
                            gradient = PmpBrandRedGradient,
                            enabled = !isDownloadingUpdate && (availableUpdate != null || downloadedApkFile != null),
                            onClick = {
                                if (downloadedApkFile != null) {
                                    showUpdateInstallDialog = true
                                } else {
                                    viewModel.downloadAndInstallUpdate(context) {
                                        showUpdateInstallDialog = true
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("download_install_update_button")
                        )
                    }

                    // حصرياً لحساب admin فقط — مخفي تماماً عن جميع المستخدمين الآخرين
                    if (isSuperAdmin && currentUser?.username?.equals("admin", ignoreCase = true) == true) {
                        Button(
                            onClick = {
                                viewModel.broadcastUpdateToAllPreviousVersions()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PmpCyan),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("broadcast_update_previous_versions_button")
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "بث التحديث الجديد لجميع النسخ السابقة (Admin فقط)",
                                color = Color.Black,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Section 3: User Accounts & Logout Card
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "إدارة الجلسة والحسابات",
                            color = PmpBrandRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (currentUser.isSystemManager()) {
                            GlassBadge(text = "${userAccounts.size} حسابات", color = PmpGreen)
                        }
                    }

                    if (currentUser.isSystemManager()) {
                        GlassButton(
                            text = "إدارة حسابات وصلاحيات الموظفين",
                            icon = Icons.Default.ManageAccounts,
                            gradient = PmpBrandRedGradient,
                            onClick = onNavigateToUsers,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    GlassButton(
                        text = "تسجيل الخروج",
                        icon = Icons.Default.Logout,
                        gradient = GlassAccentBlueGradient,
                        onClick = onSignOut,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Section: Biometric Security Card
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(24.dp))
                            Text(
                                text = "القفل البيومتري (البصمة والوجه)",
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        Switch(
                            checked = isBiometricEnabled,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    if (activity != null) {
                                        BiometricAuthManager.authenticate(
                                            activity = activity,
                                            title = "تفعيل القفل البيومتري",
                                            subtitle = "يرجى مسح البصمة أو الوجه لتأكيد التفعيل",
                                            onSuccess = {
                                                BiometricAuthManager.setBiometricLockEnabled(context, true)
                                                isBiometricEnabled = true
                                                viewModel.postMessage("تم تفعيل القفل البيومتري بنجاح")
                                            },
                                            onError = { err ->
                                                viewModel.postMessage(err, isError = true)
                                            }
                                        )
                                    } else {
                                        BiometricAuthManager.setBiometricLockEnabled(context, true)
                                        isBiometricEnabled = true
                                        viewModel.postMessage("تم تفعيل القفل البيومتري")
                                    }
                                } else {
                                    BiometricAuthManager.setBiometricLockEnabled(context, false)
                                    isBiometricEnabled = false
                                    viewModel.postMessage("تم تعطيل القفل البيومتري")
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = PmpGreen,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = PmpNavySurface
                            )
                        )
                    }
                }
            }
        }
        } else {
            // Tab 1: المزامنة وفحص النظام (خاص بحساب admin فقط)
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.CloudSync, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(24.dp))
                                Text(
                                    text = "المزامنة السحابية والنسخ الاحتياطي",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                            GlassBadge(
                                text = if (isSyncing) "جارٍ المزامنة..." else "متصل",
                                color = if (isSyncing) PmpGold else PmpGreen
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "آخر مزامنة ناجحة:", color = TextMuted, fontSize = 12.sp)
                            Text(
                                text = formatRelativeTimeArabic(lastSyncTime),
                                color = TextWhite,
                                fontSize = 12.sp
                            )
                        }

                        GlassButton(
                            text = if (isSyncing) "جارٍ المزامنة..." else "مزامنة البيانات الآن",
                            icon = Icons.Default.CloudUpload,
                            gradient = GlassAccentBlueGradient,
                            enabled = !isSyncing,
                            onClick = { viewModel.syncAllErpDataToServer() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sync_all_to_cloud_button")
                        )

                        GlassButton(
                            text = if (isSyncing) "جارٍ استعادة البيانات..." else "استعادة البيانات من السيرفر",
                            icon = Icons.Default.CloudDownload,
                            gradient = GlassAccentGreenGradient,
                            enabled = !isSyncing,
                            onClick = { viewModel.restoreAllErpDataFromServer() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("restore_from_cloud_button")
                        )

                        GlassButton(
                            text = "النسخ الاحتياطي وتصدير البيانات",
                            icon = Icons.Default.Backup,
                            onClick = { showBackupDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Database Integrity & Diagnostics Check (consolidated - no duplicate)
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "فحص سلامة وتطابق البيانات",
                                color = PmpCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            GlassBadge(text = "مطابق وسليم", color = PmpGreen)
                        }
                        Text(text = healthStatus, color = TextWhite, fontSize = 12.5.sp)

                        GlassButton(
                            text = "فحص تطابق السجلات الآن",
                            icon = Icons.Default.Verified,
                            gradient = GlassAccentGoldGradient,
                            onClick = {
                                healthStatus = "تم التحقق: جميع السجلات متطابقة (${userAccounts.size} حساب، ${movements.size} حركة مالية، ${invoices.size} فاتورة، ${products.size} صنف)."
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        GlassButton(
                            text = "سجل تتبع العمليات والمزامنة",
                            icon = Icons.Default.Analytics,
                            gradient = PmpLogoSwooshGradient,
                            onClick = { showDiagnosticDashboard = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_open_diagnostic_button")
                        )
                    }
                }
            }

            // Audit Log (visible inside Admin System Check tab)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "سجل العمليات (${auditLogs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    if (auditLogs.size > 5) {
                        TextButton(onClick = { showAllAuditLogs = !showAllAuditLogs }) {
                            Text(
                                text = if (showAllAuditLogs) "عرض أقل" else "عرض الكل (${auditLogs.size})",
                                color = PmpCyan,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            val displayedLogs = if (showAllAuditLogs) auditLogs else auditLogs.take(5)
            items(displayedLogs, key = { it.id }) { log ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(text = log.detail, color = TextWhite, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${log.action} • ${log.username}",
                                color = PmpCyan,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            text = dateFormat.format(Date(log.timestamp)),
                            color = TextSubtle,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Wipe / Reset server records (Admin only)
            item {
                GlassCard(modifier = Modifier.fillMaxWidth(), borderColor = PmpRedLight.copy(alpha = 0.5f)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = PmpRedLight)
                            Text(
                                text = "تصفير سجلات النظام",
                                color = PmpRedLight,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        GlassButton(
                            text = "تصفير وحذف جميع السجلات من السيرفر",
                            icon = Icons.Default.DeleteSweep,
                            gradient = PmpBrandRedGradient,
                            onClick = { showServerWipeDangerDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_wipe_server_button")
                        )
                    }
                }
            }
        }
    }

    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text(text = "ملخص النسخة الاحتياطية", color = PmpCyan, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "إجمالي السجلات المحفوظة:", color = TextWhite, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "• ${cashboxes.size} خزن وحسابات", color = TextMuted)
                    Text(text = "• ${movements.size} حركات مالية مسجلة", color = TextMuted)
                    Text(text = "• ${invoices.size} فواتير بيع", color = TextMuted)
                    Text(text = "• ${customers.size} عملاء في الدليل", color = TextMuted)
                    Text(text = "• ${products.size} أصناف بالمخازن", color = TextMuted)
                }
            },
            confirmButton = {
                GlassButton(text = "حسناً", onClick = { showBackupDialog = false })
            },
            containerColor = PmpNavySurface
        )
    }

    if (showUpdateInstallDialog) {
        val targetApk = downloadedApkFile
        val updateName = availableUpdate?.versionName ?: "الجديد"
        val hasInstallPermission = viewModel.canRequestPackageInstalls(targetApk)

        AlertDialog(
            onDismissRequest = { showUpdateInstallDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Verified, contentDescription = null, tint = PmpGreen)
                    Text(text = "تثبيت التحديث الجديد", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "تم تنزيل التحديث بنجاح وهو جاهز للتثبيت مع الحفاظ على جميع البيانات.",
                        color = TextWhite,
                        fontSize = 13.sp
                    )
                    if (!hasInstallPermission) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "تنبيه: يتطلب أندرويد تفعيل خيار 'تثبيت التطبيقات غير المعروفة' لهذا التطبيق مرة واحدة فقط قبل التثبيت.",
                            color = PmpGold,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    text = if (hasInstallPermission) "بدء التثبيت الآن" else "تفعيل إذن التثبيت من الإعدادات",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        if (!hasInstallPermission) {
                            viewModel.openInstallPermissionSettings(context)
                        } else {
                            showUpdateInstallDialog = false
                            viewModel.launchApkInstallation(context, targetApk)
                        }
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showUpdateInstallDialog = false }) {
                    Text("إلغاء", color = TextMuted)
                }
            },
            containerColor = PmpNavySurface
        )
    }

    if (showDiagnosticDashboard) {
        SyncDiagnosticDashboardDialog(
            viewModel = viewModel,
            onDismiss = { showDiagnosticDashboard = false }
        )
    }

    if (showTransactionDiagnosticsDialog) {
        TransactionDiagnosticsDialog(
            traces = transactionTraces,
            onClear = { viewModel.clearTransactionTraces() },
            onWipeServer = { showServerWipeDangerDialog = true },
            onDismiss = { showTransactionDiagnosticsDialog = false }
        )
    }

    if (showServerWipeDangerDialog) {
        ServerWipeDangerDialog(
            onConfirmWithPassword = { password, onError ->
                viewModel.verifyAndWipeAllServerData(
                    managerPassword = password,
                    onAuthError = onError,
                    onComplete = { ok, _ ->
                        if (ok) {
                            showServerWipeDangerDialog = false
                            showTransactionDiagnosticsDialog = false
                        }
                    }
                )
            },
            onDismiss = { showServerWipeDangerDialog = false }
        )
    }
}
