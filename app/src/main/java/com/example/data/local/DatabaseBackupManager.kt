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

    /**
     * Creates an offline JSON snapshot of all core ERP tables.
     * Stored in the app's permanent internal storage which survives APK version upgrades.
     */
    suspend fun saveLocalSnapshot(context: Context, dao: PmpDao): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject()
            root.put("version", 5)
            root.put("timestamp", System.currentTimeMillis())

            // 1. Invoices
            val invoices = dao.getInvoicesDirect()
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

            // 2. Invoice Items
            val items = dao.getInvoiceItemsDirect()
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

            // 3. Invoice Payments
            val payments = dao.getInvoicePaymentsDirect()
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

            // 4. Movements
            val movements = dao.getMovementsDirect()
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
                })
            }
            root.put("movements", movArray)

            // 5. Customers
            val customers = dao.getCustomersDirect()
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

            // 6. Products
            val products = dao.getProductsDirect()
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

            // 7. Cashboxes
            val cashboxes = dao.getCashboxesDirect()
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
                        put("isCurrentSession", false) // Never leak active session flag across devices or exports
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

            val file = File(context.filesDir, BACKUP_FILE_NAME)
            file.writeText(root.toString(2))
            Log.d(TAG, "Saved persistent ERP snapshot with ${invoices.size} invoices and ${movements.size} movements to ${file.absolutePath}")
            file
        }
    }

    /**
     * Checks if the database is newly initialized or empty, and restores previous data from the snapshot.
     */
    suspend fun restoreIfEmpty(context: Context, dao: PmpDao): Boolean = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, BACKUP_FILE_NAME)
        if (!file.exists()) return@withContext false

        DeletedAccountsRegistry.init(context)

        val existingInvoices = dao.getInvoicesDirect()
        val existingMovements = dao.getMovementsDirect()
        val hasTransactions = existingInvoices.isNotEmpty() || existingMovements.isNotEmpty()

        try {
            val jsonText = file.readText()
            val root = JSONObject(jsonText)

            // 0. Load any tombstones first
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
                // Transactions already populated, accounts successfully merged
                return@withContext true
            }

            // Restore Invoices
            val invArray = root.optJSONArray("invoices")
            if (invArray != null && invArray.length() > 0) {
                val list = mutableListOf<InvoiceEntity>()
                for (i in 0 until invArray.length()) {
                    val obj = invArray.getJSONObject(i)
                    list.add(
                        InvoiceEntity(
                            id = obj.optLong("id"),
                            invoiceNo = obj.optString("invoiceNo"),
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
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                dao.upsertInvoices(list)
            }

            // Restore Invoice Items
            val itemArray = root.optJSONArray("invoice_items")
            if (itemArray != null && itemArray.length() > 0) {
                val list = mutableListOf<InvoiceItemEntity>()
                for (i in 0 until itemArray.length()) {
                    val obj = itemArray.getJSONObject(i)
                    val prodId = obj.optLong("productId", -1L)
                    val whId = obj.optLong("warehouseId", -1L)
                    list.add(
                        InvoiceItemEntity(
                            id = obj.optLong("id"),
                            invoiceId = obj.optLong("invoiceId"),
                            description = obj.optString("description"),
                            quantity = obj.optDouble("quantity", 1.0),
                            unitPrice = obj.optDouble("unitPrice"),
                            lineTotal = obj.optDouble("lineTotal"),
                            productId = if (prodId > 0) prodId else null,
                            warehouseId = if (whId > 0) whId else null
                        )
                    )
                }
                dao.upsertInvoiceItems(list)
            }

            // Restore Invoice Payments
            val payArray = root.optJSONArray("invoice_payments")
            if (payArray != null && payArray.length() > 0) {
                val list = mutableListOf<InvoicePaymentEntity>()
                for (i in 0 until payArray.length()) {
                    val obj = payArray.getJSONObject(i)
                    list.add(
                        InvoicePaymentEntity(
                            id = obj.optLong("id"),
                            invoiceId = obj.optLong("invoiceId"),
                            payDate = obj.optString("payDate"),
                            cashbox = obj.optString("cashbox"),
                            amount = obj.optDouble("amount"),
                            note = obj.optString("note"),
                            status = obj.optString("status", "active"),
                            createdBy = obj.optString("createdBy", "المدير"),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                dao.upsertInvoicePayments(list)
            }

            // Restore Movements
            val movArray = root.optJSONArray("movements")
            if (movArray != null && movArray.length() > 0) {
                val list = mutableListOf<MovementEntity>()
                for (i in 0 until movArray.length()) {
                    val obj = movArray.getJSONObject(i)
                    list.add(
                        MovementEntity(
                            id = obj.optLong("id"),
                            txDate = obj.optString("txDate"),
                            cashbox = obj.optString("cashbox"),
                            currency = obj.optString("currency", "SDG"),
                            direction = obj.optString("direction"),
                            amount = obj.optDouble("amount"),
                            description = obj.optString("description"),
                            reference = obj.optString("reference"),
                            createdBy = obj.optString("createdBy", "المدير"),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                dao.upsertMovements(list)
            }

            // Restore Customers
            val custArray = root.optJSONArray("customers")
            if (custArray != null && custArray.length() > 0) {
                val list = mutableListOf<CustomerEntity>()
                for (i in 0 until custArray.length()) {
                    val obj = custArray.getJSONObject(i)
                    list.add(
                        CustomerEntity(
                            id = obj.optLong("id"),
                            name = obj.optString("name"),
                            phone = obj.optString("phone"),
                            address = obj.optString("address"),
                            notes = obj.optString("notes"),
                            active = obj.optBoolean("active", true),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                dao.upsertCustomers(list)
            }

            Log.i(TAG, "Successfully restored ERP data from persistent snapshot!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring from persistent snapshot", e)
            false
        }
    }
}
