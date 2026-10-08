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
    [switch]$AllowDirty,
    [switch]$AllowNewKey,
    [switch]$Isolated
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if ($Version -notmatch "^[0-9]+\.[0-9]+\.[0-9]+$") { throw "version must be x.y.z" }

# --- -Isolated：在 main 的干净副本里发这一版 ---
# 2.5.0 那一次是手工做的：主工作区里还有别人的改动没提交，只能
# `git worktree add --detach` 一棵 main 的副本，再把两样被 .gitignore 挡住的东西搬过去——
# 工具链 artifacts/host-tools（没有它连 javac 都找不到）和 tools/debug.keystore
# （没有它 build-host 会悄悄新生成一把钥匙，装过旧版的人就只能卸载重装，自建库跟着没）。
# 每一步都记在 docs 里靠人执行，漏一步就是一次发坏。现在一条命令做完：
# 造副本 → 接工具链 → 拿钥匙 → 在副本里把整条流程重跑一遍 → 把 APK 与校验和搬回来 → 拆副本。
if ($Isolated) {
    $primary = $root
    $worktree = Join-Path (Split-Path -Parent $root) ("wl-rel-" + $Version)
    if (Test-Path $worktree) { throw ("发版副本已存在，先确认它不是没收尾的发版：" + $worktree) }
    $toolsJunction = Join-Path $primary "artifacts/host-tools"
    $keystore = Join-Path $primary "tools/debug.keystore"
    if (-not (Test-Path $keystore)) { throw ("找不到签名钥匙，拒绝发版（这一版会换钥匙）：" + $keystore) }
    Write-Host ("== 发版副本 " + $worktree + "（main 的干净树）==") -ForegroundColor Cyan
    & git worktree add --detach $worktree main
    if ($LASTEXITCODE -ne 0) { throw "git worktree add 失败" }
    try {
        New-Item -ItemType Directory -Force -Path (Join-Path $worktree "artifacts") | Out-Null
        if (Test-Path $toolsJunction) {
            & cmd /c mklink /J (Join-Path $worktree "artifacts/host-tools") $toolsJunction | Out-Null
        } else { Write-Warning ("没有 " + $toolsJunction + "，副本里会自己去下工具链（慢）") }
        Copy-Item -Force $keystore (Join-Path $worktree "tools/debug.keystore")
        $inner = @(("-NoProfile"), ("-File"), (Join-Path $worktree "tools/release-version.ps1"),
                   ("-Version"), $Version)
        if ($CommitMessage) { $inner += @("-CommitMessage", $CommitMessage) }
        if ($Proxy) { $inner += @("-Proxy", $Proxy) }
        if ($SkipTests) { $inner += "-SkipTests" }
        if ($SkipBuild) { $inner += "-SkipBuild" }
        if ($NoPush) { $inner += "-NoPush" }
        if ($AllowEmpty) { $inner += "-AllowEmpty" }
        if ($AllowNewKey) { $inner += "-AllowNewKey" }
        & pwsh @inner
        $innerExit = $LASTEXITCODE
    } finally {
        & cmd /c rmdir (Join-Path $worktree "artifacts/host-tools") 2>&1 | Out-Null
        & git worktree remove --force $worktree 2>&1 | Out-Null
        if (Test-Path $worktree) { Write-Warning ("副本没拆掉，手工确认后再删：" + $worktree) }
    }
    if ($innerExit -ne 0) { throw ("副本里的发版没走完（退出码 " + $innerExit + "）；上面是它自己的输出") }
    $built = Join-Path $worktree ("releases/" + $Version)
    if (Test-Path $built) {
        $archive = Join-Path $primary ("releases/" + $Version)
        New-Item -ItemType Directory -Force -Path $archive | Out-Null
        Copy-Item -Force (Join-Path $built "*") $archive
        Write-Host ("APK 已归档回主工作区：" + $archive) -ForegroundColor Green
    }
    exit 0
}

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

# --- 签名必须和上一版是同一把钥匙 ---
# 换钥匙的后果不是"不好看"：装了旧版的人更新会 INSTALL_FAILED_UPDATE_INCOMPATIBLE，
# 只能卸载重装，自建库跟着一起没。2.4.0 就踩过：发版从一个干净检出出包，而 tools/debug.keystore
# 是被 git 忽略的，构建脚本发现它不在就当场生成了一张新的，包签得漂漂亮亮却装不上老版本。
function Get-ApkCert([string]$path) {
    $lines = & keytool -printcert -jarfile $path 2>&1
    $hit = $lines | Select-String -Pattern "SHA256:\s*([0-9A-Fa-f:]+)" | Select-Object -First 1
    if (-not $hit) { throw ("读不出 " + $path + " 的签名证书（keytool 没给 SHA256）") }
    return ($hit.Matches[0].Groups[1].Value -replace ":", "").ToLower()
}
$builtCert = Get-ApkCert $built
$expected = ""
$certFile = Join-Path $root "tools/signing-cert.txt"
if (Test-Path -LiteralPath $certFile) {
    $line = Select-String -LiteralPath $certFile -Pattern "certificate-sha256:\s*([0-9a-fA-F]+)" | Select-Object -First 1
    if ($line) { $expected = $line.Matches[0].Groups[1].Value.ToLower() }
}
$expectedFrom = "tools/signing-cert.txt"
if (-not $expected) {
    # 干净检出里还没有这个文件时退一步：拿 releases/ 里最新的那个包当对照
    $prior = @(Get-ChildItem (Join-Path $root "releases") -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne $Version } | Sort-Object { VersionKey $_.Name } -Descending |
        ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Filter *.apk -ErrorAction SilentlyContinue } |
        Select-Object -First 1)
    if ($prior.Count -gt 0) { $expected = Get-ApkCert $prior[0].FullName; $expectedFrom = $prior[0].Name }
}
Write-Host ("signing cert   built:    " + $builtCert) -ForegroundColor Cyan
Write-Host ("                 " + $expectedFrom + ": " + $expected) -ForegroundColor Cyan
if (-not $expected) {
    Write-Warning "没有可以比对的签名指纹（tools/signing-cert.txt 与 releases/ 都没有）"
} elseif ($expected -ne $builtCert) {
    if (-not $AllowNewKey) {
        throw ("这一版的签名与仓库里写死的那把钥匙不同（期望 " + $expected + " 来自 " + $expectedFrom +
              "，实际 " + $builtCert + "）。换钥匙之后装了旧版的人更新只能卸载重装、" +
              "自建库跟着没。把签名用的 tools/debug.keystore 放回来重出包；" +
              "确实要换钥匙再加 -AllowNewKey 并改 tools/signing-cert.txt。")
    }
    Write-Warning "-AllowNewKey: 这一版换了签名密钥，老用户必须卸载重装"
}

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
#
# 推 HEAD，不推分支名。发版在 `git worktree add --detach` 出来的干净树里做（主工作区里还有
# 别人没提交的改动，混进去就把没量过的东西发出去了），这时 HEAD 是游离的：
# `push origin main` 推的是仓库里那个 main，不是刚出包的这一版——远端于是只有上一版的提交，
# gh 拿这一版的 $sha 当 target 直接 422（Release.target_commitish is invalid），
# APK 已经出好、签名也对，就是发不出去。`HEAD:main` 两种树下都对：
# 正常树上与 `push origin main` 等价，游离头上推的就是这一版。
if ($NoPush) { Write-Warning "-NoPush: the release step will fail unless $sha is already on the remote" }
else {
    & git -c ("http.proxy=" + $Proxy) -c ("https.proxy=" + $Proxy) push origin ("HEAD:main")
    if ($LASTEXITCODE -ne 0) { throw "git push failed" }
}
& git tag -d ("v" + $Version) 2>&1 | Out-Null
& gh release create ("v" + $Version) $apk $sums --target $sha --title ("Word Lite " + $Version) --notes-file $notesPath
if ($LASTEXITCODE -ne 0) { throw "gh release create failed (does v$Version already exist? use gh release upload)" }
Write-Host ("RELEASED {0} code {1} sha {2}" -f $Version, $newCode, $sha) -ForegroundColor Green
Write-Host ("https://github.com/Yanqiu-01/wordlite/releases/tag/v" + $Version)