# Word Lite - 让手机经电脑上的代理去问知网、万方、维普。
#
# 为什么需要这个脚本：真机上实测过一次，手机挂在移动数据上时十个检索源直连全部被当场拒回
# （连接被 RST），只有电脑上 Clash 那条路出得去。而两件事随时在变：Clash 实际监听的端口是它
# 自己配的（Clash Verge 默认 7897，不是大家以为的 7890），adb 的 USB 反代又随拔线和
# adb kill-server 一起消失。这个脚本负责确认这两件事：探出真正在监听的代理端口、把它反代到
# 手机上、再用应用自己的检索代码在手机上跑一次，把每个源最后走的那条路印出来。
#
# Usage:
#   pwsh tools/phone-gateway.ps1                 # 探端口 + 建反代 + 用手机上的代码验一次
#   pwsh tools/phone-gateway.ps1 -Port 7890      # 跳过探测，指定端口
#   pwsh tools/phone-gateway.ps1 -Status         # 只看当前状态，不改任何东西
#   pwsh tools/phone-gateway.ps1 -Watch          # 建好之后守着：拔线/adb 重启后自动补上
#   pwsh tools/phone-gateway.ps1 -SkipVerify     # 不跑手机上的验证（只修管道）
#
# 手机拿到的永远是 127.0.0.1:<同一个端口>。WordLite 的 Routes 自动发现回环上的 7897 与 7890，
# 所以应用里代理留空也能用上；把它填进设置只是让国内库少撞一次直连死路。
param(
    [int]$Port = 0,
    [string]$Serial = "",
    [switch]$Status,
    [switch]$Watch,
    [switch]$SkipVerify
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) { throw "没找到 adb，先装 platform-tools 或把它加进 PATH" }
$adb = $adb.Source
$device = @()
if ($Serial) { $device = @("-s", $Serial) }

function Invoke-Adb([string[]]$a) { & $adb @device @a }
function ReverseList { @(Invoke-Adb @("reverse", "--list")) | Where-Object { $_ -and $_.Trim() } }

function Test-PortOpen([int]$number) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $begin = $client.BeginConnect("127.0.0.1", $number, $null, $null)
        if (-not $begin.AsyncWaitHandle.WaitOne(500)) { return $false }
        $client.EndConnect($begin); return $client.Connected
    } catch { return $false } finally { $client.Close() }
}

# 端口开着不代表它是代理：Docker 也爱占 1080。所以拿应用真正要打的源挨个问一句，
# 能走 CONNECT 才算数（这一步和手机上的用法完全一样：HTTP 代理 + HTTPS 目标）。
#
# 判据只能是"有没有拿到源站回的 HTTP 状态码"，不能是"状态码好不好看"。这里栽过一次：
# 只拿 api.openalex.org 试并且只认 2xx，而 OpenAlex 对匿名流量一律回 429，
# 于是 7897 明明是通的（同一时刻 curl 走它拿到 google 204、github 200）却被判成
# "不是可用代理"，脚本自己把唯一一条出路判了死刑，手机端检索跟着全部落空。
# 429/403/404 都是源站答了话——包已经原样带出去了，这正是这里要证明的事。
$CarryProbes = @(
    "https://kns.cnki.net/kns8s/defaultresult/index",
    "https://api.github.com/zen",
    "https://www.google.com/generate_204"
)
function Test-PortCarries([int]$number, [ref]$why) {
    foreach ($u in $CarryProbes) {
        try {
            $r = Invoke-WebRequest -Uri $u -Proxy ("http://127.0.0.1:" + $number) `
                -TimeoutSec 12 -UseBasicParsing -ErrorAction Stop
            $why.Value = ("{0} 回 {1}" -f ([uri]$u).Host, $r.StatusCode)
            return $true
        } catch {
            $resp = $null
            if ($_.Exception.PSObject.Properties.Name -contains "Response") { $resp = $_.Exception.Response }
            if ($null -ne $resp) {
                $code = if ($resp.PSObject.Properties.Name -contains "StatusCode") { [int]$resp.StatusCode } else { 0 }
                $why.Value = ("{0} 回 {1}" -f ([uri]$u).Host, $code)
                return $true
            }
            # 传输层失败：这个端口没把包带出去，换下一个探测地址。
        }
    }
    $why.Value = "三个地址都没走到源站"
    return $false
}

function Find-ProxyPort {
    foreach ($candidate in @(7897, 7890, 7891, 8888, 10808)) {
        if (-not (Test-PortOpen $candidate)) {
            Write-Host ("  {0,-6} 没在监听" -f $candidate) -ForegroundColor DarkGray
            continue
        }
        $why = ""
        if (Test-PortCarries $candidate ([ref]$why)) {
            Write-Host ("  {0,-6} 开着，能把 HTTPS 带出去（{1}）" -f $candidate, $why) -ForegroundColor Green
            return $candidate
        }
        Write-Host ("  {0,-6} 开着，但不是可用代理（{1}）" -f $candidate, $why) -ForegroundColor Yellow
    }
    return 0
}

function Ensure-Reverse([int]$number) {
    # 两个端口都指过去：应用自动发现 7897 与 7890，只反代其中一个时另一个会在候选队列里
    # 白撞一次，两个都给反而让换端口时不用重新插拔。
    foreach ($remote in @(7897, 7890)) {
        Invoke-Adb @("reverse", ("tcp:{0}" -f $remote), ("tcp:{0}" -f $number)) | Out-Null
        Write-Host ("  手机 127.0.0.1:{0} -> 电脑 127.0.0.1:{1}" -f $remote, $number) -ForegroundColor Cyan
    }

    # 过验证服务（FlareSolverr）也一并反代：应用里"检索设置 → 过验证服务地址"填
    # 127.0.0.1:8191 就指到电脑上这一台，知网/万方/维普的详情页与检索页都能走它。
    if (Test-PortOpen 8191) {
        Invoke-Adb @("reverse", "tcp:8191", "tcp:8191") | Out-Null
        Write-Host "  手机 127.0.0.1:8191 -> 电脑 127.0.0.1:8191（过验证服务）" -ForegroundColor Cyan
    } else {
        Write-Host "  电脑 127.0.0.1:8191 没在监听：应用里那一格留空也找不到过验证服务，撞到人机验证就按摘要比" -ForegroundColor Yellow
        Write-Host "  用 Docker 跑的话端口要发出来才有监听（docker port flaresolverr 空着就是没发）：" -ForegroundColor Yellow
        Write-Host "    docker run -d --name flaresolverr --restart unless-stopped -p 8191:8191 ghcr.io/flaresolverr/flaresolverr:latest" -ForegroundColor DarkGray
    }
}

Write-Host "== 电脑侧代理 ==" -ForegroundColor Cyan
$found = $Port
if ($found -le 0) { $found = Find-ProxyPort }
else {
    $why = ""
    if (-not (Test-PortCarries $found ([ref]$why))) {
        Write-Host ("  {0} 不能把 HTTPS 带出去（{1}）" -f $found, $why) -ForegroundColor Yellow
    } else {
        Write-Host ("  {0,-6} 指定端口可用（{1}）" -f $found, $why) -ForegroundColor Green
    }
}

# 数据线不是唯一的去路：手机和电脑连同一个 WiFi 时，把应用里的代理填成"电脑的局域网地址:端口"
# 也能出去，手机就能拔线用。但这条路要两件事同时成立——Clash 得开着"允许局域网连接"
# （默认只监听 127.0.0.1，从局域网地址根本连不上），手机也得在同一网段。
# 所以这里先探一次：能从局域网地址走到代理就说明这条路真的通，不然就照着没通的那件事说。
function Show-LanRoute([int]$number) {
    # 只报走外网那一张网卡的地址：按 InterfaceMetric 排会挑到 WSL / Hyper-V 的虚拟适配器
    # （实测挑中过 172.24.192.1，手机永远到不了那个地址），所以问系统"去外网会用哪个源地址"，
    # 问不到再退回按跃点排的第一项。
    $ips = @()
    try {
        # 真机踩过：这里若只按 InterfaceMetric 排，会挑中 WSL 的 172.24.x，
        # 而手机能到的是走默认路由那张网卡的地址。
        $route = Find-NetRoute -RemoteIPAddress "223.5.5.5" -ErrorAction Stop |
            Where-Object { $_.IPAddress -and $_.IPAddress -ne "127.0.0.1" } |
            Select-Object -First 1
        if ($route) { $ips = @([string]$route.IPAddress) }
    } catch { $ips = @() }
    if ($ips.Count -eq 0) {
        $ips = @(Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue |
            Sort-Object -Property RouteMetric | ForEach-Object {
                Get-NetIPAddress -InterfaceIndex $_.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue
            } | Where-Object { $_.IPAddress -notlike "169.*" -and $_.IPAddress -ne "127.0.0.1" } |
            Select-Object -First 1 | ForEach-Object { $_.IPAddress })
    }
    if ($ips.Count -eq 0) {
        Write-Host "  这台电脑没有可用的局域网地址（没连 WiFi 也没插网线），只能走 USB 反代" -ForegroundColor Yellow
        return
    }
    foreach ($ip in $ips) {
        $reachable = $false
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $begin = $client.BeginConnect($ip, $number, $null, $null)
            if ($begin.AsyncWaitHandle.WaitOne(800)) { $client.EndConnect($begin); $reachable = $client.Connected }
        } catch { $reachable = $false } finally { $client.Close() }
        if ($reachable) {
            Write-Host ('  {0}:{1} 能连上代理：手机连上和电脑同一个 WiFi，应用里 检索设置 → 代理 填 {0}:{1}' -f $ip, $number) -ForegroundColor Green
            Write-Host "                    之后就能拔线用，这条路不依赖 adb" -ForegroundColor Green
        } else {
            Write-Host ('  {0}:{1} 连不上：Clash 现在只听 127.0.0.1，要拔线用得在 Clash 里打开「允许局域网连接」(allow-lan)' -f $ip, $number) -ForegroundColor Yellow
        }
    }
}
Show-LanRoute ([int]$found)
Write-Host "== USB 反代 ==" -ForegroundColor Cyan
$listed = ReverseList
if ($listed.Count -eq 0) { Write-Host "  （当前没有任何反代）" -ForegroundColor DarkGray }
else { $listed | ForEach-Object { Write-Host ("  " + $_) -ForegroundColor DarkGray } }
if ($Status) {
    if ($found -gt 0) { Write-Host ("  可用代理端口：" + $found) -ForegroundColor Green }
    else { Write-Host "  没找到可用代理端口：先把电脑上的 Clash 打开（Clash Verge 默认 7897）" -ForegroundColor Yellow }
    exit 0
}
if ($found -le 0) { throw "电脑上没有能把 HTTPS 带出去的代理端口，手机只能直连；先在 Clash 里开好混合端口再跑一次" }
Ensure-Reverse $found

if (-not $SkipVerify) {
    Write-Host "== 手机上的检索自检（用应用自己的代码）==" -ForegroundColor Cyan
    & (Join-Path $root "tools/device-probe.ps1") -IncludeCnki -Engines "cnki,wanfang,cqvip" -NoTcp -Per 3 2>&1 |
        Where-Object { $_ -match "^(OK|FAIL|SKIP|ROUTES|SUMMARY|probe exit)" } |
        ForEach-Object { Write-Host ("  " + $_) }
}
Write-Host ("应用里代理填 127.0.0.1:{0} 即可（留空也行，应用会自动发现 7897/7890）" -f $found) -ForegroundColor Green

if ($Watch) {
    Write-Host "== 守着反代（Ctrl+C 退出）==" -ForegroundColor Cyan
    while ($true) {
        Start-Sleep -Seconds 5
        $now = @(ReverseList | ForEach-Object { ("" + $_ -split " ")[0] })
        $wanted = @("tcp:7897", "tcp:7890", "tcp:8191")
        $gone = @($wanted | Where-Object { $now -notcontains $_ })
        if ($gone.Count -gt 0) {
            Write-Host ("  {0} 断了（拔线或 adb 重启），补上" -f ($gone -join ", ")) -ForegroundColor Yellow
            Ensure-Reverse $found
        }
    }
}