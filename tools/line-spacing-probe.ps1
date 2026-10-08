# tools/line-spacing-probe.ps1 -- what line spacing does the engine actually get, per paragraph, on the phone?
#
# A declaration like <w:spacing w:line="380" w:lineRule="exact"/> can go missing in two different places:
# the parser may fail to carry it through w:pStyle/basedOn/docDefaults inheritance, or the layout may get
# it and ignore it. Reading word/document.xml cannot tell those apart, so this builds
# tools/device-probe/LineSpacingProbe.java against a source tree (default: the working tree), runs it on
# the phone under app_process with the bundled fonts, and prints for every paragraph the parsed
# (w:line, w:lineRule, snapToGrid), whether a LineHeightSpan ended up on the laid-out line, and the line
# heights the paginator billed. Compare the PARA rows with Word's own read-back of the same paragraphs
# (artifacts/agent-layout-verify/page-stack-all/page_stack.tsv, LineSpacingRule/LineSpacingPoint).
#
#   pwsh tools/line-spacing-probe.ps1 -Pages 1,2,3
#   pwsh tools/line-spacing-probe.ps1 -Tree artifacts/privtree/<snapshot>/app/src/main/java -Pages 20,21
param(
    [string]$Serial = "EAMUT20528011355",
    [string]$Tree = "app/src/main/java",
    [string]$Docx = "tests/samples/input-liu.docx",
    [string]$Pages = "1,2,3,19,20,21,22,23",
    [string]$Out = "artifacts/agent-layout-verify/LineSpacingProbe.txt"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
function RunAdb([string[]]$a) { & adb -s $Serial @a }
function RunAdbShell([string]$cmd) { & adb -s $Serial shell $cmd }

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("wlspacing-" + [Guid]::NewGuid().ToString("N"))
$dev = "/data/local/tmp/wlspacing"
New-Item -ItemType Directory -Force -Path $tmp, (Join-Path $tmp "classes"), (Join-Path $tmp "dex") | Out-Null
$android = Join-Path $root "tools/android-35.jar"

# ---------- 1. engine tree + probe -> one dex ----------
$gen = Join-Path $tmp "gen/com/rikkahub/wordlite"
New-Item -ItemType Directory -Force -Path $gen | Out-Null
Set-Content -Encoding utf8NoBOM -Path (Join-Path $gen "R.java") -Value @(
    "package com.rikkahub.wordlite;",
    "public final class R {",
    "    public static final class drawable { public static final int ic_file = 1; public static final int ic_hero = 2; }",
    "    public static final class layout { public static final int simple_spinner_dropdown_item = 3; }",
    "}")
$sources = @(Get-ChildItem -Recurse -File -Filter *.java (Join-Path $root $Tree) | ForEach-Object { $_.FullName })
$sources += @((Join-Path $root "tools/device-probe/LineSpacingProbe.java"), (Join-Path $gen "R.java"))
$listing = Join-Path $tmp "listing.txt"
Set-Content -Encoding ascii -Path $listing -Value ($sources -replace "\\", "/")
Write-Host ("== javac ({0} files from {1}) ==" -f $sources.Count, $Tree)
& javac -nowarn -encoding UTF-8 -classpath $android -d (Join-Path $tmp "classes") "@$listing"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
$jar = Join-Path $tmp "lab.jar"
Push-Location (Join-Path $tmp "classes"); try { & jar -cf $jar . } finally { Pop-Location }
& java -cp (Join-Path $root "tools/d8.jar") com.android.tools.r8.D8 --min-api 23 --lib $android --release `
    --output (Join-Path $tmp "dex") $jar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# ---------- 2. bundled fonts + the thesis ----------
Add-Type -AssemblyName System.IO.Compression
$assetRoot = Join-Path $root "app/src/main/assets"
$assetZip = Join-Path $tmp "assets.zip"
$zipStream = [System.IO.File]::Create($assetZip)
$archive = New-Object System.IO.Compression.ZipArchive($zipStream, [System.IO.Compression.ZipArchiveMode]::Create)
Get-ChildItem -Recurse -File $assetRoot | ForEach-Object {
    $rel = ($_.FullName.Substring($assetRoot.Length + 1)) -replace '\\', '/'
    $entry = $archive.CreateEntry("assets/$rel", [System.IO.Compression.CompressionLevel]::NoCompression)
    $entryStream = $entry.Open(); $input = [System.IO.File]::OpenRead($_.FullName)
    $input.CopyTo($entryStream); $input.Dispose(); $entryStream.Dispose()
}
$archive.Dispose(); $zipStream.Dispose()

RunAdbShell "mkdir -p $dev" | Out-Null
RunAdb @("push", $assetZip, "$dev/assets.zip") | Out-Null
RunAdb @("push", (Join-Path $root $Docx), "$dev/input-liu.docx") | Out-Null
RunAdb @("push", (Join-Path $tmp "dex/classes.dex"), "$dev/lab.dex") | Out-Null

$sdk = (RunAdbShell "getprop ro.build.version.sdk").Trim()
Write-Host "== run LineSpacingProbe (device sdk=$sdk, tree=$Tree, pages=$Pages)"
$log = RunAdbShell "CLASSPATH=$dev/lab.dex app_process -Xmx512m / com.rikkahub.wordlite.LineSpacingProbe $dev $dev/input-liu.docx $Pages"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Out) | Out-Null
@("sdk=$sdk", "tree=$Tree", "pages=$Pages",
  "head_sha=$((git -C $root rev-parse HEAD))",
  "layout_dirty=$(@(& git -C $root status --porcelain -- app/src/main/java).Count -gt 0)") + @($log) |
    Set-Content -Encoding utf8NoBOM -Path $Out
$log
