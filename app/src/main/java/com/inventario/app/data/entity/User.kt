package com.inventario.app.data.entity

enum class UserRole {
    CONSULTA,
    VENTAS,
    SUPERVISOR,
    ADMIN
}

fun UserRole.displayLabel(): String = when (this) {
    UserRole.ADMIN -> "Administrador"
    UserRole.SUPERVISOR -> "Supervisor"
    UserRole.VENTAS -> "Ventas"
    UserRole.CONSULTA -> "Consulta"
}

/**
 * Permisos del perfil **Supervisor** (también aplican a Admin):
 *
 * App móvil
 * - Menú Flujo Aprobación: ver y validar cierres (aprobar / rechazar / revertir).
 * - Historial de cierres: día actual + días anteriores (hasta 90 días).
 * - Reiniciar contador de pedidos confirmados del día.
 * - Generar / anular cupones de descuento (cuando el flujo lo permita).
 * - Inventario, Activar cupón, Cierre de caja, validadores de batería.
 *
 * Portal web
 * - Modo manage: listar, generar, anular y administrar códigos de descuento.
 *
 * No incluye (solo Admin):
 * - CRUD de usuarios (crear/editar/eliminar cuentas).
 * - Importar inventario Excel (si está restringido a Admin en el servidor).
 */
fun UserRole.canReviewClosings(): Boolean =
    this == UserRole.ADMIN || this == UserRole.SUPERVISOR

/** Ver historial de cierres (hoy + días anteriores) en Cierre de caja / Flujo Aprobación. */
fun UserRole.canViewClosingHistory(): Boolean =
    this == UserRole.ADMIN || this == UserRole.SUPERVISOR

/** Reiniciar el contador de pedidos confirmados del día. */
fun UserRole.canResetTodayOrders(): Boolean =
    this == UserRole.ADMIN || this == UserRole.SUPERVISOR

/** Generar / gestionar códigos de descuento (app o portal). */
fun UserRole.canManageDiscountTickets(): Boolean =
    this == UserRole.ADMIN || this == UserRole.SUPERVISOR

data class User(
    val id: Long = 0,
    val username: String,
    val role: UserRole,
    val active: Boolean = true,
    val sucursal: String = ""
)

const val PENDING_SUCURSAL_LABEL = "Sucursal pendiente por asignar"

fun User.displaySucursal(): String =
    sucursal.ifBlank { PENDING_SUCURSAL_LABEL }

fun String?.displaySucursalOrPending(): String =
    this?.takeIf { it.isNotBlank() } ?: PENDING_SUCURSAL_LABEL

fun User.sucursalPending(): Boolean = sucursal.isBlank()
