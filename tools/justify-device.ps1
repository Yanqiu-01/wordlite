# Word Lite - what the phone's own text engine does with setJustificationMode.
# Robolectric's native sdk-28 engine ignores justification, so the only trustworthy
# answer comes from the device itself. No APK install: the probe runs under app_process.
#
# Usage: pwsh tools/justify-device.ps1 [-Serial X] [-Width 567]
param(
    [string]$Serial = "",
    [int]$Width = 567,
    [string]$OutDir = "artifacts/justify"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$adbArgs = @(); if ($Serial) { $adbArgs = @("-s", $Serial) }
function RunAdb([string[]]$a) { & adb @adbArgs @a }
function RunAdbShell([string]$cmd) { & adb @adbArgs shell $cmd }

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("wordlite-justify-" + [Guid]::NewGuid().ToString("N"))
$deviceTmp = "/data/local/tmp/wljustify"
New-Item -ItemType Directory -Force -Path $tmp, $OutDir | Out-Null

$android = Join-Path $root "tools/android-35.jar"
$gen = Join-Path $tmp "gen/com/rikkahub/wordlite"
New-Item -ItemType Directory -Force -Path $gen | Out-Null
Set-Content -Encoding utf8NoBOM -Path (Join-Path $gen "R.java") -Value @(
    "package com.rikkahub.wordlite;",
    "public final class R {",
    "    public static final class drawable { public static final int ic_file = 1; public static final int ic_hero = 2; }",
    "    public static final class layout { public static final int simple_spinner_dropdown_item = 3; }",
    "}"
)
$classes = Join-Path $tmp "classes"; New-Item -ItemType Directory -Force -Path $classes | Out-Null
$files = @(Get-ChildItem -Recurse -File -Filter *.java (Join-Path $root "app/src/main/java") | ForEach-Object { $_.FullName })
$probe = Join-Path $root "artifacts/device/probe/DeviceJustify.java"
if (-not (Test-Path $probe)) { throw "missing probe: $probe" }
$files += $probe; $files += (Join-Path $gen "R.java")
$list = Join-Path $tmp "sources.txt"
Set-Content -Encoding ascii -Path $list -Value $files
Write-Host "== javac ($($files.Count) files)"
& javac -nowarn -encoding UTF-8 -classpath $android -d $classes "@$list"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
$dexDir = Join-Path $tmp "dex"; New-Item -ItemType Directory -Force -Path $dexDir | Out-Null
& java -cp (Join-Path $root "tools/d8.jar") com.android.tools.r8.D8 --min-api 23 --lib $android --release --output $dexDir `
    @(Get-ChildItem -Recurse -File -Filter *.class $classes | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Add-Type -AssemblyName System.IO.Compression
$assetRoot = Join-Path $root "app/src/main/assets"
$assetZip = Join-Path $tmp "wordlite-assets.zip"
$zipStream = [System.IO.File]::Create($assetZip)
$archive = New-Object System.IO.Compression.ZipArchive($zipStream, [System.IO.Compression.ZipArchiveMode]::Create)
Get-ChildItem -Recurse -File $assetRoot | ForEach-Object {
    $rel = ($_.FullName.Substring($assetRoot.Length + 1)) -replace '\\', '/'
    $entry = $archive.CreateEntry("assets/$rel", [System.IO.Compression.CompressionLevel]::NoCompression)
    $es = $entry.Open(); $in = [System.IO.File]::OpenRead($_.FullName)
    $in.CopyTo($es); $in.Dispose(); $es.Dispose()
}
$archive.Dispose(); $zipStream.Dispose()

RunAdbShell "mkdir -p $deviceTmp" | Out-Null
RunAdb @("push", (Join-Path $dexDir "classes.dex"), "$deviceTmp/justify.dex") | Out-Null
RunAdb @("push", $assetZip, "$deviceTmp/assets.zip") | Out-Null
$out = RunAdbShell "CLASSPATH=$deviceTmp/justify.dex app_process -Xmx256m / com.rikkahub.wordlite.DeviceJustify $deviceTmp/assets.zip $Width"
Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir "device-justify.txt") -Value $out
$out | ForEach-Object { Write-Host $_ }
