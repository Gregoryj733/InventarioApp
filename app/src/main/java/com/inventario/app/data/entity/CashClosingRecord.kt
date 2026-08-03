package com.inventario.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cash_closing_records",
    indices = [Index("closedAt")]
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
    val observations: String = ""
)
