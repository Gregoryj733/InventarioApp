package com.inventario.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.inventario.app.R

const val INVENTORY_NOTIFICATION_CHANNEL_ID = "inventario_actualizado"
const val INVENTORY_UPDATED_TOPIC = "inventario_actualizado"

object NotificationHelper {
    private const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            INVENTORY_NOTIFICATION_CHANNEL_ID,
            "Actualizaciones de inventario",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Avisa cuando el administrador actualiza el inventario desde Excel."
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun showInventoryUpdated(context: Context, title: String, body: String) {
        val notification = NotificationCompat.Builder(context, INVENTORY_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
