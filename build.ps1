# DSH Pocket - deterministic manual APK build (no Gradle, no AndroidX).
#
#   pwsh -File dsh-pocket/build.ps1                 # build dist/DSH-Pocket.apk
#   pwsh -File dsh-pocket/build.ps1 -Install -Launch # build + adb install + start
#
# Toolchain: Android SDK build-tools 36.0.0 + platforms/android-35, JDK 21 (javac -source/-target 11).
# Produces: dsh-pocket/dist/DSH-Pocket.apk (debug-signed with dsh-pocket/build/debug.keystore).
#
# Assets: if dsh-pocket/runtime/{proot,rootfs.tar.gz,start-dsh.sh} exist they are staged into
# app/assets/runtime/ before packaging; otherwise the APK still builds (runtime is then missing).

[CmdletBinding()]
param(
    [switch]$Install,
    [switch]$Launch,
    [switch]$Debuggable,
    [switch]$AllowIncompleteRuntime,
    [string]$RuntimeSource = '',
    [string]$AdbSerial = $env:ANDROID_SERIAL,
    [string]$VersionName = '1.0',
    [int]$VersionCode = 1
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root      = $PSScriptRoot
$AppDir    = Join-Path $Root 'app'
$BuildDir  = Join-Path $Root 'build'
$DistDir   = Join-Path $Root 'dist'
$Manifest  = Join-Path $AppDir 'AndroidManifest.xml'
$ResDir    = Join-Path $AppDir 'res'
$SrcDir    = Join-Path $AppDir 'src'
$AssetsDir = Join-Path $AppDir 'assets'
$RuntimeDir = Join-Path $Root 'runtime'
$RuntimeAssets = Join-Path $AssetsDir 'runtime'

$GenDir     = Join-Path $BuildDir 'gen'
$ClassesDir = Join-Path $BuildDir 'classes'
$DexDir     = Join-Path $BuildDir 'dex'
$ResZip     = Join-Path $BuildDir 'res.zip'
$BaseApk    = Join-Path $BuildDir 'base.apk'
$AlignedApk = Join-Path $BuildDir 'aligned.apk'
$KeystoreDir = Join-Path $Root 'keystore'
$Keystore   = Join-Path $KeystoreDir 'debug.keystore'
$FinalApk   = Join-Path $DistDir 'DSH-Pocket.apk'

function Fail([string]$Message) {
    Write-Host ""
    Write-Host "BUILD ERROR: $Message" -ForegroundColor Red
    exit 1
}

function Info([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }

# ---------------------------------------------------------------- toolchain ---
function Find-Sdk {
    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
    $candidates += 'C:\Android\android-sdk'
    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        if ((Test-Path (Join-Path $candidate 'build-tools')) -and
            (Test-Path (Join-Path $candidate 'platforms'))) { return $candidate }
    }
    Fail "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT (needs build-tools + platforms)."
}
$Sdk = Find-Sdk

# newest build-tools that actually has all four tools
$BuildTools = $null
foreach ($dir in (Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory | Sort-Object { [version]($_.Name -replace '[^0-9.].*$','') } -Descending)) {
    $ok = @('aapt2.exe', 'd8.bat', 'apksigner.bat', 'zipalign.exe') |
        ForEach-Object { Test-Path (Join-Path $dir.FullName $_) }
    if ($ok -notcontains $false) { $BuildTools = $dir.FullName; break }
}
if (-not $BuildTools) { Fail "No build-tools directory with aapt2/d8/apksigner/zipalign found under $Sdk\build-tools" }

$AndroidJar = $null
# prefer stable android-NN platforms (android-35); preview-style names (android-37.0) come last
$platformDirs = @(Get-ChildItem (Join-Path $Sdk 'platforms') -Directory)
$platformDirs = @($platformDirs | Where-Object { $_.Name -match '^android-35$' }) +
                @($platformDirs | Where-Object { $_.Name -match '^android-\d+$' -and $_.Name -ne 'android-35' } |
                    Sort-Object { [int]($_.Name -replace '^android-','') } -Descending) +
                @($platformDirs | Where-Object { $_.Name -notmatch '^android-\d+$' } | Sort-Object Name -Descending)
foreach ($dir in $platformDirs) {
    $jar = Join-Path $dir.FullName 'android.jar'
    if (Test-Path $jar) { $AndroidJar = $jar; break }
}
if (-not $AndroidJar) { Fail "android.jar not found under $Sdk\platforms" }

$JavaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    Fail "JAVA_HOME is not set to a JDK (need bin\javac.exe and bin\keytool.exe)."
}
$Javac    = Join-Path $JavaHome 'bin\javac.exe'
$Keytool  = Join-Path $JavaHome 'bin\keytool.exe'
$Aapt2    = Join-Path $BuildTools 'aapt2.exe'
$D8       = Join-Path $BuildTools 'd8.bat'
$ZipAlign = Join-Path $BuildTools 'zipalign.exe'
$ApkSigner = Join-Path $BuildTools 'apksigner.bat'

foreach ($required in @($Manifest, (Join-Path $ResDir 'values\styles.xml'), $SrcDir)) {
    if (-not (Test-Path $required)) { Fail "missing project input: $required" }
}
if (-not (Test-Path (Join-Path $SrcDir 'com\dshpocket\MainActivity.java'))) {
    Fail "missing source: app/src/com/dshpocket/MainActivity.java"
}

Info "SDK:         $Sdk"
Info "build-tools: $($BuildTools | Split-Path -Leaf)"
Info "android.jar: $AndroidJar"
Info "JDK:         $JavaHome"

# ------------------------------------------------------- stage runtime assets ---
# dsh-pocket/runtime/ (owned by the runtime teammate) is copied verbatim into the APK assets,
# preserving subdirectories (lib/...). A version.txt fingerprint lets the installer decide
# whether files/rootfs must be re-extracted. -RuntimeSource points at an alternative fixture
# (used for plumbing tests with a mock runtime).
$RuntimeSourceDir = if ([string]::IsNullOrWhiteSpace($RuntimeSource)) { $RuntimeDir } else { (Resolve-Path $RuntimeSource).Path }
Info "staging runtime assets from $RuntimeSourceDir"
if (Test-Path $RuntimeAssets) { Remove-Item -LiteralPath $RuntimeAssets -Recurse -Force }
New-Item -ItemType Directory -Force $RuntimeAssets | Out-Null
if (Test-Path $RuntimeSourceDir) {
    # Only the actual runtime payload is staged. Anything else in runtime/ (README,
    # fetch-assets.ps1, extract-deb.py, downloads/) must not leak into the APK: the
    # version.txt fingerprint would change on doc edits and force a needless rootfs
    # re-extraction (which also wipes DSH's in-guest state).
    $RuntimeItems = @('proot', 'loader', 'rootfs.tar.gz', 'start-dsh.sh', 'lib')
    foreach ($item in $RuntimeItems) {
        $src = Join-Path $RuntimeSourceDir $item
        if (Test-Path $src) {
            Copy-Item -Path $src -Destination $RuntimeAssets -Recurse -Force
        }
    }
}

$RequiredRuntime = @(
    'proot', 'loader', 'rootfs.tar.gz', 'start-dsh.sh',
    'lib\libtalloc.so.2', 'lib\libandroid-shmem.so'
)
$missingRuntime = @($RequiredRuntime | Where-Object { -not (Test-Path (Join-Path $RuntimeAssets $_)) })
if ($missingRuntime.Count -gt 0) {
    if ($AllowIncompleteRuntime) {
        Write-Host "    WARNING: incomplete runtime assets: $($missingRuntime -join ', ') (UI dev build)" -ForegroundColor Yellow
    } else {
        Fail ("runtime assets missing from dsh-pocket/runtime/: " + ($missingRuntime -join ', ') +
              "`nThe runtime teammate must stage: proot, loader, lib/libtalloc.so.2, lib/libandroid-shmem.so, rootfs.tar.gz, start-dsh.sh." +
              "`nUse -AllowIncompleteRuntime only for launcher-UI development builds.")
    }
}

$stagedFiles = @(Get-ChildItem -Path $RuntimeAssets -Recurse -File | Sort-Object FullName)
if ($stagedFiles.Count -gt 0) {
    $fingerprintLines = @()
    foreach ($file in $stagedFiles) {
        $rel = $file.FullName.Substring($RuntimeAssets.Length + 1) -replace '\\', '/'
        $fingerprintLines += "$rel=$($file.Length)"
    }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes(($fingerprintLines -join "`n")))
    } finally {
        $sha.Dispose()
    }
    $versionText = 'v1 ' + (($digest | ForEach-Object { $_.ToString('x2') }) -join '').Substring(0, 16)
    # extra lines let the app size its progress bar without openFd() (which cannot read
    # zip entries written through .NET's ZipArchive stream API)
    $rootfsSize = 0
    $rootfsPath = Join-Path $RuntimeAssets 'rootfs.tar.gz'
    if (Test-Path $rootfsPath) { $rootfsSize = (Get-Item -LiteralPath $rootfsPath).Length }
    $prootSize = 0
    $prootPath = Join-Path $RuntimeAssets 'proot'
    if (Test-Path $prootPath) { $prootSize = (Get-Item -LiteralPath $prootPath).Length }
    $versionContent = "$versionText`nrootfs=$rootfsSize`nproot=$prootSize`n"
    [System.IO.File]::WriteAllText((Join-Path $RuntimeAssets 'version.txt'), $versionContent,
        (New-Object System.Text.UTF8Encoding($false)))
    $totalMb = ($stagedFiles | Measure-Object -Property Length -Sum).Sum / 1MB
    Write-Host ("    staged {0} runtime file(s), {1:N1} MB, version {2}" -f $stagedFiles.Count, $totalMb, $versionText)
} else {
    Write-Host "    WARNING: no runtime assets staged" -ForegroundColor Yellow
}

# ---------------------------------------------------------------- clean build ---
Info "cleaning build outputs"
if (Test-Path $BuildDir) {
    foreach ($p in @($GenDir, $ClassesDir, $DexDir, $ResZip, $BaseApk, $AlignedApk)) {
        if (Test-Path $p) { Remove-Item -LiteralPath $p -Recurse -Force }
    }
} else {
    New-Item -ItemType Directory -Force $BuildDir | Out-Null
}
New-Item -ItemType Directory -Force $DistDir | Out-Null
New-Item -ItemType Directory -Force $GenDir | Out-Null
New-Item -ItemType Directory -Force $ClassesDir | Out-Null
New-Item -ItemType Directory -Force $DexDir | Out-Null

# --------------------------------------------------------------- aapt2 compile ---
Info "aapt2 compile resources"
& $Aapt2 compile --dir $ResDir -o $ResZip
if ($LASTEXITCODE -ne 0) { Fail "aapt2 compile failed ($LASTEXITCODE)" }

# ----------------------------------------------------------------- aapt2 link ---
Info "aapt2 link (minSdk 26, targetSdk 28)"
$LinkDebugArgs = @()
if ($Debuggable) { $LinkDebugArgs = @('--debug-mode') }
# NOTE: compiled resources are a positional argument; "-R" means overlay semantics in aapt2.
& $Aapt2 link -o $BaseApk -I $AndroidJar --manifest $Manifest `
    --min-sdk-version 26 --target-sdk-version 28 `
    --version-code $VersionCode --version-name $VersionName `
    --java $GenDir @LinkDebugArgs $ResZip
if ($LASTEXITCODE -ne 0) { Fail "aapt2 link failed ($LASTEXITCODE)" }
if (-not (Test-Path $BaseApk)) { Fail "aapt2 link produced no APK" }

# ------------------------------------------------------------------- javac ---
Info "javac"
$sources = @()
$sources += Get-ChildItem -Path $SrcDir -Recurse -Filter *.java -File | ForEach-Object { $_.FullName }
$sources += Get-ChildItem -Path $GenDir -Recurse -Filter *.java -File | ForEach-Object { $_.FullName }
if ($sources.Count -eq 0) { Fail "no java sources found" }
$sourcesArgFile = Join-Path $BuildDir 'sources.txt'
[System.IO.File]::WriteAllText($sourcesArgFile,
    (($sources | ForEach-Object { '"' + ($_ -replace '\\', '\\') + '"' }) -join "`r`n"),
    (New-Object System.Text.UTF8Encoding($false)))

& $Javac -source 11 -target 11 -encoding UTF-8 -nowarn `
    -classpath $AndroidJar -d $ClassesDir "@$sourcesArgFile"
if ($LASTEXITCODE -ne 0) { Fail "javac failed ($LASTEXITCODE)" }

# ---------------------------------------------------------------------- d8 ---
Info "d8 dex"
$classFiles = @(Get-ChildItem -Path $ClassesDir -Recurse -Filter *.class -File | ForEach-Object { $_.FullName })
if ($classFiles.Count -eq 0) { Fail "no .class files produced" }
& $D8 --min-api 26 --lib $AndroidJar --output $DexDir @classFiles
if ($LASTEXITCODE -ne 0) { Fail "d8 failed ($LASTEXITCODE)" }
$Dex = Join-Path $DexDir 'classes.dex'
if (-not (Test-Path $Dex)) { Fail "d8 did not produce classes.dex" }

# ------------------------------------------------------- add dex + assets to APK ---
Info "packaging classes.dex + assets into APK"
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
Add-Type -AssemblyName System.IO.Compression | Out-Null

function Add-FileToZip([System.IO.Compression.ZipArchive]$Zip, [string]$FilePath, [string]$EntryName, [System.IO.Compression.CompressionLevel]$Level) {
    $existing = $Zip.GetEntry($EntryName)
    if ($null -ne $existing) { $existing.Delete() }
    $entry = $Zip.CreateEntry($EntryName, $Level)
    $entry.LastWriteTime = [System.DateTimeOffset]::new([System.IO.File]::GetLastWriteTimeUtc($FilePath))
    $inStream = [System.IO.File]::Open($FilePath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    try {
        $outStream = $entry.Open()
        try { $inStream.CopyTo($outStream) } finally { $outStream.Dispose() }
    } finally { $inStream.Dispose() }
}

function Compress-LevelFor([string]$Name) {
    $ext = [System.IO.Path]::GetExtension($Name).ToLowerInvariant()
    if (@('.gz', '.zip', '.xz', '.zst', '.apk', '.jpg', '.jpeg', '.png', '.webp') -contains $ext) {
        return [System.IO.Compression.CompressionLevel]::NoCompression
    }
    return [System.IO.Compression.CompressionLevel]::Optimal
}

$zip = [System.IO.Compression.ZipFile]::Open($BaseApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    Add-FileToZip $zip $Dex 'classes.dex' ([System.IO.Compression.CompressionLevel]::Optimal)

    if (Test-Path $AssetsDir) {
        $assetRoot = (Resolve-Path $AssetsDir).Path
        foreach ($file in (Get-ChildItem -Path $assetRoot -Recurse -File)) {
            $rel = $file.FullName.Substring($assetRoot.Length + 1) -replace '\\', '/'
            $entryName = "assets/$rel"
            $level = Compress-LevelFor $file.Name
            Add-FileToZip $zip $file.FullName $entryName $level
            Write-Host ("    + {0} ({1:N0} bytes, {2})" -f $entryName, $file.Length, $level)
        }
    }
} finally {
    $zip.Dispose()
}

# ---------------------------------------------------------------- zipalign ---
Info "zipalign"
& $ZipAlign -f 4 $BaseApk $AlignedApk
if ($LASTEXITCODE -ne 0) { Fail "zipalign failed ($LASTEXITCODE)" }

# ---------------------------------------------------------------- keystore ---
# Kept in keystore/debug.keystore and committed to the repo so every build
# (and every phone) keeps the same signing key and can update in place.
if (-not (Test-Path $KeystoreDir)) { New-Item -ItemType Directory -Force $KeystoreDir | Out-Null }
if (-not (Test-Path $Keystore)) {
    Info "generating debug keystore"
    & $Keytool -genkeypair -keystore $Keystore -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
    if ($LASTEXITCODE -ne 0) { Fail "keytool failed ($LASTEXITCODE)" }
}

# ------------------------------------------------------------------ signing ---
Info "apksigner sign"
if (Test-Path $FinalApk) { Remove-Item -LiteralPath $FinalApk -Force }
& $ApkSigner sign --ks $Keystore --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey `
    --v1-signing-enabled true --v2-signing-enabled true `
    --out $FinalApk $AlignedApk
if ($LASTEXITCODE -ne 0) { Fail "apksigner failed ($LASTEXITCODE)" }
if (-not (Test-Path $FinalApk)) { Fail "apksigner produced no APK" }

& $ApkSigner verify --print-certs $FinalApk | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "apksigner verify failed ($LASTEXITCODE)" }

# ------------------------------------------------------------------- report ---
$apk = Get-Item $FinalApk
$sha = (Get-FileHash $FinalApk -Algorithm SHA256).Hash
Write-Host ""
Info "BUILD OK: $($apk.FullName)"
Write-Host ("    size:   {0:N1} MB" -f ($apk.Length / 1MB))
Write-Host ("    sha256: {0}" -f $sha)
$rootfsAssetPath = Join-Path $RuntimeAssets 'rootfs.tar.gz'
$runtimeVersion = ''
$versionFile = Join-Path $RuntimeAssets 'version.txt'
if (Test-Path $versionFile) { $runtimeVersion = ((Get-Content -LiteralPath $versionFile -TotalCount 1) -join '').Trim() }
if (Test-Path $rootfsAssetPath) {
    $rootfsLength = (Get-Item -LiteralPath $rootfsAssetPath).Length
    $runtimeKind = if ([string]::IsNullOrWhiteSpace($RuntimeSource)) { 'REAL' } else { 'FIXTURE' }
    Write-Host ("    runtime: {0} rootfs.tar.gz {1:N1} MB ({2:N0} bytes), version {3}" -f $runtimeKind, ($rootfsLength / 1MB), $rootfsLength, $runtimeVersion)
} elseif ($stagedFiles.Count -gt 0) {
    Write-Host ("    runtime: MOCK/INCOMPLETE - no rootfs.tar.gz staged (version {0})" -f $runtimeVersion) -ForegroundColor Yellow
} else {
    Write-Host "    runtime: NONE (dev build)" -ForegroundColor Yellow
}

# ------------------------------------------------------------------ device ---
# The serial is intentionally not hardcoded: pass -AdbSerial, set $env:ANDROID_SERIAL,
# or just connect exactly one device/emulator.
$AdbPrefix = @()
if (-not [string]::IsNullOrWhiteSpace($AdbSerial)) { $AdbPrefix = @('-s', $AdbSerial) }

if ($Install -or $Launch) {
    $online = @((& adb devices) | Select-String '^\S+\s+device$' | ForEach-Object { ($_ -split '\s+')[0] })
    if ($AdbPrefix.Count -eq 0) {
        if ($online.Count -eq 1) {
            $AdbSerial = $online[0]
            $AdbPrefix = @('-s', $AdbSerial)
        } elseif ($online.Count -eq 0) {
            Fail "No adb device connected. Enable USB debugging or pass -AdbSerial <serial>."
        } else {
            Fail ("Multiple adb devices connected (" + ($online -join ', ') + "). Pass -AdbSerial <serial>.")
        }
    } elseif ($online -notcontains $AdbSerial) {
        Fail "adb device $AdbSerial not connected/authorized (online: $($online -join ', '))"
    }
    Info "installing on $AdbSerial"
    # --no-incremental: adb's incremental install server sometimes leaves the device stuck.
    & adb @AdbPrefix install --no-incremental -r $FinalApk
    if ($LASTEXITCODE -ne 0) {
        Write-Host "    first install failed, retrying after force-stop..." -ForegroundColor Yellow
        & adb @AdbPrefix shell am force-stop com.dshpocket | Out-Null
        Start-Sleep -Seconds 2
        & adb @AdbPrefix install --no-incremental -r $FinalApk
        if ($LASTEXITCODE -ne 0) { Fail "adb install failed ($LASTEXITCODE)" }
    }
}
if ($Launch) {
    Info "launching com.dshpocket/.MainActivity"
    & adb @AdbPrefix shell am force-stop com.dshpocket | Out-Null
    & adb @AdbPrefix shell am start -n com.dshpocket/.MainActivity
    if ($LASTEXITCODE -ne 0) { Fail "am start failed ($LASTEXITCODE)" }
}
