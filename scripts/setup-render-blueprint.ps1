# Crea/sincroniza Blueprint Render para InventarioApp (inventario-sync-supra-parts).
# Requiere RENDER_API_KEY valida.
#
# Uso:
#   $env:RENDER_API_KEY = "rnd_..."
#   powershell -ExecutionPolicy Bypass -File scripts\setup-render-blueprint.ps1

param(
    [string]$RenderApiKey = $env:RENDER_API_KEY,
    [string]$Repo = "https://github.com/Gregoryj733/InventarioApp",
    [string]$Branch = "main",
    [string]$BlueprintPath = "render.yaml"
)

$ErrorActionPreference = "Stop"

if (-not $RenderApiKey) {
    Write-Host "Falta RENDER_API_KEY." -ForegroundColor Red
    Write-Host '  $env:RENDER_API_KEY = "rnd_..."' -ForegroundColor Gray
    exit 1
}

$headers = @{
    Authorization = "Bearer $RenderApiKey"
    Accept = "application/json"
    "Content-Type" = "application/json"
}

function Invoke-RenderApi {
    param([string]$Method, [string]$Path, [object]$Body = $null)
    $uri = "https://api.render.com/v1$Path"
    if ($Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -Body ($Body | ConvertTo-Json -Depth 6)
    }
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
}

Write-Host "Verificando API key..." -ForegroundColor Cyan
try {
    $probe = Invoke-WebRequest -Uri "https://api.render.com/v1/services?limit=1" -Headers @{
        Authorization = "Bearer $RenderApiKey"
        Accept = "application/json"
    } -UseBasicParsing
    if ($probe.StatusCode -ge 400) { throw "HTTP $($probe.StatusCode)" }
} catch {
    Write-Host "RENDER_API_KEY invalida (401/403). Crea una nueva en Render y actualiza GitHub Secrets." -ForegroundColor Red
    exit 1
}

Write-Host "Buscando blueprint de InventarioApp..." -ForegroundColor Cyan
$blueprints = Invoke-RenderApi -Method GET -Path "/blueprints?limit=50"
$bp = $blueprints | ForEach-Object { $_.blueprint } | Where-Object {
    ($_.repo -match "InventarioApp") -or ($_.name -match "inventario")
} | Select-Object -First 1

if (-not $bp) {
    Write-Host "No hay blueprint. Obteniendo ownerId..." -ForegroundColor Yellow
    $owners = Invoke-RenderApi -Method GET -Path "/owners?limit=20"
    $owner = $owners | ForEach-Object { $_.owner } | Select-Object -First 1
    if (-not $owner) { throw "No se encontro workspace en Render" }
    $ownerId = $owner.id
    Write-Host "Creando blueprint en owner $ownerId ..." -ForegroundColor Yellow
    try {
        $created = Invoke-RenderApi -Method POST -Path "/blueprints" -Body @{
            name = "InventarioApp"
            ownerId = $ownerId
            repo = $Repo
            branch = $Branch
            path = $BlueprintPath
            autoSync = $true
        }
        $bp = $created
        Write-Host "Blueprint creado: $($bp.id)" -ForegroundColor Green
    } catch {
        Write-Host "No se pudo crear blueprint por API: $($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host "Abre manualmente: https://dashboard.render.com/select-repo?type=blueprint" -ForegroundColor Gray
        Start-Process "https://dashboard.render.com/select-repo?type=blueprint"
        exit 2
    }
} else {
    Write-Host "Blueprint encontrado: $($bp.name) ($($bp.id))" -ForegroundColor Green
    if (-not $bp.autoSync) {
        Invoke-RenderApi -Method PATCH -Path "/blueprints/$($bp.id)" -Body @{ autoSync = $true } | Out-Null
        Write-Host "autoSync activado." -ForegroundColor Green
    }
}

Write-Host "Esperando servicios (60s)..." -ForegroundColor Cyan
Start-Sleep -Seconds 60

$services = (Invoke-RenderApi -Method GET -Path "/services?limit=100") | ForEach-Object { $_.service }
foreach ($name in @("inventario-sync-totalcare", "inventario-sync-supra-parts")) {
    $s = $services | Where-Object { $_.name -eq $name } | Select-Object -First 1
    if ($s) {
        Write-Host "  [OK] $name -> $($s.serviceDetails.url)" -ForegroundColor Green
    } else {
        Write-Host "  [FALTA] $name" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "Si inventario-sync-supra-parts sigue faltando, en Render: Blueprint -> Manual Sync" -ForegroundColor Gray
