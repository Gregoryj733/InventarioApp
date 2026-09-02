package com.inventario.app.data.entity

import java.util.Calendar

/** Usuario gerente en el servidor (rol SUPERVISOR, acceso multi-sucursal). */
const val GERENTE_USERNAME = "gerente"

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
 * - Exportar historial de cierres a Excel desde Cierre de caja y Flujo Aprobación.
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

/** Exportar historial de cierres a Excel (.xlsx) en Cierre de caja y Flujo Aprobación. */
fun UserRole.canExportClosingHistory(): Boolean =
    this == UserRole.ADMIN || this == UserRole.SUPERVISOR

/** Reiniciar el contador de pedidos confirmados del día. */
fun UserRole.canResetTodayOrders(): Boolean =
    this == UserRole.ADMIN || this == UserRole.SUPERVISOR

/** Generar / gestionar códigos de descuento (app o portal). */
fun UserRole.canManageDiscountTickets(): Boolean =
    this == UserRole.ADMIN || this == UserRole.SUPERVISOR

/** Cambiar entre sucursales (cada una con su propia instancia sync-server). */
fun UserRole.canSwitchBranch(): Boolean =
    this == UserRole.ADMIN || this == UserRole.SUPERVISOR

/**
 * Perfiles restringidos a una sola sucursal (sin cruce de pedidos ni datos).
 * [UserRole.CONSULTA] y [UserRole.VENTAS] solo ven la instancia donde iniciaron sesión.
 */
fun UserRole.isBranchRestricted(): Boolean = !canSwitchBranch()

/** Ventas del día en el menú principal (KPIs por sucursal). Solo Admin / Supervisor. */
fun UserRole.canViewBranchSalesKpis(): Boolean = canSwitchBranch()

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

fun String?.isGerenteProfile(): Boolean =
    this?.trim()?.equals(GERENTE_USERNAME, ignoreCase = true) == true

/** Gerente o Admin: deben recibir el recordatorio de respaldo del Excel de cierres. */
fun shouldReceiveClosingExcelReminder(role: UserRole?, username: String?): Boolean =
    username.isGerenteProfile() || role == UserRole.ADMIN

const val CLOSING_EXCEL_REMINDER_MESSAGE =
    "El día de hoy, debe descargar el Excel de cierre de caja antes de que se borre el histórico. " +
        "Por seguridad, la descarga se realiza dos veces por semana."

/** Miércoles y sábado: recordatorio de descarga de Excel antes de limpiar histórico. */
fun isClosingExcelReminderDay(): Boolean {
    return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
        Calendar.WEDNESDAY, Calendar.SATURDAY -> true
        else -> false
    }
}

fun shouldShowClosingExcelReminder(
    role: UserRole?,
    username: String,
    hasExportedToday: Boolean
): Boolean =
    shouldReceiveClosingExcelReminder(role, username) &&
        isClosingExcelReminderDay() &&
        !hasExportedToday
