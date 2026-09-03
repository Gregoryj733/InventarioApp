package com.inventario.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast

object WhatsAppNotifier {
    const val GROUP_NAME = "Control Interno"

    /**
     * Abre WhatsApp para elegir contacto o grupo (incluye "Control Interno")
     * y enviar el mensaje del pedido o cierre de caja.
     */
    fun shareToGroupChooser(context: Context, message: String) {
        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }

        for (packageName in WHATSAPP_PACKAGES) {
            if (tryLaunch(context, Intent(baseIntent).setPackage(packageName))) return
        }

        val chooser = Intent.createChooser(baseIntent, "Compartir pedido")
        if (tryLaunch(context, chooser)) return

        Toast.makeText(
            context,
            "Instala WhatsApp para enviar al grupo $GROUP_NAME.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun tryLaunch(context: Context, intent: Intent): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
}
