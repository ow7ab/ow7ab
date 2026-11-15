package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PmpDatabase
import com.example.data.local.WarehouseClass
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PmpDatabaseMigration11To12Test {

    private val dbName = "pmp_migration_11_12_test.db"
    private var migratedDb: PmpDatabase? = null

    @After
    fun tearDown() {
        migratedDb?.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 11 to 12 preserves all existing data and builds Three-Warehouse schema safely`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)

        // 1. Seed a genuine SQLite v11 database with all 21 historical tables & data
        val helper = LegacyV11SqliteHelper(context, dbName)
        val rawDb = helper.writableDatabase
        rawDb.close()
        helper.close()

        // 2. Open with Room + MIGRATION_11_12 (Room automatically runs full schema validation on all 27 tables)
        val db = Room.databaseBuilder(context, PmpDatabase::class.java, dbName)
            .addMigrations(PmpDatabase.MIGRATION_11_12)
            .allowMainThreadQueries()
            .build()
        migratedDb = db

        val dao = db.pmpDao()
        val invDao = db.erpInventoryDao()

        // 3. Verify 100% preservation of existing records
        val users = dao.getUserAccountsDirect()
        assertEquals(1, users.size)
        assertEquals("legacy_admin", users.first().username)

        val cashboxes = dao.getCashboxesDirect()
        assertTrue(cashboxes.any { it.name == "خزنة السودان" })

        val movements = dao.getMovementsDirect()
        assertEquals(1, movements.size)
        assertEquals(250000.0, movements.first().amount, 0.001)

        val customers = dao.getCustomersDirect()
        assertEquals(1, customers.size)
        assertEquals("عميل تاريخي مميز", customers.first().name)

        val invoices = dao.getInvoicesDirect()
        assertEquals(1, invoices.size)
        assertEquals("INV-HIST-100", invoices.first().invoiceNo)

        val products = dao.getProductsDirect()
        assertEquals(1, products.size)
        assertEquals("PROD-UUID-1", products.first().syncUuid)

        // 4. Verify Three-Warehouse classification normalization
        val warehouses = dao.getWarehousesDirect()
        val rawWh = warehouses.firstOrNull { it.name.contains("المواد الخام") }
        val fgWh = warehouses.firstOrNull { it.name.contains("الإنتاج التام") }
        val salesWh = warehouses.firstOrNull { it.name.contains("مبيعات حلفا") }
        assertNotNull(rawWh)
        assertNotNull(fgWh)
        assertNotNull(salesWh)
        assertEquals(WarehouseClass.RAW_MATERIALS.name, rawWh!!.warehouseClass)
        assertFalse(rawWh.allowDirectSales)
        assertEquals(WarehouseClass.FINISHED_GOODS.name, fgWh!!.warehouseClass)
        assertTrue(fgWh.allowProductionReceipt)
        assertFalse(fgWh.allowDirectSales)
        assertEquals(WarehouseClass.SALES.name, salesWh!!.warehouseClass)
        assertTrue(salesWh.allowDirectSales)

        // 5. Verify Packaging Rules seed (250=48, 900=12, 4000=4)
        val rules = invDao.getPackagingRulesDirect().associateBy { it.weightGrams }
        assertEquals(48, rules[250]?.cansPerCarton)
        assertEquals(12, rules[900]?.cansPerCarton)
        assertEquals(4, rules[4000]?.cansPerCarton)

        // 6. Verify Historical StockMoves & FactoryMoves projection into append-only ledger & atomic balances
        val invMovements = invDao.getInventoryMovementsDirect()
        assertTrue(invMovements.any { it.syncUuid == "HIST-SM-1" && it.signedQuantity == 50.0 })
        assertTrue(invMovements.any { it.syncUuid == "HIST-SM-2" && it.signedQuantity == -15.0 })
        assertTrue(invMovements.any { it.syncUuid == "HIST-FM-1" && it.warehouseClass == WarehouseClass.RAW_MATERIALS.name && it.signedQuantity == 400.0 })

        val salesProductBal = invDao.getStockBalanceRow(salesWh.id, "FINISHED_GOOD", 1L, "")
        assertNotNull(salesProductBal)
        assertEquals(35.0, salesProductBal!!.onHandQty, 0.001)
        assertEquals(35.0, salesProductBal.availableQty, 0.001)

        val rawMatBal = invDao.getStockBalanceRow(rawWh.id, "RAW_MATERIAL", 1L, "BATCH-RAW-01")
        assertNotNull(rawMatBal)
        assertEquals(400.0, rawMatBal!!.onHandQty, 0.001)
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

            // Seed legacy v11 sample rows
            db.execSQL("INSERT INTO `user_accounts` (`id`, `userCode`, `fullName`, `username`, `phoneOrEmail`, `pinOrPassword`, `role`, `branch`, `permissions`, `serverSyncToken`, `remoteServerId`, `syncStatus`, `serverEndpoint`, `isCurrentSession`, `active`, `createdAt`) VALUES (1, 'USR-1', 'مدير النظام التاريخي', 'legacy_admin', 'admin@pmp.com', '1234', 'مدير النظام', 'HQ', 'ALL', 'TOK-1', '', 'APPROVED_BY_MANAGER', '', 1, 1, 1700000000000)")
            db.execSQL("INSERT INTO `cashboxes` (`id`, `name`) VALUES (1, 'خزنة السودان')")
            db.execSQL("INSERT INTO `movements` (`id`, `txDate`, `cashbox`, `currency`, `direction`, `amount`, `description`, `reference`, `createdBy`, `createdAt`) VALUES (1, '2026-10-01', 'خزنة السودان', 'SDG', 'وارد', 250000.0, 'رصيد قديم', 'HIST-OP-1', 'legacy_admin', 1700000000000)")
            db.execSQL("INSERT INTO `customers` (`id`, `name`, `phone`, `address`, `notes`, `active`, `createdAt`) VALUES (1, 'عميل تاريخي مميز', '0911111111', 'الخرطوم', '', 1, 1700000000000)")
            db.execSQL("INSERT INTO `invoices` (`id`, `invoiceNo`, `customerId`, `customerName`, `invoiceDate`, `currency`, `subtotal`, `discount`, `total`, `notes`, `status`, `cancelReason`, `createdBy`, `createdAt`) VALUES (1, 'INV-HIST-100', 1, 'عميل تاريخي مميز', '2026-10-01', 'SDG', 100000.0, 0.0, 100000.0, '', 'active', NULL, 'legacy_admin', 1700000000000)")
            db.execSQL("INSERT INTO `warehouses` (`id`, `name`, `active`, `currency`, `linkedCashbox`, `warehouseType`) VALUES (1, 'مخزن مبيعات حلفا', 1, 'SDG', 'خزنة حلفا', 'مبيعات')")
            db.execSQL("INSERT INTO `warehouses` (`id`, `name`, `active`, `currency`, `linkedCashbox`, `warehouseType`) VALUES (2, 'مخزن المواد الخام داخل المصنع (مصر)', 1, 'EGP', 'خزنة مصر', 'مواد خام')")
            db.execSQL("INSERT INTO `warehouses` (`id`, `name`, `active`, `currency`, `linkedCashbox`, `warehouseType`) VALUES (3, 'مخزن الإنتاج التام داخل المصنع', 1, 'EGP', 'خزنة مصر', 'داخل المصنع/إنتاج')")
            db.execSQL("INSERT INTO `products` (`id`, `sku`, `name`, `category`, `unit`, `purchasePrice`, `salePrice`, `salePriceEgp`, `minimumStock`, `packageWeight`, `paintColor`, `active`) VALUES (1, 'PMP-101', 'بوهية أبيض 900 جم', 'بوهيات', 'كرتونة', 1200.0, 385000.0, 1925.0, 10.0, '900', 'أبيض', 1)")
            db.execSQL("INSERT INTO `stock_moves` (`id`, `warehouseId`, `productId`, `quantity`, `kind`, `invoiceId`, `note`, `createdBy`, `createdAt`) VALUES (1, 1, 1, 50.0, 'IN', NULL, 'توريد قديم', 'legacy_admin', 1700000000000)")
            db.execSQL("INSERT INTO `stock_moves` (`id`, `warehouseId`, `productId`, `quantity`, `kind`, `invoiceId`, `note`, `createdBy`, `createdAt`) VALUES (2, 1, 1, 15.0, 'SALE', 1, 'بيع تاريخي', 'legacy_admin', 1700000001000)")
            db.execSQL("INSERT INTO `factory_items` (`id`, `name`, `unit`, `category`) VALUES (1, 'تيتانيوم أبيض', 'كجم', 'raw')")
            db.execSQL("INSERT INTO `factory_moves` (`id`, `moveDate`, `kind`, `itemId`, `itemName`, `qty`, `machineId`, `machineName`, `batch`, `note`, `actor`, `counterparty`, `linked`, `createdAt`) VALUES (1, '2026-10-02', 'RAW_RECEIPT', 1, 'تيتانيوم أبيض', 400.0, NULL, NULL, 'BATCH-RAW-01', 'شراء خام', 'legacy_admin', 'مورد مصر', 1, 1700000002000)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
