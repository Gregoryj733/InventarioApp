package com.inventario.app.ui.login



import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.ExperimentalLayoutApi

import androidx.compose.foundation.layout.FlowRow

import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.imePadding

import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.systemBarsPadding

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.text.KeyboardActions

import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.Visibility

import androidx.compose.material.icons.filled.VisibilityOff

import androidx.compose.material3.Button

import androidx.compose.material3.Card

import androidx.compose.material3.CardDefaults

import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.material3.FilterChip

import androidx.compose.material3.Icon

import androidx.compose.material3.IconButton

import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.OutlinedTextField

import androidx.compose.material3.Text

import androidx.compose.runtime.Composable

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.collectAsState

import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.remember

import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.input.ImeAction

import androidx.compose.ui.text.input.KeyboardType

import androidx.compose.ui.text.input.PasswordVisualTransformation

import androidx.compose.ui.text.input.VisualTransformation

import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.unit.dp

import com.inventario.app.ui.theme.AppScreenBackground

import com.inventario.app.ui.theme.AppBrandTitle
import com.inventario.app.ui.theme.BranchBrandLogoSplash
import com.inventario.app.ui.theme.BranchBrandTitle

import com.inventario.app.ui.theme.screenHorizontalPadding



@OptIn(ExperimentalLayoutApi::class)

@Composable

fun LoginScreen(

    viewModel: LoginViewModel,

    onLoggedIn: () -> Unit

) {

    val state by viewModel.state.collectAsState()

    var passwordVisible by remember { mutableStateOf(false) }



    LaunchedEffect(state.loggedInRole) {

        if (state.loggedInRole != null) onLoggedIn()

    }



    AppScreenBackground(modifier = Modifier.fillMaxSize()) {

    Box(modifier = Modifier.fillMaxSize()) {

        Column(

            modifier = Modifier

                .fillMaxSize()

                .systemBarsPadding()

                .imePadding()

                .verticalScroll(rememberScrollState())

                .padding(screenHorizontalPadding() + 8.dp),

            verticalArrangement = Arrangement.Center,

            horizontalAlignment = Alignment.CenterHorizontally

        ) {

            AppBrandTitle(modifier = Modifier.padding(bottom = 8.dp))

            BranchBrandLogoSplash(

                branchId = state.selectedBranchId,

                modifier = Modifier.padding(bottom = 4.dp)

            )

            if (state.branches.size == 1) {
                BranchBrandTitle(
                    branchId = state.selectedBranchId,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            if (state.loading) {

                Spacer(Modifier.height(16.dp))

                CircularProgressIndicator(

                    modifier = Modifier.size(28.dp),

                    strokeWidth = 2.5.dp,

                    color = MaterialTheme.colorScheme.primary

                )

            }

            Spacer(Modifier.height(24.dp))

            Card(

                modifier = Modifier.fillMaxWidth(),

                shape = RoundedCornerShape(24.dp),

                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),

                elevation = CardDefaults.cardElevation(6.dp)

            ) {

                Column(

                    modifier = Modifier

                        .fillMaxWidth()

                        .padding(horizontal = 24.dp, vertical = 32.dp),

                    verticalArrangement = Arrangement.Center,

                    horizontalAlignment = Alignment.CenterHorizontally

                ) {

                Text(

                    text = "Consulta productos, precios y stock",

                    style = MaterialTheme.typography.bodyLarge,

                    color = MaterialTheme.colorScheme.onSurfaceVariant,

                    textAlign = TextAlign.Center

                )

                Spacer(Modifier.height(28.dp))



                if (state.branches.size > 1) {

                    Text(

                        text = "Sucursal",

                        style = MaterialTheme.typography.labelLarge,

                        modifier = Modifier

                            .fillMaxWidth()

                            .padding(bottom = 8.dp)

                    )

                    FlowRow(

                        modifier = Modifier.fillMaxWidth(),

                        horizontalArrangement = Arrangement.spacedBy(8.dp),

                        verticalArrangement = Arrangement.spacedBy(8.dp)

                    ) {

                        state.branches.forEach { branch ->

                            FilterChip(

                                selected = branch.id == state.selectedBranchId,

                                onClick = { viewModel.onBranchSelected(branch.id) },

                                label = { Text(branch.label) },

                                enabled = !state.loading

                            )

                        }

                    }

                    Spacer(Modifier.height(16.dp))

                } else if (state.branches.size == 1) {

                    Text(

                        text = state.branches.first().label,

                        style = MaterialTheme.typography.titleSmall,

                        color = MaterialTheme.colorScheme.primary,

                        modifier = Modifier

                            .fillMaxWidth()

                            .padding(bottom = 12.dp),

                        textAlign = TextAlign.Center

                    )

                }



                OutlinedTextField(

                    value = state.username,

                    onValueChange = viewModel::onUsernameChange,

                    modifier = Modifier.fillMaxWidth(),

                    label = { Text("Usuario") },

                    singleLine = true,

                    keyboardOptions = KeyboardOptions(

                        keyboardType = KeyboardType.Text,

                        imeAction = ImeAction.Next

                    ),

                    shape = RoundedCornerShape(12.dp)

                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(

                    value = state.password,

                    onValueChange = viewModel::onPasswordChange,

                    modifier = Modifier.fillMaxWidth(),

                    label = { Text("Contraseña") },

                    singleLine = true,

                    visualTransformation = if (passwordVisible) {

                        VisualTransformation.None

                    } else {

                        PasswordVisualTransformation()

                    },

                    trailingIcon = {

                        IconButton(onClick = { passwordVisible = !passwordVisible }) {

                            Icon(

                                imageVector = if (passwordVisible) {

                                    Icons.Default.VisibilityOff

                                } else {

                                    Icons.Default.Visibility

                                },

                                contentDescription = if (passwordVisible) {

                                    "Ocultar contraseña"

                                } else {

                                    "Mostrar contraseña"

                                }

                            )

                        }

                    },

                    keyboardOptions = KeyboardOptions(

                        keyboardType = KeyboardType.Password,

                        imeAction = ImeAction.Done

                    ),

                    keyboardActions = KeyboardActions(onDone = { viewModel.login() }),

                    shape = RoundedCornerShape(12.dp)

                )



                if (state.error != null) {

                    Spacer(Modifier.height(10.dp))

                    Text(state.error!!, color = MaterialTheme.colorScheme.error)

                } else if (state.loading && state.statusMessage != null) {

                    Spacer(Modifier.height(10.dp))

                    Text(

                        text = state.statusMessage!!,

                        color = MaterialTheme.colorScheme.onSurfaceVariant,

                        style = MaterialTheme.typography.bodyMedium,

                        textAlign = TextAlign.Center

                    )

                }



                Spacer(Modifier.height(20.dp))

                Button(

                    onClick = viewModel::login,

                    enabled = !state.loading,

                    modifier = Modifier

                        .fillMaxWidth()

                        .height(52.dp),

                    shape = RoundedCornerShape(14.dp)

                ) {

                    if (state.loading) {

                        CircularProgressIndicator(

                            modifier = Modifier.size(22.dp),

                            color = MaterialTheme.colorScheme.onPrimary,

                            strokeWidth = 2.dp

                        )

                    } else {

                        Text("Ingresar", fontWeight = FontWeight.SemiBold)

                    }

                }

                }

            }

        }

    }

    }

}


