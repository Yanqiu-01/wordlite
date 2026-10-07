# Where Word really breaks a paragraph into lines.
# This Word build reports Document.Lines.Count = 0 (like Range.Runs), so the breaks are
# recovered by walking characters and watching wdFirstCharacterLineNumber change. Costly, so
# point it at the paragraphs you actually care about.
#
#   pwsh tools/word-line-breaks.ps1 -WordParagraphs 69,70,75
param(
    [string]$Docx = "tests/samples/input-liu.docx",
    [string]$WordParagraphs = "",
    [string]$Out = "artifacts/word/line-breaks.tsv",
    [int]$MaxChars = 400
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$docxPath = (Resolve-Path $Docx).Path
if (-not $WordParagraphs) { throw "pass -WordParagraphs 69,70,75 (1-based Word paragraph ordinals)" }
$wanted = @($WordParagraphs -split '[,; ]+' | Where-Object { $_ } | ForEach-Object { [int]$_ })
$word = $null; $document = $null
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $document = $word.Documents.Open($docxPath, $false, $true)
    $document.Repaginate()
    $rows = New-Object System.Collections.Generic.List[string]
    $rows.Add("word_para`tline_on_page`tword_page`tchars`tstart`tend`ttext")
    foreach ($ordinal in $wanted) {
        $paragraph = $document.Paragraphs.Item($ordinal)
        $range = $paragraph.Range
        $length = [int]$range.End - [int]$range.Start
        if ($length -gt $MaxChars) { $length = $MaxChars }
        $lineStart = 0
        $currentLine = [int]$range.Characters.Item(1).Information(10)
        for ($k = 1; $k -le $length; $k++) {
            $character = $range.Characters.Item($k)
            $line = [int]$character.Information(10)
            $last = ($k -eq $length)
            if ($line -ne $currentLine -or $last) {
                # $endAbs is the exclusive end of the finished line, 0-based inside the paragraph.
                $endAbs = if ($line -ne $currentLine) { $k - 1 } else { $k }
                $taken = $endAbs - $lineStart
                $page = [int]$range.Characters.Item($lineStart + 1).Information(3)
                $from = [int]$range.Start + $lineStart
                $to = [int]$range.Start + $endAbs
                $text = ($document.Range($from, $to).Text -replace "[`t`r`n]", " ")
                $rows.Add(("{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}" -f $ordinal, $currentLine, $page, $taken,
                    $from, $to, $text))
                if ($line -ne $currentLine) { $lineStart = $k - 1; $currentLine = $line }
                if ($last) { break }
            }
        }
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Out) | Out-Null
    Set-Content -Encoding utf8NoBOM -Path $Out -Value $rows
    "paragraphs=$($wanted.Count) lines_written=$($rows.Count - 1)"
    "out=$Out"
} finally {
    if ($document) { $document.Close($false); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($document) }
    if ($word) { $word.Quit(); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($word) }
    Get-Process WINWORD -ErrorAction SilentlyContinue | Stop-Process -Force
}