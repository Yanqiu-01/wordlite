package com.rikkahub.wordlite;

/**
 * Which characters Word refuses to break a line between, and where one layout span may therefore sit.
 *
 * Every rule below is a measured Word behaviour, not a preference:
 *  - With w:wordWrap absent or true, Word moved a whole 26-letter token to the next line instead of
 *    cutting it (tools/word-break-truth.ps1, case token-true: first break at character 20, before the
 *    token; token-false with w:wordWrap="0" breaks inside it at 42).
 *  - "-" is a legal break point (hyphen-edge: first break at 31, right after the "-"), and in Word's
 *    exported PDF of tests/samples/input-liu.docx 27 lines end on a hyphen
 *    (tools/break-class-truth.py, 238 real breaks: 0 of them inside a token).
 *  - "/" alone is not a break point (slash-edge: first break at 20, before the token; slash-then-space
 *    breaks at the space at 31), so a slash is not glue either: Word does not cut "Cu/SB" at all.
 *
 * Why this is a span-placement rule and not a break-iterator one: StaticLayout treats a
 * ReplacementSpan edge as a break opportunity, and on the target phone (Android 10) we cannot hand the
 * platform our own rules -- StaticLayout.Builder.setBreakIterator is gone
 * (tools/breakiterator-probe.ps1, BreakIteratorProbe, sdk=29, NoSuchMethodException). Measured on the
 * same phone (tools/breakiterator-probe.ps1 -Probe ScriptBreakProbe, pad=32/33 rows):
 *   A no span at all             break before the token (what Word does)
 *   B scaled span over the "3"   CUT@3 / CUT@2   (the phone cutting "Ag3" | "Sn")
 *   C unscaled span over the "3" CUT@3 / CUT@2   (so it is the edge, not the scaled box)
 *   D one span over "Ag3Sn"      break before the token (fixed)
 */
public final class ScriptTokenRegression {
    private static int count;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    private static void checkAt(String what, int expected, int actual) {
        check(expected == actual, what + " expected=" + expected + " actual=" + actual);
    }

    /** The thesis string that started this: ", kong dong lv, Ag3Sn fen bu" with the subscript as its own run. */
    private static final String THESIS =
            "\u3001\u5b54\u6d1e\u7387\u3001Ag3Sn\u5206\u5e03";

    public static void main(String[] args) throws Exception {
        // --- what Word glues ---
        check(ScriptTokens.glued('A') && ScriptTokens.glued('g') && ScriptTokens.glued('3'),
                "Latin letters and digits are glued: Word keeps a token in one piece");
        check(ScriptTokens.glued('\u00b2') && ScriptTokens.glued('\u00b3') && ScriptTokens.glued('\u2086'),
                "a Unicode super/subscript digit is glued too (m\u00b2, cm\u00b3, Cu\u2086)");
        check(!ScriptTokens.glued('\u207b'),
                "U+207B, the superscript minus of W\u00b7m\u207b\u00b9, is a symbol and is NOT glued today:"
                + " Word has never been asked whether it may break between \u207b and \u00b9,"
                + " so the character keeps today's behaviour and the question stays open"
                + " (docs/layout-parity-target.md 第 25.5 节, tools/word-break-truth.ps1 needs a super-minus case)");
        check(!ScriptTokens.glued('\u6d1e') && !ScriptTokens.glued('\u7387'),
                "Chinese characters are not glued: Word breaks freely between them");
        check(!ScriptTokens.glued(' ') && !ScriptTokens.glued('\t') && !ScriptTokens.glued('\u00a0'),
                "a space is always a break point");
        check(!ScriptTokens.glued('-'),
                "\"-\" stays breakable: 27 lines of Word's own PDF end on a hyphen");
        check(!ScriptTokens.glued('/'),
                "\"/\" is not glue: Word's slash-edge test broke before the token, not at the slash");
        check(!ScriptTokens.glued('.'), "a period is not glue");
        check(!ScriptTokens.glued('\u3001') && !ScriptTokens.glued('\uff0c'),
                "CJK punctuation is not glue (line-initial punctuation is kinsoku, a separate rule)");

        // --- where a token begins and ends ---
        int ag = THESIS.indexOf('A');
        checkAt("token starts at the A of Ag3Sn", ag, ScriptTokens.start(THESIS, ag + 2));
        checkAt("token starts at the A even asked from its last character", ag,
                ScriptTokens.start(THESIS, ag + 4));
        checkAt("token ends one past the n of Ag3Sn", ag + 5, ScriptTokens.end(THESIS, ag + 2));
        checkAt("a token that runs to the end of the paragraph ends at the paragraph end",
                6, ScriptTokens.end("Cu6Sn5", 99));
        checkAt("the punctuation in front of the token is not part of it", 0,
                ScriptTokens.start(THESIS, 0));
        checkAt("a paragraph that opens with the token starts at 0", 0,
                ScriptTokens.start("Ag3Sn\u5206\u5e03", 2));

        // --- the rule the audits check: where inside a token the phone must not break ---
        int forbidden = 0;
        for (int i = ag + 1; i < ag + 5; i++) if (ScriptTokens.breakForbiddenInside(THESIS, i)) forbidden++;
        checkAt("positions inside Ag3Sn where Word forbids a break", 4, forbidden);
        check(!ScriptTokens.breakForbiddenInside(THESIS, ag),
                "before the A of Ag3Sn a break is allowed (a Chinese character sits there)");
        check(!ScriptTokens.breakForbiddenInside(THESIS, ag + 5),
                "after the n of Ag3Sn a break is allowed (a Chinese character follows)");
        check(!ScriptTokens.breakForbiddenInside(THESIS, 0), "offset 0 is not inside anything");

        // --- the token a w:vertAlign run sits in (the source text stores plain digits: the formatting
        //     carries the script, so the range of that run comes from its span, not from the characters) ---
        String twoSubs = "Cu6Sn5";                                     // Cu + sub 6 + Sn + sub 5
        checkAt("Cu6Sn5 is one token from its first character", 0, ScriptTokens.start(twoSubs, 4));
        checkAt("and ends at the end of the paragraph", 6, ScriptTokens.end(twoSubs, 4));
        check(ScriptTokens.breakForbiddenInside(twoSubs, 3) && ScriptTokens.breakForbiddenInside(twoSubs, 5),
                "every position inside Cu6Sn5 forbids a break, both subscripts included");

        // --- the Unicode super/subscript spelling, which the characters do identify: the engine draws
        //     those glyphs at full size (WordScriptSpan.isUnicode), so they need no span of their own ---
        String unicode = "Cu\u2086Sn\u2085";
        checkAt("the script range starts at the U+2086", 2, ScriptTokens.firstScript(unicode, 0, 6));
        checkAt("and ends one past the U+2085", 6, ScriptTokens.lastScriptEnd(unicode, 0, 6));
        checkAt("a token with no script has no script range", -1,
                ScriptTokens.firstScript("Cu-Sn", 0, 2));

        String twoParts = "Cu-Sn";
        checkAt("the hyphen ends the first token", 2, ScriptTokens.end(twoParts, 0));
        checkAt("and the second token starts after it", 3, ScriptTokens.start(twoParts, 3));

        // --- the glue plan: which range a script span has to be stretched to -------------------------
        // Pure arithmetic (no Android text classes), so it is checkable here and not only on the phone.
        // DocxTextLayout.glueScriptTokens hangs exactly these ranges, and tools/script-edge-audit.py
        // counts the positions in the thesis that need one (53 of them, all w:vertAlign).
        String ag3sn = THESIS;                                   // 、孔洞率、Ag3Sn分布, Ag + sub 3 + Sn
        int[] plan = ScriptTokens.planGluedRanges(ag3sn,
                new int[] { ag3sn.indexOf("Ag"), ag3sn.indexOf('3'), ag3sn.indexOf("Sn") },
                new int[] { ag3sn.indexOf('3'), ag3sn.indexOf("Sn"), ag3sn.indexOf("Sn") + 2 });
        checkAt("Ag3Sn: one range, not three", 2, plan.length);
        checkAt("  it starts at the A", ag, plan[0]);
        checkAt("  and ends one past the n", ag + 5, plan[1]);

        String cu6sn5 = "Cu6Sn5";                                 // Cu + sub 6 + Sn + sub 5
        plan = ScriptTokens.planGluedRanges(cu6sn5,
                new int[] { 0, 2, 3, 5 }, new int[] { 2, 3, 5, 6 });
        checkAt("Cu6Sn5: the two subscripts collapse into one span", 2, plan.length);
        checkAt("  over the whole token", 0, plan[0]);
        checkAt("  and nothing else", 6, plan[1]);

        String cite = "中\u4e00[1]\u4e2d\u4e2d";                  // superscript "[1]" between Chinese
        plan = ScriptTokens.planGluedRanges(cite, new int[] { 2 }, new int[] { 5 });
        checkAt("a superscript citation keeps its brackets: left", 2, plan[0]);
        checkAt("  because Word bills the whole run at the script size", 5, plan[1]);

        String hy = "Cu6-Sn5";                                    // Cu + sub 6 + "-Sn" + sub 5
        plan = ScriptTokens.planGluedRanges(hy,
                new int[] { 0, 2, 3, 6 }, new int[] { 2, 3, 5, 7 });
        checkAt("Cu6-Sn5: two spans, because Word may break after the hyphen", 4, plan.length);
        checkAt("  the first ends at the hyphen", 3, plan[1]);
        checkAt("  the second starts at it", 3, plan[2]);
        checkAt("  and runs to the last digit", 7, plan[3]);
        for (int i = 2; i < plan.length; i += 2)
            check(plan[i] >= plan[i - 1], "glued ranges never overlap out of order at " + i);

        String solo = "\u4e2d1\u4e2d";                            // digit run with Chinese on both sides
        plan = ScriptTokens.planGluedRanges(solo, new int[] { 1 }, new int[] { 2 });
        checkAt("a token already between Chinese needs no widening: left", 1, plan[0]);
        checkAt("  and no widening: right", 2, plan[1]);

        System.out.println("SUMMARY " + count
                + " token-boundary assertions passed (Word truth: tools/word-break-truth.ps1,"
                + " tools/break-class-truth.py; span-shape measurement: tools/script-token-lab.ps1).");
    }
}