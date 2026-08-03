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
    sales: []
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
      sales JSONB NOT NULL DEFAULT '[]'
    );
    INSERT INTO sync_state (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
  `);
}

async function loadState() {
  const { rows } = await pool.query(
    "SELECT inventory_revision, meta, products, sales FROM sync_state WHERE id = 1"
  );
  if (!rows[0]) {
    return defaultState();
  }
  return {
    inventoryRevision: Number(rows[0].inventory_revision) || 0,
    meta: rows[0].meta || defaultState().meta,
    products: rows[0].products || [],
    sales: rows[0].sales || []
  };
}

async function saveState(state) {
  await pool.query(
    `
    UPDATE sync_state
    SET inventory_revision = $1,
        meta = $2,
        products = $3,
        sales = $4
    WHERE id = 1
    `,
    [state.inventoryRevision, state.meta, state.products, state.sales]
  );
}

module.exports = {
  init,
  loadState,
  saveState,
  backend: "postgres"
};
