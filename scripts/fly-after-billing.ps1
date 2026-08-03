$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$FlyExe = Join-Path $env:USERPROFILE ".fly\bin\flyctl.exe"

Write-Host ""
Write-Host "=== Estado Fly.io ===" -ForegroundColor Cyan
Write-Host ""

if (-not (Test-Path $FlyExe)) {
    Write-Host "flyctl no instalado." -ForegroundColor Red
    exit 1
}

& $FlyExe auth whoami
Write-Host ""
Write-Host "Login: OK" -ForegroundColor Green
Write-Host ""
Write-Host "Para desplegar el servidor, Fly.io pide verificar un método de pago" -ForegroundColor Yellow
Write-Host "(plan gratuito; no cobra sin uso significativo)." -ForegroundColor Gray
Write-Host ""
Write-Host "1. Abre: https://fly.io/dashboard/total-care-automotriz/billing" -ForegroundColor White
Write-Host "2. Agrega tarjeta o crédito" -ForegroundColor White
Write-Host "3. Ejecuta: scripts\deploy-fly.ps1" -ForegroundColor White
Write-Host ""

$billing = Read-Host "¿Ya agregaste el método de pago? (s/n)"
if ($billing -match '^[sS]') {
    Write-Host ""
    Write-Host "Desplegando..." -ForegroundColor Cyan
    & powershell -ExecutionPolicy Bypass -File (Join-Path $ProjectRoot "scripts\deploy-fly.ps1")
}
