package com.inventario.app.data

import androidx.room.TypeConverter
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.entity.UserRole

class Converters {
    @TypeConverter
    fun toRole(value: String): UserRole = UserRole.valueOf(value)

    @TypeConverter
    fun fromRole(role: UserRole): String = role.name

    @TypeConverter
    fun toCashClosingStatus(value: String): CashClosingStatus = CashClosingStatus.valueOf(value)

    @TypeConverter
    fun fromCashClosingStatus(status: CashClosingStatus): String = status.name
}
