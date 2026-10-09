# Word Lite -- "同样 120 次请求、同样 180 秒，这一篇有多少字真进了比对"的量台（不是闸门）。
#
# 它回答的是 2.6.2 那轮真机读数留下的问题：120 次请求走到 8/49 个检索窗口就报"检索请求已达上限"，
# 覆盖 4188/19967 字。钱花到哪儿去了、换个花法多覆盖多少字，都在这一台上量。
# 四种模式（plan/stub 一个真请求都不发；latency/live 出网）：
#   plan     只算额度分配：每种花法排上几扇窗、覆盖多少字、每源问几次（秒级，默认）
#   stub     起九个回环桩（借 tests/RetrievalCoverageRegression 的夹具），按逐源延迟跑一轮完整 scan 量墙钟
#   latency  真联网一家一次，量逐源响应耗时，喂给 stub 当延迟表
#   live     真联网按出厂参数跑一轮产品链路
#
# 用法：
#   pwsh tools/coverage-budget-probe.ps1                                    # plan 档，样稿
#   pwsh tools/coverage-budget-probe.ps1 -Mode latency -Proxy 127.0.0.1:7897
#   pwsh tools/coverage-budget-probe.ps1 -Mode stub -Windows 12 -Delay "cnki=1221;cqvip=1922;..."
#   pwsh tools/coverage-budget-probe.ps1 -Mode live -Proxy 127.0.0.1:7897 -Windows 12
# 改前改后要各量一次：改前那轮在 main 的快照里跑同一条命令（引擎代码取 commit 原样，只给夹具补上借桩出口），
# 两边报的是同一把尺上的两个数。
param(
    [string]$Doc = "tests/samples/input-liu.docx",
    [ValidateSet("plan", "stub", "latency", "live")][string]$Mode = "plan",
    [string]$Proxy = "",
    [int]$Windows = 12,
    [string]$Engines = "",
    [string]$Delay = "0",
    [string]$Label = "本机",
    [string]$Out = "",
    [string]$JavaHome = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not (Test-Path $Doc)) { throw "no such manuscript: $Doc" }
if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin/javac.exe"))) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome "bin") + ";" + $env:PATH
}
if (-not $Out) { $Out = "artifacts/tmp/coverage-budget-$Mode.txt" }

$classes = Join-Path $root "artifacts/build/coverage-probe"
$gen     = Join-Path $root "artifacts/build/host-gen"
$android = Join-Path $root "tools/android-35.jar"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
# 与 tools/test-host.ps1 同一份编译桩：aapt2 才是生产 R 类的出处，这里只需要那几个 id。
$rdir = Join-Path $gen "com/rikkahub/wordlite"
New-Item -ItemType Directory -Force -Path $rdir | Out-Null
$stub = @(
    "package com.rikkahub.wordlite;",
    "public final class R {",
    "    public static final class drawable { public static final int ic_file = 1; public static final int ic_hero = 2; }",
    "    public static final class layout { public static final int simple_spinner_dropdown_item = 3; }",
    "}"
)
Set-Content -Encoding utf8NoBOM -Path (Join-Path $rdir "R.java") -Value $stub

Write-Host "== compile probe closure =="
$appFiles = @(Get-ChildItem -Recurse -File -Filter *.java (Join-Path $root "app/src/main/java") | ForEach-Object { $_.FullName })
$appFiles += (Join-Path $rdir "R.java")
$listing = Join-Path ([System.IO.Path]::GetTempPath()) ("wordlite-cov-" + [Guid]::NewGuid().ToString("N") + ".txt")
Set-Content -Encoding ascii -Path $listing -Value $appFiles
& javac -nowarn -encoding UTF-8 -classpath $android -d $classes "@$listing"
$code = $LASTEXITCODE
Remove-Item $listing -ErrorAction SilentlyContinue
if ($code -ne 0) { throw "app compile failed" }
# 量台要用 RetrievalCoverageRegression 的九个回环桩做延迟注入，所以连它一起编。
& javac -nowarn --add-modules jdk.httpserver -encoding UTF-8 -classpath "$classes;$android" -d $classes `
    (Join-Path $root "tests/CoverageBudgetProbe.java") (Join-Path $root "tests/RetrievalCoverageRegression.java")
if ($LASTEXITCODE -ne 0) { throw "probe compile failed" }

Write-Host "== CoverageBudgetProbe ($Mode) ==" -ForegroundColor Cyan
$argv = @("--doc=$Doc", "--mode=$Mode", "--windows=$Windows", "--delay=$Delay", "--label=$Label", "--out=$Out")
if ($Proxy)   { $argv += "--proxy=$Proxy" }
if ($Engines) { $argv += "--engines=$Engines" }
& java "-Dfile.encoding=UTF-8" -classpath "$classes;$android" "com.rikkahub.wordlite.CoverageBudgetProbe" @argv
exit $LASTEXITCODE
