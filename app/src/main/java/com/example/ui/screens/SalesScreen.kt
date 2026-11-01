package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.local.CustomerEntity
import com.example.data.local.InvoiceEntity
import com.example.data.local.InvoiceItemEntity
import com.example.data.local.getEffectiveSalePrice
import com.example.data.local.resolveCurrency
import com.example.data.local.resolveLinkedCashbox
import com.example.hasModulePermission
import com.example.isSystemManager
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import com.example.util.InvoicePdfExporter
import com.example.util.StatementPdfAndExcelExporter
import java.text.NumberFormat
import java.util.Locale

@Composable
fun SalesScreen(
    viewModel: PmpViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val invoices by viewModel.invoices.collectAsState()
    val allInvoiceItems by viewModel.invoiceItems.collectAsState()
    val invoicePayments by viewModel.invoicePayments.collectAsState()
    val customers by viewModel.customers.collectAsState()
    val products by viewModel.products.collectAsState()
    val warehouses by viewModel.warehouses.collectAsState()
    val cashboxes by viewModel.cashboxes.collectAsState()
    val stockBalances by viewModel.productStockBalances.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()

    val canExportPdf = remember(currentUser) {
        currentUser.hasModulePermission("تصدير PDF")
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val format = remember { NumberFormat.getNumberInstance(Locale.US) }

    // Dialog state for viewing invoice details
    var selectedInvoiceForDetail by remember { mutableStateOf<InvoiceEntity?>(null) }
    var showPaymentDialog by remember { mutableStateOf(false) }
    var payAmountText by remember { mutableStateOf("") }
    var payCashbox by remember { mutableStateOf(cashboxes.firstOrNull()?.name ?: "خزنة السودان") }
    var payNote by remember { mutableStateOf("") }

    // Launcher for exporting PDF to custom user-selected location
    val createPdfDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val inv = selectedInvoiceForDetail
        if (uri != null && inv != null) {
            val invItems = allInvoiceItems.filter { it.invoiceId == inv.id }
            val invPays = invoicePayments.filter { it.invoiceId == inv.id && it.status == "active" }
            val result = InvoicePdfExporter.exportInvoiceToDownloads(
                context = context,
                invoice = inv,
                items = invItems,
                payments = invPays,
                customOutputUri = uri
            )
            result.onSuccess {
                viewModel.postMessage("تم تصدير الفاتورة (${inv.invoiceNo}) بصيغة PDF مع شعار الهوية بنجاح")
            }.onFailure { err ->
                viewModel.postMessage("فشل تصدير PDF: ${err.message}", isError = true)
            }
        }
    }

    val canDeleteOrZero = remember(currentUser) {
        currentUser.isSystemManager() || currentUser.hasModulePermission("صلاحية الحذف والتصفير")
    }

    var statementCustomerId by remember { mutableStateOf<Long?>(null) }
    var customerToClearBalance by remember { mutableStateOf<CustomerEntity?>(null) }
    var customerToDeletePermanently by remember { mutableStateOf<CustomerEntity?>(null) }

    // Launcher for exporting statement PDF
    val createStatementPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val targetCust = customers.find { it.id == statementCustomerId }
        if (uri != null && targetCust != null) {
            val custInvoices = invoices.filter { it.customerId == targetCust.id && it.status == "active" }
            val custInvoiceIds = custInvoices.map { it.id }.toSet()
            val custPays = invoicePayments.filter { it.invoiceId in custInvoiceIds && it.status == "active" }
            val res = StatementPdfAndExcelExporter.exportStatementToPdf(
                context = context,
                customer = targetCust,
                invoices = custInvoices,
                payments = custPays,
                customOutputUri = uri
            )
            res.onSuccess {
                viewModel.postMessage("تم تصدير كشف الحساب بصيغة PDF بنجاح مع شعار الهوية والجدول التفصيلي")
            }.onFailure { err ->
                viewModel.postMessage("تعذر تصدير كشف الحساب PDF: ${err.message}", isError = true)
            }
        }
    }

    // Launcher for exporting statement Excel CSV
    val createStatementExcelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val targetCust = customers.find { it.id == statementCustomerId }
        if (uri != null && targetCust != null) {
            val custInvoices = invoices.filter { it.customerId == targetCust.id && it.status == "active" }
            val custInvoiceIds = custInvoices.map { it.id }.toSet()
            val custPays = invoicePayments.filter { it.invoiceId in custInvoiceIds && it.status == "active" }
            val res = StatementPdfAndExcelExporter.exportStatementToExcel(
                context = context,
                customer = targetCust,
                invoices = custInvoices,
                payments = custPays,
                customOutputUri = uri
            )
            res.onSuccess {
                viewModel.postMessage("تم تصدير كشف الحساب بصيغة Excel (CSV) المتوافقة مع اللغة العربية بنجاح")
            }.onFailure { err ->
                viewModel.postMessage("تعذر تصدير Excel: ${err.message}", isError = true)
            }
        }
    }

    // Tab 1: New Invoice Form State
    var invCustomerId by remember { mutableStateOf<Long?>(null) }
    var invCustomerName by remember { mutableStateOf("") }
    var invCurrency by remember { mutableStateOf("SDG") }
    var invDiscount by remember { mutableStateOf("") }
    var invNotes by remember { mutableStateOf("") }
    var invPayNow by remember { mutableStateOf(false) }
    var invPayAmount by remember { mutableStateOf("") }
    var invPayCashbox by remember { mutableStateOf(cashboxes.firstOrNull()?.name ?: "خزنة السودان") }

    // Invoice items builder
    val draftItems = remember { mutableStateListOf<InvoiceItemEntity>() }
    var itemProductText by remember { mutableStateOf("") }
    var itemQuantityText by remember { mutableStateOf("1") }
    var itemPriceText by remember { mutableStateOf("") }
    var itemWarehouseText by remember { mutableStateOf(warehouses.firstOrNull()?.name ?: "مخزن المصنع الرئيسي") }

    // Tab 2: New Customer Form State
    var showAddCustomerDialog by remember { mutableStateOf(false) }
    var newCustName by remember { mutableStateOf("") }
    var newCustPhone by remember { mutableStateOf("") }
    var newCustAddress by remember { mutableStateOf("") }
    var newCustNotes by remember { mutableStateOf("") }

    val tabs = listOf("سجل الفواتير", "فاتورة جديدة", "كشف حساب عميل", "دليل العملاء")

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
                        text = "الفواتير والمبيعات وإدارة العملاء",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "اضغط على أي فاتورة لعرض الفاتورة الكاملة بالتفاصيل وتصديرها بصيغة PDF مع شعار الهوية.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        fontSize = 13.sp
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

        when (selectedTab) {
            0 -> {
                // Invoices Directory
                if (invoices.isEmpty()) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "لا توجد فواتير مصدرة بعد", color = TextMuted)
                            }
                        }
                    }
                } else {
                    items(invoices, key = { it.id }) { inv ->
                        val payments = invoicePayments.filter { it.invoiceId == inv.id && it.status == "active" }
                        val invItemsCount = allInvoiceItems.count { it.invoiceId == inv.id }
                        val paid = payments.sumOf { it.amount }
                        val remaining = inv.total - paid

                        val statusLabel = when {
                            inv.status == "cancelled" -> "ملغاة"
                            paid >= inv.total -> "مسددة بالكامل"
                            paid > 0 -> "مسددة جزئياً"
                            else -> "غير مسددة"
                        }

                        val statusColor = when {
                            inv.status == "cancelled" -> PmpRedLight
                            paid >= inv.total -> PmpGreen
                            paid > 0 -> PmpCyan
                            else -> PmpGold
                        }

                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("invoice_card_${inv.invoiceNo}"),
                            onClick = { selectedInvoiceForDetail = inv }
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
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
                                            text = inv.invoiceNo,
                                            color = PmpCyan,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${inv.customerName} • ${inv.invoiceDate}",
                                            color = TextWhite,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    GlassBadge(text = statusLabel, color = statusColor)
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "الصافي: ${format.format(inv.total)} ${inv.currency}",
                                        color = TextWhite,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "المدفوع: ${format.format(paid)} | المتبقي: ${format.format(remaining)}",
                                        color = TextMuted,
                                        fontSize = 12.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(color = GlassBorder)
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.ReceiptLong,
                                            contentDescription = null,
                                            tint = PmpBrandRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "عرض الفاتورة الكاملة بالتفاصيل ($invItemsCount بنود)",
                                            color = PmpBrandRed,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.5.sp
                                        )
                                    }
                                    if (canExportPdf) {
                                        GlassBadge(
                                            text = "PDF + شعار الهوية",
                                            color = PmpBrandNavy
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                // New Invoice Form
                item {
                    val customerNames = customers.map { it.name }
                    val warehouseNames = warehouses.map { it.name }.ifEmpty { listOf("مخزن المصنع الرئيسي") }
                    val subtotal = draftItems.sumOf { it.lineTotal }
                    val disc = invDiscount.toDoubleOrNull() ?: 0.0
                    val total = (subtotal - disc).coerceAtLeast(0.0)

                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "إنشاء فاتورة بيع جديدة",
                                style = MaterialTheme.typography.titleMedium,
                                color = PmpCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            // Sales Warehouse & Branch Selection (Auto-sets Currency and Cashbox)
                            GlassDropdown(
                                label = "مخزن المبيعات الرئيسي للفاتورة (تحديد العملة والخزنة تلقائياً) *",
                                options = warehouseNames,
                                selectedOption = itemWarehouseText,
                                onOptionSelected = { whName ->
                                    itemWarehouseText = whName
                                    val wh = warehouses.find { it.name == whName }
                                    if (wh != null) {
                                        invCurrency = wh.resolveCurrency()
                                        invPayCashbox = wh.resolveLinkedCashbox()
                                        val matched = products.find { itemProductText.startsWith(it.sku) }
                                        if (matched != null) {
                                            itemPriceText = matched.getEffectiveSalePrice(invCurrency).toString()
                                        }
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Customer & Currency
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                GlassDropdown(
                                    label = "العميل *",
                                    options = customerNames,
                                    selectedOption = invCustomerName,
                                    onOptionSelected = { name ->
                                        invCustomerName = name
                                        invCustomerId = customers.find { it.name == name }?.id
                                    },
                                    modifier = Modifier.weight(1.3f)
                                )
                                GlassDropdown(
                                    label = "العملة",
                                    options = listOf("SDG", "EGP", "USD"),
                                    selectedOption = invCurrency,
                                    onOptionSelected = { curr ->
                                        invCurrency = curr
                                        val matched = products.find { itemProductText.startsWith(it.sku) }
                                        if (matched != null) {
                                            itemPriceText = matched.getEffectiveSalePrice(curr).toString()
                                        }
                                    },
                                    modifier = Modifier.weight(0.7f)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Product Line Item Adder
                            Text(
                                text = "إضافة بنود الفاتورة من المخزون",
                                color = PmpGold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            val productOptions = listOf("— بند حر —") + products.map { "${it.sku} | ${it.name} (${it.paintColor})" }

                            GlassDropdown(
                                label = "اختر صنفاً أو اكتبه بالأسفل",
                                options = productOptions,
                                selectedOption = itemProductText,
                                onOptionSelected = { opt ->
                                    itemProductText = opt
                                    val matched = products.find { opt.startsWith(it.sku) }
                                    if (matched != null) {
                                        itemPriceText = matched.getEffectiveSalePrice(invCurrency).toString()
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassTextField(
                                    value = itemQuantityText,
                                    onValueChange = { itemQuantityText = it },
                                    label = "الكمية (كرتونة/وحدة) *",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = itemPriceText,
                                    onValueChange = { itemPriceText = it },
                                    label = "سعر الوحدة ($invCurrency) *",
                                    placeholder = "0.00",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            GlassDropdown(
                                label = "مخزن صرف هذا البند",
                                options = warehouseNames,
                                selectedOption = itemWarehouseText,
                                onOptionSelected = { whName ->
                                    itemWarehouseText = whName
                                    val wh = warehouses.find { it.name == whName }
                                    if (wh != null) {
                                        invCurrency = wh.resolveCurrency()
                                        invPayCashbox = wh.resolveLinkedCashbox()
                                        val matched = products.find { itemProductText.startsWith(it.sku) }
                                        if (matched != null) {
                                            itemPriceText = matched.getEffectiveSalePrice(invCurrency).toString()
                                        }
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            GlassButton(
                                text = "إضافة بند",
                                gradient = GlassAccentGoldGradient,
                                onClick = {
                                    val qty = itemQuantityText.toDoubleOrNull() ?: 1.0
                                    val price = itemPriceText.toDoubleOrNull() ?: 0.0
                                    val matchedProduct = products.find { itemProductText.startsWith(it.sku) }
                                    val matchedWh = warehouses.find { it.name == itemWarehouseText }

                                    val desc = if (matchedProduct != null) {
                                        "${matchedProduct.name} - ${matchedProduct.paintColor} ($qty ${matchedProduct.unit})"
                                    } else {
                                        itemProductText.ifBlank { "بضاعة بويات متنوعة" }
                                    }

                                    draftItems.add(
                                        InvoiceItemEntity(
                                            invoiceId = 0,
                                            description = desc,
                                            quantity = qty,
                                            unitPrice = price,
                                            lineTotal = qty * price,
                                            productId = matchedProduct?.id,
                                            warehouseId = matchedWh?.id ?: 1L
                                        )
                                    )
                                    itemQuantityText = "1"
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Display Draft Items
                            if (draftItems.isNotEmpty()) {
                                Text(
                                    text = "البنود المضافة (${draftItems.size}):",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                draftItems.forEachIndexed { idx, itm ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${idx + 1}. ${itm.description} × ${itm.quantity}",
                                            color = TextWhite,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "${format.format(itm.lineTotal)} $invCurrency",
                                            color = PmpCyan,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        IconButton(onClick = { draftItems.removeAt(idx) }) {
                                            Icon(Icons.Default.Delete, contentDescription = null, tint = PmpRed)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassTextField(
                                    value = invDiscount,
                                    onValueChange = { invDiscount = it },
                                    label = "الخصم ($invCurrency)",
                                    placeholder = "0.00",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = invNotes,
                                    onValueChange = { invNotes = it },
                                    label = "ملاحظات الفاتورة",
                                    placeholder = "شروط الدفع / رقم الإذن",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Total Preview
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = PmpNavySurface
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(text = "الإجمالي قبل الخصم:", color = TextMuted)
                                        Text(text = "${format.format(subtotal)} $invCurrency", color = TextWhite)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(text = "صافي الفاتورة النهائي:", color = TextMuted)
                                        Text(
                                            text = "${format.format(total)} $invCurrency",
                                            color = PmpCyan,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Direct Payment Option
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = invPayNow,
                                    onCheckedChange = {
                                        invPayNow = it
                                        if (it) invPayAmount = total.toString()
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = PmpCyan)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "تسجيل سداد فوري بالخزنة الآن (نقداً)",
                                    color = TextWhite,
                                    fontSize = 13.sp
                                )
                            }

                            if (invPayNow) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    GlassTextField(
                                        value = invPayAmount,
                                        onValueChange = { invPayAmount = it },
                                        label = "المبلغ المسدد ($invCurrency) *",
                                        isNumber = true,
                                        modifier = Modifier.weight(1.2f)
                                    )
                                    GlassDropdown(
                                        label = "عملة الدفع",
                                        options = listOf("SDG", "EGP", "USD"),
                                        selectedOption = invCurrency,
                                        onOptionSelected = { invCurrency = it },
                                        modifier = Modifier.weight(0.8f)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                GlassDropdown(
                                    label = "الخزنة المستلمة (تحديد تلقائي للعملة) *",
                                    options = cashboxes.map { it.name },
                                    selectedOption = invPayCashbox,
                                    onOptionSelected = { box ->
                                        invPayCashbox = box
                                        if (box.contains("مصر") || box.contains("القاهرة") || box.contains("EGP")) {
                                            invCurrency = "EGP"
                                        } else if (box.contains("سودان") || box.contains("خرطوم") || box.contains("حلفا") || box.contains("عطبرة") || box.contains("SDG")) {
                                            invCurrency = "SDG"
                                        }
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            GlassButton(
                                text = "إصدار الفاتورة",
                                icon = Icons.Default.CheckCircle,
                                onClick = {
                                    val cid = invCustomerId ?: customers.firstOrNull()?.id ?: 1L
                                    val cname = invCustomerName.ifBlank { customers.find { it.id == cid }?.name ?: "عميل عام" }
                                    val payAmt = if (invPayNow) invPayAmount.toDoubleOrNull() else null

                                    viewModel.createInvoice(
                                        customerId = cid,
                                        customerName = cname,
                                        currency = invCurrency,
                                        items = draftItems.toList(),
                                        discount = disc,
                                        notes = invNotes,
                                        paymentCashbox = if (invPayNow) invPayCashbox else null,
                                        paymentAmount = payAmt
                                    )
                                    draftItems.clear()
                                    invDiscount = ""
                                    invNotes = ""
                                    invPayNow = false
                                    selectedTab = 0
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            2 -> {
                // Individual Customer Account Statement (كشف حساب لكل عميل لوحده)
                val targetCust = customers.find { it.id == statementCustomerId } ?: customers.firstOrNull()

                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "كشف حساب تفصيلي للعميل",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )

                            if (customers.isEmpty()) {
                                Text(
                                    text = "لا يوجد عملاء مسجلين في النظام. يرجى إضافة عميل أولاً من تبويب (دليل العملاء).",
                                    color = TextMuted,
                                    fontSize = 13.sp
                                )
                            } else {
                                val customerNames = customers.map { it.name }
                                GlassDropdown(
                                    label = "اختر العميل لعرض كشف حسابه",
                                    options = customerNames,
                                    selectedOption = targetCust?.name ?: "",
                                    onOptionSelected = { selectedName: String ->
                                        val found = customers.find { it.name == selectedName }
                                        statementCustomerId = found?.id
                                    }
                                )

                                targetCust?.let { cust ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        GlassBadge(
                                            text = "هاتف: ${cust.phone.ifBlank { "غير مسجل" }}",
                                            color = PmpCyan
                                        )
                                        GlassBadge(
                                            text = "العنوان: ${cust.address.ifBlank { "غير محدد" }}",
                                            color = PmpGold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (targetCust != null) {
                    val custInvoices = invoices.filter { it.customerId == targetCust.id && it.status == "active" }
                    val custInvoiceIds = custInvoices.map { it.id }.toSet()
                    val custPayments = invoicePayments.filter { it.invoiceId in custInvoiceIds && it.status == "active" }

                    val totalDebit = custInvoices.sumOf { it.total }
                    val totalCredit = custPayments.sumOf { it.amount }
                    val netBalance = totalDebit - totalCredit

                    // Summary Financial Cards
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassCard(modifier = Modifier.weight(1f)) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(text = "إجمالي الفواتير (مدين)", color = TextMuted, fontSize = 11.5.sp)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "${format.format(totalDebit)} SDG",
                                            color = PmpBrandNavy,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 15.sp
                                        )
                                        Text(text = "${custInvoices.size} فواتير مسحوبة", color = TextSubtle, fontSize = 10.5.sp)
                                    }
                                }

                                GlassCard(modifier = Modifier.weight(1f)) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(text = "إجمالي السداد (دائن)", color = TextMuted, fontSize = 11.5.sp)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "${format.format(totalCredit)} SDG",
                                            color = PmpGreen,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 15.sp
                                        )
                                        Text(text = "${custPayments.size} إيصالات سداد", color = TextSubtle, fontSize = 10.5.sp)
                                    }
                                }
                            }

                            // Net Remaining Balance Card
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = if (netBalance > 0) Color(0xFFFEF2F2) else Color(0xFFF0FDF4)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = if (netBalance > 0) "الرصيد المتبقي (مديونية مطلوبة على العميل):" else if (netBalance < 0) "رصيد دائن لصالح العميل:" else "حالة الحساب:",
                                            color = if (netBalance > 0) PmpBrandRed else PmpGreen,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${format.format(kotlin.math.abs(netBalance))} SDG",
                                            color = if (netBalance > 0) PmpBrandRed else PmpGreen,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 20.sp
                                        )
                                    }

                                    GlassBadge(
                                        text = if (netBalance > 0) "مطلوب سداد" else "الحساب خالص",
                                        color = if (netBalance > 0) PmpBrandRed else PmpGreen
                                    )
                                }
                            }

                            // Statement Export and Management Actions
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    GlassButton(
                                        text = "تصدير كشف PDF رسمي",
                                        icon = Icons.Default.PictureAsPdf,
                                        gradient = PmpBrandRedGradient,
                                        onClick = {
                                            createStatementPdfLauncher.launch("pmp_statement_${targetCust.name.replace(" ", "_")}.pdf")
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                            .testTag("export_statement_pdf_btn")
                                    )

                                    GlassButton(
                                        text = "تصدير Excel (عربي)",
                                        icon = Icons.Default.TableChart,
                                        gradient = GlassAccentGreenGradient,
                                        onClick = {
                                            createStatementExcelLauncher.launch("pmp_statement_${targetCust.name.replace(" ", "_")}.csv")
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                            .testTag("export_statement_excel_btn")
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    GlassButton(
                                        text = "مشاركة نصية سريعة",
                                        icon = Icons.Default.Share,
                                        gradient = GlassAccentGoldGradient,
                                        onClick = {
                                            val statementText = buildString {
                                                appendLine("═══════════════════════════════════")
                                                appendLine("📋 كشف حساب عميل • PMP PAINTS ERP")
                                                appendLine("═══════════════════════════════════")
                                                appendLine("العميل: ${targetCust.name}")
                                                appendLine("الهاتف: ${targetCust.phone.ifBlank { "غير مسجل" }}")
                                                appendLine("العنوان: ${targetCust.address.ifBlank { "غير محدد" }}")
                                                appendLine("تاريخ الكشف: ${java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
                                                appendLine("───────────────────────────────────")
                                                appendLine("إجمالي الفواتير المسحوبة: ${format.format(totalDebit)} SDG (${custInvoices.size} فاتورة)")
                                                appendLine("إجمالي السدادات والمقبوضات: ${format.format(totalCredit)} SDG (${custPayments.size} دفعة)")
                                                appendLine("صافي الرصيد المستحق: ${format.format(netBalance)} SDG")
                                                appendLine("───────────────────────────────────")
                                                appendLine("سجل الحركات التفصيلية:")
                                                custInvoices.forEach { inv ->
                                                    val invPaid = custPayments.filter { it.invoiceId == inv.id }.sumOf { it.amount }
                                                    appendLine("• فاتورة [${inv.invoiceNo}] تاريخ ${inv.invoiceDate}: ${format.format(inv.total)} SDG (مسدد منها: ${format.format(invPaid)})")
                                                }
                                                custPayments.forEach { p ->
                                                    appendLine("• سداد دفعة تاريخ ${p.payDate}: ${format.format(p.amount)} SDG [خزنة: ${p.cashbox}] ${p.note}")
                                                }
                                                appendLine("═══════════════════════════════════")
                                                appendLine("شركة PMP للدهانات والطلاءات (PMP PAINTS) • ألوان تصنع الفرق")
                                            }

                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, "كشف حساب العميل: ${targetCust.name} - PMP Paints")
                                                putExtra(Intent.EXTRA_TEXT, statementText)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "مشاركة كشف حساب العميل").apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            })
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp)
                                            .testTag("share_customer_statement_btn")
                                    )

                                    if (canDeleteOrZero) {
                                        GlassButton(
                                            text = "تصفير حساب العميل",
                                            icon = Icons.Default.RestartAlt,
                                            gradient = Brush.horizontalGradient(listOf(PmpBrandRed, Color(0xFF7F1D1D))),
                                            onClick = { customerToClearBalance = targetCust },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(42.dp)
                                                .testTag("clear_customer_balance_btn")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Detailed Chronological Ledger
                    item {
                        Text(
                            text = "سجل الحركات التفصيلي للعميل (${custInvoices.size + custPayments.size} حركة)",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    if (custInvoices.isEmpty() && custPayments.isEmpty()) {
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = "لا توجد حركات أو فواتير سابقة لهذا العميل", color = TextMuted)
                                }
                            }
                        }
                    } else {
                        // Display Invoices
                        items(custInvoices, key = { "inv_${it.id}" }) { inv ->
                            val invPays = custPayments.filter { it.invoiceId == inv.id }
                            val paidAmt = invPays.sumOf { it.amount }
                            val rem = (inv.total - paidAmt).coerceAtLeast(0.0)

                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Receipt,
                                            contentDescription = null,
                                            tint = PmpBrandNavy,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = "فاتورة: ${inv.invoiceNo}",
                                                    color = TextWhite,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp
                                                )
                                                GlassBadge(text = "مدين (+)", color = PmpBrandNavy)
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "التاريخ: ${inv.invoiceDate} • المسدد: ${format.format(paidAmt)} • المتبقي: ${format.format(rem)}",
                                                color = TextMuted,
                                                fontSize = 11.5.sp
                                            )
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "${format.format(inv.total)} SDG",
                                            color = PmpBrandRed,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.5.sp
                                        )
                                        TextButton(
                                            onClick = { selectedInvoiceForDetail = inv },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text(text = "عرض الفاتورة", color = PmpCyan, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }

                        // Display Payments
                        items(custPayments, key = { "pay_${it.id}" }) { pay ->
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Payments,
                                            contentDescription = null,
                                            tint = PmpGreen,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = "سداد دفعة نقدية",
                                                    color = TextWhite,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp
                                                )
                                                GlassBadge(text = "دائن (-)", color = PmpGreen)
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "التاريخ: ${pay.payDate} • الخزنة: ${pay.cashbox} ${if (pay.note.isNotBlank()) "• ${pay.note}" else ""}",
                                                color = TextMuted,
                                                fontSize = 11.5.sp
                                            )
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "-${format.format(pay.amount)} SDG",
                                            color = PmpGreen,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.5.sp
                                        )
                                        Text(text = "مسددة", color = TextSubtle, fontSize = 10.5.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            3 -> {
                // Customers List & Add Customer
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "سجل العملاء وبيانات الاتصال",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        GlassButton(
                            text = "＋ عميل جديد",
                            onClick = { showAddCustomerDialog = true },
                            modifier = Modifier.height(38.dp)
                        )
                    }
                }

                items(customers, key = { it.id }) { c ->
                    val custInvoices = invoices.filter { it.customerId == c.id && it.status == "active" }
                    val totalDebt = custInvoices.sumOf { it.total }

                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = c.name,
                                        color = TextWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "الهاتف: ${c.phone.ifEmpty { "—" }} • العنوان: ${c.address.ifEmpty { "—" }}",
                                        color = TextMuted,
                                        fontSize = 12.sp
                                    )
                                    if (c.notes.isNotBlank()) {
                                        Text(text = c.notes, color = TextSubtle, fontSize = 11.sp)
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${custInvoices.size} فواتير",
                                        color = PmpCyan,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "${format.format(totalDebt)} إجمالي",
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        statementCustomerId = c.id
                                        selectedTab = 2
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = "عرض كشف الحساب", fontSize = 11.5.sp)
                                }

                                if (canDeleteOrZero) {
                                    OutlinedButton(
                                        onClick = { customerToDeletePermanently = c },
                                        modifier = Modifier.height(34.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PmpBrandRed),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(text = "حذف العميل", fontSize = 11.5.sp, color = PmpBrandRed)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal: Confirmation Dialog for Customer Balance Clearing
    customerToClearBalance?.let { cust ->
        AlertDialog(
            onDismissRequest = { customerToClearBalance = null },
            title = {
                Text(
                    text = "تصفير حساب العميل",
                    color = PmpBrandRed,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "هل أنت متأكد من تصفير حساب العميل (${cust.name}) وإلغاء المبالغ المسجلة عليه بالكامل؟ لا يمكن التراجع عن هذا الإجراء الإداري.",
                    color = TextWhite,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                GlassButton(
                    text = "نعم، تصفير الحساب",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        viewModel.clearCustomerBalance(cust.id, cust.name)
                        customerToClearBalance = null
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { customerToClearBalance = null })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Confirmation Dialog for Permanent Customer Deletion
    customerToDeletePermanently?.let { cust ->
        AlertDialog(
            onDismissRequest = { customerToDeletePermanently = null },
            title = {
                Text(
                    text = "حذف العميل نهائياً من النظام",
                    color = PmpBrandRed,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "هل أنت متأكد من حذف العميل (${cust.name}) نهائياً من قاعدة البيانات والسيرفر السحابي وكافة سجلاته المالية؟ هذا الإجراء لا يمكن التراجع عنه.",
                    color = TextWhite,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                GlassButton(
                    text = "نعم، حذف العميل نهائياً",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        viewModel.deleteCustomer(cust.id, cust.name)
                        if (statementCustomerId == cust.id) {
                            statementCustomerId = null
                        }
                        customerToDeletePermanently = null
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { customerToDeletePermanently = null })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Full Invoice Details View with Identity Logo & Permission-Gated PDF Export
    if (selectedInvoiceForDetail != null) {
        val inv = selectedInvoiceForDetail!!
        val invItems = allInvoiceItems.filter { it.invoiceId == inv.id }
        val payments = invoicePayments.filter { it.invoiceId == inv.id && it.status == "active" }
        val paid = payments.sumOf { it.amount }
        val remaining = (inv.total - paid).coerceAtLeast(0.0)
        val customerObj = customers.find { it.id == inv.customerId || it.name == inv.customerName }

        Dialog(
            onDismissRequest = { selectedInvoiceForDetail = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .heightIn(max = 680.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.5.dp, GlassBorder, RoundedCornerShape(24.dp))
                    .testTag("full_invoice_details_dialog"),
                color = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Official Invoice Header with PMP Paints Identity Logo
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFF8FAFC))
                            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 76.dp, height = 52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White)
                                    .border(1.dp, PmpBrandRed.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_pmp_emblem),
                                    contentDescription = "شعار هوية شركة PMP للدهانات والطلاءات",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "شركة PMP للدهانات والطلاءات (PMP PAINTS)",
                                    color = PmpBrandNavy,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "فاتورة مبيعات تفصيلية معتمدة بشعار الهوية",
                                    color = PmpBrandRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "رقم الفاتورة: ${inv.invoiceNo} • التاريخ: ${inv.invoiceDate}",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        IconButton(onClick = { selectedInvoiceForDetail = null }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إغلاق",
                                tint = TextMuted
                            )
                        }
                    }

                    // Customer & Status Info Box
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFF1F5F9))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "العميل: ${inv.customerName}",
                                color = TextDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
                            )
                            GlassBadge(
                                text = when {
                                    inv.status == "cancelled" -> "ملغاة"
                                    remaining <= 0.0 -> "مسددة بالكامل"
                                    paid > 0.0 -> "مسددة جزئياً"
                                    else -> "غير مسددة"
                                },
                                color = when {
                                    inv.status == "cancelled" -> PmpRed
                                    remaining <= 0.0 -> PmpGreen
                                    paid > 0.0 -> PmpBlueLight
                                    else -> Color(0xFFD97706)
                                }
                            )
                        }
                        if (customerObj != null) {
                            Text(
                                text = "الهاتف: ${customerObj.phone.ifBlank { "—" }} • العنوان: ${customerObj.address.ifBlank { "—" }}",
                                color = TextMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        Text(
                            text = "عملة الفاتورة: ${inv.currency}",
                            color = PmpBrandNavy,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.5.sp
                        )
                    }

                    // Full Line Items Table
                    Text(
                        text = "بنود وأصناف الفاتورة بالتفصيل (${invItems.size} بنود):",
                        color = PmpBrandNavy,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                    ) {
                        // Table Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PmpBrandNavy)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "الصنف والمخزن",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1.6f)
                            )
                            Text(
                                text = "الكمية × السعر",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1.1f)
                            )
                            Text(
                                text = "الإجمالي",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (invItems.isEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "إجمالي مبيعات الفاتورة (${inv.invoiceNo})",
                                    color = TextDark,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${format.format(inv.subtotal)} ${inv.currency}",
                                    color = PmpBrandRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            invItems.forEachIndexed { idx, item ->
                                val whName = warehouses.find { it.id == item.warehouseId }?.name ?: "المخزن الرئيسي"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (idx % 2 == 1) Color(0xFFF8FAFC) else Color.White)
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1.6f)) {
                                        Text(
                                            text = "${idx + 1}. ${item.description}",
                                            color = TextDark,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = whName,
                                            color = TextMuted,
                                            fontSize = 10.5.sp
                                        )
                                    }
                                    Text(
                                        text = "${format.format(item.quantity)} × ${format.format(item.unitPrice)}",
                                        color = TextMuted,
                                        fontSize = 11.5.sp,
                                        modifier = Modifier.weight(1.1f)
                                    )
                                    Text(
                                        text = "${format.format(item.lineTotal)} ${inv.currency}",
                                        color = PmpBrandNavy,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                HorizontalDivider(color = GlassBorder)
                            }
                        }
                    }

                    // Financial Summary Box
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFFEF2F2))
                            .border(1.dp, PmpBrandRed.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("الإجمالي قبل الخصم:", color = TextMuted, fontSize = 12.sp)
                            Text("${format.format(inv.subtotal)} ${inv.currency}", color = TextDark, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("قيمة الخصم:", color = TextMuted, fontSize = 12.sp)
                            Text("${format.format(inv.discount)} ${inv.currency}", color = TextDark, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                        HorizontalDivider(color = PmpBrandRed.copy(alpha = 0.2f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("صافي الفاتورة النهائي:", color = PmpBrandRed, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                            Text("${format.format(inv.total)} ${inv.currency}", color = PmpBrandRed, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("المدفوع حتى الآن:", color = PmpGreen, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("${format.format(paid)} ${inv.currency}", color = PmpGreen, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("المتبقي للتحصيل:", color = PmpBrandNavy, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("${format.format(remaining)} ${inv.currency}", color = PmpBrandNavy, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        }
                    }

                    // Payment History
                    if (payments.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "سجل الدفعات المسددة على الفاتورة:",
                                color = PmpBrandNavy,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            payments.forEach { p ->
                                Text(
                                    text = "• ${p.payDate} | ${p.cashbox}: ${format.format(p.amount)} ${inv.currency} (${p.note})",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    if (inv.notes.isNotBlank()) {
                        Text(
                            text = "ملاحظات الفاتورة: ${inv.notes}",
                            color = TextMuted,
                            fontSize = 11.5.sp
                        )
                    }

                    // Permission-Gated PDF Export with Identity Logo Section
                    if (canExportPdf) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFFF0FDF4))
                                .border(1.dp, PmpGreen.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PictureAsPdf,
                                    contentDescription = null,
                                    tint = PmpGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "صلاحية تصدير الفاتورة PDF مع شعار الهوية مفعّلة",
                                    color = PmpGreen,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                GlassButton(
                                    text = "تصدير PDF بشعار الهوية",
                                    icon = Icons.Default.PictureAsPdf,
                                    gradient = PmpBrandRedGradient,
                                    onClick = {
                                        val result = InvoicePdfExporter.exportInvoiceToDownloads(
                                            context = context,
                                            invoice = inv,
                                            items = invItems,
                                            payments = payments
                                        )
                                        result.onSuccess { savedPath ->
                                            viewModel.postMessage("تم تصدير الفاتورة PDF بشعار الهوية إلى: $savedPath")
                                        }.onFailure { err ->
                                            viewModel.postMessage("تعذر تصدير ملف PDF: ${err.message}", isError = true)
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("export_invoice_pdf_button")
                                )

                                GlassButton(
                                    text = "حفظ باسم...",
                                    icon = Icons.Default.SaveAlt,
                                    gradient = GlassAccentBlueGradient,
                                    onClick = {
                                        createPdfDocumentLauncher.launch("PMP_Invoice_${inv.invoiceNo}.pdf")
                                    },
                                    modifier = Modifier.weight(0.85f)
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFFFFBEB))
                                .border(1.dp, PmpGold, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFFB45309),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "صلاحية (تصدير PDF) غير مخصصة لحسابك حالياً. يمكن لمدير النظام فقط تفعيلها لك.",
                                color = Color(0xFF92400E),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Invoice Actions Row (Pay / Cancel / Close)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (inv.status == "active" && remaining > 0) {
                            GlassButton(
                                text = "تسجيل سداد",
                                icon = Icons.Default.Payments,
                                gradient = GlassAccentGoldGradient,
                                onClick = {
                                    payAmountText = remaining.toString()
                                    showPaymentDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (inv.status == "active") {
                            GlassButton(
                                text = "إلغاء الفاتورة",
                                gradient = Brush.horizontalGradient(listOf(PmpRed, PmpRedLight)),
                                onClick = {
                                    viewModel.cancelInvoice(inv.id, "إلغاء بناء على طلب العميل")
                                    selectedInvoiceForDetail = null
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        GlassButton(
                            text = "إغلاق",
                            gradient = GlassAccentBlueGradient,
                            onClick = { selectedInvoiceForDetail = null },
                            modifier = Modifier.weight(0.8f)
                        )
                    }
                }
            }
        }
    }

    // Modal: Record Payment on Invoice
    if (showPaymentDialog && selectedInvoiceForDetail != null) {
        val inv = selectedInvoiceForDetail!!
        var payCurrency by remember(inv) { mutableStateOf(inv.currency) }

        AlertDialog(
            onDismissRequest = { showPaymentDialog = false },
            title = { Text(text = "تسجيل دفعة لفاتورة ${inv.invoiceNo}", color = PmpCyan) },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlassTextField(
                            value = payAmountText,
                            onValueChange = { payAmountText = it },
                            label = "مبلغ الدفعة ($payCurrency) *",
                            isNumber = true,
                            modifier = Modifier.weight(1.3f)
                        )
                        GlassDropdown(
                            label = "عملة الدفع",
                            options = listOf("SDG", "EGP", "USD"),
                            selectedOption = payCurrency,
                            onOptionSelected = { payCurrency = it },
                            modifier = Modifier.weight(0.7f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    GlassDropdown(
                        label = "الخزنة المستلمة (تحديد تلقائي للعملة) *",
                        options = cashboxes.map { it.name },
                        selectedOption = payCashbox,
                        onOptionSelected = { box ->
                            payCashbox = box
                            if (box.contains("مصر") || box.contains("القاهرة") || box.contains("EGP")) {
                                payCurrency = "EGP"
                            } else if (box.contains("سودان") || box.contains("خرطوم") || box.contains("حلفا") || box.contains("عطبرة") || box.contains("SDG")) {
                                payCurrency = "SDG"
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    GlassTextField(
                        value = payNote,
                        onValueChange = { payNote = it },
                        label = "ملاحظة السداد",
                        placeholder = "دفعة نقدية / تحويل بنكي"
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "تأكيد الدفعة",
                    onClick = {
                        val amt = payAmountText.toDoubleOrNull() ?: 0.0
                        viewModel.payInvoice(
                            invoiceId = inv.id,
                            invoiceNo = inv.invoiceNo,
                            customerName = inv.customerName,
                            currency = payCurrency,
                            cashbox = payCashbox,
                            amount = amt,
                            note = payNote
                        )
                        showPaymentDialog = false
                        selectedInvoiceForDetail = null
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { showPaymentDialog = false })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Add Customer Dialog
    if (showAddCustomerDialog) {
        AlertDialog(
            onDismissRequest = { showAddCustomerDialog = false },
            title = { Text(text = "إضافة عميل جديد", color = PmpCyan, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    GlassTextField(
                        value = newCustName,
                        onValueChange = { newCustName = it },
                        label = "اسم العميل / الشركة *"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    GlassTextField(
                        value = newCustPhone,
                        onValueChange = { newCustPhone = it },
                        label = "رقم الهاتف"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    GlassTextField(
                        value = newCustAddress,
                        onValueChange = { newCustAddress = it },
                        label = "العنوان / المدينة"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    GlassTextField(
                        value = newCustNotes,
                        onValueChange = { newCustNotes = it },
                        label = "ملاحظات"
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "حفظ العميل",
                    onClick = {
                        if (newCustName.isNotBlank()) {
                            viewModel.addCustomer(newCustName, newCustPhone, newCustAddress, newCustNotes)
                            newCustName = ""
                            newCustPhone = ""
                            newCustAddress = ""
                            newCustNotes = ""
                            showAddCustomerDialog = false
                        }
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { showAddCustomerDialog = false })
            },
            containerColor = PmpNavySurface
        )
    }
}
