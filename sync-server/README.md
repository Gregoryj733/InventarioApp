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
