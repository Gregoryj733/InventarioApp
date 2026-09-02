package com.inventario.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.inventario.app.InventarioApplication

/**
 * Recibe notificaciones del sync-server (vía Firebase Admin SDK):
 * - Inventario actualizado (Excel importado)
 * - Pedido confirmado en otro dispositivo de la misma sucursal
 */
class InventarioMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"].orEmpty()) {
            "sales_updated" -> {
                (application as? InventarioApplication)?.scheduleConfirmedOrdersRefresh()
            }
            "inventory_updated" -> {
                (application as? InventarioApplication)?.scheduleInventoryRefresh()
                val title = message.notification?.title ?: "Inventario actualizado"
                val body = message.notification?.body
                    ?: "El administrador actualizó el inventario. Los cambios ya están disponibles."
                NotificationHelper.showInventoryUpdated(applicationContext, title, body)
            }
            else -> Unit
        }
    }

    override fun onNewToken(token: String) {
        // El servidor envía por topic, no por token individual; no se requiere
        // registrar el token en el backend.
    }
}
