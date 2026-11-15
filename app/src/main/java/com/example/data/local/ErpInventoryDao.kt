package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ErpInventoryDao {

    // --- Inventory Movements (Append-Only Audit Ledger; NO DELETE QUERIES) ---

    @Query("SELECT * FROM inventory_movements ORDER BY createdAt DESC, id DESC")
    fun getAllInventoryMovements(): Flow<List<InventoryMovementEntity>>

    @Query("SELECT * FROM inventory_movements ORDER BY id ASC")
    suspend fun getInventoryMovementsDirect(): List<InventoryMovementEntity>

    @Query("SELECT * FROM inventory_movements WHERE warehouseId = :warehouseId ORDER BY createdAt DESC, id DESC")
    fun getInventoryMovementsByWarehouse(warehouseId: Long): Flow<List<InventoryMovementEntity>>

    @Query("SELECT * FROM inventory_movements WHERE id = :id LIMIT 1")
    suspend fun getInventoryMovementById(id: Long): InventoryMovementEntity?

    @Query("SELECT * FROM inventory_movements WHERE syncUuid = :syncUuid LIMIT 1")
    suspend fun getInventoryMovementBySyncUuid(syncUuid: String): InventoryMovementEntity?

    @Query("SELECT * FROM inventory_movements WHERE idempotencyKey != '' AND idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun getInventoryMovementByIdempotencyKey(idempotencyKey: String): InventoryMovementEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInventoryMovement(movement: InventoryMovementEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertInventoryMovementsFromSync(movements: List<InventoryMovementEntity>)

    /**
     * يُستخدم فقط لتحديث الحالة إلى REVERSED عند إنشاء قيد عكسي تعويضي — لا يحذف السجل أبداً
     */
    @Query("UPDATE inventory_movements SET status = :status, recordVersion = recordVersion + 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markInventoryMovementStatus(id: Long, status: String, updatedAt: Long)

    // --- Warehouse Stock Balances ---

    @Query("SELECT * FROM warehouse_stock_balances ORDER BY warehouseId ASC, itemName ASC")
    fun getAllStockBalances(): Flow<List<WarehouseStockBalanceEntity>>

    @Query("SELECT * FROM warehouse_stock_balances WHERE warehouseId = :warehouseId ORDER BY itemName ASC")
    fun getStockBalancesByWarehouse(warehouseId: Long): Flow<List<WarehouseStockBalanceEntity>>

    @Query("SELECT * FROM warehouse_stock_balances")
    suspend fun getStockBalancesDirect(): List<WarehouseStockBalanceEntity>

    @Query(
        """
        SELECT * FROM warehouse_stock_balances
        WHERE warehouseId = :warehouseId
          AND itemCategory = :itemCategory
          AND itemId = :itemId
          AND batchNumber = :batchNumber
        LIMIT 1
        """
    )
    suspend fun getStockBalanceRow(
        warehouseId: Long,
        itemCategory: String,
        itemId: Long,
        batchNumber: String
    ): WarehouseStockBalanceEntity?

    @Query(
        """
        SELECT COALESCE(SUM(availableQty), 0.0)
        FROM warehouse_stock_balances
        WHERE warehouseId = :warehouseId
          AND itemCategory = :itemCategory
          AND itemId = :itemId
        """
    )
    suspend fun getTotalAvailableQty(warehouseId: Long, itemCategory: String, itemId: Long): Double

    @Query(
        """
        SELECT COALESCE(SUM(onHandQty), 0.0)
        FROM warehouse_stock_balances
        WHERE warehouseId = :warehouseId
          AND itemCategory = :itemCategory
          AND itemId = :itemId
        """
    )
    suspend fun getTotalOnHandQty(warehouseId: Long, itemCategory: String, itemId: Long): Double

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStockBalance(balance: WarehouseStockBalanceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStockBalances(balances: List<WarehouseStockBalanceEntity>)

    // --- Warehouse Transfers ---

    @Query("SELECT * FROM warehouse_transfers ORDER BY createdAt DESC, id DESC")
    fun getAllWarehouseTransfers(): Flow<List<WarehouseTransferEntity>>

    @Query("SELECT * FROM warehouse_transfers ORDER BY id DESC")
    suspend fun getWarehouseTransfersDirect(): List<WarehouseTransferEntity>

    @Query("SELECT * FROM warehouse_transfers WHERE id = :id LIMIT 1")
    suspend fun getWarehouseTransferById(id: Long): WarehouseTransferEntity?

    @Query("SELECT * FROM warehouse_transfers WHERE transferNo = :transferNo LIMIT 1")
    suspend fun getWarehouseTransferByNo(transferNo: String): WarehouseTransferEntity?

    @Query("SELECT * FROM warehouse_transfers WHERE idempotencyKey != '' AND idempotencyKey = :key LIMIT 1")
    suspend fun getWarehouseTransferByIdempotencyKey(key: String): WarehouseTransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateWarehouseTransfer(transfer: WarehouseTransferEntity): Long

    @Update
    suspend fun updateWarehouseTransfer(transfer: WarehouseTransferEntity)

    @Query("SELECT * FROM warehouse_transfer_items WHERE transferId = :transferId ORDER BY id ASC")
    suspend fun getTransferItemsByTransferId(transferId: Long): List<WarehouseTransferItemEntity>

    @Query("SELECT * FROM warehouse_transfer_items")
    suspend fun getAllTransferItemsDirect(): List<WarehouseTransferItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransferItems(items: List<WarehouseTransferItemEntity>)

    @Update
    suspend fun updateTransferItem(item: WarehouseTransferItemEntity)

    // --- Packaging Rules ---

    @Query("SELECT * FROM packaging_rules WHERE active = 1 ORDER BY weightGrams ASC")
    fun getAllActivePackagingRules(): Flow<List<PackagingRuleEntity>>

    @Query("SELECT * FROM packaging_rules ORDER BY weightGrams ASC")
    suspend fun getPackagingRulesDirect(): List<PackagingRuleEntity>

    @Query("SELECT * FROM packaging_rules WHERE weightGrams = :weightGrams LIMIT 1")
    suspend fun getPackagingRuleByWeight(weightGrams: Int): PackagingRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPackagingRule(rule: PackagingRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPackagingRules(rules: List<PackagingRuleEntity>)

    // --- Sync Outbox ---

    @Query("SELECT * FROM sync_outbox WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingSyncOutbox(): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox ORDER BY createdAt DESC LIMIT 200")
    fun observeSyncOutbox(): Flow<List<SyncOutboxEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueOutbox(entry: SyncOutboxEntity): Long

    @Query("UPDATE sync_outbox SET status = :status, retryCount = retryCount + 1, lastError = :lastError, updatedAt = :updatedAt WHERE operationId = :operationId")
    suspend fun updateOutboxStatus(operationId: String, status: String, lastError: String, updatedAt: Long)

    // --- Warehouse & Catalog Lookup Helpers ---

    @Query("SELECT * FROM warehouses WHERE id = :warehouseId LIMIT 1")
    suspend fun getWarehouseById(warehouseId: Long): WarehouseEntity?

    @Query("SELECT * FROM warehouses WHERE warehouseClass = :warehouseClass AND active = 1 ORDER BY id ASC")
    fun getWarehousesByClass(warehouseClass: String): Flow<List<WarehouseEntity>>

    @Query("SELECT * FROM products WHERE id = :productId LIMIT 1")
    suspend fun getProductById(productId: Long): ProductEntity?

    @Query("SELECT * FROM factory_items WHERE id = :itemId LIMIT 1")
    suspend fun getFactoryItemById(itemId: Long): FactoryItemEntity?
}
