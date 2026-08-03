package com.inventario.app.data

import androidx.room.TypeConverter
import com.inventario.app.data.entity.UserRole

class Converters {
    @TypeConverter
    fun toRole(value: String): UserRole = UserRole.valueOf(value)

    @TypeConverter
    fun fromRole(role: UserRole): String = role.name
}
