package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DatabaseBackupManager
import com.example.data.local.DeletedRecordsRegistry
import com.example.data.local.MovementEntity
import com.example.data.local.PmpDatabase
import com.google.firebase.Timestamp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PmpDatabaseMigration15To16Test {

    private val dbName = "pmp_migration_15_16_test.db"
    private var migratedDb: PmpDatabase? = null

    @After
    fun tearDown() {
        migratedDb?.close()
        DeletedRecordsRegistry.resetForTesting()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)
        File(context.filesDir, "pmp_erp_persistent_backup.json").delete()
        File(context.filesDir, "pmp_deleted_erp_records.json").delete()
    }

    @Test
    fun `migration 15 to 16 hardens movements, creates record_tombstones, and prevents resurrection across SQLite & JSON backup`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeletedRecordsRegistry.resetForTesting()
        context.deleteDatabase(dbName)
        File(context.filesDir, "pmp_erp_persistent_backup.json").delete()
        File(context.filesDir, "pmp_deleted_erp_records.json").delete()

        val helper = LegacyV11SqliteHelper(context, dbName)
        helper.writableDatabase.close()
        helper.close()

        val db = Room.databaseBuilder(context, PmpDatabase::class.java, dbName)
            .addMigrations(
                PmpDatabase.MIGRATION_11_12,
                PmpDatabase.MIGRATION_12_13,
                PmpDatabase.MIGRATION_13_14,
                PmpDatabase.MIGRATION_14_15,
                PmpDatabase.MIGRATION_15_16,
                PmpDatabase.MIGRATION_16_17,
                PmpDatabase.MIGRATION_17_18,
                PmpDatabase.MIGRATION_18_19
            )
            .allowMainThreadQueries()
            .build()
        migratedDb = db

        val dao = db.pmpDao()
        DeletedRecordsRegistry.init(context)
        DeletedRecordsRegistry.syncWithDao(dao)

        // 1. Both existing active movement (id=1) & about-to-be-tombstoned movement (id=2) survived migration
        val initialMovements = dao.getMovementsDirect()
        assertEquals(2, initialMovements.size)

        // 2. Merge remote Firestore-style tombstone containing Timestamp, aliases, reference & syncUuid for movement #2
        val now = System.currentTimeMillis()
        val delAt = Timestamp(Date(now))
        DeletedRecordsRegistry.mergeRemoteTombstones(
            remoteList = listOf(
                mapOf(
                    "collection" to "movements",
                    "entityId" to "MOV-IDEM-999",
                    "docId" to "MOV-IDEM-999",
                    "numericId" to 2L,
                    "reference" to "REC-DEL-002",
                    "syncUuid" to "UUID-MOV-DEL-2",
                    "aliases" to listOf("2", "MOV-2", "MOV-IDEM-999", "REC-DEL-002", "UUID-MOV-DEL-2"),
                    "deletedAt" to delAt,
                    "deletedBy" to "admin"
                )
            ),
            remoteEpoch = now - 86_400_000L
        )
        DeletedRecordsRegistry.syncWithDao(dao)

        assertTrue(DeletedRecordsRegistry.isDeleted("movements", 2L))
        assertTrue(DeletedRecordsRegistry.isDeleted("movements", "MOV-2"))
        assertTrue(DeletedRecordsRegistry.isDeleted("movements", "MOV-IDEM-999"))
        assertTrue(DeletedRecordsRegistry.isDeleted("movements", "REC-DEL-002"))
        assertTrue(DeletedRecordsRegistry.isDeleted("movements", "UUID-MOV-DEL-2"))

        // 3. Purge tombstoned & pre-epoch records locally: movement #2 and pre-epoch movement #3 removed, active #1 kept
        val preEpochMov = MovementEntity(
            id = 3L,
            txDate = "2023-01-01",
            cashbox = "خزنة السودان",
            currency = "SDG",
            direction = "منصرف",
            amount = 9999.0,
            description = "حركة قديمة جداً قبل التصفير",
            reference = "REC-OLD-003",
            createdBy = "legacy",
            createdAt = now - (10L * 86_400_000L)
        )
        dao.insertMovement(preEpochMov)
        assertEquals(3, dao.getMovementsDirect().size)

        val purged = PmpDatabase.purgeTombstonedRecordsLocally(dao)
        assertTrue("Expected at least 2 purged movements (tombstoned + pre-epoch), got $purged", purged >= 2)

        val surviving = dao.getMovementsDirect()
        assertEquals(1, surviving.size)
        assertEquals(1L, surviving.first().id)

        // 4. Snapshot backup & restore strictly excludes any tombstoned or pre-epoch records even if injected into snapshot JSON
        DatabaseBackupManager.saveLocalSnapshot(context, dao).getOrThrow()
        dao.clearAllMovements()
        assertEquals(0, dao.getMovementsDirect().size)

        // Inject a stale resurrected record into the backup JSON before restoreIfEmpty to simulate an old device snapshot
        val backupFile = File(context.filesDir, "pmp_erp_persistent_backup.json")
        val jsonText = backupFile.readText()
        val root = org.json.JSONObject(jsonText)
        val movArr = root.getJSONArray("movements")
        movArr.put(org.json.JSONObject().apply {
            put("id", 2L)
            put("txDate", "2026-10-01")
            put("cashbox", "خزنة السودان")
            put("currency", "SDG")
            put("direction", "وارد")
            put("amount", 50000.0)
            put("description", "حركة محذوفة متسللة من نسخة قديمة")
            put("reference", "REC-DEL-002")
            put("cloudDocId", "MOV-IDEM-999")
            put("syncUuid", "UUID-MOV-DEL-2")
            put("createdBy", "staff")
            put("createdAt", 1750000500000L)
        })
        backupFile.writeText(root.toString())

        val restoredOk = DatabaseBackupManager.restoreIfEmpty(context, dao)
        assertTrue(restoredOk)
        val restoredMovements = dao.getMovementsDirect()
        assertEquals("Only surviving non-tombstoned movement #1 must restore", 1, restoredMovements.size)
        assertEquals(1L, restoredMovements.first().id)
        assertFalse(restoredMovements.any { it.reference == "REC-DEL-002" })
    }

    private class LegacyV11SqliteHelper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, 11) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE `cashboxes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `movements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `txDate` TEXT NOT NULL, `cashbox` TEXT NOT NULL, `currency` TEXT NOT NULL, `direction` TEXT NOT NULL, `amount` REAL NOT NULL, `description` TEXT NOT NULL, `reference` TEXT NOT NULL, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `transfers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `reference` TEXT NOT NULL, `txDate` TEXT NOT NULL, `sourceCashbox` TEXT NOT NULL, `destinationCashbox` TEXT NOT NULL, `sourceCurrency` TEXT NOT NULL, `destinationCurrency` TEXT NOT NULL, `sourceAmount` REAL NOT NULL, `rate` REAL NOT NULL, `destinationAmount` REAL NOT NULL, `description` TEXT NOT NULL, `halfaInvoiceId` INTEGER, `halfaAmount` REAL, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `daily_closings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `closeDate` TEXT NOT NULL, `cashbox` TEXT NOT NULL, `currency` TEXT NOT NULL, `bookBalance` REAL NOT NULL, `countedBalance` REAL NOT NULL, `difference` REAL NOT NULL, `notes` TEXT NOT NULL, `preparedBy` TEXT NOT NULL, `status` TEXT NOT NULL, `closedAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `customers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `phone` TEXT NOT NULL, `address` TEXT NOT NULL, `notes` TEXT NOT NULL, `active` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `invoices` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `invoiceNo` TEXT NOT NULL, `customerId` INTEGER NOT NULL, `customerName` TEXT NOT NULL, `invoiceDate` TEXT NOT NULL, `currency` TEXT NOT NULL, `subtotal` REAL NOT NULL, `discount` REAL NOT NULL, `total` REAL NOT NULL, `notes` TEXT NOT NULL, `status` TEXT NOT NULL, `cancelReason` TEXT, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `invoice_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `invoiceId` INTEGER NOT NULL, `description` TEXT NOT NULL, `quantity` REAL NOT NULL, `unitPrice` REAL NOT NULL, `lineTotal` REAL NOT NULL, `productId` INTEGER, `warehouseId` INTEGER)")
            db.execSQL("CREATE TABLE `invoice_payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `invoiceId` INTEGER NOT NULL, `payDate` TEXT NOT NULL, `cashbox` TEXT NOT NULL, `amount` REAL NOT NULL, `note` TEXT NOT NULL, `status` TEXT NOT NULL, `reversalReason` TEXT, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `warehouses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `active` INTEGER NOT NULL, `currency` TEXT NOT NULL, `linkedCashbox` TEXT NOT NULL, `warehouseType` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `products` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sku` TEXT NOT NULL, `name` TEXT NOT NULL, `category` TEXT NOT NULL, `unit` TEXT NOT NULL, `purchasePrice` REAL NOT NULL, `salePrice` REAL NOT NULL, `salePriceEgp` REAL NOT NULL, `minimumStock` REAL NOT NULL, `packageWeight` TEXT NOT NULL, `paintColor` TEXT NOT NULL, `active` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `stock_moves` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `warehouseId` INTEGER NOT NULL, `productId` INTEGER NOT NULL, `quantity` REAL NOT NULL, `kind` TEXT NOT NULL, `invoiceId` INTEGER, `note` TEXT NOT NULL, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `factory_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `unit` TEXT NOT NULL, `category` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `factory_machines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)")
            db.execSQL("CREATE TABLE `factory_moves` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `moveDate` TEXT NOT NULL, `kind` TEXT NOT NULL, `itemId` INTEGER NOT NULL, `itemName` TEXT NOT NULL, `qty` REAL NOT NULL, `machineId` INTEGER, `machineName` TEXT, `batch` TEXT NOT NULL, `note` TEXT NOT NULL, `actor` TEXT NOT NULL, `counterparty` TEXT NOT NULL, `linked` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `halfa_invoices` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `invoiceNo` TEXT NOT NULL, `invoiceDate` TEXT NOT NULL, `cashbox` TEXT NOT NULL, `currency` TEXT NOT NULL, `warehouseId` INTEGER NOT NULL, `productId` INTEGER NOT NULL, `productName` TEXT NOT NULL, `quantity` REAL NOT NULL, `unitPrice` REAL NOT NULL, `total` REAL NOT NULL, `factoryMoveId` INTEGER, `note` TEXT NOT NULL, `status` TEXT NOT NULL, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `poster_prices` (`id` TEXT NOT NULL, `weight` TEXT NOT NULL, `kind` TEXT NOT NULL, `price` INTEGER NOT NULL, `priceEgp` INTEGER NOT NULL, `currency` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE `audit_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `username` TEXT NOT NULL, `action` TEXT NOT NULL, `detail` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `user_accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userCode` TEXT NOT NULL, `fullName` TEXT NOT NULL, `username` TEXT NOT NULL, `phoneOrEmail` TEXT NOT NULL, `pinOrPassword` TEXT NOT NULL, `role` TEXT NOT NULL, `branch` TEXT NOT NULL, `permissions` TEXT NOT NULL, `serverSyncToken` TEXT NOT NULL, `remoteServerId` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, `serverEndpoint` TEXT NOT NULL, `isCurrentSession` INTEGER NOT NULL, `active` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `lab_formulations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `formulaNo` TEXT NOT NULL, `productName` TEXT NOT NULL, `paintType` TEXT NOT NULL, `baseKind` TEXT NOT NULL, `targetPvc` REAL NOT NULL, `calculatedPvc` REAL NOT NULL, `cpvcEstimated` REAL NOT NULL, `totalSolidsWeight` REAL NOT NULL, `totalSolidsVolume` REAL NOT NULL, `totalBatchWeight` REAL NOT NULL, `totalBatchVolume` REAL NOT NULL, `calculatedDensity` REAL NOT NULL, `totalCost` REAL NOT NULL, `costPerKg` REAL NOT NULL, `ingredientsJson` TEXT NOT NULL, `technicalAnalysis` TEXT NOT NULL, `notes` TEXT NOT NULL, `approvedForProduction` INTEGER NOT NULL, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `chemical_safety` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chemicalName` TEXT NOT NULL, `commonName` TEXT NOT NULL, `casNumber` TEXT NOT NULL, `molecularFormula` TEXT NOT NULL, `molecularWeight` REAL NOT NULL, `ghsPictograms` TEXT NOT NULL, `signalWord` TEXT NOT NULL, `hazardStatements` TEXT NOT NULL, `precautionaryStatements` TEXT NOT NULL, `storageConditions` TEXT NOT NULL, `firstAidMeasures` TEXT NOT NULL, `sdsSupplierName` TEXT NOT NULL, `sdsFileUrlOrUri` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `daily_production_batches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `batchNumber` TEXT NOT NULL, `productionDate` TEXT NOT NULL, `formulaId` INTEGER, `formulaNo` TEXT NOT NULL, `productName` TEXT NOT NULL, `paintType` TEXT NOT NULL, `baseKind` TEXT NOT NULL, `targetPvc` REAL NOT NULL, `targetWeightKg` REAL NOT NULL, `actualWeightKg` REAL NOT NULL, `targetVolumeLiters` REAL NOT NULL, `cartonsCount` INTEGER NOT NULL, `packageUnit` TEXT NOT NULL, `machineId` INTEGER, `machineName` TEXT NOT NULL, `qualityStatus` TEXT NOT NULL, `measuredDensity` REAL NOT NULL, `measuredViscosity` TEXT NOT NULL, `scaledIngredientsJson` TEXT NOT NULL, `supervisorOrEngineer` TEXT NOT NULL, `shift` TEXT NOT NULL, `notes` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")

            val now = System.currentTimeMillis()
            db.execSQL("INSERT INTO `movements` (`id`, `txDate`, `cashbox`, `currency`, `direction`, `amount`, `description`, `reference`, `createdBy`, `createdAt`) VALUES (1, '2026-10-10', 'خزنة السودان', 'SDG', 'وارد', 150000.0, 'حركة سارية صحيحة', 'REC-ACT-001', 'admin', $now)")
            db.execSQL("INSERT INTO `movements` (`id`, `txDate`, `cashbox`, `currency`, `direction`, `amount`, `description`, `reference`, `createdBy`, `createdAt`) VALUES (2, '2026-10-10', 'خزنة السودان', 'SDG', 'منصرف', 42000.0, 'حركة سيتم حذفها بشاهد متعدد', 'REC-DEL-002', 'admin', ${now + 1000L})")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
