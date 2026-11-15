package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.*
import com.example.domain.accounting.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import java.text.NumberFormat
import java.util.Locale

private fun accountTypeLabelAr(type: String): String = when (type) {
    AccountType.ASSET.name -> "أصول"
    AccountType.LIABILITY.name -> "التزامات"
    AccountType.EQUITY.name -> "حقوق ملكية"
    AccountType.REVENUE.name -> "إيرادات"
    else -> "مصروفات وتكاليف"
}

private fun sourceTypeLabelAr(sourceType: String): String = when (sourceType) {
    "PURCHASE_RECEIPT" -> "استلام مشتريات خام"
    "PRODUCTION_ISSUE" -> "صرف خامات للإنتاج"
    "QC_FG_RECEIPT" -> "ترحيل جودة للإنتاج التام"
    "SALES_INVOICE" -> "فاتورة مبيعات وتكلفة"
    "CUSTOMER_PAYMENT" -> "تحصيل دفعة عميل"
    "INVOICE_CANCEL" -> "إلغاء وعكس فاتورة"
    "TREASURY_MOVEMENT" -> "حركة خزنة"
    "TREASURY_TRANSFER" -> "تحويل بين الخزن"
    "REVERSAL" -> "قيد عكسي"
    else -> "قيد يدوي / تسوية"
}

/**
 * 1. لوحة دفتر الأستاذ والقيود المزدوجة ودليل الحسابات (ReportsScreen Tab: دفتر الأستاذ والقيود)
 */
@Composable
fun AccountingJournalLedgerPanel(
    viewModel: PmpViewModel,
    searchQuery: String
) {
    val accounts by viewModel.accountingAccounts.collectAsState()
    val entries by viewModel.journalEntries.collectAsState()
    val lines by viewModel.journalLines.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val activeGuardKeys by viewModel.activeGuardKeys.collectAsState()
    val guardLoadingLabels by viewModel.guardLoadingLabels.collectAsState()
    val format = remember { NumberFormat.getNumberInstance(Locale.US) }

    val effectiveAccounts = remember(accounts) {
        if (accounts.isNotEmpty()) accounts else AccountingLedgerEngine.DEFAULT_ACCOUNTS
    }
    val linesByEntryId = remember(lines) { lines.groupBy { it.journalEntryId } }

    var selectedCurrencyFilter by remember { mutableStateOf("الكل") }
    var showChartOfAccounts by remember { mutableStateOf(false) }
    var showNewEntryDialog by remember { mutableStateOf(false) }
    var reversingTargetEntry by remember { mutableStateOf<JournalEntryEntity?>(null) }

    val filteredEntries = remember(entries, selectedCurrencyFilter, searchQuery) {
        entries.filter { entry ->
            val curOk = selectedCurrencyFilter == "الكل" ||
                AccountingLedgerEngine.resolveCanonicalCurrency(entry.currency) == selectedCurrencyFilter
            val q = searchQuery.trim()
            val searchOk = q.isBlank() ||
                entry.entryNo.contains(q, ignoreCase = true) ||
                entry.description.contains(q, ignoreCase = true) ||
                entry.sourceDocNo.contains(q, ignoreCase = true) ||
                entry.createdBy.contains(q, ignoreCase = true)
            curOk && searchOk
        }
    }

    val allBalanced = remember(entries) {
        entries.all { kotlin.math.abs(it.totalDebit - it.totalCredit) < 0.01 }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = PmpCyan.copy(alpha = 0.45f),
            cornerRadius = 18.dp
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                        Icon(Icons.Default.MenuBook, contentDescription = null, tint = PmpCyan, modifier = Modifier.size(22.dp))
                        Column {
                            Text(
                                text = "دفتر الأستاذ العام والقيود المزدوجة",
                                color = TextWhite,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "${entries.size} قيد مرحل • ${effectiveAccounts.size} حساب في الدليل المحاسبي",
                                color = TextMuted,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                    GlassBadge(
                        text = if (allBalanced) "متوازن 100%" else "مراجعة توازن",
                        color = if (allBalanced) PmpGreen else PmpRed
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassButton(
                        text = "+ قيد يومية يدوي",
                        onClick = { showNewEntryDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("open_manual_journal_btn"),
                        icon = Icons.Default.AddCircleOutline,
                        gradient = SolidColor(PmpBrandNavy)
                    )
                    GlassButton(
                        text = if (showChartOfAccounts) "إخفاء دليل الحسابات" else "دليل الحسابات (${effectiveAccounts.size})",
                        onClick = { showChartOfAccounts = !showChartOfAccounts },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("toggle_coa_btn"),
                        icon = Icons.Default.AccountTree,
                        gradient = SolidColor(PmpCyan)
                    )
                }

                // Currency Filter Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("الكل", "SDG", "EGP", "USD").forEach { cur ->
                        FilterChip(
                            selected = selectedCurrencyFilter == cur,
                            onClick = { selectedCurrencyFilter = cur },
                            label = { Text(cur, fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (showChartOfAccounts) {
            GlassCard(modifier = Modifier.fillMaxWidth(), borderColor = PmpGold.copy(alpha = 0.4f)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "دليل الحسابات الموحد (Chart of Accounts)",
                        color = TextWhite,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                    effectiveAccounts.forEach { acc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(PmpSubtleSurface)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${acc.accountCode} — ${acc.nameAr}",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                )
                                Text(
                                    text = "${acc.nameEn} • الطبيعة: ${if (acc.normalSide == "DEBIT") "مدين" else "دائن"}",
                                    color = TextMuted,
                                    fontSize = 10.5.sp
                                )
                            }
                            GlassBadge(
                                text = accountTypeLabelAr(acc.accountType),
                                color = when (acc.accountType) {
                                    AccountType.ASSET.name -> PmpCyan
                                    AccountType.LIABILITY.name -> PmpRed
                                    AccountType.EQUITY.name -> PmpGold
                                    AccountType.REVENUE.name -> PmpGreen
                                    else -> PmpBrandNavy
                                }
                            )
                        }
                    }
                }
            }
        }

        if (filteredEntries.isEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = TextMuted, modifier = Modifier.size(34.dp))
                    Text("لا توجد قيود يومية مسجلة في هذا الفلتر بعد", color = TextMuted, fontSize = 13.sp)
                    Text(
                        "تُرحّل القيود آلياً عند المبيعات والتحصيل والمشتريات والإنتاج والتحويلات أو يدوياً من الأعلى.",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            filteredEntries.forEach { entry ->
                val entryLines = linesByEntryId[entry.id].orEmpty()
                val isReversed = entry.status == "REVERSED"
                val isReversalDoc = entry.sourceType == "REVERSAL"
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("journal_entry_card_${entry.id}"),
                    borderColor = when {
                        isReversed -> PmpRed.copy(alpha = 0.45f)
                        isReversalDoc -> PmpGold.copy(alpha = 0.45f)
                        else -> GlassBorder
                    }
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "${entry.entryNo} • ${sourceTypeLabelAr(entry.sourceType)}",
                                    color = TextWhite,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${entry.entryDate} • ${entry.createdBy}" +
                                        if (entry.sourceDocNo.isNotBlank()) " • مرجع: ${entry.sourceDocNo}" else "",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${format.format(entry.totalDebit)} ${entry.currency}",
                                    color = PmpGreen,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp
                                )
                                GlassBadge(
                                    text = if (isReversed) "معكوس" else if (isReversalDoc) "قيد عكسي" else "مرحّل",
                                    color = if (isReversed) PmpRed else if (isReversalDoc) PmpGold else PmpCyan
                                )
                            }
                        }

                        Text(text = entry.description, color = TextDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

                        if (isReversed && entry.reversedByEntryNo.isNotBlank()) {
                            Text(
                                text = "عُكس بالقيد ${entry.reversedByEntryNo} (${entry.reversalReason})",
                                color = PmpRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        HorizontalDivider(color = GlassBorder.copy(alpha = 0.5f), thickness = 0.5.dp)

                        entryLines.forEach { ln ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(PmpSubtleSurface)
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                    Text(
                                        text = "${ln.accountCode} • ${ln.accountName}",
                                        color = TextWhite,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (ln.partyName.isNotBlank()) {
                                        Text(text = ln.partyName, color = TextMuted, fontSize = 10.sp)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = if (ln.debit > 0.0) "مدين: ${format.format(ln.debit)}" else "",
                                        color = PmpCyan,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = if (ln.credit > 0.0) "دائن: ${format.format(ln.credit)}" else "",
                                        color = PmpGold,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        if (!isReversed && !isReversalDoc && currentUser != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { reversingTargetEntry = entry },
                                    modifier = Modifier.testTag("reverse_journal_btn_${entry.id}")
                                ) {
                                    Icon(Icons.Default.Undo, contentDescription = null, tint = PmpRed, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("عكس القيد (قيد تصحيحي)", color = PmpRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewEntryDialog) {
        val journalGuard = "phase4_post_journal"
        val isPostingJournal = activeGuardKeys.contains(journalGuard)
        ManualJournalEntryDialog(
            accounts = effectiveAccounts,
            isSubmitting = isPostingJournal,
            loadingText = guardLoadingLabels[journalGuard] ?: "جاري الحفظ...",
            onDismiss = { if (!isPostingJournal) showNewEntryDialog = false },
            onSubmit = { drCode, crCode, amount, cur, desc ->
                viewModel.postManualJournalEntry(
                    debitAccountCode = drCode,
                    creditAccountCode = crCode,
                    amount = amount,
                    currency = cur,
                    description = desc,
                    guardKey = journalGuard,
                    onComplete = { ok ->
                        if (ok) showNewEntryDialog = false
                    }
                )
            }
        )
    }

    reversingTargetEntry?.let { target ->
        var reason by remember(target.id) { mutableStateOf("") }
        val revJournalGuard = "phase4_reverse_journal_${target.id}"
        val isReversingJournal = activeGuardKeys.contains(revJournalGuard)
        AlertDialog(
            onDismissRequest = { if (!isReversingJournal) reversingTargetEntry = null },
            title = {
                Text(
                    "عكس القيد ${target.entryNo}",
                    color = PmpBrandNavy,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "نظام دفتر الأستاذ لا يسمح بالحذف. سيتم إنشاء قيد عكسي متوازن بقيمة ${format.format(target.totalDebit)} ${target.currency}.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("سبب عكس القيد (إلزامي)") },
                        modifier = Modifier.fillMaxWidth().testTag("reverse_journal_reason_input"),
                        singleLine = false
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "تأكيد العكس",
                    isLoading = isReversingJournal,
                    loadingText = guardLoadingLabels[revJournalGuard] ?: "جاري تحديث البيانات...",
                    enabled = reason.isNotBlank() && !isReversingJournal,
                    onClick = {
                        if (reason.isNotBlank()) {
                            viewModel.reverseAccountingJournal(
                                entryId = target.id,
                                reason = reason,
                                guardKey = revJournalGuard,
                                onComplete = { ok ->
                                    if (ok) reversingTargetEntry = null
                                }
                            )
                        }
                    },
                    gradient = SolidColor(PmpRed)
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { reversingTargetEntry = null },
                    enabled = !isReversingJournal
                ) {
                    Text("إلغاء")
                }
            },
            containerColor = PmpWhiteCard
        )
    }
}

@Composable
private fun ManualJournalEntryDialog(
    accounts: List<ChartAccountEntity>,
    isSubmitting: Boolean = false,
    loadingText: String = "جاري الحفظ...",
    onDismiss: () -> Unit,
    onSubmit: (String, String, Double, String, String) -> Unit
) {
    var debitCode by remember { mutableStateOf("5300") }
    var creditCode by remember { mutableStateOf("1110") }
    var currency by remember { mutableStateOf("SDG") }
    var amountText by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Text(
                "ترحيل قيد يومية مزدوج متوازن",
                color = PmpBrandNavy,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("SDG", "EGP", "USD").forEach { cur ->
                        FilterChip(
                            selected = currency == cur,
                            onClick = { currency = cur },
                            label = { Text(cur, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text("الحساب المدين (DR):", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = PmpCyan)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    accounts.take(18).chunked(2).forEach { pair ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            pair.forEach { acc ->
                                val selected = debitCode == acc.accountCode
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) PmpCyan.copy(alpha = 0.2f) else PmpSubtleSurface)
                                        .border(1.dp, if (selected) PmpCyan else GlassBorder, RoundedCornerShape(8.dp))
                                        .clickable { debitCode = acc.accountCode }
                                        .padding(6.dp)
                                ) {
                                    Text("${acc.accountCode} ${acc.nameAr}", fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextWhite)
                                }
                            }
                        }
                    }
                }

                Text("الحساب الدائن (CR):", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = PmpGold)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    accounts.take(18).chunked(2).forEach { pair ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            pair.forEach { acc ->
                                val selected = creditCode == acc.accountCode
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) PmpGold.copy(alpha = 0.2f) else PmpSubtleSurface)
                                        .border(1.dp, if (selected) PmpGold else GlassBorder, RoundedCornerShape(8.dp))
                                        .clickable { creditCode = acc.accountCode }
                                        .padding(6.dp)
                                ) {
                                    Text("${acc.accountCode} ${acc.nameAr}", fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextWhite)
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("المبلغ ($currency)") },
                    modifier = Modifier.fillMaxWidth().testTag("manual_journal_amount_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("بيان / شرح القيد المحاسبي") },
                    modifier = Modifier.fillMaxWidth().testTag("manual_journal_desc_input"),
                    singleLine = false
                )
            }
        },
        confirmButton = {
            val amount = amountText.toDoubleOrNull() ?: 0.0
            GlassButton(
                text = "ترحيل القيد",
                isLoading = isSubmitting,
                loadingText = loadingText,
                onClick = {
                    if (amount > 0.0 && description.isNotBlank() && debitCode != creditCode) {
                        onSubmit(debitCode, creditCode, amount, currency, description)
                    }
                },
                enabled = amount > 0.0 && description.isNotBlank() && debitCode != creditCode && !isSubmitting,
                gradient = SolidColor(PmpBrandNavy)
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("إلغاء") }
        },
        containerColor = PmpWhiteCard
    )
}

/**
 * 2. لوحة ميزان المراجعة والقوائم المالية والمطابقة متعددة العملات (ReportsScreen Tab: القوائم المالية والتسوية)
 */
@Composable
fun FinancialStatementsPanel(
    viewModel: PmpViewModel
) {
    val accounts by viewModel.accountingAccounts.collectAsState()
    val entries by viewModel.journalEntries.collectAsState()
    val lines by viewModel.journalLines.collectAsState()
    val cashboxBalances by viewModel.cashboxBalances.collectAsState()
    val invoices by viewModel.invoices.collectAsState()
    val invoicePayments by viewModel.invoicePayments.collectAsState()
    val stockBalances by viewModel.erpStockBalances.collectAsState()

    val format = remember { NumberFormat.getNumberInstance(Locale.US) }
    var selectedCurrency by remember { mutableStateOf("SDG") }
    var egpRateInput by remember { mutableStateOf("12.5") }
    var usdRateInput by remember { mutableStateOf("600.0") }

    val effectiveAccounts = remember(accounts) {
        if (accounts.isNotEmpty()) accounts else AccountingLedgerEngine.DEFAULT_ACCOUNTS
    }

    val trialBalance = remember(effectiveAccounts, entries, lines, selectedCurrency) {
        AccountingLedgerEngine.computeTrialBalance(effectiveAccounts, entries, lines, selectedCurrency)
    }
    val incomeStatement = remember(trialBalance) {
        AccountingLedgerEngine.computeIncomeStatement(trialBalance)
    }
    val balanceSheet = remember(trialBalance, incomeStatement) {
        AccountingLedgerEngine.computeBalanceSheet(trialBalance, incomeStatement.netIncome)
    }
    val reconciliation = remember(selectedCurrency, cashboxBalances, invoices, invoicePayments, stockBalances, trialBalance) {
        AccountingLedgerEngine.computeReconciliationReport(
            currency = selectedCurrency,
            cashboxBalances = cashboxBalances,
            invoices = invoices,
            payments = invoicePayments,
            stockBalances = stockBalances,
            trialBalance = trialBalance
        )
    }
    val fxRates = CurrencyFxRates(
        egpToSdg = (egpRateInput.toDoubleOrNull() ?: 12.5).coerceAtLeast(0.01),
        usdToSdg = (usdRateInput.toDoubleOrNull() ?: 600.0).coerceAtLeast(0.01)
    )
    val consolidated = remember(effectiveAccounts, entries, lines, fxRates) {
        AccountingLedgerEngine.computeConsolidatedFxSummary(effectiveAccounts, entries, lines, fxRates)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Currency Isolation Switcher
        GlassCard(modifier = Modifier.fillMaxWidth(), borderColor = PmpCyan.copy(alpha = 0.5f)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "القوائم المالية المعزولة بالعملة (SDG / EGP / USD)",
                    color = TextWhite,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp
                )
                Text(
                    text = "يُمنع جمع الجنيه السوداني والمصري والدولار مباشرةً؛ اختر العملة لعرض ميزان المراجعة وقائمة الدخل والميزانية بدقة.",
                    color = TextMuted,
                    fontSize = 11.5.sp
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("SDG", "EGP", "USD").forEach { cur ->
                        FilterChip(
                            selected = selectedCurrency == cur,
                            onClick = { selectedCurrency = cur },
                            label = { Text(cur, fontWeight = FontWeight.ExtraBold) },
                            modifier = Modifier.weight(1f).testTag("fin_currency_chip_$cur")
                        )
                    }
                }
            }
        }

        // KPI Summary Row (Income Statement Highlights)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassStatCard(
                title = "صافي المبيعات ($selectedCurrency)",
                value = format.format(incomeStatement.totalRevenue),
                unit = selectedCurrency,
                subtitle = "التكلفة: ${format.format(incomeStatement.totalCogs)}",
                accentColor = PmpCyan,
                icon = Icons.Default.TrendingUp,
                modifier = Modifier.weight(1f)
            )
            GlassStatCard(
                title = "صافي الربح / الخسارة ($selectedCurrency)",
                value = format.format(incomeStatement.netIncome),
                unit = selectedCurrency,
                subtitle = "مجمل الربح: ${format.format(incomeStatement.grossProfit)}",
                accentColor = if (incomeStatement.netIncome >= 0.0) PmpGreen else PmpRed,
                icon = Icons.Default.Assessment,
                modifier = Modifier.weight(1f)
            )
        }

        // Trial Balance Card
        GlassCard(modifier = Modifier.fillMaxWidth(), borderColor = PmpBrandNavy.copy(alpha = 0.45f)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ميزان المراجعة ($selectedCurrency)",
                        color = TextWhite,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.5.sp
                    )
                    GlassBadge(
                        text = if (trialBalance.isBalanced) "مدين = دائن ✓" else "فرق توازن!",
                        color = if (trialBalance.isBalanced) PmpGreen else PmpRed
                    )
                }

                if (trialBalance.rows.isEmpty()) {
                    Text("لا توجد حركات محاسبية مسجلة بعملة $selectedCurrency حتى الآن.", color = TextMuted, fontSize = 12.sp)
                } else {
                    trialBalance.rows.forEach { r ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(PmpSubtleSurface)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                Text("${r.accountCode} • ${r.accountName}", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(
                                    text = "مدين: ${format.format(r.totalDebit)} | دائن: ${format.format(r.totalCredit)}",
                                    color = TextMuted,
                                    fontSize = 10.5.sp
                                )
                            }
                            Text(
                                text = format.format(r.signedBalance),
                                color = if (r.signedBalance >= 0) PmpCyan else PmpRed,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.5.sp
                            )
                        }
                    }

                    HorizontalDivider(color = GlassBorder)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("إجمالي المدين: ${format.format(trialBalance.totalDebit)} $selectedCurrency", color = PmpCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("إجمالي الدائن: ${format.format(trialBalance.totalCredit)} $selectedCurrency", color = PmpGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // Balance Sheet Card
        GlassCard(modifier = Modifier.fillMaxWidth(), borderColor = PmpGreen.copy(alpha = 0.45f)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("الميزانية العمومية ($selectedCurrency)", color = TextWhite, fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp)
                    GlassBadge(
                        text = if (balanceSheet.isBalanced) "الميزانية متوازنة ✓" else "عدم تطابق!",
                        color = if (balanceSheet.isBalanced) PmpGreen else PmpRed
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("إجمالي الأصول (Assets):", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    Text("${format.format(balanceSheet.totalAssets)} $selectedCurrency", color = PmpCyan, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("إجمالي الالتزامات (Liabilities):", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    Text("${format.format(balanceSheet.totalLiabilities)} $selectedCurrency", color = PmpRed, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("حقوق الملكية + صافي ربح الفترة:", color = TextDark, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    Text("${format.format(balanceSheet.totalEquityBeforeNetIncome + balanceSheet.currentNetIncome)} $selectedCurrency", color = PmpGold, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                }
                HorizontalDivider(color = GlassBorder)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("الالتزامات + حقوق الملكية:", color = TextWhite, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    Text("${format.format(balanceSheet.totalLiabilitiesAndEquity)} $selectedCurrency", color = PmpGreen, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp)
                }
            }
        }

        // Operational vs GL Reconciliation Card
        GlassCard(modifier = Modifier.fillMaxWidth(), borderColor = PmpGold.copy(alpha = 0.45f)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "لوحة التدقيق ومطابقة الدفاتر مع العمليات ($selectedCurrency)",
                    color = TextWhite,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                )
                reconciliation.forEach { rec ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PmpSubtleSurface)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                            Text(rec.label, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                text = "تشغيلي: ${format.format(rec.operationalValue)} • الأستاذ العام: ${format.format(rec.glValue)}",
                                color = TextMuted,
                                fontSize = 10.5.sp
                            )
                        }
                        GlassBadge(
                            text = if (rec.status == "MATCHED") "متطابق ✓" else "فرق ${format.format(rec.difference)}",
                            color = if (rec.status == "MATCHED") PmpGreen else PmpGold
                        )
                    }
                }
            }
        }

        // Consolidated FX Summary Card (Explicitly Configurable FX Rates)
        GlassCard(modifier = Modifier.fillMaxWidth(), borderColor = GlassBorder) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الملخص التجميعي بالعملة الأساسية (SDG عبر أسعار صرف صريحة)", color = TextWhite, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = egpRateInput,
                        onValueChange = { egpRateInput = it },
                        label = { Text("1 EGP -> SDG") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = usdRateInput,
                        onValueChange = { usdRateInput = it },
                        label = { Text("1 USD -> SDG") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("إجمالي الأصول المعادلة:", color = TextMuted, fontSize = 12.sp)
                    Text("${format.format(consolidated.totalAssetsConsolidated)} SDG", color = PmpCyan, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("صافي الدخل التجميعي المعادل:", color = TextMuted, fontSize = 12.sp)
                    Text("${format.format(consolidated.netIncomeConsolidated)} SDG", color = PmpGreen, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                }
            }
        }
    }
}
