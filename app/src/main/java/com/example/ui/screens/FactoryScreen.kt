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

    val isManager = currentUser.isSystemManager()
    val hasFactoryFull = isManager || currentUser.hasModulePermission("المصنع")
    val hasRawMaterials = hasFactoryFull || currentUser.hasModulePermission("مواد الإنتاج")
    val hasPackaging = hasFactoryFull || currentUser.hasModulePermission("التعبئة والتغليف")

    // Filter tabs strictly based on assigned permissions
    val tabs = remember(hasFactoryFull, hasRawMaterials, hasPackaging) {
        val list = mutableListOf<String>()
        if (hasRawMaterials) {
            list.add("المواد الخام")
            list.add("مخزن المواد الخام")
        }
        if (hasPackaging) {
            list.add("أوامر الإنتاج")
        }
        if (hasFactoryFull) {
            list.add("خروج المنتج للعملاء")
            list.add("سجل دورة المصنع")
        }
        list.ifEmpty { listOf("المواد الخام") }
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "دورة صناعية متكاملة: شراء الخامات، مخزن المواد الخام، أوامر الإنتاج والتعبئة، وخروج المنتج للعملاء.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        fontSize = 12.sp
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

        val activeTabTitle = tabs.getOrElse(selectedTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))) { tabs.firstOrNull() ?: "المواد الخام" }

        when (activeTabTitle) {
            "المواد الخام" -> {
                // Tab 0: Raw Materials Procurement (المواد الخام)
                item {
                    val rawItemNames = factoryItems.map { it.name }.ifEmpty { listOf("صبغات تيتانيوم أبيض", "راتنجات ألكيد", "مذيبات ومخففات", "كربونات كالسيوم") }
                    val currentSelectedRaw = if (rawItemName.isNotBlank()) rawItemName else rawItemNames.first()

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = Color.White,
                        borderColor = Color(0xFFCBD5E1),
                        cornerRadius = 20.dp
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            // Clear top row: Title and Add button separated with clean wrapping
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "إضافة وتوريد مادة خام (المشتريات)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = PmpCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.5.sp,
                                    modifier = Modifier.weight(1f)
                                )

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFFEF3C7),
                                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = "مصر ⇄ EGP",
                                        color = Color(0xFFB45309),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "تسجيل المواد الخام المشتراة من مصر بالجنيه المصري لمخزن الخام.",
                                    color = TextMuted,
                                    fontSize = 11.5.sp,
                                    modifier = Modifier.weight(1f)
                                )

                                TextButton(
                                    onClick = { showAddItemDialog = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("＋ خامة جديدة", color = PmpCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))

                            GlassDropdown(
                                label = "اسم المادة الخام *",
                                options = rawItemNames,
                                selectedOption = currentSelectedRaw,
                                onOptionSelected = { rawItemName = it }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassTextField(
                                    value = rawQty,
                                    onValueChange = { rawQty = it },
                                    label = "الكمية المشتراة (كجم/برميل) *",
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

                            GlassTextField(
                                value = rawSupplier,
                                onValueChange = { rawSupplier = it },
                                label = "اسم المورد في مصر (افتراضي: مورد مصر المعتمد)",
                                placeholder = "اكتب اسم شركة التوريد أو المصنع في مصر..."
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            GlassTextField(
                                value = rawNote,
                                onValueChange = { rawNote = it },
                                label = "ملاحظات إضافية والتوريد من مصر",
                                placeholder = "مثال: استيراد وتوريد من مخازن القاهرة..."
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            GlassButton(
                                text = "شراء وتوريد خامة",
                                icon = Icons.Default.AddShoppingCart,
                                onClick = {
                                    val qty = rawQty.toDoubleOrNull() ?: 1.0
                                    val targetItem = factoryItems.find { it.name == currentSelectedRaw } ?: factoryItems.firstOrNull()
                                    val itemId = targetItem?.id ?: 1L
                                    val amountStr = if (rawPurchaseAmount.isNotBlank()) " • بمبلغ $rawPurchaseAmount $rawCurrency" else ""

                                    viewModel.addFactoryMove(
                                        kind = "RAW_PURCHASE",
                                        itemId = itemId,
                                        itemName = currentSelectedRaw,
                                        qty = qty,
                                        note = if (rawNote.isBlank()) "شراء مادة خام من مصر$amountStr" else "${rawNote}$amountStr",
                                        counterparty = rawSupplier.ifBlank { "مورد مصر المعتمد (EGP)" },
                                        actor = "مسؤول المواد الخام (ربط مصر)"
                                    )
                                    successRecord = FactorySuccessRecord(
                                        title = "تم تسجيل وإرسال المادة الخام بنجاح",
                                        subtitle = "تم توجيه المادة الخام لمخزن المواد الخام؛ بانتظار استلامها وإدخالها يدوياً بواسطة أمين المخزن المختص.",
                                        itemName = currentSelectedRaw,
                                        quantity = "$qty ${targetItem?.unit ?: "كجم/برميل"}",
                                        destinationWarehouse = "مخزن المواد الخام (مرتبط بمستودع مصر)",
                                        responsibleDepartment = "قسم المشتريات والمواد الخام (EGP)",
                                        counterparty = rawSupplier.ifBlank { "مورد مصر المعتمد (EGP)" },
                                        financialAmount = if (rawPurchaseAmount.isNotBlank()) "$rawPurchaseAmount $rawCurrency" else null,
                                        note = rawNote.ifBlank { null }
                                    )
                                    rawQty = "100"
                                    rawPurchaseAmount = ""
                                    rawSupplier = ""
                                    rawNote = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Raw Materials Catalog List
                item {
                    Text(
                        text = "قائمة المواد الخام وعناصر المصنع (${factoryItems.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                items(factoryItems, key = { "fi_${it.id}" }) { item ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = item.name, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(text = "الفئة: ${item.category} • الوحدة: ${item.unit}", color = TextMuted, fontSize = 11.sp)
                            }
                            IconButton(onClick = { itemToDelete = item }) {
                                Icon(Icons.Default.Delete, contentDescription = "حذف الخامة", tint = PmpRedLight)
                            }
                        }
                    }
                }
            }

            "مخزن المواد الخام" -> {
                // Tab 1: Raw Materials Warehouse (مخزن المواد الخام)
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
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "تسجيل استلام الخامات بالمخزن: الخامات والمنتجات مرتبطة بمصر وبالجنيه المصري (EGP) وتُضاف للرصيد الفعلي للمخزن.",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
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
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "صرف مواد خام إلى خطوط الإنتاج: تُخصم تلقائياً من رصيد مخزن المواد الخام وتُسلّم لمهندس التشغيل.",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
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
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "المواد المنتجة تنقسم لمعايير قياسية: 250 جم (48 علبة/كرتونة) أو 900 جم (12 علبة/كرتونة)، مع إمكانية التعديل والتخصيص.",
                                color = TextMuted,
                                fontSize = 12.sp
                            )

                            Spacer(modifier = Modifier.height(14.dp))

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
