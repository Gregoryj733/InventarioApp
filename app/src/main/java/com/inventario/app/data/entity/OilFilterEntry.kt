package com.inventario.app.data.entity

/**
 * Aplicación vehículo → filtro de aceite del catálogo WEB Filtros, persistida
 * en la base SQLite local del módulo "Validador Filtro Aceite".
 */
data class OilFilterEntry(
    val id: Long,
    val marca: String,
    val modelo: String,
    val motor: String,
    val cilindrada: String,
    val anio: String,
    val categoria: String,
    val filtroCodigo: String,
    val filtroRol: String,
    val tipoFiltro: String,
    val aceitesRecomendados: List<String>,
    val alternativas: List<String>,
    val equivalencias: List<String>,
    val observaciones: String
) {
    val vehicleLabel: String
        get() = listOf(marca, modelo)
            .filter { it.isNotBlank() }
            .joinToString(" ")

    val detailLabel: String
        get() = listOfNotNull(
            motor.takeIf { it.isNotBlank() }?.let { "Motor $it" },
            cilindrada.takeIf { it.isNotBlank() }?.let { "Cil. $it" },
            anio.takeIf { it.isNotBlank() }?.let { "Año $it" }
        ).joinToString(" · ")
}
