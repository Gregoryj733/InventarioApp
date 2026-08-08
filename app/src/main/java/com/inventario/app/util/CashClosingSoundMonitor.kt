package com.inventario.app.util

import android.content.Context
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.repository.InventoryRepository
import com.inventario.app.data.session.SessionManager

enum class CashClosingSoundEvent {
    SUBMITTED,
    APPROVED,
    REJECTED,
    REVERSED
}

/**
 * Detecta cambios en los cierres de caja del día y emite alertas auditivas +
 * popup para el supervisor/administrador y el usuario que envió el cierre.
 */
class CashClosingSoundMonitor(
    private val context: Context,
    private val sessionManager: SessionManager
) {
    private var knownClosings: Map<Long, CashClosingRecord> = emptyMap()
    private var initialized = false

    suspend fun refresh(inventoryRepository: InventoryRepository) {
        val closings = inventoryRepository.todayCashClosings()
        if (!initialized) {
            knownClosings = closings.associateBy { it.id }
            initialized = true
            return
        }

        val username = sessionManager.username()?.trim()?.lowercase().orEmpty()
        val role = sessionManager.role()
        val canReview = role == UserRole.SUPERVISOR || role == UserRole.ADMIN

        closings.forEach { closing ->
            val previous = knownClosings[closing.id]
            val event = when {
                previous == null && closing.status == CashClosingStatus.PENDING ->
                    CashClosingSoundEvent.SUBMITTED
                previous != null && previous.status != closing.status -> when (closing.status) {
                    CashClosingStatus.APPROVED -> CashClosingSoundEvent.APPROVED
                    CashClosingStatus.REJECTED -> CashClosingSoundEvent.REJECTED
                    CashClosingStatus.REVERTED -> CashClosingSoundEvent.REVERSED
                    else -> null
                }
                else -> null
            }

            if (event != null && shouldNotifyUser(event, closing, username, canReview)) {
                notify(event, closing)
            }
        }

        knownClosings = closings.associateBy { it.id }
    }

    fun notifySubmittedForSubmitter() {
        val (title, message) = AppNotificationMessages.cashClosingSubmittedSelf()
        AppNotificationHelper.notify(
            context = context,
            sessionManager = sessionManager,
            dedupeKey = "closing_submitted_self_${System.currentTimeMillis()}",
            title = title,
            message = message
        )
    }

    fun reset() {
        initialized = false
        knownClosings = emptyMap()
    }

    private fun shouldNotifyUser(
        event: CashClosingSoundEvent,
        closing: CashClosingRecord,
        username: String,
        canReview: Boolean
    ): Boolean {
        val isSubmitter = closing.username.equals(username, ignoreCase = true)
        return when (event) {
            CashClosingSoundEvent.SUBMITTED -> canReview
            CashClosingSoundEvent.APPROVED,
            CashClosingSoundEvent.REJECTED,
            CashClosingSoundEvent.REVERSED -> canReview || isSubmitter
        }
    }

    private fun notify(event: CashClosingSoundEvent, closing: CashClosingRecord) {
        val (title, message) = when (event) {
            CashClosingSoundEvent.SUBMITTED -> {
                val user = closing.username.ifBlank { "Un usuario" }
                AppNotificationMessages.cashClosingSubmittedRemote(user, closing.branchName)
            }
            CashClosingSoundEvent.APPROVED -> AppNotificationMessages.cashClosingApproved()
            CashClosingSoundEvent.REJECTED -> AppNotificationMessages.cashClosingRejected()
            CashClosingSoundEvent.REVERSED -> AppNotificationMessages.cashClosingReversed()
        }
        AppNotificationHelper.notify(
            context = context,
            sessionManager = sessionManager,
            dedupeKey = "${closing.id}_${event.name}",
            title = title,
            message = message
        )
    }
}
