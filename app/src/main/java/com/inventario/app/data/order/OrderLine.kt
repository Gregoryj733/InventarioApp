package com.inventario.app.data.order

import com.inventario.app.data.cashea.CasheaCalculator
import com.inventario.app.data.catalog.normalizeProductDescription
import com.inventario.app.data.entity.Product
import com.inventario.app.data.entity.SaleLineItem

data class OrderLine(
    val productId: Long,
    /** Identificador estable en la nube; no cambia si el inventario se refresca. */
    val productSyncId: String = "",
    val description: String,
    val unit: String,
    val unitPriceUsd: Double,
    val quantity: Double,
    val casheaLevel: CasheaCalculator.CasheaLevel? = null
) {
    val totalUsd: Double = unitPriceUsd * quantity
}

fun OrderLine.matchesProduct(product: Product): Boolean {
    if (productSyncId.isNotBlank() && productSyncId == product.syncId) return true
    if (productId == product.id) return true
    return normalizeProductDescription(description) ==
        normalizeProductDescription(product.description)
}

fun OrderLine.toSaleLineItem(saleSyncId: String, bcvRate: Double): SaleLineItem {
    val casheaDetail = casheaLevel?.let { level ->
        if (bcvRate > 0) CasheaCalculator.lineDetail(totalUsd, bcvRate, level) else null
    }
    return SaleLineItem(
        saleSyncId = saleSyncId,
        description = description,
        quantity = quantity,
        unit = unit,
        unitPriceUsd = unitPriceUsd,
        totalUsd = totalUsd,
        casheaLevelLabel = casheaDetail?.level?.label,
        casheaInitialUsd = casheaDetail?.initialUsd,
        casheaInitialBs = casheaDetail?.initialBs,
        casheaPendingUsd = casheaDetail?.pendingUsd,
        casheaPendingBs = casheaDetail?.pendingBs,
        casheaInstallments = casheaDetail?.installmentCount
    )
}
