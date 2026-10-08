# 材料分档：一次查重到底拿什么比的

一次比对用的材料不是一种东西。导进自建库的那份原文有整篇，知网/万方/维普公开口给的只有摘要，
手机渲染万方详情页拿回的又是另一样：整页 3,331 个汉字，其中 1,500 字上下是网站自己从整篇里摘的
「全文精要」（`docs/endpoints-access.md` 有那一轮的取回数字）。这几种材料摊进同一个分子，
报告就会把"拿三十条摘要比出来的 3.1%"读成"跟全网正文比过只有 3.1%"。所以每一条进语料的材料都带一个档，
命中按档分开数、分开报，不相加。

## 有哪几档

| 档（`DuplicateEngine.MATERIAL_*`） | 材料是什么 | 从哪条路进来 | 报告与界面上的名字 |
| --- | --- | --- | --- |
| `MATERIAL_FULL` = 全文 | 整篇正文 | OA PDF 正文、用户导进自建库的 docx/pdf/txt、把整篇摊在页面上的期刊官网渲染结果 | 正文 |
| `MATERIAL_DIGEST` = 精要 | 网站从整篇里自己摘的那一段（万方写作「全文精要」），实测 1,500 字上下 | 手机 WebView 渲染详情页取回（`PageText.pick` → `PageRender`） | 只有精要可读 |
| `MATERIAL_ABSTRACT` = 摘要 | 题录导出的摘要字段、检索接口返回的摘要、OpenAlex 的摘要 | `RecordImport` 题录入库、检索候选未取回正文时、摘要层 | 只有摘要可比 |
| `MATERIAL_RECORD` = 仅题录 | 只有题名、作者、刊名、年份，一个字的正文都没有 | `RecordImport` 里那条没有摘要字段的题录 | 只有题录 |
| 空（老索引没这个键） | 按正文读，后向兼容 | 2.4.0 之前入库的条目 | 未记档 |

`MATERIAL_RECORD` 那一档没有可比文本，进不了语料，也就拿不到命中；它存在的意义是说清"这一篇我们手里
只有一行书目，压根没比过"，别让它在来源榜里被读成比过而且没有。

档位落盘在自建库索引的 `material` 键上（`LocalLibrary.Entry.material`）。老那个 `record` 布尔位照写：
只要不是正文档就为真，这样旧版本 App 读不到 `material` 时也照样降权，不会让"精要""仅题录"从降权里溜出去。

## 为什么不乘折扣系数

给摘要级乘一个 0.4 之类的系数最省事，但量过之后这条路不成立。同一篇真论文（《机械工程学报》60 卷 19 期，
DOI 10.3901/JME.2024.19.318，正文取 11 句共 505 个有效字，摘要取 OpenAlex 那份 365 个有效字，两份都在 `tests/corpus/` 里）：

| 稿子 | 摘要级语料 | 正文档语料 |
| --- | --- | --- |
| 就是那 11 句正文 | 0 处 / 0 字 | 1 处 / 505 字 = 100% |
| 另一主题的真人论文 19,483 字 | 0 处 / 0 字 | 0 处 / 0 字 |

摘要级命中的证据强度不比正文级弱（它不会因为材料薄就多撞出假命中来），弱的是覆盖面：摘要只覆盖了那篇
的三四百字，正文覆盖一整篇。乘完系数，两档的字数又被合成一个数，恰好抹掉了真正有区别的那件事。
所以规矩是：`SourceLedger` 排序先分档再比字数，摘要级整体排在正文级后面；报出来的数字各档各的，
中间那个"另有"是几把尺并列，不是合成一个数。

## 报告里在哪儿分开说

- 结果页三个比率下面两行：`CorpusLedger.summaryLine()`（这次比了哪几个库、各几篇、各多少字）
  和 `DuplicateEngine.materialSplitLine()`（命中按正文级 / 精要级 / 摘要级各多少字、占整篇分母多少、几篇）。
  一处命中都没有时那一行不画。
- 来源榜多一列"可比材料"，行内第一件说的事是档位；并过条的行把每一份字数都列出来，
  不让"正文"两个字盖掉同一行里的精要与摘要。
- HTML 报告（`CheckReport`）加"比对材料 N 篇 · M 组"那一节，摘要级出处那几行带"只有摘要可比"。
- 存档（`ReportStore`）多这几键：`materialFull` / `materialDigest` / `materialAbstract` 与对应的
  `*Papers`，来源行带 `material` 与三档字数，另有 `materials[]` 那张按「检索源 × 档」的分组表。

## 复现

主机侧（不进网络，也不碰 Android）：

```
pwsh -NoProfile -File tools/run-suites-private.ps1 -Tree . -OutDir artifacts/tiers `
  -Suite MaterialTierRegression,RecordImportRegression,SourceLedgerRegression,ReportStoreRegression
```

`MaterialTierRegression` 106 条：三档字数相加等于总命中字数、排序先分档、并条行的主档取最强档、
档位穿过存档读写、两个对话框与报告中心的排版只过编译。
`RecordImportRegression` 140 条，末尾那一条查的是"1 条仅题录 + 4 条摘要"那份档位分布在重开库之后仍在。

上面那张表的数字出自不进发布前清单的探针（只吃本地语料：`tests/corpus/oa-planted-cjmenet.txt` 是那篇的 11 句正文，
`tests/corpus/oa-abstract-cjmenet.txt` 是同一篇的 OpenAlex 摘要，不带参数就跑这两份）：

```
java -Dfile.encoding=UTF-8 -cp artifacts/tiers/test-classes;artifacts/tiers/classes;tools/android-35.jar `
  com.rikkahub.wordlite.MaterialTierProbe
```