# Configuración multi-sucursal en plan GRATUITO (Render free + Neon free).
# No requiere tarjeta. Dos servicios Render + una o dos bases Postgres en Neon.
#
# Uso:
#   powershell -ExecutionPolicy Bypass -File scripts\setup-multi-sucursal-free.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\setup-multi-sucursal-free.ps1 -AssignUsers

param(
    [switch]$AssignUsers,
    [string]$ApiKey = "TcSync-7kM9pQ2xR4nW"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ConfigPath = Join-Path $ProjectRoot "app\src\main\assets\sync_config.json"

function Get-Branches {
    if (-not (Test-Path $ConfigPath)) { throw "No se encontró $ConfigPath" }
    $json = Get-Content $ConfigPath -Raw | ConvertFrom-Json
    if (-not $json.branches) { throw "sync_config.json no tiene array branches" }
    return @($json.branches)
}

function Test-BranchHealth {
    param([string]$BaseUrl)
    $url = "$($BaseUrl.TrimEnd('/'))/health"
    try {
        $r = Invoke-RestMethod -Uri $url -TimeoutSec 90
        return [pscustomobject]@{
            Ok = [bool]$r.ok
            Backend = [string]$r.backend
            Url = $BaseUrl
        }
    } catch {
        return [pscustomobject]@{ Ok = $false; Backend = "unreachable"; Url = $BaseUrl; Error = $_.Exception.Message }
    }
}

function Invoke-AdminLogin {
    param([string]$BaseUrl, [string]$ApiKey)
    $body = '{"username":"admin","password":"admin"}'
    $headers = @{ "X-Api-Key" = $ApiKey; "Content-Type" = "application/json" }
    return Invoke-RestMethod -Method Post -Uri "$($BaseUrl.TrimEnd('/'))/v1/auth/login" -Headers $headers -Body $body -TimeoutSec 120
}

function Set-BranchUserSucursales {
    param([string]$BaseUrl, [string]$BranchLabel, [string]$ApiKey)
    $login = Invoke-AdminLogin -BaseUrl $BaseUrl -ApiKey $ApiKey
    $headers = @{
        "X-Api-Key" = $ApiKey
        "Authorization" = "Bearer $($login.token)"
        "Content-Type" = "application/json"
    }
    $users = (Invoke-RestMethod -Uri "$($BaseUrl.TrimEnd('/'))/v1/users" -Headers $headers -TimeoutSec 120).users
    foreach ($user in $users) {
        if ($user.role -in @("ADMIN", "SUPERVISOR")) { continue }
        if ([string]$user.sucursal -eq $BranchLabel) { continue }
        $patch = @{ sucursal = $BranchLabel } | ConvertTo-Json
        Invoke-RestMethod -Method Patch -Uri "$($BaseUrl.TrimEnd('/'))/v1/users/$($user.id)" -Headers $headers -Body $patch -TimeoutSec 120 | Out-Null
        Write-Host "  $($user.username) -> $BranchLabel" -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "=== Multi-sucursal (plan gratuito) ===" -ForegroundColor Cyan
Write-Host ""

$branches = Get-Branches
Write-Host "Sucursales en sync_config.json:" -ForegroundColor Yellow
foreach ($b in $branches) {
    Write-Host "  - $($b.label)  $($b.baseUrl)"
}
Write-Host ""

Write-Host "1) Verificando servidores..." -ForegroundColor Yellow
$allOk = $true
foreach ($b in $branches) {
    $h = Test-BranchHealth -BaseUrl $b.baseUrl
    if ($h.Ok) {
        $backendNote = if ($h.Backend -eq "postgres") { "OK (Postgres)" } else { "AVISO: backend $($h.Backend) - configura DATABASE_URL en Render" }
        Write-Host "  [OK] $($b.label): $backendNote" -ForegroundColor $(if ($h.Backend -eq "postgres") { "Green" } else { "Yellow" })
    } else {
        $allOk = $false
        Write-Host "  [FALTA] $($b.label): no responde en $($b.baseUrl)" -ForegroundColor Red
        if ($h.Error) { Write-Host "         $($h.Error)" -ForegroundColor DarkGray }
    }
}
Write-Host ""

if (-not $allOk) {
    Write-Host "Pasos para crear la instancia faltante (sin costo):" -ForegroundColor Yellow
    Write-Host "  1. Sube render.yaml a GitHub (rama main)." -ForegroundColor Gray
    Write-Host "  2. Render Dashboard -> Blueprints -> Sync / Apply changes." -ForegroundColor Gray
    Write-Host "     Se crea inventario-sync-sucursal-b automaticamente." -ForegroundColor Gray
    Write-Host "  3. Neon (gratis): en el MISMO proyecto crea una 2da base, p.ej. inventario_b." -ForegroundColor Gray
    Write-Host "     Usa connection strings distintas (mismo host, distinto nombre de DB)." -ForegroundColor Gray
    Write-Host "  4. En cada servicio Render -> Environment:" -ForegroundColor Gray
    Write-Host "       DATABASE_URL = connection string de Neon (una por sucursal)" -ForegroundColor Gray
    Write-Host "       JWT_SECRET   = cadena larga aleatoria (puede ser la misma en ambas)" -ForegroundColor Gray
    Write-Host "       FIREBASE_TOPIC ya viene en render.yaml por servicio." -ForegroundColor Gray
    Write-Host "  5. Vuelve a ejecutar este script." -ForegroundColor Gray
    Write-Host ""
}

if ($AssignUsers) {
    Write-Host "2) Asignando sucursal a usuarios Ventas/Consulta..." -ForegroundColor Yellow
    foreach ($b in $branches) {
        $h = Test-BranchHealth -BaseUrl $b.baseUrl
        if (-not $h.Ok) {
            Write-Host "  Omitiendo $($b.label) (servidor no disponible)" -ForegroundColor DarkYellow
            continue
        }
        Write-Host "  Instancia: $($b.label)" -ForegroundColor White
        try {
            Set-BranchUserSucursales -BaseUrl $b.baseUrl -BranchLabel $b.label -ApiKey $ApiKey
        } catch {
            Write-Host "  Error: $($_.Exception.Message)" -ForegroundColor Red
        }
    }
    Write-Host ""
}

Write-Host "3) Compilar APK (opcional):" -ForegroundColor Yellow
Write-Host "  cd $ProjectRoot" -ForegroundColor Gray
Write-Host "  .\gradlew.bat assembleRelease" -ForegroundColor Gray
Write-Host ""
Write-Host "Listo." -ForegroundColor Green
