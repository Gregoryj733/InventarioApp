package com.inventario.app.data.entity

enum class UserRole {
    CONSULTA,
    SUPERVISOR,
    ADMIN
}

fun UserRole.displayLabel(): String = when (this) {
    UserRole.ADMIN -> "Administrador"
    UserRole.SUPERVISOR -> "Supervisor"
    UserRole.CONSULTA -> "Consulta"
}

/** Roles con permiso para aprobar, rechazar o revertir cierres de caja (Flujo Aprobación). */
fun UserRole.canReviewClosings(): Boolean = this == UserRole.ADMIN || this == UserRole.SUPERVISOR

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
