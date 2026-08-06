const { Pool } = require("pg");

let pool;

function defaultState() {
  return {
    inventoryRevision: 0,
    meta: {
      bcvRate: null,
      bcvFetchedAt: null,
      lastInventoryUpdateAt: null
    },
    products: [],
    sales: [],
    saleLineItems: [],
    cashClosings: [],
    users: [],
    nextCashClosingId: 1,
    nextSaleLineItemId: 1,
    nextUserId: 1
  };
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

  await pool.query(`
    CREATE TABLE IF NOT EXISTS sync_state (
      id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
      inventory_revision BIGINT NOT NULL DEFAULT 0,
      meta JSONB NOT NULL DEFAULT '{}',
      products JSONB NOT NULL DEFAULT '[]',
      sales JSONB NOT NULL DEFAULT '[]',
      sale_line_items JSONB NOT NULL DEFAULT '[]',
      cash_closings JSONB NOT NULL DEFAULT '[]',
      users JSONB NOT NULL DEFAULT '[]',
      next_cash_closing_id BIGINT NOT NULL DEFAULT 1,
      next_sale_line_item_id BIGINT NOT NULL DEFAULT 1,
      next_user_id BIGINT NOT NULL DEFAULT 1
    );
    INSERT INTO sync_state (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
  `);

  // Migración suave para bases creadas antes de agregar estas columnas.
  await pool.query(`
    ALTER TABLE sync_state ADD COLUMN IF NOT EXISTS sale_line_items JSONB NOT NULL DEFAULT '[]';
    ALTER TABLE sync_state ADD COLUMN IF NOT EXISTS cash_closings JSONB NOT NULL DEFAULT '[]';
    ALTER TABLE sync_state ADD COLUMN IF NOT EXISTS users JSONB NOT NULL DEFAULT '[]';
    ALTER TABLE sync_state ADD COLUMN IF NOT EXISTS next_cash_closing_id BIGINT NOT NULL DEFAULT 1;
    ALTER TABLE sync_state ADD COLUMN IF NOT EXISTS next_sale_line_item_id BIGINT NOT NULL DEFAULT 1;
    ALTER TABLE sync_state ADD COLUMN IF NOT EXISTS next_user_id BIGINT NOT NULL DEFAULT 1;
  `);
}

function rowToState(row) {
  if (!row) return defaultState();
  return {
    inventoryRevision: Number(row.inventory_revision) || 0,
    meta: row.meta || defaultState().meta,
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

async function loadState() {
  const { rows } = await pool.query(
    `SELECT inventory_revision, meta, products, sales, sale_line_items,
            cash_closings, users, next_cash_closing_id, next_sale_line_item_id, next_user_id
     FROM sync_state WHERE id = 1`
  );
  return rowToState(rows[0]);
}

async function persist(client, state) {
  await client.query(
    `
    UPDATE sync_state
    SET inventory_revision = $1,
        meta = $2,
        products = $3,
        sales = $4,
        sale_line_items = $5,
        cash_closings = $6,
        users = $7,
        next_cash_closing_id = $8,
        next_sale_line_item_id = $9,
        next_user_id = $10
    WHERE id = 1
    `,
    [
      state.inventoryRevision,
      state.meta,
      JSON.stringify(state.products),
      JSON.stringify(state.sales),
      JSON.stringify(state.saleLineItems),
      JSON.stringify(state.cashClosings),
      JSON.stringify(state.users),
      state.nextCashClosingId,
      state.nextSaleLineItemId,
      state.nextUserId
    ]
  );
}

async function saveState(state) {
  await persist(pool, state);
}

/**
 * Ejecuta lectura + mutación + escritura dentro de una transacción con
 * bloqueo de fila (SELECT ... FOR UPDATE), evitando pérdidas de datos por
 * escrituras concurrentes de distintos dispositivos.
 */
async function runTransaction(mutator) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const { rows } = await client.query(
      `SELECT inventory_revision, meta, products, sales, sale_line_items,
              cash_closings, users, next_cash_closing_id, next_sale_line_item_id, next_user_id
       FROM sync_state WHERE id = 1 FOR UPDATE`
    );
    const state = rowToState(rows[0]);
    const { state: nextState, result } = await mutator(state);
    await persist(client, nextState);
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
