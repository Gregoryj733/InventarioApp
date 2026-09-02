# Implementación completa multi-sucursal en Render (plan free, backend file).
# Requiere RENDER_API_KEY válida O Blueprint ya sincronizado manualmente.
#
# Uso:
#   $env:RENDER_API_KEY = "rnd_..."
#   powershell -ExecutionPolicy Bypass -File scripts\implement-render-services.ps1

param(
    [string]$RenderApiKey = $env:RENDER_API_KEY,
    [string]$JwtSecret = "gq+q8D+VpXs5M2Gd5o15xobiS0B3x48hoi3foBxZSS7gXPyxyMKc+PtVJ2r8XlNR"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Services = @("inventario-sync-totalcare", "inventario-sync-supra-parts")

function Test-RenderApiKey {
    param([string]$Key)
    if ([string]::IsNullOrWhiteSpace($Key)) { return $false }
    try {
        $r = Invoke-WebRequest -Uri "https://api.render.com/v1/services?limit=1" -Headers @{
            Authorization = "Bearer $Key"
            Accept = "application/json"
        } -UseBasicParsing
        return $r.StatusCode -lt 400
    } catch {
        return $false
    }
}

function Test-ServiceHealth {
    param([string]$BaseUrl)
    $url = "$($BaseUrl.TrimEnd('/'))/health"
    try {
        $r = Invoke-RestMethod -Uri $url -TimeoutSec 90
        return [pscustomobject]@{ Ok = [bool]$r.ok; Backend = [string]$r.backend; Url = $BaseUrl }
    } catch {
        return [pscustomobject]@{ Ok = $false; Backend = "unreachable"; Url = $BaseUrl }
    }
}

Write-Host ""
Write-Host "=== Implementacion Render (Supra Tool) ===" -ForegroundColor Cyan
Write-Host ""

if (-not (Test-RenderApiKey -Key $RenderApiKey)) {
    Write-Host "RENDER_API_KEY invalida o ausente." -ForegroundColor Red
    Write-Host "Abriendo Blueprint y API Keys en el navegador..." -ForegroundColor Yellow
    Start-Process "https://dashboard.render.com/select-repo?type=blueprint"
    Start-Process "https://dashboard.render.com/u/settings#api-keys"
    Write-Host ""
    Write-Host "Manual (3 min):" -ForegroundColor Yellow
    Write-Host "  1. Blueprint -> conectar Gregoryj733/InventarioApp -> Apply" -ForegroundColor Gray
    Write-Host "  2. JWT_SECRET = (ver render.yaml / README)" -ForegroundColor Gray
    Write-Host "  3. NO agregar DATABASE_URL (STORAGE_BACKEND=file en blueprint)" -ForegroundColor Gray
    Write-Host ""
    Write-Host "Luego crea API key y ejecuta de nuevo con RENDER_API_KEY." -ForegroundColor Gray
    exit 2
}

$env:RENDER_API_KEY = $RenderApiKey

Write-Host "1) Blueprint / servicios..." -ForegroundColor Yellow
& (Join-Path $PSScriptRoot "setup-render-blueprint.ps1") -RenderApiKey $RenderApiKey
if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 2) { exit $LASTEXITCODE }

Write-Host "2) STORAGE_BACKEND=file en ambas sucursales..." -ForegroundColor Yellow
& (Join-Path $PSScriptRoot "set-file-backend-render.ps1") -RenderApiKey $RenderApiKey
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "3) Esperando health (hasta 5 min)..." -ForegroundColor Yellow
$urls = @(
    "https://inventario-sync-totalcare.onrender.com",
    "https://inventario-sync-supra-parts.onrender.com"
)
$allOk = $false
for ($round = 1; $round -le 15; $round++) {
    $allOk = $true
    foreach ($u in $urls) {
        $h = Test-ServiceHealth -BaseUrl $u
        $color = if ($h.Ok -and $h.Backend -eq "file") { "Green" } else { "Yellow" }
        Write-Host "  $u -> backend=$($h.Backend)" -ForegroundColor $color
        if (-not ($h.Ok -and $h.Backend -eq "file")) { $allOk = $false }
    }
    if ($allOk) { break }
    Start-Sleep -Seconds 20
}

Write-Host ""
if ($allOk) {
    Write-Host "Listo: ambas sucursales en backend file." -ForegroundColor Green
    exit 0
}

Write-Host "Deploy en progreso o variables pendientes. Revisa Render Dashboard." -ForegroundColor Yellow
exit 1
