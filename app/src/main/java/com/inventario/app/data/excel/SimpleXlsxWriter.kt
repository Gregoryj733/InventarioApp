package com.inventario.app.data.excel

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Generador mínimo de archivos .xlsx sin dependencias externas (compatible con Android).
 */
object SimpleXlsxWriter {

    data class Sheet(
        val name: String,
        val headers: List<String>,
        val rows: List<List<CellValue>>
    )

    sealed class CellValue {
        data class Text(val value: String) : CellValue()
        data class Number(val value: Double) : CellValue()
    }

    fun write(sheets: List<Sheet>): ByteArray {
        require(sheets.isNotEmpty()) { "Se requiere al menos una hoja." }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun writeEntry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            writeEntry("[Content_Types].xml", contentTypes(sheets.size))
            writeEntry("_rels/.rels", rootRels())
            writeEntry("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            writeEntry("xl/workbook.xml", workbookXml(sheets))
            writeEntry("xl/styles.xml", stylesXml())
            sheets.forEachIndexed { index, sheet ->
                writeEntry("xl/worksheets/sheet${index + 1}.xml", worksheetXml(sheet))
            }
        }
        return output.toByteArray()
    }

    private fun contentTypes(sheetCount: Int): String {
        val overrides = (1..sheetCount).joinToString("\n") { index ->
            """    <Override PartName="/xl/worksheets/sheet$index.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
        }
        return """
            |<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            |<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            |    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            |    <Default Extension="xml" ContentType="application/xml"/>
            |    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
            |    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
            |$overrides
            |</Types>
        """.trimMargin()
    }

    private fun rootRels(): String = """
        |<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        |<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        |    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        |</Relationships>
    """.trimMargin()

    private fun workbookRels(sheetCount: Int): String {
        val sheetRels = (1..sheetCount).joinToString("\n") { index ->
            """    <Relationship Id="rId$index" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$index.xml"/>"""
        }
        val stylesRelId = sheetCount + 1
        return """
            |<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            |<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            |$sheetRels
            |    <Relationship Id="rId$stylesRelId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
            |</Relationships>
        """.trimMargin()
    }

    private fun workbookXml(sheets: List<Sheet>): String {
        val sheetEntries = sheets.mapIndexed { index, sheet ->
            val safeName = sheet.name.take(31).ifBlank { "Hoja${index + 1}" }
            """        <sheet name="${xmlEscape(safeName)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>"""
        }.joinToString("\n")
        return """
            |<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            |<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
            |          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
            |    <sheets>
            |$sheetEntries
            |    </sheets>
            |</workbook>
        """.trimMargin()
    }

    private fun stylesXml(): String = """
        |<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        |<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        |    <fonts count="2">
        |        <font><sz val="11"/><name val="Calibri"/></font>
        |        <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
        |    </fonts>
        |    <fills count="2">
        |        <fill><patternFill patternType="none"/></fill>
        |        <fill><patternFill patternType="solid"><fgColor rgb="FF1F4E79"/></patternFill></fill>
        |    </fills>
        |    <borders count="1">
        |        <border><left/><right/><top/><bottom/><diagonal/></border>
        |    </borders>
        |    <cellStyleXfs count="1">
        |        <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
        |    </cellStyleXfs>
        |    <cellXfs count="3">
        |        <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
        |        <xf numFmtId="0" fontId="1" fillId="1" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
        |        <xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
        |    </cellXfs>
        |</styleSheet>
    """.trimMargin()

    private fun worksheetXml(sheet: Sheet): String {
        val allRows = buildList {
            add(sheet.headers.map { CellValue.Text(it) })
            addAll(sheet.rows)
        }
        val maxCols = allRows.maxOfOrNull { it.size } ?: sheet.headers.size
        val columnWidths = (0 until maxCols).map { colIndex ->
            val maxLen = allRows.maxOfOrNull { row ->
                row.getOrNull(colIndex)?.displayLength() ?: 0
            } ?: 10
            val width = (maxLen + 2).coerceIn(10, 48)
            """    <col min="${colIndex + 1}" max="${colIndex + 1}" width="$width" customWidth="1"/>"""
        }.joinToString("\n")

        val rowXml = allRows.mapIndexed { rowIndex, row ->
            val styleAttr = if (rowIndex == 0) """ s="1"""" else ""
            val cells = (0 until maxCols).mapNotNull { colIndex ->
                val cell = row.getOrNull(colIndex) ?: return@mapNotNull null
                val ref = cellRef(colIndex, rowIndex)
                when (cell) {
                    is CellValue.Text -> {
                        """<c r="$ref"$styleAttr t="inlineStr"><is><t>${xmlEscape(cell.value)}</t></is></c>"""
                    }
                    is CellValue.Number -> {
                        val numberStyle = if (rowIndex == 0) "" else """ s="2""""
                        """<c r="$ref"$numberStyle><v>${formatNumber(cell.value)}</v></c>"""
                    }
                }
            }.joinToString("")
            """    <row r="${rowIndex + 1}">$cells</row>"""
        }.joinToString("\n")

        return """
            |<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            |<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            |    <cols>
            |$columnWidths
            |    </cols>
            |    <sheetData>
            |$rowXml
            |    </sheetData>
            |</worksheet>
        """.trimMargin()
    }

    private fun CellValue.displayLength(): Int = when (this) {
        is CellValue.Text -> value.length
        is CellValue.Number -> formatNumber(value).length
    }

    private fun cellRef(col: Int, row: Int): String = "${columnName(col)}${row + 1}"

    private fun columnName(index: Int): String {
        var col = index + 1
        val sb = StringBuilder()
        while (col > 0) {
            val rem = (col - 1) % 26
            sb.insert(0, 'A' + rem)
            col = (col - 1) / 26
        }
        return sb.toString()
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val rounded = kotlin.math.round(value * 10000.0) / 10000.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    }

    private fun xmlEscape(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
        .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "")
}
