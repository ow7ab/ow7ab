package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.domain.inventory.InventoryMovementEngine
import com.example.domain.inventory.InventoryMovementRequest
import com.example.domain.inventory.TransferCreateRequest
import com.example.domain.inventory.TransferLineInput
import com.example.domain.inventory.TransferReceiveLineInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InventoryMovementEnginePhase2Test {

    private lateinit var db: PmpDatabase
    private lateinit var dao: PmpDao
    private lateinit var invDao: ErpInventoryDao
    private lateinit var engine: InventoryMovementEngine

    private var rawWhId: Long = 0L
    private var fgWhId: Long = 0L
    private var salesWhId: Long = 0L

    private val superAdmin = UserAccountEntity(
        id = 1L,
        userCode = "USR-PMP-001",
        fullName = "المدير العام",
        username = "admin",
        role = "مدير النظام",
        branch = "HQ",
        permissions = "ALL",
        serverSyncToken = "TOK-1",
        active = true
    )

    private val warehouseClerk = UserAccountEntity(
        id = 2L,
        userCode = "USR-HF-004",
        fullName = "أمين المخزن",
        username = "halfa_logistics",
        role = "أمين مخزن وترحيل",
        branch = "HF-001",
        permissions = "المخازن,التحويلات",
        serverSyncToken = "TOK-2",
        active = true
    )

    private val branchManager = UserAccountEntity(
        id = 3L,
        userCode = "USR-MGR-003",
        fullName = "مدير المخازن",
        username = "wh_manager",
        role = "مدير فرع ومصنع مصر",
        branch = "EG-001",
        permissions = "المخازن,التحويلات,اعتماد المستندات",
        serverSyncToken = "TOK-3",
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
        engine = InventoryMovementEngine(db)

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
                name = "مخزن مبيعات حلفا",
                currency = "SDG",
                linkedCashbox = "خزنة حلفا",
                warehouseType = "مبيعات",
                warehouseClass = WarehouseClass.SALES.name,
                code = "WH-SLS-HF",
                allowDirectSales = true,
                allowProductionReceipt = false
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `three warehouse isolation blocks direct sales from finished goods and raw materials`() = runBlocking {
        // Seed QC-approved finished goods into FINISHED_GOODS
        val receipt = engine.postMovement(
            InventoryMovementRequest(
                movementType = InventoryMovementType.PRODUCTION_RECEIPT,
                warehouseId = fgWhId,
                itemCategory = InventoryItemCategory.FINISHED_GOOD,
                itemId = 10L,
                itemCode = "PMP-10",
                itemName = "بوهية أبيض 900 جم",
                quantity = 40.0,
                unit = "كرتونة",
                unitCost = 1100.0,
                batchNumber = "B-2026-01",
                qcApproved = true
            )
        )
        assertTrue(receipt.isSuccess)

        // Direct sale from FINISHED_GOODS must fail
        val directSaleFromFg = engine.postMovement(
            InventoryMovementRequest(
                movementType = InventoryMovementType.SALES_ISSUE,
                warehouseId = fgWhId,
                itemCategory = InventoryItemCategory.FINISHED_GOOD,
                itemId = 10L,
                itemName = "بوهية أبيض 900 جم",
                quantity = 5.0,
                batchNumber = "B-2026-01"
            )
        )
        assertTrue(directSaleFromFg.isFailure)

        // Direct production receipt into SALES without transfer must fail
        val directProdToSales = engine.postMovement(
            InventoryMovementRequest(
                movementType = InventoryMovementType.PRODUCTION_RECEIPT,
                warehouseId = salesWhId,
                itemCategory = InventoryItemCategory.FINISHED_GOOD,
                itemId = 10L,
                itemName = "بوهية أبيض 900 جم",
                quantity = 10.0,
                qcApproved = true
            )
        )
        assertTrue(directProdToSales.isFailure)

        // Direct sale of raw materials must fail
        val rawPurchase = engine.postMovement(
            InventoryMovementRequest(
                movementType = InventoryMovementType.PURCHASE_RECEIPT,
                warehouseId = rawWhId,
                itemCategory = InventoryItemCategory.RAW_MATERIAL,
                itemId = 20L,
                itemName = "تيتانيوم أبيض",
                quantity = 500.0,
                unit = "كجم",
                unitCost = 120.0
            )
        )
        assertTrue(rawPurchase.isSuccess)

        val saleRaw = engine.postMovement(
            InventoryMovementRequest(
                movementType = InventoryMovementType.SALES_ISSUE,
                warehouseId = rawWhId,
                itemCategory = InventoryItemCategory.RAW_MATERIAL,
                itemId = 20L,
                itemName = "تيتانيوم أبيض",
                quantity = 10.0,
                unit = "كجم"
            )
        )
        assertTrue(saleRaw.isFailure)
    }

    @Test
    fun `negative stock prevention idempotency and quarantine release work atomically`() = runBlocking {
        // Place production output in QC quarantine
        val quarantined = engine.postMovement(
            InventoryMovementRequest(
                movementType = InventoryMovementType.PRODUCTION_RECEIPT,
                warehouseId = fgWhId,
                itemCategory = InventoryItemCategory.FINISHED_GOOD,
                itemId = 11L,
                itemName = "بوهية جلوس 250 جم",
                quantity = 25.0,
                batchNumber = "B-Q-01",
                qcApproved = false,
                quarantineHold = true,
                idempotencyKey = "IDEMP-Q-1"
            )
        )
        assertTrue(quarantined.isSuccess)

        // Idempotent retry must NOT double count
        val dupRetry = engine.postMovement(
            InventoryMovementRequest(
                movementType = InventoryMovementType.PRODUCTION_RECEIPT,
                warehouseId = fgWhId,
                itemCategory = InventoryItemCategory.FINISHED_GOOD,
                itemId = 11L,
                itemName = "بوهية جلوس 250 جم",
                quantity = 25.0,
                batchNumber = "B-Q-01",
                qcApproved = false,
                quarantineHold = true,
                idempotencyKey = "IDEMP-Q-1"
            )
        )
        assertTrue(dupRetry.isSuccess)
        assertEquals(quarantined.getOrThrow().id, dupRetry.getOrThrow().id)

        val balBeforeRelease = invDao.getStockBalanceRow(fgWhId, InventoryItemCategory.FINISHED_GOOD.name, 11L, "B-Q-01")!!
        assertEquals(25.0, balBeforeRelease.onHandQty, 0.001)
        assertEquals(25.0, balBeforeRelease.quarantineQty, 0.001)
        assertEquals(0.0, balBeforeRelease.availableQty, 0.001)

        // Attempt to issue while quarantined -> fails (negative available guard)
        val blockedIssue = engine.postMovement(
            InventoryMovementRequest(
                movementType = InventoryMovementType.DAMAGED_GOODS,
                warehouseId = fgWhId,
                itemCategory = InventoryItemCategory.FINISHED_GOOD,
                itemId = 11L,
                itemName = "بوهية جلوس 250 جم",
                quantity = 10.0,
                batchNumber = "B-Q-01"
            )
        )
        assertTrue(blockedIssue.isFailure)

        // Release 15 units from quarantine
        val released = engine.releaseQuarantineToAvailable(
            warehouseId = fgWhId,
            itemCategory = InventoryItemCategory.FINISHED_GOOD,
            itemId = 11L,
            batchNumber = "B-Q-01",
            releaseQty = 15.0,
            actor = "qc_chemist"
        )
        assertTrue(released.isSuccess)
        assertEquals(15.0, released.getOrThrow().availableQty, 0.001)
        assertEquals(10.0, released.getOrThrow().quarantineQty, 0.001)
    }

    @Test
    fun `append-only ledger forbids delete and reverses movements with compensating entry`() = runBlocking {
        val added = engine.postMovement(
            InventoryMovementRequest(
                movementType = InventoryMovementType.PURCHASE_RECEIPT,
                warehouseId = rawWhId,
                itemCategory = InventoryItemCategory.RAW_MATERIAL,
                itemId = 30L,
                itemName = "راتنج أكريليك",
                quantity = 200.0,
                unit = "كجم",
                unitCost = 90.0
            )
        ).getOrThrow()

        // Direct delete is strictly forbidden
        assertTrue(engine.attemptDeleteMovement(added.id).isFailure)

        // Partial issue consumes 50 kg -> remaining available 150 kg
        val issued = engine.postMovement(
            InventoryMovementRequest(
                movementType = InventoryMovementType.PRODUCTION_ISSUE,
                warehouseId = rawWhId,
                itemCategory = InventoryItemCategory.RAW_MATERIAL,
                itemId = 30L,
                itemName = "راتنج أكريليك",
                quantity = 50.0,
                unit = "كجم"
            )
        ).getOrThrow()

        // Cannot reverse the 200kg purchase while only 150kg remains
        val blockedRev = engine.reverseMovement(added.id, "خطأ إدخال", "admin")
        assertTrue(blockedRev.isFailure)

        // Reverse the 50kg issue -> restores 200kg available and leaves audit trail
        val revIssue = engine.reverseMovement(issued.id, "تصحيح كمية مصروفة", "admin")
        assertTrue(revIssue.isSuccess)
        assertEquals(+50.0, revIssue.getOrThrow().signedQuantity, 0.001)

        val originalAfterRev = invDao.getInventoryMovementById(issued.id)!!
        assertEquals(MovementRecordStatus.REVERSED.name, originalAfterRev.status)

        val bal = invDao.getStockBalanceRow(rawWhId, InventoryItemCategory.RAW_MATERIAL.name, 30L, "")!!
        assertEquals(200.0, bal.availableQty, 0.001)
    }

    @Test
    fun `warehouse transfer lifecycle enforces SoD reserves stock tracks inTransit and quarantines discrepancies`() = runBlocking {
        // 1. Put 60 cartons in FINISHED_GOODS
        engine.postMovement(
            InventoryMovementRequest(
                movementType = InventoryMovementType.PRODUCTION_RECEIPT,
                warehouseId = fgWhId,
                itemCategory = InventoryItemCategory.FINISHED_GOOD,
                itemId = 100L,
                itemCode = "PMP-100",
                itemName = "بوهية رويال مطفي 900 جم",
                quantity = 60.0,
                unit = "كرتونة",
                unitCost = 1000.0,
                batchNumber = "LOT-2026-A",
                qcApproved = true
            )
        ).getOrThrow()

        // 2. Clerk creates Draft transfer of 20 cartons FINISHED_GOODS -> SALES
        val draft = engine.createTransferDraft(
            TransferCreateRequest(
                sourceWarehouseId = fgWhId,
                destinationWarehouseId = salesWhId,
                createdBy = warehouseClerk.username,
                items = listOf(
                    TransferLineInput(
                        itemCategory = InventoryItemCategory.FINISHED_GOOD,
                        itemId = 100L,
                        itemCode = "PMP-100",
                        itemName = "بوهية رويال مطفي 900 جم",
                        requestedQty = 20.0,
                        unitCost = 1000.0,
                        batchNumber = "LOT-2026-A",
                        qcApprovedRequired = true,
                        qcApproved = true
                    )
                )
            )
        ).getOrThrow()
        assertEquals(WarehouseTransferStatus.DRAFT.name, draft.status)

        val pending = engine.submitTransferForApproval(draft.id, warehouseClerk.username).getOrThrow()
        assertEquals(WarehouseTransferStatus.PENDING_APPROVAL.name, pending.status)

        // 3. SoD: clerk cannot approve own transfer
        val selfApprove = engine.approveTransfer(draft.id, warehouseClerk)
        assertTrue(selfApprove.isFailure)

        // 4. Manager approves transfer -> 20 cartons reserved
        val approved = engine.approveTransfer(draft.id, branchManager).getOrThrow()
        assertEquals(WarehouseTransferStatus.APPROVED.name, approved.status)

        val fgBalAfterApprove = invDao.getStockBalanceRow(fgWhId, InventoryItemCategory.FINISHED_GOOD.name, 100L, "LOT-2026-A")!!
        assertEquals(60.0, fgBalAfterApprove.onHandQty, 0.001)
        assertEquals(20.0, fgBalAfterApprove.reservedQty, 0.001)
        assertEquals(40.0, fgBalAfterApprove.availableQty, 0.001)

        // 5. Dispatch transfer -> FG drops to 40 onHand / 0 reserved; Sales shows 20 inTransit
        val dispatched = engine.dispatchTransfer(draft.id, warehouseClerk.username).getOrThrow()
        assertEquals(WarehouseTransferStatus.IN_TRANSIT.name, dispatched.status)

        val fgBalAfterDispatch = invDao.getStockBalanceRow(fgWhId, InventoryItemCategory.FINISHED_GOOD.name, 100L, "LOT-2026-A")!!
        assertEquals(40.0, fgBalAfterDispatch.onHandQty, 0.001)
        assertEquals(0.0, fgBalAfterDispatch.reservedQty, 0.001)

        val salesBalInTransit = invDao.getStockBalanceRow(salesWhId, InventoryItemCategory.FINISHED_GOOD.name, 100L, "LOT-2026-A")!!
        assertEquals(20.0, salesBalInTransit.inTransitQty, 0.001)
        assertEquals(0.0, salesBalInTransit.onHandQty, 0.001)

        // 6. Receive transfer at Sales: 18 accepted, 2 damaged in transit (quarantined)
        val received = engine.receiveTransfer(
            transferId = draft.id,
            receiver = "halfa_receiver",
            receivedLines = listOf(TransferReceiveLineInput(itemId = 100L, batchNumber = "LOT-2026-A", receivedQty = 18.0, rejectedQty = 2.0))
        ).getOrThrow()
        assertEquals(WarehouseTransferStatus.RECEIVED.name, received.status)

        val salesBalReceived = invDao.getStockBalanceRow(salesWhId, InventoryItemCategory.FINISHED_GOOD.name, 100L, "LOT-2026-A")!!
        assertEquals(0.0, salesBalReceived.inTransitQty, 0.001)
        assertEquals(20.0, salesBalReceived.onHandQty, 0.001)
        assertEquals(18.0, salesBalReceived.availableQty, 0.001)
        assertEquals(2.0, salesBalReceived.quarantineQty, 0.001)
    }

    @Test
    fun `packaging rules default to 250=48 900=12 4000=4 and restrict edits to authorized admins`() = runBlocking {
        val rules = engine.getPackagingRules().associateBy { it.weightGrams }
        assertEquals(48, rules[250]?.cansPerCarton)
        assertEquals(12, rules[900]?.cansPerCarton)
        assertEquals(4, rules[4000]?.cansPerCarton)

        val breakdown250 = engine.convertCansToCartons(250, 100)
        assertEquals(2, breakdown250.fullCartons)
        assertEquals(4, breakdown250.looseCans)
        assertEquals(48, engine.convertCartonsToCans(900, 4))
        assertEquals(20, engine.convertCartonsToCans(4000, 5))

        // Regular clerk cannot modify packaging rules
        val unauthorizedUpdate = engine.updatePackagingRule(warehouseClerk, 900, 10)
        assertTrue(unauthorizedUpdate.isFailure)

        // Super admin can modify packaging rule
        val adminUpdate = engine.updatePackagingRule(superAdmin, 900, 16)
        assertTrue(adminUpdate.isSuccess)
        assertEquals(16, invDao.getPackagingRuleByWeight(900)?.cansPerCarton)
    }
}
