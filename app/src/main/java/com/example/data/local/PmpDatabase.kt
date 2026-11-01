package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        ChemicalSafetyEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class PmpDatabase : RoomDatabase() {

    abstract fun pmpDao(): PmpDao

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

            // Update legacy warehouses and ensure proper distinct sales warehouses for each cashbox
            try {
                // Ensure Cashboxes exist
                db.execSQL("INSERT OR IGNORE INTO `cashboxes` (`name`) VALUES ('خزنة السودان')")
                db.execSQL("INSERT OR IGNORE INTO `cashboxes` (`name`) VALUES ('خزنة مصر')")
                db.execSQL("INSERT OR IGNORE INTO `cashboxes` (`name`) VALUES ('خزنة حلفا')")
                db.execSQL("INSERT OR IGNORE INTO `cashboxes` (`name`) VALUES ('خزنة عطبرة')")

                // Update existing warehouse assignments
                db.execSQL("UPDATE `warehouses` SET `currency` = 'SDG', `linkedCashbox` = 'خزنة حلفا', `warehouseType` = 'مبيعات' WHERE `name` LIKE '%حلفا%'")
                db.execSQL("UPDATE `warehouses` SET `currency` = 'SDG', `linkedCashbox` = 'خزنة عطبرة', `warehouseType` = 'مبيعات' WHERE `name` LIKE '%عطبرة%'")
                db.execSQL("UPDATE `warehouses` SET `currency` = 'SDG', `linkedCashbox` = 'خزنة السودان', `warehouseType` = 'مبيعات' WHERE `name` LIKE '%الخرطوم%' OR `name` = 'مخزن السودان'")
                db.execSQL("UPDATE `warehouses` SET `currency` = 'EGP', `linkedCashbox` = 'خزنة مصر', `warehouseType` = 'مبيعات' WHERE `name` LIKE '%مبيعات المصنع%' OR `name` LIKE '%مبيعات مصر%'")
                db.execSQL("UPDATE `warehouses` SET `currency` = 'EGP', `linkedCashbox` = 'خزنة مصر', `warehouseType` = 'مواد خام' WHERE `name` LIKE '%مواد خام%'")
                db.execSQL("UPDATE `warehouses` SET `currency` = 'EGP', `linkedCashbox` = 'خزنة مصر', `warehouseType` = 'داخل المصنع/إنتاج' WHERE `name` LIKE '%الإنتاج التام%'")

                // Insert distinct sales warehouses for each financial cashbox if not present
                db.execSQL("INSERT OR IGNORE INTO `warehouses` (`name`, `currency`, `linkedCashbox`, `warehouseType`) VALUES ('مخزن مبيعات حلفا', 'SDG', 'خزنة حلفا', 'مبيعات')")
                db.execSQL("INSERT OR IGNORE INTO `warehouses` (`name`, `currency`, `linkedCashbox`, `warehouseType`) VALUES ('مخزن مبيعات عطبرة', 'SDG', 'خزنة عطبرة', 'مبيعات')")
                db.execSQL("INSERT OR IGNORE INTO `warehouses` (`name`, `currency`, `linkedCashbox`, `warehouseType`) VALUES ('مخزن مبيعات الخرطوم', 'SDG', 'خزنة السودان', 'مبيعات')")
                db.execSQL("INSERT OR IGNORE INTO `warehouses` (`name`, `currency`, `linkedCashbox`, `warehouseType`) VALUES ('مخزن مبيعات المصنع (مصر)', 'EGP', 'خزنة مصر', 'مبيعات')")
                db.execSQL("INSERT OR IGNORE INTO `warehouses` (`name`, `currency`, `linkedCashbox`, `warehouseType`) VALUES ('مخزن المواد الخام داخل المصنع (مصر)', 'EGP', 'خزنة مصر', 'مواد خام')")
                db.execSQL("INSERT OR IGNORE INTO `warehouses` (`name`, `currency`, `linkedCashbox`, `warehouseType`) VALUES ('مخزن الإنتاج التام داخل المصنع', 'EGP', 'خزنة مصر', 'داخل المصنع/إنتاج')")
            } catch (_: Exception) {}

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

            // Drop any indices not declared in @Entity annotations so Room schema validation never throws IllegalStateException
            db.execSQL("DROP INDEX IF EXISTS `index_user_accounts_username`")
            db.execSQL("DROP INDEX IF EXISTS `index_user_accounts_userCode`")
            db.execSQL("DROP INDEX IF EXISTS `index_invoices_invoiceNo`")
            db.execSQL("DROP INDEX IF EXISTS `index_invoice_items_invoiceId`")
            db.execSQL("DROP INDEX IF EXISTS `index_movements_reference`")
            db.execSQL("DROP INDEX IF EXISTS `index_customers_name`")
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

        fun getDatabase(context: Context): PmpDatabase {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                val instance = Room.databaseBuilder(
                    appContext,
                    PmpDatabase::class.java,
                    "pmp_paints_v16.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
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
            CoroutineScope(Dispatchers.IO).launch {
                INSTANCE?.let { database ->
                    val dao = database.pmpDao()
                    val restored = DatabaseBackupManager.restoreIfEmpty(appContext, dao)
                    if (!restored) {
                        seedInitialData(dao)
                    }
                    DatabaseBackupManager.saveLocalSnapshot(appContext, dao)
                }
            }
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            CoroutineScope(Dispatchers.IO).launch {
                INSTANCE?.let { database ->
                    val dao = database.pmpDao()
                    DatabaseBackupManager.restoreIfEmpty(appContext, dao)
                    seedDefaultUsersIfEmpty(dao)
                    DatabaseBackupManager.saveLocalSnapshot(appContext, dao)
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


        private suspend fun seedInitialData(dao: PmpDao) {
            seedDefaultUsersIfEmpty(dao)
            // Seed Cashboxes (Separated for each branch & region)
            val cashboxes = listOf("خزنة السودان", "خزنة مصر", "خزنة حلفا", "خزنة عطبرة")
            cashboxes.forEach { dao.insertCashbox(CashboxEntity(name = it)) }

            // Seed Warehouses (Separated: Sudan sales vs Factory/Egypt)
            val warehouses = listOf(
                WarehouseEntity(name = "مخزن مبيعات حلفا", currency = "SDG", linkedCashbox = "خزنة حلفا", warehouseType = "مبيعات"),
                WarehouseEntity(name = "مخزن مبيعات عطبرة", currency = "SDG", linkedCashbox = "خزنة عطبرة", warehouseType = "مبيعات"),
                WarehouseEntity(name = "مخزن مبيعات الخرطوم", currency = "SDG", linkedCashbox = "خزنة السودان", warehouseType = "مبيعات"),
                WarehouseEntity(name = "مخزن مبيعات المصنع (مصر)", currency = "EGP", linkedCashbox = "خزنة مصر", warehouseType = "مبيعات"),
                WarehouseEntity(name = "مخزن المواد الخام داخل المصنع (مصر)", currency = "EGP", linkedCashbox = "خزنة مصر", warehouseType = "مواد خام"),
                WarehouseEntity(name = "مخزن الإنتاج التام داخل المصنع", currency = "EGP", linkedCashbox = "خزنة مصر", warehouseType = "داخل المصنع/إنتاج")
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

            // Seed Poster Prices (Dual Currency: Sudan SDG & Factory/Egypt EGP)
            val posterList = listOf(
                // SDG Prices (Sudan Sales / Halfa)
                PosterPriceEntity("250_أبيض", "250", "أبيض", 500000, 2500, "SDG"),
                PosterPriceEntity("250_ألوان", "250", "ألوان", 490000, 2450, "SDG"),
                PosterPriceEntity("250_فضي", "250", "فضي", 510000, 2550, "SDG"),
                PosterPriceEntity("250_ذهبي", "250", "ذهبي", 520000, 2600, "SDG"),
                PosterPriceEntity("250_ورنيش", "250", "ورنيش", 495000, 2475, "SDG"),
                PosterPriceEntity("900_أبيض", "900", "أبيض", 385000, 1925, "SDG"),
                PosterPriceEntity("900_ألوان", "900", "ألوان", 375000, 1875, "SDG"),
                PosterPriceEntity("900_فضي", "900", "فضي", 390000, 1950, "SDG"),
                PosterPriceEntity("900_ذهبي", "900", "ذهبي", 400000, 2000, "SDG"),
                PosterPriceEntity("900_ورنيش", "900", "ورنيش", 375000, 1875, "SDG"),

                // EGP Prices (Factory & Egypt)
                PosterPriceEntity("250_أبيض_EGP", "250", "أبيض", 500000, 2500, "EGP"),
                PosterPriceEntity("250_ألوان_EGP", "250", "ألوان", 490000, 2450, "EGP"),
                PosterPriceEntity("250_فضي_EGP", "250", "فضي", 510000, 2550, "EGP"),
                PosterPriceEntity("250_ذهبي_EGP", "250", "ذهبي", 520000, 2600, "EGP"),
                PosterPriceEntity("250_ورنيش_EGP", "250", "ورنيش", 495000, 2475, "EGP"),
                PosterPriceEntity("900_أبيض_EGP", "900", "أبيض", 385000, 1925, "EGP"),
                PosterPriceEntity("900_ألوان_EGP", "900", "ألوان", 375000, 1875, "EGP"),
                PosterPriceEntity("900_فضي_EGP", "900", "فضي", 390000, 1950, "EGP"),
                PosterPriceEntity("900_ذهبي_EGP", "900", "ذهبي", 400000, 2000, "EGP"),
                PosterPriceEntity("900_ورنيش_EGP", "900", "ورنيش", 375000, 1875, "EGP")
            )
            dao.insertPosterPrices(posterList)

            // Seed Initial Sample Invoices
            val invId = dao.insertInvoice(
                InvoiceEntity(
                    invoiceNo = "INV-000001",
                    customerId = c1,
                    customerName = "شركة النيل للمقاولات والديكور",
                    invoiceDate = "2026-10-24",
                    currency = "SDG",
                    subtotal = 5890000.0,
                    discount = 190000.0,
                    total = 5700000.0,
                    notes = "دفعة أولى عند الاستلام - تسليم مخزن الخرطوم"
                )
            )
            dao.insertInvoiceItems(
                listOf(
                    InvoiceItemEntity(
                        invoiceId = invId,
                        description = "بوهية أبيض 900 جم (10 كرتونة)",
                        quantity = 10.0,
                        unitPrice = 385000.0,
                        lineTotal = 3850000.0,
                        productId = 1L,
                        warehouseId = 1L
                    ),
                    InvoiceItemEntity(
                        invoiceId = invId,
                        description = "بوهية ذهبي 250 جم (4 كرتونة)",
                        quantity = 4.0,
                        unitPrice = 510000.0,
                        lineTotal = 2040000.0,
                        productId = 4L,
                        warehouseId = 1L
                    )
                )
            )
            dao.insertInvoicePayment(
                InvoicePaymentEntity(
                    invoiceId = invId,
                    payDate = "2026-10-24",
                    cashbox = "خزنة السودان",
                    amount = 3500000.0,
                    note = "سداد نقدي دفعة أولى"
                )
            )

            val invId2 = dao.insertInvoice(
                InvoiceEntity(
                    invoiceNo = "INV-000002",
                    customerId = c2,
                    customerName = "مؤسسة الأهرام لمواد البناء",
                    invoiceDate = "2026-10-25",
                    currency = "EGP",
                    subtotal = 125000.0,
                    discount = 5000.0,
                    total = 120000.0,
                    notes = "توريد فرع القاهرة - مسدد بالكامل"
                )
            )
            dao.insertInvoiceItems(
                listOf(
                    InvoiceItemEntity(
                        invoiceId = invId2,
                        description = "بوهية أبيض ناصع 250 جم (15 كرتونة)",
                        quantity = 15.0,
                        unitPrice = 5000.0,
                        lineTotal = 75000.0,
                        productId = 1L,
                        warehouseId = 1L
                    ),
                    InvoiceItemEntity(
                        invoiceId = invId2,
                        description = "ورنيش لامع ممتاز 900 جم (10 كرتونة)",
                        quantity = 10.0,
                        unitPrice = 5000.0,
                        lineTotal = 50000.0,
                        productId = 5L,
                        warehouseId = 1L
                    )
                )
            )
            dao.insertInvoicePayment(
                InvoicePaymentEntity(
                    invoiceId = invId2,
                    payDate = "2026-10-25",
                    cashbox = "خزنة مصر",
                    amount = 120000.0,
                    note = "سداد كامل نقدي بخزنة مصر"
                )
            )

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
