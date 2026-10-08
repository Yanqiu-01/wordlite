# tools/word-break-truth.ps1 -- which break opportunities does Word really take inside Latin text?
#
# Why: the phone renders "Cu/SB/P-Cu/SB/C" | "u 夹层结构" -- a Latin token cut in half, twice on one
# page (artifacts/shot-231/body.png, page 8). Word's own exported PDF of the same thesis has 803 text
# lines and cuts a Latin run on none of them (27 lines end at '-', 3 at '/'), so the engine is the odd
# one out. The thesis cannot answer the three questions below on its own: all 105 paragraphs that
# carry w:wordWrap carry <w:wordWrap/> (true), none carries val="0", and no paragraph there puts a
# Latin token in a position where the answer would be visible. Each question gets its own paragraph,
# written as raw OOXML and measured in Word.
#
#   pwsh tools/word-break-truth.ps1                                  # build + open Word + measure
#   pwsh tools/word-break-truth.ps1 -Mode Build                      # only write the .docx
#   pwsh tools/word-break-truth.ps1 -Mode Probe -Docx <path>         # measure an existing case file
#
# Questions, and what a yes/no costs in pixels (column = 425.2 pt = 566.93 px, body font 12 pt):
#   token-*    Is a [A-Za-z0-9] run breakable in the middle at all, and does w:wordWrap decide it?
#              20 Chinese characters fill 320 px, a 26-letter token needs about 283 px, so a
#              breakable token splits at roughly 22 letters and an unbreakable one moves down whole.
#   slash-edge      Is "/" a break opportunity?  yes -> line 1 ends "ABCDEFGHIJ/" (~427 px used)
#                                                       no  -> the 20 Chinese characters stand alone.
#   hyphen-edge     Control: "-" is a break opportunity (the thesis PDF proves it 27 times), so this
#                   case must end "ABCDEFGHIJ-". If it does not, the harness is measuring nothing.
#   slash-then-space  Are the three thesis lines that end in "/" explained by the blank after it?
#   hyphen-x12 / slash-x12 / sep-x3 / plain-60A: how far does Word fill before it takes a break, and
#                   does it ever pull a whole token down instead of filling the line?
#   comma-after-latin: the real thesis sentence, per-character x, for the gap before a fullwidth comma
#                   (defect B). Read with tools/autogap-truth.py.
#
# Geometry is the thesis body's own (pgSz 11906x16838, pgMar left/right 1701 -> text column
# 425.2 pt = 566.93 px), so a case string wraps where the thesis wraps the same string.
#
# Outputs (Word returns POINTS; our document px = pt * 4/3):
#   artifacts/word-break/cases.docx        the measured file (feed it to the phone too)
#   artifacts/word-break/word-lines.tsv    case  ordinal  line_on_page  page  chars  start  end  text
#   artifacts/word-break/word-chars.tsv    case  line  char  codepoint  x_rel_pt  (x-scan cases only)
param(
    [ValidateSet('Probe', 'Build')]
    [string]$Mode = 'Probe',
    [string]$OutDir = 'artifacts/word-break',
    [string]$Docx = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function CJK([int]$Code) { return [string][char]$Code }
$ce = CJK 0x6d4b            # 测
$shi = CJK 0x8bd5           # 试
$padL = $ce * 18            # 18 Chinese characters, 288 px at 12 pt
$padR = $shi * 40
$pfx20 = $ce * 20           # 320 px, leaves 246.9 px of the column free

$cases = New-Object System.Collections.Generic.List[object]
function Add-Case([string]$Name, [string]$Wrap, [string]$Text, [switch]$X) {
    $cases.Add([pscustomobject]@{ name = $Name; wrap = $Wrap; text = $Text; x = [bool]$X })
}

$token = 'Cu/SB/P-Cu/SB/Cu'
Add-Case 'wrap-absent' 'none' ($padL + $token + $padR)
Add-Case 'wrap-true'   '1'    ($padL + $token + $padR)
Add-Case 'wrap-false'  '0'    ($padL + $token + $padR)
# The three cases above let the token fit, so they cannot show a mid-token break. These three do not:
# 26 letters need about 283 px and only 246.9 px are left, so "breakable" and "moved down" are 246 px
# apart. w:wordWrap is varied across them because Word's UI ties that flag to exactly this behaviour.
$long26 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
Add-Case 'token-absent' 'none' ($pfx20 + $long26 + $padR)
Add-Case 'token-true'   '1'    ($pfx20 + $long26 + $padR)
Add-Case 'token-false'  '0'    ($pfx20 + $long26 + $padR)
Add-Case 'plain-60A'   'none' (($ce * 6) + ('A' * 60) + $padR)
$sep = 'ABCDEFGHIJ/KLMNOPQRST-UVWXYZ'
Add-Case 'sep-x3'      'none' (($ce * 6) + ($sep * 3) + $padR)
Add-Case 'hyphen-x12'  'none' (($ce * 10) + ('Sn-58Bi' * 12) + $padR)
Add-Case 'slash-x12'   'none' (($ce * 10) + ('Cu/SB/' * 12) + $padR)
Add-Case 'slash-edge'  'none' ($pfx20 + ('ABCDEFGHIJ' + [string][char]0x002F + 'KLMNOPQRSTUVWXYZ') + $padR)
Add-Case 'hyphen-edge' 'none' ($pfx20 + ('ABCDEFGHIJ-KLMNOPQRSTUVWXYZ') + $padR)
Add-Case 'slash-then-space' 'none' ($pfx20 + ('ABCDEFGHIJ' + [string][char]0x002F + ' KLMNOPQRSTUVWXYZ') + $padR)
$comma = ((CJK 0x4e2d) + (CJK 0x95f4) + (CJK 0x8584) + (CJK 0x7247) + (CJK 0x7684) +
          (CJK 0x5e73) + (CJK 0x9762) + (CJK 0x5c3a) + (CJK 0x5bf8) + (CJK 0x4e3a))
Add-Case 'comma-after-latin' 'none' ($comma + '8 mm x 8 mm、厚度为120 μm，图中截面显示Cu薄片两侧均存在In涂层。' +
         '连接过程中，In涂层被消耗，薄片两侧形成IMC连接层。') -X

function XmlEsc([string]$s) {
    ($s -replace '&', '&amp;' -replace '<', '&lt;' -replace '>', '&gt;')
}
function Build-Docx([string]$Path) {
    $body = New-Object System.Text.StringBuilder
    foreach ($c in $cases) {
        [void]$body.Append('<w:p><w:pPr>')
        if ($c.wrap -ne 'none') {
            [void]$body.Append('<w:wordWrap w:val="' + $c.wrap + '"/>')
        }
        [void]$body.Append('<w:snapToGrid w:val="0"/><w:jc w:val="both"/></w:pPr>')
        [void]$body.Append('<w:r><w:t xml:space="preserve">' + (XmlEsc $c.text) + '</w:t></w:r></w:p>')
    }
    $song = (CJK 0x5b8b) + (CJK 0x4f53)
    $document = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">' +
        '<w:body>' + $body.ToString() +
        '<w:sectPr><w:pgSz w:w="11906" w:h="16838" w:orient="portrait"/>' +
        '<w:pgMar w:top="1440" w:right="1701" w:bottom="1440" w:left="1701" w:header="1361" w:footer="1304" w:gutter="0"/>' +
        '<w:docGrid w:linePitch="312"/></w:sectPr></w:body></w:document>'
    $styles = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
        '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">' +
        '<w:docDefaults><w:rPrDefault><w:rPr>' +
        '<w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="' + $song +
        '" w:cs="Times New Roman"/>' +
        '<w:sz w:val="24"/><w:szCs w:val="24"/><w:lang w:val="en-US" w:eastAsia="' + $song + '"/>' +
        '</w:rPr></w:rPrDefault><w:pPrDefault/></w:docDefaults>' +
        '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>' +
        '</w:styles>'
    $contentTypes = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
        '<Default Extension="xml" ContentType="application/xml"/>' +
        '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>' +
        '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>' +
        '</Types>'
    $rels = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>' +
        '</Relationships>'
    $docRels = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>' +
        '</Relationships>'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
    $zip = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($part in @(@('[Content_Types].xml', $contentTypes), @('_rels/.rels', $rels),
                            @('word/document.xml', $document),
                            @('word/_rels/document.xml.rels', $docRels), @('word/styles.xml', $styles))) {
            $entry = $zip.CreateEntry($part[0])
            $writer = New-Object System.IO.StreamWriter($entry.Open(), (New-Object System.Text.UTF8Encoding($false)))
            $writer.Write($part[1]); $writer.Dispose()
        }
    } finally { $zip.Dispose() }
    $names = ($cases | ForEach-Object { $_.name }) -join ','
    Set-Content -Encoding utf8NoBOM -Path (Join-Path (Split-Path -Parent $Path) 'cases.tsv') `
        -Value (@('case' + "`t" + 'wordWrap' + "`t" + 'per_char_x' + "`t" + 'text') +
                ($cases | ForEach-Object { "$($_.name)`t$($_.wrap)`t$($_.x)`t$($_.text)" }))
    "built $Path  cases=$($cases.Count)  [$names]"
}

# Word has no usable Lines collection on this build (see tools/word-justify-truth.ps1), so a line is
# recovered by walking Characters and watching Information(10) (line number on the page) change.
function Probe-Docx([string]$Path) {
    $lineRows = New-Object System.Collections.Generic.List[string]
    $charRows = New-Object System.Collections.Generic.List[string]
    $lineRows.Add("case`tordinal`tline_on_page`tword_page`tchars`tstart`tend`ttext")
    $charRows.Add("case`tline`tchar`tcodepoint`tx_rel_pt")
    $word = $null; $document = $null
    try {
        $word = New-Object -ComObject Word.Application
        $word.Visible = $false
        $word.DisplayAlerts = 0
        $pathAbs = (Get-Item -LiteralPath $Path).FullName
        $document = $word.Documents.Open($pathAbs, $false, $true)
        "word opened paragraphs=$([int]$document.Paragraphs.Count) pages=$([int]$document.ComputeStatistics(2))"
        $ordinal = 0
        for ($p = 1; $p -le $cases.Count; $p++) {
            $case = $cases[$p - 1]
            $para = $document.Paragraphs.Item($p)
            $range = $para.Range
            $paraChars = [int]$range.End - [int]$range.Start
            $curLine = [int]$range.Characters.Item(1).Information(10)
            $lineStart = 0
            $lineNo = 0
            for ($k = 1; $k -le $paraChars; $k++) {
                $ln = [int]$range.Characters.Item($k).Information(10)
                $isLast = ($k -eq $paraChars)
                if (($ln -ne $curLine) -or $isLast) {
                    $endAbs = if ($ln -ne $curLine) { $k - 1 } else { $k }
                    $from = [int]$range.Start + $lineStart
                    $to = [int]$range.Start + $endAbs
                    $pg = ''
                    try { $pg = [int]$range.Characters.Item($lineStart + 1).Information(3) } catch { }
                    $txt = [string]$document.Range($from, $to).Text
                    $txt = ($txt -replace '[\u0007\u000B\u000D\u0009\u000A]', '').Trim()
                    $lineNo++
                    $lineRows.Add(($case.name, $p, $curLine, $pg, ($endAbs - $lineStart), $from, $to, $txt) -join "`t")
                    if ($case.x) {
                        for ($j = $lineStart; $j -lt $endAbs; $j++) {
                            $abs = [int]$range.Start + $j
                            $ch = [string]$document.Range($abs, $abs + 1).Text
                            if ($ch.Length -eq 0) { continue }
                            $x = [double]$document.Range($abs, $abs + 1).Information(7)
                            $charRows.Add(($case.name, $lineNo, $ch, ('U+{0:X4}' -f [int][char]$ch[0]), $x) -join "`t")
                        }
                    }
                    if ($ln -ne $curLine) { $lineStart = $k - 1; $curLine = $ln } else { break }
                }
            }
            "case=$($case.name) para=$p lines=$lineNo chars=$paraChars"
        }
    } finally {
        if ($document) { $document.Close($false); [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($document) }
        if ($word) { $word.Quit(); [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($word) }
    }
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'word-lines.tsv') -Value @($lineRows)
    Set-Content -Encoding utf8NoBOM -Path (Join-Path $OutDir 'word-chars.tsv') -Value @($charRows)
    "wrote $OutDir/word-lines.tsv lines=$($lineRows.Count - 1)  word-chars.tsv rows=$($charRows.Count - 1)"
}

if (-not $Docx) { $Docx = Join-Path $OutDir 'cases.docx' }
if ($Mode -eq 'Build') { Build-Docx $Docx; return }
if (-not (Test-Path -LiteralPath $Docx)) { Build-Docx $Docx }
Probe-Docx $Docx