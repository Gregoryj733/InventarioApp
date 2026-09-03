const express = require("express");
const http = require("http");
const path = require("path");
const crypto = require("crypto");
const multer = require("multer");
const QRCode = require("qrcode");
const { createStore } = require("./store");
const auth = require("./auth");
const { parseInventoryExcel } = require("./excelParser");
const realtime = require("./realtime");
const push = require("./push");

const API_KEY = process.env.API_KEY || "inventario-sync-key";
const PORT = Number(process.env.PORT || 8787);
/** Label de sucursal de esta instancia (debe coincidir con sync_config.json → branches[].label). */
const BRANCH_SUCURSAL = String(process.env.BRANCH_SUCURSAL || "").trim();
/** Supervisor gerente: acceso multi-sucursal desde la app (sin restricción por label). */
const GERENTE_SUCURSAL = "Principal";
const MAX_CLOSINGS_PER_DAY = 5;
// El plan free de Render "duerme" el servicio tras ~15 min sin tráfico
// entrante y también suspende la base Postgres de Neon tras inactividad.
// Cuando eso pasa, el WebSocket de TODOS los dispositivos conectados se
// corta a la vez y la reconexión + "despertar" el servidor puede tardar
// hasta un minuto, lo que se percibe como pedidos/inventario desfasados
// entre celulares. KEEP_ALIVE_URL es opcional; sin ella el servicio duerme en
// plan free tras 15 min (la app reintenta al usarla).
const KEEP_ALIVE_URL = (process.env.KEEP_ALIVE_URL || "").trim();
const KEEP_ALIVE_INTERVAL_MS = Number(process.env.KEEP_ALIVE_INTERVAL_MS) || 4 * 60 * 1000;
// Roles que el módulo de Usuarios puede crear/editar/eliminar. ADMIN se
// gestiona fuera de esta API (usuario semilla único).
const MANAGEABLE_ROLES = ["CONSULTA", "VENTAS", "SUPERVISOR"];
// Roles con permiso para aprobar, rechazar o revertir cierres de caja
// (Flujo Aprobación).
const CLOSING_REVIEW_ROLES = ["ADMIN", "SUPERVISOR"];
// Roles con permiso para reiniciar (borrar) los pedidos confirmados del día.
const SALES_RESET_ROLES = ["ADMIN", "SUPERVISOR"];
// Portal web: ver listado, detalle, clientes y estados (solo lectura).
const DISCOUNT_VIEW_ROLES = ["CONSULTA", "VENTAS", "SUPERVISOR", "ADMIN"];
// Portal web: generar, anular y administrar códigos (acceso completo).
const DISCOUNT_MANAGE_ROLES = ["ADMIN", "SUPERVISOR"];
// Vigencia fija de 30 días desde la activación del cupón en la app móvil.
const DISCOUNT_TICKET_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const DEFAULT_DISCOUNT_PERCENT = 10;
const PORTAL_DELETE_ACCESS_CODE = process.env.PORTAL_DELETE_ACCESS_CODE || "super";

const app = express();
app.use(express.json({ limit: "12mb" }));
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 15 * 1024 * 1024 } });

let storeRef = null;

const httpServer = http.createServer(app);

function listenHttpServer() {
  return new Promise((resolve, reject) => {
    httpServer.once("error", reject);
    httpServer.listen(PORT, "0.0.0.0", () => {
      httpServer.off("error", reject);
      resolve();
    });
  });
}

app.get("/health", async (_req, res) => {
  if (!storeRef) {
    // 200 (no 503) para que el health check de Render pase mientras Neon/Postgres despierta.
    return res.status(200).json({ ok: false, service: "inventario-sync", starting: true });
  }
  try {
    if (typeof storeRef.ping === "function") {
      await storeRef.ping();
    } else {
      await storeRef.loadState();
    }
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
    portal: "/portal/",
    api: "/v1/state",
    realtime: "/v1/ws",
    auth: "Header X-Api-Key requerido en rutas /v1/* (o JWT Bearer para el portal); Authorization: Bearer <token> en rutas protegidas"
  });
});

// Portal web: se sirve ANTES del chequeo de X-Api-Key para que el navegador
// pueda cargar HTML/CSS/JS sin credenciales de dispositivo.
const portalDir = path.join(__dirname, "public");
const PORTAL_BUILD_VERSION = "19";

app.get("/portal/build.json", (_req, res) => {
  res.setHeader("Cache-Control", "no-store");
  res.json({
    portalVersion: PORTAL_BUILD_VERSION,
    commit: process.env.RENDER_GIT_COMMIT || process.env.GIT_COMMIT || "local",
    deployedAt: process.env.RENDER_GIT_COMMIT ? "render" : "local"
  });
});

app.use("/portal", express.static(portalDir, {
  index: "index.html",
  fallthrough: false,
  setHeaders(res, filePath) {
    if (/\.(html|js|css)$/i.test(filePath)) {
      res.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
      res.setHeader("Pragma", "no-cache");
      res.setHeader("Expires", "0");
    }
  }
}));

function isPublicHttpPath(req) {
  const p = (req.path || "").toLowerCase();
  const url = String(req.originalUrl || req.url || "").split("?")[0].toLowerCase();
  if (p.startsWith("/portal") || url.startsWith("/portal")) return true;
  if (req.method === "POST" && (p === "/v1/auth/login" || url === "/v1/auth/login")) return true;
  return false;
}

app.use((req, res, next) => {
  if (!API_KEY) return next();
  if (isPublicHttpPath(req)) return next();
  // El WebSocket (/v1/ws) manda la clave como query param, no como header;
  // si el upgrade no llega "crudo" (p. ej. reintentos por HTTP/1.1 tras un
  // fallo de negociación, o algún proxy intermedio) la request cae aquí como
  // un GET normal, así que aceptamos ambas formas para no romper el socket.
  const key = req.get("X-Api-Key") || req.query.apiKey;
  if (key === API_KEY) return next();
  // Portal web administrativo: un JWT de sesión válido sustituye la X-Api-Key.
  const header = req.get("Authorization") || "";
  let token = header.startsWith("Bearer ") ? header.slice(7).trim() : null;
  if (!token && req.query.t) token = String(req.query.t).trim();
  if (token) {
    try {
      auth.verifyToken(token);
      return next();
    } catch (_) {
      // Sigue al error de clave inválida.
    }
  }
  return res.status(401).json({ error: "Clave API inválida" });
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

/** Misma normalización que ProductSearch en la app (acentos, mayúsculas). */
function normalizeProductDescription(text) {
  return String(text ?? "")
    .normalize("NFD")
    .replace(/\p{Mn}/gu, "")
    .toLowerCase()
    .replace(/\s+/g, " ")
    .replace(/\s*\+\s*/g, "+")
    .trim();
}

function compactProductDescriptionKey(text) {
  return normalizeProductDescription(text).replace(/\s+/g, "");
}

/**
 * Resuelve una línea de pedido contra el inventario del servidor.
 * Tras reimportar Excel los syncId cambian; si el cliente aún manda el
 * syncId viejo, se intenta emparejar por descripción normalizada.
 */
function resolveProductForOrderLine(products, line) {
  const productSyncId = line?.productSyncId;
  if (productSyncId) {
    const bySyncId = products.find((item) => item.syncId === productSyncId);
    if (bySyncId) return bySyncId;
  }
  const description = String(line?.description ?? "").trim();
  if (!description) return null;
  const normalized = normalizeProductDescription(description);
  const exactMatches = products.filter(
    (item) => normalizeProductDescription(item.description) === normalized
  );
  if (exactMatches.length === 1) return exactMatches[0];
  if (exactMatches.length > 1) {
    return (
      exactMatches.find((item) => item.description.toLowerCase() === description.toLowerCase()) ||
      exactMatches[0]
    );
  }
  const compact = compactProductDescriptionKey(description);
  if (compact) {
    const compactMatches = products.filter(
      (item) => compactProductDescriptionKey(item.description) === compact
    );
    if (compactMatches.length === 1) return compactMatches[0];
    if (compactMatches.length > 1) {
      return (
        compactMatches.find((item) => item.description.toLowerCase() === description.toLowerCase()) ||
        compactMatches[0]
      );
    }
  }
  const looseMatches = products.filter((item) =>
    item.description.toLowerCase() === description.toLowerCase()
  );
  return looseMatches.length === 1 ? looseMatches[0] : null;
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

function generateTicketCode() {
  return crypto.randomUUID().toUpperCase();
}

function generateUniqueTicketCode(existingCodes) {
  const taken = existingCodes instanceof Set ? existingCodes : new Set(existingCodes);
  let code = generateTicketCode();
  let attempts = 0;
  while (taken.has(code)) {
    attempts += 1;
    if (attempts >= 64) {
      throw publicError("No se pudo generar un código QR único. Intenta de nuevo.");
    }
    code = generateTicketCode();
  }
  return code;
}

const DEFAULT_CUSTOMER_PHONE = "00000000000";

function sanitizeCustomerPhone(raw) {
  if (raw == null || raw === "") return DEFAULT_CUSTOMER_PHONE;
  const trimmed = String(raw).trim();
  return trimmed || DEFAULT_CUSTOMER_PHONE;
}

/** Teléfono obligatorio al ejecutar (canjear) un cupón desde la app móvil. */
function sanitizeExecutionPhone(raw) {
  const trimmed = String(raw ?? "").trim();
  if (!trimmed) {
    throw publicError("Debes ingresar un número telefónico válido");
  }
  return trimmed;
}

function normalizeTicketRecord(ticket) {
  if (!ticket) return ticket;
  const normalized = { ...ticket };
  // Cupones legacy: se creaban ACTIVE con vencimiento al emitir.
  if (normalized.status === "ACTIVE" && !normalized.activatedAt && normalized.expiresAt) {
    normalized.activatedAt = normalized.issuedAt;
  }
  if (normalized.status === "PENDING") {
    normalized.status = "ISSUED";
  }
  return normalized;
}

/** Estado persistido: ISSUED | ACTIVE | USED | VOIDED. EXPIRED es derivado. */
function resolveTicketDisplayStatus(ticket, now = Date.now()) {
  const t = normalizeTicketRecord(ticket);
  if (t.status === "VOIDED") return "VOIDED";
  if (t.status === "USED") return "USED";
  if (t.status === "ISSUED") return "ISSUED";
  if (t.status === "ACTIVE") {
    if (t.expiresAt && t.expiresAt <= now) return "EXPIRED";
    return "ACTIVE";
  }
  if (t.expiresAt && t.expiresAt <= now) return "EXPIRED";
  return "ACTIVE";
}

function ticketPublicView(ticket, now = Date.now()) {
  const normalized = normalizeTicketRecord(ticket);
  const enriched = enrichTicket(normalized, now);
  return {
    code: enriched.code,
    discountPercent: enriched.discountPercent,
    issuedAt: enriched.issuedAt,
    activatedAt: enriched.activatedAt || null,
    expiresAt: enriched.expiresAt || null,
    status: enriched.status,
    displayStatus: enriched.displayStatus,
    usedAt: enriched.usedAt || null,
    usedBySaleSyncId: enriched.usedBySaleSyncId || null,
    issuedByUsername: enriched.issuedByUsername || "",
    issuedChannel: enriched.issuedChannel || "PORTAL",
    customerPhone: enriched.customerPhone || null,
    telefono_ejecucion: enriched.telefono_ejecucion || null,
    fecha_ejecucion: enriched.fecha_ejecucion || enriched.usedAt || null,
    auditLog: enriched.auditLog || []
  };
}

function buildLegacyAuditLog(ticket) {
  const events = [];
  events.push({
    action: "CREATED",
    at: ticket.issuedAt,
    by: ticket.issuedByUsername || "",
    details: {
      discountPercent: ticket.discountPercent,
      channel: ticket.issuedChannel || "APP"
    }
  });
  if (ticket.status === "USED" && ticket.usedAt) {
    events.push({
      action: "USED",
      at: ticket.usedAt,
      by: ticket.usedByUsername || "",
      details: { saleSyncId: ticket.usedBySaleSyncId || null }
    });
  }
  if (ticket.activatedAt) {
    events.push({
      action: "ACTIVATED",
      at: ticket.activatedAt,
      by: ticket.activatedByUsername || "",
      details: {}
    });
  }
  if (ticket.status === "VOIDED" && ticket.voidedAt) {
    events.push({
      action: "VOIDED",
      at: ticket.voidedAt,
      by: ticket.voidedByUsername || "",
      details: { reason: ticket.voidReason || "" }
    });
  }
  return events;
}

function enrichTicket(ticket, now = Date.now()) {
  const normalized = normalizeTicketRecord(ticket);
  const auditLog = Array.isArray(normalized.auditLog) && normalized.auditLog.length > 0
    ? normalized.auditLog
    : buildLegacyAuditLog(normalized);
  return {
    ...normalized,
    displayStatus: resolveTicketDisplayStatus(normalized, now),
    auditLog
  };
}

function appendTicketAudit(ticket, action, by, details = {}) {
  const auditLog = Array.isArray(ticket.auditLog) ? [...ticket.auditLog] : buildLegacyAuditLog(ticket);
  auditLog.push({ action, at: Date.now(), by: by || "", details });
  return { ...ticket, auditLog };
}

function filterDiscountTickets(tickets, query, now = Date.now()) {
  let result = tickets.map((t) => enrichTicket(t, now));
  const status = String(query.status || "").trim().toUpperCase();
  if (status) {
    result = result.filter((t) => t.displayStatus === status);
  }
  const codeQuery = String(query.code || query.customer || "").trim().toUpperCase();
  if (codeQuery) {
    result = result.filter((t) => t.code.includes(codeQuery));
  }
  const phoneQuery = String(query.phone || "").replace(/\D/g, "");
  if (phoneQuery) {
    result = result.filter((t) => {
      const customer = String(t.customerPhone || "").replace(/\D/g, "");
      const execution = String(t.telefono_ejecucion || "").replace(/\D/g, "");
      return customer.includes(phoneQuery) || execution.includes(phoneQuery);
    });
  }
  const percent = Number(query.percent);
  if (Number.isFinite(percent) && percent > 0) {
    result = result.filter((t) => t.discountPercent === percent);
  }
  const issuedStart = Number(query.issuedStart);
  const issuedEnd = Number(query.issuedEnd);
  if (Number.isFinite(issuedStart)) {
    result = result.filter((t) => t.issuedAt >= issuedStart);
  }
  if (Number.isFinite(issuedEnd)) {
    result = result.filter((t) => t.issuedAt <= issuedEnd);
  }
  const sort = String(query.sort || "issuedAt").trim();
  const order = String(query.order || "desc").trim().toLowerCase() === "asc" ? 1 : -1;
  result.sort((a, b) => {
    const av = a[sort] ?? 0;
    const bv = b[sort] ?? 0;
    if (av < bv) return -1 * order;
    if (av > bv) return 1 * order;
    return 0;
  });
  return result;
}

function resolveUserSucursal(user) {
  const raw = String(user.sucursal || "").trim();
  if ((user.role === "CONSULTA" || user.role === "VENTAS") && !raw && BRANCH_SUCURSAL) {
    return BRANCH_SUCURSAL;
  }
  return raw;
}

function sanitizeUser(user) {
  return {
    id: user.id,
    username: user.username,
    role: user.role,
    active: user.active,
    sucursal: resolveUserSucursal(user)
  };
}

function discountPortalPermissions(role) {
  const canViewDiscounts = DISCOUNT_VIEW_ROLES.includes(role);
  const canManageDiscounts = DISCOUNT_MANAGE_ROLES.includes(role);
  return {
    canViewDiscounts,
    canManageDiscounts,
    portalMode: canManageDiscounts ? "manage" : canViewDiscounts ? "read" : "none"
  };
}

async function seedDefaultUsers(store) {
  await store.runTransaction(async (state) => {
    if (state.users.length > 0) {
      return { state, result: null };
    }
    const now = state.nextUserId;
    const branchSucursal = BRANCH_SUCURSAL;
    const seeded = [
      {
        id: now,
        username: "consulta",
        passwordHash: auth.hashPassword("consulta"),
        role: "CONSULTA",
        active: true,
        sucursal: branchSucursal
      },
      {
        id: now + 1,
        username: "venta",
        passwordHash: auth.hashPassword("venta"),
        role: "VENTAS",
        active: true,
        sucursal: branchSucursal
      },
      {
        id: now + 2,
        username: "admin",
        passwordHash: auth.hashPassword("admin"),
        role: "ADMIN",
        active: true,
        sucursal: ""
      }
    ];
    return {
      state: { ...state, users: seeded, nextUserId: now + 3 },
      result: null
    };
  });
}

/** Asegura usuarios operativos por sucursal (consulta/venta) en bases ya existentes. */
async function ensureOperationalBranchUser(store, { username, password, role }) {
  const normalizedUsername = String(username).toLowerCase();
  await store.runTransaction(async (state) => {
    const users = state.users.map((u) => ({ ...u }));
    let nextUserId = state.nextUserId;
    let changed = false;
    const index = users.findIndex((u) => String(u.username).toLowerCase() === normalizedUsername);
    const targetSucursal = BRANCH_SUCURSAL;
    if (index >= 0) {
      const current = users[index];
      const updated = {
        ...current,
        username: normalizedUsername,
        role,
        active: true,
        passwordHash: auth.hashPassword(password),
        sucursal: targetSucursal || current.sucursal || ""
      };
      if (
        current.role !== updated.role ||
        current.username !== updated.username ||
        !current.active ||
        current.passwordHash !== updated.passwordHash ||
        (targetSucursal && current.sucursal !== updated.sucursal)
      ) {
        users[index] = updated;
        changed = true;
      }
    } else {
      users.push({
        id: nextUserId,
        username: normalizedUsername,
        passwordHash: auth.hashPassword(password),
        role,
        active: true,
        sucursal: targetSucursal
      });
      nextUserId += 1;
      changed = true;
    }
    if (!changed) return { state, result: null };
    return { state: { ...state, users, nextUserId }, result: null };
  });
}

async function ensureConsultaBranchUser(store) {
  return ensureOperationalBranchUser(store, {
    username: "consulta",
    password: "consulta",
    role: "CONSULTA"
  });
}

/** Asegura el usuario portal de ventas (venta/venta) con rol VENTAS en bases ya existentes. */
async function ensureVentasPortalUser(store) {
  return ensureOperationalBranchUser(store, {
    username: "venta",
    password: "venta",
    role: "VENTAS"
  });
}

/** Asegura el usuario gerente/gerente con rol SUPERVISOR (permisos de supervisión
 *  completos en app y portal). Se recrea en cada arranque si falta o quedó
 *  desactivado — importante en backend file efímero de Render free. */
async function ensureGerenteSupervisorUser(store) {
  const GERENTE_USERNAME = "gerente";
  const GERENTE_PASSWORD = "gerente";
  await store.runTransaction(async (state) => {
    const users = state.users.map((u) => ({ ...u }));
    let nextUserId = state.nextUserId;
    let changed = false;
    const index = users.findIndex((u) => String(u.username).toLowerCase() === GERENTE_USERNAME);
    if (index >= 0) {
      const current = users[index];
      const updated = {
        ...current,
        username: GERENTE_USERNAME,
        role: "SUPERVISOR",
        active: true,
        passwordHash: auth.hashPassword(GERENTE_PASSWORD),
        sucursal: GERENTE_SUCURSAL
      };
      if (
        current.role !== updated.role ||
        current.username !== updated.username ||
        !current.active ||
        current.passwordHash !== updated.passwordHash ||
        current.sucursal !== updated.sucursal
      ) {
        users[index] = updated;
        changed = true;
      }
    } else {
      users.push({
        id: nextUserId,
        username: GERENTE_USERNAME,
        passwordHash: auth.hashPassword(GERENTE_PASSWORD),
        role: "SUPERVISOR",
        active: true,
        sucursal: GERENTE_SUCURSAL
      });
      nextUserId += 1;
      changed = true;
    }
    if (!changed) return { state, result: null };
    return { state: { ...state, users, nextUserId }, result: null };
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
    let seedData;
    try {
      seedData = require("./data/battery-finder.json");
    } catch (error) {
      console.warn(
        "No se pudo cargar data/battery-finder.json; se omite la siembra del catálogo:",
        error.message
      );
      return { state, result: null };
    }
    return {
      state: { ...state, batteryFinder: seedData },
      result: null
    };
  });
}

/**
 * Ping periódico opcional a la propia URL pública. En plan free de Render solo
 * debe activarse si aceptas consumir horas de instancia; con dos sucursales el
 * keep-alive 24/7 agota el cupo mensual (~750 h) en ~16 días.
 */
function startKeepAlive() {
  if (!KEEP_ALIVE_URL) {
    console.log(
      "Keep-alive deshabilitado (plan free: el servicio duerme tras 15 min sin " +
        "tráfico; la app Android reintenta /health al usarla)."
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
  realtime.attach(httpServer, API_KEY);
  await listenHttpServer();
  console.log(`Inventario sync server listening on http://0.0.0.0:${PORT} (inicializando almacenamiento…)`);

  const store = await createStore();
  storeRef = store;
  push.init();
  await seedDefaultUsers(store);
  await ensureConsultaBranchUser(store);
  await ensureVentasPortalUser(store);
  await ensureGerenteSupervisorUser(store);
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
    res.json({
      token,
      user: sanitizeUser(user),
      ...discountPortalPermissions(user.role)
    });
  }));

  app.get("/v1/auth/me", auth.requireAuth(), asyncRoute(async (req, res) => {
    const state = await store.loadState();
    const user = state.users.find((u) => String(u.id) === String(req.user.sub));
    if (!user || !user.active) {
      return res.status(401).json({ error: "Sesión inválida" });
    }
    res.json({
      user: sanitizeUser(user),
      ...discountPortalPermissions(user.role)
    });
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
      await store.runTransaction(async (state) => {
        const byDescription = new Map();
        for (const existing of state.products) {
          const key = normalizeProductDescription(existing.description);
          if (key && !byDescription.has(key)) {
            byDescription.set(key, existing);
          }
        }
        const nextProducts = parsed.products.map((p) => {
          const key = normalizeProductDescription(p.description);
          const previous = key ? byDescription.get(key) : null;
          return {
            syncId: previous?.syncId ?? require("crypto").randomUUID(),
            description: p.description,
            quantity: p.quantity,
            unit: p.unit,
            price: p.price,
            updatedAt: now
          };
        });
        const revision = now;
        const nextState = {
          ...state,
          inventoryRevision: revision,
          products: nextProducts,
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
      const discountTicketCode = body.discountTicketCode
        ? String(body.discountTicketCode).trim().toUpperCase()
        : null;
      const discountUsd = Number(body.discountUsd) || 0;

      if (!syncId || !Number.isFinite(createdAt) || !Number.isFinite(totalUsd) || lines.length === 0) {
        throw publicError("Pedido inválido");
      }

      let discountTicketApplied = false;

      await store.runTransaction(async (state) => {
        if (state.sales.some((item) => item.syncId === syncId)) {
          // Ya se procesó este pedido (reintento tras un corte de red);
          // no volver a descontar stock ni duplicar la venta.
          return { state, result: null };
        }

        // Validación + canje del ticket de descuento en la MISMA transacción
        // que la venta: si el ticket es inválido, toda la venta se rechaza; si
        // la venta falla más abajo (p. ej. stock insuficiente), el ticket no
        // queda marcado como usado. Así quedan atómicos.
        let discountTickets = state.discountTickets;
        let appliedDiscountPercent = 0;
        if (discountTicketCode) {
          const ticketIndex = state.discountTickets.findIndex((t) => t.code === discountTicketCode);
          if (ticketIndex === -1) {
            throw publicError("Ticket de descuento no encontrado");
          }
          const ticket = normalizeTicketRecord(state.discountTickets[ticketIndex]);
          if (ticket.status === "VOIDED") {
            throw publicError("Este cupón fue anulado");
          }
          if (ticket.status === "ISSUED") {
            throw publicError("Este cupón aún no está activado. Escanéalo primero en «Activar cupón».");
          }
          if (ticket.status === "ACTIVE") {
            throw publicError(
              "Este cupón debe ejecutarse con teléfono antes de confirmar la venta. " +
              "Escanea el código en el carrito e ingresa el número telefónico."
            );
          }
          if (ticket.status !== "USED") {
            throw publicError("Este cupón no puede aplicarse a esta venta");
          }
          if (ticket.usedBySaleSyncId && ticket.usedBySaleSyncId !== syncId) {
            throw publicError("Este cupón ya fue utilizado en otra venta");
          }
          if (!ticket.expiresAt || ticket.expiresAt <= createdAt) {
            throw publicError("Este cupón está expirado");
          }
          appliedDiscountPercent = Number(ticket.discountPercent) || 0;
          let usedTicket = ticket;
          if (!ticket.usedBySaleSyncId) {
            usedTicket = appendTicketAudit(
              {
                ...ticket,
                usedBySaleSyncId: syncId,
                usedByUsername: req.user?.username || ticket.usedByUsername || ""
              },
              "SALE_LINKED",
              req.user?.username || "",
              { saleSyncId: syncId }
            );
            discountTickets = state.discountTickets.map((t, i) =>
              i === ticketIndex ? usedTicket : t
            );
          }
          discountTicketApplied = true;
        }

        const products = state.products.map((p) => ({ ...p }));
        const productBySyncId = new Map(products.map((p) => [p.syncId, p]));
        for (const line of lines) {
          const quantity = Number(line?.quantity);
          if (!Number.isFinite(quantity) || quantity <= 0) {
            throw publicError("Línea de pedido inválida");
          }
          const product = resolveProductForOrderLine(products, line);
          if (!product) {
            throw publicError(`Producto no encontrado: ${line.description || line.productSyncId}`);
          }
          productBySyncId.set(product.syncId, product);
          const newQty = Number(product.quantity) - quantity;
          if (newQty < 0) {
            throw publicError(`Stock insuficiente para "${product.description}"`);
          }
          product.quantity = newQty;
          product.updatedAt = createdAt;
        }

        const resolvedDiscountUsd = Math.max(0, Number(discountUsd) || 0);
        const resolvedSubtotalUsd = Number(body.subtotalUsd);
        const subtotalUsd = Number.isFinite(resolvedSubtotalUsd) && resolvedSubtotalUsd > 0
          ? resolvedSubtotalUsd
          : totalUsd + resolvedDiscountUsd;
        const sales = [
          ...state.sales,
          {
            syncId,
            createdAt,
            totalUsd,
            subtotalUsd,
            bcvRate,
            discountTicketCode: discountTicketCode || null,
            discountPercent: appliedDiscountPercent,
            discountUsd: resolvedDiscountUsd
          }
        ];
        let nextId = state.nextSaleLineItemId;
        // El detalle de cada línea (descripción, cantidad, precio) NUNCA debe
        // quedar vacío en el pedido guardado: si el cliente no lo manda o lo
        // manda en blanco, se completa desde el registro real de inventario
        // (ya resuelto arriba en productBySyncId) en vez de persistir un
        // hueco que luego se muestra como "Sin detalle de productos." en
        // cualquier dispositivo que consulte este pedido.
        const newLineItems = lines.map((line) => {
          const product = resolveProductForOrderLine(products, line);
          const quantity = Number(line.quantity) || 0;
          const unitPriceUsd = Number(line.unitPriceUsd) || product?.price || 0;
          const description = String(line.description || "").trim() || product?.description || "";
          const unit = String(line.unit || "").trim() || product?.unit || "UNIDAD";
          const totalUsd = Number(line.totalUsd) || unitPriceUsd * quantity;
          return {
            id: nextId++,
            saleSyncId: syncId,
            productSyncId: product?.syncId || line.productSyncId || "",
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
            meta: { ...state.meta, lastSalesUpdateAt: createdAt },
            sales,
            saleLineItems: [...state.saleLineItems, ...newLineItems],
            nextSaleLineItemId: nextId,
            discountTickets
          },
          result: null
        };
      });

      realtime.broadcast("inventory", {});
      realtime.broadcast("sales", {});
      push.sendSalesUpdatedNotification();
      if (discountTicketApplied) {
        realtime.broadcast("discountTickets", {});
      }
      res.json({ ok: true });
    })
  );

  // ---------- Ventas ----------
  // start/end (epoch ms) son opcionales: si se envían, filtra en el servidor
  // en vez de mandar todo el historial de ventas al cliente en cada
  // consulta (p. ej. el total de "hoy"), lo que ahorra datos móviles y
  // ancho de banda del plan free de Render a medida que crece el historial.
  app.get("/v1/sales", auth.requireAuth(), asyncRoute(async (req, res) => {
    // Solo lee la colección de ventas (no inventario/usuarios/cierres) para
    // que la previsualización de pedidos del día responda más rápido.
    const state = typeof store.loadSales === "function"
      ? await store.loadSales()
      : await store.loadState();
    const sales = filterByRange(state.sales, "createdAt", req.query);
    const saleSyncIds = new Set(sales.map((s) => s.syncId));
    const lineItems = state.saleLineItems.filter((l) => saleSyncIds.has(l.saleSyncId));
    res.json({
      sales,
      lineItems,
      meta: {
        lastSalesUpdateAt: state.meta?.lastSalesUpdateAt ?? null,
        count: sales.length
      }
    });
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
            meta: { ...state.meta, lastSalesUpdateAt: createdAt },
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
    auth.requireAuth(SALES_RESET_ROLES),
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

  // ---------- Cupones de descuento (sin datos personales) ----------
  app.get(
    "/v1/discount-tickets",
    auth.requireAuth(),
    asyncRoute(async (req, res) => {
      const code = String(req.query.code || "").trim().toUpperCase();
      const listAll = req.query.list === "1" || req.query.list === "true";
      const state = await store.loadState();
      const now = Date.now();

      if (code) {
        const ticket = state.discountTickets.find((t) => t.code === code) || null;
        return res.json({ ticket: ticket ? ticketPublicView(ticket, now) : null });
      }
      if (listAll) {
        if (!DISCOUNT_VIEW_ROLES.includes(req.user?.role)) {
          return res.status(403).json({ error: "No tienes permisos para listar cupones de descuento." });
        }
        const tickets = filterDiscountTickets(state.discountTickets, req.query, now)
          .map((t) => ticketPublicView(t, now));
        const stats = {
          total: tickets.length,
          issued: tickets.filter((t) => t.displayStatus === "ISSUED").length,
          active: tickets.filter((t) => t.displayStatus === "ACTIVE").length,
          used: tickets.filter((t) => t.displayStatus === "USED").length,
          expired: tickets.filter((t) => t.displayStatus === "EXPIRED").length,
          voided: tickets.filter((t) => t.displayStatus === "VOIDED").length
        };
        return res.json({ tickets, stats });
      }
      throw publicError("Debes indicar code o list=1");
    })
  );

  app.get(
    "/v1/discount-tickets/:code",
    auth.requireAuth(DISCOUNT_VIEW_ROLES),
    asyncRoute(async (req, res) => {
      const code = String(req.params.code || "").trim().toUpperCase();
      const state = await store.loadState();
      const ticket = state.discountTickets.find((t) => t.code === code);
      if (!ticket) throw publicError("Código no encontrado", 404);
      res.json({ ticket: ticketPublicView(ticket) });
    })
  );

  app.get(
    "/v1/discount-tickets/:code/qr",
    auth.requireAuth(DISCOUNT_VIEW_ROLES),
    asyncRoute(async (req, res) => {
      const code = String(req.params.code || "").trim().toUpperCase();
      const state = await store.loadState();
      const ticket = state.discountTickets.find((t) => t.code === code);
      if (!ticket) throw publicError("Código no encontrado", 404);
      const png = await QRCode.toBuffer(ticket.code, {
        type: "png",
        width: 300,
        margin: 1,
        errorCorrectionLevel: "M"
      });
      res.set({
        "Content-Type": "image/png",
        "Cache-Control": "private, max-age=3600"
      });
      res.send(png);
    })
  );

  app.post(
    "/v1/discount-tickets",
    auth.requireAuth(DISCOUNT_MANAGE_ROLES),
    asyncRoute(async (req, res) => {
      const sourceSaleSyncId = req.body?.sourceSaleSyncId ? String(req.body.sourceSaleSyncId) : null;
      const channel = String(req.body?.channel || "PORTAL").trim().toUpperCase();
      const requestedPercent = Number(req.body?.discountPercent);
      const customerPhone = sanitizeCustomerPhone(req.body?.customerPhone);
      const created = await store.runTransaction(async (state) => {
        const now = Date.now();
        const defaultPercent = Number(state.meta.discountPercent) > 0
          ? Number(state.meta.discountPercent)
          : DEFAULT_DISCOUNT_PERCENT;
        const percent = Number.isFinite(requestedPercent) && requestedPercent > 0 && requestedPercent <= 100
          ? requestedPercent
          : defaultPercent;
        const existingCodes = new Set(state.discountTickets.map((t) => t.code));
        const code = generateUniqueTicketCode(existingCodes);
        let ticket = {
          id: state.nextDiscountTicketId,
          code,
          discountPercent: percent,
          issuedAt: now,
          activatedAt: null,
          expiresAt: null,
          status: "ISSUED",
          usedAt: null,
          usedBySaleSyncId: null,
          usedByUsername: null,
          issuedByUsername: req.user?.username || "",
          issuedChannel: channel === "APP" ? "APP" : "PORTAL",
          customerPhone,
          sourceSaleSyncId,
          voidedAt: null,
          voidedByUsername: null,
          voidReason: null,
          auditLog: []
        };
        ticket = appendTicketAudit(ticket, "CREATED", req.user?.username || "", {
          discountPercent: percent,
          channel: ticket.issuedChannel,
          customerPhone
        });
        return {
          state: {
            ...state,
            discountTickets: [...state.discountTickets, ticket],
            nextDiscountTicketId: state.nextDiscountTicketId + 1
          },
          result: ticket
        };
      });

      realtime.broadcast("discountTickets", {});
      res.json(ticketPublicView(created));
    })
  );

  app.patch(
    "/v1/discount-tickets/:code/phone",
    auth.requireAuth(DISCOUNT_MANAGE_ROLES),
    asyncRoute(async (req, res) => {
      const code = String(req.params.code || "").trim().toUpperCase();
      const customerPhone = sanitizeCustomerPhone(req.body?.customerPhone);
      const updated = await store.runTransaction(async (state) => {
        const index = state.discountTickets.findIndex((t) => t.code.toUpperCase() === code);
        if (index === -1) throw publicError("Código no encontrado", 404);
        const ticket = state.discountTickets[index];
        if (ticket.status === "USED" || ticket.status === "VOIDED") {
          throw publicError("No se puede editar el teléfono de un cupón usado o anulado");
        }
        let next = { ...ticket, customerPhone };
        next = appendTicketAudit(next, "PHONE_UPDATED", req.user?.username || "", {
          customerPhone
        });
        const discountTickets = state.discountTickets.map((t, i) => (i === index ? next : t));
        return { state: { ...state, discountTickets }, result: next };
      });
      realtime.broadcast("discountTickets", {});
      res.json(ticketPublicView(updated));
    })
  );

  app.patch(
    "/v1/discount-tickets/:code/activate",
    auth.requireAuth(),
    asyncRoute(async (req, res) => {
      const code = String(req.params.code || "").trim().toUpperCase();
      const updated = await store.runTransaction(async (state) => {
        const index = state.discountTickets.findIndex((t) => t.code === code);
        if (index === -1) throw publicError("Cupón no encontrado", 404);
        const ticket = normalizeTicketRecord(state.discountTickets[index]);
        if (ticket.status === "VOIDED") {
          throw publicError("Este cupón fue anulado");
        }
        if (ticket.status === "USED") {
          throw publicError("Este cupón ya fue utilizado");
        }
        if (ticket.status === "ACTIVE") {
          throw publicError("Este cupón ya está activo");
        }
        if (ticket.status !== "ISSUED") {
          throw publicError("Este cupón no puede activarse");
        }
        const now = Date.now();
        let activated = {
          ...ticket,
          status: "ACTIVE",
          activatedAt: now,
          activatedByUsername: req.user?.username || "",
          expiresAt: now + DISCOUNT_TICKET_TTL_MS
        };
        activated = appendTicketAudit(
          activated,
          "ACTIVATED",
          req.user?.username || "",
          { expiresAt: activated.expiresAt }
        );
        const discountTickets = state.discountTickets.map((t, i) => (i === index ? activated : t));
        return { state: { ...state, discountTickets }, result: activated };
      });
      realtime.broadcast("discountTickets", {});
      res.json(ticketPublicView(updated));
    })
  );

  app.patch(
    "/v1/discount-tickets/:code/execute",
    auth.requireAuth(),
    asyncRoute(async (req, res) => {
      const code = String(req.params.code || "").trim().toUpperCase();
      const telefono_ejecucion = sanitizeExecutionPhone(
        req.body?.telefono_ejecucion ?? req.body?.executionPhone
      );
      const updated = await store.runTransaction(async (state) => {
        const index = state.discountTickets.findIndex((t) => t.code === code);
        if (index === -1) throw publicError("Cupón no encontrado", 404);
        const ticket = normalizeTicketRecord(state.discountTickets[index]);
        if (ticket.status === "VOIDED") {
          throw publicError("Este cupón fue anulado");
        }
        if (ticket.status === "ISSUED") {
          throw publicError(
            "Este cupón aún no está activado. Escanéalo primero en «Activar cupón»."
          );
        }
        if (ticket.status === "USED") {
          if (!ticket.usedBySaleSyncId) {
            return { state, result: ticket };
          }
          throw publicError("Este cupón ya fue utilizado");
        }
        if (ticket.status !== "ACTIVE") {
          throw publicError("Este cupón no puede ejecutarse");
        }
        const now = Date.now();
        if (!ticket.expiresAt || ticket.expiresAt <= now) {
          throw publicError("Este cupón está expirado");
        }
        let executed = {
          ...ticket,
          status: "USED",
          telefono_ejecucion,
          fecha_ejecucion: now,
          usedAt: now,
          usedByUsername: req.user?.username || "",
          usedBySaleSyncId: null
        };
        executed = appendTicketAudit(
          executed,
          "EXECUTED",
          req.user?.username || "",
          { telefono_ejecucion, fecha_ejecucion: now }
        );
        const discountTickets = state.discountTickets.map((t, i) => (i === index ? executed : t));
        return { state: { ...state, discountTickets }, result: executed };
      });
      realtime.broadcast("discountTickets", {});
      res.json(ticketPublicView(updated));
    })
  );

  app.patch(
    "/v1/discount-tickets/:code/void",
    auth.requireAuth(DISCOUNT_MANAGE_ROLES),
    asyncRoute(async (req, res) => {
      const code = String(req.params.code || "").trim().toUpperCase();
      const reason = String(req.body?.reason || "").trim();
      const updated = await store.runTransaction(async (state) => {
        const index = state.discountTickets.findIndex((t) => t.code === code);
        if (index === -1) throw publicError("Código no encontrado", 404);
        const ticket = state.discountTickets[index];
        if (ticket.status === "USED") {
          throw publicError("No se puede anular un cupón ya utilizado");
        }
        if (ticket.status === "VOIDED") {
          throw publicError("Este cupón ya está anulado");
        }
        const now = Date.now();
        let voided = {
          ...ticket,
          status: "VOIDED",
          voidedAt: now,
          voidedByUsername: req.user?.username || "",
          voidReason: reason
        };
        voided = appendTicketAudit(voided, "VOIDED", req.user?.username || "", { reason });
        const discountTickets = state.discountTickets.map((t, i) => (i === index ? voided : t));
        return { state: { ...state, discountTickets }, result: voided };
      });
      realtime.broadcast("discountTickets", {});
      res.json({ ticket: ticketPublicView(updated) });
    })
  );

  app.delete(
    "/v1/discount-tickets/:code",
    auth.requireAuth(DISCOUNT_MANAGE_ROLES),
    asyncRoute(async (req, res) => {
      const code = String(req.params.code || "").trim().toUpperCase();
      const accessCode = String(req.body?.accessCode || "").trim();
      if (accessCode !== PORTAL_DELETE_ACCESS_CODE) {
        throw publicError("Código de acceso incorrecto", 403);
      }
      const deleted = await store.runTransaction(async (state) => {
        const index = state.discountTickets.findIndex((t) => t.code === code);
        if (index === -1) throw publicError("Código no encontrado", 404);
        const ticket = state.discountTickets[index];
        if (ticket.status !== "VOIDED") {
          throw publicError("El cupón debe estar anulado antes de eliminarlo");
        }
        const discountTickets = state.discountTickets.filter((t) => t.code !== code);
        return { state: { ...state, discountTickets }, result: { code } };
      });
      realtime.broadcast("discountTickets", {});
      res.json({ ok: true, code: deleted.code });
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

  console.log(`Storage backend: ${store.backend}`);
  if (store.dataPath) {
    console.log(`Data file: ${store.dataPath}`);
  }
  console.log(`API key configured: ${API_KEY ? "yes" : "no"}`);
  console.log(`WebSocket endpoint: /v1/ws`);
  console.log(`Discount portal: http://0.0.0.0:${PORT}/portal/`);
  startKeepAlive();
}

start().catch((error) => {
  console.error("Failed to start server", error);
  process.exit(1);
});
