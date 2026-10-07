# 更新日志

版本号遵循"功能加一版、修复加一位"，每个可安装构建同步递增 `versionName` / `versionCode`。

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
