# Provisiona multi-sucursal en Render (plan free): crea/sincroniza servicios,
# configura DATABASE_URL + JWT_SECRET y asigna usuarios por sucursal.
#
# Uso:
#   $env:RENDER_API_KEY = "rnd_..."
#   powershell -ExecutionPolicy Bypass -File scripts\provision-multi-sucursal-render.ps1 `
#     -DatabaseUrlTotalCare "postgresql://..." `
#     -DatabaseUrlSupraParts "postgresql://..." `
#     -AssignUsers
#
# Si inventario-sync-supra-parts no existe, abre el blueprint para Sync manual
# o intenta forzar autoSync vía API.

param(
    [string]$RenderApiKey = $env:RENDER_API_KEY,
    [Parameter(Mandatory = $true)]
    [string]$DatabaseUrlTotalCare,
    [Parameter(Mandatory = $true)]
    [string]$DatabaseUrlSupraParts,
    [string]$JwtSecret = $env:JWT_SECRET,
    [switch]$AssignUsers,
    [switch]$OpenDashboard,
    [string]$ApiKey = "TcSync-7kM9pQ2xR4nW"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot

$ServiceA = "inventario-sync-totalcare"
$ServiceB = "inventario-sync-supra-parts"
$RepoName = "InventarioApp"

function New-RandomSecret {
    $bytes = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return [Convert]::ToBase64String($bytes)
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
    Write-Host "    $Key configurado" -ForegroundColor Green
}

function Start-RenderDeploy {
    param([string]$ServiceId, [string]$Name)
    try {
        $deploy = Invoke-RenderApi -Method POST -Path "/services/$ServiceId/deploys" -Body @{ clearCache = "do_not_clear" }
        Write-Host "  Deploy iniciado para $Name ($($deploy.id))" -ForegroundColor Green
        return $deploy.id
    } catch {
        Write-Host "  No se pudo iniciar deploy para $Name: $($_.Exception.Message)" -ForegroundColor Yellow
        return $null
    }
}

function Wait-RenderDeploy {
    param([string]$ServiceId, [string]$DeployId, [string]$Name, [int]$MaxAttempts = 40)
    if (-not $DeployId) { return }
    for ($i = 1; $i -le $MaxAttempts; $i++) {
        Start-Sleep -Seconds 15
        try {
            $d = Invoke-RenderApi -Path "/services/$ServiceId/deploys/$DeployId"
            $status = $d.status
            Write-Host "    $Name deploy: $status ($i/$MaxAttempts)" -ForegroundColor Gray
            if ($status -eq "live") { return }
            if ($status -in @("build_failed", "update_failed", "canceled")) {
                throw "Deploy $status"
            }
        } catch {
            Write-Host "    Error consultando deploy: $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    Write-Host "    Tiempo de espera agotado para $Name" -ForegroundColor Yellow
}

function Invoke-BlueprintAutoSync {
    try {
        $blueprints = Invoke-RenderApi -Path "/blueprints?limit=20"
        $bp = $blueprints | ForEach-Object { $_.blueprint } | Where-Object {
            $_.repo -match $RepoName -or $_.name -match "inventario"
        } | Select-Object -First 1
        if (-not $bp) {
            Write-Host "  No se encontró blueprint del repo. Sync manual en dashboard." -ForegroundColor Yellow
            return $false
        }
        Write-Host "  Blueprint: $($bp.name) ($($bp.id)) autoSync=$($bp.autoSync)" -ForegroundColor White
        if (-not $bp.autoSync) {
            Invoke-RenderApi -Method PATCH -Path "/blueprints/$($bp.id)" -Body @{ autoSync = $true } | Out-Null
            Write-Host "  autoSync habilitado (puede tardar unos minutos en crear sucursal B)" -ForegroundColor Green
        } else {
            Write-Host "  autoSync ya está activo; push a main debería sincronizar render.yaml" -ForegroundColor Gray
        }
        return $true
    } catch {
        Write-Host "  No se pudo ajustar blueprint: $($_.Exception.Message)" -ForegroundColor Yellow
        return $false
    }
}

function Test-BranchHealth {
    param([string]$BaseUrl)
    try {
        $r = Invoke-RestMethod -Uri "$($BaseUrl.TrimEnd('/'))/health" -TimeoutSec 120
        return [pscustomobject]@{ Ok = [bool]$r.ok; Backend = [string]$r.backend }
    } catch {
        return [pscustomobject]@{ Ok = $false; Backend = "unreachable" }
    }
}

if (-not $RenderApiKey) {
    Write-Host "RENDER_API_KEY requerida." -ForegroundColor Red
    Write-Host '  $env:RENDER_API_KEY = "rnd_..."' -ForegroundColor Gray
    if ($OpenDashboard) { Start-Process "https://dashboard.render.com/u/settings#api-keys" }
    exit 1
}

if (-not $JwtSecret) {
    $JwtSecret = New-RandomSecret
    Write-Host "JWT_SECRET generado (guárdalo para ambas instancias):" -ForegroundColor Yellow
    Write-Host "  $JwtSecret" -ForegroundColor White
}

Write-Host ""
Write-Host "=== Provision multi-sucursal Render ===" -ForegroundColor Cyan
Write-Host ""

try {
    $null = Invoke-RenderApi -Path "/services?limit=1"
} catch {
    Write-Host "RENDER_API_KEY inválida o sin permisos (HTTP 401/403)." -ForegroundColor Red
    Write-Host "Crea una nueva en Render -> Account Settings -> API Keys" -ForegroundColor Gray
    Write-Host "y actualiza el secret RENDER_API_KEY en GitHub." -ForegroundColor Gray
    if ($OpenDashboard) { Start-Process "https://dashboard.render.com/u/settings#api-keys" }
    exit 1
}

$services = Get-RenderServices
$svcA = $services | Where-Object { $_.name -eq $ServiceA } | Select-Object -First 1
$svcB = $services | Where-Object { $_.name -eq $ServiceB } | Select-Object -First 1

if (-not $svcA) {
    Write-Host "[ERROR] No existe $ServiceA. Crea el blueprint desde render.yaml." -ForegroundColor Red
    if ($OpenDashboard) { Start-Process "https://dashboard.render.com/select-repo?type=blueprint" }
    exit 1
}

if (-not $svcB) {
    Write-Host "[PENDIENTE] $ServiceB no existe aún." -ForegroundColor Yellow
    Invoke-BlueprintAutoSync | Out-Null
    if ($OpenDashboard) { Start-Process "https://dashboard.render.com/blueprints" }
    Write-Host "  Si no aparece en 2-3 min, en Render: Blueprints -> tu blueprint -> Manual Sync" -ForegroundColor Gray
    Write-Host "  Re-ejecuta este script cuando exista $ServiceB." -ForegroundColor Gray
    $services = Get-RenderServices
    $svcB = $services | Where-Object { $_.name -eq $ServiceB } | Select-Object -First 1
    if (-not $svcB) { exit 2 }
}

Write-Host "Configurando variables de entorno..." -ForegroundColor Yellow

Write-Host "  $ServiceA ($($svcA.id))" -ForegroundColor White
Set-RenderEnvVar -ServiceId $svcA.id -Key "DATABASE_URL" -Value $DatabaseUrlTotalCare
Set-RenderEnvVar -ServiceId $svcA.id -Key "JWT_SECRET" -Value $JwtSecret

Write-Host "  $ServiceB ($($svcB.id))" -ForegroundColor White
Set-RenderEnvVar -ServiceId $svcB.id -Key "DATABASE_URL" -Value $DatabaseUrlSupraParts
Set-RenderEnvVar -ServiceId $svcB.id -Key "JWT_SECRET" -Value $JwtSecret

Write-Host ""
Write-Host "Desplegando servicios..." -ForegroundColor Yellow
$deployA = Start-RenderDeploy -ServiceId $svcA.id -Name $ServiceA
$deployB = Start-RenderDeploy -ServiceId $svcB.id -Name $ServiceB
Wait-RenderDeploy -ServiceId $svcA.id -DeployId $deployA -Name $ServiceA
Wait-RenderDeploy -ServiceId $svcB.id -DeployId $deployB -Name $ServiceB

Write-Host ""
Write-Host "Verificando /health..." -ForegroundColor Yellow
$config = Get-Content (Join-Path $ProjectRoot "app\src\main\assets\sync_config.json") -Raw | ConvertFrom-Json
foreach ($branch in $config.branches) {
    $h = Test-BranchHealth -BaseUrl $branch.baseUrl
    if ($h.Ok -and $h.Backend -eq "postgres") {
        Write-Host "  [OK] $($branch.label) -> postgres" -ForegroundColor Green
    } elseif ($h.Ok) {
        Write-Host "  [AVISO] $($branch.label) -> backend $($h.Backend)" -ForegroundColor Yellow
    } else {
        Write-Host "  [FALTA] $($branch.label) no responde" -ForegroundColor Red
    }
}

if ($AssignUsers) {
    Write-Host ""
    & (Join-Path $PSScriptRoot "setup-multi-sucursal-free.ps1") -AssignUsers -ApiKey $ApiKey
}

Write-Host ""
Write-Host "Provision completado." -ForegroundColor Green
