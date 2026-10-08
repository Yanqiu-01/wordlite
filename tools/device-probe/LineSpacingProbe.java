package com.rikkahub.wordlite;

import android.content.res.AssetManager;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.style.LineHeightSpan;
import android.graphics.Typeface;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * What line spacing does the layout engine actually receive, paragraph by paragraph?
 *
 * The complaint this measures: a document declares a fixed line spacing on two pages and the phone
 * renders inherited multiple spacing instead, so from that page on Word and we disagree about where
 * the page ends. XML alone cannot settle that - the parser resolves w:pStyle / basedOn / docDefaults
 * before the paginator ever sees a paragraph - so this runs the working-tree engine on the phone and
 * prints BOTH sides of the hand-off, per paragraph:
 *
 *   PARA  page block style lineTwips lineRule snap lhSpans minLineHeightPx defaultPt lines sumPx carryPx text
 *   LINE  page block line top advance advancePlusCarry lhSpans text
 *
 * lineTwips / lineRule / snap are what DocxParser handed the layout AFTER style inheritance; lhSpans
 * is the number of LineHeightSpan objects sitting on the laid-out first line, i.e. whether a line-height
 * rule was installed at all; sumPx is what the paginator actually billed. A dropped declaration reads
 * as lineRule=<empty> or lhSpans=0 while Word's own read-back (Word.Range.ParagraphFormat.LineSpacingRule)
 * says otherwise for the same paragraph.
 *
 * Usage (wrapped by tools/line-spacing-probe.ps1):
 *   arg0 = device dir holding assets.zip   arg1 = docx   arg2 = pages, e.g. 1,2,3,20,21
 */
public final class LineSpacingProbe {
    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "/data/local/tmp/wlspacing";
        String docx = args.length > 1 ? args[1] : root + "/input-liu.docx";
        String want = args.length > 2 ? args[2] : "1,2,3,19,20,21,22,23";
        java.util.Set<String> pages = new java.util.HashSet<String>();
        for (String s : want.split(",")) pages.add(s.trim());

        exemptHiddenApi();
        Class.forName(Typeface.class.getName());
        AssetManager assets = assetManager(root + "/assets.zip");
        DocxTextLayout.initialize(new ProbeContext(assets));

        DocxDocument doc;
        try (FileInputStream in = new FileInputStream(docx)) {
            doc = DocxParser.parse(in, "input-liu.docx");
        }
        docRef = doc;
        A4Paginator.PageResult result = new A4Paginator(doc.section).paginate(doc);

        System.out.println("device_sdk=" + android.os.Build.VERSION.SDK_INT
                + " pages=" + result.totalPages() + " paragraphs=" + doc.paragraphs.size()
                + " sections=" + doc.sections.size());
        for (int s = 0; s < doc.sections.size(); s++) {
            DocxDocument.SectionSettings ss = doc.sections.get(s);
            System.out.println("SECTION\t" + (s + 1) + "\tlineGridPitchTwips=" + ss.lineGridPitchTwips
                    + "\tlineGridActive=" + ss.lineGridActive);
        }

        // Fold the laid-out lines back onto their block, page fragment by page fragment.
        Map<Integer, StringBuilder> advances = new HashMap<Integer, StringBuilder>();
        Map<Integer, Float> carryOf = new HashMap<Integer, Float>();
        for (int p = 0; p < result.totalPages(); p++) {
            for (A4Paginator.ParagraphLayout pl : result.pages.get(p).paragraphs) {
                if (pl.text != null && pl.text.source != null) {
                    fold(pl.text.layout, advances, pl.text.source.index, pl.startLine, pl.endLine);
                    carryOf.put(Integer.valueOf(pl.text.source.index), Float.valueOf(pl.text.lineCarry));
                }
                if (pl.row != null) {
                    for (A4Paginator.CellParagraph cp : pl.row.paragraphs) {
                        if (cp.text == null || cp.text.source == null) continue;
                        fold(cp.text.layout, advances, cp.text.source.index, 0,
                                cp.text.layout.getLineCount());
                        carryOf.put(Integer.valueOf(cp.text.source.index),
                                Float.valueOf(cp.text.lineCarry));
                    }
                }
            }
        }

        // ---------- px/em scale, measured here rather than assumed ----------
        // A fixed line height written in bang has to be converted with the SAME scale the shaper uses.
        // PageGeometry.twips(400) says a 20 pt line is 26.667 document px; that is only true if a 1 pt
        // em really lays out at PageGeometry.points(1) document px on this phone. Read it back.
        android.text.TextPaint scale = new android.text.TextPaint();
        System.out.println("SCALE\tpoints_pt\tsetPx\tgetTextSizePx\thanMeasurePx\thanPerEm\tascentToDescent");
        float[] sizes = { 9f, 10.5f, 12f, 14f, 15f, 18f, 22f };
        for (int i = 0; i < sizes.length; i++) {
            float pt = sizes[i];
            scale.setTextSize(PageGeometry.points(pt));
            android.graphics.Paint.FontMetrics fm = scale.getFontMetrics();
            float han = scale.measureText("\u6c49");
            System.out.println("SCALE\t" + pt + "\t" + round(PageGeometry.points(pt), 4) + "\t"
                    + round(scale.getTextSize(), 4) + "\t" + round(han, 4) + "\t"
                    + round(han / scale.getTextSize(), 5) + "\t" + round(fm.descent - fm.ascent, 4));
        }
        System.out.println("SCALE\ttwips_to_px\t1440twips=" + round(PageGeometry.twips(1440), 4)
                + "px\t1pt=" + round(PageGeometry.points(1f), 6) + "px\tTWIPS_PER_UNIT="
                + PageGeometry.TWIPS_PER_UNIT);

        System.out.println("== PARA page block style lineTwips lineRule snap lhSpans minLineHeightPx "
                + "defaultPt lines sumPx carryPx text");
        for (DocxDocument.ParagraphBlock para : doc.paragraphs) {
            String firstPage = firstPage(result, para.index);
            String rule = para.format.lineRule == null ? "" : para.format.lineRule;
            boolean interesting = rule.length() > 0 && !"auto".equalsIgnoreCase(rule);
            if (!pages.contains(firstPage) && !interesting) continue;
            StringBuilder sb = new StringBuilder();
            sb.append("PARA\t").append(firstPage).append('\t').append(para.index).append('\t')
              .append(one(para.styleId)).append('\t')
              .append(para.format.lineSpacingTwips).append('\t')
              .append(one(rule)).append('\t')
              .append(para.format.snapToGrid ? "true" : "false").append('\t')
              .append(spansOnFirstLine(para)).append('\t')
              .append(round(DocxTextLayout.minimumLineHeight(para, gridOf(doc, para)), 3)).append('\t')
              .append(round(DocxTextLayout.defaultFontSizePoints(para), 2)).append('\t')
              .append(advances.containsKey(Integer.valueOf(para.index))
                      ? String.valueOf(count(advances.get(Integer.valueOf(para.index)))) : "-").append('\t')
              .append(advances.containsKey(Integer.valueOf(para.index))
                      ? String.valueOf(round(sum(advances.get(Integer.valueOf(para.index))), 2)) : "-").append('\t')
              .append(round(carry(para.index, carryOf), 3)).append('\t')
              .append(one(clean(para.text), 22));
            System.out.println(sb);
        }

        System.out.println("== LINE page block line top advance advancePlusCarry lhSpans text");
        for (int p = 0; p < result.totalPages(); p++) {
            if (!pages.contains(String.valueOf(p + 1))) continue;
            for (A4Paginator.ParagraphLayout pl : result.pages.get(p).paragraphs) {
                if (pl.text != null) {
                    printLines(p + 1, pl.text, pl.startLine, pl.endLine, "TEXT");
                }
                if (pl.row != null) {
                    for (A4Paginator.CellParagraph cp : pl.row.paragraphs) {
                        if (cp.text == null || cp.text.source == null) continue;
                        System.out.println("ROW\tpage=" + (p + 1) + "\trowHeightPx="
                                + round(pl.row.height, 2));
                        printLines(p + 1, cp.text, 0, cp.text.layout.getLineCount(), "CELL");
                    }
                }
            }
        }
        System.out.println("DONE");
    }

    // ---------- helpers ----------

    private static void fold(StaticLayout l, Map<Integer, StringBuilder> out, int block,
                             int first, int last) {
        Integer key = Integer.valueOf(block);
        StringBuilder sb = out.get(key);
        if (sb == null) { sb = new StringBuilder(); out.put(key, sb); }
        for (int i = first; i < last && i < l.getLineCount(); i++) {
            if (sb.length() > 0) sb.append(',');
            sb.append(l.getLineBottom(i) - l.getLineTop(i));
        }
    }

    private static void printLines(int page, DocxTextLayout.Paragraph text, int first, int last,
                                   String kind) {
        StaticLayout l = text.layout;
        CharSequence raw = l.getText();
        int block = text.source == null ? -1 : text.source.index;
        for (int i = first; i < last && i < l.getLineCount(); i++) {
            int s = l.getLineStart(i), e = l.getLineEnd(i);
            int spans = raw instanceof Spanned
                    ? ((Spanned) raw).getSpans(s, e, LineHeightSpan.class).length : -1;
            System.out.println(kind + "\t" + page + "\t" + block + "\t" + i + "\t"
                    + round(l.getLineTop(i), 2) + "\t" + round(l.getLineBottom(i) - l.getLineTop(i), 3)
                    + "\t" + round(l.getLineBottom(i) - l.getLineTop(i) + text.lineCarry, 3) + "\t"
                    + spans + "\t" + one(clean(raw.subSequence(s, e).toString()), 22));
        }
    }

    /** Re-measure the paragraph on its own and ask what line-height rules landed on line 1. */
    private static int spansOnFirstLine(DocxDocument.ParagraphBlock para) {
        try {
            PageGeometry g = new PageGeometry(sectionOf(para));
            DocxTextLayout.Paragraph laid = DocxTextLayout.measure(para, g.contentWidth, null,
                    gridOf(docRef, para));
            CharSequence raw = laid.layout.getText();
            if (!(raw instanceof Spanned) || laid.layout.getLineCount() == 0) return 0;
            return ((Spanned) raw).getSpans(laid.layout.getLineStart(0),
                    laid.layout.getLineEnd(0), LineHeightSpan.class).length;
        } catch (RuntimeException e) {
            return -999;
        }
    }

    private static DocxDocument.SectionSettings sectionOf(DocxDocument.ParagraphBlock para) {
        DocxDocument doc = docRef;
        int index = para.sectionIndex;
        if (doc != null && index >= 0 && index < doc.sections.size()) return doc.sections.get(index);
        return doc == null ? new DocxDocument.SectionSettings() : doc.section;
    }

    private static DocxDocument docRef;

    private static int gridOf(DocxDocument doc, DocxDocument.ParagraphBlock para) {
        DocxDocument.SectionSettings s = sectionOf(para);
        return s.lineGridPitchTwips;
    }

    private static String firstPage(A4Paginator.PageResult result, int block) {
        for (int p = 0; p < result.totalPages(); p++)
            for (A4Paginator.ParagraphLayout pl : result.pages.get(p).paragraphs)
                if (pl.blockIndex == block) return String.valueOf(p + 1);
        return "-";
    }

    private static int count(StringBuilder sb) {
        if (sb.length() == 0) return 0;
        int n = 1;
        for (int i = 0; i < sb.length(); i++) if (sb.charAt(i) == ',') n++;
        return n;
    }

    private static float sum(StringBuilder sb) {
        float total = 0f;
        for (String part : sb.toString().split(",")) if (part.length() > 0) total += Float.parseFloat(part);
        return total;
    }

    private static float carry(int block, Map<Integer, Float> carryOf) {
        Float f = carryOf.get(Integer.valueOf(block));
        return f == null ? 0f : f.floatValue();
    }

    private static float round(float value, int digits) {
        float f = (float) Math.pow(10, digits);
        return Math.round(value * f) / f;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("\n", " ").replace("\r", " ").replace("\t", " ").trim();
    }

    private static String one(String value) { return one(value, 40); }

    private static String one(String value, int max) {
        if (value == null) return "";
        String s = value.replace("\n", " ").replace("\t", " ");
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static void exemptHiddenApi() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Method getRun = vm.getDeclaredMethod("getRuntime");
            Object runtime = getRun.invoke(null);
            Method exempt = vm.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            exempt.invoke(runtime, new Object[] { new String[] { "L" } });
        } catch (Exception ignored) { }
    }

    private static AssetManager assetManager(String zip) throws Exception {
        AssetManager assets = AssetManager.class.newInstance();
        Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        addAssetPath.setAccessible(true);
        addAssetPath.invoke(assets, zip);
        return assets;
    }

    private static final class ProbeContext extends android.content.ContextWrapper {
        private final AssetManager assets;
        ProbeContext(AssetManager assets) { super(null); this.assets = assets; }
        @Override public AssetManager getAssets() { return assets; }
        @Override public android.content.Context getApplicationContext() { return this; }
    }
}
