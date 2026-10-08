# Line-height parity target (baseline-to-baseline)

Owned by the layout-parity sub-agent. Everything below is measured, with the command that produced it.
Nothing in `app/src/main/java` was changed.

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

| # | 指标 | 量法（真值来源） | 现在（HEAD，`artifacts/device-round3`） | 验收线 |
| --- | --- | --- | --- | --- |
| 1 | 段落页归属 | `tools/word-parity.ps1`（Word 28 页真值 `artifacts/word/pages.tsv`） | 7 段错页 / 206（exact 96.6%） | exact >= 99%，错页 <= 2 段 |
| 2 | 逐行行高误差中位 | `artifacts/agent-typeset/line-height-rows.ps1`（Word 相邻基线距离，COM） | -0.767 px | 绝对值 <= 0.10 px |
| 3 | 逐行行高误差 p90 | 同上 | 3.267 px | <= 0.50 px |
| 4 | 每页累计高度误差 | 同上 x 每页行数（Word 每页 27-35 行） | 0.80 行（最差队列 3.4 行） | <= 0.25 行 |
| 5 | 逐行换行点一致率 | `tools/line-break-delta.ps1 -Stage report` | 103/165 = 62.4% | >= 90% |
| 6 | 两端对齐右边界超出 1px 的行数 | `tools/edge-parity.ps1 -Impl new` | 0 / 32 | 0 / 32（守住，不许为了行高牺牲它） |

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
