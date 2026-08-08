package com.inventario.app.util

import android.content.Context
import com.inventario.app.data.session.SessionManager

/**
 * Punto único para emitir sonido + popup desde la aplicación.
 */
class AppNotifier(
    private val context: Context,
    private val sessionManager: SessionManager
) {
    fun notify(dedupeKey: String, title: String, message: String) {
        AppNotificationHelper.notify(context, sessionManager, dedupeKey, title, message)
    }
}
