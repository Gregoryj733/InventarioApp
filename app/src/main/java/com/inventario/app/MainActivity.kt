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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
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
import com.inventario.app.ui.batteryfinder.BatteryFinderScreen
import com.inventario.app.ui.batteryfinder.BatteryFinderViewModel
import com.inventario.app.ui.cashclosing.CashClosingScreen
import com.inventario.app.ui.cashclosing.CashClosingViewModel
import com.inventario.app.ui.home.HomeScreen
import com.inventario.app.ui.home.HomeViewModel
import com.inventario.app.ui.hub.HubDestination
import com.inventario.app.ui.hub.HubViewModel
import com.inventario.app.ui.hub.MainHubScreen
import com.inventario.app.ui.login.LoginScreen
import com.inventario.app.ui.login.LoginViewModel
import com.inventario.app.ui.reports.ReportsScreen
import com.inventario.app.ui.reports.ReportsViewModel
import com.inventario.app.ui.theme.AppSnackbarController
import com.inventario.app.ui.theme.InventarioTheme
import com.inventario.app.ui.users.UserManagementScreen
import com.inventario.app.ui.users.UserManagementViewModel
import com.inventario.app.trial.TrialExpiredScreen
import com.inventario.app.trial.TrialGate

class MainActivity : ComponentActivity() {
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
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
    CASH_CLOSING,
    REPORTS,
    USERS,
    BATTERY_FINDER
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
    LaunchedEffect(Unit) {
        AppSnackbarController.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
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

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) {
        // Solo aplica en el flavor "demo" (BuildConfig.IS_TRIAL); en
        // "production" TrialGate.isExpired() siempre devuelve false. Se
        // revisa antes que nada, incluso antes del login, para que una
        // sesión ya guardada no siga dando acceso tras vencer la prueba.
        if (TrialGate.isExpired(app.applicationContext)) {
            TrialExpiredScreen()
            return@Scaffold
        }

        if (!loggedIn) {
            val loginVm: LoginViewModel = viewModel(
                key = "login_$loginSessionKey",
                factory = LoginViewModel.factory(app.authRepository, app.sessionManager)
            )
            LoginScreen(
                viewModel = loginVm,
                onLoggedIn = {
                    loggedIn = true
                    currentScreen = AppScreen.HUB
                    AppSnackbarController.show("Sesión iniciada correctamente.")
                }
            )
            return@Scaffold
        }

        val role = app.sessionManager.role() ?: UserRole.CONSULTA
        val username = app.sessionManager.username().orEmpty()
        val subtitle = "$username · ${role.displayLabel()}"

        val logoutAndNotify: () -> Unit = {
            logout()
            AppSnackbarController.show("Sesión cerrada.")
        }

        val hubVm: HubViewModel = viewModel(
            key = "hub_$loginSessionKey",
            factory = HubViewModel.factory(
                inventoryRepository = app.inventoryRepository,
                sessionManager = app.sessionManager,
                bcvRateFetcher = app.bcvRateFetcher
            )
        )
        val hubState by hubVm.state.collectAsState()

        when (currentScreen) {
            AppScreen.HUB -> {
                MainHubScreen(
                    username = hubState.username,
                    role = hubState.role,
                    bcvLabel = hubState.bcvLabel,
                    bcvRefreshing = hubState.bcvRefreshing,
                    cashClosingAlert = hubState.cashClosingAlert,
                    pendingReportsCount = hubState.pendingReportsCount,
                    onNavigate = { destination ->
                        currentScreen = when (destination) {
                            HubDestination.INVENTORY -> AppScreen.INVENTORY
                            HubDestination.CASH_CLOSING -> AppScreen.CASH_CLOSING
                            HubDestination.REPORTS -> AppScreen.REPORTS
                            HubDestination.USERS -> AppScreen.USERS
                            HubDestination.BATTERY_FINDER -> AppScreen.BATTERY_FINDER
                        }
                    },
                    onRefreshBcv = hubVm::refreshBcv,
                    onLogout = logoutAndNotify
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
                        restartCloudSync = app::restartCloudSync
                    )
                )
                HomeScreen(
                    viewModel = homeVm,
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
                        bcvRateFetcher = app.bcvRateFetcher
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
                        cloudEvents = app.inventoryRepository.observeCloudEvents()
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
                    factory = BatteryFinderViewModel.factory(app.batteryFinderRepository)
                )
                BatteryFinderScreen(
                    viewModel = batteryFinderVm,
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
                        app.inventoryRepository.observeCloudEvents()
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
