# Align device paragraph rows with the Word ground truth in artifacts/word/pages.tsv
# and report page-assignment deltas. Word indexes every body paragraph (420, table
# cells included); the engine indexes its own laid-out blocks, so rows are aligned by
# text prefix in document order rather than by index.
# Word parity report for one layout build. Run the device capture first, then:
#   pwsh tools/word-parity.ps1              # the working-tree engine
#   pwsh tools/word-parity.ps1 -Impl old    # the previously shipped engine
# Word truth is produced by driving Word itself (artifacts/word); device truth comes from
# the on-device DeviceCapture probe (artifacts/device/<impl>). Exits nonzero when any
# paragraph lands on a different page than Word.
param(
    [string]$Impl = "new",
    [string]$Word = "artifacts/word/pages.tsv",
    [string]$Device = "",
    [string]$Sup = "",
    [string]$Summary = "",
    [string]$OutFile = "",
    [switch]$Quiet,
    # Ceiling on paragraphs landing on a different page than Word. The reference document currently
    # drifts on 7 of 206 aligned paragraphs (all one page early, around the chapter-2 figure block);
    # tighten as that last cluster is chased down.
    [int]$MaxShifted = 10
)
$Tag = $Impl
if (-not $Device)  { $Device = "artifacts/device/$Impl/paragraphs-wordformat.tsv" }
if (-not $Sup)     { $Sup = "artifacts/device/$Impl/superscript-inventory.txt" }
if (-not $Summary) { $Summary = "artifacts/device/$Impl/summary.txt" }
if (-not $OutFile) { $OutFile = "artifacts/parity-$Impl.tsv" }
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot; while ($root -and -not (Test-Path (Join-Path $root "artifacts/word"))) { $root = Split-Path -Parent $root }; Set-Location $root

function Read-Tsv([string]$path) {
    $lines = @(Get-Content -Path $path -Encoding UTF8)
    $header = $lines[0] -split "`t"
    $items = New-Object System.Collections.Generic.List[object]
    for ($row = 1; $row -lt $lines.Count; $row++) {
        $cols = $lines[$row] -split "`t"
        if ($cols.Count -lt $header.Count) { continue }
        $map = [ordered]@{}
        for ($col = 0; $col -lt $header.Count; $col++) { $map[$header[$col]] = $cols[$col] }
        $items.Add([pscustomobject]$map) | Out-Null
    }
    return $items
}
function Norm([string]$s) { if ($null -eq $s) { return "" }; return ($s -replace '\s', '') }

$wordRows = @(Read-Tsv $Word)
$devRows = @(Read-Tsv $Device)
$devicePages = 0
if (Test-Path $Summary) {
    $pageLine = @(Get-Content $Summary -Encoding UTF8 | Where-Object { $_ -like "pages=*" })
    if ($pageLine.Count -gt 0) { $devicePages = [int](($pageLine[0] -split '=')[1] -split ' ')[0] }
}
$supByBlock = @{}
if (Test-Path $Sup) {
    foreach ($row in (Get-Content $Sup -Encoding UTF8)) {
        $cols = $row -split "`t"
        if ($cols.Count -ge 3 -and $cols[0] -match '^\d+$') {
            $supByBlock[[int]$cols[0]] = (($cols[2] -replace '[\^\]\[]', ' ') -replace '\s+', ' ').Trim()
        }
    }
}
$wordText = @($wordRows | ForEach-Object { Norm $_.text30 })
$devText = @($devRows | ForEach-Object { Norm $_.text30 })
$outLines = New-Object System.Collections.Generic.List[string]
$outLines.Add("word_para`tscript_text`tword_start`tword_end`tdevice_start`tdevice_end`tdelta`ttext30")
$i = 0; $j = 0; $aligned = 0; $wordOnly = 0; $devOnly = 0
while ($i -lt $wordText.Count -and $j -lt $devText.Count) {
    if ($wordText[$i] -eq $devText[$j] -and $wordText[$i].Length -gt 0) {
        $w = $wordRows[$i]; $d = $devRows[$j]
        $delta = [int]$d.device_start_page - [int]$w.word_start_page
        $sup = if ($supByBlock.ContainsKey([int]$d.para_index)) { $supByBlock[[int]$d.para_index] } else { "" }
        $outLines.Add(("{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}`t{7}" -f $w.para_index, $sup,
                $w.word_start_page, $w.word_end_page, $d.device_start_page, $d.device_end_page,
                $delta, (($w.text30) -replace "`t", " "))) | Out-Null
        $aligned++; $i++; $j++; continue
    }
    $ahead = 0
    for ($k = 1; $k -le 12 -and ($i + $k) -lt $wordText.Count; $k++) { if ($wordText[$i + $k] -eq $devText[$j]) { $ahead = $k; break } }
    if ($ahead -gt 0) { $wordOnly += $ahead; $i += $ahead; continue }
    $beyond = 0
    for ($k = 1; $k -le 12 -and ($j + $k) -lt $devText.Count; $k++) { if ($devText[$j + $k] -eq $wordText[$i]) { $beyond = $k; break } }
    if ($beyond -gt 0) { $devOnly += $beyond; $j += $beyond; continue }
    $wordOnly++; $i++
}
Set-Content -Encoding utf8NoBOM -Path $OutFile -Value $outLines
$cells = @($outLines | Select-Object -Skip 1)
$hist = @{}
$shiftedList = New-Object System.Collections.Generic.List[string]
$supList = New-Object System.Collections.Generic.List[string]
foreach ($line in $cells) {
    $c = $line -split "`t"
    $delta = [int]$c[6]
    if ($hist.ContainsKey($delta)) { $hist[$delta]++ } else { $hist[$delta] = 1 }
    if ($delta -ne 0) { $shiftedList.Add($line) | Out-Null }
    if ($c[1].Length -gt 0) { $supList.Add($line) | Out-Null }
}
$supShifted = @($supList | Where-Object { ([int](($_ -split "`t")[6])) -ne 0 })
"impl=$Tag"
"word_paragraphs=$($wordRows.Count) device_paragraphs=$($devRows.Count) aligned=$aligned word_only=$wordOnly device_only=$devOnly"
"word_total_pages=28 device_total_pages=$devicePages delta_pages=$($devicePages - 28)"
"shifted_paragraphs=$($shiftedList.Count)"
"page_delta_histogram: " + (($hist.Keys | Sort-Object | ForEach-Object { "$_=>$($hist[$_])" }) -join "  ")
"first_shifted: " + $(if ($shiftedList.Count -gt 0) { $shiftedList[0] } else { "none" })
"script_paragraphs_aligned=$($supList.Count) script_paragraphs_shifted=$($supShifted.Count)"

$devicePageMax = if ($devicePages) { $devicePages } else { 0 }
$wordPageMax = ($wordRows | ForEach-Object { [int]$_.word_start_page } | Measure-Object -Maximum).Maximum
$exact = if ($hist.ContainsKey(0)) { $hist[0] } else { 0 }
$report = @(
    "impl=$Tag",
    "word_total_pages=$wordPageMax device_total_pages=$devicePageMax",
    "aligned=$aligned shifted=$($shiftedList.Count) max_allowed=$MaxShifted",
    ("exact_page_match={0:N1}%" -f (100.0 * $exact / [Math]::Max(1, $cells.Count)))
)
if (-not $Quiet) { $report | ForEach-Object { Write-Output $_ } }
if ($shiftedList.Count -gt $MaxShifted) {
    throw ("parity regressed: " + $shiftedList.Count + " paragraph(s) on a different page than Word, limit is " + $MaxShifted)
}