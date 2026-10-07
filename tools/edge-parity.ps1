# edge-parity.ps1 -- right-edge (justification) parity between our layout and Microsoft Word.
#
#   pwsh tools/edge-parity.ps1 -Impl new
#   pwsh tools/edge-parity.ps1 -Impl old -Out artifacts/edge-parity/old.tsv
#   pwsh tools/edge-parity.ps1 -Impl E:\anywhere\capture -Top 10 -SkipToc
#
# For every line of a device capture (artifacts/device/<impl>/lines-all.tsv, written by
# tools/capture-device.ps1) whose text also appears in Word's own per-line measurements, this
# compares where our layout put the line's right edge with where Word put it. Line matching
# follows tools/word-parity.ps1: align by normalized text in document order, resync with a bounded
# lookahead, and count what cannot be matched instead of dropping it.
#
# Truth files (POINTS, produced offline by tools/word-justify-truth.ps1 driving Word 16.0):
#   artifacts/word-justify/lines-summary.tsv  right_edge_pt per line plus the line's kind
#                                             (justify-not-last / justify-LAST-line / left ...)
#   artifacts/word-justify/toc-chars.tsv      per-character x of the five measured TOC entries; the
#                                             line's advance end is the paragraph-mark x, or, when
#                                             absent, x(last char) + the last non-space gap, which
#                                             is the formula lines-summary.tsv itself uses.
# Neither file covers the whole document, so the line count below is "what Word measured", not
# "every line in the document".
#
# Units. The engine lays out in 96-DPI document units (PageGeometry.java):
#   PageGeometry.twips(t)  = t / 15          (1 pt = 20 twips -> 20/15 = 4/3 units)
#   PageGeometry.points(p) = p * 96 / 72     = p * 4 / 3
# Word reports points, so Word pt -> our px is exactly * 4 / 3. lines-all.tsv lineWidthPx is
# already in those units, so no other scaling happens here.
#
# What "right edge" means on each side. Both sides are counted in DOCUMENT coordinates, from the
# column's left text boundary, so nothing has to be added by hand here:
#   Word  right_edge_pt comes from Range.Information(7), which already includes a first-line or
#         left indent because it is measured from that same boundary.
#   Ours  lines-all.tsv records the line geometry itself: paraXPx is where the paragraph layout is
#         drawn (ParagraphLayout.text.x, 0 for a paragraph starting on the text margin) and
#         lineLeftPx is StaticLayout.getLineLeft(line). Both share Word's origin, so
#         our_right_px = para_x_px + line_left_px + our_width_px with all three measured.
#         indent_px is kept for reference only (Word's declared w:ind / w:firstLine for the line):
#         the engine bills a first-line indent inside the line's own advance, so adding it to the
#         width would count the same indent twice.
#
# is_last_line: Word's kind column says justify-LAST-line for the tail of a justified paragraph.
# In a left-aligned paragraph every line carries kind=left, so the last measured line of such a
# paragraph is called last only when it lands well short of the text width (<75%), which is what a
# paragraph tail looks like. The four summary buckets are disjoint: the toc-tab-entry rows are
# one-line paragraphs and would otherwise also land in last-line. Note that -match is case-insensitive in PowerShell and would also
# match "justify-not-last"; compare the kind with -eq instead.
#
# Honesty rules: a Word line with no counterpart in the capture is counted and printed as
# unmatched, never dropped silently; and Word's own deviation from the text width (its hanging
# punctuation, its own ragged left-aligned lines) is carried next to the delta so a big number is
# not automatically read as our bug.
param(
    # old | new | any directory that holds lines-all.tsv (+ summary.txt).
    [string]$Impl = "new",
    [string]$Out = "",
    [string]$WordLines = "artifacts/word-justify/lines-summary.tsv",
    [string]$WordToc = "artifacts/word-justify/toc-chars.tsv",
    [string]$WordTocParas = "artifacts/word-justify/toc-paras.tsv",
    [string]$WordTocStops = "artifacts/word-justify/toc-tabstops.tsv",
    [string]$DeviceRoot = "artifacts/device",
    # Body-section text width in points (artifacts/word/geometry.txt: sections 2-3 = 425.2 pt).
    # Overridden by contentWidth from the capture's own summary.txt when that file exists.
    [double]$TextWidthPt = 425.2,
    [int]$Top = 6,
    [switch]$SkipToc
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
while ($root -and -not (Test-Path (Join-Path $root "artifacts/word"))) { $root = Split-Path -Parent $root }
if (-not $root) { $root = Split-Path -Parent $PSScriptRoot }
Set-Location $root

$PtScale = 4.0 / 3.0
function ToPx([double]$points) { return [Math]::Round($points * $PtScale, 2) }
function R2([double]$v) { return [Math]::Round($v, 2) }
function NumOr([string]$v, $fallback) {
    $d = 0.0
    if ([double]::TryParse([string]$v, [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$d)) { return $d }
    return $fallback
}
function Read-Tsv([string]$file) {
    if (-not (Test-Path $file)) { throw "missing truth file: $file" }
    $lines = @(Get-Content -LiteralPath $file -Encoding UTF8)
    $items = New-Object System.Collections.Generic.List[object]
    if ($lines.Count -lt 2) { return ,$items }
    $head = $lines[0] -split "`t"
    for ($n = 1; $n -lt $lines.Count; $n++) {
        if ([string]::IsNullOrWhiteSpace($lines[$n])) { continue }
        $f = $lines[$n] -split "`t"
        if ($f.Count -lt $head.Count) { $f += @('') * ($head.Count - $f.Count) }
        $o = [ordered]@{}
        for ($col = 0; $col -lt $head.Count; $col++) { $o[$head[$col]] = $f[$col] }
        $items.Add([pscustomobject]$o) | Out-Null
    }
    return ,$items
}
# Line identity: the capture strips every space inside a line (DeviceCapture.clean) and both
# harnesses truncate at 60 chars, so spaces and tab/para markers go on both sides here too.
function Norm([string]$s) {
    if ($null -eq $s) { return "" }
    $v = $s -replace '(?i)<tab>', '' -replace '(?i)<para>', ''
    $v = $v -replace '\.\.\.$', ''
    return ($v -replace '[\s\u00a0\u2000-\u200b\u3000\ufeff]', '')
}
function Esc([string]$s) {
    if ($null -eq $s) { return "" }
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $s.ToCharArray()) {
        $c = [int]$ch
        if ($c -ge 32 -and $c -le 126) { [void]$sb.Append($ch) } else { [void]$sb.Append('\u').Append($c.ToString('x4')) }
    }
    return $sb.ToString()
}
function KeyHash([string]$s) {
    $h = 5381
    foreach ($ch in $s.ToCharArray()) { $h = (($h * 33) -bxor [int]$ch) -band 0x7FFFFFFF }
    return $h.ToString('x8')
}
# Exact on the normalized text, or equal up to the 60-char capture truncation.
function SameText([string]$a, [string]$b) {
    if ($a.Length -eq 0 -or $b.Length -eq 0) { return $false }
    if ($a -eq $b) { return $true }
    $m = [Math]::Min($a.Length, $b.Length)
    if ($m -lt 55) { return $false }
    return $a.Substring(0, $m) -eq $b.Substring(0, $m)
}
function PrefixParagraphMatch([string]$a, [string]$b) {
    # Short entries (a TOC line of six characters) cannot carry a prefix test, so they only
    # match when the two keys are identical; anything longer must agree over its first 8.
    if ($a -eq $b) { return $a.Length -ge 4 }
    $m = [Math]::Min($a.Length, [Math]::Min($b.Length, 30))
    if ($m -lt 8) { return $false }
    return $a.StartsWith($b.Substring(0, $m)) -or $b.StartsWith($a.Substring(0, $m))
}
function Quantiles($vals) {
    $s = @($vals)
    if ($s.Count -eq 0) { return [pscustomobject]@{ n = 0; mean = 0.0; median = 0.0; p90 = 0.0; max = 0.0 } }
    $s = @($s | Sort-Object)
    $avg = ($s | Measure-Object -Average).Average
    $median = if ($s.Count % 2) { $s[[int](($s.Count - 1) / 2)] } else { ($s[$s.Count / 2 - 1] + $s[$s.Count / 2]) / 2.0 }
    $p90 = $s[[Math]::Min($s.Count - 1, [Math]::Ceiling(0.9 * $s.Count) - 1)]
    return [pscustomobject]@{ n = $s.Count; mean = $avg; median = $median; p90 = $p90; max = ($s | Measure-Object -Maximum).Maximum }
}
function StdDev($vals) {
    $s = @($vals)
    if ($s.Count -lt 2) { return 0.0 }
    $avg = ($s | Measure-Object -Average).Average
    $sq = 0.0
    foreach ($v in $s) { $sq += ($v - $avg) * ($v - $avg) }
    return [Math]::Sqrt($sq / $s.Count)
}

# ---------- 1. our capture ----------
$dir = if (Test-Path (Join-Path $Impl "lines-all.tsv")) { $Impl } else { Join-Path $DeviceRoot $Impl }
if (-not (Test-Path (Join-Path $dir "lines-all.tsv"))) { throw "no lines-all.tsv under $dir" }
if (-not $Out) { $Out = Join-Path "artifacts/edge-parity" ((Split-Path -Leaf $dir) + ".tsv") }
$widthPx = [Math]::Round($TextWidthPt * $PtScale, 4)
$summaryPath = Join-Path $dir "summary.txt"
if (Test-Path $summaryPath) {
    foreach ($line in @(Get-Content -LiteralPath $summaryPath -Encoding UTF8)) {
        if ($line -match 'contentWidth=([0-9.]+)') {
            $widthPx = [double]$Matches[1]
            $TextWidthPt = [Math]::Round($widthPx / $PtScale, 4)
            break
        }
    }
}
$devRows = Read-Tsv (Join-Path $dir "lines-all.tsv")
# Document coordinates need the two geometry columns the probe added after the first captures.
foreach ($need in @('lineLeftPx', 'paraXPx')) {
    if ($devRows.Count -eq 0 -or -not ($devRows[0].PSObject.Properties.Name -contains $need)) {
        throw "lines-all.tsv under $dir has no $need column; re-capture it: pwsh tools/capture-device.ps1 -Impls $(Split-Path -Leaf $dir)"
    }
}

# Device paragraph blocks in document order: lines-all.tsv is emitted page by page and the block
# index never moves backwards, so a block's first occurrence places it in the document.
$blocks = New-Object System.Collections.Generic.List[object]
$byIndex = @{}
foreach ($r in $devRows) {
    $idx = [int]$r.paragraph
    if (-not $byIndex.ContainsKey($idx)) {
        $b = [pscustomobject]@{ index = $idx; page = [int]$r.page; lines = (New-Object System.Collections.Generic.List[object]); key = '' }
        $byIndex[$idx] = $b
        $blocks.Add($b) | Out-Null
    }
    $byIndex[$idx].lines.Add([pscustomobject]@{
        page  = [int]$r.page; line = [int]$r.line; chars = [int]$r.lineChars
        width = (NumOr $r.lineWidthPx 0.0); left = (NumOr $r.lineLeftPx 0.0)
        paraX = (NumOr $r.paraXPx 0.0); text = (Norm $r.lineFull); raw = $r.lineFull
    }) | Out-Null
}
foreach ($b in $blocks) { $b.key = Norm (($b.lines | ForEach-Object { $_.text }) -join '') }

# ---------- 2. Word truth ----------
$wLines = New-Object System.Collections.Generic.List[object]
$truthLines = Read-Tsv $WordLines
foreach ($r in $truthLines) {
    $kind = [string]$r.kind
    $wLines.Add([pscustomobject]@{
        source      = 'lines-summary'; para = [int]$r.para; page = [int]$r.page; line = [int]$r.line
        kind        = $kind; first = ((NumOr $r.first_k 1.0) -eq 0); last = ($kind -eq 'justify-LAST-line')
        declared_pt = (NumOr $r.x_first_declared_pt 0.0)
        right_pt    = (NumOr $r.right_edge_pt [double]::NaN)
        word_dev_px = $(if ([string]::IsNullOrWhiteSpace([string]$r.dev_from_right_edge_pt)) { $null } else { ToPx (NumOr $r.dev_from_right_edge_pt 0.0) })
        stop_px     = $null
        chars       = [int](NumOr $r.chars 0); text = (Norm $r.text)
    }) | Out-Null
}
$tocLineCount = 0
if (-not $SkipToc -and (Test-Path $WordToc)) {
    $tocChars = Read-Tsv $WordToc
    $paraFmt = @{}
    if (Test-Path $WordTocParas) {
        foreach ($r in (Read-Tsv $WordTocParas)) {
            $paraFmt[[int]$r.para] = [pscustomobject]@{
                left  = (NumOr $r.fmt_LeftIndent 0.0); first = (NumOr $r.fmt_FirstLineIndent 0.0)
                align = [int](NumOr $r.align 0)
            }
        }
    }
    $stops = @{}
    if (Test-Path $WordTocStops) {
        foreach ($r in (Read-Tsv $WordTocStops)) {
            $p = [int]$r.para
            if (-not $stops.ContainsKey($p)) { $stops[$p] = (NumOr $r.position_pt [double]::NaN) }
        }
    }
    foreach ($g in ($tocChars | Group-Object { [string]$_.para })) {
        $pnum = [int]$g.Name
        $all = @($g.Group | Sort-Object { [int]$_.k })
        $rows = @($all | Where-Object { $_.class -ne 'paraend' })
        if ($rows.Count -lt 2) { continue }
        # The paragraph-mark row carries the pen position after the last glyph, which for a
        # single-line TOC entry IS the line's advance end. Only when it is missing fall back to the
        # lines-summary formula (x of the last char + the last non-space, non-tab gap).
        $paraMark = @($all | Where-Object { $_.class -eq 'paraend' } | Select-Object -Last 1)
        $right = [double]::NaN
        if ($paraMark.Count -eq 1) { $right = NumOr $paraMark[0].x_rel_pt [double]::NaN }
        if ([double]::IsNaN($right)) {
            $real = @($rows | Where-Object { $_.class -ne 'tab' -and $_.class -ne 'space' } |
                ForEach-Object { NumOr $_.x_rel_pt [double]::NaN })
            $real = @($real | Where-Object { -not [double]::IsNaN($_) })
            if ($real.Count -ge 2) { $right = [Math]::Round($real[-1] + ($real[-1] - $real[-2]), 2) }
        }
        if ([double]::IsNaN($right)) { continue }
        $fmt = if ($paraFmt.ContainsKey($pnum)) { $paraFmt[$pnum] } else { $null }
        $align = if ($fmt) { $fmt.align } else { 0 }
        $leftPt = if ($fmt) { $fmt.left } else { 0.0 }
        $firstPt = if ($fmt) { $fmt.first } else { 0.0 }
        $wLines.Add([pscustomobject]@{
            source      = 'toc-chars'; para = $pnum
            page        = [int](NumOr $rows[0].page 0); line = [int](NumOr $rows[0].line 0)
            kind        = $(if ($align -eq 3) { 'justify-not-last' } elseif ($align -eq 1) { 'center' } elseif ($align -eq 2) { 'right' } else { 'left' })
            first       = $true; last = ($align -ne 3)
            declared_pt = ($leftPt + $firstPt)
            right_pt    = $right
            word_dev_px = (ToPx ($right - $TextWidthPt))
            stop_px     = $(if ($stops.ContainsKey($pnum)) { ToPx $stops[$pnum] } else { $null })
            chars       = $rows.Count
            text        = Norm ((($rows | ForEach-Object { [string]$_.char }) -join ''))
        }) | Out-Null
        $tocLineCount++
    }
}
# Group into Word paragraphs (document order) and settle is_last_line for non-justified tails.
$wordParas = New-Object System.Collections.Generic.List[object]
foreach ($g in ($wLines | Group-Object { [string]$_.para } | Sort-Object { [int]$_.Name })) {
    $rows = @($g.Group | Sort-Object { $_.page }, { $_.line })
    $tail = $rows[$rows.Count - 1]
    if ($tail.source -eq 'lines-summary' -and $tail.kind -ne 'justify-not-last' -and $tail.kind -ne 'justify-LAST-line' `
            -and -not [double]::IsNaN($tail.right_pt) -and $tail.right_pt -lt (0.75 * $TextWidthPt)) {
        $tail.last = $true
    }
    $wordParas.Add([pscustomobject]@{
        para = [int]$g.Name; lines = $rows; key = (Norm (($rows | ForEach-Object { $_.text }) -join ''))
    }) | Out-Null
}

# ---------- 3. align by text in document order (the word-parity.ps1 strategy) ----------
$rowsOut = New-Object System.Collections.Generic.List[object]
$unmatchedParas = New-Object System.Collections.Generic.List[string]
$unmatchedLines = New-Object System.Collections.Generic.List[string]
$cursor = 0; $paraAligned = 0; $devLinesSkipped = 0
foreach ($wp in $wordParas) {
    $hit = -1
    for ($j = $cursor; $j -lt $blocks.Count; $j++) {
        if (PrefixParagraphMatch $wp.key $blocks[$j].key) { $hit = $j; break }
    }
    if ($hit -lt 0) {
        $unmatchedParas.Add(("word_para={0} lines={1} text={2}" -f $wp.para, $wp.lines.Count, (Esc $wp.key))) | Out-Null
        continue
    }
    $paraAligned++
    $cursor = $hit + 1
    $wl = $wp.lines; $dl = $blocks[$hit].lines
    $i = 0; $k = 0
    while ($i -lt $wl.Count -and $k -lt $dl.Count) {
        if (SameText $wl[$i].text $dl[$k].text) {
            $w = $wl[$i]; $d = $dl[$k]
            $indentPx = ToPx $w.declared_pt
            $ourRight = R2 ($d.paraX + $d.left + $d.width)
            $wordRight = $(if ([double]::IsNaN($w.right_pt)) { $null } else { ToPx $w.right_pt })
            $rowsOut.Add([pscustomobject]@{
                source        = $w.source; word_para = $w.para; word_page = $w.page; word_line = $w.line
                our_page      = $d.page; our_para = $blocks[$hit].index; our_line = $d.line
                kind          = $w.kind; is_last_line = [int][bool]$w.last; char_count = $d.chars
                indent_px     = $indentPx
                para_x_px     = R2 $d.paraX
                line_left_px  = R2 $d.left
                our_width_px  = R2 $d.width
                our_right_px  = $ourRight
                word_right_px = $wordRight
                delta_px      = $(if ($null -eq $wordRight) { $null } else { R2 ($ourRight - $wordRight) })
                our_gap_px    = R2 ($ourRight - $widthPx)
                word_gap_px   = $w.word_dev_px
                word_stop_px  = $w.stop_px
                text_hash     = (KeyHash $wl[$i].text)
                text          = (Esc $dl[$k].raw)
            }) | Out-Null
            $i++; $k++; continue
        }
        $ahead = 0
        for ($s = 1; $s -le 12 -and ($k + $s) -lt $dl.Count; $s++) { if (SameText $wl[$i].text $dl[$k + $s].text) { $ahead = $s; break } }
        if ($ahead -gt 0) { $devLinesSkipped += $ahead; $k += $ahead; continue }
        $beyond = 0
        for ($s = 1; $s -le 12 -and ($i + $s) -lt $wl.Count; $s++) { if (SameText $wl[$i + $s].text $dl[$k].text) { $beyond = $s; break } }
        if ($beyond -gt 0) {
            for ($s = 0; $s -lt $beyond; $s++) {
                $unmatchedLines.Add(("word_para={0} word_line={1} kind={2} text={3}" -f $wl[$i + $s].para, $wl[$i + $s].line, $wl[$i + $s].kind, (Esc $wl[$i + $s].text))) | Out-Null
            }
            $i += $beyond; continue
        }
        $unmatchedLines.Add(("word_para={0} word_line={1} kind={2} text={3}" -f $wl[$i].para, $wl[$i].line, $wl[$i].kind, (Esc $wl[$i].text))) | Out-Null
        $i++
    }
    for (; $i -lt $wl.Count; $i++) {
        $unmatchedLines.Add(("word_para={0} word_line={1} kind={2} text={3}" -f $wl[$i].para, $wl[$i].line, $wl[$i].kind, (Esc $wl[$i].text))) | Out-Null
    }
}

# ---------- 4. write the per-line TSV (ASCII, CRLF) ----------
$cols = @('source', 'word_para', 'word_page', 'word_line', 'our_page', 'our_para', 'our_line', 'kind',
    'is_last_line', 'char_count', 'indent_px', 'para_x_px', 'line_left_px', 'our_width_px', 'our_right_px', 'word_right_px', 'delta_px',
    'our_gap_px', 'word_gap_px', 'word_stop_px', 'text_hash', 'text')
$outFull = if ([IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path (Get-Location) $Out }
$parent = Split-Path -Parent $outFull
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
$body = New-Object System.Collections.Generic.List[string]
$body.Add(($cols -join "`t")) | Out-Null
foreach ($r in $rowsOut) {
    $vals = @()
    foreach ($c in $cols) { $v = $r.$c; $vals += $(if ($null -eq $v) { '' } else { [string]$v }) }
    $body.Add(($vals -join "`t")) | Out-Null
}
[System.IO.File]::WriteAllText($outFull, (($body -join "`r`n") + "`r`n"), [System.Text.Encoding]::ASCII)

# ---------- 5. printed summary ----------
$notLast = { $_.kind -eq 'justify-not-last' -and $_.is_last_line -eq 0 }
$lastLine = { $_.is_last_line -eq 1 -and $_.source -eq 'lines-summary' }
$leftAlign = { $_.kind -ne 'justify-not-last' -and $_.is_last_line -eq 0 -and $_.source -eq 'lines-summary' }
$tocEntry = { $_.source -eq 'toc-chars' }
function Bucket($name, [scriptblock]$pred) {
    $sel = @($rowsOut | Where-Object -FilterScript $pred)
    $q = Quantiles @($sel | ForEach-Object { [Math]::Abs($_.delta_px) })
    $signed = if ($sel.Count) { ($sel | ForEach-Object { $_.delta_px } | Measure-Object -Average).Average } else { 0.0 }
    [pscustomobject]@{ name = $name; n = $q.n; mean = $q.mean; median = $q.median; p90 = $q.p90; max = $q.max; signed = $signed }
}
$buckets = @((Bucket 'justify-not-last' $notLast), (Bucket 'last-line' $lastLine),
    (Bucket 'left-aligned' $leftAlign), (Bucket 'toc-tab-entry' $tocEntry))
"impl=$(Split-Path -Leaf $dir)"
"capture=$dir"
"out=$Out"
$capFile = Get-Item -LiteralPath (Join-Path $dir "lines-all.tsv")
"capture_stamp=$($capFile.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')) sha256=$((Get-FileHash -Algorithm SHA256 -LiteralPath $capFile.FullName).Hash.Substring(0, 16)) bytes=$($capFile.Length)"
"our_lines_in_capture=$($devRows.Count)  our_paragraph_blocks=$($blocks.Count)"
"word_truth: lines-summary=$($truthLines.Count) toc-chars=$tocLineCount  total=$($wLines.Count) lines in $($wordParas.Count) paragraphs"
"text_width=$TextWidthPt pt = $widthPx px  (pt -> px = * 4/3, PageGeometry.points)"
"paragraphs aligned=$paraAligned/$($wordParas.Count)  lines matched=$($rowsOut.Count)/$($wLines.Count)"
"unmatched word lines=$($unmatchedLines.Count)  unmatched word paragraphs=$($unmatchedParas.Count)  device lines skipped=$devLinesSkipped"
""
"delta_px = our right edge - Word right edge, px at 96 dpi (positive = our ink sits right of Word's)"
"bucket n columns are disjoint and add up to lines matched; toc-tab-entry rows are kept out of last-line."
"{0,-18} {1,5} {2,9} {3,10} {4,8} {5,8} {6,12}" -f 'bucket', 'n', 'mean|x|', 'median|x|', 'p90|x|', 'max|x|', 'mean signed'
foreach ($b in $buckets) {
    "{0,-18} {1,5} {2,9:F2} {3,10:F2} {4,8:F2} {5,8:F2} {6,12:F2}" -f $b.name, $b.n, $b.mean, $b.median, $b.p90, $b.max, $b.signed
}
$offMargin = @($rowsOut | Where-Object { $_.line_left_px -ne 0 -or $_.para_x_px -ne 0 })
"rows starting off the text margin (para_x_px or line_left_px non-zero): n={0} mean|d|={1:F2} of {2} matched" -f `
    $offMargin.Count, $(if ($offMargin.Count) { ($offMargin | ForEach-Object { [Math]::Abs($_.delta_px) } | Measure-Object -Average).Average } else { 0 }), $rowsOut.Count
""
"raggedness = stddev (px) of OUR justified non-last line right edges inside one paragraph;"
"Word's own stddev over exactly those lines is printed next to it for scale."
$ourSDs = @(); $wordSDs = @(); $spreadSDs = @()
foreach ($g in ($rowsOut | Where-Object -FilterScript $notLast | Group-Object { "$($_.source)|$($_.word_para)" })) {
    if (@($g.Group).Count -lt 3) { continue }
    $ours = @($g.Group | ForEach-Object { $_.our_right_px })
    $ourSDs += (StdDev $ours)
    $wordSDs += (StdDev @($g.Group | ForEach-Object { $_.word_right_px }))
    $spreadSDs += (($ours | Measure-Object -Maximum).Maximum - ($ours | Measure-Object -Minimum).Minimum)
}
$qo = Quantiles $ourSDs; $qw = Quantiles $wordSDs; $qs = Quantiles $spreadSDs
"paragraphs with >=3 justified non-last lines: $($qo.n)"
"our  stddev px: mean={0:F2} median={1:F2} p90={2:F2} max={3:F2}" -f $qo.mean, $qo.median, $qo.p90, $qo.max
"word stddev px: mean={0:F2} median={1:F2} p90={2:F2} max={3:F2}" -f $qw.mean, $qw.median, $qw.p90, $qw.max
"our spread (max-min) px: mean={0:F2} median={1:F2} max={2:F2}" -f $qs.mean, $qs.median, $qs.max
$all = @($rowsOut | Where-Object -FilterScript $notLast | ForEach-Object { $_.our_right_px })
if ($all.Count) {
    "pooled justified non-last right edges: stddev={0:F2} px min={1:F2} max={2:F2} (text width {3:F2})" -f (StdDev $all), ($all | Measure-Object -Minimum).Minimum, ($all | Measure-Object -Maximum).Maximum, $widthPx
    $flush = @($all | Where-Object { [Math]::Abs($_ - $widthPx) -le 1.0 })
    $short = @($all | Where-Object { ($_ - $widthPx) -lt -8.0 })
    "within 1 px of the right margin: {0}/{1} ({2:N1}%)   more than 8 px short of it: {3} ({4:N1}%)" -f `
        $flush.Count, $all.Count, (100.0 * $flush.Count / $all.Count), $short.Count, (100.0 * $short.Count / $all.Count)
}
""
if ($Top -gt 0 -and $rowsOut.Count) {
    ""
    "worst $Top by |delta_px|:"
    foreach ($r in ($rowsOut | Where-Object { $null -ne $_.delta_px } | Sort-Object { [Math]::Abs($_.delta_px) } -Descending | Select-Object -First $Top)) {
        "  our p{0} blk{1} ln{2} | word para{3} ln{4} | {5} paraX={6} left={7} width={8} right={9} | word={10} delta={11} word_gap={12} stop={13}" -f `
            $r.our_page, $r.our_para, $r.our_line, $r.word_para, $r.word_line, $r.kind, $r.para_x_px, `
            $r.line_left_px, $r.our_width_px, $r.our_right_px, $r.word_right_px, $r.delta_px, `
            $r.word_gap_px, $r.word_stop_px
    }
}
if ($unmatchedLines.Count -gt 0) {
    ""
    "unmatched word lines (first $([Math]::Min(6, $unmatchedLines.Count)) of $($unmatchedLines.Count)):"
    $unmatchedLines | Select-Object -First 6 | ForEach-Object { "  $_" }
}
if ($unmatchedParas.Count -gt 0) {
    ""
    "unmatched word paragraphs (all):"
    $unmatchedParas | ForEach-Object { "  $_" }
}