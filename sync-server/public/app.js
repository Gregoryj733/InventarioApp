(function () {
  const TOKEN_KEY = "inventario_portal_token";
  const USER_KEY = "inventario_portal_user";

  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => document.querySelectorAll(sel);

  const state = {
    token: localStorage.getItem(TOKEN_KEY),
    user: JSON.parse(localStorage.getItem(USER_KEY) || "null"),
    tickets: [],
    stats: null,
    selected: null,
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
      ACTIVE: "Activo",
      USED: "Usado",
      EXPIRED: "Expirado",
      VOIDED: "Anulado"
    }[s] || s;
  }

  function badgeClass(s) {
    return {
      ACTIVE: "badge-active",
      USED: "badge-used",
      EXPIRED: "badge-expired",
      VOIDED: "badge-voided"
    }[s] || "badge-used";
  }

  function saveSession(token, user) {
    state.token = token;
    state.user = user;
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  }

  function clearSession() {
    disconnectRealtime();
    state.token = null;
    state.user = null;
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
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
      } catch (_) {
        // Ignorar mensajes no JSON.
      }
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
  }

  function roleLabel(role) {
    return { ADMIN: "Admin", SUPERVISOR: "Supervisor", CONSULTA: "Consulta" }[role] || role;
  }

  async function verifySession() {
    if (!state.token) return showLogin();
    try {
      const data = await api("/v1/auth/me");
      if (!data.canManageDiscounts) {
        clearSession();
        return showLogin("Tu perfil no tiene acceso al portal de descuentos.");
      }
      saveSession(state.token, data.user);
      showMain();
      connectRealtime();
      await loadTickets();
    } catch (_) {
      clearSession();
      showLogin("Sesión expirada. Inicia sesión de nuevo.");
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
        body: JSON.stringify({ username, password })
      });
      if (!["ADMIN", "SUPERVISOR"].includes(data.user.role)) {
        throw new Error("Solo usuarios Admin o Supervisor pueden acceder al portal.");
      }
      saveSession(data.token, data.user);
      showMain();
      connectRealtime();
      await loadTickets();
    } catch (err) {
      $("#login-error").textContent = err.message;
      $("#login-error").classList.remove("hidden");
    } finally {
      btn.disabled = false;
    }
  }

  function buildQuery() {
    const params = new URLSearchParams({ list: "1" });
    const status = $("#filter-status").value;
    const customer = $("#filter-customer").value.trim();
    const percent = $("#filter-percent").value;
    const issuedStart = $("#filter-start").value;
    const issuedEnd = $("#filter-end").value;
    if (status) params.set("status", status);
    if (customer) params.set("customer", customer);
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
      renderStats();
      renderTable();
    } catch (err) {
      $("#table-body").innerHTML = `<tr><td colspan="8" class="empty">${err.message}</td></tr>`;
    } finally {
      if (!silent) state.loading = false;
      if (!silent) $("#refresh-btn").disabled = false;
      // Si hay un detalle abierto, refrescarlo tras un evento en vivo.
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
    $("#stat-active").textContent = s.active;
    $("#stat-used").textContent = s.used;
    $("#stat-expired").textContent = s.expired;
    $("#stat-voided").textContent = s.voided;
  }

  function renderTable() {
    const tbody = $("#table-body");
    if (!state.tickets.length) {
      tbody.innerHTML = '<tr><td colspan="8" class="empty">No hay códigos que coincidan con los filtros.</td></tr>';
      return;
    }
    tbody.innerHTML = state.tickets.map((t) => `
      <tr>
        <td class="code-cell">${t.code}</td>
        <td>${escapeHtml(t.customerName)}</td>
        <td>${escapeHtml(t.customerPhone)}</td>
        <td>${t.discountPercent}%</td>
        <td>${formatDate(t.issuedAt)}</td>
        <td>${formatDate(t.expiresAt)}</td>
        <td><span class="badge ${badgeClass(t.displayStatus)}">${statusLabel(t.displayStatus)}</span></td>
        <td>
          <button class="btn btn-ghost btn-sm" data-action="detail" data-code="${t.code}">Ver</button>
          ${t.displayStatus === "ACTIVE" ? `<button class="btn btn-danger btn-sm" data-action="void" data-code="${t.code}">Anular</button>` : ""}
        </td>
      </tr>
    `).join("");
  }

  function escapeHtml(str) {
    return String(str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  async function generateCode(ev) {
    ev.preventDefault();
    $("#generate-error").classList.add("hidden");
    $("#generate-success").classList.add("hidden");
    const btn = $("#generate-btn");
    btn.disabled = true;
    try {
      const ticket = await api("/v1/discount-tickets", {
        method: "POST",
        body: JSON.stringify({
          customerName: $("#gen-name").value.trim(),
          customerPhone: $("#gen-phone").value.trim(),
          discountPercent: Number($("#gen-percent").value),
          channel: "PORTAL"
        })
      });
      $("#generate-success").textContent = `Código ${ticket.code} generado (${ticket.discountPercent}% · válido hasta ${formatDate(ticket.expiresAt)}).`;
      $("#generate-success").classList.remove("hidden");
      $("#gen-name").value = "";
      $("#gen-phone").value = "";
      openDetail(ticket);
      await loadTickets();
    } catch (err) {
      $("#generate-error").textContent = err.message;
      $("#generate-error").classList.remove("hidden");
    } finally {
      btn.disabled = false;
    }
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
    $("#modal-title").textContent = `Código ${ticket.code}`;
    $("#modal-body").innerHTML = `
      <div class="qr-wrap"><canvas id="qr-canvas"></canvas></div>
      <div class="detail-grid">
        <div class="detail-row"><span>Cliente</span><span>${escapeHtml(ticket.customerName)}</span></div>
        <div class="detail-row"><span>Teléfono</span><span>${escapeHtml(ticket.customerPhone)}</span></div>
        <div class="detail-row"><span>Descuento</span><span>${ticket.discountPercent}%</span></div>
        <div class="detail-row"><span>Estado</span><span><span class="badge ${badgeClass(ticket.displayStatus)}">${statusLabel(ticket.displayStatus)}</span></span></div>
        <div class="detail-row"><span>Emitido</span><span>${formatDate(ticket.issuedAt)}</span></div>
        <div class="detail-row"><span>Vence</span><span>${formatDate(ticket.expiresAt)}</span></div>
        <div class="detail-row"><span>Emitido por</span><span>${escapeHtml(ticket.issuedByUsername || "—")}</span></div>
        <div class="detail-row"><span>Canal</span><span>${ticket.issuedChannel === "APP" ? "App móvil" : "Portal web"}</span></div>
        ${ticket.usedAt ? `<div class="detail-row"><span>Usado</span><span>${formatDate(ticket.usedAt)}</span></div>` : ""}
        ${ticket.usedBySaleSyncId ? `<div class="detail-row"><span>Venta</span><span>${escapeHtml(ticket.usedBySaleSyncId)}</span></div>` : ""}
      </div>
      <h4>Historial</h4>
      <ul class="audit-list">
        ${(ticket.auditLog || []).map((e) => `
          <li>
            <strong>${auditActionLabel(e.action)}</strong> · ${formatDate(e.at)}
            ${e.by ? ` · ${escapeHtml(e.by)}` : ""}
            ${e.details?.reason ? `<br><em>${escapeHtml(e.details.reason)}</em>` : ""}
            ${e.details?.saleSyncId ? `<br>Venta: ${escapeHtml(e.details.saleSyncId)}` : ""}
          </li>
        `).join("")}
      </ul>
    `;
    $("#modal").classList.remove("hidden");
    if (window.QRCode && $("#qr-canvas")) {
      window.QRCode.toCanvas($("#qr-canvas"), ticket.code, { width: 200, margin: 2 });
    }
  }

  function auditActionLabel(action) {
    return { CREATED: "Creado", USED: "Canjeado", VOIDED: "Anulado" }[action] || action;
  }

  function closeModal() {
    $("#modal").classList.add("hidden");
    state.selected = null;
  }

  async function voidCode(code) {
    const reason = prompt("Motivo de anulación (opcional):");
    if (reason === null) return;
    try {
      await api(`/v1/discount-tickets/${code}/void`, {
        method: "PATCH",
        body: JSON.stringify({ reason: reason.trim() })
      });
      closeModal();
      await loadTickets();
    } catch (err) {
      alert(err.message);
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
    $("#generate-form").addEventListener("submit", generateCode);
    $("#filter-form").addEventListener("submit", (ev) => { ev.preventDefault(); loadTickets(); });
    $("#clear-filters").addEventListener("click", () => {
      $("#filter-status").value = "";
      $("#filter-customer").value = "";
      $("#filter-percent").value = "";
      $("#filter-start").value = "";
      $("#filter-end").value = "";
      loadTickets();
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
      if (btn.dataset.action === "void") voidCode(code);
    });
  }

  bindEvents();
  verifySession();
})();
