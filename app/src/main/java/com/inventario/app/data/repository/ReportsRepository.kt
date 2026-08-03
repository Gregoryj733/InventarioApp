package com.inventario.app.data.repository

import com.inventario.app.data.dao.CashClosingRecordDao
import com.inventario.app.data.dao.ProductSalesAggregate
import com.inventario.app.data.dao.SaleLineItemDao
import com.inventario.app.data.dao.SaleRecordDao
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.SaleRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class ReportsSummary(
    val totalSalesUsd: Double,
    val totalSalesBs: Double,
    val orderCount: Int,
    val topProduct: ProductSalesAggregate?,
    val leastProduct: ProductSalesAggregate?,
    val cashClosings: List<CashClosingRecord>,
    val differenceAlerts: List<CashClosingRecord>,
    val dailySales: List<DailySalesPoint>
)

data class DailySalesPoint(
    val dayLabel: String,
    val totalUsd: Double,
    val orderCount: Int
)

class ReportsRepository(
    private val saleRecordDao: SaleRecordDao,
    private val saleLineItemDao: SaleLineItemDao,
    private val cashClosingRecordDao: CashClosingRecordDao
) {
    suspend fun loadSummary(start: Long, end: Long, bcvRate: Double?): ReportsSummary =
        withContext(Dispatchers.IO) {
            val totalUsd = saleRecordDao.sumTotalUsdBetween(start, end)
            val orderCount = saleRecordDao.countBetween(start, end)
            val rate = bcvRate ?: 0.0
            val totalBs = if (rate > 0) totalUsd * rate else 0.0

            ReportsSummary(
                totalSalesUsd = totalUsd,
                totalSalesBs = totalBs,
                orderCount = orderCount,
                topProduct = saleLineItemDao.topProductsBetween(start, end).firstOrNull(),
                leastProduct = saleLineItemDao.leastSoldProductsBetween(start, end).firstOrNull(),
                cashClosings = cashClosingRecordDao.listBetween(start, end),
                differenceAlerts = cashClosingRecordDao.listWithDifferenceBetween(start, end),
                dailySales = buildDailySales(saleRecordDao.listBetween(start, end), start, end)
            )
        }

    private fun buildDailySales(
        records: List<SaleRecord>,
        start: Long,
        end: Long
    ): List<DailySalesPoint> {
        if (records.isEmpty()) return emptyList()

        val cal = Calendar.getInstance()
        val dayFormat = java.text.SimpleDateFormat("dd/MM", java.util.Locale("es", "VE"))
        val buckets = linkedMapOf<String, MutableList<SaleRecord>>()

        var cursor = start
        while (cursor < end) {
            cal.timeInMillis = cursor
            buckets[dayFormat.format(cal.time)] = mutableListOf()
            cursor += TimeUnit.DAYS.toMillis(1)
        }

        for (record in records) {
            cal.timeInMillis = record.createdAt
            val key = dayFormat.format(cal.time)
            buckets.getOrPut(key) { mutableListOf() }.add(record)
        }

        return buckets.map { (label, dayRecords) ->
            DailySalesPoint(
                dayLabel = label,
                totalUsd = dayRecords.sumOf { it.totalUsd },
                orderCount = dayRecords.size
            )
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
