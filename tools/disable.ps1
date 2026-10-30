# CleanMyPhone: Disable bloatware packages safely on Android Phone via ADB
# Android 6.0.1 (API 23) compatible using: pm uninstall -k --user 0 <pkg>
# 
# Usage: .\tools\disable.ps1 -PhoneHost 192.168.0.7:5555 -List .\devices\lyf-ls4508\debloat.txt

param(
    [Parameter(Mandatory = $false, Position = 0)][string]$List = ".\devices\lyf-ls4508\debloat.txt",
    [string]$PhoneHost = $env:PHONE_HOST
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false

if (-not $PhoneHost) {
    $PhoneHost = "192.168.0.7:5555"
}

if (-not (Test-Path $List)) {
    throw "Debloat list file not found: $List"
}

$List = (Resolve-Path $List).Path
Write-Host "Connecting to $PhoneHost..." -ForegroundColor Cyan
adb connect $PhoneHost | Out-Null

$devices = (adb devices) -join "`n"
if ($devices -notmatch [regex]::Escape($PhoneHost)) {
    throw "Device $PhoneHost is not connected or authorized via ADB."
}

# Core phone packages that MUST NEVER be touched (system stability, calls, SMS, emergency, settings)
$coreGuards = @(
    '^android$',
    '^com\.android\.systemui$',
    '^com\.android\.shell$',
    '^com\.android\.phone$',
    '^com\.android\.server\.telecom$',
    '^com\.android\.settings$',
    '^com\.android\.packageinstaller$',
    '^com\.google\.android\.packageinstaller$',
    '^com\.android\.defcontainer$',
    '^com\.android\.keychain$',
    '^com\.android\.certinstaller$',
    '^com\.android\.providers\.telephony$',
    '^com\.android\.providers\.settings$',
    '^com\.android\.providers\.media$',
    '^com\.android\.providers\.downloads$',
    '^com\.qualcomm\.qcrilmsgtunnel$',
    '^org\.codeaurora\.ims$'
)

# Load full APK mapping from backup
$apkMap = @{}
$backupMapPath = ".\backup\packages_all.txt"
if (Test-Path $backupMapPath) {
    $mapLines = Get-Content $backupMapPath -Encoding UTF8
    foreach ($line in $mapLines) {
        if ($line -match '^package:(.+)=([a-zA-Z0-9_\.]+)$') {
            $apkPath = $matches[1]
            $pkgName = $matches[2]
            $apkMap[$pkgName] = $apkPath
        }
    }
}

# Read package list
$rawLines = Get-Content $List
$pkgsToDisable = @()
foreach ($line in $rawLines) {
    $clean = ($line -replace '#.*$', '').Trim()
    if ($clean) {
        $pkgName = ($clean -split '\s+')[0]
        if ($pkgName -match '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$') {
            $isCore = $false
            foreach ($g in $coreGuards) {
                if ($pkgName -match $g) {
                    $isCore = $true
                    break
                }
            }
            if ($isCore) {
                Write-Warning "Refusing to disable critical system package: $pkgName"
            } else {
                $pkgsToDisable += $pkgName
            }
        }
    }
}

if ($pkgsToDisable.Count -eq 0) {
    Write-Warning "No valid package names found in $List."
    exit 0
}

Write-Host "`nFound $($pkgsToDisable.Count) packages to process from $List" -ForegroundColor Yellow

# Query currently active packages (non-uninstalled)
$activePkgs = (adb -s $PhoneHost shell pm list packages) | ForEach-Object { $_ -replace '^package:', '' }

$record = @{
    Timestamp = (Get-Date).ToString("o")
    Device = $PhoneHost
    DisabledPackages = @()
}

$successCount = 0
$alreadyGoneCount = 0

foreach ($pkg in $pkgsToDisable) {
    if ($activePkgs -notcontains $pkg) {
        Write-Host "  [-] Already disabled/uninstalled: $pkg" -ForegroundColor DarkGray
        $alreadyGoneCount++
        continue
    }

    Write-Host "  [x] Disabling: $pkg ... " -NoNewline
    $res = adb -s $PhoneHost shell "pm uninstall -k --user 0 $pkg" 2>&1
    if ($res -match 'Success') {
        Write-Host "DISABLED (Success)" -ForegroundColor Green
        $apk = if ($apkMap.ContainsKey($pkg)) { $apkMap[$pkg] } else { "" }
        $record.DisabledPackages += @{ Package = $pkg; ApkPath = $apk }
        $successCount++
    } else {
        Write-Host "FAILED ($res)" -ForegroundColor Red
    }
}

adb -s $PhoneHost shell sync

$recordPath = ".\backup\last_disabled_record.json"
$record | ConvertTo-Json -Depth 4 | Set-Content -Path $recordPath -Encoding UTF8

Write-Host "`nDebloat summary:" -ForegroundColor Cyan
Write-Host "  Newly disabled: $successCount"
Write-Host "  Already disabled/uninstalled: $alreadyGoneCount"
Write-Host "  Recovery record saved to: $recordPath" -ForegroundColor Green
Write-Host "Done! Run .\tools\restore.ps1 to restore any package.`n" -ForegroundColor Yellow
