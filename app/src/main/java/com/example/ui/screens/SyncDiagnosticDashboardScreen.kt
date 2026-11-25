package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.credentials.CredentialManager
import com.example.data.remote.CloudErrorLogEntry
import com.example.data.remote.ServiceProbeId
import com.example.data.remote.ServiceProbeResult
import com.example.data.remote.SyncDiagnosticRecord
import com.example.isSuperAdminAccount
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats a timestamp into an intuitive, human-friendly Arabic relative time phrase.
 */
fun formatRelativeTimeArabic(timestamp: Long?): String {
    if (timestamp == null || timestamp == 0L) return "لم تتم بعد"
    val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        seconds < 30 -> "الآن (منذ بضع ثوانٍ)"
        seconds < 60 -> "منذ أقل من دقيقة"
        minutes == 1L -> "منذ دقيقة واحدة"
        minutes == 2L -> "منذ دقيقتين"
        minutes in 3..10 -> "منذ $minutes دقائق"
        minutes < 60 -> "منذ $minutes دقيقة"
        hours == 1L -> "منذ ساعة واحدة"
        hours == 2L -> "منذ ساعتين"
        hours in 3..10 -> "منذ $hours ساعات"
        hours < 24 -> "منذ $hours ساعة"
        days == 1L -> "منذ يوم واحد"
        else -> "منذ $days أيام"
    }
}

/**
 * Formats a timestamp into an exact date-time string.
 */
fun formatExactDateTime(timestamp: Long?): String {
    if (timestamp == null || timestamp == 0L) return "غير متوفر"
    val sdf = SimpleDateFormat("yyyy-MM-dd • hh:mm:ss a", Locale.US)
    return sdf.format(Date(timestamp))
}

/**
 * Full Diagnostic Dashboard View that tracks synchronization status, timestamps of last
 * successful sync, and any pending errors, keeping a clean, bright, high-contrast UI consistent
 * with the existing light PMP application theme.
 */
@Composable
fun SyncDiagnosticDashboardView(
    viewModel: PmpViewModel,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
    onClose: (() -> Unit)? = null
) {
    val isSyncing by viewModel.isSyncing.collectAsState()
    val cloudSyncState by viewModel.cloudSyncState.collectAsState()
    val serverHealthReport by viewModel.serverHealthReport.collectAsState()
    val clusterCode by viewModel.serverClusterCode.collectAsState()
    val syncDiagnostics by viewModel.syncDiagnostics.collectAsState()
    val lastSyncTime by viewModel.lastSuccessfulSyncTime.collectAsState()
    val pendingErrors by viewModel.pendingSyncErrors.collectAsState()
    val structuredErrors by viewModel.structuredCloudErrors.collectAsState()
    val preflightResult by viewModel.lastPreflightResult.collectAsState()
    val serviceProbes by viewModel.serviceProbes.collectAsState()
    val comprehensiveReport by viewModel.comprehensiveDiagnosticReport.collectAsState()
    val operationTrackingRecords by viewModel.operationTrackingRecords.collectAsState()
    val pendingOperationsCount by viewModel.pendingOperationsCount.collectAsState()
    val activeGuardKeys by viewModel.activeGuardKeys.collectAsState()

    val invoices by viewModel.invoices.collectAsState()
    val movements by viewModel.movements.collectAsState()
    val products by viewModel.products.collectAsState()
    val userAccounts by viewModel.userAccounts.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val unifiedSync by viewModel.unifiedSyncSnapshot.collectAsState()
    val reconReport by viewModel.crossPlatformReconciliationReport.collectAsState()
    val isSuperAdmin = currentUser.isSuperAdminAccount()

    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    var opStatusFilter by remember { mutableStateOf("ALL") }
    var opSearchQuery by remember { mutableStateOf("") }
    var showWipeConfirmDialog by remember { mutableStateOf(false) }
    var isRunningRecon by remember { mutableStateOf(false) }
    val totalErrorsBadge = maxOf(pendingErrors.size, structuredErrors.size)
    val tabs = listOf(
        "نظرة عامة والوحدات",
        "متابعة العمليات (${pendingOperationsCount})",
        "الأخطاء المعلقة ($totalErrorsBadge)",
        "سجل المزامنة (${syncDiagnostics.size})"
    )

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    if (!isSuperAdmin) {
        Box(
            modifier = modifier.fillMaxSize().background(GlassBackgroundBrush).padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(backgroundColor = Color.White) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "PMP SYSTEM HEALTH CENTER — صلاحية Admin فقط",
                        color = PmpRed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "مركز مراقبة صحة النظام ومطابقة الأرصدة مخصص حصرياً لحساب Admin ومخفي عن باقي الحسابات.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }
        return
    }

    var firebaseUser by remember {
        mutableStateOf(runCatching { Firebase.auth.currentUser }.getOrNull())
    }
    var isGoogleSignInLoading by remember { mutableStateOf(false) }
    var googleSignInError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val authInstance = runCatching { Firebase.auth }.getOrNull()
        val listener = FirebaseAuth.AuthStateListener { auth ->
            firebaseUser = auth.currentUser
        }
        authInstance?.addAuthStateListener(listener)
        onDispose {
            authInstance?.removeAuthStateListener(listener)
        }
    }

    val isGoogleSignedIn = firebaseUser != null
    val isHealthMeasured = serverHealthReport != null
    val isOnline = isGoogleSignedIn && serverHealthReport?.isOnline == true
    val numberFormat = remember { NumberFormat.getNumberInstance(Locale.US) }

    // Pulsing animation for live sync indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isSyncing) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    OneUiPullDownContainer(
        modifier = modifier
            .fillMaxSize()
            .background(GlassBackgroundBrush),
        pageTitle = "لوحة تشخيص ومراقبة المزامنة",
        screenKey = "sync_diagnostic_dashboard"
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .testTag("sync_dashboard_list"),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Header Card with Solid Crisp White Background
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("diagnostic_dashboard_header"),
                    cornerRadius = 24.dp,
                    backgroundColor = Color.White
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
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
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(PmpBrandNavy),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Dns,
                                        contentDescription = null,
                                        tint = PmpCyan,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "PMP SYSTEM HEALTH CENTER • لوحة تشخيص ومراقبة المزامنة",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = PmpBrandNavy,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = "حالة المزامنة: ${unifiedSync.status.code} (${unifiedSync.labelAr}) • Admin Only",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            if (onClose != null) {
                                IconButton(
                                    onClick = onClose,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFF1F5F9))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "إغلاق",
                                        tint = PmpBrandNavy
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Live Status Banner (Real measured state only - no assumed success)
                        val hasErrors = pendingErrors.isNotEmpty() || structuredErrors.isNotEmpty()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    when {
                                        isSyncing -> Color(0xFFEFF6FF)
                                        !isGoogleSignedIn -> Color(0xFFFFFBEB)
                                        hasErrors -> Color(0xFFFEF2F2)
                                        !isHealthMeasured -> Color(0xFFFFFBEB)
                                        isOnline -> Color(0xFFF0FDF4)
                                        else -> Color(0xFFFEF2F2)
                                    }
                                )
                                .border(
                                    1.dp,
                                    when {
                                        isSyncing -> PmpCyan.copy(alpha = 0.5f)
                                        !isGoogleSignedIn -> PmpGold.copy(alpha = 0.6f)
                                        hasErrors -> PmpRed.copy(alpha = 0.5f)
                                        !isHealthMeasured -> PmpGold.copy(alpha = 0.5f)
                                        isOnline -> PmpGreen.copy(alpha = 0.5f)
                                        else -> PmpRed.copy(alpha = 0.5f)
                                    },
                                    RoundedCornerShape(14.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp)
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
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .scale(pulseScale)
                                            .clip(CircleShape)
                                            .background(
                                                when {
                                                    isSyncing -> PmpCyan
                                                    !isGoogleSignedIn -> PmpGold
                                                    hasErrors -> PmpRed
                                                    !isHealthMeasured -> PmpGold
                                                    isOnline -> PmpGreen
                                                    else -> PmpRed
                                                }
                                            )
                                    )
                                    Text(
                                        text = when {
                                            isSyncing -> "جارٍ المزامنة السحابية لحظياً مع السيرفر..."
                                            !isGoogleSignedIn -> "الوضع المحلي نشط — اربط المزامنة السحابية اختيارياً بضغطة واحدة"
                                            hasErrors -> "يوجد $totalErrorsBadge أخطاء معلقة تتطلب المراجعة"
                                            !isHealthMeasured -> "بانتظار فحص السيرفر المباشر (لم يُقَس بعد)"
                                            isOnline -> "متصل تلقائياً بالسحابة ومزامن بالكامل مع قاعدة البيانات"
                                            else -> serverHealthReport?.message ?: "السيرفر غير متاح حالياً"
                                        },
                                        color = PmpBrandNavy,
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                GlassBadge(
                                    text = when {
                                        isSyncing -> "مزامنة جارية"
                                        !isGoogleSignedIn -> "وضع محلي"
                                        !isHealthMeasured -> "غير مقاس"
                                        isOnline -> "متصل تلقائياً"
                                        else -> "غير متاح"
                                    },
                                    color = when {
                                        isSyncing -> PmpCyan
                                        !isGoogleSignedIn -> PmpGold
                                        !isHealthMeasured -> PmpGold
                                        isOnline -> PmpGreen
                                        else -> PmpRed
                                    }
                                )
                            }
                        }

                        // Simple One-Tap Optional Cloud Link when not yet connected
                        if (!isGoogleSignedIn) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFFF8FAFC))
                                    .border(1.dp, PmpCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "ربط المزامنة السحابية بضغطة واحدة",
                                    color = PmpBrandNavy,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "التطبيق يعمل محلياً بكامل مزاياه دون أي قيود. اضغط مرة واحدة لربط الجهاز بالسحابة ومزامنة الأرصدة مع نسخة الويب تلقائياً بعد ذلك.",
                                    color = TextMuted,
                                    fontSize = 11.5.sp,
                                    lineHeight = 17.sp
                                )
                                googleSignInError?.let { err ->
                                    Text(
                                        text = err,
                                        color = PmpRed,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                                GlassButton(
                                    text = "ربط المزامنة السحابية الآن",
                                    icon = Icons.Default.CloudSync,
                                    gradient = GlassAccentBlueGradient,
                                    isLoading = isGoogleSignInLoading,
                                    loadingText = "جارٍ الربط السحابي...",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("diagnostic_google_signin_button"),
                                    onClick = {
                                        if (!isGoogleSignInLoading) {
                                            isGoogleSignInLoading = true
                                            googleSignInError = null
                                            onGoogleSignInClicked(
                                                context = context,
                                                credentialManager = credentialManager,
                                                onAuthSuccess = {
                                                    isGoogleSignInLoading = false
                                                    firebaseUser = runCatching { Firebase.auth.currentUser }.getOrNull()
                                                    viewModel.onFirebaseCloudSessionActivated(isErpSessionAuthenticated = true)
                                                    viewModel.checkServerHealth()
                                                },
                                                onAuthError = { msg ->
                                                    isGoogleSignInLoading = false
                                                    googleSignInError = msg
                                                },
                                                scope = coroutineScope,
                                                onAuthCancelled = {
                                                    isGoogleSignInLoading = false
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Tabs
                        GlassTabRow(
                            tabs = tabs,
                            selectedIndex = selectedTab,
                            onTabSelected = { selectedTab = it }
                        )
                    }
                }
            }

            // 4 KPI Summary Cards with Solid Backgrounds & Concise Non-Truncating Arabic Labels
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // KPI 1: Status
                        GlassStatCard(
                            title = "اتصال السيرفر",
                            value = when {
                                isSyncing -> "جارٍ الحفظ"
                                !isGoogleSignedIn -> "بانتظار الدخول"
                                !isHealthMeasured -> "غير مقاس"
                                isOnline -> "متصل ومزامن"
                                else -> "غير متاح"
                            },
                            unit = "Google Cloud",
                            subtitle = "الكلاستر: ${clusterCode.take(18)}",
                            accentColor = when {
                                isSyncing -> PmpCyan
                                !isGoogleSignedIn -> PmpGold
                                !isHealthMeasured -> PmpGold
                                isOnline -> PmpGreen
                                else -> PmpRed
                            },
                            icon = when {
                                isSyncing -> Icons.Default.Sync
                                !isGoogleSignedIn -> Icons.Default.LockClock
                                !isHealthMeasured -> Icons.Default.HourglassEmpty
                                isOnline -> Icons.Default.CloudDone
                                else -> Icons.Default.CloudOff
                            },
                            modifier = Modifier.weight(1f)
                        )

                        // KPI 2: Timestamp of Last Successful Sync
                        GlassStatCard(
                            title = "آخر مزامنة",
                            value = formatRelativeTimeArabic(lastSyncTime),
                            unit = if (lastSyncTime != null && lastSyncTime != 0L) "توقيت محلي" else null,
                            subtitle = formatExactDateTime(lastSyncTime),
                            accentColor = PmpGold,
                            icon = Icons.Default.Schedule,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // KPI 3: Pending Errors
                        GlassStatCard(
                            title = "الأخطاء المعلقة",
                            value = "$totalErrorsBadge خطأ",
                            unit = if (totalErrorsBadge == 0) "مستقر" else "للمراجعة",
                            subtitle = if (totalErrorsBadge == 0) "لا توجد أخطاء معلقة إطلاقاً" else "انقر لتبويب الأخطاء للمعالجة",
                            accentColor = if (totalErrorsBadge == 0) PmpGreen else PmpRed,
                            icon = if (totalErrorsBadge == 0) Icons.Default.VerifiedUser else Icons.Default.ReportProblem,
                            modifier = Modifier.weight(1f)
                        )

                        // KPI 4: Ping / Response Time (Never show stale latency when unauthenticated)
                        val measuredLatency = serverHealthReport?.responseTimeMs?.takeIf { it > 0L && isGoogleSignedIn }
                        val pingText = when {
                            !isGoogleSignedIn -> "بانتظار الدخول"
                            measuredLatency != null -> "$measuredLatency ms"
                            else -> "غير مقاس"
                        }
                        GlassStatCard(
                            title = "زمن الاستجابة",
                            value = pingText,
                            unit = if (measuredLatency != null) "مللي ثانية" else null,
                            subtitle = if (isGoogleSignedIn) "خوادم Firestore (Source.SERVER)" else "يتطلب تسجيل دخول Google",
                            accentColor = if (isOnline) PmpBlueLight else PmpGold,
                            icon = Icons.Default.Speed,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Tab Content
            when (selectedTab) {
                0 -> {
                    // Cross-Platform Balance Reconciliation & PDF/Excel Export Hub
                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color.White
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val maskedUid = firebaseUser?.uid?.let {
                                    if (it.length > 8) "${it.take(6)}...${it.takeLast(4)}" else it
                                } ?: "غير مرتبط"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "مطابقة الأرصدة بين المنصات (Web / Desktop / Android)",
                                            color = PmpBrandNavy,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "Firebase Auth: $maskedUid • الحالة الموحدة: ${unifiedSync.status.code} • معلق: ${unifiedSync.pendingWritesCount}",
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                    GlassBadge(
                                        text = unifiedSync.status.code,
                                        color = when (unifiedSync.status) {
                                            com.example.domain.accounting.UnifiedSyncStatus.SYNCED -> PmpGreen
                                            com.example.domain.accounting.UnifiedSyncStatus.SYNCING -> PmpCyan
                                            com.example.domain.accounting.UnifiedSyncStatus.PENDING -> PmpGold
                                            else -> PmpRed
                                        }
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    GlassButton(
                                        text = if (isRunningRecon) "جارٍ المطابقة..." else "تشغيل مطابقة الأرصدة",
                                        icon = Icons.Default.Verified,
                                        gradient = GlassAccentBlueGradient,
                                        enabled = !isRunningRecon,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            isRunningRecon = true
                                            viewModel.runCrossPlatformReconciliation {
                                                isRunningRecon = false
                                            }
                                        }
                                    )
                                    GlassButton(
                                        text = "PDF",
                                        icon = Icons.Default.PictureAsPdf,
                                        gradient = Brush.horizontalGradient(listOf(PmpBrandNavy, Color(0xFF334155))),
                                        modifier = Modifier.width(86.dp),
                                        onClick = {
                                            val rows = mutableListOf(
                                                listOf("المصادقة", "Firebase Auth", maskedUid, unifiedSync.status.code, unifiedSync.labelAr)
                                            )
                                            reconReport?.let { rep ->
                                                listOf("SDG", "EGP", "USD").forEach { cur ->
                                                    rows.add(
                                                        listOf(
                                                            "أرصدة $cur",
                                                            "مركزي: ${rep.centralCurrencyTotals[cur] ?: 0.0}",
                                                            "Android: ${rep.localCurrencyTotals[cur] ?: 0.0}",
                                                            "Ledger: ${rep.ledgerCurrencyTotals[cur] ?: 0.0}",
                                                            rep.lastSyncedTransactionRef
                                                        )
                                                    )
                                                }
                                                rep.anomalies.forEach { a ->
                                                    rows.add(
                                                        listOf(
                                                            "تباين",
                                                            a.affectedAccount,
                                                            "فرق: ${a.varianceAmount} ${a.currency}",
                                                            a.rootCauseSource,
                                                            a.safeRemediationDescriptionAr
                                                        )
                                                    )
                                                }
                                            }
                                            structuredErrors.take(15).forEach { e ->
                                                rows.add(listOf("خطأ مصنف", e.operation, e.collectionPath, e.categoryTitleAr, e.sanitizedMessage))
                                            }
                                            com.example.util.StatementPdfAndExcelExporter.exportHealthReportToPdf(
                                                context = context,
                                                reportTitle = "PMP SYSTEM HEALTH CENTER",
                                                subtitle = "حالة المزامنة: ${unifiedSync.status.code} | Admin: ${currentUser?.username ?: "admin"}",
                                                headers = listOf("القسم", "البند", "القيمة", "المصدر/الحالة", "التفاصيل"),
                                                rows = rows,
                                                shareAfterExport = true
                                            )
                                        }
                                    )
                                    GlassButton(
                                        text = "Excel",
                                        icon = Icons.Default.TableChart,
                                        gradient = Brush.horizontalGradient(listOf(Color(0xFF065F46), Color(0xFF059669))),
                                        modifier = Modifier.width(92.dp),
                                        onClick = {
                                            val rows = mutableListOf(
                                                listOf("المصادقة", "Firebase Auth", maskedUid, unifiedSync.status.code, unifiedSync.labelAr)
                                            )
                                            reconReport?.let { rep ->
                                                listOf("SDG", "EGP", "USD").forEach { cur ->
                                                    rows.add(
                                                        listOf(
                                                            "أرصدة $cur",
                                                            "مركزي: ${rep.centralCurrencyTotals[cur] ?: 0.0}",
                                                            "Android: ${rep.localCurrencyTotals[cur] ?: 0.0}",
                                                            "Ledger: ${rep.ledgerCurrencyTotals[cur] ?: 0.0}",
                                                            rep.lastSyncedTransactionRef
                                                        )
                                                    )
                                                }
                                                rep.anomalies.forEach { a ->
                                                    rows.add(
                                                        listOf(
                                                            "تباين",
                                                            a.affectedAccount,
                                                            "فرق: ${a.varianceAmount} ${a.currency}",
                                                            a.rootCauseSource,
                                                            com.example.util.StatementPdfAndExcelExporter.redactHealthTelemetry(a.safeRemediationDescriptionAr)
                                                        )
                                                    )
                                                }
                                            }
                                            structuredErrors.take(20).forEach { e ->
                                                rows.add(
                                                    listOf(
                                                        "خطأ مصنف",
                                                        e.operation,
                                                        e.collectionPath,
                                                        e.categoryTitleAr,
                                                        com.example.util.StatementPdfAndExcelExporter.redactHealthTelemetry(e.sanitizedMessage)
                                                    )
                                                )
                                            }
                                            com.example.util.StatementPdfAndExcelExporter.exportReportToExcel(
                                                context = context,
                                                reportTitle = "PMP_SYSTEM_HEALTH_CENTER",
                                                subtitle = "حالة المزامنة: ${unifiedSync.status.code} | Admin: ${currentUser?.username ?: "admin"}",
                                                headers = listOf("القسم", "البند", "القيمة", "المصدر/الحالة", "التفاصيل"),
                                                rows = rows,
                                                shareAfterExport = true
                                            )
                                        }
                                    )
                                }

                                reconReport?.let { rep ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (rep.overallStatus == "MATCHED") Color(0xFFF0FDF4) else Color(0xFFFEF2F2))
                                            .padding(10.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = rep.summaryAr,
                                                color = if (rep.overallStatus == "MATCHED") PmpGreen else PmpRed,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                            Text(
                                                text = "SDG مركزي: ${rep.centralCurrencyTotals["SDG"] ?: 0.0} | محلي: ${rep.localCurrencyTotals["SDG"] ?: 0.0} • EGP مركزي: ${rep.centralCurrencyTotals["EGP"] ?: 0.0} | محلي: ${rep.localCurrencyTotals["EGP"] ?: 0.0}",
                                                color = PmpBrandNavy,
                                                fontSize = 11.sp
                                            )
                                            rep.anomalies.take(5).forEach { a ->
                                                Text(
                                                    text = "• ${a.affectedAccount}: فرق ${a.varianceAmount} (${a.rootCauseSource}) — ${a.safeRemediationDescriptionAr}",
                                                    color = PmpRed,
                                                    fontSize = 10.5.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Tab 0: Overview & Modules Health
                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color.White
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
                                    Text(
                                        text = "تتبع تطابق الوحدات وسجلات النظام",
                                        color = PmpBrandNavy,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    val healthBadgeText = when {
                                        !isHealthMeasured -> "بانتظار الفحص"
                                        isOnline && totalErrorsBadge == 0 -> "متصل ومقاس"
                                        isOnline -> "متصل • تنبيه أخطاء"
                                        else -> "غير متاح"
                                    }
                                    val healthBadgeColor = when {
                                        !isHealthMeasured -> PmpGold
                                        isOnline && totalErrorsBadge == 0 -> PmpGreen
                                        else -> PmpRed
                                    }
                                    GlassBadge(text = healthBadgeText, color = healthBadgeColor)
                                }
                                Text(
                                    text = "مقارنة فعلية بين السجلات المحفوظة محلياً (Room DB) وسجلات السيرفر المقاسة مباشرة عبر Source.SERVER:",
                                    color = TextMuted,
                                    fontSize = 11.5.sp,
                                    lineHeight = 17.sp
                                )

                                // Module Item 1: Invoices
                                ModuleSyncItemCard(
                                    title = "فواتير المبيعات والعملاء",
                                    localCount = invoices.size,
                                    cloudCount = serverHealthReport?.cloudInvoicesCount,
                                    unitLabel = "فاتورة",
                                    icon = Icons.Default.ReceiptLong,
                                    accentColor = PmpCyan
                                )

                                // Module Item 2: Cashbox Movements
                                ModuleSyncItemCard(
                                    title = "حركات الخزن والنقدية",
                                    localCount = movements.size,
                                    cloudCount = serverHealthReport?.cloudMovementsCount,
                                    unitLabel = "حركة خزنة",
                                    icon = Icons.Default.AccountBalanceWallet,
                                    accentColor = PmpGreen
                                )

                                // Module Item 3: Inventory & Products
                                ModuleSyncItemCard(
                                    title = "المخازن والمنتجات والمصنع",
                                    localCount = products.size,
                                    cloudCount = serverHealthReport?.cloudProductsCount,
                                    unitLabel = "صنف مسجل",
                                    icon = Icons.Default.Inventory2,
                                    accentColor = PmpGold
                                )

                                // Module Item 4: Users & Permissions
                                ModuleSyncItemCard(
                                    title = "حسابات المستخدمين والصلاحيات",
                                    localCount = userAccounts.size,
                                    cloudCount = serverHealthReport?.cloudUsersCount,
                                    unitLabel = "حساب موظف",
                                    icon = Icons.Default.ManageAccounts,
                                    accentColor = PmpBlueLight
                                )
                            }
                        }
                    }

                    // Comprehensive Diagnostics & 5 Read-Only Service Probes Card
                    item {
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("service_probes_card"),
                            backgroundColor = Color.White
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
                                    Text(
                                        text = "اختبارات خدمات السيرفر (للقراءة فقط)",
                                        color = PmpBrandNavy,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.5.sp
                                    )
                                    GlassBadge(
                                        text = comprehensiveReport?.let {
                                            if (it.overallOk) "جاهز بالكامل" else "بحاجة للمراجعة"
                                        } ?: "Read-Only Probes",
                                        color = comprehensiveReport?.let {
                                            if (it.overallOk) PmpGreen else PmpRed
                                        } ?: PmpCyan
                                    )
                                }

                                GlassButton(
                                    text = "فحص تشخيصي شامل (بدون كتابة)",
                                    icon = Icons.Default.VerifiedUser,
                                    gradient = GlassAccentBlueGradient,
                                    isLoading = isSyncing,
                                    loadingText = "جارٍ فحص الخدمات الخمس...",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("comprehensive_diagnostic_btn"),
                                    onClick = { viewModel.runComprehensiveServerDiagnostics() }
                                )

                                // Preflight Check Status Banner
                                val pf = preflightResult
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            when {
                                                pf == null -> Color(0xFFF8FAFC)
                                                pf.passed -> Color(0xFFF0FDF4)
                                                else -> Color(0xFFFEF2F2)
                                            }
                                        )
                                        .border(
                                            1.dp,
                                            when {
                                                pf == null -> Color(0xFFE2E8F0)
                                                pf.passed -> PmpGreen.copy(alpha = 0.4f)
                                                else -> PmpRed.copy(alpha = 0.4f)
                                            },
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(12.dp)
                                        .testTag("preflight_status_card")
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = if (pf == null) {
                                                "فحص الجاهزية المسبق (Preflight): لم يتم تشغيله بعد"
                                            } else {
                                                "فحص الجاهزية المسبق: ${pf.message}"
                                            },
                                            color = PmpBrandNavy,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        if (pf != null) {
                                            Text(
                                                text = "الدور المعتمد: ${pf.roleSummary} • Auth: ${if (pf.firebaseAuthPresent) "نشط" else "غير مسجل"} • الشبكة: ${if (pf.networkOnline) "متصلة" else "منقطعة"}",
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }

                                val probeButtonTags = mapOf(
                                    ServiceProbeId.NETWORK to "probe_network_btn",
                                    ServiceProbeId.FIREBASE_AUTH to "probe_auth_btn",
                                    ServiceProbeId.FIRESTORE to "probe_firestore_btn",
                                    ServiceProbeId.RBAC_POLICY to "probe_rbac_btn",
                                    ServiceProbeId.APP_UPDATES to "probe_updates_btn"
                                )
                                val probeById = serviceProbes.associateBy { it.probeId }

                                ServiceProbeId.entries.forEach { probeId ->
                                    val probeRes: ServiceProbeResult? = probeById[probeId]
                                    val statusColor = when {
                                        probeRes == null -> PmpGold
                                        probeRes.ok -> PmpGreen
                                        else -> PmpRed
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFFF8FAFC))
                                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = probeId.arabicLabel,
                                                color = PmpBrandNavy,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.5.sp
                                            )
                                            Text(
                                                text = probeRes?.detail ?: "بانتظار التشغيل (للقراءة فقط)",
                                                color = if (probeRes?.ok == false) PmpRed else TextMuted,
                                                fontSize = 11.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            GlassBadge(
                                                text = when {
                                                    probeRes == null -> "غير مفحوص"
                                                    probeRes.ok -> "سليم"
                                                    else -> "فشل"
                                                },
                                                color = statusColor
                                            )
                                            OutlinedButton(
                                                onClick = { viewModel.runSingleServiceProbe(probeId) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                                modifier = Modifier
                                                    .heightIn(min = 48.dp)
                                                    .testTag(probeButtonTags[probeId] ?: "probe_${probeId.name}")
                                            ) {
                                                Text("فحص", fontSize = 11.sp, color = PmpBrandNavy)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Environment & Endpoint Info Card
                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color.White
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "بيانات الربط والبيئة السحابية (Endpoint Metadata)",
                                    color = PmpBrandNavy,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )

                                MetadataRow(label = "كود مجموعة السيرفرات (Cluster):", value = clusterCode)
                                MetadataRow(label = "قاعدة بيانات Google Cloud:", value = "ai-studio-android-glassdro-d6052420-cbc0-47dd-8cdd-8caa5e6a7248")
                                MetadataRow(label = "مشروع Google Cloud الرئيسي:", value = "gen-lang-client-0220128746")
                                MetadataRow(label = "بروتوكول التزامن:", value = "Firestore Multi-Node Snapshot Listener")
                                MetadataRow(label = "حل التعارضات (Conflict Resolution):", value = "Last-Timestamp Strict Isolation")
                            }
                        }
                    }

                    // Network Linking & Cross-Border Egypt Hub Node Card
                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color.White
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Hub, contentDescription = null, tint = PmpGold, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "شبكة الربط الدقيق (مصر ⇄ السودان)",
                                            color = PmpBrandNavy,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.5.sp
                                        )
                                    }
                                    GlassBadge(
                                        text = when {
                                            !isHealthMeasured -> "بانتظار القياس"
                                            isOnline -> "الشبكة متصلة"
                                            else -> "الشبكة غير متاحة"
                                        },
                                        color = if (!isHealthMeasured) PmpGold else if (isOnline) PmpGreen else PmpRed
                                    )
                                }

                                Text(
                                    text = "ارتباط سحابي دقيق ولحظي بين مستودعات مصر المركزية ومصنع السودان ومخزن حلفا، مع اعتماد الجنيه المصري (EGP) لكافة الخامات والبضائع.",
                                    color = TextMuted,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp
                                )

                                MetadataRow(label = "العقدة المركزية لمصر:", value = "مستودع ومخازن القاهرة (EGP Hub)")
                                MetadataRow(label = "عقدة التصنيع والإنتاج:", value = "مصنع البوهيات المركزي بالسودان")
                                MetadataRow(label = "عقدة الشحن والترحيل:", value = "مخزن ومكتب وادي حلفا الحدودي")
                                MetadataRow(label = "عملة تقييم البضاعة والخامات:", value = "الجنيه المصري (EGP) معتمد بالكامل")
                                MetadataRow(
                                    label = "حالة دقة الربط بين الشبكات:",
                                    value = if (!isHealthMeasured) "بانتظار فحص السيرفر المباشر" else if (isOnline) "Exact Cross-Network Multi-Device Active" else "غير متصل بالسيرفر"
                                )
                            }
                        }
                    }
                }

                1 -> {
                    // Tab 1: سجل متابعة العمليات (Operation Tracking & Idempotency Verification)
                    item {
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("operation_tracking_header_card"),
                            backgroundColor = Color.White
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "سجل متابعة العمليات ومنع التكرار",
                                        color = PmpBrandNavy,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    GlassButton(
                                        text = "فحص المعلقة",
                                        icon = Icons.Default.Sync,
                                        isLoading = activeGuardKeys.contains("recover_pending_ops"),
                                        loadingText = "جاري التحقق...",
                                        onClick = { viewModel.recoverPendingOperations() },
                                        modifier = Modifier.testTag("operation_tracking_recover_btn")
                                    )
                                }

                                OutlinedTextField(
                                    value = opSearchQuery,
                                    onValueChange = { opSearchQuery = it },
                                    label = { Text("بحث برقم المستند أو المفتاح أو النوع...") },
                                    singleLine = true,
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("operation_tracking_search_input")
                                )

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        "ALL" to "الكل (${operationTrackingRecords.size})",
                                        "PENDING_CONFIRMATION" to "بانتظار التأكيد",
                                        "PROCESSING" to "قيد التنفيذ",
                                        "COMPLETED" to "مكتملة",
                                        "FAILED" to "فشلت"
                                    ).forEach { (code, label) ->
                                        FilterChip(
                                            selected = opStatusFilter == code,
                                            onClick = { opStatusFilter = code },
                                            label = { Text(label, fontSize = 11.5.sp) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val filteredOps = operationTrackingRecords.filter { rec ->
                        val matchesStatus = opStatusFilter == "ALL" || rec.requestStatus == opStatusFilter
                        val q = opSearchQuery.trim()
                        val matchesQuery = q.isBlank() ||
                            rec.idempotencyKey.contains(q, ignoreCase = true) ||
                            rec.documentReference.contains(q, ignoreCase = true) ||
                            rec.operationType.contains(q, ignoreCase = true) ||
                            rec.payloadSummary.contains(q, ignoreCase = true) ||
                            rec.userId.contains(q, ignoreCase = true)
                        matchesStatus && matchesQuery
                    }

                    if (filteredOps.isEmpty()) {
                        item {
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = Color.White
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = null, tint = PmpGreen, modifier = Modifier.size(36.dp))
                                    Text(
                                        text = "لا توجد عمليات مطابقة في سجل المتابعة حالياً.",
                                        color = PmpBrandNavy,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    } else {
                        items(filteredOps, key = { it.idempotencyKey }) { rec ->
                            val statusEnum = com.example.util.IdempotencyRequestStatus.fromCode(rec.requestStatus)
                            val badgeColor = when (statusEnum) {
                                com.example.util.IdempotencyRequestStatus.COMPLETED -> PmpGreen
                                com.example.util.IdempotencyRequestStatus.FAILED,
                                com.example.util.IdempotencyRequestStatus.CANCELLED -> PmpRed
                                com.example.util.IdempotencyRequestStatus.PENDING_CONFIRMATION -> PmpGold
                                else -> PmpCyan
                            }
                            val verifyGuardKey = "verify_op_${rec.idempotencyKey}"
                            val isVerifying = activeGuardKeys.contains(verifyGuardKey)

                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("operation_tracking_card_${rec.idempotencyKey}"),
                                backgroundColor = Color.White,
                                borderColor = badgeColor.copy(alpha = 0.45f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${rec.operationType} • ${rec.documentReference.ifBlank { rec.idempotencyKey.take(12) }}",
                                            color = PmpBrandNavy,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        GlassBadge(text = statusEnum.titleAr, color = badgeColor)
                                    }

                                    if (rec.payloadSummary.isNotBlank()) {
                                        Text(
                                            text = rec.payloadSummary,
                                            color = PmpBrandNavy,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    Text(
                                        text = "المفتاح: ${rec.idempotencyKey} • المنصة: ${rec.platform} • المستخدم: ${rec.userId}",
                                        color = TextMuted,
                                        fontSize = 10.5.sp
                                    )

                                    Text(
                                        text = "توقيت الطلب: ${formatExactDateTime(rec.createdAt)}" +
                                            if (rec.resultReference.isNotBlank()) " • النتيجة: ${rec.resultReference}" else "",
                                        color = TextMuted,
                                        fontSize = 10.5.sp
                                    )

                                    if (rec.errorDetails.isNotBlank()) {
                                        Text(
                                            text = rec.errorDetails,
                                            color = if (statusEnum == com.example.util.IdempotencyRequestStatus.PENDING_CONFIRMATION) PmpGold else PmpRed,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        GlassButton(
                                            text = "التحقق من حالة العملية",
                                            icon = Icons.Default.Verified,
                                            isLoading = isVerifying,
                                            loadingText = "جاري التحقق...",
                                            onClick = { viewModel.verifyOperationStatus(rec.idempotencyKey) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("verify_operation_btn_${rec.idempotencyKey}")
                                        )

                                        if (statusEnum == com.example.util.IdempotencyRequestStatus.FAILED ||
                                            statusEnum == com.example.util.IdempotencyRequestStatus.CANCELLED
                                        ) {
                                            TextButton(
                                                onClick = { viewModel.dismissUnconfirmedOperation(rec.idempotencyKey) },
                                                modifier = Modifier.heightIn(min = 48.dp)
                                            ) {
                                                Text("إزالة", color = TextMuted, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // Tab 2: Pending Errors & Structured Root-Cause Classification Queue
                    if (pendingErrors.isEmpty() && structuredErrors.isEmpty()) {
                        item {
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = Color.White
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(60.dp)
                                            .clip(CircleShape)
                                            .background(PmpGreen.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.VerifiedUser,
                                            contentDescription = null,
                                            tint = PmpGreen,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                    Text(
                                        text = "لا توجد أي أخطاء معلقة في المزامنة",
                                        color = PmpBrandNavy,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = "جميع العمليات الأخيرة اكتملت بنجاح كامل بنسبة 100%. لم يتم رصد أي انقطاع بالشبكة أو تعارض في السجلات أو فقدان للبيانات.",
                                        color = TextMuted,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 18.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    GlassButton(
                                        text = "تشغيل فحص يدوي للتأكد",
                                        icon = Icons.Default.Refresh,
                                        gradient = GlassAccentBlueGradient,
                                        onClick = { viewModel.checkServerHealth() }
                                    )
                                }
                            }
                        }
                    } else {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "قائمة الأخطاء المرصودة (${pendingErrors.size})",
                                    color = PmpRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                TextButton(
                                    onClick = { viewModel.clearAllPendingSyncErrors() }
                                ) {
                                    Icon(Icons.Default.ClearAll, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("مسح جميع الأخطاء", color = TextMuted, fontSize = 12.sp)
                                }
                            }
                        }

                        items(structuredErrors, key = { "struct_${it.id}" }) { sErr: CloudErrorLogEntry ->
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, PmpRed.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                                    .testTag("structured_error_card_${sErr.category.name}"),
                                backgroundColor = Color.White
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = sErr.category.arabicTitle,
                                                color = PmpRed,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                text = "العملية: ${sErr.operation} • المسار: ${sErr.collectionPath}",
                                                color = PmpBrandNavy,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        GlassBadge(
                                            text = when (sErr.category) {
                                                com.example.data.remote.CloudErrorCategory.SECURITY_TAMPER -> "تنبيه تلاعب أمني"
                                                com.example.data.remote.CloudErrorCategory.DUPLICATE_TRANSACTION -> "منع تكرار مالي"
                                                else -> if (sErr.retryable) "قابل للإعادة" else "صلاحية/إعداد"
                                            },
                                            color = when (sErr.category) {
                                                com.example.data.remote.CloudErrorCategory.SECURITY_TAMPER -> PmpRed
                                                com.example.data.remote.CloudErrorCategory.DUPLICATE_TRANSACTION -> PmpGold
                                                else -> if (sErr.retryable) PmpGold else PmpRed
                                            }
                                        )
                                    }

                                    Text(
                                        text = sErr.sanitizedMessage,
                                        color = PmpBrandNavy,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp
                                    )

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFFFFFBEB))
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            text = "الإجراء المقترح: ${sErr.remediationHint}",
                                            color = PmpBrandNavy,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${sErr.formattedTime} • الكود: ${sErr.code} • الجهاز: ${sErr.deviceId.take(14)}",
                                            color = TextMuted,
                                            fontSize = 10.5.sp
                                        )
                                        TextButton(
                                            onClick = { viewModel.clearPendingSyncError(sErr.id) },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text("تجاهل", color = TextMuted, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }

                        items(pendingErrors, key = { it.id }) { err ->
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, PmpRed.copy(alpha = 0.5f), RoundedCornerShape(18.dp)),
                                backgroundColor = Color.White
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = PmpRed, modifier = Modifier.size(18.dp))
                                            Text(
                                                text = "نوع الإجراء: ${err.action}",
                                                color = PmpBrandNavy,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.5.sp
                                            )
                                        }
                                        GlassBadge(text = "فشل المزامنة", color = PmpRed)
                                    }

                                    Text(
                                        text = "تفاصيل الخطأ: ${err.message}",
                                        color = PmpRed,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        lineHeight = 17.sp
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "التوقيت: ${err.formattedTime} • الجهاز: ${err.deviceId.take(14)}",
                                            color = TextMuted,
                                            fontSize = 10.5.sp
                                        )

                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            TextButton(
                                                onClick = { viewModel.clearPendingSyncError(err.id) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.heightIn(min = 48.dp)
                                            ) {
                                                Text("تجاهل", color = TextMuted, fontSize = 11.sp)
                                            }

                                            Button(
                                                onClick = {
                                                    viewModel.clearPendingSyncError(err.id)
                                                    viewModel.syncAllErpDataToServer()
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = PmpCyan),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                modifier = Modifier.heightIn(min = 48.dp)
                                            ) {
                                                Icon(Icons.Default.Replay, contentDescription = null, tint = Color.Black, modifier = Modifier.size(12.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("إعادة المحاولة", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                3 -> {
                    // Tab 3: Live Diagnostics Stream / History
                    if (syncDiagnostics.isEmpty()) {
                        item {
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = Color.White
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.Analytics, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("لم يتم تسجيل أي عمليات مزامنة سحابية بعد في هذه الجلسة.", color = TextMuted, fontSize = 13.sp)
                                }
                            }
                        }
                    } else {
                        item {
                            Text(
                                text = "سجل التدقيق الزمني لكافة العمليات السحابية (آخر 50 عملية):",
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        items(syncDiagnostics, key = { it.id }) { record ->
                            val isSuccess = record.status == "SUCCESS"
                            val isFailed = record.status == "FAILED"
                            val actionName = when (record.action) {
                                "SYNC_PUSH", "SYNC_ALL_TO_CLOUD" -> "رفع ومزامنة البيانات"
                                "PULL_USERS" -> "تحديث الحسابات المشتركة"
                                "RESTORE_PULL" -> "استعادة شاملة من السيرفر"
                                else -> record.action
                            }

                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = Color.White
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 2.dp)
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (isSuccess) PmpGreen.copy(alpha = 0.15f)
                                                    else if (isFailed) PmpRed.copy(alpha = 0.15f)
                                                    else PmpCyan.copy(alpha = 0.15f)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isSuccess) Icons.Default.CheckCircle
                                                else if (isFailed) Icons.Default.Error
                                                else Icons.Default.Sync,
                                                contentDescription = null,
                                                tint = if (isSuccess) PmpGreen else if (isFailed) PmpRed else PmpCyan,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = actionName,
                                                color = PmpBrandNavy,
                                                fontSize = 13.5.sp,
                                                lineHeight = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = record.message,
                                                color = if (isFailed) PmpRed else TextMuted,
                                                fontSize = 11.5.sp,
                                                lineHeight = 17.sp,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${record.formattedTime} • السجلات: ${record.entitiesCount} • الجهاز: ${record.deviceId.take(8)}",
                                                color = TextMuted.copy(alpha = 0.75f),
                                                fontSize = 10.sp,
                                                lineHeight = 15.sp
                                            )
                                        }
                                    }

                                    GlassBadge(
                                        text = if (isSuccess) "ناجحة" else if (isFailed) "فشلت" else "تحذير",
                                        color = if (isSuccess) PmpGreen else if (isFailed) PmpRed else PmpGold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Action Buttons Bar
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(
                        text = "مزامنة سحابية فورية شاملة الآن",
                        icon = Icons.Default.CloudUpload,
                        gradient = PmpLogoSwooshGradient,
                        isLoading = isSyncing,
                        loadingText = "جارٍ المزامنة السحابية الآن...",
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diagnostic_manual_sync_button"),
                        onClick = { viewModel.syncAllErpDataToServer() }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassButton(
                            text = "فحص استقرار السيرفر",
                            icon = Icons.Default.Speed,
                            gradient = GlassAccentBlueGradient,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.checkServerHealth() }
                        )

                        GlassButton(
                            text = "استعادة من السيرفر",
                            icon = Icons.Default.CloudDownload,
                            gradient = Brush.horizontalGradient(listOf(Color(0xFF0F172A), Color(0xFF334155))),
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.restoreAllErpDataFromServer() }
                        )
                    }

                    GlassButton(
                        text = "حذف جميع السجلات من السيرفر والبدء من جديد",
                        icon = Icons.Default.DeleteSweep,
                        gradient = PmpBrandRedGradient,
                        enabled = !isSyncing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("wipe_server_button"),
                        onClick = { showWipeConfirmDialog = true }
                    )
                }
            }
        }

        if (showWipeConfirmDialog) {
            ServerWipeDangerDialog(
                onConfirmWithPassword = { password, onError ->
                    viewModel.verifyAndWipeAllServerData(
                        managerPassword = password,
                        onAuthError = onError,
                        onComplete = { ok, _ ->
                            if (ok) {
                                showWipeConfirmDialog = false
                            }
                        }
                    )
                },
                onDismiss = { showWipeConfirmDialog = false }
            )
        }
    }
}

/**
 * Clean Modal Sheet / Dialog wrapper for the Diagnostic Dashboard.
 * Uses a solid, crisp, bright surface without dark translucent black scrims.
 */
@Composable
fun SyncDiagnosticDashboardDialog(
    viewModel: PmpViewModel,
    initialTab: Int = 0,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 28.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = Color(0xFFF8FAFC),
            shadowElevation = 12.dp
        ) {
            SyncDiagnosticDashboardView(
                viewModel = viewModel,
                initialTab = initialTab,
                onClose = onDismiss,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ModuleSyncItemCard(
    title: String,
    localCount: Int,
    cloudCount: Int?,
    unitLabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color
) {
    val isMeasured = cloudCount != null
    val isMatched = cloudCount != null && localCount == cloudCount
    val cloudLabel = cloudCount?.let { "$it $unitLabel" } ?: "غير مقاس"
    val badgeText = when {
        !isMeasured -> "غير مقاس"
        isMatched -> "متطابق 100%"
        else -> "قيد التحديث"
    }
    val badgeColor = when {
        !isMeasured -> PmpGold
        isMatched -> PmpGreen
        else -> PmpCyan
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF8FAFC))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
            .padding(12.dp)
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
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(text = title, color = PmpBrandNavy, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        text = "محلياً: $localCount $unitLabel • السحابة: $cloudLabel",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            GlassBadge(
                text = badgeText,
                color = badgeColor
            )
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextMuted, fontSize = 11.sp)
        Text(
            text = value,
            color = PmpBrandNavy,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
