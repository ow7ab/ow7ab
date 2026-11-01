package com.example.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.isSystemManager
import com.example.parsedPermissionsSet
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import java.text.NumberFormat
import java.util.Locale

private data class RawStockItemSummary(
    val name: String,
    val unit: String,
    val category: String,
    val purchasedQty: Double,
    val issuedQty: Double,
    val balance: Double
)

@Composable
fun ReportsScreen(
    viewModel: PmpViewModel,
    modifier: Modifier = Modifier
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val isManager = currentUser.isSystemManager()
    val userPerms = remember(currentUser) { currentUser.parsedPermissionsSet() }

    val invoices by viewModel.invoices.collectAsState()
    val invoicePayments by viewModel.invoicePayments.collectAsState()
    val stockMoves by viewModel.stockMoves.collectAsState()
    val movements by viewModel.movements.collectAsState()
    val customers by viewModel.customers.collectAsState()
    val factoryMoves by viewModel.factoryMoves.collectAsState()
    val factoryItems by viewModel.factoryItems.collectAsState()

    val allReportTabs = listOf("المبيعات", "المديونيات", "حركة المخزون", "حركات الخزن", "تقارير المواد الخام")
    val reportOptions = remember(currentUser, userPerms) {
        if (isManager || userPerms.contains("التقارير (كاملة)")) {
            allReportTabs
        } else {
            val granularMatches = allReportTabs.filter { tabName ->
                when (tabName) {
                    "المبيعات" -> userPerms.contains("تقارير المبيعات")
                    "المديونيات" -> userPerms.contains("تقارير المديونيات")
                    "حركة المخزون" -> userPerms.contains("تقارير حركة المخزون")
                    "حركات الخزن" -> userPerms.contains("تقارير حركات الخزن")
                    "تقارير المواد الخام" -> userPerms.contains("تقارير المواد الخام")
                    else -> false
                }
            }
            if (granularMatches.isNotEmpty()) {
                granularMatches
            } else if (userPerms.contains("التقارير")) {
                allReportTabs
            } else {
                emptyList()
            }
        }
    }

    var selectedReportIdx by remember { mutableIntStateOf(0) }
    LaunchedEffect(reportOptions) {
        if (selectedReportIdx >= reportOptions.size) {
            selectedReportIdx = 0
        }
    }
    val format = remember { NumberFormat.getNumberInstance(Locale.US) }
    val selectedReportType = reportOptions.getOrElse(selectedReportIdx.coerceIn(0, (reportOptions.size - 1).coerceAtLeast(0))) { "" }

    var selectedRawSubTab by remember { mutableIntStateOf(0) }
    val rawSubTabs = listOf("رصيد المخزن الحالي", "مشتريات الخامات", "المنصرف للإنتاج")

    var selectedPeriodFilter by remember { mutableStateOf("الكل") }
    val periodFilters = listOf("الكل", "اليوم", "7 أيام", "30 يوم")

    val now = System.currentTimeMillis()
    val isWithinPeriod: (Long) -> Boolean = { timestamp ->
        when (selectedPeriodFilter) {
            "اليوم" -> (now - timestamp) <= 24 * 3600 * 1000L
            "7 أيام" -> (now - timestamp) <= 7 * 24 * 3600 * 1000L
            "30 يوم" -> (now - timestamp) <= 30 * 24 * 3600 * 1000L
            else -> true
        }
    }

    val rawItemMap = remember(factoryItems, factoryMoves) {
        val itemsList = factoryItems.filter {
            it.category.contains("خام", ignoreCase = true) ||
            it.category.contains("تعبئة", ignoreCase = true) ||
            it.category == "raw"
        }.ifEmpty { factoryItems }

        val allNames = (itemsList.map { it.name } + factoryMoves.map { it.itemName }).distinct().filter { it.isNotBlank() }

        allNames.map { name ->
            val matchedEntity = itemsList.firstOrNull { it.name == name }
            val unit = matchedEntity?.unit ?: "كجم"
            val category = matchedEntity?.category ?: "خامات إنتاج"

            val totalPurchased = factoryMoves
                .filter { it.itemName == name && (it.kind == "RAW_PURCHASE" || it.kind == "RAW_RECEIPT") }
                .sumOf { it.qty }
            val totalIssued = factoryMoves
                .filter { it.itemName == name && it.kind == "ISSUE_PRODUCTION" }
                .sumOf { it.qty }
            val currentBalance = (totalPurchased - totalIssued).coerceAtLeast(0.0)

            RawStockItemSummary(
                name = name,
                unit = unit,
                category = category,
                purchasedQty = totalPurchased,
                issuedQty = totalIssued,
                balance = currentBalance
            )
        }
    }

    val purchaseMoves = remember(factoryMoves, selectedPeriodFilter) {
        factoryMoves.filter {
            (it.kind == "RAW_PURCHASE" || it.kind == "RAW_RECEIPT") &&
            isWithinPeriod(it.createdAt)
        }
    }

    val productionMoves = remember(factoryMoves, selectedPeriodFilter) {
        factoryMoves.filter {
            it.kind == "ISSUE_PRODUCTION" && isWithinPeriod(it.createdAt)
        }
    }

    if (reportOptions.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = PmpBrandRed,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "صلاحيات التقارير مقيدة",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = TextDark
                    )
                    Text(
                        text = "لم يحدد مدير النظام أي صلاحيات لعرض تقارير المبيعات أو المواد الخام أو الخزن لحسابك. يرجى التواصل مع الإدارة لمنحك الصلاحيات المصرح بها.",
                        fontSize = 13.sp,
                        color = TextMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 24.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "التقارير المالية والتشغيلية",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "استعراض الإحصائيات الدقيقة للمبيعات، المديونيات، وحركات الخزن والمخازن.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    GlassTabRow(
                        tabs = reportOptions,
                        selectedIndex = selectedReportIdx,
                        onTabSelected = { selectedReportIdx = it }
                    )
                }
            }
        }

        when (selectedReportType) {
            "المبيعات" -> {
                val activeInvs = invoices.filter { it.status == "active" }
                val totalSales = activeInvs.sumOf { it.total }

                item {
                    GlassStatCard(
                        title = "إجمالي قيمة المبيعات النشطة",
                        value = format.format(totalSales),
                        unit = "مجموع الفواتير",
                        subtitle = "${activeInvs.size} فاتورة صادرة",
                        accentColor = PmpCyan,
                        icon = Icons.Default.Assessment
                    )
                }

                items(activeInvs, key = { it.id }) { inv ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 10.dp)
                            ) {
                                Text(
                                    text = "${inv.invoiceNo} • ${inv.customerName}",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "التاريخ: ${inv.invoiceDate} • خصم: ${format.format(inv.discount)} ${inv.currency}",
                                    color = TextMuted,
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "${format.format(inv.total)} ${inv.currency}",
                                color = PmpGold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            "المديونيات" -> {
                item {
                    Text(
                        text = "المديونيات المتبقية على العملاء",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(customers, key = { it.id }) { c ->
                    val custInvoices = invoices.filter { it.customerId == c.id && it.status == "active" }
                    val totalDebt = custInvoices.sumOf { inv ->
                        val paid = invoicePayments.filter { it.invoiceId == inv.id && it.status == "active" }.sumOf { it.amount }
                        (inv.total - paid).coerceAtLeast(0.0)
                    }

                    if (totalDebt > 0) {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 10.dp)
                                ) {
                                    Text(
                                        text = c.name,
                                        color = TextWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "هاتف: ${c.phone.ifEmpty { "—" }}",
                                        color = TextMuted,
                                        fontSize = 12.sp,
                                        maxLines = 1
                                    )
                                }
                                Text(
                                    text = "${format.format(totalDebt)} متبقي",
                                    color = PmpRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            "حركة المخزون" -> {
                item {
                    Text(
                        text = "حركات الوارد والصرف والمبيعات للمنتجات",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(stockMoves.take(20), key = { it.id }) { sm ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 10.dp)
                            ) {
                                Text(
                                    text = sm.note.ifBlank { "حركة رقم #${sm.id}" },
                                    color = TextWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "نوع: ${sm.kind} • مخزن #${sm.warehouseId}",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
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
            }

            "حركات الخزن" -> {
                items(movements.take(20), key = { it.id }) { m ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 10.dp)
                            ) {
                                Text(
                                    text = m.description,
                                    color = TextWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${m.cashbox} • ${m.txDate} • مرجع: ${m.reference}",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = (if (m.direction == "وارد") "+" else "-") + format.format(m.amount) + " " + m.currency,
                                color = if (m.direction == "وارد") PmpGreen else PmpRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
            "تقارير المواد الخام" -> {
                // Sub-tabs for raw material reports
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassTabRow(
                            tabs = rawSubTabs,
                            selectedIndex = selectedRawSubTab,
                            onTabSelected = { selectedRawSubTab = it }
                        )

                        // If viewing purchases or production, show period filter chips
                        if (selectedRawSubTab != 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "الفترة:",
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                periodFilters.forEach { filter ->
                                    val isSelected = selectedPeriodFilter == filter
                                    val chipShape = RoundedCornerShape(8.dp)
                                    Box(
                                        modifier = Modifier
                                            .clip(chipShape)
                                            .background(if (isSelected) PmpCyan.copy(alpha = 0.2f) else Color(0xFFF1F5F9))
                                            .border(
                                                width = 1.dp,
                                                color = if (isSelected) PmpCyan else GlassBorder,
                                                shape = chipShape
                                            )
                                            .clickable { selectedPeriodFilter = filter }
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = filter,
                                            color = if (isSelected) PmpCyan else TextWhite,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                when (selectedRawSubTab) {
                    0 -> {
                        // 1. كمية المواد الخام الموجودة في المخزن (رصيد المخزن الحالي لكل مادة خام)
                        val totalStockQty = rawItemMap.sumOf { it.balance }
                        val activeItemsCount = rawItemMap.count { it.balance > 0 }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassStatCard(
                                    title = "إجمالي رصيد الخامات بالمخزن",
                                    value = "${format.format(totalStockQty.toLong())}",
                                    unit = "كجم / وحدة",
                                    subtitle = "$activeItemsCount صنف متوفر حالياً",
                                    accentColor = PmpGreen,
                                    icon = Icons.Default.Inventory2,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassStatCard(
                                    title = "إجمالي أصناف المواد الخام",
                                    value = "${rawItemMap.size}",
                                    unit = "صنف مسجل",
                                    subtitle = "مخزن الخامات الرئيسي",
                                    accentColor = PmpCyan,
                                    icon = Icons.Default.Category,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        if (rawItemMap.isEmpty()) {
                            item {
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.Inventory2, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("لا توجد أصناف مواد خام مسجلة حالياً بالمخزن", color = TextMuted, fontSize = 13.sp)
                                    }
                                }
                            }
                        } else {
                            items(rawItemMap, key = { it.name }) { item ->
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
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
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(PmpCyan.copy(alpha = 0.15f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.Category, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(18.dp))
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column {
                                                    Text(
                                                        text = item.name,
                                                        color = TextWhite,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp
                                                    )
                                                    Text(
                                                        text = "تصنيف: ${item.category}",
                                                        color = TextMuted,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }

                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = "${format.format(item.balance.toLong())} ${item.unit}",
                                                    color = if (item.balance > 0) PmpGreen else PmpRed,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp
                                                )
                                                GlassBadge(
                                                    text = if (item.balance > 100) "متوفر بكثرة" else if (item.balance > 0) "رصيد متاح" else "رصيد منتهي",
                                                    color = if (item.balance > 100) PmpGreen else if (item.balance > 0) PmpCyan else PmpRed
                                                )
                                            }
                                        }

                                        HorizontalDivider(color = GlassBorder.copy(alpha = 0.5f), thickness = 0.5.dp)

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "إجمالي الوارد/المشترى: ${format.format(item.purchasedQty.toLong())} ${item.unit}",
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                text = "المنصرف للإنتاج: ${format.format(item.issuedQty.toLong())} ${item.unit}",
                                                color = PmpGold,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // 2. كمية المواد التي تم شراؤها خلال الفترة الزمنية المحددة
                        val totalPurchasedQty = purchaseMoves.sumOf { it.qty }

                        item {
                            GlassStatCard(
                                title = "إجمالي كمية المواد المشتراة ($selectedPeriodFilter)",
                                value = format.format(totalPurchasedQty.toLong()),
                                unit = "وحدة / كجم",
                                subtitle = "${purchaseMoves.size} عملية شراء وتوريد معتمدة",
                                accentColor = PmpCyan,
                                icon = Icons.Default.ShoppingCart
                            )
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
                                        Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("لا توجد مشتريات خامات مسجلة خلال هذه الفترة ($selectedPeriodFilter)", color = TextMuted, fontSize = 13.sp)
                                    }
                                }
                            }
                        } else {
                            items(purchaseMoves, key = { it.id }) { move ->
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 10.dp)
                                        ) {
                                            Text(
                                                text = move.itemName,
                                                color = TextWhite,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            val supplierText = if (move.counterparty.isNotBlank()) "المورد: ${move.counterparty}" else "المورد: مورد محلي معتمد"
                                            val actorText = if (move.actor.isNotBlank()) " • المسؤول: ${move.actor}" else ""
                                            Text(
                                                text = "$supplierText$actorText",
                                                color = TextMuted,
                                                fontSize = 11.5.sp
                                            )
                                            Text(
                                                text = "التاريخ: ${move.moveDate}" + if (move.note.isNotBlank()) " • ${move.note}" else "",
                                                color = TextMuted,
                                                fontSize = 10.5.sp
                                            )
                                        }

                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "+${format.format(move.qty.toLong())}",
                                                color = PmpGreen,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp
                                            )
                                            GlassBadge(
                                                text = if (move.kind == "RAW_PURCHASE") "فاتورة شراء" else "استلام مخزن",
                                                color = PmpCyan
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        // 3. كمية المواد التي خرجت للإنتاج
                        val totalIssuedQty = productionMoves.sumOf { it.qty }

                        item {
                            GlassStatCard(
                                title = "إجمالي كمية المواد المنصرفة للإنتاج ($selectedPeriodFilter)",
                                value = format.format(totalIssuedQty.toLong()),
                                unit = "كجم منصرف",
                                subtitle = "${productionMoves.size} أمر صرف لتشغيل خطوط المصنع",
                                accentColor = PmpGold,
                                icon = Icons.Default.PrecisionManufacturing
                            )
                        }

                        if (productionMoves.isEmpty()) {
                            item {
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.PrecisionManufacturing, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("لا توجد كميات منصرفة للإنتاج مسجلة خلال هذه الفترة ($selectedPeriodFilter)", color = TextMuted, fontSize = 13.sp)
                                    }
                                }
                            }
                        } else {
                            items(productionMoves, key = { it.id }) { move ->
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 10.dp)
                                        ) {
                                            Text(
                                                text = move.itemName,
                                                color = TextWhite,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            val lineText = if (move.machineName?.isNotBlank() == true) "الخط/الماكينة: ${move.machineName}" else "خط الإنتاج العام"
                                            val engineerText = if (move.actor.isNotBlank()) " • المستلم: ${move.actor}" else ""
                                            Text(
                                                text = "$lineText$engineerText",
                                                color = TextMuted,
                                                fontSize = 11.5.sp
                                            )
                                            val batchText = if (move.batch.isNotBlank()) " • التشغيلة: ${move.batch}" else ""
                                            Text(
                                                text = "التاريخ: ${move.moveDate}$batchText" + if (move.note.isNotBlank()) " • ${move.note}" else "",
                                                color = TextMuted,
                                                fontSize = 10.5.sp
                                            )
                                        }

                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "-${format.format(move.qty.toLong())}",
                                                color = PmpRed,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp
                                            )
                                            GlassBadge(
                                                text = "صرف للتشغيل",
                                                color = PmpGold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}
