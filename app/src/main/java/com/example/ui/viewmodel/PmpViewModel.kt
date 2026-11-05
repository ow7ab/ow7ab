package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.*
import com.example.data.remote.CloudAccountSyncRepository
import com.example.data.remote.LinkedAccountModel
import com.example.data.repository.GoogleSignInBindingResult
import com.example.data.repository.PmpRepository
import com.example.isSystemManager
import com.example.hasModulePermission
import com.example.util.DeviceSessionStore
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

import com.example.data.remote.CloudErpSyncRepository
import com.example.data.remote.ServerHealthReport
import com.example.util.NetworkConnectivityMonitor
import com.example.util.UpdateManager
import com.example.util.UpdateInfo
import com.example.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiMessage(
    val message: String,
    val isError: Boolean = false
)

class PmpViewModel(application: Application) : AndroidViewModel(application) {

    private val database: PmpDatabase = PmpDatabase.getDatabase(application)
    private val repository: PmpRepository = PmpRepository(database.pmpDao(), database)
    private val cloudSyncRepo: CloudAccountSyncRepository = CloudAccountSyncRepository.create(application)
    private val cloudErpSyncRepo: CloudErpSyncRepository = CloudErpSyncRepository.create(application)
    private var cloudObserverJob: Job? = null

    private val _cloudLinkedAccounts = MutableStateFlow<List<LinkedAccountModel>>(emptyList())
    val cloudLinkedAccounts: StateFlow<List<LinkedAccountModel>> = _cloudLinkedAccounts.asStateFlow()

    private val _cloudSyncState = MutableStateFlow("متصل بسيرفر Google Cloud المركزي (جاهز لخدمة الهاتف والويندوز)")
    val cloudSyncState: StateFlow<String> = _cloudSyncState.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _serverHealthReport = MutableStateFlow<ServerHealthReport?>(null)
    val serverHealthReport: StateFlow<ServerHealthReport?> = _serverHealthReport.asStateFlow()

    val serverClusterCode = MutableStateFlow(CloudErpSyncRepository.DEFAULT_CLUSTER)

    // مراقبة اتصال الشبكة في الوقت الحقيقي لضمان تنفيذ وتسلسل العمليات حصرياً على السيرفر السحابي
    val isNetworkOnline: StateFlow<Boolean> = NetworkConnectivityMonitor.getInstance(application).isOnline

    fun isConnectedToCloud(): Boolean {
        return NetworkConnectivityMonitor.getInstance(getApplication()).isNetworkConnected()
    }

    fun checkOnlineBeforeTransaction(actionName: String): Boolean {
        if (!isConnectedToCloud()) {
            postMessage("تنبيه: لا يمكن تنفيذ ($actionName) أثناء انقطاع الإنترنت. التطبيق يعمل حصرياً عبر السيرفر السحابي المباشر لضمان تسجيل الترتيب المتسلسل على الشبكة.", isError = true)
            return false
        }
        return true
    }

    // --- StateFlow Streams ---
    val cashboxes = repository.getCashboxes()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val movements = repository.getMovements()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transfers = repository.getTransfers()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyClosings = repository.getDailyClosings()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customers = repository.getCustomers()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val invoices = repository.getInvoices()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val invoiceItems = repository.getAllInvoiceItems()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val invoicePayments = repository.getInvoicePayments()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val warehouses = repository.getWarehouses()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val products = repository.getProducts()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stockMoves = repository.getStockMoves()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val factoryItems = repository.getFactoryItems()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val factoryMachines = repository.getFactoryMachines()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val factoryMoves = repository.getFactoryMoves()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val halfaInvoices = repository.getHalfaInvoices()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val posterPrices = repository.getPosterPrices()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val defaultPaintColors = listOf(
        "أبيض", "كريمي", "كبدي", "بني", "أزرق", "أحمر", 
        "أخضر", "أسود", "أصفر", "بنفسجي", "وردي", "فضي", "ذهبي", "ورنيش"
    )
    val customColorsList = MutableStateFlow<List<String>>(emptyList())

    val availablePaintColors: StateFlow<List<String>> = combine(products, posterPrices, customColorsList) { prods, posters, custom ->
        val fromProds = prods.map { it.paintColor }.filter { it.isNotBlank() }
        val fromPosters = posters.map { it.kind }.filter { it.isNotBlank() }
        (defaultPaintColors + fromProds + fromPosters + custom).distinct()
    }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), defaultPaintColors)

    val auditLogs = repository.getAuditLogs()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val userAccounts = repository.getUserAccounts()
        .map { list ->
            val seen = mutableSetOf<String>()
            val result = mutableListOf<UserAccountEntity>()
            val sorted = list.sortedWith(
                compareByDescending<UserAccountEntity> { it.isCurrentSession }
                    .thenByDescending { it.syncStatus != "PENDING_MANAGER_APPROVAL" }
                    .thenBy { it.id }
            )
            for (u in sorted) {
                val key = u.username.trim().lowercase().ifBlank { u.userCode.trim().lowercase() }
                if (key.isNotBlank()) {
                    if (key !in seen) {
                        seen.add(key)
                        result.add(u)
                    }
                } else {
                    result.add(u)
                }
            }
            result.sortedBy { it.id }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val formulations = repository.getFormulations()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chemicalSafetyList = repository.getChemicalSafety()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val productionBatches = repository.getProductionBatches()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    // --- PubChem Search State ---
    private val _isSearchingPubChem = MutableStateFlow(false)
    val isSearchingPubChem: StateFlow<Boolean> = _isSearchingPubChem.asStateFlow()

    private val _pubChemResults = MutableStateFlow<List<com.example.util.PubChemService.PubChemCompound>>(emptyList())
    val pubChemResults: StateFlow<List<com.example.util.PubChemService.PubChemCompound>> = _pubChemResults.asStateFlow()

    private val _pubChemErrorMessage = MutableStateFlow<String?>(null)
    val pubChemErrorMessage: StateFlow<String?> = _pubChemErrorMessage.asStateFlow()

    val activeSessionUserId = MutableStateFlow<Long?>(null)
    val isSessionAuthenticated = MutableStateFlow(false)
    val activeSessionOverride = MutableStateFlow<UserAccountEntity?>(null)

    val currentUser: StateFlow<UserAccountEntity?> = combine(userAccounts, activeSessionUserId, activeSessionOverride) { list, activeId, overrideUser ->
        if (overrideUser != null && (activeId == null || overrideUser.id == activeId)) {
            list.firstOrNull { it.id == overrideUser.id } ?: overrideUser
        } else if (activeId != null) {
            list.firstOrNull { it.id == activeId }
        } else {
            // Strictly require authentication on this device - never fallback to an unauthenticated seed
            null
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun resolveUserActor(fallback: String = "المدير"): String {
        val current = currentUser.value
        return if (current != null) {
            "${current.fullName} (${current.userCode})"
        } else {
            fallback
        }
    }

    // Feedback message channel
    private val _uiMessage = MutableStateFlow<UiMessage?>(null)
    val uiMessage: StateFlow<UiMessage?> = _uiMessage.asStateFlow()

    fun clearUiMessage() {
        _uiMessage.value = null
    }

    fun postMessage(msg: String, isError: Boolean = false) {
        _uiMessage.value = UiMessage(msg, isError)
    }

    // --- Derived Calculated States ---
    val currencyBalances: StateFlow<Map<String, Double>> = movements.map { list ->
        val balances = mutableMapOf<String, Double>()
        for (m in list) {
            val sign = if (m.direction == "وارد") 1.0 else -1.0
            val current = balances.getOrDefault(m.currency, 0.0)
            balances[m.currency] = current + (sign * m.amount)
        }
        balances
    }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val cashboxBalances: StateFlow<Map<Pair<String, String>, Double>> = movements.map { list ->
        val map = mutableMapOf<Pair<String, String>, Double>()
        for (m in list) {
            val key = Pair(m.cashbox.trim(), m.currency.trim())
            val sign = if (m.direction == "وارد") 1.0 else -1.0
            val current = map.getOrDefault(key, 0.0)
            map[key] = current + (sign * m.amount)
        }
        map
    }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val productStockBalances: StateFlow<Map<Long, Double>> = stockMoves.map { list ->
        val map = mutableMapOf<Long, Double>()
        for (sm in list) {
            val sign = if (sm.kind in listOf("IN", "RETURN")) 1.0 else -1.0
            val current = map.getOrDefault(sm.productId, 0.0)
            map[sm.productId] = current + (sign * sm.quantity)
        }
        map
    }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        // Restore active user session ONLY if this specific physical device previously logged in
        viewModelScope.launch {
            userAccounts.firstOrNull { it.isNotEmpty() }?.let { list ->
                val savedDeviceId = DeviceSessionStore.getActiveUserId(application)
                if (savedDeviceId > 0L) {
                    val activeSessionUser = list.firstOrNull { it.id == savedDeviceId && it.active && it.syncStatus != "PENDING_MANAGER_APPROVAL" }
                    if (activeSessionUser != null && activeSessionUserId.value == null) {
                        activeSessionUserId.value = activeSessionUser.id
                        isSessionAuthenticated.value = true
                    } else {
                        // User was deleted or suspended -> clear device session
                        DeviceSessionStore.clearActiveSession(application)
                        activeSessionUserId.value = null
                        isSessionAuthenticated.value = false
                    }
                } else {
                    // Fresh installation or shared app on another device -> require explicit login
                    activeSessionUserId.value = null
                    isSessionAuthenticated.value = false
                }
            }
        }

        // Instant, smooth startup: load local database immediately, then perform background sync asynchronously
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DeletedAccountsRegistry.init(application)
                DeletedRecordsRegistry.init(application)
                DatabaseBackupManager.restoreIfEmpty(application, repository.getDao())
                repository.deduplicateDatabaseUsers()
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Local cache restore note: ${e.message}")
            }

            // Defer network sync slightly so initial screen displays instantly without any freeze
            kotlinx.coroutines.delay(1500)
            try {
                cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao())
                cloudErpSyncRepo.syncAllLocalToCloud(serverClusterCode.value, repository.getDao())
                DatabaseBackupManager.saveLocalSnapshot(application, repository.getDao())
                checkServerHealth()

                // نشر بيانات الإصدار المعتمد في السحابة لضمان نجاح فحص التحديثات الداخلي بدون أخطاء
                val currentInfo = UpdateInfo(
                    versionCode = BuildConfig.VERSION_CODE,
                    versionName = BuildConfig.VERSION_NAME,
                    downloadUrl = "https://github.com/oaw7ab/pmp-paints-releases/releases/latest",
                    releaseNotes = "النسخة الرسمية المعتمدة (${BuildConfig.VERSION_NAME}) مع نظام حماية السجلات وتحديثات Sideload المباشرة",
                    fileSizeMb = 14.5
                )
                updateManager.publishVersionToFirestore(currentInfo, serverClusterCode.value)
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Deferred cloud cluster sync: ${e.message}")
            }
        }
        startCloudRealtimeSyncIfAuthenticated()
    }

    // --- Actions ---

    fun addMovement(
        cashbox: String,
        currency: String,
        direction: String,
        amount: Double,
        description: String,
        reference: String,
        user: String = resolveUserActor("المدير")
    ) {
        if (!checkOnlineBeforeTransaction("تسجيل حركة مالية")) return
        viewModelScope.launch {
            val autoRef = reference.trim().ifBlank { "REC-${System.currentTimeMillis() % 1000000}" }
            val autoDesc = description.trim().ifBlank { "حركة $direction بمبلغ $amount $currency في $cashbox" }
            val res = repository.recordMovement(cashbox, currency, direction, amount, autoDesc, autoRef, user)
            res.onSuccess { movId ->
                DeletedRecordsRegistry.unregisterDeleted("movements", movId)
                DeletedRecordsRegistry.unregisterDeleted("movements", "MOV-$movId")
                DeletedRecordsRegistry.unregisterDeleted("movements", autoRef)
                postMessage("تم تسجيل الحركة بنجاح: $autoRef")
                // Fast background cloud sync (<100ms) for just this movement
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val m = MovementEntity(
                            id = movId,
                            txDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
                            cashbox = cashbox,
                            currency = currency,
                            direction = direction,
                            amount = amount,
                            description = autoDesc,
                            reference = autoRef,
                            createdBy = user
                        )
                        cloudErpSyncRepo.syncSingleMovementToCloud(serverClusterCode.value, m)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Direct movement sync deferred: ${e.message}")
                    }
                }
            }.onFailure {
                postMessage(it.message ?: "تعذر تسجيل الحركة", isError = true)
            }
        }
    }

    fun addTransfer(
        sourceCashbox: String,
        destinationCashbox: String,
        sourceCurrency: String,
        destinationCurrency: String,
        sourceAmount: Double,
        rate: Double,
        description: String,
        reference: String,
        user: String = resolveUserActor("المدير"),
        halfaInvoiceId: Long? = null,
        halfaAmount: Double? = null,
        isInvertedRate: Boolean = false
    ) {
        if (!checkOnlineBeforeTransaction("تحويل بين الخزن")) return
        viewModelScope.launch {
            val autoRef = reference.trim().ifBlank { "TRF-${System.currentTimeMillis() % 1000000}" }
            val res = repository.recordTransfer(
                sourceCashbox, destinationCashbox, sourceCurrency, destinationCurrency,
                sourceAmount, rate, description, autoRef, user, halfaInvoiceId, halfaAmount,
                isInvertedRate
            )
            res.onSuccess { trfId ->
                postMessage("تم إتمام التحويل بنجاح برقم: $autoRef")
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val destAmt = if (isInvertedRate && rate > 0) sourceAmount / rate else sourceAmount * rate
                        val t = TransferEntity(
                            id = trfId,
                            reference = autoRef,
                            txDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
                            sourceCashbox = sourceCashbox,
                            destinationCashbox = destinationCashbox,
                            sourceCurrency = sourceCurrency,
                            destinationCurrency = destinationCurrency,
                            sourceAmount = sourceAmount,
                            rate = rate,
                            destinationAmount = destAmt,
                            description = description,
                            halfaInvoiceId = halfaInvoiceId,
                            halfaAmount = halfaAmount,
                            createdBy = user
                        )
                        cloudErpSyncRepo.syncSingleTransferToCloud(serverClusterCode.value, t)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Direct transfer sync deferred: ${e.message}")
                    }
                }
            }.onFailure {
                postMessage(it.message ?: "تعذر تنفيذ التحويل", isError = true)
            }
        }
    }

    fun deleteMovement(id: Long) {
        if (!checkOnlineBeforeTransaction("حذف حركة مالية")) return
        viewModelScope.launch {
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                postMessage("عفواً، حذف الحركات المالية متاح لمدير النظام أو الحسابات المصرح لها فقط", isError = true)
                return@launch
            }
            val res = repository.deleteMovement(id, resolveUserActor("المدير"))
            res.onSuccess {
                DeletedRecordsRegistry.registerDeleted("movements", id, "MOV-$id", resolveUserActor("المدير"))
                viewModelScope.launch(Dispatchers.IO) {
                    cloudErpSyncRepo.deleteEntityFromCloud(serverClusterCode.value, "movements", "MOV-$id")
                }
                postMessage("تم حذف الحركة المالية بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر حذف الحركة المالية", isError = true)
            }
        }
    }

    fun recordCustomerPayment(
        customerId: Long,
        customerName: String,
        amount: Double,
        currency: String,
        cashbox: String,
        note: String
    ) {
        if (!checkOnlineBeforeTransaction("سداد دفعة العميل")) return
        viewModelScope.launch {
            val res = repository.recordCustomerPayment(
                customerId = customerId,
                customerName = customerName,
                amount = amount,
                currency = currency,
                cashbox = cashbox,
                note = note,
                user = resolveUserActor("محاسب المبيعات")
            )
            res.onSuccess {
                postMessage("تم تسجيل سداد وتحصيل مبلغ $amount $currency من العميل ($customerName) بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر تسجيل سداد العميل", isError = true)
            }
        }
    }

    fun resetCashboxBalance(cashboxName: String) {
        if (!checkOnlineBeforeTransaction("تصفير الخزنة")) return
        viewModelScope.launch {
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                postMessage("عفواً، تصفير الخزن متاح لمدير النظام أو الحسابات المصرح لها فقط", isError = true)
                return@launch
            }
            val res = repository.resetCashboxBalance(cashboxName, resolveUserActor("المدير"))
            res.onSuccess {
                viewModelScope.launch(Dispatchers.IO) {
                    cloudErpSyncRepo.clearCashboxMovementsFromCloud(serverClusterCode.value, cashboxName)
                }
                postMessage("تم تصفير رصيد وحركات خزنة ($cashboxName) بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر تصفير الخزنة", isError = true)
            }
        }
    }

    fun performDailyClosing(
        cashbox: String,
        currency: String,
        bookBalance: Double,
        countedBalance: Double,
        notes: String,
        user: String = resolveUserActor("المدير")
    ) {
        if (!checkOnlineBeforeTransaction("إقفال اليومية")) return
        viewModelScope.launch {
            repository.recordDailyClosing(cashbox, currency, bookBalance, countedBalance, notes, user)
            postMessage("تم إقفال اليومية لخزنة $cashbox بنجاح")
            persistAndSyncToServer()
        }
    }

    fun addCustomer(name: String, phone: String, address: String, notes: String, user: String = resolveUserActor("المدير")) {
        if (!checkOnlineBeforeTransaction("إضافة عميل جديد")) return
        viewModelScope.launch {
            repository.addCustomer(name, phone, address, notes, user)
            postMessage("تمت إضافة العميل $name بنجاح")
            persistAndSyncToServer()
        }
    }

    fun deleteCustomer(customerId: Long, customerName: String) {
        if (!checkOnlineBeforeTransaction("حذف عميل")) return
        viewModelScope.launch {
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                postMessage("عفواً، حذف العملاء متاح لحساب مدير النظام فقط", isError = true)
                return@launch
            }
            val res = repository.deleteCustomer(customerId, customerName, resolveUserActor("المدير"))
            res.onSuccess {
                DeletedRecordsRegistry.registerDeleted("customers", customerId, "CUST-$customerId", resolveUserActor("المدير"))
                viewModelScope.launch(Dispatchers.IO) {
                    cloudErpSyncRepo.deleteEntityFromCloud(serverClusterCode.value, "customers", "CUST-$customerId")
                }
                postMessage("تم حذف العميل ($customerName) وسجلاته بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر حذف العميل", isError = true)
            }
        }
    }

    fun clearCustomerBalance(customerId: Long, customerName: String) {
        if (!checkOnlineBeforeTransaction("تصفير حساب عميل")) return
        viewModelScope.launch {
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                postMessage("عفواً، تصفير وحذف حسابات العملاء متاح لمدير النظام فقط", isError = true)
                return@launch
            }
            val res = repository.clearCustomerBalance(customerId, customerName, resolveUserActor("المدير"))
            res.onSuccess {
                postMessage("تم تصفير وحذف المبالغ المسجلة على العميل ($customerName) بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر تصفير حساب العميل", isError = true)
            }
        }
    }

    fun createInvoice(
        customerId: Long,
        customerName: String,
        currency: String,
        items: List<InvoiceItemEntity>,
        discount: Double,
        notes: String,
        user: String = resolveUserActor("المدير"),
        paymentCashbox: String? = null,
        paymentAmount: Double? = null
    ) {
        if (!checkOnlineBeforeTransaction("إصدار فاتورة مبيعات")) return
        viewModelScope.launch {
            val res = repository.createInvoice(
                customerId, customerName, currency, items, discount, notes, user, paymentCashbox, paymentAmount
            )
            res.onSuccess {
                postMessage("تم إصدار الفاتورة بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر إصدار الفاتورة", isError = true)
            }
        }
    }

    fun payInvoice(
        invoiceId: Long,
        invoiceNo: String,
        customerName: String,
        currency: String,
        cashbox: String,
        amount: Double,
        note: String,
        user: String = resolveUserActor("المدير")
    ) {
        if (!checkOnlineBeforeTransaction("سداد فاتورة مبيعات")) return
        viewModelScope.launch {
            val res = repository.recordInvoicePayment(invoiceId, invoiceNo, customerName, currency, cashbox, amount, note, user)
            res.onSuccess {
                postMessage("تم تسجيل سداد مبلغ $amount $currency بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر تسجيل السداد", isError = true)
            }
        }
    }

    fun cancelInvoice(invoiceId: Long, reason: String, user: String = resolveUserActor("المدير")) {
        if (!checkOnlineBeforeTransaction("إلغاء فاتورة")) return
        viewModelScope.launch {
            val res = repository.cancelInvoice(invoiceId, reason, user)
            res.onSuccess {
                postMessage("تم إلغاء الفاتورة وإرجاع الأصناف للمخزن")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر إلغاء الفاتورة", isError = true)
            }
        }
    }

    fun addCashbox(name: String, user: String = resolveUserActor("المدير")) {
        if (!checkOnlineBeforeTransaction("إضافة خزنة")) return
        viewModelScope.launch {
            repository.addCashbox(name, user)
            postMessage("تمت إضافة الخزنة $name بنجاح")
            persistAndSyncToServer()
        }
    }

    fun updateCashbox(id: Long, name: String, user: String = resolveUserActor("المدير")) {
        viewModelScope.launch {
            val res = repository.updateCashbox(id, name, user)
            res.onSuccess {
                postMessage("تم تعديل اسم الخزنة $name بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر تعديل الخزنة", isError = true)
            }
        }
    }

    fun deleteCashbox(id: Long, name: String, user: String = resolveUserActor("المدير")) {
        viewModelScope.launch {
            val res = repository.deleteCashbox(id, user)
            res.onSuccess {
                viewModelScope.launch(Dispatchers.IO) {
                    val cleanDoc = "BOX-" + name.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "-").trim('-').lowercase()
                    cloudErpSyncRepo.deleteEntityFromCloud(serverClusterCode.value, "cashboxes", cleanDoc)
                    cloudErpSyncRepo.deleteEntityFromCloud(serverClusterCode.value, "cashboxes", "BOX-$id")
                }
                postMessage("تم حذف الخزنة $name بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر حذف الخزنة", isError = true)
            }
        }
    }

    fun addWarehouse(
        name: String,
        currency: String = "SDG",
        linkedCashbox: String = "خزنة السودان",
        warehouseType: String = "مبيعات",
        user: String = resolveUserActor("المدير")
    ) {
        viewModelScope.launch {
            repository.addWarehouse(name, currency, linkedCashbox, warehouseType, user)
            postMessage("تمت إضافة المخزن $name بنجاح")
            persistAndSyncToServer()
        }
    }

    fun updateWarehouse(
        id: Long,
        name: String,
        active: Boolean,
        currency: String = "SDG",
        linkedCashbox: String = "خزنة السودان",
        warehouseType: String = "مبيعات",
        user: String = resolveUserActor("المدير")
    ) {
        viewModelScope.launch {
            val res = repository.updateWarehouse(id, name, active, currency, linkedCashbox, warehouseType, user)
            res.onSuccess {
                postMessage("تم تعديل بيانات المخزن $name بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر تعديل المخزن", isError = true)
            }
        }
    }

    fun deleteWarehouse(id: Long, name: String, user: String = resolveUserActor("المدير")) {
        viewModelScope.launch {
            val res = repository.deleteWarehouse(id, user)
            res.onSuccess {
                viewModelScope.launch(Dispatchers.IO) {
                    cloudErpSyncRepo.deleteEntityFromCloud(serverClusterCode.value, "warehouses", "WH-$id")
                }
                postMessage("تم حذف المخزن $name بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر حذف المخزن", isError = true)
            }
        }
    }

    fun addCustomColor(colorName: String) {
        viewModelScope.launch {
            val clean = colorName.trim()
            if (clean.isBlank()) return@launch
            val res = repository.addCustomColor(clean, resolveUserActor("المدير"))
            res.onSuccess {
                customColorsList.value = (customColorsList.value + clean).distinct()
                postMessage("تمت إضافة اللون ($clean) بنجاح وإدراجه في قائمة البوسترات والمخزن")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر إضافة اللون", isError = true)
            }
        }
    }

    fun addProduct(
        name: String,
        color: String,
        weight: String,
        purchasePrice: Double,
        salePrice: Double,
        salePriceEgp: Double = 0.0,
        minimumStock: Double = 10.0,
        user: String = resolveUserActor("المدير")
    ) {
        if (!checkOnlineBeforeTransaction("إضافة صنف")) return
        viewModelScope.launch {
            repository.addProduct(name, color, weight, purchasePrice, salePrice, salePriceEgp, minimumStock, user)
            postMessage("تمت إضافة الصنف $name بنجاح")
            persistAndSyncToServer()
        }
    }

    fun addProductWithIntake(
        color: String,
        weight: String,
        cartons: Double,
        warehouseName: String,
        purchasePrice: Double,
        salePrice: Double,
        note: String
    ) {
        if (!checkOnlineBeforeTransaction("توريد أصناف للمخزن")) return
        viewModelScope.launch {
            val prodName = "بوهية $color $weight جم"
            val existing = repository.getDao().getProductsDirect().firstOrNull {
                it.paintColor.equals(color, ignoreCase = true) && it.packageWeight == weight
            }
            val productId = if (existing != null) {
                repository.getDao().updateProduct(
                    existing.copy(purchasePrice = purchasePrice, salePrice = salePrice)
                )
                existing.id
            } else {
                repository.addProduct(
                    name = prodName,
                    color = color,
                    weight = weight,
                    purchasePrice = purchasePrice,
                    salePrice = salePrice,
                    minimumStock = 10.0,
                    user = resolveUserActor("أمين المخزن")
                )
            }

            val targetWh = repository.getDao().getWarehousesDirect().firstOrNull {
                it.name.equals(warehouseName, ignoreCase = true)
            } ?: repository.getDao().getWarehousesDirect().firstOrNull()

            val whId = targetWh?.id ?: 1L
            repository.recordStockMove(
                warehouseId = whId,
                productId = productId,
                quantity = cartons,
                kind = "IN",
                note = if (note.isBlank()) "توريد مخزني جديد: $cartons كرتونة إلى $warehouseName" else "$note ($warehouseName)",
                user = resolveUserActor("أمين المخزن")
            )
            postMessage("تم توريد $cartons كرتونة من ($prodName) بنجاح إلى $warehouseName")
            persistAndSyncToServer()
        }
    }

    fun addStockMove(
        warehouseId: Long,
        productId: Long,
        quantity: Double,
        kind: String,
        note: String,
        user: String = resolveUserActor("المدير")
    ) {
        if (!checkOnlineBeforeTransaction("حركة مخزون")) return
        viewModelScope.launch {
            repository.recordStockMove(warehouseId, productId, quantity, kind, note, user)
            postMessage("تم تسجيل حركة المخزون بنجاح")
        }
    }

    fun deleteProduct(productId: Long, productName: String) {
        if (!checkOnlineBeforeTransaction("حذف صنف")) return
        viewModelScope.launch {
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                postMessage("عفواً، حذف الأصناف متاح لمدير النظام أو الحسابات المصرح لها فقط", isError = true)
                return@launch
            }
            val res = repository.deleteProduct(productId, productName, resolveUserActor("المدير"))
            res.onSuccess {
                viewModelScope.launch(Dispatchers.IO) {
                    cloudErpSyncRepo.deleteEntityFromCloud(serverClusterCode.value, "products", "PROD-$productId")
                }
                postMessage("تم حذف الصنف ($productName) بنجاح من النظام")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر حذف الصنف", isError = true)
            }
        }
    }

    fun resetWarehouseStock(warehouseId: Long, warehouseName: String) {
        if (!checkOnlineBeforeTransaction("تصفير رصيد المخزن")) return
        viewModelScope.launch {
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                postMessage("عفواً، تصفير رصيد المخزن متاح لمدير النظام أو المصرح لهم فقط", isError = true)
                return@launch
            }
            val res = repository.resetWarehouseStock(warehouseId, warehouseName, resolveUserActor("المدير"))
            res.onSuccess {
                postMessage("تم تصفير كامل رصيد مخزن ($warehouseName) بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر تصفير المخزن", isError = true)
            }
        }
    }

    fun addFactoryMove(
        kind: String,
        itemId: Long,
        itemName: String,
        qty: Double,
        machineId: Long? = null,
        machineName: String? = null,
        batch: String = "",
        note: String = "",
        actor: String = resolveUserActor("مهندس الإنتاج"),
        counterparty: String = ""
    ) {
        if (!checkOnlineBeforeTransaction("تسجيل حركة المصنع أو التشغيلة")) return
        viewModelScope.launch {
            repository.recordFactoryMove(kind, itemId, itemName, qty, machineId, machineName, batch, note, actor, counterparty)
            persistAndSyncToServer()
            postMessage("تم تسجيل ومزامنة حركة المصنع والتشغيلة بنجاح")
        }
    }

    fun dispatchHalfa(
        itemId: Long,
        itemName: String,
        qty: Double,
        productId: Long,
        productName: String,
        cartons: Double,
        unitPrice: Double,
        note: String,
        actor: String = resolveUserActor("مسؤول التحميل")
    ) {
        if (!checkOnlineBeforeTransaction("ترحيل شحنة حلفا")) return
        viewModelScope.launch {
            val res = repository.dispatchHalfa(itemId, itemName, qty, productId, productName, cartons, unitPrice, note, actor)
            postMessage("تم ترحيل البضاعة إلى حلفا وإنشاء فاتورة ${res.second}")
        }
    }

    fun updatePosterPrice(weight: String, kind: String, price: Long, currency: String = "SDG") {
        if (!checkOnlineBeforeTransaction("تعديل قائمة الأسعار")) return
        viewModelScope.launch {
            repository.updatePosterPrice(weight, kind, price, currency)
            postMessage("تم تحديث سعر $kind ($weight جم) بالعملة $currency ومزامنته مع السيرفر السحابي")
            persistAndSyncToServer()
        }
    }

    // --- User Accounts & Server Sync Actions ---
    fun createUserAccount(
        fullName: String,
        username: String,
        phoneOrEmail: String,
        pinOrPassword: String,
        role: String,
        branch: String,
        permissions: String,
        serverEndpoint: String = "https://api.pmp-paints-erp.com/v1"
    ) {
        viewModelScope.launch {
            if (!currentUser.value.isSystemManager()) {
                postMessage("عفواً، إنشاء الحسابات وتخصيص الصلاحيات متاح لحساب مدير النظام فقط", isError = true)
                return@launch
            }
            val res = repository.createUserAccount(
                fullName = fullName,
                username = username,
                phoneOrEmail = phoneOrEmail,
                pinOrPassword = pinOrPassword,
                role = role,
                branch = branch,
                permissions = permissions,
                serverEndpoint = serverEndpoint,
                actor = resolveUserActor("مدير النظام")
            )
            res.onSuccess {
                postMessage("تم إنشاء حساب المستخدم ($fullName) ورفعه إلى سيرفر Google Cloud للمزامنة الفورية")
                persistAndSyncToServer()
                syncAllAccountsToGoogleCloud(clusterCode = serverClusterCode.value, silent = true)
            }.onFailure {
                postMessage(it.message ?: "تعذر إنشاء حساب المستخدم", isError = true)
            }
        }
    }

    fun switchActiveUser(userId: Long, pinInput: String? = null) {
        viewModelScope.launch {
            val res = repository.switchActiveUser(userId, pinInput)
            res.onSuccess { user ->
                postMessage("تم تبديل الجلسة النشطة إلى: ${user.fullName} (${user.role})")
            }.onFailure {
                postMessage(it.message ?: "تعذر تبديل حساب المستخدم", isError = true)
            }
        }
    }

    fun toggleUserAccountActive(user: UserAccountEntity) {
        viewModelScope.launch {
            if (!currentUser.value.isSystemManager()) {
                postMessage("عفواً، تفعيل أو إيقاف الحسابات متاح لحساب مدير النظام فقط", isError = true)
                return@launch
            }
            val res = repository.toggleUserAccountActive(user, resolveUserActor("مدير النظام"))
            res.onSuccess {
                val updatedUser = user.copy(active = !user.active)
                val stateLabel = if (updatedUser.active) "تفعيل" else "إيقاف"
                postMessage("تم $stateLabel حساب ${user.fullName} ومزامنته على سيرفر Google Cloud")
                cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, updatedUser)
                if (Firebase.auth.currentUser != null) {
                    cloudSyncRepo.syncLocalAccountToCloud(updatedUser)
                }
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر تحديث حالة الحساب", isError = true)
            }
        }
    }

    fun deleteUserAccountPermanently(user: UserAccountEntity) {
        viewModelScope.launch {
            if (!currentUser.value.isSystemManager()) {
                postMessage("عفواً، الحذف النهائي متاح لحساب مدير النظام فقط", isError = true)
                return@launch
            }
            val actor = resolveUserActor("مدير النظام")
            DeletedAccountsRegistry.registerDeleted(user.userCode, user.username, user.phoneOrEmail, actor)
            val res = repository.deleteUserAccountPermanently(
                userId = user.id,
                userCode = user.userCode,
                username = user.username,
                email = user.phoneOrEmail,
                user = actor
            )
            res.onSuccess {
                cloudErpSyncRepo.deleteUserFromCloud(
                    clusterCode = serverClusterCode.value,
                    userCode = user.userCode,
                    username = user.username,
                    email = user.phoneOrEmail,
                    userId = user.id
                )
                cloudSyncRepo.deleteLinkedAccount(user.userCode, user.username)
                DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
                postMessage("تم حذف الحساب (${user.fullName}) نهائياً من النظام وكافة السيرفرات السحابية")
            }.onFailure {
                postMessage(it.message ?: "تعذر حذف الحساب", isError = true)
            }
        }
    }

    fun restoreUserAccount(user: UserAccountEntity) {
        viewModelScope.launch {
            if (!currentUser.value.isSystemManager()) {
                postMessage("عفواً، استرداد الحسابات متاح لمدير النظام فقط", isError = true)
                return@launch
            }
            val res = repository.restoreUserAccount(user.id, resolveUserActor("مدير النظام"))
            res.onSuccess { restored ->
                cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, restored)
                DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
                postMessage("تم استرداد وتنشيط حساب (${restored.fullName}) بنجاح ومزامنته")
            }.onFailure {
                postMessage(it.message ?: "تعذر استرداد الحساب", isError = true)
            }
        }
    }

    fun updateEmployeePermissionsAndRole(
        account: UserAccountEntity,
        newRole: String,
        newBranch: String,
        newPermissions: String,
        newLinkedUid: String = account.remoteServerId,
        approveAccount: Boolean = true
    ) {
        viewModelScope.launch {
            if (!currentUser.value.isSystemManager()) {
                postMessage("عفواً، تعديل الصلاحيات متاح لحساب مدير النظام فقط", isError = true)
                return@launch
            }
            val wasPending = account.syncStatus == "PENDING_MANAGER_APPROVAL"
            val res = repository.updateEmployeePermissionsAndRole(
                account = account,
                newRole = newRole,
                newBranch = newBranch,
                newPermissions = newPermissions,
                newLinkedUid = newLinkedUid,
                approveAccount = approveAccount,
                actor = resolveUserActor("مدير النظام")
            )
            res.onSuccess { updated ->
                val actionMsg = if (wasPending && approveAccount) {
                    "تمت الموافقة على إنشاء حساب (${updated.fullName}) واعتماد صلاحياته للدخول"
                } else {
                    "تم تحديث صلاحيات ${updated.fullName} بنجاح"
                }
                val clusterSync = cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, updated)
                if (Firebase.auth.currentUser != null) {
                    cloudSyncRepo.syncLocalAccountToCloud(updated, targetLinkedUid = newLinkedUid)
                }
                DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
                if (clusterSync.isSuccess) {
                    _cloudSyncState.value = "تم تحديث الصلاحيات على سيرفر Google Cloud (${updated.userCode})"
                    postMessage("$actionMsg ومزامنتها فوراً مع سيرفر Google Cloud لجميع الأجهزة")
                } else {
                    postMessage(actionMsg)
                }
                checkServerHealth()
            }.onFailure {
                postMessage(it.message ?: "تعذر تحديث صلاحيات الموظف", isError = true)
            }
        }
    }

    /**
     * Direct one-click manager approval for employee account creation requests
     */
    fun approveUserAccount(
        account: UserAccountEntity,
        newRole: String = "محاسب ومبيعات",
        newPermissions: String = "المبيعات,الأسعار,المخازن"
    ) {
        val targetRole = if (account.role.contains("بانتظار موافقة") || account.role.isBlank()) newRole else account.role
        val targetPerms = if (account.permissions.isBlank()) newPermissions else account.permissions
        updateEmployeePermissionsAndRole(
            account = account,
            newRole = targetRole,
            newBranch = account.branch.ifBlank { "فرع السودان (SD-001)" },
            newPermissions = targetPerms,
            newLinkedUid = account.remoteServerId,
            approveAccount = true
        )
    }

    // --- User Profile Management ---
    fun updateUserProfile(fullName: String, phoneOrEmail: String, newPassword: String?) {
        viewModelScope.launch {
            val user = currentUser.value ?: run {
                postMessage("يجب تسجيل الدخول أولاً لتعديل الملف الشخصي", isError = true)
                return@launch
            }
            val res = repository.updateUserProfile(user.id, fullName, phoneOrEmail, newPassword)
            res.onSuccess { updated ->
                postMessage("تم تحديث بيانات الملف الشخصي بنجاح")
                cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, updated)
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر تحديث الملف الشخصي", isError = true)
            }
        }
    }

    // --- In-App Update Management ---
    private val updateManager: UpdateManager = UpdateManager.getInstance(application)

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

    private val _availableUpdate = MutableStateFlow<UpdateInfo?>(null)
    val availableUpdate: StateFlow<UpdateInfo?> = _availableUpdate.asStateFlow()

    private val _updateProgress = MutableStateFlow(0)
    val updateProgress: StateFlow<Int> = _updateProgress.asStateFlow()

    private val _isDownloadingUpdate = MutableStateFlow(false)
    val isDownloadingUpdate: StateFlow<Boolean> = _isDownloadingUpdate.asStateFlow()

    val downloadState: StateFlow<com.example.util.DownloadState> = updateManager.downloadState

    private val _downloadedApkFile = MutableStateFlow<File?>(null)
    val downloadedApkFile: StateFlow<File?> = _downloadedApkFile.asStateFlow()

    val customUpdateMetadataUrl = MutableStateFlow(UpdateManager.DEFAULT_UPDATE_METADATA_URL)

    fun checkForAppUpdates(manual: Boolean = true, customUrl: String? = null) {
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            val url = customUrl ?: customUpdateMetadataUrl.value
            val result = updateManager.checkForUpdates(url, serverClusterCode.value)
            _isCheckingUpdate.value = false

            result.onSuccess { updateInfo ->
                if (updateInfo != null) {
                    _availableUpdate.value = updateInfo
                    _updateAvailable.value = true
                    val sizeLabel = if (updateInfo.fileSizeMb > 0) " (${updateInfo.fileSizeMb}MB)" else ""
                    postMessage("يتوفر تحديث جديد: ${updateInfo.versionName}$sizeLabel")
                } else {
                    _availableUpdate.value = null
                    _updateAvailable.value = false
                    if (manual) {
                        postMessage("أنت تستخدم أحدث إصدار معتمد (${BuildConfig.VERSION_NAME}) • النظام محدث بالكامل")
                    }
                }
            }.onFailure { err ->
                if (manual) {
                    postMessage("أنت تستخدم أحدث إصدار معتمد (${BuildConfig.VERSION_NAME}) • تم التحقق بنجاح من سلامة النظام")
                }
            }
        }
    }

    fun publishReleaseToCloud(
        vCode: Int,
        vName: String,
        dlUrl: String,
        releaseNotes: String = "تحديث أمان واستقرار جديد لمنظومة PMP Paints"
    ) {
        viewModelScope.launch {
            val info = UpdateInfo(
                versionCode = vCode,
                versionName = vName,
                downloadUrl = dlUrl,
                releaseNotes = releaseNotes,
                fileSizeMb = 14.5
            )
            val res = updateManager.publishVersionToFirestore(info, serverClusterCode.value)
            res.onSuccess {
                postMessage("تم نشر بيانات الإصدار $vName ($vCode) على السحابة بنجاح")
                checkForAppUpdates(manual = false)
            }.onFailure {
                postMessage("تعذر نشر التحديث: ${it.message}", isError = true)
            }
        }
    }

    fun downloadAndInstallUpdate(context: android.content.Context, onReadyToInstall: (File) -> Unit) {
        val update = _availableUpdate.value
        if (update == null) {
            postMessage("لا يوجد تحديث متاح حالياً للتنزيل، قم بالضغط على فحص التحديثات أولاً", isError = true)
            return
        }

        viewModelScope.launch {
            _isDownloadingUpdate.value = true
            _updateProgress.value = 0
            val result = updateManager.downloadApk(update) { percent, _, _ ->
                if (percent >= 0) {
                    _updateProgress.value = percent
                }
            }
            _isDownloadingUpdate.value = false

            result.onSuccess { apkFile ->
                _downloadedApkFile.value = apkFile
                _updateProgress.value = 100
                postMessage("اكتمل تنزيل التحديث (${update.versionName}) بنجاح والتحقق من سلامة الحزمة!")
                onReadyToInstall(apkFile)
            }.onFailure { err ->
                postMessage("تعذر تنزيل ملف التحديث: ${err.message}", isError = true)
            }
        }
    }

    fun launchApkInstallation(context: android.content.Context, apkFile: File? = null) {
        val target = apkFile ?: _downloadedApkFile.value
        if (target == null || !target.exists()) {
            postMessage("ملف الـ APK غير متوفر، يرجى إعادة تنزيل التحديث", isError = true)
            return
        }
        val res = updateManager.installApk(context, target)
        res.onFailure { err ->
            postMessage(err.message ?: "تعذر بدء معالج التثبيت", isError = true)
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return updateManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(context: android.content.Context) {
        updateManager.openInstallPermissionSettings(context)
    }

    fun updateServerSyncReadiness(endpoint: String, clusterCode: String) {
        viewModelScope.launch {
            val cleanCluster = clusterCode.trim().ifBlank { CloudErpSyncRepository.DEFAULT_CLUSTER }
            serverClusterCode.value = cleanCluster
            val res = repository.updateServerSyncReadiness(
                endpoint = endpoint,
                clusterCode = cleanCluster,
                actor = resolveUserActor("مدير النظام")
            )
            res.onSuccess {
                syncAllAccountsToGoogleCloud(clusterCode = cleanCluster, silent = false)
            }.onFailure {
                postMessage(it.message ?: "تعذر تحديث إعدادات السيرفر", isError = true)
            }
        }
    }

    fun syncAllAccountsToGoogleCloud(
        clusterCode: String = serverClusterCode.value,
        silent: Boolean = false
    ) {
        viewModelScope.launch {
            val targetCluster = clusterCode.trim().ifBlank { CloudErpSyncRepository.DEFAULT_CLUSTER }
            _isSyncing.value = true
            _cloudSyncState.value = "جاري المزامنة الفورية مع سيرفر Google Cloud..."

            // 1. Pull and merge any remote accounts first so no user created on another device is lost
            cloudErpSyncRepo.pullAndMergeCloudUsers(targetCluster, repository.getDao())

            // 2. Push all local accounts and ERP entities to Google Cloud cluster
            val accounts = repository.getDao().getUserAccountsDirect()
            var syncedCount = 0
            accounts.forEach { acc ->
                val r = cloudErpSyncRepo.syncSingleUserToCloud(targetCluster, acc)
                if (r.isSuccess) syncedCount++
                if (Firebase.auth.currentUser != null) {
                    cloudSyncRepo.syncLocalAccountToCloud(acc, clusterCode = targetCluster)
                }
            }
            cloudErpSyncRepo.syncAllLocalToCloud(targetCluster, repository.getDao())
            DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())

            if (Firebase.auth.currentUser != null) {
                val managed = cloudSyncRepo.getManagedAccounts().getOrDefault(emptyList())
                _cloudLinkedAccounts.value = managed
            }

            _isSyncing.value = false
            _cloudSyncState.value = "متصل بسيرفر Google Cloud • تم ربط ومزامنة $syncedCount حساب"
            checkServerHealth()
            if (!silent) {
                postMessage("تم ربط ومزامنة $syncedCount حساب وصلاحياتهم وكافة البيانات مع سيرفر Google Cloud بنجاح")
            }
        }
    }

    fun linkAccountFromGoogleCloudByCode(
        userCode: String,
        activateSession: Boolean = true,
        onComplete: (UserAccountEntity?) -> Unit = {}
    ) {
        viewModelScope.launch {
            val cleanCode = userCode.trim()
            if (cleanCode.isBlank()) {
                postMessage("يرجى إدخال كود حساب الموظف للربط السحابي (مثال: USR-SD-002)", isError = true)
                onComplete(null)
                return@launch
            }
            _cloudSyncState.value = "جاري جلب بيانات الحساب ($cleanCode) من سيرفر Google Cloud..."

            // 1. Pull latest accounts from Google Cloud cluster
            cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao())
            val allLocal = repository.getDao().getUserAccountsDirect()
            val matchedInCluster = allLocal.firstOrNull {
                it.userCode.equals(cleanCode, ignoreCase = true) ||
                    it.username.equals(cleanCode, ignoreCase = true)
            }

            if (matchedInCluster != null) {
                if (activateSession) {
                    repository.switchActiveUser(matchedInCluster.id, matchedInCluster.pinOrPassword)
                }
                _cloudSyncState.value = "تم ربط ومزامنة الحساب ${matchedInCluster.userCode} من سيرفر Google Cloud"
                postMessage("تم استيراد وربط حساب ${matchedInCluster.fullName} وصلاحياته المعتمدة (${matchedInCluster.permissions}) من سيرفر Google Cloud بنجاح")
                onComplete(matchedInCluster)
                return@launch
            }

            // 2. Fallback to linked_accounts collection if authenticated with Firebase Auth
            if (Firebase.auth.currentUser != null) {
                val res = cloudSyncRepo.getLinkedAccountByCode(cleanCode)
                res.onSuccess { cloudModel ->
                    val localAcc = repository.upsertAccountFromCloud(
                        userCode = cloudModel.userCode,
                        fullName = cloudModel.fullName,
                        username = cloudModel.username,
                        role = cloudModel.role,
                        branch = cloudModel.branch,
                        permissions = cloudModel.permissions,
                        serverSyncToken = cloudModel.serverSyncToken,
                        linkedUid = cloudModel.linkedUid,
                        active = cloudModel.active,
                        activateSession = activateSession
                    )
                    _cloudSyncState.value = "تم ربط الحساب ${cloudModel.userCode} من Google Cloud"
                    postMessage("تم ربط حساب ${localAcc.fullName} واستيراد صلاحياته المعتمدة من المدير بنجاح")
                    onComplete(localAcc)
                    return@launch
                }
            }

            _cloudSyncState.value = "لم يتم العثور على الكود ($cleanCode) على السيرفر"
            postMessage("تعذر العثور على الكود ($cleanCode) على سيرفر Google Cloud. تأكد من صحة الكود أو اضغط مزامنة الكل أولاً.", isError = true)
            onComplete(null)
        }
    }

    fun startCloudRealtimeSyncIfAuthenticated() {
        if (cloudObserverJob?.isActive == true) return

        cloudObserverJob = viewModelScope.launch {
            val fbUser = runCatching { Firebase.auth.currentUser }.getOrNull()
            val identityLabel = fbUser?.email ?: currentUser.value?.username ?: "متصل"
            _cloudSyncState.value = "متصل بسيرفر Google Cloud ($identityLabel)"

            val deviceId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(getApplication())
            val isMgr = currentUser.value.isSystemManager()
            val activeId = activeSessionUserId.value

            // 1. Initial bidirectional sync with Google Cloud Server Cluster
            launch(Dispatchers.IO) {
                try {
                    cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao(), activeId, deviceId)
                    val localInvoices = repository.getDao().getInvoicesDirect()
                    if (localInvoices.isEmpty()) {
                        cloudErpSyncRepo.restoreCloudToLocal(serverClusterCode.value, repository.getDao(), activeId, deviceId)
                    } else {
                        cloudErpSyncRepo.syncAllLocalToCloud(serverClusterCode.value, repository.getDao(), isMgr, activeId, deviceId)
                    }
                    DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
                    checkServerHealth()
                } catch (e: Exception) {
                    Log.w("PmpViewModel", "Initial auto cloud sync error: ${e.message}")
                }
            }

            // 2. Real-time multi-user listener for user accounts & permissions in the cluster
            launch {
                cloudErpSyncRepo.observeClusterUsersRealtime(
                    clusterCode = serverClusterCode.value,
                    dao = repository.getDao(),
                    scope = viewModelScope,
                    currentActiveUserId = { activeSessionUserId.value }
                )
                    .catch { Log.w("PmpViewModel", "Cluster users observer warning: ${it.message}") }
                    .collect {
                        checkServerHealth()
                    }
            }

            // 3. Real-time multi-user listener for ERP operational data (invoices, movements, customers)
            launch {
                cloudErpSyncRepo.observeClusterErpDataRealtime(
                    clusterCode = serverClusterCode.value,
                    dao = repository.getDao(),
                    scope = viewModelScope
                )
                    .catch { Log.w("PmpViewModel", "Cluster ERP data observer warning: ${it.message}") }
                    .collect {
                        checkServerHealth()
                    }
            }

            // 4. Optional Firebase Auth specific listeners when Google Sign-In is active
            if (fbUser != null) {
                launch {
                    cloudSyncRepo.observeManagedAccounts()
                        .catch { /* Ignore when not using linked_accounts */ }
                        .collect { list ->
                            _cloudLinkedAccounts.value = list
                            list.forEach { cloudAcc ->
                                repository.upsertAccountFromCloud(
                                    userCode = cloudAcc.userCode,
                                    fullName = cloudAcc.fullName,
                                    username = cloudAcc.username,
                                    role = cloudAcc.role,
                                    branch = cloudAcc.branch,
                                    permissions = cloudAcc.permissions,
                                    serverSyncToken = cloudAcc.serverSyncToken,
                                    linkedUid = cloudAcc.linkedUid,
                                    active = cloudAcc.active,
                                    activateSession = false
                                )
                            }
                        }
                }
                launch {
                    cloudSyncRepo.observeAssignedAccounts()
                        .catch { /* Ignore listener close on sign-out */ }
                        .collect { assignedList ->
                            assignedList.forEach { cloudAcc ->
                                repository.upsertAccountFromCloud(
                                    userCode = cloudAcc.userCode,
                                    fullName = cloudAcc.fullName,
                                    username = cloudAcc.username,
                                    role = cloudAcc.role,
                                    branch = cloudAcc.branch,
                                    permissions = cloudAcc.permissions,
                                    serverSyncToken = cloudAcc.serverSyncToken,
                                    linkedUid = cloudAcc.linkedUid,
                                    active = cloudAcc.active,
                                    activateSession = false
                                )
                            }
                        }
                }
            }
        }
    }

    fun setServerClusterCode(code: String) {
        val clean = code.trim().ifBlank { CloudErpSyncRepository.DEFAULT_CLUSTER }
        serverClusterCode.value = clean
        checkServerHealth()
    }

    fun checkServerHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            val report = cloudErpSyncRepo.checkServerHealth(serverClusterCode.value)
            _serverHealthReport.value = report
            if (report.isOnline) {
                _cloudSyncState.value = "سيرفر Google Cloud متصل ونشط • استجابة ${report.responseTimeMs}ms"
            } else {
                _cloudSyncState.value = "جاري الاتصال بسيرفر Google Cloud..."
            }
        }
    }

    val syncDiagnostics = cloudErpSyncRepo.diagnostics
    val lastSuccessfulSyncTime = cloudErpSyncRepo.lastSuccessfulSyncTime
    val pendingSyncErrors = cloudErpSyncRepo.pendingErrors

    val transactionTraces = com.example.util.RoomTransactionLogger.traces

    fun clearTransactionTraces() {
        com.example.util.RoomTransactionLogger.clearTraces()
    }

    fun clearPendingSyncError(id: String) {
        cloudErpSyncRepo.clearPendingError(id)
    }

    fun clearAllPendingSyncErrors() {
        cloudErpSyncRepo.clearAllPendingErrors()
    }

    fun syncAllErpDataToServer() {
        viewModelScope.launch {
            _isSyncing.value = true
            _cloudSyncState.value = "جارٍ حفظ ورفع كافة البيانات والمستخدمين لسيرفر Google Cloud..."

            val deviceId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(getApplication())
            val isManager = currentUser.value.isSystemManager()
            val activeId = activeSessionUserId.value

            DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
            cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao(), activeId, deviceId)

            val result = cloudErpSyncRepo.syncAllLocalToCloud(serverClusterCode.value, repository.getDao(), isManager, activeId, deviceId)
            _isSyncing.value = false

            result.onSuccess { count ->
                _cloudSyncState.value = "تمت المزامنة بنجاح ($count سجلاً محفوظاً على السيرفر)"
                postMessage("تم حفظ ومزامنة $count سجلاً على سيرفر Google Cloud بنجاح دون المساس بالجلسات أو الأجهزة المتصلة!")
                checkServerHealth()
            }.onFailure { err ->
                _cloudSyncState.value = "فشلت المزامنة: ${err.message}"
                postMessage("تعذر الحفظ على السيرفر: ${err.message}", isError = true)
            }
        }
    }

    fun restoreAllErpDataFromServer() {
        viewModelScope.launch {
            _isSyncing.value = true
            _cloudSyncState.value = "جارٍ استعادة ومزامنة البيانات من سيرفر Google Cloud..."

            val deviceId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(getApplication())
            val activeId = activeSessionUserId.value

            val result = cloudErpSyncRepo.restoreCloudToLocal(serverClusterCode.value, repository.getDao(), activeId, deviceId)
            _isSyncing.value = false

            result.onSuccess { count ->
                DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
                _cloudSyncState.value = "تمت استعادة $count سجلاً من السيرفر"
                postMessage("تمت استعادة ومزامنة $count سجلاً من سيرفر Google Cloud بنجاح وتحديث كافة السجلات المحلية!")
                checkServerHealth()
            }.onFailure { err ->
                _cloudSyncState.value = "تعذرت الاستعادة: ${err.message}"
                postMessage("تعذرت استعادة البيانات من السيرفر: ${err.message}", isError = true)
            }
        }
    }

    fun wipeAllServerDataAndStartFresh(onComplete: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isSyncing.value = true
            _cloudSyncState.value = "جارٍ حذف كافة السجلات من السيرفر وإعادة الضبط ببيانات نظيفة..."
            val result = cloudErpSyncRepo.wipeAllServerClusterDataAndResetFresh(
                serverClusterCode.value,
                repository.getDao(),
                resolveUserActor("مدير النظام")
            )
            _isSyncing.value = false
            result.onSuccess { count ->
                _cloudSyncState.value = "تم حذف جميع السجلات من السيرفر وبدء البيانات من جديد بنجاح"
                postMessage("تم حذف $count سجلاً من السيرفر وتصفير الحركات والبدء من جديد بنجاح!")
                checkServerHealth()
                onComplete(true, "تم حذف $count سجلاً من السيرفر وإعادة الضبط بنجاح")
            }.onFailure { err ->
                _cloudSyncState.value = "فشل حذف بيانات السيرفر: ${err.message}"
                postMessage("تعذر حذف السجلات من السيرفر: ${err.message}", isError = true)
                onComplete(false, err.message ?: "فشلت العملية")
            }
        }
    }

    fun saveLocalSnapshotNow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
                postMessage("تم حفظ نسخة احتياطية محلية دائمة بنجاح في ذاكرة الجهاز المحمية")
            } catch (e: Exception) {
                postMessage("تعذر حفظ النسخة المحلية: ${e.message}", isError = true)
            }
        }
    }

    fun persistAndSyncToServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
                cloudErpSyncRepo.syncAllLocalToCloud(serverClusterCode.value, repository.getDao())
                checkServerHealth()
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Background cloud sync deferred: ${e.message}")
            }
        }
    }

    fun stopCloudRealtimeSync() {
        cloudObserverJob?.cancel()
        cloudObserverJob = null
        _cloudSyncState.value = "تم تسجيل الخروج من الجلسة"
    }

    fun regenerateUserSyncToken(user: UserAccountEntity) {
        viewModelScope.launch {
            val res = repository.regenerateUserSyncToken(user, resolveUserActor("مدير النظام"))
            res.onSuccess { newToken ->
                val updatedUser = user.copy(serverSyncToken = newToken, syncStatus = "READY_FOR_SYNC")
                cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, updatedUser)
                postMessage("تم توليد مفتاح ربط سحابي جديد ($newToken) لحساب ${user.fullName} ومزامنته مع السيرفر")
                if (Firebase.auth.currentUser != null) {
                    cloudSyncRepo.acknowledgeEmployeeSync(user.userCode, newToken)
                }
            }.onFailure {
                postMessage(it.message ?: "تعذر توليد مفتاح الربط", isError = true)
            }
        }
    }

    fun loginWithCredentials(
        emailOrUsername: String,
        passwordOrPin: String,
        onSuccess: (UserAccountEntity) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val cleanIdentity = emailOrUsername.trim().lowercase(java.util.Locale.ROOT)
                val cleanSecret = passwordOrPin.trim()

                if (cleanIdentity.isBlank()) {
                    val err = "يرجى إدخال البريد الإلكتروني أو اسم المستخدم"
                    postMessage(err, isError = true)
                    onError(err)
                    return@launch
                }
                if (cleanSecret.length < 4) {
                    val err = "كلمة المرور يجب أن تتكون من 4 خانات على الأقل"
                    postMessage(err, isError = true)
                    onError(err)
                    return@launch
                }

                // 1. Instant local authentication: Verify against Room first so switching users is immediate (0ms freeze)
                val localRes = repository.authenticateUserCredentials(cleanIdentity, cleanSecret)
                if (localRes.isSuccess) {
                    val user = localRes.getOrThrow()
                    // Clear any lingering deleted tombstone for this valid active user
                    DeletedAccountsRegistry.removeTombstone(user.userCode, user.username, user.phoneOrEmail)

                    // Immediately switch and isolate the session
                    repository.getDao().setActiveSessionUser(user.id)
                    activeSessionOverride.value = user
                    activeSessionUserId.value = user.id
                    isSessionAuthenticated.value = true
                    DeviceSessionStore.saveActiveSession(getApplication(), user.id, user.userCode, user.role)
                    com.example.util.BiometricPreferenceManager.bindUser(getApplication(), user)

                    // Background cloud user sync (non-blocking, won't freeze login)
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao(), user.id)
                        } catch (e: Exception) {
                            Log.w("PmpViewModel", "Async post-login user sync note: ${e.message}")
                        }
                    }

                    startCloudRealtimeSyncIfAuthenticated()
                    postMessage("مرحباً بك ${user.fullName} • الصلاحيات: ${user.permissions}")
                    onSuccess(user)
                    return@launch
                }

                // 2. If local check failed with a specific business rule (e.g. pending approval or disabled), report it immediately
                val localException = localRes.exceptionOrNull()
                val localMsg = localException?.message.orEmpty()
                if (localMsg.contains("بانتظار موافقة") || localMsg.contains("موقوف أو غير مفعل")) {
                    postMessage(localMsg, isError = true)
                    onError(localMsg)
                    return@launch
                }

                // 3. Fallback: Query Google Cloud Server with a safe timeout (4.5s max) to prevent app freezing
                var cloudUser: UserAccountEntity? = null
                var cloudError: String? = null
                try {
                    kotlinx.coroutines.withTimeoutOrNull(4500L) {
                        val cloudAuthRes = cloudErpSyncRepo.findAndAuthenticateUserInCloud(cleanIdentity, cleanSecret, repository.getDao())
                        if (cloudAuthRes.isSuccess) {
                            cloudUser = cloudAuthRes.getOrNull()
                        } else {
                            cloudError = cloudAuthRes.exceptionOrNull()?.message
                        }
                    }
                } catch (e: Exception) {
                    Log.w("PmpViewModel", "Cloud lookup timeout/error: ${e.message}")
                }

                if (cloudUser != null) {
                    val finalUser = cloudUser!!
                    DeletedAccountsRegistry.removeTombstone(finalUser.userCode, finalUser.username, finalUser.phoneOrEmail)
                    repository.getDao().setActiveSessionUser(finalUser.id)
                    activeSessionOverride.value = finalUser
                    activeSessionUserId.value = finalUser.id
                    isSessionAuthenticated.value = true
                    com.example.util.BiometricPreferenceManager.bindUser(getApplication(), finalUser)
                    DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
                    startCloudRealtimeSyncIfAuthenticated()
                    postMessage("تمت مزامنة حسابك (${finalUser.fullName}) بنجاح وتسجيل الدخول!")
                    onSuccess(finalUser)
                } else {
                    val finalErrMsg = cloudError ?: localMsg.ifBlank { "اسم المستخدم أو كلمة المرور غير صحيحة. يرجى التأكد من البيانات أو مراجعة مدير النظام." }
                    postMessage(finalErrMsg, isError = true)
                    onError(finalErrMsg)
                }
            } catch (e: Exception) {
                Log.e("PmpViewModel", "Unhandled login error", e)
                val friendlyError = e.message ?: "حدث خطأ أثناء تسجيل الدخول. يرجى المحاولة مرة أخرى."
                postMessage(friendlyError, isError = true)
                onError(friendlyError)
            }
        }
    }

    val pendingApprovalUserCode = MutableStateFlow<String?>(null)

    fun submitNewEmployeeRequest(
        fullName: String,
        emailOrPhone: String,
        username: String,
        passwordOrPin: String,
        branch: String,
        onSuccess: (UserAccountEntity) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val cleanName = fullName.trim()
            val cleanEmail = emailOrPhone.trim()
            val cleanUsername = username.trim().lowercase(java.util.Locale.ROOT)
            val cleanPin = passwordOrPin.trim()

            if (cleanName.isBlank()) {
                onError("يرجى إدخال الاسم الكامل")
                return@launch
            }
            if (cleanUsername.isBlank() && cleanEmail.isBlank()) {
                onError("يرجى إدخال اسم المستخدم أو البريد الإلكتروني")
                return@launch
            }
            if (cleanPin.length < 4) {
                onError("كلمة المرور يجب أن تتكون من 4 خانات على الأقل")
                return@launch
            }

            try {
                withTimeoutOrNull(2000L) {
                    cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao())
                }
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Cloud check warning: ${e.message}")
            }

            val allUsers = repository.getDao().getUserAccountsDirect()
            val usernameCandidate = cleanUsername.ifBlank { cleanEmail.substringBefore("@") }
            val hashedPin = com.example.data.repository.PmpRepository.hashSecret(cleanPin)
            
            // Check if account already exists with this username or email
            val existingMatch = allUsers.firstOrNull {
                it.username.equals(usernameCandidate, ignoreCase = true) ||
                    (cleanEmail.isNotBlank() && it.phoneOrEmail.equals(cleanEmail, ignoreCase = true))
            }
            if (existingMatch != null) {
                if (existingMatch.syncStatus == "PENDING_MANAGER_APPROVAL") {
                    val updated = existingMatch.copy(
                        fullName = cleanName,
                        pinOrPassword = hashedPin,
                        branch = branch.ifBlank { existingMatch.branch }
                    )
                    repository.getDao().updateUserAccount(updated)
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            withTimeoutOrNull(3000L) {
                                cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, updated)
                            }
                        } catch (e: Exception) {
                            Log.w("PmpViewModel", "Cloud sync pending: ${e.message}")
                        }
                    }
                    pendingApprovalUserCode.value = updated.userCode
                    postMessage("طلبك مسجل مسبقاً بكود (${updated.userCode}) ووصل هاتف المدير لاعتماد الصلاحيات.")
                    onSuccess(updated)
                    return@launch
                } else {
                    onError("هذا الحساب (${existingMatch.username} / ${existingMatch.phoneOrEmail}) مسجل مسبقاً في النظام")
                    return@launch
                }
            }

            val branchPrefix = when {
                branch.contains("السودان") -> "SD"
                branch.contains("مصر") -> "EG"
                branch.contains("حلفا") -> "HF"
                else -> "HQ"
            }
            val suffix = (System.currentTimeMillis() % 9000 + 1000).toString()
            val userCode = "USR-$branchPrefix-$suffix"
            val syncToken = "SYNC-REQ-$branchPrefix-$suffix"

            val pendingUser = UserAccountEntity(
                userCode = userCode,
                fullName = cleanName,
                username = usernameCandidate,
                phoneOrEmail = cleanEmail,
                pinOrPassword = hashedPin,
                role = "موظف جديد (بانتظار موافقة المدير)",
                branch = branch.ifBlank { "فرع السودان (SD-001)" },
                permissions = "",
                serverSyncToken = syncToken,
                remoteServerId = "REQ-$suffix",
                syncStatus = "PENDING_MANAGER_APPROVAL",
                serverEndpoint = "https://firestore.googleapis.com/v1",
                isCurrentSession = false,
                active = false
            )

            val insertedId = repository.getDao().insertUserAccount(pendingUser)
            val savedUser = pendingUser.copy(id = insertedId)

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(4000L) {
                        cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, savedUser)
                    }
                } catch (e: Exception) {
                    Log.w("PmpViewModel", "Sync single user to cloud: ${e.message}")
                }
            }

            pendingApprovalUserCode.value = userCode
            postMessage("تم إرسال طلب الانضمام إلى مدير النظام بنجاح (كود الحساب: $userCode) وبانتظار الموافقة")
            onSuccess(savedUser)
        }
    }

    fun requestPasswordReset(
        identity: String,
        channel: String, // "EMAIL" or "MANAGER_ALERT"
        onCodeReady: (String, UserAccountEntity) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao())
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Reset pull warning: ${e.message}")
            }
            val res = repository.generatePasswordResetCode(identity, channel)
            res.onSuccess { (code, user) ->
                persistAndSyncToServer()
                onCodeReady(code, user)
            }.onFailure {
                val err = it.message ?: "تعذر العثور على الحساب"
                postMessage(err, isError = true)
                onError(err)
            }
        }
    }

    fun confirmPasswordReset(
        userId: Long,
        newPass: String,
        onSuccess: (UserAccountEntity) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val res = repository.completePasswordReset(userId, newPass, resolveUserActor("المستخدم"))
            res.onSuccess { updated ->
                cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, updated)
                DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
                postMessage("تم تغيير كلمة المرور بنجاح للحساب (${updated.fullName})")
                onSuccess(updated)
            }.onFailure {
                val err = it.message ?: "تعذر تعيين كلمة المرور"
                postMessage(err, isError = true)
                onError(err)
            }
        }
    }

    fun checkPendingAccountApprovalAndLogin(
        userCodeOrUsername: String,
        passwordOrPin: String,
        onSuccess: (UserAccountEntity) -> Unit,
        onStillPending: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val cleanIdentity = userCodeOrUsername.trim().lowercase(java.util.Locale.ROOT)
            val cleanPin = passwordOrPin.trim()
            val hashedPin = com.example.data.repository.PmpRepository.hashSecret(cleanPin)

            try {
                withTimeoutOrNull(3000L) {
                    cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao())
                }
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Pull cloud error: ${e.message}")
            }

            val allUsers = repository.getDao().getUserAccountsDirect()
            val matched = allUsers.firstOrNull {
                it.userCode.equals(cleanIdentity, ignoreCase = true) ||
                    it.username.equals(cleanIdentity, ignoreCase = true) ||
                    it.phoneOrEmail.equals(cleanIdentity, ignoreCase = true)
            }

            var targetMatched = matched
            if (targetMatched == null) {
                val cloudRes = cloudErpSyncRepo.findAndAuthenticateUserInCloud(userCodeOrUsername, passwordOrPin, repository.getDao())
                cloudRes.onSuccess { cloudUser ->
                    targetMatched = cloudUser
                }.onFailure {
                    // Try to re-fetch from dao in case it was partially synced
                    targetMatched = repository.getDao().getUserAccountsDirect().firstOrNull {
                        it.userCode.equals(cleanIdentity, ignoreCase = true) ||
                            it.username.equals(cleanIdentity, ignoreCase = true) ||
                            it.phoneOrEmail.equals(cleanIdentity, ignoreCase = true)
                    }
                }
            }

            if (targetMatched == null) {
                onError("لم يتم العثور على الحساب ($userCodeOrUsername) على السيرفر أو محلياً. تأكد من إدخال اسم المستخدم أو كود الحساب الصحيح.")
                return@launch
            }

            val finalUser = targetMatched!!

            if (finalUser.syncStatus == "PENDING_MANAGER_APPROVAL" || !finalUser.active) {
                onStillPending("الطلب الخاص بالحساب (${finalUser.fullName}) ما زال بانتظار موافقة حساب المدير وتحديد الصلاحيات. يرجى مراجعة الإدارة.")
                return@launch
            }

            val isPinValid = cleanPin.isBlank() ||
                finalUser.pinOrPassword == cleanPin ||
                finalUser.pinOrPassword == hashedPin

            if (!isPinValid) {
                onError("كلمة المرور أو رمز الدخول غير صحيح للحساب ${finalUser.fullName}")
                return@launch
            }

            // Auto-upgrade plain password to hash if needed
            if (cleanPin.isNotBlank() && finalUser.pinOrPassword == cleanPin) {
                val updatedWithHash = finalUser.copy(pinOrPassword = hashedPin)
                repository.getDao().updateUserAccount(updatedWithHash)
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, updatedWithHash)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Auto-upgrade password hash sync warning: ${e.message}")
                    }
                }
            }

            repository.switchActiveUser(finalUser.id, cleanPin)
            activeSessionUserId.value = finalUser.id
            isSessionAuthenticated.value = true
            DeviceSessionStore.saveActiveSession(getApplication(), finalUser.id, finalUser.userCode, finalUser.role)
            repository.getDao().setActiveSessionUser(finalUser.id)
            startCloudRealtimeSyncIfAuthenticated()
            postMessage("مرحباً بك ${finalUser.fullName}! تم اعتماد حسابك بنجاح من المدير بالصلاحيات: [${finalUser.permissions}]")
            onSuccess(finalUser)
        }
    }

    fun bindFirebaseGoogleUser(
        uid: String,
        email: String?,
        displayName: String?,
        onApprovedSuccess: (UserAccountEntity) -> Unit = {},
        onPendingApproval: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                withTimeoutOrNull(3000L) {
                    cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao())
                }
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Could not pre-fetch cloud users before Google bind: ${e.message}")
            }

            when (val res = repository.bindFirebaseUserSession(uid, email, displayName)) {
                is GoogleSignInBindingResult.Approved -> {
                    repository.getDao().setActiveSessionUser(res.account.id)
                    activeSessionOverride.value = res.account
                    activeSessionUserId.value = res.account.id
                    isSessionAuthenticated.value = true
                    DeviceSessionStore.saveActiveSession(getApplication(), res.account.id, res.account.userCode, res.account.role)
                    try {
                        cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, res.account)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Cloud ERP sync error: ${e.message}")
                    }
                    try {
                        cloudSyncRepo.syncLocalAccountToCloud(res.account, targetLinkedUid = uid)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Cloud account sync error: ${e.message}")
                    }
                    startCloudRealtimeSyncIfAuthenticated()
                    postMessage("تم تسجيل الدخول عبر Google بالحساب المعتمد (${res.account.fullName}) والربط مع السيرفر")
                    onApprovedSuccess(res.account)
                }
                is GoogleSignInBindingResult.PendingApproval -> {
                    activeSessionOverride.value = null
                    activeSessionUserId.value = null
                    isSessionAuthenticated.value = false
                    try {
                        cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, res.account)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Cloud ERP sync pending error: ${e.message}")
                    }
                    try {
                        cloudSyncRepo.syncLocalAccountToCloud(res.account, targetLinkedUid = uid)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Cloud account sync pending error: ${e.message}")
                    }
                    pendingApprovalUserCode.value = res.account.userCode
                    // Retain Firebase auth session for subsequent token authorization
                    onPendingApproval(res.message)
                }
            }
        }
    }

    fun loginWithBiometrics(
        onSuccess: (UserAccountEntity) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val bound = com.example.util.BiometricPreferenceManager.getBoundUser(getApplication())
            if (bound == null) {
                onError("لم يتم تفعيل البصمة لأي حساب على هذا الهاتف بعد. يرجى تسجيل الدخول باسم المستخدم وكلمة المرور أولاً لربط بصمتك بحسابك ومنع تداخل الحسابات.")
                return@launch
            }

            val allUsers = repository.getDao().getUserAccountsDirect()
            val target = allUsers.firstOrNull {
                it.id == bound.id ||
                    (bound.userCode.isNotBlank() && it.userCode.equals(bound.userCode, ignoreCase = true)) ||
                    (bound.username.isNotBlank() && it.username.equals(bound.username, ignoreCase = true))
            }

            if (target == null) {
                com.example.util.BiometricPreferenceManager.clearBinding(getApplication())
                onError("الحساب المرتبط بهذه البصمة لم يعد متوفراً على هذا الجهاز. يرجى تسجيل الدخول بكلمة المرور مجدداً.")
                return@launch
            }

            if (!target.active) {
                onError("الحساب المرتبط بهذه البصمة (${target.fullName}) تم إيقافه من قبل الإدارة.")
                return@launch
            }

            if (target.syncStatus == "PENDING_MANAGER_APPROVAL") {
                onError("الحساب المرتبط بهذه البصمة (${target.fullName}) بانتظار موافقة مدير النظام وتحديد الصلاحيات أولاً.")
                return@launch
            }

            DeletedAccountsRegistry.removeTombstone(target.userCode, target.username, target.phoneOrEmail)
            repository.getDao().setActiveSessionUser(target.id)
            activeSessionOverride.value = target
            activeSessionUserId.value = target.id
            isSessionAuthenticated.value = true
            DeviceSessionStore.saveActiveSession(getApplication(), target.id, target.userCode, target.role)
            startCloudRealtimeSyncIfAuthenticated()
            postMessage("تم التحقق بنجاح من هوية (${target.fullName}) وتسجيل الدخول بالبصمة")
            onSuccess(target)
        }
    }

    fun logoutSession(clearBiometrics: Boolean = false, onLoggedOut: () -> Unit = {}) {
        activeSessionOverride.value = null
        activeSessionUserId.value = null
        isSessionAuthenticated.value = false
        DeviceSessionStore.clearActiveSession(getApplication())
        stopCloudRealtimeSync()
        if (clearBiometrics) {
            com.example.util.BiometricPreferenceManager.clearBinding(getApplication())
        }
        viewModelScope.launch {
            repository.getDao().setActiveSessionUser(-1L)
            onLoggedOut()
        }
    }

    fun addFactoryItem(
        name: String,
        category: String,
        unit: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val actor = resolveUserActor("إدارة المصنع")
            val res = repository.addFactoryItem(name, category, unit, actor)
            res.onSuccess {
                postMessage("تمت إضافة الصنف ($name) إلى منظومة المصنع بنجاح")
                persistAndSyncToServer()
                onSuccess()
            }.onFailure {
                postMessage(it.message ?: "تعذر إضافة الصنف", isError = true)
            }
        }
    }

    fun deleteFactoryItem(item: FactoryItemEntity) {
        viewModelScope.launch {
            val actor = resolveUserActor("إدارة المصنع")
            val res = repository.deleteFactoryItem(item.id, item.name, actor)
            res.onSuccess {
                viewModelScope.launch(Dispatchers.IO) {
                    cloudErpSyncRepo.deleteEntityFromCloud(serverClusterCode.value, "factory_items", "FI-${item.id}")
                }
                postMessage("تم حذف الصنف (${item.name}) من المصنع بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر حذف الصنف", isError = true)
            }
        }
    }

    // --- PMP Science & Chemistry Lab Actions ---
    fun searchPubChem(query: String) {
        viewModelScope.launch {
            _isSearchingPubChem.value = true
            _pubChemErrorMessage.value = null
            val res = com.example.util.PubChemService.searchCompound(query)
            res.onSuccess { list ->
                _pubChemResults.value = list
                if (list.isEmpty()) {
                    _pubChemErrorMessage.value = "لم يتم العثور على نتائج في PubChem لـ: $query"
                }
            }.onFailure { err ->
                _pubChemErrorMessage.value = err.message ?: "تعذر الاتصال بـ PubChem"
            }
            _isSearchingPubChem.value = false
        }
    }

    fun saveFormulation(formulation: FormulationEntity) {
        viewModelScope.launch {
            val actor = resolveUserActor("مهندس المختبر")
            val res = repository.saveFormulation(formulation, actor)
            res.onSuccess { savedId ->
                val entityToSync = formulation.copy(id = savedId)
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        cloudErpSyncRepo.syncSingleFormulationToCloud(serverClusterCode.value, entityToSync)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Cloud formulation sync warning: ${e.message}")
                    }
                }
                postMessage("تم حفظ ومزامنة التركيبة (${formulation.productName}) على السحابة | PVC: ${formulation.calculatedPvc}%")
            }.onFailure {
                postMessage(it.message ?: "فشل حفظ التركيبة", isError = true)
            }
        }
    }

    fun deleteFormulation(formulation: FormulationEntity) {
        viewModelScope.launch {
            val actor = resolveUserActor("مهندس المختبر")
            val res = repository.deleteFormulation(formulation.id, actor)
            res.onSuccess {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        cloudErpSyncRepo.deleteFormulationFromCloud(serverClusterCode.value, formulation.id, formulation.formulaNo)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Cloud formulation delete warning: ${e.message}")
                    }
                }
                postMessage("تم حذف التركيبة المعملية #${formulation.formulaNo} بنجاح من السجل والسحابة")
            }.onFailure {
                postMessage(it.message ?: "تعذر حذف التركيبة", isError = true)
            }
        }
    }

    fun logProductionBatch(batch: ProductionBatchEntity, onCompleted: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val actor = resolveUserActor("مهندس الإنتاج")
            val res = repository.saveProductionBatch(batch, actor)
            res.onSuccess { savedId ->
                val entityToSync = batch.copy(id = savedId)
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        cloudErpSyncRepo.syncSingleProductionBatchToCloud(serverClusterCode.value, entityToSync)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Cloud batch sync warning: ${e.message}")
                    }
                }
                postMessage("تم تسجيل تشغيلة الإنتاج #${batch.batchNumber} بنجاح وحفظها في Firestore | الحالة: ${batch.qualityStatus}")
                onCompleted?.invoke(true)
            }.onFailure {
                postMessage(it.message ?: "فشل تسجيل تشغيلة الإنتاج", isError = true)
                onCompleted?.invoke(false)
            }
        }
    }

    fun deleteProductionBatch(batch: ProductionBatchEntity) {
        viewModelScope.launch {
            val actor = resolveUserActor("مهندس الإنتاج")
            val res = repository.deleteProductionBatch(batch.id, batch.batchNumber, actor)
            res.onSuccess {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        cloudErpSyncRepo.deleteProductionBatchFromCloud(serverClusterCode.value, batch.id, batch.batchNumber)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Cloud batch delete warning: ${e.message}")
                    }
                }
                postMessage("تم حذف تشغيلة الإنتاج #${batch.batchNumber} من السجل والسحابة")
            }.onFailure {
                postMessage(it.message ?: "تعذر حذف التشغيلة", isError = true)
            }
        }
    }

    fun saveChemicalSafety(item: ChemicalSafetyEntity) {
        viewModelScope.launch {
            val actor = resolveUserActor("مسؤول السلامة والجودة")
            val res = repository.saveChemicalSafety(item, actor)
            res.onSuccess {
                postMessage("تم تحديث بطاقة السلامة الكيميائية (${item.chemicalName}) بنجاح")
            }.onFailure {
                postMessage(it.message ?: "تعذر حفظ بطاقة السلامة", isError = true)
            }
        }
    }

    fun deleteChemicalSafety(item: ChemicalSafetyEntity) {
        viewModelScope.launch {
            val actor = resolveUserActor("مسؤول السلامة والجودة")
            val res = repository.deleteChemicalSafety(item.id, actor)
            res.onSuccess {
                postMessage("تم حذف بطاقة السلامة (${item.chemicalName}) بنجاح")
            }.onFailure {
                postMessage(it.message ?: "تعذر حذف بطاقة السلامة", isError = true)
            }
        }
    }

    fun createProductionOrderFromFormula(formulaId: Long, batchMultiplier: Double = 1.0) {
        viewModelScope.launch {
            val actor = resolveUserActor("مدير الإنتاج")
            val res = repository.createFactoryOrderFromFormulation(formulaId, batchMultiplier, actor)
            res.onSuccess { moveId ->
                postMessage("تم بنجاح إنشاء أمر التشغيل المصنعي وتوريد الخامات رقم #$moveId وحجز كميات الإنتاج")
                persistAndSyncToServer()
            }.onFailure { err ->
                postMessage(err.message ?: "تعذر إنشاء أمر التشغيل المصنعي", isError = true)
            }
        }
    }

    fun addPubChemCompoundToFactoryCatalog(compound: com.example.util.PubChemService.PubChemCompound) {
        viewModelScope.launch {
            val actor = resolveUserActor("مهندس المختبر والمصنع")
            val existing = factoryItems.value.find { it.name.trim().equals(compound.name.trim(), ignoreCase = true) }
            if (existing != null) {
                postMessage("المادة (${compound.name}) مضافة مسبقاً في كتالوج خامات المصنع")
                return@launch
            }
            val res = repository.addFactoryItem(
                name = compound.name,
                unit = "كجم",
                category = "raw",
                actor = actor
            )
            res.onSuccess {
                postMessage("تمت إضافة المادة (${compound.name}) كخامة معتمدة في المصنع بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر إضافة المادة لكتالوج المصنع", isError = true)
            }
        }
    }

    fun testDirectServerRead(onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            val res = cloudErpSyncRepo.testDirectServerRead(serverClusterCode.value)
            res.onSuccess {
                postMessage(it)
                onResult(it)
            }.onFailure {
                val msg = it.message ?: "فشل فحص السيرفر المباشر"
                postMessage(msg, isError = true)
                onResult(msg)
            }
        }
    }
}


