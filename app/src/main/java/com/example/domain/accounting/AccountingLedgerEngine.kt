package com.example.domain.accounting

import androidx.room.withTransaction
import com.example.data.local.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

data class JournalLineInput(
    val accountCode: String,
    val debit: Double = 0.0,
    val credit: Double = 0.0,
    val costCenter: String = "",
    val partyType: String = "",
    val partyName: String = "",
    val memo: String = ""
)

data class JournalPostRequest(
    val entryDate: String = "",
    val sourceType: String = "MANUAL_ADJUSTMENT",
    val sourceDocId: Long? = null,
    val sourceDocNo: String = "",
    val description: String,
    val currency: String = "SDG",
    val fxRateToUsd: Double = 1.0,
    val fxRateToSdg: Double = 1.0,
    val lines: List<JournalLineInput>,
    val createdBy: String = "المدير",
    val approvedBy: String = "",
    val idempotencyKey: String = ""
)

data class JournalWithLines(
    val entry: JournalEntryEntity,
    val lines: List<JournalLineEntity>
)

data class TrialBalanceRow(
    val accountCode: String,
    val accountName: String,
    val accountType: String,
    val normalSide: String,
    val totalDebit: Double,
    val totalCredit: Double,
    val netDebit: Double,
    val netCredit: Double,
    val signedBalance: Double // Normal-side balance: Assets/Expenses = DR-CR, Liability/Equity/Revenue = CR-DR
)

data class TrialBalanceReport(
    val currency: String,
    val rows: List<TrialBalanceRow>,
    val totalDebit: Double,
    val totalCredit: Double,
    val isBalanced: Boolean
)

data class IncomeStatementReport(
    val currency: String,
    val revenueRows: List<TrialBalanceRow>,
    val cogsRows: List<TrialBalanceRow>,
    val operatingExpenseRows: List<TrialBalanceRow>,
    val totalRevenue: Double,
    val totalCogs: Double,
    val grossProfit: Double,
    val totalOperatingExpenses: Double,
    val netIncome: Double
)

data class BalanceSheetReport(
    val currency: String,
    val assetRows: List<TrialBalanceRow>,
    val liabilityRows: List<TrialBalanceRow>,
    val equityRows: List<TrialBalanceRow>,
    val totalAssets: Double,
    val totalLiabilities: Double,
    val totalEquityBeforeNetIncome: Double,
    val currentNetIncome: Double,
    val totalLiabilitiesAndEquity: Double,
    val isBalanced: Boolean,
    val imbalanceDiff: Double
)

data class ReconciliationItem(
    val label: String,
    val operationalValue: Double,
    val glValue: Double,
    val difference: Double,
    val status: String // MATCHED or VARIANCE
)

data class CurrencyFxRates(
    val egpToSdg: Double = 12.5,
    val usdToSdg: Double = 600.0
)

data class ConsolidatedFxSummary(
    val baseCurrency: String = "SDG",
    val rates: CurrencyFxRates,
    val totalRevenueConsolidated: Double,
    val totalExpensesConsolidated: Double,
    val netIncomeConsolidated: Double,
    val totalAssetsConsolidated: Double,
    val totalLiabilitiesConsolidated: Double,
    val totalEquityConsolidated: Double
)

/**
 * المحرك المحاسبي المزدوج القيد (Phase 4 Double-Entry General Ledger Engine)
 *
 * القواعد الصارمة:
 * 1. لا يُقبل أي قيد يومية غير متوازن (مجموع المدين يجب أن يساوي مجموع الدائن بدقة < 0.001).
 * 2. كل قيد ينتمي لعملة واحدة محددة (SDG / EGP / USD) ولا تُخلط العملات في نفس القيد.
 * 3. القيود المرحّلة Append-Only غير قابلة للحذف أو التعديل؛ التصحيح يتم بقيد عكسي فقط.
 * 4. عدم التكرار (Idempotency) عبر idempotencyKey.
 */
class AccountingLedgerEngine(
    private val database: PmpDatabase
) {
    private val accDao: ErpAccountingDao = database.erpAccountingDao()

    companion object {
        const val BALANCE_EPSILON = 0.001

        val DEFAULT_ACCOUNTS: List<ChartAccountEntity> by lazy {
            val now = System.currentTimeMillis()
            listOf(
                ChartAccountEntity(syncUuid = "ACC-1110", accountCode = "1110", nameAr = "صندوق النقدية - السودان (SDG)", nameEn = "Cashbox Sudan SDG", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1100", currencyHint = "SDG", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-1111", accountCode = "1111", nameAr = "صندوق النقدية - مصر (EGP)", nameEn = "Cashbox Egypt EGP", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1100", currencyHint = "EGP", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-1112", accountCode = "1112", nameAr = "صندوق النقدية - الدولار (USD)", nameEn = "Cashbox USD", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1100", currencyHint = "USD", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-1115", accountCode = "1115", nameAr = "خزائن وعهد الفروع (حلفا/عطبرة)", nameEn = "Branch Cashboxes", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1100", currencyHint = "ALL", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-1120", accountCode = "1120", nameAr = "البنوك والتحويلات الوسيطة والعملات", nameEn = "Banks & FX Clearing", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1100", currencyHint = "ALL", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-1130", accountCode = "1130", nameAr = "ذمم العملاء المدينة (المبيعات الآجلة)", nameEn = "Accounts Receivable", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1100", currencyHint = "ALL", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-1141", accountCode = "1141", nameAr = "مخزون المواد الخام والتعبئة", nameEn = "Raw Materials Inventory", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1140", currencyHint = "ALL", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-1142", accountCode = "1142", nameAr = "مخزون الإنتاج تحت التشغيل (WIP)", nameEn = "Work In Progress Inventory", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1140", currencyHint = "ALL", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-1143", accountCode = "1143", nameAr = "مخزون الإنتاج التام والمبيعات", nameEn = "Finished Goods Inventory", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1140", currencyHint = "ALL", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-1210", accountCode = "1210", nameAr = "الآلات ومعدات الخلط والتصنيع", nameEn = "Factory Machinery & Equipment", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1200", currencyHint = "ALL", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-2110", accountCode = "2110", nameAr = "ذمم الموردين الدائنة (المشتريات)", nameEn = "Accounts Payable Suppliers", accountType = AccountType.LIABILITY.name, normalSide = "CREDIT", parentCode = "2100", currencyHint = "ALL", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-2120", accountCode = "2120", nameAr = "التزامات ومصروفات مستحقة", nameEn = "Accrued Liabilities", accountType = AccountType.LIABILITY.name, normalSide = "CREDIT", parentCode = "2100", currencyHint = "ALL", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-3100", accountCode = "3100", nameAr = "رأس المال والأرصدة الافتتاحية والأرباح المحتجزة", nameEn = "Capital & Retained Earnings", accountType = AccountType.EQUITY.name, normalSide = "CREDIT", parentCode = "3000", currencyHint = "ALL", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-4100", accountCode = "4100", nameAr = "إيرادات مبيعات الدهانات والبوهيات", nameEn = "Paint Sales Revenue", accountType = AccountType.REVENUE.name, normalSide = "CREDIT", parentCode = "4000", currencyHint = "ALL", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-4200", accountCode = "4200", nameAr = "إيرادات أخرى وفروق عملة دائنة", nameEn = "Other Income & FX Gain", accountType = AccountType.REVENUE.name, normalSide = "CREDIT", parentCode = "4000", currencyHint = "ALL", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-5100", accountCode = "5100", nameAr = "تكلفة البضاعة المباعة (COGS)", nameEn = "Cost of Goods Sold", accountType = AccountType.EXPENSE.name, normalSide = "DEBIT", parentCode = "5000", currencyHint = "ALL", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-5200", accountCode = "5200", nameAr = "فاقد ومصروفات التصنيع والإنتاج", nameEn = "Manufacturing Overhead & Waste", accountType = AccountType.EXPENSE.name, normalSide = "DEBIT", parentCode = "5000", currencyHint = "ALL", createdAt = now, updatedAt = now),
                ChartAccountEntity(syncUuid = "ACC-5300", accountCode = "5300", nameAr = "مصروفات التشغيل والعمومية", nameEn = "Operating & General Expenses", accountType = AccountType.EXPENSE.name, normalSide = "DEBIT", parentCode = "5000", currencyHint = "ALL", createdAt = now, updatedAt = now)
            )
        }

        fun resolveCanonicalCurrency(raw: String): String {
            val clean = raw.trim().uppercase(Locale.US)
            return when {
                clean == "USD" || clean.contains("دولار") -> "USD"
                clean == "EGP" || clean.contains("مصري") || clean.contains("مصر") -> "EGP"
                else -> "SDG"
            }
        }

        fun resolveCashAccountCode(cashboxName: String, currency: String): String {
            val cur = resolveCanonicalCurrency(currency)
            val box = cashboxName.trim()
            return when {
                cur == "USD" || box.contains("دولار") -> "1112"
                cur == "EGP" || box.contains("مصر") || box.contains("قاهر") -> "1111"
                box.contains("سودان") || box.contains("خرطوم") -> "1110"
                box.contains("حلفا") || box.contains("عطبر") -> "1115"
                else -> "1110"
            }
        }

        fun computeTrialBalance(
            accounts: List<ChartAccountEntity>,
            entries: List<JournalEntryEntity>,
            lines: List<JournalLineEntity>,
            currencyFilter: String = "SDG"
        ): TrialBalanceReport {
            val targetCurrency = resolveCanonicalCurrency(currencyFilter)
            val matchingEntryIds = entries.filter {
                resolveCanonicalCurrency(it.currency) == targetCurrency
            }.map { it.id }.toSet()

            val matchingEntryUuids = entries.filter {
                resolveCanonicalCurrency(it.currency) == targetCurrency
            }.map { it.syncUuid }.toSet()

            val filteredLines = lines.filter { line ->
                if (line.journalEntryUuid.isNotBlank()) {
                    line.journalEntryUuid in matchingEntryUuids
                } else {
                    line.journalEntryId in matchingEntryIds
                }
            }

            val accountByCode = (DEFAULT_ACCOUNTS + accounts).associateBy { it.accountCode }
            val totalsByCode = linkedMapOf<String, Pair<Double, Double>>()

            for (line in filteredLines) {
                val prev = totalsByCode[line.accountCode] ?: (0.0 to 0.0)
                totalsByCode[line.accountCode] = (prev.first + line.debit) to (prev.second + line.credit)
            }

            val rows = totalsByCode.entries
                .sortedBy { it.key }
                .mapNotNull { (code, pair) ->
                    val dr = pair.first
                    val cr = pair.second
                    if (dr < 0.0001 && cr < 0.0001) return@mapNotNull null
                    val meta = accountByCode[code]
                    val accType = meta?.accountType ?: when {
                        code.startsWith("1") -> AccountType.ASSET.name
                        code.startsWith("2") -> AccountType.LIABILITY.name
                        code.startsWith("3") -> AccountType.EQUITY.name
                        code.startsWith("4") -> AccountType.REVENUE.name
                        else -> AccountType.EXPENSE.name
                    }
                    val normalSide = meta?.normalSide ?: if (accType in listOf(AccountType.ASSET.name, AccountType.EXPENSE.name)) "DEBIT" else "CREDIT"
                    val diff = dr - cr
                    val netDebit = if (diff > 0.0) diff else 0.0
                    val netCredit = if (diff < 0.0) -diff else 0.0
                    val signedBalance = if (normalSide == "DEBIT") (dr - cr) else (cr - dr)
                    TrialBalanceRow(
                        accountCode = code,
                        accountName = meta?.nameAr ?: "حساب #$code",
                        accountType = accType,
                        normalSide = normalSide,
                        totalDebit = dr,
                        totalCredit = cr,
                        netDebit = netDebit,
                        netCredit = netCredit,
                        signedBalance = signedBalance
                    )
                }

            val totalDr = rows.sumOf { it.totalDebit }
            val totalCr = rows.sumOf { it.totalCredit }
            return TrialBalanceReport(
                currency = targetCurrency,
                rows = rows,
                totalDebit = totalDr,
                totalCredit = totalCr,
                isBalanced = abs(totalDr - totalCr) < 0.01
            )
        }

        fun computeIncomeStatement(trialBalance: TrialBalanceReport): IncomeStatementReport {
            val revenues = trialBalance.rows.filter { it.accountType == AccountType.REVENUE.name }
            val cogs = trialBalance.rows.filter { it.accountType == AccountType.EXPENSE.name && it.accountCode.startsWith("51") }
            val opEx = trialBalance.rows.filter { it.accountType == AccountType.EXPENSE.name && !it.accountCode.startsWith("51") }

            val totalRev = revenues.sumOf { it.signedBalance }
            val totalCogs = cogs.sumOf { it.signedBalance }
            val grossProfit = totalRev - totalCogs
            val totalOpEx = opEx.sumOf { it.signedBalance }
            val netIncome = grossProfit - totalOpEx

            return IncomeStatementReport(
                currency = trialBalance.currency,
                revenueRows = revenues,
                cogsRows = cogs,
                operatingExpenseRows = opEx,
                totalRevenue = totalRev,
                totalCogs = totalCogs,
                grossProfit = grossProfit,
                totalOperatingExpenses = totalOpEx,
                netIncome = netIncome
            )
        }

        fun computeBalanceSheet(
            trialBalance: TrialBalanceReport,
            netIncome: Double
        ): BalanceSheetReport {
            val assets = trialBalance.rows.filter { it.accountType == AccountType.ASSET.name }
            val liabilities = trialBalance.rows.filter { it.accountType == AccountType.LIABILITY.name }
            val equity = trialBalance.rows.filter { it.accountType == AccountType.EQUITY.name }

            val totalAssets = assets.sumOf { it.signedBalance }
            val totalLiabilities = liabilities.sumOf { it.signedBalance }
            val totalEquityBeforeNet = equity.sumOf { it.signedBalance }
            val totalLiabAndEq = totalLiabilities + totalEquityBeforeNet + netIncome
            val diff = abs(totalAssets - totalLiabAndEq)

            return BalanceSheetReport(
                currency = trialBalance.currency,
                assetRows = assets,
                liabilityRows = liabilities,
                equityRows = equity,
                totalAssets = totalAssets,
                totalLiabilities = totalLiabilities,
                totalEquityBeforeNetIncome = totalEquityBeforeNet,
                currentNetIncome = netIncome,
                totalLiabilitiesAndEquity = totalLiabAndEq,
                isBalanced = diff < 0.05,
                imbalanceDiff = diff
            )
        }

        fun convertToSdg(amount: Double, currency: String, rates: CurrencyFxRates): Double {
            return when (resolveCanonicalCurrency(currency)) {
                "USD" -> amount * rates.usdToSdg
                "EGP" -> amount * rates.egpToSdg
                else -> amount
            }
        }

        fun computeConsolidatedFxSummary(
            accounts: List<ChartAccountEntity>,
            entries: List<JournalEntryEntity>,
            lines: List<JournalLineEntity>,
            rates: CurrencyFxRates
        ): ConsolidatedFxSummary {
            val currencies = listOf("SDG", "EGP", "USD")
            var totalRev = 0.0
            var totalExp = 0.0
            var totalAssets = 0.0
            var totalLiab = 0.0
            var totalEq = 0.0

            for (cur in currencies) {
                val tb = computeTrialBalance(accounts, entries, lines, cur)
                val pnl = computeIncomeStatement(tb)
                val bs = computeBalanceSheet(tb, pnl.netIncome)
                totalRev += convertToSdg(pnl.totalRevenue, cur, rates)
                totalExp += convertToSdg(pnl.totalCogs + pnl.totalOperatingExpenses, cur, rates)
                totalAssets += convertToSdg(bs.totalAssets, cur, rates)
                totalLiab += convertToSdg(bs.totalLiabilities, cur, rates)
                totalEq += convertToSdg(bs.totalEquityBeforeNetIncome + bs.currentNetIncome, cur, rates)
            }

            return ConsolidatedFxSummary(
                baseCurrency = "SDG",
                rates = rates,
                totalRevenueConsolidated = totalRev,
                totalExpensesConsolidated = totalExp,
                netIncomeConsolidated = totalRev - totalExp,
                totalAssetsConsolidated = totalAssets,
                totalLiabilitiesConsolidated = totalLiab,
                totalEquityConsolidated = totalEq
            )
        }

        fun computeReconciliationReport(
            currency: String,
            cashboxBalances: Map<Pair<String, String>, Double>,
            invoices: List<InvoiceEntity>,
            payments: List<InvoicePaymentEntity>,
            stockBalances: List<WarehouseStockBalanceEntity>,
            trialBalance: TrialBalanceReport
        ): List<ReconciliationItem> {
            val targetCur = resolveCanonicalCurrency(currency)
            val operationalCash = cashboxBalances
                .filterKeys { resolveCanonicalCurrency(it.second) == targetCur }
                .values
                .sum()
            val glCash = trialBalance.rows
                .filter { it.accountCode in listOf("1110", "1111", "1112", "1115") }
                .sumOf { it.signedBalance }

            val activeInvoices = invoices.filter {
                !com.example.util.TreasuryBalanceNormalizer.isCancelledOrReversedStatus(it.status) &&
                    resolveCanonicalCurrency(it.currency) == targetCur
            }
            val operationalAr = activeInvoices.sumOf { inv ->
                val paid = payments.filter {
                    it.invoiceId == inv.id &&
                        !com.example.util.TreasuryBalanceNormalizer.isCancelledOrReversedStatus(it.status)
                }.sumOf { it.amount }
                (inv.total - paid).coerceAtLeast(0.0)
            }
            val glAr = trialBalance.rows.firstOrNull { it.accountCode == "1130" }?.signedBalance ?: 0.0

            val operationalInvVal = stockBalances
                .filter { resolveCanonicalCurrency(it.currency) == targetCur }
                .sumOf { (it.onHandQty.coerceAtLeast(0.0)) * (it.avgUnitCost.coerceAtLeast(0.0)) }
            val glInvVal = trialBalance.rows
                .filter { it.accountCode in listOf("1141", "1142", "1143") }
                .sumOf { it.signedBalance }

            fun row(label: String, op: Double, gl: Double): ReconciliationItem {
                val diff = op - gl
                return ReconciliationItem(
                    label = label,
                    operationalValue = op,
                    glValue = gl,
                    difference = diff,
                    status = if (abs(diff) < 1.0) "MATCHED" else "VARIANCE"
                )
            }

            return listOf(
                row("النقدية والخزائن ($targetCur)", operationalCash, glCash),
                row("ذمم العملاء والمبيعات ($targetCur)", operationalAr, glAr),
                row("تقييم المخزون (خام + WIP + تام) ($targetCur)", operationalInvVal, glInvVal)
            )
        }
    }

    private fun todayDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun nextEntryNo(prefix: String = "JRN"): String {
        val ts = System.currentTimeMillis().toString().takeLast(7)
        val suffix = UUID.randomUUID().toString().take(4).uppercase(Locale.US)
        return "$prefix-$ts-$suffix"
    }

    suspend fun ensureAccountsSeededInternal() {
        if (accDao.getAccountCount() == 0) {
            accDao.upsertAccounts(DEFAULT_ACCOUNTS)
        }
    }

    suspend fun postJournalEntry(request: JournalPostRequest): Result<JournalWithLines> {
        return runCatching {
            database.withTransaction {
                postJournalInternal(request)
            }
        }
    }

    /**
     * يفرض توازن القيد المزدوج بدقة (Sum Debit == Sum Credit) ويحفظه داخل معاملة Room الحالية.
     */
    suspend fun postJournalInternal(request: JournalPostRequest): JournalWithLines {
        require(request.description.isNotBlank()) { "وصف القيد المحاسبي مطلوب." }
        require(request.lines.size >= 2) { "يجب أن يحتوي القيد المحاسبي على طرفين (مدين ودائن) على الأقل." }

        ensureAccountsSeededInternal()

        val cleanIdemp = request.idempotencyKey.trim()
        if (cleanIdemp.isNotEmpty()) {
            accDao.getJournalEntryByIdempotencyKey(cleanIdemp)?.let { existing ->
                val existingLines = accDao.getLinesForEntryDirect(existing.id)
                return JournalWithLines(existing, existingLines)
            }
        }

        val canonicalCurrency = resolveCanonicalCurrency(request.currency)
        val accountsByCode = (DEFAULT_ACCOUNTS + accDao.getAllAccountsDirect()).associateBy { it.accountCode }

        var sumDebit = 0.0
        var sumCredit = 0.0

        val normalizedLines = request.lines.mapIndexed { idx, line ->
            val code = line.accountCode.trim()
            require(code.isNotBlank()) { "رمز الحساب مطلوب في السطر رقم ${idx + 1}." }
            val dr = line.debit
            val cr = line.credit
            require(dr.isFinite() && cr.isFinite() && dr >= 0.0 && cr >= 0.0) {
                "المبالغ المالية في القيد يجب أن تكون موجبة أو صفرية."
            }
            require(!(dr > BALANCE_EPSILON && cr > BALANCE_EPSILON)) {
                "السطر المحاسبي للحساب ($code) لا يمكن أن يحمل مبلغاً في المدين والدائن معاً."
            }
            require(dr > BALANCE_EPSILON || cr > BALANCE_EPSILON) {
                "السطر المحاسبي للحساب ($code) يجب أن يحتوي على قيمة أكبر من صفر."
            }
            val accountMeta = accountsByCode[code]
                ?: throw IllegalArgumentException("الحساب رقم ($code) غير معرف في دليل الحسابات.")

            sumDebit += dr
            sumCredit += cr
            Pair(accountMeta, line)
        }

        require(abs(sumDebit - sumCredit) < BALANCE_EPSILON) {
            "قيد اليومية غير متوازن! مجموع المدين ($sumDebit) لا يساوي مجموع الدائن ($sumCredit)."
        }

        val now = System.currentTimeMillis()
        val entryUuid = UUID.randomUUID().toString()
        val entryNo = nextEntryNo(if (request.sourceType == "REVERSAL") "REV" else "JRN")
        val entryDate = request.entryDate.ifBlank { todayDate() }

        val entry = JournalEntryEntity(
            syncUuid = entryUuid,
            entryNo = entryNo,
            entryDate = entryDate,
            sourceType = request.sourceType,
            sourceDocId = request.sourceDocId,
            sourceDocNo = request.sourceDocNo,
            description = request.description.trim(),
            currency = canonicalCurrency,
            fxRateToUsd = request.fxRateToUsd,
            fxRateToSdg = request.fxRateToSdg,
            totalDebit = sumDebit,
            totalCredit = sumCredit,
            status = "POSTED",
            idempotencyKey = cleanIdemp,
            createdBy = request.createdBy.ifBlank { "النظام" },
            approvedBy = request.approvedBy.ifBlank { request.createdBy },
            createdAt = now,
            updatedAt = now
        )

        val insertedEntryId = accDao.upsertJournalEntry(entry)
        val savedEntry = entry.copy(id = insertedEntryId)

        val lineEntities = normalizedLines.mapIndexed { idx, (account, raw) ->
            JournalLineEntity(
                syncUuid = "JL-${entryUuid}-${idx + 1}",
                journalEntryId = insertedEntryId,
                journalEntryUuid = entryUuid,
                lineOrder = idx + 1,
                accountCode = account.accountCode,
                accountName = account.nameAr,
                accountType = account.accountType,
                debit = raw.debit,
                credit = raw.credit,
                currency = canonicalCurrency,
                costCenter = raw.costCenter,
                partyType = raw.partyType,
                partyName = raw.partyName,
                memo = raw.memo.ifBlank { request.description.trim() },
                createdAt = now
            )
        }
        accDao.upsertJournalLines(lineEntities)

        return JournalWithLines(savedEntry, lineEntities)
    }

    /**
     * عكس قيد محاسبي مرحل (Append-Only Reversal) دون حذف السجل الأصلي
     */
    suspend fun reverseJournalEntry(
        entryId: Long,
        reason: String,
        actor: String
    ): Result<JournalWithLines> {
        return runCatching {
            val cleanReason = reason.trim()
            require(cleanReason.isNotBlank()) { "سبب عكس القيد المحاسبي إلزامي." }

            database.withTransaction {
                val original = accDao.getJournalEntryById(entryId)
                    ?: throw IllegalArgumentException("القيد المحاسبي غير موجود.")
                if (original.status == "REVERSED") {
                    throw IllegalStateException("القيد (${original.entryNo}) تم عكسه مسبقاً بالقيد ${original.reversedByEntryNo}.")
                }
                val originalLines = accDao.getLinesForEntryDirect(original.id)
                require(originalLines.size >= 2) { "أسطر القيد الأصلي غير مكتملة ولا يمكن عكسها." }

                val reversalLines = originalLines.map { line ->
                    JournalLineInput(
                        accountCode = line.accountCode,
                        debit = line.credit,
                        credit = line.debit,
                        costCenter = line.costCenter,
                        partyType = line.partyType,
                        partyName = line.partyName,
                        memo = "عكس قيد ${original.entryNo}: $cleanReason"
                    )
                }

                val reversalResult = postJournalInternal(
                    JournalPostRequest(
                        entryDate = todayDate(),
                        sourceType = "REVERSAL",
                        sourceDocId = original.id,
                        sourceDocNo = original.entryNo,
                        description = "قيد عكسي للقيد ${original.entryNo}: $cleanReason",
                        currency = original.currency,
                        fxRateToUsd = original.fxRateToUsd,
                        fxRateToSdg = original.fxRateToSdg,
                        lines = reversalLines,
                        createdBy = actor,
                        approvedBy = actor,
                        idempotencyKey = "REV-${original.syncUuid}"
                    )
                )

                val updatedReversalEntry = reversalResult.entry.copy(reversedEntryId = original.id)
                accDao.upsertJournalEntry(updatedReversalEntry)

                accDao.markJournalEntryReversed(
                    id = original.id,
                    status = "REVERSED",
                    reversedByEntryNo = updatedReversalEntry.entryNo,
                    reason = cleanReason,
                    updatedAt = System.currentTimeMillis(),
                    newVersion = original.recordVersion + 1
                )

                reversalResult.copy(entry = updatedReversalEntry)
            }
        }
    }

    // --- Automated Business Event Journal Hooks ---

    suspend fun postPurchaseReceiptJournalInternal(
        receipt: PurchaseReceiptEntity
    ): JournalWithLines? {
        val total = receipt.acceptedQty * receipt.unitCost
        if (total <= BALANCE_EPSILON) return null
        return postJournalInternal(
            JournalPostRequest(
                entryDate = receipt.receiptDate,
                sourceType = "PURCHASE_RECEIPT",
                sourceDocId = receipt.id,
                sourceDocNo = receipt.grnNo,
                description = "استلام مشتريات خامات ${receipt.grnNo} (${receipt.materialName}) - ${receipt.supplierName}",
                currency = receipt.currency,
                lines = listOf(
                    JournalLineInput(
                        accountCode = "1141",
                        debit = total,
                        costCenter = "RAW_WAREHOUSE",
                        partyType = "SUPPLIER",
                        partyName = receipt.supplierName,
                        memo = "وارد مخزن الخامات: ${receipt.acceptedQty} ${receipt.unit}"
                    ),
                    JournalLineInput(
                        accountCode = "2110",
                        credit = total,
                        costCenter = "RAW_WAREHOUSE",
                        partyType = "SUPPLIER",
                        partyName = receipt.supplierName,
                        memo = "استحقاق مورد ${receipt.supplierName}"
                    )
                ),
                createdBy = receipt.performedBy,
                idempotencyKey = "JRN-GRN-${receipt.syncUuid}"
            )
        )
    }

    suspend fun postProductionIssueJournalInternal(
        order: ProductionOrderEntity,
        netIssuedCost: Double,
        wasteCost: Double
    ): JournalWithLines? {
        val totalCr = netIssuedCost.coerceAtLeast(0.0) + wasteCost.coerceAtLeast(0.0)
        if (totalCr <= BALANCE_EPSILON) return null
        val lines = mutableListOf<JournalLineInput>()
        if (netIssuedCost > BALANCE_EPSILON) {
            lines.add(
                JournalLineInput(
                    accountCode = "1142",
                    debit = netIssuedCost,
                    costCenter = "FACTORY",
                    partyType = "WAREHOUSE",
                    partyName = order.orderNo,
                    memo = "تحميل تشغيل تحت الإنتاج لأمر ${order.orderNo}"
                )
            )
        }
        if (wasteCost > BALANCE_EPSILON) {
            lines.add(
                JournalLineInput(
                    accountCode = "5200",
                    debit = wasteCost,
                    costCenter = "FACTORY",
                    partyType = "WAREHOUSE",
                    partyName = order.orderNo,
                    memo = "فاقد خامات تشغيل لأمر ${order.orderNo}"
                )
            )
        }
        lines.add(
            JournalLineInput(
                accountCode = "1141",
                credit = totalCr,
                costCenter = "RAW_WAREHOUSE",
                partyType = "WAREHOUSE",
                partyName = order.orderNo,
                memo = "منصرف خامات من مخزن المواد الخام"
            )
        )
        return postJournalInternal(
            JournalPostRequest(
                entryDate = order.orderDate,
                sourceType = "PRODUCTION_ISSUE",
                sourceDocId = order.id,
                sourceDocNo = order.orderNo,
                description = "صرف خامات للتشغيل لأمر الإنتاج ${order.orderNo} (${order.productName})",
                currency = "EGP",
                lines = lines,
                createdBy = order.productionEngineer,
                idempotencyKey = "JRN-PRD-ISS-${order.syncUuid}"
            )
        )
    }

    suspend fun postQcFinishedGoodsJournalInternal(
        order: ProductionOrderEntity,
        qc: QcInspectionEntity,
        fgTotalCost: Double
    ): JournalWithLines? {
        if (fgTotalCost <= BALANCE_EPSILON || !qc.postedToStock) return null
        return postJournalInternal(
            JournalPostRequest(
                entryDate = todayDate(),
                sourceType = "QC_FG_RECEIPT",
                sourceDocId = qc.id,
                sourceDocNo = qc.qcNo,
                description = "ترحيل إنتاج تام معتمد جودة ${qc.qcNo} لأمر ${order.orderNo}",
                currency = "EGP",
                lines = listOf(
                    JournalLineInput(
                        accountCode = "1143",
                        debit = fgTotalCost,
                        costCenter = "FACTORY",
                        partyType = "WAREHOUSE",
                        partyName = order.productName,
                        memo = "استلام منتج تام: ${order.producedFullCartons} كرتونة"
                    ),
                    JournalLineInput(
                        accountCode = "1142",
                        credit = fgTotalCost,
                        costCenter = "FACTORY",
                        partyType = "WAREHOUSE",
                        partyName = order.orderNo,
                        memo = "إقفال تحت التشغيل إلى إنتاج تام"
                    )
                ),
                createdBy = qc.inspectorName,
                idempotencyKey = "JRN-QC-FG-${qc.syncUuid}"
            )
        )
    }

    suspend fun postSalesInvoiceJournalInternal(
        invoiceId: Long,
        invoiceNo: String,
        invoiceDate: String,
        customerName: String,
        currency: String,
        totalRevenue: Double,
        totalCogs: Double,
        actor: String
    ) {
        if (totalRevenue > BALANCE_EPSILON) {
            postJournalInternal(
                JournalPostRequest(
                    entryDate = invoiceDate,
                    sourceType = "SALES_INVOICE",
                    sourceDocId = invoiceId,
                    sourceDocNo = invoiceNo,
                    description = "إثبات مبيعات فاتورة $invoiceNo - العميل: $customerName",
                    currency = currency,
                    lines = listOf(
                        JournalLineInput(
                            accountCode = "1130",
                            debit = totalRevenue,
                            costCenter = "SALES",
                            partyType = "CUSTOMER",
                            partyName = customerName,
                            memo = "استحقاق فاتورة مبيعات $invoiceNo"
                        ),
                        JournalLineInput(
                            accountCode = "4100",
                            credit = totalRevenue,
                            costCenter = "SALES",
                            partyType = "CUSTOMER",
                            partyName = customerName,
                            memo = "إيراد مبيعات دهانات"
                        )
                    ),
                    createdBy = actor,
                    idempotencyKey = "JRN-INV-REV-$invoiceNo"
                )
            )
        }
        if (totalCogs > BALANCE_EPSILON) {
            postJournalInternal(
                JournalPostRequest(
                    entryDate = invoiceDate,
                    sourceType = "SALES_INVOICE",
                    sourceDocId = invoiceId,
                    sourceDocNo = invoiceNo,
                    description = "إثبات تكلفة بضاعة مباعة لفاتورة $invoiceNo",
                    currency = currency,
                    lines = listOf(
                        JournalLineInput(
                            accountCode = "5100",
                            debit = totalCogs,
                            costCenter = "SALES",
                            partyType = "CUSTOMER",
                            partyName = customerName,
                            memo = "تكلفة المبيعات $invoiceNo"
                        ),
                        JournalLineInput(
                            accountCode = "1143",
                            credit = totalCogs,
                            costCenter = "SALES",
                            partyType = "WAREHOUSE",
                            partyName = customerName,
                            memo = "صرف مخزون إنتاج تام"
                        )
                    ),
                    createdBy = actor,
                    idempotencyKey = "JRN-INV-COGS-$invoiceNo"
                )
            )
        }
    }

    suspend fun postCustomerPaymentJournalInternal(
        paymentId: Long,
        referenceDocNo: String,
        payDate: String,
        customerName: String,
        cashbox: String,
        currency: String,
        amount: Double,
        actor: String
    ) {
        if (amount <= BALANCE_EPSILON) return
        val cashCode = resolveCashAccountCode(cashbox, currency)
        postJournalInternal(
            JournalPostRequest(
                entryDate = payDate,
                sourceType = "CUSTOMER_PAYMENT",
                sourceDocId = paymentId,
                sourceDocNo = referenceDocNo,
                description = "تحصيل دفعة من العميل $customerName في $cashbox",
                currency = currency,
                lines = listOf(
                    JournalLineInput(
                        accountCode = cashCode,
                        debit = amount,
                        costCenter = "TREASURY",
                        partyType = "CASHBOX",
                        partyName = cashbox,
                        memo = "وارد خزنة $cashbox"
                    ),
                    JournalLineInput(
                        accountCode = "1130",
                        credit = amount,
                        costCenter = "SALES",
                        partyType = "CUSTOMER",
                        partyName = customerName,
                        memo = "تخفيض ذمة العميل $customerName"
                    )
                ),
                createdBy = actor,
                idempotencyKey = "JRN-PAY-$paymentId-$referenceDocNo"
            )
        )
    }

    suspend fun postInvoiceCancelJournalInternal(
        invoice: InvoiceEntity,
        reversedPayments: List<InvoicePaymentEntity>,
        reason: String,
        actor: String
    ) {
        val cleanCur = resolveCanonicalCurrency(invoice.currency)
        if (invoice.total > BALANCE_EPSILON) {
            postJournalInternal(
                JournalPostRequest(
                    entryDate = todayDate(),
                    sourceType = "INVOICE_CANCEL",
                    sourceDocId = invoice.id,
                    sourceDocNo = invoice.invoiceNo,
                    description = "عكس إيراد فاتورة ملغاة ${invoice.invoiceNo}: $reason",
                    currency = cleanCur,
                    lines = listOf(
                        JournalLineInput(
                            accountCode = "4100",
                            debit = invoice.total,
                            costCenter = "SALES",
                            partyType = "CUSTOMER",
                            partyName = invoice.customerName,
                            memo = "إلغاء إيراد فاتورة ${invoice.invoiceNo}"
                        ),
                        JournalLineInput(
                            accountCode = "1130",
                            credit = invoice.total,
                            costCenter = "SALES",
                            partyType = "CUSTOMER",
                            partyName = invoice.customerName,
                            memo = "إلغاء مديونية فاتورة ${invoice.invoiceNo}"
                        )
                    ),
                    createdBy = actor,
                    idempotencyKey = "JRN-CANCEL-INV-${invoice.invoiceNo}"
                )
            )
        }
        reversedPayments.forEach { pay ->
            if (pay.amount > BALANCE_EPSILON && pay.cashbox.isNotBlank() && !pay.cashbox.contains("تسوية إدارية")) {
                val cashCode = resolveCashAccountCode(pay.cashbox, cleanCur)
                postJournalInternal(
                    JournalPostRequest(
                        entryDate = todayDate(),
                        sourceType = "INVOICE_CANCEL",
                        sourceDocId = pay.id,
                        sourceDocNo = invoice.invoiceNo,
                        description = "رد دفعة نقدية لإلغاء الفاتورة ${invoice.invoiceNo}",
                        currency = cleanCur,
                        lines = listOf(
                            JournalLineInput(
                                accountCode = "1130",
                                debit = pay.amount,
                                costCenter = "SALES",
                                partyType = "CUSTOMER",
                                partyName = invoice.customerName,
                                memo = "تسوية ذمة عميل عند الإلغاء"
                            ),
                            JournalLineInput(
                                accountCode = cashCode,
                                credit = pay.amount,
                                costCenter = "TREASURY",
                                partyType = "CASHBOX",
                                partyName = pay.cashbox,
                                memo = "صرف استرداد من $cashCode"
                            )
                        ),
                        createdBy = actor,
                        idempotencyKey = "JRN-CANCEL-PAY-${pay.id}"
                    )
                )
            }
        }
    }

    suspend fun postTreasuryMovementJournalInternal(movement: MovementEntity) {
        if (movement.amount <= BALANCE_EPSILON) return
        val cashCode = resolveCashAccountCode(movement.cashbox, movement.currency)
        val isIncoming = movement.direction.trim() == "وارد" || movement.direction.trim().equals("IN", ignoreCase = true)
        val isSupplierGrnPayment = !isIncoming && (movement.reference.startsWith("GRN-") || movement.description.contains("مورد") || movement.description.contains("مشتريات"))

        val lines = if (isIncoming) {
            listOf(
                JournalLineInput(
                    accountCode = cashCode,
                    debit = movement.amount,
                    costCenter = "TREASURY",
                    partyType = "CASHBOX",
                    partyName = movement.cashbox,
                    memo = movement.description
                ),
                JournalLineInput(
                    accountCode = "4200",
                    credit = movement.amount,
                    costCenter = "TREASURY",
                    partyType = "CASHBOX",
                    partyName = movement.cashbox,
                    memo = movement.description
                )
            )
        } else {
            val offsetCode = if (isSupplierGrnPayment) "2110" else "5300"
            listOf(
                JournalLineInput(
                    accountCode = offsetCode,
                    debit = movement.amount,
                    costCenter = if (isSupplierGrnPayment) "RAW_WAREHOUSE" else "TREASURY",
                    partyType = if (isSupplierGrnPayment) "SUPPLIER" else "CASHBOX",
                    partyName = movement.cashbox,
                    memo = movement.description
                ),
                JournalLineInput(
                    accountCode = cashCode,
                    credit = movement.amount,
                    costCenter = "TREASURY",
                    partyType = "CASHBOX",
                    partyName = movement.cashbox,
                    memo = movement.description
                )
            )
        }

        postJournalInternal(
            JournalPostRequest(
                entryDate = movement.txDate,
                sourceType = "TREASURY_MOVEMENT",
                sourceDocId = movement.id,
                sourceDocNo = movement.reference,
                description = movement.description,
                currency = movement.currency,
                lines = lines,
                createdBy = movement.createdBy,
                idempotencyKey = "JRN-MOV-${movement.id}-${movement.reference}"
            )
        )
    }

    suspend fun postTreasuryTransferJournalInternal(transfer: TransferEntity) {
        val srcCur = resolveCanonicalCurrency(transfer.sourceCurrency)
        val dstCur = resolveCanonicalCurrency(transfer.destinationCurrency)
        val srcCode = resolveCashAccountCode(transfer.sourceCashbox, srcCur)
        val dstCode = resolveCashAccountCode(transfer.destinationCashbox, dstCur)

        if (srcCur == dstCur) {
            // Single-currency direct transfer between two cashboxes (if same account code, routes cleanly through 1120 or same code)
            val lines = listOf(
                JournalLineInput(
                    accountCode = dstCode,
                    debit = transfer.destinationAmount,
                    costCenter = "TREASURY",
                    partyType = "CASHBOX",
                    partyName = transfer.destinationCashbox,
                    memo = "تحويل وارد إلى ${transfer.destinationCashbox}"
                ),
                JournalLineInput(
                    accountCode = srcCode,
                    credit = transfer.sourceAmount,
                    costCenter = "TREASURY",
                    partyType = "CASHBOX",
                    partyName = transfer.sourceCashbox,
                    memo = "تحويل صادر من ${transfer.sourceCashbox}"
                )
            )
            postJournalInternal(
                JournalPostRequest(
                    entryDate = transfer.txDate,
                    sourceType = "TREASURY_TRANSFER",
                    sourceDocId = transfer.id,
                    sourceDocNo = transfer.reference,
                    description = "تحويل خزنة ${transfer.sourceCashbox} -> ${transfer.destinationCashbox}",
                    currency = srcCur,
                    lines = lines,
                    createdBy = transfer.createdBy,
                    idempotencyKey = "JRN-TRF-${transfer.id}-${transfer.reference}"
                )
            )
        } else {
            // Cross-currency FX transfer: keep each currency's Trial Balance strictly balanced via 1120 FX Clearing
            postJournalInternal(
                JournalPostRequest(
                    entryDate = transfer.txDate,
                    sourceType = "TREASURY_TRANSFER",
                    sourceDocId = transfer.id,
                    sourceDocNo = transfer.reference,
                    description = "تحويل صادر (عملات أجنبية) ${transfer.sourceCashbox} -> وسيط العملات",
                    currency = srcCur,
                    lines = listOf(
                        JournalLineInput(
                            accountCode = "1120",
                            debit = transfer.sourceAmount,
                            costCenter = "TREASURY",
                            partyType = "CASHBOX",
                            partyName = transfer.destinationCashbox,
                            memo = "وسيط صرف تحويل إلى $dstCur"
                        ),
                        JournalLineInput(
                            accountCode = srcCode,
                            credit = transfer.sourceAmount,
                            costCenter = "TREASURY",
                            partyType = "CASHBOX",
                            partyName = transfer.sourceCashbox,
                            memo = "صرف من ${transfer.sourceCashbox}"
                        )
                    ),
                    createdBy = transfer.createdBy,
                    idempotencyKey = "JRN-TRF-OUT-${transfer.id}-${transfer.reference}"
                )
            )
            postJournalInternal(
                JournalPostRequest(
                    entryDate = transfer.txDate,
                    sourceType = "TREASURY_TRANSFER",
                    sourceDocId = transfer.id,
                    sourceDocNo = transfer.reference,
                    description = "تحويل وارد (عملات أجنبية) وسيط العملات -> ${transfer.destinationCashbox}",
                    currency = dstCur,
                    lines = listOf(
                        JournalLineInput(
                            accountCode = dstCode,
                            debit = transfer.destinationAmount,
                            costCenter = "TREASURY",
                            partyType = "CASHBOX",
                            partyName = transfer.destinationCashbox,
                            memo = "استلام في ${transfer.destinationCashbox}"
                        ),
                        JournalLineInput(
                            accountCode = "1120",
                            credit = transfer.destinationAmount,
                            costCenter = "TREASURY",
                            partyType = "CASHBOX",
                            partyName = transfer.sourceCashbox,
                            memo = "مقابل تحويل من $srcCur"
                        )
                    ),
                    createdBy = transfer.createdBy,
                    idempotencyKey = "JRN-TRF-IN-${transfer.id}-${transfer.reference}"
                )
            )
        }
    }
}
