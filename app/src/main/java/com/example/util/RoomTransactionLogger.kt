package com.example.util

import android.util.Log
import com.example.data.local.AuditLogEntity
import com.example.data.local.PmpDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class TransactionStage {
    TX_BEGIN,           // Room transaction initialized
    ROOM_INSERT,        // Record inserted into SQLite table
    ROOM_UPDATE,        // Record updated in SQLite table
    ROOM_DELETE,        // Record deleted from SQLite table
    ROOM_TX_COMMIT,     // Room transaction committed successfully
    ROOM_TX_ROLLBACK,   // Room transaction aborted/rolled back
    FETCH_QUERY,        // Query executed to fetch financial data
    SYNC_DISPATCH,      // Transaction payload queued/dispatched to Cloud
    SYNC_ACKNOWLEDGED,  // Cloud server confirmed write
    SYNC_BROKEN         // Point of failure where data sync broke
}

enum class TraceStatus {
    SUCCESS,
    FAILURE,
    WARNING,
    IN_PROGRESS
}

data class RoomTransactionTrace(
    val id: String = UUID.randomUUID().toString().take(8),
    val operation: String,
    val stage: TransactionStage,
    val status: TraceStatus,
    val entityType: String,
    val recordId: String = "",
    val details: String,
    val durationMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val formattedTime: String = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp)),
    val errorMessage: String? = null,
    val diagnosticsHint: String? = null
)

/**
 * Structured diagnostic logger for Room Database transaction flows and Cloud ERP sync bridges.
 * Pinpoints the exact stage where financial transactions are committed, queried, or where sync breaks.
 */
object RoomTransactionLogger {
    private const val TAG = "PMP_ROOM_TX"
    private const val MAX_LOGS = 150

    private val _traces = MutableStateFlow<List<RoomTransactionTrace>>(emptyList())
    val traces: StateFlow<List<RoomTransactionTrace>> = _traces.asStateFlow()

    fun log(
        operation: String,
        stage: TransactionStage,
        status: TraceStatus,
        entityType: String,
        recordId: String = "",
        details: String,
        durationMs: Long = 0L,
        error: Throwable? = null,
        diagnosticsHint: String? = null,
        dao: PmpDao? = null,
        user: String = "النظام"
    ): RoomTransactionTrace {
        val trace = RoomTransactionTrace(
            operation = operation,
            stage = stage,
            status = status,
            entityType = entityType,
            recordId = recordId,
            details = details,
            durationMs = durationMs,
            errorMessage = error?.message,
            diagnosticsHint = diagnosticsHint
        )

        // 1. Thread-safe in-memory ring buffer
        val current = _traces.value.toMutableList()
        current.add(0, trace)
        if (current.size > MAX_LOGS) {
            _traces.value = current.take(MAX_LOGS)
        } else {
            _traces.value = current
        }

        // 2. Structured Android Logcat output
        val statusSymbol = when (status) {
            TraceStatus.SUCCESS -> "✅"
            TraceStatus.FAILURE -> "❌"
            TraceStatus.WARNING -> "⚠️"
            TraceStatus.IN_PROGRESS -> "⏳"
        }
        val logLine = "[$statusSymbol][${stage.name}][$operation][$entityType] rec:$recordId (${durationMs}ms) - $details"
        if (status == TraceStatus.FAILURE) {
            Log.e(TAG, "$logLine | Error: ${error?.message}", error)
        } else if (status == TraceStatus.WARNING) {
            Log.w(TAG, logLine)
        } else {
            Log.d(TAG, logLine)
        }

        return trace
    }

    suspend fun <T> traceTransaction(
        operation: String,
        entityType: String,
        details: String,
        dao: PmpDao? = null,
        user: String = "النظام",
        block: suspend () -> T
    ): T {
        val start = System.currentTimeMillis()
        log(
            operation = operation,
            stage = TransactionStage.TX_BEGIN,
            status = TraceStatus.IN_PROGRESS,
            entityType = entityType,
            details = "بدء معاملة Room: $details"
        )
        return try {
            val result = block()
            val duration = System.currentTimeMillis() - start
            val recordId = when (result) {
                is Result<*> -> (result.getOrNull() as? Long)?.toString() ?: ""
                is Long -> result.toString()
                else -> ""
            }
            log(
                operation = operation,
                stage = TransactionStage.ROOM_TX_COMMIT,
                status = TraceStatus.SUCCESS,
                entityType = entityType,
                recordId = recordId,
                details = "نجح تثبيت المعاملة محلياً في Room: $details",
                durationMs = duration
            )
            result
        } catch (e: Throwable) {
            val duration = System.currentTimeMillis() - start
            val hint = when {
                e is android.database.sqlite.SQLiteConstraintException -> "فشل قيد فريد أو مرجعي في قاعدة البيانات (Foreign Key / Unique Constraint)"
                e is android.database.sqlite.SQLiteDiskIOException -> "خطأ في قراءة أو كتابة القرص المحلي (Disk I/O Error)"
                e is NullPointerException -> "قيمة مفقودة (Null) في أحد حقول المعاملة المالية"
                else -> "فشل غير متوقع أثناء تنفيذ معاملة Room"
            }
            log(
                operation = operation,
                stage = TransactionStage.ROOM_TX_ROLLBACK,
                status = TraceStatus.FAILURE,
                entityType = entityType,
                details = "فشل المعاملة وتراجع Room (Rollback): ${e.message}",
                durationMs = duration,
                error = e,
                diagnosticsHint = hint
            )
            throw e
        }
    }

    suspend fun <T> traceFetch(
        queryName: String,
        entityType: String,
        filterParams: String = "",
        block: suspend () -> List<T>
    ): List<T> {
        val start = System.currentTimeMillis()
        return try {
            val list = block()
            val duration = System.currentTimeMillis() - start
            val status = if (list.isEmpty()) TraceStatus.WARNING else TraceStatus.SUCCESS
            val hint = if (list.isEmpty()) "تم استرجاع 0 سجلات. تأكد من مطابقة شروط الفلترة أو وجود بيانات مخزنة محلياً" else null
            log(
                operation = queryName,
                stage = TransactionStage.FETCH_QUERY,
                status = status,
                entityType = entityType,
                details = "استرجاع ${list.size} سجلات ($filterParams)",
                durationMs = duration,
                diagnosticsHint = hint
            )
            list
        } catch (e: Throwable) {
            val duration = System.currentTimeMillis() - start
            log(
                operation = queryName,
                stage = TransactionStage.FETCH_QUERY,
                status = TraceStatus.FAILURE,
                entityType = entityType,
                details = "فشل استرجاع السجلات المالية ($filterParams): ${e.message}",
                durationMs = duration,
                error = e,
                diagnosticsHint = "تعذر تنفيذ استعلام الاسترجاع من Room"
            )
            throw e
        }
    }

    fun recordSyncBreakage(
        operation: String,
        entityType: String,
        recordId: String,
        stage: TransactionStage = TransactionStage.SYNC_BROKEN,
        reason: String,
        cause: Throwable? = null
    ) {
        log(
            operation = operation,
            stage = stage,
            status = TraceStatus.FAILURE,
            entityType = entityType,
            recordId = recordId,
            details = "نقطة انقطاع المزامنة: $reason",
            error = cause,
            diagnosticsHint = "انقطاع الاتصال أو المزامنة بين Room والسيرفر السحابي: $reason"
        )
    }

    fun clearTraces() {
        _traces.value = emptyList()
    }
}
