# Provisiona solo inventario-sync-supra-parts y JWT en Total Care (sin DATABASE_URL en A si Neon agotado).

param(
    [Parameter(Mandatory = $true)]
    [string]$RenderApiKey,
    [Parameter(Mandatory = $true)]
    [string]$DatabaseUrlSupraParts,
    [string]$JwtSecret,
    [switch]$SetJwtOnTotalCare
)

$ErrorActionPreference = "Stop"
$ServiceA = "inventario-sync-totalcare"
$ServiceB = "inventario-sync-supra-parts"
$Jwt = $JwtSecret
if (-not $Jwt) {
    $Jwt = "gq+q8D+VpXs5M2Gd5o15xobiS0B3x48hoi3foBxZSS7gXPyxyMKc+PtVJ2r8XlNR"
}

$headers = @{
    Authorization = "Bearer $RenderApiKey"
    Accept = "application/json"
}

function Invoke-Render {
    param([string]$Method = "GET", [string]$Path, [object]$Body = $null)
    $uri = "https://api.render.com/v1$Path"
    if ($Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers `
            -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 5)
    }
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
}

function Set-Env {
    param([string]$ServiceId, [string]$Key, [string]$Value)
    $enc = [uri]::EscapeDataString($Key)
    Invoke-Render -Method PUT -Path "/services/$ServiceId/env-vars/$enc" -Body @{ value = $Value } | Out-Null
}

Write-Host "Listando servicios Render..." -ForegroundColor Cyan
$services = (Invoke-Render -Path "/services?limit=100") | ForEach-Object { $_.service }
$svcA = $services | Where-Object { $_.name -eq $ServiceA } | Select-Object -First 1
$svcB = $services | Where-Object { $_.name -eq $ServiceB } | Select-Object -First 1

if (-not $svcA) { throw "No existe $ServiceA" }

if (-not $svcB) {
    Write-Host "$ServiceB no existe. Sincroniza Blueprint en Render (repo ya en main)." -ForegroundColor Yellow
    Write-Host "  Dashboard -> Blueprints -> New -> InventarioApp -> Apply" -ForegroundColor Gray
    $bps = Invoke-Render -Path "/blueprints?limit=20"
    foreach ($item in $bps) {
        $bp = $item.blueprint
        if ($bp.repo -match "InventarioApp") {
            if (-not $bp.autoSync) {
                Invoke-Render -Method PATCH -Path "/blueprints/$($bp.id)" -Body @{ autoSync = $true } | Out-Null
                Write-Host "autoSync activado en blueprint $($bp.name). Espera 2-3 min y re-ejecuta." -ForegroundColor Yellow
            }
        }
    }
    exit 2
}

Write-Host "Configurando $ServiceB..." -ForegroundColor Yellow
Set-Env -ServiceId $svcB.id -Key "DATABASE_URL" -Value $DatabaseUrlSupraParts
Set-Env -ServiceId $svcB.id -Key "JWT_SECRET" -Value $Jwt

if ($SetJwtOnTotalCare) {
    Write-Host "Configurando JWT en $ServiceA (sin DATABASE_URL hasta cuota Neon)..." -ForegroundColor Yellow
    Set-Env -ServiceId $svcA.id -Key "JWT_SECRET" -Value $Jwt
}

foreach ($pair in @(@($svcB, $ServiceB), @($svcA, $ServiceA))) {
    $s, $name = $pair
    Write-Host "Deploy $name..." -ForegroundColor Gray
    Invoke-Render -Method POST -Path "/services/$($s.id)/deploys" -Body @{ clearCache = "do_not_clear" } | Out-Null
}

Write-Host "Esperando health (hasta 3 min)..." -ForegroundColor Cyan
Start-Sleep -Seconds 45
& "$PSScriptRoot\setup-multi-sucursal-free.ps1"
