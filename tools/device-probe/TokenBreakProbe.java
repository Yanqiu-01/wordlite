package com.rikkahub.wordlite.probe;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextDirectionHeuristics;
import android.text.style.ReplacementSpan;

import java.lang.reflect.Method;

/**
 * Which platform mechanism can forbid a line break after "/" on this phone, and can one add a break
 * after "-"?
 *
 * Truth being chased (py tools/break-seam-census.py over the PDF Word exported, with denominators):
 * "/" occurs 59 times inside Word's compared lines and Word ended a line after it 0 times; we end a
 * line after it 3 times (device blocks 87 p8, 95 p10, 114 p13). "-" occurs 116 times, Word ended a
 * line after it twice ("SiCHigh-|Temperat", "(5):727-|741."), we 0 times.
 *
 * The engine cannot hand Android 10 its own break rules -- StaticLayout.Builder.setBreakIterator is
 * gone by reflection (docs section 24.3). This measures the levers that are left on this ROM, and the
 * first two are already answered by the header lines this probe prints:
 *
 *   - StaticLayout.Builder exposes no setCustomSpans at sdk 29 (its only break-related public setter
 *     is setBreakStrategy), so CustomSpan[] cannot be attached to a StaticLayout here.
 *   - android.text.style.CustomSpan itself does not resolve on this device: a probe that implemented
 *     it died with "java.lang.NoClassDefFoundError: Failed resolution of:
 *     [Landroid/text/style/CustomSpan;" (logcat 10-08 19:31:32, pid 11075), so the canBreakLine lever
 *     does not exist on this ROM at all. The check below is kept in the output as a live assertion.
 *
 * What is left is the measurement-run edge: a ReplacementSpan is one indivisible measurement unit for
 * StaticLayout, so modes 6/7 test whether covering the run around each "/" removes the after-slash
 * line ends without moving any other line end and without changing any line width.
 *
 *   1 HIGH_QUALITY (what DocxTextLayout.build uses today)   2 SIMPLE   3 BALANCED
 *   6 ReplacementSpan over the whole run of letters/digits/slashes containing each "/"
 *   7 ReplacementSpan over the "/" character alone
 *
 * Usage: pwsh tools/breakiterator-probe.ps1 -Probe TokenBreakProbe
 */
public class TokenBreakProbe {

    /** Measures and draws its range as ordinary text: only the break behaviour is the question. */
    static final class Plain extends ReplacementSpan {
        public int getSize(Paint paint, CharSequence t, int s, int e, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return Math.round(paint.measureText(t, s, e));
        }
        public void draw(Canvas c, CharSequence t, int s, int e, float x, int top, int y, int b, Paint p) {
            c.drawText(t, s, e, x, y, p);
        }
    }

    static void p(String s) {
        System.out.println(s);
        System.out.flush();          // app_process dies now and then; never lose the output
    }

    static StaticLayout build(CharSequence text, TextPaint paint, int width, int strategy) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setBreakStrategy(strategy)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
    }

    /** Line ends as "offset(lastChar)", flagged when the last character is one of the disputed ones. */
    static String offsets(Layout l, CharSequence text) {
        StringBuilder sb = new StringBuilder();
        double total = 0;
        int slash = 0, hy = 0;
        for (int i = 0; i + 1 < l.getLineCount(); i++) {
            int end = l.getLineEnd(i);
            char last = end > 0 && end <= text.length() ? text.charAt(end - 1) : '?';
            sb.append(end).append('(').append(last).append(')');
            if (last == '/') { sb.append('*'); slash++; }
            if (last == '-') { sb.append('#'); hy++; }
            sb.append(' ');
            total += l.getLineWidth(i);
        }
        return sb + " [after-slash=" + slash + " after-hyphen=" + hy + " ink="
                + Math.round(total * 100) / 100.0 + "]";
    }

    public static void main(String[] args) throws Exception {
        Class.forName("android.graphics.Typeface");   // hwui default typeface, see DeviceCapture
        p("sdk=" + android.os.Build.VERSION.SDK_INT + " width=567 textSize=16");
        StringBuilder ms = new StringBuilder();
        for (Method m : StaticLayout.Builder.class.getMethods()) {
            String n = m.getName().toLowerCase();
            if (n.contains("custom") || n.contains("break") || n.contains("linebreak")) ms.append(m.getName()).append(' ');
        }
        p("StaticLayout.Builder public setters matching custom*/break*/lineBreak*: " + ms.toString().trim());
        try {
            Class.forName("android.text.style.CustomSpan");
            p("android.text.style.CustomSpan: present, canBreakLine lever available in principle"
                    + " (but see the setter list above)");
        } catch (Throwable t) {
            p("android.text.style.CustomSpan: ABSENT on this ROM (" + t.getClass().getSimpleName()
                    + ") -- the canBreakLine lever cannot be used here");
        }
        p("modes 1=HIGH_QUALITY(today) 2=SIMPLE 3=BALANCED 6=ReplacementSpan over the run"
                + " 7=ReplacementSpan over the / only");
        java.util.ArrayList<String> paras = new java.util.ArrayList<String>();
        try {                                          // pushed by tools/breakiterator-probe.ps1
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream("/data/local/tmp/wlbreak/paras.txt"), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) if (line.trim().length() > 0) paras.add(line.trim());
            r.close();
        } catch (Throwable t) {
            p("no /data/local/tmp/wlbreak/paras.txt (" + t.getClass().getSimpleName()
                    + ") -- falling back to the invented cases");
        }
        int width = 567;                               // the thesis text column, document px
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(16f);                        // 12 pt in document units
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < 24; i++) cjk.append('\u6d4b');
        java.util.LinkedHashMap<String, String> cases = new java.util.LinkedHashMap<String, String>();
        // the three device blocks where we cut at a slash and Word did not
        cases.put("thesis-cu", "\u6784\u5efa" + "Cu/SB/P-Cu/SB/Cu"
                + "\u5939\u5c42\u7ed3\u6784\uff0c\u5e76\u5728" + cjk + "\u4e2d\u8bbe\u7f6e" + cjk);
        cases.put("thesis-npc", "\u7814\u7a76\uff09\uff0c\u5bf9" + "NPC/SAC305"
                + "\u5f62\u6210" + cjk + "\u7684\u754c\u9762" + cjk);
        cases.put("thesis-nacl", "\u5173\u7cfb\u5c1a\u9700\u660e\u786e\u3002\u9700\u8981\u5efa"
                + "CuO/NaCl/Ag" + "\u4f53\u7cfb\u7684\u6210\u5f62\u3001\u8fd8\u539f" + cjk + cjk);
        // bounds: is there a slash that Word DOES break at? a URL, a date and a ratio are the answers
        cases.put("url", "\u8be6\u89c1" + "http://www.cu-sn.org.cn/2026/10/08/report.pdf"
                + "\u6240\u5e73\u53f0\u53d1\u5e03" + cjk);
        cases.put("date", "\u6d4b\u8bd5\u65e5\u671f" + "2026-10-08" + cjk + "\u5b8c\u6210");
        // hyphens: Word ends a line after "-" twice, we never do
        cases.put("hyphen-latin", "\u91c7\u7528" + "SiCHigh-Temperature-Observation"
                + "\u65b9\u6cd5\u6d4b\u91cf" + cjk);
        cases.put("hyphen-alloy", "\u7814\u7a76\u4e86" + "Cu-Sn" + "\u4e2d\u95f4\u5c42"
                + cjk + "\u7684\u754c\u9762" + cjk);
        cases.put("pages", "\u53c2\u89c1\u6587\u732e" + "(5):727-741." + cjk + cjk);
        // A token wider than the line, with the disputed character where the fill runs out: this is
        // what tells a break opportunity apart from a forced break (the line end lands ON the
        // character when it is an opportunity, and at the width limit when it is not).
        StringBuilder hif = new StringBuilder();
        for (int i = 0; i < 40; i++) hif.append("Observation");
        cases.put("hyphen-long", "\u91c7\u7528" + "HighTemperatureResponse" + "-"
                + hif + "\u65b9\u6cd5" + cjk);
        StringBuilder slf = new StringBuilder();
        for (int i = 0; i < 6; i++) slf.append("CuSO4NaClAgKClMgCl2/");
        cases.put("slash-long", "\u91c7\u7528" + slf + "\u65b9\u6cd5" + cjk);
        String[] label = { "1", "2", "3" };
        int[] strategies = { Layout.BREAK_STRATEGY_HIGH_QUALITY, Layout.BREAK_STRATEGY_SIMPLE,
                Layout.BREAK_STRATEGY_BALANCED };
        paras.addAll(cases.values());                  // the bounds cases get the same sweep
        for (String src : paras) {                     // one string, every plausible column width
            int[] sl = slashes(src);
            java.util.ArrayList<int[]> runs = slashRuns(src);
            SpannableStringBuilder g = new SpannableStringBuilder(src);
            for (int[] r0 : runs) g.setSpan(new Plain(), r0[0], r0[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int hits1 = 0, hits6 = 0, same = 0, widths = 0, hy1 = 0, hy6 = 0;
            StringBuilder where = new StringBuilder(), wh = new StringBuilder();
            for (int w = 520; w <= 575; w++) {
                widths++;
                String a = offsets(build(src, paint, w, Layout.BREAK_STRATEGY_HIGH_QUALITY), src);
                String b = offsets(build(g, paint, w, Layout.BREAK_STRATEGY_HIGH_QUALITY), src);
                boolean s1 = a.contains("*"), s6 = b.contains("*");
                if (s1) hits1++;
                if (s6) hits6++;
                if (a.contains("#")) hy1++;
                if (b.contains("#")) hy6++;
                if (a.contains("#") && wh.length() < 200) wh.append(w).append(' ');
                if (a.substring(0, a.indexOf('[')).equals(b.substring(0, b.indexOf('[')))) same++;
                if (s1 && where.length() < 400) where.append(w).append(' ');
            }
            p("");
            p("para len=" + src.length() + " text=" + src.substring(0, Math.min(28, src.length()))
                    + "  slashes at " + show(sl));
            p("  over " + widths + " column widths 520..575: line ends right after a slash -- no span "
                    + hits1 + " widths (at " + (where.length() == 0 ? "-" : where.toString().trim())
                    + "), ReplacementSpan over the run " + hits6 + " widths");
            p("  line ends right after a hyphen -- no span " + hy1 + " widths (at "
                    + (wh.length() == 0 ? "-" : wh.toString().trim()) + "), with the span " + hy6);
            p("  column widths where the span left every line end where it was: " + same + " of " + widths
                    + "   (ink no span / with span: " + offsets(build(src, paint, 567,
                        Layout.BREAK_STRATEGY_HIGH_QUALITY), src).split("ink=")[1]
                    + " / " + offsets(build(g, paint, 567,
                        Layout.BREAK_STRATEGY_HIGH_QUALITY), src).split("ink=")[1]);
        }
        for (String name : cases.keySet()) {
            String src = cases.get(name);
            try {
                int[] slashes = slashes(src);
                java.util.ArrayList<int[]> runs = slashRuns(src);
                SpannableStringBuilder s6 = new SpannableStringBuilder(src);
                for (int[] r : runs) s6.setSpan(new Plain(), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                SpannableStringBuilder s7 = new SpannableStringBuilder(src);
                for (int sl : slashes) s7.setSpan(new Plain(), sl, sl + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                p("");
                p("case " + name + " len=" + src.length() + " slashes at " + show(slashes));
                for (int i = 0; i < strategies.length; i++) {
                    p("  " + label[i] + " " + offsets(build(src, paint, width, strategies[i]), src));
                }
                p("  6 " + offsets(build(s6, paint, width, Layout.BREAK_STRATEGY_HIGH_QUALITY), src));
                p("  7 " + offsets(build(s7, paint, width, Layout.BREAK_STRATEGY_HIGH_QUALITY), src));
            } catch (Throwable t) {
                p("case " + name + " FAILED " + t);
            }
        }
        p("");
        p("DONE");
    }

    static int[] slashes(CharSequence t) {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<Integer>();
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '/') out.add(i);
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** Maximal run of letters/digits/slashes around each "/": the block Word keeps together. */
    static java.util.ArrayList<int[]> slashRuns(CharSequence t) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<int[]>();
        for (int sl : slashes(t)) {
            int a = sl, b = sl;
            while (a > 0 && isTok(t.charAt(a - 1))) a--;
            while (b < t.length() && isTok(t.charAt(b))) b++;
            out.add(new int[]{ a, b });
        }
        return out;
    }

    static boolean isTok(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/';
    }

    static String show(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int x : a) sb.append(x).append(' ');
        return sb.toString().trim();
    }
}
