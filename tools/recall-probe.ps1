# Word Lite - live recall rig for the built-in literature search. NOT part of the gate: it dials
# out, spends tens of real requests per run, and depends on three Chinese databases being up.
#
# What it answers, in the only units that matter for a plagiarism check: if a sentence is copied
# verbatim out of a real paper, does the built-in search bring that paper back, does the matcher
# flag the sentence, and does the report name the right paper?
#
# Usage:
#   pwsh tools/recall-probe.ps1                                    # RecallProbe, direct-first
#   pwsh tools/recall-probe.ps1 -Probe CnkiFormProbe               # 知网检索式对比台
#   pwsh tools/recall-probe.ps1 -Probe CqvipAlignProbe             # 维普文献号与摘要对不对行
#   pwsh tools/recall-probe.ps1 -Proxy 127.0.0.1:7897 -Per 12      # 挂代理再量一遍
#   pwsh tools/recall-probe.ps1 -Query "深度学习 图像分割" -Corpus tests/corpus/cnki-cross.txt
#
# -Proxy "" means "no explicit proxy": domestic databases then go out directly first (Routes decides
# the order), which is what a phone on plain mobile data does. A phone on the tether can carry the
# proxy run too: adb reverse tcp:7897 tcp:7897, then -Proxy 127.0.0.1:7897.
param(
    [ValidateSet("RecallProbe", "CnkiFormProbe", "CqvipAlignProbe")]
    [string]$Probe = "RecallProbe",
    [string]$Proxy = "",
    [int]$Per = 12,
    [string]$Engines = "cnki,wanfang,cqvip",
    [string]$Query = "碳化硅 瞬态液相扩散焊 界面组织 中间层 接头性能",
    [string]$Corpus = "tests/corpus/real-prose.txt",
    [int]$Seeds = 4,
    [string]$JavaHome = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if ($Probe -ne "RecallProbe" -and -not (Test-Path "tests/$Probe.java")) { throw "no such probe: tests/$Probe.java" }
if ($Probe -eq "RecallProbe" -and -not (Test-Path $Corpus)) { throw "no such filler corpus: $Corpus" }
if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin/javac.exe"))) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome "bin") + ";" + $env:PATH
}

$classes = Join-Path $root "artifacts/build/recall-probe"
$android = Join-Path $root "tools/android-35.jar"
New-Item -ItemType Directory -Force -Path $classes | Out-Null

# Only the probe's closure is compiled; -sourcepath pulls in exactly the PaperSources/TextCorpus
# subset, none of which imports android.*, so no aapt2 R stub is needed here.
Write-Host "== compile probe closure =="
& javac -nowarn -encoding UTF-8 -classpath $android -sourcepath (Join-Path $root "app/src/main/java") `
    -d $classes (Join-Path $root "tests/$Probe.java")
if ($LASTEXITCODE -ne 0) { throw "probe compile failed" }

$argv = switch ($Probe) {
    "RecallProbe"     { @($Proxy, $Per, $Engines, $Query, $Corpus, $Seeds) }
    "CnkiFormProbe"   { @($(if ($Proxy) { $Proxy } else { "127.0.0.1:7897" }), $Per, $Query) }
    "CqvipAlignProbe" { @($Query) }
}
Write-Host ("== {0} (live) ==" -f $Probe) -ForegroundColor Cyan
& java "-Dfile.encoding=UTF-8" -classpath $classes "com.rikkahub.wordlite.$Probe" @argv
exit $LASTEXITCODE
