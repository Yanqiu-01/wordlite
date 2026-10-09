/*
 * DeviceCapture -- the layout measurement rig that tools/capture-device.ps1 runs on the phone through
 * app_process: the real DocxParser + A4Paginator + DocxTextLayout, no APK installed, no UI involved.
 * It is the source of every number in docs/layout-parity-target.md section 0, so it lives in the repo
 * next to the tools that read its output.
 *
 * One rule this file learned the hard way (2026-10-09): a column header must end with a newline.
 * Adding hangOverPx without it glued the header to the first data row, and every reader that maps
 * columns by name quietly reported zeros instead of failing -- "no line hangs punctuation" read clean
 * for a run where 8 lines hung. Check the printed header against the printed first row whenever a
 * column is added here.
 */
package com.rikkahub.wordlite;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.text.StaticLayout;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * On-device capture of the app's own pagination/line-breaking, run under app_process
 * so no APK install is needed. Layout coordinates are 96-DPI document units, so this
 * is the same layout the app would draw on this phone; only the platform shaper and
 * the bundled fonts decide the breaks.
 *
 * args: <docx> <assetsZip> <outDir> [tag] [screenWidthPx]
 */
public final class DeviceCapture {
    private static String tag = "impl";

    public static void main(String[] args) throws Exception {
        String docxPath = args[0];
        String assetsZip = args[1];
        File out = new File(args[2]);
        tag = args.length > 3 ? args[3] : "impl";
        float screenWidthPx = args.length > 4 ? Float.parseFloat(args[4]) : 1080f;
        out.mkdirs();

        exemptHiddenApi();
        // hwui installs its default typeface from Typeface's class init; without this,
        // any measureText() aborts the process outside a zygote-forked app process.
        Class.forName("android.graphics.Typeface");

        AssetManager assets = assetManager(assetsZip);
        Context ctx = new ProbeContext(assets);
        DocxTextLayout.initialize(ctx);

        long begin = System.currentTimeMillis();
        DocxDocument doc;
        try (FileInputStream in = new FileInputStream(docxPath)) {
            doc = DocxParser.parse(in, "input-liu.docx");
        }
        long parseMs = System.currentTimeMillis() - begin;
        A4Paginator.PageResult result = new A4Paginator(doc.section).paginate(doc);
        long totalMs = System.currentTimeMillis() - begin;

        PageGeometry g = new PageGeometry(doc.section);
        int words = wordCount(doc);
        int scale = Math.round(g.fitScale(screenWidthPx) * 100);

        write(new File(out, "summary.txt"), summary(doc, result, g, parseMs, totalMs));
        write(new File(out, "status.txt"), status(result, words, scale));
        write(new File(out, "pages.tsv"), pages(result));
        write(new File(out, "lines-all.tsv"), lines(result, false));
        write(new File(out, "chars-at-punct.tsv"), charsAtPunct(result));
        write(new File(out, "lines-geo.tsv"), linesGeo(result));
        write(new File(out, "page-geo.tsv"), pageGeo(result));
        write(new File(out, "superscript-inventory.txt"), inventory(doc));
        write(new File(out, "superscript-lines.txt"), lines(result, true));
        write(new File(out, "script-width.tsv"), scriptWidth(result));
        write(new File(out, "paragraphs-wordformat.tsv"), paragraphPages(result, doc));
        write(new File(out, "span-edges.tsv"), spanEdges(result));
        write(new File(out, "run-fit.tsv"), runFit(result));
        write(new File(out, "chars-tail.tsv"), charsTail(result));
        write(new File(out, "tail-fit.tsv"), tailFit(result));
        write(new File(out, "glue-price.tsv"), gluePrice(result));
        System.out.println("DONE tag=" + tag + " pages=" + result.totalPages()
                + " words=" + words + " parseMs=" + parseMs + " totalMs=" + totalMs);
    }

    // ---------- evidence dumps ----------

    private static String summary(DocxDocument doc, A4Paginator.PageResult result,
                                 PageGeometry g, long parseMs, long totalMs) {
        StringBuilder sb = new StringBuilder();
        int sup = 0, sub = 0, positioned = 0;
        LinkedHashSet<String> families = new LinkedHashSet<String>();
        for (DocxDocument.ParagraphBlock p : doc.paragraphs) {
            for (DocxDocument.Run r : p.runs) {
                if (r.style.superscript) sup++;
                if (r.style.subscript) sub++;
                if (r.style.positionSet) positioned++;
                if (r.style.fontFamily != null && r.style.fontFamily.length() > 0)
                    families.add(r.style.fontFamily);
            }
        }
        sb.append("impl=").append(tag).append('\n');
        sb.append("device_sdk=").append(android.os.Build.VERSION.SDK_INT)
          .append(" model=").append(android.os.Build.MODEL).append('\n');
        sb.append("paragraphs=").append(doc.paragraphs.size())
          .append(" images=").append(doc.imageCount)
          .append(" superscriptRuns=").append(sup)
          .append(" subscriptRuns=").append(sub)
          .append(" positionedRuns=").append(positioned).append('\n');
        sb.append("pages=").append(result.totalPages())
          .append(" imageFragments=").append(result.imageFragments).append('\n');
        sb.append("geometry_units96 width=").append(g.width).append(" height=").append(g.height)
          .append(" contentWidth=").append(g.contentWidth)
          .append(" contentHeight=").append(g.contentHeight).append('\n');
        sb.append("parseMs=").append(parseMs).append(" paginateMs=").append(totalMs - parseMs).append('\n');
        sb.append("font_loadedFaces=").append(FontManager.loadedCount()).append('\n');
        sb.append("font_resolution_probe:");
        String[] probeFamilies = { "Times New Roman", "宋体", "黑体", "Calibri", "仿宋", "楷体" };
        for (String family : probeFamilies) {
            Typeface face = DocxTextLayout.resolve(family);
            String path = FontManager.pathFor(face);
            sb.append(' ').append(family).append("=>").append(path == null ? "SYSTEM:" + face : path);
        }
        sb.append('\n');
        sb.append("script_metrics(OS/2):");
        for (String family : new String[] { "Times New Roman", "宋体", "Calibri" }) {
            FontScriptMetrics m = FontManager.metrics(family);
            sb.append(' ').append(family)
              .append(" supScale=").append(m.scale(true)).append(" supRaise=").append(m.offset(true))
              .append(" subScale=").append(m.scale(false)).append(" subLower=").append(m.offset(false));
        }
        sb.append('\n');
        String script = scriptGeometry();
        if (script != null) sb.append("ScriptGeometry(new impl)=").append(script).append('\n');
        sb.append("doc_families=").append(families).append('\n');
        return sb.toString();
    }

    /** Reflection only: ScriptGeometry exists in the fixed implementation. */
    private static String scriptGeometry() {
        try {
            Class<?> sg = Class.forName("com.rikkahub.wordlite.ScriptGeometry");
            Method of = sg.getMethod("of", boolean.class, FontScriptMetrics.class);
            StringBuilder sb = new StringBuilder();
            for (String family : new String[] { "Times New Roman", "宋体" }) {
                FontScriptMetrics m = FontManager.metrics(family);
                for (boolean sup : new boolean[] { true, false }) {
                    Object geo = of.invoke(null, Boolean.valueOf(sup), m);
                    sb.append(' ').append(family).append(sup ? "/sup" : "/sub")
                      .append(" scale=").append(floatOf(geo, "scale"))
                      .append(" shift=").append(floatOf(geo, "shift"));
                }
            }
            return sb.toString().trim();
        } catch (Throwable absent) {
            return null;
        }
    }

    private static float floatOf(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getFloat(target);
    }

    private static String status(A4Paginator.PageResult result, int words, int scale) {
        StringBuilder sb = new StringBuilder();
        sb.append("# status-bar text reproduced with EditorActivity.updateStatus()'s format\n");
        sb.append("# (app cannot be installed on this device; see capture-report.md)\n");
        for (int i = 0; i < result.totalPages(); i++) {
            A4Paginator.PageContent page = result.pages.get(i);
            sb.append(String.format(Locale.CHINA,
                    "page %d:   %d / %d 页  ·  %d 字  ·  中文  ·  %d%%",
                    i + 1, i + 1, result.totalPages(), words, scale));
            if (page.footerTemplate != null && page.footerTemplate.length() > 0)
                sb.append("   [footer template present: NUMPAGES-dependent pagination renders in-page]");
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String pages(A4Paginator.PageResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("page\tdisplayedNo\tsection\tfragments\tfirstLine\tlastLine\tusedHeight\toverflow\n");
        for (int i = 0; i < result.totalPages(); i++) {
            A4Paginator.PageContent page = result.pages.get(i);
            String[] ends = firstAndLastText(page);
            sb.append(i + 1).append('\t').append(page.displayedPageNumber).append('\t')
              .append(page.sectionIndex).append('\t').append(page.paragraphs.size()).append('\t')
              .append(one(ends[0])).append('\t').append(one(ends[1])).append('\t')
              .append(Math.round(page.usedHeight)).append('\t').append(page.overflow).append('\n');
        }
        return sb.toString();
    }

    private static String[] firstAndLastText(A4Paginator.PageContent page) {
        String first = null, last = null;
        for (A4Paginator.ParagraphLayout pl : page.paragraphs) {
            if (pl.text == null) continue;
            StaticLayout l = pl.text.layout;
            for (int i = pl.startLine; i < pl.endLine && i < l.getLineCount(); i++) {
                String text = clean(l.getText().subSequence(l.getLineStart(i), l.getLineEnd(i)).toString());
                if (text.length() == 0) continue;
                if (first == null) first = text;
                last = text;
            }
        }
        return new String[] { first == null ? "" : first, last == null ? "" : last };
    }

    /**
     * Per-character x for every line where a Latin/digit character sits right in front of CJK
     * punctuation ("120 \u03bcm\uff0c\u56fe\u4e2d"). Defect B on the user's page 8: a fullwidth comma with a visible gap in
     * front of it. Word's own per-character x for the same sentence is in
     * artifacts/word-break/word-chars.tsv, so the two sides are subtracted character by character.
     * One row per character: page, paragraph, line, char index, char, x from the line's left edge.
     */
    private static String charsAtPunct(A4Paginator.PageResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("page\tparagraph\tline\tindex\tchar\txRelPx\tstepPx\tlineWidthPx\n");
        for (int p = 0; p < result.totalPages(); p++) {
            for (A4Paginator.ParagraphLayout pl : result.pages.get(p).paragraphs) {
                if (pl.text == null) continue;
                StaticLayout l = pl.text.layout;
                for (int i = pl.startLine; i < pl.endLine && i < l.getLineCount(); i++) {
                    int lineStart = l.getLineStart(i), lineEnd = l.getLineEnd(i);
                    CharSequence t = l.getText();
                    if (!hasLatinThenCjkPunct(t, lineStart, lineEnd)
                            && !hasSymbolChar(t, lineStart, lineEnd)) continue;
                    float left = l.getLineLeft(i);
                    float prev = l.getPrimaryHorizontal(lineStart);
                    for (int c = lineStart; c < lineEnd; c++) {
                        float x = l.getPrimaryHorizontal(c);
                        sb.append(p + 1).append('\t').append(pl.blockIndex).append('\t').append(i)
                          .append('\t').append(c - lineStart).append('\t')
                          .append(t.charAt(c) == '\n' ? ' ' : t.charAt(c)).append('\t')
                          .append(Math.round((x - left) * 100) / 100f).append('\t')
                          .append(Math.round((x - prev) * 100) / 100f).append('\t')
                          .append(Math.round(l.getLineWidth(i) * 100) / 100f).append('\n');
                        prev = x;
                    }
                }
            }
        }
        return sb.toString();
    }

    // ---------- what the tail of a line costs ----------

    private static final char TAB = (char) 9;
    private static final char NL = (char) 10;

    private static float r2(float v) {
        return Math.round(v * 100) / 100f;
    }

    /**
     * Two horizontal positions off the platform in one call, without letting a refusal pass as a width.
     * getPrimaryHorizontal answers -1 for some offsets on a line this engine stretched with its own
     * spans (measured on the device: 74 of 600 lines came back negative at the start, and more read
     * 0 at the end), and a -1 handed straight into a subtraction turns into "the line's ink is 0 px",
     * which is the opposite of the truth. So: fall back to the line's own left edge at the start, and
     * walk back to the last position the platform did place at the end.
     */
    private static float[] primaryPair(StaticLayout l, int from, int to) {
        float a = l.getPrimaryHorizontal(from);
        if (a < 0) a = l.getLineLeft(l.getLineForOffset(from));
        float b = -1f;
        for (int k = to; k >= from; k--) {
            float v = l.getPrimaryHorizontal(k);
            if (v >= 0) { b = v; break; }
        }
        return new float[] { a, b };
    }

    /** Blanks the wrap left at the end of a line, still inside that line's own range. */
    private static int tailBlanks(StaticLayout l, int line) {
        int s = l.getLineStart(line);
        int last = l.getLineEnd(line) - 1;
        CharSequence t = l.getText();
        int n = 0;
        while (last > s && Character.isWhitespace(t.charAt(last))) { n++; last--; }
        return n;
    }

    /**
     * 1 when those blanks are billed nothing because a BlankTail covers them, 0 when they ride inside
     * the line's width. Only the justification pass puts a BlankTail on the text, and it throws the
     * whole result away when no line of the paragraph could be spread (DocxTextLayout.spreadText ends
     * with "if (widened == 0) return laidOut"), so a paragraph whose lines are all exactly full, or all
     * without an openable seam, silently keeps its trailing blanks priced. That price is what this
     * column exists to catch: a line can be 43 px short of the column and still have no room for a
     * 46 px token, and the 4 px that tipped it is a blank nobody can see on the page.
     */
    private static int tailFree(StaticLayout l, int line) {
        int n = tailBlanks(l, line);
        if (n == 0) return 0;
        int at = l.getLineEnd(line) - n;
        if (!(l.getText() instanceof android.text.Spanned)) return 0;
        android.text.Spanned sp = (android.text.Spanned) l.getText();
        return sp.getSpans(at, at + 1, DocxTextLayout.BlankTail.class).length > 0 ? 1 : 0;
    }

    /**
     * Span names covering one code unit, with the pixels the span adds. AutoGap is the quarter-em
     * autoSpace seam, WidenGap the justification slack, BlankTail a blank billed nothing,
     * AtomicRunSpan a Latin/digit string the breaker moves whole. Which one sits on a line's tail is
     * what turns "the line stopped 43 px short and the next token still did not fit" into a sentence.
     */
    private static String spansAt(CharSequence text, int at) {
        if (!(text instanceof android.text.Spanned)) return "";
        android.text.Spanned sp = (android.text.Spanned) text;
        Object[] found = sp.getSpans(at, at + 1, Object.class);
        StringBuilder out = new StringBuilder();
        for (Object o : found) {
            if (out.length() > 0) out.append('+');
            String name = o.getClass().getSimpleName();
            if (name.length() == 0) name = o.getClass().getName();
            out.append(name);
            if (o instanceof DocxTextLayout.AutoGap) {
                DocxTextLayout.AutoGap g = (DocxTextLayout.AutoGap) o;
                out.append("(before=").append(g.before ? 1 : 0)
                   .append(",after=").append(g.after ? 1 : 0).append(')');
            } else if (o instanceof DocxTextLayout.WidenGap && WIDEN_EXTRA != null) {
                try {
                    out.append("(extra=").append(Math.round(WIDEN_EXTRA.getFloat(o))).append(')');
                } catch (Throwable ignored) {
                    out.append("(extra=?)");
                }
            }
        }
        return out.length() > 140 ? out.substring(0, 140) : out.toString();
    }

    /**
     * The last nine code units of every laid-out line, one row each: what the character is, where it
     * sits, what it costs, and which span owns that cost. A line's width alone cannot say whether the
     * blank its wrap left behind took room -- this can, in the same document pixels every other column
     * uses. Blanks show as U+0020 because clean() drops them from the printable text.
     */
    private static String charsTail(A4Paginator.PageResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("page").append(TAB).append("paragraph").append(TAB).append("line").append(TAB)
          .append("index").append(TAB).append("char").append(TAB).append("codepoint").append(TAB)
          .append("xRelPx").append(TAB).append("advPx").append(TAB).append("spans").append(TAB)
          .append("lineWidthPx").append(TAB).append("lineNaturalPx").append(TAB)
          .append("lineTailChars").append(TAB).append("lineTailFree").append(NL);
        for (int p = 0; p < result.totalPages(); p++) {
            for (A4Paginator.ParagraphLayout pl : result.pages.get(p).paragraphs) {
                if (pl.text == null) continue;
                StaticLayout l = pl.text.layout;
                for (int i = pl.startLine; i < pl.endLine && i < l.getLineCount(); i++) {
                    int s = l.getLineStart(i), e = l.getLineEnd(i);
                    if (e <= s) continue;
                    float left = l.getLineLeft(i);
                    float stretch = widenExtraSum(l.getText(), s, e);
                    int from = Math.max(s, e - 9);
                    for (int c = from; c < e; c++) {
                        float x = l.getPrimaryHorizontal(c);
                        float nx = l.getPrimaryHorizontal(c + 1);
                        char ch = l.getText().charAt(c);
                        sb.append(p + 1).append(TAB).append(pl.blockIndex).append(TAB).append(i)
                          .append(TAB).append(c - s).append(TAB).append(ch == 10 ? ' ' : ch).append(TAB)
                          .append(String.format(Locale.ROOT, "U+%04X", (int) ch)).append(TAB)
                          .append(r2(x < 0 ? -1f : x - left)).append(TAB)
                          .append(r2(x < 0 || nx < 0 ? -1f : nx - x)).append(TAB)
                          .append(spansAt(l.getText(), c)).append(TAB)
                          .append(r2(l.getLineWidth(i))).append(TAB)
                          .append(r2(l.getLineWidth(i) - stretch)).append(TAB)
                          .append(tailBlanks(l, i)).append(TAB).append(tailFree(l, i)).append(NL);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String tailFitError = "";

    /** One TSV row from cells, tab between, newline at the end. */
    private static String row16(String... cells) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(TAB);
            out.append(cells[i]);
        }
        return out.append(NL).toString();
    }

    /**
     * The same paragraph, one step later: justification slack taken out (it follows the break, it is
     * never the cause of one) and every blank a live line left at its tail billed nothing, exactly as
     * the engine means to bill it. The glue stays on -- whether a Latin string moves whole is a rule,
     * and this layout is only here to price the blank.
     */
    private static StaticLayout relaidWithFreeTails(StaticLayout live) {
        tailFitError = LAYOUT_BUILD == null ? "no build() to call" : "";
        if (LAYOUT_BUILD == null) return null;
        try {
            android.text.SpannableStringBuilder copy =
                    new android.text.SpannableStringBuilder(live.getText());
            for (DocxTextLayout.WidenGap gap
                    : copy.getSpans(0, copy.length(), DocxTextLayout.WidenGap.class)) copy.removeSpan(gap);
            int billed = 0;
            for (int i = 0; i + 1 < live.getLineCount(); i++) {
                int n = tailBlanks(live, i);
                if (n == 0) continue;
                copy.setSpan(new DocxTextLayout.BlankTail(), live.getLineEnd(i) - n,
                        live.getLineEnd(i), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                billed++;
            }
            if (billed == 0) return null;
            return (StaticLayout) LAYOUT_BUILD.invoke(null, copy, live.getPaint(), live.getWidth(),
                    live.getAlignment(), false, true);
        } catch (Throwable failed) {
            Throwable cause = failed instanceof java.lang.reflect.InvocationTargetException
                    && failed.getCause() != null ? failed.getCause() : failed;
            tailFitError = cause.getClass().getSimpleName() + ":" + cause.getMessage();
            return null;
        }
    }

    private static void appendLineRow(StringBuilder sb, int page, int block, String which,
                                      StaticLayout l, int i, String note) {
        int s = l.getLineStart(i), e = l.getLineEnd(i);
        String text = clean(l.getText().subSequence(s, e).toString());
        float stretch = widenExtraSum(l.getText(), s, e);
        float natural = l.getLineWidth(i) - stretch;
        sb.append(page).append(TAB).append(block).append(TAB).append(which).append(TAB).append(i)
          .append(TAB).append(text.length()).append(TAB).append(edge(text, true)).append(TAB)
          .append(edge(text, false)).append(TAB).append(r2(l.getLineWidth(i))).append(TAB)
          .append(r2(stretch)).append(TAB).append(r2(natural)).append(TAB)
          .append(r2(l.getWidth() - l.getLineLeft(i) - natural)).append(TAB)
          .append(tailBlanks(l, i)).append(TAB).append(tailFree(l, i)).append(TAB)
          .append(r2(nextRunWidth(l, i))).append(TAB).append(nextRunChars(l, i)).append(TAB)
          .append(note).append(NL);
    }

    /**
     * One row per line of every paragraph that leaves a blank at the end of a wrapped line, twice over:
     * which=live is what the page shows, which=relay is the same text with those blanks billed nothing.
     * Where a Latin token our layout moved down comes back onto the line, that break was lost to the
     * width of a blank and not to the advance table -- the distinction the master asked for on para 94.
     */
    private static String tailFit(A4Paginator.PageResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("page").append(TAB).append("paragraph").append(TAB).append("which").append(TAB)
          .append("line").append(TAB).append("lineChars").append(TAB).append("first8").append(TAB)
          .append("last8").append(TAB).append("lineWidthPx").append(TAB).append("lineStretchPx")
          .append(TAB).append("lineNaturalPx").append(TAB).append("lineRoomPx").append(TAB)
          .append("tailChars").append(TAB).append("tailFree").append(TAB).append("nextRunPx")
          .append(TAB).append("nextRunChars").append(TAB).append("note").append(NL);
        for (int p = 0; p < result.totalPages(); p++) {
            for (A4Paginator.ParagraphLayout pl : result.pages.get(p).paragraphs) {
                if (pl.text == null) continue;
                StaticLayout l = pl.text.layout;
                boolean hasTail = false;
                for (int i = pl.startLine; i + 1 < pl.endLine && i + 1 < l.getLineCount(); i++)
                    if (tailBlanks(l, i) > 0) hasTail = true;
                if (!hasTail) continue;
                for (int i = pl.startLine; i < pl.endLine && i < l.getLineCount(); i++)
                    appendLineRow(sb, p + 1, pl.blockIndex, "live", l, i, "");
                StaticLayout relay = relaidWithFreeTails(l);
                if (relay == null) {
                    sb.append(row16(String.valueOf(p + 1), String.valueOf(pl.blockIndex), "relay",
                            "-1", "", "", "", "", "", "", "", "", "", "", "", "",
                            tailFitError.length() == 0 ? "no tail blanks" : tailFitError));
                    continue;
                }                for (int i = 0; i < relay.getLineCount(); i++)
                    appendLineRow(sb, p + 1, pl.blockIndex, "relay", relay, i, "");
            }
        }
        return sb.toString();
    }
    private static boolean isLatinOrDigit(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || Character.isLetterOrDigit(c) && c < 0x2E80;
    }

    /**
     * True when the line holds a unit symbol -- neither ASCII, nor a Chinese ideograph, nor a fullwidth
     * mark (μ ± ℃ ² × ·). The engine may answer those with a symbol face rather than the face the run
     * declared, and on the lines where our own advance table and the phone part company these are the
     * only unusual characters present, so their per-character x has to come off the device.
     */
    private static boolean hasSymbolChar(CharSequence t, int start, int end) {
        for (int c = start; c < end; c++) {
            char ch = t.charAt(c);
            if (ch < 128 || DocxTextLayout.isCjkPunctuation(ch)) continue;
            if (ch >= 0x2E80 && ch <= 0x9FFF) continue;       // radicals, kana, ideographs
            if (ch >= 0xF900 && ch <= 0xFAFF) continue;       // compatibility ideographs
            if (ch >= 0xFF00 && ch <= 0xFFEF) continue;       // fullwidth forms
            return true;
        }
        return false;
    }

    /** True when the line holds a Latin/digit character with CJK punctuation in front of it. */
    private static boolean hasLatinThenCjkPunct(CharSequence t, int start, int end) {
        for (int c = start + 1; c < end; c++) {
            char prev = t.charAt(c - 1), now = t.charAt(c);
            if (DocxTextLayout.isCjkPunctuation(now) && isLatinOrDigit(prev)) return true;
        }
        return false;
    }

    /**
     * `Paragraph.lineOverhang` only exists on an engine that hangs punctuation past the margin. It is
     * read reflectively so this one probe compiles against a HEAD without the field as well as against
     * an engine with it, which lets a single device run print both readings side by side.
     */
    private static final java.lang.reflect.Field LINE_OVERHANG = lineOverhangField();

    private static java.lang.reflect.Field lineOverhangField() {
        try {
            java.lang.reflect.Field f = DocxTextLayout.Paragraph.class.getField("lineOverhang");
            f.setAccessible(true);
            return f;
        } catch (Throwable absent) {
            return null;
        }
    }

    private static float lineOverhang(Object paragraph, int line) {
        if (LINE_OVERHANG == null) return 0f;
        try {
            float[] over = (float[]) LINE_OVERHANG.get(paragraph);
            return over != null && line < over.length ? over[line] : 0f;
        } catch (Throwable unreadable) {
            return 0f;
        }
    }

    /** paragraph \t line \t first8 \t last8 (+ page and width for auditing). */
    private static String lines(A4Paginator.PageResult result, boolean scriptsOnly) {
        java.util.Set<Integer> scriptBlocks = scriptsOnly ? scriptParagraphIndexes(result) : null;
        StringBuilder sb = new StringBuilder();
        if (!scriptsOnly)
            sb.append("page\tparagraph\tline\tfirst8\tlast8\tlineChars\tlineWidthPx\tlineFull"
                    + "\tlineLeftPx\tparaXPx\topenGaps\tautoGaps\thangOverPx"
                    + "\tlineStretchPx\tlineNaturalPx\tlineRoomPx\tlayoutWidthPx"
                    + "\tnextCharPx\tnextRunPx\tnextRunChars"
                    + "\tlineInkPx\tlineTailPx"                    + "\tlineTailChars\tlineTailFree\n");
        else
            sb.append("paragraph\tline\tfirst8\tlast8\tlineChars\tlineWidthPx\tlineStretchPx"
                    + "\tlineNaturalPx\tlineRoomPx\tscriptChars\tscriptLaidPx\tscriptSpanPx"
                    + "\tscriptPlainPx\tscriptCeilTaxPx\tscriptScale\texpectScale\tscriptRanges"
                    + "\n");
        for (int p = 0; p < result.totalPages(); p++) {
            for (A4Paginator.ParagraphLayout pl : result.pages.get(p).paragraphs) {
                if (pl.text == null) continue;
                if (scriptsOnly && !scriptBlocks.contains(Integer.valueOf(pl.blockIndex))) continue;
                StaticLayout l = pl.text.layout;
                for (int i = pl.startLine; i < pl.endLine && i < l.getLineCount(); i++) {
                    int s = l.getLineStart(i), e = l.getLineEnd(i);
                    String raw = l.getText().subSequence(s, e).toString();
                    String text = clean(raw);
                    if (text.length() == 0) continue;
                    // How the line got its width: gaps opened by the justification pass, and seams still
                    // carrying only the automatic CJK/Latin quarter em. A line short of the column with
                    // openGaps=0 was never spread, which is a different bug than a spread that fell short.
                    int openGaps = 0, autoGaps = 0;
                    if (l.getText() instanceof android.text.Spanned) {
                        android.text.Spanned sp = (android.text.Spanned) l.getText();
                        openGaps = sp.getSpans(s, e, DocxTextLayout.WidenGap.class).length;
                        autoGaps = sp.getSpans(s, e, DocxTextLayout.AutoGap.class).length;
                    }
                    // A mark Word hangs past the margin is measured inside the column and drawn outside, so
                    // StaticLayout says the line ends at the margin while the ink ends hangOverPx past it.
                    float hangOver = lineOverhang(pl.text, i);
                    // lineWidth is what the page shows AFTER justification stretched it, so on a spread
                    // line it reads the same 566.93 whether the line is full or half empty. The stretch
                    // is exactly the whole pixels every WidenGap on the line added, so subtracting it
                    // gives the width the break was actually chosen on -- the only figure that can
                    // answer "could this line have taken one more character".
                    float stretch = widenExtraSum(l.getText(), s, e);
                    float natural = l.getLineWidth(i) - stretch;
                    float room = l.getWidth() - l.getLineLeft(i) - natural;
                    float nextChar = nextCharAdvance(l, i);
                    float nextRun = nextRunWidth(l, i);
                    // The line's own ink end, and the difference between that and what the layout
                    // reports as the line's width. A wrap that leaves a blank at the end of a line may
                    // still bill it: the difference is what it costs, which is the figure that decides
                    // whether a Latin token moved down because the trailing blank ate its room.
                    float[] ink = inkEnd(l, i);
                    float inkPx = ink[0], tailPx = ink[1];
                    if (scriptsOnly) {
                        // Width columns here, not just the text: a script-bearing line is exactly the
                        // line whose width is in dispute, and until now this file could only say which
                        // characters sat on it. scriptSpanPx is the price the breaker paid, scriptLaidPx
                        // what the platform placed, scriptPlainPx the same range at base size.
                        float[] agg = scriptAgg(l, i);
                        sb.append(pl.blockIndex).append('\t').append(i + 1).append('\t')
                          .append(edge(text, true)).append('\t').append(edge(text, false)).append('\t')
                          .append(text.length()).append('\t').append(r2(l.getLineWidth(i))).append('\t')
                          .append(r2(stretch)).append('\t').append(r2(natural)).append('\t')
                          .append(r2(room)).append('\t').append((int) agg[0]).append('\t')
                          .append(r2(agg[1])).append('\t').append(r2(agg[2])).append('\t')
                          .append(r2(agg[3])).append('\t').append(r2(agg[2] - agg[4])).append('\t')
                          .append(r2(agg[5])).append('\t').append(r2(agg[6])).append('\t')
                          .append(scriptRanges(l, i)).append('\n');
                    } else {
                        sb.append(p + 1).append('\t').append(pl.blockIndex).append('\t').append(i)
                          .append('\t').append(edge(text, true)).append('\t')
                          .append(edge(text, false)).append('\t').append(text.length())
                          .append('\t').append(Math.round(l.getLineWidth(i) * 100) / 100f)
                          .append('\t').append(one(text))
                          .append('\t').append(Math.round(l.getLineLeft(i) * 100) / 100f)
                          .append('\t').append(Math.round(pl.text.x * 100) / 100f)
                          .append('\t').append(openGaps).append('\t').append(autoGaps)
                          .append('\t').append(Math.round(hangOver * 100) / 100f)
                          .append('\t').append(Math.round(stretch * 100) / 100f)
                          .append('\t').append(Math.round(natural * 100) / 100f)
                          .append('\t').append(Math.round(room * 100) / 100f)
                          .append('\t').append(Math.round(l.getWidth() * 100) / 100f)
                          .append('\t').append(Math.round(nextChar * 100) / 100f)
                          .append('\t').append(Math.round(nextRun * 100) / 100f)
                          .append('\t').append(nextRunChars(l, i))
                          .append('\t').append(Math.round(inkPx * 100) / 100f)
                          .append('\t').append(Math.round(tailPx * 100) / 100f).append(TAB).append(tailBlanks(l, i)).append(TAB).append(tailFree(l, i)).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * WidenGap owns the stretched advance and its fields are private. The sum is read reflectively on
     * purpose: a capture has to be able to say how far the engine stretched a line without the engine
     * growing an accessor just for the measuring rig.
     */
    private static final java.lang.reflect.Field WIDEN_EXTRA = widenField("extraPx");

    private static java.lang.reflect.Field widenField(String name) {
        try {
            java.lang.reflect.Field f = DocxTextLayout.WidenGap.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable absent) {
            return null;
        }
    }

    /** Whole pixels the justification pass added to this line's advances; 0 when it never spread. */
    private static float widenExtraSum(CharSequence text, int start, int end) {
        if (WIDEN_EXTRA == null || !(text instanceof android.text.Spanned)) return 0f;
        android.text.Spanned sp = (android.text.Spanned) text;
        int sum = 0;
        for (DocxTextLayout.WidenGap gap : sp.getSpans(start, end, DocxTextLayout.WidenGap.class)) {
            if (sp.getSpanStart(gap) < start || sp.getSpanEnd(gap) > end) continue;
            try {
                sum += WIDEN_EXTRA.getInt(gap);
            } catch (Throwable unreadable) {
                return 0f;
            }
        }
        return sum;
    }

    /**
     * What the first character of the NEXT line costs, unstretched. A line that stopped short of the
     * column left this much ink on the floor, so room >= nextCharPx means we broke where we did not
     * have to. -1 on the last line of a paragraph, where there is no next character.
     */
    private static float nextCharAdvance(StaticLayout l, int line) {
        if (line + 1 >= l.getLineCount()) return -1f;
        int at = l.getLineStart(line + 1);
        if (at + 1 > l.getText().length()) return -1f;
        float step = l.getPrimaryHorizontal(at + 1) - l.getPrimaryHorizontal(at);
        if (step <= 0f) return -1f;
        return step - widenExtraSum(l.getText(), at, at + 1);
    }

    /**
     * The range that has to cross this break whole. The engine refuses to split a Latin/digit string
     * that Word keeps together (AtomicRunSpan), so such a string moves to the next line entire and a
     * single character's box is the wrong yardstick for a line that stopped in front of it.
     */
    private static int[] nextRunRange(StaticLayout l, int line) {
        if (line + 1 >= l.getLineCount()) return null;
        int at = l.getLineStart(line + 1), lineEnd = l.getLineEnd(line + 1);
        if (at >= l.getText().length()) return null;
        if (l.getText() instanceof android.text.Spanned) {
            android.text.Spanned sp = (android.text.Spanned) l.getText();
            for (DocxTextLayout.AtomicRunSpan run
                    : sp.getSpans(at, at + 1, DocxTextLayout.AtomicRunSpan.class)) {
                int from = sp.getSpanStart(run), to = sp.getSpanEnd(run);
                if (from <= at && to > at) return new int[] { at, Math.min(to, lineEnd) };
            }
        }
        return new int[] { at, Math.min(at + 1, lineEnd) };
    }

    /** Natural width of that range -- the ink a line that stopped short would have had to find. */
    private static float nextRunWidth(StaticLayout l, int line) {
        int[] r = nextRunRange(l, line);
        if (r == null) return -1f;
        float from = l.getPrimaryHorizontal(r[0]), to = l.getPrimaryHorizontal(r[1]);
        if (to < from) return -1f;
        return (to - from) - widenExtraSum(l.getText(), r[0], r[1]);
    }

    private static String nextRunChars(StaticLayout l, int line) {
        int[] r = nextRunRange(l, line);
        if (r == null) return "";
        return one(clean(l.getText().subSequence(r[0], r[1]).toString()), 24);
    }

    /** DocxTextLayout.build is private; the control layout below needs it and nothing else. */
    private static final java.lang.reflect.Method LAYOUT_BUILD = docxBuild();

    private static java.lang.reflect.Method docxBuild() {
        try {
            java.lang.reflect.Method m = DocxTextLayout.class.getDeclaredMethod("build",
                    android.text.Spannable.class, android.text.TextPaint.class, int.class,
                    android.text.Layout.Alignment.class, boolean.class, boolean.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable absent) {
            return null;
        }
    }

    /** True when a glued Latin/digit run starts this line (the glue that forbids the after-slash break). */
    private static boolean gluedAt(StaticLayout l, int line) {
        if (!(l.getText() instanceof android.text.Spanned)) return false;
        android.text.Spanned sp = (android.text.Spanned) l.getText();
        return sp.getSpans(l.getLineStart(line), l.getLineStart(line) + 1,
                DocxTextLayout.AtomicRunSpan.class).length > 0;
    }

    /** Room the line still had before the justification pass stretched it. */
    private static float roomOf(StaticLayout l, int line) {
        int s = l.getLineStart(line), e = l.getLineEnd(line);
        return l.getWidth() - l.getLineLeft(line)
                - (l.getLineWidth(line) - widenExtraSum(l.getText(), s, e));
    }

    /**
     * Control layout of one paragraph: the same text with the same paint and width, the justification
     * stretch taken out (it is a consequence of the break, never a cause) and the glue taken off. If
     * the line in front of a glued run takes that run in the control but left it outside in the live
     * layout, the run moved because of the rule and not because of the width model -- which is the
     * difference between "our advance table charges too much" and "the rule reaches further than Word's".
     */
    private static String relayError = "";

    private static StaticLayout relaidWithoutGlue(StaticLayout live) {
        relayError = LAYOUT_BUILD == null ? "no build() to call" : "";
        if (LAYOUT_BUILD == null) return null;
        try {
            android.text.SpannableStringBuilder copy =
                    new android.text.SpannableStringBuilder(live.getText());
            for (DocxTextLayout.AtomicRunSpan glue
                    : copy.getSpans(0, copy.length(), DocxTextLayout.AtomicRunSpan.class)) copy.removeSpan(glue);
            for (DocxTextLayout.WidenGap gap
                    : copy.getSpans(0, copy.length(), DocxTextLayout.WidenGap.class)) copy.removeSpan(gap);
            return (StaticLayout) LAYOUT_BUILD.invoke(null, copy, live.getPaint(), live.getWidth(),
                    live.getAlignment(), false, true);
        } catch (Throwable failed) {
            Throwable cause = failed instanceof java.lang.reflect.InvocationTargetException
                    && failed.getCause() != null ? failed.getCause() : failed;
            relayError = cause.getClass().getSimpleName() + ":" + cause.getMessage();
            return null;
        }
    }

    /**
     * One row per glued run that sits at the start of a line: what it cost, what room the line in
     * front of it had, and where the same text broke once the glue came off.
     */
    /**
     * What AtomicRunSpan.getSize answers for the run (the number the platform fits the line against),
     * and what an ordinary measureText of the same range answers. They are only equal when the glue
     * prices the run with the same faces the line itself uses; a gap between them is the glue charging
     * the wrong face, and it is the glue's own fit test that decides whether the run stays on the line.
     */
    private static float[] glueWidths(StaticLayout l, int line) {
        int[] r = nextRunRange(l, line - 1);
        if (r == null) return new float[] { -1f, -1f };
        android.text.TextPaint paint = l.getPaint();
        CharSequence t = l.getText();
        int glued = 0;
        float sum = 0f;
        if (t instanceof android.text.Spanned) {
            android.text.Spanned sp = (android.text.Spanned) t;
            for (DocxTextLayout.AtomicRunSpan run
                    : sp.getSpans(r[0], r[0] + 1, DocxTextLayout.AtomicRunSpan.class)) {
                glued = 1;
                for (int i = sp.getSpanStart(run); i < sp.getSpanEnd(run); i++)
                    sum += Math.round(paint.measureText(t, i, i + 1));
            }
        }
        float plain = Math.round(paint.measureText(t, r[0], r[1]));
        return new float[] { glued == 1 ? sum : -1f, plain };
    }

    private static String runFit(A4Paginator.PageResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("page\tblock\tline\trun_chars\trun_px\troom_px\tlive_run_line\tctrl_run_line"
                + "\tctrl_line_end\tctrl_line_px\tctrl_room_px\tsize_px\tplain_px\trun_would_fit\ttake_run\tctrl_err\n");
        java.util.IdentityHashMap<StaticLayout, StaticLayout> cache = new java.util.IdentityHashMap<>();
        for (int p = 0; p < result.totalPages(); p++) {
            for (A4Paginator.ParagraphLayout pl : result.pages.get(p).paragraphs) {
                if (pl.text == null) continue;
                StaticLayout l = pl.text.layout;
                for (int i = Math.max(1, pl.startLine); i < pl.endLine && i < l.getLineCount(); i++) {
                    if (!gluedAt(l, i)) continue;
                    StaticLayout c = cache.get(l);
                    if (c == null) {
                        c = relaidWithoutGlue(l);
                        cache.put(l, c);
                    }
                    int liveEnd = l.getLineStart(i);
                    int ctrlEnd = -1, ctrlLine = -1;
                    float ctrlPx = -1f, ctrlRoom = -1f;
                    if (c != null && liveEnd < c.getText().length()) {
                        ctrlLine = c.getLineForOffset(liveEnd);
                        ctrlEnd = c.getLineEnd(ctrlLine);
                        ctrlPx = c.getLineWidth(ctrlLine);
                        ctrlRoom = c.getWidth() - c.getLineLeft(ctrlLine) - ctrlPx;
                    }
                    float run = nextRunWidth(l, i - 1), room = roomOf(l, i - 1);
                    float[] glue = glueWidths(l, i);
                    String err = c == null ? relayError : "";
                    sb.append(p + 1).append('\t').append(pl.blockIndex).append('\t').append(i)
                      .append('\t').append(one(clean(l.getText().subSequence(
                              l.getLineStart(i), Math.min(l.getLineEnd(i),
                              l.getLineStart(i) + 24)).toString()), 24))
                      .append('\t').append(Math.round(run * 100) / 100f)
                      .append('\t').append(Math.round(room * 100) / 100f)
                      .append('\t').append(i).append('\t').append(ctrlLine)
                      .append('\t').append(ctrlEnd)
                      .append('\t').append(Math.round(ctrlPx * 100) / 100f)
                      .append('\t').append(Math.round(ctrlRoom * 100) / 100f)
                      .append('\t').append(Math.round(glue[0] * 100) / 100f)
                      .append('\t').append(Math.round(glue[1] * 100) / 100f)
                      .append('\t').append(run <= room ? 1 : 0)
                      .append('\t').append(ctrlLine >= 0 && ctrlLine < i ? 1 : 0)
                      .append('\t').append(one(err, 60)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /**
     * What a glued Latin/digit string really costs the line the platform put it on, measured instead of
     * assumed. AtomicRunSpan answers getSize with the sum of the character advances (block 87: 128 px),
     * and that is comfortably under the 156 px the line in front of it still had, yet the phone moves the
     * string down anyway. So re-lay the same paragraph, glue and all, one pixel wider at a time and record
     * the column width at which the string finally stays on the earlier line: that threshold minus the
     * line's own natural width is the price the breaker actually used. If it is far above getSize, the
     * glue is not over-pricing -- the refusal comes from somewhere else, and the fix has to look there.
     */
    private static String gluePrice(A4Paginator.PageResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("page").append(TAB).append("block").append(TAB).append("line").append(TAB)
          .append("glue_px").append(TAB).append("prev_natural_px").append(TAB).append("room_px")
          .append(TAB).append("threshold_px").append(TAB).append("effective_px").append(TAB)
          .append("glue_size_px").append(TAB).append("plain_px").append(TAB)
          .append("spans_at_run").append(TAB).append("span_px_sum").append(TAB)
          .append("span_ranges").append(TAB).append("note").append(NL);
        for (int p = 0; p < result.totalPages(); p++) {
            for (A4Paginator.ParagraphLayout pl : result.pages.get(p).paragraphs) {
                if (pl.text == null) continue;
                StaticLayout l = pl.text.layout;
                for (int i = Math.max(1, pl.startLine); i < pl.endLine && i < l.getLineCount(); i++) {
                    if (!gluedAt(l, i)) continue;
                    int runAt = l.getLineStart(i);
                    float run = nextRunWidth(l, i - 1);
                    float natural = l.getLineWidth(i - 1) - widenExtraSum(l.getText(),
                            l.getLineStart(i - 1), l.getLineEnd(i - 1));
                    float room = roomOf(l, i - 1);
                    float[] glue = glueWidths(l, i);
                    int spanCount = 0;
                    float spanSum = 0f;
                    StringBuilder ranges = new StringBuilder();
                    if (l.getText() instanceof android.text.Spanned) {
                        android.text.Spanned sp = (android.text.Spanned) l.getText();
                        for (DocxTextLayout.AtomicRunSpan glueSpan
                                : sp.getSpans(runAt, runAt + 1, DocxTextLayout.AtomicRunSpan.class)) {
                            spanCount++;
                            spanSum += glueSpan.getSize(l.getPaint(), l.getText(),
                                    sp.getSpanStart(glueSpan), sp.getSpanEnd(glueSpan), null);
                            if (ranges.length() > 0) ranges.append('+');
                            ranges.append(sp.getSpanStart(glueSpan)).append('-').append(sp.getSpanEnd(glueSpan));
                        }
                    }
                    int threshold = -1;
                    String note = LAYOUT_BUILD == null ? "no build() to call" : "";
                    if (LAYOUT_BUILD != null && l.getText() instanceof android.text.Spannable) {
                        for (int w = 500; w <= 660; w++) {
                            try {
                                StaticLayout wider = (StaticLayout) LAYOUT_BUILD.invoke(null,
                                        l.getText(), l.getPaint(), w, l.getAlignment(), false, true);
                                int at = wider.getLineForOffset(runAt);
                                if (w == l.getWidth()) note = "run line at own width " + at;
                                if (at == i - 1) { threshold = w; break; }
                            } catch (Throwable failed) {
                                Throwable cause = failed instanceof java.lang.reflect.InvocationTargetException
                                        && failed.getCause() != null ? failed.getCause() : failed;
                                note = cause.getClass().getSimpleName();
                                break;
                            }
                        }
                    }
                    sb.append(p + 1).append(TAB).append(pl.blockIndex).append(TAB).append(i).append(TAB)
                      .append(r2(run)).append(TAB).append(r2(natural)).append(TAB).append(r2(room))
                      .append(TAB).append(threshold).append(TAB)
                      .append(threshold < 0 ? -1 : r2(threshold - natural)).append(TAB)
                      .append(r2(glue[0])).append(TAB).append(r2(glue[1])).append(TAB)
                      .append(spanCount).append(TAB).append(r2(spanSum)).append(TAB)
                      .append(ranges.length() == 0 ? "-" : ranges.toString()).append(TAB)
                      .append(note.length() == 0 ? "n/a" : note)
                      .append(NL);
                }
            }
        }
        return sb.toString();
    }
    /**
     * Where this line's ink really ends, and what its tail costs the layout.
     *
     * StaticLayout keeps the blanks a wrap left at the end of the line inside the line, and the engine
     * bills them as nothing (BlankTail) because Word's line width stops at the last character with ink.
     * Whether the platform honored that on a given line can only be measured, and it decides the case
     * where Word and we break differently: a line reported as ending at 524 px of a 567 px column with
     * a Latin token pushed to the next line only makes sense if something at the tail -- the blank in
     * front of that token -- took the room. So: ink end, and width minus ink end.
     */
    private static float[] inkEnd(StaticLayout l, int line) {
        int s = l.getLineStart(line), e = l.getLineEnd(line);
        int last = e - 1;
        CharSequence t = l.getText();
        while (last > s && Character.isWhitespace(t.charAt(last))) last--;
        float[] pos = primaryPair(l, s, last + 1);
        float from = pos[0], to = pos[1];
        if (to < from) return new float[] { -1f, -1f };
        return new float[] { to - from, l.getLineWidth(line) - l.getLineLeft(line) - (to - from) };
    }

    /**
     * Every laid-out line with its box, measured from the page's top edge: table cell rows
     * included. Word's exported PDF gives the same three numbers per line (box top, baseline,
     * box bottom), so a page closes as first baseline + pitches between baselines + last
     * baseline to page bottom with nothing estimated. Document px (96 dpi).
     */
    private static String linesGeo(A4Paginator.PageResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("page\tsection\tblock\tkind\tline\ttop\tadvance\tbaseline\tbottom\tx0\tx1\ttext\n");
        for (int p = 0; p < result.totalPages(); p++) {
            A4Paginator.PageContent page = result.pages.get(p);
            float pageTop = page.geometry == null ? 0f : page.geometry.top;
            for (A4Paginator.ParagraphLayout pl : page.paragraphs) {
                if (pl.text != null) {
                    StaticLayout l = pl.text.layout;
                    int first = Math.max(0, pl.startLine);
                    float origin = pageTop + pl.top - (first < l.getLineCount() ? l.getLineTop(first) : 0f);
                    for (int i = first; i < pl.endLine && i < l.getLineCount(); i++) {
                        String text = clean(l.getText().subSequence(l.getLineStart(i), l.getLineEnd(i)).toString());
                        sb.append(p + 1).append('\t').append(pl.sectionIndex).append('\t')
                          .append(pl.blockIndex).append("\ttext\t").append(i).append('\t')
                          .append(px(origin + l.getLineTop(i))).append('\t')
                          .append(px(l.getLineBottom(i) - l.getLineTop(i))).append('\t')
                          .append(px(origin + l.getLineBaseline(i))).append('\t')
                          .append(px(origin + l.getLineBottom(i))).append('\t')
                          .append(px(pl.text.x + l.getLineLeft(i))).append('\t')
                          .append(px(pl.text.x + l.getLineLeft(i) + l.getLineWidth(i))).append('\t')
                          .append(one(text, 24)).append('\n');
                    }
                }
                if (pl.row == null) continue;
                for (A4Paginator.CellParagraph cp : pl.row.paragraphs) {
                    if (cp.text == null || cp.text.layout == null) continue;
                    StaticLayout l = cp.text.layout;
                    float origin = pageTop + pl.top + cp.y;
                    for (int i = 0; i < l.getLineCount(); i++) {
                        String text = clean(l.getText().subSequence(l.getLineStart(i), l.getLineEnd(i)).toString());
                        sb.append(p + 1).append('\t').append(pl.sectionIndex).append('\t')
                          .append(pl.blockIndex).append("\tcell\t").append(i).append('\t')
                          .append(px(origin + l.getLineTop(i))).append('\t')
                          .append(px(l.getLineBottom(i) - l.getLineTop(i))).append('\t')
                          .append(px(origin + l.getLineBaseline(i))).append('\t')
                          .append(px(origin + l.getLineBottom(i))).append('\t')
                          .append(px(cp.x + l.getLineLeft(i))).append('\t')
                          .append(px(cp.x + l.getLineLeft(i) + l.getLineWidth(i))).append('\t')
                          .append(one(text, 24)).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Per page: this section's margin box, so every page-top coordinate can be audited. */
    private static String pageGeo(A4Paginator.PageResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("page\tsection\tpageH\tmarginTop\tmarginBottom\tcontentH\theaderDist\tfooterDist\tused\toverflow\n");
        for (int p = 0; p < result.totalPages(); p++) {
            A4Paginator.PageContent page = result.pages.get(p);
            PageGeometry g = page.geometry;
            sb.append(p + 1).append('\t').append(page.sectionIndex).append('\t')
              .append(px(g.height)).append('\t').append(px(g.top)).append('\t')
              .append(px(g.bottom)).append('\t').append(px(g.contentHeight)).append('\t')
              .append(px(g.headerDistance)).append('\t').append(px(g.footerDistance)).append('\t')
              .append(px(page.usedHeight)).append('\t').append(page.overflow).append('\n');
        }
        return sb.toString();
    }

    private static String px(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static java.util.Set<Integer> scriptParagraphIndexes(A4Paginator.PageResult result) {
        java.util.Set<Integer> blocks = new java.util.HashSet<Integer>();
        java.util.Set<A4Paginator.ParagraphLayout> seen =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<A4Paginator.ParagraphLayout, Boolean>());
        for (int p = 0; p < result.totalPages(); p++)
            for (A4Paginator.ParagraphLayout pl : result.pages.get(p).paragraphs) {
                if (pl.text == null || !seen.add(pl)) continue;
                for (DocxDocument.Run r : pl.text.source.runs)
                    if (r.style.superscript || r.style.subscript) { blocks.add(Integer.valueOf(pl.blockIndex)); break; }
            }
        return blocks;
    }

    // ---------- what a script run costs a line ----------

    /** One script span clipped to the line it was measured on. */
    private static final class ScriptRun {
        final DocxTextLayout.WordScriptSpan span;
        final int from, to;
        ScriptRun(DocxTextLayout.WordScriptSpan span, int from, int to) {
            this.span = span; this.from = from; this.to = to;
        }
        String kind() {
            return span.isUnicode() ? "uni" : (span.isSuperscript() ? "sup" : "sub");
        }
    }

    /**
     * Script spans touching one line, outermost only, clipped to that line. ScriptTokenSpan is a
     * container that swallows the script spans of its own token, so a plain getSpans over the line
     * would bill the same characters twice; a span sitting inside another span of the same text is
     * dropped, and two spans with byte-identical ranges are kept (neither contains the other).
     */
    private static java.util.List<ScriptRun> scriptRunsOn(StaticLayout l, int line) {
        java.util.List<ScriptRun> out = new java.util.ArrayList<ScriptRun>();
        CharSequence t = l.getText();
        if (!(t instanceof android.text.Spanned)) return out;
        android.text.Spanned sp = (android.text.Spanned) t;
        int s = l.getLineStart(line), e = l.getLineEnd(line);
        DocxTextLayout.WordScriptSpan[] found =
                sp.getSpans(s, e, DocxTextLayout.WordScriptSpan.class);
        for (DocxTextLayout.WordScriptSpan a : found) {
            boolean swallowed = false;
            for (DocxTextLayout.WordScriptSpan b : found) {
                if (a == b) continue;
                if (sp.getSpanStart(b) > sp.getSpanStart(a) || sp.getSpanEnd(b) < sp.getSpanEnd(a))
                    continue;
                if (sp.getSpanStart(b) == sp.getSpanStart(a) && sp.getSpanEnd(b) == sp.getSpanEnd(a))
                    continue;
                swallowed = true;
                break;
            }
            if (swallowed) continue;
            int from = Math.max(s, sp.getSpanStart(a)), to = Math.min(e, sp.getSpanEnd(a));
            if (to > from) out.add(new ScriptRun(a, from, to));
        }
        return out;
    }

    /** WordScriptSpan.value is private and decides what is actually drawn (plain digits for vertAlign). */
    private static final java.lang.reflect.Method SCRIPT_VALUE = scriptValueMethod();

    private static java.lang.reflect.Method scriptValueMethod() {
        try {
            java.lang.reflect.Method m = DocxTextLayout.WordScriptSpan.class
                    .getDeclaredMethod("value", CharSequence.class, int.class, int.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable absent) {
            return null;
        }
    }

    private static String scriptText(DocxTextLayout.WordScriptSpan span, CharSequence t,
                                     int from, int to) {
        if (SCRIPT_VALUE != null && !span.isUnicode()) {
            try {
                Object drawn = SCRIPT_VALUE.invoke(span, t, Integer.valueOf(from), Integer.valueOf(to));
                if (drawn != null) return drawn.toString();
            } catch (Throwable unreadable) { }
        }
        return t.subSequence(from, to).toString();
    }

    /** The run's advance at the size the span really renders at, before getSize rounds it up. */
    private static float scriptExactPx(StaticLayout l, ScriptRun r) {
        try {
            android.text.TextPaint paint = l.getPaint();
            String drawn = scriptText(r.span, l.getText(), r.from, r.to);
            if (r.span.isUnicode()) return paint.measureText(drawn);
            android.text.TextPaint copy = new android.text.TextPaint(paint);
            copy.setTextSize(r.span.renderedSize(paint));
            return copy.measureText(drawn);
        } catch (Throwable failed) {
            return -1f;
        }
    }

    /** The scale the font asks for: OS/2 ySuper/ySub ratio, or 1.0 for a Unicode super/subscript glyph. */
    private static float scriptExpectScale(DocxTextLayout.WordScriptSpan span) {
        if (span.isUnicode()) return 1f;
        try {
            return FontManager.metrics(span.family()).scale(span.isSuperscript());
        } catch (Throwable unreadable) {
            return -1f;
        }
    }

    /**
     * One line's script accounting: { code units, px the platform placed, px the breaker paid,
     * px the same range costs at base size, px before the ceil, placed/base, scale the font asks for }.
     * laidOverPlain is the figure to read against Word: the breaker charges a script run one width, and
     * every px of excess over what Word charges for the same run is a line that ends earlier than Word's.
     */
    private static float[] scriptAgg(StaticLayout l, int line) {
        float chars = 0f, laid = 0f, paid = 0f, plain = 0f, exact = 0f, expect = 0f;
        android.text.TextPaint paint = l.getPaint();
        for (ScriptRun r : scriptRunsOn(l, line)) {
            chars += r.to - r.from;
            float[] pair = primaryPair(l, r.from, r.to);
            if (pair[1] >= pair[0])
                laid += (pair[1] - pair[0]) - widenExtraSum(l.getText(), r.from, r.to);
            plain += paint.measureText(l.getText(), r.from, r.to);
            try {
                paid += r.span.getSize(paint, l.getText(), r.from, r.to, null);
            } catch (Throwable unreadable) { }
            exact += scriptExactPx(l, r);
            expect += scriptExpectScale(r.span) * (r.to - r.from);
        }
        return new float[] { chars, laid, paid, plain, exact,
                plain > 0f ? laid / plain : -1f, chars > 0f ? expect / chars : -1f };
    }

    /** "from-to/chars+from-to/chars", so a row can be tied back to the character indices Word numbers. */
    private static String scriptRanges(StaticLayout l, int line) {
        StringBuilder out = new StringBuilder();
        for (ScriptRun r : scriptRunsOn(l, line)) {
            if (out.length() > 0) out.append('+');
            out.append(r.from).append('-').append(r.to).append('/')
               .append(r.kind()).append('/')
               .append(one(clean(l.getText().subSequence(r.from, r.to).toString()), 12));
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    /**
     * One row per script run per line, with the line's own width in front of it. Everything here is the
     * engine's own arithmetic in the same 96-DPI units as lines-all.tsv, so a Word number read off COM
     * (pt * 4/3) subtracts directly: span_size_px is what the breaker charged, exact_px the same charge
     * before getSize rounds up, laid_px what the line breaker ended up placing, plain_px the range at
     * body size, and ceil_tax_px the rounding that a merged ScriptTokenSpan was supposed to remove.
     */
    private static String scriptWidth(A4Paginator.PageResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("page").append(TAB).append("paragraph").append(TAB).append("line").append(TAB)
          .append("lineChars").append(TAB).append("lineWidthPx").append(TAB)
          .append("lineStretchPx").append(TAB).append("lineNaturalPx").append(TAB)
          .append("lineRoomPx").append(TAB).append("run_from").append(TAB).append("run_to").append(TAB)
          .append("run_chars").append(TAB).append("kind").append(TAB).append("family").append(TAB)
          .append("span_size_px").append(TAB).append("laid_px").append(TAB).append("exact_px").append(TAB)
          .append("plain_px").append(TAB).append("ceil_tax_px").append(TAB)
          .append("laid_over_plain").append(TAB).append("expect_scale").append(TAB)
          .append("base_size_px").append(TAB).append("rendered_size_px").append(TAB)
          .append("run_text").append(NL);
        for (int p = 0; p < result.totalPages(); p++) {
            for (A4Paginator.ParagraphLayout pl : result.pages.get(p).paragraphs) {
                if (pl.text == null) continue;
                StaticLayout l = pl.text.layout;
                for (int i = pl.startLine; i < pl.endLine && i < l.getLineCount(); i++) {
                    java.util.List<ScriptRun> runs = scriptRunsOn(l, i);
                    if (runs.isEmpty()) continue;
                    int s = l.getLineStart(i), e = l.getLineEnd(i);
                    float stretch = widenExtraSum(l.getText(), s, e);
                    float natural = l.getLineWidth(i) - stretch;
                    String lineHead = String.valueOf(p + 1) + TAB + pl.blockIndex + TAB + i + TAB
                            + clean(l.getText().subSequence(s, e).toString()).length() + TAB
                            + r2(l.getLineWidth(i)) + TAB + r2(stretch) + TAB + r2(natural) + TAB
                            + r2(l.getWidth() - l.getLineLeft(i) - natural) + TAB;
                    android.text.TextPaint paint = l.getPaint();
                    for (ScriptRun r : runs) {
                        float paid = -1f, exact = scriptExactPx(l, r);
                        try {
                            paid = r.span.getSize(paint, l.getText(), r.from, r.to, null);
                        } catch (Throwable unreadable) { }
                        float[] pair = primaryPair(l, r.from, r.to);
                        float laid = pair[1] < pair[0] ? -1f
                                : (pair[1] - pair[0]) - widenExtraSum(l.getText(), r.from, r.to);
                        float plain = paint.measureText(l.getText(), r.from, r.to);
                        sb.append(lineHead).append(r.from).append(TAB).append(r.to).append(TAB)
                          .append(r.to - r.from).append(TAB).append(r.kind()).append(TAB)
                          .append(one(r.span.family(), 24)).append(TAB)
                          .append(r2(paid)).append(TAB).append(r2(laid)).append(TAB)
                          .append(r2(exact)).append(TAB).append(r2(plain)).append(TAB)
                          .append(r2(paid - exact)).append(TAB)
                          .append(r2(plain > 0f && laid >= 0f ? laid / plain : -1f)).append(TAB)
                          .append(r2(scriptExpectScale(r.span))).append(TAB)
                          .append(r2(r.span.baseSizePx())).append(TAB)
                          .append(r2(r.span.isUnicode() ? paint.getTextSize()
                                  : r.span.renderedSize(paint))).append(TAB)
                          .append(one(clean(l.getText().subSequence(r.from, r.to).toString()), 24))
                          .append(NL);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String inventory(DocxDocument doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("# paragraphs holding vertAlign runs: paragraph index \t first 40 chars \t script runs\n");
        int index = 0;
        for (DocxDocument.ParagraphBlock p : doc.paragraphs) {
            StringBuilder scripts = new StringBuilder();
            for (DocxDocument.Run r : p.runs) {
                if (r.style.superscript || r.style.subscript)
                    scripts.append(r.style.superscript ? "^" : "_").append('[').append(clean(r.text)).append("] ");
            }
            if (scripts.length() > 0)
                sb.append(index).append('\t').append(one(clean(p.text), 40)).append('\t')
                  .append(scripts.toString().trim()).append('\n');
            index++;
        }
        return sb.toString();
    }

    /**
     * One row per paragraph, column-compatible with artifacts/word/pages.tsv
     * (para_index  start_page  end_page  ...  text30) so the two files diff directly.
     * Column 4 is lines rendered on the end page; Word's line_adj_end is a paragraph
     * format flag with no engine counterpart, so it is replaced by something measurable.
     */
    private static String paragraphPages(A4Paginator.PageResult result, DocxDocument doc) {
        java.util.LinkedHashMap<Integer, int[]> pages = new java.util.LinkedHashMap<Integer, int[]>();
        java.util.HashMap<Integer, String> texts = new java.util.HashMap<Integer, String>();
        for (int p = 0; p < result.totalPages(); p++) {
            for (A4Paginator.ParagraphLayout pl : result.pages.get(p).paragraphs) {
                if (pl.text == null) continue;
                Integer key = Integer.valueOf(pl.blockIndex);
                int[] span = pages.get(key);
                if (span == null) { span = new int[] { p + 1, p + 1, 0 }; pages.put(key, span); }
                span[1] = p + 1;
                span[2] = pl.endLine - pl.startLine;
                texts.put(key, pl.text.source.text);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("para_index\tdevice_start_page\tdevice_end_page\tend_page_lines\ttext30\n");
        for (java.util.Map.Entry<Integer, int[]> entry : pages.entrySet()) {
            int[] span = entry.getValue();
            String text = texts.get(entry.getKey());
            if (text == null) text = "";
            if (text.length() > 30) text = text.substring(0, 30);
            sb.append(entry.getKey().intValue()).append('\t').append(span[0]).append('\t')
              .append(span[1]).append('\t').append(span[2]).append('\t')
              .append(one(text, 30)).append('\n');
        }
        return sb.toString();
    }
    // ---------- helpers ----------

    /** Word/LibreOffice-ish count, copied from EditorActivity.updateWordCount(). */
    private static int wordCount(DocxDocument doc) {
        int count = 0;
        for (DocxDocument.ParagraphBlock p : doc.paragraphs) {
            boolean latinWord = false;
            for (int at = 0; at < p.text.length(); at++) {
                char c = p.text.charAt(at);
                if (c >= 0x3400 && c <= 0x9FFF) { count++; latinWord = false; }
                else if (Character.isLetterOrDigit(c)) { if (!latinWord) count++; latinWord = true; }
                else latinWord = false;
            }
        }
        return count;
    }

    private static String clean(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ' || c == '\u00a0'
                    || c == '\u3000' || c == '\ufeff' || c == '\u200b') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private static String edge(String text, boolean head) {
        int limit = 8;
        if (text.length() <= limit) return text;
        return head ? text.substring(0, limit) : tail(text, limit);
    }

    private static String tail(String text, int count) {
        int from = text.length() - count;
        while (from > 0 && from < text.length() && Character.isLowSurrogate(text.charAt(from))) from--;
        return text.substring(from);
    }

    private static String one(String value) { return one(value, 60); }

    private static String one(String value, int max) {
        String v = value.replace("\t", " ").replace("\r", " ").replace("\n", " ");
        return v.length() <= max ? v : v.substring(0, max) + "...";
    }

    private static void write(File file, String content) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
        w.write(content);
        w.close();
        System.out.println("wrote " + file.getName() + " " + content.length() + " chars");
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
        Object cookie = add.invoke(am, zip);
        System.out.println("asset_cookie=" + cookie + " listing="
                + java.util.Arrays.toString(am.list("fonts")));
        return am;
    }

    /** FontManager only needs getAssets()/getApplicationContext(). */
    private static final class ProbeContext extends android.content.ContextWrapper {
        private final AssetManager assets;
        ProbeContext(AssetManager assets) { super(null); this.assets = assets; }
        @Override public AssetManager getAssets() { return assets; }
        @Override public Context getApplicationContext() { return this; }
    }

    /**
     * The rule from docs/layout-parity-target.md 第 25 节, measured on the platform that has to break the
     * text: a ReplacementSpan edge is a break opportunity for StaticLayout, and Word never breaks a Latin
     * or digit token, so no span of ours may start or end at a token interior. tests/ui/.../
     * tests/ScriptTokenRegression.java asserts the rule itself; this counts it with the phone's own line
     * breaker, on the laid-out document, with the phone's own fonts. The shape comparison (does any span
     * placement keep a token whole?) lives in tools/script-token-lab.ps1.
     */
    private static String spanEdges(A4Paginator.PageResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("para\toffset\tinside_token\tkinds\tchars\n");
        java.util.Map<Object, Boolean> seen = new java.util.IdentityHashMap<Object, Boolean>();
        int interiors = 0, offenders = 0, scriptSpans = 0, containers = 0;
        for (int p = 0; p < result.totalPages(); p++) {
            for (A4Paginator.ParagraphLayout pl : result.pages.get(p).paragraphs) {
                if (pl.text == null) continue;
                CharSequence t = pl.text.layout.getText();
                if (!(t instanceof android.text.Spanned) || seen.containsKey(t)) continue;
                seen.put(t, Boolean.TRUE);
                android.text.Spanned sp = (android.text.Spanned) t;
                android.text.style.ReplacementSpan[] spans =
                        sp.getSpans(0, sp.length(), android.text.style.ReplacementSpan.class);
                boolean[] edge = new boolean[sp.length() + 1];
                for (android.text.style.ReplacementSpan s : spans) {
                    edge[sp.getSpanStart(s)] = true;
                    edge[sp.getSpanEnd(s)] = true;
                    if (s instanceof DocxTextLayout.WordScriptSpan) scriptSpans++;
                    // 名字判断，不引类型：这份采样还要能在没有容器的树上编过。
                    if ("ScriptTokenSpan".equals(s.getClass().getSimpleName())) containers++;
                }
                for (int i = 1; i < sp.length(); i++) {
                    if (!glued(t.charAt(i - 1)) || !glued(t.charAt(i)))
                        continue;
                    interiors++;
                    if (!edge[i]) continue;
                    offenders++;
                    StringBuilder kinds = new StringBuilder();
                    for (android.text.style.ReplacementSpan s : spans) {
                        if (sp.getSpanStart(s) != i && sp.getSpanEnd(s) != i) continue;
                        if (kinds.length() > 0) kinds.append('+');
                        kinds.append(s.getClass().getSimpleName());
                    }
                    sb.append(pl.blockIndex).append('\t').append(i).append("\tword_forbids\t")
                      .append(kinds).append('\t').append(t.charAt(i - 1)).append('|')
                      .append(t.charAt(i)).append('\n');
                }
            }
        }
        sb.append("SUMMARY\ttoken_interiors=").append(interiors)
          .append("\tspan_edges_inside_a_token=").append(offenders)
          .append("\tscript_spans_attached=").append(scriptSpans)
          .append("\tscript_token_containers=").append(containers).append('\n');
        return sb.toString();
    }

    /**
     * Does Word glue this character to its neighbours inside one unbreakable token? Latin letters and
     * digits of any alphabet plus the Unicode super/subscript code points: yes. CJK, spaces, "-", "/"
     * and ".": no (the thesis proves Word may end a line on a hyphen or on a space). This is the rig's
     * own copy on purpose - tools/device-probe/../ScriptTokens lives with the engine fix on the
     * script-tokens branch, and the capture must build against plain HEAD.
     */
    private static boolean glued(char c) {
        if (Character.isWhitespace(c) || isCjk(c)) return false;
        if (FontScriptMetrics.unicodeScript(c) != 0) return true;
        return Character.isLetterOrDigit(c);
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
}




