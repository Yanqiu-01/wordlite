# Word Lite - AIGC 特征/系数标定台（0.7.0）。口径全在 tests/AigcCalibrationSweep.java 里，本脚本只管编译与跑。
# 要读的四类行：FPB 真人误报余量（含"最像机写的真人句子离门槛多远"）、TIER 档位与双门槛（含 run=1 对照组）、
# GRID 人机双向同屏的候选系数行、MARGIN 真人钉子户逐条余量；OVERLAP 行把"骨架词与打分特征同源"这个循环摆在产物最前面。
# 产物 artifacts/corpus/aigc-sweep.txt，读数解释写进 docs/aigc-calibration.md。
#
# 这个脚本只往 artifacts/build/aigc-check/ 里写 .class：不去碰 host-classes / host-test-classes，
# 也不跑 tools/test-host.ps1（那套目录现在归检索链路的改动用，会互相踩）。
param(
    [string]$Corpus = "",
    [string]$Frames = "",
    [string]$Cartoon = "",
    [string]$Out = "",
    [string]$Classes = ""
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
if (-not $Frames)  { $Frames  = Join-Path $root "tests/corpus/aigc-frames.txt" }
if (-not $Cartoon) { $Cartoon = Join-Path $root "tests/corpus/aigc-cartoon.txt" }
if (-not $Out)     { $Out     = Join-Path $root "artifacts/corpus/aigc-sweep.txt" }
if (-not $Classes) { $Classes = Join-Path $root "artifacts/build/aigc-check" }
if (-not (Test-Path -LiteralPath $Corpus -PathType Leaf)) {
    throw ("找不到真人论文正文：" + $Corpus + "（每行一段正文）。FPB/TIER/MARGIN 全靠它，缺了就没有误报余量可言。")
}
if (-not (Test-Path -LiteralPath $Frames -PathType Leaf)) {
    throw ("找不到机器腔骨架表：" + $Frames + "（M2 弱标签就是拿它拼真人句造的）。")
}
# .NET 的当前目录不跟 Set-Location 走，所以送给 [System.IO.File] 的路径一律先取绝对路径。
$corpusPath = (Resolve-Path -LiteralPath $Corpus).Path
$framePath  = (Resolve-Path -LiteralPath $Frames).Path
$cartoonPath = ""
if (Test-Path -LiteralPath $Cartoon -PathType Leaf) { $cartoonPath = (Resolve-Path -LiteralPath $Cartoon).Path }
$outDir = Split-Path -Parent $Out
if (-not $outDir) { $outDir = (Get-Location).Path }
if (-not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
$outPath = Join-Path (Resolve-Path -LiteralPath $outDir).Path (Split-Path -Leaf $Out)

$android     = Join-Path $root "tools/android-35.jar"
$appClasses  = Join-Path $Classes "classes"
$testClasses = Join-Path $Classes "test-classes"

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
    if ($null -ne $psi.ArgumentList) {
        foreach ($a in $argv) { [void]$psi.ArgumentList.Add($a) }
    } else {
        # Windows PowerShell 5.1 的 ProcessStartInfo 没有 ArgumentList，只能自己拼引号。
        $psi.Arguments = ($argv | ForEach-Object {
            if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
        }) -join " "
    }
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
# --- JDK 探测 ---
$jdk = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/javac.exe"))) { $jdk = $env:JAVA_HOME }
elseif (Test-Path "C:/Program Files/Microsoft/jdk-17.0.7.7-hotspot/bin/javac.exe") { $jdk = "C:/Program Files/Microsoft/jdk-17.0.7.7-hotspot" }
elseif (Get-Command javac.exe -ErrorAction SilentlyContinue) { $jdk = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe).Source) }
else { throw "no JDK found; set JAVA_HOME or put javac on PATH" }
$javac = Join-Path $jdk "bin/javac.exe"
$java  = Join-Path $jdk "bin/java.exe"
foreach ($t in @($javac, $java)) { if (-not (Test-Path -LiteralPath $t)) { throw ("missing tool: " + $t) } }
Write-Host ("toolchain: " + $jdk) -ForegroundColor DarkGray
New-Item -ItemType Directory -Force -Path $testClasses | Out-Null

# --- app 类：只补缺，且只写进 artifacts/build/aigc-check/classes ---
if (-not (Test-PackageClass $appClasses)) {
    if (-not (Test-Path -LiteralPath $android)) { throw ("missing input: " + $android) }
    New-Item -ItemType Directory -Force -Path $appClasses | Out-Null
    # 出包的 R 类由 aapt2 生成，host 编译只要 app 碰到的那几个 id；桩写在临时目录，不碰任何既有产物目录。
    $stubRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("wordlite-aigc-cal-" + [Guid]::NewGuid().ToString("N"))
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
    # -Encoding utf8NoBOM 只有 pwsh 7 认，这里直接走 .NET，两个 shell 都能跑。
    [System.IO.File]::WriteAllLines((Join-Path $rdir "R.java"), $stub, $utf8NoBom)
    $appFiles = @(Get-ChildItem -Recurse -File -Filter *.java (Join-Path $root "app/src/main/java") | ForEach-Object { $_.FullName })
    $appFiles += (Join-Path $rdir "R.java")
    # argfile 里的反斜杠会被 javac 当转义符，所以统一换成正斜杠。
    $listing = Join-Path $stubRoot "app-sources.txt"
    [System.IO.File]::WriteAllText($listing, (($appFiles -replace "\\", "/") -join "`n"), [System.Text.Encoding]::ASCII)
    Write-Host ("== javac app ({0} files) -> {1}" -f $appFiles.Count, $appClasses)
    $r = Invoke-Tool $javac @("-J-Dfile.encoding=UTF-8", "-nowarn", "-encoding", "UTF-8",
        "-classpath", $android, "-d", $appClasses, ("@" + ($listing -replace "\\", "/")))
    Remove-Item -Recurse -Force $stubRoot -ErrorAction SilentlyContinue
    if ($r.Exit -ne 0) { throw ("app compile failed (exit " + $r.Exit + ")：标定台直接用 app 里的 Aigc* 类，先看上面的 javac 报错。") }
} else {
    Write-Host ("aigc-check/classes 已有 app 类，跳过全量编译：" + $appClasses) -ForegroundColor DarkGray
    Write-Host "   （改了 app 里的 Aigc* 就自己删了这个目录再跑，否则读的是旧 class 的数。）" -ForegroundColor DarkGray
}

# --- 标定台本身每次都重编：一个文件两秒钟，换来的是"表里的数不可能来自旧代码" ---
$sweepSrc = Join-Path $root "tests/AigcCalibrationSweep.java"
if (-not (Test-Path -LiteralPath $sweepSrc)) { throw ("找不到标定台源码：" + $sweepSrc) }
Write-Host ("== javac AigcCalibrationSweep -> " + $testClasses)
$r = Invoke-Tool $javac @("-J-Dfile.encoding=UTF-8", "-nowarn", "--add-modules", "jdk.httpserver",
    "-encoding", "UTF-8", "-classpath", ($appClasses + ";" + $android), "-sourcepath", (Join-Path $root "tests"),
    "-d", $testClasses, $sweepSrc)
if ($r.Exit -ne 0) { throw ("test compile failed (exit " + $r.Exit + ")：tests/AigcCalibrationSweep.java 编不过；脚本不改 Java，把报错交回改这个文件的人。") }

# --- 跑标定台 ---
Write-Host "== AigcCalibrationSweep =="
$cp = $testClasses + ";" + $appClasses + ";" + $android
function Get-RepoRelative([string]$absolute) {
    # 产物里 human-path= 那一行要进版本库，不能把某台机器的绝对路径钉进去。
    if ($absolute -like ($root + "*")) {
        return ($absolute.Substring($root.Length).TrimStart("\", "/") -replace "\\", "/")
    }
    return $absolute
}
$sweepArgs = @("-Dfile.encoding=UTF-8", "-classpath", $cp, "com.rikkahub.wordlite.AigcCalibrationSweep",
    (Get-RepoRelative $corpusPath), (Get-RepoRelative $framePath))
if ($cartoonPath) { $sweepArgs += (Get-RepoRelative $cartoonPath) }
$r = Invoke-Tool $java $sweepArgs
if ($r.Exit -ne 0) { throw ("AigcCalibrationSweep 运行失败 exit=" + $r.Exit + "（文库=" + $corpusPath + "）；stderr 见上。") }
$lines = @($r.Lines)
if ($lines.Count -eq 0) { throw "AigcCalibrationSweep 没有任何输出，标定表是空的。" }
[System.IO.File]::WriteAllLines($outPath, $lines, $utf8NoBom)

# --- 给人眼看结论：门槛口径 + 真人余量 + 候选行读数的规则 + 钉子户前几条 ---
foreach ($l in ($lines | Select-Object -First 2)) { Write-Host $l }
foreach ($tag in @("OVERLAP note", "LEGACY ", "GRADE ", "FPB ", "RUNS ", "RULE ", "LONG3 ", "PICK ")) {
    foreach ($l in ($lines | Where-Object { $_.StartsWith($tag) })) { Write-Host $l }
}
Write-Host "---- TIER（含 run=1 对照组：单句即可成档时真人侧立刻非零）----" -ForegroundColor DarkGray
foreach ($l in ($lines | Where-Object { $_.StartsWith("TIER ") })) { Write-Host $l }
Write-Host "---- MARGIN（真人侧最像机写的句子，逐条余量）----" -ForegroundColor DarkGray
foreach ($l in ($lines | Where-Object { $_.StartsWith("MARGIN ") })) { Write-Host $l }
Write-Host "---- GRID 里与落表系数同一行 + 预算内最优行 ----" -ForegroundColor DarkGray
foreach ($l in ($lines | Where-Object { $_.StartsWith("GRID ") })) {
    if ($l -match "cliche=0\.350 frame=0\.200 rhythm=0\.150 lexis=0\.100") { Write-Host $l -ForegroundColor Cyan }
}

$nFpb   = @($lines | Where-Object { $_.StartsWith("FPB ") }).Count
$nTier  = @($lines | Where-Object { $_.StartsWith("TIER ") }).Count
$nGrid  = @($lines | Where-Object { $_.StartsWith("GRID ") }).Count
$nMargin = @($lines | Where-Object { $_.StartsWith("MARGIN ") }).Count
$said = "标定台口径：真人侧 = " + $corpusPath + " 的逐段正文（唯一的真人负例），机写侧 = 骨架拼真人句（M2，" +
    "与打分特征表同源）和漫画式机写稿（M3）。落表系数只是由真人误报预算反推的上限，版本号 v1-order-only，不叫已标定。"
Write-Host $said | Out-Host
Write-Host ("产物 {0}（{1} 行：FPB {2} / TIER {3} / GRID {4} / MARGIN {5}）；读数解释见 docs/aigc-calibration.md" -f
    $outPath, $lines.Count, $nFpb, $nTier, $nGrid, $nMargin) | Out-Host

if ($console) { try { [Console]::OutputEncoding = $console } catch { } }