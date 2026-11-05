package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PmpDao {

    // --- Cashboxes & Movements ---
    @Query("SELECT * FROM cashboxes ORDER BY id ASC")
    fun getAllCashboxes(): Flow<List<CashboxEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCashbox(cashbox: CashboxEntity): Long

    @Update
    suspend fun updateCashbox(cashbox: CashboxEntity)

    @Query("DELETE FROM cashboxes WHERE id = :id")
    suspend fun deleteCashbox(id: Long)

    @Query("SELECT * FROM movements ORDER BY id DESC")
    fun getAllMovements(): Flow<List<MovementEntity>>

    @Query("SELECT * FROM movements WHERE cashbox = :cashbox ORDER BY id DESC")
    fun getMovementsByCashbox(cashbox: String): Flow<List<MovementEntity>>

    @Insert
    suspend fun insertMovement(movement: MovementEntity): Long

    @Query("DELETE FROM movements WHERE cashbox = :cashbox")
    suspend fun clearMovementsByCashbox(cashbox: String)

    @Query("DELETE FROM movements WHERE id = :id")
    suspend fun deleteMovementById(id: Long)

    // --- Transfers ---
    @Query("SELECT * FROM transfers ORDER BY id DESC")
    fun getAllTransfers(): Flow<List<TransferEntity>>

    @Insert
    suspend fun insertTransfer(transfer: TransferEntity): Long

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun deleteTransferById(id: Long)

    // --- Daily Closings ---
    @Query("SELECT * FROM daily_closings ORDER BY id DESC")
    fun getAllDailyClosings(): Flow<List<DailyClosingEntity>>

    @Insert
    suspend fun insertDailyClosing(closing: DailyClosingEntity): Long

    // --- Customers ---
    @Query("SELECT * FROM customers ORDER BY name ASC")
    fun getAllCustomers(): Flow<List<CustomerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: CustomerEntity): Long

    @Update
    suspend fun updateCustomer(customer: CustomerEntity)

    @Query("DELETE FROM customers WHERE id = :id")
    suspend fun deleteCustomer(id: Long)

    @Query("DELETE FROM invoice_payments WHERE invoiceId IN (SELECT id FROM invoices WHERE customerId = :customerId)")
    suspend fun deleteCustomerPayments(customerId: Long)

    @Query("DELETE FROM invoice_items WHERE invoiceId IN (SELECT id FROM invoices WHERE customerId = :customerId)")
    suspend fun deleteCustomerInvoiceItems(customerId: Long)

    @Query("DELETE FROM invoices WHERE customerId = :customerId")
    suspend fun deleteCustomerInvoices(customerId: Long)

    // --- Invoices & Items ---
    @Query("SELECT * FROM invoices ORDER BY id DESC")
    fun getAllInvoices(): Flow<List<InvoiceEntity>>

    @Query("SELECT * FROM invoices WHERE customerId = :customerId ORDER BY id DESC")
    fun getInvoicesByCustomer(customerId: Long): Flow<List<InvoiceEntity>>

    @Query("SELECT * FROM invoices WHERE id = :invoiceId LIMIT 1")
    suspend fun getInvoiceById(invoiceId: Long): InvoiceEntity?

    @Insert
    suspend fun insertInvoice(invoice: InvoiceEntity): Long

    @Update
    suspend fun updateInvoice(invoice: InvoiceEntity)

    @Query("DELETE FROM invoices WHERE id = :id")
    suspend fun deleteInvoiceById(id: Long)

    @Query("SELECT * FROM invoice_items ORDER BY id ASC")
    fun getAllInvoiceItems(): Flow<List<InvoiceItemEntity>>

    @Query("SELECT * FROM invoice_items WHERE invoiceId = :invoiceId")
    fun getInvoiceItems(invoiceId: Long): Flow<List<InvoiceItemEntity>>

    @Insert
    suspend fun insertInvoiceItems(items: List<InvoiceItemEntity>)

    // --- Payments ---
    @Query("SELECT * FROM invoice_payments ORDER BY id DESC")
    fun getAllInvoicePayments(): Flow<List<InvoicePaymentEntity>>

    @Query("SELECT * FROM invoice_payments WHERE invoiceId = :invoiceId")
    fun getInvoicePayments(invoiceId: Long): Flow<List<InvoicePaymentEntity>>

    @Insert
    suspend fun insertInvoicePayment(payment: InvoicePaymentEntity): Long

    @Update
    suspend fun updateInvoicePayment(payment: InvoicePaymentEntity)

    // --- Warehouses & Products ---
    @Query("SELECT * FROM warehouses ORDER BY name ASC")
    fun getAllWarehouses(): Flow<List<WarehouseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWarehouse(warehouse: WarehouseEntity): Long

    @Update
    suspend fun updateWarehouse(warehouse: WarehouseEntity)

    @Query("DELETE FROM warehouses WHERE id = :id")
    suspend fun deleteWarehouse(id: Long)

    @Query("SELECT * FROM products ORDER BY name ASC")
    fun getAllProducts(): Flow<List<ProductEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: ProductEntity): Long

    @Update
    suspend fun updateProduct(product: ProductEntity)

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun deleteProduct(id: Long)

    @Query("SELECT * FROM stock_moves ORDER BY id DESC")
    fun getAllStockMoves(): Flow<List<StockMoveEntity>>

    @Insert
    suspend fun insertStockMove(stockMove: StockMoveEntity): Long

    @Query("DELETE FROM stock_moves WHERE warehouseId = :warehouseId")
    suspend fun clearStockMovesByWarehouse(warehouseId: Long)

    // --- Factory ---
    @Query("SELECT * FROM factory_items ORDER BY name ASC")
    fun getAllFactoryItems(): Flow<List<FactoryItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFactoryItem(item: FactoryItemEntity): Long

    @Query("DELETE FROM factory_items WHERE id = :id")
    suspend fun deleteFactoryItemById(id: Long)

    @Delete
    suspend fun deleteFactoryItem(item: FactoryItemEntity)

    @Query("SELECT * FROM factory_machines ORDER BY name ASC")
    fun getAllFactoryMachines(): Flow<List<FactoryMachineEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFactoryMachine(machine: FactoryMachineEntity): Long

    @Query("SELECT * FROM factory_moves ORDER BY id DESC")
    fun getAllFactoryMoves(): Flow<List<FactoryMoveEntity>>

    @Insert
    suspend fun insertFactoryMove(move: FactoryMoveEntity): Long

    @Query("SELECT * FROM halfa_invoices ORDER BY id DESC")
    fun getAllHalfaInvoices(): Flow<List<HalfaInvoiceEntity>>

    @Insert
    suspend fun insertHalfaInvoice(invoice: HalfaInvoiceEntity): Long

    // --- Poster Prices ---
    @Query("SELECT * FROM poster_prices")
    fun getAllPosterPrices(): Flow<List<PosterPriceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPosterPrices(prices: List<PosterPriceEntity>)

    // --- Audit Log ---
    @Query("SELECT * FROM audit_log ORDER BY id DESC LIMIT 500")
    fun getAuditLogs(): Flow<List<AuditLogEntity>>

    @Insert
    suspend fun insertAuditLog(log: AuditLogEntity): Long

    // --- User Accounts & Server Sync ---
    @Query("SELECT * FROM user_accounts ORDER BY id ASC")
    fun getAllUserAccounts(): Flow<List<UserAccountEntity>>

    @Query("SELECT COUNT(*) FROM user_accounts")
    suspend fun getUserAccountCount(): Int

    @Query("SELECT * FROM user_accounts WHERE username = :username LIMIT 1")
    suspend fun getUserByUsername(username: String): UserAccountEntity?

    @Query("SELECT * FROM user_accounts WHERE id = :id LIMIT 1")
    suspend fun getUserById(id: Long): UserAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserAccount(user: UserAccountEntity): Long

    @Update
    suspend fun updateUserAccount(user: UserAccountEntity)

    @Query("DELETE FROM user_accounts WHERE id = :id")
    suspend fun deleteUserAccount(id: Long)

    @Query("DELETE FROM user_accounts WHERE userCode = :userCode")
    suspend fun deleteUserAccountByCode(userCode: String)

    @Query("DELETE FROM user_accounts WHERE LOWER(username) = LOWER(:username)")
    suspend fun deleteUserAccountByUsername(username: String)

    @Query("UPDATE user_accounts SET isCurrentSession = CASE WHEN id = :activeUserId THEN 1 ELSE 0 END")
    suspend fun setActiveSessionUser(activeUserId: Long)

    @Query("UPDATE user_accounts SET serverEndpoint = :endpoint, syncStatus = :status")
    suspend fun updateAllUsersServerConfig(endpoint: String, status: String)

    // --- Strict Deduplication Queries to prevent duplicate cashboxes, transactions and accounts ---
    @Query("DELETE FROM cashboxes WHERE id NOT IN (SELECT MIN(id) FROM cashboxes GROUP BY TRIM(LOWER(name)))")
    suspend fun removeDuplicateCashboxes()

    @Query("SELECT COUNT(*) FROM movements")
    suspend fun removeDuplicateMovements(): Int

    @Query("DELETE FROM user_accounts WHERE id NOT IN (SELECT MIN(id) FROM user_accounts GROUP BY TRIM(LOWER(username))) AND isCurrentSession = 0")
    suspend fun removeDuplicateUserAccounts()

    // --- Bulk Direct Operations for Cloud Server Sync & Safe Backup ---
    @Query("SELECT * FROM invoices")
    suspend fun getInvoicesDirect(): List<InvoiceEntity>

    @Query("SELECT * FROM invoice_items")
    suspend fun getInvoiceItemsDirect(): List<InvoiceItemEntity>

    @Query("SELECT * FROM invoice_payments")
    suspend fun getInvoicePaymentsDirect(): List<InvoicePaymentEntity>

    @Query("SELECT * FROM movements")
    suspend fun getMovementsDirect(): List<MovementEntity>

    @Query("SELECT * FROM customers")
    suspend fun getCustomersDirect(): List<CustomerEntity>

    @Query("SELECT * FROM products")
    suspend fun getProductsDirect(): List<ProductEntity>

    @Query("SELECT * FROM cashboxes")
    suspend fun getCashboxesDirect(): List<CashboxEntity>

    @Query("SELECT * FROM transfers")
    suspend fun getTransfersDirect(): List<TransferEntity>

    @Query("SELECT * FROM warehouses")
    suspend fun getWarehousesDirect(): List<WarehouseEntity>

    @Query("SELECT * FROM user_accounts")
    suspend fun getUserAccountsDirect(): List<UserAccountEntity>

    @Query("SELECT * FROM factory_items")
    suspend fun getFactoryItemsDirect(): List<FactoryItemEntity>

    @Query("SELECT * FROM factory_moves")
    suspend fun getFactoryMovesDirect(): List<FactoryMoveEntity>

    @Query("SELECT * FROM stock_moves")
    suspend fun getStockMovesDirect(): List<StockMoveEntity>

    @Query("SELECT * FROM halfa_invoices")
    suspend fun getHalfaInvoicesDirect(): List<HalfaInvoiceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInvoices(invoices: List<InvoiceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInvoiceItems(items: List<InvoiceItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInvoicePayments(payments: List<InvoicePaymentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMovements(movements: List<MovementEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomers(customers: List<CustomerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProducts(products: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCashboxes(cashboxes: List<CashboxEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransfers(transfers: List<TransferEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWarehouses(warehouses: List<WarehouseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserAccounts(accounts: List<UserAccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFactoryItems(items: List<FactoryItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFactoryMoves(moves: List<FactoryMoveEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStockMoves(moves: List<StockMoveEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHalfaInvoices(invoices: List<HalfaInvoiceEntity>)

    // --- PMP Science & Chemistry Lab (Formulations & Safety) ---
    @Query("SELECT * FROM lab_formulations ORDER BY id DESC")
    fun getAllFormulations(): Flow<List<FormulationEntity>>

    @Query("SELECT * FROM lab_formulations WHERE id = :id LIMIT 1")
    suspend fun getFormulationById(id: Long): FormulationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFormulation(formulation: FormulationEntity): Long

    @Update
    suspend fun updateFormulation(formulation: FormulationEntity)

    @Query("DELETE FROM lab_formulations WHERE id = :id")
    suspend fun deleteFormulation(id: Long)

    @Query("SELECT * FROM lab_formulations")
    suspend fun getFormulationsDirect(): List<FormulationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFormulations(list: List<FormulationEntity>)

    @Query("SELECT * FROM chemical_safety ORDER BY chemicalName ASC")
    fun getAllChemicalSafety(): Flow<List<ChemicalSafetyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChemicalSafety(item: ChemicalSafetyEntity): Long

    @Update
    suspend fun updateChemicalSafety(item: ChemicalSafetyEntity)

    @Query("DELETE FROM chemical_safety WHERE id = :id")
    suspend fun deleteChemicalSafety(id: Long)

    @Query("SELECT * FROM chemical_safety")
    suspend fun getChemicalSafetyDirect(): List<ChemicalSafetyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChemicalSafety(list: List<ChemicalSafetyEntity>)

    // --- Daily Production Batches (Firestore & Local) ---
    @Query("SELECT * FROM daily_production_batches ORDER BY id DESC")
    fun getAllProductionBatches(): Flow<List<ProductionBatchEntity>>

    @Query("SELECT * FROM daily_production_batches WHERE id = :id LIMIT 1")
    suspend fun getProductionBatchById(id: Long): ProductionBatchEntity?

    @Query("SELECT * FROM daily_production_batches WHERE batchNumber = :batchNumber LIMIT 1")
    suspend fun getProductionBatchByNumber(batchNumber: String): ProductionBatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductionBatch(batch: ProductionBatchEntity): Long

    @Update
    suspend fun updateProductionBatch(batch: ProductionBatchEntity)

    @Query("DELETE FROM daily_production_batches WHERE id = :id")
    suspend fun deleteProductionBatch(id: Long)

    @Query("SELECT * FROM daily_production_batches")
    suspend fun getProductionBatchesDirect(): List<ProductionBatchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProductionBatches(batches: List<ProductionBatchEntity>)

    // --- Safe Table Clearing Queries for Server Reset ---
    @Query("DELETE FROM movements")
    suspend fun clearAllMovements()

    @Query("DELETE FROM transfers")
    suspend fun clearAllTransfers()

    @Query("DELETE FROM daily_closings")
    suspend fun clearAllDailyClosings()

    @Query("DELETE FROM invoices")
    suspend fun clearAllInvoices()

    @Query("DELETE FROM invoice_items")
    suspend fun clearAllInvoiceItems()

    @Query("DELETE FROM invoice_payments")
    suspend fun clearAllInvoicePayments()

    @Query("DELETE FROM stock_moves")
    suspend fun clearAllStockMoves()

    @Query("DELETE FROM daily_production_batches")
    suspend fun clearAllProductionBatches()

    @Query("DELETE FROM audit_log")
    suspend fun clearAllAuditLogs()
}


