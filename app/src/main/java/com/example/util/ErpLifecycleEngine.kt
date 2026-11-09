package com.example.util

import android.content.Context
import com.example.data.local.FormulationEntity
import com.example.data.local.FormulationIngredient
import com.example.data.local.UserAccountEntity
import com.example.isSuperAdminAccount
import com.example.isSystemManager
import com.example.parsedPermissionsSet
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * الصلاحيات الدقيقة للعمليات داخل نظام PMP PAINTS Enterprise ERP
 */
enum class ErpActionPermission(val arabicLabel: String) {
    VIEW("عرض (View)"),
    CREATE("إنشاء (Create)"),
    EDIT("تعديل (Edit)"),
    SUBMIT("إرسال للاعتماد (Submit)"),
    APPROVE("اعتماد (Approve)"),
    REJECT("رفض (Reject)"),
    RECEIVE("استلام مخزني (Receive)"),
    ISSUE("صرف مخزني (Issue)"),
    TRANSFER("تحويل مخزني (Transfer)"),
    EXPORT("تصدير تقارير (Export)"),
    REVERSE("عكس / تسوية مستند (Reverse)"),
    MANAGE_SETTINGS("إدارة الإعدادات الحساسة (Manage Settings)")
}

/**
 * الأدوار الوظيفية المعتمدة في النظام
 */
enum class ErpJobRole(val arabicTitle: String) {
    ADMIN("Admin - مدير النظام الأعلى"),
    GENERAL_MANAGER("المدير"),
    PROCUREMENT_OFFICER("مسؤول المشتريات"),
    RAW_STOREKEEPER("أمين مخزن الخام"),
    PRODUCTION_ENGINEER("مهندس الإنتاج"),
    QC_INSPECTOR("مسؤول الجودة"),
    FG_STOREKEEPER("أمين مخزن المنتج التام"),
    SALES_OFFICER("مسؤول المبيعات"),
    ACCOUNTANT("المحاسب")
}

/**
 * حالات استلام ومطابقة شحنات المواد الخام (GRN)
 */
enum class GoodsReceiptStatus(val arabicLabel: String) {
    AWAITING_RECEIPT("بانتظار الاستلام"),
    PARTIAL_RECEIPT("استلام جزئي"),
    UNDER_INSPECTION("تحت الفحص"),
    DISCREPANCY_REVIEW("اختلاف يحتاج مراجعة"),
    ACCEPTED("مقبول"),
    REJECTED("مرفوض"),
    COMPLETED("مكتمل")
}

/**
 * حالات أمر الإنتاج المعتمد
 */
enum class ProductionOrderStatus(val arabicLabel: String) {
    DRAFT("مسودة"),
    SUBMITTED("بانتظار الاعتماد"),
    APPROVED("أمر إنتاج معتمد"),
    RAW_ISSUED("تم صرف الخامات"),
    ENGINEER_RECEIVED("تم استلام مهندس الإنتاج"),
    IN_PRODUCTION("قيد التصنيع والتعبئة"),
    QC_PENDING("تحت فحص الجودة QC"),
    QC_APPROVED("معتمد جودة QC"),
    QC_REJECTED("مرفوض جودة / معالجة"),
    DELIVERED_TO_FG("تم التسليم لمخزن المنتج التام"),
    COMPLETED("مكتمل ومضاف للمخزون")
}

/**
 * فئة التعبئة القياسية المعتمدة في المصنع
 * 250 جرام -> 48 علبة في الكرتونة
 * 900 جرام -> 12 علبة في الكرتونة
 * 4000 جرام -> 4 علب في الكرتونة
 */
data class PackagingRuleSpec(
    val weightGrams: Int,
    val label: String,
    val cansPerCarton: Int,
    val defaultEmptyCanCostEgp: Double = 4.5,
    val defaultLidCostEgp: Double = 1.2,
    val defaultLabelCostEgp: Double = 0.8,
    val defaultCartonBoxCostEgp: Double = 18.0
) {
    fun calculateExpectedCans(cartons: Int): Int = (cartons.coerceAtLeast(0)) * cansPerCarton
    fun calculateFullCartons(goodApprovedCans: Int): Int =
        if (cansPerCarton > 0) (goodApprovedCans.coerceAtLeast(0)) / cansPerCarton else 0
    fun calculateRemainingLooseCans(goodApprovedCans: Int): Int =
        if (cansPerCarton > 0) (goodApprovedCans.coerceAtLeast(0)) % cansPerCarton else 0
    fun netPaintWeightKgForCans(cansCount: Int): Double =
        (cansCount.coerceAtLeast(0) * weightGrams.toDouble()) / 1000.0
}

/**
 * طلب شراء مواد خام (Purchase Requisition)
 */
data class PurchaseRequisitionRecord(
    val reqNumber: String,
    val requestDate: String,
    val responsibleEmployee: String,
    val materialName: String,
    val materialCode: String,
    val requestedQty: Double,
    val unit: String,
    val suggestedSupplier: String,
    val notes: String = "",
    val status: String = "معتمد", // مسودة, بانتظار الاعتماد, معتمد, تم التحويل لأمر شراء, مرفوض
    val approvedBy: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * أمر شراء معتمد (Purchase Order)
 * لا يؤدي تسجيل أمر الشراء وحده إلى زيادة رصيد المخزن إطلاقاً.
 */
data class PurchaseOrderRecord(
    val poNumber: String,
    val reqNumber: String = "",
    val orderDate: String,
    val supplierName: String,
    val materialId: Long,
    val materialCode: String,
    val materialName: String,
    val orderedQty: Double,
    val unit: String,
    val unitPrice: Double,
    val currency: String = "EGP",
    val additionalExpenses: Double = 0.0,
    val expectedDeliveryDate: String = "",
    val createdBy: String,
    val approvalStatus: String = "معتمد", // بانتظار الاعتماد, معتمد, استلام جزئي, مكتمل, مرفوض
    val approvedBy: String = "",
    val receivedTotalQty: Double = 0.0,
    val acceptedTotalQty: Double = 0.0,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val subtotalCost: Double get() = orderedQty * unitPrice
    val totalCost: Double get() = subtotalCost + additionalExpenses
    val remainingQtyToReceive: Double get() = (orderedQty - receivedTotalQty).coerceAtLeast(0.0)
}

/**
 * تقرير استلام ومطابقة شحنة المواد الخام (Goods Receipt Note - GRN)
 */
data class GoodsReceiptNoteRecord(
    val grnNumber: String,
    val poNumber: String,
    val receiptDate: String,
    val storekeeperName: String,
    val materialId: Long,
    val materialName: String,
    val unit: String,
    val expectedQty: Double,
    val actualReceivedQty: Double,
    val acceptedQty: Double,
    val rejectedQty: Double,
    val discrepancyReason: String = "",
    val notes: String = "",
    val attachmentRef: String = "",
    val status: GoodsReceiptStatus = GoodsReceiptStatus.UNDER_INSPECTION,
    val approvedByManager: String = "",
    val stockPosted: Boolean = false,
    val idempotencyKey: String = "GRN-$grnNumber",
    val createdAt: Long = System.currentTimeMillis()
) {
    val shortageOrOverageQty: Double get() = actualReceivedQty - expectedQty
    val hasDiscrepancy: Boolean
        get() = kotlin.math.abs(actualReceivedQty - expectedQty) > 0.0001 || rejectedQty > 0.0
}

/**
 * بطاقة رصيد مخزن المواد الخام التفصيلية
 */
data class RawMaterialInventoryCard(
    val itemId: Long,
    val materialCode: String,
    val materialName: String,
    val materialType: String,
    val unit: String,
    val actualBalance: Double,
    val reservedBalance: Double,
    val underInspectionQty: Double,
    val reorderLevel: Double,
    val unitCostEgp: Double,
    val lastMoveDate: String,
    val lastBatchNumber: String
) {
    val availableBalance: Double get() = (actualBalance - reservedBalance).coerceAtLeast(0.0)
    val totalInventoryValueEgp: Double get() = actualBalance * unitCostEgp
    val isLowStock: Boolean get() = availableBalance <= reorderLevel
}

/**
 * بند مادة خام محسوبة من تركيبة BOM لأمر إنتاج
 */
data class ProductionBomRequirementLine(
    val materialName: String,
    val materialType: String,
    val unit: String = "كجم",
    val theoreticalRequiredKg: Double,
    val actuallyIssuedKg: Double = 0.0,
    val actuallyConsumedKg: Double = 0.0,
    val returnedToStoreKg: Double = 0.0,
    val wastedKg: Double = 0.0,
    val unitCostEgp: Double = 0.0
)

/**
 * نتيجة تعبئة لفئة وزن محددة داخل أمر الإنتاج (يدعم الإنتاج المختلط لأكثر من وزن)
 */
data class PackagingRunBreakdown(
    val weightGrams: Int,
    val cansPerCarton: Int,
    val targetCartons: Int,
    val actualProducedCans: Int,
    val goodApprovedCans: Int,
    val damagedCans: Int,
    val underInspectionCans: Int
) {
    val expectedCansFromTarget: Int get() = targetCartons * cansPerCarton
    val isEquationBalanced: Boolean
        get() = actualProducedCans == (goodApprovedCans + damagedCans + underInspectionCans)
    val fullCartonsCount: Int
        get() = if (cansPerCarton > 0) goodApprovedCans / cansPerCarton else 0
    val remainingLooseCans: Int
        get() = if (cansPerCarton > 0) goodApprovedCans % cansPerCarton else 0
    val netGoodPaintWeightKg: Double
        get() = (goodApprovedCans * weightGrams.toDouble()) / 1000.0
}

/**
 * أمر الإنتاج المعتمد (Production Order)
 */
data class ProductionOrderRecord(
    val orderNumber: String,
    val createdDate: String,
    val executionDate: String,
    val productType: String,
    val formulaId: Long?,
    val formulaNo: String,
    val formulaName: String,
    val paintColor: String,
    val packageWeightGrams: Int, // 250, 900, 4000
    val targetWeightKg: Double,
    val targetCartons: Int,
    val productionLine: String = "خط الإنتاج الرئيسي 1",
    val machineName: String,
    val productionEngineer: String,
    val status: ProductionOrderStatus = ProductionOrderStatus.APPROVED,
    val approvedBy: String = "المدير",
    val bomRequirements: List<ProductionBomRequirementLine> = emptyList(),
    val packagingBreakdowns: List<PackagingRunBreakdown> = emptyList(),
    val issueVoucherNo: String = "",
    val engineerReceiptConfirmed: Boolean = false,
    val engineerReceiptTimestamp: Long = 0L,
    val batchNumber: String = "",
    val damageReason: String = "",
    val notes: String = "",
    val idempotencyKey: String = "PROD-ORD-$orderNumber",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * سجل فحص ومراقبة الجودة (Quality Control Inspection)
 */
data class QualityControlInspectionRecord(
    val qcNumber: String,
    val batchNumber: String,
    val productionOrderNumber: String,
    val inspectorName: String,
    val inspectionDate: String,
    val measuredDensity: Double,
    val expectedMinDensity: Double,
    val expectedMaxDensity: Double,
    val measuredViscosity: String,
    val appearanceAndColorMatch: Boolean,
    val acceptedCans: Int,
    val rejectedCans: Int,
    val reworkCans: Int = 0,
    val acceptedCartons: Int,
    val resultStatus: String, // مقبول, مرفوض, مشروط / إعادة تشغيل
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val isApprovedForWarehouse: Boolean
        get() = resultStatus == "مقبول" && acceptedCans > 0
}

/**
 * إشعار تسليم واستلام المنتج التام (Finished Goods Receipt Note)
 */
data class FinishedGoodsReceiptRecord(
    val fgReceiptNo: String,
    val batchNumber: String,
    val productionOrderNumber: String,
    val qcNumber: String,
    val productName: String,
    val paintColor: String,
    val packageWeightGrams: Int,
    val cansPerCarton: Int,
    val acceptedCans: Int,
    val fullCartonsReceived: Int,
    val looseCansReceived: Int,
    val targetWarehouseId: Long,
    val targetWarehouseName: String,
    val fgStorekeeperName: String,
    val receiptDate: String,
    val confirmedByStorekeeper: Boolean = true,
    val idempotencyKey: String = "FG-RCV-$batchNumber-$packageWeightGrams",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * دورة المبيعات والتسليم للعميل (طلب بيع -> اعتماد -> فاتورة -> إذن تسليم -> خروج مخزني -> إثبات تسليم)
 */
data class SalesDeliveryCycleRecord(
    val salesOrderNo: String,
    val invoiceNo: String,
    val deliveryNoteNo: String,
    val gatePassNo: String,
    val proofOfDeliveryNo: String,
    val customerId: Long,
    val customerName: String,
    val warehouseId: Long,
    val warehouseName: String,
    val currency: String, // SDG or EGP
    val paymentTerms: String, // نقدي, آجل, دفع جزئي
    val subtotalAmount: Double,
    val approvedDiscount: Double,
    val netTotalAmount: Double,
    val paidAtInvoiceAmount: Double,
    val remainingDebtAmount: Double,
    val dueDate: String,
    val cashboxName: String,
    val deliveryConfirmedByCustomer: Boolean = true,
    val createdBy: String,
    val approvedBy: String = "المدير",
    val idempotencyKey: String = "SALE-DLV-$invoiceNo",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * تحليل تكاليف التشغيل والربحية (Production Costing & Profitability Breakdown)
 */
data class BatchCostingAndProfitBreakdown(
    val batchNumber: String,
    val currency: String = "EGP",
    val rawMaterialsConsumedCost: Double,
    val emptyCansCost: Double,
    val lidsCost: Double,
    val labelsCost: Double,
    val cartonsCost: Double,
    val directLaborCost: Double,
    val manufacturingOverheadCost: Double,
    val wasteCost: Double,
    val goodApprovedCans: Int,
    val goodApprovedCartons: Int,
    val soldCartonsCount: Double = 0.0,
    val unitSellingPricePerCarton: Double = 0.0,
    val operatingExpensesAllocated: Double = 0.0
) {
    val totalPackagingCost: Double
        get() = emptyCansCost + lidsCost + labelsCost + cartonsCost

    val totalBatchManufacturingCost: Double
        get() = rawMaterialsConsumedCost + totalPackagingCost + directLaborCost + manufacturingOverheadCost + wasteCost

    val unitCostPerGoodCan: Double
        get() = if (goodApprovedCans > 0) totalBatchManufacturingCost / goodApprovedCans else 0.0

    val unitCostPerGoodCarton: Double
        get() = if (goodApprovedCartons > 0) totalBatchManufacturingCost / goodApprovedCartons else 0.0

    val costOfGoodsSold: Double
        get() = soldCartonsCount * unitCostPerGoodCarton

    val totalSalesRevenue: Double
        get() = soldCartonsCount * unitSellingPricePerCarton

    val grossProfit: Double
        get() = totalSalesRevenue - costOfGoodsSold

    val netProfit: Double
        get() = grossProfit - operatingExpensesAllocated
}

/**
 * المحرك المركزي لمنظومة PMP PAINTS Enterprise ERP
 */
object ErpLifecycleEngine {

    private const val PREFS_NAME = "pmp_enterprise_erp_store_v1"
    private const val KEY_PACKAGING_RULES = "packaging_rules_json"
    private const val KEY_PURCHASE_REQS = "purchase_reqs_json"
    private const val KEY_PURCHASE_ORDERS = "purchase_orders_json"
    private const val KEY_GOODS_RECEIPTS = "goods_receipts_json"
    private const val KEY_PRODUCTION_ORDERS = "production_orders_json"
    private const val KEY_QC_INSPECTIONS = "qc_inspections_json"
    private const val KEY_FG_RECEIPTS = "fg_receipts_json"
    private const val KEY_SALES_DELIVERIES = "sales_deliveries_json"
    private const val KEY_PROCESSED_IDEMPOTENCY_KEYS = "processed_idempotency_keys"

    /**
     * فئات التعبئة المعتمدة افتراضياً:
     * 250 جرام -> 48 علبة في الكرتونة
     * 900 جرام -> 12 علبة في الكرتونة
     * 4000 جرام -> 4 علب في الكرتونة
     */
    val DEFAULT_PACKAGING_RULES: List<PackagingRuleSpec> = listOf(
        PackagingRuleSpec(
            weightGrams = 250,
            label = "250 جرام (48 علبة/كرتونة)",
            cansPerCarton = 48,
            defaultEmptyCanCostEgp = 3.2,
            defaultLidCostEgp = 0.9,
            defaultLabelCostEgp = 0.6,
            defaultCartonBoxCostEgp = 16.0
        ),
        PackagingRuleSpec(
            weightGrams = 900,
            label = "900 جرام (12 علبة/كرتونة)",
            cansPerCarton = 12,
            defaultEmptyCanCostEgp = 5.5,
            defaultLidCostEgp = 1.4,
            defaultLabelCostEgp = 0.9,
            defaultCartonBoxCostEgp = 18.0
        ),
        PackagingRuleSpec(
            weightGrams = 4000,
            label = "4000 جرام - جالون (4 علب/كرتونة)",
            cansPerCarton = 4,
            defaultEmptyCanCostEgp = 14.0,
            defaultLidCostEgp = 3.5,
            defaultLabelCostEgp = 1.8,
            defaultCartonBoxCostEgp = 22.0
        )
    )

    fun resolvePackagingRule(
        weightGramsOrText: String,
        customRules: List<PackagingRuleSpec> = DEFAULT_PACKAGING_RULES
    ): PackagingRuleSpec {
        val digits = weightGramsOrText.filter { it.isDigit() }.toIntOrNull()
        val matched = when {
            digits != null -> customRules.find { it.weightGrams == digits }
            weightGramsOrText.contains("جالون") || weightGramsOrText.contains("4000") ->
                customRules.find { it.weightGrams == 4000 }
            weightGramsOrText.contains("250") || weightGramsOrText.contains("ربع") ->
                customRules.find { it.weightGrams == 250 }
            else -> customRules.find { it.weightGrams == 900 }
        }
        return matched ?: PackagingRuleSpec(
            weightGrams = digits ?: 900,
            label = "${digits ?: 900} جرام",
            cansPerCarton = when (digits) {
                250 -> 48
                4000 -> 4
                else -> 12
            }
        )
    }

    /**
     * التحقق الصارم من أن حساب Admin فقط هو المخول بإدارة حسابات المستخدمين وتحديد أو تعديل صلاحياتهم
     * أو تعديل قواعد التعبئة المركزية
     */
    fun canManageUsersAndPermissions(actor: UserAccountEntity?): Boolean {
        return actor.isSuperAdminAccount()
    }

    fun canModifyCentralPackagingRules(actor: UserAccountEntity?): Boolean {
        return actor.isSuperAdminAccount()
    }

    /**
     * مصفوفة الصلاحيات الدقيقة حسب الدور والوحدة ونوع العملية مع الحفاظ على الصلاحيات الحالية
     */
    fun hasGranularPermission(
        user: UserAccountEntity?,
        module: String,
        action: ErpActionPermission
    ): Boolean {
        if (user == null || !user.active) return false
        if (user.isSuperAdminAccount()) return true

        // المدير يملك الاطلاع والاعتماد والإشراف دون إدارة صلاحيات المستخدمين أو إعدادات Admin الحساسة
        if (user.isSystemManager()) {
            if (module == "المستخدمين" && action in listOf(
                    ErpActionPermission.CREATE,
                    ErpActionPermission.EDIT,
                    ErpActionPermission.APPROVE,
                    ErpActionPermission.MANAGE_SETTINGS
                )
            ) {
                return false
            }
            if (action == ErpActionPermission.MANAGE_SETTINGS) {
                return false
            }
            return true
        }

        val assignedPerms = user.parsedPermissionsSet()
        val role = user.role

        return when (action) {
            ErpActionPermission.MANAGE_SETTINGS -> false
            ErpActionPermission.EXPORT -> "تصدير PDF" in assignedPerms || "التقارير (كاملة)" in assignedPerms
            ErpActionPermission.REVERSE -> "صلاحية الحذف والتصفير" in assignedPerms
            ErpActionPermission.APPROVE, ErpActionPermission.REJECT -> {
                role.contains("مدير") || role.contains("جودة") || "اعتماد المستندات" in assignedPerms
            }
            ErpActionPermission.RECEIVE -> {
                role.contains("أمين مخزن") || "المخازن" in assignedPerms || "مواد الإنتاج" in assignedPerms
            }
            ErpActionPermission.ISSUE -> {
                role.contains("أمين مخزن") || "المخازن" in assignedPerms || "مواد الإنتاج" in assignedPerms
            }
            ErpActionPermission.TRANSFER -> {
                "التحويلات" in assignedPerms || "المخازن" in assignedPerms
            }
            ErpActionPermission.VIEW, ErpActionPermission.CREATE, ErpActionPermission.EDIT, ErpActionPermission.SUBMIT -> {
                module in assignedPerms ||
                    (module == "المشتريات" && ("المشتريات" in assignedPerms || "مواد الإنتاج" in assignedPerms)) ||
                    (module == "المصنع" && ("المصنع" in assignedPerms || "التعبئة والتغليف" in assignedPerms || "مواد الإنتاج" in assignedPerms))
            }
        }
    }

    /**
     * فصل المهام (Segregation of Duties):
     * يمنع الموظف من اعتماد مستنده الشخصي في العمليات الحساسة إلا لحساب Admin أو استثناء موثق
     */
    fun canApproveDocument(
        approver: UserAccountEntity?,
        documentCreatorUsername: String,
        allowDocumentedEmergencyOverride: Boolean = false
    ): Boolean {
        if (approver == null || !approver.active) return false
        if (approver.isSuperAdminAccount()) return true
        if (!hasGranularPermission(approver, "الاعتماد", ErpActionPermission.APPROVE)) return false
        if (approver.username.equals(documentCreatorUsername.trim(), ignoreCase = true)) {
            return allowDocumentedEmergencyOverride && approver.isSystemManager()
        }
        return true
    }

    /**
     * تحويلات وحدات القياس المعتمدة للمواد الخام والمنتجات
     * يدعم: كجم، جم، لتر، مل، طن، برميل، مع التحويل بين الوزن والحجم عبر الكثافة النوعية المعتمدة
     */
    fun convertQuantity(
        amount: Double,
        fromUnit: String,
        toUnit: String,
        densityKgPerLiter: Double = 1.0
    ): Double {
        if (fromUnit.trim().equals(toUnit.trim(), ignoreCase = true)) return amount
        val normFrom = normalizeUnit(fromUnit)
        val normTo = normalizeUnit(toUnit)
        if (normFrom == normTo) return amount

        val safeDensity = if (densityKgPerLiter > 0.0) densityKgPerLiter else 1.0

        // Convert source to base (kg for mass, liter for volume)
        val inKg: Double? = when (normFrom) {
            "KG" -> amount
            "G" -> amount / 1000.0
            "TON" -> amount * 1000.0
            "L" -> amount * safeDensity
            "ML" -> (amount / 1000.0) * safeDensity
            else -> null
        }

        if (inKg != null) {
            return when (normTo) {
                "KG" -> inKg
                "G" -> inKg * 1000.0
                "TON" -> inKg / 1000.0
                "L" -> inKg / safeDensity
                "ML" -> (inKg / safeDensity) * 1000.0
                else -> amount
            }
        }
        return amount
    }

    private fun normalizeUnit(unit: String): String {
        val u = unit.trim().lowercase(Locale.ROOT)
        return when {
            u in listOf("كجم", "كيلو", "كيلوجرام", "kg") -> "KG"
            u in listOf("جم", "جرام", "غ", "غرام", "g", "gm") -> "G"
            u in listOf("طن", "ton") -> "TON"
            u in listOf("لتر", "ليتر", "l", "liter") -> "L"
            u in listOf("مل", "ملليلتر", "ملل", "ml") -> "ML"
            else -> u.uppercase(Locale.ROOT)
        }
    }

    /**
     * حساب احتياجات المواد الخام النظرية تلقائياً من تركيبة المنتج المعتمدة (BOM) وحجم التشغيلة بالكيلوجرام
     */
    fun calculateTheoreticalBomRequirements(
        formulation: FormulationEntity,
        targetProductionWeightKg: Double
    ): List<ProductionBomRequirementLine> {
        val ingredients = parseFormulationIngredients(formulation.ingredientsJson)
        val baseBatchWeight = formulation.totalBatchWeight.takeIf { it > 0.0 }
            ?: ingredients.sumOf { it.weight }.takeIf { it > 0.0 }
            ?: 100.0
        val scaleFactor = (targetProductionWeightKg.coerceAtLeast(0.0)) / baseBatchWeight

        return ingredients.map { ing ->
            val requiredKg = kotlin.math.round((ing.weight * scaleFactor) * 1000.0) / 1000.0
            ProductionBomRequirementLine(
                materialName = ing.name,
                materialType = ing.type,
                unit = "كجم",
                theoreticalRequiredKg = requiredKg,
                unitCostEgp = ing.unitCost
            )
        }
    }

    fun parseFormulationIngredients(jsonStr: String): List<FormulationIngredient> {
        if (jsonStr.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<FormulationIngredient>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    FormulationIngredient(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", ""),
                        type = obj.optString("type", "خامات إنتاج"),
                        weight = obj.optDouble("weight", 0.0),
                        density = obj.optDouble("density", 1.0),
                        solidsPercent = obj.optDouble("solidsPercent", 100.0),
                        unitCost = obj.optDouble("unitCost", 0.0),
                        notes = obj.optString("notes", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * التحقق من مطابقة استلام شحنة المواد الخام (GRN) مع أمر الشراء (PO)
     */
    fun evaluateGoodsReceipt(
        po: PurchaseOrderRecord,
        storekeeperName: String,
        actualReceivedQty: Double,
        acceptedQty: Double,
        rejectedQty: Double,
        discrepancyReason: String,
        notes: String = ""
    ): GoodsReceiptNoteRecord {
        require(actualReceivedQty >= 0.0) { "الكمية المستلمة يجب أن تكون صفراً أو أكبر" }
        require(acceptedQty >= 0.0 && rejectedQty >= 0.0) { "الكميات المقبولة والمرفوضة لا يمكن أن تكون سالبة" }
        require(kotlin.math.abs(actualReceivedQty - (acceptedQty + rejectedQty)) < 0.001) {
            "الكمية المستلمة فعلياً ($actualReceivedQty) يجب أن تساوي مجموع الكمية المقبولة ($acceptedQty) والمرفوضة ($rejectedQty)"
        }

        val expectedRemaining = po.remainingQtyToReceive.takeIf { it > 0.0 } ?: po.orderedQty
        val hasDiscrepancy = kotlin.math.abs(actualReceivedQty - expectedRemaining) > 0.001 || rejectedQty > 0.0
        if (hasDiscrepancy && discrepancyReason.isBlank()) {
            throw IllegalArgumentException("يجب تحديد سبب النقص أو الزيادة أو التلف عند وجود فرق في الشحنة")
        }

        val status = when {
            acceptedQty == 0.0 && rejectedQty > 0.0 -> GoodsReceiptStatus.REJECTED
            hasDiscrepancy -> GoodsReceiptStatus.DISCREPANCY_REVIEW
            actualReceivedQty < expectedRemaining -> GoodsReceiptStatus.PARTIAL_RECEIPT
            else -> GoodsReceiptStatus.ACCEPTED
        }

        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return GoodsReceiptNoteRecord(
            grnNumber = "GRN-${System.currentTimeMillis().toString().takeLast(6)}",
            poNumber = po.poNumber,
            receiptDate = dateStr,
            storekeeperName = storekeeperName,
            materialId = po.materialId,
            materialName = po.materialName,
            unit = po.unit,
            expectedQty = expectedRemaining,
            actualReceivedQty = actualReceivedQty,
            acceptedQty = acceptedQty,
            rejectedQty = rejectedQty,
            discrepancyReason = discrepancyReason.trim(),
            notes = notes.trim(),
            status = status,
            stockPosted = false
        )
    }

    /**
     * فحص الجودة QC المرتبط بمواصفات التركيبة المعتمدة (الكثافة واللزوجة والمظهر)
     * لا يخترع حدوداً ثابتة لكل الدهانات بل يشتق نطاق القبول من الكثافة المعتمدة للتركيبة نفسها (±3%)
     */
    fun performQualityControlInspection(
        batchNumber: String,
        productionOrderNumber: String,
        formulation: FormulationEntity?,
        inspectorName: String,
        measuredDensity: Double,
        measuredViscosity: String,
        appearanceAndColorMatch: Boolean,
        goodCandidateCans: Int,
        damagedCans: Int,
        reworkCans: Int = 0,
        cansPerCarton: Int,
        notes: String = ""
    ): QualityControlInspectionRecord {
        val targetDensity = formulation?.calculatedDensity?.takeIf { it > 0.0 } ?: measuredDensity.takeIf { it > 0.0 } ?: 1.20
        val minDensity = kotlin.math.round((targetDensity * 0.97) * 1000.0) / 1000.0
        val maxDensity = kotlin.math.round((targetDensity * 1.03) * 1000.0) / 1000.0
        val densityInSpec = measuredDensity <= 0.0 || (measuredDensity in minDensity..maxDensity)

        val isPassed = densityInSpec && appearanceAndColorMatch && goodCandidateCans > 0
        val acceptedCans = if (isPassed) goodCandidateCans else 0
        val totalRejected = if (isPassed) damagedCans else (goodCandidateCans + damagedCans)
        val acceptedCartons = if (cansPerCarton > 0) acceptedCans / cansPerCarton else 0

        val resultStatus = when {
            isPassed -> "مقبول"
            reworkCans > 0 -> "مشروط / إعادة تشغيل"
            else -> "مرفوض"
        }

        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return QualityControlInspectionRecord(
            qcNumber = "QC-${batchNumber.replace("PB-", "")}",
            batchNumber = batchNumber,
            productionOrderNumber = productionOrderNumber,
            inspectorName = inspectorName,
            inspectionDate = dateStr,
            measuredDensity = measuredDensity,
            expectedMinDensity = minDensity,
            expectedMaxDensity = maxDensity,
            measuredViscosity = measuredViscosity,
            appearanceAndColorMatch = appearanceAndColorMatch,
            acceptedCans = acceptedCans,
            rejectedCans = totalRejected,
            reworkCans = reworkCans,
            acceptedCartons = acceptedCartons,
            resultStatus = resultStatus,
            notes = notes
        )
    }

    /**
     * حساب تكاليف التشغيل والهالك وتكلفة البضاعة المباعة ومجمل وصافي الربح
     */
    fun calculateBatchCostingAndProfit(
        batchNumber: String,
        currency: String = "EGP",
        bomLines: List<ProductionBomRequirementLine>,
        packagingSpec: PackagingRuleSpec,
        actualProducedCans: Int,
        goodApprovedCans: Int,
        damagedCans: Int,
        directLaborCost: Double,
        manufacturingOverheadCost: Double,
        soldCartonsCount: Double = 0.0,
        unitSellingPricePerCarton: Double = 0.0,
        operatingExpensesAllocated: Double = 0.0
    ): BatchCostingAndProfitBreakdown {
        val rawConsumedCost = bomLines.sumOf { line ->
            val consumed = if (line.actuallyConsumedKg > 0.0) line.actuallyConsumedKg else line.theoreticalRequiredKg
            consumed * line.unitCostEgp
        }
        val rawWasteCost = bomLines.sumOf { line ->
            line.wastedKg * line.unitCostEgp
        }

        val fullCartons = packagingSpec.calculateFullCartons(goodApprovedCans)
        val emptyCansCost = actualProducedCans * packagingSpec.defaultEmptyCanCostEgp
        val lidsCost = actualProducedCans * packagingSpec.defaultLidCostEgp
        val labelsCost = goodApprovedCans * packagingSpec.defaultLabelCostEgp
        val cartonsCost = fullCartons * packagingSpec.defaultCartonBoxCostEgp

        val damagedPackagingWasteCost = damagedCans * (packagingSpec.defaultEmptyCanCostEgp + packagingSpec.defaultLidCostEgp)
        val totalWasteCost = rawWasteCost + damagedPackagingWasteCost

        return BatchCostingAndProfitBreakdown(
            batchNumber = batchNumber,
            currency = currency,
            rawMaterialsConsumedCost = rawConsumedCost,
            emptyCansCost = emptyCansCost,
            lidsCost = lidsCost,
            labelsCost = labelsCost,
            cartonsCost = cartonsCost,
            directLaborCost = directLaborCost,
            manufacturingOverheadCost = manufacturingOverheadCost,
            wasteCost = totalWasteCost,
            goodApprovedCans = goodApprovedCans,
            goodApprovedCartons = fullCartons,
            soldCartonsCount = soldCartonsCount,
            unitSellingPricePerCarton = unitSellingPricePerCarton,
            operatingExpensesAllocated = operatingExpensesAllocated
        )
    }

    // --- Persistence & Idempotency Guards ---

    fun getPackagingRules(context: Context): List<PackagingRuleSpec> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PACKAGING_RULES, null) ?: return DEFAULT_PACKAGING_RULES
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<PackagingRuleSpec>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    PackagingRuleSpec(
                        weightGrams = o.getInt("weightGrams"),
                        label = o.optString("label", "${o.getInt("weightGrams")} جرام"),
                        cansPerCarton = o.getInt("cansPerCarton"),
                        defaultEmptyCanCostEgp = o.optDouble("defaultEmptyCanCostEgp", 4.5),
                        defaultLidCostEgp = o.optDouble("defaultLidCostEgp", 1.2),
                        defaultLabelCostEgp = o.optDouble("defaultLabelCostEgp", 0.8),
                        defaultCartonBoxCostEgp = o.optDouble("defaultCartonBoxCostEgp", 18.0)
                    )
                )
            }
            if (list.isEmpty()) DEFAULT_PACKAGING_RULES else list
        } catch (_: Exception) {
            DEFAULT_PACKAGING_RULES
        }
    }

    fun updatePackagingRuleAsAdmin(
        context: Context,
        actor: UserAccountEntity?,
        weightGrams: Int,
        newCansPerCarton: Int
    ): Result<List<PackagingRuleSpec>> {
        if (!canModifyCentralPackagingRules(actor)) {
            return Result.failure(SecurityException("حساب Admin فقط يملك صلاحية تعديل قواعد التعبئة المركزية."))
        }
        if (weightGrams <= 0 || newCansPerCarton <= 0) {
            return Result.failure(IllegalArgumentException("وزن العبوة وعدد العلب في الكرتونة يجب أن يكونا أكبر من صفر."))
        }
        val current = getPackagingRules(context).toMutableList()
        val idx = current.indexOfFirst { it.weightGrams == weightGrams }
        val updatedSpec = if (idx >= 0) {
            current[idx].copy(
                cansPerCarton = newCansPerCarton,
                label = "$weightGrams جرام ($newCansPerCarton علبة/كرتونة)"
            )
        } else {
            PackagingRuleSpec(
                weightGrams = weightGrams,
                label = "$weightGrams جرام ($newCansPerCarton علبة/كرتونة)",
                cansPerCarton = newCansPerCarton
            )
        }
        if (idx >= 0) current[idx] = updatedSpec else current.add(updatedSpec)

        val arr = JSONArray()
        current.sortedBy { it.weightGrams }.forEach { spec ->
            arr.put(
                JSONObject().apply {
                    put("weightGrams", spec.weightGrams)
                    put("label", spec.label)
                    put("cansPerCarton", spec.cansPerCarton)
                    put("defaultEmptyCanCostEgp", spec.defaultEmptyCanCostEgp)
                    put("defaultLidCostEgp", spec.defaultLidCostEgp)
                    put("defaultLabelCostEgp", spec.defaultLabelCostEgp)
                    put("defaultCartonBoxCostEgp", spec.defaultCartonBoxCostEgp)
                }
            )
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PACKAGING_RULES, arr.toString())
            .apply()
        return Result.success(current.sortedBy { it.weightGrams })
    }

    /**
     * التحقق من عدم تكرار تنفيذ نفس العملية الحساسة (Idempotency) عند انقطاع الإنترنت أو المزامنة بين الأجهزة
     */
    @Synchronized
    fun markAndCheckIdempotencyKey(context: Context, idempotencyKey: String): Boolean {
        val cleanKey = idempotencyKey.trim()
        if (cleanKey.isBlank()) return true
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getStringSet(KEY_PROCESSED_IDEMPOTENCY_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (cleanKey in existing) {
            return false // Already processed! Prevent duplicate stock/financial impact.
        }
        existing.add(cleanKey)
        prefs.edit().putStringSet(KEY_PROCESSED_IDEMPOTENCY_KEYS, existing).apply()
        return true
    }

    fun isIdempotencyKeyProcessed(context: Context, idempotencyKey: String): Boolean {
        val cleanKey = idempotencyKey.trim()
        if (cleanKey.isBlank()) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getStringSet(KEY_PROCESSED_IDEMPOTENCY_KEYS, emptySet()).orEmpty()
        return cleanKey in existing
    }

    // --- Purchase Orders & GRN Local Store ---

    fun getPurchaseOrders(context: Context): List<PurchaseOrderRecord> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PURCHASE_ORDERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<PurchaseOrderRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    PurchaseOrderRecord(
                        poNumber = o.getString("poNumber"),
                        reqNumber = o.optString("reqNumber", ""),
                        orderDate = o.getString("orderDate"),
                        supplierName = o.getString("supplierName"),
                        materialId = o.optLong("materialId", 0L),
                        materialCode = o.optString("materialCode", "RAW-01"),
                        materialName = o.getString("materialName"),
                        orderedQty = o.getDouble("orderedQty"),
                        unit = o.optString("unit", "كجم"),
                        unitPrice = o.getDouble("unitPrice"),
                        currency = o.optString("currency", "EGP"),
                        additionalExpenses = o.optDouble("additionalExpenses", 0.0),
                        expectedDeliveryDate = o.optString("expectedDeliveryDate", ""),
                        createdBy = o.optString("createdBy", "مسؤول المشتريات"),
                        approvalStatus = o.optString("approvalStatus", "معتمد"),
                        approvedBy = o.optString("approvedBy", "المدير"),
                        receivedTotalQty = o.optDouble("receivedTotalQty", 0.0),
                        acceptedTotalQty = o.optDouble("acceptedTotalQty", 0.0),
                        notes = o.optString("notes", ""),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            list.sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun savePurchaseOrder(context: Context, po: PurchaseOrderRecord): List<PurchaseOrderRecord> {
        val current = getPurchaseOrders(context).filterNot { it.poNumber == po.poNumber }.toMutableList()
        current.add(0, po)
        val arr = JSONArray()
        current.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("poNumber", item.poNumber)
                    put("reqNumber", item.reqNumber)
                    put("orderDate", item.orderDate)
                    put("supplierName", item.supplierName)
                    put("materialId", item.materialId)
                    put("materialCode", item.materialCode)
                    put("materialName", item.materialName)
                    put("orderedQty", item.orderedQty)
                    put("unit", item.unit)
                    put("unitPrice", item.unitPrice)
                    put("currency", item.currency)
                    put("additionalExpenses", item.additionalExpenses)
                    put("expectedDeliveryDate", item.expectedDeliveryDate)
                    put("createdBy", item.createdBy)
                    put("approvalStatus", item.approvalStatus)
                    put("approvedBy", item.approvedBy)
                    put("receivedTotalQty", item.receivedTotalQty)
                    put("acceptedTotalQty", item.acceptedTotalQty)
                    put("notes", item.notes)
                    put("createdAt", item.createdAt)
                }
            )
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PURCHASE_ORDERS, arr.toString())
            .apply()
        return current
    }

    fun getGoodsReceipts(context: Context): List<GoodsReceiptNoteRecord> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_GOODS_RECEIPTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<GoodsReceiptNoteRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val statusName = o.optString("status", GoodsReceiptStatus.ACCEPTED.name)
                val status = runCatching { GoodsReceiptStatus.valueOf(statusName) }
                    .getOrDefault(GoodsReceiptStatus.ACCEPTED)
                list.add(
                    GoodsReceiptNoteRecord(
                        grnNumber = o.getString("grnNumber"),
                        poNumber = o.getString("poNumber"),
                        receiptDate = o.getString("receiptDate"),
                        storekeeperName = o.optString("storekeeperName", "أمين مخزن الخام"),
                        materialId = o.optLong("materialId", 0L),
                        materialName = o.getString("materialName"),
                        unit = o.optString("unit", "كجم"),
                        expectedQty = o.getDouble("expectedQty"),
                        actualReceivedQty = o.getDouble("actualReceivedQty"),
                        acceptedQty = o.getDouble("acceptedQty"),
                        rejectedQty = o.getDouble("rejectedQty"),
                        discrepancyReason = o.optString("discrepancyReason", ""),
                        notes = o.optString("notes", ""),
                        attachmentRef = o.optString("attachmentRef", ""),
                        status = status,
                        approvedByManager = o.optString("approvedByManager", ""),
                        stockPosted = o.optBoolean("stockPosted", false),
                        idempotencyKey = o.optString("idempotencyKey", "GRN-${o.getString("grnNumber")}"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            list.sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveGoodsReceipt(context: Context, grn: GoodsReceiptNoteRecord): List<GoodsReceiptNoteRecord> {
        val current = getGoodsReceipts(context).filterNot { it.grnNumber == grn.grnNumber }.toMutableList()
        current.add(0, grn)
        val arr = JSONArray()
        current.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("grnNumber", item.grnNumber)
                    put("poNumber", item.poNumber)
                    put("receiptDate", item.receiptDate)
                    put("storekeeperName", item.storekeeperName)
                    put("materialId", item.materialId)
                    put("materialName", item.materialName)
                    put("unit", item.unit)
                    put("expectedQty", item.expectedQty)
                    put("actualReceivedQty", item.actualReceivedQty)
                    put("acceptedQty", item.acceptedQty)
                    put("rejectedQty", item.rejectedQty)
                    put("discrepancyReason", item.discrepancyReason)
                    put("notes", item.notes)
                    put("attachmentRef", item.attachmentRef)
                    put("status", item.status.name)
                    put("approvedByManager", item.approvedByManager)
                    put("stockPosted", item.stockPosted)
                    put("idempotencyKey", item.idempotencyKey)
                    put("createdAt", item.createdAt)
                }
            )
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GOODS_RECEIPTS, arr.toString())
            .apply()
        return current
    }

    fun getProductionOrders(context: Context): List<ProductionOrderRecord> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PRODUCTION_ORDERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ProductionOrderRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val status = runCatching {
                    ProductionOrderStatus.valueOf(o.optString("status", ProductionOrderStatus.APPROVED.name))
                }.getOrDefault(ProductionOrderStatus.APPROVED)
                list.add(
                    ProductionOrderRecord(
                        orderNumber = o.getString("orderNumber"),
                        createdDate = o.getString("createdDate"),
                        executionDate = o.optString("executionDate", o.getString("createdDate")),
                        productType = o.optString("productType", "دهان مائي"),
                        formulaId = if (o.has("formulaId")) o.optLong("formulaId") else null,
                        formulaNo = o.optString("formulaNo", ""),
                        formulaName = o.optString("formulaName", ""),
                        paintColor = o.optString("paintColor", "أبيض"),
                        packageWeightGrams = o.optInt("packageWeightGrams", 900),
                        targetWeightKg = o.optDouble("targetWeightKg", 100.0),
                        targetCartons = o.optInt("targetCartons", 10),
                        productionLine = o.optString("productionLine", "خط الإنتاج الرئيسي 1"),
                        machineName = o.optString("machineName", "ماكينة التعبئة الآلية 1"),
                        productionEngineer = o.optString("productionEngineer", "مهندس الإنتاج"),
                        status = status,
                        approvedBy = o.optString("approvedBy", "المدير"),
                        issueVoucherNo = o.optString("issueVoucherNo", ""),
                        engineerReceiptConfirmed = o.optBoolean("engineerReceiptConfirmed", false),
                        engineerReceiptTimestamp = o.optLong("engineerReceiptTimestamp", 0L),
                        batchNumber = o.optString("batchNumber", ""),
                        damageReason = o.optString("damageReason", ""),
                        notes = o.optString("notes", ""),
                        idempotencyKey = o.optString("idempotencyKey", "PROD-ORD-${o.getString("orderNumber")}"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            list.sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveProductionOrder(context: Context, order: ProductionOrderRecord): List<ProductionOrderRecord> {
        val current = getProductionOrders(context).filterNot { it.orderNumber == order.orderNumber }.toMutableList()
        current.add(0, order)
        val arr = JSONArray()
        current.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("orderNumber", item.orderNumber)
                    put("createdDate", item.createdDate)
                    put("executionDate", item.executionDate)
                    put("productType", item.productType)
                    if (item.formulaId != null) put("formulaId", item.formulaId)
                    put("formulaNo", item.formulaNo)
                    put("formulaName", item.formulaName)
                    put("paintColor", item.paintColor)
                    put("packageWeightGrams", item.packageWeightGrams)
                    put("targetWeightKg", item.targetWeightKg)
                    put("targetCartons", item.targetCartons)
                    put("productionLine", item.productionLine)
                    put("machineName", item.machineName)
                    put("productionEngineer", item.productionEngineer)
                    put("status", item.status.name)
                    put("approvedBy", item.approvedBy)
                    put("issueVoucherNo", item.issueVoucherNo)
                    put("engineerReceiptConfirmed", item.engineerReceiptConfirmed)
                    put("engineerReceiptTimestamp", item.engineerReceiptTimestamp)
                    put("batchNumber", item.batchNumber)
                    put("damageReason", item.damageReason)
                    put("notes", item.notes)
                    put("idempotencyKey", item.idempotencyKey)
                    put("createdAt", item.createdAt)
                }
            )
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRODUCTION_ORDERS, arr.toString())
            .apply()
        return current
    }
}
