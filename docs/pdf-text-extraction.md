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

## 断言在哪

- `tests/PdfRegression.java` 29 条：Identity-H + Adobe-GB1 三种夹具（`fixture-gb1-gb1.pdf`、
  `fixture-gb1-identity.pdf`、`fixture-gb1-radical.pdf`）、字体名跨页不串用、读不出的字形按个数与字体名记。
- `tests/DetectRegression.java` 209 条里含全文这一路：随表解出真值句、壳页判 `text-blocked`、脚本样式不算正文。
- 夹具由 `tests/make_cid_cmap_fixture.py` 自造，真值写死在构造里（`GB1_TRUTH` 那句）。

## 量不到的

- **手机上的这一层没量**：本轮全部在 host JVM 上跑。APK 里那张 62,370 字节的表能不能被
  `ApiWorkflow` 正常从 assets 装进 `CidUnicodeTables`，只验过 host 侧同一个入口（探针从文件装），
  设备侧本轮没跑，所以这一条量不到。
- 手机 `app_process` 那条路开不了 socket（十个源全部 ECONNREFUSED），设备侧联网取字这一层也量不到。
