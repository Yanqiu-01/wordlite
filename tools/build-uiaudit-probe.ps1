# 量 ribbon 在真机上的可达性：编一个独立包的探针 APK（com.rikkahub.wordlite.uiaudit），
# 装到手机上跑一遍，把 ribbon-ergonomics.tsv 拉回来——每条横向滚动行里每个控件的左右边界、
# 静止时看得见还是被屏幕边切一刀、滚到底又能看见谁。
# 它不覆盖用户机里的 com.rikkahub.wordlite，也不改它的 applicationId，跑完默认卸掉。
#
# 用法：pwsh tools/build-uiaudit-probe.ps1                 默认量当前工作树
#             [-Tree <源码树>]         改到别的树上量（改动还没提交时量自己的快照）
#             [-OutTsv <tsv>]          量出来的表落在哪
#             [-Serial <adb 序列号>] [-KeepInstalled]
param(
    [string]$Tree = "",
    [string]$OutTsv = "artifacts/analysis/ui-ergonomics/probe.tsv",
    [string]$Serial = "EAMUT20528011355",
    [switch]$KeepInstalled
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$JavaHome = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME/bin/javac.exe")) { $env:JAVA_HOME }
            elseif (Test-Path "C:/Program Files/Microsoft/jdk-17.0.7.7-hotspot/bin/javac.exe") { "C:/Program Files/Microsoft/jdk-17.0.7.7-hotspot" }
            else { throw "没找到 JDK" }
$javac = Join-Path $JavaHome "bin/javac.exe"
$java  = Join-Path $JavaHome "bin/java.exe"
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $root "artifacts/host-tools/android-sdk" }
$BuildTools = Join-Path $sdk "build-tools/35.0.0"
if (-not (Test-Path (Join-Path $BuildTools "aapt2.exe"))) { throw "$BuildTools 里没有 aapt2" }
$aapt2 = Join-Path $BuildTools "aapt2.exe"
$zipalign = Join-Path $BuildTools "zipalign.exe"
$apksigner = Join-Path $BuildTools "lib/apksigner.jar"
$android = "$root/tools/android-35.jar"
$d8 = "$root/tools/d8.jar"
$keystore = "$root/tools/debug.keystore"
$work = Join-Path $root "artifacts/build/uiaudit-probe"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force -Path "$work/gen/com/rikkahub/wordlite", "$work/classes", "$work/dex", "$work/assets" | Out-Null

# app 源码引用 R 字段，探针一个都不 inflate，桩 id 就够（与 build-pagerender-probe.ps1 同一套）。
Set-Content -Encoding utf8NoBOM -Path "$work/gen/com/rikkahub/wordlite/R.java" -Value @(
  "package com.rikkahub.wordlite;",
  "public final class R {",
  "    public static final class drawable { public static final int ic_file = 1; public static final int ic_hero = 2; }",
  "    public static final class layout { public static final int simple_spinner_dropdown_item = 3; }",
  "}")

if (-not $Tree) { $Tree = $root }
if (-not (Test-Path (Join-Path $Tree "app/src/main/java"))) { throw "$Tree 里没有 app/src/main/java" }
Write-Host ("源码树 {0}" -f $Tree)
& $aapt2 link -o "$work/linked.apk" --manifest "tools/device-probe/RibbonErgonomicsManifest.xml" -I $android `
    --java "$work/aapt-gen" -A "$work/assets"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

$sources = @(Get-ChildItem -Recurse -File -Filter *.java (Join-Path $Tree "app/src/main/java") | ForEach-Object { $_.FullName })
$sources += "$work/gen/com/rikkahub/wordlite/R.java"
$sources += "$root/tools/device-probe/RibbonErgonomicsActivity.java"
$sources += "$root/tools/device-probe/ScrollCensusActivity.java"
& $javac -nowarn -encoding UTF-8 -classpath $android -d "$work/classes" $sources
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }
$classes = @(Get-ChildItem -Recurse -File -Filter *.class "$work/classes" | ForEach-Object { $_.FullName -replace "\\", "/" })
Set-Content -Encoding ascii -Path "$work/classes.list" -Value $classes
& $java -cp $d8 com.android.tools.r8.D8 --min-api 23 --lib $android --release --output "$work/dex" ("@" + "$work/classes.list")
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

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
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }
& $java -jar $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias wordlite --out "$work/uiaudit-probe.apk" "$work/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败" }
& $java -jar $apksigner verify "$work/uiaudit-probe.apk"
Write-Host ("APK {0:N0} 字节" -f (Get-Item "$work/uiaudit-probe.apk").Length)

if (-not $Serial) { Write-Host "没给 -Serial，只打包不跑"; return }
$adb = @("-s", $Serial)
& adb @adb install -r -t "$work/uiaudit-probe.apk"
if ($LASTEXITCODE -ne 0) { throw "装不上" }
$component = "com.rikkahub.wordlite.uiaudit/com.rikkahub.wordlite.RibbonErgonomicsActivity"
$remote = "/sdcard/Android/data/com.rikkahub.wordlite.uiaudit/files/ribbon-ergonomics.tsv"
& adb @adb shell rm -f $remote | Out-Null
& adb @adb logcat -c
& adb @adb shell am start -n $component | Out-Null

$deadline = (Get-Date).AddSeconds(75)
$done = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    $dump = & adb @adb logcat -d -s WLuaudit | Out-String
    if ($dump -match "DONE") { $done = $true; break }
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutTsv) | Out-Null
& adb @adb pull $remote $OutTsv | Out-Null
if (-not (Test-Path $OutTsv)) {
    Write-Host "没拉到表，下面是 logcat：" -ForegroundColor Red
    Write-Host $dump
    throw "探针没写出 ribbon-ergonomics.tsv"
}
$rows = @(Get-Content $OutTsv -Encoding UTF8)
Write-Host ("表 {0}：{1} 行（含表头），DONE={2}" -f $OutTsv, $rows.Count, $done)
if (-not $KeepInstalled) { & adb @adb uninstall com.rikkahub.wordlite.uiaudit | Out-Null }