# Word Lite

Android 上的 `.docx` 论文工作台：本地解析与回写 OOXML，按 Word 的排版规则分页排版，并把查重、AIGC 检测、降重接在同一条编辑链路上。

- 原生 Java + Android SDK，运行时零第三方依赖；构建链路是 aapt2 → javac → D8 → zipalign → apksigner。
- 文档默认全程留在本机。网络只在用户点击某条命令时发生，没有默认服务器、内置密钥或后台上报。
- 当前版本 `1.0.0` / `versionCode 31`，完整变更历史见 [CHANGELOG.md](CHANGELOG.md)。

## 功能

**排版与分页**

- 多 section 页面：纸张尺寸、`gutter`、四边页边距、页眉/页脚距离、页码格式与起始页、`landscape` 归一化、页眉双线。
- Word 行盒：`docGrid` / `snapToGrid` 基线网格、`lineRule`（auto / exact / atLeast）、`beforeLines`/`afterLines` 优先于 `before`/`after`、首行缩进与悬挂缩进、孤行寡行控制、`pageBreakBefore`。
- 版心宽度按最近整数交给 `StaticLayout`（它只吃整数）：向下取整等于每条紧行白送 Word 一个字，实测 20 多处行末剩余恰好 0.9 px。
- Word 断行：`autoSpaceDE`/`autoSpaceDN` 只在汉字与西文/数字之间加 1/4 em（中文标点边界不加）、`w:wordWrap` 长西文串断行、`w:overflowPunct` 行尾标点悬挂、两端对齐走逐字符拉伸。
- 上下标：`w:vertAlign` 与 Unicode 上下标按字体 OS/2 的 `ySuper/ySub` 参数取字号与基线，行宽按脚本字号计量，行高按 Word 的"为升降文字让位"规则增长（见[上下标排版](#上下标与行高)）。
- 表格走 Word 的固定布局：`w:tblGrid` 列宽、`w:gridSpan` 跨列、`w:tblCellMar`/`w:tcMar` 内边距、`w:trHeight` 的 `exact`/`atLeast`、`w:cantSplit`、`w:tblHeader`、`w:vAlign` 与表格 `w:jc`（宽于版心时按 Word 一样定位）。内联图片、点引导目录、页眉页脚域按同一套文档坐标参与分页；`PAGE` / `NUMPAGES` 随分页结果回填并迭代收敛。

**编辑与格式**

- 段落级即时编辑，撤销/重做覆盖文本、格式、插入的图片与表格结构；字符格式（字体四槽 `ascii`/`hAnsi`/`eastAsia`/`cs`、字号、颜色、加粗斜体下划线删除线、上下标、高亮）与段落格式（对齐、缩进、行距、段前段后）往返保存。
- 插入图片、插入表格、符号面板、批注、项目符号与编号列表。
- 打开旧文档时按 OOXML 保留策略回写：未识别的部件、关系、域代码、section 结构原样保留。

**审阅**

- 插入/删除/字符格式/段落格式修订，保存 `w:ins`、`w:del`、`w:rPrChange`、`w:pPrChange`；接受/拒绝、上一条/下一条、作者筛选。
- 批注线程走标准 `comments.xml` + `commentsExtended.xml`，含作者、时间、回复与解决状态；修订与批注锚点随段级撤销快照一起回滚。
- 导出带修订 PDF 使用独立标记副本，源文档不变。

**引用与域**

- `PAGE`、`NUMPAGES`、`SECTIONPAGES`、`DATE`、`TIME`、`FILENAME`、`REF`、`STYLEREF`、`SEQ`、`TOC`（缓存文本 + 点引导线）；复杂域保留原文。

**PDF 导出**

- 自研 `PdfCanvas`/`PdfFile`/`PdfTrueType` 输出矢量 PDF：子集内嵌 TrueType、按 section 还原纸型与边距、页眉页脚、表格、图片、缓存目录。
- 范围可选全文 / 当前物理页 / 选中文字区间；标题生成 PDF 大纲，外链生成 URI annotation，批注与修订生成 PDF annotation。

**字体**

内置 Times New Roman（Regular/Bold/Italic/BoldItalic）、宋体、黑体、楷体、仿宋、方正公文小标宋、STIX Two Math 等，按 `w:rFonts` 的字体槽精确匹配，缺失字体走类别回退再到系统 Noto Serif CJK。上下标参数与单倍行高比例从每个字体文件的 OS/2 表实时读取。

**查重 · AIGC · 降重**

三条链路共用同一份文档模型与同一套高亮/接受机制，命令都在底部"查重"栏触发：

- **查重**：`本地查重`（离线，只用自建库）与`全网查重`（自建库 + 内置检索源）两种模式，输出总相似度比、去除引用重复比、自编率、按来源分布与可点击定位的相似片段。
- **AIGC 检测**：逐句给出机器生成倾向分与整篇 AI 生成比例，附判定依据，独立于查重结果高亮。
- **降重**：选区 / 当前段落 / 全文逐段改写，原文与改写并排 diff，逐条接受、拒绝、重新生成、多建议切换；支持离线规则改写与自定义大模型接口两种后端。
- **报告中心**：每次比对存成一条记录（指标、来源分布、证据表、注记），历史按时间倒序，详情页从盘上重画不重跑比对，点证据跳回正文并高亮那一段；正文被改过时只提示重查，不把高亮打到错位的地方。
- **自建库**：把参考文献、学院论文合集、往届论文导入本机索引，作为离线查重与 AIGC 语料基线。一次可选多个文件（TXT / MD / DOCX / PDF），逐文件回执：成功、正文重复、类型不支持、没有文字层、读取失败。PDF 自己解（对象表线性扫、`ToUnicode` / `/Encoding`、FlateDecode 含 PNG 滤波），扫描版如实报"无文字层"，不冒充一次成功的空导入。

## 查重与 AIGC 检测怎么工作

### 匹配

正文先做归一化（全半角、繁简、标点、空白、序号与前缀剥离），按句子切分后生成字符级 n-gram 指纹与 MinHash 签名；倒排索引按 n-gram 命中候选句，再用片段的 Dice 系数与跨句连续段合并成相似区间。句子级之外还有一条字符指纹带：Schleimer/Wilkerson/Aiken 的 winnowing（7 元组取样、窗口 12），任何连续 18 个字符以上的相同片段必定留下一枚共同指纹，所以两边断句不同、把两句并成一句、或者只在中间加个逗号，整段照抄照样被追出来并成一段报告。这一层里汉字数目字与阿拉伯数字同形（"四十分钟"与"40 分钟"、"七成"与"70%"算同一枚 token），那正是降重写法和录入差异最常动的两处。全部计算在本机完成，比对期间没有正文离开设备。判定线上每一个数的来路分三处可查：[检测阈值标定](docs/detection-calibration.md)（n、w、最短共通指纹数、句级两条下限的网格扫描）、[抗改写召回实测台](docs/rewrite-robustness.md)（17 种改写口径下的召回/归属/噪声，随闸门跑）、[匹配改造方案](docs/paraphrase-robustness.md)（开源实现的参数对照与排序）。

三个指标彼此独立：

| 指标 | 定义 |
| --- | --- |
| 总相似度比 | 命中的相似字符数 / 参与比对的正文有效字符数 |
| 去除引用重复比 | 命中相似但落在参考文献段、显式引号段、脚注段内的部分不计入 |
| 自编率 | 未命中任何来源、且 AIGC 倾向分低于阈值的有效字符占比 |

### 检索源

公开文献库通过内置连接器直接检索，无需配置：

| 来源 | 端点 | 取用内容 |
| --- | --- | --- |
| 知网 | `search.cnki.com.cn`（POST 表单） | 中文期刊、学位论文与会论文的题录加摘要；匿名口是部分索引，见下 |
| 维普 | `www.cqvip.com/search` | 中文期刊与学位论文的摘要、作者、刊名、年期、文献页地址 |
| 万方数据 | `s.wanfangdata.com.cn`（gRPC-web） | 期刊与学位论文的题名、作者、刊名或学校、年份、完整摘要、DOI |
| 国家哲学社会科学文献中心 | `www.ncpssd.org/searchHandler/search` | 社科期刊题录与摘要 |
| OpenAlex | `api.openalex.org/works` | 标题、摘要、开放获取全文链接 |
| Crossref | `api.crossref.org/works` | 题录、摘要、DOI 元数据 |
| Semantic Scholar | `api.semanticscholar.org/graph/v1` | 题录、摘要、TLDR、OA PDF |
| Europe PMC | `www.ebi.ac.uk/europepmc` | 生物医学开放获取全文 |
| arXiv | `export.arxiv.org` | 预印本标题与摘要 |
| CORE | `core.ac.uk` | 聚合开放获取全文（可选配置 API Key） |

开放获取全文按候选题录按需拉取，只在手机上比对；命中结果带来源、题名、作者、年份与标识符写进报告。中文三库里万方开着检索服务：POST 一帧 protobuf 到 `SearchService.SearchService/search`，回来的同样是 protobuf，一次检索连完整摘要一起给回，编解码在 `WanfangProtocol` 与 `ProtoWire` 里手写，不额外引一个 protobuf 运行时。知网另外两个匿名可读的公开页用来按文献号或链接取条目、按刊期看目录（`wap.cnki.net/touch/web/Journal/…`）。检索口 `search.cnki.com.cn` 实测是个**部分索引**：它自己几分钟前刚返回过的文献，按题名精确查只有 3/5 回得来；它也不做短语检索，喂整句或连续 16 个字一律回 0 条，`Order=2`（相关度）是四种排序里唯一能用的。所以中文库给回的是题录加摘要，报告里的相似率是**下限**，"检索覆盖率"与这句说明必须同段出现；手上的题录、PDF 或 Word 走`自建库`导进来就参与比对，机构或代理商的检测 API 接在`接口设置`（`审阅 → 接口设置`，支持文档上传或选区提交、字段映射、超时重试）。

部分移动网络会重置海外源的 TLS 连接：`检索设置 → HTTP 代理`填一个 `host:port` 就能让检索从那里出网。手机上最省事的接法是把电脑上的代理端口用数据线映射进来：`adb reverse tcp:18899 tcp:7897`（7897 是电脑上 Clash 的 mixed 端口），然后填 `127.0.0.1:18899`。代理只隧道 HTTPS，看不到正文；留空即直连，写法不合法也按直连处理。代理那头没人应答（拔了线、忘了 `adb reverse`）时退回直连重试一次，一次查重不会因为代理没接上而整轮失败。知网、万方、维普、哲社中心按域名认成国内源，一律先直连（知网的检索口在 `cnki.com.cn` 下，不在 `cnki.net` 下）；实测同一个知网口走直连与走海外出口召回没有差别，所以这条只省下每扇窗口一次六秒的死路等待。用户在设置里填的代理与自动发现的端口重合时也只试一次。

### AIGC 倾向

不依赖外部服务，特征全部从文本本身算：句长分布的突发度、功能词与转折连接词密度、模板化句式命中率（中文"综上所述/值得注意的是/通过……可以发现"、英文 "Moreover/It is worth noting/In conclusion" 一类）、句首结构重复率、字符二元组熵、标点画像（破折号、分号、引号密度）、以及词汇总量与实词多样度。逐句打分后按字符数加权成整篇比例，并对每句给出触发到的特征名，便于人工复核。

### 降重

改写只发生在"可动区域"。引用 `[12]`、`w:vertAlign` 上下标、域、超链接、公式、计量数值、希腊字母与专业术语、用户在设置里登记的词，全部先掩成不可见标记；模型或规则返回后校验标记是否原样保留，丢失标记的建议直接判为不可接受。接受时把改动写回原 run 样式与锚点，进撤销栈，参与修订记录。参考文献段落默认不改写。

离线规则改写覆盖：把/被字句互换、"由于—因此—所以"因果连接词替换、"对……进行"与动词互换、"的/地/得"结构改写、数字与单位书写形式互换、英文主动被动互换与常见副词替换；与自定义大模型接口混用时，优先用模型结果，失败段落回落到规则改写并标注为规则结果。

## 上下标与行高

上下标是长文档分页最容易跑偏的地方，本版按 Word 的实际行为重写：

- **行宽按脚本字号计量**。Word 把 `w:vertAlign` 的 run 缩到 OS/2 的 `ySuper/ySubXSize` 后取字宽，所以 `…性能提升[18]，其中` 里的 `[18]` 只占小字宽度。此前按基础字号计宽，紧边界的行会提前换行。同一篇论文、同一部手机、同一套字体下复测：同样 37 字的行原来占 562 px，现在 555 px 装下 38 字；一段带 `[11] [12] [14]` 三处引注的文字从 7 行收成 6 行。
- **行高为升降文字让位**。上标顶出单倍行盒时，Word 会撑高这一行；`exact` 行距下不撑高、按裁剪处理。规则实现为逐行取该行上下标的最小必需行盒，与段落行盒取大，因此没有上下标的段落完全不受影响。
- **段落单倍行高不被小字带上**。含上下标的段落按各 run 的实际渲染字号取最高行盒，一个 12 pt 正文里的 8 pt 上标不会把整段行高抬到 12 pt 以上。
- **Unicode 上下标保持原字形**。`Cu₆Sn₅`、`m²` 这类字符用 hAnsi 字体的原始字形全尺寸计量，不替换成普通数字、不再二次缩放。

真机复核不依赖安装：`pwsh tools/capture-device.ps1` 把 `DocxParser` 与 `A4Paginator` 连同随包字体 dex 后在设备上以 `app_process` 运行，逐行导出页码与行末；`pwsh tools/word-parity.ps1 -Impl new` 再把它与 Word 自身导出的页码表按文本对齐，逐段给出页码差。

## 表格分页

Word 的表格不是等分栏：列宽写在 `w:tblGrid` 里，单元格可以 `w:gridSpan` 跨列，内边距由 `w:tblCellMar` 与单元格自身的 `w:tcMar` 决定，行高由 `w:trHeight` 的 `hRule` 决定——`exact` 把行钉死并裁掉多余内容，`atLeast` 只抬高不裁，表格整体的 `w:jc` 决定它在版心里的位置。此前引擎按版心等分列宽、按内容撑高行，一篇四张表的开题报告因此多出一页。现在这几项按 Word 语义实现，并回写进 `w:tblGrid` / `w:trPr` / `w:tcPr`，保存再打开不会掉列宽；增删行列时同步维护 `w:tblGrid` 与 `w:trPr`。

同一篇论文、同一部手机、同一套字体，只把表格几何换成 Word 语义前后的对比：

| | 修正前 | 修正后 | Word |
| --- | --- | --- | --- |
| 总页数 | 29 | 28 | 28 |
| 与 Word 落在同一页的段落 | 156 / 206 | 199 / 206 | — |
| 页码错位段落 | 50 | 7 | — |
| 含上下标段落错位 | 7 / 34 | 1 / 34 | — |

宽于版心的表格照 Word 一样留在页边距里，连贴边的两条竖线也画在版心之外；绘制裁剪跟到纸张物理边界为止。列分隔线的落点按像素验：整页渲染成位图后，`w:tblGrid` 每条列边所在的 x 上纵向取样必须命中那笔灰色栏线，而旧的等分位置必须一根都没有（`TableRenderTest`）。

## 架构

```
DocxZipReader ─┐
               ├─ DocxParser ── DocxDocument（段落/Run/表格/图片/域/批注/修订/section）
OoxmlPreserver ┘                     │
                                     ├─ DocxTextLayout  字形测量：字体槽、上下标、autoSpace、标点悬挂、行距
                                     ├─ A4Paginator     行盒 → 页（section 几何、网格余量、域迭代收敛）
                                     ├─ PageBreaker     分页约束：孤行寡行、keepNext、pageBreakBefore
                                     ├─ PaperPageView   页面绘制（与分页共用同一套布局对象）
                                     ├─ PdfExporter ── PdfCanvas/PdfFile/PdfTrueType
                                     ├─ DocxWriter      OOXML 回写（保留未识别部件）
                                     └─ ReviewManager   修订与批注线程

DuplicateEngine ─┬─ TextCorpus      归一化、句子切分、n-gram/MinHash 指纹、倒排匹配
                 │                └─ Fingerprints   字符级 winnowing 指纹带：跨句连续复制
                 ├─ LocalLibrary    自建库导入与持久化索引
                 ├─ PaperSources    内置检索源查询与响应解析
                 │                └─ WanfangProtocol 万方 gRPC-web 编解码（ProtoWire）
                 ├─ AigcDetector    逐句机器生成倾向分
                 └─ LocalRewriter   离线降重规则；与自定义大模型接口共用 TextProtection
```

关键类：

| 文件 | 职责 |
| --- | --- |
| `DocxParser.java` / `DocxWriter.java` | OOXML 读写与往返保留 |
| `DocxTextLayout.java` | Word 行内排版规则（字体槽、上下标、断行、行距、目录点引导） |
| `A4Paginator.java` / `PageBreaker.java` / `PageGeometry.java` | 页几何与分页 |
| `FontScriptMetrics.java` / `ScriptGeometry.java` | OS/2 上下标参数、升降文字行盒推导 |
| `PdfExporter.java` / `PdfCanvas.java` / `PdfTrueType.java` | 矢量 PDF 输出 |
| `DuplicateEngine.java` / `TextCorpus.java` / `AigcDetector.java` | 查重与 AIGC 检测 |
| `PaperSources.java` / `HttpTransport.java` | 内置文献检索连接器 |
| `TextProtection.java` / `TextRewriter.java` / `LocalRewriter.java` | 降重保护与改写 |
| `SettingsManager.java` / `SecretCipher.java` | AndroidKeyStore AES-256-GCM 加密配置 |

## 构建

```sh
sh tools/build.sh
```

产物 `artifacts/apk/wordlite-debug.apk`。需要 `aapt2`、`javac`、`keytool`、`zipalign`、`apksigner`；`tools/android-35.jar` 与 `tools/d8.jar` 随仓库提供，`AAPT2` 可用环境变量指向本机二进制。
Windows 上没有 Android SDK 时用 `pwsh tools/build-host.ps1`：它用 sdkmanager 把 build-tools 装进 `artifacts/host-tools/`，跑同一套流水线，并修正 Windows aapt2 写出的反斜杠资源路径。

## 测试

```sh
sh tools/test.sh                 # JVM 核心：分页/OOXML、字体资产、真实文档往返
sh tools/test-enhancements.sh    # PDF、OOXML 保留、修订批注、查重/AIGC/降重（本地回环 HTTP）
```

Windows 上跑同一批 JVM 回归（不需要 aapt2）：

```powershell
pwsh tools/test-host.ps1
```

Robolectric 排版与 UI 回归在 `tests/ui`：

```sh
cd tests/ui && gradle --no-daemon test --console=plain
```

`tools/test-host.ps1` 一次跑完 22 个 JVM 套件，2279 条断言：`Regression` 分页/OOXML 70、`ScriptRegression` 上下标行盒 55、`TextCorpusRegression` 指纹比对 183、`SourceLedgerRegression` 来源榜 146、`CharLedgerRegression` 字符账本 100、`CandidateRankerRegression` 候选去重 92、`RoutesRegression` 国内外选路 60、`CorpusImportRegression` 自建库批量导入 131、`AigcRegression` 逐句倾向 157、`LocalRewriteRegression` 离线降重 388、`DetectRegression` 检索/报告/传输 182、`RetrievalCoverageRegression` 检索覆盖率 111、`CnkiSearchRegression` 知网检索式 226、`CnkiTouchRegression` 知网公开页 20、`RewriteRobustnessRegression` 抗改写召回与嵌入多报 58、`ReportStoreRegression` 报告中心存储 175、`ReviewRegression` 修订批注 22、`PreservationRegression` OOXML 保留 11、`ApiRegression` 接口配置与加密 40、`PdfRegression` 9、`OriginalDocxRegression` 真实论文往返 12、`TableGeometryRegression` 表格几何与回写 31。`FontAssetsRegression` 另计 51 条，直接校验 APK 内的字体字节。

联网检索源的解析全部走本地回环服务，避免测试依赖外网；要确认这九个内置源此刻真的能返回题录，跑：

```powershell
java -cp <classes> com.rikkahub.wordlite.LiveEngineProbe engines "论文关键词"
java -cp <classes> com.rikkahub.wordlite.LiveEngineProbe scan tests/samples/input-liu.docx
```

查重的真召回不靠回环桩回答，另有三台联网实测台（同样故意不在闸门里）：

```powershell
pwsh tools/recall-probe.ps1                          # 种一句真论文原文，量召回/检出/归属
pwsh tools/recall-probe.ps1 -Probe CnkiFormProbe     # 十种写法问知网，比哪种问得回那一篇
pwsh tools/recall-probe.ps1 -Probe CqvipAlignProbe   # 维普的文献号与摘要有没有对行
pwsh tools/recall-probe.ps1 -Proxy 127.0.0.1:7897    # 挂代理再量一遍，与直连做 A/B
```

Robolectric 排版与 UI 回归在 `tests/ui`，覆盖行距、断行、目录分页边界、表格列宽与行高、ribbon 与 PDF 导出、页面缩放与 section 页眉页脚坐标、报告中心（历史倒序、详情页从盘重画、点证据跳转与命中区间、证据行回收），共 18 个测试类 69 例（67 例执行，2 例待真机参考取样），Windows 下全绿。
跑这套需要 `build.gradle` 里的 `options.encoding = UTF-8`（已加）：javac 默认按代码页读取，中文断言字符串会全部变成乱码。
渲染类用例统一 `sdk = 28` + `GraphicsMode.NATIVE`：`robolectric.enabledSdks` 只放行 28，请求别的 sdk 的测试类会被静默略过且不报错；`LEGACY` 图形模式的画布不真正落笔，`getPixel()` 恒为 `00000000`，像素断言在它上面只会假通过。


真机取样与逐页比对：

```powershell
pwsh tools/capture-device.ps1 -Serial <serial>   # 设备上跑排版，导出逐行页码
pwsh tools/word-parity.ps1 -Impl new             # 与 Word 页码表逐段对齐，报告页码差
```

`artifacts/word/` 保存 Word 自身导出的页码真值（COM 驱动 Word：28 页、每页 36 个基线网格行、420 个段落序号），`artifacts/device/new|old/` 保存同一篇文档在设备上的排版结果。`tests/samples/input-liu.docx` 的当前结果：引擎与 Word 同为 28 页，206 段可对齐的段落里 199 段落在同一页（96.6%），其余 7 段全部只差一页且集中在同一处；带上下标的 34 段里 1 段错位。断行点本身另有一张表：`tools/word-line-breaks.ps1` 逐字符问 `wdFirstCharacterLineNumber`，把 Word 的真实断行点取回来（这个 Word build 的 `Document.Lines` 返回空集合，取不了现成的行）；`tools/line-break-delta.ps1` 再把它与设备逐行结果按正文对齐——Word 段号靠段落正文证明，不靠固定偏移，实测偏移在 +1 / +7 / +13 / +24 / +44 之间跳，用统一偏移会整批错位。当前 27 段抽样、165 行里 103 行逐字对齐（62.4%），16 段的首个分歧成因分布：西文与数字字宽 4、中西文混排留白 4、长西文串不可断 3、首行缩进计量 2、行尾标点悬挂与禁则 2、上下标计量 1。其中 242 个西文空格是设备导出脚本 `clean()` 丢白的取样口径损失，不是排版差异。

## 与微软 Word 的对照

排版真值取自本机 PC 版 Word 16.0（COM 驱动，逐段导出页码；断行点用 `tools/word-line-breaks.ps1` 逐字扫描取回）。手机版 Office 也做了静态对照（`base.apk.1` 实测为 `com.microsoft.office.officehubrow` 16.0.17328.20180，`minSdkVersion 33`），完整报告在 [docs/ms-office/comparison.md](docs/ms-office/comparison.md)，附字体清单与 native 依赖探针；原始扫描数据留在 `artifacts/ms-office/`。与本项目直接相关的几条：

- 手机版 Office 装不进这台测试机：APK `minSdkVersion 33`，测试机是 Android 10 / API 29，安装期直接 `INSTALL_FAILED_OLDER_SDK`。同机 A/B 做不到，Office 侧只能静态比，运行时真值由 PC 版 Word 提供。
- Word 的排版不在 Java 层：`libwlibandroid.so` 的 `DT_NEEDED` 直接含 `libdwritecore.so` / `librichedit.so`，内部有 181 个 Windows 排版编译单元，分断行/行盒与页面/表格/脚注两层。逐条复刻规则是可行路线，但要认清对手是这两层。
- 它不带任何中文正文字体：APK 内 22 个字体 family 只有 Arial、Calibri、Tahoma 与一批符号字体，无宋体/黑体/楷体/仿宋/等线，也没有 Times New Roman。换到没装这些字体的手机上就换一套字形，行宽随之变。Word Lite 自带 14 个字体文件（安装包 63.9 MB，字体压缩后占 99.61%，全部非字体条目合计 244 KB），同一篇文档在任何机型上字宽一致。
- 安装包体积是两种取舍：Office 343 MB 里 79.5% 是 98 个 `.so`；Word Lite 64 MB 零 native。
- 查重 / AIGC / 降重在 Office 的 APK 里没有任何用户可见入口（`查重`、`相似度`、`AIGC`、`plagiar` 等字符串跨 98 个 `.so` 与 9,856 个条目全部 0 命中，只有一组没接入口的 `AugLoop::Similarity` 类型和服务端开关）。
- 值得抄的一条：它专门带了 Wingdings 1/2/3 与 Symbol 共 226 KB 符号字体，Word Lite 目前没带，`w:rFonts` 指向 Wingdings 的项目符号会渲染成字母。

## 版本

`1.0.0` / `versionCode 31`

- 报告中心：一次比对存成一条可回看的记录，历史列表、详情页、点证据跳正文并高亮那段；上限写死（60 条、单条 512KiB、索引 256KiB），存盘失败必须提示而不是静默少一条。
- 抗改写判据第一次有实测：17 种确定性改写口径 + 召回/归属/噪声三列，全部进闸门。删句子、调语序、并句、跨句拼接打不动判据（97.8%~100%）；换掉一成汉字仍是 99.8%，换掉两成掉到 26.4%，悬崖位置与算式 `1 - ∛0.50 = 20.6%` 对得上。方法与表格见 [抗改写召回实测台](docs/rewrite-robustness.md)。
- 句级 Dice 下限 0.55 → 0.50。标定台在现行代码上重跑，`ENGINE CURRENT` 与 `dice=0.50` 那一行逐位相同（召回 99.5%、误报 471 字、相似字符 3634、命中 60 条），所以相似率的分子一个字符没动，多认回的字全在真被改写过的那批种入段里；停在 0.50 而不去 0.45 的边界写在同一篇文档里。
- 检索与自建库：每扇检索窗口自己决定问几次、中文检索式学会去标点保住术语与数字、自建库支持一次多个文件与 PDF 正文抽取（逐文件回执，扫描版如实报"无文字层"）。
- 知网公开检索入口仍关在滑块验证之后，能匿名取回的是题录加摘要，且实测是部分索引：报告里的相似率是下限，与"检索覆盖率"同段出现。
## 隐私与安全

- 没有默认服务器、硬编码密钥、遥测或后台上传；只有点击查重/降重/测试连接时才使用 `INTERNET`。
- 接口地址与密钥经 AndroidKeyStore 中的 AES-256-GCM 密钥加密后保存，密钥不可导出；允许配置内网 HTTP 服务，但不改变系统 TLS 校验，不跟随重定向。
- 检索源查询只发送检索用的短语（论文标题、段落关键词），全文只在需要比对时按题录拉取；比对与打分全在本机。
- 自建库文件与索引存在应用私有目录，卸载即清除。

## 字体与授权

`app/src/main/assets/fonts/` 内置以下 14 个字体文件；`app/src/main/assets/licenses/` 目前只有 STIX Two Math 的 SIL Open Font License 文本，其余字体随包内置是为跨机型字宽一致，未附各自的授权文本。

| 文件 | 字体 | 用途 |
| --- | --- | --- |
| times-new-roman*.ttf | Times New Roman 四种字重 | 西文正文 |
| arial.ttf / arial-bold.ttf | Arial 常规与粗体 | 西文正文与列表 |
| calibri.ttf | Calibri | 默认西文正文 |
| cambria.ttf | Cambria | 西文标题 |
| song.ttc | 宋体 | 中文正文 |
| simhei.ttf | 黑体 | 中文标题 |
| kaiti.ttf | 楷体 | 摘要、批注 |
| fangsong.ttf | 仿宋 | 公文正文 |
| fz-small-song.ttf | 方正公文小标宋 | 公文标题 |
| stix-two-math.ttf | STIX Two Math | 数学符号 |

字体解析优先级：精确匹配（`DocxFontAssets.pathFor`）→ 类别回退（黑体类 → SimHei，宋体类 → 宋体）→ 系统字体。

## 范围

以下能力在路线图上，当前版本按"保留原文、不参与渲染"处理：

- OMML 公式排版、脚注/尾注正文、浮动对象与文字环绕、SmartArt。
- 西文与数字的字宽仍与 Word 差半个到一个像素级别：紧边界行因此比 Word 少装一个字符，这是剩下 7 段偏一页的主因（逐行差异表按成因排在第一位）。
- 跨页表格按整行搬页，不拆单行（`w:cantSplit` 与 `hRule=exact` 的行本就不可拆，其余超高行也整行搬页，行高大于版心时上一页留白）。
- `w:tblHeader` 已解析并随保存回写，但跨页重复表头尚未参与渲染。
- `TOC` 页码重算（当前显示缓存文本）；`REF`/`STYLEREF`/`SEQ` 为简化语义。
- 双指平移（当前只支持双指缩放）；行内选区拖动。
- EXIF 方向修正；多人协同合并。
- 中文黑体/楷体/仿宋的独立粗体字面（当前用合成加粗）。

## 目录结构

```
app/src/main/java/com/rikkahub/wordlite/   全部 Java 源码
app/src/main/assets/fonts/                 内置字体与授权文本
app/src/main/res/                          Android 资源
docs/ms-office/                            微软 Office 静态对照报告
tests/                                     JVM 回归与真实文档样例
tests/ui/                                  Robolectric 排版与 UI 回归
tests/android/RealLayoutProbe.java         真机排版取样
tools/                                     构建与测试脚本、SDK stub、D8
artifacts/                                 构建产物与测试输出（不入库）
```
