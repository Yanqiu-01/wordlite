# Word 移动版的排版引擎是 native 的：证据与对 Word Lite 的难度定位

只读静态分析 `artifacts/ms-office.apk`（343,748,748 字节，`com.microsoft.office.officehubrow`
16.0.17328.20180）。所有结论只到"这个符号/字符串/依赖在这个条目里"这一层，超出的一律标"推测"。

## 1. lib 下与文字排版/渲染直接相关的 .so

`lib/` 只有一个 ABI：`arm64-v8a`（`aapt2 dump badging` 的 `native-code: 'arm64-v8a'`）。98 个 .so，
273,357,208 字节，且全部是 store 不压缩（compressed == uncompressed）。

| .so | 字节 | 与排版的关系 |
| --- | --- | --- |
| libwlibandroid.so | 30,492,968 | Word 主体（Word Library）。下面第 2 节的内部符号和源文件路径都在这里 |
| librichedit.so | 3,995,888 | RichEdit 文本控件/排版层 |
| libdwritecore.so | 3,195,008 | DirectWrite 移植。自身只依赖 liblog/libdl/libm/libc，是一套自带实现的移植而非系统 API 转发 |
| libdwriteshim.so | 10,360 | DWrite 适配层 |
| libgdi.so | 1,999,568 | GDI 移植（`GetTextFaceW` 一类老文本 API） |
| libwic.so | 1,885,160 | WIC 移植（图像编解码，参与图片参与分页） |
| libskiaoffice.so | 4,626,048 | Office 自己的 Skia 后端，绘制出口 |
| libgfxandroid.so | 2,980,576 | Office 图形抽象层 |
| libicui18n56.so | 64,664 | ICU 裁剪版（64KB，明显是被裁过的，只提供少量 i18n 能力） |
| libmsoandroid.so / libmso20/30/40ui/50/98android.so | 22,975,880 / 8,249,392 / 13,917,968 / 6,548,216 / 535,136 / 6,884,288 | MSO 共享核心（字体名/回退相关字符串也在这里，见第 4 节） |
| libmsspell7.so | 340,264 | 拼写检查 |
| libmsgram8.so | 835,592 | 语法检查 |
| libPdfium.so / libPdfControl.so / libPdfJni.so / libPDFManipulator.so | 4,650,008 / 6,964,472 / 837,944 / 551,256 | PDF 读取与导出 |
| libyoga.so | 162,096 | React Native 的 UI 布局，只负责 App 外壳界面，与文档排版无关 |

关键：**没有 libharfbuzz、没有 libfreetype**。文本整形与字形测量不走 Android 系统那套
（System UI 的 minikin/harfbuzz），而是走自带的 DWrite/GDI/RichEdit。

## 2. 决定性证据：Word 主引擎的 ELF 依赖

`py probe_dt_needed.py extract/libwlibandroid.so` 读 `SHT_DYNAMIC` 的 `DT_NEEDED`：

```
libwlibandroid.so 的 DT_NEEDED（节选，去掉系统库）
  libc++_shared.so  libcsiandroid.so  libplat.so  libstg.so
  libdwritecore.so          <-- DirectWrite
  libdwriteshim.so
  libgdi.so                 <-- GDI
  libwic.so
  ...
  libgfxandroid.so
  libicui18n56.so
  librichedit.so            <-- RichEdit
```

`librichedit.so` 和 `libgdi.so` 同样 DT_NEEDED `libdwritecore.so` / `libdwriteshim.so`。
也就是说 Word → RichEdit/GDI → DWrite 这条链是链接期就定下来的，不是运行时可选。

复现：

```powershell
py probe_dt_needed.py extract\libwlibandroid.so extract\librichedit.so extract\libdwritecore.so extract\libgdi.so
```

## 3. libwlibandroid.so 里的排版内部符号与 Windows 源文件路径

```powershell
py probe_strings.py 'DWriteAssistant' extract\libwlibandroid.so
py probe_strings.py 'd:.dbs.{0,20}pts.{0,40}[a-z_]+\.cpp' extract\libwlibandroid.so
py probe_srcpaths.py extract\libwlibandroid.so          # 汇总，结果存 _srcpaths_wlib.txt
```

命中样例（原样摘自二进制）：

```
@0x45626  ...Mso15DWriteAssistant19MsoCreateTextFormatER14IDWriteFactoryPKwP21IDWriteFontCollection18DWRITE_FONT_...
@0x456d7  ...Mso15DWriteAssistant15ResourceManager21GetDWriteFontFallbackERKNS_7T...
@0x15f457 .GetTextFaceW..char scaled font create failed.
@0x12a506 ...d:\dbs\el\omr\src\ptls7\pts\src\fsbaselineinfoapi.cpp...
```

`libdwritecore.so` 里有 `IDWriteFactory` / `IDWriteFactory1..4` 全套 COM 接口名 27 处
（`py probe_strings.py 'IDWriteFactory' extract\libdwritecore.so`）。

`libwlibandroid.so` 内嵌了 **181 个 Windows 侧编译单元的源文件路径**，形如
`d:\dbs\el\omr\src\ptls7\<模块>\<子目录>\<文件>.cpp`，分三个模块：

- `ptls7/ls`（94 个）＝ LS 排版引擎（Line/Linear Surface，Windows/Office 那套文本排版内核）。
  断行一族：`lstxtbrk.cpp`、`lstxtbr1.cpp`、`lstxtbrhyph.cpp`（连字符断行）、
  `lstxtbrshape.cpp`、`lstxtbrwidth.cpp`、`lstxtbrloops.cpp`；
  行与行盒：`lscrline.cpp`、`lsqline.cpp`、`lsparabreak.cpp`、`lsgetpenaltymodule.cpp`（断行惩罚）；
  东亚文字对象：`lsobjruby.cpp`、`lsobjvruby.cpp`、`lsobjwarichu.cpp`、`lsobjtatenak.cpp`（ ruby/竖排 ruby/和字训点/竖中黑）；
  原生数学公式对象：`lsmathsubscriptapi.cpp`、`lsmathsuperscriptapi.cpp`、`lsmathmatrixapi.cpp` 等 30 余个。
- `ptls7/pts`（85 个）＝ PTS 页面排版层。页面与节：`fspageapi.cpp`、`fspagebodyapi.cpp`、
  `fspagefmtstateapi.cpp`、`fspagepropercore.cpp`、`fssectionapi.cpp`、`fsgeom*.cpp`、`fsmargins*.cpp`；
  多栏：`fsmulticolumnlayout.cpp`、`fscompositecolumnapi.cpp`；
  最优段落断行：`fsoptimalparamain.cpp`、`fsoptimalparabestfit.cpp`；
  基线网格：`fsbaselineinfoapi.cpp`；
  升降文字（上下标）：`fsshiftapi.cpp`、`fsshiftoff.cpp`；
  脚注：`fsnoteservices.cpp`、`fsftnrejapi.cpp`（脚注放不下时的回退处理）；
  表格：`fstableobj.cpp` + `fstablesrv*.cpp` 共 11 个，其中行定位有两套实现
  `fstablesrvrowposword.cpp` / `fstablesrvrowword.cpp`（Word 兼容算法）与
  `fstablesrvrowgood.cpp` / `fstablesrvrowcomn.cpp`（另一套算法）。
- `ptls7/shared`（2 个）。

这些文件名只能证明"这套引擎的模块划分与源文件在这里"，不能证明每个文件的行为。
但从模块划分能确定一件事：**分页在 Word 里是一个有上百个编译单元、分两层（LS 管行、PTS 管页）的独立子系统。**

## 4. 字体回退的线索

```powershell
py font_roster.py extract\native-res\officeandroid.odf\officeandroid.odf extract\libwlibandroid.so extract\libmsoandroid.so
```

`libmsoandroid.so` / `libwlibandroid.so` 里以 UTF-16LE 出现的字体族名（节选）：
SimSun、SimHei、KaiTi、等线（DengXian）、宋体、黑体、楷体、仿宋、方正、PMingLiU、Microsoft JhengHei、
MS Mincho、MS Gothic、Yu Gothic、Meiryo、Malgun Gothic、Times New Roman、Cambria、Cambria Math、
Calibri、Calibri Light、Consolas、Tahoma、Verdana、Georgia、Segoe UI、Segoe UI Symbol、Courier New、
Wingdings、Webdings、Liberation、DejaVu、Arial。

需要说清证据强度：把上面这些名字周围的字节窗口解出来看，它们落在**大字符串池**里
（例如 `宋体` 附近的邻居是 `細明體`、`돋움` 以及 `HyphenationNotSupported`、
`DownloadProofingResourceFailed` 这类错误/资源名；`方正` 附近是 `AR DELANEY`、`FUTURA`、
`SOURCE CODE PRO` 这类字体名）。所以能证明的是"Word 的二进制按名字认识这些字体族"，
**具体映射关系（宋体 → 设备上用哪个字体文件）在静态层面看不出来，属于推测**。
能直接读到的回退机制入口只有一个符号名：`Mso::DWriteAssistant::ResourceManager::GetDWriteFontFallback`。

`extract/native-res/officeandroid.odf/officeandroid.odf`（1,708,720 字节）是一张按文字/script 组织的元数据表，
里面能看到 `Calibri`、`10pt`、`9pt`、`8`、`normal`、`Kaiti`、`Kannada`、`Hanunoo`、`Kayah Li` 这类条目混排，
推测是 Word 的"每种 script 的默认字体/默认字号"表。结构未解析，**未验证**。

## 5. 对 Word Lite 的难度定位

Word Lite 0.4.0 侧（`app/src/main/java`，只读）用纯 Java 实现了 `DocxTextLayout` → `A4Paginator` → `PageBreaker`
这一条链，验证方式是 `tools/capture-device.ps1` + `tools/word-parity.ps1` 拿 PC 版 Word 16.0（COM 驱动）的页码真值逐段对齐，
`tests/samples/input-liu.docx` 当前 206 段可对齐、156 段同页。

把两边放一起，难度定位是这样：

1. **对手不是一个引擎，是两层。** Word 的断行在 LS、分页在 PTS，中间用"惩罚模块"
   （`lsgetpenaltymodule.cpp`）和"最优段落"（`fsoptimalparamain.cpp`）耦合。Word Lite 是一层
   Java，靠 docx 能观察到的输入反推输出。凡是 Word 内部有状态、docx 里不留痕的环节，
   Word Lite 原则上没有可对齐的观测点。
2. **Word 断行不是"宽度够了就换行"。** `lstxtbrwidth` / `lstxtbrshape` / `lstxtbrhyph` /
   `lstxtbrloops` 是分开的四套逻辑，再叠 `lsgetpenaltymodule`。Word Lite 目前是"按 advance 累加 +
   边界判定"的单套规则。tight-boundary 的行差 1 字，就是这么来的（0.4.0 修上下标行宽就是在补这一类）。
3. **东亚排版是 Word 的一等公民。** `lsobjruby` / `lsobjvruby` / `lsobjwarichu` / `lsobjtatenak`
   说明 ruby、竖排 ruby、和字训点有独立对象。Word Lite 目前只处理 `docGrid` 基线网格、
   `overflowPunct`、`wordWrap`，没有 ruby 对象模型。中文论文不写 ruby，所以这条对 Word Lite
   的实际影响是"暂时不用追"，不是"已经对齐"。
4. **表格跨页断行有两套算法。** `fstablesrvrowposword.cpp` / `fstablesrvrowword.cpp`（Word 兼容）
   与 `fstablesrvrowgood.cpp`（另一套）并存，说明连微软自己都要维护"新旧两套行定位"。
   Word Lite 只有一套。这一块是所有维度里最容易和 Word 分歧、且最难靠看 docx 收敛的。
5. **上下标有专门模块。** Word 用 `fsshiftapi.cpp` / `fsshiftoff.cpp` 处理升降文字，
   并且基线信息单独成模块（`fsbaselineinfoapi.cpp`）。Word Lite 0.4.0 的 `ScriptGeometry`
   对应的是这两个模块的可观测部分（脚本字号、基线、行盒让位），已经能从"提前换行"
   收到"逐页一致"，说明这条路走得通；但 Word 内部还有 shift 与表格/栏互动的部分，
   Word Lite 未覆盖（`fsshiftapi` 与 `fstableobj` 的关系无法静态确认，**推测**）。
6. **Word Lite 有一个 Word 在移动端没有的优势：字体文件。** Word 的排版引擎按名字要
   SimSun/等线，但 APK 里一个中文字体文件都没有（见 `fonts.tsv`），只能拿设备字体顶上。
   在 HONOR/Android 10 上这意味着 Word 自己的分页结果取决于 OEM 装了哪个 CJK 字体，
   **不同手机上 Word 的页数本身就不一致（推测，未验证）**。Word Lite 随包带 14 个字体文件
   （63,653,408 字节，占 APK 99.6%），同一篇文档在任何设备上分页确定。
   这一条是静态可证的差异，也是 Word Lite 该守住的地方。

## 6. 本轮用到的命令

```powershell
$aapt = 'E:\download\claude\Wordlite\artifacts\host-tools\android-sdk\build-tools\35.0.0\aapt2.exe'
& $aapt dump badging E:\download\claude\Wordlite\artifacts\ms-office.apk   # native-code / minSdkVersion
py probe_strings.py <pattern> extract\lib*.so                              # 符号与源路径探针
py probe_srcpaths.py extract\libwlibandroid.so                             # 181 个源文件路径按模块汇总
py probe_dt_needed.py extract\libwlibandroid.so                            # DT_NEEDED
py probe_u16_context.py 宋体 extract\libmsoandroid.so                      # UTF-16LE 邻域
py font_roster.py extract\native-res\officeandroid.odf\officeandroid.odf extract\libwlibandroid.so
```

探针脚本：`probe_strings.py`、`probe_srcpaths.py`、`probe_dt_needed.py`、`probe_u16_context.py`、
`probe_ascii_context.py`、`font_roster.py`，都在本目录。
源路径汇总原始输出：`_srcpaths_wlib.txt`。

