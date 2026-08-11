package com.inventario.app.data.repository

import com.inventario.app.data.entity.BatteryFinderEntry
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.entity.DiscountTicket
import com.inventario.app.data.entity.Product
import com.inventario.app.data.entity.ConfirmedOrderPreview
import com.inventario.app.data.entity.SaleLineItem
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

internal fun JSONObject.putCasheaFields(line: SaleLineItem) {
    line.casheaLevelLabel?.let { put("casheaLevelLabel", it) }
    line.casheaInitialUsd?.let { put("casheaInitialUsd", it) }
    line.casheaInitialBs?.let { put("casheaInitialBs", it) }
    line.casheaPendingUsd?.let { put("casheaPendingUsd", it) }
    line.casheaPendingBs?.let { put("casheaPendingBs", it) }
    line.casheaInstallments?.let { put("casheaInstallments", it) }
}

internal fun JSONObject.toSaleLineItem(saleSyncId: String? = null): SaleLineItem? {
    val resolvedSaleSyncId = saleSyncId
        ?: optString("saleSyncId").takeIf { it.isNotBlank() }
        ?: return null
    val description = optString("description").takeIf { it.isNotBlank() } ?: return null
    val quantity = optDouble("quantity", Double.NaN)
    if (quantity.isNaN() || quantity <= 0) return null
    return SaleLineItem(
        saleSyncId = resolvedSaleSyncId,
        description = description,
        quantity = quantity,
        unit = optString("unit", "UNIDAD"),
        unitPriceUsd = optDouble("unitPriceUsd", 0.0),
        totalUsd = optDouble("totalUsd", 0.0),
        casheaLevelLabel = optString("casheaLevelLabel").takeIf { it.isNotBlank() },
        casheaInitialUsd = optDoubleOrNull("casheaInitialUsd"),
        casheaInitialBs = optDoubleOrNull("casheaInitialBs"),
        casheaPendingUsd = optDoubleOrNull("casheaPendingUsd"),
        casheaPendingBs = optDoubleOrNull("casheaPendingBs"),
        casheaInstallments = optInt("casheaInstallments", -1).takeIf { it > 0 }
    )
}

internal fun JSONArray.toSaleLineItemList(): List<SaleLineItem> {
    val items = mutableListOf<SaleLineItem>()
    for (i in 0 until length()) {
        val json = optJSONObject(i) ?: continue
        val item = json.toSaleLineItem() ?: continue
        items.add(item)
    }
    return items
}

internal fun buildConfirmedOrderPreviews(
    sales: List<SaleRecord>,
    lineItems: List<SaleLineItem>
): List<ConfirmedOrderPreview> {
    val linesBySale = lineItems.groupBy { it.saleSyncId }
    return sales
        .sortedByDescending { it.createdAt }
        .map { sale ->
            ConfirmedOrderPreview(
                syncId = sale.syncId,
                createdAt = sale.createdAt,
                totalUsd = sale.totalUsd,
                bcvRate = sale.bcvRate,
                lines = linesBySale[sale.syncId].orEmpty()
            )
        }
}

internal fun ConfirmedOrderPreview.toJsonObject(): JSONObject = JSONObject().apply {
    put("syncId", syncId)
    put("createdAt", createdAt)
    put("totalUsd", totalUsd)
    put("bcvRate", bcvRate)
    put(
        "lines",
        JSONArray().apply {
            lines.forEach { line ->
                put(
                    JSONObject().apply {
                        put("saleSyncId", line.saleSyncId)
                        put("description", line.description)
                        put("quantity", line.quantity)
                        put("unit", line.unit)
                        put("unitPriceUsd", line.unitPriceUsd)
                        put("totalUsd", line.totalUsd)
                        putCasheaFields(line)
                    }
                )
            }
        }
    )
}

internal fun JSONArray.toConfirmedOrderPreviewList(): List<ConfirmedOrderPreview> {
    val orders = mutableListOf<ConfirmedOrderPreview>()
    for (i in 0 until length()) {
        val json = optJSONObject(i) ?: continue
        val syncId = json.optString("syncId").takeIf { it.isNotBlank() } ?: continue
        val createdAt = json.optLong("createdAt", 0L)
        if (createdAt <= 0L) continue
        val lineItems = json.optJSONArray("lines")?.toSaleLineItemList().orEmpty()
        orders.add(
            ConfirmedOrderPreview(
                syncId = syncId,
                createdAt = createdAt,
                totalUsd = json.optDouble("totalUsd", 0.0),
                bcvRate = json.optDouble("bcvRate", 0.0),
                lines = lineItems
            )
        )
    }
    return orders
}

internal fun mergeConfirmedOrderPreviews(
    serverOrders: List<ConfirmedOrderPreview>,
    localOrders: List<ConfirmedOrderPreview>
): List<ConfirmedOrderPreview> {
    if (localOrders.isEmpty()) return serverOrders
    val localBySyncId = localOrders.associateBy { it.syncId }
    val merged = serverOrders.map { order ->
        if (order.lines.isNotEmpty()) {
            order
        } else {
            localBySyncId[order.syncId]?.let { local -> order.copy(lines = local.lines) } ?: order
        }
    }
    val serverIds = serverOrders.map { it.syncId }.toSet()
    val localOnly = localOrders.filter { it.syncId !in serverIds }
    return (merged + localOnly).sortedByDescending { it.createdAt }
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

internal fun JSONObject.toDiscountTicket(): DiscountTicket = DiscountTicket(
    code = optString("code"),
    discountPercent = optDouble("discountPercent", 0.0),
    issuedAt = optLong("issuedAt"),
    activatedAt = optLongOrNull("activatedAt"),
    expiresAt = optLongOrNull("expiresAt"),
    status = optString("status", "ISSUED"),
    displayStatus = optString("displayStatus", optString("status", "ISSUED")),
    usedAt = optLongOrNull("usedAt"),
    usedBySaleSyncId = optString("usedBySaleSyncId").takeIf { it.isNotBlank() },
    issuedByUsername = optString("issuedByUsername"),
    sourceSaleSyncId = optString("sourceSaleSyncId").takeIf { it.isNotBlank() },
    telefonoEjecucion = optString("telefono_ejecucion").takeIf { it.isNotBlank() },
    fechaEjecucion = optLongOrNull("fecha_ejecucion")
)

internal fun JSONArray.toDiscountTicketList(): List<DiscountTicket> {
    val tickets = mutableListOf<DiscountTicket>()
    for (i in 0 until length()) {
        val json = optJSONObject(i) ?: continue
        tickets.add(json.toDiscountTicket())
    }
    return tickets
}

internal fun JSONArray.toBatteryFinderList(): List<BatteryFinderEntry> {
    val entries = mutableListOf<BatteryFinderEntry>()
    for (i in 0 until length()) {
        val json = optJSONObject(i) ?: continue
        val attributes = json.optJSONObject("attributes")
        val marca = json.optString("marca").takeIf { it.isNotBlank() }
            ?: attributes?.optString("attribute_pa_marca")?.takeIf { it.isNotBlank() }
            ?: continue
        val modelo = json.optString("modelo").takeIf { it.isNotBlank() }
            ?: attributes?.optString("attribute_pa_modelo")?.takeIf { it.isNotBlank() }
            ?: continue
        val anio = json.optString("anio").takeIf { it.isNotBlank() }
            ?: attributes?.optString("attribute_pa_ano")?.takeIf { it.isNotBlank() }
            ?: continue
        val bateria = json.optString("bateria").takeIf { it.isNotBlank() }
            ?: attributes?.optString("attribute_pa_bateria")?.takeIf { it.isNotBlank() }
            ?: continue
        entries.add(
            BatteryFinderEntry(
                marca = marca.trim().lowercase(),
                modelo = modelo.trim().lowercase(),
                anio = anio.trim(),
                bateria = bateria.trim().lowercase()
            )
        )
    }
    return entries
}
