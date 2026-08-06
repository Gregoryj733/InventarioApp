package com.inventario.app.ui.cashclosing

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inventario.app.data.entity.CashClosingAlertType
import com.inventario.app.ui.theme.AccentSectionCard
import com.inventario.app.ui.theme.AppScreenBackground
import com.inventario.app.ui.theme.BrandOutflow
import com.inventario.app.ui.theme.BrandSuccess
import com.inventario.app.ui.theme.BrandWarning
import com.inventario.app.ui.theme.ReportDivider
import com.inventario.app.ui.theme.ReportHeader
import com.inventario.app.ui.theme.ReportKeyValueRow
import com.inventario.app.ui.theme.ConfirmedOrdersBanner
import com.inventario.app.ui.theme.ReportMetaChip
import com.inventario.app.ui.theme.ReportTotalBanner
import com.inventario.app.ui.theme.StatusPill
import com.inventario.app.ui.theme.WhatsAppGreen
import com.inventario.app.ui.theme.isVeryCompactWidth
import com.inventario.app.ui.theme.screenHorizontalPadding
import com.inventario.app.ui.theme.screenVerticalPadding
import com.inventario.app.util.WhatsAppNotifier
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CashClosingScreen(
    viewModel: CashClosingViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showPreview by remember { mutableStateOf(false) }

    if (showPreview) {
        CashClosingPreviewDialog(
            viewModel = viewModel,
            onDismiss = { showPreview = false },
            onShare = {
                WhatsAppNotifier.shareToGroupChooser(context, viewModel.buildWhatsAppMessage())
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Cierre de caja", fontWeight = FontWeight.Bold)
                        Text(
                            "Cuadre diario",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    if (state.bcvRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        IconButton(onClick = viewModel::refreshBcv) {
                            Icon(Icons.Default.Refresh, contentDescription = "Actualizar BCV")
                        }
                    }
                }
            )
        }
    ) { padding ->
        AppScreenBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = screenHorizontalPadding(), vertical = screenVerticalPadding())
        ) {
            CashClosingAlertBanner(
                alert = state.closingAlert,
                remainingAttempts = state.remainingAttempts,
                onAcknowledge = viewModel::acknowledgeClosingAlert
            )
            if (state.closingAlert != null) {
                Spacer(Modifier.height(10.dp))
            }
            SectionCard(title = "Encabezado") {
                KeyboardAwareTextField(
                    value = state.branchName,
                    onValueChange = viewModel::onBranchChange,
                    label = "Sucursal / Local"
                )
                Spacer(Modifier.height(8.dp))
                KeyboardAwareTextField(
                    value = state.dateText,
                    onValueChange = viewModel::onDateChange,
                    label = "Fecha"
                )
                Spacer(Modifier.height(8.dp))
                BcvRateHeader(
                    label = state.bcvLabel,
                    hasRate = state.bcvRate != null,
                    refreshing = state.bcvRefreshing
                )
                Spacer(Modifier.height(8.dp))
                KeyboardAwareTextField(
                    value = state.rateText,
                    onValueChange = viewModel::onRateChange,
                    label = "Tasa BCV (Bs)",
                    keyboardType = KeyboardType.Decimal
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Precargada desde BCV del día (bcv.org.ve). Sin internet se usa la última guardada. Editable.",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                DualCurrencyField(
                    usdLabel = "Ef. anterior USD",
                    bsLabel = "Ef. anterior Bs",
                    usdText = state.prevCashUsdText,
                    bsText = state.prevCashBsText,
                    onUsdChange = viewModel::onPrevCashUsdChange,
                    onBsChange = viewModel::onPrevCashBsChange
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Efectivo recibido del cierre del día anterior. Editable.",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "Total ventas del día") {
                if (state.loadingSales) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    DailySalesMetaRow(
                        bcvLabel = state.bcvLabel,
                        orderCount = state.confirmedOrdersToday,
                        onReset = viewModel::resetTodayOrders,
                        resetting = state.resettingOrders
                    )
                    Spacer(Modifier.height(10.dp))
                    DualCurrencyField(
                        usdLabel = "Total USD",
                        bsLabel = "Total Bs",
                        usdText = state.salesUsdText,
                        bsText = state.salesBsText,
                        onUsdChange = viewModel::onSalesUsdChange,
                        onBsChange = viewModel::onSalesBsChange
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Precargado desde pedidos confirmados hoy. Editable.",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "Entradas — Puntos de venta") {
                state.posEntries.forEach { entry ->
                    PosEntryRow(
                        entry = entry,
                        onNameChange = { viewModel.onPosNameChange(entry.id, it) },
                        onUsdChange = { viewModel.onPosUsdChange(entry.id, it) },
                        onBsChange = { viewModel.onPosBsChange(entry.id, it) },
                        onRemove = { viewModel.removePosEntry(entry.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = viewModel::addPosEntry,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Agregar punto de venta")
                }
                Spacer(Modifier.height(8.dp))
                TotalLine(
                    label = "TOTAL (A)",
                    usd = viewModel.formatPrice(viewModel.totalPosUsd()),
                    bs = viewModel.formatBs(viewModel.totalPosBs())
                )
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "Pago móvil") {
                state.mobileEntries.forEach { entry ->
                    MobileEntryRow(
                        entry = entry,
                        onRefChange = { viewModel.onMobileRefChange(entry.id, it) },
                        onUsdChange = { viewModel.onMobileUsdChange(entry.id, it) },
                        onBsChange = { viewModel.onMobileBsChange(entry.id, it) },
                        onRemove = { viewModel.removeMobileEntry(entry.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = viewModel::addMobileEntry,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Agregar referencia")
                }
                if (state.mobileEntries.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    TotalLine(
                        label = "TOTAL (B)",
                        usd = viewModel.formatPrice(viewModel.totalMobileUsd()),
                        bs = viewModel.formatBs(viewModel.totalMobileBs())
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "Efectivo") {
                state.cashEntries.forEach { entry ->
                    CashEntryRow(
                        entry = entry,
                        onDescChange = { viewModel.onCashDescChange(entry.id, it) },
                        onUsdChange = { viewModel.onCashUsdChange(entry.id, it) },
                        onBsChange = { viewModel.onCashBsChange(entry.id, it) },
                        onRemove = { viewModel.removeCashEntry(entry.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = viewModel::addCashEntry,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Agregar descripción")
                }
                Spacer(Modifier.height(8.dp))
                TotalLine(
                    label = "TOTAL (C)",
                    usd = viewModel.formatPrice(viewModel.totalCashUsd()),
                    bs = viewModel.formatBs(viewModel.totalCashBs())
                )
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "Cashea (financiado)") {
                KeyboardAwareTextField(
                    value = state.casheaUsdText,
                    onValueChange = viewModel::onCasheaUsdChange,
                    label = "Monto USD",
                    keyboardType = KeyboardType.Decimal
                )
                Spacer(Modifier.height(8.dp))
                TotalLine(
                    label = "TOTAL (E)",
                    usd = viewModel.formatPrice(viewModel.textToAmount(state.casheaUsdText)),
                    bs = viewModel.formatBsEquiv(viewModel.textToAmount(state.casheaUsdText))
                )
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(
                title = "Salidas",
                accentColor = BrandOutflow,
                titleColor = BrandOutflow,
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            ) {
                state.expenseEntries.forEach { entry ->
                    ExpenseEntryRow(
                        entry = entry,
                        bsEquiv = run {
                            val amount = viewModel.textToAmount(entry.usdText)
                            if (amount > 0) viewModel.formatBsEquiv(amount) else null
                        },
                        onDescChange = { viewModel.onExpenseDescChange(entry.id, it) },
                        onUsdChange = { viewModel.onExpenseUsdChange(entry.id, it) },
                        onRemove = { viewModel.removeExpenseEntry(entry.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = viewModel::addExpenseEntry,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = BrandOutflow
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Agregar salida")
                }
                if (state.expenseEntries.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    TotalLine(
                        label = "TOTAL (D)",
                        usd = viewModel.formatPrice(viewModel.totalExpenseUsd()),
                        bs = viewModel.formatBsEquiv(viewModel.totalExpenseUsd()),
                        valueColor = BrandOutflow
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            DifferenceBanner(state = state, viewModel = viewModel)

            Spacer(Modifier.height(8.dp))

            SummaryCard(state = state, viewModel = viewModel)

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "Observaciones") {
                KeyboardAwareTextField(
                    value = state.observations,
                    onValueChange = viewModel::onObservationsChange,
                    label = "Notas adicionales",
                    minLines = 2
                )
            }

            Spacer(Modifier.height(16.dp))

            if (state.saveError != null) {
                Text(
                    text = state.saveError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
            }

            Text(
                text = "Intentos restantes hoy: ${state.remainingAttempts}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    if (viewModel.validateForClosing()) showPreview = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Preview, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Previsualizar cierre", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(24.dp))
        }
        }
    }
}


@Composable
private fun CashClosingAlertBanner(
    alert: CashClosingAlertType?,
    remainingAttempts: Int,
    onAcknowledge: () -> Unit
) {
    if (alert == null) return

    val isRejected = alert == CashClosingAlertType.REJECTED_RESUBMIT
    val containerColor = if (isRejected) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
    } else {
        BrandSuccess.copy(alpha = 0.14f)
    }
    val iconTint = if (isRejected) MaterialTheme.colorScheme.error else BrandSuccess
    val title = if (isRejected) {
        "Cierre de caja rechazado"
    } else {
        "Cierre de caja aprobado"
    }
    val message = if (isRejected) {
        "Tu cierre fue rechazado desde Reportes. Debes volver a ejecutar el cierre de caja del día."
    } else {
        "Tu cierre de caja del día fue aprobado y contabilizado."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = iconTint)
                Spacer(Modifier.height(4.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isRejected && remainingAttempts > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Intentos restantes hoy: $remainingAttempts",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(onClick = onAcknowledge) {
                Text("Entendido")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyboardAwareTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    minLines: Int = 1,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusEvent { event ->
                if (event.isFocused) {
                    coroutineScope.launch { bringIntoViewRequester.bringIntoView() }
                }
            },
        singleLine = minLines == 1,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun CashClosingPreviewDialog(
    viewModel: CashClosingViewModel,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val balanced = viewModel.isBalanced()
    val diff = viewModel.differenceUsd()
    var posClosureDone by remember { mutableStateOf(false) }
    var casheaClosureDone by remember { mutableStateOf(false) }
    var fiscalZReportDone by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var closingSaved by remember { mutableStateOf(false) }
    val allPrerequisitesMet = posClosureDone && casheaClosureDone && fiscalZReportDone
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                ReportHeader(
                    title = "Vista previa del cierre",
                    subtitle = state.branchName
                )
                if (viewModel.prevCashUsd() > 0) {
                    Spacer(Modifier.height(6.dp))
                    ReportKeyValueRow(
                        label = "Efectivo cierre anterior",
                        value = "${viewModel.formatPrice(viewModel.prevCashUsd())} · ${viewModel.formatBs(viewModel.prevCashBs())}"
                    )
                }
                Spacer(Modifier.height(8.dp))
                StatusPill(
                    text = if (balanced) "Cuadre correcto ✓" else "Diferencia: ${viewModel.formatPrice(kotlin.math.abs(diff))}",
                    color = if (balanced) BrandSuccess else MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(Modifier.height(14.dp))
                ClosingPrerequisitesReminder(
                    posClosureDone = posClosureDone,
                    onPosClosureDoneChange = { posClosureDone = it },
                    casheaClosureDone = casheaClosureDone,
                    onCasheaClosureDoneChange = { casheaClosureDone = it },
                    fiscalZReportDone = fiscalZReportDone,
                    onFiscalZReportDoneChange = { fiscalZReportDone = it }
                )

                Spacer(Modifier.height(14.dp))
                ReportDivider(label = "Ventas del día")
                Spacer(Modifier.height(6.dp))
                ReportTotalBanner(
                    label = "Total ventas",
                    usd = viewModel.formatPrice(viewModel.salesUsd()),
                    bs = viewModel.formatBs(viewModel.salesBs()),
                    highlight = false
                )

                Spacer(Modifier.height(12.dp))
                ReportDivider(label = "Detalle")
                Spacer(Modifier.height(6.dp))
                ReportKeyValueRow("Puntos de venta (A)", viewModel.formatPrice(viewModel.totalPosUsd()))
                ReportKeyValueRow("Pago móvil (B)", viewModel.formatPrice(viewModel.totalMobileUsd()))
                Spacer(Modifier.height(6.dp))
                Text(
                    "Efectivo (C)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val cashWithValues = state.cashEntries.filter {
                    it.description.isNotBlank() || it.usdText.isNotBlank() || it.bsText.isNotBlank()
                }
                if (cashWithValues.isEmpty()) {
                    ReportKeyValueRow("— sin registros —", viewModel.formatPrice(viewModel.totalCashUsd()))
                } else {
                    cashWithValues.forEach { entry ->
                        ReportKeyValueRow(
                            entry.description.ifBlank { "Efectivo" },
                            viewModel.formatPrice(viewModel.textToAmount(entry.usdText))
                        )
                    }
                    ReportKeyValueRow("Subtotal (C)", viewModel.formatPrice(viewModel.totalCashUsd()))
                }
                Spacer(Modifier.height(6.dp))
                ReportKeyValueRow("Salidas (D)", viewModel.formatPrice(viewModel.totalExpenseUsd()))
                ReportKeyValueRow("Cashea (E)", viewModel.formatPrice(viewModel.casheaUsd()))

                Spacer(Modifier.height(10.dp))
                ReportTotalBanner(
                    label = "Total cuadre (A+B+C+D+E)",
                    usd = viewModel.formatPrice(viewModel.grandTotalUsd()),
                    bs = viewModel.formatBs(viewModel.grandTotalBs())
                )

                if (state.observations.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    ReportDivider()
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Observaciones: ${state.observations}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (closingSaved) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(
                                BrandSuccess.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = BrandSuccess,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Caja cerrada correctamente. Ya puedes enviarla por WhatsApp.",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = BrandSuccess
                        )
                    }
                } else if (!allPrerequisitesMet) {
                    Text(
                        text = "Confirme los cierres pendientes para cerrar caja",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    )
                }
                if (state.saveError != null) {
                    Text(
                        text = state.saveError!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    )
                }
                Button(
                    onClick = {
                        saving = true
                        viewModel.saveClosingRecord { success ->
                            saving = false
                            if (success) {
                                closingSaved = true
                                android.widget.Toast.makeText(
                                    context,
                                    "Caja cerrada correctamente",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    enabled = allPrerequisitesMet && !saving && !closingSaved,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (closingSaved) "Caja cerrada ✓" else "Aceptar cerrar caja",
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onShare,
                    enabled = closingSaved && !saving,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = WhatsAppGreen,
                        disabledContainerColor = WhatsAppGreen.copy(alpha = 0.38f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Enviar por WhatsApp", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (closingSaved) "Cerrar" else "Volver a editar")
                }
            }
        }
    )
}

@Composable
private fun ClosingPrerequisitesReminder(
    posClosureDone: Boolean,
    onPosClosureDoneChange: (Boolean) -> Unit,
    casheaClosureDone: Boolean,
    onCasheaClosureDoneChange: (Boolean) -> Unit,
    fiscalZReportDone: Boolean,
    onFiscalZReportDoneChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = BrandWarning,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Antes de cerrar caja, asegúrese de haber ejecutado:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            ClosingPrerequisiteCheckbox(
                label = "Cierre de puntos de venta",
                checked = posClosureDone,
                onCheckedChange = onPosClosureDoneChange
            )
            ClosingPrerequisiteCheckbox(
                label = "Cierre de caja de Cashea",
                checked = casheaClosureDone,
                onCheckedChange = onCasheaClosureDoneChange
            )
            ClosingPrerequisiteCheckbox(
                label = "Emisión de reporte Z de máquina fiscal",
                checked = fiscalZReportDone,
                onCheckedChange = onFiscalZReportDoneChange
            )
        }
    }
}

@Composable
private fun ClosingPrerequisiteCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DifferenceBanner(
    state: CashClosingUiState,
    viewModel: CashClosingViewModel
) {
    val totalA = state.posEntries.sumOf { viewModel.textToAmount(it.usdText) }
    val totalB = state.mobileEntries.sumOf { viewModel.textToAmount(it.usdText) }
    val totalC = state.cashEntries.sumOf { viewModel.textToAmount(it.usdText) }
    val totalD = state.expenseEntries.sumOf { viewModel.textToAmount(it.usdText) }
    val totalE = viewModel.textToAmount(state.casheaUsdText)
    val salesUsd = viewModel.textToAmount(state.salesUsdText)
    val grandUsd = totalA + totalB + totalC + totalD + totalE
    val hasEntries = grandUsd > 0.0 || salesUsd > 0.0
    val diff = salesUsd - grandUsd
    val balanced = kotlin.math.abs(diff) < 0.01

    if (!hasEntries || balanced) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Diferencia detectada: ${viewModel.formatPrice(abs(diff))}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (diff > 0) {
                        "El total calculado (A+B+C+D+E) es menor que las ventas del día. " +
                            "Faltan ${viewModel.formatPrice(kotlin.math.abs(diff))} en caja."
                    } else {
                        "El total calculado (A+B+C+D+E) es mayor que las ventas del día. " +
                            "Sobran ${viewModel.formatPrice(kotlin.math.abs(diff))} en caja."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

@Composable
private fun BcvRateHeader(
    label: String,
    hasRate: Boolean,
    refreshing: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Tasa del día",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (hasRate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun DailySalesMetaRow(
    bcvLabel: String,
    orderCount: Int,
    onReset: (() -> Unit)? = null,
    resetting: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReportMetaChip(
            icon = "💱",
            text = "Tasa BCV: ${bcvLabel.removePrefix("Tasa BCV: ")}"
        )
        ConfirmedOrdersBanner(
            count = orderCount,
            onReset = onReset,
            resetting = resetting
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    accentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit
) {
    AccentSectionCard(
        title = title,
        accentColor = accentColor,
        titleColor = titleColor,
        containerColor = containerColor
    ) {
        content()
    }
}

@Composable
private fun DualCurrencyField(
    usdLabel: String,
    bsLabel: String,
    usdText: String,
    bsText: String,
    onUsdChange: (String) -> Unit,
    onBsChange: (String) -> Unit
) {
    val stackVertical = isVeryCompactWidth()
    if (stackVertical) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KeyboardAwareTextField(
                value = usdText,
                onValueChange = onUsdChange,
                label = usdLabel,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth()
            )
            KeyboardAwareTextField(
                value = bsText,
                onValueChange = onBsChange,
                label = bsLabel,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth()
            )
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyboardAwareTextField(
                value = usdText,
                onValueChange = onUsdChange,
                label = usdLabel,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f)
            )
            KeyboardAwareTextField(
                value = bsText,
                onValueChange = onBsChange,
                label = bsLabel,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PosEntryRow(
    entry: PosEntry,
    onNameChange: (String) -> Unit,
    onUsdChange: (String) -> Unit,
    onBsChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyboardAwareTextField(
                value = entry.name,
                onValueChange = onNameChange,
                label = "Nombre",
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(4.dp))
        DualCurrencyField(
            usdLabel = "USD",
            bsLabel = "Bs",
            usdText = entry.usdText,
            bsText = entry.bsText,
            onUsdChange = onUsdChange,
            onBsChange = onBsChange
        )
    }
}

@Composable
private fun MobileEntryRow(
    entry: MobilePaymentEntry,
    onRefChange: (String) -> Unit,
    onUsdChange: (String) -> Unit,
    onBsChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyboardAwareTextField(
                value = entry.ref,
                onValueChange = onRefChange,
                label = "Ref #",
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(4.dp))
        DualCurrencyField(
            usdLabel = "USD",
            bsLabel = "Bs",
            usdText = entry.usdText,
            bsText = entry.bsText,
            onUsdChange = onUsdChange,
            onBsChange = onBsChange
        )
    }
}

@Composable
private fun CashEntryRow(
    entry: CashEntry,
    onDescChange: (String) -> Unit,
    onUsdChange: (String) -> Unit,
    onBsChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyboardAwareTextField(
                value = entry.description,
                onValueChange = onDescChange,
                label = "Descripción",
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(4.dp))
        DualCurrencyField(
            usdLabel = "USD",
            bsLabel = "Bs",
            usdText = entry.usdText,
            bsText = entry.bsText,
            onUsdChange = onUsdChange,
            onBsChange = onBsChange
        )
    }
}

@Composable
private fun ExpenseEntryRow(
    entry: ExpenseEntry,
    bsEquiv: String?,
    onDescChange: (String) -> Unit,
    onUsdChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyboardAwareTextField(
                value = entry.description,
                onValueChange = onDescChange,
                label = "Descripción",
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(4.dp))
        KeyboardAwareTextField(
            value = entry.usdText,
            onValueChange = onUsdChange,
            label = "Monto USD",
            keyboardType = KeyboardType.Decimal
        )
        if (bsEquiv != null) {
            Text(bsEquiv, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TotalLine(
    label: String,
    usd: String,
    bs: String?,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, color = valueColor)
        Column(horizontalAlignment = Alignment.End) {
            Text(usd, fontWeight = FontWeight.Bold, color = valueColor)
            if (bs != null) {
                Text(bs, style = MaterialTheme.typography.bodySmall, color = valueColor.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun SummaryCard(
    state: CashClosingUiState,
    viewModel: CashClosingViewModel
) {
    val totalA = state.posEntries.sumOf { viewModel.textToAmount(it.usdText) }
    val totalABs = state.posEntries.sumOf { viewModel.textToAmount(it.bsText) }
    val totalB = state.mobileEntries.sumOf { viewModel.textToAmount(it.usdText) }
    val totalBBs = state.mobileEntries.sumOf { viewModel.textToAmount(it.bsText) }
    val totalC = state.cashEntries.sumOf { viewModel.textToAmount(it.usdText) }
    val totalCBs = state.cashEntries.sumOf { viewModel.textToAmount(it.bsText) }
    val totalD = state.expenseEntries.sumOf { viewModel.textToAmount(it.usdText) }
    val totalE = viewModel.textToAmount(state.casheaUsdText)
    val salesUsd = viewModel.textToAmount(state.salesUsdText)
    val salesBs = viewModel.textToAmount(state.salesBsText)
    val grandUsd = totalA + totalB + totalC + totalD + totalE
    val rate = state.rateText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    val grandBs = if (rate != null) grandUsd * rate else totalABs + totalBBs + totalCBs
    val diff = salesUsd - grandUsd
    val balanced = kotlin.math.abs(diff) < 0.01

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (balanced) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
            }
        ),
        elevation = CardDefaults.cardElevation(3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Resumen del cuadre",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            SummaryRow(
                "Tasa BCV del día",
                state.bcvLabel.removePrefix("Tasa BCV: "),
                bold = false
            )
            SummaryRow(
                "Pedidos confirmados",
                "${state.confirmedOrdersToday}",
                bold = false
            )
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            SummaryRow("Total ventas", viewModel.formatPrice(salesUsd), viewModel.formatBs(salesBs))
            SummaryRow("Puntos de venta (A)", viewModel.formatPrice(totalA), viewModel.formatBs(totalABs))
            SummaryRow("Pago móvil (B)", viewModel.formatPrice(totalB), viewModel.formatBs(totalBBs))
            SummaryRow("Efectivo (C)", viewModel.formatPrice(totalC), viewModel.formatBs(totalCBs))
            SummaryRow(
                "Salidas (D)",
                viewModel.formatPrice(totalD),
                viewModel.formatBsEquiv(totalD),
                valueColor = BrandOutflow
            )
            SummaryRow("Cashea (E)", viewModel.formatPrice(totalE), viewModel.formatBsEquiv(totalE))
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            SummaryRow(
                "TOTAL (A+B+C+D+E)",
                viewModel.formatPrice(grandUsd),
                viewModel.formatBs(grandBs),
                bold = true
            )
            Spacer(Modifier.height(8.dp))
            val diffText = if (balanced) {
                "Cuadra con ventas ✓"
            } else {
                "Diferencia: ${viewModel.formatPrice(abs(diff))}" +
                    if (diff > 0) " (faltan en caja)" else " (sobra en caja)"
            }
            Text(
                diffText,
                fontWeight = FontWeight.SemiBold,
                color = if (balanced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    usd: String,
    bs: String? = null,
    bold: Boolean = false,
    valueColor: androidx.compose.ui.graphics.Color? = null
) {
    val usdColor = valueColor ?: if (bold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val bsColor = valueColor?.copy(alpha = 0.75f) ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                usd,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
                color = usdColor
            )
            if (bs != null) {
                Text(
                    bs,
                    style = MaterialTheme.typography.bodySmall,
                    color = bsColor
                )
            }
        }
    }
}
