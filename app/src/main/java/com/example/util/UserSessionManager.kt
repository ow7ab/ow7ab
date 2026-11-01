package com.example.util

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.local.PmpDatabase
import com.example.data.local.UserAccountEntity
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Monitors FirebaseAuth state changes, provides data synchronization triggers
 * to fetch user profile & permissions from Firestore immediately after authentication,
 * clears local caches & Room database session flags when a user signs out,
 * and maintains global loading state during auth transitions.
 */
class UserSessionManager(
    private val appContext: Context,
    private val database: PmpDatabase,
    private val firebaseAuth: FirebaseAuth = Firebase.auth,
    private val firestoreInstance: FirebaseFirestore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    sealed interface SessionEvent {
        data object SignedOut : SessionEvent
        data class SignedIn(val uid: String) : SessionEvent
        data class AccountSwitched(val oldUid: String, val newUid: String) : SessionEvent
        data class ProfileSynced(val user: UserAccountEntity) : SessionEvent
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val isMonitoring = AtomicBoolean(false)
    private var lastKnownUid: String? = firebaseAuth.currentUser?.uid
    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    // Global loading state overlay observable across navigation and screens
    val isAuthTransitioning = MutableStateFlow(false)
    val authTransitionMessage = MutableStateFlow("جارٍ تجهيز جلسة المستخدم ومزامنة الصلاحيات...")

    private val _sessionEventFlow = MutableStateFlow<SessionEvent?>(null)
    val sessionEventFlow: StateFlow<SessionEvent?> = _sessionEventFlow.asStateFlow()

    private val sessionListeners = CopyOnWriteArrayList<(SessionEvent) -> Unit>()

    fun addListener(listener: (SessionEvent) -> Unit) {
        sessionListeners.add(listener)
    }

    fun removeListener(listener: (SessionEvent) -> Unit) {
        sessionListeners.remove(listener)
    }

    private fun getFirestore(): FirebaseFirestore {
        if (firestoreInstance != null) return firestoreInstance
        val dbId = runCatching { appContext.getString(R.string.firestore_database_id) }.getOrNull()
        return if (!dbId.isNullOrBlank()) {
            FirebaseFirestore.getInstance(dbId)
        } else {
            FirebaseFirestore.getInstance()
        }
    }

    /**
     * Starts monitoring FirebaseAuth state changes.
     */
    fun startMonitoring() {
        if (!isMonitoring.compareAndSet(false, true)) {
            Log.d(TAG, "UserSessionManager is already monitoring")
            return
        }

        lastKnownUid = firebaseAuth.currentUser?.uid
        Log.i(TAG, "Starting FirebaseAuth session monitoring. Initial UID: $lastKnownUid")

        val listener = FirebaseAuth.AuthStateListener { auth ->
            val currentUid = auth.currentUser?.uid
            val prevUid = lastKnownUid

            if (prevUid != null && currentUid == null) {
                // User signed out from Firebase
                Log.w(TAG, "FirebaseAuth sign-out detected for user: $prevUid. Clearing local caches and session data.")
                lastKnownUid = null
                val event = SessionEvent.SignedOut
                _sessionEventFlow.value = event
                sessionListeners.forEach { runCatching { it(event) } }

                scope.launch {
                    isAuthTransitioning.value = true
                    authTransitionMessage.value = "جارٍ إنهاء الجلسة وتطهير البيانات المؤقتة..."
                    try {
                        clearLocalCachesAndSessionData(
                            clearBiometrics = false,
                            clearFileCache = true,
                            clearActiveRoomSession = true
                        )
                    } finally {
                        isAuthTransitioning.value = false
                    }
                }
            } else if (prevUid != null && currentUid != null && prevUid != currentUid) {
                // User switched accounts
                Log.w(TAG, "FirebaseAuth account switch detected: from $prevUid to $currentUid. Isolating session.")
                lastKnownUid = currentUid
                val event = SessionEvent.AccountSwitched(prevUid, currentUid)
                _sessionEventFlow.value = event
                sessionListeners.forEach { runCatching { it(event) } }

                scope.launch {
                    isAuthTransitioning.value = true
                    authTransitionMessage.value = "جارٍ التبديل وتأمين بيانات الحساب الجديد ومزامنة الصلاحيات..."
                    try {
                        clearLocalCachesAndSessionData(
                            clearBiometrics = false,
                            clearFileCache = true,
                            clearActiveRoomSession = true
                        )
                        fetchAndSyncUserProfileFromFirestore(currentUid)
                    } finally {
                        isAuthTransitioning.value = false
                    }
                }
            } else if (prevUid == null && currentUid != null) {
                // User signed in
                Log.i(TAG, "FirebaseAuth sign-in detected for user: $currentUid. Triggering profile sync.")
                lastKnownUid = currentUid
                val event = SessionEvent.SignedIn(currentUid)
                _sessionEventFlow.value = event
                sessionListeners.forEach { runCatching { it(event) } }

                scope.launch {
                    isAuthTransitioning.value = true
                    authTransitionMessage.value = "جارٍ استيراد الملف الشخصي والصلاحيات المعتمدة..."
                    try {
                        fetchAndSyncUserProfileFromFirestore(currentUid)
                    } finally {
                        isAuthTransitioning.value = false
                    }
                }
            }
        }

        authStateListener = listener
        runCatching {
            firebaseAuth.addAuthStateListener(listener)
        }.onFailure {
            Log.e(TAG, "Failed to register FirebaseAuth AuthStateListener", it)
        }
    }

    /**
     * Stops monitoring FirebaseAuth state changes.
     */
    fun stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            authStateListener?.let {
                runCatching { firebaseAuth.removeAuthStateListener(it) }
            }
            authStateListener = null
            Log.i(TAG, "Stopped FirebaseAuth session monitoring")
        }
    }

    /**
     * Fetches user profile, permissions, and active status from Firestore immediately after successful authentication.
     * Updates the local Room database and marks the user as the active session.
     */
    suspend fun fetchAndSyncUserProfileFromFirestore(uid: String): Result<UserAccountEntity?> = withContext(ioDispatcher) {
        if (uid.isBlank()) return@withContext Result.failure(IllegalArgumentException("UID cannot be blank"))

        Log.i(TAG, "Fetching profile and permissions from Firestore for UID: $uid")
        try {
            val firestore = getFirestore()
            val dao = database.pmpDao()

            // 1. Look in linked_accounts collection by linkedUid or ownerId
            val linkedSnapshot = runCatching {
                firestore.collection("linked_accounts")
                    .whereEqualTo("linkedUid", uid)
                    .get()
                    .await()
            }.getOrNull()

            val doc = linkedSnapshot?.documents?.firstOrNull() ?: runCatching {
                firestore.collection("linked_accounts")
                    .whereEqualTo("ownerId", uid)
                    .get()
                    .await()
                    .documents.firstOrNull()
            }.getOrNull()

            val matchedUser: UserAccountEntity? = if (doc != null && doc.exists()) {
                val userCode = doc.getString("userCode") ?: doc.id
                val fullName = doc.getString("fullName") ?: "موظف"
                val username = doc.getString("username") ?: userCode
                val role = doc.getString("role") ?: "كاشير"
                val branch = doc.getString("branch") ?: "فرع الخرطوم"
                val permissions = doc.getString("permissions") ?: "الخزن,المبيعات"
                val active = doc.getBoolean("active") ?: true
                val serverSyncToken = doc.getString("serverSyncToken") ?: ""

                val existing = dao.getUserByUsername(username)
                    ?: dao.getUserAccountsDirect().firstOrNull { it.userCode == userCode }

                val updatedUser = if (existing != null) {
                    val entity = existing.copy(
                        userCode = userCode,
                        fullName = fullName,
                        username = username,
                        role = role,
                        branch = branch,
                        permissions = permissions,
                        serverSyncToken = serverSyncToken.ifBlank { existing.serverSyncToken },
                        remoteServerId = uid,
                        syncStatus = "LINKED",
                        active = active,
                        isCurrentSession = true
                    )
                    dao.updateUserAccount(entity)
                    entity
                } else {
                    val entity = UserAccountEntity(
                        userCode = userCode,
                        fullName = fullName,
                        username = username,
                        role = role,
                        branch = branch,
                        permissions = permissions,
                        serverSyncToken = serverSyncToken.ifBlank { "SYNC-${System.currentTimeMillis()}" },
                        remoteServerId = uid,
                        syncStatus = "LINKED",
                        active = active,
                        isCurrentSession = true
                    )
                    val newId = dao.insertUserAccount(entity)
                    entity.copy(id = newId)
                }

                dao.setActiveSessionUser(updatedUser.id)
                Log.i(TAG, "Successfully synced permissions for (${updatedUser.fullName}): ${updatedUser.permissions}")
                val event = SessionEvent.ProfileSynced(updatedUser)
                _sessionEventFlow.value = event
                sessionListeners.forEach { runCatching { it(event) } }
                updatedUser
            } else {
                Log.w(TAG, "No linked_accounts document found in Firestore for UID: $uid")
                null
            }

            Result.success(matchedUser)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching profile and permissions from Firestore", e)
            AuthErrorHandler.handleAuthError(appContext, e, showToastNotification = false)
            Result.failure(e)
        }
    }

    /**
     * Public trigger for UI and ViewModels to initiate a full profile & permissions sync.
     */
    fun triggerPostAuthSync(
        uid: String = firebaseAuth.currentUser?.uid ?: "",
        onComplete: (Result<UserAccountEntity?>) -> Unit = {}
    ) {
        scope.launch {
            isAuthTransitioning.value = true
            authTransitionMessage.value = "جارٍ مزامنة الصلاحيات والملف الشخصي من الخادم..."
            val result = try {
                fetchAndSyncUserProfileFromFirestore(uid)
            } finally {
                isAuthTransitioning.value = false
            }
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    /**
     * Clears local application caches, temporary files, and resets Room database session state.
     */
    suspend fun clearLocalCachesAndSessionData(
        clearBiometrics: Boolean = false,
        clearFileCache: Boolean = true,
        clearActiveRoomSession: Boolean = true
    ) = withContext(ioDispatcher) {
        Log.i(TAG, "Starting purge of local session data and caches...")

        // 1. Reset Room database active session indicator & DeviceSessionStore
        if (clearActiveRoomSession) {
            runCatching {
                DeviceSessionStore.clearActiveSession(appContext)
                database.pmpDao().setActiveSessionUser(-1L)
                Log.d(TAG, "Device session store cleared and Room active session deactivated")
            }.onFailure {
                Log.e(TAG, "Error deactivating Room database session", it)
            }
        }

        // 2. Clear app disk cache directory (PDFs, invoice exports, temporary image files, network caches)
        if (clearFileCache) {
            runCatching {
                clearDirectoryContents(appContext.cacheDir)
                appContext.externalCacheDir?.let { clearDirectoryContents(it) }
                Log.d(TAG, "Disk cache directories purged")
            }.onFailure {
                Log.e(TAG, "Error purging cache directories", it)
            }
        }

        // 3. Clear transient biometric grace periods
        runCatching {
            BiometricAuthManager.clearRecentAuthentication(appContext)
            if (clearBiometrics) {
                BiometricPreferenceManager.clearBinding(appContext)
            }
            Log.d(TAG, "Biometric authentication cache reset")
        }.onFailure {
            Log.e(TAG, "Error resetting biometric cache", it)
        }

        Log.i(TAG, "Local session cleanup completed successfully")
    }

    /**
     * Asynchronously triggers session cleanup and notifies the optional callback when finished.
     */
    fun clearLocalCachesAndSessionDataAsync(
        clearBiometrics: Boolean = false,
        onComplete: () -> Unit = {}
    ) {
        scope.launch {
            isAuthTransitioning.value = true
            authTransitionMessage.value = "جارٍ تفريغ بيانات الجلسة المؤقتة..."
            try {
                clearLocalCachesAndSessionData(clearBiometrics = clearBiometrics)
            } finally {
                isAuthTransitioning.value = false
            }
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    private fun clearDirectoryContents(directory: File?): Boolean {
        if (directory == null || !directory.exists() || !directory.isDirectory) {
            return false
        }
        var success = true
        directory.listFiles()?.forEach { file ->
            val deleted = runCatching { file.deleteRecursively() }.getOrDefault(false)
            if (!deleted) success = false
        }
        return success
    }

    companion object {
        private const val TAG = "UserSessionManager"

        @Volatile
        private var instance: UserSessionManager? = null

        fun getInstance(
            context: Context,
            database: PmpDatabase = PmpDatabase.getDatabase(context.applicationContext),
            auth: FirebaseAuth = Firebase.auth
        ): UserSessionManager {
            return instance ?: synchronized(this) {
                instance ?: UserSessionManager(
                    appContext = context.applicationContext,
                    database = database,
                    firebaseAuth = auth
                ).also { instance = it }
            }
        }
    }
}
