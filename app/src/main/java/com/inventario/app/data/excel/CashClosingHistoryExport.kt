package com.inventario.app.data.excel

import android.content.ContentResolver
import android.net.Uri
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.displayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CashClosingHistoryExport {

    suspend fun writeToUri(
        resolver: ContentResolver,
        uri: Uri,
        closings: List<CashClosingRecord>,
        simplifiedForGerente: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (closings.isEmpty()) error("No hay cierres para exportar.")
            val bytes = if (simplifiedForGerente) {
                CashClosingExcelExporter.exportGerenteSummary(closings)
            } else {
                CashClosingExcelExporter.export(closings) { it.displayLabel() }
            }
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(bytes)
            } ?: error("No se pudo escribir el archivo.")
        }
    }
}
