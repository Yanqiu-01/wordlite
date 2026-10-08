# Runs a named list of host suites for ONE source tree, into PRIVATE class dirs.
# Answers "is this red because of MY change?": run the same suite list on a clean HEAD tree and on
# HEAD plus your own files, and compare. Nobody stomps artifacts/build/host-classes this way.
# Runs DetectRegression + RetrievalCoverageRegression for ONE source tree, into PRIVATE class dirs.
# Never touches artifacts/build/host-classes (shared with tools/test-host.ps1).
param(
    [Parameter(Mandatory=$true)][string]$Tree,      # source tree root to compile
    [Parameter(Mandatory=$true)][string]$OutDir,    # private output dir for classes/logs
    [string]$Suite = "DetectRegression,RetrievalCoverageRegression"
)
$ErrorActionPreference = "Stop"
$root  = Split-Path -Parent $PSScriptRoot
$jar   = Join-Path $root "tools/android-35.jar"
$gen   = Join-Path $OutDir "gen"
$cls   = Join-Path $OutDir "classes"
$tcls  = Join-Path $OutDir "test-classes"
foreach ($d in @($gen,$cls,$tcls,(Join-Path $OutDir "logs"))) { New-Item -ItemType Directory -Force -Path $d | Out-Null }

$rdir = Join-Path $gen "com/rikkahub/wordlite"
New-Item -ItemType Directory -Force -Path $rdir | Out-Null
$stub = @(
  "package com.rikkahub.wordlite;",
  "public final class R {",
  "    public static final class drawable { public static final int ic_file = 1; public static final int ic_hero = 2; }",
  "    public static final class layout { public static final int simple_spinner_dropdown_item = 3; }",
  "}")
Set-Content -Encoding utf8NoBOM -Path (Join-Path $rdir "R.java") -Value $stub

$appFiles = @(Get-ChildItem -Recurse -File -Filter *.java (Join-Path $Tree "app/src/main/java") | ForEach-Object { $_.FullName })
$appFiles += (Join-Path $rdir "R.java")
$listing = Join-Path $OutDir "app-listing.txt"
Set-Content -Encoding ascii -Path $listing -Value $appFiles
Write-Host ("== javac app ({0} files) from {1} ==" -f $appFiles.Count, $Tree)
& javac -nowarn -encoding UTF-8 -classpath $jar -d $cls "@$listing"
if ($LASTEXITCODE -ne 0) { throw "app compile failed" }

$testFiles = @(Get-ChildItem -File -Filter *.java (Join-Path $Tree "tests") | ForEach-Object { $_.FullName })
Write-Host ("== javac tests ({0} files) ==" -f $testFiles.Count)
& javac -nowarn --add-modules jdk.httpserver -encoding UTF-8 -classpath "$cls;$jar" -d $tcls $testFiles
if ($LASTEXITCODE -ne 0) { throw "test compile failed" }

$cp = "$tcls;$cls;$jar"
$bad = 0
foreach ($name in $Suite.Split(",")) {
    $log = Join-Path $OutDir ("logs/" + $name.ToLower() + ".log")
    & java --add-modules jdk.httpserver "-Dfile.encoding=UTF-8" -classpath $cp ("com.rikkahub.wordlite." + $name) *> $log
    $code = $LASTEXITCODE
    $tail = if (Test-Path $log) { (Get-Content $log -Encoding UTF8 | Where-Object { $_ -ne "" } | Select-Object -Last 2) } else { "" }
    if ($code -eq 0) { Write-Host ("PASS {0}  {1}" -f $name, ($tail -join " | ")) }
    else { $bad++; Write-Host ("FAIL {0} exit {1}" -f $name, $code) -ForegroundColor Red; Write-Host ($tail -join "`n") }
}
Write-Host ("== tree={0} failed={1} ==" -f $Tree, $bad)

