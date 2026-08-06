package com.inventario.app.data.entity

/**
 * Un renglón del catálogo de compatibilidad vehículo -> batería usado por el
 * módulo "Validar Batería" (copia de la herramienta "Encuentra fácilmente la
 * batería que tu vehículo necesita" de duncan.com.ve). [marca]/[modelo] se
 * guardan en minúscula tal como llegan del servidor; la UI los formatea para
 * mostrarlos en mayúsculas, igual que en el sitio de origen.
 */
data class BatteryFinderEntry(
    val marca: String,
    val modelo: String,
    val anio: String,
    val bateria: String
)
