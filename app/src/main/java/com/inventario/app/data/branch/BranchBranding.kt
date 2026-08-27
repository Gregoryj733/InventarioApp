package com.inventario.app.data.branch

/** Normaliza IDs de sucursal (compatibilidad con versiones anteriores). */
fun normalizeBranchId(id: String?): String = when (id?.trim()?.lowercase()) {
    "sucursal_a", "total_care", "", null -> "total_care"
    "sucursal_b", "supra_parts" -> "supra_parts"
    else -> id.orEmpty().ifBlank { "total_care" }
}

fun branchDisplayName(branchId: String?): String = when (normalizeBranchId(branchId)) {
    "supra_parts" -> "Supra Parts"
    else -> "Total Care Automotriz"
}
