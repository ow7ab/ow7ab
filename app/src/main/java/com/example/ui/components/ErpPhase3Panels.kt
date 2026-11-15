package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * 1. Procurement GRN Inspection -> RAW_MATERIALS Warehouse Panel (PurchasesScreen)
 */
@Composable
fun ProcurementGrnPhase3Card(
    viewModel: PmpViewModel,
    selectedItem: FactoryItemEntity?,
    fallbackMaterialName: String,
    currency: String,
    supplierName: String,
    note: String
) {
    val purchaseReceipts by viewModel.purchaseReceipts.collectAsState()
    var batchNumber by remember { mutableStateOf("") }
    var expiryDate by remember { mutableStateOf("") }
    var orderedQty by remember { mutableStateOf("100") }
    var receivedQty by remember { mutableStateOf("100") }
    var acceptedQty by remember { mutableStateOf("100") }
    var rejectedQty by remember { mutableStateOf("0") }
    var unitCost by remember { mutableStateOf("0") }
    var discrepancyReason by remember { mutableStateOf("") }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = Color.White,
        borderColor = PmpCyan.copy(alpha = 0.55f),
        cornerRadius = 20.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.FactCheck, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(20.dp))
                    Text(
                        text = "استلام وفحص المشتريات (GRN -> مخزن المواد الخام)",
                        color = PmpBrandNavy,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.5.sp
                    )
                }
                GlassBadge(text = "${purchaseReceipts.size} سند", color = PmpCyan)
            }

            Text(
                text = "يسجل هذا المستند الكمية المقبولة في مخزن المواد الخام فوراً ويعزل الكمية المرفوضة تحت الحجر (Quarantine).",
                color = TextMuted,
                fontSize = 11.5.sp
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassTextField(
                    value = batchNumber,
                    onValueChange = { batchNumber = it },
                    label = "رقم التشغيلة Batch *",
                    placeholder = "BAT-RAW-01",
                    modifier = Modifier.weight(1f)
                )
                GlassTextField(
                    value = expiryDate,
                    onValueChange = { expiryDate = it },
                    label = "تاريخ الصلاحية",
                    placeholder = "2027-12-31",
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassTextField(
                    value = orderedQty,
                    onValueChange = { orderedQty = it },
                    label = "المطلوبة",
                    isNumber = true,
                    modifier = Modifier.weight(1f)
                )
                GlassTextField(
                    value = receivedQty,
                    onValueChange = {
                        receivedQty = it
                        val rec = it.toDoubleOrNull() ?: 0.0
                        val rej = rejectedQty.toDoubleOrNull() ?: 0.0
                        acceptedQty = (rec - rej).coerceAtLeast(0.0).toString()
                    },
                    label = "المستلمة *",
                    isNumber = true,
                    modifier = Modifier.weight(1f)
                )
                GlassTextField(
                    value = acceptedQty,
                    onValueChange = {
                        acceptedQty = it
                        val rec = receivedQty.toDoubleOrNull() ?: 0.0
                        val acc = it.toDoubleOrNull() ?: 0.0
                        rejectedQty = (rec - acc).coerceAtLeast(0.0).toString()
                    },
                    label = "المقبولة *",
                    isNumber = true,
                    modifier = Modifier.weight(1f)
                )
                GlassTextField(
                    value = rejectedQty,
                    onValueChange = {
                        rejectedQty = it
                        val rec = receivedQty.toDoubleOrNull() ?: 0.0
                        val rej = it.toDoubleOrNull() ?: 0.0
                        acceptedQty = (rec - rej).coerceAtLeast(0.0).toString()
                    },
                    label = "المرفوضة",
                    isNumber = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassTextField(
                    value = unitCost,
                    onValueChange = { unitCost = it },
                    label = "تكلفة الوحدة ($currency)",
                    isNumber = true,
                    modifier = Modifier.weight(0.9f)
                )
                GlassTextField(
                    value = discrepancyReason,
                    onValueChange = { discrepancyReason = it },
                    label = "سبب الرفض أو العجز (إن وجد)",
                    placeholder = "مطابق / تلف عبوة / عجز وزن...",
                    modifier = Modifier.weight(1.4f)
                )
            }

            val recVal = receivedQty.toDoubleOrNull() ?: 0.0
            val accVal = acceptedQty.toDoubleOrNull() ?: 0.0
            val rejVal = rejectedQty.toDoubleOrNull() ?: 0.0
            val uCost = unitCost.toDoubleOrNull() ?: 0.0

            GlassButton(
                text = "اعتماد سند الاستلام GRN وترحيل للمواد الخام",
                icon = Icons.Default.VerifiedUser,
                gradient = GlassAccentGreenGradient,
                onClick = {
                    val matId = selectedItem?.id ?: 1L
                    val matName = selectedItem?.name ?: fallbackMaterialName
                    val matUnit = selectedItem?.unit ?: "كجم"
                    viewModel.receivePurchaseToRawWarehouse(
                        materialId = matId,
                        materialName = matName,
                        unit = matUnit,
                        supplierName = supplierName.ifBlank { "مورد خامات معتمد" },
                        batchNumber = batchNumber,
                        expiryDate = expiryDate,
                        orderedQty = orderedQty.toDoubleOrNull() ?: recVal,
                        receivedQty = recVal,
                        acceptedQty = accVal,
                        rejectedQty = rejVal,
                        unitCost = uCost,
                        currency = currency,
                        discrepancyReason = discrepancyReason,
                        cashboxAmount = if (uCost > 0.0) accVal * uCost else 0.0,
                        notes = note
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("submit_grn_phase3_button")
            )

            if (purchaseReceipts.isNotEmpty()) {
                HorizontalDivider(color = GlassBorder)
                Text(
                    text = "أحدث سندات الاستلام والفحص (${purchaseReceipts.size}):",
                    color = TextDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp
                )
                purchaseReceipts.take(4).forEach { pr ->
                    val statusColor = when (pr.qcStatus) {
                        "APPROVED" -> PmpGreen
                        "PARTIAL" -> PmpGold
                        else -> PmpRed
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF8FAFC))
                            .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                            Text(
                                text = "${pr.grnNo} • ${pr.materialName}",
                                color = TextDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "مقبول: ${pr.acceptedQty} ${pr.unit} | حجر مرفوض: ${pr.rejectedQty} • Batch: ${pr.batchNumber}",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        GlassBadge(text = pr.qcStatus, color = statusColor)
                    }
                }
            }
        }
    }
}

/**
 * 2. Production Order BOM + Raw Material Issue + QC Gate -> FINISHED_GOODS Panel (FactoryScreen)
 */
@Composable
fun ProductionOrderQcPhase3Panel(
    viewModel: PmpViewModel,
    formulations: List<FormulationEntity>,
    factoryItems: List<FactoryItemEntity>,
    machines: List<FactoryMachineEntity>
) {
    val productionOrders by viewModel.productionOrdersDb.collectAsState()
    val qcInspections by viewModel.qcInspections.collectAsState()
    val rawMaterialsList = remember(factoryItems) {
        factoryItems.filter {
            it.category.contains("خام", ignoreCase = true) ||
                it.category.contains("raw", ignoreCase = true) ||
                it.category.contains("تعبئة", ignoreCase = true)
        }.ifEmpty { factoryItems }
    }

    var selectedFormulaIndex by remember { mutableIntStateOf(0) }
    var productName by remember { mutableStateOf("بوهية أبيض 900 جم") }
    var paintColor by remember { mutableStateOf("أبيض") }
    var selectedWeightGrams by remember { mutableIntStateOf(900) }
    var targetCartonsText by remember { mutableStateOf("10") }
    var selectedMachine by remember { mutableStateOf(machines.firstOrNull()?.name ?: "خلاط رئيسي 1") }
    var engineerName by remember { mutableStateOf("مهندس الإنتاج") }

    // Issue raw materials modal
    var orderToIssue by remember { mutableStateOf<ProductionOrderEntity?>(null) }
    var issueMatIndex by remember { mutableIntStateOf(0) }
    var issueQtyText by remember { mutableStateOf("25") }
    var returnQtyText by remember { mutableStateOf("0") }
    var wasteQtyText by remember { mutableStateOf("0") }

    // QC inspection modal
    var orderToInspect by remember { mutableStateOf<ProductionOrderEntity?>(null) }
    var qcDensityText by remember { mutableStateOf("1.25") }
    var qcViscosityText by remember { mutableStateOf("110 KU") }
    var qcGoodCansText by remember { mutableStateOf("") }
    var qcRejectedCansText by remember { mutableStateOf("0") }
    var qcReworkCansText by remember { mutableStateOf("0") }
    var qcColorMatch by remember { mutableStateOf(true) }
    var qcForceReject by remember { mutableStateOf(false) }
    var qcNotes by remember { mutableStateOf("") }

    val selectedFormula = formulations.getOrNull(selectedFormulaIndex)
    val cansPerCarton = when (selectedWeightGrams) {
        250 -> 48
        4000 -> 4
        else -> 12
    }
    val cartonsNum = targetCartonsText.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val expectedCans = cartonsNum * cansPerCarton
    val expectedKg = (expectedCans * selectedWeightGrams) / 1000.0

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = Color.White,
        borderColor = Color(0xFF1E40AF).copy(alpha = 0.4f),
        cornerRadius = 20.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.PrecisionManufacturing, contentDescription = null, tint = Color(0xFF1E40AF))
                    Text(
                        text = "دورة أوامر الإنتاج المعتمدة وبوابة الجودة QC",
                        color = PmpBrandNavy,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.5.sp
                    )
                }
                GlassBadge(text = "${productionOrders.size} أمر", color = Color(0xFF1E40AF))
            }

            if (formulations.isNotEmpty()) {
                GlassDropdown(
                    label = "التركيبة الكيميائية BOM",
                    options = formulations.map { "${it.formulaNo} | ${it.productName}" },
                    selectedOption = selectedFormula?.let { "${it.formulaNo} | ${it.productName}" } ?: "",
                    onOptionSelected = { opt ->
                        val idx = formulations.indexOfFirst { "${it.formulaNo} | ${it.productName}" == opt }
                        if (idx >= 0) {
                            selectedFormulaIndex = idx
                            productName = formulations[idx].productName
                            paintColor = formulations[idx].baseKind.ifBlank { "أبيض" }
                        }
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(250 to "250 جم (48/كرتونة)", 900 to "900 جم (12/كرتونة)", 4000 to "4000 جم (4/كرتونة)").forEach { (w, lbl) ->
                    FilterChip(
                        selected = selectedWeightGrams == w,
                        onClick = { selectedWeightGrams = w },
                        label = { Text(lbl, fontSize = 11.5.sp, softWrap = false) }
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassTextField(
                    value = productName,
                    onValueChange = { productName = it },
                    label = "اسم المنتج *",
                    modifier = Modifier.weight(1.4f)
                )
                GlassTextField(
                    value = targetCartonsText,
                    onValueChange = { targetCartonsText = it },
                    label = "الكراتين *",
                    isNumber = true,
                    modifier = Modifier.weight(0.8f)
                )
            }

            Text(
                text = "المعادلة التلقائية: $cartonsNum كرتونة × $cansPerCarton = $expectedCans علبة • الوزن الصافي: ${String.format(Locale.US, "%.2f", expectedKg)} كجم",
                color = Color(0xFF1E40AF),
                fontWeight = FontWeight.Bold,
                fontSize = 11.5.sp
            )

            GlassButton(
                text = "إنشاء أمر إنتاج BOM جديد",
                icon = Icons.Default.AddTask,
                gradient = GlassAccentBlueGradient,
                onClick = {
                    viewModel.createBomProductionOrder(
                        formula = selectedFormula,
                        productName = productName,
                        paintColor = paintColor,
                        packageWeightGrams = selectedWeightGrams,
                        targetCartons = cartonsNum,
                        machineName = selectedMachine,
                        productionEngineer = engineerName
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_bom_production_order_button")
            )

            if (productionOrders.isNotEmpty()) {
                HorizontalDivider(color = GlassBorder)
                Text("أوامر الإنتاج الموثقة (${productionOrders.size}):", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                productionOrders.take(6).forEach { ord ->
                    val statusColor = when (ord.status) {
                        "POSTED_TO_FG" -> PmpGreen
                        "QC_REJECTED" -> PmpRed
                        "MATERIALS_ISSUED" -> PmpGold
                        else -> PmpCyan
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF8FAFC))
                            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${ord.orderNo} • ${ord.productName}", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            GlassBadge(text = ord.status, color = statusColor)
                        }
                        Text(
                            text = "المطلوب: ${ord.targetCartons} كرتونة (${ord.targetCans} علبة × ${ord.packageWeightGrams} جم) | المعتمد تام: ${ord.producedFullCartons} كرتونة (+${ord.producedLooseCans} علبة)",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                        if (ord.status != "POSTED_TO_FG") {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        orderToIssue = ord
                                        issueQtyText = String.format(Locale.US, "%.2f", ord.targetWeightKg.coerceAtLeast(1.0))
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                                    border = BorderStroke(1.dp, PmpGold)
                                ) {
                                    Text("صرف خامات", color = PmpBrandNavy, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = {
                                        orderToInspect = ord
                                        qcGoodCansText = ord.targetCans.toString()
                                        qcRejectedCansText = "0"
                                        qcReworkCansText = "0"
                                        qcForceReject = false
                                        qcColorMatch = true
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PmpGreen)
                                ) {
                                    Text("فحص الجودة QC", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            if (qcInspections.isNotEmpty()) {
                HorizontalDivider(color = GlassBorder)
                Text("أحدث نتائج بوابة الجودة QC (${qcInspections.size}):", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                qcInspections.take(3).forEach { qc ->
                    Text(
                        text = "${qc.qcNo} (${qc.sourceDocNo}): ${qc.status} • مقبول ${qc.acceptedQty.toInt()} | مرفوض/حجر ${qc.rejectedQty.toInt()} • ${if (qc.postedToStock) "تم الترحيل للإنتاج التام" else "محجور - ممنوع البيع"}",
                        color = if (qc.postedToStock) PmpGreen else PmpRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    // Issue Materials Dialog
    orderToIssue?.let { ord ->
        val selectedMat = rawMaterialsList.getOrNull(issueMatIndex) ?: rawMaterialsList.firstOrNull()
        AlertDialog(
            onDismissRequest = { orderToIssue = null },
            title = { Text("صرف مواد خام لأمر ${ord.orderNo}", color = PmpBrandNavy, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rawMaterialsList.isNotEmpty()) {
                        GlassDropdown(
                            label = "المادة الخام من مخزن الخامات *",
                            options = rawMaterialsList.map { it.name },
                            selectedOption = selectedMat?.name ?: "",
                            onOptionSelected = { name ->
                                val idx = rawMaterialsList.indexOfFirst { it.name == name }
                                if (idx >= 0) issueMatIndex = idx
                            }
                        )
                    }
                    GlassTextField(value = issueQtyText, onValueChange = { issueQtyText = it }, label = "الكمية المصروفة *", isNumber = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassTextField(value = returnQtyText, onValueChange = { returnQtyText = it }, label = "مرتجع فائض", isNumber = true, modifier = Modifier.weight(1f))
                        GlassTextField(value = wasteQtyText, onValueChange = { wasteQtyText = it }, label = "الفاقد", isNumber = true, modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    text = "تأكيد الصرف",
                    onClick = {
                        viewModel.issueMaterialsForProductionOrder(
                            order = ord,
                            materialId = selectedMat?.id ?: 1L,
                            materialName = selectedMat?.name ?: "مادة خام أساسية",
                            issueQty = issueQtyText.toDoubleOrNull() ?: 1.0,
                            returnedQty = returnQtyText.toDoubleOrNull() ?: 0.0,
                            wastedQty = wasteQtyText.toDoubleOrNull() ?: 0.0,
                            unit = selectedMat?.unit ?: "كجم",
                            batchNumber = ord.batchNumber
                        )
                        orderToIssue = null
                    }
                )
            },
            dismissButton = { GlassButton(text = "إلغاء", onClick = { orderToIssue = null }) },
            containerColor = PmpWhiteSurface
        )
    }

    // QC Inspection Dialog
    orderToInspect?.let { ord ->
        AlertDialog(
            onDismissRequest = { orderToInspect = null },
            title = { Text("فحص الجودة QC لأمر ${ord.orderNo}", color = PmpBrandNavy, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassTextField(value = qcGoodCansText, onValueChange = { qcGoodCansText = it }, label = "علب مطابقة", isNumber = true, modifier = Modifier.weight(1f))
                        GlassTextField(value = qcRejectedCansText, onValueChange = { qcRejectedCansText = it }, label = "مرفوضة", isNumber = true, modifier = Modifier.weight(1f))
                        GlassTextField(value = qcReworkCansText, onValueChange = { qcReworkCansText = it }, label = "إعادة تشغيل", isNumber = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassTextField(value = qcDensityText, onValueChange = { qcDensityText = it }, label = "الكثافة المقاسة", isNumber = true, modifier = Modifier.weight(1f))
                        GlassTextField(value = qcViscosityText, onValueChange = { qcViscosityText = it }, label = "اللزوجة", modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = qcColorMatch, onCheckedChange = { qcColorMatch = it })
                        Text("مطابقة اللون والدرجة المعملية", color = TextDark, fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = qcForceReject, onCheckedChange = { qcForceReject = it })
                        Text("رفض وحجر التشغيلة بالكامل (منع الترحيل)", color = PmpRed, fontSize = 12.sp)
                    }
                    GlassTextField(value = qcNotes, onValueChange = { qcNotes = it }, label = "ملاحظات فحص الجودة")
                }
            },
            confirmButton = {
                GlassButton(
                    text = "اعتماد نتيجة الفحص",
                    onClick = {
                        viewModel.submitQcInspectionForProductionOrder(
                            order = ord,
                            goodCans = qcGoodCansText.toIntOrNull() ?: 0,
                            rejectedCans = qcRejectedCansText.toIntOrNull() ?: 0,
                            reworkCans = qcReworkCansText.toIntOrNull() ?: 0,
                            measuredDensity = qcDensityText.toDoubleOrNull() ?: 1.25,
                            measuredViscosity = qcViscosityText,
                            colorMatchPassed = qcColorMatch,
                            forceReject = qcForceReject,
                            notes = qcNotes
                        )
                        orderToInspect = null
                    }
                )
            },
            dismissButton = { GlassButton(text = "إلغاء", onClick = { orderToInspect = null }) },
            containerColor = PmpWhiteSurface
        )
    }
}

/**
 * 3. Three-Warehouse Stock Balances, Inter-Warehouse Transfers & Append-Only Reversal Panel (InventoryScreen)
 */
@Composable
fun ThreeWarehouseTransferPanel(
    viewModel: PmpViewModel,
    warehouses: List<WarehouseEntity>,
    products: List<ProductEntity>
) {
    val erpBalances by viewModel.erpStockBalances.collectAsState()
    val erpTransfers by viewModel.erpTransfers.collectAsState()
    val erpMovements by viewModel.erpMovements.collectAsState()
    val format = remember { NumberFormat.getNumberInstance(Locale.US) }

    var classFilter by remember { mutableStateOf("ALL") } // ALL, RAW_MATERIALS, FINISHED_GOODS, SALES
    var showTransferForm by remember { mutableStateOf(false) }

    val fgWarehouses = remember(warehouses) { warehouses.filter { it.resolveWarehouseClass() == WarehouseClass.FINISHED_GOODS }.ifEmpty { warehouses } }
    val salesWarehouses = remember(warehouses) { warehouses.filter { it.resolveWarehouseClass() == WarehouseClass.SALES }.ifEmpty { warehouses } }

    var srcWhName by remember(fgWarehouses) { mutableStateOf(fgWarehouses.firstOrNull()?.name ?: "") }
    var dstWhName by remember(salesWarehouses) { mutableStateOf(salesWarehouses.firstOrNull()?.name ?: "") }
    var trfProdName by remember(products) { mutableStateOf(products.firstOrNull()?.name ?: "") }
    var trfQtyText by remember { mutableStateOf("10") }
    var trfNotes by remember { mutableStateOf("") }

    var movementToReverse by remember { mutableStateOf<InventoryMovementEntity?>(null) }
    var reverseReason by remember { mutableStateOf("") }

    val filteredBalances = remember(erpBalances, classFilter) {
        if (classFilter == "ALL") erpBalances else erpBalances.filter { it.warehouseClass == classFilter }
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = Color.White,
        borderColor = PmpBrandNavy.copy(alpha = 0.25f),
        cornerRadius = 18.dp
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "نظام المخازن الثلاثة والتحويلات المعتمدة",
                    color = PmpBrandNavy,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                )
                TextButton(onClick = { showTransferForm = !showTransferForm }) {
                    Text(if (showTransferForm) "إخفاء التحويل" else "+ تحويل مخزني", color = PmpCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "ALL" to "الكل (${erpBalances.size})",
                    "RAW_MATERIALS" to "المواد الخام",
                    "FINISHED_GOODS" to "الإنتاج التام",
                    "SALES" to "المبيعات"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = classFilter == key,
                        onClick = { classFilter = key },
                        label = { Text(label, fontSize = 11.sp, softWrap = false) }
                    )
                }
            }

            if (showTransferForm) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF8FAFC))
                        .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassDropdown(label = "من مخزن (المصدر) *", options = warehouses.map { it.name }, selectedOption = srcWhName, onOptionSelected = { srcWhName = it })
                    GlassDropdown(label = "إلى مخزن (الوجهة) *", options = warehouses.map { it.name }, selectedOption = dstWhName, onOptionSelected = { dstWhName = it })
                    if (products.isNotEmpty()) {
                        GlassDropdown(label = "الصنف *", options = products.map { it.name }, selectedOption = trfProdName, onOptionSelected = { trfProdName = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassTextField(value = trfQtyText, onValueChange = { trfQtyText = it }, label = "الكمية", isNumber = true, modifier = Modifier.weight(0.8f))
                        GlassTextField(value = trfNotes, onValueChange = { trfNotes = it }, label = "ملاحظات", modifier = Modifier.weight(1.2f))
                    }
                    GlassButton(
                        text = "إنشاء مستند تحويل رسمي",
                        onClick = {
                            val src = warehouses.find { it.name == srcWhName }
                            val dst = warehouses.find { it.name == dstWhName }
                            val pr = products.find { it.name == trfProdName } ?: products.firstOrNull()
                            if (src != null && dst != null && pr != null) {
                                viewModel.createWarehouseTransfer(
                                    sourceWarehouseId = src.id,
                                    destinationWarehouseId = dst.id,
                                    productId = pr.id,
                                    productName = pr.name,
                                    qty = trfQtyText.toDoubleOrNull() ?: 1.0,
                                    notes = trfNotes
                                )
                                showTransferForm = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (filteredBalances.isNotEmpty()) {
                filteredBalances.take(6).forEach { bal ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF8FAFC))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${bal.itemName} (${bal.warehouseClass})", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                text = "فعلي: ${format.format(bal.onHandQty)} | محجوز: ${format.format(bal.reservedQty)} | طريق: ${format.format(bal.inTransitQty)} | حجر: ${format.format(bal.quarantineQty)}",
                                color = TextMuted,
                                fontSize = 10.5.sp
                            )
                        }
                        GlassBadge(text = "متاح ${format.format(bal.availableQty)}", color = if (bal.availableQty > 0) PmpGreen else PmpRed)
                    }
                }
            }

            if (erpTransfers.isNotEmpty()) {
                HorizontalDivider(color = GlassBorder)
                Text("أذون التحويل بين المخازن (${erpTransfers.size}):", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                erpTransfers.take(4).forEach { trf ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF8FAFC))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${trf.transferNo}: ${trf.sourceWarehouseName} -> ${trf.destinationWarehouseName}", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                            GlassBadge(text = trf.status, color = PmpCyan)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (trf.status == "PENDING_APPROVAL" || trf.status == "DRAFT") {
                                OutlinedButton(onClick = { viewModel.approveWarehouseTransfer(trf.id) }, modifier = Modifier.heightIn(min = 38.dp)) {
                                    Text("اعتماد", fontSize = 11.sp)
                                }
                            }
                            if (trf.status == "APPROVED") {
                                OutlinedButton(onClick = { viewModel.dispatchWarehouseTransfer(trf.id) }, modifier = Modifier.heightIn(min = 38.dp)) {
                                    Text("شحن", fontSize = 11.sp)
                                }
                            }
                            if (trf.status == "IN_TRANSIT") {
                                Button(onClick = { viewModel.receiveWarehouseTransfer(trf.id) }, modifier = Modifier.heightIn(min = 38.dp)) {
                                    Text("استلام نهائي", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (erpMovements.isNotEmpty()) {
                HorizontalDivider(color = GlassBorder)
                Text("أحدث قيود دفتر المخزن غير القابلة للحذف (${erpMovements.size}):", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                erpMovements.take(4).forEach { mv ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${mv.documentNo} • ${mv.movementType} • ${mv.itemName}", color = TextDark, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("الكمية: ${mv.signedQuantity} ${mv.unit} • الحالة: ${mv.status}", color = TextMuted, fontSize = 10.sp)
                        }
                        if (mv.status == "POSTED") {
                            TextButton(onClick = {
                                movementToReverse = mv
                                reverseReason = ""
                            }) {
                                Text("قيد عكسي", color = PmpRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    movementToReverse?.let { mv ->
        AlertDialog(
            onDismissRequest = { movementToReverse = null },
            title = { Text("إنشاء قيد عكسي للحركة ${mv.documentNo}", color = PmpRed, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("لا يتم حذف القيود المعتمدة. سيتم قيد حركة عكسية وإبقاء السجل للتدقيق.", color = TextMuted, fontSize = 12.sp)
                    GlassTextField(value = reverseReason, onValueChange = { reverseReason = it }, label = "سبب القيد العكسي *")
                }
            },
            confirmButton = {
                GlassButton(
                    text = "تنفيذ القيد العكسي",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        viewModel.reverseInventoryMovement(mv.id, reverseReason)
                        movementToReverse = null
                    }
                )
            },
            dismissButton = { GlassButton(text = "إلغاء", onClick = { movementToReverse = null }) },
            containerColor = PmpWhiteSurface
        )
    }
}
