package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.util.BiometricAuthManager
import com.example.util.BottomNavPreferences
import com.example.util.UserSessionManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.UserAccountEntity
import com.example.util.AuthErrorHandler
import com.example.ui.components.GlassBadge
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {

    private val viewModel: PmpViewModel by viewModels()
    private val openUpdatesScreenTrigger = kotlinx.coroutines.flow.MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installDefensiveUncaughtExceptionHandler()
        super.onCreate(savedInstanceState)
        handleIncomingUpdateIntent(intent)
        runCatching {
            if (FirebaseApp.getApps(applicationContext).isEmpty()) {
                FirebaseApp.initializeApp(applicationContext)
            }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        setContent {
            val shouldOpenUpdates by openUpdatesScreenTrigger.collectAsState()
            MyApplicationTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    PmpAppContent(
                        viewModel = viewModel,
                        openUpdatesScreenRequest = shouldOpenUpdates,
                        onOpenUpdatesScreenConsumed = { openUpdatesScreenTrigger.value = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingUpdateIntent(intent)
    }

    private fun handleIncomingUpdateIntent(incomingIntent: android.content.Intent?) {
        if (incomingIntent?.getBooleanExtra(com.example.util.UpdateNotificationHelper.EXTRA_OPEN_UPDATES_SCREEN, false) == true) {
            openUpdatesScreenTrigger.value = true
            incomingIntent.removeExtra(com.example.util.UpdateNotificationHelper.EXTRA_OPEN_UPDATES_SCREEN)
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            viewModel.verifyActiveSessionNotExpired()
            viewModel.verifyPostInstallAndRefreshVersion()
        }
    }

    private fun installDefensiveUncaughtExceptionHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (previousHandler?.javaClass?.name?.contains("PmpSafeExceptionHandler") == true) return
        Thread.setDefaultUncaughtExceptionHandler(
            object : Thread.UncaughtExceptionHandler {
                override fun uncaughtException(thread: Thread, throwable: Throwable) {
                    runCatching {
                        android.util.Log.e(
                            "PmpCrashGuard",
                            "Caught unhandled exception on thread ${thread.name}: ${throwable.message}",
                            throwable
                        )
                    }
                    val isMainThread = android.os.Looper.getMainLooper()?.thread === thread
                    if (!isMainThread) {
                        // Prevent transient background sync/network/Firebase worker thread exceptions from killing the app process
                        runCatching {
                            viewModel.postMessage(
                                "تنبيه تقني: تم احتواء خطأ خلفي طارئ دون إيقاف التطبيق (${throwable.javaClass.simpleName})",
                                isError = true
                            )
                        }
                        return
                    }
                    previousHandler?.uncaughtException(thread, throwable)
                }
            }
        )
    }
}

enum class PmpScreen(val route: String, val title: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "الرئيسية", Icons.Default.Dashboard),
    TREASURY("treasury", "الخزن", Icons.Default.AccountBalanceWallet),
    PURCHASES("purchases", "المشتريات", Icons.Default.ShoppingCart),
    SALES("sales", "المبيعات", Icons.Default.ReceiptLong),
    INVENTORY("inventory", "المخازن", Icons.Default.Inventory2),
    FACTORY("factory", "المصنع", Icons.Default.PrecisionManufacturing),
    CHEMISTRY_LAB("chemistry_lab", "المختبر الكيميائي", Icons.Default.Science),
    USERS("users", "المستخدمين", Icons.Default.ManageAccounts),
    POSTER("poster", "الأسعار", Icons.Default.LocalOffer),
    REPORTS("reports", "التقارير", Icons.Default.BarChart),
    SETTINGS("settings", "الإعدادات", Icons.Default.Settings)
}

/**
 * Checks whether the given user is a System Manager with full access to all modules.
 */
fun UserAccountEntity?.isSystemManager(): Boolean {
    if (this == null) return false
    return role.contains("مدير النظام") || username.equals("admin", ignoreCase = true)
}

/**
 * Checks whether the given user is strictly the primary `admin` account.
 * Technical and server-level diagnostic details are visible only to `admin`.
 */
fun UserAccountEntity?.isSuperAdminAccount(): Boolean {
    if (this == null) return false
    return username.equals("admin", ignoreCase = true)
}

/**
 * Parses the comma-separated permissions assigned to this employee by the manager.
 */
fun UserAccountEntity?.parsedPermissionsSet(): Set<String> {
    if (this == null) return emptySet()
    if (isSystemManager()) {
        return setOf(
            "المصنع",
            "المختبر الكيميائي",
            "مواد الإنتاج",
            "التعبئة والتغليف",
            "المخازن",
            "الخزن",
            "المشتريات",
            "التحويلات",
            "المبيعات",
            "تصدير PDF",
            "الأسعار",
            "التقارير",
            "التقارير (كاملة)",
            "تقارير المبيعات",
            "تقارير مبيعات الشهر الحالي",
            "تقارير المواد الخام",
            "تقارير المنتج التام",
            "تقارير حركة المخزون",
            "تقارير حركات الخزن",
            "تقارير المديونيات",
            "المستخدمين",
            "الإعدادات",
            "تعديل نوع الصنف",
            "صلاحية الحذف والتصفير"
        )
    }
    return permissions.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
}

/**
 * Enforces strict permission separation: an employee can only see and open modules
 * explicitly assigned to them by the manager.
 */
fun UserAccountEntity?.hasModulePermission(moduleTitle: String): Boolean {
    if (moduleTitle == "الرئيسية") return true
    if (this == null) return false
    if (isSystemManager()) return true
    val perms = parsedPermissionsSet()
    if (moduleTitle == "التقارير") {
        return perms.contains("التقارير") ||
            perms.contains("التقارير (كاملة)") ||
            perms.any { it.startsWith("تقارير ") }
    }
    return moduleTitle in perms
}

/**
 * Checks whether an employee has permission to view a specific report tab (e.g. Sales, Raw Materials, Inventory).
 */
fun UserAccountEntity?.hasReportTabPermission(tabName: String): Boolean {
    if (this == null) return false
    if (isSystemManager()) return true
    val perms = parsedPermissionsSet()
    if (perms.contains("التقارير (كاملة)")) return true
    val specificKey = when (tabName) {
        "المبيعات", "مبيعات الشهر الحالي" -> "تقارير المبيعات"
        "المديونيات" -> "تقارير المديونيات"
        "حركة المخزون", "المنتج التام" -> "تقارير حركة المخزون"
        "حركات الخزن" -> "تقارير حركات الخزن"
        "تقارير المواد الخام", "المواد الخام في المخزن" -> "تقارير المواد الخام"
        else -> "تقارير $tabName"
    }
    if (perms.contains(specificKey) || perms.contains("تقارير $tabName")) return true
    val hasGranularReportPerms = perms.any { it.startsWith("تقارير ") }
    if (perms.contains("التقارير") && !hasGranularReportPerms) {
        return true
    }
    return false
}

@Composable
fun PmpAppContent(
    viewModel: PmpViewModel,
    openUpdatesScreenRequest: Boolean = false,
    onOpenUpdatesScreenConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
    val coroutineScope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(PmpScreen.DASHBOARD) }
    var treasuryInitialSection by remember { mutableStateOf<String?>(null) }
    var showReorderNavDialog by remember { mutableStateOf(false) }
    var showNavigationMenu by remember { mutableStateOf(false) }
    val uiMessage by viewModel.uiMessage.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val isSessionAuthenticated by viewModel.isSessionAuthenticated.collectAsState()
    val isInitializingSession by viewModel.isInitializingSession.collectAsState()
    val saveableStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()

    var firebaseUser by remember { mutableStateOf(runCatching { Firebase.auth.currentUser }.getOrNull()) }
    val sessionManager = remember { UserSessionManager.getInstance(context) }
    val isAuthTransitioning by sessionManager.isAuthTransitioning.collectAsState()
    val authTransitionMessage by sessionManager.authTransitionMessage.collectAsState()

    DisposableEffect(Unit) {
        sessionManager.startMonitoring()
        val listener: (UserSessionManager.SessionEvent) -> Unit = { event ->
            when (event) {
                is UserSessionManager.SessionEvent.SignedOut -> {
                    firebaseUser = null
                }
                is UserSessionManager.SessionEvent.SignedIn -> {
                    firebaseUser = Firebase.auth.currentUser
                }
                is UserSessionManager.SessionEvent.AccountSwitched -> {
                    firebaseUser = Firebase.auth.currentUser
                }
                is UserSessionManager.SessionEvent.ProfileSynced -> {
                    val activeCode = currentUser?.userCode.orEmpty()
                    if (activeCode.isBlank() || activeCode.equals(event.user.userCode, ignoreCase = true)) {
                        viewModel.switchActiveUser(event.user.id, null)
                    }
                }
            }
        }
        sessionManager.addListener(listener)
        onDispose {
            sessionManager.removeListener(listener)
            sessionManager.stopMonitoring()
        }
    }

    val activeUserId by viewModel.activeSessionUserId.collectAsState()
    val isNetworkOnline by viewModel.isNetworkOnline.collectAsState()
    val updateAvailable by viewModel.updateAvailable.collectAsState()
    val availableUpdate by viewModel.availableUpdate.collectAsState()
    val effectiveVersionName by viewModel.effectiveVersionName.collectAsState()

    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(isSessionAuthenticated) {
        if (isSessionAuthenticated && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                runCatching {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    LaunchedEffect(openUpdatesScreenRequest, isSessionAuthenticated) {
        if (openUpdatesScreenRequest && isSessionAuthenticated) {
            currentScreen = PmpScreen.SETTINGS
            onOpenUpdatesScreenConsumed()
        }
    }

    // Enforce session expiration, idle timeout, and session/device revocation checks
    LaunchedEffect(isSessionAuthenticated, currentScreen) {
        if (isSessionAuthenticated) {
            viewModel.verifyActiveSessionNotExpired()
            while (isSessionAuthenticated) {
                delay(60_000L)
                viewModel.verifyActiveSessionNotExpired()
            }
        }
    }

    // Only de-authenticate if the currently active employee was explicitly deactivated/suspended by the manager
    LaunchedEffect(currentUser, isSessionAuthenticated, activeUserId) {
        val user = currentUser
        if (isSessionAuthenticated && user != null && !user.active && activeUserId != null && user.id == activeUserId) {
            viewModel.logoutSession()
            viewModel.postMessage("تم إيقاف هذا الحساب من قبل الإدارة", isError = true)
        }
    }

    // Filter screens strictly based on the permissions assigned to the logged-in employee
    val allowedScreens = remember(currentUser) {
        PmpScreen.entries.filter { screen ->
            when (screen) {
                PmpScreen.DASHBOARD -> true
                PmpScreen.SETTINGS -> true // Allow all authenticated users to access personal profile, biometrics, and app updates
                PmpScreen.TREASURY -> currentUser.hasModulePermission("الخزن") || currentUser.hasModulePermission("التحويلات")
                PmpScreen.PURCHASES -> currentUser.hasModulePermission("المشتريات") || currentUser.hasModulePermission("مواد الإنتاج") || currentUser.hasModulePermission("المصنع") || currentUser.isSystemManager()
                PmpScreen.FACTORY -> currentUser.hasModulePermission("المصنع") || currentUser.hasModulePermission("مواد الإنتاج") || currentUser.hasModulePermission("التعبئة والتغليف")
                PmpScreen.CHEMISTRY_LAB -> currentUser.hasModulePermission("المختبر الكيميائي") || currentUser.hasModulePermission("المصنع") || currentUser.isSystemManager()
                else -> currentUser.hasModulePermission(screen.title)
            }
        }
    }

    val baseNavScreens = remember(allowedScreens) { allowedScreens.filter { it != PmpScreen.USERS } }
    var orderedNavScreens by remember(currentUser, baseNavScreens) {
        mutableStateOf(BottomNavPreferences.getOrderedScreens(context, currentUser?.userCode, baseNavScreens))
    }

    LaunchedEffect(baseNavScreens) {
        orderedNavScreens = BottomNavPreferences.getOrderedScreens(context, currentUser?.userCode, baseNavScreens)
    }

    // Biometric authentication gate for sensitive financial and inventory screens
    val navigateWithBiometrics: (PmpScreen, () -> Unit) -> Unit = { targetScreen, onProceed ->
        val sensitiveScreens = setOf(PmpScreen.TREASURY, PmpScreen.PURCHASES, PmpScreen.SALES, PmpScreen.INVENTORY, PmpScreen.REPORTS)
        if (targetScreen in sensitiveScreens &&
            BiometricAuthManager.isBiometricLockEnabled(context) &&
            !BiometricAuthManager.isRecentlyAuthenticated(context) &&
            activity != null
        ) {
            BiometricAuthManager.authenticate(
                activity = activity,
                title = "تأكيد الهوية البيومترية",
                subtitle = "الوصول إلى قسم (${targetScreen.title}) يتطلب التحقق من البصمة أو الوجه",
                onSuccess = { onProceed() },
                onError = { err -> viewModel.postMessage(err, isError = true) }
            )
        } else {
            onProceed()
        }
    }

    // Redirect immediately to DASHBOARD if the active employee loses permission for the current screen
    LaunchedEffect(allowedScreens, currentScreen) {
        if (currentScreen !in allowedScreens) {
            currentScreen = PmpScreen.DASHBOARD
        }
    }

    val handleSignOut: () -> Unit = {
        viewModel.logoutSession {
            UserSessionManager.getInstance(context).clearLocalCachesAndSessionDataAsync {
                signOutFromFirebaseAndCredentials(
                    context = context,
                    onSignOutComplete = {
                        firebaseUser = null
                        currentScreen = PmpScreen.DASHBOARD
                    },
                    scope = coroutineScope
                )
            }
        }
    }

    // Handle Android system back button
    BackHandler(enabled = isSessionAuthenticated && currentScreen != PmpScreen.DASHBOARD) {
        currentScreen = PmpScreen.DASHBOARD
    }

    // Auto-dismiss feedback message
    LaunchedEffect(uiMessage) {
        if (uiMessage != null) {
            delay(3500)
            viewModel.clearUiMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isInitializingSession) {
            PmpSplashScreen()
        } else if (!isSessionAuthenticated) {
            com.example.ui.components.OneUiPullDownContainer(
                pageTitle = "PMP PAINTS",
                screenKey = "login_screen"
            ) {
                LoginScreen(
                    viewModel = viewModel,
                    onLoginSuccess = {
                        firebaseUser = Firebase.auth.currentUser
                        currentScreen = PmpScreen.DASHBOARD
                        treasuryInitialSection = null
                        viewModel.startCloudRealtimeSyncIfAuthenticated()
                    }
                )
            }
        } else {

    val handleDashboardNavigate: (String) -> Unit = { route ->
        if (route == "transfers") {
            if (currentUser.hasModulePermission("التحويلات")) {
                navigateWithBiometrics(PmpScreen.TREASURY) {
                    treasuryInitialSection = "تحويل خزن"
                    currentScreen = PmpScreen.TREASURY
                }
            } else {
                viewModel.postMessage("عفواً، صلاحية (التحويلات) غير مخصصة لحسابك من قبل الإدارة", isError = true)
            }
        } else {
            val targetScreen = PmpScreen.entries.find { it.route == route }
            if (targetScreen != null && targetScreen in allowedScreens) {
                navigateWithBiometrics(targetScreen) {
                    if (route == "treasury") {
                        treasuryInitialSection = "حركة مالية"
                    }
                    currentScreen = targetScreen
                }
            } else if (targetScreen != null) {
                viewModel.postMessage("صلاحية (${targetScreen.title}) غير متاحة لحسابك الوظيفي", isError = true)
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(GlassBackgroundBrush),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            GlassBottomNavigationBar(
                allowedScreens = orderedNavScreens,
                currentScreen = currentScreen,
                onScreenSelected = { screen ->
                    if (screen in allowedScreens) {
                        navigateWithBiometrics(screen) {
                            currentScreen = screen
                        }
                    } else {
                        viewModel.postMessage("عفواً، صلاحية قسم (${screen.title}) غير مخصصة لحسابك من قبل المدير", isError = true)
                    }
                }
            )
        }
    ) { innerPadding ->
        com.example.ui.components.OneUiPullDownContainer(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
            pageTitle = if (currentScreen == PmpScreen.DASHBOARD) "لوحة التحكم الرئيسية" else currentScreen.title,
            screenKey = currentScreen.route,
            onDeepPullTriggered = {
                if (currentScreen == PmpScreen.DASHBOARD) {
                    viewModel.syncAllErpDataToServer()
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(GlassBackgroundBrush)
            ) {
                // Top Bar moves together with the page content as a single unified One UI surface
                Column(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                    Surface(
                        color = PmpWhiteSurface.copy(alpha = 0.96f),
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.clickable {
                                    currentScreen = PmpScreen.DASHBOARD
                                }
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_pmp_emblem),
                                    contentDescription = "PMP Emblem",
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Column {
                                    Text(
                                        text = "PMP PAINTS",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = PmpBrandNavy
                                    )
                                    Text(
                                        text = if (currentScreen == PmpScreen.DASHBOARD) "لوحة التحكم الرئيسية" else currentScreen.title,
                                        fontSize = 11.sp,
                                        color = PmpBrandRed,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (updateAvailable && availableUpdate != null) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF047857))
                                            .clickable {
                                                currentScreen = PmpScreen.SETTINGS
                                            }
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                            .testTag("top_bar_update_badge")
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.SystemUpdate,
                                                contentDescription = "تحديث متاح",
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = "تحديث متاح",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                // Quick Dark Mode Pro toggle button in Top Bar
                                IconButton(
                                    onClick = { viewModel.toggleDarkModeQuick() },
                                    modifier = Modifier
                                        .size(38.dp)
                                        .testTag("top_bar_theme_toggle_button")
                                ) {
                                    Icon(
                                        imageVector = if (PmpThemeController.isDarkModeActive) Icons.Default.LightMode else Icons.Default.DarkMode,
                                        contentDescription = if (PmpThemeController.isDarkModeActive) "تفعيل الوضع الفاتح" else "تفعيل الوضع المظلم الاحترافي",
                                        tint = if (PmpThemeController.isDarkModeActive) PmpGold else PmpBrandNavy,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }
                                // Clean Logout Icon Button (no text "تسجيل الخروج" as requested)
                                IconButton(
                                    onClick = { handleSignOut() },
                                    modifier = Modifier
                                        .size(38.dp)
                                        .testTag("top_bar_logout_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Logout,
                                        contentDescription = "خروج",
                                        tint = PmpBrandNavy,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }

                    val liveCloudUpdate = availableUpdate
                    if (updateAvailable && liveCloudUpdate != null && currentScreen != PmpScreen.SETTINGS) {
                        Surface(
                            color = if (liveCloudUpdate.isMandatory) Color(0xFFB91C1C) else Color(0xFF0F172A),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentScreen = PmpScreen.SETTINGS }
                                .testTag("live_cloud_update_banner")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SystemUpdate,
                                        contentDescription = null,
                                        tint = Color(0xFF34D399),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column {
                                        Text(
                                            text = if (liveCloudUpdate.isMandatory) {
                                                "تحديث إلزامي متاح الآن (الإصدار ${liveCloudUpdate.versionName})"
                                            } else {
                                                "تحديث جديد متاح (الإصدار ${liveCloudUpdate.versionName})"
                                            },
                                            color = Color.White,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        val cleanNote = remember(liveCloudUpdate.releaseNotes) {
                                            com.example.util.UpdateManager.sanitizeUserFacingReleaseNotes(liveCloudUpdate.releaseNotes)
                                                .lineSequence()
                                                .firstOrNull()
                                                .orEmpty()
                                        }
                                        if (cleanNote.isNotBlank()) {
                                            Text(
                                                text = cleanNote,
                                                color = Color(0xFFCBD5E1),
                                                fontSize = 10.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF10B981))
                                        .clickable { currentScreen = PmpScreen.SETTINGS }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "تحديث الآن",
                                        color = Color.Black,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }

                    if (!isNetworkOnline) {
                        Surface(
                            color = Color(0xFFDC2626),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WifiOff,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "لا يوجد اتصال بالإنترنت • يرجى التحقق من الشبكة للمتابعة",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(GlassBackgroundBrush)
                ) {
                    val effectiveCurrentScreen = if (currentScreen in allowedScreens) currentScreen else PmpScreen.DASHBOARD
                    // Fast, smooth screen transition with SaveableStateProvider so scroll positions and states are preserved across tabs
                    // Resolving effectiveCurrentScreen BEFORE Crossfade prevents two Crossfade branches from ever mapping to the same SaveableStateProvider key ("dashboard") simultaneously.
                    Crossfade(
                        targetState = effectiveCurrentScreen,
                        animationSpec = tween(durationMillis = 90),
                        label = "ScreenTransition"
                    ) { screen ->
                        saveableStateHolder.SaveableStateProvider(screen.route) {
                            when (screen) {
                                PmpScreen.DASHBOARD -> DashboardScreen(
                                    viewModel = viewModel,
                                    onNavigate = handleDashboardNavigate,
                                    onSignOut = handleSignOut
                                )
                                PmpScreen.TREASURY -> TreasuryScreen(
                                    viewModel = viewModel,
                                    initialSection = treasuryInitialSection,
                                    onInitialSectionConsumed = { treasuryInitialSection = null }
                                )
                                PmpScreen.PURCHASES -> PurchasesScreen(viewModel = viewModel)
                                PmpScreen.SALES -> SalesScreen(viewModel = viewModel)
                                PmpScreen.INVENTORY -> InventoryScreen(viewModel = viewModel)
                                PmpScreen.FACTORY -> FactoryScreen(viewModel = viewModel)
                                PmpScreen.CHEMISTRY_LAB -> ChemistryLabScreen(viewModel = viewModel)
                                PmpScreen.USERS -> UsersScreen(
                                    viewModel = viewModel,
                                    onSignOut = handleSignOut
                                )
                                PmpScreen.POSTER -> PosterPriceScreen(viewModel = viewModel)
                                PmpScreen.REPORTS -> ReportsScreen(viewModel = viewModel)
                                PmpScreen.SETTINGS -> SettingsScreen(
                                    viewModel = viewModel,
                                    onNavigateToUsers = {
                                        if (PmpScreen.USERS in allowedScreens) {
                                            currentScreen = PmpScreen.USERS
                                        } else {
                                            viewModel.postMessage("إدارة المستخدمين متاحة لمدير النظام فقط", isError = true)
                                        }
                                    },
                                    onSignOut = handleSignOut
                                )
                            }
                        }
                    }

            // Glass Floating Snackbar/Toast
            uiMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (msg.isError) Color(0xF27F1D1D) else Color(0xF2064E3B))
                            .border(1.dp, if (msg.isError) PmpRedLight else PmpGreenLight, RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (msg.isError) Icons.Default.ErrorOutline else Icons.Default.CheckCircleOutline,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = msg.message,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            IconButton(
                                onClick = { viewModel.clearUiMessage() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "إغلاق",
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
                }
            }
        }
    }
    }

        if (showReorderNavDialog) {
            ReorderBottomNavDialog(
                initialScreens = orderedNavScreens,
                onDismiss = { showReorderNavDialog = false },
                onSave = { newOrder ->
                    BottomNavPreferences.saveOrderedScreens(context, currentUser?.userCode, newOrder)
                    orderedNavScreens = newOrder
                    showReorderNavDialog = false
                    viewModel.postMessage("تم حفظ ترتيب الأيقونات بنجاح")
                },
                onResetDefault = {
                    BottomNavPreferences.resetOrder(context, currentUser?.userCode)
                    orderedNavScreens = baseNavScreens
                    showReorderNavDialog = false
                    viewModel.postMessage("تمت استعادة الترتيب الافتراضي")
                }
            )
        }

        if (showNavigationMenu) {
            PmpNavigationMenuModal(
                currentUser = currentUser,
                allowedScreens = allowedScreens,
                currentScreen = currentScreen,
                onSelectScreen = { targetScreen ->
                    showNavigationMenu = false
                    navigateWithBiometrics(targetScreen) {
                        currentScreen = targetScreen
                    }
                },
                onDismiss = { showNavigationMenu = false },
                onSignOut = {
                    showNavigationMenu = false
                    handleSignOut()
                }
            )
        }

        // Global loading state overlay during auth state transitions
        if (isAuthTransitioning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(enabled = false) {} // Blocks all touch interaction
                    .zIndex(9999f),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = PmpWhiteSurface,
                    shadowElevation = 16.dp,
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = PmpBrandRed,
                            strokeWidth = 3.5.dp,
                            modifier = Modifier.size(46.dp)
                        )
                        Text(
                            text = "جارٍ تحديث الجلسة ومزامنة الحساب",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = TextDark,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = authTransitionMessage,
                            fontSize = 13.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "يرجى الانتظار، يتم تأمين صلاحياتك ومنع تداخل البيانات",
                            fontSize = 11.5.sp,
                            color = TextSubtle,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GlassBottomNavigationBar(
    allowedScreens: List<PmpScreen>,
    currentScreen: PmpScreen,
    onScreenSelected: (PmpScreen) -> Unit
) {
    val listState = rememberLazyListState()

    val distinctScreens = remember(allowedScreens) { allowedScreens.distinctBy { it.route } }

    // Auto-scroll to selected item only if it is outside the currently visible viewport
    LaunchedEffect(currentScreen, distinctScreens) {
        val index = distinctScreens.indexOf(currentScreen)
        if (index >= 0) {
            val layoutInfo = listState.layoutInfo
            val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            val isFullyVisible = itemInfo != null &&
                itemInfo.offset >= layoutInfo.viewportStartOffset &&
                (itemInfo.offset + itemInfo.size) <= layoutInfo.viewportEndOffset
            if (!isFullyVisible) {
                listState.animateScrollToItem(index)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(PmpWhiteSurface.copy(alpha = 0.98f))
            .border(0.5.dp, GlassBorder, RoundedCornerShape(0.dp))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(vertical = 4.dp)
    ) {
        val totalWidth = if (maxWidth != androidx.compose.ui.unit.Dp.Infinity && maxWidth.value.isFinite() && maxWidth > 0.dp) maxWidth else 360.dp
        val visibleColumns = distinctScreens.size.coerceIn(1, 5)
        val itemWidth = (totalWidth / visibleColumns.toFloat()).coerceAtLeast(64.dp)

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
                items(
                    items = distinctScreens,
                    key = { it.route },
                    contentType = { "nav_tab_item" }
                ) { screen ->
                    val selected = currentScreen == screen
                    val color = if (selected) PmpBrandRed else TextSubtle

                    Column(
                        modifier = Modifier
                            .width(itemWidth)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onScreenSelected(screen) }
                            .padding(vertical = 5.dp)
                            .testTag("nav_tab_${screen.route}"),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .width(50.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) {
                                        if (PmpThemeController.isDarkModeActive) PmpBrandRed.copy(alpha = 0.22f) else Color(0xFFFEE2E2)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            1.dp,
                                            PmpBrandRed.copy(alpha = 0.35f),
                                            RoundedCornerShape(12.dp)
                                        )
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.title,
                                tint = color,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = screen.title,
                            color = color,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                }
            }
        }
    }
}

@Composable
fun ReorderBottomNavDialog(
    initialScreens: List<PmpScreen>,
    onDismiss: () -> Unit,
    onSave: (List<PmpScreen>) -> Unit,
    onResetDefault: () -> Unit
) {
    var screensList by remember { mutableStateOf(initialScreens) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = PmpBrandRed)
                Text(
                    text = "ترتيب أيقونات الشريط السفلي",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TextDark
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "قم بترتيب الأقسام حسب أولويات عملك اليومي. استخدم الأسهم لتقديم أو تأخير أي قسم في الشريط السفلي.",
                    fontSize = 12.5.sp,
                    color = TextSubtle,
                    lineHeight = 17.sp
                )

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(screensList, key = { _, screen -> screen.route }) { index, screen ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PmpWhiteCard,
                            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(PmpBrandRed.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PmpBrandRed
                                        )
                                    }
                                    Icon(
                                        imageVector = screen.icon,
                                        contentDescription = null,
                                        tint = PmpBrandRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = screen.title,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextDark
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                val mutable = screensList.toMutableList()
                                                val item = mutable.removeAt(index)
                                                mutable.add(index - 1, item)
                                                screensList = mutable
                                            }
                                        },
                                        enabled = index > 0,
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowUpward,
                                            contentDescription = "تحريك لأعلى",
                                            tint = if (index > 0) TextDark else Color.LightGray,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            if (index < screensList.size - 1) {
                                                val mutable = screensList.toMutableList()
                                                val item = mutable.removeAt(index)
                                                mutable.add(index + 1, item)
                                                screensList = mutable
                                            }
                                        },
                                        enabled = index < screensList.size - 1,
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowDownward,
                                            contentDescription = "تحريك لأسفل",
                                            tint = if (index < screensList.size - 1) TextDark else Color.LightGray,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                TextButton(
                    onClick = {
                        onResetDefault()
                    },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSubtle)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("إعادة الترتيب الافتراضي", fontSize = 12.sp, color = TextSubtle)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(screensList) },
                colors = ButtonDefaults.buttonColors(containerColor = PmpBrandRed),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("حفظ الترتيب", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("إلغاء", color = TextSubtle)
            }
        },
        containerColor = PmpWhiteSurface,
        shape = RoundedCornerShape(20.dp)
    )
}

private data class NavCategory(
    val title: String,
    val subtitle: String,
    val items: List<NavMenuItem>
)

private data class NavMenuItem(
    val screen: PmpScreen,
    val label: String,
    val desc: String,
    val icon: ImageVector,
    val color: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PmpNavigationMenuModal(
    currentUser: UserAccountEntity?,
    allowedScreens: List<PmpScreen>,
    currentScreen: PmpScreen,
    onSelectScreen: (PmpScreen) -> Unit,
    onDismiss: () -> Unit,
    onSignOut: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PmpWhiteBg,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(48.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFCBD5E1))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Enterprise Header Card
            Surface(
                color = if (PmpThemeController.isDarkModeActive) Color(0xFF121216) else Color(0xFF0F294A),
                border = BorderStroke(1.dp, GlassBorder),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_pmp_emblem),
                            contentDescription = "PMP Paints",
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Column {
                            Text(
                                text = "PMP PAINTS • نظام الإدارة المتكامل",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "المستخدم: ${currentUser?.fullName ?: currentUser?.username ?: "المستخدم"} (${currentUser?.role ?: "موظف"})",
                                color = Color(0xFF93C5FD),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color.White.copy(alpha = 0.15f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Quick Status Pill: Warehouses & Currency Separation Notice
            Surface(
                color = PmpInfoSoftSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (PmpThemeController.isDarkModeActive) Color(0xFF1E3A8A) else Color(0xFFBFDBFE)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.AccountBalance,
                        contentDescription = null,
                        tint = if (PmpThemeController.isDarkModeActive) Color(0xFF60A5FA) else Color(0xFF1E40AF),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "نظام الفروع المنفصل: مخازن مبيعات السودان (SDG) • مخازن مصنع مصر (EGP)",
                        color = if (PmpThemeController.isDarkModeActive) Color(0xFF93C5FD) else Color(0xFF1E3A8A),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Categorized modules
            val categories = listOf(
                NavCategory(
                    title = "المبيعات والتسعير التجاري",
                    subtitle = "إصدار الفواتير، نقاط البيع، ودليل أسعار العملتين (EGP / SDG)",
                    items = listOf(
                        NavMenuItem(
                            screen = PmpScreen.SALES,
                            label = "المبيعات ونقاط البيع",
                            desc = "فواتير البيع (نقد / آجل) وربط مخزن المبيعات المختار بخزنته وعملته",
                            icon = Icons.Default.ReceiptLong,
                            color = Color(0xFF10B981)
                        ),
                        NavMenuItem(
                            screen = PmpScreen.POSTER,
                            label = "دليل وبوستر الأسعار",
                            desc = "دليل أسعار السودان (SDG) ودليل أسعار مصنع مصر (EGP) وتصدير الصور",
                            icon = Icons.Default.LocalOffer,
                            color = Color(0xFFF59E0B)
                        )
                    )
                ),
                NavCategory(
                    title = "الخزائن والمالية والمشتريات",
                    subtitle = "السيولة النقدية، الحركات المالية، ومشتريات المواد الخام للمصنع",
                    items = listOf(
                        NavMenuItem(
                            screen = PmpScreen.TREASURY,
                            label = "الخزن والحركات المالية",
                            desc = "سجل الإيرادات والمصروفات والتحويلات (خزنة حلفا، عطبرة، الخرطوم، مصر)",
                            icon = Icons.Default.AccountBalanceWallet,
                            color = Color(0xFF3B82F6)
                        ),
                        NavMenuItem(
                            screen = PmpScreen.PURCHASES,
                            label = "المشتريات والموردين",
                            desc = "فواتير وتوريد خامات ومشتريات المصنع وتتبع حسابات الموردين (EGP / SDG)",
                            icon = Icons.Default.ShoppingCart,
                            color = Color(0xFF10B981)
                        )
                    )
                ),
                NavCategory(
                    title = "المختبر الكيميائي والتصنيع",
                    subtitle = "تركيبات الدهانات العلمية وخطوط الإنتاج بالمصنع",
                    items = listOf(
                        NavMenuItem(
                            screen = PmpScreen.CHEMISTRY_LAB,
                            label = "مختبر العلوم الكيميائية (R&D)",
                            desc = "محرك حساب PVC، مكتبة PubChem، سلامة GHS، وتوريد الخامات",
                            icon = Icons.Default.Science,
                            color = Color(0xFF8B5CF6)
                        ),
                        NavMenuItem(
                            screen = PmpScreen.FACTORY,
                            label = "تشغيل المصنع والإنتاج",
                            desc = "أوامر التشغيل، ماكينات الخلط، والتعبئة والتغليف",
                            icon = Icons.Default.PrecisionManufacturing,
                            color = Color(0xFFEC4899)
                        )
                    )
                ),
                NavCategory(
                    title = "المستودعات واللوجستيات",
                    subtitle = "أرصدة المخازن والتوريدات وحركة المواد",
                    items = listOf(
                        NavMenuItem(
                            screen = PmpScreen.INVENTORY,
                            label = "أرصدة المخازن والتوريد",
                            desc = "مخازن مبيعات السودان ومخازن مصنع مصر (المواد الخام والإنتاج التام)",
                            icon = Icons.Default.Inventory2,
                            color = Color(0xFF06B6D4)
                        )
                    )
                ),
                NavCategory(
                    title = "الإدارة والتحليلات",
                    subtitle = "التقارير الرقابية، الحسابات، وإعدادات النظام",
                    items = buildList {
                        add(
                            NavMenuItem(
                                screen = PmpScreen.REPORTS,
                                label = "التقارير والتحليلات الشاملة",
                                desc = "كشوفات المبيعات، أرباح ومصروفات، وحركة الخزن والمخازن",
                                icon = Icons.Default.BarChart,
                                color = Color(0xFF6366F1)
                            )
                        )
                        if (currentUser.isSystemManager()) {
                            add(
                                NavMenuItem(
                                    screen = PmpScreen.USERS,
                                    label = "إدارة المستخدمين والصلاحيات",
                                    desc = "إنشاء وتعديل حسابات الموظفين وتخصيص الأذونات لكل قسم",
                                    icon = Icons.Default.ManageAccounts,
                                    color = Color(0xFFEF4444)
                                )
                            )
                        }
                        add(
                            NavMenuItem(
                                screen = PmpScreen.SETTINGS,
                                label = "إعدادات النظام والأمان",
                                desc = "المزامنة السحابية، البصمة البيومترية، وإدارة النظام",
                                icon = Icons.Default.Settings,
                                color = Color(0xFF64748B)
                            )
                        )
                    }
                )
            )

            categories.forEach { category ->
                Text(
                    text = category.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = PmpBrandNavy,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                Text(
                    text = category.subtitle,
                    fontSize = 10.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                category.items.forEach { item ->
                    val isAllowed = item.screen in allowedScreens
                    val isCurrent = currentScreen == item.screen

                    Surface(
                        onClick = {
                            if (isAllowed) {
                                onSelectScreen(item.screen)
                            }
                        },
                        enabled = isAllowed,
                        color = if (isCurrent) PmpInfoSoftSurface else PmpWhiteCard,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isCurrent) Color(0xFF3B82F6) else GlassBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(item.color.copy(alpha = if (isAllowed) 0.14f else 0.05f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.label,
                                        tint = if (isAllowed) item.color else TextSubtle,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = item.label,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = if (isAllowed) {
                                                if (isCurrent) {
                                                    if (PmpThemeController.isDarkModeActive) Color(0xFF60A5FA) else Color(0xFF1E40AF)
                                                } else {
                                                    TextDark
                                                }
                                            } else {
                                                TextSubtle
                                            }
                                        )
                                        if (isCurrent) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = Color(0xFF2563EB),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "النشط حالياً",
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = item.desc,
                                        fontSize = 10.sp,
                                        color = if (isAllowed) TextMuted else TextSubtle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (!isAllowed) {
                                Icon(Icons.Default.Lock, contentDescription = "مقفل", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                            } else {
                                Icon(
                                    Icons.Default.ChevronLeft,
                                    contentDescription = null,
                                    tint = if (isCurrent) Color(0xFF3B82F6) else Color(0xFF94A3B8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sign out button
            OutlinedButton(
                onClick = onSignOut,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("تسجيل الخروج من الحساب", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun PmpSplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GlassBackgroundBrush)
            .testTag("pmp_splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = PmpWhiteSurface,
                shadowElevation = 10.dp,
                border = BorderStroke(2.dp, PmpBrandRed.copy(alpha = 0.25f)),
                modifier = Modifier.size(116.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_pmp_emblem),
                        contentDescription = "شعار PMP PAINTS",
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(20.dp))
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "PMP PAINTS",
                    color = PmpBrandNavy,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "شركة PMP للدهانات والطلاءات والصناعات الكيميائية",
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            CircularProgressIndicator(
                color = PmpBrandRed,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
