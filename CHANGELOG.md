# 更新日志

版本号遵循"功能加一版、修复加一位"，每个可安装构建同步递增 `versionName` / `versionCode`。

## 0.5.0（`versionCode 21`）

**万方接进来了，照抄的那一段不再看两边怎么断句**

- 内置检索源从八个变成九个，万方数据排在中文库第一位。万方的检索只说 gRPC-web：`POST https://s.wanfangdata.com.cn/SearchService.SearchService/search`，请求体是 5 字节帧头加一帧 protobuf（`CommonRequest{searchType=paper, searchWord, currentPage, pageSize}` + `interfaceType`），回来的也是帧化 protobuf。`ProtoWire` 只实现线格式里用到的 varint 与长度前缀两段，`WanfangProtocol` 按字段号取题名/作者/刊名或学校/年份/摘要/DOI，并给出 `https://d.wanfangdata.com.cn/periodical/{id}` 这类可点开的文献页。为的是一个不到四十一个字节的请求体去引 protobuf 运行时不划算，读侧越界的长度前缀一律按格式无效处理。
- 实测"机器学习"0.5 秒返回 5 条题录、总命中 242193 条，摘要整段进本机比对——这是中文三库里唯一给匿名调用者留门的检索口。`HttpTransport.postBytes` 是新的字节通道：写 protobuf 请求体、按字节收响应（`ApiClient.Response.raw`），护栏与 GET/POST 相同（只走 HTTPS、不追重定向、响应有上限、错误里不带地址）。
- `TextCorpus` 在句子级之外加了一条字符指纹带：`Fingerprints` 做滚动 8 元组哈希 + Robust Winnowing（窗口 12），数学上保证任何不少于 19 个字符（`n + w - 1`）的公共片段必定留下一枚共同指纹，与两边怎么断句无关。取样比的是指纹值而不是窗口下标——按窗口下标去重时两边历史不同，同一段文字可以一边输出一边不输出，整段照抄反而躲过检测。命中经 `run()` 逐 token 验算后只补句级没盖住的字符，两套算法不会把分子翻倍。
- 滚窗口那一步少乘了一次 BASE：滚出窗口的旧字符此时位权是 `BASE^GRAM`，减成 `BASE^(GRAM-1)` 会让哈希一直拖着整段前缀，同一段文字换个位置算出来两样，指纹比对退化成"两边恰好在同一处开头"才命中。修完之后同一段 26 字的复制从 0 枚共同指纹变成 3 枚，报成一段而不是切碎。
- 汉字数目字与阿拉伯数字在指纹层同形：连着的数字折成一枚 token，`40` 与 `四十`、`70%` 与 `七成` 占同一个格子，取样与验算同一个口径。这是降重写法和录入差异里最常动的两处，不折就会把一段连续复制砍成三四截，谁都不到 19 字符。折完之后的副作用写进了注释：1949 年与 1979 年同形，但 19 字符的门槛决定了单靠年份撑不起一段重复。
- 代理那一头没人应答时退回直连重试一次（`HttpTransport.send`）。手机上的代理是 `adb reverse tcp:18899 tcp:7897` 映射过来的电脑端口，拔线或忘了 reverse 之后端口就没人听；只有"拨不通/握手失败"才换路重试，对端明确拒绝或限流时不重复打扰。
- 知网仍然没有匿名检索口：`kns8s/*` 一律 302 到滑块验证，`brief/grid` 对匿名会话固定回 102 字节的"暂无数据"，`search.cnki.com.cn/api/search/listresult` 的 POST 在直连与代理下都是 50 秒 504。能匿名取回的是 `wap.cnki.net/touch/web/Journal/…` 的条目与刊期目录，知网题录走`自建库`或`接口设置`里的自定义服务，本版不假装能白拿。
- 回归：`TextCorpusRegression` 156 → 168（跨句复制、标点与数字改写、不足 19 字符不报重复），`DetectRegression` 156 → 167（万方真实抓包的解析、帧长自洽、`status=false` 的原话进注记、帧被截断不得静默给空结果、代理没人应答退回直连）。夹具 `tests/samples/wanfang-search.bin` 是真实响应字节。12 个套件 1010 → 1033 断言通过；`LiveEngineProbe engines` 在线核对九个源：万方 5 条 / 维普 5 条 / 哲社 5 条 / OpenAlex 5 条 / Crossref 5 条 / Europe PMC 5 条，Semantic Scholar 公共配额偶尔 429。

## 0.4.7（`versionCode 20`）

**改写有没有用，由改写前后的对照说了算**

- `DuplicateEngine.compareRewrite(before, after, corpus)`：拿同一份比对基线把改写前与改写后各检一次（结构排除区间各按自己的文本重算），返回两个相似率、两侧有效字符数与命中字数，外加一句结论：下降 X 个百分点 / 基本没动（这一轮改写没有真的降重）/ 反而升高（改写把句子改得更像原文了）/ 两边都没重复，衡量不出效果。0.5 个百分点以内算没动。
- 为什么补这一条：降重面板只有逐段的原文与改写对照，看不出整篇相似率有没有变。离线改写多是同义词替换与语序微调，"每段都改了、相似率没动"是常态。参照 WCopyfind 与 JPlag 的做法，判效果看整体共享量而不是逐段观感（出处见 `docs/oss-algorithms.md`）。
- `DuplicateEngine.Report.baseline` 记下本次真正比对的语料（自建库加检索到的候选）。改写效果必须用同一份基线，否则两个百分比没有可比性。
- 降重菜单多一项"改写效果"：基线优先用上一次扫描那份，没有就现编自建库，两边都空时直说"先做一次查重"。
- 回归：`DetectRegression` 147 → 156 断言，覆盖真降了、改了等于没改、改得更像原文、没有基线、报告留住了比对语料、离线扫描相似率非零。12 个套件 1010 断言通过。

## 0.4.6（`versionCode 19`）

**AIGC 只看自己该看的部分，样本不够就不给比例**

- `AigcDetector.detect(text, excludedSpans)`：引用区间加上 0.4.5 找出的结构性文本（参考文献表、致谢、目录）一律不参与机器腔打分，那些本来就是抄来或套来的。`DuplicateEngine` 把两类区间接在一起传进去，报告多一句"AIGC 分析跳过引用与结构性文本 N 字"，`Result.excludedChars` 负责落账。
- 门槛 `MIN_DOCUMENT_CHARS = 400`：有效字符不够就置 `insufficientSample`，指标那一格写"样本不足"而不是百分比，自编率也不再被这份倾向拉动。参照公开实现的入口守卫（DetectGPT 复现仓库的 min 100 characters / min 100 words 与 60..80 的"需要更多文本"中间档，出处记在 `docs/oss-algorithms.md`）。
- 结论改成三档人话（`Result.verdict`）：样本不足 / 机器腔明显建议逐段复核 / 部分段落有机器腔 / 未见明显机器腔。手机端拿不到 token 级 logprob，Binoculars、DetectGPT、GLTR 那类算法在这里不成立，所以只报倾向不报判定。
- HTML 报告在 AIGC 一节顶上把 verdict 原样打出来，未完成查重的分支同样走这套。
- 回归：`AigcRegression` 58 → 70 断言，覆盖门槛、三档结论、不传区间与传 null 结果一致、把模板句圈成引用之后倾向下降、排除区间里没有残留计分句。12 个套件 1001 断言通过。

## 0.4.5（`versionCode 18`）

**参考文献表、致谢、目录不再算进相似率**

- `citationLike` 以前只是躺在代码里被测试调用，查重照样把参考文献表算进分母。现在 `TextCorpus.structure()` 会找出结构性段落：按标题开段（参考文献/引用文献/参考书目/致谢/鸣谢/后记/附录/目录/目次/references/bibliography/acknowledgements/appendix/contents），参考文献那一段必须真有两条以上带 `[J]`/`[M]`/`[D]` 的条目才算，目录段必须有两条以上"标题 + 页码"，否则整段不动；没有标题的文献表（连续三条以上条目）也认。
- 实测这篇 23674 字的参考论文：目录 812 字加参考文献 36 条 6229 字，共 5969 个有效字符，占参与比对文本 20817 字的 28.7%。这部分只要引了同样的文献就会被判重，与抄袭无关，知网与万方默认也不比参考文献。
- 第一版差点把 8984 字当成文献表：正文里"（2）热压连接采用 240、250 和 260 ℃"这类编号段落在 `citationLike` 下同样够 3 分，末尾三章差点被整段排除。现在无标题的条目串必须带 `[J]`/`[M]`/`[D]`/DOI 这类标识（`bibliographyEntry`），排除量回到 5969 字。
- `match(text, citationSpans, excludedSpans)`：落在排除区间里的句子整体退出比对，分子分母都不计。`TextCorpus.Report.excludedChars` 与 `DuplicateEngine.Report.excludedChars` 一路带到报告，HTML 报告多一句"另有 N 个字符按论文结构排除在比对之外"，检测说明写清排除了几节、多少条。
- 回归：`TextCorpusRegression` 129 → 156 断言，包括标题识别的九个正反用例（"参考文献的编排要遵循国标。"不得当标题）、只有一条条目时不得整节排除、致谢之后的正文仍然参与比对、目录条目不得被当成下一章节、没有标题的条目串也要认、以及"不排除时参考文献条目确实会被当成抄袭命中"这条对照。全部 12 个主机套件 989 断言通过。
## 0.4.4（`versionCode 17`）

**内置检索源加到八个，其中两个是中文库；检索可选走代理**

- 检索设置多了一个 `HTTP 代理`（`host:port`，留空即直连）。手机链路会把 Semantic Scholar、Europe PMC 这几个主机的 TLS 连接直接掐断，填上电脑上代理客户端的端口就能出网；代理只隧道 HTTPS，看不到正文。写法不对（缺端口、写成 URL）一律按"不用代理"处理，不会把一次查重弄成失败。`HttpTransport` 相应支持传入 `java.net.Proxy`，`EngineSettings` 存 `proxy` 字段。
- 新增两个内置中文检索源，并排在默认列表最前：**维普**（`www.cqvip.com/search` 的服务端渲染检索页，取摘要/作者/刊名/年期/文献页地址；题名由脚本后填、服务端不给，报告里这一行以"《刊名》 年 期"署名）与**国家哲学社会科学文献中心**（`searchHandler/search` 只接 POST，检索式必须带 `IKTE`/`IKST`/`IKRK` 字段码，裸词返回 0 条）。内置源从 6 个变成 8 个。
- 知网与万方没有可用的匿名检索入口：知网的检索页跳滑块验证、`brief/grid` 对匿名会话固定返回"暂无数据"；万方的检索走 gRPC-web 且带反爬跳转。这两家的题录走自建库（把下载好的文献导进来比对）或"自定义接口"（接机构/付费的检测服务），本版不假装能白拿。
- 检索窗口不再被封面带偏：短于 20 字的段（封面行、页眉、目录行、图表注）不进检索窗口。真机拿一篇 14460 字的工学学位论文实测，之前的窗口取到的是"本科毕业论文（设计）"这类封面字样，Crossref 因此回了一批"毕业论文教学改革"的论文，与正文无关。
- 检索短语上限 160 字 → 48 字：实测同一篇摘要，维普对 160 字的整句返回 0 条、截到 40 字返回 20 多条，OpenAlex 与 Crossref 在短查询上没有变差。`DuplicateEngine.MAX_PHRASE_CHARS` 改到这个量级，哲社中心的字段码检索式同步收窄。
- 单次检测的检索请求上限 40 → 72：八个源都在跑之后，40 次连一篇学位论文的前三分之一都没查完，报告里那句"剩余窗口未检索"是常态。维普与哲社中心的署名做了收拾：学位论文那一条链接里串着"作者 • 导师 • 培养单位"，只取第一段作为作者，没有期刊链接时以"学位论文 培养单位"署名，年份不再要求后面必须跟期号。
- 报告里的"X 未命中相关文献"改成一个检索源一句、跑完再下结论：以前只要某个窗口没查到就记一句，实测同一篇论文里维普先空后中，却被报成"未命中"，而候选池里明明有它的 5 篇。
- `HttpTransport` 增加 `post(...)`，与 GET 同一套限制（仅 HTTPS、不跟随重定向、响应上限、错误不带查询串）和同一次 429 退避重试。
- 回归：`DetectRegression` 124 → 141 断言，夹具是维普检索页与哲社中心返回的真实片段（含 `HtmlUrl` 为 null、作者用空格分隔这两种真实脏数据）；另加回环桩代理用例，断言代理收到的是绝对形式请求行、答案原路返回、且这一趟不会又直连一次源站。

## 0.4.3（`versionCode 16`）

**查重不再把"没查到"报成"没重复"**

- 真机跑"联网查重（内置文献库）"复现出一个会害人的输出：五个内置检索源全部连接失败、候选文献 0 篇、自建库为空，报告照样写"总相似度比 0.00% · 去除引用重复比 0.00% · 自编率 99.65%"。对一篇准备提交的论文，这是整个应用里最贵的一个数字。现在 `DuplicateEngine.Report` 带 `retrievalIncomplete` / `retrievalReason`，对话框与导出 HTML 在这种情形显示"未完成查重"加原因，AIGC 倾向照旧给（它是本机算的）。
- 只有五种情形算未完成：所选检索源本次全部不可用、取候选前被取消、启用联网却没有可用源、联网扫描被异常中断、未联网且自建库为空。联网成功而确实零命中的 0.00% 仍照实显示，`rates()` 一位未动。
- `HttpTransport` 不再把 TLS 与 IO 异常压成"安全连接失败""网络连接失败"：现在带上 JSSE 的叶子异常。真机因此报出 `SSLHandshakeException: Connection closed by peer`，与根证书过期那种 `unable to find valid certification path` 一眼可分；本次五源同错、且 PC 侧同一组端点全部 200，说明是链路被重置而不是设备信任库过期。
- CORE 的地址补上尾斜杠。`/v3/search/works` 会 301 到 `/v3/search/works/`，而本传输层按设计不跟随重定向，所以这个源以前必然失败。
- 429 改为读 `Retry-After` 退避：等待上限 10 秒，超过就记"检索源限流，约 N 秒后恢复，本次跳过"。Semantic Scholar 匿名池是 1 req/s、CORE 是 10 req/10min，原来固定 700 ms 的单次重试对它们是纯浪费。
- `User-Agent` 改为报出项目主页（`WordLite document checker (+https://github.com/Yanqiu-01/wordlite)`，不写版本号以免和 manifest 走偏），这是 OpenAlex 与 Crossref polite pool 的约定；实测两者对匿名 UA 都返回 200，不加 `mailto` 也不会 403。
- `DetectRegression` 增加"全部端点指向一个已关闭的回环端口"的用例，断言 `retrievalIncomplete` 为真且导出的 HTML 里没有 `总相似度比` 指标行（105 → 124 断言）。主机侧 12 个回归套件全通过。

## 0.4.2（`versionCode 15`）

**两端对齐不再等平台**

- 补缝改为所有版本都做，`DocxTextLayout` 去掉 `Build.VERSION.SDK_INT < 34` 这道门。理由是在 android-all 34 + 原生 minikin 上实测参考开题报告的首个长段：平台自己走完 `JUSTIFICATION_MODE_INTER_CHARACTER` 之后，566.93 px 版心的六个满行仍分别剩 7、8、23、23、11、7 px 没摊开。Robolectric 一直不是这条链路的可信参照，但 34 的框架代码是真的，够说明"到了 34 就交给平台"这个前提站不住。补缝后同一篇的六行余量全为 0.0 px，断行点与左对齐一致，也没有越过版心。
- 只摊平台这一遍剩下的余量：缝隙基准仍取 `getPrimaryHorizontal` 的已排布位置，平台若真把行拉满，这里就无余量可摊，不会二次加宽。
- 新增 `JustifyApi34Test`（`@Config(sdk = 34)`，`gradle -p tests/ui test -ProbolectricSdks=28,34`）钉住满行落到右缘、不越过版心、断行点不动，并逐行打印余量。
- API 29 真机像素不变：`tools/capture-device.ps1 -Impls new` 重跑后页数仍 28，206 段与 Word 对齐、7 段各差 1 页（精确同页率 96.6%），逐行比右缘仍是 32/32 落在 1 px 以内、段内右缘标准差 0.00 px。

## 0.4.1（`versionCode 14`）

**两端对齐与目录页码**

- `w:jc="both"` 在 API 34 以下改由应用层拉开字间：`DocxTextLayout.justifyEastAsian` 逐行取剩余空隙，按 Bresenham 把整像素摊到候选缝隙上，用 `ReplacementSpan` 复述该字符已排布的 advance。真机 API 29 实测 `JUSTIFICATION_MODE_INTER_CHARACTER` 对纯中文完全无效（none / interWord / interChar 行宽同为 560.0），`setLetterSpacing` 又被量化到整像素（0.02em 不动，0.05em 才 +1px），所以只能这么走；API 34 及以上仍交回平台，拉丁词间的拉伸始终留给平台。
- 可开缝隙沿用 Word 的口径：任一侧为中文（含中文标点）才开，拉丁词内部不开；中西文自动空位（`w:autoSpaceDE/DN`）同样可拉，Word 实测这类 1/4 em 缝 100% 被加宽、最宽 +3.55 pt，而中文标点与拉丁之间的缝不加宽；被上下标、下划线、删除线、底纹覆盖的字符不参与；末行永不拉伸。一条线每隔 2 px 开一条缝，缝位在行内居中均匀取点，单缝上限 6 px。被加宽的字符若正带着自动空位，先摘掉那个 span 再挂 `WidenGap`：同一区间挂两个 `ReplacementSpan`，旧系统成型只拿一个计量，加宽就悄悄没了。步长取 2 是实测结果——1 px 是 Word 量子 0.767 px 的最近近似，却要改动行内最多字符，参考文档上留下的参差行是 2 px 的 1.6 倍；3 px（开缝条数与 Word 相同）在手机上读作句子中间的洞。
- 换行点一律不动。缝隙基准取 `getPrimaryHorizontal` 的已排布位置而不是重新 `measureText`（成型 run 的宽度并不等于逐字宽度之和），只接受本来就是整像素的 advance；排完逐行比对 `getLineStart`，某行的断点被推走时把那一行的空隙一次退让 1 px 重试，退无可退才整段回落原布局。
- 实测同一篇开题报告（真机 HONOR CDY-AN90 / Android 10 / API 29，`py -3 tools/justify-raggedness.py old new`）：中文正文非末行在文档坐标下相对 566.93 px 版心的缺口，中位数 6.93 px → -0.07 px，超过 2 px 的行 183/266（68.8%）→ 10/266（3.8%），最大缺口 23.9 px → 11.9 px。与 Word 真值逐行比右缘（`pwsh tools/edge-parity.ps1 -Impl new`，口径见 `docs/edge-parity-baseline.md`）：平均绝对偏差 6.81 px → 0.99 px（中位 0.20 px），落在右边界 1 px 以内的行 4/26（15.4%）→ 32/32（100%），段落内右缘标准差 2.69 px → 0.00 px。页数仍 28，206 个与 Word 逐段对齐的段落里错页仍是那 7 段（各差 1 页），断行点未被移动。
- 目录右对齐制表位 `LeaderTab` 改为预留页码宽度：页码**结束**在声明的 `w:tab w:pos` 上，而不是从停止位起排再越过它，点线只填到页码起始处。停止位与 Word 一样从左边距起算、与条目自身缩进无关，所以缩进的 `3.1.1` 和不缩进的 `1` 页码右缘落在同一条线上。
- Word 侧真值由 `tools/word-justify-truth.ps1` 产出（`artifacts/word-justify/`），规则拆解在 `docs/word-justification-rule.md`（`py -3 tools/justify-rule.py` 重算全部数字）：加宽量子 Q = 0.575 pt = 11.5 twips = 0.767 文档像素，`k_total = round(slack / Q)` 在 18 条纯中文行上全中；34 条缝的行中位数只开 3 条，缝位均匀分布，同字符数同缩进的行索引集合一致（如 [5, 15, 25]）；最宽单缝 +3.55 pt；行尾全角标点溢出边界且其前保持自然宽度；不存在放弃拉伸的阈值（缺 14.8% 的行照样填满）。实现与实测见 `docs/justification.md`。
- 新增 `JustifyLayoutTest`（Robolectric，3 例）：两端对齐逐行落到版心且换行点不动、渲染后该行墨迹抵达右边界（`GraphicsMode.NATIVE` 逐像素取点）、目录页码右缘压在声明的停止位 ±1.5 px 内。

## 0.4.0（`versionCode 13`）

**上下标与分页**

- `w:vertAlign` 上下标的行宽改按脚本字号（OS/2 `ySuper/ySubXSize`）计量，与 Word 的 advance 取法一致；此前按基础字号计宽，含上标引用的紧边界行会提前换行。
- 行高按 Word 的升降文字让位规则逐行增长：上标顶出单倍行盒时撑高该行，`exact` 行距下裁剪不撑高；无上下标的段落行盒不变。
- 段落单倍行高按各 run 的实际渲染字号计算，小字上标不再抬高整段行盒。
- Unicode 上下标（`Cu₆Sn₅`、`m²`）保持 hAnsi 原始字形与全尺寸计量。
- 新增 `ScriptGeometry`（纯 Java）承载升降文字的字号/基线/行盒推导，`ScriptRegression` 在 JVM 侧断言；Robolectric 侧补充上下标行宽与行高用例。

**查重 · AIGC · 降重**

- 查重内置检索源连接器：OpenAlex、Crossref、Semantic Scholar、Europe PMC、arXiv、CORE（可选 Key）。检索短语查询 → 候选题录 → 按需拉取开放获取全文 → 本机比对。
- 本机匹配引擎 `TextCorpus`：全半角/繁简/标点/空白归一化、句子切分、字符 n-gram 指纹与 MinHash 签名、倒排候选、Dice 系数与跨句合并。
- 指标拆成总相似度比、去除引用重复比、自编率三项；相似片段带来源题名、作者、年份、标识符，可点击定位到段落。
- 自建库 `LocalLibrary`：导入 `.docx` / `.txt` 建立本机索引并持久化，作为离线查重语料与 AIGC 基线。
- AIGC 检测 `AigcDetector`：逐句机器生成倾向分（句长突发度、连接词密度、模板句式、句首结构重复、二元组熵、标点画像、词汇总度），整篇比例按字符加权，附触发特征。
- 降重新增离线规则后端 `LocalRewriter`，与自定义大模型接口共用 `TextProtection` 掩码；模型失败段落回落规则改写。
- 报告补齐来源分布、AIGC 明细与自建库命中；`DetectRegression` 覆盖指纹、匹配、指标、AIGC、离线改写与报告转义，检索源用本地回环服务打桩。
- OpenAlex 检索词按前五个关键词下发：`title_and_abstract.search` 是逐词必含的，线上实测同一短语五个词命中 35 篇、加到六个词命中 0 篇，整句窗口等于关掉这个源；无空格的中文串按字符预算截断。
- 检索源状态码不再一律报"网络连接失败"：429 说明被限流并自动重试一次，401/403/5xx 各给各的说法；错误响应体读取失败也不会盖掉真实状态码。


**表格分页**

- 列宽改从 `w:tblGrid/w:gridCol` 读取，`w:tblW` 与网格不符时按比例缩放，`w:gridSpan` 跨列累加，`w:tblInd` 与表格 `w:jc` 决定表格落点；宽于版心的表格按 Word 一样越出页边距，不再被压回版心。此前一律按版心等分列宽。
- 单元格内边距按 `w:tblCellMar` 与单元格 `w:tcMar` 取，文本框宽 = 列宽 - 左右内边距；`w:vAlign` 的 `center`/`bottom` 在行盒内垂直对中。
- `w:trHeight` 生效：`exact` 钉死行高（内容超出按 Word 裁剪），`atLeast` 只抬高不裁，未声明时按内容撑高。`w:cantSplit` 的行不参与跨页搬移。
- 上述几何随保存回写：`w:tblGrid`、`w:tblW`、`w:tblInd`、`w:jc`、`w:tblLayout`、`w:tblCellMar`、`w:trPr`、`w:tcW`、`w:gridSpan`、`w:vMerge`、`w:tcMar`、`w:vAlign` 逐项还原，`w:tblHeader` 也不再丢失；增删行列同步维护 `w:tblGrid` 与 `w:trPr`。
- 实测同一篇开题报告：总页数 29 → 28（与 Word 一致），页码错位段落 50 → 7，含上下标的 34 个段落里错位的从 7 段降到 1 段。
- 新增 `TableGeometryRegression`（JVM，31 条）覆盖解析、行高规则、保存往返与增删行列；Robolectric 新增 `TableGeometryTest`（3 例）在真机字体下断言列宽、`exact` 钉死行高、`atLeast` 下限与 `w:vAlign` 偏移。

**断行与版心宽度**

- `DocxTextLayout.measure` 的可用宽度改为四舍五入。`StaticLayout` 只接受整数宽度，此前向下取整：425.2 pt 版心被砍成 566 px，Word 用 566.93 px 判行，每条紧行 Word 多装一个字符——逐行差异表里 20 多处行末剩余正好是这 0.9 px。改后同一批 165 行的逐字对齐率 57.1% → 62.4%，页数与页级结果不变。
- `tools/word-line-breaks.ps1`：Word 的真实断行点。这个 Word build 的 `Document.Lines` 返回空集合，只能逐字符读 `Information(wdFirstCharacterLineNumber)` 反推断行位置，按段落计费，单轮控制在几十个段落。
- `tools/line-break-delta.ps1`：把 Word 断行点与设备逐行结果按正文对齐后出表，分上下标 / 页码边界 / 纯正文对照 / 长西文四组，给逐行差异、首个分歧行与成因分布。段落对应关系用正文证明，实测 Word 段号偏移在 +1 / +7 / +13 / +24 / +44 之间跳（表格块占位导致），固定偏移会整批错位。
- `tools/word-line-truth.ps1` 标注为死路并保留备查：它走 `Range.Lines`，在这个 Word build 上取到空集合。
- `tools/word-parity.ps1` 的页码错位上限从 50 收到 10。

**构建与验证**

- `tools/build-host.ps1`：Windows 无 Android SDK 时自建构建链（sdkmanager 拉 build-tools → aapt2 → javac → D8 → zipalign → apksigner），并修正 Windows aapt2 写出的 `assets/fonts\x.ttf` 反斜杠资源路径，产物 `artifacts/apk/wordlite-debug.apk`。
- `tools/test-host.ps1`：一条命令跑完 12 个 JVM 套件（920 条断言），不需要 aapt2；`FontAssetsRegression` 另校验 APK 内字体字节 51 条。
- `tools/capture-device.ps1` + `tools/word-parity.ps1`：把排版引擎 dex 后用 `app_process` 在真机跑一遍，再与 COM 驱动 Word 得到的页码真值逐段对齐，输出页码差分布。
- `LiveEngineProbe`：显式联网探针，逐个确认六个检索源当前可用，并跑一次完整查重链路。
- `tests/ui/build.gradle` 显式指定 `UTF-8` 源码编码：Windows 上 javac 按代码页读取会让所有中文断言字符串变成乱码，Robolectric 52 例里 14 例因此假失败；修复后仅剩一例与平台路径分隔符相关的已知失败（现为 55 例，1 例已知失败）。
- 渲染类 Robolectric 用例改钉 `sdk = 28`：`robolectric.enabledSdks` 只放行 28，`RibbonRenderTest` 与 `PdfNativeTest` 此前请求 sdk 35，两个类从未执行也不报错。改后 `PdfNativeTest` 通过，ribbon 用例补上手机密度（`w411dp-xxhdpi`）与按底色差异计数的出墨断言，开始/审阅两个选项卡分别落墨 3384 / 4045 点。
- `TableRenderTest` 把列宽正确性落到像素：整页光栅化后沿 `w:tblGrid` 每条列边纵向取样必须命中那笔灰色栏线，旧的等分位置必须没有栏线；整页 PNG 同时落盘 `artifacts/analysis/` 供肉眼复核。
- 宽于版心的表格不再被裁在版心内：行的绘制裁剪改按该行的实际列范围（含 `w:jc` 越出页边距的部分，收到纸张物理边界为止），这类表格左右两条贴边竖线重新显示——此前整页只在正文栏内裁剪，越界的栏线被整条擦掉。`TableRenderTest` 增加两条外侧栏线的像素断言。
- `ViewportTest.recentFileCanBeReopened` 改按 Android 的路径形状构造 `file://` URI：`Uri.fromFile` 在 Windows 上得到 `file://E%3A` 加反斜杠路径，`getPath()` 不是可用路径，`openRecent()` 判该条目失效并转去选文件；Android 只存绝对 POSIX 路径，这个分支在真机上不存在。整套 58 例全绿。
## 0.3.4（`versionCode 12`）

- 编辑器功能区回到页面顶部，选项卡：文件、开始、插入、引用、审阅、视图；文件页提供打开、保存、另存为、导出 PDF、关闭。
- 页面底部保留左右等宽的"正文"与"查重"两栏，"查重"栏提供查重、降重、接口设置三个入口，网络请求只由命令点击触发。
- 状态栏、查重入口与底部双栏改为纵向底部区域，修复横向挤压。
- OOXML 段落模型新增并往返保存 `w:wordWrap`、`w:overflowPunct`；Android 33+ 对含长西文/数字串的段落启用 Word word-wrap 规则，悬挂标点只在文档声明 `overflowPunct` 时启用。
- 上下标 `ReplacementSpan` 宽度向上取整，紧边界不再少占一像素导致提前换行；Unicode 上下标保留原始字形。

## 0.3.3（`versionCode 11`）

- 底部命令栏重构（对齐 M365 Android 交互）：选项卡行 + 命令行位于屏幕底部，页面区上方无工具栏。
- 选项卡为开始、插入、引用、审阅、视图、论文；"论文"含查重、降重、API 设置、另存为、导出 PDF；撤销/重做并入"开始"。
- 状态栏缩为命令栏下方一行小字，阅读模式隐藏命令栏与状态行。

## 0.3.2（`versionCode 10`）

- 删除 `RulerView` 与"标尺"命令；去掉空心矩形保存图标，另存走 文件 → 另存为 / Ctrl+S。
- 返回、撤销、重做、折叠统一为 2dp 圆角描边矢量图标。
- 功能区改用 `sans-serif-medium`：选项卡 14sp + 2dp 品牌蓝下划线，命令 13sp medium，B/I/U 用衬线粗体/斜体/下划线，分隔线改半透明细线，浅色模式功能区纯白底。

## 0.3.1（`versionCode 9`）

- 收口交付字体精简/惰性加载、Ribbon 与导航、PDF 导出、修订与批注线程、自定义查重/降重接口。
- 首页空文档状态固定为 `Word Lite → 打开文档 → 最近文件 → 暂无文件`。
- 构建链保持 aapt2 → javac → D8 → zipalign → 签名，v1/v2/v3 与版本信息在最终产物上校验。

## 0.3.0（并入 0.3.1）

- 文件 → 设置：分别配置查重/降重的 URL、Key、方法、请求头 JSON、正文模板、响应路径、上传字段、超时重试、保留术语；配置整体经 AndroidKeyStore AES-256-GCM 加密保存。
- 查重：上传编辑后的 DOCX（文档上传模式）或提交选区，显示重复率、相似片段、来源与时间，页面临时高亮，导出离线 HTML 报告。
- 降重：选区/当前段落/全文逐段请求，原文与改写对比，逐条接受/拒绝/重新生成与多建议选择；参考文献段落不自动改写。
- 引用、上下标、域、链接、计量、希腊与数学符号、配置术语作为保护片段；丢失保护标记的建议不可接受；改写只发生在片段之间，保留原 run 样式与锚点，进撤销栈并参与修订。
- 网络仅在用户点击时发生：无默认服务器与硬编码 Key，允许内网 HTTP 但不改变系统 TLS 校验，不跟随重定向，重试携带稳定请求 ID。
- JSON 模板 `{text}`/`{content}`/`{key}`/`{filename}` 支持置于值内或字符串内；结果映射支持 `data.field` 与 `$.data[0].field`；重复率支持 0–1 与百分比，偏移支持 codepoint 与 UTF-16。
- API 23 兼容审计：移除 `List.sort`、`removeIf`、`Float/Double.isFinite`、`Comparator.comparing` 的新版本依赖，无需 desugar。

## 0.2.2（`versionCode 7`）

- 支持插入/删除/字符格式/段落格式修订，保存 `w:ins`、`w:del`、`w:rPrChange`、`w:pPrChange`。
- `ReviewManager` 以已接受视图坐标保存被删文本与旧格式，支持接受/拒绝、上一条/下一条、作者筛选。
- 批注侧栏支持作者、时间、回复、解决，线程与解决状态写入 `comments.xml` + `commentsExtended.xml`。
- 修订与批注锚点随段级撤销快照一起恢复；导出带修订 PDF 使用独立标记副本，源文档不变。
- 修订测试改用 namespace-aware 元素断言。

## 0.2.1（`versionCode 6`）

- 文件 → 导出 PDF → SAF 位置；范围支持全文、当前物理页、选中段落内文字区间，可选批注、修订、书签；选中内容用隔离模型副本。
- 与页面共用 `PaperPageView.render` 与已布局的文字位置；`PdfCanvas`/`PdfFile`/`PdfTrueType` 输出矢量 PDF，96 → 72 DPI，保留 section 纸型、边距、页眉页脚、`PAGE`、表格、图片与缓存目录。
- 标题生成 PDF outlines，外链生成 URI annotation，批注生成 PDF Text annotation。
- 嵌字改为显式 FontFile2 + CIDFontType2 + ToUnicode，只嵌构建子集，不依赖设备字体。
- 未引入 iText/OkHttp 等运行时库。

## 0.2.0（`versionCode 5`）

- 编辑器框架重构为六个 Ribbon 选项卡（文件、开始、插入、引用、审阅、视图）与快速访问栏。
- 新增导航大纲、厘米标尺、页面阴影、页码/字数/语言/缩放状态栏；阅读模式隐藏工具区。
- 表格/图片/符号上下文工具；手机功能区横向滚动，平板与分屏可缩放，取消竖屏锁定。
- 使用系统夜间主题，编辑页面保持白纸黑字。
- 支持 Ctrl+S/Z/Y/B/I/U、Ctrl+Alt+M、Esc，正文使用系统文本选择与剪贴板菜单。

## 0.1.3（`versionCode 4`）

- 字体从 59 个约 220 MiB 精简为 14 个白名单子集，合计 63,653,408 字节；移除与保留字体在仓库内留备份，不打包进 APK。
- 中文宋/黑/楷/仿宋/小标宋保留完整 cmap 与字号行高度量，去掉内嵌 bitmap 表，宋体 TTC 仅保留在用的 SimSun face。
- Times New Roman 保留四个真实字重；Arial、Calibri、Cambria 保留论文常用 Unicode，完整保留 GSUB/GPOS/GDEF。
- 新增 STIX Two Math 子集（SIL OFL，授权随包），覆盖希腊字母、积分/求和/集合/箭头/上下标与双线数学字符。
- `FontManager` 按选中字形惰性加载，启动不打开全量字体；保存沿用原字体名，移除字体走显式回退。
- 修订拉丁字宽小数与 `wordWrap` 语义记录：`wordWrap` 为 off 时允许西文单词中间断开。

## 0.1.2（`versionCode 3`）

- 标点边界不再叠加中西文间距：`autoSpaceDE/DN` 只作用于汉字与西文/数字之间，`MPa，`、`5、10`、`℃、0.1` 边界不加 1/4 em。
- 行尾标点悬挂：两端对齐段落第二遍布局按字形墨迹宽度计量 Word 会悬挂的行尾 `。，、；：？！ ”`，绘制仍为完整字形。
- Unicode 上下标（`Cu₆Sn₅` 的 `₆₅₃`）按 Times New Roman 原字形原字号渲染，宽度按原字形计量。
- 两端对齐在 API 34+ 使用 `INTER_CHARACTER`，低版本回退 `INTER_WORD`。

## 0.1.1（`versionCode 2`）

- 正文 `snapToGrid + docGrid linePitch=360` 保留 Word 的网格取整余量，不再只用整数文档像素。
- 目录点引导条目按 `docGrid=360` 与 Word 半像素余量分页。
- 图片段落的最小行盒与网格余量与正文统一，图片实际高度不重复挤压后续正文。

## 0.1.0

- 建立原生 Android Java 工程与首页固定文本约束，离线解析、编辑、保存 `.docx`。
- 自研 OOXML 读写：段落、Run 字体、域代码、批注、表格、图片、页眉页脚与多 section。
- 多 section 分页：页面尺寸、页边距、页眉/页脚距离、页码格式、页眉双线；`gutter` 与 `landscape` 归一化。
- 内置 Times New Roman、宋体、黑体、楷体、仿宋等字体，按 `ascii`/`hAnsi`/`eastAsia`/`cs` 字体槽选择。
- 支持 `PAGE`、`NUMPAGES` 等常用域；`docGrid`/`snapToGrid`、中西文自动间距、行距、首行缩进、孤行寡行、`pageBreakBefore`。
- 目录点引导线保留在制表位上，层级缩进按 XML 的 `397/794` twips 读取；`beforeLines`/`afterLines` 优先于 `before`/`after`。
