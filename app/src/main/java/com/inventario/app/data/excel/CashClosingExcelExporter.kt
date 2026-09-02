package com.inventario.app.data.excel

import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingSnapshot
import com.inventario.app.data.entity.CashClosingSnapshotCodec
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.entity.displaySalesDiscountUsd
import com.inventario.app.data.entity.displaySalesGrossUsd
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

object CashClosingExcelExporter {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es", "VE"))
    private val timeFormat = SimpleDateFormat("HH:mm", Locale("es", "VE"))
    private val fileStampFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
    private val veDecimalFormat = NumberFormat.getNumberInstance(Locale("es", "VE")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        isGroupingUsed = true
    }

    data class PaymentBreakdown(
        val posUsd: Double,
        val posBs: Double,
        val mobileUsd: Double,
        val mobileBs: Double,
        val cashUsd: Double,
        val cashBs: Double,
        val casheaUsd: Double,
        val casheaBs: Double,
        val expenseUsd: Double
    )

    fun suggestedFileName(): String =
        "cierres_caja_${fileStampFormat.format(Date())}.xlsx"

    fun export(
        closings: List<CashClosingRecord>,
        statusLabel: (CashClosingStatus) -> String
    ): ByteArray {
        require(closings.isNotEmpty()) { "No hay cierres para exportar." }

        val summaryHeaders = listOf(
            "Fecha del cierre",
            "Hora",
            "Usuario",
            "Sucursal",
            "Estado",
            "Tasa BCV (Bs/USD)",
            "Punto de Venta (USD)",
            "Punto de Venta (Bs)",
            "Pago Móvil (USD)",
            "Pago Móvil (Bs)",
            "Efectivo en Dólares (USD)",
            "Efectivo en Bolívares (Bs)",
            "Cashea (USD)",
            "Cashea (Bs equiv.)",
            "Salidas / Gastos (USD)",
            "Total consolidado (USD)",
            "Total consolidado (Bs)",
            "Ventas brutas (USD)",
            "Descuentos (USD)",
            "Ventas netas (USD)",
            "Ventas del día (Bs)",
            "Diferencia (USD)",
            "Observaciones",
            "Revisado por",
            "Fecha revisión"
        )

        val summaryRows = closings.map { closing ->
            val breakdown = paymentBreakdown(closing)
            val grossUsd = closing.displaySalesGrossUsd()
            val discountUsd = closing.displaySalesDiscountUsd()
            val reviewedAtText = if (closing.reviewedAt > 0L) {
                "${dateFormat.format(Date(closing.reviewedAt))} ${timeFormat.format(Date(closing.reviewedAt))}"
            } else {
                ""
            }
            listOf(
                text(dateFormat.format(Date(closing.closedAt))),
                text(timeFormat.format(Date(closing.closedAt))),
                text(closing.username),
                text(closing.branchName.ifBlank { closing.userSucursal }.ifBlank { "Sin sucursal" }),
                text(statusLabel(closing.status)),
                number(closing.rate),
                number(breakdown.posUsd),
                number(breakdown.posBs),
                number(breakdown.mobileUsd),
                number(breakdown.mobileBs),
                number(breakdown.cashUsd),
                number(breakdown.cashBs),
                number(breakdown.casheaUsd),
                number(breakdown.casheaBs),
                number(breakdown.expenseUsd),
                number(closing.grandTotalUsd),
                number(closing.grandTotalBs),
                number(grossUsd),
                number(discountUsd),
                number(closing.salesUsd),
                number(closing.salesBs),
                number(closing.differenceUsd),
                text(closing.observations.ifBlank { snapshotObservations(closing) }),
                text(closing.reviewedBy),
                text(reviewedAtText)
            )
        }

        val detailHeaders = listOf(
            "Fecha del cierre",
            "Usuario",
            "Estado",
            "Medio de pago",
            "Detalle / Referencia",
            "Monto (USD)",
            "Monto (Bs)",
            "Tasa BCV",
            "Equiv. USD (calculado)",
            "Equiv. Bs (calculado)"
        )

        val detailRows = buildList {
            closings.forEach { closing ->
                val snapshot = CashClosingSnapshotCodec.decode(closing.detailSnapshot)
                val rate = closing.rate
                if (snapshot == null) {
                    add(
                        listOf(
                            text(dateFormat.format(Date(closing.closedAt))),
                            text(closing.username),
                            text(statusLabel(closing.status)),
                            text("—"),
                            text("Sin detalle de medios de pago"),
                            number(0.0),
                            number(0.0),
                            number(rate),
                            number(0.0),
                            number(0.0)
                        )
                    )
                    return@forEach
                }
                snapshot.posEntries.forEach { entry ->
                    val (usd, bs) = dualAmount(entry.usd, entry.bs, rate)
                    add(detailRow(closing, statusLabel, "Punto de Venta", entry.name, usd, bs, rate))
                }
                snapshot.mobileEntries.forEach { entry ->
                    val (usd, bs) = dualAmount(entry.usd, entry.bs, rate)
                    add(detailRow(closing, statusLabel, "Pago Móvil", entry.ref, usd, bs, rate))
                }
                snapshot.cashEntries.forEach { entry ->
                    if (entry.usd > 0.0) {
                        val (usd, bs) = dualAmount(entry.usd, 0.0, rate)
                        add(
                            detailRow(
                                closing,
                                statusLabel,
                                "Efectivo en Dólares",
                                entry.description,
                                usd,
                                bs,
                                rate
                            )
                        )
                    }
                    if (entry.bs > 0.0) {
                        val (usd, bs) = dualAmount(0.0, entry.bs, rate)
                        add(
                            detailRow(
                                closing,
                                statusLabel,
                                "Efectivo en Bolívares",
                                entry.description,
                                usd,
                                bs,
                                rate
                            )
                        )
                    }
                }
                if (snapshot.casheaUsd > 0.0) {
                    val (usd, bs) = dualAmount(snapshot.casheaUsd, 0.0, rate)
                    add(detailRow(closing, statusLabel, "Cashea", "Financiado", usd, bs, rate))
                }
                snapshot.expenseEntries.forEach { entry ->
                    val (usd, bs) = dualAmount(entry.usd, 0.0, rate)
                    add(
                        detailRow(
                            closing,
                            statusLabel,
                            "Salida / Gasto",
                            entry.description,
                            usd,
                            bs,
                            rate
                        )
                    )
                }
            }
        }

        val orderHeaders = listOf(
            "Fecha del cierre",
            "Hora",
            "Usuario",
            "Sucursal",
            "Nº pedido",
            "Hora pedido",
            "Subtotal (USD)",
            "Descuento (USD)",
            "Neto (USD)"
        )

        val orderRows = buildList {
            closings.forEach { closing ->
                val snapshot = CashClosingSnapshotCodec.decode(closing.detailSnapshot) ?: return@forEach
                snapshot.confirmedOrders.forEach { order ->
                    add(
                        listOf(
                            text(dateFormat.format(Date(closing.closedAt))),
                            text(timeFormat.format(Date(closing.closedAt))),
                            text(closing.username),
                            text(closing.branchName.ifBlank { closing.userSucursal }.ifBlank { "Sin sucursal" }),
                            text(order.orderNumber.takeIf { it > 0 }?.toString() ?: "—"),
                            text(timeFormat.format(Date(order.createdAt))),
                            number(order.subtotalUsd.takeIf { it > 0 } ?: order.totalUsd + order.discountUsd),
                            number(order.discountUsd),
                            number(order.totalUsd)
                        )
                    )
                }
            }
        }

        return SimpleXlsxWriter.write(
            buildList {
                add(
                    SimpleXlsxWriter.Sheet(
                        name = "Resumen",
                        headers = summaryHeaders,
                        rows = summaryRows
                    )
                )
                add(
                    SimpleXlsxWriter.Sheet(
                        name = "Detalle medios de pago",
                        headers = detailHeaders,
                        rows = detailRows
                    )
                )
                if (orderRows.isNotEmpty()) {
                    add(
                        SimpleXlsxWriter.Sheet(
                            name = "Pedidos del día",
                            headers = orderHeaders,
                            rows = orderRows
                        )
                    )
                }
            }
        )
    }

    /**
     * Resumen simplificado para el perfil Gerente: una sola hoja con las columnas
     * del reporte operativo (fecha, sucursal, tasa, ventas USD/Bs, observaciones).
     */
    fun exportGerenteSummary(closings: List<CashClosingRecord>): ByteArray {
        require(closings.isNotEmpty()) { "No hay cierres para exportar." }
        val headers = listOf(
            "Fecha del cierre",
            "Sucursal",
            "Tasa BCV (Bs/USD)",
            "Ventas del dia (USD)",
            "Ventas del día (Bs)",
            "Observaciones"
        )
        val rows = closings
            .sortedByDescending { it.closedAt }
            .map { closing ->
                listOf(
                    text(dateFormat.format(Date(closing.closedAt))),
                    text(
                        closing.branchName.ifBlank { closing.userSucursal }.ifBlank { "Sin sucursal" }
                    ),
                    text(formatVeDecimal(closing.rate)),
                    text(formatVeDecimal(closing.salesUsd)),
                    text(formatVeDecimal(closing.salesBs)),
                    text(closing.observations.ifBlank { snapshotObservations(closing) })
                )
            }
        return SimpleXlsxWriter.write(
            listOf(
                SimpleXlsxWriter.Sheet(
                    name = "Cierres",
                    headers = headers,
                    rows = rows
                )
            )
        )
    }

    fun paymentBreakdown(closing: CashClosingRecord): PaymentBreakdown {
        val snapshot = CashClosingSnapshotCodec.decode(closing.detailSnapshot)
        val rate = closing.rate
        if (snapshot == null) {
            return PaymentBreakdown(
                posUsd = 0.0,
                posBs = 0.0,
                mobileUsd = 0.0,
                mobileBs = 0.0,
                cashUsd = 0.0,
                cashBs = 0.0,
                casheaUsd = 0.0,
                casheaBs = 0.0,
                expenseUsd = 0.0
            )
        }
        val posUsd = snapshot.posEntries.sumOf { it.usd }
        val posBs = reconcileBs(posUsd, snapshot.posEntries.sumOf { it.bs }, rate)
        val mobileUsd = snapshot.mobileEntries.sumOf { it.usd }
        val mobileBs = reconcileBs(mobileUsd, snapshot.mobileEntries.sumOf { it.bs }, rate)
        val cashUsd = if (snapshot.cashUsd > 0.0) {
            snapshot.cashUsd
        } else {
            snapshot.cashEntries.sumOf { it.usd }
        }
        val cashBs = if (snapshot.cashBs > 0.0) {
            snapshot.cashBs
        } else {
            snapshot.cashEntries.sumOf { it.bs }
        }
        val (resolvedCashUsd, resolvedCashBs) = dualAmount(cashUsd, cashBs, rate)
        val casheaUsd = snapshot.casheaUsd
        val casheaBs = dualAmount(casheaUsd, 0.0, rate).second
        val expenseUsd = snapshot.expenseEntries.sumOf { it.usd }
        return PaymentBreakdown(
            posUsd = posUsd,
            posBs = posBs,
            mobileUsd = mobileUsd,
            mobileBs = mobileBs,
            cashUsd = resolvedCashUsd,
            cashBs = resolvedCashBs,
            casheaUsd = casheaUsd,
            casheaBs = casheaBs,
            expenseUsd = expenseUsd
        )
    }

    private fun detailRow(
        closing: CashClosingRecord,
        statusLabel: (CashClosingStatus) -> String,
        method: String,
        detail: String,
        usd: Double,
        bs: Double,
        rate: Double
    ): List<SimpleXlsxWriter.CellValue> {
        val (equivUsd, equivBs) = dualAmount(usd, bs, rate)
        return listOf(
            text(dateFormat.format(Date(closing.closedAt))),
            text(closing.username),
            text(statusLabel(closing.status)),
            text(method),
            text(detail),
            number(usd),
            number(bs),
            number(rate),
            number(equivUsd),
            number(equivBs)
        )
    }

    private fun snapshotObservations(closing: CashClosingRecord): String =
        CashClosingSnapshotCodec.decode(closing.detailSnapshot)?.observations.orEmpty()

    private fun dualAmount(usd: Double, bs: Double, rate: Double): Pair<Double, Double> {
        val hasUsd = usd > 0.0
        val hasBs = bs > 0.0
        return when {
            hasUsd && hasBs -> roundMoney(usd) to roundMoney(bs)
            hasUsd && rate > 0.0 -> roundMoney(usd) to roundMoney(usd * rate)
            hasBs && rate > 0.0 -> roundMoney(bs / rate) to roundMoney(bs)
            else -> 0.0 to 0.0
        }
    }

    private fun reconcileBs(usd: Double, bs: Double, rate: Double): Double =
        if (bs > 0.0) bs else if (usd > 0.0 && rate > 0.0) roundMoney(usd * rate) else 0.0

    private fun roundMoney(value: Double): Double = round(value * 100.0) / 100.0

    private fun formatVeDecimal(value: Double): String = veDecimalFormat.format(value)

    private fun text(value: String): SimpleXlsxWriter.CellValue =
        SimpleXlsxWriter.CellValue.Text(value)

    private fun number(value: Double): SimpleXlsxWriter.CellValue =
        SimpleXlsxWriter.CellValue.Number(value)
}
