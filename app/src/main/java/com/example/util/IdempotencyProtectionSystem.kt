package com.example.util

import com.example.data.local.IdempotencyDao
import com.example.data.local.IdempotencyRequestEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

/**
 * Exact Arabic operation types & button loading labels required across Android, Desktop, and Web.
 */
enum class IdempotencyOperationType(
    val code: String,
    val titleAr: String,
    val loadingLabel: String
) {
    SAVE("SAVE", "الحفظ", "جاري الحفظ..."),
    CREATE("CREATE", "الإضافة", "جاري إضافة العملية..."),
    SUPPLY_RAW("SUPPLY_RAW", "توريد الخامات", "جاري توريد الخامات..."),
    DISPATCH_GOODS("DISPATCH_GOODS", "صرف البضاعة", "جاري تنفيذ الصرف..."),
    STOCK_TRANSFER("STOCK_TRANSFER", "التحويل المخزني", "جاري التحويل..."),
    APPROVE_INVOICE("APPROVE_INVOICE", "اعتماد الفاتورة", "جاري اعتماد الفاتورة..."),
    PAYMENT("PAYMENT", "السداد", "جاري تنفيذ السداد..."),
    RECEIPT_VOUCHER("RECEIPT_VOUCHER", "تسجيل القبض", "جاري تسجيل سند القبض..."),
    PAYMENT_VOUCHER("PAYMENT_VOUCHER", "تسجيل الصرف", "جاري تسجيل سند الصرف..."),
    UPDATE("UPDATE", "التعديل", "جاري تحديث البيانات..."),
    DELETE("DELETE", "الحذف المسموح", "جاري تنفيذ الحذف...")
}

/**
 * Unified processing lifecycle states across all platforms.
 */
enum class IdempotencyRequestStatus(val code: String, val titleAr: String) {
    DRAFT("DRAFT", "مسودة"),
    PROCESSING("PROCESSING", "قيد التنفيذ"),
    PENDING_CONFIRMATION("PENDING_CONFIRMATION", "بانتظار التأكيد من الخادم"),
    COMPLETED("COMPLETED", "مكتملة بنجاح"),
    FAILED("FAILED", "فشلت"),
    CANCELLED("CANCELLED", "ملغاة");

    val isActiveOrPending: Boolean
        get() = this == PROCESSING || this == PENDING_CONFIRMATION

    companion object {
        fun fromCode(raw: String?): IdempotencyRequestStatus =
            entries.firstOrNull { it.code.equals(raw?.trim(), ignoreCase = true) } ?: DRAFT
    }
}

/**
 * Exact standardized Arabic status messages.
 */
object IdempotencyStatusMessages {
    const val IN_PROGRESS = "جاري تنفيذ العملية، يرجى الانتظار..."
    const val SLOW_CHECKING = "الاتصال بطيء، يتم التحقق من حالة العملية..."
    const val COMPLETED = "تم تنفيذ العملية بنجاح."
    const val FAILED = "تعذر تنفيذ العملية، يرجى مراجعة التفاصيل."
    const val PENDING_DO_NOT_REENTER = "لم يتم التأكد من نتيجة العملية بعد، لا تعِد إدخالها."
    const val ALREADY_RECORDED = "العملية مسجلة مسبقاً."
}

sealed class IdempotencyClaimOutcome {
    data class Claimed(val idempotencyKey: String) : IdempotencyClaimOutcome()
    data class DuplicateCompleted(
        val idempotencyKey: String,
        val existingResultReference: String = "",
        val message: String = IdempotencyStatusMessages.ALREADY_RECORDED
    ) : IdempotencyClaimOutcome()

    data class DuplicateInProgress(
        val idempotencyKey: String,
        val existingStatus: String = IdempotencyRequestStatus.PROCESSING.code,
        val message: String = IdempotencyStatusMessages.PENDING_DO_NOT_REENTER
    ) : IdempotencyClaimOutcome()

    data class NetworkUncertain(
        val idempotencyKey: String,
        val reason: String
    ) : IdempotencyClaimOutcome()

    data class Rejected(
        val idempotencyKey: String,
        val reason: String
    ) : IdempotencyClaimOutcome()
}

data class GuardedExecutionResult<T>(
    val success: Boolean,
    val blockedAsDuplicate: Boolean = false,
    val alreadyRecorded: Boolean = false,
    val pendingConfirmation: Boolean = false,
    val idempotencyKey: String = "",
    val status: IdempotencyRequestStatus = IdempotencyRequestStatus.DRAFT,
    val message: String = "",
    val data: T? = null,
    val error: Throwable? = null
)

data class OperationVerificationResult(
    val idempotencyKey: String,
    val resolvedStatus: IdempotencyRequestStatus,
    val message: String,
    val safeToRetry: Boolean = false,
    val resultReference: String = ""
)

/**
 * Abstraction for server/Firestore atomic idempotency claims & checks.
 * Allows seamless production Firestore transactions and fast unit tests.
 */
interface IdempotencyBackendAdapter {
    suspend fun claimOrGetRequest(
        clusterCode: String,
        request: IdempotencyRequestEntity
    ): IdempotencyClaimOutcome

    suspend fun markCompleted(
        clusterCode: String,
        idempotencyKey: String,
        resultReference: String,
        operationType: String = IdempotencyOperationType.SAVE.code
    )

    suspend fun markPendingOrFailed(
        clusterCode: String,
        idempotencyKey: String,
        status: IdempotencyRequestStatus,
        errorDetails: String,
        operationType: String = IdempotencyOperationType.SAVE.code
    )

    suspend fun fetchRequest(
        clusterCode: String,
        idempotencyKey: String
    ): IdempotencyRequestEntity?

    suspend fun checkClusterDocumentExists(
        clusterCode: String,
        targetCollection: String,
        documentReference: String
    ): Boolean
}

/**
 * In-memory Room fallback / test DAO bridge when running isolated tests or standalone guard checks.
 */
class InMemoryIdempotencyStore {
    private val map = LinkedHashMap<String, IdempotencyRequestEntity>()
    private val lock = Any()

    fun upsert(entity: IdempotencyRequestEntity) {
        synchronized(lock) {
            val existing = map[entity.idempotencyKey]
            val id = if (entity.id > 0L) entity.id else (existing?.id ?: (map.size + 1L))
            map[entity.idempotencyKey] = entity.copy(id = id)
        }
    }

    fun getByKey(key: String): IdempotencyRequestEntity? = synchronized(lock) { map[key] }

    fun getUnresolved(): List<IdempotencyRequestEntity> = synchronized(lock) {
        map.values.filter {
            it.requestStatus == IdempotencyRequestStatus.PROCESSING.code ||
                it.requestStatus == IdempotencyRequestStatus.PENDING_CONFIRMATION.code
        }.sortedByDescending { it.createdAt }
    }

    fun getAll(): List<IdempotencyRequestEntity> = synchronized(lock) {
        map.values.sortedByDescending { it.updatedAt }
    }
}

/**
 * Central Transaction Submission Guard + Idempotency Protection System.
 * Guarantees:
 * - Immediate synchronous lock before async execution (immune to Compose recomposition & rapid taps)
 * - Document-level concurrency lock (blocks parallel submits on the same documentReference)
 * - Unique Idempotency Key lifecycle persisted locally + atomically in Firestore
 * - Network drop / timeout -> PENDING_CONFIRMATION instead of silent drop or fake failure
 * - Validation/business error -> FAILED + lock released immediately + form inputs preserved
 */
class TransactionSubmissionGuard(
    private val idempotencyDao: IdempotencyDao? = null,
    private val backendAdapter: IdempotencyBackendAdapter? = null,
    private val clusterCodeProvider: () -> String = { "PMP-ERP-MAIN-CLUSTER" },
    private val userIdProvider: () -> String = { "SYSTEM" },
    private val isOnlineProvider: () -> Boolean = { true },
    private val slowThresholdMs: Long = 4_500L,
    private val staleProcessingTimeoutMs: Long = 60_000L
) {
    private data class ActiveLock(
        val guardKey: String,
        val documentReference: String,
        val idempotencyKey: String,
        val operationType: IdempotencyOperationType,
        val acquiredAt: Long
    )

    private val lockMutex = Any()
    private val activeLocksByGuard = ConcurrentHashMap<String, ActiveLock>()
    private val activeLocksByDoc = ConcurrentHashMap<String, ActiveLock>()
    private val inMemoryStore = InMemoryIdempotencyStore()

    private val _activeGuardKeys = MutableStateFlow<Set<String>>(emptySet())
    val activeGuardKeys: StateFlow<Set<String>> = _activeGuardKeys.asStateFlow()

    private val _guardLoadingLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val guardLoadingLabels: StateFlow<Map<String, String>> = _guardLoadingLabels.asStateFlow()

    private val _guardStatusMessages = MutableStateFlow<Map<String, String>>(emptyMap())
    val guardStatusMessages: StateFlow<Map<String, String>> = _guardStatusMessages.asStateFlow()

    private val _recordsSnapshot = MutableStateFlow<List<IdempotencyRequestEntity>>(emptyList())
    val recordsSnapshot: StateFlow<List<IdempotencyRequestEntity>> = _recordsSnapshot.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    fun generateIdempotencyKey(
        operationType: IdempotencyOperationType,
        documentReference: String = ""
    ): String {
        val docPart = documentReference
            .trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(24)
            .ifBlank { "DOC" }
        val rand = UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
        return "IDEMP-${operationType.code}-$docPart-$rand"
    }

    fun isProcessing(guardKey: String): Boolean = activeLocksByGuard.containsKey(guardKey)

    fun isDocumentLocked(documentReference: String): Boolean {
        val norm = normalizeDocRef(documentReference)
        return norm.isNotBlank() && activeLocksByDoc.containsKey(norm)
    }

    fun loadingLabelFor(guardKey: String, fallback: String = ""): String =
        _guardLoadingLabels.value[guardKey] ?: fallback

    private fun normalizeDocRef(documentReference: String): String =
        documentReference.trim().uppercase()

    private fun refreshPublishedLockState() {
        _activeGuardKeys.value = activeLocksByGuard.keys.toSet()
        _guardLoadingLabels.value = activeLocksByGuard.mapValues { it.value.operationType.loadingLabel }
    }

    private suspend fun persistRecord(entity: IdempotencyRequestEntity) {
        inMemoryStore.upsert(entity)
        runCatching { idempotencyDao?.upsert(entity) }
        publishRecordsSnapshot()
    }

    private suspend fun publishRecordsSnapshot() {
        val records = runCatching {
            idempotencyDao?.getRecentDirect(200)
        }.getOrNull() ?: inMemoryStore.getAll()
        _recordsSnapshot.value = records
        _pendingCount.value = records.count {
            it.requestStatus == IdempotencyRequestStatus.PROCESSING.code ||
                it.requestStatus == IdempotencyRequestStatus.PENDING_CONFIRMATION.code
        }
    }

    suspend fun getLocalRecord(idempotencyKey: String): IdempotencyRequestEntity? {
        return runCatching { idempotencyDao?.getByKey(idempotencyKey) }.getOrNull()
            ?: inMemoryStore.getByKey(idempotencyKey)
    }

    suspend fun getAllRecords(): List<IdempotencyRequestEntity> {
        publishRecordsSnapshot()
        return _recordsSnapshot.value
    }

    /**
     * Synchronous atomic lock check that MUST succeed before dispatching any async mutation.
     */
    private fun tryAcquireSynchronousLock(
        guardKey: String,
        documentReference: String,
        operationType: IdempotencyOperationType,
        idempotencyKey: String
    ): ActiveLock? {
        val now = System.currentTimeMillis()
        val normDoc = normalizeDocRef(documentReference)
        synchronized(lockMutex) {
            if (activeLocksByGuard.containsKey(guardKey)) {
                return null
            }
            if (normDoc.isNotBlank() && activeLocksByDoc.containsKey(normDoc)) {
                return null
            }
            val lock = ActiveLock(
                guardKey = guardKey,
                documentReference = normDoc,
                idempotencyKey = idempotencyKey,
                operationType = operationType,
                acquiredAt = now
            )
            activeLocksByGuard[guardKey] = lock
            if (normDoc.isNotBlank()) {
                activeLocksByDoc[normDoc] = lock
            }
            refreshPublishedLockState()
            _guardStatusMessages.value = _guardStatusMessages.value + (guardKey to IdempotencyStatusMessages.IN_PROGRESS)
            return lock
        }
    }

    private fun releaseSynchronousLock(lock: ActiveLock) {
        synchronized(lockMutex) {
            val currentGuard = activeLocksByGuard[lock.guardKey]
            if (currentGuard?.idempotencyKey == lock.idempotencyKey) {
                activeLocksByGuard.remove(lock.guardKey)
            }
            if (lock.documentReference.isNotBlank()) {
                val currentDoc = activeLocksByDoc[lock.documentReference]
                if (currentDoc?.idempotencyKey == lock.idempotencyKey) {
                    activeLocksByDoc.remove(lock.documentReference)
                }
            }
            refreshPublishedLockState()
        }
    }

    /**
     * Executes a guarded mutating ERP operation end-to-end with synchronous locking,
     * local Room persistence, slow-network monitoring, and atomic Firestore idempotency protection.
     */
    suspend fun <T> executeGuarded(
        guardKey: String,
        operationType: IdempotencyOperationType,
        documentReference: String = "",
        targetCollection: String = "",
        payloadSummary: String = "",
        explicitIdempotencyKey: String? = null,
        requiresServerClaim: Boolean = true,
        scopeForSlowMonitor: CoroutineScope? = null,
        block: suspend (idempotencyKey: String) -> Result<Pair<String, T>>
    ): GuardedExecutionResult<T> {
        val cleanDocRef = documentReference.trim()
        val existingCompleted = explicitIdempotencyKey?.takeIf { it.isNotBlank() }?.let { getLocalRecord(it) }
        if (existingCompleted != null) {
            val status = IdempotencyRequestStatus.fromCode(existingCompleted.requestStatus)
            if (status == IdempotencyRequestStatus.COMPLETED) {
                _guardStatusMessages.value = _guardStatusMessages.value + (guardKey to IdempotencyStatusMessages.ALREADY_RECORDED)
                return GuardedExecutionResult(
                    success = true,
                    blockedAsDuplicate = true,
                    alreadyRecorded = true,
                    idempotencyKey = existingCompleted.idempotencyKey,
                    status = IdempotencyRequestStatus.COMPLETED,
                    message = IdempotencyStatusMessages.ALREADY_RECORDED
                )
            }
            if (status.isActiveOrPending) {
                _guardStatusMessages.value = _guardStatusMessages.value + (guardKey to IdempotencyStatusMessages.PENDING_DO_NOT_REENTER)
                return GuardedExecutionResult(
                    success = false,
                    blockedAsDuplicate = true,
                    pendingConfirmation = true,
                    idempotencyKey = existingCompleted.idempotencyKey,
                    status = status,
                    message = IdempotencyStatusMessages.PENDING_DO_NOT_REENTER
                )
            }
        }

        // Prevent duplicate work on the same active persisted documentReference (even from another tab/screen)
        if (cleanDocRef.isNotBlank()) {
            val activeDocRec = runCatching { idempotencyDao?.getActiveByDocumentReference(cleanDocRef) }.getOrNull()
                ?: inMemoryStore.getUnresolved().firstOrNull {
                    it.documentReference.equals(cleanDocRef, ignoreCase = true)
                }
            if (activeDocRec != null) {
                _guardStatusMessages.value = _guardStatusMessages.value + (guardKey to IdempotencyStatusMessages.PENDING_DO_NOT_REENTER)
                return GuardedExecutionResult(
                    success = false,
                    blockedAsDuplicate = true,
                    pendingConfirmation = true,
                    idempotencyKey = activeDocRec.idempotencyKey,
                    status = IdempotencyRequestStatus.fromCode(activeDocRec.requestStatus),
                    message = IdempotencyStatusMessages.PENDING_DO_NOT_REENTER
                )
            }
        }

        val idempotencyKey = explicitIdempotencyKey?.trim()?.takeIf { it.isNotBlank() }
            ?: generateIdempotencyKey(operationType, cleanDocRef)

        val lock = tryAcquireSynchronousLock(
            guardKey = guardKey,
            documentReference = cleanDocRef,
            operationType = operationType,
            idempotencyKey = idempotencyKey
        ) ?: return GuardedExecutionResult(
            success = false,
            blockedAsDuplicate = true,
            idempotencyKey = idempotencyKey,
            status = IdempotencyRequestStatus.PROCESSING,
            message = IdempotencyStatusMessages.IN_PROGRESS
        )

        val now = System.currentTimeMillis()
        val initialEntity = IdempotencyRequestEntity(
            idempotencyKey = idempotencyKey,
            userId = userIdProvider(),
            operationType = operationType.code,
            documentReference = cleanDocRef,
            requestStatus = IdempotencyRequestStatus.PROCESSING.code,
            createdAt = now,
            updatedAt = now,
            completionTime = null,
            resultReference = "",
            errorDetails = "",
            targetCollection = targetCollection,
            payloadSummary = payloadSummary,
            platform = "ANDROID",
            guardKey = guardKey
        )

        val slowMonitorJob = scopeForSlowMonitor?.launch {
            delay(slowThresholdMs)
            if (activeLocksByGuard.containsKey(guardKey)) {
                _guardStatusMessages.value = _guardStatusMessages.value + (guardKey to IdempotencyStatusMessages.SLOW_CHECKING)
            }
        }

        try {
            persistRecord(initialEntity)

            val cluster = clusterCodeProvider()

            val adapter = backendAdapter
            // Atomic server claim before executing business write when adapter is active and online
            if (requiresServerClaim && adapter != null && isOnlineProvider()) {
                when (val claim = adapter.claimOrGetRequest(cluster, initialEntity)) {
                    is IdempotencyClaimOutcome.Claimed -> Unit
                    is IdempotencyClaimOutcome.DuplicateCompleted -> {
                        val completedAt = System.currentTimeMillis()
                        persistRecord(
                            initialEntity.copy(
                                requestStatus = IdempotencyRequestStatus.COMPLETED.code,
                                updatedAt = completedAt,
                                completionTime = completedAt,
                                resultReference = claim.existingResultReference
                            )
                        )
                        _guardStatusMessages.value = _guardStatusMessages.value + (guardKey to IdempotencyStatusMessages.ALREADY_RECORDED)
                        return GuardedExecutionResult(
                            success = true,
                            blockedAsDuplicate = true,
                            alreadyRecorded = true,
                            idempotencyKey = idempotencyKey,
                            status = IdempotencyRequestStatus.COMPLETED,
                            message = IdempotencyStatusMessages.ALREADY_RECORDED
                        )
                    }
                    is IdempotencyClaimOutcome.DuplicateInProgress -> {
                        val updatedAt = System.currentTimeMillis()
                        persistRecord(
                            initialEntity.copy(
                                requestStatus = IdempotencyRequestStatus.PENDING_CONFIRMATION.code,
                                updatedAt = updatedAt,
                                errorDetails = claim.message
                            )
                        )
                        _guardStatusMessages.value = _guardStatusMessages.value + (guardKey to IdempotencyStatusMessages.PENDING_DO_NOT_REENTER)
                        return GuardedExecutionResult(
                            success = false,
                            blockedAsDuplicate = true,
                            pendingConfirmation = true,
                            idempotencyKey = idempotencyKey,
                            status = IdempotencyRequestStatus.PENDING_CONFIRMATION,
                            message = IdempotencyStatusMessages.PENDING_DO_NOT_REENTER
                        )
                    }
                    is IdempotencyClaimOutcome.NetworkUncertain -> {
                        val updatedAt = System.currentTimeMillis()
                        persistRecord(
                            initialEntity.copy(
                                requestStatus = IdempotencyRequestStatus.PENDING_CONFIRMATION.code,
                                updatedAt = updatedAt,
                                errorDetails = claim.reason
                            )
                        )
                        _guardStatusMessages.value = _guardStatusMessages.value + (guardKey to IdempotencyStatusMessages.PENDING_DO_NOT_REENTER)
                        return GuardedExecutionResult(
                            success = false,
                            pendingConfirmation = true,
                            idempotencyKey = idempotencyKey,
                            status = IdempotencyRequestStatus.PENDING_CONFIRMATION,
                            message = IdempotencyStatusMessages.PENDING_DO_NOT_REENTER
                        )
                    }
                    is IdempotencyClaimOutcome.Rejected -> {
                        val updatedAt = System.currentTimeMillis()
                        persistRecord(
                            initialEntity.copy(
                                requestStatus = IdempotencyRequestStatus.FAILED.code,
                                updatedAt = updatedAt,
                                errorDetails = claim.reason
                            )
                        )
                        _guardStatusMessages.value = _guardStatusMessages.value + (guardKey to claim.reason)
                        return GuardedExecutionResult(
                            success = false,
                            pendingConfirmation = false,
                            idempotencyKey = idempotencyKey,
                            status = IdempotencyRequestStatus.FAILED,
                            message = claim.reason,
                            error = SecurityException(claim.reason)
                        )
                    }
                }
            }

            // Execute actual operation
            val outcome = block(idempotencyKey)
            return if (outcome.isSuccess) {
                val (resultRef, payload) = outcome.getOrThrow()
                val completedAt = System.currentTimeMillis()
                persistRecord(
                    initialEntity.copy(
                        requestStatus = IdempotencyRequestStatus.COMPLETED.code,
                        updatedAt = completedAt,
                        completionTime = completedAt,
                        resultReference = resultRef,
                        errorDetails = ""
                    )
                )
                if (requiresServerClaim && adapter != null && isOnlineProvider()) {
                    runCatching { adapter.markCompleted(cluster, idempotencyKey, resultRef, operationType.code) }
                }
                _guardStatusMessages.value = _guardStatusMessages.value + (guardKey to IdempotencyStatusMessages.COMPLETED)
                GuardedExecutionResult(
                    success = true,
                    idempotencyKey = idempotencyKey,
                    status = IdempotencyRequestStatus.COMPLETED,
                    message = IdempotencyStatusMessages.COMPLETED,
                    data = payload
                )
            } else {
                val err = outcome.exceptionOrNull() ?: IllegalStateException(IdempotencyStatusMessages.FAILED)
                handleExecutionThrowable(guardKey, cluster, initialEntity, err)
            }
        } catch (ce: CancellationException) {
            // Interrupted mid-flight (e.g., screen closed / app backgrounded) -> keep PENDING_CONFIRMATION!
            val updatedAt = System.currentTimeMillis()
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    persistRecord(
                        initialEntity.copy(
                            requestStatus = IdempotencyRequestStatus.PENDING_CONFIRMATION.code,
                            updatedAt = updatedAt,
                            errorDetails = "انقطعت الجلسة أثناء المعالجة — يرجى التحقق قبل إعادة الإدخال"
                        )
                    )
                }
            }
            throw ce
        } catch (t: Throwable) {
            return handleExecutionThrowable(guardKey, clusterCodeProvider(), initialEntity, t)
        } finally {
            slowMonitorJob?.cancel()
            releaseSynchronousLock(lock)
        }
    }

    private suspend fun <T> handleExecutionThrowable(
        guardKey: String,
        clusterCode: String,
        initialEntity: IdempotencyRequestEntity,
        err: Throwable
    ): GuardedExecutionResult<T> {
        val now = System.currentTimeMillis()
        val uncertain = isNetworkOrAmbiguousException(err)
        val adapter = backendAdapter
        return if (uncertain) {
            val msg = IdempotencyStatusMessages.PENDING_DO_NOT_REENTER
            persistRecord(
                initialEntity.copy(
                    requestStatus = IdempotencyRequestStatus.PENDING_CONFIRMATION.code,
                    updatedAt = now,
                    errorDetails = err.message ?: msg
                )
            )
            if (adapter != null && isOnlineProvider()) {
                runCatching {
                    adapter.markPendingOrFailed(
                        clusterCode,
                        initialEntity.idempotencyKey,
                        IdempotencyRequestStatus.PENDING_CONFIRMATION,
                        err.message ?: msg,
                        initialEntity.operationType
                    )
                }
            }
            _guardStatusMessages.value = _guardStatusMessages.value + (guardKey to msg)
            GuardedExecutionResult(
                success = false,
                pendingConfirmation = true,
                idempotencyKey = initialEntity.idempotencyKey,
                status = IdempotencyRequestStatus.PENDING_CONFIRMATION,
                message = msg,
                error = err
            )
        } else {
            val detail = err.message?.takeIf { it.isNotBlank() } ?: IdempotencyStatusMessages.FAILED
            persistRecord(
                initialEntity.copy(
                    requestStatus = IdempotencyRequestStatus.FAILED.code,
                    updatedAt = now,
                    errorDetails = detail
                )
            )
            if (adapter != null && isOnlineProvider()) {
                runCatching {
                    adapter.markPendingOrFailed(
                        clusterCode,
                        initialEntity.idempotencyKey,
                        IdempotencyRequestStatus.FAILED,
                        detail,
                        initialEntity.operationType
                    )
                }
            }
            _guardStatusMessages.value = _guardStatusMessages.value + (guardKey to IdempotencyStatusMessages.FAILED)
            GuardedExecutionResult(
                success = false,
                pendingConfirmation = false,
                idempotencyKey = initialEntity.idempotencyKey,
                status = IdempotencyRequestStatus.FAILED,
                message = detail,
                error = err
            )
        }
    }

    /**
     * Verifies an uncertain/pending operation against Firestore before allowing any retry.
     */
    suspend fun verifyOperationStatus(idempotencyKey: String): OperationVerificationResult {
        val local = getLocalRecord(idempotencyKey)
            ?: return OperationVerificationResult(
                idempotencyKey = idempotencyKey,
                resolvedStatus = IdempotencyRequestStatus.FAILED,
                message = "لم يتم العثور على سجل العملية محلياً.",
                safeToRetry = true
            )

        val currentStatus = IdempotencyRequestStatus.fromCode(local.requestStatus)
        if (currentStatus == IdempotencyRequestStatus.COMPLETED) {
            return OperationVerificationResult(
                idempotencyKey = idempotencyKey,
                resolvedStatus = IdempotencyRequestStatus.COMPLETED,
                message = IdempotencyStatusMessages.ALREADY_RECORDED,
                safeToRetry = false,
                resultReference = local.resultReference
            )
        }

        val cluster = clusterCodeProvider()
        val adapter = backendAdapter
        if (!isOnlineProvider() || adapter == null) {
            return OperationVerificationResult(
                idempotencyKey = idempotencyKey,
                resolvedStatus = IdempotencyRequestStatus.PENDING_CONFIRMATION,
                message = IdempotencyStatusMessages.PENDING_DO_NOT_REENTER,
                safeToRetry = false
            )
        }

        val remote = runCatching { adapter.fetchRequest(cluster, idempotencyKey) }.getOrNull()
        val docExists = if (local.targetCollection.isNotBlank() && local.documentReference.isNotBlank()) {
            runCatching {
                adapter.checkClusterDocumentExists(cluster, local.targetCollection, local.documentReference)
            }.getOrDefault(false)
        } else {
            false
        }

        val now = System.currentTimeMillis()
        if (remote?.requestStatus == IdempotencyRequestStatus.COMPLETED.code || docExists) {
            val resolvedRef = remote?.resultReference?.takeIf { it.isNotBlank() } ?: local.documentReference
            persistRecord(
                local.copy(
                    requestStatus = IdempotencyRequestStatus.COMPLETED.code,
                    updatedAt = now,
                    completionTime = remote?.completionTime ?: now,
                    resultReference = resolvedRef,
                    errorDetails = ""
                )
            )
            return OperationVerificationResult(
                idempotencyKey = idempotencyKey,
                resolvedStatus = IdempotencyRequestStatus.COMPLETED,
                message = IdempotencyStatusMessages.ALREADY_RECORDED,
                safeToRetry = false,
                resultReference = resolvedRef
            )
        }

        if (remote == null && !docExists) {
            persistRecord(
                local.copy(
                    requestStatus = IdempotencyRequestStatus.CANCELLED.code,
                    updatedAt = now,
                    errorDetails = "تم التحقق: لم تُنفذ العملية على الخادم ويمكن إعادة المحاولة بأمان."
                )
            )
            return OperationVerificationResult(
                idempotencyKey = idempotencyKey,
                resolvedStatus = IdempotencyRequestStatus.CANCELLED,
                message = "تم التحقق من الخادم: لم يتم تنفيذ العملية بعد ويمكن إعادة المحاولة بأمان.",
                safeToRetry = true
            )
        }

        val remoteStatus = IdempotencyRequestStatus.fromCode(remote?.requestStatus)
        if (remoteStatus == IdempotencyRequestStatus.FAILED || remoteStatus == IdempotencyRequestStatus.CANCELLED) {
            persistRecord(
                local.copy(
                    requestStatus = remoteStatus.code,
                    updatedAt = now,
                    errorDetails = remote?.errorDetails.orEmpty()
                )
            )
            return OperationVerificationResult(
                idempotencyKey = idempotencyKey,
                resolvedStatus = remoteStatus,
                message = remote?.errorDetails?.ifBlank { IdempotencyStatusMessages.FAILED } ?: IdempotencyStatusMessages.FAILED,
                safeToRetry = true
            )
        }

        // Still uncertain or active remotely
        persistRecord(
            local.copy(
                requestStatus = IdempotencyRequestStatus.PENDING_CONFIRMATION.code,
                updatedAt = now
            )
        )
        return OperationVerificationResult(
            idempotencyKey = idempotencyKey,
            resolvedStatus = IdempotencyRequestStatus.PENDING_CONFIRMATION,
            message = IdempotencyStatusMessages.PENDING_DO_NOT_REENTER,
            safeToRetry = false
        )
    }

    /**
     * Startup and Reconnect Recovery: scans unresolved local operations (PROCESSING / PENDING_CONFIRMATION)
     * and reconciles them with the server so interrupted requests are never blindly re-sent.
     */
    suspend fun recoverPendingOperations(): List<OperationVerificationResult> {
        val unresolved = runCatching { idempotencyDao?.getUnresolvedDirect() }.getOrNull()
            ?: inMemoryStore.getUnresolved()
        val now = System.currentTimeMillis()
        val results = mutableListOf<OperationVerificationResult>()
        for (item in unresolved) {
            if (item.requestStatus == IdempotencyRequestStatus.PROCESSING.code &&
                !activeLocksByGuard.containsKey(item.guardKey) &&
                now - item.updatedAt >= staleProcessingTimeoutMs
            ) {
                persistRecord(
                    item.copy(
                        requestStatus = IdempotencyRequestStatus.PENDING_CONFIRMATION.code,
                        updatedAt = now,
                        errorDetails = item.errorDetails.ifBlank { IdempotencyStatusMessages.PENDING_DO_NOT_REENTER }
                    )
                )
            }
            if (isOnlineProvider() && backendAdapter != null) {
                results += verifyOperationStatus(item.idempotencyKey)
            }
        }
        publishRecordsSnapshot()
        return results
    }

    suspend fun dismissCancelledOrFailedOperation(idempotencyKey: String) {
        val existing = getLocalRecord(idempotencyKey) ?: return
        if (existing.requestStatus == IdempotencyRequestStatus.COMPLETED.code) return
        persistRecord(
            existing.copy(
                requestStatus = IdempotencyRequestStatus.CANCELLED.code,
                updatedAt = System.currentTimeMillis(),
                errorDetails = "تمت مراجعة الحالة وتأكيد عدم التكرار"
            )
        )
    }

    companion object {
        fun isNetworkOrAmbiguousException(err: Throwable): Boolean {
            var cur: Throwable? = err
            while (cur != null) {
                if (
                    cur is SocketTimeoutException ||
                    cur is UnknownHostException ||
                    cur is TimeoutException ||
                    cur is IOException
                ) {
                    return true
                }
                val msg = cur.message?.lowercase().orEmpty()
                if (
                    msg.contains("timeout") ||
                    msg.contains("timed out") ||
                    msg.contains("unavailable") ||
                    msg.contains("network") ||
                    msg.contains("unable to resolve host") ||
                    msg.contains("failed to connect") ||
                    msg.contains("connection reset") ||
                    msg.contains("deadline exceeded") ||
                    msg.contains("offline")
                ) {
                    return true
                }
                cur = cur.cause
            }
            return false
        }
    }
}
