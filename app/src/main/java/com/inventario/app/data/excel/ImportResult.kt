package com.inventario.app.data.excel

/**
 * Resultado de importar un Excel de inventario. El parseo ahora ocurre en el
 * servidor (multipart a /v1/inventory/import); este archivo solo mantiene el
 * contrato de datos que consume la UI.
 */
data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val errors: List<String>
)
