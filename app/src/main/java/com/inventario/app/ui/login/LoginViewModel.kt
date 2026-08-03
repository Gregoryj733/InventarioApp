package com.inventario.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.repository.AuthRepository
import com.inventario.app.data.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val loggedInRole: UserRole? = null
)

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        if (sessionManager.isLoggedIn()) {
            _state.update { it.copy(loggedInRole = sessionManager.role()) }
        }
    }

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun login() {
        val current = _state.value
        if (current.username.isBlank() || current.password.isBlank()) {
            _state.update { it.copy(error = "Ingresa usuario y contraseña.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val user = authRepository.login(current.username, current.password)
            if (user == null) {
                _state.update { it.copy(loading = false, error = "Credenciales incorrectas.") }
            } else {
                sessionManager.saveSession(user.username, user.role)
                _state.update { it.copy(loading = false, loggedInRole = user.role) }
            }
        }
    }

    companion object {
        fun factory(authRepository: AuthRepository, sessionManager: SessionManager) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LoginViewModel(authRepository, sessionManager) as T
                }
            }
    }
}
