package com.inventario.app.data.repository

import android.content.Context
import com.inventario.app.data.branch.BranchCatalog
import com.inventario.app.data.branch.BranchManager
import com.inventario.app.data.entity.effectiveDiscountUsd
import com.inventario.app.data.entity.effectiveSubtotalUsd
import com.inventario.app.data.entity.canSwitchBranch
import com.inventario.app.data.session.SessionManager
import com.inventario.app.data.sync.CloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

data class BranchDailySalesKpi(
    val branchId: String,
    val label: String,
    val totalUsd: Double,
    val totalBs: Double,
    val orderCount: Int = 0,
    val grossUsd: Double = totalUsd,
    val discountUsd: Double = 0.0,
    val discountBs: Double = 0.0,
    val unavailable: Boolean = false
)

class BranchSalesKpiRepository(
    context: Context,
    private val branchManager: BranchManager,
    private val sessionManager: SessionManager
) {
    private val fallbackUrls = BranchCatalog(context).fallbackUrls

    suspend fun loadTodayPerBranch(bcvRate: Double?): List<BranchDailySalesKpi> =
        withContext(Dispatchers.IO) {
            val (start, end) = todayBounds()
            val query = mapOf("start" to start.toString(), "end" to end.toString())
            val role = sessionManager.role()
            val branches = if (role != null && role.canSwitchBranch()) {
                branchManager.allBranches()
            } else {
                branchManager.branchesVisibleToUser(role, sessionManager.sucursal())
            }
            branches.map { branch ->
                val token = sessionManager.tokenForBranch(branch.id)
                if (token.isNullOrBlank()) {
                    return@map BranchDailySalesKpi(
                        branchId = branch.id,
                        label = branch.chipLabel,
                        totalUsd = 0.0,
                        totalBs = 0.0,
                        unavailable = true
                    )
                }
                val sync = CloudSync(
                    branch.toSyncConfig(fallbackUrls).copy(branchId = branch.id)
                )
                sync.setAuthToken(token)
                runCatching {
                    val response = sync.get("/v1/sales", query)
                    val sales = response.optJSONArray("sales")?.toSaleList().orEmpty()
                    val lineItems = response.optJSONArray("lineItems")?.toSaleLineItemList().orEmpty()
                    val linesBySale = lineItems.groupBy { it.saleSyncId }
                    val fallback = bcvRate ?: 0.0
                    var totalUsd = 0.0
                    var grossUsd = 0.0
                    var discountUsd = 0.0
                    var totalBs = 0.0
                    var discountBs = 0.0
                    sales.forEach { sale ->
                        val lines = linesBySale[sale.syncId].orEmpty()
                        val discount = sale.effectiveDiscountUsd(lines)
                        val gross = sale.effectiveSubtotalUsd(lines)
                        val net = sale.totalUsd
                        val rate = sale.bcvRate.takeIf { it > 0 } ?: fallback
                        totalUsd += net
                        grossUsd += gross
                        discountUsd += discount
                        if (rate > 0) {
                            totalBs += net * rate
                            discountBs += discount * rate
                        }
                    }
                    BranchDailySalesKpi(
                        branchId = branch.id,
                        label = branch.chipLabel,
                        totalUsd = totalUsd,
                        totalBs = totalBs,
                        orderCount = sales.size,
                        grossUsd = grossUsd,
                        discountUsd = discountUsd,
                        discountBs = discountBs
                    )
                }.getOrElse {
                    BranchDailySalesKpi(
                        branchId = branch.id,
                        label = branch.chipLabel,
                        totalUsd = 0.0,
                        totalBs = 0.0,
                        unavailable = true
                    )
                }
            }
        }

    private fun todayBounds(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return start to cal.timeInMillis
    }
}
