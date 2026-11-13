package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.InvoiceEntity
import com.example.data.local.getEffectiveSalePrice
import com.example.data.local.resolvePaymentCurrency
import com.example.hasReportTabPermission
import com.example.isSystemManager
import com.example.parsedPermissionsSet
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import com.example.util.PvcEngine
import com.example.util.StatementPdfAndExcelExporter
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class RawMaterialReportRow(
    val name: String,
    val unit: String,
    val category: String,
    val incomingPeriodQty: Double,
    val outgoingPeriodQty: Double,
    val totalPurchasedQty: Double,
    val totalIssuedQty: Double,
    val availableQty: Double,
    val unitCostEgp: Double,
    val totalInventoryValueEgp: Double,
    val isLowStock: Boolean,
    val lastMoveDate: String
)

data class FinishedProductReportRow(
    val productId: Long,
    val name: String,
    val unit: String,
    val producedQty: Double,
    val soldOrDispatchedQty: Double,
    val remainingQty: Double,
    val unitPriceSdg: Double,
    val unitPriceEgp: Double,
    val inventoryValueSdg: Double,
    val inventoryValueEgp: Double,
    val lastMovementDate: String,
    val isLowStock: Boolean
)

data class CustomerDebtReportRow(
    val customerId: Long,
    val customerName: String,
    val phone: String,
    val currency: String,
    val totalCreditSales: Double,
    val totalPayments: Double,
    val remainingBalance: Double,
    val lastPaymentDate: String,
    val debtStatus: String,
    val invoicesCount: Int
)

private data class PendingExcelExportPayload(
    val title: String,
    val subtitle: String,
    val headers: List<String>,
    val rows: List<List<String>>,
    val summaryRows: List<List<String>>
)

@Composable
fun ReportsScreen(
    viewModel: PmpViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentUser by viewModel.currentUser.collectAsState()
    val isManager = currentUser.isSystemManager()
    val userPerms = remember(currentUser) { currentUser.parsedPermissionsSet() }

    val invoices by viewModel.invoices.collectAsState()
    val invoiceItems by viewModel.invoiceItems.collectAsState()
    val invoicePayments by viewModel.invoicePayments.collectAsState()
    val stockMoves by viewModel.stockMoves.collectAsState()
    val movements by viewModel.movements.collectAsState()
    val customers by viewModel.customers.collectAsState()
    val products by viewModel.products.collectAsState()
    val warehouses by viewModel.warehouses.collectAsState()
    val factoryMoves by viewModel.factoryMoves.collectAsState()
    val factoryItems by viewModel.factoryItems.collectAsState()
    val productionBatches by viewModel.productionBatches.collectAsState()
    val formulations by viewModel.formulations.collectAsState()
    val halfaInvoices by viewModel.halfaInvoices.collectAsState()
    val isInitialDataLoading by viewModel.isInitialDataLoading.collectAsState()
    val dataLoadError by viewModel.dataLoadError.collectAsState()

    val allReportTabs = listOf(
        "مبيعات الشهر الحالي",
        "المبيعات",
        "المديونيات",
        "المواد الخام في المخزن",
        "المنتج التام",
        "حركة المخزون",
        "حركات الخزن"
    )

    val reportOptions = remember(currentUser, userPerms) {
        if (isManager || userPerms.contains("التقارير (كاملة)")) {
            allReportTabs
        } else {
            val granularMatches = allReportTabs.filter { tabName ->
                currentUser.hasReportTabPermission(tabName)
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

    var selectedReportIdx by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(reportOptions) {
        if (selectedReportIdx >= reportOptions.size) {
            selectedReportIdx = 0
        }
    }

    val format = remember { NumberFormat.getNumberInstance(Locale.US) }
    val selectedReportType = reportOptions.getOrElse(
        selectedReportIdx.coerceIn(0, (reportOptions.size - 1).coerceAtLeast(0))
    ) { "" }

    // Filters & Search state (preserved across recompositions and modal views)
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedPeriodFilter by rememberSaveable { mutableStateOf("الكل") }
    val periodFilters = listOf("الكل", "اليوم", "7 أيام", "30 يوم")

    var selectedRawSubTab by rememberSaveable { mutableIntStateOf(0) }
    val rawSubTabs = listOf("رصيد المخزن الحالي", "مشتريات الخامات", "المنصرف للإنتاج")

    var selectedDebtFilter by rememberSaveable { mutableStateOf("المديونيات القائمة") }
    val debtFilterOptions = listOf("المديونيات القائمة", "الكل", "مسدد بالكامل")

    // Current Month Sales date filter
    val currentMonthPrefix = remember {
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
    }
    val previousMonthPrefix = remember {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -1)
        SimpleDateFormat("yyyy-MM", Locale.US).format(cal.time)
    }
    val todayDateStr = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
    var monthFilterMode by rememberSaveable { mutableStateOf("الشهر الحالي") }
    var customStartDate by rememberSaveable { mutableStateOf("$currentMonthPrefix-01") }
    var customEndDate by rememberSaveable { mutableStateOf(todayDateStr) }

    // Invoice Details Modal State
    var selectedInvoiceIdForDetail by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedInvoiceForDetail: InvoiceEntity? = remember(selectedInvoiceIdForDetail, invoices) {
        selectedInvoiceIdForDetail?.let { targetId ->
            invoices.firstOrNull { it.id == targetId }
        }
    }

    // Excel Save-As Launcher
    var pendingExcelPayload by remember { mutableStateOf<PendingExcelExportPayload?>(null) }
    val saveExcelDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        val payload = pendingExcelPayload
        if (uri != null && payload != null) {
            StatementPdfAndExcelExporter.exportReportToExcel(
                context = context,
                reportTitle = payload.title,
                subtitle = payload.subtitle,
                headers = payload.headers,
                rows = payload.rows,
                summaryRows = payload.summaryRows,
                customOutputUri = uri,
                shareAfterExport = false
            ).onSuccess {
                viewModel.postMessage("تم حفظ ملف Excel (${payload.title}) على الهاتف بنجاح")
            }.onFailure { err ->
                viewModel.postMessage("تعذر حفظ ملف Excel: ${err.message}", isError = true)
            }
        }
    }

    val now = System.currentTimeMillis()
    val isWithinPeriod: (Long) -> Boolean = { timestamp ->
        when (selectedPeriodFilter) {
            "اليوم" -> (now - timestamp) <= 24 * 3600 * 1000L
            "7 أيام" -> (now - timestamp) <= 7 * 24 * 3600 * 1000L
            "30 يوم" -> (now - timestamp) <= 30 * 24 * 3600 * 1000L
            else -> true
        }
    }

    // --- 1. Computed Data: Raw Materials in Warehouse Report ---
    val rawMaterialRows = remember(factoryItems, factoryMoves, formulations, selectedPeriodFilter, searchQuery) {
        // Extract unit costs from formulations or purchase notes when available
        val costByItemName = mutableMapOf<String, Double>()
        formulations.forEach { form ->
            runCatching {
                PvcEngine.jsonToIngredients(form.ingredientsJson).forEach { ing ->
                    if (ing.unitCost > 0) {
                        costByItemName[ing.name.trim().lowercase()] = ing.unitCost
                    }
                }
            }
        }
        factoryMoves.filter { it.kind == "RAW_PURCHASE" || it.kind == "RAW_RECEIPT" }.forEach { m ->
            // Extract amount from note if formatted like "مبلغ الشراء: 15000 EGP"
            val match = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:EGP|SDG|USD)", RegexOption.IGNORE_CASE).find(m.note)
            val totalAmt = match?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            if (totalAmt != null && m.qty > 0) {
                costByItemName[m.itemName.trim().lowercase()] = totalAmt / m.qty
            }
        }

        val itemsList = factoryItems.filter {
            !it.category.equals("finished", ignoreCase = true) &&
                !it.category.contains("إنتاج تام", ignoreCase = true)
        }.ifEmpty { factoryItems }

        val rawMoveItemNames = factoryMoves
            .filter {
                it.kind == "RAW_PURCHASE" ||
                    it.kind == "RAW_RECEIPT" ||
                    it.kind == "ISSUE_PRODUCTION" ||
                    it.kind.contains("خام", ignoreCase = true)
            }
            .map { it.itemName.trim() }

        val allNames = (itemsList.map { it.name.trim() } + rawMoveItemNames)
            .distinct()
            .filter { it.isNotBlank() }

        allNames.map { name ->
            val matchedEntity = itemsList.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
            val unit = matchedEntity?.unit ?: "كجم"
            val category = matchedEntity?.category ?: "خامات إنتاج"

            val itemMoves = factoryMoves.filter { it.itemName.trim().equals(name, ignoreCase = true) }
            val incomingAll = itemMoves
                .filter { it.kind == "RAW_PURCHASE" || it.kind == "RAW_RECEIPT" || it.kind.contains("وارد") || it.kind.contains("شراء") }
                .sumOf { it.qty }
            val outgoingAll = itemMoves
                .filter { it.kind == "ISSUE_PRODUCTION" || it.kind.contains("صرف") }
                .sumOf { it.qty }

            val incomingPeriod = itemMoves
                .filter {
                    (it.kind == "RAW_PURCHASE" || it.kind == "RAW_RECEIPT" || it.kind.contains("وارد") || it.kind.contains("شراء")) &&
                        isWithinPeriod(it.createdAt)
                }
                .sumOf { it.qty }
            val outgoingPeriod = itemMoves
                .filter {
                    (it.kind == "ISSUE_PRODUCTION" || it.kind.contains("صرف")) &&
                        isWithinPeriod(it.createdAt)
                }
                .sumOf { it.qty }

            val available = (incomingAll - outgoingAll).coerceAtLeast(0.0)
            val unitCost = costByItemName[name.lowercase()] ?: 0.0
            val totalVal = available * unitCost
            val lastDate = itemMoves.maxByOrNull { it.createdAt }?.moveDate ?: "—"

            RawMaterialReportRow(
                name = name,
                unit = unit,
                category = category,
                incomingPeriodQty = incomingPeriod,
                outgoingPeriodQty = outgoingPeriod,
                totalPurchasedQty = incomingAll,
                totalIssuedQty = outgoingAll,
                availableQty = available,
                unitCostEgp = unitCost,
                totalInventoryValueEgp = totalVal,
                isLowStock = available <= 50.0,
                lastMoveDate = lastDate
            )
        }.filter { row ->
            searchQuery.isBlank() ||
                row.name.contains(searchQuery.trim(), ignoreCase = true) ||
                row.category.contains(searchQuery.trim(), ignoreCase = true)
        }
    }

    val purchaseMoves = remember(factoryMoves, selectedPeriodFilter, searchQuery) {
        factoryMoves.filter {
            (it.kind == "RAW_PURCHASE" || it.kind == "RAW_RECEIPT") &&
                isWithinPeriod(it.createdAt) &&
                (searchQuery.isBlank() ||
                    it.itemName.contains(searchQuery.trim(), ignoreCase = true) ||
                    it.counterparty.contains(searchQuery.trim(), ignoreCase = true))
        }
    }

    val productionMoves = remember(factoryMoves, selectedPeriodFilter, searchQuery) {
        factoryMoves.filter {
            (it.kind == "ISSUE_PRODUCTION" || it.kind.contains("صرف خامات")) &&
                isWithinPeriod(it.createdAt) &&
                (searchQuery.isBlank() ||
                    it.itemName.contains(searchQuery.trim(), ignoreCase = true) ||
                    it.batch.contains(searchQuery.trim(), ignoreCase = true))
        }
    }

    // --- 2. Computed Data: Finished Products Report ---
    val finishedProductRows = remember(products, stockMoves, productionBatches, factoryMoves, halfaInvoices, searchQuery) {
        val rows = mutableListOf<FinishedProductReportRow>()

        // 2a. Commercial finished products in warehouses
        for (prod in products) {
            val prodMoves = stockMoves.filter { it.productId == prod.id }
            val inQty = prodMoves.filter { it.kind in listOf("IN", "RETURN", "RETURN_IN", "CANCEL", "وارد", "مرتجع") }.sumOf { it.quantity }
            val outQty = prodMoves.filter { it.kind !in listOf("IN", "RETURN", "RETURN_IN", "CANCEL", "وارد", "مرتجع") }.sumOf { it.quantity }

            // Also check factory batches or dispatches matching product name
            val batchCartons = productionBatches
                .filter { it.productName.trim().equals(prod.name.trim(), ignoreCase = true) }
                .sumOf { it.cartonsCount.toDouble() }

            val totalProduced = inQty + batchCartons
            val remaining = (inQty - outQty).coerceAtLeast(0.0)
            val sPriceSdg = prod.getEffectiveSalePrice("SDG")
            val sPriceEgp = prod.getEffectiveSalePrice("EGP")
            val lastMoveMs = prodMoves.maxOfOrNull { it.createdAt } ?: System.currentTimeMillis()
            val lastDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(lastMoveMs))

            rows.add(
                FinishedProductReportRow(
                    productId = prod.id,
                    name = "${prod.name} (${prod.packageWeight} جم)",
                    unit = prod.unit.ifBlank { "كرتونة" },
                    producedQty = totalProduced,
                    soldOrDispatchedQty = outQty,
                    remainingQty = remaining,
                    unitPriceSdg = sPriceSdg,
                    unitPriceEgp = sPriceEgp,
                    inventoryValueSdg = remaining * sPriceSdg,
                    inventoryValueEgp = remaining * sPriceEgp,
                    lastMovementDate = lastDateStr,
                    isLowStock = remaining <= prod.minimumStock
                )
            )
        }

        // 2b. Factory production batches / finished items not already in products catalog
        val catalogNames = products.map { it.name.trim().lowercase() }.toSet()
        val extraBatchGroups = productionBatches
            .filter { it.productName.trim().lowercase() !in catalogNames }
            .groupBy { it.productName.trim() }

        for ((batchProdName, batches) in extraBatchGroups) {
            if (batchProdName.isBlank()) continue
            val producedKg = batches.sumOf { if (it.actualWeightKg > 0) it.actualWeightKg else it.targetWeightKg }
            val dispatchedKg = factoryMoves
                .filter {
                    it.itemName.trim().equals(batchProdName, ignoreCase = true) &&
                        (it.kind == "DISPATCH_HALFA" || it.kind.contains("صرف") || it.kind.contains("ترحيل"))
                }
                .sumOf { it.qty }
            val remKg = (producedKg - dispatchedKg).coerceAtLeast(0.0)
            val lastBatchDate = batches.maxByOrNull { it.createdAt }?.productionDate ?: "—"

            rows.add(
                FinishedProductReportRow(
                    productId = -(batches.first().id),
                    name = batchProdName,
                    unit = "كجم / تشغيلة",
                    producedQty = producedKg,
                    soldOrDispatchedQty = dispatchedKg,
                    remainingQty = remKg,
                    unitPriceSdg = 0.0,
                    unitPriceEgp = 0.0,
                    inventoryValueSdg = 0.0,
                    inventoryValueEgp = 0.0,
                    lastMovementDate = lastBatchDate,
                    isLowStock = remKg <= 0.0
                )
            )
        }

        rows.filter { r ->
            searchQuery.isBlank() || r.name.contains(searchQuery.trim(), ignoreCase = true)
        }
    }

    // --- 3. Computed Data: Customer Debts Report ---
    val customerDebtRows = remember(customers, invoices, invoicePayments, searchQuery, selectedDebtFilter) {
        val activeInvoices = invoices.filter { it.status == "active" }
        val activePayments = invoicePayments.filter { it.status == "active" }

        customers.map { c ->
            val custInvs = activeInvoices.filter { it.customerId == c.id }
            val invIds = custInvs.map { it.id }.toSet()
            val custPayments = activePayments.filter { it.invoiceId in invIds }

            val totalSales = custInvs.sumOf { it.total }
            val totalPaid = custPayments.sumOf { it.amount }
            val remaining = (totalSales - totalPaid).coerceAtLeast(0.0)
            val primaryCurrency = custInvs.firstOrNull()?.currency ?: "SDG"
            val lastPayDate = custPayments.maxByOrNull { it.createdAt }?.payDate ?: "لا توجد دفعات"
            val status = when {
                custInvs.isEmpty() -> "لا توجد مسحوبات"
                remaining <= 0.0 -> "مسدد بالكامل"
                totalPaid > 0.0 -> "مديونية قائمة (سداد جزئي)"
                else -> "مديونية مستحقة (بدون سداد)"
            }

            CustomerDebtReportRow(
                customerId = c.id,
                customerName = c.name,
                phone = c.phone.ifBlank { "—" },
                currency = primaryCurrency,
                totalCreditSales = totalSales,
                totalPayments = totalPaid,
                remainingBalance = remaining,
                lastPaymentDate = lastPayDate,
                debtStatus = status,
                invoicesCount = custInvs.size
            )
        }.filter { row ->
            val matchesQuery = searchQuery.isBlank() ||
                row.customerName.contains(searchQuery.trim(), ignoreCase = true) ||
                row.phone.contains(searchQuery.trim(), ignoreCase = true)
            val matchesFilter = when (selectedDebtFilter) {
                "المديونيات القائمة" -> row.remainingBalance > 0.0
                "مسدد بالكامل" -> row.remainingBalance <= 0.0 && row.totalCreditSales > 0.0
                else -> true
            }
            matchesQuery && matchesFilter
        }.sortedByDescending { it.remainingBalance }
    }

    // --- 4. Computed Data: Current Month Sales Report (with strict Sales vs Collections separation) ---
    val isDateInMonthRange: (String) -> Boolean = remember(monthFilterMode, currentMonthPrefix, previousMonthPrefix, customStartDate, customEndDate) {
        { dateStr ->
            val clean = dateStr.trim()
            when (monthFilterMode) {
                "الشهر الحالي" -> clean.startsWith(currentMonthPrefix)
                "الشهر السابق" -> clean.startsWith(previousMonthPrefix)
                "تخصيص التاريخ" -> {
                    val start = customStartDate.trim().ifBlank { "0000-01-01" }
                    val end = customEndDate.trim().ifBlank { "9999-12-31" }
                    clean >= start && clean <= end
                }
                else -> true
            }
        }
    }

    val currentMonthInvoices = remember(invoices, isDateInMonthRange, searchQuery) {
        invoices.filter { inv ->
            inv.status == "active" &&
                isDateInMonthRange(inv.invoiceDate) &&
                (searchQuery.isBlank() ||
                    inv.invoiceNo.contains(searchQuery.trim(), ignoreCase = true) ||
                    inv.customerName.contains(searchQuery.trim(), ignoreCase = true))
        }
    }

    val activePaymentsByInvoice = remember(invoicePayments) {
        invoicePayments.filter { it.status == "active" }.groupBy { it.invoiceId }
    }

    // Helper function to trigger Export / Share for the active tab
    fun buildActiveReportExcelPayload(): PendingExcelExportPayload {
        val printDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return when (selectedReportType) {
            "مبيعات الشهر الحالي" -> {
                val totalGross = currentMonthInvoices.sumOf { it.subtotal }
                val totalDiscount = currentMonthInvoices.sumOf { it.discount }
                val totalNet = currentMonthInvoices.sumOf { it.total }
                var totalPaidOnMonthInvs = 0.0
                val rows = currentMonthInvoices.map { inv ->
                    val invPays = activePaymentsByInvoice[inv.id].orEmpty()
                    val paid = invPays.sumOf { it.amount }.coerceAtMost(inv.total)
                    val rem = (inv.total - paid).coerceAtLeast(0.0)
                    totalPaidOnMonthInvs += paid
                    val payKind = if (rem <= 0.0) "نقدية / مسددة بالكامل" else if (paid > 0.0) "آجلة (سداد جزئي)" else "آجلة"
                    listOf(
                        inv.invoiceNo,
                        inv.invoiceDate,
                        inv.customerName,
                        payKind,
                        inv.subtotal.toString(),
                        inv.discount.toString(),
                        inv.total.toString(),
                        paid.toString(),
                        rem.toString(),
                        inv.currency,
                        inv.createdBy.ifBlank { "المدير" }
                    )
                }
                val totalRem = (totalNet - totalPaidOnMonthInvs).coerceAtLeast(0.0)
                PendingExcelExportPayload(
                    title = "تقرير_مبيعات_الشهر_الحالي",
                    subtitle = "الفترة: $monthFilterMode | تاريخ الاستخراج: $printDate | عدد الفواتير: ${currentMonthInvoices.size}",
                    headers = listOf(
                        "رقم الفاتورة",
                        "التاريخ",
                        "اسم العميل",
                        "نوع البيع / الحالة",
                        "الإجمالي قبل الخصم",
                        "الخصم",
                        "صافي الفاتورة",
                        "المحصل",
                        "المتبقي (غير محصل)",
                        "العملة",
                        "الموظف المنفذ"
                    ),
                    rows = rows,
                    summaryRows = listOf(
                        listOf(
                            "الإجمالي الكلي",
                            "${currentMonthInvoices.size} فاتورة",
                            "",
                            "",
                            format.format(totalGross),
                            format.format(totalDiscount),
                            format.format(totalNet),
                            format.format(totalPaidOnMonthInvs),
                            format.format(totalRem),
                            "",
                            ""
                        )
                    )
                )
            }

            "المبيعات" -> {
                val activeInvs = invoices.filter {
                    it.status == "active" &&
                        (searchQuery.isBlank() ||
                            it.invoiceNo.contains(searchQuery.trim(), ignoreCase = true) ||
                            it.customerName.contains(searchQuery.trim(), ignoreCase = true))
                }
                val totalNet = activeInvs.sumOf { it.total }
                val totalDisc = activeInvs.sumOf { it.discount }
                var totalPaidAll = 0.0
                val rows = activeInvs.map { inv ->
                    val paid = activePaymentsByInvoice[inv.id].orEmpty().sumOf { it.amount }.coerceAtMost(inv.total)
                    val rem = (inv.total - paid).coerceAtLeast(0.0)
                    totalPaidAll += paid
                    val cashboxUsed = activePaymentsByInvoice[inv.id]?.firstOrNull()?.cashbox ?: "آجل"
                    listOf(
                        inv.invoiceNo,
                        inv.invoiceDate,
                        inv.customerName,
                        inv.subtotal.toString(),
                        inv.discount.toString(),
                        inv.total.toString(),
                        paid.toString(),
                        rem.toString(),
                        inv.currency,
                        cashboxUsed,
                        inv.createdBy.ifBlank { "المدير" }
                    )
                }
                PendingExcelExportPayload(
                    title = "تقرير_المبيعات_الشامل",
                    subtitle = "تاريخ الاستخراج: $printDate | إجمالي الفواتير: ${activeInvs.size}",
                    headers = listOf(
                        "رقم الفاتورة",
                        "تاريخ الفاتورة",
                        "اسم العميل",
                        "الإجمالي الفرعي",
                        "الخصم",
                        "الإجمالي النهائي",
                        "المدفوع",
                        "المتبقي",
                        "العملة",
                        "الخزنة / الدفع",
                        "الموظف"
                    ),
                    rows = rows,
                    summaryRows = listOf(
                        listOf(
                            "الإجمالي العام",
                            "${activeInvs.size} فاتورة",
                            "",
                            "",
                            format.format(totalDisc),
                            format.format(totalNet),
                            format.format(totalPaidAll),
                            format.format((totalNet - totalPaidAll).coerceAtLeast(0.0)),
                            "",
                            "",
                            ""
                        )
                    )
                )
            }

            "المديونيات" -> {
                val totalSales = customerDebtRows.sumOf { it.totalCreditSales }
                val totalPaid = customerDebtRows.sumOf { it.totalPayments }
                val totalRem = customerDebtRows.sumOf { it.remainingBalance }
                val rows = customerDebtRows.map { r ->
                    listOf(
                        r.customerName,
                        r.phone,
                        r.invoicesCount.toString(),
                        r.totalCreditSales.toString(),
                        r.totalPayments.toString(),
                        r.remainingBalance.toString(),
                        r.currency,
                        r.lastPaymentDate,
                        r.debtStatus
                    )
                }
                PendingExcelExportPayload(
                    title = "تقرير_المديونيات_والذمم",
                    subtitle = "تاريخ الاستخراج: $printDate | عدد العملاء بالتقرير: ${customerDebtRows.size}",
                    headers = listOf(
                        "اسم العميل",
                        "رقم الهاتف",
                        "عدد الفواتير",
                        "إجمالي المبيعات (مدين)",
                        "إجمالي المدفوعات (دائن)",
                        "الرصيد المتبقي المستحق",
                        "العملة",
                        "تاريخ آخر دفعة",
                        "حالة المديونية"
                    ),
                    rows = rows,
                    summaryRows = listOf(
                        listOf(
                            "إجمالي الديون المستحقة",
                            "${customerDebtRows.size} عميل",
                            "",
                            format.format(totalSales),
                            format.format(totalPaid),
                            format.format(totalRem),
                            "",
                            "",
                            ""
                        )
                    )
                )
            }

            "المواد الخام في المخزن" -> {
                val totalAvail = rawMaterialRows.sumOf { it.availableQty }
                val totalVal = rawMaterialRows.sumOf { it.totalInventoryValueEgp }
                val rows = rawMaterialRows.map { r ->
                    listOf(
                        r.name,
                        r.category,
                        r.unit,
                        r.incomingPeriodQty.toString(),
                        r.outgoingPeriodQty.toString(),
                        r.availableQty.toString(),
                        if (r.isLowStock) "منخفض / يحتاج توريد" else "متوفر",
                        r.unitCostEgp.toString(),
                        r.totalInventoryValueEgp.toString(),
                        r.lastMoveDate
                    )
                }
                PendingExcelExportPayload(
                    title = "تقرير_المواد_الخام_في_المخزن",
                    subtitle = "تاريخ الاستخراج: $printDate | الفترة: $selectedPeriodFilter | عدد الأصناف: ${rawMaterialRows.size}",
                    headers = listOf(
                        "اسم المادة الخام",
                        "التصنيف",
                        "وحدة القياس",
                        "الكمية الواردة (خلال الفترة)",
                        "الكمية المنصرفة للإنتاج (خلال الفترة)",
                        "الكمية الحالية المتاحة بالمخزن",
                        "حالة المخزون",
                        "تكلفة الوحدة التقديرية (EGP)",
                        "القيمة الإجمالية للمخزون (EGP)",
                        "تاريخ آخر حركة"
                    ),
                    rows = rows,
                    summaryRows = listOf(
                        listOf(
                            "الإجمالي الكلي",
                            "${rawMaterialRows.size} مادة خام",
                            "",
                            "",
                            "",
                            format.format(totalAvail),
                            "",
                            "",
                            format.format(totalVal),
                            ""
                        )
                    )
                )
            }

            "المنتج التام" -> {
                val totalProd = finishedProductRows.sumOf { it.producedQty }
                val totalSold = finishedProductRows.sumOf { it.soldOrDispatchedQty }
                val totalRem = finishedProductRows.sumOf { it.remainingQty }
                val totalValSdg = finishedProductRows.sumOf { it.inventoryValueSdg }
                val totalValEgp = finishedProductRows.sumOf { it.inventoryValueEgp }
                val rows = finishedProductRows.map { r ->
                    listOf(
                        r.name,
                        r.unit,
                        r.producedQty.toString(),
                        r.soldOrDispatchedQty.toString(),
                        r.remainingQty.toString(),
                        r.unitPriceSdg.toString(),
                        r.inventoryValueSdg.toString(),
                        r.unitPriceEgp.toString(),
                        r.inventoryValueEgp.toString(),
                        r.lastMovementDate
                    )
                }
                PendingExcelExportPayload(
                    title = "تقرير_المنتج_التام",
                    subtitle = "تاريخ الاستخراج: $printDate | عدد المنتجات التامة: ${finishedProductRows.size}",
                    headers = listOf(
                        "اسم المنتج التام",
                        "الوحدة",
                        "الكمية المنتجة / الواردة",
                        "الكمية المباعة أو المنصرفة",
                        "الكمية المتبقية في المخزن",
                        "سعر الوحدة (SDG)",
                        "قيمة المخزون (SDG)",
                        "سعر الوحدة (EGP)",
                        "قيمة المخزون (EGP)",
                        "تاريخ آخر حركة"
                    ),
                    rows = rows,
                    summaryRows = listOf(
                        listOf(
                            "الإجمالي العام",
                            "${finishedProductRows.size} منتج",
                            format.format(totalProd),
                            format.format(totalSold),
                            format.format(totalRem),
                            "",
                            format.format(totalValSdg),
                            "",
                            format.format(totalValEgp),
                            ""
                        )
                    )
                )
            }

            "حركة المخزون" -> {
                val prodMap = products.associateBy { it.id }
                val whMap = warehouses.associateBy { it.id }
                val rows = stockMoves.map { sm ->
                    val prodName = prodMap[sm.productId]?.name ?: "صنف #${sm.productId}"
                    val whName = whMap[sm.warehouseId]?.name ?: "مخزن #${sm.warehouseId}"
                    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(sm.createdAt))
                    listOf(
                        "#${sm.id}",
                        dateStr,
                        prodName,
                        whName,
                        sm.kind,
                        sm.quantity.toString(),
                        sm.note,
                        sm.createdBy.ifBlank { "المدير" }
                    )
                }
                PendingExcelExportPayload(
                    title = "تقرير_حركة_المخزون",
                    subtitle = "تاريخ الاستخراج: $printDate | إجمالي الحركات: ${stockMoves.size}",
                    headers = listOf(
                        "رقم الحركة",
                        "التاريخ",
                        "المنتج",
                        "المخزن",
                        "نوع الحركة",
                        "الكمية",
                        "البيان والملاحظات",
                        "الموظف المنفذ"
                    ),
                    rows = rows,
                    summaryRows = listOf(listOf("إجمالي الحركات المخزنية", "${stockMoves.size}"))
                )
            }

            else -> {
                val totalIn = movements.filter { it.direction == "وارد" }.sumOf { it.amount }
                val totalOut = movements.filter { it.direction != "وارد" }.sumOf { it.amount }
                val rows = movements.map { m ->
                    listOf(
                        "#${m.id}",
                        m.txDate,
                        m.cashbox,
                        m.direction,
                        m.amount.toString(),
                        m.currency,
                        m.reference,
                        m.description,
                        m.createdBy.ifBlank { "المدير" }
                    )
                }
                PendingExcelExportPayload(
                    title = "تقرير_حركات_الخزن",
                    subtitle = "تاريخ الاستخراج: $printDate | إجمالي الحركات المالية: ${movements.size}",
                    headers = listOf(
                        "رقم الحركة",
                        "التاريخ",
                        "الخزنة",
                        "الاتجاه",
                        "المبلغ",
                        "العملة",
                        "المرجع",
                        "البيان والتفاصيل",
                        "الموظف المنفذ"
                    ),
                    rows = rows,
                    summaryRows = listOf(
                        listOf(
                            "ملخص الحركات",
                            "وارد: ${format.format(totalIn)}",
                            "منصرف: ${format.format(totalOut)}",
                            "الصافي: ${format.format(totalIn - totalOut)}",
                            "",
                            "",
                            "",
                            "",
                            ""
                        )
                    )
                )
            }
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
                        text = "لم يحدد مدير النظام أي صلاحيات لعرض التقارير لحسابك. يرجى التواصل مع الإدارة لمنحك الصلاحيات المصرح بها.",
                        fontSize = 13.sp,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Header + Tabs + Excel Export & Share Bar
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 24.dp
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "التقارير المالية والمخزنية",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp
                                )
                                Text(
                                    text = "تقارير حية ودقيقة مرتبطة بقاعدة البيانات مع تصدير ومشاركة Excel",
                                    color = TextMuted,
                                    fontSize = 11.5.sp
                                )
                            }
                            GlassBadge(
                                text = selectedReportType,
                                color = PmpCyan
                            )
                        }

                        GlassTabRow(
                            tabs = reportOptions,
                            selectedIndex = selectedReportIdx,
                            onTabSelected = { selectedReportIdx = it }
                        )

                        // Search Bar across reports
                        GlassTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = "بحث سريع داخل التقرير الحالي",
                            placeholder = "ابحث باسم العميل، رقم الفاتورة، أو اسم الصنف...",
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("reports_search_input")
                        )

                        // Excel Export & Share Action Buttons ("تصدير ومشاركة Excel")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GlassButton(
                                text = "تصدير ومشاركة Excel",
                                icon = Icons.Default.Share,
                                gradient = GlassAccentGreenGradient,
                                onClick = {
                                    val payload = buildActiveReportExcelPayload()
                                    if (payload.rows.isEmpty()) {
                                        viewModel.postMessage("لا توجد سجلات في التقرير الحالي لتصديرها", isError = true)
                                        return@GlassButton
                                    }
                                    StatementPdfAndExcelExporter.exportReportToExcel(
                                        context = context,
                                        reportTitle = payload.title,
                                        subtitle = payload.subtitle,
                                        headers = payload.headers,
                                        rows = payload.rows,
                                        summaryRows = payload.summaryRows,
                                        customOutputUri = null,
                                        shareAfterExport = true
                                    ).onSuccess { savedPath ->
                                        viewModel.postMessage("تم تجهيز ملف Excel (${payload.title}) للمشاركة والحفظ في: $savedPath")
                                    }.onFailure { err ->
                                        viewModel.postMessage("تعذر تصدير ومشاركة ملف Excel: ${err.message}", isError = true)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1.25f)
                                    .heightIn(min = 46.dp)
                                    .testTag("export_and_share_excel_btn")
                            )

                            GlassButton(
                                text = "حفظ Excel",
                                icon = Icons.Default.SaveAlt,
                                gradient = GlassAccentBlueGradient,
                                onClick = {
                                    val payload = buildActiveReportExcelPayload()
                                    if (payload.rows.isEmpty()) {
                                        viewModel.postMessage("لا توجد سجلات في التقرير الحالي لحفظها", isError = true)
                                        return@GlassButton
                                    }
                                    pendingExcelPayload = payload
                                    val safeFileName = "PMP_${payload.title}_${System.currentTimeMillis() % 10000}.xlsx"
                                    saveExcelDocumentLauncher.launch(safeFileName)
                                },
                                modifier = Modifier
                                    .weight(0.75f)
                                    .heightIn(min = 46.dp)
                                    .testTag("save_excel_to_phone_btn")
                            )
                        }
                    }
                }
            }

            if (isInitialDataLoading && invoices.isEmpty() && movements.isEmpty() && products.isEmpty()) {
                item(key = "reports_shimmer_skeleton") {
                    ProvideShimmerEffect(enabled = true) {
                        ReportsSkeletonSection()
                    }
                }
            } else if (dataLoadError != null && invoices.isEmpty() && movements.isEmpty() && products.isEmpty()) {
                item(key = "reports_error_card") {
                    PmpErrorStateCard(
                        message = dataLoadError ?: "تعذر تحميل بيانات التقارير",
                        onRetry = { viewModel.retryDataLoadAndSync() }
                    )
                }
            } else when (selectedReportType) {
                // =========================================================================
                // 1. تقرير مبيعات الشهر الحالي (Current Month Sales Report)
                // =========================================================================
                "مبيعات الشهر الحالي" -> {
                    val monthInvoiceIds = currentMonthInvoices.map { it.id }.toSet()
                    val grossSalesTotal = currentMonthInvoices.sumOf { it.subtotal }
                    val totalDiscounts = currentMonthInvoices.sumOf { it.discount }
                    val netInvoicesTotal = currentMonthInvoices.sumOf { it.total }

                    // Returns recorded during the selected month range
                    val monthReturnsMoves = stockMoves.filter { sm ->
                        sm.kind in listOf("RETURN", "RETURN_IN", "CANCEL") &&
                            isDateInMonthRange(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(sm.createdAt)))
                    }
                    val prodPriceMap = products.associateBy { it.id }
                    val returnsValue = monthReturnsMoves.sumOf { sm ->
                        val p = prodPriceMap[sm.productId]
                        sm.quantity * (p?.salePrice ?: 0.0)
                    }
                    val netSalesAfterReturns = (netInvoicesTotal - returnsValue).coerceAtLeast(0.0)

                    // Cash vs Credit sales breakdown & Collected vs Uncollected on THIS month's invoices
                    var cashSalesAmount = 0.0
                    var creditSalesAmount = 0.0
                    var totalCollectedOnMonthSales = 0.0
                    var totalUncollectedOnMonthSales = 0.0

                    for (inv in currentMonthInvoices) {
                        val pays = activePaymentsByInvoice[inv.id].orEmpty()
                        val paidOnInv = pays.sumOf { it.amount }.coerceAtMost(inv.total)
                        val remOnInv = (inv.total - paidOnInv).coerceAtLeast(0.0)
                        totalCollectedOnMonthSales += paidOnInv
                        totalUncollectedOnMonthSales += remOnInv

                        // If paid in full on the invoice date -> Cash sale; otherwise Credit sale
                        val paidSameDay = pays.filter { it.payDate.trim() == inv.invoiceDate.trim() }.sumOf { it.amount }
                        if (remOnInv <= 0.0 && paidSameDay >= inv.total) {
                            cashSalesAmount += inv.total
                        } else {
                            creditSalesAmount += inv.total
                        }
                    }

                    // Subsequent debt collections during this month on OLDER invoices (Strictly separated from sales!)
                    val subsequentDebtCollections = invoicePayments.filter { pay ->
                        pay.status == "active" &&
                            isDateInMonthRange(pay.payDate) &&
                            pay.invoiceId !in monthInvoiceIds
                    }.sumOf { it.amount }

                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "تصفية فترة مبيعات الشهر:",
                                    color = PmpBrandNavy,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("الشهر الحالي", "الشهر السابق", "تخصيص التاريخ").forEach { mode ->
                                        val selected = monthFilterMode == mode
                                        FilterChip(
                                            selected = selected,
                                            onClick = { monthFilterMode = mode },
                                            label = { Text(mode, fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                if (monthFilterMode == "تخصيص التاريخ") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        GlassTextField(
                                            value = customStartDate,
                                            onValueChange = { customStartDate = it },
                                            label = "من تاريخ (YYYY-MM-DD)",
                                            placeholder = "2026-11-01",
                                            modifier = Modifier.weight(1f)
                                        )
                                        GlassTextField(
                                            value = customEndDate,
                                            onValueChange = { customEndDate = it },
                                            label = "إلى تاريخ (YYYY-MM-DD)",
                                            placeholder = "2026-11-30",
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Summary KPI Cards for Current Month Sales
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassStatCard(
                                    title = "إجمالي المبيعات ($monthFilterMode)",
                                    value = format.format(netInvoicesTotal),
                                    unit = "${currentMonthInvoices.size} فاتورة مبيعات",
                                    subtitle = "صافي المبيعات: ${format.format(netSalesAfterReturns)}",
                                    accentColor = PmpCyan,
                                    icon = Icons.Default.ShoppingCartCheckout,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassStatCard(
                                    title = "المحصل مقابل المتبقي",
                                    value = format.format(totalCollectedOnMonthSales),
                                    unit = "محصل فعلياً",
                                    subtitle = "غير محصل (آجل): ${format.format(totalUncollectedOnMonthSales)}",
                                    accentColor = PmpGreen,
                                    icon = Icons.Default.Payments,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassStatCard(
                                    title = "المبيعات النقدية والآجلة",
                                    value = "نقدي: ${format.format(cashSalesAmount)}",
                                    unit = "آجل: ${format.format(creditSalesAmount)}",
                                    subtitle = "الخصومات: ${format.format(totalDiscounts)} | المرتجعات: ${format.format(returnsValue)}",
                                    accentColor = PmpGold,
                                    icon = Icons.Default.ReceiptLong,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassStatCard(
                                    title = "تحصيلات مديونيات سابقة",
                                    value = format.format(subsequentDebtCollections),
                                    unit = "سداد فواتير سابقة",
                                    subtitle = "مفصولة محاسبياً عن مبيعات الشهر",
                                    accentColor = PmpPurple,
                                    icon = Icons.Default.AccountBalanceWallet,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            text = "قائمة فواتير الفترة (${currentMonthInvoices.size}) • اضغط على أي فاتورة لعرض التفاصيل الكاملة",
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    if (currentMonthInvoices.isEmpty()) {
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("لا توجد فواتير مبيعات مسجلة خلال هذه الفترة", color = TextMuted, fontSize = 13.sp)
                                }
                            }
                        }
                    } else {
                        items(currentMonthInvoices, key = { "cm_inv_${it.id}" }) { inv ->
                            val invPays = activePaymentsByInvoice[inv.id].orEmpty()
                            val paidAmt = invPays.sumOf { it.amount }.coerceAtMost(inv.total)
                            val remAmt = (inv.total - paidAmt).coerceAtLeast(0.0)
                            InvoiceReportItemCard(
                                inv = inv,
                                paidAmount = paidAmt,
                                remainingAmount = remAmt,
                                format = format,
                                onClick = { selectedInvoiceIdForDetail = inv.id }
                            )
                        }
                    }
                }

                // =========================================================================
                // 2. تقرير المبيعات الشامل (All Sales Report)
                // =========================================================================
                "المبيعات" -> {
                    val activeInvs = invoices.filter {
                        it.status == "active" &&
                            (searchQuery.isBlank() ||
                                it.invoiceNo.contains(searchQuery.trim(), ignoreCase = true) ||
                                it.customerName.contains(searchQuery.trim(), ignoreCase = true))
                    }
                    val totalSales = activeInvs.sumOf { it.total }
                    val totalDiscounts = activeInvs.sumOf { it.discount }

                    item {
                        GlassStatCard(
                            title = "إجمالي قيمة المبيعات النشطة",
                            value = format.format(totalSales),
                            unit = "${activeInvs.size} فاتورة صادرة",
                            subtitle = "إجمالي الخصومات الممنوحة: ${format.format(totalDiscounts)} • اضغط على أي فاتورة للتفاصيل",
                            accentColor = PmpCyan,
                            icon = Icons.Default.Assessment
                        )
                    }

                    items(activeInvs, key = { "all_inv_${it.id}" }) { inv ->
                        val invPays = activePaymentsByInvoice[inv.id].orEmpty()
                        val paidAmt = invPays.sumOf { it.amount }.coerceAtMost(inv.total)
                        val remAmt = (inv.total - paidAmt).coerceAtLeast(0.0)
                        InvoiceReportItemCard(
                            inv = inv,
                            paidAmount = paidAmt,
                            remainingAmount = remAmt,
                            format = format,
                            onClick = { selectedInvoiceIdForDetail = inv.id }
                        )
                    }
                }

                // =========================================================================
                // 3. تقرير المديونيات (Debts / Receivables Report)
                // =========================================================================
                "المديونيات" -> {
                    val totalOutstandingDebt = customerDebtRows.sumOf { it.remainingBalance }
                    val totalCreditSalesAll = customerDebtRows.sumOf { it.totalCreditSales }
                    val totalCollectedAll = customerDebtRows.sumOf { it.totalPayments }

                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassStatCard(
                                    title = "إجمالي الديون المستحقة",
                                    value = format.format(totalOutstandingDebt),
                                    unit = "${customerDebtRows.count { it.remainingBalance > 0 }} عميل مدين",
                                    subtitle = "الرصيد المتبقي الواجب تحصيله",
                                    accentColor = PmpRed,
                                    icon = Icons.Default.WarningAmber,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassStatCard(
                                    title = "إجمالي المبيعات والتحصيلات",
                                    value = format.format(totalCollectedAll),
                                    unit = "إجمالي المسدد",
                                    subtitle = "من أصل مبيعات: ${format.format(totalCreditSalesAll)}",
                                    accentColor = PmpGreen,
                                    icon = Icons.Default.PriceCheck,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                debtFilterOptions.forEach { opt ->
                                    FilterChip(
                                        selected = selectedDebtFilter == opt,
                                        onClick = { selectedDebtFilter = opt },
                                        label = { Text(opt, fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    if (customerDebtRows.isEmpty()) {
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.CheckCircleOutline, contentDescription = null, tint = PmpGreen, modifier = Modifier.size(36.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("لا توجد مديونيات مطابقة للفلتر الحالي", color = TextMuted, fontSize = 13.sp)
                                }
                            }
                        }
                    } else {
                        items(
                            customerDebtRows.distinctBy { "${it.customerId}_${it.currency}" },
                            key = { "debt_${it.customerId}_${it.currency}" }
                        ) { row ->
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
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 10.dp)
                                        ) {
                                            Text(
                                                text = row.customerName,
                                                color = TextWhite,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.5.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "هاتف: ${row.phone} • عدد الفواتير: ${row.invoicesCount}",
                                                color = TextMuted,
                                                fontSize = 11.5.sp
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "${format.format(row.remainingBalance)} ${row.currency}",
                                                color = if (row.remainingBalance > 0) PmpRed else PmpGreen,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 15.sp
                                            )
                                            GlassBadge(
                                                text = row.debtStatus,
                                                color = if (row.remainingBalance > 0) PmpRed else PmpGreen
                                            )
                                        }
                                    }

                                    HorizontalDivider(color = GlassBorder.copy(alpha = 0.5f), thickness = 0.5.dp)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "إجمالي المسحوبات: ${format.format(row.totalCreditSales)} ${row.currency}",
                                            color = TextDark,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "إجمالي المدفوعات: ${format.format(row.totalPayments)} ${row.currency}",
                                            color = PmpGreen,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    Text(
                                        text = "تاريخ آخر دفعة سداد: ${row.lastPaymentDate}",
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // =========================================================================
                // 4. تقرير المواد الخام في المخزن (Raw Materials in Warehouse Report)
                // =========================================================================
                "المواد الخام في المخزن" -> {
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
                                            .background(if (isSelected) PmpCyan.copy(alpha = 0.2f) else PmpSubtleSurface)
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

                    when (selectedRawSubTab) {
                        0 -> {
                            val totalStockQty = rawMaterialRows.sumOf { it.availableQty }
                            val totalRawValue = rawMaterialRows.sumOf { it.totalInventoryValueEgp }
                            val lowStockRawCount = rawMaterialRows.count { it.isLowStock }

                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    GlassStatCard(
                                        title = "إجمالي رصيد الخامات المتاح",
                                        value = format.format(totalStockQty),
                                        unit = "كجم / وحدة",
                                        subtitle = "${rawMaterialRows.size} صنف ($lowStockRawCount منخفض)",
                                        accentColor = PmpGreen,
                                        icon = Icons.Default.Inventory2,
                                        modifier = Modifier.weight(1f)
                                    )
                                    GlassStatCard(
                                        title = "القيمة التقديرية للمخزون",
                                        value = if (totalRawValue > 0) format.format(totalRawValue) else "حسب فواتير التوريد",
                                        unit = if (totalRawValue > 0) "EGP" else "${rawMaterialRows.size} مادة خام",
                                        subtitle = "مخزن المواد الخام الرئيسي",
                                        accentColor = PmpCyan,
                                        icon = Icons.Default.Category,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            if (rawMaterialRows.isEmpty()) {
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
                                items(rawMaterialRows, key = { "raw_${it.name}" }) { item ->
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
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .padding(end = 8.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(34.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(PmpCyan.copy(alpha = 0.15f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(Icons.Default.Science, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(18.dp))
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
                                                            text = "التصنيف: ${item.category} • الوحدة: ${item.unit}",
                                                            color = TextMuted,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                }

                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(
                                                        text = "${format.format(item.availableQty)} ${item.unit}",
                                                        color = if (item.availableQty > 0) PmpGreen else PmpRed,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontSize = 15.sp
                                                    )
                                                    GlassBadge(
                                                        text = when {
                                                            item.availableQty <= 0.0 -> "رصيد منتهي"
                                                            item.isLowStock -> "مخزون منخفض"
                                                            else -> "متوفر"
                                                        },
                                                        color = when {
                                                            item.availableQty <= 0.0 -> PmpRed
                                                            item.isLowStock -> PmpGold
                                                            else -> PmpGreen
                                                        }
                                                    )
                                                }
                                            }

                                            HorizontalDivider(color = GlassBorder.copy(alpha = 0.5f), thickness = 0.5.dp)

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "الوارد ($selectedPeriodFilter): +${format.format(item.incomingPeriodQty)} ${item.unit}",
                                                    color = PmpGreen,
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = "المنصرف ($selectedPeriodFilter): -${format.format(item.outgoingPeriodQty)} ${item.unit}",
                                                    color = PmpGold,
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = if (item.totalInventoryValueEgp > 0) {
                                                        "قيمة المخزون: ${format.format(item.totalInventoryValueEgp)} EGP"
                                                    } else {
                                                        "إجمالي التوريد الكلي: ${format.format(item.totalPurchasedQty)} ${item.unit}"
                                                    },
                                                    color = TextMuted,
                                                    fontSize = 11.sp
                                                )
                                                Text(
                                                    text = "آخر حركة: ${item.lastMoveDate}",
                                                    color = TextMuted,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        1 -> {
                            val totalPurchasedQty = purchaseMoves.sumOf { it.qty }
                            item {
                                GlassStatCard(
                                    title = "إجمالي كمية المواد المشتراة ($selectedPeriodFilter)",
                                    value = format.format(totalPurchasedQty),
                                    unit = "وحدة / كجم",
                                    subtitle = "${purchaseMoves.size} عملية شراء وتوريد معتمدة",
                                    accentColor = PmpCyan,
                                    icon = Icons.Default.ShoppingCart
                                )
                            }
                            items(purchaseMoves, key = { "raw_pur_${it.id}" }) { move ->
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
                                            Text(
                                                text = "المورد: ${move.counterparty.ifBlank { "مورد معتمد" }} • الموظف: ${move.actor.ifBlank { "المدير" }}",
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
                                                text = "+${format.format(move.qty)}",
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

                        2 -> {
                            val totalIssuedQty = productionMoves.sumOf { it.qty }
                            item {
                                GlassStatCard(
                                    title = "إجمالي كمية المواد المنصرفة للإنتاج ($selectedPeriodFilter)",
                                    value = format.format(totalIssuedQty),
                                    unit = "كجم منصرف",
                                    subtitle = "${productionMoves.size} أمر صرف لتشغيل خطوط المصنع",
                                    accentColor = PmpGold,
                                    icon = Icons.Default.PrecisionManufacturing
                                )
                            }
                            items(productionMoves, key = { "raw_iss_${it.id}" }) { move ->
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
                                            val lineText = if (!move.machineName.isNullOrBlank()) "الخط/الماكينة: ${move.machineName}" else "خط الإنتاج العام"
                                            Text(
                                                text = "$lineText • المستلم: ${move.actor.ifBlank { "مهندس الإنتاج" }}",
                                                color = TextMuted,
                                                fontSize = 11.5.sp
                                            )
                                            Text(
                                                text = "التاريخ: ${move.moveDate}" + if (move.batch.isNotBlank()) " • التشغيلة: ${move.batch}" else "",
                                                color = TextMuted,
                                                fontSize = 10.5.sp
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "-${format.format(move.qty)}",
                                                color = PmpRed,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp
                                            )
                                            GlassBadge(text = "صرف للتشغيل", color = PmpGold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // =========================================================================
                // 5. تقرير المنتج التام (Finished Products Report)
                // =========================================================================
                "المنتج التام" -> {
                    val totalProduced = finishedProductRows.sumOf { it.producedQty }
                    val totalSold = finishedProductRows.sumOf { it.soldOrDispatchedQty }
                    val totalRemaining = finishedProductRows.sumOf { it.remainingQty }
                    val totalValueSdg = finishedProductRows.sumOf { it.inventoryValueSdg }
                    val totalValueEgp = finishedProductRows.sumOf { it.inventoryValueEgp }

                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassStatCard(
                                    title = "إجمالي المتبقي بالمخازن",
                                    value = format.format(totalRemaining),
                                    unit = "كرتونة / وحدة",
                                    subtitle = "المنتج: ${format.format(totalProduced)} | المباع: ${format.format(totalSold)}",
                                    accentColor = PmpGreen,
                                    icon = Icons.Default.Inventory,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassStatCard(
                                    title = "إجمالي قيمة مخزون المنتج التام",
                                    value = "${format.format(totalValueSdg)} SDG",
                                    unit = "تقييم مصر: ${format.format(totalValueEgp)} EGP",
                                    subtitle = "${finishedProductRows.size} صنف منتج تام",
                                    accentColor = PmpGold,
                                    icon = Icons.Default.FactCheck,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    if (finishedProductRows.isEmpty()) {
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.Inventory, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("لا توجد منتجات تامة مطابقة للبحث", color = TextMuted, fontSize = 13.sp)
                                }
                            }
                        }
                    } else {
                        items(finishedProductRows, key = { "fp_${it.productId}_${it.name}" }) { row ->
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
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp)
                                        ) {
                                            Text(
                                                text = row.name,
                                                color = TextWhite,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.5.sp
                                            )
                                            Text(
                                                text = "الوحدة: ${row.unit} • آخر حركة: ${row.lastMovementDate}",
                                                color = TextMuted,
                                                fontSize = 11.5.sp
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "${format.format(row.remainingQty)} ${row.unit}",
                                                color = if (row.remainingQty > 0) PmpGreen else PmpRed,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 15.sp
                                            )
                                            GlassBadge(
                                                text = if (row.isLowStock) "رصيد منخفض" else "متاح بالمخزن",
                                                color = if (row.isLowStock) PmpRed else PmpCyan
                                            )
                                        }
                                    }

                                    HorizontalDivider(color = GlassBorder.copy(alpha = 0.5f), thickness = 0.5.dp)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "المنتج/الوارد: ${format.format(row.producedQty)} ${row.unit}",
                                            color = PmpCyan,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "المباع/المنصرف: ${format.format(row.soldOrDispatchedQty)} ${row.unit}",
                                            color = PmpGold,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    if (row.inventoryValueSdg > 0 || row.inventoryValueEgp > 0) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "قيمة المخزون (SDG): ${format.format(row.inventoryValueSdg)}",
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                text = "قيمة المخزون (EGP): ${format.format(row.inventoryValueEgp)}",
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // =========================================================================
                // 6. حركة المخزون (Stock Movements Report)
                // =========================================================================
                "حركة المخزون" -> {
                    val prodMap = products.associateBy { it.id }
                    val whMap = warehouses.associateBy { it.id }
                    val filteredMoves = stockMoves.filter { sm ->
                        if (searchQuery.isBlank()) true else {
                            val pName = prodMap[sm.productId]?.name.orEmpty()
                            val wName = whMap[sm.warehouseId]?.name.orEmpty()
                            pName.contains(searchQuery.trim(), true) ||
                                wName.contains(searchQuery.trim(), true) ||
                                sm.note.contains(searchQuery.trim(), true)
                        }
                    }

                    item {
                        Text(
                            text = "حركات الوارد والصرف والمبيعات للمنتجات (${filteredMoves.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    items(filteredMoves.take(60), key = { "sm_${it.id}" }) { sm ->
                        val pName = prodMap[sm.productId]?.name ?: "صنف #${sm.productId}"
                        val wName = whMap[sm.warehouseId]?.name ?: "مخزن #${sm.warehouseId}"
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
                                        text = "$pName • ${sm.note.ifBlank { "حركة مخزنية" }}",
                                        color = TextWhite,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "نوع: ${sm.kind} • $wName • الموظف: ${sm.createdBy.ifBlank { "المدير" }}",
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "${format.format(sm.quantity)} كرتونة",
                                    color = if (sm.kind in listOf("IN", "RETURN", "RETURN_IN", "CANCEL", "وارد", "مرتجع")) PmpGreen else PmpCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                // =========================================================================
                // 7. حركات الخزن (Treasury Movements Report)
                // =========================================================================
                "حركات الخزن" -> {
                    val filteredMovements = movements.filter { m ->
                        searchQuery.isBlank() ||
                            m.description.contains(searchQuery.trim(), true) ||
                            m.cashbox.contains(searchQuery.trim(), true) ||
                            m.reference.contains(searchQuery.trim(), true)
                    }
                    items(filteredMovements.take(60), key = { "rep_mov_${it.id}" }) { m ->
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
                                        text = "${m.cashbox} • ${m.txDate} • مرجع: ${m.reference} • ${m.createdBy.ifBlank { "المدير" }}",
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
            }
        }
    }

    // =========================================================================
    // Comprehensive Invoice Details Modal from Reports (Requirement 6)
    // =========================================================================
    selectedInvoiceForDetail?.let { inv ->
        val lineItems = remember(inv.id, invoiceItems) {
            invoiceItems.filter { it.invoiceId == inv.id }
        }
        val payments = remember(inv.id, invoicePayments) {
            invoicePayments.filter { it.invoiceId == inv.id && it.status == "active" }
        }
        val paidTotal = payments.sumOf { it.amount }.coerceAtMost(inv.total)
        val remainingTotal = (inv.total - paidTotal).coerceAtLeast(0.0)
        val invoiceStatusText = when {
            inv.status == "cancelled" -> "ملغاة"
            remainingTotal <= 0.0 -> "مسددة بالكامل"
            paidTotal > 0.0 -> "مسددة جزئياً (متبقي آجل)"
            else -> "آجلة (لم تسدد)"
        }
        val paymentCashboxes = payments.map { it.cashbox }.filter { it.isNotBlank() }.distinct()
            .joinToString(" ، ").ifBlank { "بيع آجل (بدون خزنة سداد فوري)" }

        AlertDialog(
            onDismissRequest = { selectedInvoiceIdForDetail = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = PmpBrandRed)
                        Column {
                            Text(
                                text = "تفاصيل الفاتورة ${inv.invoiceNo}",
                                color = PmpBrandNavy,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "تاريخ الفاتورة: ${inv.invoiceDate}",
                                color = TextMuted,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                    GlassBadge(
                        text = invoiceStatusText,
                        color = if (remainingTotal <= 0.0) PmpGreen else PmpGold
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Customer & Employee Info Box
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PmpContainerBarSurface)
                            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "العميل: ${inv.customerName}",
                            color = TextDark,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "طريقة الدفع والخزنة المرتبطة: $paymentCashboxes",
                            color = PmpBrandNavy,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "موظف الحساب المصدر للفاتورة: ${inv.createdBy.ifBlank { "المدير" }}",
                            color = TextMuted,
                            fontSize = 11.5.sp
                        )
                    }

                    // Line Items Section
                    Text(
                        text = "الأصناف والكميات (${lineItems.size}):",
                        color = PmpBrandNavy,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )

                    if (lineItems.isEmpty()) {
                        Text(
                            text = "لا توجد بنود تفصيلية مسجلة لهذه الفاتورة",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    } else {
                        lineItems.forEachIndexed { idx, item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PmpSubtleSurface)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = "${idx + 1}. ${item.description}",
                                        color = TextDark,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "الكمية: ${format.format(item.quantity)} × سعر الوحدة: ${format.format(item.unitPrice)} ${inv.currency}",
                                        color = TextMuted,
                                        fontSize = 11.5.sp
                                    )
                                }
                                Text(
                                    text = "${format.format(item.lineTotal)} ${inv.currency}",
                                    color = PmpBrandNavy,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = GlassBorder)

                    // Financial Totals Breakdown
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PmpDangerSoftSurface)
                            .border(1.dp, PmpBrandRed.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("الإجمالي الفرعي:", color = TextMuted, fontSize = 12.sp)
                            Text("${format.format(inv.subtotal)} ${inv.currency}", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("الخصومات والضرائب:", color = TextMuted, fontSize = 12.sp)
                            Text("${format.format(inv.discount)} ${inv.currency}", color = PmpGold, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("الإجمالي النهائي للفاتورة:", color = PmpBrandNavy, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                            Text("${format.format(inv.total)} ${inv.currency}", color = PmpBrandNavy, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        }
                        HorizontalDivider(color = GlassBorder)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("المبلغ المدفوع (المحصل):", color = PmpGreen, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                            Text("${format.format(paidTotal)} ${inv.currency}", color = PmpGreen, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("المبلغ المتبقي (غير محصل):", color = if (remainingTotal > 0) PmpRed else TextMuted, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                            Text("${format.format(remainingTotal)} ${inv.currency}", color = if (remainingTotal > 0) PmpRed else PmpGreen, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp)
                        }
                    }

                    // Linked Payments History
                    if (payments.isNotEmpty()) {
                        Text(
                            text = "سجل دفعات السداد المرتبطة (${payments.size}):",
                            color = PmpBrandNavy,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp
                        )
                        payments.forEach { pay ->
                            val payCur = pay.resolvePaymentCurrency(inv.currency)
                            Text(
                                text = "• ${pay.payDate}: ${format.format(pay.amount)} $payCur في (${pay.cashbox}) — ${pay.note}",
                                color = TextMuted,
                                fontSize = 11.5.sp
                            )
                        }
                    }

                    if (inv.notes.isNotBlank()) {
                        HorizontalDivider(color = GlassBorder)
                        Text(
                            text = "الملاحظات: ${inv.notes}",
                            color = TextDark,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    text = "إغلاق والعودة للتقرير",
                    onClick = { selectedInvoiceIdForDetail = null },
                    modifier = Modifier.testTag("close_invoice_detail_modal_btn")
                )
            },
            containerColor = PmpWhiteCard
        )
    }
}

@Composable
private fun InvoiceReportItemCard(
    inv: InvoiceEntity,
    paidAmount: Double,
    remainingAmount: Double,
    format: NumberFormat,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("report_invoice_card_${inv.id}"),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "التاريخ: ${inv.invoiceDate} • الموظف: ${inv.createdBy.ifBlank { "المدير" }}",
                        color = TextMuted,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${format.format(inv.total)} ${inv.currency}",
                        color = PmpGold,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.5.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                    GlassBadge(
                        text = if (remainingAmount <= 0.0) "مسددة" else "متبقي: ${format.format(remainingAmount)}",
                        color = if (remainingAmount <= 0.0) PmpGreen else PmpRed
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "المدفوع: ${format.format(paidAmount)} ${inv.currency} | الخصم: ${format.format(inv.discount)}",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Text(
                    text = "اضغط لعرض الفاتورة كاملة ⬅",
                    color = PmpCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
    }
}
