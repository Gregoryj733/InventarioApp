package com.inventario.app.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Reproduce una alerta auditiva tipo notificación de mensaje (doble tono corto),
 * similar al sonido de WhatsApp.
 */
object CashClosingSoundNotifier {

    private const val TONE_DURATION_MS = 110
    private const val GAP_BETWEEN_TONES_MS = 160L

    fun play(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        val volume = (maxVolume * 0.75).toInt().coerceAtLeast(1)
        val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume)
        val handler = Handler(Looper.getMainLooper())

        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, TONE_DURATION_MS)
        handler.postDelayed({
            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, TONE_DURATION_MS)
            handler.postDelayed({
                toneGenerator.release()
            }, TONE_DURATION_MS + 40L)
        }, TONE_DURATION_MS + GAP_BETWEEN_TONES_MS)
    }
}
