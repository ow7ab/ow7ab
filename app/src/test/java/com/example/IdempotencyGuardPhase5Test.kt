package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.IdempotencyRequestEntity
import com.example.data.local.PmpDatabase
import com.example.util.IdempotencyBackendAdapter
import com.example.util.IdempotencyClaimOutcome
import com.example.util.IdempotencyOperationType
import com.example.util.IdempotencyRequestStatus
import com.example.util.IdempotencyStatusMessages
import com.example.util.TransactionSubmissionGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IdempotencyGuardPhase5Test {

    private lateinit var db: PmpDatabase

    private class FakeBackendAdapter : IdempotencyBackendAdapter {
        val requests = ConcurrentHashMap<String, IdempotencyRequestEntity>()
        val clusterDocs = ConcurrentHashMap<String, Boolean>()
        val claimCalls = AtomicInteger(0)
        var simulateUncertainNetwork = false

        override suspend fun claimOrGetRequest(
            clusterCode: String,
            request: IdempotencyRequestEntity
        ): IdempotencyClaimOutcome {
            claimCalls.incrementAndGet()
            if (simulateUncertainNetwork) {
                return IdempotencyClaimOutcome.NetworkUncertain(
                    idempotencyKey = request.idempotencyKey,
                    reason = "انتهت مهلة الاتصال أثناء المطالبة بالمعرف"
                )
            }
            val mapKey = "$clusterCode::${request.idempotencyKey}"
            val existing = requests.putIfAbsent(mapKey, request)
            if (existing == null) {
                return IdempotencyClaimOutcome.Claimed(request.idempotencyKey)
            }
            return if (existing.requestStatus == IdempotencyRequestStatus.COMPLETED.code) {
                IdempotencyClaimOutcome.DuplicateCompleted(
                    idempotencyKey = request.idempotencyKey,
                    existingResultReference = existing.resultReference
                )
            } else {
                IdempotencyClaimOutcome.DuplicateInProgress(
                    idempotencyKey = request.idempotencyKey,
                    existingStatus = existing.requestStatus
                )
            }
        }

        override suspend fun markCompleted(
            clusterCode: String,
            idempotencyKey: String,
            resultReference: String
        ) {
            val mapKey = "$clusterCode::$idempotencyKey"
            val now = System.currentTimeMillis()
            val prev = requests[mapKey]
            if (prev != null) {
                requests[mapKey] = prev.copy(
                    requestStatus = IdempotencyRequestStatus.COMPLETED.code,
                    updatedAt = now,
                    completionTime = now,
                    resultReference = resultReference
                )
            }
        }

        override suspend fun markPendingOrFailed(
            clusterCode: String,
            idempotencyKey: String,
            status: IdempotencyRequestStatus,
            errorDetails: String
        ) {
            val mapKey = "$clusterCode::$idempotencyKey"
            val now = System.currentTimeMillis()
            val prev = requests[mapKey]
            if (prev != null) {
                requests[mapKey] = prev.copy(
                    requestStatus = status.code,
                    updatedAt = now,
                    errorDetails = errorDetails
                )
            }
        }

        override suspend fun fetchRequest(
            clusterCode: String,
            idempotencyKey: String
        ): IdempotencyRequestEntity? = requests["$clusterCode::$idempotencyKey"]

        override suspend fun checkClusterDocumentExists(
            clusterCode: String,
            targetCollection: String,
            documentReference: String
        ): Boolean = clusterDocs["$clusterCode::$targetCollection::$documentReference"] == true
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PmpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `exact 11 loading texts and 6 standardized Arabic status messages match specification`() {
        assertEquals("جاري الحفظ...", IdempotencyOperationType.SAVE.loadingLabel)
        assertEquals("جاري إضافة العملية...", IdempotencyOperationType.CREATE.loadingLabel)
        assertEquals("جاري توريد الخامات...", IdempotencyOperationType.SUPPLY_RAW.loadingLabel)
        assertEquals("جاري تنفيذ الصرف...", IdempotencyOperationType.DISPATCH_GOODS.loadingLabel)
        assertEquals("جاري التحويل...", IdempotencyOperationType.STOCK_TRANSFER.loadingLabel)
        assertEquals("جاري اعتماد الفاتورة...", IdempotencyOperationType.APPROVE_INVOICE.loadingLabel)
        assertEquals("جاري تنفيذ السداد...", IdempotencyOperationType.PAYMENT.loadingLabel)
        assertEquals("جاري تسجيل سند القبض...", IdempotencyOperationType.RECEIPT_VOUCHER.loadingLabel)
        assertEquals("جاري تسجيل سند الصرف...", IdempotencyOperationType.PAYMENT_VOUCHER.loadingLabel)
        assertEquals("جاري تحديث البيانات...", IdempotencyOperationType.UPDATE.loadingLabel)
        assertEquals("جاري تنفيذ الحذف...", IdempotencyOperationType.DELETE.loadingLabel)

        assertEquals("جاري تنفيذ العملية، يرجى الانتظار...", IdempotencyStatusMessages.IN_PROGRESS)
        assertEquals("الاتصال بطيء، يتم التحقق من حالة العملية...", IdempotencyStatusMessages.SLOW_CHECKING)
        assertEquals("تم تنفيذ العملية بنجاح.", IdempotencyStatusMessages.COMPLETED)
        assertEquals("تعذر تنفيذ العملية، يرجى مراجعة التفاصيل.", IdempotencyStatusMessages.FAILED)
        assertEquals("لم يتم التأكد من نتيجة العملية بعد، لا تعِد إدخالها.", IdempotencyStatusMessages.PENDING_DO_NOT_REENTER)
        assertEquals("العملية مسجلة مسبقاً.", IdempotencyStatusMessages.ALREADY_RECORDED)
    }

    @Test
    fun `concurrent rapid multi-taps on save and vouchers execute strictly once`(): Unit = runBlocking {
        db.idempotencyDao().getRecentDirect(1) // warm up Room before concurrency assertions
        val backend = FakeBackendAdapter()
        val guard = TransactionSubmissionGuard(
            idempotencyDao = db.idempotencyDao(),
            backendAdapter = backend,
            userIdProvider = { "USR-PMP-001" }
        )
        val execCount = AtomicInteger(0)
        val firstEntered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        val jobs = (1..5).map { idx ->
            async {
                guard.executeGuarded(
                    guardKey = "create_invoice_btn",
                    operationType = IdempotencyOperationType.APPROVE_INVOICE,
                    documentReference = "INV-1001",
                    targetCollection = "invoices",
                    payloadSummary = "فاتورة بيع #$idx"
                ) {
                    execCount.incrementAndGet()
                    firstEntered.complete(Unit)
                    gate.await()
                    Result.success("INV-1001" to "OK")
                }
            }
        }

        firstEntered.await()
        assertTrue(guard.isProcessing("create_invoice_btn"))
        assertEquals("جاري اعتماد الفاتورة...", guard.loadingLabelFor("create_invoice_btn"))
        assertTrue(guard.isDocumentLocked("INV-1001"))

        gate.complete(Unit)
        val results = jobs.awaitAll()

        assertEquals(1, execCount.get())
        assertEquals(1, results.count { it.success && !it.blockedAsDuplicate })
        assertEquals(4, results.count { it.blockedAsDuplicate })
        assertFalse(guard.isProcessing("create_invoice_btn"))
        assertFalse(guard.isDocumentLocked("INV-1001"))

        // Double tap on receipt voucher also executes strictly once
        val receiptCount = AtomicInteger(0)
        val receiptEntered = CompletableDeferred<Unit>()
        val receiptGate = CompletableDeferred<Unit>()
        val receiptJobs = (1..3).map {
            async {
                guard.executeGuarded(
                    guardKey = "receipt_voucher_btn",
                    operationType = IdempotencyOperationType.RECEIPT_VOUCHER,
                    documentReference = "RCV-2026-1",
                    targetCollection = "invoice_payments"
                ) {
                    receiptCount.incrementAndGet()
                    receiptEntered.complete(Unit)
                    receiptGate.await()
                    Result.success("RCV-2026-1" to Unit)
                }
            }
        }
        receiptEntered.await()
        assertEquals("جاري تسجيل سند القبض...", guard.loadingLabelFor("receipt_voucher_btn"))
        receiptGate.complete(Unit)
        receiptJobs.awaitAll()
        assertEquals(1, receiptCount.get())
    }

    @Test
    fun `slow response shows slow status message and lock clears cleanly on completion`(): Unit = runBlocking {
        db.idempotencyDao().getRecentDirect(1) // warm up Room before slow monitor check
        val backend = FakeBackendAdapter()
        val guard = TransactionSubmissionGuard(
            idempotencyDao = db.idempotencyDao(),
            backendAdapter = backend,
            slowThresholdMs = 50L
        )
        val blockEntered = CompletableDeferred<Unit>()
        val releaseBlock = CompletableDeferred<Unit>()
        val job = async {
            guard.executeGuarded(
                guardKey = "slow_transfer",
                operationType = IdempotencyOperationType.STOCK_TRANSFER,
                documentReference = "TRF-SLOW-1",
                scopeForSlowMonitor = this@runBlocking
            ) {
                blockEntered.complete(Unit)
                releaseBlock.await()
                Result.success("TRF-SLOW-1" to "DONE")
            }
        }
        blockEntered.await()
        withTimeout(2_000) {
            while (guard.guardStatusMessages.value["slow_transfer"] != IdempotencyStatusMessages.SLOW_CHECKING) {
                delay(15)
            }
        }
        assertEquals(IdempotencyStatusMessages.SLOW_CHECKING, guard.guardStatusMessages.value["slow_transfer"])
        releaseBlock.complete(Unit)
        val result = job.await()
        assertTrue(result.success)
        assertEquals(IdempotencyStatusMessages.COMPLETED, result.message)
        assertFalse(guard.isProcessing("slow_transfer"))
    }

    @Test
    fun `network drop sets PENDING_CONFIRMATION and blocks duplicate document edits while validation error unlocks safely`(): Unit = runBlocking {
        val backend = FakeBackendAdapter()
        val guard = TransactionSubmissionGuard(
            idempotencyDao = db.idempotencyDao(),
            backendAdapter = backend
        )

        // 1. Mid-flight SocketTimeoutException -> PENDING_CONFIRMATION
        val netRes = guard.executeGuarded<String>(
            guardKey = "raw_supply_btn",
            operationType = IdempotencyOperationType.SUPPLY_RAW,
            documentReference = "GRN-TIMEOUT-77",
            targetCollection = "purchase_receipts"
        ) {
            throw SocketTimeoutException("timeout while syncing")
        }
        assertFalse(netRes.success)
        assertTrue(netRes.pendingConfirmation)
        assertEquals(IdempotencyRequestStatus.PENDING_CONFIRMATION, netRes.status)
        assertEquals(IdempotencyStatusMessages.PENDING_DO_NOT_REENTER, netRes.message)

        // Re-submitting same document from another button/tab is blocked while PENDING_CONFIRMATION!
        val dupDocRes = guard.executeGuarded(
            guardKey = "other_screen_btn",
            operationType = IdempotencyOperationType.SUPPLY_RAW,
            documentReference = "GRN-TIMEOUT-77",
            targetCollection = "purchase_receipts"
        ) {
            Result.success("SHOULD_NOT_RUN" to Unit)
        }
        assertTrue(dupDocRes.blockedAsDuplicate)
        assertTrue(dupDocRes.pendingConfirmation)

        // 2. Deterministic validation error -> FAILED and immediately unlocks for user retry
        val valRes = guard.executeGuarded<String>(
            guardKey = "payment_btn",
            operationType = IdempotencyOperationType.PAYMENT,
            documentReference = "PAY-INVALID-1"
        ) {
            Result.failure(IllegalArgumentException("المبلغ يتجاوز الرصيد المتبقي"))
        }
        assertFalse(valRes.success)
        assertFalse(valRes.pendingConfirmation)
        assertEquals(IdempotencyRequestStatus.FAILED, valRes.status)
        assertFalse(guard.isProcessing("payment_btn"))
        assertFalse(guard.isDocumentLocked("PAY-INVALID-1"))

        // User corrects input and retries -> succeeds cleanly with a fresh key
        val retryRes = guard.executeGuarded(
            guardKey = "payment_btn",
            operationType = IdempotencyOperationType.PAYMENT,
            documentReference = "PAY-INVALID-1"
        ) {
            Result.success("PAY-VALID-1" to 500.0)
        }
        assertTrue(retryRes.success)
        assertEquals(500.0, retryRes.data)
    }

    @Test
    fun `explicit idempotencyKey replay blocks duplicates and recoverPendingOperations reconciles both executed and unexecuted ops`(): Unit = runBlocking {
        val backend = FakeBackendAdapter()
        val guard = TransactionSubmissionGuard(
            idempotencyDao = db.idempotencyDao(),
            backendAdapter = backend
        )

        // 1. Complete an operation with explicit key and attempt to replay it
        val fixedKey = "IDEMP-SAVE-FIXED-001"
        val firstRun = guard.executeGuarded(
            guardKey = "save_btn",
            operationType = IdempotencyOperationType.SAVE,
            documentReference = "DOC-FIXED-1",
            explicitIdempotencyKey = fixedKey
        ) {
            Result.success("DOC-FIXED-1" to 42)
        }
        assertTrue(firstRun.success)

        val replayRun = guard.executeGuarded(
            guardKey = "save_btn",
            operationType = IdempotencyOperationType.SAVE,
            documentReference = "DOC-FIXED-1",
            explicitIdempotencyKey = fixedKey
        ) {
            Result.success("SHOULD_NOT_EXECUTE" to 99)
        }
        assertTrue(replayRun.blockedAsDuplicate)
        assertTrue(replayRun.alreadyRecorded)
        assertEquals(IdempotencyStatusMessages.ALREADY_RECORDED, replayRun.message)

        // 2. Sequential new operation generates distinct fresh key and succeeds
        val secondRun = guard.executeGuarded(
            guardKey = "save_btn",
            operationType = IdempotencyOperationType.SAVE,
            documentReference = "DOC-FIXED-2"
        ) {
            Result.success("DOC-FIXED-2" to 100)
        }
        assertTrue(secondRun.success)
        assertNotEquals(firstRun.idempotencyKey, secondRun.idempotencyKey)

        // 3. Startup/Reconnect recovery: case A -> already executed on Firestore collection
        val now = System.currentTimeMillis()
        val alreadyExecutedKey = "IDEMP-CREATE-ORPHAN-EXEC"
        db.idempotencyDao().upsert(
            IdempotencyRequestEntity(
                idempotencyKey = alreadyExecutedKey,
                userId = "USR-PMP-001",
                operationType = "CREATE",
                documentReference = "INV-ORPHAN-99",
                requestStatus = "PENDING_CONFIRMATION",
                createdAt = now - 5_000,
                updatedAt = now - 5_000,
                targetCollection = "invoices"
            )
        )
        backend.clusterDocs["PMP-ERP-MAIN-CLUSTER::invoices::INV-ORPHAN-99"] = true

        // 4. Startup/Reconnect recovery: case B -> never reached server
        val neverExecutedKey = "IDEMP-CREATE-ORPHAN-NONE"
        db.idempotencyDao().upsert(
            IdempotencyRequestEntity(
                idempotencyKey = neverExecutedKey,
                userId = "USR-PMP-001",
                operationType = "CREATE",
                documentReference = "INV-ORPHAN-100",
                requestStatus = "PENDING_CONFIRMATION",
                createdAt = now - 5_000,
                updatedAt = now - 5_000,
                targetCollection = "invoices"
            )
        )

        val recovered = guard.recoverPendingOperations()
        assertEquals(2, recovered.size)

        val execVer = recovered.first { it.idempotencyKey == alreadyExecutedKey }
        assertEquals(IdempotencyRequestStatus.COMPLETED, execVer.resolvedStatus)
        assertFalse(execVer.safeToRetry)
        assertEquals(IdempotencyStatusMessages.ALREADY_RECORDED, execVer.message)

        val noneVer = recovered.first { it.idempotencyKey == neverExecutedKey }
        assertEquals(IdempotencyRequestStatus.CANCELLED, noneVer.resolvedStatus)
        assertTrue(noneVer.safeToRetry)
    }

    @Test
    fun `coroutine cancellation mid-flight persists PENDING_CONFIRMATION instead of losing operation state`(): Unit = runBlocking {
        val backend = FakeBackendAdapter()
        val guard = TransactionSubmissionGuard(
            idempotencyDao = db.idempotencyDao(),
            backendAdapter = backend
        )
        val started = CompletableDeferred<Unit>()
        val job = launch {
            guard.executeGuarded(
                guardKey = "cancel_midflight",
                operationType = IdempotencyOperationType.UPDATE,
                documentReference = "DOC-CANCEL-1"
            ) {
                started.complete(Unit)
                delay(5_000)
                Result.success("DOC-CANCEL-1" to Unit)
            }
        }
        started.await()
        delay(40)
        job.cancel(CancellationException("screen closed"))
        job.join()

        val stored = db.idempotencyDao().getRecentDirect(50).firstOrNull { it.documentReference == "DOC-CANCEL-1" }
        assertNotNull(stored)
        assertEquals("PENDING_CONFIRMATION", stored?.requestStatus)
        assertFalse(guard.isProcessing("cancel_midflight"))
    }

    @Test
    fun `version bumped to 21 and shared idempotency files exist across Android, Web, Desktop, and Rules`() {
        val rootDir = listOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }
        assertEquals(21, BuildConfig.VERSION_CODE)
        assertTrue(BuildConfig.VERSION_NAME.startsWith("21.0"))
        assertTrue(File(rootDir, "app/src/main/java/com/example/util/IdempotencyProtectionSystem.kt").exists())
        assertTrue(File(rootDir, "web/src/services/IdempotencyService.ts").exists())
        assertTrue(File(rootDir, "web/src/hooks/useTransactionGuard.ts").exists())
        assertTrue(File(rootDir, "web/src/components/modules/OperationTrackingView.tsx").exists())
        assertTrue(File(rootDir, "firestore.rules").readText().contains("idempotency_requests"))
    }
}
