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
    val role: UserRole
)
