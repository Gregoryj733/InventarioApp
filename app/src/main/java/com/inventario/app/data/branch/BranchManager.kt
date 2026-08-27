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

    fun branchesForLogin(role: UserRole?, userSucursal: String): List<BranchConfig> {
        if (role != null && role.canSwitchBranch()) return catalog.branches
        if (userSucursal.isNotBlank()) {
            return catalog.branches.filter { branchLabelsMatch(it.label, userSucursal) }
        }
        return catalog.branches
    }

    fun canAccessBranch(role: UserRole, userSucursal: String, branch: BranchConfig): Boolean {
        if (role.canSwitchBranch()) return true
        if (userSucursal.isBlank()) return false
        return branchLabelsMatch(userSucursal, branch.label)
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
        sessionManager.tokenForBranch(branchId)?.let { sessionManager.saveToken(it) }
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

    fun configFor(branchId: String?): BranchConfig? = catalog.configFor(branchId)
}
