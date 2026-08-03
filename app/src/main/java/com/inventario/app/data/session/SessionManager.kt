package com.inventario.app.data.session

import android.content.Context
import com.inventario.app.data.entity.UserRole

class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("inventario_session", Context.MODE_PRIVATE)

    fun saveSession(username: String, role: UserRole) {
        prefs.edit()
            .putString(KEY_USER, username)
            .putString(KEY_ROLE, role.name)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun username(): String? = prefs.getString(KEY_USER, null)

    fun role(): UserRole? = prefs.getString(KEY_ROLE, null)?.let {
        runCatching { UserRole.valueOf(it) }.getOrNull()
    }

    fun isLoggedIn(): Boolean = username() != null && role() != null

    companion object {
        private const val KEY_USER = "username"
        private const val KEY_ROLE = "role"
    }
}
