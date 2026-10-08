# tools/tree-snapshot.ps1 -- a private, compilable source tree for one measurement.
#
# Why: several people share this work tree, and an unfinished .java in app/src/main/java breaks
# javac for everyone (it happened: a half-written RecordImport.java stopped every build and every
# phone capture). It also means the code can change under a measurement that is already running.
# This builds <Out> as "commit <Base> plus exactly the files you name", so a build sees committed
# code and your own change and nothing else, and the manifest records which commit it was.
#
#   pwsh tools/tree-snapshot.ps1 -Out artifacts/privtree/work -Overlay app/src/main/java/com/rikkahub/wordlite/DocxTextLayout.java
#   pwsh tools/tree-snapshot.ps1 -Out ... -Include app/src/main/java,tests -Overlay a.java,tests/Foo.java
#
# Then measure against it (both tools take a tree path):
#   pwsh tools/capture-device.ps1 -Tree <Out>/app/src/main/java -Impls new -OutDir <dir>
#   pwsh tools/run-suites-private.ps1 -Tree <Out> -OutDir <dir> -Suite Regression,...
#
# <Out>/manifest.tsv: head_sha, then every copied file with its sha256 and state=base|overlay.
# Quote head_sha and the overlay sha256 next to any number taken from the snapshot -- a sample whose
# engine fingerprint does not match the commit it claims is void (docs/layout-parity-target.md).
param(
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$Base = 'HEAD',
    [string]$Include = 'app/src/main/java',
    [string]$Overlay = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
# ";" separates the lists: pwsh -File binds "-Include a,b" as one string, so a comma would silently
# turn into a single pathspec that git then fails to find.
$inc = @($Include.Split(';') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$overlayList = @($Overlay.Split(';') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($inc.Count -eq 0) { throw '-Include is empty' }
if (Test-Path -LiteralPath $Out) {
    # a run that died mid-way leaves only its own archive behind; anything else means the name is taken
    $stray = @(Get-ChildItem -Force -LiteralPath $Out)
    if ($stray.Count -eq 1 -and $stray[0].Name -eq '_base.tar') { Remove-Item -LiteralPath $stray[0].FullName -Force }
    else { throw "refusing to overwrite an existing snapshot: $Out (pick a fresh -Out)" }
}
$head = (& git -C $root rev-parse $Base).Trim()
if ($LASTEXITCODE -ne 0 -or -not $head) { throw "cannot resolve $Base" }
New-Item -ItemType Directory -Force -Path $Out | Out-Null
# tar reads "E:\dir\f.tar" as "host:path", so the archive is extracted from inside the target
# directory with a relative name.
& git -C $root archive --format=tar -o (Join-Path $Out '_base.tar') $Base $inc
if ($LASTEXITCODE -ne 0) { throw "git archive $Base failed" }
Push-Location $Out
try { tar -x -f '_base.tar' } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw "tar -x failed" }
Remove-Item -LiteralPath (Join-Path $root (Join-Path $Out '_base.tar')) -Force
$rows = New-Object System.Collections.Generic.List[string]
$rows.Add("head_sha`t$head`t-")
$rows.Add("file`tsha256`tstate")
$baseFiles = @(Get-ChildItem -Recurse -File -Filter *.java -LiteralPath $Out |
    ForEach-Object { ($_.FullName.Substring((Resolve-Path -LiteralPath $Out).Path.Length + 1)).Replace('\', '/') })
$overlaid = New-Object System.Collections.Generic.HashSet[string]
foreach ($rel in $overlayList) {
    $src = Join-Path $root $rel
    if (-not (Test-Path -LiteralPath $src)) { throw "overlay missing: $rel" }
    $dst = Join-Path $Out $rel
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
    Copy-Item -LiteralPath $src -Destination $dst -Force
    [void]$overlaid.Add($rel.Replace('\', '/'))
}
foreach ($rel in ($baseFiles + @(@($overlayList | ForEach-Object { $_.Replace('\', '/') }) |
        Where-Object { -not $baseFiles.Contains($_) }) | Sort-Object)) {
    $sha = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $Out $rel)).Hash
    $state = if ($overlaid.Contains($rel)) { 'overlay' } else { 'base' }
    $rows.Add("$rel`t$sha`t$state")
}
Set-Content -Encoding utf8NoBOM -LiteralPath (Join-Path $Out 'manifest.tsv') -Value @($rows)
"snapshot out=$Out base=$Base($head) java=$($baseFiles.Count) overlay=$($overlayList.Count) manifest=$Out/manifest.tsv"
foreach ($r in @($rows | Where-Object { $_.EndsWith("`toverlay") })) { "  overlay $($r.Split("`t")[0]) sha=$($r.Split("`t")[1])" }