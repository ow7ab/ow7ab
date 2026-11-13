package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.PosterPriceEntity
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import java.text.NumberFormat
import java.util.Locale

/**
 * شاشة كتابة وتعديل قائمة الأسعار (خالية تماماً من الصور والبوسترات لضمان أقصى سلاسة وسرعة)
 * مخصصة لإدخال الأسعار، تعديلها، حساب الخصومات الفورية والمزامنة المباشرة مع السيرفر السحابي
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PosterPriceScreen(
    viewModel: PmpViewModel,
    modifier: Modifier = Modifier
) {
    val posterPrices by viewModel.posterPrices.collectAsState()
    val availablePaintColors by viewModel.availablePaintColors.collectAsState()
    val isInitialDataLoading by viewModel.isInitialDataLoading.collectAsState()
    val format = remember { NumberFormat.getNumberInstance(Locale.US) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedWeightFilter by remember { mutableStateOf("الكل") } // "الكل", "250", "900"
    var selectedCurrency by remember { mutableStateOf("SDG") } // "SDG", "EGP"
    var discountPercent by remember { mutableFloatStateOf(15f) }

    // Dialog state for editing / adding price
    var showEditPriceDialog by remember { mutableStateOf(false) }
    var editingKind by remember { mutableStateOf("") }
    var editingWeight by remember { mutableStateOf("900") }
    var editingPriceText by remember { mutableStateOf("") }
    var editingCurrency by remember { mutableStateOf("SDG") }

    // Dialog state for adding a new paint color/kind
    var showAddNewKindDialog by remember { mutableStateOf(false) }
    var newKindName by remember { mutableStateOf("") }
    var newKindPrice250 by remember { mutableStateOf("") }
    var newKindPrice900 by remember { mutableStateOf("") }

    // Merge predefined kinds with database records for the selected currency
    val baseKinds = remember { listOf("أبيض", "ألوان", "فضي", "ذهبي", "ورنيش") }
    val allUniqueKinds = remember(posterPrices, selectedCurrency) {
        val fromDbForCurrency = posterPrices
            .filter {
                if (selectedCurrency == "EGP") {
                    it.currency.equals("EGP", ignoreCase = true) || it.id.endsWith("_EGP")
                } else {
                    !it.id.endsWith("_EGP") && it.currency.equals("SDG", ignoreCase = true)
                }
            }
            .map { it.kind }
        (baseKinds + fromDbForCurrency).distinct().filter { it.isNotBlank() }
    }

    // Prepare list of items for the table strictly separated by currency
    val priceItems = remember(allUniqueKinds, posterPrices, selectedWeightFilter, searchQuery, selectedCurrency) {
        val weights = when (selectedWeightFilter) {
            "250" -> listOf("250")
            "900" -> listOf("900")
            else -> listOf("250", "900")
        }

        val list = mutableListOf<PriceRowItem>()
        for (kind in allUniqueKinds) {
            if (searchQuery.isNotBlank() && !kind.contains(searchQuery.trim(), ignoreCase = true)) {
                continue
            }
            for (w in weights) {
                if (selectedCurrency == "EGP") {
                    val egpEntity = posterPrices.firstOrNull {
                        (it.id.equals("${w}_${kind}_EGP", ignoreCase = true) ||
                            (it.kind.equals(kind, ignoreCase = true) && it.weight == w &&
                                (it.currency.equals("EGP", ignoreCase = true) || it.id.endsWith("_EGP"))))
                    }
                    val rawEgpPrice = if (egpEntity != null) {
                        if (egpEntity.priceEgp > 0L && egpEntity.price == egpEntity.priceEgp * 200L) {
                            egpEntity.priceEgp
                        } else {
                            egpEntity.price
                        }
                    } else if (kind in baseKinds) {
                        when (kind) {
                            "أبيض" -> if (w == "900") 1925L else 2500L
                            "ألوان" -> if (w == "900") 1875L else 2450L
                            "فضي" -> if (w == "900") 1950L else 2550L
                            "ذهبي" -> if (w == "900") 2000L else 2600L
                            "ورنيش" -> if (w == "900") 1875L else 2475L
                            else -> 0L
                        }
                    } else {
                        null
                    }
                    if (rawEgpPrice != null) {
                        list.add(PriceRowItem(kind = kind, weight = w, price = rawEgpPrice, currency = "EGP"))
                    }
                } else {
                    val sdgEntity = posterPrices.firstOrNull {
                        !it.id.endsWith("_EGP") &&
                            it.currency.equals("SDG", ignoreCase = true) &&
                            it.kind.equals(kind, ignoreCase = true) &&
                            it.weight == w
                    }
                    val rawSdgPrice = sdgEntity?.price ?: if (kind in baseKinds) {
                        when (kind) {
                            "أبيض" -> if (w == "900") 385000L else 500000L
                            "ألوان" -> if (w == "900") 375000L else 490000L
                            "فضي" -> if (w == "900") 390000L else 510000L
                            "ذهبي" -> if (w == "900") 400000L else 520000L
                            "ورنيش" -> if (w == "900") 375000L else 495000L
                            else -> 0L
                        }
                    } else {
                        null
                    }
                    if (rawSdgPrice != null) {
                        list.add(PriceRowItem(kind = kind, weight = w, price = rawSdgPrice, currency = "SDG"))
                    }
                }
            }
        }
        list
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Header Card - Pure info, high performance
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                                    .clip(CircleShape)
                                    .background(PmpBrandNavy),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.EditNote,
                                    contentDescription = null,
                                    tint = PmpGold,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Text(
                                text = "كتابة وتعديل قائمة الأسعار",
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                    }

                    // Currency Switch & Add New Kind Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Currency Selector (SDG / EGP)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(PmpNavySurface)
                                .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("SDG" to "الجنيه السوداني", "EGP" to "الجنيه المصري").forEach { (code, label) ->
                                val isSelected = selectedCurrency == code
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) PmpBrandRed else Color.Transparent)
                                        .clickable { selectedCurrency = code }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) Color.White else TextMuted,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        // Add new paint kind button
                        Button(
                            onClick = {
                                newKindName = ""
                                newKindPrice250 = ""
                                newKindPrice900 = ""
                                showAddNewKindDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PmpGreen),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("صنف جديد", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2. Filter & Discount Calculator Card
        item {
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Search Input
                    GlassTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = "البحث في أصناف وألوان الدهانات",
                        placeholder = "ابحث عن: أبيض، كبدي، ورنيش، فضي...",
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Weight Package Filter
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "العبوة:",
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        listOf("الكل" to "جميع الأحجام", "250" to "250 جرام", "900" to "900 جرام").forEach { (filterVal, label) ->
                            val isSelected = selectedWeightFilter == filterVal
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) PmpGold.copy(alpha = 0.25f) else PmpNavySurface)
                                    .border(1.dp, if (isSelected) PmpGold else GlassBorder, RoundedCornerShape(10.dp))
                                    .clickable { selectedWeightFilter = filterVal }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) PmpGold else TextWhite,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = GlassBorder.copy(alpha = 0.4f), thickness = 0.5.dp)

                    // Discount Slider & Percentage
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Percent, contentDescription = null, tint = PmpGold, modifier = Modifier.size(16.dp))
                                Text(
                                    text = "نسبة الخصم المعروضة للعميل:",
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = "${discountPercent.toInt()}%",
                                color = PmpGold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        Slider(
                            value = discountPercent,
                            onValueChange = { discountPercent = it },
                            valueRange = 0f..50f,
                            steps = 9, // 0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50
                            colors = SliderDefaults.colors(
                                thumbColor = PmpGold,
                                activeTrackColor = PmpGold,
                                inactiveTrackColor = PmpNavySurface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 3. Count Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "الأصناف والأسعار المعتمدة (${priceItems.size})",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = "العملة: $selectedCurrency",
                    color = PmpCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 4. Pure, High-Performance Price Cards with Skeleton Loading
        if (isInitialDataLoading && posterPrices.isEmpty()) {
            item(key = "poster_prices_shimmer_skeleton") {
                ProvideShimmerEffect(enabled = true) {
                    PosterPricesSkeletonSection()
                }
            }
        } else if (priceItems.isEmpty()) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "لا توجد أصناف مطابقة للبحث المحدد",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            items(priceItems, key = { "${it.weight}_${it.kind}_${it.currency}" }) { item ->
                val discountAmount = (item.price * (discountPercent / 100f)).toLong()
                val netPrice = (item.price - discountAmount).coerceAtLeast(0L)

                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Kind & Weight with color dot
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1.2f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(getPMPColorTint(item.kind))
                                    .border(1.dp, GlassBorder, CircleShape)
                            )
                            Column {
                                Text(
                                    text = item.kind,
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "عبوة ${item.weight} جم",
                                    color = TextMuted,
                                    fontSize = 11.5.sp
                                )
                            }
                        }

                        // Base Price & Net Price
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.weight(1.5f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${format.format(item.price)} ${item.currency}",
                                    color = if (discountPercent > 0) TextMuted else PmpGold,
                                    fontSize = if (discountPercent > 0) 12.sp else 15.sp,
                                    fontWeight = if (discountPercent > 0) FontWeight.Normal else FontWeight.Bold,
                                    style = if (discountPercent > 0) MaterialTheme.typography.bodySmall.copy(
                                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                                    ) else MaterialTheme.typography.bodyMedium
                                )
                            }

                            if (discountPercent > 0) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "الصافي:",
                                        color = PmpGreen,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${format.format(netPrice)} ${item.currency}",
                                        color = PmpGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        // Quick Edit Button
                        Button(
                            onClick = {
                                editingKind = item.kind
                                editingWeight = item.weight
                                editingPriceText = item.price.toString()
                                editingCurrency = item.currency
                                showEditPriceDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PmpNavySurface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, PmpGold.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "تعديل السعر",
                                tint = PmpGold,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "تعديل",
                                color = TextWhite,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // --- Modal Dialog 1: Edit Price ---
    if (showEditPriceDialog) {
        AlertDialog(
            onDismissRequest = { showEditPriceDialog = false },
            title = {
                Text(
                    text = "تعديل سعر $editingKind ($editingWeight جم)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = PmpBrandNavy
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "أدخل السعر الأساسي الجديد للعبوة. سيتم حفظه ومزامنته فورياً مع السيرفر السحابي:",
                        fontSize = 12.sp,
                        color = Color.DarkGray
                    )
                    OutlinedTextField(
                        value = editingPriceText,
                        onValueChange = { editingPriceText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("السعر بالـ $editingCurrency") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newPrice = editingPriceText.toLongOrNull() ?: 0L
                        if (newPrice > 0L) {
                            viewModel.updatePosterPrice(
                                weight = editingWeight,
                                kind = editingKind,
                                price = newPrice,
                                currency = editingCurrency
                            )
                            showEditPriceDialog = false
                        } else {
                            viewModel.postMessage("يرجى كتابة سعر صحيح أكبر من الصفر", isError = true)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PmpBrandRed)
                ) {
                    Text("حفظ وتحديث السعر")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditPriceDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    // --- Modal Dialog 2: Add New Paint Kind ---
    if (showAddNewKindDialog) {
        AlertDialog(
            onDismissRequest = { showAddNewKindDialog = false },
            title = {
                Text(
                    text = "إضافة صنف أو لون جديد لقائمة الأسعار",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = PmpBrandNavy
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newKindName,
                        onValueChange = { newKindName = it },
                        label = { Text("اسم الصنف أو اللون (مثال: نحاسي، كبدي، زيتي)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newKindPrice250,
                        onValueChange = { newKindPrice250 = it.filter { ch -> ch.isDigit() } },
                        label = { Text("سعر عبوة 250 جم ($selectedCurrency)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newKindPrice900,
                        onValueChange = { newKindPrice900 = it.filter { ch -> ch.isDigit() } },
                        label = { Text("سعر عبوة 900 جم ($selectedCurrency)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanName = newKindName.trim()
                        val p250 = newKindPrice250.toLongOrNull() ?: 0L
                        val p900 = newKindPrice900.toLongOrNull() ?: 0L

                        if (cleanName.isBlank()) {
                            viewModel.postMessage("يرجى كتابة اسم الصنف أو اللون", isError = true)
                            return@Button
                        }
                        if (p250 <= 0L && p900 <= 0L) {
                            viewModel.postMessage("يرجى إدخال سعر واحد على الأقل للعبوة", isError = true)
                            return@Button
                        }

                        if (p250 > 0L) {
                            viewModel.updatePosterPrice(
                                weight = "250",
                                kind = cleanName,
                                price = p250,
                                currency = selectedCurrency
                            )
                        }
                        if (p900 > 0L) {
                            viewModel.updatePosterPrice(
                                weight = "900",
                                kind = cleanName,
                                price = p900,
                                currency = selectedCurrency
                            )
                        }

                        // Add to custom colors
                        val currentList = viewModel.customColorsList.value
                        if (!currentList.contains(cleanName)) {
                            viewModel.customColorsList.value = currentList + cleanName
                        }

                        viewModel.postMessage("تمت إضافة $cleanName إلى قائمة الأسعار بنجاح ومزامنته سحابياً")
                        showAddNewKindDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PmpGreen)
                ) {
                    Text("إضافة وحفظ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNewKindDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

private data class PriceRowItem(
    val kind: String,
    val weight: String,
    val price: Long,
    val currency: String
)

private fun getPMPColorTint(kind: String): Color {
    return when {
        kind.contains("أبيض") -> Color.White
        kind.contains("كبدي") || kind.contains("بني") -> Color(0xFF800000)
        kind.contains("أزرق") -> Color(0xFF1E40AF)
        kind.contains("أحمر") -> Color(0xFFDC2626)
        kind.contains("أخضر") -> Color(0xFF16A34A)
        kind.contains("أسود") -> Color(0xFF111827)
        kind.contains("أصفر") -> Color(0xFFFBBF24)
        kind.contains("فضي") -> Color(0xFF94A3B8)
        kind.contains("ذهبي") -> Color(0xFFF59E0B)
        kind.contains("ورنيش") -> Color(0xFFD97706)
        kind.contains("كريمي") -> Color(0xFFFEF3C7)
        kind.contains("وردي") -> Color(0xFFF472B6)
        kind.contains("بنفسجي") -> Color(0xFF8B5CF6)
        else -> PmpGold
    }
}
