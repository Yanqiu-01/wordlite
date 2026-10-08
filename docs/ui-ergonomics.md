# 界面可达性实测：ribbon、底栏、对话框

这份文档是别人来吵架用的，所以每一条都得能重跑。量的对象是用户机上那一档：Huawei CDY-AN90，1080x2400，480dpi（密度 3.0，360dp 宽），Android 10。

## 1. 先定状态，再谈数

一条横向滚动行里的控件只有三种状态，说的都是"看得见多少"，不是"有没有做"：

| 状态 | 意思 | 判据 |
|---|---|---|
| 看得全 | 整个按钮在视口里 | 节点矩形 ∩ 视口 = 节点矩形 |
| 切断 | 被屏幕边切一刀，露着半截 | 交集不为空，但小于节点矩形 |
| 折线外 | 这一排往那个方向滚一下就到 | 交集为空 |

表里的每个数都是从 View 层量的：探针 `tools/device-probe/RibbonErgonomicsActivity.java`（独立包 `com.rikkahub.wordlite.uiaudit`，不碰用户机里的 `com.rikkahub.wordlite`）在真机上把每个控件的左右边界直接写出来。"折线外"永远不等于"这个控件没做"，理由见下一节。

## 2. dump 会不会漏报滚动容器里的节点：量出来的答案

`uiautomator dump` 报的边界 = 节点矩形 ∩ 容器可见带，交集为空的那个节点整条不出现。这条不是推的，是拿两个东西量出来的（`pwsh tools/ui-visibility-oracle.ps1`）：

- 合成滚动容器：40 行 TextView，视口 2292px，内容 14400px。静止那份 dump 报出 7 个、不报 33 个；滚到底还是 7 / 33，只是换了一批。"在视口里却没被报出来"这种情形数出来是 0。也就是说 dump 只按可见带切，切完为空的就整条不要。
- 真机 ribbon（360dp，开始页签命令行）：View 树里 20 个控件，dump 报了 16 个，4 个不报（段落、字体、字号、颜色）；行距被报成 `[1070,1080]` 这种切掉大半的形状，与你量到的 `两端[1031..1080]` 是同一种形状。

结论：**dump 里没有 ≠ 控件不存在**。所以这份文档里 ribbon 的可见性一律用 View 层数，dump 只用来证第 3 节那种事。

## 3. 2.5.0 的查重结果页：那两格到底建没建

结果页（标题"查重结果"）整个装在 ScrollView 里。真机那一次（`2026-10-08T22:46:38Z`，10 篇可下载候选、1 处命中）的四份 dump：

| dump | 文本节点 | 关键内容 |
|---|--:|---|
| 静止 | 35 | 最后一行的下沿正好压在 2166（滚动带下沿），关闭 / 导出报告在 [561,2178][969,2292] |
| 下滑一次 | 12 | 10 行「只下这一篇：…」全在，第一行的上沿 278 = 被滚动带上沿切掉 |
| 再下滑 | 12 | 「降重并复核：只改判为重复的段落…（1 处命中）」在 [123,1711][957,1862] |
| 滑到底 | 12 | 同上一份，位置不动 = 到底了 |

四份并起来 48 个节点。静止那份里没露面的只有汇总那两行（`可以下进自建库的开放获取全文 N 篇…` 与 `全部下进自建库：N 篇…`）——它们排在第一行「只下这一篇」的上面，而那份 dump 里第一行「只下这一篇」的上沿就是被带上沿切掉的，这两行落在同一段被滑过去的带里。

在 View 层把这件事钉死（不靠读代码推理）：`ResultDialogCensusTest` 走真代码路径 `ApiWorkflow.showScan()`，在 360dp 上把同一屏建出来（10 个候选 + 1 处命中），量到的是：

- 那一屏 40 行全建出来。第 26 行是汇总行（不是按钮，带"10 篇"和"自建库"），第 27 行是整批下载（粗体、点得动），第 28..37 是逐篇 10 行（都点得动），第 38 行是降重并复核（粗体、点得动）。
- 按第 1 节那条求交规矩分类：内容高 2853px，视口取真机量到的滚动带 1888px。静止时 6 行整行在折线外（dump 就是不报它们）；滚到底这 6 行全部变成看得全；再把汇总行整行挪进视口，汇总行与整批下载那行都是看得全。

**结论：那两格在，结果页没有承诺一个屏幕上不存在的按钮。** 这份测试跑的是 HEAD 上那份代码；另一位在改的查重措辞不动行数，行数一变这条测试会先红，红得也说明白。

## 4. 复现

```powershell
# 1) 真机 ribbon 可达性：从哪棵树编，就量哪棵树。改前用 git archive HEAD 那棵树。
git archive HEAD --output artifacts/build/head.zip
Expand-Archive artifacts/build/head.zip -DestinationPath artifacts/build/head-tree
pwsh tools/build-uiaudit-probe.ps1 -Tree artifacts/build/head-tree -OutTsv artifacts/analysis/ui-ergonomics/before.tsv
pwsh tools/build-uiaudit-probe.ps1 -Tree . -OutTsv artifacts/analysis/ui-ergonomics/after.tsv

# 2) 把两张 tsv 打成下面这些表
python tools/ui-ergonomics-report.py --before artifacts/analysis/ui-ergonomics/before.tsv --after artifacts/analysis/ui-ergonomics/after.tsv

# 3) dump 与 View 层的那条规矩（合成滚动容器 + 真机 ribbon 各量一遍）
pwsh tools/ui-visibility-oracle.ps1

# 4) 结果页那一屏的 View 层普查（私有树，不碰共享编译目录）
cd artifacts/build/<树>/tests/ui
& <gradle> --offline --no-daemon test -PappClasses=<你那棵树的 app 编译产物> --tests "*ResultDialogCensusTest"
```

原始产物在 `artifacts/analysis/ui-ergonomics/`（gitignore 里，重跑就盖掉）：`before.tsv`、`after.tsv`、`oracle-scroll-*.{xml,tsv}`、`oracle-ribbon*.{xml,tsv}`、`dialog-{rest,scrolled,scrolled2,scrolled3}.xml`。

## 5. ribbon：改前 / 改后（真机 View 层）

两张汇总表的静止计数看着一样（360dp 那一排改前改后都是 9 / 1 / 4），这不代表什么都没改：改前那一个被切的是 `两端`，`行距 段落 撤销 重做` 四个在折线外；改后静止看得见的是 `撤销 重做 B I U 左 中 右 两端`，被切的那半截换成了 `行距`，右边那道渐隐亮着（`command-right=shown`）。谁被切、谁在折线外，看下面按控件列的那几张表。320dp 那一档改后是 8 / 1 / 5，被切的是 `两端`。页签条同理：360dp 下 `表格工具` 那一档溢出，改之后才有 `tab-right=shown`。

**改前（HEAD 那一棵树）**

| 档 | 哪一排 | 页签 | 控件数 | 视口 | 内容宽 | 可滚 | 静止：看得全/切断/折线外 | 边缘提示 |
|---|---|---|--:|--:|--:|--:|:-:|---|
| w1080px-360dp | tab | 文件 | 6 | 1080 | 900 | 0 | 6 / 0 / 0 | none |
| w1080px-360dp | command | 文件 | 5 | 1080 | 857 | 0 | 5 / 0 / 0 | none |
| w1080px-360dp | tab | 开始 | 6 | 1080 | 900 | 0 | 6 / 0 / 0 | none |
| w1080px-360dp | command | 开始 | 14 | 1080 | 1781 | 701 | 9 / 1 / 4 | none |
| w1080px-360dp | tab | 插入 | 6 | 1080 | 900 | 0 | 6 / 0 / 0 | none |
| w1080px-360dp | command | 插入 | 4 | 1080 | 552 | 0 | 4 / 0 / 0 | none |
| w1080px-360dp | tab | 引用 | 6 | 1080 | 900 | 0 | 6 / 0 / 0 | none |
| w1080px-360dp | command | 引用 | 4 | 1080 | 651 | 0 | 4 / 0 / 0 | none |
| w1080px-360dp | tab | 审阅 | 6 | 1080 | 900 | 0 | 6 / 0 / 0 | none |
| w1080px-360dp | command | 审阅 | 6 | 1080 | 987 | 0 | 6 / 0 / 0 | none |
| w1080px-360dp | tab | 视图 | 6 | 1080 | 900 | 0 | 6 / 0 / 0 | none |
| w1080px-360dp | command | 视图 | 5 | 1080 | 727 | 0 | 5 / 0 / 0 | none |
| w1080px-360dp | tab | 表格工具 | 7 | 1080 | 1116 | 36 | 6 / 1 / 0 | none |
| w1080px-360dp | command | 表格工具 | 1 | 1080 | 156 | 0 | 1 / 0 / 0 | none |
| w960px-320dp | tab | 文件 | 6 | 960 | 900 | 0 | 6 / 0 / 0 | none |
| w960px-320dp | command | 文件 | 5 | 960 | 857 | 0 | 5 / 0 / 0 | none |
| w960px-320dp | tab | 开始 | 6 | 960 | 900 | 0 | 6 / 0 / 0 | none |
| w960px-320dp | command | 开始 | 14 | 960 | 1781 | 821 | 8 / 1 / 5 | none |
| w960px-320dp | tab | 插入 | 6 | 960 | 900 | 0 | 6 / 0 / 0 | none |
| w960px-320dp | command | 插入 | 4 | 960 | 552 | 0 | 4 / 0 / 0 | none |
| w960px-320dp | tab | 引用 | 6 | 960 | 900 | 0 | 6 / 0 / 0 | none |
| w960px-320dp | command | 引用 | 4 | 960 | 651 | 0 | 4 / 0 / 0 | none |
| w960px-320dp | tab | 审阅 | 6 | 960 | 900 | 0 | 6 / 0 / 0 | none |
| w960px-320dp | command | 审阅 | 6 | 960 | 987 | 27 | 5 / 1 / 0 | none |
| w960px-320dp | tab | 视图 | 6 | 960 | 900 | 0 | 6 / 0 / 0 | none |
| w960px-320dp | command | 视图 | 5 | 960 | 727 | 0 | 5 / 0 / 0 | none |
| w960px-320dp | tab | 表格工具 | 7 | 960 | 1116 | 156 | 6 / 1 / 0 | none |
| w960px-320dp | command | 表格工具 | 1 | 960 | 156 | 0 | 1 / 0 / 0 | none |

**改后**

| 档 | 哪一排 | 页签 | 控件数 | 视口 | 内容宽 | 可滚 | 静止：看得全/切断/折线外 | 边缘提示 |
|---|---|---|--:|--:|--:|--:|:-:|---|
| w1080px-360dp | tab | 文件 | 6 | 1080 | 900 | 0 | 6 / 0 / 0 | tab-left=gone+tab-right=gone |
| w1080px-360dp | command | 文件 | 5 | 1080 | 857 | 0 | 5 / 0 / 0 | command-left=gone+command-right=gone |
| w1080px-360dp | tab | 开始 | 6 | 1080 | 900 | 0 | 6 / 0 / 0 | tab-left=gone+tab-right=gone |
| w1080px-360dp | command | 开始 | 14 | 1080 | 1781 | 701 | 9 / 1 / 4 | command-left=gone+command-right=shown |
| w1080px-360dp | tab | 插入 | 6 | 1080 | 900 | 0 | 6 / 0 / 0 | tab-left=gone+tab-right=gone |
| w1080px-360dp | command | 插入 | 4 | 1080 | 552 | 0 | 4 / 0 / 0 | command-left=gone+command-right=gone |
| w1080px-360dp | tab | 引用 | 6 | 1080 | 900 | 0 | 6 / 0 / 0 | tab-left=gone+tab-right=gone |
| w1080px-360dp | command | 引用 | 4 | 1080 | 651 | 0 | 4 / 0 / 0 | command-left=gone+command-right=gone |
| w1080px-360dp | tab | 审阅 | 6 | 1080 | 900 | 0 | 6 / 0 / 0 | tab-left=gone+tab-right=gone |
| w1080px-360dp | command | 审阅 | 6 | 1080 | 987 | 0 | 6 / 0 / 0 | command-left=gone+command-right=gone |
| w1080px-360dp | tab | 视图 | 6 | 1080 | 900 | 0 | 6 / 0 / 0 | tab-left=gone+tab-right=gone |
| w1080px-360dp | command | 视图 | 5 | 1080 | 727 | 0 | 5 / 0 / 0 | command-left=gone+command-right=gone |
| w1080px-360dp | tab | 表格工具 | 7 | 1080 | 1116 | 36 | 6 / 1 / 0 | tab-left=gone+tab-right=shown |
| w1080px-360dp | command | 表格工具 | 1 | 1080 | 156 | 0 | 1 / 0 / 0 | command-left=gone+command-right=gone |
| w960px-320dp | tab | 文件 | 6 | 960 | 900 | 0 | 6 / 0 / 0 | tab-left=gone+tab-right=gone |
| w960px-320dp | command | 文件 | 5 | 960 | 857 | 0 | 5 / 0 / 0 | command-left=gone+command-right=gone |
| w960px-320dp | tab | 开始 | 6 | 960 | 900 | 0 | 6 / 0 / 0 | tab-left=gone+tab-right=gone |
| w960px-320dp | command | 开始 | 14 | 960 | 1781 | 821 | 8 / 1 / 5 | command-left=gone+command-right=shown |
| w960px-320dp | tab | 插入 | 6 | 960 | 900 | 0 | 6 / 0 / 0 | tab-left=gone+tab-right=gone |
| w960px-320dp | command | 插入 | 4 | 960 | 552 | 0 | 4 / 0 / 0 | command-left=gone+command-right=gone |
| w960px-320dp | tab | 引用 | 6 | 960 | 900 | 0 | 6 / 0 / 0 | tab-left=gone+tab-right=gone |
| w960px-320dp | command | 引用 | 4 | 960 | 651 | 0 | 4 / 0 / 0 | command-left=gone+command-right=gone |
| w960px-320dp | tab | 审阅 | 6 | 960 | 900 | 0 | 6 / 0 / 0 | tab-left=gone+tab-right=gone |
| w960px-320dp | command | 审阅 | 6 | 960 | 987 | 27 | 5 / 1 / 0 | command-left=gone+command-right=shown |
| w960px-320dp | tab | 视图 | 6 | 960 | 900 | 0 | 6 / 0 / 0 | tab-left=gone+tab-right=gone |
| w960px-320dp | command | 视图 | 5 | 960 | 727 | 0 | 5 / 0 / 0 | command-left=gone+command-right=gone |
| w960px-320dp | tab | 表格工具 | 7 | 960 | 1116 | 156 | 6 / 1 / 0 | tab-left=gone+tab-right=shown |
| w960px-320dp | command | 表格工具 | 1 | 960 | 156 | 0 | 1 / 0 / 0 | command-left=gone+command-right=gone |

**`开始` 页签的命令行（`w1080px-360dp`）**

| 控件 | 改前边界 | 改前静止 | 改前滚到底 | 改后边界 | 改后静止 | 改后滚到底 | 改后选中态 |
|---|---|:-:|:-:|---|:-:|:-:|:-:|
| 撤销 `undo` | 1505..1637 | 折线外 | 看得全 | 12..144 | 看得全 | 折线外 | plain |
| 重做 `redo` | 1637..1769 | 折线外 | 看得全 | 144..276 | 看得全 | 折线外 | plain |
| B `bold` | 12..95 | 看得全 | 折线外 | 315..398 | 看得全 | 折线外 | selected |
| I `italic` | 95..174 | 看得全 | 折线外 | 398..477 | 看得全 | 折线外 | plain |
| U `underline` | 174..260 | 看得全 | 折线外 | 477..563 | 看得全 | 折线外 | plain |
| 左 `left` | 734..833 | 看得全 | 看得全 | 602..701 | 看得全 | 折线外 | plain |
| 中 `center` | 833..932 | 看得全 | 看得全 | 701..800 | 看得全 | 看得全 | plain |
| 右 `right` | 932..1031 | 看得全 | 看得全 | 800..899 | 看得全 | 看得全 | plain |
| 两端 `justify` | 1031..1163 | 切断 | 看得全 | 899..1031 | 看得全 | 看得全 | selected |
| 行距 `spacing` | 1202..1334 | 折线外 | 看得全 | 1070..1202 | 切断 | 看得全 | plain |
| 段落 `paragraph` | 1334..1466 | 折线外 | 看得全 | 1202..1334 | 折线外 | 看得全 | plain |
| 字体 `font` | 299..431 | 看得全 | 折线外 | 1373..1505 | 折线外 | 看得全 | plain |
| 字号 `size` | 431..563 | 看得全 | 折线外 | 1505..1637 | 折线外 | 看得全 | plain |
| 颜色 `color` | 563..695 | 看得全 | 折线外 | 1637..1769 | 折线外 | 看得全 | plain |

- 改后这一排静止时停在 `scroll_x=0`；`+state` 那一档报的是「这一段已经加粗并且两端对齐」的选中态与自动挪动。

**`开始` 页签的命令行（`w960px-320dp`）**

| 控件 | 改前边界 | 改前静止 | 改前滚到底 | 改后边界 | 改后静止 | 改后滚到底 | 改后选中态 |
|---|---|:-:|:-:|---|:-:|:-:|:-:|
| 撤销 `undo` | 1505..1637 | 折线外 | 看得全 | 12..144 | 看得全 | 折线外 | plain |
| 重做 `redo` | 1637..1769 | 折线外 | 看得全 | 144..276 | 看得全 | 折线外 | plain |
| B `bold` | 12..95 | 看得全 | 折线外 | 315..398 | 看得全 | 折线外 | selected |
| I `italic` | 95..174 | 看得全 | 折线外 | 398..477 | 看得全 | 折线外 | plain |
| U `underline` | 174..260 | 看得全 | 折线外 | 477..563 | 看得全 | 折线外 | plain |
| 左 `left` | 734..833 | 看得全 | 切断 | 602..701 | 看得全 | 折线外 | plain |
| 中 `center` | 833..932 | 看得全 | 看得全 | 701..800 | 看得全 | 折线外 | plain |
| 右 `right` | 932..1031 | 切断 | 看得全 | 800..899 | 看得全 | 切断 | plain |
| 两端 `justify` | 1031..1163 | 折线外 | 看得全 | 899..1031 | 切断 | 看得全 | selected |
| 行距 `spacing` | 1202..1334 | 折线外 | 看得全 | 1070..1202 | 折线外 | 看得全 | plain |
| 段落 `paragraph` | 1334..1466 | 折线外 | 看得全 | 1202..1334 | 折线外 | 看得全 | plain |
| 字体 `font` | 299..431 | 看得全 | 折线外 | 1373..1505 | 折线外 | 看得全 | plain |
| 字号 `size` | 431..563 | 看得全 | 折线外 | 1505..1637 | 折线外 | 看得全 | plain |
| 颜色 `color` | 563..695 | 看得全 | 折线外 | 1637..1769 | 折线外 | 看得全 | plain |

- 改后这一排静止时停在 `scroll_x=821`；`+state` 那一档报的是「这一段已经加粗并且两端对齐」的选中态与自动挪动。

**`表格工具` 页签的页签条（`w1080px-360dp`）**

| 控件 | 改前边界 | 改前静止 | 改前滚到底 | 改后边界 | 改后静止 | 改后滚到底 | 改后选中态 |
|---|---|:-:|:-:|---|:-:|:-:|:-:|
| 文件 `tab-文件` | 0..150 | 看得全 | 切断 | 0..150 | 看得全 | 切断 | - |
| 开始 `tab-开始` | 150..300 | 看得全 | 看得全 | 150..300 | 看得全 | 看得全 | - |
| 插入 `tab-插入` | 300..450 | 看得全 | 看得全 | 300..450 | 看得全 | 看得全 | - |
| 引用 `tab-引用` | 450..600 | 看得全 | 看得全 | 450..600 | 看得全 | 看得全 | - |
| 审阅 `tab-审阅` | 600..750 | 看得全 | 看得全 | 600..750 | 看得全 | 看得全 | - |
| 视图 `tab-视图` | 750..900 | 看得全 | 看得全 | 750..900 | 看得全 | 看得全 | - |
| 表格工具 `tab-表格工具` | 900..1116 | 切断 | 看得全 | 900..1116 | 切断 | 看得全 | - |

**`表格工具` 页签的页签条（`w960px-320dp`）**

| 控件 | 改前边界 | 改前静止 | 改前滚到底 | 改后边界 | 改后静止 | 改后滚到底 | 改后选中态 |
|---|---|:-:|:-:|---|:-:|:-:|:-:|
| 文件 `tab-文件` | 0..150 | 看得全 | 折线外 | 0..150 | 看得全 | 折线外 | - |
| 开始 `tab-开始` | 150..300 | 看得全 | 切断 | 150..300 | 看得全 | 切断 | - |
| 插入 `tab-插入` | 300..450 | 看得全 | 看得全 | 300..450 | 看得全 | 看得全 | - |
| 引用 `tab-引用` | 450..600 | 看得全 | 看得全 | 450..600 | 看得全 | 看得全 | - |
| 审阅 `tab-审阅` | 600..750 | 看得全 | 看得全 | 600..750 | 看得全 | 看得全 | - |
| 视图 `tab-视图` | 750..900 | 看得全 | 看得全 | 750..900 | 看得全 | 看得全 | - |
| 表格工具 `tab-表格工具` | 900..1116 | 切断 | 看得全 | 900..1116 | 切断 | 看得全 | - |

## 6. 改了什么，手机上会看见什么

1. 开始页签的命令行按手机上真正按得频繁的顺序重排：`撤销 重做 | B I U | 左 中 右 两端 | 行距 段落 | 字体 字号 颜色`。静止不动时，你那块屏上从"撤销"一路看到"两端"完整可见（改前是 `两端` 被屏幕边切一半、`撤销 重做` 在折线外）；320dp 那档静止到 `两端` 被切，往左滚一下就到。
2. 两条横向滚动行各加左右一道渐隐（`edge-tab-*`、`edge-command-*`，宽 dp(20)）：这一排真放不下时才亮，滚到头就灭。改前那一排被切掉的半个按钮和"就只有这几个"长得一模一样。
3. 换页签时这一排回到行首：上一排滚到哪儿不带进这一排。
4. 当前段落已经带的那个格式亮在按钮上（加粗 / 斜体 / 下划线 / 四种对齐），亮的是 ribbon 自己那颗 Word 蓝压出来的底色；亮着的那个如果被屏幕边切着，这一排会自己挪到它看得见的位置。看不见的状态比要多滑一下的按钮更糟。
5. 正文编辑态的头栏多了 撤销 / 重做（`edit-undo`、`edit-redo`，dp(72)×dp(36)，与"完成"同一尺寸）：不用先把 ribbon 拉下来就能撤回。

高度一个没动：页签条 42dp + 1px 分隔 + 命令行 46dp，命令按钮还是 dp(46) 高。颜色没有新增，选中底色就是 ribbon 已有的 0xFF2B579A 加透明度（浅色 0x1F…、深色 0x55…）；深色下只换底色不改字色。

## 7. 没动的地方，以及为什么

- 底部 `正文 | 查重` 那一排：从代码算是两枚各占一半屏宽（各约 540px），高 dp(40) = 120px，状态行 dp(18)。这两枚是那一屏上最大的目标，比 ribbon 的 dp(46) 命令按钮好按得多，不在这轮最该修的位置。它们的真机边界未测：那几次要量手机时，前台是别的窗口。
- `查重` 那颗弹出来的 9 项菜单在 `ApiWorkflow` 里，不属于这块界面；它的边界与项高未测。

## 8. 测不准的地方

- Robolectric 的字体度量与真机不是一套：LEGACY 下中文字宽接近 0（那一排量出来 1126px），NATIVE 下按字号给常量行高、比真机宽约 7%（同一排 1913px，真机 1781px）。所以宿主测试只断言与度量无关的事：控件在不在、顺序、行高、渐隐该不该亮、滚进来之后看不看得见。绝对像素只出现在上面真机那张表里。
- 第 3 节结果页普查的视口用的是真机量到的 1888px，内容高 2853px 是 Robolectric 量的。这两个数不能拿来预测真机上第几行落在折线外，只能证"有整行落在折线外，而且滚得进来"。
- 结果页汇总那两行在真机四份 dump 里始终没露面，这一条是靠 View 层复核证的，不是靠 dump。要把它在 dump 里也露出来，得再跑一次联网查重并做小幅滑动（一屏的三分之一那么滑），未测。

## 9. 断言在哪

| 测试 | 改前 | 改后 |
|---|---|---|
| `RibbonUITest` | 6 条 / 17 个断言 | 13 条 / 48 个断言 |
| `EditorWorkflowTest` | 2 条 / 16 个断言 | 3 条 / 22 个断言 |
| `ResultDialogCensusTest` | 没有这个测试 | 2 条 / 20 个断言 |
| `RibbonRenderTest` | 1 条 / 3 个断言 | 1 条 / 3 个断言 |

断言数按源码里的 assert 调用数计；`ResultDialogCensusTest` 里逐篇那一档在循环里，跑起来执行 10 次。

跑法：私有树里 `gradle --offline --no-daemon test -PappClasses=<私有编译产物> --tests "*RibbonUITest" --tests "*EditorWorkflowTest" --tests "*RibbonRenderTest" --tests "*ResultDialogCensusTest"`，四条全绿。宿主侧 `tools/run-suites-private.ps1 -Tree <树> -OutDir <私有目录> -Suite ScriptRegression,Regression,ReviewRegression` 通过 65 / 74 / 22 条，failed=0。

`tests/ui` 全量跑下来另有 5 条红：`AutoSpaceTest` 2 条、`WordBreakLayoutTest` 1 条、`PdfNativeTest` 1 条、`ViewportTest.homeContainsOnlyDocumentActionsAndRecentFileState` 1 条。这 5 条在干净的 HEAD 树（HEAD 的 app 源码 + HEAD 的测试源码）上一样红，量过，与这轮改动无关。
