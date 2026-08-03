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
      lastInventoryUpdateAt: null
    },
    products: [],
    sales: []
  };
}

function init() {
  if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
  }
}

function loadState() {
  if (!fs.existsSync(DATA_FILE)) {
    return defaultState();
  }
  try {
    return JSON.parse(fs.readFileSync(DATA_FILE, "utf8"));
  } catch (error) {
    console.warn("data.json corrupto, reiniciando estado", error);
    return defaultState();
  }
}

function saveState(state) {
  fs.writeFileSync(DATA_FILE, JSON.stringify(state, null, 2));
}

module.exports = {
  init,
  loadState,
  saveState,
  backend: "file",
  dataPath: DATA_FILE
};
