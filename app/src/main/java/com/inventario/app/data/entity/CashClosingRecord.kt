package com.inventario.app.data.entity

data class CashClosingRecord(
    val id: Long = 0,
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
