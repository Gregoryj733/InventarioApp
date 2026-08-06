const express = require("express");
const http = require("http");
const multer = require("multer");
const { createStore } = require("./store");
const auth = require("./auth");
const { parseInventoryExcel } = require("./excelParser");
const realtime = require("./realtime");
const push = require("./push");

const API_KEY = process.env.API_KEY || "inventario-sync-key";
const PORT = Number(process.env.PORT || 8787);
const MAX_CLOSINGS_PER_DAY = 5;
// El plan free de Render "duerme" el servicio tras ~15 min sin tráfico
// entrante y también suspende la base Postgres de Neon tras inactividad.
// Cuando eso pasa, el WebSocket de TODOS los dispositivos conectados se
// corta a la vez y la reconexión + "despertar" el servidor puede tardar
// hasta un minuto, lo que se percibe como pedidos/inventario desfasados
// entre celulares. Un ping periódico a /health (tráfico HTTP entrante real,
// no una llamada interna) evita que el contenedor llegue a esos ~15 min de
// inactividad y mantiene la conexión a la base de datos activa.
const KEEP_ALIVE_URL = (process.env.KEEP_ALIVE_URL || process.env.RENDER_EXTERNAL_URL || "").trim();
const KEEP_ALIVE_INTERVAL_MS = Number(process.env.KEEP_ALIVE_INTERVAL_MS) || 4 * 60 * 1000;
// Roles que el módulo de Usuarios puede crear/editar/eliminar. ADMIN se
// gestiona fuera de esta API (usuario semilla único).
const MANAGEABLE_ROLES = ["CONSULTA", "SUPERVISOR"];
// Roles con permiso para aprobar, rechazar o revertir cierres de caja
// (Flujo Aprobación).
const CLOSING_REVIEW_ROLES = ["ADMIN", "SUPERVISOR"];

const app = express();
app.use(express.json({ limit: "12mb" }));
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 15 * 1024 * 1024 } });

let storeRef = null;

app.get("/health", async (_req, res) => {
  if (!storeRef) {
    return res.status(503).json({ ok: false, service: "inventario-sync", starting: true });
  }
  try {
    await storeRef.loadState();
    res.json({ ok: true, service: "inventario-sync", backend: storeRef.backend });
  } catch (error) {
    console.error("Health check failed", error);
    res.status(503).json({ ok: false, service: "inventario-sync", error: error.message });
  }
});

app.get("/", (_req, res) => {
  res.json({
    ok: true,
    service: "inventario-sync",
    health: "/health",
    api: "/v1/state",
    realtime: "/v1/ws",
    auth: "Header X-Api-Key requerido en rutas /v1/*; Authorization: Bearer <token> en rutas protegidas"
  });
});

app.use((req, res, next) => {
  if (!API_KEY) return next();
  // El WebSocket (/v1/ws) manda la clave como query param, no como header;
  // si el upgrade no llega "crudo" (p. ej. reintentos por HTTP/1.1 tras un
  // fallo de negociación, o algún proxy intermedio) la request cae aquí como
  // un GET normal, así que aceptamos ambas formas para no romper el socket.
  const key = req.get("X-Api-Key") || req.query.apiKey;
  if (key !== API_KEY) {
    return res.status(401).json({ error: "Clave API inválida" });
  }
  next();
});

function asyncRoute(handler) {
  return async (req, res, next) => {
    try {
      await handler(req, res, next);
    } catch (error) {
      console.error(`${req.method} ${req.path} failed`, error);
      if (!res.headersSent) {
        const status = error.statusCode || 500;
        res.status(status).json({ error: error.publicMessage || "Error interno del servidor de sincronización" });
      }
    }
  };
}

function publicError(message, statusCode = 400) {
  const error = new Error(message);
  error.publicMessage = message;
  error.statusCode = statusCode;
  return error;
}

/**
 * Filtra `items` por `[start, end)` sobre `field` cuando ambos vienen en la
 * query string. Si faltan (o son inválidos), devuelve `items` sin cambios
 * para no romper compatibilidad con clientes que aún no envían el rango.
 */
function filterByRange(items, field, query) {
  const start = Number(query?.start);
  const end = Number(query?.end);
  if (!Number.isFinite(start) || !Number.isFinite(end)) return items;
  return items.filter((item) => item[field] >= start && item[field] < end);
}

function todayBoundsFrom(dateMillis) {
  const date = new Date(dateMillis);
  date.setHours(0, 0, 0, 0);
  const start = date.getTime();
  const end = start + 24 * 60 * 60 * 1000;
  return { start, end };
}

function sanitizeUser(user) {
  return {
    id: user.id,
    username: user.username,
    role: user.role,
    active: user.active,
    sucursal: user.sucursal || ""
  };
}

async function seedDefaultUsers(store) {
  await store.runTransaction(async (state) => {
    if (state.users.length > 0) {
      return { state, result: null };
    }
    const now = state.nextUserId;
    const seeded = [
      {
        id: now,
        username: "consulta",
        passwordHash: auth.hashPassword("consulta"),
        role: "CONSULTA",
        active: true,
        sucursal: ""
      },
      {
        id: now + 1,
        username: "admin",
        passwordHash: auth.hashPassword("admin"),
        role: "ADMIN",
        active: true,
        sucursal: ""
      }
    ];
    return {
      state: { ...state, users: seeded, nextUserId: now + 2 },
      result: null
    };
  });
}

/**
 * Carga el catálogo de compatibilidad marca/modelo/año -> batería (copiado
 * de duncan.com.ve, ver sync-server/data/battery-finder.json) solo si la
 * colección todavía está vacía. Al no depender de una API externa en tiempo
 * de ejecución, basta con sembrarlo una vez; una actualización futura del
 * archivo requeriría un cambio explícito (no se resiembra automáticamente
 * para no pisar datos ya editados a mano en la nube).
 */
async function seedBatteryFinderData(store) {
  await store.runTransaction(async (state) => {
    if (state.batteryFinder.length > 0) {
      return { state, result: null };
    }
    const seedData = require("./data/battery-finder.json");
    return {
      state: { ...state, batteryFinder: seedData },
      result: null
    };
  });
}

/**
 * Ping periódico a la propia URL pública (no localhost: tiene que ser
 * tráfico HTTP entrante "real" para que la plataforma no cuente al
 * servicio como inactivo) para evitar el sueño por inactividad del plan
 * free y mantener viva la conexión a la base de datos. Se desactiva solo
 * si no hay ninguna URL configurada (p. ej. desarrollo local con `npm
 * start`, donde no aplica).
 */
function startKeepAlive() {
  if (!KEEP_ALIVE_URL) {
    console.log(
      "Keep-alive deshabilitado (define KEEP_ALIVE_URL o usa el RENDER_EXTERNAL_URL " +
        "automático de Render para evitar que el plan free duerma el servicio)."
    );
    return;
  }
  const target = `${KEEP_ALIVE_URL.replace(/\/+$/, "")}/health`;
  const ping = () => {
    fetch(target).catch((error) => {
      console.warn("Keep-alive ping falló:", error.message);
    });
  };
  setInterval(ping, KEEP_ALIVE_INTERVAL_MS);
  console.log(`Keep-alive activo: ping a ${target} cada ${Math.round(KEEP_ALIVE_INTERVAL_MS / 1000)}s`);
}

async function start() {
  const store = await createStore();
  storeRef = store;
  push.init();
  await seedDefaultUsers(store);
  await seedBatteryFinderData(store);

  // ---------- Autenticación ----------
  app.post("/v1/auth/login", asyncRoute(async (req, res) => {
    const username = String(req.body?.username || "").trim().toLowerCase();
    const password = String(req.body?.password || "");
    if (!username || !password) {
      throw publicError("Usuario y contraseña son requeridos");
    }
    const state = await store.loadState();
    const user = state.users.find((u) => u.username.toLowerCase() === username);
    if (!user || !auth.verifyPassword(password, user.passwordHash)) {
      return res.status(401).json({ error: "Credenciales incorrectas" });
    }
    if (!user.active) {
      return res.status(403).json({ error: "Usuario desactivado. Contacta al administrador." });
    }
    const token = auth.signToken(user);
    res.json({ token, user: sanitizeUser(user) });
  }));

  // Cambio de la propia contraseña: la única vía para rotar la del usuario
  // "admin" (semilla, fuera del CRUD de /v1/users) sin tocar la base de
  // datos a mano, ya que solo requiere la sesión ya autenticada.
  app.post(
    "/v1/auth/password",
    auth.requireAuth(),
    asyncRoute(async (req, res) => {
      const currentPassword = String(req.body?.currentPassword || "");
      const newPassword = String(req.body?.newPassword || "");
      if (newPassword.length < 4) {
        throw publicError("La nueva contraseña debe tener al menos 4 caracteres.");
      }
      await store.runTransaction(async (state) => {
        const users = state.users.map((u) => ({ ...u }));
        const target = users.find((u) => String(u.id) === String(req.user.sub));
        if (!target) throw publicError("Usuario no encontrado", 404);
        if (!auth.verifyPassword(currentPassword, target.passwordHash)) {
          throw publicError("La contraseña actual no es correcta.", 401);
        }
        target.passwordHash = auth.hashPassword(newPassword);
        return { state: { ...state, users }, result: null };
      });
      realtime.broadcast("users", {});
      res.json({ ok: true });
    })
  );

  // ---------- Inventario ----------
  app.get("/v1/state", asyncRoute(async (_req, res) => {
    const state = await store.loadState();
    res.json({ inventoryRevision: state.inventoryRevision, meta: state.meta, products: state.products });
  }));

  app.post(
    "/v1/inventory/import",
    auth.requireAuth("ADMIN"),
    upload.single("file"),
    asyncRoute(async (req, res) => {
      if (!req.file) {
        throw publicError("Debes adjuntar un archivo Excel (.xlsx)");
      }
      const parsed = parseInventoryExcel(req.file.buffer);
      if (parsed.products.length === 0) {
        return res.json({ imported: 0, skipped: parsed.skipped, errors: parsed.errors });
      }

      const now = Date.now();
      const products = parsed.products.map((p) => ({
        syncId: require("crypto").randomUUID(),
        description: p.description,
        quantity: p.quantity,
        unit: p.unit,
        price: p.price,
        updatedAt: now
      }));

      await store.runTransaction(async (state) => {
        const revision = now;
        const nextState = {
          ...state,
          inventoryRevision: revision,
          products,
          meta: { ...state.meta, lastInventoryUpdateAt: now }
        };
        return { state: nextState, result: revision };
      });

      realtime.broadcast("inventory", {});
      push.sendInventoryUpdatedNotification({ imported: parsed.imported, skipped: parsed.skipped });

      res.json({ imported: parsed.imported, skipped: parsed.skipped, errors: parsed.errors });
    })
  );

  app.post(
    "/v1/inventory/deduct",
    auth.requireAuth(),
    asyncRoute(async (req, res) => {
      const lines = req.body?.lines;
      if (!Array.isArray(lines) || lines.length === 0) {
        throw publicError("lines debe ser un arreglo no vacío");
      }

      await store.runTransaction(async (state) => {
        const now = Date.now();
        const products = state.products.map((p) => ({ ...p }));

        for (const line of lines) {
          const syncId = line?.syncId;
          const quantity = Number(line?.quantity);
          if (!syncId || !Number.isFinite(quantity) || quantity <= 0) {
            throw publicError("Línea de pedido inválida");
          }
          const product = products.find((item) => item.syncId === syncId);
          if (!product) {
            throw publicError(`Producto no encontrado: ${syncId}`);
          }
          const newQty = Number(product.quantity) - quantity;
          if (newQty < 0) {
            throw publicError(`Stock insuficiente para "${product.description}"`);
          }
          product.quantity = newQty;
          product.updatedAt = now;
        }

        return {
          state: { ...state, products, inventoryRevision: now },
          result: null
        };
      });

      realtime.broadcast("inventory", {});
      res.json({ ok: true });
    })
  );

  app.put(
    "/v1/meta",
    auth.requireAuth(),
    asyncRoute(async (req, res) => {
      const updated = await store.runTransaction(async (state) => {
        const meta = { ...state.meta, ...(req.body || {}) };
        return { state: { ...state, meta }, result: meta };
      });
      realtime.broadcast("inventory", {});
      res.json(updated);
    })
  );

  // ---------- Pedidos (descuento de stock + venta, atómico) ----------
  // Combina lo que antes eran dos llamadas separadas (deduct + sales) en una
  // sola transacción idempotente (por syncId), para que un reintento por
  // fallo de red nunca descuente el stock dos veces ni deje una venta a
  // medio registrar.
  app.post(
    "/v1/orders",
    auth.requireAuth(),
    asyncRoute(async (req, res) => {
      const body = req.body || {};
      const syncId = body.syncId;
      const createdAt = Number(body.createdAt);
      const totalUsd = Number(body.totalUsd);
      const bcvRate = Number(body.bcvRate) || 0;
      const lines = Array.isArray(body.lines) ? body.lines : [];

      if (!syncId || !Number.isFinite(createdAt) || !Number.isFinite(totalUsd) || lines.length === 0) {
        throw publicError("Pedido inválido");
      }

      await store.runTransaction(async (state) => {
        if (state.sales.some((item) => item.syncId === syncId)) {
          // Ya se procesó este pedido (reintento tras un corte de red);
          // no volver a descontar stock ni duplicar la venta.
          return { state, result: null };
        }

        const products = state.products.map((p) => ({ ...p }));
        const productBySyncId = new Map(products.map((p) => [p.syncId, p]));
        for (const line of lines) {
          const productSyncId = line?.productSyncId;
          const quantity = Number(line?.quantity);
          if (!productSyncId || !Number.isFinite(quantity) || quantity <= 0) {
            throw publicError("Línea de pedido inválida");
          }
          const product = productBySyncId.get(productSyncId);
          if (!product) {
            throw publicError(`Producto no encontrado: ${line.description || productSyncId}`);
          }
          const newQty = Number(product.quantity) - quantity;
          if (newQty < 0) {
            throw publicError(`Stock insuficiente para "${product.description}"`);
          }
          product.quantity = newQty;
          product.updatedAt = createdAt;
        }

        const sales = [...state.sales, { syncId, createdAt, totalUsd, bcvRate }];
        let nextId = state.nextSaleLineItemId;
        // El detalle de cada línea (descripción, cantidad, precio) NUNCA debe
        // quedar vacío en el pedido guardado: si el cliente no lo manda o lo
        // manda en blanco, se completa desde el registro real de inventario
        // (ya resuelto arriba en productBySyncId) en vez de persistir un
        // hueco que luego se muestra como "Sin detalle de productos." en
        // cualquier dispositivo que consulte este pedido.
        const newLineItems = lines.map((line) => {
          const product = productBySyncId.get(line.productSyncId);
          const quantity = Number(line.quantity) || 0;
          const unitPriceUsd = Number(line.unitPriceUsd) || product?.price || 0;
          const description = String(line.description || "").trim() || product?.description || "";
          const unit = String(line.unit || "").trim() || product?.unit || "UNIDAD";
          const totalUsd = Number(line.totalUsd) || unitPriceUsd * quantity;
          return {
            id: nextId++,
            saleSyncId: syncId,
            productSyncId: line.productSyncId || "",
            description,
            quantity,
            unit,
            unitPriceUsd,
            totalUsd,
            createdAt
          };
        });

        return {
          state: {
            ...state,
            products,
            inventoryRevision: createdAt,
            sales,
            saleLineItems: [...state.saleLineItems, ...newLineItems],
            nextSaleLineItemId: nextId
          },
          result: null
        };
      });

      realtime.broadcast("inventory", {});
      realtime.broadcast("sales", {});
      res.json({ ok: true });
    })
  );

  // ---------- Ventas ----------
  // start/end (epoch ms) son opcionales: si se envían, filtra en el servidor
  // en vez de mandar todo el historial de ventas al cliente en cada
  // consulta (p. ej. el total de "hoy"), lo que ahorra datos móviles y
  // ancho de banda del plan free de Render a medida que crece el historial.
  app.get("/v1/sales", auth.requireAuth(), asyncRoute(async (req, res) => {
    const state = await store.loadState();
    const sales = filterByRange(state.sales, "createdAt", req.query);
    const saleSyncIds = new Set(sales.map((s) => s.syncId));
    const lineItems = state.saleLineItems.filter((l) => saleSyncIds.has(l.saleSyncId));
    res.json({ sales, lineItems });
  }));

  app.post(
    "/v1/sales",
    auth.requireAuth(),
    asyncRoute(async (req, res) => {
      const sale = req.body || {};
      const syncId = sale.syncId;
      const createdAt = Number(sale.createdAt);
      const totalUsd = Number(sale.totalUsd);
      const bcvRate = Number(sale.bcvRate) || 0;
      const lines = Array.isArray(sale.lines) ? sale.lines : [];

      if (!syncId || !Number.isFinite(createdAt) || !Number.isFinite(totalUsd)) {
        throw publicError("Venta inválida");
      }

      await store.runTransaction(async (state) => {
        if (state.sales.some((item) => item.syncId === syncId)) {
          return { state, result: null };
        }
        const productBySyncId = new Map(state.products.map((p) => [p.syncId, p]));
        const sales = [...state.sales, { syncId, createdAt, totalUsd, bcvRate }];
        let nextId = state.nextSaleLineItemId;
        const newLineItems = lines.map((line) => {
          const product = productBySyncId.get(line.productSyncId);
          const quantity = Number(line.quantity) || 0;
          const unitPriceUsd = Number(line.unitPriceUsd) || product?.price || 0;
          const description = String(line.description || "").trim() || product?.description || "";
          const unit = String(line.unit || "").trim() || product?.unit || "UNIDAD";
          const totalUsd = Number(line.totalUsd) || unitPriceUsd * quantity;
          return {
            id: nextId++,
            saleSyncId: syncId,
            productSyncId: line.productSyncId || "",
            description,
            quantity,
            unit,
            unitPriceUsd,
            totalUsd,
            createdAt
          };
        });
        return {
          state: {
            ...state,
            sales,
            saleLineItems: [...state.saleLineItems, ...newLineItems],
            nextSaleLineItemId: nextId
          },
          result: null
        };
      });

      realtime.broadcast("sales", {});
      res.json({ ok: true });
    })
  );

  app.delete(
    "/v1/sales",
    auth.requireAuth("ADMIN"),
    asyncRoute(async (req, res) => {
      const start = Number(req.query.start);
      const end = Number(req.query.end);
      if (!Number.isFinite(start) || !Number.isFinite(end)) {
        throw publicError("start y end son requeridos");
      }
      const deleted = await store.runTransaction(async (state) => {
        const kept = state.sales.filter((s) => !(s.createdAt >= start && s.createdAt < end));
        const removedIds = state.sales
          .filter((s) => s.createdAt >= start && s.createdAt < end)
          .map((s) => s.syncId);
        const keptLines = state.saleLineItems.filter((l) => !removedIds.includes(l.saleSyncId));
        return {
          state: { ...state, sales: kept, saleLineItems: keptLines },
          result: state.sales.length - kept.length
        };
      });
      realtime.broadcast("sales", {});
      res.json({ ok: true, deleted });
    })
  );

  // ---------- Cierres de caja ----------
  app.get("/v1/cash-closings", auth.requireAuth(), asyncRoute(async (req, res) => {
    const state = await store.loadState();
    const cashClosings = filterByRange(state.cashClosings, "closedAt", req.query);
    res.json({ cashClosings });
  }));

  app.post(
    "/v1/cash-closings",
    auth.requireAuth(),
    asyncRoute(async (req, res) => {
      const body = req.body || {};
      const username = String(body.username || "").trim().toLowerCase();
      if (!username) {
        throw publicError("username es requerido");
      }
      const closedAt = Number(body.closedAt) || Date.now();
      const { start: dayStart, end: dayEnd } = todayBoundsFrom(closedAt);

      const created = await store.runTransaction(async (state) => {
        const sameDay = state.cashClosings.filter(
          (c) => c.username === username && c.closedAt >= dayStart && c.closedAt < dayEnd
        );
        const latest = sameDay.slice().sort((a, b) => b.closedAt - a.closedAt)[0] || null;
        const maxRevision = sameDay.reduce((max, c) => Math.max(max, c.revisionNumber || 0), 0);

        if (latest?.status === "APPROVED") {
          throw publicError("Tu cierre de caja de hoy ya fue aprobado. No puedes registrar otro.");
        }

        if (latest?.status === "PENDING") {
          if (maxRevision >= MAX_CLOSINGS_PER_DAY) {
            throw publicError(`Has alcanzado el máximo de ${MAX_CLOSINGS_PER_DAY} intentos de cierre por día.`);
          }
          const updatedRecord = {
            ...latest,
            ...body,
            id: latest.id,
            username,
            status: "PENDING",
            revisionNumber: latest.revisionNumber + 1,
            reviewedBy: "",
            reviewedAt: 0
          };
          const cashClosings = state.cashClosings.map((c) => (c.id === latest.id ? updatedRecord : c));
          return { state: { ...state, cashClosings }, result: updatedRecord };
        }

        if (maxRevision >= MAX_CLOSINGS_PER_DAY && latest?.status !== "REVERTED") {
          throw publicError(`Has alcanzado el máximo de ${MAX_CLOSINGS_PER_DAY} intentos de cierre por día.`);
        }

        const id = state.nextCashClosingId;
        const record = {
          ...body,
          id,
          username,
          closedAt,
          status: "PENDING",
          revisionNumber: maxRevision + 1,
          reviewedBy: "",
          reviewedAt: 0
        };
        return {
          state: {
            ...state,
            cashClosings: [...state.cashClosings, record],
            nextCashClosingId: id + 1
          },
          result: record
        };
      });

      realtime.broadcast("cashClosings", {});
      res.json(created);
    })
  );

  app.patch(
    "/v1/cash-closings/:id/status",
    auth.requireAuth(CLOSING_REVIEW_ROLES),
    asyncRoute(async (req, res) => {
      const id = Number(req.params.id);
      const { status, reviewedBy, reviewedAt } = req.body || {};
      const validTransitions = {
        APPROVED: "PENDING",
        REJECTED: "PENDING",
        REVERTED: "APPROVED"
      };
      const requiredCurrentStatus = validTransitions[status];
      if (!requiredCurrentStatus) {
        throw publicError("Estado de cierre inválido");
      }

      const updated = await store.runTransaction(async (state) => {
        const record = state.cashClosings.find((c) => c.id === id);
        if (!record || record.status !== requiredCurrentStatus) {
          throw publicError("No se pudo actualizar el cierre. Verifica su estado actual.");
        }
        const updatedRecord = {
          ...record,
          status,
          reviewedBy: reviewedBy || "",
          reviewedAt: Number(reviewedAt) || Date.now()
        };
        const cashClosings = state.cashClosings.map((c) => (c.id === id ? updatedRecord : c));
        return { state: { ...state, cashClosings }, result: updatedRecord };
      });

      realtime.broadcast("cashClosings", {});
      res.json(updated);
    })
  );

  // ---------- Validar Batería ----------
  // Catálogo de compatibilidad marca/modelo/año -> batería, visible para
  // cualquier perfil (solo requiere la X-Api-Key global, sin auth.requireAuth
  // ni restricción de rol): es información de referencia, no datos
  // sensibles del negocio.
  app.get(
    "/v1/battery-finder",
    asyncRoute(async (_req, res) => {
      const state = await store.loadState();
      const items = Array.isArray(state.batteryFinder)
        ? state.batteryFinder
        : Array.isArray(state.batteryFinder?.items)
          ? state.batteryFinder.items
          : [];
      res.json({ items });
    })
  );

  // ---------- Usuarios ----------
  app.get(
    "/v1/users",
    auth.requireAuth("ADMIN"),
    asyncRoute(async (_req, res) => {
      const state = await store.loadState();
      res.json({
        users: state.users.filter((u) => MANAGEABLE_ROLES.includes(u.role)).map(sanitizeUser)
      });
    })
  );

  app.post(
    "/v1/users",
    auth.requireAuth("ADMIN"),
    asyncRoute(async (req, res) => {
      const username = String(req.body?.username || "").trim().toLowerCase();
      const password = String(req.body?.password || "");
      const sucursal = String(req.body?.sucursal || "").trim();
      const requestedRole = String(req.body?.role || "CONSULTA").trim().toUpperCase();

      if (username.length < 3) throw publicError("El usuario debe tener al menos 3 caracteres.");
      if (password.length < 4) throw publicError("La contraseña debe tener al menos 4 caracteres.");
      if (!sucursal) throw publicError("Indica la sucursal del usuario.");
      if (username === "admin") throw publicError('Ese nombre de usuario no está permitido.');
      if (!MANAGEABLE_ROLES.includes(requestedRole)) {
        throw publicError("Rol de usuario inválido.");
      }

      const created = await store.runTransaction(async (state) => {
        if (state.users.some((u) => u.username.toLowerCase() === username)) {
          throw publicError(`El usuario "${username}" ya existe.`);
        }
        const id = state.nextUserId;
        const user = {
          id,
          username,
          passwordHash: auth.hashPassword(password),
          role: requestedRole,
          active: true,
          sucursal
        };
        return {
          state: { ...state, users: [...state.users, user], nextUserId: id + 1 },
          result: user
        };
      });

      realtime.broadcast("users", {});
      res.json(sanitizeUser(created));
    })
  );

  app.delete(
    "/v1/users/:id",
    auth.requireAuth("ADMIN"),
    asyncRoute(async (req, res) => {
      const id = Number(req.params.id);
      await store.runTransaction(async (state) => {
        const target = state.users.find((u) => u.id === id && MANAGEABLE_ROLES.includes(u.role));
        if (!target) throw publicError("No se pudo eliminar el usuario.");
        const users = state.users.filter((u) => u.id !== id);
        return { state: { ...state, users }, result: null };
      });
      realtime.broadcast("users", {});
      res.json({ ok: true });
    })
  );

  app.patch(
    "/v1/users/:id",
    auth.requireAuth("ADMIN"),
    asyncRoute(async (req, res) => {
      const id = Number(req.params.id);
      const { active, sucursal, role } = req.body || {};
      const requestedRole = typeof role === "string" ? role.trim().toUpperCase() : null;
      if (requestedRole && !MANAGEABLE_ROLES.includes(requestedRole)) {
        throw publicError("Rol de usuario inválido.");
      }
      const updated = await store.runTransaction(async (state) => {
        const target = state.users.find((u) => u.id === id && MANAGEABLE_ROLES.includes(u.role));
        if (!target) throw publicError("No se pudo actualizar el usuario.");
        const updatedUser = {
          ...target,
          active: typeof active === "boolean" ? active : target.active,
          sucursal: typeof sucursal === "string" && sucursal.trim() ? sucursal.trim() : target.sucursal,
          role: requestedRole || target.role
        };
        const users = state.users.map((u) => (u.id === id ? updatedUser : u));
        return { state: { ...state, users }, result: updatedUser };
      });
      realtime.broadcast("users", {});
      res.json(sanitizeUser(updated));
    })
  );

  const httpServer = http.createServer(app);
  realtime.attach(httpServer, API_KEY);

  httpServer.listen(PORT, "0.0.0.0", () => {
    console.log(`Inventario sync server on http://0.0.0.0:${PORT}`);
    console.log(`Storage backend: ${store.backend}`);
    if (store.dataPath) {
      console.log(`Data file: ${store.dataPath}`);
    }
    console.log(`API key configured: ${API_KEY ? "yes" : "no"}`);
    console.log(`WebSocket endpoint: /v1/ws`);
    startKeepAlive();
  });
}

start().catch((error) => {
  console.error("Failed to start server", error);
  process.exit(1);
});
