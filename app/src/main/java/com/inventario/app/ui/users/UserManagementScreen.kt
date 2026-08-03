package com.inventario.app.ui.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.entity.User
import com.inventario.app.data.repository.AuthRepository
import com.inventario.app.ui.theme.AppScreenBackground
import com.inventario.app.ui.theme.BrandAppTopBar
import com.inventario.app.ui.theme.StatusPill
import com.inventario.app.ui.theme.screenHorizontalPadding
import com.inventario.app.ui.theme.screenVerticalPadding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UserManagementUiState(
    val users: List<User> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val showCreateDialog: Boolean = false,
    val newUsername: String = "",
    val newPassword: String = "",
    val creating: Boolean = false
)

class UserManagementViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {
    private val _state = MutableStateFlow(UserManagementUiState())
    val state: StateFlow<UserManagementUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { authRepository.listConsultaUsers() }
                .onSuccess { users ->
                    _state.update { it.copy(loading = false, users = users) }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(loading = false, error = err.message ?: "Error al cargar usuarios.")
                    }
                }
        }
    }

    fun openCreateDialog() {
        _state.update {
            it.copy(showCreateDialog = true, newUsername = "", newPassword = "", message = null, error = null)
        }
    }

    fun dismissCreateDialog() {
        _state.update { it.copy(showCreateDialog = false, newUsername = "", newPassword = "") }
    }

    fun onNewUsernameChange(value: String) {
        _state.update { it.copy(newUsername = value, error = null) }
    }

    fun onNewPasswordChange(value: String) {
        _state.update { it.copy(newPassword = value, error = null) }
    }

    fun createUser() {
        val current = _state.value
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null) }
            authRepository.createConsultaUser(current.newUsername, current.newPassword)
                .onSuccess {
                    _state.update {
                        it.copy(
                            creating = false,
                            showCreateDialog = false,
                            newUsername = "",
                            newPassword = "",
                            message = "Usuario creado correctamente."
                        )
                    }
                    refresh()
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(creating = false, error = err.message ?: "No se pudo crear el usuario.")
                    }
                }
        }
    }

    fun toggleActive(user: User) {
        viewModelScope.launch {
            authRepository.setConsultaUserActive(user.id, !user.active)
                .onSuccess { refresh() }
                .onFailure { err ->
                    _state.update { it.copy(error = err.message ?: "No se pudo actualizar el usuario.") }
                }
        }
    }

    fun deleteUser(user: User) {
        viewModelScope.launch {
            authRepository.deleteConsultaUser(user.id)
                .onSuccess {
                    _state.update { it.copy(message = "Usuario eliminado.") }
                    refresh()
                }
                .onFailure { err ->
                    _state.update { it.copy(error = err.message ?: "No se pudo eliminar el usuario.") }
                }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null, error = null) }
    }

    companion object {
        fun factory(authRepository: AuthRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return UserManagementViewModel(authRepository) as T
            }
        }
    }
}

@Composable
fun UserManagementScreen(
    viewModel: UserManagementViewModel,
    subtitle: String,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    if (state.showCreateDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCreateDialog,
            title = { Text("Nuevo usuario consulta", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.newUsername,
                        onValueChange = viewModel::onNewUsernameChange,
                        label = { Text("Usuario") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.newPassword,
                        onValueChange = viewModel::onNewPasswordChange,
                        label = { Text("Contraseña") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::createUser,
                    enabled = !state.creating
                ) {
                    Text(if (state.creating) "Creando…" else "Crear")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCreateDialog) {
                    Text("Cancelar")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            BrandAppTopBar(
                subtitle = subtitle,
                onRefreshBcv = {},
                onLogout = onLogout,
                showBack = true,
                onBack = onBack
            )
        }
    ) { padding ->
        AppScreenBackground(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = screenHorizontalPadding(), vertical = screenVerticalPadding())
            ) {
                Text(
                    text = "Usuarios consulta",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Crear, activar, desactivar o eliminar usuarios con rol consulta.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = viewModel::openCreateDialog,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Nuevo usuario")
                }
                if (state.message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.message!!, color = MaterialTheme.colorScheme.primary)
                }
                if (state.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.users, key = { it.id }) { user ->
                        UserRow(
                            user = user,
                            onToggleActive = { viewModel.toggleActive(user) },
                            onDelete = { viewModel.deleteUser(user) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserRow(
    user: User,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(user.username, fontWeight = FontWeight.SemiBold)
                StatusPill(
                    text = if (user.active) "Activo" else "Inactivo",
                    color = if (user.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = user.active, onCheckedChange = { onToggleActive() })
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Eliminar")
                }
            }
        }
    }
}
