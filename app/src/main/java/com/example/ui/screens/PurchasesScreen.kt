package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.FactoryItemEntity
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

    val format = remember { NumberFormat.getNumberInstance(Locale.US) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    var rawItemName by remember { mutableStateOf("") }
    var rawQty by remember { mutableStateOf("100") }
    var rawPurchaseAmount by remember { mutableStateOf("") }
    var rawCurrency by remember { mutableStateOf("EGP") }
    var rawSupplier by remember { mutableStateOf("") }
    var rawNote by remember { mutableStateOf("") }

    var showAddItemDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    var newItemKind by remember { mutableStateOf("خامات إنتاج") }
    var newItemUnit by remember { mutableStateOf("كجم") }
    var successRecord by remember { mutableStateOf<FactorySuccessRecord?>(null) }
    var selectedFilter by remember { mutableStateOf("الكل") }

    val rawMaterialItems = remember(factoryItems) {
        factoryItems.filter { it.category.contains("خام", ignoreCase = true) || it.category.contains("تعبئة", ignoreCase = true) }
            .ifEmpty { factoryItems }
    }

    val purchaseMoves = remember(factoryMoves, selectedFilter) {
        val list = factoryMoves.filter { it.kind.contains("PURCHASE", ignoreCase = true) || it.note.contains("شراء", ignoreCase = true) }
        if (selectedFilter == "الكل") list else list.filter { it.counterparty.contains(selectedFilter, ignoreCase = true) }
    }

    val rawItemNames = factoryItems.map { it.name }.ifEmpty { listOf("صبغات تيتانيوم أبيض", "راتنجات ألكيد", "مذيبات ومخففات", "كربونات كالسيوم") }
    val currentSelectedRaw = if (rawItemName.isNotBlank()) rawItemName else rawItemNames.first()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(28.dp))
                            Column {
                                Text(
                                    text = "المشتريات والمواد الخام",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp
                                )
                                Text(
                                    text = "إدارة المشتريات الخارجية، توريد الخامات من مصر، وحسابات الموردين.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        GlassBadge(text = "مصر ⇄ EGP", color = PmpGold)
                    }
                }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "تسجيل وتوريد مادة خام جديدة (المشتريات)",
                            style = MaterialTheme.typography.titleMedium,
                            color = PmpCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )

                        TextButton(
                            onClick = { showAddItemDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("＋ تعريف خامة", color = PmpCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                            val qty = rawQty.toDoubleOrNull() ?: 1.0
                            val targetItem = factoryItems.find { it.name == currentSelectedRaw } ?: factoryItems.firstOrNull()
                            val itemId = targetItem?.id ?: 1L
                            val amountStr = if (rawPurchaseAmount.isNotBlank()) " • بمبلغ $rawPurchaseAmount $rawCurrency" else ""
                            val actorName = currentUser?.fullName ?: "مسؤول المشتريات"

                            viewModel.addFactoryMove(
                                kind = "RAW_PURCHASE",
                                itemId = itemId,
                                itemName = currentSelectedRaw,
                                qty = qty,
                                note = if (rawNote.isBlank()) "شراء مادة خام$amountStr" else "${rawNote}$amountStr",
                                counterparty = rawSupplier.ifBlank { "مورد مصر المعتمد (EGP)" },
                                actor = actorName
                            )
                            successRecord = FactorySuccessRecord(
                                title = "تم تسجيل وإرسال المادة الخام بنجاح",
                                subtitle = "تم توجيه المادة الخام لمخزن المواد الخام؛ بانتظار استلامها وإدخالها يدوياً بواسطة أمين المخزن المختص.",
                                itemName = currentSelectedRaw,
                                quantity = "$qty ${targetItem?.unit ?: "كجم/برميل"}",
                                destinationWarehouse = "مخزن المواد الخام (مرتبط بمستودع مصر)",
                                responsibleDepartment = "قسم المشتريات والمواد الخام ($rawCurrency)",
                                counterparty = rawSupplier.ifBlank { "مورد مصر المعتمد ($rawCurrency)" },
                                financialAmount = if (rawPurchaseAmount.isNotBlank()) "$rawPurchaseAmount $rawCurrency" else null,
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
                GlassCard(modifier = Modifier.fillMaxWidth()) {
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
                                fontSize = 14.sp
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
                                fontSize = 12.sp
                            )
                            Text(
                                text = dateFormat.format(Date(move.createdAt)),
                                color = TextSubtle,
                                fontSize = 11.sp
                            )
                        }

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

    // Dialog: Add New Raw Material Definition
    if (showAddItemDialog) {
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
                        options = listOf("خامات إنتاج", "مواد تعبئة وتغليف", "مذيبات ومخففات"),
                        selectedOption = newItemKind,
                        onOptionSelected = { newItemKind = it }
                    )
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
                        if (newItemName.isNotBlank()) {
                            viewModel.addFactoryItem(
                                name = newItemName.trim(),
                                category = newItemKind,
                                unit = newItemUnit
                            )
                            rawItemName = newItemName.trim()
                            newItemName = ""
                            showAddItemDialog = false
                        }
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { showAddItemDialog = false })
            },
            containerColor = Color.White
        )
    }
}
