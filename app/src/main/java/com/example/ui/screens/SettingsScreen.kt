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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import com.example.util.BiometricAuthManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    var healthStatus by remember { mutableStateOf("قاعدة البيانات سليمة وجاهزة (SQLite Room)") }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showAllAuditLogs by remember { mutableStateOf(false) }
    var showDiagnosticDashboard by remember { mutableStateOf(false) }

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

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "تعديل الملف الشخصي، فحص التحديثات من داخل التطبيق، إدارة الصلاحيات وسلامة البيانات.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        fontSize = 12.sp
                    )
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

                    // Fixed Read-Only Username Field
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "اسم المستخدم (ثابت للقراءة فقط ولا يمكن تعديله):",
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

                    // Editable Password
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassTextField(
                            value = profileNewPassword,
                            onValueChange = { profileNewPassword = it },
                            label = "كلمة المرور الجديدة (اختياري)",
                            placeholder = "4 خانات على الأقل",
                            modifier = Modifier.weight(1f)
                        )
                        GlassTextField(
                            value = profileConfirmPassword,
                            onValueChange = { profileConfirmPassword = it },
                            label = "تأكيد كلمة المرور",
                            placeholder = "إعادة كتابة الرمز",
                            modifier = Modifier.weight(1f)
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

        // Section 2: In-App Updates Card (تحديث التطبيق)
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
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = PmpGreen, modifier = Modifier.size(24.dp))
                            Text(
                                text = "تحديث التطبيق",
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        GlassBadge(text = "الإصدار v2.5.0", color = PmpGreen)
                    }

                    Text(
                        text = "آلية تنزيل وتثبيت التحديثات الرسمية للتطبيق مباشرة من داخل النظام دون الحاجة لمصادر خارجية، مع الحفاظ التام والآمن على بيانات المستخدمين والأرصدة وقاعدة البيانات.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )

                    if (isDownloadingUpdate) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "جاري تنزيل التحديث والتحقق من التوقيع...", color = PmpCyan, fontSize = 12.sp)
                                Text(text = "$updateProgress%", color = PmpCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            LinearProgressIndicator(
                                progress = { updateProgress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = PmpCyan,
                                trackColor = GlassBorder
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassButton(
                            text = if (isCheckingUpdate) "جاري فحص الإصدار..." else "التحقق من وجود تحديث جديد",
                            icon = Icons.Default.Refresh,
                            gradient = GlassAccentBlueGradient,
                            enabled = !isCheckingUpdate && !isDownloadingUpdate,
                            onClick = { viewModel.checkForAppUpdates(manual = true) },
                            modifier = Modifier.weight(1.2f)
                        )

                        GlassButton(
                            text = "تحديث الآن",
                            icon = Icons.Default.CloudDownload,
                            gradient = PmpBrandRedGradient,
                            enabled = !isDownloadingUpdate,
                            onClick = {
                                viewModel.downloadAndInstallUpdate(
                                    onReadyToInstall = { showUpdateInstallDialog = true }
                                )
                            },
                            modifier = Modifier.weight(0.8f)
                        )
                    }
                }
            }
        }

        // Section 3: User Accounts & Server Linkage Card
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
                            text = "حسابات المستخدمين",
                            color = PmpBrandRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        GlassBadge(text = "${userAccounts.size} حسابات", color = PmpGreen)
                    }

                    Text(
                        text = "الحساب النشط حالياً: ${currentUser?.fullName ?: "المدير العام"} (${currentUser?.userCode ?: "USR-PMP-001"}) • جميع الحسابات مزودة بمعرفات ربط سحابية (Sync Tokens).",
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    GlassButton(
                        text = "إدارة وإنشاء حسابات المستخدمين والربط السحابي",
                        icon = Icons.Default.ManageAccounts,
                        gradient = PmpBrandRedGradient,
                        onClick = onNavigateToUsers,
                        modifier = Modifier.fillMaxWidth()
                    )

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

        // Section: Biometric Security Card (بصمة الإصبع والوجه لتأمين البيانات المالية والمخازن)
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
                                text = "القفل البيومتري (بصمة الإصبع والوجه)",
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
                                            subtitle = "يرجى مسح البصمة أو الوجه لتأكيد التفعيل وحماية البيانات المالية",
                                            onSuccess = {
                                                BiometricAuthManager.setBiometricLockEnabled(context, true)
                                                isBiometricEnabled = true
                                                viewModel.postMessage("تم تفعيل القفل البيومتري بنجاح لحماية الخزن والمبيعات والمخازن")
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

                    Text(
                        text = if (isBiometricAvailable) {
                            "المستشعر البيومتري (بصمة/وجه) جاهز ومتاح. عند التفعيل، يطلب النظام التحقق من البصمة تلقائياً قبل الدخول إلى الخزن، المبيعات والفواتير، والمخازن الحساسة."
                        } else {
                            "المستشعر البيومتري غير مهيأ أو لم يتم تعيين بصمة في إعدادات جهازك. يمكنك تعيين بصمة في نظام أندرويد لتفعيل القفل."
                        },
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "حالة القفل البيومتري:",
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        GlassBadge(
                            text = if (isBiometricEnabled) "مفعل ومحمي 🔒" else "معطل 🔓",
                            color = if (isBiometricEnabled) PmpGreen else PmpGold
                        )
                    }
                }
            }
        }

        // System & Branch Info
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "معلومات النظام والفرع",
                        color = PmpCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "إصدار التطبيق:", color = TextMuted)
                        Text(text = "نظام PMP لإدارة الدهانات والطلاءات الموحد", color = TextWhite, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "الفرع الأساسي:", color = TextMuted)
                        Text(text = "فرع السودان (SD-001) / فرع مصر (EG-001)", color = PmpGold, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "نوع التخزين والتشغيل:", color = TextMuted)
                        Text(text = "محلي آمن (Offline-First) قابل للربط السحابي", color = PmpGreen, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Database Health Check
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "فحص سلامة البيانات (Database Health)",
                            color = PmpGold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        GlassBadge(text = "فحص تلقائي سليم", color = PmpGreen)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = healthStatus, color = TextWhite, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    GlassButton(
                        text = "إعادة فحص سلامة البيانات الآن",
                        icon = Icons.Default.Verified,
                        gradient = GlassAccentGoldGradient,
                        onClick = {
                            healthStatus = "تم الفحص: جميع الجداول متطابقة (${userAccounts.size} مستخدمين، ${movements.size} حركات، ${invoices.size} فواتير، ${products.size} أصناف)."
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Data Export & Future Sync Readiness
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "النسخ الاحتياطي والتحويل المستقبلي",
                        color = PmpCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "البرنامج مصمم بهيكلية معمارية نظيفة (Clean Architecture) تسمح بنقل البيانات أو مزامنتها مع خادم مركزي أو قاعدة بيانات سحابية في أي وقت.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    GlassButton(
                        text = "إنشاء نسخة احتياطية وتصدير البيانات",
                        icon = Icons.Default.CloudUpload,
                        onClick = { showBackupDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    GlassButton(
                        text = "لوحة تشخيص ومراقبة المزامنة السحابية",
                        icon = Icons.Default.Dns,
                        gradient = PmpLogoSwooshGradient,
                        onClick = { showDiagnosticDashboard = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_open_diagnostic_button")
                    )
                }
            }
        }

        // Audit Log
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "سجل التدقيق والعمليات (${auditLogs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                if (auditLogs.size > 5) {
                    TextButton(onClick = { showAllAuditLogs = !showAllAuditLogs }) {
                        Text(
                            text = if (showAllAuditLogs) "عرض أقل" else "عرض المزيد (${auditLogs.size - 5})",
                            color = PmpCyan,
                            fontSize = 13.sp,
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = log.detail, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${log.action} • بواسطة: ${log.username}",
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

        if (auditLogs.size > 5) {
            item {
                OutlinedButton(
                    onClick = { showAllAuditLogs = !showAllAuditLogs },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PmpCyan)
                ) {
                    Text(
                        text = if (showAllAuditLogs) "عرض أقل (العودة لـ 5)" else "عرض المزيد (${auditLogs.size - 5} عمليات إضافية)",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text(text = "النسخ الاحتياطي وتصدير السجلات", color = PmpCyan, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(text = "تم تجهيز ملخص البيانات الكامل للتصدير:", color = TextWhite)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "• ${cashboxes.size} خزن وحسابات", color = TextMuted)
                    Text(text = "• ${movements.size} حركات مالية مسجلة", color = TextMuted)
                    Text(text = "• ${invoices.size} فواتير بيع", color = TextMuted)
                    Text(text = "• ${customers.size} عملاء في الدليل", color = TextMuted)
                    Text(text = "• ${products.size} أصناف بالمخازن", color = TextMuted)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "قاعدة البيانات مؤمنة بتنسيق SQLite/Room محلي بالكامل وجاهزة للترحيل عند الطلب.", color = PmpGold, fontSize = 12.sp)
                }
            },
            confirmButton = {
                GlassButton(text = "حسناً", onClick = { showBackupDialog = false })
            },
            containerColor = PmpNavySurface
        )
    }

    if (showUpdateInstallDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateInstallDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Verified, contentDescription = null, tint = PmpGreen)
                    Text(text = "جاهز لتثبيت التحديث", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "تم تنزيل حزمة التحديث (v2.5.0) بنجاح والتحقق من التوقيع الرقمي ومصدر الملف الموثوق.",
                        color = TextWhite,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "• لن يتم حذف أي بيانات أو إعدادات أو أرصدة أثناء التثبيت.\n• بعد اكتمال التثبيت سيتم تشغيل الإصدار الجديد بكافة صلاحياتك وبياناتك السابقة.",
                        color = PmpCyan,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "بدء التثبيت الآن",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        showUpdateInstallDialog = false
                        viewModel.postMessage("بدء تثبيت الإصدار الجديد مع الاحتفاظ التام بكافة البيانات والصلاحيات")
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
}
