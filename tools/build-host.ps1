# Word Lite - Windows host build (a faithful port of tools/build.sh).
# Pipeline: aapt2 compile/link -> javac -> D8 -> insert classes.dex -> zipalign -> apksigner.
# Usage:  pwsh tools/build-host.ps1 [-FullClean] [-BuildTools <dir>] [-Aapt2 <exe>] [-JavaHome <dir>]
param(
    [string]$BuildTools = "",
    [string]$Aapt2 = "",
    [string]$JavaHome = "",
    [switch]$FullClean
)
$ErrorActionPreference = "Stop"
# Native tools write warnings to stderr; only exit codes decide success here.
$PSNativeCommandUseErrorActionPreference = $false
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Invoke-Checked {
    param([string]$exe, [string[]]$argv)
    Write-Host ("> " + (Split-Path -Leaf $exe) + " " + ($argv -join " ")) -ForegroundColor DarkGray
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw ("{0} failed with exit code {1}" -f (Split-Path -Leaf $exe), $LASTEXITCODE) }
}

function Remove-Tree {
    param([string]$path)
    if (Test-Path -LiteralPath $path) { [System.IO.Directory]::Delete($path, $true) }
}

# --- toolchain: JDK 17 plus the Windows SDK build-tools (aapt2/zipalign/apksigner) ---
if (-not $JavaHome) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/javac.exe"))) { $JavaHome = $env:JAVA_HOME }
    elseif (Test-Path "C:/Program Files/Microsoft/jdk-17.0.7.7-hotspot/bin/javac.exe") { $JavaHome = "C:/Program Files/Microsoft/jdk-17.0.7.7-hotspot" }
    else { throw "no JDK found; set JAVA_HOME or pass -JavaHome" }
}
$javaBin = Join-Path $JavaHome "bin"
$env:JAVA_HOME = $JavaHome
$env:PATH = $javaBin + ";" + $env:PATH
$java = Join-Path $javaBin "java.exe"
$javac = Join-Path $javaBin "javac.exe"
$keytool = Join-Path $javaBin "keytool.exe"
foreach ($t in @($java, $javac, $keytool)) { if (-not (Test-Path $t)) { throw ("missing tool: " + $t) } }

if (-not $BuildTools) {
    $sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $root "artifacts/host-tools/android-sdk" }
    $BuildTools = Join-Path $sdk "build-tools/35.0.0"
    if (-not (Test-Path (Join-Path $BuildTools "aapt2.exe"))) {
        $cands = @(Get-ChildItem -Directory (Join-Path $sdk "build-tools") -ErrorAction SilentlyContinue | Sort-Object Name -Descending)
        if ($cands.Count -gt 0) { $BuildTools = $cands[0].FullName }
    }
}
if (-not (Test-Path (Join-Path $BuildTools "aapt2.exe"))) {
    throw ("Windows build-tools not under " + $BuildTools + "; install build-tools;35.0.0 with sdkmanager")
}
$aapt2Exe = if ($Aapt2) { $Aapt2 } else { Join-Path $BuildTools "aapt2.exe" }
$zipalignExe = Join-Path $BuildTools "zipalign.exe"
$apksignerJar = Join-Path $BuildTools "lib/apksigner.jar"
foreach ($t in @($aapt2Exe, $zipalignExe, $apksignerJar)) { if (-not (Test-Path $t)) { throw ("missing tool: " + $t) } }

$src = Join-Path $root "app/src/main"
$out = Join-Path $root "artifacts/build"
$gen = Join-Path $out "gen"
$classes = Join-Path $out "classes"
$dex = Join-Path $out "dex"
$apkDir = Join-Path $root "artifacts/apk"
$apk = Join-Path $apkDir "wordlite-debug.apk"
$androidJar = Join-Path $root "tools/android-35.jar"
$d8Jar = Join-Path $root "tools/d8.jar"
$keystore = Join-Path $root "tools/debug.keystore"
$resZip = Join-Path $out "res.zip"
$unsigned = Join-Path $out "wordlite.unsigned.apk"
$aligned = Join-Path $out "wordlite.aligned.apk"
$outDex = Join-Path $out "classes.dex"
$srcList = Join-Path $out "sources.list"
$d8Args = Join-Path $out "d8-args.txt"
foreach ($f in @($androidJar, $d8Jar, (Join-Path $src "AndroidManifest.xml"))) { if (-not (Test-Path $f)) { throw ("missing input: " + $f) } }
foreach ($d in @($out, $apkDir)) { if (-not $d.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { throw ("refusing to touch " + $d) } }

# build.sh runs "rm -rf $OUT". By default only this pipeline's own outputs are cleared so the
# sibling dirs the test scripts write (host-classes, classes-A..D) survive; -FullClean is the
# literal port.
if ($FullClean) { Remove-Tree $out } else { foreach ($d in @($gen, $classes, $dex)) { Remove-Tree $d } }
New-Item -ItemType Directory -Force -Path $gen, $classes, $dex, $apkDir | Out-Null
foreach ($f in @($resZip, $unsigned, $aligned, $outDex, $srcList, $d8Args)) { if (Test-Path -LiteralPath $f) { [System.IO.File]::Delete($f) } }

Write-Host "== resources =="
Invoke-Checked $aapt2Exe @("compile", "--dir", (Join-Path $src "res"), "-o", $resZip)
Invoke-Checked $aapt2Exe @("link", "-o", $unsigned, "--manifest", (Join-Path $src "AndroidManifest.xml"),
    "-I", $androidJar, "--java", $gen, $resZip, "-A", (Join-Path $src "assets"), "-0", "ttc", "-0", "ttf", "-0", "otf")

Write-Host "== javac =="
# Slash-separated paths: a javac argfile treats a backslash as an escape character.
$sources = @(Get-ChildItem -Recurse -File -Filter *.java (Join-Path $src "java") | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem -Recurse -File -Filter *.java $gen | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { throw "no java sources found" }
Set-Content -Encoding ascii -Path $srcList -Value ($sources | ForEach-Object { $_ -replace "\\", "/" })
Write-Host ("javac {0} files" -f $sources.Count)
Invoke-Checked $javac @("-source", "8", "-target", "8", "-encoding", "UTF-8", "-classpath", $androidJar,
    "-d", $classes, ("@" + ($srcList -replace "\\", "/")))

Write-Host "== dex =="
# One D8 pass over every .class, handed through an argfile to dodge the cmd line limit.
$classFiles = @(Get-ChildItem -Recurse -File -Filter *.class $classes | ForEach-Object { $_.FullName -replace "\\", "/" })
if ($classFiles.Count -eq 0) { throw "no .class files produced" }
Set-Content -Encoding ascii -Path $d8Args -Value $classFiles
Write-Host ("d8 {0} class files" -f $classFiles.Count)
Invoke-Checked $java @("-cp", $d8Jar, "com.android.tools.r8.D8", "--min-api", "23", "--lib", $androidJar,
    "--output", $dex, ("@" + ($d8Args -replace "\\", "/")))
Copy-Item (Join-Path $dex "classes.dex") $outDex -Force

Write-Host "== insert classes.dex =="
# Same as build.sh appending with zipfile(apk, "a"): deflate the dex into the linked apk.
if (-not ("System.IO.Compression.ZipFile" -as [type])) { Add-Type -AssemblyName System.IO.Compression.FileSystem }
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($e in @($zip.Entries | Where-Object { $_.FullName -eq "classes.dex" })) { $e.Delete() }
    $entry = $zip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
    $srcstream = [System.IO.File]::OpenRead($outDex)
    $dststream = $entry.Open()
    try { $srcstream.CopyTo($dststream) } finally { $dststream.Dispose(); $srcstream.Dispose() }
} finally { $zip.Dispose() }

Write-Host "== normalize asset paths =="
# aapt2 on Windows joins -A subdirectory paths with "\", so assets land as
# "assets\fonts\x.ttf". Android's AssetManager only knows "/", so rename those entries
# in place (keeping STORED for the -0 exclusions) before align and sign.
$renamed = 0
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($e in @($zip.Entries | Where-Object { $_.FullName.Contains("\") })) {
        $name = $e.FullName
        $fixed = $name.Replace("\", "/")
        $level = [System.IO.Compression.CompressionLevel]::Optimal
        if ($e.CompressedLength -eq $e.Length) { $level = [System.IO.Compression.CompressionLevel]::NoCompression }
        $buffer = New-Object System.IO.MemoryStream
        $in = $e.Open()
        try { $in.CopyTo($buffer) } finally { $in.Dispose() }
        $bytes = $buffer.ToArray()
        $buffer.Dispose()
        $new = $zip.CreateEntry($fixed, $level)
        $outstream = $new.Open()
        try { $outstream.Write($bytes, 0, $bytes.Length) } finally { $outstream.Dispose() }
        $e.Delete()
        $renamed++
    }
} finally { $zip.Dispose() }
Write-Host ("renamed {0} entries to slash separators" -f $renamed)

& $keytool -list -keystore $keystore -storepass android -alias wordlite 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "== generating debug keystore =="
    $dn = "CN=WordLite Debug,O=Rikkahub,C=CN"
    & $keytool -genkeypair -keystore $keystore -storepass android -keypass android -alias wordlite -keyalg RSA -keysize 2048 -validity 10000 -dname $dn 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool -genkeypair failed" }
}

Write-Host "== align + sign =="
Invoke-Checked $zipalignExe @("-f", "4", $unsigned, $aligned)
Invoke-Checked $java @("-jar", $apksignerJar, "sign", "--ks", $keystore, "--ks-pass", "pass:android",
    "--key-pass", "pass:android", "--ks-key-alias", "wordlite", "--out", $apk, $aligned)
Invoke-Checked $java @("-jar", $apksignerJar, "verify", "--verbose", $apk)
Write-Host ("APK: {0} ({1:N0} bytes)" -f $apk, (Get-Item $apk).Length)