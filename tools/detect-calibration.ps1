# Word Lite - 检测阈值标定台：拿真实论文正文重跑 CalibrationSweep，把标定表落成可复现的产物。
# 口径在 tests/CalibrationSweep.java 里：正例是原文句子的六种写法（原样、数字换写法、去掉标点、
# 相邻两句拼接、前后半句调换、同义替换），负例是同一篇论文里内容无关的句子；负例故意和正例同源，
# 术语/刊名/方法名全撞在一起，是最难也最诚实的一关。BAND 行是锚点层，ENGINE 行跑真正的 match()。
#
# 这个脚本只补 host-classes / host-test-classes 里缺的 .class，绝不清空 artifacts/build，
# 也不会去跑 tools/test-host.ps1（那个脚本开头就把这两个目录 Remove-Item 掉了）。
#
# Usage:  pwsh tools/detect-calibration.ps1 [-Corpus <论文正文.txt>] [-Foreign <别家论文片段.txt>]
#         [-Out <sweep.txt>] [-Classes <dir>]
param(
    [string]$Corpus = "",
    [string]$Out = "",
    [string]$Classes = "",
    [string]$Foreign = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# GBK 代码页下 javac/java 的控制台中文会糊成乱码，全程按 UTF-8 走，退出再还原。
$console = $null
try {
    $console = [Console]::OutputEncoding
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

if (-not $Corpus)  { $Corpus  = Join-Path $root "tests/corpus/real-prose.txt" }
if (-not $Foreign) { $Foreign = Join-Path $root "tests/corpus/cnki-cross.txt" }
if (-not $Out)     { $Out     = Join-Path $root "artifacts/corpus/sweep.txt" }
if (-not $Classes) { $Classes = Join-Path $root "artifacts/build" }
if (-not (Test-Path -LiteralPath $Corpus -PathType Leaf)) {
    throw ("找不到文库正文：" + $Corpus + "（每行一段真实论文正文）。用 -Corpus 指一个存在的 txt，" +
           "或先把语料放进 tests/corpus/real-prose.txt（每行一段，别混进目录行与参考文献）。")
}
# .NET 的当前目录不跟 Set-Location 走，所以送给 [System.IO.File] 的路径一律先取绝对路径。
$corpusPath = (Resolve-Path -LiteralPath $Corpus).Path
$outDir = Split-Path -Parent $Out
if (-not $outDir) { $outDir = (Get-Location).Path }
if (-not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
$outPath = Join-Path (Resolve-Path -LiteralPath $outDir).Path (Split-Path -Leaf $Out)

$android     = Join-Path $root "tools/android-35.jar"
$appClasses  = Join-Path $Classes "host-classes"
$testClasses = Join-Path $Classes "host-test-classes"
if (-not (Test-Path -LiteralPath $android)) { throw ("missing input: " + $android) }

function Invoke-Tool {
    # 自己起进程并显式按 UTF-8 解码：PowerShell 的 > 重定向会写 UTF-16，而在
    # $ErrorActionPreference = "Stop" 下原生命令往 stderr 吐一行警告就能把脚本打断。
    param([string]$exe, [string[]]$argv)
    $shown = ($argv | ForEach-Object { if ($_ -match "\s") { '"' + $_ + '"' } else { $_ } }) -join " "
    Write-Host ("> " + (Split-Path -Leaf $exe) + " " + $shown) -ForegroundColor DarkGray
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.WorkingDirectory = $root
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    foreach ($a in $argv) { [void]$psi.ArgumentList.Add($a) }
    $p = [System.Diagnostics.Process]::Start($psi)
    $stdout = $p.StandardOutput.ReadToEndAsync()
    $stderr = $p.StandardError.ReadToEndAsync()
    $p.WaitForExit()
    foreach ($l in @($stderr.Result -split "`r?`n")) { if ($l.Trim() -ne "") { Write-Host $l } }
    $lines = @($stdout.Result -split "`r?`n")
    while ($lines.Count -gt 0 -and $lines[$lines.Count - 1] -eq "") { $lines = @($lines | Select-Object -SkipLast 1) }
    return [pscustomobject]@{ Exit = $p.ExitCode; Lines = $lines }
}

function Test-PackageClass([string]$dir) {
    if (-not (Test-Path -LiteralPath $dir)) { return $false }
    $pkg = Join-Path $dir "com/rikkahub/wordlite"
    if (-not (Test-Path -LiteralPath $pkg)) { return $false }
    return [bool](Get-ChildItem -LiteralPath $pkg -Recurse -File -Filter *.class -ErrorAction SilentlyContinue |
        Select-Object -First 1)
}

# --- 只补缺：app 类、CalibrationSweep 的 class，缺什么补什么 ---
$jdk = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/javac.exe"))) { $jdk = $env:JAVA_HOME }
elseif (Test-Path "C:/Program Files/Microsoft/jdk-17.0.7.7-hotspot/bin/javac.exe") { $jdk = "C:/Program Files/Microsoft/jdk-17.0.7.7-hotspot" }
elseif (Get-Command javac.exe -ErrorAction SilentlyContinue) { $jdk = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe).Source) }
else { throw "no JDK found; set JAVA_HOME or put javac on PATH" }
$javac = Join-Path $jdk "bin/javac.exe"
$java  = Join-Path $jdk "bin/java.exe"
foreach ($t in @($javac, $java)) { if (-not (Test-Path -LiteralPath $t)) { throw ("missing tool: " + $t) } }
Write-Host ("toolchain: " + $jdk) -ForegroundColor DarkGray

if (-not (Test-PackageClass $appClasses)) {
    New-Item -ItemType Directory -Force -Path $appClasses | Out-Null
    # 出包的 R 类由 aapt2 生成，host 编译只要 app 碰到的那几个 id；桩写在临时目录，
    # 不去动 artifacts/build/host-gen 里的任何东西。
    $stubRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("wordlite-cal-" + [Guid]::NewGuid().ToString("N"))
    $rdir = Join-Path $stubRoot "com/rikkahub/wordlite"
    New-Item -ItemType Directory -Force -Path $rdir | Out-Null
    $stub = @(
        "package com.rikkahub.wordlite;",
        "/** Host compile stub; the shipping R class is generated by aapt2. */",
        "public final class R {",
        "    public static final class drawable { public static final int ic_file = 1; public static final int ic_hero = 2; }",
        "    public static final class layout { public static final int simple_spinner_dropdown_item = 3; }",
        "}"
    )
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $rdir "R.java") -Value $stub
    $appFiles = @(Get-ChildItem -Recurse -File -Filter *.java (Join-Path $root "app/src/main/java") | ForEach-Object { $_.FullName })
    $appFiles += (Join-Path $rdir "R.java")
    # argfile 里的反斜杠会被 javac 当转义符，所以统一换成正斜杠。
    $listing = Join-Path $stubRoot "app-sources.txt"
    Set-Content -Encoding ascii -Path $listing -Value ($appFiles -replace "\\", "/")
    Write-Host ("== javac app ({0} files) -> {1}" -f $appFiles.Count, $appClasses)
    $r = Invoke-Tool $javac @("-J-Dfile.encoding=UTF-8", "-nowarn", "-encoding", "UTF-8",
        "-classpath", $android, "-d", $appClasses, ("@" + ($listing -replace "\\", "/")))
    Remove-Item -Recurse -Force $stubRoot -ErrorAction SilentlyContinue
    if ($r.Exit -ne 0) { throw ("app compile failed (exit " + $r.Exit + ")：CalibrationSweep 依赖 app 类，先看上面的 javac 报错。") }
} else {
    Write-Host ("host-classes 已有 app 类，跳过编译：" + $appClasses) -ForegroundColor DarkGray
}

$sweepClass = Join-Path $testClasses "com/rikkahub/wordlite/CalibrationSweep.class"
if (-not (Test-Path -LiteralPath $sweepClass)) {
    New-Item -ItemType Directory -Force -Path $testClasses | Out-Null
    $sweepSrc = Join-Path $root "tests/CalibrationSweep.java"
    if (-not (Test-Path -LiteralPath $sweepSrc)) { throw ("找不到标定台源码：" + $sweepSrc) }
    Write-Host ("== javac CalibrationSweep -> " + $testClasses)
    $r = Invoke-Tool $javac @("-J-Dfile.encoding=UTF-8", "-nowarn", "--add-modules", "jdk.httpserver",
        "-encoding", "UTF-8", "-classpath", ($appClasses + ";" + $android), "-sourcepath", (Join-Path $root "tests"),
        "-d", $testClasses, $sweepSrc)
    if ($r.Exit -ne 0) { throw ("test compile failed (exit " + $r.Exit + ")：tests/CalibrationSweep.java 编不过；脚本不改 Java，把报错交回改这个文件的人。") }
} else {
    Write-Host ("CalibrationSweep.class 已就位，跳过编译：" + $testClasses) -ForegroundColor DarkGray
}

# --- 跑标定台 ---
Write-Host "== CalibrationSweep =="
$cp = $testClasses + ";" + $appClasses + ";" + $android
$sweepArgs = @("-Dfile.encoding=UTF-8", "-classpath", $cp, "com.rikkahub.wordlite.CalibrationSweep", $corpusPath)
# 第二个语料是跨库探针：文库是 A 篇论文，探针换成别家论文的片段，测的是"库里的别人的论文"
# 和"稿子里别人的论文"会不会互相认亲。没有这个文件就只跑同库那几档，不算错。
$foreignPath = ""
if ($Foreign -and (Test-Path -LiteralPath $Foreign -PathType Leaf)) {
    $foreignPath = (Resolve-Path -LiteralPath $Foreign).Path
    $sweepArgs += $foreignPath
}
$r = Invoke-Tool $java $sweepArgs
if ($r.Exit -ne 0) { throw ("CalibrationSweep 运行失败 exit=" + $r.Exit + "（文库=" + $corpusPath + "）；stderr 见上。") }
$lines = @($r.Lines)
if ($lines.Count -eq 0) { throw "CalibrationSweep 没有任何输出，标定表是空的。" }

[System.IO.File]::WriteAllLines($outPath, $lines, $utf8NoBom)

# --- 给人眼看拐点：头两行 + 误报最小的 8 行 BAND ---
foreach ($l in ($lines | Select-Object -First 2)) { Write-Host $l }
$band = New-Object System.Collections.ArrayList
for ($i = 0; $i -lt $lines.Count; $i++) {
    $m = [regex]::Match($lines[$i], "falsePositive=\s*([0-9]+(?:\.[0-9]+)?)")
    if ($lines[$i].StartsWith("BAND ") -and $m.Success) {
        [void]$band.Add([pscustomobject]@{ No = $i; Fp = [double]$m.Groups[1].Value; Text = $lines[$i] })
    }
}
if ($band.Count -eq 0) { Write-Host "warn: 没有 BAND 行，拐点得自己看全文。" -ForegroundColor Yellow }
Write-Host ("---- 误报最小的 {0} 行 BAND（召回还顶得住、误报掉下来那一端就是拐点）----" -f [Math]::Min(8, $band.Count)) -ForegroundColor DarkGray
foreach ($b in ($band | Sort-Object Fp, No | Select-Object -First 8)) { Write-Host $b.Text }

$nCorpus  = @($lines | Where-Object { $_.StartsWith("CORPUS ") }).Count
$nEngine  = @($lines | Where-Object { $_.StartsWith("ENGINE ") }).Count
Write-Host ("标定台口径：正例是原文句子的六种写法（原样/数字换写法/去掉标点/相邻两句拼接/前后半句调换/同义替换），" +
    "负例是同一篇论文里内容无关的句子（同源负例，术语全撞车）。文库 {0} -> 产物 {1}（{2} 行：CORPUS {3} / ENGINE {4} / BAND {5}）" -f
    $corpusPath, $outPath, $lines.Count, $nCorpus, $nEngine, $band.Count) | Out-Host

if ($console) { try { [Console]::OutputEncoding = $console } catch { } }
