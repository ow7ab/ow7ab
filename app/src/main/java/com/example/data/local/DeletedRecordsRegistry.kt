package com.example.data.local

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * سجل شواهد الحذف الشامل (Distributed ERP Tombstone Registry)
 * يمنع عودة السجلات المحذوفة (حركات مالية، عملاء، فواتير، تحويلات، أصناف)
 * نهائياً عند قيام أي هاتف آخر أو نسخة قديمة بالضغط على "مزامنة"،
 * مع مزامنة ثنائية مع جدول record_tombstones داخل SQLite/Room.
 */
object DeletedRecordsRegistry {

    private const val TAG = "DeletedRecordsRegistry"
    private const val FILE_NAME = "pmp_deleted_erp_records.json"
    private val persistLock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        val deletedBy: String = "مدير النظام",
        val aliases: List<String> = emptyList()
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

    @Volatile
    private var boundDao: PmpDao? = null

    fun init(context: Context) {
        val app = context.applicationContext
        if (isInitialized && appContext === app) return
        appContext = app
        loadPersisted(app)
        isInitialized = true
    }

    /**
     * يفرغ الذاكرة المؤقتة بين اختبارات الوحدات المعزولة مع الحفاظ على التوافق الكامل في التشغيل الفعلي.
     */
    fun resetForTesting() {
        synchronized(persistLock) {
            runCatching {
                appContext?.let { File(it.filesDir, FILE_NAME).delete() }
            }
            deletedKeys.clear()
            allTombstones.clear()
            clusterEpochTimestamp = 0L
            boundDao = null
            appContext = null
            isInitialized = false
        }
    }

    /**
     * يربط السجل بـ Room PmpDao ويقوم بتبادل/مزامنة الشواهد بين SQLite والذاكرة والملف المحلي.
     */
    suspend fun syncWithDao(dao: PmpDao) {
        boundDao = dao
        runCatching {
            val dbTombstones = dao.getAllTombstonesDirect()
            var loadedFromDb = 0
            for (row in dbTombstones) {
                val col = row.collectionName.trim()
                if (col.isBlank()) continue
                val kNorm = row.normalizedKey.ifBlank { makeKey(col, row.entityId.ifBlank { row.docId }) }
                if (deletedKeys.add(kNorm)) {
                    allTombstones[kNorm] = RecordTombstone(
                        collection = col,
                        entityId = row.entityId,
                        docId = row.docId,
                        deletedAt = row.deletedAt,
                        deletedBy = row.deletedBy
                    )
                    loadedFromDb++
                }
                if (row.entityId.isNotBlank()) deletedKeys.add(makeKey(col, row.entityId))
                if (row.docId.isNotBlank()) deletedKeys.add(makeKey(col, row.docId))
            }
            // Push any in-memory tombstones into SQLite table
            persistTombstonesToDao(dao)
            if (loadedFromDb > 0) {
                appContext?.let { persist(it) }
                logInfo("Synchronized $loadedFromDb tombstones from SQLite record_tombstones")
            }
        }.onFailure {
            logWarn("Failed syncing tombstones with Room DAO: ${it.message}")
        }
    }

    private suspend fun persistTombstonesToDao(dao: PmpDao) {
        val entities = buildSqlEntitiesSnapshot()
        if (entities.isNotEmpty()) {
            dao.upsertTombstones(entities)
        }
    }

    private fun buildSqlEntitiesSnapshot(): List<RecordTombstoneEntity> {
        val rows = LinkedHashMap<String, RecordTombstoneEntity>()
        allTombstones.forEach { (k, t) ->
            val cleanCol = t.collection.trim().lowercase()
            if (cleanCol.isNotBlank() && k.isNotBlank()) {
                rows[k] = RecordTombstoneEntity(
                    collectionName = cleanCol,
                    normalizedKey = k,
                    entityId = t.entityId,
                    docId = t.docId,
                    deletedAt = t.deletedAt,
                    deletedBy = t.deletedBy
                )
            }
        }
        return rows.values.toList()
    }

    private fun normalize(v: String?): String = v?.trim()?.lowercase() ?: ""

    fun makeKey(collection: String, idOrDoc: String): String {
        val cleanCol = normalize(collection)
        val cleanId = normalize(idOrDoc).replace(Regex("[^\\p{L}\\p{N}_\\-]"), "-").trim('-')
        return "${cleanCol}_$cleanId"
    }

    private fun extractBareId(idOrDoc: String): String? {
        val trimmed = idOrDoc.trim()
        return when {
            trimmed.startsWith("MOV-", true) -> trimmed.substring(4)
            trimmed.startsWith("CUST-", true) -> trimmed.substring(5)
            trimmed.startsWith("INV-", true) -> trimmed.substring(4)
            trimmed.startsWith("TRF-", true) -> trimmed.substring(4)
            trimmed.startsWith("PROD-", true) -> trimmed.substring(5)
            trimmed.startsWith("PRD-", true) -> trimmed.substring(4)
            trimmed.startsWith("BOX-", true) -> trimmed.substring(4)
            trimmed.startsWith("WH-", true) -> trimmed.substring(3)
            trimmed.startsWith("SM-", true) -> trimmed.substring(3)
            trimmed.startsWith("FI-", true) -> trimmed.substring(3)
            trimmed.startsWith("FM-", true) -> trimmed.substring(3)
            else -> null
        }?.takeIf { it.isNotBlank() }
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
        registerDeletedWithAliases(
            collection = collection,
            aliases = listOfNotNull(
                entityId.takeIf { it.isNotBlank() },
                docId.takeIf { it.isNotBlank() }
            ),
            primaryEntityId = entityId,
            primaryDocId = docId,
            actor = actor
        )
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
     * تسجيل السجل مع كافة المعرفات البديلة (id, MOV-id, cloudDocId, reference, syncUuid)
     * لضمان مطابقة ومنع عودة السجل عبر Android و Web و Desktop.
     */
    fun registerDeletedWithAliases(
        collection: String,
        aliases: Collection<String>,
        primaryEntityId: String = "",
        primaryDocId: String = "",
        actor: String = "مدير النظام"
    ) {
        val cleanCol = collection.trim()
        val nonBlankAliases = aliases.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleanCol.isBlank() || (nonBlankAliases.isEmpty() && primaryEntityId.isBlank() && primaryDocId.isBlank())) return

        val resolvedEntityId = primaryEntityId.trim().ifBlank {
            nonBlankAliases.firstOrNull().orEmpty().ifBlank { primaryDocId.trim() }
        }
        val resolvedDocId = primaryDocId.trim().ifBlank {
            when (normalize(cleanCol)) {
                "movements" -> if (resolvedEntityId.startsWith("MOV-", true)) resolvedEntityId else "MOV-$resolvedEntityId"
                "customers" -> if (resolvedEntityId.startsWith("CUST-", true)) resolvedEntityId else "CUST-$resolvedEntityId"
                "invoices" -> if (resolvedEntityId.startsWith("INV-", true)) resolvedEntityId else "INV-$resolvedEntityId"
                "transfers" -> if (resolvedEntityId.startsWith("TRF-", true)) resolvedEntityId else "TRF-$resolvedEntityId"
                "products" -> if (resolvedEntityId.startsWith("PROD-", true)) resolvedEntityId else "PROD-$resolvedEntityId"
                "warehouses" -> if (resolvedEntityId.startsWith("WH-", true)) resolvedEntityId else "WH-$resolvedEntityId"
                "stock_moves" -> if (resolvedEntityId.startsWith("SM-", true)) resolvedEntityId else "SM-$resolvedEntityId"
                "cashboxes" -> if (resolvedEntityId.startsWith("BOX-", true)) resolvedEntityId else cashboxDocId(resolvedEntityId)
                else -> resolvedEntityId
            }
        }

        val allKeysToRegister = linkedSetOf<String>()
        if (resolvedEntityId.isNotBlank()) allKeysToRegister.add(resolvedEntityId)
        if (resolvedDocId.isNotBlank()) allKeysToRegister.add(resolvedDocId)
        allKeysToRegister.addAll(nonBlankAliases)
        if (normalize(cleanCol) == "cashboxes") {
            if (resolvedEntityId.isNotBlank() && !resolvedEntityId.startsWith("BOX-", true)) {
                allKeysToRegister.add(cashboxDocId(resolvedEntityId))
            }
            nonBlankAliases.forEach { a ->
                if (!a.startsWith("BOX-", true)) allKeysToRegister.add(cashboxDocId(a))
            }
        }

        val tombstone = RecordTombstone(
            collection = cleanCol,
            entityId = resolvedEntityId,
            docId = resolvedDocId,
            deletedAt = System.currentTimeMillis(),
            deletedBy = actor,
            aliases = allKeysToRegister.toList()
        )

        val sqlBatch = mutableListOf<RecordTombstoneEntity>()
        for (rawAlias in allKeysToRegister) {
            val k = makeKey(cleanCol, rawAlias)
            deletedKeys.add(k)
            allTombstones[k] = tombstone
            sqlBatch.add(
                RecordTombstoneEntity(
                    collectionName = cleanCol.lowercase(),
                    normalizedKey = k,
                    entityId = resolvedEntityId,
                    docId = resolvedDocId,
                    deletedAt = tombstone.deletedAt,
                    deletedBy = actor
                )
            )
            val bare = extractBareId(rawAlias)
            if (bare != null) {
                val bareKey = makeKey(cleanCol, bare)
                deletedKeys.add(bareKey)
                allTombstones[bareKey] = tombstone
            }
        }

        appContext?.let { persist(it) }
        val dao = boundDao
        if (dao != null && sqlBatch.isNotEmpty()) {
            ioScope.launch {
                runCatching { dao.upsertTombstones(sqlBatch) }
            }
        }
        logInfo("Registered tombstone for $cleanCol: entityId=$resolvedEntityId, docId=$resolvedDocId, aliases=${allKeysToRegister.size} by $actor")
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

        val altId = extractBareId(idOrDoc)
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
     * فحص شامل للحركة المالية بكافة معرفاتها (id, MOV-id, reference, cloudDocId, syncUuid, epoch).
     */
    fun isMovementDeleted(movement: MovementEntity): Boolean {
        if (isPreEpoch(movement.createdAt)) return true
        if (movement.id > 0L && (isDeleted("movements", movement.id) || isDeleted("movements", "MOV-${movement.id}"))) {
            return true
        }
        if (movement.cloudDocId.isNotBlank() && isDeleted("movements", movement.cloudDocId)) return true
        if (movement.reference.isNotBlank() && isDeleted("movements", movement.reference)) return true
        if (movement.syncUuid.isNotBlank() && isDeleted("movements", movement.syncUuid)) return true
        return false
    }

    /**
     * إلغاء الشاهد عند إضافة سجل جديد أو إعادة إنشائه لمنع حذفه بالخطأ
     */
    fun unregisterDeleted(collection: String, idOrDoc: String) {
        if (idOrDoc.isBlank()) return
        val keysToRemove = mutableListOf(makeKey(collection, idOrDoc))

        if (normalize(collection) == "cashboxes" && !idOrDoc.startsWith("BOX-", true)) {
            keysToRemove.add(makeKey(collection, cashboxDocId(idOrDoc)))
        }

        val altId = extractBareId(idOrDoc)
        if (altId != null) {
            keysToRemove.add(makeKey(collection, altId))
        }

        keysToRemove.forEach { k ->
            deletedKeys.remove(k)
            allTombstones.remove(k)
        }
        appContext?.let { persist(it) }
        val dao = boundDao
        if (dao != null) {
            val cleanCol = collection.trim().lowercase()
            ioScope.launch {
                keysToRemove.forEach { k ->
                    runCatching { dao.deleteTombstoneByKey(cleanCol, k) }
                }
            }
        }
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
        return allTombstones.values
            .distinctBy { "${normalize(it.collection)}::${normalize(it.docId)}::${normalize(it.entityId)}" }
            .map { t ->
                val map = mutableMapOf<String, Any>(
                    "collection" to t.collection,
                    "entityId" to t.entityId,
                    "docId" to t.docId,
                    "deletedAt" to t.deletedAt,
                    "deletedBy" to t.deletedBy
                )
                if (t.aliases.isNotEmpty()) {
                    map["aliases"] = t.aliases
                }
                map
            }
    }

    /**
     * دمج شواهد الحذف المستلمة من السحابة لتطبيق الحذف فورياً على قاعدة البيانات المحلية
     */
    fun mergeRemoteTombstones(remoteList: List<Map<String, Any>>, remoteEpoch: Long = 0L) {
        var added = 0
        val sqlBatch = mutableListOf<RecordTombstoneEntity>()

        for (item in remoteList) {
            val col = (item["collection"] as? String).orEmpty().trim()
            val entityId = (item["entityId"] as? String).orEmpty().trim()
            val docId = (item["docId"] as? String).orEmpty().trim()
            val numId = (item["numericId"] as? Number)?.toLong()?.takeIf { it > 0L }?.toString().orEmpty()
            val ref = (item["reference"] as? String).orEmpty().trim()
            val invoiceNo = (item["invoiceNo"] as? String).orEmpty().trim()
            val syncUuid = (item["syncUuid"] as? String).orEmpty().trim()
            @Suppress("UNCHECKED_CAST")
            val remoteAliases = (item["aliases"] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } }.orEmpty()

            val delAt = when (val raw = item["deletedAt"]) {
                is Number -> raw.toLong()
                is Timestamp -> raw.toDate().time
                else -> System.currentTimeMillis()
            }
            val delBy = (item["deletedBy"] as? String) ?: "سيرفر السحابة"

            val candidateAliases = linkedSetOf<String>()
            listOf(entityId, docId, numId, ref, invoiceNo, syncUuid).forEach { v ->
                if (v.isNotBlank()) candidateAliases.add(v)
            }
            candidateAliases.addAll(remoteAliases)

            if (col.isNotBlank() && candidateAliases.isNotEmpty()) {
                val primaryId = entityId.ifBlank { docId.ifBlank { candidateAliases.first() } }
                val resolvedDocId = docId.ifBlank { primaryId }
                val tombstone = RecordTombstone(
                    collection = col,
                    entityId = primaryId,
                    docId = resolvedDocId,
                    deletedAt = delAt,
                    deletedBy = delBy,
                    aliases = candidateAliases.toList()
                )
                for (alias in candidateAliases) {
                    val key = makeKey(col, alias)
                    if (deletedKeys.add(key)) added++
                    allTombstones[key] = tombstone
                    sqlBatch.add(
                        RecordTombstoneEntity(
                            collectionName = col.lowercase(),
                            normalizedKey = key,
                            entityId = primaryId,
                            docId = resolvedDocId,
                            deletedAt = delAt,
                            deletedBy = delBy
                        )
                    )
                    val bare = extractBareId(alias)
                    if (bare != null) {
                        val bareKey = makeKey(col, bare)
                        if (deletedKeys.add(bareKey)) added++
                        allTombstones[bareKey] = tombstone
                    }
                }
            }
        }

        val epochUpdated = remoteEpoch > clusterEpochTimestamp
        if (epochUpdated) {
            clusterEpochTimestamp = remoteEpoch
        }

        if (added > 0 || epochUpdated) {
            appContext?.let { persist(it) }
            val dao = boundDao
            if (dao != null && sqlBatch.isNotEmpty()) {
                ioScope.launch {
                    runCatching { dao.upsertTombstones(sqlBatch) }
                }
            }
            logInfo("Merged $added remote tombstone keys. Total active tombstones: ${allTombstones.size}, epoch=$clusterEpochTimestamp")
        }
    }

    private fun persist(context: Context) {
        synchronized(persistLock) {
            runCatching {
                val file = File(context.filesDir, FILE_NAME)
                val tmpFile = File(context.filesDir, "$FILE_NAME.tmp")
                val root = JSONObject()
                root.put("epoch", clusterEpochTimestamp)
                val arr = JSONArray()
                allTombstones.values
                    .distinctBy { "${normalize(it.collection)}::${normalize(it.docId)}::${normalize(it.entityId)}" }
                    .forEach { t ->
                        val obj = JSONObject()
                        obj.put("collection", t.collection)
                        obj.put("entityId", t.entityId)
                        obj.put("docId", t.docId)
                        obj.put("deletedAt", t.deletedAt)
                        obj.put("deletedBy", t.deletedBy)
                        if (t.aliases.isNotEmpty()) {
                            obj.put("aliases", JSONArray(t.aliases))
                        }
                        arr.put(obj)
                    }
                root.put("tombstones", arr)
                tmpFile.writeText(root.toString())
                if (!tmpFile.renameTo(file)) {
                    file.writeText(root.toString())
                    tmpFile.delete()
                }
            }.onFailure {
                logWarn("Failed to persist tombstones: ${it.message}")
            }
        }
    }

    private fun loadPersisted(context: Context) {
        synchronized(persistLock) {
            runCatching {
                val file = File(context.filesDir, FILE_NAME)
                if (!file.exists()) return
                val content = file.readText()
                if (content.isBlank()) return
                val root = JSONObject(content)
                clusterEpochTimestamp = maxOf(clusterEpochTimestamp, root.optLong("epoch", 0L))
                val arr = root.optJSONArray("tombstones") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val col = obj.optString("collection", "").trim()
                    val entityId = obj.optString("entityId", "").trim()
                    val docId = obj.optString("docId", "").trim()
                    val delAt = obj.optLong("deletedAt", System.currentTimeMillis())
                    val delBy = obj.optString("deletedBy", "مدير النظام")
                    val aliasesArr = obj.optJSONArray("aliases")
                    val aliasList = mutableListOf<String>()
                    if (aliasesArr != null) {
                        for (j in 0 until aliasesArr.length()) {
                            val v = aliasesArr.optString(j, "").trim()
                            if (v.isNotBlank()) aliasList.add(v)
                        }
                    }

                    if (col.isNotBlank() && (entityId.isNotBlank() || docId.isNotBlank() || aliasList.isNotEmpty())) {
                        val primaryId = entityId.ifBlank { docId.ifBlank { aliasList.first() } }
                        val resolvedDocId = docId.ifBlank { primaryId }
                        val combinedAliases = (listOf(primaryId, resolvedDocId) + aliasList).filter { it.isNotBlank() }.distinct()
                        val t = RecordTombstone(col, primaryId, resolvedDocId, delAt, delBy, combinedAliases)
                        for (alias in combinedAliases) {
                            val k = makeKey(col, alias)
                            deletedKeys.add(k)
                            allTombstones[k] = t
                            extractBareId(alias)?.let { bare ->
                                val bk = makeKey(col, bare)
                                deletedKeys.add(bk)
                                allTombstones[bk] = t
                            }
                        }
                    }
                }
                logInfo("Loaded ${allTombstones.size} tombstones from local storage. Epoch: $clusterEpochTimestamp")
            }.onFailure {
                logWarn("Failed to load persisted tombstones: ${it.message}")
            }
        }
    }
}
