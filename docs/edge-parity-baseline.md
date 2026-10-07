# Edge parity baseline (before the justification fix)

Produced by `tools/edge-parity.ps1`, which compares where our layout puts a line's right edge with
where Microsoft Word put the same line, in px at 96 dpi, for the lines Word itself measured in
`tests/samples/input-liu.docx`. New files: this report, that script, and `artifacts/edge-parity/`.
The `old` capture was re-run with `tools/capture-device.ps1 -Impls old` so that both captures carry
the line-geometry columns; the re-run reproduces the earlier `old` capture (601 laid-out lines, 28
pages, identical `lineWidthPx` on all 39 lines this report cites from it), so the baseline is the
same rendering measured more precisely.

## Re-running it

```
pwsh tools/edge-parity.ps1 -Impl old
pwsh tools/edge-parity.ps1 -Impl new
```

Each run writes `artifacts/edge-parity/<impl>.tsv` (one row per matched line) and prints the summary
reproduced below. The script refuses a capture whose `lines-all.tsv` has no `lineLeftPx` / `paraXPx`
columns, so an outdated capture is a hard error rather than a silent fallback. After the rebuild, the
figures that should move are the `justify-not-last` and `toc-tab-entry` bucket rows, the raggedness
lines, and `within 1 px of the right margin`.

## Coordinates and conversion

`PageGeometry.java` is the only authority: `twips(t) = t / 15` and `points(pt) = pt * 96 / 72`.
Since 1 pt = 20 twips, both routes give 1 pt = 20/15 = 96/72 = 1.3333 document units, and those
document units are the px this tool reports (the layout is 96-DPI by construction, so screen density
never enters). Word's truth files are in points, so every Word number here is `pt * 4 / 3`. The body
column is 425.2 pt = 566.9333 px, read from each capture's own `summary.txt`
(`geometry_units96 ... contentWidth=566.9333`), which matches `artifacts/word/geometry.txt`
("text width sections 2-3 = 425.2 pt").

Both right edges are counted in the same document coordinate system, from the column's left text
boundary, so no term is added by hand:

- Word's `right_edge_pt` (`Range.Information(7)`) is measured from that boundary, so any first-line
  or left indent is already inside the number.
- Our `our_right_px = para_x_px + line_left_px + our_width_px`. The capture now records line geometry
  directly: `paraXPx` is where the paragraph layout is drawn (`ParagraphLayout.text.x`, 0 for a
  paragraph starting on the text margin), `lineLeftPx` is `StaticLayout.getLineLeft(line)` and
  `our_width_px` is `StaticLayout.getLineWidth(line)`. All three are measured, none is inferred.
- `indent_px` stays in the TSV for reference (Word's declared `w:ind` / `w:firstLine` for that line).
  It is deliberately not added to the right edge: the engine bills a first-line indent inside the
  line's own advance, so adding it would count the same indent twice.

Only 2 of the 39 matched rows in old and 2 of 47 in new start
anywhere other than the text margin: the two indented contents entries (word paragraphs
33 and 46 at `para_x_px` 52.93). Both sides of those rows are measured geometry now.

## Inputs pinned to exact content

| side | file | sha256 (first 16) | bytes | stamp |
| --- | --- | --- | --- | --- |
| ours, old | `artifacts/device/old/lines-all.tsv` | 8193DD572B774510 | 84920 | 2026-10-07 22:22:45 |
| ours, new | `artifacts/device/new/lines-all.tsv` | F6FC9574FC9BBC07 | 84757 | 2026-10-07 22:33:06 |
| Word truth | `artifacts/word-justify/lines-summary.tsv` | 121A9D0B0A7C7D6F | 15425 | 2026-10-07 21:25:50 |
| Word truth | `artifacts/word-justify/toc-chars.tsv` | 9FBA99B738445E1A | 5027 | 2026-10-07 21:29:13 |

Word's truth files hold 65 lines in 11 body paragraphs plus 5 table-of-contents entries: 70 lines in
16 paragraphs, and that is the whole comparison. The captures hold about 600 laid-out lines and only
these 70 have a Word right edge to compare against. This report pairs 39 of the 70 in the
`old` capture and 47 in the `new` capture; the rest are counted as unmatched and printed,
never dropped.

## Baseline: `-Impl old` (capture sha 8193DD572B774510)

```text
impl=old
capture=artifacts\device\old
out=artifacts\edge-parity\old.tsv
capture_stamp=2026-10-07 22:22:45 sha256=8193DD572B774510 bytes=84920
our_lines_in_capture=601  our_paragraph_blocks=210
word_truth: lines-summary=65 toc-chars=5  total=70 lines in 16 paragraphs
text_width=425.2 pt = 566.9333 px  (pt -> px = * 4/3, PageGeometry.points)
paragraphs aligned=16/16  lines matched=39/70
unmatched word lines=31  unmatched word paragraphs=0  device lines skipped=5

delta_px = our right edge - Word right edge, px at 96 dpi (positive = our ink sits right of Word's)
bucket n columns are disjoint and add up to lines matched; toc-tab-entry rows are kept out of last-line.
bucket                 n   mean|x|  median|x|   p90|x|   max|x|  mean signed
justify-not-last      26      6.81       6.80    11.80    16.93        -6.57
last-line              5      1.04       0.53     3.67     3.67        -0.75
left-aligned           3      0.93       0.93     0.93     0.93         0.93
toc-tab-entry          5     12.24      15.40    15.47    15.47        12.24
rows starting off the text margin (para_x_px or line_left_px non-zero): n=2 mean|d|=11.40 of 39 matched

raggedness = stddev (px) of OUR justified non-last line right edges inside one paragraph;
Word's own stddev over exactly those lines is printed next to it for scale.
paragraphs with >=3 justified non-last lines: 4
our  stddev px: mean=2.69 median=2.44 p90=3.98 max=3.98
word stddev px: mean=2.44 median=0.92 p90=7.60 max=7.60
our spread (max-min) px: mean=7.00 median=5.50 max=13.00
pooled justified non-last right edges: stddev=3.46 px min=553.00 max=566.00 (text width 566.93)
within 1 px of the right margin: 4/26 (15.4%)   more than 8 px short of it: 6 (23.1%)


worst 6 by |delta_px|:
  our p5 blk72 ln2 | word para79 ln21 | justify-not-last paraX=0 left=0 width=566 right=566 | word=582.93 delta=-16.93 word_gap=16 stop=
  our p3 blk52 ln0 | word para59 ln1 | left paraX=0 left=0 width=560 right=560 | word=544.53 delta=15.47 word_gap=-22.4 stop=544.27
  our p3 blk58 ln0 | word para65 ln7 | left paraX=0 left=0 width=560 right=560 | word=544.53 delta=15.47 word_gap=-22.4 stop=544.27
  our p2 blk39 ln0 | word para46 ln21 | left paraX=52.93 left=0 width=507 right=559.93 | word=544.53 delta=15.4 word_gap=-22.4 stop=544.27
  our p9 blk88 ln1 | word para95 ln2 | justify-not-last paraX=0 left=0 width=553 right=553 | word=566.8 delta=-13.8 word_gap=-0.13 stop=
  our p9 blk88 ln5 | word para95 ln6 | justify-not-last paraX=0 left=0 width=555 right=555 | word=566.8 delta=-11.8 word_gap=-0.13 stop=

unmatched word lines (first 6 of 31):
  word_para=77 word_line=13 kind=justify-not-last text=\u5e94\u529b\u96c6\u4e2d[13]\u3002\u8fde\u63a5\u5b8c\u6210\u540e\uff0c\u82e5\u80fd\u591f\u5408\u7406\u63a7\u5236\u5b54\u9699\u7ed3\u6784\u548c\u6db2\u76f8\u586b\u5145\u7a0b\u5ea6\uff0c\u5219\u6709\u671b\u5f62
  word_para=77 word_line=14 kind=justify-not-last text=\u6210\u591a\u5b54\u94dc\u9aa8\u67b6\u4e0e\u91d1\u5c5e\u95f4\u5316\u5408\u7269\u7ec4\u6210\u7684\u590d\u5408\u63a5\u5934\uff0c\u5728\u4fdd\u6301\u8f83\u9ad8\u5bfc\u7535\u3001\u5bfc\u70ed\u80fd\u529b\u7684\u540c
  word_para=77 word_line=15 kind=justify-not-last text=\u65f6\u6539\u5584\u63a5\u5934\u7684\u97e7\u6027\u548c\u6297\u70ed\u75b2\u52b3\u6027\u80fd\u3002\u76f8\u53cd\uff0c\u5b54\u9699\u7387\u8fc7\u9ad8\u3001\u5b54\u5f84\u5206\u5e03\u4e0d\u5747\u6216\u6db2\u76f8\u586b
  word_para=77 word_line=16 kind=justify-not-last text=\u5145\u4e0d\u8db3\uff0c\u4e5f\u53ef\u80fd\u9020\u6210\u672a\u586b\u5145\u5b54\u6d1e\u548c\u5c40\u90e8\u7f3a\u9677\u3002\u56e0\u6b64\uff0c\u591a\u5b54\u94dc\u7684\u5236\u5907\u5de5\u827a\u3001\u5b54\u9699\u7ed3
  word_para=77 word_line=17 kind=justify-LAST-line text=\u6784\u4ee5\u53ca\u5176\u4e0eSn\u57fa\u6db2\u76f8\u4e4b\u95f4\u7684\u53cd\u5e94\u884c\u4e3a\uff0c\u9700\u8981\u8fdb\u884c\u7cfb\u7edf\u7814\u7a76\u3002
  word_para=85 word_line=6 kind=left text=\u5b54Cu\u7684\u5236\u5907\u4e0e\u4e2d\u95f4\u5c42\u8bbe\u8ba1\u3001\u754c\u9762\u53cd\u5e94\u4e0eIMC\u751f\u957f\u3001\u63a5\u5934\u529b\u5b66\u6027\u80fd\u53ca\u9ad8\u6e29\u53ef\u9760\u6027
```

## Baseline: `-Impl new` (capture sha F6FC9574FC9BBC07)

```text
impl=new
capture=artifacts\device\new
out=artifacts\edge-parity\new.tsv
capture_stamp=2026-10-07 22:33:06 sha256=F6FC9574FC9BBC07 bytes=84757
our_lines_in_capture=599  our_paragraph_blocks=210
word_truth: lines-summary=65 toc-chars=5  total=70 lines in 16 paragraphs
text_width=425.2 pt = 566.9333 px  (pt -> px = * 4/3, PageGeometry.points)
paragraphs aligned=16/16  lines matched=47/70
unmatched word lines=23  unmatched word paragraphs=0  device lines skipped=6

delta_px = our right edge - Word right edge, px at 96 dpi (positive = our ink sits right of Word's)
bucket n columns are disjoint and add up to lines matched; toc-tab-entry rows are kept out of last-line.
bucket                 n   mean|x|  median|x|   p90|x|   max|x|  mean signed
justify-not-last      32      0.99       0.20     0.93    15.93        -0.00
last-line              7      1.13       0.73     3.67     3.67        -0.14
left-aligned           3      0.93       0.93     0.93     0.93         0.93
toc-tab-entry          5      0.56       0.53     0.60     0.60        -0.56
rows starting off the text margin (para_x_px or line_left_px non-zero): n=2 mean|d|=0.60 of 47 matched

raggedness = stddev (px) of OUR justified non-last line right edges inside one paragraph;
Word's own stddev over exactly those lines is printed next to it for scale.
paragraphs with >=3 justified non-last lines: 5
our  stddev px: mean=0.00 median=0.00 p90=0.00 max=0.00
word stddev px: mean=1.94 median=0.36 p90=7.60 max=7.60
our spread (max-min) px: mean=0.00 median=0.00 max=0.00
pooled justified non-last right edges: stddev=0.00 px min=567.00 max=567.00 (text width 566.93)
within 1 px of the right margin: 32/32 (100.0%)   more than 8 px short of it: 0 (0.0%)


worst 6 by |delta_px|:
  our p5 blk72 ln2 | word para79 ln21 | justify-not-last paraX=0 left=0 width=567 right=567 | word=582.93 delta=-15.93 word_gap=16 stop=
  our p17 blk148 ln0 | word para155 ln28 | justify-not-last paraX=0 left=0 width=567 right=567 | word=562.93 delta=4.07 word_gap=-4 stop=
  our p28 blk372 ln3 | word para416 ln21 | left paraX=0 left=0 width=57 right=57 | word=60.67 delta=-3.67 word_gap=-506.27 stop=
  our p5 blk70 ln6 | word para77 ln17 | justify-LAST-line paraX=0 left=0 width=425 right=425 | word=423.13 delta=1.87 word_gap=-143.8 stop=
  our p7 blk78 ln1 | word para85 ln4 | left paraX=0 left=0 width=560 right=560 | word=559.07 delta=0.93 word_gap=-7.87 stop=
  our p7 blk78 ln0 | word para85 ln3 | left paraX=0 left=0 width=560 right=560 | word=559.07 delta=0.93 word_gap=-7.87 stop=

unmatched word lines (first 6 of 23):
  word_para=85 word_line=6 kind=left text=\u5b54Cu\u7684\u5236\u5907\u4e0e\u4e2d\u95f4\u5c42\u8bbe\u8ba1\u3001\u754c\u9762\u53cd\u5e94\u4e0eIMC\u751f\u957f\u3001\u63a5\u5934\u529b\u5b66\u6027\u80fd\u53ca\u9ad8\u6e29\u53ef\u9760\u6027
  word_para=85 word_line=7 kind=left text=\u7b49\u65b9\u9762\u3002\u56fd\u5916\u76f8\u5173\u7814\u7a76\u5f00\u5c55\u8f83\u65e9\uff0c\u56fd\u5185\u5219\u9010\u6e10\u91cd\u89c6\u591a\u5b54Cu\u5728\u77ac\u6001\u6db2\u76f8\u8fde\u63a5\u548c\u9ad8
  word_para=85 word_line=8 kind=left text=\u6e29\u7535\u5b50\u5c01\u88c5\u4e2d\u7684\u5e94\u7528\u3002\u4e0b\u9762\u5c06\u4ece\u591a\u5b54Cu\u5236\u5907\u3001TLP\u8fde\u63a5\u3001\u63a5\u5934\u6027\u80fd\u53ca\u53ef\u9760\u6027\u7b49
  word_para=85 word_line=9 kind=left text=\u65b9\u9762\u8fdb\u884c\u7efc\u8ff0\u3002
  word_para=121 word_line=25 kind=justify-not-last text=\uff081\uff09\u5b54\u7ed3\u6784\u53ef\u63a7\u6027\u4e0e\u53ef\u6d78\u6e17\u6027\u4e4b\u95f4\u7684\u5339\u914d\u5173\u7cfb\u5c1a\u9700\u660e\u786e\u3002\u9700\u8981\u5efa
  word_para=121 word_line=26 kind=justify-not-last text=CuO/NaCl/Ag\u4f53\u7cfb\u7684\u6210\u5f62\u3001\u8fd8\u539f\u70e7\u7ed3\u548c\u9020\u5b54\u5242\u53bb\u9664\u7a97\u53e3\uff0c\u5b9a\u91cf\u63cf\u8ff0\u5b54\u9699\u7387\u3001\u5f00\u5b54
```

## Reading the two summaries

Justified non-last lines, the lines the user calls "not flush", in document coordinates:

| | lines | our right edges seen | mean signed `delta_px` | within 1 px of the margin |
| --- | --- | --- | --- | --- |
| old | 26 | 553, 555, 558, 560, 561, 562, 563, 565, 566 (9 distinct, spread 13.00 px) | -6.57 | 4/26 |
| new | 32 | 567 (1 distinct, spread 0.00 px) | -0.00 | 32/32 |

- old: those 26 lines land on 9 different edges between 553 and 566 px.
  The bucket sits -6.57 px from the margin on average, its worst line is 13.93 px inside
  it, and only 4/26 land within 1 px of it. Within-paragraph raggedness averages 2.69 px
  against Word's 2.44 px for the same lines.
- new: all 32 of those lines land on the same edge, 567 px, which is 0.07 px
  outside the true column width of 566.9333 px (the engine hands `StaticLayout` an integer width), so
  raggedness is 0.00 px in every paragraph and 32/32 sit within 1 px of the margin. Word's
  own stddev over those lines is 1.94 px, all of it from hanging marks.
- Ignoring the one line where Word itself hangs punctuation past the margin, `|delta_px|` on that
  population peaks at 13.80 px in old (mean 6.41 px over 25 lines) and 4.07 px
  in new (mean 0.51 px over 31 lines).
- The one line far off in both captures is word paragraph 79 line 21 (-16.93 old / -15.93
  new): Word hangs that line's trailing punctuation 16 px past the margin (`word_gap_px=16`) while our
  advance ends on it. That column beside `delta_px` is what keeps it readable as hanging punctuation
  rather than a shortfall.
- Table-of-contents page numbers: old sits 7.40 to 15.47 px from Word's measured number
  edge (544.53 px) and lands right of the declared right tab stop at 544.27 px, ending at
  551.93, 552, 559.93, 560 px; new sits -0.60 to -0.53 px from it, ending at 543.93, 544 px. Two of the
  five entries are genuinely indented (`para_x_px` 52.93): old put them at 551.93, 559.93 px, new at
  543.93 px.
- Paragraph tails were never the defect: mean |d| 1.04 px over 5 tail lines in old and
  1.13 px over 7 in new.
- The `left-aligned` bucket holds 3 matched lines at 0.93 px, too few to conclude anything from.

## What this proves, and what it cannot

1. It proves, line by line against Word's own character positions and in one coordinate system,
   whether a justified non-last line ends where Word's does: old scatters 26 such lines over
   9 different right edges (553 to 566 px) with a -6.57 px mean shortfall
   and 2.69 px of within-paragraph raggedness, while new puts all 32 of them on one edge
   0.07 px outside the margin with 0.00 px of raggedness and 4.07 px from Word at
   worst once Word's own hanging punctuation is set aside.
2. It proves the table-of-contents page-number x the same way on the 5 entries Word
   measured: old sits up to 15.47 px right of Word's number edge and of the 544.27 px right tab
   stop, new within -0.60 to -0.53 px of it.
3. It cannot say anything about the 31 (old) and 23 (new) Word lines the matcher could
   not pair, which differ from our line break by about one character (word paragraph 85 line 6 onward
   is the clearest case), nor about the ~550 capture lines Word never measured; and because it
   compares advances rather than glyph ink, a line ending in a wide or hanging mark reads as short by
   that mark's blank half, which is why `word_gap_px` sits beside `delta_px`.