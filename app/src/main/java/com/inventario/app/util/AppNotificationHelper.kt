package com.inventario.app.util

import android.content.Context
import com.inventario.app.data.session.SessionManager
import com.inventario.app.ui.theme.AppAlertController

/**
 * Emite sonido + popup para un evento, deduplicado por [dedupeKey] para no
 * repetir la misma alerta en reintentos o refrescos consecutivos.
 */
object AppNotificationHelper {

    fun notify(
        context: Context,
        sessionManager: SessionManager,
        dedupeKey: String,
        title: String,
        message: String
    ) {
        if (sessionManager.lastPlayedSoundEventKey() == dedupeKey) return
        sessionManager.markSoundEventPlayed(dedupeKey)
        CashClosingSoundNotifier.play(context)
        AppAlertController.show(title, message)
    }
}
