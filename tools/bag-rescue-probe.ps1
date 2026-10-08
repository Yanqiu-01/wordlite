# Word Lite - 袋口径"取候选"那一路的探针：机制（倒过来写的句子）+ 开销（多精算几个候选）。
# 用法: pwsh tools/bag-rescue-probe.ps1 [-Corpus <论文正文.txt>] [-Classes <dir>]
param(
    [string]$Corpus = "",
    [string]$Classes = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
try { $console = [Console]::OutputEncoding; [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
if (-not $Corpus)  { $Corpus = Join-Path $root "tests/corpus/real-prose.txt" }
if (-not $Classes) { $Classes = Join-Path $root "artifacts/build/host-classes" }
$tests   = Join-Path $root "artifacts/build/host-test-classes"
$android = Join-Path $root "tools/android-35.jar"
if (-not (Test-Path (Join-Path $Classes "com/rikkahub/wordlite/TextCorpus.class"))) {
    throw "先跑 pwsh tools/test-host.ps1（或 tools/build-host.ps1）把 class 编出来"
}
& javac -nowarn -encoding UTF-8 -classpath "$Classes;$android" -d $tests (Join-Path $root "tests/BagRescueProbe.java")
if ($LASTEXITCODE -ne 0) { throw "编译失败" }
& java "-Dfile.encoding=UTF-8" -cp "$tests;$Classes;$android" com.rikkahub.wordlite.BagRescueProbe $Corpus
$code = $LASTEXITCODE
if ($console) { try { [Console]::OutputEncoding = $console } catch { } }
if ($code -ne 0) { throw "探针失败（退出码 $code）" }