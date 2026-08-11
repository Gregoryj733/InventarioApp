package com.inventario.app.data.battery

import com.inventario.app.data.cashea.CasheaCalculator
import com.inventario.app.data.entity.Product

data class BatteryStockMatch(
    val inStock: Boolean,
    val product: Product? = null,
    val priceMessage: String? = null
)

data class BatteryRecommendation(
    val code: String,
    val stockMatch: BatteryStockMatch
)

object BatteryInventoryMatcher {

    fun match(validatorAmperage: Int?, products: List<Product>): BatteryStockMatch {
        if (validatorAmperage == null) {
            return BatteryStockMatch(inStock = false)
        }
        val product = products.firstOrNull { candidate ->
            candidate.quantity > 0 &&
                BatteryAmperage.fromProductDescription(candidate.description) == validatorAmperage
        }
        return BatteryStockMatch(
            inStock = product != null,
            product = product,
            priceMessage = product?.let(::priceMessageFor)
        )
    }

    fun recommend(code: String, products: List<Product>): BatteryRecommendation =
        BatteryRecommendation(
            code = code,
            stockMatch = match(BatteryAmperage.fromBatteryCode(code), products)
        )

    private fun priceMessageFor(product: Product): String =
        if (CasheaCalculator.isCasheaEligible(product.price)) {
            "Precio por Cashea"
        } else {
            "Precio al contacto"
        }
}
