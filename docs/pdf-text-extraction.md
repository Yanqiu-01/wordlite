# PDF 取字实测：随包带一张 Adobe-GB1 的码表之后

这份文档只放一件事：app 自己的 PDF 解析器对真中文期刊 PDF 能读出多少字，前后差多少，
剩下的读不出的到底是什么。所有数字都是本机重跑得到的，命令照抄就能复现。

    # 1) 装表与不装表各跑一遍四份真期刊 PDF
    java -Dfile.encoding=UTF-8 -classpath "artifacts/build/host-test-classes;artifacts/build/host-classes;tools/android-35.jar" `
         com.rikkahub.wordlite.PdfExtractProbe -no-cmap artifacts/agent-corpus/corpus/*.pdf
    java -Dfile.encoding=UTF-8 -classpath "artifacts/build/host-test-classes;artifacts/build/host-classes;tools/android-35.jar" `
         com.rikkahub.wordlite.PdfExtractProbe -cmap app/src/main/assets/cmaps/adobe-gb1.cid artifacts/agent-corpus/corpus/*.pdf
    # 2) 差的那一百来字在哪几行上
    py tools/pdf-line-diff.py <同一批 pdf> --dump artifacts/tmp/pdf-dump

## 为什么非要随包带一张表

《中国科学》那份 PDF（`artifacts/agent-corpus/corpus/scichina.pdf`，716,958 字节 / 6 页）里的中文
有 **54.2%（5,101 / 9,414 字）**写在五个方正字体里（`FZXBSJW--GB1-0` 那一族）。这些字体：

- 没有内嵌字体文件（`FontDescriptor` 里连 `FontFile2/3` 都没有）；
- 没有 `/ToUnicode`；
- 编码是 `/Identity-H`，`/CIDSystemInfo` 写着 `/Adobe /GB1`。

也就是说：PDF 里只有字形码，码到字的对照表**不在这份文件里**，声明的是"我的码就是 Adobe-GB1 的 CID"。
PyMuPDF 之所以读得出，是它自己带了 Adobe 公开的字符集定义。app 要读得出，只能随包带同一张表。
（这一段是 2.3.0 那份查重的收尾结论，见 `docs/retrieval-recall.md` 的"PDF 取字量：1,846 对 9,414"一节。）

## 表是怎么做出来的，多大

- 来源：`third_party/cmap-resources/`（adobe-type-tools/cmap-resources，BSD 3 条款）。
  许可证原文照抄进 `app/src/main/assets/licenses/ADOBE-CMAP-RESOURCES-BSD.txt`。
- 命令：`py tools/build-cmaps.py`（`--check` 只核对不写文件）。本机跑到的核对数字：
  Adobe 官方 `cid2code.txt` 给出 30,178 个 CID→码位；`UniGB-UTF16-H` 反转出 30,506 条码位→CID，
  覆盖 30,171 个 CID，两边**对不上的 0 个**；同一个 CID 挂着多个码位的 331 个。
- 产物 `app/src/main/assets/cmaps/adobe-gb1.cid`：**62,370 字节**（deflate 后 57,275 字节，APK 里按压缩存放），
  槽位 30,572 个，装载后 `CidUnicodeTables.active()` 报 `Adobe-GB1=30774`。
- 一处明确的改写：Adobe 官方把 **214 个 CID** 的规范码位写成康熙部首（U+2F00..U+2FDF）或
  CJK 部首补充（U+2E80..U+2EFF），而同一个 CID 上也挂着汉字本体。方正这些 PDF 在那个码位上画的是汉字，
  照官方取部首就会拿到 `⼀`(U+2F00) 而不是 `一`(U+4E00)——查重时对不上。这两段一律换成同一 CID 上的汉字本体
  （两者本来就是 NFKC 等价），脚本会把改写的条数和例子打出来。

## 前后差多少（同一批四份真期刊 PDF，各跑一遍）

四份：`frontsci.pdf`、`scichina.pdf`、`viserdata.pdf`、`zidong-cjmenet.pdf`，合计 4,173,588 字节、27 页。
真值尺子是 PyMuPDF 1.28.2 报的非空白字数——就是下面 `tools/pdf-line-diff.py` 每份第一行打印的那个数。

| | 不装表（等于 2.3.0） | 装表（改后） | PyMuPDF 真值 |
| --- | --- | --- | --- |
| frontsci.pdf | 7,664 | 7,664 | 7,664 |
| scichina.pdf | 4,313 | 9,386 | 9,414 |
| viserdata.pdf | 6,600 | 6,600 | 6,600 |
| zidong-cjmenet.pdf | 20,130 | 20,130 | 20,263 |
| 四份合计（解析口径） | **38,707** | **43,780** | 43,941 |
| 读到占真值 | **88.1%** | **99.6%** | 100% |
| 汉字数 | 21,559 | 26,610 | 26,694 |
| 读不出的字形 | 5,080 个 | **7 个** | — |
| 卡住的字体 | FZXBSJW/FZHTJW/FZSSJW--GB1-0、DLHKGM+Symbol、DLICEB+EuclidExtra | DLHKGM+Symbol、DLICEB+EuclidExtra | — |

走"下进自建库"那一条（`CorpusImport.download` 一次四篇，同一批文件，不联网）：
入库 38,707 字 → **43,777 字**（和上表差 3 字是首尾空白计数口径不同），下载字节同为 4,173,588，
耗时 1,474ms → 1,345ms。命令：

    java -Dfile.encoding=UTF-8 -classpath <同上> com.rikkahub.wordlite.BatchImportMeasure without
    java -Dfile.encoding=UTF-8 -classpath <同上> com.rikkahub.wordlite.BatchImportMeasure with

## 剩下的 161 字是什么（不是"还剩一堆读不出"）

`py tools/pdf-line-diff.py` 把两边逐行对着看，结论：**app 一个多余字符都没有**（app 独有的字符数 = 0），
差的字全集中在 11 行里，`frontsci.pdf` 与 `viserdata.pdf` 与 PyMuPDF **一字不差**。

- `scichina.pdf` 差 31 字 = 3 行：一条作者单位行（"合肥微尺度物质科学国家实验室(筹), 中国科学技术大学化学系…"）
  和正文里两段半截行。方向 `dir=(1.00,0.00)`，不是竖排；这一份的读不出字形是 0，所以不是码表的问题，
  是这几段根本没进文本流。
- `zidong-cjmenet.pdf` 差 133 字 = 8 行：3 行是摘要里的整段/半段（含两行英文），5 行是第 8 页上
  几个 Symbol/EuclidExtra 的符号（`\x05` 这类）。真正"字形读不出"的只有 **7 个**，就是这两份字体。

所以"缺 164 字"不能读成"还有 164 个字形读不出"。前者是行数问题（下一步查文本流为什么漏这段），
后者只剩 7 个，需要的是 Symbol/EuclidExtra 这一族数学符号字体的对照表。

## 线上真的抓到期刊 PDF 时长什么样

2026-10-09 用 `tests/samples/input-liu.docx` 跑联网查重（只问 OpenAlex），从
`www.cjmenet.com.cn` 的下载口实时取回两份 PDF：

- 2,961,037 字节 → 11,783 字 / 7 页，形状 `pdf-body`（一个读不出的字形都没有）；
- 740,712 字节 → 15,312 字 / 8 页，形状 `pdf-body-partial`，读不出字形 7 个（`DPIFMN+MTExtra`、另一支）。

回执与留档行都说得出这两件事（多少字 / 几个字读不出 / 哪个字体），不是一个光秃秃的"抓取失败"。

## 1,846 对 9,414 这一条结案：是解析漏字，不需要 OCR（2026-10-09 复算）

PyMuPDF 1.28.2（本机 python 3.12）逐份取文本、按**非空白字符**数当分母；app 侧同口径
（`tests/PdfExtractProbe.java` 与一次性探针 `UndecodableProbe`，都数非空白字符）：

| 文件 | PyMuPDF | 改前的解析器 `f878f64` | 现在，不装表 | 现在 + 随包 Adobe-GB1 |
| --- | --- | --- | --- | --- |
| `scichina.pdf` | 9,414 | **1,846**（lost 7,978） | 4,313（lost 5,073） | **9,386**（lost 0，随包表补 5,073） |
| `zidong-cjmenet.pdf` | 20,263 | 18,977（lost 263） | 20,130（lost 7） | 20,130（lost 7） |
| `frontsci.pdf` | 7,664 | 7,664 | 7,664 | 7,664 |
| `viserdata.pdf` | 6,600 | 6,600 | 6,600 | 6,600 |
| 四份合计 | 43,941 | 35,087 | 38,707 | **43,780（99.6%）** |

"改前"那一列不是抄旧文档：把 `f878f64`（`ab84d0a` 的前一版）的 `PdfFile.java` 单独编出来跑同一份
`scichina.pdf`，落点是 **1,846 字**，与 2.3.0 记下的数一字不差；同一版对 `zidong-cjmenet.pdf`
是 18,977 字，也和当时记下的数一样。两处对上，说明这一列量的确实是当时那台解析器。

**漏掉的那些字在 PDF 里是什么**：

- 大头是那五个方正字体（`FZXBSJW--GB1-0` / `FZHTJW--GB1-0` / `FZSSJW--GB1-0` / `FZKTJW--GB1-0` /
  `FZFSJW--GB1-0`）：`Subtype=Type0`、`/Encoding=/Identity-H`、**未内嵌**（`FontDescriptor` 里没有
  `FontFile2`，也没有 `FontFile3`）、**没有 `/ToUnicode`**，`/CIDSystemInfo` 写 `/Adobe /GB1`。
  文件里只有字形码，没有码到字的表。这一档 5,073 字，被随包那张 62,370 字节 / 30,774 条的
  `app/src/main/assets/cmaps/adobe-gb1.cid` 精确补回（装表后 `随包表=5,073`、`lost=0`、
  卡住的字体清单为空）。
- 第二档是解析器自己的两处 bug，跟字体无关：① 字体缓存按资源名（`/C2_1`）而不是按解析到的字体对象缓存，
  同名跨页串用，后一页整页丢掉——1,846 主要是这么来的（同一版解析器同一份文件，
  光这两处修完不装表也能读到 4,313）；② `TrueTypeCmap.format4` 少读 `endCode[]` 与 `startCode[]`
  之间那 2 字节 `reservedPad`，整张表错位。
- **没有 Type3**：`scichina.pdf` 的字体清单只有 `TrueType`（Times/Arial/Trebuchet 族，
  `WinAnsiEncoding`，未内嵌）与 `Type0`（`Identity-H`）两类。
- **不是扫描件**：六页里最大的一张图只占页面面积 0.03，`textOps=4,735`。
  所以这条验收的答案是：解析漏字 + 缺一张码表，**不需要 OCR**。

还剩多少、卡在哪（同口径逐字对照，`Counter(真值) - Counter(app)`，两份文件 **app 多出来的字都是 0 个**——
解析器不造字）：

- `scichina.pdf` 差 28 字（0.30%），`zidong-cjmenet.pdf` 差 133 字（0.66%）。
- `zidong` 那 7 个字形认得清清楚楚：`DLHKGM+Symbol` 与 `DLICEB+EuclidExtra`（数学符号字体，无 ToUnicode），
  `undecodableGlyphs` 按个数记了这 7 个并留下字体名。
- 剩下那些是另一种形状：在中英交替的行里，那一小段 FZ*-GB1 整段没进文本，例如
  "中国科学技术大学化学系"（p1 bbox `[176.0, 176.2, 266.0, 184.2]`）、
  "不仅仅可以作为电荷传导"（p1 bbox `[396.6, 556.8, 511.0, 567.5]`）、
  "如导电和物质输运等"（p1 bbox `[327.2, 282.8, 417.3, 292.8]`）。
  装表之后这份文件 `lost=0` 且 `undecodable=false`，也就是说这**不是缺表、不是缺 ToUnicode**，
  而是那几次 `Tj`/`TJ` 的字节压根没走到 `show()`。占 0.3%-0.7%，是一个还没定位到具体算子序列的解析缺口。
- 顺着这条记一个可改进项：`PdfFile.show()` 在 `font == null` 那一支只置 `undecodable=true`，
  不把丢掉的字数记进 `undecodableGlyphs`，于是"整段掉了"在账上和"一个没掉"长得一样。
  上面那个缺口要定位，先补这个计数最省事。

复现命令（本机 python 有 pymupdf 1.28.2）：

    # PyMuPDF 真值
    py -c "import pymupdf,glob;[print(p, sum(1 for c in ''.join(pg.get_text() for pg in pymupdf.open(p)) if not c.isspace())) for p in glob.glob(r'artifacts/agent-corpus/corpus/*.pdf')]"
    # app 侧：不装表 / 装表
    java -cp "<test-classes>;<classes>;tools/android-35.jar" com.rikkahub.wordlite.PdfExtractProbe -no-cmap <四份 pdf>
    java -cp "<test-classes>;<classes>;tools/android-35.jar" com.rikkahub.wordlite.PdfExtractProbe -cmap app/src/main/assets/cmaps/adobe-gb1.cid <四份 pdf>
    # 改前那一版解析器
    git show f878f64:app/src/main/java/com/rikkahub/wordlite/PdfFile.java > <旧目录>/com/rikkahub/wordlite/PdfFile.java
    javac -cp "<classes>;tools/android-35.jar" -sourcepath app/src/main/java -d <旧 classes> <旧目录>/com/rikkahub/wordlite/PdfFile.java
    java -cp "<旧 classes>;<classes>;tools/android-35.jar" ...   # 旧 classes 放前面

## 断言在哪

- `tests/PdfRegression.java` 29 条：Identity-H + Adobe-GB1 三种夹具（`fixture-gb1-gb1.pdf`、
  `fixture-gb1-identity.pdf`、`fixture-gb1-radical.pdf`）、字体名跨页不串用、读不出的字形按个数与字体名记。
- `tests/DetectRegression.java` 209 条里含全文这一路：随表解出真值句、壳页判 `text-blocked`、脚本样式不算正文。
- 夹具由 `tests/make_cid_cmap_fixture.py` 自造，真值写死在构造里（`GB1_TRUTH` 那句）。

## 量不到的

- **手机 APK 里从 assets 装表这一层没量**：本轮全部在 host JVM 上跑。APK 里那张 62,370 字节的表能不能被
  `ApiWorkflow` 正常从 assets 装进 `CidUnicodeTables`，只验过 host 侧同一个入口（探针从文件装），
  所以这一条量不到。
- 旧结论"手机 `app_process` 那条路开不了 socket"作废（2026-10-09 复跑）：把 `adb reverse tcp:7897`
  挂上之后，`tools/device-probe.ps1` 在 `app_process` 里跑得通真网，一轮查重取回 60 条候选、
  抓到 1 篇开放获取全文（见 `docs/retrieval-recall.md`）。之前那十个 ECONNREFUSED 是**没有出口**，
  不是 `app_process` 开不了 socket。取字这一层在手机上仍未单独量（PDF 解析没有设备侧差异的代码）。
