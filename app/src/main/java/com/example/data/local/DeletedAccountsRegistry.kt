package com.example.data.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Distributed Tombstone Registry for user accounts.
 * Prevents deleted accounts from resurrecting when other devices, periodic syncs,
 * background workers, or real-time snapshot listeners pull cloud or local replicas.
 */
object DeletedAccountsRegistry {

    private const val TAG = "DeletedAccountsRegistry"
    private const val FILE_NAME = "pmp_deleted_accounts.json"

    data class TombstoneEntry(
        val userCode: String = "",
        val username: String = "",
        val email: String = "",
        val deletedAt: Long = System.currentTimeMillis(),
        val deletedBy: String = "مدير النظام"
    )

    // In-memory fast lookups
    private val deletedUserCodes = ConcurrentHashMap.newKeySet<String>()
    private val deletedUsernames = ConcurrentHashMap.newKeySet<String>()
    private val deletedEmails = ConcurrentHashMap.newKeySet<String>()
    private val allTombstones = ConcurrentHashMap<String, TombstoneEntry>()

    @Volatile
    private var isInitialized = false
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        loadPersistedTombstones(context.applicationContext)
        isInitialized = true
    }

    private fun normalize(value: String?): String =
        value?.trim()?.lowercase() ?: ""

    private fun entryKey(code: String, uname: String): String {
        val c = normalize(code)
        val u = normalize(uname)
        return if (c.isNotBlank()) c else u
    }

    /**
     * Registers an account as permanently deleted (Tombstone).
     */
    fun registerDeleted(
        userCode: String?,
        username: String?,
        email: String? = null,
        actor: String = "مدير النظام"
    ) {
        val cleanCode = normalize(userCode)
        val cleanUname = normalize(username)
        val cleanEmail = normalize(email)

        if (cleanCode.isBlank() && cleanUname.isBlank() && cleanEmail.isBlank()) return

        if (cleanCode.isNotBlank()) deletedUserCodes.add(cleanCode)
        if (cleanUname.isNotBlank()) deletedUsernames.add(cleanUname)
        if (cleanEmail.isNotBlank()) deletedEmails.add(cleanEmail)

        val key = entryKey(cleanCode, cleanUname)
        if (key.isNotBlank()) {
            allTombstones[key] = TombstoneEntry(
                userCode = userCode?.trim() ?: "",
                username = username?.trim() ?: "",
                email = email?.trim() ?: "",
                deletedAt = System.currentTimeMillis(),
                deletedBy = actor
            )
        }

        appContext?.let { persistTombstones(it) }
    }

    /**
     * Unregisters/removes an account from tombstones when created, reactivated, or authenticated.
     */
    fun removeTombstone(userCode: String?, username: String?, email: String? = null) {
        val cleanCode = normalize(userCode)
        val cleanUname = normalize(username)
        val cleanEmail = normalize(email)

        var changed = false
        if (cleanCode.isNotBlank() && deletedUserCodes.remove(cleanCode)) changed = true
        if (cleanUname.isNotBlank() && deletedUsernames.remove(cleanUname)) changed = true
        if (cleanEmail.isNotBlank() && deletedEmails.remove(cleanEmail)) changed = true

        val key = entryKey(cleanCode, cleanUname)
        if (key.isNotBlank() && allTombstones.remove(key) != null) {
            changed = true
        }

        if (changed) {
            appContext?.let { persistTombstones(it) }
        }
    }

    /**
     * Checks if a given account has been permanently deleted.
     */
    fun isDeleted(userCode: String?, username: String?, email: String? = null): Boolean {
        val cleanCode = normalize(userCode)
        if (cleanCode.isNotBlank() && deletedUserCodes.contains(cleanCode)) return true

        val cleanUname = normalize(username)
        if (cleanUname.isNotBlank() && deletedUsernames.contains(cleanUname)) return true

        val cleanEmail = normalize(email)
        if (cleanEmail.isNotBlank() && deletedEmails.contains(cleanEmail)) return true

        return false
    }

    fun getDeletedUserCodes(): Set<String> = deletedUserCodes.toSet()
    fun getDeletedUsernames(): Set<String> = deletedUsernames.toSet()

    fun getAllTombstoneRecords(): List<Map<String, Any>> {
        return allTombstones.values.map { entry ->
            mapOf(
                "userCode" to entry.userCode,
                "username" to entry.username,
                "email" to entry.email,
                "deleted" to true,
                "deletedAt" to entry.deletedAt,
                "deletedBy" to entry.deletedBy
            )
        }
    }

    /**
     * Merges remote tombstones received from Firestore across clusters.
     */
    fun mergeRemoteTombstones(remoteList: List<Map<String, Any>>) {
        var changed = false
        for (item in remoteList) {
            val code = item["userCode"] as? String ?: ""
            val uname = item["username"] as? String ?: ""
            val email = item["email"] as? String ?: ""
            val actor = item["deletedBy"] as? String ?: "مدير النظام"
            val ts = (item["deletedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()

            val cleanCode = normalize(code)
            val cleanUname = normalize(uname)
            val cleanEmail = normalize(email)

            if (cleanCode.isNotBlank() && deletedUserCodes.add(cleanCode)) changed = true
            if (cleanUname.isNotBlank() && deletedUsernames.add(cleanUname)) changed = true
            if (cleanEmail.isNotBlank() && deletedEmails.add(cleanEmail)) changed = true

            val key = entryKey(cleanCode, cleanUname)
            if (key.isNotBlank()) {
                allTombstones.putIfAbsent(
                    key,
                    TombstoneEntry(
                        userCode = code.trim(),
                        username = uname.trim(),
                        email = email.trim(),
                        deletedAt = ts,
                        deletedBy = actor
                    )
                )
            }
        }
        if (changed) {
            appContext?.let { persistTombstones(it) }
        }
    }

    private fun persistTombstones(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            val root = JSONObject()
            val array = JSONArray()
            for (entry in allTombstones.values) {
                array.put(JSONObject().apply {
                    put("userCode", entry.userCode)
                    put("username", entry.username)
                    put("email", entry.email)
                    put("deletedAt", entry.deletedAt)
                    put("deletedBy", entry.deletedBy)
                })
            }
            root.put("tombstones", array)
            file.writeText(root.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist tombstones: ${e.message}")
        }
    }

    private fun loadPersistedTombstones(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return
            val jsonText = file.readText()
            val root = JSONObject(jsonText)
            val array = root.optJSONArray("tombstones") ?: return
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val code = obj.optString("userCode", "")
                val uname = obj.optString("username", "")
                val email = obj.optString("email", "")
                val deletedAt = obj.optLong("deletedAt", System.currentTimeMillis())
                val deletedBy = obj.optString("deletedBy", "مدير النظام")

                val cleanCode = normalize(code)
                val cleanUname = normalize(uname)
                val cleanEmail = normalize(email)

                if (cleanCode.isNotBlank()) deletedUserCodes.add(cleanCode)
                if (cleanUname.isNotBlank()) deletedUsernames.add(cleanUname)
                if (cleanEmail.isNotBlank()) deletedEmails.add(cleanEmail)

                val key = entryKey(cleanCode, cleanUname)
                if (key.isNotBlank()) {
                    allTombstones[key] = TombstoneEntry(
                        userCode = code,
                        username = uname,
                        email = email,
                        deletedAt = deletedAt,
                        deletedBy = deletedBy
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted tombstones: ${e.message}")
        }
    }
}
