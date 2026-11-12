package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PmpRepository(
    private val dao: PmpDao,
    private val database: PmpDatabase? = null
) {

    fun getDao(): PmpDao = dao

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

        val id = dao.insertMovement(
            MovementEntity(
                txDate = date,
                cashbox = cashbox,
                currency = currency,
                direction = direction,
                amount = amount,
                description = safeDesc,
                reference = safeRef,
                createdBy = user
            )
        )
        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "CREATE_MOVEMENT",
                detail = "حركة #$id | $direction $amount $currency في $cashbox | مرجع: $safeRef"
            )
        )
        Result.success(id)
    }

    suspend fun deleteMovement(id: Long, user: String): Result<Unit> = runInTransaction(
        operationName = "DELETE_MOVEMENT",
        entityType = "movements",
        details = "حذف الحركة المالية #$id"
    ) {
        dao.deleteMovementById(id)
        dao.insertAuditLog(AuditLogEntity(username = user, action = "DELETE_MOVEMENT", detail = "حذف الحركة المالية #$id"))
        Result.success(Unit)
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

        val ref = "PAY-CUST-$customerId-${System.currentTimeMillis() % 100000}"
        val movId = dao.insertMovement(
            MovementEntity(
                txDate = date,
                cashbox = cashbox,
                currency = currency,
                direction = "وارد",
                amount = amount,
                description = "تحصيل وسداد دفعة من العميل: $customerName ${if (note.isNotBlank()) "• $note" else ""}",
                reference = ref,
                createdBy = user
            )
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
        dao.insertMovement(
            MovementEntity(
                txDate = date,
                cashbox = sourceCashbox,
                currency = sourceCurrency,
                direction = "منصرف",
                amount = sourceAmount,
                description = "تحويل صادر $reference إلى $destinationCashbox: $fullDescription",
                reference = "TRF:$reference:OUT",
                createdBy = user
            )
        )

        // 2. Record Incoming Movement on Destination
        dao.insertMovement(
            MovementEntity(
                txDate = date,
                cashbox = destinationCashbox,
                currency = destinationCurrency,
                direction = "وارد",
                amount = destinationAmount,
                description = "تحويل وارد $reference من $sourceCashbox: $fullDescription",
                reference = "TRF:$reference:IN",
                createdBy = user
            )
        )

        // 3. Record Transfer Log
        val transferId = dao.insertTransfer(
            TransferEntity(
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
        )

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
        if (items.isEmpty()) return@runInTransaction Result.failure(Exception("يجب إضافة بند واحد على الأقل"))

        val subtotal = items.sumOf { it.lineTotal }
        val finalDiscount = discount.coerceIn(0.0, subtotal)
        val total = subtotal - finalDiscount

        val invoiceNumber = "INV-" + SimpleDateFormat("yyMMdd-HHmmss", Locale.getDefault()).format(Date())

        val invoiceId = dao.insertInvoice(
            InvoiceEntity(
                invoiceNo = invoiceNumber,
                customerId = customerId,
                customerName = customerName,
                invoiceDate = date,
                currency = currency,
                subtotal = subtotal,
                discount = finalDiscount,
                total = total,
                notes = notes,
                createdBy = user
            )
        )

        // Insert line items & record stock movements for products
        val updatedItems = items.map { it.copy(invoiceId = invoiceId) }
        dao.insertInvoiceItems(updatedItems)

        for (item in updatedItems) {
            if (item.productId != null && item.warehouseId != null) {
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
            dao.insertMovement(
                MovementEntity(
                    txDate = date,
                    cashbox = paymentCashbox,
                    currency = currency,
                    direction = "وارد",
                    amount = actualPay,
                    description = "دفعة فاتورة $invoiceNumber - $customerName",
                    reference = "INV:$invoiceNumber:P$payId",
                    createdBy = user
                )
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
        if (amount <= 0) return@runInTransaction Result.failure(Exception("المبلغ غير صالح"))

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

        dao.insertMovement(
            MovementEntity(
                txDate = date,
                cashbox = cashbox,
                currency = currency,
                direction = "وارد",
                amount = amount,
                description = "دفعة فاتورة $invoiceNo - $customerName: $note",
                reference = "INV:$invoiceNo:P$payId",
                createdBy = user
            )
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

    suspend fun cancelInvoice(invoiceId: Long, reason: String, user: String): Result<Unit> = runInTransaction(
        operationName = "CANCEL_INVOICE_COMPENSATING",
        entityType = "invoices",
        details = "إلغاء الفاتورة #$invoiceId بقيد عكسي مخزني ومالي: $reason"
    ) {
        val invoice = dao.getInvoiceById(invoiceId) ?: return@runInTransaction Result.failure(Exception("الفاتورة غير موجودة"))
        if (invoice.status == "cancelled") return@runInTransaction Result.failure(Exception("الفاتورة ملغاة مسبقاً"))

        val cleanReason = reason.trim().ifBlank { "إلغاء فاتورة معتمد" }
        val reversalDate = currentDateString()

        // 1. تحديث حالة الفاتورة إلى ملغاة مع حفظ سبب الإلغاء
        dao.updateInvoice(
            invoice.copy(
                status = "cancelled",
                cancelReason = cleanReason
            )
        )

        // 2. القيود العكسية للمخزون (Compensating Stock Moves): إرجاع كافة الأصناف المباعة في الفاتورة إلى مخازنها الأصلية
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

        // 3. القيود العكسية للخزينة والمدفوعات (Compensating Financial Movements): عكس أي دفعات مسجلة على الفاتورة في الخزنة
        val invoicePayments = dao.getInvoicePaymentsByInvoiceIdDirect(invoiceId)
        var reversedPaymentsTotal = 0.0
        for (payment in invoicePayments) {
            if (payment.status == "active" && payment.amount > 0.0) {
                dao.updateInvoicePayment(
                    payment.copy(
                        status = "cancelled",
                        note = "${payment.note} | ملغاة بقيد عكسي لإلغاء الفاتورة ${invoice.invoiceNo}: $cleanReason"
                    )
                )
                if (payment.cashbox.isNotBlank() && !payment.cashbox.contains("تسوية إدارية")) {
                    dao.insertMovement(
                        MovementEntity(
                            txDate = reversalDate,
                            cashbox = payment.cashbox,
                            currency = invoice.currency.ifBlank { "SDG" },
                            direction = "منصرف",
                            amount = payment.amount,
                            description = "قيد عكسي لاسترداد دفعة فاتورة ملغاة ${invoice.invoiceNo} - ${invoice.customerName}: $cleanReason",
                            reference = "REV:${invoice.invoiceNo}:P${payment.id}",
                            createdBy = user
                        )
                    )
                }
                reversedPaymentsTotal += payment.amount
            }
        }

        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "CANCEL_INVOICE",
                detail = "إلغاء الفاتورة ${invoice.invoiceNo} بقيد عكسي شامل (استرجاع $restoredItemsCount بنود مخزنية وعكس مدفوعات بقيمة $reversedPaymentsTotal ${invoice.currency}): $cleanReason"
            )
        )
        Result.success(Unit)
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
        val id = dao.insertWarehouse(
            WarehouseEntity(
                name = name.trim(),
                currency = currency,
                linkedCashbox = linkedCashbox,
                warehouseType = warehouseType
            )
        )
        dao.insertAuditLog(AuditLogEntity(username = user, action = "ADD_WAREHOUSE", detail = "$name [$currency - $linkedCashbox]"))
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
        dao.updateWarehouse(
            WarehouseEntity(
                id = id,
                name = trimmed,
                active = active,
                currency = currency,
                linkedCashbox = linkedCashbox,
                warehouseType = warehouseType
            )
        )
        dao.insertAuditLog(AuditLogEntity(username = user, action = "UPDATE_WAREHOUSE", detail = "$trimmed [نشط: $active, $currency, $linkedCashbox]"))
        return Result.success(Unit)
    }

    suspend fun deleteWarehouse(id: Long, user: String): Result<Unit> {
        dao.deleteWarehouse(id)
        dao.insertAuditLog(AuditLogEntity(username = user, action = "DELETE_WAREHOUSE", detail = "حذف المخزن #$id"))
        return Result.success(Unit)
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

    suspend fun deleteProduct(id: Long, productName: String, user: String): Result<Unit> {
        dao.deleteProduct(id)
        dao.insertAuditLog(AuditLogEntity(username = user, action = "DELETE_PRODUCT", detail = "حذف صنف من النظام: $productName (#$id)"))
        return Result.success(Unit)
    }

    suspend fun resetWarehouseStock(warehouseId: Long, warehouseName: String, user: String): Result<Unit> {
        dao.clearStockMovesByWarehouse(warehouseId)
        dao.insertAuditLog(AuditLogEntity(username = user, action = "ZERO_WAREHOUSE", detail = "تصفير كامل أرصدة مخزن: $warehouseName (#$warehouseId)"))
        return Result.success(Unit)
    }

    suspend fun resetCashboxBalance(cashboxName: String, user: String): Result<Unit> {
        dao.clearMovementsByCashbox(cashboxName)
        dao.insertAuditLog(AuditLogEntity(username = user, action = "ZERO_CASHBOX", detail = "تصفير كامل حركات ورصيد خزنة: $cashboxName"))
        return Result.success(Unit)
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

    suspend fun deleteFactoryItem(itemId: Long, itemName: String, actor: String = "إدارة المصنع"): Result<Unit> {
        dao.deleteFactoryItemById(itemId)
        dao.insertAuditLog(
            AuditLogEntity(
                username = actor,
                action = "DELETE_FACTORY_ITEM",
                detail = "حذف صنف من المصنع: $itemName (ID: $itemId)"
            )
        )
        return Result.success(Unit)
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
        val cleanIdentity = identity.trim().lowercase(Locale.ROOT)
        if (cleanIdentity.isBlank()) return null
        val allUsers = dao.getAllUserAccounts().first()
        return allUsers.firstOrNull { user ->
            user.username.lowercase(Locale.ROOT) == cleanIdentity ||
                (user.phoneOrEmail.isNotBlank() && user.phoneOrEmail.lowercase(Locale.ROOT) == cleanIdentity) ||
                user.userCode.lowercase(Locale.ROOT) == cleanIdentity
        }
    }

    suspend fun authenticateUserCredentials(
        emailOrUsername: String,
        passwordOrPin: String
    ): Result<UserAccountEntity> {
        val cleanIdentity = emailOrUsername.trim().lowercase(Locale.ROOT)
        val cleanSecret = passwordOrPin.trim()
        if (cleanIdentity.isBlank()) {
            return Result.failure(Exception("يرجى إدخال البريد الإلكتروني أو اسم المستخدم أو كود الحساب"))
        }
        if (cleanSecret.length < 4) {
            return Result.failure(Exception("كلمة المرور أو رمز الدخول يجب أن يتكون من 4 خانات على الأقل"))
        }

        val allUsers = dao.getAllUserAccounts().first()
        val matchedUser = allUsers.firstOrNull { user ->
            user.username.lowercase(Locale.ROOT) == cleanIdentity ||
                (user.phoneOrEmail.isNotBlank() && user.phoneOrEmail.lowercase(Locale.ROOT) == cleanIdentity) ||
                user.userCode.lowercase(Locale.ROOT) == cleanIdentity
        }

        if (matchedUser != null) {
            if (matchedUser.syncStatus == "PENDING_MANAGER_APPROVAL") {
                return Result.failure(
                    Exception("لا يُسمح بالدخول: هذا الحساب بانتظار موافقة حساب المدير وتحديد الصلاحيات أولاً")
                )
            }
            if (!matchedUser.active) {
                return Result.failure(Exception("هذا الحساب موقوف أو غير مفعل حالياً، يرجى مراجعة مدير النظام"))
            }
            val isValidPassword = com.example.util.PasswordSecurityUtil.verifyPassword(cleanSecret, matchedUser.pinOrPassword)
            if (!isValidPassword) {
                return Result.failure(Exception("كلمة المرور أو رمز الدخول غير صحيح للحساب ${matchedUser.fullName}"))
            }
            var authenticatedUser = matchedUser
            if (com.example.util.PasswordSecurityUtil.needsUpgradeToPbkdf2(matchedUser.pinOrPassword)) {
                val upgradedHash = com.example.util.PasswordSecurityUtil.hashPassword(cleanSecret)
                authenticatedUser = matchedUser.copy(pinOrPassword = upgradedHash)
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

    suspend fun updateEmployeePermissionsAndRole(
        account: UserAccountEntity,
        newRole: String,
        newBranch: String,
        newPermissions: String,
        newLinkedUid: String,
        approveAccount: Boolean = true,
        actor: String
    ): Result<UserAccountEntity> {
        val cleanPerms = newPermissions.trim()
        val updated = account.copy(
            role = newRole.trim().ifBlank { account.role },
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
                detail = "اعتماد/تحديث صلاحيات الحساب ${account.fullName} (${account.userCode}) -> [$cleanPerms]"
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

    // --- PMP Science & Chemistry Lab (Formulations & Safety) ---
    fun getFormulations(): Flow<List<FormulationEntity>> = dao.getAllFormulations()
    fun getChemicalSafety(): Flow<List<ChemicalSafetyEntity>> = dao.getAllChemicalSafety()

    suspend fun saveFormulation(formulation: FormulationEntity, user: String): Result<Long> {
        val id = if (formulation.id == 0L) {
            dao.insertFormulation(formulation)
        } else {
            dao.updateFormulation(formulation)
            formulation.id
        }
        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "SAVE_FORMULATION",
                detail = "حفظ تركيبة: ${formulation.productName} (#${formulation.formulaNo}) | PVC: ${formulation.calculatedPvc}%"
            )
        )
        return Result.success(id)
    }

    suspend fun deleteFormulation(id: Long, user: String): Result<Unit> {
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

    suspend fun createFactoryOrderFromFormulation(
        formulationId: Long,
        batchMultiplier: Double,
        user: String
    ): Result<Long> {
        val formulation = dao.getFormulationById(formulationId)
            ?: return Result.failure(Exception("لم يتم العثور على التركيبة المطلوبة"))

        val mult = if (batchMultiplier > 0) batchMultiplier else 1.0
        val ingredients = com.example.util.PvcEngine.jsonToIngredients(formulation.ingredientsJson)
        val today = currentDateString()
        val batchCode = "${formulation.formulaNo}-${System.currentTimeMillis() % 10000}"

        // Record factory raw material intake / disbursement moves for each ingredient
        for (ing in ingredients) {
            val reqQty = ing.weight * mult
            val existingItem = dao.getFactoryItemsDirect().find { it.name.trim().equals(ing.name.trim(), ignoreCase = true) }
            val itemId = existingItem?.id ?: dao.insertFactoryItem(
                FactoryItemEntity(
                    name = ing.name.trim(),
                    unit = "كجم",
                    category = "raw"
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
                    note = "صرف لخلاط الإنتاج وفق تركيبة ${formulation.productName}",
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
                note = "أمر تشغيل مصنعي معتمد من المختبر الكيميائي (PVC: ${formulation.calculatedPvc}%)",
                actor = user,
                counterparty = "خط إنتاج PMP",
                linked = true
            )
        )

        dao.updateFormulation(formulation.copy(approvedForProduction = true))
        dao.insertAuditLog(
            AuditLogEntity(
                username = user,
                action = "FACTORY_WORK_ORDER_CREATED",
                detail = "توليد أمر تشغيل مصنعي #${moveId} للخلطة ${formulation.formulaNo} بحجم ${totalBatchWeight} كجم"
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


