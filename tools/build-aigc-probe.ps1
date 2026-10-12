# Word Lite - on-device AIGC probe.
# Builds a small standalone APK (app sources + the shipped aigc tables only, no fonts), installs it,
# pushes text samples, runs AigcDetector on the phone and prints what the phone actually read.
# Usage: pwsh tools/build-aigc-probe.ps1 -Serial <adb-serial> [-File a.txt -File b.txt]
#        pwsh tools/build-aigc-probe.ps1                     # build only, no device
param(
    [string]$Serial = "",
    [string[]]$File = @(),
    [string]$FileList = "",
    [string]$Tree = ""
)
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$javaHome = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/javac.exe"))) { $env:JAVA_HOME } else { "" }
$javac = if ($javaHome) { Join-Path $javaHome "bin/javac.exe" } else { "javac" }
$java = if ($javaHome) { Join-Path $javaHome "bin/java.exe" } else { "java" }
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $root "artifacts/host-tools/android-sdk" }
$BuildTools = Join-Path $sdk "build-tools/35.0.0"
if (-not (Test-Path (Join-Path $BuildTools "aapt2.exe"))) { throw "aapt2 not under $BuildTools" }
$aapt2 = Join-Path $BuildTools "aapt2.exe"
$zipalign = Join-Path $BuildTools "zipalign.exe"
$apksigner = Join-Path $BuildTools "lib/apksigner.jar"
$android = "$root/tools/android-35.jar"
$d8 = "$root/tools/d8.jar"
$keystore = "$root/tools/debug.keystore"

$work = Join-Path $root "artifacts/build/aigc-probe"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force -Path "$work/gen/com/rikkahub/wordlite", "$work/classes", "$work/dex", "$work/assets" | Out-Null

# 只把 AIGC 那几张表打进这个包：字体是排版那条线要的，探针不需要，带了就是 200 MB。
Copy-Item -Recurse -Force "$root/app/src/main/assets/aigc" "$work/assets/aigc"
Write-Host ("随包判据表 {0} 个文件" -f (Get-ChildItem "$work/assets/aigc" -File).Count)

# 应用源码引用 R 里的 id，探针不 inflate 界面，桩 id 就够编译。
Set-Content -Encoding utf8NoBOM -Path "$work/gen/com/rikkahub/wordlite/R.java" -Value @(
  "package com.rikkahub.wordlite;",
  "public final class R {",
  "    public static final class drawable { public static final int ic_file = 1; public static final int ic_hero = 2; }",
  "    public static final class layout { public static final int simple_spinner_dropdown_item = 3; }",
  "}")

if (-not $Tree) { $Tree = $root }
if (-not (Test-Path (Join-Path $Tree "app/src/main/java"))) { throw "$Tree 里没有 app/src/main/java" }
Write-Host ("源码树 {0}" -f $Tree)
& $aapt2 link -o "$work/linked.apk" --manifest "tools/device-probe/AigcManifest.xml" -I $android --java "$work/aapt-gen" -A "$work/assets"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$sources = @(Get-ChildItem -Recurse -File -Filter *.java (Join-Path $Tree "app/src/main/java") | ForEach-Object { $_.FullName })
$sources += "$work/gen/com/rikkahub/wordlite/R.java"
$sources += "$root/tools/device-probe/AigcProbeActivity.java"
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

# Windows 上的 aapt2 用反斜杠拼 -A 的子目录，资产会变成 "assets\aigc\x.tsv"，
# 而 AssetManager 只认 "/"。build-host.ps1 里同一件事叫 normalize asset paths，这里照做一遍。
$renamed = 0
$zip = [System.IO.Compression.ZipFile]::Open("$work/linked.apk", [System.IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($e in @($zip.Entries | Where-Object { $_.FullName.Contains("\") })) {
        $name = $e.FullName
        $fixed = $name.Replace("\", "/")
        $buffer = New-Object System.IO.MemoryStream
        $in = $e.Open()
        try { $in.CopyTo($buffer) } finally { $in.Dispose() }
        $bytes = $buffer.ToArray()
        $buffer.Dispose()
        $new = $zip.CreateEntry($fixed, [System.IO.Compression.CompressionLevel]::Optimal)
        $outstream = $new.Open()
        try { $outstream.Write($bytes, 0, $bytes.Length) } finally { $outstream.Dispose() }
        $e.Delete()
        $renamed++
    }
} finally { $zip.Dispose() }
Write-Host ("renamed {0} entries to slash separators" -f $renamed)
$check = [System.IO.Compression.ZipFile]::OpenRead("$work/linked.apk")
try {
    $left = @($check.Entries | Where-Object { $_.FullName.Contains("\") })
} finally { $check.Dispose() }
if ($left.Count -gt 0) { throw ("还有 {0} 个资产条目带反斜杠，AssetManager 打不开" -f $left.Count) }

& $zipalign -f 4 "$work/linked.apk" "$work/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
& $java -jar $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias wordlite --out "$work/aigc-probe.apk" "$work/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& $java -jar $apksigner verify "$work/aigc-probe.apk"
Write-Host ("APK {0:N0} bytes" -f (Get-Item "$work/aigc-probe.apk").Length)

if ($FileList) {
    $File = @(Get-Content $FileList -Encoding UTF8 | Where-Object { $_.Trim().Length -gt 0 -and -not $_.Trim().StartsWith("#") } | ForEach-Object { $_.Trim() })
    Write-Host ("清单 $FileList 里读了 {0} 个样本" -f $File.Count)
}
if (-not $Serial) { Write-Host "没给 -Serial，只打包不跑"; return }
$adb = @("-s", $Serial)
& adb @adb install -r -t "$work/aigc-probe.apk"
if ($LASTEXITCODE -ne 0) { throw "install failed" }
& adb @adb logcat -c

$remote = "/sdcard/Android/data/com.rikkahub.wordlite.aigcprobe/files"
& adb @adb shell mkdir -p $remote | Out-Null
$report = "$remote/aigc-probe-report.txt"
& adb @adb shell rm -f $report | Out-Null
$start = @("am", "start", "-n", "com.rikkahub.wordlite.aigcprobe/com.rikkahub.wordlite.AigcProbeActivity")
for ($i = 0; $i -lt $File.Count; $i++) {
    $name = Split-Path -Leaf $File[$i]
    & adb @adb push $File[$i] "$remote/$name" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "adb push $name 失败" }
    $key = if ($i -eq 0) { "file" } else { "file" + ($i + 1) }
    $start += @("--es", $key, $name)
}
& adb @adb shell @start | Out-Null

$out = Join-Path $root "reports/current/aigc-device.log"
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
if (Test-Path "$out.device.txt") { Remove-Item -Force "$out.device.txt" }
# 华为 ROM 的 logcat 会吞行，所以以应用自己写下的那份读数文件为准。
$deadline = (Get-Date).AddSeconds(240)
$done = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 4
    & adb @adb shell "test -s $report" 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $done = $true; break }
}
& adb @adb pull $report "$out.device.txt" 2>&1 | Out-Null
& adb @adb logcat -d -s WLaigc | Set-Content -Encoding utf8NoBOM $out
if ($done) { Get-Content "$out.device.txt" -Encoding UTF8 | Add-Content -Encoding utf8NoBOM $out }
Write-Host ("读数写到 {0}" -f $out)
if (-not $done) { throw "手机上没等到读数文件，看 $out" }
