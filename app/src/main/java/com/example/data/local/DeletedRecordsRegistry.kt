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
 * سجل شواهد الحذف الشامل (Distributed ERP Tombstone Registry)
 * يمنع عودة السجلات المحذوفة (حركات مالية، عملاء، فواتير، تحويلات، أصناف)
 * نهائياً عند قيام أي هاتف آخر أو نسخة قديمة بالضغط على "مزامنة".
 */
object DeletedRecordsRegistry {

    private const val TAG = "DeletedRecordsRegistry"
    private const val FILE_NAME = "pmp_deleted_erp_records.json"

    private fun logInfo(msg: String) {
        runCatching { Log.i(TAG, msg) }
    }

    private fun logWarn(msg: String) {
        runCatching { Log.w(TAG, msg) }
    }

    data class RecordTombstone(
        val collection: String,
        val entityId: String,
        val docId: String,
        val deletedAt: Long = System.currentTimeMillis(),
        val deletedBy: String = "مدير النظام"
    )

    // Fast in-memory sets for O(1) checks
    private val deletedKeys = ConcurrentHashMap.newKeySet<String>()
    private val allTombstones = ConcurrentHashMap<String, RecordTombstone>()

    // Epoch timestamp: Any operational record created prior to this timestamp is considered purged/wiped
    @Volatile
    private var clusterEpochTimestamp: Long = 0L

    @Volatile
    private var isInitialized = false
    private var appContext: Context? = null

    fun init(context: Context) {
        if (isInitialized && appContext != null) return
        appContext = context.applicationContext
        loadPersisted(context.applicationContext)
        isInitialized = true
    }

    private fun normalize(v: String?): String = v?.trim()?.lowercase() ?: ""

    private fun makeKey(collection: String, idOrDoc: String): String {
        val cleanCol = normalize(collection)
        val cleanId = normalize(idOrDoc).replace(Regex("[^\\p{L}\\p{N}_\\-]"), "-").trim('-')
        return "${cleanCol}_$cleanId"
    }

    /**
     * Generates a deterministic, ASCII-safe Firestore document ID for a cashbox name
     * (including Arabic names like "خزنة حلفا") so all devices address the exact same document.
     */
    fun cashboxDocId(name: String, fallbackId: Long = 0L): String {
        val cleanName = normalize(name)
        if (cleanName.isBlank()) return "BOX-$fallbackId"
        val asciiPart = cleanName.replace(Regex("[^a-zA-Z0-9_\\-]"), "-").trim('-')
        val hashHex = cleanName.hashCode().toUInt().toString(16)
        return if (asciiPart.isNotBlank()) {
            "BOX-${asciiPart.take(32)}-$hashHex"
        } else {
            "BOX-H$hashHex"
        }
    }

    /**
     * تسجيل مستند كمحذوف نهائياً لمنع استرجاعه عبر مزامنة النسخ القديمة
     */
    fun registerDeleted(
        collection: String,
        entityId: String,
        docId: String = "",
        actor: String = "مدير النظام"
    ) {
        if (collection.isBlank() || (entityId.isBlank() && docId.isBlank())) return

        val primaryId = entityId.ifBlank { docId }
        val key1 = makeKey(collection, primaryId)
        deletedKeys.add(key1)

        val resolvedDocId = docId.ifBlank {
            when (normalize(collection)) {
                "movements" -> if (primaryId.startsWith("MOV-", true)) primaryId else "MOV-$primaryId"
                "customers" -> if (primaryId.startsWith("CUST-", true)) primaryId else "CUST-$primaryId"
                "invoices" -> if (primaryId.startsWith("INV-", true)) primaryId else "INV-$primaryId"
                "transfers" -> if (primaryId.startsWith("TRF-", true)) primaryId else "TRF-$primaryId"
                "products" -> if (primaryId.startsWith("PROD-", true)) primaryId else "PROD-$primaryId"
                "cashboxes" -> if (primaryId.startsWith("BOX-", true)) primaryId else cashboxDocId(primaryId)
                else -> primaryId
            }
        }

        val key2 = makeKey(collection, resolvedDocId)
        deletedKeys.add(key2)

        if (normalize(collection) == "cashboxes" && !primaryId.startsWith("BOX-", true)) {
            deletedKeys.add(makeKey(collection, cashboxDocId(primaryId)))
        }

        val tombstone = RecordTombstone(
            collection = collection.trim(),
            entityId = primaryId,
            docId = resolvedDocId,
            deletedAt = System.currentTimeMillis(),
            deletedBy = actor
        )
        allTombstones[key1] = tombstone
        allTombstones[key2] = tombstone

        appContext?.let { persist(it) }
        logInfo("Registered tombstone for $collection: entityId=$primaryId, docId=$resolvedDocId by $actor")
    }

    fun registerDeleted(
        collection: String,
        numericId: Long,
        docId: String = "",
        actor: String = "مدير النظام"
    ) {
        registerDeleted(collection, numericId.toString(), docId, actor)
    }

    /**
     * التحقق مما إذا كان السجل محذوفاً مسبقاً
     */
    fun isDeleted(collection: String, idOrDoc: String): Boolean {
        if (idOrDoc.isBlank()) return false
        val key = makeKey(collection, idOrDoc)
        if (deletedKeys.contains(key)) return true

        if (normalize(collection) == "cashboxes" && !idOrDoc.startsWith("BOX-", true)) {
            if (deletedKeys.contains(makeKey(collection, cashboxDocId(idOrDoc)))) {
                return true
            }
        }

        // Also check prefixed variant if it was stored without prefix
        val altId = when {
            idOrDoc.startsWith("MOV-", true) -> idOrDoc.substring(4)
            idOrDoc.startsWith("CUST-", true) -> idOrDoc.substring(5)
            idOrDoc.startsWith("INV-", true) -> idOrDoc.substring(4)
            idOrDoc.startsWith("TRF-", true) -> idOrDoc.substring(4)
            idOrDoc.startsWith("PROD-", true) -> idOrDoc.substring(5)
            idOrDoc.startsWith("BOX-", true) -> idOrDoc.substring(4)
            else -> null
        }
        if (altId != null && deletedKeys.contains(makeKey(collection, altId))) {
            return true
        }

        return false
    }

    fun isDeleted(collection: String, numericId: Long): Boolean {
        if (numericId <= 0) return false
        return isDeleted(collection, numericId.toString())
    }

    /**
     * إلغاء الشاهد عند إضافة سجل جديد أو إعادة إنشائه لمنع حذفه بالخطأ
     */
    fun unregisterDeleted(collection: String, idOrDoc: String) {
        if (idOrDoc.isBlank()) return
        val key1 = makeKey(collection, idOrDoc)
        deletedKeys.remove(key1)
        allTombstones.remove(key1)

        if (normalize(collection) == "cashboxes" && !idOrDoc.startsWith("BOX-", true)) {
            val hashDocKey = makeKey(collection, cashboxDocId(idOrDoc))
            deletedKeys.remove(hashDocKey)
            allTombstones.remove(hashDocKey)
        }

        val altId = when {
            idOrDoc.startsWith("MOV-", true) -> idOrDoc.substring(4)
            idOrDoc.startsWith("CUST-", true) -> idOrDoc.substring(5)
            idOrDoc.startsWith("INV-", true) -> idOrDoc.substring(4)
            idOrDoc.startsWith("TRF-", true) -> idOrDoc.substring(4)
            idOrDoc.startsWith("PROD-", true) -> idOrDoc.substring(5)
            idOrDoc.startsWith("BOX-", true) -> idOrDoc.substring(4)
            else -> null
        }
        if (altId != null) {
            val key2 = makeKey(collection, altId)
            deletedKeys.remove(key2)
            allTombstones.remove(key2)
        }
        appContext?.let { persist(it) }
    }

    fun unregisterDeleted(collection: String, numericId: Long) {
        if (numericId > 0) unregisterDeleted(collection, numericId.toString())
    }

    /**
     * التحقق مما إذا كان السجل قديماً يسبق تاريخ تصفير النظام (Wipe Epoch).
     * تطبق فقط على التواريخ الفعلية بالمللي ثانية (أكبر من عام 2020: 1600000000000L).
     * الأرقام التسلسلية التلقائية (1، 2، 3...) لا تُعتبر تاريخ تصفير مطلقاً.
     */
    fun isPreEpoch(createdAtTimestamp: Long): Boolean {
        if (createdAtTimestamp < 1_600_000_000_000L) return false
        return clusterEpochTimestamp > 0 && createdAtTimestamp < clusterEpochTimestamp
    }

    fun setClusterEpoch(timestamp: Long, actor: String = "مدير النظام") {
        if (timestamp > clusterEpochTimestamp) {
            clusterEpochTimestamp = timestamp
            appContext?.let { persist(it) }
            logInfo("Updated cluster epoch to $timestamp by $actor")
        }
    }

    fun getClusterEpoch(): Long = clusterEpochTimestamp

    /**
     * استخراج كافة شواهد الحذف لرفعها ومزامنتها على السحابة
     */
    fun getAllTombstoneRecords(): List<Map<String, Any>> {
        return allTombstones.values.distinctBy { it.docId }.map { t ->
            mapOf(
                "collection" to t.collection,
                "entityId" to t.entityId,
                "docId" to t.docId,
                "deletedAt" to t.deletedAt,
                "deletedBy" to t.deletedBy
            )
        }
    }

    /**
     * دمج شواهد الحذف المستلمة من السحابة لتطبيق الحذف فورياً على قاعدة البيانات المحلية
     */
    fun mergeRemoteTombstones(remoteList: List<Map<String, Any>>, remoteEpoch: Long = 0L) {
        var added = 0
        for (item in remoteList) {
            val col = (item["collection"] as? String).orEmpty()
            val entityId = (item["entityId"] as? String).orEmpty()
            val docId = (item["docId"] as? String).orEmpty()
            val delAt = (item["deletedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val delBy = (item["deletedBy"] as? String) ?: "سيرفر السحابة"

            if (col.isNotBlank() && (entityId.isNotBlank() || docId.isNotBlank())) {
                val primaryId = entityId.ifBlank { docId }
                val key1 = makeKey(col, primaryId)
                val key2 = if (docId.isNotBlank()) makeKey(col, docId) else key1

                if (deletedKeys.add(key1) or deletedKeys.add(key2)) {
                    val tombstone = RecordTombstone(col, primaryId, docId.ifBlank { primaryId }, delAt, delBy)
                    allTombstones[key1] = tombstone
                    allTombstones[key2] = tombstone
                    added++
                }
            }
        }

        if (remoteEpoch > clusterEpochTimestamp) {
            clusterEpochTimestamp = remoteEpoch
        }

        if (added > 0 || remoteEpoch > 0) {
            appContext?.let { persist(it) }
            logInfo("Merged $added remote tombstones. Total active tombstones: ${allTombstones.size}")
        }
    }

    private fun persist(context: Context) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val root = JSONObject()
            root.put("epoch", clusterEpochTimestamp)
            val arr = JSONArray()
            allTombstones.values.distinctBy { it.docId }.forEach { t ->
                val obj = JSONObject()
                obj.put("collection", t.collection)
                obj.put("entityId", t.entityId)
                obj.put("docId", t.docId)
                obj.put("deletedAt", t.deletedAt)
                obj.put("deletedBy", t.deletedBy)
                arr.put(obj)
            }
            root.put("tombstones", arr)
            file.writeText(root.toString())
        }.onFailure {
            logWarn("Failed to persist tombstones: ${it.message}")
        }
    }

    private fun loadPersisted(context: Context) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return
            val content = file.readText()
            if (content.isBlank()) return
            val root = JSONObject(content)
            clusterEpochTimestamp = root.optLong("epoch", 0L)
            val arr = root.optJSONArray("tombstones") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val col = obj.optString("collection", "")
                val entityId = obj.optString("entityId", "")
                val docId = obj.optString("docId", "")
                val delAt = obj.optLong("deletedAt", System.currentTimeMillis())
                val delBy = obj.optString("deletedBy", "مدير النظام")

                if (col.isNotBlank() && (entityId.isNotBlank() || docId.isNotBlank())) {
                    val primaryId = entityId.ifBlank { docId }
                    val key1 = makeKey(col, primaryId)
                    val key2 = if (docId.isNotBlank()) makeKey(col, docId) else key1

                    deletedKeys.add(key1)
                    deletedKeys.add(key2)

                    val t = RecordTombstone(col, primaryId, docId.ifBlank { primaryId }, delAt, delBy)
                    allTombstones[key1] = t
                    allTombstones[key2] = t
                }
            }
            logInfo("Loaded ${allTombstones.size} tombstones from local storage. Epoch: $clusterEpochTimestamp")
        }.onFailure {
            logWarn("Failed to load persisted tombstones: ${it.message}")
        }
    }
}
