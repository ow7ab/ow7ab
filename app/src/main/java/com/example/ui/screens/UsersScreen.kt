package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.UserAccountEntity
import com.example.isSuperAdminAccount
import com.example.isSystemManager
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel

@Composable
fun UsersScreen(
    viewModel: PmpViewModel,
    onSignOut: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val userAccounts by viewModel.userAccounts.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val isInitialDataLoading by viewModel.isInitialDataLoading.collectAsState()
    val isManager = currentUser.isSystemManager()
    val isSuperAdmin = currentUser.isSuperAdminAccount()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = if (isManager) {
        listOf("حسابات المستخدمين", "+ إنشاء حساب", "الأرشيف")
    } else {
        listOf("حسابي الحالي")
    }

    // Form state for creating a new user account
    var fullName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var phoneOrEmail by remember { mutableStateOf("") }
    var pinOrPassword by remember { mutableStateOf("1234") }

    val roleOptions = listOf(
        "مدير النظام",
        "محاسب خزنة ومبيعات",
        "مسؤول مبيعات وفواتير",
        "مهندس إنتاج - المصنع",
        "أمين مخزن وترحيل",
        "مراجع مالي وتقارير",
        "مسمى وظيفي آخر (كتابة يدوية)..."
    )
    var selectedRole by remember { mutableStateOf(roleOptions[1]) }
    var customRoleText by remember { mutableStateOf("") }

    val branchOptions = listOf(
        "فرع السودان (SD-001)",
        "فرع مصر والمصنع (EG-001)",
        "مخزن حلفا (HF-001)",
        "مخزن عطبرة (AT-002)",
        "كل الفروع (السودان ومصر)"
    )
    var selectedBranch by remember { mutableStateOf(branchOptions[0]) }

    val cloudSyncState by viewModel.cloudSyncState.collectAsState()
    val cloudLinkedAccounts by viewModel.cloudLinkedAccounts.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val serverHealthReport by viewModel.serverHealthReport.collectAsState()
    val currentClusterCode by viewModel.serverClusterCode.collectAsState()
    val syncDiagnostics by viewModel.syncDiagnostics.collectAsState()
    var showFullDiagnosticDialog by remember { mutableStateOf(false) }

    val generalPermissions = listOf(
        "المبيعات",
        "المشتريات",
        "الأسعار",
        "تصدير PDF",
        "الخزن",
        "التحويلات",
        "المخازن",
        "المصنع",
        "المختبر الكيميائي",
        "مواد الإنتاج",
        "التعبئة والتغليف",
        "تعديل نوع الصنف",
        "المستخدمين",
        "الإعدادات",
        "صلاحية الحذف والتصفير"
    )
    val reportSectionPermissions = listOf(
        "التقارير (كاملة)",
        "تقارير المبيعات",
        "تقارير المواد الخام",
        "تقارير حركة المخزون",
        "تقارير حركات الخزن",
        "تقارير المديونيات"
    )
    val allPermissions = generalPermissions + reportSectionPermissions

    var selectedPermissions by remember {
        mutableStateOf(setOf("المبيعات", "تصدير PDF", "الأسعار", "تقارير المبيعات"))
    }

    var serverEndpointInput by remember {
        mutableStateOf("https://firestore.googleapis.com/v1/projects/pmp-paints-cloud")
    }
    var clusterCodeInput by remember(currentClusterCode) {
        mutableStateOf(currentClusterCode)
    }
    var cloudLinkCodeInput by remember {
        mutableStateOf("")
    }

    // Optional PIN modal when switching user
    var userPendingSwitch by remember { mutableStateOf<UserAccountEntity?>(null) }
    var switchPinInput by remember { mutableStateOf("") }

    // Manager Permission Editor modal
    var userPendingPermEdit by remember { mutableStateOf<UserAccountEntity?>(null) }
    var editRoleState by remember { mutableStateOf("") }
    var editCustomRoleText by remember { mutableStateOf("") }
    var editBranchState by remember { mutableStateOf("") }
    var editPermsState by remember { mutableStateOf<Set<String>>(emptySet()) }
    var userToDeletePermanently by remember { mutableStateOf<UserAccountEntity?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "إدارة حسابات المستخدمين",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        GlassTabRow(
            tabs = tabs,
            selectedIndex = selectedTab.coerceIn(0, tabs.lastIndex),
            onTabSelected = { selectedTab = it }
        )

        Spacer(modifier = Modifier.height(12.dp))

        when (selectedTab.coerceIn(0, tabs.lastIndex)) {
            0 -> {
                val activeCount = userAccounts.count { it.active }
                val pendingAccounts = userAccounts.filter { it.syncStatus == "PENDING_MANAGER_APPROVAL" }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (isInitialDataLoading && userAccounts.isEmpty()) {
                        item(key = "users_shimmer_skeleton") {
                            ProvideShimmerEffect(enabled = true) {
                                UsersSkeletonSection()
                            }
                        }
                    }

                    // Active Session Banner Card
                    item {
                        currentUser?.let { activeUser ->
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 20.dp
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    Color(0xFFFEF2F2),
                                                    Color(0xFFF8FAFC)
                                                )
                                            )
                                        )
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(CircleShape)
                                                    .background(PmpBrandRedGradient),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.VerifiedUser,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "الحساب النشط حالياً في الجلسة",
                                                    color = PmpBrandRed,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = activeUser.fullName,
                                                    color = TextDark,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "@${activeUser.username} • ${activeUser.branch}",
                                                    color = TextMuted,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        GlassBadge(
                                            text = activeUser.role,
                                            color = PmpBrandNavy
                                        )
                                    }

                                    if (isSuperAdmin) {
                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "معرف الحساب: ${activeUser.userCode}",
                                                color = PmpBrandNavy,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            GlassBadge(
                                                text = "حساب رئيسي",
                                                color = PmpGreen
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (isSuperAdmin) {
                                            GlassButton(
                                                text = "مزامنة المستخدمين",
                                                icon = Icons.Default.CloudSync,
                                                gradient = PmpBrandRedGradient,
                                                onClick = { viewModel.syncAllAccountsToGoogleCloud() },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("quick_sync_users_cloud_button")
                                            )
                                        }

                                        GlassButton(
                                            text = "تسجيل الخروج",
                                            icon = Icons.Default.Logout,
                                            gradient = GlassAccentBlueGradient,
                                            onClick = onSignOut,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("sign_out_button")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Pending Google Accounts Banner for System Manager
                    if (isManager && pendingAccounts.isNotEmpty()) {
                        item {
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.5.dp, PmpGold, RoundedCornerShape(18.dp)),
                                cornerRadius = 18.dp
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFFFFBEB))
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.NotificationsActive,
                                                contentDescription = null,
                                                tint = Color(0xFFB45309),
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "طلبات حسابات بانتظار موافقة المدير (${pendingAccounts.size})",
                                                color = Color(0xFF92400E),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // KPI Row
                    if (isManager) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassStatCard(
                                    title = "إجمالي الحسابات",
                                    value = "${userAccounts.size}",
                                    unit = "حساب",
                                    subtitle = "$activeCount حساب معتمد ومفعل",
                                    accentColor = PmpBlueLight,
                                    icon = Icons.Default.Group,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassStatCard(
                                    title = "بانتظار موافقة المدير",
                                    value = "${pendingAccounts.size}",
                                    unit = "طلب",
                                    subtitle = "حسابات Google معلقة",
                                    accentColor = if (pendingAccounts.isNotEmpty()) PmpGold else PmpGreen,
                                    icon = Icons.Default.VerifiedUser,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "دليل حسابات المستخدمين واعتماد الصلاحيات",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                GlassButton(
                                    text = "حساب جديد",
                                    icon = Icons.Default.PersonAdd,
                                    gradient = PmpBrandRedGradient,
                                    onClick = { selectedTab = 1 },
                                    modifier = Modifier.testTag("open_create_user_tab_button")
                                )
                            }
                        }
                    }

                    val displayedAccounts = if (isManager) {
                        // Show active accounts and pending approval accounts (suspended accounts are preserved in the Archive tab)
                        userAccounts.filter { it.active || it.syncStatus == "PENDING_MANAGER_APPROVAL" }
                            .sortedByDescending { it.syncStatus == "PENDING_MANAGER_APPROVAL" }
                    } else {
                        userAccounts.filter { it.id == currentUser?.id }
                    }

                    items(displayedAccounts, key = { it.id }) { account ->
                        UserAccountCard(
                            account = account,
                            isCurrent = currentUser?.id == account.id,
                            canManagePermissions = isManager,
                            isSuperAdmin = isSuperAdmin,
                            onSwitchRequest = {
                                userPendingSwitch = account
                                switchPinInput = account.pinOrPassword
                            },
                            onEditPermissionsRequest = {
                                if (isManager) {
                                    userPendingPermEdit = account
                                    if (account.role in roleOptions && account.role != "مسمى وظيفي آخر (كتابة يدوية)...") {
                                        editRoleState = account.role
                                        editCustomRoleText = ""
                                    } else {
                                        editRoleState = "مسمى وظيفي آخر (كتابة يدوية)..."
                                        editCustomRoleText = account.role
                                    }
                                    editBranchState = account.branch
                                    val existingPerms = account.permissions
                                        .split(",")
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .toSet()
                                    editPermsState = if (existingPerms.isEmpty() && account.syncStatus == "PENDING_MANAGER_APPROVAL") {
                                        setOf("المبيعات", "الأسعار")
                                    } else {
                                        existingPerms
                                    }
                                } else {
                                    viewModel.postMessage("عفواً، تعديل الصلاحيات متاح فقط من حساب مدير النظام", isError = true)
                                }
                            },
                            onToggleActive = {
                                viewModel.toggleUserAccountActive(account)
                            },
                            onRegenerateToken = {
                                viewModel.regenerateUserSyncToken(account)
                            },
                            onDeletePermanently = {
                                userToDeletePermanently = account
                            },
                            onApproveDirectly = {
                                viewModel.approveUserAccount(account)
                            }
                        )
                    }
                }
            }

            1 -> {
                // Create New User Account Form (Manager Only)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "بيانات حساب المستخدم الجديد",
                                        color = TextWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    GlassBadge(text = "اعتماد مدير النظام", color = PmpBlueLight)
                                }

                                GlassTextField(
                                    value = fullName,
                                    onValueChange = { fullName = it },
                                    label = "الاسم الكامل للموظف / المسؤول",
                                    placeholder = "مثال: محمد عثمان - محاسب فرع عطبرة",
                                    leadingIcon = Icons.Default.Person,
                                    modifier = Modifier.testTag("user_fullname_input")
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    GlassTextField(
                                        value = username,
                                        onValueChange = { username = it },
                                        label = "اسم الدخول",
                                        placeholder = "مثال: atbara_acc",
                                        leadingIcon = Icons.Default.AlternateEmail,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("user_username_input")
                                    )

                                    GlassTextField(
                                        value = pinOrPassword,
                                        onValueChange = { pinOrPassword = it },
                                        label = "كلمة المرور أو الرمز السري",
                                        placeholder = "1234",
                                        isNumber = true,
                                        leadingIcon = Icons.Default.Lock,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("user_pin_input")
                                    )
                                }

                                GlassTextField(
                                    value = phoneOrEmail,
                                    onValueChange = { phoneOrEmail = it },
                                    label = "رقم الهاتف أو البريد الإلكتروني",
                                    placeholder = "+249912345678 / user@pmp-paints.com",
                                    leadingIcon = Icons.Default.Phone,
                                    modifier = Modifier.testTag("user_contact_input")
                                )

                                GlassDropdown(
                                    label = "المسمى الوظيفي",
                                    options = roleOptions,
                                    selectedOption = selectedRole,
                                    onOptionSelected = { role ->
                                        selectedRole = role
                                        selectedPermissions = when {
                                            role.contains("مدير") -> allPermissions.toSet()
                                            role.contains("محاسب") -> setOf("الخزن", "المبيعات", "تصدير PDF", "الأسعار")
                                            role.contains("مبيعات") -> setOf("المبيعات", "تصدير PDF", "الأسعار")
                                            role.contains("مصنع") || role.contains("إنتاج") -> setOf("المصنع", "مواد الإنتاج", "التعبئة والتغليف", "التقارير")
                                            role.contains("مخزن") -> setOf("المخازن", "التحويلات")
                                            else -> setOf("التقارير", "الخزن")
                                        }
                                    }
                                )

                                if (selectedRole == "مسمى وظيفي آخر (كتابة يدوية)...") {
                                    GlassTextField(
                                        value = customRoleText,
                                        onValueChange = { customRoleText = it },
                                        label = "اكتب المسمى الوظيفي المخصص يدوياً *",
                                        placeholder = "مثال: مدير المبيعات الإقليمي، فني مختبر...",
                                        leadingIcon = Icons.Default.Badge
                                    )
                                }

                                GlassDropdown(
                                    label = "الفرع / الموقع المرتبط بالحساب",
                                    options = branchOptions,
                                    selectedOption = selectedBranch,
                                    onOptionSelected = { selectedBranch = it }
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "تحديد الصلاحيات المخصصة للموظف (${selectedPermissions.size}/${allPermissions.size}):",
                                        color = TextMuted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TextButton(
                                            onClick = { selectedPermissions = allPermissions.toSet() },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("تحديد الكل", color = PmpCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                        TextButton(
                                            onClick = { selectedPermissions = emptySet() },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("إلغاء الكل", color = PmpRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                // Permissions Chips Grid (2 per row so nothing gets truncated)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    generalPermissions.chunked(2).forEach { rowPerms ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            rowPerms.forEach { perm ->
                                                val isSelected = perm in selectedPermissions
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .heightIn(min = 40.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(
                                                            if (isSelected) PmpBrandRed.copy(alpha = 0.14f)
                                                            else Color(0xFFF1F5F9)
                                                        )
                                                        .border(
                                                            1.dp,
                                                            if (isSelected) PmpBrandRed else GlassBorder,
                                                            RoundedCornerShape(10.dp)
                                                        )
                                                        .clickable {
                                                            selectedPermissions = if (isSelected) {
                                                                selectedPermissions - perm
                                                            } else {
                                                                selectedPermissions + perm
                                                            }
                                                        }
                                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                                    contentAlignment = Alignment.CenterStart
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                                            contentDescription = null,
                                                            tint = if (isSelected) PmpBrandRed else TextMuted,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Text(
                                                            text = perm,
                                                            color = if (isSelected) PmpBrandRed else TextDark,
                                                            fontSize = 11.5.sp,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                            if (rowPerms.size == 1) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "تحديد صلاحيات التقارير (تخصيص ما يراه الموظف كالمبيعات وحجب المواد الخام):",
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    reportSectionPermissions.chunked(2).forEach { rowPerms ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            rowPerms.forEach { perm ->
                                                val isSelected = perm in selectedPermissions
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(38.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(
                                                            if (isSelected) PmpBrandNavy.copy(alpha = 0.14f)
                                                            else Color(0xFFF1F5F9)
                                                        )
                                                        .border(
                                                            1.dp,
                                                            if (isSelected) PmpBrandNavy else GlassBorder,
                                                            RoundedCornerShape(10.dp)
                                                        )
                                                        .clickable {
                                                            selectedPermissions = if (isSelected) {
                                                                selectedPermissions - perm
                                                            } else {
                                                                selectedPermissions + perm
                                                            }
                                                        }
                                                        .padding(horizontal = 4.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = if (isSelected) "✓ $perm" else perm,
                                                        color = if (isSelected) PmpBrandNavy else TextMuted,
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                GlassButton(
                                    text = "إنشاء الحساب",
                                    icon = Icons.Default.PersonAddAlt1,
                                    gradient = PmpBrandRedGradient,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("submit_create_user_button"),
                                    onClick = {
                                        val finalRole = if (selectedRole == "مسمى وظيفي آخر (كتابة يدوية)..." && customRoleText.isNotBlank()) {
                                            customRoleText.trim()
                                        } else {
                                            selectedRole
                                        }
                                        viewModel.createUserAccount(
                                            fullName = fullName,
                                            username = username,
                                            phoneOrEmail = phoneOrEmail,
                                            pinOrPassword = pinOrPassword,
                                            role = finalRole,
                                            branch = selectedBranch,
                                            permissions = selectedPermissions.joinToString(","),
                                            serverEndpoint = serverEndpointInput
                                        )
                                        if (fullName.isNotBlank() && username.isNotBlank() && pinOrPassword.length >= 4) {
                                            fullName = ""
                                            username = ""
                                            phoneOrEmail = ""
                                            pinOrPassword = "1234"
                                            customRoleText = ""
                                            selectedTab = 0
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            2 -> {
                // Archive Tab: Suspended User Accounts (استرداد الحساب أو حذفه نهائياً)
                val suspendedAccounts = userAccounts.filter { !it.active && it.syncStatus != "PENDING_MANAGER_APPROVAL" }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 18.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFFEF2F2))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Archive,
                                        contentDescription = null,
                                        tint = PmpRed,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "أرشيف الحسابات الموقوفة (${suspendedAccounts.size})",
                                        color = TextDark,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                            }
                        }
                    }

                    if (suspendedAccounts.isEmpty()) {
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = PmpGreen,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "لا توجد أي حسابات موقوفة في الأرشيف حالياً",
                                        color = TextWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    } else {
                        items(suspendedAccounts, key = { "arch_${it.id}" }) { account ->
                            ArchivedUserAccountCard(
                                account = account,
                                isSuperAdmin = isSuperAdmin,
                                onRestore = { viewModel.restoreUserAccount(account) },
                                onDeletePermanently = { userToDeletePermanently = account }
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialog to confirm switching active user session
    userPendingSwitch?.let { targetUser ->
        AlertDialog(
            onDismissRequest = { userPendingSwitch = null },
            containerColor = Color.White,
            title = {
                Text(
                    text = "تبديل الحساب النشط",
                    color = TextDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "سيتم تسجيل جميع العمليات والفواتير القادمة باسم: ${targetUser.fullName} (${targetUser.role})",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                    GlassTextField(
                        value = switchPinInput,
                        onValueChange = { switchPinInput = it },
                        label = "رمز الدخول للحساب (PIN)",
                        placeholder = "أدخل رمز PIN",
                        isNumber = true,
                        leadingIcon = Icons.Default.Key
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "تأكيد التفعيل",
                    icon = Icons.Default.CheckCircle,
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        viewModel.switchActiveUser(targetUser.id, switchPinInput)
                        userPendingSwitch = null
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { userPendingSwitch = null }) {
                    Text("إلغاء", color = TextMuted)
                }
            }
        )
    }

    // Dialog for Manager to set permissions before approving login OR modify permissions in the future
    userPendingPermEdit?.let { targetUser ->
        val isPendingApproval = targetUser.syncStatus == "PENDING_MANAGER_APPROVAL"
        val displayGeneralPermissions = remember(generalPermissions, editPermsState) {
            val extraPerms = editPermsState.filter { it.isNotBlank() && it !in generalPermissions && it !in reportSectionPermissions }
            (generalPermissions + extraPerms).distinct()
        }
        val totalAvailablePerms = displayGeneralPermissions.size + reportSectionPermissions.size

        AlertDialog(
            onDismissRequest = { userPendingPermEdit = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 680.dp),
            containerColor = Color.White,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isPendingApproval) {
                                "تحديد الصلاحيات والموافقة (${targetUser.fullName})"
                            } else {
                                "تعديل صلاحيات الموظف (${targetUser.fullName})"
                            },
                            color = TextDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "تم تحديد ${editPermsState.size} من $totalAvailablePerms صلاحية متاحة",
                            color = PmpBrandRed,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassDropdown(
                        label = "الدور الوظيفي",
                        options = roleOptions,
                        selectedOption = editRoleState,
                        onOptionSelected = { newRole ->
                            editRoleState = newRole
                            if (newRole.contains("مدير النظام")) {
                                editPermsState = (displayGeneralPermissions + reportSectionPermissions).toSet()
                            }
                        }
                    )

                    if (editRoleState == "مسمى وظيفي آخر (كتابة يدوية)...") {
                        GlassTextField(
                            value = editCustomRoleText,
                            onValueChange = { editCustomRoleText = it },
                            label = "اكتب المسمى الوظيفي المخصص يدوياً *",
                            placeholder = "مثال: مدير المبيعات الإقليمي...",
                            leadingIcon = Icons.Default.Badge
                        )
                    }

                    GlassDropdown(
                        label = "الفرع",
                        options = branchOptions,
                        selectedOption = editBranchState,
                        onOptionSelected = { editBranchState = it }
                    )

                    // Select All / Deselect All quick bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "صلاحيات الأقسام والعمليات (${displayGeneralPermissions.size}):",
                            color = TextDark,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                onClick = {
                                    editPermsState = (displayGeneralPermissions + reportSectionPermissions).toSet()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("تحديد الكل", color = PmpGreen, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                            TextButton(
                                onClick = { editPermsState = emptySet() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("إلغاء الكل", color = PmpRed, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        displayGeneralPermissions.chunked(2).forEach { rowPerms ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowPerms.forEach { perm ->
                                    val isSelected = perm in editPermsState
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 40.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (isSelected) PmpBrandRed.copy(alpha = 0.14f)
                                                else Color(0xFFF1F5F9)
                                            )
                                            .border(
                                                1.dp,
                                                if (isSelected) PmpBrandRed else GlassBorder,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                editPermsState = if (isSelected) {
                                                    editPermsState - perm
                                                } else {
                                                    editPermsState + perm
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                                contentDescription = null,
                                                tint = if (isSelected) PmpBrandRed else TextMuted,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = perm,
                                                color = if (isSelected) PmpBrandRed else TextDark,
                                                fontSize = 11.5.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                if (rowPerms.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "صلاحيات أقسام التقارير التفصيلية (${reportSectionPermissions.size}):",
                        color = TextDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        reportSectionPermissions.chunked(2).forEach { rowPerms ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowPerms.forEach { perm ->
                                    val isSelected = perm in editPermsState
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 40.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (isSelected) PmpBrandNavy.copy(alpha = 0.14f)
                                                else Color(0xFFF1F5F9)
                                            )
                                            .border(
                                                1.dp,
                                                if (isSelected) PmpBrandNavy else GlassBorder,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                editPermsState = if (isSelected) {
                                                    editPermsState - perm
                                                } else {
                                                    editPermsState + perm
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                                contentDescription = null,
                                                tint = if (isSelected) PmpBrandNavy else TextMuted,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = perm,
                                                color = if (isSelected) PmpBrandNavy else TextDark,
                                                fontSize = 11.5.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                if (rowPerms.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    text = if (isPendingApproval) "اعتماد الحساب وتفعيل الدخول" else "حفظ الصلاحيات",
                    icon = if (isPendingApproval) Icons.Default.Verified else Icons.Default.Save,
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        val resolvedRole = if (editRoleState == "مسمى وظيفي آخر (كتابة يدوية)..." && editCustomRoleText.isNotBlank()) {
                            editCustomRoleText.trim()
                        } else {
                            editRoleState
                        }
                        viewModel.updateEmployeePermissionsAndRole(
                            account = targetUser,
                            newRole = resolvedRole,
                            newBranch = editBranchState,
                            newPermissions = editPermsState.joinToString(","),
                            approveAccount = true
                        )
                        userPendingPermEdit = null
                    },
                    modifier = Modifier.testTag("confirm_save_permissions_button")
                )
            },
            dismissButton = {
                TextButton(onClick = { userPendingPermEdit = null }) {
                    Text("إلغاء", color = TextMuted)
                }
            }
        )
    }

    // Modal: Permanent Account Deletion Confirmation
    if (userToDeletePermanently != null) {
        val target = userToDeletePermanently!!
        AlertDialog(
            onDismissRequest = { userToDeletePermanently = null },
            title = {
                Text(
                    text = "تأكيد حذف الحساب نهائياً",
                    color = PmpRed,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "هل أنت متأكد من رغبتك في حذف حساب (${target.fullName} - @${target.username}) نهائياً؟",
                        color = TextWhite,
                        fontSize = 13.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "تحذير: هذا الإجراء لا يمكن التراجع عنه وسيتم إزالة كافة صلاحيات هذا الحساب فوراً.",
                        color = Color(0xFFFCA5A5),
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "نعم، حذف نهائي",
                    icon = Icons.Default.DeleteForever,
                    gradient = Brush.horizontalGradient(listOf(Color(0xFFDC2626), Color(0xFF991B1B))),
                    onClick = {
                        viewModel.deleteUserAccountPermanently(target)
                        userToDeletePermanently = null
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { userToDeletePermanently = null })
            },
            containerColor = PmpNavySurface
        )
    }

    if (showFullDiagnosticDialog && isSuperAdmin) {
        SyncDiagnosticDashboardDialog(
            viewModel = viewModel,
            onDismiss = { showFullDiagnosticDialog = false }
        )
    }
}

@Composable
private fun UserAccountCard(
    account: UserAccountEntity,
    isCurrent: Boolean,
    canManagePermissions: Boolean,
    isSuperAdmin: Boolean = false,
    onSwitchRequest: () -> Unit,
    onEditPermissionsRequest: () -> Unit,
    onToggleActive: () -> Unit,
    onRegenerateToken: () -> Unit,
    onDeletePermanently: (() -> Unit)? = null,
    onApproveDirectly: (() -> Unit)? = null
) {
    val isPendingApproval = account.syncStatus == "PENDING_MANAGER_APPROVAL"

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isPendingApproval) {
                    Modifier.border(1.5.dp, PmpGold, RoundedCornerShape(18.dp))
                } else Modifier
            ),
        cornerRadius = 18.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Avatar + Name + Role Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isPendingApproval -> PmpGold.copy(alpha = 0.2f)
                                    isCurrent -> PmpBrandRed.copy(alpha = 0.14f)
                                    else -> PmpBrandNavy.copy(alpha = 0.10f)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isPendingApproval -> Icons.Default.HourglassTop
                                isCurrent -> Icons.Default.VerifiedUser
                                else -> Icons.Default.AccountCircle
                            },
                            contentDescription = null,
                            tint = when {
                                isPendingApproval -> Color(0xFFB45309)
                                isCurrent -> PmpBrandRed
                                else -> PmpBrandNavy
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = account.fullName,
                            color = TextDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "@${account.username} • ${account.branch}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                GlassBadge(
                    text = when {
                        isPendingApproval -> "بانتظار الموافقة"
                        isCurrent -> "النشط حالياً"
                        else -> account.role
                    },
                    color = when {
                        isPendingApproval -> Color(0xFFD97706)
                        isCurrent -> PmpBrandRed
                        else -> PmpBlueLight
                    }
                )
            }

            // Account Status & Contact Row (Technical codes visible only to SuperAdmin)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF8FAFC))
                    .border(0.5.dp, GlassBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                    Text(
                        text = if (isSuperAdmin) {
                            "كود الحساب: ${account.userCode} • ${account.phoneOrEmail.ifBlank { account.branch }}"
                        } else {
                            account.phoneOrEmail.ifBlank { "الفرع: ${account.branch}" }
                        },
                        color = PmpBrandNavy,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isSuperAdmin) {
                        Text(
                            text = "رمز الربط: ${account.serverSyncToken}",
                            color = TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                GlassBadge(
                    text = when {
                        isPendingApproval -> "معلّق"
                        account.active -> "مفعل"
                        else -> "موقوف"
                    },
                    color = when {
                        isPendingApproval -> Color(0xFFD97706)
                        account.active -> PmpGreen
                        else -> PmpRed
                    }
                )
            }

            // Permissions Summary Row (visible in UsersScreen for Manager)
            if (canManagePermissions) {
                Text(
                    text = if (account.permissions.isBlank()) {
                        "الصلاحيات: لم يتم تحديد صلاحيات بعد"
                    } else {
                        "الصلاحيات: ${account.permissions.replace(",", " • ")}"
                    },
                    color = if (account.permissions.isBlank()) Color(0xFFB45309) else PmpBrandNavy,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (isPendingApproval && canManagePermissions && onApproveDirectly != null) {
                    Button(
                        onClick = onApproveDirectly,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .testTag("approve_account_btn_${account.id}"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "موافقة واعتماد الحساب",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Manager Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isCurrent && account.active && !isPendingApproval) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(PmpBrandRed.copy(alpha = 0.12f))
                                .border(1.dp, PmpBrandRed.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .clickable { onSwitchRequest() }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.SwitchAccount,
                                    contentDescription = null,
                                    tint = PmpBrandRed,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "تبديل",
                                    color = PmpBrandRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1.4f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isPendingApproval) PmpGreen.copy(alpha = 0.14f)
                                else PmpBrandNavy.copy(alpha = 0.08f)
                            )
                            .border(
                                1.dp,
                                if (isPendingApproval) PmpGreen else PmpBrandNavy.copy(alpha = 0.25f),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onEditPermissionsRequest() }
                            .padding(horizontal = 6.dp)
                            .testTag("edit_permissions_${account.username}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isPendingApproval) Icons.Default.Verified else Icons.Default.AdminPanelSettings,
                                contentDescription = null,
                                tint = if (isPendingApproval) PmpGreen else PmpBrandNavy,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isPendingApproval) "الصلاحيات والموافقة" else "تعديل الصلاحيات",
                                color = if (isPendingApproval) PmpGreen else PmpBrandNavy,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (isSuperAdmin) {
                        Box(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFF1F5F9))
                                .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                                .clickable { onRegenerateToken() }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SyncLock,
                                contentDescription = "تحديث رمز الربط",
                                tint = PmpBrandNavy,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (!isCurrent && !isPendingApproval) {
                        Box(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (account.active) PmpRed.copy(alpha = 0.10f)
                                    else PmpGreen.copy(alpha = 0.12f)
                                )
                                .clickable { onToggleActive() }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (account.active) "إيقاف" else "تفعيل",
                                color = if (account.active) PmpRed else PmpGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    if (!isCurrent && canManagePermissions && onDeletePermanently != null) {
                        Box(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(PmpRed.copy(alpha = 0.12f))
                                .border(1.dp, PmpRed.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                .clickable { onDeletePermanently() }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = "حذف نهائي",
                                tint = PmpRed,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivedUserAccountCard(
    account: UserAccountEntity,
    isSuperAdmin: Boolean = false,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(PmpRed.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonOff,
                            contentDescription = null,
                            tint = PmpRed,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = account.fullName,
                            color = TextDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "@${account.username} • ${account.branch} • ${account.role}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                GlassBadge(text = "موقوف بالأرشيف", color = PmpRed)
            }

            if (isSuperAdmin || account.phoneOrEmail.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF8FAFC))
                        .border(0.5.dp, GlassBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSuperAdmin) {
                        Text(
                            text = "كود الحساب: ${account.userCode}",
                            color = PmpBrandNavy,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = account.phoneOrEmail.ifBlank { "بدون بريد مسجل" },
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            // Action buttons: Restore vs Permanent Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassButton(
                    text = "استرداد الحساب",
                    icon = Icons.Default.Restore,
                    gradient = Brush.horizontalGradient(listOf(Color(0xFF0D9488), Color(0xFF059669))),
                    onClick = onRestore,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                )

                GlassButton(
                    text = "حذف نهائي",
                    icon = Icons.Default.DeleteForever,
                    gradient = Brush.horizontalGradient(listOf(Color(0xFFDC2626), Color(0xFF991B1B))),
                    onClick = onDeletePermanently,
                    modifier = Modifier
                        .weight(0.9f)
                        .height(38.dp)
                )
            }
        }
    }
}
