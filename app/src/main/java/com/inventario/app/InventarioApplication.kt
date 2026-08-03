package com.inventario.app

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.inventario.app.data.AppDatabase
import com.inventario.app.data.MIGRATION_1_2
import com.inventario.app.data.MIGRATION_2_3
import com.inventario.app.data.MIGRATION_3_4
import com.inventario.app.data.MIGRATION_4_5
import com.inventario.app.data.bcv.BcvRateFetcher
import com.inventario.app.data.repository.AuthRepository
import com.inventario.app.data.repository.InventoryRepository
import com.inventario.app.data.repository.ReportsRepository
import com.inventario.app.data.session.SessionManager
import com.inventario.app.data.sync.CloudSyncInfo
import com.inventario.app.data.sync.InventoryCloudSync
import com.inventario.app.data.sync.NetworkMonitor
import com.inventario.app.data.sync.SyncConfig
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class InventarioApplication : Application() {
    private val appScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                Log.w(TAG, "Background task failed", throwable)
            }
    )

    lateinit var database: AppDatabase
        private set
    lateinit var sessionManager: SessionManager
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var inventoryRepository: InventoryRepository
        private set
    lateinit var reportsRepository: ReportsRepository
        private set
    val bcvRateFetcher = BcvRateFetcher()

    private var cloudSync: InventoryCloudSync? = null
    private var networkMonitor: NetworkMonitor? = null

    override fun onCreate() {
        super.onCreate()
        database = openDatabase()
        sessionManager = SessionManager(this)
        authRepository = AuthRepository(database.userDao())

        val sync = initCloudSync()
        cloudSync = sync
        inventoryRepository = InventoryRepository(
            context = this,
            productDao = database.productDao(),
            appMetaDao = database.appMetaDao(),
            saleRecordDao = database.saleRecordDao(),
            saleLineItemDao = database.saleLineItemDao(),
            cashClosingRecordDao = database.cashClosingRecordDao(),
            cloudSync = sync
        )
        reportsRepository = ReportsRepository(
            saleRecordDao = database.saleRecordDao(),
            saleLineItemDao = database.saleLineItemDao(),
            cashClosingRecordDao = database.cashClosingRecordDao()
        )

        appScope.launch {
            authRepository.ensureDefaultUsers()
            inventoryRepository.ensureMeta()
            inventoryRepository.rebuildSearchIndex()
        }

        sync?.start(appScope)
        sync?.let { cloud ->
            val monitor = NetworkMonitor(this)
            networkMonitor = monitor
            monitor.start()
            appScope.launch {
                monitor.isOnline.collect { online ->
                    cloud.setNetworkAvailable(online)
                }
            }
        }
    }

    override fun onTerminate() {
        networkMonitor?.stop()
        cloudSync?.stop()
        super.onTerminate()
    }

    private fun openDatabase(): AppDatabase {
        val dbName = "inventario.db"
        val builder = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            dbName
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        return runCatching { builder.build() }.getOrElse { migrationError ->
            Log.w(TAG, "Database migration failed, recreating local database", migrationError)
            applicationContext.deleteDatabase(dbName)
            builder
                .fallbackToDestructiveMigration()
                .build()
        }
    }

    private fun initCloudSync(): InventoryCloudSync? {
        val config = SyncConfig.load(this)
        if (config == null || !config.isConfigured) {
            Log.i(TAG, "Sync server not configured; cloud sync disabled")
            return null
        }

        return runCatching {
            InventoryCloudSync(
                config = config,
                productDao = database.productDao(),
                appMetaDao = database.appMetaDao(),
                saleRecordDao = database.saleRecordDao()
            )
        }.onFailure { error ->
            Log.w(TAG, "Cloud sync unavailable", error)
        }.getOrNull()
    }

    fun restartCloudSync(): StateFlow<CloudSyncInfo>? {
        cloudSync?.stop()
        val sync = initCloudSync()
        cloudSync = sync
        inventoryRepository.setCloudSync(sync)
        sync?.start(appScope)
        return sync?.status
    }

    companion object {
        private const val TAG = "InventarioApplication"
    }
}
