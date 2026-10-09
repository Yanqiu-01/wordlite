package com.rikkahub.wordlite;

// Host assertions for Word's hanging punctuation (w:overflowPunct).
//
// These pin the arithmetic of DocxTextLayout.hangSplit(), which is the part that does not need a
// device: where a trailing closing mark lands. The truth is Word's own PDF, read character by
// character by tools/hang-truth.py (sha256 AB298AC416DCFC72855645E5AD8E472ABDA3DFFC9A6B10925A4FAA1396E761A1):
//
//   370 body lines start on the left margin at 12 pt; 22 of them end with the last character's box
//   11.42 to 12.44 pt PAST Word's right margin - one full em of one full-width mark, never two, and
//   only on closing marks (，。、；).
//
// What the rule is NOT: the mark's ink does not have to fit inside the column. A justified Chinese
// line has already been spread to the column by then, so "ink fits inside" can never be true and we
// dropped the mark to the next line, running one character short on every one of those 22 lines.
public class HangPunctuationRegression {
    private static final double COL_PX = 566.93;   // Word's column: 8504 twips = 425.2 pt at 96 dpi

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        System.out.println("  PASS " + what);
    }

    public static void main(String[] args) {
        double em = 16.0;                       // a 12 pt full-width mark, PageGeometry.points(12)

        // A justified line already at the margin: the mark claims nothing inside and hangs all of it.
        float[] full = DocxTextLayout.hangSplit((float) COL_PX, (float) COL_PX, (float) em);
        check(full[0] == 0f, "a mark on a full line claims nothing inside, got " + full[0]);
        // The line's text ends on the margin; the mark is measured at roomInside and drawn at
        // roomInside + overhang, so the ink ends roomInside + overhang past the margin.
        double drawnEnd = COL_PX + full[0] + full[1];
        check(Math.abs(drawnEnd - (COL_PX + em)) < 0.02,
                "the drawn line ends at " + drawnEnd + ", Word's own right edge is " + (COL_PX + em));
        // Word's measured overflow, in pt: 11.42 .. 12.44 pt == 15.23 .. 16.59 px.
        check(full[1] >= 15.2 && full[1] <= 16.6, "overflow outside the margin is " + full[1] + " px");

        // A line with room left inside the column: the mark spends that room and hangs only the rest.
        float[] half = DocxTextLayout.hangSplit((float) (COL_PX - 6f), (float) COL_PX, (float) em);
        check(Math.abs(half[0] - 6f) < 0.01f, "the mark spends the 6 px still open, got " + half[0]);
        check(Math.abs(half[1] - 10f) < 0.01f, "and hangs the remaining 10 px, got " + half[1]);
        check(Math.abs((COL_PX - 6f + half[0] + half[1]) - (COL_PX - 6f + em)) < 0.02,
                "a line 6 px short ends its mark 10 px past the margin, not a full 16");

        // Room is never negative. Zero is legitimate (the mark starts exactly on the margin)
        // and dropMarksThatMoved() keeps a zero-width mark from ever landing mid-line.
        float[] over = DocxTextLayout.hangSplit((float) (COL_PX + 40f), (float) COL_PX, (float) em);
        check(over[0] == 0f, "a line already past the margin gives the mark no room, got " + over[0]);
        check(Math.abs(over[1] - em) < 0.01, "and the whole mark hangs, got " + over[1]);

        // A narrower mark (the closing quote is drawn inside its em) still cannot push the line out
        // further than its own advance.
        float[] small = DocxTextLayout.hangSplit((float) COL_PX, (float) COL_PX, 8f);
        check(Math.abs(small[1] - 8f) < 0.01f, "a mark half an em wide hangs its whole 8 px, got " + small[1]);

        System.out.println("HangPunctuationRegression: Word's one-em mark overflow pinned, 4 groups");
    }
}