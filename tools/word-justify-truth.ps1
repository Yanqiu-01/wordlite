# word-justify-truth.ps1
#
# Ground-truth measurement harness: asks Microsoft Word (16.0, via COM) where it actually puts
# characters on a justified line and where a right tab stop with a dot leader really lands.
# Measurement only - the document is opened read-only and nothing is saved.
#
#   pwsh tools/word-justify-truth.ps1 -Mode Inventory
#   pwsh tools/word-justify-truth.ps1 -Mode Measure -Paragraphs 79,81,121
#   pwsh tools/word-justify-truth.ps1 -Mode Toc    -Paragraphs 101,103,105,107,109
#   pwsh tools/word-justify-truth.ps1 -Mode Analyze          # no Word, reads the TSVs
#   pwsh tools/word-justify-truth.ps1 -Mode Xml              # no Word, reads the docx
#
# COM facts measured on THIS build (Word 16.0), all constants probed on live ranges:
#   Information(3)  page number                       works (wdActiveEndPageNumber)
#   Information(5)  x relative to PAGE, pt            works, but on the first line of a paragraph
#                                                   the first-line indent is SUBTRACTED from the
#                                                   true x, so it is only usable with a correction.
#   Information(6)  y relative to page, pt            works (constant within a line)
#   Information(7)  x relative to left text boundary  works, twip-rounded (0.05 pt) - primary x.
#                                                   Quirk: always returns 0 for the first character
#                                                   of any line, so line-initial x is rebuilt from
#                                                   Information(5) plus the paragraph indents.
#   Information(9)  column (1-based char index in line)  works
#   Information(10) line number within the page       works (wdFirstCharacterLineNumber)
#   Information(1)  returns 2, Information(23) returns False -> the constants named in the brief
#                                                   are NOT the horizontal ones on this build.
#   Document.Lines / Range.Runs are empty here (see tools/word-line-truth.ps1), so lines are
#   recovered from Information(10)/Information(9) and the x sequence.
# Units: everything Word returns is POINTS. px = pt * 96/72 = pt * 4/3. Twips = pt * 20.
param(
    [ValidateSet('Inventory', 'Measure', 'Toc', 'Analyze', 'Xml')]
    [string]$Mode = 'Inventory',
    [string]$Docx = 'E:\download\claude\Wordlite\tests\samples\input-liu.docx',
    [string]$OutDir = 'E:\download\claude\Wordlite\artifacts\word-justify',
    [string]$Paragraphs = '',
    [int]$MaxChars = 420
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$docxPath = (Resolve-Path $Docx).Path
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$PT_TO_PX = 4.0 / 3.0
function ConvertTo-Px([double]$pt) { return [Math]::Round($pt * $PT_TO_PX, 2) }
function Round2([double]$v) { if ($null -eq $v) { return '' }; return [Math]::Round($v, 3) }

function Get-NumInfo($range, [int]$c) {
    try { $v = $range.Information($c); if ($v -is [bool]) { return [double]::NaN }; return [double]$v }
    catch { return [double]::NaN }
}
function Get-CharClass([string]$ch) {
    if ([string]::IsNullOrEmpty($ch)) { return 'empty' }
    $c = [int][char]$ch[0]
    if ($c -eq 13 -or $c -eq 10 -or $c -eq 7) { return 'paraend' }
    if ($c -eq 9) { return 'tab' }
    if ($c -eq 11) { return 'softbreak' }
    if ($c -eq 32 -or $c -eq 0x3000) { return 'space' }
    if ($c -ge 0x3000 -and $c -le 0x303F) { return 'cjkpunct' }
    if ($c -ge 0x3040 -and $c -le 0x30FF) { return 'kana' }
    if ($c -ge 0x3100 -and $c -le 0x312F) { return 'bopomofo' }
    if ($c -ge 0x4E00 -and $c -le 0x9FFF) { return 'cjk' }
    if ($c -ge 0x3400 -and $c -le 0x4DBF) { return 'cjk' }
    if ($c -ge 0xF900 -and $c -le 0xFAFF) { return 'cjk' }
    if ($c -ge 0xFF00 -and $c -le 0xFF60) { return 'cjkpunct' }
    if ($c -ge 0xFFE0 -and $c -le 0xFFE6) { return 'cjkpunct' }
    if ($c -ge 0x2010 -and $c -le 0x2027) { return 'punct' }
    if (($c -ge 0x41 -and $c -le 0x5A) -or ($c -ge 0x61 -and $c -le 0x7A)) { return 'latin' }
    if ($c -ge 0x30 -and $c -le 0x39) { return 'digit' }
    if ($c -ge 0x2000 -and $c -le 0x206F) { return 'punct' }
    return 'other'
}
function Split-ParaList([string]$s) {
    return @($s -split '[,; ]+' | Where-Object { $_ } | ForEach-Object { [int]$_ })
}

function Invoke-WordSession([scriptblock]$Body) {
    $word = $null; $document = $null
    # Another agent may hold its own Word open: remember the PIDs that were already running so
    # the finally block below stops only the instance this session created.
    $before = @(Get-Process WINWORD -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
    try {
        $word = New-Object -ComObject Word.Application
        $word.Visible = $false
        $word.DisplayAlerts = 0
        $document = $word.Documents.Open($docxPath, $false, $true)   # ConfirmConversions=false ReadOnly=true
        $document.Repaginate()
        & $Body $word $document
    } finally {
        if ($document) { $document.Close($false); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($document) }
        if ($word) { $word.Quit(); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($word) }
        Start-Sleep -Milliseconds 800
        Get-Process WINWORD -ErrorAction SilentlyContinue |
            Where-Object { $before -notcontains $_.Id } | Stop-Process -Force
    }
}
function TryProp($obj, [string]$name) {
    try { $v = $obj.$name
        if ($null -eq $v) { return [double]::NaN }
        if ($v -is [bool]) { if ($v) { return 1 } else { return 0 } }
        if ($v -is [string]) { if ($v -eq '') { return [double]::NaN }; try { return [double]$v } catch { return [double]::NaN } }
        return [double]$v
    } catch { return [double]::NaN }
}
function StrProp($obj, [string]$name) { try { return [string]$obj.$name } catch { return '' } }
function Style-Name($para) {
    try { $s = $para.Style; if ($null -eq $s) { return '' }
        if ($s -is [int] -or $s -is [long]) { return "builtin_$s" }
        try { return [string]$s.NameLocal } catch { return [string]$s }
    } catch { return '' }
}

# ---------- Mode: Inventory ----------
if ($Mode -eq 'Inventory') {
    $rows = [System.Collections.Generic.List[string]]::new()
    $rows.Add((@('para','page','align','fmt_align','style','len','start','n_cjk','n_cjkpunct','n_latin','n_digit','n_space','n_tab','n_softbreak','rightdot_stop_pt','font_far','font_ascii','font_size_pt','text') -join [char]9))
    $summary = [System.Collections.Generic.List[string]]::new()
    Invoke-WordSession {
        param($word, $document)
        $count = [int]$document.Paragraphs.Count
        $summary.Add("word_version=`t$($word.Version)")
        $summary.Add("docx=`t$docxPath")
        $summary.Add("paragraphs=`t$count")
        $summary.Add("pages=`t$($document.ComputeStatistics(2))")
        for ($s = 1; $s -le [int]$document.Sections.Count; $s++) {
            $ps = $document.Sections.Item($s).PageSetup
            $w = TryProp $ps 'PageWidth'; $lm = TryProp $ps 'LeftMargin'; $rm = TryProp $ps 'RightMargin'
            $summary.Add(("section`t{0}`tpage_width_pt`t{1}`tleft_margin_pt`t{2}`tright_margin_pt`t{3}`ttext_width_pt`t{4}" -f `
                $s, (Round2 $w), (Round2 $lm), (Round2 $rm), (Round2 ($w - $lm - $rm))))
        }
        for ($p = 1; $p -le $count; $p++) {
            $para = $document.Paragraphs.Item($p)
            $r = $para.Range
            $abs = [int]$r.Start; $len = [int]$r.End - $abs
            $text = [string]$r.Text
            $plain = $text -replace "[`r`n`a]", ''
            $cls = @($plain.ToCharArray() | ForEach-Object { Get-CharClass ([string]$_) })
            $n = @{ cjk = 0; cjkpunct = 0; latin = 0; digit = 0; space = 0; tab = 0; softbreak = 0 }
            foreach ($c in $cls) { if ($n.ContainsKey($c)) { $n[$c]++ } }
            $align = [int](TryProp $para 'Alignment')
            $fmtAlign = [int](TryProp $para.Format 'Alignment')
            $page = [int](Get-NumInfo $r 3)
            $font = $r.Font
            $ffar = StrProp $font 'NameFarEast'
            $fasii = StrProp $font 'Name'
            $fsize = Round2 (TryProp $font 'Size')
            $rightdot = ''
            if ($n['tab'] -gt 0) {
                foreach ($ts in $para.TabStops) {
                    $pos = TryProp $ts 'Position'; $led = TryProp $ts 'Leader'; $ta = TryProp $ts 'Alignment'
                    if ($led -gt 0 -or ($ta -eq 2 -and [Math]::Abs($pos % 36) -gt 0.01)) {
                        $rightdot = ('{0}/{1}/{2}' -f (Round2 $pos), [int]$led, [int]$ta)
                    }
                }
            }
            $rows.Add((($p, $page, $align, $fmtAlign, (Style-Name $para), $len, $abs, $n['cjk'], $n['cjkpunct'], $n['latin'], `
                $n['digit'], $n['space'], $n['tab'], $n['softbreak'], $rightdot, $ffar, $fasii, $fsize, `
                ($plain.Substring(0, [Math]::Min(48, $plain.Length)) -replace "`t", ' ')) -join "`t"))
            if ($p % 60 -eq 0) { "inventory: $p/$count" }
        }
    }
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'inventory.tsv') -Value $rows
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'inventory-summary.txt') -Value $summary
    "rows=$(($rows.Count - 1))  out=$(Join-Path $OutDir 'inventory.tsv')"
}

# ---------- shared per-character scanner ----------
$script:FORMAT_PROPS = @('Alignment', 'FirstLineIndent', 'CharacterUnitFirstLineIndent', 'LeftIndent',
    'CharacterUnitLeftIndent', 'RightIndent', 'CharacterUnitRightIndent', 'LineSpacingRule', 'LineSpacing',
    'SpaceBefore', 'SpaceAfter', 'SpaceBeforeAuto', 'SpaceAfterAuto', 'WidowControl', 'AdjustRightIndent',
    'SnapToGrid', 'FarEastLineBreakControl', 'WordSpacing', 'BaseAlignment', 'FrameTextAlignment')
$script:CHAR_HEADER = @('para', 'k', 'char', 'cp', 'class', 'x_rel_pt', 'line', 'col', 'page', 'y_page_pt',
    'x_page_pt', 'font_size_pt', 'font_far', 'font_ascii', 'font_spacing_pt', 'font_kern_pt')
$script:META_EXTRA = @('softbreaks', 'tabs', 'surrogates', 'text')

function Measure-ParagraphGeometry($word, $document, [int]$ordinal, [int]$cap) {
    $para = $document.Paragraphs.Item($ordinal)
    $r = $para.Range
    $abs = [int]$r.Start
    $len = [int]$r.End - $abs
    $full = [string]$r.Text
    $pf = $para.Format
    $meta = [ordered]@{ para = $ordinal; start = $abs; len = $len
        truncated = $(if ($len -gt $cap) { 1 } else { 0 }); align = [int](TryProp $para 'Alignment')
        style = (Style-Name $para); page = [int](Get-NumInfo $r 3) }
    foreach ($prop in $script:FORMAT_PROPS) { $meta["fmt_$prop"] = Round2 (TryProp $pf $prop) }
    $f0 = $r.Font
    $meta['font_size_pt'] = Round2 (TryProp $f0 'Size')
    $meta['font_far'] = StrProp $f0 'NameFarEast'
    $meta['font_ascii'] = StrProp $f0 'Name'
    $meta['font_spacing_pt'] = Round2 (TryProp $f0 'Spacing')
    $meta['font_kern_pt'] = Round2 (TryProp $f0 'Kerning')
    $meta['softbreaks'] = ([regex]::Matches($full, "`v")).Count
    $meta['tabs'] = ([regex]::Matches($full, "`t")).Count
    $meta['surrogates'] = ([regex]::Matches($full, '[\uD800-\uDFFF]')).Count
    $meta['text'] = ($full -replace "[`a]", '<cell>')
    $rows = [System.Collections.Generic.List[string]]::new()
    $n = [Math]::Min($len, $cap)
    $prevLine = -1.0; $prevClass = ''
    for ($k = 0; $k -lt $n; $k++) {
        $ch = [string]$full[$k]
        $cls = Get-CharClass $ch
        $cr = $document.Range($abs + $k, $abs + $k + 1)
        $x = Get-NumInfo $cr 7
        $line = Get-NumInfo $cr 10
        $lineStart = ($line -ne $prevLine)
        $col = ''; $page = ''; $ypage = ''; $xpage = ''
        if ($lineStart) {
            $col = Round2 (Get-NumInfo $cr 9); $page = Round2 (Get-NumInfo $cr 3)
            $ypage = Round2 (Get-NumInfo $cr 6); $xpage = Round2 (Get-NumInfo $cr 5)
            $prevLine = $line
        }
        $fsz = ''; $ffar = ''; $fasii = ''; $fsp = ''; $fkern = ''
        if ($lineStart -or $cls -ne $prevClass) {
            $f = $cr.Font
            $fsz = Round2 (TryProp $f 'Size'); $ffar = StrProp $f 'NameFarEast'
            $fasii = StrProp $f 'Name'; $fsp = Round2 (TryProp $f 'Spacing')
            $fkern = Round2 (TryProp $f 'Kerning')
        }
        $prevClass = $cls
        $disp = $ch -replace "`t", '<tab>' -replace "`v", '<softbreak>' -replace "`r", '<para>' -replace "`a", '<cell>'
        $cp = if ([int][char]$ch[0] -lt 0x10000) { 'U+{0:X4}' -f [int][char]$ch[0] } else { 'U+?' }
        $rows.Add(((@($ordinal, $k, $disp, $cp, $cls, (Round2 $x), (Round2 $line), $col, $page, $ypage, $xpage, `
            $fsz, $ffar, $fasii, $fsp, $fkern) | ForEach-Object { [string]$_ }) -join "`t"))
    }
    return [pscustomobject]@{ Meta = $meta; Rows = $rows; Para = $para }
}

# ---------- Mode: Measure ----------
if ($Mode -eq 'Measure') {
    $wanted = Split-ParaList $Paragraphs
    if (-not $wanted) { throw 'pass -Paragraphs 79,81,...' }
    $charRows = [System.Collections.Generic.List[string]]::new()
    $charRows.Add(($script:CHAR_HEADER -join "`t"))
    $metaRows = [System.Collections.Generic.List[string]]::new()
    $metaHeader = $null
    Invoke-WordSession {
        param($word, $document)
        foreach ($p in $wanted) {
            $m = Measure-ParagraphGeometry $word $document $p $MaxChars
            $charRows.AddRange($m.Rows)
            if ($null -eq $metaHeader) { $metaHeader = @($m.Meta.Keys); $metaRows.Add(($metaHeader -join "`t")) }
            $metaRows.Add((($metaHeader | ForEach-Object { $v = $m.Meta[$_]; ([string]$v -replace "`t", ' ') }) -join "`t"))
            "measured para=$($m.Meta['para']) len=$($m.Meta['len']) rows=$($m.Rows.Count)"
        }
    }
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'chars.tsv') -Value $charRows
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'paras-measured.tsv') -Value $metaRows
    "chars_rows=$(($charRows.Count - 1))  paras=$($wanted.Count)"
}

# ---------- Mode: Toc ----------
if ($Mode -eq 'Toc') {
    $wanted = Split-ParaList $Paragraphs
    if (-not $wanted) { throw 'pass -Paragraphs <toc paragraph ordinals>' }
    $charRows = [System.Collections.Generic.List[string]]::new()
    $charRows.Add(($script:CHAR_HEADER -join "`t"))
    $metaRows = [System.Collections.Generic.List[string]]::new()
    $tabRows = [System.Collections.Generic.List[string]]::new()
    $tabRows.Add((@('para','tab_index','position_pt','position_twips','position_px','leader','leader_name','alignment','alignment_name','is_default_set') -join [char]9))
    $metaHeader = $null
    Invoke-WordSession {
        param($word, $document)
        foreach ($p in $wanted) {
            $m = Measure-ParagraphGeometry $word $document $p $MaxChars
            $charRows.AddRange($m.Rows)
            if ($null -eq $metaHeader) { $metaHeader = @($m.Meta.Keys); $metaRows.Add(($metaHeader -join "`t")) }
            $metaRows.Add((($metaHeader | ForEach-Object { $v = $m.Meta[$_]; ([string]$v -replace "`t", ' ') }) -join "`t"))
            $i = 0
            foreach ($ts in $m.Para.TabStops) {
                $i++
                $pos = TryProp $ts 'Position'; $led = [int](TryProp $ts 'Leader'); $ta = [int](TryProp $ts 'Alignment')
                $isDef = StrProp $ts 'IsDefaultSet'
                $ledName = @{ 0 = 'spaces'; 1 = 'dots'; 2 = 'dashes'; 3 = 'lines'; 4 = 'heavy'; 5 = 'middleDot'; 6 = 'hyphen'; 7 = 'underscore' }[[Math]::Min($led, 7)]
                $alName = @{ 0 = 'left'; 1 = 'center'; 2 = 'right'; 3 = 'decimal'; 4 = 'bar'; 5 = 'list'; 6 = 'clear' }[[Math]::Min($ta, 6)]
                if ($led -gt 0 -or $ta -ne 0 -or [Math]::Abs($pos % 36) -gt 0.01) {
                    $tabRows.Add((($p, $i, (Round2 $pos), [int]($pos * 20), (ConvertTo-Px $pos), $led, $ledName, $ta, $alName, $isDef) -join "`t"))
                }
            }
            "toc para=$p len=$($m.Meta['len']) rows=$($m.Rows.Count)"
        }
    }
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'toc-chars.tsv') -Value $charRows
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'toc-paras.tsv') -Value $metaRows
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'toc-tabstops.tsv') -Value $tabRows
    "toc_char_rows=$(($charRows.Count - 1))  tab_rows=$(($tabRows.Count - 1))"
}

# ---------- Mode: Analyze (no Word) ----------
function Read-Tsv([string]$path) {
    $lines = [System.IO.File]::ReadAllLines($path)
    if ($lines.Count -lt 2) { return @() }
    $head = $lines[0] -split "`t"
    $out = [System.Collections.Generic.List[object]]::new()
    for ($n = 1; $n -lt $lines.Count; $n++) {
        $f = $lines[$n] -split "`t"
        if ($f.Count -lt $head.Count) { $f += @('') * ($head.Count - $f.Count) }
        $o = [ordered]@{}
        for ($c = 0; $c -lt $head.Count; $c++) { $o[$head[$c]] = $f[$c] }
        $out.Add([pscustomobject]$o)
    }
    return $out
}
function NumOr($v, $fallback) { $d = 0.0; if ([double]::TryParse([string]$v, [ref]$d)) { return $d } else { return $fallback } }
function Median($vals) { $s = @($vals | Where-Object { $null -ne $_ } | Sort-Object); if ($s.Count -eq 0) { return $null }
    if ($s.Count % 2 -eq 1) { return [double]$s[[int][Math]::Floor($s.Count / 2)] }
    return ([double]$s[$s.Count / 2 - 1] + [double]$s[$s.Count / 2]) / 2.0 }
function GroupStats($vals) {
    $g = $vals | Group-Object { [Math]::Round([double]$_, 2) } | Sort-Object { [double]$_.Name }
    return (@($g | ForEach-Object { '{0}x{1}' -f $_.Name, $_.Count }) -join ' ')
}
if ($Mode -eq 'Analyze') {
    $TextWidth = 425.2; $TabStop = 408.2
    foreach ($p in @($Paragraphs -split '[,; ]+' | Where-Object { $_ })) { }
    $paraFile = Join-Path $OutDir 'paras-measured.tsv'
    $tocMode = -not (Test-Path $paraFile)
    if ($tocMode) { $paraFile = Join-Path $OutDir 'toc-paras.tsv'; $charFile = Join-Path $OutDir 'toc-chars.tsv' }
    else { $charFile = Join-Path $OutDir 'chars.tsv' }
    $paras = Read-Tsv $paraFile
    $chars = Read-Tsv $charFile
    $lineRows = [System.Collections.Generic.List[string]]::new()
    $lineRows.Add((@('para', 'page', 'line', 'kind', 'chars', 'first_k', 'last_k', 'x_first_declared_pt', 'x_first_from_page_pt',
        'x_second_pt', 'x_last_pt', 'pitch_median_pt', 'pitch_min_pt', 'pitch_max_pt', 'last_gap_pt', 'right_edge_pt',
        'dev_from_right_edge_pt', 'dev_px', 'gap_pattern', 'text') -join "`t"))
    $gapRows = [System.Collections.Generic.List[string]]::new()
    $gapRows.Add((@('para', 'page', 'line', 'kind', 'k', 'prev_char', 'next_char', 'pair', 'gap_pt') -join "`t"))
    $notes = [System.Collections.Generic.List[string]]::new()
    foreach ($pm in $paras) {
        $pnum = [int]$pm.para
        $align = [int]$pm.align
        $alignName = @{ 0 = 'left'; 1 = 'center'; 2 = 'right'; 3 = 'justify' }[$align]
        $leftInd = NumOr $pm.fmt_LeftIndent 0.0
        $firstInd = NumOr $pm.fmt_FirstLineIndent 0.0
        $trunc = [int]$pm.truncated
        $rows = @($chars | Where-Object { [int]$_.para -eq $pnum } | Sort-Object { [int]$_.k })
        if ($rows.Count -eq 0) { continue }
        # contiguous-run grouping: page/x_page/col are only sampled at line starts, so a Group-Object
        # key built from them would split every line in two. Walk the rows instead.
        $groups = [System.Collections.Generic.List[object]]::new()
        $curGroup = $null
        foreach ($row in $rows) {
            if ($null -eq $curGroup -or ($row.line -ne $curGroup[-1].line)) {
                $curGroup = [System.Collections.Generic.List[object]]::new()
                $groups.Add([pscustomobject]@{ Name = "$($row.page)|$($row.line)"; Group = $curGroup })
            }
            $curGroup.Add($row)
        }
        $gi = 0; $total = $groups.Count
        foreach ($g in $groups) {
            $gi++
            $r = @($g.Group)
            $page = ($r | Where-Object { $_.page -ne "" } | Select-Object -First 1).page; $line = $r[0].line
            $lastInPara = ($gi -eq $total) -and ($trunc -eq 0)
            $hasParaMark = ($r[-1].class -eq 'paraend')
            $text = @($r | Where-Object { $_.class -ne 'paraend' })
            if ($text.Count -eq 0) { continue }
            $kind = if ($align -eq 3 -and -not $lastInPara) { 'justify-not-last' } elseif ($align -eq 3) { 'justify-LAST-line' } else { "$alignName" }
            $xs = @($text | ForEach-Object { NumOr $_.x_rel_pt 0.0 })
            $n = $xs.Count
            $isFirstLine = ($gi -eq 1)
            $declared = $leftInd + $(if ($isFirstLine) { $firstInd } else { 0.0 })
            $xPage0 = NumOr $r[0].x_page_pt 0.0
            $xSecond = if ($n -ge 2) { $xs[1] } else { $null }
            $inner = @(); for ($k = 2; $k -lt $n; $k++) { $inner += ($xs[$k] - $xs[$k - 1]) }
            $pitchMed = if ($inner.Count) { [Math]::Round((Median $inner), 4) } else { $null }
            $pitchMin = if ($inner.Count) { [Math]::Round(($inner | Measure-Object -Minimum).Minimum, 2) } else { $null }
            $pitchMax = if ($inner.Count) { [Math]::Round(($inner | Measure-Object -Maximum).Maximum, 2) } else { $null }
            $allInner = @(); for ($k = 1; $k -lt $n; $k++) { $allInner += ('{0:F2}' -f ($xs[$k] - $xs[$k - 1])) }
            $lastGap = if ($n -ge 2) { [Math]::Round($xs[$n - 1] - $xs[$n - 2], 2) } else { $null }
            $rightEdge = if ($n -ge 2) { [Math]::Round($xs[$n - 1] + $lastGap, 2) } else { $null }
            $dev = if ($null -ne $rightEdge) { [Math]::Round($rightEdge - $TextWidth, 2) } else { $null }
            $devPx = if ($null -ne $dev) { ConvertTo-Px $dev } else { $null }
            $txtShown = ((($text | ForEach-Object { $_.char }) -join '') -replace "`t", '<TAB>')
            $lineRows.Add(((($pnum, $page, $line, $kind, $n, $text[0].k, $text[-1].k, (Round2 $declared), (Round2 $xPage0),
                (Round2 $xSecond), (Round2 $xs[$n - 1]), $pitchMed, $pitchMin, $pitchMax, $lastGap, $rightEdge, $dev, $devPx,
                (GroupStats $allInner), $txtShown.Substring(0, [Math]::Min(60, $txtShown.Length))) | ForEach-Object { [string]$_ }) -join "`t"))
            for ($k = 1; $k -lt $n; $k++) {
                $gapRows.Add(((($pnum, $page, $line, $kind, $text[$k].k, $text[$k - 1].char, $text[$k].char,
                    "$($text[$k - 1].class)>$($text[$k].class)", ('{0:F2}' -f ($xs[$k] - $xs[$k - 1]))) | ForEach-Object { [string]$_ }) -join "`t"))
            }
        }
    }
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'lines-summary.tsv') -Value $lineRows
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'gaps.tsv') -Value $gapRows
    # aggregate gap statistics by line kind and character-pair class
    $gaps = Read-Tsv (Join-Path $OutDir 'gaps.tsv')
    $agg = [System.Collections.Generic.List[string]]::new()
    $agg.Add((@('kind', 'pair', 'n_gaps', 'min_pt', 'mean_pt', 'median_pt', 'max_pt', 'distinct_values') -join "`t"))
    foreach ($grp in ($gaps | Group-Object { "$($_.kind)|$($_.pair)" } | Sort-Object Name)) {
        $vals = @($grp.Group | ForEach-Object { [double]$_.gap_pt })
        $agg.Add(((($grp.Name -split '\|')[0], ($grp.Name -split '\|')[1], $vals.Count,
            ('{0:F2}' -f ($vals | Measure-Object -Minimum).Minimum), ('{0:F3}' -f ($vals | Measure-Object -Average).Average),
            ('{0:F2}' -f (Median $vals)), ('{0:F2}' -f ($vals | Measure-Object -Maximum).Maximum),
            (GroupStats $vals)) -join "`t"))
    }
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'gap-agg.tsv') -Value $agg
    "lines=$(( $lineRows.Count - 1 ))  gaps=$(( $gapRows.Count - 1 ))  agg=$(( $agg.Count - 1 ))"
    "out: lines-summary.tsv gaps.tsv gap-agg.tsv"
}

# ---------- Mode: Xml (no Word) ----------
if ($Mode -eq 'Xml') {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $unpack = Join-Path $OutDir 'unpacked'
    New-Item -ItemType Directory -Force -Path $unpack | Out-Null
    $zip = [System.IO.Compression.ZipFile]::OpenRead($docxPath)
    try {
        foreach ($n in @('word/document.xml', 'word/styles.xml', 'word/settings.xml')) {
            $entry = $zip.GetEntry($n)
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $unpack (Split-Path $n -Leaf)), $true)
        }
    } finally { $zip.Dispose() }
    $W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
    [xml]$doc = [IO.File]::ReadAllText((Join-Path $unpack 'document.xml'))
    [xml]$sty = [IO.File]::ReadAllText((Join-Path $unpack 'styles.xml'))
    [xml]$set = [IO.File]::ReadAllText((Join-Path $unpack 'settings.xml'))
    $nsd = New-Object System.Xml.XmlNamespaceManager($doc.NameTable); $nsd.AddNamespace('w', $W)
    $nss = New-Object System.Xml.XmlNamespaceManager($sty.NameTable); $nss.AddNamespace('w', $W)
    $nse = New-Object System.Xml.XmlNamespaceManager($set.NameTable); $nse.AddNamespace('w', $W)

    $styleById = [ordered]@{}
    foreach ($s in $sty.SelectNodes('/w:styles/w:style', $nss)) {
        $id = $s.GetAttribute('styleId', $W)
        $nameNode = $s.SelectSingleNode('w:name', $nss)
        $styleById[$id] = [pscustomobject]@{
            Id = $id; Name = $(if ($nameNode) { $nameNode.GetAttribute('val', $W) } else { '' })
            Type = $s.GetAttribute('type', $W); IsDefault = $s.GetAttribute('default', $W)
            BasedOn = $(($s.SelectSingleNode('w:basedOn', $nss)).GetAttribute('val', $W))
            PPr = $s.SelectSingleNode('w:pPr', $nss); RPr = $s.SelectSingleNode('w:rPr', $nss)
        }
    }
    $defaultParaStyle = $styleById.Values | Where-Object { $_.Type -eq 'paragraph' -and $_.IsDefault -eq '1' } | Select-Object -First 1
    $docDefaultPPr = $sty.SelectSingleNode('/w:styles/w:docDefaults/w:rPrDefault', $nss)
    $docDefaultRPr = $sty.SelectSingleNode('/w:styles/w:docDefaults/w:rPrDefault/w:rPr', $nss)
    function StyleChain($styleId) {
        $chain = [System.Collections.Generic.List[object]]::new(); $seen = @{}; $cur = $styleId
        while ($cur -and $styleById.Contains($cur) -and -not $seen.ContainsKey($cur)) {
            $seen[$cur] = 1; $chain.Add($styleById[$cur]); $cur = $styleById[$cur].BasedOn
        }
        if ($defaultParaStyle -and -not $seen.ContainsKey($defaultParaStyle.Id)) { $chain.Add($defaultParaStyle) }
        return $chain
    }
    function NodeAttr($node) {
        if ($null -eq $node) { return $null }
        foreach ($a in @('val', 'w', 'chars', 'start', 'left', 'line', 'before', 'after')) {
            $v = $node.GetAttribute($a, $W)
            if ($v) { return $v }
        }
        return ''
    }
    function Resolve([System.Xml.XmlElement]$direct, $chain, [string]$path, [switch]$OnOff) {
        # direct pPr/rPr wins, then the style chain (style, basedOn..., default style), then docDefaults
        if ($direct) {
            $n = $direct.SelectSingleNode($path, $nsd)
            if ($n) { if ($OnOff) { $v = $n.GetAttribute('val', $W); return [pscustomobject]@{ V = $(if ($v) { $v } else { 'on' }); S = 'direct' } }
                return [pscustomobject]@{ V = (NodeAttr $n); S = 'direct' } }
        }
        foreach ($st in $chain) {
            $n = $st.PPr.SelectSingleNode($path, $nss)
            if ($n) { if ($OnOff) { $v = $n.GetAttribute('val', $W); return [pscustomobject]@{ V = $(if ($v) { $v } else { 'on' }); S = "style:$($st.Id)" } }
                return [pscustomobject]@{ V = (NodeAttr $n); S = "style:$($st.Id)" } }
        }
        return [pscustomobject]@{ V = ''; S = 'inherited-default' }
    }
    $PPROPS = [ordered]@{
        jc = 'w:jc'; textAlignment = 'w:textAlignment'; snapToGrid = 'w:snapToGrid'; autoSpaceDE = 'w:autoSpaceDE'
        autoSpaceDN = 'w:autoSpaceDN'; adjustRightInd = 'w:adjustRightInd'; overflowPunct = 'w:overflowPunct'
        kinsoku = 'w:kinsoku'; contextualSpacing = 'w:contextualSpacing'; widowControl = 'w:widowControl'
        doNotExpandShiftReturn = 'w:doNotExpandShiftReturn'
    }
    $INDATTRS = @('w:left', 'w:start', 'w:right', 'w:end', 'w:firstLine', 'w:hanging', 'w:leftChars', 'w:rightChars', 'w:firstLineChars', 'w:hangingChars')
    $rows = [System.Collections.Generic.List[string]]::new()
    $head = @('p_index', 'in_table', 'style_id', 'style_name')
    foreach ($k in $PPROPS.Keys) { $head += @($k, "${k}_src") }
    $head += @('tabs_direct', 'ind_direct', 'ind_src', 'rPr_kern', 'rPr_spacing', 'rPr_sz', 'rPr_rFonts_eastAsia', 'text')
    $rows.Add(($head -join "`t"))
    $allP = $doc.SelectNodes('//w:p', $nsd)
    $idx = 0
    foreach ($p in $allP) {
        $idx++
        $inTable = if ($p.AncestorNodes().LocalName -contains 'tbl') { 1 } else { 0 }
        $pPr = $p.SelectSingleNode('w:pPr', $nsd)
        $styleId = $(if ($pPr) { ($pPr.SelectSingleNode('w:pStyle', $nsd)).GetAttribute('val', $W) } else { '' })
        if (-not $styleId) { $styleId = $defaultParaStyle.Id }
        $chain = StyleChain $styleId
        $vals = [ordered]@{ p_index = $idx; in_table = $inTable; style_id = $styleId; style_name = $styleById[$styleId].Name }
        foreach ($k in $PPROPS.Keys) {
            $r = Resolve $pPr $chain $PPROPS[$k] -OnOff
            $vals[$k] = $r.V; $vals["${k}_src"] = $r.S
        }
        $tabsDirect = ''
        if ($pPr) {
            $tnodes = $pPr.SelectNodes('w:tabs/w:tab', $nsd)
            if ($tnodes.Count) { $tabsDirect = (($tnodes | ForEach-Object { '{0}/leader={1}/pos={2}' -f (NodeAttr $_), $_.GetAttribute('leader', $W), $_.GetAttribute('pos', $W) }) -join ';') }
        }
        if (-not $tabsDirect) {
            foreach ($st in $chain) {
                $tn = $st.PPr.SelectSingleNode('w:tabs', $nss)
                if ($tn) { $tabsDirect = (($tn.SelectNodes('w:tab', $nss) | ForEach-Object { '{0}/leader={1}/pos={2}' -f (NodeAttr $_), $_.GetAttribute('leader', $W), $_.GetAttribute('pos', $W) }) -join ';') + " [style:$($st.Id)]"; break }
            }
        }
        $indDirect = ''
        if ($pPr) {
            $ind = $pPr.SelectSingleNode('w:ind', $nsd)
            if ($ind) { $indDirect = (($INDATTRS | ForEach-Object { $v = $ind.GetAttribute($_.Substring(2), $W); if ($v) { "$_=$v" } }) -join ' ') }
        }
        $indSrc = 'direct'
        if (-not $indDirect) {
            $indSrc = 'inherited-default'
            foreach ($st in $chain) {
                $ind = $st.PPr.SelectSingleNode('w:ind', $nss)
                if ($ind) { $indDirect = (($INDATTRS | ForEach-Object { $v = $ind.GetAttribute($_.Substring(2), $W); if ($v) { "$_=$v" } }) -join ' '); $indSrc = "style:$($st.Id)"; break }
            }
        }
        # run-level properties that matter for justification
        $kern = [System.Collections.Generic.List[string]]::new(); $spc = [System.Collections.Generic.List[string]]::new()
        $sz = [System.Collections.Generic.List[string]]::new(); $ea = [System.Collections.Generic.List[string]]::new()
        foreach ($rPr in $p.SelectNodes('.//w:r/w:rPr', $nsd)) {
            $kn = $rPr.SelectSingleNode('w:kern', $nsd); if ($kn) { $kern.Add((NodeAttr $kn)) }
            $sn = $rPr.SelectSingleNode('w:spacing', $nsd); if ($sn) { $spc.Add((NodeAttr $sn)) }
            $zn = $rPr.SelectSingleNode('w:sz', $nsd); if ($zn) { $sz.Add((NodeAttr $zn)) }
            $fn = $rPr.SelectSingleNode('w:rFonts', $nsd); if ($fn) { $ea.Add($fn.GetAttribute('eastAsia', $W)) }
        }
        $text = (($p.SelectNodes('.//w:t', $nsd) | ForEach-Object { $_.InnerText }) -join '')
        $rows.Add(((($vals.GetEnumerator() | ForEach-Object { $_.Value }) + @($tabsDirect, $indDirect, $indSrc,
            (($kern | Select-Object -Unique) -join '/'), (($spc | Select-Object -Unique) -join '/'),
            (($sz | Select-Object -Unique) -join '/'), (($ea | Select-Object -Unique) -join '/'),
            ($text.Substring(0, [Math]::Min(40, $text.Length)) -replace "`t", ' '))) -join "`t"))
    }
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'xml-paragraphs.tsv') -Value $rows
    # per-property presence summary
    $sum = [System.Collections.Generic.List[string]]::new()
    $sum.Add((@('property', 'set_directly_on_w:p', 'values_direct', 'set_on_styles', 'styles', 'paragraphs_with_any_value', 'effective_value_counts') -join "`t"))
    $data = Read-Tsv (Join-Path $OutDir 'xml-paragraphs.tsv')
    foreach ($k in $PPROPS.Keys) {
        $dir = @($data | Where-Object { $_."${k}_src" -eq 'direct' })
        $sty2 = @($data | Where-Object { $_."${k}_src" -like 'style:*' })
        $arr1 = @(
            $k, $dir.Count,
            (($dir | ForEach-Object { $_.$k } | Select-Object -Unique) -join '/'),
            $sty2.Count,
            (($sty2 | ForEach-Object { $_."${k}_src" } | Select-Object -Unique) -join '/'),
            @($data | Where-Object { $_.$k -ne '' }).Count,
            ((($data | Group-Object $k | ForEach-Object { $_.Name + 'x' + $_.Count }) -join ' '))
        )
        $sum.Add(($arr1 -join "`t"))
    }
    foreach ($k in @('tabs_direct', 'ind_direct')) {
        $withVal = @($data | Where-Object { $_.$k -ne '' })
        $arr2 = @($k, $withVal.Count, ((($withVal | ForEach-Object { $_.$k } | Group-Object | ForEach-Object { $_.Name + 'x' + $_.Count }) | Select-Object -First 6) -join ' ;; '), '', '', '', '')
        $sum.Add(($arr2 -join "`t"))
    }   # foreach ($k in @('tabs_direct', 'ind_direct')) -- closed 2026-10-08: the brace was missing,
        # so every write below ran inside the loop and the script would not parse at all.
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'xml-props-summary.tsv') -Value $sum
    # styles that carry justification-relevant properties
    $srows = [System.Collections.Generic.List[string]]::new()
    $srows.Add((@('style_id', 'style_name', 'type', 'default', 'basedOn', 'pPr_xml', 'rPr_xml') -join "`t"))
    foreach ($st in $styleById.Values) {
        $px = if ($st.PPr) { ($st.PPr.InnerXml -replace '<w:pPr[^>]*>', '' -replace '</w:pPr>', '') } else { '' }
        $rx = if ($st.RPr) { ($st.RPr.InnerXml -replace '<w:rPr[^>]*>', '' -replace '</w:rPr>', '') } else { '' }
        $keep = ($px -match 'jc|tabs|ind|snapToGrid|autoSpace|adjustRightInd|textAlignment|overflowPunct|kinsoku') -or ($rx -match 'kern|spacing|rFonts|sz')
        if (-not $keep) { continue }
        $srows.Add(((($st.Id, $st.Name, $st.Type, $st.IsDefault, $st.BasedOn, ($px -replace "`r?`n", ''), ($rx -replace "`r?`n", '')) | ForEach-Object { [string]$_ }) -join "`t"))
    }
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'xml-styles.tsv') -Value $srows
    # document-level settings
    $grows = [System.Collections.Generic.List[string]]::new()
    $grows.Add('setting`tvalue')
    foreach ($n in $set.SelectNodes('/w:settings/*', $nse)) {
        if ($n.LocalName -eq 'compat') {
            foreach ($cn in $n.ChildNodes) { $grows.Add(("compat/{0}`t{1}" -f $cn.LocalName, (NodeAttr $cn))) }
        } else { $grows.Add(("{0}`t{1}" -f $n.LocalName, (NodeAttr $n))) }
    }
    foreach ($dg in $doc.SelectNodes('//w:sectPr/w:docGrid', $nsd)) {
        $grows.Add(("sectPr/docGrid`tscale={0} linePitch={1} charSpace={2} type={3} linePitchBase={4} charSpaceBase={5}" -f `
            $dg.GetAttribute('scale', $W), $dg.GetAttribute('linePitch', $W), $dg.GetAttribute('charSpace', $W), $dg.GetAttribute('type', $W), `
            $dg.GetAttribute('linePitchBase', $W), $dg.GetAttribute('charSpaceBase', $W)))
    }
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'xml-settings.tsv') -Value $grows
    "paragraphs=$(( $rows.Count - 1 ))  props=$(( $sum.Count - 1 ))  styles=$(( $srows.Count - 1 ))  settings=$(( $grows.Count - 1 ))"
}
