package com.inventario.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.inventario.app.data.entity.Product
import com.inventario.app.data.order.OrderLine
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT COUNT(*) FROM products")
    suspend fun count(): Int

    @Query(
        """
        SELECT products.* FROM products
        INNER JOIN products_fts ON products.rowid = products_fts.rowid
        WHERE products_fts MATCH :query
        ORDER BY products.description
        LIMIT :limit
        """
    )
    suspend fun searchFts(query: String, limit: Int = 40): List<Product>

    @Query(
        """
        SELECT * FROM products
        WHERE LOWER(description) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
        ORDER BY
          CASE WHEN LOWER(description) LIKE LOWER(:query) || '%' ESCAPE '\' THEN 0 ELSE 1 END,
          description
        LIMIT :limit
        """
    )
    suspend fun searchLike(query: String, limit: Int = 40): List<Product>

    @Query("INSERT INTO products_fts(products_fts) VALUES('rebuild')")
    suspend fun rebuildFtsIndex()

    @Query("DELETE FROM products")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<Product>)

    @Transaction
    suspend fun replaceAll(products: List<Product>) {
        clearAll()
        products.chunked(INSERT_CHUNK_SIZE).forEach { chunk ->
            insertAll(chunk)
        }
        rebuildFtsIndex()
    }

    @Transaction
    suspend fun replaceAllFromCloud(products: List<Product>, activeSyncIds: List<String>) {
        if (activeSyncIds.isNotEmpty()) {
            deleteNotInSyncIds(activeSyncIds)
        } else {
            clearAll()
        }
        upsertFromCloud(products)
    }

    @Query("SELECT * FROM products ORDER BY description")
    fun observeAll(): Flow<List<Product>>

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): Product?

    @Query("SELECT * FROM products WHERE syncId = :syncId LIMIT 1")
    suspend fun findBySyncId(syncId: String): Product?

    @Query("SELECT syncId FROM products WHERE syncId != ''")
    suspend fun allSyncIds(): List<String>

    @Query("DELETE FROM products WHERE syncId NOT IN (:syncIds)")
    suspend fun deleteNotInSyncIds(syncIds: List<String>)

    @Transaction
    suspend fun upsertFromCloud(products: List<Product>) {
        for (product in products) {
            val existing = findBySyncId(product.syncId)
            if (existing == null) {
                insertAll(listOf(product))
            } else if (product.updatedAt >= existing.updatedAt) {
                updateFromCloud(
                    syncId = product.syncId,
                    description = product.description,
                    quantity = product.quantity,
                    unit = product.unit,
                    price = product.price,
                    updatedAt = product.updatedAt
                )
            }
        }
        rebuildFtsIndex()
    }

    @Query(
        """
        UPDATE products
        SET description = :description,
            quantity = :quantity,
            unit = :unit,
            price = :price,
            updatedAt = :updatedAt
        WHERE syncId = :syncId
        """
    )
    suspend fun updateFromCloud(
        syncId: String,
        description: String,
        quantity: Double,
        unit: String,
        price: Double,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE products
        SET quantity = :quantity, updatedAt = :updatedAt
        WHERE syncId = :syncId
        """
    )
    suspend fun updateQuantityBySyncId(syncId: String, quantity: Double, updatedAt: Long)

    @Query(
        """
        UPDATE products
        SET quantity = quantity - :amount, updatedAt = :updatedAt
        WHERE id = :id AND quantity >= :amount
        """
    )
    suspend fun deductQuantity(id: Long, amount: Double, updatedAt: Long): Int

    @Query("UPDATE products SET syncId = :syncId WHERE id = :id")
    suspend fun updateSyncId(id: Long, syncId: String)

    @Transaction
    suspend fun executeOrderDeductions(lines: List<OrderLine>, now: Long) {
        for (line in lines) {
            val product = findById(line.productId)
                ?: error("Producto no encontrado: ${line.description}")
            if (product.quantity < line.quantity) {
                error(
                    "Stock insuficiente para \"${line.description}\" " +
                        "(disponible ${product.quantity}, pedido ${line.quantity})."
                )
            }
        }
        for (line in lines) {
            val updated = deductQuantity(line.productId, line.quantity, now)
            if (updated == 0) {
                error("No se pudo descontar \"${line.description}\".")
            }
        }
    }

    companion object {
        private const val INSERT_CHUNK_SIZE = 500
    }
}
