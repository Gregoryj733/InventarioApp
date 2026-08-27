package com.inventario.app.data.branch

/**
 * Tema visual por sucursal. Cada entrada en [sync_config.json] declara su `theme`;
 * por defecto coincide con el `id` de la sucursal.
 */
enum class BranchTheme(val id: String) {
    TOTAL_CARE("total_care"),
    SUPRA_PARTS("supra_parts");

    companion object {
        fun fromId(branchId: String?): BranchTheme = when (normalizeBranchId(branchId)) {
            SUPRA_PARTS.id -> SUPRA_PARTS
            else -> TOTAL_CARE
        }

        fun fromConfig(config: BranchConfig?): BranchTheme =
            fromId(config?.theme?.ifBlank { config.id } ?: config?.id)
    }
}
