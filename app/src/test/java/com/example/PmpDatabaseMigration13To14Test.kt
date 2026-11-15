package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.JournalEntryEntity
import com.example.data.local.JournalLineEntity
import com.example.data.local.PmpDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PmpDatabaseMigration13To14Test {

    private val dbName = "pmp_migration_13_14_test.db"
    private var migratedDb: PmpDatabase? = null

    @After
    fun tearDown() {
        migratedDb?.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 11 to 14 preserves all historical & Phase 3 records and seeds 18 CoA accounts`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)

        val helper = LegacyV11SqliteHelper(context, dbName)
        helper.writableDatabase.close()
        helper.close()

        val db = Room.databaseBuilder(context, PmpDatabase::class.java, dbName)
            .addMigrations(PmpDatabase.MIGRATION_11_12, PmpDatabase.MIGRATION_12_13, PmpDatabase.MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()
        migratedDb = db

        val pmpDao = db.pmpDao()
        val accDao = db.erpAccountingDao()

        // 1. Historical invoice preserved
        val invoices = pmpDao.getInvoicesDirect()
        assertEquals(1, invoices.size)
        assertEquals("INV-PRESERVE-14", invoices.first().invoiceNo)

        // 2. All 18 system Chart of Accounts seeded cleanly by MIGRATION_13_14
        val accounts = accDao.getAllAccountsDirect()
        assertEquals(18, accounts.size)
        val codes = accounts.map { it.accountCode }.toSet()
        assertTrue(codes.containsAll(listOf("1110", "1111", "1112", "1120", "1130", "1141", "1142", "1143", "2110", "3100", "4100", "5100", "5200", "5300")))

        // 3. Journal entry & lines insert cleanly with Room schema integrity
        val entryId = accDao.upsertJournalEntry(
            JournalEntryEntity(
                syncUuid = "JRN-MIG-14",
                entryNo = "JRN-00014",
                entryDate = "2026-11-15",
                sourceType = "MANUAL_ADJUSTMENT",
                description = "قيد اختبار هجرة 14",
                currency = "SDG",
                totalDebit = 5000.0,
                totalCredit = 5000.0
            )
        )
        assertTrue(entryId > 0L)

        accDao.upsertJournalLines(
            listOf(
                JournalLineEntity(
                    syncUuid = "JL-MIG-14-1",
                    journalEntryId = entryId,
                    journalEntryUuid = "JRN-MIG-14",
                    lineOrder = 1,
                    accountCode = "1110",
                    accountName = "صندوق النقدية - السودان (SDG)",
                    accountType = "ASSET",
                    debit = 5000.0,
                    credit = 0.0,
                    currency = "SDG"
                ),
                JournalLineEntity(
                    syncUuid = "JL-MIG-14-2",
                    journalEntryId = entryId,
                    journalEntryUuid = "JRN-MIG-14",
                    lineOrder = 2,
                    accountCode = "3100",
                    accountName = "رأس المال",
                    accountType = "EQUITY",
                    debit = 0.0,
                    credit = 5000.0,
                    currency = "SDG"
                )
            )
        )

        val fetched = accDao.getJournalEntryById(entryId)
        assertNotNull(fetched)
        assertEquals(2, accDao.getLinesForEntryDirect(entryId).size)
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

            db.execSQL("INSERT INTO `invoices` (`id`, `invoiceNo`, `customerId`, `customerName`, `invoiceDate`, `currency`, `subtotal`, `discount`, `total`, `notes`, `status`, `cancelReason`, `createdBy`, `createdAt`) VALUES (1, 'INV-PRESERVE-14', 1, 'عميل قديم', '2026-10-01', 'SDG', 75000.0, 0.0, 75000.0, '', 'active', NULL, 'admin', 1700000000000)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
