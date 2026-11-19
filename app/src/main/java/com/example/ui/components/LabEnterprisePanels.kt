package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.*
import com.example.ui.screens.PubChemTab
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import com.example.util.LabExcelAnalyzer
import com.example.util.PvcEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatLabDate(millis: Long): String {
    if (millis <= 0L) return "—"
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
    }.getOrDefault("—")
}

@Composable
fun FormulaStatusChip(approvalStatus: String, approvedForProduction: Boolean, versionNumber: Int) {
    val norm = approvalStatus.trim().uppercase(Locale.US)
    val (label, bg, fg) = when {
        norm == "APPROVED" || (approvedForProduction && norm == "DRAFT") ->
            Triple("معتمدة v$versionNumber", Color(0xFFDCFCE7), Color(0xFF166534))
        norm == "NEEDS_REVIEW" ->
            Triple("تحتاج مراجعة v$versionNumber", Color(0xFFFEF3C7), Color(0xFF92400E))
        norm == "REJECTED" ->
            Triple("مرفوضة v$versionNumber", Color(0xFFFEE2E2), Color(0xFF991B1B))
        norm == "ARCHIVED" ->
            Triple("مؤرشفة v$versionNumber", Color(0xFFE2E8F0), Color(0xFF475569))
        else ->
            Triple("مسودة v$versionNumber", PmpInfoSoftSurface, PmpBlueLight)
    }
    Surface(shape = RoundedCornerShape(8.dp), color = bg) {
        Text(
            text = label,
            color = fg,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1
        )
    }
}

@Composable
fun FormulaVersionHistoryDialog(
    formulation: FormulationEntity,
    versions: List<FormulationVersionEntity>,
    canViewSecrets: Boolean,
    onDismiss: () -> Unit
) {
    val filtered = remember(versions, formulation.id) {
        versions.filter { it.formulaId == formulation.id }.sortedByDescending { it.versionNumber }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PmpWhiteSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.History, contentDescription = null, tint = PmpBlueLight)
                Text("سجل الإصدارات: ${formulation.formulaNo}", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
            }
        },
        text = {
            if (filtered.isEmpty()) {
                Text("لا توجد إصدارات سابقة مسجلة لهذه التركيبة بعد.", color = TextMuted, fontSize = 12.sp)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered) { ver ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = PmpSubtleSurface),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, GlassBorder)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("الإصدار v${ver.versionNumber}", fontWeight = FontWeight.Bold, color = TextDark, fontSize = 13.sp)
                                    FormulaStatusChip(ver.status, ver.status == "APPROVED", ver.versionNumber)
                                }
                                Text("المُعدّل: ${ver.createdBy.ifBlank { "—" }} • ${formatLabDate(ver.createdAt)}", color = TextMuted, fontSize = 10.5.sp)
                                Text("السبب: ${ver.changeReason.ifBlank { "—" }}", color = TextDark, fontSize = 11.sp)
                                if (canViewSecrets && ver.diffSummaryJson.isNotBlank() && ver.diffSummaryJson != "{}") {
                                    Text("ملخص التغيير: ${ver.diffSummaryJson}", color = PmpBlueLight, fontSize = 11.sp)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("سعر الكيلو السابق: ${String.format(Locale.US, "%.2f", ver.previousCostPerKg)}", color = TextMuted, fontSize = 10.5.sp)
                                    Text("الجديد: ${String.format(Locale.US, "%.2f", ver.newCostPerKg)}", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                                    Text("التاريخي: ${String.format(Locale.US, "%.2f", ver.historicalTotalCost)}", color = Color(0xFF059669), fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                                }
                                if (ver.approvedBy.isNotBlank()) {
                                    Text("اعتماد: ${ver.approvedBy} (${formatLabDate(ver.approvedAt)})", color = Color(0xFF166534), fontSize = 10.5.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}

@Composable
fun ThreeCostComparisonDialog(
    report: PvcEngine.ThreeCostComparisonReport,
    currency: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PmpWhiteSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CompareArrows, contentDescription = null, tint = PmpBlueLight)
                Text("مقارنة التكاليف الثلاثية (${report.formulaNo} v${report.versionNumber})", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    CostCompareRowCard(
                        title = "1. التكلفة التاريخية وقت الاعتماد",
                        total = report.historicalApprovedTotalCost,
                        perKg = report.historicalApprovedCostPerKg,
                        currency = currency,
                        accent = Color(0xFF059669)
                    )
                }
                item {
                    val sign = if (report.currentVsHistoricalDeltaTotal >= 0) "+" else ""
                    CostCompareRowCard(
                        title = "2. التكلفة الحالية بأسعار اليوم (${sign}${report.currentVsHistoricalDeltaPct}%)",
                        total = report.currentEstimatedTotalCost,
                        perKg = report.currentEstimatedCostPerKg,
                        currency = currency,
                        accent = Color(0xFF1E40AF)
                    )
                }
                item {
                    CostCompareRowCard(
                        title = "3. تكلفة التشغيل الفعلي بالمصنع",
                        total = report.actualProductionTotalCost ?: 0.0,
                        perKg = report.actualProductionCostPerKg ?: 0.0,
                        currency = currency,
                        accent = Color(0xFFB45309),
                        emptyText = if (report.actualProductionTotalCost == null) "لا توجد دفعة إنتاج مكتملة لهذه التركيبة بعد" else null
                    )
                }
                if (report.priceImpacts.isNotEmpty()) {
                    item {
                        Text("أثر تغير أسعار المواد الخام على هذه التركيبة:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextDark)
                    }
                    items(report.priceImpacts) { imp ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = PmpSubtleSurface,
                            border = BorderStroke(1.dp, GlassBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(imp.materialName, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, color = TextDark)
                                val deltaColor = if (imp.deltaTotalBatchCost > 0) Color(0xFFDC2626) else Color(0xFF059669)
                                Text(
                                    "سعر الصيغة: ${imp.formulaUnitCost} -> السوق: ${imp.currentMarketUnitCost} | الأثر: ${if (imp.deltaTotalBatchCost > 0) "+" else ""}${imp.deltaTotalBatchCost} $currency (${imp.deltaPercent}%)",
                                    color = deltaColor,
                                    fontSize = 10.5.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("تم") }
        }
    )
}

@Composable
private fun CostCompareRowCard(
    title: String,
    total: Double,
    perKg: Double,
    currency: String,
    accent: Color,
    emptyText: String? = null
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = PmpSubtleSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            if (emptyText != null) {
                Text(emptyText, color = TextMuted, fontSize = 11.sp)
            } else {
                Text(
                    "إجمالي الخلطة: ${String.format(Locale.US, "%.2f", total)} $currency • تكلفة الكيلو: ${String.format(Locale.US, "%.3f", perKg)} $currency/kg",
                    color = TextDark,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.5.sp
                )
            }
        }
    }
}

@Composable
fun FormulaApprovalDialog(
    formulation: FormulationEntity,
    isProcessing: Boolean,
    onDismiss: () -> Unit,
    onDecide: (status: String, note: String) -> Unit
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        containerColor = PmpWhiteSurface,
        title = {
            Text("قرار اعتماد التركيبة (${formulation.formulaNo} v${formulation.versionNumber})", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextDark)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("المنتج: ${formulation.productName}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextDark)
                Text("عند الاعتماد: تُقفل التكلفة التاريخية المرجعية (${formulation.totalCost}) ويُسمح بإصدار أوامر الإنتاج.", fontSize = 11.sp, color = TextMuted)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("ملاحظات الاعتماد أو الرفض") },
                    modifier = Modifier.fillMaxWidth().testTag("lab_approval_note_input")
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { onDecide("REJECTED", note.ifBlank { "مطلوب تعديل ومراجعة معملية" }) },
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("رفض / إعادة", fontSize = 11.sp)
                }
                Button(
                    onClick = { onDecide("APPROVED", note.ifBlank { "اعتماد نهائي للإنتاج" }) },
                    enabled = !isProcessing,
                    modifier = Modifier.testTag("lab_approve_formula_confirm_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text("اعتماد للإنتاج", fontSize = 11.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isProcessing) { Text("إلغاء") }
        }
    )
}

@Composable
fun LabExcelImportPreviewDialog(
    analysis: LabExcelAnalyzer.WorkbookAnalysisResult,
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onConfirmImport: (List<LabExcelAnalyzer.ImportedFormulaDraft>) -> Unit
) {
    val selectedSheets = remember(analysis) {
        mutableStateMapOf<String, Boolean>().apply {
            analysis.candidateFormulas.forEach { put(it.sourceSheetName, true) }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        containerColor = PmpWhiteSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.TableView, contentDescription = null, tint = PmpBlueLight)
                Text("معاينة تحليل ملف Excel قبل الاستيراد", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextDark)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Surface(shape = RoundedCornerShape(10.dp), color = PmpInfoSoftSurface, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("الملف: ${analysis.fileName} (${analysis.worksheetCount} أوراق عمل)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextDark)
                            Text("خلايا المعادلات: ${analysis.totalFormulaCells} • قيم ثابتة: ${analysis.totalFixedCells}", fontSize = 11.sp, color = PmpBlueLight)
                            Text("مواد خام موحدة مكتشفة: ${analysis.deduplicatedMaterials.size} (جديد: ${analysis.deduplicatedMaterials.count { it.isNewMaterial }})", fontSize = 11.sp, color = TextMuted)
                            Text("حالة الاستيراد: جميع التركيبات ستدخل بحالة (تحتاج مراجعة واعتماد) دون المساس بالبيانات السابقة.", fontSize = 10.5.sp, color = Color(0xFF92400E), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (analysis.brokenReferenceIssues.isNotEmpty() || analysis.externalWorkbookLinks.isNotEmpty() || analysis.formulaVerificationMismatches.isNotEmpty()) {
                    item {
                        Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFFEF3C7), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("تنبيهات التحقق والمعادلات والروابط:", fontWeight = FontWeight.Bold, fontSize = 11.5.sp, color = Color(0xFF92400E))
                                Text("• روابط خارجية: ${analysis.externalWorkbookLinks.size}", fontSize = 10.5.sp, color = Color(0xFF78350F))
                                Text("• مراجع معطلة (#REF!): ${analysis.brokenReferenceIssues.size}", fontSize = 10.5.sp, color = Color(0xFF78350F))
                                Text("• فروقات تحقق حسابي: ${analysis.formulaVerificationMismatches.size}", fontSize = 10.5.sp, color = Color(0xFF78350F))
                            }
                        }
                    }
                }
                items(analysis.candidateFormulas) { draft ->
                    val checked = selectedSheets[draft.sourceSheetName] == true
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PmpSubtleSurface,
                        border = BorderStroke(1.dp, GlassBorder),
                        modifier = Modifier.fillMaxWidth().clickable { selectedSheets[draft.sourceSheetName] = !checked }
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = { selectedSheets[draft.sourceSheetName] = it })
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${draft.suggestedFormulaNo} — ${draft.productName}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextDark)
                                Text(
                                    "الورقة: ${draft.sourceSheetName} | المكونات: ${draft.ingredients.size} | PVC: ${draft.calculatedPvcResult.calculatedPvc}% | التكلفة: ${draft.calculatedPvcResult.totalCost}",
                                    fontSize = 10.5.sp,
                                    color = TextMuted
                                )
                                if (draft.matchingExistingFormulaId != null) {
                                    Text("ملاحظة: سيُنشأ إصدار مراجعة جديد فوق التركيبة الحالية v${draft.matchingExistingFormulaVersion ?: 1}", fontSize = 10.sp, color = Color(0xFF92400E))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val chosen = analysis.candidateFormulas.filter { selectedSheets[it.sourceSheetName] == true }
            Button(
                onClick = { onConfirmImport(chosen) },
                enabled = !isImporting && chosen.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
            ) {
                if (isImporting) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("جاري تنفيذ العملية...", fontSize = 11.sp)
                } else {
                    Text("استيراد (${chosen.size}) للمراجعة", fontSize = 11.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isImporting) { Text("إلغاء") }
        }
    )
}

@Composable
fun LabExperimentsAndQcTab(viewModel: PmpViewModel) {
    val formulations by viewModel.formulations.collectAsState()
    val experiments by viewModel.labExperiments.collectAsState()
    val activeGuardKeys by viewModel.activeGuardKeys.collectAsState()
    val canLog = viewModel.canLogLabExperiments()
    val canDecideQc = viewModel.canDecideQcGate()

    var showNewExpDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PmpWhiteCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("التجارب المخبرية وبوابة الجودة (QC Gate)", fontWeight = FontWeight.Bold, color = TextDark, fontSize = 14.sp)
                        Text("كل تجربة مرتبطة برقم التركيبة ورقم الإصدار لضمان التتبع وعدم دخول أي تشغيلة غير مطابقة", color = TextMuted, fontSize = 11.sp)
                    }
                    if (canLog) {
                        Button(
                            onClick = { showNewExpDialog = true },
                            modifier = Modifier.testTag("lab_new_experiment_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تجربة جديدة", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (experiments.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PmpWhiteCard)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Biotech, contentDescription = null, tint = TextSubtle, modifier = Modifier.size(34.dp))
                        Text("لا توجد تجارب مخبرية مسجلة حتى الآن", color = TextMuted, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            items(experiments) { exp ->
                val isProcessingQc = activeGuardKeys.contains("decide_exp_qc_${exp.id}")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = PmpWhiteCard),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${exp.experimentNo} • ${exp.productName}", fontWeight = FontWeight.Bold, color = TextDark, fontSize = 13.5.sp)
                                Text("التركيبة: ${exp.formulaNo} (v${exp.versionNumber}) • الفني: ${exp.technicianName}", color = TextMuted, fontSize = 11.sp)
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when (exp.status) {
                                    "PASSED" -> Color(0xFFDCFCE7)
                                    "REJECTED" -> Color(0xFFFEE2E2)
                                    "RETEST_REQUIRED" -> Color(0xFFFEF3C7)
                                    else -> PmpInfoSoftSurface
                                }
                            ) {
                                Text(
                                    text = when (exp.status) {
                                        "PASSED" -> "ناجحة QC"
                                        "REJECTED" -> "مرفوضة"
                                        "RETEST_REQUIRED" -> "إعادة فحص"
                                        else -> "قيد الفحص"
                                    },
                                    color = when (exp.status) {
                                        "PASSED" -> Color(0xFF166534)
                                        "REJECTED" -> Color(0xFF991B1B)
                                        "RETEST_REQUIRED" -> Color(0xFF92400E)
                                        else -> PmpBlueLight
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.5.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Text(
                            "الكثافة: ${exp.measuredDensity} g/cm³ • اللزوجة: ${exp.measuredViscosity} • pH: ${exp.measuredPh} • النعومة: ${exp.measuredGrindMicrons} µm • التغطية: ${exp.hidingPowerPercent}%",
                            color = TextDark,
                            fontSize = 11.sp
                        )
                        if (exp.notes.isNotBlank()) {
                            Text("الملاحظات: ${exp.notes}", color = TextMuted, fontSize = 11.sp)
                        }
                        if (exp.decidedBy.isNotBlank()) {
                            Text("قرار الجودة (${exp.decidedBy}): ${exp.decision}${if (exp.linkedQcNo.isNotBlank()) " • ${exp.linkedQcNo}" else ""}", color = PmpBlueLight, fontSize = 10.5.sp)
                        }
                        if (canDecideQc && exp.status != "PASSED") {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { viewModel.decideLabExperimentQc(exp.id, "RETEST_REQUIRED", "طلب إعادة فحص ومطابقة") },
                                    enabled = !isProcessingQc,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("إعادة فحص", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { viewModel.decideLabExperimentQc(exp.id, "REJECTED", "عدم مطابقة المواصفة") },
                                    enabled = !isProcessingQc,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("رفض", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { viewModel.decideLabExperimentQc(exp.id, "PASSED", "مطابق للمواصفات المخبرية") },
                                    enabled = !isProcessingQc,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("اعتماد QC", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewExpDialog && formulations.isNotEmpty()) {
        var chosenFormula by remember { mutableStateOf(formulations.first()) }
        var density by remember { mutableStateOf(chosenFormula.calculatedDensity.toString()) }
        var viscosity by remember { mutableStateOf("105 KU") }
        var ph by remember { mutableStateOf("8.5") }
        var fineness by remember { mutableStateOf("25") }
        var hiding by remember { mutableStateOf("96") }
        var notes by remember { mutableStateOf("") }
        val isSaving = activeGuardKeys.contains("record_lab_experiment")

        AlertDialog(
            onDismissRequest = { if (!isSaving) showNewExpDialog = false },
            containerColor = PmpWhiteSurface,
            title = { Text("تسجيل تجربة مخبرية جديدة", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextDark) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("${chosenFormula.formulaNo} (v${chosenFormula.versionNumber}) - ${chosenFormula.productName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            formulations.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text("${f.formulaNo} v${f.versionNumber} - ${f.productName}") },
                                    onClick = {
                                        chosenFormula = f
                                        density = f.calculatedDensity.toString()
                                        menuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = density, onValueChange = { density = it }, label = { Text("الكثافة") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        OutlinedTextField(value = viscosity, onValueChange = { viscosity = it }, label = { Text("اللزوجة") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = ph, onValueChange = { ph = it }, label = { Text("pH") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        OutlinedTextField(value = fineness, onValueChange = { fineness = it }, label = { Text("الطحن (µm)") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        OutlinedTextField(value = hiding, onValueChange = { hiding = it }, label = { Text("التغطية %") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    }
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("نتائج وملاحظات الاختبار") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.recordLabExperiment(
                            experiment = LabExperimentEntity(
                                syncUuid = java.util.UUID.randomUUID().toString(),
                                experimentNo = "",
                                formulaId = chosenFormula.id,
                                formulaNo = chosenFormula.formulaNo,
                                formulaVersionId = chosenFormula.versionNumber.toLong(),
                                versionNumber = chosenFormula.versionNumber,
                                productId = chosenFormula.linkedProductId,
                                productName = chosenFormula.productName,
                                experimentDate = "",
                                technicianName = "",
                                measuredDensity = density.toDoubleOrNull() ?: 1.0,
                                measuredViscosity = viscosity,
                                measuredPh = ph.toDoubleOrNull() ?: 8.5,
                                measuredGrindMicrons = fineness.toDoubleOrNull() ?: 25.0,
                                hidingPowerPercent = hiding.toDoubleOrNull() ?: 95.0,
                                notes = notes
                            ),
                            onComplete = { ok -> if (ok) showNewExpDialog = false }
                        )
                    },
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
                ) {
                    Text(if (isSaving) "جاري الحفظ..." else "حفظ التجربة")
                }
            },
            dismissButton = { TextButton(onClick = { showNewExpDialog = false }, enabled = !isSaving) { Text("إلغاء") } }
        )
    }
}

@Composable
fun UnifiedRawMaterialsRegistryTab(viewModel: PmpViewModel) {
    val factoryItems by viewModel.factoryItems.collectAsState()
    val stockBalances by viewModel.erpStockBalances.collectAsState()
    val priceHistory by viewModel.rawMaterialPriceHistory.collectAsState()
    val canUpdatePrices = viewModel.canEditSecretFormulas() || viewModel.canViewFormulaCosts()

    var showPubChemLookup by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<FactoryItemEntity?>(null) }

    val rawItems = remember(factoryItems, search) {
        factoryItems
            .filter { it.category.equals("raw", ignoreCase = true) || it.category.contains("خام") }
            .filter { search.isBlank() || it.name.contains(search, ignoreCase = true) || it.itemCode.contains(search, ignoreCase = true) }
    }

    if (showPubChemLookup) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(PmpInfoSoftSurface).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("بحث PubChem العالمي لإضافة خامات جديدة", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = PmpBlueLight)
                TextButton(onClick = { showPubChemLookup = false }) { Text("العودة للسجل الموحد") }
            }
            PubChemTab(viewModel = viewModel)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PmpWhiteCard), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("سجل المواد الخام الموحد (المختبر • المخازن • المشتريات)", fontWeight = FontWeight.Bold, color = TextDark, fontSize = 13.5.sp)
                            Text("يربط أسعار آخر شراء وتكلفة الوحدة والرصيد المتاح مع أثرها على التركيبات دون تغيير الأسعار التاريخية المعتمدة", color = TextMuted, fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { showPubChemLookup = true }) {
                            Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("PubChem", fontSize = 11.sp)
                        }
                    }
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("بحث باسم المادة الخام أو الكود...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                }
            }
        }

        items(rawItems) { item ->
            val matchingBalances = stockBalances.filter {
                it.itemCategory == InventoryItemCategory.RAW_MATERIAL.name && it.itemId == item.id
            }
            val availQty = matchingBalances.sumOf { it.availableQty }
            val avgCost = matchingBalances.lastOrNull()?.avgUnitCost?.takeIf { it > 0.0 } ?: item.unitCostEgp
            val lastPurchase = item.lastPurchasePrice.takeIf { it > 0.0 } ?: item.unitCostEgp
            val itemHistory = priceHistory.filter { it.materialId == item.id }.take(2)

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PmpWhiteCard), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${item.itemCode.ifBlank { "RAW-${item.id}" }} • ${item.name}", fontWeight = FontWeight.Bold, color = TextDark, fontSize = 13.sp)
                            Text("المورد: ${item.supplierName.ifBlank { "غير محدد" }} • الكثافة: ${item.density} • الصلابة: ${item.solidsPercent}%", color = TextMuted, fontSize = 10.5.sp)
                        }
                        if (canUpdatePrices) {
                            OutlinedButton(onClick = { editingItem = item }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("تحديث السعر", fontSize = 10.5.sp)
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("المتاح بالمخزن: $availQty ${item.unit}", color = Color(0xFF0D9488), fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                        Text("آخر شراء: $lastPurchase ${item.lastPriceCurrency}", color = Color(0xFF1E40AF), fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                        Text("متوسط التكلفة: ${String.format(Locale.US, "%.2f", avgCost)}", color = TextMuted, fontSize = 11.sp)
                    }
                    if (itemHistory.isNotEmpty()) {
                        itemHistory.forEach { h ->
                            Text(
                                "• ${h.sourceDocNo}: ${h.previousPrice} -> ${h.unitPrice} ${h.currency} (${formatLabDate(h.createdAt)})",
                                color = TextSubtle,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }

    editingItem?.let { item ->
        var priceStr by remember { mutableStateOf((item.lastPurchasePrice.takeIf { it > 0.0 } ?: item.unitCostEgp).toString()) }
        var supplierStr by remember { mutableStateOf(item.supplierName) }
        var reasonStr by remember { mutableStateOf("تحديث سعر توريد") }
        AlertDialog(
            onDismissRequest = { editingItem = null },
            containerColor = PmpWhiteSurface,
            title = { Text("تحديث سعر الخامة: ${item.name}", fontWeight = FontWeight.Bold, fontSize = 14.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = priceStr, onValueChange = { priceStr = it }, label = { Text("السعر الجديد (EGP)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    OutlinedTextField(value = supplierStr, onValueChange = { supplierStr = it }, label = { Text("المورد المعتمد") }, singleLine = true)
                    OutlinedTextField(value = reasonStr, onValueChange = { reasonStr = it }, label = { Text("سبب التحديث") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val np = priceStr.toDoubleOrNull() ?: return@Button
                        viewModel.updateRawMaterialPriceAndImpact(
                            materialId = item.id,
                            newPrice = np,
                            supplierName = supplierStr,
                            reason = reasonStr,
                            onComplete = { ok -> if (ok) editingItem = null }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
                ) { Text("حفظ السعر") }
            },
            dismissButton = { TextButton(onClick = { editingItem = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun FormulaProductionReadinessDialog(
    formulation: FormulationEntity,
    ingredients: List<FormulationIngredient>,
    factoryItems: List<FactoryItemEntity>,
    stockBalances: List<WarehouseStockBalanceEntity>,
    experiments: List<LabExperimentEntity>,
    canViewSecrets: Boolean,
    defaultTargetWeightKg: Double,
    onDismiss: () -> Unit,
    onProceedToProductionOrder: (() -> Unit)? = null
) {
    var weightInput by remember(formulation.id) {
        mutableStateOf(
            (if (defaultTargetWeightKg > 0.0) defaultTargetWeightKg else formulation.totalBatchWeight.takeIf { it > 0.0 } ?: 500.0)
                .toInt()
                .toString()
        )
    }
    val targetWeight = weightInput.toDoubleOrNull() ?: 0.0
    val report = remember(formulation, ingredients, targetWeight, factoryItems, stockBalances, experiments, canViewSecrets) {
        PvcEngine.evaluateProductionReadiness(
            formula = formulation,
            ingredients = ingredients,
            targetWeightKg = targetWeight,
            factoryItems = factoryItems,
            stockBalances = stockBalances,
            experiments = experiments,
            includeSecretBreakdown = canViewSecrets
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PmpWhiteSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.FactCheck, contentDescription = null, tint = PmpBlueLight)
                Text(
                    "جاهزية الإنتاج والمخازن (${report.formulaNo} v${report.versionNumber})",
                    color = TextDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = weightInput,
                        onValueChange = { weightInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("وزن التشغيلة المطلوب فحصه (كجم)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("lab_readiness_weight_input")
                    )
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (report.isApprovedForProduction) Color(0xFFDCFCE7) else Color(0xFFFEE2E2),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = if (report.isApprovedForProduction) "بوابة الاعتماد: معتمدة للإنتاج (APPROVED)" else "بوابة الاعتماد: غير معتمدة (${report.approvalStatus})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (report.isApprovedForProduction) Color(0xFF166534) else Color(0xFF991B1B)
                            )
                            Text(
                                text = "تجارب QC ناجحة: ${report.passedQcCount} • قيد الفحص: ${report.pendingQcCount} • مرفوضة: ${report.failedQcCount}",
                                fontSize = 11.sp,
                                color = TextDark
                            )
                            Text(
                                text = if (report.shortageCount == 0 && report.totalMaterialsChecked > 0)
                                    "مخزون الخامات: متوفر بالكامل (${report.totalMaterialsChecked} مواد)"
                                else
                                    "مخزون الخامات: يوجد عجز في ${report.shortageCount} من أصل ${report.totalMaterialsChecked} مواد",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (report.shortageCount == 0) Color(0xFF166534) else Color(0xFF92400E)
                            )
                        }
                    }
                }
                if (report.blockingReasons.isNotEmpty()) {
                    item {
                        Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFFEE2E2), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                report.blockingReasons.forEach { reason ->
                                    Text("• $reason", color = Color(0xFF991B1B), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                if (report.warnings.isNotEmpty()) {
                    item {
                        Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFFEF3C7), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                report.warnings.forEach { warn ->
                                    Text("• $warn", color = Color(0xFF92400E), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                if (canViewSecrets && report.materialLines.isNotEmpty()) {
                    items(report.materialLines) { line ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = PmpSubtleSurface,
                            border = BorderStroke(1.dp, if (line.isSufficient) Color(0xFF86EFAC) else Color(0xFFFCA5A5)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(line.materialName, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, color = TextDark)
                                    Text("مطلوب: ${line.requiredKg} كجم • متاح: ${line.availableKg} كجم", fontSize = 10.5.sp, color = TextMuted)
                                }
                                Text(
                                    text = if (line.isSufficient) "كافٍ" else "عجز ${line.shortageKg}",
                                    color = if (line.isSufficient) Color(0xFF166534) else Color(0xFF991B1B),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                } else if (!canViewSecrets) {
                    item {
                        Text(
                            "حماية الملكية الفكرية: تفاصيل الأوزان الفردية للخامات محجوبة عن دورك الوظيفي ويظهر الملخص الإجمالي فقط.",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (onProceedToProductionOrder != null) {
                Button(
                    onClick = onProceedToProductionOrder,
                    enabled = report.canReleaseToProduction,
                    modifier = Modifier.testTag("lab_readiness_release_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) {
                    Text("إصدار أمر تشغيل", fontSize = 11.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}

