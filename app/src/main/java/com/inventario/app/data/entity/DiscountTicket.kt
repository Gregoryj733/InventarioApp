package com.inventario.app.data.entity

import java.util.concurrent.TimeUnit

/** Vigencia fija del ticket de descuento: 30 días desde la emisión. */
val DISCOUNT_TICKET_VALIDITY_MS: Long = TimeUnit.DAYS.toMillis(30)

const val DISCOUNT_TICKET_CONDITIONS =
    "Válido por 30 días desde su emisión. Aplica a lubricantes, accesorios y repuestos. " +
        "Un solo uso, no acumulable con otras promociones."

object DiscountTicketStatus {
    const val ACTIVE = "ACTIVE"
    const val USED = "USED"
    const val VOIDED = "VOIDED"
}

data class DiscountTicket(
    val code: String,
    val customerName: String,
    val customerPhone: String,
    val discountPercent: Double,
    val issuedAt: Long,
    val expiresAt: Long,
    val status: String,
    val usedAt: Long? = null,
    val usedBySaleSyncId: String? = null,
    val issuedByUsername: String = "",
    val sourceSaleSyncId: String? = null
)

fun DiscountTicket.isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAt <= now

fun DiscountTicket.isUsed(): Boolean = status == DiscountTicketStatus.USED

fun DiscountTicket.isVoided(): Boolean = status == DiscountTicketStatus.VOIDED

/** Un ticket solo puede aplicarse a una venta si sigue activo y no venció. */
fun DiscountTicket.isRedeemable(now: Long = System.currentTimeMillis()): Boolean =
    status == DiscountTicketStatus.ACTIVE && !isExpired(now)

/** Estado legible para el usuario, considerando la expiración calculada en el cliente. */
fun DiscountTicket.displayStatus(now: Long = System.currentTimeMillis()): String = when {
    isVoided() -> "Anulado"
    isUsed() -> "Usado"
    isExpired(now) -> "Expirado"
    else -> "Activo"
}
