package com.rikkahub.wordlite.probe;

import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.TextDirectionHeuristics;
import android.text.style.ReplacementSpan;
import android.graphics.Canvas;

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;

/**
 * Does Android 10 honor StaticLayout.Builder.setBreakIterator at all?
 *
 * Word truth (tools/word-break-truth.ps1, artifacts/word-break/word-lines.tsv) says a Latin/digit run
 * is one unbreakable block, "-" is a break opportunity and "/" is not. The platform breaker gives the
 * opposite ("/" breaks, "-" moves the whole run down), and the engine has no way to say otherwise
 * unless a custom BreakIterator is read by the layout. This probe answers that on the device:
 * four modes per case, break offsets printed.
 *
 *   1 default          the builder as DocxTextLayout.build() uses it today
 *   2 word-iterator    + setBreakIterator(wordRules)
 *   3 word+span        same iterator, text carries a ReplacementSpan over one CJK char (an AutoGap seam)
 *   4 span only        default breaker, same spanned text (control for mode 3)
 */
public class BreakIteratorProbe {

    static final class Seam extends ReplacementSpan {
        final int extra;
        Seam(int extra) { this.extra = extra; }
        public int getSize(Paint paint, CharSequence t, int s, int e, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return Math.round(paint.measureText(t, s, e)) + extra;
        }
        public void draw(Canvas c, CharSequence t, int s, int e, float x, int top, int y, int b, Paint p) {
            c.drawText(t, s, e, x, y, p);
        }
    }

    /**
     * Word's break rules, the candidate engine implementation. Subclasses java.text.BreakIterator
     * only to get the type the framework setter expects; the offset bookkeeping is our own field,
     * because BreakIterator's own `current` is package-private.
     */
    public static class WordBreaks extends java.text.BreakIterator {
        private CharSequence text = "";
        private int base = 0;      // offset the framework's CharacterIterator started at
        private int at = 0;        // absolute offset in the paragraph text
        public WordBreaks() { }
        public WordBreaks(CharSequence text) { setText(text); }
        public void setText(CharSequence t) { text = t; base = 0; at = 0; }
        public void setText(CharacterIterator ci) {
            StringBuilder sb = new StringBuilder();
            for (char c = ci.first(); c != CharacterIterator.DONE; c = ci.next()) {
                sb.append(c);
            }
            text = sb.toString();
            base = ci.getBeginIndex();
            at = base;
        }
        public CharacterIterator getText() { return new StringCharacterIterator(text.toString()); }
        public int first() { at = base; return next(); }
        public int last() { at = base + text.length(); return at; }
        public int current() { return at; }
        public int next() {
            int i = at - base + 1;
            while (i < text.length() && !isWordBreak(text, i)) i++;
            at = i < text.length() ? base + i : DONE;
            return at;
        }
        public int next(int n) {
            int r = at;
            for (int k = 0; k < n; k++) r = next();
            return r;
        }
        public int previous() {
            int i = at - base - 1;
            while (i > 0 && !isWordBreak(text, i)) i--;
            at = base + i;
            return at;
        }
        public int following(int offset) {
            WordBreaks c = (WordBreaks) clone();
            c.at = offset;
            return c.next();
        }
        public boolean isBoundary(int offset) {
            int i = offset - base;
            return i <= 0 || i >= text.length() || isWordBreak(text, i);
        }
        public Object clone() {
            WordBreaks b = new WordBreaks(text);
            b.base = base; b.at = at;
            return b;
        }
    }

    static boolean isCjk(char c) {
        int o = c;
        return (o >= 0x2E80 && o <= 0x9FFF) || (o >= 0xF900 && o <= 0xFAFF)
                || (o >= 0xFF00 && o <= 0xFF60) || (o >= 0x3000 && o <= 0x303F);
    }
    static boolean isAlnum(char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'); }

    static boolean isWordBreak(CharSequence t, int i) {
        if (i <= 0 || i >= t.length()) return true;
        char p = t.charAt(i - 1), n = t.charAt(i);
        if (Character.isWhitespace(p)) return !Character.isWhitespace(n);   // break after a blank run
        if (Character.isWhitespace(n)) return false;
        boolean pcjk = isCjk(p), ncjk = isCjk(n);
        if (pcjk && ncjk) {
            if ("\uFF0C\u3002\u3001\uFF1B\uFF1A\uFF01\uFF1F\u300D\u300B\u201D\u2019\uFF09".indexOf(n) >= 0) return false;
            if ("\u300C\u300A\u201C\u2018\uFF08".indexOf(p) >= 0) return false;
            return true;
        }
        if (pcjk != ncjk) return true;                       // CJK next to Latin/digits
        if (p == '-' && isAlnum(n) && i - 2 >= 0 && isAlnum(t.charAt(i - 2))) return true;
        return false;                                        // inside a token, after "/", anywhere else
    }

    static int reflectFailures = 0;
    static boolean DUMP = true;

    /**
     * Mode 7/8: PrecomputedText is the other way in -- it carries break data that StaticLayout reads.
     * Its Params.Builder.setBreakIterator is also gone from the SDK stubs, so it too is reached by
     * reflection. measuringPt=false asks for break data only: the engine measures with per-run spans,
     * and a metrics mismatch would make StaticLayout throw instead of laying out.
     */
    static String precomputed(CharSequence text, TextPaint paint, int width,
                              java.text.BreakIterator it, int strategy) {
        try {
            Class<?> paramsCls = Class.forName("android.text.PrecomputedText$Params$Builder");
            if (DUMP) {
                for (java.lang.reflect.Constructor<?> c : paramsCls.getConstructors()) {
                    StringBuilder cs = new StringBuilder("  Params.Builder(");
                    for (Class<?> t : c.getParameterTypes()) cs.append(t.getSimpleName()).append(',');
                    System.out.println(cs.append(')').toString());
                }
                for (java.lang.reflect.Method m : paramsCls.getMethods()) {
                    if (m.getName().contains("Break") || m.getName().contains("Measuring")) {
                        StringBuilder ms = new StringBuilder("  ").append(m.getName()).append('(');
                        for (Class<?> t : m.getParameterTypes()) ms.append(t.getSimpleName()).append(',');
                        System.out.println(ms.append(')').toString());
                    }
                }
                DUMP = false;
            }
            Object pb = paramsCls.getConstructor(android.text.TextPaint.class).newInstance(paint);
            if (it != null) {
                java.lang.reflect.Method sbi =
                        paramsCls.getMethod("setBreakIterator", java.text.BreakIterator.class);
                pb = sbi.invoke(pb, it);
            }
            Object params = paramsCls.getMethod("build").invoke(pb);
            Object pt = Class.forName("android.text.PrecomputedText")
                    .getMethod("createFrom", CharSequence.class,
                            Class.forName("android.text.PrecomputedText$Params"))
                    .invoke(null, text, params);
            StaticLayout l = build((CharSequence) pt, paint, width, null, strategy);
            return breaks(l);
        } catch (Throwable t) {
            return "ERR:" + (t.getCause() == null ? t.toString() : t.getCause().toString());
        }
    }

    static StaticLayout build(CharSequence text, TextPaint paint, int width,
                              java.text.BreakIterator it, int strategy) throws Exception {
        StaticLayout.Builder b = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setBreakStrategy(strategy)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        if (it != null) {
            // setBreakIterator is deprecated since API 24 and gone from the SDK stubs, so it can only
            // be reached by reflection. Whether the platform still reads it is the whole question.
            try {
                java.lang.reflect.Method m = StaticLayout.Builder.class.getMethod(
                        "setBreakIterator", java.text.BreakIterator.class);
                m.invoke(b, it);
            } catch (Exception e) {
                reflectFailures++;
                System.out.println("  setBreakIterator unavailable: " + e);
            }
        }
        return b.build();
    }

    static String breaks(StaticLayout l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.getLineCount() - 1; i++) {
            if (i > 0) sb.append(',');
            sb.append(l.getLineEnd(i));
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        // hwui installs its default typeface during Typeface's class init; without this any
        // measureText() under app_process aborts the process (see DeviceCapture.main).
        Class.forName("android.graphics.Typeface");
        int width = 567;                       // the thesis text column, document px
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(16f);                // 12 pt in document units

        String ce = repeat('\u6d4b', 20);      // 20 Chinese characters = 320 px
        java.util.LinkedHashMap<String, String> cases = new java.util.LinkedHashMap<String, String>();
        cases.put("hyphen-edge", ce + "ABCDEFGHIJ-KLMNOPQRSTUVWXYZ" + repeat('\u8bd5', 40));
        cases.put("slash-edge", ce + "ABCDEFGHIJ/KLMNOPQRSTUVWXYZ" + repeat('\u8bd5', 40));
        cases.put("token-absent", ce + "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + repeat('\u8bd5', 40));
        cases.put("plain-60A", repeat('\u6d4b', 6) + repeat('A', 60) + repeat('\u8bd5', 40));
        cases.put("thesis-cu", "\u6784\u5efa" + "Cu/SB/P-Cu/SB/Cu"
                + "\u5939\u5c42\u7ed3\u6784\uff0c\u5e76\u5728\u5939\u5c42\u7ed3\u6784"
                + "\u4e2d\u8bbe\u7f6e" + repeat('\u6d4b', 30));

        System.out.println("sdk=" + android.os.Build.VERSION.SDK_INT);
        System.out.println("width=" + width + " textSize=16 mode: 1=default 2=word-iterator 3=word+span 4=span-only 5=simple 6=balanced 7=precomputed+word-iterator 8=precomputed+default");
        for (String name : cases.keySet()) {
            String src = cases.get(name);
            SpannableStringBuilder sp = new SpannableStringBuilder(src);
            int seam = src.indexOf('\u5939');
            if (seam < 0) seam = 4;
            sp.setSpan(new Seam(4), seam, seam + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            WordBreaks it = new WordBreaks(src);
            WordBreaks it2 = new WordBreaks(sp);
            System.out.println(name
                    + "\t1=" + breaks(build(src, paint, width, null, Layout.BREAK_STRATEGY_HIGH_QUALITY))
                    + "\t2=" + breaks(build(src, paint, width, it, Layout.BREAK_STRATEGY_HIGH_QUALITY))
                    + "\t3=" + breaks(build(sp, paint, width, it2, Layout.BREAK_STRATEGY_HIGH_QUALITY))
                    + "\t4=" + breaks(build(sp, paint, width, null, Layout.BREAK_STRATEGY_HIGH_QUALITY))
                    + "\t5=" + breaks(build(src, paint, width, null, Layout.BREAK_STRATEGY_SIMPLE))
                    + "\t6=" + breaks(build(src, paint, width, null, Layout.BREAK_STRATEGY_BALANCED))
                    + "\t7=" + precomputed(src, paint, width, it, Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    + "\t8=" + precomputed(src, paint, width, null, Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    + "\tlen=" + src.length());
        }
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
