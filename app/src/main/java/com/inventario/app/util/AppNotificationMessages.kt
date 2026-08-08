package com.inventario.app.util

object AppNotificationMessages {

    fun paymentChoiceRequired(): Pair<String, String> =
        "Forma de pago requerida" to
            "Seleccione Pago móvil / Punto o un nivel Cashea para continuar."

    fun paymentChoiceUpdated(label: String): Pair<String, String> =
        "Forma de pago actualizada" to
            "Se aplicó: $label."

    fun orderConfirmedSelf(): Pair<String, String> =
        "Pedido confirmado" to
            "El pedido se registró correctamente y el stock fue actualizado."

    fun orderConfirmedRemote(totalUsd: String, time: String): Pair<String, String> =
        "Nuevo pedido confirmado" to
            "Se registró un pedido a las $time por un total de $totalUsd."

    fun cashClosingSubmittedSelf(): Pair<String, String> =
        "Cierre de caja enviado" to
            "Tu cierre quedó pendiente de aprobación por un supervisor."

    fun cashClosingSubmittedRemote(username: String, branch: String): Pair<String, String> =
        "Nuevo cierre de caja" to
            "$username envió un cierre${branch.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""} " +
            "pendiente de aprobación."

    fun cashClosingApproved(): Pair<String, String> =
        "Cierre aprobado" to
            "Tu cierre de caja fue aprobado y contabilizado."

    fun cashClosingRejected(): Pair<String, String> =
        "Cierre rechazado" to
            "Tu cierre fue rechazado. Corrígelo y vuelve a enviarlo desde Cierre de caja."

    fun cashClosingReversed(): Pair<String, String> =
        "Cierre revertido" to
            "Tu cierre aprobado fue revertido. Puedes registrar un cierre nuevo."

    fun cashClosingApprovedByReviewer(): Pair<String, String> =
        "Cierre aprobado" to
            "El cierre fue aprobado y contabilizado en reportes."

    fun cashClosingRejectedByReviewer(): Pair<String, String> =
        "Cierre rechazado" to
            "El cierre fue rechazado. El usuario deberá volver a ejecutarlo."

    fun cashClosingRevertedByReviewer(): Pair<String, String> =
        "Cierre revertido" to
            "El cierre fue revertido y ya no cuenta en la recaudación total."
}
