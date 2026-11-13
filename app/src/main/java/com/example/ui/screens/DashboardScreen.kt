package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.hasModulePermission
import com.example.isSuperAdminAccount
import com.example.isSystemManager
import com.example.parsedPermissionsSet
import com.example.ui.components.*
import com.example.ui.theme.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.example.ui.viewmodel.PmpViewModel
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: PmpViewModel,
    onNavigate: (String) -> Unit,
    onSignOut: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currencyBalances by viewModel.currencyBalances.collectAsState()
    val cashboxes by viewModel.cashboxes.collectAsState()
    val movements by viewModel.movements.collectAsState()
    val products by viewModel.products.collectAsState()
    val stockBalances by viewModel.productStockBalances.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val userAccounts by viewModel.userAccounts.collectAsState()
    val cloudSyncState by viewModel.cloudSyncState.collectAsState()
    val serverHealthReport by viewModel.serverHealthReport.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val lastSyncTime by viewModel.lastSuccessfulSyncTime.collectAsState()
    val pendingErrors by viewModel.pendingSyncErrors.collectAsState()
    val isInitialDataLoading by viewModel.isInitialDataLoading.collectAsState()
    val dataLoadError by viewModel.dataLoadError.collectAsState()

    var showDiagnosticDashboard by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    val format = remember { NumberFormat.getNumberInstance(Locale.US) }

    val totalSd = currencyBalances["SDG"] ?: 0.0
    val totalEgp = currencyBalances["EGP"] ?: 0.0
    val totalUsd = currencyBalances["USD"] ?: 0.0

    val isManager = currentUser.isSystemManager()
    val allowedSet = remember(currentUser) { currentUser.parsedPermissionsSet() }
    val canAccessTreasury = currentUser.hasModulePermission("الخزن")
    val canAccessTransfers = currentUser.hasModulePermission("التحويلات")
    val canAccessSales = currentUser.hasModulePermission("المبيعات")
    val canAccessInventory = currentUser.hasModulePermission("المخازن")
    val canAccessFactory = currentUser.hasModulePermission("المصنع")
    val canAccessPoster = currentUser.hasModulePermission("الأسعار")
    val canAccessReports = currentUser.hasModulePermission("التقارير")
    val canAccessUsers = currentUser.hasModulePermission("المستخدمين")
    val canAccessLab = currentUser.hasModulePermission("المختبر الكيميائي") || canAccessFactory || isManager

    val lowStockCount = remember(products, stockBalances) {
        products.count { product ->
            val current = stockBalances[product.id] ?: 0.0
            current <= product.minimumStock
        }
    }

    val pendingAccounts = remember(userAccounts) {
        userAccounts.filter { it.syncStatus == "PENDING_MANAGER_APPROVAL" }
    }

    var showAllMovements by remember { mutableStateOf(false) }

    val filteredMovements = remember(movements, searchQuery, showAllMovements) {
        if (searchQuery.isBlank()) {
            if (showAllMovements) movements else movements.take(5)
        } else {
            val list = movements.filter {
                it.description.contains(searchQuery, ignoreCase = true) ||
                    it.reference.contains(searchQuery, ignoreCase = true) ||
                    it.cashbox.contains(searchQuery, ignoreCase = true)
            }
            if (showAllMovements) list else list.take(5)
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // User Profile Card (Clean & Minimal, Server Status removed, Pull-to-Refresh active)
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isManager) Icons.Default.AdminPanelSettings else Icons.Default.ManageAccounts,
                                contentDescription = null,
                                tint = PmpBrandRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentUser?.fullName ?: "الإدارة العامة - المدير التنفيذي",
                                    color = TextDark,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val isSuperAdmin = currentUser.isSuperAdminAccount()
                                Text(
                                    text = if (isSuperAdmin) {
                                        "${currentUser?.role ?: "مدير النظام"} • ${currentUser?.branch ?: "الإدارة العامة"} • ${currentUser?.userCode ?: ""}"
                                    } else {
                                        "${currentUser?.role ?: "موظف"} • ${currentUser?.branch ?: "الفرع الرئيسي"}"
                                    },
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // Pending Account Creation Requests - Direct Manager Approval Card
            if (isManager && pendingAccounts.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                        border = BorderStroke(1.dp, Color(0xFFFDE68A))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = Color(0xFFD97706),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "طلبات إنشاء حساب جديدة بانتظار موافقة المدير (${pendingAccounts.size})",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    color = Color(0xFF92400E)
                                )
                            }

                            pendingAccounts.forEach { pendingUser ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color.White,
                                    border = BorderStroke(0.6.dp, Color(0xFFFCD34D))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp)
                                        ) {
                                            Text(
                                                text = pendingUser.fullName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = Color(0xFF1E293B),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = pendingUser.branch,
                                                fontSize = 11.sp,
                                                color = Color(0xFF64748B),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        Button(
                                            onClick = { viewModel.approveUserAccount(pendingUser) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier.testTag("dashboard_approve_account_btn_${pendingUser.id}")
                                        ) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("موافقة واعتماد", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Skeleton Loading & Error State Handling (No flash on background sync or tab switches)
            if (isInitialDataLoading && cashboxes.isEmpty() && movements.isEmpty()) {
                item(key = "dashboard_shimmer_skeleton") {
                    ProvideShimmerEffect(enabled = true) {
                        DashboardSkeletonSection()
                    }
                }
            } else if (dataLoadError != null && cashboxes.isEmpty() && movements.isEmpty()) {
                item(key = "dashboard_error_card") {
                    PmpErrorStateCard(
                        message = dataLoadError ?: "تعذر تحميل البيانات",
                        onRetry = { viewModel.retryDataLoadAndSync() }
                    )
                }
            } else if (canAccessTreasury) {
            item {
                Text(
                    text = "الأرصدة والسيولة الحالية (لكل عملة)",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassStatCard(
                        title = "خزينة الجنيه السوداني",
                        value = format.format(totalSd),
                        unit = "SDG",
                        subtitle = "الرصيد المتاح بالسودان",
                        accentColor = PmpBlueLight,
                        icon = Icons.Default.AccountBalanceWallet,
                        modifier = Modifier.weight(1f)
                    )
                    GlassStatCard(
                        title = "خزينة الجنيه المصري",
                        value = format.format(totalEgp),
                        unit = "EGP",
                        subtitle = "الرصيد المتاح بمصر",
                        accentColor = PmpGold,
                        icon = Icons.Default.Paid,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                GlassStatCard(
                    title = if (canAccessInventory) "الخزن النشطة والمخازن" else "الخزن المالية المعتمدة",
                    value = "${cashboxes.size} خزن",
                    subtitle = if (canAccessInventory) {
                        if (lowStockCount > 0) "$lowStockCount أصناف منخفضة" else "المخزون كافٍ"
                    } else {
                        "متاحة لتسجيل الحركات"
                    },
                    accentColor = if (canAccessInventory && lowStockCount > 0) PmpRedLight else PmpPurple,
                    icon = if (canAccessInventory) Icons.Default.Warehouse else Icons.Default.AccountBalance,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Recent Movements Title & Search Bar
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "سجل الحركات المالية الأخيرة",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "${movements.size} حركة مسجلة",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }

            item {
                GlassTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = "",
                    placeholder = "بحث برقم المستند، البيان، أو الخزنة...",
                    leadingIcon = Icons.Default.Search,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (filteredMovements.isEmpty()) {
                item {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (searchQuery.isBlank()) "لا توجد حركات مسجلة بعد" else "لا توجد حركات مطابقة للبحث",
                                color = TextMuted,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            } else {
                items(filteredMovements, key = { it.id }) { item ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp
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
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 10.dp)
                            ) {
                                GlassBadge(
                                    text = item.direction,
                                    color = if (item.direction == "وارد") PmpGreen else PmpRed
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextWhite,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${item.cashbox} • ${item.txDate} • مرجع: ${item.reference}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = (if (item.direction == "وارد") "+" else "-") + format.format(item.amount),
                                    color = if (item.direction == "وارد") PmpGreen else PmpRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = item.currency,
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                if (movements.size > 5) {
                    item {
                        OutlinedButton(
                            onClick = { showAllMovements = !showAllMovements },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PmpCyan)
                        ) {
                            Text(
                                text = if (showAllMovements) "عرض أقل (العودة لآخر 5 حركات)" else "عرض المزيد (${movements.size - 5} حركات إضافية)",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        } else {
            // Employee does not have Treasury permission: show role-scoped workspace summary
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LockPerson,
                                contentDescription = null,
                                tint = PmpBrandNavy,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "عزل الصلاحيات الوظيفية مفعل",
                                color = TextDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        Text(
                            text = "حسابك الحالي (${currentUser?.role}) مخصص للأقسام التالية فقط حسب توجيه مدير النظام: ${allowedSet.joinToString(" • ")}. بيانات الخزينة العامة والأقسام الأخرى محجوبة تلقائياً.",
                            color = TextMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
    }

    if (showDiagnosticDashboard) {
        SyncDiagnosticDashboardDialog(
            viewModel = viewModel,
            onDismiss = { showDiagnosticDashboard = false }
        )
    }
}

@Composable
private fun BrandQualityChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFEF2F2))
            .border(0.5.dp, Color(0xFFFECACA), RoundedCornerShape(10.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PmpBrandRed,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                color = TextDark,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
