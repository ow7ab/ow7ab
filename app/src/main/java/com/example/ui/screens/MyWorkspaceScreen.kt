package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.architecture.EmployeeRoleCatalog
import com.example.core.architecture.EmployeeRoleKey
import com.example.data.local.InTransitShipmentEntity
import com.example.data.local.SeqaofaEntryEntity
import com.example.data.local.ShiftProductionLogEntity
import com.example.data.local.ToolCustodyEntity
import com.example.isSystemManager
import com.example.ui.components.GlassBadge
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassDropdown
import com.example.ui.components.GlassTextField
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MyWorkspaceScreen(
    viewModel: PmpViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val shiftLogs by viewModel.shiftProductionLogs.collectAsState()
    val dailyChecklists by viewModel.dailyChecklists.collectAsState()
    val employeeTasks by viewModel.employeeTasks.collectAsState()
    val seqaofaEntries by viewModel.seqaofaEntries.collectAsState()
    val inTransitShipments by viewModel.inTransitShipments.collectAsState()
    val toolCustodies by viewModel.toolCustodies.collectAsState()
    val financialBudgets by viewModel.financialBudgets.collectAsState()

    val isManager = currentUser.isSystemManager()
    val naturalRoleKey = remember(currentUser) {
        EmployeeRoleCatalog.resolveEmployeeRoleKey(currentUser)
    }
    var previewRoleKey by remember(naturalRoleKey) { mutableStateOf(naturalRoleKey) }
    val activeRoleKey = if (isManager) previewRoleKey else naturalRoleKey
    val profile = remember(activeRoleKey) {
        EmployeeRoleCatalog.ALL_PROFILES.getValue(activeRoleKey)
    }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }

    val roleChecklist = remember(dailyChecklists, activeRoleKey, today) {
        dailyChecklists.filter { it.dateKey == today && it.roleKey == activeRoleKey.name }
    }
    val roleTasks = remember(employeeTasks, activeRoleKey, isManager) {
        if (isManager && activeRoleKey == EmployeeRoleKey.GENERAL_MANAGER) {
            employeeTasks
        } else {
            employeeTasks.filter {
                it.roleKey == activeRoleKey.name ||
                    (!currentUser?.username.isNullOrBlank() && it.assigneeUsername == currentUser?.username)
            }
        }
    }

    val todayCartons = remember(shiftLogs, today) {
        shiftLogs.filter { it.shiftDate == today }.sumOf { it.producedCartons }
    }
    val todayWaste = remember(shiftLogs, today) {
        shiftLogs.filter { it.shiftDate == today }.sumOf { it.wasteCans }
    }
    val pendingShipmentsCount = remember(inTransitShipments) {
        inTransitShipments.count { it.status != "تم الاستلام" }
    }

    // Inline quick-entry states
    var lineGroup by remember { mutableStateOf("3 خطوط (دورة أولى)") }
    var producedCartons by remember { mutableStateOf("500") }
    var wasteCans by remember { mutableStateOf("10") }
    var techDowntime by remember { mutableStateOf("0") }
    var downtimeReason by remember { mutableStateOf("") }

    var seqHub by remember { mutableStateOf("حلفا") }
    var seqDirection by remember { mutableStateOf("وارد") }
    var seqAmountSdg by remember { mutableStateOf("500000") }
    var seqRate by remember { mutableStateOf("12.0") }
    var seqPurpose by remember { mutableStateOf("تحويل سقوفة للمبيعات") }

    var toolName by remember { mutableStateOf("طقم مفاتيح وبكر لحام") }
    var toolCustodian by remember { mutableStateOf("منذر مزمل فضل") }
    var toolQty by remember { mutableStateOf("2") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Role Header Card
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "مكتبي • ${profile.titleAr}",
                                color = TextDark,
                                fontWeight = FontWeight.Black,
                                fontSize = 17.sp
                            )
                            Text(
                                text = "${currentUser?.fullName ?: profile.sampleHolders} • ${profile.departmentAr}",
                                color = TextMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        GlassBadge(text = profile.key.name, color = PmpBrandRed)
                    }

                    Text(
                        text = profile.summaryAr,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    if (isManager) {
                        Text(
                            text = "معاينة مكاتب الموظفين (متاح للإدارة):",
                            color = PmpCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            EmployeeRoleCatalog.ROLE_PRESET_LIST.forEach { preset ->
                                val selected = preset.key == activeRoleKey
                                FilterChip(
                                    selected = selected,
                                    onClick = { previewRoleKey = preset.key },
                                    label = {
                                        Text(
                                            text = preset.titleAr,
                                            fontSize = 10.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlassButton(
                            text = "توليد قائمة اليوم (${profile.dailyChecklist.size})",
                            icon = Icons.Default.PlaylistAddCheck,
                            gradient = GlassAccentBlueGradient,
                            onClick = {
                                viewModel.seedDailyChecklistForRole(
                                    roleKey = activeRoleKey,
                                    assigneeName = currentUser?.fullName ?: profile.sampleHolders,
                                    assigneeUsername = currentUser?.username ?: activeRoleKey.name.lowercase(),
                                    branch = currentUser?.branch ?: profile.defaultBranch
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        GlassButton(
                            text = "التشغيل والمكافآت",
                            icon = Icons.Default.Assignment,
                            gradient = PmpBrandRedGradient,
                            onClick = { onNavigate("operations") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // KPI Summary Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PmpWhiteSurface,
                    border = BorderStroke(1.dp, GlassBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("إنتاج اليوم", color = TextMuted, fontSize = 11.sp)
                        Text(
                            "$todayCartons / ${EmployeeRoleCatalog.DAILY_SHIFT_CARTON_TARGET}",
                            color = PmpBrandNavy,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp
                        )
                        Text("كرتونة (تالف: $todayWaste)", color = if (todayWaste > 50) PmpBrandRed else PmpGreen, fontSize = 10.sp)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PmpWhiteSurface,
                    border = BorderStroke(1.dp, GlassBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    val doneCount = roleChecklist.count { it.completed }
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("التحقق اليومي", color = TextMuted, fontSize = 11.sp)
                        Text("$doneCount / ${roleChecklist.size}", color = PmpCyan, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        Text("مهام معلقة: ${roleTasks.count { it.status != "مكتمل" }}", color = TextMuted, fontSize = 10.sp)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PmpWhiteSurface,
                    border = BorderStroke(1.dp, GlassBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("الطريق / الموازنة", color = TextMuted, fontSize = 11.sp)
                        Text("$pendingShipmentsCount شحنات", color = PmpGold, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        val overBudgets = financialBudgets.count { it.deviationPercent > it.maxAllowedDeviationPercent }
                        Text(
                            if (overBudgets > 0) "تجاوز موازنة: $overBudgets" else "الموازنة ≤ 5%",
                            color = if (overBudgets > 0) PmpBrandRed else PmpGreen,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // Dedicated Shortcuts Section
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "أدوات وشاشات ${profile.titleAr}",
                        color = TextDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    profile.focusModules.forEach { mod ->
                        Surface(
                            onClick = { onNavigate(mod.routeKey) },
                            shape = RoundedCornerShape(12.dp),
                            color = PmpSubtleSurface,
                            border = BorderStroke(1.dp, GlassBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mod.labelAr, color = TextDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(mod.descriptionAr, color = TextMuted, fontSize = 11.sp)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = PmpCyan)
                            }
                        }
                    }
                }
            }
        }

        // Role-Specific Quick Entry Card
        when (profile.primaryQuickAction) {
            "SHIFT_ENTRY", "PRODUCTION_KETTLE" -> {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("تسجيل سريع لإنتاج الوردية والتوالف", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            GlassDropdown(
                                label = "مجموعة الخطوط",
                                options = listOf("3 خطوط (دورة أولى)", "خطان (دورة ثانية)", "خط الألكيد والخلط", "خط البلاستيك"),
                                selectedOption = lineGroup,
                                onOptionSelected = { lineGroup = it }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassTextField(
                                    value = producedCartons,
                                    onValueChange = { producedCartons = it },
                                    label = "الكراتين المنتجة",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = wasteCans,
                                    onValueChange = { wasteCans = it },
                                    label = "توالف العبوات",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = techDowntime,
                                    onValueChange = { techDowntime = it },
                                    label = "عطل (دقيقة)",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            GlassTextField(
                                value = downtimeReason,
                                onValueChange = { downtimeReason = it },
                                label = "ملاحظات الأعطال / سبب التوقف",
                                placeholder = "مثال: ضبط سخان الماكينة"
                            )
                            GlassButton(
                                text = "حفظ ومزامنة تقرير الوردية",
                                icon = Icons.Default.Save,
                                gradient = GlassAccentGreenGradient,
                                onClick = {
                                    val is3Line = lineGroup.contains("3")
                                    viewModel.saveShiftProductionLog(
                                        ShiftProductionLogEntity(
                                            syncUuid = "SHF-$today-${System.currentTimeMillis()}",
                                            shiftDate = today,
                                            lineGroup = lineGroup,
                                            engineerName = currentUser?.fullName ?: profile.sampleHolders,
                                            engineerUsername = currentUser?.username ?: "",
                                            activeLinesCount = if (is3Line) 3 else 2,
                                            workersPresent = if (is3Line) 10 else 6,
                                            workersRequired = if (is3Line) 10 else 6,
                                            targetCartons = 500,
                                            producedCartons = producedCartons.toIntOrNull() ?: 0,
                                            wasteCans = wasteCans.toIntOrNull() ?: 0,
                                            maxWasteAllowed = EmployeeRoleCatalog.DEFAULT_MAX_WASTE_CANS_PER_LINE,
                                            technicalDowntimeMin = techDowntime.toIntOrNull() ?: 0,
                                            downtimeReason = downtimeReason
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            "SUDAN_SEQAOFA" -> {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("قيد سقوفة سريع (SDG ⇄ EGP)", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassDropdown(
                                    label = "المخزن / النقطة",
                                    options = listOf("حلفا", "عطبرة", "الخرطوم"),
                                    selectedOption = seqHub,
                                    onOptionSelected = { seqHub = it },
                                    modifier = Modifier.weight(1f)
                                )
                                GlassDropdown(
                                    label = "الحركة",
                                    options = listOf("وارد", "منصرف"),
                                    selectedOption = seqDirection,
                                    onOptionSelected = { seqDirection = it },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassTextField(
                                    value = seqAmountSdg,
                                    onValueChange = { seqAmountSdg = it },
                                    label = "المبلغ (SDG)",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = seqRate,
                                    onValueChange = { seqRate = it },
                                    label = "سعر التحويل",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            GlassTextField(
                                value = seqPurpose,
                                onValueChange = { seqPurpose = it },
                                label = "البيان والغرض"
                            )
                            GlassButton(
                                text = "حفظ حركة السقوفة",
                                icon = Icons.Default.CurrencyExchange,
                                gradient = GlassAccentGoldGradient,
                                onClick = {
                                    val sdg = seqAmountSdg.toDoubleOrNull() ?: 0.0
                                    val rate = (seqRate.toDoubleOrNull() ?: 1.0).coerceAtLeast(0.01)
                                    viewModel.saveSeqaofaEntry(
                                        SeqaofaEntryEntity(
                                            syncUuid = "SEQ-$today-${System.currentTimeMillis()}",
                                            entryDate = today,
                                            hub = seqHub,
                                            custodianName = currentUser?.fullName ?: "عمر عبدالوهاب",
                                            direction = seqDirection,
                                            amountSdg = sdg,
                                            exchangeRate = rate,
                                            equivalentEgp = sdg / rate,
                                            purpose = seqPurpose,
                                            recordedBy = currentUser?.fullName ?: "فرع السودان"
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            "LOADING_TOOLS" -> {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("صرف / جرد عهدة عدة يدوية", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            GlassTextField(value = toolName, onValueChange = { toolName = it }, label = "اسم العدة / الأداة")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassTextField(value = toolCustodian, onValueChange = { toolCustodian = it }, label = "المستلم", modifier = Modifier.weight(1.5f))
                                GlassTextField(value = toolQty, onValueChange = { toolQty = it }, label = "الكمية", isNumber = true, modifier = Modifier.weight(1f))
                            }
                            GlassButton(
                                text = "تسجيل العهدة (${toolCustodies.size} مسجلة)",
                                icon = Icons.Default.Build,
                                gradient = GlassAccentBlueGradient,
                                onClick = {
                                    viewModel.saveToolCustody(
                                        ToolCustodyEntity(
                                            syncUuid = "TL-${System.currentTimeMillis()}",
                                            toolCode = "TL-${toolCustodies.size + 101}",
                                            toolName = toolName,
                                            custodianName = toolCustodian,
                                            quantityIssued = toolQty.toIntOrNull() ?: 1,
                                            issuedDate = today
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // Daily Checklist Items
        item {
            Text(
                text = "قائمة التحقق اليومية ($today)",
                color = TextDark,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (roleChecklist.isEmpty()) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "اضغط (توليد قائمة اليوم) بالأعلى لإنشاء بنود المتابعة اليومية المخصصة لوظيفتك.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(roleChecklist, key = { it.syncUuid }) { item ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (item.completed) PmpSuccessSoftSurface else PmpWhiteSurface,
                    border = BorderStroke(1.dp, if (item.completed) PmpGreen else GlassBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleDailyChecklistItem(item) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (item.completed) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = if (item.completed) PmpGreen else TextMuted
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, color = TextDark, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                            Text("المستهدف: ${item.metricTarget}", color = TextMuted, fontSize = 11.sp)
                        }
                        GlassBadge(
                            text = if (item.completed) "مكتمل" else "مطلوب",
                            color = if (item.completed) PmpGreen else PmpGold
                        )
                    }
                }
            }
        }

        // Role Tasks Section
        item {
            Text(
                text = "المهام المسندة والمتابعة (${roleTasks.size})",
                color = TextDark,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        items(roleTasks, key = { it.syncUuid }) { task ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PmpWhiteSurface,
                border = BorderStroke(1.dp, if (task.hasDeviation) PmpBrandRed else GlassBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.cycleEmployeeTaskStatus(task) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${task.taskCode} • ${task.title}", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${task.assigneeName} • استحقاق: ${task.dueDate}", color = TextMuted, fontSize = 11.sp)
                    }
                    GlassBadge(
                        text = task.status,
                        color = when (task.status) {
                            "مكتمل" -> PmpGreen
                            "متأخر" -> PmpBrandRed
                            "جاري" -> PmpCyan
                            else -> PmpGold
                        }
                    )
                }
            }
        }
    }
}
