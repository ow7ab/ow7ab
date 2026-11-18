package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * سجل الإصدارات غير القابل للتعديل للتركيبات الكيميائية (Immutable Formula Version Control)
 * أي تعديل على تركيبة معتمدة ينشئ إصداراً جديداً مع حفظ اللقطة السابقة والتكلفة التاريخية والفروقات.
 */
@Entity(
    tableName = "lab_formula_versions",
    indices = [
        Index(value = ["syncUuid"], unique = true, name = "idx_lab_ver_uuid"),
        Index(value = ["formulaId", "versionNumber"], name = "idx_lab_ver_formula")
    ]
)
data class FormulationVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String,
    val formulaId: Long,
    val formulaNo: String,
    val productName: String,
    val versionNumber: Int,
    val previousVersionNumber: Int = 0,
    val status: String = "NEEDS_REVIEW", // DRAFT, NEEDS_REVIEW, APPROVED, ARCHIVED, REJECTED
    val changeReason: String = "",
    val diffSummaryJson: String = "{}", // Safe structural diff without leaking full secret ratios externally
    val previousCostPerKg: Double = 0.0,
    val newCostPerKg: Double = 0.0,
    val historicalTotalCost: Double = 0.0,
    val calculatedPvc: Double = 0.0,
    val calculatedDensity: Double = 1.0,
    val currency: String = "EGP",
    val ingredientsSnapshotJson: String = "[]",
    val encryptedSnapshotCiphertext: String = "",
    val createdBy: String = "",
    val approvedBy: String = "",
    val approvedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * سجل التجارب والفحوصات المخبرية المرتبط بإصدار تركيبة محدد وبوابة الجودة QC.
 */
@Entity(
    tableName = "lab_experiments",
    indices = [
        Index(value = ["syncUuid"], unique = true, name = "idx_lab_exp_uuid"),
        Index(value = ["formulaId", "formulaVersionId"], name = "idx_lab_exp_formula")
    ]
)
data class LabExperimentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String,
    val experimentNo: String,
    val formulaId: Long,
    val formulaNo: String,
    val formulaVersionId: Long? = null,
    val versionNumber: Int = 1,
    val productId: Long? = null,
    val productName: String,
    val experimentDate: String,
    val technicianName: String,
    val materialsUsedJson: String = "[]",
    val measuredDensity: Double = 0.0,
    val measuredViscosity: String = "",
    val measuredPh: Double = 0.0,
    val measuredGrindMicrons: Double = 0.0,
    val hidingPowerPercent: Double = 0.0,
    val notes: String = "",
    val status: String = "PENDING", // PENDING, PASSED, FAILED, RETEST_REQUIRED
    val decision: String = "PENDING", // PENDING, APPROVED, REJECTED, RETEST
    val decidedBy: String = "",
    val decidedAt: Long = 0L,
    val linkedQcNo: String = "",
    val idempotencyKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * سجل تغيرات أسعار المواد الخام الموحد (المشتريات GRN + المختبر + استيراد إكسل).
 */
@Entity(
    tableName = "raw_material_price_history",
    indices = [
        Index(value = ["syncUuid"], unique = true, name = "idx_rm_price_uuid"),
        Index(value = ["materialId", "createdAt"], name = "idx_rm_price_mat")
    ]
)
data class RawMaterialPriceHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String,
    val materialId: Long,
    val itemCode: String = "",
    val materialName: String,
    val supplierName: String = "",
    val unitPrice: Double,
    val previousPrice: Double = 0.0,
    val currency: String = "EGP",
    val sourceType: String = "MANUAL_LAB", // PURCHASE_GRN, MANUAL_LAB, EXCEL_IMPORT
    val sourceDocNo: String = "",
    val effectiveDate: String = "",
    val recordedBy: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * سجل دفعات استيراد ملفات Excel (يحفظ فقط الميتاداتا الآمنة ومسار الأرشيف المقيّد بدون تسريب النسب).
 */
@Entity(
    tableName = "lab_excel_imports",
    indices = [
        Index(value = ["syncUuid"], unique = true, name = "idx_lab_xlsx_uuid")
    ]
)
data class LabExcelImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String,
    val importNo: String,
    val fileName: String,
    val sheetCount: Int = 0,
    val formulasDetected: Int = 0,
    val formulasImported: Int = 0,
    val materialsMatched: Int = 0,
    val materialsCreated: Int = 0,
    val externalLinksDetected: Int = 0,
    val brokenRefsDetected: Int = 0,
    val warningsCount: Int = 0,
    val approvalStatus: String = "NEEDS_REVIEW",
    val checksumSha256: String = "",
    val restrictedSummaryJson: String = "{}",
    val referenceArchivePath: String = "",
    val importedBy: String = "",
    val idempotencyKey: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
