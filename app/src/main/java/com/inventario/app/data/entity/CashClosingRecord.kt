package com.inventario.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cash_closing_records",
    indices = [Index("closedAt"), Index("username")]
)
data class CashClosingRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val branchName: String,
    val dateText: String,
    val closedAt: Long,
    val rate: Double,
    val salesUsd: Double,
    val salesBs: Double,
    val grandTotalUsd: Double,
    val grandTotalBs: Double,
    val differenceUsd: Double,
    val hasDifference: Boolean,
    val username: String,
    val observations: String = "",
    val status: CashClosingStatus = CashClosingStatus.PENDING,
    val revisionNumber: Int = 1,
    val reviewedBy: String = "",
    val reviewedAt: Long = 0L,
    val userSucursal: String = "",
    val detailSnapshot: String = ""
)
