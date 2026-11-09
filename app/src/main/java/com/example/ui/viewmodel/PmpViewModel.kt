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
        .onEach { _isInitialDataLoading.value = false }
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in movements flow", e)
            _isInitialDataLoading.value = false
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val transfers = repository.getTransfers()
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
        .onEach { _isInitialDataLoading.value = false }
        .catch { e ->
            android.util.Log.e("PmpViewModel", "Error in customers flow", e)
            _isInitialDataLoading.value = false
            emit(emptyList())
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val invoices = repository.getInvoices()
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
                if (savedDeviceId > 0L) {
                    val dao = repository.getDao()
                    var directUser = dao.getUserById(savedDeviceId)
                    if (directUser == null) {
                        DatabaseBackupManager.restoreIfEmpty(application, dao)
                        directUser = dao.getUserById(savedDeviceId)
                            ?: dao.getUserAccountsDirect().firstOrNull { it.id == savedDeviceId }
                    }
                    if (directUser != null && directUser.active && directUser.syncStatus != "PENDING_MANAGER_APPROVAL") {
                        activeSessionOverride.value = directUser
                        activeSessionUserId.value = directUser.id
                        isSessionAuthenticated.value = true
                        _isInitializingSession.value = false
                    } else if (directUser != null && (!directUser.active || directUser.syncStatus == "PENDING_MANAGER_APPROVAL")) {
                        DeviceSessionStore.clearActiveSession(application)
                        activeSessionOverride.value = null
                        activeSessionUserId.value = null
                        isSessionAuthenticated.value = false
                        _isInitializingSession.value = false
                    } else {
                        // Wait briefly for initial seed if DB is still initializing on first launch
                        val seededList = userAccounts.firstOrNull { it.isNotEmpty() }.orEmpty()
                        val matched = seededList.firstOrNull {
                            it.id == savedDeviceId && it.active && it.syncStatus != "PENDING_MANAGER_APPROVAL"
                        }
                        if (matched != null) {
                            activeSessionOverride.value = matched
                            activeSessionUserId.value = matched.id
                            isSessionAuthenticated.value = true
                        } else {
                            DeviceSessionStore.clearActiveSession(application)
                            activeSessionOverride.value = null
                            activeSessionUserId.value = null
                            isSessionAuthenticated.value = false
                        }
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
                    if (signal != null) {
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
                                if (!_isDownloadingUpdate.value) {
                                    _updateUiState.value = com.example.util.UpdateUiState.UP_TO_DATE
                                    _lastUpdateCheckStatus.value = "أنت تستخدم أحدث إصدار من PMP PAINTS."
                                }
                            }
                            refreshBuildIdentityReport()
                        }
                    }
                    checkForAppUpdates(manual = false)
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
                cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao())
                cloudErpSyncRepo.syncAllLocalToCloud(serverClusterCode.value, repository.getDao())
                DatabaseBackupManager.saveLocalSnapshot(application, repository.getDao())
                checkServerHealth()
            } catch (e: Exception) {
                Log.w("PmpViewModel", "Deferred cloud cluster sync: ${e.message}")
            }
        }
        startCloudRealtimeSyncIfAuthenticated()
    }

    fun retryDataLoadAndSync() {
        _dataLoadError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DatabaseBackupManager.restoreIfEmpty(getApplication(), repository.getDao())
                cloudErpSyncRepo.pullAndMergeCloudUsers(serverClusterCode.value, repository.getDao())
                cloudErpSyncRepo.syncAllLocalToCloud(serverClusterCode.value, repository.getDao())
                _isInitialDataLoading.value = false
            } catch (e: Exception) {
                _isInitialDataLoading.value = false
                if (!isConnectedToCloud()) {
                    _dataLoadError.value = "تعذر الاتصال بالشبكة لتحديث البيانات السحابية. يتم عرض البيانات المحفوظة محلياً."
                }
            }
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
                postMessage("تمت تسوية وإقفال المديونية المسجلة على العميل ($customerName) بقيد تسوية معتمد مع الحفاظ على سجل الدفاتر")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر تسوية حساب العميل", isError = true)
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
            if (!currentUser.value.isSystemManager()) {
                postMessage("عفواً، إضافة الخزن الجديدة متاحة لمدير النظام فقط", isError = true)
                return@launch
            }
            val cleanName = name.trim()
            if (cleanName.isBlank()) {
                postMessage("يرجى إدخال اسم الخزنة", isError = true)
                return@launch
            }
            val newId = repository.addCashbox(cleanName, user)
            viewModelScope.launch(Dispatchers.IO) {
                cloudErpSyncRepo.syncSingleCashboxToCloud(serverClusterCode.value, newId, cleanName)
            }
            postMessage("تمت إضافة الخزنة ($cleanName) ومزامنتها بنجاح")
            persistAndSyncToServer()
        }
    }

    fun updateCashbox(id: Long, name: String, user: String = resolveUserActor("المدير")) {
        if (!checkOnlineBeforeTransaction("تعديل اسم الخزنة")) return
        viewModelScope.launch {
            if (!currentUser.value.isSystemManager()) {
                postMessage("عفواً، تعديل الخزن متاح لمدير النظام فقط", isError = true)
                return@launch
            }
            val cleanName = name.trim()
            val res = repository.updateCashbox(id, cleanName, user)
            res.onSuccess {
                viewModelScope.launch(Dispatchers.IO) {
                    cloudErpSyncRepo.syncSingleCashboxToCloud(serverClusterCode.value, id, cleanName)
                }
                postMessage("تم تعديل اسم الخزنة إلى ($cleanName) بنجاح")
                persistAndSyncToServer()
            }.onFailure {
                postMessage(it.message ?: "تعذر تعديل الخزنة", isError = true)
            }
        }
    }

    fun deleteCashbox(id: Long, name: String, user: String = resolveUserActor("المدير")) {
        if (!checkOnlineBeforeTransaction("حذف الخزنة")) return
        viewModelScope.launch {
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                postMessage("عفواً، حذف أو أرشفة الخزن متاح لمدير النظام أو الحسابات المصرح لها فقط", isError = true)
                return@launch
            }
            val cleanName = name.trim()
            val res = repository.deleteCashbox(id, cleanName, user)
            res.onSuccess { hadFinancialHistory ->
                viewModelScope.launch(Dispatchers.IO) {
                    cloudErpSyncRepo.deleteCashboxFromCloud(serverClusterCode.value, id, cleanName)
                    DatabaseBackupManager.saveLocalSnapshot(getApplication(), repository.getDao())
                    cloudErpSyncRepo.syncAllLocalToCloud(serverClusterCode.value, repository.getDao())
                }
                val msg = if (hadFinancialHistory) {
                    "تم إلغاء تفعيل وأرشفة الخزنة ($cleanName) عبر جميع الأجهزة مع الاحتفاظ الكامل بكافة الحركات والسجلات المالية التاريخية المرتبطة بها"
                } else {
                    "تم حذف الخزنة ($cleanName) نهائياً ومزامنة الحذف عبر جميع الأجهزة"
                }
                postMessage(msg)
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
            val prodId = repository.addProduct(name, color, weight, purchasePrice, salePrice, salePriceEgp, minimumStock, user)
            DeletedRecordsRegistry.unregisterDeleted("products", prodId)
            DeletedRecordsRegistry.unregisterDeleted("products", "PROD-$prodId")
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
            postMessage("تم تسجيل حركة المخزون بنجاح ومزامنتها")
            persistAndSyncToServer()
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
        note: String
    ) {
        if (!checkOnlineBeforeTransaction("تحديث كمية المخزون")) return
        viewModelScope.launch {
            val cleanColor = color.trim().ifBlank { "أبيض" }
            val cleanWeight = com.example.util.InventoryWeightManager.normalizeWeightValue(weight).ifBlank { weight.trim() }
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
                        postMessage("يرجى إدخال كمية أكبر من صفر للإضافة", isError = true)
                        return@launch
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
                        postMessage("يرجى إدخال كمية أكبر من صفر للخصم", isError = true)
                        return@launch
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
                        postMessage("لا يمكن أن يكون الرصيد بالسالب", isError = true)
                        return@launch
                    }
                    val currentMoves = repository.getDao().getStockMovesDirect().filter { it.productId == product.id }
                    val currentBalance = currentMoves.sumOf { sm ->
                        val sign = if (sm.kind in listOf("IN", "RETURN", "RETURN_IN", "CANCEL", "وارد", "مرتجع")) 1.0 else -1.0
                        sign * sm.quantity
                    }
                    val delta = quantityInput - currentBalance
                    if (kotlin.math.abs(delta) < 0.0001) {
                        postMessage("الرصيد الفعلي مطابق للرصيد الحالي (${quantityInput.toInt()})")
                        return@launch
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
                    postMessage("تم تحديث رصيد ($cleanColor - $cleanWeight جم) إلى ${quantityInput.toInt()} بنجاح")
                }
            }
            persistAndSyncToServer()
        }
    }

    fun deleteProduct(productId: Long, productName: String) {
        if (!checkOnlineBeforeTransaction("حذف صنف")) return
        viewModelScope.launch {
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("صلاحية الحذف والتصفير")) {
                postMessage("عفواً، حذف الأصناف متاح لمدير النظام أو الحسابات المصرح لها فقط", isError = true)
                return@launch
            }
            val actor = resolveUserActor("المدير")
            val res = repository.deleteProduct(productId, productName, actor)
            res.onSuccess {
                DeletedRecordsRegistry.registerDeleted("products", productId, "PROD-$productId", actor)
                viewModelScope.launch(Dispatchers.IO) {
                    cloudErpSyncRepo.deleteEntityFromCloud(serverClusterCode.value, "products", "PROD-$productId")
                }
                postMessage("تم حذف الصنف ($productName) بنجاح من النظام والسحابة")
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
            persistAndSyncToServer()
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

    // --- User Accounts & Server Sync Actions (Strictly Admin-Only per Section 16) ---
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
            if (!checkOnlineBeforeTransaction("إنشاء حساب موظف جديد")) return@launch
            if (!com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(currentUser.value)) {
                postMessage("عفواً، حساب Admin هو الحساب الوحيد الذي يملك صلاحية إنشاء حسابات الموظفين وتحديد صلاحياتهم.", isError = true)
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
                actor = resolveUserActor("Admin")
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
            if (!checkOnlineBeforeTransaction("تعديل حالة تفعيل الحساب")) return@launch
            if (!com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(currentUser.value)) {
                postMessage("عفواً، تفعيل أو إيقاف حسابات المستخدمين متاح لحساب Admin فقط.", isError = true)
                return@launch
            }
            val res = repository.toggleUserAccountActive(user, resolveUserActor("Admin"))
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
            if (!checkOnlineBeforeTransaction("حذف حساب الموظف نهائياً")) return@launch
            if (!com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(currentUser.value)) {
                postMessage("عفواً، الحذف النهائي لحسابات المستخدمين متاح لحساب Admin فقط.", isError = true)
                return@launch
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
            if (!checkOnlineBeforeTransaction("استرداد حساب الموظف")) return@launch
            if (!com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(currentUser.value)) {
                postMessage("عفواً، استرداد الحسابات متاح لحساب Admin فقط.", isError = true)
                return@launch
            }
            val res = repository.restoreUserAccount(user.id, resolveUserActor("Admin"))
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
            if (!com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(currentUser.value)) {
                postMessage("عفواً، حساب Admin هو الوحيد المخول بتحديد أو تعديل صلاحيات المستخدمين.", isError = true)
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
                actor = resolveUserActor("Admin")
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
            val nextMinor = (nextCode - 13).coerceAtLeast(1)
            val nextName = "v3.$nextMinor"
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
            _isCheckingUpdate.value = false

            val installedCode = updateManager.getEffectiveVersionCode()

            result.onSuccess { updateInfo ->
                if (updateInfo != null && updateInfo.versionCode > installedCode) {
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
        val target = apkFile ?: _downloadedApkFile.value
        if (target == null || !target.exists()) {
            postMessage("يرجى تنزيل التحديث أولاً قبل التثبيت.", isError = true)
            return
        }
        val pendingUpdate = _availableUpdate.value
        val res = updateManager.installApk(context, target, pendingUpdate)
        res.onSuccess {
            updateManager.refreshInstalledVersionState()
            _updateAvailable.value = false
            _availableUpdate.value = null
            _updateUiState.value = com.example.util.UpdateUiState.UP_TO_DATE
            _lastUpdateCheckStatus.value = "تم تحديث التطبيق وتفعيل الإصدار الجديد بنجاح."
            postMessage("تم تجهيز وتثبيت الإصدار الجديد بنجاح.")
        }.onFailure { err ->
            val errMsg = UpdateManager.sanitizeUserFacingErrorMessage(err.message)
            _lastUpdateCheckStatus.value = errMsg
            postMessage(errMsg, isError = true)
        }
    }

    fun canRequestPackageInstalls(apkFile: File? = _downloadedApkFile.value): Boolean {
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
            if (!checkOnlineBeforeTransaction("إضافة صنف لمخزن المصنع")) return@launch
            val actor = resolveUserActor("إدارة المصنع")
            val res = repository.addFactoryItem(name, category, unit, actor)
            res.onSuccess { itemId ->
                DeletedRecordsRegistry.unregisterDeleted("factory_items", itemId)
                DeletedRecordsRegistry.unregisterDeleted("factory_items", "FI-$itemId")
                DeletedRecordsRegistry.unregisterDeleted("factory_items", name.trim())
                postMessage("تمت إضافة الصنف ($name) إلى منظومة المصنع بنجاح")
                persistAndSyncToServer()
                onSuccess()
            }.onFailure {
                postMessage(it.message ?: "تعذر إضافة الصنف", isError = true)
            }
        }
    }

    fun updateFactoryItem(
        item: FactoryItemEntity,
        newName: String,
        newCategory: String,
        newUnit: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            if (!checkOnlineBeforeTransaction("تعديل بيانات المادة الخام")) return@launch
            val categoryChanged = item.category.trim() != newCategory.trim()
            if (categoryChanged && !currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("تعديل نوع الصنف")) {
                postMessage("عفواً، تعديل نوع الصنف يتطلب صلاحية (تعديل نوع الصنف)", isError = true)
                return@launch
            }
            val actor = resolveUserActor("إدارة المشتريات والمصنع")
            val res = repository.updateFactoryItem(item.id, newName, newCategory, newUnit, actor)
            res.onSuccess {
                DeletedRecordsRegistry.unregisterDeleted("factory_items", item.id)
                DeletedRecordsRegistry.unregisterDeleted("factory_items", "FI-${item.id}")
                DeletedRecordsRegistry.unregisterDeleted("factory_items", newName.trim())
                postMessage("تم تعديل بيانات المادة الخام ($newName) بنجاح ومزامنتها")
                persistAndSyncToServer()
                onSuccess()
            }.onFailure {
                postMessage(it.message ?: "تعذر تعديل المادة الخام", isError = true)
            }
        }
    }

    fun renameFactoryItemCategory(
        oldCategory: String,
        newCategory: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            if (!checkOnlineBeforeTransaction("تعديل اسم نوع الصنف")) return@launch
            if (!currentUser.value.isSystemManager() && !currentUser.value.hasModulePermission("تعديل نوع الصنف")) {
                postMessage("عفواً، هذه العملية تتطلب صلاحية (تعديل نوع الصنف)", isError = true)
                return@launch
            }
            val actor = resolveUserActor("إدارة الأصناف")
            val res = repository.renameFactoryItemCategory(oldCategory, newCategory, actor)
            res.onSuccess {
                postMessage("تم تعديل اسم نوع الصنف من ($oldCategory) إلى ($newCategory) بنجاح")
                persistAndSyncToServer()
                onSuccess()
            }.onFailure {
                postMessage(it.message ?: "تعذر تعديل اسم نوع الصنف", isError = true)
            }
        }
    }

    fun deleteFactoryItem(item: FactoryItemEntity) {
        viewModelScope.launch {
            if (!checkOnlineBeforeTransaction("حذف المادة الخام / الصنف")) return@launch
            val actor = resolveUserActor("إدارة المصنع")
            val res = repository.deleteFactoryItem(item.id, item.name, actor)
            res.onSuccess {
                com.example.data.local.DeletedRecordsRegistry.registerDeleted(
                    "factory_items",
                    "FI-${item.id}",
                    item.name,
                    actor
                )
                com.example.data.local.DeletedRecordsRegistry.registerDeleted(
                    "factory_items",
                    item.id.toString(),
                    item.name,
                    actor
                )
                viewModelScope.launch(Dispatchers.IO) {
                    cloudErpSyncRepo.deleteEntityFromCloud(serverClusterCode.value, "factory_items", "FI-${item.id}")
                }
                postMessage("تم حذف المادة الخام (${item.name}) من النظام والسحابة بنجاح")
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
            if (!checkOnlineBeforeTransaction("حفظ تركيبة دهانات بالمختبر")) return@launch
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
            if (!checkOnlineBeforeTransaction("حذف تركيبة من المختبر")) return@launch
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
            if (!checkOnlineBeforeTransaction("تسجيل تشغيلة إنتاج بالمصنع")) {
                onCompleted?.invoke(false)
                return@launch
            }
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
            if (!checkOnlineBeforeTransaction("حذف تشغيلة إنتاج من المصنع")) return@launch
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
        notes: String = ""
    ) {
        if (orderedQty <= 0.0) {
            postMessage("يرجى إدخال كمية مطلوبة أكبر من صفر", isError = true)
            return
        }
        viewModelScope.launch {
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

            // تسجيل الحركة المالية لأمر الشراء في حال وجود تكلفة مدفوعة دون زيادة مخزون الخام قبل الاستلام والفحص
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
            // نسجل حركة RAW_PURCHASE كسجل تعاقدي فقط (والرصيد المتاح يعتمد على RAW_RECEIPT بعد الفحص والاعتماد)
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
        autoApproveIfManagerOrNoDiscrepancy: Boolean = true
    ) {
        viewModelScope.launch {
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
            grnResult.onFailure { err ->
                postMessage(err.message ?: "خطأ في بيانات مطابقة الشحنة", isError = true)
                return@launch
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
        }
    }

    /**
     * اعتماد تقرير استلام يحتوي على فروقات بواسطة المدير أو Admin وإدخال الكمية المقبولة للمخزون
     */
    fun approveDiscrepantGoodsReceipt(grn: com.example.util.GoodsReceiptNoteRecord) {
        if (!currentUser.value.isSystemManager()) {
            postMessage("اعتماد فروقات الاستلام متاح للمدير أو Admin فقط", isError = true)
            return
        }
        if (grn.stockPosted) {
            postMessage("تم ترحيل هذا الاستلام إلى المخزون مسبقاً", isError = true)
            return
        }
        viewModelScope.launch {
            if (!com.example.util.ErpLifecycleEngine.markAndCheckIdempotencyKey(getApplication(), grn.idempotencyKey)) {
                postMessage("تم اعتماد وترحيل هذا المستند مسبقاً (منع التكرار)", isError = true)
                return@launch
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
        notes: String = ""
    ) {
        if (targetCartons <= 0) {
            postMessage("يرجى إدخال عدد كراتين مستهدف أكبر من صفر", isError = true)
            return
        }
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
    }

    /**
     * صرف المواد الخام لأمر إنتاج معتمد وتأكيد استلام مهندس الإنتاج مع منع الخصم المكرر (Section 8)
     */
    fun issueAndConfirmRawMaterialsForOrder(order: com.example.util.ProductionOrderRecord) {
        val idempotencyKey = "ISSUE-${order.orderNumber}"
        if (!com.example.util.ErpLifecycleEngine.markAndCheckIdempotencyKey(getApplication(), idempotencyKey)) {
            postMessage("تم صرف المواد الخام لأمر الإنتاج ${order.orderNumber} مسبقاً (منع الخصم المكرر)", isError = true)
            return
        }
        viewModelScope.launch {
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
        }
    }
}



