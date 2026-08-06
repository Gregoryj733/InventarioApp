package com.inventario.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Recibe las notificaciones que el sync-server envía (vía Firebase Admin SDK)
 * al topic "inventario_actualizado" cada vez que el administrador carga un
 * nuevo Excel. La app ya se mantiene al día en tiempo real por WebSocket
 * mientras está abierta; este servicio cubre el caso de la app en segundo
 * plano o cerrada, mostrando el aviso en la bandeja del sistema.
 */
class InventarioMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: "Inventario actualizado"
        val body = message.notification?.body
            ?: "El administrador actualizó el inventario. Abre la app para ver los cambios."
        NotificationHelper.showInventoryUpdated(applicationContext, title, body)
    }

    override fun onNewToken(token: String) {
        // El servidor envía por topic, no por token individual; no se requiere
        // registrar el token en el backend.
    }
}
