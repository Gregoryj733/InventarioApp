package com.inventario.app.data.repository

import com.inventario.app.data.dao.CashClosingRecordDao
import com.inventario.app.data.dao.SaleRecordDao
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class ReportsSummary(
    val totalSalesUsd: Double,
    val totalSalesBs: Double,
    val orderCount: Int,
    val balancedPendingClosings: List<CashClosingRecord>,
    val differencePendingClosings: List<CashClosingRecord>,
    val approvedClosings: List<CashClosingRecord>,
    val approvedClosingIncomeUsd: Double,
    val approvedClosingIncomeBs: Double,
    val rejectedClosings: List<CashClosingRecord>
)

class ReportsRepository(
    private val saleRecordDao: SaleRecordDao,
    private val cashClosingRecordDao: CashClosingRecordDao
) {
    suspend fun loadSummary(start: Long, end: Long, bcvRate: Double?): ReportsSummary =
        withContext(Dispatchers.IO) {
            val sales = saleRecordDao.listBetween(start, end)
            val totalUsd = sales.sumOf { it.totalUsd }
            val fallbackRate = bcvRate ?: 0.0
            val totalBs = sales.sumOf { sale ->
                val rate = sale.bcvRate.takeIf { it > 0 } ?: fallbackRate
                if (rate > 0) sale.totalUsd * rate else 0.0
            }
            val orderCount = sales.size

            val approvedClosings = cashClosingRecordDao.listApprovedBetween(start, end)
            val approvedIncomeUsd = approvedClosings.sumOf { it.grandTotalUsd }
            val approvedIncomeBs = approvedClosings.sumOf { it.grandTotalBs }

            ReportsSummary(
                totalSalesUsd = totalUsd,
                totalSalesBs = totalBs,
                orderCount = orderCount,
                balancedPendingClosings = cashClosingRecordDao.listBalancedPendingBetween(start, end),
                differencePendingClosings = cashClosingRecordDao.listDifferencePendingBetween(start, end),
                approvedClosings = approvedClosings,
                approvedClosingIncomeUsd = approvedIncomeUsd,
                approvedClosingIncomeBs = approvedIncomeBs,
                rejectedClosings = cashClosingRecordDao.listRejectedBetween(start, end)
            )
        }

    suspend fun approveClosing(id: Long, reviewerUsername: String, verificationCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(verificationCode == VERIFICATION_CODE) { "Código de verificación incorrecto." }
                val updated = cashClosingRecordDao.updateStatus(
                    id = id,
                    status = CashClosingStatus.APPROVED,
                    reviewedBy = reviewerUsername,
                    reviewedAt = System.currentTimeMillis()
                )
                if (updated == 0) error("No se pudo aprobar el cierre. Verifica que esté pendiente.")
            }
        }

    suspend fun rejectClosing(id: Long, reviewerUsername: String, verificationCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(verificationCode == VERIFICATION_CODE) { "Código de verificación incorrecto." }
                val updated = cashClosingRecordDao.updateStatus(
                    id = id,
                    status = CashClosingStatus.REJECTED,
                    reviewedBy = reviewerUsername,
                    reviewedAt = System.currentTimeMillis()
                )
                if (updated == 0) error("No se pudo rechazar el cierre. Verifica que esté pendiente.")
            }
        }

    suspend fun revertClosing(id: Long, reviewerUsername: String, verificationCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(verificationCode == VERIFICATION_CODE) { "Código de verificación incorrecto." }
                val updated = cashClosingRecordDao.revertApproved(
                    id = id,
                    reviewedBy = reviewerUsername,
                    reviewedAt = System.currentTimeMillis()
                )
                if (updated == 0) error("No se pudo revertir el cierre. Verifica que esté aprobado.")
            }
        }

    companion object {
        const val MAX_RANGE_DAYS = 90
        const val VERIFICATION_CODE = "4321"

        fun clampRange(start: Long, end: Long): Pair<Long, Long> {
            val maxMillis = TimeUnit.DAYS.toMillis(MAX_RANGE_DAYS.toLong())
            val adjustedEnd = minOf(end, System.currentTimeMillis())
            val adjustedStart = maxOf(start, adjustedEnd - maxMillis)
            return adjustedStart to adjustedEnd
        }

        fun rangeSpanDays(start: Long, end: Long): Long =
            TimeUnit.MILLISECONDS.toDays(end - start).coerceAtLeast(0)
    }
}
