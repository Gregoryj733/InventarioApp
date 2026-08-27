package com.inventario.app

import android.app.Application
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.inventario.app.data.bcv.BcvRateFetcher
import com.inventario.app.data.branch.BranchManager
import com.inventario.app.data.repository.AuthRepository
import com.inventario.app.data.acpower.AcPowerBatteryRepository
import com.inventario.app.data.repository.BatteryFinderRepository
import com.inventario.app.data.repository.InventoryRepository
import com.inventario.app.data.repository.OilFilterCatalogRepository
import com.inventario.app.data.repository.ReportsRepository
import com.inventario.app.data.session.SessionManager
import com.inventario.app.data.sync.CloudSync
import com.inventario.app.data.sync.CloudSyncInfo
import com.inventario.app.data.sync.NetworkMonitor
import com.inventario.app.data.sync.SyncConfig
import com.inventario.app.push.NotificationHelper
import com.inventario.app.util.AppNotifier
import com.inventario.app.util.CashClosingSoundMonitor
import com.inventario.app.util.ConfirmedOrderSoundMonitor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class InventarioApplication : Application() {
    private val appScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                Log.w(TAG, "Background task failed", throwable)
            }
    )

    lateinit var sessionManager: SessionManager
        private set
    lateinit var branchManager: BranchManager
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var inventoryRepository: InventoryRepository
        private set
    lateinit var reportsRepository: ReportsRepository
        private set
    lateinit var batteryFinderRepository: BatteryFinderRepository
        private set
    lateinit var acPowerBatteryRepository: AcPowerBatteryRepository
        private set
    lateinit var oilFilterCatalogRepository: OilFilterCatalogRepository
        private set
    val bcvRateFetcher = BcvRateFetcher()
    lateinit var cashClosingSoundMonitor: CashClosingSoundMonitor
        private set
    lateinit var confirmedOrderSoundMonitor: ConfirmedOrderSoundMonitor
        private set
    lateinit var appNotifier: AppNotifier
        private set

    private lateinit var cloudSync: CloudSync
    private var networkMonitor: NetworkMonitor? = null
    private var subscribedFirebaseTopic: String? = null

    val cloudSyncStatus: StateFlow<CloudSyncInfo>
        get() = cloudSync.status

    /**
     * Estable a través de reinicios de [cloudSync] (p. ej. al cambiar de
     * servidor desde ajustes): reenvía el evento de sesión vencida para que
     * la UI pueda forzar el cierre de sesión sin quedar suscrita a una
     * instancia de CloudSync que ya fue reemplazada.
     */
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)

        sessionManager = SessionManager(this)
        branchManager = BranchManager(this, sessionManager)
        cashClosingSoundMonitor = CashClosingSoundMonitor(this, sessionManager)
        confirmedOrderSoundMonitor = ConfirmedOrderSoundMonitor(this, sessionManager)
        appNotifier = AppNotifier(this, sessionManager)
        cloudSync = buildCloudSync()
        cloudSync.setAuthToken(sessionManager.token())

        authRepository = AuthRepository(cloudSync, sessionManager)
        inventoryRepository = InventoryRepository(context = this, cloudSync = cloudSync, appScope = appScope)
        reportsRepository = ReportsRepository(cloudSync)
        batteryFinderRepository = BatteryFinderRepository(this, cloudSync)
        acPowerBatteryRepository = AcPowerBatteryRepository(this)
        oilFilterCatalogRepository = OilFilterCatalogRepository(this)

        cloudSync.start(appScope)
        startNetworkMonitor(cloudSync)
        subscribeToActiveBranchTopic()
        forwardSessionExpiredEvents(cloudSync)
    }

    override fun onTerminate() {
        networkMonitor?.stop()
        cloudSync.stop()
        super.onTerminate()
    }

    private fun forwardSessionExpiredEvents(sync: CloudSync) {
        appScope.launch {
            sync.sessionExpired.collect { _sessionExpired.emit(Unit) }
        }
    }

    private fun startNetworkMonitor(cloud: CloudSync) {
        val monitor = NetworkMonitor(this)
        networkMonitor = monitor
        monitor.start()
        appScope.launch {
            monitor.isOnline.collect { online -> cloud.setNetworkAvailable(online) }
        }
    }

    fun subscribeToActiveBranchTopic() {
        val topic = branchManager.firebaseTopicForActiveBranch()
        if (topic == subscribedFirebaseTopic) return
        appScope.launch {
            runCatching {
                subscribedFirebaseTopic?.let { old ->
                    FirebaseMessaging.getInstance().unsubscribeFromTopic(old).await()
                }
                FirebaseMessaging.getInstance().subscribeToTopic(topic).await()
                subscribedFirebaseTopic = topic
            }.onFailure { error ->
                Log.w(TAG, "No se pudo suscribir al topic de notificaciones: $topic", error)
            }
        }
    }

    private fun buildCloudSync(): CloudSync {
        val config = branchManager.syncConfigForActiveBranch()
            ?: SyncConfig.load(this)
            ?: SyncConfig(baseUrl = "", apiKey = "")
        return CloudSync(config)
    }

    /** Reconstruye el cliente de nube tras cambiar la URL/clave o la sucursal activa. */
    fun restartCloudSync(): StateFlow<CloudSyncInfo> {
        cloudSync.stop()
        val sync = buildCloudSync()
        sync.setAuthToken(sessionManager.token())
        cloudSync = sync
        inventoryRepository.setCloudSync(sync)
        authRepository.setCloudSync(sync)
        reportsRepository.setCloudSync(sync)
        batteryFinderRepository.setCloudSync(sync)
        sync.start(appScope)
        networkMonitor?.stop()
        startNetworkMonitor(sync)
        forwardSessionExpiredEvents(sync)
        subscribeToActiveBranchTopic()
        return sync.status
    }

    /**
     * Cambia la sucursal activa, reconecta al sync-server correspondiente y
     * limpia cachés en memoria. Devuelve false si hay pedidos offline pendientes.
     */
    fun switchBranch(branchId: String): Boolean {
        if (inventoryRepository.hasPendingOfflineOrders()) return false
        branchManager.activateBranch(branchId)
        restartCloudSync()
        cashClosingSoundMonitor.reset()
        confirmedOrderSoundMonitor.reset()
        return true
    }

    companion object {
        private const val TAG = "InventarioApplication"
    }
}
