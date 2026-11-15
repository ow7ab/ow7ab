package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * سند استلام وفحص المشتريات (GRN -> مخزن المواد الخام)
 */
@Entity(
    tableName = "purchase_receipts",
    indices = [
        Index(value = ["syncUuid"], unique = true, name = "idx_pr_sync_uuid"),
        Index(value = ["grnNo"], unique = true, name = "idx_pr_grn_no"),
        Index(value = ["materialId"], name = "idx_pr_material_id")
    ]
)
data class PurchaseReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String = UUID.randomUUID().toString(),
    val grnNo: String,
    val poNo: String = "",
    val receiptDate: String,
    val supplierName: String,
    val warehouseId: Long,
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
    val totalCost: Double = 0.0,
    val currency: String = "EGP",
    val discrepancyReason: String = "",
    val qcStatus: String = "APPROVED", // PENDING, APPROVED, PARTIAL, REJECTED
    val stockPosted: Boolean = false,
    val notes: String = "",
    val performedBy: String = "أمين مخزن الخام",
    val approvedBy: String = "",
    val idempotencyKey: String = "",
    val recordVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * أمر الإنتاج المعتمد (BOM -> صرف خامات -> تعبئة 250/900/4000 -> فحص جودة QC -> مخزن الإنتاج التام)
 */
@Entity(
    tableName = "production_orders",
    indices = [
        Index(value = ["syncUuid"], unique = true, name = "idx_po_sync_uuid"),
        Index(value = ["orderNo"], unique = true, name = "idx_po_order_no")
    ]
)
data class ProductionOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String = UUID.randomUUID().toString(),
    val orderNo: String,
    val orderDate: String,
    val formulaId: Long? = null,
    val formulaNo: String = "",
    val productId: Long? = null,
    val productName: String,
    val paintColor: String = "أبيض",
    val packageWeightGrams: Int = 900, // 250, 900, 4000
    val cansPerCarton: Int = 12,       // 48, 12, 4
    val targetCartons: Int,
    val targetCans: Int,
    val targetWeightKg: Double,
    val actualGoodCans: Int = 0,
    val rejectedCans: Int = 0,
    val reworkCans: Int = 0,
    val producedFullCartons: Int = 0,
    val producedLooseCans: Int = 0,
    val wastedRawKg: Double = 0.0,
    val bomLinesJson: String = "[]",
    val machineName: String = "",
    val productionEngineer: String = "مهندس الإنتاج",
    val status: String = "APPROVED", // DRAFT, APPROVED, MATERIALS_ISSUED, IN_PRODUCTION, QC_PENDING, QC_APPROVED, QC_REJECTED, POSTED_TO_FG
    val batchNumber: String = "",
    val rawWarehouseId: Long = 0L,
    val fgWarehouseId: Long = 0L,
    val qcInspectedBy: String = "",
    val qcNotes: String = "",
    val approvedBy: String = "المدير",
    val idempotencyKey: String = "",
    val recordVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * سجل فحص ومراقبة الجودة (Quality Control Gate)
 */
@Entity(
    tableName = "qc_inspections",
    indices = [
        Index(value = ["syncUuid"], unique = true, name = "idx_qc_sync_uuid"),
        Index(value = ["qcNo"], unique = true, name = "idx_qc_no"),
        Index(value = ["sourceDocNo"], name = "idx_qc_source_doc")
    ]
)
data class QcInspectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String = UUID.randomUUID().toString(),
    val qcNo: String,
    val sourceType: String, // PRODUCTION_ORDER, PURCHASE_RECEIPT, TRANSFER
    val sourceDocId: Long? = null,
    val sourceDocNo: String,
    val batchNumber: String = "",
    val itemCategory: String = "FINISHED_GOOD",
    val itemId: Long = 0L,
    val itemName: String,
    val inspectedQty: Double,
    val acceptedQty: Double,
    val rejectedQty: Double = 0.0,
    val reworkQty: Double = 0.0,
    val measuredDensity: Double = 0.0,
    val expectedMinDensity: Double = 0.0,
    val expectedMaxDensity: Double = 0.0,
    val measuredViscosity: String = "",
    val colorMatchPassed: Boolean = true,
    val status: String = "APPROVED", // APPROVED, CONDITIONAL_REWORK, REJECTED
    val inspectorName: String = "مسؤول الجودة",
    val notes: String = "",
    val postedToStock: Boolean = false,
    val idempotencyKey: String = "",
    val recordVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
