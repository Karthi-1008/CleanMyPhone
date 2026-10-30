# Opens the game page in the TV's browser. Usage: .\open-on-tv.ps1 -TvHost 192.168.0.20:5555 -PhoneIp 192.168.0.7
param(
    [Parameter(Mandatory=$true)][string]$TvHost,
    [Parameter(Mandatory=$false)][string]$PhoneIp = "192.168.0.7"
)

Write-Host "Connecting to TV at $TvHost..." -ForegroundColor Cyan
adb connect $TvHost | Out-Null
Write-Host "Opening Lane Runner on TV browser (http://${PhoneIp}:8081/)..." -ForegroundColor Green
adb -s $TvHost shell am start -a android.intent.action.VIEW -d "http://${PhoneIp}:8081/"
