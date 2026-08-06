package com.inventario.app.trial

import android.content.Context
import com.inventario.app.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Vencimiento de la version de prueba (flavor "demo", ver app/build.gradle.kts).
 * El conteo arranca en la primera apertura de la app en el dispositivo
 * (no en la fecha de instalación del paquete, que un usuario podría alterar
 * junto con la fecha del sistema; aun así esto es una traba simple para una
 * prueba interna, no un mecanismo anticopia).
 *
 * En el flavor "production", [BuildConfig.IS_TRIAL] es `false` y todas las
 * funciones de este archivo son no-op / siempre permiten el uso.
 */
object TrialGate {
    private const val PREFS_NAME = "inventario_trial"
    private const val KEY_FIRST_LAUNCH_AT = "first_launch_at"

    fun isExpired(context: Context): Boolean {
        if (!BuildConfig.IS_TRIAL) return false
        val elapsedMs = System.currentTimeMillis() - firstLaunchAt(context)
        return elapsedMs >= trialDurationMs()
    }

    /** Días restantes de prueba, sin bajar de 0. Solo relevante si [BuildConfig.IS_TRIAL]. */
    fun daysRemaining(context: Context): Long {
        val elapsedMs = System.currentTimeMillis() - firstLaunchAt(context)
        val remainingMs = (trialDurationMs() - elapsedMs).coerceAtLeast(0)
        return TimeUnit.MILLISECONDS.toDays(remainingMs)
    }

    private fun trialDurationMs(): Long = TimeUnit.DAYS.toMillis(BuildConfig.TRIAL_DAYS.toLong())

    private fun firstLaunchAt(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getLong(KEY_FIRST_LAUNCH_AT, 0L)
        if (stored > 0L) return stored
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_FIRST_LAUNCH_AT, now).apply()
        return now
    }
}
