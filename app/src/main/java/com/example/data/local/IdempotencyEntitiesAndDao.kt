package com.example.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Unified Room Entity for the Idempotency Protection System & Operation Tracking Log.
 * Prevents duplicate operations across network interruptions, multi-taps, app restarts,
 * and cross-device retries.
 */
@Entity(
    tableName = "idempotency_requests",
    indices = [
        Index(name = "idx_idemp_key", value = ["idempotencyKey"], unique = true),
        Index(name = "idx_idemp_status", value = ["requestStatus"])
    ]
)
data class IdempotencyRequestEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val idempotencyKey: String,
    val userId: String,
    val operationType: String,
    val documentReference: String,
    val requestStatus: String, // DRAFT, PROCESSING, PENDING_CONFIRMATION, COMPLETED, FAILED, CANCELLED
    val createdAt: Long,
    val updatedAt: Long,
    val completionTime: Long? = null,
    val resultReference: String = "",
    val errorDetails: String = "",
    val targetCollection: String = "",
    val payloadSummary: String = "",
    val platform: String = "ANDROID",
    val guardKey: String = ""
)

@Dao
interface IdempotencyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: IdempotencyRequestEntity): Long

    @Query("SELECT * FROM idempotency_requests ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<IdempotencyRequestEntity>>

    @Query("SELECT * FROM idempotency_requests ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    suspend fun getRecentDirect(limit: Int = 200): List<IdempotencyRequestEntity>

    @Query("SELECT * FROM idempotency_requests WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun getByKey(idempotencyKey: String): IdempotencyRequestEntity?

    @Query(
        "SELECT * FROM idempotency_requests " +
            "WHERE requestStatus IN ('PROCESSING', 'PENDING_CONFIRMATION') " +
            "ORDER BY createdAt DESC"
    )
    suspend fun getUnresolvedDirect(): List<IdempotencyRequestEntity>

    @Query(
        "SELECT * FROM idempotency_requests " +
            "WHERE documentReference = :documentReference " +
            "AND requestStatus IN ('PROCESSING', 'PENDING_CONFIRMATION') " +
            "LIMIT 1"
    )
    suspend fun getActiveByDocumentReference(documentReference: String): IdempotencyRequestEntity?

    @Query(
        "UPDATE idempotency_requests SET " +
            "requestStatus = :status, " +
            "updatedAt = :updatedAt, " +
            "completionTime = :completionTime, " +
            "resultReference = :resultReference, " +
            "errorDetails = :errorDetails " +
            "WHERE idempotencyKey = :idempotencyKey"
    )
    suspend fun updateStatus(
        idempotencyKey: String,
        status: String,
        updatedAt: Long,
        completionTime: Long?,
        resultReference: String,
        errorDetails: String
    )
}
