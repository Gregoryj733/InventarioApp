package com.inventario.app.data.repository

import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.entity.Product
import com.inventario.app.data.entity.SaleRecord
import com.inventario.app.data.entity.User
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.entity.stableProductId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversión JSON <-> entidades compartida entre repositorios. Todas las
 * pantallas obtienen sus datos exclusivamente a partir de estas funciones,
 * que a su vez solo se alimentan de respuestas del sync-server.
 */

internal fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key)
}

internal fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key)
}

internal fun JSONArray.toProductList(): List<Product> {
    val products = mutableListOf<Product>()
    for (i in 0 until length()) {
        val json = optJSONObject(i) ?: continue
        val syncId = json.optString("syncId").takeIf { it.isNotBlank() } ?: continue
        val description = json.optString("description").takeIf { it.isNotBlank() } ?: continue
        val quantity = json.optDouble("quantity", Double.NaN)
        if (quantity.isNaN()) continue
        val unit = json.optString("unit", "UNIDAD")
        val price = json.optDouble("price", Double.NaN)
        if (price.isNaN()) continue
        val updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
        products.add(
            Product(
                id = stableProductId(syncId),
                syncId = syncId,
                description = description,
                quantity = quantity,
                unit = unit,
                price = price,
                updatedAt = updatedAt
            )
        )
    }
    return products.sortedBy { it.description }
}

internal fun JSONArray.toSaleList(): List<SaleRecord> {
    val sales = mutableListOf<SaleRecord>()
    for (i in 0 until length()) {
        val json = optJSONObject(i) ?: continue
        val syncId = json.optString("syncId").takeIf { it.isNotBlank() } ?: continue
        val createdAt = json.optLong("createdAt", 0L)
        if (createdAt <= 0L) continue
        sales.add(
            SaleRecord(
                syncId = syncId,
                createdAt = createdAt,
                totalUsd = json.optDouble("totalUsd", 0.0),
                bcvRate = json.optDouble("bcvRate", 0.0)
            )
        )
    }
    return sales
}

internal fun JSONObject.toCashClosingRecord(): CashClosingRecord = CashClosingRecord(
    id = optLong("id"),
    branchName = optString("branchName"),
    dateText = optString("dateText"),
    closedAt = optLong("closedAt"),
    rate = optDouble("rate", 0.0),
    salesUsd = optDouble("salesUsd", 0.0),
    salesBs = optDouble("salesBs", 0.0),
    grandTotalUsd = optDouble("grandTotalUsd", 0.0),
    grandTotalBs = optDouble("grandTotalBs", 0.0),
    differenceUsd = optDouble("differenceUsd", 0.0),
    hasDifference = optBoolean("hasDifference", false),
    username = optString("username"),
    observations = optString("observations", ""),
    status = runCatching { CashClosingStatus.valueOf(optString("status", "PENDING")) }
        .getOrDefault(CashClosingStatus.PENDING),
    revisionNumber = optInt("revisionNumber", 1),
    reviewedBy = optString("reviewedBy", ""),
    reviewedAt = optLong("reviewedAt", 0L),
    userSucursal = optString("userSucursal", ""),
    detailSnapshot = optString("detailSnapshot", "")
)

internal fun JSONArray.toCashClosingList(): List<CashClosingRecord> {
    val closings = mutableListOf<CashClosingRecord>()
    for (i in 0 until length()) {
        val json = optJSONObject(i) ?: continue
        closings.add(json.toCashClosingRecord())
    }
    return closings
}

internal fun CashClosingRecord.toJsonBody(): JSONObject = JSONObject().apply {
    put("branchName", branchName)
    put("dateText", dateText)
    put("closedAt", closedAt)
    put("rate", rate)
    put("salesUsd", salesUsd)
    put("salesBs", salesBs)
    put("grandTotalUsd", grandTotalUsd)
    put("grandTotalBs", grandTotalBs)
    put("differenceUsd", differenceUsd)
    put("hasDifference", hasDifference)
    put("username", username)
    put("observations", observations)
    put("userSucursal", userSucursal)
    put("detailSnapshot", detailSnapshot)
}

/**
 * El servidor siempre guarda el rol en mayúsculas, pero se normaliza aquí
 * (trim + uppercase) como defensa adicional: una cuenta ya existente con el
 * rol en otro formato (p. ej. datos migrados a mano) no debe degradar
 * silenciosamente a CONSULTA solo porque `UserRole.valueOf` es sensible a
 * mayúsculas.
 */
internal fun JSONObject.toUser(): User = User(
    id = optLong("id"),
    username = optString("username"),
    role = runCatching {
        UserRole.valueOf(optString("role", "CONSULTA").trim().uppercase())
    }.getOrDefault(UserRole.CONSULTA),
    active = optBoolean("active", true),
    sucursal = optString("sucursal", "")
)

internal fun JSONArray.toUserList(): List<User> {
    val users = mutableListOf<User>()
    for (i in 0 until length()) {
        val json = optJSONObject(i) ?: continue
        users.add(json.toUser())
    }
    return users.sortedBy { it.username }
}
