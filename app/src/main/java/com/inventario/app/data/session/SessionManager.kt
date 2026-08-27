package com.inventario.app.data.session

import android.content.Context
import com.inventario.app.data.entity.UserRole

class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("inventario_session", Context.MODE_PRIVATE)

    fun saveSession(username: String, role: UserRole, sucursal: String = "") {
        prefs.edit()
            .putString(KEY_USER, username)
            .putString(KEY_ROLE, role.name)
            .putString(KEY_SUCURSAL, sucursal)
            .apply()
    }

    fun activeBranchId(): String? =
        prefs.getString(KEY_ACTIVE_BRANCH, null)?.takeIf { it.isNotBlank() }?.let {
            com.inventario.app.data.branch.normalizeBranchId(it)
        }

    fun setActiveBranchId(branchId: String) {
        prefs.edit().putString(
            KEY_ACTIVE_BRANCH,
            com.inventario.app.data.branch.normalizeBranchId(branchId)
        ).apply()
    }

    fun tokenForBranch(branchId: String): String? {
        val normalized = com.inventario.app.data.branch.normalizeBranchId(branchId)
        prefs.getString(tokenKey(normalized), null)?.takeIf { it.isNotBlank() }?.let { return it }
        legacyTokenKey(normalized)?.let { legacy ->
            prefs.getString(legacy, null)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    fun saveTokenForBranch(branchId: String, token: String) {
        val normalized = com.inventario.app.data.branch.normalizeBranchId(branchId)
        prefs.edit()
            .putString(tokenKey(normalized), token)
            .apply()
        if (activeBranchId() == normalized) {
            prefs.edit().putString(KEY_TOKEN, token).apply()
        }
    }

    fun saveToken(token: String) {
        val branchId = activeBranchId()
        if (branchId != null) {
            saveTokenForBranch(branchId, token)
        } else {
            prefs.edit().putString(KEY_TOKEN, token).apply()
        }
    }

    fun token(): String? {
        val branchId = activeBranchId()
        if (branchId != null) {
            tokenForBranch(branchId)?.let { return it }
        }
        return prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
    }

    fun clearTokenForBranch(branchId: String) {
        prefs.edit().remove(tokenKey(branchId)).apply()
        legacyTokenKey(branchId)?.let { prefs.edit().remove(it).apply() }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun username(): String? = prefs.getString(KEY_USER, null)

    fun sucursal(): String = prefs.getString(KEY_SUCURSAL, "").orEmpty()

    fun role(): UserRole? = prefs.getString(KEY_ROLE, null)?.let {
        runCatching { UserRole.valueOf(it) }.getOrNull()
    }

    fun isLoggedIn(): Boolean = username() != null && role() != null && token() != null

    fun lastAcknowledgedClosingId(username: String): Long =
        prefs.getLong(ackKey(username), 0L)

    fun acknowledgeClosing(username: String, closingId: Long) {
        prefs.edit().putLong(ackKey(username), closingId).apply()
    }

    fun lastPlayedSoundEventKey(): String? =
        prefs.getString(scopedKey(KEY_LAST_SOUND_EVENT), null)

    fun markSoundEventPlayed(key: String) {
        prefs.edit().putString(scopedKey(KEY_LAST_SOUND_EVENT), key).apply()
    }

    fun migrateLegacyToken(defaultBranchId: String) {
        val legacy = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return
        if (tokenForBranch(defaultBranchId) != null) return
        saveTokenForBranch(defaultBranchId, legacy)
        if (activeBranchId() == null) {
            setActiveBranchId(defaultBranchId)
        }
    }

    private fun ackKey(username: String): String =
        "closing_ack_${branchScope()}_${username.trim().lowercase()}"

    private fun tokenKey(branchId: String): String =
        "auth_token_${com.inventario.app.data.branch.normalizeBranchId(branchId)}"

    private fun legacyTokenKey(branchId: String): String? = when (
        com.inventario.app.data.branch.normalizeBranchId(branchId)
    ) {
        "total_care" -> "auth_token_sucursal_a"
        "supra_parts" -> "auth_token_sucursal_b"
        else -> null
    }

    private fun scopedKey(key: String): String = "${branchScope()}_$key"

    private fun branchScope(): String = activeBranchId().orEmpty().ifBlank { "default" }

    companion object {
        private const val KEY_USER = "username"
        private const val KEY_ROLE = "role"
        private const val KEY_SUCURSAL = "sucursal"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_ACTIVE_BRANCH = "active_branch_id"
        private const val KEY_LAST_SOUND_EVENT = "last_sound_event"
    }
}
