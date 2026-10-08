package com.rikkahub.wordlite;

/**
 * Which characters Word refuses to break a line between, where a layout span of ours may therefore sit,
 * and how a span that has to sit inside such a run is stretched to cover the whole of it.
 *
 * The Word side is measured, not assumed:
 *  - with w:wordWrap absent or true Word moved a whole 26-letter token to the next line instead of
 *    cutting it (tools/word-break-truth.ps1, case token-true: first break at 20, before the token;
 *    token-false with w:wordWrap="0" breaks inside it at 42);
 *  - "-" is a legal break point (hyphen-edge: first break at 31, right after the "-"; 27 lines of Word's
 *    exported PDF end on one) and "/" is not (slash-edge: first break at 20, before the token);
 *  - 0 of the 238 real breaks in Word's exported PDF of tests/samples/input-liu.docx cut a run
 *    (tools/break-class-truth.py).
 *
 * Why this is a span-placement rule rather than a break rule: on the target phone (Android 10) we cannot
 * hand the platform our own rules -- StaticLayout.Builder.setBreakIterator throws NoSuchMethodException
 * through reflection (tools/breakiterator-probe.ps1, BreakIteratorProbe, sdk=29). The lever left is where
 * our own spans sit, and what the phone does with each shape is measured too
 * (tools/breakiterator-probe.ps1 -Probe ScriptBreakProbe, phone sdk=29, token "Ag3Sn", width 567, swept
 * over the Chinese in front so the line runs out at a known place; ok0 = broke before the token, which is
 * what Word does, CUT@n = broke n characters into it):
 *  - a seam riding a Latin character opened a break inside the word, so every autoSpace seam now rides
 *    the Chinese side (docs/layout-parity-target.md 第 24.2 节: 10 cut tokens per capture down to 1);
 *  - a ReplacementSpan over just the script character cuts the token (B: CUT@3 at pad 32, CUT@2 at pad
 *    33), and an unscaled one cuts in exactly the same place (C) -- it is the span EDGE that the breaker
 *    reads as an opportunity, not the scaled box;
 *  - a colour, a size or a typeface span on the same character does not cut (E/F/G, all ok0): only
 *    ReplacementSpan edges are dangerous, so FontSpan/MeasuredFontSpan/ForegroundColorSpan boundaries may
 *    stay where the OOXML run boundaries put them;
 *  - one span over the whole "Ag3Sn" does keep the token whole (D: ok0 at both pads), which is why a
 *    script span is stretched over the run it sits in (DocxTextLayout.glueScriptTokens, planned here);
 *  - hanging a token-wide span over the token while leaving the run's own span inside does NOT work
 *    (H: CUT@3 / CUT@2) -- the inner edge still cuts, so the run's span has to move, not be decorated.
 *
 * This class holds the rule and the plan; DocxTextLayout does the span surgery, and both audits measure
 * the result: tools/script-edge-audit.py on the source document, span-edges.tsv from
 * artifacts/device/probe/DeviceCapture.java on the laid-out one.
 */
public final class ScriptTokens {

    /**
     * True for a character Word glues to its neighbours inside a token: Latin and digit letters of any
     * alphabet (CJK excluded, since Word breaks freely between Chinese characters), plus the Unicode
     * super/subscript code points that stand in for a scaled run. Spaces and "-", "/", "." are left
     * out: Word may end a line on "-" and on a space, and the thesis proves it (27 of its lines end on
     * a hyphen, 3 on a slash that is followed by a space).
     */
    public static boolean glued(char c) {
        if (Character.isWhitespace(c) || DocxTextLayout.isCjk(c)) return false;
        if (FontScriptMetrics.unicodeScript(c) != 0) return true;
        return Character.isLetterOrDigit(c);
    }

    /** First character of the token that holds {@code at}. Offset 0 asks about the first character. */
    public static int start(CharSequence text, int at) {
        int i = Math.min(Math.max(at, 0), text.length());
        while (i > 0 && glued(text.charAt(i - 1))) i--;
        return i;
    }

    /** One past the last character of the token that holds {@code at}. */
    public static int end(CharSequence text, int at) {
        int i = Math.max(Math.min(at, text.length() - 1), 0);
        while (i < text.length() && glued(text.charAt(i))) i++;
        return i;
    }

    /**
     * True when Word forbids a break between the characters either side of {@code at}. A
     * ReplacementSpan edge here is a break opportunity inside a run Word keeps whole, which is what
     * span-edges.tsv counts on the laid-out document.
     */
    public static boolean breakForbiddenInside(CharSequence text, int at) {
        return at > 0 && at < text.length()
                && glued(text.charAt(at - 1)) && glued(text.charAt(at));
    }

    /**
     * The ranges a script span has to be stretched to, as flat [left,right) pairs in document order.
     *
     * Each input run [runStart[k], runEnd[k]) grows to the token its first character belongs to and the
     * token its last character belongs to, but never shrinks: a superscript citation "[1]" keeps its
     * brackets because Word bills the whole run at the script size, and a bracket is a legal break point
     * anyway. Runs whose grown ranges overlap collapse into one range -- "Cu" + sub "6" + "Sn" + sub "5"
     * all grow to the same token and must be drawn by ONE span, because a second span inside one token
     * would leave an edge inside it again (shape H above). Ranges that only touch stay apart.
     *
     * Pure Java on purpose: the ranges are checkable without Android (tests/ScriptTokenRegression.java),
     * while the caller still vetoes a range whose characters do not share one paint.
     */
    public static int[] planGluedRanges(CharSequence text, int[] runStart, int[] runEnd) {
        if (text == null || runStart == null || runEnd == null) return new int[0];
        int n = Math.min(runStart.length, runEnd.length);
        int[] out = new int[2 * n];
        int kept = 0;
        for (int k = 0; k < n; k++) {
            if (runStart[k] >= runEnd[k]) continue;
            int left = gluedAt(text, runStart[k]) ? start(text, runStart[k]) : runStart[k];
            int right = gluedAt(text, runEnd[k] - 1) ? end(text, runEnd[k] - 1) : runEnd[k];
            // Only OVERLAPPING ranges collapse. Two ranges that merely touch share a boundary, and a
            // boundary is where Word may break -- "Cu6-Sn5" grows to [0,3) and [3,7), and one span over
            // "Cu6-Sn5" would forbid the break after the hyphen that Word's hyphen-edge case allows
            // (tools/word-break-truth.ps1: first break 31, right after the "-").
            if (kept > 0 && left < out[2 * (kept - 1) + 1]) {
                if (right > out[2 * (kept - 1) + 1]) out[2 * (kept - 1) + 1] = right;
                continue;
            }
            out[2 * kept] = left;
            out[2 * kept + 1] = right;
            kept++;
        }
        int[] trimmed = new int[2 * kept];
        System.arraycopy(out, 0, trimmed, 0, 2 * kept);
        return trimmed;
    }

    private static boolean gluedAt(CharSequence text, int at) {
        return at >= 0 && at < text.length() && glued(text.charAt(at));
    }

    /**
     * First character of the script run inside the token [tokenStart, tokenEnd), or -1 when the token
     * holds no script. A script run is the one thing allowed to carry a span here: it needs it for its
     * OS/2 scale and baseline shift, and the measured alternatives are worse.
     */
    public static int firstScript(CharSequence text, int tokenStart, int tokenEnd) {
        for (int i = tokenStart; i < tokenEnd; i++)
            if (FontScriptMetrics.unicodeScript(text.charAt(i)) != 0) return i;
        return -1;
    }

    /** One past the last character of that script run, or -1 when the token holds no script. */
    public static int lastScriptEnd(CharSequence text, int tokenStart, int tokenEnd) {
        for (int i = tokenEnd - 1; i >= tokenStart; i--)
            if (FontScriptMetrics.unicodeScript(text.charAt(i)) != 0) return i + 1;
        return -1;
    }

    private ScriptTokens() { }
}
