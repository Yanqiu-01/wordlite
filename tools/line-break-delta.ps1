# Line-by-line break delta: Microsoft Word (COM) versus Word Lite (device capture).
#
#   pwsh tools/line-break-delta.ps1
#   pwsh tools/line-break-delta.ps1 -JvmParagraphs 63,67,70
#   pwsh tools/line-break-delta.ps1 -Stage report
#
# Stages: index  = dump every Word paragraph ordinal + text, prove the JVM mapping by text.
#         breaks = character scan that recovers where Word really breaks the selected lines.
#         report = join both sides and emit line-delta.tsv + line-delta.md.
#         all    = index (unless cached) + breaks + report in ONE Word session.
#
# Paragraph identity is always proved by paragraph text; the index offsets in
# artifacts/word/index_map_from_xml.tsv are only used as a search hint and are scored later.
param(
    [string]$Docx = "tests/samples/input-liu.docx",
    [string]$DeviceLines = "artifacts/device/new/lines-all.tsv",
    [string]$IndexMap = "artifacts/word/index_map_from_xml.tsv",
    [string]$ScriptInventory = "artifacts/device/new/superscript-inventory.txt",
    [string]$PageAlign = "artifacts/device/word-vs-device-new.tsv",
    [string]$DeviceSummary = "artifacts/device/new/summary.txt",
    [string]$ScriptLines = "artifacts/device/new/superscript-lines.txt",
    [string]$OutDir = "artifacts/parity",
    [string]$JvmParagraphs = "",
    [string[]]$WordParagraphs = @(),
    [switch]$All,
    [ValidateSet("all", "index", "breaks", "report")]
    [string]$Stage = "all",
    [int]$MaxChars = 400,
    [int]$ChunkSize = 24,
    [switch]$ReuseIndex,
    [switch]$OnlyMissing
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$WordIndexTsv = Join-Path $OutDir "word-para-index.tsv"
$WordBreakTsv = Join-Path $OutDir "word-line-breaks.tsv"
$WordPropTsv  = Join-Path $OutDir "word-para-props.tsv"
$MapTsv       = Join-Path $OutDir "word-para-map.tsv"
$SelTsv       = Join-Path $OutDir "selection.tsv"
$DeltaTsv     = Join-Path $OutDir "line-delta.tsv"
$DeltaMd      = Join-Path $OutDir "line-delta.md"
$CtxTxt       = Join-Path $OutDir "context.txt"

# ---------- small helpers ----------

function Write-Lines {
    param([string]$Path, $Lines)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    Set-Content -Encoding utf8NoBOM -Path $Path -Value @($Lines)
}

function Read-Tsv {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return @() }
    @(Import-Csv -LiteralPath $Path -Delimiter "`t")
}

function Save-Tsv {
    param([string]$Path, [string[]]$Header, $Rows)
    $out = New-Object System.Collections.Generic.List[string]
    $out.Add(($Header -join "`t"))
    foreach ($r in $Rows) {
        $vals = @()
        foreach ($h in $Header) { $vals += [string]$r.$h }
        $out.Add(($vals -join "`t"))
    }
    Write-Lines -Path $Path -Lines $out
}

# Display text: control marks, tabs and line breaks collapse to one space.
function Clean-Text {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return "" }
    return (([regex]::Replace($Text, '[\u0007\u000B\u000D\u0009\u000A\u0020]+', ' ')).Trim())
}

# Normalized text used to line the two engines up.
#   raw   (default): whitespace and Word control marks dropped, fullwidth ASCII folded to
#                    halfwidth, punctuation kept, so it counts every glyph that eats width.
#   loose (-Loose) : letters/digits/CJK only, which tells "different break points" apart from
#                    "different characters in the paragraph" (footnote refs, field results).
function Get-Norm {
    param([string]$Text, [switch]$Loose)
    if ([string]::IsNullOrEmpty($Text)) { return "" }
    $t = [regex]::Replace($Text, '[\s\u00A0\u2009\u200B\u3000\uFEFF\u0007\u000B]', '')
    $chars = $t.ToCharArray()
    $need = $false
    foreach ($ch in $chars) {
        $c = [int]$ch
        if (($c -ge 0xFF01 -and $c -le 0xFF5E) -or $c -eq 0x2010 -or $c -eq 0x2011) { $need = $true; break }
    }
    if ($need) {
        $sb = New-Object System.Text.StringBuilder
        foreach ($ch in $chars) {
            $c = [int]$ch
            if ($c -ge 0xFF01 -and $c -le 0xFF5E) { [void]$sb.Append([char]($c - 0xFEE0)) }
            elseif ($c -eq 0x2010 -or $c -eq 0x2011) { [void]$sb.Append([char]0x2D) }
            else { [void]$sb.Append($ch) }
        }
        $t = $sb.ToString()
    }
    if ($Loose) { $t = [regex]::Replace($t, '[^\p{L}\p{N}]+', '') }
    return $t
}

function Get-PropSafe {
    param($Object, [string]$Name, $Fallback = "")
    try { $v = $Object.$Name; if ($null -eq $v) { return $Fallback }; return $v } catch { return $Fallback }
}


# ---------- device side (Word Lite) ----------

# lines-all.tsv keys lines by the paginator blockIndex, which counts the four table blocks
# too, so it is NOT the same numbering as doc.paragraphs positions used by
# superscript-inventory.txt. Both are carried around separately and reconciled by text.
function Get-DeviceParagraphs {
    param([string]$Path)
    $rows = Read-Tsv $Path
    $map = @{}
    foreach ($r in $rows) {
        if (-not $r.paragraph) { continue }
        $blk = [int]$r.paragraph
        if (-not $map.Contains($blk)) { $map[$blk] = New-Object System.Collections.Generic.List[object] }
        $txt = [string]$r.lineFull
        $map[$blk].Add([pscustomobject]@{
                page  = [int]$r.page
                line  = [int]$r.line
                chars = [int]$r.lineChars
                px    = [double]$r.lineWidthPx
                text  = $txt
                exportTrunc = $txt.EndsWith("...")
                norm  = (Get-Norm $txt)
                loose = (Get-Norm $txt -Loose)
            })
    }
    # plain hashtable: an OrderedDictionary indexer treats an int key as a position
    $out = @{}
    foreach ($blk in @($map.Keys | Sort-Object)) {
        $lines = @($map[$blk] | Sort-Object page, line)
        $norm = ""; $loose = ""
        foreach ($l in $lines) { $norm += $l.norm; $loose += $l.loose }
        $out.Add($blk, [pscustomobject]@{
            block     = $blk
            lines     = $lines
            lineCount = $lines.Count
            pages     = @($lines | ForEach-Object { $_.page } | Select-Object -Unique)
            firstPage = $lines[0].page
            lastPage  = $lines[-1].page
            norm      = $norm
            loose     = $loose
            exportTrunc = (@($lines | Where-Object { $_.exportTrunc }).Count -gt 0)
        })
    }
    return $out
}

# superscript-lines.txt is one device line per row: blockIndex, StaticLayout line (1 based),
# first 8 and last 8 chars. It is the only per-line record of which lines hold a script run.
function Get-ScriptLineSet {
    param([string]$Path)
    $set = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $set }
    foreach ($ln in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        $c = $ln -split "`t"
        if ($c.Count -lt 2) { continue }
        $blk = 0; $li = 0
        if (-not ([int]::TryParse($c[0], [ref]$blk) -and [int]::TryParse($c[1], [ref]$li))) { continue }
        $set[("$blk|" + $li)] = ($c[2] + ".." + $c[3])
    }
    return $set
}

# superscript-inventory.txt is keyed by doc.paragraphs position, with a 40 char text prefix.
function Get-ScriptInventory {
    param([string]$Path)
    $list = New-Object System.Collections.Generic.List[object]
    if (-not (Test-Path -LiteralPath $Path)) { return $list }
    foreach ($ln in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        if (-not $ln -or $ln.StartsWith("#")) { continue }
        $c = $ln -split "`t"
        if ($c.Count -lt 3) { continue }
        $prefix = ($c[1] -replace '\.{3}$', '' -replace [string][char]0x2026 + '$', '')
        $list.Add([pscustomobject]@{
                jvm_pos  = [int]$c[0]
                prefix   = $prefix
                loose    = (Get-Norm $prefix -Loose)
                scripts  = $c[2]
            })
    }
    return $list
}

function Find-ScriptInfo {
    param($Inventory, [string]$DeviceLoose)
    foreach ($s in $Inventory) {
        if ($s.loose.Length -lt 8) { continue }
        if ($DeviceLoose.Length -lt 8) { continue }
        $n = [Math]::Min($s.loose.Length, $DeviceLoose.Length)
        if ($n -ge 8 -and $DeviceLoose.Substring(0, $n) -eq $s.loose.Substring(0, $n)) { return $s }
    }
    return $null
}

# ---------- Word side: paragraph index dump ----------

function Invoke-WordIndex {
    param($Document)
    $count = [int]$Document.Paragraphs.Count
    $rows = New-Object System.Collections.Generic.List[object]
    for ($i = 1; $i -le $count; $i++) {
        $para = $Document.Paragraphs.Item($i)
        $range = $para.Range
        $txt = Clean-Text ([string]$range.Text)
        $page = ""
        try { $page = [int]$range.Information(3) } catch { }
        $rows.Add([pscustomobject]@{
                word_para  = $i
                word_para0 = $i - 1
                first16    = if ($txt.Length -gt 16) { $txt.Substring(0, 16) } else { $txt }
                chars      = $txt.Length
                page       = $page
                norm       = (Get-Norm $txt)
                loose      = (Get-Norm $txt -Loose)
                text              = $txt
            })
    }
    $hdr = @("word_para", "word_para0", "first16", "chars", "page", "chars_norm", "chars_loose", "text")
    $flat = New-Object System.Collections.Generic.List[object]
    foreach ($r in $rows) {
        $flat.Add([pscustomobject]@{
                word_para = $r.word_para; word_para0 = $r.word_para0; first16 = $r.first16
                chars = $r.chars; page = $r.page
                chars_norm = $r.norm.Length; chars_loose = $r.loose.Length; text = $r.text
            })
    }
    Save-Tsv -Path $WordIndexTsv -Header $hdr -Rows $flat
    $script:IndexNote = "word_index paragraphs=$count -> $WordIndexTsv"
    return $rows
}

function Get-WordPageSetup {
    param($Document)
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("== Word page setup (points, 1 pt = 96/72 px at 96 DPI) ==")
    $lines.Add("section`twidth_pt`theight_pt`left_pt right_pt`ttextwidth_pt`ttextwidth_px96")
    for ($s = 1; $s -le [int]$Document.Sections.Count; $s++) {
        $ps = $Document.Sections.Item($s).PageSetup
        $w = [double](Get-PropSafe $ps "PageWidth" 0)
        $h = [double](Get-PropSafe $ps "PageHeight" 0)
        $l = [double](Get-PropSafe $ps "LeftMargin" 0)
        $r = [double](Get-PropSafe $ps "RightMargin" 0)
        $tw = $w - $l - $r
        $lines.Add(("{0}`t{1:N2}`t{2:N2}`t{3:N2}`t{4:N2}`t{5:N2}`t{6:N2}" -f $s, $w, $h, $l, $r, $tw, ($tw * 96 / 72)))
    }
    return $lines
}

# ---------- mapping: prove COM ordinal from device paragraph text ----------

function Get-Prefix { param([string]$S, [int]$N) if ([string]::IsNullOrEmpty($S)) { return "" }; if ($S.Length -le $N) { return $S }; return $S.Substring(0, $N) }

# Both sides walk the same document, so the match is anchored on text and forced monotone:
# a paragraph may only map to a Word ordinal larger than the previous match.
function Build-ParaMap {
    param($WordRows, $Device, $Inventory, [string]$IndexMapPath)
    $imap = Read-Tsv $IndexMapPath
    $predByPos = @{}
    $posByPred = @{}
    foreach ($r in $imap) {
        if (-not $r.jvm_para_index) { continue }
        $pos = [int]$r.jvm_para_index
        $pred = [int]$r.word_para_index_predicted
        $predByPos[$pos] = $pred
        if (-not $posByPred.ContainsKey($pred)) { $posByPred[$pred] = $pos }
    }
    $rows = New-Object System.Collections.Generic.List[object]
    $lastCom = 0
    foreach ($blk in @($Device.Keys | Sort-Object)) {
        $d = $Device[$blk]
        $hit = $null; $method = "none"
        $dp24 = Get-Prefix $d.loose 24
        $dp12 = Get-Prefix $d.loose 12
        foreach ($mode in @("loose_full", "norm_full", "loose_prefix24", "loose_prefix12")) {
            for ($i = $lastCom; $i -lt $WordRows.Count; $i++) {
                $w = $WordRows[$i]
                $ok = $false
                if ($mode -eq "loose_full") { $ok = ($d.loose.Length -ge 6) -and ($d.loose -eq $w.loose) }
                elseif ($mode -eq "norm_full") { $ok = ($d.norm.Length -ge 6) -and ($d.norm -eq $w.norm) }
                elseif ($mode -eq "loose_prefix24") { $ok = ($dp24.Length -ge 14) -and ($dp24 -eq (Get-Prefix $w.loose 24)) }
                else { $ok = ($dp12.Length -ge 12) -and ($dp12 -eq (Get-Prefix $w.loose 12)) }
                if ($ok) { $hit = $w; $method = $mode; break }
            }
            if ($hit) { break }
        }
        $com = 0
        if ($hit) { $com = [int]$hit.word_para; $lastCom = $com }
        $script = Find-ScriptInfo -Inventory $Inventory -DeviceLoose $d.loose
        $impliedPos = ""
        if ($com -gt 0 -and $posByPred.ContainsKey($com - 1)) { $impliedPos = $posByPred[$com - 1] }
        $mapOk = ""
        if ($script) {
            $p = $script.jvm_pos
            if ($predByPos.ContainsKey($p)) { $mapOk = if (([int]$predByPos[$p] + 1) -eq $com) { "1" } else { "0" } }
        }
        $rows.Add([pscustomobject]@{
                device_block    = $blk
                word_para       = $com
                com_minus_block = if ($com) { $com - $blk } else { "" }
                jvm_pos_implied = $impliedPos
                block_minus_pos = if ($com -and $impliedPos -ne "") { $blk - [int]$impliedPos } else { "" }
                inv_jvm_pos     = if ($script) { $script.jvm_pos } else { "" }
                inv_pred_com    = if ($script -and $predByPos.ContainsKey($script.jvm_pos)) { [int]$predByPos[$script.jvm_pos] + 1 } else { "" }
                index_map_ok    = $mapOk
                script_runs     = if ($script) { $script.scripts } else { "" }
                match_method    = $method
                word_chars      = if ($hit) { $hit.chars } else { "" }
                device_chars    = $d.norm.Length
                loose_equal     = if ($hit) { if ($d.loose -eq $hit.loose) { 1 } else { 0 } } else { "" }
                device_text16   = Get-Prefix $d.norm 16
                word_text16     = if ($hit) { Get-Prefix $hit.norm 16 } else { "" }
                device_lines    = $d.lineCount
                device_pages    = ($d.pages -join ",")
            })
    }
    $hdr = @("device_block", "word_para", "com_minus_block", "jvm_pos_implied", "block_minus_pos",
             "inv_jvm_pos", "inv_pred_com", "index_map_ok", "script_runs", "match_method",
             "word_chars", "device_chars", "loose_equal", "device_text16", "word_text16",
             "device_lines", "device_pages")
    Save-Tsv -Path $MapTsv -Header $hdr -Rows $rows
    $m = @($rows | Where-Object { $_.word_para -gt 0 })
    $script:MapNote = "map matched=$($m.Count)/$($rows.Count) -> $MapTsv"
    return $rows
}


# ---------- paragraph selection ----------

function Get-Spread {
    param($List, [int]$N)
    if ($List.Count -le $N) { return , @($List).ToArray() }
    $out = New-Object System.Collections.Generic.List[object]
    for ($i = 0; $i -lt $N; $i++) { $out.Add($List[[int][Math]::Floor($i * $List.Count / $N)]) }
    return , $out.ToArray()
}

# Figure captions, headings and bibliography entries break for their own reasons, so the
# prose cohorts below skip them; they are still in the map table for the record.
function Test-NoiseText {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $true }
    if ($Text -match '^(图|表|公式|参考文献|致谢|摘要|Abstract|ABSTRACT)') { return $true }
    if ($Text.StartsWith("[")) { return $true }
    if ($Text -match '^[0-9]+(\.[0-9]+)*$') { return $true }
    return $false
}

function Select-Paragraphs {
    param($MapRows, $Device, [switch]$Everything)
    $byWord = @{}
    $byBlock = @{}
    foreach ($r in $MapRows) {
        if ($r.word_para -gt 0) { $byWord[[int]$r.word_para] = $r; $byBlock[[int]$r.device_block] = $r }
    }
    $sel = New-Object System.Collections.Generic.List[object]
    $taken = @{}
    $add = {
        param($r, $cohort, $why)
        if ($null -eq $r -or $r.word_para -le 0) { return }
        $b = [int]$r.device_block
        if ($taken.ContainsKey($b)) { return }
        $taken[$b] = 1
        $sel.Add([pscustomobject]@{
                device_block = $b; word_para = [int]$r.word_para; cohort = $cohort; why = $why
                lines = $r.device_lines; pages = $r.device_pages; script_runs = $r.script_runs
                text = $r.device_text16
            })
    }
    if ($Everything) {
        foreach ($r in ($MapRows | Where-Object { $_.word_para -gt 0 -and $_.device_lines -ge 2 })) {
            & $add $r "z_all" "all paragraphs with at least 2 lines"
        }
        return , $sel.ToArray()
    }
    # a) body paragraphs that carry superscript or subscript runs
    $poolA = @($MapRows | Where-Object {
            $_.word_para -gt 0 -and $_.script_runs -and [int]$_.device_lines -ge 3 -and
            -not (Test-NoiseText $_.device_text16) })
    foreach ($r in (Get-Spread $poolA 12)) { & $add $r "a_script" $r.script_runs }
    # b) paragraphs at the page-number disagreement boundaries
    foreach ($wp in @(121, 155, 208, 209)) { & $add $byWord[$wp] "b_boundary" "word page differs by one" }
    # c) control group: plain prose without any script run
    $poolC = @($MapRows | Where-Object {
            $_.word_para -gt 0 -and -not $_.script_runs -and [int]$_.device_lines -ge 4 -and
            [int]$_.device_chars -ge 120 -and -not (Test-NoiseText $_.device_text16) -and
            $_.device_text16 -notmatch '^[0-9]' })
    foreach ($r in (Get-Spread $poolC 8)) { & $add $r "c_control" "plain prose, no script runs" }
    # d) paragraphs with a long latin or digit run (wordWrap / unbreakable token behaviour)
    $poolD = @()
    foreach ($blk in @($Device.Keys | Sort-Object)) {
        $d = $Device[$blk]
        if ($d.norm -match '[A-Za-z0-9][A-Za-z0-9\-\./%]{9,}' -or $d.norm -match 'http') {
            if ($byBlock.ContainsKey($blk)) { $poolD += $byBlock[$blk] }
        }
    }
    foreach ($r in (Get-Spread $poolD 5)) { & $add $r "d_latin" "long latin or digit run" }
    return , $sel.ToArray()
}



# ---------- Word side: recover the real break points ----------

$script:BreakHdr = @("word_para", "para_line", "line_on_page", "word_page", "chars", "chars_norm",
                     "start", "end", "end_char", "end_font_pt", "end_sup", "end_sub",
                     "start_font_pt", "start_sup", "start_sub", "text", "truncated", "line_sup", "line_sub", "line_font_pt")
$script:PropHdr  = @("word_para", "device_block", "font_pt", "align", "firstline_pt", "firstline_chars",
                     "autospacede", "autospacedn", "wordwrap", "fareast_break", "linespacing_rule", "linespacing",
                     "word_lines", "scanned_chars", "paragraph_chars", "truncated")

function Load-BreakCache {
    param([string]$Path)
    $cache = @{}
    foreach ($r in (Read-Tsv $Path)) {
        $k = [int]$r.word_para
        if (-not $cache.Contains($k)) { $cache[$k] = New-Object System.Collections.Generic.List[object] }
        $cache[$k].Add($r)
    }
    return $cache
}

function Save-BreakCache {
    param($Cache)
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($k in @($Cache.Keys | Sort-Object)) { foreach ($r in $Cache[$k]) { $rows.Add($r) } }
    Save-Tsv -Path $WordBreakTsv -Header $script:BreakHdr -Rows $rows
}

function New-PropRow {
    param($Paragraph, [int]$Com, [int]$LineCount, [int]$Scan, [int]$ParaChars, $Truncated)
    $fmt = Get-PropSafe $Paragraph "Format" $null
    $range = $Paragraph.Range
    return [pscustomobject]@{
        word_para = $Com
        device_block = ""
        font_pt = Get-PropSafe $range.Font "Size" ""
        align = Get-PropSafe $fmt "Alignment" ""
        firstline_pt = Get-PropSafe $fmt "FirstLineIndent" ""
        firstline_chars = Get-PropSafe $fmt "CharacterUnitFirstLineIndent" ""
        autospacede = Get-PropSafe $fmt "AutoSpaceDE" ""
        autospacedn = Get-PropSafe $fmt "AutoSpaceDN" ""
        wordwrap = Get-PropSafe $fmt "WordWrap" ""
        fareast_break = Get-PropSafe $fmt "FarEastLineBreakControl" ""
        linespacing_rule = Get-PropSafe $fmt "LineSpacingRule" ""
        linespacing = Get-PropSafe $fmt "LineSpacing" ""
        word_lines = $LineCount
        scanned_chars = $Scan
        paragraph_chars = $ParaChars
        truncated = $Truncated
    }
}
# Which lines hold a script run: Font returns wdUndefined (non zero) when the range mixes sizes.
function Add-LineScriptProbe {
    param($Document, $LineRows)
    foreach ($cl in $LineRows) {
        $lr = $Document.Range([int]$cl.start, [int]$cl.end)
        $cl | Add-Member -NotePropertyName line_sup -NotePropertyValue (Get-PropSafe $lr.Font "Superscript" "") -Force
        $cl | Add-Member -NotePropertyName line_sub -NotePropertyValue (Get-PropSafe $lr.Font "Subscript" "") -Force
        $cl | Add-Member -NotePropertyName line_font_pt -NotePropertyValue (Get-PropSafe $lr.Font "Size" "") -Force
    }
}
function Invoke-WordBreaks {
    param($Document, [int[]]$Ordinals, [int]$MaxChars, [ref]$PropRows, [switch]$SkipCached)
    $cache = Load-BreakCache $WordBreakTsv
    $done = 0
    $sinceFlush = 0
    foreach ($com in $Ordinals) {
        if ($SkipCached -and $cache.Contains($com)) {
            $cached = $cache[$com].ToArray()
            $p2 = $Document.Paragraphs.Item($com)
            $span = [int]$cached[-1].end - [int]$cached[0].start
            $row = New-PropRow -Paragraph $p2 -Com $com -LineCount $cached.Count -Scan $span -ParaChars $span -Truncated $cached[-1].truncated
            $PropRows.Value.Add($row)
            Add-LineScriptProbe -Document $Document -LineRows $cached
            continue
        }
        $para = $Document.Paragraphs.Item($com)
        $range = $para.Range
        $paraChars = [int]$range.End - [int]$range.Start
        $scan = [Math]::Min($paraChars, $MaxChars)
        $truncated = if ($paraChars -gt $MaxChars) { 1 } else { 0 }
        $lines = New-Object System.Collections.Generic.List[object]
        if ($scan -ge 1) {
            $lineStart = 0
            $curLine = [int]$range.Characters.Item(1).Information(10)
            $paraLine = 1
            for ($k = 1; $k -le $scan; $k++) {
                $ln = [int]$range.Characters.Item($k).Information(10)
                $isLast = ($k -eq $scan)
                if (($ln -ne $curLine) -or $isLast) {
                    $endAbs = if ($ln -ne $curLine) { $k - 1 } else { $k }
                    $from = [int]$range.Start + $lineStart
                    $to = [int]$range.Start + $endAbs
                    $txt = Clean-Text ([string]$Document.Range($from, $to).Text)
                    $pg = ""
                    try { $pg = [int]$range.Characters.Item($lineStart + 1).Information(3) } catch { }
                    $szEnd = Get-PropSafe $Document.Range($to - 1, $to).Font "Size" ""
                    $supEnd = Get-PropSafe $Document.Range($to - 1, $to).Font "Superscript" ""
                    $subEnd = Get-PropSafe $Document.Range($to - 1, $to).Font "Subscript" ""
                    $szStart = Get-PropSafe $Document.Range($from, $from + 1).Font "Size" ""
                    $supStart = Get-PropSafe $Document.Range($from, $from + 1).Font "Superscript" ""
                    $subStart = Get-PropSafe $Document.Range($from, $from + 1).Font "Subscript" ""
                    $lines.Add([pscustomobject]@{
                            word_para = $com; para_line = $paraLine; line_on_page = $curLine
                            word_page = $pg; chars = ($endAbs - $lineStart)
                            chars_norm = (Get-Norm $txt).Length
                            start = $from; end = $to
                            end_char = if ($txt.Length -gt 0) { $txt.Substring($txt.Length - 1) } else { "" }
                            end_font_pt = $szEnd; end_sup = $supEnd; end_sub = $subEnd
                            start_font_pt = $szStart; start_sup = $supStart; start_sub = $subStart
                            text = $txt; truncated = $truncated
                        })
                    $paraLine++
                    if ($ln -ne $curLine) { $lineStart = $k - 1; $curLine = $ln } else { break }
                }
            }
        }
        Add-LineScriptProbe -Document $Document -LineRows $lines
        $cache[$com] = $lines
        $PropRows.Value.Add((New-PropRow -Paragraph $para -Com $com -LineCount $lines.Count -Scan $scan -ParaChars $paraChars -Truncated $truncated))
        $done++
        $sinceFlush++
        "scanned word_para=$com lines=$($lines.Count) chars=$scan"
        if ($sinceFlush -ge $ChunkSize) { Save-BreakCache $cache; $sinceFlush = 0 }
    }
    Save-BreakCache $cache
    "word_breaks scanned=$done cached=$($cache.Count) -> $WordBreakTsv"
}

# ---------- joining the two sides line by line ----------

function Get-Lcp {
    param([string]$A, [string]$B)
    $n = [Math]::Min($A.Length, $B.Length)
    for ($i = 0; $i -lt $n; $i++) { if ($A[$i] -ne $B[$i]) { return $i } }
    return $n
}

function Get-SubSafe {
    param([string]$S, [int]$From, [int]$Len)
    if ([string]::IsNullOrEmpty($S) -or $From -ge $S.Length) { return "" }
    $take = [Math]::Min($Len, $S.Length - $From)
    return $S.Substring($From, $take)
}

# Rough advance model, only used to turn "Word fitted K more glyphs" into px:
# CJK and fullwidth punctuation cost one em, ASCII costs about half an em.
function Get-EmWidth {
    param([string]$S)
    $em = 0.0
    foreach ($ch in $S.ToCharArray()) { if ([int]$ch -lt 0x2E80) { $em += 0.5 } else { $em += 1.0 } }
    return $em
}

function Test-CjkLatinMix {
    param([string]$S)
    return ($S -match '[\u4E00-\u9FFF]') -and ($S -match '[A-Za-z0-9]')
}

$script:PunctRx = '^[\p{P}\p{S}]'
$script:HangRx = '^[\u3001\u3002\uFF0C\uFF0E\uFF1B\uFF1A\uFF1F\uFF01\uFF09\u3011\u300B\u300D\u300F\u201D\u2019\uFF05\u2025\u2014\uFF5E>\)\]\u00B7]'
$script:LatinRx = '[A-Za-z0-9\-\./%]'

# One divergent line boundary -> one primary cause, most specific rule first.
# Word reports 9999999 when a range mixes sizes, so anything implausible is dropped.
function Get-ValidPt {
    param($Value, [double]$Fallback)
    $v = 0.0
    if ([double]::TryParse([string]$Value, [ref]$v)) { if ($v -gt 0 -and $v -le 1000) { return $v } }
    return $Fallback
}

function Test-InsideToken {

    param([string]$Stream, [int]$At)
    if ([string]::IsNullOrEmpty($Stream) -or $At -lt 1 -or $At -ge $Stream.Length) { return $false }
    return ($Stream.Substring($At - 1, 1) -match '[A-Za-z0-9]' -and $Stream.Substring($At, 1) -match '[A-Za-z0-9]')
}

function Get-Cause {
    param(
        [int]$Index, [bool]$SameContent, [int]$Lcp, [int]$Common, [string]$Extra, [string]$Pushed,
        [bool]$WordLonger, [string]$LineText, [bool]$ScriptPara, [bool]$InTable,
        [string]$EndSup, [string]$EndSub, [string]$EndPt, [string]$ParaPt,
        [string]$LongAll, [int]$LongEnd, [bool]$LineHasScript
    )
    if (-not $SameContent -and $Lcp -lt $Common) { return "文本内容不一致（脚注引用或域结果残留）" }
    if ((Test-InsideToken -Stream $LongAll -At $Common) -or (Test-InsideToken -Stream $LongAll -At $LongEnd) -or
        ($Extra -match '^[A-Za-z0-9][A-Za-z0-9\-\./%]{1,}$')) {
        return "长西文或数字串不可断（w:wordWrap）"
    }
    if (($Extra -and $Extra -match '^[\p{P}\p{S}]+$') -or ($Pushed -and $Pushed -match $script:HangRx)) {
        return "行尾标点悬挂与行首标点禁则（w:overflowPunct 加 w:kinsoku）"
    }
    $scriptFlag = ("$EndSup$EndSub" -match "-1") -or
                  ("$EndPt" -and "$ParaPt" -and ([double]$EndPt -ne -9999999) -and ([double]$ParaPt -ne -9999999) -and ([double]$EndPt -lt [double]$ParaPt))
    $tail = Get-SubSafe $LineText ([Math]::Max(0, $LineText.Length - 4)) 4
    $scriptish = ($Extra -match '\[\d') -or ($Pushed -match '^\[') -or ($Extra -match '^[\d\u2103]+$') -or ((($tail -match '[0-9]$') -and ($Extra -match '^[0-9]' -or $Pushed -match '^[0-9]')))
    if ($scriptFlag -or $LineHasScript -or ($ScriptPara -and $scriptish)) { return "上下标小字号 run 参与行宽计量" }
    if (-not $WordLonger -and (Test-CjkLatinMix $LineText)) { return "中西文混排留白未计入（autoSpaceDE/autoSpaceDN）" }
    if ($Index -eq 0) { return "首行缩进计量（firstLineChars）" }
    if ($InTable) { return "表格列宽" }
    if (Test-CjkLatinMix $LineText) { return "西文与数字字符宽度量差（Times New Roman advance）" }
    return "全角字宽与行尾余量取整"
}

function Build-LineDelta {
    param($Device, $MapRows, $Sel, [double]$ContentPx, $ScriptSet)
    $byBlock = @{}
    foreach ($r in $MapRows) { $byBlock[[int]$r.device_block] = $r }
    $wordLines = Load-BreakCache $WordBreakTsv
    $props = @{}
    foreach ($r in (Read-Tsv $WordPropTsv)) { $props[[int]$r.word_para] = $r }
    $rows = New-Object System.Collections.Generic.List[object]
    $paras = New-Object System.Collections.Generic.List[object]
    $skipped = New-Object System.Collections.Generic.List[object]
    foreach ($s in $Sel) {
        $blk = [int]$s.device_block
        $com = [int]$s.word_para
        if (-not $Device.Contains($blk)) { continue }
        if (-not $wordLines.Contains($com)) { continue }
        $d = $Device[$blk]
        if ($d.exportTrunc) {
            $skipped.Add([pscustomobject]@{ cohort = $s.cohort; device_block = $blk; word_para = $com
                    reason = "设备端导出行文本有 60 字上限，该行被截断成 ... ，字符序号不可比"
                    text = (Get-Prefix ([string]$s.text) 20) })
            continue
        }
        $wl = @($wordLines[$com] | Sort-Object { [int]$_.para_line })
        $wNorm = @($wl | ForEach-Object { Get-Norm ([string]$_.text) })
        $dNorm = @($d.lines | ForEach-Object { $_.norm })
        $wAll = ($wNorm -join ""); $dAll = ($dNorm -join "")
        $same = ($wAll -eq $dAll)
        $lcp = Get-Lcp $wAll $dAll
        $safeLimit = if ($same) { [Math]::Max($wAll.Length, $dAll.Length) } else { $lcp }
        $wCum = @(); $acc = 0; foreach ($x in $wNorm) { $acc += $x.Length; $wCum += $acc }
        $dCum = @(); $acc = 0; foreach ($x in $dNorm) { $acc += $x.Length; $dCum += $acc }
        $n = [Math]::Min($wCum.Count, $dCum.Count)
        $prop = $null; if ($props.ContainsKey($com)) { $prop = $props[$com] }
        $paraPt = if ($prop) { Get-ValidPt $prop.font_pt 0 } else { 0 }
        $scriptPara = [bool]$s.script_runs
        $firstDiv = -1; $aligned = 0
        $lineRows = New-Object System.Collections.Generic.List[object]
        for ($i = 0; $i -lt $n; $i++) {
            $wEnd = [int]$wCum[$i]; $dEnd = [int]$dCum[$i]
            $ok = ($wEnd -eq $dEnd) -and ($wEnd -le $safeLimit)
            if ($ok) { $aligned++ } elseif ($firstDiv -lt 0) { $firstDiv = $i + 1 }
            $common = [Math]::Min($wEnd, $dEnd)
            $wordLonger = $wEnd -gt $dEnd
            $extra = if ($wordLonger) { Get-SubSafe $wAll $common ($wEnd - $common) } else { Get-SubSafe $dAll $common ($dEnd - $common) }
            $pushed = if ($wordLonger) { Get-SubSafe $dAll $common 1 } else { Get-SubSafe $wAll $common 1 }
            $linePt = Get-ValidPt $wl[$i].end_font_pt $paraPt
            if ($linePt -le 0) { $linePt = 12 }
            $wSpaces = [int]$wl[$i].chars - $wNorm[$i].Length
            $lsSup = [string]$wl[$i].line_sup
            $lsSub = [string]$wl[$i].line_sub
            $lineHasScript = (($lsSup -ne "" -and $lsSup -ne "0") -or ($lsSub -ne "" -and $lsSub -ne "0"))
            $slack = ""
            if ($ContentPx -gt 0) { $slack = [Math]::Round($ContentPx - [double]$d.lines[$i].px, 1) }
            $cause = ""
            if (-not $ok) {
                $longAll = if ($wordLonger) { $wAll } else { $dAll }
                $longEnd = [Math]::Max($wEnd, $dEnd)
                $cause = Get-Cause -Index $i -SameContent $same -Lcp $lcp -Common $common -Extra $extra -Pushed $pushed `
                    -WordLonger $wordLonger -LineText $wNorm[$i] -ScriptPara $scriptPara -InTable $false `
                    -EndSup $wl[$i].end_sup -EndSub $wl[$i].end_sub `
                    -EndPt $wl[$i].end_font_pt -ParaPt $linePt -LongAll $longAll -LongEnd $longEnd `
                    -LineHasScript $lineHasScript
            }
            $lineRows.Add([pscustomobject]@{
                    cohort = $s.cohort; device_block = $blk; word_para = $com
                    line_index = $i + 1
                    word_line_on_page = $wl[$i].line_on_page; word_page = $wl[$i].word_page
                    device_page = $d.lines[$i].page
                    word_chars = $wNorm[$i].Length; device_chars = $dNorm[$i].Length
                    word_chars_raw = $wl[$i].chars; word_spaces = $wSpaces
                    word_cum = $wEnd; device_cum = $dEnd; cum_delta = ($wEnd - $dEnd)
                    aligned = if ($ok) { 1 } else { 0 }
                    extra_chars = $extra.Length; extra_text = $extra; pushed_char = $pushed
                    longer = if ($wEnd -eq $dEnd) { "same" } elseif ($wordLonger) { "word" } else { "device" }
                    cause = $cause
                    device_px = $d.lines[$i].px; device_slack_px = $slack
                    extra_est_px = if ($extra) { [Math]::Round((Get-EmWidth $extra) * ($linePt * 96 / 72), 1) } else { 0 }
                    word_end_char = $wl[$i].end_char; word_end_font_pt = $wl[$i].end_font_pt
                    word_end_sup = $wl[$i].end_sup; word_end_sub = $wl[$i].end_sub
                    word_head12 = Get-Prefix ([string]$wl[$i].text) 12
                    word_tail12 = if (([string]$wl[$i].text).Length -gt 12) { ([string]$wl[$i].text).Substring(([string]$wl[$i].text).Length - 12) } else { [string]$wl[$i].text }
                    device_head12 = Get-Prefix ([string]$d.lines[$i].text) 12
                    device_tail12 = if (([string]$d.lines[$i].text).Length -gt 12) { ([string]$d.lines[$i].text).Substring(([string]$d.lines[$i].text).Length - 12) } else { [string]$d.lines[$i].text }
                })
        }
        foreach ($r in $lineRows) { $rows.Add($r) }
        $wLinesAll = $wCum.Count; $dLinesAll = $dCum.Count
        $trunc = if ($wl[-1].truncated -eq "1" -or [string]$wl[-1].truncated -eq "True") { 1 } else { 0 }
        $scope = "行内"
        if ($trunc -eq 1) { $scope = "截断（超 MaxChars，末行不计入行数比较）" }
        elseif ($dLinesAll -gt $wLinesAll) { $scope = "段落多出一行（推高一行，影响分页）" }
        elseif ($dLinesAll -lt $wLinesAll) { $scope = "段落少一行（压低一行，影响分页）" }
        $paras.Add([pscustomobject]@{
                cohort = $s.cohort; device_block = $blk; word_para = $com
                word_lines = $wLinesAll; device_lines = $dLinesAll; compared_lines = $n
                aligned_lines = $aligned; divergent_lines = ($n - $aligned)
                first_div_line = $firstDiv; line_delta = ($dLinesAll - $wLinesAll)
                same_text = if ($same) { 1 } else { 0 }; lcp = $lcp
                word_total = $wAll.Length; device_total = $dAll.Length
                truncated = $trunc; scope = $scope
                script_runs = $s.script_runs
                word_pages = (($wl | ForEach-Object { $_.word_page } | Select-Object -Unique) -join ",")
                device_pages = ($d.pages -join ",")
                text = (Get-Prefix ([string]$s.text) 20)
            })
    }
    return [pscustomobject]@{ lines = $rows.ToArray(); paras = $paras.ToArray(); skipped = $skipped.ToArray() }
}


# ---------- static docx layout switches (read only, no Word needed) ----------

function Get-DocxSwitchReport {
    param([string]$Path)
    $lines = New-Object System.Collections.Generic.List[string]
    if (-not (Test-Path -LiteralPath $Path)) { return $lines }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Path).Path)
    try {
        $texts = @{}
        foreach ($name in @("word/document.xml", "word/settings.xml", "word/styles.xml")) {
            $entry = $zip.GetEntry($name)
            if ($null -ne $entry) {
                $sr = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
                $texts[$name] = $sr.ReadToEnd(); $sr.Dispose()
            }
        }
        $lines.Add("== docx 静态排版开关（出现次数，显式声明不等于全局） ==")
        $doc = if ($texts.ContainsKey("word/document.xml")) { $texts["word/document.xml"] } else { "" }
        $set = if ($texts.ContainsKey("word/settings.xml")) { $texts["word/settings.xml"] } else { "" }
        foreach ($k in @("overflowPunct", "autoSpaceDE", "autoSpaceDN", "kinsoku", "wordWrap",
                         "snapToGrid", "firstLineChars", "docGrid", "charSpacingControl", "compatibilityMode")) {
            $nd = ([regex]::Matches($doc, "<w:$k[ />]")).Count
            $ns = ([regex]::Matches($set, "<w:$k[ />]")).Count
            $lines.Add(("w:{0}`tdocument.xml={1}`tsettings.xml={2}" -f $k, $nd, $ns))
        }
        foreach ($m in ([regex]::Matches($doc, '<w:docGrid[^>]*>') | Select-Object -First 1)) { $lines.Add("docGrid: " + $m.Value) }
        $off = ([regex]::Matches($doc, '<w:overflowPunct/>')).Count
        $offOff = ([regex]::Matches($doc, '<w:overflowPunct w:val="false"/>')).Count
        $lines.Add(("overflowPunct 开启={0} 关闭={1}" -f $off, $offOff))
        $fc = @{}
        foreach ($m in ([regex]::Matches($doc, 'w:firstLineChars="([0-9]+)"'))) {
            $v = $m.Groups[1].Value
            if ($fc.ContainsKey($v)) { $fc[$v] = $fc[$v] + 1 } else { $fc[$v] = 1 }
        }
        foreach ($k in ($fc.Keys | Sort-Object)) { $lines.Add(("firstLineChars={0} x{1}" -f $k, $fc[$k])) }
    } finally { $zip.Dispose() }
    return $lines
}

# ---------- report ----------

function Format-Example {
    param($r, [int]$No)
    $wt = $r.word_tail12; $dt = $r.device_tail12
    $wh = $r.word_head12; $dh = $r.device_head12
    $dir = if ($r.longer -eq "word") { "Word 多容纳" } else { "Word Lite 多容纳" }
    $overNote = ""
    if ([double]$r.device_slack_px -lt 0) { $overNote = "（已越出正文栏）" }
    $o = New-Object System.Collections.Generic.List[string]
    $o.Add(("- 例 {0}：word_para {1} / 设备段 {2}，两侧第 {3} 行（Word 页 {4}，设备页 {5}）。" -f `
            $No, $r.word_para, $r.device_block, $r.line_index, $r.word_page, $r.device_page))
    $o.Add(("  - Word 该行 {0} 字（另有 {1} 个西文空格不计入字数），Word Lite 该行 {2} 字，累计序号差 {3}；{4} {5} 字「{6}」，按行末 {7}pt 估算占 {8} px；Word Lite 该行末尾距版心还剩 {9} px{10}。" -f `
            $r.word_chars, $r.word_spaces, $r.device_chars, [Math]::Abs([int]$r.cum_delta), $dir, $r.extra_chars, $r.extra_text, $r.word_end_font_pt, $r.extra_est_px, $r.device_slack_px, $overNote))
    $o.Add(("  - Word 行首「{0}」…行尾「{1}」" -f $wh, $wt))
    $o.Add(("  - Word Lite 行首「{0}」…行尾「{1}」" -f $dh, $dt))
    $o.Add(("  - 多出来的字符（归一化写法）「{0}」；被推到下一行的首字符「{1}」；Word 行末字符「{2}」字号 {3}pt，上标 {4}，下标 {5}。" -f `
            $r.extra_text, $r.pushed_char, $r.word_end_char, $r.word_end_font_pt, $r.word_end_sup, $r.word_end_sub))
    return $o
}

function Write-Report {
    param($ParaRows, $LineRows, $MapRows, $PageSetup, $Switches, [double]$ContentPx, [int]$MaxChars, [int]$ScanCap, $Skipped = @())
    $md = New-Object System.Collections.Generic.List[string]
    $md.Add("# 逐行断行差异表：Microsoft Word 与 Word Lite")
    $md.Add("")
    $md.Add(("生成时间 {0}；Word 断行来自 Word COM 逐字符 wdFirstCharacterLineNumber 扫描（默认每段落最多 {1} 字符，本次实际最长扫到 {2} 字符）；" -f (Get-Date -Format "yyyy-MM-dd HH:mm"), $MaxChars, $ScanCap))
    $md.Add("Word Lite 断行来自设备端 StaticLayout 逐行导出 artifacts/device/new/lines-all.tsv。")
    $md.Add("")

    $nP = @($ParaRows).Count
    $nL = @($LineRows).Count
    $aligned = @($LineRows | Where-Object { $_.aligned -eq 1 }).Count
    $div = @($LineRows | Where-Object { $_.aligned -eq 0 })
    $divP = @($ParaRows | Where-Object { $_.first_div_line -gt 0 })
    $avgFirst = if ($divP.Count) { [Math]::Round(($divP | ForEach-Object { [double]$_.first_div_line } | Measure-Object -Average).Average, 2) } else { 0 }
    $rate = if ($nL) { [Math]::Round(100.0 * $aligned / $nL, 1) } else { 0 }
    $md.Add("## 一、总结论")
    $md.Add("")
    $md.Add(("- 抽样 {0} 个段落，两侧可比较的行共 {1} 行。" -f $nP, $nL))
    $divRate = if ($nL) { [Math]::Round(100.0 * $div.Count / $nL, 1) } else { 0 }
    $md.Add(("- 逐字对齐（同一行序号上两侧累计字符完全相同）{0} 行，占 {1}%；分歧 {2} 行，占 {3}%。" -f `
            $aligned, $rate, $div.Count, $divRate))
    $md.Add(("- {0} 个段落存在至少一处分歧，首个分歧行平均落在第 {1} 行；{2} 个段落两侧行序号全对得上。" -f `
            $divP.Count, $avgFirst, ($nP - $divP.Count)))
    $md.Add(("- 段落总行数不一致（即整段被推高或压低）的段落：{0} 个；行数相同只有行内断点不同的段落：{1} 个。" -f `
            @($ParaRows | Where-Object { $_.scope -like "*影响分页*" }).Count, @($ParaRows | Where-Object { $_.scope -eq "行内" }).Count))
    $md.Add(("- 段落正文本身不一致（去掉空白和标点后仍不等）的段落：{0} 个，这类段落的字符序号只在公共前缀以内可比。" -f `
            @($ParaRows | Where-Object { $_.same_text -eq 0 }).Count))
    $spaceLines = @($LineRows | Where-Object { [int]$_.word_spaces -gt 0 })
    $spaceTotal = 0
    foreach ($r in $spaceLines) { $spaceTotal += [int]$r.word_spaces }
    $md.Add(("- 口径差异要先说清：设备端导出脚本 clean() 会丢掉所有空白，所以 Word Lite 的行文本里没有空格（Mode 1 变成 Mode1）。"))
    $md.Add(("  抽样 {0} 行中 Word 侧共 {1} 个西文空格在设备侧文本里不存在。这些空格在 Word 里占行宽，在设备侧只留下了宽度痕迹（lineWidthPx 含空格推进，lineChars 不含）。" -f $nL, $spaceTotal))
    $md.Add("  所以两边的字符数口径不同，比对一律用去掉空格后的累计字符序号；空格是否被渲染成实际宽度需要另做像素实验，本表不下结论。")
    if ($Skipped -and @($Skipped).Count) {
        $md.Add(("- 排除：{0} 个段落（{1}）不参与统计，原因是设备端导出对单行文本有 60 字上限，长行被写成 ... ，字符序号无法逐字对齐。" -f `
                @($Skipped).Count, (($Skipped | ForEach-Object { $_.device_block }) -join ", ")))
    }
    $md.Add("")

    $md.Add("## 二、段落序号映射的证据")
    $md.Add("")
    $ok = @($MapRows | Where-Object { $_.word_para -gt 0 })
    $md.Add(("- 设备段（lines-all.tsv 的 paragraph，即分页器 blockIndex）共 {0} 个，其中 {1} 个用正文前缀在 Word 侧唯一命中；匹配强制单调递增（后一段只能落在更大的 Word 段号上）。" -f $MapRows.Count, $ok.Count))
    $byOff = $ok | Group-Object com_minus_block | Sort-Object { [int]$_.Name }
    $md.Add("")
    $md.Add("| 设备段号 + N = Word 段号 | 段落数 | 设备段号范围 |")
    $md.Add("| --- | --- | --- |")
    foreach ($g in $byOff) {
        $blocks = @($g.Group | ForEach-Object { [int]$_.device_block } | Sort-Object)
        $md.Add(("| +{0} | {1} | {2} ~ {3} |" -f $g.Name, $g.Count, $blocks[0], $blocks[-1]))
    }
    $md.Add("")
    $chk = @($ok | Where-Object { $_.index_map_ok -ne "" })
    $md.Add(("- 与 artifacts/word/index_map_from_xml.tsv 的交叉校验：{0} 个带上下标的段落可用 inventory 段号独立推出预测值，其中 {1} 个与实测 Word 段号一致。" -f `
            $chk.Count, @($chk | Where-Object { $_.index_map_ok -eq "1" }).Count))
    $bad = @($chk | Where-Object { $_.index_map_ok -eq "0" }) | Select-Object -First 5
    foreach ($b in $bad) { $md.Add(("  - 不一致：设备段 {0} -> Word {1}，inventory 段 {2} 预测 {3}" -f $b.device_block, $b.word_para, $b.inv_jvm_pos, $b.inv_pred_com)) }
    $mm = @($ok | Where-Object { $_.match_method -ne "loose_full" })
    $md.Add(("- 匹配方式分布：全字符相等 {0}，其余 {1}（{2}）。" -f `
            @($ok | Where-Object { $_.match_method -eq "loose_full" }).Count, $mm.Count,
            (($mm | Group-Object match_method | ForEach-Object { "$($_.Name)x$($_.Count)" }) -join ", ")))
    $md.Add("")

    $md.Add("## 三、分段组结果")
    $md.Add("")
    $md.Add("| 段组 | 段落 | 比较行 | 对齐行 | 对齐率 | 平均首个分歧行 | 段落行数不一致 |")
    $md.Add("| --- | --- | --- | --- | --- | --- | --- |")
    foreach ($g in ($ParaRows | Group-Object cohort)) {
        $lr = @($LineRows | Where-Object { $_.cohort -eq $g.Name })
        $al = @($lr | Where-Object { $_.aligned -eq 1 }).Count
        $fp = @($g.Group | Where-Object { $_.first_div_line -gt 0 } | ForEach-Object { [double]$_.first_div_line })
        $avg = if ($fp.Count) { [Math]::Round(($fp | Measure-Object -Average).Average, 2) } else { 0 }
        $md.Add(("| {0} | {1} | {2} | {3} | {4}% | {5} | {6} |" -f $g.Name, $g.Count, $lr.Count, $al,
                [Math]::Round(100.0 * $al / [Math]::Max(1, $lr.Count), 1), $avg,
                @($g.Group | Where-Object { $_.line_delta -ne 0 }).Count))
    }
    $md.Add("")
    return , $md
}

function Add-CauseSections {
    param($md, $LineRows, $ParaRows)
    $div = @($LineRows | Where-Object { $_.aligned -eq 0 })
    $firstDiv = @($ParaRows | Where-Object { $_.first_div_line -gt 0 })
    $md.Add("## 四、分歧按成因分组")
    $md.Add("")
    $md.Add("判定顺序写死在 Get-Cause 里，一行只归一个主因：先看文本本身是否已经不同，再看断点是否落在西文串内部或整体挪动了一个西文数字串，")
    $md.Add("再看行尾标点悬挂，再看上下标证据，再看中西文混排，最后才落到字宽取整。")
    $md.Add("")
    $firstKeys = @{}
    $firstRows = @($LineRows | Where-Object { $_.aligned -eq 0 } | Group-Object device_block |
        ForEach-Object { @($_.Group | Where-Object { $_.aligned -eq 0 } | Sort-Object { [int]$_.line_index })[0] })
    foreach ($f in $firstRows) { $firstKeys[("{0}|{1}" -f $f.device_block, $f.line_index)] = 1 }
    $md.Add("### 4.1 首个分歧（段落级，最能说明成因）")
    $md.Add("")
    $d1 = @($firstRows | Where-Object { [int]$_.cum_delta -eq 1 -or [int]$_.cum_delta -eq -1 }).Count
    $md.Add(("{0} 个段落有分歧，其中 {1} 个的首个分歧只差 1 个字符；Word 多容纳 {2} 个，Word Lite 多容纳 {3} 个；" -f `
            $firstRows.Count, $d1, @($firstRows | Where-Object { $_.longer -eq "word" }).Count, @($firstRows | Where-Object { $_.longer -eq "device" }).Count))
    $md.Add("其余分歧幅度达到 3 到 9 个字，都是整串西文数字或整段文本内容不同造成的。")
    $md.Add("")
    $md.Add("| 成因 | 段落数 | 占分歧段落 |")
    $md.Add("| --- | --- | --- |")
    foreach ($g in ($firstRows | Group-Object cause | Sort-Object Count -Descending)) {
        $md.Add(("| {0} | {1} | {2}% |" -f $g.Name, $g.Count, [Math]::Round(100.0 * $g.Count / [Math]::Max(1, $firstRows.Count), 1)))
    }
    $md.Add("")
    $md.Add("### 4.2 全部分歧行（行级，含被前面分歧带偏的后续行）")
    $md.Add("")
    $md.Add("| 成因 | 分歧行数 | 占分歧行 | 涉及段落 | 其中首个分歧 |")
    $md.Add("| --- | --- | --- | --- | --- |")
    $groups = @($div | Group-Object cause | Sort-Object Count -Descending)
    foreach ($g in $groups) {
        $fdHit = 0
        foreach ($k in $firstDiv) {
            $hit = @($LineRows | Where-Object { $_.device_block -eq $k.device_block -and $_.line_index -eq [int]$k.first_div_line -and $_.cause -eq $g.Name })
            if ($hit.Count) { $fdHit++ }
        }
        $md.Add(("| {0} | {1} | {2}% | {3} | {4} |" -f $g.Name, $g.Count,
                [Math]::Round(100.0 * $g.Count / [Math]::Max(1, $div.Count), 1),
                @($g.Group | ForEach-Object { $_.device_block } | Select-Object -Unique).Count, $fdHit))
    }
    $md.Add("")
    $no = 0
    foreach ($g in $groups) {
        $md.Add("### 成因：{0}" -f $g.Name)
        $md.Add("")
        $md.Add(("分歧行 {0} 行（占全部分歧行 {1}%），涉及段落 {2} 个，其中 {3} 个段落就是在这里第一次分叉。" -f `
                $g.Count, [Math]::Round(100.0 * $g.Count / [Math]::Max(1, $div.Count), 1),
                @($g.Group | ForEach-Object { $_.device_block } | Select-Object -Unique).Count,
                (@($firstRows | Where-Object { $_.cause -eq $g.Name }).Count)))
        $md.Add("")
        $cand = @($g.Group | Group-Object device_block | ForEach-Object {
                $inSet = @($_.Group | Where-Object { $firstKeys.Contains(("{0}|{1}" -f $_.device_block, $_.line_index)) })
                if ($inSet.Count) { $inSet[0] }
                else { @($_.Group | Sort-Object { [Math]::Abs([int]$_.cum_delta) } -Descending | Select-Object -First 1)[0] }
            })
        $picks = @($cand | Sort-Object { if ($firstKeys.Contains(("{0}|{1}" -f $_.device_block, $_.line_index))) { 0 } else { 1 } },
                                         { [Math]::Abs([int]$_.cum_delta) } -Descending | Select-Object -First 3)
        foreach ($r in $picks) {
            $no++
            foreach ($l in (Format-Example -r $r -No $no)) { $md.Add($l) }
        }
        $md.Add("")
    }
    return $no
}

function Add-ScopeSections {
    param($md, $LineRows, $ParaRows, $PageSetup, $Switches, [double]$ContentPx, $Skipped = @())
    $md.Add("## 五、行内差异与分页影响")
    $md.Add("")
    $push = @($ParaRows | Where-Object { [int]$_.line_delta -ne 0 } | Sort-Object { -[int]$_.line_delta })
    $inline = @($ParaRows | Where-Object { [int]$_.line_delta -eq 0 })
    $md.Add(("- 只影响行内（两侧段落总行数相同，断点位置不同）：{0} 个段落，{1} 个分歧行。这类差异改变一行里放了哪几个字，但不改变段落高度，不会直接把后面的内容推页。" -f `
            $inline.Count, @($inline | ForEach-Object { $_.divergent_lines } | Measure-Object -Sum).Sum))
    $md.Add(("- 直接改变段落行数（进而影响分页）：{0} 个段落。" -f $push.Count))
    $md.Add("")
    $md.Add("| 设备段 | Word 段 | 段组 | Word 行数 | Word Lite 行数 | 行数差 | Word 页 | 设备页 | 首个分歧行 |")
    $md.Add("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    foreach ($r in $push) {
        $md.Add(("| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} |" -f $r.device_block, $r.word_para, $r.cohort,
                $r.word_lines, $r.device_lines, $r.line_delta, $r.word_pages, $r.device_pages, $r.first_div_line))
    }
    $md.Add("")
    if ($Skipped -and @($Skipped).Count) {
        $md.Add("设备端导出截断而排除的段落：")
        $md.Add("")
        $md.Add("| 设备段 | Word 段 | 段组 | 原因 | 正文前 20 字 |")
        $md.Add("| --- | --- | --- | --- | --- |")
        foreach ($s in $Skipped) { $md.Add(("| {0} | {1} | {2} | {3} | {4} |" -f $s.device_block, $s.word_para, $s.cohort, $s.reason, $s.text)) }
        $md.Add("")
    }
    $t = @($ParaRows | Where-Object { $_.truncated -eq 1 -or $_.truncated -eq "1" })
    if ($t.Count) { $md.Add(("扫描被 MaxChars 截断的段落 {0} 个（{1}），这些段落的行数差只作下限看待。" -f $t.Count, (($t | ForEach-Object { $_.device_block }) -join ", "))) }
    $md.Add("")
    $md.Add("## 六、两侧几何与文档开关")
    $md.Add("")
    if ($PageSetup -and $PageSetup.Count) {
        foreach ($l in $PageSetup) { $md.Add(("    " + ($l -replace "`t", "  "))) }
        $md.Add("")
    }
    $md.Add(("Word Lite 排版内容宽（96 DPI 单位）contentWidth = {0:N2} px；12pt 全角字宽 16 px，" -f $ContentPx))
    $md.Add("所以一行最多容纳 floor(contentWidth/16) 个全角字。")
    $md.Add("")
    if ($Switches -and $Switches.Count) {
        foreach ($l in $Switches) { $md.Add(("    " + ($l -replace "`t", "  "))) }
        $md.Add("")
    }
    $md.Add("## 七、口径与限制")
    $md.Add("")
    $md.Add("- 行内容比对先做归一化：去掉空格/制表/换行/零宽字符与 Word 控制符，全角 ASCII 折半角，标点保留（标点占行宽，必须计入）。")
    $md.Add("- 对齐判定用累计字符序号：第 k 行只在两侧累计序号相等时才算对齐；另有一层去掉标点后的宽松比对，用来区分断点不同和字符本身不同。")
    $md.Add("- Word 段号是 COM Paragraphs 的 1 基序号；设备段号是 lines-all.tsv 的 paragraph（分页器 blockIndex，含 4 个表格块占位）。")
    $md.Add("- superscript-inventory.txt 用的是 doc.paragraphs 位置序号，和 blockIndex 差一个表格块计数，本报告用文本把它们对齐，不用固定偏移。")
    $md.Add("- Word 行宽没有可读接口，正文两端对齐时行宽恒等于版心宽，所以差异用 Word Lite 的行末剩余 px 与多容纳字符数表示；px 为 96 DPI 文档单位。")
    $md.Add("- 表格内部 Word Lite 走行渲染，不产出 StaticLayout 行，故表格段落不在本表内；表格列宽只在 Word 侧可见。")
    $md.Add("- 两端对齐只改字间空隙不改断点，本表无法用断点证据区分它，需要另行比对行内字形间距。")
    $md.Add("- 只有段落的第一处分歧行才是容量证据（同一行谁多放一个字）；后面的分歧行多数是前面错位带偏的结果，")
    $md.Add("  所以第四节的成因占比按段落级首个分歧读更准确，行级占比只用于看影响面。")
    $md.Add("- 每行的上下标证据来自 Word 侧对该行字符区间查询 Font.Superscript 与 Font.Subscript，返回非 0 即该行含小字号 run。")
    $md.Add("- 设备端 lineFull 有 60 字上限（超出部分写成 ...），命中该上限的段落直接排除，不进统计；Word 侧超过 MaxChars 的段落会标出并只作下限看待。")
    $md.Add("- Word 侧 AutoSpaceDE 与 AutoSpaceDN 这两个属性经 COM 读回来是空值（属性存在但取不到），该项只能依据 docx 里显式声明的 w:autoSpaceDE 与 w:autoSpaceDN 判断。")
    $md.Add("")
    $md.Add("## 八、逐段明细")
    $md.Add("")
    $md.Add("| 设备段 | Word 段 | 段组 | 比较行 | 对齐行 | 首个分歧行 | Word 行 | 设备行 | 正文一致 | Word 页 | 设备页 | 上下标 |")
    $md.Add("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    foreach ($r in $ParaRows) {
        $md.Add(("| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} |" -f `
                $r.device_block, $r.word_para, $r.cohort, $r.compared_lines, $r.aligned_lines, $r.first_div_line,
                $r.word_lines, $r.device_lines, $r.same_text, $r.word_pages, $r.device_pages, $r.script_runs))
    }
    $md.Add("")
    return $md
}


# ---------- main ----------

function Import-IndexRows {
    $out = New-Object System.Collections.Generic.List[object]
    foreach ($r in (Read-Tsv $WordIndexTsv)) {
        $txt = [string]$r.text
        $out.Add([pscustomobject]@{
                word_para = [int]$r.word_para; word_para0 = [int]$r.word_para0
                first16 = $r.first16; chars = [int]$r.chars; page = $r.page
                norm = (Get-Norm $txt); loose = (Get-Norm $txt -Loose); text = $txt
            })
    }
    return $out
}

$docxPath = (Resolve-Path -LiteralPath $Docx).Path
$Device = Get-DeviceParagraphs $DeviceLines
$Inventory = Get-ScriptInventory $ScriptInventory
$ScriptSet = Get-ScriptLineSet $ScriptLines
"device paragraphs from $DeviceLines = $($Device.Count), script inventory = $($Inventory.Count)"

$wantBreaks = ($Stage -eq "all" -or $Stage -eq "breaks")
$wantReport = ($Stage -eq "all" -or $Stage -eq "report")
$cacheIndex = Test-Path -LiteralPath $WordIndexTsv
if ($ReuseIndex -and -not $cacheIndex) { throw "ReuseIndex asked but $WordIndexTsv is missing" }
$needIndex = (-not $cacheIndex) -or ($Stage -eq "index" -and -not $ReuseIndex)
$needWord = $needIndex -or $wantBreaks

$idxRows = @()
$mapRows = @()
$sel = @()
$propRows = New-Object System.Collections.Generic.List[object]
$pageSetup = @()
$word = $null
$document = $null
try {
    if ($needWord) {
        $word = New-Object -ComObject Word.Application
        $word.Visible = $false
        $word.DisplayAlerts = 0
        $document = $word.Documents.Open($docxPath, $false, $true)
        $document.Repaginate()
        "word opened paragraphs=$([int]$document.Paragraphs.Count) pages=$([int]$document.ComputeStatistics(2))"
        if ($needIndex) { $idxRows = Invoke-WordIndex $document; if ($script:IndexNote) { $script:IndexNote } }
    }
    if (@($idxRows).Count -eq 0) { $idxRows = Import-IndexRows }
    if (@($idxRows).Count -eq 0) { throw "no Word paragraph index available" }
    $mapRows = Build-ParaMap -WordRows $idxRows -Device $Device -Inventory $Inventory -IndexMapPath $IndexMap
    if ($script:MapNote) { $script:MapNote }

    if ($JvmParagraphs) {
        $picked = New-Object System.Collections.Generic.List[object]
        foreach ($b in @($JvmParagraphs -split "[,; ]+" | Where-Object { $_ })) {
            $r = @($mapRows | Where-Object { [int]$_.device_block -eq [int]$b } | Select-Object -First 1)
            if ($r.Count -and $r[0].word_para -gt 0) {
                $m = $r[0]
                $picked.Add([pscustomobject]@{ device_block = [int]$b; word_para = [int]$m.word_para; cohort = "m_forced"
                        why = "requested"; lines = $m.device_lines; pages = $m.device_pages; script_runs = $m.script_runs; text = $m.device_text16 })
            } else { "warn: device_block $b has no proven Word ordinal" }
        }
        $sel = $picked.ToArray()
    } elseif ($WordParagraphs) {
        $picked = New-Object System.Collections.Generic.List[object]
        foreach ($wp in @($WordParagraphs)) {
            foreach ($w in @($wp -split "[,; ]+" | Where-Object { $_ })) {
                $m = @($mapRows | Where-Object { [int]$_.word_para -eq [int]$w } | Select-Object -First 1)
                $blk = if ($m.Count) { [int]$m[0].device_block } else { 0 }
                $picked.Add([pscustomobject]@{ device_block = $blk; word_para = [int]$w; cohort = "w_forced"
                        why = "requested word ordinal"; lines = ""; pages = ""; script_runs = ""; text = "" })
            }
        }
        $sel = $picked.ToArray()
    } else {
        $sel = Select-Paragraphs -MapRows $mapRows -Device $Device -Everything:$All
    }
    $selHdr = @("device_block", "word_para", "cohort", "why", "lines", "pages", "script_runs", "text")
    Save-Tsv -Path $SelTsv -Header $selHdr -Rows $sel
    $cohList = @($sel | Group-Object cohort | ForEach-Object { $_.Name + "x" + $_.Count })
    $cohText = ($cohList -join " ")
    "selected paragraphs=" + @($sel).Count + " cohorts=" + $cohText

    if ($wantBreaks -and -not $document) {
        "stage=$Stage reusing cached Word scans in $WordBreakTsv"
    } elseif ($wantBreaks) {
        $ordinals = @($sel | ForEach-Object { [int]$_.word_para } | Where-Object { $_ -gt 0 } | Sort-Object -Unique)
        Invoke-WordBreaks -Document $document -Ordinals $ordinals -MaxChars $MaxChars -PropRows ([ref]$propRows) -SkipCached:$OnlyMissing
        $propsCache = @{}
        foreach ($r in (Read-Tsv $WordPropTsv)) { $propsCache[[int]$r.word_para] = $r }
        foreach ($r in $propRows) {
            $m = @($mapRows | Where-Object { [int]$_.word_para -eq [int]$r.word_para } | Select-Object -First 1)
            if ($m.Count) { $r.device_block = $m[0].device_block }
            $propsCache[[int]$r.word_para] = $r
        }
        Save-Tsv -Path $WordPropTsv -Header $script:PropHdr -Rows @($propsCache.Values | Sort-Object { [int]$_.word_para })
        $pageSetup = Get-WordPageSetup $document
    }
} finally {
    if ($document) { try { $document.Close($false) } catch { }; [void][Runtime.InteropServices.Marshal]::ReleaseComObject($document) }
    if ($word) { try { $word.Quit() } catch { }; [void][Runtime.InteropServices.Marshal]::ReleaseComObject($word) }
    Start-Sleep -Milliseconds 300
    Get-Process WINWORD -ErrorAction SilentlyContinue | Stop-Process -Force
}

if (-not $wantReport) { "stage=$Stage done"; return }

$summaryPath = $DeviceSummary
$contentPx = 566.9333
if (Test-Path -LiteralPath $summaryPath) {
    $mt = [regex]::Match((Get-Content -LiteralPath $summaryPath -Raw -Encoding UTF8), "contentWidth=([0-9\.]+)")
    if ($mt.Success) { $contentPx = [double]$mt.Groups[1].Value }
}
$pair = Build-LineDelta -Device $Device -MapRows $mapRows -Sel $sel -ContentPx $contentPx -ScriptSet $ScriptSet
$lineRows = @($pair.lines); $paraRows = @($pair.paras); $skippedRows = @($pair.skipped)
$deltaHdr = @("cohort", "device_block", "word_para", "line_index", "word_line_on_page", "word_page", "device_page",
              "word_chars", "device_chars", "word_chars_raw", "word_spaces", "word_cum", "device_cum", "cum_delta", "aligned", "longer",
              "extra_chars", "extra_text", "pushed_char", "cause", "device_px", "device_slack_px", "extra_est_px",
              "word_end_char", "word_end_font_pt", "word_end_sup", "word_end_sub",
              "word_head12", "word_tail12", "device_head12", "device_tail12")
Save-Tsv -Path $DeltaTsv -Header $deltaHdr -Rows $lineRows

$scanCap = 0
foreach ($r in (Read-Tsv $WordPropTsv)) { $v = 0; if ([int]::TryParse([string]$r.scanned_chars, [ref]$v) -and $v -gt $scanCap) { $scanCap = $v } }
$switches = Get-DocxSwitchReport $docxPath
$md = Write-Report -ParaRows $paraRows -LineRows $lineRows -MapRows $mapRows -PageSetup $pageSetup `
    -Switches $switches -ContentPx $contentPx -MaxChars $MaxChars -ScanCap $scanCap -Skipped $skippedRows
[void](Add-CauseSections -md $md -LineRows $lineRows -ParaRows $paraRows)
[void](Add-ScopeSections -md $md -LineRows $lineRows -ParaRows $paraRows -PageSetup $pageSetup -Switches $switches -ContentPx $contentPx -Skipped $skippedRows)
Write-Lines -Path $DeltaMd -Lines $md.ToArray()

$ctx = New-Object System.Collections.Generic.List[string]
foreach ($l in $pageSetup) { $ctx.Add($l) }
$ctx.Add("word_lite_content_width_px96=$contentPx")
$ctx.Add("sampled_paragraphs=$(@($paraRows).Count) compared_lines=$(@($lineRows).Count) aligned_lines=$( @($lineRows | Where-Object { $_.aligned -eq 1 }).Count)")
foreach ($l in $switches) { $ctx.Add($l) }
Write-Lines -Path $CtxTxt -Lines $ctx

$aligned = @($lineRows | Where-Object { $_.aligned -eq 1 }).Count
"DONE paragraphs=$(@($paraRows).Count) lines=$(@($lineRows).Count) aligned=$aligned rate=$([Math]::Round(100.0*$aligned/[Math]::Max(1,$lineRows.Count),1))%"
"out: $DeltaTsv"
"out: $DeltaMd"




































