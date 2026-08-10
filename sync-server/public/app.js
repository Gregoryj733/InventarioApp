(function () {
  const TOKEN_KEY = "inventario_portal_token";
  const USER_KEY = "inventario_portal_user";
  const QR_TICKET_VALIDITY_TEXT = "Cupón válido por 30 días desde activación";
  const QR_TICKET_TITLE = "Total Care · Cupón de descuento";
  const DEFAULT_CUSTOMER_PHONE = "00000000000";
  const PORTAL_UI_VERSION = "15";
  const PAGE_SIZE_OPTIONS = [10, 20, 30, 40, 50];

  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => document.querySelectorAll(sel);

  const state = {
    token: localStorage.getItem(TOKEN_KEY),
    user: JSON.parse(localStorage.getItem(USER_KEY) || "null"),
    canViewDiscounts: false,
    canManageDiscounts: false,
    portalMode: "none",
    tickets: [],
    stats: null,
    page: 1,
    pageSize: 10,
    selected: null,
    lastGenerated: null,
    pendingDeleteCode: null,
    loading: false,
    ws: null
  };

  let refreshTimer = null;

  function api(path, options = {}) {
    const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
    if (state.token) headers.Authorization = `Bearer ${state.token}`;
    return fetch(path, { ...options, headers }).then(async (res) => {
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || `Error ${res.status}`);
      return data;
    });
  }

  function formatDate(ms) {
    if (!ms) return "—";
    return new Date(ms).toLocaleString("es-VE", {
      dateStyle: "short",
      timeStyle: "short"
    });
  }

  function statusLabel(s) {
    return {
      ISSUED: "Sin activar",
      ACTIVE: "Activo",
      USED: "Usado",
      EXPIRED: "Expirado",
      VOIDED: "Anulado"
    }[s] || s;
  }

  function badgeClass(s) {
    return {
      ISSUED: "badge-issued",
      ACTIVE: "badge-active",
      USED: "badge-used",
      EXPIRED: "badge-expired",
      VOIDED: "badge-voided"
    }[s] || "badge-used";
  }

  function saveSession(token, user, permissions = {}) {
    state.token = token;
    state.user = user;
    state.canViewDiscounts = permissions.canViewDiscounts === true;
    state.canManageDiscounts = permissions.canManageDiscounts === true;
    state.portalMode = permissions.portalMode || (state.canManageDiscounts ? "manage" : "read");
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  }

  function clearSession() {
    disconnectRealtime();
    state.token = null;
    state.user = null;
    state.canViewDiscounts = false;
    state.canManageDiscounts = false;
    state.portalMode = "none";
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  }

  function canManagePortal() {
    return state.canManageDiscounts === true;
  }

  function applyUiPermissions() {
    const manage = canManagePortal();
    $("#generate-panel")?.classList.toggle("hidden", !manage);
    const modeEl = $("#access-mode");
    if (modeEl) {
      modeEl.textContent = manage ? "" : "Modo consulta — solo lectura";
      modeEl.classList.toggle("hidden", manage);
    }
    const listTitle = $("#list-panel-title");
    if (listTitle) {
      listTitle.textContent = manage ? "Gestión y seguimiento" : "Consulta y seguimiento";
    }
  }

  function setLiveStatus(live) {
    const el = $("#live-status");
    if (!el) return;
    el.textContent = live ? "En vivo" : "Reconectando…";
    el.className = live ? "live-dot live-on" : "live-dot live-off";
  }

  function disconnectRealtime() {
    if (state.ws) {
      state.ws.onclose = null;
      state.ws.close();
      state.ws = null;
    }
    setLiveStatus(false);
  }

  function scheduleRefresh() {
    if (refreshTimer) return;
    refreshTimer = setTimeout(async () => {
      refreshTimer = null;
      if (!state.token || $("#main-view").classList.contains("hidden")) return;
      await loadTickets({ silent: true });
    }, 300);
  }

  function connectRealtime() {
    disconnectRealtime();
    if (!state.token) return;
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    const ws = new WebSocket(`${proto}//${location.host}/v1/ws?token=${encodeURIComponent(state.token)}`);
    state.ws = ws;
    ws.onopen = () => setLiveStatus(true);
    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data);
        if (msg.type === "discountTickets") scheduleRefresh();
      } catch (_) {}
    };
    ws.onclose = () => {
      setLiveStatus(false);
      if (state.token) setTimeout(connectRealtime, 5000);
    };
  }

  function showLogin(msg) {
    $("#login-view").classList.remove("hidden");
    $("#main-view").classList.add("hidden");
    if (msg) {
      const el = $("#login-error");
      el.textContent = msg;
      el.classList.remove("hidden");
    }
  }

  function showMain() {
    $("#login-view").classList.add("hidden");
    $("#main-view").classList.remove("hidden");
    $("#user-label").textContent = `${state.user.username} (${roleLabel(state.user.role)})`;
    applyUiPermissions();
    updateGenerateButtonState();
    loadPortalBuildInfo();
  }

  async function loadPortalBuildInfo() {
    const el = $("#portal-build");
    if (!el) return;
    try {
      const data = await fetch("/portal/build.json", { cache: "no-store" }).then((r) => r.json());
      const commit = data.commit ? String(data.commit).slice(0, 7) : "?";
      el.textContent = `Portal v${data.portalVersion || PORTAL_UI_VERSION} · ${commit}`;
    } catch (_) {
      el.textContent = `Portal v${PORTAL_UI_VERSION}`;
    }
  }

  function roleLabel(role) {
    return {
      ADMIN: "Admin",
      SUPERVISOR: "Supervisor",
      CONSULTA: "Consulta",
      VENTAS: "Ventas"
    }[role] || role;
  }

  const PORTAL_VIEW_ROLES = ["CONSULTA", "VENTAS", "SUPERVISOR", "ADMIN"];
  const PORTAL_MANAGE_ROLES = ["SUPERVISOR", "ADMIN"];

  function resolvePortalPermissions(data) {
    const role = String(data.user?.role || "").toUpperCase();
    if (typeof data.canViewDiscounts === "boolean") {
      return {
        canViewDiscounts: data.canViewDiscounts,
        canManageDiscounts: data.canManageDiscounts === true,
        portalMode: data.portalMode || (data.canManageDiscounts ? "manage" : "read")
      };
    }
    const canViewDiscounts = PORTAL_VIEW_ROLES.includes(role);
    const canManageDiscounts = PORTAL_MANAGE_ROLES.includes(role);
    return {
      canViewDiscounts,
      canManageDiscounts,
      portalMode: canManageDiscounts ? "manage" : canViewDiscounts ? "read" : "none"
    };
  }

  function applyAuthPayload(data) {
    const permissions = resolvePortalPermissions(data);
    if (!permissions.canViewDiscounts) {
      throw new Error("Tu perfil no tiene acceso al portal de cupones.");
    }
    saveSession(state.token, data.user, permissions);
  }

  async function verifySession() {
    if (!state.token) return showLogin();
    try {
      const data = await api("/v1/auth/me");
      applyAuthPayload(data);
      showMain();
      connectRealtime();
      await loadTickets();
    } catch (err) {
      clearSession();
      showLogin(err.message || "Sesión expirada. Inicia sesión de nuevo.");
    }
  }

  async function login(ev) {
    ev.preventDefault();
    $("#login-error").classList.add("hidden");
    const username = $("#login-user").value.trim();
    const password = $("#login-pass").value;
    const btn = $("#login-btn");
    btn.disabled = true;
    try {
      const data = await api("/v1/auth/login", {
        method: "POST",
        body: JSON.stringify({ username: username.toLowerCase(), password })
      });
      state.token = data.token;
      applyAuthPayload(data);
      showMain();
      connectRealtime();
      await loadTickets();
    } catch (err) {
      $("#login-error").textContent = err.message || "No se pudo iniciar sesión.";
      $("#login-error").classList.remove("hidden");
    } finally {
      btn.disabled = false;
    }
  }

  function buildQuery() {
    const params = new URLSearchParams({ list: "1" });
    const status = $("#filter-status").value;
    const code = $("#filter-code").value.trim();
    const phone = $("#filter-phone").value.trim();
    const percent = $("#filter-percent").value;
    const issuedStart = $("#filter-start").value;
    const issuedEnd = $("#filter-end").value;
    if (status) params.set("status", status);
    if (code) params.set("code", code);
    if (phone) params.set("phone", phone);
    if (percent) params.set("percent", percent);
    if (issuedStart) params.set("issuedStart", String(new Date(issuedStart).getTime()));
    if (issuedEnd) {
      const end = new Date(issuedEnd);
      end.setHours(23, 59, 59, 999);
      params.set("issuedEnd", String(end.getTime()));
    }
    return params.toString();
  }

  async function loadTickets(opts = {}) {
    const silent = opts.silent === true;
    if (!silent) state.loading = true;
    if (!silent) $("#refresh-btn").disabled = true;
    try {
      const data = await api(`/v1/discount-tickets?${buildQuery()}`);
      state.tickets = data.tickets || [];
      state.stats = data.stats || null;
      normalizePagination();
      renderStats();
      renderTable();
      renderPagination();
    } catch (err) {
      $("#table-body").innerHTML = `<tr><td colspan="8" class="empty">${err.message}</td></tr>`;
      renderPagination();
    } finally {
      if (!silent) state.loading = false;
      if (!silent) $("#refresh-btn").disabled = false;
      if (state.selected?.code) {
        const updated = state.tickets.find((t) => t.code === state.selected.code);
        if (updated) openDetail(updated);
      }
    }
  }

  function renderStats() {
    const s = state.stats;
    if (!s) return;
    $("#stat-total").textContent = s.total;
    $("#stat-issued").textContent = s.issued ?? 0;
    $("#stat-active").textContent = s.active;
    $("#stat-used").textContent = s.used;
    $("#stat-expired").textContent = s.expired;
    $("#stat-voided").textContent = s.voided;
  }

  function normalizePagination() {
    const total = state.tickets.length;
    const totalPages = Math.max(1, Math.ceil(total / state.pageSize) || 1);
    if (state.page > totalPages) state.page = totalPages;
    if (state.page < 1) state.page = 1;
  }

  function getPaginatedTickets() {
    const start = (state.page - 1) * state.pageSize;
    return state.tickets.slice(start, start + state.pageSize);
  }

  function renderPagination() {
    const bar = $("#pagination-bar");
    if (!bar) return;
    const total = state.tickets.length;
    const totalPages = Math.max(1, Math.ceil(total / state.pageSize) || 1);
    const start = total === 0 ? 0 : (state.page - 1) * state.pageSize + 1;
    const end = total === 0 ? 0 : Math.min(state.page * state.pageSize, total);

    $("#page-info").textContent = total === 0
      ? "Sin registros"
      : `Mostrando ${start}–${end} de ${total} · Página ${state.page} de ${totalPages}`;

    const prev = $("#page-prev");
    const next = $("#page-next");
    if (prev) prev.disabled = state.page <= 1 || total === 0;
    if (next) next.disabled = state.page >= totalPages || total === 0;

    const sizeSelect = $("#page-size");
    if (sizeSelect && String(sizeSelect.value) !== String(state.pageSize)) {
      sizeSelect.value = String(state.pageSize);
    }
  }

  function renderTable() {
    const tbody = $("#table-body");
    if (!state.tickets.length) {
      tbody.innerHTML = '<tr><td colspan="8" class="empty">No hay cupones que coincidan con los filtros.</td></tr>';
      renderPagination();
      return;
    }
    const pageTickets = getPaginatedTickets();
    tbody.innerHTML = pageTickets.map((t) => `
      <tr>
        <td class="code-cell">${t.code}</td>
        <td>${escapeHtml(formatCustomerPhone(t.customerPhone))}</td>
        <td>${t.discountPercent}%</td>
        <td>${formatDate(t.issuedAt)}</td>
        <td>${formatDate(t.activatedAt)}</td>
        <td>${formatDate(t.expiresAt)}</td>
        <td><span class="badge ${badgeClass(t.displayStatus)}">${statusLabel(t.displayStatus)}</span></td>
        <td>
          <button class="btn btn-ghost btn-sm" data-action="detail" data-code="${t.code}">Ver</button>
          ${canManagePortal() && (t.displayStatus === "ISSUED" || t.displayStatus === "ACTIVE") ? `<button class="btn btn-ghost btn-sm" data-action="print-qr" data-code="${t.code}">QR</button>` : ""}
          ${canManagePortal() && (t.displayStatus === "ISSUED" || t.displayStatus === "ACTIVE") ? `<button class="btn btn-danger btn-sm" data-action="void" data-code="${t.code}">Anular</button>` : ""}
          ${canManagePortal() && t.displayStatus === "VOIDED" ? `<button class="btn btn-danger btn-sm" data-action="delete" data-code="${t.code}">Eliminar</button>` : ""}
        </td>
      </tr>
    `).join("");
    renderPagination();
  }

  function escapeHtml(str) {
    return String(str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function formatCustomerPhone(phone) {
    if (!phone || phone === DEFAULT_CUSTOMER_PHONE) return "—";
    return phone;
  }

  function updateGenerateButtonState() {
    const btn = $("#generate-btn");
    const confirm = $("#gen-confirm");
    if (!btn || !confirm) return;
    btn.disabled = !confirm.checked;
  }

  async function generateCode(ev) {
    ev.preventDefault();
    $("#generate-error").classList.add("hidden");
    $("#generate-success").classList.add("hidden");
    if (!$("#gen-confirm")?.checked) {
      $("#generate-error").textContent = "Debes confirmar la generación del QR antes de continuar.";
      $("#generate-error").classList.remove("hidden");
      return;
    }
    const btn = $("#generate-btn");
    btn.disabled = true;
    try {
      const phoneRaw = $("#gen-phone").value.trim();
      const payload = {
        discountPercent: Number($("#gen-percent").value),
        channel: "PORTAL",
        customerPhone: phoneRaw || DEFAULT_CUSTOMER_PHONE
      };
      const ticket = await api("/v1/discount-tickets", {
        method: "POST",
        body: JSON.stringify(payload)
      });
      state.lastGenerated = ticket;
      const phoneLabel = formatCustomerPhone(ticket.customerPhone);
      $("#generate-success").textContent =
        `Cupón ${ticket.code} generado (${ticket.discountPercent}%).` +
        (phoneLabel !== "—" ? ` Teléfono: ${phoneLabel}.` : "") +
        " Actívalo escaneando el QR desde la app.";
      $("#generate-success").classList.remove("hidden");
      $("#generate-qr-btn").classList.remove("hidden");
      $("#gen-phone").value = "";
      $("#gen-confirm").checked = false;
      updateGenerateButtonState();
      openDetail(ticket);
      await loadTickets();
    } catch (err) {
      $("#generate-error").textContent = err.message;
      $("#generate-error").classList.remove("hidden");
    } finally {
      updateGenerateButtonState();
    }
  }

  function ticketQrImageUrl(code) {
    if (!state.token) return "";
    const params = new URLSearchParams({ t: state.token });
    return `/v1/discount-tickets/${encodeURIComponent(code)}/qr?${params.toString()}`;
  }

  function buildQrPrintHtml(ticket, qrSrc) {
    const expiresText = ticket.expiresAt
      ? formatDate(ticket.expiresAt)
      : "Al activar (30 días)";
    const pct = Number(ticket.discountPercent);
    const pctLabel = Number.isInteger(pct) ? String(pct) : String(pct);
    return `<!DOCTYPE html>
      <html lang="es"><head>
        <meta charset="UTF-8" />
        <title>QR cupón · ${escapeHtml(ticket.code)}</title>
        <style>
          * { box-sizing: border-box; margin: 0; padding: 0; }
          body {
            font-family: "Segoe UI", "Arial Black", Impact, sans-serif;
            margin: 0;
            padding: 12px;
            background: #111;
            -webkit-print-color-adjust: exact;
            print-color-adjust: exact;
          }
          .coupon {
            position: relative;
            width: 340px;
            margin: 0 auto;
            overflow: hidden;
            border-radius: 12px;
            color: #fff;
            text-align: center;
            background:
              linear-gradient(145deg, rgba(0,0,0,.72), rgba(120,0,0,.55)),
              repeating-linear-gradient(
                45deg,
                #1a1a1a 0 14px,
                #2b2b2b 14px 28px
              );
            box-shadow: 0 12px 40px rgba(0,0,0,.45);
          }
          .coupon::before {
            content: "";
            position: absolute;
            inset: 0;
            background:
              linear-gradient(115deg, transparent 40%, rgba(255,30,30,.35) 50%, transparent 60%),
              radial-gradient(circle at 20% 10%, rgba(255,60,60,.25), transparent 45%),
              radial-gradient(circle at 85% 20%, rgba(255,40,40,.2), transparent 40%);
            pointer-events: none;
          }
          .inner { position: relative; z-index: 1; padding: 22px 16px 18px; }
          .gear {
            position: absolute;
            width: 64px;
            height: 64px;
            opacity: .22;
            border: 6px solid #c0c0c0;
            border-radius: 50%;
            top: 8px;
            left: -18px;
          }
          .gear::before {
            content: "";
            position: absolute;
            inset: 14px;
            border: 4px solid #a8a8a8;
            border-radius: 50%;
          }
          .discount {
            font-size: 58px;
            font-weight: 900;
            font-style: italic;
            line-height: .95;
            letter-spacing: -1px;
            color: #fff;
            text-shadow:
              0 0 12px rgba(255,40,40,.95),
              0 0 28px rgba(255,0,0,.65),
              0 3px 0 #8b0000;
          }
          .discount small {
            font-size: 34px;
            font-weight: 900;
          }
          .subtitle {
            margin-top: 6px;
            font-size: 13px;
            font-weight: 800;
            letter-spacing: .12em;
            text-transform: uppercase;
          }
          .qr-stage {
            margin: 16px auto 14px;
            width: 220px;
            padding: 12px;
            border-radius: 10px;
            background: linear-gradient(180deg, #ececec 0%, #b8b8b8 100%);
            box-shadow:
              inset 0 1px 0 rgba(255,255,255,.8),
              0 8px 18px rgba(0,0,0,.35);
          }
          .qr-frame {
            background: #fff;
            border-radius: 8px;
            padding: 10px;
            border: 2px solid #d4d4d4;
          }
          .qr-img {
            display: block;
            width: 176px;
            height: 176px;
            margin: 0 auto;
            image-rendering: pixelated;
          }
          .banner {
            display: inline-block;
            margin: 4px auto 12px;
            padding: 8px 22px;
            transform: skewX(-12deg);
            background: linear-gradient(90deg, #b30000, #ff1a1a);
            box-shadow: 0 4px 14px rgba(255,0,0,.35);
          }
          .banner span {
            display: inline-block;
            transform: skewX(12deg);
            font-size: 28px;
            font-weight: 900;
            letter-spacing: .06em;
            color: #fff;
          }
          .meta {
            font-size: 11px;
            line-height: 1.55;
            color: rgba(255,255,255,.92);
            font-weight: 600;
          }
          .validity {
            margin-top: 8px;
            font-size: 10px;
            line-height: 1.45;
            color: rgba(255,255,255,.78);
            font-weight: 500;
          }
          .brand {
            margin-top: 14px;
            padding-top: 12px;
            border-top: 1px solid rgba(255,255,255,.15);
          }
          .brand-main {
            font-size: 18px;
            font-weight: 900;
            letter-spacing: .04em;
            color: #fff;
          }
          .brand-address {
            margin-top: 6px;
            font-size: 10px;
            letter-spacing: .1em;
            text-transform: uppercase;
            color: rgba(255,255,255,.85);
            font-weight: 600;
          }
          .qr-error {
            color: #ffb4b4;
            font-size: 12px;
            margin-top: 8px;
            font-weight: 700;
          }
          .hidden { display: none; }
          @media print {
            body { padding: 0; background: #fff; }
            .coupon { box-shadow: none; page-break-inside: avoid; }
          }
        </style>
      </head><body>
        <div class="coupon">
          <div class="gear" aria-hidden="true"></div>
          <div class="inner">
            <div class="discount">${pctLabel}% <small>OFF</small></div>
            <div class="subtitle">Cupón de descuento</div>
            <div class="qr-stage">
              <div class="qr-frame">
                <img class="qr-img" id="qr-img" src="${qrSrc}" alt="QR del cupón" />
              </div>
            </div>
            <div class="banner"><span>SUPRA PART</span></div>
            <div class="meta">Creado: ${formatDate(ticket.issuedAt)}</div>
            <div class="meta">Vence: ${expiresText}</div>
            <div class="validity">${QR_TICKET_VALIDITY_TEXT}</div>
            <div class="brand">
              <div class="brand-main">TOTAL CARE</div>
              <div class="brand-address">dirección AV. RIVAS</div>
            </div>
            <div class="qr-error hidden" id="qr-error">No se pudo cargar el QR. Cierra e intenta de nuevo.</div>
          </div>
        </div>
        <script>
          (function() {
            var img = document.getElementById("qr-img");
            var err = document.getElementById("qr-error");
            function printNow() { setTimeout(function() { window.print(); }, 350); }
            if (!img) return;
            img.addEventListener("error", function() {
              if (err) err.classList.remove("hidden");
            });
            if (img.complete && img.naturalWidth > 0) printNow();
            else img.addEventListener("load", printNow);
          })();
        <\/script>
      </body></html>`;
  }

  function printQrCode(ticket) {
    if (!state.token) {
      alert("Inicia sesión de nuevo para imprimir el QR.");
      return;
    }
    const qrSrc = ticketQrImageUrl(ticket.code);
    const win = window.open("", "_blank", "width=420,height=780");
    if (!win) {
      alert("Permite ventanas emergentes para imprimir el QR.");
      return;
    }
    win.document.write(buildQrPrintHtml(ticket, qrSrc));
    win.document.close();
  }

  async function openDetail(ticketOrCode) {
    let ticket = typeof ticketOrCode === "string"
      ? state.tickets.find((t) => t.code === ticketOrCode)
      : ticketOrCode;
    if (!ticket || typeof ticketOrCode === "string") {
      try {
        const data = await api(`/v1/discount-tickets/${typeof ticketOrCode === "string" ? ticketOrCode : ticketOrCode.code}`);
        ticket = data.ticket;
      } catch (err) {
        alert(err.message);
        return;
      }
    }
    state.selected = ticket;
    $("#modal-title").textContent = `Cupón · ${ticket.discountPercent}%`;
    const qrSrc = ticketQrImageUrl(ticket.code);
    const qrBlock = qrSrc
      ? `<div class="qr-wrap"><img class="qr-preview-img" src="${qrSrc}" alt="QR del cupón" width="200" height="200" /></div>`
      : '<div class="qr-wrap"><p class="error">Inicia sesión de nuevo para ver el QR.</p></div>';
    $("#modal-body").innerHTML = `
      ${qrBlock}
      <div class="detail-grid">
        <div class="detail-row"><span>Descuento</span><span>${ticket.discountPercent}%</span></div>
        <div class="detail-row"><span>Teléfono</span><span>${escapeHtml(formatCustomerPhone(ticket.customerPhone))}</span></div>
        <div class="detail-row"><span>Estado</span><span><span class="badge ${badgeClass(ticket.displayStatus)}">${statusLabel(ticket.displayStatus)}</span></span></div>
        <div class="detail-row"><span>Creado</span><span>${formatDate(ticket.issuedAt)}</span></div>
        <div class="detail-row"><span>Activado</span><span>${formatDate(ticket.activatedAt)}</span></div>
        <div class="detail-row"><span>Vence</span><span>${formatDate(ticket.expiresAt)}</span></div>
        <div class="detail-row"><span>Emitido por</span><span>${escapeHtml(ticket.issuedByUsername || "—")}</span></div>
        <div class="detail-row"><span>Canal</span><span>${ticket.issuedChannel === "APP" ? "App móvil" : "Portal web"}</span></div>
        ${ticket.usedAt ? `<div class="detail-row"><span>Usado</span><span>${formatDate(ticket.usedAt)}</span></div>` : ""}
      </div>
      <p class="validity-note">${QR_TICKET_VALIDITY_TEXT}</p>
      <h4>Historial</h4>
      <ul class="audit-list">
        ${(ticket.auditLog || []).map((e) => `
          <li>
            <strong>${auditActionLabel(e.action)}</strong> · ${formatDate(e.at)}
            ${e.by ? ` · ${escapeHtml(e.by)}` : ""}
          </li>
        `).join("")}
      </ul>
    `;
    const printBtn = $("#modal-print-qr");
    const deleteBtn = $("#modal-delete");
    if (printBtn) {
      printBtn.classList.toggle("hidden", !canManagePortal() || ticket.displayStatus === "VOIDED");
      printBtn.onclick = () => printQrCode(ticket);
    }
    if (deleteBtn) {
      const canDelete = canManagePortal() && ticket.displayStatus === "VOIDED";
      deleteBtn.classList.toggle("hidden", !canDelete);
      deleteBtn.onclick = () => openDeleteModal(ticket.code);
    }
    $("#modal").classList.remove("hidden");
  }

  function auditActionLabel(action) {
    return {
      CREATED: "Creado",
      ACTIVATED: "Activado",
      USED: "Canjeado",
      VOIDED: "Anulado"
    }[action] || action;
  }

  function closeModal() {
    $("#modal").classList.add("hidden");
    state.selected = null;
  }

  async function voidCode(code) {
    if (!canManagePortal()) return;
    const reason = prompt("Motivo de anulación (opcional):");
    if (reason === null) return;
    try {
      await api(`/v1/discount-tickets/${code}/void`, {
        method: "PATCH",
        body: JSON.stringify({ reason: reason.trim() })
      });
      await loadTickets();
      const updated = state.tickets.find((t) => t.code === code);
      if (updated && state.selected?.code === code) {
        openDetail(updated);
      } else {
        closeModal();
      }
      alert("Cupón anulado. Para eliminarlo permanentemente, usa el botón Eliminar e ingresa el código de acceso.");
    } catch (err) {
      alert(err.message);
    }
  }

  function openDeleteModal(code) {
    if (!canManagePortal()) return;
    const ticket = state.tickets.find((t) => t.code === code) || state.selected;
    if (!ticket || ticket.displayStatus !== "VOIDED") {
      alert("Para eliminar un cupón primero debes anularlo con el botón Anular.");
      return;
    }
    state.pendingDeleteCode = code;
    $("#delete-access-code").value = "";
    $("#delete-error").classList.add("hidden");
    $("#delete-modal-code").textContent = code;
    $("#delete-modal").classList.remove("hidden");
  }

  function closeDeleteModal() {
    $("#delete-modal").classList.add("hidden");
    state.pendingDeleteCode = null;
    $("#delete-access-code").value = "";
    $("#delete-error").classList.add("hidden");
  }

  async function confirmDeleteTicket() {
    const code = state.pendingDeleteCode;
    if (!code || !canManagePortal()) return;
    const accessCode = $("#delete-access-code").value.trim();
    if (!accessCode) {
      $("#delete-error").textContent = "Ingresa el código de acceso.";
      $("#delete-error").classList.remove("hidden");
      return;
    }
    const btn = $("#delete-confirm");
    btn.disabled = true;
    try {
      await api(`/v1/discount-tickets/${code}`, {
        method: "DELETE",
        body: JSON.stringify({ accessCode })
      });
      closeDeleteModal();
      closeModal();
      await loadTickets();
    } catch (err) {
      $("#delete-error").textContent = err.message || "No se pudo eliminar el cupón.";
      $("#delete-error").classList.remove("hidden");
    } finally {
      btn.disabled = false;
    }
  }

  function logout() {
    disconnectRealtime();
    clearSession();
    showLogin();
  }

  function bindEvents() {
    $("#login-form").addEventListener("submit", login);
    $("#logout-btn").addEventListener("click", logout);
    if ($("#generate-form")) {
      $("#generate-form").addEventListener("submit", generateCode);
      $("#gen-confirm")?.addEventListener("change", updateGenerateButtonState);
      updateGenerateButtonState();
    }
    $("#generate-qr-btn")?.addEventListener("click", () => {
      if (state.lastGenerated) printQrCode(state.lastGenerated);
    });
    $("#filter-form").addEventListener("submit", (ev) => {
      ev.preventDefault();
      state.page = 1;
      loadTickets();
    });
    $("#clear-filters").addEventListener("click", () => {
      $("#filter-status").value = "";
      $("#filter-code").value = "";
      $("#filter-phone").value = "";
      $("#filter-percent").value = "";
      $("#filter-start").value = "";
      $("#filter-end").value = "";
      state.page = 1;
      loadTickets();
    });
    $("#page-size")?.addEventListener("change", (ev) => {
      const size = Number(ev.target.value);
      if (!PAGE_SIZE_OPTIONS.includes(size)) return;
      state.pageSize = size;
      state.page = 1;
      normalizePagination();
      renderTable();
    });
    $("#page-prev")?.addEventListener("click", () => {
      if (state.page <= 1) return;
      state.page -= 1;
      renderTable();
    });
    $("#page-next")?.addEventListener("click", () => {
      const totalPages = Math.max(1, Math.ceil(state.tickets.length / state.pageSize) || 1);
      if (state.page >= totalPages) return;
      state.page += 1;
      renderTable();
    });
    $("#refresh-btn").addEventListener("click", loadTickets);
    $("#modal-close").addEventListener("click", closeModal);
    $("#modal-backdrop").addEventListener("click", (ev) => {
      if (ev.target.id === "modal-backdrop") closeModal();
    });
    $("#table-body").addEventListener("click", (ev) => {
      const btn = ev.target.closest("button[data-action]");
      if (!btn) return;
      const code = btn.dataset.code;
      if (btn.dataset.action === "detail") openDetail(code);
      if (btn.dataset.action === "print-qr") {
        const ticket = state.tickets.find((t) => t.code === code);
        if (ticket) printQrCode(ticket);
      }
      if (btn.dataset.action === "void") voidCode(code);
      if (btn.dataset.action === "delete") openDeleteModal(code);
    });
    $("#delete-confirm")?.addEventListener("click", confirmDeleteTicket);
    $("#delete-cancel")?.addEventListener("click", closeDeleteModal);
    $("#delete-modal-backdrop")?.addEventListener("click", (ev) => {
      if (ev.target.id === "delete-modal-backdrop") closeDeleteModal();
    });
  }

  bindEvents();
  verifySession();
})();
