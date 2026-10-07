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

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");

        // --- superscript: raised, so every demand lands above the baseline ---
        ScriptGeometry sup = ScriptGeometry.of(true, FontScriptMetrics.DEFAULT);
        check(sup.superscript, "ScriptGeometry.of(true) is a raised run");
        checkClose("superscript scale is OS/2 ySuperYSize", 0.65f, sup.scale);
        checkClose("superscript baseline shift raises the glyphs", 0.30f, sup.shift);
        // ySuperYOffset plus the scaled glyph ascent (0.65 x 0.8 of the em).
        checkClose("superscript ascent is 0.30 + 0.65 * 0.8 em", 0.82f, sup.ascentEm);
        checkClose("raised glyphs leave the descent untouched", 0f, sup.descentEm);
        check(sup.ascentEm > FontScriptMetrics.DEFAULT.ascentFraction,
                "superscript outgrows the single-line ascent, so Word has to grow the row");

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
                "subscript outgrows the single-line descent, so Word drops the row bottom");

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
