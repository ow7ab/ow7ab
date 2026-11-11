package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.data.local.FactoryItemEntity
import com.example.data.local.FactoryMoveEntity
import com.example.hasModulePermission
import com.example.isSystemManager
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PurchasesScreen(
    viewModel: PmpViewModel,
    modifier: Modifier = Modifier
) {
    val factoryItems by viewModel.factoryItems.collectAsState()
    val factoryMoves by viewModel.factoryMoves.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val defaultAccountCurrency by viewModel.defaultAccountCurrency.collectAsState()
    val isInitialDataLoading by viewModel.isInitialDataLoading.collectAsState()
    val dataLoadError by viewModel.dataLoadError.collectAsState()
    var selectedPurchaseForDetail by remember { mutableStateOf<FactoryMoveEntity?>(null) }

    val isManager = currentUser.isSystemManager()
    val canEditItemCategory = remember(currentUser) {
        currentUser.isSystemManager() || currentUser.hasModulePermission("تعديل نوع الصنف")
    }

    val format = remember { NumberFormat.getNumberInstance(Locale.US) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    var rawItemName by remember { mutableStateOf("") }
    var rawQty by remember { mutableStateOf("100") }
    var rawPurchaseAmount by remember { mutableStateOf("") }
    var rawCurrency by remember { mutableStateOf(defaultAccountCurrency) }
    var rawSupplier by remember { mutableStateOf("") }
    var rawNote by remember { mutableStateOf("") }

    LaunchedEffect(defaultAccountCurrency) {
        rawCurrency = defaultAccountCurrency
    }

    // Custom categories added or renamed during session + categories existing in DB
    var customItemCategories by remember {
        mutableStateOf(listOf("خامات إنتاج", "مواد تعبئة وتغليف", "مذيبات ومخففات", "إضافات كيميائية"))
    }

    val availableCategories = remember(factoryItems, customItemCategories) {
        val dbCategories = factoryItems.map { it.category.trim() }
            .filter { it.isNotBlank() && it !in listOf("raw", "finished", "empty") }
        (customItemCategories + dbCategories).distinct()
    }

    // Add New Raw Material Dialog State
    var showAddItemDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    var newItemKind by remember { mutableStateOf("خامات إنتاج") }
    var newCustomKindText by remember { mutableStateOf("") }
    var newItemUnit by remember { mutableStateOf("كجم") }

    // Edit Existing Raw Material Dialog State
    var itemToEdit by remember { mutableStateOf<FactoryItemEntity?>(null) }
    var editItemName by remember { mutableStateOf("") }
    var editItemCategory by remember { mutableStateOf("") }
    var editCustomCategoryText by remember { mutableStateOf("") }
    var editItemUnit by remember { mutableStateOf("كجم") }

    // Delete Existing Raw Material Dialog State
    var itemToDelete by remember { mutableStateOf<FactoryItemEntity?>(null) }

    // Rename Item Category (تعديل اسم نوع الصنف) Dialog State
    var showRenameCategoryDialog by remember { mutableStateOf(false) }
    var selectedCategoryToRename by remember { mutableStateOf("") }
    var renamedCategoryNewText by remember { mutableStateOf("") }

    var successRecord by remember { mutableStateOf<FactorySuccessRecord?>(null) }
    var selectedFilter by remember { mutableStateOf("الكل") }

    val purchaseMoves = remember(factoryMoves, selectedFilter) {
        val list = factoryMoves.filter { it.kind.contains("PURCHASE", ignoreCase = true) || it.note.contains("شراء", ignoreCase = true) }
        if (selectedFilter == "الكل") list else list.filter { it.counterparty.contains(selectedFilter, ignoreCase = true) }
    }

    val rawItemNames = remember(factoryItems) {
        factoryItems.map { it.name }.ifEmpty {
            listOf("صبغات تيتانيوم أبيض", "راتنجات ألكيد", "مذيبات ومخففات", "كربونات كالسيوم")
        }
    }
    val currentSelectedRaw = remember(rawItemName, rawItemNames) {
        if (rawItemName.isNotBlank() && rawItemName in rawItemNames) rawItemName else rawItemNames.first()
    }
    val selectedFactoryItem = remember(factoryItems, currentSelectedRaw) {
        factoryItems.find { it.name == currentSelectedRaw } ?: factoryItems.firstOrNull()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header Card
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 24.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(28.dp))
                            Text(
                                text = "المشتريات والمواد الخام",
                                style = MaterialTheme.typography.headlineMedium,
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 19.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        GlassBadge(text = "مصر ⇄ EGP", color = PmpGold)
                    }
                }
            }
        }

        if (isInitialDataLoading && factoryItems.isEmpty() && factoryMoves.isEmpty()) {
            item(key = "purchases_shimmer_skeleton") {
                ProvideShimmerEffect(enabled = true) {
                    PurchasesSkeletonSection()
                }
            }
        } else if (dataLoadError != null && factoryItems.isEmpty() && factoryMoves.isEmpty()) {
            item(key = "purchases_error_card") {
                PmpErrorStateCard(
                    message = dataLoadError ?: "تعذر تحميل بيانات المشتريات",
                    onRetry = { viewModel.retryDataLoadAndSync() }
                )
            }
        }

        // Procurement Form Card
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = Color.White,
                borderColor = Color(0xFFCBD5E1),
                cornerRadius = 20.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "تسجيل وتوريد مادة خام جديدة (المشتريات)",
                        style = MaterialTheme.typography.titleMedium,
                        color = PmpCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.5.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (canEditItemCategory) {
                            OutlinedButton(
                                onClick = {
                                    selectedCategoryToRename = availableCategories.firstOrNull() ?: "خامات إنتاج"
                                    renamedCategoryNewText = selectedCategoryToRename
                                    showRenameCategoryDialog = true
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, PmpBrandNavy.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 40.dp)
                                    .testTag("rename_category_button")
                            ) {
                                Icon(Icons.Default.Category, contentDescription = null, tint = PmpBrandNavy, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "تعديل نوع الصنف",
                                    color = PmpBrandNavy,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                newItemName = ""
                                newItemKind = availableCategories.firstOrNull() ?: "خامات إنتاج"
                                newCustomKindText = ""
                                showAddItemDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, PmpCyan),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp)
                                .testTag("add_raw_material_button")
                        ) {
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "＋ تعريف خامة",
                                color = PmpCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    GlassDropdown(
                        label = "اسم المادة الخام *",
                        options = rawItemNames,
                        selectedOption = currentSelectedRaw,
                        onOptionSelected = { rawItemName = it }
                    )

                    // Quick Edit / Delete Bar for the currently selected Raw Material
                    selectedFactoryItem?.let { currentItem ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(PmpContainerBarSurface)
                                .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = "الخامة المحددة: ${currentItem.name}",
                                        color = TextDark,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "نوع الصنف: ${currentItem.category} • الوحدة: ${currentItem.unit}",
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            itemToEdit = currentItem
                                            editItemName = currentItem.name
                                            editItemCategory = currentItem.category.ifBlank { availableCategories.firstOrNull() ?: "خامات إنتاج" }
                                            editCustomCategoryText = ""
                                            editItemUnit = currentItem.unit.ifBlank { "كجم" }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .heightIn(min = 36.dp)
                                            .testTag("edit_selected_raw_item_button"),
                                        border = BorderStroke(1.dp, PmpCyan)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "تعديل الخامة", tint = PmpCyan, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("تعديل", color = PmpCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = { itemToDelete = currentItem },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .heightIn(min = 36.dp)
                                            .testTag("delete_selected_raw_item_button"),
                                        border = BorderStroke(1.dp, PmpRed)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "حذف الخامة", tint = PmpRed, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("حذف", color = PmpRed, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassTextField(
                            value = rawQty,
                            onValueChange = { rawQty = it },
                            label = "الكمية المشتراة *",
                            isNumber = true,
                            modifier = Modifier.weight(1f)
                        )

                        GlassTextField(
                            value = rawPurchaseAmount,
                            onValueChange = { rawPurchaseAmount = it },
                            label = "مبلغ الشراء ($rawCurrency) *",
                            placeholder = "0.00 EGP",
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
                            label = "العملة",
                            options = listOf("EGP", "SDG", "USD"),
                            selectedOption = rawCurrency,
                            onOptionSelected = { rawCurrency = it },
                            modifier = Modifier.width(110.dp)
                        )

                        GlassTextField(
                            value = rawSupplier,
                            onValueChange = { rawSupplier = it },
                            label = "اسم المورد / الشركة *",
                            placeholder = "مثال: مورد مصر المعتمد...",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    GlassTextField(
                        value = rawNote,
                        onValueChange = { rawNote = it },
                        label = "ملاحظات الفاتورة والتوريد",
                        placeholder = "مثال: شحنة براميل عبر مخازن القاهرة..."
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    GlassButton(
                        text = "تسجيل وإرسال المشتريات للمخزن",
                        icon = Icons.Default.AddShoppingCart,
                        onClick = {
                            if (!viewModel.checkOnlineBeforeTransaction("تسجيل المشتريات وتوريد الخامات")) {
                                return@GlassButton
                            }

                            val qty = rawQty.toDoubleOrNull() ?: 1.0
                            val targetItem = factoryItems.find { it.name == currentSelectedRaw } ?: factoryItems.firstOrNull()
                            val itemId = targetItem?.id ?: 1L
                            val purchaseAmountNum = rawPurchaseAmount.toDoubleOrNull() ?: 0.0
                            val amountStr = if (purchaseAmountNum > 0) " • بمبلغ ${format.format(purchaseAmountNum)} $rawCurrency" else ""
                            val actorName = currentUser?.fullName ?: "مسؤول المشتريات"

                            // 1. تسجيل حركة المصنع وتوريد الخامة للمخزن
                            viewModel.addFactoryMove(
                                kind = "RAW_PURCHASE",
                                itemId = itemId,
                                itemName = currentSelectedRaw,
                                qty = qty,
                                note = if (rawNote.isBlank()) "شراء مادة خام$amountStr" else "${rawNote}$amountStr",
                                counterparty = rawSupplier.ifBlank { "مورد مصر المعتمد ($rawCurrency)" },
                                actor = actorName
                            )

                            // 2. تسجيل قيد منصرف بالخزنة المربوطة تلقائياً إذا تم تحديد قيمة مالية
                            if (purchaseAmountNum > 0.0) {
                                val linkedBox = when (rawCurrency.uppercase()) {
                                    "EGP" -> "خزنة مصر"
                                    "USD" -> "خزنة الدولار"
                                    else -> "خزنة السودان"
                                }
                                viewModel.addMovement(
                                    cashbox = linkedBox,
                                    currency = rawCurrency,
                                    direction = "منصرف",
                                    amount = purchaseAmountNum,
                                    reference = "PO-${System.currentTimeMillis() % 1000000}",
                                    description = "فاتورة مشتريات خامات ($currentSelectedRaw) - المورد: ${rawSupplier.ifBlank { "مورد معتمد" }}"
                                )
                            }

                            val linkedBoxNotice = if (purchaseAmountNum > 0) " وقيد منصرف بقيمة ${format.format(purchaseAmountNum)} $rawCurrency في خزنة $rawCurrency" else ""
                            successRecord = FactorySuccessRecord(
                                title = "تم تسجيل وإرسال المادة الخام بنجاح",
                                subtitle = "تم توجيه المادة الخام لمخزن المواد الخام$linkedBoxNotice؛ بانتظار استلامها وإدخالها يدوياً بواسطة أمين المخزن المختص.",
                                itemName = currentSelectedRaw,
                                quantity = "$qty ${targetItem?.unit ?: "كجم/برميل"}",
                                destinationWarehouse = "مخزن المواد الخام (مرتبط بمستودع مصر)",
                                responsibleDepartment = "قسم المشتريات والمواد الخام ($rawCurrency)",
                                counterparty = rawSupplier.ifBlank { "مورد مصر المعتمد ($rawCurrency)" },
                                financialAmount = if (purchaseAmountNum > 0) "${format.format(purchaseAmountNum)} $rawCurrency" else null,
                                note = rawNote.ifBlank { null }
                            )
                            rawQty = "100"
                            rawPurchaseAmount = ""
                            rawSupplier = ""
                            rawNote = ""
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("submit_purchase_button")
                    )
                }
            }
        }

        // Success Confirmation Banner
        successRecord?.let { record ->
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = PmpGreen,
                    backgroundColor = Color(0xFFF0FDF4)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = record.title, color = Color(0xFF166534), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(text = record.subtitle, color = Color(0xFF15803D), fontSize = 12.sp)
                        }
                        IconButton(onClick = { successRecord = null }) {
                            Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color(0xFF166534))
                        }
                    }
                }
            }
        }

        // Defined Raw Materials Directory Card (Manage / Edit / Delete Raw Materials & Categories)
        if (factoryItems.isNotEmpty()) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "دليل المواد الخام المضافة (${factoryItems.size})",
                                color = TextDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        factoryItems.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFF8FAFC))
                                    .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = item.name,
                                        color = TextDark,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        GlassBadge(
                                            text = "نوع الصنف: ${item.category.ifBlank { "خامات إنتاج" }}",
                                            color = PmpBrandNavy
                                        )
                                        GlassBadge(
                                            text = "الوحدة: ${item.unit}",
                                            color = PmpCyan
                                        )
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            itemToEdit = item
                                            editItemName = item.name
                                            editItemCategory = item.category.ifBlank { availableCategories.firstOrNull() ?: "خامات إنتاج" }
                                            editCustomCategoryText = ""
                                            editItemUnit = item.unit.ifBlank { "كجم" }
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("edit_raw_item_${item.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "تعديل المادة الخام",
                                            tint = PmpCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { itemToDelete = item },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("delete_raw_item_${item.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "حذف المادة الخام",
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
        }

        // Purchases History Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "سجل فواتير وحركات المشتريات (${purchaseMoves.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        if (purchaseMoves.isEmpty()) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.ShoppingBag, contentDescription = null, tint = TextMuted, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("لا توجد فواتير مشتريات مسجلة بعد", color = TextMuted, fontSize = 13.sp)
                    }
                }
            }
        } else {
            items(purchaseMoves, key = { "pm_${it.id}" }) { move ->
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { selectedPurchaseForDetail = move }
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = move.itemName,
                                color = TextDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            GlassBadge(text = "+${move.qty}", color = PmpGreen)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "المورد: ${move.counterparty.ifBlank { "مورد معتمد" }}",
                                color = TextMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            Text(
                                text = dateFormat.format(Date(move.createdAt)),
                                color = TextSubtle,
                                fontSize = 11.sp
                            )
                        }

                        Text(
                            text = "موظف الحساب المنفذ: ${move.actor.ifBlank { "المدير" }} (اضغط لعرض التفاصيل)",
                            color = PmpBrandNavy,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        )

                        if (move.note.isNotBlank()) {
                            Text(
                                text = move.note,
                                color = PmpCyan,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal: Purchase Transaction Details Dialog (Displays Employee Name & Full Details)
    selectedPurchaseForDetail?.let { move ->
        AlertDialog(
            onDismissRequest = { selectedPurchaseForDetail = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = PmpCyan)
                    Text(
                        text = "تفاصيل معاملة المشتريات #${move.id}",
                        color = PmpBrandNavy,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PmpDangerSoftSurface)
                            .border(1.dp, PmpBrandRed.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Badge, contentDescription = null, tint = PmpBrandRed)
                        Column {
                            Text(text = "اسم موظف الحساب الذي قام بعمل المعاملة:", color = TextMuted, fontSize = 11.sp)
                            Text(
                                text = move.actor.ifBlank { "المدير" },
                                color = PmpBrandRed,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.5.sp
                            )
                        }
                    }
                    Text(text = "الصنف / الخامة: ${move.itemName}", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(text = "الكمية الموردة: +${move.qty}", color = PmpGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(text = "المورد: ${move.counterparty.ifBlank { "مورد معتمد" }}", color = TextDark, fontSize = 13.sp)
                    Text(text = "تاريخ المعاملة: ${dateFormat.format(Date(move.createdAt))}", color = TextMuted, fontSize = 12.sp)
                    if (move.note.isNotBlank()) {
                        HorizontalDivider(color = GlassBorder)
                        Text(text = "البيان والتفاصيل: ${move.note}", color = TextDark, fontSize = 12.5.sp)
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    text = "إغلاق",
                    onClick = { selectedPurchaseForDetail = null }
                )
            },
            containerColor = PmpWhiteSurface
        )
    }

    // Dialog 1: Add New Raw Material Definition
    if (showAddItemDialog) {
        val addKindOptions = remember(availableCategories, canEditItemCategory) {
            if (canEditItemCategory) {
                availableCategories + "نوع صنف جديد (كتابة يدوية)..."
            } else {
                availableCategories
            }
        }

        AlertDialog(
            onDismissRequest = { showAddItemDialog = false },
            title = {
                Text("تعريف مادة خام جديدة في النظام", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassTextField(
                        value = newItemName,
                        onValueChange = { newItemName = it },
                        label = "اسم المادة الخام *",
                        placeholder = "مثال: صمغ ألكيد معدل..."
                    )
                    GlassDropdown(
                        label = "نوع الصنف",
                        options = addKindOptions,
                        selectedOption = newItemKind,
                        onOptionSelected = { newItemKind = it }
                    )
                    if (newItemKind == "نوع صنف جديد (كتابة يدوية)..." && canEditItemCategory) {
                        GlassTextField(
                            value = newCustomKindText,
                            onValueChange = { newCustomKindText = it },
                            label = "اكتب اسم نوع الصنف الجديد *",
                            placeholder = "مثال: مواد رابطة ومثبتات..."
                        )
                    }
                    GlassDropdown(
                        label = "وحدة القياس",
                        options = listOf("كجم", "برميل", "لتر", "طن", "عبوة"),
                        selectedOption = newItemUnit,
                        onOptionSelected = { newItemUnit = it }
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "حفظ المادة الخام",
                    onClick = {
                        if (!viewModel.checkOnlineBeforeTransaction("تعريف مادة خام جديدة")) {
                            return@GlassButton
                        }
                        if (newItemName.isNotBlank()) {
                            val finalCategory = if (newItemKind == "نوع صنف جديد (كتابة يدوية)..." && newCustomKindText.isNotBlank()) {
                                val customCat = newCustomKindText.trim()
                                if (customCat !in customItemCategories) {
                                    customItemCategories = customItemCategories + customCat
                                }
                                customCat
                            } else {
                                newItemKind
                            }
                            viewModel.addFactoryItem(
                                name = newItemName.trim(),
                                category = finalCategory,
                                unit = newItemUnit
                            )
                            rawItemName = newItemName.trim()
                            newItemName = ""
                            newCustomKindText = ""
                            showAddItemDialog = false
                        }
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { showAddItemDialog = false })
            },
            containerColor = PmpWhiteSurface
        )
    }

    // Dialog 2: Edit Existing Raw Material (تعديل اسم المادة الخام ونوع الصنف)
    itemToEdit?.let { targetItem ->
        val editCategoryOptions = remember(availableCategories, targetItem.category, canEditItemCategory) {
            val base = (availableCategories + targetItem.category.trim()).filter { it.isNotBlank() }.distinct()
            if (canEditItemCategory) {
                base + "كتابة اسم نوع صنف مخصص..."
            } else {
                base
            }
        }

        AlertDialog(
            onDismissRequest = { itemToEdit = null },
            title = {
                Text(
                    text = "تعديل بيانات المادة الخام (${targetItem.name})",
                    color = TextDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassTextField(
                        value = editItemName,
                        onValueChange = { editItemName = it },
                        label = "اسم المادة الخام *",
                        placeholder = "أدخل الاسم الجديد للمادة الخام...",
                        modifier = Modifier.testTag("edit_raw_item_name_input")
                    )

                    if (canEditItemCategory) {
                        GlassDropdown(
                            label = "نوع الصنف (متاح للتعديل)",
                            options = editCategoryOptions,
                            selectedOption = editItemCategory,
                            onOptionSelected = { editItemCategory = it }
                        )
                        if (editItemCategory == "كتابة اسم نوع صنف مخصص...") {
                            GlassTextField(
                                value = editCustomCategoryText,
                                onValueChange = { editCustomCategoryText = it },
                                label = "الاسم الجديد لنوع الصنف *",
                                placeholder = "اكتب اسم نوع الصنف...",
                                modifier = Modifier.testTag("edit_custom_category_input")
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "نوع الصنف الحالي: ${targetItem.category}",
                                color = TextDark,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "ملاحظة: تعديل نوع الصنف يتطلب صلاحية (تعديل نوع الصنف) من مدير النظام.",
                                color = PmpRed,
                                fontSize = 11.sp
                            )
                        }
                    }

                    GlassDropdown(
                        label = "وحدة القياس",
                        options = listOf("كجم", "برميل", "لتر", "طن", "عبوة"),
                        selectedOption = editItemUnit,
                        onOptionSelected = { editItemUnit = it }
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "حفظ التعديلات",
                    onClick = {
                        val cleanName = editItemName.trim()
                        if (cleanName.isBlank()) {
                            viewModel.postMessage("يرجى إدخال اسم المادة الخام", isError = true)
                            return@GlassButton
                        }
                        val resolvedCategory = if (!canEditItemCategory) {
                            targetItem.category
                        } else if (editItemCategory == "كتابة اسم نوع صنف مخصص..." && editCustomCategoryText.isNotBlank()) {
                            val customCat = editCustomCategoryText.trim()
                            if (customCat !in customItemCategories) {
                                customItemCategories = customItemCategories + customCat
                            }
                            customCat
                        } else {
                            editItemCategory
                        }

                        viewModel.updateFactoryItem(
                            item = targetItem,
                            newName = cleanName,
                            newCategory = resolvedCategory,
                            newUnit = editItemUnit,
                            onSuccess = {
                                if (rawItemName == targetItem.name) {
                                    rawItemName = cleanName
                                }
                                itemToEdit = null
                            }
                        )
                    },
                    modifier = Modifier.testTag("confirm_edit_raw_item_button")
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { itemToEdit = null })
            },
            containerColor = PmpWhiteSurface
        )
    }

    // Dialog 3: Confirm Delete Raw Material (حذف المادة الخام بعد إضافتها)
    itemToDelete?.let { targetItem ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = {
                Text(
                    text = "تأكيد حذف المادة الخام",
                    color = PmpRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = "هل أنت متأكد من رغبتك في حذف المادة الخام (${targetItem.name}) من قائمة المشتريات والمصنع والسحابة؟",
                    color = TextDark,
                    fontSize = 13.5.sp
                )
            },
            confirmButton = {
                GlassButton(
                    text = "نعم، حذف المادة الخام",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        viewModel.deleteFactoryItem(targetItem)
                        if (rawItemName == targetItem.name) {
                            rawItemName = ""
                        }
                        itemToDelete = null
                    },
                    modifier = Modifier.testTag("confirm_delete_raw_item_button")
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { itemToDelete = null })
            },
            containerColor = PmpWhiteSurface
        )
    }

    // Dialog 4: Rename Item Category (تعديل اسم نوع الصنف)
    if (showRenameCategoryDialog && canEditItemCategory) {
        AlertDialog(
            onDismissRequest = { showRenameCategoryDialog = false },
            title = {
                Text(
                    text = "تعديل اسم نوع الصنف",
                    color = PmpBrandNavy,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "اختر نوع الصنف الحالي واكتب الاسم الجديد ليتم تحديثه في النظام والمواد الخام المرتبطة به:",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    GlassDropdown(
                        label = "نوع الصنف المراد تعديله",
                        options = availableCategories,
                        selectedOption = selectedCategoryToRename.ifBlank { availableCategories.firstOrNull() ?: "خامات إنتاج" },
                        onOptionSelected = {
                            selectedCategoryToRename = it
                            renamedCategoryNewText = it
                        }
                    )
                    GlassTextField(
                        value = renamedCategoryNewText,
                        onValueChange = { renamedCategoryNewText = it },
                        label = "الاسم الجديد لنوع الصنف *",
                        placeholder = "اكتب الاسم الجديد لنوع الصنف...",
                        modifier = Modifier.testTag("new_category_name_input")
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "حفظ اسم نوع الصنف",
                    onClick = {
                        val oldCat = selectedCategoryToRename.trim()
                        val newCat = renamedCategoryNewText.trim()
                        if (newCat.isBlank()) {
                            viewModel.postMessage("يرجى كتابة الاسم الجديد لنوع الصنف", isError = true)
                            return@GlassButton
                        }
                        customItemCategories = customItemCategories.map { if (it == oldCat) newCat else it }
                            .let { if (newCat !in it) it + newCat else it }
                        viewModel.renameFactoryItemCategory(
                            oldCategory = oldCat,
                            newCategory = newCat,
                            onSuccess = {
                                showRenameCategoryDialog = false
                            }
                        )
                    },
                    modifier = Modifier.testTag("confirm_rename_category_button")
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { showRenameCategoryDialog = false })
            },
            containerColor = PmpWhiteSurface
        )
    }
}
