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

| # | 指标 | 量法（真值来源） | HEAD 复采（tag `autospace1`，2026-10-09；engine head_sha `bdd2e6a`） | 验收线 |
| --- | --- | --- | --- | --- |
| 1 | 段落页归属 | `tools/word-parity.ps1`（Word 28 页真值 `artifacts/word/pages.tsv`） | 7 段错页 / 206（exact 96.6%，页差全是 -1，首个 para 121；28/28 页） | exact >= 99%，错页 <= 2 段 |
| 2 | 逐行行高误差中位 | `artifacts/agent-typeset/line-height-rows.ps1`（Word 相邻基线距离，COM） | -0.767 px（n=91，Word 26.267 px） | 绝对值 <= 0.10 px |
| 3 | 逐行行高误差 p90 | 同上（取 \|误差\| 的 p90） | 2.533 px（max 3.467） | <= 0.50 px |
| 4 | 每页累计高度误差 | 同上 x 每页行数（Word 每页 27-35 行） | 0.79 行（最差队列 sz12 line300 snap=false 2.55 行） | <= 0.25 行 |
| 5 | 逐行换行点一致率 | `tools/line-break-delta.ps1 -Stage report` | 114/165 = 69.1%（`autospace1`，改前 103/165 = 62.4%） | >= 90% |
| 6 | 两端对齐右边界超出 1px 的行数 | `tools/edge-parity.ps1 -Impl new` | 0 / 32 | 0 / 32（守住，不许为了行高牺牲它） |

一条命令复采这六条：`pwsh tools/parity-six.ps1 -Tag <tag>`（真机采样 + 行高探针 + 三条对比，只调已有脚本、不重新定义量法；Word 真值走缓存，不重开 Word 会话；结果写 `<tag>/six.tsv`、`<tag>/six.txt`，带 head_sha 与 `lines-all.tsv` 的 sha）。第 0 节的六个数每一轮都以这条命令的输出为准，第 17 节记下本轮的复采与指纹。

本轮落到的 HEAD 是 tag `autospace1`（第 20 节）：`w:autoSpaceDE` / `w:autoSpaceDN` 的缺省从"没写=关"改成 Word 的"没写=开"，第 5 条 62.4% → 69.1%，其余五条一字不差（`pages.tsv` sha `921A8FAB4D3D09F8` 不变、右边界仍 0/32、`lines-all.tsv` sha `D9822E2C3396D6EC` → `AC44D355853F4616`）。

再后一轮（第 21 节，U+207B 上下标单位那条线索）没有动引擎：量完确认这个字符两边用的是同一张随包 Times New Roman、advance 也一致（0.3384 em 对 Word 的 0.338 em），所以六个数与上一行完全相同，新增的只是量台 `tools/font-advance-audit.py`（逐字把 Word 导出 PDF 的脸/advance 与我们随包字库对账）。

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

