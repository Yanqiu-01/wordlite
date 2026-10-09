package com.rikkahub.wordlite;

// Host assertions for the UAX#14 line-break rules this engine takes from the reference table, one
// assertion per rule, each naming its rule number and the reference file it was read out of.
//
//   reference  icu4c/source/data/brkitr/rules/line.txt            (ICU's own RBBI source for UAX#14,
//                which is what the phone's StaticLayout runs through the device's libicu)
//              Unicode LineBreak.txt 15.1                          (the class of every character)
//              docs/line-break-rules-uax14.md                      (the rule-by-rule comparison)
//   truth      Word's own exported PDF of tests/samples/input-liu.docx, read line by line by
//                py tools/break-agreement.py and py tools/break-rule-yield.py
//
// The device half of every rule here is an AtomicRunSpan, which a host cannot exercise. What is pinned
// below is the decision the span is built from -- which ranges qualify -- because that is where the
// rule's reach and its guards live.
public class Uax14RulesRegression {
    private static final int COLUMN_PX = 567;      // the thesis text column, 8504 twips at 96 dpi

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        System.out.println("  PASS " + what);
    }

    private static String range(String text, int[] r) { return text.substring(r[0], r[1]); }

    private static java.util.ArrayList<int[]> runs(String text) {
        return DocxTextLayout.numericUnitRuns(text, COLUMN_PX, new DocxTextLayout.RunWidth() {
            @Override public float of(int start, int end) { return 8.4f * (end - start); }
        });
    }

    public static void main(String[] args) throws Exception {
        // ---- LB25 (numbers keep their postfix) applied across the blank: the rule this change lands.
        // line.txt LB25 is `($PR|$PO)? ($OP|$HY)? $IS? $NU ($NU|$SY|$IS)* ($CL|$CP)? ($PR|$PO)?`, which
        // holds "200℃" together but stops at a blank; LB18 (`$SP` breaks) then lets ICU end a line on
        // "200 ". Word does not: py tools/break-rule-yield.py row "NU SP PO" counts 25 such seams in
        // the compared paragraphs, Word ends a line at 0 of them, we ended at 2.
        String p154 = "分别测量室温和300 ℃强度，给出每个样品";
        java.util.ArrayList<int[]> r = runs(p154);
        check(r.size() == 1, "one number-plus-unit range, got " + r.size() + " for " + p154);
        check(range(p154, r.get(0)).equals("300 \u2103"),
                "the number, the blank and the unit glue as one range, got " + range(p154, r.get(0)));
        // Word's own break on this very line sits right after the unit ("300 \u2103|强度"), so moving the
        // seam by the width of the unit is the whole of the fix.
        check(p154.charAt(r.get(0)[0] - 1) == '和' && p154.charAt(r.get(0)[1]) == '强',
                "the range starts on the digit and closes on the unit, leaving Word's seam at its edge");

        // The para 94 / page 10 case, the one that dragged 14 break points of one paragraph and one
        // mis-paged paragraph: "…接头在200 |\u2103、15 d条件下…". The following 、 stays outside the range on
        // purpose -- LB13 (`x CL`) already forbids a break before it, and pulling it in would take the
        // mark away from the w:overflowPunct pass, which hangs exactly these marks (see §28).
        String p94 = "因此这组数据支持接头在200 \u2103、15 d条件下仍";
        r = runs(p94);
        check(r.size() == 1 && range(p94, r.get(0)).equals("200 \u2103"),
                "the case Word never cuts also glues, got " + (r.isEmpty() ? "none" : range(p94, r.get(0))));
        check(p94.charAt(r.get(0)[1]) == '\u3001',
                "the closing mark stays outside the range, so the hanging pass can still reach it");

        // Two clusters in one caption, both collected: "（c）200 \u2103与（d）300 \u2103试样截面".
        String two = "（c）200 \u2103与（d）300 \u2103试样截面";
        r = runs(two);
        check(r.size() == 2, "both clusters in one line are collected, got " + r.size());
        for (int[] one : r) {
            check(Character.isWhitespace(two.charAt(one[0] + range(two, one).lastIndexOf(' '))),
                    "every collected range holds the blank it protects");
            check(two.charAt(one[1] - 1) == '\u2103', "a range closes on the postfix, nowhere else");
            check(one[0] == 0 || !isNumberChar(two.charAt(one[0] - 1)),
                    "a range opens at the head of the number, not in the middle of it");
        }

        // ---- LB25 without a blank: already one token in the reference table, so nothing is collected.
        // Gluing these would add two span edges -- a ReplacementSpan edge IS a break opportunity for
        // StaticLayout -- and remove no seam. 29 of the document's 70 number-plus-unit sites look like
        // this ("强度保持率约为93%", "100%填充").
        check(runs("强度保持率约为93%，该比例").isEmpty(),
                "LB25 already holds an unspaced 93%: no range, and no new span edge");
        check(runs("Mode 3实现100%填充，且").isEmpty(), "an unspaced 100% stays as it is");

        // ---- LB23 (`NU x AL`) / LB24 across a blank: measured as width, NOT as a rule, so "37.68 MPa"
        // must stay breakable. Word ends a line at 2 of its 75 "NU SP AL" slots and we at 2 -- it cuts
        // "℃下时效1000 |h后" itself -- and 13 of the 14 "at a space" disagreements are lines where we
        // hold fewer characters than Word fits (py tools/line-capacity.py). Gluing this family would
        // have cost the agreements it destroyed and fixed nothing.
        check(runs("告的平均剪切强度为37.68 MPa，最高").isEmpty(),
                "LB23 across a blank stays breakable: Word itself cuts 1000 |h后");

        // ---- LB7 (`x SP`): a range must not end in front of a blank, or the next line can start on
        // one. The document has one such site, "在250 \u2103 Sn池中", and it is left alone.
        check(runs("将合格多孔Cu在250 \u2103 Sn池中").isEmpty(),
                "LB7: a cluster followed by a blank is not glued (an edge there would be a break before a space)");

        // ---- LB25's prefix walk-back: a signed or bracketed number glues from its prefix, so the left
        // edge of the range lands where the reference table allows a break (LB31), not between a
        // prefix and its digits.
        String signed = "约变化 +3 %、随后";
        r = runs(signed);
        check(r.size() == 1 && range(signed, r.get(0)).equals("+3 %"),
                "the prefix joins the number instead of sitting against a span edge, got "
                        + (r.isEmpty() ? "none" : range(signed, r.get(0))));

        // A postfix with no digits in front of it is not a number: "（%）" and a stray sign glue nothing.
        check(runs("括号里的百分号（%）不算").isEmpty(), "a postfix with no number in front glues nothing");
        check(runs("这段中文里没有数字也没有单位").isEmpty(), "plain Chinese glues nothing");
        check(runs("SiCHigh-Temperature").isEmpty(),
                "LB21/LB21a: a hyphenated Latin word is left to its own seam (Word ends 2 lines on '-')");
        check(runs("构建Cu/SB/P-Cu/SB/Cu夹层").isEmpty(),
                "the slash rule owns slash runs; this rule adds no range there");

        // ---- The one defect a host can settle without a layout: where the span edges land. StaticLayout
        // treats a ReplacementSpan edge as a break opportunity, so an edge in front of a blank is a line
        // that starts on a space, and an edge behind a blank is the very seam this rule exists to close.
        // LB7 (`x SP`) is what forbids the first; the sweep reads it off every paragraph of the thesis.
        sweepDocument(args.length > 0 ? args[0] : "tests/samples/input-liu.docx");

        // ---- The width guard, same lesson the slash rule learned: a glued cluster wider than the
        // column is a worse defect than the break the glue removes.
        StringBuilder wide = new StringBuilder("室温");
        for (int k = 0; k < 40; k++) wide.append("1200 \u2103");
        wide.append("下");
        final int[] asked = new int[1];
        java.util.ArrayList<int[]> none = DocxTextLayout.numericUnitRuns(wide.toString(), COLUMN_PX,
                new DocxTextLayout.RunWidth() {
                    @Override public float of(int start, int end) {
                        asked[0]++;
                        return 9.0f * (end - start);
                    }
                });
        check(none.isEmpty(), "a cluster wider than the column is dropped, got " + none.size());
        check(asked[0] > 0, "the width of a candidate cluster is actually asked before it is glued");
        java.util.ArrayList<int[]> fits = DocxTextLayout.numericUnitRuns(wide.toString(), COLUMN_PX,
                new DocxTextLayout.RunWidth() {
                    @Override public float of(int start, int end) { return 0.0f; }
                });
        check(!fits.isEmpty(), "with room in the column the same clusters do qualify");

        System.out.println("Uax14RulesRegression: OK");
    }

    /** The characters LB25 lets stand inside a number, mirrored from the engine's own walk-back. */
    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == ',' || c == ':' || c == '-'
                || c == '\u2013' || c == '+' || c == '$' || c == '\u00a5' || c == '\u20ac'
                || c == '(' || c == '\uff08';
    }
    /**
     * The same decision, read off every paragraph of the thesis. The cases above pin where a range may
     * open and close; this sweep is what says the rule cannot misbehave somewhere else either. Two
     * properties are checked on every collected range, both about where a span edge would land, because
     * for StaticLayout an edge IS a break opportunity:
     *   - nothing may end in front of a blank -- LB7 (x SP) already forbids a break there, so an edge
     *     there buys nothing and can hand the next line a leading space;
     *   - nothing may open right behind a blank -- that blank stays breakable under LB18, which would
     *     make the glue a no-op while still costing an edge.
     * Overlapping ranges are rejected too, since two AtomicRunSpans over one range are billed once.
     */
    private static void sweepDocument(String path) throws Exception {
        String xml;
        java.util.zip.ZipFile docx = new java.util.zip.ZipFile(path);
        try {
            java.io.InputStream in = docx.getInputStream(docx.getEntry("word/document.xml"));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1 << 16];
            int got;
            try { while ((got = in.read(buffer)) > 0) out.write(buffer, 0, got); } finally { in.close(); }
            xml = new String(out.toByteArray(), "UTF-8");
        } finally {
            docx.close();
        }
        int paragraphs = 0, withRange = 0, ranges = 0;
        for (String body : xml.split("</w:p>")) {
            String text = paragraphText(body);
            if (text.length() == 0) continue;
            paragraphs++;
            java.util.ArrayList<int[]> found = DocxTextLayout.numericUnitRuns(text, COLUMN_PX,
                    new DocxTextLayout.RunWidth() {
                        @Override public float of(int start, int end) { return 0f; }
                    });
            if (found.isEmpty()) continue;
            withRange++;
            int previousEnd = -1;
            for (int[] r : found) {
                ranges++;
                String one = text.substring(r[0], r[1]);
                if (r[0] <= previousEnd)
                    throw new AssertionError("two ranges overlap at offset " + r[0] + ": " + one);
                previousEnd = r[1];
                if (one.indexOf(' ') < 0 && one.indexOf('　') < 0)
                    throw new AssertionError("a collected range holds no blank to protect: " + one);
                if (r[1] < text.length() && Character.isWhitespace(text.charAt(r[1])))
                    throw new AssertionError("a span edge would sit in front of a blank (LB7): " + one);
                if (r[0] > 0 && Character.isWhitespace(text.charAt(r[0] - 1)))
                    throw new AssertionError("a range opens behind a breakable blank, so the glue buys "
                            + "nothing there: " + one);
                if (!digitInside(text, r[0], r[1]))
                    throw new AssertionError("a range without a digit inside: " + one);
                if (!closesOnPostfix(text.charAt(r[1] - 1)))
                    throw new AssertionError("a range does not close on a numeric postfix: " + one);
            }
        }
        check(paragraphs > 200, "the sweep reads the whole thesis: " + paragraphs + " paragraphs");
        check(withRange > 0 && ranges >= withRange, "the sweep finds real number-plus-unit sites: "
                + ranges + " ranges in " + withRange + " of " + paragraphs + " paragraphs");
        System.out.println("  PASS swept " + paragraphs + " paragraphs: " + ranges
                + " numeric-unit ranges, no edge in front of or behind a blank");
    }

    /** The characters of one w:p element, in document order, with the XML entities resolved. */
    private static String paragraphText(String body) {
        StringBuilder text = new StringBuilder();
        int at = 0;
        while (true) {
            int open = body.indexOf("<w:t", at);
            if (open < 0) break;
            int close = body.indexOf('>', open);
            int end = body.indexOf("</w:t>", close);
            if (close < 0 || end < 0) break;
            if (body.charAt(close - 1) != '/') text.append(unescape(body.substring(close + 1, end)));
            at = end + 6;
        }
        return text.toString();
    }

    private static String unescape(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static boolean digitInside(String text, int start, int end) {
        for (int i = start; i < end; i++) if (text.charAt(i) >= '0' && text.charAt(i) <= '9') return true;
        return false;
    }

    /** The six LB25 postfixes the engine accepts, kept in step with the engine's own list. */
    private static boolean closesOnPostfix(char c) {
        return c == '℃' || c == '℉' || c == '°'
                || c == '%' || c == '‰' || c == '‱' || c == '％';
    }

}
