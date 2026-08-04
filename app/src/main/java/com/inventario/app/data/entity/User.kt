package com.inventario.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class UserRole {
    CONSULTA,
    ADMIN
}

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val passwordHash: String,
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
