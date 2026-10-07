# The CJK justification rule Microsoft Word actually applies

Measured from the captured Word layout in `artifacts/word-justify/` (65 lines, 11 paragraphs,
one body size: 12 pt CJK with Latin/digits mixed in). Every number below is printed by

    py -3 tools/justify-rule.py

which reads only the TSVs. Section references (`s5b`) point at that script's output.

Corpus: 45 `justify-not-last` lines, 9 last lines of justified paragraphs, 11 left-aligned lines,
2378 inter-character seams. Body column = 425.2 pt = 566.93 document px at 96 dpi. Positions are
Word's `Range.Information(wdHorizontalPositionRelativeToTextBoundary)`, reported on a 0.05 pt
(1 twip) grid; the worst off-grid gap in the corpus is 0.0000 pt (s5).

## The rule

```
justify(line):                                   # paragraph alignment = justify, and the line is
    if is_last_line_of_paragraph: return         # NOT the paragraph's last line (s1)
    n      = characters on the line              # a trailing space is not part of the width
    avail  = column_width - indent               # 425.2 pt body column here
    cell   = 1.00833 * size                      # Word's modal CJK cell: 12.10 pt at 12 pt
    if last_char is CJK punctuation and the line overfills by about one cell:
        avail += cell                            # w:overflowPunct: the mark sits outside the column
    natural  = n * cell + autospace_seams        # see below
    k_total  = round((avail - natural) / Q)      # Q = 0.0479 * size = 0.575 pt = 11.5 twips
    for i in 0 .. n-2:                           # the seam in front of the last cell is left alone
        k_i   = round((i+1) * k_total / (n-1)) - round(i * k_total / (n-1))   # even dither, Bresenham
        gap_i = round_to_0.05pt(cell + k_i * Q + autospace(i))
    autospace(i) = 0.24 * size (measured 2.85..2.90 pt) when the seam joins a Han ideograph and a
                   Latin letter or digit in either order, 0 when either side is CJK punctuation
    seams inside a Latin word never get a k_i; explicit spaces do (s6)
    no lower bound on slack: a line 14.8% short still justifies to the edge (s7)
```

The line always lands on the right edge; the work is done by lifting a few seams by whole
multiples of one quantum (0.575 pt = 0.767 document px), spread evenly from end to end. Nothing is
stretched continuously, and the seams at either end of the line are the least likely to move.

## 1. Only non-final lines of a justified paragraph, and they land exactly (question 1)

Right edge = x(last character) + that character's natural advance. The capture script adds the last
gap instead, which over-counts whenever the last seam was widened, so the numbers here are
recomputed from `chars.tsv`.

| kind | n | min | median | max |
|---|---|---|---|---|
| `justify-not-last` | 42 | -0.20 pt (-0.27 px) | -0.20 pt | +11.90 pt |
| `justify-LAST-line` | 9 | -299.05 pt | -65.30 pt | -20.95 pt |
| left-aligned | 8 | -382.55 pt | -5.95 pt | -5.40 pt |

39 of the 42 justified lines sit at exactly -0.20 pt (-0.27 document px) of the edge and one at
-0.15 pt; only two genuinely pass it, both hanging punctuation (s1, s7). The last line of a
paragraph and left-aligned paragraphs are never brought to it. Six justified lines end on a
space and are excluded from the table, since a trailing space is not part of a line's width.

## 2. What "natural" means

`s2`. On lines Word did not stretch, the mean CJK cell is the em: 8 pure-CJK lines average
11.99 / 12.00 / 12.01 / 12.02 pt. That mean is built out of two values, 12.10 pt (modal) and
11.50 pt, in about a 5:1 ratio:

| kind | CJK seams at 11.5/11.55 ("compressed") | share |
|---|---|---|
| `justify-not-last` | 65 of 1308 | 5.0% |
| `justify-LAST-line` | 33 of 200 | 16.5% |
| left-aligned | 35 of 198 | 17.7% |

Justification mostly removes the 11.5 cells; they are not what justification adds.

Word's automatic inter-script space, measured as (seam width - the left character's advance) on
lines it did not stretch: 2.85..2.90 pt (n=8), i.e. a quarter em. It appears at Han/Latin and
Han/digit seams and never next to CJK punctuation: `cjkpunct>latin` measures a bare 12.10 pt.
That matches the `punctEdge` exception already in `DocxTextLayout`.

## 3. Slack per line (question 1, second half)

Slack = (425.2 pt - line indent) - natural width of the same characters. Chinese-containing lines,
two readings of "natural" because the modal cell is 12.10 pt and the em is 12.00 pt (`s3`):

| measure | n | min | p25 | median | p75 | max |
|---|---|---|---|---|---|---|
| slack, cell basis | 42 | -8.60 | 1.85 | 4.00 | 5.80 | 57.60 pt |
| slack, em basis | 42 | -6.80 | 4.75 | 5.20 | 7.30 | 59.20 pt |
| slack, em basis, document px | 42 | -9.07 | 6.33 | 6.93 | 9.73 | 78.93 px |
| delivered stretch, em basis | 42 | -2.60 | 4.80 | 5.00 | 7.15 | 59.00 pt |
| slack per seam, em basis | 42 | -0.19 | 0.12 | 0.15 | 0.22 | 2.11 pt |

A typical Chinese line has about 5 pt (6.9 document px) of slack, under half a character for the
whole line, and 34 seams to spend it on (28..42 in this corpus). Median 3.00 of those 34 seams move,
8.82% of the line (s3). The four lines whose em-model slack comes out negative are mixed-script
lines where the model reads the Latin advances a little wide; they fill to -0.20 pt like the rest.

## 4. Which seams get widened (question 2)

A seam counts as widened when it sits 0.35 pt or more above its measured natural width. 225 seams
across the 45 justified lines.

Position along the line, widened seams by decile (d0 = leftmost tenth) and the same seams counted
back from the right edge:

| decile from the left | d0 | d1 | d2 | d3 | d4 | d5 | d6 | d7 | d8 | d9 |
|---|---|---|---|---|---|---|---|---|---|---|
| widened seams | 12 | 34 | 28 | 21 | 33 | 21 | 19 | 31 | 16 | 10 |
| share | 5% | 15% | 12% | 9% | 15% | 9% | 8% | 14% | 7% | 4% |
| same, from the right | 16 | 11 | 34 | 18 | 18 | 34 | 21 | 27 | 34 | 12 |

No left or right bias: the counts sit between 4% and 15% of the total on every decile from both
ends, and the two ends themselves are almost never used. The first seam of a line is widened on 1
line of 45, the last seam on 2. The first widened seam sits at fraction 0.00..0.91 of the line
(median 0.15), the last one at 0.15..1.00 (median 0.76), so the widened seams stop well short of
the right edge on most lines.

Between two neighbouring widened seams: 180 samples, min 1, p25 2, median 3, p75 8, max 26 seams.

Within a line the pattern is not content-driven, it is positional. All 14 pure-CJK lines with 35
characters and no indent widen exactly seams [5, 15, 25] of 34; the two 33-character lines that
carry a 24.2 pt first-line indent widen [7, 15, 24] of 32; the two 36-character lines widen
[5, 16, 27] of 35 (`s4` prints the run-length map, which is identical line to line). Different
text, same indices: Word spends the slack by position, not by word or by character identity.

## 5. How much, and is it quantized (question 3)

CJK seam deltas on justified lines, measured against a 12.00 pt em, on the 0.05 pt report grid:

    -0.50 x37  -0.45 x28  +0.05 x82  +0.10 x1038  +0.65 x44  +0.70 x24  +1.20 x1  +1.25 x2
    +1.80 x14  +1.85 x6   +2.35 x5   +2.40 x16    +2.95 x5   +3.50 x1   +3.55 x5

That is a ladder, not a continuum. The +0.10 population is Word's own 12.10 pt cell against a
12.00 pt em. Above it the values step by about 0.55/0.60 pt, twice that, three times, and so on.
Restricting to the widened seams: min +0.55, p25 +0.60, median +0.70, p75 +1.80, max +3.55 pt.

The single quantum is fixed more precisely than 0.6 pt. All 20 distinct CJK seam widths in the
corpus, stretched or not, lie on one grid built from the 12.10 pt cell (`s5b`):

| k | cell + k*Q (pt) | observed widths (count) |
|---|---|---|
| -1 | 11.525 | 11.50 x102, 11.55 x31 |
| 0 | 12.100 | 12.05 x84, 12.10 x1358 |
| +1 | 12.675 | 12.65 x41, 12.70 x24 |
| +3 | 13.825 | 13.80 x8, 13.85 x6 |
| +4 | 14.400 | 14.40 x13 |
| +5 | 14.975 | 14.95 x3, 15.00 x5 |
| +6 | 15.550 | 15.55 x3 |
| +7 | 16.125 | 16.10 x1, 16.15 x2 |
| +8 | 16.700 | 16.70 x6 |
| +9 | 17.275 | 17.25 x5, 17.30 x3 |
| +10 | 17.850 | 17.85 x5 |
| +11 | 18.425 | 18.40 x1, 18.45 x5 |

(k = +2 never occurs in this corpus.) Requiring every one of those 20 values to be cell + k*Q
leaves Q in 0.573..0.577 pt; the worst residual is 0.050 pt, which is exactly the resolution of the
capture, and the next-best Q outside that band already misses by 0.058 pt. So:

    Q = 0.575 pt = 11.5 twips = 0.767 document px = 4.8% of the 12 pt em

The same quantum explains the compressed cells on unstretched lines: their k mix is 0 x135 and
-1 x30, mean k = -0.182, which puts the average cell at 12.00 pt, the em. Word is not stretching
those lines; it is rounding the em onto this grid. Justification then lifts k on a few seams.

Answers to the specific questions:

- It is one quantum of 0.575 pt applied to N seams, and for large slack the same seam takes
  several quanta (k up to +11 measured). It is not a continuous distribution.

- The quantum is not 0.05 pt. 0.05 pt is only the reporting grid; the increments are 11.5 twips.
- Largest single-seam stretch measured: +3.55 pt (4.73 document px) on a Han/Latin seam,
  para 95 / page 9 / line 1, seam 17, gap 18.45 pt (= 11 quanta on that one seam). On a plain
  ideograph-to-ideograph seam the maximum is +2.40 pt (gap 14.40 = 4 quanta).

- Inside one line the distinct deltas number 2..7 (mode 4), and the step between neighbouring
  values on the same line is 0.05 pt (66 cases), 0.55 pt (33), 0.50 (9), 0.60 (6) and larger jumps
  of 1.7/2.3/2.85 when a line runs several quanta deep.

## 6. Which seam types may open (question 4)

`s6` measures every seam against the natural advance of its own left character (plus Word's quarter
em where Word adds one). Key rows, `d_max` = the largest delta seen on justified lines, `k%` = the
share of samples that qualify as widened:

| seam | n | median gap | max gap | d_max | k% | verdict |
|---|---|---|---|---|---|---|
| `cjk>cjk` | 1051 | 12.10 | 14.40 | +2.40 | 7% | opens |
| `cjk>cjkpunct` | 107 | 12.10 | 14.40 | +2.40 | 7% | opens |
| `cjkpunct>cjk` | 106 | 12.10 | 14.40 | +2.40 | 7% | opens |
| `cjkpunct>digit` | 4 | 12.65 | 13.80 | +1.80 | 50% | opens |
| `digit>cjkpunct` | 4 | 5.80 | 8.10 | +2.30 | 50% | opens |
| `cjk>latin` | 24 | 17.25 | 18.45 | +3.55 | 100% | opens, hardest of all |
| `cjk>digit` | 7 | 17.85 | 18.45 | +3.55 | 100% | opens |
| `latin>cjk` | 29 | 10.90 | 13.25 | +2.90 | 86% | opens |
| `digit>cjk` | 3 | 10.95 | 10.95 | +2.90 | 100% | opens |
| `space>latin` | 32 | 3.45 | 6.35 | +3.45 | 66% | opens |
| `latin>space` | 23 | 5.80 | 9.20 | +0.60 | 22% | wobble, see below |
| `latin>latin` | 206 | 5.80 | 10.95 | +0.60 | 15% | wobble, see below |
| `digit>digit` | 11 | 5.75 | 6.35 | +0.60 | 27% | wobble, see below |
| `cjkpunct>latin` | 6 | 12.10 | 12.10 | +0.10 | 0% | never |
| `cjkpunct>cjkpunct` | 1 | 12.10 | 12.10 | +0.10 | 0% | never |
| `cjk>other` | 2 | 12.05 | 12.10 | +0.10 | 0% | never |
| `other>latin`, `other>digit`, `other>other`, `digit>latin`, `other>cjkpunct` | 17 | - | - | +0.05 | 0% | never |

Reading of it:

- Every seam with a CJK character on either side opens. `cjkpunct>digit` and `digit>cjkpunct` open
  even though the punctuation itself never receives Word's automatic quarter em.

- `cjkpunct>latin` never opens and never receives the quarter em either (12.10 pt natural, +0.10
  worst case). CJK punctuation is transparent to Word's inter-script spacing.

- The seam at a Chinese/Latin boundary is not protected, it is the most stretched seam in the
  document: 100% of those seams are widened, up to +3.55 pt (15.00 pt natural with its quarter em,
  18.45 pt realized = 11 quanta). So widening that seam is legitimate.

- Explicit spaces inside Chinese text open too: space advance is 2.85..3.45 pt unstretched, and on
  justified lines the delta is median +0.55, p75 +1.70, max +3.45 pt; 23 of 34 samples are widened
  and none lower than -0.05 pt. Examples inside Chinese paragraphs: para 95 / page 9 / line 8 seam 15
  (2.90 -> 4.60 pt) and para 160 / page 18 / line 16 seam 28 (2.90 -> 5.20 pt).

- Seams inside Latin words do not open. The `+0.60` outliers are Word's own placement wobble on
  proportional text, not justification: the delta distribution is two-sided (16% at or above
  +0.35 pt, 13% at or below -0.35 pt, median +0.00) while the CJK column is one-sided (9% up, 5%
  down against a +0.10 baseline) and the space column is strictly one-sided (68% up, 0% down). Per
  character, 22 of 24 letters and digits keep exactly the same median advance justified or not, and
  the per-line median intra-Latin delta is +0.00 on all seven Latin-heavy lines.

- One measurement caveat: the digit U+0031 alone measures anywhere from 4.00 to 10.95 pt in this
  corpus depending on what follows it, so no claim here rests on a single Latin seam.

- `cjk>space` and `space>cjk` seams do not occur in this corpus, so the behaviour of a space
  directly beside an ideograph is unmeasured.

## 7. Trailing punctuation, and whether Word ever gives up (question 5)

Four justified lines end on CJK punctuation. Two of them (para 79 / page 5 / line 21 and para 217 /
page 22 / line 8, both 36 characters ending U+3001) sit +11.90 pt past the edge: the first 35
cells fill to x = 425.10 pt, 0.10 pt short of the column, and the mark hangs from 425.10 pt to
437.20 pt, entirely outside it. That is `w:overflowPunct`, and it is also what makes those
lines' arithmetic work: counted inside the column, 36 cells of 12.10 pt overshoot the 425.2 pt
column by a whole cell; with the mark outside, the line needs the usual +1.70 pt and takes the
usual 3 quanta (`s5b`). The seam in front of a trailing mark is not widened (delta +0.10 pt on
all four lines).

There is no slack threshold. Bucketing justified lines by slack, every bucket still reaches the
edge (`s7`):

| slack (pt) | n | fill error min | median | max | per-seam delta median | max |
|---|---|---|---|---|---|---|
| 0..5 | 7 | -0.20 | -0.20 | -0.20 | +0.10 | +2.95 |
| 5..10 | 26 | -0.20 | -0.20 | -0.15 | +0.10 | +3.55 |
| 10..20 | 3 | -0.20 | -0.20 | -0.20 | +0.10 | +3.55 |
| 20+ | 2 | -0.20 | -0.20 | -0.20 | +1.80 | +3.55 |

The most stretched lines in the corpus, none left ragged:

| line | chars | slack | share of the line | seams moved | fill error |
|---|---|---|---|---|---|
| para 121 / page 13 / line 25 | 29 | +59.20 pt | 14.8% | 28 of 28 | -0.20 pt |
| para 160 / page 18 / line 16 | 40 | +40.25 pt | 9.5% | 16 of 39 | -0.20 pt |
| para 120 / page 13 / line 20 | 38 | +12.35 pt | 2.9% | 10 of 37 | -0.20 pt |

The first carries 50.1 pt of slack on the cell basis (401.0 pt available for 29 cells of
12.10 pt, so four characters spare), and Word opens all 28 of its seams by three or four
quanta rather than leave it short.

## 8. The same lines through `DocxTextLayout` (question 6)

Geometry our code works in: document px = pt * 4/3 (`PageGeometry.points`), so the body column is
`round(425.2 * 4/3)` = 567 px, a 12 pt ideograph advances exactly 16 px, and `AutoGap` adds
`textSize / 4` = 4 px. A 595.3 pt page is 793.7 px wide, so on a 1080 px screen `fitScale` puts one
document px at 1.361 device px. Word's quantum is 0.767 document px; `WidenGap.getSize` returns an
`int`, so the smallest step we can express is 1 whole document px, 30% coarser.

`tools/justify-rule.py` replays `spreadLine` + `gapOffsets` (whole-pixel advances, floored slack,
Bresenham across the gaps with a CJK side, 6 px ceiling) and the derived rule, on the same lines.

**The derived rule reproduces Word.** On the 18 pure-CJK justified lines, replaying
`cell 12.10 + round(slack / Q)` quanta dithered evenly puts every character within 0.63 pt of where
Word put it (median 0.07 pt), predicts `k_total` exactly on 18 of 18 lines, and assigns the same k
as Word to 538 of 610 seams (88%). The misses are phase, not amount: the model's first widened seam
averages 5.0 against Word's 5.2, and its period 11.4 seams against Word's 9.9. Word's exact phase
law is not recoverable from this corpus; its arithmetic is.

**Our spread fills the line and stays inside one document pixel of Word everywhere.** The replay
mirrors `spreadLine` as it stands when this was run: floored slack, `MAX_GAP_STRETCH_STEP_PX = 1`,
`MAX_GAP_STRETCH_PX = 6`, seams chosen centred and evenly across the eligible gaps. On the 16
comparable pure-CJK lines (the 2 hanging-punctuation lines are excluded because the replay has no
`PunctInkWidth` model):

| measure | value |
|---|---|
| our right edge minus the 567 px column | 0.00 px on every line |
| Word's right edge minus the column | -0.27 px on every line |
| our x minus Word's x, every character | min -0.87, median +0.07, max +0.87 document px |
| the same in device px on a 1080 px screen | min -1.18, median +0.09, max +1.18 |
| largest disagreement on a line | 0.73 px (0.87 px on the two indented lines) |
| seams we open | 7 of 32..34 |
| seams Word opens | 3 of 32..34 |
| lines that open the seam in front of the last character | us 3 of 45, Word 2 of 45 |

The centred placement matters: choosing the seams instead by integer division from the left leaves
the worst character 1.33 document px away instead of 0.87. What remains is the base cell, not
the spread: we advance an ideograph 16 px (12.00 pt) while Word places on a 12.10 pt cell (16.13
document px), so the same 35-character line hands us 7 px of slack and Word 2.27 px (2.96
quanta). That is the whole reason we open 7 seams of 1 px where Word opens 3 of 0.767 px.

Two more differences, both benign:

- 71 of the 225 seams Word widens carry no CJK character on either side (explicit spaces and Latin
  seams, +64.00 pt between them). `gapOffsets` cannot see those and does not need to: the platform
  opens them under `JUSTIFICATION_MODE_INTER_WORD`, which is what we pass below API 34.
- The Han/Latin quarter-em seam is already inside our eligible set, because `ownsReplacedRange` lets
  `AutoGap` through, and Word leans on that seam hardest: 59 widened instances, up to +3.55 pt.

**How coarse may one seam be?** One whole document px is the smallest seam a `ReplacementSpan` can
report, so the only free choice is how many seams to open. Replaying the same lines at different
ceilings per seam:

| one seam up to | seams opened per line | widest seam | largest disagreement with Word | right edge |
|---|---|---|---|---|
| 1 px (shipped) | 7 | 1 px | 0.87 document px (1.18 device px) | 567 px |
| 2 px | 4 | 2 px | 1.53 document px (2.09 device px) | 567 px |
| 3 px | 3 | 3 px | 1.87 document px (2.54 device px) | 567 px |

Concentrating the slack moves us away from Word rather than toward it, even though it reproduces
Word's seam *count*: Word's own 3 seams move 0.767 document px each, so a 3 px seam is four of Word's
quanta in one place. The even texture is the closer match, which is what the note in `spreadLine`
reports from the phone.

## What we should change

1. Nothing. The line target, the even and centred spread, the eligible seams and the absence of a
   slack threshold all match what Word does. The measured disagreement is 0.73..0.87 document px
   (up to 1.18 device px on a 1080 px screen) at its worst point mid-line and 0.27 px at the edge, on
   a glyph that is 21.8 device px wide. Word's 0.575 pt quantum cannot be expressed through a
   `ReplacementSpan` at all, since `getSize` returns an integer, so `MAX_GAP_STRETCH_STEP_PX = 1` is
   the nearest expressible approximation of Word's 0.767 document px step rather than a coarse
   version of it.
2. If the mid-line texture is ever judged again, note that the remaining difference is set by the
   base cell: an ideograph advanced at Word's 12.10 pt (16.13 px) would leave 2.27 px of slack on a
   full 35-character line instead of 7 px, and 3 seams would be enough. That is a font-metric
   question, not a justification one, and it would change line breaks as well as spacing.
3. Worth verifying on a device rather than assuming: a widened character that also carries `AutoGap`
   ends up with two `ReplacementSpan`s over the same range, and pre-34 text shaping sizes such a
   range with one of them. Word opens 59 auto-space seams on this document, up to +3.55 pt, so if
   `WidenGap` silently loses there, mixed lines lose most of their stretch. This is the only place
   where the measurement suggests our stretch may not reach the seam we intend.
4. Leave `MAX_GAP_STRETCH_PX = 6` alone. Word's widest seam, measured against the 12.10 pt cell it
   places on, is +2.30 pt ideograph to ideograph and +3.45 pt at an inter-script seam, and the
   ceiling is 6 document px = 4.5 pt, so it never binds. The replay never needs more than 1 px.

## Where this corpus stops

- 65 lines, 11 paragraphs, one font size (12 pt) and one document, so `cell = 1.00833 * size` and
  `Q = 0.0479 * size` are one-point extrapolations from a single size. The em-relative form is
  deliberate, but it is not measured.
- No `cjk>space` or `space>cjk` seams occur, so a space directly beside an ideograph is unmeasured.
- The 0.575 pt quantum is fitted from 20 distinct widths; the capture reports to 0.05 pt, so Q is
  pinned to 0.573..0.577 pt and no finer. Whether it is a device-grid artifact of the machine that
  produced the capture or a constant of Word's own is not answerable from these files.
- Word's dither phase (which seam of an evenly spread set gets the quantum) is not determined here;
  only its spacing (about one quantum per 10 seams at typical Chinese slack) and its total are.
- Section 8's replay uses Word's own Latin advances as a stand-in for Android's metrics, which is why
  mixed lines are counted there rather than measured, and it has no `PunctInkWidth`, which is why the
  two hanging-punctuation lines are excluded from the deviation figures. It mirrors the Java named
  above; if `spreadLine` changes, rerun the script after pointing `STEP_PX` and `CEIL_PX` at the new
  constants.
