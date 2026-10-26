# Fetches the small, third-party runtime binaries that the APK embeds:
#   runtime/proot, runtime/loader, runtime/lib/libtalloc.so.2, runtime/lib/libandroid-shmem.so
# and downloads the rootfs build inputs (Ubuntu base + Node.js arm64) into
# runtime/downloads/ (building rootfs.tar.gz itself needs an arm64 machine; see README.md).
#
#   pwsh -File runtime/fetch-assets.ps1
#   pwsh -File runtime/fetch-assets.ps1 -SkipRootfsInputs
[CmdletBinding()]
param(
    [string]$TermuxRepo = 'https://packages.termux.dev/apt/termux-main',
    [string]$DownloadsDir = '',
    [switch]$SkipRootfsInputs,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($DownloadsDir)) { $DownloadsDir = Join-Path $Root 'downloads' }
$LibDir = Join-Path $Root 'lib'
New-Item -ItemType Directory -Force $DownloadsDir, $LibDir | Out-Null

function Info([string]$m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Fail([string]$m) { Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }

function Get-Python {
    foreach ($candidate in @('python', 'python3', 'py')) {
        $cmd = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    Fail "Python is required to unpack .deb files (install Python 3 and retry)."
}

function Get-LatestDeb([string]$PackagePath, [string]$NamePrefix) {
    $url = "$TermuxRepo/pool/main/$PackagePath"
    Info "listing $url"
    try { $html = (Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 30).Content }
    catch { Fail "cannot list $url ($($_.Exception.Message))" }
    $files = [regex]::Matches($html, 'href="([^"]+_aarch64\.deb)"') |
        ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_ -like "$NamePrefix*" }
    if (-not $files) { Fail "no aarch64 .deb starting with '$NamePrefix' under $url" }
    $sorted = $files | Sort-Object -Descending
    return @{ Url = "$url/$($sorted | Select-Object -First 1)"; Name = $sorted | Select-Object -First 1 }
}

function Get-Deb([string]$PackagePath, [string]$NamePrefix) {
    $deb = Get-LatestDeb $PackagePath $NamePrefix
    $out = Join-Path $DownloadsDir $deb.Name
    if (-not (Test-Path $out) -or $Force) {
        Info "downloading $($deb.Name)"
        Invoke-WebRequest -Uri $deb.Url -OutFile $out -UseBasicParsing
    }
    return $out
}

function Expand-Deb([string]$DebFile) {
    $python = Get-Python
    $extractScript = Join-Path $Root 'extract-deb.py'
    $target = Join-Path $DownloadsDir ("x-" + [System.IO.Path]::GetFileNameWithoutExtension($DebFile))
    if (Test-Path $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    & $python $extractScript $DebFile $target | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "python extract-deb.py failed for $DebFile" }
    return $target
}

function Find-Under([string]$Dir, [string]$Leaf) {
    $hit = Get-ChildItem -Path $Dir -Recurse -File -Filter $Leaf -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $hit) { Fail "expected $Leaf inside $Dir" }
    return $hit.FullName
}

function Assert-Aarch64([string]$Path, [string]$Label) {
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 20 -or $bytes[0] -ne 0x7F -or $bytes[1] -ne 0x45 -or
        $bytes[2] -ne 0x4C -or $bytes[3] -ne 0x46) {
        Fail "$Label is not an ELF file: $Path"
    }
    $machine = [BitConverter]::ToUInt16($bytes, 18)   # little-endian e_machine
    if ($machine -ne 183) { Fail "$Label is not aarch64 (e_machine=$machine): $Path" }
    Info "verified aarch64: $Label ($([math]::Round($bytes.Length/1KB)) KB)"
}

# ---------------------------------------------------------------- binaries ---
$prootDeb = Get-Deb 'p/proot' 'proot_'
$tallocDeb = Get-Deb 'libt/libtalloc' 'libtalloc_'
$shmemDeb = Get-Deb 'liba/libandroid-shmem' 'libandroid-shmem_'

$prootDir = Expand-Deb $prootDeb
$tallocDir = Expand-Deb $tallocDeb
$shmemDir = Expand-Deb $shmemDeb

Copy-Item (Find-Under $prootDir 'proot') (Join-Path $Root 'proot') -Force
Copy-Item (Find-Under $prootDir 'loader') (Join-Path $Root 'loader') -Force
Copy-Item (Find-Under $tallocDir 'libtalloc.so.2') (Join-Path $LibDir 'libtalloc.so.2') -Force
Copy-Item (Find-Under $shmemDir 'libandroid-shmem.so') (Join-Path $LibDir 'libandroid-shmem.so') -Force

Assert-Aarch64 (Join-Path $Root 'proot') 'proot'
Assert-Aarch64 (Join-Path $Root 'loader') 'loader'
Assert-Aarch64 (Join-Path $LibDir 'libtalloc.so.2') 'libtalloc.so.2'
Assert-Aarch64 (Join-Path $LibDir 'libandroid-shmem.so') 'libandroid-shmem.so'

# ------------------------------------------------------- rootfs build inputs --
if (-not $SkipRootfsInputs) {
    $basePage = 'https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/'
    Info "listing $basePage"
    try {
        $html = (Invoke-WebRequest -Uri $basePage -UseBasicParsing -TimeoutSec 30).Content
        $baseFile = [regex]::Matches($html, 'href="(ubuntu-base-[0-9.]+-base-arm64\.tar\.gz)"') |
            ForEach-Object { $_.Groups[1].Value } | Sort-Object -Descending | Select-Object -First 1
        if (-not $baseFile) { Fail "no ubuntu-base arm64 tarball listed at $basePage" }
        $baseOut = Join-Path $DownloadsDir $baseFile
        if (-not (Test-Path $baseOut) -or $Force) {
            Info "downloading $baseFile"
            Invoke-WebRequest -Uri "$basePage$baseFile" -OutFile $baseOut -UseBasicParsing
        }
    } catch { Write-Host "    WARNING: could not fetch Ubuntu base: $($_.Exception.Message)" -ForegroundColor Yellow }

    $nodePage = 'https://nodejs.org/dist/latest-v22.x/'
    Info "listing $nodePage"
    try {
        $html = (Invoke-WebRequest -Uri $nodePage -UseBasicParsing -TimeoutSec 30).Content
        $nodeFile = [regex]::Matches($html, 'href="(node-v22\.[0-9.]+-linux-arm64\.tar\.xz)"') |
            ForEach-Object { $_.Groups[1].Value } | Sort-Object -Descending | Select-Object -First 1
        if (-not $nodeFile) { Fail "no node-v22 linux-arm64 tarball listed at $nodePage" }
        $nodeOut = Join-Path $DownloadsDir $nodeFile
        if (-not (Test-Path $nodeOut) -or $Force) {
            Info "downloading $nodeFile"
            Invoke-WebRequest -Uri "$nodePage$nodeFile" -OutFile $nodeOut -UseBasicParsing
        }
    } catch { Write-Host "    WARNING: could not fetch Node.js: $($_.Exception.Message)" -ForegroundColor Yellow }
}

Info "runtime binaries ready:"
Get-ChildItem $Root -File | Where-Object { $_.Name -in @('proot', 'loader') } |
    ForEach-Object { Write-Host ("    {0}  {1:N0} bytes" -f $_.Name, $_.Length) }
Get-ChildItem $LibDir -File | ForEach-Object { Write-Host ("    lib/{0}  {1:N0} bytes" -f $_.Name, $_.Length) }
Write-Host ""
Info "next: build runtime/rootfs.tar.gz on an arm64 machine (see runtime/README.md),"
Info "      then run: pwsh -File build.ps1"
