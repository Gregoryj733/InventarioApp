package com.inventario.app.ui.theme

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class AppAlert(
    val title: String,
    val message: String
)

/**
 * Canal global para mostrar diálogos emergentes (popup) desde cualquier capa
 * de la app. Un único host en [com.inventario.app.MainActivity] consume
 * [alerts] y los muestra sobre la pantalla actual.
 */
object AppAlertController {
    private val _alerts = MutableSharedFlow<AppAlert>(extraBufferCapacity = 8)
    val alerts: SharedFlow<AppAlert> = _alerts.asSharedFlow()

    fun show(title: String, message: String) {
        if (title.isBlank() && message.isBlank()) return
        _alerts.tryEmit(AppAlert(title = title, message = message))
    }
}
