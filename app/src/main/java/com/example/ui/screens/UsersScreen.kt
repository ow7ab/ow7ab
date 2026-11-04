package com.example.ui.screens

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.UserAccountEntity
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
    val isManager = currentUser.isSystemManager()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = if (isManager) {
        listOf("حسابات المستخدمين", "+ إنشاء حساب", "الأرشيف", "سيرفر السحابة والويندوز")
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
        "الأسعار",
        "تصدير PDF",
        "الخزن",
        "التحويلات",
        "المخازن",
        "المصنع",
        "المختبر الكيميائي",
        "مواد الإنتاج",
        "التعبئة والتغليف",
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
                    text = "إدارة حسابات المستخدمين واعتماد الصلاحيات",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = if (isManager) {
                        "تعديل الصلاحيات واعتماد حسابات Google الجديدة حصرياً عبر حساب مدير النظام"
                    } else {
                        "لا يمكن تعديل الصلاحيات إلا من خلال حساب مدير النظام فقط"
                    },
                    color = TextMuted,
                    fontSize = 12.sp,
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

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "كود الربط: ${activeUser.userCode} | ${activeUser.serverSyncToken}",
                                            color = PmpBrandNavy,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        GlassBadge(
                                            text = "متصل محلياً وجاهز للسيرفر",
                                            color = PmpGreen
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        GlassButton(
                                            text = "مزامنة المستخدمين مع السيرفر",
                                            icon = Icons.Default.CloudSync,
                                            gradient = PmpBrandRedGradient,
                                            onClick = { viewModel.syncAllAccountsToGoogleCloud() },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("quick_sync_users_cloud_button")
                                        )

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
                                                text = "طلبات تسجيل دخول عبر Google بانتظار موافقة المدير (${pendingAccounts.size})",
                                                color = Color(0xFF92400E),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                    Text(
                                        text = "لا يُسمح لهذه الحسابات بالدخول للتطبيق حتى تحدد الصلاحيات المخصصة لها وتعتمد تفعيل الحساب.",
                                        color = Color(0xFFB45309),
                                        fontSize = 11.5.sp
                                    )
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
                                        label = "اسم الدخول (Username)",
                                        placeholder = "مثال: atbara_acc",
                                        leadingIcon = Icons.Default.AlternateEmail,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("user_username_input")
                                    )

                                    GlassTextField(
                                        value = pinOrPassword,
                                        onValueChange = { pinOrPassword = it },
                                        label = "رمز الدخول (PIN/كلمة السر)",
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
                                    label = "رقم الهاتف أو البريد الإلكتروني (للتوثيق مع السيرفر)",
                                    placeholder = "+249912345678 / user@pmp-paints.com",
                                    leadingIcon = Icons.Default.Phone,
                                    modifier = Modifier.testTag("user_contact_input")
                                )

                                GlassDropdown(
                                    label = "الدور الوظيفي (Role)",
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

                                Text(
                                    text = "تحديد الصلاحيات المخصصة للموظف (بما فيها صلاحية تصدير الفواتير PDF):",
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )

                                // Permissions Chips Grid
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    generalPermissions.chunked(3).forEach { rowPerms ->
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
                                                        .padding(horizontal = 4.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = perm,
                                                        color = if (isSelected) PmpBrandRed else TextMuted,
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

                                GlassTextField(
                                    value = serverEndpointInput,
                                    onValueChange = { serverEndpointInput = it },
                                    label = "نقطة ربط السيرفر المركزي (Server API Endpoint)",
                                    placeholder = "https://api.pmp-paints-erp.com/v1",
                                    leadingIcon = Icons.Default.CloudQueue
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                GlassButton(
                                    text = "إنشاء الحساب وتوليد معرف الربط السحابي",
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
                                Text(
                                    text = "الحسابات الموقوفة هنا لا يمكنها تسجيل الدخول للنظام. يمكنك استرداد أي حساب لإعادته للعمل فوراً، أو حذفه نهائياً من قاعدة البيانات والسحابة.",
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
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
                                onRestore = { viewModel.restoreUserAccount(account) },
                                onDeletePermanently = { userToDeletePermanently = account }
                            )
                        }
                    }
                }
            }

            3 -> {
                // Cloud Server Hub & Windows Cross-Device Integration
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    // 1. Data Safety & Migration Protection Card (Anti-Data-Loss Architecture)
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
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
                                        Icon(
                                            imageVector = Icons.Default.Security,
                                            contentDescription = null,
                                            tint = PmpGreen,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Text(
                                            text = "حماية البيانات الدائمة من الحذف عند التحديث",
                                            color = TextWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                    GlassBadge(text = "محمي 100%", color = PmpGreen)
                                }

                                Text(
                                    text = "تم إصلاح الخلل بشكل جذري: تم إلغاء الحذف التدميري (Destructive Migration) وتفعيل مسار ترقية سلس لقاعدة البيانات (Room Migrations V1-V5)، بالإضافة إلى نظام النسخ الاحتياطي المشفر والمزامنة السحابية المباشرة مع سيرفر Google Cloud.",
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    GlassBadge(text = "هجرة آمنة V1-V5", color = PmpBrandNavy)
                                    GlassBadge(text = "نسخة مشفرة محلية", color = PmpBlueLight)
                                    GlassBadge(text = "مرآة سحابية Cloud", color = PmpGreen)
                                }

                                if (isSyncing) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            color = PmpBrandRed,
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.5.dp
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "جارٍ المعالجة والمزامنة السحابية...",
                                            color = PmpBrandNavy,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                GlassButton(
                                    text = "حفظ ومزامنة شاملة لسيرفر Google Cloud الآن (حفظ دائم)",
                                    icon = Icons.Default.CloudUpload,
                                    gradient = PmpLogoSwooshGradient,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("sync_all_erp_server_button"),
                                    onClick = {
                                        viewModel.syncAllErpDataToServer()
                                    }
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    GlassButton(
                                        text = "استعادة من السيرفر",
                                        icon = Icons.Default.CloudDownload,
                                        gradient = GlassAccentBlueGradient,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("restore_erp_server_button"),
                                        onClick = {
                                            viewModel.restoreAllErpDataFromServer()
                                        }
                                    )

                                    GlassButton(
                                        text = "نسخة محلية فورية",
                                        icon = Icons.Default.Save,
                                        gradient = Brush.horizontalGradient(listOf(Color(0xFF0F172A), Color(0xFF334155))),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("save_local_snapshot_button"),
                                        onClick = {
                                            viewModel.saveLocalSnapshotNow()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 2. Google Cloud Server Status & Health Diagnostics
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
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
                                        text = "حالة سيرفر Google Cloud واستقرار الاتصال",
                                        color = TextWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    val isOnline = serverHealthReport?.isOnline == true
                                    GlassBadge(
                                        text = if (isOnline) "سيرفر مستقر أونلاين" else "السيرفر قيد الاتصال",
                                        color = if (isOnline) PmpGreen else Color(0xFFF59E0B)
                                    )
                                }

                                Text(
                                    text = serverHealthReport?.message ?: cloudSyncState,
                                    color = PmpBrandNavy,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )

                                serverHealthReport?.let { report ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFFF8FAFC))
                                                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                                                .padding(8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("سرعة الاستجابة", fontSize = 10.sp, color = TextMuted)
                                                Text("${report.responseTimeMs} ms", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PmpGreen)
                                            }
                                        }

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFFF8FAFC))
                                                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                                                .padding(8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("فواتير السيرفر", fontSize = 10.sp, color = TextMuted)
                                                Text("${report.cloudInvoicesCount}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PmpBrandNavy)
                                            }
                                        }

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFFF8FAFC))
                                                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                                                .padding(8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("حركات وخزن", fontSize = 10.sp, color = TextMuted)
                                                Text("${report.cloudMovementsCount}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PmpBrandNavy)
                                            }
                                        }

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFFF8FAFC))
                                                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                                                .padding(8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("المستخدمين", fontSize = 10.sp, color = TextMuted)
                                                Text("${report.cloudUsersCount}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PmpBlueLight)
                                            }
                                        }
                                    }
                                }

                                GlassTextField(
                                    value = clusterCodeInput,
                                    onValueChange = {
                                        clusterCodeInput = it
                                        viewModel.setServerClusterCode(it)
                                    },
                                    label = "معرف السيرفر وعنقود البيانات (Cluster Code)",
                                    placeholder = "PMP-MAIN-CLOUD-SERVER",
                                    leadingIcon = Icons.Default.Hub,
                                    modifier = Modifier.testTag("server_cluster_code_input")
                                )

                                GlassButton(
                                    text = "فحص استقرار السيرفر وسرعة الاستجابة",
                                    icon = Icons.Default.Speed,
                                    gradient = GlassAccentBlueGradient,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("check_server_health_button"),
                                    onClick = {
                                        viewModel.checkServerHealth()
                                    }
                                )
                            }
                        }
                    }

                    // 2.5. Google Cloud Server Sync Diagnostics Log
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
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
                                        Icon(
                                            imageVector = Icons.Default.Analytics,
                                            contentDescription = null,
                                            tint = PmpCyan,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Text(
                                            text = "سجل تشخيص وأمان المزامنة (Sync Diagnostics Log)",
                                            color = TextWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                    GlassBadge(
                                        text = "${syncDiagnostics.size} عمليات مسجلة",
                                        color = PmpCyan
                                    )
                                }

                                Text(
                                    text = "تسجيل شفاف لكافة عمليات المزامنة مع Google Cloud مع التوقيت، معرف الجهاز، ونوع العملية لضمان عدم حدوث أي تعارض أو تسجيل خروج قسري.",
                                    color = TextMuted,
                                    fontSize = 11.5.sp,
                                    lineHeight = 17.sp
                                )

                                GlassButton(
                                    text = "فتح لوحة تشخيص ومراقبة المزامنة المتقدمة",
                                    icon = Icons.Default.Dns,
                                    gradient = PmpLogoSwooshGradient,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("users_open_diagnostic_dashboard_button"),
                                    onClick = { showFullDiagnosticDialog = true }
                                )

                                if (syncDiagnostics.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFFF8FAFC))
                                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                                            .padding(12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "لا توجد عمليات مزامنة مسجلة بعد في هذه الجلسة. اضغط على «حفظ ومزامنة شاملة» للبدء.",
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                } else {
                                    val timeFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US) }
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        syncDiagnostics.take(6).forEach { diag ->
                                            val actionTitle = when (diag.action) {
                                                "SYNC_PUSH" -> "رفع ومزامنة البيانات"
                                                "PULL_USERS" -> "مزامنة الحسابات المشتركة"
                                                "RESTORE_PULL" -> "استعادة شاملة من السيرفر"
                                                else -> diag.action
                                            }
                                            val isSuccess = diag.status == "STATUS_SUCCESS"
                                            val isInProgress = diag.status == "IN_PROGRESS"

                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color(0xFFF8FAFC))
                                                    .border(
                                                        1.dp,
                                                        if (isSuccess) PmpGreen.copy(alpha = 0.5f) else if (isInProgress) PmpCyan.copy(alpha = 0.5f) else PmpRed.copy(alpha = 0.5f),
                                                        RoundedCornerShape(8.dp)
                                                    )
                                                    .padding(10.dp)
                                            ) {
                                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = actionTitle,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 12.5.sp,
                                                            color = PmpBrandNavy
                                                        )
                                                        GlassBadge(
                                                            text = if (isSuccess) "ناجحة" else if (isInProgress) "جارية" else "فشلت",
                                                            color = if (isSuccess) PmpGreen else if (isInProgress) PmpCyan else PmpRed
                                                        )
                                                    }
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(
                                                            text = "الجهاز: ${diag.deviceId.take(12)}... • السجلات: ${diag.entitiesCount}",
                                                            fontSize = 10.5.sp,
                                                            color = TextMuted
                                                        )
                                                        Text(
                                                            text = timeFormat.format(java.util.Date(diag.timestamp)),
                                                            fontSize = 10.sp,
                                                            color = TextMuted
                                                        )
                                                    }
                                                    if (diag.message.isNotBlank()) {
                                                        Text(
                                                            text = "البيان: ${diag.message}",
                                                            fontSize = 10.sp,
                                                            color = if (isSuccess) PmpBrandNavy else PmpRed,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Windows Desktop & Multi-Device Integration Hub
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
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
                                        Icon(
                                            imageVector = Icons.Default.Computer,
                                            contentDescription = null,
                                            tint = PmpBlueLight,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Text(
                                            text = "ربط برنامج الويندوز مع الهاتف (Windows & Phone Sync)",
                                            color = TextWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                    GlassBadge(text = "Windows & Android", color = PmpBlueLight)
                                }

                                Text(
                                    text = "يتصل تطبيق الهاتف وبرنامج الكمبيوتر (Windows Desktop) بنفس قاعدة بيانات Google Cloud المركزية لحظياً. أي حركة بيع، صرف، أو فاتورة تسجل من أي جهاز تنعكس تلقائياً على باقي الأجهزة دون تعارض وبنفس الصلاحيات المحددة للمستخدم.",
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )

                                Text(
                                    text = "بيانات الربط مع برنامج الويندوز (Windows Endpoint Configuration):",
                                    color = PmpBrandNavy,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                val windowsApiSnippet = """
// رابط وخادم الربط مع برنامج الويندوز (REST & Firestore SDK)
Server Endpoint: ${serverHealthReport?.endpoint ?: "https://firestore.googleapis.com/v1/projects/erp-pmp-cloud"}
Cluster ID: $clusterCodeInput
Supported Clients: Windows Desktop (C#/WPF/Electron), Android Mobile
Auth Protocol: Google Cloud IAM & Token Verification
Realtime Sync: Enabled (Bi-directional)
                                """.trimIndent()

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF0F172A))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = windowsApiSnippet,
                                        color = Color(0xFF38BDF8),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 16.sp
                                    )
                                }

                                HorizontalDivider(color = GlassBorder)

                                GlassTextField(
                                    value = cloudLinkCodeInput,
                                    onValueChange = { cloudLinkCodeInput = it },
                                    label = "ربط ومزامنة حساب موظف من السيرفر بواسطة الكود (User Code)",
                                    placeholder = "مثال: USR-SD-002 أو USR-HF-004",
                                    leadingIcon = Icons.Default.Link,
                                    modifier = Modifier.testTag("cloud_link_code_input")
                                )

                                GlassButton(
                                    text = "استيراد ومزامنة حساب وصلاحيات الموظف من السيرفر",
                                    icon = Icons.Default.CloudDownload,
                                    gradient = GlassAccentBlueGradient,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("link_account_by_code_button"),
                                    onClick = {
                                        viewModel.linkAccountFromGoogleCloudByCode(
                                            userCode = cloudLinkCodeInput,
                                            activateSession = false
                                        )
                                    }
                                )
                            }
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
        AlertDialog(
            onDismissRequest = { userPendingPermEdit = null },
            containerColor = Color.White,
            title = {
                Text(
                    text = if (isPendingApproval) {
                        "تحديد الصلاحيات والموافقة على إنشاء الحساب (${targetUser.fullName})"
                    } else {
                        "تعديل صلاحيات الموظف (${targetUser.fullName})"
                    },
                    color = TextDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.5.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (isPendingApproval) {
                            "هذا الحساب سجل عبر Google وهو معلّق حالياً. حدد الصلاحيات المسموح له بها قبل الموافقة على تسجيل الدخول (ويمكنك تغييرها مستقبلاً في أي وقت):"
                        } else {
                            "حدد الصلاحيات المسموح بها لهذا الحساب فقط. ستختفي جميع الصلاحيات غير المحددة فوراً من حساب الموظف:"
                        },
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    GlassDropdown(
                        label = "الدور الوظيفي",
                        options = roleOptions,
                        selectedOption = editRoleState,
                        onOptionSelected = { editRoleState = it }
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

                    Text(
                        text = "صلاحيات الأقسام العامة:",
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        generalPermissions.chunked(3).forEach { rowPerms ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowPerms.forEach { perm ->
                                    val isSelected = perm in editPermsState
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(
                                                if (isSelected) PmpBrandRed.copy(alpha = 0.14f)
                                                else Color(0xFFF1F5F9)
                                            )
                                            .border(
                                                1.dp,
                                                if (isSelected) PmpBrandRed else GlassBorder,
                                                RoundedCornerShape(9.dp)
                                            )
                                            .clickable {
                                                editPermsState = if (isSelected) {
                                                    editPermsState - perm
                                                } else {
                                                    editPermsState + perm
                                                }
                                            }
                                            .padding(horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (isSelected) "✓ $perm" else perm,
                                            color = if (isSelected) PmpBrandRed else TextMuted,
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

                    Text(
                        text = "صلاحيات أقسام التقارير التفصيلية (تحديد ما يراه الموظف كالمبيعات وحجب المواد الخام):",
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
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
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(
                                                if (isSelected) PmpBrandNavy.copy(alpha = 0.14f)
                                                else Color(0xFFF1F5F9)
                                            )
                                            .border(
                                                1.dp,
                                                if (isSelected) PmpBrandNavy else GlassBorder,
                                                RoundedCornerShape(9.dp)
                                            )
                                            .clickable {
                                                editPermsState = if (isSelected) {
                                                    editPermsState - perm
                                                } else {
                                                    editPermsState + perm
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
                }
            },
            confirmButton = {
                GlassButton(
                    text = if (isPendingApproval) "اعتماد الحساب وتفعيل الدخول" else "حفظ الصلاحيات ومزامنة السحابة",
                    icon = if (isPendingApproval) Icons.Default.Verified else Icons.Default.CloudUpload,
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
                        text = "هل أنت متأكد من رغبتك في حذف حساب (${target.fullName} - @${target.username}) نهائياً من قاعدة البيانات وسيرفر السحابة؟",
                        color = TextWhite,
                        fontSize = 13.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "تحذير: هذا الإجراء لا يمكن التراجع عنه وسيتم إزالة كافة صلاحيات ومعرف ربط السيرفر لهذا الحساب فوراً.",
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

    if (showFullDiagnosticDialog) {
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
                        isPendingApproval -> "بانتظار موافقة المدير"
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

            // Server Linkage Info Row
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "معرف السيرفر: ${account.userCode} • ${account.phoneOrEmail.ifBlank { account.remoteServerId }}",
                        color = PmpBrandNavy,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Sync Token: ${account.serverSyncToken}",
                        color = TextMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                GlassBadge(
                    text = when {
                        isPendingApproval -> "معلّق (غير مصرح بالدخول)"
                        account.active -> "معتمد ومفعل"
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
                        "الصلاحيات المخصصة: لم يتم تحديد صلاحيات بعد (حدد الصلاحيات قبل الموافقة على الدخول)"
                    } else {
                        "الصلاحيات المخصصة: ${account.permissions.replace(",", " • ")}"
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
                            text = "موافقة المدير على طلب إنشاء الحساب",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp
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
                                .clickable { onSwitchRequest() },
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
                                    text = "تبديل للحساب",
                                    color = PmpBrandRed,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1.3f)
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
                                text = if (isPendingApproval) "تحديد الصلاحيات والموافقة" else "تعديل الصلاحيات",
                                color = if (isPendingApproval) PmpGreen else PmpBrandNavy,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

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

            // Server ID row
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
                Text(
                    text = "كود الحساب: ${account.userCode}",
                    color = PmpBrandNavy,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = account.phoneOrEmail.ifBlank { "بدون بريد مسجل" },
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            // Action buttons: Restore vs Permanent Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassButton(
                    text = "استرداد الحساب للعمل",
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
