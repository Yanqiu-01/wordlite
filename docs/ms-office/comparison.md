# 微软原版 Office APK ↔ Word Lite 0.4.0：实证对比

分析对象：`artifacts/ms-office.apk` ＝ `com.microsoft.office.officehubrow` 16.0.17328.20180
（versionCode 43915851，应用名 `Microsoft 365 (Office)`），343,748,748 字节。
对照：`artifacts/apk/wordlite-debug.apk` ＝ `com.rikkahub.wordlite` 0.4.0（versionCode 13），63,911,622 字节。
方法：只读静态分析。凡是运行时才知道的，一律写"未验证"。

## 1. 为什么这台手机上做不了运行时 A/B

`aapt2 dump badging` 实测（不是引用别人的说法）：

```
package: name='com.microsoft.office.officehubrow' versionCode='43915851' versionName='16.0.17328.20180'
         platformBuildVersionName='13' platformBuildVersionCode='33' compileSdkVersion='33'
minSdkVersion:'33'
targetSdkVersion:'33'
native-code: 'arm64-v8a'
application-label:'Microsoft 365 (Office)'
```

测试机 HONOR CDY-AN90，Android 10 / API 29。**29 < 33，差 4 个 API 级别。**
本项目的 `adb install` 实测报错原文：

```
INSTALL_FAILED_OLDER_SDK: Requires newer sdk version #33 (current version is #29)
```

（这一行是本项目先前在真机上实测得到的原始输出，本轮按约定未重跑 adb；
上面 `minSdkVersion:'33'` 是本轮从 APK 直接读出来的，两者互相印证，报错原因就是包声明的 minSdk。）

这条限制是包声明层面的，不是签名/架构/存储问题：APK 只有 `arm64-v8a`，这台机器就是 arm64，
架构对得上；换签名、split-select 拆包、`--bypass-low-target-sdk-block` 都改不了
`minSdkVersion` 这个安装期检查。所以"同一篇文档两边各跑一遍导页码"这条路在这台机器上物理不通。

第二个障碍（即使装上也存在，**推测**）：APK 声明了 `com.google.android.c2dm.permission.RECEIVE`、
`com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE`、
`com.google.android.gms.permission.AD_ID` 等 GMS/Play 依赖权限，这台机器没有 Google Play 服务，
Office 的登录与云端能力会受限；具体退化到什么程度未验证。

结论：本文所有 Office 侧结论都来自 APK 字节，排版行为一律以 PC 版 Word 16.0 为排版真值。

## 2. 343MB 到底花在哪

数据见 `inventory.tsv`（按顶层目录）与 `size-breakdown.tsv`（按后缀 + 分类汇总）。压缩后字节占 APK 文件比例：

| 分类 | 条目 | 压缩后 | 占比 |
| --- | --- | --- | --- |
| `lib/arm64-v8a/*.so` | 98 | 273,357,208 | 79.52% |
| `assets/**` | 336 | 25,440,245 | 7.40% |
| `classes*.dex` | 7 | 20,791,254 | 6.05% |
| `resources.arsc` | 1 | 14,324,024 | 4.17% |
| `res/**` | 9,313 | 7,833,236 | 2.28% |
| `AndroidManifest.xml` + `META-INF/**` + 其它 | 199 | 167,330 | 0.05% |
| zip 头/对齐开销 | — | 1,835,451 | 0.53% |

一句话：**近八成是 native 库，且 .so 全部是 store 不压缩**。最大的六个：
`libxlnextandroid.so` 35.4MB、`libwlibandroid.so` 30.5MB、`libmsoandroid.so` 23.0MB、
`libppt_android.so` 21.8MB、`liboartandroid.so` 15.9MB、`libcsiandroid.so` 15.2MB。
Excel/PPT/Word 三个应用主体加共享 MSO 层就吃掉了大半。

字体文件解压后合计 14,601,764 字节；承载它们的 99 个 `.7z` 包在 APK 内压缩后共 15,874,132 字节（4.62%，里面除字体还有资源包）。`assets/lens/models/*.ort` 是 5 个 ONNX 模型（4,157,336 字节），
给 Microsoft Lens 做文档分类/分割用。

同一口径下 Word Lite 0.4.0：字体 `assets/fonts` 14 个文件 63,653,408 字节＝APK 的 99.6%，
除字体外全部条目压缩后合计 247,011 字节，**0 个 native 库**。

```powershell
py build_inventory.py E:\download\claude\Wordlite\artifacts\ms-office.apk .
py probe_apk_shape.py E:\download\claude\Wordlite\artifacts\apk\wordlite-debug.apk
```

## 3. 对比表

Word Lite 侧证据来自 `README.md`、`CHANGELOG.md`、`app/src/main/java`（只读）与 `artifacts/word/`、`artifacts/device/`。

| # | 维度 | Word Lite 0.4.0 | 移动 Office 16.0.17328.20180 |
| --- | --- | --- | --- |
| 1 | 排版引擎实现层 | 纯 Java：`DocxTextLayout` → `A4Paginator` → `PageBreaker`（APK 内 0 个 .so） | native C++。`libwlibandroid.so` 的 DT_NEEDED 直接包含 `libdwritecore.so`/`libdwriteshim.so`/`libgdi.so`/`libwic.so`/`librichedit.so`；内部有 `Mso::DWriteAssistant::MsoCreateTextFormat` 等符号 |
| 2 | 引擎规模 | 排版三件套源码合计 93,492 字节（`A4Paginator` 29,542 + `DocxTextLayout` 57,877 + `PageBreaker` 6,073） | `libwlibandroid.so` 内嵌 181 个 Windows 编译单元路径，分两层：`ptls7/ls` 94 个（断行/行盒）+ `ptls7/pts` 85 个（页面/节/表格/脚注） |
| 3 | 分页一致性验证方法 | 实测：`tools/capture-device.ps1` 把排版 dex 后用 `app_process` 在真机跑，`tools/word-parity.ps1` 与 COM 驱动的 PC Word 页码表逐段对齐。`tests/samples/input-liu.docx`：206 段可对齐，199 段同页（96.6%，表格几何按 `w:tblGrid` 修正前为 156 段），正文第一章到表 5-1 题注逐页一致 | **未验证**（装不上）。静态只能给出引擎规模与模块划分，给不出一页的页码 |
| 4 | 上下标 `w:vertAlign` | 按 OS/2 `ySuper/ySubXSize` 取脚本字号，行宽按脚本字号计量，行高按 Word 让位规则逐行增长；`ScriptGeometry` + `ScriptRegression` 55 条断言。实测把带 `[11][12][14]` 的一段从 7 行收成 6 行 | 有专门模块：`fsshiftapi.cpp`、`fsshiftoff.cpp`，基线信息另有 `fsbaselineinfoapi.cpp`。行为**未验证** |
| 5 | 表格跨页断行 | 一行＝一个不可分块（`A4Paginator.java:261` 注释：等同 `w:cantSplit`），比页高的行自己溢到下一页。**`w:tblHeader`（重复表头）未解析，代码 0 处引用** | 表格服务 11 个源文件（`fstablesrv*.cpp`），行定位有 Word 兼容套（`fstablesrvrowposword.cpp`/`fstablesrvrowword.cpp`）与另一套（`fstablesrvrowgood.cpp`）并存。行为**未验证** |
| 6 | 目录与域 | `PAGE`/`NUMPAGES`/`SECTIONPAGES`/`DATE`/`TIME`/`FILENAME`/`REF`/`STYLEREF`/`SEQ`/`TOC`（缓存文本 + 点引导线），复杂域保留原文；页码域随分页迭代收敛（`A4Paginator` 里的 stable 循环） | **未验证**。APK 内有 `assets/sortdefault.nls`（2,969,292 字节排序表）、`libmsxml.so`、`libxmllite.so`，但域求值行为看不出来 |
| 7 | 修订与批注 | `w:ins`/`w:del`/`w:rPrChange`/`w:pPrChange` 往返；接受/拒绝/上下条/作者筛选；批注走 `comments.xml` + `commentsExtended.xml`；`ReviewRegression` 22 条 | **未验证** |
| 8 | PDF 导出 | 自研 `PdfCanvas`/`PdfFile`/`PdfTrueType`，矢量输出 + TrueType 子集内嵌，按 section 还原纸型边距，范围可选，标题转 PDF 大纲，批注/修订转 annotation；`PdfRegression` 9 条 | `libPdfium.so` 4,650,008 + `libPdfControl.so` 6,964,472 + `libPdfJni.so` 837,944 + `libPDFManipulator.so` 551,256。能力大概率强于 Word Lite，但导出结果**未验证** |
| 9 | 字体内置策略 | 随包 14 个字体文件 63,653,408 字节：宋体(`song.ttc`)、黑体、楷体、仿宋、方正公文小标宋、Times New Roman ×4、Arial ×2、Calibri、Cambria、STIX Two Math | 32 个字体文件、22 个 family，**没有一个 CJK 字体，也没有 Times New Roman / Cambria / Consolas**。正文用只有 Arial(含 Narrow/Black)、Calibri(含 Light)、Tahoma，加 Symbol/Wingdings×3/Segoe UI Symbol/MS Office Symbol×9 这些符号字体 |
| 10 | 中文论文排版 | `docGrid` 基线网格、`snapToGrid`、`lineRule`、`beforeLines`/`afterLines` 优先、首行/悬挂缩进、孤行寡行、`pageBreakBefore`、`w:wordWrap`/`w:overflowPunct` 往返 | `resources.arsc` 带 `zh-rCN` 2,650 条中文字符串，但 `脚注`/`尾注`/`目录`/`网格`/`分页符` 全 0 命中——这些是 App 外壳文案，Word 文档层 UI 串在 native 包里；而 native 侧只装了 en-US（`assets/en-US/`）加 `res.7z` 里的 es-ES/fr-FR/id-ID/pt-BR/tr-TR 五种，中文 Word 文档界面串不在这个 APK 内，装完联网再取。所以这一格只能给"部分可证" |
| 11 | 查重 | 有：`DuplicateEngine` + `TextCorpus`（n-gram 指纹/MinHash/倒排/Dice）+ 6 个开放检索源 + `LocalLibrary` 自建库；输出总相似度比/去引用重复比/自编率 | **无**。全 APK 检索 `查重`/`重复率`/`相似度`/`plagiar` 全 0 命中。唯一相关是 `Microsoft::Office::AugLoop::Similarity::{SimilarSource, SimilaritySummary, CitationSuggestion, ...}` 一套云端对象类型 + `EditorSDX.Similarity{,Enterprise,Consumer}Enabled` 三个服务端开关 + flight 名 `EnableSimilarityChecker`；APK 内 0 条用户可见字符串、0 个布局 → 无可点击入口，能否服务端开启**未验证** |
| 12 | AIGC 检测 | 有：`AigcDetector` 逐句机器生成倾向分（句长突发度、连接词密度、模板句式、熵、标点画像等）+ 整篇比例 | **无**。`AIGC` 0 命中。8 处 `AI generated` 全是 Copilot 免责声明（`idsCopilotAIGenerated`、`feedback_ai_disclaimer`、`tooltip_content_ai_gen` 等），语义是"自己标注"不是"检出别人" |
| 13 | 降重 | 有：`LocalRewriter` 离线规则后端 + 自定义大模型接口，共用 `TextProtection` 掩码，模型失败回落规则；`LocalRewriteRegression` 388 条 | **无**。`降重`/`改写`/`paraphras` 全 0 命中 |
| 14 | 离线与隐私 | `uses-permission` 恰好 1 条：`android.permission.INTERNET`。网络只在命令点击时发起，无默认服务器、无硬编码 Key，接口配置走 AndroidKeyStore AES-256-GCM | `uses-permission` 49 条，含 `INTERNET`、`MANAGE_EXTERNAL_STORAGE`、`POST_NOTIFICATIONS`、`READ_MEDIA_*`、`BLUETOOTH`、`RECEIVE_BOOT_COMPLETED`、GCM `com.google.android.c2dm.permission.RECEIVE`、`com.google.android.gms.permission.AD_ID`、Play `BIND_GET_INSTALL_REFERRER_SERVICE`。实际联网行为**未验证** |
| 15 | APK 体积 | 63,911,622 字节（其中字体 99.6%） | 343,748,748 字节 ＝ Word Lite 的 5.38 倍 |
| 16 | 最低系统版本 | `minSdkVersion:'23'`（Android 6.0），`targetSdkVersion:'35'` | `minSdkVersion:'33'`（Android 13），`targetSdkVersion:'33'`。这就是本机装不上的原因 |
| 17 | 未知 OOXML 保留 | 打开旧文档按保留策略回写：未识别部件、关系、域代码、section 结构原样保留（`PreservationRegression` 11 条） | **未验证**（静态无法证明回写行为） |
| 18 | 文档格式识别 | docx 读写 + PDF 导出 | 额外带 ODF 支持线索：`assets/officeandroid.odf`（1,708,720 字节）；`.nls` 代码页表 30 余个（含 `c_936`/`c_950`）。用途推断为 script/字体元数据表，**未验证** |

## 4. 可以从这个 APK 抄的东西

按"能不能直接落地"排序。每条都注明 APK 内来源条目。

1. **`symbol.ttf` / `wingding.ttf` / `wingdng2.ttf` / `wingdng3.ttf` —— Word Lite 现在完全没有 Wingdings。**
   来源：`assets/canvasFonts.7z` 内的 `fonts/symbol.ttf`(70,128)、`fonts/wingding.ttf`(55,620)、
   `fonts/wingdng2.ttf`(65,788)、`fonts/wingdng3.ttf`(35,328)，合计 226,864 字节。
   Word Lite 侧 `Wingding` 在 `app/src/main` 全 Java 里 0 处命中（`Symbol` 只在
   `DocxFontAssets.java:116` 当作数学字体名认了一下）。
   而 `.docx` 的项目符号 frequently 写 `<w:rFonts w:ascii="Wingdings"/><w:tab/>l`——
   Word 专门带这四个字体，就是在保证这类列表渲染出来是实心方块/箭头而不是字母 `l`。
   这四个文件体积小、命名表是标准格式（`probe_ttf_names.py` 已读出 family），可以直接收进
   `app/src/main/assets/fonts` 并在 `DocxFontAssets` 里加映射。

2. **正文用字体的度量对齐：Calibri / Calibri Light / Arial / Arial Narrow / Tahoma 的完整字重。**
   来源：`assets/canvasFonts.7z`（`arial.ttf`、`calibri.ttf`、`calibril.ttf`）与
   `assets/canvasFontsAssetPack.7z`（`arialbd/ariali/arialbi`、`arialn*`×4、`ariblk`、
   `calibrib/calibrii/calibriz/calibrili`、`tahoma/tahomabd`、`seguisym.ttf`）。
   Word Lite 目前只有 `calibri.ttf` 一个 Calibri，没有 Calibri 粗体/斜体的真字体。
   Word 把 Calibri 六个字重全带齐（Regular/Bold/Italic/BoldItalic/Light/Light Italic），
   说明 `w:b`/`w:i` 在 Calibri 段落上必须换真字体而不是算法加粗，否则行宽就偏。
   这是能直接解释分页差异的一条，值得按字重补齐。
   **注意版权**：这些是微软授权字体，APK 里的许可串只覆盖 Segoe（`segoeui_variable_static_display.ttf`
   的 name 13 有 "Microsoft supplied font..."），其余按 Arial/Calibri 各自 EULA 处理，别直接分发二进制。
   要度量可以从开源同度量字体（Carlito/Caladea/Liberation）走。顺带一条实测：
   `libmsoandroid.so` 的 UTF-16LE 字体名列表里有 `LIBERATION SERIF`、`LIBERATION SANS`、`DEJAVU SANS`
   （邻居是 `KELLYANNGOTHIC`、`SEGOE LIGHT`、`MYRIAD PRO`、`ZAPFDINGBATS` 这类字体名，全大写形式），
   说明 Word 认这些替代字体名。注意这只证明「名字被认识」，不等于替换算法如此。

3. **CJK 回退的目标名清单。**
   来源：`libmsoandroid.so` / `libwlibandroid.so` 的 UTF-16LE 字符串池，出现
   SimSun、SimHei、KaiTi、等线（DengXian）、PMingLiU、Microsoft JhengHei、MS Mincho、MS Gothic、
   Yu Gothic、Meiryo、Malgun Gothic、宋体、黑体、楷体、仿宋、方正。
   证据强度说清楚：这些名字落在大字符串池里（`宋体` 的邻居是 `細明體`、`돋움` 和
   `HyphenationNotSupported`、`DownloadProofingResourceFailed`），**只能证明 Word 按名字认识这些字体族，
   具体映射表静态读不出来**。唯一能读到的机制入口是符号名
   `Mso::DWriteAssistant::ResourceManager::GetDWriteFontFallback`。
   能抄的是"要认的名字集合"：Word Lite 的 `DocxFontAssets` 目前认 `ascii/hAnsi/eastAsia/cs` 四槽 +
   类别回退 + Noto Serif CJK，可以对照补 `等线/DengXian`（Word 2016+ 中文默认正文体）和
   `仿宋_GB2312`（公文常用，Word Lite 现在没有这个别名）。这条属于低成本高收益。

4. **`officeandroid.odf` 的 script→默认字体/字号表结构。**
   来源：`assets/officeandroid.odf.7z` → 1,708,720 字节。里面能看到 `Calibri`、`10pt`、`9pt`、`8`、
   `normal` 与 `Kannada`、`Hanunoo`、`Kayah Li`、`Kaiti` 这类 script 名混排，推测是每种 script 的
   默认字体与默认字号表。**结构未解析**。如果哪天 Word Lite 要做"文档没写字体时按 script 取默认"，
   这是唯一一份能对照的实物，值得单独排一次结构。

5. **Word 的中文默认正文看起来是 SimSun 12pt。**
   来源：`libmsoandroid.so` 里的 UTF-16LE 字符串 `SimSun, 12, SimSun, 12`（同一字符串池里
   另有一条孤立 `SimSun`，邻居是 `SpellCheckOff`、`PresentationFormat`、`TitlesOfParts`）。
   形似 `字体, 字号, 字体, 字号` 的配置默认值，与中文论文「小四宋体」的习惯一致。
   用途属**推测**（没解析出配置表结构），但对 Word Lite 有直接价值：当 `.docx` 的 `styles.xml`
   没写 `w:sz`、或 `docDefaults` 缺省时，取 12pt 还是 10.5pt 会直接改变每页行数。
   这条要 Word Lite 自己拿真文档验过再采纳。

6. **`assets/chromeFonts/offsymand.ttf.7z` → `MS Office Monochrome Icons`，14,056 字节。**
   Word 给 Android 单出了一个 icon 字体（名字后缀 `and` 推测＝Android），把界面图标当字体装。
   这条不是排版结论，是"APK 体积优化到 14KB 级别"的一个参考做法，**推测**。
   顺带纠个容易踩的坑：`offsym*.ttf` 十个文件是 `MS Office Symbol <字重>` 系列（Medium/Semibold/
   Thin/Light/Bold/Extrabold/Extralight/Semilight/Black + Check 两个字重），是界面符号字体，
   **不是数学字体**，别当成 Cambria Math 的替代品收进来。

## 5. 移动 Office 做不到、Word Lite 已经有的

1. **中文正文字体随包。** Word Lite 带 宋体/黑体/楷体/仿宋/方正公文小标宋；这个 Office APK 里
   22 个字体 family 无一 CJK。Word 的引擎按名字要 SimSun，但包里没有 SimSun 文件，只能用设备上
   现成的 CJK 字体顶（HONOR 上具体顶成哪个，**未验证**）。
   后果：**同一篇论文在两台不同 OEM 的安卓机上，移动 Word 的分页本身就可能不同（推测，未验证）**。
   Word Lite 随包字体意味着跨设备分页确定。这是静态可证的策略差异，也是 Word Lite 最该守住的一点。
   Times New Roman / Cambria 同理：西文正文真字体 Word Lite 有、移动 Office 没有。

2. **查重 / AIGC 检测 / 降重整条链路。** Word Lite 五个类
   （`DuplicateEngine` 19,064 + `TextCorpus` 44,565 + `AigcDetector` 17,631 +
   `LocalRewriter` 29,120 + `LocalLibrary` 16,043 字节），离线可用、结果带可点击定位。
   移动 Office 侧：查重/AIGC/降重 三个词跨 98 个 .so + 9,856 个 APK 条目 + 45.8MB 原生资源全 0 命中。
   唯一沾边的 AugLoop Similarity 是"找相似来源并建议引用"的云端对象模型，无中文、无入口，
   且形态是补引用不是给重复率。

3. **离线可用 + 权限面。** Word Lite 1 条权限，Office 49 条。Word Lite 的查重/降重可以完全走
   自建库与本机规则（`LocalRewriter`），不联网也能出结果；Word Lite 没有默认服务器、没有硬编码 Key。

4. **可验证的分页真值链路。** `capture-device.ps1` + `word-parity.ps1` 能在不安装任何对手的前提下，
   把 Word Lite 的逐行页码和 PC Word 的页码表对齐并给出页码差分布。移动 Office 在这台机器上
   连"跑一遍"都做不到，也就没有任何东西能动这条链路。

5. **体积。** 61MB 对 328MB。Word Lite 的 61MB 里 60.7MB 是字体（也就是排版精度的直接来源），
   逻辑部分压缩后 247,011 字节。

6. **minSdk 23。** Android 6.0 起可用；移动 Office 16.0 要 Android 13。这台 Android 10 的机器
   能装 Word Lite、装不上 Office，本身就是这一行的注脚。

## 6. 复现

```powershell
# 全部在 E:\download\claude\Wordlite\artifacts\ms-office\ 下
py build_inventory.py E:\download\claude\Wordlite\artifacts\ms-office.apk .
py build_fonts.py E:\download\claude\Wordlite\artifacts\ms-office.apk `
    E:\download\claude\Wordlite\app\src\main\assets\fonts fonts.tsv
py append_font_names.py fonts.tsv extract
py probe_apk_shape.py E:\download\claude\Wordlite\artifacts\apk\wordlite-debug.apk

$aapt = 'E:\download\claude\Wordlite\artifacts\host-tools\android-sdk\build-tools\35.0.0\aapt2.exe'
& $aapt dump badging E:\download\claude\Wordlite\artifacts\ms-office.apk
& $aapt dump resources E:\download\claude\Wordlite\artifacts\ms-office.apk |
    Set-Content _resources_dump.txt -Encoding UTF8

py feature_probe.py E:\download\claude\Wordlite\artifacts\ms-office.apk
py unpack_native_res.py E:\download\claude\Wordlite\artifacts\ms-office.apk extract\native-res
py sweep_dir.py extract\native-res
py sweep_dir.py extract\_libs_all
py probe_dt_needed.py extract\libwlibandroid.so
py probe_srcpaths.py extract\libwlibandroid.so
py probe_ascii_context.py similarity extract\_libs_all\libwlibandroid.so
py probe_pool_neighbors.py EnableSimilarityChecker extract\classes4.dex
py probe_ttf_names.py extract\native-res\offsym.ttf\offsym.ttf
py font_roster.py extract\native-res\officeandroid.odf\officeandroid.odf
```

`unpack_native_res.py` 用 py7zr 解不开的 BCJ2 过滤会失败，本轮改用官方
`7zr.exe`（本目录，602,624 字节，来自 7-zip.org）解出全部 81 个包，0 失败。
第一次 py7zr 扫描漏掉的 16 个包含 `wintlandroid.dll`、`msointlandroid.dll`、`res.7z`，
重扫后的结论见 `feature-probe.md`。

原始中间产物：`_entries.csv`（9,954 条目清单）、`_resources_dump.txt`、`_feature_probe.txt`、
`_srcpaths_wlib.txt`、`_native_res_manifest.txt`。



