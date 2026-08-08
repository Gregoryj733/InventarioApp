package com.inventario.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.inventario.app.data.entity.displayStatusLabel
import com.inventario.app.data.entity.DISCOUNT_TICKET_CONDITIONS
import com.inventario.app.data.entity.DiscountTicket
import com.inventario.app.ui.theme.BrandSuccess

/**
 * Sección de canje de ticket dentro del carrito (`OrderSummaryCard`): entrada
 * de código + escaneo QR cuando no hay ninguno aplicado, o un chip con el
 * descuento activo y un botón para quitarlo.
 */
@Composable
fun DiscountTicketSection(
    state: HomeUiState,
    viewModel: HomeViewModel,
    onScanRequested: () -> Unit
) {
    val ticket = state.appliedDiscountTicket
    if (ticket != null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = BrandSuccess.copy(alpha = 0.12f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LocalOffer, contentDescription = null, tint = BrandSuccess)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Cupón ${ticket.code} · -${viewModel.formatQty(ticket.discountPercent)}%",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Estado: ${ticket.displayStatusLabel()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = viewModel::removeDiscountTicket) {
                    Icon(Icons.Default.Close, contentDescription = "Quitar ticket")
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "¿El cliente tiene un ticket de descuento?",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.discountTicketCodeInput,
                    onValueChange = viewModel::onDiscountTicketCodeChange,
                    label = { Text("Código del ticket") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        capitalization = KeyboardCapitalization.Characters
                    ),
                    keyboardActions = KeyboardActions(onDone = { viewModel.applyDiscountTicket() })
                )
                IconButton(onClick = onScanRequested) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Escanear código QR")
                }
            }
            if (state.discountTicketError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.discountTicketError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { viewModel.applyDiscountTicket() },
                enabled = !state.validatingDiscountTicket && state.discountTicketCodeInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (state.validatingDiscountTicket) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Validar ticket")
            }
        }
    }
}

@Composable
fun GenerateDiscountTicketOfferCard(
    onGenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LocalOffer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "¿Generar cupón de descuento?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "El cupón debe activarse escaneando el QR desde «Activar cupón» en la app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Generar código de descuento")
            }
        }
    }
}

@Composable
fun GenerateDiscountTicketDialog(
    state: HomeUiState,
    viewModel: HomeViewModel,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generar cupón") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Se creará un código único sin datos personales. " +
                        "Imprímelo en carnet o comparte el QR para activarlo en tienda.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (state.generateTicketError != null) {
                    Text(
                        state.generateTicketError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = viewModel::submitGenerateTicket,
                enabled = !state.generatingTicket
            ) {
                if (state.generatingTicket) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Generar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
fun GeneratedDiscountTicketDialog(
    ticket: DiscountTicket,
    viewModel: HomeViewModel,
    onDismiss: () -> Unit,
    onShare: (String) -> Unit
) {
    val qrBitmap = remember(ticket.code) { buildTicketQrBitmap(ticket.code) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cupón generado") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    ticket.code,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = BrandSuccess
                )
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Código QR del ticket",
                        modifier = Modifier.size(160.dp)
                    )
                }
                Text(
                    "Descuento: ${viewModel.formatQty(ticket.discountPercent)}%",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Estado: ${ticket.displayStatusLabel()}",
                    style = MaterialTheme.typography.bodySmall
                )
                ticket.expiresAt?.let { expires ->
                    Text(
                        "Vence: ${viewModel.formatTicketDate(expires)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    DISCOUNT_TICKET_CONDITIONS,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.generatedTicketShareText()?.let(onShare) }) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Compartir")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    )
}

private fun buildTicketQrBitmap(code: String, size: Int = 512): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, size, size)
    Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        for (x in 0 until size) {
            for (y in 0 until size) {
                setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
    }
}.getOrNull()
