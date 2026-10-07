# Word Lite - measure CNKI query quality against the live endpoint.
#
# This is NOT part of the gate: it dials out, needs a proxy, and spends ~20 real requests
# per run. It exists because the numbers it prints are the only honest answer to "is the
# query shaping actually helping?", and `tests/CnkiSearchRegression` rightly refuses to
# contact the network.
#
# Usage:
#   pwsh tools/cnki-live-probe.ps1                       # proxy 127.0.0.1:7897, 11 windows
#   pwsh tools/cnki-live-probe.ps1 -Proxy 127.0.0.1:7897 -Windows 20
#   pwsh tools/cnki-live-probe.ps1 -Corpus tests/corpus/cnki-cross.txt
#
# A phone on the tether can also carry this traffic; on the PC the same Clash port works.
param(
    [string]$Proxy = "127.0.0.1:7897",
    [string]$Corpus = "tests/corpus/real-prose.txt",
    [int]$Windows = 11
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not (Test-Path $Corpus)) { throw "no such corpus: $Corpus" }

$classes = Join-Path $root "artifacts/build/live-probe/classes"
$android = Join-Path $root "tools/android-35.jar"
New-Item -ItemType Directory -Force -Path $classes | Out-Null

# Only the probe's closure gets compiled: -sourcepath lets javac pull in exactly the
# CnkiSearch/PaperSources/ApiClient subset, none of which touches R or android.*, so no
# aapt2 stub is needed here (unlike the full-app host gate).
Write-Host "== compile probe closure =="
& javac -nowarn -encoding UTF-8 -classpath $android -sourcepath (Join-Path $root "app/src/main/java") `
    -d $classes (Join-Path $root "tests/CnkiLiveProbe.java")
if ($LASTEXITCODE -ne 0) { throw "probe compile failed" }
Write-Host "== probe (live CNKI via $Proxy) ==" -ForegroundColor Cyan
& java "-Dfile.encoding=UTF-8" -classpath $classes com.rikkahub.wordlite.CnkiLiveProbe $Proxy $Corpus $Windows
exit $LASTEXITCODE
