# Dispara redeploy de ambos sync-servers en Render (plan free).
# Requiere secret RENDER_API_KEY en GitHub o variable de entorno local.
#
# Uso local (si tienes RENDER_API_KEY):
#   $env:RENDER_API_KEY = "rnd_..."
#   powershell -ExecutionPolicy Bypass -File scripts\deploy-multi-sucursal-render.ps1
#
# O desde GitHub Actions: workflow "Deploy Sync Server" tras push a main.

$ErrorActionPreference = "Stop"

$ServiceNames = @(
    "inventario-sync-totalcare",
    "inventario-sync-sucursal-b"
)

$apiKey = $env:RENDER_API_KEY
if (-not $apiKey) {
    Write-Host "RENDER_API_KEY no configurada." -ForegroundColor Yellow
    Write-Host "Opciones gratis:" -ForegroundColor Gray
    Write-Host "  - GitHub -> Actions -> Deploy Sync Server -> Run workflow" -ForegroundColor Gray
    Write-Host "  - Render Dashboard -> cada servicio -> Manual Deploy" -ForegroundColor Gray
    Write-Host "  - Tras push a main, Render Auto-Deploy redeploya si el blueprint ya está sincronizado." -ForegroundColor Gray
    exit 0
}

$headers = @{
    Authorization = "Bearer $apiKey"
    Accept = "application/json"
}

Write-Host "Buscando servicios en Render..." -ForegroundColor Cyan
$services = Invoke-RestMethod -Uri "https://api.render.com/v1/services?limit=100" -Headers $headers

foreach ($name in $ServiceNames) {
    $svc = $services | Where-Object { $_.service.name -eq $name } | Select-Object -First 1
    if (-not $svc) {
        Write-Host "[SKIP] $name no existe aún. Sincroniza render.yaml en Render Blueprints." -ForegroundColor Yellow
        continue
    }
    $id = $svc.service.id
    Write-Host "Desplegando $name ($id)..." -ForegroundColor White
    $body = '{"clearCache":"do_not_clear"}' 
    try {
        $deploy = Invoke-RestMethod -Method Post -Uri "https://api.render.com/v1/services/$id/deploys" -Headers $headers -ContentType "application/json" -Body $body
        Write-Host "  Deploy iniciado: $($deploy.id)" -ForegroundColor Green
    } catch {
        Write-Host "  Error: $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host "Hecho." -ForegroundColor Green
