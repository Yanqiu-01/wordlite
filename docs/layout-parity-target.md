# Line-height parity target (baseline-to-baseline)

Owned by the layout-parity sub-agent. Everything below is measured, with the command that produced it.
本文只记账，不改引擎：每一节下面都是量出来的数与量它的命令。引擎侧的改动一律单独提交，
并把真机对账的读数写回本文（第 7、13 节就是两次改完之后的实测），验收线在第 0 节。

Scope: `tests/samples/input-liu.docx`, 96-DPI document units (1 pt = 4/3 px, `PageGeometry`),
Word truth from desktop Word 16.0 COM line y-coordinates (`artifacts/agent-typeset/word-line-pitch.ps1`,
121 paragraphs / 508 lines / 380 measurable advances), our numbers from an on-device probe that runs the
working-tree engine under `app_process` (`artifacts/agent-typeset/AdvanceRepaginate.java`,
per-line advance = `StaticLayout.getLineTop(i+1)-getLineTop(i)`, plus the `+= 0.5f` that
`A4Paginator.java:196` adds when it bills the line).

## 0. 目标：APK 与原生 Word 的排版一致性，验收就这六条

总目标写死在这里：**同一篇稿子，手机上的 Wordlite 与微软 Word 必须给出同一页归属、同一条右边界、
同一组换行点、同一份逐行行高账单。** 不比"看起来差不多"，只比下面六个数；每条都带量法，
量不出来按没过算。这一节是验收线，谁改排版引擎都拿它复核；复核由常驻的排版对账子代理执行，
每轮改动重跑一遍，结果贴回本文与 `docs/edge-parity-baseline.md`。

| # | 指标 | 量法（真值来源） | HEAD 复采（改后 tag `slashglue2`，2026-10-09，engine `1feb83f`+本轮改动；改前基线 tag `265-release`，engine head_sha `6c96dc7`；两列同一台手机 EAMUT20528011355，机上装的是 2.6.6/versionCode 55，两次探针各跑自己那棵树的源码，见第 30.5 节） | 验收线 |
| --- | --- | --- | --- | --- |
| 1 | 段落页归属 | `tools/word-parity.ps1`（Word 28 页真值 `artifacts/word/pages.tsv`） | 7 段错页 / 206（exact 96.6%，页差全是 -1，首个 para 121；28/28 页，与改前 `265-release` 一字不差。同一份 PDF 真值算出的错页 6 段 → 6 段） | exact >= 99%，错页 <= 2 段 |
| 2 | 逐行行高误差中位 | `artifacts/agent-typeset/line-height-rows.ps1`（Word 相邻基线距离，COM） | -0.767 px（n=91，Word 26.267 px；改前同值，本轮改的是断点，与行高无关。归因见第 28.6 节） | 绝对值 <= 0.10 px |
| 3 | 逐行行高误差 p90 | 同上（取 \|误差\| 的 p90） | 2.533 px（max 3.467；改前同值。这 12 行落在哪几段见第 28.6 节，逐段复量 `py tools/line-height-tail.py -Capture artifacts/agent-layout-verify/slashglue2`） | <= 0.50 px |
| 4 | 每页累计高度误差 | 同上 x 每页行数（Word 每页 27-35 行） | 0.79 行（最差队列 sz12 line300 snap=false 2.55 行；改前同值） | <= 0.25 行 |
| 5 | 逐行换行点一致率（**旧口径**） | `tools/line-break-delta.ps1 -Stage report` | 117/165 = 70.9%（改前 `265-release` 115/165 = 69.7%；历史上 `hang2` 与 `hangbase2` 同值、`breakfix3`、`autospace1` 114/165 = 69.1%，最早 103/165 = 62.4%）。**旧口径：对我们自己的行序逐行号对，不可与第 7 条并列引用** | >= 90% |
| 6 | 两端对齐右边界超出 1px 的行数 | `tools/edge-parity.ps1 -Impl new` | **1 / 35（改前 1 / 33：多出来的 2 行是本轮新配对上的行，两行都在 1px 以内；越线的仍然只有原来那一行——para 160 第 14 行 563.00 px 对 Word 564.53 px，差 1.53 px，见第 24.4 节；本轮没有新增越线行）**。另加一个墨迹口径：我们越版心的 1 行，Word 自己那行也越版心，比 Word 多越 1px 以上的行数 0（第 28.3 节） | 0 / 32（守住，不许为了行高牺牲它） |
| 7 | 断点一致率（**Word 导出 PDF 真值**，新口径） | `py tools/break-agreement.py -Capture <tag>`（真值 = Word 自己导出的 PDF `artifacts/agent-typeset/pdf-truth/input-liu.pdf`，sha256 `AB298AC416DCFC72855645E5AD8E472ABDA3DFFC9A6B10925A4FAA1396E761A1`，逐行读回原文） | 230/366 = **62.8%**（103 段；多断 68、少断 68；改前 `265-release` 227/353 = 64.3%（102 段，多断 63 / 少断 63）。分段明细：只有两段变了——para 113 从 0 一致 / 2 多断 / 2 少断变成 2 / 0 / 0；para 94 是本轮才配得上对、进入比对的段，带进 1 一致 / 7 多断 / 7 少断，那是每行少装字那一族（第 30.7 节），不是本轮改出来的。**同一口径只算两次都进池的 102 段：227/353 = 64.3% → 229/351 = 65.2%**，命令 `py tools/break-common.py <改前 tsv> <改后 tsv>`。分类明细：行尾是斜杠的那一类 2 处 → 0 处。采样 `slashglue2`，`lines-all.tsv` sha `9888ADEAB7AB90AB`；上一轮的分类与页效应仍在第 28.7 节） | >= 90% |

一条命令复采这七条（前六条的结构与口径一字不动，第 7 条是本轮加上去的断点一致率，尺子见第 27 节；单跑第 7 条：`py tools/break-agreement.py -Capture artifacts/agent-layout-verify/<tag>`）：`pwsh tools/parity-six.ps1 -Tag <tag>`（真机采样 + 行高探针 + 三条对比，只调已有脚本、不重新定义量法；Word 真值走缓存，不重开 Word 会话；结果写 `<tag>/six.tsv`、`<tag>/six.txt`，带 head_sha 与 `lines-all.tsv` 的 sha）。第 0 节的六个数每一轮都以这条命令的输出为准，第 17 节记下本轮的复采与指纹。

本轮落到的 HEAD 是 tag `autospace1`（第 20 节）：`w:autoSpaceDE` / `w:autoSpaceDN` 的缺省从"没写=关"改成 Word 的"没写=开"，第 5 条 62.4% → 69.1%，其余五条一字不差（`pages.tsv` sha `921A8FAB4D3D09F8` 不变、右边界仍 0/32、`lines-all.tsv` sha `D9822E2C3396D6EC` → `AC44D355853F4616`）。

再后一轮（第 21 节，U+207B 上下标单位那条线索）没有动引擎：量完确认这个字符两边用的是同一张随包 Times New Roman、advance 也一致（0.3384 em 对 Word 的 0.338 em），所以六个数与上一行完全相同，新增的只是量台 `tools/font-advance-audit.py`（逐字把 Word 导出 PDF 的脸/advance 与我们随包字库对账）。发版前又按最新 HEAD（engine head_sha `594c1a6`，含别人那笔字库装载改动）真机复采一次，六个数与三个指纹（`lines-all` / `pages.tsv` / 字体表）全部一字不差，见第 22 节。

最近一轮（第 23 节）没有留下引擎改动：在真机应用进程里量死"每个字的 advance 被就近取整到整像素"这个缺陷之后，试过把度量那把 paint 换成线性度量
`setLinearText(true)`（40 个 `m` 的行宽 480.00 → 497.81，正好等于 hmtx 精确和），真机复采是第 5 条
69.1% → 64.8%、第 6 条 0/32 → 1/33，另外四条一项不动，于是把那 19 行整体退回原状，只把量台
（`tools/build-metrics-probe.ps1`、`tools/device-probe/MetricsActivity.java`、`MetricsManifest.xml`）留在版本库里，
下一版和"西文行的宽度多收"放在同一轮改。六个数因此仍是上表那一列。

本轮（第 33 节）也没有改引擎，量的是"换行留在行末的那个空格到底占不占宽"这一件事，七个数因此仍是上表那一列。
真机两个新 tag：`tailfit1` 与合并 main（`9e1fc6a`，2.6.8）之后的 `postmerge1`（engine head_sha `1fb8c4e`，
两次都是 28 页 14460 字，第 7 项同为 231/366 = 63.3%）。量到的东西：para 94（手机 block 95）第 2 行行末那个
空格确实收了 4.00 px 的行宽（`lineTailChars=1`、`lineTailFree=0`），把"行末空格不计宽"这一改放到 33 个受影响
段落上和 Word 断点对账是 55 处对上变成 46 处对上，所以否掉；Word 那一行真正多出来的是把收尾的全角逗号整张脸
（16.0 px）画在版心外（696.5 px 对版心 680.3 px），我们的悬挂提行只认下一行第 1、2 个字，提不动 `MPa，` 里的
逗号。另外两件事按实测纠正：`431933f` 在这台仓库查不到对象，能查到的 main（`9e1fc6a`）引擎在手机上跑出来
para 94 第 2 行仍是 39 字 / 断点 70；`Cu-Sn/TLP/10 min` 这一串在样稿与真值 PDF 里都搜不到，而真值 PDF 810 行
正文里没有一行以 `/` 结尾，所以"斜杠不是断点"与真值一致，不动。数字与命令在第 33 节。


再后一轮（第 26 节）先把"引擎到底收到了什么"量死：全篇只有封面第 0 段写了 w:lineRule="exact" w:line="380"（19 磅），没有任何 20 磅 / 16 磅的行距声明；Word 自己的读数是 Exactly 0 段、AtLeast 0 段、相邻基线正好 20.0pt 的段落 0 个。该改的还是改了：exact 当一把绝对长度算（真机量到 1pt=1.333333px、12pt 的 em=16.0000px，比例 1.000，不是 1.0741），画整像素行盒、小数那一截走 lineCarry。六条一个没动（两边 `lines-all.tsv` 同一个 sha `5F714EE762A78345`），页数 28=28，7 段错页全是 -1，见第 26.4 节。

最近一轮（第 28 节，改后 tag `hang2`，改前基线 tag `hangbase2`）把 Word 的 `w:overflowPunct` 按它自己导出
PDF 的真值改了：一个收尾标点挤不进本行时不再被整行挤下去，而是只占版心里剩下的那点位置、其余画在版心外
（第 28.1、28.2 节）。七条里第 7 条 63.4% -> **64.3%**（多断 65 -> 63、少断 65 -> 63），第 1 条 7 段错页、
第 2/3/4 条行高、第 5 条旧口径、第 6 条 1/33 一项没退。顺带把两件事量死：第 3 条 p90 = 2.533 px 全部来自
12 行、两类声明（第 28.6 节），"我们多断"的 63 处里真正属于"字母数字串内部"的只剩 1 处，而且就是上下标
span 那处 `Ag3|Sn`（第 28.7 节）。这一轮还逮到量台自己的一次假数：探针表头少写一个换行符，所有按列名取值的
脚本静默读出 0（第 28.5 节）。

发版后复采（第 29.1 节，tag `265-release`）：手机上装的是 2.6.5 发布包（`versionName=2.6.5 versionCode=54`，
`adb shell dumpsys package`），探针跑的源码 = `head_sha 6c96dc705ab2`（出 2.6.5 那条提交，快照 overlay=0），
七个数与上表**一字不差**，`lines-all.tsv` 同一个 sha `11241D5CED2CA4A5`：页数 28 = Word 28、页归属 7 段错页（全 -1）、
行高 -0.767 / 2.533 px、每页 0.79 行、旧口径 69.7%、右边界 1/33、第 7 条 227/353 = 64.3%。

本轮（第 30 节，改后 tag `slashglue2`，改前基线 tag `265-release`）动的是断点选择里最刺眼的一族：斜杠不是断点。真机第 8 页把 `Cu/SB/P-Cu/SB/Cu` 断成 `…SB/C` / `u 夹层结构` 那一类字母串内部断行，根因是平台允许在 `/` 之后断行而 Word 不允许。改完七条：第 1 条 7 段错页与 28/28 页一字不差，第 2/3/4 条行高一字不差，第 5 条 69.7% → 70.9%，第 6 条越线的仍是同一行（分母 33 → 35 是本轮新配对上的两行，都在 1px 内），第 7 条 227/353 = 64.3% → 230/366 = 62.8%——这个百分比降的原因写在第 30.5 节：para 94 因为行数和 Word 对上了才第一次进比对，把它自己那 7 处"每行少装字"带进池子；把两边共同的 102 段单独算，是 64.3% → 65.2%，而"行尾是斜杠"这一类从 2 处归零。真机可见的变化：三处斜杠断行全部消失，全文 600 行没有一行以 `-` 或 `/` 开头。


三条规矩：

1. 真值只有两个来源——桌面 Word 16.0 COM 的逐行基线/页码坐标，和手机上微软 Word 的实际渲染。
   任何一侧的 capture 都要记 sha256 与引擎指纹，对不上 HEAD 的 capture 一律作废（`word-parity` 已实现）。
2. 行高一族的问题按队列分开算：中文 snap=true、中文 snap=false、纯西文、含上下标、不含上下标。
   只看汇总会把"抬错了脸"当成"抬对了"，2.1.1 那版就是这么把 19 段参考文献抬高一档的（第 7 节）。
3. 一次改动只要有一条退步，必须在 CHANGELOG 里写清退了哪条、退了多远，以及为什么仍然值得改；
   只报涨的那条不算对账。上下标不许再当成独立成因重提一次：第 4 节已经量过，它的问题在第 8 节那条网格跳格上。

## 1. Word's advance, by cohort (px, median of that paragraph's line advances)

| cohort | n | Word px | ours px | delta med | p10 | p90 | min | max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| all joined paragraphs (>=2 lines) | 85 | 26.267 | 23 | -0.767 | -3.267 | -0.533 | -3.267 | +1.333 |
| sz=12 w:line=300 snapToGrid=true | 37 | 26.267 | 25.5 | -0.767 | -0.767 | -0.767 | -0.767 | -0.700 |
| sz=12 w:line=300 snapToGrid false/absent | 40 | 26.267 | 23 | -2.267 | -3.267 | +0.333 | -3.267 | +1.333 |
| sz=12 w:line=300, CJK text | 58 | 26.267 | 25.5 | -0.767 | -3.267 | -0.767 | -3.267 | -0.700 |
| sz=12 w:line=300, Latin only (references) | 19 | 23.533 | 23 | -0.533 | -0.600 | +0.467 | -0.600 | +1.333 |
| contains a super/subscript run | 32 | 26.267 | 25.5 | -0.767 | -3.267 | -0.733 | -3.267 | +1.333 |
| no super/subscript (control) | 53 | 26.267 | 23 | -0.767 | -3.267 | -0.533 | -3.267 | +0.333 |

Exact agreement on a line advance: 0/85 within 0.1 px, 4/85 within 0.5 px, 61/85 within 1 px.

Word's own line-by-line values are quantised on a ~0.675 pt lattice (15.6, 16.3, 17.0, 17.65, 18.35,
19.0/19.05, 19.65/19.7), so a per-paragraph least-squares constant fits 51 paragraphs with
max residual 0.31 pt; the paragraph constant for the dominant class is 19.44 pt = 25.92 px, and the
per-line median is 19.7 pt = 26.267 px. Both agree that the class advance is 25.9-26.3 px.

Cumulative error per page: -0.767 px x 27 lines = -20.7 px = 0.80 of one line; the worst cohort
(snapToGrid false/absent, -3.267 px) reaches -88 px = 3.4 lines. That is what lets one extra line
onto a page and puts 7 paragraphs one page early.

## 2. What Word's 26.267 px is *not* (all measured on this machine)

`song.ttc` (our bundled 宋体) and the installed `C:\Windows\Fonts\simsun.ttc` have identical metrics, so
the gap is not "we shipped a different 宋体":

| source of the number | 宋体 / SimSun | Times New Roman (ours) |
| --- | --- | --- |
| head unitsPerEm | 256 | 2048 |
| OS/2 usWinAscent / usWinDescent | 220 / 36 -> ratio 1.000 em | 1825 / 443 -> ratio 1.1074 em |
| OS/2 sTypoAscender / sTypoDescender | 220 / -36 -> 1.000 em | 1420 / -442 -> 0.9092 em |
| + sTypoLineGap (36 / 307) | 1.1406 em | 1.0591 em |
| hhea ascender / descender | 220 / -36 -> 1.000 em | 1825 / -443 -> 1.1074 em |
| hhea + lineGap (GDI+ `FontFamily.GetLineSpacing`) | 292/256 = 1.1406 em, `Font.GetHeight`=18.25 px | 2355/2048 = 1.1499 em, 18.4 px |
| GDI `GetTextMetricsW` at 12 pt / 96 dpi | tmHeight 16 (asc 14, desc 2, intLead 0, extLead 2) -> 1.000 em | (face falls back to 宋体 via GDI name lookup) |

Multiply each single-line height by `w:line/240 = 1.25` and none of them reaches Word's 26.267 px
they land at 20.0, 22.8 and 22.5 px. Word's implied single line is 26.267 / 1.25 = 21.01 px
15.76 pt = **1.313 em** (least squares: 1.296 em); for the Latin reference lines Word's implied single
line is 23.533 / 1.25 = 18.83 px = **1.177 em** against Times' 1.1074 em from the table. So Word's
single-line height is an empirical per-font constant that is 0.07 em (Latin) to 0.31 em (CJK) above
every table sum we can read. Conclusion: do not look for a metric table that explains it - carry
measured per-font line-height ratios.

## 3. Our 23 px, arithmetically

```
FontScriptMetrics.java:22        lineHeightRatio = winAscentRatio + winDescentRatio
   宋体 (song.ttc)          = (220 + 36) / 256  = 1.0000 em
   Times New Roman (ours)  = (1825 + 443) / 2048 = 1.1074 em
DocxTextLayout.java:559          runH = pt * max(mEA.lineHeightRatio, mLatin.lineHeightRatio)
   = 12 * max(1.0000, 1.1074) = 13.289 pt          <- the Latin font sets a CJK line's height
DocxTextLayout.java:1081         singlePx  = round(13.289 * 96/72) = round(17.719) = 18
DocxTextLayout.java:1088         desired   = round(18 * 300/240)   = round(22.5)   = 23 px
DocxTextLayout.java:1100-1108    snapToGrid: gridHeight = round(twips(360)) = 24; line 1106 gridHeight += 1 -> 25
                                 desired = max(23, 25) = 25 px
A4Paginator.java:188-196         gridAdvance -> heights[i] += 0.5f -> billed 25.5 px
```

Measured billed advances come out exactly as this predicts: 23 px (snap false/absent), 25.5 px
(snap true), 21 px (`w:line=240`), 24 px (Latin paragraph carrying a superscript run).

`w:docGrid` in the sample is `<w:docGrid w:linePitch="360" w:charSpace="0"/>` with no `w:type`
24 px grid rows. Word measures 25.8 px (snapToGrid false) against 25.93 px (snapToGrid true) for the
same 12 pt / `w:line=300` text, a 0.13 px difference: the grid is inert in Word, so our grid floor is
modelling something Word does not do - and removing it before the ratio is fixed makes pagination worse,
because that accidental +2 px is what currently keeps 37 paragraphs close to Word.

## 4. Super/subscript, isolated

| population | ours px (values x count) | Word px (values x count) |
| --- | --- | --- |
| Latin only, no script | 21x5 23x17 (median 23) | 20.87x1 21.73x4 22.67x2 23.53x9 23.6x6 (median 23.533) |
| Latin only, has script run | 21x1 24x2 (median 24) | 21.73x1 22.67x1 23.53x1 (median 22.667) |
| CJK, no script | 21x1 23x13 25.5x17 (median 25.5) | 23.53x1 26.2x1 26.27x29 (median 26.267) |
| CJK, has script run | 23x7 24x1 25.5x21 (median 25.5) | 24.47x1 26.27x28 (median 26.267) |

- A script run costs us +1 px per line on Latin lines (23 -> 24, 2 of 3 paragraphs) where Word's script
  line is 0.87 px *shorter* than its control. Sign inverted, magnitude 1 px = 3.8% of a line.
- Inside CJK paragraphs the script and control medians are identical (25.5 vs 25.5) and so are the
  deltas (-0.767 both), which is why the script cohort and the control cohort moved together in the
  earlier runs: the shared -1.27 px/page-row offset is the engine-wide advance deficit of section 3,
  not a script effect.
- Per-line on-device lift, script line vs its sibling lines in the same paragraph, 37 paragraphs where
  both are measurable: 0 px in 23, +1 px in 5, and 8 rows of |19-25 px| which are paragraph-split /
  image boundaries inside the fragment, not line boxes. Word's own lift over the same lines is
  +0.017 pt median, |max| 0.70 pt.
- So the reported "上下角标导致分页不良" is not reproduced as a line-height lift. The superscript-adjacent
  evidence points elsewhere: 7 of the 20 paragraphs whose page assignment differs from Word contain
  superscript runs, but their advance error equals their cohort's (-0.767 or -3.267 px), and the
  remaining 13 do not contain any.

## 5. Target rule, and the code sites

```
singlePx(font, size)  = sizePt * R(font)                      R measured: CJK 1.313 em, Latin 1.177 em
advance(line)         = singlePx_of_max_run_on_that_line * (w:line / 240)   kept float, no rounding
top(i)                = round(sum of advance(0..i-1))          round once per line top, not per line
```

1. `FontScriptMetrics.java:22` - `lineHeightRatio` is the only font term that reaches line height and for
   SimSun it is exactly 1.000 em against Word's 1.313 em. Replace with measured per-font ratios
   (or keep the win sum and add the CJK surplus as its own constant: 宋体 +0.313 em, Latin +0.069 em).
2. `DocxTextLayout.java:559` - `max(mEA, mLatin)` lets the Latin metrics set a CJK line's height, which is
   why the error is cohort-dependent (23 px for CJK, near-correct 23 px for the Latin references).
3. `DocxTextLayout.java:1081` and `:1088` - the two-step integer rounding; float model on our own metrics
   gives 22.15 px instead of 23 px, so this is the second-order term.
4. `DocxTextLayout.java:1106` `gridHeight += 1` and `A4Paginator.java:196` `heights[i] += 0.5f` - remove
   together with (1), never alone.
5. `DocxDocument.java:238-239` `snapToGrid` defaults to false where OOXML defaults to true
   (`snapToGridSet` is false for 170 of 373 body paragraphs). Worth aligning, but Word's own
   snap/no-snap difference is 0.13 px, so it is not the defect.

## 6. Acceptance floors (proposed, per metric)

| metric | tool | measured now | floor |
| --- | --- | --- | --- |
| paragraph page ownership | `tools/word-parity.ps1` | 7 of 206 wrong (96.6% exact) | >= 99% exact, i.e. <= 2 wrong |
| per-line advance error, median | `artifacts/agent-typeset/line-height-cohorts.ps1` | -0.767 px | abs median <= 0.10 px |
| per-line advance error, p90 | same | 3.267 px | <= 0.50 px |
| cumulative height error per page | same, delta x lines/page | 0.80 line (worst cohort 3.4) | <= 0.25 of a line |
| line break points | `tools/line-break-delta.ps1` | 103/165 = 62.4% | >= 90% |
| justified right edge, rows > 1 px off | `tools/edge-parity.ps1 -Impl new` | 0 of 32 | 0 of 32 (hold) |

## 7. 候选版（WordLineHeights 打开）三项实测：逐项进步/退步

跑法：`artifacts/agent-typeset/run-repaginate.ps1`（探针编工作树）+ `artifacts/agent-typeset/line-height-rows.ps1`
+ 三件套。候选版产物 `artifacts/agent-typeset/device-cand`、`repag-cand2`；回退后产物
`artifacts/agent-typeset/device-cand4`、`repag-cand3`。两份 capture 的 `lines-all.tsv` 差在页码列，
行宽列一字节不差（候选 sha `2A9E7D6F18F5DED3` vs HEAD `F6FC9574FC9BBC07`，都是 84757 字节）。

A 行距（逐行配对 90 行，Word 相邻基线距离为真值，单位 px）：

| cohort | n | Word | ours 画出来的行盒 | ours 记账行高 | delta 中位 | p90 | min | max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 全部 | 90 | 26.267 | 26 | 26.267 | +0.0003 | +0.934 | -0.534 | +3.600 |
| sz12 line300 snap=true | 62 | 26.267 | 26 | 26.267 | +0.0003 | +0.0003 | +0.0003 | +0.934 |
| sz12 line300 snap=false | 16 | 26.267 | 26 | 26.267 | +0.867 | +3.600 | +0.0003 | +3.600 |
| sz12 line300 纯中文 | 74 | 26.267 | 26 | 26.267 | +0.0003 | +0.934 | +0.0003 | +1.934 |
| sz12 line300 纯西文 | 4 | 23.533 | 26 | 26.267 | +3.600 | +3.600 | +2.734 | +3.600 |
| 含上下标 run | 48 | 26.267 | 26 | 26.267 | +0.0003 | +0.934 | -0.534 | +1.934 |
| 不含上下标（对照） | 42 | 26.267 | 26 | 26.267 | +0.0003 | +0.934 | -0.301 | +3.600 |

- 中文正文行从中位 -0.767 px 变成 +0.0003 px：这一档是**进步**，且上下标队列与对照队列的 delta 完全相同，
  确认第 4 节的判断——上下标不是独立成因。
- 退步的三处，全是"把不该抬的行也抬了"：纯西文参考文献行被抬到中文行高（+2.734~+3.600 px/行，
  因为 `WordLineHeights.tallest` 用 `max(mEA, mLatin)` 选脸，不看这一行里到底有没有中文字符）；
  snap=false 那一档中位 +0.867、最高 +3.600；含下标的行在网格里整行翻倍（见下）。
- 整篇按行累计：候选版比 HEAD 多算 **1385.8 px = 1.6 个版心页**（223 个块逐块 行数 x 行高差 相加），
  页数 28 -> 30（Word 28）。其中两条段首含下标的行 25.5 -> 50.267 px（block 156/161，共 +272 px）、
  一条目录条目 24.5 -> 48.216 px（block 30）：`ScriptGeometry.lineBox` 里
  `rows = ceil((ascent+descent)/gridPitchPx)`，实测比值把 auto 行盒从 25 抬到 26，越过 gridHeight=25 那一格，
  于是长高的行直接跳到两格。Word 对这些行量到的还是 26.267 px。

B 分页归属（`tools/word-parity.ps1`）：

| 项 | HEAD / 回退后 | 候选版 | 判定 |
| --- | --- | --- | --- |
| 总页数 | 28（Word 28） | 30 | 退步 +2 |
| 错页段落 | 7 / 206（exact 96.6%） | 74 / 206（64.1%） | 退步 +67 |
| 页差直方图 | -1=>7 0=>199 | 0=>132 1=>70 2=>4 | 方向从"早一页"翻成"晚一页" |
| 首个错页段 | para 121 | para 56（目录 4.1 进度安排） | 退步，漂移提前到目录 |
| 含上下标段 | 34 对齐 / 1 错 | 34 对齐 / 6 错 | 退步 +5 |

C 两端对齐右边界（`tools/edge-parity.ps1`）：候选版**无变化**，因为候选版只动行高不动行宽。
`justify-not-last` n=32 mean|x|=0.99 median 0.20 p90 0.93 max 15.93、右边界 1 px 以内 32/32、
我们自己的参差 0.00 px（Word 1.94 px）、目录 5 条 mean|x|=0.56、两条带缩进的条目 mean|d|=0.60。

## 8. 网格地板：撤 vs 留（同一篇、同一探针，只换取行高的口径）

`artifacts/agent-typeset/AdvanceRepaginate.java` 加了两种重放模式：`no_row_snap`（只把被网格整行顶起来的
行还原成无网格行高）与 `grid_off`（文档网格完全不参与行高）。无网格口径不是我猜的：探针反射拿到该段
`Spacing` span 的 `desiredHeight`/`gridPitchPx`/`clipScripts`，逐行调引擎自己的 `scriptSpace()`，
再把 `ScriptGeometry.lineBox()` 分别按真实格距和 0 各算一遍；自校验 `autoModelMismatch=0`
（307 个被网格抬高的行、7 个整行翻倍的行之外，逐行都能复现 StaticLayout 的行盒）。

对 Word 页归属打分（`repag-score.ps1`，209 组段落对齐；绝对数只用于 A/B，闸门数以 `word-parity` 为准）：

| 模式 | 页数 | exact | mismatch |
| --- | --- | --- | --- |
| real（引擎真实分页） | 28 | 180 | 29 |
| current（现状：留网格） | 28 | 178 | 31 |
| no_row_snap（只撤整行跳格） | 28 | 178 | 31 |
| grid_off（整条撤网格地板） | 28 | 177 | 32 |
| no_carry（把小数余量也去掉） | 28 | 177 | 32 |

结论：**留**。撤掉地板要把 307 个行拉回它们自己的 auto 行高（23/25 px，比现在离 Word 的 26.267 更远），
对页归属是净损失。`no_row_snap` 在回退后的引擎上中性，但它只在实测比值那种情形下才要紧——那时整行跳格
一次就多吃 24 px。这两格现在都由 `WordLineHeights.APPLIED_TO_LAYOUT=false` 挡着，谁重开开关必须先处理它。

## 9. 目标一：Word 13 -> 14 页那一页页尾，逐段账本（COM 实测）

量法：`artifacts/agent-typeset/word-page-stack.ps1`（`Information(3)` 页码、`(6)` 距页顶 pt、
`(10)` 页内行号），产物 `artifacts/agent-typeset/page-stack/`。第 2、3 节版心 top=107.7 pt、
bottom=85.05 pt、文字高 649.15 pt -> 版心底边在页顶下 756.85 pt。

Word 第 13 页（段号是 0 基 `para_index`，与 `artifacts/word/pages.tsv` 同口径）：

| 段 | 页内行 | 首行 y | 末行 y | 该段占到（pt） | 备注 |
| --- | --- | --- | --- | --- | --- |
| 115 | 1..12 | 108.00 | 321.95 | 235.70 | 12 行，行距 19.45-19.70 pt |
| 116 | 13 | 343.70 | 343.70 | 98.50 | keepNext=true 的图段，占位 98.5 pt = 131.3 px |
| 117 | 14..15 | 442.20 | 457.80 | 39.40 | 图题，10.5 pt，段后 8 pt |
| 118 | 16 | 481.60 | 481.60 | 32.60 | 15 pt 小标题，w:line=324，段前后 6 pt |
| 119 | 17..24 | 514.20 | 650.05 | 155.55 | 8 行 |
| 120 | 25..27 | 669.75 | 708.45 | 58.40 | 本页最后一段 |

合计 620.15 pt = **826.9 px**，版心底边还剩 756.85 - (708.45 + 19.7) = **28.70 pt = 38.3 px**。
一行要 19.7 pt：放得下 1 行，放不下 2 行。`w:widowControl`（settings.xml 里没有覆盖，走 OOXML 默认开）
要求段首段尾各至少 2 行，于是下一段 121（3 行）整段被推到第 14 页。

我们同一页（`device-cand4/new/lines-all.tsv` + `repag-cand3`，`pages.tsv` 的 usedHeight=856）：
block 109 12x25.5=306、block 110 图 126.533、111 图题 2x19=38、112 标题 1x30、113 8x23=184、
114 3x23=69、115 23+23+24=70。把 115（= Word 的 121）拿掉，我们页尾还剩 865.53-786 = **79.5 px = 3.4 行**，
widow/orphan 直接满足，所以它留下了。

差额拆项（同一页同一批内容）：11 行 23 vs 26.267 = -36.0 px；12 行 25.5 vs 26.267 = -9.2 px；
图 126.533 vs 131.33 = -4.8 px；合计约 -50 px，正好对上两边页尾剩余空间之差 86 - 38.3 = 47.7 px。

**所以：页尾没有一次性的 25 px 窟窿。**第 13 页的机制是"逐行少算的钱在这一页攒够了一行多"，
而把它变成翻页的那条规则是 widow/orphan（Word 剩 1 行 < 2 行就整段搬走）。
7 个错页段全都是"Word 某页的第一段"，各页界处 Word 的实测余量（`page-stack/page_lines.tsv`）：

| Word 页 | 末行 y(pt) | 版心底剩 px | 放得下几行 | 被搬走的段 |
| --- | --- | --- | --- | --- |
| 13 | 708.45 | 38.3 | 1（widow/orphan 挡下） | 121 |
| 17 | 732.90 | 5.7 | 0（纯容量不够） | 155 |
| 20 | 735.60 | 2.1 | 0 | 208 |
| 24 | 717.30 | 26.5 | 1（widow/orphan） | 382 |
| 26 | 717.30 | 31.9（按 10.5 pt 参考文献行盒 15.6 pt） | 2 行盒以内 | 397 |
| 27 | 694.20 | 62.7（同上） | 3 行盒以内，[25] 放不下尾行 -> orphan | 408、409 |

## 10. 目标二：para 79 第 21 行为什么 Word 多摊 16 px

真值行（`artifacts/word-justify/lines-summary.tsv`）：36 个字符，文本
`的孔隙率、孔径、孔壁厚度和孔道连通性，建立多孔铜结构参数与其导电、导热、`，末字符是 `、`；
`x_last = 425.1 pt`（正好压在版心右边上），`right_edge_pt = 437.2 pt`，`dev_from_right = +12 pt`、
`dev_px = +16`。该段的 `w:pPr` 里有 `<w:kinsoku/><w:overflowPunct/><w:snapToGrid/>`、
`w:lineRule="auto" w:line="300"`、`w:jc w:val="both"`（`word/document.xml` 里 `overflowPunct` 出现 105 次，
`settings.xml` 不设 = 走默认开）。

规则：**`w:overflowPunct` + `w:kinsoku` 的合用效果**。kinsoku 禁止 `、` 这类收尾标点做行首，
所以它不能挪到下一行；overflowPunct 允许这个收尾全角标点按自己的整个字宽（这里 12.1 pt = 16.13 px）
**整颗挂在右版心之外**，前 35 个字仍然两端对齐到版心边上（Word 实测 gap_pattern
`12.05x2 12.1x30 12.65x2 12.7x1`，35 个间距合计 424.5 pt = 版心宽 425.2 pt）。
我们这边把 36 个字全塞进版心，所以右边界停在 566.93 px（= 版心），对 Word 的测量边就是 -15.93 px。
引擎已经把 `overflowPunct` 解析进 `DocxDocument.java:260-261`，但排版链路（`DocxTextLayout`）没用它。

对闸门的影响：`edge-parity` 的"1 px 以内 32/32"量的是我们的墨迹到版心，这条本来就赢；
`bucket max|x| = 15.93` 全是这一行，且它是 32 行里唯一 `word_gap_px` 恰为一个全角字宽的行。
建议闸门把 `word_gap_px ~= +一个全角字宽` 判为悬挂标点而不是缺边，真要像素级一致就得实现 overflowPunct。

## 11. "Word 每页只收 33 行"这条：量了，不成立为常数

Word 每页显示行数的实测分布（`page-stack/page_lines.tsv`，`Information(10)` 的最大值）：
第 2 页 33、第 20 页 33、第 28 页 33、第 26 页 34、**第 27 页 35**、第 13 页只有 27（还剩 38.3 px）。
没有一条"33 行"的硬上限，收页的是版心高度(649.15 pt) + widow/orphan 的 2 行门槛。
所以 `PageBreaker.Item.hang` 反向用（版心底边留白）这条不该做：第 13 页 Word 明明还剩一行空间，
它翻页是因为放不下第二行，不是因为留了一行白。

## 12. 复跑（一次一条命令）

```
# 1) 真机 capture（编译工作树，装在手机上的 APK 不参与）
pwsh tools/capture-device.ps1 -Serial EAMUT20528011355 -Impls new -OutDir artifacts/agent-typeset/device-cand4
# 2) 分页归属（闸门：错页段 <= 10）
pwsh tools/word-parity.ps1 -Impl new -Device artifacts/agent-typeset/device-cand4/new/paragraphs-wordformat.tsv `
    -Sup artifacts/agent-typeset/device-cand4/new/superscript-inventory.txt `
    -Summary artifacts/agent-typeset/device-cand4/new/summary.txt -OutFile artifacts/agent-typeset/parity-cand4.tsv
# 3) 右边界
pwsh tools/edge-parity.ps1 -Impl artifacts/agent-typeset/device-cand4/new -Out artifacts/agent-typeset/edge-parity-cand.tsv
# 4) 行高逐行账单（探针先跑 run-repaginate.ps1）
pwsh artifacts/agent-typeset/run-repaginate.ps1 -Serial EAMUT20528011355 -OutDir artifacts/agent-typeset/repag-cand3
pwsh artifacts/agent-typeset/line-height-rows.ps1 -Adv artifacts/agent-typeset/repag-cand3/out/repag-advances.tsv `
    -OutTsv artifacts/agent-typeset/line-height-rows.tsv -OutTxt artifacts/agent-typeset/line-height-rows.txt
pwsh artifacts/agent-typeset/repag-score.ps1 -Map artifacts/agent-typeset/repag-cand3/out/repag-map.tsv
# 5) Word 侧页账本（要开着桌面 Word 16.0）
pwsh artifacts/agent-typeset/word-page-stack.ps1 -Pages 12,13,14,15 -OutDir artifacts/agent-typeset/page-stack
```

过期产物提醒：`artifacts/device/old`（29 页 / 50 错页）不再引用；行高档的对比基准是本文第 1 节那张表。

## 13. cand-lh1：修完选脸与网格跳格再开实测行高，赢在行距、输在页归属

改动三处：`WordLineHeights.tallest` 按字符选脸（有中日韩文字或全角标点才算中文脸，西文/数字/半角算
西文脸）；`ScriptGeometry.lineBox` 新增 `snapGrownRow`，行高走实测值的段落不再把长高的行补齐到整格；
`APPLIED_TO_LAYOUT=true`。跑法同第 12 节，产物 `artifacts/agent-layout-verify/cand-lh1`，
`lines-all.tsv` sha256 前 8 位 `F3378E06`（HEAD 是 `F6FC9574`，同为 84757B：宽度列 0 行差、页码列
205 行差——只动了高度，断行一字未动）。Host 侧 59 条行高断言全过。

| 第 0 节指标 | HEAD | cand-lh1 | 底线 | 判定 |
| --- | --- | --- | --- | --- |
| 1 页归属 exact / 错页段 | 96.6% / 7（全 -1） | 65.05% / 72（全 +1） | ≥99% 且 ≤2 | 退 65 段 |
| 2 逐行行高误差中位 | -0.767 px | **0.000 px**（n=90） | ≤0.10 px | 过（HEAD 不过） |
| 3 逐行行高误差 p90 | 3.267 px | **0.934 px**（max 1.934） | ≤0.50 px | 改善 3.5×，仍未过 |
| 4 每页累计高度误差 | 0.79 行 | 中位口径 0.00 行 / 均值口径 0.33 行 | ≤0.25 行 | 中位过、均值不过 |
| 5 断点一致率 | 99/154 = 64.3% | 99/154 = 64.3% | ≥90% | 持平（本轮不该动它） |
| 6 右边界 >1px | 0 / 32 | 0 / 32 | 0 / 32 | 守住 |
| 总页数（Word 28） | 28 | 29 | 28 | 退 1 页 |
| 全篇记账高度 | 14660.0 px = 16.94 版心页 | 15528.9 px = 17.94 版心页 | — | +868.9 px = 整一页 |
| 含上下标段 对齐/错 | 33 / 1 | 28 / 6 | — | 退 5 段 |
| 整行翻倍（≤12.5pt 且 advance≥40） | 0 行 | **0 行** | 0 行 | 2.1.1 那个坑确认已消 |
| 纯西文参考文献行 | 24 px（2.1.1 候选曾被抬到 26.267） | **23.533 px = Word** | — | 赢 |

三条读数：

1. 行距这一族基本赢了：中文正文行逐行对上 Word 的 26.267px，纯西文参考文献行对上 23.533px，
   两条同时成立（这正是 2.1.1 那版做不到的）。残余误差只剩两处：Word 自己的西文行距在
   22.667/23.533 两档交替，一个常数比值追不上；Word 的上下标行比正文行**还矮**（25.333 vs 26.267），
   我们反而多给 1px——`ScriptGeometry` 只会长高，不会为上下标变矮，这条得另外拿真值才能补。
2. 页归属的形状很干净：72 段全部 +1（晚一页），首个错页段从 121 提前到 57（目录 4.2）。Word 在
   这几个页界的实测余量是 2.1-5.7px（纯容量）与 26.5-58.2px（widow/orphan 挡下），也就是说 Word
   的页是排到只剩几 px 的，我们每页多算 ~8.6px 就足以整页后挪。
3. 最要紧的一条：cand-lh1 全篇多算 868.9px，而行高按行补的钱正好是这个量级（约 1130 行 × 0.767px）。
   所以 **HEAD 的 96.6% 是两处反向误差抵出来的假平账**：行高每行少算 0.767px，另一处每页多算约
   8.6px。开关保持关闭（出厂 false，Host 闸门钉着），等第 2 项那处多算被单独定出来并修掉再开；
   调小比值去凑页码是不许的（第 0 节第 3 条）。

## 14. 段前后距这笔账：Word 的算法是"行距 + 声明段距"，不是网格取整

口径：Word 侧 gap = 下一段首基线 - 上一段末基线；净段距 = gap - 上一段自己的行距（只在上一段 ≥2 行时可算）。
我方侧 billed = PageBreaker 实际消费的 max(prev.after, next.before)，由探针反射调
`A4Paginator.effectiveSpacingTwips` 得到（探针 `artifacts/agent-layout-verify/gaps/GapProbe.java`，
engine-vs-mirror mismatch=0，228 块全等）。可配对边界 166 条。

| 类别 | 边界数 | Word 净段距 中位/均值 | 我方 billed 中位/均值 | 我方合计 |
| --- | --- | --- | --- | --- |
| 目录（para 25..56） | 31 | 0（gap 恰好等于它自己的 24.47/25.33 行距档） | 0 / 0 | 0 |
| 编号项 | 23 | 0.00 / 0.04 | 0 / 0.52 | 12.0 px |
| 正文 | 71 | 0.00 / 2.68 | 0 / 4.00 | 284.0 px |
| 图表题注 | 7 | 10.93 / 11.33 | 22.67 / 17.53 | 122.7 px |
| 标题 | 34 | 推不出（前一段全是单行） | 12.0 / 12.54 | 426.4 px |
| 合计 | 166 | — | 0 / 5.09 | 845.1 px |

四条读数：

1. **没有网格取整这回事。** 166 条边界里落在 24/48/72 ±0.6 px 的只有 26 条，48 与 72 附近一条都没有；
   Word 的 gap 全堆在它自己的行距档位上（22.6 / 24.47 / 25.33 / 25.4 / 26.27），净段距 0~0.4 px。
   所以 Word = 行距 + 声明段距，`A4Paginator` 里任何"段距向上取整到整格"的模仿都是无据的。
2. **目录页两边一致**：Word 那 31 条目录边界的净段距是 0，我方也是 0。第 2 页那 27.5px 超支不在目录行上，
   全在 `目 录` 标题段自身（我方 43.2 px 段距 + 32 px 行盒）。这条待第 15 节的页账定性，别急着改。
3. **`beforeLines/afterLines` 的单位我们取错了，但这条先不改。** 稿件自查（tests/samples/input-liu.docx）：
   39 段同时写了 lines 与 twips 两种写法，按 implied = twips x 100 / lines 反推 Word 自己的"一行"：
   62 对给 390/391 twips（19.5 pt，style0/style2/style3），7 对给 361 twips（≈ 文档网格 360，style1）。
   而 `effectiveSpacingTwips` 一律用 section grid pitch（360 twips = 24 px），对那 62 对少算约 8%
   （`目 录` 标题：beforeLines=100 我们给 24.0 px，Word 自己写的 before=391twips=26.07 px）。
   不改的理由与第 13 节同源：这条改完是**多算钱**，而我们已知的病就是多算钱（cand-lh1 多算 868.9px）。
   等页账对平、那处反向多算定出来之后，再与它一起进，且必须带一次新的真机对账。
4. **解析器有一个真缺口**：`w:beforeAutospacing/w:afterAutospacing` 在 document.xml 出现 170 次
   （85 个 w:spacing 带它），我们的解析器完全不认这个属性。本稿全是 "false" 所以没吃进偏差，
   换一份 autospacing=true 的稿子就会错。本轮不凭猜实现，记在 ROADMAP 等真值。

另外更正第 13 节转过来的一句话：我方第 11 页那 339.8 px 差额**不是段前后距**（该页段距只有 42.7 px），
而它多半也不是引擎真多算了 340 px——逐页"逐项高"是按块的起始页记账的，跨页大块会把钱记错页。
下一轮要让探针按 PageBreaker 的实际消费顺序逐 fragment 打账（item id / before gap / height / after gap），
再与 Word 的 page_stack 按内容对齐，而不是按页号硬拼。（第 15 节已经把这页的账定下来了。）

## 15. 页顶那条：分节符开出来的那一页不画段前距（已修），章标题那一半还没定死

真值（Word 16.0.20430.20092 COM，`artifacts/agent-layout-verify/gaps/page-top.tsv`，
sha 前 8 位 7F2B84E1）：

| 量 | Word 实测 | 当时我方计费 |
| --- | --- | --- |
| 目录页文字区顶 | 107.7 pt | - |
| "目 录"标题行框顶 | 110.05 pt | - |
| 标题上方净空间（扣掉 13 页 min=max 的自然页顶偏移 0.40px） | **2.73 px** | **24.0 px** |
| 标题下方 after（= 标题行顶到下一行顶 49.80px - 行高 30.60px） | **17.8~19.2 px** | 19.2 px |

after 是对的，**before 才是错的**。触发方式在 XML 里：`w:p[3]`（空段）带 `w:sectPr`（`w:type` 缺省
=nextPage），所以第 2 页是**分节符**开的；标题自己没有 `w:pageBreakBefore`，而 `PageBreaker` 只对
`item.pageBreakBefore` 归零段前距（那里的注释早就写了"也该管节首"，但代码一直没实现）。

**已修（本节结论已被第 16 节作废，代码已撤）**：`A4Paginator.sectionStart(batch, batches)` 给"分节符开出来的那一页的第一项"打
`PageBreaker.Item.sectionStart`，`PageBreaker` 对它不画 before。文档第一节的第一段排除——那一页不是
被分页符推上来的，段前距照旧画（Host 两条断言把这两侧都钉住）。第 2 页因此少计 24.0 px。

**没修的那一半，理由是不确定**：7 个真 `w:pageBreakBefore` 的章标题，声明 before=1 行=24.0 px，
Word 在页顶实际给的只有 3.13 px（p4/p15/p24：上一页末段 after=10 pt）或 16.73 px
（p7/p20/p22/p26：上一页末段 after=0）——**Word 从不给 24 px**，但这 7 页的差别只与"上一页末段有没有
after 空间"相关，规则没定死之前不改 `pageBreakBefore` 的现有行为（第 0 节第 3 条：不许为了让页码对上
而调参）。要补的真值：页顶上方那一段到底等于 max(上一页 after, 本段 before)、还是被上一页 after 挤掉。

同一条账上还挂着三笔，都量到了但都没动：

1. **黑体标题行高偏高**：用"下一行顶 - 声明 after"反推 Word 的黑体单行高，14pt/1.25 得 23.7~26.0px、
   15pt/1.25 得 25.5~28.8px、18pt/1.25 得 31.7~34.0px，约 1.02~1.13 em x 倍数；而我方 HEAD 是
   27.455/29.416/35.3 px，cand（拿宋体 1.31335 em 去套黑体）是 30.24/32.40/38.88 px。34 条标题段
   约多算 150 px，且这条正是"实测行高不能拿宋体的比值套所有中文脸"的证据——要单独量黑体再进表。
2. **`w:contextualSpacing` 118 次全为 true，解析器 0 处处理**（111 处在表格单元格，7 处在正文，
   其中 3 段声明 beforeLines=50 = 我方计的 12 px）。Word 的语义是同样式相邻段不加间距，也就是这 12 px
   我们多半多算了；需要真值确认哪些相邻对被抑制。
3. **`w:beforeAutospacing/w:afterAutospacing` 共 85 个 `w:spacing`（63 个 true）**，全部在表格单元格内，
   与 `before=100 after=100 line=300` 成对出现；我们照 5pt+5pt 记账，属性本身不解析。


## 13. 发版重跑记录（`parity-gate.ps1`，一条命令一行结果）

`artifacts/agent-typeset/gate/summary.tsv`，每行一个 tag：

| tag | 页数 ours/Word | 错页段 | exact | 页差直方图 | 上下标段 | 首个错页 | 右边界 1px 内 | 我们的参差 | 逐行 delta 中位/p90 | lines-all sha |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| worktree（HEAD 2.1.0 引擎） | 28/28 | 7 | 96.6% | -1=>7 0=>199 | 1/34 | 121 | 32/32 | 0.00 px | -0.767 / +0.333 | F6FC9574FC9BBC07 |
| 2.1.1（工作区，`APPLIED_TO_LAYOUT=false`） | 28/28 | 7 | 96.6% | -1=>7 0=>199 | 1/34 | 121 | 32/32 | 0.00 px | -0.767 / +0.333 | F6FC9574FC9BBC07 |

两行的 `lines-all.tsv`、`pages.tsv`、`paragraphs-wordformat.tsv` 三个 sha 与 HEAD 的 capture 完全相同
（`F6FC9574FC9BBC07` / `015F98691731BB81` / `557FC76C2468AB27`，连编译出的 dex 都同为 `2D63B4EC1E14D7DD`），
所以 2.1.1 在排版上与 2.1.0 逐页等价，这条是实测不是推断。

## 14. 下一次重开行高开关的前置条件（量的结论，不是建议写法）

把第 13 页那类边界的剩余空间并排看：Word 第 13 页剩 38.3 px（够 1 行、不够 2 行）、第 17 页 5.7 px、
第 20 页 2.1 px；我们回退版同一页剩 79.5 px。也就是说 Word 的页尾普遍"只差半行到一行半"，
而 CJK 正文行从 23/25.5 抬到 26.267 要给整篇加上约 0.9 页的高度（`repag-cand2` 对 `repag`：全篇 +1385.8 px）。
这 0.9 页必须有地方去，否则就是 28 -> 30 页。同一批实测里已经定位到的三处过算：

- 纯西文行被抬到中文行高：29 段 / 85 行，每行 +2.734 px = 232 px（Word 这些行是 23.533 px）。
- 含上下标的行整格跳行：7 行、每行 +24 px = 168 px（`ScriptGeometry.lineBox` 的整行进一，block 30/156/161）。
- 标题行被顺带抬高：18 行左右、每行 +3~3.6 px ≈ 60 px（15 pt / 18 pt 那些，block 60/76/120/168/202/327/340 等）。

这三处合计约 460 px，加上去掉 `+0.5f` 之后仍然补不平 CJK 的抬升，所以开关重开时三处必须同行，
缺一处就会像候选版那样把首个错页从 para 121 提前到 para 56。

## 16. 受控样张把段前后距的规则量死了（`tools/word-spacing-truth.ps1`，23 个用例）

为什么非得用受控样张：Word 对象模型没有 `ContextualSpacing` 属性，`ParagraphFormat.SpaceBefore` 只有磅值，COM 连问题都问不出来。所以每个用例自己写成最小 .docx（原始 OOXML），只读打开，用 `Range.Information(6)` 量每段首字符框顶，再对"同脸无声明间距"的基线（`lh_single` / `lh_150`）做差——字体自身那段被减掉，剩下的才是间距规则本身。单位：Word 出磅，px = pt x 4/3。

| Word 16.0 实测规则 | 用例 | 读数 |
| --- | --- | --- |
| 同样式相邻段，旗标在**后一段**上 ⇒ 段前与段后一起归零；跨页、在表格单元格内一样 | ctx_direct_same / ctx_after / tbl_ctx_in_cell | 逐行 gap 与 lh_single 一字不差（20.8px） |
| 旗标写在前一段不算数 | ctx_mixed | 被标那段上方 20.8（抑制），它下面那段上方 36.27（照画） |
| 样式 id 不同就不抑制，哪怕两段形状完全一样 | ctx_diff_style_both / ctx_diff_style_one | 37.13 / 36.2（= 行高 + 16px 段前） |
| 文档 / 节 / 单元格的第一段保留段前距（抑制必须有"上一段"） | ctx_style_same / sect_break_before / tbl_ctx_in_cell | 首段上方 15.4~16.3px（声明 12pt） |
| **分节符开的那一页：段前距照画** | sect_break_before | 16.33px（声明 240twips = 16px） |
| **`w:pageBreakBefore` 那一页：段前距照画**；同一段带上 `contextualSpacing` 就归零 | pagebreak_before / pagebreak_before_ctx | 16.33px / 0px |
| **挤不下而翻页的那一页：段前距一点不画** | overflow_before / overflow_before_ctx | 第 2 页首段框顶 144px = 版心顶，上方 0px |
| `w:beforeLines` 与 `w:before` 同时写：lines 赢，磅值是死字 | bl_and_twips 与 bl_only 逐字节相同 | — |
| 没有活动网格时 lines 的单位与字号、行距倍数、docDefaults 字号都无关，约 240twips | bl_only(12pt) / bl_18(18pt) / -DefaultSz 32 | 50 单位 = 8.1 / 8.1 / 7.7px |
| 有 `w:docGrid w:type="lines"` 时单位 = pitch | bl_grid（pitch 360） | 50 单位 = 11.73px ≈ 9pt |
| `beforeAutospacing/afterAutospacing=1` **不是空操作**：声明的 before/after 作废，换成 Word 自己的自动距 | tbl_plain vs tbl_autospacing_on；autospacing_body vs _off | 单元格内 32.6→44.4px；正文 27.2→39.4px；写 `=0` 与不写完全相同 |

下面三条是当时的计划；真机 `spacing1` 对账之后只落地了两条，第三条被真稿当场否掉，记在第 16.1 小节。

1. **撤销上一版的 sectionStart 抑制**——分节符那一页不但不归零，反而要画段前距；第 15 节把"目录标题上方只有 2.73px"解释成分节符抑制，是错的解释。
2. **页顶分两种**：显式翻页（`w:pageBreakBefore`）画 `max(0, before - 上一页末段 after)`；挤不下翻过去的那一页画 0。第 15 节留下的那两个数（同样式的章标题，上一页末段 after=0 的四页给 16.73px、after=10pt 的三页给 3.13px）在 `before` 单位改成 240twips（=16px）之后正好对上：16.73 ≈ 16，3.13 ≈ 16 − 13.33。
3. **`beforeLines` 的单位**只在 `w:docGrid/@w:type` 真是 lines/linesAndChars 时用 pitch，否则 240twips。本稿三个 `<w:docGrid w:linePitch="360" w:charSpace="0"/>` 都没有 `w:type`，也就是没有活动网格，于是章标题的声明段前距从 24.0px 变成 16.0px，目录标题从 24.0px 变成 16.0px。

**一条还没合上的账（不许猜，下一刀先量它）**：受控样张说"分节符那页照画段前距"，可 `tests/samples/input-liu.docx` 的"目 录"标题——分节符之后的第一段，声明 `before=391 beforeLines=100`——在 Word 里上方只有 2.35pt（第 15 节、`secfix1/page2-audit.md` 第 2 节），按本轮单位应是 16px。两处必有一处理解错了，差额 13px，而 Word 第 2 页只剩 16.11px 空量：这 13px 直接决定目录最后一条（para 58）落在第 2 页还是第 3 页。要补的真值是把"带 sectPr 的那一段 + 标题那一段"连同本稿 styles.xml 一起剥成 replica，再逐条 pPr 属性 bisect；在此之前不许再往 `PageBreaker` 里加第四条页顶规则。

第 15 节里"已修：分节符那一页不画节首段段前距"那一条自本节起作废，代码已撤；节里其余读数继续有效。

### 16.1 落地结果：`spacing1` 真机对账把第 2 条当场否掉

同一篇稿子、同一台 CDY-AN90、`engine.tsv` 与 capture 对齐，只改这几条规则：

| 指标 | 2.1.0（`head-f7f91cb`） | HEAD 4985e66（`secfix1`） | 三条全上（`spacing1`） | 只留第 1、3 条（`spacing2`） | 出厂（flag 关掉） |
| --- | --- | --- | --- | --- | --- |
| 段落页归属 | 7 错 / 96.6% | 8 错 / 96.12% | **15 错 / 92.72%** | **9 错 / 95.63%** | **7 错 / 96.6%**（199/206，`spacing3` 真机复采） |
| 错位段清单 | 121,155,208,382,397,408,409 | 58,121,155,208,382,397,408,409 | 80,121,146,147,148,155,156,164,165,166,208,397,408,409（全 -1） | 80,121,155,208,209,382,397,408,409（全 -1） | 121,155,208,382,397,408,409（全 -1，与 2.1.0 一字不差） |
| para 58（目录末条，Word 第 3 页） | 对 | 错吸到第 2 页 | **回正** | 回正 | **对**（第 3 页，delta=0） |
| 页数 | 28 / 28 | 28 / 28 | 28 / 28 | 28 / 28 | 28 / 28 |


出厂那一列不是推定，是 `spacing3` 真机复采（HEAD cbd8a92，`layout_dirty=False`，CDY-AN90，同一篇 `input-liu.docx`，报告见 `artifacts/agent-layout-verify/spacing3/report.md`）。证据比页归属更硬：真机吐出的 `lines-all.tsv`（599 行换行点）、`paragraphs-wordformat.tsv`、`pages.tsv`、上下标两份清单、`status.txt`、`parity.tsv`、`edge.tsv` 与 2.1.0 基线 `head-f7f91cb/new/` 下的同名文件**逐字节相同**（`lines-all.tsv` 两边同为 sha256 `F6FC9574…4DF03`），也就是说这一版与 2.1.0 引擎在换行、页归属、上下标、右边界四项上量不出区别。
读数：**第 1 条（撤销节首抑制）与第 3 条（beforeLines 单位 24.0→16.0px）把 para 58 送回了第 3 页**，与 Word 一致——第 2 页那点 16.11px 空量确实是被这 16px 吃掉的，第 15 节当年把这笔钱记成"超支"是记反了。
**第 2 条（页顶分两种）净亏 7 段**：挤不下的页顶不画段前距，等于每一页多吸一行，新增的 80 / 146-148 / 156 / 164-166 全是 -1，正好是这个方向。所以第 2 条整条退回，`PageBreaker` 维持"显式翻页不画段前距"的旧行为，注释里写明受控样张与真稿在这里对不上：受控样张是一串同样式、无 `w:keepNext`、无分节几何的平铺段落，真稿有 keepNext 链和分节边界，缺的就是那笔钱的去向。

教训按第 0 节第 3 条记档：**实验台上量到的规则不等于在真稿上量到的规则**。今后凡受控样张给出的规则要改进 `PageBreaker` / `A4Paginator`，必须同一次提交里带一个 `spacingN` 真机 capture；只在实验室成立的规则不许进代码。


**所以出厂状态是什么**：第 2 条（页顶分两种）整条退回；第 1 条（撤销节首抑制）留下；第 3 条（beforeLines 单位）实现完、断言钉住，但用 `A4Paginator.MEASURED_LINE_UNIT_APPLIED = false` 关掉——它单独打开是 9 错，比关掉之后应有的 7 错差两段。这不是把规则藏起来：`spacing2` 的三列数字、关掉的理由、以及打开它的前置（先把黑体标题那 ~150px 的过算定住）都写在上面这张表和 `MEASURED_LINE_UNIT_APPLIED` 的注释里，`tests/Regression.java` 有一条断言守着这个开关，翻它必须连同一次新的 `spacingN` 真机 capture 一起进来。

## 17. HEAD `f90dd78` 复采：六条真数，以及"按字库 ascent+descent 算行高"这条路被量死

### 17.1 六条真数（`pwsh tools/parity-six.ps1 -Tag lh-base2`，含真机重采）

| # | 指标 | f90dd78 实测 | 与第 0 节旧抄录的差 |
| --- | --- | --- | --- |
| 1 | 段落页归属 | 7 段错页 / 206 对齐段（exact 96.6%，页差直方图 -1=>7 0=>199，首个错页 para 121，页数 28/Word 28） | 持平 |
| 2 | 逐行行高误差中位 | -0.767 px（n=91 逐行配对，Word 26.267 px） | 持平 |
| 3 | 逐行行高误差 p90 | 2.533 px（\|误差\|，max 3.467，<=1px 的 68/91） | 比旧抄录的 3.267 好 0.734 px：本轮段-段映射 208/210 全对上，多配到 1 行 |
| 4 | 每页累计高度误差 | 0.79 行（每行 -0.767 px x Word 正文页 27 行 / 26.267 px；最差队列 sz12 line300 snap=false 2.55 行） | 旧抄录 0.80 行 / 最差 3.4 行，同一口径下的第 3 条同源差 |
| 5 | 逐行换行点一致率 | 103/165 = 62.4%（27 段 165 行） | 持平 |
| 6 | 两端对齐右边界超出 1px | 0 / 32（1 px 以内 32/32；我方参差 0.00 px，Word 1.94 px） | 守住 |

指纹：`engine.tsv` head_sha=`f90dd7845731…`（工作树编 dex 在手机上跑 app_process，手机里装的 APK 不参与）、`lines-all.tsv` sha256 前 16 位 `D9822E2C3396D6EC`（84,949B / 601 行）、`pages.tsv` sha `921A8FAB4D3D09F8`。Word 真值三个文件：`artifacts/word/pages.tsv` `6AFED202C7AAB71A`（28 页）、`artifacts/agent-typeset/word-line-pitch.tsv` `80A7344C728E2A18`、`artifacts/parity/word-line-breaks.tsv` `E7C473F6BE7064A9`（Word 16.0）；样稿 `tests/samples/input-liu.docx` sha `2AB84AAEAF2D6BB8`。

换字库不动排版这条是实测不是推断：`f90dd78` 的采样与工作树换上宋体全量副本之前的 `artifacts/agent-layout-verify/lh-base` 三份产物逐字节相同（`lines-all.tsv` / `pages.tsv` / `paragraphs-wordformat.tsv` 三个 sha 一字不差），所以宋体补的那 43 个码位不改换行点、也不改页归属。

### 17.2 "行高取本脸字体的 ascent+descent 乘行距"：量了，走不通

命令：`pwsh artifacts/agent-typeset/font-metrics.ps1 -Dir app/src/main/assets/fonts`，再用同一支脚本量桌面 Word 在本机实际用的那套原字库 `pwsh artifacts/agent-typeset/font-metrics.ps1 -Dir artifacts/agent-typeset/winfonts`。

| 脸 | upm | OS/2 win | hhea | hhea+lineGap | Word 反推的单一行高 |
| --- | --- | --- | --- | --- | --- |
| 宋体 `song.ttc`（度量与本机 `simsun.ttc` face0/face1 完全相同：220/36/256） | 256 | 1.000 em | 1.000 em | 1.1406 em | **1.3134 em**（26.267 px ÷ 1.25 ÷ 16 px） |
| Times New Roman（打包件与本机 `times.ttf` 同一套度量） | 2048 | 1.1074 em | 1.1074 em | 1.1499 em | **1.1767 em**（23.533 px） |

- 字库表里任何一个和——win 和、typo 和、hhea 和、再加 lineGap——都够不到 Word 的数：中文差 +0.173~+0.313 em（每行 2.8~5.0 px），西文差 +0.027~+0.069 em（每行 0.5~0.9 px）。
- 关键点在"字库同源"这一条已经不成立得彻底：本机 Word 渲染这篇稿子用的就是 `winfonts/simsun.ttc` 那一份，同一张表、同一台机器，仍然推不出 26.267 px。所以"手机字库换成 Windows 原字库以后就能按度量算准"这个前提是错的，`WordLineHeights` 那张实测表不是权宜之计，而是目前唯一能对上 Word 的口径。
- 因此行高一族的可行口径钉死为：**按脸取 Word 实测 em**（已量到 宋体 1.31335 em、Times 1.17665 em），没量过的脸（黑体、幼圆、隶书、华文各脸……）一律不动，等各自的真值。想扩这张表必须先有 Word 侧逐行基线，不许拿邻近字体的数凑。

### 17.3 23 px 那一族的钱是谁出的、有多少

- **23 px 不是宋体给的，是 Times 给的。** `DocxTextLayout.Spacing` 的两步取整是 `singlePx = round(比值 x 字号pt x 4/3)`、`desired = round(singlePx x w:line/240)`；开关关掉时中文段落无条件取 `max(中, 西)`，Times 的 1.1074 em 大于宋体的 1.000 em，于是 `round(round(1.1074 x 16) x 300/240) = 23`。宋体自己那张表只给 20 px（同一支脚本输出的 `line300` 列）。所以"改比值"改的其实是西文脸，先按字符把脸选对才有意义——这与第 5 节第 2 条同源，本轮把它量成了具体数字。
- 全篇 601 行按行型普查（`artifacts/agent-layout-verify/lh-base2/repag/out/repag-advances.tsv`，与六条同一次采样）：

| 行型 | 行数 | 现在每行 | Word 每行 | 每行差 | 全篇差 |
| --- | --- | --- | --- | --- | --- |
| auto + snap=true + 中文 | 268 | 25.5 | 26.267 | -0.767 | -205.6 px |
| auto + snap=false + 中文 | 140 | 23.0 | 26.267 | -3.267 | **-457.4 px** |
| auto + snap=false + 纯西文 | 68 | 23.0 | 23.533 | -0.533 | -36.2 px |
| auto + snap=false + 目录点线行 | 39 | 24.5 | 24.47 / 25.33 | ~0 | ~0 |
| 其余（exact / atLeast / 10.5pt / 标题） | 86 | 19~61 | 待逐族量 | — | — |

- 本轮开刀的目标队列就是那 140 行：42 个块，全部 12pt / `w:line=300` / 不吃文档网格，现在合计 3,220.0 px，Word 要 3,677.4 px，**补上去 +457.4 px = 0.53 个版心页**（版心 865.53 px）。下一族 snap=true 的 268 行另算 +205.6 px；两族一起 +663.0 px ≈ 0.77 页——第 13 节 cand-lh1 那次"全篇多算 868.9 px、28 页变 29 页"就是这个钱一次性全下去的结果。

## 18. `lh-family1`：把 snap=false 这一族按 Word 实测 em 抬高，行高三条过线、第 1 条退 63 段

改了什么（`WordLineHeights.MEASURED_AUTO_NO_GRID` + `DocxTextLayout.lineHeightOf`）：段落同时满足"带 `w:line` + `lineRule=auto` + `snapToGrid=false`"，且撑起这一段行高那张脸在 Word 实测表里（宋体 / Times），行高就按 Word 实测 em 走一步小数（`measuredFamilyPt`）。`snapToGrid=true` 那 268 行、目录点线行、`exact`/`atLeast`、没量过的脸（黑体等）一概不参与。选脸按字符走（这一笔里有中日韩文字或全角标点才轮到中文脸），选脸与行高出自同一次比较。

复采（`pwsh tools/parity-six.ps1 -Tag lh-family1`，真机 CDY-AN90 / android 10 / sdk 29，改完重打包 `artifacts/apk/wordlite-debug.apk` 117,681,340B 并 `adb install -r` 装过再采）：

| 第 0 节指标 | 改前（`lh-base2`） | 打开这一族（`lh-family1`） | 关掉（`lh-gateoff`） | 验收线 | 判定 |
| --- | --- | --- | --- | --- | --- |
| 1 段落页归属 | 7 段错页 / 206（exact 96.6%，全 -1，首个 para 121，28/28 页） | **70 段错页（exact 66.0%，全 +1 晚一页，首个 para 163，29/28 页）** | 7 段（与 `lh-base2` 逐字节同） | 错页 <= 2 | 退 63 段，退 +1 方向 |
| 2 逐行行高误差中位 | -0.767 px | **0.000 px**（n=91） | -0.767 px | <= 0.10 px | 过（HEAD 不过） |
| 3 逐行行高误差 p90 | 2.533 px | **0.934 px**（max 1.100） | 2.533 px | <= 0.50 px | 好 2.7 倍，仍差 0.434 px |
| 4 每页累计高度误差 | 0.79 行（最差队列 snap=false 2.55 行） | **0.00 行**（最差队列纯西文 1.03 行） | 0.79 行 | <= 0.25 行 | 过 |
| 5 逐行换行点一致率 | 103/165 = 62.4% | 103/165 = 62.4% | 62.4% | >= 90% | 不动（本轮不该动它） |
| 6 右边界超出 1px | 0 / 32 | **0 / 32** | 0 / 32 | 0 / 32 | 守住 |
| 目标队列 snap=false 中文行 | 每行 23.0px（差 -3.267） | **每行 26.2725px（差 +0.006）** | 23.0px | — | 对上 Word |
| 纯西文参考文献行 | 23.0px（差 -0.533） | 23.54px（差 +0.007） | 23.0px | — | 对上 Word |

钱的去向：这一族在全篇是 140 行中文 + 68 行纯西文 = **+493.6px = 0.57 个版心页**，就把 70 段推到晚一页、页数 28 变 29。这不是"钱太多"，而是**我们的页与 Word 的页本来就差着一笔反向的多算**：第 9 节量过 Word 的页尾只剩 2.1~5.7px（纯容量见底）到 26.5~58.2px（widow/orphan 挡下），也就是说 Word 的页是排到只剩几 px 的，我们每页多占一点点就整页后挪。第 13 节把这件事叫"假平账"，本轮把它的量级钉死了：**HEAD 的 96.6% 靠的是行高每行少算 0.767~3.267px 换来的页尾余量**。

所以这一族留在代码里、开关关掉（`MEASURED_AUTO_NO_GRID = false`，`tests/WordLineHeightRegression.java` 有一条断言盯着出厂值）。关掉之后 `lines-all.tsv` 与 `pages.tsv` 与 `lh-base2` 逐字节相同（sha `D9822E2C3396D6EC` / `921A8FAB4D3D09F8`，601 行 / 28 页），也就是说本轮新增的判定与 `lineHeightOf` 的单次扫描重构在排版上是惰性的，六条回到改前原值。

重开这一族的前置条件（按本轮实测排顺序，不再靠猜）：

1. 先把**逐页高度对平**：拿 Word 的逐段页账（`artifacts/agent-layout-verify/page-stack-all/page_stack.tsv`，395 行 / 28 页，带每段 `y_first_pt` / `y_last_pt` / `lines_on_page` / `space_before_pt` / `space_after_pt`）与我们同一页逐块的消费对账，定位是哪一类项（黑体标题行高、图、表格行、段前后距）在我们这边多算。这件事在行高对平之后做才干净——行高不再是混淆项，本轮这套开关就是量它的工具。
2. 第 15 节挂着的黑体标题行高（150px 量级）、`MEASURED_LINE_UNIT_APPLIED`（beforeLines 单位 24.0→16.0px，`spacing2` 实测单独打开是 9 错）与 `w:contextualSpacing`（本稿 118 处 true，我们按声明段距算）都要在这一步一起定，它们的方向各不相同，只有逐页对账能分清。
3. 残余的行高误差有两条已定位、都不许调参补：Word 的纯西文行距在 22.667 / 23.533 两档交替（一个常数比值追不上），Word 的上下标行比正文行还矮（25.333 vs 26.267）而 `ScriptGeometry` 只会长高。这两条要单独拿真值。

## 19. `lh-tier1` / `lh-tier2` / `lh-revert1`：把抬 em 收到"量过那一格"，页归属照样崩在第 13 页；行高改回原状

**一句话：上一轮那一族就算收到只剩量过的 140 行，页归属照样从 9 段错页涨到 68 段、页数 28 变 29；钱不是花多了，是我们别处每页多占，把 13/15/16/24/26 页的余量吃光了。所以本轮把 app/src 里的行高抬法整个改回原状——不留关掉的开关，也不留不跑的分支。**

上一轮（第 18 节）的做法是"开关关掉留在代码里"。本轮按发版规矩（不许留"关掉的开关 + 一段不跑的死代码"）改成：`WordLineHeights.java`、`DocxTextLayout.java`、`tests/WordLineHeightRegression.java` 三个文件回到 `15fd636` 的样子（`git checkout 15fd636 -- 这三个文件`）。抬哪一格、涨多少钱、崩在哪一页，全部改由量台测出来并留在这里，量台在 `artifacts/agent-typeset/`，不进包。

### 19.1 上一轮整族抬的钱到底花在哪（真机两份复采相减）

命令：`pwsh artifacts/agent-typeset/run-repaginate.ps1 -Serial <sn> -OutDir <tag>/repag`，改前（`lh-base2`）与改后（`lh-family1`）各跑一次，比 `repag/out/repag-advances.tsv` 的逐行 `advance`。

| 抬的是哪一格 | 行数 | 每行涨 | 合计 |
| --- | --- | --- | --- |
| 中文脸 + 12pt + `w:line=300` + `auto` + `snapToGrid=false` | 140 | +3.267 px | **+457.4 px** |
| Times + 12pt + `w:line=300`（参考文献） | 68 | +0.533 px | **+36.2 px** |
| 12pt + `w:line=276` | 26 | +1.327 px | +34.5 px |
| 10.5pt + `w:line=280` | 16 | +2.450 px | +39.2 px |
| 14pt + `w:line=300` | 16 | +1.450 px | +23.3 px |
| 15pt + `w:line=300` | 11 | +1.418 px | +15.6 px |
| 其余（10.5pt/300、12pt/288、10.5pt/360、15pt/324、14pt/280） | 6 | +1.7 ~ +6.2 px | +18.2 px |
| **合计** | **283** | — | **+624.4 px = 0.72 个版心页（865.53 px）** |

第 17.3 节量死的目标队列只有前两行：140 行 +457.4px。后面 5 行 75 行 / +130.8px 落在**从来没量过 Word 行距的格子**上（10.5pt、14pt、15pt、`w:line=276/280/288`），是外推。本轮把这两类分开，各测一档。

### 19.2 三档并排：抬了哪几类行 / 逐行行高 / 页归属

| 抬哪几类行 | 逐行行高误差：中位 / p90 | 段落页归属 |
| --- | --- | --- |
| 不抬（HEAD，真机 tag `lh-revert1`） | -0.767 px / 2.533 px | 7 段错页 / 206（exact 96.6%，全是 -1），28 页 |
| 1 档：中文脸 + 12pt + `w:line=300` + `auto` + `snap=false`（140 行 / +457.4 px） | -0.767 px / 1.033 px | 量台重放 9 段 → **68 段**（全 +1 晚一页，首个 Word 段 163），29 页 |
| 2 档：1 档再加 Times 12pt/`w:line=300`（再 68 行 / +36.2 px） | -0.767 px / 1.033 px | 与 1 档一字不差：**68 段**，29 页 |
| 整族（旧开关，含没量过的 75 行；283 行 / +624.4 px，真机 tag `lh-family1`） | 0.000 px / 0.934 px | 真机 7 段 → **70 段**，29 页 |

- 页归属那一列在同一台手机上跑量台重放（引擎自己的 `PageBreaker`）：`pwsh tools/word-parity.ps1 -Device <tag>/repag/out/mode-<mode>-wordformat.tsv ...`。同一次重放里 `current` 是 9 段错页（真机整采是 7 段，重放与整采差 2 段，比前后一律用重放内的数）。
- 1/2 档的行高两列是把这两档代入 `lh-revert1` 的逐行账单（91 行 joined）算出来的，不是再上一次手机；这 91 行样本里落到 1 档的是 12 行、落到 2 档的是 17 行。
- **一条读数陷阱，写死在这里**：整族那次中位变成 0.000 px、第 4 条变成 0.00 行，都不是"行高排对了"。同样这 91 行里有 70 行是 `snapToGrid=true`，改前改后都是 -0.767 px，这一族从没动过它们；中位数只是恰好掉进 10 行并列 0 的那一段，而第 4 条本来就是中位乘每页行数。真的那条是 p90：2.533 px → 0.934 px，最差那批行确实被抬对了。

### 19.3 一抬就崩页的是哪一页、哪几段（本轮新量的）

命令：量台本轮多写一份逐页账单 `repag/out/repag-pagefill.tsv`（`mode page block start_line end_line height top before after hang used capacity`，9 档每档一份）；把每一档涨的钱按当前分页摊到页上，与那一页的余量比。

| 页 | 已用 px | 版心 px | 余量 px | 这一档要吃的钱 | 落点 |
| --- | --- | --- | --- | --- | --- |
| 13 | 854.5 | 865.5 | **11.0** | **45.7** | 段 113 八行 +26.1、段 114 三行 +9.8、段 115 三行 +9.8 → 段尾被推走 |
| 15 | 824.0 | 865.5 | 41.5 | 65.3 | 段 122 / 124 / 125 / 126 / 128 |
| 16 | 826.0 | 865.5 | 39.5 | 81.7 | 段 132 / 133 / 135 / 137 / 139 |
| 24 | 851.5 | 865.5 | 14.0 | 107.8 | 段 329~339（参考文献那一页） |
| 26 | 863.6 | 865.5 | **2.0** | 32.7 | 段 345 / 346 / 351 / 352 / 353 |

- 第一个崩的是**第 13 页**：余量 11.0 px，这一页要吃的钱 45.7 px，于是段尾那块被推到 14 页——设备段 115 / **Word 段 122**「(2)多孔Cu辅助TLP反应区具有三维非均匀特征…」。后面所有段跟着 +1 页，所以 68 段错页、29 页。
- 这一页正是 HEAD 已有的老毛病：HEAD 首个错页段是 Word 段 121（= 设备段 114），也在第 13 页，方向相反（-1，我们比 Word 早）。第 13 页上同时挂着"我们早一页"和"一抬就晚一页"，说明这一页的账本来就错着。
- 全篇 28 页里有 9 页余量 ≤ 14.5 px（第 2 页 6.3、4 页 14.0、10 页 11.8、13 页 11.0、17 页 11.5、22 页 27.2、24 页 14.0、26 页 2.0、27 页 14.5）。在这种页面上，行高哪怕只差 6.5 px/段也会翻一页。

### 19.4 本轮验证：改回原状是惰性的

`pwsh tools/parity-six.ps1 -Serial <sn> -Tag lh-revert1`（真机重采）：

```
1 段落页归属      7 段错页 / 206（exact 96.6%，-1=>7  0=>199，首个 para 121）
2 逐行行高误差中位  -0.767 px（n=91，Word 26.267 px）
3 逐行行高误差 p90  2.533 px（max 3.467）
4 每页累计高度误差  0.79 行（最差队列 sz12 line300 snap=false 2.55 行）
5 逐行换行点一致率  103/165 = 62.4%
6 右边界超出 1px   0 / 32
lines-all sha=D9822E2C3396D6EC  pages.tsv sha=921A8FAB4D3D09F8  字体指纹=E751F0AFAC19
```

与 `lh-base2` 逐字节相同（`lines-all.tsv`、`pages.tsv` 的 sha 一字不差），六条一个数都没动。

### 19.5 下一步的顺序（按本轮实测排，不是写法建议）

1. 先用 `repag-pagefill.tsv` 与 Word 的逐段页账（`artifacts/agent-layout-verify/page-stack-all/page_stack.tsv`）对第 13 / 15 / 16 / 24 / 26 页：把我们每页多占的那一项定出来（候选：黑体标题行高、表格行高、图高、段前后距、`snapToGrid=true` 那 268 行的 25.5px 经验值），拿到段号与 px。
2. 那一项定出来并单独复采过，再开这一族：一次一档（1 档 → 2 档），每档跑 `tools/parity-six.ps1`，行高三条与页归属一起看。
3. 不许为了让第 2/3/4 条好看而把 em 调小或只抬一部分行——第 1 条会替你记住这件事。


## 20. `autospace1`：`w:autoSpaceDE/DN` 缺省按 Word 当开（第 5 条 62.4% → 69.1%）；顺带把逐页账本做成工具，并否掉"每页多收 40~60px"这个假设

### 20.1 这一刀改了什么，凭什么

稿件自查（`Expand-Archive tests/samples/input-liu.docx` 后数 `word/document.xml`）：373 段里只有
**105 段**写了 `<w:autoSpaceDE/>` + `<w:autoSpaceDN/>`，显式关掉的 **0 段**，
`word/styles.xml` 的 docDefaults 与 `word/settings.xml` 里一次都没出现 `autoSpace`。
OOXML 与桌面 Word 的缺省是**开**，旧解析器把"没写"读成"关"，于是另外 268 段的中文与西文/数字之间
少了 1/4 em 的自动空隙——`tools/line-break-delta.ps1` 把它单列成一档"中西文混排留白未计入"
（13 行，4 个首分歧）。

代码只动一处：`DocxDocument.ParagraphFormat` 的 `autoSpaceDe` / `autoSpaceDn` 字段默认值改成 `true`，
`autoSpaceDeSet` / `autoSpaceDnSet` 仍然只记录文件真正写过什么，`DocxWriter` 的回写口径一字未改。
断言：`tests/Regression.java` 两条（沉默=开、显式 `w:val="0"` 仍然关，Regression 72 → 74 条）、
`tests/OriginalDocxRegression.java` 一条（真稿 105 声明 + 268 沉默 + 0 关掉，19 条全过）、
Robolectric `tests/ui/.../AutoSpaceTest.java` 拆成"显式关掉"与"沉默走缺省"两条。

### 20.2 六条前后

命令：`pwsh tools/parity-six.ps1 -Serial EAMUT20528011355 -Tag autospace1`
（engine head_sha `bdd2e6a`，`lines-all.tsv` sha `D9822E2C3396D6EC` → `AC44D355853F4616`，
`pages.tsv` sha `921A8FAB4D3D09F8` **未变**，字体表指纹 `E751F0AFAC19`）

| # | 指标 | 改前（tag `lh-revert1`） | 改后（tag `autospace1`） | 结论 |
| --- | --- | --- | --- | --- |
| 1 | 段落页归属 | 7 段错页 / 206（exact 96.6%，全 -1，首个 para 121；28/28 页） | **7 段错页 / 206**（同一分布，首个仍是 para 121） | 不动 |
| 2 | 逐行行高误差中位 | -0.767 px（n=91） | -0.767 px | 不动 |
| 3 | 逐行行高误差 p90 | 2.533 px | 2.533 px | 不动 |
| 4 | 每页累计高度误差 | 0.79 行 | 0.79 行 | 不动 |
| 5 | 逐行换行点一致率 | 103/165 = 62.4% | **114/165 = 69.1%** | 涨 6.7 分（多对 11 行） |
| 6 | 右边界超出 1px 的行数 | 0 / 32 | **0 / 32** | 守住 |

没有退步项，所以 CHANGELOG 只记涨的那条。

### 20.3 逐页账本做成工具（`tools/page-fill-ledger.ps1` + `tools/pdf-line-truth.py`）

Word 的 COM 只报"每段首行"的位置，续排页的页顶和表格内部的行问不到，所以改拿 Word 自己导出的 PDF
（`artifacts/agent-typeset/word-export-pdf.ps1`，28 页，sha256 前缀 `ab298ac416dcfc72`）。
每页三条数：`A` 首行基线到页顶、`B` 首末基线之差、`C` 末行基线到页底，三条相加必然等于页高
（自检 `max|A+B+C-pageH| = 0.00 px`，`max|dA+dB+dC| = 0.10 px`）。手机侧配套给探针
`artifacts/device/probe/DeviceCapture.java` 加了 `lines-geo.tsv`（每行的盒顶/基线/盒底，
**含表格单元格行**，共 766 行 = 610 段行 + 156 表格行）与 `page-geo.tsv`；加 dump 后的第一次采样
`lines-all.tsv` / `pages.tsv` 的 sha 与 `lh-revert1` 一字不差，证明这份 dump 是惰性的。

HEAD（`pwsh tools/page-fill-ledger.ps1 -Tag autospace1`，单位 px）：

| 项 | Word | 我们 | 差 |
| --- | --- | --- | --- |
| 正文行数（含表格行） | 749 | 766 | **+17 行** |
| 每行基线间距（页均） | 26.78 | 25.61 | **-1.17 px**（全篇 -844 px） |
| 首行基线到页顶 A（页均） | 163.6 | 166.0 | +2.4 |
| 末行基线到页底 C（中位） | 155.0 | 162.4 | +9.4 |
| 末行基线到页底 C（min） | 120.5 | 119.4 | -1.1 |

**否决一条假设**："我们在行高之外每页多收 40~60px"不存在——每页差的中位只有 9.4px、均值 12.1px，
是噪声量级。页尾之所以看起来"卡"，是因为 Word 第 13 页页尾本来就有半行溢出 + 悬挂标点，那是 Word
自己的例外机制，我们不抄它，所以那一页不该朝"把余量填平"的方向修；朝这个方向多花的钱都是亏的。
行高差的 -844px 现在就躺在页尾余量里，单独把它补回来必然挤出整页（第 19 节实测 68 段错页），
所以它必须等到断行收敛之后再动。

### 20.4 主攻方向按实测排下来

1. **每行装几个字、断点落在第几行**（第 5 条）：+17 行 ≈ +435px，比行高差里属于换行的那部分更直接。
   `autospace1` 吃掉 6.7 分之后剩 69.1%，剩下的分档见 `artifacts/agent-layout-verify/autospace1/parity/line-delta.md`，
   大头仍是西文 advance（21 行 / 33.9%）与全角余量（14 行）。
2. 表格：表题到表格首行我们比 Word 多 8.6pt、段到表题多 6.8pt（第 23 页逐行对读：Word 119.8 / 137.9 /
   158.4 pt，我们 122 / 146.9 / 176 pt；行距本身两边一致，Word 22.5pt vs 我们 22.65pt）。算噪声，本轮不动。
3. 行高与页归属：等第 1 条把每页行数对齐 Word 之后再一并算，验收仍是这六条。


### 20.5 断行的靶子（改一次宽度模型就拿它复量，别猜）

`artifacts/agent-layout-verify/autospace1/parity/line-delta.tsv` 的 51 条分歧行，按档把"Word 多塞几个字 /
我们这行还剩几像素 / 再塞一字要几像素 / 行尾字号"并排列出来（`Import-Csv ... -Delimiter "`t"` 后按
`cause` 分组取中位，字段 `extra_chars`、`device_slack_px`、`extra_est_px`、`word_end_font_pt`）：

| 成因 | 分歧行 | Word 多塞（字，中位） | 我方行尾余量 px | 再塞一字要 px | 行尾字号 pt |
| --- | --- | --- | --- | --- | --- |
| 西文与数字字符宽度量差（Times New Roman advance） | 21 | 2 | **-0.1** | 32 | 12 |
| 全角字宽与行尾余量取整 | 13 | 2 | +6.9 | 32 | 12 |
| 上下标小字号 run 参与行宽计量 | 6 | 4 | -0.1 | 32 | 12 |
| 中西文混排留白（`autoSpaceDE/DN`，本轮后残余） | 4 | 3 | -0.1 | 48 | 12 |
| 长西文或数字串不可断（`w:wordWrap`） | 3 | 5 | -0.1 | 40 | 12 |
| 首行缩进计量（`firstLineChars`） | 2 | 1 | +25.9 | 16 | 12 |
| 行尾标点悬挂与行首禁则（`overflowPunct` + `kinsoku`） | 2 | 1 | -0.1 | 8 | 12 |

读法：`-0.1 px` 表示我们这行已经塞到贴边，而 Word 在同一行还塞得下中位 2 个字（≈32 px）。
所以最大那一档不是"我们算漏了什么规则"，就是我们把西文与数字的 advance 算宽了——每行大约宽 32 px。
第二档 +6.9 px 才是取整余量那一类。宽度模型每改一次，跑
`pwsh tools/parity-six.ps1 -Tag <tag>` 之后拿这张表对：第 5 条要涨，第 6 条必须还是 0/32。



## 21. U+207B（W·m⁻¹·K⁻¹ 的上标减号）：两边用的是同一张脸、同一个 advance，回退链不改

线索是用户给的：稿子里 `cm⁻¹ / W·m⁻²` 那一类上标用的是 U+207B，随包的 `song.ttc` 与本机
`simsun.ttc` 都没有这个码位（各 28849 个码位，逐字对过），所以怀疑"Word 换了脸、我们没换，
于是拿宋体的 advance 去量一个只有替换字库才有的字形"，并把这一条挂到第 1 节"含上下标"那一队列
（n=32，行高中位 -0.767px）。三条分开量完，**这条线索不成立**，但量出另一件要盯的事（第 21.4 小节）。

### 21.1 Word 侧：这个字符 Word 用哪张脸、收多宽

不用重开 Word 会话，拿已经导出的 PDF（`artifacts/agent-typeset/pdf-truth/input-liu.pdf`，28 页，
sha256 前缀 `ab298ac416dcfc72`）逐字查：`py tools/font-advance-audit.py`（报告 C）。

- U+207B 全篇只出现 **2 次，都在第 19 页**（`w:p` 第 166 段"…的热导率分别为112.3和102.6 W·m⁻¹·K⁻¹…"）。
- Word 用的字库是 **`TimesNewRomanPSMT`，12pt，advance 4.056 pt = 5.408 px**；同一段落里
  `¹` 3.6 pt、`m` 9.336 pt、`K` 8.664 pt。也就是说 Word 把这个字符按**西文**处理，取的是这个 run
  自己声明的 `w:hAnsi`（这份稿子该 run 写的是 `ascii/hAnsi=Times New Roman`、`eastAsia=宋体`），
  按整号 12pt 画它自带的上标字形，**不缩放**。
- 随包的 `times-new-roman.ttf` 里 U+207B 有字形，`hmtx` = 693/2048 em = **0.3384 em**，
  12pt 下 4.061 pt = 5.414 px；Word 的 0.338 em 与它差 0.0004 em（PDF 坐标的舍入位）。
  量它的命令：`py "$env:TEMP\wl-edit\cmap.py" app/src/main/assets/fonts/times-new-roman.ttf`
  与 `py tools/font-advance-audit.py` 报告 C：`U+207B ours[Times]=0.3384  Word(Times) mode=0.338`。

### 21.2 手机侧：我们给这个字符挑了哪张脸、真机收几像素

探针（不装 APK、不进版本库，和 `DeviceCapture.java` 同样跑法）：
`artifacts/device/probe/FontAdvanceProbe.java`，
`javac -classpath tools/android-35.jar` → `d8` → `adb push` →
`CLASSPATH=/data/local/tmp/wlcapture/fap.dex app_process -Xmx256m / FontAdvanceProbe <assets.zip> <out>`，
设备 `EAMUT20528011355`。读数（12pt = 16.0 px 文档像素）：

| 问的东西 | 读数 |
| --- | --- |
| `FontManager.symbolFamily(声明=Times New Roman, U+207B)` | **Times New Roman**（没有改脸） |
| `FontManager.symbolFamily(声明=宋体, U+207B)` | STIX Two Math（这才是会被换脸的那条路） |
| `DocxTextLayout.resolve("Times New Roman") == FontManager.load(fonts/times-new-roman.ttf)` | true，随包字库确实挂上去了 |
| 真机 `measureText` U+207B：Times 脸 / 宋体脸 / STIX 脸 | **5.000 px** / 6.000 px / 9.000 px |
| 同一批：`¹` 4.797→5.000、`·` 5.328→5.000、`℃` 16.383→16.000、`中` 16.000 | 见 21.4 |
| `FontScriptMetrics.unicodeScript(U+207B)` | 0 → 这一格不参与上下标缩放，和 Word 一样按整号收 |

结论：**手机用的脸和 Word 是同一张（随包 Times New Roman），advance 的精确值也一样**，
"Android 静默落到系统 Noto、按宋体量宽度"没有发生——`MeasuredFontSpan` 把 `Typeface` 明确设成
随包那张脸，而这个码位在那张脸里有字形，走不到系统回退。
所以 21.1 里那 2 处 U+207B 两边各收 5.4 px（真机取整后 5.0 px），差不到 0.5 px，撑不起一个字位的换行差。

`FontScriptMetrics` / `FontManager` 的回退链这一轮一字未动。

### 21.3 全篇逐字对账（不只看 U+207B）

命令：`py tools/font-advance-audit.py`（新加的量台，报告 A/B/C 一把跑完）

- 23,263 个字符里，**11 个**会被挪出声明的那张脸：全是 `ＭＳ 明朝` 那一段的汉字（随包 `msgothic.ttf`
  没那些码位），Word 用的是宋体/黑体。U+207B、`¹`、`·`、`℃` **一个都不在**里面。
- 两边同脸、又能对上的 (字符, 脸) 组合 **1102 组**，`|我们 - Word| > 0.004 em` 的 278 组，逐条看只有两类：
  1. 中文整宽字 1.000 vs Word 1.009 / 1.019 —— 那是**两端对齐把字距拉开**，不是字库差。同一字符的最小值
     就是 1.000：`的` 的分布 1.000×161、1.009×63、1.019×31（`py "$env:TEMP\wl-edit\emdist.py"`）。
  2. 西文成对字距：`I` 我们 0.333 vs Word 0.320（94 例）、`F` 0.556 vs 0.550、`V` 0.722 vs 0.727。
     这是 Word 用了字距对（kerning），我们逐字相加。**没量到任何一个替换字库造成的宽度差。**

### 21.4 顺带量到的一件事：这台手机把每个字的 advance 就近取整到整像素

同一次探针跑出来的（`ROUNDING` 那两行）：

| 量的串 | hmtx 精确值 | 真机读到 |
| --- | --- | --- |
| `m` ×1 | 12.445 px | 12.000 |
| `m` ×20 | 248.90 px | **240.000**（= 20 × 12，逐字取整后再相加） |
| U+207B ×1 / ×20 | 5.414 / 108.28 px | 5.000 / **100.000** |
| `W` ×20 | 301.9 px | 300.000 |
| `中` ×20（宋体） | 320 px | 320.000（整宽，取整不动它） |

`subpixel`、`antiAlias` 四种开关组合都是整数（只有 `mw` 从 22 变 24，那是字距对/字形的差别）。
中文整宽所以不受影响，西文窄字受影响最大：`i`/`l` 0.278 em → 4.45 px 收 4、`r`/`t`/`I` 0.333 em →
5.33 px 收 5，单字最多差 0.45 px（≈10%）。

**这条先别拿去调宽度模型**：还不知道是 `app_process` 探针环境如此，还是装在手机上的 Word Lite 真在
屏幕上也是这样。动手的人在编辑器里用同样的 `TextPaint` 量一次 `"m"*20` 就知道；在确认之前，
第 20.5 节里"西文那一档每行宽 32px"不要直接归到 advance 表上。

### 21.5 第 19 页那一段逐行对照：分歧确实不在上标那一行

Word（PDF，页 19）与手机（`autospace1/new/lines-all.tsv`）并排，第 166 段：

| 行 | Word | 手机 |
| --- | --- | --- |
| 1 | 计算互连层热导率，再用四探针测量电阻率。公开大面积TLP 接头在室温和**200 ℃** | ，计算互连层热导率，再用四探针测量电阻率。公开大面积TLP接头在室温和 |
| 2 | 的热导率分别为112.3 和102.6 W·m⁻¹·K⁻¹，可用于检查测试量级和 | **200℃**的热导率分别为112.3和102.6W·m⁻¹·K⁻¹，可用于检查测试量级和温度修正 |

含 U+207B 的那一行两边都是两端对齐、都吃满版心（Word `x1-x0` = 425.48 pt = **567.31 px**，
手机 **567.0 px**），所以那一行量不出宽度差；真正的分歧在第 1 行末尾——Word 把 `200 ℃` 留下了，
我们把它挤到下一行。它属于第 20.5 节的"西文与数字 advance"那一档，不属于上下标。

### 21.6 这一轮改了什么、测了什么

- 版本库里只多一个量台：`tools/font-advance-audit.py`。`app/src` 一行没动，
  所以六条不需要复采，也不会退步：HEAD 的六个数仍是第 20 节 `autospace1` 那次真机采的
  （7 段错页 / -0.767 / 2.533 / 0.79 行 / 69.1% / 0/32）。
- 主机测试按 HEAD 的源码全绿：`Regression 74 / OriginalDocx 19 / WordLineHeight 63 / Script 65 /
  TableGeometry 31 / Preservation 11 / TextCorpus 209`，`javac` 82 个源文件通过。
- **挡住发版的一件事不在排版这一路**：工作树 `app/src/main/java/com/rikkahub/wordlite/ApiWorkflow.java:327`
  的 Java 字符串里有没转义的引号（`"检索设置里"联网检索"是关着的…"`），`javac` 报 4 个错，
  `tools/build-host.ps1` 与 `tools/test-host.ps1` 现在都会挂在那儿。那是别人在飞的改动，我没有动它，
  所以我这一轮的测试是从 `git archive HEAD` 的源码单独编一遍跑的。



## 22. 发版前复采（tag `head898`，engine head_sha `594c1a6`）：别人那三笔（含字库装载）没动到一个换行点

为什么要重采：`921b0ae` 改了字库装载（一次装载失败不再永久退成宋体、APK 里字库不压缩存放），
`3a5d247`、`594c1a6` 是界面。字库装载在排版路径上，所以不能拿 `autospace1` 的旧读数交差。

命令：`pwsh tools/parity-six.ps1 -Serial EAMUT20528011355 -Tag head898`（设备 `EAMUT20528011355`，
CDY-AN90 / Android 10）

| # | 指标 | `autospace1`（第 20 节） | `head898`（本轮） |
| --- | --- | --- | --- |
| 1 | 段落页归属 | 7 段错页 / 206（exact 96.6%，页差 -1×7，首个 para 121；28/28 页） | **一样**：7 段错页 / 206，直方图 `-1=>7 0=>199`，首个仍是 para 121 |
| 2 | 逐行行高中位 | -0.767 px（n=91） | -0.767 px |
| 3 | 逐行行高 p90 | 2.533 px（max 3.467） | 2.533 px |
| 4 | 每页累计高度 | 0.79 行 | 0.79 行（最差队列 sz12 line300 snap=false 2.55 行） |
| 5 | 逐行换行点 | 114/165 = 69.1% | 114/165 = 69.1% |
| 6 | 右边界超出 1px | 0 / 32 | **0 / 32（守住）** |

指纹也对得上，所以"一样"不是四舍五入出来的：`lines-all.tsv` sha `AC44D355853F4616`、
`pages.tsv` sha `921A8FAB4D3D09F8`、字体表指纹 `E751F0AFAC19`——三个与 `autospace1` 一字不差，
即那三笔改动没动到任何一个换行点、任何一次分页。

### 22.1 每页余量的分布（两边同口径，末行基线到页底）

命令：`pwsh tools/page-fill-ledger.ps1 -Tag head898`（单位 px，页高 1122.53；自检
`max|A+B+C-页高| = 0.00 px`、`max|dA+dB+dC| = 0.10 px`）

| 每页余量 C | Word | 我们 |
| --- | --- | --- |
| 最小 | 120.5 | 119.4 |
| p25 | 137.9 | 133.9 |
| 中位 | 155.0 | 162.4 |
| 最大 | 859.0 | 936.9 |
| 页差 dC | 中位 +9.4，均值 +12.1 |  |
| 每页行数 | 均值 26.8（min 5 / 中位 29 / max 65） | 均值 27.4（min 2 / 中位 30 / max 66） |

我们比 Word 多排 17 行（749 → 766），多排的 13 页、少排的 6 页；第 5 条要涨的分就在这 17 行里。
两边都没有"页尾只剩两三像素"那种硬撑的页（余量最小 119.4 px ≈ 4.6 行），所以之前担心的
"靠余量硬撑"这一条在数据上不成立，方向仍是每行装几个字。

### 22.2 这一版能不能发：主机侧与真机侧都过在哪

- 主机：`git archive HEAD` 的源码 `javac` 83 个文件通过；七套断言全过
  （`Regression 74 / OriginalDocx 19 / WordLineHeight 63 / Script 65 / TableGeometry 31 /
  Preservation 11 / TextCorpus 209`）。工作树（含别人未提交的改动）`javac` 也通过。
- 真机：六条见上表，第 6 条守住 0/32。
- 相对 2.3.0 的净变化只有第 5 条 `62.4% → 69.1%`（`w:autoSpaceDE/DN` 缺省按 Word 当开，第 20 节），
  其余五条与页归属分布未动，行高没有单独去补（原因见第 19、20.3 节）。

## 23. 线性度量（`setLinearText(true)`）：缺陷在真机应用进程里量死了，但单独上线六条退两条，本轮整体退回原状

### 23.1 先把 21.4 那个问号关掉：整像素取整不是量具造成的

量台：`tools/build-metrics-probe.ps1` 打一个独立包名 `com.rikkahub.wordlite.metrics` 的探针 APK，用 `am start`
起一个真正的 zygote 应用进程（真 AssetManager、真随包字库、真屏幕密度），在里面跑
`tools/device-probe/MetricsActivity.java`。它量的字符串和 `app_process` 那批探针一字不差，只为回答一件事：
取整是量具的现象，还是装机应用的现象。

命令：`pwsh tools/build-metrics-probe.ps1 -Serial EAMUT20528011355`（CDY-AN90 / Android 10 / API 29；
读数留在 `artifacts/agent-layout-verify/metrics-probe-20261009.txt`）

| 同一个串（随包 `fonts/times-new-roman.ttf`，12pt = 16.0 px 文档像素） | `app_process` 探针 | 真应用进程 |
| --- | --- | --- |
| `m` | 12.000 | 12.000 |
| `m` x20 | 240.000 | 240.000 |
| `WWWW` | 60.000 | 60.000 |
| U+207B | 5.000 | 5.000 |
| `中`（宋体） | 16.000 | 16.000 |
| `Paint.getHinting()` | 1 | 1 |

两边完全一致，所以**装在手机上、画在屏幕上的 Word Lite 断行时用的也是整像素 advance**——21.4 节留的
"会不会只是探针环境如此"这个问号关掉：不是探针的锅。20.5 节归到"西文与数字 advance"那一档的 21 行
按真缺陷查，但按 23.4 的顺序查，不能只把精度补上。

### 23.2 修法是知道的：一把度量 paint、一个开关

同一次读数，40 个 `m`。hmtx 精确和先从随包字库里自己算一遍：

```text
py -c "from fontTools.ttLib import TTFont; f=TTFont('app/src/main/assets/fonts/times-new-roman.ttf'); print(f['hmtx']['m'], f['head'].unitsPerEm)"
→ (1593, 2048)，即 1593/2048 em = 0.77783 em = 12.4453125 px，x40 = 497.8125 px
```

Word 按这张脸的 hmtx 逐字相加收的就是 497.81 px。

| 度量用的 paint | `measureText` | `StaticLayout.getLineWidth(0)`（断行真正用的数） |
| --- | --- | --- |
| 默认（anti+subpixel，hinting=1） | 12.000 / 字，240.000 / 20 字 | 480.000 |
| 再 `setHinting(HINTING_OFF)` | 12.000 / 字，240.000 / 20 字 | **480.000（关掉 hinting 不动它）** |
| 再 `setLinearText(true)` | 13.000 / 字，249.000 / 20 字 | **497.813 = hmtx 精确和** |

两点：`hinting` 不是那个开关；`setLinearText(true)` 之后断行用的行宽正好等于 Word 按这张脸 hmtx 逐字相加的
数（Word 侧 40 个 `m` = 497.81 px，与上面 497.8125 对得上）。改法一共 19 行：在 `DocxTextLayout.measure()`
那把度量 `TextPaint` 上调一个新加的 `applyMeasuringFlags(paint)`，里面做
`setFlags(getFlags() | Paint.LINEAR_TEXT_FLAG)` + `setLinearText(true)`；layout 复制这把 paint 的几处
（span 度量、两端对齐拉伸）会跟着一起走。

### 23.3 单独上线的实测：六条退两条（tag `linearmeas1`，engine head_sha `168fd1e`，layout_dirty=True）

命令：`pwsh tools/parity-six.ps1 -Serial EAMUT20528011355 -Tag linearmeas1`。对比基准是"把那 19 行退回原状"
之后再真机采一次的 tag `revert-linear`：同一台手机、同一份工作树源码，差别只有那 19 行。

| # | 指标 | 原状 `revert-linear` | 加线性度量 `linearmeas1` |
| --- | --- | --- | --- |
| 1 | 段落页归属 | 7 段错页 / 206（exact 96.6%，首个 para 121，28/28 页） | 一样，没退 |
| 2 | 逐行行高中位 | -0.767 px（n=91） | 一样 |
| 3 | 逐行行高 p90 | 2.533 px | 一样 |
| 4 | 每页累计高度 | 0.79 行 | 一样 |
| 5 | 逐行换行点 | 114/165 = 69.1% | **107/165 = 64.8%（少 7 行对上，退 4.3 分）** |
| 6 | 右边界不在版心 +/-1 px 的行数 | 0 / 32 | **1 / 33（退，样本也从 32 行变 33 行）** |

指纹能对上才敢叫"一样"：`linearmeas1` 的 `lines-all.tsv` sha `4A0C912BD6AF324B`、`pages.tsv` sha
`428FA8CA1BC23506`；原状那两次（`head898` 与 `revert-linear`）都是 `AC44D355853F4616` /
`921A8FAB4D3D09F8`，两个 sha 一字不差。

第 6 条那一行退在"短"而不是"超"：`artifacts/agent-layout-verify/linearmeas1/edge.tsv` 里页 18 blk153
那一行 `our_right = 561.90`，比版心 566.93 短 5.03 px（Word 自己那一行 564.53，也短 2.40 px）。
两个 tag 里都没有任何一行越过版心右界：`our_right` 最大 567.00，版心 566.9333，仍在 +/-1 px 内。
样本从 32 变 33 是因为多出来的那一行我们的断点和 Word 对上了，才被算进两端对齐那一族。
"线性度量之后有一行两端对齐没拉满"这件事本身要单独查（23.5 第 3 条）。

分歧行按成因分档（`<tag>/parity/line-delta.tsv` 的 `cause` 列，两次样本都是 165 行）：

| 成因 | 原状 | 加线性度量 |
| --- | --- | --- |
| 西文与数字字符宽度量差 | 21 | **26** |
| 全角字宽与行尾余量取整 | 13 | 14 |
| 上下标小字号 run 参与行宽计量 | 6 | 8 |
| 长西文或数字串不可断（`w:wordWrap`） | 3 | 4 |
| 中西文混排留白未计入（`autoSpaceDE/DN`） | 4 | **2** |
| 首行缩进计量（`firstLineChars`） | 2 | 2 |
| 行尾标点悬挂与行首标点禁则 | 2 | 2 |

每页余量（末行基线到页底，`pwsh tools/page-fill-ledger.ps1 -Tag <tag>`，单位 px）：

| 每页余量 C | Word | 原状 `revert-linear` | 加线性度量 `linearmeas1` |
| --- | --- | --- | --- |
| 最小 | 120.5 | 119.4 | 119.4 |
| p25 | 137.9 | 133.9 | 133.9 |
| 中位 | 155.0 | 162.4 | **156.9** |
| 最大 | 859.0 | 936.9 | 936.9 |
| 页差 dC 中位 | - | +9.4 | **+7.2** |
| 全篇行数 | 749 | 766（多 17 行） | 767（多 18 行） |

### 23.4 为什么会退：整像素取整此刻正好在抵消另一处宽度多收

我们全篇比 Word 多排 17 行（749 → 766），也就是每行装的字比 Word 少：同一行文本我们把行"量宽了"，
于是提前换行。整像素取整平均每个西文字符削掉 0.4 px 上下（`m` 12.445 → 12.000），方向正好相反，
替我们把多收的那一截顶掉了一部分。只把取整换成精确 advance 是只加不减：那一档从 21 行变 26 行，
全篇多一行，第 5 条掉 4.3 分，第 6 条也多出一行没拉满。

同一轮里每页余量倒是更贴 Word（中位 162.4 → 156.9，Word 155.0；页差 dC 中位 +9.4 → +7.2），
说明方向不错，但先亏掉的是第 5、6 条。结论写死：

**线性度量和"西文行的宽度多收"必须放在同一轮改。** 顺序是先盯第 5、6 条，把每行宽度里多收的那部分
减到 Word 那一侧（要查的三处：第 20 节那条 1/4 em 自动空隙的口径、全角字宽、`A4Paginator` 里那个
`+= 0.5f` 网格经验值，现在是 `A4Paginator.java:230`），再打开线性度量。量台和改法都已经在版本库里，
下一版按这个顺序动。

### 23.5 留给下一轮的四条

1. 量亚像素的 advance 必须在装机的应用进程里量。这次 `app_process` 与真进程读数一致，但"一致"是量出来的，
   不是推定出来的；入口 `pwsh tools/build-metrics-probe.ps1 -Serial <sn>`。
2. `hinting` 不是整数化的原因，别再试 `HINTING_OFF`（23.2 那张表的第二行就是这个实验）。
3. 线性度量下有一行两端对齐只到 561.90（比版心短 5.03 px）：查 `StaticLayout` 在 `LINEAR_TEXT_FLAG` 下
   对带尾随空格/末字那一行的拉伸。
4. `measureText` 在线性度量下仍把整个串收成整数（20 个 `m` 给 249.000 而不是 248.906），
   只有 `StaticLayout.getLineWidth` 是精确的 497.813。凡是拿 `measureText` 判"放不放得下"的地方，
   换成线性度量后都要改成按行宽度量。

### 23.6 本轮工作树与提交

- 引擎那 19 行整体退回 HEAD（`git checkout -- app/src/main/java/com/rikkahub/wordlite/DocxTextLayout.java`），
  `git diff -- app/src` 为空，不留"关掉的开关加一段不跑的死代码"这种半状态。
- 配套断言暂存在 `artifacts/parked-LinearMetricsTest.java.txt`（`artifacts/` 在 `.gitignore` 内，
  版本库里不留死代码）。断言内容是"度量那把 paint 必须带 `LINEAR_TEXT_FLAG`"，等 23.4 那一轮一起回来。
- 真机复采确认退回干净：`pwsh tools/parity-six.ps1 -Serial EAMUT20528011355 -Tag revert-linear` →
  7 段错页 / -0.767 px / 2.533 px / 0.79 行 / 114/165 = 69.1% / 0/32，`layout_dirty=False`，
  `lines-all.tsv` 与 `pages.tsv` 两个 sha 与 `head898` 一字不差。
- 主机侧：`git archive HEAD` 的源码 `javac` 83 个文件通过，七组断言全绿（`Regression 74 /`
  `OriginalDocx 19 / WordLineHeight 63 / Script 65 / TableGeometry 31 / Preservation 11 / TextCorpus 209`）。
- 这一轮进版本库的只有量台三件（`tools/build-metrics-probe.ps1`、`tools/device-probe/MetricsActivity.java`、
  `tools/device-probe/MetricsManifest.xml`）加本节文档；排版引擎一字未动。


## 24. `breakfix3`：中西文缝隙的 span 挪回汉字那一侧，字母串内部断行 10 行 -> 1 行（第 6 条退 1 行）

用户在真机（2.3.1，build 46）第 8 页肉眼看到的两处：`Cu/SB/P-Cu/SB/C` | `u 夹层结构`（同一个词被劈开两次），
以及 `120 μm ，图中` 里全角逗号前那道可见空隙。本轮先修第一处，并把两处的真值口径一起量死。

### 24.1 Word 到底在哪些字符对之间断行（不是猜，两条独立真值）

- Word COM 受控样张（`pwsh tools/word-break-truth.ps1`，14 个用例，版面与论文正文同宽 566.93 px）：
  `w:wordWrap` 缺省或 `true` 时 `[A-Za-z0-9]` 整串不可断（token-true 首断点 20，26 个字母整串挪到下一行）；
  `w:wordWrap val="0"` 才允许串内断（token-false 首断点 42）；`-` 之后可断（hyphen-edge 首断点 31）；
  `/` 之后不可断（slash-edge 首断点 20；slash-then-space 首断点 31 断的是空格）；
  整串比一列还宽时才在串内断（plain-60A：Word 先把整串挪到空行，再断成 49+11）。
- Word 导出 PDF 全篇对回源文（`py tools/break-class-truth.py`）：238 个真断点里
  cjk|cjk 205、cjk|lat 13、lat|cjk 7、cjk|num 7、num|cjk 3、other 1（`—|S`），**串内断 0**。

### 24.2 我们为什么会在字母串中间断：是自己的 span 造出了可断位置

`StaticLayout` 把 ReplacementSpan 的边界当成可断点。`autoSpaceDE/DN` 那 1/4 em 的缝此前挂在**拉丁字符**上，
于是"CJK|拉丁"这道缝在单词内部多出一个可断位置，手机就从那里断：`构建Cu/SB/P-Cu/SB/C|u夹层结构`、
`…局部SE|M形貌`、`…结合ED|S确认裂纹路径`、`PC/SAC30|5`、`Ag3S|n`。论文里 10 行如此
（`py tools/midword-audit.py --lines artifacts/agent-layout-verify/revert-linear/new/lines-all.tsv`：token_cut=10）。

改法（只改 span 落在哪个字上，不改缝的宽度）：一道缝只许骑在**汉字**上；汉字两侧都有拉丁时两条缝合并到一个 span
（一个区间上放两个 ReplacementSpan，平台只会用其中一个去量），绘制时按缝在哪一侧把字形挪 `textSize/4`。
两端对齐撑缝时同理：撑缝的目标字符若在单词内部，就顺延到该串之后的第一个汉字，撑开的像素仍然加在同一处宽度上。

复采（`py tools/midword-audit.py --lines artifacts/agent-layout-verify/breakfix3/new/lines-all.tsv`）：
**token_cut 10 -> 1**，只剩 page 19 para 160 line 3 `、孔洞率、Ag3` | `Sn分布`。
这一行留着的原因已量清：源文里 `Ag` 与下标 `3` 与 `Sn` 是**三个 run**（`<w:vertAlign w:val="subscript"/>` 单占一段），
下标用的是缩放 span，它的右边界正好落在 `3|Sn`；这不是 autoSpace 那族，下一轮单独处理（不许再用"整串不可断"糊过去）。
受控用例那边：wrap-absent/true/false、token-absent/true/false 六个用例的首断点已与 Word 一字不差
（`pwsh tools/break-rules-parity.ps1 -Device artifacts/agent-layout-verify/breakrules-A3/new`）。

### 24.3 平台这条路堵死：Android 10 不接受我们递给它的断行规则

`pwsh tools/breakiterator-probe.ps1`（真机 app_process，sdk=29）：
`StaticLayout.Builder.setBreakIterator` 反射 `NoSuchMethodException`（API 24 起废弃、API 29 移除），
`PrecomputedText$Params$Builder` 只剩 `setBreakStrategy`。也就是说"把 Word 的断行规则交给平台"在目标机上做不到，
只能我们自己在 span 摆放上负责——本轮的改法就是这个结论的直接产物。
同一文本三种 breakStrategy 的断点不同（plain-60A：default 47,87 / simple 6,57,95 / balanced 41,77），
simple 那一列更像 Word（6,55,93），但换 strategy 会同时改掉中文与标点压缩的口径，不在本轮动。

### 24.4 六条前后（`pwsh tools/parity-six.ps1 -Tag breakfix3 -Tree artifacts/privtree/breakfix3/app/src/main/java`）

| # | 指标 | 改前（tag `revert-linear`） | 改后（tag `breakfix3`） | 判定 |
| --- | --- | --- | --- | --- |
| 1 | 段落页归属 | 7 段错页 / 206 | 7 段错页 / 206（pages.tsv sha `E23F59E8C409EF4C` 不变） | 不动 |
| 2 | 逐行行高中位误差 | -0.767 px | -0.767 px | 不动 |
| 3 | 逐行行高 p90 | 2.533 px | 2.533 px | 不动 |
| 4 | 每页累计高度 | 0.79 行 | 0.79 行 | 不动 |
| 5 | 断点一致率 | 114/165 = 69.1% | 115/165 = 69.7% | +1 行 |
| 6 | 右边界超出 1px | 0 / 32 | **1 / 33** | **退 1 行** |

第 6 条退的那一行：`（1）连接试验以浸渗时间、连接温度、连接时间和压力为自变量，以Sn填`，
我们 563.00 px，Word 564.53 px（少 1.53 px）。原因：这条线以拉丁串 `Sn` + 汉字 `填` 收尾，
缝挪到 `填` 上之后，撑缝那一轮量 `填` 的步长时要看它后面那个字符的水平位置，而那个字符已经在下一行，
步长量成负数、这道缝就被踢出可撑集合，整行少撑 1~2 px。本轮已经补了"行末字符改用本行墨迹右边界量"
（`DocxTextLayout.gapOffsets`，两处注释里都写着这个数），但这一行仍然差 1.53 px，
说明撑缝还漏了别处，下一轮按 `tools/edge-parity.ps1` 的 gap 列继续查（同一轮不许再动阈值）。

`lines-all.tsv` sha `5F714EE762A78345`、dex `932bc7c35f0f0a72…`、字体表指纹 `E751F0AFAC19`。

### 24.5 缺陷 B（全角逗号前那道空隙）：先记下真值口径，本轮没动引擎

源文里 `120 μm，图中` 逗号前**没有空格**（`py` 扫 `word/document.xml`：全篇 0 处"空白 + U+FF0C"），
所以那道空隙是我们的，不是稿子里带的。Word 侧同一句的逐字符 x 已经采到
（`artifacts/word-break/word-chars.tsv`，`comma-after-latin` 用例：`m`→`，` 的步长 9.5 pt，
而汉字之间步长 12 pt、`示`→`C`（汉字|拉丁）步长 14.95 pt = 12 + 2.95 ≈ 1/4 em）。
即：Word 在"拉丁|全角标点"处不加那 1/4 em，这与引擎里 `isCjkPunctuation` 的排除一致，
所以嫌疑落在两端对齐撑缝把 slack 倒进这道缝上。手机侧逐字符 x 的量台已就位
（`artifacts/device/probe/DeviceCapture.java` 现出 `chars-at-punct.tsv`：每行含"拉丁紧邻全角标点"的行逐字符 x/步长），
下一轮两边逐字符相减，再决定改撑缝还是改标点压缩口径。

## 26. 固定值行距：先量死"引擎收到什么"，再把它当一把绝对长度（真机 LineSpacingProbe）

用户给的前提是"那两页写着行距固定值 20 磅 / 16 磅，我们静默沿用了 1.5 倍行距"。这一轮第一步不看 XML，
先看引擎到底收到什么：新增量台 `tools/device-probe/LineSpacingProbe.java`（一键跑
`pwsh tools/line-spacing-probe.ps1 -Tree <快照>/app/src/main/java -Pages 1,2,3,20,21`，在手机上用
app_process 跑同一套 DocxParser + A4Paginator），每段打印 DocxParser 走完 w:pStyle / basedOn /
docDefaults 继承之后交给 DocxTextLayout 的 (w:line, w:lineRule, snapToGrid)、排版后的第一行上挂了
几个 LineHeightSpan、以及分页真正按的行高。这样"解析器没继承到"和"继承到了但排版不理"分得开。

### 26.1 全篇的声明普查：这篇稿子里没有 20 磅 / 16 磅的行距

- 引擎侧（tag `linespacing1`，engine head_sha `a81444a`，373 段全打）：只有一条 exact——第 0 段
  （封面"毕业论文 毕业设计"）`<w:spacing w:before="240" w:lineRule="exact" w:line="380"/>` = 19 磅，
  引擎读到 lineTwips=380、rule=exact、第一行 LineHeightSpan=1，用的行高 25.0px。其余全是 auto：
  line=300×262、288×41、240×20、284/324/360 各 1；**atLeast 0 条，400（20 磅）0 条，320（16 磅）0 条**。
- 样式那边也没有：styles.xml 的 lineRule 全是 auto（line=240×175、300×5、276×2、480、360、280）。
- 桌面 Word 自己的读数（`artifacts/agent-layout-verify/page-stack-all/page_stack.tsv` 的
  LineSpacingRule 列，Multiple=5 / Single=0 / 1.5 倍=1）：Multiple 325 段、Single 69 段、1.5 倍 1 段
  （第 177 段"表4-1课题进度及主要工作"，正文里那条唯一的 line=360 auto），**Exactly 0 段、AtLeast 0 段**。
- Word 自己的相邻基线距离（同一份真值，117 个多行段落）：主峰 19.7pt = 26.267px（23 段），
  第二族 17.325pt = 23.100px（16 段），中位 25.900px；**正好 20.0pt 的段落 0 个，正好 16.0pt 的 0 个**。

结论说白：那两页没有"固定值被丢掉"这回事，这篇稿子根本没写过 20 磅 / 16 磅的行距。
1154 / 923 那两个数是按某个 em 比例换算出来的我们自己算的数，不是 Word 的行高，不能拿来当对账目标。

### 26.2 那个 em 比例顺手量死了：是 1.000，不是 1.0741

同一台手机（CDY-AN90，sdk 29）同一轮采样里的 SCALE 行（`pwsh tools/line-spacing-probe.ps1 -Pages 20,21`）：

| 字号 | 该是几 px（pt×4/3） | Paint.getTextSize() | "汉"宽 | 汉宽/em |
| --- | --- | --- | --- | --- |
| 9pt | 12.0 | 12.0000 | 12.0 | 1.00000 |
| 12pt | 16.0 | 16.0000 | 16.0 | 1.00000 |
| 15pt | 20.0 | 20.0000 | 20.0 | 1.00000 |
| 18pt | 24.0 | 24.0000 | 24.0 | 1.00000 |
| 22pt | 29.3333 | 29.3333 | 29.0 | 0.98864（整像素 advance，第 21.4 节那条） |

`1440twips = 96.0000px`、`1pt = 1.333333px`。也就是说文档坐标到像素的比例是 **1.000**：
20 磅就该是 26.6667px、16 磅就该是 21.3333px。1154/1080 那个 1.0741 不进引擎。

### 26.3 改了什么：exact 是一把长度，画整像素、按小数分页

只动 exact 这一条，atLeast 一个字没改：

- `WordLineHeights.fixedAdvancePx(twips) = twips / 15`（不取整）、`fixedBoxPx = round(它)`。
  `DocxTextLayout.Spacing` 用行盒画、把小数那一截放进 `lineCarry`，由 A4Paginator 按行补给分页
  （`minimumLineHeight` 的 exact 分支同步改成不取整，图片段与无文字段同一个口径）。
  封面那条 380twips：过去整段按 25px/行收费，现在画 25px 的行盒、按 25.3333px 分页。
- 网格不许再把固定值补到整格（固定值的意思是这一行就这么多高），所以 carrying 时 `gridSnapGrownRow=false`；
  只有行盒还等于固定值算出来的那一格时才交小数，避免网格地板抬过之后重复加钱。
- 上标下标仍然不许抬高固定值的行：`clipScripts = exact` 这条本来就在，本轮补了断言。
  脚注引用这一族引擎里没有落地排版（`DocxParser` 只记 `output.hasFootnotes`，正文里没有脚注标记 run），
  宽度与高度天然都是 0，本轮没有改这条。
- 断言（`pwsh tools/run-suites-private.ps1 -Tree artifacts/privtree/fixedlh -OutDir artifacts/privout/fixedlh
  -Suite WordLineHeightRegression,ScriptRegression,Regression`，全绿）：
  `tests/WordLineHeightRegression.fixedLineHeightIsAnAbsoluteLength` 新增 6 条——20 磅 = 26.6667px、
  16 磅 = 21.3333px、380twips = 25.3333px、行盒 27px / 25px、比例锁在 1.000。
  三个套件的读数是 69 / 65 / 74 条断言 PASS。

六条前后（`pwsh tools/parity-six.ps1 -Tag fixedlh1 -Tree artifacts/privtree/fixedlh/app/src/main/java`，
对照同机 HEAD 复采 `-Tag head25`）：

| # | 指标 | 改前 `head25` | 改后 `fixedlh1` |
| --- | --- | --- | --- |
| 1 | 段落页归属 | 7 段错页 / 206（exact 96.6%，全是 -1） | 7 段错页 / 206（一字不差） |
| 2 | 逐行行高中位误差 | -0.767 px | -0.767 px |
| 3 | 逐行行高 p90 | 2.533 px | 2.533 px |
| 4 | 每页累计高度 | 0.79 行 | 0.79 行 |
| 5 | 断点一致率 | 115/165 = 69.7% | 115/165 = 69.7% |
| 6 | 右边界超出 1px | 1 / 33 | 1 / 33 |

六个数一个没动，`lines-all.tsv` 的 sha 两边都是 `5F714EE762A78345`——这是预期结果而不是没测出来：
这篇稿子只有封面那一个 exact 段落、而且封面自己就是一页，多出来的 0.3333px 换不了页。
这一条改的真正用处是用户自己在编辑器里选的固定行距（`EditorActivity` 写 `w:lineRule="exact"`），
以及任何真写了固定值的 docx。不许拿这个改动去声称页归属涨了。

### 26.4 21 页 vs 20 页：页数是 28 = 28，那个数字说的是 6 个段落的归属

- `pwsh tools/parity-six.ps1 -Tag head25 -Tree artifacts/privtree/head25/app/src/main/java`
  （HEAD `68beb77`）：**页数 ours/Word = 28/28**。这篇稿子既不是 20 页也不是 21 页。
- Word 放在物理第 21 页、我们放在第 20 页的是这 6 段：word_para 208 `（2）建立可重复的多孔Cu制备流程…`
  到 `（7）形成统一的数据表、显微组织图、统计图和…`（4.2 预期目标那串）。
  同一族的另外 7 段错页全是 -1：para 121（Word 14 / 我们 13）、155（18 / 17）、208（21 / 20）、
  382（25 / 24）、397（27 / 26）、408 与 409（28 / 27）。没有一段是晚一页。
- 所以"改完固定值能不能从 21 页塌回 20 页"这个问法本身不成立：两边页数本来就相等，
  要修的是这 7 段的归属；-1 的方向说我们每页装得比 Word 多，账就在第 2/3/4 条那 0.767px/行上，
  而那一族单独修会在别处多出来（第 23 节、第 19 节的反向抵消是同一条原因）。
- 另外记一笔对不上：交接时引用的基线"201/206、73.9%、78.8%、0/33"在 HEAD 上复现不出来，
  同一条命令的读数是 199/206（7 段错页）、69.7%、1/33（`artifacts/agent-layout-verify/head25/six.txt`）。
  写进更新说明的数应当从这份读取出。

## 27. 第七项：断点一致率改用 Word 导出 PDF 那把尺（63.4%）；并核对清 `497f19b / 204/206 / 50.1%` 这组数不存在

**口径先写死，后面每一轮都照这句引用。** 断点一致率只认 `tools/break-agreement.py` 打出来的那个数：
真值是 Word 自己导出的 PDF（`artifacts/agent-typeset/pdf-truth/input-liu.pdf`，
sha256 `AB298AC416DCFC72855645E5AD8E472ABDA3DFFC9A6B10925A4FAA1396E761A1`，876,606 字节），
用 pymupdf 的 `rawdict` 逐行读回原文；每个段落两边各得到一组"这一行停在第几个字"的偏移，
一致率 = 两边都断的点数 / 两边不重复的点数。旧口径的 115/165 = 69.7% 只出现在第 5 条那一行，
那句"对我们自己的行序逐行号对"跟着它一起写死，两个数不是同一把尺，不许并列引用。

### 27.1 怎么跑（也写进了 `tools/parity-six.ps1` 的头注）

```
py tools/break-agreement.py -SelfTest                    # 13 条断言，量法本身不许悄悄漂
py tools/break-agreement.py -Capture artifacts/agent-layout-verify/<tag> \
                            -Out artifacts/word-break/break-agreement.tsv
pwsh tools/parity-six.ps1 -Tag <tag>                     # 第七项现在自己出现在表里
```

`-SelfTest` 不需要 PDF 也不需要采样，它盯的是量法：一处断行吞掉的空格必须和下一个真字符算同一个断点
（`canon("中文 word 中文", 2) == 3`），每个类别名各钉一个用例（`cjk|cjk`、字母串内部、`cjk|latin`、
`latin|cjk`、`cjk|digit`、标点后、标点前、斜杠后、括号引号后），一致率的算术钉成 2 个共同 + 1 个只我们有
+ 1 个只有 Word 有 = 50.0%。写这几条断言的时候它当场逮到我三处下位数错，这正是它的用途。

### 27.2 本轮读数（采样 `main251`，`lines-all.tsv` sha `5F714EE762A78345`，engine head_sha `df89547`）

```
BREAK AGREEMENT (set ruler): 225 of 355 break points = 63.4%
  phone_only (we broke, Word did not) = 65  (18.3% of all break points)
  word_only  (Word broke, we did not) = 65  (18.3%)
paragraphs compared=102   device blocks=210   Word paragraphs with a full line set=114
page assignment from this same truth file: 6 of 102 compared paragraphs start on a different page
```

两件事值得单记：

1. 102 段里只有 34 段第一个断点就和 Word 不一样，另外 68 段整段一模一样。一段之内一旦第一处错开，
   后面每一行的行尾都跟着挪，所以 65 处"我们多断"里有 96 处属于这种被带偏的后续点。
   **排队修的时候按"每段第一处错点"排，不按 65 这个总数排**，否则会把同一个病数四遍。
2. 每处断点都带 Word 那一行的页码，所以页归属直接从同一份 PDF 真值算得（6/102 段起页不同），
   不用为页码再开一次 Word 会话。这个 6 与 `tools/word-parity.ps1` 的 7 段错页是同一件事在两个
   分母上的读数（102 段可比 / 206 段对齐），两边方向一致（全是手机早一页）。

每段第一处错点的分类（`py tools/break-agreement.py` 的 `-Top` 表，另有
`artifacts/word-break/break-agreement.tsv` 逐点一行）：

| 方向 | 断点落在什么上面 | 第一处错点 | 该方向合计 |
| --- | --- | --- | --- |
| 我们多断 | 两个汉字之间 | 8 | 38 |
| 我们多断 | 一个真空格上 | 6 | 12 |
| 我们多断 | 汉字与数字之间 | 3 | 3 |
| 我们多断 | 中文标点之后 / 之前 | 2 / 1 | 3 / 1 |
| 我们多断 | 中西文交界、斜杠后、其它 | 各 1 | 4 |
| 我们多断 | **连续字母数字串内部** | 0 | **1** |
| Word 断了而我们没断 | 两个汉字之间 | 3 | 32 |
| Word 断了而我们没断 | 一个真空格上 | 5 | 11 |
| Word 断了而我们没断 | 中文标点之后 | 0 | 8 |
| Word 断了而我们没断 | 中西文交界 / 数字与汉字 / 连字符后 | 0 / 1 / 0 | 4 / 3 / 2 |

真机第 8 页那个把 `Cu/SB/P-Cu/SB/Cu` 拆成 `C|u` 的缺陷，在第七项这把尺上现在是 **355 处断点里 1 处**
（`inside alnum run`，我们多断方向）。这一族从第 24 节记的 10 行降到 1 处，是确实落在 HEAD 里的。

### 27.3 Word 那侧三条几何事实（顺手量死，其中一条否掉我们自己的错线索）

命令是把 Word 导出的 PDF 用 `pymupdf` 的 `rawdict` 逐字读回坐标（脚本要点：
`page.get_text("rawdict")` 的 line 结构与 Word 的行一致，正文 803 行；
按 y 聚 `get_text("words")` 会得 1020 个假行，只能当交叉核对）。

1. 版心：左边界众数 85.1 pt（381 行），右边界众数 510.3 pt（93 行）→ 425.2 pt = 566.9 px，
   和 `tests/samples/input-liu.docx` 的 sectPr（11906 − 1701 − 1701 twips = 8504 twips = 566.93 px）对上。
2. 汉字 advance：段落末行（不拉伸）的众数是 **12.000 pt = 16.000 px 整**（5,634 个采样）；
   两端对齐的行是 **12.108 pt**（2,576 个）。也就是说 Word 是靠**拉开字距**做两端对齐的，
   它没有把整宽字压缩到 12 pt 以下。
3. 一条整行最多装 35 个整宽字：36 × 12.000 = 432.0 pt > 425.2 pt。实测所有左边界 ≤ 90 pt 的整行里，
   汉字数最多的桶只到 34（再算上不落在 `\u4e00-\u9fff` 的全角标点正好 35~36 字），
   **没有任何一行装下 36 个整宽字**。

第 3 条否掉一条我们自己的线索：第 26 节之前 replay 台给出的"Word 靠悬挂标点多吃一个字，
打开悬挂可把断点率从 23.3% 抬到 30.9%"不能当引擎改法用。
`py tools/break-replay.py -Top 8` 的读数是当前规则 23.3%（67/288）、最好组合 31.2%（90/288），
而真机对同一批段落量到的是 63.4% —— 差出四成说明这个 Python 重排台自己的贪心断行就是错的，
它排序出来的"哪条规则值得改"不可信。**要定规则就改引擎上真机复采，不要在 replay 台上试。**

### 27.4 查无此提交：`497f19b`、`breakBefore[IDENTIFICATOR]`、`204/206`、`50.1%`

发版前逐条核对，全部可重跑：

```
git cat-file -t 497f19b                 -> fatal: Not a valid object name 497f19b
git cat-file --batch-all-objects --batch | Select-String -SimpleMatch 'IDENTIFICATOR','breakBefore'
                                        -> hits: 0（全库 2,305 个对象，含 loose、pack 与不可达对象）
git grep -n 'IDENTIFICATOR\|breakBefore' -> 工作树 0 处（app / tools / tests / docs 全查过）
git log --all --oneline                 -> 没有任何一节的标题或正文写过 204/206 或 50.1%
```

名为 `breakfix1` / `breakfix2` / `breakfix3` 的三次真机采样也支撑不了那组数。把每份 `six.tsv` 的
`n=value` 串起来取 sha256 前 12 位，六个 tag（`head25` / `fixedlh1` / `breakfix1` / `breakfix2` /
`breakfix3` / `main251`）全是同一个 `BBFB62626BC7` —— 六个数一字不差。再看指纹：
`breakfix2`、`breakfix3` 的 `new/lines-all.tsv` 与 `head25`、`main251` 字节相同（sha 前 12 位
`5F714EE762A7`），而它们的 engine head_sha 分别是 `e30c376`、`68beb77`、`df89547`。
换了引擎提交、采样输出一个换行点都没变，这是"那轮改动没有作用面"的直接证据，不是"改动没跑起来"。

结论写在这里给发版用：**`196/206 -> 204/206`、`28.5% -> 50.1%`、"页数与 Word 同为 28" 这三个数在这台
机器上没有任何一份采样支撑，`breakBefore[IDENTIFICATOR]` 那段代码从来没有作为文件存在过。**
2.6.2 不该发它。要这条规则，就照 27.5 的顺序从真值重做。

### 27.5 下一步（按页数优先）

1. 先只盯"每段第一处错点"里最大的一类：我们多断、断在两个汉字之间（第一处 8 段 / 合计 38 处）。
   这一类要拿第 2 条那个 12.000 pt 与第 3 条那个 35 字上限去对：我们到底在哪一行比 Word 少装了一个字，
   少的那一个字的宽度是从哪一项扣掉的（版心取整 567 对 566.93、中西缝隙 4.000 px 落在断行处是否计入、
   首行缩进的计量单位）。三处都用真机复采判，不在 Python 台上判。
2. 每改一类报三样：这类的第一处错点从几降到几、六个老指标、错页段数。页数与页归属优先于断点率。
3. 引文那一簇（我们没断 Word 断了）单列一张表：`artifacts/word-break/citation-cuts.tsv`。
   本轮它读到 0 行——判定规则（一段里没有空格、连续拉丁 ≥ 10 字）在真值段落上不成立，
   参考文献条目里是带空格的。这条得换个判法（按段落落在参考文献区 `word_para >= 300` 判），
   换完之前那 13 处的说法先不要引。
---

## 28. 悬挂末行标点按 Word 自己导出的 PDF 改准（第 7 条 63.4% -> 64.3%）；行高 p90 与"我们多断"逐处量死

本轮只动一族：`w:overflowPunct`。行高 p90 的归因（28.6）与"我们多断"的分类（28.7）是量出来的结论，
没有跟着改引擎。改前基线 tag `hangbase2`（引擎 = HEAD `e1e77bc` 的私有快照 `artifacts/privtree/headbase`），
改后 tag `hang2`（工作树引擎），同一台手机（`EAMUT20528011355`）、同一次采样会话、同一份 Word 导出 PDF 真值。

### 28.1 真值：Word 让 22 行把最后一个整宽标点画在版心外，一行只挂一个

```
py tools/hang-truth.py     # 真值 artifacts/agent-typeset/pdf-truth/input-liu.pdf，sha256 AB298AC4...96E761A1
body lines at the left margin drawn at 12.0 pt: 370
ending more than 1.0 pt past Word's own right margin: 22
  how far past (pt): min 11.42  median 12.01  max 12.44
  which character hangs: ， x7, 。 x7, 、 x4, ℃ x2, ； x2
```

版心 = 85.1 -> 510.3 pt = 425.2 pt = 566.93 px。12 pt 的一个整宽字框正好 12.00 pt，所以"越界
11.42~12.44 pt"就是这个字框整个画在版心外，且一行最多挂一个（22 行里没有一行挂两个）。
`℃` 那 2 行 Word 也挂，但 `℃` 不是收尾标点，本轮没进符号集，是写进引擎注释的已知剩下误差。

声明侧（数 `word/document.xml` / `word/styles.xml` 里的 `<w:overflowPunct>`）：段落自己声明 105 处
（空标签即 true），显式 false 0 处，样式表 0 处。也就是说该开的段落都开着，这条规则不需要猜缺省。

### 28.2 引擎改了什么（只 `DocxTextLayout.java` 一个文件）

1. 删掉旧的"按墨迹宽挤进本行"（`hangInkFraction()` + `ceilInk()`）。它对中文两端对齐行永远触发不了：
   `justifyEastAsian()` 已把整行铺到版心右边界，再要求标点的墨迹也在版心里 = 要求第 37 个整宽字挤进
   只装得下 36 个的列，于是那一行只能少装一个字。
2. 换成 Word 的规则：`isHangingPunctuation()` 收 。 ， 、 ； ： ？ ！ ” 八个收尾符号；`hangSplit()`
   让这个符号只用掉"本行在版心里还剩的位置"，剩下的 `overhangPx` 画在版心外，行内其它位置一个都不动。
3. `dropMarksThatMoved()`：悬挂符号是按剩余位置量的（可能是 0 宽），只有当它确实是本行最后一个字时才安全，
   所以每次重排后把已经不在行尾的 span 撤掉。
4. `Paragraph.lineOverhang`（逐行 px）把越界量交给画图与右边界对账；探针多写一列 `hangOverPx`。
5. 断言 `tests/HangPunctuationRegression.java`（4 组：8 px 宽的符号要悬挂整 8 px、版心 566.93 px 的落点、
   剩余位置不足、非收尾符号不许挂）已挂进 `tools/test-host.ps1`。本轮主机测试：
   `pwsh tools/run-suites-private.ps1 -Tree . -OutDir tmp/hang-out9 -Suite HangPunctuationRegression,Regression,`
   `WordLineHeightRegression,ScriptRegression,TableGeometryRegression,PageTextRegression` -> 全部 PASS，failed=0。

### 28.3 七条前后

```
pwsh tools/parity-six.ps1 -Tag hangbase2 -Tree artifacts/privtree/headbase/app/src/main/java   # 改前
pwsh tools/parity-six.ps1 -Tag hang2     -Tree app/src/main/java                               # 改后
```

| # | 指标 | 改前 `hangbase2` | 改后 `hang2` |
| --- | --- | --- | --- |
| 1 | 段落页归属 | 7 段错页 / 206（exact 96.6%，全是 -1，para 121 起；28/28 页） | 7 段错页 / 206（同一组段，一字不差） |
| 2 | 逐行行高误差中位 | -0.767 px（n=91，Word 26.267） | -0.767 px |
| 3 | 逐行行高误差 p90 | 2.533 px（max 3.467） | 2.533 px |
| 4 | 每页累计高度误差 | 0.79 行 | 0.79 行 |
| 5 | 换行点一致率（旧口径，不可与第 7 条并列） | 115/165 = 69.7% | 115/165 = 69.7% |
| 6 | 右边界超出 1px 的行数 | 1 / 33 | 1 / 33（同一行：para 160 第 14 行 563.00 对 Word 564.53） |
| 7 | 断点一致率（Word 导出 PDF 真值） | 225/355 = 63.4%（多断 65、少断 65） | **227/353 = 64.3%**（多断 63、少断 63） |

指纹：`hangbase2` `lines-all.tsv` sha `2D96481F8488F0B7`、dex `1d07bffd…`；`hang2` `lines-all.tsv` sha
`11241D5CED2CA4A5`、dex `ad3f11ff…`；两边 `pages.tsv` sha 不同（`E23F59E8C409EF4C` / `7809FC19F6A92CA7`），
因为 3 段的行内容与跨页位置挪了一行，但第 1 条的读数和页差直方图完全相同。

第 6 条再加一个把墨迹算进去的口径（`pwsh tools/edge-parity.ps1 -Impl <tag>/new -Out <tag>/edge.tsv`）：

```
改前  within 1 px of the right margin: 32/33    ink past the right margin: 0/33
改后  within 1 px of the right margin: 32/33    ink past the right margin: 1/33
      Word 自己那行也越版心的：1 行；我们比 Word 多越 1px 以上的：0 行
```

改后确实有 1 行挂着收尾标点越出版心，而 Word 的同一行也越版心，所以它不算新的越界；旧的 32/33 口径没动，
两条一起看。

### 28.4 那 22 行逐行对：像 Word 一样挂着的从 3 行变 5 行

`py tools/hang-truth.py -Capture artifacts/agent-layout-verify/<tag>`

| 每行的结局 | 改前 `hangbase2` | 改后 `hang2` |
| --- | --- | --- |
| 与 Word 同一行、同一串字 | 3 | **5** |
| 标点被挤到下一行（本行少一个字） | 1 | 1 |
| 我们的断点根本不在这里 | 18 | 16 |

改后真机上有 8 行带越界量：`p15 blk130 。15.00`、`p19 blk161 ，15.00`、`p5 blk69 、9.00`、`p5 blk72 、9.00`、
`p19 blk164 、3.00`、`p20 blk195 ，2.00` 等。15.00 px 对 Word 的 11.42 pt x 4/3 = 15.23 px，一致。
剩下那 16 行"断点不在这里"说明这 22 行主要不是悬挂问题，而是每行少装 1~2 个整宽字（28.7 的 `cjk|cjk`）。

### 28.5 量台自己的一次假数：表头少一个换行符

`DeviceCapture` 上一次加 `hangOverPx` 那一列时把表头字符串结尾的 `\n` 吃掉了，表头和第一条数据粘成一行。
所有按列名取值的脚本（`csv.DictReader`、`Import-Csv`）不报错，只把这一列读成 0，于是"真机上一行都没有悬挂"
这个结论看着干净、其实是假的；同一份采样里第 5 条也报成 `0/0`。修好之后同样的引擎、同样的稿子量出 8 行
悬挂、第 5 条 115/165。

两条后果：1）本轮之前的 `hang1`、`hangbase` 两份采样作废（表头坏），本轮用 `hangbase2`/`hang2`；
2）探针进了版本库（`tools/device-probe/DeviceCapture.java`，`tools/capture-device.ps1` 默认用它，
`-Probe` 可覆盖），文件头写明"加列之后必须把打印出来的表头和第一行数据对一遍"。

### 28.6 第 3 条 p90 = 2.533 px 落在哪几段（只定性，没调常数）

```
py tools/line-height-tail.py -Capture artifacts/agent-layout-verify/pdfgate1
rows=91  median|delta|=0.767 px  p90|delta|=2.533 px  max=3.467 px
```

尾巴正好 12 行，两类声明，全部是 `snapToGrid=false` + `lineRule=auto` + 本行只有西文/数字：

| 声明 | 尾巴行数 | Word 行高 | 我们行高 | 中位误差 | 段号（设备 blockIndex） | 占尾巴误差总量 |
| --- | --- | --- | --- | --- | --- | --- |
| sz12 line300 auto snap=false grid=false 本行无汉字 | 8 | 26.267 px | 23.00 px | -3.267 | 115, 122, 132, 139, 196, 339 | 70.3% |
| sz12 line276 auto snap=false grid=false 本行无汉字 | 4 | 23.533 px | 21.00 px | -2.533（最差 -3.467） | 78 | 29.7% |

全篇对照（同一支脚本）：`sz12 line300 auto snap=true` 有汉字的 62 行 Word 26.267 / 我们 25.50（-0.767）；
`sz12 line284 auto snap=true` 8 行 Word 24.467 / 我们 25.50（**+1.033**，这一族是我们多收）。

定性：五类队列的声明各自完全相同，Word 却在不同行给出不同行高（同一份 `line=300 auto` 声明下出现
26.267 与 25.400 两种），而我们对一段只给一个值。**Word 是按这一行实际用到的那张脸的 ascent+descent 乘行距
重算行高，我们是按段算一次。** 那两类"本行没有汉字"的尾巴行 Word 给的是 26.267 / 23.533（和汉字行的值同一档），
我们给 23.00 / 21.00（西文脸自己的行高），说明 Word 用的还是这一段声明的中文字脸，而不是本行出现过的那张脸。
下一轮该做的是把行高从"按段一个值"改成"按行一个值"，动的是 `WordLineHeights` 那条链（已有
`tests/WordLineHeightRegression.java` 69 条断言兜着），不是去调那两个像素。

### 28.7 "我们多断"的 63 处：分类、页效应、逐处实例

```
py tools/break-agreement.py -Capture artifacts/agent-layout-verify/hang2
```

| 断点落在什么上面 | 处数 | 牵动几个错页段 | \|chars_off\| 中位 | 差 1 个字的占比 | 判词 |
| --- | --- | --- | --- | --- | --- |
| 两个汉字之间 | 36 | 0 / 6 | 2 | 18/36 | 宽度：Word 一行多装 1~2 个整宽字 |
| 一个真空格上 | 12 | 1 / 6 | 5 | 1/12 | 24~28 页英文参考文献，两边选的空格不同 |
| 中文标点之后 | 3 | 0 / 6 | 1 | 2/3 | 悬挂那一族的边角，本轮之后从 3 处起继续看 |
| 汉字与数字之间 | 3 | 0 / 6 | 2 | 1/3 | 宽度 |
| 斜杠之后 | 2 | 0 / 6 | 9 | 0/2 | 见下面两条实例，方向相反 |
| 中西文交界 | 2 | 0 / 6 | 4 | 0/2 | 宽度 |
| 标点之前 / 西文与汉字 / 数字与汉字 | 各 1 | 0 / 6 | 1 | 1/1 | 宽度，差一个字 |
| **连续字母数字串内部** | **1** | 0 / 6 | 3 | 0/1 | 上下标 span 那处，见下 |

（`chars_off > 0` = Word 的最近断点在我们后面，即我们的行早断、少装了字；`< 0` = Word 更早断，
它把某个整串挪到了下一行而我们把它留在本行。）

几条决定改法的实例（前 8 字 + 断点 + 后 8 字，`para/blk/Word 页码/Word 行号`）：

1. `para 159 blk 160 第 19 页 5/5 行 off -3`：`厚度、孔洞率、Ag3|Sn分布、剪切强度保`。
   Word 把 `Ag3Sn` 整串挪到下一行，我们在 `3|Sn` 之间断了。这就是上下标 span 那一族，也是全部剩余
   的"字母数字串内部断行"唯一一处（第 24 节记的 10 行 -> 第 27 节 1 处 -> 本轮仍 1 处）。
2. `para 86 blk 87 第 8 页 1/7 行 off +2`：`B/P-Cu/SB/|Cu夹层结构，并在甲`。
   Word 把 `Cu` 留在本行、断在 `Cu` 之后，我们的行止于斜杠。也就是这一处是"我们少装两个字"，
   不是"我们在斜杠后多断了一个字"。
3. `para 113 blk 114 第 13 页 2/3 行 off -9`：`建CuO/NaCl/|Ag体系的成形、还原`。
   Word 早 9 个字就断了。两处斜杠方向相反，所以"斜杠后面到底能不能断"这一条还没量死，
   不能按猜的改。
4. `para 371 blk 375 第 28 页 off -22`：`on, 2018, |144: 469-4`。英文参考文献行，Word 比我们的断点早 22 个字。

页效应这一列是本轮新量的，结论直接否掉一个假设：**这 63 处"我们多断"和 6 个错页段几乎不相干**
（只有 `at a space` 那一类牵到 1 段），所以"先修断点最多的那类，错页就会往下掉"在这篇稿子上不成立；
`cjk|cjk` 36 处一个错页段都不带动。错页要单独找原因（`word para 114 / device blk 115：Word 第 14 页、手机第 13 页`
是其中一段，方向与其它 5 段一样都是手机早一页）。

另外第 27.2 节那条规矩仍然有效：一段之内第一处错点会带着后面每一行一起挪，本轮 32 段有错点、
后面被带偏的后续点 94 处。上表是按"处数"排的，真要排修法顺序，看脚本里 "First divergence per paragraph"
那张表：第一处错点里 `cjk|cjk` 6 段、`at a space` 6 段并列最多。

### 28.8 更正第 27.3 节第 3 条

第 27.3 节第 3 条记的是"一条整行最多装 35 个整宽字……汉字数最多的桶只到 34（再算上不落在
`\u4e00-\u9fff` 的全角标点正好 35~36 字）"。这句在悬挂标点那一族上要加限定：`py tools/hang-truth.py`
新打印的那一行显示，Word 自己越版心的那 22 行字符数是 **min 36 / 中位 37 / max 49**，而越出去的那一个
标点也算行上的一个字符。真机改后同一批里的 `p15 blk130` 第 5 行、`p19 blk161` 第 3 行都是 37 个字符，
`StaticLayout` 报的行宽 567.00 px（正好版心），其中最后一个标点的 15.00 px 画在版心外，Word 的同一行
也是 37 个字符并越版心 11.42~12.16 pt。判据要写成"**版心里**的整宽字不超过 36 个"，否则合法的悬挂行
会被当成越界行处理。

### 28.9 剩下什么（不在本轮）

1. 第 2/3/4 条：行高从"按段一个值"改成"按行一个值"（28.6 的定性），这是三项一起的根因。
2. `cjk|cjk` 那 36 处 = 每行少装 1~2 个整宽字，属宽度模型（和 `autoSpace` 那族同源），下一轮单独量。
3. `℃` 悬挂 2 行；para 160 第 14 行右边界差 1.53 px；斜杠断点 Word 口径未定。

## 29. 2.6.5 装机版复采；"我们多断"的拉丁/数字那批：`/` 不能断、`-` 能断，量死了

本轮没动排版引擎（`git status` 里 `app/src/main/java` 一字未改），落地的只有三张量台与这一节记录。

### 29.1 复采口径（手机装的就是发布包，不是快照改动）

```
pwsh tools/tree-snapshot.ps1 -Out artifacts/privtree/release265 -Base 6c96dc7   # 87 个 .java，overlay=0，manifest 全 base
pwsh tools/parity-six.ps1 -Tag 265-release -Tree artifacts/privtree/release265/app/src/main/java
```

手机 `CDY-AN90`、android 10、sdk 29、1080x2400、480dpi；`adb shell dumpsys package com.rikkahub.wordlite`：
`versionName=2.6.5 versionCode=54`。探针跑的源码 = `head_sha 6c96dc705ab20d8b200ce4f8cea55122481d0113`
（= 出 2.6.5 那条提交），`layout_dirty=False`，dex `f84d7f6e9829323ebe1653857080fc3927560b0124d3128df605d34832ed66f3`，
`lines-all.tsv` sha `11241D5CED2CA4A5`、`pages.tsv` sha `7809FC19F6A92CA7`、字体表指纹 `AB3B592E304F`。

| # | 指标 | 2.6.5 真机读数（tag `265-release`） | 与 tag `hang2` 比 |
| --- | --- | --- | --- |
| 1 | 段落页归属 | 7 段错页 / 206（exact 96.6%，页差直方图 `-1=>7  0=>199`，首个错页 para 121），**页数 28 = Word 28** | 一字不差 |
| 2 | 逐行行高误差中位 | -0.767 px（n=91，Word 26.267 px） | 一字不差 |
| 3 | 逐行行高误差 p90 | 2.533 px（max 3.467） | 一字不差 |
| 4 | 每页累计高度误差 | 0.79 行（最差队列 sz12 line300 snap=false 2.55 行） | 一字不差 |
| 5 | 断点一致率（旧口径，对我们自己的行序） | 115/165 = 69.7% | 一字不差 |
| 6 | 两端对齐右边界超出 1px | 1 / 33 | 一字不差 |
| 7 | 断点一致率（Word 导出 PDF 真值） | 227/353 = 64.3%（102 段；多断 63 / 少断 63；同一份真值算出错页 6 段） | 一字不差 |

`lines-all.tsv` 的 sha 与 `hang2` 相同，所以"2.6.5 那几笔检索/下载改动没动到一个换行点"这句是被行序指纹证死的，不是推断。
第 7 项单跑：`py tools/break-agreement.py -Capture artifacts/agent-layout-verify/265-release -Out <tsv>`。

### 29.2 "我们多断"的 63 处按断点字符分类，两边一起数（`artifacts/agent-layout-verify/265-release/break-agreement.tsv`）

| 断点落在什么上面 | 两边都断 | 只有我们断 | 只有 Word 断 |
| --- | --- | --- | --- |
| 两个汉字之间 | 137 | 36 | 32 |
| 一个真空格上 | 47 | 12 | 11 |
| 汉字标点之后 | 16 | 3 | 6 |
| 汉字与数字之间 | 7 | 3 | 1 |
| 汉字与拉丁之间 | 8 | 2 | 4 |
| 斜杠之后 | 0 | **2** | **0** |
| 连字符之后 | 0 | 0 | **2** |
| 连续字母数字串内部 | 0 | 1 | 0 |
| 数字与汉字之间 | 1 | 1 | 3 |
| 拉丁与汉字之间 | 9 | 1 | 3 |
| 标点之前 | 2 | 1 | 0 |
| 其它 | 0 | 1 | 1 |

这张表把上一轮没量死的两条规则口径直接钉住：

1. **`/` 不是 Word 的断点。** 全篇 102 段里我们在斜杠后断了 2 次，Word 一次都没有。实例：`para 113 blk 114 第 13 页`——
   Word 第一行断在 `需要建|CuO/NaCl/Ag`（整串 `CuO/NaCl/Ag` 挪到下一行），我们断在 `建CuO/NaCl/|Ag`。
2. **`-` 是 Word 的断点，我们反倒不断。** `after -` 两边都断 0、只有我们断 0、只有 Word 断 2。方向与斜杠正好相反。
3. 字母数字串内部只剩 1 处：`para 159 blk 160 第 19 页第 5 行`，断点 `Ag3|Sn`，下标缩放 span 的右边界（第 24 节那条，本轮没动）。

数清楚谁该先修：**拉丁/数字边界那批一共 10 处**（`cjk|digit 3 + cjk|latin 2 + after "/" 2 + latin|cjk 1 + digit|cjk 1 + 串内 1`），
其中真正的规则问题只有 3 处（2 个斜杠 + 1 个下标 span），另外 7 处与最大的 `cjk|cjk 36` 是同一件事：我们的行早断 1~4 个字，属宽度模型。
所以"先修拉丁/数字边界"能拿到的上限是 3 处；剩下 60 处的钥匙在宽度那一族。

### 29.3 那 10 处逐条（断在哪两个字符、Word 同一行自己断在哪一类、段里声明了什么）

```
py tools/break-rule-source.py -Rows artifacts/agent-layout-verify/265-release/break-agreement.tsv \
   -Capture artifacts/agent-layout-verify/265-release \
   -Classes "inside alnum run,after '/',cjk|latin,latin|cjk,cjk|digit,digit|cjk"
```

工具每条打印：段号（docx 与设备块）、我们第几页第几行、Word 第几页第几行、断点两侧字符与码位、前后各 8 字、
`word_cut_chars_off`（正=Word 的最近断点在我们后面，即我们的行少装了字；负=Word 更早断，它把整串挪走了）、
本段 pPr 声明的 `wordWrap/kinsoku/adjustRightInd/autoSpaceDE/autoSpaceDN/overflowPunct/snapToGrid/widowControl`、
以及 Word 在这一行自己用的是哪一类断点。10 条的判定：**宽度 8 / 规则 2 / 同点不同类 0**。

声明口径也量死了（不是猜的继承）：`word/document.xml` 里 `<w:wordWrap/>` 出现 105 次、从不写 `w:val="0"`；
`word/styles.xml` 里 `wordWrap` 0 次、`kinsoku` 0 次；`docDefaults` 的 `pPrDefault/pPr` 是空的。
所以对这几个开关，"pPr 里看到的"就是"引擎收到的"，`None` 只表示"全篇没说，走 Word 默认"。

### 29.4 第 3 条 p90 = 2.533 px 在 2.6.5 上落在哪几段

```
py tools/line-height-tail.py -Capture artifacts/agent-layout-verify/265-release
rows=91  median|delta|=0.767 px  p90|delta|=2.533 px  max=3.467 px
```

尾巴正好 12 行，两类声明，都是 `snapToGrid=false + lineRule=auto + 本行没有汉字`：

| 声明 | 行数 | Word 行高 | 我们行高 | 中位误差 | 设备块 → docx 段号 | 占尾巴误差总量 |
| --- | --- | --- | --- | --- | --- | --- |
| sz12 line300 auto snap=false Latin-only | 8 | 26.267 px | 23.00 px | -3.267 | 115=para114、122=para121、132=para131、339=para335（另 139、196 没进对比行） | 70.3% |
| sz12 line276 auto snap=false Latin-only | 4 | 23.533 px | 21.00 px | -2.533（最差 -3.467） | 78 | 29.7% |

对照全篇同族：`sz12 line300 snap=true` 有汉字 62 行 Word 26.267 / 我们 25.50（-0.767）；`sz12 line284 snap=true` 8 行
Word 24.467 / 我们 25.50（**+1.033**，这一族是我们多收）。段号翻译用 `line-height-tail.py -Map <break-agreement.tsv>`，
默认读捕获目录里的那份，覆盖 102 段（没覆盖的块只是没有可比的行）。

### 29.5 行高能不能压到 2.0 px 以下：能（0.934 px），但页归属从 7 段崩到 71 段

`WordLineHeights.APPLIED_TO_LAYOUT=true`（表里宋体 1.31335 em、Times 1.17665 em 真用到排版上）在真机复采
（tag `lh-apply2`，引擎 = `f0956ce` + 这一行改动，`lines-all.tsv` sha `5D3D6C473904F641`）：

| # | 关掉（= 出厂 / 2.6.5） | 打开 | 判定 |
| --- | --- | --- | --- |
| 2 行高中位 | -0.767 px | **0.000 px** | 过 |
| 3 行高 p90 | 2.533 px | **0.934 px** | 满足"压到 2.0 以下"，仍不到 0.50 |
| 4 每页累计 | 0.79 行 | 0.00 行 | 过 |
| 1 页归属 | 7 段错页（全 -1），28 页 | **71 段错页（全 +1），29 页** | 崩 |
| 5/6/7 | 69.7% / 1 对 33 / 64.3% | 69.7% / 1 对 33 / 64.3%（错页 6 → 51 段） | 断点没动，页归属跟着崩 |

按第 0 节第 3 条规矩（有一条退步就不算过），开关保持关掉。这一轮把"行高之外那一笔到底在哪"往前推了三步：

1. **不在页顶。** `py tools/page-height-split.py -Capture artifacts/agent-layout-verify/265-release`：我们每一页第一行的
   line top 正好落在版心上边界 143.60 px，Word 的是 144.00~146.73 px，也就是我们比 Word **还早** 0.4~3.1 px 起行。
2. **不在版心。** Word 自己吐回来的 SECTION 行 `top=107.7pt bottom=85.05pt` = 143.60 / 113.40 px，与我们
   `page-geo.tsv` 的 `marginTop/marginBottom` 一字不差，两边都是 865.53 px 的版心。
3. **按页号硬对不可比，必须按内容对。** 同一个页号两边装的东西不同：Word 第 11 页第一行文字在 562.40 px（上面压着一张图），
   我们那一页第 1 行在 165.60 px。`tools/page-height-split.py` 因此只用来量"每页第一行/版心/末行余量"这类不跨页的量；
   跨页的对账走 `tools/height-debt-fit.py`（按文本把两边段落对齐，再解 `delta = a*行数 + b*段数`）。
   这台量台目前对到 105~152 段、残差中位 10.85 px（p90 26.23 px）——图的位置两边不同会把残差抬高，**还不够定案**，
   下一轮要先把图/表占的垂直高度单独摘出来再解。
4. **Word 在同一族声明下给两种行高。** 打开开关后剩下的 19 行尾巴全是 +0.934 px：`sz12 line300 snap=true` 有汉字
   16 行（设备块 62、68、74、88、95、98、109、149…）Word 给 25.333 px、我们给 26.27 px；`sz12 line276` Latin-only
   4 行 Word 23.533 / 我们 24.17（+0.633）。也就是说"一个常数比值"追不上 Word 的两档行高（第 13 节第 1 条记过西文那两档，
   这是中文这一档的第二处）。要真过 0.50 px 那条线，得按行取脸与两档判据，判据从真值来，不许在这张表里调常数。

### 29.6 下一轮（按页数 > 断点 > 行高的顺序）

1. `cjk|cjk` 36 处 + 拉丁数字那 7 处 = 同一个宽度问题：我们的行少装 1~4 个字。先量死一行到底装几个整宽字
   （`tools/width-bill.py` 已经给出"123 行里 3 行装不下"这个量级，说明大头不在逐字宽度上，得去查行末的撑缝与
   自动空隙是不是又收了一遍）。
2. `/` 不可断、`-` 可断这两条规则要落地：平台不接受我们递断行表（第 24.3 节），只能像第 24 节那样在 span 摆放上做，
   目标 3 处；动它必须同时盯第 6 条（右边界）。
3. 行高改成按行一个值，并把图/表占的垂直高度从对账里摘干净，再解一次 `height-debt-fit`。

### 29.7 "少装一个字"还是"断在别处"：把这 43 处拆开量（新增量台 `tools/line-capacity.py`）

```
py tools/line-capacity.py -Capture artifacts/agent-layout-verify/265-release
lines compared 43   每行字符数 我们减 Word：中位 -1.00  最少 -4  最多 +2
  我们少装：24 行    字数一样但断点不同：17 行    我们多装：2 行
Word 在这些行上的逐字墨迹步长：中位 15.302 px（12 pt 中文自然 advance 16.00 px、版心 566.93 px）
```

同一份 Word 导出 PDF、同一套段落匹配（`line-capacity.py` 直接 import `break-agreement.py` 的读取器，不重写第二套），
取"我们多断"那一侧的 43 行（`cjk|cjk 36` + 拉丁/数字交界 7），逐行数字数：

1. **24 行是真少装**（中位少 1 个字，最多少 4 个）——属宽度模型，与第 29.2 节那 7 处拉丁/数字同一件事。
2. **17 行两边字数一样，只是断在别的字符上**——这不是容量问题，是断点选择：同一串拉丁/数字挂在行尾还是行首。
   实例（第 29.3 节的 para 86）：Word 那一行 `...P-Cu/SB/Cu | 夹`，我们 `...B/P-Cu/SB/ | Cu夹`，
   字数一样，差在 `Cu` 归到哪一边。这一族跟第 29.2 节的"斜杠不是 Word 的断点"是同一条规则，
   **不需要动宽度模型就能拿下来**，而且是 17 处，不是 3 处。
3. Word 在这些行上的逐字步长中位 15.302 px 是两端对齐撑开之后的墨迹步长（撑开的行会高于 16.00），
   这一列只用来证明"Word 的行确实是被撑满的"，不用来对字库 advance 表。

所以下一轮的顺序改了：**先做断点选择那一族（17 处：斜杠不可断 + 拉丁/数字整块归行首还是行尾）**，
再动宽度模型（24 处 + 拉丁/数字那 7 处）。页数 > 断点 > 行高这条不动，改哪一族都要先把第 1 条与第 6 条复采贴上来。

## 30. 斜杠不是断点、串内连字符是连接符（改后 tag `slashglue2`，改前基线 `265-release`）

用户最早那两条真机抱怨里的一条——第 8 页 `Cu/SB/P-Cu/SB/C` 换行后下一行行首是 `u 夹层结构`（把 `Cu` 拆成 `C|u`）——本轮处理掉了。改的是"允许在哪些位置断行"，不是行宽，也不是行高。

### 30.1 Word 真值：`/` 不是断点，只有"整串比一行还宽"才必须拆

两条命令，都不开 Word 会话，读的是仓库里那份 Word 自己导出的 PDF（sha 钉在 `tools/break-agreement.py`）。

1. `py tools/break-seam-census.py`：Word 114 段、336 个行边界里，行尾正好是 `/` 的 **0 次**；我们 210 块、390 个行边界里 **3 次**（`blk 87` p8 `P-Cu/SB/|Cu夹层`、`blk 95` p10 `对NPC/|SAC305`、`blk 114` p13 `uO/NaCl/|Ag体系`）。"没出现"本身不算证据：从没坐到断点位置上的字符从没被考过，所以再量第 2 条。
2. `py tools/slash-break-truth.py`：给 Word 每一行算出它还剩多少空位，再给下一行开头的内容标价（到斜杠为止 / 整串），只有"到斜杠放得下、整串放不下"才是 Word 真做过的一次选择。结果：下一行含 `/` 的 21 行里 **6 行是这种决定性行**，反例 **0 行**。

   | 段 | 页/行 | 行内剩余 | 到斜杠 | 整串 | Word 实际怎么断 |
   | --- | --- | --- | --- | --- | --- |
   | para 94 | p10 第 1 行 | 77.87 px | 36.00 px | 300.00 px | 整串 `NPC/SAC305` 挪到下一行 |
   | para 113 | p13 第 1 行 | 78.87 px | 35.00 px | 173.00 px | 整串 `CuO/NaCl/Ag` 挪到下一行 |
   | para 357 | p27 第 1 行 | 63.86 px | 23.00 px | 413.00 px | 整串 `Cu/Sn/Ag` 挪到下一行 |
   | para 358 | p27 第 1 行 | 109.86 px | 99.00 px | 169.00 px | 同上（英文题名行） |
   | para 360 | p27 第 2 行 | 96.87 px | 60.00 px | 156.00 px | `SAC305/CuSolderJ…` 整串挪下 |
   | para 371 | p28 第 1 行 | 114.86 px | 98.00 px | 168.00 px | 同上 |

3. 同一条命令的后半张表（`-Tokens`，默认开）：把文档里同时含 `/` 和 `-` 的串逐个问"Word 有没有整串留在同一行"。正文那 5 个串（`Cu/SB/P-Cu/SB/Cu`、`Cu-Sn/Ag-Sn`、`Cu/Sn-58Bi`、`Cu/Cu-Sn`、`Cu-SnTLP/TLPS`，共 9 处）**全部整串待在 Word 的一行里，0 处拆开**；剩下 5 个是参考文献那种整段不带空格的长串（`RapidEquiaxedCu3SnFormation…` 等），比一行还宽，Word 全拆了——拆点 4 处落在字母之间、1 处落在连字符之后（`…gh-Temperature | ReliabilityofT…`）。所以规则的边界是"整串放得下就整串待下；整串比一行还宽才拆"，不是"永远不许拆"。
4. 连字符自己**不算**串内连接符：Word 行尾连字符 2 次（para 337 p26 `SiCHigh-|Temperat`、para 349 p26 `(5):727-|741.`），这两串里都没有 `/`。

### 30.2 平台只给了一根杠杆：测量段的边界

`pwsh tools/breakiterator-probe.ps1 -Probe TokenBreakProbe`（真机，`ro.build.version.sdk=29`）：

- `StaticLayout.Builder` 上与断行有关的公开 setter 只有 `setBreakStrategy` 一个；`setCustomSpans` 在 sdk 29 不存在，`android.text.style.CustomSpan` 在这台 ROM 上连类都解析不了（`ClassNotFoundException`）；`setBreakIterator` 反射拿不到（第 24.3 节）。**"把我们的断行规则交给平台"这条路是死的。**
- 能给的是 `ReplacementSpan`：测量段不可分。给串套上它，段 86 / 94 / 113 在 520..575 px 共 56 个版心宽度上，"行尾正好在斜杠之后"从 16 / 11 / 34 个降到 **0 个**，而整段墨迹宽度一字不差（3336.0 / 3336.0、3912.0 / 3912.0、1114.0 / 1114.0 px）——只换断点，不改宽度。
- 必须留宽度上限的证据（case `slash-long`）：一个 126 字的串套上 span 后在 567 px 版心里排成 **一行 1119 px**。套 span 不能比它去掉的那个断行更糟，所以整串宽过版心就不套。
- 连字符：56 个宽度、全部用例上"行尾正好在 `-` 之后"**0 次**。也就是说 Word 那 2 处连字符断行在 API 29 上给不出来——平台没有这根杠杆，这一档我们是量死而不是没做。

### 30.3 引擎改了什么（`DocxTextLayout.java`，方法名一份）

- `measure(...)`：`build(...)` 之前加一行 `if (text.length() > 0) glueSlashRuns(text, paint, width);`。
- 新增 `slashAtomicRuns(CharSequence, int columnPx, RunWidth)`（挑出该整串待下的区间）、`glueSlashRuns(...)`（套 span）、`uniformRun(...)`（区间内度量/绘制状态不一致就不套）、`AtomicRunSpan`（`getSize` 用逐字 `Math.round(measureText)` 之和，与手机"每个 advance 取整到整像素"的口径一致，见第 21.4 节；`draw` 就是一次普通 `drawText`）、`isJoiner`、`isLatinOrDigit`、`at`、`RunWidth`。
- `ownsReplacedRange(...)`：`AutoGap` 与 `AtomicRunSpan` 都不算"别人占了这一格"。不放行的话 `gapOffsets()` 会把两端对齐的缝隙从拉丁串上撤走，第 6 条立刻退（那正是第 24.4 节 para 160 少撑 1.53 px 的同一条路径）。
- 三条边界（都写进注释与断言）：只有左右都是字母数字的 `/` 才算——文档 77 个斜杠里 3 个不算（`（IMCs）/Cu`、`助焊膏//深圳` ×2），我们本来也不在那儿断；区间不许以 `/` 或 `-` 开头或结尾（span 边界本身就是断点）；宽过版心不套。`化学镀Ni/浸Au`（右边是汉字）保留断点，作为未决记录钉在断言里。
- `uniformRun` 挡的是"用一支画笔、一次 `drawText` 画整串"会画错的情形：字号（`PointSizeSpan`）、字脸（`FontSpan` / `MeasuredFontSpan`）、颜色、下划线、删除线、上下标/`w:position` 任一不一致就放弃。

断言：`tests/SlashAtomicRegression.java`（`pwsh tools/run-suites-private.ps1 -Suite SlashAtomicRegression`，已注册进 `tools/test-host.ps1`）——第 8 页那串必须整串成一区间、`Cu/Sn-58Bi` 等四个串整串、`（IMCs）/Cu` 与 `助焊膏//深圳` 不许动、`SiCHigh-Temperature` 与 `727-741` 必须仍可断、宽过版心的串必须放弃、`Cu6Sn5/Cu3Sn` 整串。全 40 项主机测试 `failed=0`。

### 30.4 真机上看得见的三处变化

`artifacts/agent-layout-verify/slashglue2/new/lines-all.tsv`（同一篇 `input-liu.docx`）：

| 页 | 改前（2.6.5 装机包） | 改后 |
| --- | --- | --- |
| p8 | `…Cu/SB/` \| `Cu夹层结构…`（`Cu` 被拆） | `…（P-Cu）构建` \| `Cu/SB/P-Cu/SB/Cu夹层结构…`（整串挪下，Word 同规则） |
| p10 | `对NPC/` \| `SAC305…` | `…制备的研究），对` \| `NPC/SAC305…` |
| p13 | `uO/NaCl/` \| `Ag体系…` | `…尚需明确。需要建` \| `CuO/NaCl/Ag体系…` |

行首字符普查：我们 600 行里以 `-` 或 `/` 开头的行数 2.6.5 = 0、只给 `/` 不加连字符连接的第一版 = **1**（p8 那行 `-Cu/SB/Cu`，所以连字符必须一起并进来）、本版 = **0**。Word 那 803 行里以 `-` 开头的 28 行全是页码 `- 1 -`，正文 0 行。

### 30.5 七条复采（`pwsh tools/parity-six.ps1 -Tag slashglue2`）

| # | 改前 `265-release` | 改后 `slashglue2` | 判定 |
| --- | --- | --- | --- |
| 1 页归属 | 7 段错页 / 206（全 -1，para 121 起），28/28 页 | 同左，一字不差 | 不退 |
| 2 行高中位 | -0.767 px | -0.767 px | 一字不差 |
| 3 行高 p90 | 2.533 px | 2.533 px | 一字不差 |
| 4 每页高度 | 0.79 行 | 0.79 行 | 一字不差 |
| 5 旧口径换行点 | 115/165 = 69.7% | 117/165 = 70.9% | +2 行 |
| 6 右边界越线 | 1 / 33 | 1 / 35 | 越线的还是同一行（para 160 第 14 行 563.00 对 564.53，差 1.53 px）；多的 2 行是新配对上的行，都在 1 px 内 |
| 7 断点一致率 | 227/353 = 64.3%（102 段） | 230/366 = 62.8%（103 段） | 见下面分段说明 |

第 7 条为什么分子涨了、百分比反而降：`py tools/break-common.py artifacts/agent-layout-verify/265-release/break-agreement.tsv artifacts/agent-layout-verify/slashglue2/break-agreement.tsv` 打得出来——只有两段动了。para 113：`0 一致 / 2 我们多断 / 2 Word 多断` → `2 / 0 / 0`，这一族被修掉；para 94 以前**不在池子里**（`tools/break-agreement.py` 要把 Word 的行和我们的行配对才计数），本轮行数与 Word 一样了才进来，带进 `1 / 7 / 7`，那 7 处是"每行少装字"（30.6 节），不是本轮改出来的。**两次都进池的 102 段单独算：64.3% → 65.2%（229/351，我们多断 63 → 61、Word 多断 63 → 61）**。这条口径以后就交给 `tools/break-common.py`，别再手算。

同一份真值算出的页归属错页数 6 段 → 6 段（`break-agreement.py` 输出行 `page assignment from this same truth file`）。

### 30.6 下一轮的靶子：每行少装字（本轮只量，没动）

para 86 六行的断点与 Word 的断点分别是 `34|50`、`78|88`、`121|132`、`164|173`、`201|211`、`236|246`（非空白字符偏移，`slashglue2/break-agreement.tsv` 里 wp86 那 12 行）——我们每行少装 16/10/11/9/10/10 个字。这是宽度，不是行高。已有的量法给出的口径是：`py tools/width-bill.py` 在 76 条含拉丁的非齐行上算出的多收是**中位 -1.200 px、均值 +0.859 px，只有 3/123 行因为取整少装一个字**；缝隙单价按最小二乘（强制过原点）解出来：`cjk>latin` 我们收 4.000 px / Word 3.650 px、`latin>cjk` 4.000 / 5.320、`digit>cjk` 4.000 / 5.353、`cjk>digit` 4.000 / 3.215，解出来的残差中位 -0.040 px、p90 6.496 px、最大 12.624 px。

也就是说整篇的平均数能对上，但残差 p90 6.5 px 说明还有一笔没进模型的宽度；而 `width-bill.py` 现在只量非齐行（齐行里 Word 自己拉伸过字距，不能直接读），para 86 那六行全是两端对齐行，不在它的样本里。下一轮先把 `width-bill.py` 扩到能处理齐行（用 hmtx 和而不是 PDF 上的实测间距），再谈动 advance 口径。线性度量（`setLinearText(true)`）**不许单独上线**：第 23 节量过，单独开它第 5、6 条一起退。

行高 p90 = 2.533 px 的归因仍在第 28.6 节那 12 行、两类声明上，本轮 `WordLineHeights.APPLIED_TO_LAYOUT` 保持 false，没有动任何行高代码。


## 31. 宽度这台秤本身有两处错；修完之后“每行少装字”落到整像素取整上

第 30.6 节留下一个对不上的数：para 86 每行比 Word 少装 16/10/11/9/10/10 个字，而 `py tools/width-bill.py` 说整像素取整全篇只让 3/123 行少装一个字。为了搞清是哪一个在说谎，把这台秤拆开量，结果它自己有两处错。

### 31.1 两处错

1. **每个中西文缝隙收了两次**。`bill()` 对字符 i 同时判“与左边是不是缝隙”“与右边是不是缝隙”，于是一道边界在左右两个字符上各收 4 px，一共 8 px。引擎不是这么做：`applyAutoSpace` 把缝隙挂在汉字那一侧，同一个汉字左右都是西文时两个缝隙合进一个 span（`before |= has.before`），一道边界只收 4 px。现在 `seams_on()` 只在汉字那一侧收。
2. **空格按中文字库量**。`face_of()` 用 PDF 报的字脸给空格定价，而 PDF 里空格报的字脸常常是前一个汉字的宋体，于是收 8.00 px（0.5 em）。引擎按字符挑脸：空格 `char < 128` 走 `w:ascii` 那格，拿到的是西文脸，Times 的空格是 3.23 px（0.202 em）。每个这样的空格我们多报 4.77 px。

两处都只改 `tools/width-bill.py`，引擎一个字没动（引擎本来就按上面两条做）。修前修后同一个命令的对照（`py tools/width-bill.py -Top 3`；真值是 Word 自己导出的 PDF 里那 123 条没被拉伸的行，它们的行宽就是画上去了多少）：

| 模型 | 修前平均/行 | 修后平均/行 | 修前 p90 差 | 修后 p90 差 | 修前少装字行数 | 修后 |
| --- | --- | --- | --- | --- | --- | --- |
| round（引擎现在：每个 advance 取整到整像素） | +0.522 px | -1.331 px | 9.445 px | **6.496 px** | 3 | **0** |
| linear（精确浮点 advance） | +2.106 px | +0.191 px | 9.045 px | **0.778 px** | 3 | **0** |

缝隙单价也跟着回到原位：修前那台最小二乘说“我们收 4.000 px、Word 收 3.215~5.353 px”，修后是 **Word 收 -0.785 ~ +1.353 px**，也就是在 ±1.4 px 以内与我们的 4.000 px 一致——中西文缝隙的尺寸这一项已经没有可省的钱（同一命令最后一段）。依赖 `width-bill.py` 的 `tools/slash-break-truth.py` 不受影响，重跑仍是 21 行下一行含斜杠 / 6 行决定性 / 15 行没位置 / 反例 0，六个数一字不差（它自己内联算价，不走 `bill()`）。

### 31.2 新量的一档：两端对齐行不能再读 x 坐标

齐行被 Word 拉伸过，x 坐标里含着它加的字距，不能当行宽。所以 `width-bill.py` 新加一档（默认开，`-Justified 0` 关）：只读“Word 把哪些字放在这一行”，把同一串字用 hmtx 标两次价（精确 / 整像素），再和这一行**实际有的宽度**（版心右边减去这一行第一个字的 x，首行缩进因此自动算进去）比。若精确价放得下而整像素价放不下，这一行就少装字。

结果：366 条齐行上，我们相对精确 advance 每行多收 **中位 0.000 px / p90 2.565 px / 最大 2.672 px**；“精确放得下而我们放不下”只有 **3 条**，差额分别是 1.47 px（para 72 p5）、0.47 px（para 160 p19）、0.47 px（para 194 p21）。

### 31.3 para 86 重算：1.47 px 的短缺被整串放大成 100 px

| 行 | 这一行实际有的宽度 | 精确价 | 我们的价 | 差额 |
| --- | --- | --- | --- | --- |
| 1 | 534.53 px | 535.65 | **536.00** | +1.47 px |
| 2 | 566.53 px | 565.77 | 566.00 | 放得下 |
| 3 | 566.53 px | 566.90 | 565.00 | 放得下 |
| 4 | 566.53 px | 554.37 | 552.00 | 放得下 |
| 5 | 566.53 px | 555.56 | 555.00 | 放得下 |

模型修准之后，para 86 只剩第 1 行差 1.47 px。而那一行行尾正好是本轮刚变成整串的 `Cu/SB/P-Cu/SB/Cu`（约 100 px）——差 1.47 px 就得把整串挪到下一行，这就是“每行少装十几个字”的放大机制。第 6 条右边界那一行（para 160）在这一档里也露出同一张脸：0.47 px 的短缺，和它少撑 1.53 px 是同一件事的两头。

### 31.4 下一轮按哪两个数排序

- **取整是每行宽度误差的主项**：同一条命令下 p90 差是 6.496 px（整像素）对 **0.778 px**（精确 advance），平均 -1.331 px 对 +0.191 px。这和第 23 节“40 个 m 量成 480.00 px 而 Word 是 497.81 px”是同一件事，现在有了整篇的分布。线性度量仍然不许单独上线（第 23 节：单独开它第 5、6 条一起退）。
- **缝隙尺寸不用再动**：修完这台秤之后，Word 在中西文缝隙上收的钱与我们的 4.000 px 在 ±1.4 px 内一致。本轮原本排第一的“autoSpace 宽度多计”，量下来不存在。
- **动手之前还缺一台秤**：上面算的都是“hmtx 应该是多少”，不是“手机这次量到多少”。采样文件里的 `lineWidthPx` 是拉伸之后的值，不能当证据（para 86 第 1 行拉伸后是 567.0，拉伸前才是 536 这一档）。下一轮先给探针加一列“引擎在拉伸前给每一行量的宽度”，与 31.3 那张表逐行对上，再决定动哪一行代码。


## 32. 上下标：行高那条尾巴与它无关，咬住的是「串内断」（改前 `sup-base`，改后 `sup-fix3`）

取样口径写进工具：`py tools/superscript-attribution.py -Capture artifacts/agent-layout-verify/sup-fix3 -Top 12`，明细 `superscript-lines-join.tsv`。凡某一行的字符区间落进 `w:vertAlign` run（或 Unicode 上下标字形）就算含上下标行。第 7 项那份 Word 导出 PDF 的 392 条行里含上下标行 31 条（上标 20 / 下标 11），内容最多的是 `3`×9、`6`×4、`5`×4、`3Sn`×4、`[2]`/`[14]`/`[16]`/`[19]` 各 2。

**行高**：以本段无上标行的中位为基准，我方把含上下标行顶高超过 0.4 px 的行数 **0/31**（Word 同样是 0.000）；含上下标行的我方-Word 误差 med -0.920 px、绝对值 p90 2.920 px，与「本段无上标」那些行同档。所以第 3 项那条 p90 2.533 px 的尾巴不在上下标上，仍属 `sz12 line300 snap=false` 那一队（第 28 节）。

**断点**：124 处分歧里 14 分落在含上下标行，8 个段落的首个分歧就在含上下标行；含上下标行的早断率 16.1%，无上标行 12.2%。贴到行的样本：para 62 第 3 行（上标 `[2]`，距分歧 0 字）、para 68 第 3/5 行（`[12]`/`[14]`）、para 105 第 2 行（`[19]`，距 4~5 字）、para 159 第 3~4 行（下标 `3`）。

**唯一一条真正的串内断**：第 19 页、docx 第 159 段第 3 行 `、孔洞率、Ag3 | Sn分布`，断点正落在下标 run `[153,154)=3` 与它后面那个 run 的边界上。真机 `ScriptBreakProbe` 的形状：span 只盖住 `3` 时在 32/33 px 两个宽度都会断；整串一个 span 时八个宽度全不断。改法是让整串共用一个容器（`ScriptTokenSpan`）：宽度整串一次取整，`getSize` 只报基准字体的行度盒。改后串内断 1 → 0，串内可断的 span 边 67 → 2（只剩 para 160 的 `R²`、`x²`：那个平方号与字母落在不同的符号字体，样式指纹不一致，按准入条件不并，实测也从没在那两处断过），容器 33 个。

**七项前后**（同一台 CDY-AN90，手机上跑的是各自那棵树的引擎采样，不是手机上装的发布包）：页数 28 对 28；段落页归属 7 段错页/206；行高误差中位 -0.767 px；p90 2.533 px；每页累计 0.79 行；右边界超 1 px 的行 1/33；逐行换行点 115/165 → 116/165；第 7 项 227/353 = 64.3% → 228/352 = 64.8%（多断 63 → 62，少断 63 → 62）。两合一（本改动 + 第 30 节斜杠）的读数由发布包另采，记在 `tools/parity-six.ps1` 的 tag 里。

## 33. 行末那个空格占不占宽：量到 px，否掉"行末空格不计宽"，并把 Word 真正多出来的那 16 px 定位到悬挂（改前 tag `tailfit1`，合并 main 后 `postmerge1`）

一句话：那个空格收 4.00 px，是真的；但把"行末空格不计宽"改下去，第 7 项从对上 55 处掉到对上 46 处，
所以不改。para 94 那一行 Word 真正多出来的是把收尾全角逗号整张脸画到版心外（+16.0 px），我们的悬挂
只认下一行头两个字，提不动 `MPa，` 里的逗号。

### 33.1 采样台加的两列与一台对照（都进了版本库）

`tools/device-probe/DeviceCapture.java` 在 `lines-all.tsv` 末尾追加两列（老列位置一字不动）：

- `lineTailChars`：这一行的行范围内、被换行留在行末的空白字符个数（数原始字符，不看 `clean()` 之后的文本）。
- `lineTailFree`：这些空白有没有被 `DocxTextLayout.BlankTail` 盖住。1 = 不计宽（引擎的本意），0 = 仍算在行宽里。

为什么非要在真机量：`BlankTail` 只在两端对齐那一趟里挂上文本，而那一趟在整段一行都拉不开时会把整份结果丢掉
（`DocxTextLayout` 的 `spreadText`：`if (widened == 0) return laidOut;`）——于是一整段的行末空白都还在计费。
block 95 正是这种段：9 行 `lineStretchPx` 全是 0.00。

另两台：

- `chars-tail.tsv`：每行末尾 9 个码元逐条列出码位、x、advance、以及盖在它身上的 span 名字（`AutoGap(before,after)`
  / `WidenGap(extra)` / `BlankTail` / `AtomicRunSpan`）。"行末那 4 px 是空格还是 autoSpace 缝"这种问题只有这一张表能答。
- `tail-fit.tsv` + `tools/tail-fit.py`：把每个"换行在行末留了空格"的段落重排一遍（`which=relay`：两端对齐的拉伸去掉、
  行末空格改计 0 宽，glue 保留），与 `which=live` 并列输出，再用 Word 导出 PDF 的断点给两版打分。
  用法：`py tools/tail-fit.py -Capture artifacts/agent-layout-verify/tailfit1 -Tsv <out.tsv>`。

`lineInkPx` / `lineTailPx` 这两列同时改成不再把平台的拒绝当成宽度：`getPrimaryHorizontal` 在被我们自己的 span
拉伸过的行上有时回答 -1、有时回答 0，原来这两种回答都被当成坐标减出过"墨迹 0 px"。现在改成单调行走、拿不到就报 -1。

### 33.2 para 94 第 2 行（手机 block 95 第 1 行，第 10 页）的全部数字

| 量 | 数 | 命令 |
| --- | --- | --- |
| 我们这一行的拉伸前行宽 | 524.00 px（版心 566.93 px，剩余 43.00 px） | 读 `postmerge1/new/lines-all.tsv` 里 paragraph=95、line=1 那一行（`py tools/line-room.py -Capture artifacts/agent-layout-verify/tailfit1` 的 para 94 行同源） |
| 行内墨迹终点 | 520.00 px | 同上，`lineInkPx` |
| 行末空格 | `lineTailChars=1`、`lineTailFree=0`、`lineTailPx=4.00` | 同上 |
| 那 4 px 是空格还是 autoSpace 缝 | 是空格：`chars-tail.tsv` 里 block 95 line 1 最后一个码元是 `U+0020`，x=520.00，身上没有 `AutoGap` | 读 `tailfit1/new/chars-tail.tsv` |
| 下一行开头那一串 | `M`（14.00 px），未粘成整串；`MPa，` 单价 14+9+7+16 = 46.00 px | `nextRunChars` / `chars-at-punct.tsv` block 95 line 2 |
| Word 这一行 | `…剪切强度为37.68 MPa，`，43 字，断点在偏移 74（我们在 70，早 4 字） | `py tools/break-agreement.py -Capture artifacts/agent-layout-verify/postmerge1` |
| Word 这一行的墨迹右端 | 696.5 px，最后一张 `，` 的 bbox 宽 16.00 px、x1=696.5 | pymupdf rawdict，`artifacts/agent-typeset/pdf-truth/input-liu.pdf` 第 10 页 |
| 版心右边界 | 680.32 px（510.26 pt） | 同上 |

两个假设都能把这一行修好，但只有一个能全篇用：

1. 行末空格不计宽：520.00 + 46.00 = 566.00 ≤ 566.93，正好塞进（真机 `relay` 重排实测这一行 566.00 px、剩 1.00 px、
   断点 74 = Word 的断点）。
2. 收尾全角标点允许悬挂：524.00 + 30.00 + 16.00 = 570.00，超出 566.93 只有 3.07 px，而 Word 自己在这行让逗号挂出
   16.06 px。

### 33.3 否掉"行末空格不计宽"（数字与命令）

`py tools/tail-fit.py -Capture artifacts/agent-layout-verify/tailfit1`：

- 全文 600 行里换行在行末留了空白的 68 行，**68 行全部在计费**（`lineTailFree=0`）。
- 逐行判"若空格不计宽，被拒的那一串就塞得下"：14 行。
- 段落级对账（只算能配上 Word 段落的 33 段，共 75 个 Word 断点）：**改前对上 55 处，改后对上 46 处**。
  变好的 1 段是 block 95（= para 94，1/8 → 2/8）；变坏的 7 段全在第 26~28 页的参考文献里
  （block 344 2/2 → 0/2、349 3/3 → 2/3、360 3/3 → 2/3、362 2/2 → 0/2、363 2/2 → 0/2、369 1/1 → 0/1、374 1/2 → 0/2），
  另外 24 段不变。
- 原因很直白：那 7 段是纯西文条目，Word 的断点正好落在"这个空格收钱"的位置上；空格一免费，我们每行多吃一个词。

所以这一改不动，结论写在这里免得下一轮再量一遍。

### 33.4 Word 真正多出来的那 16 px：我们的悬挂提行只走到下一行第 2 个字

`DocxTextLayout.hangTrailingPunctuation`（`DocxTextLayout.java:266-309`）只看下一行的第 1 个字（若是可悬挂标点）
或第 1、2 个字（第 1 个是普通汉字、第 2 个是可悬挂标点），且要求普通字本身仍在版心里。`MPa，` 的逗号在下一行
第 4 个字上，所以提不上来。全文 600 行我们的 `hangOverPx` 只有 8 行非零；Word 导出 PDF 里越界 11.42~12.44 pt 的
正文行有 20 行（`，×9 、×5 。×4 ；×2`，另有 `℃×3` 不是我们挂的那类）。也就是说差的那一小步是：把"拉丁/数字整串
+ 收尾标点"当成一个可提单元，整串塞进版心、只让标点越界。改之前先把第 6 条的判法记住：`tools/edge-parity.ps1`
比的是"我们挂出不超过 Word 自己那一行的挂出"，不是"谁都不许越界"。

### 33.5 两处与主控件说法对不上的地方（都给命令）

1. `431933f` 在这台仓库里查不到对象：`git cat-file -t 431933f` → `Not a valid object name`。能查到的 `main`
   是 `9e1fc6a`（含 2.6.8，`70d4d26`），把它并进 `agent-parity2`（`1fb8c4e`）后在手机上跑一遍：
   para 94 第 2 行仍是 **39 字、断点 70**，不是 44 字（`postmerge1`，engine head_sha `1fb8c4e8486485…`，
   与 `tailfit1` 的 `lines-all.tsv` 是同一份字节（两边 sha256 前 16 位都是 `EEE155468C638083`，`Get-FileHash -Algorithm SHA256`），说明 2.6.8 没有动这份稿子的排版）。
2. `Cu-Sn/TLP/10 min` 这一串：样稿与真值 PDF 里都搜不到——
   `py -c "…re.findall(w:t) …"` 全文 373 段没有 `TLP/10` 或 `Cu-Sn/TLP`；真值 PDF `get_text()` 28 页里也没有。
   真值 PDF 810 行正文里以 `/` 结尾的行 = **0**（只有第 22 页表格里三个只含 `/` 的单元格），
   我们 600 行里也是 0。也就是说"斜杠不是断点"（`e16b236`）与真值一致，这一条不动。

### 33.6 这一族真正剩下的那一处：block 87（para 86）整串挪走，空位还有 156 px

`postmerge1/new/run-fit.tsv` 第 1 行：粘住的串 `Cu/SB/P-Cu/SB/Cu` 自然宽 127.00 px，我们那一行末尾还剩
156.00 px（`run_would_fit=1`），glue 自己的报价 128.00 px 与普通 `measureText` 的 128.00 px 相同（不贵）；
把 glue 去掉重排，落点正是 Word 的 50 字那一行（`ctrl_line_end=50`、552.00 px、还剩 15.00 px），
而带 glue 的真机布局把这串整串挪到了下一行，本段第一个断点因此早 16 字，后面 2 行跟着错
（`py tools/line-room.py -Capture artifacts/agent-layout-verify/tailfit1` 里 para 86 那三行：1 行早断 + 2 行 knock-on）。
128 px 的东西在 156 px 空位前被挪走，原因还没量到；下一轮拿这一段文本做单段实验室，一次改一个开关。
其余 4 条 run-fit 行都是合理拒绝（run_px > room_px：block 95 92>86、114 93>75、361 64>7、364 79>53）。

### 33.7 七项

本轮未改引擎（三次采样的 `lines-all.tsv` 都是同一份字节 `EEE155468C638083`），七项在合并 main 之后的树上
重跑一遍（`pwsh tools/parity-six.ps1 -Tag merged268`，engine head_sha `03c6afd`、`layout_dirty=False`，
`pages.tsv` sha `C4C5DBA577EF336D`，字体表指纹 `AB3B592E304F`，28 页 / 14460 字）：

| # | 本轮读数 | 与第 30 节那一列 |
| --- | --- | --- |
| 1 | 段落页归属 7 段错页 / 206（exact 96.6%，页差直方图 -1=>7、0=>199，首个错页 para 121） | 一字不差 |
| 2 | 逐行行高误差中位 -0.767 px（n=91，Word 26.267 px） | 一字不差 |
| 3 | 逐行行高误差 p90 2.533 px（max 3.467） | 一字不差 |
| 4 | 每页累计高度误差 0.79 行（最差队列 sz12 line300 snap=false 2.55 行） | 一字不差 |
| 5 | 逐行换行点一致率 118/165 = 71.5% | 一字不差 |
| 6 | 右边界超出 1 px 的行数 1 / 35（还是 para 160 那一行，少 1.53 px） | 一字不差 |
| 7 | 断点一致率（Word 导出 PDF 尺子）231/365 = 63.3%（103 段，多断 67、少断 67；同一份真值算出错页 6 段） | 与 `postmerge1`、`tailfit1` 同一个数 |

也就是说这一轮的所有结论都是量出来的，没有任何一项指标因为量台改动而动过。

### 33.8 粘串的真实价格量出来了：block 87 那一串平台按 235 px 收，而 span 自己报 128 px

新量台 `glue-price.tsv`（`tools/device-probe/DeviceCapture.java` 的 `gluePrice`）：把粘住的串所在段一遍遍
重排，列宽从 500 px 加到 660 px，记下"这一串终于留在上一行"的最小列宽 `threshold_px`；这个门槛减掉上一行的
拉伸前行宽就是断行器真正用的价格。同时记下盖在串首字上的 `AtomicRunSpan` 个数、它的 `getSize` 报价、以及它的范围。

真机（tag `glue3`，engine head_sha `369085d`，与 postmerge1 的 `lines-all.tsv` 同一份字节
`eee155468c638083`，也就是本轮没改排版）：

| 页 | block | 串 | 串的自然宽 | 上一行还剩 | span 报价 | 覆盖数 | 门槛列宽 | 平台实收 | 判定 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 8 | 87 | `Cu/SB/P-Cu/SB/Cu` | 127.00 | 156.00 | 128.00 | 1 | 646 | **235.00** | 串该留在原行却被挪走 |
| 10 | 95 | `NPC/SAC305` | 92.00 | 86.00 | 80.00 | 1 | 573 | 92.00 | 合理拒绝（92 > 86） |
| 13 | 114 | `CuO/NaCl/Ag` | 93.00 | 75.00 | 88.00 | 1 | 660 | 168.00 | 拒绝本身合理（93 > 75），但价也虚高 |
| 27 | 361 | `Cu/Sn/Ag` | 64.00 | 7.00 | 64.00 | 1 | 624 | 64.00 | 报价与实收一致，合理拒绝 |
| 27 | 364 | `SAC305/Cu` | 79.00 | 53.00 | 72.00 | 1 | 580 | 66.00 | 合理拒绝 |

三条结论：

1. block 87 的拒绝是错的。串自己报 128.00 px、上一行还剩 156.00 px，去掉粘串重排的落点正是 Word 的 50 字行
   （`run-fit.tsv`：552.00 px、剩 15.00 px），可平台一直要列宽加到 646 px 才肯把这一串留在原行，等于按
   235.00 px 收。覆盖在串首字上的 `AtomicRunSpan` 只有 1 张，所以不是重复粘。
2. block 95 / 361 / 364 三处平台按串自己的报价收（92 / 64 / 66），拒得对，说明机制本身没错，错的是价格。
3. 下一轮的改法已经有靶子：布局之后做一次自检——某一行的行首是粘住的串、而它上一行的剩余宽度足够放下
   这串（按不粘时的实测宽算），就把那一张粘串去掉重排，且只有"其他行的断点一个都没动"才接受这次重排。
   本轮没有动引擎：这条改动要配 `tools/test-host.ps1` 全绿与七项复采，留给下一轮一次做完。
