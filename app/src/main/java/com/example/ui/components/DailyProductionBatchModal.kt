package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.FactoryMachineEntity
import com.example.data.local.FormulationEntity
import com.example.data.local.FormulationIngredient
import com.example.data.local.ProductionBatchEntity
import com.example.ui.theme.*
import com.example.util.PvcEngine
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyProductionBatchModal(
    availableFormulations: List<FormulationEntity>,
    factoryMachines: List<FactoryMachineEntity>,
    preSelectedFormula: FormulationEntity? = null,
    onDismiss: () -> Unit,
    onSaveBatch: (ProductionBatchEntity) -> Unit
) {
    val df = remember { DecimalFormat("#,##0.00") }
    val todayDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val initialBatchNumber = remember {
        val rand = (100..999).random()
        "PB-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}-$rand"
    }

    var selectedFormula by remember {
        mutableStateOf(preSelectedFormula ?: availableFormulations.firstOrNull())
    }

    var formulaDropdownExpanded by remember { mutableStateOf(false) }
    var machineDropdownExpanded by remember { mutableStateOf(false) }
    var shiftDropdownExpanded by remember { mutableStateOf(false) }
    var qualityStatusDropdownExpanded by remember { mutableStateOf(false) }
    var packageUnitDropdownExpanded by remember { mutableStateOf(false) }

    var batchNumber by remember { mutableStateOf(initialBatchNumber) }
    var productionDate by remember { mutableStateOf(todayDate) }
    var batchWeightInput by remember {
        mutableStateOf(
            if ((selectedFormula?.totalBatchWeight ?: 0.0) > 0.0) {
                selectedFormula?.totalBatchWeight?.toString() ?: "1000.0"
            } else "1000.0"
        )
    }
    var actualWeightInput by remember { mutableStateOf("") }
    var cartonsCountInput by remember { mutableStateOf("100") }
    var packageUnit by remember { mutableStateOf("كرتونة 900 مل") }
    var selectedMachine by remember {
        mutableStateOf(
            factoryMachines.firstOrNull()?.name ?: "خلاط الديزولفر الرئيسي 1500L"
        )
    }
    var qualityStatus by remember { mutableStateOf("معتمد ومطابق") }
    var measuredDensityInput by remember {
        mutableStateOf(
            if ((selectedFormula?.calculatedDensity ?: 0.0) > 0.0) {
                df.format(selectedFormula?.calculatedDensity)
            } else "1.45"
        )
    }
    var measuredViscosityInput by remember { mutableStateOf("95 KU") }
    var supervisorName by remember { mutableStateOf("مهندس الإنتاج والجودة") }
    var shift by remember { mutableStateOf("الوردية الصباحية") }
    var batchNotes by remember { mutableStateOf("") }

    val batchWeight = batchWeightInput.toDoubleOrNull() ?: 0.0
    val formulaBaseWeight = selectedFormula?.totalBatchWeight?.takeIf { it > 0.0 } ?: 100.0
    val scaleMultiplier = if (formulaBaseWeight > 0.0) batchWeight / formulaBaseWeight else 1.0

    // Parse formula ingredients
    val baseIngredients = remember(selectedFormula) {
        PvcEngine.jsonToIngredients(selectedFormula?.ingredientsJson)
    }

    // Scaled ingredients
    val scaledIngredients = remember(baseIngredients, scaleMultiplier) {
        baseIngredients.map { ing ->
            ing.copy(weight = ing.weight * scaleMultiplier)
        }
    }

    val calculatedDensity = measuredDensityInput.toDoubleOrNull()
        ?: selectedFormula?.calculatedDensity?.takeIf { it > 0.0 }
        ?: 1.45
    val estimatedVolumeLiters = if (calculatedDensity > 0.0) batchWeight / calculatedDensity else batchWeight

    val packageUnits = listOf(
        "كرتونة 900 مل",
        "بستلة 250 مل",
        "جردل 18 لتر",
        "برميل 200 لتر",
        "عبوة خاصة 1 لتر"
    )

    val qualityStatuses = listOf(
        "معتمد ومطابق",
        "تحت الفحص المخبري",
        "مرفوض/معالجة"
    )

    val shifts = listOf(
        "الوردية الصباحية",
        "الوردية المسائية",
        "الوردية الليلية"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .testTag("daily_production_batch_modal"),
            shape = RoundedCornerShape(20.dp),
            color = PmpWhiteBg,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Modal Top Bar
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
                                .size(42.dp)
                                .background(Color(0xFF1E40AF), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PrecisionManufacturing,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "تسجيل تشغيلة إنتاج يومية (Firestore Sync)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = TextDark
                            )
                            Text(
                                text = "ربط التشغيلة بالتركيبة الكيميائية وحساب استهلاك الخامات في السحابة",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_batch_modal_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "إغلاق",
                            tint = TextMuted
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = GlassBorder)

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // SECTION 1: Linked Chemical Formulation
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PmpWhiteCard),
                        shape = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Science, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(18.dp))
                                Text(
                                    text = "1. اختيار التركيبة المعملية المعتمدة (Formulation)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = TextDark
                                )
                            }

                            if (availableFormulations.isEmpty()) {
                                Text(
                                    text = "لا توجد تركيبات مسجلة في المختبر الكيميائي حالياً. يرجى إنشاء تركيبة أولاً.",
                                    color = Color(0xFFEF4444),
                                    fontSize = 12.sp
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = { formulaDropdownExpanded = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = selectedFormula?.let { "[${it.formulaNo}] ${it.productName} (${it.paintType})" }
                                                    ?: "اختر التركيبة الكيميائية",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextDark
                                            )
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = formulaDropdownExpanded,
                                        onDismissRequest = { formulaDropdownExpanded = false },
                                        modifier = Modifier.fillMaxWidth(0.85f)
                                    ) {
                                        availableFormulations.forEach { form ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text("[${form.formulaNo}] ${form.productName}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                        Text("النوع: ${form.paintType} | PVC: ${form.calculatedPvc}% | الكثافة: ${form.calculatedDensity} kg/L", fontSize = 10.sp, color = TextMuted)
                                                    }
                                                },
                                                onClick = {
                                                    selectedFormula = form
                                                    if (batchWeightInput.toDoubleOrNull() == 0.0 || batchWeightInput == "1000.0") {
                                                        batchWeightInput = form.totalBatchWeight.toString()
                                                    }
                                                    measuredDensityInput = form.calculatedDensity.toString()
                                                    formulaDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                selectedFormula?.let { form ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(PmpSubtleSurface, RoundedCornerShape(10.dp))
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("تركيز الصبغة بالحجم (PVC)", fontSize = 11.sp, color = TextMuted)
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text("${form.calculatedPvc}%", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PmpBlueLight)
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("الصلابة بالحجم", fontSize = 11.sp, color = TextMuted)
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text("${form.totalSolidsVolume}%", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextDark)
                                            }
                                        }

                                        HorizontalDivider(color = GlassBorder, thickness = 0.8.dp)

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("الكثافة التصميمية", fontSize = 11.sp, color = TextMuted)
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text("${form.calculatedDensity} kg/L", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF059669))
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("تكلفة الكيلو", fontSize = 11.sp, color = TextMuted)
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text("${form.costPerKg} ج.م", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFD97706))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SECTION 2: Production Parameters & Machine
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PmpWhiteCard),
                        shape = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Build, contentDescription = null, tint = Color(0xFF059669), modifier = Modifier.size(18.dp))
                                Text(
                                    text = "2. بيانات التشغيلة وخط الإنتاج",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = TextDark
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = batchNumber,
                                    onValueChange = { batchNumber = it },
                                    label = { Text("رقم التشغيلة (Batch No)", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = productionDate,
                                    onValueChange = { productionDate = it },
                                    label = { Text("تاريخ الإنتاج", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = batchWeightInput,
                                    onValueChange = { batchWeightInput = it },
                                    label = { Text("الوزن المستهدف (كجم)", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = actualWeightInput,
                                    onValueChange = { actualWeightInput = it },
                                    label = { Text("الناتج الفعلي (كجم)", fontSize = 11.sp) },
                                    placeholder = { Text(batchWeightInput, fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = cartonsCountInput,
                                    onValueChange = { cartonsCountInput = it },
                                    label = { Text("الكمية المعبأة (عدد)", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )

                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedButton(
                                        onClick = { packageUnitDropdownExpanded = true },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(packageUnit, fontSize = 11.sp, maxLines = 1)
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = packageUnitDropdownExpanded,
                                        onDismissRequest = { packageUnitDropdownExpanded = false }
                                    ) {
                                        packageUnits.forEach { unit ->
                                            DropdownMenuItem(
                                                text = { Text(unit, fontSize = 12.sp) },
                                                onClick = {
                                                    packageUnit = unit
                                                    packageUnitDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Machine Selection
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { machineDropdownExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("الماكينة / الخلاط: $selectedMachine", fontSize = 12.sp)
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                }
                                DropdownMenu(
                                    expanded = machineDropdownExpanded,
                                    onDismissRequest = { machineDropdownExpanded = false }
                                ) {
                                    val machinesList = if (factoryMachines.isNotEmpty()) {
                                        factoryMachines.map { it.name }
                                    } else {
                                        listOf("خلاط الديزولفر الرئيسي 1500L", "مطحنة خرز دقيقة Dyno-Mill", "خلاط تشطيب وتعبئة 2000L", "خط تعبئة كراتين وبستلات")
                                    }
                                    machinesList.forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text(m, fontSize = 12.sp) },
                                            onClick = {
                                                selectedMachine = m
                                                machineDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // SECTION 3: Dynamic Scaled Raw Materials Consumption Breakdown
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PmpWhiteCard),
                        shape = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                    Icon(Icons.Default.Calculate, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(18.dp))
                                    Text(
                                        text = "3. استهلاك المواد الخام المحسوب للتشغيلة (${scaledIngredients.size} مادة)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = TextDark
                                    )
                                }
                                Text(
                                    text = "مضروب التوسعة: x${df.format(scaleMultiplier)}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PmpBlueLight
                                )
                            }

                            if (scaledIngredients.isEmpty()) {
                                Text(
                                    text = "لم يتم تحديد مواد خام في التركيبة المختارة.",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(PmpContainerBarSurface, RoundedCornerShape(8.dp))
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    scaledIngredients.forEachIndexed { idx, ing ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(PmpWhiteCard, RoundedCornerShape(6.dp))
                                                .border(1.dp, GlassBorder, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .background(PmpInfoSoftSurface, RoundedCornerShape(4.dp)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text("${idx + 1}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PmpBlueLight)
                                                }
                                                Column {
                                                    Text(ing.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                                                    Text(ing.type, fontSize = 10.sp, color = TextMuted)
                                                }
                                            }

                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = "${df.format(ing.weight)} كجم",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextDark
                                                )
                                                if (ing.unitCost > 0) {
                                                    Text(
                                                        text = "${df.format(ing.weight * ing.unitCost)} ج.م",
                                                        fontSize = 10.sp,
                                                        color = Color(0xFF059669)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SECTION 4: Quality Control (QC) & Testing
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PmpWhiteCard),
                        shape = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Verified, contentDescription = null, tint = Color(0xFF7C3AED), modifier = Modifier.size(18.dp))
                                Text(
                                    text = "4. الفحص المخبري واعتماد الجودة (QC)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = TextDark
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = measuredDensityInput,
                                    onValueChange = { measuredDensityInput = it },
                                    label = { Text("الكثافة المقاسة (g/cm³)", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = measuredViscosityInput,
                                    onValueChange = { measuredViscosityInput = it },
                                    label = { Text("اللزوجة المقاسة (KU)", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }

                            // Quality Status Selector
                            Box(modifier = Modifier.fillMaxWidth()) {
                                val statusColor = when (qualityStatus) {
                                    "معتمد ومطابق" -> Color(0xFF059669)
                                    "تحت الفحص المخبري" -> Color(0xFFD97706)
                                    else -> Color(0xFFDC2626)
                                }
                                OutlinedButton(
                                    onClick = { qualityStatusDropdownExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = statusColor)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("حالة الجودة: $qualityStatus", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                }
                                DropdownMenu(
                                    expanded = qualityStatusDropdownExpanded,
                                    onDismissRequest = { qualityStatusDropdownExpanded = false }
                                ) {
                                    qualityStatuses.forEach { st ->
                                        DropdownMenuItem(
                                            text = { Text(st, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                            onClick = {
                                                qualityStatus = st
                                                qualityStatusDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // SECTION 5: Shift, Supervisor & Notes
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PmpWhiteCard),
                        shape = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedButton(
                                        onClick = { shiftDropdownExpanded = true },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(shift, fontSize = 11.sp, maxLines = 1)
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = shiftDropdownExpanded,
                                        onDismissRequest = { shiftDropdownExpanded = false }
                                    ) {
                                        shifts.forEach { sh ->
                                            DropdownMenuItem(
                                                text = { Text(sh, fontSize = 12.sp) },
                                                onClick = {
                                                    shift = sh
                                                    shiftDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = supervisorName,
                                    onValueChange = { supervisorName = it },
                                    label = { Text("المشرف / المهندس", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }

                            OutlinedTextField(
                                value = batchNotes,
                                onValueChange = { batchNotes = it },
                                label = { Text("ملاحظات التشغيل ومواصفات الدفعة", fontSize = 11.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = GlassBorder)

                // Actions Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("إلغاء", fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            val form = selectedFormula
                            val finalActualWeight = actualWeightInput.toDoubleOrNull() ?: batchWeight
                            val machine = factoryMachines.find { it.name == selectedMachine }
                            val newBatch = ProductionBatchEntity(
                                batchNumber = batchNumber.ifBlank { initialBatchNumber },
                                productionDate = productionDate.ifBlank { todayDate },
                                formulaId = form?.id,
                                formulaNo = form?.formulaNo ?: "MANUAL-FORMULA",
                                productName = form?.productName ?: "تشغيلة دهان مصنع PMP",
                                paintType = form?.paintType ?: "مطفي معماري",
                                baseKind = form?.baseKind ?: "أبيض",
                                targetPvc = form?.calculatedPvc ?: 0.0,
                                targetWeightKg = batchWeight,
                                actualWeightKg = finalActualWeight,
                                targetVolumeLiters = estimatedVolumeLiters,
                                cartonsCount = cartonsCountInput.toIntOrNull() ?: 0,
                                packageUnit = packageUnit,
                                machineId = machine?.id,
                                machineName = selectedMachine,
                                qualityStatus = qualityStatus,
                                measuredDensity = measuredDensityInput.toDoubleOrNull() ?: calculatedDensity,
                                measuredViscosity = measuredViscosityInput,
                                scaledIngredientsJson = PvcEngine.ingredientsToJson(scaledIngredients),
                                supervisorOrEngineer = supervisorName,
                                shift = shift,
                                notes = batchNotes,
                                syncStatus = "SYNCED"
                            )
                            onSaveBatch(newBatch)
                        },
                        modifier = Modifier
                            .weight(2f)
                            .height(48.dp)
                            .testTag("save_production_batch_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "تسجيل وحفظ التشغيلة في السحابة (Firestore)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
