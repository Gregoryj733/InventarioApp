package com.inventario.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.entity.displayLabel
import com.inventario.app.data.sync.CloudEvent
import com.inventario.app.ui.batteryfinder.BatteryFinderScreen
import com.inventario.app.ui.batteryfinder.BatteryFinderViewModel
import com.inventario.app.ui.powermaxx.PowerMaxxBatteryScreen
import com.inventario.app.ui.powermaxx.PowerMaxxBatteryViewModel
import com.inventario.app.ui.coupon.CouponActivateScreen
import com.inventario.app.ui.coupon.CouponActivateViewModel
import com.inventario.app.ui.cashclosing.CashClosingScreen
import com.inventario.app.ui.cashclosing.CashClosingViewModel
import com.inventario.app.ui.home.HomeScreen
import com.inventario.app.ui.home.HomeViewModel
import com.inventario.app.ui.hub.HubDestination
import com.inventario.app.ui.hub.HubViewModel
import com.inventario.app.ui.hub.MainHubScreen
import com.inventario.app.ui.login.LoginScreen
import com.inventario.app.ui.login.LoginViewModel
import com.inventario.app.ui.oilfilter.OilFilterFinderScreen
import com.inventario.app.ui.oilfilter.OilFilterFinderViewModel
import com.inventario.app.ui.reports.ReportsScreen
import com.inventario.app.ui.reports.ReportsViewModel
import com.inventario.app.ui.theme.AppAlert
import com.inventario.app.ui.theme.AppAlertController
import com.inventario.app.ui.theme.AppSnackbarController
import com.inventario.app.ui.theme.InventarioTheme
import com.inventario.app.ui.theme.LocalActiveBranchId
import com.inventario.app.ui.users.UserManagementScreen
import com.inventario.app.ui.users.UserManagementViewModel

class MainActivity : ComponentActivity() {
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        val app = application as InventarioApplication
        setContent {
            InventarioTheme {
                InventarioRoot(app)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private enum class AppScreen {
    HUB,
    INVENTORY,
    COUPON_ACTIVATE,
    CASH_CLOSING,
    REPORTS,
    USERS,
    BATTERY_FINDER,
    POWER_MAXX_BATTERY,
    OIL_FILTER_FINDER
}

@Composable
private fun InventarioRoot(app: InventarioApplication) {
    // El inicio de sesión persiste entre aperturas de la app (SessionManager
    // usa SharedPreferences); solo se vuelve a pedir si el usuario cierra
    // sesión explícitamente o si el servidor invalida la sesión (ver abajo).
    var loggedIn by remember { mutableStateOf(app.sessionManager.isLoggedIn()) }
    var loginSessionKey by remember { mutableIntStateOf(0) }
    var currentScreen by remember { mutableStateOf(AppScreen.HUB) }

    val snackbarHostState = remember { SnackbarHostState() }
    var currentAlert by remember { mutableStateOf<AppAlert?>(null) }
    LaunchedEffect(Unit) {
        AppSnackbarController.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
    LaunchedEffect(Unit) {
        AppAlertController.alerts.collect { alert ->
            currentAlert = alert
        }
    }

    val logout: () -> Unit = {
        app.sessionManager.clear()
        loggedIn = false
        currentScreen = AppScreen.HUB
        loginSessionKey++
    }

    // Si el servidor rechaza la sesión (token vencido o inválido) se cierra
    // la sesión automáticamente y se vuelve a pedir inicio de sesión, en vez
    // de dejar la app en un estado roto mostrando errores de sincronización.
    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            app.sessionExpired.collect {
                logout()
                AppSnackbarController.show("Tu sesión expiró. Inicia sesión nuevamente.")
            }
        }
    }

    LaunchedEffect(loggedIn, loginSessionKey) {
        if (!loggedIn) return@LaunchedEffect
        app.cashClosingSoundMonitor.reset()
        app.cashClosingSoundMonitor.refresh(app.inventoryRepository)
        app.confirmedOrderSoundMonitor.reset()
        app.confirmedOrderSoundMonitor.refresh(app.inventoryRepository)
        app.inventoryRepository.observeCloudEvents().collect { event ->
            when (event) {
                is CloudEvent.CashClosings -> {
                    app.cashClosingSoundMonitor.refresh(app.inventoryRepository)
                }
                is CloudEvent.Sales -> {
                    app.confirmedOrderSoundMonitor.refresh(app.inventoryRepository)
                }
                else -> Unit
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) {
        currentAlert?.let { alert ->
            AlertDialog(
                onDismissRequest = { currentAlert = null },
                title = {
                    Text(text = alert.title, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(text = alert.message)
                },
                confirmButton = {
                    TextButton(onClick = { currentAlert = null }) {
                        Text("Entendido")
                    }
                }
            )
        }

        if (!loggedIn) {
            val loginVm: LoginViewModel = viewModel(
                key = "login_$loginSessionKey",
                factory = LoginViewModel.factory(
                    authRepository = app.authRepository,
                    sessionManager = app.sessionManager,
                    branchManager = app.branchManager,
                    appContext = app,
                    cloudSyncStatus = app.cloudSyncStatus,
                    restartCloudSync = { app.restartCloudSync() }
                )
            )
            val loginState by loginVm.state.collectAsState()
            CompositionLocalProvider(LocalActiveBranchId provides loginState.selectedBranchId) {
                LoginScreen(
                    viewModel = loginVm,
                    onLoggedIn = {
                        loggedIn = true
                        currentScreen = AppScreen.HUB
                        AppSnackbarController.show("Sesión iniciada correctamente.")
                    }
                )
            }
            return@Scaffold
        }

        val role = app.sessionManager.role() ?: UserRole.CONSULTA
        val username = app.sessionManager.username().orEmpty()
        val branchLabel = app.branchManager.getActiveBranch()?.label.orEmpty()
        val subtitle = buildString {
            append(username)
            append(" · ")
            append(role.displayLabel())
            if (branchLabel.isNotBlank()) {
                append(" · ")
                append(branchLabel)
            }
        }

        val logoutAndNotify: () -> Unit = {
            logout()
            AppSnackbarController.show("Sesión cerrada.")
        }

        val hubVm: HubViewModel = viewModel(
            key = "hub_$loginSessionKey",
            factory = HubViewModel.factory(
                inventoryRepository = app.inventoryRepository,
                sessionManager = app.sessionManager,
                branchManager = app.branchManager,
                authRepository = app.authRepository,
                bcvRateFetcher = app.bcvRateFetcher,
                switchBranch = app::switchBranch,
                onBranchSwitched = {
                    loginSessionKey++
                    currentScreen = AppScreen.HUB
                    AppSnackbarController.show(
                        "Sucursal activa: ${app.branchManager.getActiveBranch()?.label.orEmpty()}"
                    )
                }
            )
        )
        val hubState by hubVm.state.collectAsState()
        val activeBranchId = app.sessionManager.activeBranchId().orEmpty()

        CompositionLocalProvider(LocalActiveBranchId provides activeBranchId) {
        when (currentScreen) {
            AppScreen.HUB -> {
                MainHubScreen(
                    username = hubState.username,
                    role = hubState.role,
                    activeBranchLabel = hubState.activeBranchLabel,
                    canSwitchBranch = hubState.canSwitchBranch,
                    availableBranches = hubState.availableBranches,
                    showBranchSwitchDialog = hubState.showBranchSwitchDialog,
                    branchSwitchLoading = hubState.branchSwitchLoading,
                    branchSwitchError = hubState.branchSwitchError,
                    pendingReauthBranchId = hubState.pendingReauthBranchId,
                    reauthPassword = hubState.reauthPassword,
                    activeBranchId = activeBranchId,
                    bcvLabel = hubState.bcvLabel,
                    bcvRefreshing = hubState.bcvRefreshing,
                    cashClosingAlert = hubState.cashClosingAlert,
                    pendingReportsCount = hubState.pendingReportsCount,
                    onNavigate = { destination ->
                        currentScreen = when (destination) {
                            HubDestination.INVENTORY -> AppScreen.INVENTORY
                            HubDestination.COUPON_ACTIVATE -> AppScreen.COUPON_ACTIVATE
                            HubDestination.CASH_CLOSING -> AppScreen.CASH_CLOSING
                            HubDestination.REPORTS -> AppScreen.REPORTS
                            HubDestination.USERS -> AppScreen.USERS
                            HubDestination.BATTERY_FINDER -> AppScreen.BATTERY_FINDER
                            HubDestination.POWER_MAXX_BATTERY -> AppScreen.POWER_MAXX_BATTERY
                            HubDestination.OIL_FILTER_FINDER -> AppScreen.OIL_FILTER_FINDER
                        }
                    },
                    onRefreshBcv = hubVm::refreshBcv,
                    onLogout = logoutAndNotify,
                    onOpenBranchSwitch = hubVm::openBranchSwitchDialog,
                    onDismissBranchSwitch = hubVm::dismissBranchSwitchDialog,
                    onBranchSelected = hubVm::requestBranchSwitch,
                    onReauthPasswordChange = hubVm::onReauthPasswordChange,
                    onConfirmBranchReauth = hubVm::confirmBranchReauth
                )
            }
            AppScreen.INVENTORY -> {
                val homeVm: HomeViewModel = viewModel(
                    key = "home_$loginSessionKey",
                    factory = HomeViewModel.factory(
                        appContext = app.applicationContext,
                        inventoryRepository = app.inventoryRepository,
                        sessionManager = app.sessionManager,
                        bcvRateFetcher = app.bcvRateFetcher,
                        restartCloudSync = app::restartCloudSync,
                        appNotifier = app.appNotifier,
                        onOrderConfirmedSound = app.confirmedOrderSoundMonitor::notifyForSubmitter,
                        onOrdersReset = app.confirmedOrderSoundMonitor::reset
                    )
                )
                HomeScreen(
                    viewModel = homeVm,
                    subtitle = subtitle,
                    onBack = { currentScreen = AppScreen.HUB },
                    onLogout = logoutAndNotify
                )
            }
            AppScreen.COUPON_ACTIVATE -> {
                val couponVm: CouponActivateViewModel = viewModel(
                    key = "coupon_activate_$loginSessionKey",
                    factory = CouponActivateViewModel.factory(app.inventoryRepository)
                )
                CouponActivateScreen(
                    viewModel = couponVm,
                    subtitle = subtitle,
                    onBack = { currentScreen = AppScreen.HUB },
                    onLogout = logoutAndNotify
                )
            }
            AppScreen.CASH_CLOSING -> {
                val cashClosingVm: CashClosingViewModel = viewModel(
                    key = "cash_closing_$loginSessionKey",
                    factory = CashClosingViewModel.factory(
                        inventoryRepository = app.inventoryRepository,
                        sessionManager = app.sessionManager,
                        bcvRateFetcher = app.bcvRateFetcher,
                        onClosingSubmittedSound = app.cashClosingSoundMonitor::notifySubmittedForSubmitter
                    )
                )
                CashClosingScreen(
                    viewModel = cashClosingVm,
                    onBack = {
                        hubVm.refreshClosingAlerts()
                        currentScreen = AppScreen.HUB
                    }
                )
            }
            AppScreen.REPORTS -> {
                val reportsVm: ReportsViewModel = viewModel(
                    key = "reports_$loginSessionKey",
                    factory = ReportsViewModel.factory(
                        reportsRepository = app.reportsRepository,
                        bcvRateProvider = { app.inventoryRepository.currentBcvRate() },
                        sessionManager = app.sessionManager,
                        userRole = role,
                        cloudEvents = app.inventoryRepository.observeCloudEvents(),
                        onReviewCompleted = { dedupeKey, title, message ->
                            app.appNotifier.notify(dedupeKey, title, message)
                        }
                    )
                )
                ReportsScreen(
                    viewModel = reportsVm,
                    subtitle = subtitle,
                    onBack = {
                        hubVm.refreshClosingAlerts()
                        currentScreen = AppScreen.HUB
                    },
                    onLogout = logoutAndNotify,
                    onRefreshBcv = hubVm::refreshBcv
                )
            }
            AppScreen.BATTERY_FINDER -> {
                val batteryFinderVm: BatteryFinderViewModel = viewModel(
                    key = "battery_finder_$loginSessionKey",
                    factory = BatteryFinderViewModel.factory(
                        app.batteryFinderRepository,
                        app.inventoryRepository
                    )
                )
                BatteryFinderScreen(
                    viewModel = batteryFinderVm,
                    subtitle = subtitle,
                    onBack = { currentScreen = AppScreen.HUB },
                    onLogout = logoutAndNotify
                )
            }
            AppScreen.POWER_MAXX_BATTERY -> {
                val powerMaxxVm: PowerMaxxBatteryViewModel = viewModel(
                    key = "power_maxx_battery_$loginSessionKey",
                    factory = PowerMaxxBatteryViewModel.factory(
                        app.acPowerBatteryRepository,
                        app.inventoryRepository
                    )
                )
                PowerMaxxBatteryScreen(
                    viewModel = powerMaxxVm,
                    subtitle = subtitle,
                    onBack = { currentScreen = AppScreen.HUB },
                    onLogout = logoutAndNotify
                )
            }
            AppScreen.OIL_FILTER_FINDER -> {
                val oilFilterVm: OilFilterFinderViewModel = viewModel(
                    key = "oil_filter_finder_$loginSessionKey",
                    factory = OilFilterFinderViewModel.factory(
                        app.oilFilterCatalogRepository,
                        app.inventoryRepository
                    )
                )
                OilFilterFinderScreen(
                    viewModel = oilFilterVm,
                    subtitle = subtitle,
                    onBack = { currentScreen = AppScreen.HUB },
                    onLogout = logoutAndNotify
                )
            }
            AppScreen.USERS -> if (role == UserRole.ADMIN) {
                val usersVm: UserManagementViewModel = viewModel(
                    key = "users_$loginSessionKey",
                    factory = UserManagementViewModel.factory(
                        app.authRepository,
                        app.inventoryRepository.observeCloudEvents(),
                        app.inventoryRepository,
                        app.branchManager.getActiveBranch()?.label.orEmpty()
                    )
                )
                UserManagementScreen(
                    viewModel = usersVm,
                    subtitle = subtitle,
                    onBack = { currentScreen = AppScreen.HUB },
                    onLogout = logoutAndNotify
                )
            } else {
                LaunchedEffect(Unit) {
                    currentScreen = AppScreen.HUB
                }
            }
        }
        }
    }
}
