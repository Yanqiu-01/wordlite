package com.rikkahub.wordlite.probe;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;

/**
 * Is the cut inside "Ag3Sn" the same mechanism as the autoSpace one -- a ReplacementSpan edge is a
 * break opportunity -- or is the scaled script something the breaker treats specially?
 *
 * The thesis has "Ag" + subscript "3" + "Sn" as three OOXML runs, and the engine draws the script
 * with a ReplacementSpan over just the "3" (DocxTextLayout.WordScriptSpan). Measured on the phone, page 19:
 * the line ends "...、孔洞率、Ag3" and the next one starts "Sn分布". Word's exported PDF cuts that run on
 * none of its 803 lines (tools/break-class-truth.py: 串内断 0).
 *
 * Four variants of the same string, swept over the amount of Chinese in front of the token so the
 * line runs out at a known place:
 *   A none      no script span at all (control: where does the platform break an untouched token?)
 *   B script    scaled span over just the "3"      (what the engine does today)
 *   C plain     unscaled span over just the "3"    (isolates "it is an edge" from "it is a scaled box")
 *   D token     one mixed span over the whole "Ag3Sn", drawing the "3" scaled inside it
 *               (the candidate fix: the only edges are the ones Word may break at)
 *   E colour    ForegroundColorSpan over the "3" only      (no metric effect at all)
 *   F size      AbsoluteSizeSpan over the "3" only         (MetricAffectingSpan, not a replacement)
 *   G typeface  TypefaceSpan over the "3" only             (what FontSpan does at a run boundary)
 *
 *   H container the token-wide span on top of the per-run script span (can the fix be a container that
 *               delegates, instead of a span that takes the script's own drawing over?)
 *
 * E/F/G answer the wider question: the engine hangs more than script spans inside a token -- every OOXML
 * run boundary gets a FontSpan/MeasuredFontSpan, and a coloured run gets a ForegroundColorSpan. Only a
 * family that does NOT open a break can be left where it is.
 *
 * Output per line: variant, pad, token start offset, break offsets relative to the token start
 * (a value between 1 and len-1 means the token was cut).
 */
public class ScriptBreakProbe {

    static final String TOKEN = "Ag3Sn";          // "3" is the subscript character
    static final int SCRIPT_AT = 2;               // index of "3" inside TOKEN
    static final float SCRIPT_SCALE = 0.65f;      // Times New Roman OS/2 supsScale, measured on device
    static final float SCRIPT_RAISE = 0.45f;

    /** B: what the engine does today, minus the font lookup. */
    static final class ScriptOnOne extends ReplacementSpan {
        final float scale, raise;
        ScriptOnOne(float scale, float raise) { this.scale = scale; this.raise = raise; }
        public int getSize(Paint p, CharSequence t, int s, int e, Paint.FontMetricsInt fm) {
            if (fm != null) p.getFontMetricsInt(fm);
            Paint c = new Paint(p);
            c.setTextSize(p.getTextSize() * scale);
            return Math.max(1, (int) Math.ceil(c.measureText(t, s, e)));
        }
        public void draw(Canvas cv, CharSequence t, int s, int e, float x, int top, int y, int b, Paint p) {
            Paint c = new Paint(p);
            c.setTextSize(p.getTextSize() * scale);
            cv.drawText(t, s, e, x, y - p.getTextSize() * raise, c);
        }
    }

    /** C: same box but no scaling, so only the span edge differs from the control. */
    static final class PlainOnOne extends ReplacementSpan {
        public int getSize(Paint p, CharSequence t, int s, int e, Paint.FontMetricsInt fm) {
            if (fm != null) p.getFontMetricsInt(fm);
            return (int) Math.ceil(p.measureText(t, s, e));
        }
        public void draw(Canvas cv, CharSequence t, int s, int e, float x, int top, int y, int b, Paint p) {
            cv.drawText(t, s, e, x, y, p);
        }
    }

    /**
     * D: the candidate fix. One span owns the whole unbreakable token and knows which part of it is
     * the script: measurement and drawing are done per segment, so the token keeps its exact width
     * while the only measurement-run edges sit at its two ends.
     */
    static final class ScriptToken extends ReplacementSpan {
        final int scriptStart, scriptEnd;     // absolute offsets
        ScriptToken(int s, int e) { scriptStart = s; scriptEnd = e; }
        private float measure(Paint p, CharSequence t, int s, int e) {
            float w = 0;
            Paint script = null;
            int at = s;
            while (at < e) {
                int next = at < scriptStart ? Math.min(scriptStart, e)
                        : at < scriptEnd ? scriptEnd : e;
                if (at >= scriptStart && at < scriptEnd) {
                    if (script == null) {
                        script = new Paint(p);
                        script.setTextSize(p.getTextSize() * SCRIPT_SCALE);
                    }
                    w += script.measureText(t, at, next);
                } else {
                    w += p.measureText(t, at, next);
                }
                at = next;
            }
            return w;
        }
        public int getSize(Paint p, CharSequence t, int s, int e, Paint.FontMetricsInt fm) {
            if (fm != null) p.getFontMetricsInt(fm);
            return (int) Math.ceil(measure(p, t, s, e));
        }
        public void draw(Canvas cv, CharSequence t, int s, int e, float x, int top, int y, int b, Paint p) {
            float at = x;
            int i = s;
            while (i < e) {
                int next = i < scriptStart ? Math.min(scriptStart, e)
                        : i < scriptEnd ? scriptEnd : e;
                if (i >= scriptStart && i < scriptEnd) {
                    Paint c = new Paint(p);
                    c.setTextSize(p.getTextSize() * SCRIPT_SCALE);
                    cv.drawText(t, i, next, at, y - p.getTextSize() * SCRIPT_RAISE, c);
                    at += c.measureText(t, i, next);
                } else {
                    cv.drawText(t, i, next, at, y, p);
                    at += p.measureText(t, i, next);
                }
                i = next;
            }
        }
    }

    static StaticLayout build(CharSequence text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
    }

    static String cutsAt(StaticLayout l, int tokenStart, int tokenLen) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.getLineCount() - 1; i++) {
            int end = l.getLineEnd(i);
            int rel = end - tokenStart;
            if (i > 0) sb.append(',');
            // inside the token only counts as a cut; anything else is a legal break
            sb.append(rel > 0 && rel < tokenLen ? "CUT@" + rel : "ok" + rel);
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        Class.forName("android.graphics.Typeface");
        int width = 567;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(16f);
        String zh = "\u5b54\u6d1e\u7387\u3001\u526a\u5207\u5f3a\u5ea6\u4fdd\u6301\u7387\u548c\u65ad\u53e3\u7c7b\u578b\u662f\u54cd\u5e94\u53d8\u91cf";
        String[] variants = {"A-none", "B-script", "C-plain-edge", "D-token-span",
                "E-colour-only", "F-size-only", "G-typeface-only", "H-container-plus-inner"};
        System.out.println("sdk=" + android.os.Build.VERSION.SDK_INT);
        System.out.println("width=" + width + " token=" + TOKEN + " scriptIndex=" + SCRIPT_AT
                + "  (CUT@n = the breaker cut the token n characters in)");
        for (int pad = 28; pad <= 33; pad++) {
            for (int v = 0; v < variants.length; v++) {
                StringBuilder lead = new StringBuilder("\u3001");
                for (int i = 0; i < pad; i++) lead.append('\u5b54');
                SpannableStringBuilder sp = new SpannableStringBuilder(
                        lead + TOKEN + "\u5206\u5e03\u3001" + zh + zh);
                int tokenStart = lead.length();
                if (v == 1) {
                    sp.setSpan(new ScriptOnOne(SCRIPT_SCALE, SCRIPT_RAISE),
                            tokenStart + SCRIPT_AT, tokenStart + SCRIPT_AT + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (v == 2) {
                    sp.setSpan(new PlainOnOne(), tokenStart + SCRIPT_AT, tokenStart + SCRIPT_AT + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (v == 3) {
                    sp.setSpan(new ScriptToken(tokenStart + SCRIPT_AT, tokenStart + SCRIPT_AT + 1),
                            tokenStart, tokenStart + TOKEN.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (v == 7) {
                    // H: the cheap engine shape -- leave the per-run script span where it is and hang a
                    // container span over the whole token on top of it. Only works if it is the span that
                    // owns the token's edges that counts, not every span edge that still exists.
                    sp.setSpan(new ScriptOnOne(SCRIPT_SCALE, SCRIPT_RAISE),
                            tokenStart + SCRIPT_AT, tokenStart + SCRIPT_AT + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sp.setSpan(new ScriptToken(tokenStart + SCRIPT_AT, tokenStart + SCRIPT_AT + 1),
                            tokenStart, tokenStart + TOKEN.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (v >= 4) {
                    // The other span families the engine hangs inside a token: colour (no metric at all),
                    // a size change (MetricAffectingSpan, not a replacement) and a typeface change (what
                    // FontSpan/MeasuredFontSpan do at every OOXML run boundary). If any of these opens a
                    // break too, gluing the script span alone cannot fix the cut.
                    Object style = v == 4 ? (Object) new android.text.style.ForegroundColorSpan(0xFF0000FF)
                            : v == 5 ? (Object) new android.text.style.AbsoluteSizeSpan(11)
                            : (Object) new android.text.style.TypefaceSpan("serif");
                    sp.setSpan(style, tokenStart + SCRIPT_AT, tokenStart + SCRIPT_AT + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                System.out.println("pad=" + pad + "\t" + variants[v]
                        + "\t" + cutsAt(build(sp, paint, width), tokenStart, TOKEN.length()));
            }
        }
    }
}
