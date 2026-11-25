package com.example.domain.accounting

import com.example.data.local.CashboxEntity
import com.example.data.local.InvoiceEntity
import com.example.data.local.InvoicePaymentEntity
import com.example.data.local.JournalEntryEntity
import com.example.data.local.JournalLineEntity
import com.example.data.local.MovementEntity
import com.example.data.local.WarehouseStockBalanceEntity
import com.example.util.TreasuryBalanceNormalizer
import kotlin.math.abs

/**
 * Exact 6 explicit sync states required identically across Android, Desktop, and Web.
 */
enum class UnifiedSyncStatus(val code: String, val labelAr: String) {
    SYNCED("Synced", "متزامن ومؤكد على الخادم"),
    SYNCING("Syncing", "جارٍ المزامنة مع الخادم..."),
    PENDING("Pending", "عمليات محلية معلقة بانتظار التأكيد"),
    FAILED("Failed", "فشل المزامنة — يرجى المراجعة وإعادة المحاولة"),
    OFFLINE("Offline", "وضع عدم الاتصال (Offline)"),
    CONFLICT_DETECTED("Conflict Detected", "تم رصد تعارض أرصدة أو بيانات")
}

data class CollectionSyncMetadata(
    val collectionName: String,
    val hasPendingWrites: Boolean,
    val fromCache: Boolean,
    val docCount: Int,
    val lastServerAckAt: Long,
    val lastError: String? = null
)

data class UnifiedSyncSnapshot(
    val status: UnifiedSyncStatus,
    val labelAr: String,
    val online: Boolean,
    val pendingWritesCount: Int,
    val fromCacheCollectionsCount: Int,
    val failedCount: Int,
    val conflictCount: Int,
    val lastServerAckAt: Long,
    val collections: Map<String, CollectionSyncMetadata> = emptyMap()
)

data class CrossPlatformBalanceCheckpoint(
    val platform: String, // WEB, DESKTOP, ANDROID
    val cashboxTotals: Map<String, Map<String, Double>> = emptyMap(),
    val currencyTotals: Map<String, Double> = emptyMap(),
    val arTotals: Map<String, Double> = emptyMap(),
    val movementCount: Int = 0,
    val invoiceCount: Int = 0,
    val lastSyncedTransactionRef: String = "NONE",
    val lastSyncedAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val appVersion: String = ""
)

data class BalanceAnomalyRecord(
    val id: String,
    val affectedAccount: String,
    val currency: String,
    val platformOrSource: String,
    val centralServerValue: Double,
    val ledgerSumValue: Double,
    val comparedValue: Double,
    val varianceAmount: Double,
    val detectedAtMillis: Long,
    val lastSyncedTransactionRef: String,
    val lastSyncedAtMillis: Long,
    val rootCauseSource: String,
    val rootCauseDetailAr: String,
    val safeRemediationAction: String,
    val safeRemediationDescriptionAr: String
)

data class CrossPlatformReconciliationReport(
    val generatedAtMillis: Long,
    val overallStatus: String, // MATCHED | VARIANCE_DETECTED
    val centralCurrencyTotals: Map<String, Double>,
    val localCurrencyTotals: Map<String, Double>,
    val ledgerCurrencyTotals: Map<String, Double>,
    val glCashTotals: Map<String, Double>,
    val centralArTotals: Map<String, Double>,
    val webCheckpoint: CrossPlatformBalanceCheckpoint? = null,
    val desktopCheckpoint: CrossPlatformBalanceCheckpoint? = null,
    val androidCheckpoint: CrossPlatformBalanceCheckpoint? = null,
    val anomalies: List<BalanceAnomalyRecord> = emptyList(),
    val lastSyncedTransactionRef: String = "NONE",
    val lastSyncedAtMillis: Long = 0L,
    val summaryAr: String = ""
)

object CrossPlatformReconciliationEngine {

    private val CURRENCIES = listOf("SDG", "EGP", "USD")

    fun resolveUnifiedSyncState(
        online: Boolean,
        hasPendingWrites: Boolean,
        fromCache: Boolean,
        syncingCount: Int = 0,
        failedCount: Int = 0,
        conflictCount: Int = 0,
        lastServerAckAt: Long = 0L
    ): UnifiedSyncStatus {
        if (conflictCount > 0) return UnifiedSyncStatus.CONFLICT_DETECTED
        if (!online) return UnifiedSyncStatus.OFFLINE
        if (failedCount > 0) return UnifiedSyncStatus.FAILED
        if (hasPendingWrites) return UnifiedSyncStatus.PENDING
        if (syncingCount > 0 || fromCache || lastServerAckAt <= 0L) return UnifiedSyncStatus.SYNCING
        return UnifiedSyncStatus.SYNCED
    }

    fun computeCashboxBalancesFromMovements(
        cashboxes: List<CashboxEntity>,
        movements: List<MovementEntity>
    ): Map<Pair<String, String>, Double> {
        val deduped = TreasuryBalanceNormalizer.deduplicateMovements(movements)
        val totals = LinkedHashMap<Pair<String, String>, Double>()
        val displayNames = LinkedHashMap<String, String>()
        for (cb in cashboxes) {
            val clean = cb.name.trim()
            if (clean.isNotBlank()) {
                displayNames.putIfAbsent(TreasuryBalanceNormalizer.normalizeArabicKey(clean), clean)
            }
        }
        for (m in deduped) {
            if (TreasuryBalanceNormalizer.isCancelledOrReversedStatus(m.status)) continue
            val cleanBox = m.cashbox.trim()
            if (cleanBox.isBlank()) continue
            val boxKey = TreasuryBalanceNormalizer.normalizeArabicKey(cleanBox)
            displayNames.putIfAbsent(boxKey, cleanBox)
            val display = displayNames[boxKey] ?: cleanBox
            val cur = TreasuryBalanceNormalizer.resolveCanonicalCurrency(m.currency, cleanBox)
            val signed = TreasuryBalanceNormalizer.signedAmount(m.direction, m.amount)
            val pair = display to cur
            totals[pair] = (totals[pair] ?: 0.0) + signed
        }
        return totals
    }

    fun computeCurrencyTotalsFromMovements(movements: List<MovementEntity>): Map<String, Double> {
        val deduped = TreasuryBalanceNormalizer.deduplicateMovements(movements)
        val totals = linkedMapOf("SDG" to 0.0, "EGP" to 0.0, "USD" to 0.0)
        for (m in deduped) {
            if (TreasuryBalanceNormalizer.isCancelledOrReversedStatus(m.status)) continue
            val cur = TreasuryBalanceNormalizer.resolveCanonicalCurrency(m.currency, m.cashbox)
            totals[cur] = (totals[cur] ?: 0.0) + TreasuryBalanceNormalizer.signedAmount(m.direction, m.amount)
        }
        return totals
    }

    fun computeCheckpointPayload(
        cashboxes: List<CashboxEntity>,
        movements: List<MovementEntity>,
        invoices: List<InvoiceEntity>,
        payments: List<InvoicePaymentEntity>,
        appVersion: String
    ): CrossPlatformBalanceCheckpoint {
        val boxTotalsRaw = computeCashboxBalancesFromMovements(cashboxes, movements)
        val nested = LinkedHashMap<String, MutableMap<String, Double>>()
        for ((pair, bal) in boxTotalsRaw) {
            nested.getOrPut(pair.first) { linkedMapOf("SDG" to 0.0, "EGP" to 0.0, "USD" to 0.0) }[pair.second] = bal
        }
        val curTotals = computeCurrencyTotalsFromMovements(movements)

        val arTotals = linkedMapOf("SDG" to 0.0, "EGP" to 0.0, "USD" to 0.0)
        for (inv in invoices) {
            if (TreasuryBalanceNormalizer.isCancelledOrReversedStatus(inv.status)) continue
            val cur = TreasuryBalanceNormalizer.resolveCanonicalCurrency(inv.currency)
            val paid = payments
                .filter { it.invoiceId == inv.id && !TreasuryBalanceNormalizer.isCancelledOrReversedStatus(it.status) }
                .sumOf { it.amount }
            arTotals[cur] = (arTotals[cur] ?: 0.0) + (inv.total - paid).coerceAtLeast(0.0)
        }

        val dedupedMovs = TreasuryBalanceNormalizer.deduplicateMovements(movements)
        val latest = dedupedMovs.maxByOrNull { it.createdAt }
        val lastRef = latest?.reference?.takeIf { it.isNotBlank() }
            ?: latest?.cloudDocId?.takeIf { it.isNotBlank() }
            ?: latest?.let { "MOV-${it.id}" }
            ?: "NONE"
        val now = System.currentTimeMillis()
        return CrossPlatformBalanceCheckpoint(
            platform = "ANDROID",
            cashboxTotals = nested,
            currencyTotals = curTotals,
            arTotals = arTotals,
            movementCount = dedupedMovs.size,
            invoiceCount = invoices.size,
            lastSyncedTransactionRef = lastRef,
            lastSyncedAtMillis = latest?.createdAt ?: 0L,
            updatedAtMillis = now,
            appVersion = appVersion
        )
    }

    fun reconcile(
        centralCashboxes: List<CashboxEntity>,
        centralMovements: List<MovementEntity>,
        centralInvoices: List<InvoiceEntity>,
        centralPayments: List<InvoicePaymentEntity>,
        localCashboxes: List<CashboxEntity>,
        localMovements: List<MovementEntity>,
        journalEntries: List<JournalEntryEntity>,
        journalLines: List<JournalLineEntity>,
        stockBalances: List<WarehouseStockBalanceEntity> = emptyList(),
        webCheckpoint: CrossPlatformBalanceCheckpoint? = null,
        desktopCheckpoint: CrossPlatformBalanceCheckpoint? = null,
        androidCheckpoint: CrossPlatformBalanceCheckpoint? = null,
        pendingWritesCount: Int = 0,
        nowMillis: Long = System.currentTimeMillis()
    ): CrossPlatformReconciliationReport {
        val centralBoxTotals = computeCashboxBalancesFromMovements(centralCashboxes, centralMovements)
        val localBoxTotals = computeCashboxBalancesFromMovements(localCashboxes, localMovements)
        val centralCurTotals = computeCurrencyTotalsFromMovements(centralMovements)
        val localCurTotals = computeCurrencyTotalsFromMovements(localMovements)
        val ledgerCurTotals = computeCurrencyTotalsFromMovements(centralMovements)

        val latestMov = TreasuryBalanceNormalizer.deduplicateMovements(centralMovements).maxByOrNull { it.createdAt }
        val lastRef = latestMov?.reference?.takeIf { it.isNotBlank() }
            ?: latestMov?.cloudDocId?.takeIf { it.isNotBlank() }
            ?: latestMov?.let { "MOV-${it.id}" }
            ?: "NONE"
        val lastAt = latestMov?.createdAt ?: 0L

        val anomalies = mutableListOf<BalanceAnomalyRecord>()

        // 1. Compare Central vs Local Room per cashbox & currency
        val byNormBoxCurCentral = centralBoxTotals.entries.associateBy {
            TreasuryBalanceNormalizer.normalizeArabicKey(it.key.first) to it.key.second
        }
        val byNormBoxCurLocal = localBoxTotals.entries.associateBy {
            TreasuryBalanceNormalizer.normalizeArabicKey(it.key.first) to it.key.second
        }
        val allPairs = (byNormBoxCurCentral.keys + byNormBoxCurLocal.keys).toSet()
        for (pair in allPairs) {
            val cEntry = byNormBoxCurCentral[pair]
            val lEntry = byNormBoxCurLocal[pair]
            val displayBox = cEntry?.key?.first ?: lEntry?.key?.first ?: pair.first
            val cur = pair.second
            val cVal = cEntry?.value ?: 0.0
            val lVal = lEntry?.value ?: 0.0
            val diff = cVal - lVal
            if (abs(diff) >= 0.01) {
                val hasPending = pendingWritesCount > 0 || localMovements.any { it.syncStatus.equals("PENDING", ignoreCase = true) }
                anomalies.add(
                    BalanceAnomalyRecord(
                        id = "LOCAL_VS_CENTRAL_${pair.first}_$cur",
                        affectedAccount = "$displayBox ($cur)",
                        currency = cur,
                        platformOrSource = "Android Room vs Firestore Central",
                        centralServerValue = cVal,
                        ledgerSumValue = cVal,
                        comparedValue = lVal,
                        varianceAmount = diff,
                        detectedAtMillis = nowMillis,
                        lastSyncedTransactionRef = lastRef,
                        lastSyncedAtMillis = lastAt,
                        rootCauseSource = if (hasPending) "PENDING_OFFLINE_WRITES" else "DUPLICATE_OR_MISSING_RECORDS",
                        rootCauseDetailAr = if (hasPending) {
                            "توجد عمليات محلية معلقة ($pendingWritesCount) لم تُرفع بعد إلى السيرفر المركزي."
                        } else {
                            "فرق بين رصيد Room المحلي ودفتر الحركات المركزي بمقدار ${"%.2f".format(abs(diff))} $cur."
                        },
                        safeRemediationAction = if (hasPending) "FLUSH_PENDING_OUTBOX" else "REFRESH_CLIENT_SNAPSHOT_FROM_SERVER",
                        safeRemediationDescriptionAr = if (hasPending) {
                            "إرسال العمليات المعلقة عبر Outbox ثم إعادة المطابقة من دفتر الحركات."
                        } else {
                            "تحديث نسخة Android مباشرة من حركات السيرفر المركزي (Ledger) بدون نسخ رصيد مجرد."
                        }
                    )
                )
            }
        }

        // 2. Compare Central vs Web / Desktop / Android Checkpoints
        listOf(
            "نسخة الويب (Web)" to webCheckpoint,
            "نسخة سطح المكتب (Desktop)" to desktopCheckpoint,
            "نسخة الأندرويد (Android)" to androidCheckpoint
        ).forEach { (label, cp) ->
            if (cp != null) {
                for (cur in CURRENCIES) {
                    val cVal = centralCurTotals[cur] ?: 0.0
                    val cpVal = cp.currencyTotals[cur] ?: 0.0
                    val diff = cVal - cpVal
                    if (abs(diff) >= 0.01) {
                        anomalies.add(
                            BalanceAnomalyRecord(
                                id = "CHECKPOINT_${cp.platform}_$cur",
                                affectedAccount = "إجمالي العملة ($cur) — $label",
                                currency = cur,
                                platformOrSource = label,
                                centralServerValue = cVal,
                                ledgerSumValue = ledgerCurTotals[cur] ?: 0.0,
                                comparedValue = cpVal,
                                varianceAmount = diff,
                                detectedAtMillis = cp.updatedAtMillis.takeIf { it > 0L } ?: nowMillis,
                                lastSyncedTransactionRef = cp.lastSyncedTransactionRef,
                                lastSyncedAtMillis = cp.lastSyncedAtMillis,
                                rootCauseSource = "UNSYNCED_CLIENT_CHECKPOINT",
                                rootCauseDetailAr = "قراءة $label ($cpVal $cur) تختلف عن السيرفر المركزي ($cVal $cur) بمقدار ${"%.2f".format(abs(diff))}.",
                                safeRemediationAction = "REFRESH_CLIENT_SNAPSHOT_FROM_SERVER",
                                safeRemediationDescriptionAr = "مزامنة $label من دفتر الأستاذ المركزي وترحيل أي حركات معلقة."
                            )
                        )
                    }
                }
            }
        }

        // 3. Compare Central Operational Cash vs General Ledger Trial Balance
        val glCashTotals = linkedMapOf("SDG" to 0.0, "EGP" to 0.0, "USD" to 0.0)
        val centralArTotals = linkedMapOf("SDG" to 0.0, "EGP" to 0.0, "USD" to 0.0)
        for (inv in centralInvoices) {
            if (TreasuryBalanceNormalizer.isCancelledOrReversedStatus(inv.status)) continue
            val cur = TreasuryBalanceNormalizer.resolveCanonicalCurrency(inv.currency)
            val paid = centralPayments
                .filter { it.invoiceId == inv.id && !TreasuryBalanceNormalizer.isCancelledOrReversedStatus(it.status) }
                .sumOf { it.amount }
            centralArTotals[cur] = (centralArTotals[cur] ?: 0.0) + (inv.total - paid).coerceAtLeast(0.0)
        }

        for (cur in CURRENCIES) {
            val tb = AccountingLedgerEngine.computeTrialBalance(emptyList(), journalEntries, journalLines, cur)
            val glCash = tb.rows.filter { it.accountCode in listOf("1110", "1111", "1112", "1115") }.sumOf { it.signedBalance }
            glCashTotals[cur] = glCash
            val opCash = centralCurTotals[cur] ?: 0.0
            val diff = opCash - glCash
            if (journalEntries.isNotEmpty() && abs(diff) >= 1.0) {
                anomalies.add(
                    BalanceAnomalyRecord(
                        id = "GL_CASH_DRIFT_$cur",
                        affectedAccount = "النقدية والخزائن مقابل دفتر الأستاذ ($cur)",
                        currency = cur,
                        platformOrSource = "Movements Ledger vs GL Journal",
                        centralServerValue = opCash,
                        ledgerSumValue = ledgerCurTotals[cur] ?: 0.0,
                        comparedValue = glCash,
                        varianceAmount = diff,
                        detectedAtMillis = nowMillis,
                        lastSyncedTransactionRef = lastRef,
                        lastSyncedAtMillis = lastAt,
                        rootCauseSource = "GL_JOURNAL_OMISSION",
                        rootCauseDetailAr = "فرق بين رصيد حركات الخزن ($opCash $cur) وميزان المراجعة المحاسبي ($glCash $cur).",
                        safeRemediationAction = "POST_MISSING_GL_JOURNAL",
                        safeRemediationDescriptionAr = "إنشاء قيد تسوية محاسبي موثق بناءً على سجل الحركات."
                    )
                )
            }
        }

        val status = if (anomalies.isEmpty()) "MATCHED" else "VARIANCE_DETECTED"
        val summary = if (anomalies.isEmpty()) {
            "تمت المطابقة الكاملة بنجاح بين السيرفر المركزي وAndroid والمنصات ودفتر الأستاذ."
        } else {
            "تم اكتشاف ${anomalies.size} تباين/ملاحظة مطابقة تتطلب مراجعة وإجراءً آمناً من سجل الحركات."
        }

        return CrossPlatformReconciliationReport(
            generatedAtMillis = nowMillis,
            overallStatus = status,
            centralCurrencyTotals = centralCurTotals,
            localCurrencyTotals = localCurTotals,
            ledgerCurrencyTotals = ledgerCurTotals,
            glCashTotals = glCashTotals,
            centralArTotals = centralArTotals,
            webCheckpoint = webCheckpoint,
            desktopCheckpoint = desktopCheckpoint,
            androidCheckpoint = androidCheckpoint,
            anomalies = anomalies,
            lastSyncedTransactionRef = lastRef,
            lastSyncedAtMillis = lastAt,
            summaryAr = summary
        )
    }
}
