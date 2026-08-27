# Fuerza backend file en ambas instancias Render (plan free, datos efímeros).
# Quita DATABASE_URL de Supra Parts si existía (postgres) y define STORAGE_BACKEND=file.
#
# Uso:
#   $env:RENDER_API_KEY = "rnd_..."
#   powershell -ExecutionPolicy Bypass -File scripts\set-file-backend-render.ps1

param(
    [string]$RenderApiKey = $env:RENDER_API_KEY
)

$ErrorActionPreference = "Stop"
$Services = @("inventario-sync-totalcare", "inventario-sync-supra-parts")

if ([string]::IsNullOrWhiteSpace($RenderApiKey)) {
    Write-Host "Falta RENDER_API_KEY." -ForegroundColor Red
    Write-Host ""
    Write-Host "Manual en Render Dashboard (cada servicio):" -ForegroundColor Yellow
    Write-Host "  1. Environment -> agregar STORAGE_BACKEND = file" -ForegroundColor Gray
    Write-Host "  2. Eliminar DATABASE_URL si existe (especialmente en Supra Parts)" -ForegroundColor Gray
    Write-Host "  3. Manual Deploy" -ForegroundColor Gray
    Write-Host ""
    Write-Host "Verificar:" -ForegroundColor Yellow
    Write-Host "  powershell -ExecutionPolicy Bypass -File scripts\setup-multi-sucursal-free.ps1" -ForegroundColor Gray
    exit 1
}

function Invoke-RenderApi {
    param(
        [string]$Method = "GET",
        [string]$Path,
        [object]$Body = $null
    )
    $headers = @{
        Authorization = "Bearer $RenderApiKey"
        Accept = "application/json"
    }
    $uri = "https://api.render.com/v1$Path"
    if ($null -ne $Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 6)
    }
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
}

function Get-RenderServices {
    $all = @()
    $cursor = $null
    do {
        $qs = if ($cursor) { "?limit=100&cursor=$cursor" } else { "?limit=100" }
        $page = Invoke-RenderApi -Path "/services$qs"
        $all += $page
        $cursor = ($page | Select-Object -Last 1).cursor
        if ($page.Count -lt 100) { break }
    } while ($cursor)
    return $all | ForEach-Object { $_.service } | Where-Object { $_ }
}

function Set-RenderEnvVar {
    param([string]$ServiceId, [string]$Key, [string]$Value)
    $encodedKey = [uri]::EscapeDataString($Key)
    Invoke-RenderApi -Method PUT -Path "/services/$ServiceId/env-vars/$encodedKey" -Body @{ value = $Value } | Out-Null
    Write-Host "    $Key = $Value" -ForegroundColor Green
}

function Remove-RenderEnvVar {
    param([string]$ServiceId, [string]$Key)
    $encodedKey = [uri]::EscapeDataString($Key)
    try {
        Invoke-RenderApi -Method DELETE -Path "/services/$ServiceId/env-vars/$encodedKey" | Out-Null
        Write-Host "    $Key eliminada" -ForegroundColor Yellow
    } catch {
        Write-Host "    $Key (no existía o no se pudo eliminar)" -ForegroundColor DarkGray
    }
}

function Start-RenderDeploy {
    param([string]$ServiceId, [string]$Name)
    try {
        $deploy = Invoke-RenderApi -Method POST -Path "/services/$ServiceId/deploys" -Body @{ clearCache = "do_not_clear" }
        Write-Host "  Deploy iniciado: $Name ($($deploy.id))" -ForegroundColor Green
    } catch {
        Write-Host "  No se pudo iniciar deploy de $Name : $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "=== Backend file en ambas sucursales ===" -ForegroundColor Cyan
Write-Host ""

$byName = @{}
foreach ($svc in Get-RenderServices) {
    $byName[$svc.name] = $svc
}

foreach ($name in $Services) {
    $svc = $byName[$name]
    if (-not $svc) {
        Write-Host "[FALTA] $name no existe en Render" -ForegroundColor Red
        continue
    }
    Write-Host "Configurando $name ..." -ForegroundColor White
    Set-RenderEnvVar -ServiceId $svc.id -Key "STORAGE_BACKEND" -Value "file"
    Remove-RenderEnvVar -ServiceId $svc.id -Key "DATABASE_URL"
    Start-RenderDeploy -ServiceId $svc.id -Name $name
}

Write-Host ""
Write-Host "Espera 1-2 min y ejecuta:" -ForegroundColor Yellow
Write-Host "  powershell -ExecutionPolicy Bypass -File scripts\setup-multi-sucursal-free.ps1" -ForegroundColor Gray
Write-Host ""
