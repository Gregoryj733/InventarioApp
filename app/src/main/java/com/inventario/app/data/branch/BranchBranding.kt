package com.inventario.app.data.branch

import android.content.Context

/** Normaliza IDs de sucursal (compatibilidad con versiones anteriores). */
fun normalizeBranchId(id: String?): String = when (id?.trim()?.lowercase()) {
    "sucursal_a", "total_care" -> "total_care"
    "sucursal_b", "supra_parts" -> "supra_parts"
    "", null -> "supra_parts"
    else -> id.orEmpty().ifBlank { "supra_parts" }
}

/** Nombre visible de la sucursal según [sync_config.json] (etiqueta corta en UI). */
fun branchDisplayName(branchId: String?, context: Context? = null): String {
    if (context != null) {
        val fromConfig = BranchCatalog(context).displayLabelFor(branchId)
        if (fromConfig.isNotBlank()) return fromConfig
    }
    return when (normalizeBranchId(branchId)) {
        "total_care" -> "S2 (Total Care Automotriz)"
        else -> "S1 (Supra Parts)"
    }
}
