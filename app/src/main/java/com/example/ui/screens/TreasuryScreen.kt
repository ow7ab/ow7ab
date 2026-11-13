package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.CashboxEntity
import com.example.data.local.DailyClosingEntity
import com.example.data.local.MovementEntity
import com.example.data.local.TransferEntity
import com.example.hasModulePermission
import com.example.isSystemManager
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import com.example.util.RoomTransactionLogger
import com.example.util.RoomTransactionTrace
import com.example.util.StatementPdfAndExcelExporter
import com.example.util.TraceStatus
import com.example.util.TransactionStage
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TreasuryScreen(
    viewModel: PmpViewModel,
    initialSection: String? = null,
    onInitialSectionConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cashboxes by viewModel.cashboxes.collectAsState()
    val movements by viewModel.movements.collectAsState()
    val transfers by viewModel.transfers.collectAsState()
    val dailyClosings by viewModel.dailyClosings.collectAsState()
    val halfaInvoices by viewModel.halfaInvoices.collectAsState()
    val cashboxBalances by viewModel.cashboxBalances.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val defaultAccountCurrency by viewModel.defaultAccountCurrency.collectAsState()
    val isInitialDataLoading by viewModel.isInitialDataLoading.collectAsState()
    val dataLoadError by viewModel.dataLoadError.collectAsState()

    var selectedMovementForDetail by remember { mutableStateOf<MovementEntity?>(null) }
    var selectedTransferForDetail by remember { mutableStateOf<TransferEntity?>(null) }
    var selectedClosingForDetail by remember { mutableStateOf<DailyClosingEntity?>(null) }

    val canAccessTreasury = currentUser.hasModulePermission("الخزن")
    val canAccessTransfers = currentUser.hasModulePermission("التحويلات")
    val isManager = currentUser.isSystemManager()

    val tabs = remember(canAccessTreasury, canAccessTransfers, isManager) {
        buildList {
            if (canAccessTreasury) add("حركة مالية")
            if (canAccessTransfers) add("تحويل خزن")
            if (canAccessTreasury) {
                add("إقفال اليومية")
                add("كشف الحساب")
            }
            if (canAccessTreasury || isManager) {
                add("إدارة الخزن")
            }
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(tabs, initialSection) {
        if (initialSection != null) {
            val targetIdx = tabs.indexOf(initialSection)
            if (targetIdx >= 0) {
                selectedTab = targetIdx
            }
            onInitialSectionConsumed()
        } else if (selectedTab >= tabs.size) {
            selectedTab = 0
        }
    }

    val activeTabTitle = tabs.getOrElse(selectedTab) { tabs.firstOrNull() ?: "" }

    val format = remember { NumberFormat.getNumberInstance(Locale.US) }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }

    // Tab 0: New Movement State
    var moveCashbox by remember { mutableStateOf(viewModel.resolveCashboxForCurrency(defaultAccountCurrency)) }
    var moveCurrency by remember { mutableStateOf(defaultAccountCurrency) }
    var moveDirection by remember { mutableStateOf("وارد") }
    var moveAmount by remember { mutableStateOf("") }
    var moveReference by remember { mutableStateOf("REC-${System.currentTimeMillis() % 1000000}") }
    var moveDescription by remember { mutableStateOf("") }
    var txSearchQuery by remember { mutableStateOf("") }
    var txDirectionFilter by remember { mutableStateOf("الكل") }
    var txCashboxFilter by remember { mutableStateOf("كل الخزن") }

    // Tab 1: Transfer State
    var trfSourceBox by remember { mutableStateOf("خزنة السودان") }
    var trfDestBox by remember { mutableStateOf("خزنة مصر") }
    var trfSourceCur by remember { mutableStateOf("SDG") }
    var trfDestCur by remember { mutableStateOf("EGP") }
    var trfAmount by remember { mutableStateOf("") }
    var trfRate by remember { mutableStateOf("1.0") }
    var trfReference by remember { mutableStateOf("") }
    var trfDescription by remember { mutableStateOf("") }
    var trfHalfaInvoiceId by remember { mutableStateOf<Long?>(null) }
    var trfIsInvertedRate by remember { mutableStateOf(false) }
    var boxToZeroOut by remember { mutableStateOf<String?>(null) }

    // Tab 2: Daily Closing State
    var closeBox by remember { mutableStateOf(viewModel.resolveCashboxForCurrency(defaultAccountCurrency)) }
    var closeCur by remember { mutableStateOf(defaultAccountCurrency) }
    var closeActualAmount by remember { mutableStateOf("") }
    var closeNotes by remember { mutableStateOf("") }

    // Tab 3: Statement State
    var stBox by remember { mutableStateOf(viewModel.resolveCashboxForCurrency(defaultAccountCurrency)) }
    var stCur by remember { mutableStateOf(defaultAccountCurrency) }
    var showAllBoxMovements by remember { mutableStateOf(false) }

    LaunchedEffect(defaultAccountCurrency) {
        val targetBox = viewModel.resolveCashboxForCurrency(defaultAccountCurrency)
        moveCurrency = defaultAccountCurrency
        moveCashbox = targetBox
        closeCur = defaultAccountCurrency
        closeBox = targetBox
        stCur = defaultAccountCurrency
        stBox = targetBox
    }

    val createTreasuryExcelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val boxMovements = movements.filter {
                it.cashbox.trim().equals(stBox.trim(), ignoreCase = true) &&
                    it.currency.trim().equals(stCur.trim(), ignoreCase = true)
            }
            val currentTotal = cashboxBalances[Pair(stBox.trim(), stCur.trim())] ?: 0.0
            val res = StatementPdfAndExcelExporter.exportTreasuryStatementToExcel(
                context = context,
                cashboxName = stBox,
                currency = stCur,
                movements = boxMovements,
                closingBalance = currentTotal,
                customOutputUri = uri
            )
            res.onSuccess {
                viewModel.postMessage("تم تصدير كشف حساب الخزنة ($stBox) في ملف إكسيل (Excel) بنجاح")
            }.onFailure { err ->
                viewModel.postMessage("تعذر تصدير ملف إكسيل: ${err.message}", isError = true)
            }
        }
    }

    // Tab 4: Manage Cashboxes State
    var showAddCashboxDialog by remember { mutableStateOf(false) }
    var newCashboxName by remember { mutableStateOf("") }
    var newCashboxCurrency by remember { mutableStateOf("SDG") }
    var editingCashbox by remember { mutableStateOf<CashboxEntity?>(null) }
    var editCashboxName by remember { mutableStateOf("") }
    var editCashboxCurrency by remember { mutableStateOf("SDG") }
    var editCashboxActive by remember { mutableStateOf(true) }
    var deletingCashbox by remember { mutableStateOf<CashboxEntity?>(null) }

    val boxNames = cashboxes.map { it.name }.ifEmpty { listOf("خزنة السودان", "خزنة مصر", "مخزن عطبرة", "مخزن حلفا") }
    val currencies = listOf("SDG", "EGP", "USD")

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 24.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (canAccessTreasury && canAccessTransfers) {
                                "الخزن والحسابات والتحويلات"
                            } else if (canAccessTransfers) {
                                "التحويلات المالية بين الخزن"
                            } else {
                                "الخزن والحركات المالية"
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Unified Default Account Currency Selector Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PmpContainerBarSurface)
                            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "عملة معاملات الحساب الموحدة:",
                                color = PmpBrandNavy,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "تتوحد عليها الحركات وكشف الحساب وتقبل التغيير داخل المعاملة",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            currencies.forEach { cur ->
                                val selected = defaultAccountCurrency == cur
                                Surface(
                                    onClick = {
                                        viewModel.setDefaultAccountCurrency(cur)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) PmpBrandRed else PmpSubtleSurface,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (selected) PmpBrandRed else GlassBorder
                                    ),
                                    modifier = Modifier.testTag("treasury_unified_currency_$cur")
                                ) {
                                    Text(
                                        text = cur,
                                        color = if (selected) Color.White else PmpBrandNavy,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.5.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (tabs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))

                        GlassTabRow(
                            tabs = tabs,
                            selectedIndex = selectedTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)),
                            onTabSelected = { selectedTab = it }
                        )
                    }
                }
            }
        }

        if (isInitialDataLoading && cashboxes.isEmpty() && movements.isEmpty()) {
            item(key = "treasury_shimmer_skeleton") {
                ProvideShimmerEffect(enabled = true) {
                    TreasurySkeletonSection()
                }
            }
        } else if (dataLoadError != null && cashboxes.isEmpty() && movements.isEmpty()) {
            item(key = "treasury_error_card") {
                PmpErrorStateCard(
                    message = dataLoadError ?: "تعذر تحميل بيانات الخزينة",
                    onRetry = { viewModel.retryDataLoadAndSync() }
                )
            }
        } else when (activeTabTitle) {
            "حركة مالية" -> {
                // New Movement Form
                item {
                    val currentBal = cashboxBalances[Pair(moveCashbox, moveCurrency)] ?: 0.0
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "تسجيل حركة وارد أو منصرف",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = PmpCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                )
                                GlassBadge(
                                    text = "الرصيد: ${format.format(currentBal)} $moveCurrency",
                                    color = if (currentBal >= 0) PmpGreen else PmpRed
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassDropdown(
                                    label = "الخزنة *",
                                    options = boxNames,
                                    selectedOption = moveCashbox,
                                    onOptionSelected = { moveCashbox = it },
                                    modifier = Modifier.weight(1.3f)
                                )
                                GlassDropdown(
                                    label = "العملة *",
                                    options = currencies,
                                    selectedOption = moveCurrency,
                                    onOptionSelected = { cur ->
                                        moveCurrency = cur
                                        moveCashbox = viewModel.resolveCashboxForCurrency(cur)
                                    },
                                    modifier = Modifier.weight(0.7f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            GlassDropdown(
                                label = "نوع الحركة المالية (وارد / منصرف) *",
                                options = listOf("وارد", "منصرف"),
                                selectedOption = moveDirection,
                                onOptionSelected = { moveDirection = it },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassTextField(
                                    value = moveAmount,
                                    onValueChange = { moveAmount = it },
                                    label = "المبلغ ($moveCurrency) *",
                                    placeholder = "0.00",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = moveReference,
                                    onValueChange = { moveReference = it },
                                    label = "رقم المستند / الإذن *",
                                    placeholder = "مثال: REC-102",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            GlassTextField(
                                value = moveDescription,
                                onValueChange = { moveDescription = it },
                                label = "البيان وملاحظات الحركة *",
                                placeholder = "اكتب تفاصيل المعاملة...",
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            GlassButton(
                                text = "إضافة معاملة",
                                icon = Icons.Default.Save,
                                onClick = {
                                    val cleanAmtStr = moveAmount.replace("٬", "").replace(",", "").trim()
                                    val amt = cleanAmtStr.toDoubleOrNull() ?: 0.0
                                    val ref = moveReference.trim().ifBlank { "REC-${System.currentTimeMillis() % 1000000}" }
                                    val desc = moveDescription.trim().ifBlank { "حركة $moveDirection نقدية في $moveCashbox" }
                                    viewModel.addMovement(
                                        cashbox = moveCashbox,
                                        currency = moveCurrency,
                                        direction = moveDirection,
                                        amount = amt,
                                        description = desc,
                                        reference = ref
                                    )
                                    if (amt > 0) {
                                        moveAmount = ""
                                        moveReference = "REC-${System.currentTimeMillis() % 1000000}"
                                        moveDescription = ""
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Recent Transactions List Header & Filters
                val filteredMovements = movements.filter { m ->
                    val matchQuery = txSearchQuery.isBlank() ||
                        m.reference.contains(txSearchQuery, ignoreCase = true) ||
                        m.description.contains(txSearchQuery, ignoreCase = true) ||
                        m.cashbox.contains(txSearchQuery, ignoreCase = true) ||
                        m.createdBy.contains(txSearchQuery, ignoreCase = true)
                    val matchDirection = txDirectionFilter == "الكل" || m.direction == txDirectionFilter
                    val matchCashbox = txCashboxFilter == "كل الخزن" || m.cashbox.trim().equals(txCashboxFilter.trim(), ignoreCase = true)
                    matchQuery && matchDirection && matchCashbox
                }

                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.ReceiptLong,
                                        contentDescription = null,
                                        tint = PmpCyan,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "سجل المعاملات والحركات المالية",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                                GlassBadge(
                                    text = "${filteredMovements.size} حركة مسجلة",
                                    color = PmpCyan
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Search bar
                            GlassTextField(
                                value = txSearchQuery,
                                onValueChange = { txSearchQuery = it },
                                label = "بحث في المعاملات",
                                placeholder = "رقم المستند، البيان، الخزنة...",
                                leadingIcon = Icons.Default.Search,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Direction Filter and Cashbox Filter
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                GlassDropdown(
                                    label = "نوع الحركة",
                                    options = listOf("الكل", "وارد", "منصرف"),
                                    selectedOption = txDirectionFilter,
                                    onOptionSelected = { txDirectionFilter = it },
                                    modifier = Modifier.weight(1f)
                                )
                                GlassDropdown(
                                    label = "فلترة الخزنة",
                                    options = listOf("كل الخزن") + boxNames,
                                    selectedOption = txCashboxFilter,
                                    onOptionSelected = { txCashboxFilter = it },
                                    modifier = Modifier.weight(1.2f)
                                )
                            }
                        }
                    }
                }

                if (filteredMovements.isEmpty()) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.AccountBalanceWallet,
                                        contentDescription = null,
                                        tint = TextSubtle,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (txSearchQuery.isNotBlank() || txDirectionFilter != "الكل" || txCashboxFilter != "كل الخزن") {
                                            "لا توجد معاملات مطابقة لخيارات البحث المحددة"
                                        } else {
                                            "لا توجد معاملات مالية مسجلة بعد. استخدم النموذج أعلاه لتسجيل أول حركة."
                                        },
                                        color = TextMuted,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(filteredMovements, key = { "tx_mov_${it.id}" }) { m ->
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("movement_card_${m.id}"),
                            onClick = { selectedMovementForDetail = m }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 10.dp)
                                ) {
                                    GlassBadge(
                                        text = m.direction,
                                        color = if (m.direction == "وارد") PmpGreen else PmpRed
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = m.description.ifBlank { "معاملة نقدية (${m.direction})" },
                                            color = TextWhite,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.5.sp,
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${m.cashbox} • ${m.txDate} • مستند: ${m.reference}",
                                            color = TextMuted,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "موظف الحساب: ${m.createdBy.ifBlank { "المدير" }} (اضغط لعرض التفاصيل)",
                                            color = PmpBrandNavy,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = (if (m.direction == "وارد") "+" else "-") + "${format.format(m.amount)} ${m.currency}",
                                        color = if (m.direction == "وارد") PmpGreen else PmpRed,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.5.sp,
                                        maxLines = 1
                                    )

                                    if (isManager || currentUser.hasModulePermission("صلاحية الحذف والتصفير")) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(
                                            onClick = { viewModel.deleteMovement(m.id) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "حذف الحركة",
                                                tint = PmpRedLight.copy(alpha = 0.7f),
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

            "تحويل خزن" -> {
                // Transfers
                item {
                    val srcAmountNum = trfAmount.toDoubleOrNull() ?: 0.0
                    val rateNum = trfRate.toDoubleOrNull() ?: 1.0
                    val calculatedDest = if (trfIsInvertedRate) {
                        if (rateNum > 0) srcAmountNum / rateNum else 0.0
                    } else {
                        srcAmountNum * rateNum
                    }

                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "تحويل أموال بين الخزن والعملات",
                                style = MaterialTheme.typography.titleMedium,
                                color = PmpGold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassDropdown(
                                    label = "من خزنة",
                                    options = boxNames,
                                    selectedOption = trfSourceBox,
                                    onOptionSelected = { trfSourceBox = it },
                                    modifier = Modifier.weight(1f)
                                )
                                GlassDropdown(
                                    label = "إلى خزنة",
                                    options = boxNames,
                                    selectedOption = trfDestBox,
                                    onOptionSelected = { trfDestBox = it },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassDropdown(
                                    label = "عملة الخصم",
                                    options = currencies,
                                    selectedOption = trfSourceCur,
                                    onOptionSelected = { trfSourceCur = it },
                                    modifier = Modifier.weight(1f)
                                )
                                GlassDropdown(
                                    label = "عملة الإضافة",
                                    options = currencies,
                                    selectedOption = trfDestCur,
                                    onOptionSelected = { trfDestCur = it },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassTextField(
                                    value = trfAmount,
                                    onValueChange = { trfAmount = it },
                                    label = "مبلغ الخصم ($trfSourceCur) *",
                                    placeholder = "0.00",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = trfRate,
                                    onValueChange = { trfRate = it },
                                    label = "سعر الصرف *",
                                    placeholder = "1.0",
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Exchange Rate Direction Selector (Reversal Option)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "اتجاه وطريقة احتساب سعر الصرف:",
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = !trfIsInvertedRate,
                                        onClick = { trfIsInvertedRate = false },
                                        label = { Text("مباشر (ضرب ×)", fontSize = 11.5.sp, maxLines = 1) },
                                        leadingIcon = {
                                            if (!trfIsInvertedRate) {
                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = trfIsInvertedRate,
                                        onClick = { trfIsInvertedRate = true },
                                        label = { Text("عكس السعر (قسمة ÷)", fontSize = 11.5.sp, maxLines = 1) },
                                        leadingIcon = {
                                            if (trfIsInvertedRate) {
                                                Icon(Icons.Default.SwapCalls, contentDescription = null, modifier = Modifier.size(15.dp))
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Preview Conversion & Calculation Formula
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = PmpNavySurface
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "المبلغ الناتج في $trfDestBox:",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextMuted
                                        )
                                        Text(
                                            text = "${format.format(calculatedDest)} $trfDestCur",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = PmpCyan,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = if (trfIsInvertedRate) {
                                            "المعادلة المعكوسة: $srcAmountNum $trfSourceCur ÷ $rateNum = ${format.format(calculatedDest)} $trfDestCur"
                                        } else {
                                            "المعادلة المباشرة: $srcAmountNum $trfSourceCur × $rateNum = ${format.format(calculatedDest)} $trfDestCur"
                                        },
                                        color = PmpGold,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassTextField(
                                    value = trfReference,
                                    onValueChange = { trfReference = it },
                                    label = "رقم المرجع *",
                                    placeholder = "TRF-001",
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextField(
                                    value = trfDescription,
                                    onValueChange = { trfDescription = it },
                                    label = "سبب التحويل",
                                    placeholder = "تغذية خزنة / تحويل أرباح",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Optional Halfa Invoice linking
                            if (halfaInvoices.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "ربط بسداد فاتورة حلفا (اختياري)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PmpGold
                                )
                                val halfaOptions = listOf("بدون ربط") + halfaInvoices.map { "${it.invoiceNo} (${format.format(it.total)} EGP)" }
                                var selectedHalfaText by remember { mutableStateOf("بدون ربط") }
                                GlassDropdown(
                                    label = "",
                                    options = halfaOptions,
                                    selectedOption = selectedHalfaText,
                                    onOptionSelected = { chosen ->
                                        selectedHalfaText = chosen
                                        trfHalfaInvoiceId = halfaInvoices.find { chosen.startsWith(it.invoiceNo) }?.id
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            GlassButton(
                                text = "تحويل",
                                icon = Icons.Default.SwapHoriz,
                                gradient = GlassAccentGoldGradient,
                                onClick = {
                                    val amt = trfAmount.toDoubleOrNull() ?: 0.0
                                    val rate = trfRate.toDoubleOrNull() ?: 1.0
                                    viewModel.addTransfer(
                                        sourceCashbox = trfSourceBox,
                                        destinationCashbox = trfDestBox,
                                        sourceCurrency = trfSourceCur,
                                        destinationCurrency = trfDestCur,
                                        sourceAmount = amt,
                                        rate = rate,
                                        description = trfDescription,
                                        reference = trfReference,
                                        halfaInvoiceId = trfHalfaInvoiceId,
                                        isInvertedRate = trfIsInvertedRate
                                    )
                                    if (amt > 0 && trfReference.isNotBlank()) {
                                        trfAmount = ""
                                        trfReference = ""
                                        trfDescription = ""
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Recent Transfers List
                item {
                    Text(
                        text = "سجل التحويلات المنفذة",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                items(transfers, key = { it.id }) { t ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { selectedTransferForDetail = t }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "تحويل ${t.reference}",
                                    color = PmpGold,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(text = t.txDate, color = TextMuted, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "من: ${t.sourceCashbox} (${format.format(t.sourceAmount)} ${t.sourceCurrency}) ➔ إلى: ${t.destinationCashbox} (${format.format(t.destinationAmount)} ${t.destinationCurrency})",
                                color = TextWhite,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "موظف الحساب المنفذ: ${t.createdBy.ifBlank { "المدير" }} (اضغط للتفاصيل)",
                                color = PmpBrandNavy,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            )
                            if (t.description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(text = t.description, color = TextSubtle, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            "إقفال اليومية" -> {
                // Daily Closing Form
                item {
                    val bookBal = cashboxBalances[Pair(closeBox, closeCur)] ?: 0.0
                    val actualVal = closeActualAmount.toDoubleOrNull() ?: 0.0
                    val diff = actualVal - bookBal

                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "إقفال اليومية ومطابقة الجرد",
                                style = MaterialTheme.typography.titleMedium,
                                color = PmpPurple,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassDropdown(
                                    label = "الخزنة المراد إقفالها",
                                    options = boxNames,
                                    selectedOption = closeBox,
                                    onOptionSelected = { closeBox = it },
                                    modifier = Modifier.weight(1.2f)
                                )
                                GlassDropdown(
                                    label = "العملة",
                                    options = currencies,
                                    selectedOption = closeCur,
                                    onOptionSelected = { closeCur = it },
                                    modifier = Modifier.weight(0.8f)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Comparison Card
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = PmpNavySurface
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(text = "الرصيد الدفتري المتوقع:", color = TextMuted)
                                        Text(
                                            text = "${format.format(bookBal)} $closeCur",
                                            color = TextWhite,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(text = "الفارق المحسوب:", color = TextMuted)
                                        Text(
                                            text = "${format.format(diff)} $closeCur",
                                            color = if (diff == 0.0) PmpGreen else if (diff > 0) PmpCyan else PmpRed,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            GlassTextField(
                                value = closeActualAmount,
                                onValueChange = { closeActualAmount = it },
                                label = "الرصيد الفعلي بعد العد والعدالة النقدية *",
                                placeholder = "0.00",
                                isNumber = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            GlassTextField(
                                value = closeNotes,
                                onValueChange = { closeNotes = it },
                                label = "ملاحظات وتوقيع الجرد",
                                placeholder = "تم الجرد بواسطة فلان...",
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            GlassButton(
                                text = "إقفال اليومية",
                                icon = Icons.Default.Lock,
                                onClick = {
                                    viewModel.performDailyClosing(
                                        cashbox = closeBox,
                                        currency = closeCur,
                                        bookBalance = bookBal,
                                        countedBalance = actualVal,
                                        notes = closeNotes
                                    )
                                    closeActualAmount = ""
                                    closeNotes = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item {
                    Text(
                        text = "سجل الإقفالات اليومية السابقة",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                items(dailyClosings, key = { it.id }) { c ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { selectedClosingForDetail = c }
                    ) {
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
                                    .padding(end = 8.dp)
                            ) {
                                Text(
                                    text = "${c.cashbox} • ${c.closeDate}",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "دفتري: ${format.format(c.bookBalance)} | فعلي: ${format.format(c.countedBalance)} ${c.currency}",
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "موظف الحساب: ${c.preparedBy.ifBlank { "المدير" }} (اضغط للتفاصيل)",
                                    color = PmpBrandNavy,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp
                                )
                            }
                            GlassBadge(
                                text = if (c.difference == 0.0) "مطابق" else "${format.format(c.difference)} ${c.currency}",
                                color = if (c.difference == 0.0) PmpGreen else PmpRed
                            )
                        }
                    }
                }
            }

            "كشف الحساب" -> {
                // Statement of Account (كشف حساب)
                val boxMovements = movements.filter {
                    it.cashbox.trim().equals(stBox.trim(), ignoreCase = true) &&
                    it.currency.trim().equals(stCur.trim(), ignoreCase = true)
                }
                val currentTotal = cashboxBalances[Pair(stBox.trim(), stCur.trim())] ?: 0.0

                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "كشف حساب مفصل للخزنة",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = PmpCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                GlassBadge(
                                    text = "${boxMovements.size} حركة",
                                    color = PmpCyan
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassDropdown(
                                    label = "الخزنة",
                                    options = boxNames,
                                    selectedOption = stBox,
                                    onOptionSelected = { stBox = it },
                                    modifier = Modifier.weight(1.2f)
                                )
                                GlassDropdown(
                                    label = "العملة",
                                    options = currencies,
                                    selectedOption = stCur,
                                    onOptionSelected = { cur ->
                                        stCur = cur
                                        stBox = viewModel.resolveCashboxForCurrency(cur)
                                    },
                                    modifier = Modifier.weight(0.8f)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "إجمالي الرصيد الختامي:", color = TextMuted)
                                Text(
                                    text = "${format.format(currentTotal)} $stCur",
                                    color = if (currentTotal >= 0) PmpGreenLight else PmpRedLight,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Direct Excel Export Buttons for Treasury Statement
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                GlassButton(
                                    text = "تصدير كشف الحساب إكسيل (Excel)",
                                    icon = Icons.Default.TableChart,
                                    gradient = GlassAccentGreenGradient,
                                    onClick = {
                                        val res = StatementPdfAndExcelExporter.exportTreasuryStatementToExcel(
                                            context = context,
                                            cashboxName = stBox,
                                            currency = stCur,
                                            movements = boxMovements,
                                            closingBalance = currentTotal,
                                            customOutputUri = null
                                        )
                                        res.onSuccess { savedPath ->
                                            viewModel.postMessage("تم تصدير كشف حساب ($stBox - $stCur) في ملف إكسيل بنجاح إلى: $savedPath")
                                        }.onFailure { err ->
                                            viewModel.postMessage("تعذر تصدير ملف إكسيل: ${err.message}", isError = true)
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1.25f)
                                        .height(44.dp)
                                        .testTag("export_treasury_excel_direct_btn")
                                )

                                GlassButton(
                                    text = "حفظ Excel باسم...",
                                    icon = Icons.Default.SaveAlt,
                                    gradient = GlassAccentBlueGradient,
                                    onClick = {
                                        val safeName = stBox.replace(" ", "_")
                                        createTreasuryExcelLauncher.launch("pmp_treasury_${safeName}_${stCur}.csv")
                                    },
                                    modifier = Modifier
                                        .weight(0.75f)
                                        .height(44.dp)
                                        .testTag("export_treasury_excel_save_as_btn")
                                )
                            }
                        }
                    }
                }

                if (boxMovements.isEmpty()) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.ReceiptLong,
                                        contentDescription = null,
                                        tint = TextSubtle,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "لا توجد معاملات مسجلة لخزنة ($stBox) بالعملة ($stCur) حتى الآن",
                                        color = TextMuted,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val displayedBoxMovements = if (showAllBoxMovements) boxMovements else boxMovements.take(30)
                    items(displayedBoxMovements, key = { "stmt_m_${it.id}" }) { m ->
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { selectedMovementForDetail = m }
                        ) {
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
                                        .padding(end = 10.dp)
                                ) {
                                    GlassBadge(
                                        text = m.direction,
                                        color = if (m.direction == "وارد") PmpGreen else PmpRed
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = m.description.ifBlank { "حركة نقدية ${m.direction}" }, color = TextWhite, fontSize = 13.sp, maxLines = 1)
                                        Text(
                                            text = "${m.txDate} • مستند: ${m.reference}",
                                            color = TextSubtle,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "موظف الحساب: ${m.createdBy.ifBlank { "المدير" }} (اضغط للتفاصيل)",
                                            color = PmpBrandNavy,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = (if (m.direction == "وارد") "+" else "-") + format.format(m.amount),
                                        color = if (m.direction == "وارد") PmpGreen else PmpRed,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )

                                    if (isManager || currentUser.hasModulePermission("صلاحية الحذف والتصفير")) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(
                                            onClick = { viewModel.deleteMovement(m.id) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "حذف الحركة",
                                                tint = PmpRedLight.copy(alpha = 0.7f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (boxMovements.size > 30) {
                        item {
                            OutlinedButton(
                                onClick = { showAllBoxMovements = !showAllBoxMovements },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PmpCyan)
                            ) {
                                Text(
                                    text = if (showAllBoxMovements) "عرض أقل (العودة لـ 30)" else "عرض كامل السجل (${boxMovements.size - 30} حركات إضافية)",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            "إدارة الخزن" -> {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "الخزن النقدية المسجلة (${cashboxes.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold
                        )
                        if (isManager) {
                            GlassButton(
                                text = "＋ إضافة خزنة جديدة",
                                onClick = { showAddCashboxDialog = true },
                                modifier = Modifier.height(38.dp)
                            )
                        }
                    }
                }

                items(cashboxes, key = { "cb_${it.id}" }) { box ->
                    val sdgBal = cashboxBalances[Pair(box.name, "SDG")] ?: 0.0
                    val egpBal = cashboxBalances[Pair(box.name, "EGP")] ?: 0.0
                    val usdBal = cashboxBalances[Pair(box.name, "USD")] ?: 0.0
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
                                    Text(
                                        text = box.name,
                                        color = TextWhite,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    GlassBadge(text = "خزنة معتمدة", color = PmpGold)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "الأرصدة: SDG ${format.format(sdgBal)} | EGP ${format.format(egpBal)} | USD ${format.format(usdBal)}",
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }

                            if (isManager || currentUser.hasModulePermission("صلاحية الحذف والتصفير")) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { boxToZeroOut = box.name },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.RestartAlt,
                                            contentDescription = "تصفير رصيد الخزنة",
                                            tint = PmpGold,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            editingCashbox = box
                                            editCashboxName = box.name
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "تعديل الخزنة",
                                            tint = PmpCyan,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { deletingCashbox = box },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "حذف الخزنة",
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
        }
    }

    // Modal: Add Cashbox
    if (showAddCashboxDialog) {
        AlertDialog(
            onDismissRequest = { showAddCashboxDialog = false },
            title = { Text(text = "إضافة خزنة نقدية جديدة", color = PmpCyan, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassTextField(
                        value = newCashboxName,
                        onValueChange = { newCashboxName = it },
                        label = "اسم الخزنة *"
                    )
                    GlassDropdown(
                        label = "العملة الأساسية للخزنة",
                        options = listOf("SDG", "EGP", "USD"),
                        selectedOption = newCashboxCurrency,
                        onOptionSelected = { newCashboxCurrency = it }
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "حفظ الخزنة",
                    onClick = {
                        if (newCashboxName.isNotBlank()) {
                            viewModel.addCashbox(newCashboxName)
                            newCashboxName = ""
                            showAddCashboxDialog = false
                        }
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { showAddCashboxDialog = false })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Edit Cashbox
    editingCashbox?.let { box ->
        AlertDialog(
            onDismissRequest = { editingCashbox = null },
            title = { Text(text = "تعديل اسم الخزنة", color = PmpCyan, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassTextField(
                        value = editCashboxName,
                        onValueChange = { editCashboxName = it },
                        label = "اسم الخزنة *"
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "حفظ التعديلات",
                    onClick = {
                        if (editCashboxName.isNotBlank()) {
                            viewModel.updateCashbox(box.id, editCashboxName)
                            editingCashbox = null
                        }
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { editingCashbox = null })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Delete / Archive Cashbox Confirmation
    deletingCashbox?.let { box ->
        val linkedMovesCount = remember(box.name, movements) {
            movements.count { it.cashbox.trim().equals(box.name.trim(), ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { deletingCashbox = null },
            title = {
                Text(
                    text = if (linkedMovesCount > 0) "أرشفة وإلغاء تفعيل الخزنة" else "حذف الخزنة نهائياً",
                    color = PmpRedLight,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (linkedMovesCount > 0) {
                            "الخزنة (${box.name}) مرتبطة بـ ($linkedMovesCount) حركة مالية مسجلة. سيتم إزالتها وأرشفتها من قائمة الخزن النشطة في جميع الأجهزة والحسابات فوراً، مع الاحتفاظ الكامل (100%) بجميع السجلات والحركات المالية التاريخية وأرصدتها في التقارير وكشف الحساب."
                        } else {
                            "هل أنت متأكد من حذف الخزنة (${box.name}) نهائياً من النظام ومزامنة الحذف عبر جميع الأجهزة؟"
                        },
                        color = TextWhite,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = if (linkedMovesCount > 0) "تأكيد الأرشفة ومزامنة الحذف" else "نعم، حذف نهائي",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        viewModel.deleteCashbox(box.id, box.name)
                        deletingCashbox = null
                    },
                    modifier = Modifier.testTag("confirm_delete_cashbox_button")
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { deletingCashbox = null })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Zero Cashbox Balance Confirmation
    boxToZeroOut?.let { boxName ->
        AlertDialog(
            onDismissRequest = { boxToZeroOut = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, tint = PmpGold)
                    Text(text = "تأكيد تصفير رصيد الخزنة", color = PmpGold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Text(
                    text = "هل أنت متأكد من رغبتك في تصفير رصيد وحركات الخزنة ($boxName) بالكامل؟ لا يمكن التراجع عن هذه العملية بعد التأكيد.",
                    color = TextWhite,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                GlassButton(
                    text = "نعم، تصفير الخزنة",
                    gradient = PmpBrandRedGradient,
                    onClick = {
                        viewModel.resetCashboxBalance(boxName)
                        boxToZeroOut = null
                    }
                )
            },
            dismissButton = {
                GlassButton(text = "إلغاء", onClick = { boxToZeroOut = null })
            },
            containerColor = PmpNavySurface
        )
    }

    // Modal: Movement Transaction Details Dialog (Displays Full Details & Employee Name)
    selectedMovementForDetail?.let { m ->
        val createdTimeStr = remember(m.createdAt) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(m.createdAt))
        }
        AlertDialog(
            onDismissRequest = { selectedMovementForDetail = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (m.direction == "وارد") Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = if (m.direction == "وارد") PmpGreen else PmpRed
                    )
                    Text(
                        text = "تفاصيل المعاملة المالية (${m.reference})",
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
                                text = m.createdBy.ifBlank { "المدير" },
                                color = PmpBrandRed,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.5.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "نوع الحركة المالية:", color = TextMuted, fontSize = 12.5.sp)
                        GlassBadge(
                            text = m.direction,
                            color = if (m.direction == "وارد") PmpGreen else PmpRed
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "المبلغ والعملة:", color = TextMuted, fontSize = 12.5.sp)
                        Text(
                            text = "${format.format(m.amount)} ${m.currency}",
                            color = if (m.direction == "وارد") PmpGreen else PmpRed,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "الخزنة:", color = TextMuted, fontSize = 12.5.sp)
                        Text(text = m.cashbox, color = TextDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "رقم المستند / المرجع:", color = TextMuted, fontSize = 12.5.sp)
                        Text(text = m.reference, color = TextDark, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "تاريخ وتوقيت التنفيذ:", color = TextMuted, fontSize = 12.5.sp)
                        Text(text = "${m.txDate} ($createdTimeStr)", color = TextDark, fontSize = 11.5.sp)
                    }
                    HorizontalDivider(color = GlassBorder)
                    Text(text = "البيان والتفاصيل الكاملة للمعاملة:", color = PmpBrandNavy, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    Text(
                        text = m.description.ifBlank { "معاملة نقدية (${m.direction})" },
                        color = TextDark,
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "إغلاق",
                    onClick = { selectedMovementForDetail = null }
                )
            },
            containerColor = PmpWhiteSurface
        )
    }

    // Modal: Transfer Details Dialog (Displays Full Transfer Details & Employee Name)
    selectedTransferForDetail?.let { t ->
        val createdTimeStr = remember(t.createdAt) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(t.createdAt))
        }
        AlertDialog(
            onDismissRequest = { selectedTransferForDetail = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = PmpGold)
                    Text(
                        text = "تفاصيل عملية التحويل (${t.reference})",
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
                            Text(text = "اسم موظف الحساب الذي قام بعمل التحويل:", color = TextMuted, fontSize = 11.sp)
                            Text(
                                text = t.createdBy.ifBlank { "المدير" },
                                color = PmpBrandRed,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.5.sp
                            )
                        }
                    }
                    Text(text = "من خزنة: ${t.sourceCashbox} (${format.format(t.sourceAmount)} ${t.sourceCurrency})", color = PmpRed, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    Text(text = "إلى خزنة: ${t.destinationCashbox} (${format.format(t.destinationAmount)} ${t.destinationCurrency})", color = PmpGreen, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    Text(text = "معامل الصرف: ${t.rate}", color = TextDark, fontSize = 12.5.sp)
                    Text(text = "تاريخ العملية: ${t.txDate} ($createdTimeStr)", color = TextMuted, fontSize = 12.sp)
                    if (t.description.isNotBlank()) {
                        HorizontalDivider(color = GlassBorder)
                        Text(text = "البيان والملاحظات: ${t.description}", color = TextDark, fontSize = 12.5.sp)
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    text = "إغلاق",
                    onClick = { selectedTransferForDetail = null }
                )
            },
            containerColor = PmpWhiteSurface
        )
    }

    // Modal: Daily Closing Details Dialog (Displays Closing Details & Employee Name)
    selectedClosingForDetail?.let { c ->
        AlertDialog(
            onDismissRequest = { selectedClosingForDetail = null },
            title = {
                Text(
                    text = "تفاصيل إقفال اليومية (${c.cashbox})",
                    color = PmpBrandNavy,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
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
                            Text(text = "اسم موظف الحساب الذي قام بالإقفال:", color = TextMuted, fontSize = 11.sp)
                            Text(
                                text = c.preparedBy.ifBlank { "المدير" },
                                color = PmpBrandRed,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.5.sp
                            )
                        }
                    }
                    Text(text = "التاريخ: ${c.closeDate}", color = TextDark, fontSize = 13.sp)
                    Text(text = "الرصيد الدفتري: ${format.format(c.bookBalance)} ${c.currency}", color = TextDark, fontSize = 13.sp)
                    Text(text = "الرصيد الفعلي المعدود: ${format.format(c.countedBalance)} ${c.currency}", color = PmpGreen, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    Text(text = "الفارق: ${format.format(c.difference)} ${c.currency}", color = if (c.difference == 0.0) PmpGreen else PmpRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (c.notes.isNotBlank()) {
                        Text(text = "ملاحظات الجرد: ${c.notes}", color = TextMuted, fontSize = 12.5.sp)
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    text = "إغلاق",
                    onClick = { selectedClosingForDetail = null }
                )
            },
            containerColor = PmpWhiteSurface
        )
    }
}
