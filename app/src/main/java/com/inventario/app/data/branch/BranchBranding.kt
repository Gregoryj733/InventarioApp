package com.inventario.app.data.branch

import android.content.Context

/** Normaliza IDs de sucursal (compatibilidad con versiones anteriores). */
fun normalizeBranchId(id: String?): String = when (id?.trim()?.lowercase()) {
    "sucursal_a", "total_care", "", null -> "total_care"
    "sucursal_b", "supra_parts" -> "supra_parts"
    else -> id.orEmpty().ifBlank { "total_care" }
}

/** Nombre visible de la sucursal según [sync_config.json]. */
fun branchDisplayName(branchId: String?, context: Context? = null): String {
    if (context != null) {
        val fromConfig = BranchCatalog(context).labelFor(branchId)
        if (fromConfig.isNotBlank()) return fromConfig
    }
    return when (normalizeBranchId(branchId)) {
        "supra_parts" -> "Supra Parts"
        else -> "Total Care Automotriz"
    }
}
