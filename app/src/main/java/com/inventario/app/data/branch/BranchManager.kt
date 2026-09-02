package com.inventario.app.data.branch

import android.content.Context
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.entity.canSwitchBranch
import com.inventario.app.data.session.SessionManager
import com.inventario.app.data.sync.CloudConfigStore
import com.inventario.app.data.sync.SyncConfig

class BranchManager(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val catalog: BranchCatalog = BranchCatalog(context)
) {
    init {
        catalog.defaultBranch?.id?.let { defaultId ->
            sessionManager.migrateLegacyToken(defaultId)
            if (sessionManager.activeBranchId() == null) {
                sessionManager.setActiveBranchId(defaultId)
            }
        }
    }

    fun allBranches(): List<BranchConfig> = catalog.branches

    fun getActiveBranch(): BranchConfig? {
        val id = sessionManager.activeBranchId()
        return id?.let(catalog::findById) ?: catalog.defaultBranch
    }

    fun branchesForLogin(role: UserRole?, userSucursal: String): List<BranchConfig> =
        branchesVisibleToUser(role, userSucursal)

    /**
     * Sucursales que el usuario puede ver o usar.
     * - Admin/Supervisor: todas.
     * - Consulta/Ventas: solo la que coincide con [userSucursal].
     * - Sin sesión (role null): todas (selector en pantalla de login).
     */
    fun branchesVisibleToUser(role: UserRole?, userSucursal: String): List<BranchConfig> {
        if (role != null && role.canSwitchBranch()) return catalog.branches
        if (role == null) return catalog.branches
        // Consulta/Ventas: priorizar la instancia donde ya iniciaron sesión.
        getActiveBranch()?.let { return listOf(it) }
        branchForSucursal(userSucursal)?.let { return listOf(it) }
        return catalog.branches
    }

    fun branchForSucursal(sucursal: String): BranchConfig? =
        catalog.branches.firstOrNull { sucursalMatchesBranch(sucursal, it) }

    /**
     * Consulta/Ventas: si ya autenticaron en [authenticatedBranch], permitir acceso
     * salvo que el servidor indique otra sucursal explícita.
     */
    fun canLoginToBranch(role: UserRole, userSucursal: String, authenticatedBranch: BranchConfig): Boolean {
        if (role.canSwitchBranch()) return true
        val assignedBranch = branchForSucursal(userSucursal) ?: return true
        return assignedBranch.id == authenticatedBranch.id
    }

    /** Sucursal efectiva en sesión: la del usuario si es válida, si no la de la instancia autenticada. */
    fun effectiveSucursalForLogin(userSucursal: String, authenticatedBranch: BranchConfig): String {
        val assigned = branchForSucursal(userSucursal)
        return if (assigned != null && assigned.id == authenticatedBranch.id) {
            assigned.label
        } else {
            authenticatedBranch.label
        }
    }

    fun canAccessBranch(role: UserRole, userSucursal: String, branch: BranchConfig): Boolean {
        if (role.canSwitchBranch()) return true
        getActiveBranch()?.takeIf { it.id == branch.id }?.let { return true }
        val assigned = branchForSucursal(userSucursal) ?: return false
        return assigned.id == branch.id
    }

    /**
     * Fija la sucursal activa y elimina tokens de otras instancias para perfiles
     * Consulta/Ventas (evita cruce de datos si el dispositivo se usó antes con Admin).
     */
    fun enforceBranchIsolation(role: UserRole, userSucursal: String): BranchConfig? {
        if (role.canSwitchBranch()) return getActiveBranch()
        // Mantener la instancia sync-server de la última sesión válida; el
        // campo sucursal del usuario puede estar desactualizado o mal escrito.
        val branch = getActiveBranch() ?: branchForSucursal(userSucursal)
        return branch?.let { enforceBranchIsolationForBranch(role, it.id) }
    }

    fun enforceBranchIsolationForBranch(role: UserRole, branchId: String): BranchConfig? {
        if (role.canSwitchBranch()) return getActiveBranch()
        val branch = catalog.findById(branchId) ?: return null
        clearOtherBranchSessions(branch.id)
        if (sessionManager.activeBranchId() != branch.id) {
            activateBranch(branch.id)
        } else {
            sessionManager.tokenForBranch(branch.id)?.let { sessionManager.saveToken(it) }
        }
        return branch
    }

    fun clearOtherBranchSessions(keepBranchId: String) {
        val keep = com.inventario.app.data.branch.normalizeBranchId(keepBranchId)
        catalog.branches.forEach { branch ->
            if (com.inventario.app.data.branch.normalizeBranchId(branch.id) != keep) {
                sessionManager.clearTokenForBranch(branch.id)
            }
        }
    }

    fun requiresReauth(branchId: String): Boolean =
        sessionManager.tokenForBranch(branchId).isNullOrBlank()

    fun syncConfigForActiveBranch(): SyncConfig? {
        val branch = getActiveBranch() ?: return null
        return branch.toSyncConfig(catalog.fallbackUrls).copy(branchId = branch.id)
    }

    fun syncConfigForBranch(branchId: String): SyncConfig? =
        SyncConfig.loadForBranch(context, branchId)

    fun activateBranch(branchId: String) {
        val branch = catalog.findById(branchId) ?: return
        sessionManager.setActiveBranchId(branchId)
        val config = branch.toSyncConfig(catalog.fallbackUrls).copy(branchId = branchId)
        CloudConfigStore.save(context, config)
        val branchToken = sessionManager.tokenForBranch(branchId)
        if (!branchToken.isNullOrBlank()) {
            sessionManager.saveToken(branchToken)
        } else {
            sessionManager.clearActiveToken()
        }
    }

    fun saveBranchSession(branchId: String, token: String) {
        sessionManager.setActiveBranchId(branchId)
        sessionManager.saveTokenForBranch(branchId, token)
        val branch = catalog.findById(branchId) ?: return
        val config = branch.toSyncConfig(catalog.fallbackUrls).copy(branchId = branchId)
        CloudConfigStore.save(context, config)
    }

    fun firebaseTopicForBranch(branchId: String): String =
        catalog.findById(branchId)?.firebaseTopic ?: BranchCatalog.DEFAULT_FIREBASE_TOPIC

    fun firebaseTopicForActiveBranch(): String =
        getActiveBranch()?.firebaseTopic ?: BranchCatalog.DEFAULT_FIREBASE_TOPIC

    fun labelFor(branchId: String?): String = catalog.labelFor(branchId)

    fun displayLabelFor(branchId: String?): String = catalog.displayLabelFor(branchId)

    fun configFor(branchId: String?): BranchConfig? = catalog.configFor(branchId)
}
