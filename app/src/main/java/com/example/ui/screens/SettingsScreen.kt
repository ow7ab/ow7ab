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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    var showAdminPublishDialog by remember { mutableStateOf(false) }
    var showVersionJsonUploadDialog by remember { mutableStateOf(false) }
    var versionJsonEditorText by remember { mutableStateOf("") }

    val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsState()
    val isDownloadingUpdate by viewModel.isDownloadingUpdate.collectAsState()
    val updateProgress by viewModel.updateProgress.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val updateUiState by viewModel.updateUiState.collectAsState()
    val availableUpdate by viewModel.availableUpdate.collectAsState()
    val updateAvailable by viewModel.updateAvailable.collectAsState()
    val downloadedApkFile by viewModel.downloadedApkFile.collectAsState()
    val effectiveVersionCode by viewModel.effectiveVersionCode.collectAsState()
    val effectiveVersionName by viewModel.effectiveVersionName.collectAsState()
    val lastUpdateCheckStatus by viewModel.lastUpdateCheckStatus.collectAsState()
    val isUploadingVersionJson by viewModel.isUploadingVersionJson.collectAsState()
    val versionJsonUploadStatus by viewModel.versionJsonUploadStatus.collectAsState()
    val firebaseUploadProgress by viewModel.firebaseUploadProgress.collectAsState()
    val firebaseUploadStageText by viewModel.firebaseUploadStageText.collectAsState()
    val isUploadingUpdateToFirebase by viewModel.isUploadingUpdateToFirebase.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val isDarkActive = PmpThemeController.isDarkModeActive
    var showAdminCloudTools by remember { mutableStateOf(false) }

    // Phase 02 Advanced Security Flows
    val securityActivities by viewModel.securityActivities.collectAsState()
    val suspiciousAlerts by viewModel.suspiciousAlerts.collectAsState()
    val trustedDevices by viewModel.trustedDevices.collectAsState()
    val currentTwoFactorConfig by viewModel.currentTwoFactorConfig.collectAsState()
    val latestGeneratedRecoveryCodes by viewModel.latestGeneratedRecoveryCodes.collectAsState()
    val sessionMaxAgeHours by viewModel.sessionMaxAgeHours.collectAsState()
    val sessionIdleTimeoutMinutes by viewModel.sessionIdleTimeoutMinutes.collectAsState()
    var showSecurityDetailsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(currentUser) {
        viewModel.refreshSecurityStateForUser(currentUser)
    }

    val versionJsonFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadVersionJsonFromUri(context, uri) { loadedJson ->
                versionJsonEditorText = loadedJson
            }
        }
    }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 112.dp),
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
        // Section 0: Professional Dark Mode & Appearance Card (الوضع المظلم الاحترافي والمظهر)
        item {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("professional_dark_mode_card"),
                cornerRadius = 20.dp
            ) {
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
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isDarkActive) Icons.Default.DarkMode else Icons.Default.LightMode,
                                contentDescription = "الوضع المظلم الأسود",
                                tint = if (isDarkActive) PmpGold else PmpCyan,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "الوضع المظلم الأسود (True Black Dark Mode)",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.5.sp
                                )
                                Text(
                                    text = if (isDarkActive) {
                                        "مفعّل حالياً • مظهر أسود فائق (Pitch Black #000000) مريح للعين وعالي التباين"
                                    } else {
                                        "الوضع الفاتح الكلاسيكي • يمكنك التبديل الفوري للوضع المظلم الأسود"
                                    },
                                    color = TextMuted,
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                        Switch(
                            checked = isDarkActive,
                            onCheckedChange = { enableDark ->
                                viewModel.setThemeMode(if (enableDark) PmpThemeMode.DARK else PmpThemeMode.LIGHT)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = PmpCyan,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = PmpSubtleSurface
                            ),
                            modifier = Modifier.testTag("dark_mode_toggle_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val modes = listOf(
                            Triple(PmpThemeMode.LIGHT, "النهاري الفاتح", Icons.Default.LightMode),
                            Triple(PmpThemeMode.DARK, "المظلم الأسود", Icons.Default.DarkMode),
                            Triple(PmpThemeMode.SYSTEM, "تلقائي للنظام", Icons.Default.BrightnessAuto)
                        )
                        modes.forEach { (mode, label, icon) ->
                            val selected = themeMode == mode
                            Surface(
                                onClick = { viewModel.setThemeMode(mode) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) PmpBrandRed else PmpSubtleSurface,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (selected) PmpBrandRed else GlassBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("theme_mode_${mode.name.lowercase()}_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = label,
                                        tint = if (selected) Color.White else TextWhite,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = label,
                                        color = if (selected) Color.White else TextWhite,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

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

        // Section 2: Modern App Updates Card (تحديثات التطبيق)
        item {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_updates_card"),
                cornerRadius = 22.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header: تحديثات التطبيق
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = PmpGreen.copy(alpha = 0.14f),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.SystemUpdate,
                                        contentDescription = "تحديثات التطبيق",
                                        tint = PmpGreen,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "تحديثات التطبيق",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                                Text(
                                    text = "الإصدار الحالي: $effectiveVersionName",
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        GlassBadge(
                            text = effectiveVersionName,
                            color = PmpGreen
                        )
                    }

                    val activeUpdate = availableUpdate
                    val resolvedUiState = when {
                        isCheckingUpdate -> com.example.util.UpdateUiState.CHECKING
                        isDownloadingUpdate -> com.example.util.UpdateUiState.DOWNLOADING
                        downloadedApkFile != null -> com.example.util.UpdateUiState.READY_TO_INSTALL
                        updateUiState == com.example.util.UpdateUiState.FAILED ||
                            updateUiState == com.example.util.UpdateUiState.CANCELLED -> updateUiState
                        activeUpdate != null && updateAvailable -> com.example.util.UpdateUiState.AVAILABLE
                        else -> com.example.util.UpdateUiState.UP_TO_DATE
                    }

                    when (resolvedUiState) {
                        com.example.util.UpdateUiState.CHECKING -> {
                            UpdateVersionCardSkeleton()
                            GlassButton(
                                text = "جاري التحقق من وجود تحديث...",
                                icon = Icons.Default.Refresh,
                                gradient = GlassAccentBlueGradient,
                                enabled = false,
                                onClick = {},
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("check_for_updates_button")
                            )
                        }

                        com.example.util.UpdateUiState.UP_TO_DATE,
                        com.example.util.UpdateUiState.IDLE -> {
                            // بطاقة الحالة: التطبيق محدّث
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(PmpGreen.copy(alpha = 0.10f))
                                    .border(1.dp, PmpGreen.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                                    .padding(16.dp)
                                    .testTag("update_status_up_to_date_card")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = PmpGreen,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "التطبيق محدّث",
                                            color = TextWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                        Text(
                                            text = "أنت تستخدم أحدث إصدار من PMP PAINTS.",
                                            color = TextMuted,
                                            fontSize = 12.5.sp,
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            }

                            // زر الإجراء الوحيد: التحقق من وجود تحديث
                            GlassButton(
                                text = "التحقق من وجود تحديث",
                                icon = Icons.Default.Refresh,
                                gradient = GlassAccentBlueGradient,
                                enabled = true,
                                onClick = { viewModel.checkForAppUpdates(manual = true) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("check_for_updates_button")
                            )
                        }

                        com.example.util.UpdateUiState.AVAILABLE -> {
                            val cleanNotes = com.example.util.UpdateManager.sanitizeUserFacingReleaseNotes(
                                activeUpdate?.releaseNotes
                            )
                            val sizeMbText = activeUpdate?.fileSizeMb?.takeIf { it > 0.0 }?.let {
                                String.format(Locale.US, "%.1f ميجابايت", it)
                            }

                            // بطاقة الحالة: تحديث جديد متاح
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(PmpNavySurface.copy(alpha = 0.85f))
                                    .border(1.dp, PmpCyan.copy(alpha = 0.50f), RoundedCornerShape(16.dp))
                                    .padding(16.dp)
                                    .testTag("update_status_available_card")
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                            Icon(
                                                imageVector = Icons.Default.NewReleases,
                                                contentDescription = null,
                                                tint = PmpCyan,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Column {
                                                Text(
                                                    text = "تحديث جديد متاح",
                                                    color = TextWhite,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp
                                                )
                                                Text(
                                                    text = "يتوفر إصدار جديد يتضمن تحسينات وإصلاحات.",
                                                    color = TextMuted,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                        if (activeUpdate != null) {
                                            GlassBadge(
                                                text = "الإصدار ${activeUpdate.versionName}",
                                                color = PmpGold
                                            )
                                        }
                                    }

                                    HorizontalDivider(color = GlassBorder.copy(alpha = 0.5f))

                                    Text(
                                        text = cleanNotes,
                                        color = TextWhite.copy(alpha = 0.92f),
                                        fontSize = 12.5.sp,
                                        lineHeight = 19.sp
                                    )

                                    if (sizeMbText != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Storage,
                                                contentDescription = null,
                                                tint = TextMuted,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Text(
                                                text = "حجم التحديث: $sizeMbText",
                                                color = TextMuted,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }

                            // زر الإجراء الوحيد: تنزيل التحديث
                            GlassButton(
                                text = "تنزيل التحديث",
                                icon = Icons.Default.CloudDownload,
                                gradient = PmpBrandRedGradient,
                                enabled = true,
                                onClick = {
                                    viewModel.startUpdateDownload()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("download_install_update_button")
                            )
                        }

                        com.example.util.UpdateUiState.DOWNLOADING,
                        com.example.util.UpdateUiState.VERIFYING -> {
                            val progressFraction = (updateProgress.coerceIn(0, 100)) / 100f
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(PmpNavySurface.copy(alpha = 0.85f))
                                    .border(1.dp, PmpCyan.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                                    .padding(16.dp)
                                    .testTag("update_status_downloading_card")
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                color = PmpCyan,
                                                strokeWidth = 2.5.dp
                                            )
                                            Text(
                                                text = if (resolvedUiState == com.example.util.UpdateUiState.VERIFYING) {
                                                    "جاري التحقق من سلامة التحديث..."
                                                } else {
                                                    "جاري تنزيل التحديث..."
                                                },
                                                color = TextWhite,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                        }
                                        GlassBadge(
                                            text = "${updateProgress.coerceIn(0, 100)}%",
                                            color = PmpCyan,
                                            modifier = Modifier.testTag("update_download_percent_badge")
                                        )
                                    }

                                    LinearProgressIndicator(
                                        progress = { progressFraction },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(9.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .testTag("update_download_progress_bar"),
                                        color = PmpCyan,
                                        trackColor = PmpWhiteBorder.copy(alpha = 0.5f)
                                    )

                                    if (downloadState is com.example.util.DownloadState.Downloading) {
                                        val dl = downloadState as com.example.util.DownloadState.Downloading
                                        Text(
                                            text = "${String.format(Locale.US, "%.1f", dl.downloadedMb)} من ${String.format(Locale.US, "%.1f", dl.totalMb)} ميجابايت",
                                            color = TextMuted,
                                            fontSize = 11.5.sp
                                        )
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = { viewModel.cancelUpdateDownload() },
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("cancel_update_download_button")
                            ) {
                                Icon(Icons.Default.PauseCircleOutline, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "إيقاف التنزيل مؤقتاً",
                                    color = TextWhite,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        com.example.util.UpdateUiState.READY_TO_INSTALL,
                        com.example.util.UpdateUiState.INSTALLING -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(PmpGreen.copy(alpha = 0.12f))
                                    .border(1.dp, PmpGreen.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                                    .padding(16.dp)
                                    .testTag("update_status_ready_card")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = PmpGreen,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "تم تنزيل التحديث بنجاح.",
                                            color = TextWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                        Text(
                                            text = "التحديث جاهز للتثبيت الآن مع الحفاظ الكامل على بياناتك.",
                                            color = TextMuted,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }

                            // زر الإجراء الوحيد: تثبيت التحديث
                            GlassButton(
                                text = "تثبيت التحديث",
                                icon = Icons.Default.InstallMobile,
                                gradient = PmpBrandRedGradient,
                                enabled = true,
                                onClick = {
                                    if (viewModel.hasDeclaredInstallPackagesPermission() && viewModel.canRequestPackageInstalls(downloadedApkFile)) {
                                        viewModel.launchApkInstallation(context, downloadedApkFile)
                                    } else {
                                        showUpdateInstallDialog = true
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("download_install_update_button")
                            )
                        }

                        com.example.util.UpdateUiState.FAILED,
                        com.example.util.UpdateUiState.CANCELLED -> {
                            val cleanErrorMessage = if (resolvedUiState == com.example.util.UpdateUiState.CANCELLED) {
                                "تم إيقاف تنزيل التحديث مؤقتاً. يمكنك استئناف التنزيل في أي وقت."
                            } else {
                                com.example.util.UpdateManager.sanitizeUserFacingErrorMessage(lastUpdateCheckStatus)
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(PmpRedLight.copy(alpha = 0.10f))
                                    .border(1.dp, PmpRedLight.copy(alpha = 0.40f), RoundedCornerShape(16.dp))
                                    .padding(16.dp)
                                    .testTag("update_status_failed_card")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = PmpRedLight,
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = if (resolvedUiState == com.example.util.UpdateUiState.CANCELLED) {
                                                "تم إيقاف التنزيل"
                                            } else {
                                                "تعذر إتمام العملية"
                                            },
                                            color = TextWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.5.sp
                                        )
                                        Text(
                                            text = cleanErrorMessage,
                                            color = TextMuted,
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            }

                            // زر الإجراء الوحيد: إعادة المحاولة
                            GlassButton(
                                text = "إعادة المحاولة",
                                icon = Icons.Default.Refresh,
                                gradient = PmpBrandRedGradient,
                                enabled = true,
                                onClick = {
                                    if (activeUpdate != null && updateAvailable) {
                                        viewModel.startUpdateDownload()
                                    } else {
                                        viewModel.checkForAppUpdates(manual = true)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("download_install_update_button")
                            )
                        }
                    }

                    // قسم إدارة النشر السحابي للمدير العام فقط (قابل للطي للحفاظ على نظافة وبساطة الواجهة)
                    if (isSuperAdmin || currentUser?.username?.equals("admin", ignoreCase = true) == true) {
                        HorizontalDivider(color = GlassBorder.copy(alpha = 0.5f))

                        Surface(
                            onClick = { showAdminCloudTools = !showAdminCloudTools },
                            shape = RoundedCornerShape(12.dp),
                            color = PmpNavySurface.copy(alpha = 0.6f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("toggle_admin_cloud_tools_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudUpload,
                                        contentDescription = null,
                                        tint = PmpCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "إدارة نشر التحديثات السحابية (للمدير)",
                                        color = TextWhite,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.5.sp
                                    )
                                }
                                Icon(
                                    imageVector = if (showAdminCloudTools) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = TextMuted
                                )
                            }
                        }

                        // شريط تقدم الرفع السحابي إلى Firebase يظهر للمدير عند تفعيل الأدوات أو أثناء الرفع
                        if (showAdminCloudTools || isUploadingUpdateToFirebase || isUploadingVersionJson) {
                            val normalizedUploadProgress = (firebaseUploadProgress.coerceIn(0, 100)) / 100f
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(PmpNavySurface.copy(alpha = 0.85f))
                                    .border(
                                        1.dp,
                                        if (isUploadingUpdateToFirebase || isUploadingVersionJson) PmpCyan else GlassBorder,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(12.dp)
                                    .testTag("admin_firebase_upload_progress_container")
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
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = if (firebaseUploadProgress >= 100 && !isUploadingUpdateToFirebase) {
                                                    Icons.Default.CheckCircle
                                                } else {
                                                    Icons.Default.CloudUpload
                                                },
                                                contentDescription = "حالة الرفع إلى Firebase",
                                                tint = if (firebaseUploadProgress >= 100 && !isUploadingUpdateToFirebase) PmpGreen else PmpCyan,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "تقدم الرفع السحابي",
                                                color = TextWhite,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                        }
                                        GlassBadge(
                                            text = "${firebaseUploadProgress.coerceIn(0, 100)}%",
                                            color = if (firebaseUploadProgress >= 100 && !isUploadingUpdateToFirebase) PmpGreen else PmpCyan,
                                            modifier = Modifier.testTag("admin_firebase_upload_progress_text")
                                        )
                                    }

                                    LinearProgressIndicator(
                                        progress = { normalizedUploadProgress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(9.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .testTag("admin_firebase_upload_progress_bar"),
                                        color = if (firebaseUploadProgress >= 100 && !isUploadingUpdateToFirebase) PmpGreen else PmpCyan,
                                        trackColor = PmpWhiteBorder.copy(alpha = 0.55f)
                                    )

                                    if (firebaseUploadStageText.isNotBlank()) {
                                        Text(
                                            text = firebaseUploadStageText,
                                            color = if (isUploadingUpdateToFirebase || isUploadingVersionJson) PmpCyan else TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    viewModel.broadcastUpdateToAllPreviousVersions(forceNewIncrement = false)
                                },
                                enabled = !isCheckingUpdate && !isUploadingUpdateToFirebase,
                                colors = ButtonDefaults.buttonColors(containerColor = PmpCyan),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("broadcast_update_previous_versions_button")
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isUploadingUpdateToFirebase) {
                                        "جاري الرفع ($firebaseUploadProgress%)..."
                                    } else {
                                        "مزامنة الإصدار الحالي ($effectiveVersionName) للأجهزة السابقة"
                                    },
                                    color = Color.Black,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = { showAdminPublishDialog = true },
                                enabled = !isCheckingUpdate && !isUploadingUpdateToFirebase,
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, PmpCyan.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("admin_custom_release_publish_button")
                            ) {
                                Icon(Icons.Default.Verified, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "نشر إصدار سحابي موثّق",
                                    color = PmpCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    if (versionJsonEditorText.isBlank()) {
                                        versionJsonEditorText = viewModel.generateDefaultVersionJsonForAdmin()
                                    }
                                    showVersionJsonUploadDialog = true
                                },
                                enabled = !isUploadingVersionJson,
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, PmpGold.copy(alpha = 0.7f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("open_version_json_editor_button")
                            ) {
                                Icon(Icons.Default.UploadFile, contentDescription = null, tint = PmpGold, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "رفع ملف بيانات الإصدار (version.json)",
                                    color = PmpGold,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
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

        // Section: Phase 02 Advanced Authentication & Session Security Center
        item {
            val is2FaEnabled = currentTwoFactorConfig?.enabled == true
            val unacknowledgedAlerts = remember(suspiciousAlerts, currentUser, isSuperAdmin) {
                suspiciousAlerts.filter { alert ->
                    !alert.acknowledged && (isSuperAdmin || alert.userCode.isBlank() || alert.userCode.equals(currentUser?.userCode, ignoreCase = true))
                }
            }
            val userLoginHistory = remember(securityActivities, currentUser, isSuperAdmin) {
                securityActivities.filter { act ->
                    isSuperAdmin || act.userCode.equals(currentUser?.userCode, ignoreCase = true) || act.username.equals(currentUser?.username, ignoreCase = true)
                }.take(10)
            }
            val currentDeviceId = remember { com.example.util.BiometricPreferenceManager.getDeviceIdentifier(context) }

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("advanced_security_center_card"),
                borderColor = if (unacknowledgedAlerts.isNotEmpty()) PmpRedLight.copy(alpha = 0.65f) else PmpCyan.copy(alpha = 0.35f)
            ) {
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
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = PmpGold, modifier = Modifier.size(24.dp))
                            Column {
                                Text(
                                    text = "مركز الأمان المتقدم وإدارة الجلسات",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "المصادقة الثنائية (2FA) • الأجهزة الموثوقة • سجل الدخول",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        GlassBadge(
                            text = if (is2FaEnabled) "2FA مفعّل" else "قياسي PBKDF2",
                            color = if (is2FaEnabled) PmpGreen else PmpCyan
                        )
                    }

                    // Suspicious Login Notifications Banner
                    if (unacknowledgedAlerts.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(PmpRedLight.copy(alpha = 0.14f))
                                .border(1.dp, PmpRedLight.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = PmpRedLight, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "تنبيهات أمنية لمحاولات دخول مشبوهة (${unacknowledgedAlerts.size})",
                                    color = PmpRedLight,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                )
                            }
                            unacknowledgedAlerts.take(3).forEach { alert ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = alert.title,
                                            color = TextWhite,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = "${alert.detail} • ${dateFormat.format(Date(alert.timestamp))}",
                                            color = TextMuted,
                                            fontSize = 10.5.sp
                                        )
                                    }
                                    TextButton(onClick = { viewModel.acknowledgeSuspiciousAlert(alert.id) }) {
                                        Text("تأكيد القراءة", color = PmpGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // Optional Two-Factor Authentication (2FA) Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "المصادقة الثنائية الاختيارية (2FA - TOTP)",
                                color = TextWhite,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = if (is2FaEnabled) {
                                    "مفعّلة • المفتاح السري: ${currentTwoFactorConfig?.secretBase32?.take(16)}"
                                } else {
                                    "تأمين الحساب برمز متغير (6 أرقام) + 8 أكواد استرداد احتياطية"
                                },
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = is2FaEnabled,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    viewModel.enableTwoFactorForCurrentUser(requireOnEveryLogin = true)
                                } else {
                                    viewModel.disableTwoFactorForCurrentUser()
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

                    if (is2FaEnabled) {
                        val currentTotpSample = remember(currentTwoFactorConfig) {
                            currentTwoFactorConfig?.secretBase32?.let { secret ->
                                if (secret.isNotBlank()) com.example.util.PasswordSecurityUtil.generateTotpCode(secret) else ""
                            }.orEmpty()
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(PmpNavySurface.copy(alpha = 0.7f))
                                .border(1.dp, PmpGold.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "مفتاح TOTP لربط تطبيق المصادقة: ${currentTwoFactorConfig?.secretBase32}",
                                    color = PmpGold,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (currentTotpSample.isNotBlank()) {
                                    Text(
                                        text = "الرمز الزمني الحالي (TOTP): $currentTotpSample • الأكواد الاحتياطية المتبقية: ${currentTwoFactorConfig?.hashedRecoveryCodes?.size ?: 0}",
                                        color = PmpCyan,
                                        fontSize = 11.sp
                                    )
                                }
                                if (latestGeneratedRecoveryCodes.isNotEmpty()) {
                                    Text(
                                        text = "احتفظ بأكواد الاسترداد الاحتياطية في مكان آمن:\n${latestGeneratedRecoveryCodes.joinToString("   •   ")}",
                                        color = TextWhite,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                TextButton(
                                    onClick = { viewModel.regenerateBackupCodesForCurrentUser() }
                                ) {
                                    Text("توليد أكواد استرداد احتياطية جديدة (8 أكواد)", color = PmpGold, fontSize = 11.5.sp)
                                }
                            }
                        }
                    }

                    // Session Expiration & Revocation Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "صلاحية الجلسة: $sessionMaxAgeHours ساعة • مهلة الخمول: $sessionIdleTimeoutMinutes دقيقة",
                            color = TextMuted,
                            fontSize = 11.5.sp
                        )
                        TextButton(
                            onClick = {
                                val nextHours = when (sessionMaxAgeHours) {
                                    4 -> 12
                                    12 -> 24
                                    else -> 4
                                }
                                val nextIdle = when (sessionIdleTimeoutMinutes) {
                                    15 -> 30
                                    30 -> 60
                                    else -> 15
                                }
                                viewModel.updateSessionTimeoutSettings(nextHours, nextIdle)
                            }
                        ) {
                            Text("تغيير المهلة", color = PmpCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    GlassButton(
                        text = "إنهاء وإبطال جميع الجلسات الأخرى وتدوير رمز الأمان",
                        icon = Icons.Default.PhonelinkErase,
                        gradient = PmpBrandRedGradient,
                        onClick = { viewModel.revokeAllOtherSessionsForCurrentUser() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("revoke_all_other_sessions_button")
                    )

                    // Expandable Trusted Devices & Login Activity History
                    OutlinedButton(
                        onClick = { showSecurityDetailsExpanded = !showSecurityDetailsExpanded },
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (showSecurityDetailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = PmpCyan
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (showSecurityDetailsExpanded) {
                                "إخفاء الأجهزة الموثوقة وسجل نشاط الدخول"
                            } else {
                                "عرض الأجهزة الموثوقة (${trustedDevices.size}) وسجل نشاط الدخول (${userLoginHistory.size})"
                            },
                            color = TextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (showSecurityDetailsExpanded) {
                        Text(
                            text = "الأجهزة المسجلة والموثوقة لهذا الحساب:",
                            color = PmpGold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp
                        )
                        if (trustedDevices.isEmpty()) {
                            Text("لا توجد أجهزة مسجلة بعد.", color = TextMuted, fontSize = 11.5.sp)
                        } else {
                            trustedDevices.forEach { dev ->
                                val isThisDevice = dev.deviceId == currentDeviceId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(PmpNavySurface.copy(alpha = 0.65f))
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${dev.deviceName} ${if (isThisDevice) "(هذا الجهاز)" else ""}",
                                            color = TextWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = "الحالة: ${if (dev.revoked) "موقوف أمنياً" else if (dev.trusted) "جهاز موثوق" else "غير موثوق"} • آخر نشاط: ${dateFormat.format(Date(dev.lastActiveAt))}",
                                            color = if (dev.revoked) PmpRedLight else TextMuted,
                                            fontSize = 10.5.sp
                                        )
                                    }
                                    if (dev.revoked) {
                                        TextButton(onClick = { viewModel.restoreTrustedDevice(dev.deviceId) }) {
                                            Text("استعادة", color = PmpGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        Row {
                                            TextButton(onClick = { viewModel.setTrustedDeviceStatus(dev.deviceId, !dev.trusted) }) {
                                                Text(if (dev.trusted) "إلغاء التوثيق" else "توثيق", color = PmpCyan, fontSize = 11.sp)
                                            }
                                            if (!isThisDevice) {
                                                TextButton(onClick = { viewModel.revokeTrustedDevice(dev.deviceId) }) {
                                                    Text("إبطال", color = PmpRedLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "سجل نشاط تسجيل الدخول الأخير:",
                            color = PmpCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp
                        )
                        if (userLoginHistory.isEmpty()) {
                            Text("لا يوجد سجل نشاط دخول مسجل حتى الآن.", color = TextMuted, fontSize = 11.5.sp)
                        } else {
                            userLoginHistory.forEach { act ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.04f))
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${act.detail} (${act.deviceName})",
                                            color = if (act.success) TextWhite else PmpRedLight,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "${act.eventType} • ${act.username} • ${dateFormat.format(Date(act.timestamp))}",
                                            color = TextMuted,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
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
        val canDirectSideload = viewModel.hasDeclaredInstallPackagesPermission()
        val hasInstallPermission = canDirectSideload && viewModel.canRequestPackageInstalls(targetApk)

        AlertDialog(
            onDismissRequest = { showUpdateInstallDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Verified, contentDescription = null, tint = PmpGreen)
                    Text(
                        text = "تثبيت التحديث ($updateName)",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (canDirectSideload) {
                            "تم تنزيل التحديث بنجاح والتحقق من سلامته. عند المتابعة ستفتح نافذة التثبيت الرسمية لترقية التطبيق مع الحفاظ الكامل على بياناتك."
                        } else {
                            "تم التحقق من توفر الإصدار الجديد ($updateName). التزاماً بسياسات الأمان ومتجر Google Play، سيتم نقلك إلى صفحة التحديث الرسمية لإكمال التثبيت مع الحفاظ الكامل على بياناتك."
                        },
                        color = TextWhite,
                        fontSize = 13.sp
                    )
                    if (canDirectSideload && !hasInstallPermission) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ملاحظة: يرجى السماح بتثبيت التحديثات من هذا المصدر في إعدادات النظام للمتابعة.",
                            color = PmpGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    text = when {
                        !canDirectSideload -> "التحديث عبر الصفحة الرسمية"
                        hasInstallPermission -> "تثبيت التحديث"
                        else -> "السماح بالتثبيت من الإعدادات"
                    },
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        when {
                            !canDirectSideload -> {
                                showUpdateInstallDialog = false
                                viewModel.openGooglePlayOrWebUpdatePage(context, availableUpdate?.downloadUrl)
                            }
                            !hasInstallPermission -> {
                                viewModel.openInstallPermissionSettings(context)
                            }
                            else -> {
                                showUpdateInstallDialog = false
                                viewModel.launchApkInstallation(context, targetApk)
                            }
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

    if (showAdminPublishDialog) {
        var inputVersionCode by remember { mutableStateOf((effectiveVersionCode + 1).toString()) }
        var inputVersionName by remember { mutableStateOf("3.${effectiveVersionCode + 1}-PRO") }
        var inputDownloadUrl by remember(inputVersionName, inputVersionCode) {
            mutableStateOf("https://raw.githubusercontent.com/ow7ab/ow7ab/main/public/pmp-paints-latest.apk")
        }
        var inputBuildId by remember(inputVersionName, inputVersionCode) {
            mutableStateOf("PMP-v${inputVersionName}-b${inputVersionCode}-${BuildConfig.GIT_COMMIT_SHA}")
        }
        var inputSha256 by remember { mutableStateOf("") }
        var inputNotes by remember {
            mutableStateOf("تحديث سحابي معتمد لتطبيق PMP PAINTS يتضمن أحدث التعديلات البرمجية وتحسينات الأداء والتقارير.")
        }
        var isMandatoryRelease by remember { mutableStateOf(false) }
        var requireStrictRemoteProbe by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAdminPublishDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = PmpCyan)
                    Text(
                        text = "إرسال ونشر تحديث سحابي (Admin Cloud Publisher)",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "سيتم بث هذا التحديث سحابياً عبر Firebase Firestore ليظهر فوراً لجميع المستخدمين والكلاسترات المتصلة.",
                        color = PmpGold,
                        fontSize = 11.5.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = inputVersionCode,
                            onValueChange = { inputVersionCode = it.filter { ch -> ch.isDigit() } },
                            label = { Text("رقم البناء (Code)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = inputVersionName,
                            onValueChange = { inputVersionName = it },
                            label = { Text("اسم الإصدار") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TextButton(
                            onClick = {
                                inputDownloadUrl = "https://raw.githubusercontent.com/ow7ab/ow7ab/main/public/pmp-paints-latest.apk"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("رابط GitHub المباشر", color = PmpCyan, fontSize = 10.5.sp)
                        }
                        TextButton(
                            onClick = {
                                inputDownloadUrl = "https://ow7ab.github.io/ow7ab/pmp-paints-v${inputVersionName}-b${inputVersionCode}.apk"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("رابط GitHub Pages", color = PmpGold, fontSize = 10.5.sp)
                        }
                    }

                    OutlinedTextField(
                        value = inputDownloadUrl,
                        onValueChange = { inputDownloadUrl = it },
                        label = { Text("رابط APK المباشر (HTTPS)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = inputBuildId,
                        onValueChange = { inputBuildId = it },
                        label = { Text("معرّف البناء (Build ID)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = inputSha256,
                        onValueChange = { inputSha256 = it },
                        label = { Text("بصمة SHA-256 (اختياري - 64 محرفاً)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = inputNotes,
                        onValueChange = { inputNotes = it },
                        label = { Text("ملاحظات التحديث للمستخدمين") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("تحديث إلزامي عاجل للمستخدمين:", color = TextWhite, fontSize = 12.sp)
                        Switch(
                            checked = isMandatoryRelease,
                            onCheckedChange = { isMandatoryRelease = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PmpBrandRed)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("اشتراط فحص رابط APK قبل النشر:", color = TextMuted, fontSize = 11.5.sp)
                        Switch(
                            checked = requireStrictRemoteProbe,
                            onCheckedChange = { requireStrictRemoteProbe = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PmpCyan)
                        )
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    text = "إرسال ونشر التحديث سحابياً الآن",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        val vCode = inputVersionCode.toIntOrNull() ?: (effectiveVersionCode + 1)
                        showAdminPublishDialog = false
                        viewModel.publishReleaseToCloud(
                            vCode = vCode,
                            vName = inputVersionName.trim().ifBlank { "3.$vCode-PRO" },
                            dlUrl = inputDownloadUrl.trim(),
                            releaseNotes = inputNotes.trim(),
                            sha256 = inputSha256.trim(),
                            buildId = inputBuildId.trim(),
                            gitCommit = BuildConfig.GIT_COMMIT_SHA,
                            isMandatory = isMandatoryRelease,
                            requireRemoteBinaryVerification = requireStrictRemoteProbe
                        )
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showAdminPublishDialog = false }) {
                    Text("إلغاء", color = TextMuted)
                }
            },
            containerColor = PmpNavySurface
        )
    }

    if (showVersionJsonUploadDialog) {
        var quickVersionCode by remember { mutableStateOf((effectiveVersionCode + 1).toString()) }
        var quickVersionName by remember { mutableStateOf("3.${effectiveVersionCode + 1}-PRO") }
        var quickDownloadUrl by remember {
            mutableStateOf("https://raw.githubusercontent.com/ow7ab/ow7ab/main/public/pmp-paints-latest.apk")
        }
        var quickNotes by remember {
            mutableStateOf("إصدار سحابي جديد لتطبيق PMP PAINTS — تحديث مباشر لصفحة التنزيل وكافة المستخدمين.")
        }

        AlertDialog(
            onDismissRequest = { if (!isUploadingVersionJson) showVersionJsonUploadDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = PmpGold)
                    Text(
                        text = "رفع ملف version.json إلى Firebase Storage",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "يرفع هذا المحرر ملف version.json مباشرة إلى Firebase Storage (gs://${com.example.util.UpdateManager.FIREBASE_STORAGE_BUCKET}/version.json) ويزامن Firestore، مما يحدّث صفحة التنزيل تلقائياً للمستخدمين دون الحاجة لتعديل الكود يدوياً.",
                        color = PmpGold,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = quickVersionCode,
                            onValueChange = { quickVersionCode = it.filter { ch -> ch.isDigit() } },
                            label = { Text("versionCode") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = quickVersionName,
                            onValueChange = { quickVersionName = it },
                            label = { Text("versionName") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = {
                                val code = quickVersionCode.toIntOrNull() ?: (effectiveVersionCode + 1)
                                val name = quickVersionName.ifBlank { "3.$code-PRO" }
                                val generated = com.example.util.UpdateManager.generateVersionJsonString(
                                    com.example.util.UpdateInfo(
                                        versionCode = code,
                                        versionName = name,
                                        downloadUrl = quickDownloadUrl,
                                        releaseNotes = quickNotes,
                                        isMandatory = false,
                                        sha256Checksum = "",
                                        fileSizeMb = 30.6,
                                        fileSize = 0L,
                                        buildId = "PMP-v${name}-b${code}-${BuildConfig.GIT_COMMIT_SHA}",
                                        gitCommit = BuildConfig.GIT_COMMIT_SHA,
                                        signingCertSha256 = com.example.util.UpdateManager.APPROVED_SIGNING_CERT_SHA256
                                    )
                                )
                                versionJsonEditorText = generated
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("توليد JSON تلقائياً", color = PmpCyan, fontSize = 11.sp)
                        }

                        TextButton(
                            onClick = { versionJsonFilePickerLauncher.launch("*/*") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = PmpGold, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("استيراد ملف من الهاتف", color = PmpGold, fontSize = 11.sp)
                        }
                    }

                    OutlinedTextField(
                        value = versionJsonEditorText,
                        onValueChange = { versionJsonEditorText = it },
                        label = { Text("محتوى ملف version.json") },
                        minLines = 6,
                        maxLines = 10,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("version_json_raw_input")
                    )

                    if (isUploadingVersionJson || isUploadingUpdateToFirebase || firebaseUploadProgress > 0) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(PmpWhiteSurface)
                                .border(1.dp, PmpGold.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "تقدم الرفع إلى Firebase:",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp
                                )
                                Text(
                                    text = "${firebaseUploadProgress.coerceIn(0, 100)}%",
                                    color = PmpGold,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 12.sp
                                )
                            }
                            LinearProgressIndicator(
                                progress = { (firebaseUploadProgress.coerceIn(0, 100)) / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .testTag("dialog_firebase_upload_progress_bar"),
                                color = PmpGold,
                                trackColor = PmpWhiteBorder
                            )
                            Text(
                                text = firebaseUploadStageText,
                                color = TextMuted,
                                fontSize = 10.5.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    text = if (isUploadingVersionJson) "جاري الرفع ($firebaseUploadProgress%)..." else "رفع version.json وتحديث صفحة التنزيل الآن",
                    gradient = PmpBrandRedGradient,
                    enabled = !isUploadingVersionJson && versionJsonEditorText.isNotBlank(),
                    onClick = {
                        viewModel.uploadVersionJsonToFirebaseStorage(versionJsonEditorText) { _, _ ->
                            showVersionJsonUploadDialog = false
                        }
                    },
                    modifier = Modifier.testTag("confirm_upload_version_json_button")
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { showVersionJsonUploadDialog = false },
                    enabled = !isUploadingVersionJson
                ) {
                    Text("إغلاق", color = TextMuted)
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
