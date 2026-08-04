package com.inventario.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingStatus

@Dao
interface CashClosingRecordDao {
    @Insert
    suspend fun insert(record: CashClosingRecord): Long

    @Update
    suspend fun update(record: CashClosingRecord)

    @Query(
        """
        SELECT * FROM cash_closing_records
        WHERE closedAt >= :start AND closedAt < :end
        AND hasDifference = 0 AND status = 'PENDING'
        ORDER BY closedAt DESC
        """
    )
    suspend fun listBalancedPendingBetween(start: Long, end: Long): List<CashClosingRecord>

    @Query(
        """
        SELECT * FROM cash_closing_records
        WHERE closedAt >= :start AND closedAt < :end
        AND hasDifference = 1 AND status = 'PENDING'
        ORDER BY closedAt DESC
        """
    )
    suspend fun listDifferencePendingBetween(start: Long, end: Long): List<CashClosingRecord>

    @Query(
        """
        SELECT * FROM cash_closing_records
        WHERE closedAt >= :start AND closedAt < :end AND status = 'APPROVED'
        ORDER BY closedAt DESC
        """
    )
    suspend fun listApprovedBetween(start: Long, end: Long): List<CashClosingRecord>

    @Query(
        """
        SELECT * FROM cash_closing_records
        WHERE closedAt >= :start AND closedAt < :end AND status = 'REJECTED'
        ORDER BY closedAt DESC
        """
    )
    suspend fun listRejectedBetween(start: Long, end: Long): List<CashClosingRecord>

    @Query(
        """
        SELECT * FROM cash_closing_records
        WHERE username = :username AND closedAt >= :start AND closedAt < :end
        ORDER BY closedAt DESC LIMIT 1
        """
    )
    suspend fun latestByUserBetween(username: String, start: Long, end: Long): CashClosingRecord?

    @Query(
        """
        SELECT COALESCE(MAX(revisionNumber), 0) FROM cash_closing_records
        WHERE username = :username AND closedAt >= :start AND closedAt < :end
        """
    )
    suspend fun maxRevisionByUserBetween(username: String, start: Long, end: Long): Int

    @Query("SELECT * FROM cash_closing_records WHERE id = :id")
    suspend fun findById(id: Long): CashClosingRecord?

    @Query(
        """
        UPDATE cash_closing_records
        SET status = :status, reviewedBy = :reviewedBy, reviewedAt = :reviewedAt
        WHERE id = :id AND status = 'PENDING'
        """
    )
    suspend fun updateStatus(
        id: Long,
        status: CashClosingStatus,
        reviewedBy: String,
        reviewedAt: Long
    ): Int

    @Query(
        """
        UPDATE cash_closing_records
        SET status = 'REVERTED', reviewedBy = :reviewedBy, reviewedAt = :reviewedAt
        WHERE id = :id AND status = 'APPROVED'
        """
    )
    suspend fun revertApproved(
        id: Long,
        reviewedBy: String,
        reviewedAt: Long
    ): Int
}
