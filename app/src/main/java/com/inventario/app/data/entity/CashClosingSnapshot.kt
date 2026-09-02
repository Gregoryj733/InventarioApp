package com.inventario.app.data.entity

import org.json.JSONArray
import org.json.JSONObject

data class CashClosingSnapshot(
    val branchName: String = "",
    val userSucursal: String = "",
    val prevCashUsd: Double = 0.0,
    val prevCashBs: Double = 0.0,
    val salesUsd: Double = 0.0,
    val salesBs: Double = 0.0,
    val salesGrossUsd: Double = 0.0,
    val salesDiscountUsd: Double = 0.0,
    val confirmedOrders: List<SnapshotConfirmedOrder> = emptyList(),
    val posEntries: List<SnapshotPosEntry> = emptyList(),
    val mobileEntries: List<SnapshotMobileEntry> = emptyList(),
    val cashEntries: List<SnapshotCashEntry> = emptyList(),
    val cashUsd: Double = 0.0,
    val cashBs: Double = 0.0,
    val casheaUsd: Double = 0.0,
    val expenseEntries: List<SnapshotExpenseEntry> = emptyList(),
    val observations: String = ""
) {
    data class SnapshotPosEntry(val name: String, val usd: Double, val bs: Double)
    data class SnapshotMobileEntry(val ref: String, val usd: Double, val bs: Double)
    data class SnapshotCashEntry(val description: String, val usd: Double, val bs: Double)
    data class SnapshotExpenseEntry(val description: String, val usd: Double)
    data class SnapshotConfirmedOrder(
        val syncId: String,
        val orderNumber: Int,
        val createdAt: Long,
        val subtotalUsd: Double,
        val discountUsd: Double,
        val totalUsd: Double
    )
}

object CashClosingSnapshotCodec {
    fun encode(snapshot: CashClosingSnapshot): String {
        val root = JSONObject()
        root.put("branchName", snapshot.branchName)
        root.put("userSucursal", snapshot.userSucursal)
        root.put("prevCashUsd", snapshot.prevCashUsd)
        root.put("prevCashBs", snapshot.prevCashBs)
        root.put("salesUsd", snapshot.salesUsd)
        root.put("salesBs", snapshot.salesBs)
        root.put("salesGrossUsd", snapshot.salesGrossUsd)
        root.put("salesDiscountUsd", snapshot.salesDiscountUsd)
        root.put("cashUsd", snapshot.cashUsd)
        root.put("cashBs", snapshot.cashBs)
        root.put("casheaUsd", snapshot.casheaUsd)
        root.put("observations", snapshot.observations)

        val posArray = JSONArray()
        snapshot.posEntries.forEach { entry ->
            posArray.put(
                JSONObject()
                    .put("name", entry.name)
                    .put("usd", entry.usd)
                    .put("bs", entry.bs)
            )
        }
        root.put("posEntries", posArray)

        val mobileArray = JSONArray()
        snapshot.mobileEntries.forEach { entry ->
            mobileArray.put(
                JSONObject()
                    .put("ref", entry.ref)
                    .put("usd", entry.usd)
                    .put("bs", entry.bs)
            )
        }
        root.put("mobileEntries", mobileArray)

        val cashArray = JSONArray()
        snapshot.cashEntries.forEach { entry ->
            cashArray.put(
                JSONObject()
                    .put("description", entry.description)
                    .put("usd", entry.usd)
                    .put("bs", entry.bs)
            )
        }
        root.put("cashEntries", cashArray)

        val expenseArray = JSONArray()
        snapshot.expenseEntries.forEach { entry ->
            expenseArray.put(
                JSONObject()
                    .put("description", entry.description)
                    .put("usd", entry.usd)
            )
        }
        root.put("expenseEntries", expenseArray)

        val ordersArray = JSONArray()
        snapshot.confirmedOrders.forEach { order ->
            ordersArray.put(
                JSONObject()
                    .put("syncId", order.syncId)
                    .put("orderNumber", order.orderNumber)
                    .put("createdAt", order.createdAt)
                    .put("subtotalUsd", order.subtotalUsd)
                    .put("discountUsd", order.discountUsd)
                    .put("totalUsd", order.totalUsd)
            )
        }
        root.put("confirmedOrders", ordersArray)

        return root.toString()
    }

    fun decode(json: String): CashClosingSnapshot? = runCatching {
        if (json.isBlank()) return null
        val root = JSONObject(json)
        val posEntries = mutableListOf<CashClosingSnapshot.SnapshotPosEntry>()
        root.optJSONArray("posEntries")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                posEntries += CashClosingSnapshot.SnapshotPosEntry(
                    name = item.optString("name", ""),
                    usd = item.optDouble("usd", 0.0),
                    bs = item.optDouble("bs", 0.0)
                )
            }
        }
        val mobileEntries = mutableListOf<CashClosingSnapshot.SnapshotMobileEntry>()
        root.optJSONArray("mobileEntries")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                mobileEntries += CashClosingSnapshot.SnapshotMobileEntry(
                    ref = item.optString("ref", ""),
                    usd = item.optDouble("usd", 0.0),
                    bs = item.optDouble("bs", 0.0)
                )
            }
        }
        val cashEntries = mutableListOf<CashClosingSnapshot.SnapshotCashEntry>()
        root.optJSONArray("cashEntries")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                cashEntries += CashClosingSnapshot.SnapshotCashEntry(
                    description = item.optString("description", ""),
                    usd = item.optDouble("usd", 0.0),
                    bs = item.optDouble("bs", 0.0)
                )
            }
        }
        val expenseEntries = mutableListOf<CashClosingSnapshot.SnapshotExpenseEntry>()
        root.optJSONArray("expenseEntries")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                expenseEntries += CashClosingSnapshot.SnapshotExpenseEntry(
                    description = item.optString("description", ""),
                    usd = item.optDouble("usd", 0.0)
                )
            }
        }
        val confirmedOrders = mutableListOf<CashClosingSnapshot.SnapshotConfirmedOrder>()
        root.optJSONArray("confirmedOrders")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                confirmedOrders += CashClosingSnapshot.SnapshotConfirmedOrder(
                    syncId = item.optString("syncId", ""),
                    orderNumber = item.optInt("orderNumber", 0),
                    createdAt = item.optLong("createdAt", 0L),
                    subtotalUsd = item.optDouble("subtotalUsd", 0.0),
                    discountUsd = item.optDouble("discountUsd", 0.0),
                    totalUsd = item.optDouble("totalUsd", 0.0)
                )
            }
        }
        CashClosingSnapshot(
            branchName = root.optString("branchName", ""),
            userSucursal = root.optString("userSucursal", ""),
            prevCashUsd = root.optDouble("prevCashUsd", 0.0),
            prevCashBs = root.optDouble("prevCashBs", 0.0),
            salesUsd = root.optDouble("salesUsd", 0.0),
            salesBs = root.optDouble("salesBs", 0.0),
            salesGrossUsd = root.optDouble("salesGrossUsd", 0.0),
            salesDiscountUsd = root.optDouble("salesDiscountUsd", 0.0),
            confirmedOrders = confirmedOrders,
            posEntries = posEntries,
            mobileEntries = mobileEntries,
            cashEntries = cashEntries,
            cashUsd = root.optDouble("cashUsd", 0.0),
            cashBs = root.optDouble("cashBs", 0.0),
            casheaUsd = root.optDouble("casheaUsd", 0.0),
            expenseEntries = expenseEntries,
            observations = root.optString("observations", "")
        )
    }.getOrNull()
}
