package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.repository.PmpRepository
import com.example.domain.inventory.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProductionAndProcurementPhase3Test {

    private lateinit var db: PmpDatabase
    private lateinit var dao: PmpDao
    private lateinit var invDao: ErpInventoryDao
    private lateinit var repository: PmpRepository
    private lateinit var movementEngine: InventoryMovementEngine
    private lateinit var phase3Engine: ProductionAndProcurementEngine

    private var rawWhId: Long = 0L
    private var fgWhId: Long = 0L
    private var salesWhId: Long = 0L
    private var rawMatId: Long = 0L
    private var formulaId: Long = 0L
    private var productId: Long = 0L

    private val managerUser = UserAccountEntity(
        id = 1L,
        userCode = "USR-MGR-1",
        fullName = "مدير المصنع",
        username = "mgr1",
        role = "مدير فرع ومصنع مصر",
        branch = "EG-001",
        permissions = "ALL",
        serverSyncToken = "T-1",
        active = true
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PmpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.pmpDao()
        invDao = db.erpInventoryDao()
        repository = PmpRepository(dao, db)
        movementEngine = repository.inventoryEngine!!
        phase3Engine = repository.phase3Engine!!

        rawWhId = dao.insertWarehouse(
            WarehouseEntity(
                name = "مخزن المواد الخام داخل المصنع (مصر)",
                currency = "EGP",
                linkedCashbox = "خزنة مصر",
                warehouseType = "مواد خام",
                warehouseClass = WarehouseClass.RAW_MATERIALS.name,
                code = "WH-RAW-EG",
                allowDirectSales = false,
                allowProductionReceipt = false
            )
        )
        fgWhId = dao.insertWarehouse(
            WarehouseEntity(
                name = "مخزن الإنتاج التام داخل المصنع",
                currency = "EGP",
                linkedCashbox = "خزنة مصر",
                warehouseType = "داخل المصنع/إنتاج",
                warehouseClass = WarehouseClass.FINISHED_GOODS.name,
                code = "WH-FG-EG",
                allowDirectSales = false,
                allowProductionReceipt = true
            )
        )
        salesWhId = dao.insertWarehouse(
            WarehouseEntity(
                name = "مخزن مبيعات الخرطوم",
                currency = "SDG",
                linkedCashbox = "خزنة السودان",
                warehouseType = "مبيعات",
                warehouseClass = WarehouseClass.SALES.name,
                code = "WH-SLS-KH",
                allowDirectSales = true,
                allowProductionReceipt = false
            )
        )

        rawMatId = dao.insertFactoryItem(
            FactoryItemEntity(
                name = "تيتانيوم أبيض R902",
                unit = "كجم",
                category = "خامات إنتاج"
            )
        )
        productId = dao.insertProduct(
            ProductEntity(
                sku = "PMP-900-WHT",
                name = "بوهية أبيض 900 جم",
                category = "بوهيات",
                unit = "كرتونة",
                purchasePrice = 1100.0,
                salePrice = 420000.0,
                salePriceEgp = 1950.0,
                packageWeight = "900",
                paintColor = "أبيض"
            )
        )
        formulaId = dao.insertFormulation(
            FormulationEntity(
                formulaNo = "FRM-01",
                productName = "بوهية أبيض 900 جم",
                paintType = "بوهيات داخلية",
                baseKind = "أبيض",
                targetPvc = 42.0,
                calculatedPvc = 41.8,
                cpvcEstimated = 55.0,
                totalSolidsWeight = 700.0,
                totalSolidsVolume = 400.0,
                totalBatchWeight = 1000.0,
                totalBatchVolume = 800.0,
                calculatedDensity = 1.25,
                totalCost = 50000.0,
                costPerKg = 50.0,
                ingredientsJson = """[{"name":"تيتانيوم أبيض R902","type":"صبغة","weight":250.0,"unitCost":120.0}]""",
                approvedForProduction = true
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `grn inspection splits accepted raw material to available and rejected to quarantine`() = runBlocking {
        // Missing discrepancy reason when rejected > 0 must fail
        val missingReason = phase3Engine.receivePurchaseToRawMaterials(
            PurchaseReceiptInput(
                supplierName = "المتحدة للكيماويات",
                warehouseId = rawWhId,
                materialId = rawMatId,
                materialName = "تيتانيوم أبيض R902",
                orderedQty = 100.0,
                receivedQty = 100.0,
                acceptedQty = 85.0,
                rejectedQty = 15.0,
                unitCost = 120.0,
                discrepancyReason = ""
            )
        )
        assertTrue(missingReason.isFailure)

        // Valid GRN splits 85 available + 15 quarantined
        val receipt = phase3Engine.receivePurchaseToRawMaterials(
            PurchaseReceiptInput(
                supplierName = "المتحدة للكيماويات",
                warehouseId = rawWhId,
                materialId = rawMatId,
                materialName = "تيتانيوم أبيض R902",
                batchNumber = "BAT-R-01",
                expiryDate = "2028-01-01",
                orderedQty = 100.0,
                receivedQty = 100.0,
                acceptedQty = 85.0,
                rejectedQty = 15.0,
                unitCost = 120.0,
                discrepancyReason = "رطوبة في 15 كجم"
            )
        ).getOrThrow()

        assertEquals("PARTIAL", receipt.qcStatus)
        val accBal = invDao.getStockBalanceRow(rawWhId, InventoryItemCategory.RAW_MATERIAL.name, rawMatId, "BAT-R-01")!!
        assertEquals(85.0, accBal.availableQty, 0.001)
        val rejBal = invDao.getStockBalanceRow(rawWhId, InventoryItemCategory.RAW_MATERIAL.name, rawMatId, "BAT-R-01-REJ")!!
        assertEquals(15.0, rejBal.quarantineQty, 0.001)
        assertEquals(0.0, rejBal.availableQty, 0.001)
    }

    @Test
    fun `production lifecycle scales packaging weights issues raw blocks qc fail and posts pass then transfers for sale`() = runBlocking {
        // 1. Receive 500 kg of raw material
        phase3Engine.receivePurchaseToRawMaterials(
            PurchaseReceiptInput(
                supplierName = "مورد مصر",
                warehouseId = rawWhId,
                materialId = rawMatId,
                materialName = "تيتانيوم أبيض R902",
                batchNumber = "BAT-900",
                orderedQty = 500.0,
                receivedQty = 500.0,
                acceptedQty = 500.0,
                rejectedQty = 0.0,
                unitCost = 100.0
            )
        ).getOrThrow()

        // 2. Create Production Order for 10 cartons @ 900g (12 cans/carton = 120 cans = 108.0 kg)
        val order = phase3Engine.createProductionOrder(
            ProductionOrderCreateInput(
                formulaId = formulaId,
                productId = productId,
                productName = "بوهية أبيض 900 جم",
                paintColor = "أبيض",
                packageWeightGrams = 900,
                targetCartons = 10,
                rawWarehouseId = rawWhId,
                fgWarehouseId = fgWhId
            )
        ).getOrThrow()

        assertEquals(12, order.cansPerCarton)
        assertEquals(120, order.targetCans)
        assertEquals(108.0, order.targetWeightKg, 0.01)

        // Check 250g -> 48 & 4000g -> 4 calculations too
        val ord250 = phase3Engine.createProductionOrder(
            ProductionOrderCreateInput(
                productName = "بوهية 250",
                packageWeightGrams = 250,
                targetCartons = 2,
                rawWarehouseId = rawWhId,
                fgWarehouseId = fgWhId
            )
        ).getOrThrow()
        assertEquals(96, ord250.targetCans)

        val ord4000 = phase3Engine.createProductionOrder(
            ProductionOrderCreateInput(
                productName = "جالون 4000",
                packageWeightGrams = 4000,
                targetCartons = 5,
                rawWarehouseId = rawWhId,
                fgWarehouseId = fgWhId
            )
        ).getOrThrow()
        assertEquals(20, ord4000.targetCans)

        // 3. Issue raw materials exceeding available (600 > 500) must fail
        val overIssue = phase3Engine.issueRawMaterialsForOrder(
            orderId = order.id,
            materials = listOf(
                MaterialIssueLineInput(
                    itemId = rawMatId,
                    itemName = "تيتانيوم أبيض R902",
                    qty = 600.0,
                    batchNumber = "BAT-900"
                )
            )
        )
        assertTrue(overIssue.isFailure)

        // Issue 100 kg, return 10 kg surplus -> net 90 kg issued, 410 kg remains in RAW_MATERIALS
        val issuedOrder = phase3Engine.issueRawMaterialsForOrder(
            orderId = order.id,
            materials = listOf(
                MaterialIssueLineInput(
                    itemId = rawMatId,
                    itemName = "تيتانيوم أبيض R902",
                    qty = 100.0,
                    returnedQty = 10.0,
                    wastedQty = 2.0,
                    batchNumber = "BAT-900"
                )
            )
        ).getOrThrow()
        assertEquals("MATERIALS_ISSUED", issuedOrder.status)
        val rawBal = invDao.getStockBalanceRow(rawWhId, InventoryItemCategory.RAW_MATERIAL.name, rawMatId, "BAT-900")!!
        assertEquals(410.0, rawBal.availableQty, 0.001)

        // 4. QC Failure test on 250g order: density out of bounds (1.50) blocks FINISHED_GOODS available stock
        val (failedQc, failedOrder) = phase3Engine.inspectQcAndPostToFinishedGoods(
            QcProductionInspectionInput(
                productionOrderId = ord250.id,
                measuredDensity = 1.65,
                colorMatchPassed = false,
                goodCans = 90,
                rejectedCans = 6,
                forceReject = true,
                notes = "فشل مطابقة اللون"
            )
        ).getOrThrow()
        assertFalse(failedQc.postedToStock)
        assertEquals("QC_REJECTED", failedOrder.status)

        // 5. QC Pass on 900g order: 120 good cans -> 10 full cartons posted to FINISHED_GOODS
        val (passedQc, postedOrder) = phase3Engine.inspectQcAndPostToFinishedGoods(
            QcProductionInspectionInput(
                productionOrderId = order.id,
                measuredDensity = 1.25,
                colorMatchPassed = true,
                goodCans = 120,
                rejectedCans = 0,
                reworkCans = 0
            )
        ).getOrThrow()
        assertTrue(passedQc.postedToStock)
        assertEquals("POSTED_TO_FG", postedOrder.status)
        assertEquals(10, postedOrder.producedFullCartons)
        assertEquals(0, postedOrder.producedLooseCans)

        val fgBal = invDao.getStockBalanceRow(fgWhId, InventoryItemCategory.FINISHED_GOOD.name, productId, order.batchNumber)!!
        assertEquals(10.0, fgBal.availableQty, 0.001)

        // 6. Customer sale directly from FINISHED_GOODS or RAW_MATERIALS MUST fail at repository level
        val directSaleFg = repository.createInvoice(
            customerId = 1L,
            customerName = "عميل مباشر",
            currency = "SDG",
            items = listOf(
                InvoiceItemEntity(
                    invoiceId = 0L,
                    description = "بوهية أبيض 900 جم",
                    quantity = 2.0,
                    unitPrice = 420000.0,
                    lineTotal = 840000.0,
                    productId = productId,
                    warehouseId = fgWhId
                )
            ),
            discount = 0.0,
            notes = "",
            user = "sales_clerk"
        )
        assertTrue(directSaleFg.isFailure)

        // 7. Transfer 6 cartons FINISHED_GOODS -> SALES and sell 2 cartons cleanly from SALES
        val trfDraft = movementEngine.createTransferDraft(
            TransferCreateRequest(
                sourceWarehouseId = fgWhId,
                destinationWarehouseId = salesWhId,
                createdBy = "dispatch_clerk",
                items = listOf(
                    TransferLineInput(
                        itemCategory = InventoryItemCategory.FINISHED_GOOD,
                        itemId = productId,
                        itemName = "بوهية أبيض 900 جم",
                        requestedQty = 6.0,
                        batchNumber = order.batchNumber,
                        qcApprovedRequired = true,
                        qcApproved = true
                    )
                )
            )
        ).getOrThrow()
        movementEngine.submitTransferForApproval(trfDraft.id, "dispatch_clerk").getOrThrow()
        movementEngine.approveTransfer(trfDraft.id, managerUser).getOrThrow()
        movementEngine.dispatchTransfer(trfDraft.id, "dispatch_clerk").getOrThrow()
        movementEngine.receiveTransfer(trfDraft.id, "kh_storekeeper").getOrThrow()

        val salesBalBeforeSale = invDao.getStockBalanceRow(salesWhId, InventoryItemCategory.FINISHED_GOOD.name, productId, order.batchNumber)!!
        assertEquals(6.0, salesBalBeforeSale.availableQty, 0.001)

        val validSale = repository.createInvoice(
            customerId = 1L,
            customerName = "عميل معتمد",
            currency = "SDG",
            items = listOf(
                InvoiceItemEntity(
                    invoiceId = 0L,
                    description = "بوهية أبيض 900 جم",
                    quantity = 2.0,
                    unitPrice = 420000.0,
                    lineTotal = 840000.0,
                    productId = productId,
                    warehouseId = salesWhId
                )
            ),
            discount = 0.0,
            notes = "بيع من مخزن المبيعات",
            user = "sales_clerk"
        )
        assertTrue(validSale.isSuccess)
    }
}
