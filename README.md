# Word Lite（Android .docx 查看/编辑器）

一个本地优先的 Android `.docx` 阅读、编辑和格式预检应用，目标是尽量接近 Word 2024 的论文工作流。

## 版本记录

> 项目此前没有写入 Android `versionCode` / `versionName`，因此早期多轮改动没有可靠的正式版本号。现在开始每个可安装构建都递增版本号；旧的未标记改动统一归档到 `0.1.0`。

### 0.3.4（顶部 Ribbon + 正文/查重底部双栏；换行属性补齐）

- `versionName=0.3.4` / `versionCode=12`。
- 编辑器功能区恢复到页面顶部，选项卡为：文件、开始、插入、引用、审阅、视图；文件页提供打开、保存、另存为、导出 PDF、关闭。
- 删除顶部的“论文”选项卡及查重/降重命令。页面底部只保留左右等宽的“正文”和“查重”两栏；选中“查重”后显示查重、降重、接口设置三个入口，网络请求仍只由实际命令点击触发。
- 修复底部栏横向布局错误：状态栏、查重入口和底部双栏改为真正的纵向底部区域，不再把整组控件挤在一行。
- OOXML 段落模型新增并往返保存 `w:wordWrap`、`w:overflowPunct`；换行器在 Android 33+ 对包含长西文/数字串的段落启用 Word word-wrap 规则，悬挂标点只在文档明确声明 `overflowPunct` 时启用。
- 修订上下标 ReplacementSpan 的宽度取整改为向上取整，避免上标引用在紧边界处少占一个像素导致换行提前；Unicode 上下标继续使用原始字形，不替换为普通数字。
- 保留旧文档、PDF、批注/修订、字体白名单和 API 加密配置链路。
- 验证：JVM 核心 70+51+12 项、增强 9+11+22+30+139 项通过；UI 回归 51 项（49 通过、2 项 Word 原生断行测试跳过）；x86 原生 PDF 图形测试 1 项通过。

### 0.3.3（底部命令栏重构：借鉴 M365 Android）

- `versionName=0.3.3` / `versionCode=11`。用户明确指出不要把电脑端 Ribbon 搬到顶部，应借鉴 M365 安卓端底部命令栏模式。
- 删除顶部所有元素：返回/撤销/重做图标、文件名标题、折叠按钮、选项卡行。页面区上方不再有任何工具栏。
- 新增底部命令栏（RibbonUI 重写）：选项卡行 + 命令行，位于屏幕最底部。选项卡：开始、插入、引用、审阅、视图、**论文**。
- "论文"选项卡包含：查重、降重、API 设置、另存为、导出 PDF。
- "开始"选项卡包含撤销/重做（原顶部快捷访问的功能移入此处）。
- 状态栏（页码/字数/缩放）缩为底部命令栏下方一行小字。
- 阅读模式隐藏底部命令栏和状态行。
- 首页固定文本不变；分页/绘制/数据链路不变。
- UI 回归 51 项（49 通过 + 2 项 Word 原生断行跳过）；JVM 核心 70+51+12+9+11+22+30+139 项全部通过。

### 0.3.2（界面修整：按用户实机截图反馈）

- `versionName=0.3.2` / `versionCode=10`。用户实机截图指出顶部标尺和保存框图标粗糙多余，功能区字体观感差；本版只改编辑器外观，不动分页、绘制与数据链路。
- 删除 `RulerView` 与视图选项卡中的“标尺”命令；页面区上方不再有厘米刻度条。
- 快速访问栏去掉画成空心矩形的“保存”图标（另存仍走 文件 → 另存为 / Ctrl+S）；返回、撤销、重做、折叠改为统一 2dp 圆角描边矢量图标。
- 功能区字体改用系统 `sans-serif-medium`：选项卡 14sp，选中项加 2dp 品牌蓝下划线而不是仅变色；命令 13sp medium，字距统一；B/I/U 使用衬线粗体/斜体/下划线以贴合文字含义；分隔线改为半透明细线。
- 顶栏标题 14.5sp medium；点击项使用系统 selectableItemBackground 触摸反馈。浅色模式功能区改纯白底。
- RibbonUITest 更新：命令点击先选对应选项卡；新增断言“标尺命令已移除、选项卡为 TextView”。
- 首页固定文本与全部文档功能未变；构建、测试和哈希见 `releases/0.3.2/`。

### 0.3.1（收口交付）

- 按用户要求，本轮停止扩展并收口在 `versionName=0.3.1` / `versionCode=9`；0.3.0 为开发阶段，功能并入本版，未单独发布。
- 交付字体精简/惰性加载、Ribbon/导航/标尺、PDF导出、修订/批注线程及自定义查重/降重API。首页空文件状态仍精确保持 `Word Lite → 打开文档 → 最近文件 → 暂无文件`。
- 构建仍为 aapt2 → javac → D8 → zipalign → APK签名；v1/v2/v3与版本信息须经最终产物验证。
- 顶层交付固定为 `/workspace/wordlite-debug.apk`；版本归档为 `releases/0.3.1/wordlite-0.3.1.apk`。源码、字体资产、测试fixture、构建测试日志与SHA256随版本留档。
- 最终验证结果以 `reports/current/validation-0.3.1.md` 和本版归档为准；真机Word逐像素排版、完整OMML/脚注/浮动对象渲染、AndroidKeyStore真机验证和商业API联调不在已验证成果内。
- 完成构建后等待用户根据实际成果给出下一步指示，不继续提升版本或扩展任务。

### 0.3.0（开发阶段，并入 0.3.1）

- `versionName=0.3.0` / `versionCode=8`；原生 Android Java 与手工构建链路不变，运行时仍无第三方依赖。
- 文件 → 设置：分别配置查重/降重 URL、API Key、HTTP 方法、请求头 JSON、正文模板、响应路径、上传字段、超时/重试、保留术语；密钥和整份配置经 AndroidKeyStore AES-256-GCM 加密后本地保存。
- 审阅 → 查重：上传当前编辑过的 DOCX（需文档上传模式）或提交选区，显示重复率/相似片段/来源/时间，页面临时高亮相似片段、导出离线 HTML 报告。
- 审阅 → 降重：选区/当前段落/全文逐段请求，原文/改写对比、逐条接受/拒绝/重新生成和多个建议选择。参考文献段落不自动改写。
- 引用/上下标/域/链接/计量/希腊数学符号/配置术语作为保护片段；丢失保护标记的建议不允许接受。修改只发生在这些片段之间，保留原 run 样式和锚点，支持撤销及修订。
- 只有用户点击请求或测试连接时才使用 INTERNET；没有默认服务器、硬编码 Key、遥测或后台上传。允许用户配置 HTTP 内网服务，不改变系统 HTTPS证书验证；不自动跟随重定向，避免凭据转发。重试使用稳定请求 ID。
- JSON模板 `{text}` / `{content}` / `{key}` / `{filename}` 可放在 JSON值或字符串内部，正文文本内的同名 token不二次展开。GET用配置文本字段作为 query参数；multipart上传文件字节。
- 结果映射支持 `data.field` / `$.data[0].field`，可选择重复率0–1及Unicode codepoint offset，页面与OOXML采用UTF-16偏移。
- 本地 loopback HTTP测试覆盖超时/重试/鉴权/不跟随重定向/模板/响应映射/保护片段/报告转义，未向真实外部API提交任何用户文档。真实服务需按其返回结构配置，未声称与任何商业平台已联调。
- 旧文档复杂 OMML、脚注和浮动对象的完整页面渲染仍有边界；本阶段不将XML保留当作渲染已实现，详见当前能力边界。
- 最低API23兼容审计去除 `List.sort`、`removeIf`、`Float/Double.isFinite` 和 `Comparator.comparing` 的新版本依赖；使用原链路无需核心库desugar。
- 本阶段功能随 0.3.1 收口交付，构建与验证日志、可安装APK及源码资产快照见 `releases/0.3.1/`。

### 0.2.2（审阅阶段）

- `versionName=0.2.2` / `versionCode=7`；支持插入/删除/字符格式/段落格式修订，保存 w:ins/w:del/w:rPrChange/w:pPrChange。
- ReviewManager 使用已接受视图坐标保存被删除文本与旧格式，支持接受/拒绝、上一条/下一条、作者筛选。
- 批注侧栏支持作者/时间、回复/解决；用标准 comments.xml + commentsExtended.xml 保留线程与解决状态。
- 修订信息存入段级撤销快照，编辑文本、修订和批注锚点一起恢复。
- 导出带修订 PDF 使用独立标记副本，不修改源文档；批注包括回复与标准 PDF annotation。
- 不支持多人实时合并；复杂嵌套跨段移动修订保留原 XML，未声称有完整 Word 合并引擎。
- 完整 UI 回归按测试类隔离后 41 项执行通过、2 项 Word 原生断行目标跳过；不再受 Paint shadow 静态串扰影响。16 项修订／批注往返断言通过。此前接受修订测试误匹配 `w:instrText`，已改用 namespace-aware 元素断言而非字符串前缀。
- 阶段回归、构建和签名哈希见 `releases/0.2.2/`。本版起源码快照包含字体/授权资产和资产 SHA256，便于复建。

### 0.2.1（PDF 阶段）

- `versionName=0.2.1` / `versionCode=6`；文件 → 导出 PDF → SAF 位置。
- 全文/当前物理页/选中段落内文字范围，批注/修订/书签选项。选中内容使用隔离的模型副本，不修改文档。
- 与页面共用 `PaperPageView.render` 和 Android 已布局的文字位置；自研 `PdfCanvas/PdfFile/PdfTrueType` 写标准矢量 PDF，按 96 → 72 DPI 转为 PDF point，保留 section 纸型/边距/页眉页脚/PAGE、表格、图片和缓存目录。
- 标题生成标准 PDF outlines；外部 HTTP(S)/mailto 链接生成 URI annotations；批注生成 PDF Text annotations。
- 原 `PdfDocument` 方案在测试环境缺少 JNI 后端（document is closed），改为显式 FontFile2 + CIDFontType2 + ToUnicode 嵌字。仅嵌入已用字体的构建子集，不依赖设备是否安装该字体；PDF最终像素需真机复核。
- x86_64 JRE + QEMU 已执行真实 Android native graphics 输出验证：两页 A4、当前页、选区 PDF均能严格解析，正文抽取一致，宋体/Times/STIX真字体嵌入已验证。短文输出约 7 MiB（整份构建子集仅嵌入一次，尚未按该PDF动态裁字）。
- 修订显示选项在审阅阶段接入；当前只保留该选项。当前页面对复杂 OMML 和脚注仍有既有能力边界，PDF不会自动补齐原视图没渲染的对象。
- 未新增 iText/OkHttp 等运行时库。测试/哈希见该版留档。

### 0.2.0（Ribbon 阶段）

- `versionName=0.2.0` / `versionCode=5`，只重构编辑器框架，不改分页坐标/首页固定文本。
- 六个 Ribbon 选项卡：文件、开始、插入、引用、审阅、视图；快速访问另存为/撤销/重做/折叠。
- 导航大纲、物理厘米标尺、页面阴影、页码/字数/语言/缩放状态栏；阅读模式隐藏工具区。
- 表格/图片/符号上下文工具，手机功能区横向滚动，平板/窗口可缩放，不再锁定竖屏。
- 使用系统夜间主题；编辑页面维持白纸黑字以免改变打印文档。
- 支持 Ctrl+S/Z/Y/B/I/U、Ctrl+Alt+M 和 Esc，正文使用标准文本选择/剪贴板菜单。
- PDF、修订增强和 API 在后续阶段接入，不把尚未实现的入口算作功能完成。
- 该阶段构建/测试及 APK 哈希见 `releases/0.2.0/`。

### 0.1.3（字体精简阶段）

- `versionName=0.1.3` / `versionCode=4`，保留 Android Java 和原构建链路。
- 字体由 59 个、约 220 MiB 的全量资源改为 14 个白名单子集；子集字体合计 63,653,408 字节（60.70 MiB），以 `reports/current/font-subsets.json` 为准。
- 46 个移除字体在 `resources/fonts/removed-fonts-backup/` 留备份；13 个保留字体的原件在 `resources/fonts/subset-originals/` 留备份，不打包进 APK。
- 中文宋/黑/楷/仿宋/小标宋保留原来完整 cmap 和字号/行高度量；去掉内嵌 bitmap 表，宋体 TTC 仅保留使用中的 SimSun face。
- Times New Roman 保留四个真实字重，Arial regular/bold、Calibri、Cambria 仅保留论文常用 Unicode；完整保留 GSUB/GPOS/GDEF 特性。
- 新增 STIX Two Math 子集（SIL OFL，授权在 assets/licenses），覆盖希腊字母、积分/求和/集合/箭头/上下标及双线数学字符，保留 MATH/GSUB/GPOS。
- FontManager 只注册应用 Context，按选中字形/字体加载；不在启动时打开全量字体。保存仍使用原字体名，移除字体走显式回退。
- 构建工具使用系统 aapt2；新增仅构建时 FontTools 4.60.1，无新增运行时依赖。
- 注意：0.1.2 的“末行字宽仿真即已对齐”结论不充分；原仿真舍掉拉丁字宽小数。`wordWrap` 的 ISO 示例为 off 才允许西文单词中间断开，先前历史描述反了。本文不再声称已经与原生 Word 逐像素一致。
- 测试和签名哈希见该版本 `releases/0.1.3/` 归档日志。

### 0.1.2（历史归档）

**构建标识**

- `versionName`: `0.1.2`
- `versionCode`: `3`
- APK：`/workspace/wordlite-debug.apk`
- SHA-256：`7ec665211e6396b02c1b271ca18b9ebe46a516469ba59efd9b2667a7ec214e73`
- 本版本针对“行数对但换行位置不对”的 Kim 段问题，按最新 Word 截图逐像素定标后修复断行规则。

**本版本修改（断行规则，全部有像素级证据）**

- **标点边界不加中西文间距**：`autoSpaceDE/DN` 现在只作用于“汉字↔西文/数字”，`MPa，`、`5、10`、`℃、0.1` 这类“中文标点↔西文/数字”边界不再加 1/4 em。此前每行多计 4–8px，是 Kim 段每行比 Word 少塞字的主因之一。
- **行尾标点悬挂（`overflowPunct`）**：两端对齐段落第二遍布局，把 Word 会悬挂的行尾 `。，、；：？！ ”` 按字形墨迹宽度计量（宋体实测墨迹占比），断行与 Word 的悬挂语义等价；绘制仍是完整字形，行中位置不变。实测 Kim 段第 8 行由 `说明300 ℃…` 修正为 Word 的 `300 ℃…`。
- **Unicode 上下标用真实字形**：`Cu₆Sn₅` 的 `₆₅₃` 等字符按 Times New Roman 原字形原字号渲染（宋体无这些字形，Word 也是从西文字体取），不再替换成普通数字再按 OS/2 缩放取整；宽度由 6px/字修正为 5px/字。`[18]` 这类 `w:vertAlign` 上下标 run 仍按 OS/2 0.65 缩放（与 Word 一致，未改）。
- **两端对齐改 `INTER_CHARACTER`**（API 34+，低版本回退 `INTER_WORD`）：中文行没有空格，`INTER_WORD` 完全无法拉伸，导致右边缘参差（实测 APK Kim 第 7 行末端距 Word 约 45px）；`INTER_CHARACTER` 才能得到 Word 的齐边效果。

**验证状态**

- Robolectric 回归 36 项全部通过（34 项执行断言 + 2 项 `@Ignore` 的 Word 断行目标）：新增 `WordBreakLayoutTest` 断言 Kim 段 `Sn，`/`、0.1` 标点边界无 AutoGap、`MPa条` 汉字边界保留间距、`₆` 按 TNR 原字形全尺寸计量；`AutoSpaceTest` 新增标点边界用例。
- Word 断行位置目标（Kim 8 行末行 `300 ℃下…缺陷。`、在连接段 11 行末行 `…提供了参考。`）以 `@Ignore` 形式写在 `WordBreakLayoutTest` 中：本 CI 主机是 Linux aarch64，Robolectric native runtime 不可用，无法执行真实断行引擎；上述目标由内置字体的 AWT 逐字宽度仿真（对 Word/APK 两组截图实测末行墨迹误差 ≤1px 交叉验证）与待真机截图复核。
- JVM 回归：70 项分页/OOXML、186 项字体、12 项真实文档断言通过。
- Su等段差异（Word `w:wordWrap` 允许 `SAC305` 等长西文串从单词中间断行填满行，Android StaticLayout 不支持）本版本未实现，属已知差异，见上文。

### 0.1.1（历史归档）

**构建标识**

- `versionName`: `0.1.1`
- `versionCode`: `2`
- APK：`/workspace/wordlite-debug.apk`
- SHA-256：`5a3e490fc38922b4003d28eb9515a2275bcd98968605dc16db7dd3d1b924e1ed`
- 本版本不是只修目录：同时修正目录 section 和正文 section 的 Word 基线网格分页。

**本版本修改**

- 正文 `snapToGrid + docGrid linePitch=360` 不再只使用整数 24 文档像素；保留 Word 的网格取整余量，避免第一章第一页多放正文行。
- 目录点引导条目继续按 `docGrid=360` 和 Word 半像素余量分页，第一页边界为 `4.2` 结束、`5` 从下一页开始。
- 图片段落的最小行盒和网格余量与普通正文统一，图片实际高度不再重复挤压或释放后续正文。
- 版本号改为 `0.1.1 / versionCode 2`，避免安装后继续显示旧版本而无法确认构建。

**验证状态**

- 增加正文网格分页回归和目录分页边界回归。
- JVM、Robolectric 和真实文档回归结果以本次最终构建日志为准；真机截图仍需安装本 APK 后复核。

### 0.1.0（历史归档）

**构建标识**

- `versionName`: `0.1.0`
- `versionCode`: `1`
- APK：`/workspace/wordlite-debug.apk`
- SHA-256：`37ec4076fccc28bce0e0f592cc29d734e4a1e5d06189245cad851c9e45e4a17d`
- 当前版本包含本轮版式审计后的改动，不代表已经实现与 Word 逐像素一致。

**已纳入的修改**

- 建立原生 Android Java 独立工程和首页固定文本约束；本地离线解析、编辑和保存 `.docx`。
- 自研 OOXML 读取/写回：段落、Run 字体、域代码、批注、表格、图片、页眉页脚和多 section。
- 多 section 页面分页：分别读取页面尺寸、上下左右页边距、页眉/页脚距离、页码格式和页眉双线。
- 内置 Times New Roman、宋体、黑体、楷体、仿宋等字体文件；按 `ascii/hAnsi/eastAsia/cs` 字体槽选择字体。
- 支持 `PAGE`、`NUMPAGES` 等常用域，保存时保留域、批注、图片关系和 section 结构。
- 支持 `docGrid` / `snapToGrid`、自动中西文/中文数字间距、行距、首行缩进、孤行/寡行控制和 `pageBreakBefore`。
- 本轮目录修正：
  - 目录右对齐制表位的点引导线保留在制表位，不再覆盖标题文本；层级左缩进继续按 XML 的 `397/794 twips` 读取。
  - 对带点引导的 TOC 条目使用该 section 的 `docGrid linePitch=360`，并在分页中保留 Word 的半像素行距余量；真实截图对应的第一页边界固定为 `4.2` 结束，`5` 从下一页开始。
- 其他版式修正：
  - 页几何增加 Word `gutter` 处理；`landscape` 页面在 `pgSz` 未交换宽高时也能正确归一化。
  - 读取并保存 `w:spacing` 的 `beforeLines/afterLines`，分页时按 Word 的行单位优先于 `before/after`。
  - 图片段落使用统一的有效段前/段后间距；同一段多张图片不再重复累加段后间距。
  - 图片高度与图片所在段落的最小行盒分离，分页高度不会通过拉伸图片来凑行。

**验证状态**

- JVM 核心回归：70 项分页/OOXML 断言、186 项字体断言、12 项真实文档断言通过。
- Robolectric UI 回归：29 项通过，其中包含最新 Word 目录第一页边界（`4.2` 后分页）断言。
- Android 真机 StaticLayout 实际断行、最新 Word 截图逐页像素对比：尚未在工程内自动化完成；因此“行数与 Word 完全一致”仍是当前待验证项。

## 当前状态

- 竖版 A4 纸张布局，双指缩放，页码跳转；正文可直接点击编辑。
- **内置真实字体文件**：Times New Roman Regular/Bold/Italic/BoldItalic、宋体（SimSun TTC）、黑体（SimHei）、楷体、楷体\_GB2312、仿宋、仿宋\_GB2312、方正公文小标宋。来源为浙江师范大学外国语学院公开的办公字体包。不再使用 Android 系统 serif/sans 替代。
- **上下标**：独立 ReplacementSpan（WordScriptSpan），读取字体 OS/2 表的 ySubscript/ySuperscript 参数，不修改整行 FontMetricsInt，不撑坏行高。支持 w:vertAlign、w:position、Unicode 上下标字符。
- **字体解析**：按 OOXML rFonts 的 ascii/hAnsi/eastAsia/cs 四槽分别选字体，CJK 与 Latin 在同一段落内正确分界。
- 基础工具栏：粗体、斜体、下划线、字号、对齐、行距、缩进、颜色、字体。
- 多 section 页面布局：封面、目录、正文可分别使用自己的页面尺寸、页边距、页眉页脚距离、页眉双线、页脚模板和页码起始值；页眉页脚按物理纸张坐标绘制，不占用正文行高。
- 读取、显示、刷新、保存常见域代码：`PAGE`、`NUMPAGES`、`DATE`、`TIME`、`AUTHOR`、`TITLE`、`FILENAME`、`TOC`、`SEQ`、`REF`、`STYLEREF`、`DOCPROPERTY`；其中 `PAGE` / `NUMPAGES` 显示值与分页互相迭代至收敛。
- 选择正文添加批注，并写入 `comments.xml`；时间戳保留原批注时间。
- 插入图片（从相册/文件；png/gif，自动降采样到 2048px 以内），写出新 media 部件、关系与 Content\_Types。
- 表格结构编辑：上方/下方插行、删除行、左侧/右侧插列、删除列。
- 格式一致性面板：正文主字号、字体混用、空段、页边距、行距和修订标记等。
- 另存为新的 `.docx`；原文件不会直接覆盖。默认另存文件名为 `原名-编辑.docx`。
- 容错 ZIP 读取器：处理 STORED 条目 CRC=0 的异常 docx（如本项目的测试文档）。
- 段级撤销/重做（文字与段落格式；结构类操作不在撤销栈内）。

## 字体说明

内置办公字体文件（浙江师范大学公开字库 + 用户提供的方正小标宋），放在 `app/src/main/assets/fonts/`：

| 文件 | 对应字体 | 用途 |
|------|----------|------|
| times-new-roman.ttf | Times New Roman Regular | 拉丁正文 |
| times-new-roman-bold.ttf | Times New Roman Bold | 拉丁粗体 |
| times-new-roman-italic.ttf | Times New Roman Italic | 拉丁斜体 |
| times-new-roman-bolditalic.ttf | Times New Roman Bold Italic | 拉丁粗斜体 |
| song.ttc | SimSun / NSimSun | 中文正文 |
| simhei.ttf | 黑体 | 中文标题 |
| kaiti.ttf | 楷体 | 中文楷体 |
| kaiti-gb2312.ttf | 楷体\_GB2312 | 兼容旧公文 |
| fangsong.ttf | 仿宋 | 中文仿宋 |
| fangsong-gb2312.ttf | 仿宋\_GB2312 | 兼容旧公文 |
| fz-small-song.ttf | 方正公文小标宋 | 公文标题 |

字体解析优先级：精确匹配（DocxFontAssets.pathFor）→ 类别回退（黑体类→SimHei，宋体类→宋体）→ 系统字体。

上下标参数从各字体的 OS/2 表实时读取（ySubscriptXSize、ySubscriptYOffset、ySuperscriptXSize、ySuperscriptYOffset），不使用硬编码比例。

## 目录结构

```
wordlite/
├── app/src/main/java/com/rikkahub/wordlite/   # 全部 Java 源码
├── app/src/main/assets/fonts/                      # 6 个内置字体
├── app/src/main/res/                               # Android 资源
├── tests/                                          # JVM 回归测试
│   ├── Regression.java                             # 70 项（分页、域、批注、往返、表格、图片）
│   ├── OriginalDocxRegression.java                 # 12 项（真实文档解析+往返）
│   ├── FontAssetsRegression.java                   # 186 项（字体文件完整性+参数）
│   ├── ui/src/test/java/...                        # 36 项 Robolectric UI/排版回归
│   ├── android/RealLayoutProbe.java                # 需真机运行
│   ├── fixture.docx                                # 合成测试文档
│   ├── make_fixture.py                             # 生成 fixture
│   └── samples/input-liu.docx                      # 真实测试文档
├── tools/
│   ├── build.sh                                    # 构建脚本
│   ├── test.sh                                     # 全量测试脚本
│   ├── android-35.jar                              # Android SDK stub
│   └── d8.jar                                      # DEX 编译器
├── artifacts/
│   ├── apk/wordlite-debug.apk                      # 构建产物
│   ├── build/                                      # 编译中间产物
│   ├── tests/                                      # 测试输出
│   └── downloads/                                  # 下载缓存
├── resources/fonts/
│   ├── supplied-fonts.zip                          # 用户提供的原始字体包
│   └── fallback-archive/                           # 旧版回退字体（已停用）
├── reports/current/                                # 最新构建/测试日志
└── checkpoints/                                    # 历史快照
```

## 构建

```sh
cd /workspace/wordlite
sh tools/build.sh
```

产物：`artifacts/apk/wordlite-debug.apk`（当前约 230 MB，包含工程内置字体资源）。

## 测试

```sh
sh tools/test.sh [可选：真实文档路径]
```

默认用 `tests/samples/input-liu.docx`。当前 268 项断言全部通过：
- Regression：70 项
- FontAssetsRegression：186 项
- OriginalDocxRegression：12 项

另有 Robolectric UI 回归 36 项（行距、Word 行高、目录点引导与分页边界、标点边界间距与 Unicode 上下标宽度、页面/缩放/首页控件约束、section 页眉页脚坐标、docGrid 基线网格；其中 2 项为环境受限而 @Ignore 的 Word 断行目标）：

```sh
cd tests/ui
env JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 \
  /root/.gradle/wrapper/dists/gradle-8.14.2-bin/2pb3mgt1p815evrl3weanttgr/gradle-8.14.2/bin/gradle \
  --no-daemon test --console=plain
```

注意：Android shaping（StaticLayout 行断）、UI 手势、真机安装启动、与 Word 逐页逐像素对比**不**在这里运行。

## 兼容性边界

当前版本是可用的 Word-like 编辑器，不是 Microsoft Word 排版引擎。

已知边界（如实说明）：

- Times New Roman 已内置 Regular/Bold/Italic/BoldItalic 四个真字重；中文黑体/楷体/仿宋仍只有 Regular，粗体会走合成加粗。
- 华文新魏、隶书、ＭＳ 明朝等字体未内置文件，但会优先尝试 Android 系统内置的 Noto Serif CJK SC（Android 7+ 通常可用），视觉效果接近原字体；若系统无此字体则回退到内置宋体。
- 缩放仅支持双指缩放手势，不支持双指平移；行内文本选择/拖动光标未实现，编辑走"点击段落 → 下方编辑该段全文"。
- 表格结构编辑、插入图片不在撤销栈内；插入图片不处理 EXIF 方向与环绕式（floating）图片。
- `TOC` 只显示缓存文本，不重算页码；`REF`/`STYLEREF`/`SEQ` 为简化语义。
- 复杂域（公式 `EQ`、`IncludeText`、交叉引用书签名解析）保留原文，不参与显示替换。
- 页面高度、字体回退、复杂浮动对象、SmartArt、域代码完整语义、修订可视化和复杂目录分页仍需要用样例文档持续校准。
- `PAGE` / `NUMPAGES` 按本应用当前的 A4 分页估算，不保证与 Word 2024 逐页相同。

正式提交论文前，建议用目标 Word 版本再做一次最终分页复核。