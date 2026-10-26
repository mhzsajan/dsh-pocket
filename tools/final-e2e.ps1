# Independent cold-start verification of the shipped DSH-Pocket.apk
# Usage: pwsh -File tools/final-e2e.ps1 [-Serial <serial>] [-TimeoutSeconds 240]
param([string]$Serial = $env:ANDROID_SERIAL, [int]$TimeoutSeconds = 240)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$dist = Join-Path $root 'dsh-pocket\dist'

if (-not $Serial) {
    $Serial = ((adb devices) | Select-String '^\S+\s+device$' |
        ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1)
    if (-not $Serial) { throw 'No adb device connected. Pass -Serial <serial> or set ANDROID_SERIAL.' }
}

function A([string[]]$a) { & adb -s $Serial @a 2>&1 }

Write-Host '== force-stop + clear data (cold install state) =='
A @('shell','am','force-stop','com.dshpocket') | Out-Null
A @('shell','pm','clear','com.dshpocket') | Out-Null
A @('shell','pm','grant','com.dshpocket','android.permission.READ_EXTERNAL_STORAGE') | Out-Null
A @('shell','pm','grant','com.dshpocket','android.permission.WRITE_EXTERNAL_STORAGE') | Out-Null

Write-Host '== launch =='
A @('logcat','-c') | Out-Null
$t0 = Get-Date
A @('shell','am','start','-n','com.dshpocket/.MainActivity') | Out-Null

Write-Host "== waiting for DSH_READY (max ${TimeoutSeconds}s) =="
$url = $null
$extractDone = $null
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    $logs = (A @('logcat','-d','-v','brief')) -join "`n"
    if (-not $extractDone -and $logs -match 'rootfs extracted in (\d+) ms') { $extractDone = "extract=$($Matches[1])ms ($([math]::Round(((Get-Date)-$t0).TotalSeconds,1))s after launch)" }
    if ($logs -match 'DSH_READY\s+(http\S+)') { $url = $Matches[1]; break }
    Start-Sleep -Seconds 4
}
$elapsed = [math]::Round(((Get-Date)-$t0).TotalSeconds,1)
if (-not $url) {
    Write-Host 'TIMEOUT. Last DshPocket lines:' -ForegroundColor Red
    (A @('logcat','-d')) | Select-String -Pattern 'DshPocket|proot error|EACCES' | Select-Object -Last 40 | ForEach-Object { $_.Line }
    exit 1
}
Write-Host "extraction: $extractDone"
Write-Host "DSH_READY after ${elapsed}s: $url" -ForegroundColor Green

$u = [uri]$url
$port = $u.Port
Write-Host '== HTTP checks through adb forward =='
A @('forward','tcp:5398',"tcp:$port") | Out-Null
Start-Sleep -Seconds 1
try {
    $r = Invoke-WebRequest "http://127.0.0.1:5398/" -UseBasicParsing -ErrorAction Stop
    Write-Host "GET / (no token): $($r.StatusCode)" -ForegroundColor Yellow
} catch {
    Write-Host "GET / (no token): $($_.Exception.Response.StatusCode.value__) (auth fence, expected 401)"
}
$token = ($u.Query -replace '^\?token=','')
try {
    $r2 = Invoke-WebRequest "http://127.0.0.1:5398/?token=$token" -UseBasicParsing -MaximumRedirection 0 -ErrorAction Stop
    Write-Host "GET /?token: $($r2.StatusCode)"
} catch {
    $resp = $_.Exception.Response
    if ($resp) { Write-Host "GET /?token: $($resp.StatusCode.value__) -> redirect (expected 303)" }
    else { Write-Host "GET /?token failed: $($_.Exception.Message)" }
}
A @('forward','--remove','tcp:5398') | Out-Null

Write-Host '== foreground service state =='
(A @('shell','dumpsys','activity','services','com.dshpocket')) | Select-String -Pattern 'isForeground|ServiceRecord|startForeground' | Select-Object -First 6 | ForEach-Object { $_.Line }

Write-Host '== screenshots =='
& cmd /c "adb -s $Serial exec-out screencap -p > `"$dist\final-launch.png`"" 2>$null
Start-Sleep -Seconds 4
& cmd /c "adb -s $Serial exec-out screencap -p > `"$dist\final-webview.png`"" 2>$null
Write-Host "screenshots: $dist\final-launch.png , $dist\final-webview.png"

A @('shell','dumpsys','activity','activities') | Select-String -Pattern 'topResumedActivity' | Select-Object -First 1 | ForEach-Object { $_.Line }

Write-Host '== log excerpt =='
(A @('logcat','-d')) | Select-String -Pattern 'DshPocket' | Select-Object -Last 12 | ForEach-Object { $_.Line }
Write-Host 'DONE' -ForegroundColor Green
