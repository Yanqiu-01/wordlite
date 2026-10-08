# AIGC 标定拟合台（2026-10-08 轮）。用法：pwsh tools/aigc-fit.ps1 <mode>
#   screen    逐特征方向表（拟合侧 / 留出侧各量一次）
#   raw       归一化之前的原始计数与文档级比例（定"原始量 -> 0..1"刻度用的证据，别凭感觉调门槛）
#   matched   两侧都只留 >=5 个计分句的段落，把"段落更长所以文档级特征更容易亮灯"这个构造差拆掉
#   fit       坐标上升拟合 + 留出档验收（目标、约束、方向筛见 tests/AigcCalibrationFit.java 的类注释）
#   ceiling   天花板实验：AigcScorer.check 那两条静态纪律之内最多能让多少机器句过线（这一台决定够不够格换号）
#   shipped   产品表 v1-order-only 在八档语料上的全部可引用数字
#   auditpool 逐字照 tests/AigcFeatureAuditRegression 的七档池量一遍；AigcScorer.UNCALIBRATED_NOTE 的数出自这里
#
# 只编 AIGC 这条链（AigcFeatures/AigcScorer/AigcDetector/AigcFamily + TextCorpus + 拟合台），
# 刻意不编整棵 app：查重那条链上有别的组在改，他们半成品时也不该挡住标定工作。
# class 落在 artifacts/build/aigc-fit/（与 tools/aigc-calibration.ps1 的 aigc-check 同级，互不清目录），
# 结果表落在 artifacts/agent-aigc/fit-<mode>.txt，不覆盖主线的 artifacts/corpus/aigc-sweep.txt。
param(
    [Parameter(Position = 0)][string]$Mode = "screen",
    [string]$Out = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$classes = Join-Path $root "artifacts/build/aigc-fit"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$src = @(
    "app/src/main/java/com/rikkahub/wordlite/AigcFeatureId.java",
    "app/src/main/java/com/rikkahub/wordlite/AigcFeatures.java",
    "app/src/main/java/com/rikkahub/wordlite/AigcScorer.java",
    "app/src/main/java/com/rikkahub/wordlite/AigcDetector.java",
    "app/src/main/java/com/rikkahub/wordlite/AigcFamily.java",
    "tests/AigcCalibrationFit.java")
& javac -nowarn -encoding UTF-8 -sourcepath "app/src/main/java" -classpath "tools/android-35.jar" -d $classes $src
if ($LASTEXITCODE -ne 0) { throw "compile failed" }

$outFile = if ($Out) { $Out } else { Join-Path $root ("artifacts/agent-aigc/fit-" + $Mode + ".txt") }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outFile) | Out-Null
& java "-Dfile.encoding=UTF-8" -cp "$classes;tools/android-35.jar" com.rikkahub.wordlite.AigcCalibrationFit $Mode *> $outFile
Write-Host ("wrote {0} ({1} lines)" -f $outFile, (Get-Content $outFile).Count)