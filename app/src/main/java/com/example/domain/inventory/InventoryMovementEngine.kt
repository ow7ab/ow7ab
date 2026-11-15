package com.example.domain.inventory

import android.content.Context
import androidx.room.withTransaction
import com.example.data.local.*
import com.example.isSuperAdminAccount
import com.example.util.EnterpriseRbacManager
import com.example.util.ErpActionPermission
import com.example.util.ErpLifecycleEngine
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

data class InventoryMovementRequest(
    val movementType: InventoryMovementType,
    val warehouseId: Long,
    val itemCategory: InventoryItemCategory,
    val itemId: Long,
    val itemCode: String = "",
    val itemName: String,
    val quantity: Double,
    val adjustmentDirection: Int = +1, // Used only for STOCK_ADJUSTMENT (+1 or -1)
    val unit: String = "كرتونة",
    val unitCost: Double = 0.0,
    val currency: String = "EGP",
    val batchNumber: String = "",
    val expiryDate: String = "",
    val referenceDocType: String = "",
    val referenceDocId: Long? = null,
    val referenceDocNo: String = "",
    val reason: String = "",
    val qcApproved: Boolean = true,
    val quarantineHold: Boolean = false,
    val performedBy: String = "المدير",
    val approvedBy: String = "",
    val idempotencyKey: String = "",
    val syncUuid: String = UUID.randomUUID().toString(),
    val deviceId: String = "ANDROID",
    val documentNo: String = ""
)

data class TransferLineInput(
    val itemCategory: InventoryItemCategory,
    val itemId: Long,
    val itemCode: String = "",
    val itemName: String,
    val unit: String = "كرتونة",
    val requestedQty: Double,
    val unitCost: Double = 0.0,
    val batchNumber: String = "",
    val qcApprovedRequired: Boolean = true,
    val qcApproved: Boolean = true
)

data class TransferCreateRequest(
    val transferNo: String = "",
    val transferDate: String = "",
    val sourceWarehouseId: Long,
    val destinationWarehouseId: Long,
    val currency: String = "EGP",
    val notes: String = "",
    val createdBy: String,
    val items: List<TransferLineInput>,
    val idempotencyKey: String = "",
    val deviceId: String = "ANDROID"
)

data class TransferReceiveLineInput(
    val itemId: Long,
    val batchNumber: String = "",
    val receivedQty: Double,
    val rejectedQty: Double = 0.0
)

data class PackagingCartonBreakdown(
    val weightGrams: Int,
    val cansPerCarton: Int,
    val totalCans: Int,
    val fullCartons: Int,
    val looseCans: Int
)

/**
 * المحرك الموحد للحركات المخزنية وإدارة المخازن الثلاثة والتحويلات وقواعد التعبئة
 *
 * الضمانات الهندسية والمحاسبية:
 * 1. سجل إلحاقي غير قابل للحذف (Append-Only Ledger): أي تصحيح يتم بقيد عكسي فقط.
 * 2. عزل المخازن الثلاثة (RAW_MATERIALS / FINISHED_GOODS / SALES) ومنع تجاوز مسار الجودة QC.
 * 3. منع الرصيد السالب في جميع الحركات المنصرفة.
 * 4. عدم تكرار القيد (Idempotency) عبر syncUuid / idempotencyKey.
 * 5. تتبع البضاعة في الطريق (In Transit) وحجز الكميات المعتمدة في التحويلات.
 */
class InventoryMovementEngine(
    private val database: PmpDatabase
) {
    private val dao: PmpDao = database.pmpDao()
    private val invDao: ErpInventoryDao = database.erpInventoryDao()

    companion object {
        private const val EPSILON = 1e-6

        fun validateWarehousePolicy(
            warehouseClass: WarehouseClass,
            movementType: InventoryMovementType,
            itemCategory: InventoryItemCategory,
            qcApproved: Boolean,
            quarantineHold: Boolean
        ): String? {
            return when (warehouseClass) {
                WarehouseClass.RAW_MATERIALS -> {
                    if (itemCategory == InventoryItemCategory.FINISHED_GOOD) {
                        return "لا يُسمح بتخزين منتجات تامة الصنع داخل مخزن المواد الخام."
                    }
                    when (movementType) {
                        InventoryMovementType.SALES_ISSUE ->
                            "لا يُسمح ببيع المواد الخام مباشرة في فواتير المبيعات من هذا المخزن."
                        InventoryMovementType.PRODUCTION_RECEIPT ->
                            "مخرجات الإنتاج التام تُستلم في مخزن الإنتاج التام وليس مخزن المواد الخام."
                        InventoryMovementType.CUSTOMER_RETURN ->
                            "مرتجعات العملاء تُستلم في مخزن المبيعات وليس مخزن المواد الخام."
                        else -> null
                    }
                }
                WarehouseClass.FINISHED_GOODS -> {
                    if (itemCategory != InventoryItemCategory.FINISHED_GOOD) {
                        return "مخزن الإنتاج التام مخصص للمنتجات التامة الصنع فقط."
                    }
                    when (movementType) {
                        InventoryMovementType.SALES_ISSUE ->
                            "لا يُسمح بالبيع المباشر من مخزن الإنتاج التام؛ يجب التحويل أولاً إلى مخزن المبيعات بمستند تحويل معتمد."
                        InventoryMovementType.PURCHASE_RECEIPT,
                        InventoryMovementType.SUPPLIER_RETURN,
                        InventoryMovementType.PRODUCTION_ISSUE,
                        InventoryMovementType.PRODUCTION_RETURN ->
                            "هذه الحركة مخصصة لمخزن المواد الخام فقط وليست لمخزن الإنتاج التام."
                        InventoryMovementType.PRODUCTION_RECEIPT -> {
                            if (!qcApproved && !quarantineHold) {
                                "لا يُسمح بإضافة إنتاج تام إلى الرصيد المتاح قبل اجتياز مراقبة الجودة QC أو وضعه في الحجر."
                            } else {
                                null
                            }
                        }
                        InventoryMovementType.WAREHOUSE_TRANSFER_OUT -> {
                            if (!qcApproved) {
                                "لا يُسمح بتحويل منتج من مخزن الإنتاج التام قبل اعتماد مراقبة الجودة QC."
                            } else {
                                null
                            }
                        }
                        else -> null
                    }
                }
                WarehouseClass.SALES -> {
                    if (itemCategory != InventoryItemCategory.FINISHED_GOOD) {
                        return "مخزن المبيعات مخصص للمنتجات التامة الجاهزة للبيع فقط."
                    }
                    when (movementType) {
                        InventoryMovementType.PRODUCTION_RECEIPT ->
                            "لا يُسمح باستلام الإنتاج التام مباشرة في مخزن المبيعات؛ يجب الاستلام في مخزن الإنتاج التام بعد الجودة ثم التحويل الرسمي."
                        InventoryMovementType.PURCHASE_RECEIPT,
                        InventoryMovementType.SUPPLIER_RETURN,
                        InventoryMovementType.PRODUCTION_ISSUE,
                        InventoryMovementType.PRODUCTION_RETURN ->
                            "هذه الحركة خاصة بمخزن المواد الخام ولا تنطبق على مخزن المبيعات."
                        else -> null
                    }
                }
            }
        }

        fun validateTransferRoute(
            sourceClass: WarehouseClass,
            destClass: WarehouseClass,
            items: List<TransferLineInput>
        ): String? {
            if (items.isEmpty()) return "يجب إضافة صنف واحد على الأقل لمستند التحويل."
            if (items.any { it.requestedQty <= 0.0 }) return "جميع الكميات المطلوبة في التحويل يجب أن تكون أكبر من صفر."

            return when (sourceClass) {
                WarehouseClass.RAW_MATERIALS -> {
                    if (destClass != WarehouseClass.RAW_MATERIALS) {
                        "التحويل من مخزن المواد الخام يُسمح به فقط إلى مخزن مواد خام آخر."
                    } else if (items.any { it.itemCategory == InventoryItemCategory.FINISHED_GOOD }) {
                        "مخزن المواد الخام لا يحول منتجات تامة الصنع."
                    } else null
                }
                WarehouseClass.FINISHED_GOODS -> {
                    if (destClass == WarehouseClass.RAW_MATERIALS) {
                        "لا يُسمح بتحويل المنتجات التامة إلى مخزن المواد الخام."
                    } else if (items.any { it.qcApprovedRequired && !it.qcApproved }) {
                        "يوجد صنف لم يجتز اعتماد مراقبة الجودة QC ولا يمكن تحويله إلى مخزن المبيعات."
                    } else null
                }
                WarehouseClass.SALES -> {
                    if (destClass == WarehouseClass.RAW_MATERIALS) {
                        "لا يُسمح بالتحويل من مخزن المبيعات إلى مخزن المواد الخام."
                    } else null
                }
            }
        }
    }

    private fun todayDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun nextDocNo(prefix: String): String {
        val ts = System.currentTimeMillis().toString().takeLast(8)
        val suffix = UUID.randomUUID().toString().take(4).uppercase(Locale.US)
        return "$prefix-$ts-$suffix"
    }

    private fun signedQtyFor(request: InventoryMovementRequest): Double {
        val qty = abs(request.quantity)
        val sign = when (request.movementType) {
            InventoryMovementType.STOCK_ADJUSTMENT -> if (request.adjustmentDirection < 0) -1 else +1
            else -> request.movementType.defaultDirectionSign
        }
        return qty * sign
    }

    /**
     * تسجيل حركة مخزنية جديدة ضمن Transaction ذرية (Append-Only) مع تحديث الأرصدة ومنع التكرار
     */
    suspend fun postMovement(request: InventoryMovementRequest): Result<InventoryMovementEntity> {
        return runCatching {
            require(request.quantity > 0.0) { "الكمية يجب أن تكون أكبر من صفر." }
            require(request.itemName.isNotBlank()) { "اسم الصنف مطلوب." }

            database.withTransaction {
                postMovementInternal(request)
            }
        }
    }

    internal suspend fun postMovementInternal(request: InventoryMovementRequest): InventoryMovementEntity {
        val cleanIdemp = request.idempotencyKey.trim()
        if (cleanIdemp.isNotEmpty()) {
            val existingByIdemp = invDao.getInventoryMovementByIdempotencyKey(cleanIdemp)
            if (existingByIdemp != null) return existingByIdemp
        }
        val cleanSyncUuid = request.syncUuid.trim().ifBlank { UUID.randomUUID().toString() }
        invDao.getInventoryMovementBySyncUuid(cleanSyncUuid)?.let { return it }

        val warehouse = invDao.getWarehouseById(request.warehouseId)
            ?: throw IllegalArgumentException("المخزن رقم ${request.warehouseId} غير موجود.")
        val whClass = warehouse.resolveWarehouseClass()

        val policyError = validateWarehousePolicy(
            warehouseClass = whClass,
            movementType = request.movementType,
            itemCategory = request.itemCategory,
            qcApproved = request.qcApproved,
            quarantineHold = request.quarantineHold
        )
        if (policyError != null) {
            throw IllegalStateException(policyError)
        }

        val now = System.currentTimeMillis()
        val signedQty = signedQtyFor(request)
        val absQty = abs(request.quantity)
        var batchKey = request.batchNumber.trim()

        var currentBalance = invDao.getStockBalanceRow(
            warehouseId = warehouse.id,
            itemCategory = request.itemCategory.name,
            itemId = request.itemId,
            batchNumber = batchKey
        )

        // When batchNumber is not explicitly specified on an outbound movement, resolve FIFO from batched stock
        if (signedQty < 0.0 && batchKey.isEmpty() && ((currentBalance?.availableQty ?: 0.0) + signedQty < -EPSILON)) {
            val fifoCandidates = invDao.getAvailableStockBalanceRows(
                warehouseId = warehouse.id,
                itemCategory = request.itemCategory.name,
                itemId = request.itemId
            )
            val totalAcrossBatches = fifoCandidates.sumOf { it.availableQty }
            if (totalAcrossBatches >= absQty - EPSILON && fifoCandidates.isNotEmpty()) {
                val firstCandidate = fifoCandidates.first()
                if (firstCandidate.availableQty >= absQty - EPSILON) {
                    batchKey = firstCandidate.batchNumber
                    currentBalance = firstCandidate
                } else {
                    var remaining = absQty
                    var lastMovement: InventoryMovementEntity? = null
                    for ((idx, candidate) in fifoCandidates.withIndex()) {
                        if (remaining <= EPSILON) break
                        val take = minOf(remaining, candidate.availableQty)
                        if (take <= EPSILON) continue
                        lastMovement = postMovementInternal(
                            request.copy(
                                quantity = take,
                                batchNumber = candidate.batchNumber,
                                syncUuid = "$cleanSyncUuid-B$idx",
                                idempotencyKey = if (cleanIdemp.isNotBlank()) "$cleanIdemp-B$idx" else ""
                            )
                        )
                        remaining -= take
                    }
                    return lastMovement ?: throw IllegalStateException("تعذر إتمام الصرف FIFO للصنف ${request.itemName}.")
                }
            }
        }

        val currentOnHand = currentBalance?.onHandQty ?: 0.0
        val currentReserved = currentBalance?.reservedQty ?: 0.0
        val currentInTransit = currentBalance?.inTransitQty ?: 0.0
        val currentQuarantine = currentBalance?.quarantineQty ?: 0.0
        val currentAvailable = currentBalance?.availableQty ?: 0.0
        val currentAvgCost = currentBalance?.avgUnitCost ?: request.unitCost

        val newOnHand: Double
        val newQuarantine: Double
        val newAvailable: Double
        val newAvgCost: Double

        if (request.quarantineHold && signedQty > 0.0) {
            newOnHand = currentOnHand + absQty
            newQuarantine = currentQuarantine + absQty
            newAvailable = (newOnHand - currentReserved - newQuarantine).coerceAtLeast(0.0)
            newAvgCost = computeWeightedAvgCost(currentOnHand, currentAvgCost, absQty, request.unitCost)
        } else {
            if (signedQty < 0.0 && currentAvailable + signedQty < -EPSILON) {
                throw IllegalStateException(
                    "الرصيد المتاح غير كافٍ للصرف (${warehouse.name} - ${request.itemName}): المتاح $currentAvailable، المطلوب $absQty."
                )
            }
            newOnHand = currentOnHand + signedQty
            if (newOnHand < -EPSILON) {
                throw IllegalStateException("لا يُسمح برصيد مخزني سالب (${request.itemName}).")
            }
            newQuarantine = currentQuarantine
            newAvailable = (newOnHand - currentReserved - newQuarantine).coerceAtLeast(0.0)
            newAvgCost = if (signedQty > 0.0 && request.unitCost > 0.0) {
                computeWeightedAvgCost(currentOnHand, currentAvgCost, absQty, request.unitCost)
            } else {
                currentAvgCost
            }
        }

        val effectiveUnitCost = if (request.unitCost > 0.0) request.unitCost else currentAvgCost
        val docNo = request.documentNo.ifBlank { nextDocNo("IM") }
        val movement = InventoryMovementEntity(
            syncUuid = cleanSyncUuid,
            documentNo = docNo,
            movementType = request.movementType.name,
            warehouseId = warehouse.id,
            warehouseCode = warehouse.code.ifBlank { "WH-${warehouse.id}" },
            warehouseClass = whClass.name,
            itemCategory = request.itemCategory.name,
            itemId = request.itemId,
            itemCode = request.itemCode,
            itemName = request.itemName.trim(),
            quantity = absQty,
            signedQuantity = signedQty,
            unit = request.unit.ifBlank { "كرتونة" },
            unitCost = effectiveUnitCost,
            totalCost = absQty * effectiveUnitCost,
            currency = request.currency.ifBlank { warehouse.resolveCurrency() },
            batchNumber = batchKey,
            expiryDate = request.expiryDate.trim(),
            referenceDocType = request.referenceDocType.ifBlank { request.movementType.name },
            referenceDocId = request.referenceDocId,
            referenceDocNo = request.referenceDocNo,
            reversedMovementId = null,
            reason = request.reason.trim(),
            status = MovementRecordStatus.POSTED.name,
            qcApproved = request.qcApproved,
            idempotencyKey = cleanIdemp,
            performedBy = request.performedBy.ifBlank { "النظام" },
            approvedBy = request.approvedBy,
            deviceId = request.deviceId,
            recordVersion = 1,
            createdAt = now,
            updatedAt = now
        )

        val insertedId = invDao.insertInventoryMovement(movement)
        val savedMovement = movement.copy(id = insertedId)

        val updatedBalance = (currentBalance ?: WarehouseStockBalanceEntity(
            syncUuid = "BAL-${warehouse.id}-${request.itemCategory.name}-${request.itemId}-$batchKey",
            warehouseId = warehouse.id,
            warehouseClass = whClass.name,
            itemCategory = request.itemCategory.name,
            itemId = request.itemId,
            itemCode = request.itemCode,
            itemName = request.itemName.trim(),
            unit = request.unit.ifBlank { "كرتونة" },
            batchNumber = batchKey,
            expiryDate = request.expiryDate.trim(),
            currency = request.currency.ifBlank { warehouse.resolveCurrency() }
        )).copy(
            onHandQty = newOnHand.coerceAtLeast(0.0),
            reservedQty = currentReserved,
            inTransitQty = currentInTransit,
            quarantineQty = newQuarantine,
            availableQty = newAvailable,
            avgUnitCost = newAvgCost,
            lastMovementUuid = savedMovement.syncUuid,
            updatedAt = now
        )
        invDao.upsertStockBalance(updatedBalance)

        dao.insertAuditLog(
            AuditLogEntity(
                username = savedMovement.performedBy,
                action = "INVENTORY_MOVEMENT_${savedMovement.movementType}",
                detail = "${request.movementType.arabicLabel} | ${warehouse.name} | ${savedMovement.itemName} | الكمية: $signedQty | مستند: ${savedMovement.documentNo}"
            )
        )

        invDao.enqueueOutbox(
            SyncOutboxEntity(
                operationId = "OUTBOX-IM-${savedMovement.syncUuid}",
                entityType = "inventory_movements",
                entityUuid = savedMovement.syncUuid,
                action = "UPSERT",
                payloadJson = movementToJson(savedMovement).toString(),
                status = "PENDING",
                createdAt = now,
                updatedAt = now
            )
        )

        return savedMovement
    }

    /**
     * منع الحذف المباشر قطعياً (Append-Only Audit Trail)
     */
    @Suppress("UNUSED_PARAMETER")
    fun attemptDeleteMovement(movementId: Long): Result<Unit> {
        return Result.failure(
            UnsupportedOperationException("لا يُسمح بحذف الحركات المخزنية المعتمدة (Append-Only). يجب استخدام القيد العكسي (Reverse Movement) فقط.")
        )
    }

    /**
     * عكس حركة مخزنية معتمدة عبر إنشاء قيد عكسي تعويضي مع الإبقاء على السجل الأصلي للتدقيق
     */
    suspend fun reverseMovement(
        movementId: Long,
        reason: String,
        actor: String
    ): Result<InventoryMovementEntity> {
        return runCatching {
            val cleanReason = reason.trim()
            require(cleanReason.isNotEmpty()) { "يجب كتابة سبب واضح عند إنشاء القيد العكسي." }

            database.withTransaction {
                val original = invDao.getInventoryMovementById(movementId)
                    ?: throw IllegalArgumentException("الحركة المخزنية رقم $movementId غير موجودة.")
                if (original.status == MovementRecordStatus.REVERSED.name) {
                    throw IllegalStateException("تم عكس هذه الحركة مسبقاً ولا يمكن عكسها مرتين.")
                }

                val now = System.currentTimeMillis()
                val reversingSignedQty = -original.signedQuantity
                val batchKey = original.batchNumber.trim()
                val currentBalance = invDao.getStockBalanceRow(
                    warehouseId = original.warehouseId,
                    itemCategory = original.itemCategory,
                    itemId = original.itemId,
                    batchNumber = batchKey
                ) ?: throw IllegalStateException("لا يوجد سجل رصيد مطابق للحركة المطلوب عكسها.")

                if (reversingSignedQty < 0.0 && currentBalance.availableQty + reversingSignedQty < -EPSILON) {
                    throw IllegalStateException(
                        "لا يمكن عكس حركة الإدخال لأن الكمية المتاحة (${currentBalance.availableQty}) قد تم صرف جزء منها بالفعل."
                    )
                }

                val newOnHand = (currentBalance.onHandQty + reversingSignedQty).coerceAtLeast(0.0)
                val newAvailable = (newOnHand - currentBalance.reservedQty - currentBalance.quarantineQty).coerceAtLeast(0.0)

                invDao.markInventoryMovementStatus(original.id, MovementRecordStatus.REVERSED.name, now)

                val reversalMovement = InventoryMovementEntity(
                    syncUuid = UUID.randomUUID().toString(),
                    documentNo = nextDocNo("REV"),
                    movementType = InventoryMovementType.STOCK_ADJUSTMENT.name,
                    warehouseId = original.warehouseId,
                    warehouseCode = original.warehouseCode,
                    warehouseClass = original.warehouseClass,
                    itemCategory = original.itemCategory,
                    itemId = original.itemId,
                    itemCode = original.itemCode,
                    itemName = original.itemName,
                    quantity = abs(original.quantity),
                    signedQuantity = reversingSignedQty,
                    unit = original.unit,
                    unitCost = original.unitCost,
                    totalCost = abs(original.totalCost),
                    currency = original.currency,
                    batchNumber = batchKey,
                    expiryDate = original.expiryDate,
                    referenceDocType = "REVERSAL",
                    referenceDocId = original.id,
                    referenceDocNo = original.documentNo,
                    reversedMovementId = original.id,
                    reason = "قيد عكسي للحركة ${original.documentNo}: $cleanReason",
                    status = MovementRecordStatus.POSTED.name,
                    qcApproved = original.qcApproved,
                    idempotencyKey = "REV-${original.syncUuid}",
                    performedBy = actor.ifBlank { "المدير" },
                    approvedBy = actor.ifBlank { "المدير" },
                    deviceId = original.deviceId,
                    recordVersion = 1,
                    createdAt = now,
                    updatedAt = now
                )

                val revId = invDao.insertInventoryMovement(reversalMovement)
                val savedReversal = reversalMovement.copy(id = revId)

                invDao.upsertStockBalance(
                    currentBalance.copy(
                        onHandQty = newOnHand,
                        availableQty = newAvailable,
                        lastMovementUuid = savedReversal.syncUuid,
                        updatedAt = now
                    )
                )

                dao.insertAuditLog(
                    AuditLogEntity(
                        username = actor,
                        action = "INVENTORY_MOVEMENT_REVERSED",
                        detail = "قيد عكسي (${savedReversal.documentNo}) للحركة ${original.documentNo} | السبب: $cleanReason"
                    )
                )

                invDao.enqueueOutbox(
                    SyncOutboxEntity(
                        operationId = "OUTBOX-REV-${savedReversal.syncUuid}",
                        entityType = "inventory_movements",
                        entityUuid = savedReversal.syncUuid,
                        action = "UPSERT",
                        payloadJson = movementToJson(savedReversal).toString(),
                        status = "PENDING",
                        createdAt = now,
                        updatedAt = now
                    )
                )

                savedReversal
            }
        }
    }

    /**
     * فك الحجر عن كمية مقبولة بعد اعتماد الجودة QC
     */
    suspend fun releaseQuarantineToAvailable(
        warehouseId: Long,
        itemCategory: InventoryItemCategory,
        itemId: Long,
        batchNumber: String,
        releaseQty: Double,
        actor: String
    ): Result<WarehouseStockBalanceEntity> {
        return runCatching {
            require(releaseQty > 0.0) { "الكمية المطلوب الإفراج عنها يجب أن تكون أكبر من صفر." }
            database.withTransaction {
                val balance = invDao.getStockBalanceRow(
                    warehouseId = warehouseId,
                    itemCategory = itemCategory.name,
                    itemId = itemId,
                    batchNumber = batchNumber.trim()
                ) ?: throw IllegalStateException("لا يوجد رصيد محجوز مطابق في المخزن.")

                if (balance.quarantineQty + EPSILON < releaseQty) {
                    throw IllegalStateException("الكمية تحت الحجر (${balance.quarantineQty}) أقل من الكمية المطلوب الإفراج عنها ($releaseQty).")
                }

                val now = System.currentTimeMillis()
                val newQuarantine = (balance.quarantineQty - releaseQty).coerceAtLeast(0.0)
                val newAvailable = (balance.onHandQty - balance.reservedQty - newQuarantine).coerceAtLeast(0.0)
                val updated = balance.copy(
                    quarantineQty = newQuarantine,
                    availableQty = newAvailable,
                    updatedAt = now
                )
                invDao.upsertStockBalance(updated)
                dao.insertAuditLog(
                    AuditLogEntity(
                        username = actor,
                        action = "QC_QUARANTINE_RELEASED",
                        detail = "إفراج جودة QC عن $releaseQty ${balance.unit} من ${balance.itemName} (تشغيلة: ${balance.batchNumber})"
                    )
                )
                updated
            }
        }
    }

    // --- Warehouse Transfer Lifecycle (Draft -> Pending -> Approved -> In Transit -> Received) ---

    suspend fun createTransferDraft(request: TransferCreateRequest): Result<WarehouseTransferEntity> {
        return runCatching {
            require(request.sourceWarehouseId != request.destinationWarehouseId) {
                "لا يمكن إنشاء تحويل إلى نفس المخزن."
            }
            database.withTransaction {
                val cleanIdemp = request.idempotencyKey.trim()
                if (cleanIdemp.isNotEmpty()) {
                    invDao.getWarehouseTransferByIdempotencyKey(cleanIdemp)?.let { return@withTransaction it }
                }

                val src = invDao.getWarehouseById(request.sourceWarehouseId)
                    ?: throw IllegalArgumentException("المخزن المصدر غير موجود.")
                val dst = invDao.getWarehouseById(request.destinationWarehouseId)
                    ?: throw IllegalArgumentException("المخزن المستلم غير موجود.")

                val srcClass = src.resolveWarehouseClass()
                val dstClass = dst.resolveWarehouseClass()
                val routeError = validateTransferRoute(srcClass, dstClass, request.items)
                if (routeError != null) throw IllegalStateException(routeError)

                val now = System.currentTimeMillis()
                val transferNo = request.transferNo.ifBlank { nextDocNo("TRF-WH") }
                val transferUuid = UUID.randomUUID().toString()
                val totalCost = request.items.sumOf { it.requestedQty * it.unitCost }
                val timeline = JSONArray().apply {
                    put(JSONObject().apply {
                        put("status", WarehouseTransferStatus.DRAFT.name)
                        put("actor", request.createdBy)
                        put("timestamp", now)
                    })
                }.toString()

                val entity = WarehouseTransferEntity(
                    syncUuid = transferUuid,
                    transferNo = transferNo,
                    transferDate = request.transferDate.ifBlank { todayDate() },
                    sourceWarehouseId = src.id,
                    sourceWarehouseName = src.name,
                    sourceWarehouseClass = srcClass.name,
                    destinationWarehouseId = dst.id,
                    destinationWarehouseName = dst.name,
                    destinationWarehouseClass = dstClass.name,
                    status = WarehouseTransferStatus.DRAFT.name,
                    totalCost = totalCost,
                    currency = request.currency.ifBlank { src.resolveCurrency() },
                    notes = request.notes.trim(),
                    createdBy = request.createdBy.ifBlank { "أمين المخزن" },
                    timelineJson = timeline,
                    idempotencyKey = cleanIdemp,
                    deviceId = request.deviceId,
                    createdAt = now,
                    updatedAt = now
                )
                val trfId = invDao.insertOrUpdateWarehouseTransfer(entity)
                val savedTransfer = entity.copy(id = trfId)

                val lineEntities = request.items.map { item ->
                    WarehouseTransferItemEntity(
                        transferId = trfId,
                        transferUuid = transferUuid,
                        itemCategory = item.itemCategory.name,
                        itemId = item.itemId,
                        itemCode = item.itemCode,
                        itemName = item.itemName.trim(),
                        unit = item.unit,
                        requestedQty = item.requestedQty,
                        unitCost = item.unitCost,
                        totalCost = item.requestedQty * item.unitCost,
                        batchNumber = item.batchNumber.trim(),
                        qcApprovedRequired = item.qcApprovedRequired,
                        qcApproved = item.qcApproved
                    )
                }
                invDao.insertTransferItems(lineEntities)
                savedTransfer
            }
        }
    }

    suspend fun submitTransferForApproval(transferId: Long, actor: String): Result<WarehouseTransferEntity> {
        return runCatching {
            database.withTransaction {
                val transfer = invDao.getWarehouseTransferById(transferId)
                    ?: throw IllegalArgumentException("مستند التحويل غير موجود.")
                if (transfer.status != WarehouseTransferStatus.DRAFT.name) {
                    throw IllegalStateException("يمكن إرسال المسودات فقط للاعتماد.")
                }
                val now = System.currentTimeMillis()
                val updated = transfer.copy(
                    status = WarehouseTransferStatus.PENDING_APPROVAL.name,
                    reviewedBy = actor,
                    timelineJson = appendTimeline(transfer.timelineJson, WarehouseTransferStatus.PENDING_APPROVAL.name, actor, now),
                    recordVersion = transfer.recordVersion + 1,
                    updatedAt = now
                )
                invDao.updateWarehouseTransfer(updated)
                updated
            }
        }
    }

    suspend fun approveTransfer(
        transferId: Long,
        approver: UserAccountEntity?,
        allowCreatorOverride: Boolean = false
    ): Result<WarehouseTransferEntity> {
        return runCatching {
            require(approver != null && approver.active) { "يجب تسجيل دخول مستخدم نشط للاعتماد." }
            database.withTransaction {
                val transfer = invDao.getWarehouseTransferById(transferId)
                    ?: throw IllegalArgumentException("مستند التحويل غير موجود.")
                if (transfer.status != WarehouseTransferStatus.PENDING_APPROVAL.name &&
                    transfer.status != WarehouseTransferStatus.DRAFT.name
                ) {
                    throw IllegalStateException("لا يمكن اعتماد التحويل في حالته الحالية (${transfer.status}).")
                }

                // Segregation of Duties (SoD): Creator cannot approve own transfer unless Super Admin override
                if (!approver.isSuperAdminAccount() &&
                    !allowCreatorOverride &&
                    approver.username.equals(transfer.createdBy, ignoreCase = true)
                ) {
                    throw SecurityException("فصل المهام (SoD): لا يُسمح لمنشئ التحويل (${transfer.createdBy}) باعتماد مستنده الشخصي.")
                }

                val items = invDao.getTransferItemsByTransferId(transferId)
                if (items.isEmpty()) throw IllegalStateException("لا توجد أصناف داخل مستند التحويل.")

                val now = System.currentTimeMillis()
                items.forEach { item ->
                    if (item.qcApprovedRequired && !item.qcApproved) {
                        throw IllegalStateException("الصنف (${item.itemName}) لم يجتز اعتماد مراقبة الجودة QC.")
                    }
                    val balance = invDao.getStockBalanceRow(
                        warehouseId = transfer.sourceWarehouseId,
                        itemCategory = item.itemCategory,
                        itemId = item.itemId,
                        batchNumber = item.batchNumber.trim()
                    )
                    val avail = balance?.availableQty ?: 0.0
                    if (avail + EPSILON < item.requestedQty) {
                        throw IllegalStateException("الرصيد المتاح للصنف (${item.itemName}) غير كافٍ: المتاح $avail، المطلوب ${item.requestedQty}.")
                    }
                    // Reserve stock atomically at approval
                    val b = balance!!
                    invDao.upsertStockBalance(
                        b.copy(
                            reservedQty = b.reservedQty + item.requestedQty,
                            availableQty = (b.onHandQty - (b.reservedQty + item.requestedQty) - b.quarantineQty).coerceAtLeast(0.0),
                            updatedAt = now
                        )
                    )
                    invDao.updateTransferItem(item.copy(approvedQty = item.requestedQty))
                }

                val updated = transfer.copy(
                    status = WarehouseTransferStatus.APPROVED.name,
                    approvedBy = approver.username,
                    timelineJson = appendTimeline(transfer.timelineJson, WarehouseTransferStatus.APPROVED.name, approver.username, now),
                    recordVersion = transfer.recordVersion + 1,
                    updatedAt = now
                )
                invDao.updateWarehouseTransfer(updated)
                dao.insertAuditLog(
                    AuditLogEntity(
                        username = approver.username,
                        action = "TRANSFER_APPROVED",
                        detail = "اعتماد تحويل مخزني ${transfer.transferNo} من ${transfer.sourceWarehouseName} إلى ${transfer.destinationWarehouseName}"
                    )
                )
                updated
            }
        }
    }

    suspend fun dispatchTransfer(transferId: Long, dispatcher: String): Result<WarehouseTransferEntity> {
        return runCatching {
            database.withTransaction {
                val transfer = invDao.getWarehouseTransferById(transferId)
                    ?: throw IllegalArgumentException("مستند التحويل غير موجود.")
                if (transfer.status != WarehouseTransferStatus.APPROVED.name) {
                    throw IllegalStateException("يمكن شحن التحويلات المعتمدة فقط.")
                }

                val items = invDao.getTransferItemsByTransferId(transferId)
                val now = System.currentTimeMillis()
                val dstWarehouse = invDao.getWarehouseById(transfer.destinationWarehouseId)
                    ?: throw IllegalArgumentException("المخزن المستلم غير موجود.")

                items.forEach { item ->
                    val qty = if (item.approvedQty > 0.0) item.approvedQty else item.requestedQty
                    val batchKey = item.batchNumber.trim()

                    // 1. Release reservation on source balance before posting OUT movement
                    val srcBal = invDao.getStockBalanceRow(
                        warehouseId = transfer.sourceWarehouseId,
                        itemCategory = item.itemCategory,
                        itemId = item.itemId,
                        batchNumber = batchKey
                    ) ?: throw IllegalStateException("رصيد الصنف غير موجود في المخزن المصدر.")

                    invDao.upsertStockBalance(
                        srcBal.copy(
                            reservedQty = (srcBal.reservedQty - qty).coerceAtLeast(0.0),
                            availableQty = (srcBal.onHandQty - (srcBal.reservedQty - qty).coerceAtLeast(0.0) - srcBal.quarantineQty).coerceAtLeast(0.0),
                            updatedAt = now
                        )
                    )

                    // 2. Post WAREHOUSE_TRANSFER_OUT on source warehouse
                    postMovementInternal(
                        InventoryMovementRequest(
                            movementType = InventoryMovementType.WAREHOUSE_TRANSFER_OUT,
                            warehouseId = transfer.sourceWarehouseId,
                            itemCategory = InventoryItemCategory.fromRaw(item.itemCategory),
                            itemId = item.itemId,
                            itemCode = item.itemCode,
                            itemName = item.itemName,
                            quantity = qty,
                            unit = item.unit,
                            unitCost = item.unitCost,
                            currency = transfer.currency,
                            batchNumber = batchKey,
                            referenceDocType = "WAREHOUSE_TRANSFER",
                            referenceDocId = transfer.id,
                            referenceDocNo = transfer.transferNo,
                            reason = "تحويل صادر إلى ${transfer.destinationWarehouseName}",
                            qcApproved = item.qcApproved || !item.qcApprovedRequired,
                            performedBy = dispatcher,
                            approvedBy = transfer.approvedBy,
                            idempotencyKey = "TRF-OUT-${transfer.syncUuid}-${item.id}"
                        )
                    )

                    // 3. Increment inTransitQty on destination warehouse balance
                    val dstBal = invDao.getStockBalanceRow(
                        warehouseId = dstWarehouse.id,
                        itemCategory = item.itemCategory,
                        itemId = item.itemId,
                        batchNumber = batchKey
                    ) ?: WarehouseStockBalanceEntity(
                        syncUuid = "BAL-${dstWarehouse.id}-${item.itemCategory}-${item.itemId}-$batchKey",
                        warehouseId = dstWarehouse.id,
                        warehouseClass = transfer.destinationWarehouseClass,
                        itemCategory = item.itemCategory,
                        itemId = item.itemId,
                        itemCode = item.itemCode,
                        itemName = item.itemName,
                        unit = item.unit,
                        batchNumber = batchKey,
                        currency = dstWarehouse.resolveCurrency()
                    )
                    invDao.upsertStockBalance(
                        dstBal.copy(
                            inTransitQty = dstBal.inTransitQty + qty,
                            updatedAt = now
                        )
                    )

                    invDao.updateTransferItem(item.copy(shippedQty = qty))
                }

                val updated = transfer.copy(
                    status = WarehouseTransferStatus.IN_TRANSIT.name,
                    dispatchedBy = dispatcher,
                    dispatchedAt = now,
                    timelineJson = appendTimeline(transfer.timelineJson, WarehouseTransferStatus.IN_TRANSIT.name, dispatcher, now),
                    recordVersion = transfer.recordVersion + 1,
                    updatedAt = now
                )
                invDao.updateWarehouseTransfer(updated)
                updated
            }
        }
    }

    suspend fun receiveTransfer(
        transferId: Long,
        receiver: String,
        receivedLines: List<TransferReceiveLineInput>? = null
    ): Result<WarehouseTransferEntity> {
        return runCatching {
            database.withTransaction {
                val transfer = invDao.getWarehouseTransferById(transferId)
                    ?: throw IllegalArgumentException("مستند التحويل غير موجود.")
                if (transfer.status != WarehouseTransferStatus.IN_TRANSIT.name) {
                    throw IllegalStateException("لا يمكن استلام تحويل ليس في حالة (في الطريق / IN_TRANSIT).")
                }

                val items = invDao.getTransferItemsByTransferId(transferId)
                val now = System.currentTimeMillis()

                items.forEach { item ->
                    val batchKey = item.batchNumber.trim()
                    val overrideLine = receivedLines?.firstOrNull {
                        it.itemId == item.itemId && it.batchNumber.trim() == batchKey
                    }
                    val shipped = if (item.shippedQty > 0.0) item.shippedQty else item.approvedQty
                    val recQty = overrideLine?.receivedQty ?: shipped
                    val rejQty = overrideLine?.rejectedQty ?: 0.0
                    require(recQty >= 0.0 && rejQty >= 0.0) { "الكميات المستلمة والمرفوضة لا يمكن أن تكون سالبة." }
                    require(recQty + rejQty <= shipped + EPSILON) {
                        "مجموع المستلم ($recQty) والمرفوض ($rejQty) للصنف (${item.itemName}) يتجاوز المشحون ($shipped)."
                    }

                    // 1. Clear inTransitQty on destination warehouse
                    val dstBal = invDao.getStockBalanceRow(
                        warehouseId = transfer.destinationWarehouseId,
                        itemCategory = item.itemCategory,
                        itemId = item.itemId,
                        batchNumber = batchKey
                    )
                    if (dstBal != null) {
                        invDao.upsertStockBalance(
                            dstBal.copy(
                                inTransitQty = (dstBal.inTransitQty - shipped).coerceAtLeast(0.0),
                                updatedAt = now
                            )
                        )
                    }

                    // 2. Post WAREHOUSE_TRANSFER_IN for accepted receivedQty
                    if (recQty > 0.0) {
                        postMovementInternal(
                            InventoryMovementRequest(
                                movementType = InventoryMovementType.WAREHOUSE_TRANSFER_IN,
                                warehouseId = transfer.destinationWarehouseId,
                                itemCategory = InventoryItemCategory.fromRaw(item.itemCategory),
                                itemId = item.itemId,
                                itemCode = item.itemCode,
                                itemName = item.itemName,
                                quantity = recQty,
                                unit = item.unit,
                                unitCost = item.unitCost,
                                currency = transfer.currency,
                                batchNumber = batchKey,
                                referenceDocType = "WAREHOUSE_TRANSFER",
                                referenceDocId = transfer.id,
                                referenceDocNo = transfer.transferNo,
                                reason = "استلام تحويل وارد من ${transfer.sourceWarehouseName}",
                                qcApproved = true,
                                performedBy = receiver,
                                approvedBy = transfer.approvedBy,
                                idempotencyKey = "TRF-IN-${transfer.syncUuid}-${item.id}"
                            )
                        )
                    }

                    // 3. Quarantine rejected portion if any
                    if (rejQty > 0.0) {
                        postMovementInternal(
                            InventoryMovementRequest(
                                movementType = InventoryMovementType.WAREHOUSE_TRANSFER_IN,
                                warehouseId = transfer.destinationWarehouseId,
                                itemCategory = InventoryItemCategory.fromRaw(item.itemCategory),
                                itemId = item.itemId,
                                itemCode = item.itemCode,
                                itemName = item.itemName,
                                quantity = rejQty,
                                unit = item.unit,
                                unitCost = item.unitCost,
                                currency = transfer.currency,
                                batchNumber = batchKey,
                                referenceDocType = "WAREHOUSE_TRANSFER_DISCREPANCY",
                                referenceDocId = transfer.id,
                                referenceDocNo = transfer.transferNo,
                                reason = "عجز / تلف أثناء النقل للتحويل ${transfer.transferNo}",
                                qcApproved = false,
                                quarantineHold = true,
                                performedBy = receiver,
                                approvedBy = transfer.approvedBy,
                                idempotencyKey = "TRF-REJ-${transfer.syncUuid}-${item.id}"
                            )
                        )
                    }

                    invDao.updateTransferItem(item.copy(receivedQty = recQty, rejectedQty = rejQty))
                }

                val updated = transfer.copy(
                    status = WarehouseTransferStatus.RECEIVED.name,
                    receivedBy = receiver,
                    receivedAt = now,
                    timelineJson = appendTimeline(transfer.timelineJson, WarehouseTransferStatus.RECEIVED.name, receiver, now),
                    recordVersion = transfer.recordVersion + 1,
                    updatedAt = now
                )
                invDao.updateWarehouseTransfer(updated)
                dao.insertAuditLog(
                    AuditLogEntity(
                        username = receiver,
                        action = "TRANSFER_RECEIVED",
                        detail = "تم استلام التحويل المخزني ${transfer.transferNo} في ${transfer.destinationWarehouseName}"
                    )
                )
                updated
            }
        }
    }

    suspend fun rejectOrCancelTransfer(
        transferId: Long,
        actor: String,
        isReject: Boolean,
        reason: String
    ): Result<WarehouseTransferEntity> {
        return runCatching {
            require(reason.isNotBlank()) { "سبب الرفض أو الإلغاء مطلوب." }
            database.withTransaction {
                val transfer = invDao.getWarehouseTransferById(transferId)
                    ?: throw IllegalArgumentException("مستند التحويل غير موجود.")
                if (transfer.status == WarehouseTransferStatus.IN_TRANSIT.name ||
                    transfer.status == WarehouseTransferStatus.RECEIVED.name
                ) {
                    throw IllegalStateException("لا يمكن إلغاء أو رفض تحويل بعد خروجه من المخزن أو استلامه.")
                }
                val now = System.currentTimeMillis()
                if (transfer.status == WarehouseTransferStatus.APPROVED.name) {
                    val items = invDao.getTransferItemsByTransferId(transferId)
                    items.forEach { item ->
                        val qty = if (item.approvedQty > 0.0) item.approvedQty else item.requestedQty
                        val srcBal = invDao.getStockBalanceRow(
                            warehouseId = transfer.sourceWarehouseId,
                            itemCategory = item.itemCategory,
                            itemId = item.itemId,
                            batchNumber = item.batchNumber.trim()
                        )
                        if (srcBal != null) {
                            val newRes = (srcBal.reservedQty - qty).coerceAtLeast(0.0)
                            invDao.upsertStockBalance(
                                srcBal.copy(
                                    reservedQty = newRes,
                                    availableQty = (srcBal.onHandQty - newRes - srcBal.quarantineQty).coerceAtLeast(0.0),
                                    updatedAt = now
                                )
                            )
                        }
                    }
                }
                val targetStatus = if (isReject) WarehouseTransferStatus.REJECTED else WarehouseTransferStatus.CANCELLED
                val updated = transfer.copy(
                    status = targetStatus.name,
                    rejectionReason = reason.trim(),
                    timelineJson = appendTimeline(transfer.timelineJson, targetStatus.name, actor, now),
                    recordVersion = transfer.recordVersion + 1,
                    updatedAt = now
                )
                invDao.updateWarehouseTransfer(updated)
                updated
            }
        }
    }

    // --- Packaging Rules (250g -> 48, 900g -> 12, 4000g -> 4) ---

    suspend fun getPackagingRules(context: Context? = null): List<PackagingRuleEntity> {
        val dbRules = invDao.getPackagingRulesDirect()
        if (dbRules.isNotEmpty()) return dbRules
        val fallbackSpecs = if (context != null) {
            ErpLifecycleEngine.getPackagingRules(context)
        } else {
            ErpLifecycleEngine.DEFAULT_PACKAGING_RULES
        }
        val now = System.currentTimeMillis()
        val seeded = fallbackSpecs.map { spec ->
            PackagingRuleEntity(
                syncUuid = "PKG-RULE-${spec.weightGrams}",
                weightGrams = spec.weightGrams,
                label = spec.label,
                cansPerCarton = spec.cansPerCarton,
                defaultEmptyCanCostEgp = spec.defaultEmptyCanCostEgp,
                defaultLidCostEgp = spec.defaultLidCostEgp,
                defaultLabelCostEgp = spec.defaultLabelCostEgp,
                defaultCartonBoxCostEgp = spec.defaultCartonBoxCostEgp,
                active = true,
                updatedBy = "SYSTEM",
                updatedAt = now
            )
        }
        invDao.upsertPackagingRules(seeded)
        return invDao.getPackagingRulesDirect()
    }

    suspend fun updatePackagingRule(
        actor: UserAccountEntity?,
        weightGrams: Int,
        cansPerCarton: Int,
        label: String? = null,
        emptyCanCostEgp: Double? = null,
        lidCostEgp: Double? = null,
        labelCostEgp: Double? = null,
        cartonBoxCostEgp: Double? = null
    ): Result<PackagingRuleEntity> {
        return runCatching {
            val authorized = actor != null && actor.active && (
                actor.isSuperAdminAccount() ||
                    EnterpriseRbacManager.hasGranularPermission(
                        actor,
                        EnterpriseRbacManager.PermissionTokens.MODULE_PACKAGING,
                        ErpActionPermission.ADMINISTRATIVE_SETTINGS
                    )
                )
            if (!authorized) {
                throw SecurityException("تعديل معدلات التعبئة القياسية مقتصر على مدير النظام أو المفوض بصلاحية إعدادات التعبئة.")
            }
            require(weightGrams > 0 && cansPerCarton > 0) {
                "وزن العبوة وعدد العبوات في الكرتونة يجب أن يكونا أكبر من صفر."
            }

            database.withTransaction {
                val now = System.currentTimeMillis()
                val existing = invDao.getPackagingRuleByWeight(weightGrams)
                val updated = (existing ?: PackagingRuleEntity(
                    syncUuid = "PKG-RULE-$weightGrams",
                    weightGrams = weightGrams,
                    label = label ?: "$weightGrams جرام ($cansPerCarton علبة/كرتونة)",
                    cansPerCarton = cansPerCarton
                )).copy(
                    label = label ?: "$weightGrams جرام ($cansPerCarton علبة/كرتونة)",
                    cansPerCarton = cansPerCarton,
                    defaultEmptyCanCostEgp = emptyCanCostEgp ?: existing?.defaultEmptyCanCostEgp ?: 4.5,
                    defaultLidCostEgp = lidCostEgp ?: existing?.defaultLidCostEgp ?: 1.2,
                    defaultLabelCostEgp = labelCostEgp ?: existing?.defaultLabelCostEgp ?: 0.8,
                    defaultCartonBoxCostEgp = cartonBoxCostEgp ?: existing?.defaultCartonBoxCostEgp ?: 18.0,
                    updatedBy = actor.username,
                    recordVersion = (existing?.recordVersion ?: 0) + 1,
                    updatedAt = now
                )
                val rowId = invDao.upsertPackagingRule(updated)
                val finalRule = if (updated.id > 0) updated else updated.copy(id = rowId)
                dao.insertAuditLog(
                    AuditLogEntity(
                        username = actor.username,
                        action = "PACKAGING_RULE_UPDATED",
                        detail = "تحديث قاعدة التعبئة ${weightGrams} جم إلى $cansPerCarton وحدة/كرتونة"
                    )
                )
                finalRule
            }
        }
    }

    suspend fun convertCansToCartons(weightGrams: Int, totalCans: Int): PackagingCartonBreakdown {
        val rule = invDao.getPackagingRuleByWeight(weightGrams)
        val perCarton = rule?.cansPerCarton ?: when (weightGrams) {
            250 -> 48
            4000 -> 4
            else -> 12
        }
        val safeCans = totalCans.coerceAtLeast(0)
        return PackagingCartonBreakdown(
            weightGrams = weightGrams,
            cansPerCarton = perCarton,
            totalCans = safeCans,
            fullCartons = safeCans / perCarton,
            looseCans = safeCans % perCarton
        )
    }

    suspend fun convertCartonsToCans(weightGrams: Int, cartons: Int): Int {
        val rule = invDao.getPackagingRuleByWeight(weightGrams)
        val perCarton = rule?.cansPerCarton ?: when (weightGrams) {
            250 -> 48
            4000 -> 4
            else -> 12
        }
        return cartons.coerceAtLeast(0) * perCarton
    }

    private fun computeWeightedAvgCost(
        existingQty: Double,
        existingCost: Double,
        addedQty: Double,
        addedCost: Double
    ): Double {
        if (addedCost <= 0.0) return existingCost
        val safeExistingQty = existingQty.coerceAtLeast(0.0)
        if (safeExistingQty <= EPSILON || existingCost <= 0.0) return addedCost
        val totalQty = safeExistingQty + addedQty
        return if (totalQty > EPSILON) {
            ((safeExistingQty * existingCost) + (addedQty * addedCost)) / totalQty
        } else {
            addedCost
        }
    }

    private fun appendTimeline(existingJson: String, status: String, actor: String, ts: Long): String {
        val arr = runCatching { JSONArray(existingJson) }.getOrElse { JSONArray() }
        arr.put(JSONObject().apply {
            put("status", status)
            put("actor", actor)
            put("timestamp", ts)
        })
        return arr.toString()
    }

    private fun movementToJson(m: InventoryMovementEntity): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("syncUuid", m.syncUuid)
        put("documentNo", m.documentNo)
        put("movementType", m.movementType)
        put("warehouseId", m.warehouseId)
        put("warehouseClass", m.warehouseClass)
        put("itemCategory", m.itemCategory)
        put("itemId", m.itemId)
        put("itemName", m.itemName)
        put("quantity", m.quantity)
        put("signedQuantity", m.signedQuantity)
        put("unit", m.unit)
        put("unitCost", m.unitCost)
        put("totalCost", m.totalCost)
        put("currency", m.currency)
        put("batchNumber", m.batchNumber)
        put("status", m.status)
        put("performedBy", m.performedBy)
        put("createdAt", m.createdAt)
    }
}
