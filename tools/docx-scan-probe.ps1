# Word Lite - 拿真稿子跑一遍产品查重链路，并把每一次对外请求的账落全（不是闸门，真联网，一轮上百个请求）。
#
# 它回答的就是用户问的那个问题：这篇稿子跑一遍，最后有几篇真把正文抓进了比对、覆盖率多少、
# 花了多少流量与时间、命中了谁。改前改后各跑一次同一份稿子，报的就是同一把尺上的两个数。
# 与 tools/full-scan-probe.ps1 的分工：那台自己造稿件种句子，量"种下去抓不抓得回"；
# 这一台吃真 docx，量"这篇稿子实际拿到了什么"。
#
# 用法：
#   pwsh tools/docx-scan-probe.ps1                                   # 直连优先
#   pwsh tools/docx-scan-probe.ps1 -Proxy 127.0.0.1:7897             # 外网走本机代理（OpenAlex 等）
#   pwsh tools/docx-scan-probe.ps1 -Docx tests/samples/input-liu.docx -Out artifacts/tmp/liu-after
#   pwsh tools/docx-scan-probe.ps1 -Engines openalex -Windows 6      # 只问一家：OpenAlex 按出口 IP 计配额
#   pwsh tools/docx-scan-probe.ps1 -Cmap ""                          # 不装随包 CID 表，量取字水平
#   pwsh tools/docx-scan-probe.ps1 -Engines openalex -OpenAlex http://127.0.0.1:8797
#       OpenAlex 按出口 IP 计配额，Routes 对海外源又是代理优先；把这一家指到本机转发器
#       （tools/openalex-relay.py，它直连上游），才能把配额花在还有货的那条出口上。
#
# 中文库（知网/万方/维普）不吃这个 -Proxy 之外的额外配置：Limits.proxy 只是一个 host:port，
# Routes 会先试显式路由、再试直连与自动发现的端口，报告里 via= 那一列才是真走了哪条路。
param(
    [string]$Docx = "tests/samples/input-liu.docx",
    [string]$Out = "artifacts/tmp/docx-scan",
    [string]$Proxy = "",
    [int]$Per = 12,
    [int]$Windows = 12,
    [int]$FullTexts = 10,
    [string]$Engines = "",
    [string]$Cmap = "app/src/main/assets/cmaps/adobe-gb1.cid",
    [string]$OpenAlex = "",
    [string]$JavaHome = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not (Test-Path $Docx)) { throw "no such manuscript: $Docx" }
if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin/javac.exe"))) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome "bin") + ";" + $env:PATH
}

$classes = Join-Path $root "artifacts/build/docx-scan-probe"
$android = Join-Path $root "tools/android-35.jar"
New-Item -ItemType Directory -Force -Path $classes | Out-Null

# DocxParser imports android.content.Context, so the probe closes against the SDK stub jar;
# nothing in the closure calls into it on this path, so plain java runs it fine.
Write-Host "== compile probe closure =="
& javac -nowarn -encoding UTF-8 -classpath $android -sourcepath (Join-Path $root "app/src/main/java") `
    -d $classes (Join-Path $root "tests/DocxScanProbe.java")
if ($LASTEXITCODE -ne 0) { throw "probe compile failed" }

Write-Host "== DocxScanProbe (live, production path) ==" -ForegroundColor Cyan
$extra = @()
if ($Cmap -and (Test-Path $Cmap)) { $extra += "--cmap=$Cmap" } else { Write-Warning "no cid table ($Cmap): 取字水平等于随包表之前" }
if ($Engines) { $extra += "--engines=$Engines" }
if ($OpenAlex) { $extra += "--openalex=$OpenAlex" }
& java "-Dfile.encoding=UTF-8" -classpath "$classes;$android" "com.rikkahub.wordlite.DocxScanProbe" `
    $Docx $Out $Proxy $Per $Windows $FullTexts @extra
exit $LASTEXITCODE
