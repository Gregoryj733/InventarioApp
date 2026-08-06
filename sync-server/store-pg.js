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
const ENTITY_KEYS = ["products", "meta", "sales", "cashClosings", "users"];

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
    default:
      return null;
  }
}

/** Reconstruye el objeto de estado "plano" que usa el resto del servidor a partir de las filas por colección. */
function composeState(valuesByKey) {
  const products = valuesByKey.products || defaultEntityValue("products");
  const sales = valuesByKey.sales || defaultEntityValue("sales");
  const cashClosings = valuesByKey.cashClosings || defaultEntityValue("cashClosings");
  const users = valuesByKey.users || defaultEntityValue("users");
  const meta = valuesByKey.meta || defaultEntityValue("meta");
  return {
    inventoryRevision: Number(products.inventoryRevision) || 0,
    meta,
    products: products.items || [],
    sales: sales.items || [],
    saleLineItems: sales.lineItems || [],
    cashClosings: cashClosings.items || [],
    users: users.items || [],
    nextCashClosingId: Number(cashClosings.nextId) || 1,
    nextSaleLineItemId: Number(sales.nextLineItemId) || 1,
    nextUserId: Number(users.nextId) || 1
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
    connectionString,
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
  } finally {
    client.release();
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
  saveState,
  runTransaction,
  backend: "postgres"
};
