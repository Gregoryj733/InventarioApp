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

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun token(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun username(): String? = prefs.getString(KEY_USER, null)

    fun sucursal(): String = prefs.getString(KEY_SUCURSAL, "").orEmpty()

    fun role(): UserRole? = prefs.getString(KEY_ROLE, null)?.let {
        runCatching { UserRole.valueOf(it) }.getOrNull()
    }

    fun isLoggedIn(): Boolean = username() != null && role() != null

    fun lastAcknowledgedClosingId(username: String): Long =
        prefs.getLong(ackKey(username), 0L)

    fun acknowledgeClosing(username: String, closingId: Long) {
        prefs.edit().putLong(ackKey(username), closingId).apply()
    }

    fun lastPlayedSoundEventKey(): String? =
        prefs.getString(KEY_LAST_SOUND_EVENT, null)

    fun markSoundEventPlayed(key: String) {
        prefs.edit().putString(KEY_LAST_SOUND_EVENT, key).apply()
    }

    private fun ackKey(username: String): String = "closing_ack_${username.trim().lowercase()}"

    companion object {
        private const val KEY_USER = "username"
        private const val KEY_ROLE = "role"
        private const val KEY_SUCURSAL = "sucursal"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_LAST_SOUND_EVENT = "last_sound_event"
    }
}
