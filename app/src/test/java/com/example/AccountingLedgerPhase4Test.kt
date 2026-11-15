package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.domain.accounting.*
import com.example.domain.inventory.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountingLedgerPhase4Test {

    private var db: PmpDatabase? = null

    @After
    fun tearDown() {
        db?.close()
    }

    private fun createInMemoryDb(): PmpDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, PmpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { db = it }
    }

    @Test
    fun `unbalanced manual journal entry is rejected and balanced append-only reversal zeroes net effect`() = runBlocking {
        val database = createInMemoryDb()
        val engine = AccountingLedgerEngine(database)
        val accDao = database.erpAccountingDao()

        // Unbalanced entry must fail
        val unbalanced = engine.postJournalEntry(
            JournalPostRequest(
                description = "قيد غير متوازن",
                currency = "SDG",
                lines = listOf(
                    JournalLineInput("1110", debit = 1000.0),
                    JournalLineInput("4100", credit = 950.0)
                )
            )
        )
        assertTrue(unbalanced.isFailure)
        assertEquals(0, accDao.getAllJournalEntriesDirect().size)

        // Balanced manual entry succeeds
        val balanced = engine.postJournalEntry(
            JournalPostRequest(
                description = "رأس مال افتتاحي خزنة السودان",
                currency = "SDG",
                lines = listOf(
                    JournalLineInput("1110", debit = 100000.0),
                    JournalLineInput("3100", credit = 100000.0)
                ),
                createdBy = "المدير"
            )
        ).getOrThrow()

        // Reverse entry via append-only compensator
        val reversed = engine.reverseJournalEntry(balanced.entry.id, "تعديل قيمة القيد الافتتاحي", "المدير").getOrThrow()
        assertEquals("REVERSED", accDao.getJournalEntryById(balanced.entry.id)?.status)
        assertEquals("POSTED", reversed.entry.status)

        // Double reversal fails
        val doubleReverse = engine.reverseJournalEntry(balanced.entry.id, "عكس مكرر", "المدير")
        assertTrue(doubleReverse.isFailure)

        // Trial balance stays balanced and net balances return to zero
        val tb = AccountingLedgerEngine.computeTrialBalance(
            accDao.getAllAccountsDirect(),
            accDao.getAllJournalEntriesDirect(),
            accDao.getAllJournalLinesDirect(),
            "SDG"
        )
        assertTrue(tb.isBalanced)
        assertEquals(0.0, tb.rows.firstOrNull { it.accountCode == "1110" }?.signedBalance ?: 0.0, 0.01)
    }

    @Test
    fun `end-to-end GRN, production WIP, QC finished goods, multi-currency statements & cross-currency FX clearing stay balanced`() = runBlocking {
        val database = createInMemoryDb()
        val dao = database.pmpDao()
        val accDao = database.erpAccountingDao()
        val accounting = AccountingLedgerEngine(database)
        val movementEngine = InventoryMovementEngine(database)
        val prodProcEngine = ProductionAndProcurementEngine(database, movementEngine)

        // Seed warehouses + raw material + product
        val rawWhId = dao.insertWarehouse(WarehouseEntity(name = "مخزن الخامات", currency = "EGP", warehouseType = "مواد خام", warehouseClass = WarehouseClass.RAW_MATERIALS.name, allowDirectSales = false))
        val fgWhId = dao.insertWarehouse(WarehouseEntity(name = "مخزن الإنتاج التام", currency = "EGP", warehouseType = "داخل المصنع/إنتاج", warehouseClass = WarehouseClass.FINISHED_GOODS.name, allowDirectSales = false, allowProductionReceipt = true))
        val matId = dao.insertFactoryItem(FactoryItemEntity(name = "راتنج أكريليك", unit = "كجم", category = "خامات إنتاج", unitCostEgp = 100.0))
        val prodId = dao.insertProduct(ProductEntity(sku = "P-900", name = "بوهية أبيض 900", purchasePrice = 1000.0, salePrice = 50000.0, salePriceEgp = 1500.0, packageWeight = "900", paintColor = "أبيض"))

        // 1. GRN purchase receipt -> Debit 1141 Raw Inv (100 * 100 = 10,000 EGP), Credit 2110 AP (10,000 EGP)
        val receipt = prodProcEngine.receivePurchaseToRawMaterials(
            PurchaseReceiptInput(
                supplierName = "مورد القاهرة",
                warehouseId = rawWhId,
                materialId = matId,
                materialName = "راتنج أكريليك",
                orderedQty = 100.0,
                receivedQty = 100.0,
                acceptedQty = 100.0,
                rejectedQty = 0.0,
                unitCost = 100.0,
                currency = "EGP",
                batchNumber = "B-RAW-1"
            )
        ).getOrThrow()
        assertEquals(100.0, receipt.acceptedQty, 0.01)

        // 2. Production order + Material Issue (50 kg issued, 5 kg wasted) -> DR 1142 WIP (4500), DR 5200 Waste (500), CR 1141 Raw (5000)
        val order = prodProcEngine.createProductionOrder(
            ProductionOrderCreateInput(
                productId = prodId,
                productName = "بوهية أبيض 900",
                packageWeightGrams = 900,
                targetCartons = 5,
                rawWarehouseId = rawWhId,
                fgWarehouseId = fgWhId
            )
        ).getOrThrow()

        prodProcEngine.issueRawMaterialsForOrder(
            order.id,
            listOf(
                MaterialIssueLineInput(
                    itemId = matId,
                    itemName = "راتنج أكريليك",
                    qty = 50.0,
                    wastedQty = 5.0,
                    unit = "كجم",
                    unitCost = 100.0,
                    batchNumber = "B-RAW-1"
                )
            ),
            actor = "أمين المخزن"
        ).getOrThrow()

        // 3. QC approval (60 cans = 5 cartons * 1000 costPrice = 5000 EGP? Wait 4500 WIP vs 5000 FG is fine for double entry or product cost)
        prodProcEngine.inspectQcAndPostToFinishedGoods(
            QcProductionInspectionInput(
                productionOrderId = order.id,
                measuredDensity = 1.25,
                goodCans = 60,
                rejectedCans = 0,
                reworkCans = 0
            )
        ).getOrThrow()

        // 4. Sales Invoice + COGS + Payment in SDG
        accounting.postJournalEntry(
            JournalPostRequest(
                entryDate = "2026-11-15",
                sourceType = "SALES_INVOICE",
                sourceDocNo = "INV-SDG-1",
                description = "فاتورة مبيعات الخرطوم",
                currency = "SDG",
                lines = listOf(
                    JournalLineInput("1130", debit = 200000.0),
                    JournalLineInput("4100", credit = 200000.0)
                )
            )
        ).getOrThrow()
        accounting.postCustomerPaymentJournalInternal(
            paymentId = 10L,
            referenceDocNo = "INV-SDG-1",
            payDate = "2026-11-15",
            customerName = "عميل الخرطوم",
            cashbox = "خزنة السودان",
            currency = "SDG",
            amount = 120000.0,
            actor = "أمين الخزنة"
        )

        // 5. Cross-currency cashbox transfer EGP -> SDG via 1120 FX Clearing
        accounting.postTreasuryTransferJournalInternal(
            TransferEntity(
                id = 99L,
                reference = "TRF-FX-99",
                txDate = "2026-11-15",
                sourceCashbox = "خزنة مصر",
                destinationCashbox = "خزنة السودان",
                sourceCurrency = "EGP",
                destinationCurrency = "SDG",
                sourceAmount = 1000.0,
                rate = 12.5,
                destinationAmount = 12500.0,
                description = "تحويل تمويل بين مصر والسودان",
                createdBy = "المدير",
                createdAt = System.currentTimeMillis()
            )
        )

        val accounts = accDao.getAllAccountsDirect()
        val entries = accDao.getAllJournalEntriesDirect()
        val lines = accDao.getAllJournalLinesDirect()

        // Verify per-currency isolation & balance equation on both EGP and SDG
        val egpTb = AccountingLedgerEngine.computeTrialBalance(accounts, entries, lines, "EGP")
        val egpPnl = AccountingLedgerEngine.computeIncomeStatement(egpTb)
        val egpBs = AccountingLedgerEngine.computeBalanceSheet(egpTb, egpPnl.netIncome)
        assertTrue(egpTb.isBalanced)
        assertTrue(egpBs.isBalanced)
        assertEquals(500.0, egpPnl.totalOperatingExpenses, 0.01) // Waste 500 EGP

        val sdgTb = AccountingLedgerEngine.computeTrialBalance(accounts, entries, lines, "SDG")
        val sdgPnl = AccountingLedgerEngine.computeIncomeStatement(sdgTb)
        val sdgBs = AccountingLedgerEngine.computeBalanceSheet(sdgTb, sdgPnl.netIncome)
        assertTrue(sdgTb.isBalanced)
        assertTrue(sdgBs.isBalanced)
        assertEquals(200000.0, sdgPnl.totalRevenue, 0.01)
        assertEquals(80000.0, sdgTb.rows.first { it.accountCode == "1130" }.signedBalance, 0.01) // Remaining AR 200k - 120k

        // Verify consolidated FX summary converts EGP + SDG cleanly
        val consolidated = AccountingLedgerEngine.computeConsolidatedFxSummary(
            accounts,
            entries,
            lines,
            CurrencyFxRates(egpToSdg = 12.5, usdToSdg = 600.0)
        )
        assertEquals(200000.0, consolidated.totalRevenueConsolidated, 0.01)
        assertFalse(consolidated.totalAssetsConsolidated <= 0.0)
    }
}
