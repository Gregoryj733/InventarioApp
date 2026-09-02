const fs = require("fs");
const path = require("path");

const DATA_DIR = process.env.DATA_DIR || __dirname;
const DATA_FILE = path.join(DATA_DIR, "data.json");

function defaultState() {
  return {
    inventoryRevision: 0,
    meta: {
      bcvRate: null,
      bcvFetchedAt: null,
      lastInventoryUpdateAt: null,
      lastSalesUpdateAt: null
    },
    products: [],
    sales: [],
    saleLineItems: [],
    cashClosings: [],
    users: [],
    batteryFinder: [],
    discountTickets: [],
    nextCashClosingId: 1,
    nextSaleLineItemId: 1,
    nextUserId: 1,
    nextDiscountTicketId: 1
  };
}

function init() {
  if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
  }
}

function readRaw() {
  if (!fs.existsSync(DATA_FILE)) {
    return defaultState();
  }
  try {
    const parsed = JSON.parse(fs.readFileSync(DATA_FILE, "utf8"));
    return { ...defaultState(), ...parsed };
  } catch (error) {
    console.warn("data.json corrupto, reiniciando estado", error);
    return defaultState();
  }
}

async function loadState() {
  return readRaw();
}

/** Misma forma que store-pg.loadSales: solo ventas para GET /v1/sales. */
async function loadSales() {
  const state = readRaw();
  return {
    sales: state.sales || [],
    saleLineItems: state.saleLineItems || []
  };
}

function writeRaw(state) {
  fs.writeFileSync(DATA_FILE, JSON.stringify(state, null, 2));
}

async function saveState(state) {
  writeRaw(state);
}

// Almacenamiento de archivo: un solo proceso local, se serializa con una cola
// en memoria para evitar escrituras concurrentes corruptas.
let queue = Promise.resolve();

async function runTransaction(mutator) {
  const result = queue.then(async () => {
    const state = readRaw();
    const next = await mutator(state);
    writeRaw(next.state);
    return next.result;
  });
  queue = result.catch(() => {});
  return result;
}

module.exports = {
  init,
  loadState,
  loadSales,
  saveState,
  runTransaction,
  ping: async () => {},
  backend: "file",
  dataPath: DATA_FILE
};
