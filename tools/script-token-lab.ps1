# tools/script-token-lab.ps1 -- why does the phone cut "Ag3Sn" in half, and does any span shape avoid it?
#
# Builds tools/device-probe/ScriptTokenLab.java against a source tree (default: the working tree), runs it
# under app_process on the phone with the bundled fonts, and prints one row per shape per pad. No APK
# install: the lab measures the same 96-DPI layout the app would draw there, with the phone's own line
# breaker and the phone's own shaping. Read the probe header for what the four shapes are.
#
#   pwsh tools/script-token-lab.ps1
#   pwsh tools/script-token-lab.ps1 -Tree artifacts/privtree/<snapshot>/app/src/main/java
# Printed output is copied to -Out (docs/layout-parity-target.md 第 25 节 quotes it).
param(
    [string]$Serial = "EAMUT20528011355",
    [string]$Tree = "app/src/main/java",
    # ScriptTokenLab compares span shapes on one made-up string; ScriptGlueProbe reads the real thesis and
    # prints the span table of the paragraph whose token the phone cuts.
    [ValidateSet("ScriptTokenLab", "ScriptGlueProbe")]
    [string]$Probe = "ScriptTokenLab",
    [string]$Needle = "Ag3Sn",
    [string]$Docx = "tests/samples/input-liu.docx",
    [string]$Out = ""
)
if (-not $Out) { $Out = "artifacts/agent-layout-verify/$Probe.txt" }
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
function RunAdb([string[]]$a) { & adb -s $Serial @a }
function RunAdbShell([string]$cmd) { & adb -s $Serial shell $cmd }

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("wlscriptlab-" + [Guid]::NewGuid().ToString("N"))
$dev = "/data/local/tmp/wlscriptlab"
New-Item -ItemType Directory -Force -Path $tmp, (Join-Path $tmp "classes"), (Join-Path $tmp "dex") | Out-Null
$android = Join-Path $root "tools/android-35.jar"

# ---------- 1. app tree + lab -> one dex ----------
$gen = Join-Path $tmp "gen/com/rikkahub/wordlite"
New-Item -ItemType Directory -Force -Path $gen | Out-Null
Set-Content -Encoding utf8NoBOM -Path (Join-Path $gen "R.java") -Value @(
    "package com.rikkahub.wordlite;",
    "public final class R {",
    "    public static final class drawable { public static final int ic_file = 1; public static final int ic_hero = 2; }",
    "    public static final class layout { public static final int simple_spinner_dropdown_item = 3; }",
    "}")
$sources = @(Get-ChildItem -Recurse -File -Filter *.java (Join-Path $root $Tree) | ForEach-Object { $_.FullName })
$sources += @((Join-Path $root "tools/device-probe/$Probe.java"), (Join-Path $gen "R.java"))
$listing = Join-Path $tmp "listing.txt"
Set-Content -Encoding ascii -Path $listing -Value ($sources -replace "\\", "/")
Write-Host ("== javac ({0} files from {1}) ==" -f $sources.Count, $Tree)
& javac -nowarn -encoding UTF-8 -classpath $android -d (Join-Path $tmp "classes") "@$listing"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
$jar = Join-Path $tmp "lab.jar"
Push-Location (Join-Path $tmp "classes"); try { & jar -cf $jar . } finally { Pop-Location }
& java -cp (Join-Path $root "tools/d8.jar") com.android.tools.r8.D8 --min-api 23 --lib $android --release `
    --output (Join-Path $tmp "dex") $jar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# ---------- 2. the bundled fonts, in a zip AssetManager can read ----------
Add-Type -AssemblyName System.IO.Compression
$assetRoot = Join-Path $root "app/src/main/assets"
$assetZip = Join-Path $tmp "assets.zip"
$zipStream = [System.IO.File]::Create($assetZip)
$archive = New-Object System.IO.Compression.ZipArchive($zipStream, [System.IO.Compression.ZipArchiveMode]::Create)
Get-ChildItem -Recurse -File $assetRoot | ForEach-Object {
    $rel = ($_.FullName.Substring($assetRoot.Length + 1)) -replace '\\', '/'
    $entry = $archive.CreateEntry("assets/$rel", [System.IO.Compression.CompressionLevel]::NoCompression)
    $entryStream = $entry.Open(); $input = [System.IO.File]::OpenRead($_.FullName)
    $input.CopyTo($entryStream); $input.Dispose(); $entryStream.Dispose()
}
$archive.Dispose(); $zipStream.Dispose()

# ---------- 3. run it on the phone ----------
RunAdbShell "mkdir -p $dev" | Out-Null
RunAdb @("push", $assetZip, "$dev/assets.zip") | Out-Null
RunAdb @("push", (Join-Path $tmp "dex/classes.dex"), "$dev/lab.dex") | Out-Null
$probeArgs = "$dev/assets.zip"
if ($Probe -eq "ScriptGlueProbe") {
    RunAdb @("push", (Join-Path $root $Docx), "$dev/input-liu.docx") | Out-Null
    $probeArgs = "$dev $dev/input-liu.docx $Needle"
}
$sdk = (RunAdbShell "getprop ro.build.version.sdk").Trim()
Write-Host "== run $Probe (device sdk=$sdk, tree=$Tree)"
$log = RunAdbShell "CLASSPATH=$dev/lab.dex app_process -Xmx512m / com.rikkahub.wordlite.$Probe $probeArgs"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Out) | Out-Null
@("sdk=$sdk", "tree=$Tree") + @($log) | Set-Content -Encoding utf8NoBOM -Path $Out
$log
