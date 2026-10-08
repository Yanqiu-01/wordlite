package com.rikkahub.wordlite;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Word's script geometry, checked without a device: the OS/2 driven size and
 * baseline shift of w:vertAlign runs, and the rule that decides how far a line
 * box grows for text that was raised or lowered.
 */
public final class ScriptRegression {
    private static int count;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    private static void checkClose(String what, float expected, float actual) {
        check(Math.abs(expected - actual) < 1e-4f,
                what + " expected=" + expected + " actual=" + actual);
    }

    private static void checkBox(String what, int ascent, int descent, int[] box) {
        check(box != null && box.length == 2 && box[0] == ascent && box[1] == descent,
                what + " expected={" + ascent + ", " + descent + "} actual=" + render(box));
    }

    private static String render(int[] box) {
        return box == null ? "null" : "{" + box[0] + ", " + box[1] + "}";
    }

    private static FontScriptMetrics scriptMetrics(File file) throws Exception {
        check(file.isFile(), "shipped font is readable: " + file.getPath());
        InputStream in = new FileInputStream(file);
        try {
            return FontScriptMetrics.read(in);
        } finally {
            in.close();
        }
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");

        // --- superscript: raised, so every demand lands above the baseline ---
        ScriptGeometry sup = ScriptGeometry.of(true, FontScriptMetrics.DEFAULT);
        check(sup.superscript, "ScriptGeometry.of(true) is a raised run");
        checkClose("superscript scale is OS/2 ySuperYSize", 0.65f, sup.scale);
        checkClose("superscript baseline shift raises the glyphs", 0.30f, sup.shift);
        // ySuperYOffset plus the scaled glyph ascent (0.65 x 0.8 of the em). This is where the
        // glyphs land; it is not what the row is billed for -- see declared() below.
        checkClose("superscript ascent is 0.30 + 0.65 * 0.8 em", 0.82f, sup.ascentEm);
        checkClose("raised glyphs leave the descent untouched", 0f, sup.descentEm);
        check(sup.ascentEm > FontScriptMetrics.DEFAULT.ascentFraction,
                "the raised glyphs do sit above the ordinary ascent -- Word draws them there");

        // --- subscript: lowered, so the growth lands below the baseline ---
        ScriptGeometry sub = ScriptGeometry.of(false, FontScriptMetrics.DEFAULT);
        check(!sub.superscript, "ScriptGeometry.of(false) is a lowered run");
        checkClose("subscript scale is OS/2 ySubscriptYSize", 0.65f, sub.scale);
        checkClose("subscript baseline shift drops the glyphs", -0.15f, sub.shift);
        // The drop closes 0.15 em of the scaled ascent, so only 0.37 em of the
        // base line's ascent is actually needed above the baseline.
        checkClose("subscript ascent is 0.65 * 0.8 - 0.15 em", 0.37f, sub.ascentEm);
        check(sub.ascentEm < FontScriptMetrics.DEFAULT.ascentFraction,
                "a dropped subscript never demands more ascent than plain text");
        checkClose("subscript descent is 0.13 + 0.15 em", 0.28f, sub.descentEm);
        check(sub.descentEm > FontScriptMetrics.DEFAULT.lineHeightRatio
                        - FontScriptMetrics.DEFAULT.ascentFraction,
                "the lowered glyphs do sit below the ordinary descent -- Word draws them there");

        // --- what the ROW is billed for: the lab, not the drawing box ---
        // artifacts/agent-layout-fix/lab (Word 16.0 COM, one paragraph per case, five lines split
        // by <w:br/>, every line starting with the same Han glyph so two Information(6) reads
        // differ by exactly the advance of the line above). On a 12pt body paragraph:
        //   script declaring 12pt -> advance equals the paragraph's own plain lines, 0.000 px,
        //     under w:line=240 and 300, lineRule auto/atLeast, docGrid on and off;
        //   script declaring 24pt -> advance 36.2 px (plain lines 20.867 px), and superscript and
        //     subscript grow by the same 15.333 px, so the shift direction is not in the box;
        //   script declaring 36pt -> 55.267 px; w:position raised text -> +12.733 px, so that
        //   one really does pay for its shift (raised(), kept as it was).
        ScriptGeometry declared = ScriptGeometry.declared(FontScriptMetrics.DEFAULT);
        checkClose("a vertAlign run competes at the size it declares", 1f, declared.scale);
        checkClose("and the script shift buys it no row height", 0f, declared.shift);
        checkClose("declared ascent is the face's own", 0.8f, declared.ascentEm);
        checkClose("declared descent is the face's own", 0.2f, declared.descentEm);
        check(declared.ascentEm < sup.ascentEm,
                "so a script is a smaller line-box claim than where its glyphs are drawn");

        // --- guard: a script run is typeset smaller than its paragraph ---
        // 这就是上下标行宽必须比正文字号窄的依据: Word 先按 OS/2 把 run 缩小再取字宽,
        // 所以 12 pt 正文里的 [12] 只按 12 * scale 计费, 按基础字号计费会让紧边界的行提前换行。
        check(sup.scale < 1f && sub.scale < 1f,
                "script scale is below one so the script advance must be charged at the smaller size");
        checkClose("superscript 12 pt renders at 12 * scale points", 12f * 0.65f, sup.effectiveSizePt(12f));
        checkClose("subscript 12 pt renders at 12 * scale points", 12f * 0.65f, sub.effectiveSizePt(12f));

        // --- w:position raises without rescaling, so the scale stays one ---
        ScriptGeometry raised = ScriptGeometry.raised(0.25f, FontScriptMetrics.DEFAULT);
        checkClose("w:position keeps the run size", 1f, raised.scale);
        checkClose("w:position adds the raise to the ascent", 0.8f + 0.25f, raised.ascentEm);

        // --- lineBox: the paragraph box, grown only as far as the line needs ---
        checkBox("line without scripts keeps the paragraph box", 13, 3,
                ScriptGeometry.lineBox(13, 3, 0, 0, 0, false));
        checkBox("a script that already fits does not grow the row", 13, 3,
                ScriptGeometry.lineBox(13, 3, 13, 3, 0, false));
        checkBox("exact line spacing clips instead of growing", 13, 3,
                ScriptGeometry.lineBox(13, 3, 40, 40, 0, true));
        checkBox("exact line spacing clips on a document grid too", 13, 3,
                ScriptGeometry.lineBox(13, 3, 40, 40, 24, true));
        // Single spacing on a tight box: the raise pushes the line up and the
        // baseline keeps its distance from the row bottom, so descent stays put.
        checkBox("tight single-spaced row grows upward for a superscript", 20, 3,
                ScriptGeometry.lineBox(13, 3, 20, 0, 0, false));
        checkBox("a subscript grows the row below the baseline only", 13, 7,
                ScriptGeometry.lineBox(13, 3, 0, 7, 0, false));
        checkBox("both directions grow at once when both are present", 20, 7,
                ScriptGeometry.lineBox(13, 3, 20, 7, 0, false));

        // --- document grid: the grown row snaps to whole grid rows ---
        // grid 24, ascent 26 -> 26 + 4 = 30 needs two rows, and the leftover of
        // 48 - 26 lands below the baseline.
        int[] grid = ScriptGeometry.lineBox(20, 4, 26, 0, 24, false);
        checkBox("grid row snaps a grown row to whole rows", 26, 22, grid);
        check(grid[0] + grid[1] == 48, "grown row on a 24 px grid lands on 48 px");
        check(grid[0] >= 26 && grid[1] >= 4, "grid snapping never cuts the demand");
        int[] firstRow = ScriptGeometry.lineBox(10, 6, 16, 0, 24, false);
        checkBox("growth inside the first grid row snaps up to exactly one row", 16, 8, firstRow);
        int[] secondRow = ScriptGeometry.lineBox(20, 4, 28, 0, 24, false);
        check(secondRow[0] + secondRow[1] == 48,
                "a grown row that already clears one row takes a second: " + render(secondRow));
        checkBox("a paragraph without scripts is never grid-rounded here", 20, 4,
                ScriptGeometry.lineBox(20, 4, 0, 0, 24, false));

        // --- the same rules straight from the shipped font files ---
        scriptFont(new File(root, "app/src/main/assets/fonts/times-new-roman.ttf"),
                "Times New Roman", 0.64990234f, 0.453125f);
        scriptFont(new File(root, "app/src/main/assets/fonts/song.ttc"),
                "宋体", 0.5f, 0.5f);

        // --- the six rows this document actually paid for, in px ---
        // 12pt body, w:line=300, no grid: the paragraph box is 23 px (ascent 19 / descent 4) and
        // six lines carry a Latin subscript at the body size (16 px). Every one of them grew 1 px
        // while Word kept them at the paragraph's own height.
        FontScriptMetrics times = scriptMetrics(
                new File(root, "app/src/main/assets/fonts/times-new-roman.ttf"));
        ScriptGeometry bodyScript = ScriptGeometry.declared(times);
        float body16 = 16f;                                    // 12pt at 96/72
        checkBox("a 12pt Latin subscript fits the 12pt body row it sits in", 19, 4,
                ScriptGeometry.lineBox(19, 4,
                        Math.round(bodyScript.ascentPx(body16)),
                        Math.round(bodyScript.descentPx(body16)), 0, false));
        checkBox("the same row under w:line=276 (17/4) still does not grow", 17, 4,
                ScriptGeometry.lineBox(17, 4,
                        Math.round(bodyScript.ascentPx(body16)),
                        Math.round(bodyScript.descentPx(body16)), 0, false));
        // The lab's growing case: a script declaring 24pt (32 px) inside that same paragraph.
        int[] big = ScriptGeometry.lineBox(19, 4,
                Math.round(bodyScript.ascentPx(32f)), Math.round(bodyScript.descentPx(32f)), 0, false);
        check(big[0] + big[1] >= 35 && big[0] + big[1] <= 37,
                "a 24pt script grows the row to the 36.2 px Word measured: got "
                        + (big[0] + big[1]) + " px");
        // w:position pays for its shift: Word +12.733 px on a 20.867 px line.
        int[] shifted = ScriptGeometry.lineBox(19, 4,
                Math.round(ScriptGeometry.raised(0.833f, times).ascentPx(body16)), 0, 0, false);
        check(shifted[0] + shifted[1] > 23,
                "w:position still grows the row: got " + (shifted[0] + shifted[1]) + " px");

        System.out.println("SUMMARY " + count
                + " script geometry assertions passed; Word pixel comparison not run here.");
    }

    /**
     * Golden OS/2 numbers for the two shipped faces. Times New Roman scales to
     * 0.6499 and raises by 0.4531 em, 宋体 by exactly half an em; both stay at
     * or below half an em of raise and well under the base size, which is what
     * keeps a script run narrower and lower than the surrounding text.
     */
    private static void scriptFont(File file, String label, float expectedScale, float expectedShift)
            throws Exception {
        check(file.isFile(), label + " font file is shipped: " + file.getPath());
        InputStream in = new FileInputStream(file);
        FontScriptMetrics m;
        try {
            m = FontScriptMetrics.read(in);
        } finally {
            in.close();
        }
        ScriptGeometry sup = ScriptGeometry.of(true, m);
        ScriptGeometry sub = ScriptGeometry.of(false, m);
        check(m.superscriptScale >= 0.5f && m.superscriptScale <= 0.8f,
                label + " superscript scale sits in the Office range: " + m.superscriptScale);
        check(m.subscriptScale >= 0.5f && m.subscriptScale <= 0.8f,
                label + " subscript scale sits in the Office range: " + m.subscriptScale);
        check(sup.shift > 0f && sup.shift <= 0.5f,
                label + " superscript raises by a fraction of an em: " + sup.shift);
        check(sub.shift < 0f && sub.shift >= -0.5f,
                label + " subscript drops by a fraction of an em: " + sub.shift);
        checkClose(label + " superscript scale matches OS/2", expectedScale, sup.scale);
        checkClose(label + " superscript shift matches OS/2", expectedShift, sup.shift);
        checkClose(label + " 12 pt superscript renders at 12 * scale",
                12f * sup.scale, sup.effectiveSizePt(12f));
        checkClose(label + " 12 pt subscript renders at 12 * scale",
                12f * sub.scale, sub.effectiveSizePt(12f));
        check(sup.ascentEm > 0f, label + " superscript needs space above the baseline: " + sup.ascentEm);
        check(sub.descentEm > 0f, label + " subscript needs space below the baseline: " + sub.descentEm);
        check(sup.scale < 1f && sub.scale < 1f,
                label + " script glyphs are smaller than body text");
    }
}
