const XLSX = require("xlsx");

const DESC_HEADERS = new Set(["DESCRIPCION", "NOMBRE", "PRODUCTO"]);
const QTY_HEADERS = new Set(["CANT.", "CANT", "CANTIDAD", "STOCK"]);
const UNIT_HEADERS = new Set(["UND", "UNIDAD", "U/M", "UM"]);

function normalizeHeader(raw) {
  return String(raw ?? "")
    .trim()
    .toUpperCase()
    .replace(/\u00A0/g, " ")
    .replace(/\s+/g, " ");
}

function parseNumber(raw) {
  if (raw === null || raw === undefined) return null;
  if (typeof raw === "number") {
    return Number.isFinite(raw) ? raw : null;
  }
  const trimmed = String(raw).trim();
  if (!trimmed) return null;
  const cleaned = trimmed.replace(/\s/g, "").replace(",", ".");
  const value = Number(cleaned);
  return Number.isFinite(value) ? value : null;
}

function cellText(raw) {
  if (raw === null || raw === undefined) return "";
  return String(raw).trim();
}

/**
 * Parsea un archivo .xlsx (buffer) con columnas DESCRIPCIÓN | CANT. | UND | PRECIO.
 * Réplica del parser que antes vivía en el cliente Android (ExcelImporter.kt),
 * ahora centralizado en el servidor para que un solo lugar defina las reglas
 * de importación (sin depender de actualizar la app para corregir un caso borde).
 */
function parseInventoryExcel(buffer) {
  const workbook = XLSX.read(buffer, { type: "buffer" });
  const sheetName = workbook.SheetNames[0];
  if (!sheetName) {
    return { products: [], imported: 0, skipped: 0, errors: ["El archivo Excel está vacío."] };
  }
  const sheet = workbook.Sheets[sheetName];
  const rows = XLSX.utils.sheet_to_json(sheet, { header: 1, defval: "", raw: true });

  if (rows.length === 0) {
    return { products: [], imported: 0, skipped: 0, errors: ["El archivo Excel está vacío."] };
  }

  const header = rows[0].map(normalizeHeader);
  const colDesc = header.findIndex((h) => DESC_HEADERS.has(h.replace(/Ó/g, "O").replace(/É/g, "E")));
  const colQty = header.findIndex((h) => QTY_HEADERS.has(h) || h.startsWith("CANT"));
  const colUnit = header.findIndex((h) => UNIT_HEADERS.has(h));
  const colPrice = header.findIndex((h) => h.replace(/\s/g, "").includes("PRECIO"));

  if (colDesc < 0 || colQty < 0 || colUnit < 0 || colPrice < 0) {
    return {
      products: [],
      imported: 0,
      skipped: 0,
      errors: [
        "Encabezados inválidos. Se esperan: DESCRIPCIÓN, CANT., UND, PRECIO. " +
          `Encontrados: ${rows[0].join(" | ")}`
      ]
    };
  }

  const products = [];
  const errors = [];
  let skipped = 0;

  for (let index = 1; index < rows.length; index += 1) {
    const row = rows[index];
    const excelRow = index + 1;
    const description = cellText(row[colDesc]);
    if (!description) {
      skipped += 1;
      continue;
    }

    const quantity = parseNumber(row[colQty]);
    const price = parseNumber(row[colPrice]);
    const unit = cellText(row[colUnit]) || "UNIDAD";

    if (quantity === null || price === null) {
      errors.push(`Fila ${excelRow} (${description}): cantidad o precio inválido.`);
      skipped += 1;
      continue;
    }
    if (quantity < 0 || price < 0) {
      errors.push(`Fila ${excelRow} (${description}): valores negativos no permitidos.`);
      skipped += 1;
      continue;
    }

    products.push({
      description,
      quantity,
      unit: unit.toUpperCase(),
      price
    });
  }

  return {
    products,
    imported: products.length,
    skipped,
    errors: errors.slice(0, 20)
  };
}

module.exports = { parseInventoryExcel };
