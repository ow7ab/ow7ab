package com.example

import com.example.data.local.CashboxEntity
import com.example.data.local.InvoiceEntity
import com.example.data.local.InvoicePaymentEntity
import com.example.data.local.JournalEntryEntity
import com.example.data.local.JournalLineEntity
import com.example.data.local.MovementEntity
import com.example.data.local.PmpDatabase
import com.example.data.local.UserAccountEntity
import com.example.domain.accounting.CrossPlatformBalanceCheckpoint
import com.example.domain.accounting.CrossPlatformReconciliationEngine
import com.example.domain.accounting.UnifiedSyncStatus
import com.example.util.StatementPdfAndExcelExporter
import com.example.util.TreasuryBalanceNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconciliationAndLedgerAuditTest {

    @Test
    fun `resolveUnifiedSyncState enforces all 6 explicit sync states and never emits SYNCED prematurely`() {
        assertEquals(
            UnifiedSyncStatus.SYNCED,
            CrossPlatformReconciliationEngine.resolveUnifiedSyncState(
                online = true,
                hasPendingWrites = false,
                fromCache = false,
                syncingCount = 0,
                failedCount = 0,
                conflictCount = 0,
                lastServerAckAt = 1700000000000L
            )
        )
        assertEquals(
            UnifiedSyncStatus.PENDING,
            CrossPlatformReconciliationEngine.resolveUnifiedSyncState(
                online = true,
                hasPendingWrites = true,
                fromCache = false,
                lastServerAckAt = 1700000000000L
            )
        )
        assertEquals(
            UnifiedSyncStatus.SYNCING,
            CrossPlatformReconciliationEngine.resolveUnifiedSyncState(
                online = true,
                hasPendingWrites = false,
                fromCache = true,
                lastServerAckAt = 1700000000000L
            )
        )
        assertEquals(
            UnifiedSyncStatus.SYNCING,
            CrossPlatformReconciliationEngine.resolveUnifiedSyncState(
                online = true,
                hasPendingWrites = false,
                fromCache = false,
                lastServerAckAt = 0L
            )
        )
        assertEquals(
            UnifiedSyncStatus.OFFLINE,
            CrossPlatformReconciliationEngine.resolveUnifiedSyncState(
                online = false,
                hasPendingWrites = true,
                fromCache = true,
                lastServerAckAt = 1700000000000L
            )
        )
        assertEquals(
            UnifiedSyncStatus.FAILED,
            CrossPlatformReconciliationEngine.resolveUnifiedSyncState(
                online = true,
                hasPendingWrites = false,
                fromCache = false,
                failedCount = 1,
                lastServerAckAt = 1700000000000L
            )
        )
        assertEquals(
            UnifiedSyncStatus.CONFLICT_DETECTED,
            CrossPlatformReconciliationEngine.resolveUnifiedSyncState(
                online = true,
                hasPendingWrites = false,
                fromCache = false,
                failedCount = 1,
                conflictCount = 1,
                lastServerAckAt = 1700000000000L
            )
        )
    }

    @Test
    fun `reconcile validates ledger parity and pinpoints local, web, desktop, android, and GL variances`() {
        val boxes = listOf(CashboxEntity(id = 1, name = "خزنة السودان"))
        val movIn = MovementEntity(
            id = 10,
            txDate = "2026-11-25",
            cashbox = "خزنة السودان",
            currency = "SDG",
            direction = "وارد",
            amount = 50000.0,
            description = "توريد مبيعات",
            reference = "REC-50",
            status = "active",
            createdAt = 1700000010000L
        )
        val movOut = MovementEntity(
            id = 11,
            txDate = "2026-11-25",
            cashbox = "خزنة السودان",
            currency = "SDG",
            direction = "منصرف",
            amount = 12000.0,
            description = "مصروف تشغيل",
            reference = "EXP-12",
            status = "active",
            createdAt = 1700000020000L
        )
        val movReversed = MovementEntity(
            id = 12,
            txDate = "2026-11-25",
            cashbox = "خزنة السودان",
            currency = "SDG",
            direction = "منصرف",
            amount = 99999.0,
            description = "حركة معكوسة",
            reference = "EXP-REV",
            status = "reversed",
            createdAt = 1700000030000L
        )
        val centralMovs = listOf(movIn, movOut, movReversed)

        val jrnEntry = JournalEntryEntity(
            id = 1L,
            syncUuid = "jrn-recon-1",
            entryNo = "JRN-1001",
            entryDate = "2026-11-25",
            sourceType = "TREASURY_MOVEMENT",
            description = "مطابقة نقدية خزنة السودان",
            currency = "SDG",
            totalDebit = 38000.0,
            totalCredit = 38000.0
        )
        val jrnLines = listOf(
            JournalLineEntity(
                id = 1L,
                journalEntryId = 1L,
                journalEntryUuid = "jrn-recon-1",
                lineOrder = 1,
                accountCode = "1110",
                accountName = "النقدية بالخزائن",
                accountType = "ASSET",
                debit = 38000.0,
                currency = "SDG"
            ),
            JournalLineEntity(
                id = 2L,
                journalEntryId = 1L,
                journalEntryUuid = "jrn-recon-1",
                lineOrder = 2,
                accountCode = "3100",
                accountName = "رأس المال",
                accountType = "EQUITY",
                credit = 38000.0,
                currency = "SDG"
            )
        )

        val alignedCp = CrossPlatformBalanceCheckpoint(
            platform = "WEB",
            currencyTotals = mapOf("SDG" to 38000.0, "EGP" to 0.0, "USD" to 0.0),
            lastSyncedTransactionRef = "EXP-12",
            lastSyncedAtMillis = 1700000020000L,
            updatedAtMillis = 1700000030000L
        )

        val matched = CrossPlatformReconciliationEngine.reconcile(
            centralCashboxes = boxes,
            centralMovements = centralMovs,
            centralInvoices = emptyList(),
            centralPayments = emptyList(),
            localCashboxes = boxes,
            localMovements = centralMovs,
            journalEntries = listOf(jrnEntry),
            journalLines = jrnLines,
            webCheckpoint = alignedCp,
            desktopCheckpoint = alignedCp.copy(platform = "DESKTOP"),
            androidCheckpoint = alignedCp.copy(platform = "ANDROID")
        )

        assertEquals("MATCHED", matched.overallStatus)
        assertTrue(matched.anomalies.isEmpty())
        assertEquals(38000.0, matched.centralCurrencyTotals["SDG"] ?: 0.0, 0.01)
        assertEquals(38000.0, matched.glCashTotals["SDG"] ?: 0.0, 0.01)

        // Introduce divergence: local Room missing movOut (with pending write) + Web lags at 50000
        val pendingMovIn = movIn.copy(syncStatus = "PENDING")
        val diverged = CrossPlatformReconciliationEngine.reconcile(
            centralCashboxes = boxes,
            centralMovements = centralMovs,
            centralInvoices = emptyList(),
            centralPayments = emptyList(),
            localCashboxes = boxes,
            localMovements = listOf(pendingMovIn),
            journalEntries = listOf(jrnEntry),
            journalLines = jrnLines,
            webCheckpoint = alignedCp.copy(currencyTotals = mapOf("SDG" to 50000.0, "EGP" to 0.0, "USD" to 0.0)),
            desktopCheckpoint = alignedCp.copy(platform = "DESKTOP"),
            androidCheckpoint = alignedCp.copy(platform = "ANDROID"),
            pendingWritesCount = 1
        )

        assertEquals("VARIANCE_DETECTED", diverged.overallStatus)
        assertTrue(diverged.anomalies.any { it.rootCauseSource == "PENDING_OFFLINE_WRITES" && it.safeRemediationAction == "FLUSH_PENDING_OUTBOX" })
        val webAnomaly = diverged.anomalies.firstOrNull { it.id == "CHECKPOINT_WEB_SDG" }
        assertTrue(webAnomaly != null)
        assertEquals(-12000.0, webAnomaly!!.varianceAmount, 0.01)
        assertEquals("UNSYNCED_CLIENT_CHECKPOINT", webAnomaly.rootCauseSource)
    }

    @Test
    fun `treasury normalizer excludes reversed movements and telemetry redaction masks credentials`() {
        val active = MovementEntity(
            id = 1,
            txDate = "2026-11-25",
            cashbox = "خزنة مصر",
            currency = "EGP",
            direction = "وارد",
            amount = 4500.0,
            description = "إيداع",
            reference = "EGP-1",
            status = "active"
        )
        val reversed = active.copy(id = 2, reference = "EGP-2", status = "reversed")

        assertTrue(TreasuryBalanceNormalizer.isCancelledOrReversedStatus("reversed"))
        assertTrue(TreasuryBalanceNormalizer.isCancelledOrReversedStatus("ملغاة"))
        assertFalse(TreasuryBalanceNormalizer.isCancelledOrReversedStatus("active"))
        assertEquals(4500.0, TreasuryBalanceNormalizer.signedActiveAmount(active), 0.001)
        assertEquals(0.0, TreasuryBalanceNormalizer.signedActiveAmount(reversed), 0.001)

        val leaked = "Authorization: Bearer myTok123 token=eyJhbGciOiJIUzI1NiJ9.eyJhIjoxfQ.xyz key=AIzaSyB1234567890abcdefghijk pinOrPassword=4321 refreshToken=ref999"
        val redacted = StatementPdfAndExcelExporter.redactHealthTelemetry(leaked)
        assertFalse(redacted.contains("myTok123"))
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(redacted.contains("AIzaSyB1234567890"))
        assertFalse(redacted.contains("4321"))
        assertFalse(redacted.contains("ref999"))
        assertTrue(redacted.contains("Bearer [REDACTED]"))
        assertTrue(redacted.contains("[JWT_REDACTED]"))
        assertTrue(redacted.contains("[API_KEY_REDACTED]"))
    }

    @Test
    fun `admin privilege helper remains exclusive to admin and MIGRATION_17_18 is non-destructive`() {
        val superAdmin = UserAccountEntity(
            id = 1,
            userCode = "USR-0001",
            fullName = "المدير العام",
            username = "admin",
            role = "مدير النظام",
            branch = "الإدارة العامة",
            permissions = "ALL",
            serverSyncToken = "tok"
        )
        val manager = superAdmin.copy(id = 2, userCode = "USR-0002", username = "branch_mgr", role = "مدير فرع ومصنع مصر")
        val accountant = superAdmin.copy(id = 3, userCode = "USR-0003", username = "acc1", role = "المحاسب")

        assertTrue(superAdmin.isSuperAdminAccount())
        assertFalse(manager.isSuperAdminAccount())
        assertFalse(accountant.isSuperAdminAccount())

        assertEquals(17, PmpDatabase.MIGRATION_17_18.startVersion)
        assertEquals(18, PmpDatabase.MIGRATION_17_18.endVersion)

        val acc3900 = com.example.domain.accounting.AccountingLedgerEngine.DEFAULT_ACCOUNTS.firstOrNull {
            it.accountCode == "3900"
        }
        assertTrue("Reconciliation adjustment account 3900 must exist in Android DEFAULT_ACCOUNTS", acc3900 != null)
        assertEquals("EQUITY", acc3900!!.accountType)
    }
}
