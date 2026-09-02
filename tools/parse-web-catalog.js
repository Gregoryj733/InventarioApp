/**
 * Extrae y normaliza el catálogo WEB Filtros (PDF) a JSON para el módulo
 * "Validador Filtro Aceite". Solo conserva aplicaciones de filtro de aceite.
 */
const fs = require("fs");
const path = require("path");
const { PDFParse } = require("pdf-parse");

const PDF_PATH = process.argv[2] || "C:\\Users\\greg7\\Downloads\\Web_Catalogo.pdf";
const OUT_PATH =
  process.argv[3] ||
  path.join(__dirname, "..", "app", "src", "main", "assets", "oil-filter-catalog.json");

const NOT_BRAND = new Set(
  [
    "SUV",
    "N/D",
    "ND",
    "L4",
    "V6",
    "V8",
    "VVT",
    "FI",
    "SIG",
    "TURBO",
    "BLUE",
    "CORE",
    "PLUS",
    "PRO",
    "AIR",
    "ACEITE",
    "AIRE",
    "MOTOR",
    "MODELO",
    "AÑO",
    "ANO",
    "CILINDRADA",
    "PRIMARIO",
    "SECUNDARIO",
    "HIDRAULICO",
    "HIDRÁULICO",
    "COMBUSTIBLE",
    "INDICE",
    "ÍNDICE",
    "VEHÍCULOS",
    "VEHICULOS",
    "LIVIANOS",
    "COMERCIALES",
    "MAQUINARIA",
    "AGRÍCOLA",
    "AGRICOLA",
    "FUERA",
    "CARRETERA",
    "REFERENCIAS",
    "CRUZADAS",
    "FILTROS",
    "SELLADOS",
    "TABLAS",
    "ESPECIFICACIONES",
    "CONTINÚA",
    "CONTINUA",
    "PÁGINA",
    "PAGINA",
    "WWW",
    "WEBFILTROS",
    "COM",
    "AGOSTO",
    "DE",
    "Y",
  ].map((s) => s.toUpperCase())
);

function normalizeSpaces(s) {
  return String(s || "")
    .replace(/\s+/g, " ")
    .trim();
}

function stripHeaders(text) {
  return text
    .replace(/\d+\s+6 de agosto de 2026\s+www\.webfiltros\.com/gi, "\n")
    .replace(/www\.webfiltros\.com\s+6 de agosto de 2026\s+\d+/gi, "\n")
    .replace(/\b--\s*\d+\s+of\s+\d+\s+--\b/gi, "\n")
    .replace(/VEH[IÍ]CULOS LIVIANOS\s*\/\s*SUV/gi, "\n")
    .replace(/Vehículos livianos\s*\/\s*SUV/gi, "\n")
    .replace(/VEH[IÍ]CULOS COMERCIALES/gi, "\n")
    .replace(/Vehículos comerciales/gi, "\n")
    .replace(/MAQUINARIA AGR[IÍ]COLA/gi, "\n")
    .replace(/Maquinaria agrícola/gi, "\n")
    .replace(/MAQUINARIA FUERA DE CARRETERA/gi, "\n")
    .replace(/Maquinaria fuera de carretera/gi, "\n");
}

function joinHyphens(text) {
  return text.replace(/(\w)-\s*\n\s*/g, "$1");
}

function filterType(code) {
  const c = code.toUpperCase().replace(/\s+/g, "");
  if (c.startsWith("WCH") || c.startsWith("WCS") || c.startsWith("WCK")) {
    return "Cartucho / elemento filtrante";
  }
  if (c.startsWith("W-") || /^W\d/.test(c)) {
    return "Filtro sellado";
  }
  return "Filtro de aceite";
}

function isOilCode(code) {
  const c = String(code || "")
    .toUpperCase()
    .replace(/\s+/g, "");
  if (!c) return false;
  if (/^(WA|WCA|WRA|WRAK|WP|WPS|WPR|WPB|WG|WC-|WCG)/.test(c)) return false;
  return /^(W-|WCH|WCS|WCK)/.test(c);
}

function cleanCode(raw) {
  return String(raw || "")
    .replace(/\s+/g, "")
    .toUpperCase();
}

function findSectionStart(text, regex) {
  const flags = regex.flags.includes("g") ? regex.flags : `${regex.flags}g`;
  const re = new RegExp(regex.source, flags);
  let last = -1;
  let match;
  while ((match = re.exec(text)) !== null) {
    last = match.index;
    if (match.index > 5000) return match.index;
  }
  return last;
}

function categoryAt(original, index) {
  const head = original.slice(Math.max(0, index - 800), index + 80).toUpperCase();
  if (head.includes("FUERA DE CARRETERA")) return "Maquinaria fuera de carretera";
  if (head.includes("AGRÍCOLA") || head.includes("AGRICOLA")) return "Maquinaria agrícola";
  if (head.includes("COMERCIALES")) return "Vehículos comerciales";
  return "Vehículos livianos / SUV";
}

function extractTrailingBrand(chunk) {
  const tokens = chunk
    .replace(/[\.·•…]+/g, " ")
    .split(/\s+/)
    .filter(Boolean);
  const brandTokens = [];
  for (let i = tokens.length - 1; i >= 0; i--) {
    const token = tokens[i].replace(/[^A-ZÁÉÍÓÚÜÑ0-9&\-]/gi, "");
    if (!token) continue;
    if (/^[A-Z]{1,5}-?[A-Z0-9]{2,}$/i.test(token) && /[0-9]/.test(token)) break;
    if (/^(W|WC|WP|WA|WCH|WCS|WPS|WRA|WCA)/i.test(token) && /[0-9]/.test(token)) break;
    if (/^\d/.test(token)) break;
    if (NOT_BRAND.has(token.toUpperCase())) {
      if (brandTokens.length) break;
      continue;
    }
    if (token === token.toUpperCase() && /[A-ZÁÉÍÓÚÜÑ]/.test(token) && token.length >= 2) {
      brandTokens.unshift(token);
      if (brandTokens.join(" ").length > 28) break;
      continue;
    }
    break;
  }
  const brand = brandTokens.join(" ").trim();
  return brand.length >= 2 ? brand : "";
}

function captureField(text, re) {
  const match = text.match(re);
  return match ? normalizeSpaces(match[1]) : "";
}

function parseApplications(text) {
  const startMatch = text.search(/VEHÍCULOS LIVIANOS\s*\/\s*SUV/i);
  const bodyStart = startMatch >= 0 ? startMatch : 0;
  const rest = text.slice(bodyStart);
  const cut = findSectionStart(rest, /referencias cruzadas de filtros/i);
  const body = (cut > 200 ? rest.slice(0, cut) : rest).replace(/\r/g, "");
  const originalForCat = body;
  const joined = joinHyphens(stripHeaders(body)).replace(/\n+/g, " ");
  const parts = joined.split(/\bModelo:\s*/i);
  const preamble = parts.shift() || "";
  let marca = extractTrailingBrand(preamble) || "ACURA";
  const apps = [];
  let cursor = bodyStart;

  for (const chunk of parts) {
    cursor = text.indexOf("Modelo:", cursor + 1);
    const categoria = categoryAt(text, cursor >= 0 ? cursor : bodyStart);

    const modelo = captureField(
      `x ${chunk}`,
      /^x\s+(.+?)(?=\s+Motor:|\s+Cilindrada:|\s+Año:|\s+Aceite|\s+Aire\s|\s+Hidráulico|\s+Combustible|$)/i
    );
    const motor = captureField(
      chunk,
      /Motor:\s*(.*?)(?=\s+Cilindrada:|\s+Año:|\s+Aceite|\s+Aire\s|\s+Hidráulico|\s+Combustible|$)/i
    );
    const cilindrada = captureField(
      chunk,
      /Cilindrada:\s*(.*?)(?=\s+Año:|\s+Aceite|\s+Aire\s|\s+Hidráulico|\s+Combustible|$)/i
    );
    const anio = captureField(
      chunk,
      /Año:\s*(.*?)(?=\s+Aceite|\s+Aire\s|\s+Hidráulico|\s+Combustible|$)/i
    );

    const filtros = [];
    const aceiteRe =
      /Aceite(?:\s+(primario|secundario))?\s*[.\s·•…]{2,}\s*([A-Za-z][A-Za-z0-9\-]*)/gi;
    let match;
    while ((match = aceiteRe.exec(chunk)) !== null) {
      const rol = (match[1] || "primario").toLowerCase().includes("secundario")
        ? "secundario"
        : "primario";
      const code = cleanCode(match[2]);
      if (isOilCode(code)) filtros.push({ codigo: code, rol });
    }

    const aceites = (chunk.match(/\b\d{1,2}W-?\d{2}\b/gi) || []).map((v) =>
      v.toUpperCase().replace("-", "")
    );

    const currentMarca = looksLikeBrandName(marca) ? marca : "";
    const nextBrand = extractTrailingBrand(chunk);
    if (nextBrand && looksLikeBrandName(nextBrand) && nextBrand !== currentMarca) {
      marca = nextBrand;
    }

    if (!modelo || filtros.length === 0 || !currentMarca) continue;

    apps.push({
      marca: currentMarca,
      modelo,
      motor,
      cilindrada,
      anio,
      categoria,
      filtros,
      aceites,
    });
  }
  return apps;
}

function parseCrossRefs(text) {
  const start = findSectionStart(text, /referencias cruzadas de filtros/i);
  const end = findSectionStart(text, /\nFiltros [Ss]ellados\n/);
  if (start < 0) return new Map();
  const body = text.slice(start, end > start ? end : undefined);
  const cleaned = joinHyphens(stripHeaders(body));
  const map = new Map();
  const re =
    /([A-Za-z0-9][A-Za-z0-9.\-\/]*)\s*[.·• ]{2,}\s*([A-Z]{1,4}-?[A-Z0-9][A-Z0-9\-]*(?:\s+SY)?)/g;
  let match;
  while ((match = re.exec(cleaned)) !== null) {
    const oem = match[1].trim();
    const web = cleanCode(match[2]);
    if (!isOilCode(web)) continue;
    if (oem.toUpperCase() === web) continue;
    if (!map.has(web)) map.set(web, []);
    const list = map.get(web);
    if (!list.includes(oem) && list.length < 12) list.push(oem);
  }
  return map;
}

function unique(arr) {
  const out = [];
  const seen = new Set();
  for (const item of arr) {
    const key = String(item || "").trim();
    if (!key) continue;
    const norm = key.toLowerCase();
    if (seen.has(norm)) continue;
    seen.add(norm);
    out.push(key);
  }
  return out;
}

function cleanNad(raw) {
  const value = normalizeSpaces(raw)
    .replace(/\s+Año:.*$/i, "")
    .replace(/\s+Aceite.*$/i, "")
    .replace(/\s+Aire.*$/i, "");
  if (!value || /^n\/d$/i.test(value)) return "";
  return value;
}

function formatAnio(raw) {
  const value = cleanNad(raw);
  if (/^\d{4}$/.test(value) && !/^(19|20)/.test(value)) {
    return `${value.slice(0, 2)}/${value.slice(2)}`;
  }
  return value;
}

function looksLikeBrandName(name) {
  const value = normalizeSpaces(name);
  if (!value) return false;
  if (/[0-9]/.test(value) && /^(W|WC|WP|WA|WCH|WCS|WPS|WRA|WCA)/i.test(value)) return false;
  return true;
}

function finalize(apps, cross) {
  const records = [];
  const seen = new Set();
  for (const app of apps) {
    const modelo = cleanNad(app.modelo);
    const marca = normalizeSpaces(app.marca);
    if (!marca || !modelo) continue;
    const motor = cleanNad(app.motor);
    const cilindrada = cleanNad(app.cilindrada);
    const anio = formatAnio(app.anio);
    const oilCodes = app.filtros.filter((f) => isOilCode(f.codigo));
    if (oilCodes.length === 0) continue;

    const primary = oilCodes.find((f) => f.rol === "primario") || oilCodes[0];
    const alts = oilCodes.filter((f) => f.codigo !== primary.codigo).map((f) => f.codigo);
    const oem = unique((cross.get(primary.codigo) || []).map((c) => c.replace(/\.+$/, ""))).slice(0, 6);
    const alternativas = unique(alts).slice(0, 6);
    const aceites = unique(app.aceites);
    const observaciones = [
      motor ? `Motor ${motor}` : null,
      cilindrada ? `Cilindrada ${cilindrada}` : null,
      anio ? `Año ${anio}` : null,
      app.categoria,
    ]
      .filter(Boolean)
      .join(" · ");

    const key = [marca, modelo, motor, cilindrada, anio, primary.codigo, primary.rol]
      .join("|")
      .toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);

    records.push({
      marca,
      modelo,
      motor,
      cilindrada,
      anio,
      categoria: app.categoria,
      filtroCodigo: primary.codigo,
      filtroRol: primary.rol,
      tipoFiltro: filterType(primary.codigo),
      aceitesRecomendados: aceites,
      alternativas,
      equivalencias: oem,
      observaciones,
    });
  }
  return records;
}

async function main() {
  const buffer = fs.readFileSync(PDF_PATH);
  const parser = new PDFParse({ data: new Uint8Array(buffer) });
  const parsed = await parser.getText();
  await parser.destroy();
  const text = parsed.text;
  const apps = parseApplications(text);
  const cross = parseCrossRefs(text);
  const records = finalize(apps, cross);

  const payload = {
    source: "WEB Filtros — catálogo 6 de agosto de 2026",
    version: 1,
    items: records,
  };
  fs.mkdirSync(path.dirname(OUT_PATH), { recursive: true });
  fs.writeFileSync(OUT_PATH, JSON.stringify(payload));
  console.log(
    JSON.stringify(
      {
        pages: parsed.total,
        rawApps: apps.length,
        records: records.length,
        marcas: new Set(records.map((r) => r.marca)).size,
        filtros: new Set(records.map((r) => r.filtroCodigo)).size,
        withOilTypes: records.filter((r) => r.aceitesRecomendados.length > 0).length,
        withAlternatives: records.filter((r) => r.alternativas.length > 0).length,
        out: OUT_PATH,
        sample: records.slice(0, 6),
        brands: [...new Set(records.map((r) => r.marca))].slice(0, 40),
      },
      null,
      2
    )
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
