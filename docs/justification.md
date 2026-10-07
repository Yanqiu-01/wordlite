# 两端对齐（`w:jc="both"`）与目录页码

版心与断行已经和 Word 逐行对齐之后，剩下的两件事：中文行的右边缘要真的落到右边界上，目录页码要压在声明的制表位上。两端对齐不是"换行的副产品"，它是换行之后的第二次分配，Word 也是这么分两步做的：先决定每行装哪些字符，再决定这一行的空隙怎么摊。本引擎同样保证第一步不被第二步影响。

## 平台在旧系统上给不了什么

真机 HONOR CDY-AN90 / Android 10 / API 29 实测（`tools/justify-device.ps1`、`artifacts/device/probe/DeviceJustify.java`）：

- 同一段纯中文，`JUSTIFICATION_MODE_NONE` / `INTER_WORD` / `INTER_CHARACTER` 三种模式的行宽都是 560.0。API 34 以下 `INTER_CHARACTER` 对中文完全不起作用，传 2 也不报错。
- `setLetterSpacing` 被量化到整像素：0.0125em、0.02em 行宽不动，0.05em 才 +1 px，亚像素拉伸没有出路。
- `U+200B` 不构成可拉伸缝隙。

所以 API 34 以下只能在应用层拉；API 34 及以上交回平台。拉丁词间的拉伸自始至终交回平台（`INTER_WORD`），因为 Word 对拉丁文本只拉词间空格。

## Word 的口径

真值由 Word 本体产出：`tools/word-justify-truth.ps1`，产物在 `artifacts/word-justify/`。

- 版心 425.2 pt = 566.93 px；12 pt 宋体的自然字距 pitch 12.1 pt。
- 每条非末行都被拉满：45 条 `justify-not-last` 行的右边缘相对版心偏 -3~+12 pt，末行一律短。没有"拉伸量太大就退回左对齐"这种行：那几条看起来 ragged 的行本身就是 `w:jc=left` 的段落。
- 加宽只落在与中文相邻的缝隙上（含中西文边界与部分标点缝），拉丁词内部的缝隙不动。一条线只开少数几条缝：34 条缝的行里中位数只有 3 条被拉开，缝位在行内均匀分布，字符数与缩进相同的两行，被拉开的缝索引集合完全一致（例如 [5, 15, 25]）。
- Word 的加宽有固定量子：Q = 0.575 pt = 11.5 twips = 0.767 文档像素，`k_total = round(slack / Q)`，18 条纯中文行全部命中；语料里每一条中文缝的宽度都落在 12.10 + k * 0.575 pt 这张网格上，最坏残差 0.050 pt，正好是 Word 自己的读数分辨率。单缝最大 +3.55 pt（中西文 1/4 em 缝）、+2.40 pt（汉字与汉字之间）。
- 中西文之间的 1/4 em 自动空位会被拉（`cjk>latin` 缝 100% 被加宽），中文标点与拉丁之间不拉（`cjkpunct>latin` 最宽只到自然值 +0.10 pt）；拉丁词内部的双向变化（16% 变宽 / 13% 变窄，中位 0.00）是成型噪声，不是对齐。
- 没有放弃拉伸的阈值：缺 59.20 pt（差 14.8% 才满行）的那条行，Word 照样填满到 -0.20 pt，把全部 28 条缝都拉开。行尾全角标点溢出到版心之外，它前面那条缝保持自然宽度。
- 完整推导与逐项数据见 `docs/word-justification-rule.md`，`py -3 tools/justify-rule.py` 可重算其中每一个数字。

## 实现

`DocxTextLayout`：

1. `justifyEastAsian`：仅当 `alignment == 3`、`SDK_INT < 34`、段内有中文时进入。逐行取 `slack = width - lineLeft - lineWidth`，不足 1 px 跳过；末行永不参与。
2. `spreadLine` + `gapOffsets`：可开缝隙 = 缝隙任一侧为中文（`isCjk` 含中文标点），拉丁词内部不开；中西文自动空位（`w:autoSpaceDE/DN` 产生的 `AutoGap`）同样可开，Word 实测这类缝 100% 被加宽；被上下标、下划线、删除线、底纹覆盖的字符一律不参与。一条线每隔 `MAX_GAP_STRETCH_STEP_PX = 2` px 开一条缝，缝位在候选缝里居中均匀取点，单缝上限 `MAX_GAP_STRETCH_PX = 6` 只是保险。被加宽的字符若正带着 `AutoGap`，先摘掉那个 span 再挂 `WidenGap`：同一区间挂两个 `ReplacementSpan`，旧系统的成型只拿其中一个计量，加宽会悄悄失效。
3. `WidenGap`：`ReplacementSpan`，`getSize` 返回 `basePx + extraPx`，`draw` 照常 `drawText`。关键是 `basePx` 取自 `getPrimaryHorizontal(i+1) - getPrimaryHorizontal(i)` 的**已排布**距离，不是重新 `measureText`：成型 run 的宽度并不等于逐字宽度之和，用后者会在拉缝的同时把断行点推走。只有本来就是整像素 advance 的字符才做候选。
4. 换行点安全网（`lineToClear` + `shrinkLine`）：重建后逐行比对 `getLineStart` 与是否越出列宽。被推走的断点，成因在它上一行，于是把那一行的空隙退让 1 px 重试；那一行已无空隙时退到最低一条还有空隙的行；退无可退才整段回落原布局。取舍很清楚：右缘短 1 px 没人看得出，断行点一动，分页和已经核对过的 Word 逐行对齐全部作废。
4b. 缝步长取 2 px 而不是 1 px 是实测取舍。Word 的量子 0.767 px 在 `ReplacementSpan` 只能报整像素的约束下无法直接表达，1 px 是最近的近似，但它要改动行内最多的字符，而每改动一个字符就多一次把断行点推走的机会：参考文档上 1 px 留下 16 条右缘缺 2 px 以上的行，2 px 留下 10 条。3 px（开缝条数与 Word 一致）在手机上读作句子中间的洞。2 px 合屏幕上约 2.3 个物理像素，读起来仍然是齐的。

5. 目录 `LeaderTab`：右对齐制表位按"页码**结束**在 `w:tab w:pos` 上"计算，`getSize` 里扣掉 tab 之后页码的宽度，点线只填到页码起始处。停止位与 Word 一样从左边距起算，与条目自身缩进无关，所以缩进的 `3.1.1` 与不缩进的 `1` 页码右缘在同一条线上。

## 实测（同一篇开题报告，真机 capture，真字形真字体）

| 指标 | 改前 | 改后 |
| --- | --- | --- |
| 正文非末行右缘相对版心的缺口，中位数 | 6.93 px | -0.07 px |
| 缺口超过 2 px 的行 | 183 / 266（68.8%） | 10 / 266（3.8%） |
| 最大缺口 | 23.9 px | 11.9 px |
| 目录页码右缘相对声明停止位 | +7.4 ~ +15.5 px（越过停止位） | -0.60 ~ -0.53 px |
| 与 Word 真值逐行比右缘，平均绝对偏差 | 6.81 px | 0.99 px（中位 0.20 px） |
| 落在右边界 1 px 以内的行 | 4 / 26（15.4%） | 32 / 32（100%） |
| 段落内右缘散布（标准差） | 2.69 px | 0.00 px |
| 页数 / 与 Word 对齐段落的错页数 | 28 页 / 7 段各差 1 页 | 28 页 / 7 段各差 1 页（未被改动） |

改前改后是同一篇文档、同一台手机、同一份 capture 流程。`docs/edge-parity-baseline.md` 是与 Word 真值逐行对齐的完整分布，含未匹配行数（31 / 23 条，全是单字断行差异，只计数不丢弃）。

## 复测命令

```
pwsh tools/capture-device.ps1 -Impls new    # 真机跑一遍引擎，真字形真字体
py -3 tools/justify-raggedness.py old new   # 文档坐标下的右缘缺口分布
pwsh tools/edge-parity.ps1 -Impl new        # 与 Word 真值逐行比右缘（含目录页码）
pwsh tools/word-parity.ps1 -Impl new        # 断行点与页数：必须仍 28 页、shifted <= 10
pwsh tools/test-host.ps1                    # JVM 侧回归
artifacts/host-tools/gradle-8.14.2/bin/gradle.bat -p tests/ui --no-daemon test
```
