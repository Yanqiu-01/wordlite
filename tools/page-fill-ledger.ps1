# tools/page-fill-ledger.ps1 -- 每页高度对账：手机 Word Lite vs Word 自己导出的 PDF，逐页同口径。
#
# 每页只算三条数（一律换算到我方文档 px：1pt = 4/3 px）：
#   A 首行基线到页顶   B 首行基线到末行基线   C 末行基线到页底
# 三条加起来必然等于页高（两边页高都折成 1122.56px），所以 dA + dB + dC = 0 是一台闭合的账：
# 顶部或行盒里多花的钱，一定从页尾剩余里扣走。基线两边都是精确读数（手机侧
# StaticLayout.getLineBaseline，Word 侧 PDF 文本矩阵的 origin），不估行高，也不受
# "墨迹盒 != 行盒"影响。页眉/页脚按基线落在版心之外单独归类报出，它们不占版心。
#
#   pwsh tools/page-fill-ledger.ps1 -Tag lh-geo1
#   pwsh tools/page-fill-ledger.ps1 -GeoLines <lines-geo.tsv> -PageGeo <page-geo.tsv> -WordLines <word-pdf-lines.tsv>
# 前置：手机侧 pwsh tools/capture-device.ps1 -Serial <sn> -Impls new -OutDir artifacts/agent-layout-verify/<tag>
#       Word  侧 pwsh artifacts/agent-typeset/word-export-pdf.ps1 再 py tools/pdf-line-truth.py
# 用法约定：本脚本只读，不改任何输入；输出的 dA/dB/dC 三列相加为 0，是它自己的自检。
param(
    [string]$Tag = "lh-geo1",
    [string]$GeoLines = "",
    [string]$PageGeo = "",
    [string]$WordLines = "artifacts/agent-layout-verify/word-pdf-lines.tsv",
    [string]$Out = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not $GeoLines) { $GeoLines = "artifacts/agent-layout-verify/$Tag/new/lines-geo.tsv" }
if (-not $PageGeo)  { $PageGeo  = "artifacts/agent-layout-verify/$Tag/new/page-geo.tsv" }
foreach ($f in @($GeoLines, $PageGeo, $WordLines)) { if (-not (Test-Path $f)) { throw "missing input: $f" } }

$PT2PX = 4.0 / 3.0
$pageRows  = @(Import-Csv $PageGeo  -Delimiter "`t")
$geoRows   = @(Import-Csv $GeoLines -Delimiter "`t")
$wordRows  = @(Import-Csv $WordLines -Delimiter "`t" | Where-Object { $_.page -match '^\d+$' })
if ($geoRows.Count -lt 100) { throw "suspiciously few phone lines: $($geoRows.Count)" }

$boxByPage = @{}
foreach ($r in $pageRows) {
    $boxByPage[[int]$r.page] = [pscustomobject]@{
        pagePt = [double]$r.pageH * 0.75; topPt = [double]$r.marginTop * 0.75
        bottomPt = [double]$r.marginBottom * 0.75; pagePx = [double]$r.pageH }
}
$wordByPage = @{}
foreach ($g in ($wordRows | Group-Object { [int]$_.page })) { $wordByPage[[int]$g.Name] = @($g.Group) }
$ourByPage = @{}
foreach ($g in ($geoRows | Group-Object { [int]$_.page })) { $ourByPage[[int]$g.Name] = @($g.Group) }

$rows = @()
foreach ($p in ($boxByPage.Keys | Sort-Object)) {
    $b = $boxByPage[$p]
    $wAll = $wordByPage[$p]
    if (-not $wAll) { throw "page $p missing in the Word PDF dump" }
    $wb = @($wAll | Where-Object { [double]$_.baseline -ge $b.topPt -and [double]$_.baseline -le $b.pagePt - $b.bottomPt } | ForEach-Object { [double]$_.baseline * $PT2PX } | Sort-Object)
    $wh = @($wAll | Where-Object { [double]$_.baseline -lt $b.topPt } | ForEach-Object { [double]$_.baseline * $PT2PX } | Sort-Object)
    $wf = @($wAll | Where-Object { [double]$_.baseline -gt $b.pagePt - $b.bottomPt } | ForEach-Object { [double]$_.baseline * $PT2PX } | Sort-Object)
    $oAll = $ourByPage[$p]
    if (-not $oAll) { throw "page $p missing in the phone dump" }
    $ob = @($oAll | ForEach-Object { [double]$_.baseline } | Sort-Object)
    if (-not $wb.Count -or -not $ob.Count) { throw "page $p has no body lines on one side" }
    $A_w = $wb[0]; $B_w = $wb[-1] - $wb[0]; $C_w = $b.pagePx - $wb[-1]
    $A_o = $ob[0]; $B_o = $ob[-1] - $ob[0]; $C_o = $b.pagePx - $ob[-1]
    $pitchW = if ($wb.Count -gt 1) { $B_w / ($wb.Count - 1) } else { 0 }
    $pitchO = if ($ob.Count -gt 1) { $B_o / ($ob.Count - 1) } else { 0 }
    $rows += [pscustomobject]@{
        page = $p; nW = $wb.Count; nO = $ob.Count; dN = $ob.Count - $wb.Count
        AW = [math]::Round($A_w,1); AO = [math]::Round($A_o,1); dA = [math]::Round($A_o - $A_w,1)
        BW = [math]::Round($B_w,1); BO = [math]::Round($B_o,1); dB = [math]::Round($B_o - $B_w,1)
        CW = [math]::Round($C_w,1); CO = [math]::Round($C_o,1); dC = [math]::Round($C_o - $C_w,1)
        pitchW = [math]::Round($pitchW,2); pitchO = [math]::Round($pitchO,2)
        dPitch = [math]::Round($pitchO - $pitchW,2)
        headW = [math]::Round($(if ($wh.Count) { $wh[-1] } else { 0 }),1)
        footW = [math]::Round($(if ($wf.Count) { $wf[0] } else { 0 }),1)
        closeW = [math]::Round($A_w + $B_w + $C_w - $b.pagePx, 2)
        closeO = [math]::Round($A_o + $B_o + $C_o - $b.pagePx, 2) }
}
$rows | Format-Table -AutoSize | Out-String -Width 260
$identity = $rows | ForEach-Object { [math]::Abs([double]$_.dA + [double]$_.dB + [double]$_.dC) } | Measure-Object -Maximum
$close = @($rows | ForEach-Object { [math]::Abs([double]$_.closeW) }) + @($rows | ForEach-Object { [math]::Abs([double]$_.closeO) }) | Measure-Object -Maximum
"自检 dA+dB+dC 最大 = {0:N2} px；A+B+C 与页高之差最大 = {1:N2} px（页高 {2} px）" -f $identity.Maximum, $close.Maximum, $pageRows[0].pageH
foreach ($c in @("nW","nO","dN","AW","AO","dA","BW","BO","dB","CW","CO","dC")) {
    $v = @($rows | ForEach-Object { [double] $_.$c }) | Sort-Object
    "{0,-5} sum={1,9:N1} mean={2,7:N1} min={3,7:N1} p50={4,7:N1} max={5,7:N1}" -f $c, $v.Sum, (($v | Measure-Object -Average).Average), $v[0], $v[[int][math]::Floor($v.Count / 2)], $v[-1]
}
foreach ($c in @("CW","CO")) {
    $v = @($rows | ForEach-Object { [double] $_.$c }) | Sort-Object
    "页尾剩余 $c ：min={0:N1} p25={1:N1} 中位={2:N1} max={3:N1} 页数<=20px={4} <=40px={5}" -f $v[0], $v[[int][math]::Floor($v.Count * 0.25)], $v[[int][math]::Floor($v.Count / 2)], $v[-1], @($v | Where-Object { $_ -le 20 }).Count, @($v | Where-Object { $_ -le 40 }).Count
}
$dn = @($rows | ForEach-Object { [int]$_.dN })
"我们比 Word 多排的行：全篇合计 {0} 行（Word {1} 行 / 我们 {2} 行），多排的页 {3} 页，少排的页 {4} 页" -f `
    (($dn | Measure-Object -Sum).Sum), (($rows | ForEach-Object { [int]$_.nW }) | Measure-Object -Sum).Sum, `
    (($rows | ForEach-Object { [int]$_.nO }) | Measure-Object -Sum).Sum, @($dn | Where-Object { $_ -gt 0 }).Count, @($dn | Where-Object { $_ -lt 0 }).Count
if ($Out) { $rows | Export-Csv -NoTypeInformation -Delimiter "`t" -Encoding utf8NoBOM -Path $Out }
