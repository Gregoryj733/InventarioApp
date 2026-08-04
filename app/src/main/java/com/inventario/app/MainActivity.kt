package com.inventario.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.inventario.app.ui.theme.InventarioTheme
import com.inventario.app.ui.users.UserManagementScreen
import com.inventario.app.ui.users.UserManagementViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as InventarioApplication
        setContent {
            InventarioTheme {
                InventarioRoot(app)
            }
        }
    }
}

private enum class AppScreen {
    HUB,
    INVENTORY,
    CASH_CLOSING,
    REPORTS,
    USERS
}

@Composable
private fun InventarioRoot(app: InventarioApplication) {
    var loggedIn by remember { mutableStateOf(app.sessionManager.isLoggedIn()) }
    var loginSessionKey by remember { mutableIntStateOf(0) }
    var currentScreen by remember { mutableStateOf(AppScreen.HUB) }

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
            }
        )
        return
    }

    val role = app.sessionManager.role() ?: UserRole.CONSULTA
    val username = app.sessionManager.username().orEmpty()
    val roleLabel = if (role == UserRole.ADMIN) "Administrador" else "Consulta"
    val subtitle = "$username · $roleLabel"

    val logout: () -> Unit = {
        app.sessionManager.clear()
        loggedIn = false
        currentScreen = AppScreen.HUB
        loginSessionKey++
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
                    }
                },
                onRefreshBcv = hubVm::refreshBcv,
                onLogout = logout
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
                onLogout = logout
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
                    userRole = role
                )
            )
            ReportsScreen(
                viewModel = reportsVm,
                subtitle = subtitle,
                onBack = {
                    hubVm.refreshClosingAlerts()
                    currentScreen = AppScreen.HUB
                },
                onLogout = logout,
                onRefreshBcv = hubVm::refreshBcv
            )
        }
        AppScreen.USERS -> if (role == UserRole.ADMIN) {
            val usersVm: UserManagementViewModel = viewModel(
                key = "users_$loginSessionKey",
                factory = UserManagementViewModel.factory(app.authRepository)
            )
            UserManagementScreen(
                viewModel = usersVm,
                subtitle = subtitle,
                onBack = { currentScreen = AppScreen.HUB },
                onLogout = logout
            )
        } else {
            LaunchedEffect(Unit) {
                currentScreen = AppScreen.HUB
            }
        }
    }
}
