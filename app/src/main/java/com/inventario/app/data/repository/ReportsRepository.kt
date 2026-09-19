package com.inventario.app.data.repository

import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.sync.CloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
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
    val rejectedClosings: List<CashClosingRecord>,
    /** Cierres aprobados visibles en el flujo de aprobación (retención semanal). */
    val approvalFlowApprovedClosings: List<CashClosingRecord>,
    /** Cierres rechazados visibles en el flujo de aprobación (retención semanal). */
    val approvalFlowRejectedClosings: List<CashClosingRecord>
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
            // Ventas sí se acotan al rango (crecen sin límite). Los cierres se
            // traen completos: la cola de aprobación debe mostrar TODOS los
            // PENDING (cuadrados y con diferencia), de cualquier usuario de la
            // sucursal; aprobados/rechazados se filtran al período seleccionado.
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
            val approvalFlowClosings = allClosings.filter {
                it.closedAt >= approvalFlowRetentionStartMillis()
            }
            val periodApprovedClosings = periodClosings
                .filter { it.status == CashClosingStatus.APPROVED }
                .sortedByDescending { it.closedAt }
            val approvedIncomeUsd = periodApprovedClosings.sumOf { it.grandTotalUsd }
            val approvedIncomeBs = periodApprovedClosings.sumOf { it.grandTotalBs }

            ReportsSummary(
                totalSalesUsd = totalUsd,
                totalSalesBs = totalBs,
                orderCount = orderCount,
                balancedPendingClosings = approvalFlowClosings
                    .filter { it.status == CashClosingStatus.PENDING && !it.hasDifference }
                    .sortedByDescending { it.closedAt },
                differencePendingClosings = approvalFlowClosings
                    .filter { it.status == CashClosingStatus.PENDING && it.hasDifference }
                    .sortedByDescending { it.closedAt },
                approvedClosings = periodApprovedClosings,
                approvedClosingIncomeUsd = approvedIncomeUsd,
                approvedClosingIncomeBs = approvedIncomeBs,
                rejectedClosings = periodClosings
                    .filter { it.status == CashClosingStatus.REJECTED }
                    .sortedByDescending { it.closedAt },
                approvalFlowApprovedClosings = approvalFlowClosings
                    .filter { it.status == CashClosingStatus.APPROVED }
                    .sortedByDescending { it.closedAt },
                approvalFlowRejectedClosings = approvalFlowClosings
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

        private val APPROVAL_RETENTION_TZ: TimeZone = TimeZone.getTimeZone("America/Caracas")

        /** Inicio de la semana en curso (lunes 00:00, America/Caracas). */
        fun approvalFlowRetentionStartMillis(nowMillis: Long = System.currentTimeMillis()): Long {
            val cal = Calendar.getInstance(APPROVAL_RETENTION_TZ).apply {
                timeInMillis = nowMillis
                firstDayOfWeek = Calendar.MONDAY
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }

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
