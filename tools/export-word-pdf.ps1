# One-off capture helper (writes only under artifacts/): re-export the harness's Word PDF truth.
# The pinned truth file artifacts/agent-typeset/pdf-truth/input-liu.pdf was pruned to the Recycle
# Bin with its data gone; tools/break-agreement.py reads it but cannot produce it. This drives the
# same Word 16.0 on this machine to export the same sample, so the sha256 can be checked against
# docs/layout-parity-target.md. Word is opened read-only and quit afterwards: one session, closed.
param(
    [string]$Docx = "tests/samples/input-liu.docx",
    [string]$Out  = "artifacts/agent-typeset/pdf-truth/input-liu.pdf"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root
$docxPath = (Resolve-Path -LiteralPath $Docx).Path
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Out) | Out-Null
$pdfPath = Join-Path $root $Out
if (Test-Path -LiteralPath $pdfPath) { throw "refusing to overwrite existing $Out" }
$word = $null; $doc = $null
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $doc = $word.Documents.Open($docxPath, $false, $true)
    $doc.Repaginate()
    "word paragraphs=$([int]$doc.Paragraphs.Count) pages=$([int]$doc.ComputeStatistics(2)) build=$($word.Version)"
    # wdExportFormatPDF=17, wdExportAllDocument=0, wdExportOptimizeForPrint=0
    $doc.ExportAsFixedFormat($pdfPath, 17, $false, 0, 0, 1, 0, 0, $true, $true, 0, $true, $true, $false)
} finally {
    if ($doc) { $doc.Close($false); [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($doc) }
    if ($word) { $word.Quit(); [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($word) }
}
if (-not (Test-Path -LiteralPath $pdfPath)) { throw "export produced no file" }
$h = (Get-FileHash -Algorithm SHA256 -LiteralPath $pdfPath).Hash
"bytes=$((Get-Item -LiteralPath $pdfPath).Length) sha256=$h"
