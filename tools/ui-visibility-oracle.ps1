# 量"uiautomator 的一份 dump 到底看得见谁"。它不测 ribbon 好坏，它测的是量 ribbon 的那把尺：
# 滚动容器折线以下的节点 dump 根本不报，被折线切到的那个报的是切完的边界——
# 所以"dump 里没有"既可能是"在屏幕外"也可能是"没建"，这一台把两者分开量出来。
#
# 两步：
#   1) 已知内容的竖向 ScrollView（探针 ScrollCensusActivity，40 行）：自己写 View 层真值表，
#      主机静止 dump 一次、滚到底再 dump 一次，逐行对质。
#   2) 真的 ribbon（探针 RibbonErgonomicsActivity，停在屏幕上）：dump 一次，
#      和它自己那张表对质——ribbon 表里"这一排在视口外"那一栏靠的就是这条判据。
#
# 前提：探针 APK 已装好（pwsh tools/build-uiaudit-probe.ps1 -KeepInstalled）。
# 用法：pwsh tools/ui-visibility-oracle.ps1 [-Serial <序列号>] [-OutDir <目录>]
param(
    [string]$Serial = "EAMUT20528011355",
    [string]$OutDir = "artifacts/analysis/ui-ergonomics"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$py = if (Test-Path "C:/Users/ad/AppData/Local/Programs/Python/Python312/python.exe") {
    "C:/Users/ad/AppData/Local/Programs/Python/Python312/python.exe" } else { "python" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$adb = @("-s", $Serial)
$pkg = "com.rikkahub.wordlite.uiaudit"
$files = "/sdcard/Android/data/$pkg/files"

function Wait-Log([string]$needle, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if (((& adb @adb logcat -d -s WLuaudit) | Out-String) -match $needle) { return $true }
    }
    return $false
}

function Save-Dump([string]$name) {
    & adb @adb shell uiautomator dump /sdcard/wl-oracle.xml | Out-Null
    & adb @adb pull /sdcard/wl-oracle.xml (Join-Path $OutDir $name) | Out-Null
    & adb @adb shell rm -f /sdcard/wl-oracle.xml
    if (-not (Test-Path (Join-Path $OutDir $name))) { throw "没拉到 $name" }
    Write-Host ("dump {0}：{1:N0} 字节" -f $name, (Get-Item (Join-Path $OutDir $name)).Length)
}

& adb @adb logcat -c

# --- 1) 竖向 ScrollView：静止那一档 --------------------------------------------
Write-Host "== 竖向滚动容器：静止 ==" -ForegroundColor Cyan
& adb @adb shell am start -S -n "$pkg/com.rikkahub.wordlite.ScrollCensusActivity" --es hold 1 | Out-Null
if (-not (Wait-Log "CENSUS-DONE" 25)) { throw "探针没报到 CENSUS-DONE" }
Save-Dump "oracle-scroll-rest.xml"
& adb @adb pull "$files/scroll-census.tsv" (Join-Path $OutDir "oracle-scroll-rest.tsv") | Out-Null

# --- 1b) 同一台，自己滚到底 ----------------------------------------------------
Write-Host "== 竖向滚动容器：滚到底 ==" -ForegroundColor Cyan
& adb @adb shell am start -S -n "$pkg/com.rikkahub.wordlite.ScrollCensusActivity" --es hold 1 --es scroll 1 | Out-Null
if (-not (Wait-Log "CENSUS-DONE" 25)) { throw "探针没报到 CENSUS-DONE" }
Save-Dump "oracle-scroll-end.xml"
& adb @adb pull "$files/scroll-census.tsv" (Join-Path $OutDir "oracle-scroll-end.tsv") | Out-Null

# --- 2) 真 ribbon 停在屏幕上，dump 与它自己的表对质 ----------------------------
Write-Host "== ribbon：停在屏幕上 dump ==" -ForegroundColor Cyan
& adb @adb shell am start -S -n "$pkg/com.rikkahub.wordlite.RibbonErgonomicsActivity" --es hold 1 --es tab 开始 | Out-Null
if (-not (Wait-Log "HOLD" 30)) { throw "探针没停在 ribbon 上" }
Start-Sleep -Seconds 2
Save-Dump "oracle-ribbon.xml"
& adb @adb pull "$files/ribbon-ergonomics.tsv" (Join-Path $OutDir "oracle-ribbon-tree.tsv") | Out-Null
& adb @adb shell am force-stop $pkg | Out-Null

# --- 对质 -----------------------------------------------------------------------
Write-Host "`n== 竖向：静止 ==" -ForegroundColor Cyan
& $py tools/ui-dump-census.py --kind scroll --tree (Join-Path $OutDir "oracle-scroll-rest.tsv") --dump (Join-Path $OutDir "oracle-scroll-rest.xml") --tree2 (Join-Path $OutDir "oracle-scroll-end.tsv") --dump2 (Join-Path $OutDir "oracle-scroll-end.xml") |
    Tee-Object -FilePath (Join-Path $OutDir "oracle-scroll-census.tsv")
Write-Host "`n== ribbon（开始页，横向）==" -ForegroundColor Cyan
& $py tools/ui-dump-census.py --kind ribbon --tree (Join-Path $OutDir "oracle-ribbon-tree.tsv") --dump (Join-Path $OutDir "oracle-ribbon.xml") --tab 开始 |
    Tee-Object -FilePath (Join-Path $OutDir "oracle-ribbon-census.tsv")
Write-Host "`n表与 dump 都在 $OutDir"