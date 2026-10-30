# CleanMyPhone: Restore/Re-enable packages on Android Phone via ADB
# Android 6.0.1 (API 23) compatible using: pm install -r -g --user 0 <apkPath>
# 
# Usage: .\tools\restore.ps1 -PhoneHost 192.168.0.7:5555 [-List .\devices\lyf-ls4508\debloat.txt]

param(
    [Parameter(Mandatory = $false, Position = 0)][string]$List = ".\devices\lyf-ls4508\debloat.txt",
    [string]$PhoneHost = $env:PHONE_HOST
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false

if (-not $PhoneHost) {
    $PhoneHost = "192.168.0.7:5555"
}

Write-Host "Connecting to $PhoneHost..." -ForegroundColor Cyan
adb connect $PhoneHost | Out-Null

# Load APK mapping from backup
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

# Determine packages to restore
$pkgsToRestore = @()
$recordPath = ".\backup\last_disabled_record.json"

if (Test-Path $recordPath) {
    try {
        $rec = Get-Content $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($item in $rec.DisabledPackages) {
            if ($item.Package) {
                $pkgsToRestore += $item.Package
            }
        }
    } catch {
        # Fall back to List
    }
}

if ($pkgsToRestore.Count -eq 0) {
    if (Test-Path $List) {
        $rawLines = Get-Content $List
        foreach ($line in $rawLines) {
            $clean = ($line -replace '#.*$', '').Trim()
            if ($clean) {
                $pkgName = ($clean -split '\s+')[0]
                if ($pkgName -match '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$') {
                    $pkgsToRestore += $pkgName
                }
            }
        }
    }
}

Write-Host "Restoring $($pkgsToRestore.Count) packages on $PhoneHost..." -ForegroundColor Yellow

$restored = 0
foreach ($pkg in $pkgsToRestore) {
    if ($apkMap.ContainsKey($pkg)) {
        $apkPath = $apkMap[$pkg]
        Write-Host "  [+] Restoring $pkg ($apkPath) ... " -NoNewline
        $res = adb -s $PhoneHost shell "pm install -r -g --user 0 $apkPath" 2>&1
        if ($res -match 'Success') {
            Write-Host "RESTORED" -ForegroundColor Green
            $restored++
        } else {
            Write-Host "FAILED ($res)" -ForegroundColor Red
        }
    } else {
        Write-Host "  [?] No APK path known for $pkg, trying pm enable ... " -NoNewline
        $res = adb -s $PhoneHost shell "pm enable $pkg" 2>&1
        Write-Host "$res" -ForegroundColor DarkGray
    }
}

adb -s $PhoneHost shell sync
Write-Host "`nRestore complete. $restored packages restored.`n" -ForegroundColor Green
