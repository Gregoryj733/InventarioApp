# Despliegue en Render.com (gratis)

Servidor REST para InventarioApp. **No requiere tarjeta de crédito** en el plan gratuito de Render.

URL prevista: `https://inventario-sync-totalcare.onrender.com`

## Guía rápida (Windows)

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy-render.ps1
```

## Pasos manuales

### 1. Base de datos gratis (Neon)

Render reinicia el contenedor y **no guarda archivos** en el plan free. Usa Postgres gratis en [neon.tech](https://neon.tech):

1. Crea cuenta y proyecto
2. Copia la **Connection string** (`postgresql://...`)

### 2. Publicar en Render

1. Cuenta en [render.com](https://render.com)
2. **New** → **Blueprint**
3. Conecta el repositorio GitHub de InventarioApp
4. Render lee `render.yaml` y crea el servicio `inventario-sync-totalcare`
5. Cuando esté **Live**, abre el servicio → **Environment**
6. Agrega `DATABASE_URL` = connection string de Neon
7. Render redeploya automáticamente

### 3. Variables de entorno en Render

| Variable | Valor |
|----------|-------|
| `API_KEY` | `TcSync-7kM9pQ2xR4nW` (ya en render.yaml) |
| `DATABASE_URL` | Connection string de Neon (obligatorio para persistencia) |
| `JWT_SECRET` | Cadena aleatoria larga para firmar los tokens de sesión (obligatorio) |
| `FIREBASE_SERVICE_ACCOUNT` | JSON completo de la cuenta de servicio de Firebase, en una sola línea (opcional; sin esto no se envían notificaciones push) |
| `KEEP_ALIVE_URL` | URL pública del propio servicio (opcional). En Render normalmente **no hace falta**: la plataforma expone `RENDER_EXTERNAL_URL` automáticamente y el servidor la usa como valor por defecto. |
| `KEEP_ALIVE_INTERVAL_MS` | Intervalo entre pings de keep-alive en milisegundos (opcional, por defecto 240000 = 4 min) |

Para generar `FIREBASE_SERVICE_ACCOUNT`: en Firebase Console → ⚙️ Configuración del proyecto → Cuentas de servicio → **Generar nueva clave privada**. Pega el contenido completo del `.json` descargado como valor de la variable.

### Keep-alive (evita desfasajes entre dispositivos)

El plan free de Render duerme el servicio tras ~15 min sin tráfico entrante, y Neon
(Postgres free) también suspende la base tras inactividad. Cuando eso pasa, el
WebSocket de **todos** los celulares conectados se corta a la vez y el que
reactiva el servicio tiene que esperar hasta ~1 minuto — eso es lo que se percibe
como "pedidos/inventario desfasados entre dispositivos". El servidor ahora hace
un ping periódico a su propia `/health` (usando `RENDER_EXTERNAL_URL`, que Render
inyecta solo) para que nunca llegue a esos ~15 min de inactividad. No requiere
ninguna configuración adicional en Render; en desarrollo local (`npm start`) el
keep-alive queda deshabilitado automáticamente porque no hay URL pública.

Para una garantía más fuerte de "sin retrasos" (el keep-alive interno no puede
despertar el servicio si ya estaba dormido, solo evita que llegue a dormirse),
lo más confiable sigue siendo: 1) un monitor externo (p. ej. [UptimeRobot](https://uptimerobot.com)
o [cron-job.org](https://cron-job.org), gratis) pegándole a `/health` cada 5
minutos, o 2) subir al plan de pago de Render ("Starter" en adelante no duerme).
Ninguna de las dos opciones requiere tocar la app Android.

### 4. Verificar

```powershell
curl https://inventario-sync-totalcare.onrender.com/health
```

### 5. App Android

`app/src/main/assets/sync_config.json`:

```json
{
  "baseUrl": "https://inventario-sync-totalcare.onrender.com",
  "apiKey": "TcSync-7kM9pQ2xR4nW"
}
```

Recompila el APK e instálalo en los celulares.

## Notas

- El plan free de Render **duerme** tras ~15 min sin tráfico; la app lo reactiva al sincronizar (puede tardar ~30 s).
- Sin `DATABASE_URL`, el inventario **no persiste** entre reinicios del servicio.
- Alternativa local: `npm start` en esta carpeta.

## API

Todas las rutas `/v1/*` requieren el header `X-Api-Key`. Las marcadas con 🔒 además requieren `Authorization: Bearer <token>` obtenido en `/v1/auth/login`; 🔒ADMIN requiere que el token tenga `role = ADMIN`.

- `POST /v1/auth/login` — `{ username, password }` → `{ token, user }`
- `GET /v1/state` — inventario + meta + revisión actual
- `POST /v1/inventory/import` 🔒ADMIN — multipart `file` (.xlsx) → parsea y reemplaza el inventario, notifica por WebSocket y FCM
- `POST /v1/inventory/deduct` 🔒 — descuenta stock (legacy, sin registrar venta; el cliente actual no lo usa)
- `PUT /v1/meta` 🔒 — actualiza tasa BCV / metadatos
- `POST /v1/orders` 🔒 — pedido atómico: descuenta stock y registra la venta + detalle de líneas
  (`description`/`unit`/`unitPriceUsd` se autocompletan desde el inventario si el cliente los manda
  vacíos) en una sola transacción idempotente por `syncId`; notifica `inventory` y `sales` por WebSocket
- `GET /v1/sales` 🔒 / `POST /v1/sales` 🔒 (legacy) / `DELETE /v1/sales?start=&end=` 🔒ADMIN
- `GET /v1/cash-closings` 🔒 / `POST /v1/cash-closings` 🔒 / `PATCH /v1/cash-closings/:id/status` 🔒
- `GET /v1/users` 🔒ADMIN / `POST /v1/users` 🔒ADMIN / `PATCH /v1/users/:id` 🔒ADMIN / `DELETE /v1/users/:id` 🔒ADMIN
- `GET /v1/ws?apiKey=...` — WebSocket; emite `{ type: "inventory" | "sales" | "cashClosings" | "users" | "discountTickets" }` tras cada cambio
- `GET /v1/ws?token=<JWT>` — WebSocket para el portal web (sin `X-Api-Key`)

Usuarios por defecto (se crean automáticamente si la base está vacía): `admin/admin` (ADMIN), `consulta/consulta` (CONSULTA) y `venta/venta` (VENTAS).

## Portal de códigos de descuento

- **URL:** `https://<tu-servidor>/portal/`
- **Acceso al portal:**
  - **Consulta** y **Ventas** — solo lectura (ver códigos, clientes, fechas, estados e historial)
  - **Supervisor** y **Admin** — gestión completa (generar, anular, administrar)
- **Generación:** solo Supervisor y Admin desde el portal (la app móvil valida y canjea códigos en el carrito)
- **Tiempo real:** el portal se conecta por WebSocket (`/v1/ws?token=...`) y actualiza el listado al instante cuando la app canjea un código

### API de códigos de descuento

| Método | Ruta | Auth | Descripción |
|--------|------|------|-------------|
| `GET` | `/v1/discount-tickets?code=ABC123` | 🔒 | Validar un código (app móvil) |
| `GET` | `/v1/discount-tickets?list=1&status=ACTIVE&customer=...` | 🔒 Consulta/Ventas/Supervisor/Admin | Listado con filtros |
| `GET` | `/v1/discount-tickets/:code` | 🔒 Consulta/Ventas/Supervisor/Admin | Detalle + auditoría |
| `POST` | `/v1/discount-tickets` | 🔒 Supervisor/Admin | Generar código |
| `PATCH` | `/v1/discount-tickets/:code/void` | 🔒 Supervisor/Admin | Anular código activo |
| `GET` | `/v1/auth/me` | 🔒 | Sesión actual + `canViewDiscounts` / `canManageDiscounts` |

El portal web puede autenticarse solo con JWT (sin `X-Api-Key`). La app Android sigue usando ambos.

### Modelo JSON — `DiscountTicket`

```json
{
  "id": 1,
  "code": "AB12CD34",
  "customerName": "María Pérez",
  "customerPhone": "04141234567",
  "discountPercent": 10,
  "issuedAt": 1710000000000,
  "expiresAt": 1712592000000,
  "status": "ACTIVE",
  "displayStatus": "ACTIVE",
  "usedAt": null,
  "usedBySaleSyncId": null,
  "usedByUsername": null,
  "issuedByUsername": "admin",
  "issuedChannel": "PORTAL",
  "sourceSaleSyncId": null,
  "voidedAt": null,
  "voidedByUsername": null,
  "voidReason": null,
  "auditLog": [
    {
      "action": "CREATED",
      "at": 1710000000000,
      "by": "admin",
      "details": { "discountPercent": 10, "channel": "PORTAL", "customerName": "María Pérez", "customerPhone": "04141234567" }
    }
  ]
}
```

**`status` persistido:** `ACTIVE` | `USED` | `VOIDED`

**`displayStatus` calculado:** `ACTIVE` | `USED` | `EXPIRED` | `VOIDED` (expirado si `expiresAt <= now` y sigue `ACTIVE`)

**Vigencia:** 30 días desde `issuedAt` (`expiresAt = issuedAt + 30 días`)

**Canje:** al confirmar un pedido con `discountTicketCode`, el servidor marca el ticket como `USED` en la misma transacción atómica que la venta y emite `discountTickets` por WebSocket.


## Desarrollo local

```powershell
npm install
npm start
```

Con Postgres local:

```powershell
$env:DATABASE_URL = "postgresql://..."
npm start
```
