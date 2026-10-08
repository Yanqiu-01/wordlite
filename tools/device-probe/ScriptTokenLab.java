package com.rikkahub.wordlite;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ReplacementSpan;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * What is it about a superscript/subscript run that makes the phone cut "Ag3Sn" in half, and does any
 * shape of span avoid the cut? Four shapes of one string, swept over how much Chinese sits in front of
 * it so the line runs out at a known place.
 *
 * Why this exists: OOXML stores "Ag3Sn" as three runs ("Ag", a w:vertAlign "3", "Sn"), and on the phone
 * page 19 of tests/samples/input-liu.docx ends a line as "...、孔洞率、Ag3" | "Sn分布" -- the user's
 * "上下角标导致分页不良". Word's own exported PDF cuts none of its 803 lines inside a token
 * (tools/break-class-truth.py), and Word moves the whole token down instead (tools/word-break-truth.ps1,
 * token-true).
 *
 *   script-span   what the app does: a span over the script run's own characters (it owns the OS/2 scale
 *                 and the baseline shift, so it has to be a ReplacementSpan)
 *   size-span     no ReplacementSpan: the script run keeps only its scaled size, as an AbsoluteSizeSpan
 *                 -- Word's width, without the baseline shift
 *   no-span       three runs, no span at all: the run boundary and its paint change stay
 *   one-run       Ag3Sn as a single run: what the platform does with an untouched token
 *
 * Run it on the phone (it needs the bundled fonts): pwsh tools/script-token-lab.ps1
 * A row reading CUT@n means the line ended n characters into the token; ok0 means it broke before the
 * token, which is what Word and both span-free controls do. docs/layout-parity-target.md 第 25 节.
 */
public final class ScriptTokenLab {

    private static final String TOKEN = "Ag3Sn";
    private static final float SCRIPT_SCALE = 0.65f;      // Times New Roman OS/2 ySubscriptYSize / em
    private static final int WIDTH = 567;                 // the thesis column, in document pixels

    public static void main(String[] args) throws Exception {
        String assetsZip = args.length > 0 ? args[0] : "/data/local/tmp/wlscriptlab/assets.zip";
        exemptHiddenApi();
        Class.forName("android.graphics.Typeface");
        DocxTextLayout.initialize(new FontContext(assetManager(assetsZip)));

        String zh = "\u5206\u5e03\u3001\u526a\u5207\u5f3a\u5ea6\u4fdd\u6301\u7387\u548c\u65ad\u53e3\u7c7b\u578b";
        String[] variants = { "script-span", "size-span", "no-span", "one-run" };
        System.out.println("sdk=" + android.os.Build.VERSION.SDK_INT + " width=" + WIDTH + " token=" + TOKEN
                + "  (CUT@n = the line ended n characters into the token; ok0 = broke before it, as Word does)");
        for (int pad = 26; pad <= 36; pad++) {
            for (int v = 0; v < variants.length; v++) {
                SpannableStringBuilder text = labText(variants[v], pad, zh);
                StaticLayout layout = layOut(text);
                int token = text.toString().indexOf(TOKEN);
                StringBuilder rel = new StringBuilder();
                String cut = "-";
                for (int i = 0; i < layout.getLineCount() - 1; i++) {
                    int r = layout.getLineEnd(i) - token;
                    if (rel.length() > 0) rel.append(',');
                    boolean inside = r > 0 && r < TOKEN.length();
                    rel.append(inside ? "CUT@" + r : "ok" + r);
                    if (inside && "-".equals(cut)) cut = "CUT@" + r + " edges=" + edgesAt(text, token + r);
                }
                System.out.println("pad=" + pad + "\t" + variants[v] + "\tlines=" + layout.getLineCount()
                        + "\t" + rel + "\tcut=" + cut + "\tspans=" + spansIn(text, token, token + TOKEN.length())
                        + "\twidth=" + Math.round(layout.getLineWidth(0) * 100f) / 100f);
            }
        }
    }

    private static SpannableStringBuilder labText(String variant, int pad, String zh) {
        DocxDocument.ParagraphBlock p = new DocxDocument.ParagraphBlock();
        p.baseRunStyle.fontSizeHalfPoints = 24;                       // 12 pt
        p.baseRunStyle.asciiFontFamily = "Times New Roman";
        p.baseRunStyle.eastAsiaFontFamily = "\u5b8b\u4f53";
        StringBuilder lead = new StringBuilder("\u3001");
        for (int i = 0; i < pad; i++) lead.append('\u5b54');
        p.text = lead + TOKEN + zh;
        if (variant.equals("one-run")) {
            p.runs.add(new DocxDocument.Run(p.text, p.baseRunStyle.copy()));
            return DocxTextLayout.styledText(p);
        }
        p.runs.add(new DocxDocument.Run(lead + "Ag", p.baseRunStyle.copy()));
        DocxDocument.RunStyle script = p.baseRunStyle.copy();
        script.subscript = true;                                      // w:vertAlign val="subscript"
        script.subscriptSet = true;
        p.runs.add(new DocxDocument.Run("3", script));
        p.runs.add(new DocxDocument.Run("Sn" + zh, p.baseRunStyle.copy()));
        SpannableStringBuilder text = DocxTextLayout.styledText(p);
        int token = text.toString().indexOf(TOKEN);
        DocxTextLayout.WordScriptSpan[] scripts = text.getSpans(token, token + TOKEN.length(),
                DocxTextLayout.WordScriptSpan.class);
        if (scripts.length == 0) throw new IllegalStateException("no script span in the token: " + variant);
        if (variant.equals("no-span")) {
            for (DocxTextLayout.WordScriptSpan span : scripts) text.removeSpan(span);
        }
        if (variant.equals("size-span")) {
            // Only the size Word bills the run at. The baseline shift comes with the replacement span,
            // and losing it is exactly the trade this row measures.
            DocxTextLayout.WordScriptSpan span = scripts[0];
            int from = Math.max(token, text.getSpanStart(span)), to = Math.min(token + TOKEN.length(),
                    text.getSpanEnd(span));
            text.removeSpan(span);
            text.setSpan(new AbsoluteSizeSpan(Math.round(
                            PageGeometry.points(span.baseHalfPoints() / 2f) * SCRIPT_SCALE)),
                    from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    /** The same StaticLayout options DocxTextLayout.build uses, minus justification: this is about breaks. */
    private static StaticLayout layOut(SpannableStringBuilder text) {
        TextPaint paint = new TextPaint();
        paint.setTextSize(PageGeometry.points(12f));
        paint.setTypeface(DocxTextLayout.resolve("Times New Roman"));
        StaticLayout.Builder b = StaticLayout.Builder.obtain(text, 0, text.length(), paint, WIDTH)
                .setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        if (android.os.Build.VERSION.SDK_INT >= 26)
            b.setJustificationMode(Layout.JUSTIFICATION_MODE_NONE);
        return b.build();
    }

    private static String edgesAt(Spanned text, int at) {
        StringBuilder kinds = new StringBuilder();
        for (ReplacementSpan span : text.getSpans(0, text.length(), ReplacementSpan.class)) {
            int from = text.getSpanStart(span), to = text.getSpanEnd(span);
            if (from == at || to == at) {
                if (kinds.length() > 0) kinds.append('+');
                kinds.append(span.getClass().getSimpleName()).append('[').append(from).append(',').append(to).append(')');
            }
        }
        return kinds.length() == 0 ? "NONE" : kinds.toString();
    }

    private static String spansIn(Spanned text, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (Object span : text.getSpans(from, to, Object.class)) {
            if (!(span instanceof ReplacementSpan)) continue;
            if (out.length() > 0) out.append('+');
            out.append(span.getClass().getSimpleName()).append('[')
               .append(text.getSpanStart(span)).append(',').append(text.getSpanEnd(span)).append(')');
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    private static void exemptHiddenApi() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vm.getDeclaredMethod("getRuntime").invoke(null);
            vm.getDeclaredMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, new Object[] { new String[] { "L" } });
        } catch (Throwable ignored) { }
    }

    private static AssetManager assetManager(String zip) throws Exception {
        Constructor<AssetManager> ctor = AssetManager.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        AssetManager am = ctor.newInstance();
        Method add = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        add.setAccessible(true);
        add.invoke(am, zip);
        return am;
    }

    /** FontManager only needs getAssets() and getApplicationContext(). */
    private static final class FontContext extends ContextWrapper {
        private final AssetManager assets;
        FontContext(AssetManager assets) { super(null); this.assets = assets; }
        @Override public AssetManager getAssets() { return assets; }
        @Override public Context getApplicationContext() { return this; }
    }
}
