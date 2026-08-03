$projectId = "total-33a68"
$packageName = "com.inventario.app"

Write-Host ""
Write-Host "=== Configurar Firebase para InventarioApp ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "PASO 1: Se abrira Firebase Console en tu navegador." -ForegroundColor Yellow
Write-Host "        Si no tienes app Android, haz clic en el icono Android y registra:" -ForegroundColor Yellow
Write-Host "        Package: $packageName" -ForegroundColor Green
Write-Host ""
Write-Host "PASO 2: En la pantalla de registro, descarga google-services.json" -ForegroundColor Yellow
Write-Host ""
Write-Host "PASO 3: En la app (como Administrador), toca 'Configurar sincronizacion'" -ForegroundColor Yellow
Write-Host "        y pega el contenido del archivo descargado." -ForegroundColor Yellow
Write-Host ""
Write-Host "PASO 4: Cierra y vuelve a abrir la app." -ForegroundColor Yellow
Write-Host ""

Start-Process "https://console.firebase.google.com/project/$projectId/settings/general"

Start-Sleep -Seconds 2
Start-Process "https://console.firebase.google.com/project/$projectId/overview"

Write-Host "Listo. Sigue los pasos en el navegador." -ForegroundColor Green
Write-Host ""
