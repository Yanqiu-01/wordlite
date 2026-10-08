# Word Lite - one release, end to end.
# Bumps the manifest, checks the changelog entry exists, runs the host suite, builds the APK,
# archives it with a digest, commits, tags, opens the GitHub release with the installable APK, pushes.
# Usage: pwsh tools/release-version.ps1 -Version 0.4.5 [-CommitMessage "..."] [-Proxy http://127.0.0.1:7897]
#        [-SkipTests] [-SkipBuild] [-NoPush]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$CommitMessage = "",
    [string]$Proxy = "http://127.0.0.1:7897",
    [switch]$SkipTests,
    [switch]$SkipBuild,
    [switch]$NoPush,
    [switch]$AllowEmpty,
    [switch]$AllowDirty
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if ($Version -notmatch "^[0-9]+\.[0-9]+\.[0-9]+$") { throw "version must be x.y.z" }

# --- the tree must be exactly what is committed ---
# 这个脚本最后会 git add + git commit 整个 app/tools/tests/docs，再拿 HEAD 去出包发版。
# 工作区里只要还有别人没提交的改动（排版引擎改到一半、真机还没复采），这一版就会把没量过的
# 东西一起打包、装进手机、发成 release。所以先看工作区，脏就停，除非明确 -AllowDirty。
if (-not $AllowDirty) {
    $dirty = & git status --porcelain -- app tools tests docs CHANGELOG.md README.md
    if ($LASTEXITCODE -ne 0) { throw "git status failed" }
    if (($dirty | Measure-Object).Count -gt 0) {
        Write-Host "工作区还有未提交的改动：" -ForegroundColor Yellow
        foreach ($line in $dirty) { Write-Host ("  " + $line) -ForegroundColor Yellow }
        throw "先把这些提交（或让改它的人提交）再发版；确认无误要强行发版就加 -AllowDirty"
    }
}

# --- version bump ---
$manifest = Join-Path $root "app/src/main/AndroidManifest.xml"
$text = [IO.File]::ReadAllText($manifest)
$old = [regex]::Match($text, 'android:versionName="([^"]+)"').Groups[1].Value
$oldCode = [int][regex]::Match($text, 'android:versionCode="(\d+)"').Groups[1].Value
$newCode = $oldCode + 1
if ($old -eq $Version) { $newCode = $oldCode; Write-Host ("manifest already at $Version, keeping versionCode $newCode") -ForegroundColor DarkGray }
function VersionKey([string]$v) { $p = $v.Split("."); return ([int]$p[0] * 1000000 + [int]$p[1] * 1000 + [int]$p[2]) }
if ((VersionKey $Version) -lt (VersionKey $old)) { throw "$Version is older than $old" }
$text = [regex]::Replace($text, 'android:versionCode="\d+"', ('android:versionCode="{0}"' -f $newCode))
$text = [regex]::Replace($text, 'android:versionName="[^"]+"', ('android:versionName="{0}"' -f $Version))
[IO.File]::WriteAllText($manifest, $text, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ("version {0} (code {1}) -> {2} (code {3})" -f $old, $oldCode, $Version, $newCode) -ForegroundColor Cyan

# --- changelog entry and release notes come from the same text ---
$changelogPath = Join-Path $root "CHANGELOG.md"
$changelog = [IO.File]::ReadAllText($changelogPath)
$marker = "## $Version"
$at = $changelog.IndexOf($marker)
if ($at -lt 0) { throw "CHANGELOG.md has no section for $Version; write it first" }
$rest = $changelog.Substring($at + $marker.Length)
$next = $rest.IndexOf("`n## ")
$body = if ($next -lt 0) { $rest } else { $rest.Substring(0, $next) }
$body = ($body -replace "`r`n", "`n").Trim()
if ($body.Length -lt 40) { throw "CHANGELOG section for $Version is too thin to be a release" }
$notesPath = Join-Path $env:TEMP ("wordlite-notes-" + $Version + ".md")
$apkName = "wordlite-$Version.apk"
$preamble = "Word Lite $Version (versionCode $newCode). ``$apkName`` installs directly: package com.rikkahub.wordlite, minSdk 23 / targetSdk 35, one APK for arm64 and x86_64, debug-signed with this repository's key. Digest in SHA256SUMS."
[IO.File]::WriteAllText($notesPath, ($preamble + "`r`n`r`n" + $body + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))

# --- 字库覆盖表必须和随包字库一致：字体面板那句"带了多少汉字"念的就是它 ---
$py = if (Get-Command py -ErrorAction SilentlyContinue) { "py" }
      elseif (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { $null }
if ($py) {
    & $py (Join-Path $root "tools/build-font-coverage.py") --check
    if ($LASTEXITCODE -ne 0) { throw "DocxFontCoverage.java 与随包字库对不上：py tools/build-font-coverage.py 重生成后再发" }
} else {
    Write-Warning "找不到 py/python，没核对字库覆盖表"
}

# --- gates ---
if (-not $SkipTests) { & (Join-Path $root "tools/test-host.ps1"); if ($LASTEXITCODE -ne 0) { throw "host suite failed" } }
if (-not $SkipBuild) { & (Join-Path $root "tools/build-host.ps1"); if ($LASTEXITCODE -ne 0) { throw "build failed" } }

# --- archive the installable artifact with a digest ---
$built = Join-Path $root "artifacts/apk/wordlite-debug.apk"
if (-not (Test-Path $built)) { throw "missing $built" }
$dest = Join-Path $root ("releases/" + $Version)
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$apk = Join-Path $dest $apkName
Copy-Item -Force $built $apk
$digest = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLower()
$sums = Join-Path $dest "SHA256SUMS"
[IO.File]::WriteAllText($sums, ("{0}  {1}`n" -f $digest, $apkName), (New-Object System.Text.UTF8Encoding($false)))
Write-Host ("apk {0} bytes sha256 {1}" -f (Get-Item $apk).Length, $digest) -ForegroundColor Cyan

# --- commit, tag, release, push ---
if (-not $CommitMessage) { $CommitMessage = "Release $Version" }
# Binaries live in the GitHub release, never in git history: stage sources and docs only.
& git add -- app tools tests docs CHANGELOG.md README.md docs/ROADMAP.md
if ($LASTEXITCODE -ne 0) { throw "git add failed" }
$committed = & git commit -m $CommitMessage 2>&1
if ($LASTEXITCODE -ne 0 -and (($committed -join " ") -notmatch "nothing to commit")) { throw ("git commit failed: " + ($committed -join " ")) }
if (($committed -join " ") -match "nothing to commit") {
    if (-not $AllowEmpty) { throw "nothing to commit; this version has no change in it" }
    Write-Warning "-AllowEmpty: releasing the tree exactly as committed"
}
$sha = (& git rev-parse HEAD).Trim()
# The release targets a commit that must already exist on the remote, so push first and let
# gh cut the tag there; a stale local tag of the same name only makes gh refuse.
if ($NoPush) { Write-Warning "-NoPush: the release step will fail unless $sha is already on the remote" }
else {
    & git -c ("http.proxy=" + $Proxy) -c ("https.proxy=" + $Proxy) push origin main
    if ($LASTEXITCODE -ne 0) { throw "git push failed" }
}
& git tag -d ("v" + $Version) 2>&1 | Out-Null
& gh release create ("v" + $Version) $apk $sums --target $sha --title ("Word Lite " + $Version) --notes-file $notesPath
if ($LASTEXITCODE -ne 0) { throw "gh release create failed (does v$Version already exist? use gh release upload)" }
Write-Host ("RELEASED {0} code {1} sha {2}" -f $Version, $newCode, $sha) -ForegroundColor Green
Write-Host ("https://github.com/Yanqiu-01/wordlite/releases/tag/v" + $Version)