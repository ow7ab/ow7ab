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
    suspend fun saveLocalSnapshot(context: Context, dao: PmpDao): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            DeletedAccountsRegistry.init(context)
            DeletedRecordsRegistry.init(context)
            DeletedRecordsRegistry.syncWithDao(dao)

            val root = JSONObject()
            root.put("version", 6)
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

            synchronized(snapshotLock) {
                val file = File(context.filesDir, BACKUP_FILE_NAME)
                val tmpFile = File(context.filesDir, "$BACKUP_FILE_NAME.tmp")
                tmpFile.writeText(root.toString(2))
                if (!tmpFile.renameTo(file)) {
                    file.writeText(root.toString(2))
                    tmpFile.delete()
                }
                Log.d(TAG, "Saved persistent ERP snapshot v6 with ${invoices.size} invoices and ${movements.size} movements to ${file.absolutePath}")
                file
            }
        }
    }

    /**
     * Checks if the database is newly initialized or empty, and restores previous data from the snapshot,
     * applying all account & ERP record tombstones FIRST so deleted records never resurrect.
     */
    suspend fun restoreIfEmpty(context: Context, dao: PmpDao): Boolean = withContext(Dispatchers.IO) {
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

            Log.i(TAG, "Successfully restored ERP data from persistent snapshot v6 with tombstone protection!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring from persistent snapshot", e)
            false
        }
    }
}
