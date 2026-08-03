# InventarioApp (Android)

App Android offline-first para consultar y actualizar inventario desde Excel (`product.xlsx`), con roles, buscador con autocompletado, fecha del día y tasa BCV.

## Requisitos cumplidos

1. Consulta de inventario  
2. Usuario **consulta** (solo lectura)  
3. Usuario **admin** (consulta + actualización)  
4. El inventario solo se actualiza con admin  
5. Actualización por carga de Excel (`DESCRIPCIÓN | CANT. | UND | PRECIO`)  
6. Buscador con autocompletado al escribir  
7. Resultado: nombre, precio unitario y cantidad/UND  
8. Fecha actual + tasa BCV (con caché offline)  
9. Costo cero (servidor REST propio, sin Firebase)  
10. Instalación por APK  
11. **Inventario compartido** entre todos los celulares con la app (sincronización vía servidor REST)

## Usuarios demo

| Usuario    | Clave      | Rol      |
|------------|------------|----------|
| `consulta` | `consulta` | Solo lectura |
| `admin`    | `admin`    | Lectura + importar Excel |

## Cómo generar el APK

1. Instala [Android Studio](https://developer.android.com/studio) (incluye JDK y SDK).  
2. Abre la carpeta `C:\Users\greg7\Projects\InventarioApp`.  
3. Espera el sync de Gradle.  
4. Menú **Build → Build Bundle(s) / APK(s) → Build APK(s)**.  
5. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.  

Para release firmado: **Build → Generate Signed Bundle / APK**.

### Por terminal (con Android Studio instalado)

```bat
gradlew.bat assembleDebug
```

## Uso en el celular

1. Copia el APK al teléfono.  
2. Permite instalar apps de origen desconocido.  
3. Instala e inicia sesión.  
4. Con **admin**, pulsa **Actualizar inventario (Excel)** y elige `product.xlsx`.  
5. Busca un producto y **tócalo** para seleccionarlo.  
6. Modifica la **cantidad** y verás el cálculo automático:
   - Nombre
   - Precio unitario
   - Stock (`CANT.` + `UND`)
   - Equiv. unitario en Bs (BCV)
   - **Total USD** = precio × cantidad
   - **Total Bs** = total USD × tasa BCV

## Sincronización entre celulares

El inventario se sincroniza mediante un **servidor REST** incluido en `sync-server/`. Todos los dispositivos con la app apuntan al mismo servidor y comparten inventario, ventas y tasa BCV.

### Opción recomendada: Render.com (gratis, sin tarjeta)

Despliega el servidor en la nube con HTTPS. **Render no pide tarjeta** en el plan gratuito.

**Guía completa:** `sync-server/README.md`

**Despliegue guiado (Windows):**

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy-render.ps1
```

1. Crea cuenta en [render.com](https://render.com) y conecta el repo GitHub.
2. Render detecta `render.yaml` y publica `https://inventario-sync-totalcare.onrender.com`.
3. Crea Postgres gratis en [neon.tech](https://neon.tech) y agrega `DATABASE_URL` en Render (los datos persisten).

Configuración ya incluida en `app/src/main/assets/sync_config.json`:

```json
{
  "baseUrl": "https://inventario-sync-totalcare.onrender.com",
  "apiKey": "TcSync-7kM9pQ2xR4nW"
}
```

Recompila el APK (`gradlew.bat assembleDebug`) e instálalo en los celulares.

### Opción alternativa: servidor en red local

```bat
cd sync-server
npm install
npm start
```

Por defecto: `http://0.0.0.0:8787` con clave API `inventario-sync-key`.

URL en la app: `http://IP_DE_TU_PC:8787` (ej. `http://192.168.1.10:8787`).

### Configuración en cada celular

**Opción A — desde la app**

1. En la pantalla principal, pulsa **Configurar sincronización**.
2. URL: Render (`https://…onrender.com`) o IP local (`http://192.168.x.x:8787`).
3. Clave API: la misma que configuraste en el servidor.
4. Cierra y vuelve a abrir la app.

**Opción B — al compilar el APK**

Edita `app/src/main/assets/sync_config.json` con `baseUrl` y `apiKey`, luego recompila.

### Uso diario

1. Solo el **admin** carga o actualiza el Excel; los demás celulares reciben el inventario automáticamente (la app consulta el servidor cada ~15 s).
2. Las ventas/pedidos que descuentan stock se reflejan en todos los dispositivos.
3. En la pantalla principal verás **Nube: sincronizado** cuando la conexión esté activa.

## Notas

- Cada celular mantiene una copia local (SQLite) para búsquedas rápidas y uso sin internet; al reconectar, se sincroniza con el servidor.
- Si usas varios celulares, **no** hace falta cargar el Excel en cada uno: basta con que el admin lo cargue una vez.
- La tasa BCV se obtiene de `https://www.bcv.org.ve/`; sin internet se muestra la última guardada (también compartida en el servidor).
- Precios del Excel se muestran en USD; también se calcula el equivalente en Bs con la tasa BCV.
- Para servidor en red local con `http://`, la app permite tráfico HTTP claro (necesario en Android para IPs locales).
