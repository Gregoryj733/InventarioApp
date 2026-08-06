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

Para generar `FIREBASE_SERVICE_ACCOUNT`: en Firebase Console → ⚙️ Configuración del proyecto → Cuentas de servicio → **Generar nueva clave privada**. Pega el contenido completo del `.json` descargado como valor de la variable.

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
- `POST /v1/inventory/deduct` 🔒 — descuenta stock por pedido
- `PUT /v1/meta` 🔒 — actualiza tasa BCV / metadatos
- `GET /v1/sales` 🔒 / `POST /v1/sales` 🔒 / `DELETE /v1/sales?start=&end=` 🔒ADMIN
- `GET /v1/cash-closings` 🔒 / `POST /v1/cash-closings` 🔒 / `PATCH /v1/cash-closings/:id/status` 🔒
- `GET /v1/users` 🔒ADMIN / `POST /v1/users` 🔒ADMIN / `PATCH /v1/users/:id` 🔒ADMIN / `DELETE /v1/users/:id` 🔒ADMIN
- `GET /v1/ws?apiKey=...` — WebSocket; emite `{ type: "inventory" | "sales" | "cashClosings" | "users" }` tras cada cambio

Usuarios por defecto (se crean automáticamente si la base está vacía): `admin/admin` (ADMIN) y `consulta/consulta` (CONSULTA).

### Instancia de prueba (DEMO_MODE)

Con `DEMO_MODE=true`, esta misma imagen sirve una instancia **aislada** con datos de ejemplo en vez de los reales — pensada para la versión de prueba de Play Store, para que ninguna interacción de los testers toque el inventario/ventas/usuarios reales:

- Usuarios semilla: `usuario1/usuario` (CONSULTA) y `usuario2/usuario` (SUPERVISOR). No se crea `admin`.
- Inventario semilla: 5 productos de ejemplo (batería, aceite, filtro, bujía, pastillas de freno).
- El seeding solo ocurre si la base está vacía (mismo criterio que la instancia real), así que sobrevive a reinicios pero nunca pisa datos ya generados por los testers.

Se despliega como un **servicio Render separado** (ver `render.yaml`, servicio `inventario-sync-demo`), con su propio `API_KEY` y sin `DATABASE_URL` (usa el almacenamiento de archivo efímero: no hay ninguna base de datos compartida con producción).

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
