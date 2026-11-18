package com.example.data.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DatabaseBackupManager {

    private const val TAG = "DatabaseBackupManager"
    private const val BACKUP_FILE_NAME = "pmp_erp_persistent_backup.json"
    private val snapshotLock = Any()

    /**
     * Creates an offline JSON snapshot of all core ERP tables.
     * Strictly excludes tombstoned / pre-epoch deleted records and embeds record_tombstones + clusterEpoch
     * so deleted financial transactions can NEVER resurrect across APK upgrades or restores.
     */
    suspend fun saveLocalSnapshot(
        context: Context,
        dao: PmpDao,
        labDao: LabDao? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            DeletedAccountsRegistry.init(context)
            DeletedRecordsRegistry.init(context)
            DeletedRecordsRegistry.syncWithDao(dao)

            // Preserve any prior snapshot version before overwriting with v7
            synchronized(snapshotLock) {
                val currentBackup = File(context.filesDir, BACKUP_FILE_NAME)
                val preUpgradeBackup = File(context.filesDir, "pmp_erp_pre_v17_backup.json")
                if (currentBackup.exists() && !preUpgradeBackup.exists()) {
                    runCatching { currentBackup.copyTo(preUpgradeBackup, overwrite = false) }
                }
            }

            val root = JSONObject()
            root.put("version", 7)
            root.put("timestamp", System.currentTimeMillis())
            root.put("clusterEpoch", DeletedRecordsRegistry.getClusterEpoch())

            // 1. Invoices (excluding deleted/pre-epoch)
            val invoices = dao.getInvoicesDirect().filterNot { inv ->
                DeletedRecordsRegistry.isPreEpoch(inv.createdAt) ||
                    DeletedRecordsRegistry.isDeleted("invoices", inv.id) ||
                    DeletedRecordsRegistry.isDeleted("invoices", "INV-${inv.id}") ||
                    (inv.invoiceNo.isNotBlank() && DeletedRecordsRegistry.isDeleted("invoices", inv.invoiceNo))
            }
            val validInvoiceIds = invoices.map { it.id }.toSet()
            val invArray = JSONArray()
            invoices.forEach { inv ->
                invArray.put(JSONObject().apply {
                    put("id", inv.id)
                    put("invoiceNo", inv.invoiceNo)
                    put("customerId", inv.customerId)
                    put("customerName", inv.customerName)
                    put("invoiceDate", inv.invoiceDate)
                    put("currency", inv.currency)
                    put("subtotal", inv.subtotal)
                    put("discount", inv.discount)
                    put("total", inv.total)
                    put("notes", inv.notes)
                    put("status", inv.status)
                    put("cancelReason", inv.cancelReason ?: "")
                    put("createdBy", inv.createdBy)
                    put("createdAt", inv.createdAt)
                })
            }
            root.put("invoices", invArray)

            // 2. Invoice Items (only for surviving non-deleted invoices)
            val items = dao.getInvoiceItemsDirect().filter { item ->
                item.invoiceId in validInvoiceIds &&
                    !DeletedRecordsRegistry.isDeleted("invoice_items", item.id)
            }
            val itemArray = JSONArray()
            items.forEach { item ->
                itemArray.put(JSONObject().apply {
                    put("id", item.id)
                    put("invoiceId", item.invoiceId)
                    put("description", item.description)
                    put("quantity", item.quantity)
                    put("unitPrice", item.unitPrice)
                    put("lineTotal", item.lineTotal)
                    put("productId", item.productId ?: -1L)
                    put("warehouseId", item.warehouseId ?: -1L)
                })
            }
            root.put("invoice_items", itemArray)

            // 3. Invoice Payments (only for surviving non-deleted invoices)
            val payments = dao.getInvoicePaymentsDirect().filter { pay ->
                pay.invoiceId in validInvoiceIds &&
                    !DeletedRecordsRegistry.isPreEpoch(pay.createdAt) &&
                    !DeletedRecordsRegistry.isDeleted("invoice_payments", pay.id)
            }
            val payArray = JSONArray()
            payments.forEach { pay ->
                payArray.put(JSONObject().apply {
                    put("id", pay.id)
                    put("invoiceId", pay.invoiceId)
                    put("payDate", pay.payDate)
                    put("cashbox", pay.cashbox)
                    put("amount", pay.amount)
                    put("note", pay.note)
                    put("status", pay.status)
                    put("createdBy", pay.createdBy)
                    put("createdAt", pay.createdAt)
                })
            }
            root.put("invoice_payments", payArray)

            // 4. Movements (strictly excluding tombstoned & pre-epoch records)
            val movements = dao.getMovementsDirect().filterNot { m ->
                DeletedRecordsRegistry.isMovementDeleted(m)
            }
            val movArray = JSONArray()
            movements.forEach { m ->
                movArray.put(JSONObject().apply {
                    put("id", m.id)
                    put("txDate", m.txDate)
                    put("cashbox", m.cashbox)
                    put("currency", m.currency)
                    put("direction", m.direction)
                    put("amount", m.amount)
                    put("description", m.description)
                    put("reference", m.reference)
                    put("createdBy", m.createdBy)
                    put("createdAt", m.createdAt)
                    put("cloudDocId", m.cloudDocId)
                    put("syncUuid", m.syncUuid)
                    put("updatedAt", m.updatedAt)
                })
            }
            root.put("movements", movArray)

            // 5. Customers (excluding deleted)
            val customers = dao.getCustomersDirect().filterNot { c ->
                DeletedRecordsRegistry.isPreEpoch(c.createdAt) ||
                    DeletedRecordsRegistry.isDeleted("customers", c.id) ||
                    DeletedRecordsRegistry.isDeleted("customers", "CUST-${c.id}") ||
                    DeletedRecordsRegistry.isDeleted("customers", c.name)
            }
            val custArray = JSONArray()
            customers.forEach { c ->
                custArray.put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("phone", c.phone)
                    put("address", c.address)
                    put("notes", c.notes)
                    put("active", c.active)
                    put("createdAt", c.createdAt)
                })
            }
            root.put("customers", custArray)

            // 6. Products (excluding deleted)
            val products = dao.getProductsDirect().filterNot { p ->
                DeletedRecordsRegistry.isDeleted("products", p.id) ||
                    DeletedRecordsRegistry.isDeleted("products", "PROD-${p.id}") ||
                    DeletedRecordsRegistry.isDeleted("products", "PRD-${p.id}")
            }
            val prodArray = JSONArray()
            products.forEach { p ->
                prodArray.put(JSONObject().apply {
                    put("id", p.id)
                    put("sku", p.sku)
                    put("name", p.name)
                    put("category", p.category)
                    put("unit", p.unit)
                    put("salePrice", p.salePrice)
                    put("minimumStock", p.minimumStock)
                    put("paintColor", p.paintColor)
                    put("packageWeight", p.packageWeight)
                    put("active", p.active)
                })
            }
            root.put("products", prodArray)

            // 7. Cashboxes (excluding deleted)
            val cashboxes = dao.getCashboxesDirect().filterNot { cb ->
                val cbDocId = DeletedRecordsRegistry.cashboxDocId(cb.name, cb.id)
                DeletedRecordsRegistry.isDeleted("cashboxes", cb.id) ||
                    DeletedRecordsRegistry.isDeleted("cashboxes", "BOX-${cb.id}") ||
                    DeletedRecordsRegistry.isDeleted("cashboxes", cb.name) ||
                    DeletedRecordsRegistry.isDeleted("cashboxes", cbDocId)
            }
            val cashArray = JSONArray()
            cashboxes.forEach { cb ->
                cashArray.put(JSONObject().apply {
                    put("id", cb.id)
                    put("name", cb.name)
                })
            }
            root.put("cashboxes", cashArray)

            // 8. User Accounts (excluding permanently deleted tombstones)
            val userAccounts = dao.getUserAccountsDirect()
            val userArray = JSONArray()
            userAccounts.forEach { u ->
                if (!DeletedAccountsRegistry.isDeleted(u.userCode, u.username, u.phoneOrEmail)) {
                    userArray.put(JSONObject().apply {
                        put("id", u.id)
                        put("userCode", u.userCode)
                        put("fullName", u.fullName)
                        put("username", u.username)
                        put("phoneOrEmail", u.phoneOrEmail)
                        put("pinOrPassword", u.pinOrPassword)
                        put("role", u.role)
                        put("branch", u.branch)
                        put("permissions", u.permissions)
                        put("serverSyncToken", u.serverSyncToken)
                        put("remoteServerId", u.remoteServerId)
                        put("syncStatus", u.syncStatus)
                        put("serverEndpoint", u.serverEndpoint)
                        put("isCurrentSession", false)
                        put("active", u.active)
                        put("createdAt", u.createdAt)
                    })
                }
            }
            root.put("user_accounts", userArray)

            // 9. Persistent Tombstones of Permanently Deleted Accounts
            val tombstonesArray = JSONArray()
            DeletedAccountsRegistry.getAllTombstoneRecords().forEach { t ->
                tombstonesArray.put(JSONObject(t))
            }
            root.put("deleted_accounts", tombstonesArray)

            // 10. Persistent Tombstones of All Deleted ERP Records (Financial, Invoices, Inventory, etc.)
            val recordTombstonesArray = JSONArray()
            DeletedRecordsRegistry.getAllTombstoneRecords().forEach { t ->
                val obj = JSONObject()
                t.forEach { (k, v) ->
                    if (v is List<*>) obj.put(k, JSONArray(v)) else obj.put(k, v)
                }
                recordTombstonesArray.put(obj)
            }
            root.put("record_tombstones", recordTombstonesArray)

            // 11. Chemical Formulations & Version History (excluding deleted/pre-epoch)
            val formulations = dao.getFormulationsDirect().filterNot { f ->
                DeletedRecordsRegistry.isPreEpoch(f.createdAt) ||
                    DeletedRecordsRegistry.isDeleted("lab_formulations", f.id) ||
                    DeletedRecordsRegistry.isDeleted("lab_formulations", "FORM-${f.id}") ||
                    (f.formulaNo.isNotBlank() && DeletedRecordsRegistry.isDeleted("lab_formulations", f.formulaNo))
            }
            val frmArray = JSONArray()
            formulations.forEach { f ->
                frmArray.put(JSONObject().apply {
                    put("id", f.id)
                    put("formulaNo", f.formulaNo)
                    put("productName", f.productName)
                    put("paintType", f.paintType)
                    put("baseKind", f.baseKind)
                    put("targetPvc", f.targetPvc)
                    put("calculatedPvc", f.calculatedPvc)
                    put("cpvcEstimated", f.cpvcEstimated)
                    put("totalSolidsWeight", f.totalSolidsWeight)
                    put("totalSolidsVolume", f.totalSolidsVolume)
                    put("totalBatchWeight", f.totalBatchWeight)
                    put("totalBatchVolume", f.totalBatchVolume)
                    put("calculatedDensity", f.calculatedDensity)
                    put("totalCost", f.totalCost)
                    put("costPerKg", f.costPerKg)
                    put("ingredientsJson", if (f.isConfidentialSealed) "[]" else f.ingredientsJson)
                    put("technicalAnalysis", f.technicalAnalysis)
                    put("notes", f.notes)
                    put("approvedForProduction", f.approvedForProduction)
                    put("createdBy", f.createdBy)
                    put("createdAt", f.createdAt)
                    put("syncUuid", f.syncUuid)
                    put("linkedProductId", f.linkedProductId ?: -1L)
                    put("linkedProductSku", f.linkedProductSku)
                    put("category", f.category)
                    put("versionNumber", f.versionNumber)
                    put("approvalStatus", f.approvalStatus)
                    put("approvedBy", f.approvedBy)
                    put("approvedAt", f.approvedAt)
                    put("historicalTotalCost", f.historicalTotalCost)
                    put("historicalCostPerKg", f.historicalCostPerKg)
                    put("currency", f.currency)
                    put("changeReason", f.changeReason)
                    put("updatedBy", f.updatedBy)
                    put("updatedAt", f.updatedAt)
                    put("confidentialCiphertext", f.confidentialCiphertext)
                    put("isConfidentialSealed", f.isConfidentialSealed)
                })
            }
            root.put("lab_formulations", frmArray)

            if (labDao != null) {
                val versionsArray = JSONArray()
                labDao.getAllFormulaVersionsDirect().forEach { v ->
                    versionsArray.put(JSONObject().apply {
                        put("id", v.id)
                        put("syncUuid", v.syncUuid)
                        put("formulaId", v.formulaId)
                        put("formulaNo", v.formulaNo)
                        put("productName", v.productName)
                        put("versionNumber", v.versionNumber)
                        put("previousVersionNumber", v.previousVersionNumber)
                        put("status", v.status)
                        put("changeReason", v.changeReason)
                        put("diffSummaryJson", v.diffSummaryJson)
                        put("previousCostPerKg", v.previousCostPerKg)
                        put("newCostPerKg", v.newCostPerKg)
                        put("historicalTotalCost", v.historicalTotalCost)
                        put("calculatedPvc", v.calculatedPvc)
                        put("calculatedDensity", v.calculatedDensity)
                        put("currency", v.currency)
                        put("ingredientsSnapshotJson", if (v.encryptedSnapshotCiphertext.isNotBlank()) "[]" else v.ingredientsSnapshotJson)
                        put("encryptedSnapshotCiphertext", v.encryptedSnapshotCiphertext)
                        put("createdBy", v.createdBy)
                        put("approvedBy", v.approvedBy)
                        put("approvedAt", v.approvedAt)
                        put("createdAt", v.createdAt)
                    })
                }
                root.put("lab_formula_versions", versionsArray)

                val expArray = JSONArray()
                labDao.getAllLabExperimentsDirect().forEach { ex ->
                    expArray.put(JSONObject().apply {
                        put("id", ex.id)
                        put("syncUuid", ex.syncUuid)
                        put("experimentNo", ex.experimentNo)
                        put("formulaId", ex.formulaId)
                        put("formulaNo", ex.formulaNo)
                        put("formulaVersionId", ex.formulaVersionId ?: -1L)
                        put("versionNumber", ex.versionNumber)
                        put("productId", ex.productId ?: -1L)
                        put("productName", ex.productName)
                        put("experimentDate", ex.experimentDate)
                        put("technicianName", ex.technicianName)
                        put("materialsUsedJson", ex.materialsUsedJson)
                        put("measuredDensity", ex.measuredDensity)
                        put("measuredViscosity", ex.measuredViscosity)
                        put("measuredPh", ex.measuredPh)
                        put("measuredGrindMicrons", ex.measuredGrindMicrons)
                        put("hidingPowerPercent", ex.hidingPowerPercent)
                        put("notes", ex.notes)
                        put("status", ex.status)
                        put("decision", ex.decision)
                        put("decidedBy", ex.decidedBy)
                        put("decidedAt", ex.decidedAt)
                        put("linkedQcNo", ex.linkedQcNo)
                        put("idempotencyKey", ex.idempotencyKey)
                        put("createdAt", ex.createdAt)
                        put("updatedAt", ex.updatedAt)
                    })
                }
                root.put("lab_experiments", expArray)

                val priceHistArray = JSONArray()
                labDao.getAllRawMaterialPriceHistoryDirect().forEach { ph ->
                    priceHistArray.put(JSONObject().apply {
                        put("id", ph.id)
                        put("syncUuid", ph.syncUuid)
                        put("materialId", ph.materialId)
                        put("itemCode", ph.itemCode)
                        put("materialName", ph.materialName)
                        put("supplierName", ph.supplierName)
                        put("unitPrice", ph.unitPrice)
                        put("previousPrice", ph.previousPrice)
                        put("currency", ph.currency)
                        put("sourceType", ph.sourceType)
                        put("sourceDocNo", ph.sourceDocNo)
                        put("effectiveDate", ph.effectiveDate)
                        put("recordedBy", ph.recordedBy)
                        put("createdAt", ph.createdAt)
                    })
                }
                root.put("raw_material_price_history", priceHistArray)

                val excelArray = JSONArray()
                labDao.getAllExcelImportsDirect().forEach { exImp ->
                    excelArray.put(JSONObject().apply {
                        put("id", exImp.id)
                        put("syncUuid", exImp.syncUuid)
                        put("importNo", exImp.importNo)
                        put("fileName", exImp.fileName)
                        put("sheetCount", exImp.sheetCount)
                        put("formulasDetected", exImp.formulasDetected)
                        put("formulasImported", exImp.formulasImported)
                        put("materialsMatched", exImp.materialsMatched)
                        put("materialsCreated", exImp.materialsCreated)
                        put("externalLinksDetected", exImp.externalLinksDetected)
                        put("brokenRefsDetected", exImp.brokenRefsDetected)
                        put("warningsCount", exImp.warningsCount)
                        put("approvalStatus", exImp.approvalStatus)
                        put("checksumSha256", exImp.checksumSha256)
                        put("restrictedSummaryJson", exImp.restrictedSummaryJson)
                        put("referenceArchivePath", exImp.referenceArchivePath)
                        put("importedBy", exImp.importedBy)
                        put("idempotencyKey", exImp.idempotencyKey)
                        put("createdAt", exImp.createdAt)
                    })
                }
                root.put("lab_excel_imports", excelArray)
            }

            synchronized(snapshotLock) {
                val file = File(context.filesDir, BACKUP_FILE_NAME)
                val tmpFile = File(context.filesDir, "$BACKUP_FILE_NAME.tmp")
                tmpFile.writeText(root.toString(2))
                if (!tmpFile.renameTo(file)) {
                    file.writeText(root.toString(2))
                    tmpFile.delete()
                }
                Log.d(TAG, "Saved persistent ERP snapshot v7 with ${invoices.size} invoices, ${movements.size} movements, ${formulations.size} formulations")
                file
            }
        }
    }

    /**
     * Checks if the database is newly initialized or empty, and restores previous data from the snapshot,
     * applying all account & ERP record tombstones FIRST so deleted records never resurrect.
     */
    suspend fun restoreIfEmpty(
        context: Context,
        dao: PmpDao,
        labDao: LabDao? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, BACKUP_FILE_NAME)
        if (!file.exists()) return@withContext false

        DeletedAccountsRegistry.init(context)
        DeletedRecordsRegistry.init(context)

        val existingInvoices = dao.getInvoicesDirect()
        val existingMovements = dao.getMovementsDirect()
        val hasTransactions = existingInvoices.isNotEmpty() || existingMovements.isNotEmpty()

        try {
            val jsonText = synchronized(snapshotLock) { file.readText() }
            val root = JSONObject(jsonText)

            // 0a. Restore cluster epoch and ERP record tombstones FIRST before restoring any entity!
            val backupEpoch = root.optLong("clusterEpoch", 0L)
            val recTombArray = root.optJSONArray("record_tombstones")
            val remoteList = mutableListOf<Map<String, Any>>()
            if (recTombArray != null) {
                for (i in 0 until recTombArray.length()) {
                    val obj = recTombArray.getJSONObject(i)
                    val aliasesArr = obj.optJSONArray("aliases")
                    val aliases = mutableListOf<String>()
                    if (aliasesArr != null) {
                        for (j in 0 until aliasesArr.length()) {
                            val a = aliasesArr.optString(j, "").trim()
                            if (a.isNotBlank()) aliases.add(a)
                        }
                    }
                    val map = mutableMapOf<String, Any>(
                        "collection" to obj.optString("collection"),
                        "entityId" to obj.optString("entityId"),
                        "docId" to obj.optString("docId"),
                        "deletedAt" to obj.optLong("deletedAt", System.currentTimeMillis()),
                        "deletedBy" to obj.optString("deletedBy", "مدير النظام")
                    )
                    if (aliases.isNotEmpty()) map["aliases"] = aliases
                    remoteList.add(map)
                }
            }
            if (remoteList.isNotEmpty() || backupEpoch > 0L) {
                DeletedRecordsRegistry.mergeRemoteTombstones(remoteList, backupEpoch)
            }
            DeletedRecordsRegistry.syncWithDao(dao)

            // 0b. Load account tombstones
            val delArray = root.optJSONArray("deleted_accounts")
            if (delArray != null && delArray.length() > 0) {
                val listDel = mutableListOf<Map<String, Any>>()
                for (i in 0 until delArray.length()) {
                    val obj = delArray.getJSONObject(i)
                    listDel.add(
                        mapOf(
                            "userCode" to obj.optString("userCode"),
                            "username" to obj.optString("username"),
                            "email" to obj.optString("email"),
                            "deletedAt" to obj.optLong("deletedAt", System.currentTimeMillis()),
                            "deletedBy" to obj.optString("deletedBy", "مدير النظام")
                        )
                    )
                }
                DeletedAccountsRegistry.mergeRemoteTombstones(listDel)
            }

            // Always restore and merge non-deleted user accounts so accounts from previous app versions are preserved
            val userArray = root.optJSONArray("user_accounts")
            if (userArray != null && userArray.length() > 0) {
                val currentUsers = dao.getUserAccountsDirect()
                val currentActiveId = currentUsers.firstOrNull { it.isCurrentSession }?.id
                val list = mutableListOf<UserAccountEntity>()
                for (i in 0 until userArray.length()) {
                    val obj = userArray.getJSONObject(i)
                    val uId = obj.optLong("id")
                    val uCode = obj.optString("userCode")
                    val uName = obj.optString("username")
                    val uEmail = obj.optString("phoneOrEmail")

                    // Strictly reject and purge any deleted tombstoned account
                    if (DeletedAccountsRegistry.isDeleted(uCode, uName, uEmail)) {
                        if (uId > 0) dao.deleteUserAccount(uId)
                        if (uCode.isNotBlank()) dao.deleteUserAccountByCode(uCode)
                        if (uName.isNotBlank()) dao.deleteUserAccountByUsername(uName)
                        continue
                    }

                    val existing = currentUsers.firstOrNull { it.id == uId || (it.userCode.isNotBlank() && it.userCode == uCode) }
                    val isSession = (existing != null && existing.isCurrentSession) || (currentActiveId != null && uId == currentActiveId)
                    list.add(
                        UserAccountEntity(
                            id = uId,
                            userCode = uCode,
                            fullName = obj.optString("fullName"),
                            username = uName,
                            phoneOrEmail = uEmail,
                            pinOrPassword = obj.optString("pinOrPassword", "1234"),
                            role = obj.optString("role"),
                            branch = obj.optString("branch"),
                            permissions = obj.optString("permissions"),
                            serverSyncToken = obj.optString("serverSyncToken"),
                            remoteServerId = obj.optString("remoteServerId"),
                            syncStatus = obj.optString("syncStatus", "APPROVED_BY_MANAGER"),
                            serverEndpoint = obj.optString("serverEndpoint"),
                            isCurrentSession = isSession,
                            active = obj.optBoolean("active", true),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    dao.upsertUserAccounts(list)
                }
            }

            if (hasTransactions) {
                return@withContext true
            }

            // Restore Invoices (excluding any tombstoned or pre-epoch invoices)
            val restoredInvoiceIds = mutableSetOf<Long>()
            val invArray = root.optJSONArray("invoices")
            if (invArray != null && invArray.length() > 0) {
                val list = mutableListOf<InvoiceEntity>()
                for (i in 0 until invArray.length()) {
                    val obj = invArray.getJSONObject(i)
                    val invId = obj.optLong("id")
                    val invNo = obj.optString("invoiceNo")
                    val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    if (DeletedRecordsRegistry.isPreEpoch(createdAt) ||
                        DeletedRecordsRegistry.isDeleted("invoices", invId) ||
                        DeletedRecordsRegistry.isDeleted("invoices", "INV-$invId") ||
                        (invNo.isNotBlank() && DeletedRecordsRegistry.isDeleted("invoices", invNo))
                    ) {
                        continue
                    }
                    restoredInvoiceIds.add(invId)
                    list.add(
                        InvoiceEntity(
                            id = invId,
                            invoiceNo = invNo,
                            customerId = obj.optLong("customerId"),
                            customerName = obj.optString("customerName"),
                            invoiceDate = obj.optString("invoiceDate"),
                            currency = obj.optString("currency", "SDG"),
                            subtotal = obj.optDouble("subtotal"),
                            discount = obj.optDouble("discount"),
                            total = obj.optDouble("total"),
                            notes = obj.optString("notes"),
                            status = obj.optString("status", "active"),
                            cancelReason = obj.optString("cancelReason").ifBlank { null },
                            createdBy = obj.optString("createdBy", "المدير"),
                            createdAt = createdAt
                        )
                    )
                }
                if (list.isNotEmpty()) dao.upsertInvoices(list)
            }

            // Restore Invoice Items (only for kept non-tombstoned invoices)
            val itemArray = root.optJSONArray("invoice_items")
            if (itemArray != null && itemArray.length() > 0) {
                val list = mutableListOf<InvoiceItemEntity>()
                for (i in 0 until itemArray.length()) {
                    val obj = itemArray.getJSONObject(i)
                    val itemId = obj.optLong("id")
                    val invoiceId = obj.optLong("invoiceId")
                    if (invoiceId !in restoredInvoiceIds || DeletedRecordsRegistry.isDeleted("invoice_items", itemId)) {
                        continue
                    }
                    val prodId = obj.optLong("productId", -1L)
                    val whId = obj.optLong("warehouseId", -1L)
                    list.add(
                        InvoiceItemEntity(
                            id = itemId,
                            invoiceId = invoiceId,
                            description = obj.optString("description"),
                            quantity = obj.optDouble("quantity", 1.0),
                            unitPrice = obj.optDouble("unitPrice"),
                            lineTotal = obj.optDouble("lineTotal"),
                            productId = if (prodId > 0) prodId else null,
                            warehouseId = if (whId > 0) whId else null
                        )
                    )
                }
                if (list.isNotEmpty()) dao.upsertInvoiceItems(list)
            }

            // Restore Invoice Payments (only for kept non-tombstoned invoices)
            val payArray = root.optJSONArray("invoice_payments")
            if (payArray != null && payArray.length() > 0) {
                val list = mutableListOf<InvoicePaymentEntity>()
                for (i in 0 until payArray.length()) {
                    val obj = payArray.getJSONObject(i)
                    val payId = obj.optLong("id")
                    val invoiceId = obj.optLong("invoiceId")
                    val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    if (invoiceId !in restoredInvoiceIds ||
                        DeletedRecordsRegistry.isPreEpoch(createdAt) ||
                        DeletedRecordsRegistry.isDeleted("invoice_payments", payId)
                    ) {
                        continue
                    }
                    list.add(
                        InvoicePaymentEntity(
                            id = payId,
                            invoiceId = invoiceId,
                            payDate = obj.optString("payDate"),
                            cashbox = obj.optString("cashbox"),
                            amount = obj.optDouble("amount"),
                            note = obj.optString("note"),
                            status = obj.optString("status", "active"),
                            createdBy = obj.optString("createdBy", "المدير"),
                            createdAt = createdAt
                        )
                    )
                }
                if (list.isNotEmpty()) dao.upsertInvoicePayments(list)
            }

            // Restore Movements (strictly excluding tombstoned & pre-epoch records)
            val movArray = root.optJSONArray("movements")
            if (movArray != null && movArray.length() > 0) {
                val list = mutableListOf<MovementEntity>()
                for (i in 0 until movArray.length()) {
                    val obj = movArray.getJSONObject(i)
                    val mov = MovementEntity(
                        id = obj.optLong("id"),
                        txDate = obj.optString("txDate"),
                        cashbox = obj.optString("cashbox"),
                        currency = obj.optString("currency", "SDG"),
                        direction = obj.optString("direction"),
                        amount = obj.optDouble("amount"),
                        description = obj.optString("description"),
                        reference = obj.optString("reference"),
                        createdBy = obj.optString("createdBy", "المدير"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        cloudDocId = obj.optString("cloudDocId", ""),
                        syncUuid = obj.optString("syncUuid", ""),
                        updatedAt = obj.optLong("updatedAt", 0L)
                    )
                    if (!DeletedRecordsRegistry.isMovementDeleted(mov)) {
                        list.add(mov)
                    }
                }
                if (list.isNotEmpty()) dao.upsertMovements(list)
            }

            // Restore Customers (excluding tombstoned)
            val custArray = root.optJSONArray("customers")
            if (custArray != null && custArray.length() > 0) {
                val list = mutableListOf<CustomerEntity>()
                for (i in 0 until custArray.length()) {
                    val obj = custArray.getJSONObject(i)
                    val custId = obj.optLong("id")
                    val custName = obj.optString("name")
                    val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    if (DeletedRecordsRegistry.isPreEpoch(createdAt) ||
                        DeletedRecordsRegistry.isDeleted("customers", custId) ||
                        DeletedRecordsRegistry.isDeleted("customers", "CUST-$custId") ||
                        (custName.isNotBlank() && DeletedRecordsRegistry.isDeleted("customers", custName))
                    ) {
                        continue
                    }
                    list.add(
                        CustomerEntity(
                            id = custId,
                            name = custName,
                            phone = obj.optString("phone"),
                            address = obj.optString("address"),
                            notes = obj.optString("notes"),
                            active = obj.optBoolean("active", true),
                            createdAt = createdAt
                        )
                    )
                }
                if (list.isNotEmpty()) dao.upsertCustomers(list)
            }

            // Restore Lab Formulations if empty (excluding tombstoned)
            val frmArray = root.optJSONArray("lab_formulations")
            if (frmArray != null && frmArray.length() > 0 && dao.getFormulationsDirect().isEmpty()) {
                val list = mutableListOf<FormulationEntity>()
                for (i in 0 until frmArray.length()) {
                    val obj = frmArray.getJSONObject(i)
                    val fId = obj.optLong("id")
                    val fNo = obj.optString("formulaNo")
                    val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    if (DeletedRecordsRegistry.isPreEpoch(createdAt) ||
                        DeletedRecordsRegistry.isDeleted("lab_formulations", fId) ||
                        DeletedRecordsRegistry.isDeleted("lab_formulations", "FORM-$fId") ||
                        (fNo.isNotBlank() && DeletedRecordsRegistry.isDeleted("lab_formulations", fNo))
                    ) {
                        continue
                    }
                    val prodIdRaw = obj.optLong("linkedProductId", -1L)
                    list.add(
                        FormulationEntity(
                            id = fId,
                            formulaNo = fNo,
                            productName = obj.optString("productName"),
                            paintType = obj.optString("paintType", "مطفي معماري (Matt)"),
                            baseKind = obj.optString("baseKind", "أبيض"),
                            targetPvc = obj.optDouble("targetPvc", 0.0),
                            calculatedPvc = obj.optDouble("calculatedPvc", 0.0),
                            cpvcEstimated = obj.optDouble("cpvcEstimated", 52.0),
                            totalSolidsWeight = obj.optDouble("totalSolidsWeight", 0.0),
                            totalSolidsVolume = obj.optDouble("totalSolidsVolume", 0.0),
                            totalBatchWeight = obj.optDouble("totalBatchWeight", 0.0),
                            totalBatchVolume = obj.optDouble("totalBatchVolume", 0.0),
                            calculatedDensity = obj.optDouble("calculatedDensity", 1.0),
                            totalCost = obj.optDouble("totalCost", 0.0),
                            costPerKg = obj.optDouble("costPerKg", 0.0),
                            ingredientsJson = obj.optString("ingredientsJson", "[]"),
                            technicalAnalysis = obj.optString("technicalAnalysis", ""),
                            notes = obj.optString("notes", ""),
                            approvedForProduction = obj.optBoolean("approvedForProduction", false),
                            createdBy = obj.optString("createdBy", "مهندس المختبر الكيميائي"),
                            createdAt = createdAt,
                            syncUuid = obj.optString("syncUuid", "FRM-UUID-$fId"),
                            linkedProductId = if (prodIdRaw > 0L) prodIdRaw else null,
                            linkedProductSku = obj.optString("linkedProductSku", ""),
                            category = obj.optString("category", "معجونات ودهانات"),
                            versionNumber = obj.optInt("versionNumber", 1),
                            approvalStatus = obj.optString("approvalStatus", "DRAFT"),
                            approvedBy = obj.optString("approvedBy", ""),
                            approvedAt = obj.optLong("approvedAt", 0L),
                            historicalTotalCost = obj.optDouble("historicalTotalCost", 0.0),
                            historicalCostPerKg = obj.optDouble("historicalCostPerKg", 0.0),
                            currency = obj.optString("currency", "EGP"),
                            changeReason = obj.optString("changeReason", ""),
                            updatedBy = obj.optString("updatedBy", ""),
                            updatedAt = obj.optLong("updatedAt", createdAt),
                            confidentialCiphertext = obj.optString("confidentialCiphertext", ""),
                            isConfidentialSealed = obj.optBoolean("isConfidentialSealed", false)
                        )
                    )
                }
                if (list.isNotEmpty()) dao.upsertFormulations(list)
            }

            if (labDao != null) {
                val verArray = root.optJSONArray("lab_formula_versions")
                if (verArray != null && verArray.length() > 0 && labDao.getAllFormulaVersionsDirect().isEmpty()) {
                    val vList = mutableListOf<FormulationVersionEntity>()
                    for (i in 0 until verArray.length()) {
                        val o = verArray.getJSONObject(i)
                        vList.add(
                            FormulationVersionEntity(
                                id = o.optLong("id"),
                                syncUuid = o.optString("syncUuid"),
                                formulaId = o.optLong("formulaId"),
                                formulaNo = o.optString("formulaNo"),
                                productName = o.optString("productName"),
                                versionNumber = o.optInt("versionNumber", 1),
                                previousVersionNumber = o.optInt("previousVersionNumber", 0),
                                status = o.optString("status", "NEEDS_REVIEW"),
                                changeReason = o.optString("changeReason", ""),
                                diffSummaryJson = o.optString("diffSummaryJson", "{}"),
                                previousCostPerKg = o.optDouble("previousCostPerKg", 0.0),
                                newCostPerKg = o.optDouble("newCostPerKg", 0.0),
                                historicalTotalCost = o.optDouble("historicalTotalCost", 0.0),
                                calculatedPvc = o.optDouble("calculatedPvc", 0.0),
                                calculatedDensity = o.optDouble("calculatedDensity", 1.0),
                                currency = o.optString("currency", "EGP"),
                                ingredientsSnapshotJson = o.optString("ingredientsSnapshotJson", "[]"),
                                encryptedSnapshotCiphertext = o.optString("encryptedSnapshotCiphertext", ""),
                                createdBy = o.optString("createdBy", ""),
                                approvedBy = o.optString("approvedBy", ""),
                                approvedAt = o.optLong("approvedAt", 0L),
                                createdAt = o.optLong("createdAt", System.currentTimeMillis())
                            )
                        )
                    }
                    if (vList.isNotEmpty()) labDao.upsertFormulaVersions(vList)
                }

                val expArray = root.optJSONArray("lab_experiments")
                if (expArray != null && expArray.length() > 0 && labDao.getAllLabExperimentsDirect().isEmpty()) {
                    val eList = mutableListOf<LabExperimentEntity>()
                    for (i in 0 until expArray.length()) {
                        val o = expArray.getJSONObject(i)
                        val fvId = o.optLong("formulaVersionId", -1L)
                        val pId = o.optLong("productId", -1L)
                        eList.add(
                            LabExperimentEntity(
                                id = o.optLong("id"),
                                syncUuid = o.optString("syncUuid"),
                                experimentNo = o.optString("experimentNo"),
                                formulaId = o.optLong("formulaId"),
                                formulaNo = o.optString("formulaNo"),
                                formulaVersionId = if (fvId > 0L) fvId else null,
                                versionNumber = o.optInt("versionNumber", 1),
                                productId = if (pId > 0L) pId else null,
                                productName = o.optString("productName"),
                                experimentDate = o.optString("experimentDate"),
                                technicianName = o.optString("technicianName"),
                                materialsUsedJson = o.optString("materialsUsedJson", "[]"),
                                measuredDensity = o.optDouble("measuredDensity", 0.0),
                                measuredViscosity = o.optString("measuredViscosity", ""),
                                measuredPh = o.optDouble("measuredPh", 0.0),
                                measuredGrindMicrons = o.optDouble("measuredGrindMicrons", 0.0),
                                hidingPowerPercent = o.optDouble("hidingPowerPercent", 0.0),
                                notes = o.optString("notes", ""),
                                status = o.optString("status", "PENDING"),
                                decision = o.optString("decision", "PENDING"),
                                decidedBy = o.optString("decidedBy", ""),
                                decidedAt = o.optLong("decidedAt", 0L),
                                linkedQcNo = o.optString("linkedQcNo", ""),
                                idempotencyKey = o.optString("idempotencyKey", ""),
                                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                            )
                        )
                    }
                    if (eList.isNotEmpty()) labDao.upsertLabExperiments(eList)
                }

                val phArray = root.optJSONArray("raw_material_price_history")
                if (phArray != null && phArray.length() > 0 && labDao.getAllRawMaterialPriceHistoryDirect().isEmpty()) {
                    val phList = mutableListOf<RawMaterialPriceHistoryEntity>()
                    for (i in 0 until phArray.length()) {
                        val o = phArray.getJSONObject(i)
                        phList.add(
                            RawMaterialPriceHistoryEntity(
                                id = o.optLong("id"),
                                syncUuid = o.optString("syncUuid"),
                                materialId = o.optLong("materialId"),
                                itemCode = o.optString("itemCode", ""),
                                materialName = o.optString("materialName"),
                                supplierName = o.optString("supplierName", ""),
                                unitPrice = o.optDouble("unitPrice", 0.0),
                                previousPrice = o.optDouble("previousPrice", 0.0),
                                currency = o.optString("currency", "EGP"),
                                sourceType = o.optString("sourceType", "MANUAL_LAB"),
                                sourceDocNo = o.optString("sourceDocNo", ""),
                                effectiveDate = o.optString("effectiveDate", ""),
                                recordedBy = o.optString("recordedBy", ""),
                                createdAt = o.optLong("createdAt", System.currentTimeMillis())
                            )
                        )
                    }
                    if (phList.isNotEmpty()) labDao.upsertPriceHistories(phList)
                }

                val xlsArray = root.optJSONArray("lab_excel_imports")
                if (xlsArray != null && xlsArray.length() > 0 && labDao.getAllExcelImportsDirect().isEmpty()) {
                    val xlsList = mutableListOf<LabExcelImportBatchEntity>()
                    for (i in 0 until xlsArray.length()) {
                        val o = xlsArray.getJSONObject(i)
                        xlsList.add(
                            LabExcelImportBatchEntity(
                                id = o.optLong("id"),
                                syncUuid = o.optString("syncUuid"),
                                importNo = o.optString("importNo"),
                                fileName = o.optString("fileName"),
                                sheetCount = o.optInt("sheetCount", 0),
                                formulasDetected = o.optInt("formulasDetected", 0),
                                formulasImported = o.optInt("formulasImported", 0),
                                materialsMatched = o.optInt("materialsMatched", 0),
                                materialsCreated = o.optInt("materialsCreated", 0),
                                externalLinksDetected = o.optInt("externalLinksDetected", 0),
                                brokenRefsDetected = o.optInt("brokenRefsDetected", 0),
                                warningsCount = o.optInt("warningsCount", 0),
                                approvalStatus = o.optString("approvalStatus", "NEEDS_REVIEW"),
                                checksumSha256 = o.optString("checksumSha256", ""),
                                restrictedSummaryJson = o.optString("restrictedSummaryJson", "{}"),
                                referenceArchivePath = o.optString("referenceArchivePath", ""),
                                importedBy = o.optString("importedBy", ""),
                                idempotencyKey = o.optString("idempotencyKey", ""),
                                createdAt = o.optLong("createdAt", System.currentTimeMillis())
                            )
                        )
                    }
                    if (xlsList.isNotEmpty()) {
                        xlsList.forEach { labDao.upsertExcelImport(it) }
                    }
                }
            }

            Log.i(TAG, "Successfully restored ERP data from persistent snapshot v7 with tombstone protection!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring from persistent snapshot", e)
            false
        }
    }
}
