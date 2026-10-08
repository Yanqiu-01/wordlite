# Word Lite - run the real retrieval stack on a connected device and print per-engine results.
# No UI, no APK: javac the pure-Java subset -> d8 -> push one dex -> app_process.
#
# Usage:  pwsh tools/device-probe.ps1 [[-Query <text>] [-Engines a,b] [-Proxy host:port]
#         [-Reverse] [-Serial <sn>] [-Per N] [-Timeout s] [-IncludeCnki] [-Keep]
#         [-Mode engines|fulltext] [-Fetch N] [-Budget N]     (fulltext: see EngineProbe's BODY/EMPTY lines)
#
# Which classes the probe needs (deliverable 4, resolved automatically by javac via -sourcepath and
# printed as "closure:" on every run):
#   EngineProbe -> PaperSources (+ nested ApiJson/Xml/ProtoWire helpers) -> ApiClient, HttpTransport,
#   Routes, WanfangProtocol, TextCorpus. ApiClient also drags in ApiConfig/ApiTemplate/ApiResult.
#   Every one of those is plain Java: none imports android.* or androidx.*, and none needs a Context,
#   so app_process can load the dex off the boot classpath with no framework stubs at all. Anything
#   that does import android (DocxParser, MainActivity, ApiWorkflow, ...) is simply out of the closure.
#   java.nio.file.Files (the --query-file reader) needs API 26+; the device is Android 10 (API 29).
#
# CnkiSearch/CnkiTouch are deliberately NEVER compiled here (they are being written concurrently).
# PaperSources.search references CnkiSearch, so without a stand-in the subset would not link at all.
# This script therefore stages a placeholder CnkiSearch *outside* app/ that throws the sentinel
# WORDLITE_PROBE_EXCLUDED; the probe turns that into a "SKIP cnki not in this probe build" line, so
# cnki never shows up as a fake pass or a real failure. Pass -IncludeCnki to compile the real file
# once it exists.
#
# Proxy: production code honours PaperSources.Limits.proxy (host:port), which HttpTransport feeds to
# Routes.order() as the explicit route. So -Proxy is passed through as --proxy=, exactly like the app
# would. Note Routes also auto-discovers 127.0.0.1:7897/7890 and the JVM http.proxyHost for overseas
# hosts, so an overseas engine can still end up on the proxy with -Proxy unset; the via= column shows
# which route actually carried the traffic.
#
# Host-side reminder: this shell injects HTTP_PROXY/HTTPS_PROXY, so a comparison curl needs
# --noproxy '*' to be a real direct test.
param(
    [string]$Query = "深度学习 图像分割 综述",
    [string]$Engines = "",
    [string]$Proxy = "",
    [switch]$Reverse,
    [int]$ReversePort = 7897,
    [string]$Serial = "EAMUT20528011355",
    [int]$Repeat = 1,
    [int]$Per = 5,
    [int]$Timeout = 25,
    [switch]$IncludeCnki,
    [ValidateSet("engines","fulltext")][string]$Mode = "engines",
    [int]$Fetch = 3,
    [int]$Budget = 6,
    [switch]$Keep,
    [switch]$BuildOnly,
    [switch]$NoTcp,
    [string]$JavaHome = ""
)
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$stage    = Join-Path $root "tools/device-probe/build/stage"
$classes  = Join-Path $root "tools/device-probe/build/classes"
$dexDir   = Join-Path $root "tools/device-probe/build/dex"
$android  = Join-Path $root "tools/android-35.jar"
$d8Jar    = Join-Path $root "tools/d8.jar"
$appJava  = Join-Path $root "app/src/main/java"
$probeSrc = Join-Path $root "tests/android/EngineProbe.java"
$cnkiReal = Join-Path $appJava "com/rikkahub/wordlite/CnkiSearch.java"
$remoteDex   = "/data/local/tmp/wordlite-engine-probe.dex"
$remoteQuery = "/data/local/tmp/wordlite-probe-query.txt"
$remoteClass = "com.rikkahub.wordlite.EngineProbe"

function Invoke-Checked {
    param([string]$exe, [string[]]$argv)
    Write-Host ("> " + (Split-Path -Leaf $exe) + " " + ($argv -join " ")) -ForegroundColor DarkGray
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw ("{0} failed with exit code {1}" -f (Split-Path -Leaf $exe), $LASTEXITCODE) }
}

# --- toolchain (same resolution order as tools/build-host.ps1) ---
if (-not $JavaHome) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin/javac.exe"))) { $JavaHome = $env:JAVA_HOME }
    elseif (Test-Path "C:/Program Files/Microsoft/jdk-17.0.7.7-hotspot/bin/javac.exe") { $JavaHome = "C:/Program Files/Microsoft/jdk-17.0.7.7-hotspot" }
    else { throw "no JDK found; set JAVA_HOME or pass -JavaHome" }
}
$javaBin = Join-Path $JavaHome "bin"
$javac = Join-Path $javaBin "javac.exe"
$java  = Join-Path $javaBin "java.exe"
foreach ($t in @($javac, $java)) { if (-not (Test-Path $t)) { throw ("missing tool: " + $t) } }
foreach ($f in @($android, $d8Jar, $probeSrc)) { if (-not (Test-Path $f)) { throw ("missing input: " + $f) } }
$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) { throw "adb not on PATH; install platform-tools or add it to PATH" }
$adb = $adb.Source

# --- device check with an explicit complaint ---
$lines = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -and $_.Trim() })
$ready = @($lines | Where-Object { $_ -match "`tdevice`$" })
if ($Serial) {
    $mine = @($ready | Where-Object { ($_ -split "`t")[0] -eq $Serial })
    if ($mine.Count -eq 0) {
        $offline = @($lines | Where-Object { ($_ -split "`t")[0] -eq $Serial -and $_ -notmatch "`tdevice`$" })
        if ($offline.Count -gt 0) { throw ("device " + $Serial + " is present but not online (" + $offline[0].Trim() + "); unlock it or run: adb kill-server; adb start-server") }
        throw ("device " + $Serial + " is not attached; seen: " + $(if ($ready.Count) { ($ready | ForEach-Object { ($_ -split "`t")[0] }) -join ", " } else { "none" }) + ". Pass -Serial <sn> or plug it in and accept the USB debugging prompt.")
    }
} elseif ($ready.Count -eq 0) {
    throw "no device in 'device' state; run: adb devices"
} elseif ($ready.Count -gt 1) {
    $first = ($ready[0] -split "`t")[0]
    throw ("several devices attached; pass -Serial, e.g. -Serial " + $first)
}
$device = if ($Serial) { $Serial } else { ($ready[0] -split "`t")[0] }
Write-Host ("== device " + $device + " ==")
$release = ((& $adb -s $device shell getprop ro.build.version.release) -join "").Trim()
$sdk = ((& $adb -s $device shell getprop ro.build.version.sdk) -join "").Trim()
Write-Host ("   Android " + $release + " (sdk " + $sdk + ")")

# --- stage: the probe plus a CnkiSearch stand-in, never the real CNKI sources ---
foreach ($d in @($stage, $classes, $dexDir)) { if (Test-Path -LiteralPath $d) { Remove-Item -Recurse -Force $d } ; New-Item -ItemType Directory -Force -Path $d | Out-Null }
Copy-Item $probeSrc $stage -Force
$cnkiPlaceholder = -not ($IncludeCnki -and (Test-Path $cnkiReal))
if ($cnkiPlaceholder) {
    Set-Content -Encoding ascii -Path (Join-Path $stage "CnkiSearch.java") -Value @(
        "package com.rikkahub.wordlite;",
        "import java.io.IOException;",
        "import java.util.ArrayList;",
        "import java.util.LinkedHashMap;",
        "/** Staged placeholder for tools/device-probe.ps1 only; app/ is untouched. */",
        "final class CnkiSearch {",
        "    private CnkiSearch() { }",
        "    static String form(String phrase, int page) throws IOException { throw new IOException(""WORDLITE_PROBE_EXCLUDED""); }",
        "    static LinkedHashMap<String, String> headers() { return new LinkedHashMap<String, String>(); }",
        "    static ArrayList<PaperSources.Candidate> parse(String body, int limit) throws IOException { throw new IOException(""WORDLITE_PROBE_EXCLUDED""); }",
        "}")
    Write-Host "   cnki: placeholder staged (CnkiSearch is not compiled by this script)" -ForegroundColor DarkGray
} else {
    Copy-Item $cnkiReal $stage -Force
    Write-Host "   cnki: real CnkiSearch compiled (-IncludeCnki)" -ForegroundColor DarkGray
}

Write-Host "== javac (closure resolved from -sourcepath) =="
# Stage dir comes first so the placeholder wins over app/ for CnkiSearch.
$sources = @(Get-ChildItem $stage -Filter *.java | ForEach-Object { $_.FullName })
$javacArgs = @("-nowarn", "-encoding", "UTF-8", "-classpath", $android,
    "-sourcepath", ($stage + [IO.Path]::PathSeparator + $appJava), "-d", $classes) + @($sources)
Invoke-Checked $javac $javacArgs
$made = @(Get-ChildItem $classes -Recurse -Filter *.class)
Write-Host ("   closure: " + (($made | ForEach-Object { $_.Name -replace "\.class$", "" } | Sort-Object) -join " "))

Write-Host "== d8 =="
$classFiles = @($made | ForEach-Object { $_.FullName -replace "\\", "/" })
$d8Args = Join-Path $dexDir "d8-args.txt"
Set-Content -Encoding ascii -Path $d8Args -Value $classFiles
Invoke-Checked $java @("-cp", $d8Jar, "com.android.tools.r8.D8", "--min-api", "23", "--lib", $android,
    "--output", $dexDir, ("@" + ($d8Args -replace "\\", "/")))
$localDex = Join-Path $dexDir "classes.dex"
if (-not (Test-Path $localDex)) { throw "d8 produced no classes.dex" }
Write-Host ("   dex: {0} ({1:N0} bytes)" -f $localDex, (Get-Item $localDex).Length)
if ($BuildOnly) { Write-Host "BUILD ONLY: nothing pushed"; return }

# --- query goes over as a UTF-8 file: CJK through adb shell argv is a mojibake trap ---
$localQuery = Join-Path $dexDir "query.txt"
[System.IO.File]::WriteAllText($localQuery, $Query, (New-Object System.Text.UTF8Encoding($false)))

$reverseApplied = $false
$console = [Console]::OutputEncoding
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    Write-Host "== push =="
    Invoke-Checked $adb @("-s", $device, "push", $localDex, $remoteDex)
    Invoke-Checked $adb @("-s", $device, "push", $localQuery, $remoteQuery)

    if ($Reverse) {
        Write-Host ("== adb reverse tcp:{0} tcp:{0} ==" -f $ReversePort)
        Invoke-Checked $adb @("-s", $device, "reverse", ("tcp:{0}" -f $ReversePort), ("tcp:{0}" -f $ReversePort))
        $reverseApplied = $true
        if (-not $Proxy) { $Proxy = ("127.0.0.1:{0}" -f $ReversePort); Write-Host ("   -Proxy defaulted to " + $Proxy + " because -Reverse was asked for") }
    }

    # -Mode fulltext 让探针走 app 的全文那一路：每源试取 -Fetch 条，再按 app 的排队取 -Budget 条。
    $argv = @("-s", $device, "shell", ("CLASSPATH={0} app_process / {1} {2} --query-file={3} --per={4} --timeout={5} --repeat={6} --fetch={7} --budget={8}" -f
            $remoteDex, $remoteClass, $Mode, $remoteQuery, $Per, $Timeout, $Repeat, $Fetch, $Budget))
    if ($Engines) { $argv[-1] += (" --only=" + $Engines) }
    if ($Proxy)   { $argv[-1] += (' --proxy=' + $Proxy) }
    if ($NoTcp)   { $argv[-1] += ' --no-tcp' }
    Write-Host ("> adb -s <sn> shell " + $argv[-1]) -ForegroundColor DarkGray
    Write-Host ("   remote: CLASSPATH=" + $remoteDex + " app_process / " + $remoteClass + " ...")
    & $adb @argv
    $code = $LASTEXITCODE
    Write-Host ("probe exit code: " + $code)
    if ($code -ne 0) { Write-Host ('probe did not reach any engine (device exit ' + $code + ')') -ForegroundColor Red }
} finally {
    [Console]::OutputEncoding = $console
    if ($reverseApplied -and -not $Keep) {
        Write-Host "== adb reverse --remove-all =="
        & $adb -s $device reverse --remove-all 2>&1 | Out-Null
    }
    if (-not $Keep) {
        Write-Host "== cleanup =="
        & $adb -s $device shell rm -f $remoteDex $remoteQuery 2>&1 | Out-Null
    } else {
        Write-Host ("kept " + $remoteDex + " on the device (-Keep)")
    }
}


