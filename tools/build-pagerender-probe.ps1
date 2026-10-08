# Builds the on-device render probe (package com.rikkahub.wordlite.pagerender) and runs it, so the
# WebView harvest is measured inside a real application process with a real WebView provider,
# not inside app_process. It does not touch app/src, does not touch artifacts/apk, and it never
# replaces the installed Word Lite (different applicationId, own signing run).
#
# Usage:  pwsh tools/build-pagerender-probe.ps1 [-Serial <adb serial>] [-KeepInstalled]
#             [-Url <详情页地址> ...]      可重复给，探针会在内置那批地址之后再渲染它们
#             [-UrlFile <清单>] [-PushList <清单>]   网址与页面文件走清单，不进命令行
#             [-Tree <源码树>]            默认仓库根；树里有别人改到一半的文件时指到自己的快照上
param(
    [string]$Serial = "EAMUT20528011355",
    [string]$Tree = "",
    [string[]]$Url = @(),
    [string]$UrlFile = "",      # 一行一个详情页地址：网址不进命令行，也不进版本库
    [string[]]$PushFile = @(),  # 本地页面文件：adb push 进探针自己的外部目录，按 file:// 离线加载
    [string]$PushList = "",     # 同上，但走清单文件（有些终端把逗号数组传成一个字符串，清单不会）
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
$work = Join-Path $root "artifacts/build/pagerender-probe"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force -Path "$work/gen/com/rikkahub/wordlite", "$work/classes", "$work/dex", "$work/assets" | Out-Null

# the app sources reference R fields; the probe never inflates them, so stub ids are enough
Set-Content -Encoding utf8NoBOM -Path "$work/gen/com/rikkahub/wordlite/R.java" -Value @(
  "package com.rikkahub.wordlite;",
  "public final class R {",
  "    public static final class drawable { public static final int ic_file = 1; public static final int ic_hero = 2; }",
  "    public static final class layout { public static final int simple_spinner_dropdown_item = 3; }",
  "}")

if (-not $Tree) { $Tree = $root }
if (-not (Test-Path (Join-Path $Tree "app/src/main/java"))) { throw "$Tree 里没有 app/src/main/java" }
Write-Host ("源码树 {0}" -f $Tree)
& $aapt2 link -o "$work/linked.apk" --manifest "tools/device-probe/PageRenderManifest.xml" -I $android `
    --java "$work/aapt-gen" -A "$work/assets"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$sources = @(Get-ChildItem -Recurse -File -Filter *.java (Join-Path $Tree "app/src/main/java") | ForEach-Object { $_.FullName })
$sources += "$work/gen/com/rikkahub/wordlite/R.java"
$sources += "$root/tools/device-probe/PageRenderActivity.java"
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
} finally { $zip.Dispose() }

& $zipalign -f 4 "$work/linked.apk" "$work/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
& $java -jar $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias wordlite --out "$work/pagerender-probe.apk" "$work/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& $java -jar $apksigner verify "$work/pagerender-probe.apk"
Write-Host ("APK {0:N0} bytes" -f (Get-Item "$work/pagerender-probe.apk").Length)

if ($UrlFile) {
    foreach ($line in (Get-Content $UrlFile -Encoding UTF8)) {
        if ($line.Trim().Length -gt 0 -and -not $line.StartsWith("#")) { $Url += $line.Trim() }
    }
    Write-Host ("清单 {0} 里读了 {1} 个地址" -f $UrlFile, $Url.Count)
}
if (-not $Serial) { Write-Host "没给 -Serial，只打包不跑"; return }
$adb = @("-s", $Serial)
& adb @adb install -r -t "$work/pagerender-probe.apk"
if ($LASTEXITCODE -ne 0) { throw "install failed" }
& adb @adb logcat -c
$component = "com.rikkahub.wordlite.pagerender/com.rikkahub.wordlite.PageRenderActivity"
if ($PushList) {
    foreach ($line in (Get-Content $PushList -Encoding UTF8)) {
        if ($line.Trim().Length -gt 0 -and -not $line.StartsWith("#")) { $PushFile += $line.Trim() }
    }
}
$remote = "/sdcard/Android/data/com.rikkahub.wordlite.pagerender/files"
& adb @adb shell mkdir -p $remote | Out-Null
$fileArgs = @()
for ($i = 0; $i -lt $PushFile.Count; $i++) {
    $name = Split-Path -Leaf $PushFile[$i]
    & adb @adb push $PushFile[$i] "$remote/$name" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "adb push $name 失败" }
    $key = if ($i -eq 0) { "file" } else { "file" + ($i + 1) }
    $fileArgs += @("--es", $key, $name)
}
$args = @("am", "start", "-n", $component)
for ($i = 0; $i -lt $Url.Count; $i++) {
    $key = if ($i -eq 0) { "url" } else { "url" + ($i + 1) }
    $args += @("--es", $key, $Url[$i])
}
$args += $fileArgs
if ($PushFile.Count -gt 0) { $args += @("--ez", "scan", "1") }
& adb @adb shell @args | Out-Null

# 每页最多三十秒，四到九页：等 logcat 里那行 DONE，别拿一个固定秒数去赌对家的页面还在不在
$deadline = (Get-Date).AddSeconds(60 + 35 * [math]::Max(4, $Url.Count + 4))
$done = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 6
    $dump = & adb @adb logcat -d -s WLpagerender | Out-String
    if ($dump -match "DONE ") { $done = $true; break }
    Write-Host ("... 等渲染：" + (($dump -split "`n" | Where-Object { $_ -match "URL " }).Count) + " 页已开始")
}
Write-Host $dump
if (-not $done) { Write-Host "没等到 DONE 那行，上面是已经打出来的部分" -ForegroundColor DarkYellow }
if (-not $KeepInstalled) { & adb @adb uninstall com.rikkahub.wordlite.pagerender | Out-Null }