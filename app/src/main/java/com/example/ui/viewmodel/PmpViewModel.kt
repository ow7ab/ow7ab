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
import com.example.isSuperAdminAccount
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

    private val currencyPrefs = application.getSharedPreferences("pmp_account_currency_prefs", android.content.Context.MODE_PRIVATE)
    private val _defaultAccountCurrency = MutableStateFlow(
        currencyPrefs.getString("default_account_currency", "SDG") ?: "SDG"
    )
    val defaultAccountCurrency: StateFlow<String> = _defaultAccountCurrency.asStateFlow()

    private val themePrefs = application.getSharedPreferences("pmp_ui_theme_prefs", android.content.Context.MODE_PRIVATE)
    private val _themeMode = MutableStateFlow(
        runCatching {
            val savedKey = themePrefs.getString("theme_mode", com.example.ui.theme.PmpThemeMode.LIGHT.storageKey)
                ?: com.example.ui.theme.PmpThemeMode.LIGHT.storageKey
            com.example.ui.theme.PmpThemeMode.entries.firstOrNull { it.storageKey == savedKey }
                ?: com.example.ui.theme.PmpThemeMode.LIGHT
        }.getOrDefault(com.example.ui.theme.PmpThemeMode.LIGHT)
    )
    val themeMode: StateFlow<com.example.ui.theme.PmpThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: com.example.ui.theme.PmpThemeMode) {
        _themeMode.value = mode
        themePrefs.edit().putString("theme_mode", mode.storageKey).apply()
        val isDark = when (mode) {
            com.example.ui.theme.PmpThemeMode.DARK -> true
            com.example.ui.theme.PmpThemeMode.LIGHT -> false
            com.example.ui.theme.PmpThemeMode.SYSTEM -> {
                val nightFlags = getApplication<Application>().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
        com.example.ui.theme.PmpThemeController.updateDarkModeState(isDark, mode)
        postMessage("تم تفعيل وضع المظهر: ${mode.arabicLabel}")
    }

    fun toggleDarkModeQuick() {
        val nextMode = if (com.example.ui.theme.PmpThemeController.isDarkModeActive) {
            com.example.ui.theme.PmpThemeMode.LIGHT
        } else {
            com.example.ui.theme.PmpThemeMode.DARK
        }
        setThemeMode(nextMode)
    }

    fun setDefaultAccountCurrency(currency: String) {
        val clean = currency.trim().uppercase().takeIf { it in listOf("SDG", "EGP", "USD") } ?: "SDG"
        _defaultAccountCurrency.value = clean
        currencyPrefs.edit().putString("default_account_currency", clean).apply()
        postMessage("تم توحيد عملة معاملات الحساب الافتراضية على ($clean) — ويمكنك تغيير العملة من داخل أي معاملة عند الحاجة")
    }

    fun resolveCashboxForCurrency(currency: String): String {
        val clean = currency.trim().uppercase()
        val boxes = cashboxes.value
        return when (clean) {
            "EGP" -> boxes.firstOrNull { it.name.contains("مصر") || it.name.contains("القاهرة") || it.name.contains("EGP", ignoreCase = true) }?.name ?: "خزنة مصر"
            "USD" -> boxes.firstOrNull { it.name.contains("دولار") || it.name.contains("USD", ignoreCase = true) }?.name ?: "خزنة السودان"
            else -> boxes.firstOrNull { it.name.contains("سودان") || it.name.contains("خرطوم") }?.name ?: "خزنة السودان"
        }
    }

    fun lookupProductSalePrice(product: ProductEntity, currency: String): Double {
        val cleanCur = currency.trim().uppercase()
        val posters = posterPrices.value
        if (cleanCur == "EGP") {
            val egpPoster = posters.firstOrNull {
                (it.currency.equals("EGP", ignoreCase = true) || it.id.endsWith("_EGP")) &&
                    it.kind.equals(product.paintColor, ignoreCase = true) &&
                    it.weight == product.packageWeight
            }
            if (egpPoster != null) {
                val p = if (egpPoster.priceEgp > 0L && egpPoster.price == egpPoster.priceEgp * 200L) egpPoster.priceEgp else egpPoster.price
                if (p > 0L) return p.toDouble()
            }
            return product.getEffectiveSalePrice("EGP")
        } else if (cleanCur == "SDG") {
            val sdgPoster = posters.firstOrNull {
                !it.id.endsWith("_EGP") &&
                    it.currency.equals("SDG", ignoreCase = true) &&
                    it.kind.equals(product.paintColor, ignoreCase = true) &&
                    it.weight == product.packageWeight
            }
            if (sdgPoster != null && sdgPoster.price > 0L) {
                return sdgPoster.price.toDouble()
            }
            return product.getEffectiveSalePrice("SDG")
        }
        return 0.0
    }

    // --- Real Data Loading & Error States (Zero artificial delay, cached in memory across tab switches) ---
    private val _isInitialDataLoading = MutableStateFlow(true)
    val isInitialDataLoading: StateFlow<Boolean> = _isInitialDataLoading.asStateFlow()

    private val _dataLoadError = MutableStateFlow<String?>(null)
    val dataLoadError: StateFlow<String?> = _dataLoadError.asStateFlow()

    // --- StateFlow Streams (SharingStarted.Lazily keeps loaded data hot in memory across tab switches) ---
    val cashboxes = repository.getCashboxes()
        .map { list ->
            list.filter { cb ->
                !DeletedRecordsRegistry.isDeleted("cashboxes", cb.id) &&
                    !DeletedRecordsRegistry.isDeleted("cashboxes", cb.name)
            }.distinctBy { it.name.trim().lowercase() }
        }
        .flowOn(Dispatchers.Default)
        .onEach { _isInitialDataLoading.value = false }
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in cashboxes flow", e)
            _isInitialDataLoading.value = false
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val movements = repository.getMovements()
        .map { list -> list.filter { !DeletedRecordsRegistry.isMovementDeleted(it) } }
        .flowOn(Dispatchers.Default)
        .onEach { _isInitialDataLoading.value = false }
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in movements flow", e)
            _isInitialDataLoading.value = false
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val transfers = repository.getTransfers()
        .map { list ->
            list.filter { trf ->
                !DeletedRecordsRegistry.isPreEpoch(trf.createdAt) &&
                    !DeletedRecordsRegistry.isDeleted("transfers", trf.id) &&
                    !DeletedRecordsRegistry.isDeleted("transfers", "TRF-${trf.id}") &&
                    (trf.reference.isBlank() || !DeletedRecordsRegistry.isDeleted("transfers", trf.reference))
            }
        }
        .flowOn(Dispatchers.Default)
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in transfers flow", e)
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val dailyClosings = repository.getDailyClosings()
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in dailyClosings flow", e)
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val customers = repository.getCustomers()
        .map { list ->
            list.filter { c ->
                !DeletedRecordsRegistry.isPreEpoch(c.createdAt) &&
                    !DeletedRecordsRegistry.isDeleted("customers", c.id) &&
                    !DeletedRecordsRegistry.isDeleted("customers", "CUST-${c.id}") &&
                    (c.name.isBlank() || !DeletedRecordsRegistry.isDeleted("customers", c.name))
            }
        }
        .flowOn(Dispatchers.Default)
        .onEach { _isInitialDataLoading.value = false }
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in customers flow", e)
            _isInitialDataLoading.value = false
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val invoices = repository.getInvoices()
        .map { list ->
            list.filter { inv ->
                !DeletedRecordsRegistry.isPreEpoch(inv.createdAt) &&
                    !DeletedRecordsRegistry.isDeleted("invoices", inv.id) &&
                    !DeletedRecordsRegistry.isDeleted("invoices", "INV-${inv.id}") &&
                    (inv.invoiceNo.isBlank() || !DeletedRecordsRegistry.isDeleted("invoices", inv.invoiceNo))
            }
        }
        .flowOn(Dispatchers.Default)
        .onEach { _isInitialDataLoading.value = false }
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in invoices flow", e)
            _isInitialDataLoading.value = false
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val invoiceItems = repository.getAllInvoiceItems()
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in invoiceItems flow", e)
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val invoicePayments = repository.getInvoicePayments()
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in invoicePayments flow", e)
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val warehouses = repository.getWarehouses()
        .map { list ->
            list.filter { wh ->
                !DeletedRecordsRegistry.isDeleted("warehouses", wh.id) &&
                    !DeletedRecordsRegistry.isDeleted("warehouses", "WH-${wh.id}") &&
                    !DeletedRecordsRegistry.isDeleted("warehouses", wh.name)
            }.distinctBy { it.name.trim().lowercase() }
        }
        .onEach { _isInitialDataLoading.value = false }
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in warehouses flow", e)
            _isInitialDataLoading.value = false
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val weightManager: com.example.util.InventoryWeightManager =
        com.example.util.InventoryWeightManager.getInstance(application)

    val packageWeights: StateFlow<List<com.example.util.PackageWeightSpec>> = weightManager.weights

    val activePackageWeights: StateFlow<List<com.example.util.PackageWeightSpec>> = weightManager.weights
        .map { list -> list.filter { it.active } }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            weightManager.weights.value.filter { it.active }
        )

    val selectedInventoryWeight: StateFlow<String> = weightManager.selectedWeight

    val products = repository.getProducts()
        .onEach { list ->
            _isInitialDataLoading.value = false
            if (list.isNotEmpty()) {
                weightManager.syncDiscoveredProductWeights(list.map { it.packageWeight })
            }
        }
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in products flow", e)
            _isInitialDataLoading.value = false
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val stockMoves = repository.getStockMoves()
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in stockMoves flow", e)
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val factoryItems = repository.getFactoryItems()
        .onEach { _isInitialDataLoading.value = false }
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in factoryItems flow", e)
            _isInitialDataLoading.value = false
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val factoryMachines = repository.getFactoryMachines()
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in factoryMachines flow", e)
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val factoryMoves = repository.getFactoryMoves()
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in factoryMoves flow", e)
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // --- Phase 2 & Phase 3 Enterprise ERP Reactive Flows ---
    val erpMovements = repository.getErpMovements()
        .catch { emit(emptyList()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val erpStockBalances = repository.getErpStockBalances()
        .catch { emit(emptyList()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val erpTransfers = repository.getErpTransfers()
        .catch { emit(emptyList()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val packagingRulesDb = repository.getPackagingRulesDb()
        .catch { emit(emptyList()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val purchaseReceipts = repository.getPurchaseReceipts()
        .catch { emit(emptyList()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val productionOrdersDb = repository.getProductionOrdersDb()
        .catch { emit(emptyList()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val qcInspections = repository.getQcInspections()
        .catch { emit(emptyList()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // --- Phase 4 Double-Entry General Ledger Reactive Flows ---
    val accountingAccounts = repository.getAccountingAccounts()
        .catch { emit(emptyList()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val journalEntries = repository.getJournalEntries()
        .catch { emit(emptyList()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val journalLines = repository.getJournalLines()
        .catch { emit(emptyList()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val halfaInvoices = repository.getHalfaInvoices()
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in halfaInvoices flow", e)
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val posterPrices = repository.getPosterPrices()
        .onEach { _isInitialDataLoading.value = false }
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in posterPrices flow", e)
            _isInitialDataLoading.value = false
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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
        .catch { emit(defaultPaintColors) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, defaultPaintColors)

    val auditLogs = repository.getAuditLogs()
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in auditLogs flow", e)
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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
        .flowOn(Dispatchers.Default)
        .onEach { _isInitialDataLoading.value = false }
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in userAccounts flow", e)
            _isInitialDataLoading.value = false
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val formulations = repository.getFormulations()
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in formulations flow", e)
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val chemicalSafetyList = repository.getChemicalSafety()
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in chemicalSafetyList flow", e)
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val productionBatches = repository.getProductionBatches()
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in productionBatches flow", e)
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())


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

    private val _isInitializingSession = MutableStateFlow(true)
    val isInitializingSession: StateFlow<Boolean> = _isInitializingSession.asStateFlow()

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

    // --- Derived Calculated States via SQL Aggregations (SUM / GROUP BY on Dispatchers.IO) ---
    val currencyBalances: StateFlow<Map<String, Double>> = repository.getAggregatedCurrencyBalances()
        .map { rows ->
            val balances = LinkedHashMap<String, Double>(rows.size)
            for (row in rows) {
                balances[row.currency] = row.netBalance
            }
            balances as Map<String, Double>
        }
        .flowOn(Dispatchers.IO)
        .catch { emit(emptyMap()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val cashboxBalances: StateFlow<Map<Pair<String, String>, Double>> = repository.getAggregatedCashboxBalances()
        .map { rows ->
            val map = LinkedHashMap<Pair<String, String>, Double>(rows.size)
            for (row in rows) {
                map[Pair(row.cashbox.trim(), row.currency.trim())] = row.netBalance
            }
            map as Map<Pair<String, String>, Double>
        }
        .flowOn(Dispatchers.IO)
        .catch { emit(emptyMap()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val productStockBalances: StateFlow<Map<Long, Double>> = repository.getAggregatedProductStockBalances()
        .map { rows ->
            val map = LinkedHashMap<Long, Double>(rows.size)
            for (row in rows) {
                map[row.productId] = row.netBalance
            }
            map as Map<Long, Double>
        }
        .flowOn(Dispatchers.IO)
        .catch { emit(emptyMap()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // --- In-App Update & Cloud Diagnostics Properties (Must be initialized BEFORE init {}) ---
    private val updateManager: UpdateManager = UpdateManager.getInstance(application)

    val effectiveVersionCode: StateFlow<Int> = updateManager.effectiveVersionCode
    val effectiveVersionName: StateFlow<String> = updateManager.effectiveVersionName

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

    private val _availableUpdate = MutableStateFlow<UpdateInfo?>(null)
    val availableUpdate: StateFlow<UpdateInfo?> = _availableUpdate.asStateFlow()

    private val _isUpdateBannerDismissed = MutableStateFlow(false)
    val isUpdateBannerDismissed: StateFlow<Boolean> = _isUpdateBannerDismissed.asStateFlow()

    private val _updateUiState = MutableStateFlow(com.example.util.UpdateUiState.IDLE)
    val updateUiState: StateFlow<com.example.util.UpdateUiState> = _updateUiState.asStateFlow()

    private val _lastUpdateCheckStatus = MutableStateFlow(
        "أنت تستخدم أحدث إصدار من PMP PAINTS."
    )
    val lastUpdateCheckStatus: StateFlow<String> = _lastUpdateCheckStatus.asStateFlow()

    private val _updateProgress = MutableStateFlow(0)
    val updateProgress: StateFlow<Int> = _updateProgress.asStateFlow()

    private val _isDownloadingUpdate = MutableStateFlow(false)
    val isDownloadingUpdate: StateFlow<Boolean> = _isDownloadingUpdate.asStateFlow()

    val downloadState: StateFlow<com.example.util.DownloadState> = updateManager.downloadState

    private val _downloadedApkFile = MutableStateFlow<File?>(null)
    val downloadedApkFile: StateFlow<File?> = _downloadedApkFile.asStateFlow()

    val customUpdateMetadataUrl = MutableStateFlow(UpdateManager.DEFAULT_UPDATE_METADATA_URL)

    private val _buildIdentityReport = MutableStateFlow(
        updateManager.getInstalledBuildIdentityReport(
            updateSourceUrl = UpdateManager.DEFAULT_UPDATE_METADATA_URL,
            latestRemoteUpdate = null,
            computeHeavyApkHash = false
        )
    )
    val buildIdentityReport: StateFlow<com.example.util.BuildIdentityReport> = _buildIdentityReport.asStateFlow()

    private val _isUploadingVersionJson = MutableStateFlow(false)
    val isUploadingVersionJson: StateFlow<Boolean> = _isUploadingVersionJson.asStateFlow()

    private val _firebaseUploadProgress = MutableStateFlow(0)
    val firebaseUploadProgress: StateFlow<Int> = _firebaseUploadProgress.asStateFlow()

    private val _firebaseUploadStageText = MutableStateFlow("")
    val firebaseUploadStageText: StateFlow<String> = _firebaseUploadStageText.asStateFlow()

    private val _isUploadingUpdateToFirebase = MutableStateFlow(false)
    val isUploadingUpdateToFirebase: StateFlow<Boolean> = _isUploadingUpdateToFirebase.asStateFlow()

    private val _lastUploadedVersionJsonUrl = MutableStateFlow(UpdateManager.FIREBASE_STORAGE_VERSION_JSON_URL)
    val lastUploadedVersionJsonUrl: StateFlow<String> = _lastUploadedVersionJsonUrl.asStateFlow()

    private val _versionJsonUploadStatus = MutableStateFlow("جاهز لرفع ملف version.json إلى Firebase Storage وتحديث صفحة التنزيل تلقائياً")
    val versionJsonUploadStatus: StateFlow<String> = _versionJsonUploadStatus.asStateFlow()

    val syncDiagnostics = cloudErpSyncRepo.diagnostics
    val lastSuccessfulSyncTime = cloudErpSyncRepo.lastSuccessfulSyncTime
    val pendingSyncErrors = cloudErpSyncRepo.pendingErrors
    val structuredCloudErrors: StateFlow<List<com.example.data.remote.CloudErrorLogEntry>> =
        cloudErpSyncRepo.structuredErrors
    val lastSyncStepSummaries: StateFlow<List<com.example.data.remote.SyncStepSummary>> =
        cloudErpSyncRepo.syncStepSummaries
    val lastPreflightResult: StateFlow<com.example.data.remote.PreflightCheckResult?> =
        cloudErpSyncRepo.lastPreflightResult
    val serviceProbes: StateFlow<List<com.example.data.remote.ServiceProbeResult>> =
        cloudErpSyncRepo.serviceProbes
    val comprehensiveDiagnosticReport: StateFlow<com.example.data.remote.ComprehensiveDiagnosticReport?> =
        cloudErpSyncRepo.comprehensiveReport
    val transactionTraces = com.example.util.RoomTransactionLogger.traces
    val pendingApprovalUserCode = MutableStateFlow<String?>(null)

    // --- Enterprise ERP Lifecycle StateFlows (Declared strictly before init) ---
    private val _packagingRules = MutableStateFlow(
        com.example.util.ErpLifecycleEngine.getPackagingRules(application)
    )
    val packagingRules: StateFlow<List<com.example.util.PackagingRuleSpec>> = _packagingRules.asStateFlow()

    private val _purchaseOrders = MutableStateFlow(
        com.example.util.ErpLifecycleEngine.getPurchaseOrders(application)
    )
    val purchaseOrders: StateFlow<List<com.example.util.PurchaseOrderRecord>> = _purchaseOrders.asStateFlow()

    private val _goodsReceipts = MutableStateFlow(
        com.example.util.ErpLifecycleEngine.getGoodsReceipts(application)
    )
    val goodsReceipts: StateFlow<List<com.example.util.GoodsReceiptNoteRecord>> = _goodsReceipts.asStateFlow()

    private val _productionOrders = MutableStateFlow(
        com.example.util.ErpLifecycleEngine.getProductionOrders(application)
    )
    val productionOrders: StateFlow<List<com.example.util.ProductionOrderRecord>> = _productionOrders.asStateFlow()

    // --- Phase 02 Advanced Authentication & Security StateFlows (Declared strictly before init) ---
    val twoFactorPendingUser = MutableStateFlow<UserAccountEntity?>(null)

    private val _securityActivities = MutableStateFlow(
        com.example.util.AuthSecurityManager.getLoginActivities(application)
    )
    val securityActivities: StateFlow<List<com.example.util.LoginActivityRecord>> = _securityActivities.asStateFlow()

    private val _suspiciousAlerts = MutableStateFlow(
        com.example.util.AuthSecurityManager.getSuspiciousAlerts(application)
    )
    val suspiciousAlerts: StateFlow<List<com.example.util.SuspiciousLoginAlert>> = _suspiciousAlerts.asStateFlow()

    private val _trustedDevices = MutableStateFlow<List<com.example.util.TrustedDeviceRecord>>(emptyList())
    val trustedDevices: StateFlow<List<com.example.util.TrustedDeviceRecord>> = _trustedDevices.asStateFlow()

    private val _currentTwoFactorConfig = MutableStateFlow<com.example.util.TwoFactorConfig?>(null)
    val currentTwoFactorConfig: StateFlow<com.example.util.TwoFactorConfig?> = _currentTwoFactorConfig.asStateFlow()

    private val _latestGeneratedRecoveryCodes = MutableStateFlow<List<String>>(emptyList())
    val latestGeneratedRecoveryCodes: StateFlow<List<String>> = _latestGeneratedRecoveryCodes.asStateFlow()

    private val _sessionMaxAgeHours = MutableStateFlow(
        (DeviceSessionStore.getSessionMaxAgeMs(application) / (60 * 60 * 1000L)).toInt().coerceAtLeast(1)
    )
    val sessionMaxAgeHours: StateFlow<Int> = _sessionMaxAgeHours.asStateFlow()

    private val _sessionIdleTimeoutMinutes = MutableStateFlow(
        (DeviceSessionStore.getSessionIdleTimeoutMs(application) / (60 * 1000L)).toInt().coerceAtLeast(5)
    )
    val sessionIdleTimeoutMinutes: StateFlow<Int> = _sessionIdleTimeoutMinutes.asStateFlow()

    // --- Phase 03 Enterprise RBAC Permission Change History (Declared strictly before init) ---
    private val _permissionChangeHistory = MutableStateFlow(
        com.example.util.EnterpriseRbacManager.getPermissionChangeHistory(context = application)
    )
    val permissionChangeHistory: StateFlow<List<com.example.util.EnterpriseRbacManager.PermissionChangeAuditEntry>> =
        _permissionChangeHistory.asStateFlow()

    // --- Phase 5 TransactionSubmissionGuard & Idempotency Protection System (Declared strictly before init) ---
    val idempotencyGuard: com.example.util.TransactionSubmissionGuard = com.example.util.TransactionSubmissionGuard(
        idempotencyDao = database.idempotencyDao(),
        backendAdapter = cloudErpSyncRepo.asIdempotencyBackendAdapter(),
        clusterCodeProvider = { serverClusterCode.value },
        userIdProvider = { resolveUserActor("SYSTEM") },
        isOnlineProvider = { isConnectedToCloud() }
    )
    val operationTrackingRecords: StateFlow<List<IdempotencyRequestEntity>> = database.idempotencyDao()
        .observeAll()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val activeGuardKeys: StateFlow<Set<String>> = idempotencyGuard.activeGuardKeys
    val guardLoadingLabels: StateFlow<Map<String, String>> = idempotencyGuard.guardLoadingLabels
    val guardStatusMessages: StateFlow<Map<String, String>> = idempotencyGuard.guardStatusMessages
    val pendingOperationsCount: StateFlow<Int> = operationTrackingRecords
        .map { list ->
            list.count {
                it.requestStatus == com.example.util.IdempotencyRequestStatus.PROCESSING.code ||
                    it.requestStatus == com.example.util.IdempotencyRequestStatus.PENDING_CONFIRMATION.code
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)
    private var wasNetworkOnlineForRecovery: Boolean = isConnectedToCloud()
    private var periodicUpdateSchedulerJob: kotlinx.coroutines.Job? = null

    init {
        val initialMode = _themeMode.value
        val isDarkInitial = when (initialMode) {
            com.example.ui.theme.PmpThemeMode.DARK -> true
            com.example.ui.theme.PmpThemeMode.LIGHT -> false
            com.example.ui.theme.PmpThemeMode.SYSTEM -> {
                val nightFlags = application.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
        com.example.ui.theme.PmpThemeController.updateDarkModeState(isDarkInitial, initialMode)

        // Restore active user session BEFORE hiding splash screen so LoginScreen never flashes when already logged in
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DeletedAccountsRegistry.init(application)
                DeletedRecordsRegistry.init(application)
                val savedDeviceId = DeviceSessionStore.getActiveUserId(application)
                val savedUserCode = DeviceSessionStore.getActiveUserCode(application)
                if (savedDeviceId > 0L || savedUserCode.isNotBlank()) {
                    val dao = repository.getDao()
                    var directUser = if (savedUserCode.isNotBlank()) {
                        dao.getUserAccountsDirect().firstOrNull {
                            it.userCode.equals(savedUserCode, ignoreCase = true)
                        } ?: dao.getUserById(savedDeviceId)
                    } else {
                        dao.getUserById(savedDeviceId)
                    }
                    if (directUser == null) {
                        DatabaseBackupManager.restoreIfEmpty(application, dao)
                        directUser = dao.getUserAccountsDirect().firstOrNull {
                            (savedUserCode.isNotBlank() && it.userCode.equals(savedUserCode, ignoreCase = true)) ||
                                it.id == savedDeviceId
                        }
                    }
                    if (directUser == null) {
                        val seededList = userAccounts.firstOrNull { it.isNotEmpty() }.orEmpty()
                        directUser = seededList.firstOrNull {
                            (savedUserCode.isNotBlank() && it.userCode.equals(savedUserCode, ignoreCase = true)) ||
                                it.id == savedDeviceId
                        }
                    }
                    val validation = DeviceSessionStore.validateSessionForUser(application, directUser)
                    if (directUser != null && validation.isValid) {
                        DeviceSessionStore.touchSessionActivity(application)
                        activeSessionOverride.value = directUser
                        activeSessionUserId.value = directUser.id
                        isSessionAuthenticated.value = true
                        refreshSecurityStateForUser(directUser)
                        _isInitializingSession.value = false
                    } else {
                        DeviceSessionStore.clearActiveSession(application)
                        activeSessionOverride.value = null
                        activeSessionUserId.value = null
                        isSessionAuthenticated.value = false
                        _isInitializingSession.value = false
                    }
                } else {
                    // Fresh installation or explicitly logged out -> show LoginScreen after splash check
                    activeSessionOverride.value = null
                    activeSessionUserId.value = null
                    isSessionAuthenticated.value = false
                    _isInitializingSession.value = false
                }
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Session init check notice: ${e.message}")
                _isInitializingSession.value = false
            }
        }

        // Instant, smooth startup: load local database immediately, then perform background sync asynchronously
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DeletedAccountsRegistry.init(application)
                DeletedRecordsRegistry.init(application)
                DatabaseBackupManager.restoreIfEmpty(application, repository.getDao())
                repository.deduplicateDatabaseUsers()
                _dataLoadError.value = null
                _isInitialDataLoading.value = false
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Local cache restore note: ${e.message}")
                _isInitialDataLoading.value = false
            }

            // تهيئة قناة إشعارات التحديث والاشتراك في موضوع FCM
            runCatching {
                com.example.util.UpdateNotificationHelper.ensureChannelAndSubscribe(application)
            }

            // مزامنة حالة آلة حالات التحديث (UpdateUiState) مع DownloadState
            viewModelScope.launch {
                updateManager.downloadState.collect { dlState ->
                    when (dlState) {
                        is com.example.util.DownloadState.Downloading -> {
                            _updateUiState.value = com.example.util.UpdateUiState.DOWNLOADING
                            if (dlState.progressPercent > 0) {
                                _updateProgress.value = dlState.progressPercent
                            }
                            _lastUpdateCheckStatus.value = "جاري تنزيل التحديث... (${dlState.progressPercent}%)"
                        }
                        is com.example.util.DownloadState.VerifyingChecksum -> {
                            _updateUiState.value = com.example.util.UpdateUiState.VERIFYING
                            _updateProgress.value = 99
                            _lastUpdateCheckStatus.value = "جاري التحقق من سلامة ملف التحديث..."
                        }
                        is com.example.util.DownloadState.ReadyToInstall -> {
                            _downloadedApkFile.value = dlState.apkFile
                            _updateProgress.value = 100
                            _updateUiState.value = com.example.util.UpdateUiState.READY_TO_INSTALL
                            _lastUpdateCheckStatus.value = "تم تنزيل التحديث بنجاح."
                        }
                        is com.example.util.DownloadState.Installing -> {
                            _updateUiState.value = com.example.util.UpdateUiState.INSTALLING
                            _lastUpdateCheckStatus.value = "جاري تثبيت التحديث..."
                        }
                        is com.example.util.DownloadState.Failed -> {
                            _updateUiState.value = com.example.util.UpdateUiState.FAILED
                            _lastUpdateCheckStatus.value =
                                UpdateManager.sanitizeUserFacingErrorMessage(dlState.reason)
                        }
                        is com.example.util.DownloadState.Cancelled -> {
                            _updateUiState.value = com.example.util.UpdateUiState.CANCELLED
                            _lastUpdateCheckStatus.value = "تم إيقاف تنزيل التحديث مؤقتاً. يمكنك إعادة المحاولة في أي وقت."
                        }
                        is com.example.util.DownloadState.Idle -> {
                            if (!_isCheckingUpdate.value) {
                                _updateUiState.value = if (_updateAvailable.value && _availableUpdate.value != null) {
                                    com.example.util.UpdateUiState.AVAILABLE
                                } else {
                                    com.example.util.UpdateUiState.UP_TO_DATE
                                }
                            }
                        }
                    }
                }
            }

            // الاستماع لإشارات إشعارات FCM الواردة عند نشر إصدار جديد
            viewModelScope.launch {
                com.example.util.UpdateNotificationHelper.fcmUpdateSignal.collect { signal ->
                    if (signal > 0) {
                        checkForAppUpdates(manual = false)
                    }
                }
            }

            // التحقق الفعلي من PackageManager بعد أي عملية تثبيت سابقة، والاستماع اللحظي للتحديثات السحابية
            viewModelScope.launch {
                try {
                    verifyPostInstallAndRefreshVersion()

                    updateManager.installStatusMessage.collect { statusPair ->
                        if (statusPair != null) {
                            val (msg, isOk) = statusPair
                            val cleanMsg = if (isOk) msg else UpdateManager.sanitizeUserFacingErrorMessage(msg)
                            postMessage(cleanMsg, isError = !isOk)
                            updateManager.clearInstallStatusMessage()
                            verifyPostInstallAndRefreshVersion()
                        }
                    }
                } catch (e: Exception) {
                    Log.w("PmpViewModel", "Install status observer notice: ${e.message}")
                }
            }

            viewModelScope.launch {
                try {
                    updateManager.observeCloudUpdatesRealtime(serverClusterCode.value) { liveUpdate ->
                        viewModelScope.launch {
                            val installedCode = updateManager.getEffectiveVersionCode()
                            val verifiedReachable = if (liveUpdate.versionCode > installedCode) {
                                updateManager.verifyCandidateApkReachability(liveUpdate)
                            } else {
                                null
                            }
                            if (verifiedReachable != null && verifiedReachable.versionCode > installedCode) {
                                _availableUpdate.value = verifiedReachable
                                _updateAvailable.value = true
                                _isUpdateBannerDismissed.value =
                                    !verifiedReachable.isMandatory &&
                                        updateManager.isUpdateNotificationSnoozed(verifiedReachable.versionCode)
                                if (_updateUiState.value != com.example.util.UpdateUiState.DOWNLOADING &&
                                    _updateUiState.value != com.example.util.UpdateUiState.READY_TO_INSTALL &&
                                    _updateUiState.value != com.example.util.UpdateUiState.INSTALLING
                                ) {
                                    _updateUiState.value = com.example.util.UpdateUiState.AVAILABLE
                                    _lastUpdateCheckStatus.value = "يتوفر إصدار جديد يتضمن تحسينات وإصلاحات."
                                }
                                com.example.util.UpdateNotificationHelper.notifyNewUpdateAvailableIfNeeded(
                                    context = application,
                                    updateInfo = verifiedReachable,
                                    installedVersionCode = installedCode
                                )
                            } else {
                                _availableUpdate.value = null
                                _updateAvailable.value = false
                                _isUpdateBannerDismissed.value = false
                                if (!_isDownloadingUpdate.value) {
                                    _updateUiState.value = com.example.util.UpdateUiState.UP_TO_DATE
                                    _lastUpdateCheckStatus.value = "أنت تستخدم أحدث إصدار من PMP PAINTS."
                                }
                            }
                            refreshBuildIdentityReport()
                        }
                    }
                    checkForAppUpdates(manual = false)
                    startPeriodicUpdateScheduler()
                } catch (e: Exception) {
                    Log.w("PmpViewModel", "Cloud update init notice: ${e.message}")
                }
            }

            viewModelScope.launch {
                currentUser.collect { user ->
                    val isAdmin = user.isSuperAdminAccount() || user?.username?.equals("admin", ignoreCase = true) == true
                    updateManager.setAdminBroadcastGuardEnabled(isAdmin)
                    refreshBuildIdentityReport()
                }
            }

            // Defer network sync slightly so initial screen displays instantly without any freeze
            kotlinx.coroutines.delay(1500)
            try {
                val devId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(application)
                val activeId = activeSessionUserId.value
                val isMgr = currentUser.value.isSystemManager()
                cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao(), activeId, devId)
                cloudErpSyncRepo.syncAllLocalToCloud(serverClusterCode.value, repository.getDao(), isMgr, activeId, devId)
                DatabaseBackupManager.saveLocalSnapshot(application, repository.getDao())
                checkServerHealth()
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Deferred cloud cluster sync: ${e.message}")
            }

            viewModelScope.launch(Dispatchers.IO) {
                runCatching { idempotencyGuard.recoverPendingOperations() }
                isNetworkOnline.collect { online ->
                    if (online && !wasNetworkOnlineForRecovery) {
                        runCatching {
                            val recovered = idempotencyGuard.recoverPendingOperations()
                            val confirmed = recovered.count {
                                it.resolvedStatus == com.example.util.IdempotencyRequestStatus.COMPLETED
                            }
                            if (confirmed > 0) {
                                postMessage("تم التحقق التلقائي بعد عودة الاتصال: تم تأكيد $confirmed عملية سابقة.")
                            }
                        }
                    }
                    wasNetworkOnlineForRecovery = online
                }
            }
        }
        startCloudRealtimeSyncIfAuthenticated()
    }

    fun retryDataLoadAndSync() {
        _dataLoadError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val devId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(getApplication())
                val activeId = activeSessionUserId.value
                val isMgr = currentUser.value.isSystemManager()
                DatabaseBackupManager.restoreIfEmpty(getApplication(), repository.getDao())
                cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao(), activeId, devId)
                val res = cloudErpSyncRepo.syncAllLocalToCloud(serverClusterCode.value, repository.getDao(), isMgr, activeId, devId)
                res.onFailure { err ->
                    Log.w("PmpViewModel", "retryDataLoadAndSync sync failure: ${err.message}")
                }
                _isInitialDataLoading.value = false
            } catch (e: Exception) {
                _isInitialDataLoading.value = false
                if (!isConnectedToCloud()) {
                    _dataLoadError.value = "تعذر الاتصال بالشبكة لتحديث البيانات السحابية. يتم عرض البيانات المحفوظة محلياً."
                }
            }
        }
    }

    // --- Phase 5 Transaction Submission Guard & Operation Tracking Actions ---

    fun isGuardActive(guardKey: String): Boolean = activeGuardKeys.value.contains(guardKey)

    fun loadingLabelFor(guardKey: String, defaultLabel: String = ""): String =
        guardLoadingLabels.value[guardKey] ?: defaultLabel

    fun verifyOperationStatus(
        idempotencyKey: String,
        onVerified: ((com.example.util.OperationVerificationResult) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val result = idempotencyGuard.verifyOperationStatus(idempotencyKey)
            postMessage(
                result.message,
                isError = result.resolvedStatus == com.example.util.IdempotencyRequestStatus.PENDING_CONFIRMATION ||
                    result.resolvedStatus == com.example.util.IdempotencyRequestStatus.FAILED
            )
            onVerified?.invoke(result)
        }
    }

    fun recoverPendingOperations() {
        viewModelScope.launch(Dispatchers.IO) {
            val results = idempotencyGuard.recoverPendingOperations()
            if (results.isNotEmpty()) {
                val confirmedCount = results.count {
                    it.resolvedStatus == com.example.util.IdempotencyRequestStatus.COMPLETED
                }
                if (confirmedCount > 0) {
                    postMessage("تم التحقق من حالة العمليات المعلقة وتأكيد $confirmedCount عملية.")
                }
            }
        }
    }

    fun dismissUnconfirmedOperation(idempotencyKey: String) {
        viewModelScope.launch {
            idempotencyGuard.dismissCancelledOrFailedOperation(idempotencyKey)
        }
    }

    private fun <T> launchGuardedTransaction(
        guardKey: String,
        operationType: com.example.util.IdempotencyOperationType,
        actionLabel: String,
        documentReference: String = "",
        targetCollection: String = "",
        payloadSummary: String = "",
        requiresOnline: Boolean = true,
        requiresServerClaim: Boolean = true,
        onComplete: ((Boolean) -> Unit)? = null,
        block: suspend (idempotencyKey: String) -> Result<Pair<String, T>>
    ) {
        if (idempotencyGuard.isProcessing(guardKey)) {
            postMessage(com.example.util.IdempotencyStatusMessages.IN_PROGRESS)
            return
        }
        if (requiresOnline && !checkOnlineBeforeTransaction(actionLabel)) {
            onComplete?.invoke(false)
            return
        }
        viewModelScope.launch {
            val result = idempotencyGuard.executeGuarded(
                guardKey = guardKey,
                operationType = operationType,
                documentReference = documentReference,
                targetCollection = targetCollection,
                payloadSummary = payloadSummary,
                requiresServerClaim = requiresServerClaim && requiresOnline,
                scopeForSlowMonitor = viewModelScope,
                block = block
            )
            if (result.blockedAsDuplicate) {
                if (result.alreadyRecorded) {
                    postMessage(com.example.util.IdempotencyStatusMessages.ALREADY_RECORDED)
                } else if (result.pendingConfirmation) {
                    postMessage(com.example.util.IdempotencyStatusMessages.PENDING_DO_NOT_REENTER, isError = true)
                } else {
                    postMessage(com.example.util.IdempotencyStatusMessages.IN_PROGRESS)
                }
                onComplete?.invoke(result.alreadyRecorded)
                return@launch
            }
            if (!result.success) {
                val errMsg = if (result.pendingConfirmation) {
                    com.example.util.IdempotencyStatusMessages.PENDING_DO_NOT_REENTER
                } else {
                    result.message.ifBlank { com.example.util.IdempotencyStatusMessages.FAILED }
                }
                postMessage(errMsg, isError = true)
            }
            onComplete?.invoke(result.success)
        }
    }

    // --- Actions ---

    fun addMovement(
        cashbox: String,
        currency: String,
        direction: String,
        amount: Double,
        description: String,
        reference: String,
        user: String = resolveUserActor("المدير"),
        guardKey: String = "treasury_movement",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val autoRef = reference.trim().ifBlank { "REC-${System.currentTimeMillis() % 1000000}" }
        val opType = if (direction == "وارد") {
            com.example.util.IdempotencyOperationType.RECEIPT_VOUCHER
        } else {
            com.example.util.IdempotencyOperationType.PAYMENT_VOUCHER
        }
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = opType,
            actionLabel = "تسجيل حركة مالية",
            documentReference = autoRef,
            targetCollection = "movements",
            payloadSummary = "$direction $amount $currency في $cashbox",
            onComplete = onComplete
        ) { _ ->
            val autoDesc = description.trim().ifBlank { "حركة $direction بمبلغ $amount $currency في $cashbox" }
            val prePayload = mapOf<String, Any?>(
                "cashbox" to cashbox,
                "currency" to currency,
                "direction" to direction,
                "amount" to amount,
                "reference" to autoRef
            )
            val payloadCheck = CloudErpSyncRepository.validateTransactionCommitPayload("movements", autoRef, prePayload)
            if (!payloadCheck.valid) {
                cloudErpSyncRepo.recordTamperAttempt("ADD_MOVEMENT_VALIDATION", "movements/$autoRef", payloadCheck.reason, user)
                return@launchGuardedTransaction Result.failure(SecurityException(payloadCheck.reason))
            }
            val existingLocal = movements.value.map { m ->
                (m.cloudDocId.ifBlank { "MOV-${m.id}" }) to mapOf<String, Any?>(
                    "reference" to m.reference,
                    "cashbox" to m.cashbox,
                    "direction" to m.direction,
                    "currency" to m.currency,
                    "amount" to m.amount,
                    "syncUuid" to m.syncUuid
                )
            }
            val localConflict = CloudErpSyncRepository.checkExistingFinancialCommitConflict(
                collectionName = "movements",
                candidateDocId = autoRef,
                candidatePayload = prePayload,
                existingDocs = existingLocal
            )
            if (!localConflict.valid) {
                if (localConflict.violationType == CloudErpSyncRepository.Companion.TransactionViolationType.TAMPER) {
                    cloudErpSyncRepo.recordTamperAttempt("ADD_MOVEMENT_VALIDATION", "movements/$autoRef", localConflict.reason, user)
                    return@launchGuardedTransaction Result.failure(SecurityException(localConflict.reason))
                } else {
                    cloudErpSyncRepo.recordDuplicateAttempt("ADD_MOVEMENT_VALIDATION", "movements/$autoRef", localConflict.reason, user)
                    return@launchGuardedTransaction Result.failure(IllegalStateException(localConflict.reason))
                }
            }

            val res = repository.recordMovement(cashbox, currency, direction, amount, autoDesc, autoRef, user)
            res.map { movId ->
                DeletedRecordsRegistry.unregisterDeleted("movements", movId)
                DeletedRecordsRegistry.unregisterDeleted("movements", "MOV-$movId")
                DeletedRecordsRegistry.unregisterDeleted("movements", autoRef)
                postMessage("تم تسجيل الحركة بنجاح: $autoRef")
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
                        val syncRes = cloudErpSyncRepo.syncSingleMovementToCloud(serverClusterCode.value, m, user)
                        syncRes.onFailure { err ->
                            val msg = err.message.orEmpty()
                            if (msg.contains("مكررة") || msg.contains("تلاعب") || msg.contains("مسبقاً")) {
                                postMessage(msg, isError = true)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Direct movement sync deferred: ${e.message}")
                    }
                }
                "MOV-$movId" to movId
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
        isInvertedRate: Boolean = false,
        guardKey: String = "treasury_transfer",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val autoRef = reference.trim().ifBlank { "TRF-${System.currentTimeMillis() % 1000000}" }
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.STOCK_TRANSFER,
            actionLabel = "تحويل بين الخزن",
            documentReference = autoRef,
            targetCollection = "transfers",
            payloadSummary = "$sourceCashbox -> $destinationCashbox ($sourceAmount $sourceCurrency)",
            onComplete = onComplete
        ) { _ ->
            val destAmtPreview = if (isInvertedRate && rate > 0) sourceAmount / rate else sourceAmount * rate
            val prePayload = mapOf<String, Any?>(
                "reference" to autoRef,
                "sourceCashbox" to sourceCashbox,
                "destinationCashbox" to destinationCashbox,
                "sourceCurrency" to sourceCurrency,
                "destinationCurrency" to destinationCurrency,
                "sourceAmount" to sourceAmount,
                "rate" to rate,
                "destinationAmount" to destAmtPreview
            )
            val payloadCheck = CloudErpSyncRepository.validateTransactionCommitPayload("transfers", autoRef, prePayload)
            if (!payloadCheck.valid) {
                cloudErpSyncRepo.recordTamperAttempt("ADD_TRANSFER_VALIDATION", "transfers/$autoRef", payloadCheck.reason, user)
                return@launchGuardedTransaction Result.failure(SecurityException(payloadCheck.reason))
            }
            val existingLocalTransfers = transfers.value.map { tr ->
                "TRF-${tr.id}-${tr.reference}" to mapOf<String, Any?>(
                    "reference" to tr.reference,
                    "sourceCashbox" to tr.sourceCashbox,
                    "destinationCashbox" to tr.destinationCashbox,
                    "sourceAmount" to tr.sourceAmount,
                    "destinationAmount" to tr.destinationAmount
                )
            }
            val localConflict = CloudErpSyncRepository.checkExistingFinancialCommitConflict(
                collectionName = "transfers",
                candidateDocId = autoRef,
                candidatePayload = prePayload,
                existingDocs = existingLocalTransfers
            )
            if (!localConflict.valid) {
                if (localConflict.violationType == CloudErpSyncRepository.Companion.TransactionViolationType.TAMPER) {
                    cloudErpSyncRepo.recordTamperAttempt("ADD_TRANSFER_VALIDATION", "transfers/$autoRef", localConflict.reason, user)
                    return@launchGuardedTransaction Result.failure(SecurityException(localConflict.reason))
                } else {
                    cloudErpSyncRepo.recordDuplicateAttempt("ADD_TRANSFER_VALIDATION", "transfers/$autoRef", localConflict.reason, user)
                    return@launchGuardedTransaction Result.failure(IllegalStateException(localConflict.reason))
                }
            }

            val res = repository.recordTransfer(
                sourceCashbox, destinationCashbox, sourceCurrency, destinationCurrency,
                sourceAmount, rate, description, autoRef, user, halfaInvoiceId, halfaAmount,
                isInvertedRate
            )
            res.map { trfId ->
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
                        val syncRes = cloudErpSyncRepo.syncSingleTransferToCloud(serverClusterCode.value, t, user)
                        syncRes.onFailure { err ->
                            val msg = err.message.orEmpty()
                            if (msg.contains("مكررة") || msg.contains("تلاعب") || msg.contains("مسبقاً")) {
                                postMessage(msg, isError = true)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Direct transfer sync deferred: ${e.message}")
                    }
                }
                "TRF-$trfId" to trfId
            }
        }
    }

    fun deleteMovement(
        id: Long,
        guardKey: String = "delete_movement_$id",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DELETE,
            actionLabel = "حذف حركة مالية",
            documentReference = "MOV-$id",
            targetCollection = "movements",
            payloadSummary = "حذف الحركة المالية #$id",
            onComplete = onComplete
        ) { _ ->
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("عفواً، حذف الحركات المالية متاح لمدير النظام أو الحسابات المصرح لها فقط")
                )
            }
            val actor = resolveUserActor("المدير")
            val res = repository.deleteMovement(id, actor)
            res.map { deletedMov ->
                cloudErpSyncRepo.deleteMovementFromCloud(
                    clusterCode = serverClusterCode.value,
                    movementId = id,
                    reference = deletedMov?.reference.orEmpty(),
                    cloudDocId = deletedMov?.cloudDocId.orEmpty(),
                    syncUuid = deletedMov?.syncUuid.orEmpty(),
                    actor = actor
                )
                postMessage("تم حذف الحركة المالية نهائياً ومزامنة الحذف مع السيرفر بنجاح")
                persistAndSyncToServer()
                "MOV-$id" to Unit
            }
        }
    }

    fun recordCustomerPayment(
        customerId: Long,
        customerName: String,
        amount: Double,
        currency: String,
        cashbox: String,
        note: String,
        guardKey: String = "customer_payment_$customerId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.RECEIPT_VOUCHER,
            actionLabel = "سداد دفعة العميل",
            documentReference = "CUST-PAY-$customerId-${System.currentTimeMillis() / 1000L}",
            targetCollection = "movements",
            payloadSummary = "تحصيل $amount $currency من $customerName",
            onComplete = onComplete
        ) { _ ->
            val res = repository.recordCustomerPayment(
                customerId = customerId,
                customerName = customerName,
                amount = amount,
                currency = currency,
                cashbox = cashbox,
                note = note,
                user = resolveUserActor("محاسب المبيعات")
            )
            res.map {
                postMessage("تم تسجيل سداد وتحصيل مبلغ $amount $currency من العميل ($customerName) بنجاح")
                persistAndSyncToServer()
                "CUST-PAY-$customerId" to Unit
            }
        }
    }

    fun resetCashboxBalance(
        cashboxName: String,
        guardKey: String = "reset_cashbox_$cashboxName",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DELETE,
            actionLabel = "تصفير الخزنة",
            documentReference = "RESET-BOX-$cashboxName",
            targetCollection = "movements",
            payloadSummary = "تصفير خزنة $cashboxName",
            onComplete = onComplete
        ) { _ ->
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("عفواً، تصفير الخزن متاح لمدير النظام أو الحسابات المصرح لها فقط")
                )
            }
            val actor = resolveUserActor("المدير")
            val res = repository.resetCashboxBalance(cashboxName, actor)
            res.map { clearedMovements ->
                cloudErpSyncRepo.clearCashboxMovementsFromCloud(
                    clusterCode = serverClusterCode.value,
                    cashboxName = cashboxName,
                    knownClearedMovements = clearedMovements,
                    actor = actor
                )
                postMessage("تم تصفير رصيد وحركات خزنة ($cashboxName) ومنع عودتها بنجاح")
                persistAndSyncToServer()
                "RESET-BOX-$cashboxName" to Unit
            }
        }
    }

    fun performDailyClosing(
        cashbox: String,
        currency: String,
        bookBalance: Double,
        countedBalance: Double,
        notes: String,
        user: String = resolveUserActor("المدير"),
        guardKey: String = "daily_closing",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.SAVE,
            actionLabel = "إقفال اليومية",
            documentReference = "CLOSE-$cashbox-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}",
            targetCollection = "daily_closings",
            payloadSummary = "إقفال يومية $cashbox ($currency)",
            onComplete = onComplete
        ) { _ ->
            repository.recordDailyClosing(cashbox, currency, bookBalance, countedBalance, notes, user)
            postMessage("تم إقفال اليومية لخزنة $cashbox بنجاح")
            persistAndSyncToServer()
            Result.success("CLOSE-$cashbox" to Unit)
        }
    }

    fun addCustomer(
        name: String,
        phone: String,
        address: String,
        notes: String,
        user: String = resolveUserActor("المدير"),
        guardKey: String = "add_customer",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.CREATE,
            actionLabel = "إضافة عميل جديد",
            documentReference = "CUST-${name.trim()}",
            targetCollection = "customers",
            payloadSummary = "إضافة عميل $name",
            onComplete = onComplete
        ) { _ ->
            if (name.isBlank()) {
                return@launchGuardedTransaction Result.failure(IllegalArgumentException("يرجى إدخال اسم العميل"))
            }
            val custId = repository.addCustomer(name, phone, address, notes, user)
            postMessage("تمت إضافة العميل $name بنجاح")
            persistAndSyncToServer()
            Result.success("CUST-$custId" to custId)
        }
    }

    fun deleteCustomer(
        customerId: Long,
        customerName: String,
        guardKey: String = "delete_customer_$customerId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DELETE,
            actionLabel = "حذف عميل",
            documentReference = "CUST-$customerId",
            targetCollection = "customers",
            payloadSummary = "حذف عميل $customerName",
            onComplete = onComplete
        ) { _ ->
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("عفواً، حذف العملاء متاح لحساب مدير النظام فقط")
                )
            }
            val actor = resolveUserActor("المدير")
            val res = repository.deleteCustomer(customerId, customerName, actor)
            res.map {
                DeletedRecordsRegistry.registerDeletedWithAliases(
                    collection = "customers",
                    aliases = listOfNotNull(
                        customerId.toString(),
                        "CUST-$customerId",
                        customerName.trim().takeIf { it.isNotBlank() }
                    ),
                    primaryEntityId = customerId.toString(),
                    primaryDocId = "CUST-$customerId",
                    actor = actor
                )
                cloudErpSyncRepo.deleteEntityFromCloud(serverClusterCode.value, "customers", "CUST-$customerId")
                postMessage("تم حذف العميل ($customerName) وسجلاته نهائياً بنجاح")
                persistAndSyncToServer()
                "CUST-$customerId" to Unit
            }
        }
    }

    fun clearCustomerBalance(
        customerId: Long,
        customerName: String,
        guardKey: String = "clear_customer_$customerId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "تصفير حساب عميل",
            documentReference = "CUST-CLR-$customerId",
            targetCollection = "customers",
            payloadSummary = "تسوية رصيد العميل $customerName",
            onComplete = onComplete
        ) { _ ->
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("عفواً، تصفير وحذف حسابات العملاء متاح لمدير النظام فقط")
                )
            }
            val res = repository.clearCustomerBalance(customerId, customerName, resolveUserActor("المدير"))
            res.map {
                postMessage("تمت تسوية وإقفال المديونية المسجلة على العميل ($customerName) بقيد تسوية معتمد مع الحفاظ على سجل الدفاتر")
                persistAndSyncToServer()
                "CUST-CLR-$customerId" to Unit
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
        paymentAmount: Double? = null,
        guardKey: String = "create_invoice",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.APPROVE_INVOICE,
            actionLabel = "إصدار فاتورة مبيعات",
            documentReference = "INV-CUST-$customerId-${items.sumOf { it.lineTotal }.toLong()}",
            targetCollection = "invoices",
            payloadSummary = "فاتورة مبيعات للعميل $customerName (${items.size} صنف)",
            onComplete = onComplete
        ) { _ ->
            val res = repository.createInvoice(
                customerId, customerName, currency, items, discount, notes, user, paymentCashbox, paymentAmount
            )
            res.map { invId ->
                postMessage("تم إصدار الفاتورة بنجاح")
                persistAndSyncToServer()
                "INV-$invId" to invId
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
        user: String = resolveUserActor("المدير"),
        guardKey: String = "pay_invoice_$invoiceId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.PAYMENT,
            actionLabel = "سداد فاتورة مبيعات",
            documentReference = invoiceNo.ifBlank { "INV-$invoiceId" },
            targetCollection = "invoice_payments",
            payloadSummary = "سداد $amount $currency لفاتورة $invoiceNo",
            onComplete = onComplete
        ) { _ ->
            val res = repository.recordInvoicePayment(invoiceId, invoiceNo, customerName, currency, cashbox, amount, note, user)
            res.map {
                postMessage("تم تسجيل سداد مبلغ $amount $currency بنجاح")
                persistAndSyncToServer()
                "PAY-$invoiceNo" to Unit
            }
        }
    }

    fun cancelInvoice(
        invoiceId: Long,
        reason: String,
        user: String = resolveUserActor("المدير"),
        guardKey: String = "cancel_invoice_$invoiceId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "إلغاء فاتورة",
            documentReference = "INV-$invoiceId",
            targetCollection = "invoices",
            payloadSummary = "إلغاء فاتورة #$invoiceId",
            onComplete = onComplete
        ) { _ ->
            val res = repository.cancelInvoice(invoiceId, reason, user)
            res.map {
                postMessage("تم إلغاء الفاتورة وإرجاع الأصناف للمخزن")
                persistAndSyncToServer()
                "INV-$invoiceId" to Unit
            }
        }
    }

    fun deleteInvoicePermanently(
        invoiceId: Long,
        user: String = resolveUserActor("المدير"),
        guardKey: String = "delete_invoice_$invoiceId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val userObj = currentUser.value
        val allowed = userObj.isSystemManager() || userObj.hasModulePermission("صلاحية الحذف والتصفير")
        if (!allowed) {
            postMessage("لا تملك صلاحية الحذف النهائي للفواتير", isError = true)
            onComplete?.invoke(false)
            return
        }
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DELETE,
            actionLabel = "حذف فاتورة نهائياً",
            documentReference = "INV-$invoiceId",
            targetCollection = "invoices",
            payloadSummary = "حذف نهائي للفاتورة #$invoiceId",
            onComplete = onComplete
        ) { _ ->
            val res = repository.deleteInvoicePermanently(invoiceId, user)
            res.map { deletedInv ->
                cloudErpSyncRepo.deleteInvoiceFromCloud(
                    clusterCode = serverClusterCode.value,
                    invoiceId = deletedInv.id,
                    invoiceNo = deletedInv.invoiceNo,
                    actor = user
                )
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
                }
                postMessage("تم الحذف النهائي للفاتورة ${deletedInv.invoiceNo} (${deletedInv.customerName}) من السيرفر")
                persistAndSyncToServer()
                deletedInv.invoiceNo to deletedInv
            }
        }
    }

    fun addCashbox(
        name: String,
        user: String = resolveUserActor("المدير"),
        guardKey: String = "add_cashbox",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.CREATE,
            actionLabel = "إضافة خزنة",
            documentReference = "BOX-${name.trim()}",
            targetCollection = "cashboxes",
            payloadSummary = "إضافة خزنة ${name.trim()}",
            onComplete = onComplete
        ) { _ ->
            if (!currentUser.value.isSystemManager()) {
                return@launchGuardedTransaction Result.failure(IllegalStateException("عفواً، إضافة الخزن الجديدة متاحة لمدير النظام فقط"))
            }
            val cleanName = name.trim()
            if (cleanName.isBlank()) {
                return@launchGuardedTransaction Result.failure(IllegalArgumentException("يرجى إدخال اسم الخزنة"))
            }
            val newId = repository.addCashbox(cleanName, user)
            viewModelScope.launch(Dispatchers.IO) {
                cloudErpSyncRepo.syncSingleCashboxToCloud(serverClusterCode.value, newId, cleanName)
            }
            postMessage("تمت إضافة الخزنة ($cleanName) ومزامنتها بنجاح")
            persistAndSyncToServer()
            Result.success("BOX-$newId" to newId)
        }
    }

    fun updateCashbox(
        id: Long,
        name: String,
        user: String = resolveUserActor("المدير"),
        guardKey: String = "update_cashbox_$id",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val cleanName = name.trim()
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "تعديل اسم الخزنة",
            documentReference = "BOX-$id",
            targetCollection = "cashboxes",
            payloadSummary = "تعديل الخزنة #$id إلى $cleanName",
            onComplete = onComplete
        ) { _ ->
            if (!currentUser.value.isSystemManager()) {
                return@launchGuardedTransaction Result.failure(IllegalStateException("عفواً، تعديل الخزن متاح لمدير النظام فقط"))
            }
            val res = repository.updateCashbox(id, cleanName, user)
            res.map {
                viewModelScope.launch(Dispatchers.IO) {
                    cloudErpSyncRepo.syncSingleCashboxToCloud(serverClusterCode.value, id, cleanName)
                }
                postMessage("تم تعديل اسم الخزنة إلى ($cleanName) بنجاح")
                persistAndSyncToServer()
                "BOX-$id" to Unit
            }
        }
    }

    fun deleteCashbox(
        id: Long,
        name: String,
        user: String = resolveUserActor("المدير"),
        guardKey: String = "delete_cashbox_$id",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val cleanName = name.trim()
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DELETE,
            actionLabel = "حذف الخزنة",
            documentReference = "BOX-$id",
            targetCollection = "cashboxes",
            payloadSummary = "حذف/أرشفة خزنة $cleanName",
            onComplete = onComplete
        ) { _ ->
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("عفواً، حذف أو أرشفة الخزن متاح لمدير النظام أو الحسابات المصرح لها فقط")
                )
            }
            val res = repository.deleteCashbox(id, cleanName, user)
            res.map { hadFinancialHistory ->
                cloudErpSyncRepo.deleteCashboxFromCloud(serverClusterCode.value, id, cleanName)
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
                }
                val devId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(getApplication())
                cloudErpSyncRepo.syncAllLocalToCloud(
                    serverClusterCode.value,
                    repository.getDao(),
                    currentUser.value.isSystemManager(),
                    activeSessionUserId.value,
                    devId
                )
                val msg = if (hadFinancialHistory) {
                    "تم إلغاء تفعيل وأرشفة الخزنة ($cleanName) عبر جميع الأجهزة مع الاحتفاظ الكامل بكافة الحركات والسجلات المالية التاريخية المرتبطة بها"
                } else {
                    "تم حذف الخزنة ($cleanName) نهائياً ومزامنة الحذف عبر جميع الأجهزة"
                }
                postMessage(msg)
                "BOX-$id" to hadFinancialHistory
            }
        }
    }

    fun addWarehouse(
        name: String,
        currency: String = "SDG",
        linkedCashbox: String = "خزنة السودان",
        warehouseType: String = "مبيعات",
        user: String = resolveUserActor("المدير"),
        guardKey: String = "add_warehouse",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.CREATE,
            actionLabel = "إضافة مخزن",
            documentReference = "WH-${name.trim()}",
            targetCollection = "warehouses",
            payloadSummary = "إضافة مخزن $name",
            requiresOnline = false,
            onComplete = onComplete
        ) { _ ->
            val id = repository.addWarehouse(name, currency, linkedCashbox, warehouseType, user)
            postMessage("تمت إضافة المخزن $name بنجاح")
            persistAndSyncToServer()
            Result.success("WH-$id" to id)
        }
    }

    fun updateWarehouse(
        id: Long,
        name: String,
        active: Boolean,
        currency: String = "SDG",
        linkedCashbox: String = "خزنة السودان",
        warehouseType: String = "مبيعات",
        user: String = resolveUserActor("المدير"),
        guardKey: String = "update_warehouse_$id",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "تعديل بيانات المخزن",
            documentReference = "WH-$id",
            targetCollection = "warehouses",
            payloadSummary = "تعديل مخزن $name",
            requiresOnline = false,
            onComplete = onComplete
        ) { _ ->
            val res = repository.updateWarehouse(id, name, active, currency, linkedCashbox, warehouseType, user)
            res.map {
                postMessage("تم تعديل بيانات المخزن $name بنجاح")
                persistAndSyncToServer()
                "WH-$id" to Unit
            }
        }
    }

    fun deleteWarehouse(
        id: Long,
        name: String,
        user: String = resolveUserActor("المدير"),
        guardKey: String = "delete_warehouse_$id",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DELETE,
            actionLabel = "حذف مخزن",
            documentReference = "WH-$id",
            targetCollection = "warehouses",
            payloadSummary = "حذف مخزن $name",
            requiresOnline = false,
            onComplete = onComplete
        ) { _ ->
            val res = repository.deleteWarehouse(id, user)
            res.map {
                DeletedRecordsRegistry.registerDeletedWithAliases(
                    collection = "warehouses",
                    aliases = listOfNotNull(id.toString(), "WH-$id", name.trim().takeIf { it.isNotBlank() }),
                    primaryEntityId = name.trim().ifBlank { id.toString() },
                    primaryDocId = "WH-$id",
                    actor = user
                )
                cloudErpSyncRepo.deleteWarehouseFromCloud(serverClusterCode.value, id, name)
                postMessage("تم حذف المخزن $name نهائياً بنجاح من النظام والسحابة")
                persistAndSyncToServer()
                "WH-$id" to Unit
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
        user: String = resolveUserActor("المدير"),
        guardKey: String = "add_product",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.CREATE,
            actionLabel = "إضافة صنف",
            documentReference = "PROD-${name.trim()}",
            targetCollection = "products",
            payloadSummary = "إضافة الصنف $name",
            onComplete = onComplete
        ) { _ ->
            val prodId = repository.addProduct(name, color, weight, purchasePrice, salePrice, salePriceEgp, minimumStock, user)
            DeletedRecordsRegistry.unregisterDeleted("products", prodId)
            DeletedRecordsRegistry.unregisterDeleted("products", "PROD-$prodId")
            postMessage("تمت إضافة الصنف $name بنجاح")
            persistAndSyncToServer()
            Result.success("PROD-$prodId" to prodId)
        }
    }

    fun addProductWithIntake(
        color: String,
        weight: String,
        cartons: Double,
        warehouseName: String,
        purchasePrice: Double,
        salePrice: Double,
        note: String,
        guardKey: String = "product_intake",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val prodName = "بوهية $color $weight جم"
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.SUPPLY_RAW,
            actionLabel = "توريد أصناف للمخزن",
            documentReference = "INTAKE-$color-$weight-${cartons.toInt()}-$warehouseName",
            targetCollection = "stock_moves",
            payloadSummary = "توريد $cartons كرتونة من $prodName إلى $warehouseName",
            onComplete = onComplete
        ) { _ ->
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
            val smId = repository.recordStockMove(
                warehouseId = whId,
                productId = productId,
                quantity = cartons,
                kind = "IN",
                note = if (note.isBlank()) "توريد مخزني جديد: $cartons كرتونة إلى $warehouseName" else "$note ($warehouseName)",
                user = resolveUserActor("أمين المخزن")
            )
            postMessage("تم توريد $cartons كرتونة من ($prodName) بنجاح إلى $warehouseName")
            persistAndSyncToServer()
            Result.success("SM-$smId" to smId)
        }
    }

    fun addStockMove(
        warehouseId: Long,
        productId: Long,
        quantity: Double,
        kind: String,
        note: String,
        user: String = resolveUserActor("المدير"),
        guardKey: String = "stock_move_${warehouseId}_$productId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DISPATCH_GOODS,
            actionLabel = "حركة مخزون",
            documentReference = "SM-$warehouseId-$productId-$kind-${quantity.toInt()}",
            targetCollection = "stock_moves",
            payloadSummary = "حركة مخزن ($kind) بكمية $quantity",
            onComplete = onComplete
        ) { _ ->
            val id = repository.recordStockMove(warehouseId, productId, quantity, kind, note, user)
            postMessage("تم تسجيل حركة المخزون بنجاح ومزامنتها")
            persistAndSyncToServer()
            Result.success("SM-$id" to id)
        }
    }

    fun selectInventoryWeight(weightValue: String) {
        weightManager.selectWeight(weightValue)
    }

    fun addInventoryWeight(rawWeight: String, setAsDefault: Boolean = false) {
        val result = weightManager.addWeight(rawInput = rawWeight, setAsDefault = setAsDefault)
        result.onSuccess { spec ->
            weightManager.selectWeight(spec.value)
            postMessage("تمت إضافة الوزن (${spec.displayLabel()}) بنجاح إلى قائمة الأوزان")
            val actor = resolveUserActor("المدير")
            viewModelScope.launch(Dispatchers.IO) {
                cloudErpSyncRepo.syncInventoryWeightsToCloud(serverClusterCode.value, actor)
            }
        }.onFailure { err ->
            postMessage(err.message ?: "تعذر إضافة الوزن", isError = true)
        }
    }

    fun editInventoryWeight(oldWeight: String, newRawWeight: String) {
        if (!checkOnlineBeforeTransaction("تعديل وزن مسجل")) return
        val result = weightManager.updateWeightValue(oldWeight, newRawWeight)
        result.onSuccess { updatedSpec ->
            viewModelScope.launch {
                val actor = resolveUserActor("المدير")
                val repoRes = repository.renamePackageWeightSafely(
                    oldWeight = oldWeight,
                    newWeight = updatedSpec.value,
                    user = actor
                )
                repoRes.onSuccess { count ->
                    postMessage("تم تعديل الوزن إلى (${updatedSpec.displayLabel()}) وتحديث $count صنف مع الحفاظ على الأرصدة")
                    viewModelScope.launch(Dispatchers.IO) {
                        cloudErpSyncRepo.syncInventoryWeightsToCloud(serverClusterCode.value, actor)
                    }
                    persistAndSyncToServer()
                }.onFailure { err ->
                    postMessage(err.message ?: "تعذر تحديث الأصناف المرتبطة بالوزن", isError = true)
                }
            }
        }.onFailure { err ->
            postMessage(err.message ?: "تعذر تعديل الوزن", isError = true)
        }
    }

    fun toggleInventoryWeightActive(weightValue: String, active: Boolean) {
        val result = weightManager.setWeightActive(weightValue, active)
        result.onSuccess { spec ->
            val statusLabel = if (spec.active) "تفعيل" else "تعطيل"
            postMessage("تم $statusLabel الوزن (${spec.displayLabel()}) مع الاحتفاظ بجميع الأرصدة السابقة")
            val actor = resolveUserActor("المدير")
            viewModelScope.launch(Dispatchers.IO) {
                cloudErpSyncRepo.syncInventoryWeightsToCloud(serverClusterCode.value, actor)
            }
        }.onFailure { err ->
            postMessage(err.message ?: "تعذر تغيير حالة الوزن", isError = true)
        }
    }

    fun setDefaultInventoryWeight(weightValue: String) {
        val result = weightManager.setDefaultWeight(weightValue)
        result.onSuccess { spec ->
            postMessage("تم تعيين (${spec.displayLabel()}) كوزن افتراضي للنظام")
            val actor = resolveUserActor("المدير")
            viewModelScope.launch(Dispatchers.IO) {
                cloudErpSyncRepo.syncInventoryWeightsToCloud(serverClusterCode.value, actor)
            }
        }.onFailure { err ->
            postMessage(err.message ?: "تعذر تعيين الوزن الافتراضي", isError = true)
        }
    }

    fun adjustColorWeightStock(
        color: String,
        weight: String,
        warehouseName: String,
        quantityInput: Double,
        operationMode: String,
        note: String,
        guardKey: String = "adjust_stock",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (!quantityInput.isFinite()) {
            postMessage("القيمة المدخلة غير صالحة", isError = true)
            onComplete?.invoke(false)
            return
        }
        val cleanColor = color.trim().ifBlank { "أبيض" }
        val cleanWeight = com.example.util.InventoryWeightManager.normalizeWeightValue(weight).ifBlank { weight.trim() }
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "تحديث كمية المخزون",
            documentReference = "ADJ-$cleanColor-$cleanWeight-$operationMode-${quantityInput.toInt()}",
            targetCollection = "stock_moves",
            payloadSummary = "تعديل مخزون ($cleanColor - $cleanWeight جم) نمط $operationMode في $warehouseName",
            onComplete = onComplete
        ) { _ ->
            val actor = resolveUserActor("أمين المخزن")

            val product = repository.ensureProductForColorAndWeight(
                color = cleanColor,
                weight = cleanWeight,
                user = actor
            )

            val allWarehouses = repository.getDao().getWarehousesDirect()
            val targetWh = allWarehouses.firstOrNull {
                it.name.trim().equals(warehouseName.trim(), ignoreCase = true)
            } ?: allWarehouses.firstOrNull()
            val whId = targetWh?.id ?: 1L
            val resolvedWhName = targetWh?.name ?: warehouseName

            when (operationMode.uppercase()) {
                "IN" -> {
                    if (quantityInput <= 0.0) {
                        return@launchGuardedTransaction Result.failure(IllegalArgumentException("يرجى إدخال كمية أكبر من صفر للإضافة"))
                    }
                    repository.recordStockMove(
                        warehouseId = whId,
                        productId = product.id,
                        quantity = quantityInput,
                        kind = "IN",
                        note = note.ifBlank { "إضافة مخزنية ($cleanColor - $cleanWeight جم) في $resolvedWhName" },
                        user = actor
                    )
                    postMessage("تمت إضافة ${quantityInput.toInt()} إلى رصيد ($cleanColor - $cleanWeight جم) بنجاح")
                }
                "OUT" -> {
                    if (quantityInput <= 0.0) {
                        return@launchGuardedTransaction Result.failure(IllegalArgumentException("يرجى إدخال كمية أكبر من صفر للخصم"))
                    }
                    repository.recordStockMove(
                        warehouseId = whId,
                        productId = product.id,
                        quantity = quantityInput,
                        kind = "OUT",
                        note = note.ifBlank { "صرف مخزني ($cleanColor - $cleanWeight جم) من $resolvedWhName" },
                        user = actor
                    )
                    postMessage("تم خصم ${quantityInput.toInt()} من رصيد ($cleanColor - $cleanWeight جم) بنجاح")
                }
                "SET" -> {
                    if (quantityInput < 0.0) {
                        return@launchGuardedTransaction Result.failure(IllegalArgumentException("لا يمكن أن يكون الرصيد بالسالب"))
                    }
                    val currentMoves = repository.getDao().getStockMovesDirect().filter {
                        it.productId == product.id && it.warehouseId == whId
                    }
                    val currentBalance = currentMoves.sumOf { sm ->
                        val sign = if (sm.kind in listOf("IN", "RETURN", "RETURN_IN", "CANCEL", "وارد", "مرتجع", "توريد", "إضافة")) 1.0 else -1.0
                        sign * kotlin.math.abs(sm.quantity)
                    }.coerceAtLeast(0.0)
                    val delta = quantityInput - currentBalance
                    if (kotlin.math.abs(delta) < 0.0001) {
                        postMessage("الرصيد الفعلي مطابق للرصيد الحالي (${quantityInput.toInt()})")
                        return@launchGuardedTransaction Result.success("ADJ-NOOP" to Unit)
                    }
                    val moveKind = if (delta > 0.0) "IN" else "ADJUST"
                    val moveQty = kotlin.math.abs(delta)
                    repository.recordStockMove(
                        warehouseId = whId,
                        productId = product.id,
                        quantity = moveQty,
                        kind = moveKind,
                        note = note.ifBlank {
                            "تعديل رصيد ($cleanColor - $cleanWeight جم) من ${currentBalance.toInt()} إلى ${quantityInput.toInt()} ($resolvedWhName)"
                        },
                        user = actor
                    )
                    postMessage("تم تحديث رصيد ($cleanColor - $cleanWeight جم) في $resolvedWhName إلى ${quantityInput.toInt()} بنجاح")
                }
            }
            persistAndSyncToServer()
            Result.success("ADJ-${product.id}" to Unit)
        }
    }

    fun deleteProduct(
        productId: Long,
        productName: String,
        guardKey: String = "delete_product_$productId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DELETE,
            actionLabel = "حذف صنف",
            documentReference = "PROD-$productId",
            targetCollection = "products",
            payloadSummary = "حذف صنف $productName",
            onComplete = onComplete
        ) { _ ->
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("عفواً، حذف الأصناف متاح لمدير النظام أو الحسابات المصرح لها فقط")
                )
            }
            val actor = resolveUserActor("المدير")
            val res = repository.deleteProduct(productId, productName, actor)
            res.map {
                DeletedRecordsRegistry.registerDeletedWithAliases(
                    collection = "products",
                    aliases = listOfNotNull(productId.toString(), "PROD-$productId", "PRD-$productId"),
                    primaryEntityId = productId.toString(),
                    primaryDocId = "PROD-$productId",
                    actor = actor
                )
                cloudErpSyncRepo.deleteEntityFromCloud(serverClusterCode.value, "products", "PROD-$productId")
                cloudErpSyncRepo.deleteStockMovesByProductFromCloud(serverClusterCode.value, productId)
                postMessage("تم حذف الصنف ($productName) وحركاته بنجاح من النظام والسحابة")
                persistAndSyncToServer()
                "PROD-$productId" to Unit
            }
        }
    }

    fun resetWarehouseStock(
        warehouseId: Long,
        warehouseName: String,
        guardKey: String = "reset_warehouse_$warehouseId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DELETE,
            actionLabel = "تصفير رصيد المخزن",
            documentReference = "RESET-WH-$warehouseId",
            targetCollection = "stock_moves",
            payloadSummary = "تصفير مخزن $warehouseName",
            onComplete = onComplete
        ) { _ ->
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("عفواً، تصفير رصيد المخزن متاح لمدير النظام أو المصرح لهم فقط")
                )
            }
            val actor = resolveUserActor("المدير")
            val res = repository.resetWarehouseStock(warehouseId, warehouseName, actor)
            res.map {
                cloudErpSyncRepo.deleteStockMovesByWarehouseFromCloud(serverClusterCode.value, warehouseId)
                postMessage("تم تصفير كامل رصيد مخزن ($warehouseName) وحذف حركاته من السحابة")
                persistAndSyncToServer()
                "RESET-WH-$warehouseId" to Unit
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
        counterparty: String = "",
        guardKey: String = "factory_move_$kind",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val opType = when (kind.uppercase()) {
            "RAW_PURCHASE", "RAW_RECEIPT" -> com.example.util.IdempotencyOperationType.SUPPLY_RAW
            "ISSUE_PRODUCTION", "DISPATCH_CUSTOMER" -> com.example.util.IdempotencyOperationType.DISPATCH_GOODS
            else -> com.example.util.IdempotencyOperationType.CREATE
        }
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = opType,
            actionLabel = "تسجيل حركة المصنع أو التشغيلة",
            documentReference = "FM-$kind-$itemId-${qty.toLong()}-$batch",
            targetCollection = "factory_moves",
            payloadSummary = "$kind • $itemName ($qty)",
            onComplete = onComplete
        ) { _ ->
            val fmId = repository.recordFactoryMove(kind, itemId, itemName, qty, machineId, machineName, batch, note, actor, counterparty)
            persistAndSyncToServer()
            postMessage("تم تسجيل ومزامنة حركة المصنع والتشغيلة بنجاح")
            Result.success("FM-$fmId" to fmId)
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
        actor: String = resolveUserActor("مسؤول التحميل"),
        guardKey: String = "dispatch_halfa",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.STOCK_TRANSFER,
            actionLabel = "ترحيل شحنة حلفا",
            documentReference = "HALFA-$productId-${cartons.toInt()}",
            targetCollection = "halfa_invoices",
            payloadSummary = "ترحيل $cartons كرتونة $productName إلى حلفا",
            onComplete = onComplete
        ) { _ ->
            val res = repository.dispatchHalfa(itemId, itemName, qty, productId, productName, cartons, unitPrice, note, actor)
            postMessage("تم ترحيل البضاعة إلى حلفا وإنشاء فاتورة ${res.second}")
            persistAndSyncToServer()
            Result.success(res.second to res)
        }
    }

    fun updatePosterPrice(
        weight: String,
        kind: String,
        price: Long,
        currency: String = "SDG",
        guardKey: String = "poster_price_${weight}_${kind}_$currency",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "تعديل قائمة الأسعار",
            documentReference = "POSTER-${weight}_${kind}_$currency",
            targetCollection = "poster_prices",
            payloadSummary = "تحديث سعر $kind ($weight جم) = $price $currency",
            onComplete = onComplete
        ) { _ ->
            repository.updatePosterPrice(weight, kind, price, currency)
            postMessage("تم تحديث سعر $kind ($weight جم) بالعملة $currency ومزامنته مع السيرفر السحابي")
            persistAndSyncToServer()
            Result.success("POSTER-${weight}_${kind}_$currency" to Unit)
        }
    }

    // --- User Accounts & Server Sync Actions (Strictly Admin-Only per Section 16) ---
    fun createUserAccount(
        fullName: String,
        username: String,
        phoneOrEmail: String,
        pinOrPassword: String,
        role: String,
        branch: String,
        permissions: String,
        serverEndpoint: String = "https://api.pmp-paints-erp.com/v1",
        guardKey: String = "create_user_account",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.CREATE,
            actionLabel = "إنشاء حساب موظف جديد",
            documentReference = "USR-${username.trim().lowercase()}",
            targetCollection = "user_accounts",
            payloadSummary = "إنشاء حساب $fullName ($username)",
            onComplete = onComplete
        ) { _ ->
            if (!com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(currentUser.value)) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("عفواً، حساب Admin هو الحساب الوحيد الذي يملك صلاحية إنشاء حسابات الموظفين وتحديد صلاحياتهم.")
                )
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
                actor = resolveUserActor("Admin")
            )
            res.map { createdUser ->
                postMessage("تم إنشاء حساب المستخدم ($fullName) ورفعه إلى سيرفر Google Cloud للمزامنة الفورية")
                persistAndSyncToServer()
                syncAllAccountsToGoogleCloud(clusterCode = serverClusterCode.value, silent = true)
                "USR-$createdUser" to createdUser
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

    fun toggleUserAccountActive(
        user: UserAccountEntity,
        guardKey: String = "toggle_user_${user.id}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "تعديل حالة تفعيل الحساب",
            documentReference = "USR-TOGGLE-${user.id}",
            targetCollection = "user_accounts",
            payloadSummary = "تبديل حالة حساب ${user.fullName}",
            onComplete = onComplete
        ) { _ ->
            if (!com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(currentUser.value)) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("عفواً، تفعيل أو إيقاف حسابات المستخدمين متاح لحساب Admin فقط.")
                )
            }
            val res = repository.toggleUserAccountActive(user, resolveUserActor("Admin"))
            res.map {
                val updatedUser = user.copy(active = !user.active)
                val stateLabel = if (updatedUser.active) "تفعيل" else "إيقاف"
                postMessage("تم $stateLabel حساب ${user.fullName} ومزامنته على سيرفر Google Cloud")
                cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, updatedUser)
                if (Firebase.auth.currentUser != null) {
                    cloudSyncRepo.syncLocalAccountToCloud(updatedUser)
                }
                persistAndSyncToServer()
                "USR-TOGGLE-${user.id}" to Unit
            }
        }
    }

    fun deleteUserAccountPermanently(
        user: UserAccountEntity,
        guardKey: String = "delete_user_${user.id}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DELETE,
            actionLabel = "حذف حساب الموظف نهائياً",
            documentReference = "USR-DEL-${user.id}",
            targetCollection = "user_accounts",
            payloadSummary = "حذف حساب ${user.fullName}",
            onComplete = onComplete
        ) { _ ->
            if (!com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(currentUser.value)) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("عفواً، الحذف النهائي لحسابات المستخدمين متاح لحساب Admin فقط.")
                )
            }
            val actor = resolveUserActor("Admin")
            DeletedAccountsRegistry.registerDeleted(user.userCode, user.username, user.phoneOrEmail, actor)
            val res = repository.deleteUserAccountPermanently(
                userId = user.id,
                userCode = user.userCode,
                username = user.username,
                email = user.phoneOrEmail,
                user = actor
            )
            res.map {
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
                "USR-DEL-${user.id}" to Unit
            }
        }
    }

    fun restoreUserAccount(
        user: UserAccountEntity,
        guardKey: String = "restore_user_${user.id}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "استرداد حساب الموظف",
            documentReference = "USR-RESTORE-${user.id}",
            targetCollection = "user_accounts",
            payloadSummary = "استرداد حساب ${user.fullName}",
            onComplete = onComplete
        ) { _ ->
            if (!com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(currentUser.value)) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("عفواً، استرداد الحسابات متاح لحساب Admin فقط.")
                )
            }
            val res = repository.restoreUserAccount(user.id, resolveUserActor("Admin"))
            res.map { restored ->
                cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, restored)
                DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
                postMessage("تم استرداد وتنشيط حساب (${restored.fullName}) بنجاح ومزامنته")
                "USR-RESTORE-${user.id}" to restored
            }
        }
    }

    fun refreshPermissionChangeHistory() {
        _permissionChangeHistory.value = com.example.util.EnterpriseRbacManager.getPermissionChangeHistory(
            context = getApplication()
        )
    }

    fun validateBackendAction(
        module: String,
        action: com.example.util.ErpActionPermission,
        operationLabel: String = action.arabicLabel
    ): Boolean {
        val decision = com.example.util.EnterpriseRbacManager.validateBackendRequest(
            user = currentUser.value,
            module = module,
            action = action,
            context = getApplication()
        )
        if (!decision.allowed) {
            postMessage("تم رفض العملية ($operationLabel): ${decision.message}", isError = true)
            return false
        }
        return true
    }

    fun updateEmployeePermissionsAndRole(
        account: UserAccountEntity,
        newRole: String,
        newBranch: String,
        newPermissions: String,
        newLinkedUid: String = account.remoteServerId,
        approveAccount: Boolean = true,
        changeReason: String = "تحديث صلاحيات الموظف من لوحة الصلاحيات",
        guardKey: String = "update_user_perms_${account.id}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "تحديث صلاحيات الموظف",
            documentReference = "USR-PERM-${account.id}",
            targetCollection = "user_accounts",
            payloadSummary = "تحديث صلاحيات ${account.fullName} ($newRole)",
            requiresOnline = false,
            requiresServerClaim = isConnectedToCloud(),
            onComplete = onComplete
        ) { _ ->
            val actorUser = currentUser.value
            if (!com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(actorUser)) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("عفواً، حساب Admin أو المسؤول المفوض بإدارة المستخدمين هو الوحيد المخول بتحديد أو تعديل صلاحيات المستخدمين.")
                )
            }
            val configRes = com.example.util.EnterpriseRbacManager.configureEmployeePermissions(
                actor = actorUser,
                targetUser = account,
                newRole = newRole,
                newPermissions = newPermissions,
                reason = changeReason,
                context = getApplication()
            )
            if (configRes.isFailure) {
                return@launchGuardedTransaction Result.failure(
                    configRes.exceptionOrNull() ?: IllegalStateException("تعذر التحقق من صلاحية تعديل الحساب")
                )
            }
            val wasPending = account.syncStatus == "PENDING_MANAGER_APPROVAL"
            val res = repository.updateEmployeePermissionsAndRole(
                account = account,
                newRole = newRole,
                newBranch = newBranch,
                newPermissions = newPermissions,
                newLinkedUid = newLinkedUid,
                approveAccount = approveAccount,
                actor = resolveUserActor("Admin"),
                actorAccount = actorUser,
                reason = changeReason
            )
            res.map { updated ->
                refreshPermissionChangeHistory()
                val actionMsg = if (wasPending && approveAccount) {
                    "تمت الموافقة على إنشاء حساب (${updated.fullName}) واعتماد صلاحياته للدخول"
                } else {
                    "تم تحديث صلاحيات ${updated.fullName} بنجاح وتسجيلها في سجل تدقيق الصلاحيات"
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
                "USR-PERM-${account.id}" to updated
            }
        }
    }

    /**
     * منح صلاحية مؤقتة اختيارية للموظف مع تاريخ انتهاء تلقائي
     */
    fun grantTemporaryPermissionToEmployee(
        account: UserAccountEntity,
        permissionToken: String,
        durationHours: Int,
        reason: String = "تفويض مؤقت للعمل",
        guardKey: String = "grant_temp_perm_${account.id}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "منح صلاحية مؤقتة",
            documentReference = "USR-TEMP-${account.id}-$permissionToken",
            targetCollection = "user_accounts",
            payloadSummary = "منح $permissionToken للموظف ${account.fullName}",
            requiresOnline = false,
            requiresServerClaim = isConnectedToCloud(),
            onComplete = onComplete
        ) { _ ->
            val actorUser = currentUser.value
            val expiresAt = System.currentTimeMillis() + (durationHours.coerceAtLeast(1) * 3600_000L)
            val grantResult = com.example.util.EnterpriseRbacManager.grantTemporaryPermission(
                actor = actorUser,
                targetUser = account,
                permissionToken = permissionToken,
                expiresAtMillis = expiresAt,
                reason = reason,
                context = getApplication()
            )
            grantResult.map { updatedAccount ->
                repository.updateEmployeePermissionsAndRole(
                    account = account,
                    newRole = updatedAccount.role,
                    newBranch = updatedAccount.branch,
                    newPermissions = updatedAccount.permissions,
                    newLinkedUid = updatedAccount.remoteServerId,
                    approveAccount = false,
                    actor = resolveUserActor("Admin"),
                    actorAccount = actorUser,
                    reason = "منح صلاحية مؤقتة ($permissionToken) لمدة $durationHours ساعة: $reason"
                )
                refreshPermissionChangeHistory()
                cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, updatedAccount)
                postMessage("تم منح صلاحية مؤقتة ($permissionToken) للموظف ${account.fullName} لمدة $durationHours ساعة")
                "USR-TEMP-${account.id}-$permissionToken" to updatedAccount
            }
        }
    }

    /**
     * Direct one-click manager approval for employee account creation requests
     */
    fun approveUserAccount(
        account: UserAccountEntity,
        newRole: String = "محاسب ومبيعات",
        newPermissions: String = "المبيعات,الأسعار,المخازن",
        guardKey: String = "approve_user_${account.id}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val targetRole = if (account.role.contains("بانتظار موافقة") || account.role.isBlank()) newRole else account.role
        val targetPerms = if (account.permissions.isBlank()) newPermissions else account.permissions
        updateEmployeePermissionsAndRole(
            account = account,
            newRole = targetRole,
            newBranch = account.branch.ifBlank { "فرع السودان (SD-001)" },
            newPermissions = targetPerms,
            newLinkedUid = account.remoteServerId,
            approveAccount = true,
            changeReason = "اعتماد حساب جديد وتفعيل صلاحياته الافتراضية",
            guardKey = guardKey,
            onComplete = onComplete
        )
    }

    // --- User Profile Management ---
    fun updateUserProfile(
        fullName: String,
        phoneOrEmail: String,
        newPassword: String?,
        guardKey: String = "update_user_profile",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "تحديث الملف الشخصي",
            documentReference = "USR-PROFILE-${currentUser.value?.id ?: 0L}",
            targetCollection = "user_accounts",
            payloadSummary = "تحديث ملف $fullName",
            requiresOnline = false,
            requiresServerClaim = isConnectedToCloud(),
            onComplete = onComplete
        ) { _ ->
            val app = getApplication<Application>()
            val user = currentUser.value ?: return@launchGuardedTransaction Result.failure(
                IllegalStateException("يجب تسجيل الدخول أولاً لتعديل الملف الشخصي")
            )
            val res = repository.updateUserProfile(user.id, fullName, phoneOrEmail, newPassword)
            res.map { updated ->
                activeSessionOverride.value = updated
                DeviceSessionStore.saveActiveUserSession(app, updated)
                if (com.example.util.BiometricPreferenceManager.isBoundToUser(app, updated)) {
                    com.example.util.BiometricPreferenceManager.bindUser(app, updated)
                }
                postMessage("تم تحديث بيانات الملف الشخصي بنجاح")
                cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, updated)
                persistAndSyncToServer()
                "USR-PROFILE-${updated.id}" to updated
            }
        }
    }

    // --- In-App Update Management ---
    fun refreshBuildIdentityReport() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val report = updateManager.getInstalledBuildIdentityReport(
                    updateSourceUrl = customUpdateMetadataUrl.value,
                    latestRemoteUpdate = _availableUpdate.value,
                    computeHeavyApkHash = true
                )
                _buildIdentityReport.value = report
            }
        }
    }

    /**
     * توليد محتوى version.json الافتراضي الجاهز للتعديل أو الرفع المباشر من واجهة المدير
     */
    fun generateDefaultVersionJsonForAdmin(): String {
        val currentOrNext = _availableUpdate.value ?: run {
            val nextCode = updateManager.getEffectiveVersionCode() + 1
            val nextName = "v${nextCode}.0"
            val cleanNextName = nextName.removePrefix("v")
            UpdateManager.LATEST_CLOUD_RELEASE_INFO.copy(
                versionCode = nextCode,
                versionName = nextName,
                downloadUrl = "https://raw.githubusercontent.com/ow7ab/ow7ab/main/public/pmp-paints-latest.apk",
                buildId = "PMP-v${cleanNextName}-b${nextCode}-${BuildConfig.GIT_COMMIT_SHA}",
                gitCommit = BuildConfig.GIT_COMMIT_SHA,
                releaseNotes = "إصدار ${nextName} (Build ${nextCode}): تحديث سحابي جديد لتطبيق PMP PAINTS مع الحفاظ الكامل على البيانات."
            )
        }
        return UpdateManager.generateVersionJsonString(currentOrNext)
    }

    /**
     * رفع محتوى ملف version.json مباشرة إلى Firebase Storage ومزامنته مع Firestore لتحديث صفحة التنزيل تلقائياً
     */
    fun uploadVersionJsonToFirebaseStorage(
        rawJson: String,
        onSuccess: (UpdateInfo, String) -> Unit = { _, _ -> }
    ) {
        val user = currentUser.value
        val isAdmin = user.isSuperAdminAccount() || user?.username?.equals("admin", ignoreCase = true) == true
        if (!isAdmin) {
            postMessage("عفواً، صلاحية رفع ملف version.json إلى Firebase Storage مخصصة لحساب المدير العام (admin) فقط", isError = true)
            return
        }
        if (_isUploadingVersionJson.value) {
            postMessage("جاري رفع ملف version.json بالفعل، يرجى الانتظار...")
            return
        }
        viewModelScope.launch {
            _isUploadingVersionJson.value = true
            _isUploadingUpdateToFirebase.value = true
            _firebaseUploadProgress.value = 5
            _firebaseUploadStageText.value = "جاري بدء رفع ملف version.json إلى Firebase Storage..."
            _versionJsonUploadStatus.value = "جاري رفع ملف version.json إلى Firebase Storage ومزامنة صفحة التنزيل..."
            updateManager.setAdminBroadcastGuardEnabled(true)

            val res = updateManager.uploadVersionJsonToFirebaseStorage(
                rawVersionJson = rawJson,
                clusterCode = serverClusterCode.value,
                onProgress = { pct, stage ->
                    _firebaseUploadProgress.value = pct
                    _firebaseUploadStageText.value = stage
                    _versionJsonUploadStatus.value = stage
                }
            )
            _isUploadingVersionJson.value = false
            _isUploadingUpdateToFirebase.value = false

            res.onSuccess { (verifiedInfo, storageUrl) ->
                _firebaseUploadProgress.value = 100
                _lastUploadedVersionJsonUrl.value = storageUrl
                if (verifiedInfo.versionCode > updateManager.getEffectiveVersionCode()) {
                    _availableUpdate.value = verifiedInfo
                    _updateAvailable.value = true
                }
                val successMsg =
                    "تم رفع version.json إلى Firebase Storage بنجاح (100%)! تم تحديث صفحة التنزيل تلقائياً إلى v${verifiedInfo.versionName} (Build ${verifiedInfo.versionCode})"
                _firebaseUploadStageText.value = successMsg
                _versionJsonUploadStatus.value = successMsg
                _lastUpdateCheckStatus.value = successMsg
                postMessage(successMsg, isError = false)
                refreshBuildIdentityReport()
                onSuccess(verifiedInfo, storageUrl)
            }.onFailure { err ->
                _firebaseUploadProgress.value = 0
                val failMsg = err.message ?: "تعذر رفع ملف version.json إلى Firebase Storage"
                _firebaseUploadStageText.value = failMsg
                _versionJsonUploadStatus.value = failMsg
                postMessage(failMsg, isError = true)
            }
        }
    }

    /**
     * قراءة ملف version.json المختار من ذاكرة الهاتف ورفعه مباشرة إلى Firebase Storage
     */
    fun uploadVersionJsonFromUri(
        context: android.content.Context,
        uri: android.net.Uri,
        onLoadedJson: (String) -> Unit = {}
    ) {
        val user = currentUser.value
        val isAdmin = user.isSuperAdminAccount() || user?.username?.equals("admin", ignoreCase = true) == true
        if (!isAdmin) {
            postMessage("عفواً، صلاحية رفع ملف version.json مخصصة لحساب المدير العام (admin) فقط", isError = true)
            return
        }
        viewModelScope.launch {
            _isUploadingUpdateToFirebase.value = true
            _firebaseUploadProgress.value = 2
            _firebaseUploadStageText.value = "جاري قراءة ملف version.json من ذاكرة الجهاز..."
            val readResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw Exception("تعذر فتح الملف المختار للقراءة")
                }
            }
            readResult.onSuccess { rawJson ->
                onLoadedJson(rawJson)
                uploadVersionJsonToFirebaseStorage(rawJson)
            }.onFailure { err ->
                _isUploadingUpdateToFirebase.value = false
                _firebaseUploadProgress.value = 0
                val msg = "تعذر قراءة ملف version.json المختار: ${err.localizedMessage}"
                _firebaseUploadStageText.value = msg
                _versionJsonUploadStatus.value = msg
                postMessage(msg, isError = true)
            }
        }
    }

    /**
     * التحقق الفعلي من PackageManager وBuildConfig.BUILD_ID عند فتح التطبيق أو العودة من شاشة التثبيت
     * للتأكد مما إذا كان الإصدار الجديد قد تم تثبيته فعلياً أم فشل/أُلغي.
     */
    fun verifyPostInstallAndRefreshVersion() {
        updateManager.refreshInstalledVersionState()
        when (val verifyResult = updateManager.verifyPendingInstallation()) {
            is com.example.util.PendingInstallVerification.VerifiedInstalled -> {
                _availableUpdate.value = null
                _updateAvailable.value = false
                _downloadedApkFile.value = null
                _updateProgress.value = 0
                updateManager.resetDownloadState()
                _updateUiState.value = com.example.util.UpdateUiState.UP_TO_DATE
                val msg = "تم تحديث التطبيق بنجاح إلى الإصدار ${verifyResult.versionName}."
                _lastUpdateCheckStatus.value = msg
                postMessage(msg, isError = false)
            }
            is com.example.util.PendingInstallVerification.NotYetInstalled -> {
                val reason = verifyResult.diagnosticError.ifBlank {
                    "لم يكتمل تثبيت التحديث بعد."
                }
                val cleanMsg = UpdateManager.sanitizeUserFacingErrorMessage(reason)
                _lastUpdateCheckStatus.value = cleanMsg
                if (verifyResult.diagnosticError.isNotBlank()) {
                    postMessage(cleanMsg, isError = true)
                }
            }
            com.example.util.PendingInstallVerification.None -> {
                val installedCode = updateManager.getEffectiveVersionCode()
                val pending = _availableUpdate.value
                if (pending != null && installedCode >= pending.versionCode) {
                    _availableUpdate.value = null
                    _updateAvailable.value = false
                    _downloadedApkFile.value = null
                    _updateProgress.value = 0
                    updateManager.resetDownloadState()
                    _updateUiState.value = com.example.util.UpdateUiState.UP_TO_DATE
                }
            }
        }
        refreshBuildIdentityReport()
    }

    private fun startPeriodicUpdateScheduler() {
        if (periodicUpdateSchedulerJob?.isActive == true) return
        periodicUpdateSchedulerJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(UpdateManager.DEFAULT_PERIODIC_CHECK_INTERVAL_MS)
                runPeriodicUpdateCheckIfDue(force = false)
            }
        }
    }

    fun dismissUpdateBanner() {
        val currentCandidate = _availableUpdate.value
        if (currentCandidate != null && !currentCandidate.isMandatory) {
            updateManager.snoozeUpdateNotification(currentCandidate.versionCode)
            _isUpdateBannerDismissed.value = true
        }
    }

    fun runPeriodicUpdateCheckIfDue(force: Boolean = false) {
        if (_isCheckingUpdate.value) return
        if (!force && !updateManager.shouldRunPeriodicCheck()) return
        viewModelScope.launch {
            if (_isCheckingUpdate.value) return@launch
            val result = updateManager.checkForUpdatesPeriodicallyIfDue(
                metadataUrl = customUpdateMetadataUrl.value,
                clusterCode = serverClusterCode.value,
                force = force
            )
            val installedCode = updateManager.getEffectiveVersionCode()
            result.onSuccess { updateInfo ->
                if (updateInfo != null && updateInfo.versionCode > installedCode) {
                    _availableUpdate.value = updateInfo
                    _updateAvailable.value = true
                    _isUpdateBannerDismissed.value =
                        !updateInfo.isMandatory &&
                            updateManager.isUpdateNotificationSnoozed(updateInfo.versionCode)
                    if (_updateUiState.value != com.example.util.UpdateUiState.DOWNLOADING &&
                        _updateUiState.value != com.example.util.UpdateUiState.READY_TO_INSTALL &&
                        _updateUiState.value != com.example.util.UpdateUiState.INSTALLING
                    ) {
                        _updateUiState.value = com.example.util.UpdateUiState.AVAILABLE
                        _lastUpdateCheckStatus.value = "يتوفر إصدار جديد يتضمن تحسينات وإصلاحات."
                    }
                    refreshBuildIdentityReport()
                }
            }
        }
    }

    fun checkForAppUpdates(manual: Boolean = true, customUrl: String? = null) {
        if (_isCheckingUpdate.value) {
            if (manual) {
                postMessage("جاري التحقق من التحديثات، يرجى الانتظار...")
            }
            return
        }
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            _updateUiState.value = com.example.util.UpdateUiState.CHECKING
            _lastUpdateCheckStatus.value = "جاري التحقق من وجود تحديثات..."
            verifyPostInstallAndRefreshVersion()
            val url = customUrl ?: customUpdateMetadataUrl.value
            val result = updateManager.checkForUpdates(url, serverClusterCode.value)
            updateManager.recordPeriodicCheckTimestamp()
            _isCheckingUpdate.value = false

            val installedCode = updateManager.getEffectiveVersionCode()

            result.onSuccess { updateInfo ->
                if (updateInfo != null && updateInfo.versionCode > installedCode) {
                    if (manual) {
                        updateManager.clearUpdateNotificationSnooze()
                        _isUpdateBannerDismissed.value = false
                    } else {
                        _isUpdateBannerDismissed.value =
                            !updateInfo.isMandatory &&
                                updateManager.isUpdateNotificationSnoozed(updateInfo.versionCode)
                    }
                    _availableUpdate.value = updateInfo
                    _updateAvailable.value = true
                    _updateUiState.value = com.example.util.UpdateUiState.AVAILABLE
                    val statusMsg = "يتوفر إصدار جديد يتضمن تحسينات وإصلاحات."
                    _lastUpdateCheckStatus.value = statusMsg
                    com.example.util.UpdateNotificationHelper.notifyNewUpdateAvailableIfNeeded(
                        context = getApplication(),
                        updateInfo = updateInfo,
                        installedVersionCode = installedCode
                    )
                    if (manual) {
                        postMessage("يتوفر تحديث جديد (${updateInfo.versionName}).")
                    }
                } else {
                    _availableUpdate.value = null
                    _updateAvailable.value = false
                    _isUpdateBannerDismissed.value = false
                    _downloadedApkFile.value = null
                    _updateUiState.value = com.example.util.UpdateUiState.UP_TO_DATE
                    val upToDateMsg = "أنت تستخدم أحدث إصدار من PMP PAINTS."
                    _lastUpdateCheckStatus.value = upToDateMsg
                    if (manual) {
                        postMessage("التطبيق محدّث — أنت تستخدم أحدث إصدار من PMP PAINTS.")
                    }
                }
                refreshBuildIdentityReport()
            }.onFailure { err ->
                val cleanErr = UpdateManager.sanitizeUserFacingErrorMessage(err.message)
                _updateUiState.value = com.example.util.UpdateUiState.FAILED
                _lastUpdateCheckStatus.value = cleanErr
                if (manual) {
                    postMessage(cleanErr, isError = true)
                }
                refreshBuildIdentityReport()
            }
        }
    }

    fun publishReleaseToCloud(
        vCode: Int,
        vName: String,
        dlUrl: String,
        releaseNotes: String = "يتوفر إصدار جديد يتضمن تحسينات وإصلاحات.",
        sha256: String = "",
        buildId: String = "",
        gitCommit: String = "",
        isMandatory: Boolean = false,
        requireRemoteBinaryVerification: Boolean = true
    ) {
        val user = currentUser.value
        val isAdmin = user.isSuperAdminAccount() || user?.username?.equals("admin", ignoreCase = true) == true
        if (!isAdmin) {
            postMessage("عفواً، صلاحية بث ونشر التحديثات السحابية مخصصة لحساب المدير العام (admin) فقط", isError = true)
            return
        }
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            _isUploadingUpdateToFirebase.value = true
            _firebaseUploadProgress.value = 5
            _firebaseUploadStageText.value = "جاري التحقق من جاهزية الإصدار ونشره في السحابة..."
            updateManager.setAdminBroadcastGuardEnabled(true)
            val info = UpdateInfo(
                versionCode = vCode,
                versionName = vName,
                downloadUrl = dlUrl,
                releaseNotes = releaseNotes,
                isMandatory = isMandatory,
                sha256Checksum = sha256.trim(),
                fileSizeMb = 30.6,
                fileSize = if (vCode == UpdateManager.LATEST_CLOUD_RELEASE_INFO.versionCode) {
                    UpdateManager.LATEST_CLOUD_RELEASE_INFO.fileSize
                } else {
                    0L
                },
                buildId = buildId.trim(),
                gitCommit = gitCommit.trim().ifBlank { BuildConfig.GIT_COMMIT_SHA },
                signingCertSha256 = UpdateManager.APPROVED_SIGNING_CERT_SHA256,
                releaseStatus = "PUBLISHED"
            )
            val res = updateManager.publishVersionToFirestore(
                info = info,
                clusterCode = serverClusterCode.value,
                requireRemoteBinaryVerification = requireRemoteBinaryVerification,
                onProgress = { pct, stage ->
                    _firebaseUploadProgress.value = pct
                    _firebaseUploadStageText.value = stage
                }
            )
            _isCheckingUpdate.value = false
            _isUploadingUpdateToFirebase.value = false
            res.onSuccess { verified ->
                _firebaseUploadProgress.value = 100
                if (verified.versionCode > updateManager.getEffectiveVersionCode()) {
                    _availableUpdate.value = verified
                    _updateAvailable.value = true
                    _updateUiState.value = com.example.util.UpdateUiState.AVAILABLE
                }
                val msg = "تم نشر التحديث السحابي (${verified.versionName}) بنجاح لجميع الأجهزة."
                _firebaseUploadStageText.value = msg
                _lastUpdateCheckStatus.value = msg
                postMessage(msg, isError = false)
                refreshBuildIdentityReport()
            }.onFailure {
                _firebaseUploadProgress.value = 0
                val errMsg = it.message ?: "تعذر نشر التحديث في السحابة."
                _firebaseUploadStageText.value = errMsg
                postMessage(errMsg, isError = true)
                refreshBuildIdentityReport()
            }
        }
    }

    /**
     * بث التحديث المعتمد إلى كافة النسخ السابقة أو جميع الأجهزة سحابياً عبر حساب المدير (admin)
     */
    fun broadcastUpdateToAllPreviousVersions(
        customCandidate: UpdateInfo? = null,
        forceNewIncrement: Boolean = false
    ) {
        val user = currentUser.value
        val isAdmin = user.isSuperAdminAccount() || user?.username?.equals("admin", ignoreCase = true) == true
        if (!isAdmin) {
            postMessage("عفواً، صلاحية بث التحديثات السحابية مخصصة لحساب المدير العام (admin) فقط", isError = true)
            return
        }
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            _isUploadingUpdateToFirebase.value = true
            _firebaseUploadProgress.value = 5
            _firebaseUploadStageText.value = "جاري التحقق من حزمة التحديث ورفعها إلى السحابة..."
            updateManager.setAdminBroadcastGuardEnabled(true)
            val release = customCandidate ?: _availableUpdate.value ?: UpdateManager.LATEST_CLOUD_RELEASE_INFO
            val res = updateManager.broadcastToAllClusters(
                info = release,
                primaryClusterCode = serverClusterCode.value,
                forceNewIncrement = forceNewIncrement,
                onProgress = { pct, stage ->
                    _firebaseUploadProgress.value = pct
                    _firebaseUploadStageText.value = stage
                }
            )
            _isCheckingUpdate.value = false
            _isUploadingUpdateToFirebase.value = false
            res.onSuccess { verified ->
                _firebaseUploadProgress.value = 100
                if (verified.versionCode > updateManager.getEffectiveVersionCode()) {
                    _availableUpdate.value = verified
                    _updateAvailable.value = true
                    _updateUiState.value = com.example.util.UpdateUiState.AVAILABLE
                }
                val doneMsg = "تم بث التحديث السحابي (${verified.versionName}) لجميع الأجهزة بنجاح."
                _firebaseUploadStageText.value = doneMsg
                _lastUpdateCheckStatus.value = doneMsg
                postMessage(doneMsg)
                refreshBuildIdentityReport()
            }.onFailure { err ->
                _firebaseUploadProgress.value = 0
                val msg = err.message ?: "تعذر بث التحديث سحابياً."
                _firebaseUploadStageText.value = msg
                postMessage(msg, isError = true)
                refreshBuildIdentityReport()
            }
        }
    }

    fun cancelUpdateDownload() {
        updateManager.cancelDownload()
        _isDownloadingUpdate.value = false
        _updateUiState.value = com.example.util.UpdateUiState.CANCELLED
        _lastUpdateCheckStatus.value = "تم إيقاف تنزيل التحديث مؤقتاً. يمكنك إعادة المحاولة في أي وقت."
    }

    fun startUpdateDownload(onReadyToInstall: (File) -> Unit = {}) {
        if (_isDownloadingUpdate.value) return
        val update = _availableUpdate.value
        if (update == null) {
            postMessage("لا يوجد تحديث متاح حالياً للتنزيل.", isError = true)
            return
        }

        viewModelScope.launch {
            _isDownloadingUpdate.value = true
            _updateUiState.value = com.example.util.UpdateUiState.DOWNLOADING
            _updateProgress.value = 2
            val result = updateManager.downloadApk(update) { percent, _, _ ->
                if (percent >= 0) {
                    _updateProgress.value = percent
                }
            }
            _isDownloadingUpdate.value = false

            result.onSuccess { apkFile ->
                _downloadedApkFile.value = apkFile
                _updateProgress.value = 100
                _updateUiState.value = com.example.util.UpdateUiState.READY_TO_INSTALL
                _lastUpdateCheckStatus.value = "تم تنزيل التحديث بنجاح."
                onReadyToInstall(apkFile)
            }.onFailure { err ->
                _downloadedApkFile.value = null
                if (err is kotlinx.coroutines.CancellationException) {
                    _updateUiState.value = com.example.util.UpdateUiState.CANCELLED
                    _lastUpdateCheckStatus.value = "تم إيقاف تنزيل التحديث مؤقتاً."
                } else {
                    val cleanErr = UpdateManager.sanitizeUserFacingErrorMessage(err.message)
                    _updateUiState.value = com.example.util.UpdateUiState.FAILED
                    _lastUpdateCheckStatus.value = cleanErr
                    postMessage(cleanErr, isError = true)
                }
            }
        }
    }

    fun downloadAndInstallUpdate(context: android.content.Context, onReadyToInstall: (File) -> Unit = {}) {
        startUpdateDownload(onReadyToInstall = onReadyToInstall)
    }

    fun launchApkInstallation(context: android.content.Context, apkFile: File? = null) {
        val pendingUpdate = _availableUpdate.value
        if (!updateManager.hasDeclaredRequestInstallPackagesPermission()) {
            openGooglePlayOrWebUpdatePage(context, pendingUpdate?.downloadUrl)
            return
        }
        val target = apkFile ?: _downloadedApkFile.value
        if (target == null || !target.exists()) {
            postMessage("يرجى تنزيل التحديث أولاً قبل التثبيت.", isError = true)
            return
        }
        val res = updateManager.installApk(context, target, pendingUpdate)
        res.onSuccess {
            _updateUiState.value = com.example.util.UpdateUiState.INSTALLING
            _lastUpdateCheckStatus.value = "تم فتح مثبّت الحزم لتثبيت التحديث المنزّل؛ يرجى تأكيد التثبيت."
            postMessage("تم فتح نافذة تثبيت الإصدار الجديد.")
        }.onFailure { err ->
            val errMsg = UpdateManager.sanitizeUserFacingErrorMessage(err.message)
            _lastUpdateCheckStatus.value = errMsg
            postMessage(errMsg, isError = true)
        }
    }

    fun hasDeclaredInstallPackagesPermission(): Boolean {
        return updateManager.hasDeclaredRequestInstallPackagesPermission()
    }

    fun canRequestPackageInstalls(apkFile: File? = _downloadedApkFile.value): Boolean {
        return updateManager.canRequestPackageInstalls()
    }

    fun openGooglePlayOrWebUpdatePage(
        context: android.content.Context,
        fallbackWebUrl: String? = _availableUpdate.value?.downloadUrl
    ): Boolean {
        val opened = updateManager.openGooglePlayOrWebUpdatePage(
            activityContext = context,
            fallbackWebUrl = fallbackWebUrl?.takeIf { it.isNotBlank() } ?: UpdateManager.DEFAULT_APK_DOWNLOAD_URL
        )
        if (opened) {
            postMessage("تم فتح صفحة التحديث الرسمية لإكمال التثبيت بأمان.")
        } else {
            postMessage("تعذر فتح متجر Google Play أو صفحة التحديث الرسمية.", isError = true)
        }
        return opened
    }

    fun openInstallPermissionSettings(context: android.content.Context) {
        updateManager.openInstallPermissionSettings(
            activityContext = context,
            fallbackWebUrl = _availableUpdate.value?.downloadUrl?.takeIf { it.isNotBlank() } ?: UpdateManager.DEFAULT_APK_DOWNLOAD_URL
        )
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
            val devId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(getApplication())
            val activeId = activeSessionUserId.value
            val isMgr = currentUser.value.isSystemManager()
            _isSyncing.value = true
            _cloudSyncState.value = "جاري المزامنة الفورية مع سيرفر Google Cloud..."

            // 1. Pull and merge any remote accounts first so no user created on another device is lost
            cloudErpSyncRepo.pullAndMergeCloudUsers(targetCluster, repository.getDao(), activeId, devId)

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
            val syncRes = cloudErpSyncRepo.syncAllLocalToCloud(targetCluster, repository.getDao(), isMgr, activeId, devId)
            DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())

            if (Firebase.auth.currentUser != null) {
                val managed = cloudSyncRepo.getManagedAccounts().getOrDefault(emptyList())
                _cloudLinkedAccounts.value = managed
            }

            _isSyncing.value = false
            syncRes.onSuccess {
                _cloudSyncState.value = "متصل بسيرفر Google Cloud • تم ربط ومزامنة $syncedCount حساب"
                checkServerHealth()
                if (!silent) {
                    postMessage("تم ربط ومزامنة $syncedCount حساب وصلاحياتهم وكافة البيانات مع سيرفر Google Cloud بنجاح")
                }
            }.onFailure { err ->
                val safeMsg = com.example.data.remote.CloudSyncErrorTracker.sanitizeCloudMessage(err.message)
                _cloudSyncState.value = "تعذر الحفظ على السيرفر: $safeMsg"
                checkServerHealth()
                if (!silent) {
                    postMessage("تعذر الحفظ على السيرفر: $safeMsg", isError = true)
                }
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
            val devId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(getApplication())
            cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao(), activeSessionUserId.value, devId)
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
                _cloudSyncState.value = report.message
            }
        }
    }

    fun refreshPreflightCheck() {
        viewModelScope.launch(Dispatchers.IO) {
            cloudErpSyncRepo.runSyncPreflightCheck(
                clusterCode = serverClusterCode.value,
                dao = repository.getDao(),
                activeUserId = activeSessionUserId.value,
                isManagerHint = currentUser.value.isSystemManager()
            )
        }
    }

    fun runSingleServiceProbe(probeId: com.example.data.remote.ServiceProbeId) {
        viewModelScope.launch(Dispatchers.IO) {
            val cluster = serverClusterCode.value
            val result = when (probeId) {
                com.example.data.remote.ServiceProbeId.NETWORK -> cloudErpSyncRepo.probeNetworkConnectivity()
                com.example.data.remote.ServiceProbeId.FIREBASE_AUTH -> cloudErpSyncRepo.probeFirebaseAuth()
                com.example.data.remote.ServiceProbeId.FIRESTORE -> cloudErpSyncRepo.probeFirestoreAccess(cluster)
                com.example.data.remote.ServiceProbeId.RBAC_POLICY -> cloudErpSyncRepo.probeRbacPolicy(cluster, currentUser.value)
                com.example.data.remote.ServiceProbeId.APP_UPDATES -> cloudErpSyncRepo.probeUpdatesMetadata()
            }
            postMessage("${probeId.arabicLabel}: ${result.detail}", isError = !result.ok)
        }
    }

    fun runComprehensiveServerDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncing.value = true
            val report = cloudErpSyncRepo.runComprehensiveDiagnostics(
                clusterCode = serverClusterCode.value,
                dao = repository.getDao(),
                activeUserId = activeSessionUserId.value,
                isManagerHint = currentUser.value.isSystemManager()
            )
            val health = cloudErpSyncRepo.checkServerHealth(serverClusterCode.value)
            _serverHealthReport.value = health
            _isSyncing.value = false
            _cloudSyncState.value = report.summaryMessage
            postMessage(report.summaryMessage, isError = !report.overallOk)
        }
    }

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

    fun verifyAndWipeAllServerData(
        managerPassword: String,
        onAuthError: (String) -> Unit = {},
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            if (!checkOnlineBeforeTransaction("تصفير وحذف جميع سجلات السيرفر")) {
                onAuthError("لا يمكن تصفير السيرفر بدون اتصال بالإنترنت")
                return@launch
            }
            val authRes = repository.verifySystemManagerPassword(managerPassword)
            authRes.onSuccess {
                wipeAllServerDataAndStartFresh(onComplete)
            }.onFailure { err ->
                val msg = err.message ?: "كلمة المرور الخاصة بمدير النظام غير صحيحة!"
                postMessage(msg, isError = true)
                onAuthError(msg)
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
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
                }
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
                val devId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(getApplication())
                val syncRes = cloudErpSyncRepo.syncAllLocalToCloud(
                    serverClusterCode.value,
                    repository.getDao(),
                    currentUser.value.isSystemManager(),
                    activeSessionUserId.value,
                    devId
                )
                syncRes.onFailure { err ->
                    val safeMsg = com.example.data.remote.CloudSyncErrorTracker.sanitizeCloudMessage(err.message)
                    _cloudSyncState.value = "تعذر الحفظ على السيرفر: $safeMsg"
                    Log.w("PmpViewModel", "persistAndSyncToServer failure: $safeMsg")
                }
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
            if (!com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(currentUser.value)) {
                postMessage("عفواً، توليد أو تعديل رمز الربط السحابي متاح لحساب Admin فقط.", isError = true)
                return@launch
            }
            val res = repository.regenerateUserSyncToken(user, resolveUserActor("Admin"))
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

    fun refreshSecurityStateForUser(targetUser: UserAccountEntity? = currentUser.value) {
        val app = getApplication<Application>()
        _securityActivities.value = com.example.util.AuthSecurityManager.getLoginActivities(app)
        _suspiciousAlerts.value = com.example.util.AuthSecurityManager.getSuspiciousAlerts(app)
        _sessionMaxAgeHours.value = (DeviceSessionStore.getSessionMaxAgeMs(app) / (60 * 60 * 1000L)).toInt().coerceAtLeast(1)
        _sessionIdleTimeoutMinutes.value = (DeviceSessionStore.getSessionIdleTimeoutMs(app) / (60 * 1000L)).toInt().coerceAtLeast(5)
        if (targetUser != null && targetUser.userCode.isNotBlank()) {
            _trustedDevices.value = com.example.util.AuthSecurityManager.getTrustedDevicesForUser(app, targetUser.userCode)
            _currentTwoFactorConfig.value = com.example.util.AuthSecurityManager.getTwoFactorConfig(
                app,
                targetUser.userCode,
                targetUser.username
            )
        }
    }

    private fun completeAuthenticatedSessionLogin(
        user: UserAccountEntity,
        method: String,
        bindBiometricIfFirstOrSameUser: Boolean = true,
        onSuccess: (UserAccountEntity) -> Unit = {}
    ) {
        val app = getApplication<Application>()
        DeletedAccountsRegistry.removeTombstone(user.userCode, user.username, user.phoneOrEmail)
        com.example.util.AuthSecurityManager.clearFailedLoginAttempts(app, user.username)
        com.example.util.AuthSecurityManager.clearFailedLoginAttempts(app, user.userCode)
        if (user.phoneOrEmail.isNotBlank()) {
            com.example.util.AuthSecurityManager.clearFailedLoginAttempts(app, user.phoneOrEmail)
        }

        viewModelScope.launch {
            repository.getDao().setActiveSessionUser(user.id)
        }
        twoFactorPendingUser.value = null
        activeSessionOverride.value = user
        activeSessionUserId.value = user.id
        isSessionAuthenticated.value = true
        DeviceSessionStore.saveActiveUserSession(app, user)
        com.example.util.BiometricPreferenceManager.synchronizeBindingOnAuthenticatedLogin(
            context = app,
            authenticatedUser = user,
            autoBindIfUnbound = bindBiometricIfFirstOrSameUser
        )
        com.example.util.AuthSecurityManager.registerAuthenticatedDevice(
            context = app,
            userCode = user.userCode,
            username = user.username,
            autoTrustFirstDevice = true
        )
        com.example.util.AuthSecurityManager.recordLoginActivity(
            context = app,
            userCode = user.userCode,
            username = user.username,
            fullName = user.fullName,
            eventType = "LOGIN_SUCCESS",
            method = method,
            success = true,
            detail = "تسجيل دخول ناجح عبر $method (${user.role})"
        )
        refreshSecurityStateForUser(user)
        startCloudRealtimeSyncIfAuthenticated()
        onSuccess(user)
    }

    fun loginWithCredentials(
        emailOrUsername: String,
        passwordOrPin: String,
        onSuccess: (UserAccountEntity) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val cleanIdentity = emailOrUsername.trim().lowercase(java.util.Locale.ROOT)
                val cleanSecret = passwordOrPin.trim()

                if (cleanIdentity.isBlank()) {
                    val err = "يرجى إدخال البريد الإلكتروني أو اسم المستخدم أو كود الحساب"
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

                // 0. Enforce Brute-Force Protection before checking credentials
                val lockoutStatus = com.example.util.AuthSecurityManager.checkLoginAllowed(app, cleanIdentity)
                if (lockoutStatus.isLocked) {
                    postMessage(lockoutStatus.message, isError = true)
                    onError(lockoutStatus.message)
                    return@launch
                }

                // 1. Instant local authentication: Verify against Room first so switching users is immediate (0ms freeze)
                val localRes = repository.authenticateUserCredentials(cleanIdentity, cleanSecret)
                if (localRes.isSuccess) {
                    val user = localRes.getOrThrow()
                    val deviceId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(app)
                    if (com.example.util.AuthSecurityManager.isDeviceRevokedForUser(app, user.userCode, deviceId)) {
                        repository.getDao().setActiveSessionUser(-1L)
                        val revokedMsg = "هذا الجهاز تم إبطاله أمنياً للحساب (${user.fullName}). يرجى مراجعة مدير النظام أو استعادة الجهاز من إعدادات الأمان."
                        postMessage(revokedMsg, isError = true)
                        onError(revokedMsg)
                        return@launch
                    }

                    // Check if optional 2FA is enabled and required for this login
                    if (com.example.util.AuthSecurityManager.isTwoFactorRequiredForLogin(app, user.userCode, deviceId)) {
                        repository.getDao().setActiveSessionUser(-1L)
                        com.example.util.AuthSecurityManager.clearFailedLoginAttempts(app, cleanIdentity)
                        twoFactorPendingUser.value = user
                        postMessage("تم التحقق من كلمة المرور. يرجى إدخال رمز المصادقة الثنائية (2FA) لإكمال تسجيل الدخول.")
                        return@launch
                    }

                    completeAuthenticatedSessionLogin(
                        user = user,
                        method = "PASSWORD",
                        bindBiometricIfFirstOrSameUser = true
                    ) { authenticated ->
                        // Background cloud user sync (non-blocking, won't freeze login)
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val devId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(app)
                                cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao(), authenticated.id, devId)
                                if (com.example.util.PasswordSecurityUtil.isPbkdf2Hash(authenticated.pinOrPassword)) {
                                    cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, authenticated)
                                }
                            } catch (e: Exception) {
                                Log.w("PmpViewModel", "Async post-login user sync note: ${e.message}")
                            }
                        }
                        postMessage("مرحباً بك ${authenticated.fullName} • الصلاحيات: ${authenticated.permissions}")
                        onSuccess(authenticated)
                    }
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
                    val deviceId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(app)
                    if (com.example.util.AuthSecurityManager.isDeviceRevokedForUser(app, finalUser.userCode, deviceId)) {
                        repository.getDao().setActiveSessionUser(-1L)
                        val revokedMsg = "هذا الجهاز تم إبطاله أمنياً للحساب (${finalUser.fullName})."
                        postMessage(revokedMsg, isError = true)
                        onError(revokedMsg)
                        return@launch
                    }
                    if (com.example.util.AuthSecurityManager.isTwoFactorRequiredForLogin(app, finalUser.userCode, deviceId)) {
                        repository.getDao().setActiveSessionUser(-1L)
                        com.example.util.AuthSecurityManager.clearFailedLoginAttempts(app, cleanIdentity)
                        twoFactorPendingUser.value = finalUser
                        postMessage("تم التحقق من كلمة المرور السحابية. يرجى إدخال رمز المصادقة الثنائية (2FA) لإكمال الدخول.")
                        return@launch
                    }
                    DatabaseBackupManager.saveLocalSnapshot(app, repository.getDao())
                    completeAuthenticatedSessionLogin(
                        user = finalUser,
                        method = "CLOUD_PASSWORD",
                        bindBiometricIfFirstOrSameUser = true
                    ) { authenticated ->
                        postMessage("تمت مزامنة حسابك (${authenticated.fullName}) بنجاح وتسجيل الدخول!")
                        onSuccess(authenticated)
                    }
                } else {
                    val matchedAccount = repository.findUserByIdentity(cleanIdentity)
                    val lockoutAfterFail = com.example.util.AuthSecurityManager.recordFailedLoginAttempt(
                        context = app,
                        identity = cleanIdentity,
                        matchedUserCode = matchedAccount?.userCode.orEmpty(),
                        reason = cloudError ?: localMsg.ifBlank { "كلمة المرور أو الهوية غير صحيحة" }
                    )
                    refreshSecurityStateForUser()
                    val finalErrMsg = if (lockoutAfterFail.isLocked) {
                        lockoutAfterFail.message
                    } else {
                        val baseErr = cloudError ?: localMsg.ifBlank { "اسم المستخدم أو كلمة المرور غير صحيحة. يرجى التأكد من البيانات أو مراجعة مدير النظام." }
                        if (lockoutAfterFail.remainingAttemptsBeforeLock <= 2) {
                            "$baseErr (تنبيه أمني: متبقي ${lockoutAfterFail.remainingAttemptsBeforeLock} محاولات قبل الإغلاق المؤقت)"
                        } else {
                            baseErr
                        }
                    }
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

    fun verifyTwoFactorLogin(
        codeInput: String,
        trustCurrentDevice: Boolean = true,
        onSuccess: (UserAccountEntity) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val pendingUser = twoFactorPendingUser.value
            if (pendingUser == null) {
                val err = "لا توجد جلسة مصادقة ثنائية معلقة حالياً. يرجى تسجيل الدخول أولاً."
                postMessage(err, isError = true)
                onError(err)
                return@launch
            }
            val lockoutStatus = com.example.util.AuthSecurityManager.checkLoginAllowed(app, pendingUser.userCode)
            if (lockoutStatus.isLocked) {
                postMessage(lockoutStatus.message, isError = true)
                onError(lockoutStatus.message)
                return@launch
            }

            val verifyRes = com.example.util.AuthSecurityManager.verifyTwoFactorCodeOrRecovery(
                context = app,
                userCode = pendingUser.userCode,
                inputCode = codeInput
            )
            verifyRes.onSuccess {
                val deviceId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(app)
                if (trustCurrentDevice) {
                    com.example.util.AuthSecurityManager.registerAuthenticatedDevice(
                        context = app,
                        userCode = pendingUser.userCode,
                        username = pendingUser.username,
                        autoTrustFirstDevice = true
                    )
                    com.example.util.AuthSecurityManager.setDeviceTrustStatus(
                        context = app,
                        userCode = pendingUser.userCode,
                        deviceId = deviceId,
                        trusted = true
                    )
                }
                completeAuthenticatedSessionLogin(
                    user = pendingUser,
                    method = "PASSWORD_2FA",
                    bindBiometricIfFirstOrSameUser = true
                ) { authenticated ->
                    postMessage("تم التحقق من المصادقة الثنائية (2FA) بنجاح! مرحباً بك ${authenticated.fullName}")
                    onSuccess(authenticated)
                }
            }.onFailure { err ->
                val failLock = com.example.util.AuthSecurityManager.recordFailedLoginAttempt(
                    context = app,
                    identity = pendingUser.userCode,
                    matchedUserCode = pendingUser.userCode,
                    reason = "فشل رمز المصادقة الثنائية (2FA)"
                )
                refreshSecurityStateForUser(pendingUser)
                val msg = if (failLock.isLocked) failLock.message else (err.message ?: "رمز المصادقة الثنائية غير صحيح")
                postMessage(msg, isError = true)
                onError(msg)
            }
        }
    }

    fun cancelTwoFactorLogin() {
        twoFactorPendingUser.value = null
    }

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
                    val devId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(getApplication())
                    cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao(), activeSessionUserId.value, devId)
                }
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Cloud check warning: ${e.message}")
            }

            val allUsers = repository.getDao().getUserAccountsDirect()
            val usernameCandidate = cleanUsername.ifBlank { cleanEmail.substringBefore("@") }
            val hashedPin = com.example.util.PasswordSecurityUtil.hashPassword(cleanPin)
            
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
            val app = getApplication<Application>()
            try {
                val devId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(app)
                cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao(), activeSessionUserId.value, devId)
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Reset pull warning: ${e.message}")
            }
            val targetUser = repository.findUserByIdentity(identity)
            if (targetUser == null) {
                val err = "لم يتم العثور على أي حساب مسجل بهذه البيانات ($identity)"
                postMessage(err, isError = true)
                onError(err)
                return@launch
            }
            val issuedOtp = com.example.util.AuthSecurityManager.issuePasswordResetChallenge(
                context = app,
                userCode = targetUser.userCode,
                username = targetUser.username,
                channel = channel
            )
            val res = repository.generatePasswordResetCode(identity, channel, providedOtpCode = issuedOtp)
            res.onSuccess { (code, user) ->
                refreshSecurityStateForUser(user)
                persistAndSyncToServer()
                onCodeReady(code, user)
            }.onFailure {
                val err = it.message ?: "تعذر العثور على الحساب"
                postMessage(err, isError = true)
                onError(err)
            }
        }
    }

    fun confirmPasswordResetWithOtp(
        userId: Long,
        otpInput: String,
        newPass: String,
        onSuccess: (UserAccountEntity) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val user = repository.getDao().getUserById(userId)
            if (user == null) {
                val err = "الحساب غير موجود"
                postMessage(err, isError = true)
                onError(err)
                return@launch
            }
            val verifyOtp = com.example.util.AuthSecurityManager.verifyAndConsumePasswordResetOtp(
                context = app,
                userCode = user.userCode,
                otpInput = otpInput
            )
            if (verifyOtp.isFailure) {
                val err = verifyOtp.exceptionOrNull()?.message ?: "رمز التحقق غير صحيح أو منتهي الصلاحية"
                postMessage(err, isError = true)
                onError(err)
                return@launch
            }
            confirmPasswordReset(userId, newPass, onSuccess, onError)
        }
    }

    fun confirmPasswordReset(
        userId: Long,
        newPass: String,
        onSuccess: (UserAccountEntity) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val res = repository.completePasswordReset(userId, newPass, resolveUserActor("المستخدم"))
            res.onSuccess { updated ->
                com.example.util.AuthSecurityManager.clearFailedLoginAttempts(app, updated.username)
                com.example.util.AuthSecurityManager.clearFailedLoginAttempts(app, updated.userCode)
                if (updated.phoneOrEmail.isNotBlank()) {
                    com.example.util.AuthSecurityManager.clearFailedLoginAttempts(app, updated.phoneOrEmail)
                }
                com.example.util.AuthSecurityManager.revokeAllSessionsForUser(app, updated.userCode)
                if (com.example.util.BiometricPreferenceManager.isBoundToUser(app, updated)) {
                    com.example.util.BiometricPreferenceManager.bindUser(app, updated)
                }
                cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, updated)
                DatabaseBackupManager.saveLocalSnapshot(app, repository.getDao())
                refreshSecurityStateForUser(updated)
                postMessage("تم تغيير كلمة المرور بنجاح وإبطال الجلسات القديمة للحساب (${updated.fullName})")
                onSuccess(updated)
            }.onFailure {
                val err = it.message ?: "تعذر تعيين كلمة المرور"
                postMessage(err, isError = true)
                onError(err)
            }
        }
    }

    fun recoverAccountWithBackupCode(
        identity: String,
        backupRecoveryCode: String,
        newPass: String,
        onSuccess: (UserAccountEntity) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val user = repository.findUserByIdentity(identity)
            if (user == null) {
                val err = "لم يتم العثور على الحساب ($identity)"
                postMessage(err, isError = true)
                onError(err)
                return@launch
            }
            if (newPass.trim().length < 4) {
                val err = "كلمة المرور الجديدة يجب أن تتكون من 4 خانات على الأقل"
                postMessage(err, isError = true)
                onError(err)
                return@launch
            }
            val consumed = com.example.util.AuthSecurityManager.consumeBackupRecoveryCode(
                context = app,
                userCode = user.userCode,
                rawRecoveryCode = backupRecoveryCode
            )
            if (!consumed) {
                com.example.util.AuthSecurityManager.recordFailedLoginAttempt(
                    context = app,
                    identity = user.userCode,
                    matchedUserCode = user.userCode,
                    reason = "فشل محاولة استرداد الحساب بكود استرداد احتياطي غير صالح"
                )
                val err = "كود الاسترداد الاحتياطي غير صحيح أو تم استخدامه مسبقاً."
                postMessage(err, isError = true)
                onError(err)
                return@launch
            }
            confirmPasswordReset(
                userId = user.id,
                newPass = newPass,
                onSuccess = { updated ->
                    com.example.util.AuthSecurityManager.recordLoginActivity(
                        context = app,
                        userCode = updated.userCode,
                        username = updated.username,
                        fullName = updated.fullName,
                        eventType = "ACCOUNT_RECOVERY_BACKUP_CODE",
                        method = "BACKUP_RECOVERY_CODE",
                        success = true,
                        detail = "تم استرداد الحساب وتغيير كلمة المرور باستخدام كود استرداد احتياطي"
                    )
                    refreshSecurityStateForUser(updated)
                    onSuccess(updated)
                },
                onError = onError
            )
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

            try {
                withTimeoutOrNull(3000L) {
                    val devId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(getApplication())
                    cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao(), activeSessionUserId.value, devId)
                }
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Pull cloud error: ${e.message}")
            }

            val allUsers = repository.getDao().getUserAccountsDirect()
            val matched = allUsers.firstOrNull {
                it.userCode.equals(cleanIdentity, ignoreCase = true) ||
                    it.username.equals(cleanIdentity, ignoreCase = true) ||
                    (it.phoneOrEmail.isNotBlank() && it.phoneOrEmail.equals(cleanIdentity, ignoreCase = true))
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
                            (it.phoneOrEmail.isNotBlank() && it.phoneOrEmail.equals(cleanIdentity, ignoreCase = true))
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

            val isPinValid = cleanPin.isNotBlank() &&
                com.example.util.PasswordSecurityUtil.verifyPassword(cleanPin, finalUser.pinOrPassword)

            if (!isPinValid) {
                onError("كلمة المرور أو رمز الدخول غير صحيح للحساب ${finalUser.fullName}")
                return@launch
            }

            // Auto-upgrade legacy password to PBKDF2 hash if needed
            var userToActivate = finalUser
            if (com.example.util.PasswordSecurityUtil.needsUpgradeToPbkdf2(finalUser.pinOrPassword)) {
                val pbkdf2Hash = com.example.util.PasswordSecurityUtil.hashPassword(cleanPin)
                userToActivate = finalUser.copy(pinOrPassword = pbkdf2Hash)
                repository.getDao().updateUserAccount(userToActivate)
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, userToActivate)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Auto-upgrade password hash sync warning: ${e.message}")
                    }
                }
            }

            repository.switchActiveUser(userToActivate.id, cleanPin)
            completeAuthenticatedSessionLogin(
                user = userToActivate,
                method = "PENDING_APPROVAL_LOGIN",
                bindBiometricIfFirstOrSameUser = true
            ) { authenticated ->
                postMessage("مرحباً بك ${authenticated.fullName}! تم اعتماد حسابك بنجاح من المدير بالصلاحيات: [${authenticated.permissions}]")
                onSuccess(authenticated)
            }
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
                    val devId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(getApplication())
                    cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao(), activeSessionUserId.value, devId)
                }
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Could not pre-fetch cloud users before Google bind: ${e.message}")
            }

            when (val res = repository.bindFirebaseUserSession(uid, email, displayName)) {
                is GoogleSignInBindingResult.Approved -> {
                    val app = getApplication<Application>()
                    val deviceId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(app)
                    if (com.example.util.AuthSecurityManager.isDeviceRevokedForUser(app, res.account.userCode, deviceId)) {
                        repository.getDao().setActiveSessionUser(-1L)
                        val msg = "هذا الجهاز موقوف أمنياً للحساب (${res.account.fullName})."
                        postMessage(msg, isError = true)
                        return@launch
                    }
                    try {
                        cloudErpSyncRepo.ensureOwnClusterMemberRegistration(
                            clusterCode = serverClusterCode.value,
                            account = res.account,
                            fbUid = uid,
                            fbEmail = email
                        )
                        cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, res.account)
                        if (res.account.isSystemManager() || res.account.isSuperAdminAccount()) {
                            cloudErpSyncRepo.syncRbacDirectoryForAccount(
                                clusterCode = serverClusterCode.value,
                                account = res.account,
                                linkedUid = uid
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Cloud ERP sync error: ${e.message}")
                    }
                    try {
                        cloudSyncRepo.syncLocalAccountToCloud(res.account, targetLinkedUid = uid)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Cloud account sync error: ${e.message}")
                    }
                    completeAuthenticatedSessionLogin(
                        user = res.account,
                        method = "GOOGLE_OAUTH",
                        bindBiometricIfFirstOrSameUser = true
                    ) { authenticated ->
                        postMessage("تم تسجيل الدخول عبر Google بالحساب المعتمد (${authenticated.fullName}) والربط مع السيرفر")
                        onApprovedSuccess(authenticated)
                    }
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
            val app = getApplication<Application>()
            val bound = com.example.util.BiometricPreferenceManager.getBoundUser(app)
            if (bound == null) {
                onError("لم يتم تفعيل البصمة لأي حساب على هذا الهاتف بعد. يرجى تسجيل الدخول باسم المستخدم وكلمة المرور أولاً لربط بصمتك بحسابك ومنع تداخل الحسابات.")
                return@launch
            }

            // 1. Check Brute-Force Lockout for the bound user
            val lockoutStatus = com.example.util.AuthSecurityManager.checkLoginAllowed(app, bound.userCode)
            if (lockoutStatus.isLocked) {
                postMessage(lockoutStatus.message, isError = true)
                onError(lockoutStatus.message)
                return@launch
            }

            // 2. Validate local account binding strictly by (userCode + username + deviceId + credentialFingerprint)
            // NEVER falls back to auto-increment Room `id`!
            val initialUsers = repository.getDao().getUserAccountsDirect()
            val initialValidation = com.example.util.BiometricPreferenceManager.resolveAndValidateBoundAccount(app, initialUsers)
            if (!initialValidation.isValid || initialValidation.user == null) {
                refreshSecurityStateForUser()
                val errMsg = initialValidation.errorMessage.ifBlank {
                    "فشل التحقق من ربط البصمة بالحساب المسجل. يرجى تسجيل الدخول بكلمة المرور."
                }
                postMessage(errMsg, isError = true)
                onError(errMsg)
                return@launch
            }

            // 3. Server-Side Identity & Status Verification when online:
            // Biometric authentication is NEVER used as a replacement for server-side verification.
            val deviceId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(app)
            if (isConnectedToCloud()) {
                try {
                    withTimeoutOrNull(2800L) {
                        cloudErpSyncRepo.pullAndMergeCloudUsers(
                            serverClusterCode.value,
                            repository.getDao(),
                            initialValidation.user.id,
                            deviceId
                        )
                    }
                } catch (e: Exception) {
                    Log.w("PmpViewModel", "Biometric server-side verification pull note: ${e.message}")
                }
            }

            // 4. Re-validate after server sync to ensure account wasn't suspended, deleted, or had its token/password rotated on the server
            val postSyncUsers = repository.getDao().getUserAccountsDirect()
            val finalValidation = com.example.util.BiometricPreferenceManager.resolveAndValidateBoundAccount(app, postSyncUsers)
            if (!finalValidation.isValid || finalValidation.user == null) {
                refreshSecurityStateForUser()
                val errMsg = finalValidation.errorMessage.ifBlank {
                    "تم رفض الدخول بالبصمة بعد التحقق من السيرفر المركزي. يرجى تسجيل الدخول بكلمة المرور."
                }
                postMessage(errMsg, isError = true)
                onError(errMsg)
                return@launch
            }

            val verifiedTarget = finalValidation.user

            // 5. Check if 2FA is required on an untrusted device
            if (com.example.util.AuthSecurityManager.isTwoFactorRequiredForLogin(app, verifiedTarget.userCode, deviceId)) {
                twoFactorPendingUser.value = verifiedTarget
                postMessage("تم التحقق من البصمة. يرجى إدخال رمز المصادقة الثنائية (2FA) لإكمال الدخول.")
                return@launch
            }

            completeAuthenticatedSessionLogin(
                user = verifiedTarget,
                method = "BIOMETRIC",
                bindBiometricIfFirstOrSameUser = true
            ) { authenticated ->
                postMessage("تم التحقق بنجاح من هوية (${authenticated.fullName}) وتسجيل الدخول بالبصمة")
                onSuccess(authenticated)
            }
        }
    }

    fun verifyActiveSessionNotExpired(onSessionExpired: (String) -> Unit = {}) {
        if (!isSessionAuthenticated.value) return
        val app = getApplication<Application>()
        val activeUser = currentUser.value
        val validation = DeviceSessionStore.validateSessionForUser(app, activeUser)
        if (!validation.isValid) {
            val reason = validation.reasonMessage.ifBlank {
                "انتهت صلاحية الجلسة الحالية، يرجى إعادة تسجيل الدخول."
            }
            com.example.util.AuthSecurityManager.recordLoginActivity(
                context = app,
                userCode = activeUser?.userCode.orEmpty(),
                username = activeUser?.username.orEmpty(),
                fullName = activeUser?.fullName.orEmpty(),
                eventType = "SESSION_EXPIRED_OR_REVOKED",
                method = "SESSION_GUARD",
                success = false,
                detail = reason
            )
            logoutSession(clearBiometrics = false) {
                postMessage(reason, isError = true)
                onSessionExpired(reason)
            }
        } else {
            DeviceSessionStore.touchSessionActivity(app)
        }
    }

    fun updateSessionTimeoutSettings(maxAgeHours: Int, idleTimeoutMinutes: Int) {
        val app = getApplication<Application>()
        val cleanHours = maxAgeHours.coerceIn(1, 168)
        val cleanIdle = idleTimeoutMinutes.coerceIn(5, 720)
        DeviceSessionStore.setSessionMaxAgeMs(app, cleanHours * 60L * 60L * 1000L)
        DeviceSessionStore.setSessionIdleTimeoutMs(app, cleanIdle * 60L * 1000L)
        _sessionMaxAgeHours.value = cleanHours
        _sessionIdleTimeoutMinutes.value = cleanIdle
        postMessage("تم تحديث سياسة انتهاء الجلسة: الحد الأقصى ($cleanHours ساعة) • الخمول ($cleanIdle دقيقة)")
    }

    fun revokeAllOtherSessionsForCurrentUser() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val user = currentUser.value ?: return@launch
            val res = repository.regenerateUserSyncToken(user, resolveUserActor(user.fullName))
            res.onSuccess { newToken ->
                val updatedUser = user.copy(serverSyncToken = newToken, syncStatus = "READY_FOR_SYNC")
                com.example.util.AuthSecurityManager.revokeAllSessionsForUser(app, user.userCode)
                // Re-save current session with the new token so current device stays logged in
                activeSessionOverride.value = updatedUser
                DeviceSessionStore.saveActiveUserSession(app, updatedUser)
                if (com.example.util.BiometricPreferenceManager.isBoundToUser(app, updatedUser)) {
                    com.example.util.BiometricPreferenceManager.bindUser(app, updatedUser)
                }
                cloudErpSyncRepo.syncSingleUserToCloud(serverClusterCode.value, updatedUser)
                refreshSecurityStateForUser(updatedUser)
                postMessage("تم إبطال جميع الجلسات الأخرى وتدوير رمز الأمان السحابي لحساب (${updatedUser.fullName}) بنجاح")
            }.onFailure {
                postMessage(it.message ?: "تعذر إبطال الجلسات الأخرى", isError = true)
            }
        }
    }

    fun enableTwoFactorForCurrentUser(
        requireOnEveryLogin: Boolean = true,
        onEnabled: (String, List<String>) -> Unit = { _, _ -> }
    ) {
        val app = getApplication<Application>()
        val user = currentUser.value ?: run {
            postMessage("يجب تسجيل الدخول أولاً لتفعيل المصادقة الثنائية (2FA)", isError = true)
            return
        }
        val (secret, backupCodes) = com.example.util.AuthSecurityManager.enableTwoFactorAuth(
            context = app,
            userCode = user.userCode,
            username = user.username,
            requireOnEveryLogin = requireOnEveryLogin
        )
        _latestGeneratedRecoveryCodes.value = backupCodes
        refreshSecurityStateForUser(user)
        postMessage("تم تفعيل المصادقة الثنائية (2FA) وتوليد ${backupCodes.size} أكواد استرداد احتياطية لحسابك")
        onEnabled(secret, backupCodes)
    }

    fun disableTwoFactorForCurrentUser() {
        val app = getApplication<Application>()
        val user = currentUser.value ?: return
        com.example.util.AuthSecurityManager.disableTwoFactorAuth(app, user.userCode, user.username)
        _latestGeneratedRecoveryCodes.value = emptyList()
        refreshSecurityStateForUser(user)
        postMessage("تم إيقاف المصادقة الثنائية (2FA) للحساب (${user.fullName})")
    }

    fun regenerateBackupCodesForCurrentUser(onGenerated: (List<String>) -> Unit = {}) {
        val app = getApplication<Application>()
        val user = currentUser.value ?: return
        val codes = com.example.util.AuthSecurityManager.regenerateBackupRecoveryCodes(
            context = app,
            userCode = user.userCode,
            username = user.username
        )
        _latestGeneratedRecoveryCodes.value = codes
        refreshSecurityStateForUser(user)
        postMessage("تم توليد أكواد استرداد احتياطية جديدة (8 أكواد) وإبطال الأكواد السابقة")
        onGenerated(codes)
    }

    fun setTrustedDeviceStatus(deviceId: String, trusted: Boolean) {
        val app = getApplication<Application>()
        val user = currentUser.value ?: return
        com.example.util.AuthSecurityManager.setDeviceTrustStatus(app, user.userCode, deviceId, trusted)
        refreshSecurityStateForUser(user)
        postMessage(if (trusted) "تم توثيق الجهاز كجهاز موثوق" else "تم إلغاء توثيق الجهاز")
    }

    fun revokeTrustedDevice(deviceId: String) {
        val app = getApplication<Application>()
        val user = currentUser.value ?: return
        val currentDevId = com.example.util.BiometricPreferenceManager.getDeviceIdentifier(app)
        com.example.util.AuthSecurityManager.revokeTrustedDevice(
            context = app,
            userCode = user.userCode,
            targetDeviceId = deviceId,
            actor = user.fullName
        )
        refreshSecurityStateForUser(user)
        if (deviceId == currentDevId) {
            logoutSession(clearBiometrics = true) {
                postMessage("تم إبطال هذا الجهاز وإنهاء الجلسة الحالية فوراً", isError = true)
            }
        } else {
            postMessage("تم إبطال الجهاز المحدد ومنعه من الدخول لحسابك")
        }
    }

    fun restoreTrustedDevice(deviceId: String) {
        val app = getApplication<Application>()
        val user = currentUser.value ?: return
        com.example.util.AuthSecurityManager.restoreRevokedDevice(app, user.userCode, deviceId)
        refreshSecurityStateForUser(user)
        postMessage("تمت استعادة صلاحية الجهاز للوصول إلى الحساب")
    }

    fun acknowledgeSuspiciousAlert(alertId: String) {
        val app = getApplication<Application>()
        com.example.util.AuthSecurityManager.acknowledgeSuspiciousAlert(app, alertId)
        refreshSecurityStateForUser()
    }

    fun logoutSession(clearBiometrics: Boolean = false, onLoggedOut: () -> Unit = {}) {
        val app = getApplication<Application>()
        val exitingUser = currentUser.value
        val activeSessId = DeviceSessionStore.getActiveSessionId(app)
        if (activeSessId.isNotBlank()) {
            com.example.util.AuthSecurityManager.revokeSessionById(app, activeSessId, "USER_LOGOUT")
        }
        if (exitingUser != null) {
            com.example.util.AuthSecurityManager.recordLoginActivity(
                context = app,
                userCode = exitingUser.userCode,
                username = exitingUser.username,
                fullName = exitingUser.fullName,
                eventType = "LOGOUT",
                method = "MANUAL_LOGOUT",
                success = true,
                detail = "تسجيل خروج آمن وإبطال معرف الجلسة (${activeSessId.take(12)})"
            )
        }
        twoFactorPendingUser.value = null
        activeSessionOverride.value = null
        activeSessionUserId.value = null
        isSessionAuthenticated.value = false
        DeviceSessionStore.clearActiveSession(app)
        com.example.util.BiometricAuthManager.clearRecentAuthentication(app)
        stopCloudRealtimeSync()
        if (clearBiometrics) {
            com.example.util.BiometricPreferenceManager.clearBinding(app)
        }
        refreshSecurityStateForUser(null)
        viewModelScope.launch {
            repository.getDao().setActiveSessionUser(-1L)
            onLoggedOut()
        }
    }

    fun addFactoryItem(
        name: String,
        category: String,
        unit: String,
        guardKey: String = "add_factory_item",
        onSuccess: () -> Unit = {}
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.CREATE,
            actionLabel = "إضافة صنف لمخزن المصنع",
            documentReference = "FI-NEW-${name.trim()}",
            targetCollection = "factory_items",
            payloadSummary = "إضافة مادة $name ($unit)",
            onComplete = { ok -> if (ok) onSuccess() }
        ) { _ ->
            val actor = resolveUserActor("إدارة المصنع")
            val res = repository.addFactoryItem(name, category, unit, actor)
            res.map { itemId ->
                DeletedRecordsRegistry.unregisterDeleted("factory_items", itemId)
                DeletedRecordsRegistry.unregisterDeleted("factory_items", "FI-$itemId")
                DeletedRecordsRegistry.unregisterDeleted("factory_items", name.trim())
                postMessage("تمت إضافة الصنف ($name) إلى منظومة المصنع بنجاح")
                persistAndSyncToServer()
                "FI-$itemId" to itemId
            }
        }
    }

    fun updateFactoryItem(
        item: FactoryItemEntity,
        newName: String,
        newCategory: String,
        newUnit: String,
        guardKey: String = "update_factory_item_${item.id}",
        onSuccess: () -> Unit = {}
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "تعديل بيانات المادة الخام",
            documentReference = "FI-${item.id}",
            targetCollection = "factory_items",
            payloadSummary = "تعديل المادة $newName ($newUnit)",
            onComplete = { ok -> if (ok) onSuccess() }
        ) { _ ->
            val categoryChanged = item.category.trim() != newCategory.trim()
            if (categoryChanged && !currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("تعديل نوع الصنف")) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("عفواً، تعديل نوع الصنف يتطلب صلاحية (تعديل نوع الصنف)")
                )
            }
            val actor = resolveUserActor("إدارة المشتريات والمصنع")
            val res = repository.updateFactoryItem(item.id, newName, newCategory, newUnit, actor)
            res.map {
                DeletedRecordsRegistry.unregisterDeleted("factory_items", item.id)
                DeletedRecordsRegistry.unregisterDeleted("factory_items", "FI-${item.id}")
                DeletedRecordsRegistry.unregisterDeleted("factory_items", newName.trim())
                postMessage("تم تعديل بيانات المادة الخام ($newName) بنجاح ومزامنتها")
                persistAndSyncToServer()
                "FI-${item.id}" to Unit
            }
        }
    }

    fun renameFactoryItemCategory(
        oldCategory: String,
        newCategory: String,
        guardKey: String = "rename_factory_cat",
        onSuccess: () -> Unit = {}
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "تعديل اسم نوع الصنف",
            documentReference = "FI-CAT-${oldCategory.trim()}",
            targetCollection = "factory_items",
            payloadSummary = "تغيير فئة $oldCategory إلى $newCategory",
            onComplete = { ok -> if (ok) onSuccess() }
        ) { _ ->
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("تعديل نوع الصنف")) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("عفواً، هذه العملية تتطلب صلاحية (تعديل نوع الصنف)")
                )
            }
            val actor = resolveUserActor("إدارة الأصناف")
            val res = repository.renameFactoryItemCategory(oldCategory, newCategory, actor)
            res.map {
                postMessage("تم تعديل اسم نوع الصنف من ($oldCategory) إلى ($newCategory) بنجاح")
                persistAndSyncToServer()
                "FI-CAT-${newCategory.trim()}" to Unit
            }
        }
    }

    fun deleteFactoryItem(
        item: FactoryItemEntity,
        guardKey: String = "delete_factory_item_${item.id}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DELETE,
            actionLabel = "حذف المادة الخام / الصنف",
            documentReference = "FI-${item.id}",
            targetCollection = "factory_items",
            payloadSummary = "حذف ${item.name}",
            onComplete = onComplete
        ) { _ ->
            val actor = resolveUserActor("إدارة المصنع")
            val res = repository.deleteFactoryItem(item.id, item.name, actor)
            res.map {
                com.example.data.local.DeletedRecordsRegistry.registerDeletedWithAliases(
                    collection = "factory_items",
                    aliases = listOfNotNull(item.id.toString(), "FI-${item.id}", item.name.trim().takeIf { it.isNotBlank() }),
                    primaryEntityId = item.id.toString(),
                    primaryDocId = "FI-${item.id}",
                    actor = actor
                )
                cloudErpSyncRepo.deleteEntityFromCloud(serverClusterCode.value, "factory_items", "FI-${item.id}")
                postMessage("تم حذف المادة الخام (${item.name}) من النظام والسحابة بنجاح")
                persistAndSyncToServer()
                "FI-DEL-${item.id}" to Unit
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

    fun saveFormulation(
        formulation: FormulationEntity,
        guardKey: String = "save_formulation",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = if (formulation.id > 0L) com.example.util.IdempotencyOperationType.UPDATE else com.example.util.IdempotencyOperationType.SAVE,
            actionLabel = "حفظ تركيبة دهانات بالمختبر",
            documentReference = "FRM-${formulation.formulaNo}",
            targetCollection = "formulations",
            payloadSummary = "حفظ تركيبة ${formulation.productName}",
            onComplete = onComplete
        ) { _ ->
            val actor = resolveUserActor("مهندس المختبر")
            val res = repository.saveFormulation(formulation, actor)
            res.map { savedId ->
                val entityToSync = formulation.copy(id = savedId)
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        cloudErpSyncRepo.syncSingleFormulationToCloud(serverClusterCode.value, entityToSync)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Cloud formulation sync warning: ${e.message}")
                    }
                }
                postMessage("تم حفظ ومزامنة التركيبة (${formulation.productName}) على السحابة | PVC: ${formulation.calculatedPvc}%")
                "FRM-$savedId" to savedId
            }
        }
    }

    fun deleteFormulation(
        formulation: FormulationEntity,
        guardKey: String = "delete_formulation_${formulation.id}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DELETE,
            actionLabel = "حذف تركيبة من المختبر",
            documentReference = "FRM-${formulation.id}",
            targetCollection = "formulations",
            payloadSummary = "حذف تركيبة ${formulation.formulaNo}",
            onComplete = onComplete
        ) { _ ->
            val actor = resolveUserActor("مهندس المختبر")
            val res = repository.deleteFormulation(formulation.id, actor)
            res.map {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        cloudErpSyncRepo.deleteFormulationFromCloud(serverClusterCode.value, formulation.id, formulation.formulaNo)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Cloud formulation delete warning: ${e.message}")
                    }
                }
                postMessage("تم حذف التركيبة المعملية #${formulation.formulaNo} بنجاح من السجل والسحابة")
                "FRM-DEL-${formulation.id}" to Unit
            }
        }
    }

    fun logProductionBatch(
        batch: ProductionBatchEntity,
        guardKey: String = "log_production_batch",
        onCompleted: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.SAVE,
            actionLabel = "تسجيل تشغيلة إنتاج بالمصنع",
            documentReference = "BATCH-${batch.batchNumber}",
            targetCollection = "production_batches",
            payloadSummary = "تسجيل تشغيلة #${batch.batchNumber}",
            onComplete = onCompleted
        ) { _ ->
            val actor = resolveUserActor("مهندس الإنتاج")
            val res = repository.saveProductionBatch(batch, actor)
            res.map { savedId ->
                val entityToSync = batch.copy(id = savedId)
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        cloudErpSyncRepo.syncSingleProductionBatchToCloud(serverClusterCode.value, entityToSync)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Cloud batch sync warning: ${e.message}")
                    }
                }
                postMessage("تم تسجيل تشغيلة الإنتاج #${batch.batchNumber} بنجاح وحفظها في Firestore | الحالة: ${batch.qualityStatus}")
                "BATCH-$savedId" to savedId
            }
        }
    }

    fun deleteProductionBatch(
        batch: ProductionBatchEntity,
        guardKey: String = "delete_production_batch_${batch.id}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DELETE,
            actionLabel = "حذف تشغيلة إنتاج من المصنع",
            documentReference = "BATCH-${batch.id}",
            targetCollection = "production_batches",
            payloadSummary = "حذف تشغيلة #${batch.batchNumber}",
            onComplete = onComplete
        ) { _ ->
            val actor = resolveUserActor("مهندس الإنتاج")
            val res = repository.deleteProductionBatch(batch.id, batch.batchNumber, actor)
            res.map {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        cloudErpSyncRepo.deleteProductionBatchFromCloud(serverClusterCode.value, batch.id, batch.batchNumber)
                    } catch (e: Exception) {
                        Log.w("PmpViewModel", "Cloud batch delete warning: ${e.message}")
                    }
                }
                postMessage("تم حذف تشغيلة الإنتاج #${batch.batchNumber} من السجل والسحابة")
                "BATCH-DEL-${batch.id}" to Unit
            }
        }
    }

    fun saveChemicalSafety(
        item: ChemicalSafetyEntity,
        guardKey: String = "save_chemical_safety",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.SAVE,
            actionLabel = "حفظ بطاقة السلامة الكيميائية",
            documentReference = "MSDS-${item.chemicalName}",
            targetCollection = "chemical_safety",
            payloadSummary = "حفظ بطاقة ${item.chemicalName}",
            requiresOnline = false,
            requiresServerClaim = isConnectedToCloud(),
            onComplete = onComplete
        ) { _ ->
            val actor = resolveUserActor("مسؤول السلامة والجودة")
            val res = repository.saveChemicalSafety(item, actor)
            res.map { savedId ->
                postMessage("تم تحديث بطاقة السلامة الكيميائية (${item.chemicalName}) بنجاح")
                "MSDS-$savedId" to savedId
            }
        }
    }

    fun deleteChemicalSafety(
        item: ChemicalSafetyEntity,
        guardKey: String = "delete_chemical_safety_${item.id}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DELETE,
            actionLabel = "حذف بطاقة السلامة الكيميائية",
            documentReference = "MSDS-${item.id}",
            targetCollection = "chemical_safety",
            payloadSummary = "حذف بطاقة ${item.chemicalName}",
            requiresOnline = false,
            requiresServerClaim = isConnectedToCloud(),
            onComplete = onComplete
        ) { _ ->
            val actor = resolveUserActor("مسؤول السلامة والجودة")
            val res = repository.deleteChemicalSafety(item.id, actor)
            res.map {
                postMessage("تم حذف بطاقة السلامة (${item.chemicalName}) بنجاح")
                "MSDS-DEL-${item.id}" to Unit
            }
        }
    }

    fun createProductionOrderFromFormula(
        formulaId: Long,
        batchMultiplier: Double = 1.0,
        guardKey: String = "create_prod_order_formula_$formulaId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.CREATE,
            actionLabel = "إنشاء أمر تشغيل مصنعي من التركيبة",
            documentReference = "ORD-FRM-$formulaId",
            targetCollection = "factory_moves",
            payloadSummary = "أمر تشغيل لتركيبة #$formulaId (x$batchMultiplier)",
            requiresOnline = false,
            requiresServerClaim = isConnectedToCloud(),
            onComplete = onComplete
        ) { _ ->
            val actor = resolveUserActor("مدير الإنتاج")
            val res = repository.createFactoryOrderFromFormulation(formulaId, batchMultiplier, actor)
            res.map { moveId ->
                postMessage("تم بنجاح إنشاء أمر التشغيل المصنعي وتوريد الخامات رقم #$moveId وحجز كميات الإنتاج")
                persistAndSyncToServer()
                "ORD-FRM-$moveId" to moveId
            }
        }
    }

    fun addPubChemCompoundToFactoryCatalog(
        compound: com.example.util.PubChemService.PubChemCompound,
        guardKey: String = "add_pubchem_${compound.cid}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.CREATE,
            actionLabel = "إضافة مركب كيميائي لكتالوج المصنع",
            documentReference = "PUBCHEM-${compound.cid}",
            targetCollection = "factory_items",
            payloadSummary = "إضافة ${compound.name} من PubChem",
            requiresOnline = false,
            requiresServerClaim = isConnectedToCloud(),
            onComplete = onComplete
        ) { _ ->
            val actor = resolveUserActor("مهندس المختبر والمصنع")
            val existing = factoryItems.value.find { it.name.trim().equals(compound.name.trim(), ignoreCase = true) }
            if (existing != null) {
                postMessage("المادة (${compound.name}) مضافة مسبقاً في كتالوج خامات المصنع")
                return@launchGuardedTransaction Result.success("FI-${existing.id}" to existing.id)
            }
            val res = repository.addFactoryItem(
                name = compound.name,
                unit = "كجم",
                category = "raw",
                actor = actor
            )
            res.map { itemId ->
                postMessage("تمت إضافة المادة (${compound.name}) كخامة معتمدة في المصنع بنجاح")
                persistAndSyncToServer()
                "FI-$itemId" to itemId
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

    // =========================================================================
    // ENTERPRISE ERP WORKFLOW OPERATIONS (Procurement -> GRN -> BOM -> QC -> FG)
    // =========================================================================

    fun updatePackagingRuleAsAdmin(weightGrams: Int, newCansPerCarton: Int) {
        val res = com.example.util.ErpLifecycleEngine.updatePackagingRuleAsAdmin(
            context = getApplication(),
            actor = currentUser.value,
            weightGrams = weightGrams,
            newCansPerCarton = newCansPerCarton
        )
        res.onSuccess { updatedList ->
            _packagingRules.value = updatedList
            postMessage("تم تحديث قاعدة التعبئة لوزن $weightGrams جم إلى $newCansPerCarton علبة/كرتونة بنجاح")
        }.onFailure { err ->
            postMessage(err.message ?: "تعذر تعديل قواعد التعبئة", isError = true)
        }
    }

    /**
     * تسجيل أمر شراء معتمد (PO) دون زيادة رصيد المخزن الفعلي قبل الاستلام والفحص (Section 4)
     */
    fun createEnterprisePurchaseOrder(
        materialId: Long,
        materialName: String,
        orderedQty: Double,
        unit: String,
        unitPrice: Double,
        currency: String = "EGP",
        additionalExpenses: Double = 0.0,
        supplierName: String,
        expectedDeliveryDate: String = "",
        notes: String = "",
        guardKey: String = "create_enterprise_po",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (orderedQty <= 0.0) {
            postMessage("يرجى إدخال كمية مطلوبة أكبر من صفر", isError = true)
            onComplete?.invoke(false)
            return
        }
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.CREATE,
            actionLabel = "إصدار أمر شراء خامات",
            documentReference = "PO-$materialId-${orderedQty.toInt()}",
            targetCollection = "purchase_orders",
            payloadSummary = "أمر شراء $orderedQty $unit من $materialName",
            requiresOnline = false,
            requiresServerClaim = isConnectedToCloud(),
            onComplete = onComplete
        ) { _ ->
            val actor = resolveUserActor("مسؤول المشتريات")
            val poNo = "PO-${System.currentTimeMillis().toString().takeLast(6)}"
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val po = com.example.util.PurchaseOrderRecord(
                poNumber = poNo,
                orderDate = dateStr,
                supplierName = supplierName.ifBlank { "مورد معتمد" },
                materialId = materialId,
                materialCode = "RAW-$materialId",
                materialName = materialName,
                orderedQty = orderedQty,
                unit = unit.ifBlank { "كجم" },
                unitPrice = unitPrice.coerceAtLeast(0.0),
                currency = currency,
                additionalExpenses = additionalExpenses.coerceAtLeast(0.0),
                expectedDeliveryDate = expectedDeliveryDate.ifBlank { dateStr.substringBefore(" ") },
                createdBy = actor,
                approvalStatus = "معتمد - بانتظار الاستلام",
                approvedBy = actor,
                notes = notes
            )
            _purchaseOrders.value = com.example.util.ErpLifecycleEngine.savePurchaseOrder(getApplication(), po)

            val totalCost = po.totalCost
            if (totalCost > 0.0) {
                val targetCashbox = if (currency.equals("EGP", ignoreCase = true)) "خزنة مصر" else "خزنة السودان"
                repository.recordMovement(
                    cashbox = targetCashbox,
                    currency = currency,
                    direction = "منصرف",
                    amount = totalCost,
                    description = "أمر شراء خامات رقم $poNo: $orderedQty ${po.unit} من $materialName (المورد: ${po.supplierName})",
                    reference = poNo,
                    user = actor
                )
            }
            repository.recordFactoryMove(
                kind = "RAW_PURCHASE",
                itemId = materialId,
                itemName = materialName,
                qty = orderedQty,
                note = "أمر شراء رقم $poNo (بانتظار الاستلام والفحص) - $notes",
                actor = actor,
                counterparty = po.supplierName
            )
            postMessage("تم إصدار أمر الشراء رقم $poNo بنجاح. لن يزاد رصيد المخزن إلا بعد الاستلام والفحص والاعتماد.")
            persistAndSyncToServer()
            Result.success(poNo to po)
        }
    }

    /**
     * استلام ومطابقة شحنة المواد الخام (GRN) بواسطة أمين المخزن وإدخال الكمية المقبولة فقط للمخزون بعد الاعتماد (Section 5)
     */
    fun receiveAndMatchPurchaseOrder(
        po: com.example.util.PurchaseOrderRecord,
        actualReceivedQty: Double,
        acceptedQty: Double,
        rejectedQty: Double,
        discrepancyReason: String,
        notes: String = "",
        autoApproveIfManagerOrNoDiscrepancy: Boolean = true,
        guardKey: String = "receive_match_po_${po.poNumber}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.SUPPLY_RAW,
            actionLabel = "استلام ومطابقة شحنة المشتريات",
            documentReference = "GRN-${po.poNumber}",
            targetCollection = "goods_receipts",
            payloadSummary = "استلام ${po.poNumber} مقبول $acceptedQty",
            requiresOnline = false,
            requiresServerClaim = isConnectedToCloud(),
            onComplete = onComplete
        ) { _ ->
            val storekeeper = resolveUserActor("أمين مخزن الخام")
            val grnResult = runCatching {
                com.example.util.ErpLifecycleEngine.evaluateGoodsReceipt(
                    po = po,
                    storekeeperName = storekeeper,
                    actualReceivedQty = actualReceivedQty,
                    acceptedQty = acceptedQty,
                    rejectedQty = rejectedQty,
                    discrepancyReason = discrepancyReason,
                    notes = notes
                )
            }
            if (grnResult.isFailure) {
                return@launchGuardedTransaction Result.failure(
                    grnResult.exceptionOrNull() ?: IllegalStateException("خطأ في بيانات مطابقة الشحنة")
                )
            }
            var grn = grnResult.getOrThrow()
            val canApproveNow = !grn.hasDiscrepancy ||
                (autoApproveIfManagerOrNoDiscrepancy && currentUser.value.isSystemManager())

            if (canApproveNow && grn.acceptedQty > 0.0) {
                if (com.example.util.ErpLifecycleEngine.markAndCheckIdempotencyKey(getApplication(), grn.idempotencyKey)) {
                    repository.recordFactoryMove(
                        kind = "RAW_RECEIPT",
                        itemId = grn.materialId,
                        itemName = grn.materialName,
                        qty = grn.acceptedQty,
                        batch = grn.grnNumber,
                        note = "استلام معتمد بموجب ${grn.grnNumber} لأمر الشراء ${po.poNumber} (مستلم: $actualReceivedQty، مقبول: $acceptedQty، مرفوض: $rejectedQty)",
                        actor = storekeeper,
                        counterparty = po.supplierName
                    )
                    grn = grn.copy(
                        status = com.example.util.GoodsReceiptStatus.COMPLETED,
                        approvedByManager = resolveUserActor("المدير"),
                        stockPosted = true
                    )
                    val newReceivedTotal = po.receivedTotalQty + actualReceivedQty
                    val newAcceptedTotal = po.acceptedTotalQty + acceptedQty
                    val updatedPoStatus = if (newReceivedTotal >= po.orderedQty) "مكتمل" else "استلام جزئي"
                    _purchaseOrders.value = com.example.util.ErpLifecycleEngine.savePurchaseOrder(
                        getApplication(),
                        po.copy(
                            receivedTotalQty = newReceivedTotal,
                            acceptedTotalQty = newAcceptedTotal,
                            approvalStatus = updatedPoStatus
                        )
                    )
                }
            }
            _goodsReceipts.value = com.example.util.ErpLifecycleEngine.saveGoodsReceipt(getApplication(), grn)
            val statusMsg = if (grn.stockPosted) {
                "تم اعتماد تقرير الاستلام ${grn.grnNumber} وإضافة الكمية المقبولة فقط (${grn.acceptedQty} ${grn.unit}) إلى رصيد مخزن المواد الخام"
            } else {
                "تم تسجيل تقرير الاستلام ${grn.grnNumber} بحالة (${grn.status.arabicLabel}) ورفعه للمسؤول للاعتماد بسبب وجود فرق"
            }
            postMessage(statusMsg)
            persistAndSyncToServer()
            Result.success(grn.grnNumber to grn)
        }
    }

    /**
     * اعتماد تقرير استلام يحتوي على فروقات بواسطة المدير أو Admin وإدخال الكمية المقبولة للمخزون
     */
    fun approveDiscrepantGoodsReceipt(
        grn: com.example.util.GoodsReceiptNoteRecord,
        guardKey: String = "approve_grn_${grn.grnNumber}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (!currentUser.value.isSystemManager()) {
            postMessage("اعتماد فروقات الاستلام متاح للمدير أو Admin فقط", isError = true)
            onComplete?.invoke(false)
            return
        }
        if (grn.stockPosted) {
            postMessage("تم ترحيل هذا الاستلام إلى المخزون مسبقاً", isError = true)
            onComplete?.invoke(false)
            return
        }
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "اعتماد فروقات استلام الخامات",
            documentReference = grn.grnNumber,
            targetCollection = "goods_receipts",
            payloadSummary = "اعتماد GRN ${grn.grnNumber}",
            requiresOnline = false,
            requiresServerClaim = isConnectedToCloud(),
            onComplete = onComplete
        ) { _ ->
            if (!com.example.util.ErpLifecycleEngine.markAndCheckIdempotencyKey(getApplication(), grn.idempotencyKey)) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("تم اعتماد وترحيل هذا المستند مسبقاً (منع التكرار)")
                )
            }
            val approver = resolveUserActor("المدير")
            if (grn.acceptedQty > 0.0) {
                repository.recordFactoryMove(
                    kind = "RAW_RECEIPT",
                    itemId = grn.materialId,
                    itemName = grn.materialName,
                    qty = grn.acceptedQty,
                    batch = grn.grnNumber,
                    note = "اعتماد فروقات استلام ${grn.grnNumber} لأمر شراء ${grn.poNumber} (الكمية المقبولة: ${grn.acceptedQty}) - السبب: ${grn.discrepancyReason}",
                    actor = approver,
                    counterparty = grn.poNumber
                )
            }
            val updatedGrn = grn.copy(
                status = com.example.util.GoodsReceiptStatus.COMPLETED,
                approvedByManager = approver,
                stockPosted = true
            )
            _goodsReceipts.value = com.example.util.ErpLifecycleEngine.saveGoodsReceipt(getApplication(), updatedGrn)
            val matchedPo = _purchaseOrders.value.find { it.poNumber == grn.poNumber }
            if (matchedPo != null) {
                val newReceived = matchedPo.receivedTotalQty + grn.actualReceivedQty
                val newAccepted = matchedPo.acceptedTotalQty + grn.acceptedQty
                _purchaseOrders.value = com.example.util.ErpLifecycleEngine.savePurchaseOrder(
                    getApplication(),
                    matchedPo.copy(
                        receivedTotalQty = newReceived,
                        acceptedTotalQty = newAccepted,
                        approvalStatus = if (newReceived >= matchedPo.orderedQty) "مكتمل" else "استلام جزئي"
                    )
                )
            }
            postMessage("تم اعتماد تقرير الاستلام ${grn.grnNumber} وإضافة ${grn.acceptedQty} ${grn.unit} للمخزن المتاح")
            persistAndSyncToServer()
            Result.success(grn.grnNumber to updatedGrn)
        }
    }

    /**
     * إنشاء أمر إنتاج معتمد مرتبط بتركيبة BOM مع حساب الخامات المطلوبة تلقائياً (Section 7)
     */
    fun createEnterpriseProductionOrder(
        formula: FormulationEntity?,
        paintColor: String,
        packageWeightGrams: Int,
        targetCartons: Int,
        machineName: String,
        productionEngineer: String,
        notes: String = "",
        guardKey: String = "create_enterprise_prod_order",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (targetCartons <= 0) {
            postMessage("يرجى إدخال عدد كراتين مستهدف أكبر من صفر", isError = true)
            onComplete?.invoke(false)
            return
        }
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.CREATE,
            actionLabel = "إنشاء أمر إنتاج معتمد",
            documentReference = "ORD-ENT-$paintColor-$packageWeightGrams-$targetCartons",
            targetCollection = "production_orders",
            payloadSummary = "أمر إنتاج $targetCartons كرتونة $paintColor",
            requiresOnline = false,
            requiresServerClaim = isConnectedToCloud(),
            onComplete = onComplete
        ) { _ ->
            val pkgRule = com.example.util.ErpLifecycleEngine.resolvePackagingRule(
                packageWeightGrams.toString(),
                _packagingRules.value
            )
            val expectedCans = pkgRule.calculateExpectedCans(targetCartons)
            val targetWeightKg = pkgRule.netPaintWeightKgForCans(expectedCans)
            val bomLines = if (formula != null) {
                com.example.util.ErpLifecycleEngine.calculateTheoreticalBomRequirements(formula, targetWeightKg)
            } else {
                emptyList()
            }
            val orderNo = "PO-PRD-${System.currentTimeMillis().toString().takeLast(6)}"
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val order = com.example.util.ProductionOrderRecord(
                orderNumber = orderNo,
                createdDate = dateStr,
                executionDate = dateStr.substringBefore(" "),
                productType = formula?.paintType ?: "دهان مائي",
                formulaId = formula?.id,
                formulaNo = formula?.formulaNo ?: "STD-BOM",
                formulaName = formula?.productName ?: "بوهية $paintColor $packageWeightGrams جم",
                paintColor = paintColor,
                packageWeightGrams = pkgRule.weightGrams,
                targetWeightKg = targetWeightKg,
                targetCartons = targetCartons,
                machineName = machineName,
                productionEngineer = productionEngineer,
                status = com.example.util.ProductionOrderStatus.APPROVED,
                approvedBy = resolveUserActor("المدير"),
                bomRequirements = bomLines,
                notes = notes
            )
            _productionOrders.value = com.example.util.ErpLifecycleEngine.saveProductionOrder(getApplication(), order)
            postMessage("تم إصدار واعتماد أمر الإنتاج $orderNo ($targetCartons كرتونة = $expectedCans علبة، الوزن الصافي: $targetWeightKg كجم)")
            Result.success(orderNo to order)
        }
    }

    /**
     * صرف المواد الخام لأمر إنتاج معتمد وتأكيد استلام مهندس الإنتاج مع منع الخصم المكرر (Section 8)
     */
    fun issueAndConfirmRawMaterialsForOrder(
        order: com.example.util.ProductionOrderRecord,
        guardKey: String = "issue_raw_order_${order.orderNumber}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DISPATCH_GOODS,
            actionLabel = "صرف المواد الخام لأمر الإنتاج",
            documentReference = "ISSUE-${order.orderNumber}",
            targetCollection = "production_orders",
            payloadSummary = "صرف خامات لأمر ${order.orderNumber}",
            requiresOnline = false,
            requiresServerClaim = isConnectedToCloud(),
            onComplete = onComplete
        ) { _ ->
            val idempotencyKey = "ISSUE-${order.orderNumber}"
            if (!com.example.util.ErpLifecycleEngine.markAndCheckIdempotencyKey(getApplication(), idempotencyKey)) {
                return@launchGuardedTransaction Result.failure(
                    IllegalStateException("تم صرف المواد الخام لأمر الإنتاج ${order.orderNumber} مسبقاً (منع الخصم المكرر)")
                )
            }
            val storekeeper = resolveUserActor("أمين مخزن الخام")
            val rawItem = factoryItems.value.firstOrNull {
                it.category.contains("raw", ignoreCase = true) || it.category.contains("خام", ignoreCase = true)
            } ?: factoryItems.value.firstOrNull()
            val itemId = rawItem?.id ?: 1L
            val itemName = rawItem?.name ?: order.formulaName

            repository.recordFactoryMove(
                kind = "ISSUE_PRODUCTION",
                itemId = itemId,
                itemName = itemName,
                qty = order.targetWeightKg,
                batch = order.orderNumber,
                note = "مستند صرف خامات معتمد لأمر الإنتاج ${order.orderNumber} (استلام: ${order.productionEngineer})",
                actor = storekeeper,
                counterparty = order.productionEngineer
            )
            val updated = order.copy(
                status = com.example.util.ProductionOrderStatus.ENGINEER_RECEIVED,
                issueVoucherNo = "MIV-${order.orderNumber.takeLast(6)}",
                engineerReceiptConfirmed = true,
                engineerReceiptTimestamp = System.currentTimeMillis()
            )
            _productionOrders.value = com.example.util.ErpLifecycleEngine.saveProductionOrder(getApplication(), updated)
            postMessage("تم صرف الخامات (${order.targetWeightKg} كجم) وتأكيد استلام مهندس الإنتاج لأمر ${order.orderNumber}")
            persistAndSyncToServer()
            Result.success(updated.issueVoucherNo to updated)
        }
    }

    // --- Phase 3 Room + Ledger Actions (Procurement -> Raw Materials, BOM -> Production -> QC Gate -> Finished Goods, Transfers) ---

    fun receivePurchaseToRawWarehouse(
        materialId: Long,
        materialName: String,
        unit: String,
        supplierName: String,
        batchNumber: String,
        expiryDate: String,
        orderedQty: Double,
        receivedQty: Double,
        acceptedQty: Double,
        rejectedQty: Double,
        unitCost: Double,
        currency: String,
        discrepancyReason: String,
        cashboxAmount: Double = 0.0,
        notes: String = "",
        guardKey: String = "phase3_receive_raw_grn",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.SUPPLY_RAW,
            actionLabel = "استلام وفحص شحنة المشتريات",
            documentReference = "GRN-$materialId-$batchNumber",
            targetCollection = "purchase_receipts",
            payloadSummary = "استلام $acceptedQty $unit من $materialName ($supplierName)",
            onComplete = onComplete
        ) { _ ->
            val coordinator = repository.phase3Engine ?: return@launchGuardedTransaction Result.failure(
                IllegalStateException("محرك الاستلام غير مهيأ حالياً")
            )
            val actor = resolveUserActor("أمين مخزن الخام")
            val result = coordinator.receivePurchaseToRawMaterials(
                com.example.domain.inventory.PurchaseReceiptInput(
                    supplierName = supplierName,
                    materialId = materialId,
                    materialName = materialName,
                    unit = unit,
                    batchNumber = batchNumber,
                    expiryDate = expiryDate,
                    orderedQty = orderedQty,
                    receivedQty = receivedQty,
                    acceptedQty = acceptedQty,
                    rejectedQty = rejectedQty,
                    unitCost = unitCost,
                    currency = currency,
                    discrepancyReason = discrepancyReason,
                    notes = notes,
                    performedBy = actor,
                    approver = currentUser.value
                )
            )
            result.map { receipt ->
                if (cashboxAmount > 0.0) {
                    val linkedBox = resolveCashboxForCurrency(currency)
                    repository.recordMovement(
                        cashbox = linkedBox,
                        currency = currency,
                        direction = "منصرف",
                        amount = cashboxAmount,
                        description = "فاتورة واستلام مشتريات ${receipt.grnNo} (${receipt.materialName}) - المورد: ${receipt.supplierName}",
                        reference = receipt.grnNo,
                        user = actor
                    )
                }
                postMessage("تم اعتماد استلام ${receipt.grnNo}: مقبول ${receipt.acceptedQty} ${receipt.unit} في مخزن المواد الخام" +
                    if (receipt.rejectedQty > 0.0) " • وحجر ${receipt.rejectedQty} مرفوضة" else "")
                persistAndSyncToServer()
                receipt.grnNo to receipt
            }
        }
    }

    fun createBomProductionOrder(
        formula: FormulationEntity?,
        productName: String,
        paintColor: String,
        packageWeightGrams: Int,
        targetCartons: Int,
        machineName: String,
        productionEngineer: String,
        notes: String = "",
        guardKey: String = "phase3_create_bom_order",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.CREATE,
            actionLabel = "إنشاء أمر إنتاج BOM",
            documentReference = "BOM-ORD-$paintColor-$packageWeightGrams-$targetCartons",
            targetCollection = "production_orders",
            payloadSummary = "أمر إنتاج $productName ($targetCartons كرتونة)",
            onComplete = onComplete
        ) { _ ->
            val coordinator = repository.phase3Engine ?: return@launchGuardedTransaction Result.failure(
                IllegalStateException("محرك الإنتاج غير مهيأ حالياً")
            )
            val matchedProduct = products.value.firstOrNull {
                it.name.equals(productName, ignoreCase = true) ||
                    (it.paintColor.equals(paintColor, ignoreCase = true) && it.packageWeight == packageWeightGrams.toString())
            }
            val result = coordinator.createProductionOrder(
                com.example.domain.inventory.ProductionOrderCreateInput(
                    formulaId = formula?.id,
                    productId = matchedProduct?.id,
                    productName = productName,
                    paintColor = paintColor,
                    packageWeightGrams = packageWeightGrams,
                    targetCartons = targetCartons,
                    machineName = machineName,
                    productionEngineer = productionEngineer,
                    approvedBy = resolveUserActor("المدير"),
                    notes = notes
                )
            )
            result.map { order ->
                postMessage("تم إنشاء أمر الإنتاج ${order.orderNo}: ${order.targetCartons} كرتونة = ${order.targetCans} علبة (${order.packageWeightGrams} جم)")
                persistAndSyncToServer()
                order.orderNo to order
            }
        }
    }

    fun issueMaterialsForProductionOrder(
        order: ProductionOrderEntity,
        materialId: Long,
        materialName: String,
        issueQty: Double,
        returnedQty: Double = 0.0,
        wastedQty: Double = 0.0,
        unit: String = "كجم",
        batchNumber: String = "",
        guardKey: String = "phase3_issue_materials_${order.id}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.DISPATCH_GOODS,
            actionLabel = "صرف خامات لأمر الإنتاج",
            documentReference = "ISSUE-${order.orderNo}-$materialId",
            targetCollection = "production_orders",
            payloadSummary = "صرف $issueQty $unit من $materialName لأمر ${order.orderNo}",
            onComplete = onComplete
        ) { _ ->
            val coordinator = repository.phase3Engine ?: return@launchGuardedTransaction Result.failure(
                IllegalStateException("محرك الإنتاج غير مهيأ حالياً")
            )
            val result = coordinator.issueRawMaterialsForOrder(
                orderId = order.id,
                materials = listOf(
                    com.example.domain.inventory.MaterialIssueLineInput(
                        itemId = materialId,
                        itemName = materialName,
                        qty = issueQty,
                        returnedQty = returnedQty,
                        wastedQty = wastedQty,
                        unit = unit,
                        batchNumber = batchNumber
                    )
                ),
                actor = resolveUserActor("أمين مخزن الخام")
            )
            result.map { updated ->
                postMessage("تم صرف الخامات لأمر ${updated.orderNo} من مخزن المواد الخام")
                persistAndSyncToServer()
                updated.orderNo to updated
            }
        }
    }

    fun submitQcInspectionForProductionOrder(
        order: ProductionOrderEntity,
        goodCans: Int,
        rejectedCans: Int,
        reworkCans: Int,
        measuredDensity: Double,
        measuredViscosity: String,
        colorMatchPassed: Boolean,
        wastedRawKg: Double = 0.0,
        forceReject: Boolean = false,
        notes: String = "",
        guardKey: String = "phase3_qc_inspection_${order.id}",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.SAVE,
            actionLabel = "فحص الجودة وترحيل الإنتاج التام",
            documentReference = "QC-${order.orderNo}",
            targetCollection = "qc_inspections",
            payloadSummary = "فحص QC لأمر ${order.orderNo} (مطابق: $goodCans علبة)",
            onComplete = onComplete
        ) { _ ->
            val coordinator = repository.phase3Engine ?: return@launchGuardedTransaction Result.failure(
                IllegalStateException("محرك الجودة غير مهيأ حالياً")
            )
            val result = coordinator.inspectQcAndPostToFinishedGoods(
                com.example.domain.inventory.QcProductionInspectionInput(
                    productionOrderId = order.id,
                    inspectorName = resolveUserActor("مسؤول الجودة"),
                    measuredDensity = measuredDensity,
                    measuredViscosity = measuredViscosity,
                    colorMatchPassed = colorMatchPassed,
                    goodCans = goodCans,
                    rejectedCans = rejectedCans,
                    reworkCans = reworkCans,
                    wastedRawKg = wastedRawKg,
                    forceReject = forceReject,
                    notes = notes
                )
            )
            result.map { (qc, updatedOrder) ->
                if (qc.postedToStock) {
                    postMessage("تم اعتماد الجودة ${qc.qcNo}: دخول ${updatedOrder.producedFullCartons} كرتونة (${updatedOrder.producedLooseCans} علبة) إلى مخزن الإنتاج التام")
                } else {
                    postMessage("بوابة الجودة ${qc.qcNo}: تم رفض/حجر التشغيلة ومنع دخولها لمخزن الإنتاج التام", isError = true)
                }
                persistAndSyncToServer()
                qc.qcNo to (qc to updatedOrder)
            }
        }
    }

    fun createWarehouseTransfer(
        sourceWarehouseId: Long,
        destinationWarehouseId: Long,
        productId: Long,
        productName: String,
        qty: Double,
        batchNumber: String = "",
        notes: String = "",
        guardKey: String = "phase3_create_transfer",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.STOCK_TRANSFER,
            actionLabel = "إنشاء تحويل مخزني",
            documentReference = "TRF-$sourceWarehouseId-$destinationWarehouseId-$productId",
            targetCollection = "warehouse_transfers",
            payloadSummary = "تحويل $qty من $productName",
            onComplete = onComplete
        ) { _ ->
            val eng = repository.inventoryEngine ?: return@launchGuardedTransaction Result.failure(
                IllegalStateException("محرك المخازن غير مهيأ حالياً")
            )
            val res = eng.createTransferDraft(
                com.example.domain.inventory.TransferCreateRequest(
                    sourceWarehouseId = sourceWarehouseId,
                    destinationWarehouseId = destinationWarehouseId,
                    notes = notes,
                    createdBy = resolveUserActor("أمين المخزن"),
                    items = listOf(
                        com.example.domain.inventory.TransferLineInput(
                            itemCategory = InventoryItemCategory.FINISHED_GOOD,
                            itemId = productId,
                            itemName = productName,
                            requestedQty = qty,
                            batchNumber = batchNumber,
                            qcApprovedRequired = true,
                            qcApproved = true
                        )
                    )
                )
            )
            res.map { trf ->
                postMessage("تم إنشاء مسودة التحويل ${trf.transferNo} بانتظار الاعتماد")
                persistAndSyncToServer()
                trf.transferNo to trf
            }
        }
    }

    fun approveWarehouseTransfer(
        transferId: Long,
        guardKey: String = "phase3_approve_transfer_$transferId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "اعتماد التحويل المخزني",
            documentReference = "TRF-APP-$transferId",
            targetCollection = "warehouse_transfers",
            payloadSummary = "اعتماد تحويل #$transferId",
            onComplete = onComplete
        ) { _ ->
            val eng = repository.inventoryEngine ?: return@launchGuardedTransaction Result.failure(
                IllegalStateException("محرك المخازن غير مهيأ حالياً")
            )
            eng.approveTransfer(transferId, currentUser.value, allowCreatorOverride = currentUser.value.isSuperAdminAccount())
                .map {
                    postMessage("تم اعتماد التحويل ${it.transferNo} وحجز الكمية")
                    persistAndSyncToServer()
                    it.transferNo to it
                }
        }
    }

    fun dispatchWarehouseTransfer(
        transferId: Long,
        guardKey: String = "phase3_dispatch_transfer_$transferId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.STOCK_TRANSFER,
            actionLabel = "شحن التحويل المخزني",
            documentReference = "TRF-DISP-$transferId",
            targetCollection = "warehouse_transfers",
            payloadSummary = "شحن تحويل #$transferId",
            onComplete = onComplete
        ) { _ ->
            val eng = repository.inventoryEngine ?: return@launchGuardedTransaction Result.failure(
                IllegalStateException("محرك المخازن غير مهيأ حالياً")
            )
            eng.dispatchTransfer(transferId, resolveUserActor("مسؤول الشحن"))
                .map {
                    postMessage("تم خروج التحويل ${it.transferNo} (في الطريق / In Transit)")
                    persistAndSyncToServer()
                    it.transferNo to it
                }
        }
    }

    fun receiveWarehouseTransfer(
        transferId: Long,
        guardKey: String = "phase3_receive_transfer_$transferId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.SUPPLY_RAW,
            actionLabel = "استلام التحويل المخزني",
            documentReference = "TRF-REC-$transferId",
            targetCollection = "warehouse_transfers",
            payloadSummary = "استلام تحويل #$transferId",
            onComplete = onComplete
        ) { _ ->
            val eng = repository.inventoryEngine ?: return@launchGuardedTransaction Result.failure(
                IllegalStateException("محرك المخازن غير مهيأ حالياً")
            )
            eng.receiveTransfer(transferId, resolveUserActor("أمين مخزن المبيعات"))
                .map {
                    postMessage("تم استلام التحويل ${it.transferNo} في ${it.destinationWarehouseName}")
                    persistAndSyncToServer()
                    it.transferNo to it
                }
        }
    }

    fun reverseInventoryMovement(
        movementId: Long,
        reason: String,
        guardKey: String = "phase3_reverse_move_$movementId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "عكس حركة مخزنية",
            documentReference = "INV-REV-$movementId",
            targetCollection = "inventory_movements",
            payloadSummary = "عكس حركة #$movementId",
            onComplete = onComplete
        ) { _ ->
            val eng = repository.inventoryEngine ?: return@launchGuardedTransaction Result.failure(
                IllegalStateException("محرك المخازن غير مهيأ حالياً")
            )
            eng.reverseMovement(movementId, reason, resolveUserActor("المدير"))
                .map {
                    postMessage("تم إنشاء قيد عكسي ${it.documentNo} مع الاحتفاظ بالسجل الأصلي للتدقيق")
                    persistAndSyncToServer()
                    it.documentNo to it
                }
        }
    }

    // --- Phase 4 Double-Entry General Ledger Actions ---

    fun postManualJournalEntry(
        debitAccountCode: String,
        creditAccountCode: String,
        amount: Double,
        currency: String,
        description: String,
        sourceType: String = "MANUAL_ADJUSTMENT",
        guardKey: String = "phase4_post_journal",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.SAVE,
            actionLabel = "ترحيل قيد يومية محاسبي",
            documentReference = "JRN-$debitAccountCode-$creditAccountCode-${amount.toLong()}",
            targetCollection = "accounting_journals",
            payloadSummary = "قيد يومية $amount $currency: $description",
            onComplete = onComplete
        ) { _ ->
            val accEng = repository.accountingEngine ?: return@launchGuardedTransaction Result.failure(
                IllegalStateException("المحرك المحاسبي غير مهيأ حالياً")
            )
            val actor = resolveUserActor("المحاسب")
            accEng.postJournalEntry(
                com.example.domain.accounting.JournalPostRequest(
                    sourceType = sourceType,
                    description = description,
                    currency = currency,
                    lines = listOf(
                        com.example.domain.accounting.JournalLineInput(
                            accountCode = debitAccountCode,
                            debit = amount,
                            memo = description
                        ),
                        com.example.domain.accounting.JournalLineInput(
                            accountCode = creditAccountCode,
                            credit = amount,
                            memo = description
                        )
                    ),
                    createdBy = actor,
                    approvedBy = actor
                )
            ).map { (entry, pair) ->
                postMessage("تم ترحيل القيد المزدوج المتوازن ${entry.entryNo} (${entry.totalDebit} ${entry.currency})")
                persistAndSyncToServer()
                entry.entryNo to (entry to pair)
            }
        }
    }

    fun reverseAccountingJournal(
        entryId: Long,
        reason: String,
        guardKey: String = "phase4_reverse_journal_$entryId",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchGuardedTransaction(
            guardKey = guardKey,
            operationType = com.example.util.IdempotencyOperationType.UPDATE,
            actionLabel = "عكس قيد يومية محاسبي",
            documentReference = "JRN-REV-$entryId",
            targetCollection = "accounting_journals",
            payloadSummary = "عكس قيد #$entryId: $reason",
            onComplete = onComplete
        ) { _ ->
            val accEng = repository.accountingEngine ?: return@launchGuardedTransaction Result.failure(
                IllegalStateException("المحرك المحاسبي غير مهيأ حالياً")
            )
            accEng.reverseJournalEntry(entryId, reason, resolveUserActor("المدير"))
                .map { (rev, lines) ->
                    postMessage("تم إنشاء القيد العكسي ${rev.entryNo} مع حفظ القيد الأصلي للتدقيق")
                    persistAndSyncToServer()
                    rev.entryNo to (rev to lines)
                }
        }
    }
}



