# Word Lite - end-to-end rig for the WHOLE-document check, through the production code path.
# NOT part of the gate: it dials out and spends tens of real requests per run.
#
# What it answers, in the units the user actually sees on the report page: after the built-in search
# has run over a whole manuscript, how many candidates really entered the comparison corpus, and does
# a sentence copied verbatim out of a real paper come back flagged and attributed?
#
# tools/recall-probe.ps1 measures one sentence at a time and fills the corpus itself, so it cannot see
# the intake step. That blind spot is how a 0.00% total rate shipped next to a 66.7% recall number:
# phase two ranked candidates against 48 characters squeezed out of the whole document, so everything
# retrieved by a middle-of-the-document window scored zero and never reached the corpus.
#
# Usage:
#   pwsh tools/full-scan-probe.ps1                                    # direct-first
#   pwsh tools/full-scan-probe.ps1 -Proxy 127.0.0.1:7897              # out through the PC's proxy
#   pwsh tools/full-scan-probe.ps1 -Engines cnki,cqvip -Windows 12 -Seeds 2
param(
    [string]$Proxy = "",
    [int]$Per = 12,
    [string]$Engines = "cnki,wanfang,cqvip",
    [string]$Query = "碳化硅 瞬态液相扩散焊 界面组织 中间层 接头性能",
    [string]$Corpus = "tests/corpus/real-prose.txt",
    [int]$Seeds = 2,
    [int]$Windows = 12,
    [string]$JavaHome = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not (Test-Path $Corpus)) { throw "no such filler corpus: $Corpus" }
if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin/javac.exe"))) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome "bin") + ";" + $env:PATH
}

$classes = Join-Path $root "artifacts/build/full-scan-probe"
$android = Join-Path $root "tools/android-35.jar"
New-Item -ItemType Directory -Force -Path $classes | Out-Null

# Only the probe's closure is compiled: -sourcepath pulls in the DuplicateEngine/TextCorpus subset,
# none of which imports android.*, so no aapt2 R stub is needed here.
Write-Host "== compile probe closure =="
& javac -nowarn -encoding UTF-8 -classpath $android -sourcepath (Join-Path $root "app/src/main/java") `
    -d $classes (Join-Path $root "tests/FullScanProbe.java")
if ($LASTEXITCODE -ne 0) { throw "probe compile failed" }

Write-Host "== FullScanProbe (live, production path) ==" -ForegroundColor Cyan
& java "-Dfile.encoding=UTF-8" -classpath $classes "com.rikkahub.wordlite.FullScanProbe" `
    $Proxy $Per $Engines $Query $Corpus $Seeds $Windows
exit $LASTEXITCODE