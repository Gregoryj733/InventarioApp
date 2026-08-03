package com.inventario.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inventario.app.ui.cashclosing.CashClosingScreen
import com.inventario.app.ui.cashclosing.CashClosingViewModel
import com.inventario.app.ui.home.HomeScreen
import com.inventario.app.ui.home.HomeViewModel
import com.inventario.app.ui.login.LoginScreen
import com.inventario.app.ui.login.LoginViewModel
import com.inventario.app.ui.theme.InventarioTheme

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

@Composable
private fun InventarioRoot(app: InventarioApplication) {
    var loggedIn by remember { mutableStateOf(app.sessionManager.isLoggedIn()) }
    var loginSessionKey by remember { mutableIntStateOf(0) }
    var showCashClosing by remember { mutableStateOf(false) }

    if (!loggedIn) {
        val loginVm: LoginViewModel = viewModel(
            key = "login_$loginSessionKey",
            factory = LoginViewModel.factory(app.authRepository, app.sessionManager)
        )
        LoginScreen(
            viewModel = loginVm,
            onLoggedIn = { loggedIn = true }
        )
    } else if (showCashClosing) {
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
        val homeState by homeVm.state.collectAsState()
        val cashClosingVm: CashClosingViewModel = viewModel(
            key = "cash_closing_$loginSessionKey",
            factory = CashClosingViewModel.factory(
                inventoryRepository = app.inventoryRepository,
                sessionManager = app.sessionManager,
                initialBcvRate = homeState.bcvRate
            )
        )
        CashClosingScreen(
            viewModel = cashClosingVm,
            onBack = { showCashClosing = false }
        )
    } else {
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
            onLogout = {
                app.sessionManager.clear()
                loggedIn = false
                showCashClosing = false
                loginSessionKey++
            },
            onOpenCashClosing = { showCashClosing = true }
        )
    }
}
