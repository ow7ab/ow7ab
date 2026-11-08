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
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val message: String
)

class CloudErpSyncRepository(
    private val firestore: FirebaseFirestore,
    private val databaseId: String = "ai-studio-android-glassdro-d6052420-cbc0-47dd-8cdd-8caa5e6a7248"
) {
    companion object {
        private const val TAG = "CloudErpSyncRepo"
        const val DEFAULT_CLUSTER = "PMP-ERP-MAIN-CLUSTER"
        const val LEGACY_V16_CLUSTER = "PMP-ERP-V16-CLUSTER"

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
    }

    private val _diagnostics = kotlinx.coroutines.flow.MutableStateFlow<List<SyncDiagnosticRecord>>(emptyList())
    val diagnostics: kotlinx.coroutines.flow.StateFlow<List<SyncDiagnosticRecord>> = _diagnostics

    private val _lastSuccessfulSyncTime = kotlinx.coroutines.flow.MutableStateFlow<Long?>(System.currentTimeMillis() - 120_000L)
    val lastSuccessfulSyncTime: kotlinx.coroutines.flow.StateFlow<Long?> = _lastSuccessfulSyncTime

    private val _pendingErrors = kotlinx.coroutines.flow.MutableStateFlow<List<SyncDiagnosticRecord>>(emptyList())
    val pendingErrors: kotlinx.coroutines.flow.StateFlow<List<SyncDiagnosticRecord>> = _pendingErrors

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
    }

    fun clearAllPendingErrors() {
        _pendingErrors.value = emptyList()
    }

    fun recordDiagnostic(
        action: String,
        status: String,
        count: Int,
        message: String,
        deviceId: String = "DEV-NODE"
    ) {
        val now = System.currentTimeMillis()
        val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(now))
        val record = SyncDiagnosticRecord(
            timestamp = now,
            formattedTime = timeStr,
            deviceId = deviceId,
            action = action,
            status = status,
            entitiesCount = count,
            message = message
        )
        val current = _diagnostics.value.toMutableList()
        current.add(0, record)
        _diagnostics.value = if (current.size > 50) current.take(50) else current

        if (status == "SUCCESS") {
            _lastSuccessfulSyncTime.value = now
        } else if (status == "FAILED") {
            val currentErrors = _pendingErrors.value.toMutableList()
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
            val cRef = clusterRef(clusterCode)
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
            cRef.collection("user_accounts").document(docId).set(data, SetOptions.merge()).await()
            if (clusterCode != LEGACY_V16_CLUSTER) {
                runCatching {
                    clusterRef(LEGACY_V16_CLUSTER).collection("user_accounts").document(docId).set(data, SetOptions.merge()).await()
                }
            }
            if (user.syncStatus == "PENDING_MANAGER_APPROVAL") {
                cRef.collection("pending_requests").document(docId).set(data, SetOptions.merge()).await()
                if (clusterCode != LEGACY_V16_CLUSTER) {
                    runCatching {
                        clusterRef(LEGACY_V16_CLUSTER).collection("pending_requests").document(docId).set(data, SetOptions.merge()).await()
                    }
                }
            } else {
                runCatching { cRef.collection("pending_requests").document(docId).delete().await() }
                if (clusterCode != LEGACY_V16_CLUSTER) {
                    runCatching { clusterRef(LEGACY_V16_CLUSTER).collection("pending_requests").document(docId).delete().await() }
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
                DEFAULT_CLUSTER,
                LEGACY_V16_CLUSTER,
                "PMP-ERP-V15-CLUSTER",
                "PMP-ERP-V1-CLUSTER"
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
        deviceId: String = "DEV-NODE"
    ): Result<Int> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            internalPullAndMergeCloudUsers(clusterCode, dao, currentActiveUserId, deviceId)
        }
    }

    private suspend fun internalPullAndMergeCloudUsers(
        clusterCode: String,
        dao: PmpDao,
        currentActiveUserId: Long? = null,
        deviceId: String = "DEV-NODE"
    ): Result<Int> {
        return runCatching {
            // Clean local duplicate user accounts first
            runCatching { dao.removeDuplicateUserAccounts() }

            val primaryClusters = linkedSetOf(
                safeId(clusterCode, DEFAULT_CLUSTER),
                DEFAULT_CLUSTER
            )

            // 0. Pull tombstones with safe timeout
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

            // Collect user documents from primary clusters with timeout
            val allCloudDocs = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
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

            // Also check linked_accounts collection if available
            runCatching {
                val linkedSnap = kotlinx.coroutines.withTimeoutOrNull(1500L) {
                    firestore.collection("linked_accounts").get().await()
                }
                if (linkedSnap != null) {
                    allCloudDocs.addAll(linkedSnap.documents)
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

                // Propagate unified account to default primary cluster so all new/future versions find it immediately
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
                    clusterRef(DEFAULT_CLUSTER).collection("user_accounts").document(safeId(entity.userCode, "USR-${entity.id}")).set(userMap, SetOptions.merge())
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
    suspend fun findAndAuthenticateUserInCloud(
        identity: String,
        passwordOrPin: String,
        dao: PmpDao
    ): Result<UserAccountEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanIdentity = identity.trim().lowercase()
            val cleanSecret = passwordOrPin.trim()

            val clustersToQuery = listOf(DEFAULT_CLUSTER, LEGACY_V16_CLUSTER, "PMP-ERP-V15-CLUSTER")
            var matchedDoc: com.google.firebase.firestore.DocumentSnapshot? = null

            for (cluster in clustersToQuery) {
                runCatching {
                    val snap = clusterRef(cluster).collection("user_accounts").get().await()
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
                if (matchedDoc != null) break
            }

            if (matchedDoc == null) {
                // Also check linked_accounts if available
                runCatching {
                    val linkedSnap = firestore.collection("linked_accounts").get().await()
                    for (doc in linkedSnap.documents) {
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

            val doc = matchedDoc
            val storedPin = doc.getString("pinOrPassword") ?: "1234"
            val active = doc.getBoolean("active") ?: true
            val syncStatus = doc.getString("syncStatus") ?: "CLOUD_SYNCED"

            if (!active) {
                throw Exception("هذا الحساب موقوف أو غير مفعل على السيرفر، يرجى مراجعة مدير النظام.")
            }
            if (syncStatus == "PENDING_MANAGER_APPROVAL") {
                throw Exception("هذا الحساب بانتظار موافقة مدير النظام وتحديد الصلاحيات أولاً.")
            }
            if (storedPin != cleanSecret) {
                throw Exception("كلمة المرور غير صحيحة للحساب المسجل على السيرفر.")
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
                pinOrPassword = storedPin,
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
        deviceId: String = "DEV-NODE"
    ): Result<Int> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            runCatching {
                // Deduplicate local cashboxes, movements, and user accounts before pushing
                runCatching {
                    dao.removeDuplicateCashboxes()
                    dao.removeDuplicateMovements()
                    dao.removeDuplicateUserAccounts()
                }

                val actor = currentActorId
                var syncedCount = 0
                val cRef = clusterRef(clusterCode)

            // 0. User Accounts & Employee Permissions
            // Concurrency protection: non-manager employees only sync their own user profile,
            // strictly preventing employees from overwriting manager accounts or other colleagues' credentials
            val users = dao.getUserAccountsDirect()
            val usersToSync = (if (isManager) {
                users
            } else {
                users.filter { it.id == activeUserId || (activeUserId == null && it.isCurrentSession) }
            }).filterNot { DeletedAccountsRegistry.isDeleted(it.userCode, it.username, it.phoneOrEmail) }

            // Sync tombstones to cloud so all connected devices and clusters are aware of permanently deleted users
            runCatching {
                val tombstones = DeletedAccountsRegistry.getAllTombstoneRecords()
                for (t in tombstones) {
                    val tCode = t["userCode"] as? String ?: ""
                    val tUname = t["username"] as? String ?: ""
                    val tDocId = safeId(tCode.ifBlank { tUname }, "DEL")
                    if (tDocId.isNotBlank()) {
                        cRef.collection("deleted_users").document(tDocId).set(t, SetOptions.merge())
                    }
                }
            }

            // 0.5 مزامنة شواهد الحذف الشاملة لكافة السجلات (حركات، فواتير، عملاء، تحويلات) وتطهير المحلي
            runCatching {
                val epochSnap = cRef.collection("system_metadata").document("cluster_epoch").get().await()
                val epochTime = epochSnap.getLong("wipeTimestamp") ?: 0L
                val tombSnap = cRef.collection("deleted_records").get().await()
                val remoteTombstones = tombSnap.documents.mapNotNull { it.data }
                DeletedRecordsRegistry.mergeRemoteTombstones(remoteTombstones, epochTime)

                // رفع شواهد الحذف المحلية إلى السحابة ليعلم بها أي جهاز آخر
                val localTombs = DeletedRecordsRegistry.getAllTombstoneRecords()
                localTombs.chunked(300).forEach { chunk ->
                    val tBatch = firestore.batch()
                    chunk.forEach { t ->
                        val tDocId = safeId("${t["collection"]}_${t["docId"]}", "${t["docId"]}")
                        tBatch.set(cRef.collection("deleted_records").document(tDocId), t, SetOptions.merge())
                    }
                    tBatch.commit().await()
                }

                // تطهير السجلات المحلية التي تم حذفها سابقاً أو التي تسبق تاريخ التصفير الشامل
                dao.getMovementsDirect().forEach { m ->
                    if (DeletedRecordsRegistry.isDeleted("movements", m.id) ||
                        DeletedRecordsRegistry.isDeleted("movements", "MOV-${m.id}") ||
                        DeletedRecordsRegistry.isPreEpoch(m.createdAt)
                    ) {
                        dao.deleteMovementById(m.id)
                    }
                }
                dao.getCustomersDirect().forEach { c ->
                    if (DeletedRecordsRegistry.isDeleted("customers", c.id) ||
                        DeletedRecordsRegistry.isDeleted("customers", "CUST-${c.id}")
                    ) {
                        dao.deleteCustomer(c.id)
                    }
                }
                dao.getInvoicesDirect().forEach { inv ->
                    if (DeletedRecordsRegistry.isDeleted("invoices", inv.id) ||
                        DeletedRecordsRegistry.isDeleted("invoices", inv.invoiceNo) ||
                        DeletedRecordsRegistry.isDeleted("invoices", "INV-${inv.id}")
                    ) {
                        dao.deleteInvoiceById(inv.id)
                    }
                }
                dao.getTransfersDirect().forEach { t ->
                    if (DeletedRecordsRegistry.isDeleted("transfers", t.id) ||
                        DeletedRecordsRegistry.isDeleted("transfers", "TRF-${t.id}")
                    ) {
                        dao.deleteTransferById(t.id)
                    }
                }
                dao.getFactoryItemsDirect().forEach { fi ->
                    if (DeletedRecordsRegistry.isDeleted("factory_items", fi.id) ||
                        DeletedRecordsRegistry.isDeleted("factory_items", "FI-${fi.id}") ||
                        DeletedRecordsRegistry.isDeleted("factory_items", fi.name)
                    ) {
                        dao.deleteFactoryItemById(fi.id)
                    }
                }
                dao.getProductsDirect().forEach { p ->
                    if (DeletedRecordsRegistry.isDeleted("products", p.id) ||
                        DeletedRecordsRegistry.isDeleted("products", "PROD-${p.id}")
                    ) {
                        dao.deleteProduct(p.id)
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
                    }
                }
            }

            usersToSync.chunked(300).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { u ->
                    val docId = safeId(u.userCode, "USR-${u.id}")
                    val data = mapOf(
                        "id" to u.id,
                        "userCode" to docId,
                        "fullName" to u.fullName,
                        "username" to u.username,
                        "phoneOrEmail" to u.phoneOrEmail,
                        "pinOrPassword" to u.pinOrPassword,
                        "role" to u.role,
                        "branch" to u.branch,
                        "permissions" to u.permissions,
                        "serverSyncToken" to u.serverSyncToken,
                        "remoteServerId" to u.remoteServerId,
                        "syncStatus" to u.syncStatus,
                        "serverEndpoint" to u.serverEndpoint,
                        "active" to u.active,
                        "createdAt" to u.createdAt,
                        "serverUpdatedBy" to actor,
                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                    )
                    batch.set(cRef.collection("user_accounts").document(docId), data, SetOptions.merge())
                    syncedCount++
                }
                batch.commit().await()
            }

            // 1. Invoices & Items & Payments (Batched - excluding deleted)
            val invoices = dao.getInvoicesDirect().filterNot {
                DeletedRecordsRegistry.isDeleted("invoices", it.id) ||
                    DeletedRecordsRegistry.isDeleted("invoices", it.invoiceNo) ||
                    DeletedRecordsRegistry.isDeleted("invoices", "INV-${it.id}")
            }
            val allItems = dao.getInvoiceItemsDirect()
            val allPayments = dao.getInvoicePaymentsDirect()

            invoices.chunked(200).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { inv ->
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
                    val invPayments = allPayments.filter { it.invoiceId == inv.id && it.status == "active" }.map { p ->
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
                    val data = mapOf(
                        "id" to inv.id,
                        "invoiceNo" to inv.invoiceNo,
                        "customerId" to inv.customerId,
                        "customerName" to inv.customerName,
                        "invoiceDate" to inv.invoiceDate,
                        "currency" to inv.currency,
                        "subtotal" to inv.subtotal,
                        "discount" to inv.discount,
                        "total" to inv.total,
                        "notes" to inv.notes,
                        "status" to inv.status,
                        "cancelReason" to (inv.cancelReason ?: ""),
                        "createdBy" to inv.createdBy,
                        "items" to invItems,
                        "payments" to invPayments,
                        "serverUpdatedBy" to actor,
                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                    )
                    batch.set(cRef.collection("invoices").document(docId), data, SetOptions.merge())
                    syncedCount++
                }
                batch.commit().await()
            }

            // 2. Financial Movements (Batched for high speed and unique document ID - excluding deleted)
            val movements = dao.getMovementsDirect().filterNot {
                DeletedRecordsRegistry.isDeleted("movements", it.id) ||
                    DeletedRecordsRegistry.isDeleted("movements", "MOV-${it.id}") ||
                    DeletedRecordsRegistry.isPreEpoch(it.createdAt)
            }
            RoomTransactionLogger.log(
                operation = "SYNC_MOVEMENTS_DISPATCH",
                stage = TransactionStage.SYNC_DISPATCH,
                status = TraceStatus.IN_PROGRESS,
                entityType = "movements",
                details = "إرسال ${movements.size} حركة مالية إلى السحابة"
            )
            try {
                movements.chunked(300).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { m ->
                        val docId = "MOV-${m.id}"
                        val data = mapOf(
                            "id" to m.id,
                            "txDate" to m.txDate,
                            "cashbox" to m.cashbox,
                            "currency" to m.currency,
                            "direction" to m.direction,
                            "amount" to m.amount,
                            "description" to m.description,
                            "reference" to m.reference,
                            "createdBy" to m.createdBy,
                            "serverUpdatedBy" to actor,
                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                        )
                        batch.set(cRef.collection("movements").document(docId), data, SetOptions.merge())
                        syncedCount++
                    }
                    batch.commit().await()
                }
                RoomTransactionLogger.log(
                    operation = "SYNC_MOVEMENTS_ACK",
                    stage = TransactionStage.SYNC_ACKNOWLEDGED,
                    status = TraceStatus.SUCCESS,
                    entityType = "movements",
                    details = "تم تأكيد مزامنة ${movements.size} حركة مالية بنجاح على السيرفر"
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

            // 3. Transfers (Batched - excluding deleted)
            val transfers = dao.getTransfersDirect().filterNot {
                DeletedRecordsRegistry.isDeleted("transfers", it.id) ||
                    DeletedRecordsRegistry.isDeleted("transfers", "TRF-${it.id}")
            }
            transfers.chunked(300).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { t ->
                    val docId = safeId("TRF-${t.id}-${t.reference}", "TRF-${t.id}")
                    val data = mapOf(
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
                        "serverUpdatedBy" to actor,
                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                    )
                    batch.set(cRef.collection("transfers").document(docId), data, SetOptions.merge())
                    syncedCount++
                }
                batch.commit().await()
            }

            // 4. Customers (Batched - excluding deleted)
            val customers = dao.getCustomersDirect().filterNot {
                DeletedRecordsRegistry.isDeleted("customers", it.id) ||
                    DeletedRecordsRegistry.isDeleted("customers", "CUST-${it.id}")
            }
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
                    syncedCount++
                }
                batch.commit().await()
            }

            // 5. Products & Pricing (Batched - excluding deleted)
            val products = dao.getProductsDirect().filterNot { p ->
                DeletedRecordsRegistry.isDeleted("products", p.id) ||
                    DeletedRecordsRegistry.isDeleted("products", "PROD-${p.id}")
            }
            products.chunked(300).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { p ->
                    val docId = "PROD-${p.id}"
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
                    syncedCount++
                }
                batch.commit().await()
            }

            // 5b. Poster Price List (Separated SDG & EGP Currencies)
            val posterPrices = dao.getPosterPricesDirect()
            if (posterPrices.isNotEmpty()) {
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
                        syncedCount++
                    }
                    batch.commit().await()
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
            if (cashboxes.isNotEmpty()) {
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
                    syncedCount++
                }
                batch.commit().await()
            }

            val warehouses = dao.getWarehousesDirect()
            if (warehouses.isNotEmpty()) {
                val batch = firestore.batch()
                warehouses.forEach { wh ->
                    val docId = "WH-${wh.id}"
                    batch.set(
                        cRef.collection("warehouses").document(docId),
                        mapOf(
                            "id" to wh.id,
                            "name" to wh.name,
                            "active" to wh.active,
                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                    syncedCount++
                }
                batch.commit().await()
            }

            // 7. Factory Items & Raw Materials (Egypt Connected - Batched)
            val factoryItems = dao.getFactoryItemsDirect().filterNot { fi ->
                DeletedRecordsRegistry.isDeleted("factory_items", fi.id) ||
                    DeletedRecordsRegistry.isDeleted("factory_items", "FI-${fi.id}") ||
                    DeletedRecordsRegistry.isDeleted("factory_items", fi.name)
            }
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
                            "linkedToEgypt" to true,
                            "defaultCurrency" to "EGP",
                            "serverUpdatedBy" to actor,
                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                    syncedCount++
                }
                batch.commit().await()
            }

            // 8. Factory Movements (Batched)
            val factoryMoves = dao.getFactoryMovesDirect()
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
                    syncedCount++
                }
                batch.commit().await()
            }

            // 8b. Chemical Formulations & Paint Batch Formulas (Batched)
            val formulations = dao.getFormulationsDirect()
            formulations.chunked(300).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { form ->
                    val docId = if (form.formulaNo.isNotBlank()) {
                        form.formulaNo.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
                    } else {
                        "FORM-${form.id}"
                    }
                    val payload = mapOf(
                        "id" to form.id,
                        "formulaNo" to form.formulaNo,
                        "productName" to form.productName,
                        "paintType" to form.paintType,
                        "baseKind" to form.baseKind,
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
                        "ingredientsJson" to form.ingredientsJson,
                        "technicalAnalysis" to form.technicalAnalysis,
                        "notes" to form.notes,
                        "approvedForProduction" to form.approvedForProduction,
                        "createdBy" to form.createdBy,
                        "createdAt" to form.createdAt,
                        "serverUpdatedBy" to actor,
                        "serverUpdatedAt" to FieldValue.serverTimestamp()
                    )
                    batch.set(cRef.collection("lab_formulations").document(docId), payload, SetOptions.merge())
                    syncedCount++
                }
                batch.commit().await()
            }

            // 8c. Daily Production Batches (Batched)
            val batches = dao.getProductionBatchesDirect()
            batches.chunked(300).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { b ->
                    val docId = if (b.batchNumber.isNotBlank()) {
                        b.batchNumber.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
                    } else {
                        "BATCH-${b.id}"
                    }
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
                    syncedCount++
                }
                batch.commit().await()
            }

            // 9. Stock Moves (Batched)
            val stockMoves = dao.getStockMovesDirect()
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
                            "quantity" to sm.quantity,
                            "kind" to sm.kind,
                            "invoiceId" to (sm.invoiceId ?: 0L),
                            "note" to sm.note,
                            "createdBy" to sm.createdBy,
                            "createdAt" to sm.createdAt,
                            "serverUpdatedBy" to actor,
                            "serverUpdatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                    syncedCount++
                }
                batch.commit().await()
            }

            // 10. Halfa & Egypt Invoices (Handled in EGP - Batched)
            val halfaInvoices = dao.getHalfaInvoicesDirect()
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
                    syncedCount++
                }
                batch.commit().await()
            }

            // 11. Cluster Server Info & Cross-Network Egypt-Sudan Linking Metadata (Non-blocking)
            runCatching {
                cRef.collection("server_status").document("cluster_meta").set(
                    mapOf(
                        "clusterCode" to safeId(clusterCode, DEFAULT_CLUSTER),
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

            recordDiagnostic(
                action = "SYNC_ALL_TO_CLOUD",
                status = "SUCCESS",
                count = syncedCount,
                message = "تم حفظ ومزامنة $syncedCount سجلاً بنجاح على سيرفر Google Cloud دون المساس بالجلسات",
                deviceId = deviceId
            )
            Log.i(TAG, "Successfully synced $syncedCount total ERP entities to cloud cluster $clusterCode")
            syncedCount
        }.onFailure { err ->
            recordDiagnostic(
                action = "SYNC_ALL_TO_CLOUD",
                status = "FAILED",
                count = 0,
                message = "تعذر الحفظ على السيرفر: ${err.message}",
                deviceId = deviceId
            )
        }
    }
}

    /**
     * Restores all data from Google Cloud Firestore down to the local device database.
     * Prevents data loss when updating versions, re-installing, or switching devices.
     */
    suspend fun restoreCloudToLocal(
        clusterCode: String,
        dao: PmpDao,
        currentActiveUserId: Long? = null,
        deviceId: String = "DEV-NODE"
    ): Result<Int> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            runCatching {
                var restoredCount = 0
                val cRef = clusterRef(clusterCode)

                // 0. Restore User Accounts preserving active local session
                val mergedUsersCount = internalPullAndMergeCloudUsers(clusterCode, dao, currentActiveUserId, deviceId).getOrDefault(0)
                restoredCount += mergedUsersCount

                // 0.5 Fetch Tombstones and Cluster Epoch to prevent resurrecting deleted or pre-epoch records
                runCatching {
                    val epochSnap = cRef.collection("system_metadata").document("cluster_epoch").get().await()
                    val epochTime = epochSnap.getLong("wipeTimestamp") ?: 0L
                    val tombSnap = cRef.collection("deleted_records").get().await()
                    val remTombstones = tombSnap.documents.mapNotNull { it.data }
                    DeletedRecordsRegistry.mergeRemoteTombstones(remTombstones, epochTime)
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

            // 2. Fetch Movements from Server with deduplication & tombstone shield
            val localMovements = dao.getMovementsDirect()
            val movSnapshot = cRef.collection("movements").get().await()
            val restoredMovements = movSnapshot.documents.mapNotNull { doc ->
                val ref = doc.getString("reference") ?: doc.id
                val existingMov = localMovements.firstOrNull { it.reference.isNotBlank() && it.reference == ref }
                val movId = existingMov?.id ?: (doc.getLong("id") ?: 0L)

                val docCreatedAt = doc.getLong("createdAt") ?: 0L
                val isMovDeleted = DeletedRecordsRegistry.isDeleted("movements", movId) ||
                    DeletedRecordsRegistry.isDeleted("movements", "MOV-$movId") ||
                    DeletedRecordsRegistry.isDeleted("movements", doc.id) ||
                    DeletedRecordsRegistry.isPreEpoch(docCreatedAt)
                if (isMovDeleted) {
                    runCatching { doc.reference.delete() }
                    dao.deleteMovementById(movId)
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
                    createdBy = doc.getString("createdBy") ?: "المدير"
                )
            }
            val distinctMovements = restoredMovements.distinctBy {
                if (it.reference.isNotBlank()) it.reference.trim() else "ID-${it.id}"
            }
            if (distinctMovements.isNotEmpty()) {
                dao.upsertMovements(distinctMovements)
                restoredCount += distinctMovements.size
            }

            // 3. Fetch Transfers from Server with tombstone shield
            val trfSnapshot = cRef.collection("transfers").get().await()
            val restoredTransfers = trfSnapshot.documents.mapNotNull { doc ->
                val trfId = doc.getLong("id") ?: 0L
                val isTrfDeleted = DeletedRecordsRegistry.isDeleted("transfers", trfId) ||
                    DeletedRecordsRegistry.isDeleted("transfers", "TRF-$trfId") ||
                    DeletedRecordsRegistry.isDeleted("transfers", doc.id)
                if (isTrfDeleted) {
                    runCatching { doc.reference.delete() }
                    dao.deleteTransferById(trfId)
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
                    createdBy = doc.getString("createdBy") ?: "المدير"
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

            // 6. Fetch Warehouses and Cashboxes
            val localWh = dao.getWarehousesDirect()
            val whSnapshot = cRef.collection("warehouses").get().await()
            val restoredWarehouses = whSnapshot.documents.mapNotNull { doc ->
                val name = doc.getString("name") ?: return@mapNotNull null
                val existing = localWh.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }
                WarehouseEntity(
                    id = existing?.id ?: (doc.getLong("id") ?: 0L),
                    name = name,
                    active = doc.getBoolean("active") ?: true
                )
            }
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
                    category = doc.getString("category") ?: "raw"
                )
            }
            if (restoredFactoryItems.isNotEmpty()) {
                dao.upsertFactoryItems(restoredFactoryItems)
                restoredCount += restoredFactoryItems.size
            }

            // 8. Fetch Factory Moves
            val fmSnapshot = cRef.collection("factory_moves").get().await()
            val restoredFactoryMoves = fmSnapshot.documents.mapNotNull { doc ->
                val id = doc.getLong("id") ?: return@mapNotNull null
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
                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                )
            }
            if (restoredFactoryMoves.isNotEmpty()) {
                dao.upsertFactoryMoves(restoredFactoryMoves)
                restoredCount += restoredFactoryMoves.size
            }

            // 8b. Fetch Chemical Formulations from Cloud
            val formSnapshot = cRef.collection("lab_formulations").get().await()
            val restoredFormulations = formSnapshot.documents.mapNotNull { doc ->
                val id = doc.getLong("id") ?: return@mapNotNull null
                val fNo = doc.getString("formulaNo") ?: doc.id
                FormulationEntity(
                    id = id,
                    formulaNo = fNo,
                    productName = doc.getString("productName") ?: "تركيبة معملية",
                    paintType = doc.getString("paintType") ?: "مطفي معماري (Matt)",
                    baseKind = doc.getString("baseKind") ?: "أبيض",
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
                    ingredientsJson = doc.getString("ingredientsJson") ?: "[]",
                    technicalAnalysis = doc.getString("technicalAnalysis") ?: "",
                    notes = doc.getString("notes") ?: "",
                    approvedForProduction = doc.getBoolean("approvedForProduction") ?: false,
                    createdBy = doc.getString("createdBy") ?: "مهندس المختبر الكيميائي",
                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                )
            }
            if (restoredFormulations.isNotEmpty()) {
                dao.upsertFormulations(restoredFormulations)
                restoredCount += restoredFormulations.size
            }

            // 8c. Fetch Daily Production Batches from Cloud
            val batchSnapshot = cRef.collection("paint_batches").get().await()
            val restoredBatches = batchSnapshot.documents.mapNotNull { doc ->
                val id = doc.getLong("id") ?: return@mapNotNull null
                val bNo = doc.getString("batchNumber") ?: doc.id
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
                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                )
            }
            if (restoredBatches.isNotEmpty()) {
                dao.upsertProductionBatches(restoredBatches)
                restoredCount += restoredBatches.size
            }

            // 9. Fetch Stock Moves
            val smSnapshot = cRef.collection("stock_moves").get().await()
            val restoredStockMoves = smSnapshot.documents.mapNotNull { doc ->
                val id = doc.getLong("id") ?: return@mapNotNull null
                StockMoveEntity(
                    id = id,
                    warehouseId = doc.getLong("warehouseId") ?: 1L,
                    productId = doc.getLong("productId") ?: 1L,
                    quantity = doc.getDouble("quantity") ?: 1.0,
                    kind = doc.getString("kind") ?: "IN",
                    invoiceId = doc.getLong("invoiceId")?.takeIf { it > 0 },
                    note = doc.getString("note") ?: "",
                    createdBy = doc.getString("createdBy") ?: "المدير",
                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                )
            }
            if (restoredStockMoves.isNotEmpty()) {
                dao.upsertStockMoves(restoredStockMoves)
                restoredCount += restoredStockMoves.size
            }

            // 10. Fetch Halfa & Egypt Invoices
            val hiSnapshot = cRef.collection("halfa_invoices").get().await()
            val restoredHalfaInvoices = hiSnapshot.documents.mapNotNull { doc ->
                val id = doc.getLong("id") ?: return@mapNotNull null
                HalfaInvoiceEntity(
                    id = id,
                    invoiceNo = doc.getString("invoiceNo") ?: "HINV-$id",
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
                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                )
            }
            if (restoredHalfaInvoices.isNotEmpty()) {
                dao.upsertHalfaInvoices(restoredHalfaInvoices)
                restoredCount += restoredHalfaInvoices.size
            }

            // Clean any residual duplicates across cashboxes, transactions, and user accounts
            runCatching {
                dao.removeDuplicateCashboxes()
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

    /**
     * Performs a server diagnostics health check to verify connection stability and response latency.
     */
    suspend fun checkServerHealth(clusterCode: String): ServerHealthReport = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val projectId = firestore.app.options.projectId ?: "gen-lang-client-0220128746"
        val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
        val endpointUrl = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/erp_clusters/$cleanCluster"

        try {
            val cRef = clusterRef(cleanCluster)
            val invCountTask = cRef.collection("invoices").get()
            val movCountTask = cRef.collection("movements").get()
            val custCountTask = cRef.collection("customers").get()
            val userCountTask = cRef.collection("user_accounts").get()

            val invCount = invCountTask.await().size()
            val movCount = movCountTask.await().size()
            val custCount = custCountTask.await().size()
            val userCount = userCountTask.await().size()
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
                message = "سيرفر Google Cloud متصل ونشط • جاهز للمزامنة الفورية مع جميع المستخدمين ($latency ms)"
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            Log.e(TAG, "Server health check failed", e)
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
                message = "جاري إعادة الاتصال بسيرفر Google Cloud (${e.localizedMessage ?: "تحقق من الشبكة"})"
            )
        }
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
        val listener = clusterRef(clusterCode).collection("user_accounts")
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
        val cRef = clusterRef(clusterCode)

        // 0. الاستماع لشواهد الحذف في السحابة وتطهير أي سجل محذوف فوراً
        val tombListener = cRef.collection("deleted_records")
            .addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null && !snapshot.isEmpty) {
                    scope.launch(Dispatchers.IO + syncExceptionHandler) {
                        try {
                            val tombstones = snapshot.documents.mapNotNull { it.data }
                            DeletedRecordsRegistry.mergeRemoteTombstones(tombstones)
                            for (doc in snapshot.documents) {
                                val col = doc.safeString("collection") ?: ""
                                val docId = doc.safeString("docId") ?: doc.id
                                val numId = (doc.get("entityId") as? Number)?.toLong() ?: docId.filter { it.isDigit() }.toLongOrNull()
                                when (col) {
                                    "movements" -> if (numId != null) dao.deleteMovementById(numId)
                                    "customers" -> if (numId != null) dao.deleteCustomer(numId)
                                    "invoices" -> if (numId != null) dao.deleteInvoiceById(numId)
                                    "transfers" -> if (numId != null) dao.deleteTransferById(numId)
                                    "factory_items" -> if (numId != null) dao.deleteFactoryItemById(numId)
                                    "products" -> if (numId != null) dao.deleteProduct(numId)
                                    "warehouses" -> if (numId != null) dao.deleteWarehouse(numId)
                                    "cashboxes" -> {
                                        val entityIdStr = doc.safeString("entityId")?.trim().orEmpty()
                                        if (numId != null && numId > 0L) {
                                            dao.deleteCashbox(numId)
                                        }
                                        val currentBoxes = dao.getCashboxesDirect()
                                        currentBoxes.forEach { cb ->
                                            val cbDocId = DeletedRecordsRegistry.cashboxDocId(cb.name, cb.id)
                                            if ((entityIdStr.isNotBlank() && cb.name.trim().equals(entityIdStr, ignoreCase = true)) ||
                                                cbDocId.equals(docId, ignoreCase = true) ||
                                                DeletedRecordsRegistry.isDeleted("cashboxes", cb.name) ||
                                                DeletedRecordsRegistry.isDeleted("cashboxes", cb.id)
                                            ) {
                                                dao.deleteCashbox(cb.id)
                                            }
                                        }
                                    }
                                }
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
                                    if (movId != null) {
                                        dao.deleteMovementById(movId)
                                    }
                                }
                            }
                            if (!snapshot.isEmpty) {
                                for (doc in snapshot.documents) {
                                    val movId = doc.safeLong("id") ?: continue
                                    val docCreatedAt = doc.safeLong("createdAt") ?: 0L
                                    val isDel = DeletedRecordsRegistry.isDeleted("movements", movId) ||
                                        DeletedRecordsRegistry.isDeleted("movements", "MOV-$movId") ||
                                        DeletedRecordsRegistry.isDeleted("movements", doc.id) ||
                                        DeletedRecordsRegistry.isPreEpoch(docCreatedAt)
                                    if (isDel) {
                                        runCatching { doc.reference.delete() }
                                        dao.deleteMovementById(movId)
                                    }
                                }
                                val list = snapshot.documents.mapNotNull { doc ->
                                    val id = doc.safeLong("id") ?: return@mapNotNull null
                                    val docCreatedAt = doc.safeLong("createdAt") ?: 0L
                                    if (DeletedRecordsRegistry.isDeleted("movements", id) ||
                                        DeletedRecordsRegistry.isDeleted("movements", "MOV-$id") ||
                                        DeletedRecordsRegistry.isDeleted("movements", doc.id) ||
                                        DeletedRecordsRegistry.isPreEpoch(docCreatedAt)
                                    ) return@mapNotNull null
                                    MovementEntity(
                                        id = id,
                                        txDate = doc.safeString("txDate") ?: "2026-10-26",
                                        cashbox = doc.safeString("cashbox") ?: "خزنة السودان",
                                        currency = doc.safeString("currency") ?: "SDG",
                                        direction = doc.safeString("direction") ?: "وارد",
                                        amount = doc.safeDouble("amount") ?: 0.0,
                                        description = doc.safeString("description") ?: "",
                                        reference = doc.safeString("reference") ?: doc.id,
                                        createdBy = doc.safeString("createdBy") ?: "المدير"
                                    )
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
                                        minimumStock = doc.safeDouble("minimumStock") ?: 10.0,
                                        paintColor = doc.safeString("paintColor") ?: "أبيض",
                                        packageWeight = doc.safeString("packageWeight") ?: "900",
                                        active = doc.safeBoolean("active") ?: true
                                    )
                                }
                                if (list.isNotEmpty()) dao.upsertProducts(list)
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error syncing live products", e)
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

        awaitClose {
            tombListener.remove()
            invListener.remove()
            movListener.remove()
            custListener.remove()
            prodListener.remove()
            fiListener.remove()
            cbListener.remove()
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
                "formulaNo" to form.formulaNo,
                "productName" to form.productName,
                "paintType" to form.paintType,
                "baseKind" to form.baseKind,
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
                "ingredientsJson" to form.ingredientsJson,
                "technicalAnalysis" to form.technicalAnalysis,
                "notes" to form.notes,
                "approvedForProduction" to form.approvedForProduction,
                "createdBy" to form.createdBy,
                "createdAt" to form.createdAt,
                "serverUpdatedAt" to FieldValue.serverTimestamp()
            )
            cRef.collection("lab_formulations").document(docId).set(payload, SetOptions.merge()).await()
            runCatching {
                firestore.collection("lab_formulations").document(docId).set(payload, SetOptions.merge()).await()
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
            val cRef = clusterRef(clusterCode)
            val docId = if (batchNumber.isNotBlank()) {
                batchNumber.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
            } else {
                "BATCH-$id"
            }
            runCatching { cRef.collection("paint_batches").document(docId).delete().await() }
            runCatching { firestore.collection("paint_batches").document(docId).delete().await() }
            recordDiagnostic(
                action = "DELETE_BATCH_CLOUD",
                status = "SUCCESS",
                count = 1,
                message = "تم حذف تشغيلة الإنتاج ($docId) بنجاح من السيرفر"
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

            // 2. كتابة شاهد الحذف الدائم (Tombstone) على السحابة لمنع إعادة رفعه عند مزامنة النسخ القديمة
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
                cRef.collection("deleted_records").document(tombstoneDocId).set(tombstoneData, SetOptions.merge())
                firestore.collection("deleted_records").document(tombstoneDocId).set(tombstoneData, SetOptions.merge())
            }

            // 3. حذف المستند الفعلي من السيرفر السحابي
            cRef.collection(collectionName).document(cleanDocId).delete().await()
            if (cleanCluster != DEFAULT_CLUSTER) {
                runCatching {
                    clusterRef(DEFAULT_CLUSTER).collection(collectionName).document(cleanDocId).delete().await()
                    clusterRef(DEFAULT_CLUSTER).collection("deleted_records").document(tombstoneDocId).set(tombstoneData, SetOptions.merge())
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
            val cleanCluster = safeId(clusterCode, DEFAULT_CLUSTER)
            val cRef = clusterRef(cleanCluster)
            val serverSnap = cRef.collection("user_accounts").limit(1).get(com.google.firebase.firestore.Source.SERVER).await()
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
     * ensuring zeroed cashbox records do not bounce back upon sync.
     */
    suspend fun clearCashboxMovementsFromCloud(clusterCode: String, cashboxName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cRef = clusterRef(clusterCode)
            val snaps = cRef.collection("movements")
                .whereEqualTo("cashbox", cashboxName)
                .get()
                .await()
            if (!snaps.isEmpty) {
                snaps.documents.chunked(300).forEach { chunk ->
                    val batch = firestore.batch()
                    chunk.forEach { doc ->
                        batch.delete(doc.reference)
                    }
                    batch.commit().await()
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
     * Instantly pushes a single financial MovementEntity to Cloud Firestore in < 100ms
     */
    suspend fun syncSingleMovementToCloud(
        clusterCode: String,
        m: MovementEntity,
        actor: String = currentActorId
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cRef = clusterRef(clusterCode)
            val docId = "MOV-${m.id}"
            val data = mapOf(
                "id" to m.id,
                "txDate" to m.txDate,
                "cashbox" to m.cashbox,
                "currency" to m.currency,
                "direction" to m.direction,
                "amount" to m.amount,
                "description" to m.description,
                "reference" to m.reference,
                "createdBy" to m.createdBy,
                "serverUpdatedBy" to actor,
                "serverUpdatedAt" to FieldValue.serverTimestamp()
            )
            cRef.collection("movements").document(docId).set(data, SetOptions.merge()).await()
            RoomTransactionLogger.log(
                operation = "SYNC_SINGLE_MOVEMENT",
                stage = TransactionStage.SYNC_ACKNOWLEDGED,
                status = TraceStatus.SUCCESS,
                entityType = "movements",
                recordId = docId,
                details = "تم تأكيد مزامنة الحركة #$docId بالسيرفر فورياً"
            )
            Unit
        }
    }

    /**
     * Instantly pushes a single financial TransferEntity to Cloud Firestore in < 100ms
     */
    suspend fun syncSingleTransferToCloud(
        clusterCode: String,
        t: TransferEntity,
        actor: String = currentActorId
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cRef = clusterRef(clusterCode)
            val docId = safeId("TRF-${t.id}-${t.reference}", "TRF-${t.id}")
            val data = mapOf(
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
                "serverUpdatedBy" to actor,
                "serverUpdatedAt" to FieldValue.serverTimestamp()
            )
            cRef.collection("transfers").document(docId).set(data, SetOptions.merge()).await()
            RoomTransactionLogger.log(
                operation = "SYNC_SINGLE_TRANSFER",
                stage = TransactionStage.SYNC_ACKNOWLEDGED,
                status = TraceStatus.SUCCESS,
                entityType = "transfers",
                recordId = docId,
                details = "تم تأكيد مزامنة التحويل #$docId بالسيرفر فورياً"
            )
            Unit
        }
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
}

