# tools/break-rules-parity.ps1 -- does the phone take the same line-break decisions as Word does?
#
# Reads the Word truth produced by tools/word-break-truth.ps1 and one phone capture of the SAME file
# (tools/capture-device.ps1 -Docx artifacts/word-break/cases.docx) and compares, per case, where each
# side ended its lines. Everything is measured in a whitespace-free offset space, because the phone
# capture drops blanks from its line lengths (DeviceCapture.clean) while Word's line lengths keep
# them: 39 and 43 are the same line of "8 mm x 8 mm" text, and only the space-free count makes that
# comparable. Break offsets are therefore counted in the source with every blank removed.
#
#   pwsh tools/break-rules-parity.ps1 -Device artifacts/agent-layout-verify/breakrules-before2/new
#   pwsh tools/break-rules-parity.ps1 -Device <dir> -Out <file.md>     # also writes a table
#
# Rules, all of them derived from the Word side of this same file (nothing here is assumed):
#   no-midword-outside-truth  a break inside a [A-Za-z0-9] run is legal only where Word itself broke
#                             the same run at the same offset (Word does that when the run is wider
#                             than the column, and when w:wordWrap val="0" asks for it).
#   no-slash-break            a break right after "/" is legal only where Word broke there too (Word
#                             breaks after "/" only because a blank follows it).
#   first-break-at-truth      for the cases listed in -MustBreakCases, the phone's FIRST break must
#                             sit on Word's first break. This is the one that catches a break
#                             opportunity the platform refuses to use ("-" between letters).
#   align (reported, not asserted) the whole break sequence equals Word's. Width-model debt still
#                             moves some of these lines, so it is printed and not failed here.
param(
    [string]$Cases = "artifacts/word-break/cases.tsv",
    [string]$Word = "artifacts/word-break/word-lines.tsv",
    [Parameter(Mandatory = $true)][string]$Device,
    [string]$Out = "",
    [string[]]$MustBreakCases = @('hyphen-edge', 'hyphen-x12', 'sep-x3', 'slash-edge', 'token-false')
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function NoSpace([string]$s) {
    if ([string]::IsNullOrEmpty($s)) { return '' }
    return (($s.ToCharArray() | Where-Object { -not [char]::IsWhiteSpace($_) }) -join '')
}
function KindOf([string]$src, [int]$o) {
    # class of the break between src[o-1] and src[o], both already space-free
    $a = $src[$o - 1]; $b = $src[$o]
    $an = [char]::IsLetterOrDigit($a) -and [int]$a -lt 0x2E80
    $bn = [char]::IsLetterOrDigit($b) -and [int]$b -lt 0x2E80
    if ($a -eq [char]0x002D) { return 'after-hyphen' }
    if ($a -eq [char]0x002F) { return 'after-slash' }
    if ($an -and $bn) { return 'mid-word' }
    if ($an -or $bn) { return 'cjk-latin' }
    return 'other'
}
function BreaksFromLengths([string]$src, $lengths) {
    $out = New-Object System.Collections.Generic.List[int]
    $at = 0
    $left = $lengths.Count
    for ($i = 0; $i -lt $lengths.Count; $i++) {
        $at += [int]$lengths[$i]
        $left--
        if ($left -gt 0 -and $at -lt $src.Length) { $out.Add($at) }
    }
    return @($out)
}

$caseText = @{}
foreach ($r in @(Import-Csv -LiteralPath $Cases -Delimiter "`t")) { $caseText[$r.case] = (NoSpace $r.text) }

$wordLen = @{}
foreach ($r in @(Import-Csv -LiteralPath $Word -Delimiter "`t")) {
    if (-not $wordLen.ContainsKey($r.case)) { $wordLen[$r.case] = New-Object System.Collections.Generic.List[int] }
    $wordLen[$r.case].Add((NoSpace $r.text).Length)
}
$devLen = @{}
$devWidth = @{}
foreach ($r in @(Import-Csv -LiteralPath (Join-Path $Device 'lines-all.tsv') -Delimiter "`t")) {
    $name = $r.paragraph
    # the capture numbers paragraphs by block index; cases.tsv order is the same order
    $key = @($caseText.Keys | Where-Object { $caseText[$_] } | Out-Null)
    if (-not $devLen.ContainsKey($name)) {
        $devLen[$name] = New-Object System.Collections.Generic.List[int]
        $devWidth[$name] = New-Object System.Collections.Generic.List[string]
    }
    $devLen[$name].Add([int]$r.lineChars)
    $devWidth[$name].Add($r.lineWidthPx)
}
$byIndex = @($caseText.Keys)   # cases.tsv order == paragraph block order 0..n-1
$ordered = @(Import-Csv -LiteralPath $Cases -Delimiter "`t" | ForEach-Object { $_.case })
$devByName = @{}
$devKeys = @($devLen.Keys | Sort-Object { [int]$_ })
for ($i = 0; $i -lt $devKeys.Count; $i++) {
    if ($i -lt $ordered.Count) { $devByName[$ordered[$i]] = $devLen[$devKeys[$i]] }
}

$rows = New-Object System.Collections.Generic.List[string]
$fail = New-Object System.Collections.Generic.List[string]
$hdr = 'case','word_breaks','device_breaks','align','mid-word','after-slash','first-break'
$rows.Add(($hdr -join "`t"))
foreach ($name in $ordered) {
    $src = $caseText[$name]
    if (-not $wordLen.ContainsKey($name)) { throw "no Word lines for case $name" }
    if (-not $devByName.ContainsKey($name)) { throw "no device lines for case $name" }
    $wb = BreaksFromLengths $src $wordLen[$name]
    $db = BreaksFromLengths $src $devByName[$name]
    $wKind = @($wb | ForEach-Object { KindOf $src $_ })
    $dKind = @($db | ForEach-Object { KindOf $src $_ })
    $align = if (@(Compare-Object @($wb) @($db) -SyncWindow 0).Count -eq 0) { 'equal' } else { 'differs' }
    # R2 / R3: every suspicious device break must exist in the Word truth at the same offset
    $badMid = @(); $badSlash = @()
    for ($i = 0; $i -lt $db.Count; $i++) {
        if ($dKind[$i] -eq 'mid-word' -and -not ($wb -contains $db[$i])) { $badMid += $db[$i] }
        if ($dKind[$i] -eq 'after-slash' -and -not ($wb -contains $db[$i])) { $badSlash += $db[$i] }
    }
    $first = '-'
    if ($MustBreakCases -contains $name) {
        if ($wb.Count -eq 0) { $first = 'word-has-no-break' }
        elseif ($db.Count -eq 0) { $first = "missing(word=$($wb[0]))" }
        elseif ($db[0] -eq $wb[0]) { $first = "ok($($db[0]))" }
        else { $first = "off(word=$($wb[0]) device=$($db[0]))" }
        if ($first.StartsWith('off') -or $first.StartsWith('missing')) {
            $fail.Add("first-break-at-truth $name expected Word first break $($wb[0]) got $($db[0])")
        }
    }
    if ($badMid.Count)  { $fail.Add("no-midword-outside-truth $name offsets=$($badMid -join ',') source_tail=$((($src.Substring([Math]::Max(0,$badMid[0]-6), [Math]::Min(14, $src.Length-[Math]::Max(0,$badMid[0]-6))))))") }
    if ($badSlash.Count) { $fail.Add("no-slash-break $name offsets=$($badSlash -join ',')") }
    $rows.Add(($name, ($wb -join ','), ($db -join ','), $align, ($badMid.Count), ($badSlash.Count), $first) -join "`t")
}
$rows.Add('')
$rows.Add(("word-side kinds observed: " + ((@($wordLen.Keys | ForEach-Object {
    $s = $caseText[$_]
    @(BreaksFromLengths $s $wordLen[$_] | ForEach-Object { KindOf $s $_ })
}) | Group-Object | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join ' ')))

$text = @($rows)
if ($Out) {
    Set-Content -Encoding utf8NoBOM -LiteralPath $Out -Value @(($hdr -join "`t"), ($rows | Select-Object -Skip 1))
}
$text | ForEach-Object { if ($_ -is [string]) { $_ } }
"cases=$($ordered.Count) align_equal=$(@(Compare-Object @() @() ).Count + 0) assertions_failed=$($fail.Count)"
foreach ($f in $fail) { "FAIL $f" }
if ($fail.Count -gt 0) { exit 1 }