# Runs a named list of host suites for ONE source tree, into PRIVATE class dirs.
# Answers "is this red because of MY change?": run the same suite list on a clean HEAD tree and on
# HEAD plus your own files, and compare. Nobody stomps artifacts/build/host-classes this way.
# Runs DetectRegression + RetrievalCoverageRegression for ONE source tree, into PRIVATE class dirs.
# Never touches artifacts/build/host-classes (shared with tools/test-host.ps1).
param(
    [Parameter(Mandatory=$true)][string]$Tree,      # source tree root to compile
    [string]$Apk = "",                      # built APK, for the suites that read the package
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

# 与 tools/test-host.ps1 同一张参数表。私有复跑最容易骗人的地方就是这个：套件要参数而没人给，
# 它抛 IndexOutOfBoundsException，于是每个人每次都在同一批"红"上面学会忽略。参数在这里补齐，
# 红就只剩"真的红了"这一种解释。{out} 换成本次私有的输出目录。
$suiteOut = Join-Path $OutDir "out"
# Regression 直接往 roundtrip 目录里写，不自己建；test-host.ps1 也是预先建好的。
foreach ($d in @($suiteOut, (Join-Path $suiteOut "roundtrip"))) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
$argMap = @{
    "Regression"                  = @("tests/fixture.docx", "{out}/roundtrip")
    "RewriteRobustnessRegression" = @("tests/corpus/real-prose.txt")
    "AigcOfflineModelRegression"  = @("app/src/main/assets/aigc", "tests/corpus")
    "DeepModeAudit"               = @("tests/corpus/real-prose.txt", "tests/corpus/oa-planted-cjmenet.txt")
    "RewriteRateRegression"       = @("tests/samples/input-liu.docx", "tests/corpus/oa-planted-cjmenet.txt", "{out}/rewrite-rate")
    "RewriteCoverageAudit"        = @("tests/samples/input-liu.docx", "tests/corpus/oa-planted-cjmenet.txt", "{out}/rewrite-coverage")
    "PreservationRegression"      = @("tests/samples/complex-preservation.docx")
    "ReviewRegression"            = @("tests/fixture.docx", "{out}/threaded-revisions.docx")
    "PdfRegression"               = @("{out}/metadata.pdf")
    "ZeroRateAudit"               = @("tests/samples/input-liu.docx", "tests/corpus/real-prose.txt", "tests/corpus/aigc-cartoon.txt")
    # 三档检索被挡的用例：比的是结果页收尾那一行的字，要真样例文档切得出检索窗口。
    "RetrievalBlockageAudit"      = @("tests/samples/input-liu.docx")
    "DetectionFloor"              = @("tests/corpus", "{out}/detection-floor-library")
    "AbstractLayerRegression"     = @("tests/corpus")
    "FullTextYieldRegression"      = @()
    "FontSubstitution"            = @("tests/samples/input-liu.docx")
    "OriginalDocxRegression"      = @("tests/samples/input-liu.docx", "{out}/original-roundtrip.docx")
    "TableGeometryRegression"     = @("tests/samples/input-liu.docx", "{out}/table-geometry.docx", "tests/fixture.docx")
}
# 这两个看的是构建产物，没有 APK 可查时直说，不拿一个假红去冒充失败。
$apkSuites = @("FontAssetsRegression")
$bad = 0
foreach ($name in $Suite.Split(",")) {
    $log = Join-Path $OutDir ("logs/" + $name.ToLower() + ".log")
    $extra = @()
    if ($argMap.ContainsKey($name)) {
        $extra = @($argMap[$name] | ForEach-Object { $_.Replace("{out}", $suiteOut) })
    }
    if ($apkSuites -contains $name -and -not $Apk) {
        Write-Host ("SKIP {0}  要看构建产物，加 -Apk <apk> 再跑" -f $name) -ForegroundColor DarkYellow
        continue
    }
    if ($apkSuites -contains $name) { $extra = @($Tree, $Apk) }
    & java --add-modules jdk.httpserver "-Dfile.encoding=UTF-8" -classpath $cp ("com.rikkahub.wordlite." + $name) @extra *> $log
    $code = $LASTEXITCODE
    $tail = if (Test-Path $log) { (Get-Content $log -Encoding UTF8 | Where-Object { $_ -ne "" } | Select-Object -Last 2) } else { "" }
    if ($code -eq 0) { Write-Host ("PASS {0}  {1}" -f $name, ($tail -join " | ")) }
    else { $bad++; Write-Host ("FAIL {0} exit {1}" -f $name, $code) -ForegroundColor Red; Write-Host ($tail -join "`n") }
}
Write-Host ("== tree={0} failed={1} ==" -f $Tree, $bad)

