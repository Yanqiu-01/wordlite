# Builds the standalone measurement probe APK (package com.rikkahub.wordlite.metrics) and runs it on
# a real device, so Paint/StaticLayout advances are measured inside a zygote-forked application
# process instead of app_process. It does not touch app/src, does not touch artifacts/apk, and it
# never replaces the installed Word Lite (different applicationId, own signing run).
#
# Usage:  pwsh tools/build-metrics-probe.ps1 [-Serial <adb serial>] [-KeepInstalled]
param(
    [string]$Serial = "EAMUT20528011355",
    [switch]$KeepInstalled
)
$ErrorActionPreference = "Stop"
$env:PATH = "E:\download\claude\New-Folder\Git\bin;E:\download\claude\New-Folder\Git\usr\bin;" + $env:PATH
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$JavaHome = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME/bin/javac.exe")) { $env:JAVA_HOME }
            elseif (Test-Path "C:/Program Files/Microsoft/jdk-17.0.7.7-hotspot/bin/javac.exe") { "C:/Program Files/Microsoft/jdk-17.0.7.7-hotspot" }
            else { throw "no JDK found" }
$javac = Join-Path $JavaHome "bin/javac.exe"
$java  = Join-Path $JavaHome "bin/java.exe"
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $root "artifacts/host-tools/android-sdk" }
$BuildTools = Join-Path $sdk "build-tools/35.0.0"
if (-not (Test-Path (Join-Path $BuildTools "aapt2.exe"))) { throw "aapt2 not under $BuildTools" }
$aapt2 = Join-Path $BuildTools "aapt2.exe"
$zipalign = Join-Path $BuildTools "zipalign.exe"
$apksigner = Join-Path $BuildTools "lib/apksigner.jar"
$android = "$root/tools/android-35.jar"
$d8 = "$root/tools/d8.jar"
$keystore = "$root/tools/debug.keystore"
$work = Join-Path $root "artifacts/build/metrics-probe"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force -Path "$work/gen/com/rikkahub/wordlite", "$work/classes", "$work/dex", "$work/assets/fonts" | Out-Null
Copy-Item "app/src/main/assets/fonts/times-new-roman.ttf" "$work/assets/fonts/" -Force

# the app sources reference R fields; the probe never inflates them, so stub ids are enough
Set-Content -Encoding utf8NoBOM -Path "$work/gen/com/rikkahub/wordlite/R.java" -Value @(
  "package com.rikkahub.wordlite;",
  "public final class R {",
  "    public static final class drawable { public static final int ic_file = 1; public static final int ic_hero = 2; }",
  "    public static final class layout { public static final int simple_spinner_dropdown_item = 3; }",
  "}")

& $aapt2 link -o "$work/linked.apk" --manifest "tools/device-probe/MetricsManifest.xml" -I $android `
    --java "$work/aapt-gen" -A "$work/assets"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$sources = @(Get-ChildItem -Recurse -File -Filter *.java "app/src/main/java" | ForEach-Object { $_.FullName })
$sources += "$work/gen/com/rikkahub/wordlite/R.java"
$sources += "$root/tools/device-probe/MetricsActivity.java"
& $javac -nowarn -encoding UTF-8 -classpath $android -d "$work/classes" $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
$classes = @(Get-ChildItem -Recurse -File -Filter *.class "$work/classes" | ForEach-Object { $_.FullName -replace "\\", "/" })
Set-Content -Encoding ascii -Path "$work/classes.list" -Value $classes
& $java -cp $d8 com.android.tools.r8.D8 --min-api 23 --lib $android --release --output "$work/dex" ("@" + "$work/classes.list")
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$work/linked.apk", [System.IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($e in @($zip.Entries | Where-Object { $_.FullName -eq "classes.dex" })) { $e.Delete() }
    $entry = $zip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
    $s = [System.IO.File]::OpenRead("$work/dex/classes.dex"); $d = $entry.Open()
    try { $s.CopyTo($d) } finally { $d.Dispose(); $s.Dispose() }
    # aapt2 on Windows joins asset subdirectories with "\", AssetManager only knows "/"
    foreach ($e in @($zip.Entries | Where-Object { $_.FullName.Contains("\") })) {
        $fixed = $e.FullName.Replace("\", "/")
        $new = $zip.CreateEntry($fixed, [System.IO.Compression.CompressionLevel]::Optimal)
        $es = $e.Open(); $ns = $new.Open(); try { $es.CopyTo($ns) } finally { $ns.Dispose(); $es.Dispose() }
        $e.Delete()
    }
} finally { $zip.Dispose() }

& $zipalign -f 4 "$work/linked.apk" "$work/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
& $java -jar $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias wordlite --out "$work/metrics-probe.apk" "$work/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& $java -jar $apksigner verify "$work/metrics-probe.apk"
Write-Host ("APK {0:N0} bytes" -f (Get-Item "$work/metrics-probe.apk").Length)

if ($Serial) {
    $adb = @(); if ($Serial) { $adb = @("-s", $Serial) }
    & adb @adb install -r -t "$work/metrics-probe.apk"
    if ($LASTEXITCODE -ne 0) { throw "install failed" }
    & adb @adb logcat -c
    & adb @adb shell am start -n com.rikkahub.wordlite.metrics/.MetricsActivity | Out-Null
    Start-Sleep -Seconds 12
    & adb @adb logcat -d -s WLmetrics
    if (-not $KeepInstalled) { & adb @adb uninstall com.rikkahub.wordlite.metrics | Out-Null }
}