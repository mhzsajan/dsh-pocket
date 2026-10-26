# DSH Pocket - end-to-end test on the connected phone.
# Usage: pwsh -File dsh-pocket/tools/e2e.ps1 [-SkipBuild] [-Serial <serial>]
param(
    [switch]$SkipBuild,
    [int]$WaitSeconds = 240,
    [string]$Serial = $env:ANDROID_SERIAL
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$apk = Join-Path $root 'dsh-pocket\dist\DSH-Pocket.apk'
$outDir = Join-Path $root 'dsh-pocket\dist'

if (-not $Serial) {
    $Serial = ((adb devices) | Select-String '^\S+\s+device$' |
        ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1)
    if (-not $Serial) { throw 'No adb device connected. Pass -Serial <serial> or set ANDROID_SERIAL.' }
}

function Adb { & adb -s $Serial @args 2>&1 }

if (-not $SkipBuild) {
    Write-Host '== building APK ==' -ForegroundColor Cyan
    & pwsh -NoProfile -File (Join-Path $root 'dsh-pocket\build.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'build failed' }
}

if (-not (Test-Path $apk)) { throw "missing $apk" }

Write-Host '== installing ==' -ForegroundColor Cyan
Adb install -r $apk | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'install failed' }

Write-Host '== clearing old app state for a clean first-run test (keeps assets) ==' -ForegroundColor Cyan
Adb shell pm clear com.dshpocket | Out-Host

Write-Host '== launching ==' -ForegroundColor Cyan
Adb logcat -c
Adb shell am start -n com.dshpocket/.MainActivity | Out-Host
Start-Sleep -Seconds 3

Write-Host "== waiting up to $WaitSeconds s for DSH_READY ==" -ForegroundColor Cyan
$url = $null
$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Date) -lt $deadline) {
    $logs = Adb logcat -d -v brief
    $hit = $logs | Select-String -Pattern 'DSH_READY\s+(http\S+)' | Select-Object -Last 1
    if ($hit) { $url = $hit.Matches[0].Groups[1].Value; break }
    Start-Sleep -Seconds 5
    $tail = ($logs | Select-Object -Last 1)
    Write-Host ("  ... " + (Get-Date -Format 'HH:mm:ss') + "  " + $tail)
}

if (-not $url) {
    Write-Host 'TIMEOUT: no DSH_READY line. Dumping logcat for com.dshpocket:' -ForegroundColor Red
    Adb logcat -d | Select-String -Pattern 'DshPocket|AndroidRuntime|dshpocket' | Select-Object -Last 60 | ForEach-Object { $_.Line }
    & pwsh -NoProfile -File (Join-Path $root 'dsh-pocket\tools\shots.ps1') -Name 'e2e-timeout'
    throw 'DSH never became ready'
}

Write-Host "DSH_READY url: $url" -ForegroundColor Green
$port = ([uri]$url).Port

Write-Host '== checking HTTP from PC through adb forward ==' -ForegroundColor Cyan
Adb forward tcp:5399 tcp:$port | Out-Host
Start-Sleep -Seconds 1
try {
    $r = Invoke-WebRequest "http://127.0.0.1:5399/" -UseBasicParsing -MaximumRedirection 2 -ErrorAction Stop
    Write-Host "  HTTP $($r.StatusCode) len=$($r.RawContentLength)" -ForegroundColor Green
} catch {
    Write-Host "  HTTP check failed: $($_.Exception.Message)" -ForegroundColor Yellow
}
Adb forward --remove tcp:5399 | Out-Null

Write-Host '== screenshots ==' -ForegroundColor Cyan
& pwsh -NoProfile -File (Join-Path $root 'dsh-pocket\tools\shots.ps1') -Name 'e2e'
Write-Host '== done ==' -ForegroundColor Green
