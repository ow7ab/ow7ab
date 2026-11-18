package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LabDao {

    // --- Formula Versions (Immutable Audit & History) ---
    @Query("SELECT * FROM lab_formula_versions ORDER BY formulaId DESC, versionNumber DESC")
    fun getAllFormulaVersions(): Flow<List<FormulationVersionEntity>>

    @Query("SELECT * FROM lab_formula_versions WHERE formulaId = :formulaId ORDER BY versionNumber DESC")
    suspend fun getVersionsForFormulaDirect(formulaId: Long): List<FormulationVersionEntity>

    @Query("SELECT * FROM lab_formula_versions")
    suspend fun getAllFormulaVersionsDirect(): List<FormulationVersionEntity>

    @Query("SELECT * FROM lab_formula_versions WHERE id = :id LIMIT 1")
    suspend fun getFormulaVersionById(id: Long): FormulationVersionEntity?

    @Query("SELECT * FROM lab_formula_versions WHERE syncUuid = :syncUuid LIMIT 1")
    suspend fun getFormulaVersionBySyncUuid(syncUuid: String): FormulationVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFormulaVersion(version: FormulationVersionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFormulaVersions(versions: List<FormulationVersionEntity>)

    // --- Lab Experiments & Sample Testing ---
    @Query("SELECT * FROM lab_experiments ORDER BY createdAt DESC, id DESC")
    fun getAllLabExperiments(): Flow<List<LabExperimentEntity>>

    @Query("SELECT * FROM lab_experiments WHERE formulaId = :formulaId ORDER BY createdAt DESC, id DESC")
    suspend fun getExperimentsForFormulaDirect(formulaId: Long): List<LabExperimentEntity>

    @Query("SELECT * FROM lab_experiments")
    suspend fun getAllLabExperimentsDirect(): List<LabExperimentEntity>

    @Query("SELECT * FROM lab_experiments WHERE idempotencyKey != '' AND idempotencyKey = :key LIMIT 1")
    suspend fun getLabExperimentByIdempotencyKey(key: String): LabExperimentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLabExperiment(experiment: LabExperimentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLabExperiments(experiments: List<LabExperimentEntity>)

    // --- Unified Raw Material Price History ---
    @Query("SELECT * FROM raw_material_price_history ORDER BY createdAt DESC, id DESC")
    fun getAllRawMaterialPriceHistory(): Flow<List<RawMaterialPriceHistoryEntity>>

    @Query("SELECT * FROM raw_material_price_history WHERE materialId = :materialId ORDER BY createdAt DESC, id DESC")
    suspend fun getPriceHistoryForMaterialDirect(materialId: Long): List<RawMaterialPriceHistoryEntity>

    @Query("SELECT * FROM raw_material_price_history")
    suspend fun getAllRawMaterialPriceHistoryDirect(): List<RawMaterialPriceHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPriceHistory(entry: RawMaterialPriceHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPriceHistories(entries: List<RawMaterialPriceHistoryEntity>)

    // --- Factory Item Unified Update ---
    @Update
    suspend fun updateFactoryItem(item: FactoryItemEntity)

    // --- Lab Excel Imports ---
    @Query("SELECT * FROM lab_excel_imports ORDER BY createdAt DESC, id DESC")
    fun getAllExcelImports(): Flow<List<LabExcelImportBatchEntity>>

    @Query("SELECT * FROM lab_excel_imports")
    suspend fun getAllExcelImportsDirect(): List<LabExcelImportBatchEntity>

    @Query("SELECT * FROM lab_excel_imports WHERE idempotencyKey != '' AND idempotencyKey = :key LIMIT 1")
    suspend fun getExcelImportByIdempotencyKey(key: String): LabExcelImportBatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExcelImport(batch: LabExcelImportBatchEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExcelImports(batches: List<LabExcelImportBatchEntity>)
}
