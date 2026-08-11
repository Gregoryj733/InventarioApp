package com.inventario.app.data.entity

import java.util.concurrent.TimeUnit

/** Vigencia del cupón: 30 días desde la activación en la app móvil. */
val DISCOUNT_TICKET_VALIDITY_MS: Long = TimeUnit.DAYS.toMillis(30)

const val DISCOUNT_TICKET_CONDITIONS =
    "Válido por 30 días desde su activación. Aplica a lubricantes, accesorios y repuestos. " +
        "Un solo uso, no acumulable con otras promociones."

object DiscountTicketStatus {
    const val ISSUED = "ISSUED"
    const val ACTIVE = "ACTIVE"
    const val USED = "USED"
    const val VOIDED = "VOIDED"
}

data class DiscountTicket(
    val code: String,
    val discountPercent: Double,
    val issuedAt: Long,
    val activatedAt: Long? = null,
    val expiresAt: Long? = null,
    val status: String,
    val displayStatus: String = status,
    val usedAt: Long? = null,
    val usedBySaleSyncId: String? = null,
    val issuedByUsername: String = "",
    val sourceSaleSyncId: String? = null,
    val telefonoEjecucion: String? = null,
    val fechaEjecucion: Long? = null
)

fun DiscountTicket.isIssued(): Boolean =
    status == DiscountTicketStatus.ISSUED || displayStatus == DiscountTicketStatus.ISSUED

fun DiscountTicket.isExpired(now: Long = System.currentTimeMillis()): Boolean =
    displayStatus == "EXPIRED" || (expiresAt != null && expiresAt <= now)

fun DiscountTicket.isUsed(): Boolean = status == DiscountTicketStatus.USED

/** Ejecutado en app (teléfono capturado) pero aún sin venta vinculada. */
fun DiscountTicket.isExecutedPendingSale(): Boolean =
    status == DiscountTicketStatus.USED && usedBySaleSyncId.isNullOrBlank()

/** Cupón consumido por completo (ejecutado y vinculado a una venta). */
fun DiscountTicket.isFullyConsumed(): Boolean =
    status == DiscountTicketStatus.USED && !usedBySaleSyncId.isNullOrBlank()

fun DiscountTicket.isVoided(): Boolean = status == DiscountTicketStatus.VOIDED

/** Solo cupones activos pueden ejecutarse (segundo escaneo con teléfono). */
fun DiscountTicket.isExecutable(now: Long = System.currentTimeMillis()): Boolean =
    status == DiscountTicketStatus.ACTIVE && !isExpired(now)

/** Cupón listo para aplicar al carrito: activo (pendiente de ejecutar) o ya ejecutado sin venta. */
fun DiscountTicket.isRedeemable(now: Long = System.currentTimeMillis()): Boolean =
    isExecutable(now) || isExecutedPendingSale()

fun DiscountTicket.displayStatusLabel(now: Long = System.currentTimeMillis()): String = when {
    isVoided() -> "Anulado"
    isUsed() -> "Usado"
    isIssued() -> "Sin activar"
    isExpired(now) -> "Expirado"
    status == DiscountTicketStatus.ACTIVE -> "Activo"
    else -> displayStatus
}
