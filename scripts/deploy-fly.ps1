$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SyncDir = Join-Path $ProjectRoot "sync-server"
$ConfigPath = Join-Path $ProjectRoot "app\src\main\assets\sync_config.json"
$AppName = "inventario-sync-totalcare"
$Region = "mia"
$VolumeName = "inventario_data"

function Get-Flyctl {
    if (Get-Command flyctl -ErrorAction SilentlyContinue) {
        return "flyctl"
    }
    if (Get-Command fly -ErrorAction SilentlyContinue) {
        return "fly"
    }
    $localFly = Join-Path $env:USERPROFILE ".fly\bin\flyctl.exe"
    if (Test-Path $localFly) {
        return $localFly
    }
    return $null
}

function Invoke-Fly {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$FlyArgs)
    & $FlyExe @FlyArgs
    if ($LASTEXITCODE -ne 0) {
        throw "flyctl $($FlyArgs -join ' ') falló (código $LASTEXITCODE)"
    }
}

function Read-SyncConfig {
    if (-not (Test-Path $ConfigPath)) {
        return @{
            baseUrl = "https://$AppName.fly.dev"
            apiKey  = "inventario-sync-key"
        }
    }
    $json = Get-Content $ConfigPath -Raw | ConvertFrom-Json
    return @{
        baseUrl = [string]$json.baseUrl
        apiKey  = [string]$json.apiKey
    }
}

Write-Host ""
Write-Host "=== Despliegue Inventario Sync en Fly.io ===" -ForegroundColor Cyan
Write-Host ""

$FlyExe = Get-Flyctl
if (-not $FlyExe) {
    Write-Host "flyctl no está instalado. Instalando..." -ForegroundColor Yellow
    iwr https://fly.io/install.ps1 -useb | iex
    $FlyExe = Get-Flyctl
    if (-not $FlyExe) {
        Write-Host "No se pudo instalar flyctl. Reinicia PowerShell e intenta de nuevo." -ForegroundColor Red
        exit 1
    }
}

$syncConfig = Read-SyncConfig
$apiKey = $syncConfig.apiKey
$baseUrl = $syncConfig.baseUrl.TrimEnd('/')

Write-Host "App:     $AppName" -ForegroundColor Gray
Write-Host "URL:     $baseUrl" -ForegroundColor Gray
Write-Host "API key: $apiKey (desde sync_config.json)" -ForegroundColor Gray
Write-Host ""

Set-Location $SyncDir

Write-Host "Verificando sesión en Fly.io..." -ForegroundColor Gray
$whoamiOk = $false
try {
    Invoke-Fly auth whoami
    $whoamiOk = $true
} catch {
    $whoamiOk = $false
}

if (-not $whoamiOk) {
    Write-Host ""
    Write-Host ">>> Inicia sesión en Fly.io (se abrirá el navegador)." -ForegroundColor Yellow
    Write-Host ">>> Completa el login y vuelve a esta ventana." -ForegroundColor Yellow
    Write-Host ""
    Invoke-Fly auth login
}

if (-not (Test-Path "package-lock.json")) {
    Write-Host "Generando package-lock.json..." -ForegroundColor Gray
    npm install
}

$appExists = $false
$appsOutput = & $FlyExe apps list 2>&1 | Out-String
if ($appsOutput -match $AppName) {
    $appExists = $true
}

if (-not $appExists) {
    Write-Host "Creando app '$AppName'..." -ForegroundColor Gray
    Invoke-Fly apps create $AppName
}

$volumesOutput = & $FlyExe volumes list -a $AppName 2>&1 | Out-String
if ($volumesOutput -notmatch $VolumeName) {
    Write-Host "Creando volumen persistente '$VolumeName' en región $Region..." -ForegroundColor Gray
    Invoke-Fly volumes create $VolumeName --region $Region --size 1 -a $AppName
} else {
    Write-Host "Volumen '$VolumeName' ya existe." -ForegroundColor Gray
}

Write-Host "Configurando secreto API_KEY..." -ForegroundColor Gray
Invoke-Fly secrets set "API_KEY=$apiKey" -a $AppName

Write-Host ""
Write-Host "Desplegando (puede tardar 1-2 minutos)..." -ForegroundColor Cyan
Invoke-Fly deploy -a $AppName

Write-Host ""
Write-Host "Verificando /health..." -ForegroundColor Gray
Start-Sleep -Seconds 5
try {
    $health = Invoke-RestMethod -Uri "$baseUrl/health" -TimeoutSec 30
    if ($health.ok) {
        Write-Host "Servidor respondiendo correctamente." -ForegroundColor Green
    }
} catch {
    Write-Host "El deploy terminó pero /health aún no responde. Espera 30 s y prueba:" -ForegroundColor Yellow
    Write-Host "  curl $baseUrl/health" -ForegroundColor Gray
}

Write-Host ""
Write-Host "=== Despliegue completado ===" -ForegroundColor Green
Write-Host ""
Write-Host "URL del servidor:  $baseUrl" -ForegroundColor White
Write-Host "Clave API:         $apiKey" -ForegroundColor White
Write-Host ""
Write-Host "La app ya tiene estos valores en app/src/main/assets/sync_config.json" -ForegroundColor Yellow
Write-Host "Recompila el APK (gradlew assembleDebug) e instálalo en los celulares." -ForegroundColor Yellow
Write-Host ""
Write-Host "O en cada celular: Configurar sincronización con la URL y clave de arriba." -ForegroundColor Gray
Write-Host ""
