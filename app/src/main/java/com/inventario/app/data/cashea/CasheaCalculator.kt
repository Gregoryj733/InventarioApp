package com.inventario.app.data.cashea

import kotlin.math.round

object CasheaCalculator {
    const val IVA_RATE = 0.16
    const val MINIMUM_PURCHASE_USD = 25.0

    fun isCasheaEligible(baseUsd: Double): Boolean = baseUsd >= MINIMUM_PURCHASE_USD

    fun casheaEligibilityMessage(baseUsd: Double): String? {
        if (isCasheaEligible(baseUsd)) return null
        return "El monto debe ser igual o superior a $${MINIMUM_PURCHASE_USD.toInt()} USD " +
            "para realizar compra por Cashea. Monto actual: $${formatUsd(baseUsd)}."
    }

    private fun formatUsd(value: Double): String {
        val rounded = roundMoney(value)
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }

    enum class CasheaLevel(val label: String, val percent: Double) {
        NIVEL_1("Nivel 1 (60%)", 0.60),
        NIVEL_2("Nivel 2 (50%)", 0.50),
        NIVEL_3_6("Nivel 3-6 (40%)", 0.40)
    }

    data class CasheaLevelAmount(
        val level: CasheaLevel,
        val usd: Double,
        val bs: Double
    )

    data class FiscalBreakdown(
        val totalUsd: Double,
        val baseBs: Double,
        val ivaBs: Double,
        val totalBs: Double
    )

    data class CasheaSimulation(
        val baseUsd: Double,
        val rate: Double,
        val quantity: Double,
        val pagoMovilSinIvaUsd: Double,
        val pagoMovilSinIvaBs: Double,
        val pagoCasheaConIvaUsd: Double,
        val pagoCasheaConIvaBs: Double,
        val casheaLevels: List<CasheaLevelAmount>,
        val fiscalSinIva: FiscalBreakdown,
        val fiscalConIva: FiscalBreakdown
    )

    data class CasheaLineDetail(
        val level: CasheaLevel,
        val totalWithIvaUsd: Double,
        val totalWithIvaBs: Double,
        val initialUsd: Double,
        val initialBs: Double,
        val pendingUsd: Double,
        val pendingBs: Double,
        val installmentCount: Int = 2
    ) {
        val pendingPercent: Int
            get() = ((1.0 - level.percent) * 100).toInt()
    }

    fun lineDetail(baseUsd: Double, rate: Double, level: CasheaLevel): CasheaLineDetail? {
        if (!isCasheaEligible(baseUsd) || rate <= 0) return null
        val simulation = simulate(baseUsd, rate) ?: return null
        val levelAmount = simulation.casheaLevels.find { it.level == level } ?: return null
        return CasheaLineDetail(
            level = level,
            totalWithIvaUsd = simulation.pagoCasheaConIvaUsd,
            totalWithIvaBs = simulation.pagoCasheaConIvaBs,
            initialUsd = levelAmount.usd,
            initialBs = levelAmount.bs,
            pendingUsd = roundMoney(simulation.pagoCasheaConIvaUsd - levelAmount.usd),
            pendingBs = roundMoney(simulation.pagoCasheaConIvaBs - levelAmount.bs)
        )
    }

    fun simulate(baseUsd: Double, rate: Double, quantity: Double = 1.0): CasheaSimulation? {
        if (baseUsd <= 0 || rate <= 0 || quantity <= 0) return null

        val totalSinIvaUsd = roundMoney(baseUsd)
        val totalSinIvaBs = roundMoney(totalSinIvaUsd * rate)

        val conIvaUsd = roundMoney(totalSinIvaUsd * (1 + IVA_RATE))
        val conIvaBs = roundMoney(conIvaUsd * rate)

        val casheaLevels = CasheaLevel.entries.map { level ->
            val levelUsd = roundMoney(conIvaUsd * level.percent)
            CasheaLevelAmount(
                level = level,
                usd = levelUsd,
                bs = roundMoney(levelUsd * rate)
            )
        }

        val fiscalSinIvaBaseBs = roundMoney(totalSinIvaBs / (1 + IVA_RATE))
        val fiscalSinIvaIvaBs = roundMoney(fiscalSinIvaBaseBs * IVA_RATE)
        val fiscalSinIva = FiscalBreakdown(
            totalUsd = totalSinIvaUsd,
            baseBs = fiscalSinIvaBaseBs,
            ivaBs = fiscalSinIvaIvaBs,
            totalBs = totalSinIvaBs
        )

        val fiscalConIvaBaseBs = totalSinIvaBs
        val fiscalConIvaIvaBs = roundMoney(fiscalConIvaBaseBs * IVA_RATE)
        val fiscalConIva = FiscalBreakdown(
            totalUsd = conIvaUsd,
            baseBs = fiscalConIvaBaseBs,
            ivaBs = fiscalConIvaIvaBs,
            totalBs = conIvaBs
        )

        return CasheaSimulation(
            baseUsd = totalSinIvaUsd,
            rate = rate,
            quantity = quantity,
            pagoMovilSinIvaUsd = totalSinIvaUsd,
            pagoMovilSinIvaBs = totalSinIvaBs,
            pagoCasheaConIvaUsd = conIvaUsd,
            pagoCasheaConIvaBs = conIvaBs,
            casheaLevels = casheaLevels,
            fiscalSinIva = fiscalSinIva,
            fiscalConIva = fiscalConIva
        )
    }

    private fun roundMoney(value: Double): Double = round(value * 100) / 100.0
}
