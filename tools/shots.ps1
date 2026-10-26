# Screenshot helper: adb exec-out screencap with correct binary redirection.
# Usage: pwsh -File tools/shots.ps1 [-Name shot] [-Serial <serial>]
# Serial resolution: -Serial, else $env:ANDROID_SERIAL, else the only connected device.
param([string]$Name = 'shot', [string]$Serial = $env:ANDROID_SERIAL)
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$outDir = Join-Path $root 'dsh-pocket\dist'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

if (-not $Serial) {
    $Serial = ((adb devices) | Select-String '^\S+\s+device$' |
        ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1)
    if (-not $Serial) { throw 'No adb device connected. Pass -Serial <serial> or set ANDROID_SERIAL.' }
}

$png = Join-Path $outDir ("{0}-{1}.png" -f $Name, (Get-Date -Format 'HHmmss'))
# PowerShell '>' corrupts binary output -> use cmd redirection
& cmd /c "adb -s $Serial exec-out screencap -p > `"$png`""
Write-Host "screenshot: $png" -ForegroundColor Cyan
