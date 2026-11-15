package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class AccountType(val arabicLabel: String, val normalSide: String) {
    ASSET("الأصول", "DEBIT"),
    LIABILITY("الالتزامات", "CREDIT"),
    EQUITY("حقوق الملكية", "CREDIT"),
    REVENUE("الإيرادات", "CREDIT"),
    EXPENSE("المصروفات والتكاليف", "DEBIT")
}

@Entity(
    tableName = "accounting_accounts",
    indices = [
        Index(name = "idx_acc_code", value = ["accountCode"], unique = true),
        Index(name = "idx_acc_uuid", value = ["syncUuid"], unique = true)
    ]
)
data class ChartAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String = UUID.randomUUID().toString(),
    val accountCode: String,
    val nameAr: String,
    val nameEn: String = "",
    val accountType: String, // ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE
    val normalSide: String,  // DEBIT or CREDIT
    val parentCode: String = "",
    val currencyHint: String = "ALL", // ALL, SDG, EGP, USD
    val isSystemAccount: Boolean = true,
    val active: Boolean = true,
    val recordVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "journal_entries",
    indices = [
        Index(name = "idx_jrn_uuid", value = ["syncUuid"], unique = true),
        Index(name = "idx_jrn_no", value = ["entryNo"], unique = true),
        Index(name = "idx_jrn_idemp", value = ["idempotencyKey"])
    ]
)
data class JournalEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String = UUID.randomUUID().toString(),
    val entryNo: String,
    val entryDate: String,
    val sourceType: String, // PURCHASE_RECEIPT, PRODUCTION_ISSUE, QC_FG_RECEIPT, SALES_INVOICE, CUSTOMER_PAYMENT, INVOICE_CANCEL, TREASURY_MOVEMENT, TREASURY_TRANSFER, MANUAL_ADJUSTMENT, REVERSAL, OPENING_BALANCE
    val sourceDocId: Long? = null,
    val sourceDocNo: String = "",
    val description: String,
    val currency: String = "SDG",
    val fxRateToUsd: Double = 1.0,
    val fxRateToSdg: Double = 1.0,
    val totalDebit: Double = 0.0,
    val totalCredit: Double = 0.0,
    val status: String = "POSTED", // POSTED, REVERSED
    val reversedEntryId: Long? = null,
    val reversedByEntryNo: String = "",
    val reversalReason: String = "",
    val idempotencyKey: String = "",
    val createdBy: String = "النظام",
    val approvedBy: String = "",
    val deviceId: String = "ANDROID",
    val recordVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "journal_lines",
    indices = [
        Index(name = "idx_jln_entry_id", value = ["journalEntryId"]),
        Index(name = "idx_jln_acc_code", value = ["accountCode"]),
        Index(name = "idx_jln_uuid", value = ["syncUuid"], unique = true)
    ]
)
data class JournalLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String = UUID.randomUUID().toString(),
    val journalEntryId: Long,
    val journalEntryUuid: String,
    val lineOrder: Int = 1,
    val accountCode: String,
    val accountName: String,
    val accountType: String, // ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE
    val debit: Double = 0.0,
    val credit: Double = 0.0,
    val currency: String = "SDG",
    val costCenter: String = "", // RAW_WAREHOUSE, FACTORY, SALES, TREASURY, HQ
    val partyType: String = "",  // CUSTOMER, SUPPLIER, CASHBOX, WAREHOUSE, NONE
    val partyName: String = "",
    val memo: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
