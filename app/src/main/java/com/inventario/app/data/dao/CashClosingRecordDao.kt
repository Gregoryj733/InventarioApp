package com.inventario.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.inventario.app.data.entity.CashClosingRecord

@Dao
interface CashClosingRecordDao {
    @Insert
    suspend fun insert(record: CashClosingRecord): Long

    @Query(
        """
        SELECT * FROM cash_closing_records
        WHERE closedAt >= :start AND closedAt < :end
        ORDER BY closedAt DESC
        """
    )
    suspend fun listBetween(start: Long, end: Long): List<CashClosingRecord>

    @Query(
        """
        SELECT * FROM cash_closing_records
        WHERE closedAt >= :start AND closedAt < :end AND hasDifference = 1
        ORDER BY closedAt DESC
        """
    )
    suspend fun listWithDifferenceBetween(start: Long, end: Long): List<CashClosingRecord>
}
