# Word Lite - real-device pagination / line-break capture.
#
# The app never gets installed: the phone may be too old for a release APK, and the
# layout runs in 96-DPI document units (see PageGeometry), so feeding the app's own
# DocxParser + A4Paginator through app_process on the device reproduces exactly what
# the app would draw there -- real Android text shaping, real bundled fonts.
#
# Usage:  pwsh tools/capture-device.ps1 [-Serial X] [-Docx path] [-Impls old,new]
#                                       [-SkipBuild] [-TryInstall]
param(
    [string]$Serial = "",
    [string]$Docx = "tests/samples/input-liu.docx",
    [string]$Apk = "base.apk.1",
    [string[]]$Impls = @("old", "new"),
    [string]$OutDir = "artifacts/device",
    [switch]$SkipBuild,
    [switch]$TryInstall
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$script:adbArgs = @()
if ($Serial) { $script:adbArgs = @("-s", $Serial) }
function RunAdb([string[]]$a) { & adb @script:adbArgs @a }
function RunAdbShell([string]$cmd) { & adb @script:adbArgs shell $cmd }

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("wordlite-capture-" + [Guid]::NewGuid().ToString("N"))
$deviceTmp = "/data/local/tmp/wlcapture"
New-Item -ItemType Directory -Force -Path $tmp, $OutDir | Out-Null

# ---------- 1. device facts + optional install attempt ----------
$model      = (RunAdbShell "getprop ro.product.model").Trim()
$release    = (RunAdbShell "getprop ro.build.version.release").Trim()
$sdk        = (RunAdbShell "getprop ro.build.version.sdk").Trim()
$fingerprint= (RunAdbShell "getprop ro.build.fingerprint").Trim()
$wmSize     = (RunAdbShell "wm size").Trim()
$wmDensity  = (RunAdbShell "wm density").Trim()
$installed  = (RunAdbShell "pm list packages | grep wordlite")
$lines = @(
    "device_serial=$Serial", "model=$model", "android=$release", "sdk=$sdk",
    "fingerprint=$fingerprint", $wmSize, $wmDensity,
    "wordlite_installed=$installed",
    "",
    "# density is 480 dpi => 3.0 px/dp, but document layout uses 96-DPI units:",
    "# PageGeometry.points(pt) = pt * 96 / 72, so screen density does not move a break.",
    "# px per point at 100% zoom = 96/72 = 1.3333; a 1080px-wide screen fits A4 (793.8 units)"
)
Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir "device.txt") -Value $lines
Write-Host "== device: $model android=$release sdk=$sdk $wmSize $wmDensity"

if ($TryInstall -and (Test-Path $Apk)) {
    RunAdb @("push", $Apk, "/data/local/tmp/wordlite.apk") | Out-Null
    $installLog = RunAdbShell "pm install -r -t /data/local/tmp/wordlite.apk"
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir "install-log.txt") `
        -Value @("apk=$Apk", "install=$installLog", "installed_now=$(AdbShell 'pm list packages | grep wordlite')")
    Write-Host "== install: $installLog"
}

# ---------- 2. build one dex per implementation ----------
$android = Join-Path $root "tools/android-35.jar"
$dexes = @{}
if (-not $SkipBuild) {
    $gen = Join-Path $tmp "gen/com/rikkahub/wordlite"
    New-Item -ItemType Directory -Force -Path $gen | Out-Null
    # aapt2 generates the real R class; the probe compile only needs the ids the app touches.
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $gen "R.java") -Value @(
        "package com.rikkahub.wordlite;",
        "public final class R {",
        "    public static final class drawable { public static final int ic_file = 1; public static final int ic_hero = 2; }",
        "    public static final class layout { public static final int simple_spinner_dropdown_item = 3; }",
        "}"
    )
    $probeSrc = Join-Path $root "artifacts/device/probe/DeviceCapture.java"
    if (-not (Test-Path $probeSrc)) { throw "missing probe: $probeSrc" }

    $oldSrc = Join-Path $tmp "head-src"
    New-Item -ItemType Directory -Force -Path $oldSrc | Out-Null
    # HEAD is read-only here: it holds the pre-fix (base-size) script-width billing.
    & git -C $root archive --format=tar -o (Join-Path $tmp "head.tar") HEAD app/src/main/java
    if ($LASTEXITCODE -ne 0) { throw "git archive failed" }
    tar -xf (Join-Path $tmp "head.tar") -C $oldSrc

    $sources = @{ old = (Join-Path $oldSrc "app/src/main/java"); new = (Join-Path $root "app/src/main/java") }
    foreach ($name in $Impls) {
        $classes = Join-Path $tmp "$name-classes"; New-Item -ItemType Directory -Force -Path $classes | Out-Null
        $files = @(Get-ChildItem -Recurse -File -Filter *.java $sources[$name] | ForEach-Object { $_.FullName })
        $files += $probeSrc; $files += (Join-Path $gen "R.java")
        $list = Join-Path $tmp "$name-sources.txt"
        Set-Content -Encoding ascii -Path $list -Value $files
        Write-Host "== javac $name ($($files.Count) files)"
        & javac -nowarn -encoding UTF-8 -classpath $android -d $classes "@$list"
        if ($LASTEXITCODE -ne 0) { throw "javac failed for $name" }
        $dexDir = Join-Path $tmp "$name-dex"; New-Item -ItemType Directory -Force -Path $dexDir | Out-Null
        & java -cp (Join-Path $root "tools/d8.jar") com.android.tools.r8.D8 --min-api 23 `
            --lib $android --release --output $dexDir `
            @(Get-ChildItem -Recurse -File -Filter *.class $classes | ForEach-Object { $_.FullName })
        if ($LASTEXITCODE -ne 0) { throw "d8 failed for $name" }
        $dexes[$name] = Join-Path $dexDir "classes.dex"
    }

    # Assets must sit under assets/ inside the zip for AssetManager.addAssetPath.
    Add-Type -AssemblyName System.IO.Compression
    $assetRoot = Join-Path $root "app/src/main/assets"
    $assetZip = Join-Path $tmp "wordlite-assets.zip"
    $zipStream = [System.IO.File]::Create($assetZip)
    $archive = New-Object System.IO.Compression.ZipArchive($zipStream, [System.IO.Compression.ZipArchiveMode]::Create)
    Get-ChildItem -Recurse -File $assetRoot | ForEach-Object {
        $rel = ($_.FullName.Substring($assetRoot.Length + 1)) -replace '\\', '/'
        $entry = $archive.CreateEntry("assets/$rel", [System.IO.Compression.CompressionLevel]::NoCompression)
        $entryStream = $entry.Open(); $input = [System.IO.File]::OpenRead($_.FullName)
        $input.CopyTo($entryStream); $input.Dispose(); $entryStream.Dispose()
    }
    $archive.Dispose(); $zipStream.Dispose()
}

# ---------- 3. push and run ----------
RunAdbShell "mkdir -p $deviceTmp" | Out-Null
$assetZipPushed = "$deviceTmp/wordlite-assets.zip"
$docxPushed = "$deviceTmp/input-liu.docx"
if (-not $SkipBuild) {
    RunAdb @("push", $assetZip, $assetZipPushed) | Out-Null
}
RunAdb @("push", $Docx, $docxPushed) | Out-Null

$screenWidth = ((RunAdbShell "wm size") -replace ".*:\s*", "") -split "x" | Select-Object -Index 0
foreach ($name in $Impls) {
    if (-not $SkipBuild) { RunAdb @("push", $dexes[$name], "$deviceTmp/$name.dex") | Out-Null }
    $remoteOut = "$deviceTmp/out-$name"
    RunAdbShell "rm -rf $remoteOut" | Out-Null
    Write-Host "== run $name"
    RunAdbShell "CLASSPATH=$deviceTmp/$name.dex app_process -Xmx512m / com.rikkahub.wordlite.DeviceCapture $docxPushed $assetZipPushed $remoteOut $name $screenWidth"
    $local = Join-Path $OutDir $name
    New-Item -ItemType Directory -Force -Path $local | Out-Null
    RunAdb @("pull", $remoteOut, $local) | Out-Null
    Move-Item -Force (Join-Path $local "out-$name\*") $local
    cmd /c "rmdir `"$local\out-$name`"" | Out-Null
    if (-not $SkipBuild) { Copy-Item -Force $dexes[$name] (Join-Path $local "device-capture.dex") }
}
# Roll the "old" run up to the artifact root: that is the pre-fix baseline.
$baseline = Join-Path $OutDir "old"
if (-not (Test-Path $baseline)) { $baseline = Join-Path $OutDir $Impls[0] }
if (Test-Path (Join-Path $baseline "summary.txt")) {
    $pagesLine = @(Get-Content (Join-Path $baseline "summary.txt") -Encoding UTF8 | Where-Object { $_ -like "pages=*" })[0]
    $total = $pagesLine.Split("=")[1].Split(" ")[0]
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir "total-pages.txt") -Value @(
        "total_pages=$total",
        "impl=$(Split-Path -Leaf $baseline)",
        "engine_line=$pagesLine",
        "word_total_pages=28  (artifacts/word/geometry.txt)",
        'note: the app status bar prints the same number, "  N / total 页" (EditorActivity.updateStatus)')
    Copy-Item -Force (Join-Path $baseline "status.txt") (Join-Path $OutDir "status.txt")
    Copy-Item -Force (Join-Path $baseline "superscript-lines.txt") (Join-Path $OutDir "superscript-lines.txt")
    Copy-Item -Force (Join-Path $baseline "superscript-inventory.txt") (Join-Path $OutDir "superscript-inventory.txt")
}Write-Host "== artifacts in $OutDir"




