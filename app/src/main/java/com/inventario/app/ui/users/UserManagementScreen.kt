package com.inventario.app.ui.users

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.entity.displayLabel
import com.inventario.app.data.entity.displaySucursal
import com.inventario.app.data.entity.sucursalPending
import com.inventario.app.data.repository.AuthRepository
import com.inventario.app.data.repository.InventoryRepository
import com.inventario.app.data.sync.CloudEvent
import com.inventario.app.data.sync.toUserMessage
import com.inventario.app.ui.theme.AppScreenBackground
import com.inventario.app.ui.theme.AppSnackbarController
import com.inventario.app.ui.theme.BrandWarning
import com.inventario.app.ui.theme.BrandAppTopBar
import com.inventario.app.ui.theme.StatusPill
import com.inventario.app.ui.theme.screenHorizontalPadding
import com.inventario.app.ui.theme.screenVerticalPadding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val CREATABLE_ROLES = listOf(UserRole.CONSULTA, UserRole.VENTAS, UserRole.SUPERVISOR)

data class UserManagementUiState(
    val users: List<User> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val showCreateDialog: Boolean = false,
    val newUsername: String = "",
    val newPassword: String = "",
    val newSucursal: String = "",
    val newRole: UserRole = UserRole.CONSULTA,
    val creating: Boolean = false,
    val assignSucursalUserId: Long? = null,
    val assignSucursalText: String = "",
    val assigningSucursal: Boolean = false,
    val editingRoleUserId: Long? = null,
    val editingRoleValue: UserRole = UserRole.CONSULTA,
    val savingRole: Boolean = false,
    val discountPercentText: String = "",
    val savingDiscountPercent: Boolean = false,
    val discountPercentMessage: String? = null,
    val discountPercentMessageIsError: Boolean = false
)

class UserManagementViewModel(
    private val authRepository: AuthRepository,
    private val cloudEvents: SharedFlow<CloudEvent>? = null,
    private val inventoryRepository: InventoryRepository? = null,
    private val defaultBranchLabel: String = ""
) : ViewModel() {
    private val _state = MutableStateFlow(UserManagementUiState())
    val state: StateFlow<UserManagementUiState> = _state.asStateFlow()

    init {
        refresh()
        cloudEvents?.let { events ->
            viewModelScope.launch {
                // Si el rol o estado de un usuario cambia desde otro
                // dispositivo (u otra sesión de administrador), esta lista
                // se refresca sola.
                events.collect { event ->
                    if (event is CloudEvent.Users) refresh()
                }
            }
        }
        inventoryRepository?.let { repository ->
            viewModelScope.launch {
                repository.observeMeta().collect { meta ->
                    meta?.discountPercent?.let { percent ->
                        _state.update {
                            if (it.savingDiscountPercent) it else it.copy(discountPercentText = formatPercent(percent))
                        }
                    }
                }
            }
        }
    }

    fun onDiscountPercentTextChange(value: String) {
        val cleaned = value.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.')
        _state.update {
            it.copy(discountPercentText = cleaned, discountPercentMessage = null, discountPercentMessageIsError = false)
        }
    }

    fun saveDiscountPercent() {
        val repository = inventoryRepository ?: return
        val percent = _state.value.discountPercentText.toDoubleOrNull()
        if (percent == null || percent <= 0 || percent > 100) {
            _state.update {
                it.copy(
                    discountPercentMessage = "Ingresa un porcentaje válido (1-100).",
                    discountPercentMessageIsError = true
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(savingDiscountPercent = true, discountPercentMessage = null) }
            repository.saveDiscountPercent(percent)
                .onSuccess {
                    _state.update {
                        it.copy(
                            savingDiscountPercent = false,
                            discountPercentText = formatPercent(percent),
                            discountPercentMessage = "Porcentaje de descuento actualizado.",
                            discountPercentMessageIsError = false
                        )
                    }
                    AppSnackbarController.show("Descuento de tickets actualizado a ${formatPercent(percent)}%.")
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            savingDiscountPercent = false,
                            discountPercentMessage = err.toUserMessage("No se pudo guardar el porcentaje."),
                            discountPercentMessageIsError = true
                        )
                    }
                }
        }
    }

    private fun formatPercent(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { authRepository.listManagedUsers() }
                .onSuccess { users ->
                    _state.update { it.copy(loading = false, users = users) }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(loading = false, error = err.toUserMessage("Error al cargar usuarios."))
                    }
                }
        }
    }

    fun openCreateDialog() {
        _state.update {
            it.copy(
                showCreateDialog = true,
                newUsername = "",
                newPassword = "",
                newSucursal = defaultBranchLabel,
                newRole = UserRole.CONSULTA,
                message = null,
                error = null
            )
        }
    }

    fun dismissCreateDialog() {
        _state.update {
            it.copy(showCreateDialog = false, newUsername = "", newPassword = "", newSucursal = "")
        }
    }

    fun onNewUsernameChange(value: String) {
        _state.update { it.copy(newUsername = value, error = null) }
    }

    fun onNewPasswordChange(value: String) {
        _state.update { it.copy(newPassword = value, error = null) }
    }

    fun onNewSucursalChange(value: String) {
        _state.update { it.copy(newSucursal = value, error = null) }
    }

    fun onNewRoleChange(role: UserRole) {
        _state.update { it.copy(newRole = role, error = null) }
    }

    fun createUser() {
        val current = _state.value
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null) }
            authRepository.createManagedUser(
                current.newUsername,
                current.newPassword,
                current.newSucursal,
                current.newRole
            )
                .onSuccess {
                    _state.update {
                        it.copy(
                            creating = false,
                            showCreateDialog = false,
                            newUsername = "",
                            newPassword = "",
                            newSucursal = "",
                            newRole = UserRole.CONSULTA,
                            message = "Usuario creado correctamente."
                        )
                    }
                    refresh()
                    AppSnackbarController.show("Usuario \"${current.newUsername.trim()}\" creado correctamente.")
                }
                .onFailure { err ->
                    val message = err.toUserMessage("No se pudo crear el usuario.")
                    _state.update {
                        it.copy(creating = false, error = message)
                    }
                    AppSnackbarController.show(message)
                }
        }
    }

    fun toggleActive(user: User) {
        viewModelScope.launch {
            authRepository.setManagedUserActive(user.id, !user.active)
                .onSuccess {
                    refresh()
                    AppSnackbarController.show(
                        if (!user.active) "Usuario \"${user.username}\" activado." else "Usuario \"${user.username}\" desactivado."
                    )
                }
                .onFailure { err ->
                    val message = err.toUserMessage("No se pudo actualizar el usuario.")
                    _state.update { it.copy(error = message) }
                    AppSnackbarController.show(message)
                }
        }
    }

    fun deleteUser(user: User) {
        viewModelScope.launch {
            authRepository.deleteManagedUser(user.id)
                .onSuccess {
                    _state.update { it.copy(message = "Usuario eliminado.") }
                    refresh()
                    AppSnackbarController.show("Usuario \"${user.username}\" eliminado.")
                }
                .onFailure { err ->
                    val message = err.toUserMessage("No se pudo eliminar el usuario.")
                    _state.update { it.copy(error = message) }
                    AppSnackbarController.show(message)
                }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null, error = null) }
    }

    fun openEditRoleDialog(user: User) {
        _state.update {
            it.copy(editingRoleUserId = user.id, editingRoleValue = user.role, error = null)
        }
    }

    fun dismissEditRoleDialog() {
        _state.update { it.copy(editingRoleUserId = null, savingRole = false) }
    }

    fun onEditingRoleChange(role: UserRole) {
        _state.update { it.copy(editingRoleValue = role) }
    }

    fun saveRole() {
        val userId = _state.value.editingRoleUserId ?: return
        val role = _state.value.editingRoleValue
        viewModelScope.launch {
            _state.update { it.copy(savingRole = true, error = null) }
            authRepository.updateManagedUserRole(userId, role)
                .onSuccess {
                    _state.update {
                        it.copy(
                            savingRole = false,
                            editingRoleUserId = null,
                            message = "Perfil actualizado a \"${role.displayLabel()}\"."
                        )
                    }
                    refresh()
                    AppSnackbarController.show("Perfil actualizado a \"${role.displayLabel()}\".")
                }
                .onFailure { err ->
                    val message = err.toUserMessage("No se pudo actualizar el perfil.")
                    _state.update { it.copy(savingRole = false, error = message) }
                    AppSnackbarController.show(message)
                }
        }
    }

    fun openAssignSucursalDialog(user: User) {
        _state.update {
            it.copy(
                assignSucursalUserId = user.id,
                assignSucursalText = user.sucursal,
                error = null
            )
        }
    }

    fun dismissAssignSucursalDialog() {
        _state.update {
            it.copy(assignSucursalUserId = null, assignSucursalText = "", assigningSucursal = false)
        }
    }

    fun onAssignSucursalTextChange(value: String) {
        _state.update { it.copy(assignSucursalText = value, error = null) }
    }

    fun assignSucursal() {
        val userId = _state.value.assignSucursalUserId ?: return
        val branch = _state.value.assignSucursalText
        viewModelScope.launch {
            _state.update { it.copy(assigningSucursal = true, error = null) }
            authRepository.assignManagedUserSucursal(userId, branch)
                .onSuccess {
                    _state.update {
                        it.copy(
                            assigningSucursal = false,
                            assignSucursalUserId = null,
                            assignSucursalText = "",
                            message = "Sucursal asignada correctamente."
                        )
                    }
                    refresh()
                    AppSnackbarController.show("Sucursal asignada correctamente.")
                }
                .onFailure { err ->
                    val message = err.toUserMessage("No se pudo asignar la sucursal.")
                    _state.update {
                        it.copy(
                            assigningSucursal = false,
                            error = message
                        )
                    }
                    AppSnackbarController.show(message)
                }
        }
    }

    companion object {
        fun factory(
            authRepository: AuthRepository,
            cloudEvents: SharedFlow<CloudEvent>? = null,
            inventoryRepository: InventoryRepository? = null,
            defaultBranchLabel: String = ""
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return UserManagementViewModel(
                    authRepository,
                    cloudEvents,
                    inventoryRepository,
                    defaultBranchLabel
                ) as T
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
            title = { Text("Nuevo usuario", fontWeight = FontWeight.Bold) },
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
                    OutlinedTextField(
                        value = state.newSucursal,
                        onValueChange = viewModel::onNewSucursalChange,
                        label = { Text("Sucursal") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Perfil",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CREATABLE_ROLES.forEach { role ->
                            FilterChip(
                                selected = state.newRole == role,
                                onClick = { viewModel.onNewRoleChange(role) },
                                label = { Text(role.displayLabel()) }
                            )
                        }
                    }
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

    if (state.editingRoleUserId != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissEditRoleDialog,
            title = { Text("Cambiar perfil", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Corrige el perfil de la cuenta si quedó asignado por error.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CREATABLE_ROLES.forEach { role ->
                            FilterChip(
                                selected = state.editingRoleValue == role,
                                onClick = { viewModel.onEditingRoleChange(role) },
                                label = { Text(role.displayLabel()) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::saveRole,
                    enabled = !state.savingRole
                ) {
                    Text(if (state.savingRole) "Guardando…" else "Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissEditRoleDialog) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (state.assignSucursalUserId != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAssignSucursalDialog,
            title = { Text("Asignar sucursal", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = state.assignSucursalText,
                    onValueChange = viewModel::onAssignSucursalTextChange,
                    label = { Text("Sucursal") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = viewModel::assignSucursal,
                    enabled = !state.assigningSucursal
                ) {
                    Text(if (state.assigningSucursal) "Guardando…" else "Asignar")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAssignSucursalDialog) {
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
                    text = "Usuarios",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Crear, activar, desactivar o eliminar cuentas con perfil Supervisor o Consulta. " +
                        "Toca el perfil de un usuario para corregirlo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                DiscountPercentCard(state = state, viewModel = viewModel)
                Spacer(Modifier.height(16.dp))
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
                            onDelete = { viewModel.deleteUser(user) },
                            onAssignSucursal = { viewModel.openAssignSucursalDialog(user) },
                            onEditRole = { viewModel.openEditRoleDialog(user) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Porcentaje de descuento fijo aplicado a todos los tickets nuevos (se guarda
 * en `meta.discountPercent` vía el endpoint genérico PUT /v1/meta). Solo
 * ADMIN puede verlo/editarlo, ya que `UserManagementScreen` está restringida
 * a ese rol.
 */
@Composable
private fun DiscountPercentCard(
    state: UserManagementUiState,
    viewModel: UserManagementViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Descuento de tickets",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                "Porcentaje aplicado a todos los tickets de descuento generados (vigencia de 30 días).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.discountPercentText,
                    onValueChange = viewModel::onDiscountPercentTextChange,
                    label = { Text("Porcentaje (%)") },
                    singleLine = true,
                    enabled = !state.savingDiscountPercent,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = viewModel::saveDiscountPercent,
                    enabled = !state.savingDiscountPercent,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (state.savingDiscountPercent) "Guardando…" else "Guardar")
                }
            }
            if (state.discountPercentMessage != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    state.discountPercentMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.discountPercentMessageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    }
}

@Composable
private fun UserRow(
    user: User,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
    onAssignSucursal: () -> Unit,
    onEditRole: () -> Unit
) {
    val pending = user.sucursalPending()
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
                Text(
                    user.displaySucursal(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pending) BrandWarning else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(
                        text = user.role.displayLabel(),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.clickable(onClick = onEditRole)
                    )
                    StatusPill(
                        text = if (user.active) "Activo" else "Inactivo",
                        color = if (user.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                if (pending) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onAssignSucursal,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Asignar sucursal")
                    }
                }
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
