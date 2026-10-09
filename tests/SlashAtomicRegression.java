package com.rikkahub.wordlite;

// Host assertions for the "/" rule: which stretches of a paragraph DocxTextLayout keeps unbroken.
//
// The device half of this change is a ReplacementSpan (AtomicRunSpan), which cannot be exercised on a
// host. What CAN be pinned here is the decision it is built from -- which ranges qualify -- because
// that is where the rule's bounds live. A range that is too wide glues a token no line can hold (the
// phone measured a 126-character glued run laid out 1119 px in a 567 px column), and a range opened or
// closed on a "/" puts a span edge exactly where the forbidden break lives, since a ReplacementSpan
// edge is itself a break opportunity for StaticLayout.
//
// Truth: Word's own exported PDF, priced line by line by tools/slash-break-truth.py --
//   59 "/" occurrences inside the compared lines, 0 line ends after one; 6 lines where Word had room
//   for the text up to the slash and took none of it (para 94 p10 line 1, free 77.87 px against 36.00
//   px to the slash; para 113 p13 line 1, free 78.87 px against 35.00 px; para 357 p27 line 1, free
//   63.86 px against 23.00 px); 0 lines where it broke at the slash with room to.
// We produce 3 such breaks (device blocks 87 p8, 95 p10, 114 p13), all with a letter or digit on both
// sides of the slash -- which is the scope these assertions hold the rule to.
public class SlashAtomicRegression {
    private static final int COLUMN_PX = 567;      // the thesis text column, 8504 twips at 96 dpi

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        System.out.println("  PASS " + what);
    }

    private static String range(String text, int[] r) { return text.substring(r[0], r[1]); }

    private static java.util.ArrayList<int[]> runs(String text) {
        return DocxTextLayout.slashAtomicRuns(text, COLUMN_PX, new DocxTextLayout.RunWidth() {
            @Override public float of(int start, int end) { return 8.4f * (end - start); }
        });
    }

    public static void main(String[] args) {
        // The break the user sees on page 8: we cut "…P-Cu/SB/" and put "Cu夹层" on the next line,
        // Word carries "…Cu/SB/P-Cu/SB/Cu" whole and breaks at the Chinese character after it.
        String page8 = "构建Cu/SB/P-Cu/SB/Cu夹层结构";
        java.util.ArrayList<int[]> r = runs(page8);
        check(r.size() == 1, "the hyphen inside a slash token is a joiner, got " + r.size()
                + " ranges for " + page8);
        check(range(page8, r.get(0)).equals("Cu/SB/P-Cu/SB/Cu"),
                "the whole token glues, got " + range(page8, r.get(0)));
        check(page8.charAt(r.get(0)[1] - 1) == 'u' && page8.charAt(r.get(0)[1]) == '夹',
                "the range closes on the last letter, so the break lands after the whole token");
        check(page8.indexOf('-') > r.get(0)[0] && page8.indexOf('-') < r.get(0)[1] - 1,
                "no line may open on a hyphen: the '-' sits inside the range, not at its edge");

        // The other four slash-plus-hyphen tokens in the thesis, same evidence: whole in one Word line.
        String ag = "多孔Cu-Sn/Ag-Sn反应";
        java.util.ArrayList<int[]> ra = runs(ag);
        check(ra.size() == 1 && range(ag, ra.get(0)).equals("Cu-Sn/Ag-Sn"),
                "Cu-Sn/Ag-Sn glues whole, leading joiner trimmed off the edge, got "
                        + (ra.isEmpty() ? "none" : range(ag, ra.get(0))));
        String bi = "多孔Cu/Sn-58Bi接头";
        java.util.ArrayList<int[]> rb = runs(bi);
        check(rb.size() == 1 && range(bi, rb.get(0)).equals("Cu/Sn-58Bi"),
                "Cu/Sn-58Bi glues whole, got " + (rb.isEmpty() ? "none" : range(bi, rb.get(0))));
        String cusn = "实现Cu/Cu-Sn金属";
        java.util.ArrayList<int[]> rc = runs(cusn);
        check(rc.size() == 1 && range(cusn, rc.get(0)).equals("Cu/Cu-Sn"),
                "Cu/Cu-Sn glues whole, got " + (rc.isEmpty() ? "none" : range(cusn, rc.get(0))));

        // A hyphen with no slash in its token stays breakable -- Word ends a line after one twice
        // ("SiCHigh-|Temperat", "(5):727-|741."), and neither of those tokens holds a slash.
        check(runs("SiCHigh-Temperature").isEmpty(), "a bare hyphenated word keeps its break after '-'");
        check(runs("(5):727-741.").isEmpty(), "a page range keeps its break after '-'");

        // Every "/" inside a range has a letter or digit on both sides, so the rule cannot reach a
        // slash that sits against punctuation. Three of the document's 77 slashes are of that kind
        // ("（IMCs）/Cu", "助焊膏//深圳") and the phone breaks none of them.
        String punct = "金属间化合物（IMCs）/Cu互连";
        check(runs(punct).isEmpty(), "a slash against a full-width bracket stays breakable: " + punct);
        String doubleSlash = "助焊膏//深圳凯利顺";
        check(runs(doubleSlash).isEmpty(), "a slash between two Chinese characters stays breakable");

        // "化学镀Ni/浸Au": the slash has a letter on the left and a Chinese character on the right.
        // Word was never seen deciding this one, so the rule does not claim it -- an open case, pinned
        // here so a later round cannot lose track of it.
        String mixed = "化学镀Ni/浸Au";
        java.util.ArrayList<int[]> m = runs(mixed);
        check(m.isEmpty(), "Ni/浸 keeps its break opportunity (known open case, no Word truth for it)");

        // A trailing slash must not sit at the end of a range: an edge there is the break itself.
        String trailing = "对Cu/SB/夹层";
        r = runs(trailing);
        check(r.size() == 1 && range(trailing, r.get(0)).equals("Cu/SB"),
                "the range stops before the trailing slash, got " + range(trailing, r.get(0)));
        check(trailing.charAt(r.get(0)[0]) == 'C' && trailing.charAt(r.get(0)[1] - 1) == 'B',
                "a range opens and closes on a letter or digit");

        // Two qualifying slashes in one run glue it as one token, not two: "Cu/Sn/Ag" is a single
        // unbreakable unit (the decisive para 357 case, free 63.86 px against 23.00 px to the slash).
        String three = "需要建Cu/Sn/Ag体系";
        r = runs(three);
        check(r.size() == 1 && range(three, r.get(0)).equals("Cu/Sn/Ag"),
                "Cu/Sn/Ag glues as one range, got " + (r.isEmpty() ? "none" : range(three, r.get(0))));

        // The width guard. On the phone a glued run wider than the column went onto one line 1119 px
        // wide in a 567 px column, which is a worse defect than the break the glue removes.
        StringBuilder wide = new StringBuilder("需要建");
        for (int i = 0; i < 12; i++) wide.append("CuSO4NaClAgKClMgCl2/");
        wide.append("体系");
        final int[] asked = new int[1];
        java.util.ArrayList<int[]> none = DocxTextLayout.slashAtomicRuns(wide.toString(), COLUMN_PX,
                new DocxTextLayout.RunWidth() {
                    @Override public float of(int start, int end) {
                        asked[0]++;
                        return 9.0f * (end - start);          // 216 characters, about 1944 px
                    }
                });
        check(none.isEmpty(), "a run wider than the column is left alone, got " + none.size());
        check(asked[0] > 0, "the width of a candidate run is actually asked before it is glued");

        // Same text, width that fits: the guard must not be what stops the rule.
        java.util.ArrayList<int[]> some = DocxTextLayout.slashAtomicRuns(wide.toString(), COLUMN_PX,
                new DocxTextLayout.RunWidth() {
                    @Override public float of(int start, int end) { return 0.0f; }
                });
        check(!some.isEmpty(), "with room in the column the same runs do qualify");

        // Nothing to glue in plain Chinese, plain Latin, or a lone slash.
        check(runs("这段中文里没有斜杠").isEmpty(), "Chinese without a slash has no ranges");
        check(runs("SAC305 浸渗完整性").isEmpty(), "a Latin run with no slash has no ranges");
        check(runs("/").isEmpty() && runs("a/").isEmpty() && runs("/a").isEmpty(),
                "a slash with nothing on one side has no ranges");

        // The document's own longest glued run: 12 characters, about 101 px, nowhere near the guard.
        String longest = "Cu6Sn5/Cu3Sn";
        r = DocxTextLayout.slashAtomicRuns(longest, COLUMN_PX, new DocxTextLayout.RunWidth() {
            @Override public float of(int start, int end) { return 8.4f * (end - start); }
        });
        check(r.size() == 1 && r.get(0)[0] == 0 && r.get(0)[1] == longest.length(),
                "Cu6Sn5/Cu3Sn glues whole, the longest run in the thesis");

        System.out.println("SlashAtomicRegression: OK");
    }
}
