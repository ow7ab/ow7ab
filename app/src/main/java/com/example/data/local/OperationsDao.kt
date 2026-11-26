package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OperationsDao {

    // --- Shift Production Logs ---
    @Query("SELECT * FROM shift_production_logs ORDER BY shiftDate DESC, updatedAt DESC")
    fun getShiftLogs(): Flow<List<ShiftProductionLogEntity>>

    @Query("SELECT * FROM shift_production_logs ORDER BY shiftDate DESC, updatedAt DESC")
    suspend fun getShiftLogsDirect(): List<ShiftProductionLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShiftLog(log: ShiftProductionLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShiftLogs(logs: List<ShiftProductionLogEntity>)

    // --- Press Machines ---
    @Query("SELECT * FROM press_machines ORDER BY machineCode ASC")
    fun getPressMachines(): Flow<List<PressMachineEntity>>

    @Query("SELECT * FROM press_machines ORDER BY machineCode ASC")
    suspend fun getPressMachinesDirect(): List<PressMachineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPressMachine(item: PressMachineEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPressMachines(items: List<PressMachineEntity>)

    // --- Tool Custodies ---
    @Query("SELECT * FROM tool_custodies ORDER BY updatedAt DESC")
    fun getToolCustodies(): Flow<List<ToolCustodyEntity>>

    @Query("SELECT * FROM tool_custodies ORDER BY updatedAt DESC")
    suspend fun getToolCustodiesDirect(): List<ToolCustodyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertToolCustody(item: ToolCustodyEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertToolCustodies(items: List<ToolCustodyEntity>)

    // --- Daily Checklists ---
    @Query("SELECT * FROM daily_checklists ORDER BY dateKey DESC, updatedAt DESC")
    fun getDailyChecklists(): Flow<List<DailyChecklistEntity>>

    @Query("SELECT * FROM daily_checklists ORDER BY dateKey DESC, updatedAt DESC")
    suspend fun getDailyChecklistsDirect(): List<DailyChecklistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyChecklist(item: DailyChecklistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyChecklists(items: List<DailyChecklistEntity>)

    // --- Employee Tasks ---
    @Query("SELECT * FROM employee_tasks ORDER BY dueDate ASC, updatedAt DESC")
    fun getEmployeeTasks(): Flow<List<EmployeeTaskEntity>>

    @Query("SELECT * FROM employee_tasks ORDER BY dueDate ASC, updatedAt DESC")
    suspend fun getEmployeeTasksDirect(): List<EmployeeTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmployeeTask(item: EmployeeTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmployeeTasks(items: List<EmployeeTaskEntity>)

    // --- Seqaofa Entries ---
    @Query("SELECT * FROM seqaofa_entries ORDER BY entryDate DESC, updatedAt DESC")
    fun getSeqaofaEntries(): Flow<List<SeqaofaEntryEntity>>

    @Query("SELECT * FROM seqaofa_entries ORDER BY entryDate DESC, updatedAt DESC")
    suspend fun getSeqaofaEntriesDirect(): List<SeqaofaEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeqaofaEntry(item: SeqaofaEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeqaofaEntries(items: List<SeqaofaEntryEntity>)

    // --- In-Transit Shipments ---
    @Query("SELECT * FROM in_transit_shipments ORDER BY dispatchDate DESC, updatedAt DESC")
    fun getInTransitShipments(): Flow<List<InTransitShipmentEntity>>

    @Query("SELECT * FROM in_transit_shipments ORDER BY dispatchDate DESC, updatedAt DESC")
    suspend fun getInTransitShipmentsDirect(): List<InTransitShipmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInTransitShipment(item: InTransitShipmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInTransitShipments(items: List<InTransitShipmentEntity>)

    // --- Financial Budgets ---
    @Query("SELECT * FROM financial_budgets ORDER BY periodMonth DESC, updatedAt DESC")
    fun getFinancialBudgets(): Flow<List<FinancialBudgetEntity>>

    @Query("SELECT * FROM financial_budgets ORDER BY periodMonth DESC, updatedAt DESC")
    suspend fun getFinancialBudgetsDirect(): List<FinancialBudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFinancialBudget(item: FinancialBudgetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFinancialBudgets(items: List<FinancialBudgetEntity>)

    // --- Employee Rewards ---
    @Query("SELECT * FROM employee_rewards ORDER BY updatedAt DESC")
    fun getEmployeeRewards(): Flow<List<EmployeeRewardEntity>>

    @Query("SELECT * FROM employee_rewards ORDER BY updatedAt DESC")
    suspend fun getEmployeeRewardsDirect(): List<EmployeeRewardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmployeeReward(item: EmployeeRewardEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmployeeRewards(items: List<EmployeeRewardEntity>)
}
