package com.rikkahub.wordlite;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.text.Layout;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * The engine glued the script span over the whole token (span-edges.tsv: 0 edges inside a token on the
 * laid-out thesis), yet page 19 of tests/samples/input-liu.docx still ends a line at "...、孔洞率、Ag3"
 * and starts the next one with "Sn分布". This probe prints everything needed to see who cut the token:
 * for every paragraph holding the needle, the span table, the token bounds, the line ends of the
 * engine's own layout, and the line ends of a plain StaticLayout over the SAME laid-out text.
 *
 *   engine line ends cut inside the token, plain re-layout does not
 *      -> the cut is made by what the engine adds after measuring (the justification stretch spans),
 *         because the plain re-layout uses the same spans and the same breaker and keeps it whole.
 *   both cut
 *      -> the span does not actually cover the token, and the span table printed here says what does.
 *
 *   pwsh tools/script-token-lab.ps1 -Probe ScriptGlueProbe
 *   pwsh tools/script-token-lab.ps1 -Probe ScriptGlueProbe -Needle Ag3Sn -Tree artifacts/privtree/x/app/src/main/java
 */
public final class ScriptGlueProbe {

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "/data/local/tmp/wlscriptlab";
        String docx = args.length > 1 ? args[1] : root + "/input-liu.docx";
        String needle = args.length > 2 ? args[2] : "Ag3Sn";
        exemptHiddenApi();
        Class.forName("android.graphics.Typeface");
        DocxTextLayout.initialize(new FontContext(assetManager(root + "/assets.zip")));

        DocxDocument doc;
        try (FileInputStream in = new FileInputStream(docx)) {
            doc = DocxParser.parse(in, "input-liu.docx");
        }
        A4Paginator.PageResult result = new A4Paginator(doc.section).paginate(doc);
        System.out.println("sdk=" + android.os.Build.VERSION.SDK_INT + " needle=" + needle
                + " pages=" + result.totalPages());

        java.util.Map<Object, Boolean> seen = new java.util.IdentityHashMap<Object, Boolean>();
        for (int p = 0; p < result.totalPages(); p++) {
            for (A4Paginator.ParagraphLayout pl : result.pages.get(p).paragraphs) {
                if (pl.text == null || pl.text.layout == null) continue;
                CharSequence t = pl.text.layout.getText();
                if (!(t instanceof Spanned) || seen.containsKey(t)) continue;
                seen.put(t, Boolean.TRUE);
                if (t.toString().indexOf(needle) < 0) continue;
                report("page " + p + " block " + pl.blockIndex + " layoutWidth="
                        + pl.text.layout.getWidth(), (Spanned) t, pl.text.layout, needle);
            }
        }
    }

    private static void report(String head, Spanned text, Layout laidOut, String needle) {
        StringBuilder out = new StringBuilder();
        out.append("== ").append(head).append(" len=").append(text.length()).append('\n');
        int at = text.toString().indexOf(needle);
        int token = ScriptTokens.start(text, at);
        int tokenEnd = ScriptTokens.end(text, at + needle.length() - 1);
        out.append("token [").append(token).append(',').append(tokenEnd).append(")=\"")
           .append(text.subSequence(token, tokenEnd)).append("\" script inside=\"")
           .append(needle).append("\"\n");
        for (Object span : text.getSpans(Math.max(0, token - 2), Math.min(text.length(), tokenEnd + 2),
                Object.class)) {
            int from = text.getSpanStart(span), to = text.getSpanEnd(span);
            String kind = span.getClass().getSimpleName();
            if (span instanceof DocxTextLayout.WordScriptSpan)
                kind += " pieces=" + ((DocxTextLayout.WordScriptSpan) span).scriptPieces();
            boolean covers = from <= token && to >= tokenEnd;
            out.append("  span ").append(kind).append(" [").append(from).append(',').append(to)
               .append(")").append(covers ? "  COVERS TOKEN" : "").append('\n');
        }
        for (int i = 0; i < laidOut.getLineCount(); i++) {
            int s = laidOut.getLineStart(i), e = laidOut.getLineEnd(i);
            boolean cuts = e > token && e < tokenEnd;
            if (e < token - 40 && i + 1 < laidOut.getLineCount()
                    && laidOut.getLineEnd(i + 1) < token) continue;
            out.append("  engine line ").append(i).append(" [").append(s).append(',').append(e)
               .append(") w=").append(Math.round(laidOut.getLineWidth(i) * 100f) / 100f)
               .append(cuts ? "  <<< CUTS THE TOKEN" : "").append("  tail=\"")
               .append(text.subSequence(Math.max(s, e - 6), e)).append("\"\n");
        }
        // Same text object, same spans, same phone, ordinary layout: what does the breaker do when the
        // engine's justification spans and margins are not in the way?
        TextPaint paint = new TextPaint();
        paint.setTextSize(PageGeometry.points(12f));
        paint.setTypeface(DocxTextLayout.resolve("Times New Roman"));
        StaticLayout plain = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, laidOut.getWidth())
                .setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
        for (int i = 0; i < plain.getLineCount(); i++) {
            int e = plain.getLineEnd(i);
            boolean cuts = e > token && e < tokenEnd;
            if (e < token - 40 && i + 1 < plain.getLineCount() && plain.getLineEnd(i + 1) < token)
                continue;
            out.append("  plain  line ").append(i).append(" ends=").append(e)
               .append(cuts ? "  <<< CUTS THE TOKEN" : "").append("  tail=\"")
               .append(text.subSequence(Math.max(0, e - 6), e)).append("\"\n");
        }
        System.out.print(out);
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

    private static final class FontContext extends ContextWrapper {
        private final AssetManager assets;
        FontContext(AssetManager assets) { super(null); this.assets = assets; }
        @Override public AssetManager getAssets() { return assets; }
        @Override public Context getApplicationContext() { return this; }
    }
}
