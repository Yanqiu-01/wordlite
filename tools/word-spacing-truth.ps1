# word-spacing-truth.ps1
#
# Controlled ground truth for the three spacing rules the engine cannot see in the sample doc:
#   1. w:contextualSpacing           (118 occurrences in input-liu.docx, 0 handled by the parser)
#   2. the w:beforeLines/afterLines unit (39 paragraphs carry lines + twips side by side)
#   3. w:beforeAutospacing / w:afterAutospacing (85 w:spacing elements, all inside table cells)
#
# Why raw OOXML instead of the object model: Word exposes no ContextualSpacing property, and
# ParagraphFormat.SpaceBefore is in points only, so COM cannot even state the question.  Every
# case is therefore written as its own minimal .docx, opened read-only, and measured with
# Range.Information(wdVerticalPositionRelativeToPage) at each paragraph start.  Because every gap
# is reported against the same-face no-spacing baseline (lh_single / lh_150), the face cancels and
# what is left is the spacing rule itself.
#
#   pwsh tools/word-spacing-truth.ps1                    # build + open + measure every case
#   pwsh tools/word-spacing-truth.ps1 -Only ctx,beforeline
#   pwsh tools/word-spacing-truth.ps1 -Mode Analyze      # no Word, reads gaps.tsv
#
# Units: Word returns POINTS.  px = pt * 4 / 3.  twips = pt * 20.  A w:spacing value is twips.
param(
    [ValidateSet('Probe', 'Analyze')]
    [string]$Mode = 'Probe',
    [string]$OutDir = 'E:\download\claude\Wordlite\artifacts\word-spacing',
    [string]$Only = '',
    [int]$DefaultSz = 24
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$docDir = Join-Path $OutDir 'docs'
New-Item -ItemType Directory -Force -Path $docDir | Out-Null

function ToPx([double]$pt) { return [Math]::Round($pt * 4.0 / 3.0, 3) }

# CJK probe text, spelled from code points so this script stays ASCII on disk.
$CJK = -join (@(0x6D4B, 0x8BD5, 0x6BB5, 0x843D, 0x7532, 0x4E59, 0x4E19, 0x4E01, 0x620A, 0x5DF1) |
    ForEach-Object { [char]$_ })

$DECL = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
$NSW = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'

$CONTENT_TYPES = $DECL + (@(
'<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">',
'<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>',
'<Default Extension="xml" ContentType="application/xml"/>',
'<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>',
'<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>',
'<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>',
'</Types>') -join '')

$NSPKG = 'xmlns="http://schemas.openxmlformats.org/package/2006/relationships"'
$T_DOC = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument'
$T_STY = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles'
$T_SET = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings'
$ROOT_RELS = $DECL + ('<Relationships ' + $NSPKG + '>') +
    ('<Relationship Id="rId1" Type="' + $T_DOC + '" Target="word/document.xml"/>') + '</Relationships>'
$DOC_RELS = $DECL + ('<Relationships ' + $NSPKG + '>') +
    ('<Relationship Id="rId1" Type="' + $T_STY + '" Target="styles.xml"/>') +
    ('<Relationship Id="rId2" Type="' + $T_SET + '" Target="settings.xml"/>') + '</Relationships>'

# Normal carries no spacing of its own so that every declared value in a case is unambiguous.
# Spaced / SpacedCtx differ only by contextualSpacing, and SpacedB is a byte-for-byte clone of
# Spaced under a different styleId - that pair is what separates "same style" from "same shape".
$DEF_SZ = [string]$DefaultSz
$STYLES = $DECL + (@(
('<w:styles ' + $NSW + '>'),
'<w:docDefaults><w:rPrDefault><w:rPr>',
'<w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="SimSun" w:cs="Times New Roman"/>',
('<w:sz w:val="' + $DEF_SZ + '"/><w:szCs w:val="' + $DEF_SZ + '"/></w:rPr></w:rPrDefault>'),
'<w:pPrDefault><w:pPr><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>',
'<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>',
'<w:style w:type="paragraph" w:styleId="Spaced"><w:name w:val="Spaced"/><w:basedOn w:val="Normal"/>',
'<w:pPr><w:spacing w:before="240" w:after="0" w:line="240" w:lineRule="auto"/></w:pPr></w:style>',
'<w:style w:type="paragraph" w:styleId="SpacedB"><w:name w:val="SpacedB"/><w:basedOn w:val="Normal"/>',
'<w:pPr><w:spacing w:before="240" w:after="0" w:line="240" w:lineRule="auto"/></w:pPr></w:style>',
'<w:style w:type="paragraph" w:styleId="SpacedCtx"><w:name w:val="SpacedCtx"/><w:basedOn w:val="Normal"/>',
'<w:pPr><w:spacing w:before="240" w:after="0" w:line="240" w:lineRule="auto"/><w:contextualSpacing/></w:pPr></w:style>',
'<w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/>',
'<w:tblPr><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/>',
'<w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>',
'</w:styles>') -join '')

# Compatibility block copied from tests/samples/input-liu.docx (word/settings.xml) so the probe
# answers for the same quirk set as the real manuscript: mode 14 + FE layout.
$SETTINGS = $DECL + (@(
('<w:settings ' + $NSW + '>'),
'<w:zoom w:percent="150"/><w:defaultTabStop w:val="720"/>',
'<w:noPunctuationKerning/><w:characterSpacingControl w:val="doNotCompress"/>',
'<w:compat><w:doNotExpandShiftReturn/><w:doNotWrapTextWithPunct/><w:doNotUseEastAsianBreakRules/>',
'<w:useFELayout/>',
'<w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="14"/>',
'<w:compatSetting w:name="overrideTableStyleFontSizeAndJustification" w:uri="http://schemas.microsoft.com/office/word" w:val="1"/>',
'<w:compatSetting w:name="enableOpenTypeFeatures" w:uri="http://schemas.microsoft.com/office/word" w:val="1"/>',
'<w:compatSetting w:name="doNotFlipMirrorIndents" w:uri="http://schemas.microsoft.com/office/word" w:val="1"/></w:compat>',
'</w:settings>') -join '')

# Margins copied from the manuscript's TOC section (top 2154 / right 1701 / bottom 1701 / left 1701)
# because that is the section whose page 2 the parity gate keeps arguing about.
function Get-SectPr([bool]$grid) {
    $g = if ($grid) { '<w:docGrid w:type="lines" w:linePitch="360" w:charSpace="0"/>' }
         else { '<w:docGrid w:linePitch="360" w:charSpace="0"/>' }
    return ('<w:sectPr><w:pgSz w:w="11906" w:h="16838" w:orient="portrait"/>' +
        '<w:pgMar w:top="2154" w:right="1701" w:bottom="1701" w:left="1701" w:header="1361" w:footer="1304" w:gutter="0"/>' +
        '<w:cols w:space="720" w:num="1"></w:cols>' + $g + '</w:sectPr>')
}

function New-MinimalDocx([string]$Path, [string]$BodyXml, [bool]$Grid) {
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
    $document = $DECL + ('<w:document ' + $NSW + '><w:body>') + $BodyXml +
        ('<w:p><w:pPr></w:pPr></w:p>') + (Get-SectPr $Grid) + '</w:body></w:document>'
    $zip = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        foreach ($pair in @(
                @('[Content_Types].xml', $CONTENT_TYPES),
                @('_rels/.rels', $ROOT_RELS),
                @('word/document.xml', $document),
                @('word/_rels/document.xml.rels', $DOC_RELS),
                @('word/styles.xml', $STYLES),
                @('word/settings.xml', $SETTINGS))) {
            $entry = $zip.CreateEntry($pair[0])
            $stream = $entry.Open()
            $bytes = $utf8.GetBytes($pair[1])
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Dispose()
        }
    } finally { $zip.Dispose() }
}

function New-ParaXml([hashtable]$p) {
    $style = if ($p.ContainsKey('Style')) { '<w:pStyle w:val="' + $p.Style + '"/>' } else { '' }
    $ppr = '<w:pPr>' + $style + $(if ($p.ContainsKey('PPr')) { $p.PPr } else { '' }) + '</w:pPr>'
    $text = if ($p.ContainsKey('Text')) { $p.Text } else { $CJK }
    $rpr = if ($p.ContainsKey('Sz')) { '<w:rPr><w:sz w:val="' + $p.Sz + '"/><w:szCs w:val="' + $p.Sz + '"/></w:rPr>' } else { '' }
    return '<w:p>' + $ppr + '<w:r>' + $rpr + '<w:t xml:space="preserve">' + $text + '</w:t></w:r></w:p>'
}

$L1   = '<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/>'
$L150 = '<w:spacing w:before="0" w:after="0" w:line="360" w:lineRule="auto"/>'
$B240 = '<w:spacing w:before="240" w:after="0" w:line="240" w:lineRule="auto"/>'
$A240 = '<w:spacing w:before="0" w:after="240" w:line="240" w:lineRule="auto"/>'
$CTX  = '<w:contextualSpacing/>'
$PGPB = '<w:pageBreakBefore/>'

# SpacedB + a direct contextualSpacing is the same shape as SpacedCtx under a different styleId,
# which is what lets a case hold "same shape, different style" without touching styles.xml.
$CASES = @(
    @{ Id = 'lh_single'; Grid = $false; Note = 'no declared spacing - line-height baseline for the face'
        Paras = @(@{}, @{}, @{}, @{}) }
    @{ Id = 'lh_150'; Grid = $false; Note = 'line 360 auto, no before/after - baseline at 1.5x'
        Paras = @(@{ PPr = $L150 }, @{ PPr = $L150 }, @{ PPr = $L150 }, @{ PPr = $L150 }) }

    @{ Id = 'ctx_control'; Grid = $false; Note = 'direct before=240 on paras 2..4, no contextualSpacing'
        Paras = @(@{ PPr = $L1 }, @{ PPr = $B240 }, @{ PPr = $B240 }, @{ PPr = $B240 }) }
    @{ Id = 'ctx_direct_same'; Grid = $false; Note = 'before=240 + contextualSpacing, all Normal (same style)'
        Paras = @(@{ PPr = ($L1 + $CTX) }, @{ PPr = ($B240 + $CTX) }, @{ PPr = ($B240 + $CTX) }, @{ PPr = ($B240 + $CTX) }) }
    @{ Id = 'ctx_style_same'; Grid = $false; Note = 'style SpacedCtx on all four (before in style, ctx in style)'
        Paras = @(@{ Style = 'SpacedCtx' }, @{ Style = 'SpacedCtx' }, @{ Style = 'SpacedCtx' }, @{ Style = 'SpacedCtx' }) }
    @{ Id = 'ctx_diff_style_both'; Grid = $false; Note = 'same shape, two style ids, both flagged'
        Paras = @(@{ Style = 'SpacedCtx' }, @{ Style = 'SpacedB'; PPr = $CTX }, @{ Style = 'SpacedCtx' }, @{ Style = 'SpacedB'; PPr = $CTX }) }
    @{ Id = 'ctx_diff_style_one'; Grid = $false; Note = 'same shape, two style ids, only every other one flagged'
        Paras = @(@{ Style = 'Spaced' }, @{ Style = 'SpacedB'; PPr = $CTX }, @{ Style = 'Spaced' }, @{ Style = 'SpacedB'; PPr = $CTX }) }
    @{ Id = 'ctx_after'; Grid = $false; Note = 'after=240 on paras 1..3 with contextualSpacing everywhere - is after suppressed too?'
        Paras = @(@{ PPr = ($A240 + $CTX) }, @{ PPr = ($A240 + $CTX) }, @{ PPr = ($A240 + $CTX) }, @{ PPr = ('<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/>' + $CTX) }) }
    @{ Id = 'ctx_mixed'; Grid = $false; Note = 'para 3 flagged, paras 2 and 4 not - does the flag leak past its paragraph?'
        Paras = @(@{ PPr = $B240 }, @{ PPr = $B240 }, @{ PPr = ($B240 + $CTX) }, @{ PPr = $B240 }) }

    @{ Id = 'pagebreak_before'; Grid = $false; Note = 'pageBreakBefore + before=240 at the top of page 2, no flag'
        Paras = @(@{ PPr = $B240 }, @{ PPr = ($PGPB + $B240) }, @{ PPr = $B240 }) }
    @{ Id = 'pagebreak_before_ctx'; Grid = $false; Note = 'pageBreakBefore + before=240 + contextualSpacing at the top of page 2'
        Paras = @(@{ PPr = ($B240 + $CTX) }, @{ PPr = ($PGPB + $B240 + $CTX) }, @{ PPr = ($B240 + $CTX) }) }

    @{ Id = 'bl_only'; Grid = $false; Note = 'beforeLines=50 with no before - what is one line worth here?'
        Paras = @(@{ PPr = $L1 }, @{ PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' },
                   @{ PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' },
                   @{ PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' }) }
    @{ Id = 'bl_and_twips'; Grid = $false; Note = 'beforeLines=50 AND before=195 together, exactly as the manuscript writes it'
        Paras = @(@{ PPr = $L1 }, @{ PPr = '<w:spacing w:before="195" w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' },
                   @{ PPr = '<w:spacing w:before="195" w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' },
                   @{ PPr = '<w:spacing w:before="195" w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' }) }
    @{ Id = 'bl_twips_only'; Grid = $false; Note = 'before=195 alone - the control for bl_and_twips'
        Paras = @(@{ PPr = $L1 }, @{ PPr = '<w:spacing w:before="195" w:after="0" w:line="240" w:lineRule="auto"/>' },
                   @{ PPr = '<w:spacing w:before="195" w:after="0" w:line="240" w:lineRule="auto"/>' },
                   @{ PPr = '<w:spacing w:before="195" w:after="0" w:line="240" w:lineRule="auto"/>' }) }
    @{ Id = 'bl_line360'; Grid = $false; Note = 'beforeLines=50 at line 360 - does the unit follow the line rule?'
        Paras = @(@{ PPr = $L150 }, @{ PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="360" w:lineRule="auto"/>' },
                   @{ PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="360" w:lineRule="auto"/>' },
                   @{ PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="360" w:lineRule="auto"/>' }) }
    @{ Id = 'bl_grid'; Grid = $true; Note = 'beforeLines=50 with a lines docGrid (pitch 360) - unit = grid pitch?'
        Paras = @(@{ PPr = $L1 }, @{ PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' },
                   @{ PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' },
                   @{ PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' }) }
    @{ Id = 'bl_100_line360'; Grid = $false; Note = 'beforeLines=100 at line 360 - linearity check against bl_line360'
        Paras = @(@{ PPr = $L150 }, @{ PPr = '<w:spacing w:beforeLines="100" w:after="0" w:line="360" w:lineRule="auto"/>' },
                   @{ PPr = '<w:spacing w:beforeLines="100" w:after="0" w:line="360" w:lineRule="auto"/>' },
                   @{ PPr = '<w:spacing w:beforeLines="100" w:after="0" w:line="360" w:lineRule="auto"/>' }) }
)

# Does a paragraph that merely overflows onto page 2 keep its before spacing?  Same question as
# pagebreak_before, reached by running out of room instead of by a break.
$overflow = @(@{ PPr = $L1 })
for ($i = 0; $i -lt 44; $i++) { $overflow += @{ PPr = $B240 } }
$CASES += @{ Id = 'overflow_before'; Grid = $false; Note = '45 paragraphs with before=240 - first paragraph on page 2 keeps it?'
    Paras = $overflow }
$overflowCtx = @(@{ PPr = ($L1 + $CTX) })
for ($i = 0; $i -lt 44; $i++) { $overflowCtx += @{ PPr = ($B240 + $CTX) } }
$CASES += @{ Id = 'overflow_before_ctx'; Grid = $false; Note = 'same, but every paragraph flagged contextualSpacing'
    Paras = $overflowCtx }

# Table cases: the manuscript pairs before=100/after=100/line=300 with beforeAutospacing /
# afterAutospacing inside cells.  A sentinel paragraph after the table pins the row bottom.
function New-TableRow([string[]]$cellParas) {
    return ('<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/></w:tblPr><w:tblGrid><w:gridCol w:w="6000"/></w:tblGrid>' +
        '<w:tr><w:tc><w:tcPr><w:tcW w:w="6000" w:type="dxa"/></w:tcPr>' +
        ($cellParas -join '') + '</w:tc></w:tr></w:tbl>')
}
$SENTINEL = '<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/></w:pPr>' +
    '<w:r><w:t>SENTINEL</w:t></w:r></w:p>'
$cellPlain = @(
    '<w:p><w:pPr><w:spacing w:before="100" w:after="100" w:line="300" w:lineRule="auto"/></w:pPr><w:r><w:t>' + $CJK + '</w:t></w:r></w:p>',
    '<w:p><w:pPr><w:spacing w:before="100" w:after="100" w:line="300" w:lineRule="auto"/></w:pPr><w:r><w:t>' + $CJK + '</w:t></w:r></w:p>')
$cellAuto = @(
    '<w:p><w:pPr><w:spacing w:before="100" w:after="100" w:beforeAutospacing="1" w:afterAutospacing="1" w:line="300" w:lineRule="auto"/></w:pPr><w:r><w:t>' + $CJK + '</w:t></w:r></w:p>',
    '<w:p><w:pPr><w:spacing w:before="100" w:after="100" w:beforeAutospacing="1" w:afterAutospacing="1" w:line="300" w:lineRule="auto"/></w:pPr><w:r><w:t>' + $CJK + '</w:t></w:r></w:p>')
$cellAutoZero = @(
    '<w:p><w:pPr><w:spacing w:before="100" w:after="100" w:beforeAutospacing="0" w:afterAutospacing="0" w:line="300" w:lineRule="auto"/></w:pPr><w:r><w:t>' + $CJK + '</w:t></w:r></w:p>',
    '<w:p><w:pPr><w:spacing w:before="100" w:after="100" w:beforeAutospacing="0" w:afterAutospacing="0" w:line="300" w:lineRule="auto"/></w:pPr><w:r><w:t>' + $CJK + '</w:t></w:r></w:p>')
$CASES += @{ Id = 'tbl_plain'; Grid = $false; Note = 'two paragraphs in one cell, before=100 after=100 line=300, no autospacing attribute'
    Raw = (New-TableRow $cellPlain) + $SENTINEL }
$CASES += @{ Id = 'tbl_autospacing_on'; Grid = $false; Note = 'same cell, beforeAutospacing/afterAutospacing = 1'
    Raw = (New-TableRow $cellAuto) + $SENTINEL }
$CASES += @{ Id = 'tbl_autospacing_off'; Grid = $false; Note = 'same cell, autospacing written as 0 (the manuscript case)'
    Raw = (New-TableRow $cellAutoZero) + $SENTINEL }
$ctxCell = @(
    '<w:p><w:pPr><w:spacing w:before="240" w:after="0" w:line="240" w:lineRule="auto"/><w:contextualSpacing/></w:pPr><w:r><w:t>' + $CJK + '</w:t></w:r></w:p>',
    '<w:p><w:pPr><w:spacing w:before="240" w:after="0" w:line="240" w:lineRule="auto"/><w:contextualSpacing/></w:pPr><w:r><w:t>' + $CJK + '</w:t></w:r></w:p>')
$CASES += @{ Id = 'tbl_ctx_in_cell'; Grid = $false; Note = 'contextualSpacing between two paragraphs inside one cell'
    Raw = (New-TableRow $ctxCell) + $SENTINEL }

# The committed A4Paginator.sectionStart change assumes a section break suppresses the before
# spacing of the paragraph that opens the new section.  That assumption came from ONE reading of
# one real document, so state it as a case and let Word answer.
$sectBody = (New-ParaXml @{ PPr = $B240 }) +
    ('<w:p><w:pPr><w:spacing w:before="240" w:after="0" w:line="240" w:lineRule="auto"/>' +
        (Get-SectPr $false) + '</w:pPr><w:r><w:t>' + $CJK + '</w:t></w:r></w:p>') +
    (New-ParaXml @{ PPr = $B240 }) + (New-ParaXml @{ PPr = $B240 })
$CASES += @{ Id = 'sect_break_before'; Grid = $false; Note = 'first paragraph of a new section, before=240, no flag'
    Raw = $sectBody }
$sectBodyCtx = (New-ParaXml @{ PPr = ($B240 + $CTX) }) +
    ('<w:p><w:pPr><w:spacing w:before="240" w:after="0" w:line="240" w:lineRule="auto"/><w:contextualSpacing/>' +
        (Get-SectPr $false) + '</w:pPr><w:r><w:t>' + $CJK + '</w:t></w:r></w:p>') +
    (New-ParaXml @{ PPr = ($B240 + $CTX) }) + (New-ParaXml @{ PPr = ($B240 + $CTX) })
$CASES += @{ Id = 'sect_break_before_ctx'; Grid = $false; Note = 'same, every paragraph flagged contextualSpacing'
    Raw = $sectBodyCtx }

# Is the beforeLines unit the font size (bl_only says ~1em at 12pt) or a fixed 12pt?
$CASES += @{ Id = 'lh_single_18'; Grid = $false; Note = '18pt baseline so bl_18 can be differenced'
    Paras = @(
        @{ Sz = 36 }, @{ Sz = 36 }, @{ Sz = 36 }, @{ Sz = 36 }, @{ Sz = 36 }, @{ Sz = 36 }, @{ Sz = 36 }, @{ Sz = 36 }) }
$CASES += @{ Id = 'bl_18'; Grid = $false; Note = 'beforeLines=50 at 18pt - unit tracks the font size or not'
    Paras = @(
        @{ Sz = 36; PPr = $L1 },
        @{ Sz = 36; PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' },
        @{ Sz = 36; PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' },
        @{ Sz = 36; PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' },
        @{ Sz = 36; PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' },
        @{ Sz = 36; PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' },
        @{ Sz = 36; PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' },
        @{ Sz = 36; PPr = '<w:spacing w:beforeLines="50" w:after="0" w:line="240" w:lineRule="auto"/>' }) }

# autospacing is not table-only in Word; the manuscript turns it on 63 times inside cells, so pin
# down what the attribute is worth outside a table too before deciding how to parse it.
$CASES += @{ Id = 'autospacing_body'; Grid = $false; Note = 'before=100 after=100 with beforeAutospacing/afterAutospacing=1, in the body'
    Paras = @(
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="1" w:afterAutospacing="1" w:line="240" w:lineRule="auto"/>' },
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="1" w:afterAutospacing="1" w:line="240" w:lineRule="auto"/>' },
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="1" w:afterAutospacing="1" w:line="240" w:lineRule="auto"/>' },
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="1" w:afterAutospacing="1" w:line="240" w:lineRule="auto"/>' },
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="1" w:afterAutospacing="1" w:line="240" w:lineRule="auto"/>' },
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="1" w:afterAutospacing="1" w:line="240" w:lineRule="auto"/>' },
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="1" w:afterAutospacing="1" w:line="240" w:lineRule="auto"/>' },
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="1" w:afterAutospacing="1" w:line="240" w:lineRule="auto"/>' }) }
$CASES += @{ Id = 'autospacing_body_off'; Grid = $false; Note = 'identical body case with the same attributes written as 0'
    Paras = @(
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="0" w:afterAutospacing="0" w:line="240" w:lineRule="auto"/>' },
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="0" w:afterAutospacing="0" w:line="240" w:lineRule="auto"/>' },
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="0" w:afterAutospacing="0" w:line="240" w:lineRule="auto"/>' },
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="0" w:afterAutospacing="0" w:line="240" w:lineRule="auto"/>' },
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="0" w:afterAutospacing="0" w:line="240" w:lineRule="auto"/>' },
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="0" w:afterAutospacing="0" w:line="240" w:lineRule="auto"/>' },
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="0" w:afterAutospacing="0" w:line="240" w:lineRule="auto"/>' },
        @{ PPr = '<w:spacing w:before="100" w:after="100" w:beforeAutospacing="0" w:afterAutospacing="0" w:line="240" w:lineRule="auto"/>' }) }

# --------------------------------------------------------------------------- measurement mode

if ($Only) {
    $wanted = $Only -split ','
    $CASES = @($CASES | Where-Object { $c = $_.Id; ($wanted | Where-Object { $c -like ('*' + $_ + '*') }).Count -gt 0 })
}
$rows = New-Object System.Collections.Generic.List[string]
$TAB = [string][char]9
$rows.Add((@('case_id', 'para', 'page', 'y_pt', 'y_px', 'gap_px', 'note') -join $TAB))

if ($Mode -eq 'Probe') {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach ($case in $CASES) {
        $body = if ($case.ContainsKey('Raw')) { $case.Raw }
                else { (($case.Paras | ForEach-Object { New-ParaXml $_ }) -join '') }
        $path = Join-Path $docDir ($case.Id + '.docx')
        New-MinimalDocx -Path $path -BodyXml $body -Grid ([bool]$case.Grid)
        "built $($case.Id).docx  body=$($body.Length) chars"
    }

    $word = $null
    try {
        $word = New-Object -ComObject Word.Application
        $word.Visible = $false
        $word.DisplayAlerts = 0
        foreach ($case in $CASES) {
            $path = (Resolve-Path (Join-Path $docDir ($case.Id + '.docx'))).Path
            $document = $null
            try {
                $document = $word.Documents.Open($path, $false, $true)
                $document.Repaginate()
                $count = $document.Paragraphs.Count
                $prevY = $null
                $prevPage = $null
                for ($i = 1; $i -le $count; $i++) {
                    $prange = $document.Paragraphs.Item($i).Range
                    if ([int]$prange.End - [int]$prange.Start -lt 1) { continue }
                    $probe = $document.Range([int]$prange.Start, [int]$prange.Start + 1)
                    $y = [double]$probe.Information(6)
                    $page = [int]$probe.Information(3)
                    $style = ''
                    try { $style = [string]$document.Paragraphs.Item($i).Style.NameLocal } catch { }
                    $gap = if ($page -eq $prevPage) { [Math]::Round(($y - $prevY) * 4.0 / 3.0, 3) } else { '' }
$rows.Add((@($case.Id, $i, $page, [Math]::Round($y, 4), (ToPx $y), $gap, $case.Note) -join $TAB))
                    $prevY = $y; $prevPage = $page
                }
                "measured $($case.Id)  paragraphs=$count"
            } finally {
                if ($document) { $document.Close($false); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($document) }
            }
        }
    } finally {
        if ($word) { $word.Quit(); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($word) }
        Get-Process WINWORD -ErrorAction SilentlyContinue | Stop-Process -Force
    }
    $gapsPath = Join-Path $OutDir 'gaps.tsv'
    Set-Content -Encoding utf8NoBOM -Path $gapsPath -Value $rows
    "rows=$($rows.Count - 1) out=$gapsPath"
}

if ($Mode -eq 'Analyze') {
    $src = Join-Path $OutDir 'gaps.tsv'
    if (-not (Test-Path -LiteralPath $src)) { throw "run -Mode Probe first, $src is missing" }
    $all = Import-Csv -Delimiter "`t" -Path $src
    $base = $all | Where-Object { $_.case_id -eq 'lh_single' -and [int]$_.para -le 4 }
    $lhSingle = ($base | Where-Object { $_.gap_px -ne '' } | ForEach-Object { [double]$_.gap_px } |
        Select-Object -First 3 | Measure-Object -Average).Average
    $lh150 = (($all | Where-Object { $_.case_id -eq 'lh_150' -and $_.gap_px -ne '' } |
        ForEach-Object { [double]$_.gap_px } | Select-Object -First 3 | Measure-Object -Average).Average)
    $top = ($all | Where-Object { $_.case_id -eq 'lh_single' -and [int]$_.para -eq 1 } |
        Select-Object -First 1).y_px
    "baseline LH(SimSun 12pt, line 240 auto) = $([Math]::Round($lhSingle,3)) px"
    "baseline LH(line 360 auto)              = $([Math]::Round($lh150,3)) px"
    "text-area top (para 1 of lh_single)     = $top px"
    ''
    foreach ($case in ($all | Group-Object case_id)) {
        $g = @($case.Group | Where-Object { $_.gap_px -ne '' } | ForEach-Object { [double]$_.gap_px })
        $first = @($case.Group | Where-Object { [int]$_.para -eq 1 })
        $offset = if ($first.Count -gt 0) { [Math]::Round([double]$first[0].y_px - [double]$top, 3) } else { 'n/a' }
        $expected = if ($case.Name -like 'lh_*') { [Math]::Round($lhSingle, 2) } else { '' }
        '{0,-20} first_offset={1,8}  gaps={2}' -f $case.Name, $offset,
            (($g | ForEach-Object { [Math]::Round($_, 2) }) -join ' / ')
    }
}
