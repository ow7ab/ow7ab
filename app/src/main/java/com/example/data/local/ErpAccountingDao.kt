package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Append-Only General Ledger DAO for Phase 4 Double-Entry Accounting.
 * No DELETE statements exist on posted journal entries or journal lines;
 * corrections strictly append compensating reversing journal entries.
 */
@Dao
interface ErpAccountingDao {

    // --- Chart of Accounts ---
    @Query("SELECT * FROM accounting_accounts ORDER BY accountCode ASC")
    fun getAllAccounts(): Flow<List<ChartAccountEntity>>

    @Query("SELECT * FROM accounting_accounts ORDER BY accountCode ASC")
    suspend fun getAllAccountsDirect(): List<ChartAccountEntity>

    @Query("SELECT * FROM accounting_accounts WHERE accountCode = :code LIMIT 1")
    suspend fun getAccountByCode(code: String): ChartAccountEntity?

    @Query("SELECT COUNT(*) FROM accounting_accounts")
    suspend fun getAccountCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: ChartAccountEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccounts(accounts: List<ChartAccountEntity>)

    // --- Journal Entries (Append-Only Ledger) ---
    @Query("SELECT * FROM journal_entries ORDER BY createdAt DESC, id DESC")
    fun getAllJournalEntries(): Flow<List<JournalEntryEntity>>

    @Query("SELECT * FROM journal_entries ORDER BY createdAt DESC, id DESC")
    suspend fun getAllJournalEntriesDirect(): List<JournalEntryEntity>

    @Query("SELECT * FROM journal_entries WHERE id = :id LIMIT 1")
    suspend fun getJournalEntryById(id: Long): JournalEntryEntity?

    @Query("SELECT * FROM journal_entries WHERE entryNo = :entryNo LIMIT 1")
    suspend fun getJournalEntryByNo(entryNo: String): JournalEntryEntity?

    @Query("SELECT * FROM journal_entries WHERE syncUuid = :syncUuid LIMIT 1")
    suspend fun getJournalEntryByUuid(syncUuid: String): JournalEntryEntity?

    @Query("SELECT * FROM journal_entries WHERE idempotencyKey = :key AND idempotencyKey != '' LIMIT 1")
    suspend fun getJournalEntryByIdempotencyKey(key: String): JournalEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJournalEntry(entry: JournalEntryEntity): Long

    @Query(
        "UPDATE journal_entries SET status = :status, reversedByEntryNo = :reversedByEntryNo, " +
            "reversalReason = :reason, updatedAt = :updatedAt, recordVersion = :newVersion WHERE id = :id"
    )
    suspend fun markJournalEntryReversed(
        id: Long,
        status: String,
        reversedByEntryNo: String,
        reason: String,
        updatedAt: Long,
        newVersion: Int
    )

    // --- Journal Lines ---
    @Query("SELECT * FROM journal_lines ORDER BY journalEntryId DESC, lineOrder ASC, id ASC")
    fun getAllJournalLines(): Flow<List<JournalLineEntity>>

    @Query("SELECT * FROM journal_lines ORDER BY journalEntryId DESC, lineOrder ASC, id ASC")
    suspend fun getAllJournalLinesDirect(): List<JournalLineEntity>

    @Query("SELECT * FROM journal_lines WHERE journalEntryId = :entryId ORDER BY lineOrder ASC, id ASC")
    suspend fun getLinesForEntryDirect(entryId: Long): List<JournalLineEntity>

    @Query("SELECT * FROM journal_lines WHERE journalEntryUuid = :entryUuid ORDER BY lineOrder ASC, id ASC")
    suspend fun getLinesForEntryUuidDirect(entryUuid: String): List<JournalLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJournalLines(lines: List<JournalLineEntity>)
}
