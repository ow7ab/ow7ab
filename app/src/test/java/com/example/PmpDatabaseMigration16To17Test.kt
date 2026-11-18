package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DatabaseBackupManager
import com.example.data.local.DeletedRecordsRegistry
import com.example.data.local.LabExcelImportBatchEntity
import com.example.data.local.LabExperimentEntity
import com.example.data.local.PmpDatabase
import com.example.data.local.RawMaterialPriceHistoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PmpDatabaseMigration16To17Test {

    private val dbName = "pmp_migration_16_17_test.db"
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
    fun `migration 11 through 17 upgrades lab schema, backfills v1 snapshots, and roundtrips DatabaseBackupManager v7`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeletedRecordsRegistry.resetForTesting()
        context.deleteDatabase(dbName)
        File(context.filesDir, "pmp_erp_persistent_backup.json").delete()
        File(context.filesDir, "pmp_deleted_erp_records.json").delete()

        val helper = LegacyV11LabHelper(context, dbName)
        helper.writableDatabase.close()
        helper.close()

        val db = Room.databaseBuilder(context, PmpDatabase::class.java, dbName)
            .addMigrations(
                PmpDatabase.MIGRATION_11_12,
                PmpDatabase.MIGRATION_12_13,
                PmpDatabase.MIGRATION_13_14,
                PmpDatabase.MIGRATION_14_15,
                PmpDatabase.MIGRATION_15_16,
                PmpDatabase.MIGRATION_16_17
            )
            .allowMainThreadQueries()
            .build()
        migratedDb = db

        val dao = db.pmpDao()
        val labDao = db.labDao()
        DeletedRecordsRegistry.init(context)
        DeletedRecordsRegistry.syncWithDao(dao)

        // 1. Existing formulations preserved with upgraded defaults & historical cost lock
        val formulations = dao.getFormulationsDirect()
        assertEquals(2, formulations.size)
        val approved = formulations.firstOrNull { it.formulaNo == "FRM-LEGACY-01" }
        assertNotNull(approved)
        assertEquals(1, approved!!.versionNumber)
        assertEquals("APPROVED", approved.approvalStatus)
        assertTrue(approved.approvedForProduction)
        assertEquals(8400.0, approved.historicalTotalCost, 0.01)

        // 2. Backfilled initial v1 rows in lab_formula_versions
        val backfilledVersions = labDao.getVersionsForFormulaDirect(approved.id)
        assertTrue("Expected backfilled v1 snapshot in lab_formula_versions", backfilledVersions.isNotEmpty())
        assertEquals("APPROVED", backfilledVersions.first().status)

        // 3. New Lab DAO tables accept structured records
        val now = System.currentTimeMillis()
        labDao.upsertLabExperiment(
            LabExperimentEntity(
                syncUuid = "EXP-MIG-1",
                experimentNo = "EXP-001",
                formulaId = approved.id,
                formulaNo = approved.formulaNo,
                versionNumber = 1,
                productName = approved.productName,
                experimentDate = "2026-11-18",
                technicianName = "كيميائي أول",
                measuredDensity = 1.38,
                measuredViscosity = "95 KU",
                measuredPh = 8.4,
                notes = "اختبار مطابقة اللزوجة v1",
                status = "PASSED",
                decision = "APPROVED",
                decidedBy = "مدير الجودة",
                decidedAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
        labDao.upsertPriceHistory(
            RawMaterialPriceHistoryEntity(
                syncUuid = "PRC-MIG-1",
                materialId = 1L,
                itemCode = "RM-TIO2",
                materialName = "ثاني أكسيد التيتانيوم",
                supplierName = "شركة الأصباغ المتحدة",
                unitPrice = 4.95,
                previousPrice = 4.50,
                currency = "EGP",
                sourceType = "PURCHASE_GRN",
                sourceDocNo = "GRN-501",
                effectiveDate = "2026-11-18",
                recordedBy = "المشتريات",
                createdAt = now
            )
        )
        labDao.upsertExcelImport(
            LabExcelImportBatchEntity(
                syncUuid = "XLS-MIG-1",
                importNo = "XLS-001",
                fileName = "legacy_batch.xlsx",
                sheetCount = 3,
                formulasDetected = 2,
                formulasImported = 1,
                materialsMatched = 4,
                materialsCreated = 1,
                approvalStatus = "NEEDS_REVIEW",
                checksumSha256 = "abc123sha",
                importedBy = "مهندس المختبر",
                createdAt = now
            )
        )

        // 4. Backup v7 & restore roundtrip preserves all migrated & newly added Lab records
        DatabaseBackupManager.saveLocalSnapshot(context, dao, labDao).getOrThrow()

        val rawBackup = File(context.filesDir, "pmp_erp_persistent_backup.json").readText()
        assertTrue(rawBackup.contains("lab_formula_versions"))
        assertTrue(rawBackup.contains("lab_experiments"))
        assertTrue(rawBackup.contains("raw_material_price_history"))
        assertTrue(rawBackup.contains("lab_excel_imports"))

        db.clearAllTables()
        assertEquals(0, dao.getFormulationsDirect().size)

        val restoredOk = DatabaseBackupManager.restoreIfEmpty(context, dao, labDao)
        assertTrue(restoredOk)
        assertEquals(2, dao.getFormulationsDirect().size)
        assertTrue(labDao.getAllLabExperimentsDirect().any { it.experimentNo == "EXP-001" })
        assertTrue(labDao.getAllRawMaterialPriceHistoryDirect().any { it.sourceDocNo == "GRN-501" })
        assertTrue(labDao.getAllExcelImportsDirect().any { it.importNo == "XLS-001" })
    }

    private class LegacyV11LabHelper(context: Context, name: String) :
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
            db.execSQL("INSERT INTO `factory_items` (`id`, `name`, `unit`, `category`) VALUES (1, 'ثاني أكسيد التيتانيوم', 'كجم', 'raw')")
            db.execSQL("INSERT INTO `lab_formulations` (`id`, `formulaNo`, `productName`, `paintType`, `baseKind`, `targetPvc`, `calculatedPvc`, `cpvcEstimated`, `totalSolidsWeight`, `totalSolidsVolume`, `totalBatchWeight`, `totalBatchVolume`, `calculatedDensity`, `totalCost`, `costPerKg`, `ingredientsJson`, `technicalAnalysis`, `notes`, `approvedForProduction`, `createdBy`, `createdAt`) VALUES (1, 'FRM-LEGACY-01', 'بلاستيك مطفي سوبر', 'مطفي', 'أبيض', 45.0, 44.8, 52.0, 62.0, 40.0, 1000.0, 720.0, 1.38, 8400.0, 8.4, '[{\"name\":\"ثاني أكسيد التيتانيوم\",\"weight\":150,\"unitCost\":4.5}]', 'تحليل سليم', '', 1, 'كيميائي', $now)")
            db.execSQL("INSERT INTO `lab_formulations` (`id`, `formulaNo`, `productName`, `paintType`, `baseKind`, `targetPvc`, `calculatedPvc`, `cpvcEstimated`, `totalSolidsWeight`, `totalSolidsVolume`, `totalBatchWeight`, `totalBatchVolume`, `calculatedDensity`, `totalCost`, `costPerKg`, `ingredientsJson`, `technicalAnalysis`, `notes`, `approvedForProduction`, `createdBy`, `createdAt`) VALUES (2, 'FRM-LEGACY-02', 'أساس أكريليك تجريبي', 'أساس', 'أبيض', 55.0, 54.0, 52.0, 58.0, 38.0, 500.0, 370.0, 1.35, 3100.0, 6.2, '[]', '', '', 0, 'كيميائي', $now)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
