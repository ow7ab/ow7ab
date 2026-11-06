package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ChemicalSafetyEntity
import com.example.data.local.FormulationEntity
import com.example.data.local.FormulationIngredient
import com.example.data.local.ProductionBatchEntity
import com.example.ui.components.DailyProductionBatchModal
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassCard
import com.example.ui.components.ProvideShimmerEffect
import com.example.ui.components.ShimmerListRowCardSkeleton
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import com.example.util.FormulationPdfExporter
import com.example.util.PubChemService
import com.example.util.PvcEngine
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChemistryLabScreen(
    viewModel: PmpViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        "التركيبات والنسب" to Icons.Default.Calculate,
        "دليل المواد الخام" to Icons.Default.MenuBook,
        "معايير السلامة" to Icons.Default.HealthAndSafety
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
    ) {
        // Lab Header with PMP Royal Blue & Deep Navy Branding
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0F172A), Color(0xFF1E293B))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E40AF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Science,
                            contentDescription = "PMP Science Lab",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "مختبر التركيبات والجودة",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF2563EB).copy(alpha = 0.4f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "قسم الأبحاث والتطوير",
                                color = Color(0xFF93C5FD),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Navigation Tabs (Mobile-First scrollable row with active indicators)
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF0F172A),
            contentColor = Color(0xFF38BDF8)
        ) {
            tabs.forEachIndexed { index, pair ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    modifier = Modifier.testTag("lab_tab_$index"),
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = pair.second,
                                contentDescription = pair.first,
                                tint = if (selectedTab == index) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                                modifier = Modifier.size(17.dp)
                            )
                            Text(
                                text = pair.first,
                                color = if (selectedTab == index) Color.White else Color(0xFF94A3B8),
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp
                            )
                        }
                    }
                )
            }
        }

        // Tab Content
        Crossfade(
            targetState = selectedTab,
            label = "LabTabTransition"
        ) { tabIndex ->
            when (tabIndex) {
                0 -> PvcEngineTab(viewModel = viewModel)
                1 -> PubChemTab(viewModel = viewModel)
                2 -> GhsSafetyTab(viewModel = viewModel)
            }
        }
    }
}

// =========================================================================
// TAB 1: PVC ENGINE & FORMULATION MANAGEMENT
// =========================================================================

@Composable
fun PvcEngineTab(viewModel: PmpViewModel) {
    val context = LocalContext.current
    val formulations by viewModel.formulations.collectAsState()
    val numberFormat = remember { NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = 2 } }

    var currentFormulaId by remember { mutableLongStateOf(0L) }
    var formulaNo by remember { mutableStateOf("PMP-LAB-2026-001") }
    var productName by remember { mutableStateOf("دهان PMP معماري مطفي") }
    var paintType by remember { mutableStateOf("مطفي معماري (Matt)") }
    var baseKind by remember { mutableStateOf("أبيض") }
    var targetPvcInput by remember { mutableStateOf("45.0") }
    var notesInput by remember { mutableStateOf("") }

    var ingredientsList by remember {
        mutableStateOf(
            listOf(
                FormulationIngredient(name = "ثاني أكسيد التيتانيوم (Rutile TiO2)", type = "صبغة / ملون", weight = 15.0, density = 4.2, solidsPercent = 100.0, unitCost = 4.5),
                FormulationIngredient(name = "كربونات كالسيوم ميكرونايزد", type = "مالئ وحبيبات", weight = 30.0, density = 2.7, solidsPercent = 100.0, unitCost = 0.4),
                FormulationIngredient(name = "راتنج أكريليك 50%", type = "راتنج / بوليمر رابط", weight = 25.0, density = 1.05, solidsPercent = 50.0, unitCost = 2.6),
                FormulationIngredient(name = "ماء نقي (Water)", type = "مذيب / ماء", weight = 27.0, density = 1.0, solidsPercent = 0.0, unitCost = 0.05),
                FormulationIngredient(name = "إضافات ومساعدات (Texanol + Defoamer)", type = "إضافات ومساعدات", weight = 3.0, density = 0.98, solidsPercent = 50.0, unitCost = 5.5)
            )
        )
    }

    // Live calculation result
    val calcResult by remember(ingredientsList) {
        derivedStateOf { PvcEngine.calculate(ingredientsList) }
    }

    var showLoadDialog by remember { mutableStateOf(false) }
    var showAddIngredientDialog by remember { mutableStateOf(false) }
    var showProductionOrderDialog by remember { mutableStateOf(false) }
    var showBatchModalFromLab by remember { mutableStateOf(false) }
    val factoryMachines by viewModel.factoryMachines.collectAsState()
    var batchOrderMultiplierInput by remember { mutableStateOf("5.0") } // e.g. 5x = 500 kg batch

    var selectedBatchScaleKg by remember { mutableDoubleStateOf(100.0) }
    var costCurrency by remember { mutableStateOf("EGP") }
    var targetOrderWarehouse by remember { mutableStateOf("مخزن الإنتاج التام داخل المصنع") }

    // Sync when user selects an existing saved formula
    LaunchedEffect(formulations) {
        if (currentFormulaId == 0L && formulations.isNotEmpty()) {
            val first = formulations.first()
            currentFormulaId = first.id
            formulaNo = first.formulaNo
            productName = first.productName
            paintType = first.paintType
            baseKind = first.baseKind
            targetPvcInput = first.targetPvc.toString()
            notesInput = first.notes
            val parsed = PvcEngine.jsonToIngredients(first.ingredientsJson)
            if (parsed.isNotEmpty()) {
                ingredientsList = parsed
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Formulation Selector & Action Header
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Science, contentDescription = null, tint = Color(0xFF1E40AF), modifier = Modifier.size(14.dp))
                                Text(
                                    text = "التركيبة قيد التجهيز:",
                                    color = Color(0xFF64748B),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                text = productName,
                                color = Color(0xFF0F172A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { showLoadDialog = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFEFF6FF),
                                    contentColor = Color(0xFF1D4ED8)
                                ),
                                border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("التركيبات (${formulations.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    currentFormulaId = 0L
                                    formulaNo = "PMP-LAB-${(100..999).random()}"
                                    productName = "تركيبة جديدة"
                                    ingredientsList = emptyList()
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1E40AF),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("جديدة", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // 1-Click Recipe Preset Templates
                    Text(
                        text = "نماذج تركيبات معيارية سريعة:",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = paintType.contains("Gloss"),
                            onClick = {
                                formulaNo = "PMP-GLOSS-01"
                                productName = "دهان سوبر جلوس أبيض لامع"
                                paintType = "دهان مائي لامع (Gloss)"
                                baseKind = "أبيض"
                                targetPvcInput = "18.5"
                                ingredientsList = listOf(
                                    FormulationIngredient(name = "ثاني أكسيد التيتانيوم (Rutile TiO2)", type = "صبغة / ملون", weight = 25.0, density = 4.2, solidsPercent = 100.0, unitCost = 4.5),
                                    FormulationIngredient(name = "راتنج أكريليك نقي 50%", type = "راتنج / بوليمر رابط", weight = 45.0, density = 1.06, solidsPercent = 50.0, unitCost = 2.8),
                                    FormulationIngredient(name = "ماء نقي (Water)", type = "مذيب / ماء", weight = 25.0, density = 1.0, solidsPercent = 0.0, unitCost = 0.05),
                                    FormulationIngredient(name = "تيكسانول (Texanol)", type = "إضافات ومساعدات", weight = 2.0, density = 0.95, solidsPercent = 0.0, unitCost = 5.2),
                                    FormulationIngredient(name = "مغلظ سليلوز ومساعدات", type = "إضافات ومساعدات", weight = 3.0, density = 1.0, solidsPercent = 30.0, unitCost = 6.5)
                                )
                            },
                            label = { Text("سوبر جلوس (18% PVC)", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = paintType.contains("Semi-Gloss"),
                            onClick = {
                                formulaNo = "PMP-SEMI-02"
                                productName = "دهان نصف لامع سيلك فاخر"
                                paintType = "نصف لامع (Semi-Gloss)"
                                baseKind = "أبيض"
                                targetPvcInput = "30.0"
                                ingredientsList = listOf(
                                    FormulationIngredient(name = "ثاني أكسيد التيتانيوم (Rutile TiO2)", type = "صبغة / ملون", weight = 20.0, density = 4.2, solidsPercent = 100.0, unitCost = 4.5),
                                    FormulationIngredient(name = "كربونات كالسيوم 5 ميكرون", type = "مالئ وحبيبات", weight = 15.0, density = 2.7, solidsPercent = 100.0, unitCost = 0.4),
                                    FormulationIngredient(name = "راتنج أكريليك نقي 50%", type = "راتنج / بوليمر رابط", weight = 35.0, density = 1.05, solidsPercent = 50.0, unitCost = 2.6),
                                    FormulationIngredient(name = "ماء نقي (Water)", type = "مذيب / ماء", weight = 26.0, density = 1.0, solidsPercent = 0.0, unitCost = 0.05),
                                    FormulationIngredient(name = "إضافات اندماج ورغوة", type = "إضافات ومساعدات", weight = 4.0, density = 0.98, solidsPercent = 40.0, unitCost = 5.0)
                                )
                            },
                            label = { Text("نصف لامع (30% PVC)", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = paintType.contains("Satin"),
                            onClick = {
                                formulaNo = "PMP-SATIN-03"
                                productName = "دهان ساتان حريري معماري"
                                paintType = "ساتان / حريري (Satin)"
                                baseKind = "أبيض"
                                targetPvcInput = "38.0"
                                ingredientsList = listOf(
                                    FormulationIngredient(name = "ثاني أكسيد التيتانيوم (Rutile TiO2)", type = "صبغة / ملون", weight = 16.0, density = 4.2, solidsPercent = 100.0, unitCost = 4.5),
                                    FormulationIngredient(name = "كربونات كالسيوم ميكرونايزد", type = "مالئ وحبيبات", weight = 24.0, density = 2.7, solidsPercent = 100.0, unitCost = 0.4),
                                    FormulationIngredient(name = "راتنج ستايرين أكريليك 50%", type = "راتنج / بوليمر رابط", weight = 30.0, density = 1.04, solidsPercent = 50.0, unitCost = 2.2),
                                    FormulationIngredient(name = "ماء نقي (Water)", type = "مذيب / ماء", weight = 26.0, density = 1.0, solidsPercent = 0.0, unitCost = 0.05),
                                    FormulationIngredient(name = "إضافات ومثبتات", type = "إضافات ومساعدات", weight = 4.0, density = 0.98, solidsPercent = 40.0, unitCost = 5.0)
                                )
                            },
                            label = { Text("ساتان حريري (38% PVC)", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = paintType.contains("Matt"),
                            onClick = {
                                formulaNo = "PMP-MATT-04"
                                productName = "دهان رويال مطفي معماري فائق التغطية"
                                paintType = "مطفي معماري (Matt)"
                                baseKind = "أبيض"
                                targetPvcInput = "48.0"
                                ingredientsList = listOf(
                                    FormulationIngredient(name = "ثاني أكسيد التيتانيوم (Rutile TiO2)", type = "صبغة / ملون", weight = 12.0, density = 4.2, solidsPercent = 100.0, unitCost = 4.5),
                                    FormulationIngredient(name = "كربونات كالسيوم 5 ميكرون", type = "مالئ وحبيبات", weight = 38.0, density = 2.7, solidsPercent = 100.0, unitCost = 0.4),
                                    FormulationIngredient(name = "راتنج ستايرين أكريليك 50%", type = "راتنج / بوليمر رابط", weight = 20.0, density = 1.04, solidsPercent = 50.0, unitCost = 2.1),
                                    FormulationIngredient(name = "ماء نقي (Water)", type = "مذيب / ماء", weight = 26.0, density = 1.0, solidsPercent = 0.0, unitCost = 0.05),
                                    FormulationIngredient(name = "إضافات ومشتت بودرة", type = "إضافات ومساعدات", weight = 4.0, density = 0.98, solidsPercent = 40.0, unitCost = 4.8)
                                )
                            },
                            label = { Text("مطفي معماري (48% PVC)", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = paintType.contains("Sealer") || paintType.contains("سيلر"),
                            onClick = {
                                formulaNo = "PMP-SEALER-05"
                                productName = "سيلر مائي نفاذ وعازل للأسطح"
                                paintType = "برايمر وسيلر مائي"
                                baseKind = "شفاف"
                                targetPvcInput = "0.0"
                                ingredientsList = listOf(
                                    FormulationIngredient(name = "راتنج أكريليك نقي ميكروني 50%", type = "راتنج / بوليمر رابط", weight = 35.0, density = 1.04, solidsPercent = 50.0, unitCost = 2.8),
                                    FormulationIngredient(name = "ماء نقي (Water)", type = "مذيب / ماء", weight = 61.0, density = 1.0, solidsPercent = 0.0, unitCost = 0.05),
                                    FormulationIngredient(name = "مساعد نفاذية وكواليسنت", type = "إضافات ومساعدات", weight = 4.0, density = 0.98, solidsPercent = 50.0, unitCost = 5.5)
                                )
                            },
                            label = { Text("سيلر مائي (0% PVC)", fontSize = 11.sp) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = formulaNo,
                            onValueChange = { formulaNo = it },
                            label = { Text("كود الصيغة") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                        )
                        OutlinedTextField(
                            value = productName,
                            onValueChange = { productName = it },
                            label = { Text("اسم المنتج والطلاء") },
                            modifier = Modifier.weight(1.8f),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                        )
                    }
                }
            }
        }

        // Interactive PVC Spectrum Gauge & CPVC Critical Concentration Bar
        item {
            PvcSpectrumGauge(
                calculatedPvc = calcResult.calculatedPvc,
                cpvcEstimated = calcResult.cpvcEstimated
            )
        }

        // Live Engineering KPI Cards (Royal Blue Aesthetic & Dual-Currency Costing)
        item {
            val costUnitLabel = if (costCurrency == "EGP") "EGP/كجم" else "SDG/كجم"
            val effectiveCostPerKg = if (costCurrency == "EGP") calcResult.costPerKg else (calcResult.costPerKg * 200.0)
            val effectiveTotalCost = if (costCurrency == "EGP") calcResult.totalCost else (calcResult.totalCost * 200.0)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Header with current active currency pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Analytics, contentDescription = null, tint = Color(0xFF1E40AF), modifier = Modifier.size(18.dp))
                        Text(
                            text = "المؤشرات الهندسية وتكلفة الإنتاج",
                            color = Color(0xFF0F172A),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (costCurrency == "EGP") Color(0xFFEFF6FF) else Color(0xFFFEF2F2),
                        border = BorderStroke(1.dp, if (costCurrency == "EGP") Color(0xFFBFDBFE) else Color(0xFFFECACA))
                    ) {
                        Text(
                            text = if (costCurrency == "EGP") "عملة التكلفة: EGP" else "عملة التكلفة: SDG",
                            color = if (costCurrency == "EGP") Color(0xFF1E40AF) else PmpBrandRed,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                // Balanced Dual Currency Selector Buttons (No vertical squishing, equal 50/50 width)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFE2E8F0))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        onClick = { costCurrency = "EGP" },
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(9.dp),
                        color = if (costCurrency == "EGP") Color(0xFF1E40AF) else Color.Transparent,
                        shadowElevation = if (costCurrency == "EGP") 2.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "🇪🇬 مصنع مصر (EGP)",
                                color = if (costCurrency == "EGP") Color.White else Color(0xFF334155),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Surface(
                        onClick = { costCurrency = "SDG" },
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(9.dp),
                        color = if (costCurrency == "SDG") PmpBrandRed else Color.Transparent,
                        shadowElevation = if (costCurrency == "SDG") 2.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "🇸🇩 السودان وحلفا (SDG)",
                                color = if (costCurrency == "SDG") Color.White else Color(0xFF334155),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KpiLabCard(
                        title = "تركيز الصبغة بالحجم (PVC)",
                        value = "${calcResult.calculatedPvc}%",
                        subtitle = "الهدف: $targetPvcInput% (CPVC: ${calcResult.cpvcEstimated}%)",
                        accentColor = Color(0xFF1E40AF),
                        isHighlight = true,
                        modifier = Modifier.weight(1.2f)
                    )
                    KpiLabCard(
                        title = "نسبة المواد الصلبة (وزناً)",
                        value = "${calcResult.totalSolidsWeight}%",
                        subtitle = "بالحجم: ${calcResult.totalSolidsVolume}%",
                        accentColor = Color(0xFF0D9488),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KpiLabCard(
                        title = "الكثافة النظرية للطلاء",
                        value = "${calcResult.calculatedDensity} kg/L",
                        subtitle = "حجم الخلطة: ${calcResult.totalBatchVolume} لتر",
                        accentColor = Color(0xFF4F46E5),
                        modifier = Modifier.weight(1f)
                    )
                    KpiLabCard(
                        title = "تكلفة الكيلو والخلطة ($costCurrency)",
                        value = String.format(Locale.US, "%.2f %s", effectiveCostPerKg, costUnitLabel),
                        subtitle = "إجمالي الخلطة: " + String.format(Locale.US, "%.1f %s", effectiveTotalCost, costCurrency),
                        accentColor = Color(0xFFB45309),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Technical Analysis & CPVC Proximity Alert
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (calcResult.calculatedPvc > calcResult.cpvcEstimated) Color(0xFFFFFBEB) else Color(0xFFF0FDF4)
                ),
                shape = RoundedCornerShape(14.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.horizontalGradient(
                        listOf(
                            if (calcResult.calculatedPvc > calcResult.cpvcEstimated) Color(0xFFF59E0B) else Color(0xFF10B981),
                            Color.Transparent
                        )
                    )
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (calcResult.calculatedPvc > calcResult.cpvcEstimated) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (calcResult.calculatedPvc > calcResult.cpvcEstimated) Color(0xFFD97706) else Color(0xFF059669),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = calcResult.paintClassification,
                            color = Color(0xFF0F172A),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = calcResult.technicalAnalysisText,
                        color = Color(0xFF334155),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Batch Scaling Simulator (100 kg to 5,000 kg)
        item {
            BatchScalingSection(
                ingredients = ingredientsList,
                totalBatchWeight = calcResult.totalBatchWeight,
                selectedScaleKg = selectedBatchScaleKg,
                onScaleSelected = { selectedBatchScaleKg = it },
                costCurrency = costCurrency
            )
        }

        // Raw Materials List Title & "إضافة خامة" Button
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Layers, contentDescription = null, tint = Color(0xFF1E40AF), modifier = Modifier.size(18.dp))
                    Text(
                        text = "مكونات الخلطة والمواد الخام (${ingredientsList.size})",
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Button(
                    onClick = { showAddIngredientDialog = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("إضافة خامة", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Ingredients Cards (Mobile-first vertical layout)
        if (ingredientsList.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Science, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(36.dp))
                        Text("الخلطة فارغة حالياً", color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                        Text("اضغط على (إضافة خامة) لإدراج مكونات الدهان من المكتبة", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                }
            }
        } else {
            itemsIndexed(ingredientsList) { index, item ->
                IngredientCard(
                    ingredient = item,
                    index = index + 1,
                    onUpdate = { updated ->
                        ingredientsList = ingredientsList.toMutableList().apply { set(index, updated) }
                    },
                    onDelete = {
                        ingredientsList = ingredientsList.toMutableList().apply { removeAt(index) }
                    }
                )
            }
        }

        // Primary Bottom Action Buttons: Save, PDF, and Factory Work Order
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Primary Firestore Daily Batch Logging Button
                Button(
                    onClick = { showBatchModalFromLab = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("lab_log_daily_batch_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E40AF),
                        contentColor = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "تسجيل تشغيلة يومية في السحابة (Firestore)",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Secondary Factory Dispatch / Move Order Button
                OutlinedButton(
                    onClick = { showProductionOrderDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("lab_create_factory_order_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PrecisionManufacturing, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF1E40AF))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "أمر تشغيل وتوريد خامات للمصنع",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E40AF)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val entity = FormulationEntity(
                                id = currentFormulaId,
                                formulaNo = formulaNo,
                                productName = productName,
                                paintType = paintType,
                                baseKind = baseKind,
                                targetPvc = targetPvcInput.toDoubleOrNull() ?: calcResult.calculatedPvc,
                                calculatedPvc = calcResult.calculatedPvc,
                                cpvcEstimated = calcResult.cpvcEstimated,
                                totalSolidsWeight = calcResult.totalSolidsWeight,
                                totalSolidsVolume = calcResult.totalSolidsVolume,
                                totalBatchWeight = calcResult.totalBatchWeight,
                                totalBatchVolume = calcResult.totalBatchVolume,
                                calculatedDensity = calcResult.calculatedDensity,
                                totalCost = calcResult.totalCost,
                                costPerKg = calcResult.costPerKg,
                                ingredientsJson = PvcEngine.ingredientsToJson(ingredientsList),
                                technicalAnalysis = calcResult.technicalAnalysisText,
                                notes = notesInput
                            )
                            viewModel.saveFormulation(entity)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF059669),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("حفظ الصيغة", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            val entity = FormulationEntity(
                                id = currentFormulaId,
                                formulaNo = formulaNo,
                                productName = productName,
                                paintType = paintType,
                                baseKind = baseKind,
                                targetPvc = targetPvcInput.toDoubleOrNull() ?: calcResult.calculatedPvc,
                                calculatedPvc = calcResult.calculatedPvc,
                                cpvcEstimated = calcResult.cpvcEstimated,
                                totalSolidsWeight = calcResult.totalSolidsWeight,
                                totalSolidsVolume = calcResult.totalSolidsVolume,
                                totalBatchWeight = calcResult.totalBatchWeight,
                                totalBatchVolume = calcResult.totalBatchVolume,
                                calculatedDensity = calcResult.calculatedDensity,
                                totalCost = calcResult.totalCost,
                                costPerKg = calcResult.costPerKg,
                                ingredientsJson = PvcEngine.ingredientsToJson(ingredientsList),
                                technicalAnalysis = calcResult.technicalAnalysisText,
                                notes = notesInput
                            )
                            FormulationPdfExporter.exportAndShareFormulation(context, entity, ingredientsList)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFFFEF2F2),
                            contentColor = Color(0xFFDC2626)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFFECACA)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تصدير PDF", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Dialog: Load Existing Saved Formulation
    if (showLoadDialog) {
        AlertDialog(
            onDismissRequest = { showLoadDialog = false },
            title = {
                Text("سجل تركيبات المختبر المعتمدة", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            },
            text = {
                if (formulations.isEmpty()) {
                    Text("لا توجد تركيبات محفوظة حتى الآن.")
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(formulations) { form ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentFormulaId = form.id
                                        formulaNo = form.formulaNo
                                        productName = form.productName
                                        paintType = form.paintType
                                        baseKind = form.baseKind
                                        targetPvcInput = form.targetPvc.toString()
                                        notesInput = form.notes
                                        val parsed = PvcEngine.jsonToIngredients(form.ingredientsJson)
                                        ingredientsList = parsed
                                        showLoadDialog = false
                                    },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = form.productName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(
                                            text = "${form.formulaNo} • PVC: ${form.calculatedPvc}% • ${form.paintType}",
                                            color = Color(0xFF64748B),
                                            fontSize = 11.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteFormulation(form) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "حذف", tint = Color.Red, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLoadDialog = false }) {
                    Text("إغلاق")
                }
            }
        )
    }

    // Dialog: Add Raw Material (Preset Library + Custom)
    if (showAddIngredientDialog) {
        AddIngredientDialog(
            onDismiss = { showAddIngredientDialog = false },
            onAdd = { newIngredient ->
                ingredientsList = ingredientsList + newIngredient
                showAddIngredientDialog = false
            }
        )
    }

    // Dialog: Create Factory Production Work Order
    if (showProductionOrderDialog) {
        AlertDialog(
            onDismissRequest = { showProductionOrderDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.PrecisionManufacturing, contentDescription = null, tint = Color(0xFF1E40AF))
                    Text("إصدار أمر تشغيل وتوريد خامات للمصنع", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "سيقوم المختبر بتوليد أمر تشغيل رسمي لخلاطات المصنع وحجز كميات المواد الخام المطلوبة لصنع طبخة الدهان:",
                        fontSize = 12.sp,
                        color = Color(0xFF334155)
                    )

                    Text(
                        text = "المنتج: $productName ($formulaNo)\nPVC: ${calcResult.calculatedPvc}% • الكثافة: ${calcResult.calculatedDensity} kg/L",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E40AF)
                    )

                    OutlinedTextField(
                        value = batchOrderMultiplierInput,
                        onValueChange = { batchOrderMultiplierInput = it },
                        label = { Text("معامل حجم الطبخة (Multiplier)") },
                        supportingText = {
                            val mult = batchOrderMultiplierInput.toDoubleOrNull() ?: 1.0
                            val totalBatchKg = calcResult.totalBatchWeight * mult
                            Text("إجمالي حجم الطبخة المنتجة: $totalBatchKg كجم", color = Color(0xFF0D9488))
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "مخزن استلام الإنتاج التام وتوريد البضاعة:",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )

                    var expandedWhOrder by remember { mutableStateOf(false) }
                    val whTargetOptions = listOf(
                        "مخزن الإنتاج التام داخل المصنع (EGP)",
                        "مخزن مبيعات المصنع (مصر) (EGP)",
                        "مخزن مبيعات حلفا (SDG)",
                        "مخزن مبيعات عطبرة (SDG)",
                        "مخزن مبيعات الخرطوم (SDG)"
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expandedWhOrder = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(targetOrderWarehouse, fontSize = 12.sp, color = Color(0xFF1E293B))
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFF64748B))
                            }
                        }
                        DropdownMenu(
                            expanded = expandedWhOrder,
                            onDismissRequest = { expandedWhOrder = false }
                        ) {
                            whTargetOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt, fontSize = 12.sp) },
                                    onClick = {
                                        targetOrderWarehouse = opt
                                        expandedWhOrder = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mult = batchOrderMultiplierInput.toDoubleOrNull() ?: 1.0
                        viewModel.createProductionOrderFromFormula(currentFormulaId, mult)
                        showProductionOrderDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
                ) {
                    Text("تأكيد وإرسال للمصنع")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProductionOrderDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    // Modal: Log Daily Production Batch linked to active chemical formula
    if (showBatchModalFromLab) {
        val currentActiveFormula = remember(
            currentFormulaId, formulaNo, productName, paintType, baseKind,
            targetPvcInput, calcResult, ingredientsList, notesInput
        ) {
            FormulationEntity(
                id = currentFormulaId,
                formulaNo = formulaNo,
                productName = productName,
                paintType = paintType,
                baseKind = baseKind,
                targetPvc = targetPvcInput.toDoubleOrNull() ?: calcResult.calculatedPvc,
                calculatedPvc = calcResult.calculatedPvc,
                cpvcEstimated = calcResult.cpvcEstimated,
                totalSolidsWeight = calcResult.totalSolidsWeight,
                totalSolidsVolume = calcResult.totalSolidsVolume,
                totalBatchWeight = calcResult.totalBatchWeight,
                totalBatchVolume = calcResult.totalBatchVolume,
                calculatedDensity = calcResult.calculatedDensity,
                totalCost = calcResult.totalCost,
                costPerKg = calcResult.costPerKg,
                ingredientsJson = PvcEngine.ingredientsToJson(ingredientsList),
                technicalAnalysis = calcResult.technicalAnalysisText,
                notes = notesInput
            )
        }

        DailyProductionBatchModal(
            availableFormulations = listOf(currentActiveFormula) + formulations.filter { it.id != currentFormulaId },
            factoryMachines = factoryMachines,
            preSelectedFormula = currentActiveFormula,
            onDismiss = { showBatchModalFromLab = false },
            onSaveBatch = { batch ->
                viewModel.logProductionBatch(batch)
                showBatchModalFromLab = false
            }
        )
    }
}

// KPI Laboratory Card Component
@Composable
fun KpiLabCard(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    isHighlight: Boolean = false
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlight) Color(0xFFEFF6FF) else Color.White
        ),
        shape = RoundedCornerShape(14.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(listOf(accentColor.copy(alpha = 0.5f), Color.Transparent))
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, color = Color(0xFF64748B), fontSize = 10.5.sp, maxLines = 1)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = value, color = accentColor, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, color = Color(0xFF94A3B8), fontSize = 10.sp, maxLines = 1)
        }
    }
}

// Interactive PVC Spectrum Gauge Component
@Composable
fun PvcSpectrumGauge(
    calculatedPvc: Double,
    cpvcEstimated: Double,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = Color(0xFF1E40AF), modifier = Modifier.size(18.dp))
                    Text(
                        text = "طيف PVC ومؤشر التركيز الحرج CPVC",
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (calculatedPvc > cpvcEstimated) Color(0xFFFEE2E2) else Color(0xFFEFF6FF),
                    border = BorderStroke(1.dp, if (calculatedPvc > cpvcEstimated) Color(0xFFFECACA) else Color(0xFFBFDBFE))
                ) {
                    Text(
                        text = "PVC: $calculatedPvc%",
                        color = if (calculatedPvc > cpvcEstimated) Color(0xFFDC2626) else Color(0xFF1E40AF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        softWrap = false,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Multi-segment horizontal spectrum bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(25f).fillMaxHeight().background(Color(0xFF1E40AF)), contentAlignment = Alignment.Center) {
                        Text("لامع", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(modifier = Modifier.weight(10f).fillMaxHeight().background(Color(0xFF0284C7)), contentAlignment = Alignment.Center) {
                        Text("نصف لامع", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(modifier = Modifier.weight(10f).fillMaxHeight().background(Color(0xFF059669)), contentAlignment = Alignment.Center) {
                        Text("ساتان", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(modifier = Modifier.weight(7f).fillMaxHeight().background(Color(0xFFD97706)), contentAlignment = Alignment.Center) {
                        Text("مطفي", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(modifier = Modifier.weight(18f).fillMaxHeight().background(Color(0xFFDC2626)), contentAlignment = Alignment.Center) {
                        Text(">CPVC حرج", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Spectrum scale markers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0%", color = Color(0xFF94A3B8), fontSize = 10.sp)
                Text("25% (Gloss)", color = Color(0xFF64748B), fontSize = 10.sp)
                Text("35% (Semi)", color = Color(0xFF64748B), fontSize = 10.sp)
                Text("45% (Satin)", color = Color(0xFF64748B), fontSize = 10.sp)
                Text("52% (CPVC)", color = Color(0xFFDC2626), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("70%", color = Color(0xFF94A3B8), fontSize = 10.sp)
            }
        }
    }
}

// Batch Scaling Simulator Component
@Composable
fun BatchScalingSection(
    ingredients: List<FormulationIngredient>,
    totalBatchWeight: Double,
    selectedScaleKg: Double,
    onScaleSelected: (Double) -> Unit,
    costCurrency: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Scale, contentDescription = null, tint = Color(0xFF1E40AF), modifier = Modifier.size(18.dp))
                    Text(
                        text = "محاكي التحجيم وحساب أوزان الطبخة (Batch Scaling)",
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Batch size chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    100.0 to "100 كجم (معملي)",
                    250.0 to "250 كجم (تجريبي)",
                    500.0 to "500 كجم (دفعة ص)",
                    1000.0 to "1,000 كجم (طن)",
                    5000.0 to "5,000 كجم (خلاط 5 طن)"
                ).forEach { (scale, label) ->
                    FilterChip(
                        selected = selectedScaleKg == scale,
                        onClick = { onScaleSelected(scale) },
                        label = { Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF1E40AF),
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Scaled table summary
            val multiplier = if (totalBatchWeight > 0) selectedScaleKg / totalBatchWeight else 1.0
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF8FAFC))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("الخامة في الخلطة", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f))
                    Text("النسبة %", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.7f), textAlign = TextAlign.Center)
                    Text("الوزن المطلوب ($selectedScaleKg kg)", color = Color(0xFF1E40AF), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f), textAlign = TextAlign.End)
                }
                HorizontalDivider(color = Color(0xFFE2E8F0))
                ingredients.forEach { ing ->
                    val scaledKg = ing.weight * multiplier
                    val percent = if (totalBatchWeight > 0) (ing.weight / totalBatchWeight) * 100.0 else 0.0
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(ing.name, color = Color(0xFF0F172A), fontSize = 11.sp, modifier = Modifier.weight(1.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(String.format(Locale.US, "%.1f%%", percent), color = Color(0xFF64748B), fontSize = 11.sp, modifier = Modifier.weight(0.7f), textAlign = TextAlign.Center)
                        Text(String.format(Locale.US, "%.2f كجم", scaledKg), color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 11.5.sp, modifier = Modifier.weight(1.2f), textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

// Raw Material Ingredient Card in Formulation
@Composable
fun IngredientCard(
    ingredient: FormulationIngredient,
    index: Int,
    onUpdate: (FormulationIngredient) -> Unit,
    onDelete: () -> Unit
) {
    var weightStr by remember(ingredient.weight) { mutableStateOf(ingredient.weight.toString()) }
    var densityStr by remember(ingredient.density) { mutableStateOf(ingredient.density.toString()) }
    var solidsStr by remember(ingredient.solidsPercent) { mutableStateOf(ingredient.solidsPercent.toString()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E40AF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "$index", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Text(
                        text = ingredient.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color(0xFF0F172A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when {
                            ingredient.type.contains("صبغة") -> Color(0xFFDBEAFE)
                            ingredient.type.contains("راتنج") -> Color(0xFFD1FAE5)
                            ingredient.type.contains("مالئ") -> Color(0xFFFEF3C7)
                            else -> Color(0xFFF1F5F9)
                        }
                    ) {
                        Text(
                            text = ingredient.type,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                ingredient.type.contains("صبغة") -> Color(0xFF1E40AF)
                                ingredient.type.contains("راتنج") -> Color(0xFF065F46)
                                ingredient.type.contains("مالئ") -> Color(0xFF92400E)
                                else -> Color(0xFF475569)
                            },
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color(0xFFFEE2E2), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "حذف الخامة",
                            tint = Color(0xFFDC2626),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Micro-inputs for Weight, Density, Solids%
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = weightStr,
                    onValueChange = {
                        weightStr = it
                        val w = it.toDoubleOrNull() ?: 0.0
                        onUpdate(ingredient.copy(weight = w))
                    },
                    label = { Text("الوزن (kg)", fontSize = 10.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                )

                OutlinedTextField(
                    value = densityStr,
                    onValueChange = {
                        densityStr = it
                        val d = it.toDoubleOrNull() ?: 1.0
                        onUpdate(ingredient.copy(density = d))
                    },
                    label = { Text("الكثافة g/cm³", fontSize = 10.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                )

                OutlinedTextField(
                    value = solidsStr,
                    onValueChange = {
                        solidsStr = it
                        val s = it.toDoubleOrNull() ?: 100.0
                        onUpdate(ingredient.copy(solidsPercent = s))
                    },
                    label = { Text("صلابة %", fontSize = 10.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(0.9f),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                )
            }
        }
    }
}

// Dialog: Add Material with Presets
@Composable
fun AddIngredientDialog(
    onDismiss: () -> Unit,
    onAdd: (FormulationIngredient) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("صبغة / ملون") }
    var weight by remember { mutableStateOf("10.0") }
    var density by remember { mutableStateOf("2.7") }
    var solids by remember { mutableStateOf("100.0") }
    var cost by remember { mutableStateOf("1.0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة خامة جديدة للتركيبة", fontWeight = FontWeight.Bold, fontSize = 14.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("المواد الخام الشائعة في الدهانات (انقر للاختيار التلقائي):", fontSize = 11.sp, color = Color(0xFF64748B))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(PvcEngine.PRESET_MATERIALS) { preset ->
                        FilterChip(
                            selected = name == preset.name,
                            onClick = {
                                name = preset.name
                                type = preset.type
                                density = preset.defaultDensity.toString()
                                solids = preset.defaultSolidsPercent.toString()
                                cost = preset.defaultUnitCost.toString()
                            },
                            label = { Text(preset.name.take(18), fontSize = 10.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم المادة الخام") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = weight,
                        onValueChange = { weight = it },
                        label = { Text("الوزن (kg)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = density,
                        onValueChange = { density = it },
                        label = { Text("الكثافة (g/cm³)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = solids,
                        onValueChange = { solids = it },
                        label = { Text("المواد الصلبة %") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = cost,
                        onValueChange = { cost = it },
                        label = { Text("تكلفة الكيلو") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onAdd(
                            FormulationIngredient(
                                name = name.trim(),
                                type = type,
                                weight = weight.toDoubleOrNull() ?: 1.0,
                                density = density.toDoubleOrNull() ?: 1.0,
                                solidsPercent = solids.toDoubleOrNull() ?: 100.0,
                                unitCost = cost.toDoubleOrNull() ?: 0.0
                            )
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
            ) {
                Text("إضافة للخلطة")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

// =========================================================================
// TAB 2: PUBCHEM CHEMICAL LIBRARY
// =========================================================================

@Composable
fun PubChemTab(viewModel: PmpViewModel) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("Titanium Dioxide") }
    val isSearching by viewModel.isSearchingPubChem.collectAsState()
    val searchResults by viewModel.pubChemResults.collectAsState()
    val errorMessage by viewModel.pubChemErrorMessage.collectAsState()

    // Trigger initial search
    LaunchedEffect(Unit) {
        if (searchResults.isEmpty()) {
            viewModel.searchPubChem("Titanium Dioxide")
        }
    }

    val quickChemicalChips = listOf(
        "Titanium Dioxide",
        "Calcium Carbonate",
        "Acrylic Acid",
        "Texanol",
        "Barium Sulfate",
        "Zinc Oxide",
        "Propylene Glycol",
        "Ammonia"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Search Bar Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF1E40AF))
                        Text(
                            text = "البحث في قاعدة بيانات PubChem العالمية (NCBI)",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A),
                            fontSize = 13.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("أدخل اسم المركب أو رقم CAS...") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pubchem_search_input"),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Biotech, contentDescription = null, tint = Color(0xFF64748B)) }
                        )

                        Button(
                            onClick = { viewModel.searchPubChem(searchQuery) },
                            enabled = !isSearching,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("pubchem_search_button")
                        ) {
                            if (isSearching) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                            } else {
                                Text("بحث", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text("خامات الطلاء المقترحة للبحث السريع:", fontSize = 11.sp, color = Color(0xFF64748B))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(quickChemicalChips) { chip ->
                            SuggestionChip(
                                onClick = {
                                    searchQuery = chip
                                    viewModel.searchPubChem(chip)
                                },
                                label = { Text(chip, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }

        // Error message if any
        if (errorMessage != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color.Red, modifier = Modifier.size(20.dp))
                        Text(text = errorMessage ?: "", color = Color(0xFF991B1B), fontSize = 12.sp)
                    }
                }
            }
        }

        // Results List
        item {
            Text(
                text = "نتائج البحث الكيميائي (${searchResults.size})",
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A),
                fontSize = 13.5.sp
            )
        }

        if (isSearching && searchResults.isEmpty()) {
            item(key = "pubchem_shimmer_skeleton") {
                ProvideShimmerEffect(enabled = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(3) {
                            ShimmerListRowCardSkeleton(showTrailingBadge = true, showBottomMetaRow = true)
                        }
                    }
                }
            }
        } else {
            items(searchResults) { comp ->
                PubChemCompoundCard(
                    compound = comp,
                    onAddToCatalog = {
                        viewModel.addPubChemCompoundToFactoryCatalog(comp)
                    },
                    onOpenWeb = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(comp.pubChemUrl))
                        context.startActivity(intent)
                    }
                )
            }
        }
    }
}

// PubChem Compound Result Card
@Composable
fun PubChemCompoundCard(
    compound: PubChemService.PubChemCompound,
    onAddToCatalog: () -> Unit,
    onOpenWeb: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = compound.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        text = compound.iupacName,
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFEFF6FF))
                        .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = compound.molecularFormula,
                        color = Color(0xFF1E40AF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Properties Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PropertyChip(label = "الوزن المولي", value = "${compound.molecularWeight} g/mol")
                if (compound.casNumber.isNotBlank()) {
                    PropertyChip(label = "CAS No", value = compound.casNumber)
                }
                PropertyChip(label = "PubChem CID", value = "#${compound.cid}")
            }

            if (compound.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = compound.description,
                    color = Color(0xFF334155),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAddToCatalog,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Icon(Icons.Default.AddBusiness, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("إضافة لخامات المصنع", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onOpenWeb,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("PubChem", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun PropertyChip(label: String, value: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFF1F5F9))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column {
            Text(text = label, fontSize = 9.sp, color = Color(0xFF64748B))
            Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
        }
    }
}

// =========================================================================
// TAB 3: GHS / SDS CHEMICAL SAFETY
// =========================================================================

@Composable
fun GhsSafetyTab(viewModel: PmpViewModel) {
    val context = LocalContext.current
    val safetyList by viewModel.chemicalSafetyList.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    val filteredList = remember(safetyList, searchQuery) {
        if (searchQuery.isBlank()) safetyList else {
            safetyList.filter {
                it.chemicalName.contains(searchQuery, ignoreCase = true) ||
                    it.casNumber.contains(searchQuery, ignoreCase = true) ||
                    it.commonName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Safety Overview & Standard Header
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(24.dp))
                            Column {
                                Text(
                                    text = "نظام السلامة وتصنيف المخاطر الكيميائية (GHS / SDS)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp
                                )
                                Text(
                                    text = "النظام المنسق عالمياً لتصنيف ووسم المواد الكيميائية لمصانع الدهانات",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.5.sp
                                )
                            }
                        }

                        Button(
                            onClick = { showAddDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("بطاقة جديدة", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("بحث في سجل المواد الخطرة ورموز CAS...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF94A3B8)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF334155)
                        )
                    )
                }
            }
        }

        items(filteredList) { item ->
            ChemicalSafetyCard(
                item = item,
                onDelete = { viewModel.deleteChemicalSafety(item) },
                onViewSds = {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "بيانات السلامة الكيميائية SDS: ${item.chemicalName}")
                        putExtra(
                            Intent.EXTRA_TEXT,
                            """
                            *شركة PMP للدهانات — صحيفة بيانات السلامة (SDS)*
                            المادة: ${item.chemicalName}
                            كود CAS: ${item.casNumber}
                            الصيغة: ${item.molecularFormula}
                            كلمة التنبيه: ${item.signalWord}
                            بيانات المخاطر: ${item.hazardStatements}
                            إرشادات الوقاية: ${item.precautionaryStatements}
                            ظروف التخزين: ${item.storageConditions}
                            الإسعافات الأولية: ${item.firstAidMeasures}
                            المورد المعتمد: ${item.sdsSupplierName}
                            """.trimIndent()
                        )
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "مشاركة صحيفة بيانات السلامة"))
                }
            )
        }
    }

    // Dialog: Add Chemical Safety Item
    if (showAddDialog) {
        AddSafetyDialog(
            onDismiss = { showAddDialog = false },
            onSave = { newSafety ->
                viewModel.saveChemicalSafety(newSafety)
                showAddDialog = false
            }
        )
    }
}

// Chemical Safety Card with GHS Pictograms
@Composable
fun ChemicalSafetyCard(
    item: ChemicalSafetyEntity,
    onDelete: () -> Unit,
    onViewSds: () -> Unit
) {
    val isDanger = item.signalWord.contains("خطر")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(if (isDanger) Color(0xFFEF4444) else Color(0xFFF59E0B), Color.Transparent)
            )
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.chemicalName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        text = "${item.commonName} • CAS: ${item.casNumber} • ${item.molecularFormula}",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isDanger) Color(0xFFFEE2E2) else Color(0xFFFEF3C7))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = item.signalWord,
                        color = if (isDanger) Color(0xFFDC2626) else Color(0xFFD97706),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // GHS Pictograms Row
            GhsPictogramRow(pictogramsStr = item.ghsPictograms)

            Spacer(modifier = Modifier.height(10.dp))

            // Hazard Statements
            if (item.hazardStatements.isNotBlank()) {
                Text(text = "بيانات المخاطر (H-Statements):", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF991B1B))
                Text(text = item.hazardStatements, fontSize = 11.sp, color = Color(0xFF334155), lineHeight = 15.sp)
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Precautionary Statements
            if (item.precautionaryStatements.isNotBlank()) {
                Text(text = "إرشادات الوقاية والمناولة (P-Statements):", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF1E40AF))
                Text(text = item.precautionaryStatements, fontSize = 11.sp, color = Color(0xFF334155), lineHeight = 15.sp)
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Storage & First Aid
            if (item.storageConditions.isNotBlank()) {
                Text(text = "ظروف التخزين الموصى بها:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF047857))
                Text(text = item.storageConditions, fontSize = 11.sp, color = Color(0xFF334155), lineHeight = 15.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "المورد: ${item.sdsSupplierName}",
                    fontSize = 10.sp,
                    color = Color(0xFF94A3B8)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = onViewSds,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFFEFF6FF),
                            contentColor = Color(0xFF1D4ED8)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("مشاركة SDS", fontSize = 11.sp)
                    }

                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "حذف", tint = Color.Red, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun GhsPictogramRow(pictogramsStr: String) {
    val items = pictogramsStr.split(",").map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotBlank() }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in items) {
            val (label, color, icon) = when {
                p.contains("FLAME") || p.contains("FLAMMABLE") -> Triple("قابل للاشتعال", Color(0xFFEF4444), Icons.Default.LocalFireDepartment)
                p.contains("CORROSIVE") -> Triple("مادة آكلة", Color(0xFFD97706), Icons.Default.Warning)
                p.contains("TOXIC") -> Triple("سام", Color(0xFF991B1B), Icons.Default.Dangerous)
                p.contains("HEALTH") -> Triple("خطر صحي", Color(0xFF7C3AED), Icons.Default.HealthAndSafety)
                p.contains("ENVIRONMENT") -> Triple("خطر بيئي", Color(0xFF059669), Icons.Default.WaterDrop)
                else -> Triple("تحذير عام", Color(0xFFEAB308), Icons.Default.PriorityHigh)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.12f))
                    .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                    Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
                }
            }
        }
    }
}

// Dialog: Add Safety Card
@Composable
fun AddSafetyDialog(
    onDismiss: () -> Unit,
    onSave: (ChemicalSafetyEntity) -> Unit
) {
    var chemicalName by remember { mutableStateOf("") }
    var commonName by remember { mutableStateOf("") }
    var casNumber by remember { mutableStateOf("") }
    var formula by remember { mutableStateOf("") }
    var signalWord by remember { mutableStateOf("تحذير") }
    var hazardStatements by remember { mutableStateOf("") }
    var precautionaryStatements by remember { mutableStateOf("") }
    var storage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة بطاقة سلامة كيميائية SDS جديدة", fontWeight = FontWeight.Bold, fontSize = 14.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = chemicalName,
                    onValueChange = { chemicalName = it },
                    label = { Text("الاسم الكيميائي الرسمي") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = casNumber,
                        onValueChange = { casNumber = it },
                        label = { Text("رقم CAS") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = signalWord,
                        onValueChange = { signalWord = it },
                        label = { Text("كلمة التنبيه (خطر / تحذير)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = hazardStatements,
                    onValueChange = { hazardStatements = it },
                    label = { Text("عبارات المخاطر H-Statements") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
                OutlinedTextField(
                    value = precautionaryStatements,
                    onValueChange = { precautionaryStatements = it },
                    label = { Text("إرشادات الوقاية P-Statements") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
                OutlinedTextField(
                    value = storage,
                    onValueChange = { storage = it },
                    label = { Text("ظروف التخزين الموصى بها") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (chemicalName.isNotBlank()) {
                        onSave(
                            ChemicalSafetyEntity(
                                chemicalName = chemicalName.trim(),
                                commonName = commonName.trim(),
                                casNumber = casNumber.trim(),
                                molecularFormula = formula.trim(),
                                signalWord = signalWord.trim(),
                                ghsPictograms = if (signalWord.contains("خطر")) "TOXIC,CORROSIVE" else "EXCLAMATION,HEALTH_HAZARD",
                                hazardStatements = hazardStatements.trim(),
                                precautionaryStatements = precautionaryStatements.trim(),
                                storageConditions = storage.trim()
                            )
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
            ) {
                Text("حفظ البطاقة")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}
