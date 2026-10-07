# DEAD END - kept for reference, do not budget time around it.
# This is the approach the name promises (walk Range.Lines and read each line range), but the
# installed Word 16.0 build returns Document.Lines.Count = 0 - the same empty collection that
# Range.Runs returns here. Use tools/word-line-breaks.ps1 instead: it recovers the same break
# points by scanning Characters.Item(k).Information(wdFirstCharacterLineNumber).
#
#   pwsh tools/word-line-truth.ps1                      # tests/samples/input-liu.docx
#   pwsh tools/word-line-truth.ps1 -Docx <path> -Out artifacts/word/lines.tsv
param(
    [string]$Docx = "tests/samples/input-liu.docx",
    [string]$Out = "artifacts/word/lines.tsv",
    [int]$PageConst = 3,      # wdActiveEndPageNumber
    [int]$LineConst = 10      # wdFirstCharacterLineNumber
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$docxPath = (Resolve-Path $Docx).Path
$word = $null; $document = $null
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $document = $word.Documents.Open($docxPath, $false, $true)   # ConfirmConversions=false, ReadOnly=true
    $document.Repaginate()
    $pages = $document.ComputeStatistics(2)                      # wdStatisticPages
    $lineCount = $document.Lines.Count
    $rows = New-Object System.Collections.Generic.List[string]
    $rows.Add("line_index`tword_page`tline_on_page`tstart`tend`ttext")
    $para = 1
    $paraStart = [double]::NegativeInfinity
    $paraCount = $document.Paragraphs.Count
    $paraStarts = @{}
    # Paragraph starts once up front so each line can be attributed without more COM churn.
    for ($p = 1; $p -le $paraCount; $p++) { $paraStarts[$p] = $document.Paragraphs.Item($p).Start }
    for ($i = 1; $i -le $lineCount; $i++) {
        $line = $document.Lines.Item($i)
        $range = $line.Range
        $start = [int]$range.Start; $end = [int]$range.End
        $page = [int]$range.Information($PageConst)
        $lineNo = [int]$range.Information($LineConst)
        $owner = 0
        foreach ($key in $paraStarts.Keys) { if ($paraStarts[$key] -le $start -and $key -gt $owner) { $owner = $key } }
        $text = ($range.Text -replace "[`t`r`n]", " ")
        $rows.Add(("{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}" -f ($i - 1), $page, $lineNo, $start, $end, $owner, $text))
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Out) | Out-Null
    Set-Content -Encoding utf8NoBOM -Path $Out -Value $rows
    "word_pages=$pages"
    "word_lines=$($lineCount)"
    "word_paragraphs=$paraCount"
    "rows_written=$(( $rows.Count - 1 ))"
    "out=$Out"
} finally {
    if ($document) { $document.Close($false); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($document) }
    if ($word) { $word.Quit(); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($word) }
    Get-Process WINWORD -ErrorAction SilentlyContinue | Stop-Process -Force
}