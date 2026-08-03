package com.inventario.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.inventario.app.data.entity.SaleLineItem

data class ProductSalesAggregate(
    val productId: Long,
    val description: String,
    val totalQuantity: Double,
    val totalUsd: Double
)

@Dao
interface SaleLineItemDao {
    @Insert
    suspend fun insertAll(items: List<SaleLineItem>)

    @Query(
        """
        SELECT productId, description, SUM(quantity) AS totalQuantity, SUM(totalUsd) AS totalUsd
        FROM sale_line_items
        WHERE createdAt >= :start AND createdAt < :end
        GROUP BY productId, description
        ORDER BY totalQuantity DESC
        LIMIT 1
        """
    )
    suspend fun topProductsBetween(start: Long, end: Long): List<ProductSalesAggregate>

    @Query(
        """
        SELECT productId, description, SUM(quantity) AS totalQuantity, SUM(totalUsd) AS totalUsd
        FROM sale_line_items
        WHERE createdAt >= :start AND createdAt < :end
        GROUP BY productId, description
        ORDER BY totalQuantity ASC
        LIMIT 1
        """
    )
    suspend fun leastSoldProductsBetween(start: Long, end: Long): List<ProductSalesAggregate>
}
