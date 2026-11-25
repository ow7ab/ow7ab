package com.example.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Database(
    entities = [
        CashboxEntity::class,
        MovementEntity::class,
        TransferEntity::class,
        DailyClosingEntity::class,
        CustomerEntity::class,
        InvoiceEntity::class,
        InvoiceItemEntity::class,
        InvoicePaymentEntity::class,
        WarehouseEntity::class,
        ProductEntity::class,
        StockMoveEntity::class,
        FactoryItemEntity::class,
        FactoryMachineEntity::class,
        FactoryMoveEntity::class,
        HalfaInvoiceEntity::class,
        PosterPriceEntity::class,
        AuditLogEntity::class,
        UserAccountEntity::class,
        FormulationEntity::class,
        ChemicalSafetyEntity::class,
        ProductionBatchEntity::class,
        InventoryMovementEntity::class,
        WarehouseStockBalanceEntity::class,
        WarehouseTransferEntity::class,
        WarehouseTransferItemEntity::class,
        PackagingRuleEntity::class,
        SyncOutboxEntity::class,
        PurchaseReceiptEntity::class,
        ProductionOrderEntity::class,
        QcInspectionEntity::class,
        ChartAccountEntity::class,
        JournalEntryEntity::class,
        JournalLineEntity::class,
        IdempotencyRequestEntity::class,
        RecordTombstoneEntity::class,
        FormulationVersionEntity::class,
        LabExperimentEntity::class,
        RawMaterialPriceHistoryEntity::class,
        LabExcelImportBatchEntity::class
    ],
    version = 18,
    exportSchema = false
)
abstract class PmpDatabase : RoomDatabase() {

    abstract fun pmpDao(): PmpDao
    abstract fun erpInventoryDao(): ErpInventoryDao
    abstract fun erpAccountingDao(): ErpAccountingDao
    abstract fun idempotencyDao(): IdempotencyDao
    abstract fun labDao(): LabDao

    companion object {
        @Volatile
        private var INSTANCE: PmpDatabase? = null

        private fun repairSchemaForRoomValidation(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `user_accounts` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `userCode` TEXT NOT NULL,
                    `fullName` TEXT NOT NULL,
                    `username` TEXT NOT NULL,
                    `phoneOrEmail` TEXT NOT NULL,
                    `pinOrPassword` TEXT NOT NULL,
                    `role` TEXT NOT NULL,
                    `branch` TEXT NOT NULL,
                    `permissions` TEXT NOT NULL,
                    `serverSyncToken` TEXT NOT NULL,
                    `remoteServerId` TEXT NOT NULL,
                    `syncStatus` TEXT NOT NULL,
                    `serverEndpoint` TEXT NOT NULL,
                    `isCurrentSession` INTEGER NOT NULL,
                    `active` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // Ensure columns added in later versions exist on older installations
            val userColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`user_accounts`)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0) userColumns.add(cursor.getString(nameIdx))
                }
            }
            if ("phoneOrEmail" !in userColumns) {
                db.execSQL("ALTER TABLE `user_accounts` ADD COLUMN `phoneOrEmail` TEXT NOT NULL DEFAULT ''")
            }
            if ("pinOrPassword" !in userColumns) {
                db.execSQL("ALTER TABLE `user_accounts` ADD COLUMN `pinOrPassword` TEXT NOT NULL DEFAULT '1234'")
            }

            // Ensure columns exist on warehouses
            val whColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`warehouses`)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0) whColumns.add(cursor.getString(nameIdx))
                }
            }
            if ("currency" !in whColumns) {
                db.execSQL("ALTER TABLE `warehouses` ADD COLUMN `currency` TEXT NOT NULL DEFAULT 'SDG'")
            }
            if ("linkedCashbox" !in whColumns) {
                db.execSQL("ALTER TABLE `warehouses` ADD COLUMN `linkedCashbox` TEXT NOT NULL DEFAULT 'خزنة السودان'")
            }
            if ("warehouseType" !in whColumns) {
                db.execSQL("ALTER TABLE `warehouses` ADD COLUMN `warehouseType` TEXT NOT NULL DEFAULT 'مبيعات'")
            }

            // Ensure columns exist on products
            val prodColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`products`)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0) prodColumns.add(cursor.getString(nameIdx))
                }
            }
            if ("salePriceEgp" !in prodColumns) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `salePriceEgp` REAL NOT NULL DEFAULT 0.0")
            }

            // Ensure columns exist on poster_prices
            val posterColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`poster_prices`)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0) posterColumns.add(cursor.getString(nameIdx))
                }
            }
            if ("priceEgp" !in posterColumns) {
                db.execSQL("ALTER TABLE `poster_prices` ADD COLUMN `priceEgp` INTEGER NOT NULL DEFAULT 0")
            }
            if ("currency" !in posterColumns) {
                db.execSQL("ALTER TABLE `poster_prices` ADD COLUMN `currency` TEXT NOT NULL DEFAULT 'SDG'")
            }
            try {
                db.execSQL("UPDATE `poster_prices` SET `currency` = 'EGP' WHERE `id` LIKE '%_EGP'")
                db.execSQL("UPDATE `poster_prices` SET `price` = `priceEgp` WHERE (`currency` = 'EGP' OR `id` LIKE '%_EGP') AND `priceEgp` > 0 AND `price` = `priceEgp` * 200")
            } catch (_: Exception) {}

            // Deduplicate cashboxes, movements, and user accounts
            try {
                // Keep non-destructive user account/cashbox deduplication ONLY without ever deleting financial movements
                db.execSQL("DELETE FROM `cashboxes` WHERE `id` NOT IN (SELECT MIN(`id`) FROM `cashboxes` GROUP BY TRIM(LOWER(`name`)))")
                db.execSQL("DELETE FROM `user_accounts` WHERE `isCurrentSession` = 0 AND `id` NOT IN (SELECT MIN(`id`) FROM `user_accounts` GROUP BY TRIM(LOWER(`username`)))")

                // Ensure default Cashboxes exist without creating duplicates
                val existingBoxes = mutableSetOf<String>()
                db.query("SELECT TRIM(LOWER(`name`)) FROM `cashboxes`").use { cursor ->
                    while (cursor.moveToNext()) {
                        existingBoxes.add(cursor.getString(0))
                    }
                }
                listOf("خزنة السودان", "خزنة مصر", "خزنة حلفا", "خزنة عطبرة").forEach { boxName ->
                    if (boxName.trim().lowercase() !in existingBoxes &&
                        !DeletedRecordsRegistry.isDeleted("cashboxes", boxName)
                    ) {
                        db.execSQL("INSERT INTO `cashboxes` (`name`) VALUES ('$boxName')")
                        existingBoxes.add(boxName.trim().lowercase())
                    }
                }

                // Only initialize warehouse assignments when blank so user edits are never overwritten on app reopen
                db.execSQL("UPDATE `warehouses` SET `currency` = 'SDG', `linkedCashbox` = 'خزنة حلفا', `warehouseType` = 'مبيعات' WHERE `name` LIKE '%حلفا%' AND (TRIM(COALESCE(`linkedCashbox`, '')) = '' OR TRIM(COALESCE(`currency`, '')) = '')")
                db.execSQL("UPDATE `warehouses` SET `currency` = 'SDG', `linkedCashbox` = 'خزنة عطبرة', `warehouseType` = 'مبيعات' WHERE `name` LIKE '%عطبرة%' AND (TRIM(COALESCE(`linkedCashbox`, '')) = '' OR TRIM(COALESCE(`currency`, '')) = '')")
                db.execSQL("UPDATE `warehouses` SET `currency` = 'SDG', `linkedCashbox` = 'خزنة السودان', `warehouseType` = 'مبيعات' WHERE (`name` LIKE '%الخرطوم%' OR `name` = 'مخزن السودان') AND (TRIM(COALESCE(`linkedCashbox`, '')) = '' OR TRIM(COALESCE(`currency`, '')) = '')")
                db.execSQL("UPDATE `warehouses` SET `currency` = 'EGP', `linkedCashbox` = 'خزنة مصر', `warehouseType` = 'مبيعات' WHERE (`name` LIKE '%مبيعات المصنع%' OR `name` LIKE '%مبيعات مصر%') AND (TRIM(COALESCE(`linkedCashbox`, '')) = '' OR TRIM(COALESCE(`currency`, '')) = '')")
                db.execSQL("UPDATE `warehouses` SET `currency` = 'EGP', `linkedCashbox` = 'خزنة مصر', `warehouseType` = 'مواد خام' WHERE `name` LIKE '%مواد خام%' AND (TRIM(COALESCE(`linkedCashbox`, '')) = '' OR TRIM(COALESCE(`currency`, '')) = '')")
                db.execSQL("UPDATE `warehouses` SET `currency` = 'EGP', `linkedCashbox` = 'خزنة مصر', `warehouseType` = 'داخل المصنع/إنتاج' WHERE `name` LIKE '%الإنتاج التام%' AND (TRIM(COALESCE(`linkedCashbox`, '')) = '' OR TRIM(COALESCE(`currency`, '')) = '')")

                // Insert distinct sales warehouses ONLY if missing (never duplicate existing ones)
                val existingWhs = mutableSetOf<String>()
                db.query("SELECT TRIM(LOWER(`name`)) FROM `warehouses`").use { cursor ->
                    while (cursor.moveToNext()) {
                        existingWhs.add(cursor.getString(0))
                    }
                }
                listOf(
                    Triple("مخزن مبيعات حلفا", "SDG", "خزنة حلفا") to "مبيعات",
                    Triple("مخزن مبيعات عطبرة", "SDG", "خزنة عطبرة") to "مبيعات",
                    Triple("مخزن مبيعات الخرطوم", "SDG", "خزنة السودان") to "مبيعات",
                    Triple("مخزن مبيعات المصنع (مصر)", "EGP", "خزنة مصر") to "مبيعات",
                    Triple("مخزن المواد الخام داخل المصنع (مصر)", "EGP", "خزنة مصر") to "مواد خام",
                    Triple("مخزن الإنتاج التام داخل المصنع", "EGP", "خزنة مصر") to "داخل المصنع/إنتاج"
                ).forEach { (triple, whType) ->
                    val (wName, wCur, wBox) = triple
                    if (wName.trim().lowercase() !in existingWhs &&
                        !DeletedRecordsRegistry.isDeleted("warehouses", wName)
                    ) {
                        db.execSQL("INSERT INTO `warehouses` (`name`, `currency`, `linkedCashbox`, `warehouseType`) VALUES ('$wName', '$wCur', '$wBox', '$whType')")
                        existingWhs.add(wName.trim().lowercase())
                    }
                }
            } catch (_: Exception) {}

            // Non-destructive v18 columns safeguard
            ensureV18ImmutableLedgerColumns(db)

            // Create PMP Science & Chemistry Lab tables if not existing
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lab_formulations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `formulaNo` TEXT NOT NULL,
                    `productName` TEXT NOT NULL,
                    `paintType` TEXT NOT NULL,
                    `baseKind` TEXT NOT NULL,
                    `targetPvc` REAL NOT NULL,
                    `calculatedPvc` REAL NOT NULL,
                    `cpvcEstimated` REAL NOT NULL,
                    `totalSolidsWeight` REAL NOT NULL,
                    `totalSolidsVolume` REAL NOT NULL,
                    `totalBatchWeight` REAL NOT NULL,
                    `totalBatchVolume` REAL NOT NULL,
                    `calculatedDensity` REAL NOT NULL,
                    `totalCost` REAL NOT NULL,
                    `costPerKg` REAL NOT NULL,
                    `ingredientsJson` TEXT NOT NULL,
                    `technicalAnalysis` TEXT NOT NULL,
                    `notes` TEXT NOT NULL,
                    `approvedForProduction` INTEGER NOT NULL,
                    `createdBy` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `chemical_safety` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `chemicalName` TEXT NOT NULL,
                    `commonName` TEXT NOT NULL,
                    `casNumber` TEXT NOT NULL,
                    `molecularFormula` TEXT NOT NULL,
                    `molecularWeight` REAL NOT NULL,
                    `ghsPictograms` TEXT NOT NULL,
                    `signalWord` TEXT NOT NULL,
                    `hazardStatements` TEXT NOT NULL,
                    `precautionaryStatements` TEXT NOT NULL,
                    `storageConditions` TEXT NOT NULL,
                    `firstAidMeasures` TEXT NOT NULL,
                    `sdsSupplierName` TEXT NOT NULL,
                    `sdsFileUrlOrUri` TEXT NOT NULL,
                    `notes` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `daily_production_batches` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `batchNumber` TEXT NOT NULL,
                    `productionDate` TEXT NOT NULL,
                    `formulaId` INTEGER,
                    `formulaNo` TEXT NOT NULL,
                    `productName` TEXT NOT NULL,
                    `paintType` TEXT NOT NULL,
                    `baseKind` TEXT NOT NULL,
                    `targetPvc` REAL NOT NULL,
                    `targetWeightKg` REAL NOT NULL,
                    `actualWeightKg` REAL NOT NULL,
                    `targetVolumeLiters` REAL NOT NULL,
                    `cartonsCount` INTEGER NOT NULL,
                    `packageUnit` TEXT NOT NULL,
                    `machineId` INTEGER,
                    `machineName` TEXT NOT NULL,
                    `qualityStatus` TEXT NOT NULL,
                    `measuredDensity` REAL NOT NULL,
                    `measuredViscosity` TEXT NOT NULL,
                    `scaledIngredientsJson` TEXT NOT NULL,
                    `supervisorOrEngineer` TEXT NOT NULL,
                    `shift` TEXT NOT NULL,
                    `notes` TEXT NOT NULL,
                    `syncStatus` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // Drop any legacy indices not declared in @Entity annotations so Room schema validation never throws IllegalStateException
            db.execSQL("DROP INDEX IF EXISTS `index_user_accounts_username`")
            db.execSQL("DROP INDEX IF EXISTS `index_user_accounts_userCode`")
            db.execSQL("DROP INDEX IF EXISTS `index_invoices_invoiceNo`")
            db.execSQL("DROP INDEX IF EXISTS `index_invoice_items_invoiceId`")
            db.execSQL("DROP INDEX IF EXISTS `index_movements_reference`")
            db.execSQL("DROP INDEX IF EXISTS `index_customers_name`")

            // Ensure V16 movements columns & indices and record_tombstones table match Room schema
            upgradeSchemaToV16TombstonesAndHardening(db)

            // Ensure V17 Chemical Lab versioning, experiments, raw material price history & Excel import batch schema
            upgradeSchemaToV17ChemicalLab(db)
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
            }
        }

        private fun getTableColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val cols = mutableSetOf<String>()
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0) cols.add(cursor.getString(nameIdx))
                }
            }
            return cols
        }

        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
            columnDefSql: String,
            existingColumns: MutableSet<String>
        ) {
            if (columnName !in existingColumns) {
                db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDefSql")
                existingColumns.add(columnName)
            }
        }

        private fun upgradeSchemaToV12AndBackfill(db: SupportSQLiteDatabase) {
            // 1. Extend warehouses table safely with Three-Warehouse classification & sync metadata
            val whCols = getTableColumns(db, "warehouses").toMutableSet()
            addColumnIfMissing(db, "warehouses", "warehouseClass", "TEXT NOT NULL DEFAULT 'SALES'", whCols)
            addColumnIfMissing(db, "warehouses", "code", "TEXT NOT NULL DEFAULT ''", whCols)
            addColumnIfMissing(db, "warehouses", "branch", "TEXT NOT NULL DEFAULT ''", whCols)
            addColumnIfMissing(db, "warehouses", "allowDirectSales", "INTEGER NOT NULL DEFAULT 1", whCols)
            addColumnIfMissing(db, "warehouses", "allowProductionReceipt", "INTEGER NOT NULL DEFAULT 0", whCols)
            addColumnIfMissing(db, "warehouses", "syncUuid", "TEXT NOT NULL DEFAULT ''", whCols)
            addColumnIfMissing(db, "warehouses", "updatedAt", "INTEGER NOT NULL DEFAULT 0", whCols)
            addColumnIfMissing(db, "warehouses", "recordVersion", "INTEGER NOT NULL DEFAULT 1", whCols)
            addColumnIfMissing(db, "warehouses", "deviceId", "TEXT NOT NULL DEFAULT ''", whCols)

            db.execSQL(
                """
                UPDATE `warehouses` SET
                    `warehouseClass` = CASE
                        WHEN `warehouseType` LIKE '%مواد خام%' OR `name` LIKE '%المواد الخام%' OR `name` LIKE '%مواد خام%' THEN 'RAW_MATERIALS'
                        WHEN `warehouseType` LIKE '%إنتاج%' OR `warehouseType` LIKE '%انتاج%' OR `name` LIKE '%الإنتاج التام%' OR `name` LIKE '%الانتاج التام%' THEN 'FINISHED_GOODS'
                        ELSE 'SALES'
                    END,
                    `allowDirectSales` = CASE
                        WHEN `warehouseType` LIKE '%مواد خام%' OR `name` LIKE '%المواد الخام%' OR `warehouseType` LIKE '%إنتاج%' OR `name` LIKE '%الإنتاج التام%' THEN 0
                        ELSE 1
                    END,
                    `allowProductionReceipt` = CASE
                        WHEN `warehouseType` LIKE '%إنتاج%' OR `name` LIKE '%الإنتاج التام%' THEN 1
                        ELSE 0
                    END,
                    `code` = CASE WHEN TRIM(`code`) = '' THEN 'WH-' || `id` ELSE `code` END,
                    `syncUuid` = CASE WHEN TRIM(`syncUuid`) = '' THEN 'WH-UUID-' || `id` ELSE `syncUuid` END
                """.trimIndent()
            )

            // 2. Extend products, stock_moves, factory_items, factory_moves non-destructively
            val prodCols = getTableColumns(db, "products").toMutableSet()
            addColumnIfMissing(db, "products", "syncUuid", "TEXT NOT NULL DEFAULT ''", prodCols)
            addColumnIfMissing(db, "products", "updatedAt", "INTEGER NOT NULL DEFAULT 0", prodCols)
            addColumnIfMissing(db, "products", "recordVersion", "INTEGER NOT NULL DEFAULT 1", prodCols)
            addColumnIfMissing(db, "products", "deviceId", "TEXT NOT NULL DEFAULT ''", prodCols)
            db.execSQL("UPDATE `products` SET `syncUuid` = 'PROD-UUID-' || `id` WHERE TRIM(`syncUuid`) = ''")

            val smCols = getTableColumns(db, "stock_moves").toMutableSet()
            addColumnIfMissing(db, "stock_moves", "batchNumber", "TEXT NOT NULL DEFAULT ''", smCols)
            addColumnIfMissing(db, "stock_moves", "syncUuid", "TEXT NOT NULL DEFAULT ''", smCols)
            addColumnIfMissing(db, "stock_moves", "recordVersion", "INTEGER NOT NULL DEFAULT 1", smCols)
            addColumnIfMissing(db, "stock_moves", "deviceId", "TEXT NOT NULL DEFAULT ''", smCols)
            db.execSQL("UPDATE `stock_moves` SET `syncUuid` = 'SM-UUID-' || `id` WHERE TRIM(`syncUuid`) = ''")

            val fiCols = getTableColumns(db, "factory_items").toMutableSet()
            addColumnIfMissing(db, "factory_items", "itemCode", "TEXT NOT NULL DEFAULT ''", fiCols)
            addColumnIfMissing(db, "factory_items", "unitCostEgp", "REAL NOT NULL DEFAULT 0.0", fiCols)
            addColumnIfMissing(db, "factory_items", "minStock", "REAL NOT NULL DEFAULT 0.0", fiCols)
            addColumnIfMissing(db, "factory_items", "maxStock", "REAL NOT NULL DEFAULT 0.0", fiCols)
            addColumnIfMissing(db, "factory_items", "batchTrackingRequired", "INTEGER NOT NULL DEFAULT 0", fiCols)
            addColumnIfMissing(db, "factory_items", "syncUuid", "TEXT NOT NULL DEFAULT ''", fiCols)
            addColumnIfMissing(db, "factory_items", "updatedAt", "INTEGER NOT NULL DEFAULT 0", fiCols)
            addColumnIfMissing(db, "factory_items", "recordVersion", "INTEGER NOT NULL DEFAULT 1", fiCols)
            addColumnIfMissing(db, "factory_items", "deviceId", "TEXT NOT NULL DEFAULT ''", fiCols)
            db.execSQL(
                """
                UPDATE `factory_items` SET
                    `itemCode` = CASE WHEN TRIM(`itemCode`) = '' THEN 'RAW-' || `id` ELSE `itemCode` END,
                    `syncUuid` = CASE WHEN TRIM(`syncUuid`) = '' THEN 'FI-UUID-' || `id` ELSE `syncUuid` END
                """.trimIndent()
            )

            val fmCols = getTableColumns(db, "factory_moves").toMutableSet()
            addColumnIfMissing(db, "factory_moves", "syncUuid", "TEXT NOT NULL DEFAULT ''", fmCols)
            addColumnIfMissing(db, "factory_moves", "recordVersion", "INTEGER NOT NULL DEFAULT 1", fmCols)
            addColumnIfMissing(db, "factory_moves", "deviceId", "TEXT NOT NULL DEFAULT ''", fmCols)
            db.execSQL("UPDATE `factory_moves` SET `syncUuid` = 'FM-UUID-' || `id` WHERE TRIM(`syncUuid`) = ''")

            // 3. Create new Phase 2 tables & exact Room-declared indices
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `inventory_movements` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `syncUuid` TEXT NOT NULL,
                    `documentNo` TEXT NOT NULL,
                    `movementType` TEXT NOT NULL,
                    `warehouseId` INTEGER NOT NULL,
                    `warehouseCode` TEXT NOT NULL,
                    `warehouseClass` TEXT NOT NULL,
                    `itemCategory` TEXT NOT NULL,
                    `itemId` INTEGER NOT NULL,
                    `itemCode` TEXT NOT NULL,
                    `itemName` TEXT NOT NULL,
                    `quantity` REAL NOT NULL,
                    `signedQuantity` REAL NOT NULL,
                    `unit` TEXT NOT NULL,
                    `unitCost` REAL NOT NULL,
                    `totalCost` REAL NOT NULL,
                    `currency` TEXT NOT NULL,
                    `batchNumber` TEXT NOT NULL,
                    `expiryDate` TEXT NOT NULL,
                    `referenceDocType` TEXT NOT NULL,
                    `referenceDocId` INTEGER,
                    `referenceDocNo` TEXT NOT NULL,
                    `reversedMovementId` INTEGER,
                    `reason` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `qcApproved` INTEGER NOT NULL,
                    `idempotencyKey` TEXT NOT NULL,
                    `performedBy` TEXT NOT NULL,
                    `approvedBy` TEXT NOT NULL,
                    `deviceId` TEXT NOT NULL,
                    `recordVersion` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_inv_mov_sync_uuid` ON `inventory_movements` (`syncUuid`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_inv_mov_lookup` ON `inventory_movements` (`warehouseId`, `itemCategory`, `itemId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `warehouse_stock_balances` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `syncUuid` TEXT NOT NULL,
                    `warehouseId` INTEGER NOT NULL,
                    `warehouseClass` TEXT NOT NULL,
                    `itemCategory` TEXT NOT NULL,
                    `itemId` INTEGER NOT NULL,
                    `itemCode` TEXT NOT NULL,
                    `itemName` TEXT NOT NULL,
                    `unit` TEXT NOT NULL,
                    `batchNumber` TEXT NOT NULL,
                    `expiryDate` TEXT NOT NULL,
                    `onHandQty` REAL NOT NULL,
                    `reservedQty` REAL NOT NULL,
                    `inTransitQty` REAL NOT NULL,
                    `quarantineQty` REAL NOT NULL,
                    `availableQty` REAL NOT NULL,
                    `avgUnitCost` REAL NOT NULL,
                    `currency` TEXT NOT NULL,
                    `lastMovementUuid` TEXT NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_wh_stock_bal_key` ON `warehouse_stock_balances` (`warehouseId`, `itemCategory`, `itemId`, `batchNumber`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `warehouse_transfers` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `syncUuid` TEXT NOT NULL,
                    `transferNo` TEXT NOT NULL,
                    `transferDate` TEXT NOT NULL,
                    `sourceWarehouseId` INTEGER NOT NULL,
                    `sourceWarehouseName` TEXT NOT NULL,
                    `sourceWarehouseClass` TEXT NOT NULL,
                    `destinationWarehouseId` INTEGER NOT NULL,
                    `destinationWarehouseName` TEXT NOT NULL,
                    `destinationWarehouseClass` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `totalCost` REAL NOT NULL,
                    `currency` TEXT NOT NULL,
                    `notes` TEXT NOT NULL,
                    `rejectionReason` TEXT NOT NULL,
                    `createdBy` TEXT NOT NULL,
                    `reviewedBy` TEXT NOT NULL,
                    `approvedBy` TEXT NOT NULL,
                    `dispatchedBy` TEXT NOT NULL,
                    `receivedBy` TEXT NOT NULL,
                    `dispatchedAt` INTEGER NOT NULL,
                    `receivedAt` INTEGER NOT NULL,
                    `timelineJson` TEXT NOT NULL,
                    `idempotencyKey` TEXT NOT NULL,
                    `deviceId` TEXT NOT NULL,
                    `recordVersion` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_wh_trf_no` ON `warehouse_transfers` (`transferNo`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_wh_trf_sync_uuid` ON `warehouse_transfers` (`syncUuid`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `warehouse_transfer_items` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `transferId` INTEGER NOT NULL,
                    `transferUuid` TEXT NOT NULL,
                    `itemCategory` TEXT NOT NULL,
                    `itemId` INTEGER NOT NULL,
                    `itemCode` TEXT NOT NULL,
                    `itemName` TEXT NOT NULL,
                    `unit` TEXT NOT NULL,
                    `requestedQty` REAL NOT NULL,
                    `approvedQty` REAL NOT NULL,
                    `shippedQty` REAL NOT NULL,
                    `receivedQty` REAL NOT NULL,
                    `rejectedQty` REAL NOT NULL,
                    `unitCost` REAL NOT NULL,
                    `totalCost` REAL NOT NULL,
                    `batchNumber` TEXT NOT NULL,
                    `qcApprovedRequired` INTEGER NOT NULL,
                    `qcApproved` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_wh_trf_item_trf_id` ON `warehouse_transfer_items` (`transferId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `packaging_rules` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `syncUuid` TEXT NOT NULL,
                    `weightGrams` INTEGER NOT NULL,
                    `label` TEXT NOT NULL,
                    `cansPerCarton` INTEGER NOT NULL,
                    `emptyCanItemId` INTEGER,
                    `lidItemId` INTEGER,
                    `labelItemId` INTEGER,
                    `cartonItemId` INTEGER,
                    `defaultEmptyCanCostEgp` REAL NOT NULL,
                    `defaultLidCostEgp` REAL NOT NULL,
                    `defaultLabelCostEgp` REAL NOT NULL,
                    `defaultCartonBoxCostEgp` REAL NOT NULL,
                    `active` INTEGER NOT NULL,
                    `updatedBy` TEXT NOT NULL,
                    `recordVersion` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_pkg_rule_weight` ON `packaging_rules` (`weightGrams`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_outbox` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `operationId` TEXT NOT NULL,
                    `entityType` TEXT NOT NULL,
                    `entityUuid` TEXT NOT NULL,
                    `action` TEXT NOT NULL,
                    `payloadJson` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `retryCount` INTEGER NOT NULL,
                    `lastError` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_sync_outbox_op_id` ON `sync_outbox` (`operationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sync_outbox_status` ON `sync_outbox` (`status`)")

            // 4. Seed default packaging rules idempotently (250g=48, 900g=12, 4000g=4)
            val now = System.currentTimeMillis()
            db.execSQL(
                """
                INSERT OR IGNORE INTO `packaging_rules`
                (`syncUuid`, `weightGrams`, `label`, `cansPerCarton`, `defaultEmptyCanCostEgp`, `defaultLidCostEgp`, `defaultLabelCostEgp`, `defaultCartonBoxCostEgp`, `active`, `updatedBy`, `recordVersion`, `updatedAt`)
                VALUES
                ('PKG-RULE-250', 250, '250 جرام (48 علبة/كرتونة)', 48, 3.2, 0.9, 0.6, 16.0, 1, 'SYSTEM', 1, $now),
                ('PKG-RULE-900', 900, '900 جرام (12 علبة/كرتونة)', 12, 5.5, 1.4, 0.9, 18.0, 1, 'SYSTEM', 1, $now),
                ('PKG-RULE-4000', 4000, '4000 جرام - جالون (4 علب/كرتونة)', 4, 14.0, 3.5, 1.8, 22.0, 1, 'SYSTEM', 1, $now)
                """.trimIndent()
            )

            // 5. Idempotently project existing historical stock_moves into append-only inventory_movements
            db.execSQL(
                """
                INSERT OR IGNORE INTO `inventory_movements` (
                    `syncUuid`, `documentNo`, `movementType`, `warehouseId`, `warehouseCode`, `warehouseClass`,
                    `itemCategory`, `itemId`, `itemCode`, `itemName`, `quantity`, `signedQuantity`, `unit`,
                    `unitCost`, `totalCost`, `currency`, `batchNumber`, `expiryDate`, `referenceDocType`,
                    `referenceDocId`, `referenceDocNo`, `reversedMovementId`, `reason`, `status`,
                    `qcApproved`, `idempotencyKey`, `performedBy`, `approvedBy`, `deviceId`, `recordVersion`, `createdAt`, `updatedAt`
                )
                SELECT
                    'HIST-SM-' || sm.`id`,
                    'HIST-SM-' || sm.`id`,
                    CASE
                        WHEN UPPER(TRIM(sm.`kind`)) IN ('RETURN', 'RETURN_IN', 'مرتجع') THEN 'CUSTOMER_RETURN'
                        WHEN UPPER(TRIM(sm.`kind`)) IN ('SALE', 'OUT') THEN 'SALES_ISSUE'
                        WHEN UPPER(TRIM(sm.`kind`)) IN ('IN', 'وارد', 'توريد', 'إضافة', 'CANCEL') THEN 'OPENING_BALANCE'
                        ELSE 'STOCK_ADJUSTMENT'
                    END,
                    sm.`warehouseId`,
                    COALESCE(w.`code`, 'WH-' || sm.`warehouseId`),
                    COALESCE(w.`warehouseClass`, 'SALES'),
                    'FINISHED_GOOD',
                    sm.`productId`,
                    COALESCE(p.`sku`, 'PROD-' || sm.`productId`),
                    COALESCE(p.`name`, 'منتج #' || sm.`productId`),
                    ABS(sm.`quantity`),
                    CASE
                        WHEN UPPER(TRIM(sm.`kind`)) IN ('IN', 'RETURN', 'RETURN_IN', 'CANCEL', 'وارد', 'مرتجع', 'توريد', 'إضافة')
                            THEN ABS(sm.`quantity`)
                        ELSE -ABS(sm.`quantity`)
                    END,
                    COALESCE(p.`unit`, 'كرتونة'),
                    COALESCE(p.`purchasePrice`, 0.0),
                    ABS(sm.`quantity`) * COALESCE(p.`purchasePrice`, 0.0),
                    COALESCE(w.`currency`, 'SDG'),
                    COALESCE(sm.`batchNumber`, ''),
                    '',
                    CASE WHEN sm.`invoiceId` IS NOT NULL THEN 'SALES' ELSE 'HISTORICAL_STOCK_MOVE' END,
                    sm.`invoiceId`,
                    COALESCE('HIST-SM-' || sm.`id`, ''),
                    NULL,
                    COALESCE(sm.`note`, ''),
                    'POSTED',
                    1,
                    'HIST-SM-' || sm.`id`,
                    COALESCE(sm.`createdBy`, 'المدير'),
                    'MIGRATION_V11_V12',
                    'MIGRATION',
                    1,
                    sm.`createdAt`,
                    sm.`createdAt`
                FROM `stock_moves` sm
                LEFT JOIN `warehouses` w ON w.`id` = sm.`warehouseId`
                LEFT JOIN `products` p ON p.`id` = sm.`productId`
                """.trimIndent()
            )

            // 6. Idempotently project existing factory_moves into inventory_movements mapped to RAW_MATERIALS / FINISHED_GOODS warehouses
            db.execSQL(
                """
                INSERT OR IGNORE INTO `inventory_movements` (
                    `syncUuid`, `documentNo`, `movementType`, `warehouseId`, `warehouseCode`, `warehouseClass`,
                    `itemCategory`, `itemId`, `itemCode`, `itemName`, `quantity`, `signedQuantity`, `unit`,
                    `unitCost`, `totalCost`, `currency`, `batchNumber`, `expiryDate`, `referenceDocType`,
                    `referenceDocId`, `referenceDocNo`, `reversedMovementId`, `reason`, `status`,
                    `qcApproved`, `idempotencyKey`, `performedBy`, `approvedBy`, `deviceId`, `recordVersion`, `createdAt`, `updatedAt`
                )
                SELECT
                    'HIST-FM-' || fm.`id`,
                    'HIST-FM-' || fm.`id`,
                    CASE
                        WHEN fm.`kind` LIKE '%توريد%' OR UPPER(TRIM(fm.`kind`)) = 'RAW_RECEIPT' THEN 'PURCHASE_RECEIPT'
                        WHEN fm.`kind` LIKE '%إنتاج%' OR UPPER(TRIM(fm.`kind`)) = 'PRODUCTION_OUTPUT' THEN 'PRODUCTION_RECEIPT'
                        WHEN fm.`kind` LIKE '%تالف%' THEN 'DAMAGED_GOODS'
                        WHEN fm.`kind` LIKE '%صرف%' THEN 'PRODUCTION_ISSUE'
                        ELSE 'STOCK_ADJUSTMENT'
                    END,
                    COALESCE(
                        CASE
                            WHEN fi.`category` = 'finished' THEN (SELECT `id` FROM `warehouses` WHERE `warehouseClass` = 'FINISHED_GOODS' ORDER BY `id` ASC LIMIT 1)
                            ELSE (SELECT `id` FROM `warehouses` WHERE `warehouseClass` = 'RAW_MATERIALS' ORDER BY `id` ASC LIMIT 1)
                        END,
                        (SELECT `id` FROM `warehouses` ORDER BY `id` ASC LIMIT 1),
                        1
                    ),
                    COALESCE(
                        CASE
                            WHEN fi.`category` = 'finished' THEN (SELECT `code` FROM `warehouses` WHERE `warehouseClass` = 'FINISHED_GOODS' ORDER BY `id` ASC LIMIT 1)
                            ELSE (SELECT `code` FROM `warehouses` WHERE `warehouseClass` = 'RAW_MATERIALS' ORDER BY `id` ASC LIMIT 1)
                        END,
                        'WH-FAC'
                    ),
                    CASE WHEN fi.`category` = 'finished' THEN 'FINISHED_GOODS' ELSE 'RAW_MATERIALS' END,
                    CASE
                        WHEN fi.`category` = 'empty' THEN 'PACKAGING'
                        WHEN fi.`category` = 'finished' THEN 'FINISHED_GOOD'
                        ELSE 'RAW_MATERIAL'
                    END,
                    fm.`itemId`,
                    COALESCE(fi.`itemCode`, 'RAW-' || fm.`itemId`),
                    COALESCE(fm.`itemName`, fi.`name`, 'خامة #' || fm.`itemId`),
                    ABS(fm.`qty`),
                    CASE
                        WHEN fm.`kind` LIKE '%توريد%' OR fm.`kind` LIKE '%إنتاج%' OR fm.`kind` LIKE '%وارد%' OR UPPER(TRIM(fm.`kind`)) IN ('RAW_RECEIPT', 'PRODUCTION_OUTPUT', 'IN')
                            THEN ABS(fm.`qty`)
                        ELSE -ABS(fm.`qty`)
                    END,
                    COALESCE(fi.`unit`, 'كجم'),
                    COALESCE(fi.`unitCostEgp`, 0.0),
                    ABS(fm.`qty`) * COALESCE(fi.`unitCostEgp`, 0.0),
                    'EGP',
                    COALESCE(fm.`batch`, ''),
                    '',
                    'HISTORICAL_FACTORY_MOVE',
                    fm.`id`,
                    'HIST-FM-' || fm.`id`,
                    NULL,
                    COALESCE(fm.`note`, ''),
                    'POSTED',
                    1,
                    'HIST-FM-' || fm.`id`,
                    COALESCE(fm.`actor`, 'مهندس الإنتاج'),
                    'MIGRATION_V11_V12',
                    'MIGRATION',
                    1,
                    fm.`createdAt`,
                    fm.`createdAt`
                FROM `factory_moves` fm
                LEFT JOIN `factory_items` fi ON fi.`id` = fm.`itemId`
                """.trimIndent()
            )

            // 7. Compute initial atomic warehouse_stock_balances from projected inventory_movements
            db.execSQL(
                """
                INSERT OR IGNORE INTO `warehouse_stock_balances` (
                    `syncUuid`, `warehouseId`, `warehouseClass`, `itemCategory`, `itemId`, `itemCode`, `itemName`,
                    `unit`, `batchNumber`, `expiryDate`, `onHandQty`, `reservedQty`, `inTransitQty`,
                    `quarantineQty`, `availableQty`, `avgUnitCost`, `currency`, `lastMovementUuid`, `updatedAt`
                )
                SELECT
                    'BAL-' || im.`warehouseId` || '-' || im.`itemCategory` || '-' || im.`itemId` || '-' || im.`batchNumber`,
                    im.`warehouseId`,
                    MAX(im.`warehouseClass`),
                    im.`itemCategory`,
                    im.`itemId`,
                    MAX(im.`itemCode`),
                    MAX(im.`itemName`),
                    MAX(im.`unit`),
                    im.`batchNumber`,
                    '',
                    MAX(0.0, COALESCE(SUM(im.`signedQuantity`), 0.0)),
                    0.0,
                    0.0,
                    0.0,
                    MAX(0.0, COALESCE(SUM(im.`signedQuantity`), 0.0)),
                    COALESCE(AVG(NULLIF(im.`unitCost`, 0.0)), 0.0),
                    MAX(im.`currency`),
                    MAX(im.`syncUuid`),
                    $now
                FROM `inventory_movements` im
                WHERE im.`status` = 'POSTED'
                GROUP BY im.`warehouseId`, im.`itemCategory`, im.`itemId`, im.`batchNumber`
                """.trimIndent()
            )
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
                upgradeSchemaToV12AndBackfill(db)
            }
        }

        private fun upgradeSchemaToV13Phase3(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `purchase_receipts` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `syncUuid` TEXT NOT NULL,
                    `grnNo` TEXT NOT NULL,
                    `poNo` TEXT NOT NULL,
                    `receiptDate` TEXT NOT NULL,
                    `supplierName` TEXT NOT NULL,
                    `warehouseId` INTEGER NOT NULL,
                    `materialId` INTEGER NOT NULL,
                    `materialCode` TEXT NOT NULL,
                    `materialName` TEXT NOT NULL,
                    `unit` TEXT NOT NULL,
                    `batchNumber` TEXT NOT NULL,
                    `expiryDate` TEXT NOT NULL,
                    `orderedQty` REAL NOT NULL,
                    `receivedQty` REAL NOT NULL,
                    `acceptedQty` REAL NOT NULL,
                    `rejectedQty` REAL NOT NULL,
                    `unitCost` REAL NOT NULL,
                    `totalCost` REAL NOT NULL,
                    `currency` TEXT NOT NULL,
                    `discrepancyReason` TEXT NOT NULL,
                    `qcStatus` TEXT NOT NULL,
                    `stockPosted` INTEGER NOT NULL,
                    `notes` TEXT NOT NULL,
                    `performedBy` TEXT NOT NULL,
                    `approvedBy` TEXT NOT NULL,
                    `idempotencyKey` TEXT NOT NULL,
                    `recordVersion` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_pr_sync_uuid` ON `purchase_receipts` (`syncUuid`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_pr_grn_no` ON `purchase_receipts` (`grnNo`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_pr_material_id` ON `purchase_receipts` (`materialId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `production_orders` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `syncUuid` TEXT NOT NULL,
                    `orderNo` TEXT NOT NULL,
                    `orderDate` TEXT NOT NULL,
                    `formulaId` INTEGER,
                    `formulaNo` TEXT NOT NULL,
                    `productId` INTEGER,
                    `productName` TEXT NOT NULL,
                    `paintColor` TEXT NOT NULL,
                    `packageWeightGrams` INTEGER NOT NULL,
                    `cansPerCarton` INTEGER NOT NULL,
                    `targetCartons` INTEGER NOT NULL,
                    `targetCans` INTEGER NOT NULL,
                    `targetWeightKg` REAL NOT NULL,
                    `actualGoodCans` INTEGER NOT NULL,
                    `rejectedCans` INTEGER NOT NULL,
                    `reworkCans` INTEGER NOT NULL,
                    `producedFullCartons` INTEGER NOT NULL,
                    `producedLooseCans` INTEGER NOT NULL,
                    `wastedRawKg` REAL NOT NULL,
                    `bomLinesJson` TEXT NOT NULL,
                    `machineName` TEXT NOT NULL,
                    `productionEngineer` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `batchNumber` TEXT NOT NULL,
                    `rawWarehouseId` INTEGER NOT NULL,
                    `fgWarehouseId` INTEGER NOT NULL,
                    `qcInspectedBy` TEXT NOT NULL,
                    `qcNotes` TEXT NOT NULL,
                    `approvedBy` TEXT NOT NULL,
                    `idempotencyKey` TEXT NOT NULL,
                    `recordVersion` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_po_sync_uuid` ON `production_orders` (`syncUuid`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_po_order_no` ON `production_orders` (`orderNo`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `qc_inspections` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `syncUuid` TEXT NOT NULL,
                    `qcNo` TEXT NOT NULL,
                    `sourceType` TEXT NOT NULL,
                    `sourceDocId` INTEGER,
                    `sourceDocNo` TEXT NOT NULL,
                    `batchNumber` TEXT NOT NULL,
                    `itemCategory` TEXT NOT NULL,
                    `itemId` INTEGER NOT NULL,
                    `itemName` TEXT NOT NULL,
                    `inspectedQty` REAL NOT NULL,
                    `acceptedQty` REAL NOT NULL,
                    `rejectedQty` REAL NOT NULL,
                    `reworkQty` REAL NOT NULL,
                    `measuredDensity` REAL NOT NULL,
                    `expectedMinDensity` REAL NOT NULL,
                    `expectedMaxDensity` REAL NOT NULL,
                    `measuredViscosity` TEXT NOT NULL,
                    `colorMatchPassed` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `inspectorName` TEXT NOT NULL,
                    `notes` TEXT NOT NULL,
                    `postedToStock` INTEGER NOT NULL,
                    `idempotencyKey` TEXT NOT NULL,
                    `recordVersion` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_qc_sync_uuid` ON `qc_inspections` (`syncUuid`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_qc_no` ON `qc_inspections` (`qcNo`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_qc_source_doc` ON `qc_inspections` (`sourceDocNo`)")
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
                upgradeSchemaToV12AndBackfill(db)
                upgradeSchemaToV13Phase3(db)
            }
        }

        private fun upgradeSchemaToV14Accounting(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `accounting_accounts` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `syncUuid` TEXT NOT NULL,
                    `accountCode` TEXT NOT NULL,
                    `nameAr` TEXT NOT NULL,
                    `nameEn` TEXT NOT NULL,
                    `accountType` TEXT NOT NULL,
                    `normalSide` TEXT NOT NULL,
                    `parentCode` TEXT NOT NULL,
                    `currencyHint` TEXT NOT NULL,
                    `isSystemAccount` INTEGER NOT NULL,
                    `active` INTEGER NOT NULL,
                    `recordVersion` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_acc_code` ON `accounting_accounts` (`accountCode`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_acc_uuid` ON `accounting_accounts` (`syncUuid`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `journal_entries` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `syncUuid` TEXT NOT NULL,
                    `entryNo` TEXT NOT NULL,
                    `entryDate` TEXT NOT NULL,
                    `sourceType` TEXT NOT NULL,
                    `sourceDocId` INTEGER,
                    `sourceDocNo` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `currency` TEXT NOT NULL,
                    `fxRateToUsd` REAL NOT NULL,
                    `fxRateToSdg` REAL NOT NULL,
                    `totalDebit` REAL NOT NULL,
                    `totalCredit` REAL NOT NULL,
                    `status` TEXT NOT NULL,
                    `reversedEntryId` INTEGER,
                    `reversedByEntryNo` TEXT NOT NULL,
                    `reversalReason` TEXT NOT NULL,
                    `idempotencyKey` TEXT NOT NULL,
                    `createdBy` TEXT NOT NULL,
                    `approvedBy` TEXT NOT NULL,
                    `deviceId` TEXT NOT NULL,
                    `recordVersion` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_jrn_uuid` ON `journal_entries` (`syncUuid`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_jrn_no` ON `journal_entries` (`entryNo`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_jrn_idemp` ON `journal_entries` (`idempotencyKey`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `journal_lines` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `syncUuid` TEXT NOT NULL,
                    `journalEntryId` INTEGER NOT NULL,
                    `journalEntryUuid` TEXT NOT NULL,
                    `lineOrder` INTEGER NOT NULL,
                    `accountCode` TEXT NOT NULL,
                    `accountName` TEXT NOT NULL,
                    `accountType` TEXT NOT NULL,
                    `debit` REAL NOT NULL,
                    `credit` REAL NOT NULL,
                    `currency` TEXT NOT NULL,
                    `costCenter` TEXT NOT NULL,
                    `partyType` TEXT NOT NULL,
                    `partyName` TEXT NOT NULL,
                    `memo` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_jln_entry_id` ON `journal_lines` (`journalEntryId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_jln_acc_code` ON `journal_lines` (`accountCode`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_jln_uuid` ON `journal_lines` (`syncUuid`)")

            // Seed canonical PMP Industrial ERP Chart of Accounts idempotently
            val now = System.currentTimeMillis()
            val seedRows = listOf(
                arrayOf("ACC-1110", "1110", "صندوق النقدية - السودان (SDG)", "Cashbox Sudan SDG", "ASSET", "DEBIT", "1100", "SDG"),
                arrayOf("ACC-1111", "1111", "صندوق النقدية - مصر (EGP)", "Cashbox Egypt EGP", "ASSET", "DEBIT", "1100", "EGP"),
                arrayOf("ACC-1112", "1112", "صندوق النقدية - الدولار (USD)", "Cashbox USD", "ASSET", "DEBIT", "1100", "USD"),
                arrayOf("ACC-1115", "1115", "خزائن وعهد الفروع (حلفا/عطبرة)", "Branch Cashboxes", "ASSET", "DEBIT", "1100", "ALL"),
                arrayOf("ACC-1120", "1120", "البنوك والتحويلات الوسيطة والعملات", "Banks & FX Clearing", "ASSET", "DEBIT", "1100", "ALL"),
                arrayOf("ACC-1130", "1130", "ذمم العملاء المدينة (المبيعات الآجلة)", "Accounts Receivable", "ASSET", "DEBIT", "1100", "ALL"),
                arrayOf("ACC-1141", "1141", "مخزون المواد الخام والتعبئة", "Raw Materials Inventory", "ASSET", "DEBIT", "1140", "ALL"),
                arrayOf("ACC-1142", "1142", "مخزون الإنتاج تحت التشغيل (WIP)", "Work In Progress Inventory", "ASSET", "DEBIT", "1140", "ALL"),
                arrayOf("ACC-1143", "1143", "مخزون الإنتاج التام والمبيعات", "Finished Goods Inventory", "ASSET", "DEBIT", "1140", "ALL"),
                arrayOf("ACC-1210", "1210", "الآلات ومعدات الخلط والتصنيع", "Factory Machinery & Equipment", "ASSET", "DEBIT", "1200", "ALL"),
                arrayOf("ACC-2110", "2110", "ذمم الموردين الدائنة (المشتريات)", "Accounts Payable Suppliers", "LIABILITY", "CREDIT", "2100", "ALL"),
                arrayOf("ACC-2120", "2120", "التزامات ومصروفات مستحقة", "Accrued Liabilities", "LIABILITY", "CREDIT", "2100", "ALL"),
                arrayOf("ACC-3100", "3100", "رأس المال والأرصدة الافتتاحية والأرباح المحتجزة", "Capital & Retained Earnings", "EQUITY", "CREDIT", "3000", "ALL"),
                arrayOf("ACC-4100", "4100", "إيرادات مبيعات الدهانات والبوهيات", "Paint Sales Revenue", "REVENUE", "CREDIT", "4000", "ALL"),
                arrayOf("ACC-4200", "4200", "إيرادات أخرى وفروق عملة دائنة", "Other Income & FX Gain", "REVENUE", "CREDIT", "4000", "ALL"),
                arrayOf("ACC-5100", "5100", "تكلفة البضاعة المباعة (COGS)", "Cost of Goods Sold", "EXPENSE", "DEBIT", "5000", "ALL"),
                arrayOf("ACC-5200", "5200", "فاقد ومصروفات التصنيع والإنتاج", "Manufacturing Overhead & Waste", "EXPENSE", "DEBIT", "5000", "ALL"),
                arrayOf("ACC-5300", "5300", "مصروفات التشغيل والعمومية", "Operating & General Expenses", "EXPENSE", "DEBIT", "5000", "ALL")
            )
            seedRows.forEach { row ->
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `accounting_accounts` (
                        `syncUuid`, `accountCode`, `nameAr`, `nameEn`, `accountType`, `normalSide`,
                        `parentCode`, `currencyHint`, `isSystemAccount`, `active`, `recordVersion`, `createdAt`, `updatedAt`
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 1, 1, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any>(row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7], now, now)
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
                upgradeSchemaToV12AndBackfill(db)
                upgradeSchemaToV13Phase3(db)
                upgradeSchemaToV14Accounting(db)
            }
        }

        private fun upgradeSchemaToV15Idempotency(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `idempotency_requests` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `idempotencyKey` TEXT NOT NULL,
                    `userId` TEXT NOT NULL,
                    `operationType` TEXT NOT NULL,
                    `documentReference` TEXT NOT NULL,
                    `requestStatus` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `completionTime` INTEGER,
                    `resultReference` TEXT NOT NULL,
                    `errorDetails` TEXT NOT NULL,
                    `targetCollection` TEXT NOT NULL,
                    `payloadSummary` TEXT NOT NULL,
                    `platform` TEXT NOT NULL,
                    `guardKey` TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_idemp_key` ON `idempotency_requests` (`idempotencyKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_idemp_status` ON `idempotency_requests` (`requestStatus`)")
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
                upgradeSchemaToV12AndBackfill(db)
                upgradeSchemaToV13Phase3(db)
                upgradeSchemaToV14Accounting(db)
                upgradeSchemaToV15Idempotency(db)
            }
        }

        private fun upgradeSchemaToV16TombstonesAndHardening(db: SupportSQLiteDatabase) {
            val movCols = getTableColumns(db, "movements").toMutableSet()
            if (movCols.isNotEmpty()) {
                addColumnIfMissing(db, "movements", "cloudDocId", "TEXT NOT NULL DEFAULT ''", movCols)
                addColumnIfMissing(db, "movements", "syncUuid", "TEXT NOT NULL DEFAULT ''", movCols)
                addColumnIfMissing(db, "movements", "updatedAt", "INTEGER NOT NULL DEFAULT 0", movCols)
                db.execSQL("UPDATE `movements` SET `cloudDocId` = 'MOV-' || `id` WHERE TRIM(`cloudDocId`) = ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_mov_cashbox` ON `movements` (`cashbox`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_mov_tx_date` ON `movements` (`txDate`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_mov_ref` ON `movements` (`reference`)")
            }

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `record_tombstones` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `collectionName` TEXT NOT NULL,
                    `normalizedKey` TEXT NOT NULL,
                    `entityId` TEXT NOT NULL,
                    `docId` TEXT NOT NULL,
                    `deletedAt` INTEGER NOT NULL,
                    `deletedBy` TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_tomb_col_key` ON `record_tombstones` (`collectionName`, `normalizedKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_tomb_col` ON `record_tombstones` (`collectionName`)")
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
                upgradeSchemaToV12AndBackfill(db)
                upgradeSchemaToV13Phase3(db)
                upgradeSchemaToV14Accounting(db)
                upgradeSchemaToV15Idempotency(db)
                upgradeSchemaToV16TombstonesAndHardening(db)
            }
        }

        private fun upgradeSchemaToV17ChemicalLab(db: SupportSQLiteDatabase) {
            // 1. Preserve a non-destructive pre-migration shadow backup of existing lab_formulations if present
            val lfCols = getTableColumns(db, "lab_formulations").toMutableSet()
            if (lfCols.isNotEmpty()) {
                addColumnIfMissing(db, "lab_formulations", "syncUuid", "TEXT NOT NULL DEFAULT ''", lfCols)
                addColumnIfMissing(db, "lab_formulations", "linkedProductId", "INTEGER", lfCols)
                addColumnIfMissing(db, "lab_formulations", "linkedProductSku", "TEXT NOT NULL DEFAULT ''", lfCols)
                addColumnIfMissing(db, "lab_formulations", "category", "TEXT NOT NULL DEFAULT 'معجونات ودهانات'", lfCols)
                addColumnIfMissing(db, "lab_formulations", "versionNumber", "INTEGER NOT NULL DEFAULT 1", lfCols)
                addColumnIfMissing(db, "lab_formulations", "approvalStatus", "TEXT NOT NULL DEFAULT 'DRAFT'", lfCols)
                addColumnIfMissing(db, "lab_formulations", "approvedBy", "TEXT NOT NULL DEFAULT ''", lfCols)
                addColumnIfMissing(db, "lab_formulations", "approvedAt", "INTEGER NOT NULL DEFAULT 0", lfCols)
                addColumnIfMissing(db, "lab_formulations", "historicalTotalCost", "REAL NOT NULL DEFAULT 0.0", lfCols)
                addColumnIfMissing(db, "lab_formulations", "historicalCostPerKg", "REAL NOT NULL DEFAULT 0.0", lfCols)
                addColumnIfMissing(db, "lab_formulations", "currency", "TEXT NOT NULL DEFAULT 'EGP'", lfCols)
                addColumnIfMissing(db, "lab_formulations", "changeReason", "TEXT NOT NULL DEFAULT ''", lfCols)
                addColumnIfMissing(db, "lab_formulations", "updatedBy", "TEXT NOT NULL DEFAULT ''", lfCols)
                addColumnIfMissing(db, "lab_formulations", "updatedAt", "INTEGER NOT NULL DEFAULT 0", lfCols)
                addColumnIfMissing(db, "lab_formulations", "confidentialCiphertext", "TEXT NOT NULL DEFAULT ''", lfCols)
                addColumnIfMissing(db, "lab_formulations", "isConfidentialSealed", "INTEGER NOT NULL DEFAULT 0", lfCols)

                db.execSQL(
                    """
                    UPDATE `lab_formulations` SET
                        `syncUuid` = CASE WHEN TRIM(`syncUuid`) = '' THEN 'FRM-UUID-' || `id` ELSE `syncUuid` END,
                        `approvalStatus` = CASE
                            WHEN `approvedForProduction` = 1 AND `approvalStatus` = 'DRAFT' THEN 'APPROVED'
                            ELSE `approvalStatus`
                        END,
                        `historicalTotalCost` = CASE
                            WHEN `approvedForProduction` = 1 AND `historicalTotalCost` = 0.0 THEN `totalCost`
                            ELSE `historicalTotalCost`
                        END,
                        `historicalCostPerKg` = CASE
                            WHEN `approvedForProduction` = 1 AND `historicalCostPerKg` = 0.0 THEN `costPerKg`
                            ELSE `historicalCostPerKg`
                        END,
                        `updatedAt` = CASE WHEN `updatedAt` = 0 THEN `createdAt` ELSE `updatedAt` END
                    """.trimIndent()
                )
            }

            // 2. Extend factory_items with unified raw material pricing & spec attributes
            val fiCols = getTableColumns(db, "factory_items").toMutableSet()
            if (fiCols.isNotEmpty()) {
                addColumnIfMissing(db, "factory_items", "supplierName", "TEXT NOT NULL DEFAULT ''", fiCols)
                addColumnIfMissing(db, "factory_items", "lastPurchasePrice", "REAL NOT NULL DEFAULT 0.0", fiCols)
                addColumnIfMissing(db, "factory_items", "lastPriceCurrency", "TEXT NOT NULL DEFAULT 'EGP'", fiCols)
                addColumnIfMissing(db, "factory_items", "lastPriceUpdatedAt", "INTEGER NOT NULL DEFAULT 0", fiCols)
                addColumnIfMissing(db, "factory_items", "density", "REAL NOT NULL DEFAULT 1.0", fiCols)
                addColumnIfMissing(db, "factory_items", "solidsPercent", "REAL NOT NULL DEFAULT 100.0", fiCols)
            }

            // 3. Create immutable lab_formula_versions table
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lab_formula_versions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `syncUuid` TEXT NOT NULL,
                    `formulaId` INTEGER NOT NULL,
                    `formulaNo` TEXT NOT NULL,
                    `productName` TEXT NOT NULL,
                    `versionNumber` INTEGER NOT NULL,
                    `previousVersionNumber` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `changeReason` TEXT NOT NULL,
                    `diffSummaryJson` TEXT NOT NULL,
                    `previousCostPerKg` REAL NOT NULL,
                    `newCostPerKg` REAL NOT NULL,
                    `historicalTotalCost` REAL NOT NULL,
                    `calculatedPvc` REAL NOT NULL,
                    `calculatedDensity` REAL NOT NULL,
                    `currency` TEXT NOT NULL,
                    `ingredientsSnapshotJson` TEXT NOT NULL,
                    `encryptedSnapshotCiphertext` TEXT NOT NULL,
                    `createdBy` TEXT NOT NULL,
                    `approvedBy` TEXT NOT NULL,
                    `approvedAt` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_lab_ver_uuid` ON `lab_formula_versions` (`syncUuid`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_lab_ver_formula` ON `lab_formula_versions` (`formulaId`, `versionNumber`)")

            // Backfill v1 snapshots from any existing lab_formulations so history is never empty
            db.execSQL(
                """
                INSERT OR IGNORE INTO `lab_formula_versions` (
                    `syncUuid`, `formulaId`, `formulaNo`, `productName`, `versionNumber`,
                    `previousVersionNumber`, `status`, `changeReason`, `diffSummaryJson`,
                    `previousCostPerKg`, `newCostPerKg`, `historicalTotalCost`, `calculatedPvc`,
                    `calculatedDensity`, `currency`, `ingredientsSnapshotJson`, `encryptedSnapshotCiphertext`,
                    `createdBy`, `approvedBy`, `approvedAt`, `createdAt`
                )
                SELECT
                    'FRM-VER-' || `id` || '-V' || `versionNumber`,
                    `id`, `formulaNo`, `productName`, `versionNumber`,
                    0,
                    CASE WHEN `approvedForProduction` = 1 THEN 'APPROVED' ELSE `approvalStatus` END,
                    'ترحيل غير إتلافي من سجل التركيبات السابق',
                    '{}',
                    `costPerKg`, `costPerKg`,
                    CASE WHEN `historicalTotalCost` > 0 THEN `historicalTotalCost` ELSE `totalCost` END,
                    `calculatedPvc`, `calculatedDensity`, `currency`,
                    `ingredientsJson`, `confidentialCiphertext`,
                    `createdBy`, `approvedBy`, `approvedAt`, `createdAt`
                FROM `lab_formulations`
                """.trimIndent()
            )

            // 4. Create lab_experiments table
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lab_experiments` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `syncUuid` TEXT NOT NULL,
                    `experimentNo` TEXT NOT NULL,
                    `formulaId` INTEGER NOT NULL,
                    `formulaNo` TEXT NOT NULL,
                    `formulaVersionId` INTEGER,
                    `versionNumber` INTEGER NOT NULL,
                    `productId` INTEGER,
                    `productName` TEXT NOT NULL,
                    `experimentDate` TEXT NOT NULL,
                    `technicianName` TEXT NOT NULL,
                    `materialsUsedJson` TEXT NOT NULL,
                    `measuredDensity` REAL NOT NULL,
                    `measuredViscosity` TEXT NOT NULL,
                    `measuredPh` REAL NOT NULL,
                    `measuredGrindMicrons` REAL NOT NULL,
                    `hidingPowerPercent` REAL NOT NULL,
                    `notes` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `decision` TEXT NOT NULL,
                    `decidedBy` TEXT NOT NULL,
                    `decidedAt` INTEGER NOT NULL,
                    `linkedQcNo` TEXT NOT NULL,
                    `idempotencyKey` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_lab_exp_uuid` ON `lab_experiments` (`syncUuid`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_lab_exp_formula` ON `lab_experiments` (`formulaId`, `formulaVersionId`)")

            // 5. Create raw_material_price_history table
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `raw_material_price_history` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `syncUuid` TEXT NOT NULL,
                    `materialId` INTEGER NOT NULL,
                    `itemCode` TEXT NOT NULL,
                    `materialName` TEXT NOT NULL,
                    `supplierName` TEXT NOT NULL,
                    `unitPrice` REAL NOT NULL,
                    `previousPrice` REAL NOT NULL,
                    `currency` TEXT NOT NULL,
                    `sourceType` TEXT NOT NULL,
                    `sourceDocNo` TEXT NOT NULL,
                    `effectiveDate` TEXT NOT NULL,
                    `recordedBy` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_rm_price_uuid` ON `raw_material_price_history` (`syncUuid`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_rm_price_mat` ON `raw_material_price_history` (`materialId`, `createdAt`)")

            // 6. Create lab_excel_imports table
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lab_excel_imports` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `syncUuid` TEXT NOT NULL,
                    `importNo` TEXT NOT NULL,
                    `fileName` TEXT NOT NULL,
                    `sheetCount` INTEGER NOT NULL,
                    `formulasDetected` INTEGER NOT NULL,
                    `formulasImported` INTEGER NOT NULL,
                    `materialsMatched` INTEGER NOT NULL,
                    `materialsCreated` INTEGER NOT NULL,
                    `externalLinksDetected` INTEGER NOT NULL,
                    `brokenRefsDetected` INTEGER NOT NULL,
                    `warningsCount` INTEGER NOT NULL,
                    `approvalStatus` TEXT NOT NULL,
                    `checksumSha256` TEXT NOT NULL,
                    `restrictedSummaryJson` TEXT NOT NULL,
                    `referenceArchivePath` TEXT NOT NULL,
                    `importedBy` TEXT NOT NULL,
                    `idempotencyKey` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_lab_xlsx_uuid` ON `lab_excel_imports` (`syncUuid`)")
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
                upgradeSchemaToV12AndBackfill(db)
                upgradeSchemaToV13Phase3(db)
                upgradeSchemaToV14Accounting(db)
                upgradeSchemaToV15Idempotency(db)
                upgradeSchemaToV16TombstonesAndHardening(db)
                upgradeSchemaToV17ChemicalLab(db)
            }
        }

        private fun ensureV18ImmutableLedgerColumns(db: SupportSQLiteDatabase) {
            fun addColIfMissing(table: String, col: String, ddl: String) {
                val cols = mutableSetOf<String>()
                db.query("PRAGMA table_info(`$table`)").use { cursor ->
                    val nameIdx = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameIdx >= 0) cols.add(cursor.getString(nameIdx))
                    }
                }
                if (col !in cols) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `$col` $ddl")
                }
            }
            runCatching {
                addColIfMissing("movements", "status", "TEXT NOT NULL DEFAULT 'active'")
                addColIfMissing("movements", "reversedByRef", "TEXT NOT NULL DEFAULT ''")
                addColIfMissing("movements", "reversalReason", "TEXT NOT NULL DEFAULT ''")
                addColIfMissing("movements", "syncStatus", "TEXT NOT NULL DEFAULT 'SYNCED'")

                addColIfMissing("transfers", "status", "TEXT NOT NULL DEFAULT 'active'")
                addColIfMissing("transfers", "reversedByRef", "TEXT NOT NULL DEFAULT ''")
                addColIfMissing("transfers", "reversalReason", "TEXT NOT NULL DEFAULT ''")
                addColIfMissing("transfers", "syncStatus", "TEXT NOT NULL DEFAULT 'SYNCED'")

                addColIfMissing("stock_moves", "status", "TEXT NOT NULL DEFAULT 'active'")
                addColIfMissing("stock_moves", "reversalReason", "TEXT NOT NULL DEFAULT ''")
                addColIfMissing("stock_moves", "syncStatus", "TEXT NOT NULL DEFAULT 'SYNCED'")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairSchemaForRoomValidation(db)
                upgradeSchemaToV17ChemicalLab(db)
                ensureV18ImmutableLedgerColumns(db)
            }
        }

        private val dbCallbackMutex = Mutex()
        private val dbCallbackScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
                Log.e("PmpDatabase", "Caught exception in DatabaseCallback", throwable)
            }
        )

        suspend fun purgeTombstonedRecordsLocally(dao: PmpDao): Int {
            var purged = 0
            val epoch = DeletedRecordsRegistry.getClusterEpoch()
            if (epoch > 0L) {
                purged += dao.deletePreEpochMovements(epoch)
            }
            dao.getMovementsDirect().forEach { m ->
                if (DeletedRecordsRegistry.isMovementDeleted(m)) {
                    dao.deleteMovementById(m.id)
                    if (m.reference.isNotBlank()) dao.deleteMovementByReference(m.reference)
                    if (m.cloudDocId.isNotBlank()) dao.deleteMovementByCloudDocId(m.cloudDocId)
                    if (m.syncUuid.isNotBlank()) dao.deleteMovementBySyncUuid(m.syncUuid)
                    purged++
                }
            }
            dao.getTransfersDirect().forEach { t ->
                if (DeletedRecordsRegistry.isPreEpoch(t.createdAt) ||
                    DeletedRecordsRegistry.isDeleted("transfers", t.id) ||
                    DeletedRecordsRegistry.isDeleted("transfers", "TRF-${t.id}") ||
                    (t.reference.isNotBlank() && DeletedRecordsRegistry.isDeleted("transfers", t.reference))
                ) {
                    dao.deleteTransferById(t.id)
                    purged++
                }
            }
            dao.getInvoicesDirect().forEach { inv ->
                if (DeletedRecordsRegistry.isPreEpoch(inv.createdAt) ||
                    DeletedRecordsRegistry.isDeleted("invoices", inv.id) ||
                    DeletedRecordsRegistry.isDeleted("invoices", "INV-${inv.id}") ||
                    (inv.invoiceNo.isNotBlank() && DeletedRecordsRegistry.isDeleted("invoices", inv.invoiceNo))
                ) {
                    dao.deleteInvoicePaymentsByInvoiceId(inv.id)
                    dao.deleteInvoiceItemsByInvoiceId(inv.id)
                    dao.deleteInvoiceById(inv.id)
                    purged++
                }
            }
            dao.getCustomersDirect().forEach { c ->
                if (DeletedRecordsRegistry.isPreEpoch(c.createdAt) ||
                    DeletedRecordsRegistry.isDeleted("customers", c.id) ||
                    DeletedRecordsRegistry.isDeleted("customers", "CUST-${c.id}") ||
                    DeletedRecordsRegistry.isDeleted("customers", c.name)
                ) {
                    dao.deleteCustomer(c.id)
                    purged++
                }
            }
            dao.getCashboxesDirect().forEach { cb ->
                val cbDocId = DeletedRecordsRegistry.cashboxDocId(cb.name, cb.id)
                if (DeletedRecordsRegistry.isDeleted("cashboxes", cb.id) ||
                    DeletedRecordsRegistry.isDeleted("cashboxes", "BOX-${cb.id}") ||
                    DeletedRecordsRegistry.isDeleted("cashboxes", cb.name) ||
                    DeletedRecordsRegistry.isDeleted("cashboxes", cbDocId)
                ) {
                    dao.deleteCashbox(cb.id)
                    purged++
                }
            }
            dao.getWarehousesDirect().forEach { wh ->
                if (DeletedRecordsRegistry.isDeleted("warehouses", wh.id) ||
                    DeletedRecordsRegistry.isDeleted("warehouses", "WH-${wh.id}") ||
                    DeletedRecordsRegistry.isDeleted("warehouses", wh.name)
                ) {
                    dao.clearStockMovesByWarehouse(wh.id)
                    dao.deleteWarehouse(wh.id)
                    purged++
                }
            }
            dao.getProductsDirect().forEach { p ->
                if (DeletedRecordsRegistry.isDeleted("products", p.id) ||
                    DeletedRecordsRegistry.isDeleted("products", "PROD-${p.id}") ||
                    DeletedRecordsRegistry.isDeleted("products", "PRD-${p.id}")
                ) {
                    dao.clearStockMovesByProduct(p.id)
                    dao.deleteProduct(p.id)
                    purged++
                }
            }
            dao.getStockMovesDirect().forEach { sm ->
                if (DeletedRecordsRegistry.isPreEpoch(sm.createdAt) ||
                    DeletedRecordsRegistry.isDeleted("stock_moves", sm.id) ||
                    DeletedRecordsRegistry.isDeleted("stock_moves", "SM-${sm.id}")
                ) {
                    dao.deleteStockMoveById(sm.id)
                    purged++
                }
            }
            dao.getFactoryItemsDirect().forEach { fi ->
                if (DeletedRecordsRegistry.isDeleted("factory_items", fi.id) ||
                    DeletedRecordsRegistry.isDeleted("factory_items", "FI-${fi.id}") ||
                    DeletedRecordsRegistry.isDeleted("factory_items", fi.name)
                ) {
                    dao.deleteFactoryItemById(fi.id)
                    purged++
                }
            }
            dao.getFactoryMovesDirect().forEach { fm ->
                if (DeletedRecordsRegistry.isPreEpoch(fm.createdAt) ||
                    DeletedRecordsRegistry.isDeleted("factory_moves", fm.id) ||
                    DeletedRecordsRegistry.isDeleted("factory_moves", "FM-${fm.id}")
                ) {
                    dao.deleteFactoryMoveById(fm.id)
                    purged++
                }
            }
            dao.getHalfaInvoicesDirect().forEach { hi ->
                if (DeletedRecordsRegistry.isPreEpoch(hi.createdAt) ||
                    DeletedRecordsRegistry.isDeleted("halfa_invoices", hi.id) ||
                    DeletedRecordsRegistry.isDeleted("halfa_invoices", "HINV-${hi.id}") ||
                    (hi.invoiceNo.isNotBlank() && DeletedRecordsRegistry.isDeleted("halfa_invoices", hi.invoiceNo))
                ) {
                    dao.deleteHalfaInvoiceById(hi.id)
                    purged++
                }
            }
            dao.getFormulationsDirect().forEach { f ->
                if (DeletedRecordsRegistry.isPreEpoch(f.createdAt) ||
                    DeletedRecordsRegistry.isDeleted("lab_formulations", f.id) ||
                    DeletedRecordsRegistry.isDeleted("lab_formulations", "FORM-${f.id}") ||
                    (f.formulaNo.isNotBlank() && DeletedRecordsRegistry.isDeleted("lab_formulations", f.formulaNo))
                ) {
                    dao.deleteFormulation(f.id)
                    purged++
                }
            }
            dao.getProductionBatchesDirect().forEach { b ->
                if (DeletedRecordsRegistry.isPreEpoch(b.createdAt) ||
                    DeletedRecordsRegistry.isDeleted("paint_batches", b.id) ||
                    DeletedRecordsRegistry.isDeleted("paint_batches", "BATCH-${b.id}") ||
                    (b.batchNumber.isNotBlank() && DeletedRecordsRegistry.isDeleted("paint_batches", b.batchNumber))
                ) {
                    dao.deleteProductionBatch(b.id)
                    purged++
                }
            }
            return purged
        }

        fun getDatabase(context: Context): PmpDatabase {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                runCatching {
                    DeletedAccountsRegistry.init(appContext)
                    DeletedRecordsRegistry.init(appContext)
                }
                val instance = Room.databaseBuilder(
                    appContext,
                    PmpDatabase::class.java,
                    "pmp_paints_v16.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18
                    )
                    .addCallback(DatabaseCallback(appContext))
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class DatabaseCallback(private val appContext: Context) : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            dbCallbackScope.launch {
                dbCallbackMutex.withLock {
                    try {
                        DeletedAccountsRegistry.init(appContext)
                        DeletedRecordsRegistry.init(appContext)
                        INSTANCE?.let { database ->
                            val dao = database.pmpDao()
                            val labDao = database.labDao()
                            DeletedRecordsRegistry.syncWithDao(dao)
                            val restored = DatabaseBackupManager.restoreIfEmpty(appContext, dao, labDao)
                            if (!restored) {
                                seedInitialData(dao, database.erpInventoryDao())
                            } else {
                                seedDefaultPackagingRulesIfEmpty(database.erpInventoryDao())
                            }
                            purgeTombstonedRecordsLocally(dao)
                            seedDefaultChartOfAccountsIfEmpty(database.erpAccountingDao())
                            DatabaseBackupManager.saveLocalSnapshot(appContext, dao, labDao)
                        }
                    } catch (t: Throwable) {
                        Log.e("PmpDatabase", "Error in DatabaseCallback.onCreate", t)
                    }
                }
            }
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            runCatching {
                repairSchemaForRoomValidation(db)
            }
            dbCallbackScope.launch {
                dbCallbackMutex.withLock {
                    try {
                        DeletedAccountsRegistry.init(appContext)
                        DeletedRecordsRegistry.init(appContext)
                        INSTANCE?.let { database ->
                            val dao = database.pmpDao()
                            val labDao = database.labDao()
                            DeletedRecordsRegistry.syncWithDao(dao)
                            DatabaseBackupManager.restoreIfEmpty(appContext, dao, labDao)
                            purgeTombstonedRecordsLocally(dao)
                            seedDefaultUsersIfEmpty(dao)
                            seedDefaultPackagingRulesIfEmpty(database.erpInventoryDao())
                            seedDefaultChartOfAccountsIfEmpty(database.erpAccountingDao())
                            DatabaseBackupManager.saveLocalSnapshot(appContext, dao, labDao)
                        }
                    } catch (t: Throwable) {
                        Log.e("PmpDatabase", "Error in DatabaseCallback.onOpen", t)
                    }
                }
            }
        }

        private suspend fun seedDefaultUsersIfEmpty(dao: PmpDao) {
            if (dao.getUserAccountCount() == 0) {
                val seedUsers = listOf(
                    UserAccountEntity(
                        userCode = "USR-PMP-001",
                        fullName = "الإدارة العامة - المدير التنفيذي",
                        username = "admin",
                        phoneOrEmail = "admin@pmp-paints.com",
                        pinOrPassword = "1234",
                        role = "مدير النظام",
                        branch = "كل الفروع (السودان ومصر)",
                        permissions = "الخزن,التحويلات,المبيعات,المخازن,المصنع,الأسعار,التقارير,تصدير PDF,المستخدمين,الإعدادات",
                        serverSyncToken = "SYNC-PMP-HQ-001",
                        remoteServerId = "SRV-NODE-HQ01",
                        syncStatus = "APPROVED_BY_MANAGER",
                        isCurrentSession = false,
                        active = true
                    ),
                    UserAccountEntity(
                        userCode = "USR-SD-002",
                        fullName = "محاسب مبيعات وخزنة - السودان",
                        username = "sudan_acc",
                        phoneOrEmail = "sudan@pmp-paints.com",
                        pinOrPassword = "1111",
                        role = "محاسب مبيعات وخزنة",
                        branch = "فرع السودان (SD-001)",
                        permissions = "الخزن,المبيعات,الأسعار,تصدير PDF",
                        serverSyncToken = "SYNC-PMP-SD-002",
                        remoteServerId = "SRV-NODE-SD02",
                        syncStatus = "APPROVED_BY_MANAGER",
                        isCurrentSession = false,
                        active = true
                    ),
                    UserAccountEntity(
                        userCode = "USR-EG-003",
                        fullName = "مهندس الإنتاج والتشغيل - مصنع مصر",
                        username = "egypt_factory",
                        phoneOrEmail = "factory@pmp-paints.com",
                        pinOrPassword = "2222",
                        role = "مهندس إنتاج - المصنع",
                        branch = "فرع مصر والمصنع (EG-001)",
                        permissions = "المصنع,التقارير",
                        serverSyncToken = "SYNC-PMP-EG-003",
                        remoteServerId = "SRV-NODE-EG03",
                        syncStatus = "READY_FOR_SYNC",
                        isCurrentSession = false,
                        active = true
                    ),
                    UserAccountEntity(
                        userCode = "USR-HF-004",
                        fullName = "أمين مخازن وتحويلات - حلفا",
                        username = "halfa_logistics",
                        phoneOrEmail = "halfa@pmp-paints.com",
                        pinOrPassword = "3333",
                        role = "أمين مخزن وترحيل",
                        branch = "مخزن حلفا (HF-001)",
                        permissions = "المخازن,التحويلات",
                        serverSyncToken = "SYNC-PMP-HF-004",
                        remoteServerId = "SRV-NODE-HF04",
                        syncStatus = "READY_FOR_SYNC",
                        isCurrentSession = false,
                        active = true
                    )
                )
                for (u in seedUsers) {
                    if (!DeletedAccountsRegistry.isDeleted(u.userCode, u.username, u.phoneOrEmail)) {
                        dao.insertUserAccount(u)
                    }
                }
            }
        }


        private suspend fun seedDefaultPackagingRulesIfEmpty(invDao: ErpInventoryDao) {
            if (invDao.getPackagingRulesDirect().isEmpty()) {
                val now = System.currentTimeMillis()
                invDao.upsertPackagingRules(
                    listOf(
                        PackagingRuleEntity(
                            syncUuid = "PKG-RULE-250",
                            weightGrams = 250,
                            label = "250 جرام (48 علبة/كرتونة)",
                            cansPerCarton = 48,
                            defaultEmptyCanCostEgp = 3.2,
                            defaultLidCostEgp = 0.9,
                            defaultLabelCostEgp = 0.6,
                            defaultCartonBoxCostEgp = 16.0,
                            updatedBy = "SYSTEM",
                            updatedAt = now
                        ),
                        PackagingRuleEntity(
                            syncUuid = "PKG-RULE-900",
                            weightGrams = 900,
                            label = "900 جرام (12 علبة/كرتونة)",
                            cansPerCarton = 12,
                            defaultEmptyCanCostEgp = 5.5,
                            defaultLidCostEgp = 1.4,
                            defaultLabelCostEgp = 0.9,
                            defaultCartonBoxCostEgp = 18.0,
                            updatedBy = "SYSTEM",
                            updatedAt = now
                        ),
                        PackagingRuleEntity(
                            syncUuid = "PKG-RULE-4000",
                            weightGrams = 4000,
                            label = "4000 جرام - جالون (4 علب/كرتونة)",
                            cansPerCarton = 4,
                            defaultEmptyCanCostEgp = 14.0,
                            defaultLidCostEgp = 3.5,
                            defaultLabelCostEgp = 1.8,
                            defaultCartonBoxCostEgp = 22.0,
                            updatedBy = "SYSTEM",
                            updatedAt = now
                        )
                    )
                )
            }
        }

        private suspend fun seedDefaultChartOfAccountsIfEmpty(accDao: ErpAccountingDao) {
            if (accDao.getAccountCount() == 0) {
                val now = System.currentTimeMillis()
                accDao.upsertAccounts(
                    listOf(
                        ChartAccountEntity(syncUuid = "ACC-1110", accountCode = "1110", nameAr = "صندوق النقدية - السودان (SDG)", nameEn = "Cashbox Sudan SDG", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1100", currencyHint = "SDG", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-1111", accountCode = "1111", nameAr = "صندوق النقدية - مصر (EGP)", nameEn = "Cashbox Egypt EGP", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1100", currencyHint = "EGP", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-1112", accountCode = "1112", nameAr = "صندوق النقدية - الدولار (USD)", nameEn = "Cashbox USD", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1100", currencyHint = "USD", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-1115", accountCode = "1115", nameAr = "خزائن وعهد الفروع (حلفا/عطبرة)", nameEn = "Branch Cashboxes", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1100", currencyHint = "ALL", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-1120", accountCode = "1120", nameAr = "البنوك والتحويلات الوسيطة والعملات", nameEn = "Banks & FX Clearing", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1100", currencyHint = "ALL", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-1130", accountCode = "1130", nameAr = "ذمم العملاء المدينة (المبيعات الآجلة)", nameEn = "Accounts Receivable", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1100", currencyHint = "ALL", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-1141", accountCode = "1141", nameAr = "مخزون المواد الخام والتعبئة", nameEn = "Raw Materials Inventory", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1140", currencyHint = "ALL", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-1142", accountCode = "1142", nameAr = "مخزون الإنتاج تحت التشغيل (WIP)", nameEn = "Work In Progress Inventory", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1140", currencyHint = "ALL", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-1143", accountCode = "1143", nameAr = "مخزون الإنتاج التام والمبيعات", nameEn = "Finished Goods Inventory", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1140", currencyHint = "ALL", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-1210", accountCode = "1210", nameAr = "الآلات ومعدات الخلط والتصنيع", nameEn = "Factory Machinery & Equipment", accountType = AccountType.ASSET.name, normalSide = "DEBIT", parentCode = "1200", currencyHint = "ALL", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-2110", accountCode = "2110", nameAr = "ذمم الموردين الدائنة (المشتريات)", nameEn = "Accounts Payable Suppliers", accountType = AccountType.LIABILITY.name, normalSide = "CREDIT", parentCode = "2100", currencyHint = "ALL", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-2120", accountCode = "2120", nameAr = "التزامات ومصروفات مستحقة", nameEn = "Accrued Liabilities", accountType = AccountType.LIABILITY.name, normalSide = "CREDIT", parentCode = "2100", currencyHint = "ALL", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-3100", accountCode = "3100", nameAr = "رأس المال والأرصدة الافتتاحية والأرباح المحتجزة", nameEn = "Capital & Retained Earnings", accountType = AccountType.EQUITY.name, normalSide = "CREDIT", parentCode = "3000", currencyHint = "ALL", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-4100", accountCode = "4100", nameAr = "إيرادات مبيعات الدهانات والبوهيات", nameEn = "Paint Sales Revenue", accountType = AccountType.REVENUE.name, normalSide = "CREDIT", parentCode = "4000", currencyHint = "ALL", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-4200", accountCode = "4200", nameAr = "إيرادات أخرى وفروق عملة دائنة", nameEn = "Other Income & FX Gain", accountType = AccountType.REVENUE.name, normalSide = "CREDIT", parentCode = "4000", currencyHint = "ALL", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-5100", accountCode = "5100", nameAr = "تكلفة البضاعة المباعة (COGS)", nameEn = "Cost of Goods Sold", accountType = AccountType.EXPENSE.name, normalSide = "DEBIT", parentCode = "5000", currencyHint = "ALL", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-5200", accountCode = "5200", nameAr = "فاقد ومصروفات التصنيع والإنتاج", nameEn = "Manufacturing Overhead & Waste", accountType = AccountType.EXPENSE.name, normalSide = "DEBIT", parentCode = "5000", currencyHint = "ALL", createdAt = now, updatedAt = now),
                        ChartAccountEntity(syncUuid = "ACC-5300", accountCode = "5300", nameAr = "مصروفات التشغيل والعمومية", nameEn = "Operating & General Expenses", accountType = AccountType.EXPENSE.name, normalSide = "DEBIT", parentCode = "5000", currencyHint = "ALL", createdAt = now, updatedAt = now)
                    )
                )
            }
        }

        private suspend fun seedInitialData(dao: PmpDao, invDao: ErpInventoryDao) {
            seedDefaultUsersIfEmpty(dao)
            seedDefaultPackagingRulesIfEmpty(invDao)
            // Seed Cashboxes (Separated for each branch & region)
            val cashboxes = listOf("خزنة السودان", "خزنة مصر", "خزنة حلفا", "خزنة عطبرة")
            cashboxes.forEach { boxName ->
                if (!DeletedRecordsRegistry.isDeleted("cashboxes", boxName)) {
                    dao.insertCashbox(CashboxEntity(name = boxName))
                }
            }

            // Seed Three-Warehouse Architecture (Sales, Raw Materials, Finished Goods)
            val warehouses = listOf(
                WarehouseEntity(name = "مخزن مبيعات حلفا", currency = "SDG", linkedCashbox = "خزنة حلفا", warehouseType = "مبيعات", warehouseClass = WarehouseClass.SALES.name, code = "WH-SLS-HF", branch = "حلفا", allowDirectSales = true, allowProductionReceipt = false),
                WarehouseEntity(name = "مخزن مبيعات عطبرة", currency = "SDG", linkedCashbox = "خزنة عطبرة", warehouseType = "مبيعات", warehouseClass = WarehouseClass.SALES.name, code = "WH-SLS-AT", branch = "عطبرة", allowDirectSales = true, allowProductionReceipt = false),
                WarehouseEntity(name = "مخزن مبيعات الخرطوم", currency = "SDG", linkedCashbox = "خزنة السودان", warehouseType = "مبيعات", warehouseClass = WarehouseClass.SALES.name, code = "WH-SLS-KH", branch = "الخرطوم", allowDirectSales = true, allowProductionReceipt = false),
                WarehouseEntity(name = "مخزن مبيعات المصنع (مصر)", currency = "EGP", linkedCashbox = "خزنة مصر", warehouseType = "مبيعات", warehouseClass = WarehouseClass.SALES.name, code = "WH-SLS-EG", branch = "القاهرة", allowDirectSales = true, allowProductionReceipt = false),
                WarehouseEntity(name = "مخزن المواد الخام داخل المصنع (مصر)", currency = "EGP", linkedCashbox = "خزنة مصر", warehouseType = "مواد خام", warehouseClass = WarehouseClass.RAW_MATERIALS.name, code = "WH-RAW-EG", branch = "المصنع", allowDirectSales = false, allowProductionReceipt = false),
                WarehouseEntity(name = "مخزن الإنتاج التام داخل المصنع", currency = "EGP", linkedCashbox = "خزنة مصر", warehouseType = "داخل المصنع/إنتاج", warehouseClass = WarehouseClass.FINISHED_GOODS.name, code = "WH-FG-EG", branch = "المصنع", allowDirectSales = false, allowProductionReceipt = true)
            )
            warehouses.forEach { dao.insertWarehouse(it) }

            // Seed Products (PMP Paints) with Dual-Currency Pricing (SDG & EGP)
            val colors = listOf("أبيض", "ألوان", "فضي", "ذهبي", "ورنيش")
            val weights = listOf("250", "900")
            var skuCounter = 101
            for (weight in weights) {
                for (color in colors) {
                    val pPrice = if (weight == "250") 420000.0 else 320000.0
                    val sPrice = if (weight == "250") 500000.0 else 385000.0
                    val sPriceEgp = if (weight == "250") 2500.0 else 1925.0
                    val pid = dao.insertProduct(
                        ProductEntity(
                            sku = "PMP-$skuCounter",
                            name = "بوهية $color $weight جم",
                            category = "بوهيات",
                            unit = "كرتونة",
                            purchasePrice = pPrice,
                            salePrice = sPrice,
                            salePriceEgp = sPriceEgp,
                            minimumStock = 15.0,
                            packageWeight = weight,
                            paintColor = color
                        )
                    )
                    skuCounter++

                    // Seed initial stock
                    dao.insertStockMove(
                        StockMoveEntity(
                            warehouseId = 1L,
                            productId = pid,
                            quantity = if (color == "أبيض") 120.0 else 60.0,
                            kind = "IN",
                            note = "رصيد افتتاحي للمخزن"
                        )
                    )
                }
            }

            // Seed Customers
            val c1 = dao.insertCustomer(
                CustomerEntity(
                    name = "شركة النيل للمقاولات والديكور",
                    phone = "0912345678",
                    address = "الخرطوم - السوق المحلي",
                    notes = "عميل مميز - دفع آجل"
                )
            )
            val c2 = dao.insertCustomer(
                CustomerEntity(
                    name = "مؤسسة الأهرام لمواد البناء",
                    phone = "01098765432",
                    address = "القاهرة - مدينة نصر",
                    notes = "توزيع فرع مصر"
                )
            )
            dao.insertCustomer(
                CustomerEntity(
                    name = "محلات الصادق للبويات والعدد",
                    phone = "0987654321",
                    address = "عطبرة - السوق الكبير",
                    notes = "سداد فوري"
                )
            )

            // Seed Initial Movements (Cash balances)
            dao.insertMovement(
                MovementEntity(
                    txDate = "2026-10-20",
                    cashbox = "خزنة السودان",
                    currency = "SDG",
                    direction = "وارد",
                    amount = 45000000.0,
                    description = "رصيد افتتاحي الخزنة",
                    reference = "OP-SDG-01"
                )
            )
            dao.insertMovement(
                MovementEntity(
                    txDate = "2026-10-21",
                    cashbox = "خزنة مصر",
                    currency = "EGP",
                    direction = "وارد",
                    amount = 750000.0,
                    description = "رصيد افتتاحي فرع مصر",
                    reference = "OP-EGP-01"
                )
            )
            dao.insertMovement(
                MovementEntity(
                    txDate = "2026-10-22",
                    cashbox = "خزنة مصر",
                    currency = "USD",
                    direction = "وارد",
                    amount = 15000.0,
                    description = "سيولة بالدولار للمشتريات",
                    reference = "OP-USD-01"
                )
            )

            // Seed Factory Items & Machines
            val m1 = dao.insertFactoryMachine(FactoryMachineEntity(name = "ماكينة التعبئة الآلية 1"))
            val m2 = dao.insertFactoryMachine(FactoryMachineEntity(name = "ماكينة الخلط والطحن 2"))
            dao.insertFactoryMachine(FactoryMachineEntity(name = "ماكينة التغليف والعلب 3"))

            val rawPigment = dao.insertFactoryItem(FactoryItemEntity(name = "صبغات تيتانيوم أبيض", unit = "كجم", category = "raw"))
            val rawResin = dao.insertFactoryItem(FactoryItemEntity(name = "راتنجات ألكيد نقية", unit = "برميل", category = "raw"))
            val finPaint = dao.insertFactoryItem(FactoryItemEntity(name = "علب بوهية تام الصنع 900 جم", unit = "علبة", category = "finished"))
            val emptyDrum = dao.insertFactoryItem(FactoryItemEntity(name = "براميل حديد فارغة", unit = "برميل", category = "empty"))

            // Seed sample factory moves
            dao.insertFactoryMove(
                FactoryMoveEntity(
                    moveDate = "2026-10-23",
                    kind = "RAW_RECEIPT",
                    itemId = rawPigment,
                    itemName = "صبغات تيتانيوم أبيض",
                    qty = 2500.0,
                    note = "توريد خام من المورد"
                )
            )
            dao.insertFactoryMove(
                FactoryMoveEntity(
                    moveDate = "2026-10-24",
                    kind = "PRODUCTION_OUTPUT",
                    itemId = finPaint,
                    itemName = "علب بوهية تام الصنع 900 جم",
                    qty = 1500.0,
                    machineId = m1,
                    machineName = "ماكينة التعبئة الآلية 1",
                    batch = "BATCH-2026-A1",
                    note = "إنتاج وردية الصباح"
                )
            )

            // Seed Poster Prices (Strictly Separated Currencies: Sudan SDG & Egypt EGP)
            val posterList = listOf(
                // SDG Prices (Sudan Sales / Halfa)
                PosterPriceEntity("250_أبيض", "250", "أبيض", 500000, 0, "SDG"),
                PosterPriceEntity("250_ألوان", "250", "ألوان", 490000, 0, "SDG"),
                PosterPriceEntity("250_فضي", "250", "فضي", 510000, 0, "SDG"),
                PosterPriceEntity("250_ذهبي", "250", "ذهبي", 520000, 0, "SDG"),
                PosterPriceEntity("250_ورنيش", "250", "ورنيش", 495000, 0, "SDG"),
                PosterPriceEntity("900_أبيض", "900", "أبيض", 385000, 0, "SDG"),
                PosterPriceEntity("900_ألوان", "900", "ألوان", 375000, 0, "SDG"),
                PosterPriceEntity("900_فضي", "900", "فضي", 390000, 0, "SDG"),
                PosterPriceEntity("900_ذهبي", "900", "ذهبي", 400000, 0, "SDG"),
                PosterPriceEntity("900_ورنيش", "900", "ورنيش", 375000, 0, "SDG"),

                // EGP Prices (Factory & Egypt - Stored exactly as EGP without SDG mixing)
                PosterPriceEntity("250_أبيض_EGP", "250", "أبيض", 2500, 2500, "EGP"),
                PosterPriceEntity("250_ألوان_EGP", "250", "ألوان", 2450, 2450, "EGP"),
                PosterPriceEntity("250_فضي_EGP", "250", "فضي", 2550, 2550, "EGP"),
                PosterPriceEntity("250_ذهبي_EGP", "250", "ذهبي", 2600, 2600, "EGP"),
                PosterPriceEntity("250_ورنيش_EGP", "250", "ورنيش", 2475, 2475, "EGP"),
                PosterPriceEntity("900_أبيض_EGP", "900", "أبيض", 1925, 1925, "EGP"),
                PosterPriceEntity("900_ألوان_EGP", "900", "ألوان", 1875, 1875, "EGP"),
                PosterPriceEntity("900_فضي_EGP", "900", "فضي", 1950, 1950, "EGP"),
                PosterPriceEntity("900_ذهبي_EGP", "900", "ذهبي", 2000, 2000, "EGP"),
                PosterPriceEntity("900_ورنيش_EGP", "900", "ورنيش", 1875, 1875, "EGP")
            )
            dao.insertPosterPrices(posterList)

            // Initial Lab Formulations (PMP Science & Chemistry Lab)
            val glossIngredients = """
                [
                    {"id":"1","name":"ثاني أكسيد التيتانيوم (Rutile TiO2)","type":"صبغة / ملون","weight":25.0,"density":4.2,"solidsPercent":100.0,"unitCost":4.5,"notes":"بياض وتغطية عالية"},
                    {"id":"2","name":"راتنج أكريليك نقي (Pure Acrylic 50%)","type":"راتنج / بوليمر رابط","weight":45.0,"density":1.06,"solidsPercent":50.0,"unitCost":2.8,"notes":"رابط اللمعان والمتانة"},
                    {"id":"3","name":"ماء نقي (Process Water)","type":"مذيب / ماء","weight":25.0,"density":1.0,"solidsPercent":0.0,"unitCost":0.05,"notes":"مذيب ناقل"},
                    {"id":"4","name":"تيكسانول (Texanol Coalescent)","type":"إضافات ومساعدات","weight":2.0,"density":0.95,"solidsPercent":0.0,"unitCost":5.2,"notes":"مساعد اندماج الفيلم"},
                    {"id":"5","name":"مغلظ هيدروكسي إيثيل سليلوز (HEC)","type":"إضافات ومساعدات","weight":1.5,"density":1.0,"solidsPercent":25.0,"unitCost":6.5,"notes":"ضبط القوام واللزوجة"},
                    {"id":"6","name":"مانع رغوة (Defoamer PD-01)","type":"إضافات ومساعدات","weight":0.5,"density":0.92,"solidsPercent":100.0,"unitCost":7.0,"notes":"إزالة فقاعات الهواء"},
                    {"id":"7","name":"مبيد بكتيري (In-can Biocide)","type":"إضافات ومساعدات","weight":1.0,"density":1.05,"solidsPercent":20.0,"unitCost":8.0,"notes":"حفظ التركيبة"}
                ]
            """.trimIndent()

            dao.insertFormulation(
                FormulationEntity(
                    formulaNo = "PMP-LAB-GLOSS-01",
                    productName = "دهان PMP سوبر جلوس أبيض لامع (Super Gloss)",
                    paintType = "دهان مائي لامع (Gloss)",
                    baseKind = "أبيض",
                    targetPvc = 18.5,
                    calculatedPvc = 18.2,
                    cpvcEstimated = 54.0,
                    totalSolidsWeight = 48.0,
                    totalSolidsVolume = 36.5,
                    totalBatchWeight = 100.0,
                    totalBatchVolume = 85.2,
                    calculatedDensity = 1.17,
                    totalCost = 285.5,
                    costPerKg = 2.855,
                    ingredientsJson = glossIngredients,
                    technicalAnalysis = "تركيبة دهان لامع فائق التغطية. نسبة الـ PVC منخفضة (18.2%) تضمن غشاء طلاء كثيف ومستمر بمقاومة استثنائية للماء وقابلية فائقة للغسيل.",
                    notes = "معتمدة للمصنع - عبوات 250 و 900 جم",
                    approvedForProduction = true
                )
            )

            val mattIngredients = """
                [
                    {"id":"1","name":"ثاني أكسيد التيتانيوم (TiO2)","type":"صبغة / ملون","weight":12.0,"density":4.2,"solidsPercent":100.0,"unitCost":4.5,"notes":"تغطية أساسية"},
                    {"id":"2","name":"كربونات كالسيوم ميكرونايزد (CaCO3 5 Micron)","type":"مالئ وحبيبات","weight":35.0,"density":2.7,"solidsPercent":100.0,"unitCost":0.4,"notes":"بودرة تعبئة ومط"},
                    {"id":"3","name":"راتنج ستايرين أكريليك (Styrene Acrylic 50%)","type":"راتنج / بوليمر رابط","weight":22.0,"density":1.04,"solidsPercent":50.0,"unitCost":2.1,"notes":"رابط مطفي اقتصادي"},
                    {"id":"4","name":"ماء نقي (Process Water)","type":"مذيب / ماء","weight":28.0,"density":1.0,"solidsPercent":0.0,"unitCost":0.05,"notes":"تخفيف"},
                    {"id":"5","name":"مشتت بيجمنت (Dispersing Agent)","type":"إضافات ومساعدات","weight":1.2,"density":1.1,"solidsPercent":40.0,"unitCost":4.2,"notes":"تثبيت واستقرار البودرة"},
                    {"id":"6","name":"مانع رغوة ومبيد","type":"إضافات ومساعدات","weight":1.8,"density":0.95,"solidsPercent":50.0,"unitCost":6.0,"notes":"حماية ورغوة"}
                ]
            """.trimIndent()

            dao.insertFormulation(
                FormulationEntity(
                    formulaNo = "PMP-LAB-MATT-02",
                    productName = "دهان PMP رويال مطفي معماري (Royal Matt)",
                    paintType = "مطفي معماري (Matt)",
                    baseKind = "أبيض",
                    targetPvc = 48.0,
                    calculatedPvc = 47.8,
                    cpvcEstimated = 52.0,
                    totalSolidsWeight = 58.0,
                    totalSolidsVolume = 42.0,
                    totalBatchWeight = 100.0,
                    totalBatchVolume = 73.5,
                    calculatedDensity = 1.36,
                    totalCost = 142.0,
                    costPerKg = 1.42,
                    ingredientsJson = mattIngredients,
                    technicalAnalysis = "تركيبة مطفي راقية (PVC 47.8%) أقل قليلاً من CPVC مما يمنح مسامية متوازنة وإخفاءً عالي العيوب للأسطح مع الحفاظ على ترابط الطبقة ومقاومة الفرك.",
                    notes = "صيغة التشغيل القياسية للفرعين بمصر والسودان",
                    approvedForProduction = true
                )
            )

            // Initial GHS / SDS Chemical Safety Catalog
            dao.insertChemicalSafety(
                ChemicalSafetyEntity(
                    chemicalName = "ثاني أكسيد التيتانيوم (Titanium Dioxide Rutile)",
                    commonName = "TiO2 بياض التيتانيوم",
                    casNumber = "13463-67-7",
                    molecularFormula = "TiO2",
                    molecularWeight = 79.87,
                    ghsPictograms = "HEALTH_HAZARD",
                    signalWord = "تحذير",
                    hazardStatements = "H351: يشتبه في التسبب بالسرطان في حالة استنشاق الغبار الدقيق المركز جداً لفترات طويلة بدون كمامة.",
                    precautionaryStatements = "P260: لا تستنشق الغبار. P280: ارتداء كمامة واقية P2 وقناع ونظارات عند تفريغ الشكائر.",
                    storageConditions = "يُحفظ في مكان جاف ومغلق جيداً بعيداً عن الرطوبة المباشرة على باليتات خشبية.",
                    firstAidMeasures = "الاستنشاق: الانتقال للهواء النقي. العين: غسل بالماء الجاري لمدة 15 دقيقة.",
                    sdsSupplierName = "Kronos Worldwide / PMP Lab Certified",
                    sdsFileUrlOrUri = "SDS_TiO2_Rutile_PMP.pdf"
                )
            )

            dao.insertChemicalSafety(
                ChemicalSafetyEntity(
                    chemicalName = "كربونات الكالسيوم (Calcium Carbonate)",
                    commonName = "بودرة كالسيت / ميكرونايزد",
                    casNumber = "471-34-1",
                    molecularFormula = "CaCO3",
                    molecularWeight = 100.09,
                    ghsPictograms = "EXCLAMATION",
                    signalWord = "انتباه",
                    hazardStatements = "مادة طبيعية غير سامة. غبارها قد يسبب تهيجاً ميكانيكياً خفيفاً للعين والأغشية التنفسية.",
                    precautionaryStatements = "P261: تجنب توليد الغبار العالق. P284: ارتداء قناع الأتربة عند الخلط والصب في الخلاط.",
                    storageConditions = "مكان جاف وجيد التهوية على ارتفاع 15 سم عن الأرضية.",
                    firstAidMeasures = "شطف العين بالماء، والراحة في هواء نقي.",
                    sdsSupplierName = "MCC Minerals / PMP Lab Registry",
                    sdsFileUrlOrUri = "SDS_CaCO3_PMP.pdf"
                )
            )

            dao.insertChemicalSafety(
                ChemicalSafetyEntity(
                    chemicalName = "تيكسانول (Texanol Ester Alcohol)",
                    commonName = "كواليسنت / مساعد اندماج",
                    casNumber = "25265-77-4",
                    molecularFormula = "C12H24O3",
                    molecularWeight = 216.32,
                    ghsPictograms = "EXCLAMATION,HEALTH_HAZARD",
                    signalWord = "تحذير",
                    hazardStatements = "H315: يسبب تهيجاً خفيفاً للجلد. H319: يسبب تهيجاً للعينين. سائل عضوي بطيء التطاير.",
                    precautionaryStatements = "P280: ارتداء قفازات نتريل ونظارات واقية. P262: تجنب ملامسة العينين والجلد.",
                    storageConditions = "يُحفظ في براميل محكمة الإغلاق بدرجة حرارة أقل من 35°م في منطقة جيدة التهوية بعيداً عن أشعة الشمس المباشرة.",
                    firstAidMeasures = "الجلد: غسل وفير بالماء والصابون. العين: غسيل فوري بالماء الطبي واستشارة طبيب إن استمر التهيج.",
                    sdsSupplierName = "Eastman Chemical / PMP Approved",
                    sdsFileUrlOrUri = "SDS_Texanol_Eastman_PMP.pdf"
                )
            )

            dao.insertChemicalSafety(
                ChemicalSafetyEntity(
                    chemicalName = "مبيد بكتيري وفطري (1,2-Benzisothiazolin-3-one)",
                    commonName = "BIT-20 In-can Preservative",
                    casNumber = "2634-33-5",
                    molecularFormula = "C7H5NOS",
                    molecularWeight = 151.19,
                    ghsPictograms = "CORROSIVE,TOXIC,ENVIRONMENT",
                    signalWord = "خطر",
                    hazardStatements = "H302: ضار إذا ابتلع. H314: يسبب حروقاً شديدة للجلد وتلفاً للعين. H317: قد يسبب حساسية بالجلد. H400: شديد السمية للأحياء المائية.",
                    precautionaryStatements = "P273: تجنب إطلاقه في البيئة. P280: ارتداء قفازات واقية من مادة نيوبرين وواقي وجه كامل وملابس واقية من الكيماويات.",
                    storageConditions = "يُحفظ في مستودع الكيماويات الخطرة تحت إشراف مهندس المختبر في حاويته الأصلية محكمة الإغلاق بدرجة 5 - 30 مئوية.",
                    firstAidMeasures = "ملامسة الجلد: نزع الملابس الملوثة فوراً وغسل الجلد لمدة 20 دقيقة. ملامسة العين: شطف مستمر وطلب إسعاف فوري.",
                    sdsSupplierName = "Lanxess Biocides / PMP Lab SDS",
                    sdsFileUrlOrUri = "SDS_BIT20_Biocide_PMP.pdf"
                )
            )

            // Audit Log
            dao.insertAuditLog(
                AuditLogEntity(
                    username = "المدير",
                    action = "SYSTEM_INITIALIZED",
                    detail = "تهيئة النظام بنجاح مع بيانات الفروع ومختبر PMP Science & Chemistry Lab"
                )
            )
        }
    }
}
