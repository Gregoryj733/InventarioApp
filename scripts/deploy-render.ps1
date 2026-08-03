$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ConfigPath = Join-Path $ProjectRoot "app\src\main\assets\sync_config.json"
$ServiceName = "inventario-sync-totalcare"
$BaseUrl = "https://$ServiceName.onrender.com"

function Read-ApiKey {
    if (Test-Path $ConfigPath) {
        $json = Get-Content $ConfigPath -Raw | ConvertFrom-Json
        if ($json.apiKey) { return [string]$json.apiKey }
    }
    return "TcSync-7kM9pQ2xR4nW"
}

$apiKey = Read-ApiKey

Write-Host ""
Write-Host "=== Despliegue en Render.com (plan gratuito) ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "URL del servidor: $BaseUrl" -ForegroundColor White
Write-Host "Clave API:        $apiKey" -ForegroundColor White
Write-Host ""
Write-Host "Render no pide tarjeta para el plan gratuito." -ForegroundColor Green
Write-Host ""

Write-Host "PASO 1 — Cuenta Render" -ForegroundColor Yellow
Write-Host "  Abre Render y crea cuenta (GitHub recomendado)." -ForegroundColor Gray
Start-Process "https://dashboard.render.com/register"

Read-Host "Presiona Enter cuando tengas cuenta en Render"

Write-Host ""
Write-Host "PASO 2 — Base de datos gratis (Neon)" -ForegroundColor Yellow
Write-Host "  Los datos deben persistir fuera del contenedor de Render." -ForegroundColor Gray
Write-Host "  Neon ofrece Postgres gratis (sin tarjeta)." -ForegroundColor Gray
Start-Process "https://neon.tech"

Write-Host ""
Write-Host "  En Neon:" -ForegroundColor Gray
Write-Host "    1. New Project" -ForegroundColor Gray
Write-Host "    2. Copia la Connection string (postgresql://...)" -ForegroundColor Gray
Write-Host "    3. Guárdala para el paso 4" -ForegroundColor Gray

$neonUrl = Read-Host "Pega aquí la Connection string de Neon (o Enter para omitir ahora)"

Write-Host ""
Write-Host "PASO 3 — Publicar en Render" -ForegroundColor Yellow
Write-Host "  Conecta este repositorio en GitHub si aún no está." -ForegroundColor Gray
Start-Process "https://dashboard.render.com/select-repo?type=blueprint"

Write-Host ""
Write-Host "  En Render:" -ForegroundColor Gray
Write-Host "    1. Conecta el repo InventarioApp" -ForegroundColor Gray
Write-Host "    2. Render detectará render.yaml automáticamente" -ForegroundColor Gray
Write-Host "    3. Apply / Create Blueprint" -ForegroundColor Gray
Write-Host "    4. Espera que el deploy termine (Live)" -ForegroundColor Gray

Read-Host "Presiona Enter cuando el servicio esté Live en Render"

if ($neonUrl -and $neonUrl.Trim().Length -gt 10) {
    Write-Host ""
    Write-Host "PASO 4 — DATABASE_URL en Render" -ForegroundColor Yellow
    Write-Host "  En el servicio $ServiceName → Environment:" -ForegroundColor Gray
    Write-Host "    Agrega variable DATABASE_URL = tu connection string de Neon" -ForegroundColor Gray
    Write-Host "    Render redeployará automáticamente." -ForegroundColor Gray
    Start-Process "https://dashboard.render.com"
    Read-Host "Presiona Enter después de agregar DATABASE_URL y el redeploy"
}

Write-Host ""
Write-Host "PASO 5 — Verificar" -ForegroundColor Yellow
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/health" -TimeoutSec 60
    if ($health.ok) {
        Write-Host "Servidor respondiendo: OK" -ForegroundColor Green
    }
} catch {
    Write-Host "El servidor aún no responde (Render puede tardar 1-2 min en arrancar)." -ForegroundColor Yellow
    Write-Host "Prueba manualmente: curl $BaseUrl/health" -ForegroundColor Gray
}

Write-Host ""
Write-Host "=== Configuración en celulares ===" -ForegroundColor Cyan
Write-Host "URL:      $BaseUrl"
Write-Host "API key:  $apiKey"
Write-Host ""
Write-Host "El APK ya incluye sync_config.json con estos valores." -ForegroundColor Yellow
Write-Host "Recompila si hace falta: gradlew.bat assembleDebug" -ForegroundColor Gray
Write-Host ""
