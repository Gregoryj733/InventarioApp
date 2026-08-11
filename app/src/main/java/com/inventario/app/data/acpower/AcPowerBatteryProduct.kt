package com.inventario.app.data.acpower

/**
 * Producto AC POWER devuelto por la búsqueda por vehículo o por código de batería
 * (misma información que muestra acpowervzla.com en «Encuentra tu batería»).
 */
data class AcPowerBatteryProduct(
    val name: String,
    val code: String,
    val line: String?,
    val imageUrl: String?,
    val features: List<String>
)
