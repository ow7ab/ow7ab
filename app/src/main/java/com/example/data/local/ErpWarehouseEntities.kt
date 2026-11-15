package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * تصنيف المخازن الثلاثة المستقلة والمترابطة في نظام PMP ERP:
 * 1. RAW_MATERIALS: مخزن المواد الخام ومواد التعبئة المرتبط بالمصنع والمشتريات
 * 2. FINISHED_GOODS: مخزن الإنتاج التام المرتبط بمخرجات الإنتاج ومراقبة الجودة QC
 * 3. SALES: مخزن المبيعات (حلفا / عطبرة / الخرطوم / مبيعات مصر) ولا يستلم إلا عبر التحويلات المعتمدة
 */
enum class WarehouseClass(val arabicLabel: String, val legacyWarehouseType: String) {
    RAW_MATERIALS("مخزن المواد الخام", "مواد خام"),
    FINISHED_GOODS("مخزن الإنتاج التام", "داخل المصنع/إنتاج"),
    SALES("مخزن المبيعات", "مبيعات");

    companion object {
        fun fromRaw(raw: String?): WarehouseClass {
            val clean = raw?.trim()?.uppercase().orEmpty()
            return when {
                clean == RAW_MATERIALS.name || clean.contains("خام") || clean == "RAW" -> RAW_MATERIALS
                clean == FINISHED_GOODS.name || clean.contains("إنتاج") || clean.contains("انتاج") || clean == "FINISHED" -> FINISHED_GOODS
                else -> SALES
            }
        }
    }
}

enum class InventoryItemCategory(val arabicLabel: String) {
    RAW_MATERIAL("مادة خام"),
    PACKAGING("مادة تعبئة وتغليف"),
    FINISHED_GOOD("منتج تام الصنع");

    companion object {
        fun fromRaw(raw: String?): InventoryItemCategory {
            val clean = raw?.trim()?.uppercase().orEmpty()
            return when {
                clean == PACKAGING.name || clean == "EMPTY" || clean.contains("تعبئة") -> PACKAGING
                clean == FINISHED_GOOD.name || clean == "FINISHED" || clean.contains("تام") -> FINISHED_GOOD
                else -> RAW_MATERIAL
            }
        }
    }
}

/**
 * أنواع الحركات المخزنية الـ 13 المعتمدة (Append-Only Audit Ledger)
 * لا يُسمح بالحذف المباشر لأي حركة، بل يتم التعديل عبر قيد عكسي (Reversing Entry)
 */
enum class InventoryMovementType(
    val arabicLabel: String,
    val defaultDirectionSign: Int // +1 inbound, -1 outbound, 0 adjustment (uses explicit signedQuantity)
) {
    PURCHASE_RECEIPT("استلام مشتريات", +1),
    PRODUCTION_ISSUE("صرف مواد للإنتاج", -1),
    PRODUCTION_RETURN("إرجاع مواد من الإنتاج", +1),
    PRODUCTION_RECEIPT("استلام إنتاج تام", +1),
    WAREHOUSE_TRANSFER_OUT("تحويل صادر", -1),
    WAREHOUSE_TRANSFER_IN("تحويل وارد", +1),
    SALES_ISSUE("صرف مبيعات", -1),
    CUSTOMER_RETURN("مرتجع مبيعات", +1),
    SUPPLIER_RETURN("مرتجع مشتريات", -1),
    STOCK_ADJUSTMENT("تسوية مخزنية", 0),
    OPENING_BALANCE("رصيد افتتاحي", +1),
    DAMAGED_GOODS("بضاعة تالفة", -1),
    EXPIRED_GOODS("بضاعة منتهية الصلاحية", -1);

    companion object {
        fun fromRaw(raw: String): InventoryMovementType {
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: STOCK_ADJUSTMENT
        }
    }
}

enum class MovementRecordStatus(val arabicLabel: String) {
    POSTED("مرحّل ومعتمد"),
    REVERSED("معكوس بقيد تصحيحي"),
    PENDING_APPROVAL("بانتظار الاعتماد"),
    REJECTED("مرفوض")
}

enum class WarehouseTransferStatus(val arabicLabel: String) {
    DRAFT("مسودة"),
    PENDING_APPROVAL("بانتظار الاعتماد"),
    APPROVED("معتمد"),
    IN_TRANSIT("قيد النقل / في الطريق"),
    RECEIVED("مستلم بالكامل"),
    REJECTED("مرفوض"),
    CANCELLED("ملغى");

    companion object {
        fun fromRaw(raw: String): WarehouseTransferStatus {
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: DRAFT
        }
    }
}

/**
 * سجل الحركات المخزنية الموحد (Append-Only Inventory Ledger)
 */
@Entity(
    tableName = "inventory_movements",
    indices = [
        Index(value = ["syncUuid"], unique = true, name = "idx_inv_mov_sync_uuid"),
        Index(value = ["warehouseId", "itemCategory", "itemId"], name = "idx_inv_mov_lookup")
    ]
)
data class InventoryMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String = UUID.randomUUID().toString(),
    val documentNo: String,
    val movementType: String, // InventoryMovementType.name
    val warehouseId: Long,
    val warehouseCode: String = "",
    val warehouseClass: String, // WarehouseClass.name
    val itemCategory: String, // InventoryItemCategory.name
    val itemId: Long,
    val itemCode: String = "",
    val itemName: String,
    val quantity: Double, // Always positive magnitude
    val signedQuantity: Double, // +qty for IN, -qty for OUT
    val unit: String = "كرتونة",
    val unitCost: Double = 0.0,
    val totalCost: Double = 0.0,
    val currency: String = "EGP",
    val batchNumber: String = "",
    val expiryDate: String = "",
    val referenceDocType: String = "", // PURCHASE, PRODUCTION, TRANSFER, SALES, ADJUSTMENT, QC, OPENING
    val referenceDocId: Long? = null,
    val referenceDocNo: String = "",
    val reversedMovementId: Long? = null,
    val reason: String = "",
    val status: String = MovementRecordStatus.POSTED.name, // POSTED, REVERSED, PENDING_APPROVAL, REJECTED
    val qcApproved: Boolean = true,
    val idempotencyKey: String = "",
    val performedBy: String = "المدير",
    val approvedBy: String = "",
    val deviceId: String = "",
    val recordVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * أرصدة المخازن الذرية الموحدة (المتوفر، المحجوز، في الطريق، تحت الحجر QC)
 */
@Entity(
    tableName = "warehouse_stock_balances",
    indices = [
        Index(
            value = ["warehouseId", "itemCategory", "itemId", "batchNumber"],
            unique = true,
            name = "idx_wh_stock_bal_key"
        )
    ]
)
data class WarehouseStockBalanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String = UUID.randomUUID().toString(),
    val warehouseId: Long,
    val warehouseClass: String,
    val itemCategory: String, // RAW_MATERIAL, PACKAGING, FINISHED_GOOD
    val itemId: Long,
    val itemCode: String = "",
    val itemName: String,
    val unit: String = "كرتونة",
    val batchNumber: String = "",
    val expiryDate: String = "",
    val onHandQty: Double = 0.0,
    val reservedQty: Double = 0.0,
    val inTransitQty: Double = 0.0,
    val quarantineQty: Double = 0.0,
    val availableQty: Double = 0.0,
    val avgUnitCost: Double = 0.0,
    val currency: String = "EGP",
    val lastMovementUuid: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * مستند التحويل المخزني الرسمي (بين مخزن الإنتاج التام ومخازن المبيعات أو بين المخازن المتجانسة)
 */
@Entity(
    tableName = "warehouse_transfers",
    indices = [
        Index(value = ["transferNo"], unique = true, name = "idx_wh_trf_no"),
        Index(value = ["syncUuid"], unique = true, name = "idx_wh_trf_sync_uuid")
    ]
)
data class WarehouseTransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String = UUID.randomUUID().toString(),
    val transferNo: String,
    val transferDate: String,
    val sourceWarehouseId: Long,
    val sourceWarehouseName: String,
    val sourceWarehouseClass: String,
    val destinationWarehouseId: Long,
    val destinationWarehouseName: String,
    val destinationWarehouseClass: String,
    val status: String = WarehouseTransferStatus.DRAFT.name,
    val totalCost: Double = 0.0,
    val currency: String = "EGP",
    val notes: String = "",
    val rejectionReason: String = "",
    val createdBy: String,
    val reviewedBy: String = "",
    val approvedBy: String = "",
    val dispatchedBy: String = "",
    val receivedBy: String = "",
    val dispatchedAt: Long = 0L,
    val receivedAt: Long = 0L,
    val timelineJson: String = "[]",
    val idempotencyKey: String = "",
    val deviceId: String = "",
    val recordVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "warehouse_transfer_items",
    indices = [
        Index(value = ["transferId"], name = "idx_wh_trf_item_trf_id")
    ]
)
data class WarehouseTransferItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transferId: Long,
    val transferUuid: String,
    val itemCategory: String, // FINISHED_GOOD, RAW_MATERIAL, PACKAGING
    val itemId: Long,
    val itemCode: String = "",
    val itemName: String,
    val unit: String = "كرتونة",
    val requestedQty: Double,
    val approvedQty: Double = 0.0,
    val shippedQty: Double = 0.0,
    val receivedQty: Double = 0.0,
    val rejectedQty: Double = 0.0,
    val unitCost: Double = 0.0,
    val totalCost: Double = 0.0,
    val batchNumber: String = "",
    val qcApprovedRequired: Boolean = true,
    val qcApproved: Boolean = false
)

/**
 * قواعد ومعدلات التعبئة القياسية (قابلة للتعديل للمخولين فقط مع المزامنة)
 * 250 جم -> 48، 900 جم -> 12، 4000 جم -> 4
 */
@Entity(
    tableName = "packaging_rules",
    indices = [
        Index(value = ["weightGrams"], unique = true, name = "idx_pkg_rule_weight")
    ]
)
data class PackagingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String = UUID.randomUUID().toString(),
    val weightGrams: Int,
    val label: String,
    val cansPerCarton: Int,
    val emptyCanItemId: Long? = null,
    val lidItemId: Long? = null,
    val labelItemId: Long? = null,
    val cartonItemId: Long? = null,
    val defaultEmptyCanCostEgp: Double = 4.5,
    val defaultLidCostEgp: Double = 1.2,
    val defaultLabelCostEgp: Double = 0.8,
    val defaultCartonBoxCostEgp: Double = 18.0,
    val active: Boolean = true,
    val updatedBy: String = "SYSTEM",
    val recordVersion: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * طابور المزامنة غير المتصلة (Sync Outbox) لضمان عدم ضياع أي حركة أو قيد مع Idempotency
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["operationId"], unique = true, name = "idx_sync_outbox_op_id"),
        Index(value = ["status"], name = "idx_sync_outbox_status")
    ]
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationId: String = UUID.randomUUID().toString(),
    val entityType: String,
    val entityUuid: String,
    val action: String = "UPSERT", // UPSERT, STATUS_UPDATE
    val payloadJson: String,
    val status: String = "PENDING", // PENDING, SYNCED, FAILED
    val retryCount: Int = 0,
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
