package com.inventario.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.inventario.app.data.entity.SaleRecord

@Dao
interface SaleRecordDao {
    @Insert
    suspend fun insert(record: SaleRecord)

    @Query("SELECT COUNT(*) FROM sale_records WHERE syncId = :syncId")
    suspend fun countBySyncId(syncId: String): Int

    @Query("SELECT COALESCE(SUM(totalUsd), 0) FROM sale_records WHERE createdAt >= :start AND createdAt < :end")
    suspend fun sumTotalUsdBetween(start: Long, end: Long): Double
}
