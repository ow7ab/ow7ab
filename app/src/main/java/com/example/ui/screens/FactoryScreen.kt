package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.example.data.local.FactoryItemEntity
import com.example.data.local.FormulationEntity
import com.example.data.local.ProductionBatchEntity
import com.example.util.PvcEngine
import com.example.hasModulePermission
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.example.isSystemManager
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FactorySuccessRecord(
    val title: String,
    val subtitle: String,
    val itemName: String,
    val quantity: String,
    val destinationWarehouse: String,
    val responsibleDepartment: String,
    val counterparty: String? = null,
    val financialAmount: String? = null,
    val note: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Composable
fun FactoryScreen(
    viewModel: PmpViewModel,
    modifier: Modifier = Modifier
) {
    val factoryItems by viewModel.factoryItems.collectAsState()
    val factoryMachines by viewModel.factoryMachines.collectAsState()
    val factoryMoves by viewModel.factoryMoves.collectAsState()
    val halfaInvoices by viewModel.halfaInvoices.collectAsState()
    val products by viewModel.products.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val productionBatches by viewModel.productionBatches.collectAsState()
    val formulations by viewModel.formulations.collectAsState()
    val isInitialDataLoading by viewModel.isInitialDataLoading.collectAsState()
    val dataLoadError by viewModel.dataLoadError.collectAsState()

    var showBatchModal by remember { mutableStateOf(false) }
    var selectedFormulaForBatch by remember { mutableStateOf<FormulationEntity?>(null) }
    var batchSearchQuery by remember { mutableStateOf("") }
    var batchQualityFilter by remember { mutableStateOf("الكل") }
    var expandedBatchId by remember { mutableStateOf<Long?>(null) }
    var batchToDelete by remember { mutableStateOf<ProductionBatchEntity?>(null) }

    val isManager = currentUser.isSystemManager()
    val hasFactoryFull = isManager || currentUser.hasModulePermission("المصنع")
    val hasRawMaterials = hasFactoryFull || currentUser.hasModulePermission("مواد الإنتاج")
    val hasPackaging = hasFactoryFull || currentUser.hasModulePermission("التعبئة والتغليف")

    // Filter tabs strictly based on assigned permissions
    val tabs = remember(hasFactoryFull, hasRawMaterials, hasPackaging) {
        val list = mutableListOf<String>()
        if (hasRawMaterials) {
            list.add("المخزن")
        }
        if (hasPackaging) {
            list.add("تشغيلات الإنتاج")
            list.add("أوامر الإنتاج")
        }
        if (hasFactoryFull) {
            list.add("خروج المنتج للعملاء")
            list.add("سجل دورة المصنع")
        }
        list.ifEmpty { listOf("المخزن") }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val format = remember { NumberFormat.getNumberInstance(Locale.US) }

    // Tab 0: Raw Materials Procurement State (Linked with Egypt - EGP)
    var rawItemName by remember { mutableStateOf("") }
    var rawQty by remember { mutableStateOf("100") }
    var rawPurchaseAmount by remember { mutableStateOf("") }
    var rawCurrency by remember { mutableStateOf("EGP") }
    var rawSupplier by remember { mutableStateOf("") }
    var rawNote by remember { mutableStateOf("") }

    // Tab 1: Raw Materials Warehouse State
    var rcvItemName by remember { mutableStateOf("") }
    var rcvQty by remember { mutableStateOf("50") }
    var rcvSupplier by remember { mutableStateOf("") }
    var rcvNote by remember { mutableStateOf("") }

    var issueItemName by remember { mutableStateOf("") }
    var issueQty by remember { mutableStateOf("25") }
    var issueEngineer by remember { mutableStateOf("مهندس خط الإنتاج") }
    var issueNote by remember { mutableStateOf("") }

    // Tab 2: Production Orders State
    // Preset: 250g -> 48 cans/carton, 900g -> 12 cans/carton, Custom -> editable
    var prodPreset by remember { mutableStateOf("900") } // "250", "900", "CUSTOM"
    var prodWeight by remember { mutableStateOf("900") }
    var prodCansPerCarton by remember { mutableStateOf("12") }
    var prodCartonsCount by remember { mutableStateOf("10") }
    var prodCansCount by remember { mutableStateOf("120") }
    var prodMachineName by remember { mutableStateOf(factoryMachines.firstOrNull()?.name ?: "خط إنتاج رقم 1") }
    var prodBatch by remember { mutableStateOf("BATCH-2026-A1") }
    var prodNote by remember { mutableStateOf("") }

    // Tab 3: Finished Product Dispatch State
    var dispatchType by remember { mutableStateOf("CUSTOMER") } // "CUSTOMER" or "HALFA"
    var dispatchProductText by remember { mutableStateOf(products.firstOrNull()?.name ?: "بوهية أبيض 900 جم") }
    var dispatchCartons by remember { mutableStateOf("20") }
    var dispatchCustomerName by remember { mutableStateOf("") }
    var dispatchPrice by remember { mutableStateOf("420000") }
    var dispatchCurrency by remember { mutableStateOf("EGP") } // Defaults to EGP for Egypt-linked goods
    var dispatchNote by remember { mutableStateOf("") }

    // Tab 4: Factory Moves Log State
    var showAllFactoryMoves by remember { mutableStateOf(false) }

    // Dialog State: Add / Delete Factory Item
    var showAddItemDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    var newItemKind by remember { mutableStateOf("خامات إنتاج") }
    var newItemUnit by remember { mutableStateOf("كجم") }
    var itemToDelete by remember { mutableStateOf<FactoryItemEntity?>(null) }
    var successRecord by remember { mutableStateOf<FactorySuccessRecord?>(null) }

    val rawMaterialItems = remember(factoryItems) {
        factoryItems.filter { it.category.contains("خام", ignoreCase = true) || it.category.contains("تعبئة", ignoreCase = true) }
            .ifEmpty { factoryItems }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 24.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "المصنع والإنتاج ودورة التصنيع",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    GlassTabRow(
                        tabs = tabs,
                        selectedIndex = selectedTab,
                        onTabSelected = { selectedTab = it }
                    )
                }
            }
        }

        val activeTabTitle = tabs.getOrElse(selectedTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))) { tabs.firstOrNull() ?: "المخزن" }

        if (isInitialDataLoading && factoryItems.isEmpty() && productionBatches.isEmpty()) {
            item(key = "factory_shimmer_skeleton") {
                ProvideShimmerEffect(enabled = true) {
                    FactorySkeletonSection()
                }
            }
        } else if (dataLoadError != null && factoryItems.isEmpty() && productionBatches.isEmpty()) {
            item(key = "factory_error_card") {
                PmpErrorStateCard(
                    message = dataLoadError ?: "تعذر تحميل بيانات المصنع",
                    onRetry = { viewModel.retryDataLoadAndSync() }
                )
            }
        } else when (activeTabTitle) {
            "المخزن", "مخزن المواد الخام" -> {
                // Tab 1: Raw Materials Warehouse (المخزن)
                val rawItemNames = factoryItems.map { it.name }.ifEmpty { listOf("صبغات تيتانيوم أبيض", "راتنجات ألكيد", "مذيبات ومخففات") }
                val currentRcvItem = if (rcvItemName.isNotBlank()) rcvItemName else rawItemNames.first()
                val currentIssueItem = if (issueItemName.isNotBlank()) issueItemName else rawItemNames.first()

                // Section 1: Receive from Supplier / Procurement
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = PmpGreen, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "استلام مواد خام من المورد (مستودع مصر)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = PmpGreen,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            GlassDropdown(
                                label = "الصنف المستلم *",
                                options = rawItemNames,
                                selectedOption = currentRcvItem,
                                onOptionSelected = { rcvItemName = it }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassTextField(
                                    value = rcvQty,
                                    onValueChange = { rcvQty = it },
                                    label = "الكمية المستلمة *",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = rcvSupplier,
                                    onValueChange = { rcvSupplier = it },
                                    label = "المورد المسلم منه (مصر)",
                                    placeholder = "مورد مصر المعتمد...",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            GlassTextField(
                                value = rcvNote,
                                onValueChange = { rcvNote = it },
                                label = "ملاحظات الفحص والاستلام ومستودع مصر",
                                placeholder = "مثال: فحص الجودة مطابق للشحنة الواردة من مصر"
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            GlassButton(
                                text = "تأكيد الاستلام",
                                icon = Icons.Default.CheckCircle,
                                gradient = GlassAccentGreenGradient,
                                onClick = {
                                    val qty = rcvQty.toDoubleOrNull() ?: 1.0
                                    val targetItem = factoryItems.find { it.name == currentRcvItem } ?: factoryItems.firstOrNull()
                                    val itemId = targetItem?.id ?: 1L

                                    viewModel.addFactoryMove(
                                        kind = "RAW_RECEIPT",
                                        itemId = itemId,
                                        itemName = currentRcvItem,
                                        qty = qty,
                                        note = rcvNote.ifBlank { "استلام خامات واردة من مصر وقيدها بالجنيه المصري" },
                                        counterparty = rcvSupplier.ifBlank { "مورد مصر المعتمد (EGP)" },
                                        actor = "أمين مخزن المواد الخام"
                                    )
                                    successRecord = FactorySuccessRecord(
                                        title = "تم تأكيد استلام المادة في مخزن المواد الخام",
                                        subtitle = "تم إدخال البيانات يدوياً وإيداع الكمية في الرصيد الفعلي لمخزن المواد الخام (ربط مصر).",
                                        itemName = currentRcvItem,
                                        quantity = "$qty ${targetItem?.unit ?: "كجم"}",
                                        destinationWarehouse = "مخزن المواد الخام (مرتبط بمستودع مصر)",
                                        responsibleDepartment = "أمين مخزن المواد الخام",
                                        counterparty = rcvSupplier.ifBlank { "مورد مصر المعتمد (EGP)" },
                                        note = rcvNote.ifBlank { null }
                                    )
                                    rcvQty = "50"
                                    rcvNote = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Section 2: Issue from Raw Warehouse to Production
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Output, contentDescription = null, tint = PmpBrandRed, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "صرف مواد خام من المخزن إلى الإنتاج",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = PmpBrandRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            GlassDropdown(
                                label = "الصنف المراد صرفه *",
                                options = rawItemNames,
                                selectedOption = currentIssueItem,
                                onOptionSelected = { issueItemName = it }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassTextField(
                                    value = issueQty,
                                    onValueChange = { issueQty = it },
                                    label = "الكمية المصروفة *",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = issueEngineer,
                                    onValueChange = { issueEngineer = it },
                                    label = "المستلم (المهندس/الخط)",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            GlassTextField(
                                value = issueNote,
                                onValueChange = { issueNote = it },
                                label = "أمر التشغيل / ملاحظات الصرف",
                                placeholder = "مثال: صرف لخلطة دفعة إنتاج 900 جم"
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            GlassButton(
                                text = "صرف للإنتاج",
                                icon = Icons.Default.Sensors,
                                gradient = PmpBrandRedGradient,
                                onClick = {
                                    val qty = issueQty.toDoubleOrNull() ?: 1.0
                                    val targetItem = factoryItems.find { it.name == currentIssueItem } ?: factoryItems.firstOrNull()
                                    val itemId = targetItem?.id ?: 1L

                                    viewModel.addFactoryMove(
                                        kind = "ISSUE_PRODUCTION",
                                        itemId = itemId,
                                        itemName = currentIssueItem,
                                        qty = qty,
                                        note = issueNote.ifBlank { "صرف خامات من المخزن إلى الإنتاج" },
                                        counterparty = issueEngineer,
                                        actor = "أمين مخزن المواد الخام"
                                    )
                                    successRecord = FactorySuccessRecord(
                                        title = "تم صرف المواد للإنتاج بنجاح",
                                        subtitle = "تم خصم الكمية من رصيد مخزن المواد الخام وتسليمها لخط الإنتاج.",
                                        itemName = currentIssueItem,
                                        quantity = "$qty ${targetItem?.unit ?: "كجم"}",
                                        destinationWarehouse = "خط تشغيل الإنتاج",
                                        responsibleDepartment = "أمين مخزن المواد الخام",
                                        counterparty = issueEngineer,
                                        note = issueNote.ifBlank { null }
                                    )
                                    issueQty = "25"
                                    issueNote = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            "تشغيلات الإنتاج", "تشغيلات الإنتاج (Batches)" -> {
                // Tab: Daily Production Batches linked to Chemical Formulations & Firestore
                item {
                    val totalWeight = productionBatches.sumOf { if (it.actualWeightKg > 0) it.actualWeightKg else it.targetWeightKg }
                    val totalCartons = productionBatches.sumOf { it.cartonsCount }
                    val passedCount = productionBatches.count { it.qualityStatus == "معتمد ومطابق" }

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = Color.White,
                        borderColor = Color(0xFFCBD5E1),
                        cornerRadius = 20.dp
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "سجل تشغيلات الإنتاج اليومية",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF1E40AF),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Key Statistics - 2x2 Grid with ample spacing to prevent text clipping
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFFF1F5F9)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("إجمالي التشغيلات", fontSize = 11.sp, color = Color(0xFF64748B))
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text("${productionBatches.size}", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF1E293B))
                                        }
                                    }

                                    Surface(
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFFF1F5F9)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("الوزن الإجمالي", fontSize = 11.sp, color = Color(0xFF64748B))
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text("${format.format(totalWeight)} كجم", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF1E40AF))
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFFF1F5F9)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("العبوات المعبأة", fontSize = 11.sp, color = Color(0xFF64748B))
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text("${format.format(totalCartons)}", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF059669))
                                        }
                                    }

                                    Surface(
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFFF1F5F9)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("فحص الجودة مطابق QC", fontSize = 11.sp, color = Color(0xFF64748B))
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text("$passedCount", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF16A34A))
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // New Batch Action Button
                            Button(
                                onClick = {
                                    selectedFormulaForBatch = formulations.firstOrNull()
                                    showBatchModal = true
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("open_create_batch_modal_btn"),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "تسجيل تشغيلة إنتاج جديدة وربطها بالتركيبة المعملية",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // Filter & Search Row
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = batchSearchQuery,
                            onValueChange = { batchSearchQuery = it },
                            placeholder = { Text("بحث برقم التشغيلة، اسم الصنف، أو كود التركيبة...", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF64748B)) },
                            trailingIcon = {
                                if (batchSearchQuery.isNotBlank()) {
                                    IconButton(onClick = { batchSearchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "مسح", tint = Color(0xFF64748B))
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("الكل", "معتمد ومطابق", "تحت الفحص المخبري", "مرفوض/معالجة").forEach { status ->
                                val isSelected = batchQualityFilter == status
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { batchQualityFilter = status },
                                    label = { Text(status, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) }
                                )
                            }
                        }
                    }
                }

                // Filtered Batches List
                val filteredBatches = productionBatches.filter { b ->
                    val matchesQuery = batchSearchQuery.isBlank() ||
                        b.batchNumber.contains(batchSearchQuery, ignoreCase = true) ||
                        b.productName.contains(batchSearchQuery, ignoreCase = true) ||
                        b.formulaNo.contains(batchSearchQuery, ignoreCase = true) ||
                        b.machineName.contains(batchSearchQuery, ignoreCase = true)

                    val matchesFilter = batchQualityFilter == "الكل" || b.qualityStatus == batchQualityFilter
                    matchesQuery && matchesFilter
                }

                if (filteredBatches.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PrecisionManufacturing,
                                    contentDescription = null,
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = if (batchSearchQuery.isNotBlank() || batchQualityFilter != "الكل") {
                                        "لا توجد تشغيلات مطابقة لمعايير البحث الحالية."
                                    } else {
                                        "لم يتم تسجيل أي تشغيلات إنتاجية حتى الآن."
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF64748B),
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = {
                                        selectedFormulaForBatch = formulations.firstOrNull()
                                        showBatchModal = true
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("تسجيل أول تشغيلة إنتاج", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                } else {
                    items(filteredBatches, key = { it.id }) { batch ->
                        val isExpanded = expandedBatchId == batch.id
                        val statusColor = when (batch.qualityStatus) {
                            "معتمد ومطابق" -> Color(0xFF059669)
                            "تحت الفحص المخبري" -> Color(0xFFD97706)
                            else -> Color(0xFFDC2626)
                        }
                        val statusBg = when (batch.qualityStatus) {
                            "معتمد ومطابق" -> Color(0xFFECFDF5)
                            "تحت الفحص المخبري" -> Color(0xFFFEF3C7)
                            else -> Color(0xFFFEF2F2)
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("batch_card_${batch.id}"),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Top row: Batch Number & Status & Date
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFF1E40AF)
                                        ) {
                                            Text(
                                                text = batch.batchNumber,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = statusBg,
                                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f))
                                        ) {
                                            Text(
                                                text = batch.qualityStatus,
                                                color = statusColor,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Default.CalendarToday, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(13.dp))
                                        Text(batch.productionDate, fontSize = 11.sp, color = Color(0xFF64748B))
                                    }
                                }

                                // Product & Formula Link
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = batch.productName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color(0xFF0F172A)
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFFF1F5F9)
                                        ) {
                                            Text(
                                                text = "التركيبة: ${batch.formulaNo}",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF2563EB),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        if (batch.targetPvc > 0) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = Color(0xFFEFF6FF)
                                            ) {
                                                Text(
                                                    text = "PVC: ${batch.targetPvc}%",
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF1D4ED8),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Text(
                                            text = "نوع الدهان: ${batch.paintType}",
                                            fontSize = 10.5.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }

                                HorizontalDivider(color = Color(0xFFF1F5F9))

                                // Batch Operational Numbers - 2x2 Grid preventing overlapping text
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                                        .border(0.8.dp, Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("الوزن الإجمالي", fontSize = 10.5.sp, color = Color(0xFF64748B))
                                            val w = if (batch.actualWeightKg > 0) batch.actualWeightKg else batch.targetWeightKg
                                            Text("${format.format(w)} كجم", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("التعبئة والتغليف", fontSize = 10.5.sp, color = Color(0xFF64748B))
                                            Text("${batch.cartonsCount} ${batch.packageUnit}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                        }
                                    }

                                    HorizontalDivider(color = Color(0xFFE2E8F0), thickness = 0.6.dp)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("الحجم التقريبي", fontSize = 10.5.sp, color = Color(0xFF64748B))
                                            Text("${format.format(batch.targetVolumeLiters)} لتر", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("الماكينة / الخلاط", fontSize = 10.5.sp, color = Color(0xFF64748B))
                                            Text(batch.machineName.ifBlank { "الديزولفر الرئيسي" }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E40AF), maxLines = 1)
                                        }
                                    }
                                }

                                // Lab QC measurements row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        if (batch.measuredDensity > 0) {
                                            Text(
                                                text = "الكثافة: ${batch.measuredDensity} g/cm³",
                                                fontSize = 11.sp,
                                                color = Color(0xFF334155),
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        if (batch.measuredViscosity.isNotBlank()) {
                                            Text(
                                                text = "اللزوجة: ${batch.measuredViscosity}",
                                                fontSize = 11.sp,
                                                color = Color(0xFF334155),
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.Verified, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(14.dp))
                                        Text("موثق", fontSize = 10.sp, color = Color(0xFF2563EB), fontWeight = FontWeight.Bold)
                                    }
                                }

                                // Expandable Ingredients Breakdown
                                val scaledIngs = remember(batch.scaledIngredientsJson) {
                                    PvcEngine.jsonToIngredients(batch.scaledIngredientsJson)
                                }

                                if (scaledIngs.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { expandedBatchId = if (isExpanded) null else batch.id }
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                tint = Color(0xFF1E40AF),
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = if (isExpanded) "إخفاء بيان المواد الخام المستهلكة" else "عرض بيان استهلاك المواد الخام (${scaledIngs.size} مادة)",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF1E40AF)
                                            )
                                        }

                                        IconButton(
                                            onClick = { batchToDelete = batch },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "حذف التشغيلة",
                                                tint = Color(0xFFEF4444),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    if (isExpanded) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                                                .padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "المشرف: ${batch.supervisorOrEngineer} | الوردية: ${batch.shift}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF334155)
                                            )

                                            if (batch.notes.isNotBlank()) {
                                                Text(
                                                    text = "ملاحظات: ${batch.notes}",
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF64748B)
                                                )
                                            }

                                            HorizontalDivider(color = Color(0xFFE2E8F0))

                                            scaledIngs.forEachIndexed { i, ing ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text("${i + 1}. ${ing.name} (${ing.type})", fontSize = 11.sp, color = Color(0xFF1E293B))
                                                    Text("${DecimalFormat("#,##0.00").format(ing.weight)} كجم", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        IconButton(
                                            onClick = { batchToDelete = batch },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "حذف التشغيلة",
                                                tint = Color(0xFFEF4444),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "أوامر الإنتاج" -> {
                // Tab 2: Production Orders (أوامر الإنتاج)
                item {
                    val machineNames = listOf("بدون ماكينة") + factoryMachines.map { it.name }

                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "أمر إنتاج وتعبئة بوهية جديدة",
                                style = MaterialTheme.typography.titleMedium,
                                color = PmpCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Presets Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = prodPreset == "250",
                                    onClick = {
                                        prodPreset = "250"
                                        prodWeight = "250"
                                        prodCansPerCarton = "48"
                                        val c = prodCartonsCount.toDoubleOrNull() ?: 1.0
                                        prodCansCount = (c * 48).toInt().toString()
                                    },
                                    label = { Text("250 جم (48 علبة/كرتونة)", fontSize = 12.sp) }
                                )
                                FilterChip(
                                    selected = prodPreset == "900",
                                    onClick = {
                                        prodPreset = "900"
                                        prodWeight = "900"
                                        prodCansPerCarton = "12"
                                        val c = prodCartonsCount.toDoubleOrNull() ?: 1.0
                                        prodCansCount = (c * 12).toInt().toString()
                                    },
                                    label = { Text("900 جم (12 علبة/كرتونة)", fontSize = 12.sp) }
                                )
                                FilterChip(
                                    selected = prodPreset == "CUSTOM",
                                    onClick = { prodPreset = "CUSTOM" },
                                    label = { Text("⚙ تعديل مخصص للوزن وعدد العلب", fontSize = 11.5.sp) }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Weight and Cans per carton (editable!)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassTextField(
                                    value = prodWeight,
                                    onValueChange = {
                                        prodWeight = it
                                        prodPreset = "CUSTOM"
                                    },
                                    label = "وزن العبوة (جم) *",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = prodCansPerCarton,
                                    onValueChange = {
                                        prodCansPerCarton = it
                                        prodPreset = "CUSTOM"
                                        val ratio = it.toDoubleOrNull() ?: 1.0
                                        val cartons = prodCartonsCount.toDoubleOrNull() ?: 1.0
                                        prodCansCount = (cartons * ratio).toInt().toString()
                                    },
                                    label = "عدد العلب في الكرتونة *",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Cans and Cartons (interactive auto-calculation!)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassTextField(
                                    value = prodCartonsCount,
                                    onValueChange = {
                                        prodCartonsCount = it
                                        val c = it.toDoubleOrNull() ?: 0.0
                                        val ratio = prodCansPerCarton.toDoubleOrNull() ?: 12.0
                                        prodCansCount = (c * ratio).toInt().toString()
                                    },
                                    label = "عدد الكراتين *",
                                    placeholder = "مثال: 10",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = prodCansCount,
                                    onValueChange = {
                                        prodCansCount = it
                                        val cans = it.toDoubleOrNull() ?: 0.0
                                        val ratio = prodCansPerCarton.toDoubleOrNull() ?: 12.0
                                        if (ratio > 0) {
                                            prodCartonsCount = String.format(Locale.US, "%.1f", cans / ratio)
                                        }
                                    },
                                    label = "عدد العلب الإجمالي *",
                                    placeholder = "مثال: 120",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassDropdown(
                                    label = "خط الإنتاج / الماكينة",
                                    options = machineNames,
                                    selectedOption = prodMachineName,
                                    onOptionSelected = { prodMachineName = it },
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = prodBatch,
                                    onValueChange = { prodBatch = it },
                                    label = "رقم الباتشة / التشغيلة",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            GlassTextField(
                                value = prodNote,
                                onValueChange = { prodNote = it },
                                label = "ملاحظات أمر الإنتاج والتعبئة",
                                placeholder = "مثال: إنتاج عبوة $prodWeight جم ممتاز"
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            GlassButton(
                                text = "تسجيل الإنتاج (${prodCartonsCount} كرتونة)",
                                icon = Icons.Default.PrecisionManufacturing,
                                gradient = GlassAccentBlueGradient,
                                onClick = {
                                    val cans = prodCansCount.toDoubleOrNull() ?: 120.0
                                    val matchedMachine = factoryMachines.find { it.name == prodMachineName }
                                    val defaultItem = factoryItems.firstOrNull { it.name.contains(prodWeight) } ?: factoryItems.firstOrNull()
                                    val itemId = defaultItem?.id ?: 1L
                                    val itemName = "بوهية تام الصنع $prodWeight جم ($prodCansPerCarton علبة/كرتونة)"

                                    viewModel.addFactoryMove(
                                        kind = "PRODUCTION_OUTPUT",
                                        itemId = itemId,
                                        itemName = itemName,
                                        qty = cans,
                                        machineId = matchedMachine?.id,
                                        machineName = matchedMachine?.name,
                                        batch = prodBatch,
                                        note = if (prodNote.isBlank()) "إنتاج $prodCartonsCount كرتونة ($cans علبة عبوة $prodWeight جم)" else "$prodNote ($prodCartonsCount كرتونة)",
                                        actor = "مهندس الإنتاج"
                                    )
                                    successRecord = FactorySuccessRecord(
                                        title = "تم تسجيل أمر الإنتاج وإضافة المنتج للمخزن",
                                        subtitle = "تم تسجيل دفعة الإنتاج بنجاح وتوجيهها إلى مخزن المنتجات التامة بالمصنع.",
                                        itemName = itemName,
                                        quantity = "$prodCartonsCount كرتونة ($cans علبة)",
                                        destinationWarehouse = "مخزن المنتجات تامة الصنع",
                                        responsibleDepartment = "قسم الإنتاج والتعبئة (مهندس الإنتاج)",
                                        counterparty = "ماكينة: ${matchedMachine?.name ?: prodMachineName} • تشغيلة: $prodBatch",
                                        note = prodNote.ifBlank { null }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            "خروج المنتج للعملاء" -> {
                // Tab 3: Finished Product Dispatch to Customers & Halfa (خروج المنتج للعملاء)
                item {
                    val productNames = products.map { it.name }.ifEmpty { listOf("بوهية أبيض 900 جم", "بوهية كريمي 900 جم", "بوهية أزرق 250 جم") }

                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "خروج المنتج من المصنع إلى العملاء / الترحيل",
                                style = MaterialTheme.typography.titleMedium,
                                color = PmpGold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "تسجيل تسليم وخروج بوهيات تامة الصنع مباشرة إلى العملاء أو ترحيلها إلى مخزن حلفا.",
                                color = TextMuted,
                                fontSize = 12.sp
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = dispatchType == "CUSTOMER",
                                    onClick = { dispatchType = "CUSTOMER" },
                                    label = { Text("خروج وتسليم لعميل", fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = dispatchType == "HALFA",
                                    onClick = { 
                                        dispatchType = "HALFA"
                                        dispatchCurrency = "EGP"
                                    },
                                    label = { Text("ترحيل لمخزن حلفا (EGP)", fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (dispatchType == "CUSTOMER") {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "تحديد عملة البيع وخروج المنتج *:",
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = dispatchCurrency == "SDG",
                                        onClick = { dispatchCurrency = "SDG" },
                                        label = { Text("الجنيه السوداني (SDG)", fontSize = 12.sp) },
                                        leadingIcon = {
                                            if (dispatchCurrency == "SDG") {
                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = dispatchCurrency == "EGP",
                                        onClick = { dispatchCurrency = "EGP" },
                                        label = { Text("الجنيه المصري (EGP)", fontSize = 12.sp) },
                                        leadingIcon = {
                                            if (dispatchCurrency == "EGP") {
                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            GlassDropdown(
                                label = "الصنف المراد خروجه *",
                                options = productNames,
                                selectedOption = dispatchProductText,
                                onOptionSelected = { dispatchProductText = it }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            val effectiveDispatchCurrency = if (dispatchType == "HALFA") "EGP" else dispatchCurrency

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassTextField(
                                    value = dispatchCartons,
                                    onValueChange = { dispatchCartons = it },
                                    label = "عدد الكراتين *",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = dispatchPrice,
                                    onValueChange = { dispatchPrice = it },
                                    label = "سعر الكرتونة ($effectiveDispatchCurrency) *",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            GlassTextField(
                                value = dispatchCustomerName,
                                onValueChange = { dispatchCustomerName = it },
                                label = if (dispatchType == "HALFA") "المستلم بمخزن حلفا" else "اسم العميل المستلم *",
                                placeholder = if (dispatchType == "HALFA") "مسؤول مخزن حلفا..." else "اكتب اسم العميل..."
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            GlassTextField(
                                value = dispatchNote,
                                onValueChange = { dispatchNote = it },
                                label = "رقم البوليصة / ملاحظات الخروج",
                                placeholder = "مثال: شحن بالسيارة رقم 421"
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            GlassButton(
                                text = if (dispatchType == "HALFA") "ترحيل إلى حلفا" else "تسليم للعميل",
                                icon = Icons.Default.LocalShipping,
                                gradient = if (dispatchType == "HALFA") GlassAccentGoldGradient else PmpBrandRedGradient,
                                onClick = {
                                    val cartons = dispatchCartons.toDoubleOrNull() ?: 1.0
                                    val unitPrice = dispatchPrice.toDoubleOrNull() ?: 0.0
                                    val matchedProd = products.find { it.name == dispatchProductText } ?: products.firstOrNull()
                                    val prodId = matchedProd?.id ?: 1L
                                    val defaultItem = factoryItems.firstOrNull()
                                    val itemId = defaultItem?.id ?: 1L

                                    if (dispatchType == "HALFA") {
                                        viewModel.dispatchHalfa(
                                            itemId = itemId,
                                            itemName = dispatchProductText,
                                            qty = cartons,
                                            productId = prodId,
                                            productName = dispatchProductText,
                                            cartons = cartons,
                                            unitPrice = unitPrice,
                                            note = dispatchNote,
                                            actor = "مسؤول التحميل"
                                        )
                                        successRecord = FactorySuccessRecord(
                                            title = "تم تسجيل ترحيل المنتج إلى مخزن حلفا",
                                            subtitle = "تم إرسال الشحنة بنجاح وبانتظار الاستلام الفعلي في مخزن حلفا.",
                                            itemName = dispatchProductText,
                                            quantity = "$cartons كرتونة",
                                            destinationWarehouse = "مخزن حلفا الإقليمي",
                                            responsibleDepartment = "قسم التحميل والترحيل",
                                            counterparty = "مخزن حلفا",
                                            note = dispatchNote.ifBlank { null }
                                        )
                                    } else {
                                        viewModel.addFactoryMove(
                                            kind = "DISPATCH_CUSTOMER",
                                            itemId = itemId,
                                            itemName = dispatchProductText,
                                            qty = cartons,
                                            note = if (dispatchNote.isBlank()) "تسليم وخروج $cartons كرتونة للعميل" else "$dispatchNote ($cartons كرتونة)",
                                            counterparty = dispatchCustomerName.ifBlank { "عميل مباشر" },
                                            actor = "مسؤول التحميل والتسليم"
                                        )
                                        successRecord = FactorySuccessRecord(
                                            title = "تم تسجيل خروج المنتج وتسليمه للعميل",
                                            subtitle = "تم تسليم المنتج بنجاح وتحديث حركة خروج المصنع.",
                                            itemName = dispatchProductText,
                                            quantity = "$cartons كرتونة",
                                            destinationWarehouse = "العميل المباشر",
                                            responsibleDepartment = "قسم التحميل والتسليم",
                                            counterparty = dispatchCustomerName.ifBlank { "عميل مباشر" },
                                            financialAmount = if (dispatchPrice.isNotBlank()) "$dispatchPrice $dispatchCurrency" else null,
                                            note = dispatchNote.ifBlank { null }
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            "سجل دورة المصنع" -> {
                // Tab 4: Factory Moves Log (سجل دورة المصنع)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "سجل دورة وحركات المصنع (${factoryMoves.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        if (factoryMoves.size > 5) {
                            TextButton(onClick = { showAllFactoryMoves = !showAllFactoryMoves }) {
                                Text(
                                    text = if (showAllFactoryMoves) "عرض أقل" else "عرض المزيد (${factoryMoves.size - 5})",
                                    color = PmpCyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (factoryMoves.isEmpty()) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.padding(28.dp), contentAlignment = Alignment.Center) {
                                Text(text = "لا توجد حركات مسجلة في دورة المصنع حتى الآن.", color = TextMuted, fontSize = 13.sp)
                            }
                        }
                    }
                } else {
                    val displayedMoves = if (showAllFactoryMoves) factoryMoves else factoryMoves.take(5)
                    items(displayedMoves, key = { "fm_${it.id}" }) { fm ->
                        val kindDisplay = when (fm.kind) {
                            "RAW_PURCHASE" -> "شراء خامات خارجية"
                            "RAW_RECEIPT" -> "استلام خامات بالمخزن"
                            "ISSUE_PRODUCTION" -> "صرف خامات للإنتاج"
                            "PRODUCTION_OUTPUT" -> "إنتاج وتعبئة بوهية"
                            "DISPATCH_CUSTOMER" -> "خروج بضاعة لعميل"
                            "DISPATCH_HALFA" -> "ترحيل لمخزن حلفا"
                            "CAN_WASTE" -> "هالك علب"
                            "MACHINE_USE" -> "تشغيل ماكينة"
                            else -> fm.kind
                        }
                        val kindColor = when (fm.kind) {
                            "RAW_PURCHASE", "DISPATCH_HALFA" -> PmpGold
                            "RAW_RECEIPT", "PRODUCTION_OUTPUT" -> PmpGreen
                            "ISSUE_PRODUCTION", "DISPATCH_CUSTOMER" -> PmpCyan
                            else -> PmpBrandRed
                        }

                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        GlassBadge(text = kindDisplay, color = kindColor)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = fm.itemName,
                                            color = TextWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "الكمية: ${format.format(fm.qty)}" + if (fm.counterparty.isNotBlank()) " • الجهة: ${fm.counterparty}" else "",
                                        color = TextMuted,
                                        fontSize = 12.sp
                                    )
                                    if (fm.note.isNotBlank()) {
                                        Text(text = fm.note, color = TextSubtle, fontSize = 11.sp)
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(text = fm.moveDate, color = TextMuted, fontSize = 11.sp)
                                    if (fm.actor.isNotBlank()) {
                                        Text(text = fm.actor, color = PmpCyan, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }

                    if (factoryMoves.size > 5) {
                        item {
                            OutlinedButton(
                                onClick = { showAllFactoryMoves = !showAllFactoryMoves },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PmpCyan)
                            ) {
                                Text(
                                    text = if (showAllFactoryMoves) "عرض أقل (العودة لـ 5)" else "عرض المزيد (${factoryMoves.size - 5} حركات إضافية)",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal: Add Factory Item
    if (showAddItemDialog) {
        val kindChoices = listOf("خامات إنتاج", "مواد تعبئة وتغليف", "علب فارغة", "براميل", "كراتين فارغة", "منتج تام الصنع")
        val unitChoices = listOf("كجم", "علبة", "كرتونة", "برميل", "لتر", "طن")

        AlertDialog(
            onDismissRequest = { showAddItemDialog = false },
            title = {
                Text(
                    text = "إضافة عنصر / خامة جديدة للمصنع",
                    color = PmpCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassTextField(
                        value = newItemName,
                        onValueChange = { newItemName = it },
                        label = "اسم الصنف أو الخامة *"
                    )

                    GlassDropdown(
                        label = "تصنيف الخامة أو العنصر",
                        options = kindChoices,
                        selectedOption = newItemKind,
                        onOptionSelected = { newItemKind = it }
                    )

                    GlassDropdown(
                        label = "وحدة القياس",
                        options = unitChoices,
                        selectedOption = newItemUnit,
                        onOptionSelected = { newItemUnit = it }
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "حفظ الصنف",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        val cleanName = newItemName.trim()
                        if (cleanName.isNotBlank()) {
                            viewModel.addFactoryItem(
                                name = cleanName,
                                category = newItemKind,
                                unit = newItemUnit,
                                onSuccess = {
                                    showAddItemDialog = false
                                }
                            )
                        } else {
                            viewModel.postMessage("يرجى كتابة اسم الصنف أولاً", isError = true)
                        }
                    }
                )
            },
            dismissButton = {
                GlassButton(
                    text = "إلغاء",
                    onClick = { showAddItemDialog = false }
                )
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Confirm Delete Factory Item
    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = {
                Text(
                    text = "تأكيد حذف الصنف من المصنع",
                    color = PmpRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "هل أنت متأكد من حذف الصنف (${item.name}) من عناصر وخامات المصنع؟ لن تتأثر الحركات السابقة المسجلة.",
                    color = TextWhite,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                GlassButton(
                    text = "تأكيد الحذف",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        viewModel.deleteFactoryItem(item)
                        itemToDelete = null
                    }
                )
            },
            dismissButton = {
                GlassButton(
                    text = "إلغاء",
                    onClick = { itemToDelete = null }
                )
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Daily Production Batch (Firestore Sync)
    if (showBatchModal) {
        DailyProductionBatchModal(
            availableFormulations = formulations,
            factoryMachines = factoryMachines,
            preSelectedFormula = selectedFormulaForBatch,
            onDismiss = {
                showBatchModal = false
                selectedFormulaForBatch = null
            },
            onSaveBatch = { batch ->
                viewModel.logProductionBatch(batch)
                showBatchModal = false
                selectedFormulaForBatch = null
            }
        )
    }

    // Modal: Confirm Delete Production Batch
    batchToDelete?.let { batch ->
        AlertDialog(
            onDismissRequest = { batchToDelete = null },
            title = {
                Text(
                    text = "تأكيد حذف تشغيلة الإنتاج",
                    color = PmpRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            },
            text = {
                Text(
                    text = "هل أنت متأكد من حذف تشغيلة الإنتاج رقم (${batch.batchNumber}) للمنتج [${batch.productName}] من السجل وقاعدة بيانات Firestore السحابية نهائياً؟",
                    fontSize = 13.sp,
                    color = Color(0xFF0F172A)
                )
            },
            confirmButton = {
                GlassButton(
                    text = "تأكيد الحذف",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        viewModel.deleteProductionBatch(batch)
                        batchToDelete = null
                    }
                )
            },
            dismissButton = {
                GlassButton(
                    text = "إلغاء",
                    onClick = { batchToDelete = null }
                )
            },
            containerColor = PmpNavySurface
        )
    }

    // Success Dialog for Factory Operations (Maintains employee role boundaries)
    successRecord?.let { record ->
        FactoryOperationSuccessDialog(
            record = record,
            onDismiss = { successRecord = null }
        )
    }
}

@Composable
fun FactoryOperationSuccessDialog(
    record: FactorySuccessRecord,
    onDismiss: () -> Unit
) {
    val timeFormatted = remember(record.timestamp) {
        SimpleDateFormat("yyyy/MM/dd - hh:mm a", Locale.getDefault()).format(Date(record.timestamp))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Shiny checkmark icon badge
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color(0xFF10B981).copy(alpha = 0.15f), shape = RoundedCornerShape(32.dp))
                        .border(BorderStroke(2.dp, Color(0xFF10B981).copy(alpha = 0.5f)), shape = RoundedCornerShape(32.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "نجاح العملية",
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(38.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = record.title,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = record.subtitle,
                    color = TextMuted,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Details Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF1E293B).copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Designated Warehouse
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Storefront, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("المخزن المخصص:", color = TextMuted, fontSize = 12.sp)
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = PmpCyan.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, PmpCyan.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = record.destinationWarehouse,
                                    color = PmpCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.6f), thickness = 0.5.dp)

                        // Item & Quantity
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("الصنف / المادة:", color = TextMuted, fontSize = 12.sp)
                            Text(
                                text = record.itemName,
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("الكمية المسجلة:", color = TextMuted, fontSize = 12.sp)
                            Text(
                                text = record.quantity,
                                color = Color(0xFF34D399),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        // Responsible Department
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("القسم والموظف:", color = TextMuted, fontSize = 12.sp)
                            Text(
                                text = record.responsibleDepartment,
                                color = TextWhite,
                                fontSize = 12.sp
                            )
                        }

                        // Counterparty
                        record.counterparty?.let { cp ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("الجهة / التفاصيل:", color = TextMuted, fontSize = 12.sp)
                                Text(
                                    text = cp,
                                    color = TextWhite,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Financial amount
                        record.financialAmount?.let { amt ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("المبلغ المالي:", color = TextMuted, fontSize = 12.sp)
                                Text(
                                    text = amt,
                                    color = PmpGold,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Notes
                        record.note?.let { note ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("ملاحظات:", color = TextMuted, fontSize = 12.sp)
                                Text(
                                    text = note,
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        // Time
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("وقت العملية:", color = TextMuted, fontSize = 11.sp)
                            Text(
                                text = timeFormatted,
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Role separation explanation badge
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0284C7).copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.AdminPanelSettings,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "نظام استقلالية الأقسام: تم الإبقاء على شاشتك الحالية ليقوم موظف المخزن المخصص بإدخال واستلام بياناته يدوياً.",
                            color = Color(0xFFE0F2FE),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            GlassButton(
                text = "موافق (إضافة عملية أخرى)",
                icon = Icons.Default.Check,
                gradient = GlassAccentGreenGradient,
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("factory_success_confirm_button")
            )
        },
        containerColor = PmpNavySurface
    )
}
