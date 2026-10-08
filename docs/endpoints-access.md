# 知网 / 万方 / 维普：哪个口匿名能用、拿到哪一档材料（逐口实测）

这份只放一件事：**同一台机器、同一批真文献、两条出口各量一遍**，把"拿不到"拆成三种不同的
拿不到——JS 墙（真浏览器能过）、SPA 壳（要等前端渲染）、授权墙（软件解决不了）。
以前这些结论散在各类的注释里，没人把四档接口（检索 / 详情摘要 / 题录导出 / 正文页）摆在同一把尺上。

    java --add-modules jdk.httpserver -Dfile.encoding=UTF-8 -classpath <host 三个 classpath> `
         com.rikkahub.wordlite.SourceEndpointProbe direct                 "深度学习 图像分割 医学影像 综述" artifacts/endpoints/direct
    java ... com.rikkahub.wordlite.SourceEndpointProbe 127.0.0.1:7897     "同上"                          artifacts/endpoints/proxy
    java ... com.rikkahub.wordlite.BrowserBodyProbe   flare  artifacts/endpoints/flare    # 本机 FlareSolverr 容器
    java ... com.rikkahub.wordlite.BrowserBodyProbe   browser artifacts/endpoints/chrome  # 本机 Chrome headless

尺子（三条都是字节判据）：种子先用**生产检索代码**问回来，所以"这个口没有这篇"不能拿"库里没有"抵赖；
`题名` = 返回字节里出现某条种子的完整题名；`摘要` = 出现某条种子摘要的中段 24 字；
`汉字` = 拆掉 `script`/`style` 之后的可读汉字数——壳页与内容页的分水岭就在这个数上。
2026-10-09 实测，7890 端口拒绝连接，代理那一路走的是 7897。

## 一、检索口与详情口（直连 22 个口，代理同表）

| 源 | 档位 | 口 | 直连 | 经 7897 | 拿到什么 |
| --- | --- | --- | --- | --- | --- |
| 知网 | 检索 | `search.cnki.com.cn/search/listresult`（现役） | 生产代码取回 4 条 | 同 | 题录 + 128 字摘要预览 |
| 知网 | 检索 | `search.cnki.com.cn/Search/Result` | 200 / 41,337 B / 365 汉字 | 逐字相同 | 检索页外壳，结果由脚本填 |
| 知网 | 检索 | `kns.cnki.net/kns8s/brief/grid`（KNS 真 API） | **403** | **200 但 102 B / 11 汉字** | 空帧：没有 session 就没有数据 |
| 知网 | 检索 | `kns8s/defaultresult/index` | 302 | 302 | 落 `verify/home?captchaType=blockPuzzle`——滑块验证码 |
| 知网 | 检索 | `wap.cnki.net/touch/web/Article/Search` | 302 → `/touch/web/article/searchindex` | 同 | SPA，检索要脚本发 |
| 知网 | 详情 | `kns/dm/manage/display` | 404 | 404 | 无 session 时这个路由不存在 |
| 知网 | 题录导出 | `kns/dm/manage/export` | 200 / 22,984 B / 279 汉字 | 同 | `<title>中国知网-文献管理中心</title>` + "购买"——**授权墙** |
| 知网 | 详情/正文 | `wap.cnki.net/touch/web/Journal/Article/<id>` | **200 / 19,881 B / 477 汉字** | 逐字相同 | **题录 + 摘要（到 128 字被 `...` 截断）+ 关键词 + 领域 + 机构**；正文页只给"下载PDF版 / 下载APP" |
| 知网 | 摘要 | `kcms2/article/abstract`（不带 v token） | 404 | 404 | 那个口认一次性 token |
| 万方 | 检索 | `SearchService.SearchService/search`（现役 gRPC-web） | 生产代码取回 4 条 | 同 | 题录 + 摘要 |
| 万方 | 检索 | `s.wanfangdata.com.cn/paper` | 200 / 171,424 B / 503 汉字 | 同 | Vue 壳 + "机构登录" |
| 万方 | 检索 | `www.wanfangdata.com.cn/search/searchList` | 200 / 145,449 B / 516 汉字 | 同 | 同一个壳 |
| 万方 | 详情 | `d.wanfangdata.com.cn/periodical/<id>` | 200 / 168,407 B / 457 汉字 | 同 | 同一个壳（带 `#abstract` 也一样，两份响应字节完全相同） |
| 万方 | 题录导出 | `study/api/rest/export` | 200 / 145,449 B / 516 汉字 | 同 | 就是那个壳，没走到导出 |
| 维普 | 检索 | `www.cqvip.com/search`（现役） | 200 / 571,897 B / **10,594 汉字** | 同 | 题录在 `window.__NUXT__` 里（`CqvipState` 读的就是它），摘要在里面 |
| 维普 | 检索 | `qikan.cqvip.com/Qikan/Search/Index` | **412** | 412 | `$_ts` 那段 JS 挑战 |
| 维普 | 检索 | `apiv3.cqvip.com` | 走不了 | — | 本站只收 HTTPS（`HttpTransport` 的护栏直接拒明文） |
| 维普 | 详情 | `www.cqvip.com/doc/journal/<id>` | 404 | 404 | 检索页里没有可拼的详情 URL：详情链接与题名都由脚本填 |
| 维普 | 详情 | `www.cqvip.com/Qikan/Article/Detail?id=…` | 302 → `/?serve=…&appCode=101` | 同 | 服务跳转壳 |
| 维普 | 正文 | `www.cqvip.com/reading/<id>` | 404 | 404 | — |

两条出口之间只有**一处**差别：`kns8s/brief/grid` 直连 403、经代理 200（但回的是 102 字节的空帧）。
其余每一个口，直连与经 7897 拿回的**字节数完全相同**。所以下一版别再把"挂代理"当成拿到东西的办法——
代理只改变这一口的门，不改变门后面的东西。

## 二、真浏览器这条路：起得来，量到的差别在哪

- **FlareSolverr**：Docker 29.7.2 在跑，`ghcr.io/flaresolverr/flaresolverr:latest` 镜像本机已有，
  `POST http://127.0.0.1:8191/v1 {"cmd":"request.get","url":…,"maxTimeout":60000}` 直接可用
  （本机 8191 已被一个跑了 18 小时的 flaresolverr 容器占着，我的 `docker run` 报
  `Bind for 127.0.0.1:8191 failed: port is already allocated`，用现成那个即可）。
- **本机 Chrome headless**：`chrome --headless=new --dump-dom --virtual-time-budget=15000 <url>`，
  不需要 Docker，同一条 `BrowserBodyProbe browser` 走的就是它。

同一批地址，两种取法与裸 HTTP 的对照（汉字 = 拆掉脚本样式后的可读汉字）：

| 地址 | 裸 HTTP | Chrome headless | FlareSolverr | 里面到底有什么 |
| --- | --- | --- | --- | --- |
| 知网 wap 详情 `<id>` | 477 | 976 | **990** | 题录 + 摘要（仍到 128 字 `...` 截断）+ 关键词 + 领域 + 机构；无正文 |
| 万方 详情 `periodical/jsjgcyyy202103005` | 457 | 457（没渲染完） | **3,331** | **整段完整摘要**、关键词、分类号、7 条资助基金、页数、**"全文精要"**（万方从全文生成的结构化压缩文本，1,500+ 字）、**参考文献 (29) 全列表含 DOI** |
| 维普 详情 `doc/journal/7103817796` | 404 | 192 B 人机验证壳 | 285,169 B / 1,673 汉字 | 是维普网的外壳与搜索表单，不是那篇的详情 |
| 维普 `qikan` 检索（裸 HTTP 412） | 412 | 41 B 空壳 | 3,727 B / 18 汉字 | 挑战过去了，但这个页面仍要前端二次请求才有数据 |
| ar5iv 全文（对照组） | — | 623,841 B | 616,726 B | 116,596 个可读字符的**真全文**（英文，汉字数为 0 是正常的） |

**这一节把"万方拿不到"改成了准确的话**：万方匿名拿不到的是"一次 HTTP 请求拿到的 HTML"，
不是"页面本身"。真浏览器渲染后，万方详情页匿名给出的是**完整摘要 + 万方自己生成的全文精要 +
整条参考文献表**——这三样都比检索协议里的 128 字预览厚，而且都在页面上，不是我们绕出来的。
FlareSolverr 比裸 Chrome 多拿到 2,874 个汉字，差别只有一处：Vue 那一页要等前端把数据请求回来，
`--dump-dom` 的虚拟时间预算不够就先交了卷。

## 三、取回的东西真进了比对

`BrowserBodyProbe` 最后一步不是打印，是把取回的页面文本按现在的导入路走一遍：

    FlareSolverr 这一轮：自建库入库 导入 3 篇，8,773 字（来自无头浏览器取回的页面）
      比对（TextCorpus.match，与 scan 内部同一句）：命中 1 处、重复 24 字 / 可比 119 字
      命中 score=0.977 出处 [取回-维普-1] 材料档 [全文]

    Chrome headless 那一轮：入库 2 篇 2,042 字，命中 1 处、重复 44 字，score=0.988，材料档 [全文]

种子句取自取回的那一页正文，所以这一发非零不是判据松——同一份稿子对没装这句话的库是 0 命中。
浏览器取回的页面进的是**文档**那一路（不是题录那一路），所以档位是 `全文`；题录导出那一路
（`docs/records-import.md`）进的是 `摘要`。两档在结果页分开报，见 `docs/records-import.md` 末尾。

## 四、还量不到的

- **正文本身**。知网的正文页在浏览器里渲染出来仍是 990 汉字加一句"下载PDF版 / 下载APP"；
  维普的详情与文内页要么 404 要么跳 `/?serve=`；万方的"全文"要给机构账号。**这一档是授权问题**，
  与解析器无关，本轮没有账号。滑块那一类（`verify/home?captchaType=blockPuzzle`）FlareSolverr
  也不解——它解 JS 挑战，不解滑块。
- **手机侧**。上面全部在 host 上跑。手机端要复用这一层，得用 WebView 渲染后取 DOM
  （`--dump-dom` 那套在 Android 上没有对应物），本轮没做，所以量不到。
- **万方那一段"全文精要"是否稳定存在**。本轮只对一篇期刊论文验过；它是不是每篇都有、
  是不是只对部分库开，要几十篇才能说，本轮没跑这个量。