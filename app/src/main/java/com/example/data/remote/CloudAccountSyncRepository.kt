package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.local.UserAccountEntity
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(exception: Exception, operationType: OperationType, path: String?): String {
    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", exception.message ?: exception.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

data class LinkedAccountModel(
    @DocumentId
    val id: String = "",
    val userCode: String = "",
    val ownerId: String = "",
    val linkedUid: String = "",
    val clusterCode: String = "PMP-ERP-V16-CLUSTER",
    val fullName: String = "",
    val username: String = "",
    val role: String = "",
    val branch: String = "",
    val permissions: String = "",
    val serverSyncToken: String = "",
    val active: Boolean = true,
    @ServerTimestamp
    val createdAt: Date? = null,
    @ServerTimestamp
    val updatedAt: Date? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf<String, Any?>(
        "userCode" to userCode,
        "ownerId" to ownerId,
        "linkedUid" to linkedUid,
        "clusterCode" to clusterCode,
        "fullName" to fullName,
        "username" to username,
        "role" to role,
        "branch" to branch,
        "permissions" to permissions,
        "serverSyncToken" to serverSyncToken,
        "active" to active,
        "createdAt" to (createdAt ?: FieldValue.serverTimestamp()),
        "updatedAt" to (updatedAt ?: FieldValue.serverTimestamp())
    ).filterValues { it != null }.mapValues { it.value!! }

    fun toManagerUpdateMap(): Map<String, Any> = mapOf<String, Any?>(
        "fullName" to fullName,
        "username" to username,
        "role" to role,
        "branch" to branch,
        "permissions" to permissions,
        "linkedUid" to linkedUid,
        "serverSyncToken" to serverSyncToken,
        "active" to active,
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

class CloudAccountSyncRepository(
    private val firestore: FirebaseFirestore
) {
    companion object {
        const val COLLECTION_LINKED_ACCOUNTS = "linked_accounts"

        fun create(context: Context): CloudAccountSyncRepository {
            runCatching {
                if (com.google.firebase.FirebaseApp.getApps(context.applicationContext).isEmpty()) {
                    com.google.firebase.FirebaseApp.initializeApp(context.applicationContext)
                }
            }
            val databaseId = context.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)
            return CloudAccountSyncRepository(db)
        }
    }

    private val currentUid: String?
        get() = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()

    /**
     * Creates or overwrites a linked employee account document in Google Cloud Firestore.
     * Must be called while authenticated via Firebase Auth.
     */
    suspend fun createLinkedAccount(
        userCode: String,
        fullName: String,
        username: String,
        role: String,
        branch: String,
        permissions: String,
        serverSyncToken: String,
        linkedUid: String = "",
        clusterCode: String = "PMP-ERP-V16-CLUSTER",
        active: Boolean = true
    ): Result<String> {
        val uid = currentUid ?: return Result.failure(IllegalStateException("يرجى تسجيل الدخول السحابي عبر Google أولاً لتفعيل المزامنة"))
        val cleanCode = userCode.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
        val targetLinkedUid = linkedUid.trim().ifBlank { uid }
        val path = "$COLLECTION_LINKED_ACCOUNTS/$cleanCode"

        return try {
            val model = LinkedAccountModel(
                id = cleanCode,
                userCode = cleanCode,
                ownerId = uid,
                linkedUid = targetLinkedUid,
                clusterCode = clusterCode.trim().ifBlank { "PMP-ERP-V16-CLUSTER" },
                fullName = fullName.trim().take(120),
                username = username.trim().take(60),
                role = role.trim().take(80),
                branch = branch.trim().take(100),
                permissions = permissions.trim().ifBlank { "الخزن,المبيعات" }.take(300),
                serverSyncToken = serverSyncToken.trim().take(120),
                active = active
            )
            firestore.collection(COLLECTION_LINKED_ACCOUNTS)
                .document(cleanCode)
                .set(model.toCreateMap())
                .await()
            Result.success(cleanCode)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    /**
     * Manager Tier-1 update: modifies employee permissions, role, branch, linkedUid, or active status.
     */
    suspend fun updateEmployeePermissionsInCloud(
        userCode: String,
        fullName: String,
        username: String,
        role: String,
        branch: String,
        permissions: String,
        linkedUid: String,
        serverSyncToken: String,
        active: Boolean
    ): Result<Unit> {
        val uid = currentUid ?: return Result.failure(IllegalStateException("يرجى تسجيل الدخول السحابي أولاً"))
        val cleanCode = userCode.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
        val path = "$COLLECTION_LINKED_ACCOUNTS/$cleanCode"

        return try {
            val model = LinkedAccountModel(
                id = cleanCode,
                userCode = cleanCode,
                ownerId = uid,
                linkedUid = linkedUid.trim().ifBlank { uid },
                fullName = fullName.trim().take(120),
                username = username.trim().take(60),
                role = role.trim().take(80),
                branch = branch.trim().take(100),
                permissions = permissions.trim().ifBlank { "الخزن" }.take(300),
                serverSyncToken = serverSyncToken.trim().take(120),
                active = active
            )
            firestore.collection(COLLECTION_LINKED_ACCOUNTS)
                .document(cleanCode)
                .update(model.toManagerUpdateMap())
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    /**
     * Upserts a local Room UserAccountEntity to Google Cloud Firestore.
     */
    suspend fun syncLocalAccountToCloud(
        account: UserAccountEntity,
        targetLinkedUid: String = "",
        clusterCode: String = "PMP-ERP-V16-CLUSTER"
    ): Result<String> {
        val uid = currentUid ?: return Result.failure(IllegalStateException("يرجى تسجيل الدخول السحابي عبر Google أولاً"))
        val cleanCode = account.userCode.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
        val resolvedLinkedUid = targetLinkedUid.trim().ifBlank {
            if (account.remoteServerId.isNotBlank() && !account.remoteServerId.startsWith("SRV-NODE")) {
                account.remoteServerId
            } else {
                uid
            }
        }
        val docRef = firestore.collection(COLLECTION_LINKED_ACCOUNTS).document(cleanCode)
        return try {
            val snapshot = docRef.get().await()
            if (snapshot.exists()) {
                updateEmployeePermissionsInCloud(
                    userCode = cleanCode,
                    fullName = account.fullName,
                    username = account.username,
                    role = account.role,
                    branch = account.branch,
                    permissions = account.permissions,
                    linkedUid = resolvedLinkedUid,
                    serverSyncToken = account.serverSyncToken,
                    active = account.active
                ).map { cleanCode }
            } else {
                createLinkedAccount(
                    userCode = cleanCode,
                    fullName = account.fullName,
                    username = account.username,
                    role = account.role,
                    branch = account.branch,
                    permissions = account.permissions,
                    serverSyncToken = account.serverSyncToken,
                    linkedUid = resolvedLinkedUid,
                    clusterCode = clusterCode,
                    active = account.active
                )
            }
        } catch (e: Exception) {
            // If get() failed because doc doesn't exist yet under strict rules, attempt create
            createLinkedAccount(
                userCode = cleanCode,
                fullName = account.fullName,
                username = account.username,
                role = account.role,
                branch = account.branch,
                permissions = account.permissions,
                serverSyncToken = account.serverSyncToken,
                linkedUid = resolvedLinkedUid,
                clusterCode = clusterCode,
                active = account.active
            )
        }
    }

    /**
     * Employee Tier-2 update: acknowledges sync token without touching permissions or role.
     */
    suspend fun acknowledgeEmployeeSync(userCode: String, syncToken: String): Result<Unit> {
        val cleanCode = userCode.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
        val path = "$COLLECTION_LINKED_ACCOUNTS/$cleanCode"
        return try {
            val updates = mapOf<String, Any>(
                "serverSyncToken" to syncToken.trim().take(120),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            firestore.collection(COLLECTION_LINKED_ACCOUNTS)
                .document(cleanCode)
                .update(updates)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    /**
     * Fetches a specific linked account document by userCode.
     */
    suspend fun getLinkedAccountByCode(userCode: String): Result<LinkedAccountModel> {
        val cleanCode = userCode.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
        val path = "$COLLECTION_LINKED_ACCOUNTS/$cleanCode"
        return try {
            val snapshot = firestore.collection(COLLECTION_LINKED_ACCOUNTS)
                .document(cleanCode)
                .get()
                .await()
            val model = snapshot.toObject(LinkedAccountModel::class.java)
                ?: return Result.failure(NoSuchElementException("لم يتم العثور على الحساب السحابي ($cleanCode)"))
            Result.success(model)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    /**
     * Queries all employee accounts owned/created by the current manager.
     */
    suspend fun getManagedAccounts(): Result<List<LinkedAccountModel>> {
        val uid = currentUid.orEmpty()
        return try {
            val snapshot = firestore.collection(COLLECTION_LINKED_ACCOUNTS)
                .whereEqualTo("ownerId", uid)
                .get()
                .await()
            Result.success(snapshot.toObjects(LinkedAccountModel::class.java))
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, COLLECTION_LINKED_ACCOUNTS)
            Result.failure(e)
        }
    }

    /**
     * Queries all accounts linked to the current employee's Firebase UID.
     */
    suspend fun getEmployeeAssignedAccounts(): Result<List<LinkedAccountModel>> {
        val uid = currentUid.orEmpty()
        return try {
            val snapshot = firestore.collection(COLLECTION_LINKED_ACCOUNTS)
                .whereEqualTo("linkedUid", uid)
                .get()
                .await()
            Result.success(snapshot.toObjects(LinkedAccountModel::class.java))
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, COLLECTION_LINKED_ACCOUNTS)
            Result.failure(e)
        }
    }

    /**
     * Permanently deletes a linked account from /linked_accounts and registers tombstone in /deleted_accounts
     */
    suspend fun deleteLinkedAccount(userCode: String, username: String = ""): Result<Unit> {
        val cleanCode = userCode.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
        val cleanUname = username.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
        com.example.data.local.DeletedAccountsRegistry.registerDeleted(userCode, username)

        return try {
            if (cleanCode.isNotBlank()) {
                firestore.collection(COLLECTION_LINKED_ACCOUNTS).document(cleanCode).delete().await()
                firestore.collection("deleted_accounts").document(cleanCode).set(
                    mapOf(
                        "userCode" to userCode,
                        "username" to username,
                        "deleted" to true,
                        "deletedAt" to FieldValue.serverTimestamp(),
                        "deletedBy" to (currentUid ?: "مدير النظام")
                    )
                ).await()
            }
            if (cleanUname.isNotBlank() && cleanUname != cleanCode) {
                firestore.collection(COLLECTION_LINKED_ACCOUNTS).document(cleanUname).delete().await()
                firestore.collection("deleted_accounts").document(cleanUname).set(
                    mapOf(
                        "userCode" to userCode,
                        "username" to username,
                        "deleted" to true,
                        "deletedAt" to FieldValue.serverTimestamp(),
                        "deletedBy" to (currentUid ?: "مدير النظام")
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "$COLLECTION_LINKED_ACCOUNTS/$cleanCode")
            Result.failure(e)
        }
    }

    /**
     * Real-time listener for accounts owned by the authenticated manager.
     */
    fun observeManagedAccounts(): Flow<List<LinkedAccountModel>> = callbackFlow {
        val uid = currentUid
        if (uid.isNullOrBlank()) {
            val ex = FirebaseFirestoreException("User is unauthenticated", FirebaseFirestoreException.Code.PERMISSION_DENIED)
            close(ex)
            return@callbackFlow
        }
        val registration = firestore.collection(COLLECTION_LINKED_ACCOUNTS)
            .whereEqualTo("ownerId", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    handleFirestoreError(error, OperationType.LIST, COLLECTION_LINKED_ACCOUNTS)
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val rawList = snapshot.toObjects(LinkedAccountModel::class.java)
                    val filtered = rawList.filterNot { acc ->
                        val isDel = com.example.data.local.DeletedAccountsRegistry.isDeleted(acc.userCode, acc.username)
                        if (isDel && acc.userCode.isNotBlank()) {
                            val clean = acc.userCode.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
                            firestore.collection(COLLECTION_LINKED_ACCOUNTS).document(clean).delete()
                        }
                        isDel
                    }
                    trySend(filtered)
                }
            }
        awaitClose { registration.remove() }
    }

    /**
     * Real-time listener for accounts linked to the authenticated employee.
     */
    fun observeAssignedAccounts(): Flow<List<LinkedAccountModel>> = callbackFlow {
        val uid = currentUid
        if (uid.isNullOrBlank()) {
            val ex = FirebaseFirestoreException("User is unauthenticated", FirebaseFirestoreException.Code.PERMISSION_DENIED)
            close(ex)
            return@callbackFlow
        }
        val registration = firestore.collection(COLLECTION_LINKED_ACCOUNTS)
            .whereEqualTo("linkedUid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    handleFirestoreError(error, OperationType.LIST, COLLECTION_LINKED_ACCOUNTS)
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val rawList = snapshot.toObjects(LinkedAccountModel::class.java)
                    val filtered = rawList.filterNot { acc ->
                        val isDel = com.example.data.local.DeletedAccountsRegistry.isDeleted(acc.userCode, acc.username)
                        if (isDel && acc.userCode.isNotBlank()) {
                            val clean = acc.userCode.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
                            firestore.collection(COLLECTION_LINKED_ACCOUNTS).document(clean).delete()
                        }
                        isDel
                    }
                    trySend(filtered)
                }
            }
        awaitClose { registration.remove() }
    }
}
