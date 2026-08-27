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
        val scopedKey = "${sessionManager.activeBranchId().orEmpty()}_$dedupeKey"
        if (sessionManager.lastPlayedSoundEventKey() == scopedKey) return
        sessionManager.markSoundEventPlayed(scopedKey)
        CashClosingSoundNotifier.play(context)
        AppAlertController.show(title, message)
    }
}
