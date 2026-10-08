package com.rikkahub.wordlite;

import android.graphics.Canvas;
import android.graphics.text.LineBreakConfig;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spannable;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextDirectionHeuristics;
import android.text.style.*;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/** Platform shaping with document Run/style metrics; not a Microsoft Word engine. */
public final class DocxTextLayout {
    /** Document-coordinate paragraph layout, shared by pagination and drawing. */
    public static final class Paragraph {
        public final DocxDocument.ParagraphBlock source;
        public final StaticLayout layout;
        public final float x;
        public final DocxFieldEngine.DisplayMap displayMap;
        /**
         * Word 的相邻基线距离是小数（宋体 12pt、w:line=300 实测 26.267px），Android 的行顶只会落在
         * 整数上。这一段每行差多少就记在这里，交给分页器按行补——0.267px 一行、Word 每页实排 33 行就是
         * 8.8px，够把一段翻到下一页去。只有行高来自 WordLineHeights 实测值的段落才有这个余量。
         */
        public float lineCarry;
        /**
         * Word 分页看基线：末行的基线还在版心里，这一行就归这一页，下伸垂过下边距它不管。这个量就是允许
         * 垂下去的那一截（= 实测行距 - 画出来的上伸），交给 PageBreaker 在判页尾时还给版心。真值账：
         * 版心 865.53px、行距 26.267px、上伸 21px，Word 每页 33 行（artifacts/word/page_budget.tsv），
         * 而整行行盒装进版心只装得下 32 行。与 lineCarry 同一条门槛：没量过行高的字体这里是 0。
         */
        public float lineHang;
        Paragraph(DocxDocument.ParagraphBlock source, StaticLayout layout, float x,
                  DocxFieldEngine.DisplayMap displayMap) {
            this.source = source; this.layout = layout; this.x = x; this.displayMap = displayMap;
        }
        public int sourceOffset(int displayOffset) {
            if (displayMap == null || !displayMap.changed) return displayOffset;
            return displayMap.sourceIndex[Math.max(0, Math.min(displayOffset, displayMap.sourceIndex.length - 1))];
        }
        public int[] displayRange(int start, int end) {
            if (displayMap == null || !displayMap.changed) return new int[]{start, end};
            int lo = -1, hi = -1, n = layout.getText().length();
            for (int i = 0; i < n; i++) {
                int source = displayMap.sourceIndex[Math.min(i, displayMap.sourceIndex.length - 1)];
                if (source >= start && source < end) { if (lo < 0) lo = i; hi = i + 1; }
            }
            return lo < 0 ? new int[]{n, n} : new int[]{lo, hi};
        }
    }

    public static Paragraph measure(DocxDocument.ParagraphBlock paragraph, float availableWidth) {
        return measure(paragraph, availableWidth, null, -1);
    }

    public static Paragraph measure(DocxDocument.ParagraphBlock paragraph, float availableWidth,
                                    DocxFieldEngine.DisplayMap display) {
        return measure(paragraph, availableWidth, display, -1);
    }

    /**
     * Measures a paragraph in document coordinates. A section's docGrid pitch is
     * supplied separately because the same paragraph format can be used by
     * sections with different Word baseline grids.
     */
    public static Paragraph measure(DocxDocument.ParagraphBlock paragraph, float availableWidth,
                                    DocxFieldEngine.DisplayMap display, int lineGridPitchTwips) {
        DocxDocument.ParagraphFormat f = paragraph.format;
        float left = PageGeometry.twips(f.leftIndentTwips == -1 ? 0 : f.leftIndentTwips);
        float right = PageGeometry.twips(f.rightIndentTwips == -1 ? 0 : f.rightIndentTwips);
        float first = PageGeometry.twips(f.firstLineIndentTwips == -1 ? 0 : f.firstLineIndentTwips);
        float x = left + Math.min(0, first);
        // StaticLayout only takes an int width. Rounding to the nearest pixel keeps the break
        // threshold within a hair of Word's fractional text width; flooring handed Word a free
        // glyph on every tight line (measured: 0.9 px of unused width on 20+ break deltas).
        int width = Math.max(1, Math.round(availableWidth - right - x));
        SpannableStringBuilder text = display != null && display.changed
                ? styledText(paragraph, display) : styledText(paragraph);
        int firstMargin = Math.round(Math.max(0, first));
        int restMargin = Math.round(Math.max(0, -first));
        if (paragraph.isList) {
            String label = paragraph.listLabel.isEmpty() ? "•" : paragraph.listLabel;
            text.setSpan(new ListMargin(label, firstMargin, restMargin), 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        } else {
            text.setSpan(new LeadingMarginSpan.Standard(firstMargin, restMargin), 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }
        Spacing spacing = null;
        if (text.length() > 0) {
            spacing = new Spacing(f, 1f, wordSingleLineHeightPt(paragraph),
                    wordAscentFraction(paragraph), lineGridPitchTwips, lineHeightMeasured(paragraph));
            text.setSpan(spacing, 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            applyAutoSpace(text, f);
            // The leader is built with the styled text (applyTabLeaders) so that the reading view
            // and the editor get it as well; that helper is idempotent, so this is only a guard
            // for paragraphs that reach measure() with a hand-built SpannableStringBuilder.
            applyTabLeaders(text, paragraph, PageGeometry.points(1f));
        }

        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        // StaticLayout uses this as the fallback paint; each run's PointSizeSpan/FontSpan
        // supplies the actual metrics. Do not hard-code 12pt as the document font.
        float defaultPt = effectiveDefaultPt(paragraph);
        paint.setTextSize(PageGeometry.points(defaultPt));
        paint.setColor(0xFF222222);
        paint.setTypeface(resolve(effectiveDefaultFamily(paragraph)));
        Layout.Alignment alignment = f.alignment == 1 ? Layout.Alignment.ALIGN_CENTER
                : f.alignment == 2 ? Layout.Alignment.ALIGN_OPPOSITE
                : Layout.Alignment.ALIGN_NORMAL;
        boolean allowWordWrap = f.wordWrap && hasLongLatinRun(text);
        StaticLayout layout = build(text, paint, width, alignment, f.alignment == 3, allowWordWrap);
        // The platform keeps owning the word spaces of a Latin run only while it keeps them inside
        // the column. Below API 34 it does not: measured on the target phone, a Latin line that had
        // to pull a long token down gets its whole leftover dumped into the word spaces, so a
        // reference-list line measures 617.50 px where the same text laid out un-justified measures
        // 488.00 px and the column is 566.93 px wide (artifacts/agent-layout-fix/captures/base1,
        // block 355 line 1; the overshoot grows with the number of spaces on the line). Word
        // stretches word spaces as well, but it stops at the right boundary. Once a line is over
        // the edge the platform owns nothing in this paragraph: it is re-laid with justification off
        // and every line gets closed here, under the same floor-the-slack rule CJK seams use.
        boolean platformJustify = f.alignment != 3 || !overshootsColumn(layout, width);
        // Word's w:overflowPunct lets a trailing fullwidth punctuation mark hang
        // past the line width by its blank half, so punct-ended lines hold one
        // more character. Android's breaker cannot express that in a single
        // pass; a second pass narrows exactly the marks Word would hang.
        if (f.alignment == 3 && f.overflowPunct)
            layout = hangTrailingPunctuation(text, paint, layout, width, alignment, allowWordWrap,
                    platformJustify);
        // No Android release closes a Chinese line on its own, so this pass runs on every version:
        // API 29 (the target phone) leaves a CJK line exactly where JUSTIFICATION_MODE_NONE puts
        // it, and the API 34 framework, the first with INTER_CHARACTER, still leaves 7-23 px of a
        // 567 px column unspread on a Chinese paragraph (JustifyApi34Test). Only the slack the
        // platform pass left over gets spread, so a line Android already filled stays filled.
        if (f.alignment == 3 && (hasEastAsian(text) || !platformJustify))
            layout = justifyEastAsian(text, paint, layout, width, alignment, allowWordWrap,
                    platformJustify, !platformJustify);
        Paragraph out = new Paragraph(paragraph, layout, x, display);
        out.lineCarry = spacing == null ? 0f : spacing.lineCarry;
        out.lineHang = spacing == null ? 0f : spacing.lineHang;
        return out;
    }

    private static StaticLayout build(Spannable text, TextPaint paint, int width,
                                      Layout.Alignment alignment, boolean justify, boolean wordWrap) {
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setIncludePad(false).setAlignment(alignment)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        if (Build.VERSION.SDK_INT >= 26) {
            // The mode only decides how the platform spreads Latin word spaces: INTER_WORD below
            // API 34, INTER_CHARACTER from 34 up, where it may also take gaps inside a run. Neither
            // mode is what lands a Chinese line on the right edge -- justifyEastAsian does that on
            // every version, from whatever slack this pass leaves.
            int mode = !justify ? Layout.JUSTIFICATION_MODE_NONE
                    : Build.VERSION.SDK_INT >= 34 ? Layout.JUSTIFICATION_MODE_INTER_CHARACTER
                            : Layout.JUSTIFICATION_MODE_INTER_WORD;
            builder.setJustificationMode(mode);
        }
        if (wordWrap && Build.VERSION.SDK_INT >= 33) {
            builder.setLineBreakConfig(new LineBreakConfig.Builder()
                    .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_LOOSE)
                    .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE)
                    .build());
        }
        return builder.build();
    }

    private static boolean hasLongLatinRun(CharSequence text) {
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == '/') {
                run++;
                if (run >= 5) return true;
            } else {
                run = 0;
            }
        }
        return false;
    }

    /**
     * Second pass emulating Word's hanging punctuation. A line whose greedy
     * fill stopped before a "CJK char + hangable punctuation" pair (Android
     * kinsoku pulled the pair down) gets the punctuation pulled back up if its
     * ink would fit, exactly like Word's overflowPunct. Measured ink fractions
     * come from the bundled SimSun glyphs (ink / em).
     */
    private static StaticLayout hangTrailingPunctuation(SpannableStringBuilder text, TextPaint paint,
                                                         StaticLayout layout, int width,
                                                         Layout.Alignment alignment, boolean wordWrap,
                                                         boolean platformJustify) {
        for (int pass = 0; pass < 3; pass++) {
            ArrayList<Integer> marks = new ArrayList<Integer>();
            int lines = layout.getLineCount();
            for (int k = 0; k + 1 < lines; k++) {
                int head = layout.getLineStart(k + 1);
                if (head + 1 >= text.length()) continue;
                if (text.getSpans(head, head + 1, ReplacementSpan.class).length > 0) continue;
                char first = text.charAt(head);
                float lineRight = layout.getLineWidth(k);
                float inkHead = hangInkFraction(first);
                if (inkHead > 0) {
                    // Punctuation pushed to the next line's start; hang it when its ink fits.
                    if (lineRight + ceilInk(charAdvance(text, paint, head), inkHead) <= width + 0.5f)
                        marks.add(head);
                    continue;
                }
                if (!isCjk(first) || isCjkPunctuation(first)) continue;
                if (head + 2 > text.length()
                        || text.getSpans(head + 1, head + 2, ReplacementSpan.class).length > 0) continue;
                char punct = text.charAt(head + 1);
                float fraction = hangInkFraction(punct);
                if (fraction <= 0) continue;
                float advanceFirst = charAdvance(text, paint, head);
                float inkPunct = ceilInk(charAdvance(text, paint, head + 1), fraction);
                // Word takes "first + hanging punct" when that fits the line;
                // the follower keeps its full width on the next line.
                if (lineRight + advanceFirst + inkPunct <= width + 0.5f)
                    marks.add(head + 1);
            }
            if (marks.isEmpty()) return layout;
            for (int at : marks)
                text.setSpan(new PunctInkWidth(hangInkFraction(text.charAt(at))),
                        at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            layout = build(text, paint, width, alignment, platformJustify, wordWrap);
        }
        return layout;
    }

    /**
     * Word justifies East Asian text by opening the gaps between characters. Android only learned
     * that in API 34: measured on the target phone (API 29), INTER_CHARACTER leaves a Chinese line
     * exactly where NONE puts it and letterSpacing is quantised to whole pixels. A ReplacementSpan
     * is the only way to add a fractional-free gap on the older platforms, and it reports whole
     * pixels, so each line's slack is dithered across its candidate gaps. The right edge then lands
     * on Word's x and no single gap is more than a pixel off, which is what pixel hinting already
     * does to Word's own sub-pixel glyph origins.
     */
    private static StaticLayout justifyEastAsian(Spannable text, TextPaint paint,
                                                 StaticLayout laidOut, int width,
                                                 Layout.Alignment alignment, boolean wordWrap,
                                                 boolean platformJustify, boolean wordSpaces) {
        if (!platformJustify) {
            // The platform just proved it cannot keep this paragraph inside the column, so its
            // stretched advances are no basis for a slack figure -- measured against them every line
            // would look over-full and nothing would be spread. Re-lay the same text with
            // justification off and close the lines from what that leaves instead.
            laidOut = build(text, paint, width, alignment, false, wordWrap);
        }
        final int lines = laidOut.getLineCount();
        if (lines < 2) return laidOut;
        SpannableStringBuilder copy = new SpannableStringBuilder(text);
        int widened = 0;
        for (int line = 0; line < lines - 1; line++)
            widened += spreadLine(copy, laidOut, width, line, wordSpaces);
        if (widened == 0) return laidOut;
        // Widening may never move a line break: pagination and the measured Word parity both rest on
        // the same breaks. Slack is restated from the laid-out geometry, so a break that still moved
        // means the shaping changed underneath us; take that single line's slack back out and lay the
        // paragraph out again, rather than throwing away every line's justification.
        for (int attempt = 0, budget = 2 * lines + 4; attempt < budget; attempt++) {
            // platformJustify is what measure() decided: while the platform keeps the column it also
            // keeps the word spaces of Latin runs, and the CJK gaps here are sized from the slack
            // that pass left over, so nothing is stretched twice. When it lost the column the pass is
            // off and the word spaces are spread here instead (wordSpaces says so).
            StaticLayout spread = build(copy, paint, width, alignment, platformJustify, wordWrap);
            int blocked = lineToClear(laidOut, spread, lines, width);
            if (blocked < 0) return spread;
            // A line that just gained or lost a whole row is not necessarily the one holding the
            // slack that tipped it over, and neither is a break whose cause sits further up. What is
            // taken back is the line's own measured excess: shaving one pixel per attempt left a
            // reference line that only had to give a space back chewing through the whole retry
            // budget and losing every line's justification in the paragraph.
            int excess = Math.max(1, (int) Math.ceil(spread.getLineWidth(blocked)
                    - width + spread.getLineLeft(blocked)) + 1);
            if (!shrinkLine(copy, laidOut, blocked, excess)
                    && !shrinkLine(copy, laidOut, lastLooseLine(copy, laidOut), excess))
                break;
        }
        return laidOut;
    }

    /** Opens one line's slack across its own gaps; returns how many gaps were opened. */
    private static int spreadLine(SpannableStringBuilder copy, StaticLayout laidOut, int width,
                                  int line, boolean wordSpaces) {
        int start = laidOut.getLineStart(line);
        int end = laidOut.getLineEnd(line);
        while (end - start > 1 && Character.isWhitespace(copy.charAt(end - 1))) end--;
        // Slack is restated against the line's INK, not its laid-out width. Word bills a line's right
        // edge as the advance end of its last non-blank character (that is what the truth files call
        // right_edge_pt minus last_gap_pt) and leaves the blanks a wrap left at the tail outside the
        // text boundary, unstretched. Billing those blanks, as this did, is why every reference-list
        // line that ends on a space stopped one space short of the boundary: 52 lines of them sat at
        // 563.00 px against a column of 566.93 (captures/final1/new, blocks 341-370).
        float slack = width - laidOut.getLineLeft(line) - inkWidth(copy, laidOut, line);
        if (slack < 1f) return 0;
        // The blanks a wrap left at the tail get Word's accounting: they sit outside the text
        // boundary and take no width. Without this the platform bills them inside the line, so the
        // stretch stops a space short of the boundary, and the moment the ink reaches the boundary
        // the last blank no longer fits and jumps to the next line as leading whitespace.
        int hardEnd = laidOut.getLineEnd(line);
        if (end < hardEnd)
            copy.setSpan(new BlankTail(), end, hardEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int[][] gaps = gapOffsets(copy, laidOut, line, start, end);
        if (wordSpaces) {
            // Only reachable once the platform has lost the column (see measure()): the word spaces
            // of this line are ours to stretch, and on a mixed line the CJK seams join them so one
            // line's slack is spent exactly once. Each gap takes what it may hold -- a seam stops
            // at MAX_GAP_STRETCH_PX and the rest goes where Word would have put it anyway.
            int[][] spaces = wordSpaceOffsets(copy, laidOut, line, start, end);
            if (spaces.length > 0) {
                int[][] open = new int[gaps.length + spaces.length][];
                System.arraycopy(gaps, 0, open, 0, gaps.length);
                System.arraycopy(spaces, 0, open, gaps.length, spaces.length);
                int[] caps = new int[open.length];
                int room = 0;
                for (int i = 0; i < open.length; i++) {
                    caps[i] = i < gaps.length ? MAX_GAP_STRETCH_PX : MAX_WORD_SPACE_STRETCH_PX;
                    room += caps[i];
                }
                int[] share = spreadAcrossGaps(Math.min((int) Math.floor(slack + 0.001f), room), caps);
                int widened = 0;
                for (int i = 0; i < open.length; i++)
                    if (share[i] > 0)
                        widened += widenGap(copy, open[i][0], open[i][1], share[i], open[i][2] != 0);
                return widened;
            }
        }
        if (gaps.length == 0) return 0;
        // Floor, never round: overshooting the column by a fraction of a pixel would push the last
        // character onto the next line, and a moved break is far worse than an edge that is short by
        // a pixel. MAX_GAP_STRETCH_PX is only a sanity bound -- Word's widest measured single-gap
        // stretch on the reference document is 2.3 pt (~3 px).
        int total = Math.min((int) Math.floor(slack + 0.001f), gaps.length * MAX_GAP_STRETCH_PX);
        // Word opens only a handful of seams per line, by up to 2.3 pt (~3 px) each. That shape was
        // tried and reads wrong on the phone: three pixels at one seam is three device pixels of hole
        // in the middle of a Chinese sentence. One pixel per seam is invisible and is the nearest the
        // platform can get to Word's 0.767 pt step, but it restates the most characters and every
        // restated character is another chance for a break to move. Two pixels reads as flush and
        // leaves the ragged-line count on the reference document at half what one pixel leaves.
        int seams = Math.max(1, (int) Math.ceil(total / (float) MAX_GAP_STRETCH_STEP_PX));
        if (seams > gaps.length) seams = gaps.length;
        int widened = 0;
        for (int j = 0; j < seams && total > 0; j++) {
            // Bresenham-style over the chosen seams, which sit centred and evenly across the line.
            int slot = (int) (((long) (2 * j + 1) * gaps.length) / (2L * seams));
            int before = (int) ((long) j * total / seams);
            int after = (int) ((long) (j + 1) * total / seams);
            if (after > before)
                widened += widenGap(copy, gaps[slot][0], gaps[slot][1], after - before,
                        gaps[slot][2] != 0);
        }
        return widened;
    }

    /**
     * Open one gap: the spanned character restates its laid-out advance plus this share of the
     * slack. The range is handed over in full -- two ReplacementSpans on one range leaves the
     * platform free to size it with the other one, and the widening would silently not happen.
     */
    private static int widenGap(SpannableStringBuilder copy, int at, int basePx, int extraPx,
                                boolean before) {
        for (AutoGap gap : copy.getSpans(at, at + 1, AutoGap.class)) {
            before |= gap.before;   // the seam side belongs to the character, not to this share
            copy.removeSpan(gap);
        }
        copy.setSpan(new WidenGap(basePx, extraPx, before), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return 1;
    }

    /**
     * Hand a line's slack to its gaps as evenly as whole pixels allow, respecting each gap's own
     * ceiling: every round spreads what is left over the gaps that still have room, so a capped CJK
     * seam passes its share on to a word space instead of leaving the line short.
     */
    private static int[] spreadAcrossGaps(int total, int[] caps) {
        int[] share = new int[caps.length];
        int left = total;
        while (left > 0) {
            int open = 0;
            for (int i = 0; i < caps.length; i++) if (share[i] < caps[i]) open++;
            if (open == 0) break;
            int step = Math.max(1, left / open);
            boolean moved = false;
            for (int i = 0; i < caps.length && left > 0; i++) {
                int give = Math.min(Math.min(step, caps[i] - share[i]), left);
                if (give <= 0) continue;
                share[i] += give;
                left -= give;
                moved = true;
            }
            if (!moved) break;
        }
        return share;
    }

    /** The lowest laid-out line that still holds justification slack, or -1 when none does. */
    private static int lastLooseLine(SpannableStringBuilder copy, StaticLayout laidOut) {
        for (int line = laidOut.getLineCount() - 1; line >= 0; line--) {
            int start = laidOut.getLineStart(line), end = laidOut.getLineEnd(line);
            if (end > start && copy.getSpans(start, end, WidenGap.class).length > 0) return line;
        }
        return -1;
    }

    /** True when a replaced range that is not the CJK/Latin auto-space owns this character. */
    private static boolean ownsReplacedRange(Spannable text, int offset) {
        for (ReplacementSpan owner : text.getSpans(offset, offset + 1, ReplacementSpan.class))
            if (!(owner instanceof AutoGap)) return true;
        return false;
    }

    /**
     * How far a line's ink reaches: the advance up to the END of its last non-blank character, with
     * the line's own left offset in front of it. A line whose tail is not blank has the same ink as
     * its laid-out width, and that is the figure the platform broke on, so it is taken as is.
     */
    private static float inkWidth(CharSequence text, StaticLayout laidOut, int line) {
        int start = laidOut.getLineStart(line), end = laidOut.getLineEnd(line);
        int last = end - 1;
        while (last > start && Character.isWhitespace(text.charAt(last))) last--;
        if (last == end - 1) return laidOut.getLineWidth(line);
        float to = laidOut.getPrimaryHorizontal(last + 1);
        float from = laidOut.getPrimaryHorizontal(start);
        if (to <= 0f || to < from) return laidOut.getLineWidth(line);
        return laidOut.getLineLeft(line) + (to - from);
    }

    /** The line whose slack has to come back out, or -1 when every break and edge survived. */
    private static int lineToClear(StaticLayout laidOut, StaticLayout spread, int lines, int width) {
        if (spread.getLineCount() != lines) return lines - 2;
        for (int line = 0; line < lines; line++) {
            // A break moved: the widening on the line ABOVE it pushed a character over.
            if (spread.getLineStart(line) != laidOut.getLineStart(line)) return Math.max(0, line - 1);
            // Only INK may not pass the boundary: a line's trailing blanks hang past it on purpose
            // (see spreadLine) and the platform keeps them whenever they are not a break point, so
            // billing them here would claw the stretch back out of every reference-list line.
            if (inkWidth(spread.getText(), spread, line) > width - spread.getLineLeft(line) + 0.5f)
                return line;
        }
        return -1;
    }

    /**
     * Takes up to {@code px} whole pixels of slack back out of one line, one at a time and always off
     * the gap that is widest at that moment, so what the line keeps stays evenly dithered. False when
     * the line holds no slack left to give, which means something else moved the break.
     */
    private static boolean shrinkLine(SpannableStringBuilder copy, StaticLayout laidOut, int line,
                                      int px) {
        if (line < 0 || line >= laidOut.getLineCount()) return false;
        int start = laidOut.getLineStart(line), end = laidOut.getLineEnd(line);
        int given = 0;
        while (given < px) {
            WidenGap widest = null;
            int at = -1, extra = 0;
            for (WidenGap gap : copy.getSpans(start, end, WidenGap.class)) {
                int from = copy.getSpanStart(gap);
                if (from < start || copy.getSpanEnd(gap) > end || gap.extraPx <= extra) continue;
                widest = gap; at = from; extra = gap.extraPx;
            }
            if (widest == null) break;
            copy.removeSpan(widest);
            if (extra > 1) copy.setSpan(new WidenGap(widest.basePx, extra - 1, widest.before), at, at + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            given++;
        }
        return given > 0;
    }

    /**
     * The gaps that may open, each with the whole-pixel advance the widened character has to
     * restate. Word opens a seam when either side is East Asian; a seam inside a Latin word stays
     * closed. Word stretches the automatic CJK/Latin gap as well, so a character carrying only that
     * gap stays a candidate; any other ReplacementSpan takes the character out, since it sizes and
     * draws the range itself, and so does a decoration, which would lose its underline or get its
     * highlight slit. The advance comes from
     * the laid-out line rather than from a fresh measureText: only restating the SAME number keeps
     * the break where it was, and a shaped run is not always the sum of its per-character measures.
     */
    private static int[][] gapOffsets(Spannable text, StaticLayout laidOut, int line,
                                      int start, int end) {
        java.util.ArrayList<int[]> gaps = new java.util.ArrayList<>();
        for (int i = start; i < end - 1; i++) {
            char left = text.charAt(i), right = text.charAt(i + 1);
            if (!isCjk(left) && !isCjk(right)) continue;
            if (ownsReplacedRange(text, i)) continue;
            if (text.getSpans(i, i + 1, android.text.style.UnderlineSpan.class).length > 0) continue;
            if (text.getSpans(i, i + 1, android.text.style.StrikethroughSpan.class).length > 0) continue;
            if (text.getSpans(i, i + 1, android.text.style.BackgroundColorSpan.class).length > 0) continue;
            if (laidOut.getLineForOffset(i + 1) != line) continue;
            // Who carries the opened seam: the character to its left, unless that character sits inside
            // a Latin or digit token. A ReplacementSpan edge is a break opportunity for StaticLayout, so
            // an edge inside "Ag3Sn" is a cut inside the word (see applyAutoSpace for the measurements).
            // There the CJK character right of the seam carries the slack and the slack opens on its left.
            // Where the opened seam rides. A ReplacementSpan edge is a break opportunity, so a seam
            // inside "Ag3Sn" is a cut inside the word (measured: "、孔洞率、Ag3" | "Sn分布" on page 19).
            // A seam whose left character is inside a token therefore moves to the first character
            // after the token: the line still gains the same pixel at the same place in the sum, and
            // the only edges the breaker sees are the ones Word itself may break at.
            int at = i;
            if (continuesToken(text, i)) {
                at = i + 1;
                while (at < end && isTokenChar(text.charAt(at)) && isTokenChar(text.charAt(at - 1))) at++;
            }
            if (at >= end || at + 1 > end || laidOut.getLineForOffset(at) != line) continue;
            float from = laidOut.getPrimaryHorizontal(at);
            // A line's last character has no next character on this line to measure against:
            // getPrimaryHorizontal(end) reads the following line's left edge, so the step comes out
            // negative and the gap silently leaves the spread. Take this line's own ink edge instead,
            // which is where that character's advance -- and any autoSpace seam riding on it -- ends.
            // Measured without this: the line "…以自变量，以Sn填" of paragraph 160 ends 563 px where Word
            // ends 564.53 px, because the seam moved onto its last character.
            float next = at + 1 == end
                    ? laidOut.getLineRight(line)
                    : laidOut.getPrimaryHorizontal(at + 1);
            float raw = next - from;
            float advance = (float) Math.round(raw);
            if (raw <= 0f || Math.abs(raw - advance) > 0.01f) continue;
            gaps.add(new int[]{at, (int) advance, at == i ? 0 : 1});
        }
        return gaps.toArray(new int[gaps.size()][]);
    }

    /** True when c is not the first character of its Latin/digit token. */
    private static boolean continuesToken(CharSequence text, int c) {
        if (c <= 0 || c >= text.length()) return false;
        return isTokenChar(text.charAt(c)) && isTokenChar(text.charAt(c - 1));
    }

    /** A character that Word keeps glued to its neighbours: Latin, digits, and their - / . , */
    private static boolean isTokenChar(char c) {
        return !Character.isWhitespace(c) && !isCjk(c);
    }

    /**
     * The word spaces a line may open, each with the whole-pixel advance it holds now. Word
     * justifies Latin text by widening the spaces between words and nothing else (docs/
     * justification.md), so this is the only seam the fallback pass may use on a Latin line. The
     * caller already trimmed the line's trailing blanks, which Word leaves outside the boundary
     * unstretched; a space inside a replaced range or under a decoration stays out for the same
     * reason a CJK seam does, and U+00A0 / thin spaces are excluded because Word never widens them.
     */
    private static int[][] wordSpaceOffsets(Spannable text, StaticLayout laidOut, int line,
                                            int start, int end) {
        java.util.ArrayList<int[]> gaps = new java.util.ArrayList<>();
        for (int i = start; i < end - 1; i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\u3000') continue;
            if (ownsReplacedRange(text, i)) continue;
            if (text.getSpans(i, i + 1, android.text.style.UnderlineSpan.class).length > 0) continue;
            if (text.getSpans(i, i + 1, android.text.style.StrikethroughSpan.class).length > 0) continue;
            if (text.getSpans(i, i + 1, android.text.style.BackgroundColorSpan.class).length > 0) continue;
            if (laidOut.getLineForOffset(i) != line) continue;
            float from = laidOut.getPrimaryHorizontal(i), next = laidOut.getPrimaryHorizontal(i + 1);
            float raw = next - from;
            float advance = (float) Math.round(raw);
            if (raw <= 0f || Math.abs(raw - advance) > 0.01f) continue;
            // A blank is a break opportunity already, so it may hold the slack itself: side flag 0.
            gaps.add(new int[]{i, (int) advance, 0});
        }
        return gaps.toArray(new int[gaps.size()][]);
    }

    /**
     * True when the platform put a line of this paragraph past the column. The last line is not
     * justified by Word and is left out: a wide last line is trailing blanks, not justification,
     * and no amount of stretching here would pull it back.
     */
    private static boolean overshootsColumn(StaticLayout laidOut, int width) {
        for (int line = 0, lines = laidOut.getLineCount(); line + 1 < lines; line++)
            if (laidOut.getLineLeft(line) + laidOut.getLineWidth(line) > width + 0.5f) return true;
        return false;
    }

    /** Any Han / CJK punctuation / fullwidth form, i.e. text Word justifies between characters. */
    static boolean hasEastAsian(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x2E80 && c <= 0x9FFF) return true;   // CJK radicals .. Han
            if (c >= 0xF900 && c <= 0xFAFF) return true;   // compatibility ideographs
            if (c >= 0xFE30 && c <= 0xFE4F) return true;   // CJK compatibility forms
            if (c >= 0xFF00 && c <= 0xFF60) return true;   // fullwidth forms
            if (c >= 0x20000) return true;                 // supplementary planes (surrogates)
        }
        return false;
    }

    /**
     * Laid-out width of [from, to) honouring the spans that really shape it: run fonts and sizes
     * through MetricAffectingSpan, and replaced ranges (auto-space gaps, hanging punctuation)
     * through their own reported size. paint.measureText on the fallback paint would miss both.
     */
    private static float spannedWidth(CharSequence text, TextPaint base, int from, int to) {
        if (from >= to) return 0f;
        if (!(text instanceof Spanned)) return base.measureText(text, from, to);
        Spanned spanned = (Spanned) text;
        TextPaint paint = new TextPaint(base);
        float total = 0f;
        int i = from;
        while (i < to) {
            ReplacementSpan replacement = null;
            int replacementEnd = i + 1;
            for (ReplacementSpan span : spanned.getSpans(i, i + 1, ReplacementSpan.class)) {
                int start = spanned.getSpanStart(span), end = spanned.getSpanEnd(span);
                if (start <= i && end > i) {
                    replacement = span;
                    replacementEnd = Math.min(to, end);
                    break;
                }
            }
            if (replacement != null) {
                paint.set(base);
                total += replacement.getSize(paint, text, i, replacementEnd, null);
                i = replacementEnd;
                continue;
            }
            paint.set(base);
            for (MetricAffectingSpan span : spanned.getSpans(i, i + 1, MetricAffectingSpan.class))
                span.updateMeasureState(paint);
            total += paint.measureText(text, i, i + 1);
            i++;
        }
        return total;
    }

    private static float ceilInk(float advance, float fraction) {
        return (float) Math.ceil(advance * fraction);
    }

    private static float charAdvance(Spannable text, TextPaint base, int offset) {
        TextPaint paint = new TextPaint(base);
        for (MetricAffectingSpan span : text.getSpans(offset, offset + 1, MetricAffectingSpan.class))
            span.updateMeasureState(paint);
        return paint.measureText(text, offset, offset + 1);
    }

    /** Ink fraction (ink / em) of punctuation Word allows to hang past the margin. */
    private static float hangInkFraction(char c) {
        switch (c) {
            case '\u3002': return 0.356f; // 。
            case '\uff0c': return 0.278f; // ，
            case '\u3001': return 0.351f; // 、
            case '\uff1b': return 0.264f; // ；
            case '\uff1a': return 0.269f; // ：
            case '\uff1f': return 0.474f; // ？
            case '\uff01': return 0.371f; // ！
            case '\u201d': return 0.516f; // ”
            default: return -1f;
        }
    }

    /** CJK punctuation takes part in no autoSpaceDE/DN gap, matching Word. */
    static boolean isCjkPunctuation(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        if (b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION) return true;
        if (b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS)
            return !((c >= '\uff21' && c <= '\uff3a') || (c >= '\uff41' && c <= '\uff5a'));
        return false;
    }

    /** Editor-facing overload: pxPerPoint is screen pixels per typographic point. */
    public static SpannableStringBuilder styledText(DocxDocument.ParagraphBlock paragraph, float pxPerPoint) {
        return styledTextScaled(paragraph, pxPerPoint);
    }

    /** Document-coordinate overload: 96-DPI logical pixels. */
    public static SpannableStringBuilder styledText(DocxDocument.ParagraphBlock paragraph) {
        return styledTextScaled(paragraph, PageGeometry.points(1));
    }

    private static SpannableStringBuilder styledTextScaled(DocxDocument.ParagraphBlock paragraph, float pxPerPoint) {
        String value = paragraph.text == null ? "" : paragraph.text;
        SpannableStringBuilder text = new SpannableStringBuilder(value);
        int cursor = 0;
        for (DocxDocument.Run run : paragraph.runs) {
            int end = Math.min(value.length(), cursor + run.text.length());
            if (end > cursor) apply(text, cursor, end, mergeBase(paragraph, run.style), pxPerPoint);
            cursor = end;
        }
        if (cursor < value.length()) apply(text, cursor, value.length(), paragraph.baseRunStyle, pxPerPoint);
        for (DocxDocument.RangeStyle rs : paragraph.editedStyles)
            if (rs.end > rs.start && rs.start >= 0 && rs.end <= text.length()) apply(text, rs.start, rs.end, rs.style, pxPerPoint);
        applyTabLeaders(text, paragraph, pxPerPoint);
        return text;
    }

    public static SpannableStringBuilder styledText(DocxDocument.ParagraphBlock paragraph,
                                                    DocxFieldEngine.DisplayMap display) {
        SpannableStringBuilder text = new SpannableStringBuilder(display.text);
        int srcLen = paragraph.text == null ? 0 : paragraph.text.length();
        DocxDocument.RunStyle[] styles = new DocxDocument.RunStyle[Math.max(1, srcLen)];
        int cursor = 0;
        for (DocxDocument.Run run : paragraph.runs) {
            int end = Math.min(srcLen, cursor + run.text.length());
            DocxDocument.RunStyle style = mergeBase(paragraph, run.style);
            for (int i = cursor; i < end; i++) styles[i] = style;
            cursor = end;
        }
        DocxDocument.RunStyle fallback = paragraph.baseRunStyle.copy();
        for (int i = cursor; i < styles.length; i++) styles[i] = fallback;
        for (int i = 0; i < styles.length; i++) if (styles[i] == null) styles[i] = fallback;
        int start = 0, n = display.text.length();
        for (int i = 1; i <= n; i++) {
            boolean boundary = i == n;
            if (!boundary) boundary = styles[sourceIndex(display, i - 1)] != styles[sourceIndex(display, i)];
            if (boundary && start < i) {
                apply(text, start, i, styles[sourceIndex(display, start)]);
                start = i;
            }
        }
        applyTabLeaders(text, paragraph, PageGeometry.points(1f));
        return text;
    }

    private static int sourceIndex(DocxFieldEngine.DisplayMap map, int displayOffset) {
        return Math.max(0, Math.min(map.sourceIndex[displayOffset], map.sourceIndex.length - 2));
    }

    private static DocxDocument.RunStyle mergeBase(DocxDocument.ParagraphBlock p, DocxDocument.RunStyle direct) {
        DocxDocument.RunStyle out = p.baseRunStyle == null ? new DocxDocument.RunStyle() : p.baseRunStyle.copy();
        out.merge(direct);
        return out;
    }

    private static float effectiveDefaultPt(DocxDocument.ParagraphBlock p) {
        int half = p.baseRunStyle == null ? -1 : p.baseRunStyle.fontSizeHalfPoints;
        // Do not borrow a size from the first directly-formatted run: that run's
        // formatting does not apply to the rest of the paragraph in Word.
        return half > 0 ? half / 2f : 11f;
    }

    private static String effectiveDefaultFamily(DocxDocument.ParagraphBlock p) {
        if (p.baseRunStyle != null) {
            if (p.baseRunStyle.eastAsiaFontFamily != null) return p.baseRunStyle.eastAsiaFontFamily;
            if (p.baseRunStyle.fontFamily != null) return p.baseRunStyle.fontFamily;
            if (p.baseRunStyle.asciiFontFamily != null) return p.baseRunStyle.asciiFontFamily;
            if (p.baseRunStyle.highAnsiFontFamily != null) return p.baseRunStyle.highAnsiFontFamily;
        }
        // Word's common fallback for Chinese is a serif CJK face; individual
        // run rFonts override this when present.
        return "serif";
    }

    /**
     * Word's single-line height for a paragraph: max over all runs of
     * (effectiveFontSize × max(eastAsiaRatio, latinRatio)). This replicates
     * how Word picks the tallest line box per-line for "auto" line spacing.
     */
    private static float wordSingleLineHeightPt(DocxDocument.ParagraphBlock p) {
        // 扫描本身在 WordLineHeights.tallest（纯 Java，Host 侧测得到），这里只负责喂字体度量。
        return WordLineHeights.tallest(p, METRICS, effectiveDefaultFamily(p)).pt;
    }

    /**
     * 撑起这一段最高一行的是哪张脸，而那张脸的行高是不是 WordLineHeights 里的实测值。
     * 只有实测撑起来的段落才允许带小数行距（`Spacing.lineCarry`）：没量过的字体照旧走两步取整，
     * 行为一个字节都不改——这条改动必须可逆，不许顺手把没量过的字体一起改了。
     */
    static boolean lineHeightMeasured(DocxDocument.ParagraphBlock p) {
        // 与 wordSingleLineHeightPt 共用同一次扫描：谁的 pt × 比值最高谁说了算，不是比值单独最大。
        return WordLineHeights.carries(p, METRICS, effectiveDefaultFamily(p));
    }

    /** 把 FontManager 的字体度量喂给纯 Java 的行高扫描。 */
    private static final WordLineHeights.Metrics METRICS = new WordLineHeights.Metrics() {
        @Override public FontScriptMetrics forFamily(String family) {
            return family != null ? metricsFor(family) : FontScriptMetrics.DEFAULT;
        }
    };

    /**
     * The ascent fraction within the font's single-line box. Word positions
     * the baseline using (winAscent)/(winAscent+winDescent) of the dominant font.
     */
    private static float wordAscentFraction(DocxDocument.ParagraphBlock p) {
        // Pick the tallest font (same scan as wordSingleLineHeightPt)
        float maxH = 0;
        float bestFrac = 0.8f;
        int baseHalf = p.baseRunStyle != null ? p.baseRunStyle.fontSizeHalfPoints : -1;
        String baseEA = p.baseRunStyle != null ? p.baseRunStyle.eastAsiaFontFamily : null;
        String baseLatin = p.baseRunStyle != null && p.baseRunStyle.asciiFontFamily != null
                ? p.baseRunStyle.asciiFontFamily
                : (p.baseRunStyle != null ? p.baseRunStyle.fontFamily : null);
        for (DocxDocument.Run run : p.runs) {
            if (run.text.trim().isEmpty()) continue;
            int half = run.style.fontSizeHalfPoints > 0 ? run.style.fontSizeHalfPoints : baseHalf;
            if (half <= 0) half = 24;
            float pt = half / 2f;
            String ea = run.style.eastAsiaFontFamily != null ? run.style.eastAsiaFontFamily : baseEA;
            String latin = run.style.asciiFontFamily != null ? run.style.asciiFontFamily : baseLatin;
            FontScriptMetrics mEA = ea != null ? metricsFor(ea) : FontScriptMetrics.DEFAULT;
            FontScriptMetrics mLatin = latin != null ? metricsFor(latin) : FontScriptMetrics.DEFAULT;
            float hEA = pt * mEA.lineHeightRatio, hLatin = pt * mLatin.lineHeightRatio;
            if (run.style.superscript || run.style.subscript) {
                float scriptScale = ScriptGeometry.of(run.style.superscript,
                        hLatin >= hEA ? mLatin : mEA).scale;
                hEA *= scriptScale; hLatin *= scriptScale;
            }
            float h = Math.max(hEA, hLatin);
            if (h > maxH) {
                maxH = h;
                FontScriptMetrics dom = hEA >= hLatin ? mEA : mLatin;
                bestFrac = dom.ascentFraction;
            }
        }
        return bestFrac;
    }

    private static void apply(Spannable text, int start, int end, DocxDocument.RunStyle s) {
        apply(text, start, end, s, PageGeometry.points(1));
    }

    private static void apply(Spannable text, int start, int end, DocxDocument.RunStyle s, float pxPerPoint) {
        if (s == null || start >= end) return;
        int flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
        text.setSpan(new RunStyleMarker(s), start, end, flags);
        int typefaceStyle = (s.bold ? Typeface.BOLD : 0) | (s.italic ? Typeface.ITALIC : 0);
        if (typefaceStyle != Typeface.NORMAL) text.setSpan(new StyleSpan(typefaceStyle), start, end, flags);
        int halfPoints = s.fontSizeHalfPoints > 0 ? s.fontSizeHalfPoints : 22;
        text.setSpan(new PointSizeSpan(halfPoints, pxPerPoint), start, end, flags);
        if (s.underline) text.setSpan(new UnderlineSpan(), start, end, flags);
        if (s.strike) text.setSpan(new StrikethroughSpan(), start, end, flags);
        if (s.colorSet) text.setSpan(new ForegroundColorSpan(s.color), start, end, flags);
        if (s.highlight >= 0) text.setSpan(new BackgroundColorSpan(s.highlight), start, end, flags);

        // Replacement and font boundaries must match; TextLine otherwise draws
        // the same mixed-font script more than once.
        int cursor = start;
        while (cursor < end) {
            boolean cjk = isCjk(text.charAt(cursor));
            boolean complex = isComplexScript(text.charAt(cursor));
            boolean ascii = text.charAt(cursor) < 128;
            int unicodeScript = FontScriptMetrics.unicodeScript(text.charAt(cursor));
            int next = cursor + 1;
            while (next < end && isCjk(text.charAt(next)) == cjk
                    && (text.charAt(next) < 128) == ascii
                    && isComplexScript(text.charAt(next)) == complex
                    && FontScriptMetrics.unicodeScript(text.charAt(next)) == unicodeScript) next++;
            String family = complex ? first(s.complexScriptFontFamily, s.highAnsiFontFamily, s.fontFamily)
                    : cjk ? first(s.eastAsiaFontFamily, s.fontFamily, s.highAnsiFontFamily, s.asciiFontFamily)
                    : ascii ? first(s.asciiFontFamily, s.highAnsiFontFamily, s.fontFamily)
                    : first(s.highAnsiFontFamily, s.asciiFontFamily, s.fontFamily);
            if (family == null) family = cjk ? "\u5b8b\u4f53" : "Times New Roman";
            String requestedFamily = family;
            family = FontManager.symbolFamily(family, text.charAt(cursor), typefaceStyle);
            for (int at = cursor + 1; at < next; at++) {
                if (!family.equals(FontManager.symbolFamily(requestedFamily, text.charAt(at), typefaceStyle))) {
                    next = at; break;
                }
            }
            text.setSpan(new MeasuredFontSpan(family, cjk), cursor, next, flags);
            text.setSpan(new FontSpan(family, cjk), cursor, next, flags);
            int runHalfPoints = complex && s.complexScriptSizeHalfPoints > 0
                    ? s.complexScriptSizeHalfPoints : halfPoints;
            if (runHalfPoints != halfPoints)
                text.setSpan(new PointSizeSpan(runHalfPoints, pxPerPoint), cursor, next, flags);
            boolean script = s.superscript || s.subscript;
            if (script || unicodeScript != 0) {
                boolean sup = script ? s.superscript : unicodeScript > 0;
                text.setSpan(new WordScriptSpan(sup, runHalfPoints, pxPerPoint, family,
                        unicodeScript != 0, s.positionHalfPoints), cursor, next, flags);
            } else if (s.positionSet && s.positionHalfPoints != 0) {
                text.setSpan(new PositionSpan(s.positionHalfPoints, pxPerPoint), cursor, next, flags);
            }
            cursor = next;
        }
    }

    private static final class RunStyleMarker {
        final DocxDocument.RunStyle style;
        RunStyleMarker(DocxDocument.RunStyle style) { this.style = style.copy(); }
    }

    /** Recover document units and all four font slots, not rendered pixels. */
    public static DocxDocument.RunStyle styleAt(Spanned text, int offset, float pxPerPoint) {
        int end = Math.min(offset + 1, text.length());
        RunStyleMarker[] origins = text.getSpans(offset, end, RunStyleMarker.class);
        DocxDocument.RunStyle s = origins.length == 0 ? new DocxDocument.RunStyle()
                : origins[origins.length - 1].style.copy();
        s.bold = s.italic = false;
        for (StyleSpan span : text.getSpans(offset, end, StyleSpan.class)) {
            s.bold |= (span.getStyle() & Typeface.BOLD) != 0;
            s.italic |= (span.getStyle() & Typeface.ITALIC) != 0;
        }
        s.boldSet = s.italicSet = s.underlineSet = s.strikeSet = true;
        s.underline = text.getSpans(offset, end, UnderlineSpan.class).length > 0;
        s.strike = text.getSpans(offset, end, StrikethroughSpan.class).length > 0;
        AbsoluteSizeSpan[] sizes = text.getSpans(offset, end, AbsoluteSizeSpan.class);
        if (sizes.length > 0) {
            AbsoluteSizeSpan span = sizes[sizes.length - 1];
            // A complex-script rendering override must not replace w:sz.
            if (!isComplexScript(text.charAt(offset)) || s.fontSizeHalfPoints <= 0)
                s.fontSizeHalfPoints = span instanceof PointSizeSpan ? ((PointSizeSpan) span).halfPoints
                        : Math.max(1, Math.round(span.getSize() / pxPerPoint * 2));
        }
        for (WordScriptSpan span : text.getSpans(offset, end, WordScriptSpan.class)) {
            if (!span.isUnicode()) {
                s.superscript = span.isSuperscript(); s.subscript = span.isSubscript();
                s.superscriptSet = s.subscriptSet = true;
                if (sizes.length == 0) s.fontSizeHalfPoints = span.baseHalfPoints();
            }
            if (span.positionHalfPoints != 0) {
                s.positionSet = true; s.positionHalfPoints = span.positionHalfPoints;
            }
        }
        for (PositionSpan span : text.getSpans(offset, end, PositionSpan.class)) {
            s.positionSet = true; s.positionHalfPoints = span.halfPoints;
        }
        TypefaceSpan[] fonts = text.getSpans(offset, end, TypefaceSpan.class);
        if (fonts.length > 0) {
            TypefaceSpan last = fonts[fonts.length - 1];
            if (!(last instanceof FontSpan) || !((FontSpan) last).scriptSpecific) {
                // A deliberate font choice applies to every slot; rendering-only
                // script splits must preserve the original mixed-font run.
                s.fontFamily = s.asciiFontFamily = s.highAnsiFontFamily
                        = s.eastAsiaFontFamily = s.complexScriptFontFamily = last.getFamily();
            } else if (origins.length == 0) {
                FontSpan font = (FontSpan) last;
                if (font.cjk) s.eastAsiaFontFamily = font.getFamily();
                else s.asciiFontFamily = s.highAnsiFontFamily = font.getFamily();
            }
        }
        ForegroundColorSpan[] colors = text.getSpans(offset, end, ForegroundColorSpan.class);
        if (colors.length > 0) { s.color = colors[colors.length - 1].getForegroundColor(); s.colorSet = true; }
        BackgroundColorSpan[] highlights = text.getSpans(offset, end, BackgroundColorSpan.class);
        s.highlight = highlights.length == 0 ? -1 : highlights[highlights.length - 1].getBackgroundColor();
        return s;
    }

    /** Rebuild visual spans after a range edit without flattening its neighbors. */
    public static void applyStyle(Spannable text, int start, int end,
                                  DocxDocument.RunStyle overlay, float pxPerPoint) {
        if (start < 0 || end > text.length() || start >= end) return;
        ArrayList<Integer> starts = new ArrayList<Integer>();
        ArrayList<Integer> ends = new ArrayList<Integer>();
        ArrayList<DocxDocument.RunStyle> styles = new ArrayList<DocxDocument.RunStyle>();
        int cursor = 0;
        while (cursor < text.length()) {
            int next = text.nextSpanTransition(cursor, text.length(), Object.class);
            if (cursor < start) next = Math.min(next, start);
            else if (cursor < end) next = Math.min(next, end);
            DocxDocument.RunStyle style = styleAt(text, cursor, pxPerPoint);
            if (cursor >= start && cursor < end) style.merge(overlay);
            starts.add(cursor); ends.add(next); styles.add(style);
            cursor = next;
        }
        for (Object span : text.getSpans(0, text.length(), Object.class)) {
            if (span instanceof RunStyleMarker || span instanceof TypefaceSpan
                    || span instanceof StyleSpan || span instanceof AbsoluteSizeSpan
                    || span instanceof WordScriptSpan || span instanceof PositionSpan
                    || span instanceof UnderlineSpan || span instanceof StrikethroughSpan
                    || span instanceof ForegroundColorSpan || span instanceof BackgroundColorSpan)
                text.removeSpan(span);
        }
        for (int i = 0; i < styles.size(); i++)
            apply(text, starts.get(i), ends.get(i), styles.get(i), pxPerPoint);
    }

    private static boolean isComplexScript(char c) {
        return (c >= '\u0590' && c <= '\u08ff') || (c >= '\u0900' && c <= '\u0dff');
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && value.length() > 0) return value;
        return null;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    public static final class PointSizeSpan extends AbsoluteSizeSpan {
        public final int halfPoints;
        private final float pixels;
        /** Run size in document pixels, not the scaled-pixel value the base keeps. */
        public float pixels() { return pixels; }
        public PointSizeSpan(int halfPoints) { this(halfPoints, PageGeometry.points(1)); }
        public PointSizeSpan(int halfPoints, float pxPerPoint) {
            super(Math.max(1, Math.round(halfPoints / 2f * pxPerPoint)));
            this.halfPoints = halfPoints;
            pixels = Math.max(.1f, halfPoints / 2f * pxPerPoint);
        }
        @Override public void updateMeasureState(TextPaint paint) { paint.setTextSize(pixels); }
        @Override public void updateDrawState(TextPaint paint) { paint.setTextSize(pixels); }
    }

    public static final class PositionSpan extends ReplacementSpan {
        public final int halfPoints;
        private final float shiftPx;
        public float shiftPx() { return shiftPx; }
        public PositionSpan(int halfPoints, float pxPerPoint) {
            this.halfPoints = halfPoints;
            this.shiftPx = halfPoints / 2f * pxPerPoint;
        }
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return (int) Math.ceil(paint.measureText(text, start, end));
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, Paint paint) {
            canvas.drawText(text, start, end, x, y - shiftPx, paint);
        }
    }

    /** Script width, size and baseline use the selected font's OS/2 metrics. */
    public static final class WordScriptSpan extends ReplacementSpan {
        private final boolean superscript;
        private final int baseHalfPoints;
        private final float pxPerPoint;
        private final String family;
        private final boolean unicode;
        public final int positionHalfPoints;
        public WordScriptSpan(boolean superscript, int baseHalfPoints, float pxPerPoint) {
            this(superscript, baseHalfPoints, pxPerPoint, "Times New Roman", false, 0);
        }
        WordScriptSpan(boolean superscript, int baseHalfPoints, float pxPerPoint,
                       String family, boolean unicode, int position) {
            this.superscript = superscript;
            this.baseHalfPoints = baseHalfPoints;
            this.pxPerPoint = pxPerPoint;
            this.family = family;
            this.unicode = unicode;
            positionHalfPoints = position;
        }
        public boolean isSuperscript() { return superscript; }
        public boolean isSubscript() { return !superscript; }
        public boolean isUnicode() { return unicode; }
        public int baseHalfPoints() { return baseHalfPoints; }
        public String family() { return family; }
        /** Base run size in document pixels; the script box is measured from it. */
        public float baseSizePx() { return baseHalfPoints / 2f * pxPerPoint; }
        public float renderedSize(Paint paint) {
            return paint.getTextSize() * metricsFor(family).scale(superscript);
        }
        public float baselineOffset(Paint paint) {
            return paint.getTextSize() * metricsFor(family).offset(superscript)
                    - positionHalfPoints / 2f * pxPerPoint;
        }
        private String value(CharSequence text, int start, int end) {
            return unicode ? text.subSequence(start, end).toString()
                    : FontScriptMetrics.plainDigits(text, start, end);
        }
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            if (unicode) {
                // Word draws U+2080-2089 / ²³¹ superscript-subscript glyphs at
                // full size from the hAnsi font; the glyph itself is already
                // small, so no OS/2 scaling and no plain-digit substitution.
                return (int) Math.ceil(paint.measureText(text, start, end));
            }
            Paint copy = new Paint(paint);
            // Word scales the run to the OS/2 script size and breaks the line on
            // the scaled advance, so a superscript citation costs only its small
            // glyph width. Charging the base size here wrapped lines one
            // character early and moved whole paragraphs to the next page.
            copy.setTextSize(renderedSize(paint));
            return Math.max(1, (int) Math.ceil(copy.measureText(value(text, start, end))));
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, Paint paint) {
            if (unicode) {
                canvas.drawText(text, start, end, x, y, paint);
                return;
            }
            Paint copy = new Paint(paint);
            copy.setTextSize(renderedSize(paint));
            canvas.drawText(value(text, start, end), x, y + baselineOffset(paint), copy);
        }
    }

    /**
     * Widths of the bundled Times/宋体 files. Android's StaticLayout otherwise
     * measures with a generic face, so Latin looks and wraps unlike Word.
     */
    private static final class MeasuredFontSpan extends MetricAffectingSpan {
        private final String family;
        private final boolean cjk;
        MeasuredFontSpan(String family, boolean cjk) {
            this.family = family == null || family.length() == 0
                    ? (cjk ? "宋体" : "Times New Roman") : family;
            this.cjk = cjk;
        }
        String family() { return family; }
        private void apply(TextPaint paint) {
            int style = paint.getTypeface() == null ? Typeface.NORMAL : paint.getTypeface().getStyle();
            Typeface face = resolve(family, cjk, style);
            if (face != null) paint.setTypeface(face);
        }
        @Override public void updateMeasureState(TextPaint paint) { apply(paint); }
        @Override public void updateDrawState(TextPaint paint) { apply(paint); }
    }

    /** Uses Android fallback for unavailable Office fonts, but preserves script preference. */
    public static final class FontSpan extends TypefaceSpan {
        private final boolean cjk;
        private final boolean scriptSpecific;
        public FontSpan(String family) { this(family, containsHan(family), false); }
        public FontSpan(String family, boolean cjk) {
            this(family, cjk, true);
        }
        private FontSpan(String family, boolean cjk, boolean scriptSpecific) {
            super(family == null || family.length() == 0 ? "Times New Roman" : family);
            this.cjk = cjk;
            this.scriptSpecific = scriptSpecific;
        }
        private void apply(TextPaint p) {
            int style = p.getTypeface() == null ? Typeface.NORMAL : p.getTypeface().getStyle();
            Typeface face = resolve(getFamily(), cjk, style);
            if (face != null) p.setTypeface(face);
        }
        @Override public void updateMeasureState(TextPaint p) { apply(p); }
        @Override public void updateDrawState(TextPaint p) { apply(p); }
    }

    /** Registration only; the selected files are loaded on first use. */
    public static void initialize(android.content.Context context) {
        FontManager.initialize(context);
    }

    public static String fontStatus() { return "Times New Roman"; }

    private static FontScriptMetrics metricsFor(String name) {
        return FontManager.metrics(name);
    }

    public static Typeface resolve(String name) {
        return resolve(name, containsHan(name) || isCjkFamily(name));
    }

    private static Typeface resolve(String name, boolean cjk) {
        return resolve(name, cjk, Typeface.NORMAL);
    }

    private static Typeface resolve(String name, boolean cjk, int style) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        Typeface exact = FontManager.load(DocxFontAssets.pathFor(name, style));
        if (exact != null) return styled(exact, style);
        if (cjk && (lower.contains("黑") || lower.contains("hei") || lower.contains("yahei")
                || lower.contains("微软雅黑"))) {
            Typeface fallback = FontManager.load(DocxFontAssets.HEI);
            return fallback != null ? styled(fallback, style) : Typeface.SANS_SERIF;
        }
        if (cjk) {
            Typeface fallback = FontManager.load(DocxFontAssets.SONG);
            return fallback != null ? styled(fallback, style) : Typeface.SERIF;
        }
        if (lower.contains("courier") || lower.contains("consolas") || lower.contains("mono")) {
            Typeface mono = FontManager.load(DocxFontAssets.pathFor("Consolas", style));
            if (mono == null) mono = FontManager.load(DocxFontAssets.pathFor("Courier New", style));
            return mono != null ? styled(mono, style) : Typeface.MONOSPACE;
        }
        if (lower.contains("arial") || lower.contains("calibri") || lower.contains("tahoma") || lower.contains("verdana") || lower.contains("sans")) {
            Typeface sans = FontManager.load(DocxFontAssets.pathFor(name, style));
            return sans != null ? styled(sans, style) : Typeface.SANS_SERIF;
        }
        Typeface times = FontManager.load(DocxFontAssets.pathFor("Times New Roman", style));
        if (times != null) return styled(times, style);
        if (name == null || name.length() == 0) return Typeface.DEFAULT;
        Typeface requested = Typeface.create(name, style);
        return requested == null ? Typeface.DEFAULT : requested;
    }

    private static boolean isCjkFamily(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("simsun") || lower.contains("fangsong") || lower.contains("kaiti")
                || lower.contains("yahei") || lower.contains("hei") || lower.contains("宋")
                || lower.contains("楷") || lower.contains("仿宋") || lower.contains("黑体")
                || lower.contains("华") || lower.contains("隶") || lower.contains("明")
                || lower.contains("mincho") || lower.contains("ming")
                || lower.contains("stxinwei") || lower.contains("stliti");
    }

    private static Typeface styled(Typeface face, int style) {
        if (face == null) return Typeface.DEFAULT;
        if (style == Typeface.NORMAL || face.getStyle() == style) return face;
        Typeface result = Typeface.create(face, style);
        FontManager.associate(result, face);
        return result;
    }

    private static boolean containsHan(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '\u3400' && c <= '\u9fff') || (c >= '\uf900' && c <= '\ufaff')) return true;
        }
        return false;
    }

    public static float defaultFontSizePoints(DocxDocument.ParagraphBlock paragraph) {
        return effectiveDefaultPt(paragraph);
    }

    public static Typeface defaultTypeface(DocxDocument.ParagraphBlock paragraph) {
        return resolve(effectiveDefaultFamily(paragraph));
    }

    /**
     * Minimum line-box height in document coordinates for a paragraph that has
     * no text layout (for example an inline-picture paragraph). It deliberately
     * uses the same rounding and docGrid rules as the Spacing span above.
     */
    public static float minimumLineHeight(DocxDocument.ParagraphBlock paragraph, int lineGridPitchTwips) {
        if (paragraph == null) return 1f;
        float singleLinePt = wordSingleLineHeightPt(paragraph);
        int singlePx = Math.max(1, Math.round(singleLinePt * PageGeometry.points(1)));
        int lineTwips = paragraph.format.lineSpacingTwips;
        String rule = paragraph.format.lineRule == null ? "" : paragraph.format.lineRule;
        int desired;
        if ("exact".equalsIgnoreCase(rule)) {
            desired = Math.max(1, Math.round(PageGeometry.twips(lineTwips) ));
        } else if ("atLeast".equalsIgnoreCase(rule)) {
            desired = Math.max(singlePx, Math.round(PageGeometry.twips(lineTwips)));
        } else if (lineTwips > 0) {
            desired = Math.max(1, Math.round(singlePx * lineTwips / 240f));
        } else {
            desired = singlePx;
        }
        if (paragraph.format.snapToGrid && lineGridPitchTwips > 0)
            desired = Math.max(desired,
                    Math.max(1, Math.round(PageGeometry.twips(lineGridPitchTwips)) + 1));
        return desired;
    }

    /** Applies the same Word line-height rule to an editable Spannable. */
    public static void applyParagraphLineSpacing(SpannableStringBuilder text,
                                                  DocxDocument.ParagraphBlock paragraph,
                                                  float pxPerPoint) {
        if (text == null || text.length() == 0 || paragraph == null) return;
        if (paragraph.format.lineSpacingTwips > 0) {
            float scale = pxPerPoint / PageGeometry.points(1f);
            text.setSpan(new Spacing(paragraph.format, scale,
                            wordSingleLineHeightPt(paragraph), wordAscentFraction(paragraph), -1,
                            lineHeightMeasured(paragraph)),
                    0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }
    }

    public static void applyParagraphLineSpacing(SpannableStringBuilder text,
                                                  DocxDocument.ParagraphBlock paragraph) {
        applyParagraphLineSpacing(text, paragraph, PageGeometry.points(1f));
    }

    /** Word spacing: line=240 is one line; exact/atLeast are twips. */
    private static final class Spacing implements LineHeightSpan {
        private final int lineTwips;
        private final String rule;
        private final float coordinateScale;
        private final float ascentFraction;
        private final int lineGridPitchTwips;
        private boolean computed;
        private int desiredHeight;
        private int gridPitchPx;
        /** 分页要补的那一小截小数，见 Paragraph.lineCarry。 */
        private float lineCarry;
        /** 末行允许垂到版心以下的量，见 Paragraph.lineHang。 */
        private float lineHang;
        private boolean clipScripts;
        /** 行高走 Word 实测值的那一段：长高的行不许补齐到整格，见 ScriptGeometry.lineBox。 */
        private boolean gridSnapGrownRow = true;

        Spacing(DocxDocument.ParagraphFormat f, float coordinateScale,
                float singleLineHeightPt, float ascentFrac, int lineGridPitchTwips, boolean measuredAdvance) {
            lineTwips = f.lineSpacingTwips;
            rule = f.lineRule == null ? "" : f.lineRule;
            this.coordinateScale = coordinateScale;
            this.ascentFraction = ascentFrac;
            this.lineGridPitchTwips = lineGridPitchTwips;
            // Word uses two-step rounding:
            //   step1: singlePx = round(singleLineHeightPt × 96/72 × scale)
            //   step2: desired = round(singlePx × line/240)
            int singlePx = Math.max(1, Math.round(singleLineHeightPt * PageGeometry.points(1) * coordinateScale));
            if ("exact".equalsIgnoreCase(rule)) {
                desiredHeight = Math.max(1, Math.round(PageGeometry.twips(lineTwips) * coordinateScale));
            } else if ("atLeast".equalsIgnoreCase(rule)) {
                desiredHeight = Math.max(singlePx,
                        Math.round(PageGeometry.twips(lineTwips) * coordinateScale));
            } else if (lineTwips > 0) {
                desiredHeight = Math.max(1, Math.round(singlePx * lineTwips / 240f));
            } else {
                desiredHeight = singlePx;
            }
            int autoHeight = desiredHeight;
            // Word's generated TOC uses a right-aligned dot leader in a
            // section with a document grid. Word keeps those TOC baselines on
            // the section grid even though the generated paragraph explicitly
            // carries snapToGrid=false. Without this exception Android uses
            // only the 288-twip auto line value and fits 5/5.1/5.2 on page 1.
            boolean tocLeaderGrid = f.rightTabTwips > 0
                    && f.tabLeader != null && f.tabLeader.length() > 0
                    && "auto".equalsIgnoreCase(rule);
            if ((f.snapToGrid || tocLeaderGrid) && lineGridPitchTwips > 0) {
                int gridHeight = Math.max(1,
                        Math.round(PageGeometry.twips(lineGridPitchTwips) * coordinateScale));
                // Word's body paragraphs with snapToGrid use the next
                // document-coordinate pixel after the grid conversion; the
                // fractional remainder is carried by A4Paginator below.
                if (f.snapToGrid && !tocLeaderGrid) gridHeight += 1;
                gridPitchPx = gridHeight;
                if (desiredHeight < gridHeight) desiredHeight = gridHeight;
            }
            // Word clips raised text under exact line spacing and grows the line
            // for it under auto, multiple and at-least spacing.
            clipScripts = "exact".equalsIgnoreCase(rule);
            /* Word 的 auto 行距是一步算完的小数：单一行高 × w:line/240，中间不取整。我们原来按
               "两步取整"建模（先把单一行高取整再乘比例），那个模型对得上我们自己的旧数，对不上 Word：
               宋体 12pt / w:line=300 Word 量到 26.267px，两步取整只给 26。差的那截按行累计，
               一页 27 行差 7.2px。网格地板真的把行顶起来时不补——那是另一种情形，还没量过。 */
            boolean autoRule = !"exact".equalsIgnoreCase(rule) && !"atLeast".equalsIgnoreCase(rule);
            if (measuredAdvance && autoRule && desiredHeight == autoHeight && singleLineHeightPt > 0f) {
                float advance = WordLineHeights.advancePx(singleLineHeightPt, lineTwips, coordinateScale);
                float carry = advance - desiredHeight;
                if (Math.abs(carry) < 1f) {
                    /* 这一段的行高来自 Word 实测值，网格那两条都不许再插手：整行跳格与这 0.27px
                       是同一件事的两种写法，叠在一起就是真机量到的 25.5px -> 50.267px。 */
                    gridSnapGrownRow = false;
                    lineCarry = carry;
                    // 上伸按 chooseHeight 画出来的那个整数算，两者必须同源，否则版心还回去的量
                    // 与屏幕上真正的基线位置就对不上。
                    lineHang = advance - Math.max(1, Math.round(desiredHeight * ascentFraction));
                }
            }
            computed = true;
        }

        @Override public void chooseHeight(CharSequence text, int start, int end,
                                           int spanstartv, int v, Paint.FontMetricsInt fm) {
            // Distribute line box: baseline at ascentFraction from top
            int ascent = Math.max(1, Math.round(desiredHeight * ascentFraction));
            int[] need = scriptSpace(text, start, end);
            int[] box = ScriptGeometry.lineBox(ascent, Math.max(0, desiredHeight - ascent),
                    need[0], need[1], gridPitchPx, clipScripts, gridSnapGrownRow);
            fm.ascent = -box[0];
            fm.descent = box[1];
            fm.top = fm.ascent;
            fm.bottom = fm.descent;
        }
    }

    /**
     * Line-box demand of the raised and lowered runs on one line, in document
     * pixels. Zero keeps the paragraph's own box, so plain text never grows.
     *
     * The two kinds of raised text are not the same question and Word bills them
     * differently, which the lab in artifacts/agent-layout-fix/lab measured line by line:
     * a w:vertAlign run competes with the box of its own face at the size it declares and its
     * baseline shift buys it nothing, while text raised by w:position (same size, lifted) does
     * push the row. So a citation superscript or a formula subscript written at the body size -
     * every vertAlign run in tests/samples/input-liu.docx - never grows a row here, exactly as in
     * Word, and the row only grows when the script run is bigger than its paragraph.
     */
    private static int[] scriptSpace(CharSequence text, int start, int end) {
        int ascent = 0, descent = 0;
        if (!(text instanceof Spanned) || end <= start) return new int[]{0, 0};
        Spanned spanned = (Spanned) text;
        for (WordScriptSpan span : spanned.getSpans(start, end, WordScriptSpan.class)) {
            // Unicode super/subscript glyphs carry their own height and are drawn
            // full size, so the ordinary line box already contains them.
            if (span.isUnicode()) continue;
            float base = span.baseSizePx();
            ScriptGeometry g = ScriptGeometry.declared(metricsFor(span.family()));
            ascent = Math.max(ascent, Math.round(g.ascentPx(base)));
            descent = Math.max(descent, Math.round(g.descentPx(base)));
        }
        for (PositionSpan span : spanned.getSpans(start, end, PositionSpan.class)) {
            int at = spanned.getSpanStart(span);
            float base = runSizePx(spanned, at, 0f);
            if (base <= 0f) continue;
            ScriptGeometry g = ScriptGeometry.raised(span.shiftPx() / base, metricsFor(familyAt(spanned, at)));
            ascent = Math.max(ascent, Math.round(g.ascentPx(base)));
            descent = Math.max(descent, Math.round(g.descentPx(base)));
        }
        return new int[]{ascent, descent};
    }

    private static float runSizePx(Spanned text, int offset, float fallback) {
        if (offset < 0 || offset >= text.length()) return fallback;
        for (PointSizeSpan size : text.getSpans(offset, offset + 1, PointSizeSpan.class))
            return size.pixels();
        return fallback;
    }

    private static String familyAt(Spanned text, int offset) {
        if (offset < 0 || offset >= text.length()) return "Times New Roman";
        for (MeasuredFontSpan span : text.getSpans(offset, offset + 1, MeasuredFontSpan.class))
            return span.family();
        for (FontSpan span : text.getSpans(offset, offset + 1, FontSpan.class))
            if (span.getFamily() != null && span.getFamily().length() > 0) return span.getFamily();
        return "Times New Roman";
    }

    /**
     * Word's autoSpaceDE / autoSpaceDN: a CJK character next to Latin or a
     * digit gets one quarter of the East Asian em as extra gap. Without it a
     * line holds one extra CJK character and the page gains a line.
     */
    private static void applyAutoSpace(Spannable text, DocxDocument.ParagraphFormat format) {
        if (text == null || (!format.autoSpaceDe && !format.autoSpaceDn) || text.length() < 2) return;
        int flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
        int i = 0;
        while (i < text.length()) {
            int kind = scriptKind(text.charAt(i));
            int next = i + 1;
            while (next < text.length() && scriptKind(text.charAt(next)) == kind) next++;
            if (kind != 0 && next < text.length()) {
                int right = scriptKind(text.charAt(next));
                boolean latin = (kind == 1 && right == 2) || (kind == 2 && right == 1);
                boolean digit = (kind == 1 && right == 3) || (kind == 3 && right == 1);
                // Word spaces CJK *text* against Latin/digits. CJK punctuation
                // (，。、…) on either side of the boundary takes no auto gap;
                // measured against the Kim/Su paragraphs it is what makes Word
                // fit one more character per line than the old rule.
                boolean punctEdge = (kind == 1 && isCjkPunctuation(text.charAt(next - 1)))
                        || (right == 1 && isCjkPunctuation(text.charAt(next)));
                if (!punctEdge && ((latin && format.autoSpaceDe) || (digit && format.autoSpaceDn))) {
                    // The quarter em rides the CJK side of the seam, and only that side. A
                    // ReplacementSpan is a measurement-run edge for StaticLayout and every edge is a
                    // break opportunity, so carrying the gap on the Latin character let the breaker cut
                    // the word in half: "SEM形貌" came out "SE|M", "Ag3Sn分布" "Ag3S|n", "SAC305浸渗"
                    // "SAC30|5", "构建Cu/SB/P-Cu/SB/Cu夹层结构" "...SB/C|u夹层结构". 15 lines of the
                    // thesis split a Latin or digit token that way (counted in
                    // artifacts/agent-layout-verify/revert-linear/new/lines-all.tsv: a line whose last
                    // character and the next line's first character are both [A-Za-z0-9] with no blank
                    // between them in tests/samples/input-liu.docx, where Word's own exported PDF cuts a
                    // run on none of its 803 lines). Anchored on the CJK character, both edges around it
                    // are edges Word itself may break at.
                    int at = kind == 1 ? next - 1 : next;
                    int end = Math.min(text.length(), at + 1);
                    boolean before = kind != 1;   // kind == 1: CJK on the left, so the gap is behind it
                    boolean after = !before;
                    for (AutoGap has : text.getSpans(at, end, AutoGap.class)) {
                        // A CJK character with Latin on both sides carries both seams on one span: two
                        // ReplacementSpans on one range and the platform sizes it with only one of them.
                        before |= has.before;
                        after |= has.after;
                        text.removeSpan(has);
                    }
                    text.setSpan(new AutoGap(before, after), at, end, flags);
                }
            }
            i = next;
        }
    }

    /** 0 other, 1 CJK, 2 Latin, 3 digit. */
    private static int scriptKind(char c) {
        if (isCjk(c)) return 1;
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) return 2;
        if (c >= '0' && c <= '9') return 3;
        return 0;
    }

    /** A share of a justified line's slack, added after the spanned character. */
    /** A character holding its laid-out advance plus the whole pixels of justification slack. */
    /** Ceiling on how far one seam may open, in whole pixels. */
    private static final int MAX_GAP_STRETCH_PX = 6;
    /** How far one seam may go before the line opens another one: two pixels still reads as flush. */
    private static final int MAX_GAP_STRETCH_STEP_PX = 2;
    /**
     * A word space has no such ceiling in Word: when a Latin line holds few words, the leftover is
     * spread over those spaces however big it is. This bound only stops a pathological line (a
     * single long token on an otherwise empty line) from opening a hole across the page.
     */
    private static final int MAX_WORD_SPACE_STRETCH_PX = 24;

    static final class WidenGap extends ReplacementSpan {   // package-private: the device probe counts it
        private final int basePx;
        private final int extraPx;
        private final boolean before;
        WidenGap(int basePx, int extraPx, boolean before) {
            this.basePx = basePx; this.extraPx = extraPx; this.before = before;
        }
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return basePx + extraPx;
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, Paint paint) {
            // basePx already carries whatever autoSpace seam this character was holding, and that seam
            // stays where it was: the slack is added outside it, on the same side.
            float shift = before ? paint.getTextSize() / 4f : 0f;
            canvas.drawText(text, start, end, x + shift, y, paint);
        }
    }

    /**
     * The run of blanks a wrap left at the end of a justified line, measured as nothing. Word keeps
     * them past the text boundary and takes no width for them -- its line width stops at the last
     * character that has ink -- and a blank has no ink either, so nothing on the page changes; only
     * the numbers the layout reports start to mean what Word's mean.
     */
    static final class BlankTail extends ReplacementSpan {   // package-private: the device probe reads it
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return 0;
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, Paint paint) {
            // Nothing to draw: the range holds blanks and it has no advance to draw them in.
        }
    }

    /**
     * One or two quarter-em autoSpace seams riding one CJK character: `before` is the share that
     * belongs to the left of it, `after` the share on the right. The measured total is the same sum the
     * single-sided span measured -- ceil(advance + seams * em/4) -- so no line gains or loses room from
     * this, and only the place the breaker may cut at and the place the ink is drawn move.
     */
    static final class AutoGap extends ReplacementSpan {   // package-private: the device probe counts it
        final boolean before;
        final boolean after;
        AutoGap(boolean before, boolean after) { this.before = before; this.after = after; }
        private int seams() { return (before ? 1 : 0) + (after ? 1 : 0); }
        /** How far the glyph steps right inside its own advance: the seams that sit on its left. */
        float shift(Paint paint) { return before ? paint.getTextSize() / 4f : 0f; }
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return (int) Math.ceil(paint.measureText(text, start, end) + seams() * paint.getTextSize() / 4f);
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, Paint paint) {
            canvas.drawText(text, start, end, x + shift(paint), y, paint);
        }
    }

    /**
     * Word's hanging punctuation: the trailing mark is measured by its ink so
     * the line can also hold the character before it; drawing shows the full
     * glyph, so mid-line positions are unchanged.
     */
    private static final class PunctInkWidth extends ReplacementSpan {
        private final float inkFraction;
        PunctInkWidth(float inkFraction) { this.inkFraction = inkFraction; }
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return Math.max(1, (int) Math.ceil(paint.measureText(text, start, end) * inkFraction));
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, Paint paint) {
            canvas.drawText(text, start, end, x, y, paint);
        }
    }

    /**
     * Word TOC leader: a right-aligned tab fills the gap with dots and pins the run that follows it
     * so that it ENDS on the declared stop. Reserving only the heading leaves the number starting at
     * the stop and hanging past it, which is what Word does not do.
     *
     * The run a right stop right-aligns ends at the NEXT tab, not at the end of the paragraph, and a
     * stop is not spent once per paragraph. Word 16.0 on "<num><TAB><title><TAB><page>"
     * (artifacts/agent-layout-fix/toc1/word2tab: its own PDF plus a character dump of the same page,
     * entry 1.1; page x = the 84.9pt margin + the values below): line 1 is the number, dots from
     * 120.02pt to 438.07pt, the title ending on the 493.17pt stop. Nothing fits behind the stop, so
     * the page number falls to line 2, where the tab sits at the left indent (104.60pt), draws dots
     * 105.02..486.07pt, and the number ends on that SAME stop again (493.42pt).
     *
     * StaticLayout measures a span once and never says which line it landed on, so the first tab is
     * measured from the paragraph start and every tab after it is measured as if its own line began
     * at the paragraph indent -- exactly where Word's overflow puts it: after the first tab the cursor
     * sits on the stop, and the only stop ahead of it is on the next line.
     *
     * scale converts 96-DPI document units into the pixels of whoever lays this out: 1.0 for the
     * paged view and the reading view, the fit-to-screen factor for the editor.
     */
    private static final class LeaderTab extends ReplacementSpan {
        private final int stopPx;
        private final char leader;
        private final int runEnd;   // exclusive: the next tab, or the end of the paragraph
        private final boolean ownLine;  // measured from this tab' own line start, not the paragraph start
        private float gap = 1f;   // laid-out dot run, from the heading end to the number start
        LeaderTab(int stopTwips, String leaderName, float indentPx, float scale, int runEnd,
                  boolean ownLine) {
            stopPx = Math.max(1, Math.round(PageGeometry.twips(stopTwips) * scale - indentPx));
            this.runEnd = Math.max(0, runEnd);
            this.ownLine = ownLine;
            leader = "dot".equalsIgnoreCase(leaderName) ? '.'
                    : "underscore".equalsIgnoreCase(leaderName) ? '_'
                    : "hyphen".equalsIgnoreCase(leaderName) ? '-'
                    : "middleDot".equalsIgnoreCase(leaderName) ? '·' : ' ';
        }
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            float heading = ownLine ? 0f : spannedWidth(text, (TextPaint) paint, 0, start);
            // What the stop right-aligns is this tab' own run: up to the next tab, or to the end.
            float number = spannedWidth(text, (TextPaint) paint, end, Math.min(runEnd, text.length()));
            if (number < 0.5f) { gap = 1f; return 1; }   // nothing follows this tab: Word spends no room on it
            gap = Math.max(1f, stopPx - heading - number);
            return Math.round(gap);
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, Paint paint) {
            if (leader == ' ') return;
            float width = paint.measureText(String.valueOf(leader));
            if (width < 0.5f) return;
            float limit = x + gap;
            for (float cursor = x; cursor + width <= limit; cursor += width)
                canvas.drawText(String.valueOf(leader), cursor, y, paint);
        }
    }

    /**
     * Attaches the leaders. Word owns a leader to a tab STOP rather than to a paragraph, so every tab
     * in the line has to be placed: the first one right-aligns its own run (up to the next tab) on the
     * declared stop, and a tab behind it gets that same stop on a line of its own, dots and all.
     *
     * Both styledText builders call this, so the paged view, the reading view and the editor get the
     * same dots on the same stop. Before, only measure() had them and the reading view put TOC page
     * numbers on Android's own default tab stop with no dots at all -- that is the report of "the
     * numbers in the table of contents sit in the wrong place".
     */
    static void applyTabLeaders(SpannableStringBuilder text, DocxDocument.ParagraphBlock paragraph,
                                float pxPerPoint) {
        DocxDocument.ParagraphFormat f = paragraph == null ? null : paragraph.format;
        if (text == null || text.length() == 0 || f == null || f.rightTabTwips <= 0) return;
        float scale = pxPerPoint <= 0f ? 1f : pxPerPoint / PageGeometry.points(1f);
        float indentPx = PageGeometry.twips(f.leftIndentTwips == -1 ? 0 : f.leftIndentTwips) * scale;
        String value = text.toString();
        boolean firstTab = true;
        for (int at = value.indexOf('\t'); at >= 0; at = value.indexOf('\t', at + 1)) {
            if (text.getSpans(at, at + 1, LeaderTab.class).length > 0) continue;
            int next = value.indexOf('\t', at + 1);
            int runEnd = next >= 0 ? next : value.length();
            text.setSpan(new LeaderTab(f.rightTabTwips, f.tabLeader, indentPx, scale, runEnd, !firstTab),
                    at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            firstTab = false;
        }
    }

    private static final class ListMargin implements LeadingMarginSpan {
        final String label; final int first, rest;
        ListMargin(String label, int first, int rest) { this.label = label; this.first = first; this.rest = rest; }
        public int getLeadingMargin(boolean isFirst) { return (isFirst ? first : rest) + 24; }
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
                                      CharSequence text, int start, int end, boolean isFirst, Layout layout) {
            if (start == 0) c.drawText(label, x + dir * first, baseline, p);
        }
    }
    private DocxTextLayout() { }
}
