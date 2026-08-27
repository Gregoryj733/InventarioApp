package com.inventario.app.data.entity

enum class CashClosingStatus {
    PENDING,
    APPROVED,
    REJECTED,
    REVERTED
}

fun CashClosingStatus.displayLabel(): String = when (this) {
    CashClosingStatus.PENDING -> "Pendiente"
    CashClosingStatus.APPROVED -> "Aprobado"
    CashClosingStatus.REJECTED -> "Rechazado"
    CashClosingStatus.REVERTED -> "Revertido"
}
