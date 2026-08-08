package com.inventario.app.data.order

import com.inventario.app.data.cashea.CasheaCalculator

sealed class ProductPaymentChoice {
    data object PagoMovil : ProductPaymentChoice()
    data class Cashea(val level: CasheaCalculator.CasheaLevel) : ProductPaymentChoice()
}
