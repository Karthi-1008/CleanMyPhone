# CleanMyPhone: Start TV Webcam Server & Motion Game on Phone and stream to Android TV
# Usage: .\tools\tv-webcam.ps1 [-TvHost 192.168.0.X:5555] [-Game]

param(
    [string]$PhoneHost = "192.168.0.7:5555",
    [string]$TvHost = "",
    [switch]$Game
)

$ErrorActionPreference = 'Continue'

Write-Host "`n=======================================================" -ForegroundColor Cyan
Write-Host "  CleanMyPhone - TV Wireless Webcam & Lane Runner Game  " -ForegroundColor Cyan
Write-Host "=======================================================`n" -ForegroundColor Cyan

Write-Host "Ensuring camera permissions and waking phone..." -ForegroundColor Gray
adb -s $PhoneHost shell pm grant com.cleanmyphone.launcher android.permission.CAMERA 2>$null
adb -s $PhoneHost shell pm grant com.cleanmyphone.launcher android.permission.RECORD_AUDIO 2>$null

Write-Host "Starting TV Camera & Game Server on $PhoneHost..." -ForegroundColor Yellow
adb -s $PhoneHost shell am start -n com.cleanmyphone.launcher/.TvCamActivity | Out-Null

$phoneIp = ($PhoneHost -split ':')[0]
$streamUrl = "http://${phoneIp}:8080"
$gameUrl = "http://${phoneIp}:8081"

Write-Host "`nServers are live and running on phone:" -ForegroundColor Green
Write-Host "  📹 Video Stream:  $streamUrl" -ForegroundColor White -BackgroundColor DarkGreen
Write-Host "  🎮 Motion Game:   $gameUrl" -ForegroundColor White -BackgroundColor DarkMagenta

Write-Host "`nFeatures:" -ForegroundColor Cyan
Write-Host "  • Sub-50ms local Wi-Fi MJPEG video feed"
Write-Host "  • 3-Lane Motion Runner game served on port 8081"
Write-Host "  • Tap '🌙 Dim' on the phone to turn screen dark and save battery"
Write-Host "  • Tap '🎯 Calibrate' to re-calibrate standing player baseline"
Write-Host "  • Tap '🔄 Flip' to toggle between Front and Back camera"
Write-Host "  • Tap '🔦 Light' to turn on camera flashlight"

if ($TvHost) {
    Write-Host "`nConnecting to your TV at $TvHost..." -ForegroundColor Cyan
    adb connect $TvHost | Out-Null
    $targetUrl = if ($Game) { $gameUrl } else { $streamUrl }
    adb -s $TvHost shell am start -a android.intent.action.VIEW -d $targetUrl
    Write-Host "Successfully launched $(if ($Game) {'Lane Runner Game'} else {'Camera Stream'}) on TV ($TvHost)!" -ForegroundColor Green
} else {
    Write-Host "`nTo open on your TV:" -ForegroundColor Yellow
    Write-Host "  1. Open any browser on your TV (or computer on same Wi-Fi)"
    Write-Host "  2. Go to: $gameUrl for the Lane Runner game"
    Write-Host "        or: $streamUrl for pure camera stream"
    Write-Host "  (Or run: .\tools\tv-webcam.ps1 -TvHost <tv_ip>:5555 -Game to launch game automatically!)`n"
}
