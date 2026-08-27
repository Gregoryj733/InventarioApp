package com.inventario.app.data.repository

import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.sync.CloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

class ReportsRepository(private var cloudSync: CloudSync) {

    fun setCloudSync(sync: CloudSync) {
        cloudSync = sync
    }

    suspend fun listClosingHistory(): List<CashClosingRecord> = withContext(Dispatchers.IO) {
        cloudSync.get("/v1/cash-closings").optJSONArray("cashClosings")?.toCashClosingList().orEmpty()
            .sortedByDescending { it.closedAt }
    }

    suspend fun loadSummary(start: Long, end: Long, bcvRate: Double?): ReportsSummary =
        withContext(Dispatchers.IO) {
            // Ventas y cierres se acotan al rango seleccionado (por defecto el día
            // actual). Pendientes, con diferencia, aprobados y rechazados comparten
            // el mismo filtro de período.
            val rangeQuery = mapOf("start" to start.toString(), "end" to end.toString())
            val sales = cloudSync.get("/v1/sales", rangeQuery).optJSONArray("sales")?.toSaleList().orEmpty()
            val totalUsd = sales.sumOf { it.totalUsd }
            val fallbackRate = bcvRate ?: 0.0
            val totalBs = sales.sumOf { sale ->
                val rate = sale.bcvRate.takeIf { it > 0 } ?: fallbackRate
                if (rate > 0) sale.totalUsd * rate else 0.0
            }
            val orderCount = sales.size

            val allClosings = cloudSync.get("/v1/cash-closings").optJSONArray("cashClosings")
                ?.toCashClosingList().orEmpty()
            val periodClosings = allClosings.filter { it.closedAt >= start && it.closedAt < end }
            val approvedClosings = periodClosings
                .filter { it.status == CashClosingStatus.APPROVED }
                .sortedByDescending { it.closedAt }
            val approvedIncomeUsd = approvedClosings.sumOf { it.grandTotalUsd }
            val approvedIncomeBs = approvedClosings.sumOf { it.grandTotalBs }

            ReportsSummary(
                totalSalesUsd = totalUsd,
                totalSalesBs = totalBs,
                orderCount = orderCount,
                balancedPendingClosings = periodClosings
                    .filter { it.status == CashClosingStatus.PENDING && !it.hasDifference }
                    .sortedByDescending { it.closedAt },
                differencePendingClosings = periodClosings
                    .filter { it.status == CashClosingStatus.PENDING && it.hasDifference }
                    .sortedByDescending { it.closedAt },
                approvedClosings = approvedClosings,
                approvedClosingIncomeUsd = approvedIncomeUsd,
                approvedClosingIncomeBs = approvedIncomeBs,
                rejectedClosings = periodClosings
                    .filter { it.status == CashClosingStatus.REJECTED }
                    .sortedByDescending { it.closedAt }
            )
        }

    suspend fun approveClosing(id: Long, reviewerUsername: String): Result<Unit> =
        updateStatus(id, "APPROVED", reviewerUsername)

    suspend fun rejectClosing(id: Long, reviewerUsername: String): Result<Unit> =
        updateStatus(id, "REJECTED", reviewerUsername)

    suspend fun revertClosing(id: Long, reviewerUsername: String): Result<Unit> =
        updateStatus(id, "REVERTED", reviewerUsername)

    private suspend fun updateStatus(
        id: Long,
        status: String,
        reviewerUsername: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            cloudSync.patchJson(
                "/v1/cash-closings/$id/status",
                JSONObject().apply {
                    put("status", status)
                    put("reviewedBy", reviewerUsername)
                    put("reviewedAt", System.currentTimeMillis())
                }
            )
            Unit
        }
    }

    companion object {
        const val MAX_RANGE_DAYS = 90

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
