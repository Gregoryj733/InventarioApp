const express = require("express");
const { createStore } = require("./store");

const API_KEY = process.env.API_KEY || "inventario-sync-key";
const PORT = Number(process.env.PORT || 8787);

const app = express();
app.use(express.json({ limit: "12mb" }));

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "inventario-sync" });
});

app.get("/", (_req, res) => {
  res.json({
    ok: true,
    service: "inventario-sync",
    health: "/health",
    api: "/v1/state",
    auth: "Header X-Api-Key requerido en rutas /v1/*"
  });
});

app.use((req, res, next) => {
  if (!API_KEY) return next();
  const key = req.get("X-Api-Key");
  if (key !== API_KEY) {
    return res.status(401).json({ error: "Clave API inválida" });
  }
  next();
});

async function start() {
  const store = await createStore();

  app.get("/v1/state", async (_req, res) => {
    res.json(await store.loadState());
  });

  app.put("/v1/inventory", async (req, res) => {
    const { products, meta } = req.body || {};
    if (!Array.isArray(products)) {
      return res.status(400).json({ error: "products debe ser un arreglo" });
    }

    const state = await store.loadState();
    const revision = Date.now();
    state.inventoryRevision = revision;
    state.products = products;
    state.meta = {
      ...state.meta,
      ...(meta || {}),
      inventoryRevision: revision
    };
    await store.saveState(state);
    res.json({ inventoryRevision: revision });
  });

  app.post("/v1/inventory/deduct", async (req, res) => {
    const lines = req.body?.lines;
    if (!Array.isArray(lines) || lines.length === 0) {
      return res.status(400).json({ error: "lines debe ser un arreglo no vacío" });
    }

    const state = await store.loadState();
    const now = Date.now();

    for (const line of lines) {
      const syncId = line?.syncId;
      const quantity = Number(line?.quantity);
      if (!syncId || !Number.isFinite(quantity) || quantity <= 0) {
        return res.status(400).json({ error: "Línea de pedido inválida" });
      }

      const product = state.products.find((item) => item.syncId === syncId);
      if (!product) {
        return res.status(400).json({ error: `Producto no encontrado: ${syncId}` });
      }

      const newQty = Number(product.quantity) - quantity;
      if (newQty < 0) {
        return res.status(400).json({
          error: `Stock insuficiente para "${product.description}"`
        });
      }

      product.quantity = newQty;
      product.updatedAt = now;
    }

    await store.saveState(state);
    res.json({ ok: true });
  });

  app.put("/v1/meta", async (req, res) => {
    const state = await store.loadState();
    state.meta = {
      ...state.meta,
      ...(req.body || {})
    };
    await store.saveState(state);
    res.json(state.meta);
  });

  app.post("/v1/sales", async (req, res) => {
    const sale = req.body || {};
    const syncId = sale.syncId;
    const createdAt = Number(sale.createdAt);
    const totalUsd = Number(sale.totalUsd);

    if (!syncId || !Number.isFinite(createdAt) || !Number.isFinite(totalUsd)) {
      return res.status(400).json({ error: "Venta inválida" });
    }

    const state = await store.loadState();
    if (!state.sales.some((item) => item.syncId === syncId)) {
      state.sales.push({
        syncId,
        createdAt,
        totalUsd
      });
      await store.saveState(state);
    }

    res.json({ ok: true });
  });

  app.listen(PORT, "0.0.0.0", () => {
    console.log(`Inventario sync server on http://0.0.0.0:${PORT}`);
    console.log(`Storage backend: ${store.backend}`);
    if (store.dataPath) {
      console.log(`Data file: ${store.dataPath}`);
    }
    console.log(`API key configured: ${API_KEY ? "yes" : "no"}`);
  });
}

start().catch((error) => {
  console.error("Failed to start server", error);
  process.exit(1);
});
