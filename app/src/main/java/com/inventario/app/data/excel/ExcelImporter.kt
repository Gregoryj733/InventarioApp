package com.inventario.app.data.excel

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

data class ImportedProduct(
    val description: String,
    val quantity: Double,
    val unit: String,
    val price: Double
)

data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val errors: List<String>
)

/**
 * Lector XLSX liviano para: DESCRIPCIÓN | CANT. | UND | PRECIO
 */
object ExcelImporter {

    private val xmlFactory: XmlPullParserFactory = XmlPullParserFactory.newInstance()
    private val sheetPathRegex = Regex("""xl/worksheets/sheet\d+\.xml""", RegexOption.IGNORE_CASE)
    private val whitespaceRegex = Regex("\\s+")

    private val descHeaders = setOf("DESCRIPCION", "NOMBRE", "PRODUCTO")
    private val qtyHeaders = setOf("CANT.", "CANT", "CANTIDAD", "STOCK")
    private val unitHeaders = setOf("UND", "UNIDAD", "U/M", "UM")

    fun importProductsWithData(inputStream: InputStream): Pair<List<ImportedProduct>, ImportResult> {
        val rows = readSheetRows(inputStream)
        if (rows.isEmpty()) {
            return emptyList<ImportedProduct>() to ImportResult(0, 0, listOf("El archivo Excel está vacío."))
        }

        val header = rows.first().map { normalizeHeader(it) }
        val colDesc = header.indexOfFirst {
            val h = it.replace("Ó", "O").replace("É", "E")
            h in descHeaders
        }
        val colQty = header.indexOfFirst {
            it in qtyHeaders || it.startsWith("CANT")
        }
        val colUnit = header.indexOfFirst {
            it in unitHeaders
        }
        val colPrice = header.indexOfFirst {
            it.replace(" ", "").contains("PRECIO")
        }

        if (colDesc < 0 || colQty < 0 || colUnit < 0 || colPrice < 0) {
            return emptyList<ImportedProduct>() to ImportResult(
                0,
                0,
                listOf(
                    "Encabezados inválidos. Se esperan: DESCRIPCIÓN, CANT., UND, PRECIO. " +
                        "Encontrados: ${rows.first().joinToString(" | ")}"
                )
            )
        }

        val products = ArrayList<ImportedProduct>((rows.size - 1).coerceAtLeast(16))
        val errors = ArrayList<String>(8)
        var skipped = 0

        rows.drop(1).forEachIndexed { index, row ->
            val excelRow = index + 2
            val description = row.getOrNull(colDesc)?.trim().orEmpty()
            if (description.isBlank()) {
                skipped++
                return@forEachIndexed
            }

            val quantity = parseNumber(row.getOrNull(colQty))
            val price = parseNumber(row.getOrNull(colPrice))
            val unit = row.getOrNull(colUnit)?.trim().orEmpty().ifBlank { "UNIDAD" }

            if (quantity == null || price == null) {
                errors.add("Fila $excelRow ($description): cantidad o precio inválido.")
                skipped++
                return@forEachIndexed
            }
            if (quantity < 0 || price < 0) {
                errors.add("Fila $excelRow ($description): valores negativos no permitidos.")
                skipped++
                return@forEachIndexed
            }

            products.add(
                ImportedProduct(
                    description = description,
                    quantity = quantity,
                    unit = unit.uppercase(),
                    price = price
                )
            )
        }

        return products to ImportResult(products.size, skipped, errors.take(20))
    }

    private fun normalizeHeader(raw: String): String =
        raw.trim()
            .uppercase()
            .replace('\u00A0', ' ')
            .replace(whitespaceRegex, " ")

    private fun parseNumber(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
            .replace(" ", "")
            .replace(",", ".")
        return cleaned.toDoubleOrNull()
    }

    private fun readSheetRows(inputStream: InputStream): List<List<String>> {
        val sharedStrings = ArrayList<String>(256)
        var sheetXml: ByteArray? = null

        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name.equals("xl/sharedStrings.xml", ignoreCase = true) -> {
                        sharedStrings.addAll(parseSharedStrings(zip.readBytes().inputStream()))
                    }
                    sheetXml == null && entry.name.matches(sheetPathRegex) -> {
                        sheetXml = zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val xml = sheetXml ?: return emptyList()
        return parseSheet(xml.inputStream(), sharedStrings)
    }

    private fun parseSharedStrings(stream: InputStream): List<String> {
        val result = ArrayList<String>(128)
        val parser = xmlFactory.newPullParser().apply {
            setInput(stream, "UTF-8")
        }
        var event = parser.eventType
        var inSi = false
        var inT = false
        val current = StringBuilder(32)

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> {
                        inSi = true
                        current.setLength(0)
                    }
                    "t" -> if (inSi) inT = true
                }
                XmlPullParser.TEXT -> if (inT) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inT = false
                    "si" -> {
                        inSi = false
                        result.add(current.toString())
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseSheet(stream: InputStream, sharedStrings: List<String>): List<List<String>> {
        val rows = linkedMapOf<Int, MutableMap<Int, String>>()
        val parser = xmlFactory.newPullParser().apply {
            setInput(stream, "UTF-8")
        }

        var event = parser.eventType
        var currentRow = -1
        var currentCol = -1
        var cellType: String? = null
        var inV = false
        var cellValue = StringBuilder(16)

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentRow = parser.getAttributeValue(null, "r")?.toIntOrNull()
                        ?: (currentRow + 1)
                    "c" -> {
                        val ref = parser.getAttributeValue(null, "r")
                        currentCol = cellRefToColumn(ref)
                        cellType = parser.getAttributeValue(null, "t")
                        cellValue = StringBuilder(16)
                    }
                    "v", "t" -> inV = true
                }
                XmlPullParser.TEXT -> if (inV) cellValue.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> inV = false
                    "c" -> {
                        if (currentRow >= 0 && currentCol >= 0) {
                            val raw = cellValue.toString()
                            val value = when (cellType) {
                                "s" -> sharedStrings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                                "inlineStr" -> raw
                                else -> raw
                            }
                            rows.getOrPut(currentRow) { mutableMapOf() }[currentCol] = value
                        }
                        currentCol = -1
                        cellType = null
                    }
                }
            }
            event = parser.next()
        }

        if (rows.isEmpty()) return emptyList()
        val maxCol = rows.values.maxOf { it.keys.maxOrNull() ?: 0 }
        return rows.toSortedMap().values.map { cols ->
            (0..maxCol).map { idx -> cols[idx].orEmpty() }
        }
    }

    private fun cellRefToColumn(ref: String?): Int {
        if (ref.isNullOrBlank()) return 0
        val letters = ref.takeWhile { it.isLetter() }
        var col = 0
        for (ch in letters.uppercase()) {
            col = col * 26 + (ch - 'A' + 1)
        }
        return (col - 1).coerceAtLeast(0)
    }
}
