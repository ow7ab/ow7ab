package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.architecture.EmployeeRoleCatalog
import com.example.core.architecture.EmployeeRoleKey
import com.example.data.local.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FactoryOperationsScreen(
    viewModel: PmpViewModel,
    modifier: Modifier = Modifier
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val shiftLogs by viewModel.shiftProductionLogs.collectAsState()
    val pressMachines by viewModel.pressMachines.collectAsState()
    val toolCustodies by viewModel.toolCustodies.collectAsState()
    val seqaofaEntries by viewModel.seqaofaEntries.collectAsState()
    val inTransitShipments by viewModel.inTransitShipments.collectAsState()
    val financialBudgets by viewModel.financialBudgets.collectAsState()
    val employeeTasks by viewModel.employeeTasks.collectAsState()
    val employeeRewards by viewModel.employeeRewards.collectAsState()

    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val currentMonth = remember { SimpleDateFormat("yyyy-MM", Locale.US).format(Date()) }

    var activeTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("الورديات", "المكابس والعدد", "السقوفة والطريق", "الموازنة والمهام", "المكافآت")

    // Quick forms states
    var shipCode by remember { mutableStateOf("TRN-HF-${shipCountCode(inTransitShipments.size)}") }
    var shipDest by remember { mutableStateOf("حلفا") }
    var ship116 by remember { mutableStateOf("350") }
    var ship1Kg by remember { mutableStateOf("250") }
    var shipGal by remember { mutableStateOf("100") }
    var shipCustoms by remember { mutableStateOf("1250000") }
    var shipFreight by remember { mutableStateOf("75000") }

    var budgetCategory by remember { mutableStateOf("مشتريات الألكيد والعبوات") }
    var budgetPlanned by remember { mutableStateOf("500000") }
    var budgetActual by remember { mutableStateOf("518000") }

    var taskTitle by remember { mutableStateOf("") }
    var taskAssignee by remember { mutableStateOf("م. مصطفى الصيفي") }
    var taskRole by remember { mutableStateOf(EmployeeRoleKey.PRODUCTION_MANAGER.name) }

    var rewardEmpName by remember { mutableStateOf("م. محمد عبدالباسط") }
    var rewardCompletion by remember { mutableStateOf("100") }
    var rewardDeviation by remember { mutableStateOf("2.5") }
    var rewardErrors by remember { mutableStateOf("0") }
    var rewardInnovations by remember { mutableStateOf("1") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("مركز التشغيل والمتابعة والمكافآت", color = TextDark, fontWeight = FontWeight.Black, fontSize = 16.sp)
                            Text("هدف الوردية: 1,000 كرتونة • حد التوالف: 25/خط • انحراف مالي ≤ 5%", color = TextMuted, fontSize = 11.sp)
                        }
                        GlassButton(
                            text = "تهيئة افتراضية",
                            gradient = GlassAccentGoldGradient,
                            onClick = { viewModel.seedDefaultFactoryOperations() }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tabs.forEachIndexed { idx, title ->
                            FilterChip(
                                selected = activeTab == idx,
                                onClick = { activeTab = idx },
                                label = { Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            )
                        }
                    }
                }
            }
        }

        when (activeTab) {
            0 -> {
                val todayTotal = shiftLogs.filter { it.shiftDate == today }.sumOf { it.producedCartons }
                val progress = ((todayTotal.toDouble() / EmployeeRoleCatalog.DAILY_SHIFT_CARTON_TARGET.toDouble()) * 100.0).toInt().coerceAtLeast(0)
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("إنتاج اليوم (${today}): $todayTotal / 1,000 كرتونة ($progress%)", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            LinearProgressIndicator(
                                progress = { (todayTotal.toFloat() / 1000f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = if (progress >= 95) PmpGreen else PmpBrandBlue
                            )
                        }
                    }
                }
                items(shiftLogs, key = { it.syncUuid }) { log ->
                    val eval = EmployeeRoleCatalog.evaluateShiftMetrics(
                        log.targetCartons,
                        log.producedCartons,
                        log.wasteCans,
                        log.maxWasteAllowed,
                        log.technicalDowntimeMin,
                        log.materialDowntimeMin
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PmpWhiteSurface,
                        border = BorderStroke(1.dp, if (eval.wasteExceeded) PmpBrandRed else GlassBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${log.shiftDate} • ${log.lineGroup}", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                GlassBadge(text = eval.statusLabel, color = if (eval.wasteExceeded) PmpBrandRed else PmpGreen)
                            }
                            Text("المهندس: ${log.engineerName} • الإنتاج: ${log.producedCartons}/${log.targetCartons} كرتونة (${eval.achievementPercent}%)", color = TextSecondary, fontSize = 11.5.sp)
                            Text("التوالف: ${log.wasteCans}/${log.maxWasteAllowed} عبوة • الأعطال: ${eval.totalDowntimeMin} د • ${log.downtimeReason}", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
            1 -> {
                item {
                    Text("المكابس والماكينات (${pressMachines.size})", color = TextDark, fontWeight = FontWeight.Black, fontSize = 14.sp)
                }
                items(pressMachines, key = { it.syncUuid }) { press ->
                    val nextStatus = when (press.pressStatus) {
                        "يعمل بكفاءة" -> "جاهز / احتياط"
                        "جاهز / احتياط" -> "صيانة / عطل"
                        else -> "يعمل بكفاءة"
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PmpWhiteSurface,
                        border = BorderStroke(1.dp, GlassBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.savePressMachine(press.copy(pressStatus = nextStatus)) }
                    ) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${press.machineCode} • ${press.machineName}", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("المسؤول: ${press.assignedOperator} • ${press.notes}", color = TextMuted, fontSize = 11.sp)
                            }
                            GlassBadge(
                                text = press.pressStatus,
                                color = if (press.pressStatus.contains("يعمل")) PmpGreen else if (press.pressStatus.contains("عطل")) PmpBrandRed else PmpGold
                            )
                        }
                    }
                }
                item {
                    Text("عُهد العدد والتوكيلات (${toolCustodies.size})", color = TextDark, fontWeight = FontWeight.Black, fontSize = 14.sp)
                }
                items(toolCustodies, key = { it.syncUuid }) { tool ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PmpWhiteSurface,
                        border = BorderStroke(1.dp, GlassBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${tool.toolCode} • ${tool.toolName}", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("باسم: ${tool.custodianName} • مصروف: ${tool.quantityIssued} / مرتجع: ${tool.quantityReturned} / تالف: ${tool.damagedCount}", color = TextMuted, fontSize = 11.sp)
                            }
                            GlassBadge(text = tool.status, color = PmpCyan)
                        }
                    }
                }
            }
            2 -> {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("إرسال رسالة بضاعة في الطريق للسودان", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassTextField(value = shipCode, onValueChange = { shipCode = it }, label = "رقم الشحنة", modifier = Modifier.weight(1f))
                                GlassDropdown(
                                    label = "الوجهة",
                                    options = listOf("حلفا", "عطبرة", "الخرطوم"),
                                    selectedOption = shipDest,
                                    onOptionSelected = { shipDest = it },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassTextField(value = ship116, onValueChange = { ship116 = it }, label = "1/16 ك", isNumber = true, modifier = Modifier.weight(1f))
                                GlassTextField(value = ship1Kg, onValueChange = { ship1Kg = it }, label = "1 كجم", isNumber = true, modifier = Modifier.weight(1f))
                                GlassTextField(value = shipGal, onValueChange = { shipGal = it }, label = "جالون", isNumber = true, modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassTextField(value = shipCustoms, onValueChange = { shipCustoms = it }, label = "جمرك SDG", isNumber = true, modifier = Modifier.weight(1f))
                                GlassTextField(value = shipFreight, onValueChange = { shipFreight = it }, label = "نولون EGP", isNumber = true, modifier = Modifier.weight(1f))
                            }
                            GlassButton(
                                text = "تسجيل شحنة في الطريق",
                                icon = Icons.Default.LocalShipping,
                                gradient = GlassAccentBlueGradient,
                                onClick = {
                                    viewModel.saveInTransitShipment(
                                        InTransitShipmentEntity(
                                            syncUuid = "TRN-$today-${System.currentTimeMillis()}",
                                            shipmentCode = shipCode,
                                            dispatchDate = today,
                                            destinationHub = shipDest,
                                            cartons116 = ship116.toIntOrNull() ?: 0,
                                            cartons1Kg = ship1Kg.toIntOrNull() ?: 0,
                                            cartonsGallon = shipGal.toIntOrNull() ?: 0,
                                            customsSdg = shipCustoms.toDoubleOrNull() ?: 0.0,
                                            freightEgp = shipFreight.toDoubleOrNull() ?: 0.0,
                                            status = "خرج من مصنع مصر"
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                items(inTransitShipments, key = { it.syncUuid }) { sh ->
                    val isDelivered = sh.status == "تم الاستلام"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDelivered) PmpSuccessSoftSurface else PmpWhiteSurface,
                        border = BorderStroke(1.dp, if (isDelivered) PmpGreen else GlassBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${sh.shipmentCode} → ${sh.destinationHub}", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                GlassBadge(text = sh.status, color = if (isDelivered) PmpGreen else PmpGold)
                            }
                            Text("1/16: ${sh.cartons116} ك • 1 ك: ${sh.cartons1Kg} ك • جالون: ${sh.cartonsGallon} ك", color = TextSecondary, fontSize = 11.5.sp)
                            Text("جمرك: ${sh.customsSdg.toLong()} SDG • نولون: ${sh.freightEgp.toLong()} EGP", color = TextMuted, fontSize = 11.sp)
                            if (!isDelivered) {
                                GlassButton(
                                    text = "تأكيد الاستلام بالمخزن",
                                    icon = Icons.Default.CheckCircle,
                                    gradient = GlassAccentGreenGradient,
                                    onClick = { viewModel.markInTransitShipmentReceived(sh) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
                item {
                    Text("دفتر السقوفة (${seqaofaEntries.size})", color = TextDark, fontWeight = FontWeight.Black, fontSize = 14.sp)
                }
                items(seqaofaEntries, key = { it.syncUuid }) { s ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PmpWhiteSurface,
                        border = BorderStroke(1.dp, GlassBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${s.hub} • ${s.custodianName} (${s.direction})", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                Text("${s.entryDate} • ${s.purpose}", color = TextMuted, fontSize = 11.sp)
                            }
                            Text("${s.amountSdg.toLong()} SDG", color = if (s.direction == "وارد") PmpGreen else PmpBrandRed, fontWeight = FontWeight.Black, fontSize = 13.sp)
                        }
                    }
                }
            }
            3 -> {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("بند موازنة مالية (حد الانحراف 5%)", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            GlassTextField(value = budgetCategory, onValueChange = { budgetCategory = it }, label = "البند / القسم")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassTextField(value = budgetPlanned, onValueChange = { budgetPlanned = it }, label = "المخطط EGP", isNumber = true, modifier = Modifier.weight(1f))
                                GlassTextField(value = budgetActual, onValueChange = { budgetActual = it }, label = "الفعلي EGP", isNumber = true, modifier = Modifier.weight(1f))
                            }
                            GlassButton(
                                text = "حفظ ومراجعة الانحراف",
                                icon = Icons.Default.Analytics,
                                gradient = GlassAccentBlueGradient,
                                onClick = {
                                    viewModel.saveFinancialBudget(
                                        FinancialBudgetEntity(
                                            syncUuid = "BUD-$currentMonth-${System.currentTimeMillis()}",
                                            periodMonth = currentMonth,
                                            category = budgetCategory,
                                            plannedAmount = budgetPlanned.toDoubleOrNull() ?: 0.0,
                                            actualAmount = budgetActual.toDoubleOrNull() ?: 0.0,
                                            supervisorName = currentUser?.fullName ?: "أ. أحمد الطلخاوي"
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                items(financialBudgets, key = { it.syncUuid }) { b ->
                    val exceeded = b.deviationPercent > b.maxAllowedDeviationPercent
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PmpWhiteSurface,
                        border = BorderStroke(1.dp, if (exceeded) PmpBrandRed else GlassBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(b.category, color = TextDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                GlassBadge(text = "انحراف ${b.deviationPercent}%", color = if (exceeded) PmpBrandRed else PmpGreen)
                            }
                            Text("مخطط: ${b.plannedAmount.toLong()} • فعلي: ${b.actualAmount.toLong()} ${b.currency}", color = TextMuted, fontSize = 11.5.sp)
                        }
                    }
                }
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("تكليف مهمة لموظف", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            GlassTextField(value = taskTitle, onValueChange = { taskTitle = it }, label = "عنوان المهمة")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassTextField(value = taskAssignee, onValueChange = { taskAssignee = it }, label = "الموظف المسؤول", modifier = Modifier.weight(1f))
                                GlassDropdown(
                                    label = "الدور",
                                    options = EmployeeRoleKey.entries.map { it.name },
                                    selectedOption = taskRole,
                                    onOptionSelected = { taskRole = it },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            GlassButton(
                                text = "إرسال المهمة للموظف",
                                icon = Icons.Default.AddTask,
                                gradient = PmpBrandRedGradient,
                                onClick = {
                                    if (taskTitle.isNotBlank()) {
                                        viewModel.saveEmployeeTask(
                                            EmployeeTaskEntity(
                                                syncUuid = "TSK-${System.currentTimeMillis()}",
                                                taskCode = "TSK-${employeeTasks.size + 101}",
                                                title = taskTitle,
                                                roleKey = taskRole,
                                                assigneeName = taskAssignee,
                                                assignedBy = currentUser?.fullName ?: "الإدارة العامة",
                                                dueDate = today
                                            )
                                        )
                                        taskTitle = ""
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            4 -> {
                item {
                    val liveTier = EmployeeRoleCatalog.evaluateEmployeeBonusTier(
                        completionPercent = rewardCompletion.toDoubleOrNull() ?: 0.0,
                        deviationPercent = rewardDeviation.toDoubleOrNull() ?: 0.0,
                        errorCount = rewardErrors.toIntOrNull() ?: 0,
                        innovationCount = rewardInnovations.toIntOrNull() ?: 0
                    )
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("تقييم مكافأة الموسم (3/4/5 شهور)", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                GlassBadge(text = liveTier.bonusTier, color = if (liveTier.bonusMonths > 0) PmpGreen else PmpBrandRed)
                            }
                            GlassTextField(value = rewardEmpName, onValueChange = { rewardEmpName = it }, label = "اسم الموظف")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassTextField(value = rewardCompletion, onValueChange = { rewardCompletion = it }, label = "الإنجاز %", isNumber = true, modifier = Modifier.weight(1f))
                                GlassTextField(value = rewardDeviation, onValueChange = { rewardDeviation = it }, label = "الانحراف %", isNumber = true, modifier = Modifier.weight(1f))
                                GlassTextField(value = rewardErrors, onValueChange = { rewardErrors = it }, label = "الأخطاء", isNumber = true, modifier = Modifier.weight(1f))
                                GlassTextField(value = rewardInnovations, onValueChange = { rewardInnovations = it }, label = "تطوير", isNumber = true, modifier = Modifier.weight(1f))
                            }
                            GlassButton(
                                text = "اعتماد التقييم (${liveTier.bonusMonths} شهور)",
                                icon = Icons.Default.EmojiEvents,
                                gradient = GlassAccentGoldGradient,
                                onClick = {
                                    viewModel.saveEmployeeReward(
                                        EmployeeRewardEntity(
                                            syncUuid = "RWD-$currentMonth-${System.currentTimeMillis()}",
                                            periodLabel = "موسم $currentMonth",
                                            employeeName = rewardEmpName,
                                            completionPercent = rewardCompletion.toDoubleOrNull() ?: 0.0,
                                            deviationPercent = rewardDeviation.toDoubleOrNull() ?: 0.0,
                                            errorCount = rewardErrors.toIntOrNull() ?: 0,
                                            innovationCount = rewardInnovations.toIntOrNull() ?: 0,
                                            evaluatedBy = currentUser?.fullName ?: "الإدارة العامة"
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                items(employeeRewards, key = { it.syncUuid }) { r ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PmpWhiteSurface,
                        border = BorderStroke(1.dp, if (r.bonusMonths > 0) PmpGreen else GlassBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${r.employeeName} • ${r.periodLabel}", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("إنجاز: ${r.completionPercent}% • انحراف: ${r.deviationPercent}% • أخطاء: ${r.errorCount} • تطوير: ${r.innovationCount}", color = TextMuted, fontSize = 11.sp)
                            }
                            GlassBadge(text = r.bonusTier, color = if (r.bonusMonths >= 4) PmpGreen else if (r.bonusMonths == 3) PmpCyan else PmpBrandRed)
                        }
                    }
                }
            }
        }
    }
}

private fun shipCountCode(size: Int): String = (size + 101).toString()
