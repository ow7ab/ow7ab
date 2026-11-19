package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.local.*
import com.example.util.RoomTransactionLogger
import com.example.util.TransactionStage
import com.example.util.TraceStatus
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.example.isSuperAdminAccount
import com.example.isSystemManager
import com.example.util.EnterpriseRbacManager
import com.google.firebase.firestore.Source
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SyncDiagnosticRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val formattedTime: String,
    val deviceId: String,
    val action: String,
    val status: String, // SUCCESS, FAILED, WARNING
    val entitiesCount: Int,
    val message: String
)

data class ServerHealthReport(
    val isOnline: Boolean,
    val endpoint: String,
    val projectId: String = "gen-lang-client-0220128746",
    val databaseId: String = "ai-studio-android-glassdro-d6052420-cbc0-47dd-8cdd-8caa5e6a7248",
    val clusterCode: String,
    val responseTimeMs: Long,
    val cloudInvoicesCount: Int,
    val cloudMovementsCount: Int,
    val cloudCustomersCount: Int,
    val cloudUsersCount: Int,
    val cloudProductsCount: Int = 0,
    val message: String
)

class CloudErpSyncRepository(
    private val firestore: FirebaseFirestore,
    private val databaseId: String = "ai-studio-android-glassdro-d6052420-cbc0-47dd-8cdd-8caa5e6a7248"
) {
    companion object {
        private const val TAG = "CloudErpSyncRepo"
        const val DEFAULT_CLUSTER = "PMP-ERP-MAIN-CLUSTER"
        const val DEPRECATED_V16_CLUSTER = "PMP-ERP-V16-CLUSTER"

        fun create(context: Context): CloudErpSyncRepository {
            runCatching {
                if (com.google.firebase.FirebaseApp.getApps(context.applicationContext).isEmpty()) {
                    com.google.firebase.FirebaseApp.initializeApp(context.applicationContext)
                }
            }
            val dbId = context.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(dbId)
            return CloudErpSyncRepository(db, dbId)
        }

        fun buildEmailDirectoryKey(email: String): String {
            val clean = email.trim().lowercase()
            val normalized = clean.replace(Regex("[^a-zA-Z0-9]+"), "_").trim('_')
            val raw = "EMAIL_$normalized".trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-").trim('-')
            return (if (raw.isNotBlank()) raw.take(100) else "EMAIL_USER")
        }

        fun resolveCanonicalRbacTier(account: UserAccountEntity): String {
            if (account.isSuperAdminAccount()) return "ADMIN"
            if (account.isSystemManager() || EnterpriseRbacManager.isPrivilegedRole(account.role)) return "MANAGER"
            val r = account.role.trim().lowercase()
            if (r.contains("نائب محاسب") || r.contains("deputy")) return "DEPUTY_ACCOUNTANT"
            if (r.contains("محاسب") || r.contains("accountant")) return "ACCOUNTANT"
            return "EMPLOYEE"
        }

        enum class TransactionViolationType { NONE, DUPLICATE, TAMPER }

        data class TransactionCommitValidationResult(
            val valid: Boolean,
            val violationType: TransactionViolationType = TransactionViolationType.NONE,
            val reason: String = "",
            val matchedDocId: String = ""
        )

        private val VALID_CURRENCIES = setOf("SDG", "EGP", "USD")
        private val VALID_DIRECTIONS = setOf("وارد", "منصرف", "IN", "OUT", "INCOME", "EXPENSE", "TRANSFER_IN", "TRANSFER_OUT", "قبض", "صرف")
        private const val FINANCIAL_REALISTIC_MAX = 1_000_000_000_000.0

        private fun normKey(raw: String?): String =
            raw.orEmpty()
                .trim()
                .replace(Regex("[إأآا]"), "ا")
                .replace("ة", "ه")
                .replace("ى", "ي")
                .replace(Regex("\\s+"), " ")
                .lowercase(java.util.Locale.US)

        private fun numVal(v: Any?): Double = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull() ?: Double.NaN
            else -> Double.NaN
        }

        fun buildFinancialCommitFingerprint(
            collectionName: String,
            payload: Map<String, Any?>
        ): String {
            val col = collectionName.trim().lowercase(java.util.Locale.US)
            return when (col) {
                "movements" -> {
                    val ref = normKey((payload["reference"] ?: payload["referenceNo"] ?: "").toString())
                    val box = normKey((payload["cashbox"] ?: "").toString())
                    val dir = normKey((payload["direction"] ?: "").toString())
                    val cur = (payload["currency"] ?: "SDG").toString().trim().uppercase(java.util.Locale.US)
                    val amt = kotlin.math.round((numVal(payload["amount"]).takeIf { it.isFinite() } ?: 0.0) * 100.0).toLong()
                    "MOV|$ref|$box|$dir|$cur|$amt"
                }
                "transfers" -> {
                    val ref = normKey((payload["reference"] ?: "").toString())
                    val src = normKey((payload["sourceCashbox"] ?: "").toString())
                    val dst = normKey((payload["destinationCashbox"] ?: "").toString())
                    val sCur = (payload["sourceCurrency"] ?: "SDG").toString().trim().uppercase(java.util.Locale.US)
                    val dCur = (payload["destinationCurrency"] ?: "EGP").toString().trim().uppercase(java.util.Locale.US)
                    val sAmt = kotlin.math.round((numVal(payload["sourceAmount"]).takeIf { it.isFinite() } ?: 0.0) * 100.0).toLong()
                    "TRF|$ref|$src|$dst|$sCur|$dCur|$sAmt"
                }
                "invoices" -> {
                    val invNo = normKey((payload["invoiceNo"] ?: payload["reference"] ?: "").toString())
                    val cust = normKey((payload["customerName"] ?: "").toString())
                    val cur = (payload["currency"] ?: "SDG").toString().trim().uppercase(java.util.Locale.US)
                    val total = kotlin.math.round((numVal(payload["total"]).takeIf { it.isFinite() } ?: 0.0) * 100.0).toLong()
                    "INV|$invNo|$cust|$cur|$total"
                }
                "invoice_payments" -> {
                    val invId = (payload["invoiceId"] ?: "").toString().trim()
                    val ref = normKey((payload["reference"] ?: payload["syncUuid"] ?: "").toString())
                    val box = normKey((payload["cashbox"] ?: "").toString())
                    val cur = (payload["currency"] ?: "SDG").toString().trim().uppercase(java.util.Locale.US)
                    val amt = kotlin.math.round((numVal(payload["amount"]).takeIf { it.isFinite() } ?: 0.0) * 100.0).toLong()
                    "PAY|$invId|$ref|$box|$cur|$amt"
                }
                else -> "${col.uppercase(java.util.Locale.US)}|${normKey((payload["reference"] ?: payload["id"] ?: "").toString())}"
            }
        }

        fun validateTransactionCommitPayload(
            collectionName: String,
            docId: String,
            payload: Map<String, Any?>
        ): TransactionCommitValidationResult {
            val col = collectionName.trim().lowercase(java.util.Locale.US)
            if (docId.isBlank()) {
                return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "معرف المستند المالي فارغ أو غير صالح.")
            }
            if (payload.isEmpty()) {
                return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "بيانات المعاملة المالية فارغة أو تالفة.")
            }
            when (col) {
                "movements" -> {
                    val amt = numVal(payload["amount"])
                    val cur = (payload["currency"] ?: "").toString().trim().uppercase(java.util.Locale.US)
                    val dir = (payload["direction"] ?: "").toString().trim()
                    val cashbox = (payload["cashbox"] ?: "").toString().trim()
                    if (!amt.isFinite() || amt <= 0.0 || amt > FINANCIAL_REALISTIC_MAX) {
                        return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "قيمة الحركة المالية غير صالحة أو خارج النطاق (${payload["amount"]}).")
                    }
                    if (cur !in VALID_CURRENCIES) {
                        return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "عملة غير معتمدة في المعاملة المالية: \"$cur\".")
                    }
                    if (dir !in VALID_DIRECTIONS) {
                        return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "اتجاه حركة مالية غير مشروع: \"$dir\".")
                    }
                    if (cashbox.isBlank()) {
                        return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "محاولة تسجيل حركة مالية بدون تحديد الخزنة المعتمدة.")
                    }
                }
                "transfers" -> {
                    val srcAmt = numVal(payload["sourceAmount"])
                    val dstAmt = numVal(payload["destinationAmount"])
                    val rate = numVal(payload["rate"])
                    val srcBox = normKey((payload["sourceCashbox"] ?: "").toString())
                    val dstBox = normKey((payload["destinationCashbox"] ?: "").toString())
                    val srcCur = (payload["sourceCurrency"] ?: "").toString().trim().uppercase(java.util.Locale.US)
                    val dstCur = (payload["destinationCurrency"] ?: "").toString().trim().uppercase(java.util.Locale.US)
                    if (!srcAmt.isFinite() || srcAmt <= 0.0 || srcAmt > FINANCIAL_REALISTIC_MAX) {
                        return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "مبلغ المصدر غير صالح (${payload["sourceAmount"]}).")
                    }
                    if (!dstAmt.isFinite() || dstAmt <= 0.0 || dstAmt > FINANCIAL_REALISTIC_MAX) {
                        return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "مبلغ الوجهة غير صالح (${payload["destinationAmount"]}).")
                    }
                    if (!rate.isFinite() || rate <= 0.0 || rate > 1_000_000.0) {
                        return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "سعر الصرف غير صالح (${payload["rate"]}).")
                    }
                    if (srcCur !in VALID_CURRENCIES || dstCur !in VALID_CURRENCIES) {
                        return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "عملة تحويل غير معتمدة ($srcCur -> $dstCur).")
                    }
                    if (srcBox.isBlank() || dstBox.isBlank()) {
                        return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "خزنة المصدر أو الوجهة مفقودة في التحويل.")
                    }
                    if (srcBox == dstBox && srcCur == dstCur) {
                        return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "لا يمكن التحويل من وإلى نفس الخزنة وبنفس العملة.")
                    }
                    val expectedMul = kotlin.math.round(srcAmt * rate * 100.0) / 100.0
                    val expectedDiv = kotlin.math.round((srcAmt / rate) * 100.0) / 100.0
                    val tol = kotlin.math.max(0.1, kotlin.math.max(expectedMul, expectedDiv) * 0.005)
                    if (kotlin.math.abs(dstAmt - expectedMul) > tol && kotlin.math.abs(dstAmt - expectedDiv) > tol) {
                        return TransactionCommitValidationResult(
                            false,
                            TransactionViolationType.TAMPER,
                            "عدم تطابق حساب التحويل ($srcAmt بسعر $rate لا يطابق المستلم $dstAmt) — احتمال تلاعب بالقيم."
                        )
                    }
                }
                "invoices" -> {
                    val total = numVal(payload["total"])
                    val paid = numVal(payload["paidAmount"] ?: 0.0).takeIf { it.isFinite() } ?: 0.0
                    val cur = (payload["currency"] ?: "SDG").toString().trim().uppercase(java.util.Locale.US)
                    if (!total.isFinite() || total < 0.0 || paid < 0.0 || total > FINANCIAL_REALISTIC_MAX) {
                        return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "قيم مالية سالبة أو غير صالحة في الفاتورة.")
                    }
                    if (cur !in VALID_CURRENCIES) {
                        return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "عملة فاتورة غير معتمدة: \"$cur\".")
                    }
                    if (paid > total + 0.05) {
                        return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "المبلغ المدفوع ($paid) يتجاوز إجمالي الفاتورة ($total).")
                    }
                }
                "invoice_payments" -> {
                    val amt = numVal(payload["amount"])
                    val cur = (payload["currency"] ?: "SDG").toString().trim().uppercase(java.util.Locale.US)
                    if (!amt.isFinite() || amt <= 0.0 || amt > FINANCIAL_REALISTIC_MAX || cur !in VALID_CURRENCIES) {
                        return TransactionCommitValidationResult(false, TransactionViolationType.TAMPER, "بيانات دفعة السداد غير صالحة (${payload["amount"]} $cur).")
                    }
                }
            }
            return TransactionCommitValidationResult(valid = true, violationType = TransactionViolationType.NONE)
        }

        fun checkExistingFinancialCommitConflict(
            collectionName: String,
            candidateDocId: String,
            candidatePayload: Map<String, Any?>,
            existingDocs: List<Pair<String, Map<String, Any?>>>
        ): TransactionCommitValidationResult {
            if (existingDocs.isEmpty()) return TransactionCommitValidationResult(true)
            val col = collectionName.trim().lowercase(java.util.Locale.US)
            val candUuid = (candidatePayload["syncUuid"] ?: "").toString().trim()
            val candRef = (candidatePayload["reference"] ?: candidatePayload["referenceNo"] ?: candidatePayload["invoiceNo"] ?: "").toString().trim()

            for ((exDocId, existing) in existingDocs) {
                val exUuid = (existing["syncUuid"] ?: "").toString().trim()
                val sameDocId = candidateDocId.isNotBlank() && exDocId.isNotBlank() && candidateDocId == exDocId
                val sameUuid = candUuid.isNotBlank() && exUuid.isNotBlank() && candUuid == exUuid

                when (col) {
                    "movements" -> {
                        val exRef = (existing["reference"] ?: existing["referenceNo"] ?: "").toString().trim()
                        val sameRefAndLeg = candRef.isNotBlank() && exRef.isNotBlank() &&
                            candRef.equals(exRef, ignoreCase = true) &&
                            normKey(existing["cashbox"]?.toString()) == normKey(candidatePayload["cashbox"]?.toString()) &&
                            normKey(existing["direction"]?.toString()) == normKey(candidatePayload["direction"]?.toString())
                        if (sameDocId || sameUuid || sameRefAndLeg) {
                            val sameAmt = kotlin.math.abs((numVal(existing["amount"]).takeIf { it.isFinite() } ?: 0.0) - (numVal(candidatePayload["amount"]).takeIf { it.isFinite() } ?: 0.0)) < 0.01
                            val sameCur = (existing["currency"] ?: "SDG").toString().trim().uppercase(java.util.Locale.US) ==
                                (candidatePayload["currency"] ?: "SDG").toString().trim().uppercase(java.util.Locale.US)
                            val sameBox = normKey(existing["cashbox"]?.toString()) == normKey(candidatePayload["cashbox"]?.toString())
                            return if (!sameAmt || !sameCur || !sameBox) {
                                TransactionCommitValidationResult(
                                    valid = false,
                                    violationType = TransactionViolationType.TAMPER,
                                    matchedDocId = exDocId,
                                    reason = "رُصدت محاولة تعديل أو تلاعب في معاملة مالية مسجلة مسبقاً ($exDocId): المبلغ أو الخزنة أو العملة لا تطابق السجل المعتمد في Firestore."
                                )
                            } else {
                                TransactionCommitValidationResult(
                                    valid = false,
                                    violationType = TransactionViolationType.DUPLICATE,
                                    matchedDocId = exDocId,
                                    reason = "المعاملة المالية مسجلة مسبقاً في Firestore ($exDocId) — تم منع التكرار."
                                )
                            }
                        }
                    }
                    "transfers" -> {
                        val exRef = (existing["reference"] ?: "").toString().trim()
                        val sameRef = candRef.isNotBlank() && exRef.isNotBlank() && candRef.equals(exRef, ignoreCase = true)
                        if (sameDocId || sameUuid || sameRef) {
                            val sameSrc = kotlin.math.abs((numVal(existing["sourceAmount"]).takeIf { it.isFinite() } ?: 0.0) - (numVal(candidatePayload["sourceAmount"]).takeIf { it.isFinite() } ?: 0.0)) < 0.01
                            val sameDst = kotlin.math.abs((numVal(existing["destinationAmount"]).takeIf { it.isFinite() } ?: 0.0) - (numVal(candidatePayload["destinationAmount"]).takeIf { it.isFinite() } ?: 0.0)) < 0.01
                            val sameBoxes = normKey(existing["sourceCashbox"]?.toString()) == normKey(candidatePayload["sourceCashbox"]?.toString()) &&
                                normKey(existing["destinationCashbox"]?.toString()) == normKey(candidatePayload["destinationCashbox"]?.toString())
                            return if (!sameSrc || !sameDst || !sameBoxes) {
                                TransactionCommitValidationResult(
                                    valid = false,
                                    violationType = TransactionViolationType.TAMPER,
                                    matchedDocId = exDocId,
                                    reason = "رُصدت محاولة تعديل أو تلاعب في تحويل خزينة مسجل مسبقاً ($exDocId)."
                                )
                            } else {
                                TransactionCommitValidationResult(
                                    valid = false,
                                    violationType = TransactionViolationType.DUPLICATE,
                                    matchedDocId = exDocId,
                                    reason = "عملية التحويل مسجلة مسبقاً في Firestore ($exDocId) — تم منع التكرار."
                                )
                            }
                        }
                    }
                    "invoices" -> {
                        val exInvNo = (existing["invoiceNo"] ?: existing["reference"] ?: "").toString().trim()
                        val candInvNo = (candidatePayload["invoiceNo"] ?: candRef).toString().trim()
                        val sameInvNo = candInvNo.isNotBlank() && exInvNo.isNotBlank() && candInvNo.equals(exInvNo, ignoreCase = true)
                        if (sameDocId || sameUuid || sameInvNo) {
                            val sameTotal = kotlin.math.abs((numVal(existing["total"]).takeIf { it.isFinite() } ?: 0.0) - (numVal(candidatePayload["total"]).takeIf { it.isFinite() } ?: 0.0)) < 0.01
                            val sameCur = (existing["currency"] ?: "SDG").toString().trim().uppercase(java.util.Locale.US) ==
                                (candidatePayload["currency"] ?: "SDG").toString().trim().uppercase(java.util.Locale.US)
                            val sameCustomer = normKey(existing["customerName"]?.toString()) == normKey(candidatePayload["customerName"]?.toString())
                            return if (!sameTotal || !sameCur || !sameCustomer) {
                                TransactionCommitValidationResult(
                                    valid = false,
                                    violationType = TransactionViolationType.TAMPER,
                                    matchedDocId = exDocId,
                                    reason = "رُصدت محاولة تعديل أو تلاعب في فاتورة مسجلة مسبقاً ($exDocId): الإجمالي أو العملة أو العميل لا يطابق السجل المعتمد في Firestore."
                                )
                            } else {
                                TransactionCommitValidationResult(
                                    valid = false,
                                    violationType = TransactionViolationType.DUPLICATE,
                                    matchedDocId = exDocId,
                                    reason = "الفاتورة مسجلة مسبقاً في Firestore ($exDocId) — تم منع التكرار."
                                )
                            }
                        }
                    }
                    "invoice_payments" -> {
                        val exRef = (existing["reference"] ?: existing["referenceNo"] ?: "").toString().trim()
                        val candPayRef = (candidatePayload["reference"] ?: candidatePayload["referenceNo"] ?: "").toString().trim()
                        val samePayRef = candPayRef.isNotBlank() && exRef.isNotBlank() && candPayRef.equals(exRef, ignoreCase = true)
                        val exInvId = (existing["invoiceId"] ?: "").toString().trim()
                        val candInvId = (candidatePayload["invoiceId"] ?: "").toString().trim()
                        val compatibleInv = exInvId.isBlank() || candInvId.isBlank() || exInvId == candInvId
                        if (sameDocId || sameUuid || (samePayRef && compatibleInv)) {
                            val sameAmt = kotlin.math.abs((numVal(existing["amount"]).takeIf { it.isFinite() } ?: 0.0) - (numVal(candidatePayload["amount"]).takeIf { it.isFinite() } ?: 0.0)) < 0.01
                            val sameCur = (existing["currency"] ?: "SDG").toString().trim().uppercase(java.util.Locale.US) ==
                                (candidatePayload["currency"] ?: "SDG").toString().trim().uppercase(java.util.Locale.US)
                            return if (!sameAmt || !sameCur) {
                                TransactionCommitValidationResult(
                                    valid = false,
                                    violationType = TransactionViolationType.TAMPER,
                                    matchedDocId = exDocId,
                                    reason = "رُصدت محاولة تعديل أو تلاعب في دفعة سداد فاتورة مسجلة مسبقاً ($exDocId)."
                                )
                            } else {
                                TransactionCommitValidationResult(
                                    valid = false,
                                    violationType = TransactionViolationType.DUPLICATE,
                                    matchedDocId = exDocId,
                                    reason = "دفعة السداد مسجلة مسبقاً في Firestore ($exDocId) — تم منع التكرار."
                                )
                            }
                        }
                    }
                }
            }
            return TransactionCommitValidationResult(valid = true)
        }
    }

    private val _diagnostics = kotlinx.coroutines.flow.MutableStateFlow<List<SyncDiagnosticRecord>>(emptyList())
    val diagnostics: kotlinx.coroutines.flow.StateFlow<List<SyncDiagnosticRecord>> = _diagnostics

    private val _lastSuccessfulSyncTime = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    val lastSuccessfulSyncTime: kotlinx.coroutines.flow.StateFlow<Long?> = _lastSuccessfulSyncTime

    private val _pendingErrors = kotlinx.coroutines.flow.MutableStateFlow<List<SyncDiagnosticRecord>>(emptyList())
    val pendingErrors: kotlinx.coroutines.flow.StateFlow<List<SyncDiagnosticRecord>> = _pendingErrors

    private val _structuredErrors = kotlinx.coroutines.flow.MutableStateFlow<List<CloudErrorLogEntry>>(emptyList())
    val structuredErrors: kotlinx.coroutines.flow.StateFlow<List<CloudErrorLogEntry>> = _structuredErrors

    private val _syncStepSummaries = kotlinx.coroutines.flow.MutableStateFlow<List<SyncStepSummary>>(emptyList())
    val syncStepSummaries: kotlinx.coroutines.flow.StateFlow<List<SyncStepSummary>> = _syncStepSummaries

    private val _lastPreflightResult = kotlinx.coroutines.flow.MutableStateFlow<PreflightCheckResult?>(null)
    val lastPreflightResult: kotlinx.coroutines.flow.StateFlow<PreflightCheckResult?> = _lastPreflightResult

    private val _serviceProbes = kotlinx.coroutines.flow.MutableStateFlow<List<ServiceProbeResult>>(emptyList())
    val serviceProbes: kotlinx.coroutines.flow.StateFlow<List<ServiceProbeResult>> = _serviceProbes

    private val _comprehensiveReport = kotlinx.coroutines.flow.MutableStateFlow<ComprehensiveDiagnosticReport?>(null)
    val comprehensiveReport: kotlinx.coroutines.flow.StateFlow<ComprehensiveDiagnosticReport?> = _comprehensiveReport

    // Global mutex guaranteeing non-overlapping synchronization passes (prevents sync concurrency collisions)
    private val syncMutex = Mutex()

    private val syncExceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Recovered uncaught sync coroutine exception: ${throwable.message}", throwable)
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.safeString(field: String): String? =
        runCatching {
            when (val raw = get(field)) {
                is String -> raw
                is Number -> raw.toString()
                is Boolean -> raw.toString()
                is com.google.firebase.Timestamp -> raw.toDate().toInstant().toString()
                else -> getString(field)
            }
        }.getOrNull()

    private fun com.google.firebase.firestore.DocumentSnapshot.safeLong(field: String): Long? =
        runCatching {
            when (val raw = get(field)) {
                is Number -> raw.toLong()
                is String -> raw.trim().toLongOrNull()
                is com.google.firebase.Timestamp -> raw.toDate().time
                else -> getLong(field)
            }
        }.getOrNull()

    private fun com.google.firebase.firestore.DocumentSnapshot.safeDouble(field: String): Double? =
        runCatching {
            when (val raw = get(field)) {
                is Number -> raw.toDouble()
                is String -> raw.trim().toDoubleOrNull()
                else -> getDouble(field)
            }
        }.getOrNull()

    private fun com.google.firebase.firestore.DocumentSnapshot.safeBoolean(field: String): Boolean? =
        runCatching {
            when (val raw = get(field)) {
                is Boolean -> raw
                is String -> raw.trim().equals("true", ignoreCase = true) || raw.trim() == "1"
                is Number -> raw.toInt() != 0
                else -> getBoolean(field)
            }
        }.getOrNull()

    fun clearPendingError(errorId: String) {
        _pendingErrors.value = _pendingErrors.value.filter { it.id != errorId }
        _structuredErrors.value = _structuredErrors.value.filter { it.id != errorId }
    }

    fun clearAllPendingErrors() {
        _pendingErrors.value = emptyList()
        _structuredErrors.value = emptyList()
    }

    /**
     * Clears stale UNAUTHENTICATED errors automatically once Google Sign-In is active or a sync succeeds.
     */
    fun clearAuthPendingErrors() {
        _structuredErrors.value = _structuredErrors.value.filterNot {
            it.category == CloudErrorCategory.UNAUTHENTICATED
        }
        _pendingErrors.value = _pendingErrors.value.filterNot { rec ->
            val msg = rec.message.lowercase(java.util.Locale.US)
            msg.contains("unauthenticated") ||
                msg.contains("جلسة المصادقة") ||
                msg.contains("google sign-in") ||
                msg.contains("تسجيل الدخول بحساب google")
        }
    }

    fun recordStructuredError(
        operation: String,
        collectionPath: String,
        throwable: Throwable?,
        deviceId: String = "ANDROID-CLIENT"
    ): CloudErrorLogEntry {
        val entry = CloudSyncErrorTracker.buildLogEntry(
            operation = operation,
            collectionPath = collectionPath,
            throwable = throwable,
            deviceId = deviceId
        )
        val currentStruct = _structuredErrors.value.toMutableList()
        // Deduplicate repeated UNAUTHENTICATED errors so background triggers never stack 17+ identical entries
        if (entry.category == CloudErrorCategory.UNAUTHENTICATED) {
            currentStruct.removeAll { it.category == CloudErrorCategory.UNAUTHENTICATED }
        }
        currentStruct.add(0, entry)
        _structuredErrors.value = if (currentStruct.size > 50) currentStruct.take(50) else currentStruct
        return entry
    }

    private val recentFinancialFingerprints = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun recordTamperAttempt(
        operation: String,
        collectionPath: String,
        reason: String,
        actor: String = "",
        deviceId: String = "ANDROID-CLIENT"
    ): CloudErrorLogEntry {
        val entry = CloudSyncErrorTracker.buildTamperLogEntry(
            operation = operation,
            collectionPath = collectionPath,
            reason = reason,
            actor = actor,
            deviceId = deviceId
        )
        val currentStruct = _structuredErrors.value.toMutableList()
        currentStruct.add(0, entry)
        _structuredErrors.value = if (currentStruct.size > 50) currentStruct.take(50) else currentStruct
        recordDiagnostic(operation, "FAILED", 0, entry.sanitizedMessage, deviceId)
        RoomTransactionLogger.log(
            operation = operation,
            stage = TransactionStage.SYNC_BROKEN,
            status = TraceStatus.FAILURE,
            entityType = collectionPath,
            recordId = "",
            details = entry.sanitizedMessage
        )
        return entry
    }

    fun recordDuplicateAttempt(
        operation: String,
        collectionPath: String,
        reason: String,
        actor: String = "",
        deviceId: String = "ANDROID-CLIENT"
    ): CloudErrorLogEntry {
        val entry = CloudSyncErrorTracker.buildDuplicateLogEntry(
            operation = operation,
            collectionPath = collectionPath,
            reason = reason,
            actor = actor,
            deviceId = deviceId
        )
        val currentStruct = _structuredErrors.value.toMutableList()
        currentStruct.add(0, entry)
        _structuredErrors.value = if (currentStruct.size > 50) currentStruct.take(50) else currentStruct
        recordDiagnostic(operation, "FAILED", 0, entry.sanitizedMessage, deviceId)
        RoomTransactionLogger.log(
            operation = operation,
            stage = TransactionStage.SYNC_BROKEN,
            status = TraceStatus.FAILURE,
            entityType = collectionPath,
            recordId = "",
            details = entry.sanitizedMessage
        )
        return entry
    }

    fun recordDiagnostic(
        action: String,
        status: String,
        count: Int,
        message: String,
        deviceId: String = "ANDROID-CLIENT"
    ) {
        val safeMessage = CloudSyncErrorTracker.sanitizeCloudMessage(message)
        val now = System.currentTimeMillis()
        val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(now))
        val record = SyncDiagnosticRecord(
            timestamp = now,
            formattedTime = timeStr,
            deviceId = deviceId.ifBlank { "ANDROID-CLIENT" },
            action = action,
            status = status,
            entitiesCount = count,
            message = safeMessage
        )
        val isAuthFailure = status == "FAILED" && (
            safeMessage.contains("UNAUTHENTICATED", ignoreCase = true) ||
                safeMessage.contains("جلسة المصادقة") ||
                safeMessage.contains("Google Sign-In", ignoreCase = true)
            )

        val current = _diagnostics.value.toMutableList()
        // Collapse consecutive identical UNAUTHENTICATED diagnostic records into one fresh entry
        if (isAuthFailure && current.isNotEmpty() && current.first().status == "FAILED" &&
            (current.first().message.contains("UNAUTHENTICATED", ignoreCase = true) ||
                current.first().message.contains("جلسة المصادقة") ||
                current.first().message.contains("Google Sign-In", ignoreCase = true))
        ) {
            current[0] = record
        } else {
            current.add(0, record)
        }
        _diagnostics.value = if (current.size > 50) current.take(50) else current

        if (status == "SUCCESS") {
            _lastSuccessfulSyncTime.value = now
            clearAuthPendingErrors()
        } else if (status == "FAILED") {
            val currentErrors = _pendingErrors.value.toMutableList()
            if (isAuthFailure) {
                currentErrors.removeAll { rec ->
                    rec.message.contains("UNAUTHENTICATED", ignoreCase = true) ||
                        rec.message.contains("جلسة المصادقة") ||
                        rec.message.contains("Google Sign-In", ignoreCase = true)
                }
            }
            currentErrors.add(0, record)
            _pendingErrors.value = if (currentErrors.size > 20) currentErrors.take(20) else currentErrors
        }
    }

    private val currentActorId: String
        get() = runCatching { Firebase.auth.currentUser?.uid }.getOrNull() ?: "ERP-CLUSTER-NODE"

    private fun safeId(raw: String, fallback: String): String {
        val cleaned = raw.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-").trim('-')
        return if (cleaned.isNotBlank()) cleaned.take(100) else fallback
    }

    private fun clusterRef(clusterCode: String) =
        firestore.collection("erp_clusters").document(safeId(clusterCode, DEFAULT_CLUSTER))

    fun buildEmailDirectoryKey(email: String): String =
        Companion.buildEmailDirectoryKey(email)

    fun resolveCanonicalRbacTier(account: UserAccountEntity): String =
        Companion.resolveCanonicalRbacTier(account)

    /**
     * يزامن دليل البريد الإلكتروني `email_directory` وعضوية `cluster_members/{uid}` على Firestore
     * لضمان تطابق صلاحيات Firebase Auth مع قواعد الأمان (firestore.rules) بدون أي أخطاء PERMISSION_DENIED.
     */
    suspend fun syncRbacDirectoryForAccount(
        clusterCode: String,
        account: UserAccountEntity,
        linkedUid: String? = null
    ) = withContext(Dispatchers.IO) {
        val fbUser = runCatching { Firebase.auth.currentUser }.getOrNull() ?: return@withContext
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        if (cleanCluster.equals(DEPRECATED_V16_CLUSTER, ignoreCase = true)) return@withContext
        val cRef = clusterRef(cleanCluster)
        val roleKey = resolveCanonicalRbacTier(account)
        val docUserCode = safeId(account.userCode, "USR-${account.id}")
        val email = account.phoneOrEmail.trim().lowercase()
        val emailKey = if (email.contains("@")) buildEmailDirectoryKey(email) else ""

        if (emailKey.isNotEmpty()) {
            runCatching {
                cRef.collection("email_directory").document(emailKey).set(
                    mapOf(
                        "email" to email,
                        "userCode" to docUserCode,
                        "username" to account.username,
                        "roleKey" to roleKey,
                        "branch" to account.branch.ifBlank { "الخرطوم" },
                        "permissions" to account.permissions.ifBlank { "صلاحية العرض" },
                        "active" to account.active,
                        "updatedAt" to System.currentTimeMillis(),
                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
            }.onFailure { e ->
                recordStructuredError("SYNC_EMAIL_DIRECTORY", "erp_clusters/$cleanCluster/email_directory/$emailKey", e)
            }
        }

        val uid = linkedUid?.trim().orEmpty()
        if (uid.isNotEmpty()) {
            val cleanUid = safeId(uid, uid)
            runCatching {
                cRef.collection("cluster_members").document(cleanUid).set(
                    buildMap<String, Any> {
                        put("uid", cleanUid)
                        put("userCode", docUserCode)
                        put("username", account.username)
                        put("fullName", account.fullName)
                        put("roleKey", roleKey)
                        put("branch", account.branch.ifBlank { "الخرطوم" })
                        put("permissions", account.permissions.ifBlank { "صلاحية العرض" })
                        put("active", account.active)
                        if (emailKey.isNotEmpty()) put("emailKey", emailKey)
                        put("updatedAt", System.currentTimeMillis())
                        put("serverUpdatedAt", FieldValue.serverTimestamp())
                    },
                    SetOptions.merge()
                ).await()
            }.onFailure { e ->
                recordStructuredError("SYNC_CLUSTER_MEMBER", "erp_clusters/$cleanCluster/cluster_members/$cleanUid", e)
            }
        } else if (fbUser.uid.isNotBlank() && account.isSuperAdminAccount()) {
            ensureOwnClusterMemberRegistration(cleanCluster, account, fbUser.uid, fbUser.email)
        }
    }

    /**
     * يضمن وجود مستند عضوية `cluster_members/{uid}` للمستخدم المسجل عبر Firebase Auth
     * سواء عبر قفل المالك الأول `system_metadata/cluster_owner` أو دليل البريد المعتمد `email_directory`.
     */
    suspend fun ensureOwnClusterMemberRegistration(
        clusterCode: String,
        account: UserAccountEntity?,
        fbUid: String?,
        fbEmail: String? = null
    ) = withContext(Dispatchers.IO) {
        val currentUser = runCatching { Firebase.auth.currentUser }.getOrNull() ?: return@withContext
        val uid = fbUid?.takeIf { it.isNotBlank() } ?: currentUser.uid
        if (uid.isBlank()) return@withContext

        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        if (cleanCluster.equals(DEPRECATED_V16_CLUSTER, ignoreCase = true)) return@withContext
        val cleanUid = safeId(uid, uid)
        val cRef = clusterRef(cleanCluster)
        val memberRef = cRef.collection("cluster_members").document(cleanUid)

        try {
            val existing = runCatching { memberRef.get(Source.SERVER).await() }.getOrNull()
            if (existing != null && existing.exists()) {
                return@withContext
            }

            val resolvedEmail = (fbEmail?.takeIf { it.isNotBlank() }
                ?: currentUser.email?.takeIf { it.isNotBlank() }
                ?: account?.phoneOrEmail.orEmpty()).trim().lowercase()

            val ownerRef = cRef.collection("system_metadata").document("cluster_owner")
            val ownerSnap = runCatching { ownerRef.get(Source.SERVER).await() }.getOrNull()
            val ownerExists = ownerSnap != null && ownerSnap.exists()
            val existingOwnerUid = if (ownerExists) ownerSnap?.safeString("ownerUid").orEmpty() else ""

            val canBootstrapAdmin = account?.isSuperAdminAccount() == true ||
                account?.isSystemManager() == true ||
                resolvedEmail == "oaw7ab@gmail.com" ||
                resolvedEmail == "admin@pmp-paints.com"

            if (ownerExists && existingOwnerUid == cleanUid) {
                val adminCode = account?.let { safeId(it.userCode, "USR-ADMIN") } ?: "USR-ADMIN"
                memberRef.set(
                    mapOf(
                        "uid" to cleanUid,
                        "userCode" to adminCode,
                        "roleKey" to "ADMIN",
                        "branch" to "ALL",
                        "permissions" to (account?.permissions ?: ""),
                        "active" to true,
                        "updatedAt" to System.currentTimeMillis()
                    )
                ).await()
                return@withContext
            }

            if (ownerSnap != null && !ownerExists && canBootstrapAdmin) {
                // CRITICAL: Write cluster_members/{uid} BEFORE system_metadata/cluster_owner
                // so Firestore security rule checks pass on first-time cluster bootstrap.
                val adminCode = account?.let { safeId(it.userCode, "USR-ADMIN") } ?: "USR-ADMIN"
                memberRef.set(
                    mapOf(
                        "uid" to cleanUid,
                        "userCode" to adminCode,
                        "roleKey" to "ADMIN",
                        "branch" to "ALL",
                        "permissions" to (account?.permissions ?: ""),
                        "active" to true,
                        "updatedAt" to System.currentTimeMillis()
                    )
                ).await()
                ownerRef.set(
                    mapOf(
                        "ownerUid" to cleanUid,
                        "userCode" to adminCode,
                        "createdAt" to System.currentTimeMillis()
                    )
                ).await()
                return@withContext
            }

            if (resolvedEmail.contains("@")) {
                val emailKey = buildEmailDirectoryKey(resolvedEmail)
                val dirSnap = runCatching {
                    cRef.collection("email_directory").document(emailKey).get(Source.SERVER).await()
                }.getOrNull()
                if (dirSnap != null && dirSnap.exists()) {
                    val isDirActive = dirSnap.getBoolean("active") != false
                    if (isDirActive) {
                        val fallbackCode = account?.let { safeId(it.userCode, "USR-${it.id}") } ?: "EMP-${cleanUid.take(6)}"
                        val fallbackRoleKey = account?.let { resolveCanonicalRbacTier(it) } ?: "EMPLOYEE"
                        val payload = mapOf(
                            "uid" to cleanUid,
                            "emailKey" to emailKey,
                            "userCode" to (dirSnap.safeString("userCode") ?: fallbackCode),
                            "roleKey" to (dirSnap.safeString("roleKey") ?: fallbackRoleKey),
                            "branch" to (dirSnap.safeString("branch") ?: account?.branch?.ifBlank { "الخرطوم" } ?: "الخرطوم"),
                            "permissions" to (dirSnap.safeString("permissions") ?: account?.permissions ?: "صلاحية العرض"),
                            "active" to true,
                            "updatedAt" to System.currentTimeMillis()
                        )
                        memberRef.set(payload).await()
                        return@withContext
                    }
                }
            }
        } catch (e: Exception) {
            recordStructuredError("ENSURE_CLUSTER_MEMBER", "erp_clusters/$cleanCluster/cluster_members/$cleanUid", e)
        }
    }

    suspend fun resolveClusterMemberRoleKey(
        clusterCode: String,
        uid: String
    ): String? = withContext(Dispatchers.IO) {
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val cleanUid = safeId(uid, uid)
        if (cleanUid.isBlank()) return@withContext null
        val ref = clusterRef(cleanCluster).collection("cluster_members").document(cleanUid)
        val snap = runCatching { ref.get(Source.SERVER).await() }
            .getOrElse { runCatching { ref.get().await() }.getOrNull() }
        if (snap != null && snap.exists() && snap.getBoolean("active") != false) {
            snap.safeString("roleKey")?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    /**
     * Immediately pushes a single UserAccountEntity to Google Cloud Firestore so that
     * account creation, Google Sign-In pending requests, and Manager permission updates
     * are instantly synchronized across all connected devices and users.
     */
    suspend fun syncSingleUserToCloud(
        clusterCode: String,
        user: UserAccountEntity
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val fbUser = runCatching { Firebase.auth.currentUser }.getOrNull()
                ?: throw SecurityException("جلسة المصادقة السحابية غير نشطة — يرجى تفعيل جلسة المصادقة السحابية (بالبريد الإلكتروني أو الدخول الاختياري عبر Google)")
            val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
            ensureOwnClusterMemberRegistration(cleanCluster, user, fbUser.uid, fbUser.email)
            val roleKey = resolveClusterMemberRoleKey(cleanCluster, fbUser.uid)

            val cRef = clusterRef(cleanCluster)
            val docId = safeId(user.userCode, "USR-${user.id}")
            val data = mapOf(
                "id" to user.id,
                "userCode" to docId,
                "fullName" to user.fullName,
                "username" to user.username,
                "phoneOrEmail" to user.phoneOrEmail,
                "pinOrPassword" to user.pinOrPassword,
                "role" to user.role,
                "branch" to user.branch,
                "permissions" to user.permissions,
                "serverSyncToken" to user.serverSyncToken,
                "remoteServerId" to user.remoteServerId,
                "syncStatus" to user.syncStatus,
                "serverEndpoint" to user.serverEndpoint,
                "active" to user.active,
                "createdAt" to user.createdAt,
                "serverUpdatedBy" to currentActorId,
                "serverUpdatedAt" to FieldValue.serverTimestamp()
            )
            val isPrivilegedTarget = user.isSuperAdminAccount() ||
                user.isSystemManager() ||
                EnterpriseRbacManager.isPrivilegedRole(user.role) ||
                docId.equals("USR-ADMIN", ignoreCase = true) ||
                user.username.equals("admin", ignoreCase = true)

            if (user.syncStatus == "PENDING_MANAGER_APPROVAL") {
                cRef.collection("pending_requests").document(docId).set(data, SetOptions.merge()).await()
                if (roleKey == "ADMIN" || (roleKey == "MANAGER" && !isPrivilegedTarget)) {
                    cRef.collection("user_accounts").document(docId).set(data, SetOptions.merge()).await()
                }
            } else {
                if (roleKey == "ADMIN" || (roleKey == "MANAGER" && !isPrivilegedTarget)) {
                    cRef.collection("user_accounts").document(docId).set(data, SetOptions.merge()).await()
                    runCatching { cRef.collection("pending_requests").document(docId).delete().await() }
                    syncRbacDirectoryForAccount(cleanCluster, user)
                } else if (roleKey != null) {
                    val userDocRef = cRef.collection("user_accounts").document(docId)
                    val exists = runCatching { userDocRef.get().await().exists() }.getOrDefault(false)
                    if (exists) {
                        val selfData = mapOf(
                            "fullName" to user.fullName.ifBlank { docId },
                            "phoneOrEmail" to user.phoneOrEmail,
                            "pinOrPassword" to user.pinOrPassword,
                            "serverSyncToken" to user.serverSyncToken,
                            "updatedAt" to System.currentTimeMillis(),
                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                        )
                        userDocRef.set(selfData, SetOptions.merge()).await()
                    }
                }
            }
            docId
        }
    }

    suspend fun deleteUserFromCloud(
        clusterCode: String,
        userCode: String,
        username: String = "",
        email: String = "",
        userId: Long = 0L
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            DeletedAccountsRegistry.registerDeleted(userCode, username, email)
            val clustersToPurge = linkedSetOf(
                safeId(clusterCode, DEFAULT_CLUSTER),
                DEFAULT_CLUSTER
            )

            val candidateDocIds = linkedSetOf<String>()
            if (userCode.isNotBlank()) candidateDocIds.add(safeId(userCode, userCode))
            if (userId > 0L) {
                candidateDocIds.add(safeId(userCode, "USR-$userId"))
                candidateDocIds.add("USR-$userId")
            }
            if (username.isNotBlank()) candidateDocIds.add(safeId(username, username))

            val actor = currentActorId

            for (cCode in clustersToPurge) {
                val cRef = clusterRef(cCode)
                // 1. Delete by known document IDs
                for (docId in candidateDocIds) {
                    runCatching { cRef.collection("user_accounts").document(docId).delete().await() }
                    runCatching { cRef.collection("pending_requests").document(docId).delete().await() }
                }

                // 2. Query and delete any matching records by userCode
                if (userCode.isNotBlank()) {
                    runCatching {
                        val snap = cRef.collection("user_accounts").whereEqualTo("userCode", userCode).get().await()
                        snap.documents.forEach { doc -> doc.reference.delete().await() }
                    }
                    runCatching {
                        val snap = cRef.collection("pending_requests").whereEqualTo("userCode", userCode).get().await()
                        snap.documents.forEach { doc -> doc.reference.delete().await() }
                    }
                }

                // 3. Query and delete any matching records by username
                if (username.isNotBlank()) {
                    runCatching {
                        val snap = cRef.collection("user_accounts").whereEqualTo("username", username).get().await()
                        snap.documents.forEach { doc -> doc.reference.delete().await() }
                    }
                    runCatching {
                        val snap = cRef.collection("pending_requests").whereEqualTo("username", username).get().await()
                        snap.documents.forEach { doc -> doc.reference.delete().await() }
                    }
                }

                // 4. Record permanent tombstone in cloud deleted_users collection
                val tombstoneId = safeId(userCode.ifBlank { username }, "DEL-$userId")
                runCatching {
                    val tombstoneData = mapOf(
                        "userCode" to userCode,
                        "username" to username,
                        "email" to email,
                        "deleted" to true,
                        "deletedAt" to FieldValue.serverTimestamp(),
                        "deletedBy" to actor
                    )
                    cRef.collection("deleted_users").document(tombstoneId).set(tombstoneData, SetOptions.merge()).await()
                }
            }

            // 5. Also purge from root linked_accounts and record in root deleted_accounts
            for (docId in candidateDocIds) {
                runCatching { firestore.collection("linked_accounts").document(docId).delete().await() }
                runCatching {
                    firestore.collection("deleted_accounts").document(docId).set(
                        mapOf(
                            "userCode" to userCode,
                            "username" to username,
                            "email" to email,
                            "deleted" to true,
                            "deletedAt" to FieldValue.serverTimestamp(),
                            "deletedBy" to actor
                        ),
                        SetOptions.merge()
                    ).await()
                }
            }

            // 6. Record detailed Firestore Audit Log to debug synchronization and track user deletion
            runCatching {
                recordCloudAuditLog(
                    clusterCode = clusterCode,
                    action = "DELETE_USER_PERMANENT",
                    targetCode = userCode.ifBlank { username },
                    actor = actor,
                    detail = "حذف حساب المستخدم ($userCode / $username) نهائياً مع تسجيل شاهد الحذف (Tombstone) وتطهير الكتل",
                    metadata = mapOf(
                        "deletedUserCode" to userCode,
                        "deletedUsername" to username,
                        "deletedEmail" to email,
                        "deletedUserId" to userId,
                        "tombstonePurged" to true
                    )
                )
            }

            Unit
        }
    }

    /**
     * Records a secure audit log entry in Google Cloud Firestore (in the cluster's audit_logs collection).
     * Specifically tracks critical user actions such as deletions, permissions changes, and sync events.
     */
    suspend fun recordCloudAuditLog(
        clusterCode: String,
        action: String,
        targetCode: String,
        actor: String,
        detail: String,
        metadata: Map<String, Any> = emptyMap()
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cRef = clusterRef(clusterCode)
            val logId = "LOG-" + System.currentTimeMillis() + "-" + (100..999).random()
            val data = mutableMapOf<String, Any>(
                "logId" to logId,
                "action" to action,
                "targetCode" to targetCode,
                "actor" to actor,
                "detail" to detail,
                "deviceNode" to currentActorId,
                "createdAt" to System.currentTimeMillis(),
                "serverTimestamp" to FieldValue.serverTimestamp()
            )
            data.putAll(metadata)
            cRef.collection("audit_logs").document(logId).set(data).await()
            if (clusterCode != DEFAULT_CLUSTER) {
                runCatching {
                    clusterRef(DEFAULT_CLUSTER).collection("audit_logs").document(logId).set(data).await()
                }
            }
            logId
        }
    }

    /**
     * Pulls all user accounts from Google Cloud Firestore across all clusters and merges them into
     * the local Room database, preserving the local device's active session while updating permissions,
     * roles, and new users. Guaranteed to aggregate accounts from all versions and strictly deduplicate.
     */
    suspend fun pullAndMergeCloudUsers(
        clusterCode: String,
        dao: PmpDao,
        currentActiveUserId: Long? = null,
        deviceId: String = "ANDROID-CLIENT"
    ): Result<Int> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            internalPullAndMergeCloudUsers(clusterCode, dao, currentActiveUserId, deviceId)
        }
    }

    private suspend fun internalPullAndMergeCloudUsers(
        clusterCode: String,
        dao: PmpDao,
        currentActiveUserId: Long? = null,
        deviceId: String = "ANDROID-CLIENT"
    ): Result<Int> {
        return runCatching {
            // Clean local duplicate user accounts first
            runCatching { dao.removeDuplicateUserAccounts() }

            val fbUser = runCatching { Firebase.auth.currentUser }.getOrNull()
            if (fbUser == null) {
                return@runCatching 0
            }

            val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
            val primaryClusters = linkedSetOf(
                cleanCluster,
                DEFAULT_CLUSTER
            )
            val currentLocalUsersSnapshot = dao.getUserAccountsDirect()
            val activeLocalAcc = currentLocalUsersSnapshot.firstOrNull {
                (currentActiveUserId != null && it.id == currentActiveUserId) || it.isCurrentSession
            } ?: fbUser.email?.let { em ->
                currentLocalUsersSnapshot.firstOrNull { it.phoneOrEmail.equals(em, ignoreCase = true) }
            }
            ensureOwnClusterMemberRegistration(cleanCluster, activeLocalAcc, fbUser.uid, fbUser.email)
            val cloudRoleKey = resolveClusterMemberRoleKey(cleanCluster, fbUser.uid)

            // 0. Pull tombstones with safe timeout (only when active cluster member)
            if (cloudRoleKey != null) {
                for (code in primaryClusters) {
                    runCatching {
                        val delSnap = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                            clusterRef(code).collection("deleted_users").get().await()
                        }
                        val delList = delSnap?.documents?.mapNotNull { it.data }
                        if (!delList.isNullOrEmpty()) {
                            DeletedAccountsRegistry.mergeRemoteTombstones(delList)
                        }
                    }
                }
                runCatching {
                    val rootDelSnap = kotlinx.coroutines.withTimeoutOrNull(1500L) {
                        firestore.collection("deleted_accounts").get().await()
                    }
                    val rootDelList = rootDelSnap?.documents?.mapNotNull { it.data }
                    if (!rootDelList.isNullOrEmpty()) {
                        DeletedAccountsRegistry.mergeRemoteTombstones(rootDelList)
                    }
                }
            }

            // Purge any local Room user matching any tombstone (except active session on this device)
            val currentLocalUsers = dao.getUserAccountsDirect()
            for (u in currentLocalUsers) {
                if (DeletedAccountsRegistry.isDeleted(u.userCode, u.username, u.phoneOrEmail)) {
                    val isActiveSessionOnDevice = (currentActiveUserId != null && u.id == currentActiveUserId) || u.isCurrentSession
                    if (!isActiveSessionOnDevice) {
                        dao.deleteUserAccount(u.id)
                        if (u.userCode.isNotBlank()) dao.deleteUserAccountByCode(u.userCode)
                        if (u.username.isNotBlank()) dao.deleteUserAccountByUsername(u.username)
                    }
                }
            }

            val localUsers = dao.getUserAccountsDirect().toMutableList()
            val seenKeys = mutableSetOf<String>()
            var mergedCount = 0

            // Collect user documents from primary clusters only for Admin / Manager (aligned with firestore.rules list rule)
            val allCloudDocs = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
            if (cloudRoleKey == "ADMIN" || cloudRoleKey == "MANAGER") {
                for (code in primaryClusters) {
                    runCatching {
                        val snap = kotlinx.coroutines.withTimeoutOrNull(2500L) {
                            clusterRef(code).collection("user_accounts").get().await()
                        }
                        if (snap != null) {
                            allCloudDocs.addAll(snap.documents)
                        }
                    }
                }
            } else if (cloudRoleKey != null && activeLocalAcc?.userCode?.isNotBlank() == true) {
                // Non-Admin active members only fetch their own single user_accounts document via allow get
                val ownDocId = safeId(activeLocalAcc.userCode, "USR-${activeLocalAcc.id}")
                runCatching {
                    val ownSnap = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                        clusterRef(cleanCluster).collection("user_accounts").document(ownDocId).get().await()
                    }
                    if (ownSnap != null && ownSnap.exists()) {
                        allCloudDocs.add(ownSnap)
                    }
                }
            }

            // Query linked_accounts strictly filtered by ownerId / linkedUid (rules are not filters)
            runCatching {
                val ownedSnap = kotlinx.coroutines.withTimeoutOrNull(1500L) {
                    firestore.collection("linked_accounts").whereEqualTo("ownerId", fbUser.uid).get().await()
                }
                if (ownedSnap != null) {
                    allCloudDocs.addAll(ownedSnap.documents)
                }
            }
            runCatching {
                val assignedSnap = kotlinx.coroutines.withTimeoutOrNull(1500L) {
                    firestore.collection("linked_accounts").whereEqualTo("linkedUid", fbUser.uid).get().await()
                }
                if (assignedSnap != null) {
                    allCloudDocs.addAll(assignedSnap.documents)
                }
            }

            if (allCloudDocs.isEmpty()) return@runCatching 0

            for (doc in allCloudDocs) {
                val code = doc.getString("userCode") ?: doc.id
                val uname = doc.getString("username") ?: ""
                val email = doc.getString("phoneOrEmail") ?: ""
                val remoteUid = doc.getString("remoteServerId") ?: ""

                val isExplicitlyDeleted = doc.getBoolean("deleted") == true
                if (isExplicitlyDeleted || DeletedAccountsRegistry.isDeleted(code, uname, email)) {
                    // Proactively purge zombie document from cloud so no other devices resurrect it
                    runCatching { doc.reference.delete() }
                    continue
                }

                val dedupKey = uname.trim().lowercase().ifBlank { code.trim().lowercase() }
                if (dedupKey.isNotBlank() && dedupKey in seenKeys) {
                    continue
                }
                if (dedupKey.isNotBlank()) {
                    seenKeys.add(dedupKey)
                }

                val existing = localUsers.firstOrNull {
                    it.userCode.equals(code, ignoreCase = true) ||
                        (uname.isNotBlank() && it.username.equals(uname, ignoreCase = true)) ||
                        (email.isNotBlank() && it.phoneOrEmail.equals(email, ignoreCase = true)) ||
                        (remoteUid.isNotBlank() && !remoteUid.startsWith("SRV-NODE") && it.remoteServerId == remoteUid)
                }

                val isThisUserActiveSession = if (currentActiveUserId != null) {
                    existing?.id != null && existing.id == currentActiveUserId
                } else {
                    existing?.isCurrentSession == true
                }

                // Never let a cloud snapshot mark the active user on this device as inactive
                val resolvedActive = if (isThisUserActiveSession) true else (doc.getBoolean("active") ?: existing?.active ?: true)

                val entity = UserAccountEntity(
                    id = existing?.id ?: 0L,
                    userCode = code,
                    fullName = doc.getString("fullName") ?: existing?.fullName ?: uname,
                    username = uname.ifBlank { existing?.username ?: code.lowercase() },
                    phoneOrEmail = email.ifBlank { existing?.phoneOrEmail ?: "" },
                    pinOrPassword = doc.getString("pinOrPassword") ?: existing?.pinOrPassword ?: "1234",
                    role = doc.getString("role") ?: existing?.role ?: "محاسب خزنة ومبيعات",
                    branch = doc.getString("branch") ?: existing?.branch ?: "فرع السودان (SD-001)",
                    permissions = doc.getString("permissions") ?: existing?.permissions ?: "",
                    serverSyncToken = doc.getString("serverSyncToken") ?: existing?.serverSyncToken ?: "SYNC-$code",
                    remoteServerId = remoteUid.ifBlank { existing?.remoteServerId ?: "" },
                    syncStatus = doc.getString("syncStatus") ?: existing?.syncStatus ?: "CLOUD_SYNCED",
                    serverEndpoint = doc.getString("serverEndpoint") ?: "https://firestore.googleapis.com/v1",
                    isCurrentSession = isThisUserActiveSession,
                    active = resolvedActive,
                    createdAt = doc.getLong("createdAt") ?: existing?.createdAt ?: System.currentTimeMillis()
                )

                if (existing != null) {
                    if (existing != entity) {
                        dao.updateUserAccount(entity)
                    }
                    val idx = localUsers.indexOf(existing)
                    if (idx >= 0) localUsers[idx] = entity
                } else {
                    val newId = dao.insertUserAccount(entity.copy(id = 0L))
                    localUsers.add(entity.copy(id = newId))
                }

                // Propagate unified account only when signed in as Admin for cross-cluster consolidation
                if (cloudRoleKey == "ADMIN" && cleanCluster != DEFAULT_CLUSTER) {
                    runCatching {
                        val userMap = mapOf(
                            "id" to entity.id,
                            "userCode" to entity.userCode,
                            "fullName" to entity.fullName,
                            "username" to entity.username,
                            "phoneOrEmail" to entity.phoneOrEmail,
                            "pinOrPassword" to entity.pinOrPassword,
                            "role" to entity.role,
                            "branch" to entity.branch,
                            "permissions" to entity.permissions,
                            "serverSyncToken" to entity.serverSyncToken,
                            "remoteServerId" to entity.remoteServerId,
                            "syncStatus" to entity.syncStatus,
                            "serverEndpoint" to entity.serverEndpoint,
                            "active" to entity.active,
                            "createdAt" to entity.createdAt,
                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                        )
                        clusterRef(DEFAULT_CLUSTER).collection("user_accounts").document(safeId(entity.userCode, "USR-${entity.id}")).set(userMap, SetOptions.merge()).await()
                    }
                }

                mergedCount++
            }

            // Post-merge cleanup: remove any historical duplicates in Room by username / userCode,
            // strictly guarding the active session user from being deleted
            val freshUsers = dao.getUserAccountsDirect()
            val userTracker = mutableSetOf<String>()
            for (u in freshUsers.sortedWith(compareByDescending<UserAccountEntity> { it.isCurrentSession || (currentActiveUserId != null && it.id == currentActiveUserId) }.thenBy { it.id })) {
                val key = u.username.trim().lowercase().ifBlank { u.userCode.trim().lowercase() }
                if (key.isNotBlank()) {
                    if (key in userTracker) {
                        if (u.id != currentActiveUserId && !u.isCurrentSession) {
                            dao.deleteUserAccount(u.id)
                        }
                    } else {
                        userTracker.add(key)
                    }
                }
            }

            recordDiagnostic(
                action = "PULL_USERS",
                status = "SUCCESS",
                count = mergedCount,
                message = "تم جلب وتحديث $mergedCount حساب من السيرفر مع حماية جلسة الموظف النشط",
                deviceId = deviceId
            )
            mergedCount
        }.onFailure { err ->
            recordDiagnostic(
                action = "PULL_USERS",
                status = "WARNING",
                count = 0,
                message = "تحذير أثناء جلب المستخدمين من السيرفر: ${err.message}",
                deviceId = deviceId
            )
        }
    }

    /**
     * Resolves and authenticates an existing user account directly from Google Cloud Firestore
     * across all historical clusters and collections. Called during login if the account was registered
     * on an older APK/release and is not yet in the local database.
     */
    /**
     * Hydrates the currently signed-in Firebase user's own ERP account from Firestore
     * (`email_directory/{emailKey}` -> `user_accounts/{userCode}` or `cluster_members/{uid}` -> `user_accounts/{userCode}`)
     * into Room so non-Admin/non-Manager registered employees are always recognized on fresh installs.
     */
    suspend fun pullSingleUserAccountFromCloud(
        clusterCode: String,
        dao: PmpDao,
        fbUid: String,
        fbEmail: String?
    ): UserAccountEntity? = withContext(Dispatchers.IO) {
        if (fbUid.isBlank()) return@withContext null
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val cleanUid = safeId(fbUid, fbUid)
        val cleanEmail = fbEmail?.trim()?.lowercase().orEmpty()
        val cRef = clusterRef(cleanCluster)

        runCatching {
            ensureOwnClusterMemberRegistration(cleanCluster, null, fbUid, cleanEmail)
        }

        var matchedDoc: com.google.firebase.firestore.DocumentSnapshot? = null
        var dirFallbackData: Map<String, Any?>? = null
        var resolvedCode = ""

        // 1. Check email_directory/{emailKey} -> user_accounts/{userCode}
        if (cleanEmail.contains("@")) {
            val emailKey = buildEmailDirectoryKey(cleanEmail)
            val dirSnap = runCatching {
                cRef.collection("email_directory").document(emailKey).get(Source.SERVER).await()
            }.getOrElse {
                runCatching { cRef.collection("email_directory").document(emailKey).get().await() }.getOrNull()
            }
            if (dirSnap != null && dirSnap.exists()) {
                dirFallbackData = dirSnap.data
                resolvedCode = dirSnap.getString("userCode")?.trim().orEmpty()
                if (resolvedCode.isNotBlank()) {
                    val userSnap = runCatching {
                        cRef.collection("user_accounts").document(safeId(resolvedCode, resolvedCode)).get(Source.SERVER).await()
                    }.getOrElse {
                        runCatching { cRef.collection("user_accounts").document(safeId(resolvedCode, resolvedCode)).get().await() }.getOrNull()
                    }
                    if (userSnap != null && userSnap.exists()) {
                        matchedDoc = userSnap
                    }
                }
            }
        }

        // 2. Check cluster_members/{uid}.userCode -> user_accounts/{userCode}
        if (matchedDoc == null) {
            val memberSnap = runCatching {
                cRef.collection("cluster_members").document(cleanUid).get(Source.SERVER).await()
            }.getOrElse {
                runCatching { cRef.collection("cluster_members").document(cleanUid).get().await() }.getOrNull()
            }
            if (memberSnap != null && memberSnap.exists()) {
                val memberCode = memberSnap.getString("userCode")?.trim().orEmpty()
                if (memberCode.isNotBlank()) {
                    resolvedCode = memberCode
                    val userSnap = runCatching {
                        cRef.collection("user_accounts").document(safeId(memberCode, memberCode)).get(Source.SERVER).await()
                    }.getOrElse {
                        runCatching { cRef.collection("user_accounts").document(safeId(memberCode, memberCode)).get().await() }.getOrNull()
                    }
                    if (userSnap != null && userSnap.exists()) {
                        matchedDoc = userSnap
                    }
                }
                if (dirFallbackData == null) {
                    dirFallbackData = memberSnap.data
                }
            }
        }

        // 3. Fallback to /linked_accounts/{uid}
        if (matchedDoc == null) {
            val linkedSnap = runCatching {
                firestore.collection("linked_accounts").document(cleanUid).get().await()
            }.getOrNull()
            if (linkedSnap != null && linkedSnap.exists()) {
                matchedDoc = linkedSnap
            }
        }

        val localUsers = runCatching { dao.getUserAccountsDirect() }.getOrDefault(emptyList())
        if (matchedDoc != null && matchedDoc!!.exists()) {
            val doc = matchedDoc!!
            val userCode = doc.safeString("userCode")?.ifBlank { doc.id } ?: doc.id
            val uname = doc.safeString("username")?.ifBlank { cleanEmail.substringBefore("@") } ?: userCode.lowercase()
            val email = doc.safeString("phoneOrEmail")?.ifBlank { cleanEmail } ?: cleanEmail
            val existing = localUsers.firstOrNull {
                it.userCode.equals(userCode, ignoreCase = true) ||
                    it.username.equals(uname, ignoreCase = true) ||
                    (email.isNotBlank() && it.phoneOrEmail.equals(email, ignoreCase = true)) ||
                    it.remoteServerId == fbUid
            }
            val entity = UserAccountEntity(
                id = existing?.id ?: 0L,
                userCode = userCode,
                fullName = doc.safeString("fullName")?.ifBlank { existing?.fullName ?: uname } ?: uname,
                username = uname,
                phoneOrEmail = email,
                pinOrPassword = doc.safeString("pinOrPassword")?.ifBlank { existing?.pinOrPassword ?: "1234" } ?: "1234",
                role = doc.safeString("role")?.ifBlank { existing?.role ?: "موظف" } ?: "موظف",
                branch = doc.safeString("branch")?.ifBlank { existing?.branch ?: "فرع السودان (SD-001)" } ?: "فرع السودان (SD-001)",
                permissions = doc.safeString("permissions") ?: existing?.permissions ?: "صلاحية العرض",
                serverSyncToken = doc.safeString("serverSyncToken")?.ifBlank { "SYNC-$userCode" } ?: "SYNC-$userCode",
                remoteServerId = fbUid,
                syncStatus = doc.safeString("syncStatus")?.ifBlank { "APPROVED_BY_MANAGER" } ?: "APPROVED_BY_MANAGER",
                serverEndpoint = doc.safeString("serverEndpoint") ?: "https://firestore.googleapis.com/v1",
                isCurrentSession = existing?.isCurrentSession ?: false,
                active = doc.safeBoolean("active") ?: true,
                createdAt = doc.safeLong("createdAt") ?: existing?.createdAt ?: System.currentTimeMillis()
            )
            val savedId = if (existing != null) {
                dao.updateUserAccount(entity)
                existing.id
            } else {
                dao.insertUserAccount(entity)
            }
            return@withContext entity.copy(id = savedId)
        }

        // 4. Synthesize from authoritative email_directory / cluster_members record if active
        val fallback = dirFallbackData
        if (fallback != null && (fallback["active"] as? Boolean) != false && resolvedCode.isNotBlank()) {
            val roleKey = fallback["roleKey"]?.toString()?.uppercase().orEmpty()
            val roleLabel = when (roleKey) {
                "ADMIN" -> "مدير النظام"
                "MANAGER" -> "المدير"
                "ACCOUNTANT" -> "المحاسب"
                "DEPUTY_ACCOUNTANT" -> "نائب محاسب"
                else -> "موظف"
            }
            val uname = fallback["username"]?.toString()?.ifBlank { null }
                ?: cleanEmail.substringBefore("@").ifBlank { resolvedCode.lowercase() }
            val existing = localUsers.firstOrNull {
                it.userCode.equals(resolvedCode, ignoreCase = true) ||
                    it.username.equals(uname, ignoreCase = true) ||
                    (cleanEmail.isNotBlank() && it.phoneOrEmail.equals(cleanEmail, ignoreCase = true))
            }
            val entity = UserAccountEntity(
                id = existing?.id ?: 0L,
                userCode = resolvedCode,
                fullName = fallback["fullName"]?.toString()?.ifBlank { existing?.fullName ?: uname } ?: uname,
                username = uname,
                phoneOrEmail = cleanEmail,
                pinOrPassword = existing?.pinOrPassword ?: com.example.util.PasswordSecurityUtil.hashPassword("1234"),
                role = roleLabel,
                branch = fallback["branch"]?.toString()?.ifBlank { "فرع السودان (SD-001)" } ?: "فرع السودان (SD-001)",
                permissions = fallback["permissions"]?.toString() ?: "صلاحية العرض",
                serverSyncToken = existing?.serverSyncToken ?: "SYNC-$resolvedCode",
                remoteServerId = fbUid,
                syncStatus = "APPROVED_BY_MANAGER",
                serverEndpoint = "https://firestore.googleapis.com/v1",
                isCurrentSession = existing?.isCurrentSession ?: false,
                active = true,
                createdAt = existing?.createdAt ?: System.currentTimeMillis()
            )
            val savedId = if (existing != null) {
                dao.updateUserAccount(entity)
                existing.id
            } else {
                dao.insertUserAccount(entity)
            }
            return@withContext entity.copy(id = savedId)
        }

        null
    }

    suspend fun findAndAuthenticateUserInCloud(
        identity: String,
        passwordOrPin: String,
        dao: PmpDao
    ): Result<UserAccountEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val fbUser = runCatching { Firebase.auth.currentUser }.getOrNull()
                ?: throw Exception("جلسة المصادقة السحابية غير نشطة حالياً — يتم التحقق عبر الحساب المحلي، ويمكن تفعيل المزامنة السحابية لاحقاً بالبريد الإلكتروني أو Google اختيارياً")
            val cleanIdentity = identity.trim().lowercase()
            val cleanSecret = passwordOrPin.trim()

            // Ensure cluster membership is registered/recovered BEFORE querying user_accounts
            runCatching {
                ensureOwnClusterMemberRegistration(DEFAULT_CLUSTER, null, fbUser.uid, fbUser.email)
            }

            val cRef = clusterRef(DEFAULT_CLUSTER)
            var matchedDoc: com.google.firebase.firestore.DocumentSnapshot? = null

            val roleKey = resolveClusterMemberRoleKey(DEFAULT_CLUSTER, fbUser.uid)
            if (roleKey == "ADMIN" || roleKey == "MANAGER") {
                runCatching {
                    val snap = cRef.collection("user_accounts").get().await()
                    for (doc in snap.documents) {
                        val uname = doc.getString("username")?.trim()?.lowercase() ?: ""
                        val email = doc.getString("phoneOrEmail")?.trim()?.lowercase() ?: ""
                        val code = doc.getString("userCode")?.trim()?.lowercase() ?: doc.id.lowercase()
                        if (uname == cleanIdentity || email == cleanIdentity || code == cleanIdentity || (cleanIdentity.contains("@") && email.startsWith(cleanIdentity))) {
                            matchedDoc = doc
                            break
                        }
                    }
                }
            }

            // For ALL roles (including ACCOUNTANT, DEPUTY_ACCOUNTANT, EMPLOYEE): direct self-lookups permitted by firestore.rules
            if (matchedDoc == null) {
                val candidateEmails = listOfNotNull(
                    cleanIdentity.takeIf { it.contains("@") },
                    fbUser.email?.trim()?.lowercase()?.takeIf { it.contains("@") }
                ).distinct()
                for (em in candidateEmails) {
                    val emailKey = buildEmailDirectoryKey(em)
                    val dirSnap = runCatching { cRef.collection("email_directory").document(emailKey).get().await() }.getOrNull()
                    val dirCode = dirSnap?.getString("userCode")?.trim().orEmpty()
                    if (dirCode.isNotBlank()) {
                        val directSnap = runCatching {
                            cRef.collection("user_accounts").document(safeId(dirCode, dirCode)).get().await()
                        }.getOrNull()
                        if (directSnap != null && directSnap.exists()) {
                            val uname = directSnap.getString("username")?.trim()?.lowercase() ?: ""
                            val email = directSnap.getString("phoneOrEmail")?.trim()?.lowercase() ?: ""
                            val code = directSnap.getString("userCode")?.trim()?.lowercase() ?: directSnap.id.lowercase()
                            if (uname == cleanIdentity || email == cleanIdentity || code == cleanIdentity) {
                                matchedDoc = directSnap
                                break
                            }
                        }
                    }
                }
            }

            // Direct lookup via cluster_members/{uid}.userCode or direct userCode document ID
            if (matchedDoc == null) {
                val memberSnap = runCatching {
                    cRef.collection("cluster_members").document(safeId(fbUser.uid, fbUser.uid)).get().await()
                }.getOrNull()
                val candidateDocIds = listOfNotNull(
                    memberSnap?.getString("userCode")?.trim()?.takeIf { it.isNotBlank() },
                    safeId(identity.trim().uppercase(), "").takeIf { it.isNotBlank() }
                ).distinct()
                for (candId in candidateDocIds) {
                    val directSnap = runCatching {
                        cRef.collection("user_accounts").document(safeId(candId, candId)).get().await()
                    }.getOrNull()
                    if (directSnap != null && directSnap.exists()) {
                        val uname = directSnap.getString("username")?.trim()?.lowercase() ?: ""
                        val email = directSnap.getString("phoneOrEmail")?.trim()?.lowercase() ?: ""
                        val code = directSnap.getString("userCode")?.trim()?.lowercase() ?: directSnap.id.lowercase()
                        if (uname == cleanIdentity || email == cleanIdentity || code == cleanIdentity) {
                            matchedDoc = directSnap
                            break
                        }
                    }
                }
            }

            if (matchedDoc == null) {
                // Check strictly scoped linked_accounts queries matching security rules
                runCatching {
                    val assignedSnap = firestore.collection("linked_accounts")
                        .whereEqualTo("linkedUid", fbUser.uid).get().await()
                    val ownedSnap = firestore.collection("linked_accounts")
                        .whereEqualTo("ownerId", fbUser.uid).get().await()
                    for (doc in (assignedSnap.documents + ownedSnap.documents)) {
                        val uname = doc.getString("username")?.trim()?.lowercase() ?: ""
                        val email = doc.getString("phoneOrEmail")?.trim()?.lowercase() ?: ""
                        val code = doc.getString("userCode")?.trim()?.lowercase() ?: doc.id.lowercase()
                        if (uname == cleanIdentity || email == cleanIdentity || code == cleanIdentity) {
                            matchedDoc = doc
                            break
                        }
                    }
                }
            }

            if (matchedDoc == null) {
                throw Exception("لم يتم العثور على حساب مطابق ($identity) على السيرفر المركزي أو محلياً.")
            }

            val doc = matchedDoc!!
            val storedPin = doc.getString("pinOrPassword") ?: "1234"
            val active = doc.getBoolean("active") ?: true
            val syncStatus = doc.getString("syncStatus") ?: "CLOUD_SYNCED"

            if (!active) {
                throw Exception("هذا الحساب موقوف أو غير مفعل على السيرفر، يرجى مراجعة مدير النظام.")
            }
            if (syncStatus == "PENDING_MANAGER_APPROVAL") {
                throw Exception("هذا الحساب بانتظار موافقة مدير النظام وتحديد الصلاحيات أولاً.")
            }
            if (!com.example.util.PasswordSecurityUtil.verifyPassword(cleanSecret, storedPin)) {
                throw Exception("كلمة المرور غير صحيحة للحساب المسجل على السيرفر.")
            }

            val upgradedPin = if (com.example.util.PasswordSecurityUtil.needsUpgradeToPbkdf2(storedPin)) {
                com.example.util.PasswordSecurityUtil.hashPassword(cleanSecret)
            } else {
                storedPin
            }

            val userCode = doc.getString("userCode") ?: doc.id
            val uname = doc.getString("username") ?: cleanIdentity
            val email = doc.getString("phoneOrEmail") ?: ""

            // Check if user already exists in Room
            val localUsers = dao.getUserAccountsDirect()
            val existing = localUsers.firstOrNull {
                it.userCode.equals(userCode, ignoreCase = true) ||
                    it.username.equals(uname, ignoreCase = true) ||
                    (email.isNotBlank() && it.phoneOrEmail.equals(email, ignoreCase = true))
            }

            val entity = UserAccountEntity(
                id = existing?.id ?: 0L,
                userCode = userCode,
                fullName = doc.getString("fullName") ?: uname,
                username = uname,
                phoneOrEmail = email,
                pinOrPassword = upgradedPin,
                role = doc.getString("role") ?: "محاسب خزنة ومبيعات",
                branch = doc.getString("branch") ?: "فرع السودان (SD-001)",
                permissions = doc.getString("permissions") ?: "المبيعات,الخزن,الأسعار,تصدير PDF",
                serverSyncToken = doc.getString("serverSyncToken") ?: "SYNC-$userCode",
                remoteServerId = doc.getString("remoteServerId") ?: "",
                syncStatus = syncStatus,
                serverEndpoint = doc.getString("serverEndpoint") ?: "https://firestore.googleapis.com/v1",
                isCurrentSession = true,
                active = true,
                createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
            )

            val savedId = if (existing != null) {
                dao.updateUserAccount(entity)
                existing.id
            } else {
                dao.insertUserAccount(entity)
            }
            dao.setActiveSessionUser(savedId)
            entity.copy(id = savedId, isCurrentSession = true)
        }
    }

    /**
     * Uploads all local database records (Users, Invoices, Movements, Transfers, Customers,
     * Products, Cashboxes, Warehouses) to Google Cloud Firestore so that data is permanently
     * saved on the server and synchronized with all users on Android and Windows clients.
     */
    suspend fun syncAllLocalToCloud(
        clusterCode: String,
        dao: PmpDao,
        isManager: Boolean = true,
        activeUserId: Long? = null,
        deviceId: String = "ANDROID-CLIENT",
        invDao: ErpInventoryDao? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            runCatching {
                val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
                if (cleanCluster.equals(DEPRECATED_V16_CLUSTER, ignoreCase = true)) {
                    throw SecurityException("تم حظر الكتابة إلى الكلاستر المهجور $DEPRECATED_V16_CLUSTER لحماية الصلاحيات.")
                }

                // Require active Firebase Auth session before attempting protected Firestore writes
                val fbCurrentUser = runCatching { Firebase.auth.currentUser }.getOrNull()
                    ?: throw SecurityException(
                        "جلسة المصادقة السحابية غير نشطة — يعمل النظام محلياً بأمان، ولتفعيل المزامنة السحابية سجّل الدخول بالبريد الإلكتروني أو عبر Google اختيارياً."
                    )

                // Deduplicate local cashboxes, movements, and user accounts before pushing
                runCatching {
                    dao.removeDuplicateCashboxes()
                    dao.removeDuplicateMovements()
                    dao.removeDuplicateUserAccounts()
                }

                val actor = currentActorId
                val cRef = clusterRef(cleanCluster)

                // 0. Ensure active Firebase session has an authoritative cluster_members/{uid} registration
                val users = dao.getUserAccountsDirect()
                val activeLocalAccount = users.firstOrNull { it.id == activeUserId }
                    ?: users.firstOrNull { it.isCurrentSession }
                    ?: fbCurrentUser.email?.let { em ->
                        users.firstOrNull { it.phoneOrEmail.equals(em, ignoreCase = true) }
                    }
                ensureOwnClusterMemberRegistration(cleanCluster, activeLocalAccount, fbCurrentUser.uid, fbCurrentUser.email)

                val cloudRoleKey = resolveClusterMemberRoleKey(cleanCluster, fbCurrentUser.uid)
                    ?: throw SecurityException(
                        "الحساب الحالي (${fbCurrentUser.email ?: fbCurrentUser.uid.take(8)}) غير مربوط بدليل عضوية الكلاستر (cluster_members) على السيرفر — يرجى اعتماد البريد في إدارة المستخدمين."
                    )
                val isCloudAdmin = cloudRoleKey == "ADMIN"
                val isCloudManager = cloudRoleKey == "MANAGER"
                val isCloudAccountant = cloudRoleKey == "ACCOUNTANT"
                val isCloudDeputy = cloudRoleKey == "DEPUTY_ACCOUNTANT"
                val canManageMasterCatalogs = isCloudAdmin || isCloudManager || isCloudAccountant || isCloudDeputy

                val stepSummaries = mutableListOf<SyncStepSummary>()
                var syncedCount = 0
                var criticalFailure: Throwable? = null
                var anyFailure: Throwable? = null

                suspend fun runSyncStep(
                    stepName: String,
                    collectionName: String,
                    critical: Boolean = false,
                    block: suspend () -> Int
                ) {
                    val colPath = "erp_clusters/$cleanCluster/$collectionName"
                    try {
                        val committed = block()
                        syncedCount += committed
                        stepSummaries.add(
                            SyncStepSummary(
                                stepName = stepName,
                                collectionPath = colPath,
                                syncedCount = committed,
                                success = true,
                                message = if (committed > 0) "تم حفظ $committed سجل بنجاح" else "لا توجد سجلات جديدة للرفع"
                            )
                        )
                    } catch (e: Throwable) {
                        if (anyFailure == null) anyFailure = e
                        if (critical && criticalFailure == null) criticalFailure = e
                        val entry = recordStructuredError(
                            operation = "SYNC_${collectionName.uppercase(java.util.Locale.US)}",
                            collectionPath = colPath,
                            throwable = e,
                            deviceId = deviceId
                        )
                        stepSummaries.add(
                            SyncStepSummary(
                                stepName = stepName,
                                collectionPath = colPath,
                                syncedCount = 0,
                                success = false,
                                message = "${entry.category.arabicTitle}: ${entry.sanitizedMessage}"
                            )
                        )
                    }
                }

                // 0a. Sync tombstones for deleted users (Admin/Manager only; never write USR-ADMIN tombstone as non-Admin)
                if (isCloudAdmin || isCloudManager) {
                    runSyncStep("مزامنة شواهد حذف الحسابات", "deleted_users", critical = false) {
                        val tombstones = DeletedAccountsRegistry.getAllTombstoneRecords().filterNot { t ->
                            val tCode = (t["userCode"] as? String).orEmpty()
                            val tUname = (t["username"] as? String).orEmpty()
                            !isCloudAdmin && (tCode.equals("USR-ADMIN", ignoreCase = true) || tUname.equals("admin", ignoreCase = true))
                        }
                        var committed = 0
                        tombstones.chunked(300).forEach { chunk ->
                            val batch = firestore.batch()
                            var chunkWrites = 0
                            for (t in chunk) {
                                val tCode = t["userCode"] as? String ?: ""
                                val tUname = t["username"] as? String ?: ""
                                val tDocId = safeId(tCode.ifBlank { tUname }, "DEL")
                                if (tDocId.isNotBlank()) {
                                    batch.set(cRef.collection("deleted_users").document(tDocId), t, SetOptions.merge())
                                    chunkWrites++
                                }
                            }
                            if (chunkWrites > 0) {
                                batch.commit().await()
                                committed += chunkWrites
                            }
                        }
                        committed
                    }
                }

                // 0b. مزامنة شواهد الحذف الشاملة لكافة السجلات وتطهير المحلي
                runCatching {
                    val epochSnap = cRef.collection("system_metadata").document("cluster_epoch").get().await()
                    val epochTime = epochSnap.getLong("wipeTimestamp") ?: 0L
                    val tombSnap = cRef.collection("deleted_records").get().await()
                    val remoteTombstones = tombSnap.documents.mapNotNull { it.data }
                    DeletedRecordsRegistry.mergeRemoteTombstones(remoteTombstones, epochTime)
                    DeletedRecordsRegistry.syncWithDao(dao)
                    PmpDatabase.purgeTombstonedRecordsLocally(dao)
                }
                if (isCloudAdmin || isCloudManager) {
                    runSyncStep("مزامنة شواهد السجلات المحذوفة", "deleted_records", critical = false) {
                        val localTombs = DeletedRecordsRegistry.getAllTombstoneRecords().filter { t ->
                            val col = (t["collection"] as? String).orEmpty()
                            val docIdVal = (t["docId"] as? String).orEmpty()
                            val entIdVal = (t["entityId"] as? String).orEmpty()
                            col.isNotBlank() && (docIdVal.isNotBlank() || entIdVal.isNotBlank())
                        }
                        var committed = 0
                        localTombs.chunked(300).forEach { chunk ->
                            val tBatch = firestore.batch()
                            var chunkWrites = 0
                            chunk.forEach { t ->
                                val tDocId = safeId("${t["collection"]}_${t["docId"]}", "${t["docId"]}")
                                if (tDocId.isNotBlank()) {
                                    tBatch.set(cRef.collection("deleted_records").document(tDocId), t, SetOptions.merge())
                                    chunkWrites++
                                }
                            }
                            if (chunkWrites > 0) {
                                tBatch.commit().await()
                                committed += chunkWrites
                            }
                        }
                        committed
                    }
                }

                // 0c. User Accounts & Employee Permissions (strictly aligned with firestore.rules functionUpdateUserAccount)
                runSyncStep("مزامنة حسابات المستخدمين والصلاحيات", "user_accounts", critical = false) {
                    val existingCloudUserIds = runCatching {
                        if (isCloudAdmin || isCloudManager) {
                            cRef.collection("user_accounts").get().await().documents.map { it.id }.toSet()
                        } else emptySet()
                    }.getOrDefault(emptySet())

                    val usersToSync = (if (isCloudAdmin || isCloudManager) {
                        users
                    } else {
                        users.filter {
                            (activeLocalAccount != null && it.id == activeLocalAccount.id) ||
                                it.id == activeUserId ||
                                (activeUserId == null && it.isCurrentSession)
                        }
                    }).filterNot { DeletedAccountsRegistry.isDeleted(it.userCode, it.username, it.phoneOrEmail) }

                    var committed = 0
                    usersToSync.chunked(300).forEach { chunk ->
                        val batch = firestore.batch()
                        var chunkWrites = 0
                        for (u in chunk) {
                            val docId = safeId(u.userCode, "USR-${u.id}")
                            val isSelfUser = activeLocalAccount != null && (
                                u.id == activeLocalAccount.id ||
                                    u.userCode.equals(activeLocalAccount.userCode, ignoreCase = true)
                                )
                            val targetIsPrivileged = u.isSuperAdminAccount() ||
                                u.isSystemManager() ||
                                EnterpriseRbacManager.isPrivilegedRole(u.role) ||
                                docId.equals("USR-ADMIN", ignoreCase = true) ||
                                u.username.equals("admin", ignoreCase = true)

                            if (isCloudAdmin || (isCloudManager && !isSelfUser && !targetIsPrivileged)) {
                                val data = mapOf(
                                    "id" to u.id,
                                    "userCode" to docId,
                                    "fullName" to u.fullName.ifBlank { docId },
                                    "username" to u.username.trim().ifBlank { docId }.take(120),
                                    "phoneOrEmail" to u.phoneOrEmail,
                                    "pinOrPassword" to u.pinOrPassword,
                                    "role" to u.role,
                                    "branch" to u.branch.ifBlank { "الخرطوم" },
                                    "permissions" to u.permissions,
                                    "serverSyncToken" to u.serverSyncToken,
                                    "remoteServerId" to u.remoteServerId,
                                    "syncStatus" to u.syncStatus,
                                    "serverEndpoint" to u.serverEndpoint,
                                    "active" to (if (isSelfUser) true else u.active),
                                    "createdAt" to u.createdAt,
                                    "serverUpdatedBy" to actor,
                                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                                )
                                batch.set(cRef.collection("user_accounts").document(docId), data, SetOptions.merge())
                                chunkWrites++
                            } else if (isSelfUser) {
                                val selfExistsOnServer = if (isCloudAdmin || isCloudManager) {
                                    docId in existingCloudUserIds
                                } else {
                                    runCatching {
                                        cRef.collection("user_accounts").document(docId).get().await().exists()
                                    }.getOrDefault(false)
                                }
                                if (selfExistsOnServer) {
                                    // Non-Admin updating own profile uses strict whitelist matching firestore.rules
                                    val selfData = mapOf(
                                        "fullName" to u.fullName.ifBlank { docId },
                                        "phoneOrEmail" to u.phoneOrEmail,
                                        "pinOrPassword" to u.pinOrPassword,
                                        "serverSyncToken" to u.serverSyncToken,
                                        "deviceId" to deviceId,
                                        "updatedAt" to System.currentTimeMillis(),
                                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                                    )
                                    batch.set(cRef.collection("user_accounts").document(docId), selfData, SetOptions.merge())
                                    chunkWrites++
                                }
                            }
                        }
                        if (chunkWrites > 0) {
                            batch.commit().await()
                            committed += chunkWrites
                        }
                    }

                    // Best-effort sync of email_directory entries so Google SSO users resolve exact roles & permissions
                    if (isCloudAdmin || isCloudManager) {
                        for (u in usersToSync) {
                            val email = u.phoneOrEmail.trim().lowercase()
                            if (!email.contains("@")) continue
                            val isPriv = u.isSuperAdminAccount() || u.isSystemManager() || EnterpriseRbacManager.isPrivilegedRole(u.role)
                            if (isCloudAdmin || !isPriv) {
                                runCatching {
                                    val emailKey = buildEmailDirectoryKey(email)
                                    cRef.collection("email_directory").document(emailKey).set(
                                        mapOf(
                                            "email" to email,
                                            "userCode" to safeId(u.userCode, "USR-${u.id}"),
                                            "username" to u.username,
                                            "roleKey" to resolveCanonicalRbacTier(u),
                                            "branch" to u.branch.ifBlank { "الخرطوم" },
                                            "permissions" to u.permissions.ifBlank { "صلاحية العرض" },
                                            "active" to u.active,
                                            "updatedAt" to System.currentTimeMillis(),
                                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                                        ),
                                        SetOptions.merge()
                                    ).await()
                                }
                            }
                        }
                    }
                    committed
                }

                // 1. Invoices & Items & Payments (Batched - excluding deleted)
                val invoices = dao.getInvoicesDirect().filterNot {
                    DeletedRecordsRegistry.isPreEpoch(it.createdAt) ||
                        DeletedRecordsRegistry.isDeleted("invoices", it.id) ||
                        DeletedRecordsRegistry.isDeleted("invoices", it.invoiceNo) ||
                        DeletedRecordsRegistry.isDeleted("invoices", "INV-${it.id}")
                }
                val allItems = dao.getInvoiceItemsDirect()
                val allPayments = dao.getInvoicePaymentsDirect()
                runSyncStep("مزامنة فواتير المبيعات", "invoices", critical = true) {
                    var committed = 0
                    invoices.chunked(200).forEach { chunk ->
                        val batch = firestore.batch()
                        var chunkWrites = 0
                        for (inv in chunk) {
                            val invItems = allItems.filter { it.invoiceId == inv.id }.map { item ->
                                mapOf(
                                    "id" to item.id,
                                    "description" to item.description,
                                    "quantity" to item.quantity,
                                    "unitPrice" to item.unitPrice,
                                    "lineTotal" to item.lineTotal,
                                    "productId" to (item.productId ?: 0L),
                                    "warehouseId" to (item.warehouseId ?: 0L)
                                )
                            }
                            val activeInvPayments = allPayments.filter { it.invoiceId == inv.id && it.status == "active" }
                            val paidSum = activeInvPayments.sumOf { it.amount }.coerceAtLeast(0.0)
                            val invPayments = activeInvPayments.map { p ->
                                mapOf(
                                    "id" to p.id,
                                    "payDate" to p.payDate,
                                    "cashbox" to p.cashbox,
                                    "amount" to p.amount,
                                    "note" to p.note,
                                    "createdBy" to p.createdBy
                                )
                            }

                            val docId = safeId(inv.invoiceNo, "INV-${inv.id}")
                            val cleanCustomer = inv.customerName.trim().ifBlank { "عميل نقدي" }.take(200)
                            val cleanCurrency = inv.currency.trim().uppercase().let { if (it in VALID_CURRENCIES) it else "SDG" }
                            val data = mapOf(
                                "id" to inv.id,
                                "invoiceNo" to inv.invoiceNo,
                                "customerId" to inv.customerId,
                                "customerName" to cleanCustomer,
                                "invoiceDate" to inv.invoiceDate,
                                "currency" to cleanCurrency,
                                "subtotal" to inv.subtotal.coerceAtLeast(0.0),
                                "discount" to inv.discount.coerceAtLeast(0.0),
                                "total" to inv.total.coerceAtLeast(0.0),
                                "paidAmount" to paidSum,
                                "notes" to inv.notes,
                                "status" to inv.status,
                                "cancelReason" to (inv.cancelReason ?: ""),
                                "createdBy" to inv.createdBy,
                                "createdAt" to inv.createdAt,
                                "serverUpdatedBy" to actor,
                                "serverUpdatedAt" to FieldValue.serverTimestamp()
                            )
                            val invValidation = validateTransactionCommitPayload("invoices", docId, data)
                            if (!invValidation.valid) {
                                recordTamperAttempt("SYNC_INVOICES_VALIDATION", "erp_clusters/$cleanCluster/invoices/$docId", invValidation.reason, actor)
                                logValidationViolationToAudit(cleanCluster, actor, "TAMPER_ATTEMPT_BLOCKED", "تم حظر مزامنة فاتورة تالفة/مشبوهة [$docId]: ${invValidation.reason}")
                                continue
                            }
                            batch.set(cRef.collection("invoices").document(docId), data + mapOf("items" to invItems, "payments" to invPayments), SetOptions.merge())
                            chunkWrites++
                        }
                        if (chunkWrites > 0) {
                            batch.commit().await()
                            committed += chunkWrites
                        }
                    }
                    committed
                }

                // 2. Financial Movements (Batched - excluding deleted; requires treasury authorization in rules)
                val canPushTreasury = isCloudAdmin ||
                    isCloudManager ||
                    isCloudAccountant ||
                    isCloudDeputy ||
                    (activeLocalAccount != null && (
                        EnterpriseRbacManager.hasModuleAccess(activeLocalAccount, "الخزن") ||
                            EnterpriseRbacManager.hasModuleAccess(activeLocalAccount, "التحويلات") ||
                            EnterpriseRbacManager.hasModuleAccess(activeLocalAccount, "العمليات المالية")
                        ))
                val movements = dao.getMovementsDirect().filterNot {
                    DeletedRecordsRegistry.isMovementDeleted(it)
                }
                if (canPushTreasury) {
                RoomTransactionLogger.log(
                    operation = "SYNC_MOVEMENTS_DISPATCH",
                    stage = TransactionStage.SYNC_DISPATCH,
                    status = TraceStatus.IN_PROGRESS,
                    entityType = "movements",
                    details = "إرسال ${movements.size} حركة مالية إلى السحابة"
                )
                runSyncStep("مزامنة الحركات المالية والخزن", "movements", critical = true) {
                    var committed = 0
                    try {
                        movements.chunked(300).forEach { chunk ->
                            val batch = firestore.batch()
                            var chunkWrites = 0
                            for (m in chunk) {
                                val docId = safeId(m.cloudDocId.trim().ifBlank { "MOV-${m.id}" }, "MOV-${m.id}")
                                val cleanCashbox = m.cashbox.trim().ifBlank { "خزنة السودان" }.take(120)
                                val cleanCurrency = m.currency.trim().uppercase().let { if (it in VALID_CURRENCIES) it else "SDG" }
                                val cleanDirection = m.direction.trim().let { if (it in VALID_DIRECTIONS) it else "وارد" }
                                val data = mutableMapOf<String, Any>(
                                    "id" to m.id,
                                    "txDate" to m.txDate,
                                    "cashbox" to cleanCashbox,
                                    "currency" to cleanCurrency,
                                    "direction" to cleanDirection,
                                    "amount" to m.amount,
                                    "description" to m.description,
                                    "reference" to m.reference,
                                    "createdBy" to m.createdBy,
                                    "createdAt" to m.createdAt,
                                    "cloudDocId" to docId,
                                    "serverUpdatedBy" to actor,
                                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                                )
                                if (m.syncUuid.isNotBlank()) data["syncUuid"] = m.syncUuid
                                if (m.updatedAt > 0L) data["updatedAt"] = m.updatedAt
                                val movValidation = validateTransactionCommitPayload("movements", docId, data)
                                if (!movValidation.valid) {
                                    recordTamperAttempt("SYNC_MOVEMENTS_VALIDATION", "erp_clusters/$cleanCluster/movements/$docId", movValidation.reason, actor)
                                    logValidationViolationToAudit(cleanCluster, actor, "TAMPER_ATTEMPT_BLOCKED", "تم حظر مزامنة حركة مالية تالفة [$docId]: ${movValidation.reason}")
                                    continue
                                }
                                batch.set(cRef.collection("movements").document(docId), data, SetOptions.merge())
                                chunkWrites++
                            }
                            if (chunkWrites > 0) {
                                batch.commit().await()
                                committed += chunkWrites
                            }
                        }
                        RoomTransactionLogger.log(
                            operation = "SYNC_MOVEMENTS_ACK",
                            stage = TransactionStage.SYNC_ACKNOWLEDGED,
                            status = TraceStatus.SUCCESS,
                            entityType = "movements",
                            details = "تم تأكيد مزامنة $committed حركة مالية بنجاح على السيرفر"
                        )
                    } catch (e: Exception) {
                        RoomTransactionLogger.recordSyncBreakage(
                            operation = "SYNC_MOVEMENTS_TO_CLOUD",
                            entityType = "movements",
                            recordId = "BATCH",
                            reason = "فشل إرسال الحركات المالية إلى السيرفر: ${e.message}",
                            cause = e
                        )
                        throw e
                    }
                    committed
                }
                } // end if (canPushTreasury) for movements

                // 3. Transfers (Batched - excluding deleted; requires treasury authorization in rules)
                val transfers = dao.getTransfersDirect().filterNot {
                    DeletedRecordsRegistry.isPreEpoch(it.createdAt) ||
                        DeletedRecordsRegistry.isDeleted("transfers", it.id) ||
                        DeletedRecordsRegistry.isDeleted("transfers", "TRF-${it.id}") ||
                        (it.reference.isNotBlank() && DeletedRecordsRegistry.isDeleted("transfers", it.reference))
                }
                if (canPushTreasury) {
                runSyncStep("مزامنة تحويلات الخزن", "transfers", critical = true) {
                    var committed = 0
                    transfers.chunked(300).forEach { chunk ->
                        val batch = firestore.batch()
                        var chunkWrites = 0
                        for (t in chunk) {
                            val docId = safeId("TRF-${t.id}-${t.reference}", "TRF-${t.id}")
                            val data = mapOf(
                                "id" to t.id,
                                "reference" to t.reference,
                                "txDate" to t.txDate,
                                "sourceCashbox" to t.sourceCashbox,
                                "destinationCashbox" to t.destinationCashbox,
                                "sourceCurrency" to t.sourceCurrency.trim().uppercase().let { if (it in VALID_CURRENCIES) it else "SDG" },
                                "destinationCurrency" to t.destinationCurrency.trim().uppercase().let { if (it in VALID_CURRENCIES) it else "EGP" },
                                "sourceAmount" to t.sourceAmount,
                                "rate" to t.rate,
                                "destinationAmount" to t.destinationAmount,
                                "description" to t.description,
                                "createdBy" to t.createdBy,
                                "createdAt" to t.createdAt,
                                "serverUpdatedBy" to actor,
                                "serverUpdatedAt" to FieldValue.serverTimestamp()
                            )
                            val trfValidation = validateTransactionCommitPayload("transfers", docId, data)
                            if (!trfValidation.valid) {
                                recordTamperAttempt("SYNC_TRANSFERS_VALIDATION", "erp_clusters/$cleanCluster/transfers/$docId", trfValidation.reason, actor)
                                logValidationViolationToAudit(cleanCluster, actor, "TAMPER_ATTEMPT_BLOCKED", "تم حظر مزامنة تحويل خزينة تالف [$docId]: ${trfValidation.reason}")
                                continue
                            }
                            batch.set(cRef.collection("transfers").document(docId), data, SetOptions.merge())
                            chunkWrites++
                        }
                        if (chunkWrites > 0) {
                            batch.commit().await()
                            committed += chunkWrites
                        }
                    }
                    committed
                }
                } // end if (canPushTreasury) for transfers

                // 4. Customers (Batched - excluding deleted)
                val customers = dao.getCustomersDirect().filterNot {
                    DeletedRecordsRegistry.isDeleted("customers", it.id) ||
                        DeletedRecordsRegistry.isDeleted("customers", "CUST-${it.id}")
                }
                runSyncStep("مزامنة بيانات العملاء", "customers", critical = true) {
                    var committed = 0
                    customers.chunked(300).forEach { chunk ->
                        val batch = firestore.batch()
                        chunk.forEach { c ->
                            val docId = "CUST-${c.id}"
                            val data = mapOf(
                                "id" to c.id,
                                "name" to c.name,
                                "phone" to c.phone,
                                "address" to c.address,
                                "notes" to c.notes,
                                "active" to c.active,
                                "serverUpdatedBy" to actor,
                                "serverUpdatedAt" to FieldValue.serverTimestamp()
                            )
                            batch.set(cRef.collection("customers").document(docId), data, SetOptions.merge())
                        }
                        batch.commit().await()
                        committed += chunk.size
                    }
                    committed
                }

                // 5. Products & Pricing (Batched - excluding deleted)
                val products = dao.getProductsDirect().filterNot { p ->
                    DeletedRecordsRegistry.isDeleted("products", p.id) ||
                        DeletedRecordsRegistry.isDeleted("products", "PROD-${p.id}")
                }
                runSyncStep("مزامنة المنتجات والأصناف", "products", critical = false) {
                    val existingProdIds = if (!canManageMasterCatalogs) {
                        runCatching { cRef.collection("products").get().await().documents.map { it.id }.toSet() }.getOrDefault(emptySet())
                    } else emptySet()
                    var committed = 0
                    products.chunked(300).forEach { chunk ->
                        val batch = firestore.batch()
                        var chunkWrites = 0
                        chunk.forEach { p ->
                            val docId = "PROD-${p.id}"
                            if (canManageMasterCatalogs || docId !in existingProdIds) {
                                val data = mapOf(
                                    "id" to p.id,
                                    "sku" to p.sku,
                                    "name" to p.name,
                                    "category" to p.category,
                                    "unit" to p.unit,
                                    "purchasePrice" to p.purchasePrice,
                                    "salePrice" to p.salePrice,
                                    "salePriceEgp" to p.salePriceEgp,
                                    "minimumStock" to p.minimumStock,
                                    "paintColor" to p.paintColor,
                                    "packageWeight" to p.packageWeight,
                                    "active" to p.active,
                                    "serverUpdatedBy" to actor,
                                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                                )
                                batch.set(cRef.collection("products").document(docId), data, SetOptions.merge())
                                chunkWrites++
                            }
                        }
                        if (chunkWrites > 0) {
                            batch.commit().await()
                            committed += chunkWrites
                        }
                    }
                    committed
                }

                // 5b. Poster Price List (Separated SDG & EGP Currencies)
                val posterPrices = dao.getPosterPricesDirect()
                if (posterPrices.isNotEmpty()) {
                    runSyncStep("مزامنة قائمة الأسعار", "poster_prices", critical = false) {
                        var committed = 0
                        posterPrices.chunked(300).forEach { chunk ->
                            val batch = firestore.batch()
                            chunk.forEach { pp ->
                                val isEgp = pp.currency.equals("EGP", ignoreCase = true) || pp.id.endsWith("_EGP")
                                val cleanPrice = if (isEgp && pp.priceEgp > 0L && pp.price == pp.priceEgp * 200L) pp.priceEgp else pp.price
                                val docId = safeId(pp.id, "${pp.weight}_${pp.kind}_${pp.currency}")
                                val data = mapOf(
                                    "id" to pp.id,
                                    "weight" to pp.weight,
                                    "kind" to pp.kind,
                                    "price" to cleanPrice,
                                    "priceEgp" to if (isEgp) cleanPrice else 0L,
                                    "currency" to if (isEgp) "EGP" else "SDG",
                                    "serverUpdatedBy" to actor,
                                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                                )
                                batch.set(cRef.collection("poster_prices").document(docId), data, SetOptions.merge())
                            }
                            batch.commit().await()
                            committed += chunk.size
                        }
                        committed
                    }
                }

                // 5c. Sync Registered Package Weights (Admin-only config collection)
                if (isCloudAdmin) {
                    runSyncStep("مزامنة أوزان العبوات", "config", critical = false) {
                        val appCtx = firestore.app.applicationContext
                        val weightMgr = com.example.util.InventoryWeightManager.getInstance(appCtx)
                        val payload = weightMgr.exportForCloudSync().toMutableMap()
                        payload["updatedBy"] = actor
                        payload["serverUpdatedAt"] = FieldValue.serverTimestamp()
                        cRef.collection("config").document("inventory_weights").set(
                            payload,
                            SetOptions.merge()
                        ).await()
                        1
                    }
                }

                // 6. Cashboxes & Warehouses (Batched)
                val cashboxes = dao.getCashboxesDirect()
                    .filter { cb ->
                        val cbDocId = DeletedRecordsRegistry.cashboxDocId(cb.name, cb.id)
                        !DeletedRecordsRegistry.isDeleted("cashboxes", cb.id) &&
                            !DeletedRecordsRegistry.isDeleted("cashboxes", "BOX-${cb.id}") &&
                            !DeletedRecordsRegistry.isDeleted("cashboxes", cb.name) &&
                            !DeletedRecordsRegistry.isDeleted("cashboxes", cbDocId)
                    }
                    .distinctBy { it.name.trim().lowercase() }
                if (cashboxes.isNotEmpty() && canManageMasterCatalogs) {
                    runSyncStep("مزامنة تعريف الخزن", "cashboxes", critical = false) {
                        val batch = firestore.batch()
                        cashboxes.forEach { cb ->
                            val docId = DeletedRecordsRegistry.cashboxDocId(cb.name, cb.id)
                            batch.set(
                                cRef.collection("cashboxes").document(docId),
                                mapOf(
                                    "id" to cb.id,
                                    "name" to cb.name.trim(),
                                    "docId" to docId,
                                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                                ),
                                SetOptions.merge()
                            )
                        }
                        batch.commit().await()
                        cashboxes.size
                    }
                }

                val warehouses = dao.getWarehousesDirect()
                    .filterNot { wh ->
                        DeletedRecordsRegistry.isDeleted("warehouses", wh.id) ||
                            DeletedRecordsRegistry.isDeleted("warehouses", "WH-${wh.id}") ||
                            DeletedRecordsRegistry.isDeleted("warehouses", wh.name)
                    }
                    .distinctBy { it.name.trim().lowercase() }
                if (warehouses.isNotEmpty()) {
                    runSyncStep("مزامنة المخازن والفروع", "warehouses", critical = false) {
                        val existingWhIds = if (!canManageMasterCatalogs) {
                            runCatching { cRef.collection("warehouses").get().await().documents.map { it.id }.toSet() }.getOrDefault(emptySet())
                        } else emptySet()
                        val batch = firestore.batch()
                        var writes = 0
                        warehouses.forEach { wh ->
                            val docId = "WH-${wh.id}"
                            if (canManageMasterCatalogs || docId !in existingWhIds) {
                                val whClass = wh.resolveWarehouseClass()
                                batch.set(
                                    cRef.collection("warehouses").document(docId),
                                    mapOf(
                                        "id" to wh.id,
                                        "name" to wh.name.trim(),
                                        "active" to wh.active,
                                        "currency" to wh.currency,
                                        "linkedCashbox" to wh.linkedCashbox,
                                        "warehouseType" to wh.warehouseType,
                                        "warehouseClass" to whClass.name,
                                        "code" to wh.code.ifBlank { docId },
                                        "branch" to wh.branch,
                                        "allowDirectSales" to (whClass == WarehouseClass.SALES),
                                        "allowProductionReceipt" to (whClass == WarehouseClass.FINISHED_GOODS),
                                        "syncUuid" to wh.syncUuid.ifBlank { "WH-UUID-${wh.id}" },
                                        "recordVersion" to wh.recordVersion,
                                        "updatedAt" to wh.updatedAt,
                                        "serverUpdatedBy" to actor,
                                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                                    ),
                                    SetOptions.merge()
                                )
                                writes++
                            }
                        }
                        if (writes > 0) batch.commit().await()
                        writes
                    }
                }

                // 7. Factory Items & Raw Materials
                val factoryItems = dao.getFactoryItemsDirect().filterNot { fi ->
                    DeletedRecordsRegistry.isDeleted("factory_items", fi.id) ||
                        DeletedRecordsRegistry.isDeleted("factory_items", "FI-${fi.id}") ||
                        DeletedRecordsRegistry.isDeleted("factory_items", fi.name)
                }
                if (factoryItems.isNotEmpty()) {
                    runSyncStep("مزامنة أصناف المصنع والمواد الخام", "factory_items", critical = false) {
                        var committed = 0
                        factoryItems.chunked(300).forEach { chunk ->
                            val batch = firestore.batch()
                            chunk.forEach { fi ->
                                val docId = "FI-${fi.id}"
                                batch.set(
                                    cRef.collection("factory_items").document(docId),
                                    mapOf(
                                        "id" to fi.id,
                                        "name" to fi.name,
                                        "unit" to fi.unit,
                                        "category" to fi.category,
                                        "itemCode" to fi.itemCode.ifBlank { "RAW-${fi.id}" },
                                        "unitCostEgp" to fi.unitCostEgp,
                                        "supplierName" to fi.supplierName,
                                        "lastPurchasePrice" to fi.lastPurchasePrice,
                                        "lastPriceCurrency" to fi.lastPriceCurrency,
                                        "lastPriceUpdatedAt" to fi.lastPriceUpdatedAt,
                                        "density" to fi.density,
                                        "solidsPercent" to fi.solidsPercent,
                                        "minStock" to fi.minStock,
                                        "maxStock" to fi.maxStock,
                                        "batchTrackingRequired" to fi.batchTrackingRequired,
                                        "syncUuid" to fi.syncUuid.ifBlank { "FI-UUID-${fi.id}" },
                                        "recordVersion" to fi.recordVersion,
                                        "linkedToEgypt" to true,
                                        "defaultCurrency" to "EGP",
                                        "serverUpdatedBy" to actor,
                                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                                    ),
                                    SetOptions.merge()
                                )
                            }
                            batch.commit().await()
                            committed += chunk.size
                        }
                        committed
                    }
                }

                // 8. Factory Movements
                val factoryMoves = dao.getFactoryMovesDirect().filterNot { fm ->
                    DeletedRecordsRegistry.isPreEpoch(fm.createdAt) ||
                        DeletedRecordsRegistry.isDeleted("factory_moves", fm.id) ||
                        DeletedRecordsRegistry.isDeleted("factory_moves", "FM-${fm.id}")
                }
                if (factoryMoves.isNotEmpty()) {
                    runSyncStep("مزامنة حركات المصنع", "factory_moves", critical = false) {
                        var committed = 0
                        factoryMoves.chunked(300).forEach { chunk ->
                            val batch = firestore.batch()
                            chunk.forEach { fm ->
                                val docId = "FM-${fm.id}"
                                batch.set(
                                    cRef.collection("factory_moves").document(docId),
                                    mapOf(
                                        "id" to fm.id,
                                        "moveDate" to fm.moveDate,
                                        "kind" to fm.kind,
                                        "itemId" to fm.itemId,
                                        "itemName" to fm.itemName,
                                        "qty" to fm.qty,
                                        "machineId" to (fm.machineId ?: 0L),
                                        "machineName" to (fm.machineName ?: ""),
                                        "batch" to fm.batch,
                                        "note" to fm.note,
                                        "actor" to fm.actor,
                                        "counterparty" to fm.counterparty,
                                        "linked" to fm.linked,
                                        "createdAt" to fm.createdAt,
                                        "currency" to "EGP",
                                        "serverUpdatedBy" to actor,
                                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                                    ),
                                    SetOptions.merge()
                                )
                            }
                            batch.commit().await()
                            committed += chunk.size
                        }
                        committed
                    }
                }

                // 8b. Chemical Formulations
                val formulations = dao.getFormulationsDirect().filterNot { form ->
                    DeletedRecordsRegistry.isPreEpoch(form.createdAt) ||
                        DeletedRecordsRegistry.isDeleted("lab_formulations", form.id) ||
                        DeletedRecordsRegistry.isDeleted("lab_formulations", "FORM-${form.id}") ||
                        (form.formulaNo.isNotBlank() && DeletedRecordsRegistry.isDeleted("lab_formulations", form.formulaNo))
                }
                val canPushSecretLab = activeLocalAccount == null ||
                    isCloudAdmin ||
                    isCloudManager ||
                    com.example.util.LabSecurityManager.canCreateOrEditFormulas(activeLocalAccount) ||
                    com.example.util.LabSecurityManager.canApproveFormulas(activeLocalAccount)
                if (formulations.isNotEmpty() && canPushSecretLab) {
                    runSyncStep("مزامنة تركيبات المختبر الكيميائي", "lab_formulations", critical = false) {
                        var committed = 0
                        formulations.chunked(250).forEach { chunk ->
                            val batch = firestore.batch()
                            chunk.forEach { form ->
                                val docId = safeId(form.formulaNo, "FORM-${form.id}")
                                val payload = mapOf(
                                    "id" to form.id,
                                    "syncUuid" to form.syncUuid,
                                    "formulaNo" to form.formulaNo,
                                    "productName" to form.productName,
                                    "linkedProductId" to (form.linkedProductId ?: 0L),
                                    "linkedProductSku" to form.linkedProductSku,
                                    "category" to form.category,
                                    "paintType" to form.paintType,
                                    "baseKind" to form.baseKind,
                                    "versionNumber" to form.versionNumber,
                                    "approvalStatus" to form.approvalStatus,
                                    "approvedBy" to form.approvedBy,
                                    "approvedAt" to form.approvedAt,
                                    "targetPvc" to form.targetPvc,
                                    "calculatedPvc" to form.calculatedPvc,
                                    "cpvcEstimated" to form.cpvcEstimated,
                                    "totalSolidsWeight" to form.totalSolidsWeight,
                                    "totalSolidsVolume" to form.totalSolidsVolume,
                                    "totalBatchWeight" to form.totalBatchWeight,
                                    "totalBatchVolume" to form.totalBatchVolume,
                                    "calculatedDensity" to form.calculatedDensity,
                                    "totalCost" to form.totalCost,
                                    "costPerKg" to form.costPerKg,
                                    "historicalTotalCost" to form.historicalTotalCost,
                                    "historicalCostPerKg" to form.historicalCostPerKg,
                                    "currency" to form.currency,
                                    "changeReason" to form.changeReason,
                                    "updatedBy" to form.updatedBy,
                                    "updatedAt" to form.updatedAt,
                                    "ingredientsJson" to form.ingredientsJson,
                                    "confidentialCiphertext" to form.confidentialCiphertext,
                                    "isConfidentialSealed" to form.isConfidentialSealed,
                                    "technicalAnalysis" to form.technicalAnalysis,
                                    "notes" to form.notes,
                                    "approvedForProduction" to form.approvedForProduction,
                                    "createdBy" to form.createdBy,
                                    "createdAt" to form.createdAt,
                                    "serverUpdatedBy" to actor,
                                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                                )
                                val summaryPayload = mapOf(
                                    "id" to form.id,
                                    "formulaNo" to form.formulaNo,
                                    "productName" to form.productName,
                                    "linkedProductId" to (form.linkedProductId ?: 0L),
                                    "category" to form.category,
                                    "paintType" to form.paintType,
                                    "baseKind" to form.baseKind,
                                    "versionNumber" to form.versionNumber,
                                    "approvalStatus" to form.approvalStatus,
                                    "calculatedPvc" to form.calculatedPvc,
                                    "calculatedDensity" to form.calculatedDensity,
                                    "costPerKg" to form.costPerKg,
                                    "historicalCostPerKg" to form.historicalCostPerKg,
                                    "currency" to form.currency,
                                    "approvedForProduction" to form.approvedForProduction,
                                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                                )
                                batch.set(cRef.collection("lab_formulations").document(docId), payload, SetOptions.merge())
                                batch.set(cRef.collection("lab_formula_summaries").document(docId), summaryPayload, SetOptions.merge())
                            }
                            batch.commit().await()
                            committed += chunk.size
                        }
                        committed
                    }
                }

                // 8c. Daily Production Batches
                val batches = dao.getProductionBatchesDirect().filterNot { b ->
                    DeletedRecordsRegistry.isPreEpoch(b.createdAt) ||
                        DeletedRecordsRegistry.isDeleted("paint_batches", b.id) ||
                        DeletedRecordsRegistry.isDeleted("paint_batches", "BATCH-${b.id}") ||
                        (b.batchNumber.isNotBlank() && DeletedRecordsRegistry.isDeleted("paint_batches", b.batchNumber))
                }
                if (batches.isNotEmpty()) {
                    runSyncStep("مزامنة تشغيلات الإنتاج", "paint_batches", critical = false) {
                        var committed = 0
                        batches.chunked(300).forEach { chunk ->
                            val batch = firestore.batch()
                            chunk.forEach { b ->
                                val docId = safeId(b.batchNumber, "BATCH-${b.id}")
                                val payload = mapOf(
                                    "id" to b.id,
                                    "batchNumber" to b.batchNumber,
                                    "productionDate" to b.productionDate,
                                    "formulaId" to (b.formulaId ?: 0L),
                                    "formulaNo" to b.formulaNo,
                                    "productName" to b.productName,
                                    "paintType" to b.paintType,
                                    "baseKind" to b.baseKind,
                                    "targetPvc" to b.targetPvc,
                                    "targetWeightKg" to b.targetWeightKg,
                                    "actualWeightKg" to b.actualWeightKg,
                                    "targetVolumeLiters" to b.targetVolumeLiters,
                                    "cartonsCount" to b.cartonsCount,
                                    "packageUnit" to b.packageUnit,
                                    "machineId" to (b.machineId ?: 0L),
                                    "machineName" to b.machineName,
                                    "qualityStatus" to b.qualityStatus,
                                    "measuredDensity" to b.measuredDensity,
                                    "measuredViscosity" to b.measuredViscosity,
                                    "scaledIngredientsJson" to b.scaledIngredientsJson,
                                    "supervisorOrEngineer" to b.supervisorOrEngineer,
                                    "shift" to b.shift,
                                    "notes" to b.notes,
                                    "syncStatus" to "SYNCED",
                                    "createdAt" to b.createdAt,
                                    "serverUpdatedBy" to actor,
                                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                                )
                                batch.set(cRef.collection("paint_batches").document(docId), payload, SetOptions.merge())
                            }
                            batch.commit().await()
                            committed += chunk.size
                        }
                        committed
                    }
                }

                // 9. Stock Moves (Batched - excluding tombstoned & orphan moves)
                val validProductIds = products.map { it.id }.toSet()
                val validWarehouseIds = warehouses.map { it.id }.toSet()
                val stockMoves = dao.getStockMovesDirect().filterNot { sm ->
                    DeletedRecordsRegistry.isPreEpoch(sm.createdAt) ||
                        DeletedRecordsRegistry.isDeleted("stock_moves", sm.id) ||
                        DeletedRecordsRegistry.isDeleted("stock_moves", "SM-${sm.id}") ||
                        (validProductIds.isNotEmpty() && sm.productId !in validProductIds) ||
                        (validWarehouseIds.isNotEmpty() && sm.warehouseId !in validWarehouseIds)
                }
                if (stockMoves.isNotEmpty()) {
                    runSyncStep("مزامنة حركات المخزون", "stock_moves", critical = false) {
                        var committed = 0
                        stockMoves.chunked(300).forEach { chunk ->
                            val batch = firestore.batch()
                            chunk.forEach { sm ->
                                val docId = "SM-${sm.id}"
                                batch.set(
                                    cRef.collection("stock_moves").document(docId),
                                    mapOf(
                                        "id" to sm.id,
                                        "warehouseId" to sm.warehouseId,
                                        "productId" to sm.productId,
                                        "quantity" to sm.quantity.coerceAtLeast(0.0),
                                        "kind" to sm.kind.ifBlank { "IN" }.take(40),
                                        "invoiceId" to (sm.invoiceId ?: 0L),
                                        "note" to sm.note,
                                        "createdBy" to sm.createdBy,
                                        "createdAt" to sm.createdAt,
                                        "serverUpdatedBy" to actor,
                                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                                    ),
                                    SetOptions.merge()
                                )
                            }
                            batch.commit().await()
                            committed += chunk.size
                        }
                        committed
                    }
                }

                // 10. Halfa & Egypt Invoices
                val halfaInvoices = dao.getHalfaInvoicesDirect().filterNot { hi ->
                    DeletedRecordsRegistry.isPreEpoch(hi.createdAt) ||
                        DeletedRecordsRegistry.isDeleted("halfa_invoices", hi.id) ||
                        DeletedRecordsRegistry.isDeleted("halfa_invoices", "HINV-${hi.id}") ||
                        (hi.invoiceNo.isNotBlank() && DeletedRecordsRegistry.isDeleted("halfa_invoices", hi.invoiceNo))
                }
                if (halfaInvoices.isNotEmpty()) {
                    runSyncStep("مزامنة فواتير حلفا ومصر", "halfa_invoices", critical = false) {
                        var committed = 0
                        halfaInvoices.chunked(300).forEach { chunk ->
                            val batch = firestore.batch()
                            chunk.forEach { hi ->
                                val docId = "HINV-${hi.id}"
                                batch.set(
                                    cRef.collection("halfa_invoices").document(docId),
                                    mapOf(
                                        "id" to hi.id,
                                        "invoiceNo" to hi.invoiceNo,
                                        "invoiceDate" to hi.invoiceDate,
                                        "cashbox" to hi.cashbox,
                                        "currency" to hi.currency,
                                        "warehouseId" to hi.warehouseId,
                                        "productId" to hi.productId,
                                        "productName" to hi.productName,
                                        "quantity" to hi.quantity,
                                        "unitPrice" to hi.unitPrice,
                                        "total" to hi.total,
                                        "factoryMoveId" to (hi.factoryMoveId ?: 0L),
                                        "note" to hi.note,
                                        "status" to hi.status,
                                        "createdBy" to hi.createdBy,
                                        "createdAt" to hi.createdAt,
                                        "serverUpdatedBy" to actor,
                                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                                    ),
                                    SetOptions.merge()
                                )
                            }
                            batch.commit().await()
                            committed += chunk.size
                        }
                        committed
                    }
                }

                // 10b. Phase 2 Unified Inventory Engine & Phase 3 Manufacturing
                val resolvedInvDao = invDao ?: runCatching {
                    PmpDatabase.getDatabase(firestore.app.applicationContext).erpInventoryDao()
                }.getOrNull()
                if (resolvedInvDao != null) {
                    runSyncStep("مزامنة المخزون الموحد والتصنيع", "inventory_movements", critical = false) {
                        pushPhase2InventoryToCloud(
                            cRef = cRef,
                            invDao = resolvedInvDao,
                            actor = actor,
                            allowMasterUpdates = canManageMasterCatalogs,
                            allowInventoryReversal = isCloudAdmin || isCloudManager || isCloudAccountant
                        )
                    }
                }

                // 10c. Phase 4 Double-Entry General Ledger Sync (Accountant/Manager/Admin)
                val resolvedAccDao = accDaoOrNull()
                if (resolvedAccDao != null && (isCloudAdmin || isCloudManager || isCloudAccountant || isCloudDeputy)) {
                    runSyncStep("مزامنة القيود والمحاسبة العامة", "journal_entries", critical = false) {
                        pushPhase4AccountingToCloud(
                            cRef = cRef,
                            accDao = resolvedAccDao,
                            actor = actor,
                            allowChartWrite = isCloudAdmin || isCloudManager || isCloudAccountant,
                            allowEntryReversal = isCloudAdmin || isCloudAccountant
                        )
                    }
                }

                // 11. Cluster Server Info Metadata (Non-blocking)
                runCatching {
                    cRef.collection("server_status").document("cluster_meta").set(
                        mapOf(
                            "clusterCode" to cleanCluster,
                            "serverPlatform" to "Google Cloud Firestore Enterprise",
                            "projectId" to (firestore.app.options.projectId ?: "gen-lang-client-0220128746"),
                            "databaseId" to databaseId,
                            "status" to "ONLINE_ACTIVE",
                            "supportedClients" to listOf("Android", "Windows_Desktop", "Web_Portal"),
                            "egyptBranchLinked" to true,
                            "primaryGoodsCurrency" to "EGP",
                            "rawMaterialsSource" to "Egypt_Cairo_Hub",
                            "networkSyncAccuracy" to "100% Exact Cross-Node Sync",
                            "networkNodes" to listOf("SUDAN_FACTORY_NODE", "EGYPT_CAIRO_CENTRAL_WAREHOUSE", "HALFA_LOGISTICS_HUB"),
                            "totalUsers" to users.size,
                            "totalInvoices" to invoices.size,
                            "totalMovements" to movements.size,
                            "totalCustomers" to customers.size,
                            "totalFactoryItems" to factoryItems.size,
                            "totalFactoryMoves" to factoryMoves.size,
                            "totalHalfaInvoices" to halfaInvoices.size,
                            "lastBackupAt" to FieldValue.serverTimestamp(),
                            "lastUpdatedBy" to actor
                        ),
                        SetOptions.merge()
                    ).await()
                }

                _syncStepSummaries.value = stepSummaries

                if (criticalFailure != null) {
                    throw criticalFailure!!
                }
                if (anyFailure != null && syncedCount == 0) {
                    throw anyFailure!!
                }

                val failedStepNames = stepSummaries.filter { !it.success }.joinToString("، ") { it.stepName }
                if (failedStepNames.isNotBlank()) {
                    recordDiagnostic(
                        action = "SYNC_ALL_TO_CLOUD",
                        status = "WARNING",
                        count = syncedCount,
                        message = "تم حفظ $syncedCount سجلاً على السيرفر مع تعذر بعض الخطوات ($failedStepNames)",
                        deviceId = deviceId
                    )
                } else {
                    recordDiagnostic(
                        action = "SYNC_ALL_TO_CLOUD",
                        status = "SUCCESS",
                        count = syncedCount,
                        message = "تم حفظ ومزامنة $syncedCount سجلاً بنجاح على سيرفر Google Cloud دون المساس بالجلسات",
                        deviceId = deviceId
                    )
                }
                Log.i(TAG, "Successfully synced $syncedCount total ERP entities to cloud cluster $cleanCluster")
                syncedCount
            }.onFailure { err ->
                val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
                val entry = recordStructuredError(
                    operation = "SYNC_ALL_TO_CLOUD",
                    collectionPath = "erp_clusters/$cleanCluster",
                    throwable = err,
                    deviceId = deviceId
                )
                if (_syncStepSummaries.value.isEmpty()) {
                    _syncStepSummaries.value = listOf(
                        SyncStepSummary(
                            stepName = "مزامنة الكلاستر السحابي الكاملة",
                            collectionPath = "erp_clusters/$cleanCluster",
                            syncedCount = 0,
                            success = false,
                            message = "${entry.category.arabicTitle}: ${entry.sanitizedMessage}"
                        )
                    )
                }
                recordDiagnostic(
                    action = "SYNC_ALL_TO_CLOUD",
                    status = "FAILED",
                    count = 0,
                    message = "تعذر الحفظ على السيرفر (${entry.code}): ${entry.sanitizedMessage}",
                    deviceId = deviceId
                )
            }
        }
    }

    private fun accDaoOrNull(): ErpAccountingDao? = runCatching {
        PmpDatabase.getDatabase(firestore.app.applicationContext).erpAccountingDao()
    }.getOrNull()

    /**
     * Restores all data from Google Cloud Firestore down to the local device database.
     * Prevents data loss when updating versions, re-installing, or switching devices.
     */
    suspend fun restoreCloudToLocal(
        clusterCode: String,
        dao: PmpDao,
        currentActiveUserId: Long? = null,
        deviceId: String = "ANDROID-CLIENT",
        invDao: ErpInventoryDao? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            runCatching {
                val fbCurrentUser = runCatching { Firebase.auth.currentUser }.getOrNull()
                    ?: throw SecurityException(
                        "جلسة المصادقة السحابية غير نشطة — يرجى تفعيل الجلسة السحابية (بالبريد الإلكتروني أو عبر Google اختيارياً) لاستعادة البيانات."
                    )
                val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
                val localUsers = dao.getUserAccountsDirect()
                val activeLocalAcc = localUsers.firstOrNull {
                    (currentActiveUserId != null && it.id == currentActiveUserId) || it.isCurrentSession
                } ?: fbCurrentUser.email?.let { em ->
                    localUsers.firstOrNull { it.phoneOrEmail.equals(em, ignoreCase = true) }
                }
                ensureOwnClusterMemberRegistration(cleanCluster, activeLocalAcc, fbCurrentUser.uid, fbCurrentUser.email)
                resolveClusterMemberRoleKey(cleanCluster, fbCurrentUser.uid)
                    ?: throw SecurityException(
                        "الحساب الحالي (${fbCurrentUser.email ?: fbCurrentUser.uid.take(8)}) غير مربوط بدليل عضوية الكلاستر (cluster_members) على السيرفر."
                    )

                var restoredCount = 0
                val cRef = clusterRef(cleanCluster)

                // 0. Restore User Accounts preserving active local session
                val mergedUsersCount = internalPullAndMergeCloudUsers(cleanCluster, dao, currentActiveUserId, deviceId).getOrDefault(0)
                restoredCount += mergedUsersCount

                // 0.5 Fetch Tombstones and Cluster Epoch to prevent resurrecting deleted or pre-epoch records
                runCatching {
                    val epochSnap = cRef.collection("system_metadata").document("cluster_epoch").get().await()
                    val epochTime = epochSnap.getLong("wipeTimestamp") ?: 0L
                    val tombSnap = cRef.collection("deleted_records").get().await()
                    val remTombstones = tombSnap.documents.mapNotNull { it.data }
                    DeletedRecordsRegistry.mergeRemoteTombstones(remTombstones, epochTime)
                    DeletedRecordsRegistry.syncWithDao(dao)
                    PmpDatabase.purgeTombstonedRecordsLocally(dao)
                }

            // 1. Fetch Invoices from Server with deduplication & tombstone shield
            val localInvoices = dao.getInvoicesDirect()
            val invSnapshot = cRef.collection("invoices").get().await()
            val restoredInvoices = mutableListOf<InvoiceEntity>()
            val restoredItems = mutableListOf<InvoiceItemEntity>()
            val restoredPayments = mutableListOf<InvoicePaymentEntity>()

            for (doc in invSnapshot.documents) {
                val invNo = doc.getString("invoiceNo") ?: doc.id
                val existingInv = localInvoices.firstOrNull { it.invoiceNo == invNo }
                val invId = existingInv?.id ?: (doc.getLong("id") ?: System.currentTimeMillis())

                val isInvDeleted = DeletedRecordsRegistry.isDeleted("invoices", invId) ||
                    DeletedRecordsRegistry.isDeleted("invoices", invNo) ||
                    DeletedRecordsRegistry.isDeleted("invoices", doc.id)
                if (isInvDeleted) {
                    runCatching { doc.reference.delete() }
                    dao.deleteInvoiceById(invId)
                    continue
                }

                val invoice = InvoiceEntity(
                    id = invId,
                    invoiceNo = invNo,
                    customerId = doc.getLong("customerId") ?: existingInv?.customerId ?: 1L,
                    customerName = doc.getString("customerName") ?: existingInv?.customerName ?: "عميل",
                    invoiceDate = doc.getString("invoiceDate") ?: existingInv?.invoiceDate ?: "2026-10-26",
                    currency = doc.getString("currency") ?: existingInv?.currency ?: "SDG",
                    subtotal = doc.getDouble("subtotal") ?: existingInv?.subtotal ?: 0.0,
                    discount = doc.getDouble("discount") ?: existingInv?.discount ?: 0.0,
                    total = doc.getDouble("total") ?: existingInv?.total ?: 0.0,
                    notes = doc.getString("notes") ?: existingInv?.notes ?: "",
                    status = doc.getString("status") ?: existingInv?.status ?: "active",
                    cancelReason = doc.getString("cancelReason") ?: existingInv?.cancelReason,
                    createdBy = doc.getString("createdBy") ?: existingInv?.createdBy ?: "المدير"
                )
                restoredInvoices.add(invoice)

                val rawItems = doc.get("items") as? List<Map<String, Any>>
                rawItems?.forEachIndexed { idx, itemMap ->
                    val itemId = (itemMap["id"] as? Number)?.toLong()?.takeIf { it > 0 } ?: (invId * 100 + idx + 1)
                    restoredItems.add(
                        InvoiceItemEntity(
                            id = itemId,
                            invoiceId = invId,
                            description = itemMap["description"] as? String ?: "بند فاتورة",
                            quantity = (itemMap["quantity"] as? Number)?.toDouble() ?: 1.0,
                            unitPrice = (itemMap["unitPrice"] as? Number)?.toDouble() ?: 0.0,
                            lineTotal = (itemMap["lineTotal"] as? Number)?.toDouble() ?: 0.0,
                            productId = (itemMap["productId"] as? Number)?.toLong()?.takeIf { it > 0 },
                            warehouseId = (itemMap["warehouseId"] as? Number)?.toLong()?.takeIf { it > 0 }
                        )
                    )
                }

                val rawPays = doc.get("payments") as? List<Map<String, Any>>
                rawPays?.forEachIndexed { idx, payMap ->
                    val payId = (payMap["id"] as? Number)?.toLong()?.takeIf { it > 0 } ?: (invId * 100 + idx + 1)
                    restoredPayments.add(
                        InvoicePaymentEntity(
                            id = payId,
                            invoiceId = invId,
                            payDate = payMap["payDate"] as? String ?: "2026-10-26",
                            cashbox = payMap["cashbox"] as? String ?: "خزنة السودان",
                            amount = (payMap["amount"] as? Number)?.toDouble() ?: 0.0,
                            note = payMap["note"] as? String ?: "دفعة سحابية",
                            status = "active",
                            createdBy = (payMap["createdBy"] as? String) ?: "المدير"
                        )
                    )
                }
            }

            if (restoredInvoices.isNotEmpty()) {
                dao.upsertInvoices(restoredInvoices)
                restoredCount += restoredInvoices.size
            }
            if (restoredItems.isNotEmpty()) {
                dao.upsertInvoiceItems(restoredItems)
            }
            if (restoredPayments.isNotEmpty()) {
                dao.upsertInvoicePayments(restoredPayments)
            }

            // 2. Fetch Movements from Server with deduplication & strict alias tombstone shield
            val localMovements = dao.getMovementsDirect()
            val movSnapshot = cRef.collection("movements").get().await()
            val restoredMovements = movSnapshot.documents.mapNotNull { doc ->
                val ref = doc.getString("reference")?.trim() ?: doc.id
                val syncUuid = doc.getString("syncUuid")?.trim().orEmpty()
                val existingMov = localMovements.firstOrNull {
                    (syncUuid.isNotBlank() && it.syncUuid == syncUuid) ||
                        (ref.isNotBlank() && it.reference == ref) ||
                        it.cloudDocId == doc.id
                }
                val movId = existingMov?.id ?: (doc.getLong("id") ?: 0L)

                val docCreatedAt = doc.getLong("createdAt") ?: 0L
                val isMovDeleted = (movId > 0L && (
                    DeletedRecordsRegistry.isDeleted("movements", movId) ||
                        DeletedRecordsRegistry.isDeleted("movements", "MOV-$movId")
                    )) ||
                    DeletedRecordsRegistry.isDeleted("movements", doc.id) ||
                    (ref.isNotBlank() && DeletedRecordsRegistry.isDeleted("movements", ref)) ||
                    (syncUuid.isNotBlank() && DeletedRecordsRegistry.isDeleted("movements", syncUuid)) ||
                    DeletedRecordsRegistry.isPreEpoch(docCreatedAt)
                if (isMovDeleted) {
                    runCatching { doc.reference.delete() }
                    if (movId > 0L) dao.deleteMovementById(movId)
                    if (ref.isNotBlank()) dao.deleteMovementByReference(ref)
                    dao.deleteMovementByCloudDocId(doc.id)
                    if (syncUuid.isNotBlank()) dao.deleteMovementBySyncUuid(syncUuid)
                    return@mapNotNull null
                }

                MovementEntity(
                    id = movId,
                    txDate = doc.getString("txDate") ?: "2026-10-26",
                    cashbox = doc.getString("cashbox") ?: "خزنة السودان",
                    currency = doc.getString("currency") ?: "SDG",
                    direction = doc.getString("direction") ?: "وارد",
                    amount = doc.getDouble("amount") ?: 0.0,
                    description = doc.getString("description") ?: "",
                    reference = ref,
                    createdBy = doc.getString("createdBy") ?: "المدير",
                    createdAt = docCreatedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    cloudDocId = doc.id,
                    syncUuid = syncUuid,
                    updatedAt = doc.getLong("updatedAt") ?: 0L
                )
            }
            val distinctMovements = restoredMovements.distinctBy {
                when {
                    it.syncUuid.isNotBlank() -> "UUID-${it.syncUuid.trim()}"
                    it.reference.isNotBlank() -> "REF-${it.reference.trim()}"
                    it.cloudDocId.isNotBlank() -> "DOC-${it.cloudDocId.trim()}"
                    else -> "ID-${it.id}"
                }
            }
            if (distinctMovements.isNotEmpty()) {
                dao.upsertMovements(distinctMovements)
                restoredCount += distinctMovements.size
            }

            // 3. Fetch Transfers from Server with tombstone shield
            val trfSnapshot = cRef.collection("transfers").get().await()
            val restoredTransfers = trfSnapshot.documents.mapNotNull { doc ->
                val trfId = doc.getLong("id") ?: 0L
                val trfRef = doc.getString("reference")?.trim().orEmpty()
                val docCreatedAt = doc.getLong("createdAt") ?: 0L
                val isTrfDeleted = DeletedRecordsRegistry.isDeleted("transfers", trfId) ||
                    DeletedRecordsRegistry.isDeleted("transfers", "TRF-$trfId") ||
                    DeletedRecordsRegistry.isDeleted("transfers", doc.id) ||
                    (trfRef.isNotBlank() && DeletedRecordsRegistry.isDeleted("transfers", trfRef)) ||
                    DeletedRecordsRegistry.isPreEpoch(docCreatedAt)
                if (isTrfDeleted) {
                    runCatching { doc.reference.delete() }
                    dao.deleteTransferById(trfId)
                    if (trfRef.isNotBlank()) dao.deleteTransferByReference(trfRef)
                    return@mapNotNull null
                }
                TransferEntity(
                    id = trfId,
                    reference = doc.getString("reference") ?: doc.id,
                    txDate = doc.getString("txDate") ?: "2026-10-26",
                    sourceCashbox = doc.getString("sourceCashbox") ?: "خزنة السودان",
                    destinationCashbox = doc.getString("destinationCashbox") ?: "خزنة مصر",
                    sourceCurrency = doc.getString("sourceCurrency") ?: "SDG",
                    destinationCurrency = doc.getString("destinationCurrency") ?: "EGP",
                    sourceAmount = doc.getDouble("sourceAmount") ?: 0.0,
                    rate = doc.getDouble("rate") ?: 1.0,
                    destinationAmount = doc.getDouble("destinationAmount") ?: 0.0,
                    description = doc.getString("description") ?: "",
                    createdBy = doc.getString("createdBy") ?: "المدير",
                    createdAt = docCreatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
            }
            if (restoredTransfers.isNotEmpty()) {
                dao.upsertTransfers(restoredTransfers)
                restoredCount += restoredTransfers.size
            }

            // 4. Fetch Customers from Server with deduplication & tombstone shield
            val localCustomers = dao.getCustomersDirect()
            val custSnapshot = cRef.collection("customers").get().await()
            val restoredCustomers = custSnapshot.documents.mapNotNull { doc ->
                val name = doc.getString("name") ?: "عميل"
                val existingCust = localCustomers.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }
                val custId = existingCust?.id ?: (doc.getLong("id") ?: 0L)
                val isCustDeleted = DeletedRecordsRegistry.isDeleted("customers", custId) ||
                    DeletedRecordsRegistry.isDeleted("customers", "CUST-$custId") ||
                    DeletedRecordsRegistry.isDeleted("customers", doc.id)
                if (isCustDeleted) {
                    runCatching { doc.reference.delete() }
                    dao.deleteCustomer(custId)
                    return@mapNotNull null
                }
                CustomerEntity(
                    id = custId,
                    name = name,
                    phone = doc.getString("phone") ?: existingCust?.phone ?: "",
                    address = doc.getString("address") ?: existingCust?.address ?: "",
                    notes = doc.getString("notes") ?: existingCust?.notes ?: "",
                    active = doc.getBoolean("active") ?: existingCust?.active ?: true
                )
            }
            if (restoredCustomers.isNotEmpty()) {
                dao.upsertCustomers(restoredCustomers)
                restoredCount += restoredCustomers.size
            }

            // 5. Fetch Products from Server with deduplication & tombstone shield
            val localProducts = dao.getProductsDirect()
            val prodSnapshot = cRef.collection("products").get().await()
            val restoredProducts = prodSnapshot.documents.mapNotNull { doc ->
                val sku = doc.getString("sku") ?: ""
                val name = doc.getString("name") ?: "منتج"
                val existingProd = localProducts.firstOrNull { (sku.isNotBlank() && it.sku == sku) || it.name == name }
                val prodId = existingProd?.id ?: (doc.getLong("id") ?: 0L)
                if (DeletedRecordsRegistry.isDeleted("products", prodId) ||
                    DeletedRecordsRegistry.isDeleted("products", "PROD-$prodId") ||
                    DeletedRecordsRegistry.isDeleted("products", doc.id)
                ) {
                    runCatching { doc.reference.delete() }
                    dao.deleteProduct(prodId)
                    return@mapNotNull null
                }
                ProductEntity(
                    id = prodId,
                    sku = sku,
                    name = name,
                    category = doc.getString("category") ?: existingProd?.category ?: "بوهيات",
                    unit = doc.getString("unit") ?: existingProd?.unit ?: "كرتونة",
                    purchasePrice = doc.getDouble("purchasePrice") ?: existingProd?.purchasePrice ?: 0.0,
                    salePrice = doc.getDouble("salePrice") ?: existingProd?.salePrice ?: 0.0,
                    salePriceEgp = doc.getDouble("salePriceEgp") ?: existingProd?.salePriceEgp ?: 0.0,
                    minimumStock = doc.getDouble("minimumStock") ?: existingProd?.minimumStock ?: 10.0,
                    paintColor = doc.getString("paintColor") ?: existingProd?.paintColor ?: "أبيض",
                    packageWeight = doc.getString("packageWeight") ?: existingProd?.packageWeight ?: "900",
                    active = doc.getBoolean("active") ?: existingProd?.active ?: true
                )
            }
            if (restoredProducts.isNotEmpty()) {
                dao.upsertProducts(restoredProducts)
                restoredCount += restoredProducts.size
            }

            // 5b. Fetch Poster Prices from Server with strict SDG & EGP separation
            runCatching {
                val ppSnapshot = cRef.collection("poster_prices").get().await()
                val restoredPosterPrices = ppSnapshot.documents.mapNotNull { doc ->
                    val rawId = doc.getString("id") ?: doc.id
                    val weight = doc.getString("weight") ?: return@mapNotNull null
                    val kind = doc.getString("kind") ?: return@mapNotNull null
                    val rawCurrency = doc.getString("currency") ?: if (rawId.endsWith("_EGP")) "EGP" else "SDG"
                    val isEgp = rawCurrency.equals("EGP", ignoreCase = true) || rawId.endsWith("_EGP")
                    val rawPrice = doc.getLong("price") ?: 0L
                    val rawPriceEgp = doc.getLong("priceEgp") ?: 0L
                    val cleanPrice = if (isEgp && rawPriceEgp > 0L && rawPrice == rawPriceEgp * 200L) rawPriceEgp else rawPrice
                    if (cleanPrice <= 0L) return@mapNotNull null
                    PosterPriceEntity(
                        id = if (isEgp) "${weight}_${kind}_EGP" else "${weight}_$kind",
                        weight = weight,
                        kind = kind,
                        price = cleanPrice,
                        priceEgp = if (isEgp) cleanPrice else 0L,
                        currency = if (isEgp) "EGP" else "SDG"
                    )
                }
                if (restoredPosterPrices.isNotEmpty()) {
                    dao.insertPosterPrices(restoredPosterPrices)
                    restoredCount += restoredPosterPrices.size
                }
            }

            // 5c. Fetch Registered Package Weights from Server
            runCatching {
                val appCtx = firestore.app.applicationContext
                val weightMgr = com.example.util.InventoryWeightManager.getInstance(appCtx)
                val weightsDoc = cRef.collection("config").document("inventory_weights").get().await()
                if (weightsDoc.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val rawWeights = weightsDoc.get("weights") as? List<Map<String, Any?>>
                    val weightsJson = weightsDoc.getString("weightsJson")
                    val defaultWeight = weightsDoc.getString("defaultWeight")
                    weightMgr.mergeCloudWeightsFromFirestoreDoc(weightsJson, rawWeights, defaultWeight)
                }
                val currentProdWeights = dao.getProductsDirect().map { it.packageWeight }
                weightMgr.syncDiscoveredProductWeights(currentProdWeights)
            }

            // 6. Fetch Warehouses and Cashboxes (with tombstone shield & strict name deduplication)
            val initialLocalWh = dao.getWarehousesDirect()
            initialLocalWh.forEach { wh ->
                if (DeletedRecordsRegistry.isDeleted("warehouses", wh.id) ||
                    DeletedRecordsRegistry.isDeleted("warehouses", "WH-${wh.id}") ||
                    DeletedRecordsRegistry.isDeleted("warehouses", wh.name)
                ) {
                    dao.clearStockMovesByWarehouse(wh.id)
                    dao.deleteWarehouse(wh.id)
                }
            }
            val localWh = dao.getWarehousesDirect()
            val whSnapshot = cRef.collection("warehouses").get().await()
            val restoredWarehouses = whSnapshot.documents.mapNotNull { doc ->
                val name = doc.getString("name")?.trim() ?: return@mapNotNull null
                if (name.isBlank()) return@mapNotNull null
                val remoteId = doc.getLong("id") ?: 0L
                if (DeletedRecordsRegistry.isDeleted("warehouses", name) ||
                    DeletedRecordsRegistry.isDeleted("warehouses", doc.id) ||
                    (remoteId > 0L && (
                        DeletedRecordsRegistry.isDeleted("warehouses", remoteId) ||
                            DeletedRecordsRegistry.isDeleted("warehouses", "WH-$remoteId")
                        ))
                ) {
                    runCatching { doc.reference.delete() }
                    localWh.filter { it.name.trim().equals(name, ignoreCase = true) || (remoteId > 0L && it.id == remoteId) }
                        .forEach {
                            dao.clearStockMovesByWarehouse(it.id)
                            dao.deleteWarehouse(it.id)
                        }
                    return@mapNotNull null
                }
                val existing = localWh.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
                val whType = doc.getString("warehouseType") ?: existing?.warehouseType ?: "مبيعات"
                val whClass = WarehouseClass.fromRaw(doc.getString("warehouseClass") ?: whType.ifBlank { name })
                WarehouseEntity(
                    id = existing?.id ?: remoteId,
                    name = name,
                    active = doc.getBoolean("active") ?: existing?.active ?: true,
                    currency = doc.getString("currency") ?: existing?.currency ?: "SDG",
                    linkedCashbox = doc.getString("linkedCashbox") ?: existing?.linkedCashbox ?: "خزنة السودان",
                    warehouseType = whType,
                    warehouseClass = whClass.name,
                    code = doc.getString("code") ?: existing?.code ?: "WH-${existing?.id ?: remoteId}",
                    branch = doc.getString("branch") ?: existing?.branch ?: "",
                    allowDirectSales = doc.getBoolean("allowDirectSales") ?: (whClass == WarehouseClass.SALES),
                    allowProductionReceipt = doc.getBoolean("allowProductionReceipt") ?: (whClass == WarehouseClass.FINISHED_GOODS),
                    syncUuid = doc.getString("syncUuid") ?: existing?.syncUuid ?: "",
                    updatedAt = doc.safeLong("updatedAt") ?: existing?.updatedAt ?: 0L,
                    recordVersion = (doc.safeLong("recordVersion")?.toInt()) ?: existing?.recordVersion ?: 1
                )
            }.distinctBy { it.name.trim().lowercase() }
            if (restoredWarehouses.isNotEmpty()) {
                dao.upsertWarehouses(restoredWarehouses)
                restoredCount += restoredWarehouses.size
            }

            val initialLocalCb = dao.getCashboxesDirect()
            initialLocalCb.forEach { cb ->
                val cbDocId = DeletedRecordsRegistry.cashboxDocId(cb.name, cb.id)
                if (DeletedRecordsRegistry.isDeleted("cashboxes", cb.id) ||
                    DeletedRecordsRegistry.isDeleted("cashboxes", "BOX-${cb.id}") ||
                    DeletedRecordsRegistry.isDeleted("cashboxes", cb.name) ||
                    DeletedRecordsRegistry.isDeleted("cashboxes", cbDocId)
                ) {
                    dao.deleteCashbox(cb.id)
                }
            }
            val localCb = dao.getCashboxesDirect()
            val cbSnapshot = cRef.collection("cashboxes").get().await()
            val restoredCashboxes = cbSnapshot.documents.mapNotNull { doc ->
                val name = doc.getString("name")?.trim() ?: return@mapNotNull null
                if (name.isBlank()) return@mapNotNull null
                val remoteId = doc.getLong("id") ?: 0L
                val cbDocId = DeletedRecordsRegistry.cashboxDocId(name, remoteId)
                if (DeletedRecordsRegistry.isDeleted("cashboxes", name) ||
                    DeletedRecordsRegistry.isDeleted("cashboxes", cbDocId) ||
                    DeletedRecordsRegistry.isDeleted("cashboxes", doc.id) ||
                    (remoteId > 0L && DeletedRecordsRegistry.isDeleted("cashboxes", remoteId))
                ) {
                    runCatching { doc.reference.delete() }
                    localCb.filter { it.name.trim().equals(name, ignoreCase = true) || (remoteId > 0L && it.id == remoteId) }
                        .forEach { dao.deleteCashbox(it.id) }
                    return@mapNotNull null
                }
                val existing = localCb.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
                CashboxEntity(
                    id = existing?.id ?: remoteId,
                    name = name
                )
            }
            val distinctCashboxes = restoredCashboxes.distinctBy { it.name.trim().lowercase() }
            if (distinctCashboxes.isNotEmpty()) {
                dao.upsertCashboxes(distinctCashboxes)
                restoredCount += distinctCashboxes.size
            }

            // 7. Fetch Factory Items & Raw Materials (Egypt Linked)
            val fiSnapshot = cRef.collection("factory_items").get().await()
            val restoredFactoryItems = fiSnapshot.documents.mapNotNull { doc ->
                val id = doc.getLong("id") ?: return@mapNotNull null
                val name = doc.getString("name") ?: "خامة"
                if (DeletedRecordsRegistry.isDeleted("factory_items", id) ||
                    DeletedRecordsRegistry.isDeleted("factory_items", doc.id) ||
                    DeletedRecordsRegistry.isDeleted("factory_items", name)
                ) {
                    return@mapNotNull null
                }
                FactoryItemEntity(
                    id = id,
                    name = name,
                    unit = doc.getString("unit") ?: "كجم",
                    category = doc.getString("category") ?: "raw",
                    itemCode = doc.getString("itemCode") ?: "RAW-$id",
                    unitCostEgp = doc.safeDouble("unitCostEgp") ?: 0.0,
                    supplierName = doc.getString("supplierName") ?: "",
                    lastPurchasePrice = doc.safeDouble("lastPurchasePrice") ?: (doc.safeDouble("unitCostEgp") ?: 0.0),
                    lastPriceCurrency = doc.getString("lastPriceCurrency") ?: "EGP",
                    lastPriceUpdatedAt = doc.safeLong("lastPriceUpdatedAt") ?: 0L,
                    density = doc.safeDouble("density") ?: 1.0,
                    solidsPercent = doc.safeDouble("solidsPercent") ?: 100.0,
                    minStock = doc.safeDouble("minStock") ?: 0.0,
                    maxStock = doc.safeDouble("maxStock") ?: 0.0,
                    batchTrackingRequired = doc.safeBoolean("batchTrackingRequired") ?: false,
                    syncUuid = doc.getString("syncUuid") ?: "FI-UUID-$id",
                    recordVersion = doc.safeLong("recordVersion")?.toInt() ?: 1
                )
            }
            if (restoredFactoryItems.isNotEmpty()) {
                dao.upsertFactoryItems(restoredFactoryItems)
                restoredCount += restoredFactoryItems.size
            }

            // 8. Fetch Factory Moves (with tombstone & epoch shield)
            val fmSnapshot = cRef.collection("factory_moves").get().await()
            val restoredFactoryMoves = fmSnapshot.documents.mapNotNull { doc ->
                val id = doc.getLong("id") ?: return@mapNotNull null
                val createdAt = doc.getLong("createdAt") ?: 0L
                if (DeletedRecordsRegistry.isPreEpoch(createdAt) ||
                    DeletedRecordsRegistry.isDeleted("factory_moves", id) ||
                    DeletedRecordsRegistry.isDeleted("factory_moves", "FM-$id") ||
                    DeletedRecordsRegistry.isDeleted("factory_moves", doc.id)
                ) {
                    runCatching { doc.reference.delete() }
                    dao.deleteFactoryMoveById(id)
                    return@mapNotNull null
                }
                FactoryMoveEntity(
                    id = id,
                    moveDate = doc.getString("moveDate") ?: "2026-10-26",
                    kind = doc.getString("kind") ?: "RAW_PURCHASE",
                    itemId = doc.getLong("itemId") ?: 1L,
                    itemName = doc.getString("itemName") ?: "خامة",
                    qty = doc.getDouble("qty") ?: 1.0,
                    machineId = doc.getLong("machineId")?.takeIf { it > 0 },
                    machineName = doc.getString("machineName"),
                    batch = doc.getString("batch") ?: "",
                    note = doc.getString("note") ?: "",
                    actor = doc.getString("actor") ?: "مسؤول المواد الخام",
                    counterparty = doc.getString("counterparty") ?: "مورد مصر",
                    linked = doc.getBoolean("linked") ?: false,
                    createdAt = createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
            }
            if (restoredFactoryMoves.isNotEmpty()) {
                dao.upsertFactoryMoves(restoredFactoryMoves)
                restoredCount += restoredFactoryMoves.size
            }

            // 8b & 8c. Restore Chemical Formulations & Daily Production Batches
            restoredCount += restoreLabAndBatchesFromCloud(cRef, dao)

            // 9 & 10. Restore Stock Moves & Halfa Invoices
            restoredCount += restoreStockMovesAndHalfaInvoicesFromCloud(cRef, dao)

            // 10b. Restore Phase 2 Packaging Rules, Inventory Movements, Balances & Transfers
            val resolvedInvDao = invDao ?: runCatching {
                PmpDatabase.getDatabase(firestore.app.applicationContext).erpInventoryDao()
            }.getOrNull()
            if (resolvedInvDao != null) {
                restoredCount += restorePhase2InventoryFromCloud(cRef, resolvedInvDao)
            }

            // 10c. Restore Phase 4 Double-Entry General Ledger
            val resolvedAccDao = runCatching {
                PmpDatabase.getDatabase(firestore.app.applicationContext).erpAccountingDao()
            }.getOrNull()
            if (resolvedAccDao != null) {
                restoredCount += restorePhase4AccountingFromCloud(cRef, resolvedAccDao)
            }

            // Clean any residual duplicates across cashboxes, warehouses, transactions, and user accounts
            runCatching {
                dao.removeDuplicateCashboxes()
                dao.removeDuplicateWarehouses()
                dao.removeDuplicateMovements()
                dao.removeDuplicateUserAccounts()
            }

            recordDiagnostic(
                action = "RESTORE_CLOUD_TO_LOCAL",
                status = "SUCCESS",
                count = restoredCount,
                message = "تمت استعادة $restoredCount سجلاً بنجاح من السيرفر مع منع تكرار الفواتير وحماية الأرصدة والجلسة",
                deviceId = deviceId
            )
            Log.i(TAG, "Successfully restored $restoredCount entities from Google Cloud server")
            restoredCount
        }.onFailure { err ->
            recordDiagnostic(
                action = "RESTORE_CLOUD_TO_LOCAL",
                status = "FAILED",
                count = 0,
                message = "تعذرت استعادة البيانات من السيرفر: ${err.message}",
                deviceId = deviceId
            )
        }
    }
}

    private suspend fun restoreLabAndBatchesFromCloud(
        cRef: com.google.firebase.firestore.DocumentReference,
        dao: PmpDao
    ): Int {
        var restoredCount = 0
        // 8b. Fetch Chemical Formulations from Cloud (with RBAC/permission graceful fallback to non-secret summaries)
        val formSnapshot = runCatching { cRef.collection("lab_formulations").get().await() }.getOrNull()
            ?: runCatching { cRef.collection("lab_formula_summaries").get().await() }.getOrNull()
        val restoredFormulations = formSnapshot?.documents.orEmpty().mapNotNull { doc ->
            val id = doc.getLong("id") ?: return@mapNotNull null
            val fNo = doc.getString("formulaNo") ?: doc.id
            val createdAt = doc.getLong("createdAt") ?: 0L
            if (DeletedRecordsRegistry.isPreEpoch(createdAt) ||
                DeletedRecordsRegistry.isDeleted("lab_formulations", id) ||
                DeletedRecordsRegistry.isDeleted("lab_formulations", "FORM-$id") ||
                DeletedRecordsRegistry.isDeleted("lab_formulations", doc.id) ||
                (fNo.isNotBlank() && DeletedRecordsRegistry.isDeleted("lab_formulations", fNo))
            ) {
                runCatching { doc.reference.delete() }
                dao.deleteFormulation(id)
                return@mapNotNull null
            }
            val approvedFlag = doc.getBoolean("approvedForProduction") ?: false
            FormulationEntity(
                id = id,
                syncUuid = doc.getString("syncUuid") ?: "",
                formulaNo = fNo,
                productName = doc.getString("productName") ?: "تركيبة معملية",
                linkedProductId = doc.getLong("linkedProductId")?.takeIf { it > 0L },
                linkedProductSku = doc.getString("linkedProductSku") ?: "",
                category = doc.getString("category") ?: "بوهيات",
                paintType = doc.getString("paintType") ?: "مطفي معماري (Matt)",
                baseKind = doc.getString("baseKind") ?: "أبيض",
                versionNumber = (doc.getLong("versionNumber") ?: 1L).toInt().coerceAtLeast(1),
                approvalStatus = doc.getString("approvalStatus") ?: if (approvedFlag) "APPROVED" else "DRAFT",
                approvedBy = doc.getString("approvedBy") ?: "",
                approvedAt = doc.getLong("approvedAt") ?: 0L,
                targetPvc = doc.getDouble("targetPvc") ?: 0.0,
                calculatedPvc = doc.getDouble("calculatedPvc") ?: 0.0,
                cpvcEstimated = doc.getDouble("cpvcEstimated") ?: 52.0,
                totalSolidsWeight = doc.getDouble("totalSolidsWeight") ?: 0.0,
                totalSolidsVolume = doc.getDouble("totalSolidsVolume") ?: 0.0,
                totalBatchWeight = doc.getDouble("totalBatchWeight") ?: 0.0,
                totalBatchVolume = doc.getDouble("totalBatchVolume") ?: 0.0,
                calculatedDensity = doc.getDouble("calculatedDensity") ?: 0.0,
                totalCost = doc.getDouble("totalCost") ?: 0.0,
                costPerKg = doc.getDouble("costPerKg") ?: 0.0,
                historicalTotalCost = doc.getDouble("historicalTotalCost") ?: 0.0,
                historicalCostPerKg = doc.getDouble("historicalCostPerKg") ?: 0.0,
                currency = doc.getString("currency") ?: "EGP",
                changeReason = doc.getString("changeReason") ?: "",
                updatedBy = doc.getString("updatedBy") ?: "",
                updatedAt = doc.getLong("updatedAt") ?: 0L,
                ingredientsJson = doc.getString("ingredientsJson") ?: "[]",
                confidentialCiphertext = doc.getString("confidentialCiphertext") ?: "",
                isConfidentialSealed = doc.getBoolean("isConfidentialSealed") ?: false,
                technicalAnalysis = doc.getString("technicalAnalysis") ?: "",
                notes = doc.getString("notes") ?: "",
                approvedForProduction = approvedFlag,
                createdBy = doc.getString("createdBy") ?: "مهندس المختبر الكيميائي",
                createdAt = createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }
        if (restoredFormulations.isNotEmpty()) {
            dao.upsertFormulations(restoredFormulations)
            restoredCount += restoredFormulations.size
        }

        // 8c. Fetch Daily Production Batches from Cloud (with tombstone & epoch shield)
        val batchSnapshot = cRef.collection("paint_batches").get().await()
        val restoredBatches = batchSnapshot.documents.mapNotNull { doc ->
            val id = doc.getLong("id") ?: return@mapNotNull null
            val bNo = doc.getString("batchNumber") ?: doc.id
            val createdAt = doc.getLong("createdAt") ?: 0L
            if (DeletedRecordsRegistry.isPreEpoch(createdAt) ||
                DeletedRecordsRegistry.isDeleted("paint_batches", id) ||
                DeletedRecordsRegistry.isDeleted("paint_batches", "BATCH-$id") ||
                DeletedRecordsRegistry.isDeleted("paint_batches", doc.id) ||
                (bNo.isNotBlank() && DeletedRecordsRegistry.isDeleted("paint_batches", bNo))
            ) {
                runCatching { doc.reference.delete() }
                dao.deleteProductionBatch(id)
                return@mapNotNull null
            }
            ProductionBatchEntity(
                id = id,
                batchNumber = bNo,
                productionDate = doc.getString("productionDate") ?: "",
                formulaId = doc.getLong("formulaId"),
                formulaNo = doc.getString("formulaNo") ?: "",
                productName = doc.getString("productName") ?: "تشغيلة دهان",
                paintType = doc.getString("paintType") ?: "مطفي معماري (Matt)",
                baseKind = doc.getString("baseKind") ?: "أبيض",
                targetPvc = doc.getDouble("targetPvc") ?: 0.0,
                targetWeightKg = doc.getDouble("targetWeightKg") ?: 0.0,
                actualWeightKg = doc.getDouble("actualWeightKg") ?: 0.0,
                targetVolumeLiters = doc.getDouble("targetVolumeLiters") ?: 0.0,
                cartonsCount = doc.getLong("cartonsCount")?.toInt() ?: 0,
                packageUnit = doc.getString("packageUnit") ?: "كرتونة 900 مل",
                machineId = doc.getLong("machineId"),
                machineName = doc.getString("machineName") ?: "",
                qualityStatus = doc.getString("qualityStatus") ?: "معتمد ومطابق",
                measuredDensity = doc.getDouble("measuredDensity") ?: 0.0,
                measuredViscosity = doc.getString("measuredViscosity") ?: "",
                scaledIngredientsJson = doc.getString("scaledIngredientsJson") ?: "[]",
                supervisorOrEngineer = doc.getString("supervisorOrEngineer") ?: "مهندس الإنتاج",
                shift = doc.getString("shift") ?: "الوردية الصباحية",
                notes = doc.getString("notes") ?: "",
                syncStatus = "SYNCED",
                createdAt = createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }
        if (restoredBatches.isNotEmpty()) {
            dao.upsertProductionBatches(restoredBatches)
            restoredCount += restoredBatches.size
        }
        return restoredCount
    }

    private suspend fun restoreStockMovesAndHalfaInvoicesFromCloud(
        cRef: com.google.firebase.firestore.DocumentReference,
        dao: PmpDao
    ): Int {
        var restoredCount = 0
        // 9. Fetch Stock Moves (with tombstone shield & deleted-product/warehouse cleanup)
        val currentProdIds = dao.getProductsDirect().map { it.id }.toSet()
        val smSnapshot = cRef.collection("stock_moves").get().await()
        val restoredStockMoves = smSnapshot.documents.mapNotNull { doc ->
            val id = doc.getLong("id") ?: return@mapNotNull null
            val prodId = doc.getLong("productId") ?: 0L
            val whId = doc.getLong("warehouseId") ?: 0L
            val docCreatedAt = doc.getLong("createdAt") ?: 0L
            val isMoveDeleted = DeletedRecordsRegistry.isDeleted("stock_moves", id) ||
                DeletedRecordsRegistry.isDeleted("stock_moves", "SM-$id") ||
                DeletedRecordsRegistry.isDeleted("stock_moves", doc.id) ||
                DeletedRecordsRegistry.isDeleted("products", prodId) ||
                DeletedRecordsRegistry.isDeleted("warehouses", whId) ||
                DeletedRecordsRegistry.isPreEpoch(docCreatedAt) ||
                (currentProdIds.isNotEmpty() && prodId !in currentProdIds)
            if (isMoveDeleted) {
                runCatching { doc.reference.delete() }
                dao.deleteStockMoveById(id)
                return@mapNotNull null
            }
            StockMoveEntity(
                id = id,
                warehouseId = whId.takeIf { it > 0L } ?: 1L,
                productId = prodId.takeIf { it > 0L } ?: 1L,
                quantity = doc.getDouble("quantity") ?: 1.0,
                kind = doc.getString("kind") ?: "IN",
                invoiceId = doc.getLong("invoiceId")?.takeIf { it > 0 },
                note = doc.getString("note") ?: "",
                createdBy = doc.getString("createdBy") ?: "المدير",
                createdAt = docCreatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }
        if (restoredStockMoves.isNotEmpty()) {
            dao.upsertStockMoves(restoredStockMoves)
            restoredCount += restoredStockMoves.size
        }

        // 10. Fetch Halfa & Egypt Invoices (with tombstone & epoch shield)
        val hiSnapshot = cRef.collection("halfa_invoices").get().await()
        val restoredHalfaInvoices = hiSnapshot.documents.mapNotNull { doc ->
            val id = doc.getLong("id") ?: return@mapNotNull null
            val invNo = doc.getString("invoiceNo") ?: "HINV-$id"
            val createdAt = doc.getLong("createdAt") ?: 0L
            if (DeletedRecordsRegistry.isPreEpoch(createdAt) ||
                DeletedRecordsRegistry.isDeleted("halfa_invoices", id) ||
                DeletedRecordsRegistry.isDeleted("halfa_invoices", "HINV-$id") ||
                DeletedRecordsRegistry.isDeleted("halfa_invoices", doc.id) ||
                DeletedRecordsRegistry.isDeleted("halfa_invoices", invNo)
            ) {
                runCatching { doc.reference.delete() }
                dao.deleteHalfaInvoiceById(id)
                return@mapNotNull null
            }
            HalfaInvoiceEntity(
                id = id,
                invoiceNo = invNo,
                invoiceDate = doc.getString("invoiceDate") ?: "2026-10-26",
                cashbox = doc.getString("cashbox") ?: "خزنة مصر",
                currency = doc.getString("currency") ?: "EGP",
                warehouseId = doc.getLong("warehouseId") ?: 1L,
                productId = doc.getLong("productId") ?: 1L,
                productName = doc.getString("productName") ?: "منتج",
                quantity = doc.getDouble("quantity") ?: 1.0,
                unitPrice = doc.getDouble("unitPrice") ?: 0.0,
                total = doc.getDouble("total") ?: 0.0,
                factoryMoveId = doc.getLong("factoryMoveId")?.takeIf { it > 0 },
                note = doc.getString("note") ?: "",
                status = doc.getString("status") ?: "active",
                createdBy = doc.getString("createdBy") ?: "مسؤول التحميل",
                createdAt = createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }
        if (restoredHalfaInvoices.isNotEmpty()) {
            dao.upsertHalfaInvoices(restoredHalfaInvoices)
            restoredCount += restoredHalfaInvoices.size
        }
        return restoredCount
    }

    private suspend fun pushPhase2InventoryToCloud(
        cRef: com.google.firebase.firestore.DocumentReference,
        invDao: ErpInventoryDao,
        actor: String,
        allowMasterUpdates: Boolean = true,
        allowInventoryReversal: Boolean = true
    ): Int {
        var count = 0
        val existingPkgIds = if (!allowMasterUpdates) {
            runCatching {
                cRef.collection("packaging_rules").get().await().documents.map { it.id }.toSet()
            }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        val pkgRules = invDao.getPackagingRulesDirect()
            .filter { it.weightGrams > 0 && it.cansPerCarton > 0 }
            .filter { allowMasterUpdates || safeId(it.syncUuid.ifBlank { "PKG-RULE-${it.weightGrams}" }, "PKG-RULE-${it.weightGrams}") !in existingPkgIds }
        pkgRules.chunked(250).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { rule ->
                val docId = safeId(rule.syncUuid.ifBlank { "PKG-RULE-${rule.weightGrams}" }, "PKG-RULE-${rule.weightGrams}")
                batch.set(
                    cRef.collection("packaging_rules").document(docId),
                    mapOf(
                        "id" to rule.id,
                        "syncUuid" to rule.syncUuid,
                        "weightGrams" to rule.weightGrams,
                        "label" to rule.label,
                        "cansPerCarton" to rule.cansPerCarton,
                        "defaultEmptyCanCostEgp" to rule.defaultEmptyCanCostEgp,
                        "defaultLidCostEgp" to rule.defaultLidCostEgp,
                        "defaultLabelCostEgp" to rule.defaultLabelCostEgp,
                        "defaultCartonBoxCostEgp" to rule.defaultCartonBoxCostEgp,
                        "active" to rule.active,
                        "updatedBy" to rule.updatedBy,
                        "recordVersion" to rule.recordVersion,
                        "updatedAt" to rule.updatedAt,
                        "serverUpdatedBy" to actor,
                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }
            batch.commit().await()
            count += chunk.size
        }

        // inventory_movements is append-only in firestore.rules (updates only allowed for POSTED -> REVERSED by Manager/Accountant/Admin)
        val existingInvMovementStatuses = runCatching {
            cRef.collection("inventory_movements").get().await().documents.associate {
                it.id to (it.getString("status") ?: "POSTED").trim().uppercase()
            }
        }.getOrDefault(emptyMap())
        val invMovements = invDao.getInventoryMovementsDirect().filter { it.quantity > 0.0 }
        val invMovementsToWrite = invMovements.mapNotNull { im ->
            val docId = safeId(im.syncUuid.ifBlank { "IM-${im.id}" }, "IM-${im.id}")
            val remoteStatus = existingInvMovementStatuses[docId]
            if (remoteStatus == null) {
                Triple(docId, true, im)
            } else if (allowInventoryReversal &&
                remoteStatus == "POSTED" &&
                im.status.trim().equals("REVERSED", ignoreCase = true)
            ) {
                Triple(docId, false, im)
            } else {
                null
            }
        }
        invMovementsToWrite.chunked(250).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { (docId, isCreate, im) ->
                val docRef = cRef.collection("inventory_movements").document(docId)
                if (isCreate) {
                    batch.set(
                        docRef,
                        mapOf(
                            "id" to im.id,
                            "syncUuid" to im.syncUuid,
                            "documentNo" to im.documentNo,
                            "movementType" to im.movementType.ifBlank { "STOCK_ADJUSTMENT" }.take(60),
                            "warehouseId" to im.warehouseId,
                            "warehouseCode" to im.warehouseCode,
                            "warehouseClass" to im.warehouseClass,
                            "itemCategory" to im.itemCategory,
                            "itemId" to im.itemId,
                            "itemCode" to im.itemCode,
                            "itemName" to im.itemName,
                            "quantity" to im.quantity,
                            "signedQuantity" to im.signedQuantity,
                            "unit" to im.unit,
                            "unitCost" to im.unitCost,
                            "totalCost" to im.totalCost,
                            "currency" to im.currency,
                            "batchNumber" to im.batchNumber,
                            "expiryDate" to im.expiryDate,
                            "referenceDocType" to im.referenceDocType,
                            "referenceDocId" to (im.referenceDocId ?: 0L),
                            "referenceDocNo" to im.referenceDocNo,
                            "reversedMovementId" to (im.reversedMovementId ?: 0L),
                            "reason" to im.reason,
                            "status" to im.status,
                            "qcApproved" to im.qcApproved,
                            "idempotencyKey" to im.idempotencyKey,
                            "performedBy" to im.performedBy,
                            "approvedBy" to im.approvedBy,
                            "deviceId" to im.deviceId,
                            "recordVersion" to im.recordVersion,
                            "createdAt" to im.createdAt,
                            "updatedAt" to im.updatedAt,
                            "serverUpdatedBy" to actor,
                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                } else {
                    batch.set(
                        docRef,
                        mapOf(
                            "status" to "REVERSED",
                            "reversedByEntryNo" to im.referenceDocNo.ifBlank { "REV-${im.id}" },
                            "reversalReason" to im.reason.ifBlank { "عكس حركة مخزنية" },
                            "recordVersion" to im.recordVersion.coerceAtLeast(2),
                            "updatedAt" to System.currentTimeMillis(),
                            "serverUpdatedBy" to actor,
                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                }
            }
            batch.commit().await()
            count += chunk.size
        }

        val stockBalances = invDao.getStockBalancesDirect()
        stockBalances.chunked(250).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { bal ->
                val docId = safeId(bal.syncUuid.ifBlank { "BAL-${bal.warehouseId}-${bal.itemId}" }, "BAL-${bal.id}")
                batch.set(
                    cRef.collection("warehouse_stock_balances").document(docId),
                    mapOf(
                        "id" to bal.id,
                        "syncUuid" to bal.syncUuid,
                        "warehouseId" to bal.warehouseId,
                        "warehouseClass" to bal.warehouseClass,
                        "itemCategory" to bal.itemCategory,
                        "itemId" to bal.itemId,
                        "itemCode" to bal.itemCode,
                        "itemName" to bal.itemName,
                        "unit" to bal.unit,
                        "batchNumber" to bal.batchNumber,
                        "expiryDate" to bal.expiryDate,
                        "onHandQty" to bal.onHandQty.coerceAtLeast(0.0),
                        "reservedQty" to bal.reservedQty.coerceAtLeast(0.0),
                        "inTransitQty" to bal.inTransitQty.coerceAtLeast(0.0),
                        "quarantineQty" to bal.quarantineQty.coerceAtLeast(0.0),
                        "availableQty" to bal.availableQty.coerceAtLeast(0.0),
                        "avgUnitCost" to bal.avgUnitCost,
                        "currency" to bal.currency,
                        "lastMovementUuid" to bal.lastMovementUuid,
                        "updatedAt" to bal.updatedAt,
                        "serverUpdatedBy" to actor,
                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }
            batch.commit().await()
            count += chunk.size
        }

        val whTransfers = invDao.getWarehouseTransfersDirect()
        whTransfers.chunked(250).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { trf ->
                val docId = safeId(trf.transferNo.ifBlank { "WHTRF-${trf.id}" }, "WHTRF-${trf.id}")
                batch.set(
                    cRef.collection("warehouse_transfers").document(docId),
                    mapOf(
                        "id" to trf.id,
                        "syncUuid" to trf.syncUuid,
                        "transferNo" to trf.transferNo,
                        "transferDate" to trf.transferDate,
                        "sourceWarehouseId" to trf.sourceWarehouseId,
                        "sourceWarehouseName" to trf.sourceWarehouseName,
                        "sourceWarehouseClass" to trf.sourceWarehouseClass,
                        "destinationWarehouseId" to trf.destinationWarehouseId,
                        "destinationWarehouseName" to trf.destinationWarehouseName,
                        "destinationWarehouseClass" to trf.destinationWarehouseClass,
                        "status" to trf.status,
                        "totalCost" to trf.totalCost,
                        "currency" to trf.currency,
                        "notes" to trf.notes,
                        "rejectionReason" to trf.rejectionReason,
                        "createdBy" to trf.createdBy,
                        "reviewedBy" to trf.reviewedBy,
                        "approvedBy" to trf.approvedBy,
                        "dispatchedBy" to trf.dispatchedBy,
                        "receivedBy" to trf.receivedBy,
                        "dispatchedAt" to trf.dispatchedAt,
                        "receivedAt" to trf.receivedAt,
                        "timelineJson" to trf.timelineJson,
                        "idempotencyKey" to trf.idempotencyKey,
                        "deviceId" to trf.deviceId,
                        "recordVersion" to trf.recordVersion,
                        "createdAt" to trf.createdAt,
                        "updatedAt" to trf.updatedAt,
                        "serverUpdatedBy" to actor,
                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }
            batch.commit().await()
            count += chunk.size
        }

        val trfItems = invDao.getAllTransferItemsDirect()
        trfItems.chunked(250).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { item ->
                val docId = safeId("WHTI-${item.transferId}-${item.id}", "WHTI-${item.id}")
                batch.set(
                    cRef.collection("warehouse_transfer_items").document(docId),
                    mapOf(
                        "id" to item.id,
                        "transferId" to item.transferId,
                        "transferUuid" to item.transferUuid,
                        "itemCategory" to item.itemCategory,
                        "itemId" to item.itemId,
                        "itemCode" to item.itemCode,
                        "itemName" to item.itemName,
                        "unit" to item.unit,
                        "requestedQty" to item.requestedQty,
                        "approvedQty" to item.approvedQty,
                        "shippedQty" to item.shippedQty,
                        "receivedQty" to item.receivedQty,
                        "rejectedQty" to item.rejectedQty,
                        "unitCost" to item.unitCost,
                        "totalCost" to item.totalCost,
                        "batchNumber" to item.batchNumber,
                        "qcApprovedRequired" to item.qcApprovedRequired,
                        "qcApproved" to item.qcApproved,
                        "serverUpdatedBy" to actor,
                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }
            batch.commit().await()
            count += chunk.size
        }
        count += pushPhase3ManufacturingToCloud(cRef, invDao, actor)
        return count
    }

    private suspend fun pushPhase3ManufacturingToCloud(
        cRef: com.google.firebase.firestore.DocumentReference,
        invDao: ErpInventoryDao,
        actor: String
    ): Int {
        var count = 0
        val receipts = invDao.getPurchaseReceiptsDirect()
            .filter { it.receivedQty > 0.0 && it.acceptedQty >= 0.0 && it.rejectedQty >= 0.0 }
        receipts.chunked(250).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { r ->
                val docId = safeId(r.grnNo.ifBlank { r.syncUuid }, r.syncUuid)
                batch.set(
                    cRef.collection("purchase_receipts").document(docId),
                    mapOf(
                        "id" to r.id,
                        "syncUuid" to r.syncUuid,
                        "grnNo" to r.grnNo,
                        "poNo" to r.poNo,
                        "receiptDate" to r.receiptDate,
                        "supplierName" to r.supplierName,
                        "warehouseId" to r.warehouseId,
                        "materialId" to r.materialId,
                        "materialCode" to r.materialCode,
                        "materialName" to r.materialName,
                        "unit" to r.unit,
                        "batchNumber" to r.batchNumber,
                        "expiryDate" to r.expiryDate,
                        "orderedQty" to r.orderedQty,
                        "receivedQty" to r.receivedQty,
                        "acceptedQty" to r.acceptedQty,
                        "rejectedQty" to r.rejectedQty,
                        "unitCost" to r.unitCost,
                        "totalCost" to r.totalCost,
                        "currency" to r.currency,
                        "discrepancyReason" to r.discrepancyReason,
                        "qcStatus" to r.qcStatus,
                        "stockPosted" to r.stockPosted,
                        "notes" to r.notes,
                        "performedBy" to r.performedBy,
                        "approvedBy" to r.approvedBy,
                        "idempotencyKey" to r.idempotencyKey,
                        "recordVersion" to r.recordVersion,
                        "createdAt" to r.createdAt,
                        "updatedAt" to r.updatedAt,
                        "serverUpdatedBy" to actor,
                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }
            batch.commit().await()
            count += chunk.size
        }

        val orders = invDao.getProductionOrdersDirect()
            .filter { it.targetCartons > 0 && it.packageWeightGrams > 0 }
        orders.chunked(250).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { o ->
                val docId = safeId(o.orderNo.ifBlank { o.syncUuid }, o.syncUuid)
                batch.set(
                    cRef.collection("production_orders").document(docId),
                    mapOf(
                        "id" to o.id,
                        "syncUuid" to o.syncUuid,
                        "orderNo" to o.orderNo,
                        "orderDate" to o.orderDate,
                        "formulaId" to (o.formulaId ?: 0L),
                        "formulaNo" to o.formulaNo,
                        "productId" to (o.productId ?: 0L),
                        "productName" to o.productName,
                        "paintColor" to o.paintColor,
                        "packageWeightGrams" to o.packageWeightGrams,
                        "cansPerCarton" to o.cansPerCarton,
                        "targetCartons" to o.targetCartons,
                        "targetCans" to o.targetCans,
                        "targetWeightKg" to o.targetWeightKg,
                        "actualGoodCans" to o.actualGoodCans,
                        "rejectedCans" to o.rejectedCans,
                        "reworkCans" to o.reworkCans,
                        "producedFullCartons" to o.producedFullCartons,
                        "producedLooseCans" to o.producedLooseCans,
                        "wastedRawKg" to o.wastedRawKg,
                        "bomLinesJson" to o.bomLinesJson,
                        "machineName" to o.machineName,
                        "productionEngineer" to o.productionEngineer,
                        "status" to o.status,
                        "batchNumber" to o.batchNumber,
                        "rawWarehouseId" to o.rawWarehouseId,
                        "fgWarehouseId" to o.fgWarehouseId,
                        "qcInspectedBy" to o.qcInspectedBy,
                        "qcNotes" to o.qcNotes,
                        "approvedBy" to o.approvedBy,
                        "idempotencyKey" to o.idempotencyKey,
                        "recordVersion" to o.recordVersion,
                        "createdAt" to o.createdAt,
                        "updatedAt" to o.updatedAt,
                        "serverUpdatedBy" to actor,
                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }
            batch.commit().await()
            count += chunk.size
        }

        val qcList = invDao.getQcInspectionsDirect()
            .filter { it.inspectedQty > 0.0 && it.acceptedQty >= 0.0 && it.rejectedQty >= 0.0 }
        qcList.chunked(250).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { q ->
                val docId = safeId(q.qcNo.ifBlank { q.syncUuid }, q.syncUuid)
                batch.set(
                    cRef.collection("qc_inspections").document(docId),
                    mapOf(
                        "id" to q.id,
                        "syncUuid" to q.syncUuid,
                        "qcNo" to q.qcNo,
                        "sourceType" to q.sourceType,
                        "sourceDocId" to (q.sourceDocId ?: 0L),
                        "sourceDocNo" to q.sourceDocNo,
                        "batchNumber" to q.batchNumber,
                        "itemCategory" to q.itemCategory,
                        "itemId" to q.itemId,
                        "itemName" to q.itemName,
                        "inspectedQty" to q.inspectedQty,
                        "acceptedQty" to q.acceptedQty,
                        "rejectedQty" to q.rejectedQty,
                        "reworkQty" to q.reworkQty,
                        "measuredDensity" to q.measuredDensity,
                        "expectedMinDensity" to q.expectedMinDensity,
                        "expectedMaxDensity" to q.expectedMaxDensity,
                        "measuredViscosity" to q.measuredViscosity,
                        "colorMatchPassed" to q.colorMatchPassed,
                        "status" to q.status,
                        "inspectorName" to q.inspectorName,
                        "notes" to q.notes,
                        "postedToStock" to q.postedToStock,
                        "idempotencyKey" to q.idempotencyKey,
                        "recordVersion" to q.recordVersion,
                        "createdAt" to q.createdAt,
                        "updatedAt" to q.updatedAt,
                        "serverUpdatedBy" to actor,
                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }
            batch.commit().await()
            count += chunk.size
        }
        return count
    }

    private suspend fun restorePhase2InventoryFromCloud(
        cRef: com.google.firebase.firestore.DocumentReference,
        invDao: ErpInventoryDao
    ): Int {
        var restored = 0
        runCatching {
            val pkgSnap = cRef.collection("packaging_rules").get().await()
            val pkgList = pkgSnap.documents.mapNotNull { doc ->
                val w = doc.safeLong("weightGrams")?.toInt() ?: return@mapNotNull null
                val cans = doc.safeLong("cansPerCarton")?.toInt() ?: return@mapNotNull null
                if (w <= 0 || cans <= 0) return@mapNotNull null
                PackagingRuleEntity(
                    syncUuid = doc.getString("syncUuid") ?: "PKG-RULE-$w",
                    weightGrams = w,
                    label = doc.getString("label") ?: "$w جرام ($cans علبة/كرتونة)",
                    cansPerCarton = cans,
                    defaultEmptyCanCostEgp = doc.safeDouble("defaultEmptyCanCostEgp") ?: 4.5,
                    defaultLidCostEgp = doc.safeDouble("defaultLidCostEgp") ?: 1.2,
                    defaultLabelCostEgp = doc.safeDouble("defaultLabelCostEgp") ?: 0.8,
                    defaultCartonBoxCostEgp = doc.safeDouble("defaultCartonBoxCostEgp") ?: 18.0,
                    active = doc.safeBoolean("active") ?: true,
                    updatedBy = doc.getString("updatedBy") ?: "CLOUD_SYNC",
                    recordVersion = doc.safeLong("recordVersion")?.toInt() ?: 1,
                    updatedAt = doc.safeLong("updatedAt") ?: System.currentTimeMillis()
                )
            }
            if (pkgList.isNotEmpty()) {
                invDao.upsertPackagingRules(pkgList)
                restored += pkgList.size
            }

            val imSnap = cRef.collection("inventory_movements").get().await()
            val imList = imSnap.documents.mapNotNull { doc ->
                val syncUuid = doc.getString("syncUuid") ?: return@mapNotNull null
                val qty = doc.safeDouble("quantity") ?: return@mapNotNull null
                if (qty <= 0.0) return@mapNotNull null
                InventoryMovementEntity(
                    syncUuid = syncUuid,
                    documentNo = doc.getString("documentNo") ?: doc.id,
                    movementType = doc.getString("movementType") ?: "STOCK_ADJUSTMENT",
                    warehouseId = doc.safeLong("warehouseId") ?: 1L,
                    warehouseCode = doc.getString("warehouseCode") ?: "",
                    warehouseClass = doc.getString("warehouseClass") ?: "SALES",
                    itemCategory = doc.getString("itemCategory") ?: "FINISHED_GOOD",
                    itemId = doc.safeLong("itemId") ?: 0L,
                    itemCode = doc.getString("itemCode") ?: "",
                    itemName = doc.getString("itemName") ?: "صنف",
                    quantity = qty,
                    signedQuantity = doc.safeDouble("signedQuantity") ?: qty,
                    unit = doc.getString("unit") ?: "كرتونة",
                    unitCost = doc.safeDouble("unitCost") ?: 0.0,
                    totalCost = doc.safeDouble("totalCost") ?: 0.0,
                    currency = doc.getString("currency") ?: "EGP",
                    batchNumber = doc.getString("batchNumber") ?: "",
                    expiryDate = doc.getString("expiryDate") ?: "",
                    referenceDocType = doc.getString("referenceDocType") ?: "",
                    referenceDocId = doc.safeLong("referenceDocId")?.takeIf { it > 0L },
                    referenceDocNo = doc.getString("referenceDocNo") ?: "",
                    reversedMovementId = doc.safeLong("reversedMovementId")?.takeIf { it > 0L },
                    reason = doc.getString("reason") ?: "",
                    status = doc.getString("status") ?: "POSTED",
                    qcApproved = doc.safeBoolean("qcApproved") ?: true,
                    idempotencyKey = doc.getString("idempotencyKey") ?: syncUuid,
                    performedBy = doc.getString("performedBy") ?: "CLOUD_SYNC",
                    approvedBy = doc.getString("approvedBy") ?: "",
                    deviceId = doc.getString("deviceId") ?: "",
                    recordVersion = doc.safeLong("recordVersion")?.toInt() ?: 1,
                    createdAt = doc.safeLong("createdAt") ?: System.currentTimeMillis(),
                    updatedAt = doc.safeLong("updatedAt") ?: System.currentTimeMillis()
                )
            }
            if (imList.isNotEmpty()) {
                invDao.upsertInventoryMovementsFromSync(imList)
                restored += imList.size
            }

            val balSnap = cRef.collection("warehouse_stock_balances").get().await()
            val balList = balSnap.documents.mapNotNull { doc ->
                val whId = doc.safeLong("warehouseId") ?: return@mapNotNull null
                val itemId = doc.safeLong("itemId") ?: return@mapNotNull null
                val itemCat = doc.getString("itemCategory") ?: "FINISHED_GOOD"
                val batchNum = doc.getString("batchNumber") ?: ""
                WarehouseStockBalanceEntity(
                    syncUuid = doc.getString("syncUuid") ?: "BAL-$whId-$itemCat-$itemId-$batchNum",
                    warehouseId = whId,
                    warehouseClass = doc.getString("warehouseClass") ?: "SALES",
                    itemCategory = itemCat,
                    itemId = itemId,
                    itemCode = doc.getString("itemCode") ?: "",
                    itemName = doc.getString("itemName") ?: "صنف",
                    unit = doc.getString("unit") ?: "كرتونة",
                    batchNumber = batchNum,
                    expiryDate = doc.getString("expiryDate") ?: "",
                    onHandQty = (doc.safeDouble("onHandQty") ?: 0.0).coerceAtLeast(0.0),
                    reservedQty = (doc.safeDouble("reservedQty") ?: 0.0).coerceAtLeast(0.0),
                    inTransitQty = (doc.safeDouble("inTransitQty") ?: 0.0).coerceAtLeast(0.0),
                    quarantineQty = (doc.safeDouble("quarantineQty") ?: 0.0).coerceAtLeast(0.0),
                    availableQty = (doc.safeDouble("availableQty") ?: 0.0).coerceAtLeast(0.0),
                    avgUnitCost = doc.safeDouble("avgUnitCost") ?: 0.0,
                    currency = doc.getString("currency") ?: "EGP",
                    lastMovementUuid = doc.getString("lastMovementUuid") ?: "",
                    updatedAt = doc.safeLong("updatedAt") ?: System.currentTimeMillis()
                )
            }
            if (balList.isNotEmpty()) {
                invDao.upsertStockBalances(balList)
                restored += balList.size
            }

            val trfSnap = cRef.collection("warehouse_transfers").get().await()
            val restoredTransfers = trfSnap.documents.mapNotNull { doc ->
                val transferNo = doc.getString("transferNo") ?: doc.id
                if (transferNo.isBlank()) return@mapNotNull null
                val id = doc.safeLong("id") ?: 0L
                val srcWhId = doc.safeLong("sourceWarehouseId") ?: return@mapNotNull null
                val dstWhId = doc.safeLong("destinationWarehouseId") ?: return@mapNotNull null
                WarehouseTransferEntity(
                    id = id,
                    syncUuid = doc.getString("syncUuid") ?: "WHTRF-UUID-$transferNo",
                    transferNo = transferNo,
                    transferDate = doc.getString("transferDate") ?: "2026-10-26",
                    sourceWarehouseId = srcWhId,
                    sourceWarehouseName = doc.getString("sourceWarehouseName") ?: "",
                    sourceWarehouseClass = doc.getString("sourceWarehouseClass") ?: WarehouseClass.FINISHED_GOODS.name,
                    destinationWarehouseId = dstWhId,
                    destinationWarehouseName = doc.getString("destinationWarehouseName") ?: "",
                    destinationWarehouseClass = doc.getString("destinationWarehouseClass") ?: WarehouseClass.SALES.name,
                    status = doc.getString("status") ?: WarehouseTransferStatus.DRAFT.name,
                    totalCost = doc.safeDouble("totalCost") ?: 0.0,
                    currency = doc.getString("currency") ?: "EGP",
                    notes = doc.getString("notes") ?: "",
                    rejectionReason = doc.getString("rejectionReason") ?: "",
                    createdBy = doc.getString("createdBy") ?: "CLOUD_SYNC",
                    reviewedBy = doc.getString("reviewedBy") ?: "",
                    approvedBy = doc.getString("approvedBy") ?: "",
                    dispatchedBy = doc.getString("dispatchedBy") ?: "",
                    receivedBy = doc.getString("receivedBy") ?: "",
                    dispatchedAt = doc.safeLong("dispatchedAt") ?: 0L,
                    receivedAt = doc.safeLong("receivedAt") ?: 0L,
                    timelineJson = doc.getString("timelineJson") ?: "[]",
                    idempotencyKey = doc.getString("idempotencyKey") ?: "",
                    deviceId = doc.getString("deviceId") ?: "",
                    recordVersion = doc.safeLong("recordVersion")?.toInt() ?: 1,
                    createdAt = doc.safeLong("createdAt") ?: System.currentTimeMillis(),
                    updatedAt = doc.safeLong("updatedAt") ?: System.currentTimeMillis()
                )
            }
            if (restoredTransfers.isNotEmpty()) {
                restoredTransfers.forEach { invDao.insertOrUpdateWarehouseTransfer(it) }
                restored += restoredTransfers.size
            }

            val trfItemsSnap = cRef.collection("warehouse_transfer_items").get().await()
            val restoredTrfItems = trfItemsSnap.documents.mapNotNull { doc ->
                val transferId = doc.safeLong("transferId") ?: return@mapNotNull null
                val itemId = doc.safeLong("itemId") ?: return@mapNotNull null
                WarehouseTransferItemEntity(
                    id = doc.safeLong("id") ?: 0L,
                    transferId = transferId,
                    transferUuid = doc.getString("transferUuid") ?: "",
                    itemCategory = doc.getString("itemCategory") ?: InventoryItemCategory.FINISHED_GOOD.name,
                    itemId = itemId,
                    itemCode = doc.getString("itemCode") ?: "",
                    itemName = doc.getString("itemName") ?: "صنف",
                    unit = doc.getString("unit") ?: "كرتونة",
                    requestedQty = doc.safeDouble("requestedQty") ?: 0.0,
                    approvedQty = doc.safeDouble("approvedQty") ?: 0.0,
                    shippedQty = doc.safeDouble("shippedQty") ?: 0.0,
                    receivedQty = doc.safeDouble("receivedQty") ?: 0.0,
                    rejectedQty = doc.safeDouble("rejectedQty") ?: 0.0,
                    unitCost = doc.safeDouble("unitCost") ?: 0.0,
                    totalCost = doc.safeDouble("totalCost") ?: 0.0,
                    batchNumber = doc.getString("batchNumber") ?: "",
                    qcApprovedRequired = doc.safeBoolean("qcApprovedRequired") ?: true,
                    qcApproved = doc.safeBoolean("qcApproved") ?: false
                )
            }
            if (restoredTrfItems.isNotEmpty()) {
                invDao.insertTransferItems(restoredTrfItems)
                restored += restoredTrfItems.size
            }
        }
        restored += restorePhase3ManufacturingFromCloud(cRef, invDao)
        return restored
    }

    private suspend fun restorePhase3ManufacturingFromCloud(
        cRef: com.google.firebase.firestore.DocumentReference,
        invDao: ErpInventoryDao
    ): Int {
        var restored = 0
        runCatching {
            val prSnap = cRef.collection("purchase_receipts").get().await()
            val prList = prSnap.documents.mapNotNull { doc ->
                val grnNo = doc.getString("grnNo") ?: doc.id
                val received = doc.safeDouble("receivedQty") ?: return@mapNotNull null
                if (received <= 0.0) return@mapNotNull null
                PurchaseReceiptEntity(
                    id = doc.safeLong("id") ?: 0L,
                    syncUuid = doc.getString("syncUuid") ?: "PR-UUID-$grnNo",
                    grnNo = grnNo,
                    poNo = doc.getString("poNo") ?: "",
                    receiptDate = doc.getString("receiptDate") ?: "",
                    supplierName = doc.getString("supplierName") ?: "",
                    warehouseId = doc.safeLong("warehouseId") ?: 5L,
                    materialId = doc.safeLong("materialId") ?: 1L,
                    materialCode = doc.getString("materialCode") ?: "",
                    materialName = doc.getString("materialName") ?: "مادة خام",
                    unit = doc.getString("unit") ?: "كجم",
                    batchNumber = doc.getString("batchNumber") ?: "",
                    expiryDate = doc.getString("expiryDate") ?: "",
                    orderedQty = doc.safeDouble("orderedQty") ?: 0.0,
                    receivedQty = received,
                    acceptedQty = (doc.safeDouble("acceptedQty") ?: 0.0).coerceAtLeast(0.0),
                    rejectedQty = (doc.safeDouble("rejectedQty") ?: 0.0).coerceAtLeast(0.0),
                    unitCost = doc.safeDouble("unitCost") ?: 0.0,
                    totalCost = doc.safeDouble("totalCost") ?: 0.0,
                    currency = doc.getString("currency") ?: "EGP",
                    discrepancyReason = doc.getString("discrepancyReason") ?: "",
                    qcStatus = doc.getString("qcStatus") ?: "APPROVED",
                    stockPosted = doc.safeBoolean("stockPosted") ?: false,
                    notes = doc.getString("notes") ?: "",
                    performedBy = doc.getString("performedBy") ?: "CLOUD_SYNC",
                    approvedBy = doc.getString("approvedBy") ?: "",
                    idempotencyKey = doc.getString("idempotencyKey") ?: "",
                    recordVersion = doc.safeLong("recordVersion")?.toInt() ?: 1,
                    createdAt = doc.safeLong("createdAt") ?: System.currentTimeMillis(),
                    updatedAt = doc.safeLong("updatedAt") ?: System.currentTimeMillis()
                )
            }
            if (prList.isNotEmpty()) {
                invDao.upsertPurchaseReceipts(prList)
                restored += prList.size
            }

            val poSnap = cRef.collection("production_orders").get().await()
            val poList = poSnap.documents.mapNotNull { doc ->
                val orderNo = doc.getString("orderNo") ?: doc.id
                val targetCartons = doc.safeLong("targetCartons")?.toInt() ?: return@mapNotNull null
                if (targetCartons <= 0) return@mapNotNull null
                val weight = doc.safeLong("packageWeightGrams")?.toInt() ?: 900
                val cansPer = doc.safeLong("cansPerCarton")?.toInt() ?: 12
                ProductionOrderEntity(
                    id = doc.safeLong("id") ?: 0L,
                    syncUuid = doc.getString("syncUuid") ?: "PO-UUID-$orderNo",
                    orderNo = orderNo,
                    orderDate = doc.getString("orderDate") ?: "",
                    formulaId = doc.safeLong("formulaId")?.takeIf { it > 0L },
                    formulaNo = doc.getString("formulaNo") ?: "",
                    productId = doc.safeLong("productId")?.takeIf { it > 0L },
                    productName = doc.getString("productName") ?: "بوهية",
                    paintColor = doc.getString("paintColor") ?: "أبيض",
                    packageWeightGrams = weight,
                    cansPerCarton = cansPer,
                    targetCartons = targetCartons,
                    targetCans = doc.safeLong("targetCans")?.toInt() ?: (targetCartons * cansPer),
                    targetWeightKg = doc.safeDouble("targetWeightKg") ?: 0.0,
                    actualGoodCans = doc.safeLong("actualGoodCans")?.toInt() ?: 0,
                    rejectedCans = doc.safeLong("rejectedCans")?.toInt() ?: 0,
                    reworkCans = doc.safeLong("reworkCans")?.toInt() ?: 0,
                    producedFullCartons = doc.safeLong("producedFullCartons")?.toInt() ?: 0,
                    producedLooseCans = doc.safeLong("producedLooseCans")?.toInt() ?: 0,
                    wastedRawKg = doc.safeDouble("wastedRawKg") ?: 0.0,
                    bomLinesJson = doc.getString("bomLinesJson") ?: "[]",
                    machineName = doc.getString("machineName") ?: "",
                    productionEngineer = doc.getString("productionEngineer") ?: "مهندس الإنتاج",
                    status = doc.getString("status") ?: "APPROVED",
                    batchNumber = doc.getString("batchNumber") ?: "",
                    rawWarehouseId = doc.safeLong("rawWarehouseId") ?: 0L,
                    fgWarehouseId = doc.safeLong("fgWarehouseId") ?: 0L,
                    qcInspectedBy = doc.getString("qcInspectedBy") ?: "",
                    qcNotes = doc.getString("qcNotes") ?: "",
                    approvedBy = doc.getString("approvedBy") ?: "",
                    idempotencyKey = doc.getString("idempotencyKey") ?: "",
                    recordVersion = doc.safeLong("recordVersion")?.toInt() ?: 1,
                    createdAt = doc.safeLong("createdAt") ?: System.currentTimeMillis(),
                    updatedAt = doc.safeLong("updatedAt") ?: System.currentTimeMillis()
                )
            }
            if (poList.isNotEmpty()) {
                invDao.upsertProductionOrders(poList)
                restored += poList.size
            }

            val qcSnap = cRef.collection("qc_inspections").get().await()
            val qcList = qcSnap.documents.mapNotNull { doc ->
                val qcNo = doc.getString("qcNo") ?: doc.id
                val inspected = doc.safeDouble("inspectedQty") ?: return@mapNotNull null
                if (inspected <= 0.0) return@mapNotNull null
                QcInspectionEntity(
                    id = doc.safeLong("id") ?: 0L,
                    syncUuid = doc.getString("syncUuid") ?: "QC-UUID-$qcNo",
                    qcNo = qcNo,
                    sourceType = doc.getString("sourceType") ?: "PRODUCTION_ORDER",
                    sourceDocId = doc.safeLong("sourceDocId")?.takeIf { it > 0L },
                    sourceDocNo = doc.getString("sourceDocNo") ?: "",
                    batchNumber = doc.getString("batchNumber") ?: "",
                    itemCategory = doc.getString("itemCategory") ?: "FINISHED_GOOD",
                    itemId = doc.safeLong("itemId") ?: 0L,
                    itemName = doc.getString("itemName") ?: "منتج تام",
                    inspectedQty = inspected,
                    acceptedQty = (doc.safeDouble("acceptedQty") ?: 0.0).coerceAtLeast(0.0),
                    rejectedQty = (doc.safeDouble("rejectedQty") ?: 0.0).coerceAtLeast(0.0),
                    reworkQty = (doc.safeDouble("reworkQty") ?: 0.0).coerceAtLeast(0.0),
                    measuredDensity = doc.safeDouble("measuredDensity") ?: 0.0,
                    expectedMinDensity = doc.safeDouble("expectedMinDensity") ?: 0.0,
                    expectedMaxDensity = doc.safeDouble("expectedMaxDensity") ?: 0.0,
                    measuredViscosity = doc.getString("measuredViscosity") ?: "",
                    colorMatchPassed = doc.safeBoolean("colorMatchPassed") ?: true,
                    status = doc.getString("status") ?: "APPROVED",
                    inspectorName = doc.getString("inspectorName") ?: "مسؤول الجودة",
                    notes = doc.getString("notes") ?: "",
                    postedToStock = doc.safeBoolean("postedToStock") ?: false,
                    idempotencyKey = doc.getString("idempotencyKey") ?: "",
                    recordVersion = doc.safeLong("recordVersion")?.toInt() ?: 1,
                    createdAt = doc.safeLong("createdAt") ?: System.currentTimeMillis(),
                    updatedAt = doc.safeLong("updatedAt") ?: System.currentTimeMillis()
                )
            }
            if (qcList.isNotEmpty()) {
                invDao.upsertQcInspections(qcList)
                restored += qcList.size
            }
        }
        return restored
    }

    private suspend fun pushPhase4AccountingToCloud(
        cRef: com.google.firebase.firestore.DocumentReference,
        accDao: ErpAccountingDao,
        actor: String,
        allowChartWrite: Boolean = true,
        allowEntryReversal: Boolean = true
    ): Int {
        var count = 0
        if (allowChartWrite) {
            val accounts = accDao.getAllAccountsDirect()
                .filter { it.accountCode.isNotBlank() && it.nameAr.isNotBlank() }
            accounts.chunked(200).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { acc ->
                    val docId = safeId(acc.accountCode, acc.syncUuid)
                    batch.set(
                        cRef.collection("accounting_accounts").document(docId),
                        mapOf(
                            "id" to acc.id,
                            "syncUuid" to acc.syncUuid,
                            "accountCode" to acc.accountCode.take(32),
                            "nameAr" to acc.nameAr.take(160),
                            "nameEn" to acc.nameEn,
                            "accountType" to acc.accountType,
                            "normalSide" to acc.normalSide,
                            "parentCode" to acc.parentCode,
                            "currencyHint" to acc.currencyHint,
                            "isSystemAccount" to acc.isSystemAccount,
                            "active" to acc.active,
                            "recordVersion" to acc.recordVersion,
                            "createdAt" to acc.createdAt,
                            "updatedAt" to acc.updatedAt,
                            "serverUpdatedBy" to actor,
                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                }
                batch.commit().await()
                count += chunk.size
            }
        }

        // journal_entries are append-only in firestore.rules (only POSTED -> REVERSED by Accountant/Admin)
        val existingEntryStatusMap = runCatching {
            cRef.collection("journal_entries").get().await().documents.associate {
                it.id to (it.getString("status") ?: "POSTED").trim().uppercase()
            }
        }.getOrDefault(emptyMap())
        val entries = accDao.getAllJournalEntriesDirect()
            .filter { it.totalDebit >= 0.0 && it.totalCredit >= 0.0 && kotlin.math.abs(it.totalDebit - it.totalCredit) < 0.001 }
        val entriesToWrite = entries.mapNotNull { je ->
            val docId = safeId(je.syncUuid.ifBlank { je.entryNo }, je.entryNo)
            val remoteStatus = existingEntryStatusMap[docId]
            if (remoteStatus == null) {
                Triple(docId, true, je)
            } else if (allowEntryReversal &&
                remoteStatus == "POSTED" &&
                je.status.trim().equals("REVERSED", ignoreCase = true)
            ) {
                Triple(docId, false, je)
            } else {
                null
            }
        }
        entriesToWrite.chunked(200).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { (docId, isCreate, je) ->
                val docRef = cRef.collection("journal_entries").document(docId)
                if (isCreate) {
                    batch.set(
                        docRef,
                        mapOf(
                            "id" to je.id,
                            "syncUuid" to je.syncUuid,
                            "entryNo" to je.entryNo,
                            "entryDate" to je.entryDate,
                            "sourceType" to je.sourceType,
                            "sourceDocId" to (je.sourceDocId ?: 0L),
                            "sourceDocNo" to je.sourceDocNo,
                            "description" to je.description,
                            "currency" to je.currency,
                            "fxRateToUsd" to je.fxRateToUsd,
                            "fxRateToSdg" to je.fxRateToSdg,
                            "totalDebit" to je.totalDebit,
                            "totalCredit" to je.totalCredit,
                            "status" to je.status,
                            "reversedEntryId" to (je.reversedEntryId ?: 0L),
                            "reversedByEntryNo" to je.reversedByEntryNo,
                            "reversalReason" to je.reversalReason,
                            "idempotencyKey" to je.idempotencyKey,
                            "createdBy" to je.createdBy,
                            "approvedBy" to je.approvedBy,
                            "deviceId" to je.deviceId,
                            "recordVersion" to je.recordVersion,
                            "createdAt" to je.createdAt,
                            "updatedAt" to je.updatedAt,
                            "serverUpdatedBy" to actor,
                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                } else {
                    batch.set(
                        docRef,
                        mapOf(
                            "status" to "REVERSED",
                            "reversedByEntryNo" to je.reversedByEntryNo,
                            "reversalReason" to je.reversalReason.ifBlank { "قيد عكسي معتمد" },
                            "recordVersion" to je.recordVersion.coerceAtLeast(2),
                            "updatedAt" to System.currentTimeMillis(),
                            "serverUpdatedBy" to actor,
                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                }
            }
            batch.commit().await()
            count += chunk.size
        }

        // journal_lines are strictly immutable once created (allow update: if false)
        val existingLineIds = runCatching {
            cRef.collection("journal_lines").get().await().documents.map { it.id }.toSet()
        }.getOrDefault(emptySet())
        val linesToCreate = accDao.getAllJournalLinesDirect()
            .filter { it.debit >= 0.0 && it.credit >= 0.0 }
            .filter { ln ->
                val docId = safeId(ln.syncUuid, "JL-${ln.id}")
                docId !in existingLineIds
            }
        linesToCreate.chunked(250).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { ln ->
                val docId = safeId(ln.syncUuid, "JL-${ln.id}")
                batch.set(
                    cRef.collection("journal_lines").document(docId),
                    mapOf(
                        "id" to ln.id,
                        "syncUuid" to ln.syncUuid,
                        "journalEntryId" to ln.journalEntryId,
                        "journalEntryUuid" to ln.journalEntryUuid,
                        "lineOrder" to ln.lineOrder,
                        "accountCode" to ln.accountCode,
                        "accountName" to ln.accountName,
                        "accountType" to ln.accountType,
                        "debit" to ln.debit,
                        "credit" to ln.credit,
                        "currency" to ln.currency,
                        "costCenter" to ln.costCenter,
                        "partyType" to ln.partyType,
                        "partyName" to ln.partyName,
                        "memo" to ln.memo,
                        "createdAt" to ln.createdAt,
                        "serverUpdatedBy" to actor,
                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }
            batch.commit().await()
            count += chunk.size
        }
        return count
    }

    private suspend fun restorePhase4AccountingFromCloud(
        cRef: com.google.firebase.firestore.DocumentReference,
        accDao: ErpAccountingDao
    ): Int {
        var restored = 0
        runCatching {
            val accSnap = cRef.collection("accounting_accounts").get().await()
            val accList = accSnap.documents.mapNotNull { doc ->
                val code = doc.getString("accountCode") ?: return@mapNotNull null
                if (code.isBlank()) return@mapNotNull null
                ChartAccountEntity(
                    id = doc.safeLong("id") ?: 0L,
                    syncUuid = doc.getString("syncUuid") ?: "ACC-$code",
                    accountCode = code,
                    nameAr = doc.getString("nameAr") ?: code,
                    nameEn = doc.getString("nameEn") ?: "",
                    accountType = doc.getString("accountType") ?: "ASSET",
                    normalSide = doc.getString("normalSide") ?: "DEBIT",
                    parentCode = doc.getString("parentCode") ?: "",
                    currencyHint = doc.getString("currencyHint") ?: "ALL",
                    isSystemAccount = doc.safeBoolean("isSystemAccount") ?: true,
                    active = doc.safeBoolean("active") ?: true,
                    recordVersion = doc.safeLong("recordVersion")?.toInt() ?: 1,
                    createdAt = doc.safeLong("createdAt") ?: System.currentTimeMillis(),
                    updatedAt = doc.safeLong("updatedAt") ?: System.currentTimeMillis()
                )
            }
            if (accList.isNotEmpty()) {
                accDao.upsertAccounts(accList)
                restored += accList.size
            }

            val jeSnap = cRef.collection("journal_entries").get().await()
            val entryIdByUuid = mutableMapOf<String, Long>()
            for (doc in jeSnap.documents) {
                val uuid = doc.getString("syncUuid") ?: doc.id
                val entryNo = doc.getString("entryNo") ?: doc.id
                val totalDr = (doc.safeDouble("totalDebit") ?: 0.0).coerceAtLeast(0.0)
                val totalCr = (doc.safeDouble("totalCredit") ?: 0.0).coerceAtLeast(0.0)
                if (kotlin.math.abs(totalDr - totalCr) > 0.01) continue
                val existing = accDao.getJournalEntryByUuid(uuid) ?: accDao.getJournalEntryByNo(entryNo)
                val entity = JournalEntryEntity(
                    id = existing?.id ?: 0L,
                    syncUuid = uuid,
                    entryNo = entryNo,
                    entryDate = doc.getString("entryDate") ?: "",
                    sourceType = doc.getString("sourceType") ?: "MANUAL_ADJUSTMENT",
                    sourceDocId = doc.safeLong("sourceDocId")?.takeIf { it > 0L },
                    sourceDocNo = doc.getString("sourceDocNo") ?: "",
                    description = doc.getString("description") ?: "قيد متزامن",
                    currency = doc.getString("currency") ?: "SDG",
                    fxRateToUsd = doc.safeDouble("fxRateToUsd") ?: 1.0,
                    fxRateToSdg = doc.safeDouble("fxRateToSdg") ?: 1.0,
                    totalDebit = totalDr,
                    totalCredit = totalCr,
                    status = doc.getString("status") ?: "POSTED",
                    reversedEntryId = doc.safeLong("reversedEntryId")?.takeIf { it > 0L },
                    reversedByEntryNo = doc.getString("reversedByEntryNo") ?: "",
                    reversalReason = doc.getString("reversalReason") ?: "",
                    idempotencyKey = doc.getString("idempotencyKey") ?: "",
                    createdBy = doc.getString("createdBy") ?: "CLOUD_SYNC",
                    approvedBy = doc.getString("approvedBy") ?: "",
                    deviceId = doc.getString("deviceId") ?: "CLOUD",
                    recordVersion = doc.safeLong("recordVersion")?.toInt() ?: 1,
                    createdAt = doc.safeLong("createdAt") ?: System.currentTimeMillis(),
                    updatedAt = doc.safeLong("updatedAt") ?: System.currentTimeMillis()
                )
                val localId = accDao.upsertJournalEntry(entity)
                val resolvedId = if (entity.id > 0L) entity.id else localId
                entryIdByUuid[uuid] = resolvedId
                restored++
            }

            val jlSnap = cRef.collection("journal_lines").get().await()
            val linesToInsert = jlSnap.documents.mapNotNull { doc ->
                val uuid = doc.getString("syncUuid") ?: doc.id
                val jeUuid = doc.getString("journalEntryUuid") ?: return@mapNotNull null
                val localJeId = entryIdByUuid[jeUuid] ?: accDao.getJournalEntryByUuid(jeUuid)?.id ?: return@mapNotNull null
                val dr = (doc.safeDouble("debit") ?: 0.0).coerceAtLeast(0.0)
                val cr = (doc.safeDouble("credit") ?: 0.0).coerceAtLeast(0.0)
                JournalLineEntity(
                    syncUuid = uuid,
                    journalEntryId = localJeId,
                    journalEntryUuid = jeUuid,
                    lineOrder = doc.safeLong("lineOrder")?.toInt() ?: 1,
                    accountCode = doc.getString("accountCode") ?: "3100",
                    accountName = doc.getString("accountName") ?: "",
                    accountType = doc.getString("accountType") ?: "ASSET",
                    debit = dr,
                    credit = cr,
                    currency = doc.getString("currency") ?: "SDG",
                    costCenter = doc.getString("costCenter") ?: "",
                    partyType = doc.getString("partyType") ?: "",
                    partyName = doc.getString("partyName") ?: "",
                    memo = doc.getString("memo") ?: "",
                    createdAt = doc.safeLong("createdAt") ?: System.currentTimeMillis()
                )
            }
            if (linesToInsert.isNotEmpty()) {
                accDao.upsertJournalLines(linesToInsert)
                restored += linesToInsert.size
            }
        }
        return restored
    }

    /**
     * Performs a server diagnostics health check using Source.SERVER (no stale local cache)
     * to verify connection stability and response latency.
     */
    suspend fun checkServerHealth(clusterCode: String): ServerHealthReport = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val projectId = firestore.app.options.projectId ?: "gen-lang-client-0220128746"
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val endpointUrl = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/erp_clusters/$cleanCluster"

        val fbUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        if (fbUser == null) {
            return@withContext ServerHealthReport(
                isOnline = false,
                endpoint = endpointUrl,
                projectId = projectId,
                databaseId = databaseId,
                clusterCode = cleanCluster,
                responseTimeMs = 0L,
                cloudInvoicesCount = 0,
                cloudMovementsCount = 0,
                cloudCustomersCount = 0,
                cloudUsersCount = 0,
                cloudProductsCount = 0,
                message = "بانتظار تسجيل الدخول بحساب Google المعتمد للاتصال بالسيرفر"
            )
        }

        try {
            runCatching { ensureOwnClusterMemberRegistration(cleanCluster, null, fbUser.uid, fbUser.email) }
            val roleKey = runCatching { resolveClusterMemberRoleKey(cleanCluster, fbUser.uid) }.getOrNull()
            val cRef = clusterRef(cleanCluster)
            val src = com.google.firebase.firestore.Source.SERVER
            val invCountTask = cRef.collection("invoices").get(src)
            val movCountTask = cRef.collection("movements").get(src)
            val prodCountTask = cRef.collection("products").get(src)
            val custCountTask = cRef.collection("customers").get(src)

            val invCount = invCountTask.await().size()
            val movCount = movCountTask.await().size()
            val prodCount = prodCountTask.await().size()
            val custCount = custCountTask.await().size()
            val userCount = if (roleKey == "ADMIN" || roleKey == "MANAGER") {
                runCatching { cRef.collection("user_accounts").get(src).await().size() }.getOrDefault(0)
            } else {
                1
            }
            val latency = System.currentTimeMillis() - start

            ServerHealthReport(
                isOnline = true,
                endpoint = endpointUrl,
                projectId = projectId,
                databaseId = databaseId,
                clusterCode = cleanCluster,
                responseTimeMs = latency,
                cloudInvoicesCount = invCount,
                cloudMovementsCount = movCount,
                cloudCustomersCount = custCount,
                cloudUsersCount = userCount,
                cloudProductsCount = prodCount,
                message = "سيرفر Google Cloud متصل ومقاس مباشرة عبر Source.SERVER ($latency ms)"
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            val entry = recordStructuredError("SERVER_HEALTH_CHECK", "erp_clusters/$cleanCluster", e)
            Log.e(TAG, "Server health check failed: ${entry.sanitizedMessage}", e)
            ServerHealthReport(
                isOnline = false,
                endpoint = endpointUrl,
                projectId = projectId,
                databaseId = databaseId,
                clusterCode = cleanCluster,
                responseTimeMs = latency,
                cloudInvoicesCount = 0,
                cloudMovementsCount = 0,
                cloudCustomersCount = 0,
                cloudUsersCount = 0,
                cloudProductsCount = 0,
                message = "${entry.category.arabicTitle}: ${entry.sanitizedMessage}"
            )
        }
    }

    private fun upsertServiceProbe(result: ServiceProbeResult): ServiceProbeResult {
        val updated = _serviceProbes.value.filterNot { it.probeId == result.probeId } + result
        _serviceProbes.value = updated.sortedBy { it.probeId.ordinal }
        return result
    }

    suspend fun runSyncPreflightCheck(
        clusterCode: String,
        dao: PmpDao,
        activeUserId: Long?,
        isManagerHint: Boolean
    ): PreflightCheckResult = withContext(Dispatchers.IO) {
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val fbUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        val authPresent = fbUser != null
        val netProbe = probeNetworkConnectivity()
        val accounts = runCatching { dao.getUserAccountsDirect() }.getOrDefault(emptyList())
        val activeUser = accounts.firstOrNull { it.id == activeUserId } ?: accounts.firstOrNull { it.isCurrentSession }
        val roleSummary = when {
            activeUser != null -> "${activeUser.username} (${activeUser.role})"
            isManagerHint -> "مدير نظام"
            else -> "بدون جلسة محلية"
        }

        val passed = authPresent && netProbe.ok && !cleanCluster.equals(DEPRECATED_V16_CLUSTER, ignoreCase = true)
        val message = when {
            cleanCluster.equals(DEPRECATED_V16_CLUSTER, ignoreCase = true) ->
                "الكلاستر المهجور $DEPRECATED_V16_CLUSTER محظور أمنياً"
            !netProbe.ok ->
                "الشبكة غير متصلة حالياً — تعذر الوصول لخدمات Google Cloud"
            !authPresent ->
                "جلسة Firebase Auth غير مسجلة — مطلوب تسجيل الدخول السحابي قبل المزامنة"
            else ->
                "جميع شروط المزامنة جاهزة ($cleanCluster • Auth: نشط)"
        }

        val res = PreflightCheckResult(
            passed = passed,
            clusterCode = cleanCluster,
            firebaseAuthPresent = authPresent,
            networkOnline = netProbe.ok,
            roleSummary = roleSummary,
            message = message
        )
        _lastPreflightResult.value = res
        res
    }

    suspend fun probeNetworkConnectivity(): ServiceProbeResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val result = runCatching {
            val ctx = firestore.app.applicationContext
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val network = cm?.activeNetwork
            val caps = if (network != null) cm.getNetworkCapabilities(network) else null
            val hasInternet = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val latency = System.currentTimeMillis() - start
            ServiceProbeResult(
                probeId = ServiceProbeId.NETWORK,
                ok = hasInternet,
                measured = true,
                latencyMs = latency,
                detail = if (hasInternet) "الشبكة متصلة وجاهزة (${latency}ms)" else "لا يوجد اتصال إنترنت نشط",
                remediationHint = if (hasInternet) "" else "تأكد من تفعيل Wi-Fi أو بيانات الهاتف"
            )
        }.getOrElse { e ->
            val latency = System.currentTimeMillis() - start
            ServiceProbeResult(
                probeId = ServiceProbeId.NETWORK,
                ok = false,
                measured = true,
                latencyMs = latency,
                detail = "تعذر فحص حالة الشبكة: ${CloudSyncErrorTracker.sanitizeCloudMessage(e.message)}",
                remediationHint = CloudErrorCategory.NETWORK_ERROR.remediationHint
            )
        }
        upsertServiceProbe(result)
    }

    suspend fun probeFirebaseAuth(): ServiceProbeResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val result = runCatching {
            val user = Firebase.auth.currentUser
            val latency = System.currentTimeMillis() - start
            if (user == null) {
                ServiceProbeResult(
                    probeId = ServiceProbeId.FIREBASE_AUTH,
                    ok = false,
                    measured = true,
                    latencyMs = latency,
                    detail = "لا توجد جلسة Firebase Auth نشطة حالياً",
                    remediationHint = CloudErrorCategory.UNAUTHENTICATED.remediationHint
                )
            } else {
                val label = user.email?.takeIf { it.isNotBlank() } ?: user.uid.take(12)
                ServiceProbeResult(
                    probeId = ServiceProbeId.FIREBASE_AUTH,
                    ok = true,
                    measured = true,
                    latencyMs = latency,
                    detail = "جلسة مصادقة نشطة ($label)"
                )
            }
        }.getOrElse { e ->
            ServiceProbeResult(
                probeId = ServiceProbeId.FIREBASE_AUTH,
                ok = false,
                measured = true,
                latencyMs = System.currentTimeMillis() - start,
                detail = CloudSyncErrorTracker.sanitizeCloudMessage(e.message),
                remediationHint = CloudErrorCategory.UNAUTHENTICATED.remediationHint
            )
        }
        upsertServiceProbe(result)
    }

    suspend fun probeFirestoreAccess(clusterCode: String): ServiceProbeResult = withContext(Dispatchers.IO) {
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val start = System.currentTimeMillis()
        val fbUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        val result = try {
            if (fbUser == null) {
                ServiceProbeResult(
                    probeId = ServiceProbeId.FIRESTORE,
                    ok = false,
                    measured = true,
                    latencyMs = System.currentTimeMillis() - start,
                    detail = "UNAUTHENTICATED: يتطلب الفحص المباشر تسجيل الدخول بحساب Google المعتمد",
                    remediationHint = "سجّل الدخول بحساب Google المعتمد ثم أعد فحص الاتصال."
                )
            } else {
                runCatching { ensureOwnClusterMemberRegistration(cleanCluster, null, fbUser.uid, fbUser.email) }
                val snap = clusterRef(cleanCluster)
                    .collection("warehouses")
                    .limit(1)
                    .get(com.google.firebase.firestore.Source.SERVER)
                    .await()
                val latency = System.currentTimeMillis() - start
                ServiceProbeResult(
                    probeId = ServiceProbeId.FIRESTORE,
                    ok = true,
                    measured = true,
                    latencyMs = latency,
                    detail = "قراءة Source.SERVER ناجحة (${latency}ms • مستندات: ${snap.size()})"
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            val entry = recordStructuredError("PROBE_FIRESTORE", "erp_clusters/$cleanCluster/warehouses", e)
            ServiceProbeResult(
                probeId = ServiceProbeId.FIRESTORE,
                ok = false,
                measured = true,
                latencyMs = latency,
                detail = "${entry.code}: ${entry.sanitizedMessage}",
                remediationHint = entry.remediationHint
            )
        }
        upsertServiceProbe(result)
    }

    suspend fun probeRbacPolicy(
        clusterCode: String,
        actor: UserAccountEntity?
    ): ServiceProbeResult = withContext(Dispatchers.IO) {
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val start = System.currentTimeMillis()
        val fbUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        val result = try {
            if (fbUser == null) {
                ServiceProbeResult(
                    probeId = ServiceProbeId.RBAC_POLICY,
                    ok = false,
                    measured = true,
                    latencyMs = System.currentTimeMillis() - start,
                    detail = "يتطلب فحص عضوية RBAC السحابية تسجيل الدخول بـ Firebase Auth",
                    remediationHint = CloudErrorCategory.UNAUTHENTICATED.remediationHint
                )
            } else {
                val memberSnap = clusterRef(cleanCluster)
                    .collection("cluster_members")
                    .document(fbUser.uid)
                    .get(com.google.firebase.firestore.Source.SERVER)
                    .await()
                val latency = System.currentTimeMillis() - start
                if (memberSnap.exists()) {
                    val cloudRole = memberSnap.safeString("roleKey") ?: actor?.role ?: "MEMBER"
                    ServiceProbeResult(
                        probeId = ServiceProbeId.RBAC_POLICY,
                        ok = true,
                        measured = true,
                        latencyMs = latency,
                        detail = "عضوية RBAC مفعلة على الخادم ($cloudRole)"
                    )
                } else {
                    ServiceProbeResult(
                        probeId = ServiceProbeId.RBAC_POLICY,
                        ok = false,
                        measured = true,
                        latencyMs = latency,
                        detail = "المستند cluster_members/${fbUser.uid.take(8)} غير مسجل في الكلاستر $cleanCluster",
                        remediationHint = CloudErrorCategory.PERMISSION_DENIED.remediationHint
                    )
                }
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            val entry = recordStructuredError("PROBE_RBAC", "erp_clusters/$cleanCluster/cluster_members", e)
            ServiceProbeResult(
                probeId = ServiceProbeId.RBAC_POLICY,
                ok = false,
                measured = true,
                latencyMs = latency,
                detail = "${entry.code}: ${entry.sanitizedMessage}",
                remediationHint = entry.remediationHint
            )
        }
        upsertServiceProbe(result)
    }

    suspend fun probeUpdatesMetadata(): ServiceProbeResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val result = try {
            val src = com.google.firebase.firestore.Source.SERVER
            val snap = runCatching {
                firestore.collection("app_updates").document("latest").get(src).await()
            }.getOrNull() ?: firestore.collection("system_config").document("app_version").get(src).await()
            val latency = System.currentTimeMillis() - start
            val vName = snap.safeString("versionName") ?: snap.safeString("latestVersion") ?: "غير محدد"
            ServiceProbeResult(
                probeId = ServiceProbeId.APP_UPDATES,
                ok = snap.exists(),
                measured = true,
                latencyMs = latency,
                detail = if (snap.exists()) {
                    "مستند التحديثات متاح ($vName • ${latency}ms)"
                } else {
                    "مستند التحديثات غير موجود على Firestore"
                }
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            val entry = recordStructuredError("PROBE_UPDATES", "app_updates/latest", e)
            ServiceProbeResult(
                probeId = ServiceProbeId.APP_UPDATES,
                ok = false,
                measured = true,
                latencyMs = latency,
                detail = "${entry.code}: ${entry.sanitizedMessage}",
                remediationHint = entry.remediationHint
            )
        }
        upsertServiceProbe(result)
    }

    suspend fun runComprehensiveDiagnostics(
        clusterCode: String,
        dao: PmpDao,
        activeUserId: Long?,
        isManagerHint: Boolean
    ): ComprehensiveDiagnosticReport = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val accounts = runCatching { dao.getUserAccountsDirect() }.getOrDefault(emptyList())
        val activeUser = accounts.firstOrNull { it.id == activeUserId } ?: accounts.firstOrNull { it.isCurrentSession }

        val preflight = runSyncPreflightCheck(cleanCluster, dao, activeUserId, isManagerHint)
        val p1 = probeNetworkConnectivity()
        val p2 = probeFirebaseAuth()
        val p3 = probeFirestoreAccess(cleanCluster)
        val p4 = probeRbacPolicy(cleanCluster, activeUser)
        val p5 = probeUpdatesMetadata()

        val allProbes = listOf(p1, p2, p3, p4, p5)
        val passedCount = allProbes.count { it.ok }
        val overallOk = allProbes.all { it.ok }
        val totalLatency = System.currentTimeMillis() - start
        val summary = if (overallOk) {
            "اكتمل الفحص الشامل (5/5 خدمات سليمة • بدون أي تعديل للبيانات • ${totalLatency}ms)"
        } else {
            "نتائج الفحص الشامل: $passedCount/5 خدمات سليمة — راجع التفاصيل المقاسة أدناه"
        }

        val report = ComprehensiveDiagnosticReport(
            clusterCode = cleanCluster,
            overallOk = overallOk,
            summaryMessage = summary,
            probes = allProbes,
            preflight = preflight,
            readOnly = true,
            totalLatencyMs = totalLatency
        )
        _comprehensiveReport.value = report
        report
    }

    /**
     * Real-time multi-user listener for user_accounts in the cluster:
     * Automatically updates local Room when any user is added, approved, or has permissions modified.
     */
    fun observeClusterUsersRealtime(
        clusterCode: String,
        dao: PmpDao,
        scope: CoroutineScope,
        currentActiveUserId: () -> Long? = { null }
    ): Flow<Int> = callbackFlow {
        val fbUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        if (fbUser == null) {
            close()
            return@callbackFlow
        }
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val roleKey = runCatching { resolveClusterMemberRoleKey(cleanCluster, fbUser.uid) }.getOrNull()
        if (roleKey != "ADMIN" && roleKey != "MANAGER") {
            // Zero-trust: non-Admin/non-Manager accounts may only read their own document, never list user_accounts
            val memberSnap = runCatching {
                clusterRef(cleanCluster).collection("cluster_members").document(safeId(fbUser.uid, fbUser.uid)).get().await()
            }.getOrNull()
            val ownCode = memberSnap?.getString("userCode")?.trim().orEmpty()
            if (ownCode.isBlank()) {
                awaitClose {}
                return@callbackFlow
            }
            val selfListener = clusterRef(cleanCluster).collection("user_accounts")
                .document(safeId(ownCode, ownCode))
                .addSnapshotListener { doc, error ->
                    if (error != null || doc == null || !doc.exists()) return@addSnapshotListener
                    scope.launch(Dispatchers.IO + syncExceptionHandler) {
                        runCatching {
                            pullSingleUserAccountFromCloud(cleanCluster, dao, fbUser.uid, fbUser.email)
                            trySend(1)
                        }
                    }
                }
            awaitClose { selfListener.remove() }
            return@callbackFlow
        }

        val listener = clusterRef(cleanCluster).collection("user_accounts")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Cluster users listener warning: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && !snapshot.isEmpty) {
                    scope.launch(Dispatchers.IO + syncExceptionHandler) {
                        try {
                            val localUsers = dao.getUserAccountsDirect()
                            val activeId = currentActiveUserId()

                            for (doc in snapshot.documents) {
                                val code = doc.safeString("userCode") ?: doc.id
                                val uname = doc.safeString("username") ?: ""
                                val email = doc.safeString("phoneOrEmail") ?: ""
                                val remoteUid = doc.safeString("remoteServerId") ?: ""

                                val isExplicitlyDeleted = doc.safeBoolean("deleted") == true
                                if (isExplicitlyDeleted || DeletedAccountsRegistry.isDeleted(code, uname, email)) {
                                    val existingDel = localUsers.firstOrNull {
                                        it.userCode.equals(code, ignoreCase = true) ||
                                            (uname.isNotBlank() && it.username.equals(uname, ignoreCase = true))
                                    }
                                    if (existingDel != null && existingDel.id != activeId && !existingDel.isCurrentSession) {
                                        dao.deleteUserAccount(existingDel.id)
                                        if (existingDel.userCode.isNotBlank()) dao.deleteUserAccountByCode(existingDel.userCode)
                                        if (existingDel.username.isNotBlank()) dao.deleteUserAccountByUsername(existingDel.username)
                                    }
                                    runCatching { doc.reference.delete() }
                                    continue
                                }

                                val existing = localUsers.firstOrNull {
                                    it.userCode.equals(code, ignoreCase = true) ||
                                        (uname.isNotBlank() && it.username.equals(uname, ignoreCase = true)) ||
                                        (remoteUid.isNotBlank() && !remoteUid.startsWith("SRV-NODE") && it.remoteServerId == remoteUid)
                                }

                                val isThisUserActiveSession = if (activeId != null) {
                                    existing?.id != null && existing.id == activeId
                                } else {
                                    existing?.isCurrentSession == true
                                }

                                val resolvedActive = if (isThisUserActiveSession) true else (doc.safeBoolean("active") ?: existing?.active ?: true)

                                val entity = UserAccountEntity(
                                    id = existing?.id ?: 0L,
                                    userCode = code,
                                    fullName = doc.safeString("fullName") ?: existing?.fullName ?: uname,
                                    username = uname.ifBlank { existing?.username ?: code.lowercase() },
                                    phoneOrEmail = email.ifBlank { existing?.phoneOrEmail ?: "" },
                                    pinOrPassword = doc.safeString("pinOrPassword") ?: existing?.pinOrPassword ?: "1234",
                                    role = doc.safeString("role") ?: existing?.role ?: "محاسب خزنة ومبيعات",
                                    branch = doc.safeString("branch") ?: existing?.branch ?: "فرع السودان (SD-001)",
                                    permissions = doc.safeString("permissions") ?: existing?.permissions ?: "",
                                    serverSyncToken = doc.safeString("serverSyncToken") ?: existing?.serverSyncToken ?: "SYNC-$code",
                                    remoteServerId = remoteUid.ifBlank { existing?.remoteServerId ?: "" },
                                    syncStatus = doc.safeString("syncStatus") ?: existing?.syncStatus ?: "CLOUD_SYNCED",
                                    serverEndpoint = doc.safeString("serverEndpoint") ?: "https://firestore.googleapis.com/v1",
                                    isCurrentSession = isThisUserActiveSession,
                                    active = resolvedActive,
                                    createdAt = doc.safeLong("createdAt") ?: existing?.createdAt ?: System.currentTimeMillis()
                                )

                                if (existing != null) {
                                    if (existing != entity) {
                                        dao.updateUserAccount(entity)
                                    }
                                } else {
                                    dao.insertUserAccount(entity.copy(id = 0L))
                                }
                            }
                            trySend(snapshot.size())
                        } catch (e: Throwable) {
                            Log.e(TAG, "Failed to merge realtime cluster users", e)
                        }
                    }
                }
            }
        awaitClose { listener.remove() }
    }

    /**
     * Real-time multi-user listener for ERP operational collections (invoices, movements, customers)
     * so changes made by any connected user appear live on all other devices.
     */
    fun observeClusterErpDataRealtime(
        clusterCode: String,
        dao: PmpDao,
        scope: CoroutineScope
    ): Flow<Int> = callbackFlow {
        val fbUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        if (fbUser == null) {
            close()
            return@callbackFlow
        }
        val cRef = clusterRef(clusterCode)

        // 0. الاستماع لشواهد الحذف وعصر النظام (cluster_epoch) في السحابة وتطهير أي سجل محذوف فورياً
        val epochListener = cRef.collection("system_metadata").document("cluster_epoch")
            .addSnapshotListener { docSnap, error ->
                if (error == null && docSnap != null && docSnap.exists()) {
                    val wipeTs = docSnap.getLong("wipeTimestamp") ?: 0L
                    if (wipeTs > 0L) {
                        scope.launch(Dispatchers.IO + syncExceptionHandler) {
                            runCatching {
                                DeletedRecordsRegistry.setClusterEpoch(wipeTs, docSnap.safeString("wipedBy") ?: "SYSTEM_WIPE")
                                PmpDatabase.purgeTombstonedRecordsLocally(dao)
                                DatabaseBackupManager.saveLocalSnapshot(firestore.app.applicationContext, dao)
                                trySend(1)
                            }
                        }
                    }
                }
            }

        val tombListener = cRef.collection("deleted_records")
            .addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null && !snapshot.isEmpty) {
                    scope.launch(Dispatchers.IO + syncExceptionHandler) {
                        try {
                            val tombstones = snapshot.documents.mapNotNull { it.data }
                            DeletedRecordsRegistry.mergeRemoteTombstones(tombstones)
                            DeletedRecordsRegistry.syncWithDao(dao)
                            val purged = PmpDatabase.purgeTombstonedRecordsLocally(dao)
                            if (purged > 0) {
                                DatabaseBackupManager.saveLocalSnapshot(firestore.app.applicationContext, dao)
                            }
                            trySend(snapshot.size())
                        } catch (e: Throwable) {
                            Log.w(TAG, "Notice merging realtime tombstones: ${e.message}")
                        }
                    }
                }
            }

        val invListener = cRef.collection("invoices")
            .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snapshot, error ->
                if (error == null && snapshot != null) {
                    if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                    scope.launch(Dispatchers.IO + syncExceptionHandler) {
                        try {
                            for (dc in snapshot.documentChanges) {
                                if (dc.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                                    val invId = dc.document.safeLong("id")
                                    if (invId != null) {
                                        dao.deleteInvoiceById(invId)
                                    }
                                }
                            }
                            if (!snapshot.isEmpty) {
                                val restoredInvoices = mutableListOf<InvoiceEntity>()
                                val restoredItems = mutableListOf<InvoiceItemEntity>()
                                val restoredPayments = mutableListOf<InvoicePaymentEntity>()

                                for (doc in snapshot.documents) {
                                    val invId = doc.safeLong("id") ?: continue
                                    val invNo = doc.safeString("invoiceNo") ?: doc.id

                                    val isDel = DeletedRecordsRegistry.isDeleted("invoices", invId) ||
                                        DeletedRecordsRegistry.isDeleted("invoices", invNo) ||
                                        DeletedRecordsRegistry.isDeleted("invoices", doc.id)
                                    if (isDel) {
                                        runCatching { doc.reference.delete() }
                                        dao.deleteInvoiceById(invId)
                                        continue
                                    }

                                    restoredInvoices.add(
                                        InvoiceEntity(
                                            id = invId,
                                            invoiceNo = invNo,
                                            customerId = doc.safeLong("customerId") ?: 1L,
                                            customerName = doc.safeString("customerName") ?: "عميل",
                                            invoiceDate = doc.safeString("invoiceDate") ?: "2026-10-26",
                                            currency = doc.safeString("currency") ?: "SDG",
                                            subtotal = doc.safeDouble("subtotal") ?: 0.0,
                                            discount = doc.safeDouble("discount") ?: 0.0,
                                            total = doc.safeDouble("total") ?: 0.0,
                                            notes = doc.safeString("notes") ?: "",
                                            status = doc.safeString("status") ?: "active",
                                            cancelReason = doc.safeString("cancelReason"),
                                            createdBy = doc.safeString("createdBy") ?: "المدير"
                                        )
                                    )
                                    val rawItems = doc.get("items") as? List<Map<String, Any>>
                                    rawItems?.forEachIndexed { idx, itemMap ->
                                        val itemId = (itemMap["id"] as? Number)?.toLong()?.takeIf { it > 0 } ?: (invId * 100 + idx + 1)
                                        restoredItems.add(
                                            InvoiceItemEntity(
                                                id = itemId,
                                                invoiceId = invId,
                                                description = itemMap["description"] as? String ?: "بند فاتورة",
                                                quantity = (itemMap["quantity"] as? Number)?.toDouble() ?: 1.0,
                                                unitPrice = (itemMap["unitPrice"] as? Number)?.toDouble() ?: 0.0,
                                                lineTotal = (itemMap["lineTotal"] as? Number)?.toDouble() ?: 0.0,
                                                productId = (itemMap["productId"] as? Number)?.toLong()?.takeIf { it > 0 },
                                                warehouseId = (itemMap["warehouseId"] as? Number)?.toLong()?.takeIf { it > 0 }
                                            )
                                        )
                                    }
                                    val rawPays = doc.get("payments") as? List<Map<String, Any>>
                                    rawPays?.forEachIndexed { idx, payMap ->
                                        val payId = (payMap["id"] as? Number)?.toLong()?.takeIf { it > 0 } ?: (invId * 100 + idx + 1)
                                        restoredPayments.add(
                                            InvoicePaymentEntity(
                                                id = payId,
                                                invoiceId = invId,
                                                payDate = payMap["payDate"] as? String ?: "2026-10-26",
                                                cashbox = payMap["cashbox"] as? String ?: "خزنة السودان",
                                                amount = (payMap["amount"] as? Number)?.toDouble() ?: 0.0,
                                                note = payMap["note"] as? String ?: "دفعة سحابية",
                                                status = "active",
                                                createdBy = (payMap["createdBy"] as? String) ?: "المدير"
                                            )
                                        )
                                    }
                                }
                                if (restoredInvoices.isNotEmpty()) dao.upsertInvoices(restoredInvoices)
                                if (restoredItems.isNotEmpty()) dao.upsertInvoiceItems(restoredItems)
                                if (restoredPayments.isNotEmpty()) dao.upsertInvoicePayments(restoredPayments)
                            }
                            trySend(snapshot.size())
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error syncing live invoices", e)
                        }
                    }
                }
            }

        val movListener = cRef.collection("movements")
            .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    RoomTransactionLogger.recordSyncBreakage(
                        operation = "OBSERVE_LIVE_MOVEMENTS",
                        entityType = "movements",
                        recordId = "SNAPSHOT",
                        reason = "انقطاع الاستماع المباشر لحركات الخزينة: ${error.message}",
                        cause = error
                    )
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                    scope.launch(Dispatchers.IO + syncExceptionHandler) {
                        try {
                            for (dc in snapshot.documentChanges) {
                                if (dc.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                                    val movId = dc.document.safeLong("id")
                                    val removedRef = dc.document.safeString("reference")?.trim().orEmpty()
                                    val removedSyncUuid = dc.document.safeString("syncUuid")?.trim().orEmpty()
                                    if (movId != null && movId > 0L) {
                                        dao.deleteMovementById(movId)
                                    }
                                    dao.deleteMovementByCloudDocId(dc.document.id)
                                    if (removedRef.isNotBlank()) dao.deleteMovementByReference(removedRef)
                                    if (removedSyncUuid.isNotBlank()) dao.deleteMovementBySyncUuid(removedSyncUuid)
                                }
                            }
                            if (!snapshot.isEmpty) {
                                val localMovs = dao.getMovementsDirect()
                                for (doc in snapshot.documents) {
                                    val ref = doc.safeString("reference")?.trim() ?: doc.id
                                    val syncUuid = doc.safeString("syncUuid")?.trim().orEmpty()
                                    val movId = doc.safeLong("id")
                                        ?: localMovs.firstOrNull {
                                            (syncUuid.isNotBlank() && it.syncUuid == syncUuid) ||
                                                (ref.isNotBlank() && it.reference == ref) ||
                                                it.cloudDocId == doc.id
                                        }?.id
                                        ?: 0L
                                    val docCreatedAt = doc.safeLong("createdAt") ?: 0L
                                    val isDel = (movId > 0L && (
                                        DeletedRecordsRegistry.isDeleted("movements", movId) ||
                                            DeletedRecordsRegistry.isDeleted("movements", "MOV-$movId")
                                        )) ||
                                        DeletedRecordsRegistry.isDeleted("movements", doc.id) ||
                                        (ref.isNotBlank() && DeletedRecordsRegistry.isDeleted("movements", ref)) ||
                                        (syncUuid.isNotBlank() && DeletedRecordsRegistry.isDeleted("movements", syncUuid)) ||
                                        DeletedRecordsRegistry.isPreEpoch(docCreatedAt)
                                    if (isDel) {
                                        runCatching { doc.reference.delete() }
                                        if (movId > 0L) dao.deleteMovementById(movId)
                                        dao.deleteMovementByCloudDocId(doc.id)
                                        if (ref.isNotBlank()) dao.deleteMovementByReference(ref)
                                        if (syncUuid.isNotBlank()) dao.deleteMovementBySyncUuid(syncUuid)
                                    }
                                }
                                val list = snapshot.documents.mapNotNull { doc ->
                                    val ref = doc.safeString("reference")?.trim() ?: doc.id
                                    val syncUuid = doc.safeString("syncUuid")?.trim().orEmpty()
                                    val existing = localMovs.firstOrNull {
                                        (syncUuid.isNotBlank() && it.syncUuid == syncUuid) ||
                                            it.cloudDocId == doc.id ||
                                            (ref.isNotBlank() && it.reference == ref)
                                    }
                                    val parsedNumericId = doc.safeLong("id")
                                        ?: doc.id.hashCode().toLong().let { h -> if (h <= 0L) -h + 100_000L else h }
                                    val id = existing?.id ?: parsedNumericId
                                    val docCreatedAt = doc.safeLong("createdAt")
                                        ?: doc.getTimestamp("createdAt")?.toDate()?.time
                                        ?: doc.getTimestamp("serverUpdatedAt")?.toDate()?.time
                                        ?: System.currentTimeMillis()
                                    val candidate = MovementEntity(
                                        id = id,
                                        txDate = doc.safeString("txDate") ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(docCreatedAt)),
                                        cashbox = doc.safeString("cashbox") ?: "خزنة السودان",
                                        currency = doc.safeString("currency") ?: "SDG",
                                        direction = doc.safeString("direction") ?: "وارد",
                                        amount = doc.safeDouble("amount") ?: 0.0,
                                        description = doc.safeString("description") ?: "",
                                        reference = ref,
                                        createdBy = doc.safeString("createdBy") ?: "المدير",
                                        createdAt = docCreatedAt,
                                        cloudDocId = doc.id,
                                        syncUuid = syncUuid,
                                        updatedAt = doc.safeLong("updatedAt") ?: 0L
                                    )
                                    if (DeletedRecordsRegistry.isMovementDeleted(candidate)) return@mapNotNull null
                                    candidate
                                }
                                if (list.isNotEmpty()) dao.upsertMovements(list)
                            }
                            trySend(snapshot.size())
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error syncing live movements", e)
                            RoomTransactionLogger.recordSyncBreakage(
                                operation = "APPLY_LIVE_MOVEMENTS_TO_ROOM",
                                entityType = "movements",
                                recordId = "UPSERT",
                                reason = "فشل تطبيق الحركات القادمة من السيرفر على قاعدة Room: ${e.message}",
                                cause = e
                            )
                        }
                    }
                }
            }

        val custListener = cRef.collection("customers")
            .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snapshot, error ->
                if (error == null && snapshot != null) {
                    if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                    scope.launch(Dispatchers.IO + syncExceptionHandler) {
                        try {
                            for (dc in snapshot.documentChanges) {
                                if (dc.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                                    val custId = dc.document.safeLong("id")
                                    if (custId != null) {
                                        dao.deleteCustomer(custId)
                                    }
                                }
                            }
                            if (!snapshot.isEmpty) {
                                for (doc in snapshot.documents) {
                                    val custId = doc.safeLong("id") ?: continue
                                    val isDel = DeletedRecordsRegistry.isDeleted("customers", custId) ||
                                        DeletedRecordsRegistry.isDeleted("customers", "CUST-$custId") ||
                                        DeletedRecordsRegistry.isDeleted("customers", doc.id)
                                    if (isDel) {
                                        runCatching { doc.reference.delete() }
                                        dao.deleteCustomer(custId)
                                    }
                                }
                                val list = snapshot.documents.mapNotNull { doc ->
                                    val id = doc.safeLong("id") ?: return@mapNotNull null
                                    if (DeletedRecordsRegistry.isDeleted("customers", id) ||
                                        DeletedRecordsRegistry.isDeleted("customers", "CUST-$id") ||
                                        DeletedRecordsRegistry.isDeleted("customers", doc.id)
                                    ) return@mapNotNull null
                                    CustomerEntity(
                                        id = id,
                                        name = doc.safeString("name") ?: "عميل",
                                        phone = doc.safeString("phone") ?: "",
                                        address = doc.safeString("address") ?: "",
                                        notes = doc.safeString("notes") ?: "",
                                        active = doc.safeBoolean("active") ?: true
                                    )
                                }
                                if (list.isNotEmpty()) dao.upsertCustomers(list)
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error syncing live customers", e)
                        }
                    }
                }
            }

        val prodListener = cRef.collection("products")
            .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snapshot, error ->
                if (error == null && snapshot != null) {
                    scope.launch(Dispatchers.IO + syncExceptionHandler) {
                        try {
                            for (dc in snapshot.documentChanges) {
                                if (dc.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                                    val prodId = dc.document.safeLong("id")
                                    if (prodId != null) {
                                        dao.deleteProduct(prodId)
                                    }
                                }
                            }
                            if (!snapshot.isEmpty) {
                                val list = snapshot.documents.mapNotNull { doc ->
                                    val id = doc.safeLong("id") ?: return@mapNotNull null
                                    if (DeletedRecordsRegistry.isDeleted("products", id) ||
                                        DeletedRecordsRegistry.isDeleted("products", "PROD-$id") ||
                                        DeletedRecordsRegistry.isDeleted("products", doc.id)
                                    ) {
                                        runCatching { doc.reference.delete() }
                                        dao.deleteProduct(id)
                                        return@mapNotNull null
                                    }
                                    ProductEntity(
                                        id = id,
                                        sku = doc.safeString("sku") ?: "PMP-$id",
                                        name = doc.safeString("name") ?: "منتج",
                                        category = doc.safeString("category") ?: "بوهيات",
                                        unit = doc.safeString("unit") ?: "كرتونة",
                                        purchasePrice = doc.safeDouble("purchasePrice") ?: 0.0,
                                        salePrice = doc.safeDouble("salePrice") ?: 0.0,
                                        salePriceEgp = doc.safeDouble("salePriceEgp") ?: 0.0,
                                        minimumStock = doc.safeDouble("minimumStock") ?: 10.0,
                                        paintColor = doc.safeString("paintColor") ?: "أبيض",
                                        packageWeight = doc.safeString("packageWeight") ?: "900",
                                        active = doc.safeBoolean("active") ?: true
                                    )
                                }
                                if (list.isNotEmpty()) {
                                    dao.upsertProducts(list)
                                    com.example.util.InventoryWeightManager.getInstance(firestore.app.applicationContext)
                                        .syncDiscoveredProductWeights(list.map { it.packageWeight })
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error syncing live products", e)
                        }
                    }
                }
            }

        val weightsListener = cRef.collection("config").document("inventory_weights")
            .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snapshot, error ->
                if (error == null && snapshot != null && snapshot.exists()) {
                    if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                    scope.launch(Dispatchers.IO + syncExceptionHandler) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val rawWeights = snapshot.get("weights") as? List<Map<String, Any?>>
                            val weightsJson = snapshot.getString("weightsJson")
                            val defaultWeight = snapshot.getString("defaultWeight")
                            com.example.util.InventoryWeightManager.getInstance(firestore.app.applicationContext)
                                .mergeCloudWeightsFromFirestoreDoc(weightsJson, rawWeights, defaultWeight)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error syncing live inventory_weights", e)
                        }
                    }
                }
            }

        val fiListener = cRef.collection("factory_items")
            .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snapshot, error ->
                if (error == null && snapshot != null) {
                    if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                    scope.launch(Dispatchers.IO + syncExceptionHandler) {
                        try {
                            for (dc in snapshot.documentChanges) {
                                if (dc.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                                    val fiId = dc.document.safeLong("id")
                                    if (fiId != null) {
                                        dao.deleteFactoryItemById(fiId)
                                    }
                                }
                            }
                            if (!snapshot.isEmpty) {
                                val list = snapshot.documents.mapNotNull { doc ->
                                    val id = doc.safeLong("id") ?: return@mapNotNull null
                                    val name = doc.safeString("name") ?: "خامة"
                                    if (DeletedRecordsRegistry.isDeleted("factory_items", id) ||
                                        DeletedRecordsRegistry.isDeleted("factory_items", "FI-$id") ||
                                        DeletedRecordsRegistry.isDeleted("factory_items", doc.id) ||
                                        DeletedRecordsRegistry.isDeleted("factory_items", name)
                                    ) {
                                        runCatching { doc.reference.delete() }
                                        dao.deleteFactoryItemById(id)
                                        return@mapNotNull null
                                    }
                                    FactoryItemEntity(
                                        id = id,
                                        name = name,
                                        unit = doc.safeString("unit") ?: "كجم",
                                        category = doc.safeString("category") ?: "خامات إنتاج"
                                    )
                                }
                                if (list.isNotEmpty()) dao.upsertFactoryItems(list)
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error syncing live factory items", e)
                        }
                    }
                }
            }

        val cbListener = cRef.collection("cashboxes")
            .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snapshot, error ->
                if (error == null && snapshot != null) {
                    if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                    scope.launch(Dispatchers.IO + syncExceptionHandler) {
                        try {
                            val localBoxes = dao.getCashboxesDirect()
                            for (dc in snapshot.documentChanges) {
                                if (dc.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                                    val removedName = dc.document.safeString("name")?.trim().orEmpty()
                                    val removedId = dc.document.safeLong("id")
                                    if (removedId != null && removedId > 0L) {
                                        dao.deleteCashbox(removedId)
                                    }
                                    if (removedName.isNotBlank()) {
                                        localBoxes.filter { it.name.trim().equals(removedName, ignoreCase = true) }
                                            .forEach { dao.deleteCashbox(it.id) }
                                    }
                                }
                            }
                            if (!snapshot.isEmpty) {
                                val currentLocal = dao.getCashboxesDirect()
                                val validList = snapshot.documents.mapNotNull { doc ->
                                    val name = doc.safeString("name")?.trim() ?: return@mapNotNull null
                                    if (name.isBlank()) return@mapNotNull null
                                    val remoteId = doc.safeLong("id") ?: 0L
                                    val cbDocId = DeletedRecordsRegistry.cashboxDocId(name, remoteId)
                                    if (DeletedRecordsRegistry.isDeleted("cashboxes", name) ||
                                        DeletedRecordsRegistry.isDeleted("cashboxes", cbDocId) ||
                                        DeletedRecordsRegistry.isDeleted("cashboxes", doc.id) ||
                                        (remoteId > 0L && DeletedRecordsRegistry.isDeleted("cashboxes", remoteId))
                                    ) {
                                        runCatching { doc.reference.delete() }
                                        currentLocal.filter { it.name.trim().equals(name, ignoreCase = true) || (remoteId > 0L && it.id == remoteId) }
                                            .forEach { dao.deleteCashbox(it.id) }
                                        return@mapNotNull null
                                    }
                                    val existing = currentLocal.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
                                    CashboxEntity(
                                        id = existing?.id ?: remoteId,
                                        name = name
                                    )
                                }.distinctBy { it.name.trim().lowercase() }
                                if (validList.isNotEmpty()) {
                                    dao.upsertCashboxes(validList)
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error syncing live cashboxes", e)
                        }
                    }
                }
            }

        val whListener = cRef.collection("warehouses")
            .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snapshot, error ->
                if (error == null && snapshot != null) {
                    if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                    scope.launch(Dispatchers.IO + syncExceptionHandler) {
                        try {
                            val localWh = dao.getWarehousesDirect()
                            for (dc in snapshot.documentChanges) {
                                if (dc.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                                    val removedName = dc.document.safeString("name")?.trim().orEmpty()
                                    val removedId = dc.document.safeLong("id")
                                    if (removedId != null && removedId > 0L) {
                                        dao.clearStockMovesByWarehouse(removedId)
                                        dao.deleteWarehouse(removedId)
                                    }
                                    if (removedName.isNotBlank()) {
                                        localWh.filter { it.name.trim().equals(removedName, ignoreCase = true) }
                                            .forEach {
                                                dao.clearStockMovesByWarehouse(it.id)
                                                dao.deleteWarehouse(it.id)
                                            }
                                    }
                                }
                            }
                            if (!snapshot.isEmpty) {
                                val currentLocal = dao.getWarehousesDirect()
                                val validList = snapshot.documents.mapNotNull { doc ->
                                    val name = doc.safeString("name")?.trim() ?: return@mapNotNull null
                                    if (name.isBlank()) return@mapNotNull null
                                    val remoteId = doc.safeLong("id") ?: 0L
                                    if (DeletedRecordsRegistry.isDeleted("warehouses", name) ||
                                        DeletedRecordsRegistry.isDeleted("warehouses", doc.id) ||
                                        (remoteId > 0L && (
                                            DeletedRecordsRegistry.isDeleted("warehouses", remoteId) ||
                                                DeletedRecordsRegistry.isDeleted("warehouses", "WH-$remoteId")
                                            ))
                                    ) {
                                        runCatching { doc.reference.delete() }
                                        currentLocal.filter { it.name.trim().equals(name, ignoreCase = true) || (remoteId > 0L && it.id == remoteId) }
                                            .forEach {
                                                dao.clearStockMovesByWarehouse(it.id)
                                                dao.deleteWarehouse(it.id)
                                            }
                                        return@mapNotNull null
                                    }
                                    val existing = currentLocal.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
                                    WarehouseEntity(
                                        id = existing?.id ?: remoteId,
                                        name = name,
                                        active = doc.safeBoolean("active") ?: existing?.active ?: true,
                                        currency = doc.safeString("currency") ?: existing?.currency ?: "SDG",
                                        linkedCashbox = doc.safeString("linkedCashbox") ?: existing?.linkedCashbox ?: "خزنة السودان",
                                        warehouseType = doc.safeString("warehouseType") ?: existing?.warehouseType ?: "مبيعات"
                                    )
                                }.distinctBy { it.name.trim().lowercase() }
                                if (validList.isNotEmpty()) {
                                    dao.upsertWarehouses(validList)
                                    dao.removeDuplicateWarehouses()
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error syncing live warehouses", e)
                        }
                    }
                }
            }

        awaitClose {
            epochListener.remove()
            tombListener.remove()
            invListener.remove()
            movListener.remove()
            custListener.remove()
            prodListener.remove()
            weightsListener.remove()
            fiListener.remove()
            cbListener.remove()
            whListener.remove()
        }
    }

    /**
     * Immediately pushes registered inventory weights to Firestore so all devices receive updated weight options.
     */
    suspend fun syncInventoryWeightsToCloud(
        clusterCode: String,
        actor: String = "المدير"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cRef = clusterRef(clusterCode)
            val weightMgr = com.example.util.InventoryWeightManager.getInstance(firestore.app.applicationContext)
            val payload = weightMgr.exportForCloudSync().toMutableMap()
            payload["updatedBy"] = actor
            payload["serverUpdatedAt"] = FieldValue.serverTimestamp()
            cRef.collection("config").document("inventory_weights").set(
                payload,
                SetOptions.merge()
            ).await()
            Unit
        }
    }

    /**
     * Real-time listener for incoming invoices from Windows or other phone instances.
     */
    fun observeCloudInvoices(clusterCode: String): Flow<List<Map<String, Any>>> = callbackFlow {
        val listener = clusterRef(clusterCode).collection("invoices")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Cloud invoices listener error", error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.map { it.data ?: emptyMap() }
                    trySend(list)
                }
            }
        awaitClose { listener.remove() }
    }

    /**
     * Directly syncs a single chemical formulation to Google Cloud Firestore.
     */
    suspend fun syncSingleFormulationToCloud(
        clusterCode: String,
        form: FormulationEntity
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cRef = clusterRef(clusterCode)
            val docId = if (form.formulaNo.isNotBlank()) {
                form.formulaNo.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
            } else {
                "FORM-${form.id}"
            }
            val payload = mapOf(
                "id" to form.id,
                "syncUuid" to form.syncUuid,
                "formulaNo" to form.formulaNo,
                "productName" to form.productName,
                "linkedProductId" to (form.linkedProductId ?: 0L),
                "linkedProductSku" to form.linkedProductSku,
                "category" to form.category,
                "paintType" to form.paintType,
                "baseKind" to form.baseKind,
                "versionNumber" to form.versionNumber,
                "approvalStatus" to form.approvalStatus,
                "approvedBy" to form.approvedBy,
                "approvedAt" to form.approvedAt,
                "targetPvc" to form.targetPvc,
                "calculatedPvc" to form.calculatedPvc,
                "cpvcEstimated" to form.cpvcEstimated,
                "totalSolidsWeight" to form.totalSolidsWeight,
                "totalSolidsVolume" to form.totalSolidsVolume,
                "totalBatchWeight" to form.totalBatchWeight,
                "totalBatchVolume" to form.totalBatchVolume,
                "calculatedDensity" to form.calculatedDensity,
                "totalCost" to form.totalCost,
                "costPerKg" to form.costPerKg,
                "historicalTotalCost" to form.historicalTotalCost,
                "historicalCostPerKg" to form.historicalCostPerKg,
                "currency" to form.currency,
                "changeReason" to form.changeReason,
                "updatedBy" to form.updatedBy,
                "updatedAt" to form.updatedAt,
                "ingredientsJson" to form.ingredientsJson,
                "confidentialCiphertext" to form.confidentialCiphertext,
                "isConfidentialSealed" to form.isConfidentialSealed,
                "technicalAnalysis" to form.technicalAnalysis,
                "notes" to form.notes,
                "approvedForProduction" to form.approvedForProduction,
                "createdBy" to form.createdBy,
                "createdAt" to form.createdAt,
                "serverUpdatedAt" to FieldValue.serverTimestamp()
            )
            val summaryPayload = mapOf(
                "id" to form.id,
                "formulaNo" to form.formulaNo,
                "productName" to form.productName,
                "linkedProductId" to (form.linkedProductId ?: 0L),
                "category" to form.category,
                "paintType" to form.paintType,
                "baseKind" to form.baseKind,
                "versionNumber" to form.versionNumber,
                "approvalStatus" to form.approvalStatus,
                "calculatedPvc" to form.calculatedPvc,
                "calculatedDensity" to form.calculatedDensity,
                "costPerKg" to form.costPerKg,
                "historicalCostPerKg" to form.historicalCostPerKg,
                "currency" to form.currency,
                "approvedForProduction" to form.approvedForProduction,
                "serverUpdatedAt" to FieldValue.serverTimestamp()
            )
            cRef.collection("lab_formulations").document(docId).set(payload, SetOptions.merge()).await()
            runCatching {
                cRef.collection("lab_formula_summaries").document(docId).set(summaryPayload, SetOptions.merge()).await()
            }
            Unit
        }
    }

    /**
     * Directly syncs a daily production paint batch to Google Cloud Firestore,
     * maintaining the link to its chemical formulation.
     */
    suspend fun syncSingleProductionBatchToCloud(
        clusterCode: String,
        batch: ProductionBatchEntity
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cRef = clusterRef(clusterCode)
            val docId = if (batch.batchNumber.isNotBlank()) {
                batch.batchNumber.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
            } else {
                "BATCH-${batch.id}"
            }
            val payload = mapOf(
                "id" to batch.id,
                "batchNumber" to batch.batchNumber,
                "productionDate" to batch.productionDate,
                "formulaId" to (batch.formulaId ?: 0L),
                "formulaNo" to batch.formulaNo,
                "productName" to batch.productName,
                "paintType" to batch.paintType,
                "baseKind" to batch.baseKind,
                "targetPvc" to batch.targetPvc,
                "targetWeightKg" to batch.targetWeightKg,
                "actualWeightKg" to batch.actualWeightKg,
                "targetVolumeLiters" to batch.targetVolumeLiters,
                "cartonsCount" to batch.cartonsCount,
                "packageUnit" to batch.packageUnit,
                "machineId" to (batch.machineId ?: 0L),
                "machineName" to batch.machineName,
                "qualityStatus" to batch.qualityStatus,
                "measuredDensity" to batch.measuredDensity,
                "measuredViscosity" to batch.measuredViscosity,
                "scaledIngredientsJson" to batch.scaledIngredientsJson,
                "supervisorOrEngineer" to batch.supervisorOrEngineer,
                "shift" to batch.shift,
                "notes" to batch.notes,
                "syncStatus" to "SYNCED",
                "createdAt" to batch.createdAt,
                "serverUpdatedBy" to currentActorId,
                "serverUpdatedAt" to FieldValue.serverTimestamp()
            )
            cRef.collection("paint_batches").document(docId).set(payload, SetOptions.merge()).await()
            runCatching {
                firestore.collection("paint_batches").document(docId).set(payload, SetOptions.merge()).await()
            }
            Unit
        }
    }

    /**
     * Deletes a production batch from Google Cloud Firestore.
     */
    suspend fun deleteProductionBatchFromCloud(
        clusterCode: String,
        id: Long,
        batchNumber: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
            val cRef = clusterRef(cleanCluster)
            val actor = currentActorId
            val cleanBatch = batchNumber.trim()
            val primaryDocId = if (cleanBatch.isNotBlank()) {
                safeId(cleanBatch, "BATCH-$id")
            } else {
                "BATCH-$id"
            }
            val candidateIds = linkedSetOf(primaryDocId, "BATCH-$id")
            if (id > 0L) candidateIds.add(id.toString())

            DeletedRecordsRegistry.registerDeletedWithAliases(
                collection = "paint_batches",
                aliases = (candidateIds + listOfNotNull(cleanBatch.takeIf { it.isNotBlank() })).toList(),
                primaryEntityId = if (id > 0L) id.toString() else primaryDocId,
                primaryDocId = primaryDocId,
                actor = actor
            )

            for (docCandidate in listOf(primaryDocId, "BATCH-$id").distinct()) {
                val tombDocId = safeId("paint_batches_$docCandidate", "paint_batches_${id}_$docCandidate")
                val tombData = mutableMapOf<String, Any>(
                    "collection" to "paint_batches",
                    "entityId" to (if (id > 0L) id.toString() else docCandidate),
                    "docId" to docCandidate,
                    "aliases" to candidateIds.toList(),
                    "deletedAt" to FieldValue.serverTimestamp(),
                    "deletedBy" to actor,
                    "clusterCode" to cleanCluster
                )
                if (cleanBatch.isNotBlank()) tombData["batchNumber"] = cleanBatch
                runCatching {
                    cRef.collection("deleted_records").document(tombDocId).set(tombData, SetOptions.merge()).await()
                }
                runCatching {
                    firestore.collection("deleted_records").document(tombDocId).set(tombData, SetOptions.merge()).await()
                }
                runCatching { cRef.collection("paint_batches").document(docCandidate).delete().await() }
                runCatching { firestore.collection("paint_batches").document(docCandidate).delete().await() }
            }

            recordDiagnostic(
                action = "DELETE_BATCH_CLOUD",
                status = "SUCCESS",
                count = 1,
                message = "تم تسجيل شاهد الحذف وحذف تشغيلة الإنتاج ($primaryDocId) نهائياً من السيرفر"
            )
            Unit
        }
    }

    /**
     * Generic safe delete for an operational entity from Google Cloud Firestore.
     * Prevents resurrection during subsequent bidirectional synchronization passes.
     */
    suspend fun deleteEntityFromCloud(
        clusterCode: String,
        collectionName: String,
        docId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanDocId = safeId(docId, docId)
            val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
            val cRef = clusterRef(cleanCluster)
            val actor = currentActorId

            // 1. تسجيل الشاهد محلياً في DeletedRecordsRegistry
            DeletedRecordsRegistry.registerDeleted(collectionName, cleanDocId, cleanDocId, actor)

            // 2. كتابة شاهد الحذف الدائم (Tombstone) على السحابة وتأكيد حفظه قبل حذف المستند
            val tombstoneDocId = safeId("${collectionName}_${cleanDocId}", cleanDocId)
            val tombstoneData = mapOf(
                "collection" to collectionName,
                "entityId" to cleanDocId,
                "docId" to cleanDocId,
                "deletedAt" to FieldValue.serverTimestamp(),
                "deletedBy" to actor,
                "clusterCode" to cleanCluster
            )
            runCatching {
                cRef.collection("deleted_records").document(tombstoneDocId).set(tombstoneData, SetOptions.merge()).await()
            }
            runCatching {
                firestore.collection("deleted_records").document(tombstoneDocId).set(tombstoneData, SetOptions.merge()).await()
            }

            // 3. حذف المستند الفعلي من السيرفر السحابي
            runCatching { cRef.collection(collectionName).document(cleanDocId).delete().await() }
            if (cleanCluster != DEFAULT_CLUSTER) {
                runCatching {
                    clusterRef(DEFAULT_CLUSTER).collection("deleted_records").document(tombstoneDocId).set(tombstoneData, SetOptions.merge()).await()
                    clusterRef(DEFAULT_CLUSTER).collection(collectionName).document(cleanDocId).delete().await()
                }
            }
            if (collectionName in listOf("lab_formulations", "paint_batches")) {
                runCatching {
                    firestore.collection(collectionName).document(cleanDocId).delete().await()
                }
            }
            recordDiagnostic(
                action = "DELETE_CLOUD_ENTITY",
                status = "SUCCESS",
                count = 1,
                message = "تم تسجيل شاهد الحذف ($cleanDocId) وحذف المستند من ($collectionName) على السيرفر لمنع استرجاعه"
            )
            Unit
        }
    }

    /**
     * حذف حركة مالية من السحابة مع كافة مستنداتها البديلة (MOV-id، MOV-idempotencyKey، reference، syncUuid)
     * وتسجيل شاهد حذف شامل يمنع عودتها من أي منصة أو نسخة مخزنة محلياً.
     */
    suspend fun deleteMovementFromCloud(
        clusterCode: String,
        movementId: Long,
        reference: String = "",
        cloudDocId: String = "",
        syncUuid: String = "",
        actor: String = currentActorId
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
            val cRef = clusterRef(cleanCluster)
            val cleanRef = reference.trim()
            val cleanCloudDoc = cloudDocId.trim()
            val cleanUuid = syncUuid.trim()

            val aliases = linkedSetOf<String>()
            if (movementId > 0L) {
                aliases.add(movementId.toString())
                aliases.add("MOV-$movementId")
            }
            if (cleanCloudDoc.isNotBlank()) aliases.add(cleanCloudDoc)
            if (cleanRef.isNotBlank()) aliases.add(cleanRef)
            if (cleanUuid.isNotBlank()) aliases.add(cleanUuid)

            val primaryDoc = cleanCloudDoc.ifBlank { if (movementId > 0L) "MOV-$movementId" else cleanRef }
            if (primaryDoc.isBlank()) return@runCatching Unit

            DeletedRecordsRegistry.registerDeletedWithAliases(
                collection = "movements",
                aliases = aliases,
                primaryEntityId = if (movementId > 0L) movementId.toString() else primaryDoc,
                primaryDocId = primaryDoc,
                actor = actor
            )

            suspend fun writeTombstoneAndDeleteDoc(docIdCandidate: String) {
                val cleanDoc = safeId(docIdCandidate, docIdCandidate)
                if (cleanDoc.isBlank()) return
                val tombDocId = safeId("movements_$cleanDoc", "movements_${movementId}_${cleanDoc.take(24)}")
                val tombData = mutableMapOf<String, Any>(
                    "collection" to "movements",
                    "entityId" to (if (movementId > 0L) movementId.toString() else cleanDoc),
                    "docId" to cleanDoc,
                    "aliases" to aliases.toList(),
                    "deletedAt" to FieldValue.serverTimestamp(),
                    "deletedBy" to actor,
                    "clusterCode" to cleanCluster
                )
                if (movementId > 0L) tombData["numericId"] = movementId
                if (cleanRef.isNotBlank()) tombData["reference"] = cleanRef
                if (cleanUuid.isNotBlank()) tombData["syncUuid"] = cleanUuid

                runCatching {
                    cRef.collection("deleted_records").document(tombDocId).set(tombData, SetOptions.merge()).await()
                }
                runCatching {
                    cRef.collection("movements").document(cleanDoc).delete().await()
                }
            }

            if (movementId > 0L) writeTombstoneAndDeleteDoc("MOV-$movementId")
            if (cleanCloudDoc.isNotBlank() && cleanCloudDoc != "MOV-$movementId") {
                writeTombstoneAndDeleteDoc(cleanCloudDoc)
            }

            if (movementId > 0L) {
                runCatching {
                    val byIdSnap = cRef.collection("movements").whereEqualTo("id", movementId).get().await()
                    for (doc in byIdSnap.documents) {
                        DeletedRecordsRegistry.registerDeleted("movements", doc.id, doc.id, actor)
                        writeTombstoneAndDeleteDoc(doc.id)
                        runCatching { doc.reference.delete().await() }
                    }
                }
            }
            if (cleanRef.isNotBlank()) {
                runCatching {
                    val byRefSnap = cRef.collection("movements").whereEqualTo("reference", cleanRef).get().await()
                    for (doc in byRefSnap.documents) {
                        val docNumId = doc.getLong("id")
                        if (docNumId != null && docNumId > 0L) {
                            DeletedRecordsRegistry.registerDeleted("movements", docNumId, doc.id, actor)
                        }
                        DeletedRecordsRegistry.registerDeleted("movements", doc.id, doc.id, actor)
                        writeTombstoneAndDeleteDoc(doc.id)
                        runCatching { doc.reference.delete().await() }
                    }
                }
            }
            if (cleanUuid.isNotBlank()) {
                runCatching {
                    val byUuidSnap = cRef.collection("movements").whereEqualTo("syncUuid", cleanUuid).get().await()
                    for (doc in byUuidSnap.documents) {
                        writeTombstoneAndDeleteDoc(doc.id)
                        runCatching { doc.reference.delete().await() }
                    }
                }
            }

            recordDiagnostic(
                action = "DELETE_MOVEMENT_CLOUD",
                status = "SUCCESS",
                count = 1,
                message = "تم حذف الحركة المالية ($primaryDoc) مع كافة معرفاتها البديلة وتسجيل شاهد الحذف في السحابة"
            )
            Unit
        }
    }

    /**
     * Deletes a chemical formulation from Google Cloud Firestore.
     */
    suspend fun deleteFormulationFromCloud(
        clusterCode: String,
        id: Long,
        formulaNo: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val docId = if (formulaNo.isNotBlank()) {
                formulaNo.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
            } else {
                "FORM-$id"
            }
            deleteEntityFromCloud(clusterCode, "lab_formulations", docId)
            deleteEntityFromCloud(clusterCode, "lab_formulations", "FORM-$id")
            Unit
        }
    }

    /**
     * Verifies server reachability and rules by performing a direct, non-cached read (Source.SERVER).
     */
    suspend fun testDirectServerRead(clusterCode: String): Result<String> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val fbUser = runCatching { Firebase.auth.currentUser }.getOrNull()
                ?: return@withContext Result.failure(IllegalStateException("يرجى تسجيل الدخول بحساب Google المعتمد أولاً."))
            val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
            runCatching { ensureOwnClusterMemberRegistration(cleanCluster, null, fbUser.uid, fbUser.email) }
            val cRef = clusterRef(cleanCluster)
            val serverSnap = cRef.collection("warehouses").limit(1).get(com.google.firebase.firestore.Source.SERVER).await()
            val latency = System.currentTimeMillis() - start
            val count = serverSnap.size()
            val msg = "نجح فحص القراءة المباشرة من السيرفر (Source.SERVER): استجابة ${latency}ms (المستندات: $count)"
            recordDiagnostic(
                action = "DIRECT_SERVER_READ_TEST",
                status = "SUCCESS",
                count = count,
                message = msg
            )
            Result.success(msg)
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            val errMsg = "فشل فحص القراءة المباشرة من السيرفر (Source.SERVER): ${e.message} (${latency}ms)"
            recordDiagnostic(
                action = "DIRECT_SERVER_READ_TEST",
                status = "FAILED",
                count = 0,
                message = errMsg
            )
            Result.failure(e)
        }
    }

    /**
     * Clears all movements associated with a specific cashbox from Cloud Firestore,
     * registering persistent deleted_records tombstones for every deleted movement BEFORE deletion
     * so offline devices can NEVER re-upload them upon reconnect/sync.
     */
    suspend fun clearCashboxMovementsFromCloud(
        clusterCode: String,
        cashboxName: String,
        knownClearedMovements: List<MovementEntity> = emptyList(),
        actor: String = currentActorId
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
            val cRef = clusterRef(cleanCluster)
            val cleanBox = cashboxName.trim()

            // Register tombstones for any locally cleared movements first (covers offline/cached cases)
            knownClearedMovements.forEach { m ->
                val aliases = listOfNotNull(
                    m.id.takeIf { it > 0L }?.toString(),
                    m.id.takeIf { it > 0L }?.let { "MOV-$it" },
                    m.cloudDocId.takeIf { it.isNotBlank() },
                    m.reference.takeIf { it.isNotBlank() },
                    m.syncUuid.takeIf { it.isNotBlank() }
                )
                val primaryDoc = m.cloudDocId.ifBlank { "MOV-${m.id}" }
                DeletedRecordsRegistry.registerDeletedWithAliases(
                    collection = "movements",
                    aliases = aliases,
                    primaryEntityId = m.id.toString(),
                    primaryDocId = primaryDoc,
                    actor = actor
                )
                val tombDocId = safeId("movements_$primaryDoc", "movements_${m.id}")
                val tombData = mutableMapOf<String, Any>(
                    "collection" to "movements",
                    "entityId" to m.id.toString(),
                    "numericId" to m.id,
                    "docId" to primaryDoc,
                    "aliases" to aliases,
                    "deletedAt" to FieldValue.serverTimestamp(),
                    "deletedBy" to actor,
                    "clusterCode" to cleanCluster
                )
                if (m.reference.isNotBlank()) tombData["reference"] = m.reference
                if (m.syncUuid.isNotBlank()) tombData["syncUuid"] = m.syncUuid
                runCatching {
                    cRef.collection("deleted_records").document(tombDocId).set(tombData, SetOptions.merge()).await()
                }
            }

            // Fetch all cloud movements matching cashbox (normalized case/whitespace insensitive fallback)
            val allCloudMovements = cRef.collection("movements").get().await()
            val matchedDocs = allCloudMovements.documents.filter { doc ->
                val docBox = doc.safeString("cashbox")?.trim().orEmpty()
                docBox.equals(cleanBox, ignoreCase = true)
            }

            if (matchedDocs.isNotEmpty()) {
                matchedDocs.chunked(200).forEach { chunk ->
                    val tombBatch = firestore.batch()
                    chunk.forEach { doc ->
                        val docId = doc.id
                        val numId = doc.safeLong("id") ?: 0L
                        val ref = doc.safeString("reference")?.trim().orEmpty()
                        val syncUuid = doc.safeString("syncUuid")?.trim().orEmpty()
                        val aliases = listOfNotNull(
                            numId.takeIf { it > 0L }?.toString(),
                            numId.takeIf { it > 0L }?.let { "MOV-$it" },
                            docId,
                            ref.takeIf { it.isNotBlank() },
                            syncUuid.takeIf { it.isNotBlank() }
                        ).distinct()
                        DeletedRecordsRegistry.registerDeletedWithAliases(
                            collection = "movements",
                            aliases = aliases,
                            primaryEntityId = if (numId > 0L) numId.toString() else docId,
                            primaryDocId = docId,
                            actor = actor
                        )
                        val tombDocId = safeId("movements_$docId", docId)
                        val tombData = mutableMapOf<String, Any>(
                            "collection" to "movements",
                            "entityId" to (if (numId > 0L) numId.toString() else docId),
                            "docId" to docId,
                            "aliases" to aliases,
                            "deletedAt" to FieldValue.serverTimestamp(),
                            "deletedBy" to actor,
                            "clusterCode" to cleanCluster
                        )
                        if (numId > 0L) tombData["numericId"] = numId
                        if (ref.isNotBlank()) tombData["reference"] = ref
                        if (syncUuid.isNotBlank()) tombData["syncUuid"] = syncUuid
                        tombBatch.set(cRef.collection("deleted_records").document(tombDocId), tombData, SetOptions.merge())
                    }
                    tombBatch.commit().await()

                    val delBatch = firestore.batch()
                    chunk.forEach { doc ->
                        delBatch.delete(doc.reference)
                    }
                    delBatch.commit().await()
                }
            }
            Unit
        }
    }

    /**
     * Synchronizes a single cashbox (added or renamed) immediately to Cloud Firestore.
     */
    suspend fun syncSingleCashboxToCloud(
        clusterCode: String,
        id: Long,
        cashboxName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = cashboxName.trim()
            if (cleanName.isBlank()) return@runCatching
            DeletedRecordsRegistry.unregisterDeleted("cashboxes", cleanName)
            if (id > 0L) DeletedRecordsRegistry.unregisterDeleted("cashboxes", id)
            val docId = DeletedRecordsRegistry.cashboxDocId(cleanName, id)
            val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
            val cRef = clusterRef(cleanCluster)
            val data = mapOf(
                "id" to id,
                "name" to cleanName,
                "docId" to docId,
                "serverUpdatedBy" to currentActorId,
                "serverUpdatedAt" to FieldValue.serverTimestamp()
            )
            cRef.collection("cashboxes").document(docId).set(data, SetOptions.merge()).await()
            // Remove any stale tombstone for this cashbox if it was re-created by admin
            val tombDocId = safeId("cashboxes_$docId", docId)
            runCatching { cRef.collection("deleted_records").document(tombDocId).delete() }
            runCatching { firestore.collection("deleted_records").document(tombDocId).delete() }
            Unit
        }
    }

    /**
     * Permanently deletes/archives a cashbox from Cloud Firestore and writes a tombstone
     * so all connected devices and accounts remove it immediately and never resurrect it,
     * WITHOUT deleting any historical movements or financial transactions linked to it.
     */
    suspend fun deleteCashboxFromCloud(
        clusterCode: String,
        id: Long,
        cashboxName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = cashboxName.trim()
            val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
            val cRef = clusterRef(cleanCluster)
            val actor = currentActorId
            val docId = DeletedRecordsRegistry.cashboxDocId(cleanName, id)

            // 1. Register local tombstones for both name and ID
            if (id > 0L) {
                DeletedRecordsRegistry.registerDeleted("cashboxes", id, "BOX-$id", actor)
            }
            if (cleanName.isNotBlank()) {
                DeletedRecordsRegistry.registerDeleted("cashboxes", cleanName, docId, actor)
            }

            // 2. Write cloud tombstone to deleted_records (preserving Arabic name in entityId!)
            val tombstoneDocId = safeId("cashboxes_$docId", docId)
            val tombstoneData = mapOf(
                "collection" to "cashboxes",
                "entityId" to cleanName.ifBlank { "BOX-$id" },
                "numericId" to id,
                "docId" to docId,
                "deletedAt" to FieldValue.serverTimestamp(),
                "deletedBy" to actor,
                "clusterCode" to cleanCluster
            )
            runCatching {
                cRef.collection("deleted_records").document(tombstoneDocId).set(tombstoneData, SetOptions.merge()).await()
                firestore.collection("deleted_records").document(tombstoneDocId).set(tombstoneData, SetOptions.merge())
            }

            // 3. Delete all Firestore cashbox documents matching this cashbox (by hash docId, BOX-id, and name query)
            runCatching { cRef.collection("cashboxes").document(docId).delete().await() }
            if (id > 0L) {
                runCatching { cRef.collection("cashboxes").document("BOX-$id").delete().await() }
            }
            if (cleanName.isNotBlank()) {
                runCatching {
                    val byNameSnap = cRef.collection("cashboxes").whereEqualTo("name", cleanName).get().await()
                    for (doc in byNameSnap.documents) {
                        doc.reference.delete().await()
                    }
                }
            }
            recordDiagnostic(
                action = "DELETE_CASHBOX_CLOUD",
                status = "SUCCESS",
                count = 1,
                message = "تم حذف/أرشفة الخزنة ($cleanName) سحابياً وتسجيل شاهد الحذف ($docId) مع الحفاظ الكامل على سجل الحركات المالية"
            )
            Unit
        }
    }

    /**
     * Permanently deletes a warehouse (and all duplicates sharing its name) + linked stock_moves from Firestore
     * and writes Android/Web tombstones so deleted warehouses never reappear across devices.
     */
    suspend fun deleteWarehouseFromCloud(
        clusterCode: String,
        id: Long,
        warehouseName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = warehouseName.trim()
            val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
            val cRef = clusterRef(cleanCluster)
            val actor = currentActorId
            val primaryDocId = "WH-$id"

            if (id > 0L) {
                DeletedRecordsRegistry.registerDeleted("warehouses", id, primaryDocId, actor)
            }
            if (cleanName.isNotBlank()) {
                DeletedRecordsRegistry.registerDeleted("warehouses", cleanName, primaryDocId, actor)
            }

            val tombDocId = safeId("warehouses_$primaryDocId", primaryDocId)
            val tombstoneData = mapOf(
                "collection" to "warehouses",
                "entityId" to cleanName.ifBlank { primaryDocId },
                "numericId" to id,
                "docId" to primaryDocId,
                "deletedAt" to FieldValue.serverTimestamp(),
                "deletedBy" to actor,
                "clusterCode" to cleanCluster
            )
            runCatching {
                cRef.collection("deleted_records").document(tombDocId).set(tombstoneData, SetOptions.merge()).await()
                firestore.collection("deleted_records").document(tombDocId).set(tombstoneData, SetOptions.merge())
            }

            if (id > 0L) {
                runCatching { cRef.collection("warehouses").document(primaryDocId).delete().await() }
                deleteStockMovesByWarehouseFromCloud(cleanCluster, id)
            }
            if (cleanName.isNotBlank()) {
                runCatching {
                    val byNameSnap = cRef.collection("warehouses").whereEqualTo("name", cleanName).get().await()
                    for (doc in byNameSnap.documents) {
                        val dupId = doc.getLong("id")
                        if (dupId != null && dupId > 0L) {
                            DeletedRecordsRegistry.registerDeleted("warehouses", dupId, doc.id, actor)
                            deleteStockMovesByWarehouseFromCloud(cleanCluster, dupId)
                        }
                        doc.reference.delete().await()
                    }
                }
            }
            Unit
        }
    }

    suspend fun deleteStockMovesByWarehouseFromCloud(
        clusterCode: String,
        warehouseId: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cRef = clusterRef(clusterCode)
            val actor = currentActorId
            val snap = cRef.collection("stock_moves").whereEqualTo("warehouseId", warehouseId).get().await()
            for (doc in snap.documents) {
                val moveId = doc.getLong("id") ?: 0L
                val docId = doc.id
                val aliases = listOfNotNull(moveId.takeIf { it > 0L }?.toString(), docId)
                DeletedRecordsRegistry.registerDeletedWithAliases(
                    collection = "stock_moves",
                    aliases = aliases,
                    primaryEntityId = moveId.takeIf { it > 0L }?.toString() ?: docId,
                    primaryDocId = docId,
                    actor = actor
                )
                val tombDocId = safeId("stock_moves_$docId", docId)
                val tombData = mutableMapOf<String, Any>(
                    "collection" to "stock_moves",
                    "entityId" to (moveId.takeIf { it > 0L }?.toString() ?: docId),
                    "docId" to docId,
                    "aliases" to aliases,
                    "deletedAt" to FieldValue.serverTimestamp(),
                    "deletedBy" to actor
                )
                if (moveId > 0L) tombData["numericId"] = moveId
                runCatching {
                    cRef.collection("deleted_records").document(tombDocId).set(tombData, SetOptions.merge()).await()
                }
                runCatching { doc.reference.delete().await() }
            }
            Unit
        }
    }

    suspend fun deleteStockMovesByProductFromCloud(
        clusterCode: String,
        productId: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cRef = clusterRef(clusterCode)
            val actor = currentActorId
            val snap = cRef.collection("stock_moves").whereEqualTo("productId", productId).get().await()
            for (doc in snap.documents) {
                val moveId = doc.getLong("id") ?: 0L
                val docId = doc.id
                val aliases = listOfNotNull(moveId.takeIf { it > 0L }?.toString(), docId)
                DeletedRecordsRegistry.registerDeletedWithAliases(
                    collection = "stock_moves",
                    aliases = aliases,
                    primaryEntityId = moveId.takeIf { it > 0L }?.toString() ?: docId,
                    primaryDocId = docId,
                    actor = actor
                )
                val tombDocId = safeId("stock_moves_$docId", docId)
                val tombData = mutableMapOf<String, Any>(
                    "collection" to "stock_moves",
                    "entityId" to (moveId.takeIf { it > 0L }?.toString() ?: docId),
                    "docId" to docId,
                    "aliases" to aliases,
                    "deletedAt" to FieldValue.serverTimestamp(),
                    "deletedBy" to actor
                )
                if (moveId > 0L) tombData["numericId"] = moveId
                runCatching {
                    cRef.collection("deleted_records").document(tombDocId).set(tombData, SetOptions.merge()).await()
                }
                runCatching { doc.reference.delete().await() }
            }
            Unit
        }
    }

    suspend fun deleteInvoiceFromCloud(
        clusterCode: String,
        invoiceId: Long,
        invoiceNo: String,
        actor: String = currentActorId
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cRef = clusterRef(clusterCode)
            val cleanNo = invoiceNo.trim()
            val candidateDocIds = linkedSetOf<String>()
            if (cleanNo.isNotBlank()) {
                candidateDocIds.add(safeId(cleanNo, "INV-$invoiceId"))
                candidateDocIds.add(cleanNo)
            }
            candidateDocIds.add("INV-$invoiceId")
            candidateDocIds.add(invoiceId.toString())

            DeletedRecordsRegistry.registerDeleted("invoices", invoiceId, safeId(cleanNo, "INV-$invoiceId"), actor)
            if (cleanNo.isNotBlank()) {
                DeletedRecordsRegistry.registerDeleted("invoices", cleanNo, safeId(cleanNo, "INV-$invoiceId"), actor)
            }

            for (docId in candidateDocIds) {
                val tombDocId = safeId("invoices_$docId", "invoices_$invoiceId")
                runCatching {
                    cRef.collection("deleted_records").document(tombDocId).set(
                        mapOf(
                            "collection" to "invoices",
                            "entityId" to invoiceId.toString(),
                            "invoiceNo" to cleanNo,
                            "docId" to docId,
                            "deletedAt" to FieldValue.serverTimestamp(),
                            "deletedBy" to actor
                        ),
                        SetOptions.merge()
                    ).await()
                }
                runCatching { cRef.collection("invoices").document(docId).delete().await() }
            }

            // Also delete any invoice documents matching id or invoiceNo
            runCatching {
                val byIdSnap = cRef.collection("invoices").whereEqualTo("id", invoiceId).get().await()
                for (doc in byIdSnap.documents) {
                    DeletedRecordsRegistry.registerDeleted("invoices", doc.id, doc.id, actor)
                    runCatching { doc.reference.delete().await() }
                }
            }
            if (cleanNo.isNotBlank()) {
                runCatching {
                    val byNoSnap = cRef.collection("invoices").whereEqualTo("invoiceNo", cleanNo).get().await()
                    for (doc in byNoSnap.documents) {
                        DeletedRecordsRegistry.registerDeleted("invoices", doc.id, doc.id, actor)
                        runCatching { doc.reference.delete().await() }
                    }
                }
            }

            // Clean up linked invoice_payments & invoice_items on server
            for (linkedCol in listOf("invoice_payments", "invoice_items")) {
                runCatching {
                    val snap = cRef.collection(linkedCol).whereEqualTo("invoiceId", invoiceId).get().await()
                    for (doc in snap.documents) {
                        val lid = doc.getLong("id") ?: 0L
                        DeletedRecordsRegistry.registerDeleted(linkedCol, lid.takeIf { it > 0L }?.toString() ?: doc.id, doc.id, actor)
                        runCatching { doc.reference.delete().await() }
                    }
                }
            }
            Unit
        }
    }

    /**
     * Additional Validation Layer (`handleTransactionCommit`) executed before inserting any financial transaction
     * into Firestore. Verifies payload integrity, blocks tombstone replays, detects duplicates across devices on
     * Firestore server, detects tampering attempts against committed records, and logs violations to the error log
     * and immutable `audit_logs`.
     */
    suspend fun handleTransactionCommit(
        clusterCode: String,
        collectionName: String,
        docId: String,
        payload: Map<String, Any>,
        actor: String = currentActorId
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val col = collectionName.trim()
        val cleanDocId = safeId(docId, "DOC-${System.currentTimeMillis()}")
        val fullPath = "erp_clusters/$cleanCluster/$col/$cleanDocId"

        if (cleanCluster.equals(DEPRECATED_V16_CLUSTER, ignoreCase = true)) {
            val reason = "الكتابة في الكلاستر المهجور PMP-ERP-V16-CLUSTER مرفوضة أمنياً"
            recordTamperAttempt("HANDLE_TRANSACTION_COMMIT", fullPath, reason, actor)
            return@withContext Result.failure(SecurityException(reason))
        }

        // 1. Payload integrity & anti-tampering validation
        val integrity = validateTransactionCommitPayload(col, cleanDocId, payload)
        if (!integrity.valid) {
            recordTamperAttempt("HANDLE_TRANSACTION_COMMIT", fullPath, integrity.reason, actor)
            logValidationViolationToAudit(cleanCluster, actor, "TAMPER_ATTEMPT_BLOCKED", "رفض معاملة [$col/$cleanDocId]: ${integrity.reason}")
            return@withContext Result.failure(SecurityException(integrity.reason))
        }

        // 2. Strict tombstone replay protection (respects fresh syncUuid / unique cloudDocId to avoid recycled Room ID false positives)
        val refStr = (payload["reference"] ?: payload["referenceNo"] ?: "").toString().trim()
        val uuidStr = (payload["syncUuid"] ?: "").toString().trim()
        val numericId = (payload["id"] as? Number)?.toLong() ?: 0L
        val hasUniqueMovementIdentity = col == "movements" &&
            (uuidStr.isNotBlank() || !cleanDocId.equals("MOV-$numericId", ignoreCase = true))
        val tombstoned = DeletedRecordsRegistry.isDeleted(col, cleanDocId) ||
            (!hasUniqueMovementIdentity && numericId > 0L && DeletedRecordsRegistry.isDeleted(col, numericId.toString())) ||
            (refStr.isNotBlank() && DeletedRecordsRegistry.isDeleted(col, refStr)) ||
            (uuidStr.isNotBlank() && DeletedRecordsRegistry.isDeleted(col, uuidStr))
        if (tombstoned) {
            val reason = "محاولة إعادة إدخال معاملة مالية محذوفة مسبقاً ($cleanDocId) — تم حظر الاسترجاع."
            recordTamperAttempt("TOMBSTONE_REPLAY_BLOCKED", fullPath, reason, actor)
            logValidationViolationToAudit(cleanCluster, actor, "TOMBSTONE_REPLAY_BLOCKED", "محاولة إعادة حقن سجل محذوف [$col/$cleanDocId]")
            return@withContext Result.failure(SecurityException(reason))
        }

        // 3. Sliding-window fingerprint deduplication guard
        val now = System.currentTimeMillis()
        recentFinancialFingerprints.entries.removeIf { now - it.value > 12_000L }
        val fingerprint = buildFinancialCommitFingerprint(col, payload)
        val prevTs = recentFinancialFingerprints[fingerprint]
        if (prevTs != null && now - prevTs < 10_000L) {
            val reason = "معاملة مالية مكررة خلال نافذة الحماية ($fingerprint) — تم منع الإرسال المكرر إلى Firestore."
            recordDuplicateAttempt("HANDLE_TRANSACTION_COMMIT", fullPath, reason, actor)
            logValidationViolationToAudit(cleanCluster, actor, "DUPLICATE_TRANSACTION_BLOCKED", "منع تكرار فوري [$col/$cleanDocId] ($fingerprint)")
            return@withContext Result.failure(IllegalStateException(reason))
        }

        // 4. Server-backed Firestore conflict & tamper inspection when Firebase Auth is active
        val cRef = clusterRef(cleanCluster)
        val colRef = cRef.collection(col)
        val docRef = colRef.document(cleanDocId)
        val fbUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        if (fbUser == null) {
            val authMsg = "تم الحفظ محلياً فقط — يرجى تسجيل الدخول بحساب Google لإرسال المعاملة إلى السيرفر."
            recordStructuredError("COMMIT_${col.uppercase(java.util.Locale.US)}", fullPath, IllegalStateException(authMsg))
            return@withContext Result.failure(SecurityException(authMsg))
        }

        val existingById = linkedMapOf<String, Map<String, Any?>>()
        runCatching {
            val direct = runCatching { docRef.get(Source.SERVER).await() }
                .getOrElse { docRef.get().await() }
            if (direct.exists()) {
                direct.data?.let { existingById[direct.id] = it }
            }
        }
        val fieldProbes = buildList {
            if (uuidStr.isNotBlank()) add("syncUuid" to uuidStr)
            if (refStr.isNotBlank()) add("reference" to refStr)
            val invNo = (payload["invoiceNo"] ?: "").toString().trim()
            if (invNo.isNotBlank() && invNo != refStr) add("invoiceNo" to invNo)
        }
        for ((field, value) in fieldProbes) {
            runCatching {
                val q = colRef.whereEqualTo(field, value).limit(10)
                val snap = runCatching { q.get(Source.SERVER).await() }.getOrElse { q.get().await() }
                for (docSnap in snap.documents) {
                    docSnap.data?.let { existingById[docSnap.id] = it }
                }
            }
        }

        val conflict = checkExistingFinancialCommitConflict(
            collectionName = col,
            candidateDocId = cleanDocId,
            candidatePayload = payload,
            existingDocs = existingById.entries.map { it.key to it.value }
        )
        if (!conflict.valid) {
            val matchedId = conflict.matchedDocId.ifBlank { cleanDocId }
            val conflictPath = "erp_clusters/$cleanCluster/$col/$matchedId"
            if (conflict.violationType == TransactionViolationType.TAMPER) {
                recordTamperAttempt("HANDLE_TRANSACTION_COMMIT", conflictPath, conflict.reason, actor)
                logValidationViolationToAudit(cleanCluster, actor, "TAMPER_ATTEMPT_BLOCKED", "رصد تلاعب ضد سجل [$col/$matchedId]: ${conflict.reason}")
                return@withContext Result.failure(SecurityException(conflict.reason))
            } else {
                recordDuplicateAttempt("HANDLE_TRANSACTION_COMMIT", conflictPath, conflict.reason, actor)
                logValidationViolationToAudit(cleanCluster, actor, "DUPLICATE_TRANSACTION_BLOCKED", "منع تكرار سجل [$col/$matchedId]: ${conflict.reason}")
                return@withContext Result.failure(IllegalStateException(conflict.reason))
            }
        }

        // 5. Commit verified transaction atomically (with single-shot cluster membership repair on transient PERMISSION_DENIED)
        recentFinancialFingerprints[fingerprint] = now
        val enriched = HashMap<String, Any>(payload).apply {
            put("serverUpdatedBy", actor)
            put("serverUpdatedAt", FieldValue.serverTimestamp())
        }
        return@withContext try {
            try {
                docRef.set(enriched, SetOptions.merge()).await()
            } catch (firstErr: FirebaseFirestoreException) {
                if (firstErr.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    runCatching {
                        ensureOwnClusterMemberRegistration(cleanCluster, null, fbUser.uid, fbUser.email)
                    }
                    val hasActiveMembership = runCatching {
                        resolveClusterMemberRoleKey(cleanCluster, fbUser.uid) != null
                    }.getOrDefault(false)
                    if (hasActiveMembership) {
                        docRef.set(enriched, SetOptions.merge()).await()
                    } else {
                        throw firstErr
                    }
                } else {
                    throw firstErr
                }
            }
            RoomTransactionLogger.log(
                operation = "HANDLE_TRANSACTION_COMMIT",
                stage = TransactionStage.SYNC_ACKNOWLEDGED,
                status = TraceStatus.SUCCESS,
                entityType = col,
                recordId = cleanDocId,
                details = "تم التحقق من سلامة المعاملة وعدم تكرارها واعتمادها بالسيرفر #$cleanDocId"
            )
            Result.success(Unit)
        } catch (err: Exception) {
            recentFinancialFingerprints.remove(fingerprint)
            recordStructuredError("COMMIT_${col.uppercase(java.util.Locale.US)}", fullPath, err)
            Result.failure(err)
        }
    }

    private suspend fun logValidationViolationToAudit(
        clusterCode: String,
        actor: String,
        action: String,
        detail: String
    ) {
        if (runCatching { Firebase.auth.currentUser }.getOrNull() == null) return
        val now = System.currentTimeMillis()
        val auditDocId = "LOG-$now-${(100..999).random()}"
        runCatching {
            clusterRef(clusterCode).collection("audit_logs").document(auditDocId).set(
                mapOf(
                    "id" to now,
                    "username" to actor.ifBlank { "ANDROID_USER" },
                    "action" to action,
                    "detail" to CloudSyncErrorTracker.sanitizeCloudMessage(detail),
                    "timestamp" to now,
                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        }
    }

    /**
     * Instantly pushes a single financial MovementEntity through `handleTransactionCommit` to Cloud Firestore
     */
    suspend fun syncSingleMovementToCloud(
        clusterCode: String,
        m: MovementEntity,
        actor: String = currentActorId
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (DeletedRecordsRegistry.isMovementDeleted(m)) {
            return@withContext Result.success(Unit)
        }
        val docId = m.cloudDocId.trim().ifBlank { "MOV-${m.id}" }
        val data = mutableMapOf<String, Any>(
            "id" to m.id,
            "txDate" to m.txDate,
            "cashbox" to m.cashbox,
            "currency" to m.currency,
            "direction" to m.direction,
            "amount" to m.amount,
            "description" to m.description,
            "reference" to m.reference,
            "createdBy" to m.createdBy,
            "createdAt" to m.createdAt,
            "cloudDocId" to docId
        )
        if (m.syncUuid.isNotBlank()) data["syncUuid"] = m.syncUuid
        if (m.updatedAt > 0L) data["updatedAt"] = m.updatedAt
        handleTransactionCommit(
            clusterCode = clusterCode,
            collectionName = "movements",
            docId = docId,
            payload = data,
            actor = actor
        )
    }

    /**
     * Instantly pushes a single financial TransferEntity through `handleTransactionCommit` to Cloud Firestore
     */
    suspend fun syncSingleTransferToCloud(
        clusterCode: String,
        t: TransferEntity,
        actor: String = currentActorId
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val docId = safeId("TRF-${t.id}-${t.reference}", "TRF-${t.id}")
        val data = mapOf<String, Any>(
            "id" to t.id,
            "reference" to t.reference,
            "txDate" to t.txDate,
            "sourceCashbox" to t.sourceCashbox,
            "destinationCashbox" to t.destinationCashbox,
            "sourceCurrency" to t.sourceCurrency,
            "destinationCurrency" to t.destinationCurrency,
            "sourceAmount" to t.sourceAmount,
            "rate" to t.rate,
            "destinationAmount" to t.destinationAmount,
            "description" to t.description,
            "createdBy" to t.createdBy,
            "createdAt" to t.createdAt
        )
        handleTransactionCommit(
            clusterCode = clusterCode,
            collectionName = "transfers",
            docId = docId,
            payload = data,
            actor = actor
        )
    }

    /**
     * Wipes all operational records from the Cloud Firestore cluster collections,
     * clears local Room tables, and seeds fresh pristine baseline data both locally and on the server.
     */
    suspend fun wipeAllServerClusterDataAndResetFresh(
        clusterCode: String,
        dao: PmpDao,
        actor: String = "مدير النظام"
    ): Result<Int> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            runCatching {
                val cRef = clusterRef(clusterCode)
                var deletedDocs = 0

                val collectionsToWipe = listOf(
                    "movements",
                    "transfers",
                    "invoices",
                    "invoice_items",
                    "invoice_payments",
                    "daily_closings",
                    "stock_moves",
                    "production_batches",
                    "paint_batches",
                    "factory_moves",
                    "halfa_invoices",
                    "audit_logs",
                    "deleted_users"
                )

                for (coll in collectionsToWipe) {
                    try {
                        val snapshot = cRef.collection(coll).get().await()
                        if (!snapshot.isEmpty) {
                            snapshot.documents.chunked(400).forEach { chunk ->
                                val batch = firestore.batch()
                                chunk.forEach { doc ->
                                    batch.delete(doc.reference)
                                    deletedDocs++
                                }
                                batch.commit().await()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Notice during server collection wipe ($coll): ${e.message}")
                    }
                }

                // 1.5 Update Cluster Epoch to permanently invalidate any prior records on older clients
                val wipeTimestamp = System.currentTimeMillis()
                DeletedRecordsRegistry.setClusterEpoch(wipeTimestamp, actor)
                val epochDoc = mapOf(
                    "wipeTimestamp" to wipeTimestamp,
                    "wipedBy" to actor,
                    "reason" to "WIPE_AND_RESET_FRESH",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                runCatching {
                    cRef.collection("system_metadata").document("cluster_epoch").set(epochDoc, SetOptions.merge()).await()
                    firestore.collection("system_metadata").document("cluster_epoch").set(epochDoc, SetOptions.merge()).await()
                }

                // 2. Clear local operational tables in Room
                dao.clearAllMovements()
                dao.clearAllTransfers()
                dao.clearAllDailyClosings()
                dao.clearAllInvoices()
                dao.clearAllInvoiceItems()
                dao.clearAllInvoicePayments()
                dao.clearAllStockMoves()
                dao.clearAllProductionBatches()
                dao.clearAllFactoryMoves()
                dao.clearAllHalfaInvoices()
                dao.clearAllAuditLogs()

                // 3. Ensure baseline Cashboxes exist locally and on the server
                val existingBoxes = dao.getCashboxesDirect()
                if (existingBoxes.isEmpty()) {
                    val defaultCashboxes = listOf(
                        CashboxEntity(name = "خزنة السودان"),
                        CashboxEntity(name = "خزنة مصر")
                    )
                    for (cb in defaultCashboxes) {
                        dao.insertCashbox(cb)
                    }

                    val cbBatch = firestore.batch()
                    defaultCashboxes.forEachIndexed { idx, cb ->
                        val docId = safeId("BOX-${cb.name.trim().lowercase()}", "BOX-$idx")
                        cbBatch.set(
                            cRef.collection("cashboxes").document(docId),
                            mapOf(
                                "id" to (idx + 1L),
                                "name" to cb.name,
                                "serverUpdatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        )
                    }
                    cbBatch.commit().await()
                }

                // 4. Ensure baseline Warehouses exist
                val existingWarehouses = dao.getWarehousesDirect()
                if (existingWarehouses.isEmpty()) {
                    val defaultWarehouses = listOf(
                        WarehouseEntity(name = "مخزن عطبرة", currency = "SDG", linkedCashbox = "خزنة السودان"),
                        WarehouseEntity(name = "مخزن حلفا", currency = "SDG", linkedCashbox = "خزنة حلفا")
                    )
                    for (wh in defaultWarehouses) {
                        dao.insertWarehouse(wh)
                    }
                    val whBatch = firestore.batch()
                    defaultWarehouses.forEachIndexed { idx, wh ->
                        val docId = "WH-${idx + 1L}"
                        whBatch.set(
                            cRef.collection("warehouses").document(docId),
                            mapOf(
                                "id" to (idx + 1L),
                                "name" to wh.name,
                                "active" to true,
                                "serverUpdatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        )
                    }
                    whBatch.commit().await()
                }

                RoomTransactionLogger.clearTraces()
                RoomTransactionLogger.log(
                    operation = "SERVER_CLUSTER_WIPE_AND_RESET",
                    stage = TransactionStage.ROOM_TX_COMMIT,
                    status = TraceStatus.SUCCESS,
                    entityType = "erp_clusters",
                    details = "تم حذف كافة سجلات السيرفر ($deletedDocs مستنداً) والبدء من جديد ببيانات نظيفة"
                )

                recordDiagnostic(
                    action = "WIPE_SERVER_CLUSTER",
                    status = "SUCCESS",
                    count = deletedDocs,
                    message = "تم حذف كافة سجلات السيرفر ($deletedDocs مستنداً) بنجاح والبدء من جديد ببيانات نظيفة"
                )

                deletedDocs
            }
        }
    }

    // --- Phase 5: Server-Side Atomic Idempotency Protection System (Firestore runTransaction) ---

    suspend fun claimOrGetIdempotencyRequest(
        clusterCode: String = DEFAULT_CLUSTER,
        request: IdempotencyRequestEntity
    ): com.example.util.IdempotencyClaimOutcome = withContext(Dispatchers.IO) {
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val cleanKey = safeId(request.idempotencyKey, "IDEMP-${System.currentTimeMillis()}")
        val fbUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        if (fbUser == null) {
            // Allow offline/local Room-guarded recording when Firebase Google Sign-In session isn't active yet
            return@withContext com.example.util.IdempotencyClaimOutcome.Claimed(request.idempotencyKey)
        }

        val docRef = firestore.collection("erp_clusters")
            .document(cleanCluster)
            .collection("idempotency_requests")
            .document(cleanKey)

        suspend fun runClaimTx(): com.example.util.IdempotencyClaimOutcome =
            firestore.runTransaction { tx ->
                val snap = tx.get(docRef)
                if (snap.exists()) {
                    val existingStatus = snap.getString("requestStatus")?.trim()?.uppercase().orEmpty()
                    val existingResultRef = snap.getString("resultReference").orEmpty()
                    if (existingStatus == "COMPLETED") {
                        return@runTransaction com.example.util.IdempotencyClaimOutcome.DuplicateCompleted(
                            idempotencyKey = request.idempotencyKey,
                            existingResultReference = existingResultRef
                        )
                    }
                    if (existingStatus == "PROCESSING" || existingStatus == "PENDING_CONFIRMATION") {
                        return@runTransaction com.example.util.IdempotencyClaimOutcome.DuplicateInProgress(
                            idempotencyKey = request.idempotencyKey,
                            existingStatus = existingStatus
                        )
                    }
                }

                val existingOpType = if (snap.exists()) {
                    snap.getString("operationType")?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                val payload = hashMapOf<String, Any?>(
                    "idempotencyKey" to request.idempotencyKey,
                    "userId" to request.userId,
                    "operationType" to (existingOpType ?: request.operationType.ifBlank { "SAVE" }),
                    "documentReference" to request.documentReference,
                    "requestStatus" to com.example.util.IdempotencyRequestStatus.PROCESSING.code,
                    "createdAt" to (if (snap.exists()) (snap.getLong("createdAt") ?: request.createdAt) else request.createdAt),
                    "updatedAt" to System.currentTimeMillis(),
                    "completionTime" to null,
                    "resultReference" to "",
                    "errorDetails" to "",
                    "targetCollection" to request.targetCollection,
                    "payloadSummary" to request.payloadSummary,
                    "platform" to "ANDROID",
                    "guardKey" to request.guardKey,
                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                )
                tx.set(docRef, payload, SetOptions.merge())
                com.example.util.IdempotencyClaimOutcome.Claimed(request.idempotencyKey)
            }.await()

        try {
            try {
                runClaimTx()
            } catch (firstErr: FirebaseFirestoreException) {
                if (firstErr.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    runCatching {
                        ensureOwnClusterMemberRegistration(cleanCluster, null, fbUser.uid, fbUser.email)
                    }
                    val hasActiveMembership = runCatching {
                        resolveClusterMemberRoleKey(cleanCluster, fbUser.uid) != null
                    }.getOrDefault(false)
                    if (hasActiveMembership) {
                        runClaimTx()
                    } else {
                        throw firstErr
                    }
                } else {
                    throw firstErr
                }
            }
        } catch (fse: FirebaseFirestoreException) {
            recordStructuredError("CLAIM_IDEMPOTENCY", docRef.path, fse)
            if (fse.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ||
                fse.code == FirebaseFirestoreException.Code.UNAUTHENTICATED
            ) {
                com.example.util.IdempotencyClaimOutcome.Rejected(
                    idempotencyKey = request.idempotencyKey,
                    reason = "رفض السيرفر التحقق من صلاحيات الحساب (${fse.code.name}) — تأكد من تسجيل الدخول بحساب Google المعتمد وصلاحية العملية."
                )
            } else {
                com.example.util.IdempotencyClaimOutcome.NetworkUncertain(
                    idempotencyKey = request.idempotencyKey,
                    reason = fse.message ?: "تعذر تأكيد القفل على السيرفر"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "claimOrGetIdempotencyRequest notice ($cleanKey): ${e.message}")
            com.example.util.IdempotencyClaimOutcome.NetworkUncertain(
                idempotencyKey = request.idempotencyKey,
                reason = e.message ?: "تعذر تأكيد القفل على السيرفر"
            )
        }
    }

    suspend fun markIdempotencyCompleted(
        clusterCode: String = DEFAULT_CLUSTER,
        idempotencyKey: String,
        resultReference: String,
        operationType: String = "SAVE"
    ) = withContext(Dispatchers.IO) {
        if (runCatching { Firebase.auth.currentUser }.getOrNull() == null) return@withContext
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val cleanKey = safeId(idempotencyKey, idempotencyKey)
        val now = System.currentTimeMillis()
        val docRef = firestore.collection("erp_clusters")
            .document(cleanCluster)
            .collection("idempotency_requests")
            .document(cleanKey)
        runCatching {
            firestore.runTransaction { tx ->
                val snap = tx.get(docRef)
                if (snap.exists() && snap.getString("requestStatus") == "COMPLETED") {
                    return@runTransaction null
                }
                val resolvedOp = if (snap.exists()) {
                    snap.getString("operationType")?.takeIf { it.isNotBlank() } ?: operationType.ifBlank { "SAVE" }
                } else {
                    operationType.ifBlank { "SAVE" }
                }
                val payload = hashMapOf<String, Any>(
                    "idempotencyKey" to idempotencyKey,
                    "operationType" to resolvedOp,
                    "requestStatus" to com.example.util.IdempotencyRequestStatus.COMPLETED.code,
                    "updatedAt" to now,
                    "completionTime" to now,
                    "resultReference" to resultReference.take(256),
                    "errorDetails" to "",
                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                )
                if (!snap.exists()) {
                    payload["createdAt"] = now
                    payload["platform"] = "ANDROID"
                }
                tx.set(docRef, payload, SetOptions.merge())
                null
            }.await()
        }.onFailure { err ->
            Log.w(TAG, "markIdempotencyCompleted notice ($cleanKey): ${err.message}")
        }
    }

    suspend fun markIdempotencyStatus(
        clusterCode: String = DEFAULT_CLUSTER,
        idempotencyKey: String,
        status: com.example.util.IdempotencyRequestStatus,
        errorDetails: String,
        operationType: String = "SAVE"
    ) = withContext(Dispatchers.IO) {
        if (runCatching { Firebase.auth.currentUser }.getOrNull() == null) return@withContext
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val cleanKey = safeId(idempotencyKey, idempotencyKey)
        val now = System.currentTimeMillis()
        val docRef = firestore.collection("erp_clusters")
            .document(cleanCluster)
            .collection("idempotency_requests")
            .document(cleanKey)
        runCatching {
            firestore.runTransaction { tx ->
                val snap = tx.get(docRef)
                if (snap.exists() && snap.getString("requestStatus") == "COMPLETED") {
                    return@runTransaction null
                }
                val resolvedOp = if (snap.exists()) {
                    snap.getString("operationType")?.takeIf { it.isNotBlank() } ?: operationType.ifBlank { "SAVE" }
                } else {
                    operationType.ifBlank { "SAVE" }
                }
                val payload = hashMapOf<String, Any>(
                    "idempotencyKey" to idempotencyKey,
                    "operationType" to resolvedOp,
                    "requestStatus" to status.code,
                    "updatedAt" to now,
                    "errorDetails" to errorDetails.take(900),
                    "serverUpdatedAt" to FieldValue.serverTimestamp()
                )
                if (!snap.exists()) {
                    payload["createdAt"] = now
                    payload["platform"] = "ANDROID"
                }
                tx.set(docRef, payload, SetOptions.merge())
                null
            }.await()
        }.onFailure { err ->
            Log.w(TAG, "markIdempotencyStatus notice ($cleanKey): ${err.message}")
        }
    }

    suspend fun fetchIdempotencyRequest(
        clusterCode: String = DEFAULT_CLUSTER,
        idempotencyKey: String
    ): IdempotencyRequestEntity? = withContext(Dispatchers.IO) {
        if (runCatching { Firebase.auth.currentUser }.getOrNull() == null) return@withContext null
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val cleanKey = safeId(idempotencyKey, idempotencyKey)
        runCatching {
            val snap = firestore.collection("erp_clusters")
                .document(cleanCluster)
                .collection("idempotency_requests")
                .document(cleanKey)
                .get()
                .await()
            if (!snap.exists()) return@runCatching null
            IdempotencyRequestEntity(
                idempotencyKey = snap.safeString("idempotencyKey") ?: idempotencyKey,
                userId = snap.safeString("userId").orEmpty(),
                operationType = snap.safeString("operationType").orEmpty(),
                documentReference = snap.safeString("documentReference").orEmpty(),
                requestStatus = snap.safeString("requestStatus") ?: "PENDING_CONFIRMATION",
                createdAt = snap.safeLong("createdAt") ?: 0L,
                updatedAt = snap.safeLong("updatedAt") ?: 0L,
                completionTime = snap.safeLong("completionTime"),
                resultReference = snap.safeString("resultReference").orEmpty(),
                errorDetails = snap.safeString("errorDetails").orEmpty(),
                targetCollection = snap.safeString("targetCollection").orEmpty(),
                payloadSummary = snap.safeString("payloadSummary").orEmpty(),
                platform = snap.safeString("platform") ?: "ANDROID",
                guardKey = snap.safeString("guardKey").orEmpty()
            )
        }.getOrNull()
    }

    suspend fun checkClusterDocumentExists(
        clusterCode: String = DEFAULT_CLUSTER,
        targetCollection: String,
        documentReference: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (targetCollection.isBlank() || documentReference.isBlank()) return@withContext false
        if (runCatching { Firebase.auth.currentUser }.getOrNull() == null) return@withContext false
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val cleanDocId = safeId(documentReference, documentReference)
        runCatching {
            val docSnap = firestore.collection("erp_clusters")
                .document(cleanCluster)
                .collection(targetCollection)
                .document(cleanDocId)
                .get()
                .await()
            if (docSnap.exists()) return@runCatching true
            val querySnap = firestore.collection("erp_clusters")
                .document(cleanCluster)
                .collection(targetCollection)
                .whereEqualTo("idempotencyKey", documentReference)
                .limit(1)
                .get()
                .await()
            !querySnap.isEmpty
        }.getOrDefault(false)
    }

    fun asIdempotencyBackendAdapter(): com.example.util.IdempotencyBackendAdapter =
        object : com.example.util.IdempotencyBackendAdapter {
            override suspend fun claimOrGetRequest(
                clusterCode: String,
                request: IdempotencyRequestEntity
            ): com.example.util.IdempotencyClaimOutcome =
                claimOrGetIdempotencyRequest(clusterCode, request)

            override suspend fun markCompleted(
                clusterCode: String,
                idempotencyKey: String,
                resultReference: String,
                operationType: String
            ) {
                markIdempotencyCompleted(clusterCode, idempotencyKey, resultReference, operationType)
            }

            override suspend fun markPendingOrFailed(
                clusterCode: String,
                idempotencyKey: String,
                status: com.example.util.IdempotencyRequestStatus,
                errorDetails: String,
                operationType: String
            ) {
                markIdempotencyStatus(clusterCode, idempotencyKey, status, errorDetails, operationType)
            }

            override suspend fun fetchRequest(
                clusterCode: String,
                idempotencyKey: String
            ): IdempotencyRequestEntity? =
                fetchIdempotencyRequest(clusterCode, idempotencyKey)

            override suspend fun checkClusterDocumentExists(
                clusterCode: String,
                targetCollection: String,
                documentReference: String
            ): Boolean =
                checkClusterDocumentExists(clusterCode, targetCollection, documentReference)
        }
}


