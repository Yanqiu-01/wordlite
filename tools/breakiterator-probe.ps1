# tools/breakiterator-probe.ps1 -- does Android 10 read a custom BreakIterator in StaticLayout?
#
# The engine cannot reproduce Word's break rules (a Latin run is unbreakable, "-" breaks, "/" does not)
# unless it can hand the platform its own break opportunities. StaticLayout.Builder.setBreakIterator is
# deprecated since API 24 and the framework's own line breaker has been replacing it, so the only honest
# answer is a measurement on the target phone. This builds tools/device-probe/BreakIteratorProbe.java
# into a dex, runs it under app_process, and prints the break offsets of four modes per case:
#   1 default breaker (what the engine does today)   2 + setBreakIterator(word rules)
#   3 word rules with a ReplacementSpan in the text  4 default breaker, same spanned text
# Word's own answers for these same strings are in artifacts/word-break/word-lines.tsv.
#
# Usage: pwsh tools/breakiterator-probe.ps1 [-Serial X]
param(
    [string]$Serial = "EAMUT20528011355",
    # Which probe to build: BreakIteratorProbe (can the platform take our break rules?) or
    # ScriptBreakProbe (does a script/scale span inside a token create a break opportunity?).
    [ValidateSet("BreakIteratorProbe", "ScriptBreakProbe", "TokenBreakProbe")]
    [string]$Probe = "BreakIteratorProbe",
    # TokenBreakProbe lays out the document's own paragraphs when they are on the phone: which w:p,
    # given as the paragraph numbers every other tool uses (tools/para-text.py). Pass "" to run the
    # probe's invented cases only.
    [string]$Paras = "86,94,113")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
function RunAdb([string[]]$a) { & adb -s $Serial @a }

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("wl-" + $Probe.ToLower() + "-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$android = Join-Path $root "tools/android-35.jar"
$classes = Join-Path $tmp "classes"; New-Item -ItemType Directory -Force -Path $classes | Out-Null
& javac -nowarn -encoding UTF-8 -classpath $android -d $classes (Join-Path $root "tools/device-probe/$Probe.java")
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
$jar = Join-Path $tmp "probe.jar"
Push-Location $classes; try { & jar -cf $jar . } finally { Pop-Location }
$dexDir = Join-Path $tmp "dex"; New-Item -ItemType Directory -Force -Path $dexDir | Out-Null
& java -cp (Join-Path $root "tools/d8.jar") com.android.tools.r8.D8 --min-api 23 --lib $android --release --output $dexDir $jar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

$dev = "/data/local/tmp/wlbreak"
RunAdb @("shell", "mkdir", "-p", $dev) | Out-Null
RunAdb @("push", (Join-Path $dexDir "classes.dex"), "$dev/probe.dex") | Out-Null
if ($Probe -eq "TokenBreakProbe" -and $Paras) {
    $paraFile = Join-Path $tmp "paras.txt"   # not $paras: PowerShell names are case-insensitive
    py (Join-Path $root "tools/para-text.py") -Paras $Paras -Out $paraFile | Out-Null
    RunAdb @("push", $paraFile, "$dev/paras.txt") | Out-Null
}
$out = RunAdb @("shell", "CLASSPATH=$dev/probe.dex app_process -Xmx256m / com.rikkahub.wordlite.probe.$Probe")
$sdk = (RunAdb @("shell", "getprop", "ro.build.version.sdk")).Trim()
Write-Host "== device sdk=$sdk  (Word truth: artifacts/word-break/word-lines.tsv)"
$out
$dst = Join-Path $root "artifacts/agent-layout-verify/$Probe.txt"
New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
Set-Content -Encoding utf8NoBOM -Path $dst -Value (@("sdk=$sdk") + $out)
Write-Host "== wrote $dst"


