package com.inventario.app.util

import android.content.Context
import com.inventario.app.data.repository.InventoryRepository
import com.inventario.app.data.session.SessionManager
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detecta pedidos confirmados nuevos del día y emite alertas auditivas + popup
 * para todos los usuarios conectados en tiempo real.
 */
class ConfirmedOrderSoundMonitor(
    private val context: Context,
    private val sessionManager: SessionManager
) {
    private var knownSyncIds: Set<String> = emptySet()
    private var initialized = false

    private val timeFormat = SimpleDateFormat("HH:mm", Locale("es", "VE"))
    private val moneyFormat = NumberFormat.getNumberInstance(Locale("es", "VE")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    suspend fun refresh(inventoryRepository: InventoryRepository) {
        val orders = inventoryRepository.confirmedOrdersTodayDetails()
        if (!initialized) {
            knownSyncIds = orders.map { it.syncId }.toSet()
            initialized = true
            return
        }

        orders.forEach { order ->
            if (order.syncId !in knownSyncIds) {
                val time = timeFormat.format(Date(order.createdAt))
                val total = "$${moneyFormat.format(order.totalUsd)}"
                val (title, message) = AppNotificationMessages.orderConfirmedRemote(total, time)
                AppNotificationHelper.notify(
                    context = context,
                    sessionManager = sessionManager,
                    dedupeKey = order.syncId,
                    title = title,
                    message = message
                )
            }
        }

        knownSyncIds = orders.map { it.syncId }.toSet()
    }

    fun notifyForSubmitter(syncId: String) {
        val (title, message) = AppNotificationMessages.orderConfirmedSelf()
        AppNotificationHelper.notify(
            context = context,
            sessionManager = sessionManager,
            dedupeKey = syncId,
            title = title,
            message = message
        )
    }

    fun reset() {
        initialized = false
        knownSyncIds = emptySet()
    }
}
