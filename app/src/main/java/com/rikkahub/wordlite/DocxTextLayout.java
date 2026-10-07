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
        int width = Math.max(1, (int) Math.floor(availableWidth - right - x));
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
        if (text.length() > 0) {
            text.setSpan(new Spacing(f, 1f, wordSingleLineHeightPt(paragraph),
                        wordAscentFraction(paragraph), lineGridPitchTwips),
                    0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            applyAutoSpace(text, f);
            int tabAt = text.toString().indexOf('\t');
            if (f.rightTabTwips > 0 && tabAt >= 0) {
                // Only the tab itself is a leader. Covering the whole paragraph
                // would replace the heading text with dots.
                text.setSpan(new LeaderTab(f.rightTabTwips, f.tabLeader, left),
                        tabAt, tabAt + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
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
        // Word's w:overflowPunct lets a trailing fullwidth punctuation mark hang
        // past the line width by its blank half, so punct-ended lines hold one
        // more character. Android's breaker cannot express that in a single
        // pass; a second pass narrows exactly the marks Word would hang.
        if (f.alignment == 3 && f.overflowPunct)
            layout = hangTrailingPunctuation(text, paint, layout, width, alignment, allowWordWrap);
        return new Paragraph(paragraph, layout, x, display);
    }

    private static StaticLayout build(Spannable text, TextPaint paint, int width,
                                      Layout.Alignment alignment, boolean justify, boolean wordWrap) {
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setIncludePad(false).setAlignment(alignment)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        if (Build.VERSION.SDK_INT >= 26) {
            // CJK body text has no spaces, so INTER_WORD cannot stretch those
            // lines and the right edge stays ragged; INTER_CHARACTER (API 34+)
            // matches Word's flush justified edge. Below 34 keep INTER_WORD.
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
                                                         Layout.Alignment alignment, boolean wordWrap) {
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
            layout = build(text, paint, width, alignment, true, wordWrap);
        }
        return layout;
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
        float maxH = 0;
        int baseHalf = p.baseRunStyle != null ? p.baseRunStyle.fontSizeHalfPoints : -1;
        String baseEA = p.baseRunStyle != null ? p.baseRunStyle.eastAsiaFontFamily : null;
        String baseLatin = p.baseRunStyle != null && p.baseRunStyle.asciiFontFamily != null
                ? p.baseRunStyle.asciiFontFamily
                : (p.baseRunStyle != null ? p.baseRunStyle.fontFamily : null);
        for (DocxDocument.Run run : p.runs) {
            if (run.text.trim().isEmpty()) continue;
            int half = run.style.fontSizeHalfPoints > 0 ? run.style.fontSizeHalfPoints : baseHalf;
            if (half <= 0) half = 24; // Word default
            float pt = half / 2f;
            String ea = run.style.eastAsiaFontFamily != null ? run.style.eastAsiaFontFamily : baseEA;
            String latin = run.style.asciiFontFamily != null ? run.style.asciiFontFamily : baseLatin;
            float eaRatio = ea != null ? metricsFor(ea).lineHeightRatio : 1.0f;
            float latinRatio = latin != null ? metricsFor(latin).lineHeightRatio : 1.0f;
            float runH = pt * Math.max(eaRatio, latinRatio);
            if (runH > maxH) maxH = runH;
        }
        if (maxH <= 0) {
            float pt = baseHalf > 0 ? baseHalf / 2f : effectiveDefaultPt(p);
            String ea = baseEA != null ? baseEA : effectiveDefaultFamily(p);
            String latin = baseLatin != null ? baseLatin : effectiveDefaultFamily(p);
            float eaR = metricsFor(ea).lineHeightRatio;
            float latinR = metricsFor(latin).lineHeightRatio;
            maxH = pt * Math.max(eaR, latinR);
        }
        return maxH;
    }

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
            // Word changes the glyph size and baseline for w:vertAlign, but keeps
            // the run's original advance for line breaking. Measuring with the
            // base paint here while draw() uses the reduced paint prevents a
            // superscript citation from pulling the following CJK glyph left.
            copy.setTextSize(paint.getTextSize());
            return (int) Math.ceil(copy.measureText(value(text, start, end)));
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
                    wordSingleLineHeightPt(paragraph), wordAscentFraction(paragraph), -1),
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

        Spacing(DocxDocument.ParagraphFormat f, float coordinateScale,
                float singleLineHeightPt, float ascentFrac, int lineGridPitchTwips) {
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
                if (desiredHeight < gridHeight) desiredHeight = gridHeight;
            }
            computed = true;
        }

        @Override public void chooseHeight(CharSequence text, int start, int end,
                                           int spanstartv, int v, Paint.FontMetricsInt fm) {
            // Distribute line box: baseline at ascentFraction from top
            int ascent = Math.max(1, Math.round(desiredHeight * ascentFraction));
            fm.ascent = -ascent;
            fm.descent = desiredHeight - ascent;
            fm.top = fm.ascent;
            fm.bottom = fm.descent;
        }
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
                    // Put the extra width on the character to the left of the
                    // boundary. ReplacementSpan draws its extra width after
                    // the character, so placing it on the right-hand CJK
                    // character would move the gap to the wrong side.
                    int at = next - 1;
                    int end = Math.min(text.length(), at + 1);
                    if (text.getSpans(at, end, AutoGap.class).length == 0)
                        text.setSpan(new AutoGap(), at, end, flags);
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

    /** Adds one quarter of the current East Asian em after the spanned character. */
    private static final class AutoGap extends ReplacementSpan {
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return (int) Math.ceil(paint.measureText(text, start, end) + paint.getTextSize() / 4f);
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, Paint paint) {
            canvas.drawText(text, start, end, x, y, paint);
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
     * Word TOC leader: a right-aligned tab fills the gap with dots and pins
     * the page number to the declared tab stop.
     */
    private static final class LeaderTab extends ReplacementSpan {
        private final int stopPx;
        private final char leader;
        LeaderTab(int stopTwips, String leaderName, float indentPx) {
            stopPx = Math.max(1, Math.round(PageGeometry.twips(stopTwips) - indentPx));
            leader = "dot".equalsIgnoreCase(leaderName) ? '.'
                    : "underscore".equalsIgnoreCase(leaderName) ? '_'
                    : "hyphen".equalsIgnoreCase(leaderName) ? '-'
                    : "middleDot".equalsIgnoreCase(leaderName) ? '·' : ' ';
        }
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            float before = start == 0 ? 0 : paint.measureText(text, 0, start);
            return Math.max(1, Math.round(stopPx - before));
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, Paint paint) {
            if (leader == ' ') return;
            float width = paint.measureText(String.valueOf(leader));
            if (width < 0.5f) return;
            float limit = stopPx;
            for (float cursor = x; cursor + width <= limit; cursor += width)
                canvas.drawText(String.valueOf(leader), cursor, y, paint);
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
