# 七条验收（docs/layout-parity-target.md 第 0 节）一次跑完，输出一张表。前六条是原来的六条，第 7 条见下面口径说明。
#
#   pwsh tools/parity-six.ps1 -Tag lh-base                      # 采样 + 六条全量
#   pwsh tools/parity-six.ps1 -Tag lh-base -SkipDevice          # 手机不重跑，只重算报表
#
# 本脚本不重新实现任何量法，只按顺序调已有脚本并把六个数拼到一起：
#   采样      tools/capture-device.ps1（把工作树编成 dex 在手机上跑 app_process）
#   逐行行高  artifacts/agent-typeset/run-repaginate.ps1 + line-height-rows.ps1
#   页归属    tools/word-parity.ps1
#   换行点    tools/line-break-delta.ps1 -Stage report
#   右边界    tools/edge-parity.ps1
# Word 真值一律走缓存（-WordCache，默认 artifacts/parity），这里不重开 Word 会话：Word 只允许一个会话。
# 每条后面都带着量它的文件名，报表同时写 <Out>/<Tag>/six.tsv 与 six.txt。
#
# 第 7 条（break-agreement-pdf）的尺子单独说清：它量 Word 自己导出的 PDF
# （artifacts/agent-typeset/pdf-truth/input-liu.pdf，逐行读回原文）里每个段落行尾的字符偏移集合，
# 与手机同一篇排出来的行尾偏移集合取交集/并集。第 5 条那个 69.7% 是另一把尺（对我们自己的行序
# 按行号对），两个数不可并列引用。单独跑第 7 条：
#     py tools/break-agreement.py -Capture artifacts/agent-layout-verify/<tag> -Out <tsv>
param(
    [string]$Serial = "EAMUT20528011355",
    [Parameter(Mandatory = $true)][string]$Tag,
    [string]$OutRoot = "artifacts/agent-layout-verify",
    [string]$WordCache = "artifacts/parity",
    # Engine source tree for both device runs, from tools/tree-snapshot.ps1. Empty = the working tree.
    [string]$Tree = "",
# 第 4 条把"每行差多少"换成"一页差几行"要乘的每页数。27 = 桌面 Word 正文页行数下界（真值
# artifacts/agent-layout-verify/page-stack-all/page_lines.tsv：正文页 27-35 行，章首页与图页更少；
# 与 docs/layout-parity-target.md 第 0 节记下的基准同一个口径，前后才可比。
    [int]$LinesPerPage = 27,
    [string]$WordPageLines = "artifacts/agent-layout-verify/page-stack-all/page_lines.tsv",
    [switch]$SkipDevice
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$Out = Join-Path $OutRoot $Tag
$d = Join-Path $Out "new"
$par = Join-Path $Out "parity"
New-Item -ItemType Directory -Force -Path $Out, $d, $par | Out-Null

function Run([string[]]$a) {
    & pwsh -NoProfile -File $a[0] @($a[1..($a.Count - 1)]) 2>&1
}
function Quantile($values, $q) {
    $s = @($values | ForEach-Object { [double]$_ } | Sort-Object)
    if ($s.Count -eq 0) { return [double]::NaN }
    return [double]$s[[math]::Min($s.Count - 1, [int][math]::Ceiling(($s.Count - 1) * $q))]
}
function Grab($lines, [string]$pattern, [int]$group) {
    $m = @($lines | Select-String -Pattern $pattern | Select-Object -First 1)
    if ($m.Count -eq 0 -or $m[0].Matches.Count -eq 0) { return "" }
    return $m[0].Matches[0].Groups[$group].Value
}

# ---------- 1. 手机侧采样：编译工作树，手机上装的那个 APK 不参与 ----------
$treeArgs = @()
if ($Tree) { $treeArgs = @("-Tree", $Tree) }
if (-not $SkipDevice) {
    $capArgs = @("tools/capture-device.ps1", "-Serial", $Serial, "-Impls", "new", "-OutDir", $Out) + $treeArgs
    $repArgs = @("artifacts/agent-typeset/run-repaginate.ps1", "-Serial", $Serial,
                 "-OutDir", (Join-Path $Out "repag")) + $treeArgs
    Run $capArgs
    Run $repArgs
}
if (-not (Test-Path (Join-Path $d "lines-all.tsv"))) { throw "missing capture: $d/lines-all.tsv" }

# ---------- 2. Word 真值缓存就位（只读缓存，不开 Word） ----------
foreach ($f in @("word-line-breaks.tsv", "word-para-index.tsv", "word-para-map.tsv",
                 "word-para-props.tsv", "selection.tsv")) {
    $src = Join-Path $WordCache $f
    if (Test-Path $src) { Copy-Item -Force $src (Join-Path $par $f) }
}

# ---------- 3. 第 1 条：段落页归属 ----------
$parity = @(Run @("tools/word-parity.ps1", "-Impl", "new",
        "-Device", (Join-Path $d "paragraphs-wordformat.tsv"),
        "-Sup", (Join-Path $d "superscript-inventory.txt"),
        "-Summary", (Join-Path $d "summary.txt"),
        "-OutFile", (Join-Path $Out "parity.tsv"), "-MaxShifted", "999"))
$m1Shifted = [int](Grab $parity '^shifted_paragraphs=(\d+)$' 1)
$m1Aligned = [int](Grab $parity '^aligned=(\d+) shifted' 1)
$m1Exact = Grab $parity '^exact_page_match=(\S+)$' 1
$m1Hist = Grab $parity '^page_delta_histogram: (.+)$' 1
$m1First = Grab $parity '^first_shifted: (\d+)' 1
$wordPages = [int](Grab $parity '^word_total_pages=(\d+)' 1)
$ourPages = [int](Grab $parity 'device_total_pages=(\d+)' 1)

# ---------- 4. 第 5 条：逐行换行点 ----------
$lb = @(Run @("tools/line-break-delta.ps1", "-Stage", "report",
        "-DeviceLines", (Join-Path $d "lines-all.tsv"),
        "-ScriptInventory", (Join-Path $d "superscript-inventory.txt"),
        "-DeviceSummary", (Join-Path $d "summary.txt"), "-OutDir", $par))
$m5Lines = [int](Grab $lb 'DONE paragraphs=\d+ lines=(\d+) aligned=' 1)
$m5Aligned = [int](Grab $lb 'lines=\d+ aligned=(\d+) rate=' 1)
$m5Rate = Grab $lb 'aligned=\d+ rate=([\d.]+%)' 1

# ---------- 5. 第 6 条：两端对齐右边界 ----------
$edge = @(Run @("tools/edge-parity.ps1", "-Impl", $d, "-Out", (Join-Path $Out "edge.tsv")))
$m6In = [int](Grab $edge 'within 1 px of the right margin: (\d+)/' 1)
$m6Total = [int](Grab $edge 'within 1 px of the right margin: \d+/(\d+)' 1)

# ---------- 6. 第 2/3/4 条：逐行行高（Word 相邻基线距离为真值） ----------
$lh = @(Run @("artifacts/agent-typeset/line-height-rows.ps1",
        "-Adv", (Join-Path $Out "repag/out/repag-advances.tsv"),
        "-Map", (Join-Path $par "word-para-map.tsv"),
        "-OutTsv", (Join-Path $Out "line-height-rows.tsv"),
        "-OutTxt", (Join-Path $Out "line-height-rows.txt")))
$allRow = @($lh | Where-Object { $_ -like "ALL *" })[0]
$m2N = [int](Grab @($allRow) 'n=(\d+)' 1)
$m2Word = [double](Grab @($allRow) 'word=([\d.]+)' 1)
$m2Med = [double](Grab @($allRow) 'med=(-?[\d.]+)' 1)
$rows = Import-Csv -LiteralPath (Join-Path $Out "line-height-rows.tsv") -Delimiter "`t"
$absDelta = @($rows | ForEach-Object { [math]::Abs([double]$_.delta) })
$m3P90 = Quantile $absDelta 0.9
$m3Max = Quantile $absDelta 1.0
$pl = @(Get-Content -LiteralPath $WordPageLines -Encoding UTF8 | Select-Object -Skip 1) |
    Where-Object { $_ -match "`t" } | ForEach-Object { [int](($_ -split "`t")[1]) }
    $bodyPages = @($pl | Where-Object { $_ -ge $LinesPerPage })
    $wordSpread = "Word 正文页 " + ($bodyPages | Measure-Object -Minimum -Maximum | ForEach-Object { "$($_.Minimum)-$($_.Maximum) 行（$($bodyPages.Count)/$($pl.Count) 页）" })
    $linesPerPage = $LinesPerPage
$m4Lines = [math]::Abs($m2Med) * $linesPerPage / $m2Word
# 最差队列：同一张表里每个队列自己的行高中位误差换算成一页差几行，取最大
$worst = ""
$worstLines = 0
foreach ($line in @($lh | Where-Object { $_ -match '^(\S.*?)\s+n=(\d+)\s+word=([\d.]+)\s+.*med=(-?[\d.]+)' -and $_ -notlike "ALL *" })
) {
    $c = [regex]::Match($line, '^(.*?)\s+n=(\d+)\s+word=([\d.]+)\s+.*?med=(-?[\d.]+)')
    if ($c.Groups[1].Value.Trim() -eq "" ) { continue }
    $cw = [double]$c.Groups[3].Value
    if ($cw -le 0) { continue }
    $cl = [math]::Abs([double]$c.Groups[4].Value) * $linesPerPage / $cw
    if ($cl -gt $worstLines) { $worstLines = $cl; $worst = $c.Groups[1].Value.Trim() }
}

# ---------- 7. 第 7 条：断点一致率（Word 导出 PDF 逐行读回原文，逐段比行尾偏移集合） ----------
# 这一条既不碰手机也不重开 Word 会话：读第 1 步产出的 <Out>/new/lines-all.tsv，配上仓库里的 Word
# PDF 真值。量法、方向拆分、断点分类全写在 tools/break-agreement.py 的头注里，这里只取它打印的数。
$py = if (Get-Command py -ErrorAction SilentlyContinue) { "py" } else { "python" }
$m7Both = 0; $m7Union = 0; $m7PhoneOnly = 0; $m7WordOnly = 0; $m7Para = 0; $m7PageShift = 0
try {
    $b = @(& $py (Join-Path $root "tools/break-agreement.py") "-Capture" $Out "-Out" (Join-Path $Out "break-agreement.tsv") 2>&1)
    $m7Both      = [int](Grab $b 'BREAK AGREEMENT \(set ruler\): (\d+) of \d+ break points' 1)
    $m7Union     = [int](Grab $b 'BREAK AGREEMENT \(set ruler\): \d+ of (\d+) break points' 1)
    $m7PhoneOnly = [int](Grab $b 'phone_only \(we broke, Word did not\) = (\d+)' 1)
    $m7WordOnly  = [int](Grab $b 'word_only  \(Word broke, we did not\) = (\d+)' 1)
    $m7Para      = [int](Grab $b 'paragraphs compared=(\d+)' 1)
    $m7PageShift = [int](Grab $b 'page assignment from this same truth file: (\d+) of' 1)
} catch {
    "warn: 第 7 条没跑起来（$($_.Exception.Message)），这一格按没量处理"
}

# ---------- 8. 采样指纹：谁在什么时候、拿哪份代码量的 ----------
$engine = @{}
foreach ($l in @(Get-Content -LiteralPath (Join-Path $d "engine.tsv") -Encoding UTF8)) {
    $c = $l -split "`t"; if ($c.Count -ge 2) { $engine[$c[0]] = $c[1] }
}
$sha = { param($p) if (Test-Path -LiteralPath $p) { (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash.Substring(0, 16) } else { "missing" } }
$fontSha = (Get-ChildItem -File "app/src/main/assets/fonts" | Sort-Object Name |
    ForEach-Object { (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.Substring(0, 8) + $_.Name }) -join ","
$fontTag = [string](Get-FileHash -Algorithm SHA256 -InputStream ([System.IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes($fontSha)))).Hash.Substring(0, 12)

$rows6 = @(
    [pscustomobject]@{ n = 1; metric = "段落页归属"; value = "$m1Shifted 段错页 / $m1Aligned（exact $m1Exact，页差直方图 $m1Hist，首个错页 para $m1First）"; line = "错页 <= 2 段且 exact >= 99%"; pass = ($m1Shifted -le 2); file = "$Out/parity.tsv" },
    [pscustomobject]@{ n = 2; metric = "逐行行高误差中位"; value = ("{0:0.###} px（n={1}，Word {2:0.###} px）" -f $m2Med, $m2N, $m2Word); line = "绝对值 <= 0.10 px"; pass = ([math]::Abs($m2Med) -le 0.10); file = "$Out/line-height-rows.txt" },
    [pscustomobject]@{ n = 3; metric = "逐行行高误差 p90"; value = ("{0:0.###} px（|误差|，max {1:0.###}）" -f $m3P90, $m3Max); line = "<= 0.50 px"; pass = ($m3P90 -le 0.50); file = "$Out/line-height-rows.tsv" },
    [pscustomobject]@{ n = 4; metric = "每页累计高度误差"; value = ("{0:0.00} 行（按每页 {1} 行；{2}；最差队列 {3} {4:0.00} 行）" -f $m4Lines, $linesPerPage, $wordSpread, $worst, $worstLines); line = "<= 0.25 行"; pass = ($m4Lines -le 0.25); file = "$Out/line-height-rows.txt" },
    [pscustomobject]@{ n = 5; metric = "逐行换行点一致率"; value = "$m5Aligned/$m5Lines = $m5Rate"; line = ">= 90%"; pass = ($m5Lines -gt 0 -and ($m5Aligned / [math]::Max(1, $m5Lines)) -ge 0.90); file = "$par/line-delta.md" },
    [pscustomobject]@{ n = 6; metric = "右边界超出 1px 的行数"; value = "$($m6Total - $m6In) / $m6Total"; line = "0 / 32（守住）"; pass = (($m6Total - $m6In) -eq 0); file = "$Out/edge.tsv" },
    [pscustomobject]@{ n = 7; metric = "断点一致率(PDF 真值)"; value = "$(if ($m7Union -eq 0) { "没量到（tools/break-agreement.py 没跑起来或缺 Word PDF 真值）" } else { "$m7Both/$m7Union = $('{0:0.0}' -f (100.0 * $m7Both / [math]::Max(1, $m7Union)))%（$m7Para 段；我们多断 $m7PhoneOnly 处、少断 $m7WordOnly 处；同一份真值算出错页 $m7PageShift 段）" })"; line = ">= 90%（Word 导出 PDF 逐行读回，与第 5 条不是同一把尺）"; pass = ($m7Union -gt 0 -and ($m7Both / [math]::Max(1, $m7Union)) -ge 0.90); file = "$Out/break-agreement.tsv" }
)
""
"== 七条验收  tag=$Tag  页数 ours/Word = $ourPages/$wordPages =="
foreach ($r in $rows6) {
    "{0} {1,-14} {2,-56} 验收线 {3}  [{4}]" -f $r.n, $r.metric, $r.value, $r.line, $(if ($r.pass) { "过" } else { "不过" })
}
"engine head_sha=$($engine['head_sha']) layout_dirty=$($engine['layout_dirty']) dex=$($engine['dex_sha256'])"
"lines-all sha=$(& $sha (Join-Path $d 'lines-all.tsv'))  pages.tsv sha=$(& $sha (Join-Path $d 'pages.tsv'))  字体表指纹=$fontTag"
$rows6 | Export-Csv -NoTypeInformation -Delimiter "`t" -LiteralPath (Join-Path $Out "six.tsv")
@("tag=$Tag", "head_sha=$($engine['head_sha'])", "layout_dirty=$($engine['layout_dirty'])",
  "lines_all_sha=$(& $sha (Join-Path $d 'lines-all.tsv'))", "fonts_fingerprint=$fontTag",
  "word_page_lines=$WordPageLines") + @($rows6 | ForEach-Object { "$($_.n)`t$($_.metric)`t$($_.value)`t$($_.line)`t$(if ($_.pass) { '过' } else { '不过' })`t$($_.file)" }) |
    Set-Content -Encoding utf8NoBOM -LiteralPath (Join-Path $Out "six.txt")
