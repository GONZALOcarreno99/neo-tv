# NEO TV · optimizar la TV desde una PC con Windows
# Uso:  powershell -ExecutionPolicy Bypass -File optimizar-pc.ps1 [-Ip 192.168.1.50] [-Revertir]
param([string]$Ip, [switch]$Revertir)
$ErrorActionPreference = "Stop"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Host "No encuentro 'adb'. Instala Android Platform Tools:" -ForegroundColor Red
    Write-Host "  winget install Google.PlatformTools   (o descárgalo de developer.android.com/tools/releases/platform-tools)"
    exit 1
}
if (-not $Ip) { $Ip = Read-Host "IP de la TV (Ajustes > Red > Wi-Fi en la TV)" }
$dev = "${Ip}:5555"

Write-Host "Conectando con $dev ... si en la TV aparece un aviso, elige 'Permitir siempre'."
adb connect $dev | Out-Host
for ($i = 0; $i -lt 30; $i++) {
    if ((adb -s $dev get-state 2>$null) -eq "device") { break }
    Start-Sleep 1
}
if ((adb -s $dev get-state 2>$null) -ne "device") { Write-Host "La TV no respondió. ¿Activaste la depuración por red?" -ForegroundColor Red; exit 1 }

adb -s $dev push "$PSScriptRoot\optimizar.sh" /data/local/tmp/optimizar.sh | Out-Null
function Tv([string]$cmd) { adb -s $dev shell "sh /data/local/tmp/optimizar.sh $cmd" 2>$null | Where-Object { $_ -match '\|' } }

if ($Revertir) {
    Tv "revertir" | ForEach-Object { Write-Host "  $_" }
    Write-Host "Listo: la TV quedó como antes de optimizar." -ForegroundColor Green
    exit 0
}

$apps = @(Tv "lista" | ForEach-Object {
    $p = $_ -split '\|', 5
    [pscustomobject]@{ Paquete = $p[0]; Grupo = $p[1]; Recomendada = $p[2] -eq "1"; Estado = $p[3]; Que = $p[4] }
})
$activas = @($apps | Where-Object Estado -eq "activa")
if (-not $activas) { Write-Host "No hay nada del catálogo activo en esta TV." }

$elegidas = @()
foreach ($a in $activas) {
    $def = if ($a.Recomendada) { "S" } else { "N" }
    $r = Read-Host ("¿Desactivar {0}? {1} [S/N, Enter = {2}]" -f $a.Paquete, $a.Que, $def)
    if (-not $r) { $r = $def }
    if ($r -match '^[sS]') { $elegidas += $a.Paquete }
}

Write-Host "`nAplicando..."
Tv ("aplicar " + ($elegidas -join " ")) | ForEach-Object {
    $p = $_ -split '\|'
    switch ($p[0]) {
        "OK"     { Write-Host "  desactivada  $($p[1])" -ForegroundColor Green }
        "FALLO"  { Write-Host "  no se pudo   $($p[1])" -ForegroundColor Yellow }
        "ANIM"   { Write-Host "  animaciones  0.5" }
        "CACHE"  { Write-Host "  caché        +$($p[1]) MB  ($($p[2]) MB libres)" }
    }
}
Write-Host "`nListo. Para deshacer todo:  .\optimizar-pc.ps1 -Ip $Ip -Revertir" -ForegroundColor Green
