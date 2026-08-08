const { Pool } = require("pg");

let pool;

// El estado se guarda como una fila por "colección" (products, meta, sales,
// cashClosings, users) en vez de una única fila gigante con todo. Así, una
// operación que solo toca el inventario (deducción, import) o solo la tasa
// BCV (meta) no tiene que releer ni reescribir el historial de ventas o
// cierres de caja, que es lo que más crece con el tiempo. Esto reduce el
// tráfico hacia Postgres en cada mutación, algo relevante en el plan free de
// Neon (cómputo medido) y evita que cada venta sea cada vez más costosa de
// registrar a medida que se acumula historial.
const ENTITY_KEYS = ["products", "meta", "sales", "cashClosings", "users", "batteryFinder", "discountTickets"];

function defaultMeta() {
  return { bcvRate: null, bcvFetchedAt: null, lastInventoryUpdateAt: null };
}

function defaultEntityValue(key) {
  switch (key) {
    case "products":
      return { items: [], inventoryRevision: 0 };
    case "meta":
      return defaultMeta();
    case "sales":
      return { items: [], lineItems: [], nextLineItemId: 1 };
    case "cashClosings":
      return { items: [], nextId: 1 };
    case "users":
      return { items: [], nextId: 1 };
    case "batteryFinder":
      // Catálogo de compatibilidad marca/modelo/año -> batería (módulo
      // "Validar Batería"), copiado de duncan.com.ve. Cambia muy rara vez,
      // por eso vive en su propia colección separada de products/sales.
      return { items: [] };
    case "discountTickets":
      return { items: [], nextId: 1 };
    default:
      return null;
  }
}

/**
 * Normaliza un valor que debería ser un arreglo. Algunas filas quedaron
 * persistidas hace tiempo con el arreglo ya serializado como texto (JSON
 * dentro de JSON, incluso anidado varias veces) por una corrupción de datos
 * anterior a este archivo; eso hacía que cualquier `.some()`/`.filter()`
 * sobre esa colección tumbara el request entero con un TypeError, sin
 * posibilidad de auto-recuperarse porque `valor || []` no reemplaza un
 * string no vacío. Aquí se intenta desenrollar el texto hasta encontrar un
 * arreglo real; si no se logra, se descarta a un arreglo vacío en vez de
 * romper la petición. En cuanto la colección se reescriba (cualquier venta,
 * pedido o import nuevo), `persistChanged` guarda la forma ya saneada.
 */
function asArray(value) {
  let current = value;
  for (let attempts = 0; attempts < 5 && typeof current === "string"; attempts += 1) {
    try {
      current = JSON.parse(current);
    } catch (error) {
      return [];
    }
  }
  return Array.isArray(current) ? current : [];
}

/** Reconstruye el objeto de estado "plano" que usa el resto del servidor a partir de las filas por colección. */
function composeState(valuesByKey) {
  const products = valuesByKey.products || defaultEntityValue("products");
  const sales = valuesByKey.sales || defaultEntityValue("sales");
  const cashClosings = valuesByKey.cashClosings || defaultEntityValue("cashClosings");
  const users = valuesByKey.users || defaultEntityValue("users");
  const meta = valuesByKey.meta || defaultEntityValue("meta");
  const batteryFinder = valuesByKey.batteryFinder || defaultEntityValue("batteryFinder");
  const discountTickets = valuesByKey.discountTickets || defaultEntityValue("discountTickets");
  return {
    inventoryRevision: Number(products.inventoryRevision) || 0,
    meta,
    products: asArray(products.items),
    sales: asArray(sales.items),
    saleLineItems: asArray(sales.lineItems),
    cashClosings: asArray(cashClosings.items),
    users: asArray(users.items),
    batteryFinder: asArray(batteryFinder.items),
    discountTickets: asArray(discountTickets.items),
    nextCashClosingId: Number(cashClosings.nextId) || 1,
    nextSaleLineItemId: Number(sales.nextLineItemId) || 1,
    nextUserId: Number(users.nextId) || 1,
    nextDiscountTicketId: Number(discountTickets.nextId) || 1
  };
}

/** Descompone el estado "plano" en el valor que le corresponde a cada colección. */
function decomposeState(state) {
  return {
    products: {
      items: state.products || [],
      inventoryRevision: Number(state.inventoryRevision) || 0
    },
    meta: state.meta || defaultEntityValue("meta"),
    sales: {
      items: state.sales || [],
      lineItems: state.saleLineItems || [],
      nextLineItemId: Number(state.nextSaleLineItemId) || 1
    },
    cashClosings: {
      items: state.cashClosings || [],
      nextId: Number(state.nextCashClosingId) || 1
    },
    users: {
      items: state.users || [],
      nextId: Number(state.nextUserId) || 1
    },
    batteryFinder: {
      items: state.batteryFinder || []
    },
    discountTickets: {
      items: state.discountTickets || [],
      nextId: Number(state.nextDiscountTicketId) || 1
    }
  };
}

/**
 * Serialización con claves de objeto ordenadas recursivamente, usada SOLO
 * para comparar si una colección cambió. Postgres/JSONB no garantiza
 * conservar el orden de las claves que insertamos, así que comparar con
 * JSON.stringify "tal cual" podría detectar un cambio falso (por orden de
 * claves distinto) y escribir de más en cada transacción, anulando la
 * optimización de no reescribir lo que no cambió.
 */
function stableStringify(value) {
  if (Array.isArray(value)) {
    return `[${value.map(stableStringify).join(",")}]`;
  }
  if (value && typeof value === "object") {
    const keys = Object.keys(value).sort();
    return `{${keys.map((k) => `${JSON.stringify(k)}:${stableStringify(value[k])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

/**
 * Quita `sslmode` de la connection string antes de pasarla a `pg.Pool`.
 * TLS ya se controla explícitamente vía la opción `ssl` de abajo (no
 * dependemos de lo que traiga la URL), así que dejarlo generaba en cada
 * arranque la advertencia de `pg-connection-string` sobre que 'prefer',
 * 'require' y 'verify-ca' cambiarán de semántica en la próxima versión
 * mayor de `pg` — ruido en los logs de Render que tapa señales reales
 * (p. ej. la línea de keep-alive) sin aportar nada, ya que ese valor
 * nunca se usa.
 */
function stripSslModeParam(rawUrl) {
  try {
    const url = new URL(rawUrl);
    url.searchParams.delete("sslmode");
    return url.toString();
  } catch (error) {
    return rawUrl;
  }
}

async function tableExists(client, tableName) {
  const { rows } = await client.query("SELECT to_regclass($1) AS reg", [tableName]);
  return Boolean(rows[0]?.reg);
}

/**
 * Migra, si hace falta, los datos del esquema anterior (una sola fila con
 * todas las columnas) al nuevo esquema de filas por colección. No se destruye
 * la tabla vieja: se deja como respaldo sin uso.
 */
async function migrateLegacyRowIfNeeded(client) {
  const { rows: existingRows } = await client.query("SELECT key FROM sync_kv LIMIT 1");
  if (existingRows.length > 0) return; // ya migrado / ya en uso

  const hasLegacyTable = await tableExists(client, "sync_state");
  let legacyState = null;
  if (hasLegacyTable) {
    const { rows } = await client.query(
      `SELECT inventory_revision, meta, products, sales, sale_line_items,
              cash_closings, users, next_cash_closing_id, next_sale_line_item_id, next_user_id
       FROM sync_state WHERE id = 1`
    );
    if (rows[0]) {
      const row = rows[0];
      legacyState = {
        inventoryRevision: Number(row.inventory_revision) || 0,
        meta: row.meta || defaultEntityValue("meta"),
        products: row.products || [],
        sales: row.sales || [],
        saleLineItems: row.sale_line_items || [],
        cashClosings: row.cash_closings || [],
        users: row.users || [],
        nextCashClosingId: Number(row.next_cash_closing_id) || 1,
        nextSaleLineItemId: Number(row.next_sale_line_item_id) || 1,
        nextUserId: Number(row.next_user_id) || 1
      };
    }
  }

  const decomposed = legacyState ? decomposeState(legacyState) : null;
  for (const key of ENTITY_KEYS) {
    const value = decomposed ? decomposed[key] : defaultEntityValue(key);
    await client.query(
      "INSERT INTO sync_kv (key, value) VALUES ($1, $2) ON CONFLICT (key) DO NOTHING",
      [key, JSON.stringify(value)]
    );
  }
  if (legacyState) {
    console.log("store-pg: datos migrados del esquema anterior (sync_state) a sync_kv");
  }
}

async function init() {
  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) {
    throw new Error("DATABASE_URL no configurada");
  }

  const useSsl = process.env.PGSSL !== "false";
  pool = new Pool({
    connectionString: stripSslModeParam(connectionString),
    ssl: useSsl ? { rejectUnauthorized: false } : undefined
  });

  const client = await pool.connect();
  try {
    await client.query(`
      CREATE TABLE IF NOT EXISTS sync_kv (
        key TEXT PRIMARY KEY,
        value JSONB NOT NULL
      );
    `);
    await migrateLegacyRowIfNeeded(client);
    await ensureAllEntityKeysExist(client);
  } finally {
    client.release();
  }
}

/** Inserta en sync_kv cualquier colección nueva que falte (p. ej. batteryFinder). */
async function ensureAllEntityKeysExist(client) {
  const { rows } = await client.query("SELECT key FROM sync_kv");
  const existing = new Set(rows.map((row) => row.key));
  for (const key of ENTITY_KEYS) {
    if (existing.has(key)) continue;
    await client.query(
      "INSERT INTO sync_kv (key, value) VALUES ($1, $2) ON CONFLICT (key) DO NOTHING",
      [key, JSON.stringify(defaultEntityValue(key))]
    );
    console.log(`store-pg: creada colección faltante sync_kv.${key}`);
  }
}

async function fetchValuesByKey(client, forUpdate) {
  const { rows } = await client.query(
    `SELECT key, value FROM sync_kv WHERE key = ANY($1)${forUpdate ? " FOR UPDATE" : ""}`,
    [ENTITY_KEYS]
  );
  const valuesByKey = {};
  for (const row of rows) {
    valuesByKey[row.key] = row.value;
  }
  return valuesByKey;
}

async function loadState() {
  const valuesByKey = await fetchValuesByKey(pool, false);
  return composeState(valuesByKey);
}

/**
 * Carga solo la colección de ventas (+ line items). Usado por GET /v1/sales
 * para evitar traer inventario, usuarios y cierres en cada previsualización.
 */
async function loadSales() {
  const { rows } = await pool.query(
    "SELECT value FROM sync_kv WHERE key = $1",
    ["sales"]
  );
  const sales = rows[0]?.value || defaultEntityValue("sales");
  return {
    sales: asArray(sales.items),
    saleLineItems: asArray(sales.lineItems)
  };
}

/** Escribe solo las colecciones cuyo valor cambió respecto a `previousValuesByKey`. */
async function persistChanged(client, nextValuesByKey, previousValuesByKey) {
  for (const key of ENTITY_KEYS) {
    const nextValue = nextValuesByKey[key];
    const previousValue = previousValuesByKey[key];
    const unchanged = previousValue !== undefined && stableStringify(nextValue) === stableStringify(previousValue);
    if (unchanged) continue; // sin cambios: no reescribir esta colección
    await client.query(
      `INSERT INTO sync_kv (key, value) VALUES ($1, $2)
       ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value`,
      [key, JSON.stringify(nextValue)]
    );
  }
}

async function saveState(state) {
  const previousValuesByKey = await fetchValuesByKey(pool, false);
  await persistChanged(pool, decomposeState(state), previousValuesByKey);
}

/**
 * Ejecuta lectura + mutación + escritura dentro de una transacción con
 * bloqueo de fila (SELECT ... FOR UPDATE) sobre todas las colecciones,
 * preservando la misma serialización de escrituras concurrentes que antes,
 * pero solo reescribe en Postgres las colecciones que realmente cambiaron.
 */
async function runTransaction(mutator) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const previousValuesByKey = await fetchValuesByKey(client, true);
    const state = composeState(previousValuesByKey);
    const { state: nextState, result } = await mutator(state);
    await persistChanged(client, decomposeState(nextState), previousValuesByKey);
    await client.query("COMMIT");
    return result;
  } catch (error) {
    await client.query("ROLLBACK").catch(() => {});
    throw error;
  } finally {
    client.release();
  }
}

module.exports = {
  init,
  loadState,
  loadSales,
  saveState,
  runTransaction,
  backend: "postgres"
};
