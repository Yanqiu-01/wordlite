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
    # Source tree for the "new" implementation. Defaults to the working tree; point it at a snapshot
    # (tools/tree-snapshot.ps1) when someone else has an unfinished .java in app/src/main/java, so a
    # half-written file cannot break the build and a measurement cannot drift under your feet.
    [string]$Tree = "app/src/main/java",
    # The versioned rig is tools/device-probe/DeviceCapture.java; -Probe overrides it, and the older
    # gitignored copy under artifacts/device/probe is still used when nothing else is around.
    [string]$Probe = "",
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
    $probeSrc = if ($Probe -and [System.IO.Path]::IsPathRooted($Probe)) { $Probe }
        elseif ($Probe) { Join-Path $root $Probe }
        elseif (Test-Path (Join-Path $root "tools/device-probe/DeviceCapture.java")) {
            Join-Path $root "tools/device-probe/DeviceCapture.java"
        } else { Join-Path $root "artifacts/device/probe/DeviceCapture.java" }
    if (-not (Test-Path $probeSrc)) { throw "missing probe: $probeSrc" }

    $oldSrc = Join-Path $tmp "head-src"
    New-Item -ItemType Directory -Force -Path $oldSrc | Out-Null
    # HEAD is read-only here: it holds the pre-fix (base-size) script-width billing.
    & git -C $root archive --format=tar -o (Join-Path $tmp "head.tar") HEAD app/src/main/java
    if ($LASTEXITCODE -ne 0) { throw "git archive failed" }
    tar -xf (Join-Path $tmp "head.tar") -C $oldSrc

    # "old" is HEAD of the repository this script is running from: in a worktree that is the
    # branch base, which is exactly the engine the measurement should be compared with.
    $sources = @{ old = (Join-Path $oldSrc "app/src/main/java"); new = (Join-Path $root $Tree) }
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
        # One jar into d8, not several hundred .class paths: expanded, that command line is over
        # Windows' limit and the failure only reads as "the filename or extension is too long".
        $packed = Join-Path $tmp "$name-classes.jar"
        if (Test-Path $packed) { [System.IO.File]::Delete($packed) }
        Push-Location $classes
        try { & jar -cf $packed . } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { throw "jar failed for $name" }
        & java -cp (Join-Path $root "tools/d8.jar") com.android.tools.r8.D8 --min-api 23 `
            --lib $android --release --output $dexDir $packed
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
    if (-not $SkipBuild) {
        # 这份 capture 属于哪一个引擎，必须自己带上。capture 是构建产物：拿它去拼后来的 Word 真值、
        # 或者去拼后来的 HEAD，都会算出一个理直气壮的假数——2026-10-08 就是这么被坑过一次：
        # 10-07 留下的 old 产物读出"错位段 50"，用今天的 HEAD 重建同一个引擎再跑一遍是 7。
        # tools/word-parity.ps1 会核对这里写下的 head_sha，对不上就拒绝出报告。
        $headSha = ((& git -C $root rev-parse HEAD) | Select-Object -First 1)
        $layoutDirty = @(& git -C $root status --porcelain -- app/src/main/java).Count -gt 0
        Set-Content -Encoding utf8NoBOM -Path (Join-Path $local "engine.tsv") -Value @(
            ("impl`t{0}" -f $name),
            ("head_sha`t{0}" -f $headSha),
            ("source`t{0}" -f $(if ($name -eq "old") { "git-archive HEAD" } else { "working tree" })),
            ("layout_dirty`t{0}" -f $layoutDirty),
            ("built_at`t{0}" -f ([DateTime]::Now.ToString("yyyy-MM-dd HH:mm:ss"))),
            ("dex_sha256`t{0}" -f ((Get-FileHash -Algorithm SHA256 -LiteralPath $dexes[$name]).Hash.ToLower())),
            ("docx`t{0}" -f ([System.IO.Path]::GetFileName($Docx))))
    }
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




