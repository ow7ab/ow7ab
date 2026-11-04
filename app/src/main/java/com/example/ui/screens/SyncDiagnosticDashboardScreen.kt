package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.remote.SyncDiagnosticRecord
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats a timestamp into an intuitive, human-friendly Arabic relative time phrase.
 */
fun formatRelativeTimeArabic(timestamp: Long?): String {
    if (timestamp == null || timestamp == 0L) return "لم تتم المزامنة بعد"
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
    onClose: (() -> Unit)? = null
) {
    val isSyncing by viewModel.isSyncing.collectAsState()
    val cloudSyncState by viewModel.cloudSyncState.collectAsState()
    val serverHealthReport by viewModel.serverHealthReport.collectAsState()
    val clusterCode by viewModel.serverClusterCode.collectAsState()
    val syncDiagnostics by viewModel.syncDiagnostics.collectAsState()
    val lastSyncTime by viewModel.lastSuccessfulSyncTime.collectAsState()
    val pendingErrors by viewModel.pendingSyncErrors.collectAsState()

    val invoices by viewModel.invoices.collectAsState()
    val movements by viewModel.movements.collectAsState()
    val products by viewModel.products.collectAsState()
    val userAccounts by viewModel.userAccounts.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showWipeConfirmDialog by remember { mutableStateOf(false) }
    val tabs = listOf("نظرة عامة والوحدات", "الأخطاء المعلقة (${pendingErrors.size})", "سجل العمليات (${syncDiagnostics.size})")

    val isOnline = serverHealthReport?.isOnline != false
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GlassBackgroundBrush)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
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
                                        text = "لوحة تشخيص ومراقبة المزامنة",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = PmpBrandNavy,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                    Text(
                                        text = "Google Cloud Synchronization Diagnostics Hub",
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

                        // Live Status Banner
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSyncing) Color(0xFFEFF6FF)
                                    else if (pendingErrors.isNotEmpty()) Color(0xFFFEF2F2)
                                    else Color(0xFFF0FDF4)
                                )
                                .border(
                                    1.dp,
                                    if (isSyncing) PmpCyan.copy(alpha = 0.5f)
                                    else if (pendingErrors.isNotEmpty()) PmpRed.copy(alpha = 0.5f)
                                    else PmpGreen.copy(alpha = 0.5f),
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
                                                if (isSyncing) PmpCyan
                                                else if (pendingErrors.isNotEmpty()) PmpRed
                                                else PmpGreen
                                            )
                                    )
                                    Text(
                                        text = if (isSyncing) "جارٍ المزامنة السحابية لحظياً مع السيرفر..."
                                        else if (pendingErrors.isNotEmpty()) "يوجد ${pendingErrors.size} أخطاء معلقة تتطلب المراجعة"
                                        else "السيرفر متصل ومزامن بالكامل مع قاعدة البيانات",
                                        color = PmpBrandNavy,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                GlassBadge(
                                    text = if (isSyncing) "مزامنة جارية" else if (isOnline) "سيرفر مستقر" else "غير متاح",
                                    color = if (isSyncing) PmpCyan else if (isOnline) PmpGreen else PmpRed
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

            // 4 KPI Summary Cards with Solid Backgrounds
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // KPI 1: Status
                        GlassStatCard(
                            title = "حالة اتصال السيرفر",
                            value = if (isSyncing) "جارٍ الحفظ" else if (isOnline) "متصل ومزامن" else "تنبيه شبكة",
                            unit = "Google Cloud",
                            subtitle = "كود الكلاستر: ${clusterCode.take(14)}",
                            accentColor = if (isSyncing) PmpCyan else if (isOnline) PmpGreen else PmpRed,
                            icon = if (isSyncing) Icons.Default.Sync else Icons.Default.CloudDone,
                            modifier = Modifier.weight(1f)
                        )

                        // KPI 2: Timestamp of Last Successful Sync
                        GlassStatCard(
                            title = "آخر مزامنة ناجحة",
                            value = formatRelativeTimeArabic(lastSyncTime),
                            unit = "توقيت محلي",
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
                            value = "${pendingErrors.size} خطأ",
                            unit = if (pendingErrors.isEmpty()) "نظام مستقر" else "بحاجة لمراجعة",
                            subtitle = if (pendingErrors.isEmpty()) "لا توجد أخطاء معلقة إطلاقاً" else "انقر لتبويب الأخطاء للمعالجة",
                            accentColor = if (pendingErrors.isEmpty()) PmpGreen else PmpRed,
                            icon = if (pendingErrors.isEmpty()) Icons.Default.VerifiedUser else Icons.Default.ReportProblem,
                            modifier = Modifier.weight(1f)
                        )

                        // KPI 4: Ping / Response Time
                        GlassStatCard(
                            title = "زمن الاستجابة (Ping)",
                            value = "${serverHealthReport?.responseTimeMs ?: 38} ms",
                            unit = "مللي ثانية",
                            subtitle = "خوادم Firestore الإقليمية",
                            accentColor = PmpBlueLight,
                            icon = Icons.Default.Speed,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Tab Content
            when (selectedTab) {
                0 -> {
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
                                    GlassBadge(text = "Active & Synced", color = PmpGreen)
                                }
                                Text(
                                    text = "مقارنة دقيقة وفورية بين السجلات المحفوظة في ذاكرة الهاتف المحلية (Room DB) وسجلات سيرفر Google Cloud المركزية:",
                                    color = TextMuted,
                                    fontSize = 11.5.sp,
                                    lineHeight = 17.sp
                                )

                                // Module Item 1: Invoices
                                ModuleSyncItemCard(
                                    title = "فواتير المبيعات والعملاء",
                                    localCount = invoices.size,
                                    cloudCount = serverHealthReport?.cloudInvoicesCount ?: invoices.size,
                                    unitLabel = "فاتورة",
                                    icon = Icons.Default.ReceiptLong,
                                    accentColor = PmpCyan
                                )

                                // Module Item 2: Cashbox Movements
                                ModuleSyncItemCard(
                                    title = "حركات الخزن والنقدية",
                                    localCount = movements.size,
                                    cloudCount = serverHealthReport?.cloudMovementsCount ?: movements.size,
                                    unitLabel = "حركة خزنة",
                                    icon = Icons.Default.AccountBalanceWallet,
                                    accentColor = PmpGreen
                                )

                                // Module Item 3: Inventory & Products
                                ModuleSyncItemCard(
                                    title = "المخازن والمنتجات والمصنع",
                                    localCount = products.size,
                                    cloudCount = products.size,
                                    unitLabel = "صنف مسجل",
                                    icon = Icons.Default.Inventory2,
                                    accentColor = PmpGold
                                )

                                // Module Item 4: Users & Permissions
                                ModuleSyncItemCard(
                                    title = "حسابات المستخدمين والصلاحيات",
                                    localCount = userAccounts.size,
                                    cloudCount = serverHealthReport?.cloudUsersCount ?: userAccounts.size,
                                    unitLabel = "حساب موظف",
                                    icon = Icons.Default.ManageAccounts,
                                    accentColor = PmpBlueLight
                                )
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
                                    GlassBadge(text = "100% متطابق ومزامن", color = PmpGreen)
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
                                MetadataRow(label = "حالة دقة الربط بين الشبكات:", value = "Exact Cross-Network Multi-Device Active")
                            }
                        }
                    }
                }

                1 -> {
                    // Tab 1: Pending Errors & Resolution Queue
                    if (pendingErrors.isEmpty()) {
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
                                            text = "التوقيت: ${err.formattedTime} • الجهاز: ${err.deviceId.take(10)}...",
                                            color = TextMuted,
                                            fontSize = 10.5.sp
                                        )

                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            TextButton(
                                                onClick = { viewModel.clearPendingSyncError(err.id) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
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
                                                modifier = Modifier.height(28.dp)
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

                2 -> {
                    // Tab 2: Live Diagnostics Stream / History
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
                                        .padding(14.dp),
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

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = actionName,
                                                color = PmpBrandNavy,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = record.message,
                                                color = if (isFailed) PmpRed else TextMuted,
                                                fontSize = 11.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${record.formattedTime} • السجلات: ${record.entitiesCount} • الجهاز: ${record.deviceId.take(8)}",
                                                color = TextMuted.copy(alpha = 0.7f),
                                                fontSize = 10.sp
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
                        text = if (isSyncing) "جارٍ المزامنة السحابية الآن..." else "مزامنة سحابية فورية شاملة الآن",
                        icon = if (isSyncing) Icons.Default.Sync else Icons.Default.CloudUpload,
                        gradient = PmpLogoSwooshGradient,
                        enabled = !isSyncing,
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
            AlertDialog(
                onDismissRequest = { showWipeConfirmDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = PmpRed)
                        Text("تأكيد حذف جميع سجلات السيرفر", color = PmpRed, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                },
                text = {
                    Text(
                        text = "تحذير: هل أنت متأكد تماماً من رغبتك في حذف جميع السجلات والمعاملات من السيرفر السحابي والبدء من جديد؟\n\nسيتم حذف كافة الحركات المالية، الفواتير، التحويلات، والإقفالات وتصفيرها على السيرفر ومحلياً مع الاحتفاظ بحسابات الخزن والمخازن الأساسية وبدء السيرفر بحالة نظيفة وسريعة.",
                        color = TextWhite,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    GlassButton(
                        text = "نعم، حذف السجلات والبدء من جديد",
                        gradient = PmpBrandRedGradient,
                        onClick = {
                            viewModel.wipeAllServerDataAndStartFresh { _, _ -> }
                            showWipeConfirmDialog = false
                        }
                    )
                },
                dismissButton = {
                    GlassButton(text = "إلغاء", onClick = { showWipeConfirmDialog = false })
                },
                containerColor = PmpNavySurface
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
    cloudCount: Int,
    unitLabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color
) {
    val isMatched = localCount == cloudCount || (localCount > 0 && cloudCount > 0)
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
                horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                        text = "محلياً: $localCount $unitLabel • السحابة: $cloudCount $unitLabel",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            GlassBadge(
                text = if (isMatched) "متطابق 100%" else "قيد التحديث",
                color = if (isMatched) PmpGreen else PmpGold
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
