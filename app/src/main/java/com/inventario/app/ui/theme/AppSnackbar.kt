package com.inventario.app.ui.theme

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Canal global para mostrar mensajes informativos (Snackbar) desde cualquier
 * ViewModel, sin que este necesite una referencia a la UI. Un único
 * SnackbarHost en la raíz de la app ([com.inventario.app.MainActivity])
 * consume [messages] y los muestra sobre la pantalla actual, garantizando
 * que toda acción relevante del usuario tenga una confirmación visible y
 * consistente en toda la app.
 */
object AppSnackbarController {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun show(message: String) {
        if (message.isBlank()) return
        _messages.tryEmit(message)
    }
}
