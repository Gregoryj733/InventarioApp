$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SyncDir = Join-Path $ProjectRoot "sync-server"
$ConfigPath = Join-Path $ProjectRoot "app\src\main\assets\sync_config.json"

function Get-Cloudflared {
    if (Get-Command cloudflared -ErrorAction SilentlyContinue) {
        return "cloudflared"
    }
    $local = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Links\cloudflared.exe"
    if (Test-Path $local) { return $local }
    $wingetPath = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Cloudflare.cloudflared_Microsoft.Winget.Source_8wekyb3d8bbwe\cloudflared.exe"
    if (Test-Path $wingetPath) { return $wingetPath }
    return $null
}

Write-Host ""
Write-Host "=== Inventario Sync (servidor local + túnel HTTPS) ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "No requiere Fly.io ni tarjeta de crédito." -ForegroundColor Gray
Write-Host "Deja esta ventana abierta mientras uses la app." -ForegroundColor Gray
Write-Host ""

$cloudflared = Get-Cloudflared
if (-not $cloudflared) {
    Write-Host "cloudflared no encontrado. Instálalo:" -ForegroundColor Yellow
    Write-Host "  winget install Cloudflare.cloudflared" -ForegroundColor White
    exit 1
}

if (-not (Test-Path (Join-Path $SyncDir "node_modules"))) {
    Write-Host "Instalando dependencias del servidor..." -ForegroundColor Gray
    Set-Location $SyncDir
    npm install
}

$apiKey = "TcSync-7kM9pQ2xR4nW"
if (Test-Path $ConfigPath) {
    $json = Get-Content $ConfigPath -Raw | ConvertFrom-Json
    if ($json.apiKey) { $apiKey = [string]$json.apiKey }
}

$serverJob = Start-Job -ScriptBlock {
    param($dir, $key)
    Set-Location $dir
    $env:API_KEY = $key
    $env:PORT = "8787"
    node server.js
} -ArgumentList $SyncDir, $apiKey

Start-Sleep -Seconds 2

Write-Host "Iniciando túnel HTTPS (Cloudflare)..." -ForegroundColor Gray
Write-Host ""

& $cloudflared tunnel --url http://127.0.0.1:8787
