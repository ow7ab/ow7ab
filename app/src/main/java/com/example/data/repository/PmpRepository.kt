package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PmpRepository(
    private val dao: PmpDao,
    private val database: PmpDatabase? = null
) {

    fun getDao(): PmpDao = dao
    fun getLabDao(): LabDao? = database?.labDao()
    fun getErpInventoryDao(): ErpInventoryDao? = database?.erpInventoryDao()
    fun getErpAccountingDao(): ErpAccountingDao? = database?.erpAccountingDao()
    val inventoryEngine: com.example.domain.inventory.InventoryMovementEngine? by lazy {
        database?.let { com.example.domain.inventory.InventoryMovementEngine(it) }
    }
    val phase3Engine: com.example.domain.inventory.ProductionAndProcurementEngine? by lazy {
        val db = database ?: return@lazy null
        val inv = inventoryEngine ?: return@lazy null
        com.example.domain.inventory.ProductionAndProcurementEngine(db, inv)
    }
    val accountingEngine: com.example.domain.accounting.AccountingLedgerEngine? by lazy {
        database?.let { com.example.domain.accounting.AccountingLedgerEngine(it) }
    }

    suspend fun <T> runInTransaction(
        operationName: String = "ROOM_TRANSACTION",
        entityType: String = "general",
        details: String = "",
        block: suspend () -> T
    ): T {
        return com.example.util.RoomTransactionLogger.traceTransaction(
            operation = operationName,
            entityType = entityType,
            details = details,
            dao = dao
        ) {
            val db = database
            if (db != null) {
                db.withTransaction { block() }
            } else {
                block()
            }
        }
    }

    companion object {
        fun hashSecret(secret: String): String {
            val salted = "PMP_SECURE_V2:$secret:PMP_HASH_SALT"
            val digest = MessageDigest.getInstance("SHA-256").digest(salted.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /**
         * حساب السعر التناسبي للكرتونة بناءً على معايير التعبئة المعتمدة (PackagingRuleSpec: وزن العبوة × عدد العبوات في الكرتونة)
         * بدلاً من نسخ سعر عبوة أصغر أو أكبر بشكل أعمى عند اشتقاق وزن جديد لنفس اللون.
         */
        fun calculateWeightAdjustedCartonPrice(
            targetWeight: String,
            referenceWeight: String,
            referenceCartonPrice: Double
        ): Double {
            if (referenceCartonPrice <= 0.0) return 0.0
            val cleanTarget = com.example.util.InventoryWeightManager.normalizeWeightValue(targetWeight).ifBlank { targetWeight.trim() }
            val cleanRef = com.example.util.InventoryWeightManager.normalizeWeightValue(referenceWeight).ifBlank { referenceWeight.trim() }
            if (cleanTarget == cleanRef) return referenceCartonPrice

            val targetSpec = com.example.util.ErpLifecycleEngine.resolvePackagingRule(cleanTarget)
            val refSpec = com.example.util.ErpLifecycleEngine.resolvePackagingRule(cleanRef)

            val targetCartonGrams = (targetSpec.weightGrams * targetSpec.cansPerCarton).coerceAtLeast(250).toDouble()
            val refCartonGrams = (refSpec.weightGrams * refSpec.cansPerCarton).coerceAtLeast(250).toDouble()

            val ratio = targetCartonGrams / refCartonGrams
            val rawScaled = referenceCartonPrice * ratio
            return if (rawScaled >= 1000.0) {
                (Math.round(rawScaled / 500.0) * 500.0).coerceAtLeast(500.0)
            } else {
                (Math.round(rawScaled * 100.0) / 100.0).coerceAtLeast(1.0)
            }
        }
    }

    private fun currentDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    // --- Cashboxes & Movements ---
    fun getCashboxes(): Flow<List<CashboxEntity>> = dao.getAllCashboxes()
    fun getMovements(): Flow<List<MovementEntity>> = dao.getAllMovements()
    fun getTransfers(): Flow<List<TransferEntity>> = dao.getAllTransfers()
    fun getDailyClosings(): Flow<List<DailyClosingEntity>> = dao.getAllDailyClosings()
    fun getAggregatedCashboxBalances(): Flow<List<CashboxBalanceTuple>> = dao.getAggregatedCashboxBalances()
    fun getAggregatedCurrencyBalances(): Flow<List<CurrencyBalanceTuple>> = dao.getAggregatedCurrencyBalances()
    fun getAggregatedProductStockBalances(): Flow<List<ProductStockBalanceTuple>> = dao.getAggregatedProductStockBalances()

    // --- Phase 2/3 ERP Reactive Streams ---
    fun getErpMovements(): Flow<List<InventoryMovementEntity>> =
        getErpInventoryDao()?.getAllInventoryMovements() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    fun getErpStockBalances(): Flow<List<WarehouseStockBalanceEntity>> =
        getErpInventoryDao()?.getAllStockBalances() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    fun getErpTransfers(): Flow<List<WarehouseTransferEntity>> =
        getErpInventoryDao()?.getAllWarehouseTransfers() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    fun getPackagingRulesDb(): Flow<List<PackagingRuleEntity>> =
        getErpInventoryDao()?.getAllActivePackagingRules() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    fun getPurchaseReceipts(): Flow<List<PurchaseReceiptEntity>> =
        getErpInventoryDao()?.getAllPurchaseReceipts() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    fun getProductionOrdersDb(): Flow<List<ProductionOrderEntity>> =
        getErpInventoryDao()?.getAllProductionOrders() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    fun getQcInspections(): Flow<List<QcInspectionEntity>> =
        getErpInventoryDao()?.getAllQcInspections() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    // --- Phase 4 Double-Entry General Ledger Reactive Streams ---
    fun getAccountingAccounts(): Flow<List<ChartAccountEntity>> =
        getErpAccountingDao()?.getAllAccounts() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    fun getJournalEntries(): Flow<List<JournalEntryEntity>> =
        getErpAccountingDao()?.getAllJournalEntries() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    fun getJournalLines(): Flow<List<JournalLineEntity>> =
        getErpAccountingDao()?.getAllJournalLines() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun addCashbox(name: String, user: String): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return 0L
        DeletedRecordsRegistry.unregisterDeleted("cashboxes", trimmed)
        val existing = dao.getCashboxesDirect().firstOrNull {
            it.name.trim().equals(trimmed, ignoreCase = true)
        }
        if (existing != null) {
            DeletedRecordsRegistry.unregisterDeleted("cashboxes", existing.id)
            return existing.id
        }
        val newId = dao.insertCashbox(CashboxEntity(name = trimmed))
        DeletedRecordsRegistry.unregisterDeleted("cashboxes", newId)
        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "ADD_CASHBOX",
                detail = "إضافة خزنة معتمدة: $trimmed (#$newId)"
            )
        )
        return newId
    }

    suspend fun updateCashbox(id: Long, name: String, user: String): Result<Unit> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(Exception("اسم الخزنة لا يمكن أن يكون فارغاً"))
        val existing = dao.getCashboxesDirect().firstOrNull { it.id == id }
        if (existing != null && !existing.name.trim().equals(trimmed, ignoreCase = true)) {
            DeletedRecordsRegistry.registerDeleted(
                collection = "cashboxes",
                entityId = existing.name.trim(),
                docId = DeletedRecordsRegistry.cashboxDocId(existing.name, existing.id),
                actor = user
            )
        }
        DeletedRecordsRegistry.unregisterDeleted("cashboxes", trimmed)
        DeletedRecordsRegistry.unregisterDeleted("cashboxes", id)
        dao.updateCashbox(CashboxEntity(id = id, name = trimmed))
        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "UPDATE_CASHBOX",
                detail = "تعديل اسم الخزنة #$id إلى: $trimmed"
            )
        )
        return Result.success(Unit)
    }

    /**
     * يحذف أو يؤرشف الخزنة من قائمة الخزن النشطة ويسجل شاهد حذف سحابي (Tombstone)
     * مع الحفاظ الكامل 100% على كافة السجلات والحركات المالية التاريخية المرتبطة بها.
     * يعيد Boolean يوضح ما إذا كانت الخزنة تحتوي على حركات مالية تاريخية تم الاحتفاظ بها بأمان.
     */
    suspend fun deleteCashbox(id: Long, name: String = "", user: String): Result<Boolean> = runInTransaction(
        operationName = "DELETE_OR_ARCHIVE_CASHBOX",
        entityType = "cashboxes",
        details = "حذف/أرشفة الخزنة #$id ($name)"
    ) {
        val allBoxes = dao.getCashboxesDirect()
        val resolvedName = name.trim().ifBlank {
            allBoxes.firstOrNull { it.id == id }?.name?.trim().orEmpty()
        }

        // Check financial relationships (NEVER delete financial movements or historical records!)
        val hasMovements = resolvedName.isNotBlank() && dao.getMovementsDirect().any {
            it.cashbox.trim().equals(resolvedName, ignoreCase = true)
        }
        val hasTransfers = resolvedName.isNotBlank() && dao.getTransfersDirect().any {
            it.sourceCashbox.trim().equals(resolvedName, ignoreCase = true) ||
                it.destinationCashbox.trim().equals(resolvedName, ignoreCase = true)
        }
        val hasPayments = resolvedName.isNotBlank() && dao.getInvoicePaymentsDirect().any {
            it.cashbox.trim().equals(resolvedName, ignoreCase = true)
        }
        val hasFinancialHistory = hasMovements || hasTransfers || hasPayments

        // Register tombstones by ID, name, and deterministic docId so it never resurrects on sync
        if (id > 0) {
            DeletedRecordsRegistry.registerDeleted("cashboxes", id, "BOX-$id", user)
        }
        if (resolvedName.isNotBlank()) {
            val docId = DeletedRecordsRegistry.cashboxDocId(resolvedName, id)
            DeletedRecordsRegistry.registerDeleted("cashboxes", resolvedName, docId, user)
        }

        // Delete active cashbox row(s) matching id or name (removing any duplicates as well)
        dao.deleteCashbox(id)
        if (resolvedName.isNotBlank()) {
            allBoxes.filter { it.name.trim().equals(resolvedName, ignoreCase = true) }
                .forEach { dup -> dao.deleteCashbox(dup.id) }
        }

        val auditAction = if (hasFinancialHistory) "ARCHIVE_CASHBOX_WITH_HISTORY" else "DELETE_CASHBOX"
        val auditDetail = if (hasFinancialHistory) {
            "أرشفة وإلغاء تفعيل الخزنة ($resolvedName #$id) من الخزن النشطة ومزامنة الحذف مع الاحتفاظ الكامل بجميع الحركات المالية التاريخية"
        } else {
            "حذف الخزنة ($resolvedName #$id) نهائياً ومزامنة الحذف عبر جميع الأجهزة"
        }
        dao.insertAuditLog(AuditLogEntity(username = user, action = auditAction, detail = auditDetail))
        Result.success(hasFinancialHistory)
    }

    suspend fun getMovementsDirect(): List<MovementEntity> = com.example.util.RoomTransactionLogger.traceFetch(
        queryName = "FETCH_ALL_MOVEMENTS",
        entityType = "movements"
    ) {
        dao.getMovementsDirect()
    }

    suspend fun recordMovement(
        cashbox: String,
        currency: String,
        direction: String,
        amount: Double,
        description: String,
        reference: String,
        user: String,
        date: String = currentDateString()
    ): Result<Long> = runInTransaction(
        operationName = "RECORD_MOVEMENT",
        entityType = "movements",
        details = "$direction $amount $currency في $cashbox | مرجع: $reference"
    ) {
        if (amount <= 0) return@runInTransaction Result.failure(Exception("المبلغ يجب أن يكون أكبر من صفر"))
        val safeRef = reference.trim().ifBlank { "REC-${System.currentTimeMillis() % 1000000}" }
        val safeDesc = description.trim().ifBlank { "حركة $direction نقدية في $cashbox" }

        val cleanCashbox = cashbox.trim().ifBlank { "خزنة السودان" }
        val cleanCurrency = com.example.util.TreasuryBalanceNormalizer.resolveCanonicalCurrency(currency, cleanCashbox)
        val cleanDirection = com.example.util.TreasuryBalanceNormalizer.canonicalDirection(direction)
        val cleanAmount = kotlin.math.abs(amount)

        val nowMs = System.currentTimeMillis()
        val freshUuid = java.util.UUID.randomUUID().toString()
        val uniqueDocId = "MOV-${nowMs}-${freshUuid.take(8)}"
        val movementEntity = MovementEntity(
            txDate = date,
            cashbox = cleanCashbox,
            currency = cleanCurrency,
            direction = cleanDirection,
            amount = cleanAmount,
            description = safeDesc,
            reference = safeRef,
            createdBy = user,
            createdAt = nowMs,
            cloudDocId = uniqueDocId,
            syncUuid = freshUuid,
            updatedAt = nowMs
        )
        val id = dao.insertMovement(movementEntity)
        DeletedRecordsRegistry.unregisterDeleted("movements", uniqueDocId)
        DeletedRecordsRegistry.unregisterDeleted("movements", safeRef)
        DeletedRecordsRegistry.unregisterDeleted("movements", freshUuid)
        accountingEngine?.postTreasuryMovementJournalInternal(movementEntity.copy(id = id))
        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "CREATE_MOVEMENT",
                detail = "حركة #$id | $cleanDirection $cleanAmount $cleanCurrency في $cleanCashbox | مرجع: $safeRef"
            )
        )
        Result.success(id)
    }

    suspend fun deleteMovement(id: Long, user: String): Result<MovementEntity?> = runInTransaction(
        operationName = "REVERSE_MOVEMENT_LEDGER",
        entityType = "movements",
        details = "عكس الحركة المالية #$id بقيد عكسي معتمد (سجل غير قابل للحذف)"
    ) {
        val existing = dao.getMovementByIdDirect(id)
            ?: return@runInTransaction Result.success(null)
        if (com.example.util.TreasuryBalanceNormalizer.isCancelledOrReversedStatus(existing.status)) {
            return@runInTransaction Result.failure(Exception("تم عكس هذه الحركة المالية مسبقاً في دفتر الأستاذ."))
        }
        val cleanRef = existing.reference.trim().ifBlank { "MOV-$id" }
        val revRef = "REV-$cleanRef"
        val now = System.currentTimeMillis()
        val reason = "قيد عكسي معتمد بواسطة $user"
        val reversedEntity = existing.copy(
            status = "reversed",
            reversedByRef = revRef,
            reversalReason = reason,
            updatedAt = now,
            syncStatus = "PENDING"
        )
        dao.updateMovement(reversedEntity)

        val oppositeDirection = if (com.example.util.TreasuryBalanceNormalizer.isMovementInflow(existing.direction)) {
            "منصرف"
        } else {
            "وارد"
        }
        accountingEngine?.postTreasuryMovementJournalInternal(
            existing.copy(
                direction = oppositeDirection,
                reference = revRef,
                description = "قيد عكسي لحركة $cleanRef: ${existing.description} ($reason)",
                createdBy = user
            )
        )

        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "REVERSE_MOVEMENT",
                detail = "عكس الحركة المالية #$id ($cleanRef) بقيد عكسي $revRef دون حذف السجل الأصلي"
            )
        )
        Result.success(reversedEntity)
    }

    suspend fun recordCustomerPayment(
        customerId: Long,
        customerName: String,
        amount: Double,
        currency: String,
        cashbox: String,
        note: String,
        user: String,
        date: String = currentDateString()
    ): Result<Long> = runInTransaction(
        operationName = "RECORD_CUSTOMER_PAYMENT",
        entityType = "invoice_payments",
        details = "تحصيل $amount $currency من $customerName في $cashbox"
    ) {
        if (amount <= 0) return@runInTransaction Result.failure(Exception("المبلغ يجب أن يكون أكبر من صفر"))

        val cleanCurrency = currency.trim().uppercase().ifBlank { "SDG" }
        val activeInvoices = dao.getInvoicesDirect()
            .filter { it.customerId == customerId && it.status == "active" }
            .sortedWith(
                compareByDescending<InvoiceEntity> { it.currency.equals(cleanCurrency, ignoreCase = true) }
                    .thenBy { it.id }
            )

        val allPayments = dao.getInvoicePaymentsDirect()

        var remainingToApply = amount
        var lastPaymentId = 0L

        for (inv in activeInvoices) {
            if (remainingToApply <= 0) break
            val paidOnInv = allPayments.filter { it.invoiceId == inv.id && it.status == "active" }.sumOf { it.amount }
            val invRemaining = (inv.total - paidOnInv).coerceAtLeast(0.0)
            if (invRemaining > 0) {
                val payForThisInv = remainingToApply.coerceAtMost(invRemaining)
                val baseNote = if (note.isNotBlank()) "$note (فاتورة ${inv.invoiceNo})" else "سداد دفعة لفاتورة ${inv.invoiceNo}"
                val taggedNote = if (baseNote.contains("[$cleanCurrency]")) baseNote else "$baseNote [$cleanCurrency]"
                val payId = dao.insertInvoicePayment(
                    InvoicePaymentEntity(
                        invoiceId = inv.id,
                        payDate = date,
                        cashbox = cashbox,
                        amount = payForThisInv,
                        note = taggedNote,
                        createdBy = user
                    )
                )
                lastPaymentId = payId
                remainingToApply -= payForThisInv
            }
        }

        if (remainingToApply > 0 && activeInvoices.isNotEmpty()) {
            val primaryInv = activeInvoices.first()
            val baseNote = if (note.isNotBlank()) "$note (دفعة بحساب العميل)" else "دفعة إضافية بحساب العميل"
            val taggedNote = if (baseNote.contains("[$cleanCurrency]")) baseNote else "$baseNote [$cleanCurrency]"
            val payId = dao.insertInvoicePayment(
                InvoicePaymentEntity(
                    invoiceId = primaryInv.id,
                    payDate = date,
                    cashbox = cashbox,
                    amount = remainingToApply,
                    note = taggedNote,
                    createdBy = user
                )
            )
            lastPaymentId = payId
        }

        val nowMs = System.currentTimeMillis()
        val ref = "PAY-CUST-$customerId-${nowMs % 100000}"
        val freshUuid = java.util.UUID.randomUUID().toString()
        val uniqueDocId = "MOV-${nowMs}-${freshUuid.take(8)}"
        val cleanCashbox = cashbox.trim().ifBlank { "خزنة السودان" }
        val canonicalCurrency = com.example.util.TreasuryBalanceNormalizer.resolveCanonicalCurrency(currency, cleanCashbox)
        val movId = dao.insertMovement(
            MovementEntity(
                txDate = date,
                cashbox = cleanCashbox,
                currency = canonicalCurrency,
                direction = "وارد",
                amount = kotlin.math.abs(amount),
                description = "تحصيل وسداد دفعة من العميل: $customerName ${if (note.isNotBlank()) "• $note" else ""}",
                reference = ref,
                createdBy = user,
                createdAt = nowMs,
                cloudDocId = uniqueDocId,
                syncUuid = freshUuid,
                updatedAt = nowMs
            )
        )
        DeletedRecordsRegistry.unregisterDeleted("movements", uniqueDocId)
        DeletedRecordsRegistry.unregisterDeleted("movements", ref)
        DeletedRecordsRegistry.unregisterDeleted("movements", freshUuid)
        accountingEngine?.postCustomerPaymentJournalInternal(
            paymentId = if (lastPaymentId > 0L) lastPaymentId else movId,
            referenceDocNo = ref,
            payDate = date,
            customerName = customerName,
            cashbox = cashbox,
            currency = currency,
            amount = amount,
            actor = user
        )

        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "CUSTOMER_PAYMENT",
                detail = "تحصيل دفعة $amount $currency من $customerName في $cashbox"
            )
        )

        Result.success(movId)
    }

    suspend fun recordTransfer(
        sourceCashbox: String,
        destinationCashbox: String,
        sourceCurrency: String,
        destinationCurrency: String,
        sourceAmount: Double,
        rate: Double,
        description: String,
        reference: String,
        user: String,
        halfaInvoiceId: Long? = null,
        halfaAmount: Double? = null,
        isInvertedRate: Boolean = false,
        date: String = currentDateString()
    ): Result<Long> = runInTransaction(
        operationName = "RECORD_TRANSFER",
        entityType = "transfers",
        details = "تحويل $sourceAmount $sourceCurrency من $sourceCashbox إلى $destinationCashbox | مرجع: $reference"
    ) {
        if (sourceAmount <= 0) return@runInTransaction Result.failure(Exception("مبلغ التحويل يجب أن يكون أكبر من صفر"))
        if (rate <= 0) return@runInTransaction Result.failure(Exception("سعر الصرف يجب أن يكون أكبر من صفر"))
        if (sourceCashbox == destinationCashbox && sourceCurrency == destinationCurrency) {
            return@runInTransaction Result.failure(Exception("لا يمكن التحويل لنفس الخزنة وبنفس العملة"))
        }

        val destinationAmount = if (isInvertedRate) {
            if (rate > 0) sourceAmount / rate else 0.0
        } else {
            sourceAmount * rate
        }
        val rateDirectionText = if (isInvertedRate) "معكوس (قسمة / $rate)" else "مباشر (ضرب × $rate)"
        val fullDescription = if (description.isNotBlank()) "$description [اتجاه الصرف: $rateDirectionText]" else "[اتجاه الصرف: $rateDirectionText]"

        // 1. Record Outgoing Movement on Source
        val cleanSrcBox = sourceCashbox.trim().ifBlank { "خزنة السودان" }
        val cleanDstBox = destinationCashbox.trim().ifBlank { "خزنة مصر" }
        val cleanSrcCur = com.example.util.TreasuryBalanceNormalizer.resolveCanonicalCurrency(sourceCurrency, cleanSrcBox)
        val cleanDstCur = com.example.util.TreasuryBalanceNormalizer.resolveCanonicalCurrency(destinationCurrency, cleanDstBox)
        val nowMs = System.currentTimeMillis()
        val outUuid = java.util.UUID.randomUUID().toString()
        val outDocId = "MOV-${nowMs}-${outUuid.take(8)}"
        dao.insertMovement(
            MovementEntity(
                txDate = date,
                cashbox = cleanSrcBox,
                currency = cleanSrcCur,
                direction = "منصرف",
                amount = kotlin.math.abs(sourceAmount),
                description = "تحويل صادر $reference إلى $cleanDstBox: $fullDescription",
                reference = "TRF:$reference:OUT",
                createdBy = user,
                createdAt = nowMs,
                cloudDocId = outDocId,
                syncUuid = outUuid,
                updatedAt = nowMs
            )
        )

        // 2. Record Incoming Movement on Destination
        val inUuid = java.util.UUID.randomUUID().toString()
        val inDocId = "MOV-${nowMs + 1}-${inUuid.take(8)}"
        dao.insertMovement(
            MovementEntity(
                txDate = date,
                cashbox = cleanDstBox,
                currency = cleanDstCur,
                direction = "وارد",
                amount = kotlin.math.abs(destinationAmount),
                description = "تحويل وارد $reference من $cleanSrcBox: $fullDescription",
                reference = "TRF:$reference:IN",
                createdBy = user,
                createdAt = nowMs + 1,
                cloudDocId = inDocId,
                syncUuid = inUuid,
                updatedAt = nowMs + 1
            )
        )

        // 3. Record Transfer Log
        val transferEntity = TransferEntity(
            reference = reference.trim(),
            txDate = date,
            sourceCashbox = sourceCashbox,
            destinationCashbox = destinationCashbox,
            sourceCurrency = sourceCurrency,
            destinationCurrency = destinationCurrency,
            sourceAmount = sourceAmount,
            rate = rate,
            destinationAmount = destinationAmount,
            description = fullDescription.trim(),
            halfaInvoiceId = halfaInvoiceId,
            halfaAmount = halfaAmount,
            createdBy = user
        )
        val transferId = dao.insertTransfer(transferEntity)
        accountingEngine?.postTreasuryTransferJournalInternal(transferEntity.copy(id = transferId))

        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "CREATE_TRANSFER",
                detail = "$reference: $sourceCashbox $sourceAmount $sourceCurrency -> $destinationCashbox $destinationAmount $destinationCurrency"
            )
        )
        Result.success(transferId)
    }

    suspend fun recordDailyClosing(
        cashbox: String,
        currency: String,
        bookBalance: Double,
        countedBalance: Double,
        notes: String,
        user: String,
        date: String = currentDateString()
    ): Long {
        val diff = countedBalance - bookBalance
        val id = dao.insertDailyClosing(
            DailyClosingEntity(
                closeDate = date,
                cashbox = cashbox,
                currency = currency,
                bookBalance = bookBalance,
                countedBalance = countedBalance,
                difference = diff,
                notes = notes,
                preparedBy = user
            )
        )
        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "CLOSE_DAY",
                detail = "إقفال خزنة $cashbox ($currency) دفتري $bookBalance فعلي $countedBalance فرق $diff"
            )
        )
        return id
    }

    // --- Customers ---
    fun getCustomers(): Flow<List<CustomerEntity>> = dao.getAllCustomers()

    suspend fun addCustomer(name: String, phone: String, address: String, notes: String, user: String): Long {
        val id = dao.insertCustomer(
            CustomerEntity(
                name = name.trim(),
                phone = phone.trim(),
                address = address.trim(),
                notes = notes.trim()
            )
        )
        dao.insertAuditLog(AuditLogEntity(username = user, action = "CREATE_CUSTOMER", detail = name))
        return id
    }

    suspend fun deleteCustomer(id: Long, customerName: String, user: String): Result<Unit> = runInTransaction(
        operationName = "DELETE_CUSTOMER_SAFE",
        entityType = "customers",
        details = "محاولة حذف العميل: $customerName (#$id)"
    ) {
        val invoiceCount = dao.getCustomerInvoiceCountDirect(id)
        if (invoiceCount > 0) {
            return@runInTransaction Result.failure(
                IllegalStateException(
                    "يُمنع حذف العميل ($customerName) لوجود ($invoiceCount) فواتير ومعاملات مالية مسجلة باسمه في الدفاتر المحاسبية. يرجى استخدام تسوية الحساب أو إلغاء الفواتير بقيد عكسي للحفاظ على سلامة السجلات المالية."
                )
            )
        }
        DeletedRecordsRegistry.registerDeletedWithAliases(
            collection = "customers",
            aliases = listOfNotNull(id.toString(), "CUST-$id", customerName.trim().takeIf { it.isNotBlank() }),
            primaryEntityId = id.toString(),
            primaryDocId = "CUST-$id",
            actor = user
        )
        dao.deleteCustomer(id)
        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "DELETE_CUSTOMER",
                detail = "حذف العميل غير المرتبط بمعاملات مالية: $customerName (#$id)"
            )
        )
        Result.success(Unit)
    }

    suspend fun clearCustomerBalance(customerId: Long, customerName: String, user: String): Result<Unit> = runInTransaction(
        operationName = "SETTLE_CUSTOMER_BALANCE",
        entityType = "invoice_payments",
        details = "تسوية وإقفال المديونية المسجلة على العميل: $customerName (#$customerId)"
    ) {
        val activeInvoices = dao.getInvoicesDirect().filter { it.customerId == customerId && it.status == "active" }
        val allPayments = dao.getInvoicePaymentsDirect()
        val settlementDate = currentDateString()
        var settledInvoicesCount = 0
        var totalSettledAmount = 0.0

        for (inv in activeInvoices) {
            val paidOnInv = allPayments
                .filter { it.invoiceId == inv.id && it.status == "active" }
                .sumOf { it.amount }
            val remaining = (inv.total - paidOnInv).coerceAtLeast(0.0)
            if (remaining > 0.001) {
                val cleanCurrency = inv.currency.trim().uppercase().ifBlank { "SDG" }
                dao.insertInvoicePayment(
                    InvoicePaymentEntity(
                        invoiceId = inv.id,
                        payDate = settlementDate,
                        cashbox = "تسوية إدارية معتمدة",
                        amount = remaining,
                        note = "قيد تسوية وإقفال مديونية معتمد إدارياً للفاتورة ${inv.invoiceNo} [$cleanCurrency]",
                        createdBy = user,
                        status = "active"
                    )
                )
                settledInvoicesCount++
                totalSettledAmount += remaining
            }
        }

        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "SETTLE_CUSTOMER_BALANCE",
                detail = "تسوية وإقفال رصيد العميل ($customerName #$customerId) بقيد تسوية معتمد لعدد ($settledInvoicesCount) فواتير بإجمالي ($totalSettledAmount) دون حذف أي سجل مالي تاريخي"
            )
        )
        Result.success(Unit)
    }

    // --- Invoices ---
    fun getInvoices(): Flow<List<InvoiceEntity>> = dao.getAllInvoices()
    fun getAllInvoiceItems(): Flow<List<InvoiceItemEntity>> = dao.getAllInvoiceItems()
    fun getInvoicePayments(): Flow<List<InvoicePaymentEntity>> = dao.getAllInvoicePayments()
    fun getInvoiceItems(invoiceId: Long): Flow<List<InvoiceItemEntity>> = dao.getInvoiceItems(invoiceId)

    suspend fun createInvoice(
        customerId: Long,
        customerName: String,
        currency: String,
        items: List<InvoiceItemEntity>,
        discount: Double,
        notes: String,
        user: String,
        paymentCashbox: String? = null,
        paymentAmount: Double? = null,
        date: String = currentDateString()
    ): Result<Long> = runInTransaction {
        val cleanCustomerName = customerName.trim()
        if (cleanCustomerName.isBlank()) {
            return@runInTransaction Result.failure(Exception("يرجى تحديد اسم العميل"))
        }
        if (items.isEmpty()) {
            return@runInTransaction Result.failure(Exception("يجب إضافة بند واحد على الأقل"))
        }
        if (items.any { !it.quantity.isFinite() || it.quantity <= 0.0 || !it.unitPrice.isFinite() || it.unitPrice < 0.0 }) {
            return@runInTransaction Result.failure(Exception("كمية أو سعر أحد البنود غير صالح"))
        }
        if (!discount.isFinite() || discount < 0.0) {
            return@runInTransaction Result.failure(Exception("قيمة الخصم غير صالحة"))
        }
        if (paymentAmount != null && (!paymentAmount.isFinite() || paymentAmount < 0.0)) {
            return@runInTransaction Result.failure(Exception("مبلغ السداد الفوري غير صالح"))
        }

        val normalizedItems = items.map { item ->
            item.copy(lineTotal = item.quantity * item.unitPrice)
        }
        val subtotal = normalizedItems.sumOf { it.lineTotal }
        val finalDiscount = discount.coerceIn(0.0, subtotal)
        val total = (subtotal - finalDiscount).coerceAtLeast(0.0)

        val invoiceNumber = "INV-" + SimpleDateFormat("yyMMdd-HHmmss", Locale.getDefault()).format(Date())

        val allWarehousesById = dao.getWarehousesDirect().associateBy { it.id }
        for (item in normalizedItems) {
            if (item.warehouseId != null) {
                val wh = allWarehousesById[item.warehouseId]
                if (wh != null && wh.resolveWarehouseClass() != WarehouseClass.SALES) {
                    return@runInTransaction Result.failure(
                        Exception("لا يُسمح بالبيع المباشر من ${wh.resolveWarehouseClass().arabicLabel} (${wh.name})؛ يجب التحويل أولاً إلى مخزن المبيعات عبر مستند تحويل معتمد.")
                    )
                }
            }
        }

        val invoiceId = dao.insertInvoice(
            InvoiceEntity(
                invoiceNo = invoiceNumber,
                customerId = customerId,
                customerName = cleanCustomerName,
                invoiceDate = date,
                currency = currency,
                subtotal = subtotal,
                discount = finalDiscount,
                total = total,
                notes = notes.trim(),
                createdBy = user
            )
        )

        // Insert line items & record stock movements for products
        val updatedItems = normalizedItems.map { it.copy(invoiceId = invoiceId) }
        dao.insertInvoiceItems(updatedItems)

        val invDao = getErpInventoryDao()
        val eng = inventoryEngine
        for (item in updatedItems) {
            if (item.productId != null && item.warehouseId != null) {
                if (invDao != null && eng != null) {
                    val trackedBalance = invDao.getTotalAvailableQty(
                        warehouseId = item.warehouseId,
                        itemCategory = InventoryItemCategory.FINISHED_GOOD.name,
                        itemId = item.productId
                    )
                    if (trackedBalance > 0.0) {
                        eng.postMovementInternal(
                            com.example.domain.inventory.InventoryMovementRequest(
                                movementType = InventoryMovementType.SALES_ISSUE,
                                warehouseId = item.warehouseId,
                                itemCategory = InventoryItemCategory.FINISHED_GOOD,
                                itemId = item.productId,
                                itemName = item.description,
                                quantity = item.quantity,
                                unit = "كرتونة",
                                unitCost = item.unitPrice,
                                currency = currency,
                                referenceDocType = "SALES_INVOICE",
                                referenceDocId = invoiceId,
                                referenceDocNo = invoiceNumber,
                                reason = "صرف مبيعات فاتورة $invoiceNumber للعميل $cleanCustomerName",
                                qcApproved = true,
                                performedBy = user,
                                idempotencyKey = "INV-${invoiceId}-LINE-${item.productId}-${item.warehouseId}"
                            )
                        )
                    }
                }
                dao.insertStockMove(
                    StockMoveEntity(
                        warehouseId = item.warehouseId,
                        productId = item.productId,
                        quantity = item.quantity,
                        kind = "SALE",
                        invoiceId = invoiceId,
                        note = "مبيعات فاتورة $invoiceNumber",
                        createdBy = user
                    )
                )
            }
        }

        val productsById = dao.getProductsDirect().associateBy { it.id }
        val totalCogs = updatedItems.sumOf { item ->
            val pid = item.productId
            val prodCost = if (pid != null) (productsById[pid]?.purchasePrice ?: 0.0).coerceAtLeast(0.0) else 0.0
            item.quantity * prodCost
        }
        accountingEngine?.postSalesInvoiceJournalInternal(
            invoiceId = invoiceId,
            invoiceNo = invoiceNumber,
            invoiceDate = date,
            customerName = cleanCustomerName,
            currency = currency,
            totalRevenue = total,
            totalCogs = totalCogs,
            actor = user
        )

        // Handle instant payment if specified
        if (!paymentCashbox.isNullOrBlank() && paymentAmount != null && paymentAmount > 0) {
            val actualPay = paymentAmount.coerceAtMost(total)
            val cleanCurrency = currency.trim().uppercase().ifBlank { "SDG" }
            val payId = dao.insertInvoicePayment(
                InvoicePaymentEntity(
                    invoiceId = invoiceId,
                    payDate = date,
                    cashbox = paymentCashbox,
                    amount = actualPay,
                    note = "سداد فوري عند إصدار الفاتورة [$cleanCurrency]",
                    createdBy = user
                )
            )
            val payMovNow = System.currentTimeMillis()
            val payMovUuid = java.util.UUID.randomUUID().toString()
            val cleanPayBox = paymentCashbox.trim().ifBlank { "خزنة السودان" }
            val canonicalPayCur = com.example.util.TreasuryBalanceNormalizer.resolveCanonicalCurrency(currency, cleanPayBox)
            dao.insertMovement(
                MovementEntity(
                    txDate = date,
                    cashbox = cleanPayBox,
                    currency = canonicalPayCur,
                    direction = "وارد",
                    amount = kotlin.math.abs(actualPay),
                    description = "دفعة فاتورة $invoiceNumber - $customerName",
                    reference = "INV:$invoiceNumber:P$payId",
                    createdBy = user,
                    createdAt = payMovNow,
                    cloudDocId = "MOV-${payMovNow}-${payMovUuid.take(8)}",
                    syncUuid = payMovUuid,
                    updatedAt = payMovNow
                )
            )
            accountingEngine?.postCustomerPaymentJournalInternal(
                paymentId = payId,
                referenceDocNo = invoiceNumber,
                payDate = date,
                customerName = cleanCustomerName,
                cashbox = paymentCashbox,
                currency = cleanCurrency,
                amount = actualPay,
                actor = user
            )
        }

        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "CREATE_INVOICE",
                detail = "$invoiceNumber | $customerName | إجمالي: $total $currency"
            )
        )

        Result.success(invoiceId)
    }

    suspend fun recordInvoicePayment(
        invoiceId: Long,
        invoiceNo: String,
        customerName: String,
        currency: String,
        cashbox: String,
        amount: Double,
        note: String,
        user: String,
        date: String = currentDateString()
    ): Result<Long> = runInTransaction(
        operationName = "RECORD_INVOICE_PAYMENT",
        entityType = "invoice_payments",
        details = "دفعة $amount $currency لفاتورة $invoiceNo ($customerName) في $cashbox"
    ) {
        if (!amount.isFinite() || amount <= 0.0) {
            return@runInTransaction Result.failure(Exception("المبلغ غير صالح"))
        }
        val invoice = dao.getInvoiceById(invoiceId)
            ?: return@runInTransaction Result.failure(Exception("الفاتورة غير موجودة"))
        if (invoice.status.equals("cancelled", ignoreCase = true) || invoice.status == "ملغاة") {
            return@runInTransaction Result.failure(Exception("لا يمكن تحصيل دفعة لفاتورة ملغاة"))
        }
        val activePaid = dao.getInvoicePaymentsByInvoiceIdDirect(invoiceId)
            .filter { it.status == "active" }
            .sumOf { it.amount }
        val remaining = (invoice.total - activePaid).coerceAtLeast(0.0)
        if (amount > remaining + 0.01) {
            return@runInTransaction Result.failure(Exception("مبلغ الدفعة يتجاوز المتبقي المستحق على الفاتورة"))
        }

        val cleanCurrency = currency.trim().uppercase().ifBlank { "SDG" }
        val taggedNote = if (note.contains("[$cleanCurrency]")) note else "${note.ifBlank { "سداد دفعة" }} [$cleanCurrency]"
        val payId = dao.insertInvoicePayment(
            InvoicePaymentEntity(
                invoiceId = invoiceId,
                payDate = date,
                cashbox = cashbox,
                amount = amount,
                note = taggedNote,
                createdBy = user
            )
        )

        val payMovNow = System.currentTimeMillis()
        val payMovUuid = java.util.UUID.randomUUID().toString()
        val cleanPayBox = cashbox.trim().ifBlank { "خزنة السودان" }
        val canonicalPayCur = com.example.util.TreasuryBalanceNormalizer.resolveCanonicalCurrency(currency, cleanPayBox)
        dao.insertMovement(
            MovementEntity(
                txDate = date,
                cashbox = cleanPayBox,
                currency = canonicalPayCur,
                direction = "وارد",
                amount = kotlin.math.abs(amount),
                description = "دفعة فاتورة $invoiceNo - $customerName: $note",
                reference = "INV:$invoiceNo:P$payId",
                createdBy = user,
                createdAt = payMovNow,
                cloudDocId = "MOV-${payMovNow}-${payMovUuid.take(8)}",
                syncUuid = payMovUuid,
                updatedAt = payMovNow
            )
        )
        accountingEngine?.postCustomerPaymentJournalInternal(
            paymentId = payId,
            referenceDocNo = invoiceNo,
            payDate = date,
            customerName = customerName,
            cashbox = cashbox,
            currency = cleanCurrency,
            amount = amount,
            actor = user
        )

        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "INVOICE_PAYMENT",
                detail = "دفعة $amount $currency لفاتورة $invoiceNo"
            )
        )

        Result.success(payId)
    }

    private suspend fun executeCancelInvoiceInternal(invoiceId: Long, reason: String, user: String): Result<InvoiceEntity> {
        val invoice = dao.getInvoiceById(invoiceId) ?: return Result.failure(Exception("الفاتورة غير موجودة"))
        if (com.example.util.TreasuryBalanceNormalizer.isCancelledOrReversedStatus(invoice.status)) {
            return Result.failure(Exception("الفاتورة ملغاة مسبقاً"))
        }

        val cleanReason = reason.trim().ifBlank { "إلغاء فاتورة معتمد" }
        val reversalDate = currentDateString()

        val cancelledInvoice = invoice.copy(
            status = "cancelled",
            cancelReason = cleanReason
        )
        dao.updateInvoice(cancelledInvoice)

        val invoiceItems = dao.getInvoiceItemsByInvoiceIdDirect(invoiceId)
        var restoredItemsCount = 0
        for (item in invoiceItems) {
            val pid = item.productId
            val wid = item.warehouseId
            if (pid != null && wid != null && item.quantity > 0.0) {
                dao.insertStockMove(
                    StockMoveEntity(
                        warehouseId = wid,
                        productId = pid,
                        quantity = item.quantity,
                        kind = "RETURN_IN",
                        invoiceId = invoiceId,
                        note = "قيد عكسي لإرجاع مخزون فاتورة ملغاة ${invoice.invoiceNo}: $cleanReason",
                        createdBy = user
                    )
                )
                restoredItemsCount++
            }
        }

        val invoicePayments = dao.getInvoicePaymentsByInvoiceIdDirect(invoiceId)
        var reversedPaymentsTotal = 0.0
        val cancelledPayments = mutableListOf<InvoicePaymentEntity>()
        for (payment in invoicePayments) {
            if (!com.example.util.TreasuryBalanceNormalizer.isCancelledOrReversedStatus(payment.status) && payment.amount > 0.0) {
                cancelledPayments.add(payment)
                dao.updateInvoicePayment(
                    payment.copy(
                        status = "reversed",
                        reversalReason = cleanReason,
                        note = "${payment.note} | ملغاة بقيد عكسي لإلغاء الفاتورة ${invoice.invoiceNo}: $cleanReason"
                    )
                )
                if (payment.cashbox.isNotBlank() && !payment.cashbox.contains("تسوية إدارية")) {
                    val revNow = System.currentTimeMillis()
                    val revUuid = java.util.UUID.randomUUID().toString()
                    val cleanRevBox = payment.cashbox.trim().ifBlank { "خزنة السودان" }
                    val canonicalRevCur = com.example.util.TreasuryBalanceNormalizer.resolveCanonicalCurrency(invoice.currency, cleanRevBox)
                    dao.insertMovement(
                        MovementEntity(
                            txDate = reversalDate,
                            cashbox = cleanRevBox,
                            currency = canonicalRevCur,
                            direction = "منصرف",
                            amount = kotlin.math.abs(payment.amount),
                            description = "قيد عكسي لاسترداد دفعة فاتورة ملغاة ${invoice.invoiceNo} - ${invoice.customerName}: $cleanReason",
                            reference = "REV:${invoice.invoiceNo}:P${payment.id}",
                            createdBy = user,
                            createdAt = revNow,
                            cloudDocId = "MOV-${revNow}-${revUuid.take(8)}",
                            syncUuid = revUuid,
                            updatedAt = revNow,
                            syncStatus = "PENDING"
                        )
                    )
                }
                reversedPaymentsTotal += payment.amount
            }
        }

        accountingEngine?.postInvoiceCancelJournalInternal(
            invoice = invoice,
            reversedPayments = cancelledPayments,
            reason = cleanReason,
            actor = user
        )

        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "CANCEL_INVOICE",
                detail = "إلغاء الفاتورة ${invoice.invoiceNo} بقيد عكسي شامل (استرجاع $restoredItemsCount بنود مخزنية وعكس مدفوعات بقيمة $reversedPaymentsTotal ${invoice.currency}): $cleanReason"
            )
        )
        return Result.success(cancelledInvoice)
    }

    suspend fun cancelInvoice(invoiceId: Long, reason: String, user: String): Result<Unit> = runInTransaction(
        operationName = "CANCEL_INVOICE_COMPENSATING",
        entityType = "invoices",
        details = "إلغاء الفاتورة #$invoiceId بقيد عكسي مخزني ومالي: $reason"
    ) {
        executeCancelInvoiceInternal(invoiceId, reason, user).map { Unit }
    }

    suspend fun deleteInvoicePermanently(invoiceId: Long, user: String): Result<InvoiceEntity> = runInTransaction(
        operationName = "CANCEL_ARCHIVE_INVOICE_IMMUTABLE",
        entityType = "invoices",
        details = "إلغاء وأرشفة الفاتورة #$invoiceId بقيد عكسي معتمد (دفتر الأستاذ محصن ضد المسح المباشر)"
    ) {
        val existing = dao.getInvoiceById(invoiceId)
            ?: return@runInTransaction Result.failure(Exception("الفاتورة غير موجودة"))
        if (com.example.util.TreasuryBalanceNormalizer.isCancelledOrReversedStatus(existing.status)) {
            return@runInTransaction Result.success(existing)
        }
        executeCancelInvoiceInternal(
            invoiceId = invoiceId,
            reason = "إغلاق وأرشفة الفاتورة بقيد عكسي معتمد بواسطة $user (سجل غير قابل للحذف المباشر)",
            user = user
        )
    }

    // --- Warehouses & Products ---
    fun getWarehouses(): Flow<List<WarehouseEntity>> = dao.getAllWarehouses()
    fun getProducts(): Flow<List<ProductEntity>> = dao.getAllProducts()
    fun getStockMoves(): Flow<List<StockMoveEntity>> = dao.getAllStockMoves()

    suspend fun addWarehouse(
        name: String,
        currency: String = "SDG",
        linkedCashbox: String = "خزنة السودان",
        warehouseType: String = "مبيعات",
        user: String
    ): Long {
        val whClass = WarehouseClass.fromRaw(warehouseType.ifBlank { name })
        val id = dao.insertWarehouse(
            WarehouseEntity(
                name = name.trim(),
                currency = currency,
                linkedCashbox = linkedCashbox,
                warehouseType = warehouseType,
                warehouseClass = whClass.name,
                allowDirectSales = whClass == WarehouseClass.SALES,
                allowProductionReceipt = whClass == WarehouseClass.FINISHED_GOODS,
                syncUuid = java.util.UUID.randomUUID().toString(),
                updatedAt = System.currentTimeMillis()
            )
        )
        dao.insertAuditLog(AuditLogEntity(username = user, action = "ADD_WAREHOUSE", detail = "$name [$currency - $linkedCashbox - ${whClass.arabicLabel}]"))
        return id
    }

    suspend fun updateWarehouse(
        id: Long,
        name: String,
        active: Boolean,
        currency: String = "SDG",
        linkedCashbox: String = "خزنة السودان",
        warehouseType: String = "مبيعات",
        user: String
    ): Result<Unit> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(Exception("اسم المخزن لا يمكن أن يكون فارغاً"))
        val existing = dao.getWarehousesDirect().firstOrNull { it.id == id }
        val whClass = WarehouseClass.fromRaw(warehouseType.ifBlank { trimmed })
        dao.updateWarehouse(
            WarehouseEntity(
                id = id,
                name = trimmed,
                active = active,
                currency = currency,
                linkedCashbox = linkedCashbox,
                warehouseType = warehouseType,
                warehouseClass = whClass.name,
                code = existing?.code?.ifBlank { "WH-$id" } ?: "WH-$id",
                branch = existing?.branch.orEmpty(),
                allowDirectSales = whClass == WarehouseClass.SALES,
                allowProductionReceipt = whClass == WarehouseClass.FINISHED_GOODS,
                syncUuid = existing?.syncUuid?.ifBlank { "WH-UUID-$id" } ?: "WH-UUID-$id",
                updatedAt = System.currentTimeMillis(),
                recordVersion = (existing?.recordVersion ?: 0) + 1,
                deviceId = existing?.deviceId.orEmpty()
            )
        )
        dao.insertAuditLog(AuditLogEntity(username = user, action = "UPDATE_WAREHOUSE", detail = "$trimmed [نشط: $active, $currency, $linkedCashbox]"))
        return Result.success(Unit)
    }

    suspend fun deleteWarehouse(id: Long, user: String): Result<Unit> = runInTransaction(
        operationName = "DELETE_WAREHOUSE",
        entityType = "warehouses",
        details = "حذف المخزن #$id وتنظيف حركاته"
    ) {
        val existing = dao.getWarehouseByIdDirect(id)
        val movesToDelete = dao.getStockMovesDirect().filter { it.warehouseId == id }
        movesToDelete.forEach { sm ->
            DeletedRecordsRegistry.registerDeletedWithAliases(
                collection = "stock_moves",
                aliases = listOf(sm.id.toString(), "SM-${sm.id}"),
                primaryEntityId = sm.id.toString(),
                primaryDocId = "SM-${sm.id}",
                actor = user
            )
        }
        DeletedRecordsRegistry.registerDeletedWithAliases(
            collection = "warehouses",
            aliases = listOfNotNull(
                id.toString(),
                "WH-$id",
                existing?.name?.takeIf { it.isNotBlank() },
                existing?.syncUuid?.takeIf { it.isNotBlank() }
            ),
            primaryEntityId = id.toString(),
            primaryDocId = "WH-$id",
            actor = user
        )
        dao.clearStockMovesByWarehouse(id)
        dao.deleteWarehouse(id)
        dao.insertAuditLog(AuditLogEntity(username = user, action = "DELETE_WAREHOUSE", detail = "حذف المخزن #$id وتنظيف حركاته"))
        Result.success(Unit)
    }

    suspend fun addProduct(
        name: String,
        color: String,
        weight: String,
        purchasePrice: Double,
        salePrice: Double,
        salePriceEgp: Double = 0.0,
        minimumStock: Double,
        user: String
    ): Long {
        val sku = "PMP-" + (System.currentTimeMillis() % 1000000)
        val calculatedSaleEgp = if (salePriceEgp > 0) salePriceEgp else (salePrice / 200.0).coerceAtLeast(1.0)
        val id = dao.insertProduct(
            ProductEntity(
                sku = sku,
                name = name.trim(),
                packageWeight = weight.trim(),
                paintColor = color.trim(),
                purchasePrice = purchasePrice,
                salePrice = salePrice,
                salePriceEgp = calculatedSaleEgp,
                minimumStock = minimumStock
            )
        )
        dao.insertAuditLog(AuditLogEntity(username = user, action = "ADD_PRODUCT", detail = "$name ($color / $weight) [SDG: $salePrice, EGP: $calculatedSaleEgp]"))
        return id
    }

    suspend fun ensureProductForColorAndWeight(
        color: String,
        weight: String,
        purchasePrice: Double = 0.0,
        salePrice: Double = 0.0,
        user: String = "المدير"
    ): ProductEntity {
        val cleanColor = color.trim().ifBlank { "أبيض" }
        val cleanWeight = com.example.util.InventoryWeightManager.normalizeWeightValue(weight).ifBlank { weight.trim() }
        val allProducts = dao.getProductsDirect()
        val existing = allProducts.firstOrNull {
            it.paintColor.trim().equals(cleanColor, ignoreCase = true) &&
                com.example.util.InventoryWeightManager.normalizeWeightValue(it.packageWeight) == cleanWeight
        }
        if (existing != null) return existing

        // حساب السعر المرجعي تناسبياً بناءً على معادلة الوزن وعدد العبوات في الكرتونة (PackagingRuleSpec) بدلاً من النسخ الأعمى
        val sibling = allProducts.firstOrNull { it.paintColor.trim().equals(cleanColor, ignoreCase = true) }
        val default900Purchase = 320000.0
        val default900Sale = 385000.0

        val effectivePurchase = when {
            purchasePrice > 0.0 -> purchasePrice
            sibling != null -> calculateWeightAdjustedCartonPrice(
                targetWeight = cleanWeight,
                referenceWeight = sibling.packageWeight,
                referenceCartonPrice = sibling.purchasePrice
            )
            else -> calculateWeightAdjustedCartonPrice(
                targetWeight = cleanWeight,
                referenceWeight = "900",
                referenceCartonPrice = default900Purchase
            )
        }

        val effectiveSale = when {
            salePrice > 0.0 -> salePrice
            sibling != null -> calculateWeightAdjustedCartonPrice(
                targetWeight = cleanWeight,
                referenceWeight = sibling.packageWeight,
                referenceCartonPrice = sibling.salePrice
            )
            else -> calculateWeightAdjustedCartonPrice(
                targetWeight = cleanWeight,
                referenceWeight = "900",
                referenceCartonPrice = default900Sale
            )
        }

        val prodName = "بوهية $cleanColor $cleanWeight جم"
        val newId = addProduct(
            name = prodName,
            color = cleanColor,
            weight = cleanWeight,
            purchasePrice = effectivePurchase,
            salePrice = effectiveSale,
            minimumStock = sibling?.minimumStock ?: 10.0,
            user = user
        )
        return dao.getProductsDirect().firstOrNull { it.id == newId } ?: ProductEntity(
            id = newId,
            sku = "PMP-$newId",
            name = prodName,
            paintColor = cleanColor,
            packageWeight = cleanWeight,
            purchasePrice = effectivePurchase,
            salePrice = effectiveSale,
            salePriceEgp = (effectiveSale / 200.0).coerceAtLeast(1.0),
            minimumStock = sibling?.minimumStock ?: 10.0
        )
    }

    suspend fun renamePackageWeightSafely(
        oldWeight: String,
        newWeight: String,
        user: String
    ): Result<Int> {
        val normOld = com.example.util.InventoryWeightManager.normalizeWeightValue(oldWeight)
        val normNew = com.example.util.InventoryWeightManager.normalizeWeightValue(newWeight)
        if (normOld.isBlank() || normNew.isBlank()) {
            return Result.failure(IllegalArgumentException("قيمة الوزن غير صالحة"))
        }
        if (normOld == normNew) return Result.success(0)

        val allProducts = dao.getProductsDirect()
        val matchingProducts = allProducts.filter {
            com.example.util.InventoryWeightManager.normalizeWeightValue(it.packageWeight) == normOld
        }
        var updatedCount = 0
        for (prod in matchingProducts) {
            val conflictExists = allProducts.any {
                it.id != prod.id &&
                    it.paintColor.trim().equals(prod.paintColor.trim(), ignoreCase = true) &&
                    com.example.util.InventoryWeightManager.normalizeWeightValue(it.packageWeight) == normNew
            }
            if (!conflictExists) {
                val updatedName = if (prod.name.contains(normOld)) {
                    prod.name.replace(normOld, normNew)
                } else {
                    "بوهية ${prod.paintColor.trim()} $normNew جم"
                }
                dao.updateProduct(
                    prod.copy(
                        packageWeight = normNew,
                        name = updatedName
                    )
                )
                updatedCount++
            }
        }

        // Also keep poster_prices aligned without deleting existing records
        val allPosterPrices = dao.getPosterPricesDirect()
        val matchingPosters = allPosterPrices.filter {
            com.example.util.InventoryWeightManager.normalizeWeightValue(it.weight) == normOld
        }
        if (matchingPosters.isNotEmpty()) {
            val newPosters = matchingPosters.map { pp ->
                val isEgp = pp.currency.equals("EGP", ignoreCase = true) || pp.id.endsWith("_EGP")
                pp.copy(
                    id = if (isEgp) "${normNew}_${pp.kind}_EGP" else "${normNew}_${pp.kind}",
                    weight = normNew
                )
            }
            dao.insertPosterPrices(newPosters)
        }

        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "UPDATE_PACKAGE_WEIGHT",
                detail = "تعديل الوزن من ($normOld جم) إلى ($normNew جم) مع الحفاظ على جميع الأرصدة والحركات ($updatedCount صنف)"
            )
        )
        return Result.success(updatedCount)
    }

    suspend fun deleteProduct(id: Long, productName: String, user: String): Result<Unit> = runInTransaction(
        operationName = "DELETE_PRODUCT",
        entityType = "products",
        details = "حذف صنف من النظام: $productName (#$id)"
    ) {
        val existing = dao.getProductByIdDirect(id)
        val movesToDelete = dao.getStockMovesDirect().filter { it.productId == id }
        movesToDelete.forEach { sm ->
            DeletedRecordsRegistry.registerDeletedWithAliases(
                collection = "stock_moves",
                aliases = listOf(sm.id.toString(), "SM-${sm.id}"),
                primaryEntityId = sm.id.toString(),
                primaryDocId = "SM-${sm.id}",
                actor = user
            )
        }
        DeletedRecordsRegistry.registerDeletedWithAliases(
            collection = "products",
            aliases = listOfNotNull(
                id.toString(),
                "PROD-$id",
                "PRD-$id",
                existing?.syncUuid?.takeIf { it.isNotBlank() }
            ),
            primaryEntityId = id.toString(),
            primaryDocId = "PROD-$id",
            actor = user
        )
        dao.clearStockMovesByProduct(id)
        dao.deleteProduct(id)
        dao.insertAuditLog(AuditLogEntity(username = user, action = "DELETE_PRODUCT", detail = "حذف صنف من النظام: $productName (#$id) مع تنظيف حركاته"))
        Result.success(Unit)
    }

    suspend fun resetWarehouseStock(warehouseId: Long, warehouseName: String, user: String): Result<List<StockMoveEntity>> = runInTransaction(
        operationName = "ZERO_WAREHOUSE",
        entityType = "stock_moves",
        details = "تصفير كامل أرصدة مخزن: $warehouseName (#$warehouseId)"
    ) {
        val movesToDelete = dao.getStockMovesDirect().filter { it.warehouseId == warehouseId }
        movesToDelete.forEach { sm ->
            DeletedRecordsRegistry.registerDeletedWithAliases(
                collection = "stock_moves",
                aliases = listOf(sm.id.toString(), "SM-${sm.id}"),
                primaryEntityId = sm.id.toString(),
                primaryDocId = "SM-${sm.id}",
                actor = user
            )
        }
        dao.clearStockMovesByWarehouse(warehouseId)
        dao.insertAuditLog(AuditLogEntity(username = user, action = "ZERO_WAREHOUSE", detail = "تصفير كامل أرصدة مخزن: $warehouseName (#$warehouseId)"))
        Result.success(movesToDelete)
    }

    suspend fun resetCashboxBalance(cashboxName: String, user: String): Result<List<MovementEntity>> = runInTransaction(
        operationName = "ZERO_CASHBOX",
        entityType = "movements",
        details = "تصفير كامل حركات ورصيد خزنة: $cashboxName"
    ) {
        val cleanBox = cashboxName.trim()
        val clearedMovements = dao.getMovementsByCashboxNormalized(cleanBox)
        clearedMovements.forEach { m ->
            val aliases = listOfNotNull(
                m.id.toString(),
                "MOV-${m.id}",
                m.reference.takeIf { it.isNotBlank() },
                m.cloudDocId.takeIf { it.isNotBlank() },
                m.syncUuid.takeIf { it.isNotBlank() }
            )
            DeletedRecordsRegistry.registerDeletedWithAliases(
                collection = "movements",
                aliases = aliases,
                primaryEntityId = m.id.toString(),
                primaryDocId = m.cloudDocId.ifBlank { "MOV-${m.id}" },
                actor = user
            )
        }
        dao.clearMovementsByCashbox(cleanBox)
        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "ZERO_CASHBOX",
                detail = "تصفير كامل حركات ورصيد خزنة: $cleanBox (حذف ${clearedMovements.size} حركة وتسجيل شواهد منع العودة)"
            )
        )
        Result.success(clearedMovements)
    }

    suspend fun recordStockMove(
        warehouseId: Long,
        productId: Long,
        quantity: Double,
        kind: String,
        note: String,
        user: String
    ): Long {
        val id = dao.insertStockMove(
            StockMoveEntity(
                warehouseId = warehouseId,
                productId = productId,
                quantity = quantity,
                kind = kind,
                note = note,
                createdBy = user
            )
        )
        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "STOCK_$kind",
                detail = "مخزن #$warehouseId صنف #$productId كمية: $quantity"
            )
        )
        return id
    }

    fun getFactoryItems(): Flow<List<FactoryItemEntity>> = dao.getAllFactoryItems()
    fun getFactoryMachines(): Flow<List<FactoryMachineEntity>> = dao.getAllFactoryMachines()
    fun getFactoryMoves(): Flow<List<FactoryMoveEntity>> = dao.getAllFactoryMoves()
    fun getHalfaInvoices(): Flow<List<HalfaInvoiceEntity>> = dao.getAllHalfaInvoices()

    suspend fun addFactoryItem(
        name: String,
        category: String,
        unit: String,
        actor: String = "إدارة المصنع"
    ): Result<Long> {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return Result.failure(Exception("يرجى إدخال اسم الصنف/الخامة"))
        val id = dao.insertFactoryItem(
            FactoryItemEntity(
                name = cleanName,
                unit = unit.trim().ifBlank { "كجم" },
                category = category.trim().ifBlank { "خامات" }
            )
        )
        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = "ADD_FACTORY_ITEM",
                detail = "إضافة صنف مصنع جديد: $cleanName ($category - $unit)"
            )
        )
        return Result.success(id)
    }

    suspend fun updateFactoryItem(
        itemId: Long,
        newName: String,
        newCategory: String,
        newUnit: String,
        actor: String = "إدارة المصنع"
    ): Result<Unit> {
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return Result.failure(Exception("يرجى إدخال اسم المادة الخام/الصنف"))
        val cleanCategory = newCategory.trim().ifBlank { "خامات إنتاج" }
        val cleanUnit = newUnit.trim().ifBlank { "كجم" }
        dao.updateFactoryItem(itemId, cleanName, cleanCategory, cleanUnit)
        dao.updateFactoryMovesItemName(itemId, cleanName)
        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = "UPDATE_FACTORY_ITEM",
                detail = "تعديل بيانات المادة الخام (#$itemId): $cleanName ($cleanCategory - $cleanUnit)"
            )
        )
        return Result.success(Unit)
    }

    suspend fun renameFactoryItemCategory(
        oldCategory: String,
        newCategory: String,
        actor: String = "إدارة المصنع"
    ): Result<Unit> {
        val cleanOld = oldCategory.trim()
        val cleanNew = newCategory.trim()
        if (cleanNew.isBlank()) return Result.failure(Exception("يرجى إدخال الاسم الجديد لنوع الصنف"))
        dao.renameFactoryItemCategory(cleanOld, cleanNew)
        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = "RENAME_ITEM_CATEGORY",
                detail = "تعديل اسم نوع الصنف من ($cleanOld) إلى ($cleanNew)"
            )
        )
        return Result.success(Unit)
    }

    suspend fun deleteFactoryItem(itemId: Long, itemName: String, actor: String = "إدارة المصنع"): Result<Unit> = runInTransaction(
        operationName = "DELETE_FACTORY_ITEM",
        entityType = "factory_items",
        details = "حذف صنف من المصنع: $itemName (ID: $itemId)"
    ) {
        DeletedRecordsRegistry.registerDeletedWithAliases(
            collection = "factory_items",
            aliases = listOfNotNull(itemId.toString(), "FI-$itemId", itemName.trim().takeIf { it.isNotBlank() }),
            primaryEntityId = itemId.toString(),
            primaryDocId = "FI-$itemId",
            actor = actor
        )
        dao.deleteFactoryItemById(itemId)
        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = "DELETE_FACTORY_ITEM",
                detail = "حذف صنف من المصنع: $itemName (ID: $itemId)"
            )
        )
        Result.success(Unit)
    }

    suspend fun recordFactoryMove(
        kind: String,
        itemId: Long,
        itemName: String,
        qty: Double,
        machineId: Long? = null,
        machineName: String? = null,
        batch: String = "",
        note: String = "",
        actor: String = "مهندس الإنتاج",
        counterparty: String = "",
        date: String = currentDateString()
    ): Long {
        val id = dao.insertFactoryMove(
            FactoryMoveEntity(
                moveDate = date,
                kind = kind,
                itemId = itemId,
                itemName = itemName,
                qty = qty,
                machineId = machineId,
                machineName = machineName,
                batch = batch,
                note = note,
                actor = actor,
                counterparty = counterparty
            )
        )
        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = "FACTORY_MOVE",
                detail = "$kind: $itemName ($qty) | $counterparty"
            )
        )
        return id
    }

    suspend fun dispatchHalfa(
        itemId: Long,
        itemName: String,
        qty: Double,
        productId: Long,
        productName: String,
        cartons: Double,
        unitPrice: Double,
        note: String,
        actor: String
    ): Pair<Long, String> = runInTransaction {
        val total = cartons * unitPrice
        val invoiceNo = "HLF-" + SimpleDateFormat("yyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val targetWarehouseId = dao.getWarehousesDirect().firstOrNull { it.name.contains("حلفا") }?.id ?: 2L

        val moveId = recordFactoryMove(
            kind = "DISPATCH_HALFA",
            itemId = itemId,
            itemName = itemName,
            qty = qty,
            note = "فاتورة $invoiceNo: $note",
            actor = actor,
            counterparty = "مخزن حلفا"
        )

        dao.insertHalfaInvoice(
            HalfaInvoiceEntity(
                invoiceNo = invoiceNo,
                invoiceDate = currentDateString(),
                cashbox = "خزنة مصر",
                currency = "EGP",
                warehouseId = targetWarehouseId,
                productId = productId,
                productName = productName,
                quantity = cartons,
                unitPrice = unitPrice,
                total = total,
                factoryMoveId = moveId,
                note = note,
                createdBy = actor
            )
        )

        // Insert stock move into Halfa warehouse
        dao.insertStockMove(
            StockMoveEntity(
                warehouseId = targetWarehouseId,
                productId = productId,
                quantity = cartons,
                kind = "IN",
                note = "ترحيل من مصنع مصر - فاتورة $invoiceNo",
                createdBy = actor
            )
        )

        Pair(moveId, invoiceNo)
    }

    // --- Poster Prices ---
    fun getPosterPrices(): Flow<List<PosterPriceEntity>> = dao.getAllPosterPrices()

    suspend fun updatePosterPrice(weight: String, kind: String, price: Long, currency: String = "SDG") {
        val cleanCurrency = currency.trim().uppercase()
        val isEgp = cleanCurrency == "EGP"
        val id = if (isEgp) "${weight}_${kind}_EGP" else "${weight}_$kind"
        val exactPrice = price.coerceAtLeast(0L)
        dao.insertPosterPrices(
            listOf(
                PosterPriceEntity(
                    id = id,
                    weight = weight,
                    kind = kind,
                    price = exactPrice,
                    priceEgp = if (isEgp) exactPrice else 0L,
                    currency = if (isEgp) "EGP" else "SDG"
                )
            )
        )
        // Update matching ProductEntity for ONLY this currency without mixing or cross-multiplying
        runCatching {
            val matchingProducts = dao.getProductsDirect().filter {
                (it.paintColor.equals(kind, ignoreCase = true) || it.name.contains(kind)) &&
                    it.packageWeight == weight
            }
            for (prod in matchingProducts) {
                val updated = if (isEgp) {
                    prod.copy(salePriceEgp = exactPrice.toDouble())
                } else {
                    prod.copy(salePrice = exactPrice.toDouble())
                }
                dao.updateProduct(updated)
            }
        }
    }

    // --- Audit Log ---
    fun getAuditLogs(): Flow<List<AuditLogEntity>> = dao.getAuditLogs()

    // --- User Accounts & Future Server Sync ---
    fun getUserAccounts(): Flow<List<UserAccountEntity>> = dao.getAllUserAccounts()

    suspend fun createUserAccount(
        fullName: String,
        username: String,
        phoneOrEmail: String,
        pinOrPassword: String,
        role: String,
        branch: String,
        permissions: String,
        serverEndpoint: String,
        actor: String
    ): Result<Long> {
        val cleanName = fullName.trim()
        val cleanUsername = username.trim().lowercase(Locale.ROOT)
        if (cleanName.isBlank()) return Result.failure(Exception("يرجى إدخال الاسم الكامل للمستخدم"))
        if (cleanUsername.isBlank()) return Result.failure(Exception("يرجى إدخال اسم الدخول (Username)"))
        if (pinOrPassword.trim().length < 4) return Result.failure(Exception("رمز الدخول أو كلمة المرور يجب أن لا تقل عن 4 خانات"))

        val existing = dao.getUserByUsername(cleanUsername)
        if (existing != null) {
            return Result.failure(Exception("اسم الدخول ($cleanUsername) مسجل مسبقاً لمستخدم آخر"))
        }

        val branchPrefix = when {
            branch.contains("السودان") -> "SD"
            branch.contains("مصر") -> "EG"
            branch.contains("حلفا") -> "HF"
            else -> "HQ"
        }
        val suffix = (System.currentTimeMillis() % 9000 + 1000).toString()
        val userCode = "USR-$branchPrefix-$suffix"
        val syncToken = "SYNC-PMP-$branchPrefix-$suffix-${cleanUsername.take(4).uppercase(Locale.ROOT)}"
        val remoteId = "SRV-NODE-$branchPrefix$suffix"

        val id = dao.insertUserAccount(
            UserAccountEntity(
                userCode = userCode,
                fullName = cleanName,
                username = cleanUsername,
                phoneOrEmail = phoneOrEmail.trim(),
                pinOrPassword = com.example.util.PasswordSecurityUtil.hashPassword(pinOrPassword.trim()),
                role = role,
                branch = branch,
                permissions = permissions.ifBlank { "الخزن,المبيعات,المخازن" },
                serverSyncToken = syncToken,
                remoteServerId = remoteId,
                syncStatus = "READY_FOR_SYNC",
                serverEndpoint = serverEndpoint.trim().ifBlank { "https://api.pmp-paints-erp.com/v1" },
                isCurrentSession = false,
                active = true
            )
        )

        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = "CREATE_USER_ACCOUNT",
                detail = "إنشاء حساب $cleanName (@$cleanUsername) | كود الربط: $userCode | الفرع: $branch"
            )
        )
        return Result.success(id)
    }

    suspend fun switchActiveUser(targetUserId: Long, pinInput: String? = null): Result<UserAccountEntity> {
        val target = dao.getUserById(targetUserId)
            ?: return Result.failure(Exception("الحساب غير موجود"))
        if (!target.active) {
            return Result.failure(Exception("هذا الحساب موقوف حالياً، يرجى تفعيله أولاً"))
        }
        var activeTarget = target
        if (pinInput != null) {
            val cleanPin = pinInput.trim()
            val matches = com.example.util.PasswordSecurityUtil.verifyPassword(cleanPin, target.pinOrPassword)
            if (!matches) {
                return Result.failure(Exception("رمز الدخول (PIN) غير صحيح للحساب ${target.fullName}"))
            }
            if (com.example.util.PasswordSecurityUtil.needsUpgradeToPbkdf2(target.pinOrPassword)) {
                val upgradedHash = com.example.util.PasswordSecurityUtil.hashPassword(cleanPin)
                activeTarget = target.copy(pinOrPassword = upgradedHash)
                dao.updateUserAccount(activeTarget)
            }
        }

        dao.setActiveSessionUser(targetUserId)
        dao.insertAuditLog(
            AuditLogEntity(
                username = activeTarget.fullName,
                action = "SWITCH_USER_SESSION",
                detail = "تفعيل الجلسة بالحساب ${activeTarget.fullName} (${activeTarget.userCode}) - ${activeTarget.role}"
            )
        )
        return Result.success(activeTarget.copy(isCurrentSession = true))
    }

    suspend fun verifySystemManagerPassword(passwordInput: String): Result<UserAccountEntity> {
        val cleanInput = passwordInput.trim()
        if (cleanInput.isBlank()) {
            return Result.failure(Exception("يرجى إدخال كلمة المرور الخاصة بمدير النظام لتأكيد الحذف"))
        }
        val allUsers = dao.getAllUserAccounts().first()
        val managers = allUsers.filter {
            it.role.contains("مدير النظام") || it.username.equals("admin", ignoreCase = true)
        }
        val matchedManager = managers.firstOrNull {
            com.example.util.PasswordSecurityUtil.verifyPassword(cleanInput, it.pinOrPassword)
        }
        return if (matchedManager != null) {
            if (com.example.util.PasswordSecurityUtil.needsUpgradeToPbkdf2(matchedManager.pinOrPassword)) {
                val upgraded = matchedManager.copy(pinOrPassword = com.example.util.PasswordSecurityUtil.hashPassword(cleanInput))
                dao.updateUserAccount(upgraded)
                Result.success(upgraded)
            } else {
                Result.success(matchedManager)
            }
        } else {
            Result.failure(Exception("كلمة المرور الخاصة بمدير النظام غير صحيحة! تم إيقاف عملية تصفير السيرفر."))
        }
    }

    suspend fun toggleUserAccountActive(user: UserAccountEntity, actor: String): Result<Unit> {
        if (user.isCurrentSession && user.active) {
            return Result.failure(Exception("لا يمكن إيقاف الحساب النشط حالياً أثناء استخدامه"))
        }
        val newActive = !user.active
        dao.updateUserAccount(user.copy(active = newActive))
        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = if (newActive) "ACTIVATE_USER" else "SUSPEND_USER",
                detail = "${if (newActive) "تفعيل" else "إيقاف"} حساب ${user.fullName} (${user.userCode})"
            )
        )
        return Result.success(Unit)
    }

    suspend fun updateServerSyncReadiness(
        endpoint: String,
        clusterCode: String,
        actor: String
    ): Result<Unit> {
        val cleanEndpoint = endpoint.trim().ifBlank { "https://api.pmp-paints-erp.com/v1" }
        dao.updateAllUsersServerConfig(cleanEndpoint, "READY_FOR_SYNC")
        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = "SERVER_SYNC_CONFIG",
                detail = "تجهيز ربط حسابات المستخدمين مع السيرفر: $cleanEndpoint (Cluster: $clusterCode)"
            )
        )
        return Result.success(Unit)
    }

    suspend fun regenerateUserSyncToken(user: UserAccountEntity, actor: String): Result<String> {
        val stamp = (System.currentTimeMillis() % 90000 + 10000).toString()
        val newToken = "SYNC-PMP-${user.userCode.takeLast(4)}-$stamp"
        dao.updateUserAccount(
            user.copy(
                serverSyncToken = newToken,
                syncStatus = "READY_FOR_SYNC"
            )
        )
        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = "REGENERATE_SYNC_TOKEN",
                detail = "تحديث مفتاح الربط السحابي للحساب ${user.fullName}: $newToken"
            )
        )
        return Result.success(newToken)
    }

    suspend fun findUserByIdentity(identity: String): UserAccountEntity? {
        val cleanIdentity = com.example.util.PasswordSecurityUtil.normalizeLoginIdentity(identity)
        if (cleanIdentity.isBlank()) return null
        val allUsers = dao.getAllUserAccounts().first()
        val matches = findMatchingUsersForIdentity(allUsers, cleanIdentity)
        return matches.firstOrNull { it.active && it.pinOrPassword.isNotBlank() } ?: matches.firstOrNull()
    }

    private fun findMatchingUsersForIdentity(
        allUsers: List<UserAccountEntity>,
        cleanIdentity: String
    ): List<UserAccountEntity> {
        if (cleanIdentity.isBlank()) return emptyList()
        val exact = allUsers.filter { user ->
            com.example.util.PasswordSecurityUtil.identitiesMatch(cleanIdentity, user.username) ||
                com.example.util.PasswordSecurityUtil.identitiesMatch(cleanIdentity, user.phoneOrEmail) ||
                com.example.util.PasswordSecurityUtil.identitiesMatch(cleanIdentity, user.userCode)
        }
        if (exact.isNotEmpty()) return exact

        // Fallback: allow entering the local-part prefix of an email (e.g. "admin" for "admin@pmp-paints.com")
        // or the numeric suffix of a userCode when it uniquely matches an account.
        if (!cleanIdentity.contains("@")) {
            val prefixMatches = allUsers.filter { user ->
                val storedEmail = com.example.util.PasswordSecurityUtil.normalizeLoginIdentity(user.phoneOrEmail)
                storedEmail.contains("@") && storedEmail.substringBefore("@") == cleanIdentity
            }
            if (prefixMatches.isNotEmpty()) return prefixMatches
        }
        return emptyList()
    }

    private fun defaultSeedPasswordForAccount(user: UserAccountEntity): String? {
        val uname = user.username.trim().lowercase(Locale.ROOT)
        val code = user.userCode.trim().uppercase(Locale.ROOT)
        return when {
            uname == "admin" || code == "USR-PMP-001" -> "1234"
            uname == "sudan_acc" || code == "USR-SD-002" -> "1111"
            uname == "egypt_factory" || code == "USR-EG-003" -> "2222"
            uname == "halfa_logistics" || code == "USR-HF-004" -> "3333"
            else -> null
        }
    }

    suspend fun authenticateUserCredentials(
        emailOrUsername: String,
        passwordOrPin: String
    ): Result<UserAccountEntity> {
        val cleanIdentity = com.example.util.PasswordSecurityUtil.normalizeLoginIdentity(emailOrUsername)
        val cleanSecret = com.example.util.PasswordSecurityUtil.normalizeDigitsAndControlChars(passwordOrPin)
        if (cleanIdentity.isBlank()) {
            return Result.failure(Exception("يرجى إدخال البريد الإلكتروني أو اسم المستخدم أو كود الحساب"))
        }
        if (cleanSecret.length < 4) {
            return Result.failure(Exception("كلمة المرور أو رمز الدخول يجب أن يتكون من 4 خانات على الأقل"))
        }

        val allUsers = dao.getAllUserAccounts().first()
        val matchedCandidates = findMatchingUsersForIdentity(allUsers, cleanIdentity)

        if (matchedCandidates.isNotEmpty()) {
            // Check ALL matching candidate rows (handles duplicate rows where one may have an older/blank hash)
            val activeCandidates = matchedCandidates.filter { it.active && it.syncStatus != "PENDING_MANAGER_APPROVAL" }
            for (candidate in activeCandidates) {
                // Self-heal default seeded account if an earlier Web sync overwrote pinOrPassword with ""
                val effectiveSecret = candidate.pinOrPassword.trim().ifBlank {
                    defaultSeedPasswordForAccount(candidate).orEmpty()
                }
                if (effectiveSecret.isNotBlank() &&
                    com.example.util.PasswordSecurityUtil.verifyPassword(cleanSecret, effectiveSecret)
                ) {
                    var authenticatedUser = candidate
                    if (candidate.pinOrPassword.isBlank() ||
                        com.example.util.PasswordSecurityUtil.needsUpgradeToPbkdf2(effectiveSecret)
                    ) {
                        val upgradedHash = com.example.util.PasswordSecurityUtil.hashPassword(cleanSecret)
                        authenticatedUser = candidate.copy(pinOrPassword = upgradedHash)
                        dao.updateUserAccount(authenticatedUser)
                    }
                    dao.setActiveSessionUser(authenticatedUser.id)
                    dao.insertAuditLog(
                        AuditLogEntity(
                            username = authenticatedUser.fullName,
                            action = "USER_LOGIN",
                            detail = "تسجيل دخول ناجح للحساب ${authenticatedUser.fullName} (${authenticatedUser.userCode})"
                        )
                    )
                    return Result.success(authenticatedUser.copy(isCurrentSession = true))
                }
            }

            // No active candidate matched the password; report the most relevant status error
            val primaryUser = activeCandidates.firstOrNull() ?: matchedCandidates.first()
            if (primaryUser.syncStatus == "PENDING_MANAGER_APPROVAL") {
                return Result.failure(
                    Exception("لا يُسمح بالدخول: هذا الحساب بانتظار موافقة حساب المدير وتحديد الصلاحيات أولاً")
                )
            }
            if (!primaryUser.active) {
                return Result.failure(Exception("هذا الحساب موقوف أو غير مفعل حالياً، يرجى مراجعة مدير النظام"))
            }
            return Result.failure(Exception("كلمة المرور أو رمز الدخول غير صحيح للحساب ${primaryUser.fullName}"))
        }

        return Result.failure(
            Exception("لم يتم العثور على حساب مطابق ($emailOrUsername). تأكد من البريد الإلكتروني أو اسم المستخدم وكلمة المرور.")
        )
    }

    suspend fun updateUserProfile(
        userId: Long,
        fullName: String,
        phoneOrEmail: String,
        newPassword: String?
    ): Result<UserAccountEntity> {
        val user = dao.getUserById(userId) ?: return Result.failure(Exception("المستخدم غير موجود"))
        if (fullName.trim().isBlank()) {
            return Result.failure(Exception("الاسم لا يمكن أن يكون فارغاً"))
        }
        if (!newPassword.isNullOrBlank() && newPassword.trim().length < 4) {
            return Result.failure(Exception("كلمة المرور الجديدة يجب أن تكون 4 خانات على الأقل"))
        }

        val passwordChanged = !newPassword.isNullOrBlank()
        val updatedPin = if (passwordChanged) {
            com.example.util.PasswordSecurityUtil.hashPassword(newPassword!!.trim())
        } else {
            user.pinOrPassword
        }
        val rotatedToken = if (passwordChanged) {
            val stamp = (System.currentTimeMillis() % 90000 + 10000).toString()
            "SYNC-PMP-${user.userCode.takeLast(4)}-$stamp"
        } else {
            user.serverSyncToken
        }
        val updatedUser = user.copy(
            fullName = fullName.trim(),
            phoneOrEmail = phoneOrEmail.trim(),
            pinOrPassword = updatedPin,
            serverSyncToken = rotatedToken
        )
        dao.updateUserAccount(updatedUser)
        dao.insertAuditLog(
            AuditLogEntity(
                username = user.username,
                action = "UPDATE_PROFILE",
                detail = "تحديث بيانات الملف الشخصي للمستخدم: ${user.fullName} (${user.username})"
            )
        )
        return Result.success(updatedUser)
    }

    suspend fun bindFirebaseUserSession(
        uid: String,
        email: String?,
        displayName: String?
    ): GoogleSignInBindingResult {
        val cleanEmail = email?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val cleanName = displayName?.trim()?.ifBlank { null }
            ?: cleanEmail.substringBefore("@").ifBlank { "مستخدم Google" }
        val usernameCandidate = cleanEmail.substringBefore("@")
            .replace(Regex("[^a-z0-9_.]"), "_")
            .ifBlank { "google_${uid.take(6).lowercase(Locale.ROOT)}" }

        val allUsers = dao.getAllUserAccounts().first()
        val isPrimaryManagerEmail = cleanEmail == "oaw7ab@gmail.com" || cleanEmail == "admin@pmp-paints.com"
        val existing = if (isPrimaryManagerEmail) {
            allUsers.firstOrNull { it.userCode == "USR-HQ-001" || it.role.contains("مدير النظام") }
                ?: allUsers.firstOrNull { it.phoneOrEmail.lowercase(Locale.ROOT) == cleanEmail }
        } else {
            allUsers.firstOrNull { user ->
                (uid.isNotBlank() && user.remoteServerId == uid) ||
                    (cleanEmail.isNotBlank() && user.phoneOrEmail.lowercase(Locale.ROOT) == cleanEmail) ||
                    user.username.lowercase(Locale.ROOT) == usernameCandidate
            }
        }

        if (existing != null) {
            // Delete any duplicate pending accounts created by earlier repeated clicks with the same email or username
            allUsers.filter { it.id != existing.id && ((cleanEmail.isNotBlank() && it.phoneOrEmail.equals(cleanEmail, ignoreCase = true)) || it.username.equals(usernameCandidate, ignoreCase = true)) && it.syncStatus == "PENDING_MANAGER_APPROVAL" }
                .forEach { dup -> dao.deleteUserAccount(dup.id) }

            // Check if the account is still awaiting manager approval or is inactive (unless it is the primary manager email)
            if (!isPrimaryManagerEmail && (existing.syncStatus == "PENDING_MANAGER_APPROVAL" || !existing.active)) {
                val updatedPending = existing.copy(
                    phoneOrEmail = if (cleanEmail.isNotBlank()) cleanEmail else existing.phoneOrEmail,
                    remoteServerId = uid,
                    isCurrentSession = false
                )
                dao.updateUserAccount(updatedPending)
                dao.insertAuditLog(
                    AuditLogEntity(
                        username = updatedPending.fullName,
                        action = "GOOGLE_LOGIN_BLOCKED_PENDING",
                        detail = "محاولة دخول عبر Google (${updatedPending.phoneOrEmail}) بانتظار موافقة حساب المدير وتحديد الصلاحيات"
                    )
                )
                return GoogleSignInBindingResult.PendingApproval(
                    account = updatedPending,
                    message = "طلبك مسجل مسبقاً بكود (${updatedPending.userCode}) وبانتظار موافقة حساب المدير وتحديد الصلاحيات."
                )
            }

            // Account has been approved by the manager!
            val updated = existing.copy(
                phoneOrEmail = if (cleanEmail.isNotBlank()) cleanEmail else existing.phoneOrEmail,
                remoteServerId = uid,
                syncStatus = "APPROVED_BY_MANAGER",
                isCurrentSession = true,
                active = true
            )
            dao.updateUserAccount(updated)
            dao.setActiveSessionUser(existing.id)
            dao.insertAuditLog(
                AuditLogEntity(
                    username = updated.fullName,
                    action = "GOOGLE_AUTH_LOGIN",
                    detail = "دخول معتمد عبر Google (${updated.phoneOrEmail}) بالصلاحيات المحددة من المدير: [${updated.permissions}]"
                )
            )
            return GoogleSignInBindingResult.Approved(updated)
        }

        // New Google account: Do NOT allow access until Manager approves creation and sets permissions!
        val suffix = (System.currentTimeMillis() % 9000 + 1000).toString()
        val userCode = "USR-GGL-$suffix"
        val syncToken = "SYNC-FB-${uid.take(8).uppercase(Locale.ROOT)}"
        val pendingAccount = UserAccountEntity(
            userCode = userCode,
            fullName = cleanName,
            username = usernameCandidate,
            phoneOrEmail = cleanEmail.ifBlank { "$usernameCandidate@gmail.com" },
            pinOrPassword = com.example.util.PasswordSecurityUtil.hashPassword("1234"),
            role = "حساب Google (بانتظار موافقة المدير)",
            branch = "فرع السودان (SD-001)",
            permissions = "",
            serverSyncToken = syncToken,
            remoteServerId = uid,
            syncStatus = "PENDING_MANAGER_APPROVAL",
            serverEndpoint = "https://firestore.googleapis.com/v1/projects/pmp-paints-cloud",
            isCurrentSession = false,
            active = false
        )
        val newId = dao.insertUserAccount(pendingAccount)
        val savedPending = pendingAccount.copy(id = newId)
        dao.insertAuditLog(
            AuditLogEntity(
                username = cleanName,
                action = "GOOGLE_AUTH_PENDING_REQUEST",
                detail = "طلب إنشاء حساب جديد عبر Google ($cleanEmail) | الكود: $userCode | بانتظار موافقة المدير وتحديد الصلاحيات"
            )
        )
        return GoogleSignInBindingResult.PendingApproval(
            account = savedPending,
            message = "تم تسجيل طلب إنشاء حسابك عبر Google ($cleanName • $userCode). لا يُسمح بالدخول حتى يوافق حساب المدير ويحدد الصلاحيات المخصصة لك."
        )
    }

    suspend fun deleteUserAccountPermanently(
        userId: Long,
        userCode: String,
        username: String = "",
        email: String = "",
        user: String
    ): Result<Unit> {
        DeletedAccountsRegistry.registerDeleted(userCode, username, email, user)
        dao.deleteUserAccount(userId)
        if (userCode.isNotBlank()) {
            dao.deleteUserAccountByCode(userCode)
        }
        if (username.isNotBlank()) {
            dao.deleteUserAccountByUsername(username)
        }
        dao.insertAuditLog(AuditLogEntity(username = user, action = "DELETE_USER_PERMANENT", detail = "حذف الحساب نهائياً ($userCode / $username #$userId)"))
        return Result.success(Unit)
    }

    suspend fun restoreUserAccount(userId: Long, user: String): Result<UserAccountEntity> {
        val account = dao.getUserById(userId) ?: return Result.failure(Exception("الحساب غير موجود"))
        val restored = account.copy(active = true, syncStatus = "APPROVED_BY_MANAGER")
        dao.updateUserAccount(restored)
        dao.insertAuditLog(AuditLogEntity(username = user, action = "RESTORE_USER_ACCOUNT", detail = "استرداد وتفعيل الحساب ${restored.fullName} (${restored.userCode})"))
        return Result.success(restored)
    }

    suspend fun generatePasswordResetCode(
        identity: String,
        channel: String,
        providedOtpCode: String? = null
    ): Result<Pair<String, UserAccountEntity>> {
        val cleanIdentity = identity.trim().lowercase(Locale.ROOT)
        if (cleanIdentity.isBlank()) {
            return Result.failure(Exception("يرجى إدخال اسم المستخدم أو البريد الإلكتروني أو كود الحساب"))
        }

        val allUsers = dao.getAllUserAccounts().first()
        val user = allUsers.firstOrNull {
            it.username.lowercase(Locale.ROOT) == cleanIdentity ||
                (it.phoneOrEmail.isNotBlank() && it.phoneOrEmail.lowercase(Locale.ROOT) == cleanIdentity) ||
                it.userCode.lowercase(Locale.ROOT) == cleanIdentity
        } ?: return Result.failure(Exception("لم يتم العثور على أي حساب مسجل بهذه البيانات ($identity)"))

        val otpCode = providedOtpCode?.takeIf { it.length == 6 }
            ?: com.example.util.PasswordSecurityUtil.generateSecureSixDigitOtp()

        if (channel == "EMAIL") {
            dao.insertAuditLog(
                AuditLogEntity(
                    username = user.fullName,
                    action = "PASSWORD_RESET_EMAIL_CODE",
                    detail = "تم إنشاء رمز تحقق مشفر (صالح لمدة 10 دقائق) لبريد Google (${user.phoneOrEmail}) للحساب ${user.userCode}"
                )
            )
        } else {
            dao.insertAuditLog(
                AuditLogEntity(
                    username = user.fullName,
                    action = "PASSWORD_RESET_MANAGER_ALERT",
                    detail = "★ إشعار مدير النظام: طلب إعادة تعيين كلمة المرور للموظف: ${user.fullName} (${user.userCode}). رمز التحقق المؤقت (10 دقائق): [$otpCode]"
                )
            )
        }
        return Result.success(Pair(otpCode, user))
    }

    suspend fun completePasswordReset(
        userId: Long,
        newPinOrPassword: String,
        actor: String
    ): Result<UserAccountEntity> {
        val user = dao.getUserById(userId) ?: return Result.failure(Exception("الحساب غير موجود"))
        val cleanPass = newPinOrPassword.trim()
        if (cleanPass.length < 4) {
            return Result.failure(Exception("كلمة المرور الجديدة يجب أن تتكون من 4 خانات على الأقل"))
        }
        val stamp = (System.currentTimeMillis() % 90000 + 10000).toString()
        val rotatedToken = "SYNC-PMP-${user.userCode.takeLast(4)}-$stamp"
        val updated = user.copy(
            pinOrPassword = com.example.util.PasswordSecurityUtil.hashPassword(cleanPass),
            serverSyncToken = rotatedToken,
            syncStatus = "READY_FOR_SYNC"
        )
        dao.updateUserAccount(updated)
        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = "PASSWORD_RESET_SUCCESS",
                detail = "تم بنجاح تغيير كلمة المرور وتدوير رمز الجلسة لحساب ${user.fullName} (${user.userCode})"
            )
        )
        return Result.success(updated)
    }

    suspend fun addCustomColor(colorName: String, user: String): Result<String> {
        val clean = colorName.trim()
        if (clean.isBlank()) return Result.failure(Exception("اسم اللون لا يمكن أن يكون فارغاً"))
        dao.insertPosterPrices(
            listOf(
                PosterPriceEntity(id = "250_$clean", weight = "250", kind = clean, price = 490000L),
                PosterPriceEntity(id = "900_$clean", weight = "900", kind = clean, price = 375000L)
            )
        )
        val allProds = dao.getAllProducts().first()
        if (allProds.none { it.paintColor == clean && it.packageWeight == "900" }) {
            dao.insertProduct(
                ProductEntity(
                    sku = "PMP-" + (System.currentTimeMillis() % 90000 + 10000),
                    name = "بوهية $clean 900 جم",
                    category = "بوهيات",
                    unit = "كرتونة",
                    paintColor = clean,
                    packageWeight = "900",
                    purchasePrice = 320000.0,
                    salePrice = 385000.0,
                    minimumStock = 10.0
                )
            )
        }
        if (allProds.none { it.paintColor == clean && it.packageWeight == "250" }) {
            dao.insertProduct(
                ProductEntity(
                    sku = "PMP-" + (System.currentTimeMillis() % 90000 + 20000),
                    name = "بوهية $clean 250 جم",
                    category = "بوهيات",
                    unit = "كرتونة",
                    paintColor = clean,
                    packageWeight = "250",
                    purchasePrice = 420000.0,
                    salePrice = 500000.0,
                    minimumStock = 10.0
                )
            )
        }
        dao.insertAuditLog(AuditLogEntity(username = user, action = "ADD_PAINT_COLOR", detail = "إضافة لون بوهية جديد: $clean"))
        return Result.success(clean)
    }

    fun validateBackendOperation(
        actorAccount: UserAccountEntity?,
        module: String,
        action: com.example.util.ErpActionPermission,
        nowMillis: Long = System.currentTimeMillis()
    ): com.example.util.EnterpriseRbacManager.BackendAccessDecision {
        return com.example.util.EnterpriseRbacManager.validateBackendRequest(
            user = actorAccount,
            module = module,
            action = action,
            nowMillis = nowMillis
        )
    }

    suspend fun updateEmployeePermissionsAndRole(
        account: UserAccountEntity,
        newRole: String,
        newBranch: String,
        newPermissions: String,
        newLinkedUid: String,
        approveAccount: Boolean = true,
        actor: String,
        actorAccount: UserAccountEntity? = null,
        reason: String = "تحديث صلاحيات الموظف"
    ): Result<UserAccountEntity> {
        if (actorAccount != null) {
            val decision = validateBackendOperation(
                actorAccount = actorAccount,
                module = "المستخدمين",
                action = com.example.util.ErpActionPermission.USER_MANAGEMENT
            )
            if (!decision.allowed) {
                return Result.failure(SecurityException(decision.message))
            }
        }
        val cleanPerms = newPermissions.trim()
        val resolvedRole = newRole.trim().ifBlank { account.role }
        val historyEntry = com.example.util.EnterpriseRbacManager.recordPermissionChange(
            actor = actorAccount,
            targetUser = account,
            newRole = resolvedRole,
            newPermissions = cleanPerms,
            reason = reason
        )
        val updated = account.copy(
            role = resolvedRole,
            branch = newBranch.trim().ifBlank { account.branch },
            permissions = cleanPerms,
            remoteServerId = newLinkedUid.trim().ifBlank { account.remoteServerId },
            syncStatus = if (approveAccount) "APPROVED_BY_MANAGER" else account.syncStatus,
            active = if (approveAccount) true else account.active
        )
        dao.updateUserAccount(updated)
        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = if (account.syncStatus == "PENDING_MANAGER_APPROVAL" && approveAccount) {
                    "APPROVE_GOOGLE_ACCOUNT"
                } else {
                    "UPDATE_EMPLOYEE_PERMISSIONS"
                },
                detail = "اعتماد/تحديث صلاحيات الحساب ${account.fullName} (${account.userCode}) -> [$cleanPerms] | السبب: ${historyEntry.reason}"
            )
        )
        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = "PERMISSION_CHANGE_HISTORY",
                detail = "[${historyEntry.entryId}] الموظف: ${account.username} | الدور: (${account.role} -> $resolvedRole) | أضيف: ${historyEntry.addedPermissions.joinToString(",")} | حُذف: ${historyEntry.removedPermissions.joinToString(",")}"
            )
        )
        return Result.success(updated)
    }

    suspend fun upsertAccountFromCloud(
        userCode: String,
        fullName: String,
        username: String,
        role: String,
        branch: String,
        permissions: String,
        serverSyncToken: String,
        linkedUid: String,
        active: Boolean,
        activateSession: Boolean = false
    ): UserAccountEntity {
        // Strictly block and purge tombstoned accounts from cloud
        if (DeletedAccountsRegistry.isDeleted(userCode, username, null)) {
            val allUsers = dao.getAllUserAccounts().first()
            val existing = allUsers.firstOrNull {
                it.userCode.equals(userCode, ignoreCase = true) ||
                    it.username.equals(username, ignoreCase = true)
            }
            if (existing != null && !existing.isCurrentSession) {
                dao.deleteUserAccount(existing.id)
                if (existing.userCode.isNotBlank()) dao.deleteUserAccountByCode(existing.userCode)
                if (existing.username.isNotBlank()) dao.deleteUserAccountByUsername(existing.username)
            }
            return existing ?: UserAccountEntity(
                userCode = userCode,
                fullName = fullName.ifBlank { username },
                username = username,
                role = role.ifBlank { "موظف" },
                branch = branch.ifBlank { "فرع السودان (SD-001)" },
                permissions = permissions,
                serverSyncToken = serverSyncToken,
                active = false
            )
        }

        val allUsers = dao.getAllUserAccounts().first()
        val existing = allUsers.firstOrNull {
            it.userCode.equals(userCode, ignoreCase = true) ||
                it.username.equals(username, ignoreCase = true)
        }
        return if (existing != null) {
            val updated = existing.copy(
                userCode = userCode,
                fullName = fullName.ifBlank { existing.fullName },
                username = username.ifBlank { existing.username },
                role = role.ifBlank { existing.role },
                branch = branch.ifBlank { existing.branch },
                permissions = permissions.ifBlank { existing.permissions },
                serverSyncToken = serverSyncToken.ifBlank { existing.serverSyncToken },
                remoteServerId = linkedUid.ifBlank { existing.remoteServerId },
                syncStatus = "CLOUD_SYNCED",
                active = active
            )
            dao.updateUserAccount(updated)
            if (activateSession) {
                dao.setActiveSessionUser(existing.id)
                updated.copy(isCurrentSession = true)
            } else {
                updated
            }
        } else {
            val inserted = UserAccountEntity(
                userCode = userCode,
                fullName = fullName,
                username = username,
                phoneOrEmail = "$username@pmp-paints.com",
                pinOrPassword = "1234",
                role = role,
                branch = branch,
                permissions = permissions,
                serverSyncToken = serverSyncToken,
                remoteServerId = linkedUid,
                syncStatus = "CLOUD_SYNCED",
                serverEndpoint = "https://firestore.googleapis.com/v1",
                isCurrentSession = activateSession,
                active = active
            )
            val newId = dao.insertUserAccount(inserted)
            if (activateSession) {
                dao.setActiveSessionUser(newId)
            }
            inserted.copy(id = newId)
        }
    }

    suspend fun deduplicateDatabaseUsers() {
        val all = dao.getUserAccountsDirect()
        val seen = mutableSetOf<String>()
        val toDelete = mutableListOf<Long>()
        val sorted = all.sortedWith(
            compareByDescending<UserAccountEntity> { it.isCurrentSession }
                .thenByDescending { it.syncStatus != "PENDING_MANAGER_APPROVAL" }
                .thenBy { it.id }
        )
        for (u in sorted) {
            val key = u.username.trim().lowercase().ifBlank { u.userCode.trim().lowercase() }
            if (key.isNotBlank()) {
                if (key in seen) {
                    toDelete.add(u.id)
                } else {
                    seen.add(key)
                }
            }
        }
        toDelete.forEach { dao.deleteUserAccount(it) }
    }

    // --- PMP Science & Chemistry Lab (Formulations, Versions, Experiments, Raw Prices, Excel Import & Safety) ---
    fun getFormulations(): Flow<List<FormulationEntity>> = dao.getAllFormulations()
    fun getChemicalSafety(): Flow<List<ChemicalSafetyEntity>> = dao.getAllChemicalSafety()
    fun getFormulationVersions(formulaId: Long): Flow<List<FormulationVersionEntity>> =
        (getLabDao()?.getAllFormulaVersions() ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .map { list -> list.filter { it.formulaId == formulaId } }
    fun getAllFormulationVersions(): Flow<List<FormulationVersionEntity>> =
        getLabDao()?.getAllFormulaVersions() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    fun getAllLabExperiments(): Flow<List<LabExperimentEntity>> =
        getLabDao()?.getAllLabExperiments() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    fun getAllRawMaterialPriceHistory(): Flow<List<RawMaterialPriceHistoryEntity>> =
        getLabDao()?.getAllRawMaterialPriceHistory() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    fun getAllLabExcelImports(): Flow<List<LabExcelImportBatchEntity>> =
        getLabDao()?.getAllExcelImports() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    /**
     * Backward-compatible save helper that delegates to immutable version-controlled persistence.
     */
    suspend fun saveFormulation(
        formulation: FormulationEntity,
        user: String,
        actorAccount: UserAccountEntity? = null,
        changeReason: String = "تحديث تركيبة مخبرية"
    ): Result<Long> = saveFormulationWithVersioning(
        formulation = formulation,
        actorName = user,
        actorAccount = actorAccount,
        changeReason = changeReason
    )

    /**
     * Saves a formulation with strict RBAC, Keystore payload sealing, and immutable version archiving.
     * - Editing an already APPROVED formulation NEVER overwrites the approved historical version or cost:
     *   it increments versionNumber (v+1), sets approvalStatus = NEEDS_REVIEW and approvedForProduction = false,
     *   while locking the old version snapshot & historicalTotalCost in lab_formula_versions.
     */
    suspend fun saveFormulationWithVersioning(
        formulation: FormulationEntity,
        actorName: String,
        actorAccount: UserAccountEntity? = null,
        changeReason: String = "تعديل مواصفات التركيبة",
        initialStatus: String? = null
    ): Result<Long> = runCatching {
        if (actorAccount != null && !com.example.util.LabSecurityManager.canCreateOrEditFormulas(actorAccount)) {
            com.example.util.LabSecurityManager.logLabAccessOrDenial(
                dao = dao,
                user = actorAccount,
                action = "EDIT_FORMULATION",
                formulaCode = formulation.formulaNo,
                allowed = false,
                note = "محاولة حفظ أو تعديل تركيبة بدون صلاحية المختبر"
            )
            throw SecurityException("غير مصرح لك بإنشاء أو تعديل التركيبات الكيميائية.")
        }

        runInTransaction(
            operationName = "LAB_SAVE_VERSIONED_FORMULA",
            entityType = "lab_formulations",
            details = "حفظ التركيبة ${formulation.formulaNo}"
        ) {
            val now = System.currentTimeMillis()
            val labDao = getLabDao()
            val ingredients = com.example.util.PvcEngine.jsonToIngredients(formulation.ingredientsJson)
            val sealedPayload = com.example.util.LabSecurityManager.sealWithDeviceKey(formulation.ingredientsJson)
                .getOrDefault("")

            val existing = if (formulation.id > 0L) dao.getFormulationById(formulation.id) else null
            val wasApproved = existing != null &&
                (existing.approvedForProduction || existing.approvalStatus.equals("APPROVED", ignoreCase = true))

            val nextVersion = if (existing == null) {
                formulation.versionNumber.coerceAtLeast(1)
            } else if (wasApproved) {
                existing.versionNumber + 1
            } else {
                existing.versionNumber.coerceAtLeast(1)
            }

            val resolvedStatus = when {
                !initialStatus.isNullOrBlank() -> initialStatus
                wasApproved -> "NEEDS_REVIEW"
                existing == null -> "DRAFT"
                existing.approvalStatus == "REJECTED" -> "NEEDS_REVIEW"
                else -> existing.approvalStatus.ifBlank { "DRAFT" }
            }

            // Preserve historical approved cost untouched when moving from APPROVED -> v+1 NEEDS_REVIEW
            val preservedHistTotal = existing?.historicalTotalCost?.takeIf { it > 0.0 }
                ?: if (existing != null && wasApproved) existing.totalCost else 0.0
            val preservedHistPerKg = existing?.historicalCostPerKg?.takeIf { it > 0.0 }
                ?: if (existing != null && wasApproved) existing.costPerKg else 0.0

            val normalized = formulation.copy(
                syncUuid = formulation.syncUuid.ifBlank { existing?.syncUuid ?: java.util.UUID.randomUUID().toString() },
                versionNumber = nextVersion,
                approvalStatus = resolvedStatus,
                approvedForProduction = false, // Requires explicit approval step
                approvedBy = if (wasApproved) "" else existing?.approvedBy.orEmpty(),
                approvedAt = if (wasApproved) 0L else (existing?.approvedAt ?: 0L),
                historicalTotalCost = preservedHistTotal,
                historicalCostPerKg = preservedHistPerKg,
                updatedBy = actorName,
                updatedAt = now,
                changeReason = changeReason.ifBlank { "تحديث تركيبة" },
                confidentialCiphertext = sealedPayload.ifBlank { formulation.confidentialCiphertext },
                isConfidentialSealed = sealedPayload.isNotBlank() || formulation.isConfidentialSealed
            )

            val savedId = if (existing == null) {
                dao.insertFormulation(normalized)
            } else {
                dao.updateFormulation(normalized)
                existing.id
            }

            if (labDao != null) {
                val prevIngredients = existing?.let { com.example.util.PvcEngine.jsonToIngredients(it.ingredientsJson) }.orEmpty()
                val diffSummary = if (existing == null) {
                    "إنشاء النسخة الأولى v1"
                } else {
                    com.example.util.PvcEngine.summarizeIngredientChanges(prevIngredients, ingredients, includeSensitiveNumbers = false)
                }

                // Mark prior approved snapshots as ARCHIVED when creating a new review draft
                if (existing != null && wasApproved) {
                    val priorVersions = labDao.getVersionsForFormulaDirect(savedId)
                    priorVersions.filter { it.status == "APPROVED" }.forEach { prior ->
                        labDao.upsertFormulaVersion(prior.copy(status = "ARCHIVED"))
                    }
                    // Ensure snapshot of previous approved version exists cleanly
                    if (priorVersions.none { it.versionNumber == existing.versionNumber }) {
                        labDao.upsertFormulaVersion(
                            FormulationVersionEntity(
                                syncUuid = "VER-${existing.formulaNo}-V${existing.versionNumber}",
                                formulaId = savedId,
                                formulaNo = existing.formulaNo,
                                productName = existing.productName,
                                versionNumber = existing.versionNumber,
                                previousVersionNumber = (existing.versionNumber - 1).coerceAtLeast(0),
                                status = "ARCHIVED",
                                changeReason = existing.changeReason.ifBlank { "النسخة المعتمدة المرجعية" },
                                diffSummaryJson = "لقطة مرجعية محفوظة قبل التعديل إلى v$nextVersion",
                                previousCostPerKg = existing.costPerKg,
                                newCostPerKg = existing.costPerKg,
                                historicalTotalCost = existing.historicalTotalCost.takeIf { it > 0.0 } ?: existing.totalCost,
                                calculatedPvc = existing.calculatedPvc,
                                calculatedDensity = existing.calculatedDensity,
                                currency = existing.currency,
                                ingredientsSnapshotJson = existing.ingredientsJson,
                                encryptedSnapshotCiphertext = existing.confidentialCiphertext,
                                createdBy = existing.createdBy,
                                approvedBy = existing.approvedBy,
                                approvedAt = existing.approvedAt,
                                createdAt = existing.updatedAt.takeIf { it > 0L } ?: existing.createdAt
                            )
                        )
                    }
                }

                labDao.upsertFormulaVersion(
                    FormulationVersionEntity(
                        syncUuid = "VER-${normalized.formulaNo}-V${nextVersion}-$now",
                        formulaId = savedId,
                        formulaNo = normalized.formulaNo,
                        productName = normalized.productName,
                        versionNumber = nextVersion,
                        previousVersionNumber = existing?.versionNumber ?: 0,
                        status = resolvedStatus,
                        changeReason = changeReason.ifBlank { "حفظ تركيبة" },
                        diffSummaryJson = diffSummary,
                        previousCostPerKg = existing?.costPerKg ?: normalized.costPerKg,
                        newCostPerKg = normalized.costPerKg,
                        historicalTotalCost = preservedHistTotal,
                        calculatedPvc = normalized.calculatedPvc,
                        calculatedDensity = normalized.calculatedDensity,
                        currency = normalized.currency,
                        ingredientsSnapshotJson = normalized.ingredientsJson,
                        encryptedSnapshotCiphertext = normalized.confidentialCiphertext,
                        createdBy = actorName,
                        createdAt = now
                    )
                )
            }

            dao.insertAuditLog(
                AuditLogEntity(
                    username = actorName,
                    action = "SAVE_FORMULATION",
                    detail = "حفظ تركيبة: ${normalized.productName} (#${normalized.formulaNo} v$nextVersion) | الحالة: $resolvedStatus | PVC: ${normalized.calculatedPvc}%"
                )
            )
            savedId
        }
    }

    /**
     * Explicit Admin / Approver workflow to APPROVE, REJECT, or request REVIEW on a formulation.
     * Locks historicalTotalCost and historicalCostPerKg upon approval so future raw material price spikes
     * NEVER rewrite historical approved baseline costs.
     */
    suspend fun decideFormulationApproval(
        formulaId: Long,
        decisionStatus: String, // "APPROVED", "REJECTED", "NEEDS_REVIEW"
        decisionNote: String,
        actorAccount: UserAccountEntity?,
        actorName: String
    ): Result<FormulationEntity> = runCatching {
        if (actorAccount != null && !com.example.util.LabSecurityManager.canApproveFormulas(actorAccount)) {
            com.example.util.LabSecurityManager.logLabAccessOrDenial(
                dao = dao,
                user = actorAccount,
                action = "APPROVE_FORMULATION",
                formulaCode = "FRM-$formulaId",
                allowed = false,
                note = "محاولة اعتماد/رفض تركيبة بدون صلاحية الاعتماد"
            )
            throw SecurityException("غير مصرح لك باعتماد أو رفض التركيبات الكيميائية.")
        }
        val cleanStatus = decisionStatus.trim().uppercase(Locale.US)
        require(cleanStatus in setOf("APPROVED", "REJECTED", "NEEDS_REVIEW")) { "حالة الاعتماد غير صالحة." }

        runInTransaction(
            operationName = "LAB_FORMULA_APPROVAL_DECISION",
            entityType = "lab_formulations",
            details = "قرار $cleanStatus للتركيبة #$formulaId"
        ) {
            val formula = dao.getFormulationById(formulaId)
                ?: throw IllegalArgumentException("التركيبة غير موجودة.")
            val now = System.currentTimeMillis()
            val approved = cleanStatus == "APPROVED"

            val updated = formula.copy(
                approvalStatus = cleanStatus,
                approvedForProduction = approved,
                approvedBy = if (approved) actorName else formula.approvedBy,
                approvedAt = if (approved) now else formula.approvedAt,
                historicalTotalCost = if (approved) formula.totalCost else formula.historicalTotalCost,
                historicalCostPerKg = if (approved) formula.costPerKg else formula.historicalCostPerKg,
                updatedBy = actorName,
                updatedAt = now,
                changeReason = decisionNote.ifBlank { if (approved) "اعتماد نهائي للإنتاج" else "ملاحظات مراجعة" }
            )
            dao.updateFormulation(updated)

            getLabDao()?.let { labDao ->
                val latestVer = labDao.getVersionsForFormulaDirect(formulaId)
                    .firstOrNull { it.versionNumber == formula.versionNumber }
                if (latestVer != null) {
                    labDao.upsertFormulaVersion(
                        latestVer.copy(
                            status = cleanStatus,
                            historicalTotalCost = if (approved) formula.totalCost else latestVer.historicalTotalCost,
                            approvedBy = if (approved) actorName else latestVer.approvedBy,
                            approvedAt = if (approved) now else latestVer.approvedAt,
                            changeReason = decisionNote.ifBlank { latestVer.changeReason }
                        )
                    )
                }
            }

            dao.insertAuditLog(
                AuditLogEntity(
                    username = actorName,
                    action = "FORMULATION_$cleanStatus",
                    detail = "قرار اعتماد التركيبة (${formula.formulaNo} v${formula.versionNumber}): $cleanStatus"
                )
            )
            updated
        }
    }

    /**
     * Records or updates a Lab Experiment pinned to an exact formulation version, integrating with QC decisions.
     */
    suspend fun recordLabExperiment(
        experiment: LabExperimentEntity,
        actorAccount: UserAccountEntity?
    ): Result<Long> = runCatching {
        if (actorAccount != null && !com.example.util.LabSecurityManager.canLogLabExperiments(actorAccount)) {
            com.example.util.LabSecurityManager.logLabAccessOrDenial(
                dao = dao,
                user = actorAccount,
                action = "LOG_LAB_EXPERIMENT",
                formulaCode = experiment.formulaNo,
                allowed = false,
                note = "محاولة تسجيل تجربة مخبرية بدون صلاحية"
            )
            throw SecurityException("غير مصرح لك بتسجيل التجارب المخبرية.")
        }
        val labDao = getLabDao() ?: throw IllegalStateException("قاعدة بيانات المختبر غير مهيأة.")
        val now = System.currentTimeMillis()
        val expNo = experiment.experimentNo.ifBlank { "EXP-${now.toString().takeLast(6)}" }
        val clean = experiment.copy(
            syncUuid = experiment.syncUuid.ifBlank { "EXP-$expNo-$now" },
            experimentNo = expNo,
            experimentDate = experiment.experimentDate.ifBlank { currentDateString() },
            createdAt = if (experiment.createdAt > 0L) experiment.createdAt else now,
            updatedAt = now
        )
        val savedId = labDao.upsertLabExperiment(clean)
        dao.insertAuditLog(
            AuditLogEntity(
                username = experiment.technicianName.ifBlank { actorAccount?.username ?: "Lab" },
                action = "LAB_EXPERIMENT_SAVED",
                detail = "تسجيل تجربة معملية $expNo للتركيبة ${experiment.formulaNo} v${experiment.versionNumber} | الحالة: ${experiment.status}"
            )
        )
        savedId
    }

    suspend fun decideLabExperimentQc(
        experimentId: Long,
        decisionStatus: String, // "PASSED", "REJECTED", "RETEST_REQUIRED"
        decisionNote: String,
        actorAccount: UserAccountEntity?,
        actorName: String
    ): Result<LabExperimentEntity> = runCatching {
        if (actorAccount != null && !com.example.util.LabSecurityManager.canDecideQcGate(actorAccount)) {
            com.example.util.LabSecurityManager.logLabAccessOrDenial(
                dao = dao,
                user = actorAccount,
                action = "QC_EXPERIMENT_DECISION",
                formulaCode = "EXP-$experimentId",
                allowed = false,
                note = "محاولة اعتماد أو رفض تجربة جودة بدون صلاحية"
            )
            throw SecurityException("غير مصرح لك باعتماد أو رفض نتائج فحوصات الجودة.")
        }
        val cleanDecision = decisionStatus.trim().uppercase(Locale.US)
        require(cleanDecision in setOf("PASSED", "REJECTED", "RETEST_REQUIRED")) { "قرار الجودة غير صالح." }
        val labDao = getLabDao() ?: throw IllegalStateException("قاعدة بيانات المختبر غير مهيأة.")
        val existing = labDao.getAllLabExperimentsDirect().firstOrNull { it.id == experimentId }
            ?: throw IllegalArgumentException("التجربة غير موجودة.")
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            status = cleanDecision,
            decision = decisionNote.trim().ifBlank { cleanDecision },
            decidedBy = actorName,
            decidedAt = now,
            notes = if (decisionNote.isNotBlank() && existing.notes.isBlank()) decisionNote.trim() else existing.notes,
            updatedAt = now
        )
        labDao.upsertLabExperiment(updated)
        dao.insertAuditLog(
            AuditLogEntity(
                username = actorName,
                action = "LAB_EXPERIMENT_$cleanDecision",
                detail = "قرار الجودة للتجربة ${existing.experimentNo} (${existing.formulaNo} v${existing.versionNumber}): $cleanDecision"
            )
        )
        updated
    }

    /**
     * Unified Raw Material Registry price updater with price-history tracking.
     * Leaves locked historical approved formula snapshots untouched while recalculating live delta impact.
     */
    suspend fun updateRawMaterialRegistryPrice(
        materialId: Long,
        newPrice: Double,
        currency: String = "EGP",
        supplierName: String = "",
        density: Double? = null,
        solidsPercent: Double? = null,
        reason: String = "تحديث سعر خامة في السجل الموحد",
        actorName: String,
        actorAccount: UserAccountEntity? = null
    ): Result<FactoryItemEntity> = runCatching {
        if (actorAccount != null && !com.example.util.LabSecurityManager.canCreateOrEditFormulas(actorAccount) &&
            !com.example.util.LabSecurityManager.canViewFormulaCosts(actorAccount)
        ) {
            throw SecurityException("غير مصرح لك بتعديل أسعار أو مواصفات المواد الخام.")
        }
        require(newPrice >= 0.0) { "السعر لا يمكن أن يكون سالباً." }
        val labDao = getLabDao() ?: throw IllegalStateException("قاعدة بيانات المختبر غير مهيأة.")
        val item = dao.getFactoryItemsDirect().firstOrNull { it.id == materialId }
            ?: throw IllegalArgumentException("المادة الخام غير موجودة.")
        val prevPrice = item.lastPurchasePrice.takeIf { it > 0.0 } ?: item.unitCostEgp
        val now = System.currentTimeMillis()
        val updated = item.copy(
            lastPurchasePrice = newPrice,
            lastPriceCurrency = currency.ifBlank { item.lastPriceCurrency },
            lastPriceUpdatedAt = now,
            supplierName = supplierName.ifBlank { item.supplierName },
            unitCostEgp = if (currency.equals("EGP", ignoreCase = true)) newPrice else item.unitCostEgp,
            density = density?.takeIf { it > 0.0 } ?: item.density,
            solidsPercent = solidsPercent?.coerceIn(0.0, 100.0) ?: item.solidsPercent
        )
        labDao.updateFactoryItem(updated)
        labDao.upsertPriceHistory(
            RawMaterialPriceHistoryEntity(
                syncUuid = "PRC-REG-${item.id}-$now",
                materialId = item.id,
                itemCode = item.itemCode,
                materialName = item.name,
                supplierName = updated.supplierName,
                unitPrice = newPrice,
                previousPrice = prevPrice,
                currency = updated.lastPriceCurrency,
                sourceType = "MANUAL_LAB",
                sourceDocNo = "REG-${now.toString().takeLast(5)}",
                effectiveDate = currentDateString(),
                recordedBy = actorName,
                createdAt = now
            )
        )
        dao.insertAuditLog(
            AuditLogEntity(
                username = actorName,
                action = "RAW_MATERIAL_PRICE_UPDATED",
                detail = "تحديث سعر الخامة ${item.name} في السجل الموحد"
            )
        )
        updated
    }

    /**
     * Stages vetted Excel workbook drafts into PMP strictly as NEEDS_REVIEW ("تحتاج إلى مراجعة واعتماد"),
     * deduplicates raw materials into factory_items, preserves original source snapshots, and records batch metadata.
     */
    suspend fun commitStagedExcelImport(
        analysis: com.example.util.LabExcelAnalyzer.WorkbookAnalysisResult,
        selectedDrafts: List<com.example.util.LabExcelAnalyzer.ImportedFormulaDraft>,
        archiveFilePath: String,
        actorName: String,
        actorAccount: UserAccountEntity?
    ): Result<Int> = runCatching {
        if (actorAccount != null && !com.example.util.LabSecurityManager.canCreateOrEditFormulas(actorAccount)) {
            com.example.util.LabSecurityManager.logLabAccessOrDenial(
                dao = dao,
                user = actorAccount,
                action = "IMPORT_EXCEL",
                formulaCode = analysis.fileName,
                allowed = false,
                note = "محاولة استيراد ملف تركيبات Excel بدون صلاحية المختبر"
            )
            throw SecurityException("غير مصرح لك باستيراد ملفات التركيبات.")
        }
        require(selectedDrafts.isNotEmpty()) { "يرجى اختيار تركيبة واحدة على الأقل للاستيراد." }

        runInTransaction(
            operationName = "LAB_EXCEL_STAGED_IMPORT",
            entityType = "lab_excel_imports",
            details = "استيراد ملف ${analysis.fileName}"
        ) {
            val labDao = getLabDao() ?: throw IllegalStateException("قاعدة بيانات المختبر غير مهيأة.")
            val existingItems = dao.getFactoryItemsDirect().toMutableList()

            // Step 1: Upsert deduplicated new raw materials cleanly into FactoryItemEntity
            for (cand in analysis.deduplicatedMaterials) {
                val alreadyExists = existingItems.any {
                    it.id == cand.matchedExistingItemId ||
                        it.name.trim().equals(cand.rawName.trim(), ignoreCase = true)
                }
                if (!alreadyExists && cand.isNewMaterial) {
                    val newMat = FactoryItemEntity(
                        name = cand.rawName.trim(),
                        unit = cand.unit.ifBlank { "كجم" },
                        category = "raw",
                        unitCostEgp = cand.importedPrice.coerceAtLeast(0.0),
                        lastPurchasePrice = cand.importedPrice.coerceAtLeast(0.0),
                        lastPriceCurrency = "EGP",
                        lastPriceUpdatedAt = if (cand.importedPrice > 0.0) System.currentTimeMillis() else 0L
                    )
                    val insertedMatId = dao.insertFactoryItem(newMat)
                    existingItems.add(newMat.copy(id = insertedMatId))
                }
            }

            // Step 2: Import each selected formula strictly as NEEDS_REVIEW (never overwriting existing approved versions!)
            var importedCount = 0
            for (draft in selectedDrafts) {
                // Re-bind materialIds after dedup creation
                val reboundIngredients = draft.ingredients.map { ing ->
                    val matched = existingItems.firstOrNull {
                        it.id == ing.materialId || it.name.trim().equals(ing.name.trim(), ignoreCase = true)
                    }
                    if (matched != null) ing.copy(materialId = matched.id, supplierName = matched.supplierName) else ing
                }
                val calc = com.example.util.PvcEngine.calculate(reboundIngredients)
                val ingredientsJson = com.example.util.PvcEngine.ingredientsToJson(reboundIngredients)

                val existingFormula = draft.matchingExistingFormulaId?.let { dao.getFormulationById(it) }
                val targetEntity = if (existingFormula != null) {
                    existingFormula.copy(
                        productName = draft.productName,
                        category = draft.category,
                        targetPvc = calc.calculatedPvc,
                        calculatedPvc = calc.calculatedPvc,
                        cpvcEstimated = calc.cpvcEstimated,
                        totalSolidsWeight = calc.totalSolidsWeight,
                        totalSolidsVolume = calc.totalSolidsVolume,
                        totalBatchWeight = calc.totalBatchWeight,
                        totalBatchVolume = calc.totalBatchVolume,
                        calculatedDensity = calc.calculatedDensity,
                        totalCost = calc.totalCost,
                        costPerKg = calc.costPerKg,
                        ingredientsJson = ingredientsJson,
                        technicalAnalysis = calc.technicalAnalysisText,
                        notes = "مستورد للمراجعة من ${analysis.fileName} (ورقة: ${draft.sourceSheetName})"
                    )
                } else {
                    FormulationEntity(
                        formulaNo = draft.suggestedFormulaNo,
                        productName = draft.productName,
                        category = draft.category,
                        paintType = "أكريليك مائي",
                        baseKind = "أبيض / أساس",
                        targetPvc = calc.calculatedPvc,
                        calculatedPvc = calc.calculatedPvc,
                        cpvcEstimated = calc.cpvcEstimated,
                        totalSolidsWeight = calc.totalSolidsWeight,
                        totalSolidsVolume = calc.totalSolidsVolume,
                        totalBatchWeight = calc.totalBatchWeight,
                        totalBatchVolume = calc.totalBatchVolume,
                        calculatedDensity = calc.calculatedDensity,
                        totalCost = calc.totalCost,
                        costPerKg = calc.costPerKg,
                        ingredientsJson = ingredientsJson,
                        technicalAnalysis = calc.technicalAnalysisText,
                        notes = "مستورد للمراجعة من ${analysis.fileName} (ورقة: ${draft.sourceSheetName})",
                        approvedForProduction = false,
                        approvalStatus = "NEEDS_REVIEW",
                        createdBy = actorName
                    )
                }

                saveFormulationWithVersioning(
                    formulation = targetEntity,
                    actorName = actorName,
                    actorAccount = actorAccount,
                    changeReason = "استيراد إكسل مرحلي (${analysis.fileName} / ${draft.sourceSheetName}) - بانتظار المراجعة",
                    initialStatus = "NEEDS_REVIEW"
                ).getOrThrow()
                importedCount++
            }

            val batchNow = System.currentTimeMillis()
            val importNo = "XLS-${batchNow.toString().takeLast(6)}"
            labDao.upsertExcelImport(
                LabExcelImportBatchEntity(
                    syncUuid = "XLS-${analysis.sha256Hex.take(12)}-$batchNow",
                    importNo = importNo,
                    fileName = analysis.fileName,
                    sheetCount = analysis.worksheetCount,
                    formulasDetected = analysis.candidateFormulas.size,
                    formulasImported = importedCount,
                    materialsMatched = analysis.deduplicatedMaterials.count { !it.isNewMaterial },
                    materialsCreated = analysis.deduplicatedMaterials.count { it.isNewMaterial },
                    externalLinksDetected = analysis.externalWorkbookLinks.size,
                    brokenRefsDetected = analysis.brokenReferenceIssues.size,
                    warningsCount = analysis.brokenReferenceIssues.size + analysis.externalWorkbookLinks.size + analysis.formulaVerificationMismatches.size,
                    approvalStatus = "NEEDS_REVIEW",
                    checksumSha256 = analysis.sha256Hex,
                    restrictedSummaryJson = (analysis.brokenReferenceIssues + analysis.externalWorkbookLinks + analysis.formulaVerificationMismatches)
                        .take(8)
                        .joinToString(" | "),
                    referenceArchivePath = archiveFilePath,
                    importedBy = actorName,
                    idempotencyKey = "XLS-$importNo",
                    createdAt = batchNow
                )
            )

            // Sanitized audit entry without exposing confidential ratios/recipes
            dao.insertAuditLog(
                AuditLogEntity(
                    username = actorName,
                    action = "LAB_EXCEL_IMPORT_STAGED",
                    detail = "استيراد $importedCount تركيبة بوضع NEEDS_REVIEW من ملف (${analysis.worksheetCount} أوراق عمل)"
                )
            )
            importedCount
        }
    }

    suspend fun deleteFormulation(
        id: Long,
        user: String,
        actorAccount: UserAccountEntity? = null
    ): Result<Unit> {
        if (actorAccount != null && !com.example.util.LabSecurityManager.canApproveFormulas(actorAccount)) {
            return Result.failure(SecurityException("غير مصرح لك بحذف التركيبات الكيميائية المعتمدة أو المسجلة."))
        }
        dao.deleteFormulation(id)
        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "DELETE_FORMULATION",
                detail = "حذف تركيبة معملية رقم #$id"
            )
        )
        return Result.success(Unit)
    }

    suspend fun saveChemicalSafety(item: ChemicalSafetyEntity, user: String): Result<Long> {
        val id = if (item.id == 0L) {
            dao.insertChemicalSafety(item)
        } else {
            dao.updateChemicalSafety(item)
            item.id
        }
        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "SAVE_CHEMICAL_SAFETY",
                detail = "تحديث بطاقة سلامة: ${item.chemicalName} | CAS: ${item.casNumber}"
            )
        )
        return Result.success(id)
    }

    suspend fun deleteChemicalSafety(id: Long, user: String): Result<Unit> {
        dao.deleteChemicalSafety(id)
        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "DELETE_CHEMICAL_SAFETY",
                detail = "حذف بطاقة سلامة كيميائية #$id"
            )
        )
        return Result.success(Unit)
    }

    /**
     * Strict Gate: Blocks creating normal factory production orders from unapproved formulations!
     * Never auto-flips approvedForProduction = true inside this method.
     */
    suspend fun createFactoryOrderFromFormulation(
        formulationId: Long,
        batchMultiplier: Double,
        user: String,
        actorAccount: UserAccountEntity? = null
    ): Result<Long> {
        val formulation = dao.getFormulationById(formulationId)
            ?: return Result.failure(Exception("لم يتم العثور على التركيبة المطلوبة"))

        val blockedStatus = formulation.approvalStatus.trim().uppercase(Locale.US) in setOf("NEEDS_REVIEW", "REJECTED", "ARCHIVED")
        val isApproved = !blockedStatus && (formulation.approvedForProduction || formulation.approvalStatus.equals("APPROVED", ignoreCase = true))
        if (!isApproved) {
            dao.insertAuditLog(
                AuditLogEntity(
                    username = user,
                    action = "BLOCKED_UNAPPROVED_FACTORY_ORDER",
                    detail = "منع أمر تشغيل مصنعي من تركيبة غير معتمدة (${formulation.formulaNo} | الحالة: ${formulation.approvalStatus})"
                )
            )
            return Result.failure(
                SecurityException("لا يمكن بدء الإنتاج من تركيبة غير معتمدة (${formulation.formulaNo} - حالتها: ${formulation.approvalStatus}). يجب اعتمادها أولاً.")
            )
        }

        val mult = if (batchMultiplier > 0) batchMultiplier else 1.0
        val ingredients = com.example.util.PvcEngine.jsonToIngredients(formulation.ingredientsJson)
        val today = currentDateString()
        val batchCode = "${formulation.formulaNo}-V${formulation.versionNumber}-${System.currentTimeMillis() % 10000}"

        // Record factory raw material intake / disbursement moves for each ingredient
        for (ing in ingredients) {
            val reqQty = ing.weight * mult
            val existingItem = dao.getFactoryItemsDirect().find {
                it.id == ing.materialId || it.name.trim().equals(ing.name.trim(), ignoreCase = true)
            }
            val itemId = existingItem?.id ?: dao.insertFactoryItem(
                FactoryItemEntity(
                    name = ing.name.trim(),
                    unit = ing.unit.ifBlank { "كجم" },
                    category = "raw",
                    unitCostEgp = ing.unitCost,
                    lastPurchasePrice = ing.unitCost
                )
            )

            dao.insertFactoryMove(
                FactoryMoveEntity(
                    moveDate = today,
                    kind = "صرف خامات تشغيل",
                    itemId = itemId,
                    itemName = ing.name.trim(),
                    qty = reqQty,
                    batch = batchCode,
                    note = "صرف لخلاط الإنتاج وفق تركيبة ${formulation.productName} v${formulation.versionNumber}",
                    actor = user,
                    counterparty = "مختبر PMP",
                    linked = true
                )
            )
        }

        // Record finished paint batch production order
        val finishedProdItem = dao.getFactoryItemsDirect().find { it.name.trim().equals(formulation.productName.trim(), ignoreCase = true) }
        val finishedId = finishedProdItem?.id ?: dao.insertFactoryItem(
            FactoryItemEntity(
                name = formulation.productName.trim(),
                unit = "كجم",
                category = "finished"
            )
        )

        val totalBatchWeight = formulation.totalBatchWeight * mult
        val moveId = dao.insertFactoryMove(
            FactoryMoveEntity(
                moveDate = today,
                kind = "أمر تشغيل إنتاج",
                itemId = finishedId,
                itemName = formulation.productName.trim(),
                qty = totalBatchWeight,
                batch = batchCode,
                note = "أمر تشغيل مصنعي معتمد من المختبر الكيميائي (${formulation.formulaNo} v${formulation.versionNumber})",
                actor = user,
                counterparty = "خط إنتاج PMP",
                linked = true
            )
        )

        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "FACTORY_WORK_ORDER_CREATED",
                detail = "توليد أمر تشغيل مصنعي #${moveId} للخلطة ${formulation.formulaNo} v${formulation.versionNumber} بحجم ${totalBatchWeight} كجم"
            )
        )

        return Result.success(moveId)
    }

    // --- Daily Production Batches (Firestore linked to Formulations) ---
    fun getProductionBatches(): Flow<List<ProductionBatchEntity>> = dao.getAllProductionBatches()

    suspend fun saveProductionBatch(
        batch: ProductionBatchEntity,
        actor: String
    ): Result<Long> {
        val id = if (batch.id == 0L) {
            dao.insertProductionBatch(batch)
        } else {
            dao.updateProductionBatch(batch)
            batch.id
        }

        val savedBatch = batch.copy(id = id)

        // Record finished item factory move
        val finishedProdItem = dao.getFactoryItemsDirect().find { it.name.trim().equals(savedBatch.productName.trim(), ignoreCase = true) }
        val finishedId = finishedProdItem?.id ?: dao.insertFactoryItem(
            FactoryItemEntity(
                name = savedBatch.productName.trim(),
                unit = "كجم",
                category = "finished"
            )
        )

        dao.insertFactoryMove(
            FactoryMoveEntity(
                moveDate = savedBatch.productionDate,
                kind = "إنتاج تام معتمد",
                itemId = finishedId,
                itemName = savedBatch.productName.trim(),
                qty = if (savedBatch.actualWeightKg > 0) savedBatch.actualWeightKg else savedBatch.targetWeightKg,
                machineId = savedBatch.machineId,
                machineName = savedBatch.machineName,
                batch = savedBatch.batchNumber,
                note = "تشغيلة إنتاج يومية مرتبطة بالتركيبة المعملية [${savedBatch.formulaNo}] - ${savedBatch.cartonsCount} ${savedBatch.packageUnit}",
                actor = actor,
                counterparty = "مستودع الإنتاج التام",
                linked = true
            )
        )

        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = "PRODUCTION_BATCH_LOGGED",
                detail = "تسجيل تشغيلة إنتاج رقم ${savedBatch.batchNumber} بالتركيبة ${savedBatch.formulaNo} - حالة الجودة: ${savedBatch.qualityStatus}"
            )
        )

        return Result.success(id)
    }

    suspend fun deleteProductionBatch(
        id: Long,
        batchNumber: String,
        actor: String
    ): Result<Unit> {
        dao.deleteProductionBatch(id)
        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = "PRODUCTION_BATCH_DELETED",
                detail = "حذف تشغيلة الإنتاج رقم $batchNumber"
            )
        )
        return Result.success(Unit)
    }
}

sealed class GoogleSignInBindingResult {
    data class Approved(val account: UserAccountEntity) : GoogleSignInBindingResult()
    data class PendingApproval(
        val account: UserAccountEntity,
        val message: String
    ) : GoogleSignInBindingResult()
}


