package com.inventario.app.util

import android.content.Context
import android.content.Intent
import android.widget.Toast

object WhatsAppNotifier {
    const val GROUP_NAME = "Control Interno"

    /**
     * Abre WhatsApp para elegir contacto o grupo (incluye "Control Interno")
     * y enviar el mensaje del pedido.
     */
    fun shareToGroupChooser(context: Context, message: String) {
        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        val waIntent = Intent(baseIntent).apply { setPackage("com.whatsapp") }
        val waBusinessIntent = Intent(baseIntent).apply { setPackage("com.whatsapp.w4b") }

        when {
            waIntent.resolveActivity(context.packageManager) != null -> {
                context.startActivity(waIntent)
            }
            waBusinessIntent.resolveActivity(context.packageManager) != null -> {
                context.startActivity(waBusinessIntent)
            }
            else -> {
                val chooser = Intent.createChooser(baseIntent, "Compartir pedido")
                if (chooser.resolveActivity(context.packageManager) != null) {
                    context.startActivity(chooser)
                } else {
                    Toast.makeText(
                        context,
                        "Instala WhatsApp para enviar al grupo $GROUP_NAME.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
