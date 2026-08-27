# Asistente para completar multi-sucursal usando solo dashboards (sin API key).
# Abre Neon + Render y guía paso a paso.

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ConfigPath = Join-Path $ProjectRoot "app\src\main\assets\sync_config.json"

function Read-Branches {
    $json = Get-Content $ConfigPath -Raw | ConvertFrom-Json
    return @($json.branches)
}

Write-Host ""
Write-Host "=== Setup multi-sucursal (dashboard) ===" -ForegroundColor Cyan
Write-Host ""

Write-Host "PASO 1 — Neon (Postgres gratis)" -ForegroundColor Yellow
Write-Host "  1. Crea o abre un proyecto en Neon" -ForegroundColor Gray
Write-Host "  2. Base 1: usa la default (p.ej. neondb) -> Total Care" -ForegroundColor Gray
Write-Host "  3. Databases -> Create database -> inventario_b -> Supra Parts" -ForegroundColor Gray
Write-Host "  4. Copia cada Connection string (postgresql://...)" -ForegroundColor Gray
Write-Host ""
$openNeon = Read-Host "Abrir neon.tech ahora? (S/n)"
if ($openNeon -ne "n" -and $openNeon -ne "N") {
    Start-Process "https://console.neon.tech/"
}

Write-Host ""
Write-Host "PASO 2 — Render Blueprint Sync (crear Supra Parts)" -ForegroundColor Yellow
Write-Host "  1. Render Dashboard -> Blueprints" -ForegroundColor Gray
Write-Host "  2. Abre el blueprint de InventarioApp" -ForegroundColor Gray
Write-Host "  3. Manual Sync / Apply changes" -ForegroundColor Gray
Write-Host "     Debe aparecer: inventario-sync-supra-parts" -ForegroundColor Gray
Write-Host ""
$openBp = Read-Host "Abrir Render Blueprints ahora? (S/n)"
if ($openBp -ne "n" -and $openBp -ne "N") {
    Start-Process "https://dashboard.render.com/blueprints"
}

Write-Host ""
Write-Host "PASO 3 — Variables en cada servicio Render" -ForegroundColor Yellow
$branches = Read-Branches
foreach ($b in $branches) {
    $slug = if ($b.baseUrl -match "supra-parts") { "inventario-sync-supra-parts" } else { "inventario-sync-totalcare" }
    Write-Host ""
    Write-Host "  $($b.label) -> servicio $slug" -ForegroundColor White
    Write-Host "    DATABASE_URL = connection string Neon de ESTA sucursal" -ForegroundColor Gray
    Write-Host "    JWT_SECRET   = misma cadena larga en AMBOS servicios" -ForegroundColor Gray
    Write-Host "    (FIREBASE_TOPIC ya viene en render.yaml)" -ForegroundColor DarkGray
    $openSvc = Read-Host "  Abrir $slug en Render? (S/n)"
    if ($openSvc -ne "n" -and $openSvc -ne "N") {
        Start-Process "https://dashboard.render.com/web/$slug"
    }
}

Write-Host ""
Write-Host "PASO 4 — Esperar deploy Live en ambos servicios" -ForegroundColor Yellow
Read-Host "Presiona Enter cuando ambos esten Live"

Write-Host ""
Write-Host "PASO 5 — Verificar y asignar usuarios" -ForegroundColor Yellow
& (Join-Path $PSScriptRoot "setup-multi-sucursal-free.ps1")

$assign = Read-Host "Asignar sucursal a Ventas/Consulta en cada instancia? (S/n)"
if ($assign -ne "n" -and $assign -ne "N") {
    & (Join-Path $PSScriptRoot "setup-multi-sucursal-free.ps1") -AssignUsers
}

Write-Host ""
Write-Host "PASO 6 — APK (opcional)" -ForegroundColor Yellow
Write-Host "  cd $ProjectRoot" -ForegroundColor Gray
Write-Host "  .\gradlew.bat assembleRelease" -ForegroundColor Gray
Write-Host ""
Write-Host "Listo." -ForegroundColor Green
