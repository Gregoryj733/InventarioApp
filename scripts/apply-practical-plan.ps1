# Plan practico: Supra Parts en Render + Neon (proyecto nuevo).
# Total Care: DATABASE_URL cuando Neon renueve cuota o uses proyecto nuevo.
#
# Uso (pega tus valores reales):
#   $env:RENDER_API_KEY = "rnd_..."
#   $env:DATABASE_URL_SUPRA_PARTS = "postgresql://..."
#   # Opcional si ya tienes Total Care en Postgres:
#   $env:DATABASE_URL_TOTAL_CARE = "postgresql://..."
#   powershell -ExecutionPolicy Bypass -File scripts\apply-practical-plan.ps1

param(
    [string]$RenderApiKey = $env:RENDER_API_KEY,
    [string]$DatabaseUrlSupraParts = $env:DATABASE_URL_SUPRA_PARTS,
    [string]$DatabaseUrlTotalCare = $env:DATABASE_URL_TOTAL_CARE,
    [string]$JwtSecret = $env:JWT_SECRET
)

$ErrorActionPreference = "Stop"
$ScriptDir = $PSScriptRoot

if (-not $RenderApiKey) {
    Write-Host "Falta RENDER_API_KEY." -ForegroundColor Red
    Write-Host '  $env:RENDER_API_KEY = "rnd_..."' -ForegroundColor Gray
    exit 1
}
if (-not $DatabaseUrlSupraParts) {
    Write-Host "Falta DATABASE_URL_SUPRA_PARTS (proyecto Neon nuevo inventario-sync-supra-parts)." -ForegroundColor Red
    exit 1
}

if (-not $JwtSecret) {
    $JwtSecret = "gq+q8D+VpXs5M2Gd5o15xobiS0B3x48hoi3foBxZSS7gXPyxyMKc+PtVJ2r8XlNR"
    Write-Host "Usando JWT_SECRET pregenerado." -ForegroundColor Yellow
}

$env:RENDER_API_KEY = $RenderApiKey
$env:JWT_SECRET = $JwtSecret

# Si Total Care aun no tiene Postgres, usa placeholder solo para crear servicio B;
# provision script requiere ambas URLs — usamos la de Supra para Total solo si no hay.
$dbA = $DatabaseUrlTotalCare
if (-not $dbA) {
    Write-Host "DATABASE_URL_TOTAL_CARE no indicada; solo se configurara Supra Parts + JWT en Total Care." -ForegroundColor Yellow
    & "$ScriptDir\provision-supra-parts-only.ps1" `
        -RenderApiKey $RenderApiKey `
        -DatabaseUrlSupraParts $DatabaseUrlSupraParts `
        -JwtSecret $JwtSecret `
        -SetJwtOnTotalCare
    exit $LASTEXITCODE
}

& "$ScriptDir\provision-multi-sucursal-render.ps1" `
    -RenderApiKey $RenderApiKey `
    -DatabaseUrlTotalCare $dbA `
    -DatabaseUrlSupraParts $DatabaseUrlSupraParts `
    -JwtSecret $JwtSecret `
    -AssignUsers `
    -OpenDashboard:$false
