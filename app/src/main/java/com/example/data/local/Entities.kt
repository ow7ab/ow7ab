package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cashboxes")
data class CashboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Entity(tableName = "movements")
data class MovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val txDate: String,
    val cashbox: String,
    val currency: String, // SDG, EGP, USD
    val direction: String, // وارد, منصرف
    val amount: Double,
    val description: String,
    val reference: String,
    val createdBy: String = "المدير",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reference: String,
    val txDate: String,
    val sourceCashbox: String,
    val destinationCashbox: String,
    val sourceCurrency: String,
    val destinationCurrency: String,
    val sourceAmount: Double,
    val rate: Double,
    val destinationAmount: Double,
    val description: String,
    val halfaInvoiceId: Long? = null,
    val halfaAmount: Double? = null,
    val createdBy: String = "المدير",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "daily_closings")
data class DailyClosingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val closeDate: String,
    val cashbox: String,
    val currency: String,
    val bookBalance: Double,
    val countedBalance: Double,
    val difference: Double,
    val notes: String = "",
    val preparedBy: String = "المدير",
    val status: String = "closed", // closed, reopened
    val closedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val address: String = "",
    val notes: String = "",
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "invoices")
data class InvoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceNo: String,
    val customerId: Long,
    val customerName: String,
    val invoiceDate: String,
    val currency: String,
    val subtotal: Double,
    val discount: Double,
    val total: Double,
    val notes: String = "",
    val status: String = "active", // active, cancelled
    val cancelReason: String? = null,
    val createdBy: String = "المدير",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "invoice_items")
data class InvoiceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val description: String,
    val quantity: Double,
    val unitPrice: Double,
    val lineTotal: Double,
    val productId: Long? = null,
    val warehouseId: Long? = null
)

@Entity(tableName = "invoice_payments")
data class InvoicePaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val payDate: String,
    val cashbox: String,
    val amount: Double,
    val note: String = "",
    val status: String = "active", // active, reversed
    val reversalReason: String? = null,
    val createdBy: String = "المدير",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "warehouses")
data class WarehouseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val active: Boolean = true,
    val currency: String = "SDG", // SDG or EGP
    val linkedCashbox: String = "خزنة السودان",
    val warehouseType: String = "مبيعات" // مبيعات, داخل المصنع/إنتاج, مواد خام
)

fun WarehouseEntity.isFactory(): Boolean {
    return name.contains("مصر") || name.contains("المصنع") || name.contains("القاهرة") || currency.equals("EGP", ignoreCase = true)
}

fun WarehouseEntity.resolveCurrency(): String {
    return if (isFactory()) "EGP" else currency.ifBlank { "SDG" }
}

fun WarehouseEntity.resolveLinkedCashbox(): String {
    if (linkedCashbox.isNotBlank() && linkedCashbox != "خزنة عامة") return linkedCashbox
    return when {
        name.contains("حلفا") -> "خزنة حلفا"
        name.contains("عطبرة") -> "خزنة عطبرة"
        isFactory() -> "خزنة مصر"
        else -> "خزنة السودان"
    }
}

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sku: String,
    val name: String,
    val category: String = "بوهيات",
    val unit: String = "كرتونة",
    val purchasePrice: Double = 0.0,
    val salePrice: Double = 0.0, // SDG for Sudan/Halfa
    val salePriceEgp: Double = 0.0, // EGP for Factory/Egypt
    val minimumStock: Double = 10.0,
    val packageWeight: String = "900", // 250, 900, جالون, ربع
    val paintColor: String = "أبيض", // أبيض، ألوان، فضي، ذهبي، ورنيش
    val active: Boolean = true
)

fun ProductEntity.getEffectiveSalePrice(currency: String): Double {
    return if (currency.equals("EGP", ignoreCase = true)) {
        if (salePriceEgp > 0) salePriceEgp else (salePrice / 200.0).coerceAtLeast(1.0)
    } else {
        if (salePrice > 0) salePrice else (salePriceEgp * 200.0).coerceAtLeast(1.0)
    }
}

@Entity(tableName = "stock_moves")
data class StockMoveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val warehouseId: Long,
    val productId: Long,
    val quantity: Double,
    val kind: String, // IN, OUT, SALE, RETURN
    val invoiceId: Long? = null,
    val note: String = "",
    val createdBy: String = "المدير",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "factory_items")
data class FactoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val unit: String,
    val category: String // raw, finished, empty
)

@Entity(tableName = "factory_machines")
data class FactoryMachineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Entity(tableName = "factory_moves")
data class FactoryMoveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val moveDate: String,
    val kind: String,
    val itemId: Long,
    val itemName: String,
    val qty: Double,
    val machineId: Long? = null,
    val machineName: String? = null,
    val batch: String = "",
    val note: String = "",
    val actor: String = "مهندس الإنتاج",
    val counterparty: String = "",
    val linked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "halfa_invoices")
data class HalfaInvoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceNo: String,
    val invoiceDate: String,
    val cashbox: String = "خزنة مصر",
    val currency: String = "EGP",
    val warehouseId: Long,
    val productId: Long,
    val productName: String,
    val quantity: Double,
    val unitPrice: Double,
    val total: Double,
    val factoryMoveId: Long? = null,
    val note: String = "",
    val status: String = "active", // active, cancelled
    val createdBy: String = "مسؤول التحميل",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "poster_prices")
data class PosterPriceEntity(
    @PrimaryKey val id: String, // e.g. "900_أبيض" or "900_أبيض_EGP"
    val weight: String, // 250, 900
    val kind: String,   // أبيض، ألوان، فضي، ذهبي، ورنيش
    val price: Long,
    val priceEgp: Long = 0L,
    val currency: String = "SDG"
)

@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val action: String,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_accounts")
data class UserAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userCode: String,
    val fullName: String,
    val username: String,
    val phoneOrEmail: String = "",
    val pinOrPassword: String = "1234",
    val role: String,
    val branch: String,
    val permissions: String,
    val serverSyncToken: String,
    val remoteServerId: String = "",
    val syncStatus: String = "READY_FOR_SYNC", // READY_FOR_SYNC, PENDING_LINK, LINKED
    val serverEndpoint: String = "https://api.pmp-paints-erp.com/v1",
    val isCurrentSession: Boolean = false,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "lab_formulations")
data class FormulationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val formulaNo: String, // e.g. "PMP-LAB-2026-001"
    val productName: String,
    val paintType: String = "مطفي معماري (Matt)", // Gloss, Semi-Gloss, Matt, Primer, Texture, Sealer
    val baseKind: String = "أبيض",
    val targetPvc: Double = 0.0,
    val calculatedPvc: Double = 0.0,
    val cpvcEstimated: Double = 52.0,
    val totalSolidsWeight: Double = 0.0, // نسبة المواد الصلبة بالوزن %
    val totalSolidsVolume: Double = 0.0, // نسبة المواد الصلبة بالحجم %
    val totalBatchWeight: Double = 0.0, // إجمالي الوزن (كجم)
    val totalBatchVolume: Double = 0.0, // إجمالي الحجم (لتر)
    val calculatedDensity: Double = 0.0, // الكثافة النوعية (كجم/لتر)
    val totalCost: Double = 0.0, // إجمالي تكلفة الخلطة
    val costPerKg: Double = 0.0, // تكلفة الكيلو
    val ingredientsJson: String = "[]", // JSON representation of ingredients
    val technicalAnalysis: String = "",
    val notes: String = "",
    val approvedForProduction: Boolean = false,
    val createdBy: String = "مهندس المختبر الكيميائي",
    val createdAt: Long = System.currentTimeMillis()
)

data class FormulationIngredient(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val type: String, // "صبغة / ملون", "مالئ وحبيبات", "راتنج / بوليمر رابط", "مذيب / ماء", "إضافات ومساعدات"
    val weight: Double, // كجم
    val density: Double, // جم/سم³ أو كجم/لتر
    val solidsPercent: Double = 100.0, // %
    val unitCost: Double = 0.0, // تكلفة الكيلو
    val notes: String = ""
)

@Entity(tableName = "chemical_safety")
data class ChemicalSafetyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chemicalName: String,
    val commonName: String = "",
    val casNumber: String = "",
    val molecularFormula: String = "",
    val molecularWeight: Double = 0.0,
    val ghsPictograms: String = "", // Comma-separated: FLAME, CORROSIVE, TOXIC, EXCLAMATION, HEALTH_HAZARD, ENVIRONMENT
    val signalWord: String = "تحذير", // "خطر" or "تحذير"
    val hazardStatements: String = "", // H-Statements
    val precautionaryStatements: String = "", // P-Statements
    val storageConditions: String = "",
    val firstAidMeasures: String = "",
    val sdsSupplierName: String = "PMP Paints Lab Quality Registry",
    val sdsFileUrlOrUri: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

