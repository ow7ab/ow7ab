package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import com.example.data.local.ProductEntity
import com.example.data.local.WarehouseEntity
import com.example.data.local.getEffectiveSalePrice
import com.example.data.local.resolveCurrency
import com.example.data.local.resolveLinkedCashbox
import com.example.hasModulePermission
import com.example.isSystemManager
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import com.example.util.InventoryWeightManager
import com.example.util.PackageWeightSpec
import java.text.NumberFormat
import java.util.Locale

@Composable
fun InventoryScreen(
    viewModel: PmpViewModel,
    modifier: Modifier = Modifier
) {
    val warehouses by viewModel.warehouses.collectAsState()
    val products by viewModel.products.collectAsState()
    val stockMoves by viewModel.stockMoves.collectAsState()
    val stockBalances by viewModel.productStockBalances.collectAsState()
    val availablePaintColors by viewModel.availablePaintColors.collectAsState()
    val packageWeights by viewModel.packageWeights.collectAsState()
    val activePackageWeights by viewModel.activePackageWeights.collectAsState()
    val selectedInventoryWeight by viewModel.selectedInventoryWeight.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val isInitialDataLoading by viewModel.isInitialDataLoading.collectAsState()
    val dataLoadError by viewModel.dataLoadError.collectAsState()
    val isManager = currentUser.isSystemManager()
    val canDeleteOrZero = remember(currentUser) {
        currentUser.isSystemManager() || currentUser.hasModulePermission("صلاحية الحذف والتصفير")
    }

    // Branch / Warehouse Access Restriction
    val allowedWarehouses = remember(warehouses, currentUser, isManager) {
        if (isManager || currentUser?.branch.isNullOrBlank() || currentUser?.branch?.contains("كل الفروع") == true) {
            warehouses
        } else {
            val userBranch = currentUser?.branch.orEmpty()
            val filtered = warehouses.filter { wh ->
                userBranch.contains(wh.name, ignoreCase = true) ||
                wh.name.contains(userBranch.substringBefore(" ("), ignoreCase = true)
            }
            filtered.ifEmpty { warehouses }
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val format = remember { NumberFormat.getNumberInstance(Locale.US) }

    // Search query & Display currency (EGP for Egypt/Factory, SDG for Sudan/Halfa)
    var searchQuery by remember { mutableStateOf("") }
    var displayCurrency by remember { mutableStateOf("SDG") }
    var selectedWarehouseFilter by remember { mutableStateOf("كل المخازن") }

    // Tab 1: Intake & Add Product State
    var intakeColor by remember { mutableStateOf("أبيض") }
    var intakeWeight by remember { mutableStateOf("900") }
    var intakeCartons by remember { mutableStateOf("10") }
    var intakeWarehouse by remember(allowedWarehouses) {
        mutableStateOf(allowedWarehouses.firstOrNull()?.name ?: warehouses.firstOrNull()?.name ?: "مخزن المصنع الرئيسي")
    }
    var intakePurchasePrice by remember { mutableStateOf("320000") }
    var intakeSalePrice by remember { mutableStateOf("420000") }
    var intakeNote by remember { mutableStateOf("توريد إنتاج جديد") }

    // Tab 2: Add/Edit/Delete Warehouse & Add Product & Add Color State
    var showAddWarehouseDialog by remember { mutableStateOf(false) }
    var newWarehouseName by remember { mutableStateOf("") }
    var editingWarehouse by remember { mutableStateOf<WarehouseEntity?>(null) }
    var editWarehouseName by remember { mutableStateOf("") }
    var editWarehouseActive by remember { mutableStateOf(true) }
    var deletingWarehouse by remember { mutableStateOf<WarehouseEntity?>(null) }
    var warehouseToResetStock by remember { mutableStateOf<WarehouseEntity?>(null) }
    var productToDelete by remember { mutableStateOf<ProductEntity?>(null) }

    var showAddColorDialog by remember { mutableStateOf(false) }
    var newCustomColorName by remember { mutableStateOf("") }

    var showAddProductDialog by remember { mutableStateOf(false) }
    var newProdName by remember { mutableStateOf("") }
    var newProdColor by remember { mutableStateOf("أبيض") }
    var newProdWeight by remember { mutableStateOf("900") }
    var newProdPurchase by remember { mutableStateOf("") }
    var newProdSale by remember { mutableStateOf("") }

    // Tab 3: Stock Moves State
    var showAllStockMoves by remember { mutableStateOf(false) }

    // Weight Selector & Weight Management States (Tab 0)
    var weightDropdownExpanded by remember { mutableStateOf(false) }
    var showManageWeightsDialog by remember { mutableStateOf(false) }
    var newWeightInput by remember { mutableStateOf("") }
    var newWeightSetDefault by remember { mutableStateOf(false) }
    var editingWeightSpec by remember { mutableStateOf<PackageWeightSpec?>(null) }
    var editingWeightNewValue by remember { mutableStateOf("") }

    // Per-(Color, Weight) Stock Adjustment Dialog State
    var adjustingColorRow by remember { mutableStateOf<String?>(null) }
    var adjustOperationMode by remember { mutableStateOf("IN") } // "IN", "OUT", "SET"
    var adjustQtyInput by remember { mutableStateOf("") }
    var adjustNoteInput by remember { mutableStateOf("") }
    var adjustWarehouseName by remember(allowedWarehouses, selectedWarehouseFilter) {
        val initialWh = if (selectedWarehouseFilter != "كل المخازن") {
            selectedWarehouseFilter
        } else {
            allowedWarehouses.firstOrNull()?.name ?: warehouses.firstOrNull()?.name ?: "مخزن المصنع الرئيسي"
        }
        mutableStateOf(initialWh)
    }

    val tabs = listOf("أرصدة المخزون", "إضافة صنف وتوريد للمخزن", "الأصناف والمخازن", "سجل الحركات")

    val filteredProducts = if (searchQuery.isBlank()) products else {
        products.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.paintColor.contains(searchQuery, ignoreCase = true) ||
            it.packageWeight.contains(searchQuery, ignoreCase = true) ||
            it.sku.contains(searchQuery, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 24.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "المخازن والمنتجات وإدارة البضاعة",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
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

        if (isInitialDataLoading && products.isEmpty() && warehouses.isEmpty()) {
            item(key = "inventory_shimmer_skeleton") {
                ProvideShimmerEffect(enabled = true) {
                    InventorySkeletonSection()
                }
            }
        } else if (dataLoadError != null && products.isEmpty() && warehouses.isEmpty()) {
            item(key = "inventory_error_card") {
                PmpErrorStateCard(
                    message = dataLoadError ?: "تعذر تحميل بيانات المخازن",
                    onRetry = { viewModel.retryDataLoadAndSync() }
                )
            }
        } else when (selectedTab) {
            0 -> {
                // Warehouse filter & Effective stock calculations
                val selectedWh = warehouses.find { it.name == selectedWarehouseFilter }
                val effectiveBalances = if (selectedWh != null) {
                    val whMoves = stockMoves.filter { it.warehouseId == selectedWh.id }
                    products.associate { prod ->
                        val prodMoves = whMoves.filter { it.productId == prod.id }
                        val inQty = prodMoves.filter { it.kind in listOf("IN", "RETURN", "وارد", "مرتجع") }.sumOf { it.quantity }
                        val outQty = prodMoves.filter { it.kind in listOf("OUT", "SALE", "صرف", "مبيعات") }.sumOf { it.quantity }
                        prod.id to (inQty - outQty).coerceAtLeast(0.0)
                    }
                } else {
                    stockBalances
                }

                val totalCartons = products.sumOf { effectiveBalances[it.id] ?: 0.0 }
                val totalStockValue = products.sumOf { (effectiveBalances[it.id] ?: 0.0) * it.getEffectiveSalePrice(displayCurrency) }

                // Warehouse Selector & Currency Switcher Bar
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "تصفية المخزن وعملة التسعير:",
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val filterOptions = listOf("كل المخازن") + warehouses.map { it.name }
                            GlassDropdown(
                                label = "اختر المخزن المستهدف",
                                options = filterOptions,
                                selectedOption = selectedWarehouseFilter,
                                onOptionSelected = { option ->
                                    selectedWarehouseFilter = option
                                    val matched = warehouses.find { it.name == option }
                                    if (matched != null) {
                                        displayCurrency = matched.resolveCurrency()
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = displayCurrency == "SDG",
                                    onClick = { displayCurrency = "SDG" },
                                    label = { Text("🇸🇩 عرض بالجنيه السوداني (SDG)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = PmpBrandRed,
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = displayCurrency == "EGP",
                                    onClick = { displayCurrency = "EGP" },
                                    label = { Text("🇪🇬 عرض بالجنيه المصري (EGP)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF1E40AF),
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassStatCard(
                            title = "إجمالي عدد الكراتين",
                            value = format.format(totalCartons),
                            unit = "كرتونة",
                            subtitle = if (selectedWarehouseFilter == "كل المخازن") "رصيد كافة المخازن" else "رصيد $selectedWarehouseFilter",
                            accentColor = PmpCyan,
                            icon = Icons.Default.Inventory2,
                            modifier = Modifier.weight(1f)
                        )
                        GlassStatCard(
                            title = "تقييم المخزون المالي",
                            value = format.format(totalStockValue),
                            unit = displayCurrency,
                            subtitle = "التسعير المعتمد بالـ $displayCurrency",
                            accentColor = PmpGold,
                            icon = Icons.Default.AccountBalanceWallet,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    GlassTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = "",
                        placeholder = "بحث باللون، وزن العبوة، اسم الصنف، أو الكود...",
                        leadingIcon = Icons.Default.Search,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val selectedWeightNorm = InventoryWeightManager.normalizeWeightValue(selectedInventoryWeight)
                    .ifBlank { "250" }
                val selectedWeightLabel = InventoryWeightManager.formatWeightLabel(selectedWeightNorm)

                // Build unified color list from catalog + products so every registered color appears for the selected weight
                val combinedColors = (availablePaintColors + products.map { it.paintColor.trim() })
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                val allRegisteredColors = if (searchQuery.isBlank()) {
                    combinedColors
                } else {
                    combinedColors.filter { color ->
                        color.contains(searchQuery, ignoreCase = true) ||
                            selectedWeightLabel.contains(searchQuery, ignoreCase = true)
                    }
                }

                // Calculate independent balance for each color at the currently selected weight ONLY
                val weightSpecificRows = allRegisteredColors.map { colorName ->
                    val matchingProducts = products.filter { prod ->
                        prod.paintColor.trim().equals(colorName, ignoreCase = true) &&
                            InventoryWeightManager.normalizeWeightValue(prod.packageWeight) == selectedWeightNorm
                    }
                    val qtyForSelectedWeight = matchingProducts.sumOf { prod ->
                        effectiveBalances[prod.id] ?: 0.0
                    }
                    Triple(colorName, selectedWeightLabel, qtyForSelectedWeight)
                }

                val selectedWeightTotalCount = weightSpecificRows.sumOf { it.third }

                // 1. Weight Selector Dropdown Above the Inventory Table (الوزن: 250 جم ▼)
                item(key = "inventory_weight_selector_section") {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = PmpNavySurface
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box {
                                        Surface(
                                            modifier = Modifier
                                                .testTag("inventory_weight_selector_button")
                                                .clickable { weightDropdownExpanded = true },
                                            shape = RoundedCornerShape(12.dp),
                                            color = PmpCyan.copy(alpha = 0.14f),
                                            border = BorderStroke(1.5.dp, PmpCyan)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Scale,
                                                    contentDescription = "اختيار الوزن",
                                                    tint = PmpCyan,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = "الوزن: $selectedWeightLabel ▼",
                                                    color = TextWhite,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 15.sp
                                                )
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = weightDropdownExpanded,
                                            onDismissRequest = { weightDropdownExpanded = false },
                                            modifier = Modifier
                                                .background(PmpNavySurface)
                                                .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                                        ) {
                                            val dropdownWeights = activePackageWeights.ifEmpty { packageWeights }
                                            dropdownWeights.forEach { spec ->
                                                val isSelected = spec.value == selectedWeightNorm
                                                DropdownMenuItem(
                                                    text = {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = spec.displayLabel(),
                                                                color = if (isSelected) PmpCyan else TextWhite,
                                                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                                                fontSize = 14.sp
                                                            )
                                                            if (spec.isDefault) {
                                                                Text(
                                                                    text = "افتراضي",
                                                                    color = PmpGold,
                                                                    fontSize = 11.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    modifier = Modifier.padding(start = 8.dp)
                                                                )
                                                            }
                                                        }
                                                    },
                                                    onClick = {
                                                        viewModel.selectInventoryWeight(spec.value)
                                                        weightDropdownExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "إجمالي عبوات ($selectedWeightLabel)",
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = "${format.format(selectedWeightTotalCount.toInt())} عبوة",
                                            color = PmpGold,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "اضغط على أي صف في الجدول لتوريد أو صرف أو تعديل رصيد اللون في وزن ($selectedWeightLabel) بشكل مستقل.",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // 2. Three-Column RTL Inventory Table (اللون | الوزن | العدد)
                item(key = "inventory_three_column_table_card") {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        val tableBorderColor = PmpCyan.copy(alpha = 0.35f)
                        val rowDividerColor = GlassBorder

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("inventory_three_column_table"),
                            shape = RoundedCornerShape(16.dp),
                            color = PmpNavySurface,
                            border = BorderStroke(1.5.dp, tableBorderColor)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // Table Header Row: اللون | الوزن | العدد (RTL: Right to Left)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(IntrinsicSize.Min)
                                        .background(PmpCyan.copy(alpha = 0.16f)),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "اللون",
                                        color = PmpCyan,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .weight(1.15f)
                                            .padding(vertical = 12.dp, horizontal = 8.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(tableBorderColor)
                                    )
                                    Text(
                                        text = "الوزن",
                                        color = PmpCyan,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .weight(1.0f)
                                            .padding(vertical = 12.dp, horizontal = 8.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(tableBorderColor)
                                    )
                                    Text(
                                        text = "العدد",
                                        color = PmpCyan,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .weight(0.95f)
                                            .padding(vertical = 12.dp, horizontal = 8.dp)
                                    )
                                }

                                HorizontalDivider(thickness = 1.5.dp, color = tableBorderColor)

                                if (weightSpecificRows.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(28.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (searchQuery.isBlank()) "لا توجد ألوان مسجلة في المخزن" else "لا توجد نتائج مطابقة للبحث",
                                            color = TextMuted,
                                            fontSize = 13.sp
                                        )
                                    }
                                } else {
                                    weightSpecificRows.forEachIndexed { index, (colorName, weightLabel, countValue) ->
                                        val intCount = countValue.toInt()
                                        val isPositive = countValue > 0.0
                                        val rowBg = if (index % 2 == 0) Color.Transparent else Color.White.copy(alpha = 0.03f)

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(IntrinsicSize.Min)
                                                .background(rowBg)
                                                .clickable {
                                                    adjustingColorRow = colorName
                                                    adjustOperationMode = "IN"
                                                    adjustQtyInput = ""
                                                    adjustNoteInput = ""
                                                },
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Column 1 (Right in RTL): اللون
                                            Text(
                                                text = colorName,
                                                color = TextWhite,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier
                                                    .weight(1.15f)
                                                    .padding(vertical = 13.dp, horizontal = 8.dp)
                                            )

                                            Box(
                                                modifier = Modifier
                                                    .width(1.dp)
                                                    .fillMaxHeight()
                                                    .background(rowDividerColor)
                                            )

                                            // Column 2 (Center in RTL): الوزن
                                            Text(
                                                text = weightLabel,
                                                color = PmpGold,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.5.sp,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier
                                                    .weight(1.0f)
                                                    .padding(vertical = 13.dp, horizontal = 8.dp)
                                            )

                                            Box(
                                                modifier = Modifier
                                                    .width(1.dp)
                                                    .fillMaxHeight()
                                                    .background(rowDividerColor)
                                            )

                                            // Column 3 (Left in RTL): العدد
                                            Row(
                                                modifier = Modifier
                                                    .weight(0.95f)
                                                    .padding(vertical = 13.dp, horizontal = 8.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = format.format(intCount),
                                                    color = if (isPositive) PmpGreenLight else PmpRedLight,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 14.5.sp,
                                                    textAlign = TextAlign.Center
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "تعديل رصيد $colorName",
                                                    tint = TextMuted.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }

                                        if (index < weightSpecificRows.lastIndex) {
                                            HorizontalDivider(thickness = 1.dp, color = rowDividerColor)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Manage Weights Button Directly Below the Table (الأوزان ▼ / إدارة الأوزان)
                item(key = "inventory_manage_weights_button_section") {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("manage_weights_button")
                                    .clickable { showManageWeightsDialog = true },
                                shape = RoundedCornerShape(14.dp),
                                color = PmpNavySurface,
                                border = BorderStroke(1.5.dp, PmpGold)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp, vertical = 13.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tune,
                                            contentDescription = "إدارة الأوزان",
                                            tint = PmpGold,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column {
                                            Text(
                                                text = "الأوزان ▼  (إدارة الأوزان)",
                                                color = PmpGold,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 15.sp
                                            )
                                            Text(
                                                text = "إضافة وزن جديد • تعديل • تفعيل/تعطيل • تعيين الوزن الافتراضي",
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = PmpGold.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "${activePackageWeights.size} وزن نشط",
                                            color = PmpGold,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.5.sp,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                // Unified Intake & Add Product (إضافة صنف وتوريد للمخزن)
                item {
                    val weightsList = remember(activePackageWeights) {
                        val activeVals = activePackageWeights.map { it.value }
                        (activeVals + listOf("250", "900", "4000")).distinct()
                    }
                    val warehouseOptions = warehouses.map { it.name }.ifEmpty { listOf("مخزن المصنع الرئيسي", "مخزن عطبرة", "مخزن حلفا") }

                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "إضافة صنف وتوريد للمخزن",
                                style = MaterialTheme.typography.titleMedium,
                                color = PmpCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassDropdown(
                                    label = "اللون *",
                                    options = availablePaintColors,
                                    selectedOption = intakeColor,
                                    onOptionSelected = { intakeColor = it },
                                    modifier = Modifier.weight(1.2f)
                                )
                                GlassDropdown(
                                    label = "وزن العبوة (جم) *",
                                    options = weightsList,
                                    selectedOption = intakeWeight,
                                    onOptionSelected = { intakeWeight = it },
                                    modifier = Modifier.weight(0.8f)
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showAddColorDialog = true }) {
                                    Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("＋ إضافة لون جديد للكتالوج", color = PmpCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassTextField(
                                    value = intakeCartons,
                                    onValueChange = { intakeCartons = it },
                                    label = "الكمية بالكراتين *",
                                    placeholder = "مثال: 10",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassDropdown(
                                    label = "المخزن المراد الإضافة إليه *",
                                    options = warehouseOptions,
                                    selectedOption = intakeWarehouse,
                                    onOptionSelected = { intakeWarehouse = it },
                                    modifier = Modifier.weight(1.3f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassTextField(
                                    value = intakePurchasePrice,
                                    onValueChange = { intakePurchasePrice = it },
                                    label = "سعر الشراء للكرتونة (EGP) *",
                                    placeholder = "0.00 EGP",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = intakeSalePrice,
                                    onValueChange = { intakeSalePrice = it },
                                    label = "سعر البيع للكرتونة (EGP) *",
                                    placeholder = "0.00 EGP",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            GlassTextField(
                                value = intakeNote,
                                onValueChange = { intakeNote = it },
                                label = "ملاحظات وتفاصيل التوريد (اختياري)",
                                placeholder = "مثال: بضاعة واردة من مستودع مصر / توريد جديد",
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            GlassButton(
                                text = "حفظ",
                                icon = Icons.Default.Save,
                                onClick = {
                                    val qty = intakeCartons.toDoubleOrNull() ?: 1.0
                                    val pPrice = intakePurchasePrice.toDoubleOrNull() ?: 0.0
                                    val sPrice = intakeSalePrice.toDoubleOrNull() ?: 0.0

                                    viewModel.addProductWithIntake(
                                        color = intakeColor,
                                        weight = intakeWeight,
                                        cartons = qty,
                                        warehouseName = intakeWarehouse,
                                        purchasePrice = pPrice,
                                        salePrice = sPrice,
                                        note = intakeNote
                                    )
                                    intakeCartons = "10"
                                    selectedTab = 0
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "تنويه: سيتم حفظ الصنف وتوريد الكراتين مباشرة إلى المخزن المعتمد وتحديث الرصيد المخزني",
                                color = TextMuted,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            2 -> {
                // Manage Catalogs and Warehouses
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "المخازن المسجلة (${warehouses.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold
                        )
                        if (isManager) {
                            GlassButton(
                                text = "＋ مخزن جديد",
                                onClick = { showAddWarehouseDialog = true },
                                modifier = Modifier.height(38.dp)
                            )
                        }
                    }
                }

                items(warehouses, key = { "wh_${it.id}" }) { wh ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = wh.name, color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    GlassBadge(
                                        text = wh.resolveCurrency(),
                                        color = if (wh.resolveCurrency() == "EGP") Color(0xFF1E40AF) else PmpBrandRed
                                    )
                                    GlassBadge(
                                        text = wh.warehouseType,
                                        color = when (wh.warehouseType) {
                                            "مبيعات" -> PmpGreen
                                            "مواد خام" -> PmpGold
                                            else -> PmpCyan
                                        }
                                    )
                                    GlassBadge(
                                        text = wh.resolveLinkedCashbox(),
                                        color = Color(0xFF64748B)
                                    )
                                    GlassBadge(
                                        text = if (wh.active) "نشط" else "موقوف",
                                        color = if (wh.active) PmpGreenLight else PmpRed
                                    )
                                }
                            }
                            if (isManager || canDeleteOrZero) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (canDeleteOrZero) {
                                        IconButton(
                                            onClick = { warehouseToResetStock = wh },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.RestartAlt,
                                                contentDescription = "تصفير رصيد المخزن",
                                                tint = PmpGold,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    if (isManager) {
                                        IconButton(
                                            onClick = {
                                                editingWarehouse = wh
                                                editWarehouseName = wh.name
                                                editWarehouseActive = wh.active
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "تعديل المخزن",
                                                tint = PmpCyan,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { deletingWarehouse = wh },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "حذف المخزن",
                                                tint = PmpRedLight,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "كتالوج المنتجات (${products.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GlassButton(
                                text = "＋ لون جديد",
                                onClick = { showAddColorDialog = true },
                                modifier = Modifier.height(38.dp)
                            )
                            GlassButton(
                                text = "＋ صنف جديد",
                                gradient = GlassAccentGoldGradient,
                                onClick = { showAddProductDialog = true },
                                modifier = Modifier.height(38.dp)
                            )
                        }
                    }
                }

                items(products, key = { "cat_prod_${it.id}" }) { pr ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            ) {
                                Text(text = pr.name, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "اللون: ${pr.paintColor} • العبوة: ${pr.packageWeight} جم • سعر البيع: ${format.format(pr.salePrice)}",
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                GlassBadge(text = pr.unit, color = PmpPurple)
                                if (canDeleteOrZero) {
                                    IconButton(
                                        onClick = { productToDelete = pr },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteForever,
                                            contentDescription = "حذف الصنف",
                                            tint = PmpRed,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            3 -> {
                // Stock Moves History
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "سجل حركات المخزون (${stockMoves.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        if (stockMoves.size > 5) {
                            TextButton(onClick = { showAllStockMoves = !showAllStockMoves }) {
                                Text(
                                    text = if (showAllStockMoves) "عرض أقل" else "عرض المزيد (${stockMoves.size - 5})",
                                    color = PmpCyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                val displayedStockMoves = if (showAllStockMoves) stockMoves else stockMoves.take(5)
                items(displayedStockMoves, key = { "sm_rec_${it.id}" }) { sm ->
                    val kindLabel = when (sm.kind) {
                        "IN" -> "وارد مخزن"
                        "OUT" -> "صرف مخزن"
                        "SALE" -> "مبيعات"
                        "RETURN" -> "مرتجع"
                        else -> sm.kind
                    }
                    val kindColor = when (sm.kind) {
                        "IN", "RETURN" -> PmpGreen
                        else -> PmpRed
                    }

                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            ) {
                                GlassBadge(text = kindLabel, color = kindColor)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = sm.note.ifBlank { "حركة مخزون #${sm.id}" }, color = TextWhite, fontSize = 13.sp, maxLines = 1)
                                    Text(text = "مخزن #${sm.warehouseId} • صنف #${sm.productId}", color = TextSubtle, fontSize = 11.sp, maxLines = 1)
                                }
                            }
                            Text(
                                text = "${format.format(sm.quantity)} كرتونة",
                                color = PmpCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                if (stockMoves.size > 5) {
                    item {
                        OutlinedButton(
                            onClick = { showAllStockMoves = !showAllStockMoves },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PmpCyan)
                        ) {
                            Text(
                                text = if (showAllStockMoves) "عرض أقل (العودة لـ 5)" else "عرض المزيد (${stockMoves.size - 5} حركات إضافية)",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal: Add Warehouse
    if (showAddWarehouseDialog) {
        AlertDialog(
            onDismissRequest = { showAddWarehouseDialog = false },
            title = { Text(text = "إضافة مخزن جديد", color = PmpCyan, fontWeight = FontWeight.Bold) },
            text = {
                GlassTextField(
                    value = newWarehouseName,
                    onValueChange = { newWarehouseName = it },
                    label = "اسم المخزن *"
                )
            },
            confirmButton = {
                GlassButton(
                    text = "حفظ المخزن",
                    onClick = {
                        if (newWarehouseName.isNotBlank()) {
                            viewModel.addWarehouse(newWarehouseName)
                            newWarehouseName = ""
                            showAddWarehouseDialog = false
                        }
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { showAddWarehouseDialog = false })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Edit Warehouse
    editingWarehouse?.let { wh ->
        AlertDialog(
            onDismissRequest = { editingWarehouse = null },
            title = { Text(text = "تعديل بيانات المخزن", color = PmpCyan, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassTextField(
                        value = editWarehouseName,
                        onValueChange = { editWarehouseName = it },
                        label = "اسم المخزن *"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (editWarehouseActive) "حالة المخزن: نشط" else "حالة المخزن: موقوف",
                            color = if (editWarehouseActive) PmpGreen else PmpRedLight,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Switch(
                            checked = editWarehouseActive,
                            onCheckedChange = { editWarehouseActive = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PmpGreen,
                                checkedTrackColor = PmpGreen.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    text = "حفظ التعديلات",
                    onClick = {
                        if (editWarehouseName.isNotBlank()) {
                            viewModel.updateWarehouse(wh.id, editWarehouseName, editWarehouseActive)
                            editingWarehouse = null
                        }
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { editingWarehouse = null })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Delete Warehouse Confirmation
    deletingWarehouse?.let { wh ->
        AlertDialog(
            onDismissRequest = { deletingWarehouse = null },
            title = { Text(text = "حذف المخزن نهائياً", color = PmpRedLight, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "هل أنت متأكد من حذف المخزن (${wh.name}) نهائياً من النظام وسيرفر السحابة؟ لن تتمكن من التراجع عن هذه الخطوة.",
                    color = TextWhite,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                GlassButton(
                    text = "نعم، حذف نهائي",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        viewModel.deleteWarehouse(wh.id, wh.name)
                        deletingWarehouse = null
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { deletingWarehouse = null })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Add Custom Color
    if (showAddColorDialog) {
        AlertDialog(
            onDismissRequest = { showAddColorDialog = false },
            title = { Text(text = "إضافة لون بوهية جديد للكتالوج", color = PmpCyan, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "أدخل اسم اللون الجديد (مثال: كريمي، كبدي، بني، رمادي، ليموني...). سيتم إدراجه فوراً في بوستر الأسعار وكتالوج المخزن.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    GlassTextField(
                        value = newCustomColorName,
                        onValueChange = { newCustomColorName = it },
                        label = "اسم اللون *"
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "حفظ وإدراج اللون",
                    onClick = {
                        if (newCustomColorName.isNotBlank()) {
                            viewModel.addCustomColor(newCustomColorName)
                            intakeColor = newCustomColorName.trim()
                            newCustomColorName = ""
                            showAddColorDialog = false
                        }
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { showAddColorDialog = false })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Add Product
    if (showAddProductDialog) {
        AlertDialog(
            onDismissRequest = { showAddProductDialog = false },
            title = { Text(text = "إضافة صنف جديد للكتالوج", color = PmpGold, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    GlassTextField(
                        value = newProdName,
                        onValueChange = { newProdName = it },
                        label = "اسم الصنف *"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassDropdown(
                            label = "اللون",
                            options = availablePaintColors,
                            selectedOption = newProdColor,
                            onOptionSelected = { newProdColor = it },
                            modifier = Modifier.weight(1f)
                        )
                        GlassDropdown(
                            label = "الوزن",
                            options = (activePackageWeights.map { it.value } + listOf("900", "250", "جالون", "ربع")).distinct(),
                            selectedOption = newProdWeight,
                            onOptionSelected = { newProdWeight = it },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassTextField(
                            value = newProdPurchase,
                            onValueChange = { newProdPurchase = it },
                            label = "سعر الشراء",
                            isNumber = true,
                            modifier = Modifier.weight(1f)
                        )
                        GlassTextField(
                            value = newProdSale,
                            onValueChange = { newProdSale = it },
                            label = "سعر البيع",
                            isNumber = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    text = "حفظ الصنف",
                    onClick = {
                        if (newProdName.isNotBlank()) {
                            val p = newProdPurchase.toDoubleOrNull() ?: 0.0
                            val s = newProdSale.toDoubleOrNull() ?: 0.0
                            viewModel.addProduct(
                                name = newProdName,
                                color = newProdColor,
                                weight = newProdWeight,
                                purchasePrice = p,
                                salePrice = s,
                                minimumStock = 10.0
                            )
                            newProdName = ""
                            newProdPurchase = ""
                            newProdSale = ""
                            showAddProductDialog = false
                        }
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { showAddProductDialog = false })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Confirmation Dialog for Resetting Warehouse Stock
    warehouseToResetStock?.let { wh ->
        AlertDialog(
            onDismissRequest = { warehouseToResetStock = null },
            title = {
                Text(
                    text = "تصفير رصيد المخزن بالكامل",
                    color = PmpBrandRed,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "هل أنت متأكد من تصفير رصيد المخزن (${wh.name}) بالكامل؟ سيتم مسح كافة حركات المخزون وتصفير كراتين البوهيات المسجلة فيه. هذا الإجراء لا يمكن التراجع عنه.",
                    color = TextWhite,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                GlassButton(
                    text = "نعم، تصفير رصيد المخزن",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        viewModel.resetWarehouseStock(wh.id, wh.name)
                        warehouseToResetStock = null
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { warehouseToResetStock = null })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Confirmation Dialog for Deleting Product
    productToDelete?.let { pr ->
        AlertDialog(
            onDismissRequest = { productToDelete = null },
            title = {
                Text(
                    text = "حذف الصنف نهائياً",
                    color = PmpBrandRed,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "هل أنت متأكد من حذف المنتج (${pr.name}) نهائياً من دليل المنتجات وبوستر الأسعار وسيرفر السحابة؟ هذا الإجراء لا يمكن التراجع عنه.",
                    color = TextWhite,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                GlassButton(
                    text = "نعم، حذف الصنف نهائياً",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        viewModel.deleteProduct(pr.id, pr.name)
                        productToDelete = null
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { productToDelete = null })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Adjust Stock for Specific (Color, Weight) Row
    adjustingColorRow?.let { targetColor ->
        val currentWeightNorm = InventoryWeightManager.normalizeWeightValue(selectedInventoryWeight).ifBlank { "250" }
        val currentWeightLabel = InventoryWeightManager.formatWeightLabel(currentWeightNorm)
        val matchingProds = products.filter {
            it.paintColor.trim().equals(targetColor, ignoreCase = true) &&
                InventoryWeightManager.normalizeWeightValue(it.packageWeight) == currentWeightNorm
        }
        val currentQty = matchingProds.sumOf { stockBalances[it.id] ?: 0.0 }.toInt()
        val warehouseChoices = warehouses.map { it.name }.ifEmpty { listOf("مخزن المصنع الرئيسي", "مخزن عطبرة", "مخزن حلفا") }

        AlertDialog(
            onDismissRequest = { adjustingColorRow = null },
            title = {
                Column {
                    Text(
                        text = "تحديث كمية المخزون",
                        color = PmpCyan,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "اللون: $targetColor  |  الوزن: $currentWeightLabel  |  الرصيد الحالي: $currentQty",
                        color = PmpGold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            },
            text = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = adjustOperationMode == "IN",
                                onClick = { adjustOperationMode = "IN" },
                                label = { Text("＋ إضافة (وارد)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PmpGreen,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = adjustOperationMode == "OUT",
                                onClick = { adjustOperationMode = "OUT" },
                                label = { Text("－ صرف (صادر)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PmpBrandRed,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = adjustOperationMode == "SET",
                                onClick = {
                                    adjustOperationMode = "SET"
                                    if (adjustQtyInput.isBlank()) adjustQtyInput = currentQty.toString()
                                },
                                label = { Text("تعيين الرصيد", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF1E40AF),
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        GlassDropdown(
                            label = "المخزن المستهدف",
                            options = warehouseChoices,
                            selectedOption = adjustWarehouseName,
                            onOptionSelected = { adjustWarehouseName = it }
                        )

                        GlassTextField(
                            value = adjustQtyInput,
                            onValueChange = { adjustQtyInput = it },
                            label = when (adjustOperationMode) {
                                "IN" -> "الكمية المراد إضافتها (عدد العبوات/الكراتين) *"
                                "OUT" -> "الكمية المراد صرفها (عدد العبوات/الكراتين) *"
                                else -> "الرصيد الفعلي الجديد لهذا الوزن *"
                            },
                            isNumber = true
                        )

                        GlassTextField(
                            value = adjustNoteInput,
                            onValueChange = { adjustNoteInput = it },
                            label = "ملاحظة الحركة (اختياري)"
                        )
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    text = "حفظ وتحديث الرصيد",
                    gradient = GlassAccentBlueGradient,
                    onClick = {
                        val qty = adjustQtyInput.trim().toDoubleOrNull()
                        if (qty != null) {
                            viewModel.adjustColorWeightStock(
                                color = targetColor,
                                weight = currentWeightNorm,
                                warehouseName = adjustWarehouseName,
                                quantityInput = qty,
                                operationMode = adjustOperationMode,
                                note = adjustNoteInput
                            )
                            adjustingColorRow = null
                        } else {
                            viewModel.postMessage("يرجى إدخال رقم صحيح للكمية", isError = true)
                        }
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { adjustingColorRow = null })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Weight Management Dialog (إدارة الأوزان)
    if (showManageWeightsDialog) {
        AlertDialog(
            onDismissRequest = {
                showManageWeightsDialog = false
                editingWeightSpec = null
            },
            title = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "إدارة الأوزان المسجلة",
                                color = PmpGold,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "إضافة • تعديل • تعطيل/تفعيل • اختيار الوزن الافتراضي",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Scale,
                            contentDescription = null,
                            tint = PmpGold,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            },
            text = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 1. Add New Weight Section
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PmpNavyCard,
                            border = BorderStroke(1.dp, PmpCyan.copy(alpha = 0.35f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "إضافة وزن جديد (مثال: 400 جم)",
                                    color = PmpCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    GlassTextField(
                                        value = newWeightInput,
                                        onValueChange = { newWeightInput = it },
                                        label = "",
                                        placeholder = "أدخل الوزن بالجرام (مثال: 400)",
                                        isNumber = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("new_weight_input_field")
                                    )
                                    GlassButton(
                                        text = "إضافة",
                                        gradient = GlassAccentBlueGradient,
                                        onClick = {
                                            if (newWeightInput.isNotBlank()) {
                                                viewModel.addInventoryWeight(
                                                    rawWeight = newWeightInput,
                                                    setAsDefault = newWeightSetDefault
                                                )
                                                newWeightInput = ""
                                                newWeightSetDefault = false
                                            } else {
                                                viewModel.postMessage("يرجى كتابة الوزن المراد إضافته", isError = true)
                                            }
                                        },
                                        modifier = Modifier.testTag("confirm_add_weight_button")
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { newWeightSetDefault = !newWeightSetDefault }
                                ) {
                                    Checkbox(
                                        checked = newWeightSetDefault,
                                        onCheckedChange = { newWeightSetDefault = it }
                                    )
                                    Text(
                                        text = "تعيين كوزن افتراضي عند الإضافة",
                                        color = TextWhite,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        // 2. Edit Registered Weight Inline Box (if user clicked Edit on a weight)
                        editingWeightSpec?.let { targetSpec ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PmpGold.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, PmpGold)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "تعديل الوزن المسجل (${targetSpec.displayLabel()}) دون حذف الأرصدة:",
                                        color = PmpGold,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        GlassTextField(
                                            value = editingWeightNewValue,
                                            onValueChange = { editingWeightNewValue = it },
                                            label = "",
                                            placeholder = "الوزن الجديد بالجرام",
                                            isNumber = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        GlassButton(
                                            text = "حفظ التعديل",
                                            gradient = GlassAccentGoldGradient,
                                            onClick = {
                                                viewModel.editInventoryWeight(
                                                    oldWeight = targetSpec.value,
                                                    newRawWeight = editingWeightNewValue
                                                )
                                                editingWeightSpec = null
                                                editingWeightNewValue = ""
                                            }
                                        )
                                        IconButton(onClick = { editingWeightSpec = null }) {
                                            Icon(Icons.Default.Close, contentDescription = "إلغاء", tint = TextMuted)
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = "جميع الأوزان المسجلة في النظام (${packageWeights.size}):",
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )

                        // 3. Scrollable List of All Registered Weights (Active & Disabled)
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(packageWeights, key = { "weight_mgr_${it.value}" }) { spec ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (spec.active) PmpNavyCard else PmpNavyCard.copy(alpha = 0.45f),
                                    border = BorderStroke(
                                        1.dp,
                                        when {
                                            spec.isDefault -> PmpGold
                                            spec.active -> GlassBorder
                                            else -> PmpRedLight.copy(alpha = 0.35f)
                                        }
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = spec.displayLabel(),
                                                    color = if (spec.active) TextWhite else TextMuted,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 14.sp
                                                )
                                                if (spec.isDefault) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = PmpGold.copy(alpha = 0.2f)
                                                    ) {
                                                        Text(
                                                            text = "افتراضي ★",
                                                            color = PmpGold,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                if (!spec.active) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = PmpBrandRed.copy(alpha = 0.2f)
                                                    ) {
                                                        Text(
                                                            text = "معطل",
                                                            color = PmpRedLight,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            // Set Default Weight Button
                                            IconButton(
                                                onClick = { viewModel.setDefaultInventoryWeight(spec.value) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (spec.isDefault) Icons.Default.Star else Icons.Default.StarBorder,
                                                    contentDescription = "تعيين كوزن افتراضي",
                                                    tint = if (spec.isDefault) PmpGold else TextMuted,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            // Edit Registered Weight Button
                                            IconButton(
                                                onClick = {
                                                    editingWeightSpec = spec
                                                    editingWeightNewValue = spec.value
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "تعديل الوزن",
                                                    tint = PmpCyan,
                                                    modifier = Modifier.size(17.dp)
                                                )
                                            }

                                            // Disable / Re-enable Weight Button
                                            IconButton(
                                                onClick = {
                                                    viewModel.toggleInventoryWeightActive(spec.value, !spec.active)
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (spec.active) Icons.Default.VisibilityOff else Icons.Default.CheckCircle,
                                                    contentDescription = if (spec.active) "تعطيل الوزن" else "إعادة تفعيل الوزن",
                                                    tint = if (spec.active) PmpRedLight else PmpGreenLight,
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
            },
            confirmButton = {
                GlassButton(
                    text = "إغلاق",
                    onClick = {
                        showManageWeightsDialog = false
                        editingWeightSpec = null
                    }
                )
            },
            containerColor = PmpNavySurface
        )
    }
}
