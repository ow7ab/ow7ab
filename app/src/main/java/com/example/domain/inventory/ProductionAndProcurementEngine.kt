package com.example.domain.inventory

import androidx.room.withTransaction
import com.example.data.local.*
import com.example.domain.accounting.AccountingLedgerEngine
import com.example.isSystemManager
import com.example.util.ErpLifecycleEngine
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.round

data class PurchaseReceiptInput(
    val poNo: String = "",
    val supplierName: String,
    val warehouseId: Long? = null,
    val materialId: Long,
    val materialCode: String = "",
    val materialName: String,
    val unit: String = "كجم",
    val batchNumber: String = "",
    val expiryDate: String = "",
    val orderedQty: Double = 0.0,
    val receivedQty: Double,
    val acceptedQty: Double,
    val rejectedQty: Double = 0.0,
    val unitCost: Double = 0.0,
    val currency: String = "EGP",
    val discrepancyReason: String = "",
    val notes: String = "",
    val performedBy: String = "أمين مخزن الخام",
    val approver: UserAccountEntity? = null,
    val idempotencyKey: String = ""
)

data class ProductionOrderCreateInput(
    val orderNo: String = "",
    val formulaId: Long? = null,
    val productId: Long? = null,
    val productName: String,
    val paintColor: String = "أبيض",
    val packageWeightGrams: Int = 900,
    val targetCartons: Int,
    val machineName: String = "خلاط رئيسي 1",
    val productionEngineer: String = "مهندس الإنتاج",
    val approvedBy: String = "المدير",
    val rawWarehouseId: Long? = null,
    val fgWarehouseId: Long? = null,
    val notes: String = "",
    val idempotencyKey: String = ""
)

data class MaterialIssueLineInput(
    val itemId: Long,
    val itemCategory: InventoryItemCategory = InventoryItemCategory.RAW_MATERIAL,
    val itemName: String,
    val qty: Double,
    val returnedQty: Double = 0.0,
    val wastedQty: Double = 0.0,
    val unit: String = "كجم",
    val unitCost: Double = 0.0,
    val batchNumber: String = ""
)

data class QcProductionInspectionInput(
    val productionOrderId: Long,
    val inspectorName: String = "مسؤول الجودة",
    val measuredDensity: Double = 0.0,
    val measuredViscosity: String = "110 KU",
    val colorMatchPassed: Boolean = true,
    val goodCans: Int,
    val rejectedCans: Int = 0,
    val reworkCans: Int = 0,
    val wastedRawKg: Double = 0.0,
    val forceReject: Boolean = false,
    val notes: String = "",
    val idempotencyKey: String = ""
)

/**
 * منسق المرحلة الثالثة:
 * 1. المشتريات -> الفحص والاستلام (GRN) -> مخزن المواد الخام (المقبول متاح / المرفوض حجر صحي)
 * 2. أوامر الإنتاج (BOM + قواعد التعبئة 250=48 / 900=12 / 4000=4) -> صرف الخامات -> الفاقد والمرتجع
 * 3. بوابة مراقبة الجودة (QC Gate) -> اعتماد المطابق إلى مخزن الإنتاج التام وحجز غير المطابق
 */
class ProductionAndProcurementEngine(
    private val database: PmpDatabase,
    private val movementEngine: InventoryMovementEngine
) {
    private val dao = database.pmpDao()
    private val invDao = database.erpInventoryDao()
    private val labDao = database.labDao()
    private val accountingEngine = AccountingLedgerEngine(database)

    companion object {
        private const val EPSILON = 1e-6
    }

    private fun todayDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun nextDocNo(prefix: String): String {
        val ts = System.currentTimeMillis().toString().takeLast(7)
        val suffix = UUID.randomUUID().toString().take(4).uppercase(Locale.US)
        return "$prefix-$ts-$suffix"
    }

    suspend fun resolveWarehouseForClass(
        targetClass: WarehouseClass,
        explicitId: Long? = null
    ): WarehouseEntity {
        if (explicitId != null && explicitId > 0L) {
            val found = invDao.getWarehouseById(explicitId)
            if (found != null) {
                require(found.resolveWarehouseClass() == targetClass) {
                    "المخزن المحدد (${found.name}) ينتمي إلى فئة ${found.resolveWarehouseClass().arabicLabel} وليس ${targetClass.arabicLabel}."
                }
                return found
            }
        }
        val existing = dao.getWarehousesDirect().firstOrNull {
            it.active && it.resolveWarehouseClass() == targetClass
        }
        if (existing != null) return existing

        val seeded = when (targetClass) {
            WarehouseClass.RAW_MATERIALS -> WarehouseEntity(
                name = "مخزن المواد الخام داخل المصنع (مصر)",
                currency = "EGP",
                linkedCashbox = "خزنة مصر",
                warehouseType = "مواد خام",
                warehouseClass = WarehouseClass.RAW_MATERIALS.name,
                code = "WH-RAW-EG",
                branch = "المصنع",
                allowDirectSales = false,
                allowProductionReceipt = false
            )
            WarehouseClass.FINISHED_GOODS -> WarehouseEntity(
                name = "مخزن الإنتاج التام داخل المصنع",
                currency = "EGP",
                linkedCashbox = "خزنة مصر",
                warehouseType = "داخل المصنع/إنتاج",
                warehouseClass = WarehouseClass.FINISHED_GOODS.name,
                code = "WH-FG-EG",
                branch = "المصنع",
                allowDirectSales = false,
                allowProductionReceipt = true
            )
            WarehouseClass.SALES -> WarehouseEntity(
                name = "مخزن مبيعات الخرطوم",
                currency = "SDG",
                linkedCashbox = "خزنة السودان",
                warehouseType = "مبيعات",
                warehouseClass = WarehouseClass.SALES.name,
                code = "WH-SLS-KH",
                branch = "الخرطوم",
                allowDirectSales = true,
                allowProductionReceipt = false
            )
        }
        val insertedId = dao.insertWarehouse(seeded)
        return seeded.copy(id = insertedId)
    }

    /**
     * 1. استلام وفحص شحنة مشتريات إلى مخزن المواد الخام
     * - يطابق الكمية المستلمة = المقبولة + المرفوضة
     * - يضيف الكمية المقبولة إلى الرصيد المتاح في مخزن المواد الخام عبر PURCHASE_RECEIPT
     * - يضع الكمية المرفوضة تحت الحجر (quarantineHold = true) فلا تدخل الإنتاج
     */
    suspend fun receivePurchaseToRawMaterials(input: PurchaseReceiptInput): Result<PurchaseReceiptEntity> {
        return runCatching {
            require(input.materialName.isNotBlank()) { "اسم المادة الخام مطلوب." }
            require(input.receivedQty > 0.0) { "الكمية المستلمة يجب أن تكون أكبر من صفر." }
            require(input.acceptedQty >= 0.0 && input.rejectedQty >= 0.0) { "الكميات المقبولة والمرفوضة لا يمكن أن تكون سالبة." }
            require(abs(input.receivedQty - (input.acceptedQty + input.rejectedQty)) < 0.001) {
                "الكمية المستلمة (${input.receivedQty}) يجب أن تساوي مجموع المقبولة (${input.acceptedQty}) والمرفوضة (${input.rejectedQty})."
            }

            val hasDiscrepancy = input.rejectedQty > 0.0 ||
                (input.orderedQty > 0.0 && abs(input.receivedQty - input.orderedQty) > 0.001)
            if (hasDiscrepancy && input.discrepancyReason.isBlank()) {
                throw IllegalArgumentException("يجب توضيح سبب الرفض أو العجز/الزيادة عند وجود اختلاف في الاستلام.")
            }

            database.withTransaction {
                val cleanIdemp = input.idempotencyKey.trim()
                if (cleanIdemp.isNotEmpty()) {
                    invDao.getPurchaseReceiptByIdempotencyKey(cleanIdemp)?.let { return@withTransaction it }
                }

                val rawWh = resolveWarehouseForClass(WarehouseClass.RAW_MATERIALS, input.warehouseId)
                val now = System.currentTimeMillis()
                val grnNo = nextDocNo("GRN")
                val poNo = input.poNo.ifBlank { nextDocNo("PO") }
                val batchNo = input.batchNumber.ifBlank { "BAT-RAW-${now.toString().takeLast(6)}" }
                val cleanSupplier = input.supplierName.ifBlank { "مورد خامات معتمد" }

                val qcStatus = when {
                    input.acceptedQty <= 0.0 && input.rejectedQty > 0.0 -> "REJECTED"
                    input.rejectedQty > 0.0 -> "PARTIAL"
                    else -> "APPROVED"
                }

                if (input.acceptedQty > 0.0) {
                    movementEngine.postMovementInternal(
                        InventoryMovementRequest(
                            movementType = InventoryMovementType.PURCHASE_RECEIPT,
                            warehouseId = rawWh.id,
                            itemCategory = InventoryItemCategory.RAW_MATERIAL,
                            itemId = input.materialId,
                            itemCode = input.materialCode.ifBlank { "RAW-${input.materialId}" },
                            itemName = input.materialName.trim(),
                            quantity = input.acceptedQty,
                            unit = input.unit.ifBlank { "كجم" },
                            unitCost = input.unitCost.coerceAtLeast(0.0),
                            currency = input.currency.ifBlank { "EGP" },
                            batchNumber = batchNo,
                            expiryDate = input.expiryDate.trim(),
                            referenceDocType = "PURCHASE_RECEIPT",
                            referenceDocNo = grnNo,
                            reason = "استلام مشتريات مقبول ($poNo) من $cleanSupplier",
                            qcApproved = true,
                            quarantineHold = false,
                            performedBy = input.performedBy.ifBlank { "أمين مخزن الخام" },
                            approvedBy = input.approver?.username ?: input.performedBy,
                            idempotencyKey = "GRN-ACC-${cleanIdemp.ifBlank { grnNo }}"
                        )
                    )

                    dao.insertFactoryMove(
                        FactoryMoveEntity(
                            moveDate = todayDate(),
                            kind = "RAW_RECEIPT",
                            itemId = input.materialId,
                            itemName = input.materialName.trim(),
                            qty = input.acceptedQty,
                            batch = batchNo,
                            note = "استلام $grnNo ($poNo) - مقبول: ${input.acceptedQty}، مرفوض: ${input.rejectedQty}",
                            actor = input.performedBy,
                            counterparty = cleanSupplier,
                            syncUuid = "FM-GRN-${grnNo}"
                        )
                    )
                }

                if (input.rejectedQty > 0.0) {
                    movementEngine.postMovementInternal(
                        InventoryMovementRequest(
                            movementType = InventoryMovementType.PURCHASE_RECEIPT,
                            warehouseId = rawWh.id,
                            itemCategory = InventoryItemCategory.RAW_MATERIAL,
                            itemId = input.materialId,
                            itemCode = input.materialCode.ifBlank { "RAW-${input.materialId}" },
                            itemName = input.materialName.trim(),
                            quantity = input.rejectedQty,
                            unit = input.unit.ifBlank { "كجم" },
                            unitCost = input.unitCost.coerceAtLeast(0.0),
                            currency = input.currency.ifBlank { "EGP" },
                            batchNumber = "$batchNo-REJ",
                            expiryDate = input.expiryDate.trim(),
                            referenceDocType = "PURCHASE_QUARANTINE",
                            referenceDocNo = grnNo,
                            reason = "حجر كمية مرفوضة عند الاستلام ($grnNo): ${input.discrepancyReason}",
                            qcApproved = false,
                            quarantineHold = true,
                            performedBy = input.performedBy,
                            idempotencyKey = "GRN-REJ-${cleanIdemp.ifBlank { grnNo }}"
                        )
                    )
                }

                val receipt = PurchaseReceiptEntity(
                    grnNo = grnNo,
                    poNo = poNo,
                    receiptDate = todayDate(),
                    supplierName = cleanSupplier,
                    warehouseId = rawWh.id,
                    materialId = input.materialId,
                    materialCode = input.materialCode.ifBlank { "RAW-${input.materialId}" },
                    materialName = input.materialName.trim(),
                    unit = input.unit.ifBlank { "كجم" },
                    batchNumber = batchNo,
                    expiryDate = input.expiryDate.trim(),
                    orderedQty = input.orderedQty,
                    receivedQty = input.receivedQty,
                    acceptedQty = input.acceptedQty,
                    rejectedQty = input.rejectedQty,
                    unitCost = input.unitCost,
                    totalCost = input.acceptedQty * input.unitCost,
                    currency = input.currency.ifBlank { "EGP" },
                    discrepancyReason = input.discrepancyReason.trim(),
                    qcStatus = qcStatus,
                    stockPosted = input.acceptedQty > 0.0,
                    notes = input.notes.trim(),
                    performedBy = input.performedBy.ifBlank { "أمين مخزن الخام" },
                    approvedBy = input.approver?.username ?: if (!hasDiscrepancy) input.performedBy else "",
                    idempotencyKey = cleanIdemp.ifBlank { "PR-$grnNo" },
                    createdAt = now,
                    updatedAt = now
                )
                val insertedId = invDao.upsertPurchaseReceipt(receipt)
                val savedReceipt = receipt.copy(id = if (receipt.id > 0) receipt.id else insertedId)
                accountingEngine.postPurchaseReceiptJournalInternal(savedReceipt)

                // Update unified Raw Materials Registry (FactoryItemEntity) & append price history WITHOUT mutating locked historical formula costs
                if (input.acceptedQty > 0.0 && input.unitCost > 0.0) {
                    val existingMat = dao.getFactoryItemsDirect().firstOrNull {
                        it.id == input.materialId || it.name.trim().equals(input.materialName.trim(), ignoreCase = true)
                    }
                    if (existingMat != null) {
                        val prevPrice = existingMat.lastPurchasePrice.takeIf { it > 0.0 } ?: existingMat.unitCostEgp
                        val priceChanged = kotlin.math.abs(prevPrice - input.unitCost) > 0.0001
                        labDao.updateFactoryItem(
                            existingMat.copy(
                                lastPurchasePrice = input.unitCost,
                                lastPriceCurrency = input.currency.ifBlank { "EGP" },
                                lastPriceUpdatedAt = now,
                                supplierName = cleanSupplier,
                                unitCostEgp = if (input.currency.equals("EGP", ignoreCase = true)) input.unitCost else existingMat.unitCostEgp
                            )
                        )
                        if (priceChanged || prevPrice <= 0.0) {
                            labDao.upsertPriceHistory(
                                RawMaterialPriceHistoryEntity(
                                    syncUuid = "PRC-$grnNo-${existingMat.id}-$now",
                                    materialId = existingMat.id,
                                    itemCode = existingMat.itemCode,
                                    materialName = existingMat.name,
                                    supplierName = cleanSupplier,
                                    unitPrice = input.unitCost,
                                    previousPrice = prevPrice,
                                    currency = input.currency.ifBlank { "EGP" },
                                    sourceType = "PURCHASE_GRN",
                                    sourceDocNo = grnNo,
                                    effectiveDate = todayDate(),
                                    recordedBy = input.performedBy.ifBlank { "المشتريات" },
                                    createdAt = now
                                )
                            )
                        }
                    }
                }

                savedReceipt
            }
        }
    }

    /**
     * 2. إنشاء واعتماد أمر إنتاج مرتبط بقواعد التعبئة (250=48 / 900=12 / 4000=4) والتركيبة الكيميائية BOM
     */
    suspend fun createProductionOrder(input: ProductionOrderCreateInput): Result<ProductionOrderEntity> {
        return runCatching {
            require(input.productName.isNotBlank()) { "اسم المنتج المطلوب إنتاجه مطلوب." }
            require(input.targetCartons > 0) { "عدد الكراتين المستهدف يجب أن يكون أكبر من صفر." }

            database.withTransaction {
                val cleanIdemp = input.idempotencyKey.trim()
                if (cleanIdemp.isNotEmpty()) {
                    invDao.getProductionOrderByIdempotencyKey(cleanIdemp)?.let { return@withTransaction it }
                }

                val rawWh = resolveWarehouseForClass(WarehouseClass.RAW_MATERIALS, input.rawWarehouseId)
                val fgWh = resolveWarehouseForClass(WarehouseClass.FINISHED_GOODS, input.fgWarehouseId)

                val rule = invDao.getPackagingRuleByWeight(input.packageWeightGrams)
                val cansPerCarton = rule?.cansPerCarton ?: when (input.packageWeightGrams) {
                    250 -> 48
                    4000 -> 4
                    else -> 12
                }
                val targetCans = input.targetCartons * cansPerCarton
                val targetWeightKg = (targetCans * input.packageWeightGrams.toDouble()) / 1000.0

                val formula = input.formulaId?.let { dao.getFormulationById(it) }
                if (input.formulaId != null) {
                    if (formula == null) {
                        throw IllegalArgumentException("التركيبة الكيميائية المحددة غير موجودة.")
                    }
                    val blockedStatus = formula.approvalStatus.trim().uppercase(Locale.US) in setOf("NEEDS_REVIEW", "REJECTED", "ARCHIVED")
                    val isApproved = !blockedStatus && (formula.approvedForProduction || formula.approvalStatus.equals("APPROVED", ignoreCase = true))
                    if (!isApproved) {
                        dao.insertAuditLog(
                            AuditLogEntity(
                                username = input.approvedBy.ifBlank { input.productionEngineer },
                                action = "BLOCKED_UNAPPROVED_FORMULA_PRODUCTION",
                                detail = "تم منع إنشاء أمر إنتاج من تركيبة غير معتمدة (${formula.formulaNo} | الحالة: ${formula.approvalStatus})"
                            )
                        )
                        throw SecurityException("لا يمكن إصدار أمر إنتاج من تركيبة غير معتمدة (${formula.formulaNo} - الحالة: ${formula.approvalStatus}). يجب اعتمادها أولاً.")
                    }
                }
                val bomArr = JSONArray()
                if (formula != null) {
                    val reqs = ErpLifecycleEngine.calculateTheoreticalBomRequirements(formula, targetWeightKg)
                    reqs.forEach { line ->
                        bomArr.put(
                            JSONObject().apply {
                                put("materialName", line.materialName)
                                put("materialType", line.materialType)
                                put("unit", line.unit)
                                put("theoreticalRequiredKg", line.theoreticalRequiredKg)
                                put("unitCostEgp", line.unitCostEgp)
                            }
                        )
                    }
                }

                val now = System.currentTimeMillis()
                val orderNo = input.orderNo.ifBlank { nextDocNo("PRD") }
                val batchNumber = "PB-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now))}-${now.toString().takeLast(3)}"

                val order = ProductionOrderEntity(
                    orderNo = orderNo,
                    orderDate = todayDate(),
                    formulaId = formula?.id,
                    formulaNo = formula?.formulaNo ?: "STD-BOM",
                    productId = input.productId,
                    productName = input.productName.trim(),
                    paintColor = input.paintColor.ifBlank { formula?.baseKind ?: "أبيض" },
                    packageWeightGrams = input.packageWeightGrams,
                    cansPerCarton = cansPerCarton,
                    targetCartons = input.targetCartons,
                    targetCans = targetCans,
                    targetWeightKg = targetWeightKg,
                    bomLinesJson = bomArr.toString(),
                    machineName = input.machineName.ifBlank { "خلاط رئيسي 1" },
                    productionEngineer = input.productionEngineer.ifBlank { "مهندس الإنتاج" },
                    status = "APPROVED",
                    batchNumber = batchNumber,
                    rawWarehouseId = rawWh.id,
                    fgWarehouseId = fgWh.id,
                    approvedBy = input.approvedBy.ifBlank { "المدير" },
                    idempotencyKey = cleanIdemp.ifBlank { "PRD-$orderNo" },
                    createdAt = now,
                    updatedAt = now
                )
                val id = invDao.upsertProductionOrder(order)
                dao.insertAuditLog(
                    AuditLogEntity(
                        username = order.approvedBy,
                        action = "PRODUCTION_ORDER_CREATED",
                        detail = "أمر إنتاج $orderNo: ${order.productName} ($targetCans علبة = ${order.targetCartons} كرتونة @ ${order.packageWeightGrams} جم)"
                    )
                )
                order.copy(id = id)
            }
        }
    }

    /**
     * 3. صرف المواد الخام ومواد التعبئة لأمر الإنتاج من مخزن المواد الخام (مع منع الرصيد السالب + دعم الإرجاع)
     */
    suspend fun issueRawMaterialsForOrder(
        orderId: Long,
        materials: List<MaterialIssueLineInput>,
        actor: String = "أمين مخزن الخام"
    ): Result<ProductionOrderEntity> {
        return runCatching {
            require(materials.isNotEmpty()) { "يجب تحديد مادة خام واحدة على الأقل للصرف." }
            database.withTransaction {
                val order = invDao.getProductionOrderById(orderId)
                    ?: throw IllegalArgumentException("أمر الإنتاج رقم $orderId غير موجود.")
                if (order.status !in listOf("DRAFT", "APPROVED", "MATERIALS_ISSUED")) {
                    throw IllegalStateException("لا يمكن صرف خامات لأمر الإنتاج في حالته الحالية (${order.status}).")
                }

                val rawWh = resolveWarehouseForClass(WarehouseClass.RAW_MATERIALS, order.rawWarehouseId.takeIf { it > 0L })
                var totalWaste = 0.0

                for (line in materials) {
                    require(line.qty > 0.0) { "كمية الصرف للصنف (${line.itemName}) يجب أن تكون أكبر من صفر." }

                    movementEngine.postMovementInternal(
                        InventoryMovementRequest(
                            movementType = InventoryMovementType.PRODUCTION_ISSUE,
                            warehouseId = rawWh.id,
                            itemCategory = line.itemCategory,
                            itemId = line.itemId,
                            itemName = line.itemName.trim(),
                            quantity = line.qty,
                            unit = line.unit.ifBlank { "كجم" },
                            unitCost = line.unitCost,
                            currency = "EGP",
                            batchNumber = line.batchNumber.trim(),
                            referenceDocType = "PRODUCTION_ORDER",
                            referenceDocId = order.id,
                            referenceDocNo = order.orderNo,
                            reason = "صرف خامات لأمر الإنتاج ${order.orderNo}",
                            qcApproved = true,
                            performedBy = actor,
                            idempotencyKey = "PRD-ISS-${order.syncUuid}-${line.itemId}-${line.batchNumber.trim()}"
                        )
                    )

                    if (line.returnedQty > EPSILON) {
                        movementEngine.postMovementInternal(
                            InventoryMovementRequest(
                                movementType = InventoryMovementType.PRODUCTION_RETURN,
                                warehouseId = rawWh.id,
                                itemCategory = line.itemCategory,
                                itemId = line.itemId,
                                itemName = line.itemName.trim(),
                                quantity = line.returnedQty,
                                unit = line.unit.ifBlank { "كجم" },
                                unitCost = line.unitCost,
                                currency = "EGP",
                                batchNumber = line.batchNumber.trim(),
                                referenceDocType = "PRODUCTION_RETURN",
                                referenceDocId = order.id,
                                referenceDocNo = order.orderNo,
                                reason = "مرتجع فائض خامات من أمر الإنتاج ${order.orderNo}",
                                qcApproved = true,
                                performedBy = actor,
                                idempotencyKey = "PRD-RET-${order.syncUuid}-${line.itemId}-${line.batchNumber.trim()}"
                            )
                        )
                    }
                    totalWaste += line.wastedQty.coerceAtLeast(0.0)

                    dao.insertFactoryMove(
                        FactoryMoveEntity(
                            moveDate = todayDate(),
                            kind = "ISSUE_PRODUCTION",
                            itemId = line.itemId,
                            itemName = line.itemName.trim(),
                            qty = (line.qty - line.returnedQty).coerceAtLeast(0.0),
                            batch = order.batchNumber,
                            note = "صرف خامات لأمر الإنتاج ${order.orderNo}",
                            actor = actor,
                            counterparty = order.productionEngineer,
                            syncUuid = "FM-ISS-${order.syncUuid}-${line.itemId}"
                        )
                    )
                }

                val now = System.currentTimeMillis()
                val updated = order.copy(
                    status = "MATERIALS_ISSUED",
                    wastedRawKg = order.wastedRawKg + totalWaste,
                    recordVersion = order.recordVersion + 1,
                    updatedAt = now
                )
                invDao.upsertProductionOrder(updated)

                val netIssuedCost = materials.sumOf { line ->
                    val netQty = (line.qty - line.returnedQty - line.wastedQty).coerceAtLeast(0.0)
                    netQty * line.unitCost.coerceAtLeast(0.0)
                }
                val wasteCost = materials.sumOf { line ->
                    line.wastedQty.coerceAtLeast(0.0) * line.unitCost.coerceAtLeast(0.0)
                }
                accountingEngine.postProductionIssueJournalInternal(updated, netIssuedCost, wasteCost)
                updated
            }
        }
    }

    /**
     * 4. فحص الجودة QC Gate + إضافة المطابق فقط إلى مخزن الإنتاج التام
     * - يمنع إضافة أي كمية إلى رصيد الإنتاج التام المتاح قبل اجتياز الجودة
     * - عند رفض الجودة: تُحجر الكمية تحت quarantineQty ولا تدخل الرصيد المتاح أو المبيعات
     * - عند القبول: تُحسب الكراتين الكاملة والعلب المتبقية وفق قاعدة التعبئة وتضاف إلى FINISHED_GOODS
     */
    suspend fun inspectQcAndPostToFinishedGoods(
        input: QcProductionInspectionInput
    ): Result<Pair<QcInspectionEntity, ProductionOrderEntity>> {
        return runCatching {
            require(input.goodCans >= 0 && input.rejectedCans >= 0 && input.reworkCans >= 0) {
                "الكميات المفحوصة والمرفوضة والمعاد تشغيلها لا يمكن أن تكون سالبة."
            }
            val totalInspected = input.goodCans + input.rejectedCans + input.reworkCans
            require(totalInspected > 0) { "يجب إدخال كمية مفحوصة أكبر من صفر." }

            database.withTransaction {
                val order = invDao.getProductionOrderById(input.productionOrderId)
                    ?: throw IllegalArgumentException("أمر الإنتاج غير موجود.")
                if (order.status == "POSTED_TO_FG") {
                    throw IllegalStateException("تم ترحيل إنتاج هذا الأمر إلى مخزن الإنتاج التام مسبقاً ولا يمكن تكرار الترحيل.")
                }

                val formula = order.formulaId?.let { dao.getFormulationById(it) }
                val targetDensity = formula?.calculatedDensity?.takeIf { it > 0.0 }
                    ?: input.measuredDensity.takeIf { it > 0.0 }
                    ?: 1.25
                val minDensity = round(targetDensity * 0.97 * 1000.0) / 1000.0
                val maxDensity = round(targetDensity * 1.03 * 1000.0) / 1000.0
                val densityPass = input.measuredDensity <= 0.0 || input.measuredDensity in minDensity..maxDensity

                val isPassed = !input.forceReject && densityPass && input.colorMatchPassed && input.goodCans > 0
                val fgWh = resolveWarehouseForClass(WarehouseClass.FINISHED_GOODS, order.fgWarehouseId.takeIf { it > 0L })

                // Resolve matching ProductEntity or auto-create if missing
                val weightStr = order.packageWeightGrams.toString()
                val matchedProduct = dao.getProductsDirect().firstOrNull {
                    (it.id == order.productId) ||
                        (it.name.equals(order.productName, ignoreCase = true) && it.packageWeight == weightStr) ||
                        (it.paintColor.equals(order.paintColor, ignoreCase = true) && it.packageWeight == weightStr)
                }
                val productId = matchedProduct?.id ?: dao.insertProduct(
                    ProductEntity(
                        sku = "PMP-${order.packageWeightGrams}-${order.id}",
                        name = order.productName,
                        category = "بوهيات",
                        unit = "كرتونة",
                        packageWeight = weightStr,
                        paintColor = order.paintColor
                    )
                )

                val fullCartons = if (isPassed && order.cansPerCarton > 0) input.goodCans / order.cansPerCarton else 0
                val looseCans = if (isPassed && order.cansPerCarton > 0) input.goodCans % order.cansPerCarton else 0
                val now = System.currentTimeMillis()
                val qcNo = nextDocNo("QC")
                val cleanIdemp = input.idempotencyKey.trim().ifBlank { "QC-${order.syncUuid}" }

                if (isPassed) {
                    val qtyToPostCartons = if (fullCartons > 0) {
                        fullCartons.toDouble()
                    } else {
                        // Partial carton expressed fractionally in carton unit when fewer than 1 full carton passed QC
                        round((input.goodCans.toDouble() / order.cansPerCarton.coerceAtLeast(1)) * 1000.0) / 1000.0
                    }

                    movementEngine.postMovementInternal(
                        InventoryMovementRequest(
                            movementType = InventoryMovementType.PRODUCTION_RECEIPT,
                            warehouseId = fgWh.id,
                            itemCategory = InventoryItemCategory.FINISHED_GOOD,
                            itemId = productId,
                            itemCode = matchedProduct?.sku ?: "FG-$productId",
                            itemName = order.productName,
                            quantity = qtyToPostCartons,
                            unit = "كرتونة",
                            unitCost = matchedProduct?.purchasePrice ?: 0.0,
                            currency = "EGP",
                            batchNumber = order.batchNumber,
                            referenceDocType = "QC_PRODUCTION_RECEIPT",
                            referenceDocId = order.id,
                            referenceDocNo = qcNo,
                            reason = "استلام إنتاج تام معتمد جودة $qcNo لأمر ${order.orderNo} ($fullCartons كرتونة + $looseCans علبة)",
                            qcApproved = true,
                            quarantineHold = false,
                            performedBy = order.productionEngineer,
                            approvedBy = input.inspectorName,
                            idempotencyKey = "FG-POST-$cleanIdemp"
                        )
                    )

                    dao.insertStockMove(
                        StockMoveEntity(
                            warehouseId = fgWh.id,
                            productId = productId,
                            quantity = qtyToPostCartons,
                            kind = "IN",
                            note = "إنتاج تام معتمد جودة ($qcNo / ${order.orderNo})",
                            createdBy = input.inspectorName,
                            batchNumber = order.batchNumber,
                            syncUuid = "SM-FG-$cleanIdemp"
                        )
                    )

                    dao.insertProductionBatch(
                        ProductionBatchEntity(
                            batchNumber = order.batchNumber,
                            productionDate = todayDate(),
                            formulaId = order.formulaId,
                            formulaNo = order.formulaNo,
                            productName = order.productName,
                            paintType = formula?.paintType ?: "بوهيات",
                            baseKind = order.paintColor,
                            targetWeightKg = order.targetWeightKg,
                            actualWeightKg = (input.goodCans * order.packageWeightGrams.toDouble()) / 1000.0,
                            cartonsCount = fullCartons,
                            packageUnit = "${order.packageWeightGrams} جم (${order.cansPerCarton} علبة/كرتونة)",
                            machineName = order.machineName,
                            qualityStatus = "معتمد ومطابق",
                            measuredDensity = input.measuredDensity,
                            measuredViscosity = input.measuredViscosity,
                            supervisorOrEngineer = order.productionEngineer,
                            notes = input.notes.trim(),
                            createdAt = now
                        )
                    )
                }

                // Isolate rejected + rework quantities in QC Quarantine inside FINISHED_GOODS (NEVER available for sale)
                val quarantineCans = if (isPassed) {
                    input.rejectedCans + input.reworkCans
                } else {
                    totalInspected
                }
                if (quarantineCans > 0) {
                    val quarantineCartons = round((quarantineCans.toDouble() / order.cansPerCarton.coerceAtLeast(1)) * 1000.0) / 1000.0
                    movementEngine.postMovementInternal(
                        InventoryMovementRequest(
                            movementType = InventoryMovementType.PRODUCTION_RECEIPT,
                            warehouseId = fgWh.id,
                            itemCategory = InventoryItemCategory.FINISHED_GOOD,
                            itemId = productId,
                            itemCode = matchedProduct?.sku ?: "FG-$productId",
                            itemName = order.productName,
                            quantity = quarantineCartons.coerceAtLeast(0.001),
                            unit = "كرتونة",
                            currency = "EGP",
                            batchNumber = "${order.batchNumber}-QC-HOLD",
                            referenceDocType = "QC_QUARANTINE",
                            referenceDocId = order.id,
                            referenceDocNo = qcNo,
                            reason = "حجر منتجات غير مطابقة للمواصفات QC ($qcNo): مرفوض ${input.rejectedCans}، إعادة تشغيل ${input.reworkCans}",
                            qcApproved = false,
                            quarantineHold = true,
                            performedBy = input.inspectorName,
                            idempotencyKey = "FG-HOLD-$cleanIdemp"
                        )
                    )
                }

                val qcStatus = when {
                    isPassed -> "APPROVED"
                    input.reworkCans > 0 -> "CONDITIONAL_REWORK"
                    else -> "REJECTED"
                }

                val qcRecord = QcInspectionEntity(
                    qcNo = qcNo,
                    sourceType = "PRODUCTION_ORDER",
                    sourceDocId = order.id,
                    sourceDocNo = order.orderNo,
                    batchNumber = order.batchNumber,
                    itemCategory = InventoryItemCategory.FINISHED_GOOD.name,
                    itemId = productId,
                    itemName = order.productName,
                    inspectedQty = totalInspected.toDouble(),
                    acceptedQty = if (isPassed) input.goodCans.toDouble() else 0.0,
                    rejectedQty = if (isPassed) input.rejectedCans.toDouble() else (input.goodCans + input.rejectedCans).toDouble(),
                    reworkQty = input.reworkCans.toDouble(),
                    measuredDensity = input.measuredDensity,
                    expectedMinDensity = minDensity,
                    expectedMaxDensity = maxDensity,
                    measuredViscosity = input.measuredViscosity,
                    colorMatchPassed = input.colorMatchPassed,
                    status = qcStatus,
                    inspectorName = input.inspectorName.ifBlank { "مسؤول الجودة" },
                    notes = input.notes.trim(),
                    postedToStock = isPassed,
                    idempotencyKey = cleanIdemp,
                    createdAt = now,
                    updatedAt = now
                )
                val qcId = invDao.upsertQcInspection(qcRecord)

                val updatedOrder = order.copy(
                    productId = productId,
                    actualGoodCans = if (isPassed) input.goodCans else 0,
                    rejectedCans = if (isPassed) input.rejectedCans else (input.goodCans + input.rejectedCans),
                    reworkCans = input.reworkCans,
                    producedFullCartons = fullCartons,
                    producedLooseCans = looseCans,
                    wastedRawKg = order.wastedRawKg + input.wastedRawKg.coerceAtLeast(0.0),
                    status = if (isPassed) "POSTED_TO_FG" else "QC_REJECTED",
                    qcInspectedBy = input.inspectorName,
                    qcNotes = input.notes.trim(),
                    recordVersion = order.recordVersion + 1,
                    updatedAt = now
                )
                invDao.upsertProductionOrder(updatedOrder)

                dao.insertAuditLog(
                    AuditLogEntity(
                        username = input.inspectorName,
                        action = if (isPassed) "QC_APPROVED_POSTED_FG" else "QC_REJECTED_QUARANTINED",
                        detail = if (isPassed) {
                            "اعتماد جودة $qcNo لأمر ${order.orderNo}: دخول $fullCartons كرتونة ($looseCans علبة متبقية) إلى مخزن الإنتاج التام"
                        } else {
                            "رفض جودة $qcNo لأمر ${order.orderNo}: حجر $quarantineCans علبة ومنع دخولها للمخزون المتاح"
                        }
                    )
                )

                val savedQc = qcRecord.copy(id = qcId)
                if (isPassed) {
                    val postedQtyCartons = if (fullCartons > 0) {
                        fullCartons.toDouble()
                    } else {
                        round((input.goodCans.toDouble() / order.cansPerCarton.coerceAtLeast(1)) * 1000.0) / 1000.0
                    }
                    val unitCostEgp = (matchedProduct?.purchasePrice ?: 0.0).takeIf { it > 0.0 }
                        ?: ((formula?.costPerKg ?: 0.0) * (order.packageWeightGrams.toDouble() / 1000.0) * order.cansPerCarton)
                    val fgCostValue = postedQtyCartons * unitCostEgp.coerceAtLeast(0.0)
                    accountingEngine.postQcFinishedGoodsJournalInternal(updatedOrder, savedQc, fgCostValue)
                }

                savedQc to updatedOrder
            }
        }
    }
}
